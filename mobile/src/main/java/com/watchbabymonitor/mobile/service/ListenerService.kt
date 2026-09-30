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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.watchbabymonitor.mobile.MainActivity
import com.watchbabymonitor.mobile.R
import com.watchbabymonitor.common.audio.AudioPlayer
import com.watchbabymonitor.common.datalayer.ControlClient
import com.watchbabymonitor.shared.Constants
import com.watchbabymonitor.shared.ControlCommand
import com.watchbabymonitor.shared.Pcm16
import com.watchbabymonitor.shared.engine.ReceiverEngine
import com.watchbabymonitor.shared.engine.StreamEnd
import com.watchbabymonitor.shared.engine.WatchdogAction
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

private val TAG = Constants.logTag("ListenerService")

/**
 * 라이브 듣기: 감지기에 스트리밍 요청 → 감지기가 연 `/audio` 채널을 받아 AudioTrack 으로 재생.
 * 상태·통계·무데이터 판단·사용자 문구는 [Receiver.engine] 이 한다.
 * foreground(type=mediaPlayback) 라 앱을 닫아도 계속 들린다 (ADR 004).
 *
 * 시작 [start] 은 앱 화면에서만 (백그라운드 포그라운드 서비스 시작 제한), 정지 [stop].
 */
class ListenerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val channelClient by lazy { Wearable.getChannelClient(this) }
    private val control by lazy { ControlClient(this) }

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
        val engine = Receiver.engine
        engine.sessionStarted()
        var end = StreamEnd.USER_STOPPED
        var detail: String? = null
        var sensorNodeId: String? = null
        try {
            channelClient.registerChannelCallback(channelCallback).await()

            val node = control.connectedNodes().firstOrNull()
            if (node == null) {
                end = StreamEnd.NO_PEER
                return
            }
            sensorNodeId = node.id
            engine.peerFound(node.displayName)

            val opened = CompletableDeferred<ChannelClient.Channel>()
            channelOpened = opened

            val reply = control.send(node.id, ControlCommand.StreamOn)
            Log.i(TAG, "STREAM_ON -> $reply")
            engine.onStreamOnReply(reply)?.let {
                end = it
                detail = reply
                return
            }

            val channel = withTimeout(Constants.Stream.CHANNEL_OPEN_TIMEOUT_MS) { opened.await() }
            activeChannel = channel
            end = play(channel)
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "sensor did not respond", e)
            end = StreamEnd.NO_RESPONSE
        } catch (e: CancellationException) {
            end = StreamEnd.USER_STOPPED
            throw e
        } catch (e: Exception) {
            if (stopRequested) {
                end = StreamEnd.USER_STOPPED
            } else {
                Log.e(TAG, "live session failed", e)
                end = StreamEnd.FAILED
                detail = e.message ?: e.javaClass.simpleName
            }
        } finally {
            withContext(NonCancellable) {
                cleanup(sensorNodeId)
                engine.sessionEnded(if (stopRequested) StreamEnd.USER_STOPPED else end, detail)
            }
        }
    }

    /** 채널이 닫히거나 끊길 때까지 재생. 끝난 이유를 돌려준다. */
    private suspend fun play(channel: ChannelClient.Channel): StreamEnd = withContext(Dispatchers.IO) {
        val engine = Receiver.engine
        val input = channelClient.getInputStream(channel).await()
        val player = AudioPlayer()
        engine.channelOpened()
        val disconnected = AtomicBoolean(false)

        // read 가 조용히 멈추는 경우 감지 (CLAUDE.md §8)
        val watchdog = launch {
            while (true) {
                delay(ReceiverEngine.WATCHDOG_INTERVAL_MS)
                if (engine.watchdog() == WatchdogAction.DISCONNECT) {
                    disconnected.set(true)
                    channelClient.close(channel)
                    break
                }
            }
        }

        var totalBytes = 0L
        try {
            input.use {
                val buf = ByteArray(READ_BUFFER)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    player.write(buf, n)
                    totalBytes += n
                    engine.onData(n) { player.stats() }
                }
            }
        } catch (e: IOException) {
            // watchdog 이 채널을 닫으면 read 가 ChannelIOException 으로 끝난다
            if (!disconnected.get()) throw e
        } finally {
            watchdog.cancel()
            val st = player.stats()
            Log.i(TAG, "played ${Pcm16.bytesToMs(totalBytes) / 1000}s, dropped ${st.droppedMs}ms, resyncs=${st.resyncs}, underruns=${st.underruns}")
            player.release()
        }
        if (disconnected.get()) StreamEnd.DISCONNECTED else StreamEnd.SENSOR_ENDED
    }

    private suspend fun cleanup(sensorNodeId: String?) {
        channelOpened = null
        activeChannel?.let {
            try {
                channelClient.close(it).await()
            } catch (e: Exception) {
                Log.w(TAG, "channel close failed (already closed?)", e)
            }
        }
        activeChannel = null
        if (sensorNodeId != null) {
            try {
                control.send(sensorNodeId, ControlCommand.StreamOff, STOP_TIMEOUT_MS)
            } catch (e: Exception) {
                Log.w(TAG, "STREAM_OFF not delivered", e)
            }
        }
        try {
            channelClient.unregisterChannelCallback(channelCallback).await()
        } catch (e: Exception) {
            Log.w(TAG, "unregisterChannelCallback failed", e)
        }
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

    companion object {
        private const val ACTION_STOP = "com.watchbabymonitor.mobile.action.STOP_LIVE"
        private const val CHANNEL_ID = "live"
        private const val NOTIFICATION_ID = 2
        private const val READ_BUFFER = 4096
        private const val STOP_TIMEOUT_MS = 2_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ListenerService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ListenerService::class.java).setAction(ACTION_STOP))
        }
    }
}
