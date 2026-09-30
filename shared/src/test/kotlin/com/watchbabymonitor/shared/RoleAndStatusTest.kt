package com.watchbabymonitor.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleAndStatusTest {

    @Test
    fun opposite() {
        assertEquals(Role.RECEIVER, Role.SENSOR.opposite)
        assertEquals(Role.SENSOR, Role.RECEIVER.opposite)
    }

    @Test
    fun conflicts_whenSameRole() {
        assertTrue(Role.conflicts(Role.SENSOR, Role.SENSOR))
        assertTrue(Role.conflicts(Role.RECEIVER, Role.RECEIVER))
        assertFalse(Role.conflicts(Role.SENSOR, Role.RECEIVER))
    }

    @Test
    fun defaults_areScenarioA() {
        assertEquals(Role.SENSOR, Role.DEFAULT_WATCH)
        assertEquals(Role.RECEIVER, Role.DEFAULT_PHONE)
        assertFalse(Role.conflicts(Role.DEFAULT_WATCH, Role.DEFAULT_PHONE))
    }

    @Test
    fun status_wireFormat() {
        val json = DeviceStatus(role = Role.SENSOR, monitoring = true, batteryPercent = 71, lastDbfs = -55.5f, ts = 1L)
            .toBytes().toString(Charsets.UTF_8)
        assertEquals(
            """{"role":"SENSOR","monitoring":true,"streaming":false,"batteryPercent":71,"lastDbfs":-55.5,"micMuted":false,"config":null,"error":null,"ts":1}""",
            json,
        )
    }

    @Test
    fun status_roundTrip() {
        val s = DeviceStatus(role = Role.RECEIVER, streaming = true, error = "마이크 오류", ts = 42L)
        assertEquals(s, DeviceStatus.fromBytes(s.toBytes()))
    }

    @Test
    fun status_ignoresUnknownFields() {
        val bytes = """{"role":"RECEIVER","ts":5,"futureField":1}""".toByteArray()
        assertEquals(DeviceStatus(role = Role.RECEIVER, ts = 5L), DeviceStatus.fromBytes(bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun status_unknownRole_throws() {
        DeviceStatus.fromBytes("""{"role":"BOTH","ts":5}""".toByteArray())
    }
}
