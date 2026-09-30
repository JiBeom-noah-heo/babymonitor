package com.watchbabymonitor.mobile.ui

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.wearable.Node
import com.watchbabymonitor.common.datalayer.ControlClient
import com.watchbabymonitor.common.LinkMonitor
import com.watchbabymonitor.common.LinkState
import com.watchbabymonitor.common.RoleControl
import com.watchbabymonitor.common.RoleStore
import com.watchbabymonitor.common.StatusHub
import com.watchbabymonitor.common.service.ListenerService
import com.watchbabymonitor.common.service.MonitorService
import com.watchbabymonitor.shared.DetectionPreset
import com.watchbabymonitor.shared.DeviceStatus
import com.watchbabymonitor.shared.Role
import com.watchbabymonitor.shared.engine.SensorState
import com.watchbabymonitor.common.Engines
import com.watchbabymonitor.shared.engine.ReceiverState
import com.watchbabymonitor.shared.Constants
import com.watchbabymonitor.shared.ControlCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val TAG = Constants.logTag("HomeViewModel")

data class NodeInfo(val id: String, val displayName: String, val isNearby: Boolean)

data class HomeUiState(
    val nodes: List<NodeInfo> = emptyList(),
    val nodesLoading: Boolean = false,
    val pinging: Boolean = false,
    /** 최신 항목이 맨 앞. */
    val log: List<String> = emptyList(),
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val control = ControlClient(app)

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    val live: StateFlow<ReceiverState> = Engines.receiver.state
    val sensor: StateFlow<SensorState> = Engines.sensor.state
    val role: StateFlow<Role> = RoleStore.role(app)
    val preset: StateFlow<DetectionPreset> = RoleStore.preset(app)

    /** 상대 기기의 /status. 역할 충돌 경고·원격 제어 버튼에 사용. */
    val peer: StateFlow<DeviceStatus?> = StatusHub.peer

    /** 상대와의 연결 경로 (근처 / 원격 / 끊김). */
    val link: StateFlow<LinkState> = LinkMonitor.state

    /** 라이브 듣기 켜기/끄기. 앱이 화면에 있을 때만 호출 (포그라운드 서비스 시작 제한). */
    fun setLive(on: Boolean) {
        if (on) ListenerService.start(getApplication()) else ListenerService.stop(getApplication())
    }

    fun setRole(role: Role) = RoleControl.switchRole(getApplication(), role)

    fun setPreset(preset: DetectionPreset) = RoleControl.setPreset(getApplication(), preset)

    /** 감지기 모니터링 시작. 마이크 권한은 화면에서 먼저 받는다. */
    fun startMonitoring() = MonitorService.start(getApplication())

    fun stopMonitoring() = MonitorService.stop(getApplication())

    /**
     * 수신기에서 감지기 모니터링을 원격으로 시작/정지 (`/control START` / `STOP`).
     * 감지기 앱이 백그라운드면 `ERR:NEEDS_USER` → 감지기 기기에 "탭해서 모니터링 시작" 알림이 뜬다.
     */
    fun remoteMonitoring(start: Boolean) {
        viewModelScope.launch {
            val cmd = if (start) ControlCommand.Start else ControlCommand.Stop
            try {
                val node = control.connectedNodes().firstOrNull()
                if (node == null) {
                    appendLog("연결된 감지기 없음")
                    return@launch
                }
                val reply = control.send(node.id, cmd)
                Log.i(TAG, "${cmd.encode()} ${node.displayName} -> $reply")
                appendLog(
                    when (reply) {
                        Constants.CONTROL_REPLY_OK -> "${node.displayName}: 모니터링 ${if (start) "시작" else "정지"}"
                        Constants.CONTROL_REPLY_NEEDS_USER -> "${node.displayName}에서 알림을 눌러 시작해 주세요"
                        Constants.CONTROL_REPLY_WRONG_ROLE -> "${node.displayName}이(가) 감지기가 아니에요"
                        else -> "${node.displayName}: $reply"
                    },
                )
            } catch (e: TimeoutCancellationException) {
                appendLog("원격 ${cmd.encode()}: 응답 없음")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "remote ${cmd.encode()} failed", e)
                appendLog("원격 ${cmd.encode()} 실패: ${e.message}")
            }
        }
    }

    init {
        refreshNodes()
        viewModelScope.launch {
            Engines.receiver.alerts.collect { alert ->
                appendLog("소음 알림 ${alert.level.roundToInt()} dB")
            }
        }
    }

    fun refreshNodes() {
        viewModelScope.launch {
            _state.update { it.copy(nodesLoading = true) }
            try {
                val nodes = control.connectedNodes().map { it.toInfo() }
                Log.i(TAG, "connected nodes: ${nodes.size}")
                _state.update { it.copy(nodes = nodes) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "getConnectedNodes failed", e)
                appendLog("노드 조회 실패: ${e.message}")
            } finally {
                _state.update { it.copy(nodesLoading = false) }
            }
        }
    }

    /** 연결된 모든 노드에 PING 을 보내고 PONG 과 왕복 시간을 기록한다. */
    fun pingAll() {
        viewModelScope.launch {
            _state.update { it.copy(pinging = true) }
            try {
                val nodes = control.connectedNodes()
                _state.update { it.copy(nodes = nodes.map { n -> n.toInfo() }) }
                if (nodes.isEmpty()) {
                    appendLog("연결된 기기 없음")
                    return@launch
                }
                nodes.forEach { ping(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "pingAll failed", e)
                appendLog("PING 실패: ${e.message}")
            } finally {
                _state.update { it.copy(pinging = false) }
            }
        }
    }

    private suspend fun ping(node: Node) {
        val start = SystemClock.elapsedRealtime()
        try {
            val reply = control.send(node.id, ControlCommand.Ping)
            val rttMs = SystemClock.elapsedRealtime() - start
            Log.i(TAG, "PING ${node.displayName} -> $reply (${rttMs}ms)")
            appendLog("${node.displayName}: $reply (${rttMs} ms)")
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "PING ${node.displayName} timed out")
            appendLog("${node.displayName}: 응답 없음 (${Constants.CONTROL_REQUEST_TIMEOUT_MS} ms 초과)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "PING ${node.displayName} failed", e)
            appendLog("${node.displayName}: 실패 - ${e.message}")
        }
    }

    private fun appendLog(line: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _state.update { it.copy(log = (listOf("[$time] $line") + it.log).take(MAX_LOG)) }
    }

    private fun Node.toInfo() = NodeInfo(id = id, displayName = displayName, isNearby = isNearby)

    private companion object {
        const val MAX_LOG = 20
    }
}
