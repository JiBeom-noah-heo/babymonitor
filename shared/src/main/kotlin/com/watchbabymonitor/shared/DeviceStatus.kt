package com.watchbabymonitor.shared

import kotlinx.serialization.Serializable

/**
 * `/status` (DataClient, 양방향) 본문. 각 기기가 자기 상태를 올리고 상대 것을 읽는다 (CLAUDE.md §3).
 * 와이어 포맷: UTF-8 JSON. 알 수 없는 필드는 무시 → 필드 추가에 호환.
 *
 * @property batteryPercent 0~100, 모르면 null
 * @property lastDbfs 감지기일 때 마지막 레벨, 수신기면 null
 * @property error 서비스 실패 사유 (CLAUDE.md §6 "실패는 /status 로 전파")
 * @property ts 이 상태를 만든 시각 (epoch millis)
 */
@Serializable
data class DeviceStatus(
    val role: Role,
    val monitoring: Boolean = false,
    val streaming: Boolean = false,
    val batteryPercent: Int? = null,
    val lastDbfs: Float? = null,
    val error: String? = null,
    val ts: Long,
) {
    fun toBytes(): ByteArray = WbmJson.encodeToString(serializer(), this).toByteArray(Charsets.UTF_8)

    companion object {
        /** 형식이 잘못되면 예외 (SerializationException / IllegalArgumentException). */
        fun fromBytes(bytes: ByteArray): DeviceStatus =
            WbmJson.decodeFromString(serializer(), bytes.toString(Charsets.UTF_8))
    }
}
