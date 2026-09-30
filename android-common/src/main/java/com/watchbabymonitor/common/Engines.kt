package com.watchbabymonitor.common

import com.watchbabymonitor.shared.Clock
import com.watchbabymonitor.shared.engine.ReceiverEngine
import com.watchbabymonitor.shared.engine.SensorEngine

/**
 * 앱 프로세스의 엔진 (각각 하나). 어느 기기든 역할에 따라 둘 중 하나를 쓴다 (ADR 005, 006).
 * 로그 태그는 서비스 이름을 따라 기존 로그와 같게 유지.
 */
object Engines {
    val sensor = SensorEngine(clock = Clock.WALL, log = AndroidLog("MonitorService"))
    val receiver = ReceiverEngine(clock = Clock.MONOTONIC, log = AndroidLog("ListenerService"))
}
