package com.watchbabymonitor.shared

/**
 * 워치·폰 공용 상수. 경로, 샘플레이트, 임계값 기본값은 전부 여기에 둔다.
 */
object Constants {
    const val APP_NAME = "WatchBabyMonitor"

    /** 로그 태그 접두사. 실제 태그는 `WBM/<클래스명>`. */
    const val LOG_TAG_PREFIX = "WBM"

    fun logTag(className: String): String = "$LOG_TAG_PREFIX/$className"

    /** Wearable Data Layer 경로 (CLAUDE.md §3 통신 규약). */
    object Paths {
        /** ChannelClient, 워치 → 폰, 원시 PCM16 스트림 */
        const val AUDIO = "/audio"

        /** MessageClient, 워치 → 폰, 소음 알림 JSON */
        const val ALERT = "/alert"

        /** DataClient, 양방향, 모니터링 상태 */
        const val STATUS = "/status"

        /** MessageClient, 폰 → 워치, [ControlCommand] */
        const val CONTROL = "/control"
    }

    /** `/control` PING 요청에 대한 워치의 응답 본문. */
    const val CONTROL_REPLY_PONG = "PONG"

    /** 알 수 없는 / 아직 지원하지 않는 명령에 대한 응답 접두사. */
    const val CONTROL_REPLY_ERROR_PREFIX = "ERR:"

    /** 폰 → 워치 요청 응답 대기 시간. */
    const val CONTROL_REQUEST_TIMEOUT_MS = 5_000L

    /** 오디오 포맷 (CLAUDE.md §4-1): 16kHz / mono / PCM 16bit. */
    object Audio {
        const val SAMPLE_RATE_HZ = 16_000

        /** 레벨 계산 단위 (CLAUDE.md §4-2). */
        const val LEVEL_WINDOW_MS = 100

        /** 한 레벨 윈도우의 샘플 수 (16kHz × 100ms = 1600). */
        const val SAMPLES_PER_WINDOW = SAMPLE_RATE_HZ * LEVEL_WINDOW_MS / 1000

        /** PCM16 풀스케일. dBFS 기준값. */
        const val FULL_SCALE = 32768.0

        /** 무음(RMS 0)일 때 쓰는 하한. log10(0) = -∞ 방지. */
        const val MIN_DBFS = -96f

        /** 레벨 바 표시 범위의 하한. 이보다 작으면 바가 비어 있음. */
        const val DISPLAY_FLOOR_DBFS = -80f
    }
}
