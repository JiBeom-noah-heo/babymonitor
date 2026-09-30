package com.watchbabymonitor.mobile.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.watchbabymonitor.common.Link
import com.watchbabymonitor.common.LinkState
import com.watchbabymonitor.common.label
import com.watchbabymonitor.mobile.ui.theme.WatchBabyMonitorTheme
import com.watchbabymonitor.shared.AudioLevel
import com.watchbabymonitor.shared.Constants
import com.watchbabymonitor.shared.DetectionPreset
import com.watchbabymonitor.shared.DeviceStatus
import com.watchbabymonitor.shared.Role
import com.watchbabymonitor.shared.engine.ReceiverState
import com.watchbabymonitor.shared.engine.SensorState
import com.watchbabymonitor.shared.engine.StreamPhase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(),
    onOpenSettings: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val live by viewModel.live.collectAsStateWithLifecycle()
    val sensor by viewModel.sensor.collectAsStateWithLifecycle()
    val role by viewModel.role.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val preset = config.preset
    val peer by viewModel.peer.collectAsStateWithLifecycle()
    val link by viewModel.link.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var micDenied by remember { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micDenied = !granted
        if (granted) viewModel.startMonitoring()
    }

    HomeContent(
        role = role,
        peer = peer,
        link = link,
        onRoleChange = viewModel::setRole,
        sensor = sensor,
        preset = preset,
        micDenied = micDenied,
        onMonitoringChange = { on ->
            when {
                !on -> viewModel.stopMonitoring()
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED -> viewModel.startMonitoring()
                else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        onPresetChange = viewModel::setPreset,
        live = live,
        onLiveChange = viewModel::setLive,
        onRemoteMonitoring = viewModel::remoteMonitoring,
        state = state,
        onRefreshNodes = viewModel::refreshNodes,
        onPing = viewModel::pingAll,
        onOpenSettings = onOpenSettings,
        onOpenHistory = onOpenHistory,
        modifier = modifier,
    )
}

@Composable
private fun HomeContent(
    role: Role,
    peer: DeviceStatus?,
    link: LinkState,
    onRoleChange: (Role) -> Unit,
    sensor: SensorState,
    preset: DetectionPreset,
    micDenied: Boolean,
    onMonitoringChange: (Boolean) -> Unit,
    onPresetChange: (DetectionPreset) -> Unit,
    live: ReceiverState,
    onLiveChange: (Boolean) -> Unit,
    onRemoteMonitoring: (Boolean) -> Unit,
    state: HomeUiState,
    onRefreshNodes: () -> Unit,
    onPing: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("베이비 모니터", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenHistory) { Text("기록") }
                TextButton(onClick = onOpenSettings) { Text("설정") }
            }
        }
        item { RoleCard(role = role, peer = peer, link = link, onRoleChange = onRoleChange) }
        when (role) {
            Role.SENSOR -> item {
                SensorCard(sensor, preset, micDenied, onMonitoringChange, onPresetChange)
            }
            Role.RECEIVER -> {
                item { RemoteCard(peer = peer, alerts = live, onRemoteMonitoring = onRemoteMonitoring) }
                item { LiveCard(live = live, onLiveChange = onLiveChange) }
            }
        }
        item { NodesCard(state = state, onRefreshNodes = onRefreshNodes, onPing = onPing) }
        item { Text("기록", style = MaterialTheme.typography.titleMedium) }
        items(state.log) { line ->
            Text(line, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        }
    }
}

