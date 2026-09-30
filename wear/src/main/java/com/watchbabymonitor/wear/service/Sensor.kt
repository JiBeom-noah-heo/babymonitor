package com.watchbabymonitor.wear.service

import com.watchbabymonitor.common.AndroidLog
import com.watchbabymonitor.shared.Clock
import com.watchbabymonitor.shared.engine.SensorEngine

/**
 * 워치 프로세스의 감지기 엔진 (하나만). [MonitorService] 가 돌리고,
 * [ControlReceiver] 와 화면이 상태를 읽고 명령을 넘긴다.
 */
object Sensor {
    val engine = SensorEngine(clock = Clock.WALL, log = AndroidLog("MonitorService"))
}
