package com.watchbabymonitor.wear.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.google.android.gms.wearable.Wearable
import com.watchbabymonitor.common.label
import com.watchbabymonitor.shared.AudioLevel
import com.watchbabymonitor.common.RoleStore
import com.watchbabymonitor.shared.Constants
import com.watchbabymonitor.shared.NoiseAlert
import com.watchbabymonitor.common.service.ControlEvents
import com.watchbabymonitor.common.service.MonitorService
import com.watchbabymonitor.shared.engine.SensorState
import com.watchbabymonitor.common.Engines
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val TAG = Constants.logTag("WearApp")

@Composable
fun WearApp() {
    val context = LocalContext.current
    val ping by ControlEvents.ping.collectAsState()
    val monitor by Engines.sensor.state.collectAsState()
    var connected by remember { mutableStateOf("확인 중…") }

    fun hasMicPermission() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    var micDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        Log.i(TAG, "permissions: $result")
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            micDenied = false
            MonitorService.start(context)
        } else {
            micDenied = true
        }
    }

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
        header = "${RoleStore.current(context).label} · " + if (ping.count == 0) connected else "$connected · PING ${ping.count}",
        monitor = monitor,
        micDenied = micDenied,
        onToggle = {
            when {
                monitor.running -> MonitorService.stop(context)
                hasMicPermission() -> MonitorService.start(context)
                else -> permissionLauncher.launch(requiredPermissions())
            }
        },
    )
}

private fun requiredPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // 포그라운드 서비스 알림 표시용 (없어도 모니터링은 동작)
        arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf(Manifest.permission.RECORD_AUDIO)
    }

@Composable
private fun WearScreen(
    header: String,
    monitor: SensorState,
    micDenied: Boolean,
    onToggle: () -> Unit,
) {
    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background),
        ) {
            TimeText()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = header,
                    style = MaterialTheme.typography.caption3,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (monitor.running && monitor.dbfs > Constants.Audio.MIN_DBFS) {
                        "${monitor.dbfs.roundToInt()} dB"
                    } else {
                        "—"
                    },
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.title1,
                    color = if (monitor.running) MaterialTheme.colors.primary else MaterialTheme.colors.onSurfaceVariant,
                )
                LevelBar(dbfs = if (monitor.running) monitor.dbfs else Constants.Audio.MIN_DBFS, thresholdDbfs = monitor.thresholdDbfs)
                StatusLine(monitor = monitor, micDenied = micDenied)
                CompactChip(
                    onClick = onToggle,
                    modifier = Modifier.padding(top = 6.dp),
                    label = { Text(if (monitor.running) "정지" else "모니터링 시작") },
                    colors = if (monitor.running) ChipDefaults.secondaryChipColors() else ChipDefaults.primaryChipColors(),
                )
            }
        }
    }
}

/** 레벨 바 + 임계값 위치 표시. */
@Composable
private fun LevelBar(dbfs: Float, thresholdDbfs: Float) {
    val fraction by animateFloatAsState(
        targetValue = AudioLevel.normalize(dbfs),
        animationSpec = tween(durationMillis = Constants.Audio.LEVEL_WINDOW_MS),
        label = "level",
    )
    val over = dbfs >= thresholdDbfs
    BoxWithConstraints(
        modifier = Modifier
            .padding(vertical = 6.dp)
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
        monitor.streaming -> "폰으로 소리 보내는 중" to false
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
    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(alert.ts))
    val sent = when (delivered) {
        null -> "전송 중"
        0 -> "전송 실패"
        else -> "전송됨"
    }
    return "알림 ${count}회 · $time $sent"
}

@Preview(device = "id:wearos_small_round", showSystemUi = true)
@Composable
private fun WearScreenPreview() {
    WearScreen(
        header = "Galaxy S25 · PING 3",
        monitor = SensorState(running = true, dbfs = -28f, alertCount = 2, lastAlert = NoiseAlert(-18f, 0L), lastAlertDelivered = 1),
        micDenied = false,
        onToggle = {},
    )
}
