package com.watchbabymonitor.shared

import kotlinx.serialization.Serializable

/**
 * 기기 역할 (CLAUDE.md §1). 두 기기의 역할은 서로 반대여야 한다.
 * - [SENSOR]: 아이 옆. 마이크 캡처, 알림·오디오 송신
 * - [RECEIVER]: 부모 쪽. 알림 수신, 라이브 듣기
 */
@Serializable
enum class Role {
    SENSOR,
    RECEIVER,
    ;

    val opposite: Role
        get() = when (this) {
            SENSOR -> RECEIVER
            RECEIVER -> SENSOR
        }

    companion object {
        /** 역할 선택 화면(Phase 3.5) 전까지의 고정값. 시나리오 A: 워치 = 감지기, 폰 = 수신기. */
        val DEFAULT_WATCH = SENSOR
        val DEFAULT_PHONE = RECEIVER

        /** 두 기기의 역할이 겹치면 true → 수신기 UI 에 경고 (CLAUDE.md §3). */
        fun conflicts(mine: Role, peer: Role): Boolean = mine == peer
    }
}
