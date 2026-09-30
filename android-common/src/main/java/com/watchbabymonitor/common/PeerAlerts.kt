package com.watchbabymonitor.common

import android.content.Context
import android.os.PowerManager
import com.watchbabymonitor.shared.Constants
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
        if (RoleStore.current(context) == Role.RECEIVER) {
            if (connected) releaseAwake() else holdAwake(context)
        }
    }

    // 끊김 유예 시간 동안만 CPU 를 깨워 둔다. 잠든 수신기(워치)에서 delay() 가 멈춰
    // 끊김 알림이 연결 복구 때까지 밀렸음 (Phase 5 실측)
    @Volatile
    private var awake: PowerManager.WakeLock? = null

    private fun holdAwake(context: Context) {
        if (awake?.isHeld == true) return
        val pm = context.applicationContext.getSystemService(PowerManager::class.java)
        awake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WBM:DisconnectGrace").apply {
            setReferenceCounted(false)
            acquire(Constants.Link.DISCONNECT_GRACE_MS + TICK_MS)
        }
    }

    private fun releaseAwake() {
        awake?.let { if (it.isHeld) it.release() }
        awake = null
    }

    fun onPeerStatus(context: Context, status: DeviceStatus) {
        dispatch(context, synchronized(monitor) { monitor.onPeerStatus(status) }, status.batteryPercent)
    }

    private fun dispatch(context: Context, events: List<PeerEvent>, battery: Int?) {
        if (events.isEmpty() || RoleStore.current(context) != Role.RECEIVER) return
        events.forEach { PeerNotifications.show(context, it, battery) }
    }
}
