package com.watchbabymonitor.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.watchbabymonitor.common.history.NoiseEvent
import com.watchbabymonitor.shared.DetectionPreset
import com.watchbabymonitor.shared.Role
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** 소음 이벤트 기록 (CLAUDE.md Phase 6). 30일 보관. */
@Composable
fun HistoryScreen(viewModel: HomeViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    val events by viewModel.history.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← 뒤로") }
                Text("소음 기록", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                if (events.isNotEmpty()) TextButton(onClick = viewModel::clearHistory) { Text("지우기") }
            }
        }
        if (events.isEmpty()) {
            item { Text("아직 기록이 없어요.", style = MaterialTheme.typography.bodyMedium) }
        }
        items(events, key = { it.id }) { EventRow(it) }
        item {
            Text(
                "최근 200건 · 30일 지난 기록은 자동으로 지워져요. 소리 자체는 저장하지 않아요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EventRow(e: NoiseEvent) {
    val sent = e.role == Role.SENSOR.name
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(FORMAT.format(Date(e.ts)), style = MaterialTheme.typography.bodyLarge)
                Text(
                    buildString {
                        append(if (sent) "보냄" else "받음")
                        e.peerName?.let { append(if (sent) " → $it" else " ← $it") }
                        e.preset?.let { p -> DetectionPreset.entries.firstOrNull { it.name == p }?.let { append(" · ${it.label}") } }
                        if (sent) {
                            append(
                                when (e.delivered) {
                                    null -> ""
                                    0 -> " · 전달 실패"
                                    else -> " · 전달됨"
                                },
                            )
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (sent && e.delivered == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("${e.level.roundToInt()} dB", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}

private val FORMAT = SimpleDateFormat("M/d (E) HH:mm:ss", Locale.KOREA)
