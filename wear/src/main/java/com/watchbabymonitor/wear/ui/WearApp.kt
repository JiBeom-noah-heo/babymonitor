package com.watchbabymonitor.wear.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.google.android.gms.wearable.Wearable
import com.watchbabymonitor.shared.Constants
import com.watchbabymonitor.wear.service.ControlEvents
import com.watchbabymonitor.wear.service.PingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TAG = Constants.logTag("WearApp")

@Composable
fun WearApp() {
    val context = LocalContext.current
    val ping by ControlEvents.ping.collectAsState()
    var connected by remember { mutableStateOf("확인 중…") }

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

    WearScreen(connected = connected, ping = ping)
}

@Composable
private fun WearScreen(connected: String, ping: PingState) {
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
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = connected,
                    style = MaterialTheme.typography.caption1,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = if (ping.count == 0) "PING 대기 중" else "PING #${ping.count}\n→ PONG 회신",
                    modifier = Modifier.padding(vertical = 6.dp),
                    style = MaterialTheme.typography.title3,
                    color = MaterialTheme.colors.primary,
                    textAlign = TextAlign.Center,
                )
                ping.lastAtMillis?.let {
                    Text(
                        text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it)),
                        style = MaterialTheme.typography.caption2,
                    )
                }
            }
        }
    }
}

@Preview(device = "id:wearos_small_round", showSystemUi = true)
@Composable
private fun WearScreenPreview() {
    WearScreen(connected = "Galaxy S25", ping = PingState(count = 3, lastAtMillis = 0L, fromNodeId = "abc"))
}
