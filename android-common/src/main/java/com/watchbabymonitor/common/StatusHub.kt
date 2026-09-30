package com.watchbabymonitor.common

import android.content.Context
import android.os.BatteryManager
import com.watchbabymonitor.common.datalayer.StatusSync
import com.watchbabymonitor.shared.DeviceStatus
import com.watchbabymonitor.shared.Role
import com.watchbabymonitor.shared.engine.StreamPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * `/status` 동기화: 내 상태가 바뀔 때마다 올리고, 상대 상태를 [peer] 로 공개한다.
 * 앱이 시작될 때 Application.onCreate 에서 [start] 한 번.
 */
object StatusHub {
    private val log = AndroidLog("StatusHub")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _peer = MutableStateFlow<DeviceStatus?>(null)

    /** 상대 기기 상태. 아직 모르면 null. */
    val peer: StateFlow<DeviceStatus?> = _peer.asStateFlow()

    @Volatile
    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val sync = StatusSync(app, log)

        // 내 상태: 역할·모니터링·스트리밍·오류가 바뀔 때만 (레벨처럼 100ms 마다 바뀌는 값은 제외)
        scope.launch {
            combine(
                RoleStore.role(app),
                Engines.sensor.state,
                Engines.receiver.state,
            ) { role, sensor, receiver ->
                when (role) {
                    Role.SENSOR -> Snapshot(role, sensor.running, sensor.streaming, sensor.error)
                    Role.RECEIVER -> Snapshot(role, false, receiver.phase != StreamPhase.IDLE, receiver.error)
                }
            }.distinctUntilChanged().collect { snap ->
                val status = DeviceStatus(
                    role = snap.role,
                    monitoring = snap.monitoring,
                    streaming = snap.streaming,
                    batteryPercent = batteryPercent(app),
                    error = snap.error,
                    ts = System.currentTimeMillis(),
                )
                try {
                    sync.publish(status)
                    log.i("status published: role=${status.role} monitoring=${status.monitoring} streaming=${status.streaming} battery=${status.batteryPercent}")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.e("status publish failed", e)
                }
            }
        }

        // 상대 상태
        scope.launch {
            sync.peer()
                .catch { e -> log.e("peer status failed", e) }
                .collect { status ->
                    _peer.value = status
                    status?.let { log.i("peer status: role=${it.role} monitoring=${it.monitoring} battery=${it.batteryPercent}") }
                }
        }
    }

    private data class Snapshot(val role: Role, val monitoring: Boolean, val streaming: Boolean, val error: String?)

    private fun batteryPercent(context: Context): Int? =
        context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }
}
