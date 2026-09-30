package com.watchbabymonitor.common

import android.content.Context
import com.watchbabymonitor.common.notification.PeerNotifications
import com.watchbabymonitor.shared.DeviceStatus
import com.watchbabymonitor.shared.Role
import com.watchbabymonitor.shared.engine.PeerEvent
import com.watchbabymonitor.shared.engine.PeerMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 수신기일 때 감지기 상태를 지켜보고 알린다 (연결 끊김·배터리·마이크). 판단은 shared [PeerMonitor].
 * 이벤트는 실시간 리스너와 백그라운드 리스너 양쪽에서 올 수 있다 → PeerMonitor 가 중복 제거.
 */
object PeerAlerts {
    private const val TICK_MS = 5_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val monitor = PeerMonitor()

    @Volatile
    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            while (true) {
                delay(TICK_MS)
                dispatch(app, synchronized(monitor) { monitor.tick() }, null)
            }
        }
    }

    fun onLink(context: Context, connected: Boolean) {
        dispatch(context, synchronized(monitor) { monitor.onLink(connected) }, null)
    }

    fun onPeerStatus(context: Context, status: DeviceStatus) {
        dispatch(context, synchronized(monitor) { monitor.onPeerStatus(status) }, status.batteryPercent)
    }

    private fun dispatch(context: Context, events: List<PeerEvent>, battery: Int?) {
        if (events.isEmpty() || RoleStore.current(context) != Role.RECEIVER) return
        events.forEach { PeerNotifications.show(context, it, battery) }
    }
}
