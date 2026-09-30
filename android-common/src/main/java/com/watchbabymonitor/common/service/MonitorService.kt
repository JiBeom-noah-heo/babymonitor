package com.watchbabymonitor.common.service

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
                preset = RoleStore.preset(this).value,
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
            this, 0, DeviceInfo.launchIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
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
