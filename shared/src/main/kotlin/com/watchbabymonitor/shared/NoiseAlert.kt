package com.watchbabymonitor.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class AlertKind {
    @SerialName("NOISE")
    NOISE,
}

/**
 * 워치 → 폰 `/alert` 메시지 본문 (CLAUDE.md §3).
 * 와이어 포맷: `{"level":-22.5,"ts":1727600000000,"kind":"NOISE"}` (UTF-8 JSON)
 *
 * @property level 판정 구간의 최대 dBFS
 * @property ts 워치 시각 (epoch millis)
 */
@Serializable
data class NoiseAlert(
    val level: Float,
    val ts: Long,
    val kind: AlertKind = AlertKind.NOISE,
) {
    fun toBytes(): ByteArray = WbmJson.encodeToString(serializer(), this).toByteArray(Charsets.UTF_8)

    companion object {
        /** 형식이 잘못되면 예외 (SerializationException / IllegalArgumentException). */
        fun fromBytes(bytes: ByteArray): NoiseAlert =
            WbmJson.decodeFromString(serializer(), bytes.toString(Charsets.UTF_8))
    }
}

/** 워치·폰 공용 JSON 설정. */
val WbmJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}
