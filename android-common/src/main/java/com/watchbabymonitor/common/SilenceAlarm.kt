package com.watchbabymonitor.common

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import com.watchbabymonitor.common.datalayer.StatusSync
import com.watchbabymonitor.common.notification.PeerNotifications
import com.watchbabymonitor.shared.Constants
import com.watchbabymonitor.shared.DeviceStatus
import com.watchbabymonitor.shared.Role
import com.watchbabymonitor.shared.engine.PeerEvent
import com.watchbabymonitor.shared.engine.PeerMonitor
import java.util.concurrent.TimeUnit

/**
 * 모니터링 중인 감지기가 말없이 멈췄는지(재부팅·강제 종료·배터리 최적화) 확인하는 시스템 알람.
 * 잠든 기기에서는 앱 안 타이머(delay)가 멈추므로 AlarmManager 를 쓴다 (Phase 5 교훈).
 *
 * 감지기 상태를 받을 때마다 "그 상태 시각 + [Constants.Link.SENSOR_SILENT_MS]" 로 다시 예약한다.
 * 감지기는 모니터링 중 5분마다 상태를 올리므로 정상이면 알람이 계속 뒤로 밀린다.
 */
object SilenceAlarm {
    private const val REQUEST_CODE = 7
    private const val SLACK_MS = 30_000L
    private const val PREFS = "wbm_silence"
    private const val KEY_NOTIFIED_TS = "notified_ts"

    private val log = AndroidLog("SilenceAlarm")

    /** 수신기가 감지기 상태를 받았을 때. 모니터링 중이면 예약, 아니면 취소. */
    fun onPeerStatus(context: Context, status: DeviceStatus) {
        if (RoleStore.current(context) == Role.RECEIVER && status.role == Role.SENSOR && status.monitoring) {
            schedule(context, status.ts + Constants.Link.SENSOR_SILENT_MS + SLACK_MS)
            // 새 소식이 왔으니 "소식 없음" 알림은 내린다
            PeerNotifications.cancelSilent(context)
        } else {
            cancel(context)
        }
    }

    private fun schedule(context: Context, atWallMs: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        // 정확한 시각은 필요 없다. Doze 에서도 울리되 시스템이 조금 늦출 수 있음
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atWallMs, pendingIntent(context))
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CODE, Intent(context, SilenceCheckReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** 알람 시각: 저장된 최신 감지기 상태를 다시 읽어 판단한다 (프로세스가 새로 떴을 수 있어 메모리에 의존하지 않음). */
    internal fun check(context: Context) {
        if (RoleStore.current(context) != Role.RECEIVER) return
        val status = latestPeerStatus(context) ?: return
        val now = System.currentTimeMillis()
        if (PeerMonitor.isSilent(status, now)) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getLong(KEY_NOTIFIED_TS, 0) != status.ts) {
                prefs.edit().putLong(KEY_NOTIFIED_TS, status.ts).apply()
                val minutes = (now - status.ts) / 60_000
                log.w("sensor silent for ${minutes}min (last status ts=${status.ts})")
                PeerNotifications.show(context, PeerEvent.SENSOR_SILENT, detail = "${minutes}분")
            }
        } else if (status.role == Role.SENSOR && status.monitoring) {
            // 그사이 새 상태가 왔으면 다음 확인을 다시 예약
            schedule(context, status.ts + Constants.Link.SENSOR_SILENT_MS + SLACK_MS)
        }
    }

    private fun latestPeerStatus(context: Context): DeviceStatus? = try {
        val localId = Tasks.await(Wearable.getNodeClient(context).localNode, 10, TimeUnit.SECONDS).id
        val items = Tasks.await(
            Wearable.getDataClient(context).getDataItems(Uri.parse("wear://*${Constants.Paths.STATUS}")),
            10, TimeUnit.SECONDS,
        )
        val sync = StatusSync(context, log)
        try {
            items.filter { it.uri.host != localId }.mapNotNull { sync.decode(it) }.maxByOrNull { it.ts }
        } finally {
            items.release()
        }
    } catch (e: Exception) {
        log.e("read peer status failed", e)
        null
    }
}

/** [SilenceAlarm] 알람을 받는 리시버. Data Layer 조회가 막히는 작업이라 goAsync 로 백그라운드에서. */
class SilenceCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                SilenceAlarm.check(context.applicationContext)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
