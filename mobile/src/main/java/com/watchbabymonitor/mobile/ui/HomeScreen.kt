package com.watchbabymonitor.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.watchbabymonitor.shared.engine.ReceiverState
import com.watchbabymonitor.shared.engine.StreamPhase
import com.watchbabymonitor.mobile.ui.theme.WatchBabyMonitorTheme

@Composable
fun HomeScreen(modifier: Modifier = Modifier, viewModel: HomeViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val live by viewModel.live.collectAsStateWithLifecycle()
    HomeContent(
        state = state,
        live = live,
        onLiveChange = viewModel::setLive,
        onRefreshNodes = viewModel::refreshNodes,
        onPing = viewModel::pingAll,
        modifier = modifier,
    )
}

@Composable
private fun HomeContent(
    state: HomeUiState,
    live: ReceiverState,
    onLiveChange: (Boolean) -> Unit,
    onRefreshNodes: () -> Unit,
    onPing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("베이비 모니터", style = MaterialTheme.typography.headlineSmall)

        LiveCard(live = live, onLiveChange = onLiveChange)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "연결된 노드 (${state.nodes.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.nodesLoading) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
                Spacer(Modifier.padding(4.dp))
                if (state.nodes.isEmpty()) {
                    Text("연결된 워치가 없습니다", style = MaterialTheme.typography.bodyMedium)
                }
                state.nodes.forEach { node ->
                    Text(
                        "${node.displayName}${if (node.isNearby) " · 근처" else ""}",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        node.id,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPing, enabled = !state.pinging, modifier = Modifier.weight(1f)) {
                Text(if (state.pinging) "보내는 중…" else "PING 보내기")
            }
            OutlinedButton(onClick = onRefreshNodes, enabled = !state.nodesLoading) {
                Text("노드 새로고침")
            }
        }

        HorizontalDivider()
        Text("기록", style = MaterialTheme.typography.titleMedium)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(state.log) { line ->
                Text(line, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeContentPreview() {
    WatchBabyMonitorTheme {
        HomeContent(
            live = ReceiverState(phase = StreamPhase.PLAYING, peerName = "Galaxy Watch7", backlogMs = 320, kbps = 256),
            onLiveChange = {},
            state = HomeUiState(
                nodes = listOf(NodeInfo("3a1f9c2e", "Galaxy Watch7", isNearby = true)),
                log = listOf("[22:10:05] Galaxy Watch7: PONG (48 ms)"),
            ),
            onRefreshNodes = {},
            onPing = {},
        )
    }
}

@Composable
private fun LiveCard(live: ReceiverState, onLiveChange: (Boolean) -> Unit) {
    val on = live.phase != StreamPhase.IDLE
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("라이브 듣기", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when (live.phase) {
                            StreamPhase.IDLE -> "꺼짐"
                            StreamPhase.CONNECTING -> "연결 중…"
                            StreamPhase.PLAYING -> "${live.peerName ?: "감지기"} 소리 재생 중"
                            StreamPhase.STALLED -> "소리가 끊겼어요 (기다리는 중)"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (live.phase == StreamPhase.STALLED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Switch(checked = on, onCheckedChange = onLiveChange)
            }
            if (live.phase == StreamPhase.PLAYING || live.phase == StreamPhase.STALLED) {
                Text(
                    "폰 버퍼 ${live.backlogMs} ms · ${live.kbps} kbps\n끊김 ${live.underruns}회 · 재동기화 ${live.resyncs}회 (버림 ${live.droppedMs} ms)",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            live.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
