package com.watchbabymonitor.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.watchbabymonitor.mobile.MainActivity
import com.watchbabymonitor.mobile.R
import com.watchbabymonitor.mobile.audio.AudioPlayer
import com.watchbabymonitor.shared.Constants
import com.watchbabymonitor.shared.ControlCommand
import com.watchbabymonitor.shared.Pcm16
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private val TAG = Constants.logTag("ListenerService")

/**
 * 라이브 듣기: 워치에 `/control START` → 워치가 연 `/audio` 채널을 받아 AudioTrack 으로 재생.
 * foreground(type=mediaPlayback) 라 앱을 닫아도 계속 들린다 (ADR 004).
 *
 * 시작 [start] 은 앱 화면에서만 (백그라운드 포그라운드 서비스 시작 제한), 정지 [stop].
 */
class ListenerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val channelClient by lazy { Wearable.getChannelClient(this) }
    private val messageClient by lazy { Wearable.getMessageClient(this) }

    private var session: Job? = null
    private var channelOpened: CompletableDeferred<ChannelClient.Channel>? = null

    @Volatile
    private var activeChannel: ChannelClient.Channel? = null

    @Volatile
    private var stopRequested = false

    private val channelCallback = object : ChannelClient.ChannelCallback() {
        override fun onChannelOpened(channel: ChannelClient.Channel) {
            if (channel.path != Constants.Paths.AUDIO) return
            Log.i(TAG, "channel opened by ${channel.nodeId}")
            val waiting = channelOpened
            if (waiting == null || !waiting.complete(channel)) {
                Log.w(TAG, "unexpected /audio channel, closing")
                channelClient.close(channel)
            }
        }

        override fun onChannelClosed(channel: ChannelClient.Channel, closeReason: Int, appErrorCode: Int) {
            Log.i(TAG, "channel closed reason=$closeReason app=$appErrorCode")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            requestStop()
            return START_NOT_STICKY
        }
        if (session?.isActive == true) return START_NOT_STICKY

        ensureChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        stopRequested = false
        session = scope.launch { runSession() }
        return START_NOT_STICKY
    }

    private fun requestStop() {
        stopRequested = true
        // 끊긴 연결에서 read 가 멈춰 있을 수 있으므로 채널을 먼저 닫는다 (CLAUDE.md §8)
        activeChannel?.let { channelClient.close(it) }
        val s = session
        if (s == null || !s.isActive) {
            stopSelf()
        } else {
            s.cancel()
        }
    }

    private suspend fun runSession() {
        LiveStatus.connecting()
        var error: String? = null
        var watchNodeId: String? = null
        try {
            channelClient.registerChannelCallback(channelCallback).await()

            val node = Wearable.getNodeClient(this).connectedNodes.await().firstOrNull()
                ?: throw LiveException("연결된 워치가 없어요")
            watchNodeId = node.id
            LiveStatus.update { it.copy(watchName = node.displayName) }

            val opened = CompletableDeferred<ChannelClient.Channel>()
            channelOpened = opened

            val reply = withTimeout(Constants.CONTROL_REQUEST_TIMEOUT_MS) {
                messageClient.sendRequest(node.id, Constants.Paths.CONTROL, ControlCommand.Start.toBytes()).await()
            }.toString(Charsets.UTF_8)
            Log.i(TAG, "START -> $reply")
            when (reply) {
                Constants.CONTROL_REPLY_OK -> Unit
                Constants.CONTROL_REPLY_NOT_MONITORING -> throw LiveException("워치에서 모니터링을 먼저 시작해 주세요")
                else -> throw LiveException("워치 응답: $reply")
            }

            val channel = withTimeout(Constants.Stream.CHANNEL_OPEN_TIMEOUT_MS) { opened.await() }
            activeChannel = channel
            play(channel)
            if (!stopRequested) error = "워치에서 스트리밍이 끝났어요"
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "watch did not respond", e)
            error = "워치 응답 없음"
        } catch (e: CancellationException) {
            throw e
        } catch (e: LiveException) {
            Log.w(TAG, "live session ended: ${e.message}")
            error = e.message
        } catch (e: Exception) {
            if (!stopRequested) {
                Log.e(TAG, "live session failed", e)
                error = "오류: ${e.message ?: e.javaClass.simpleName}"
            }
        } finally {
            withContext(NonCancellable) { cleanup(watchNodeId, error) }
        }
    }

    /** 채널이 닫히거나 끊길 때까지 재생. */
    private suspend fun play(channel: ChannelClient.Channel) = withContext(Dispatchers.IO) {
        val input = channelClient.getInputStream(channel).await()
        val player = AudioPlayer()
        // read 스레드와 watchdog 이 함께 접근
        val lastDataAt = AtomicLong(SystemClock.elapsedRealtime())
        val disconnected = AtomicBoolean(false)

        // read 가 조용히 멈추는 경우 감지 (CLAUDE.md §8)
        val watchdog = launch {
            while (true) {
                delay(WATCHDOG_INTERVAL_MS)
                val idle = SystemClock.elapsedRealtime() - lastDataAt.get()
                if (idle > Constants.Stream.DISCONNECT_TIMEOUT_MS) {
                    Log.w(TAG, "no data for ${idle}ms, closing channel")
                    disconnected.set(true)
                    channelClient.close(channel)
                    break
                }
                if (idle > Constants.Stream.STALL_TIMEOUT_MS) {
                    LiveStatus.update { it.copy(phase = LivePhase.STALLED) }
                }
            }
        }

        var totalBytes = 0L
        try {
            input.use {
                val buf = ByteArray(READ_BUFFER)
                var windowStart = SystemClock.elapsedRealtime()
                var windowBytes = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    val now = SystemClock.elapsedRealtime()
                    lastDataAt.set(now)
                    player.write(buf, n)
                    totalBytes += n
                    windowBytes += n

                    val elapsed = now - windowStart
                    if (elapsed >= STATS_INTERVAL_MS) {
                        val kbps = (windowBytes * 8 / elapsed).toInt()
                        LiveStatus.update {
                            it.copy(
                                phase = LivePhase.PLAYING,
                                backlogMs = player.backlogMs,
                                kbps = kbps,
                                underruns = player.underruns,
                                droppedMs = Pcm16.bytesToMs(player.droppedBytes),
                                resyncs = player.resyncs,
                            )
                        }
                        windowStart = now
                        windowBytes = 0
                    }
                }
            }
        } catch (e: IOException) {
            // watchdog 이 채널을 닫으면 read 가 ChannelIOException 으로 끝난다
            if (!disconnected.get()) throw e
        } finally {
            watchdog.cancel()
            Log.i(TAG, "played ${Pcm16.bytesToMs(totalBytes) / 1000}s, dropped ${Pcm16.bytesToMs(player.droppedBytes)}ms, resyncs=${player.resyncs}, underruns=${player.underruns}")
            player.release()
        }
        if (disconnected.get()) throw LiveException("연결 끊김 (${Constants.Stream.DISCONNECT_TIMEOUT_MS / 1000}초 동안 소리 없음)")
    }

    private suspend fun cleanup(watchNodeId: String?, error: String?) {
        channelOpened = null
        activeChannel?.let {
            try {
                channelClient.close(it).await()
            } catch (e: Exception) {
                Log.w(TAG, "channel close failed (already closed?)", e)
            }
        }
        activeChannel = null
        if (watchNodeId != null) {
            try {
                withTimeout(STOP_TIMEOUT_MS) {
                    messageClient.sendRequest(watchNodeId, Constants.Paths.CONTROL, ControlCommand.Stop.toBytes()).await()
                }
            } catch (e: Exception) {
                Log.w(TAG, "STOP not delivered", e)
            }
        }
        try {
            channelClient.unregisterChannelCallback(channelCallback).await()
        } catch (e: Exception) {
            Log.w(TAG, "unregisterChannelCallback failed", e)
        }
        LiveStatus.stopped(error)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "라이브 듣기", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ListenerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("워치 주변 소리 듣는 중")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openApp)
            .addAction(0, "중지", stop)
            .build()
    }

    private class LiveException(message: String) : Exception(message)

    companion object {
        private const val ACTION_STOP = "com.watchbabymonitor.mobile.action.STOP_LIVE"
        private const val CHANNEL_ID = "live"
        private const val NOTIFICATION_ID = 2
        private const val READ_BUFFER = 4096
        private const val STATS_INTERVAL_MS = 500L
        private const val WATCHDOG_INTERVAL_MS = 500L
        private const val STOP_TIMEOUT_MS = 2_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ListenerService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ListenerService::class.java).setAction(ACTION_STOP))
        }
    }
}
