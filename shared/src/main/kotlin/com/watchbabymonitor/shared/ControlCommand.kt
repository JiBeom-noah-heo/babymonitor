package com.watchbabymonitor.shared

/**
 * 수신기 → 감지기 `/control` 명령 (CLAUDE.md §3). 와이어 포맷은 UTF-8 문자열:
 * `PING`, `START`, `STOP`, `STREAM_ON`, `STREAM_OFF`, `SET_THRESHOLD:<dB>`.
 */
sealed interface ControlCommand {
    fun encode(): String

    /** 연결 진단. 응답 `PONG`. */
    data object Ping : ControlCommand {
        override fun encode() = "PING"
    }

    /** 모니터링 시작. 감지기 앱이 백그라운드면 `ERR:NEEDS_USER`. */
    data object Start : ControlCommand {
        override fun encode() = "START"
    }

    /** 모니터링 정지. */
    data object Stop : ControlCommand {
        override fun encode() = "STOP"
    }

    /** 라이브 오디오 시작. 모니터링 중이 아니면 `ERR:NOT_MONITORING`. */
    data object StreamOn : ControlCommand {
        override fun encode() = "STREAM_ON"
    }

    /** 라이브 오디오 정지. */
    data object StreamOff : ControlCommand {
        override fun encode() = "STREAM_OFF"
    }

    data class SetThreshold(val db: Float) : ControlCommand {
        override fun encode() = "$SET_THRESHOLD_PREFIX$db"
    }

    fun toBytes(): ByteArray = encode().toByteArray(Charsets.UTF_8)

    companion object {
        private const val SET_THRESHOLD_PREFIX = "SET_THRESHOLD:"

        private val simple = listOf(Ping, Start, Stop, StreamOn, StreamOff).associateBy { it.encode() }

        /** 알 수 없는 명령이면 null. */
        fun decode(raw: String): ControlCommand? =
            simple[raw] ?: if (raw.startsWith(SET_THRESHOLD_PREFIX)) {
                raw.removePrefix(SET_THRESHOLD_PREFIX).toFloatOrNull()?.let(::SetThreshold)
            } else {
                null
            }

        fun fromBytes(bytes: ByteArray): ControlCommand? = decode(bytes.toString(Charsets.UTF_8))
    }
}
