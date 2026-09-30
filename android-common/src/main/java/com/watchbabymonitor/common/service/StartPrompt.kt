package com.watchbabymonitor.common.service

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
import com.watchbabymonitor.shared.Constants
import com.watchbabymonitor.common.DeviceInfo

private val TAG = Constants.logTag("StartPrompt")

/**
 * 수신기가 원격으로 모니터링 시작(`START`)을 요청했지만 앱이 백그라운드라 마이크를 켤 수 없을 때,
 * "탭해서 모니터링 시작" 알림을 띄운다. 탭하면 앱 화면(MainActivity)이 [DeviceInfo.ACTION_START_MONITORING] 으로 열려 시작한다.
 */
object StartPrompt {
    private const val CHANNEL_ID = "start_request"
    private const val NOTIFICATION_ID = 10

    fun show(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted, start request dropped")
            return
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "모니터링 시작 요청", NotificationManager.IMPORTANCE_HIGH),
        )
        val open = PendingIntent.getActivity(
            context, 0,
            DeviceInfo.launchIntent(context, DeviceInfo.ACTION_START_MONITORING),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("모니터링 시작 요청")
            .setContentText("탭해서 모니터링 시작")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        Log.i(TAG, "start request shown")
    }

    fun dismiss(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}
