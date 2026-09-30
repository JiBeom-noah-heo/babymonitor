package com.watchbabymonitor.common.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.watchbabymonitor.common.DeviceInfo
import com.watchbabymonitor.shared.Constants
import com.watchbabymonitor.shared.engine.PeerEvent

private val TAG = Constants.logTag("PeerNotifications")

/** 수신기에 보여주는 감지기 상태 알림: 연결 끊김, 배터리 부족, 마이크 막힘 (Phase 5). */
object PeerNotifications {
    private const val CHANNEL_ID = "sensor_status"
    private const val ID_LINK = 200
    private const val ID_BATTERY = 201
    private const val ID_MIC = 202
    private const val ID_STOPPED = 203
    private const val ID_SILENT = 204
    private const val RECONNECTED_TIMEOUT_MS = 10_000L

    /**
     * @param batteryPercent LOW_BATTERY 에 표시
     * @param detail SENSOR_STOPPED 의 오류 사유, SENSOR_SILENT 의 경과 시간
     */
    fun show(context: Context, event: PeerEvent, batteryPercent: Int? = null, detail: String? = null) {
        val nm = NotificationManagerCompat.from(context)
        when (event) {
            PeerEvent.DISCONNECTED -> post(
                context, ID_LINK, "감지기와 연결이 끊겼어요",
                "${Constants.Link.DISCONNECT_GRACE_MS / 1000}초 넘게 연결이 없어요. 소음 알림을 받을 수 없어요.",
            )
            PeerEvent.RECONNECTED -> post(context, ID_LINK, "감지기와 다시 연결됐어요", null, timeoutMs = RECONNECTED_TIMEOUT_MS)
            PeerEvent.LOW_BATTERY -> post(
                context, ID_BATTERY, "감지기 배터리 ${batteryPercent ?: "?"}%", "충전하지 않으면 곧 감지가 멈춰요.",
            )
            PeerEvent.MIC_MUTED -> post(
                context, ID_MIC, "감지기 마이크가 막혔어요", "통화 중이면 그동안 아이 소리를 들을 수 없어요.",
            )
            PeerEvent.MIC_BACK -> nm.cancel(ID_MIC)
            PeerEvent.SENSOR_STOPPED -> post(
                context, ID_STOPPED, "감지기 모니터링이 꺼졌어요",
                detail?.let { "사유: $it" } ?: "감지기에서 모니터링을 끄면 소음 알림이 오지 않아요.",
            )
            PeerEvent.SENSOR_SILENT -> post(
                context, ID_SILENT, "감지기 소식이 없어요",
                "${detail ?: "한참"} 넘게 감지기 상태가 오지 않았어요. 재부팅되었거나 앱이 종료됐을 수 있어요. 감지기를 확인해 주세요.",
            )
        }
        Log.i(TAG, "peer event $event battery=$batteryPercent detail=$detail")
    }

    /** 새 소식이 오면 "소식 없음" 알림을 내린다. */
    fun cancelSilent(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_SILENT)
    }

    private fun post(context: Context, id: Int, title: String, text: String?, timeoutMs: Long? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted, '$title' dropped")
            return
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "감지기 상태", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "감지기 연결 끊김, 배터리 부족, 마이크 막힘"
                enableVibration(true)
            },
        )
        val open = PendingIntent.getActivity(
            context, id, DeviceInfo.launchIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(open)
        text?.let { builder.setContentText(it).setStyle(NotificationCompat.BigTextStyle().bigText(it)) }
        timeoutMs?.let { builder.setTimeoutAfter(it) }
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }
}