/** 역할 선택 + 상대 기기 상태 + 역할 충돌 경고 (CLAUDE.md §1, §3). */
@Composable
private fun RoleCard(role: Role, peer: DeviceStatus?, link: LinkState, onRoleChange: (Role) -> Unit) {
    val conflict = peer != null && Role.conflicts(role, peer.role)
    val warnings = peerWarnings(role, peer, link)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (conflict) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("이 폰의 역할", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Role.entries.forEach { r ->
                    FilterChip(
                        selected = r == role,
                        onClick = { onRoleChange(r) },
                        label = { Text(if (r == Role.SENSOR) "감지기 (아이 옆)" else "수신기 (부모)") },
                    )
                }
            }
            Text(linkSummary(link), style = MaterialTheme.typography.bodyMedium)
            Text(peerSummary(peer), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            warnings.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            if (conflict) {
                Text(
                    "두 기기 모두 ${role.label}예요. 한쪽을 ${role.opposite.label}로 바꿔 주세요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

/** 연결 경로 (CLAUDE.md §3): 근처 = 블루투스, 원격 = 클라우드 경유. */
private fun linkSummary(link: LinkState): String = when (link.link) {
    Link.UNKNOWN -> "연결: 확인 중…"
    Link.NEARBY -> "연결: 근처 (블루투스)"
    Link.REMOTE -> "연결: 원격 (클라우드 경유 · 라이브 오디오는 지연될 수 있어요)"
    Link.DISCONNECTED -> "연결: 끊김 (${hhmm(link.sinceMs)}부터)"
}

/** 수신기에서 보여줄 감지기 경고 (Phase 5). */
private fun peerWarnings(role: Role, peer: DeviceStatus?, link: LinkState): List<String> {
    val w = mutableListOf<String>()
    if (link.link == Link.DISCONNECTED) w += "상대 기기와 연결이 끊겼어요"
    if (role == Role.RECEIVER && peer?.role == Role.SENSOR) {
        if (peer.micMuted) w += "감지기 마이크가 막혔어요 (통화 중이면 아이 소리를 못 들어요)"
        peer.batteryPercent?.let { if (it <= Constants.Link.LOW_BATTERY_PERCENT) w += "감지기 배터리 $it%" }
    }
    return w
}

private fun peerSummary(peer: DeviceStatus?): String {
    if (peer == null) return "상대 기기: 아직 상태를 모름"
    val parts = mutableListOf("상대 기기: ${peer.role.label}")
    if (peer.role == Role.SENSOR) parts += if (peer.monitoring) "모니터링 중" else "모니터링 꺼짐"
    peer.batteryPercent?.let { parts += "배터리 $it%" }
    parts += "(${hhmm(peer.ts)} 기준)"
    return parts.joinToString(" · ")
}

/** 이 폰이 감지기일 때: 모니터링 시작/정지, 레벨, 프리셋 (시나리오 B, 차 안 등). */
@Composable
private fun SensorCard(
    sensor: SensorState,
    preset: DetectionPreset,
    micDenied: Boolean,
    onMonitoringChange: (Boolean) -> Unit,
    onPresetChange: (DetectionPreset) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("소리 감지", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            sensor.streaming -> "수신기로 소리 보내는 중"
                            sensor.running -> "감지 중 · 기준 ${sensor.thresholdDbfs.roundToInt()} dB"
                            else -> "꺼짐"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = sensor.running, onCheckedChange = onMonitoringChange)
            }
            if (sensor.running) {
                Text(
                    if (sensor.dbfs > Constants.Audio.MIN_DBFS) "${sensor.dbfs.roundToInt()} dB" else "—",
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (sensor.dbfs >= sensor.thresholdDbfs) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
                LinearProgressIndicator(
                    progress = { AudioLevel.normalize(sensor.dbfs) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text("감지 조건", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DetectionPreset.entries.forEach { p ->
                    FilterChip(
                        selected = p == preset,
                        onClick = { onPresetChange(p) },
                        label = { Text("${p.label} (${p.thresholdDbfs.roundToInt()} dB)") },
                    )
                }
            }
            sensor.lastAlert?.let { a ->
                val sent = when (sensor.lastAlertDelivered) {
                    null -> "전송 중"
                    0 -> "전송 실패"
                    else -> "전송됨"
                }
                Text("알림 ${sensor.alertCount}회 · 마지막 ${hhmm(a.ts)} ${a.level.roundToInt()} dB $sent", style = MaterialTheme.typography.bodySmall)
            }
            val error = sensor.error ?: if (micDenied) "마이크 권한이 필요해요" else null
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** 이 폰이 수신기일 때: 받은 알림, 감지기 원격 시작/정지. */
@Composable
private fun RemoteCard(peer: DeviceStatus?, alerts: ReceiverState, onRemoteMonitoring: (Boolean) -> Unit) {
    val sensorMonitoring = peer?.role == Role.SENSOR && peer.monitoring
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("감지기", style = MaterialTheme.typography.titleMedium)
            Text(
                alerts.lastAlert?.let { "알림 ${alerts.alertCount}회 · 마지막 ${hhmm(it.ts)} ${it.level.roundToInt()} dB" }
                    ?: "받은 알림 없음",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = { onRemoteMonitoring(!sensorMonitoring) }) {
                Text(if (sensorMonitoring) "감지기 모니터링 정지" else "감지기 모니터링 시작")
            }
        }
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
                            StreamPhase.RECONNECTING -> "다시 연결하는 중…"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (live.phase == StreamPhase.STALLED || live.phase == StreamPhase.RECONNECTING) {
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

@Composable
private fun NodesCard(state: HomeUiState, onRefreshNodes: () -> Unit, onPing: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "연결된 기기 (${state.nodes.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (state.nodesLoading) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            if (state.nodes.isEmpty()) {
                Text("연결된 기기가 없습니다", style = MaterialTheme.typography.bodyMedium)
            }
            state.nodes.forEach { node ->
                // 근처 = 블루투스 직접 연결, 원격 = 클라우드 경유 (CLAUDE.md §3 전송 경로)
                Text(
                    "${node.displayName} · ${if (node.isNearby) "근처" else "원격(클라우드)"}",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPing, enabled = !state.pinging, modifier = Modifier.weight(1f)) {
                    Text(if (state.pinging) "보내는 중…" else "PING 보내기")
                }
                OutlinedButton(onClick = onRefreshNodes, enabled = !state.nodesLoading) {
                    Text("새로고침")
                }
            }
        }
    }
}

private fun hhmm(ts: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ts))

@Preview(showBackground = true)
@Composable
private fun HomeContentPreview() {
    WatchBabyMonitorTheme {
        HomeContent(
            role = Role.RECEIVER,
            peer = DeviceStatus(role = Role.SENSOR, monitoring = true, batteryPercent = 92, ts = 0L),
            link = LinkState(Link.NEARBY),
            onRoleChange = {},
            sensor = SensorState(),
            preset = DetectionPreset.HOME,
            micDenied = false,
            onMonitoringChange = {},
            onPresetChange = {},
            live = ReceiverState(phase = StreamPhase.PLAYING, peerName = "Galaxy Watch7", backlogMs = 320, kbps = 256),
            onLiveChange = {},
            onRemoteMonitoring = {},
            state = HomeUiState(
                nodes = listOf(NodeInfo("3a1f9c2e", "Galaxy Watch7", isNearby = true)),
                log = listOf("[22:10:05] Galaxy Watch7: PONG (48 ms)"),
            ),
            onRefreshNodes = {},
            onPing = {},
            onOpenSettings = {},
            onOpenHistory = {},
        )
    }
}
