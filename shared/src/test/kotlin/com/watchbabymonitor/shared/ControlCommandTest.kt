package com.watchbabymonitor.shared

import com.watchbabymonitor.shared.DetectionPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ControlCommandTest {
    @Test
    fun roundTrip_allCommands() {
        val commands = listOf(
            ControlCommand.Ping,
            ControlCommand.Start,
            ControlCommand.Stop,
            ControlCommand.StreamOn,
            ControlCommand.StreamOff,
            ControlCommand.SetThreshold(-30.5f),
            ControlCommand.SetCooldown(45_000),
            ControlCommand.SetPreset(DetectionPreset.CAR),
        )
        for (cmd in commands) {
            assertEquals(cmd, ControlCommand.fromBytes(cmd.toBytes()))
        }
    }

    @Test
    fun encode_matchesWireFormat() {
        assertEquals("PING", ControlCommand.Ping.encode())
        assertEquals("STREAM_ON", ControlCommand.StreamOn.encode())
        assertEquals("STREAM_OFF", ControlCommand.StreamOff.encode())
        assertEquals("SET_THRESHOLD:-20.0", ControlCommand.SetThreshold(-20f).encode())
        assertEquals("SET_COOLDOWN:60000", ControlCommand.SetCooldown(60_000).encode())
        assertEquals("SET_PRESET:CAR", ControlCommand.SetPreset(DetectionPreset.CAR).encode())
    }

    @Test
    fun decode_unknownOrMalformed_returnsNull() {
        assertNull(ControlCommand.decode("HELLO"))
        assertNull(ControlCommand.decode("ping"))
        assertNull(ControlCommand.decode("STREAM"))
        assertNull(ControlCommand.decode("SET_THRESHOLD:abc"))
        assertNull(ControlCommand.decode("SET_COOLDOWN:-5"))
        assertNull(ControlCommand.decode("SET_PRESET:BOAT"))
        assertNull(ControlCommand.decode(""))
    }
}
