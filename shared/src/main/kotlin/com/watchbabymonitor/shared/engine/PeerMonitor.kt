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
}

/**
 * 수신기 쪽에서 감지기 상태·연결을 지켜보고 알릴 사건을 고른다 (Phase 5). 순수 Kotlin.
 * - 연결: 잠깐 흔들리는 건 무시하고 유예 시간 넘게 끊겨야 알림
 * - 배터리: 기준 이하로 떨어질 때 한 번, 기준+여유를 넘게 충전되면 다시 준비 (알림 반복 방지)
 * - 마이크: 막힘/복구가 바뀔 때만
 *
 * 한 코루틴(또는 동기화된 호출)에서만 쓴다.
 */
class PeerMonitor(private val clock: Clock = Clock.MONOTONIC) {

    private var disconnectedSince: Long? = null
    private var disconnectNotified = false
    private var lowBatteryArmed = true
    private var micMuted = false
    private var lastTs = Long.MIN_VALUE

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

    /** 상대의 `/status` 가 올 때. 감지기일 때만 배터리·마이크를 본다. 오래된 상태는 무시. */
    fun onPeerStatus(status: DeviceStatus): List<PeerEvent> {
        if (status.ts < lastTs) return emptyList()
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
        return events
    }
}
