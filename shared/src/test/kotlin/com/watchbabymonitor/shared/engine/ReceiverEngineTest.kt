package com.watchbabymonitor.shared.engine

import com.watchbabymonitor.shared.Clock
import com.watchbabymonitor.shared.Constants
import com.watchbabymonitor.shared.NoiseAlert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverEngineTest {

    private class FakeClock(var t: Long = 1_000) : Clock {
        override fun nowMs() = t
    }

    private val clock = FakeClock()
    private val engine = ReceiverEngine(clock)
    private val stats = PlaybackStats(backlogMs = 320, underruns = 1, droppedMs = 0, resyncs = 0)

    private fun playing() {
        engine.sessionStarted()
        engine.peerFound("Galaxy Watch7")
        engine.channelOpened()
    }

    @Test
    fun sessionStarted_isConnecting_andClearsPreviousError() {
        engine.sessionEnded(StreamEnd.DISCONNECTED)
        engine.sessionStarted()
        assertEquals(StreamPhase.CONNECTING, engine.state.value.phase)
        assertNull(engine.state.value.error)
    }

    @Test
    fun streamOnReply_mapping() {
        assertNull(engine.onStreamOnReply("OK"))
        assertEquals(StreamEnd.SENSOR_NOT_MONITORING, engine.onStreamOnReply(Constants.CONTROL_REPLY_NOT_MONITORING))
        assertEquals(StreamEnd.REJECTED, engine.onStreamOnReply("ERR:UNKNOWN"))
    }

    @Test
    fun onData_updatesStatsEveryInterval_withKbps() {
        playing()
        clock.t += 250
        var asked = 0
        engine.onData(8_000) { asked++; stats }
        assertEquals("구간이 안 찼으면 갱신 안 함", 0, asked)
        assertEquals(StreamPhase.CONNECTING, engine.state.value.phase)

        clock.t += 250
        engine.onData(8_000) { asked++; stats }
        assertEquals(1, asked)
        val s = engine.state.value
        assertEquals(StreamPhase.PLAYING, s.phase)
        assertEquals(256, s.kbps) // 16000 B * 8 / 500 ms
        assertEquals(320L, s.backlogMs)
        assertEquals(1, s.underruns)
    }

    @Test
    fun watchdog_stallsAfter2s_disconnectsAfter10s() {
        playing()
        clock.t += 1_500
        assertEquals(WatchdogAction.NONE, engine.watchdog())
        clock.t += 1_000 // 2.5초
        assertEquals(WatchdogAction.STALLED, engine.watchdog())
        assertEquals(StreamPhase.STALLED, engine.state.value.phase)
        clock.t += 8_000 // 10.5초
        assertEquals(WatchdogAction.DISCONNECT, engine.watchdog())
    }

    @Test
    fun data_afterStall_resumesPlaying() {
        playing()
        clock.t += 3_000
        engine.watchdog()
        clock.t += 500
        engine.onData(16_000) { stats }
        assertEquals(StreamPhase.PLAYING, engine.state.value.phase)
        assertEquals(WatchdogAction.NONE, engine.watchdog())
    }

    @Test
    fun sessionEnded_messages_usePeerName() {
        playing()
        engine.sessionEnded(StreamEnd.SENSOR_NOT_MONITORING)
        assertEquals("Galaxy Watch7에서 모니터링을 먼저 시작해 주세요", engine.state.value.error)
        assertEquals(StreamPhase.IDLE, engine.state.value.phase)
    }

    @Test
    fun sessionEnded_withoutPeer_saysSensor() {
        engine.sessionStarted()
        engine.sessionEnded(StreamEnd.NO_PEER)
        assertEquals("연결된 감지기가 없어요", engine.state.value.error)
    }

    @Test
    fun sessionEnded_userStopped_hasNoError() {
        playing()
        engine.sessionEnded(StreamEnd.USER_STOPPED)
        assertNull(engine.state.value.error)
        assertEquals(0L, engine.state.value.backlogMs)
    }

    @Test
    fun allEndReasons_haveMessages() {
        assertEquals("연결 끊김 (10초 동안 소리 없음)", ReceiverEngine.messageFor(StreamEnd.DISCONNECTED, null, "w"))
        assertEquals("w 응답: ERR:X", ReceiverEngine.messageFor(StreamEnd.REJECTED, "ERR:X", "w"))
        assertEquals("오류: boom", ReceiverEngine.messageFor(StreamEnd.FAILED, "boom", "w"))
        assertEquals("w 응답 없음", ReceiverEngine.messageFor(StreamEnd.NO_RESPONSE, null, "w"))
        assertEquals("w에서 스트리밍이 끝났어요", ReceiverEngine.messageFor(StreamEnd.SENSOR_ENDED, null, "w"))
    }

    @Test
    fun retryDelay_backsOffAndCaps() {
        val d = (0..6).map { ReceiverEngine.retryDelayMs(StreamEnd.DISCONNECTED, it, 0) }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L), d)
    }

    @Test
    fun retryDelay_notForUserStopOrRejection_andGivesUp() {
        assertNull(ReceiverEngine.retryDelayMs(StreamEnd.USER_STOPPED, 0, 0))
        assertNull(ReceiverEngine.retryDelayMs(StreamEnd.SENSOR_NOT_MONITORING, 0, 0))
        assertNull(ReceiverEngine.retryDelayMs(StreamEnd.REJECTED, 0, 0))
        assertEquals(2_000L, ReceiverEngine.retryDelayMs(StreamEnd.SENSOR_ENDED, 0, 0))
        assertEquals(2_000L, ReceiverEngine.retryDelayMs(StreamEnd.NO_RESPONSE, 0, 0))
        assertNull(ReceiverEngine.retryDelayMs(StreamEnd.DISCONNECTED, 3, Constants.Stream.RECONNECT_GIVE_UP_MS))
    }

    @Test
    fun reconnecting_state() {
        playing()
        engine.reconnecting(attempt = 2, delayMs = 4_000, reason = StreamEnd.DISCONNECTED)
        assertEquals(StreamPhase.RECONNECTING, engine.state.value.phase)
        assertEquals("Galaxy Watch7 연결이 끊겨 4초 뒤 다시 연결해요 (2번째)", engine.state.value.error)
    }

    @Test
    fun shouldResync_onlyAfterStart_andOverLimit() {
        assertFalse(ReceiverEngine.shouldResync(started = false, backlogMs = 5_000))
        assertFalse(ReceiverEngine.shouldResync(started = true, backlogMs = 600))
        assertTrue(ReceiverEngine.shouldResync(started = true, backlogMs = 601))
    }

    @Test
    fun onAlert_phoneHeadsUp_watchVibrate_duplicatesIgnored() {
        val a = NoiseAlert(level = -20f, ts = 1L)
        assertEquals(AlertAction.NOTIFY_HEADS_UP, engine.onAlert(a, DeviceKind.PHONE))
        assertEquals(AlertAction.IGNORE_DUPLICATE, engine.onAlert(a, DeviceKind.PHONE))
        assertEquals(AlertAction.VIBRATE, engine.onAlert(a.copy(ts = 2L), DeviceKind.WATCH))
    }

    @Test
    fun onAlert_recordsCountAndLast_keptAcrossSessions() {
        engine.onAlert(NoiseAlert(level = -20f, ts = 1L), DeviceKind.WATCH)
        engine.onAlert(NoiseAlert(level = -12f, ts = 2L), DeviceKind.WATCH)
        engine.onAlert(NoiseAlert(level = -12f, ts = 2L), DeviceKind.WATCH) // 중복
        engine.sessionStarted()
        assertEquals(2, engine.state.value.alertCount)
        assertEquals(-12f, engine.state.value.lastAlert!!.level, 0f)
    }
}
