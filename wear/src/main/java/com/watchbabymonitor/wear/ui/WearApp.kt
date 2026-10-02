package com.watchbabymonitor.wear.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.dialog.Alert
import androidx.wear.compose.material.dialog.Dialog
import androidx.wear.compose.material.scrollAway
import com.google.android.gms.wearable.Wearable
import com.watchbabymonitor.common.Engines
import com.watchbabymonitor.common.Link
import com.watchbabymonitor.common.LinkMonitor
import com.watchbabymonitor.common.ReceiverPrefs
import com.watchbabymonitor.common.RoleControl
import com.watchbabymonitor.common.RoleStore
import com.watchbabymonitor.common.StatusHub
import com.watchbabymonitor.common.label
import com.watchbabymonitor.common.service.ControlEvents
import com.watchbabymonitor.common.service.ListenerService
import com.watchbabymonitor.common.service.MonitorService
import com.watchbabymonitor.shared.AudioLevel
import com.watchbabymonitor.shared.Constants
import com.watchbabymonitor.shared.DetectionPreset
import com.watchbabymonitor.shared.DeviceStatus
import com.watchbabymonitor.shared.NoiseAlert
import com.watchbabymonitor.shared.Role
import com.watchbabymonitor.shared.engine.ReceiverState
import com.watchbabymonitor.shared.engine.SensorState
import com.watchbabymonitor.shared.engine.StreamPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val TAG = Constants.logTag("WearApp")

