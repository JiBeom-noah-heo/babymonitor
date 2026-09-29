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
import com.watchbabymonitor.mobile.ui.theme.WatchBabyMonitorTheme

@Composable
fun HomeScreen(modifier: Modifier = Modifier, viewModel: HomeViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HomeContent(
        state = state,
        onRefreshNodes = viewModel::refreshNodes,
        onPing = viewModel::pingAll,
        modifier = modifier,
    )
}

@Composable
private fun HomeContent(
    state: HomeUiState,
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
        Text("연결 확인", style = MaterialTheme.typography.headlineSmall)

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
            state = HomeUiState(
                nodes = listOf(NodeInfo("3a1f9c2e", "Galaxy Watch7", isNearby = true)),
                log = listOf("[22:10:05] Galaxy Watch7: PONG (48 ms)"),
            ),
            onRefreshNodes = {},
            onPing = {},
        )
    }
}
