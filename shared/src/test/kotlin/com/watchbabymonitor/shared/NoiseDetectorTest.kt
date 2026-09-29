package com.watchbabymonitor.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoiseDetectorTest {

    private val quiet = -55f
    private val loud = -20f
    private val frameMs = Constants.Audio.LEVEL_WINDOW_MS.toLong()

    /** [levels] 를 100ms 간격으로 넣고, 알림이 난 시각 목록을 돌려준다. */
    private fun NoiseDetector.feed(levels: List<Float>, startMs: Long = 0): List<Long> =
        levels.mapIndexedNotNull { i, db ->
            val t = startMs + (i + 1) * frameMs
            onLevel(db, t)?.ts
        }

    private fun repeat(db: Float, frames: Int) = List(frames) { db }

    /** [loudFrames] 만큼 크고 [quietFrames] 만큼 조용한 패턴을 [cycles] 번. */
    private fun pattern(loudFrames: Int, quietFrames: Int, cycles: Int) =
        List(cycles) { repeat(loud, loudFrames) + repeat(quiet, quietFrames) }.flatten()

    @Test
    fun defaults_need10LoudFramesWithin15() {
        val d = NoiseDetector()
        assertEquals(15, d.windowFrames)
        assertEquals(10, d.minAboveFrames)
    }

    @Test
    fun silence_neverAlerts() {
        assertTrue(NoiseDetector().feed(repeat(quiet, 600)).isEmpty())
    }

    @Test
    fun continuousLoud_alertsAfterOneSecond() {
        assertEquals(listOf(1_000L), NoiseDetector().feed(repeat(loud, 10)))
    }

    @Test
    fun continuousLoudAfterQuiet_stillNeedsOneSecond() {
        val alerts = NoiseDetector().feed(repeat(quiet, 50) + repeat(loud, 10))
        assertEquals(listOf(6_000L), alerts)
    }

    @Test
    fun shortNoise_doesNotAlert() {
        // 문 닫는 소리(0.4초), 짧은 기침(0.9초)
        assertTrue(NoiseDetector().feed(repeat(quiet, 5) + repeat(loud, 4) + repeat(quiet, 20)).isEmpty())
        assertTrue(NoiseDetector().feed(repeat(quiet, 5) + repeat(loud, 9) + repeat(quiet, 20)).isEmpty())
    }

    @Test
    fun cryingWithBreathGaps_alerts() {
        // 0.3초 울고 0.1초 숨: 1.5초 중 1.1~1.2초가 큼
        val alerts = NoiseDetector().feed(pattern(loudFrames = 3, quietFrames = 1, cycles = 5))
        assertEquals(1, alerts.size)
    }

    @Test
    fun halfLoudHalfQuiet_doesNotAlert() {
        // 1.5초 중 최대 0.8초만 큼 → 1초 미만
        assertTrue(NoiseDetector().feed(pattern(loudFrames = 1, quietFrames = 1, cycles = 30)).isEmpty())
    }

    @Test
    fun thresholdIsInclusive() {
        assertEquals(1, NoiseDetector(thresholdDbfs = -30f).feed(repeat(-30f, 10)).size)
    }

    @Test
    fun cooldown_limitsToOneAlertPer30Seconds() {
        // 45초 동안 계속 시끄러움 → 1초, 31초에 알림
        assertEquals(listOf(1_000L, 31_000L), NoiseDetector().feed(repeat(loud, 450)))
    }

    @Test
    fun afterCooldown_needsFreshSustain() {
        val d = NoiseDetector()
        assertEquals(listOf(1_000L), d.feed(repeat(loud, 10)))
        d.feed(repeat(quiet, 400), startMs = 1_000)
        assertEquals(listOf(42_000L), d.feed(repeat(loud, 10), startMs = 41_000))
    }

    @Test
    fun alert_reportsPeakLevel() {
        val d = NoiseDetector()
        val levels = repeat(-30f, 9) + listOf(-12.5f)
        val alert = levels.mapIndexedNotNull { i, db -> d.onLevel(db, (i + 1) * frameMs) }.single()
        assertEquals(-12.5f, alert.level, 0f)
        assertEquals(AlertKind.NOISE, alert.kind)
    }

    @Test
    fun thresholdChange_appliesImmediately() {
        val d = NoiseDetector(thresholdDbfs = -10f)
        assertTrue(d.feed(repeat(loud, 10)).isEmpty())
        d.thresholdDbfs = -25f
        assertNotNull(d.onLevel(loud, 1_100))
    }

    @Test
    fun reset_clearsCooldown() {
        val d = NoiseDetector()
        d.feed(repeat(loud, 10))
        d.reset()
        assertEquals(listOf(2_000L), d.feed(repeat(loud, 10), startMs = 1_000))
    }

    @Test
    fun notEnoughFramesYet_returnsNull() {
        val d = NoiseDetector()
        repeat(9) { assertNull(d.onLevel(loud, (it + 1) * frameMs)) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun sustainLongerThanWindow_throws() {
        NoiseDetector(sustainMs = 2_000, windowMs = 1_500)
    }
}
