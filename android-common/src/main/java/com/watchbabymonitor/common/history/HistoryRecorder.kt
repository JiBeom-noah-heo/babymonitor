package com.watchbabymonitor.common.history

import android.content.Context
import com.watchbabymonitor.common.AndroidLog
import com.watchbabymonitor.common.Engines
import com.watchbabymonitor.common.LinkMonitor
import com.watchbabymonitor.common.RoleStore
import com.watchbabymonitor.shared.NoiseAlert
import com.watchbabymonitor.shared.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 소음 이벤트를 [HistoryDb] 에 남긴다. 30일 지난 기록은 시작할 때 지운다.
 * - 감지기: 알림 전달 결과가 나오면 (보낸 수신기 수 포함)
 * - 수신기: 알림을 받는 즉시 [recordReceived] (리스너 서비스에서 직접 호출 — 앱이 막 깨어난 경우에도 빠지지 않게)
 */
object HistoryRecorder {
    private const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    private const val RECENT_LIMIT = 200

    private val log = AndroidLog("HistoryRecorder")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val dao = HistoryDb.get(app).events()
        scope.launch {
            val removed = dao.deleteBefore(System.currentTimeMillis() - RETENTION_MS)
            if (removed > 0) log.i("removed $removed old events")
        }
        // 감지기: lastAlert 의 전달 결과가 정해지면 한 번
        scope.launch {
            Engines.sensor.state
                .map { Triple(it.lastAlert, it.lastAlertDelivered, it.preset) }
                .filter { (alert, delivered, _) -> alert != null && delivered != null }
                .distinctUntilChanged { a, b -> a.first == b.first }
                .collect { (alert, delivered, preset) ->
                    if (RoleStore.current(app) != Role.SENSOR) return@collect
                    insert(
                        app,
                        NoiseEvent(
                            ts = alert!!.ts,
                            level = alert.level,
                            role = Role.SENSOR.name,
                            preset = preset.name,
                            delivered = delivered,
                            peerName = LinkMonitor.state.value.peerName,
                        ),
                    )
                }
        }
    }

    /** 수신기가 알림을 받았을 때. */
    fun recordReceived(context: Context, alert: NoiseAlert, fromName: String?) {
        insert(
            context.applicationContext,
            NoiseEvent(
                ts = alert.ts,
                level = alert.level,
                role = Role.RECEIVER.name,
                preset = null,
                delivered = null,
                peerName = fromName,
            ),
        )
    }

    fun recent(context: Context): Flow<List<NoiseEvent>> = HistoryDb.get(context).events().recent(RECENT_LIMIT)

    fun clear(context: Context) {
        scope.launch { HistoryDb.get(context).events().clear() }
    }

    private fun insert(context: Context, event: NoiseEvent) {
        scope.launch {
            try {
                HistoryDb.get(context).events().insert(event)
            } catch (e: Exception) {
                log.e("history insert failed", e)
            }
        }
    }
}
