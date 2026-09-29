package com.watchbabymonitor.shared

/**
 * 폰 → 워치 `/control` 명령. 와이어 포맷은 UTF-8 문자열:
 * `PING`, `START`, `STOP`, `SET_THRESHOLD:<dB>`.
 */
sealed interface ControlCommand {
    fun encode(): String

    data object Ping : ControlCommand {
        override fun encode() = "PING"
    }

    data object Start : ControlCommand {
        override fun encode() = "START"
    }

    data object Stop : ControlCommand {
        override fun encode() = "STOP"
    }

    data class SetThreshold(val db: Float) : ControlCommand {
        override fun encode() = "$SET_THRESHOLD_PREFIX$db"
    }

    fun toBytes(): ByteArray = encode().toByteArray(Charsets.UTF_8)

    companion object {
        private const val SET_THRESHOLD_PREFIX = "SET_THRESHOLD:"

        /** 알 수 없는 명령이면 null. */
        fun decode(raw: String): ControlCommand? = when {
            raw == Ping.encode() -> Ping
            raw == Start.encode() -> Start
            raw == Stop.encode() -> Stop
            raw.startsWith(SET_THRESHOLD_PREFIX) ->
                raw.removePrefix(SET_THRESHOLD_PREFIX).toFloatOrNull()?.let(::SetThreshold)
            else -> null
        }

        fun fromBytes(bytes: ByteArray): ControlCommand? = decode(bytes.toString(Charsets.UTF_8))
    }
}
