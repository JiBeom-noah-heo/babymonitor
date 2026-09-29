package com.watchbabymonitor.mobile.service

import com.watchbabymonitor.shared.NoiseAlert
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** [WearMessageReceiver] 가 받은 알림을 화면으로 전달하는 프로세스 내 버스. */
object AlertEvents {
    private val _alerts = MutableSharedFlow<NoiseAlert>(extraBufferCapacity = 16)
    val alerts: SharedFlow<NoiseAlert> = _alerts.asSharedFlow()

    fun emit(alert: NoiseAlert) {
        _alerts.tryEmit(alert)
    }
}
