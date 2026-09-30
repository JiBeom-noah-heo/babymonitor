package com.watchbabymonitor.mobile.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.watchbabymonitor.common.ReceiverPrefs
import com.watchbabymonitor.shared.DetectionConfig
import com.watchbabymonitor.shared.DetectionPreset
import com.watchbabymonitor.shared.Role
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** 설정 화면 (CLAUDE.md Phase 6): 감지 조건, 수신기 옵션, 권한·배터리 안내. */
@Composable
fun SettingsScreen(viewModel: HomeViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    val role by viewModel.role.collectAsStateWithLifecycle()
    val localConfig by viewModel.config.collectAsStateWithLifecycle()
    val peer by viewModel.peer.collectAsStateWithLifecycle()
    val receiverPrefs by viewModel.receiverPrefs.collectAsStateWithLifecycle()

    // 감지 설정의 대상: 이 폰이 감지기면 자기 것, 수신기면 상대 감지기 것(/status)
    val remoteConfig = peer?.takeIf { it.role == Role.SENSOR }?.config
    val (target, editable) = when (role) {
        Role.SENSOR -> localConfig to true
        Role.RECEIVER -> (remoteConfig ?: DetectionConfig()) to (remoteConfig != null)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← 뒤로") }
                Text("설정", style = MaterialTheme.typography.headlineSmall)
            }
        }
        item {
            DetectionCard(
                title = if (role == Role.SENSOR) "감지 조건 (이 폰)" else "감지 조건 (상대 감지기)",
                config = target,
                editable = editable,
                note = if (role == Role.RECEIVER && remoteConfig == null) {
                    "상대 감지기의 설정을 아직 모릅니다. 감지기 앱을 한 번 열면 표시돼요."
                } else {
                    null
                },
                onChange = { new ->
                    if (role == Role.SENSOR) viewModel.setConfig(new) else viewModel.setRemoteConfig(target, new)
                },
            )
        }
        item { ReceiverCard(receiverPrefs, viewModel::setReceiverPrefs) }
        item { SystemCard() }
    }
}

@Composable
private fun DetectionCard(
    title: String,
    config: DetectionConfig,
    editable: Boolean,
    note: String?,
    onChange: (DetectionConfig) -> Unit,
) {
    // 슬라이더는 끄는 동안 로컬 값만 바꾸고, 손을 떼면 한 번 적용
    var threshold by remember(config) { mutableFloatStateOf(config.thresholdDbfs) }
    var cooldownSec by remember(config) { mutableFloatStateOf(config.cooldownMs / 1000f) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DetectionPreset.entries.forEach { p ->
                    FilterChip(
                        selected = p == config.preset,
                        enabled = editable,
                        onClick = { onChange(DetectionConfig.of(p)) },
                        label = { Text(p.label) },
                    )
                }
            }
            Text("임계값 ${threshold.roundToInt()} dB (낮을수록 작은 소리에도 알림)", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = threshold,
                onValueChange = { threshold = it },
                onValueChangeFinished = { onChange(config.copy(thresholdDbfs = threshold.roundToInt().toFloat())) },
                valueRange = DetectionConfig.MIN_THRESHOLD_DBFS..DetectionConfig.MAX_THRESHOLD_DBFS,
                enabled = editable,
            )
            Text("알림 간격(쿨다운) ${formatSeconds(cooldownSec)}", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = cooldownSec,
                onValueChange = { cooldownSec = it },
                onValueChangeFinished = { onChange(config.copy(cooldownMs = (cooldownSec.roundToLong() * 1000))) },
                valueRange = (DetectionConfig.MIN_COOLDOWN_MS / 1000f)..(DetectionConfig.MAX_COOLDOWN_MS / 1000f),
                enabled = editable,
            )
            Text(
                "소리가 ${config.preset.windowMs / 1000.0}초 중 ${config.preset.sustainMs / 1000.0}초 넘게 임계값을 넘으면 알림",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (config.isCustomized) {
                OutlinedButton(onClick = { onChange(DetectionConfig.of(config.preset)) }, enabled = editable) {
                    Text("${config.preset.label} 기본값으로")
                }
            }
        }
    }
}

private fun formatSeconds(sec: Float): String {
    val s = sec.roundToInt()
    return if (s < 60) "${s}초" else "${s / 60}분 ${s % 60}초"
}

@Composable
private fun ReceiverCard(prefs: ReceiverPrefs, onChange: (ReceiverPrefs) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("수신기", style = MaterialTheme.typography.titleMedium)
            SwitchRow(
                title = "알림 오면 자동으로 듣기",
                subtitle = "앱이 화면에 있을 때 바로 시작해요. 앱이 꺼져 있으면 알림의 \"듣기\" 버튼을 눌러 주세요.",
                checked = prefs.autoListen,
                onChange = { onChange(prefs.copy(autoListen = it)) },
            )
            SwitchRow(
                title = "원격(클라우드) 연결에서도 라이브 듣기",
                subtitle = "블루투스가 끊기면 소리가 구글 서버를 거쳐 전달돼요(암호화). 지연 1~3초. 알림은 이 설정과 상관없이 와요.",
                checked = prefs.allowRemoteLive,
                onChange = { onChange(prefs.copy(allowRemoteLive = it)) },
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 권한·배터리 최적화 (CLAUDE.md §8 "삼성 배터리 최적화가 서비스를 죽일 수 있음"). */
@Composable
private fun SystemCard() {
    val context = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    var micGranted by remember { mutableStateOf(false) }
    var batteryExempt by remember { mutableStateOf(false) }
    LaunchedEffect(refresh) {
        micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        batteryExempt = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("권한 · 배터리", style = MaterialTheme.typography.titleMedium)
            Text(
                "마이크 권한 (감지기용): ${if (micGranted) "허용됨" else "없음"}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "배터리 최적화: ${if (batteryExempt) "제외됨 (권장)" else "적용 중 — 삼성 기기에서 감시가 멈출 수 있어요"}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (batteryExempt) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            )
            Text(
                "앱 설정 → 배터리 → \"제한 없음\"을 골라 주세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { openAppSettings(context); refresh++ }) { Text("앱 설정 열기") }
                OutlinedButton(onClick = { refresh++ }) { Text("다시 확인") }
            }
        }
    }
}

/** 권한을 두 번 거부했거나 배터리 설정이 필요할 때 (Phase 2 에서 미룬 항목). */
fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
