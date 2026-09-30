package com.watchbabymonitor.wear.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [ControlReceiver] 가 받은 START/STOP 을 [MonitorService] 로 전달한다.
 * 값은 스트리밍 대상 노드 ID, null 이면 스트리밍 안 함.
 */
object StreamRequests {
    private val _target = MutableStateFlow<String?>(null)
    val target: StateFlow<String?> = _target.asStateFlow()

    fun start(nodeId: String) {
        _target.value = nodeId
    }

    fun stop() {
        _target.value = null
    }

    /** [nodeId] 로의 스트림이 끝났을 때. 그 사이 다른 요청이 왔으면 건드리지 않는다. */
    fun finished(nodeId: String) {
        _target.compareAndSet(nodeId, null)
    }
}
