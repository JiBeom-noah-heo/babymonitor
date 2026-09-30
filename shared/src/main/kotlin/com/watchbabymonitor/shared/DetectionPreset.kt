package com.watchbabymonitor.shared

import kotlinx.serialization.Serializable

/**
 * 감지 조건 묶음. 감지기 설정에서 고른다 (CLAUDE.md §4-8).
 */
@Serializable
enum class DetectionPreset(
    val label: String,
    val thresholdDbfs: Float,
    val sustainMs: Int,
    val windowMs: Int,
    val cooldownMs: Long,
) {
    HOME(
        label = "집 안",
        thresholdDbfs = Constants.Alert.DEFAULT_THRESHOLD_DBFS,
        sustainMs = Constants.Alert.SUSTAIN_MS,
        windowMs = Constants.Alert.SUSTAIN_WINDOW_MS,
        cooldownMs = Constants.Alert.COOLDOWN_MS,
    ),
    CAR(
        label = "차 안",
        thresholdDbfs = Constants.Alert.CAR_THRESHOLD_DBFS,
        sustainMs = Constants.Alert.CAR_SUSTAIN_MS,
        windowMs = Constants.Alert.CAR_SUSTAIN_WINDOW_MS,
        cooldownMs = Constants.Alert.CAR_COOLDOWN_MS,
    ),
    ;

    fun newDetector(): NoiseDetector = NoiseDetector(
        thresholdDbfs = thresholdDbfs,
        sustainMs = sustainMs,
        windowMs = windowMs,
        cooldownMs = cooldownMs,
    )

    companion object {
        val DEFAULT = HOME
    }
}
