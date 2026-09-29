package com.watchbabymonitor.wear.service

import com.watchbabymonitor.shared.Constants
import com.watchbabymonitor.shared.NoiseAlert
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class MonitorState(
    val running: Boolean = false,
    val dbfs: Float = Constants.Audio.MIN_DBFS,
    val thresholdDbfs: Float = Constants.Alert.DEFAULT_THRESHOLD_DBFS,
    val alertCount: Int = 0,
    val lastAlert: NoiseAlert? = null,
    /** 마지막 알림을 폰에 보낸 노드 수. 0 이면 전달 실패. */
    val lastAlertDelivered: Int? = null,
    val error: String? = null,
)

/**
 * [MonitorService] 상태를 UI 로 전달하는 프로세스 내 저장소.
 */
object MonitorStatus {
    private val _state = MutableStateFlow(MonitorState())
    val state: StateFlow<MonitorState> = _state.asStateFlow()

    internal fun onStarted(thresholdDbfs: Float) = _state.update {
        it.copy(running = true, thresholdDbfs = thresholdDbfs, error = null)
    }

    internal fun onLevel(dbfs: Float) = _state.update { it.copy(dbfs = dbfs) }

    internal fun onAlert(alert: NoiseAlert) = _state.update {
        it.copy(alertCount = it.alertCount + 1, lastAlert = alert, lastAlertDelivered = null)
    }

    internal fun onAlertDelivered(alert: NoiseAlert, nodeCount: Int) = _state.update {
        if (it.lastAlert == alert) it.copy(lastAlertDelivered = nodeCount) else it
    }

    internal fun onError(message: String) = _state.update { it.copy(error = message) }

    internal fun onStopped() = _state.update {
        it.copy(running = false, dbfs = Constants.Audio.MIN_DBFS)
    }
}
