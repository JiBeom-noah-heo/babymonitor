package com.watchbabymonitor.shared

import kotlinx.serialization.Serializable

/**
 * 감지기 설정 (Phase 6 설정 화면). 프리셋 값에서 시작하고 사용자가 임계값·쿨다운만 바꾼다.
 * 지속 조건(몇 초 중 몇 초)은 프리셋 그대로.
 */
@Serializable
data class DetectionConfig(
    val preset: DetectionPreset = DetectionPreset.DEFAULT,
    val thresholdDbfs: Float = preset.thresholdDbfs,
    val cooldownMs: Long = preset.cooldownMs,
) {
    /** 슬라이더 범위 밖 값은 가장자리로. */
    fun clamped(): DetectionConfig = copy(
        thresholdDbfs = thresholdDbfs.coerceIn(MIN_THRESHOLD_DBFS, MAX_THRESHOLD_DBFS),
        cooldownMs = cooldownMs.coerceIn(MIN_COOLDOWN_MS, MAX_COOLDOWN_MS),
    )

    /** 프리셋 기본값과 다른지 (설정 화면 "기본값으로" 버튼). */
    val isCustomized: Boolean
        get() = thresholdDbfs != preset.thresholdDbfs || cooldownMs != preset.cooldownMs

    fun newDetector(): NoiseDetector {
        val c = clamped()
        return NoiseDetector(
            thresholdDbfs = c.thresholdDbfs,
            sustainMs = preset.sustainMs,
            windowMs = preset.windowMs,
            cooldownMs = c.cooldownMs,
        )
    }

    companion object {
        const val MIN_THRESHOLD_DBFS = -70f
        const val MAX_THRESHOLD_DBFS = -5f
        const val MIN_COOLDOWN_MS = 10_000L
        const val MAX_COOLDOWN_MS = 10 * 60_000L

        fun of(preset: DetectionPreset) = DetectionConfig(preset = preset)
    }
}
