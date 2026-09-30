package com.watchbabymonitor.wear.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.Wearable
import com.watchbabymonitor.shared.AudioLevel
import com.watchbabymonitor.shared.Constants
import com.watchbabymonitor.shared.NoiseAlert
import com.watchbabymonitor.shared.NoiseDetector
import com.watchbabymonitor.wear.MainActivity
import com.watchbabymonitor.wear.R
import com.watchbabymonitor.wear.audio.AudioCapture
import com.watchbabymonitor.wear.audio.Streamer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.math.max

private val TAG = Constants.logTag("MonitorService")

/**
 * 워치 마이크로 소리를 계속 재고, 소음이 지속되면 폰에 `/alert` 를 보낸다.
 * foreground(type=microphone) + PARTIAL WakeLock 으로 화면이 꺼져도 동작 (CLAUDE.md §4-5, §4-6).
 *
 * 시작: [start] (앱이 화면에 떠 있을 때만 — 마이크 포그라운드 서비스 제한), 정지: [stop].
 */
class MonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitorJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // 녹음 코루틴과 스트림 제어 코루틴이 함께 접근
    @Volatile
    private var streamer: Streamer? = null
    private var streamJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (monitorJob?.isActive == true) return START_NOT_STICKY

        ensureChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "RECORD_AUDIO not granted")
            MonitorStatus.onError("마이크 권한 없음")
            stopSelf()
            return START_NOT_STICKY
        }

        acquireWakeLock()
        monitorJob = scope.launch { monitor() }
        scope.launch { followStreamRequests() }
        // 프로세스가 죽은 뒤 자동 재시작하면 백그라운드 마이크 시작 제한에 걸리므로 NOT_STICKY
        return START_NOT_STICKY
    }

    @Suppress("MissingPermission") // onStartCommand 에서 확인
    private suspend fun monitor() {
        val detector = NoiseDetector()
        MonitorStatus.onStarted(detector.thresholdDbfs)
        Log.i(TAG, "monitoring started, threshold=${detector.thresholdDbfs} dBFS")

        var frames = 0L
        var maxDbfsSinceHeartbeat = Constants.Audio.MIN_DBFS
        try {
            AudioCapture().frames().collect { frame ->
                // 라이브 듣기 중이면 같은 프레임을 폰으로도 보낸다 (ADR 004)
                streamer?.offer(frame)

                val dbfs = AudioLevel.dbfs(frame)
                MonitorStatus.onLevel(dbfs)
                detector.onLevel(dbfs, System.currentTimeMillis())?.let { alert ->
                    Log.i(TAG, "noise alert level=${alert.level}")
                    MonitorStatus.onAlert(alert)
                    scope.launch { sendAlert(alert) }
                }

                frames++
                maxDbfsSinceHeartbeat = max(maxDbfsSinceHeartbeat, dbfs)
                if (frames % HEARTBEAT_FRAMES == 0L) {
                    // 장시간 동작 확인용 (오디오 내용은 기록하지 않음)
                    Log.i(TAG, "alive: ${frames / 10}s, max=${maxDbfsSinceHeartbeat.toInt()} dBFS, alerts=${MonitorStatus.state.value.alertCount}")
                    maxDbfsSinceHeartbeat = Constants.Audio.MIN_DBFS
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "monitoring failed", e)
            MonitorStatus.onError("마이크 오류: ${e.message ?: e.javaClass.simpleName}")
            stopSelf()
        }
    }

    /**
     * [StreamRequests] 를 따라 스트리밍을 시작/중지한다.
     * 모니터링이 이미 돌고 있을 때만 스트리밍 가능 — 녹음 중인 프레임을 나눠 보낸다.
     */
    private suspend fun followStreamRequests() {
        StreamRequests.target.collect { nodeId ->
            streamJob?.let { job ->
                streamer?.close()
                job.cancelAndJoin()
            }
            streamer = null
            streamJob = null
            if (nodeId == null) return@collect

            val s = Streamer(this, nodeId)
            streamer = s
            streamJob = scope.launch {
                MonitorStatus.onStreaming(true)
                try {
                    s.run()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 폰이 채널을 닫은 경우도 여기로 온다
                    Log.w(TAG, "streaming to $nodeId ended: ${e.message}")
                } finally {
                    if (streamer === s) streamer = null
                    MonitorStatus.onStreaming(false)
                    StreamRequests.finished(nodeId)
                }
            }
        }
    }

    private suspend fun sendAlert(alert: NoiseAlert) {
        try {
            val messageClient = Wearable.getMessageClient(this)
            val nodes = Wearable.getNodeClient(this).connectedNodes.await()
            var delivered = 0
            for (node in nodes) {
                try {
                    messageClient.sendMessage(node.id, Constants.Paths.ALERT, alert.toBytes()).await()
                    delivered++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "sendAlert to ${node.displayName} failed", e)
                }
            }
            Log.i(TAG, "alert sent to $delivered/${nodes.size} nodes")
            MonitorStatus.onAlertDelivered(alert, delivered)
            if (delivered == 0) MonitorStatus.onError("폰에 알림 전달 실패")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "sendAlert failed", e)
            MonitorStatus.onAlertDelivered(alert, 0)
            MonitorStatus.onError("폰에 알림 전달 실패: ${e.message}")
        }
    }

    override fun onDestroy() {
        streamer?.close()
        StreamRequests.stop()
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        MonitorStatus.onStopped()
        Log.i(TAG, "monitoring stopped")
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WBM:Monitor").apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_TIMEOUT_MS)
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "모니터링", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("소리 감시 중")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openApp)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "monitor"
        private const val NOTIFICATION_ID = 1

        /** 1분마다 동작 로그. */
        private const val HEARTBEAT_FRAMES = 600L

        /** 정지를 못 받아도 WakeLock 이 영원히 잡히지 않게 하는 상한. */
        private const val WAKELOCK_TIMEOUT_MS = 12 * 60 * 60 * 1000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }
}
