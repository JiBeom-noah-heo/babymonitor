package com.watchbabymonitor.wear.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 워치가 마지막으로 받은 PING 정보. */
data class PingState(
    val count: Int = 0,
    val lastAtMillis: Long? = null,
    val fromNodeId: String? = null,
)

/**
 * [ControlReceiver] 가 받은 제어 이벤트를 UI 로 전달하는 프로세스 내 버스.
 */
object ControlEvents {
    private val _ping = MutableStateFlow(PingState())
    val ping: StateFlow<PingState> = _ping.asStateFlow()

    fun onPing(fromNodeId: String) {
        _ping.update {
            PingState(
                count = it.count + 1,
                lastAtMillis = System.currentTimeMillis(),
                fromNodeId = fromNodeId,
            )
        }
    }
}
