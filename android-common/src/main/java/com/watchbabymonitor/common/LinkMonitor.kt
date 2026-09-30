package com.watchbabymonitor.common

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.watchbabymonitor.shared.Constants
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
    }

    /** 닿을 수 있는 상대 노드 목록이 바뀔 때. */
    fun onNodes(context: Context, nodes: Set<Node>) {
        val link = when {
            nodes.isEmpty() -> Link.DISCONNECTED
            nodes.any { it.isNearby } -> Link.NEARBY
            else -> Link.REMOTE
        }
        val name = nodes.firstOrNull()?.displayName ?: _state.value.peerName
        if (link != _state.value.link) {
            log.i("link ${_state.value.link} -> $link (${nodes.size} nodes)")
            _state.value = LinkState(link = link, peerName = name)
            PeerAlerts.onLink(context, connected = link != Link.DISCONNECTED)
        }
    }
}
