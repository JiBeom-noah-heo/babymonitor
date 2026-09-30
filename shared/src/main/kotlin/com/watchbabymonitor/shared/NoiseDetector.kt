package com.watchbabymonitor.shared

/**
 * 100ms 레벨을 받아 소음 알림을 낼지 판정한다. Android 의존성 없음 (ADR 003).
 *
 * - 최근 [windowMs] 구간 안에서 [thresholdDbfs] 이상인 프레임이 합계 [sustainMs] 이상이면
 *   "지속된 소음". 기본값은 1.5초 중 1초 → 소리가 최소 1초는 커야 하고, 중간 틈은 0.5초까지 허용.
 * - 알림 후 [cooldownMs] 동안은 다시 알리지 않는다.
 * - 알림을 내면 구간을 비워서, 다음 알림도 새로 [sustainMs] 를 채워야 한다.
 *
 * 스레드 안전하지 않음. 한 코루틴에서만 호출한다.
 */
class NoiseDetector(
    thresholdDbfs: Float = Constants.Alert.DEFAULT_THRESHOLD_DBFS,
    sustainMs: Int = Constants.Alert.SUSTAIN_MS,
    windowMs: Int = Constants.Alert.SUSTAIN_WINDOW_MS,
    frameMs: Int = Constants.Audio.LEVEL_WINDOW_MS,
    cooldownMs: Long = Constants.Alert.COOLDOWN_MS,
) {
    init {
        require(frameMs > 0) { "frameMs=$frameMs" }
        require(sustainMs in frameMs..windowMs) { "sustainMs=$sustainMs, windowMs=$windowMs, frameMs=$frameMs" }
        require(cooldownMs >= 0) { "cooldownMs=$cooldownMs" }
    }

    /** 임계값. `/control SET_THRESHOLD` 로 바뀔 수 있다. */
    var thresholdDbfs: Float = thresholdDbfs

    /** 쿨다운. `/control SET_COOLDOWN` 으로 바뀔 수 있다 (Phase 6). */
    var cooldownMs: Long = cooldownMs
        set(value) {
            require(value >= 0) { "cooldownMs=$value" }
            field = value
        }

    val windowFrames: Int = windowMs / frameMs
    val minAboveFrames: Int = sustainMs / frameMs

    // 최근 windowFrames 개 레벨의 원형 버퍼. filled 개만 유효.
    private val levels = FloatArray(windowFrames)
    private var next = 0
    private var filled = 0
    private var lastAlertMs: Long? = null

    /**
     * 프레임 하나의 레벨을 넣는다.
     * @param nowMs 이 프레임의 시각 (단조 증가)
     * @return 알림을 내야 하면 [NoiseAlert], 아니면 null
     */
    fun onLevel(dbfs: Float, nowMs: Long): NoiseAlert? {
        levels[next] = dbfs
        next = (next + 1) % windowFrames
        if (filled < windowFrames) filled++

        var aboveCount = 0
        var peak = Float.NEGATIVE_INFINITY
        for (i in 0 until filled) {
            val db = levels[i]
            if (db >= thresholdDbfs) aboveCount++
            if (db > peak) peak = db
        }
        if (aboveCount < minAboveFrames) return null

        val last = lastAlertMs
        if (last != null && nowMs - last < cooldownMs) return null

        lastAlertMs = nowMs
        clearWindow()
        return NoiseAlert(level = peak, ts = nowMs)
    }

    /** 구간과 쿨다운을 모두 초기화 (모니터링 재시작 시). */
    fun reset() {
        clearWindow()
        lastAlertMs = null
    }

    private fun clearWindow() {
        next = 0
        filled = 0
    }
}