@Composable
fun WearApp() {
    val context = LocalContext.current
    // 화면이 보일 때만 수집 (collectAsStateWithLifecycle). 예전엔 화면이 꺼져도 100ms 마다 상태를 받아
    // 메인 스레드가 71분 중 24분 일했다 (배터리 분석, 2026-10-01)
    val ping by ControlEvents.ping.collectAsStateWithLifecycle()
    val role by RoleStore.role(context).collectAsStateWithLifecycle()
    val config by RoleStore.config(context).collectAsStateWithLifecycle()
    val preset = config.preset
    val sensor by Engines.sensor.state.collectAsStateWithLifecycle()
    @OptIn(FlowPreview::class)
    val levelFlow = remember { Engines.sensor.level.sample(Constants.Audio.UI_LEVEL_INTERVAL_MS) }
    val level by levelFlow.collectAsStateWithLifecycle(Constants.Audio.MIN_DBFS)
    val receiver by Engines.receiver.state.collectAsStateWithLifecycle()
    val peer by StatusHub.peer.collectAsStateWithLifecycle()
    val link by LinkMonitor.state.collectAsStateWithLifecycle()
    val receiverPrefs by RoleStore.receiverPrefs(context).collectAsStateWithLifecycle()
    var connected by remember { mutableStateOf("확인 중…") }

    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    var micDenied by remember { mutableStateOf(false) }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        Log.i(TAG, "permissions: $result")
        micDenied = result[Manifest.permission.RECORD_AUDIO] != true
        if (!micDenied) MonitorService.start(context)
    }
    // 수신기: 알림(레벨 표시)용. 없어도 진동은 온다
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok -> Log.i(TAG, "POST_NOTIFICATIONS granted=$ok") }

    LaunchedEffect(Unit) {
        connected = try {
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            if (nodes.isEmpty()) "연결된 폰 없음" else nodes.joinToString { it.displayName }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "getConnectedNodes failed", e)
            "노드 조회 실패"
        }
    }

    WearScreen(
        header = "${role.label} · " + if (ping.count == 0) connected else "$connected · PING ${ping.count}",
        role = role,
        peer = peer,
        link = link.link,
        sensor = sensor,
        level = level,
        preset = preset,
        receiver = receiver,
        micDenied = micDenied,
        onMonitoringToggle = {
            when {
                sensor.running -> MonitorService.stop(context)
                granted(Manifest.permission.RECORD_AUDIO) -> MonitorService.start(context)
                else -> micLauncher.launch(sensorPermissions())
            }
        },
        onPresetToggle = {
            val next = DetectionPreset.entries[(preset.ordinal + 1) % DetectionPreset.entries.size]
            RoleControl.setPreset(context, next)
        },
        onLiveToggle = {
            if (receiver.phase == StreamPhase.IDLE) ListenerService.start(context) else ListenerService.stop(context)
        },
        receiverPrefs = receiverPrefs,
        onReceiverPrefs = { RoleStore.setReceiverPrefs(context, it) },
        onOpenSettings = {
            // 권한을 두 번 거부하면 다시 묻지 않으므로 앱 정보 화면으로 (Phase 2 에서 미룬 항목)
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        },
        onRoleToggle = {
            val next = role.opposite
            RoleControl.switchRole(context, next)
            if (next == Role.RECEIVER && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !granted(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
    )
}

private fun sensorPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // 포그라운드 서비스 알림 표시용 (없어도 모니터링은 동작)
        arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf(Manifest.permission.RECORD_AUDIO)
    }

@Composable
private fun WearScreen(
    header: String,
    role: Role,
    peer: DeviceStatus?,
    link: Link,
    sensor: SensorState,
    level: Float,
    preset: DetectionPreset,
    receiver: ReceiverState,
    micDenied: Boolean,
    onMonitoringToggle: () -> Unit,
    onPresetToggle: () -> Unit,
    onLiveToggle: () -> Unit,
    receiverPrefs: ReceiverPrefs,
    onReceiverPrefs: (ReceiverPrefs) -> Unit,
    onOpenSettings: () -> Unit,
    onRoleToggle: () -> Unit,
) {
    val listState = rememberScalingLazyListState()
    // 역할은 확인 창을 거쳐서만, 모니터링·라이브 듣기 중에는 잠금 (2026-10-01 손이 닿아 역할이 바뀐 일)
    var confirmRole by remember { mutableStateOf(false) }
    val busy = when (role) {
        Role.SENSOR -> sensor.running
        Role.RECEIVER -> receiver.phase != StreamPhase.IDLE
    }
    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background),
        ) {
            ScalingLazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                autoCentering = AutoCenteringParams(itemIndex = 1),
            ) {
                item { Caption(header) }
                when (link) {
                    Link.DISCONNECTED -> item { Warning("폰과 연결 끊김") }
                    Link.REMOTE -> item { Caption("원격 연결 (클라우드)") }
                    else -> Unit
                }
                if (role == Role.RECEIVER && peer?.role == Role.SENSOR && peer.micMuted) {
                    item { Warning("감지기 마이크 막힘 (통화 중?)") }
                }
                if (peer != null && Role.conflicts(role, peer.role)) {
                    item {
                        Text(
                            "두 기기 모두 ${role.label}예요",
                            style = MaterialTheme.typography.caption2,
                            color = MaterialTheme.colors.error,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                when (role) {
                    Role.SENSOR -> sensorItems(sensor, level, preset, micDenied, onMonitoringToggle, onPresetToggle, onOpenSettings)
                    Role.RECEIVER -> receiverItems(receiver, peer, onLiveToggle, receiverPrefs, onReceiverPrefs)
                }
                item {
                    if (busy) {
                        Caption("정지하면 역할을 바꿀 수 있어요")
                    } else {
                        CompactChip(
                            onClick = { confirmRole = true },
                            label = { Text("${role.opposite.label}로 바꾸기") },
                            colors = ChipDefaults.secondaryChipColors(),
                        )
                    }
                }
            }
            Dialog(showDialog = confirmRole, onDismissRequest = { confirmRole = false }) {
                Alert(
                    title = { Text("${role.opposite.label}로 바꿀까요?", textAlign = TextAlign.Center) },
                    message = {
                        Text(
                            if (role.opposite == Role.SENSOR) "폰은 수신기여야 알림을 받아요" else "폰은 감지기여야 소리를 감지해요",
                            style = MaterialTheme.typography.body2,
                            textAlign = TextAlign.Center,
                        )
                    },
                ) {
                    item {
                        Chip(
                            onClick = { confirmRole = false; onRoleToggle() },
                            label = { Text("바꾸기") },
                            colors = ChipDefaults.primaryChipColors(),
                        )
                    }
                    item {
                        Chip(
                            onClick = { confirmRole = false },
                            label = { Text("취소") },
                            colors = ChipDefaults.secondaryChipColors(),
                        )
                    }
                }
            }
            // 스크롤하면 시계가 위로 사라진다 (Phase 3.5 에서 목록과 겹치던 문제)
            TimeText(modifier = Modifier.scrollAway(listState))
        }
    }
}

