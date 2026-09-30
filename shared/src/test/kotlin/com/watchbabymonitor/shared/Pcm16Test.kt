package com.watchbabymonitor.shared

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Pcm16Test {

    @Test
    fun encode_isLittleEndian() {
        val out = ByteArray(8)
        val n = Pcm16.encodeLe(shortArrayOf(0x0102, -1, Short.MIN_VALUE, Short.MAX_VALUE), out)
        assertEquals(8, n)
        assertArrayEquals(
            byteArrayOf(0x02, 0x01, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x80.toByte(), 0xFF.toByte(), 0x7F),
            out,
        )
    }

    @Test
    fun roundTrip_fullFrame() {
        val samples = ShortArray(Constants.Audio.SAMPLES_PER_WINDOW) { (it * 37 - 20_000).toShort() }
        val bytes = ByteArray(Constants.Audio.BYTES_PER_WINDOW)
        Pcm16.encodeLe(samples, bytes)
        assertArrayEquals(samples, Pcm16.decodeLe(bytes))
    }

    @Test
    fun encode_partialCount() {
        val out = ByteArray(4) { 9 }
        assertEquals(2, Pcm16.encodeLe(shortArrayOf(1, 2), out, count = 1))
        assertArrayEquals(byteArrayOf(1, 0, 9, 9), out)
    }

    @Test(expected = IllegalArgumentException::class)
    fun decode_oddLength_throws() {
        Pcm16.decodeLe(ByteArray(3))
    }

    @Test
    fun durationConversions() {
        assertEquals(3_200, Constants.Audio.BYTES_PER_WINDOW)
        assertEquals(100L, Pcm16.bytesToMs(3_200))
        assertEquals(1_000L, Pcm16.bytesToMs(32_000))
        assertEquals(9_600, Pcm16.msToBytes(300))
    }
}
