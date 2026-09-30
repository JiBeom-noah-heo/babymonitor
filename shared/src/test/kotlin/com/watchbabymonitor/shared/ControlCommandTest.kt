package com.watchbabymonitor.shared

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
    }

    @Test
    fun decode_unknownOrMalformed_returnsNull() {
        assertNull(ControlCommand.decode("HELLO"))
        assertNull(ControlCommand.decode("ping"))
        assertNull(ControlCommand.decode("STREAM"))
        assertNull(ControlCommand.decode("SET_THRESHOLD:abc"))
        assertNull(ControlCommand.decode(""))
    }
}
