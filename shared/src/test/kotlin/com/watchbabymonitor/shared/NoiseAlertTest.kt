package com.watchbabymonitor.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class NoiseAlertTest {

    @Test
    fun encode_matchesWireFormat() {
        val json = NoiseAlert(level = -22.5f, ts = 1_727_600_000_000L).toBytes().toString(Charsets.UTF_8)
        assertEquals("""{"level":-22.5,"ts":1727600000000,"kind":"NOISE"}""", json)
    }

    @Test
    fun roundTrip() {
        val alert = NoiseAlert(level = -18.25f, ts = 42L)
        assertEquals(alert, NoiseAlert.fromBytes(alert.toBytes()))
    }

    @Test
    fun decode_ignoresUnknownKeys_andDefaultsKind() {
        val bytes = """{"level":-30.0,"ts":7,"extra":true}""".toByteArray()
        assertEquals(NoiseAlert(level = -30f, ts = 7L, kind = AlertKind.NOISE), NoiseAlert.fromBytes(bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun decode_malformed_throws() {
        // kotlinx.serialization 의 SerializationException 은 IllegalArgumentException 하위 타입
        NoiseAlert.fromBytes("not json".toByteArray())
    }
}
