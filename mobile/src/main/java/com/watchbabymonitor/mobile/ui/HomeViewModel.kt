package com.watchbabymonitor.mobile.ui

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.watchbabymonitor.mobile.service.AlertEvents
import com.watchbabymonitor.shared.Constants
import com.watchbabymonitor.shared.ControlCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
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

    private val nodeClient = Wearable.getNodeClient(app)
    private val messageClient = Wearable.getMessageClient(app)

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        refreshNodes()
        viewModelScope.launch {
            AlertEvents.alerts.collect { alert ->
                appendLog("소음 알림 ${alert.level.roundToInt()} dB")
            }
        }
    }

    fun refreshNodes() {
        viewModelScope.launch {
            _state.update { it.copy(nodesLoading = true) }
            try {
                val nodes = nodeClient.connectedNodes.await().map { it.toInfo() }
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
                val nodes = nodeClient.connectedNodes.await()
                _state.update { it.copy(nodes = nodes.map { n -> n.toInfo() }) }
                if (nodes.isEmpty()) {
                    appendLog("연결된 워치 없음")
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
            val reply = withTimeout(Constants.CONTROL_REQUEST_TIMEOUT_MS) {
                messageClient.sendRequest(node.id, Constants.Paths.CONTROL, ControlCommand.Ping.toBytes()).await()
            }.toString(Charsets.UTF_8)
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
