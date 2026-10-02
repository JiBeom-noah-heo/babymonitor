package com.watchbabymonitor.common.notification

import com.watchbabymonitor.common.R
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.watchbabymonitor.common.DeviceInfo
import com.watchbabymonitor.common.service.ListenerService
import com.watchbabymonitor.shared.Constants
import com.watchbabymonitor.shared.NoiseAlert
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val TAG = Constants.logTag("NoiseNotifications")

/**
 * 수신기 "소음 감지" 알림: 진동 + 알림 (폰은 heads-up, 워치는 [WatchAlert] 가 화면도 켠다).
 *
 * - **진동은 앱이 직접, 알람 용도로**: 진동·무음·방해 금지 모드에서도 길게 세 번 울린다
 * - **소리는 채널의 일반 알림 소리**: 폰 소리 모드를 따른다 (방해 금지·진동 모드면 소리 없이 진동만)
 *
 * 예전 채널은 진동도 알림 용도라 폰이 진동 모드 + 방해 금지면 1초 기본 진동 한 번뿐이었다 (devlog 2026-10-02).
 * 채널 진동 설정은 만든 뒤 바꿀 수 없어서 새 채널 ID 로 바꾸고 예전 채널은 지운다.
 */
object NoiseNotifications {
    private const val CHANNEL_ID = "noise"
    // noise_alert: ~2026-10-01 (채널이 알림 용도로 진동), noise_alarm·noise_vibrate: 10-02 개발 중 시험
    private val OLD_CHANNELS = listOf("noise_alert", "noise_alarm", "noise_vibrate")
    private const val NOTIFICATION_ID = 100

    /** 길게-짧게 세 번. 일반 알림 진동과 구분되게. */
    private val VIBRATION = longArrayOf(0, 700, 200, 700, 200, 700)

    /** heads-up 이 뜨도록 IMPORTANCE_HIGH. 앱 시작 시 한 번 호출. */
    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        OLD_CHANNELS.forEach { nm.deleteNotificationChannel(it) }
        val channel = NotificationChannel(CHANNEL_ID, "소음 감지", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "아기 옆 감지기에서 큰 소리가 계속될 때 알림. 진동은 방해 금지 모드에서도 울려요"
            enableVibration(false) // 진동은 [vibrate] 가 알람 용도로
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(channel)
    }

    fun show(context: Context, alert: NoiseAlert) {
        vibrate(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted, alert dropped")
            return
        }
        ensureChannel(context)

        val openApp = PendingIntent.getActivity(
            context, 0,
            DeviceInfo.launchIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(alert.ts))
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_monitor)
            .setContentTitle("아기 쪽에서 소리가 나요")
            .setContentText("$time · ${alert.level.roundToInt()} dB")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setWhen(alert.ts)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            // 사용자가 누른 알림 버튼에서는 재생 FGS 를 시작할 수 있다 (자동 듣기의 백그라운드 대안, Phase 6)
            .addAction(0, "듣기", PendingIntent.getForegroundService(
                context, 3, Intent(context, ListenerService::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
            .build()

        // 같은 ID 로 갱신해도 매번 소리가 나도록 onlyAlertOnce 는 쓰지 않음
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun vibrate(context: Context) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        val effect = VibrationEffect.createWaveform(VIBRATION, -1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 알람 용도로 → 방해 금지·진동·무음 모드에서도 울리도록
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            vibrator.vibrate(effect)
        }
    }
}
