package com.watchbabymonitor.shared.engine

import com.watchbabymonitor.shared.Clock
import com.watchbabymonitor.shared.Constants
import com.watchbabymonitor.shared.ControlCommand
import com.watchbabymonitor.shared.DetectionConfig
import com.watchbabymonitor.shared.DetectionPreset
import com.watchbabymonitor.shared.NoiseAlert
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SensorEngineTest {

    // 상수 진폭 A → 20·log10(A/32768) dBFS
    private val quiet = ShortArray(Constants.Audio.SAMPLES_PER_WINDOW) { 58 }     // 약 -55 dBFS
    private val loud = ShortArray(Constants.Audio.SAMPLES_PER_WINDOW) { 3277 }    // 약 -20 dBFS

    /** 호출될 때마다 한 프레임(100ms)씩 가는 시계. */
    private class StepClock : Clock {
        var t = 0L
        override fun nowMs(): Long {
            t += Constants.Audio.LEVEL_WINDOW_MS
            return t
        }
    }

    private class FakeAlerts(var result: Delivery = Delivery(1, 1), var fail: Boolean = false) : AlertSink {
        val sent = mutableListOf<NoiseAlert>()
        override suspend fun send(alert: NoiseAlert): Delivery {
            sent += alert
            if (fail) throw IOException("no route")
            return result
        }
    }

    private class FakeStream(private val failAfter: Int? = null) : StreamSink {
        val frames = mutableListOf<ShortArray>()
        var aborted = false
        val finished = CompletableDeferred<Unit>()
        override suspend fun send(frames: ReceiveChannel<ShortArray>) {
            try {
                for (f in frames) {
                    this.frames += f
                    if (failAfter != null && this.frames.size >= failAfter) throw IOException("peer closed")
                }
            } finally {
                finished.complete(Unit)
            }
        }
        override fun abort() {
            aborted = true
        }
    }

    private class FakeStreams : StreamSinkFactory {
        val created = mutableListOf<Pair<String, FakeStream>>()
        var failAfter: Int? = null
        override fun create(nodeId: String) = FakeStream(failAfter).also { created += nodeId to it }
    }

    private fun engine() = SensorEngine(clock = StepClock())

    /** 테스트가 프레임을 하나씩 밀어 넣을 수 있게 엔진을 백그라운드로 돌린다. */
    private fun TestScope.startEngine(
        engine: SensorEngine,
        alerts: AlertSink = FakeAlerts(),
        streams: StreamSinkFactory = FakeStreams(),
    ): Channel<ShortArray> {
        val input = Channel<ShortArray>(Channel.UNLIMITED)
        backgroundScope.launch { engine.run(input.consumeAsFlow(), alerts, streams) }
        runCurrent()
        return input
    }

    @Test
    fun quietFrames_updateLevel_noAlert() = runTest {
        val e = engine()
        val alerts = FakeAlerts()
        e.run(flow { repeat(20) { emit(quiet) } }, alerts, FakeStreams())
        assertTrue(alerts.sent.isEmpty())
        assertEquals(0, e.state.value.alertCount)
        assertFalse("run 이 끝나면 정지 상태", e.state.value.running)
    }

    @Test
    fun runningState_andLevel_whileCollecting() = runTest {
        val e = engine()
        val input = startEngine(e)
        assertTrue(e.state.value.running)
        input.send(quiet)
        runCurrent()
        assertEquals(-55f, e.level.value, 0.5f)
    }

    @Test
    fun loudForOneSecond_sendsOneAlert_andRecordsDelivery() = runTest {
        val e = engine()
        val alerts = FakeAlerts(Delivery(1, 1))
        e.run(flow { repeat(10) { emit(loud) } }, alerts, FakeStreams())
        advanceUntilIdle()
        assertEquals(1, alerts.sent.size)
        assertEquals(1, e.state.value.alertCount)
        assertEquals(1, e.state.value.lastAlertDelivered)
        assertNull(e.state.value.error)
    }

    @Test
    fun alertNotDelivered_setsError() = runTest {
        val e = engine()
        e.run(flow { repeat(10) { emit(loud) } }, FakeAlerts(Delivery(0, 1)), FakeStreams())
        assertEquals(0, e.state.value.lastAlertDelivered)
        assertEquals("수신기에 알림 전달 실패", e.state.value.error)
    }

    @Test
    fun alertSinkThrows_countsAsNotDelivered_monitoringContinues() = runTest {
        val e = engine()
        val input = startEngine(e, alerts = FakeAlerts(fail = true))
        repeat(10) { input.send(loud) }
        runCurrent()
        assertEquals(0, e.state.value.lastAlertDelivered)
        assertTrue(e.state.value.running)
    }

    @Test
    fun captureFailure_setsError_andRethrows() = runTest {
        val e = engine()
        val result = runCatching {
            e.run(flow { emit(quiet); throw IllegalStateException("mic busy") }, FakeAlerts(), FakeStreams())
        }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals("마이크 오류: mic busy", e.state.value.error)
        assertFalse(e.state.value.running)
    }

    // ---- /control ----

    @Test
    fun ping_repliesPong_evenWhenIdle() {
        assertEquals(ControlResult("PONG"), engine().onControl(ControlCommand.Ping, "n", canStartMonitoring = false))
    }

    @Test
    fun streamOn_whenIdle_isRejected() {
        val r = engine().onControl(ControlCommand.StreamOn, "phone", canStartMonitoring = true)
        assertEquals(ControlResult(Constants.CONTROL_REPLY_NOT_MONITORING), r)
    }

    @Test
    fun start_whenIdle_andForeground_asksPlatformToStart() {
        val r = engine().onControl(ControlCommand.Start, "phone", canStartMonitoring = true)
        assertEquals(ControlResult("OK", ControlEffect.START_MONITORING), r)
    }

    @Test
    fun start_whenIdle_andBackground_needsUser() {
        val r = engine().onControl(ControlCommand.Start, "phone", canStartMonitoring = false)
        assertEquals(ControlResult(Constants.CONTROL_REPLY_NEEDS_USER, ControlEffect.PROMPT_USER_TO_START), r)
    }

    @Test
    fun start_and_stop_whileRunning() = runTest {
        val e = engine()
        startEngine(e)
        assertEquals(ControlResult("OK"), e.onControl(ControlCommand.Start, "p", canStartMonitoring = false))
        assertEquals(ControlResult("OK", ControlEffect.STOP_MONITORING), e.onControl(ControlCommand.Stop, "p", false))
    }

    @Test
    fun stop_whenIdle_isNoop() {
        assertEquals(ControlResult("OK"), engine().onControl(ControlCommand.Stop, "p", canStartMonitoring = false))
    }

    @Test
    fun unknownCommand() {
        assertEquals(ControlResult(Constants.CONTROL_REPLY_UNKNOWN), engine().onControl(null, "p", false))
    }

    // ---- 스트리밍 ----

    @Test
    fun streamOn_forwardsFrames_toRequestingNode() = runTest {
        val e = engine()
        val streams = FakeStreams()
        val input = startEngine(e, streams = streams)

        assertEquals(ControlResult("OK"), e.onControl(ControlCommand.StreamOn, "phone-1", false))
        runCurrent()
        assertTrue(e.state.value.streaming)

        repeat(3) { input.send(quiet) }
        runCurrent()
        val (node, sink) = streams.created.single()
        assertEquals("phone-1", node)
        assertEquals(3, sink.frames.size)
    }

    @Test
    fun streamOff_abortsSink_andStopsForwarding() = runTest {
        val e = engine()
        val streams = FakeStreams()
        val input = startEngine(e, streams = streams)
        e.onControl(ControlCommand.StreamOn, "phone", false)
        input.send(quiet)
        runCurrent()

        e.onControl(ControlCommand.StreamOff, "phone", false)
        input.send(quiet)
        runCurrent()

        val sink = streams.created.single().second
        assertTrue(sink.aborted)
        assertEquals(1, sink.frames.size)
        assertFalse(e.state.value.streaming)
    }

    @Test
    fun streamOn_again_replacesPreviousStream() = runTest {
        val e = engine()
        val streams = FakeStreams()
        startEngine(e, streams = streams)
        e.onControl(ControlCommand.StreamOn, "a", false)
        e.onControl(ControlCommand.StreamOn, "b", false)
        runCurrent()
        assertEquals(listOf("a", "b"), streams.created.map { it.first })
        assertTrue(streams.created[0].second.aborted)
        assertTrue(e.state.value.streaming)
    }

    @Test
    fun streamSinkFailure_endsStreaming_butMonitoringContinues() = runTest {
        val e = engine()
        val streams = FakeStreams().apply { failAfter = 2 }
        val input = startEngine(e, streams = streams)
        e.onControl(ControlCommand.StreamOn, "phone", false)
        repeat(3) { input.send(quiet) }
        runCurrent()
        assertFalse(e.state.value.streaming)
        assertTrue(e.state.value.running)
    }

    @Test
    fun stoppingMonitoring_endsStream() = runTest {
        val e = engine()
        val streams = FakeStreams()
        val input = startEngine(e, streams = streams)
        e.onControl(ControlCommand.StreamOn, "phone", false)
        runCurrent()
        input.close() // 녹음 종료
        runCurrent() // backgroundScope 작업은 advanceUntilIdle 로는 진행 안 됨
        assertTrue(streams.created.single().second.aborted)
        assertFalse(e.state.value.streaming)
        assertFalse(e.state.value.running)
    }

    // ---- 임계값 ----

    @Test
    fun setThreshold_appliesOnNextFrame() = runTest {
        val e = engine()
        val alerts = FakeAlerts()
        val input = startEngine(e, alerts = alerts)
        e.onControl(ControlCommand.SetThreshold(-10f), "phone", false)
        repeat(20) { input.send(loud) } // -20 dBFS < -10 → 알림 없음
        runCurrent()
        assertEquals(-10f, e.state.value.thresholdDbfs, 0f)
        assertTrue(alerts.sent.isEmpty())
    }

    // ---- 프리셋 ----

    @Test
    fun carPreset_ignoresNoiseThatHomeWouldAlertOn() = runTest {
        // -30 dBFS 가 1.5초: 집 안(-35)이면 알림, 차 안(-25)이면 무시
        val mid = ShortArray(Constants.Audio.SAMPLES_PER_WINDOW) { 1036 } // 약 -30 dBFS
        val home = FakeAlerts()
        engine().run(flow { repeat(15) { emit(mid) } }, home, FakeStreams(), DetectionConfig.of(DetectionPreset.HOME))
        val car = FakeAlerts()
        engine().run(flow { repeat(15) { emit(mid) } }, car, FakeStreams(), DetectionConfig.of(DetectionPreset.CAR))
        assertEquals(1, home.sent.size)
        assertTrue(car.sent.isEmpty())
    }

    @Test
    fun carPreset_needsLongerSustain() = runTest {
        val alerts = FakeAlerts()
        val louder = ShortArray(Constants.Audio.SAMPLES_PER_WINDOW) { 16384 } // 약 -6 dBFS
        // 1.0초는 차 안 기준(1.5초) 미달, 1.5초면 알림
        engine().run(flow { repeat(10) { emit(louder) }; repeat(30) { emit(quiet) } }, alerts, FakeStreams(), DetectionConfig.of(DetectionPreset.CAR))
        assertTrue(alerts.sent.isEmpty())
        engine().run(flow { repeat(15) { emit(louder) } }, alerts, FakeStreams(), DetectionConfig.of(DetectionPreset.CAR))
        assertEquals(1, alerts.sent.size)
    }

    @Test
    fun setPreset_whileRunning_appliesOnNextFrame() = runTest {
        val e = engine()
        val alerts = FakeAlerts()
        val input = startEngine(e, alerts = alerts)
        assertEquals(DetectionPreset.HOME, e.state.value.preset)
        e.setPreset(DetectionPreset.CAR)
        input.send(quiet)
        runCurrent()
        assertEquals(DetectionPreset.CAR, e.state.value.preset)
        assertEquals(Constants.Alert.CAR_THRESHOLD_DBFS, e.state.value.thresholdDbfs, 0f)
        repeat(20) { input.send(loud) } // -20 dBFS >= -25 → 2초면 알림
        runCurrent()
        assertEquals(1, alerts.sent.size)
    }

    @Test
    fun setPreset_whenIdle_updatesStateImmediately() {
        val e = engine()
        e.setPreset(DetectionPreset.CAR)
        assertEquals(DetectionPreset.CAR, e.state.value.preset)
        assertEquals(-25f, e.state.value.thresholdDbfs, 0f)
    }

    // ---- 마이크 막힘 (Phase 5) ----

    @Test
    fun zeroInputFor3s_setsMicMuted_andRecovers() = runTest {
        val e = engine()
        val input = startEngine(e)
        val zero = ShortArray(Constants.Audio.SAMPLES_PER_WINDOW)
        repeat(29) { input.send(zero) }
        runCurrent()
        assertFalse("2.9초는 아직", e.state.value.micMuted)
        input.send(zero)
        runCurrent()
        assertTrue(e.state.value.micMuted)
        input.send(quiet)
        runCurrent()
        assertFalse(e.state.value.micMuted)
    }

    @Test
    fun quietRoom_isNotMuted() = runTest {
        val e = engine()
        val input = startEngine(e)
        repeat(100) { input.send(quiet) }
        runCurrent()
        assertFalse(e.state.value.micMuted)
    }

    // ---- 설정 (Phase 6) ----

    @Test
    fun setCooldown_viaControl_appliesWithoutResettingPreset() = runTest {
        val e = engine()
        val alerts = FakeAlerts()
        val input = startEngine(e, alerts = alerts)
        assertEquals(ControlResult("OK"), e.onControl(ControlCommand.SetCooldown(10_000), "p", false))
        repeat(110) { input.send(loud) } // 11초 계속 시끄러움
        runCurrent()
        assertEquals(10_000L, e.state.value.config.cooldownMs)
        assertEquals(DetectionPreset.HOME, e.state.value.preset)
        assertEquals("1초, 11초에 알림 (쿨다운 10초)", 2, alerts.sent.size)
    }

    @Test
    fun setPreset_viaControl_resetsCustomValues() {
        val e = engine()
        e.onControl(ControlCommand.SetThreshold(-40f), "p", false)
        e.onControl(ControlCommand.SetPreset(DetectionPreset.CAR), "p", false)
        assertEquals(DetectionConfig.of(DetectionPreset.CAR), e.state.value.config)
    }

    @Test
    fun setConfig_clampsOutOfRange() {
        val e = engine()
        e.setConfig(DetectionConfig(thresholdDbfs = -200f, cooldownMs = 1))
        assertEquals(DetectionConfig.MIN_THRESHOLD_DBFS, e.state.value.thresholdDbfs, 0f)
        assertEquals(DetectionConfig.MIN_COOLDOWN_MS, e.state.value.config.cooldownMs)
    }

    @Test
    fun run_usesConfigSetWhileIdle() = runTest {
        val e = engine()
        e.setConfig(DetectionConfig(thresholdDbfs = -10f))
        startEngine(e)
        assertEquals(-10f, e.state.value.thresholdDbfs, 0f)
    }

    @Test
    fun levelChanges_doNotChangeState() = runTest {
        // 상태 수집기가 100ms 마다 깨어나지 않도록, 레벨은 state 를 바꾸지 않는다
        val e = engine()
        val input = startEngine(e)
        val before = e.state.value
        repeat(5) { input.send(if (it % 2 == 0) loud else quiet) } // 마지막은 loud
        runCurrent()
        assertTrue(before === e.state.value)
        assertEquals(-20f, e.level.value, 0.5f)
    }

    @Test
    fun levelResetsWhenStopped() = runTest {
        val e = engine()
        e.run(flow { emit(loud) }, FakeAlerts(), FakeStreams())
        assertEquals(Constants.Audio.MIN_DBFS, e.level.value, 0f)
    }

    @Test
    fun reportError_setsError() {
        val e = engine()
        e.reportError("마이크 권한 없음")
        assertEquals("마이크 권한 없음", e.state.value.error)
    }
}
