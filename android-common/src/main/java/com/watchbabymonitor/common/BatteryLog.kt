package com.watchbabymonitor.common

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.watchbabymonitor.shared.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 배터리 측정용 (CLAUDE.md Phase 5 "1시간 연속 동작 배터리 소모 측정").
 * - [percent]: 배터리 % (변할 때마다) → /status 에 실림
 * - 1분마다 `files/battery_log.csv` 에 한 줄: 시각, %, 충전 중, 역할, 모니터링, 라이브, 연결
 *   (adb 로 꺼낼 때: `adb shell run-as com.watchbabymonitor cat files/battery_log.csv`)
 */
object BatteryLog {
    private const val FILE = "battery_log.csv"
    private const val INTERVAL_MS = 60_000L
    private const val MAX_BYTES = 512 * 1024L

    private val log = AndroidLog("BatteryLog")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _percent = MutableStateFlow<Int?>(null)
    val percent: StateFlow<Int?> = _percent.asStateFlow()

    @Volatile
    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            val file = File(app.filesDir, FILE)
            if (!file.exists() || file.length() > MAX_BYTES) {
                file.writeText("time,battery,charging,role,monitoring,streaming,link\n")
            }
            while (true) {
                val (pct, charging) = read(app)
                _percent.value = pct
                val role = RoleStore.current(app)
                val monitoring = role == Role.SENSOR && Engines.sensor.state.value.running
                val streaming = when (role) {
                    Role.SENSOR -> Engines.sensor.state.value.streaming
                    Role.RECEIVER -> Engines.receiver.state.value.phase != com.watchbabymonitor.shared.engine.StreamPhase.IDLE
                }
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                try {
                    file.appendText("$time,${pct ?: ""},$charging,$role,$monitoring,$streaming,${LinkMonitor.state.value.link}\n")
                } catch (e: Exception) {
                    log.e("battery log write failed", e)
                }
                delay(INTERVAL_MS)
            }
        }
    }

    /** 현재 % 와 충전 여부 (sticky 브로드캐스트, 리시버 등록 없이 읽기). */
    fun read(context: Context): Pair<Int?, Boolean> {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null to false
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else null
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return pct to charging
    }
}
