package com.watchbabymonitor.common

import android.content.Context
import android.os.BatteryManager
import com.watchbabymonitor.common.datalayer.StatusSync
import com.watchbabymonitor.common.history.HistoryRecorder
import com.watchbabymonitor.shared.DetectionConfig
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
import kotlinx.coroutines.flow.map
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
        this.sync = sync
        BatteryLog.start(app)
        LinkMonitor.start(app)
        PeerAlerts.start(app)
        HistoryRecorder.start(app)

        // 저장된 감지 설정을 엔진에, 엔진에서 바뀐 설정(원격 SET_* 포함)은 저장 (Phase 6)
        Engines.sensor.setConfig(RoleStore.config(app).value)
        scope.launch {
            Engines.sensor.state.map { it.config }.distinctUntilChanged().collect { RoleStore.setConfig(app, it) }
        }

        // 내 상태: 역할·모니터링·스트리밍·오류가 바뀔 때만 (레벨처럼 100ms 마다 바뀌는 값은 제외)
        scope.launch {
            combine(
                RoleStore.role(app),
                Engines.sensor.state,
                Engines.receiver.state,
                BatteryLog.percent,
            ) { role, sensor, receiver, battery ->
                when (role) {
                    Role.SENSOR -> Snapshot(role, sensor.running, sensor.streaming, sensor.micMuted, battery, sensor.error, sensor.config)
                    Role.RECEIVER -> Snapshot(role, false, receiver.phase != StreamPhase.IDLE, false, battery, receiver.error, null)
                }
            }.distinctUntilChanged().collect { snap ->
                val status = DeviceStatus(
                    role = snap.role,
                    monitoring = snap.monitoring,
                    streaming = snap.streaming,
                    batteryPercent = snap.battery ?: batteryPercent(app),
                    micMuted = snap.micMuted,
                    config = snap.config,
                    error = snap.error,
                    ts = System.currentTimeMillis(),
                )
                try {
                    sync.publish(status)
                    log.i("status published: role=${status.role} monitoring=${status.monitoring} streaming=${status.streaming} micMuted=${status.micMuted} battery=${status.batteryPercent}")
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
                .collect { status -> status?.let { acceptPeer(app, it) } }
        }
    }

    @Volatile
    private var sync: StatusSync? = null

    /**
     * 상대 상태 반영 + 수신기 알림 판단. 실시간 리스너와 백그라운드 리스너(DATA_CHANGED) 양쪽에서 온다.
     */
    fun acceptPeer(context: Context, status: DeviceStatus) {
        val current = _peer.value
        if (current == null || status.ts >= current.ts) {
            if (status != current) {
                log.i("peer status: role=${status.role} monitoring=${status.monitoring} micMuted=${status.micMuted} battery=${status.batteryPercent}")
            }
            _peer.value = status
        }
        PeerAlerts.onPeerStatus(context, status)
    }

    /** 백그라운드 리스너가 받은 DataItem 해석용. */
    fun decode(item: com.google.android.gms.wearable.DataItem): DeviceStatus? = sync?.decode(item)

    private data class Snapshot(
        val role: Role,
        val monitoring: Boolean,
        val streaming: Boolean,
        val micMuted: Boolean,
        val battery: Int?,
        val error: String?,
        val config: DetectionConfig?,
    )

    private fun batteryPercent(context: Context): Int? =
        context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }
}
