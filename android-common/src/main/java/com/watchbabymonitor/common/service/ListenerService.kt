package com.watchbabymonitor.common.service

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
import com.watchbabymonitor.common.DeviceInfo
import com.watchbabymonitor.common.Engines
import com.watchbabymonitor.common.Link
import com.watchbabymonitor.common.LinkMonitor
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.drop
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

private val TAG = Constants.logTag("ListenerService")

/**
 * 라이브 듣기: 감지기에 스트리밍 요청 → 감지기가 연 `/audio` 채널을 받아 AudioTrack 으로 재생.
 * 상태·통계·무데이터 판단·사용자 문구는 [Engines.receiver] 이 한다.
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
        session = scope.launch { runWithReconnect() }
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

    private data class SessionResult(val end: StreamEnd, val detail: String? = null, val playedBytes: Long = 0)

    /**
     * 세션을 돌리고, 끊기면 [ReceiverEngine.retryDelayMs] 에 따라 다시 연결한다 (Phase 5).
     * 사용자가 끄거나, 감지기가 모니터링 중이 아니거나, 10분 넘게 실패하면 끝.
     */
    private suspend fun runWithReconnect() {
        val engine = Engines.receiver
        var attempt = 0
        var firstFailureAt: Long? = null
        try {
            while (true) {
                val result = runSession()
                if (stopRequested || result.end == StreamEnd.USER_STOPPED) break

                val now = SystemClock.elapsedRealtime()
                // 소리가 들어왔던 세션이 끊긴 거면 처음부터 다시 센다
                if (result.playedBytes > 0) {
                    attempt = 0
                    firstFailureAt = null
                }
                val first = firstFailureAt ?: now.also { firstFailureAt = it }
                val wait = ReceiverEngine.retryDelayMs(result.end, attempt, now - first)
                if (wait == null) {
                    engine.sessionEnded(result.end, result.detail)
                    break
                }
                attempt++
                engine.reconnecting(attempt, wait, result.end)
                // 블루투스가 다시 붙으면 기다리지 않고 바로 재시도 (Phase 5: 30초 대기 때문에 복구가 1분 늦었음)
                val linkBack = withTimeoutOrNull(wait) {
                    LinkMonitor.state.drop(1).first { it.link == Link.NEARBY }
                }
                if (linkBack != null) {
                    // 연결 직후엔 상대가 아직 준비 안 됐을 수 있다 → 실패해도 짧은 간격부터 다시
                    Log.i(TAG, "link back (nearby), retry now")
                    attempt = 0
                }
            }
        } finally {
            withContext(NonCancellable) {
                if (stopRequested) engine.sessionEnded(StreamEnd.USER_STOPPED)
                ServiceCompat.stopForeground(this@ListenerService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /** 한 번의 연결: 감지기 찾기 → STREAM_ON → 채널 → 재생. 끝나면 채널·요청을 정리한다. */
    private suspend fun runSession(): SessionResult {
        val engine = Engines.receiver
        engine.sessionStarted()
        var sensorNodeId: String? = null
        try {
            channelClient.registerChannelCallback(channelCallback).await()

            val node = control.connectedNodes().firstOrNull() ?: return SessionResult(StreamEnd.NO_PEER)
            sensorNodeId = node.id
            engine.peerFound(node.displayName)

            val opened = CompletableDeferred<ChannelClient.Channel>()
            channelOpened = opened

            val reply = control.send(node.id, ControlCommand.StreamOn)
            Log.i(TAG, "STREAM_ON -> $reply")
            engine.onStreamOnReply(reply)?.let { return SessionResult(it, reply) }

            val channel = withTimeout(Constants.Stream.CHANNEL_OPEN_TIMEOUT_MS) { opened.await() }
            activeChannel = channel
            val (end, played) = play(channel)
            return SessionResult(end, playedBytes = played)
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "sensor did not respond", e)
            return SessionResult(StreamEnd.NO_RESPONSE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (stopRequested) return SessionResult(StreamEnd.USER_STOPPED)
            Log.e(TAG, "live session failed", e)
            return SessionResult(StreamEnd.FAILED, e.message ?: e.javaClass.simpleName)
        } finally {
            withContext(NonCancellable) { cleanupSession(sensorNodeId) }
        }
    }

    /** 채널이 닫히거나 끊길 때까지 재생. 끝난 이유와 받은 바이트 수를 돌려준다. */
    private suspend fun play(channel: ChannelClient.Channel): Pair<StreamEnd, Long> = withContext(Dispatchers.IO) {
        val engine = Engines.receiver
        val input = channelClient.getInputStream(channel).await()
        // 원격(클라우드)이면 데이터가 몰려 오므로 버퍼를 크게 (Phase 5 측정)
        val remote = LinkMonitor.state.value.link == Link.REMOTE
        val buffering = ReceiverEngine.bufferingFor(remote)
        Log.i(TAG, "buffering remote=$remote prebuffer=${buffering.prebufferMs}ms max=${buffering.maxBacklogMs}ms")
        val player = AudioPlayer(buffering)
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
        (if (disconnected.get()) StreamEnd.DISCONNECTED else StreamEnd.SENSOR_ENDED) to totalBytes
    }

    private suspend fun cleanupSession(sensorNodeId: String?) {
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
            DeviceInfo.launchIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ListenerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle(DeviceInfo.appLabel(this))
            .setContentText("아기 쪽 소리 듣는 중")
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
