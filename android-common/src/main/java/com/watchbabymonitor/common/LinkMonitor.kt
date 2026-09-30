package com.watchbabymonitor.common

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.watchbabymonitor.common.datalayer.ControlClient
import com.watchbabymonitor.shared.Constants
import com.watchbabymonitor.shared.ControlCommand
import com.watchbabymonitor.shared.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** 상대 기기와의 연결 경로 (CLAUDE.md §3 전송 경로). */
enum class Link {
    UNKNOWN,

    /** 블루투스 직접 연결. */
    NEARBY,

    /** 블루투스가 끊겨 구글 클라우드 경유. 알림은 되지만 라이브 오디오는 지연·끊김 가능. */
    REMOTE,

    /** 상대에 닿을 수 없음. */
    DISCONNECTED,
}

data class LinkState(
    val link: Link = Link.UNKNOWN,
    val peerName: String? = null,
    /** 지금 상태가 된 시각 (epoch millis). */
    val sinceMs: Long = System.currentTimeMillis(),
)

/**
 * CapabilityClient 로 상대 기기 연결을 감시한다 (CLAUDE.md Phase 5).
 * 앱이 떠 있을 때는 실시간 리스너, 꺼져 있을 때는 [com.watchbabymonitor.common.service.DataLayerListenerService]
 * 의 `CAPABILITY_CHANGED` 가 [onNodes] 를 부른다.
 */
object LinkMonitor {
    private val log = AndroidLog("LinkMonitor")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(LinkState())
    val state: StateFlow<LinkState> = _state.asStateFlow()

    @Volatile
    private var started = false

    // CapabilityClient 가 말하는 상태와 닿을 수 있는 노드. 원격일 땐 PING 으로 다시 확인한다
    @Volatile
    private var reportedLink = Link.UNKNOWN

    @Volatile
    private var nodes: Set<Node> = emptySet()

    @Volatile
    private var probeFailures = 0

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val client = Wearable.getCapabilityClient(app)
        scope.launch {
            callbackFlow {
                val listener = CapabilityClient.OnCapabilityChangedListener { info -> trySend(info.nodes) }
                client.addListener(listener, Constants.Link.CAPABILITY).await()
                val initial = client.getCapability(Constants.Link.CAPABILITY, CapabilityClient.FILTER_REACHABLE).await()
                trySend(initial.nodes)
                awaitClose { client.removeListener(listener) }
            }
                .catch { e -> log.e("capability watch failed", e) }
                .collect { nodes -> onNodes(app, nodes) }
        }
        scope.launch { probeRemote(app) }
    }

    /**
     * 수신기이고 원격 연결일 때만, 주기적으로 PING 을 보내 실제로 닿는지 확인.
     * [Constants.Link.REMOTE_PROBE_FAILURES] 번 연속 실패하면 끊김으로, 다시 성공하면 원격으로.
     */
    private suspend fun probeRemote(context: Context) {
        val control = ControlClient(context)
        while (true) {
            delay(Constants.Link.REMOTE_PROBE_INTERVAL_MS)
            if (reportedLink != Link.REMOTE || RoleStore.current(context) != Role.RECEIVER) {
                probeFailures = 0
                continue
            }
            val node = nodes.firstOrNull() ?: continue
            val ok = try {
                control.send(node.id, ControlCommand.Ping, Constants.Link.REMOTE_PROBE_TIMEOUT_MS) == Constants.CONTROL_REPLY_PONG
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException) false else throw e
            } catch (e: Exception) {
                false
            }
            if (ok) {
                if (probeFailures >= Constants.Link.REMOTE_PROBE_FAILURES) {
                    log.i("remote probe ok again")
                    setLink(context, Link.REMOTE, node.displayName)
                }
                probeFailures = 0
            } else {
                probeFailures++
                log.w("remote probe failed ($probeFailures)")
                if (probeFailures == Constants.Link.REMOTE_PROBE_FAILURES && reportedLink == Link.REMOTE) {
                    setLink(context, Link.DISCONNECTED, node.displayName)
                }
            }
        }
    }

    /** 닿을 수 있는 상대 노드 목록이 바뀔 때. */
    fun onNodes(context: Context, nodes: Set<Node>) {
        val link = when {
            nodes.isEmpty() -> Link.DISCONNECTED
            nodes.any { it.isNearby } -> Link.NEARBY
            else -> Link.REMOTE
        }
        this.nodes = nodes
        if (link != reportedLink) probeFailures = 0
        reportedLink = link
        // 원격인데 PING 이 계속 실패 중이면 끊김 유지 (CapabilityClient 의 "닿음" 보고를 믿지 않음)
        if (link == Link.REMOTE && probeFailures >= Constants.Link.REMOTE_PROBE_FAILURES) return
        setLink(context, link, nodes.firstOrNull()?.displayName, nodes.size)
    }

    private fun setLink(context: Context, link: Link, name: String?, nodeCount: Int? = null) {
        if (link == _state.value.link) return
        log.i("link ${_state.value.link} -> $link${nodeCount?.let { " ($it nodes)" } ?: " (probe)"}")
        _state.value = LinkState(link = link, peerName = name ?: _state.value.peerName)
        PeerAlerts.onLink(context, connected = link != Link.DISCONNECTED)
    }
}
