package com.watchbabymonitor.shared.engine

import com.watchbabymonitor.shared.Clock
import com.watchbabymonitor.shared.DeviceStatus
import com.watchbabymonitor.shared.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerMonitorTest {

    private class FakeClock(var t: Long = 0) : Clock {
        override fun nowMs() = t
    }

    private val clock = FakeClock()
    private val m = PeerMonitor(clock)
    private var ts = 0L

    private fun sensor(battery: Int? = 80, muted: Boolean = false) =
        DeviceStatus(role = Role.SENSOR, monitoring = true, batteryPercent = battery, micMuted = muted, ts = ++ts)

    @Test
    fun shortDisconnect_isNotReported() {
        m.onLink(false)
        clock.t += 29_000
        assertTrue(m.tick().isEmpty())
        assertTrue(m.onLink(true).isEmpty())
    }

    @Test
    fun longDisconnect_reportedOnce_thenReconnected() {
        m.onLink(false)
        clock.t += 30_000
        assertEquals(listOf(PeerEvent.DISCONNECTED), m.tick())
        clock.t += 60_000
        assertTrue("한 번만", m.tick().isEmpty())
        assertTrue("끊긴 상태 반복 보고는 무시", m.onLink(false).isEmpty())
        assertEquals(listOf(PeerEvent.RECONNECTED), m.onLink(true))
        assertTrue(m.onLink(true).isEmpty())
    }

    @Test
    fun lowBattery_onceUntilRecharged() {
        assertTrue(m.onPeerStatus(sensor(battery = 21)).isEmpty())
        assertEquals(listOf(PeerEvent.LOW_BATTERY), m.onPeerStatus(sensor(battery = 20)))
        assertTrue(m.onPeerStatus(sensor(battery = 15)).isEmpty())
        assertTrue("25% 까지는 다시 준비 안 됨", m.onPeerStatus(sensor(battery = 25)).isEmpty())
        assertTrue(m.onPeerStatus(sensor(battery = 19)).isEmpty())
        m.onPeerStatus(sensor(battery = 26))
        assertEquals(listOf(PeerEvent.LOW_BATTERY), m.onPeerStatus(sensor(battery = 18)))
    }

    @Test
    fun micMuted_edgesOnly() {
        assertTrue(m.onPeerStatus(sensor(muted = false)).isEmpty())
        assertEquals(listOf(PeerEvent.MIC_MUTED), m.onPeerStatus(sensor(muted = true)))
        assertTrue(m.onPeerStatus(sensor(muted = true)).isEmpty())
        assertEquals(listOf(PeerEvent.MIC_BACK), m.onPeerStatus(sensor(muted = false)))
    }

    @Test
    fun receiverPeer_isIgnored() {
        val r = DeviceStatus(role = Role.RECEIVER, batteryPercent = 5, micMuted = true, ts = ++ts)
        assertTrue(m.onPeerStatus(r).isEmpty())
    }

    @Test
    fun staleStatus_isIgnored() {
        m.onPeerStatus(sensor(battery = 80))
        val old = DeviceStatus(role = Role.SENSOR, batteryPercent = 10, ts = 0L)
        assertTrue(m.onPeerStatus(old).isEmpty())
    }

    @Test
    fun duplicateDelivery_isIgnored() {
        val s = sensor(battery = 10)
        assertEquals(listOf(PeerEvent.LOW_BATTERY), m.onPeerStatus(s))
        m.onPeerStatus(sensor(battery = 90)) // 재준비
        assertTrue("같은 ts 로 다시 와도 무시", m.onPeerStatus(s).isEmpty())
    }

    @Test
    fun lowBatteryAndMute_together() {
        val events = m.onPeerStatus(sensor(battery = 10, muted = true))
        assertEquals(listOf(PeerEvent.LOW_BATTERY, PeerEvent.MIC_MUTED), events)
    }
}
