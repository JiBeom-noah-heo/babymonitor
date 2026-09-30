package com.watchbabymonitor.common.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.watchbabymonitor.common.DeviceInfo
import com.watchbabymonitor.shared.Constants
import com.watchbabymonitor.shared.NoiseAlert
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val TAG = Constants.logTag("NoiseNotifications")

/** 폰 "소음 감지" 알림 채널과 알림. */
object NoiseNotifications {
    private const val CHANNEL_ID = "noise_alert"
    private const val NOTIFICATION_ID = 100

    /** heads-up 이 뜨도록 IMPORTANCE_HIGH. 앱 시작 시 한 번 호출. */
    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "소음 감지", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "워치 주변에서 큰 소리가 계속될 때 알림"
            enableVibration(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun show(context: Context, alert: NoiseAlert) {
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
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("아기 쪽에서 소리가 나요")
            .setContentText("$time · ${alert.level.roundToInt()} dB")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setWhen(alert.ts)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()

        // 같은 ID 로 갱신해도 매번 소리·진동이 나도록 onlyAlertOnce 는 쓰지 않음
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }
}
