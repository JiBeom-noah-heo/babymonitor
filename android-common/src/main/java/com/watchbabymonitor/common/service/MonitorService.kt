package com.watchbabymonitor.common.service

import com.watchbabymonitor.common.R
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
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.watchbabymonitor.common.AndroidLog
import com.watchbabymonitor.common.audio.AudioCapture
import com.watchbabymonitor.common.audio.Streamer
import com.watchbabymonitor.common.datalayer.DataLayerAlertSink
import com.watchbabymonitor.shared.engine.StreamSinkFactory
import com.watchbabymonitor.common.DeviceInfo
import com.watchbabymonitor.common.Engines
import com.watchbabymonitor.common.RoleStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 감지기 모니터링 서비스: 마이크 → [Engines.sensor]. 판정·알림·스트림 분배는 엔진이 한다.
 * 여기서는 foreground(type=microphone) + PARTIAL WakeLock 으로 화면이 꺼져도 살아 있게만 한다
 * (CLAUDE.md §4-5, §4-6).
 *
 * 시작: [start] (앱이 화면에 떠 있을 때만 — 마이크 포그라운드 서비스 제한), 정지: [stop].
 */
class MonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitorJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (monitorJob?.isActive == true) return START_NOT_STICKY

        ensureChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(),
            // microphone 타입은 API 30+. 폰(minSdk 29)이 감지기일 수 있으므로 29 에서는 타입 없이
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
        )

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Engines.sensor.reportError("마이크 권한 없음")
            stopSelf()
            return START_NOT_STICKY
        }

        acquireWakeLock()
        monitorJob = scope.launch { monitor() }
        // 12시간 상한이면 밤새 쓰다 CPU 가 잠들 수 있었음 → 짧은 상한을 주기적으로 갱신
        scope.launch {
            while (true) {
                delay(WAKELOCK_RENEW_MS)
                acquireWakeLock()
            }
        }
        // 프로세스가 죽은 뒤 자동 재시작하면 백그라운드 마이크 시작 제한에 걸리므로 NOT_STICKY
        return START_NOT_STICKY
    }

    @Suppress("MissingPermission") // onStartCommand 에서 확인
    private suspend fun monitor() {
        try {
            Engines.sensor.run(
                frames = AudioCapture().frames(),
                alerts = DataLayerAlertSink(this, AndroidLog("MonitorService")),
                streams = StreamSinkFactory { nodeId -> Streamer(this, nodeId) },
                config = RoleStore.config(this).value,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 엔진이 로그와 상태(error)에 남김 → 서비스 정지
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    /**
     * 모니터링 중 CPU 유지. 상한 [WAKELOCK_TIMEOUT_MS] 를 [WAKELOCK_RENEW_MS] 마다 다시 잡는다
     * (reference count 없음 → 다시 잡으면 상한이 늘어남). 서비스가 비정상으로 죽어도 최대 1시간 안에 풀린다.
     */
    private fun acquireWakeLock() {
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WBM:Monitor")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        lock.acquire(WAKELOCK_TIMEOUT_MS)
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "모니터링", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, DeviceInfo.launchIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_monitor)
            .setContentTitle(DeviceInfo.appLabel(this))
            .setContentText("소리 감시 중")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openApp)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "monitor"
        private const val NOTIFICATION_ID = 1

        /** WakeLock 상한. 갱신이 멈추면(서비스 비정상 종료 등) 이 시간 안에 풀린다. */
        private const val WAKELOCK_TIMEOUT_MS = 60 * 60 * 1000L

        /** 상한보다 충분히 짧게 갱신. */
        private const val WAKELOCK_RENEW_MS = 30 * 60 * 1000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }
}