@Composable
private fun Warning(text: String) {
    Text(text, style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.error, textAlign = TextAlign.Center)
}

@Composable
private fun Caption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.caption3,
        color = MaterialTheme.colors.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
    )
}

/** 감지기: 레벨, 시작/정지, 감지 조건. */
private fun ScalingLazyListScope.sensorItems(
    sensor: SensorState,
    level: Float,
    preset: DetectionPreset,
    micDenied: Boolean,
    onMonitoringToggle: () -> Unit,
    onPresetToggle: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    item {
        Text(
            text = if (sensor.running && level > Constants.Audio.MIN_DBFS) "${level.roundToInt()} dB" else "—",
            style = MaterialTheme.typography.title1,
            color = if (sensor.running) MaterialTheme.colors.primary else MaterialTheme.colors.onSurfaceVariant,
        )
    }
    item {
        LevelBar(
            dbfs = if (sensor.running) level else Constants.Audio.MIN_DBFS,
            thresholdDbfs = sensor.thresholdDbfs,
        )
    }
    item { StatusLine(monitor = sensor, micDenied = micDenied) }
    item {
        CompactChip(
            onClick = onMonitoringToggle,
            label = { Text(if (sensor.running) "정지" else "모니터링 시작") },
            colors = if (sensor.running) ChipDefaults.secondaryChipColors() else ChipDefaults.primaryChipColors(),
        )
    }
    item {
        CompactChip(
            onClick = onPresetToggle,
            label = { Text("조건: ${preset.label}") },
            colors = ChipDefaults.secondaryChipColors(),
        )
    }
    if (micDenied) {
        item {
            CompactChip(
                onClick = onOpenSettings,
                label = { Text("권한 설정 열기") },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
    }
}

/** 수신기: 마지막 알림(레벨), 감지기 상태, 라이브 듣기. 알림은 진동 우선 (CLAUDE.md §4-7). */
private fun ScalingLazyListScope.receiverItems(
    receiver: ReceiverState,
    peer: DeviceStatus?,
    onLiveToggle: () -> Unit,
    prefs: ReceiverPrefs,
    onPrefs: (ReceiverPrefs) -> Unit,
) {
    val last: NoiseAlert? = receiver.lastAlert
    item {
        Text(
            text = last?.let { "${it.level.roundToInt()} dB" } ?: "알림 없음",
            style = MaterialTheme.typography.title1,
            color = if (last != null) MaterialTheme.colors.error else MaterialTheme.colors.onSurfaceVariant,
        )
    }
    item {
        Caption(last?.let { "알림 ${receiver.alertCount}회 · 마지막 ${hhmmss(it.ts)}" } ?: "소리가 나면 진동으로 알려요")
    }
    item {
        Caption(
            when {
                peer == null -> "감지기 상태 모름"
                peer.role != Role.SENSOR -> "상대가 감지기가 아니에요"
                else -> "감지기 ${if (peer.monitoring) "모니터링 중" else "꺼짐"}" +
                    (peer.batteryPercent?.let { " · $it%" } ?: "")
            },
        )
    }
    item {
        CompactChip(
            onClick = onLiveToggle,
            label = {
                Text(
                    when (receiver.phase) {
                        StreamPhase.IDLE -> "라이브 듣기"
                        StreamPhase.CONNECTING -> "연결 중… (끄기)"
                        StreamPhase.PLAYING -> "듣는 중 (끄기)"
                        StreamPhase.STALLED -> "끊김 (끄기)"
                        StreamPhase.RECONNECTING -> "재연결 중 (끄기)"
                    },
                )
            },
            colors = if (receiver.phase == StreamPhase.IDLE) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
        )
    }
    receiver.error?.let { err ->
        item {
            Text(err, style = MaterialTheme.typography.caption3, color = MaterialTheme.colors.error, textAlign = TextAlign.Center)
        }
    }
    item {
        CompactChip(
            onClick = { onPrefs(prefs.copy(autoListen = !prefs.autoListen)) },
            label = { Text("자동 듣기: ${if (prefs.autoListen) "켬" else "끔"}") },
            colors = ChipDefaults.secondaryChipColors(),
        )
    }
    item {
        // 원격(클라우드)이면 소리가 구글 서버를 거침 → 기본 꺼짐 (CLAUDE.md §8)
        CompactChip(
            onClick = { onPrefs(prefs.copy(allowRemoteLive = !prefs.allowRemoteLive)) },
            label = { Text("원격 듣기: ${if (prefs.allowRemoteLive) "허용" else "안 함"}") },
            colors = ChipDefaults.secondaryChipColors(),
        )
    }
}

/** 레벨 바 + 임계값 위치 표시. */
@Composable
private fun LevelBar(dbfs: Float, thresholdDbfs: Float) {
    val fraction by animateFloatAsState(
        targetValue = AudioLevel.normalize(dbfs),
        animationSpec = tween(durationMillis = Constants.Audio.UI_LEVEL_INTERVAL_MS.toInt()),
        label = "level",
    )
    val over = dbfs >= thresholdDbfs
    BoxWithConstraints(
        modifier = Modifier
            .padding(vertical = 6.dp, horizontal = 12.dp)
            .fillMaxWidth()
            .height(12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colors.surface),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .background(if (over) MaterialTheme.colors.error else MaterialTheme.colors.primary),
        )
        Box(
            modifier = Modifier
                .offset(x = maxWidth * AudioLevel.normalize(thresholdDbfs))
                .width(2.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colors.onSurface),
        )
    }
}

@Composable
private fun StatusLine(monitor: SensorState, micDenied: Boolean) {
    // shared 모듈 타입이라 스마트 캐스트가 안 됨 → 지역 변수로
    val error = monitor.error
    val lastAlert = monitor.lastAlert
    val (text, isError) = when {
        micDenied -> "마이크 권한이 필요해요" to true
        error != null -> error to true
        monitor.streaming -> "수신기로 소리 보내는 중" to false
        lastAlert != null -> alertSummary(monitor.alertCount, lastAlert, monitor.lastAlertDelivered) to false
        monitor.running -> "기준 ${monitor.thresholdDbfs.roundToInt()} dB" to false
        else -> "정지됨" to false
    }
    Text(
        text = text,
        style = MaterialTheme.typography.caption3,
        color = if (isError) MaterialTheme.colors.error else MaterialTheme.colors.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 2,
    )
}

private fun alertSummary(count: Int, alert: NoiseAlert, delivered: Int?): String {
    val sent = when (delivered) {
        null -> "전송 중"
        0 -> "전송 실패"
        else -> "전송됨"
    }
    return "알림 ${count}회 · ${hhmmss(alert.ts)} $sent"
}

private fun hhmmss(ts: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ts))

@Preview(device = "id:wearos_small_round", showSystemUi = true)
@Composable
private fun ReceiverPreview() {
    WearScreen(
        header = "수신기 · Galaxy S26+",
        role = Role.RECEIVER,
        peer = DeviceStatus(role = Role.SENSOR, monitoring = true, batteryPercent = 80, ts = 0L),
        link = Link.NEARBY,
        sensor = SensorState(),
        level = -40f,
        preset = DetectionPreset.CAR,
        receiver = ReceiverState(alertCount = 2, lastAlert = NoiseAlert(-18f, 0L)),
        micDenied = false,
        onMonitoringToggle = {},
        onPresetToggle = {},
        onLiveToggle = {},
        receiverPrefs = ReceiverPrefs(),
        onReceiverPrefs = {},
        onOpenSettings = {},
        onRoleToggle = {},
    )
}
