package com.watchbabymonitor.mobile.service

import com.watchbabymonitor.common.AndroidLog
import com.watchbabymonitor.shared.Clock
import com.watchbabymonitor.shared.engine.ReceiverEngine

/**
 * 폰 프로세스의 수신기 엔진 (하나만). [ListenerService] 와 [WearMessageReceiver] 가 이벤트를 넘기고,
 * 화면이 상태를 읽는다.
 */
object Receiver {
    val engine = ReceiverEngine(clock = Clock.MONOTONIC, log = AndroidLog("ListenerService"))
}
