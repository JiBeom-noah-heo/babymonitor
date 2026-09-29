package com.watchbabymonitor.shared

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AudioLevelTest {

    private val n = Constants.Audio.SAMPLES_PER_WINDOW

    private fun constant(value: Short) = ShortArray(n) { value }

    /** 440Hz 사인파, 진폭 [amplitude]. */
    private fun sine(amplitude: Double) = ShortArray(n) { i ->
        (amplitude * sin(2 * PI * 440 * i / Constants.Audio.SAMPLE_RATE_HZ)).toInt().toShort()
    }

    @Test
    fun silence_isMinDbfs() {
        assertEquals(Constants.Audio.MIN_DBFS, AudioLevel.dbfs(ShortArray(n)), 0f)
    }

    @Test
    fun fullScaleSquare_isAboutZero() {
        assertEquals(0f, AudioLevel.dbfs(constant(Short.MAX_VALUE)), 0.01f)
        // -32768 도 오버플로 없이 정확히 0 dBFS
        assertEquals(0f, AudioLevel.dbfs(constant(Short.MIN_VALUE)), 0.0001f)
    }

    @Test
    fun halfAmplitude_isMinus6db() {
        assertEquals(-6.02f, AudioLevel.dbfs(constant(16384)), 0.01f)
    }

    @Test
    fun sine_isAmplitudeMinus3db() {
        // 사인파 RMS = A/√2 → 풀스케일 사인은 약 -3.01 dBFS
        assertEquals(-3.01f, AudioLevel.dbfs(sine(32767.0)), 0.05f)
        // 진폭 1/10 → 20dB 더 작음
        assertEquals(-23.01f, AudioLevel.dbfs(sine(3276.8)), 0.05f)
    }

    @Test
    fun rms_usesOnlyFirstCountSamples() {
        val buf = ShortArray(n)
        buf.fill(1000, 0, n / 2)
        assertEquals(1000.0, AudioLevel.rms(buf, n / 2), 1e-9)
        assertEquals(0.0, AudioLevel.rms(buf, 0), 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rms_countOutOfRange_throws() {
        AudioLevel.rms(ShortArray(10), 11)
    }

    @Test
    fun normalize_mapsFloorToZeroAndFullScaleToOne() {
        assertEquals(0f, AudioLevel.normalize(-80f), 0f)
        assertEquals(0f, AudioLevel.normalize(-96f), 0f)
        assertEquals(0.5f, AudioLevel.normalize(-40f), 1e-6f)
        assertEquals(1f, AudioLevel.normalize(0f), 0f)
    }
}
