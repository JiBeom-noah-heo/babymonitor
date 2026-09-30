package com.watchbabymonitor.shared.engine

import com.watchbabymonitor.shared.Clock
import com.watchbabymonitor.shared.Constants
import com.watchbabymonitor.shared.DeviceStatus
import com.watchbabymonitor.shared.Role

/** 수신기가 사용자에게 알려야 할 상대(감지기) 쪽 사건. */
enum class PeerEvent {
    /** [Constants.Link.DISCONNECT_GRACE_MS] 넘게 연결이 끊김. */
    DISCONNECTED,

    /** [DISCONNECTED] 를 알린 뒤 다시 연결됨. */
    RECONNECTED,

    /** 감지기 배터리가 [Constants.Link.LOW_BATTERY_PERCENT] 이하. 충전돼서 기준을 넘기 전까지 한 번만. */
    LOW_BATTERY,

    /** 감지기 마이크가 막힘 (통화 중 등) → 그동안 아이 소리를 못 들음. */
    MIC_MUTED,

    /** 감지기 마이크가 다시 들림. */
    MIC_BACK,

    /** 감지기 모니터링이 꺼짐 (사용자가 감지기에서 끔 / 오류). 수신기가 원격으로 끈 직후는 제외. */
    SENSOR_STOPPED,

    /** 모니터링 중이던 감지기에서 [Constants.Link.SENSOR_SILENT_MS] 넘게 소식 없음 (재부팅·강제 종료 등). */
    SENSOR_SILENT,
}

/**
 * 수신기 쪽에서 감지기 상태·연결을 지켜보고 알릴 사건을 고른다 (Phase 5). 순수 Kotlin.
 * - 연결: 잠깐 흔들리는 건 무시하고 유예 시간 넘게 끊겨야 알림
 * - 배터리: 기준 이하로 떨어질 때 한 번, 기준+여유를 넘게 충전되면 다시 준비 (알림 반복 방지)
 * - 마이크: 막힘/복구가 바뀔 때만
 *
 * 한 코루틴(또는 동기화된 호출)에서만 쓴다.
 */
class PeerMonitor(
    private val clock: Clock = Clock.MONOTONIC,
    private val wallClock: Clock = Clock.WALL,
) {

    private var disconnectedSince: Long? = null
    private var disconnectNotified = false
    private var lowBatteryArmed = true
    private var micMuted = false
    private var lastTs = Long.MIN_VALUE
    private var sensorMonitoring = false
    private var expectedStopAt: Long? = null

    /** 연결 상태가 바뀔 때 (CapabilityClient 등). */
    fun onLink(connected: Boolean): List<PeerEvent> {
        if (!connected) {
            if (disconnectedSince == null) disconnectedSince = clock.nowMs()
            return emptyList()
        }
        disconnectedSince = null
        return if (disconnectNotified) {
            disconnectNotified = false
            listOf(PeerEvent.RECONNECTED)
        } else {
            emptyList()
        }
    }

    /** 주기적으로 호출 → 유예 시간이 지난 끊김을 알림. */
    fun tick(): List<PeerEvent> {
        val since = disconnectedSince ?: return emptyList()
        if (disconnectNotified || clock.nowMs() - since < Constants.Link.DISCONNECT_GRACE_MS) return emptyList()
        disconnectNotified = true
        return listOf(PeerEvent.DISCONNECTED)
    }

    /**
     * 상대의 `/status` 가 올 때. 감지기일 때만 배터리·마이크를 본다.
     * 오래된 상태·같은 상태의 중복 전달(실시간 리스너 + 백그라운드 리스너)은 무시.
     */
    fun onPeerStatus(status: DeviceStatus): List<PeerEvent> {
        if (status.ts <= lastTs) return emptyList()
        lastTs = status.ts
        if (status.role != Role.SENSOR) return emptyList()

        val events = mutableListOf<PeerEvent>()
        status.batteryPercent?.let { pct ->
            if (pct <= Constants.Link.LOW_BATTERY_PERCENT && lowBatteryArmed) {
                lowBatteryArmed = false
                events += PeerEvent.LOW_BATTERY
            } else if (pct > Constants.Link.LOW_BATTERY_REARM_PERCENT) {
                lowBatteryArmed = true
            }
        }
        if (status.micMuted != micMuted) {
            micMuted = status.micMuted
            events += if (micMuted) PeerEvent.MIC_MUTED else PeerEvent.MIC_BACK
        }
        if (sensorMonitoring && !status.monitoring) {
            val expected = expectedStopAt?.let { wallClock.nowMs() - it < Constants.Link.EXPECTED_STOP_WINDOW_MS } ?: false
            if (!expected) events += PeerEvent.SENSOR_STOPPED
            expectedStopAt = null
        }
        sensorMonitoring = status.monitoring
        return events
    }

    /** 수신기가 원격으로 STOP 을 보냄 → 곧 올 "모니터링 꺼짐" 은 알리지 않는다. */
    fun expectStop() {
        expectedStopAt = wallClock.nowMs()
    }

    companion object {
        /**
         * 마지막으로 받은 감지기 상태로 "소식 없음"인지 (알람 리시버에서 쓰는 상태 없는 판단).
         * 모니터링 중이던 감지기의 상태가 [Constants.Link.SENSOR_SILENT_MS] 넘게 새로 오지 않으면 true.
         */
        fun isSilent(status: DeviceStatus?, nowWallMs: Long): Boolean =
            status != null && status.role == Role.SENSOR && status.monitoring &&
                nowWallMs - status.ts > Constants.Link.SENSOR_SILENT_MS
    }
}
