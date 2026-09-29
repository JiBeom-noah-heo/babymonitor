package com.watchbabymonitor.wear.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.google.android.gms.wearable.Wearable
import com.watchbabymonitor.shared.AudioLevel
import com.watchbabymonitor.shared.Constants
import com.watchbabymonitor.wear.audio.LevelMeter
import com.watchbabymonitor.wear.service.ControlEvents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlin.math.roundToInt

private val TAG = Constants.logTag("WearApp")

private sealed interface MicState {
    data object NeedPermission : MicState
    data object Denied : MicState
    data object Running : MicState
    data class Error(val message: String) : MicState
}

@Composable
fun WearApp() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val ping by ControlEvents.ping.collectAsState()
    var connected by remember { mutableStateOf("확인 중…") }

    val meter = remember { LevelMeter() }
    val dbfs by meter.dbfs.collectAsState()

    fun hasMicPermission() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    var micState by remember {
        mutableStateOf(if (hasMicPermission()) MicState.Running else MicState.NeedPermission)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        Log.i(TAG, "RECORD_AUDIO granted=$granted")
        micState = if (granted) MicState.Running else MicState.Denied
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

    // 화면이 보이는 동안만 측정 (Phase 3 에서 MonitorService 로 이동)
    LaunchedEffect(micState) {
        if (micState != MicState.Running) return@LaunchedEffect
        try {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (hasMicPermission()) meter.run()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "level meter failed", e)
            micState = MicState.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    WearScreen(
        header = if (ping.count == 0) connected else "$connected · PING ${ping.count}",
        micState = micState,
        dbfs = dbfs,
        onRequestPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
    )
}

@Composable
private fun WearScreen(
    header: String,
    micState: MicState,
    dbfs: Float,
    onRequestPermission: () -> Unit,
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
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = header,
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
                when (micState) {
                    MicState.Running -> LevelDisplay(dbfs)
                    MicState.NeedPermission, MicState.Denied -> PermissionPrompt(
                        denied = micState == MicState.Denied,
                        onRequestPermission = onRequestPermission,
                    )
                    is MicState.Error -> Text(
                        text = "마이크 오류\n${micState.message}",
                        modifier = Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colors.error,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.caption1,
                    )
                }
            }
        }
    }
}

@Composable
private fun LevelDisplay(dbfs: Float) {
    val fraction by animateFloatAsState(
        targetValue = AudioLevel.normalize(dbfs),
        animationSpec = tween(durationMillis = Constants.Audio.LEVEL_WINDOW_MS),
        label = "level",
    )
    Text(
        text = if (dbfs <= Constants.Audio.MIN_DBFS) "—" else "${dbfs.roundToInt()} dB",
        modifier = Modifier.padding(top = 6.dp),
        style = MaterialTheme.typography.display3,
        color = MaterialTheme.colors.primary,
    )
    Box(
        modifier = Modifier
            .padding(vertical = 8.dp)
            .fillMaxWidth()
            .height(12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colors.surface),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .background(MaterialTheme.colors.primary),
        )
    }
    Text(
        text = "dBFS",
        style = MaterialTheme.typography.caption3,
        color = MaterialTheme.colors.onSurfaceVariant,
    )
}

@Composable
private fun PermissionPrompt(denied: Boolean, onRequestPermission: () -> Unit) {
    Text(
        text = if (denied) "마이크 권한이 거부됐어요.\n다시 누르거나 설정에서 허용해 주세요."
        else "소리를 측정하려면\n마이크 권한이 필요해요.",
        modifier = Modifier.padding(vertical = 8.dp),
        style = MaterialTheme.typography.caption1,
        textAlign = TextAlign.Center,
    )
    Chip(
        onClick = onRequestPermission,
        label = { Text("마이크 허용") },
    )
}

@Preview(device = "id:wearos_small_round", showSystemUi = true)
@Composable
private fun WearScreenPreview() {
    WearScreen(header = "Galaxy S25 · PING 3", micState = MicState.Running, dbfs = -35f, onRequestPermission = {})
}
