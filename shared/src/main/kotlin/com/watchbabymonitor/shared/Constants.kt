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

    /** START / STOP 등 명령을 받아들였을 때의 응답. */
    const val CONTROL_REPLY_OK = "OK"

    /** 알 수 없는 / 아직 지원하지 않는 명령에 대한 응답 접두사. */
    const val CONTROL_REPLY_ERROR_PREFIX = "ERR:"

    /** STREAM_ON 을 받았지만 감지기 모니터링이 꺼져 있음 (ADR 004). */
    const val CONTROL_REPLY_NOT_MONITORING = "${CONTROL_REPLY_ERROR_PREFIX}NOT_MONITORING"

    /** START 를 받았지만 감지기 앱이 백그라운드라 마이크를 켤 수 없음 → 감지기 기기에서 사용자가 눌러야 함. */
    const val CONTROL_REPLY_NEEDS_USER = "${CONTROL_REPLY_ERROR_PREFIX}NEEDS_USER"

    /** 알 수 없는 명령. */
    const val CONTROL_REPLY_UNKNOWN = "${CONTROL_REPLY_ERROR_PREFIX}UNKNOWN"

    /** 이 역할에서 처리하지 않는 명령 (예: 수신기에 STREAM_ON). */
    const val CONTROL_REPLY_UNSUPPORTED = "${CONTROL_REPLY_ERROR_PREFIX}UNSUPPORTED"

    /** 상대가 감지기가 아님 (수신기 역할 기기에 START / STREAM_ON 등). 역할 충돌 확인용. */
    const val CONTROL_REPLY_WRONG_ROLE = "${CONTROL_REPLY_ERROR_PREFIX}WRONG_ROLE"

    /** 폰 → 워치 요청 응답 대기 시간. */
    const val CONTROL_REQUEST_TIMEOUT_MS = 5_000L

    /** 오디오 포맷 (CLAUDE.md §4-1): 16kHz / mono / PCM 16bit. */
    object Audio {
        const val SAMPLE_RATE_HZ = 16_000

        /** 레벨 계산 단위 (CLAUDE.md §4-2). */
        const val LEVEL_WINDOW_MS = 100

        /** 한 레벨 윈도우의 샘플 수 (16kHz × 100ms = 1600). */
        const val SAMPLES_PER_WINDOW = SAMPLE_RATE_HZ * LEVEL_WINDOW_MS / 1000

        const val BYTES_PER_SAMPLE = 2

        /** 한 레벨 윈도우(100ms)의 바이트 수. */
        const val BYTES_PER_WINDOW = SAMPLES_PER_WINDOW * BYTES_PER_SAMPLE

        /** PCM16 풀스케일. dBFS 기준값. */
        const val FULL_SCALE = 32768.0

        /** 무음(RMS 0)일 때 쓰는 하한. log10(0) = -∞ 방지. */
        const val MIN_DBFS = -96f

        /** 레벨 바 표시 범위의 하한. 이보다 작으면 바가 비어 있음. */
        const val DISPLAY_FLOOR_DBFS = -80f
    }

    /** 소음 알림 판정 (CLAUDE.md §4-3, ADR 003). */
    object Alert {
        /** 기본 임계값. 조용한 방 약 -55 dBFS 기준 (Phase 2 실측). */
        const val DEFAULT_THRESHOLD_DBFS = -35f

        /** 임계값 이상이어야 하는 누적 시간. "1초 이상 지속". */
        const val SUSTAIN_MS = 1_000

        /** [SUSTAIN_MS] 를 세는 구간. 차이(0.5초)만큼 울음 사이 숨 쉬는 틈을 허용. */
        const val SUSTAIN_WINDOW_MS = 1_500

        /** 알림 후 다음 알림까지 최소 간격. */
        const val COOLDOWN_MS = 30_000L

        /**
         * 차 안 프리셋 (CLAUDE.md §4-8, §8): 에어컨·주행 소음이 커서 집 안 값이면 오탐 폭주.
         * 임계값을 올리고 더 오래 지속될 때만, 쿨다운은 길게. 값은 실차 측정 전 초기값 (Phase 3.5 devlog 에서 조정).
         */
        const val CAR_THRESHOLD_DBFS = -25f
        const val CAR_SUSTAIN_MS = 1_500
        const val CAR_SUSTAIN_WINDOW_MS = 2_000
        const val CAR_COOLDOWN_MS = 60_000L
    }

    /** 라이브 스트리밍 (ADR 004). 목표: 지연 2초 이내. */
    object Stream {
        /** 워치 송신 대기열. 넘치면 오래된 프레임부터 버림 → 지연이 쌓이지 않음. */
        const val WATCH_QUEUE_FRAMES = 5

        /** 폰에서 재생 시작 전에 모아둘 양. 너무 작으면 시작하자마자 끊김. */
        const val PREBUFFER_MS = 300

        /** 폰 재생 대기량이 이보다 많으면 받은 데이터를 버려서 따라잡는다. */
        const val MAX_PLAYBACK_BACKLOG_MS = 600

        /**
         * 원격(클라우드 경유) 연결일 때. 데이터가 1~2초씩 몰려 와서 근거리 기준(0.6초)이면
         * 받은 소리의 84% 를 버렸다 (Phase 5 측정) → 지연 1~3초를 감수 (CLAUDE.md §3).
         */
        const val REMOTE_PREBUFFER_MS = 1_000
        const val REMOTE_MAX_PLAYBACK_BACKLOG_MS = 3_000

        /** 이 시간 동안 데이터가 없으면 "끊김" 표시. */
        const val STALL_TIMEOUT_MS = 2_000L

        /** 이 시간 동안 데이터가 없으면 연결이 끊긴 것으로 보고 종료 (CLAUDE.md §8). */
        const val DISCONNECT_TIMEOUT_MS = 10_000L

        /** START 후 워치가 채널을 열 때까지 기다리는 시간. */
        const val CHANNEL_OPEN_TIMEOUT_MS = 10_000L

        /** 끊긴 라이브 듣기 자동 재연결: 첫 대기, 최대 대기, 포기까지 (Phase 5). */
        const val RECONNECT_FIRST_DELAY_MS = 2_000L
        const val RECONNECT_MAX_DELAY_MS = 30_000L
        const val RECONNECT_GIVE_UP_MS = 10 * 60_000L
    }

    /** 연결·상대 기기 감시 (Phase 5). */
    object Link {
        /** 블루투스가 잠깐 흔들려도 알리지 않도록, 이만큼 계속 끊겨야 "연결 끊김" 알림. */
        const val DISCONNECT_GRACE_MS = 30_000L

        /** 감지기 배터리가 이 값 이하로 떨어지면 수신기에 알림 (CLAUDE.md Phase 5). */
        const val LOW_BATTERY_PERCENT = 20

        /** 알림 후 이 값을 넘게 충전되면 다음 저전력 알림을 다시 준비. */
        const val LOW_BATTERY_REARM_PERCENT = 25

        /** 마이크 입력이 이만큼 완전히 0 이면 "마이크 막힘" (통화 중 등). 실제 방 소리는 0 이 아님. */
        const val MIC_MUTED_DETECT_MS = 3_000

        /**
         * 원격(클라우드) 연결일 때 실제로 닿는지 PING 으로 확인하는 간격·제한시간·실패 기준.
         * 폰이 완전히 오프라인이어도 CapabilityClient 가 "원격으로 닿음"이라고 보고하는 경우가 있었다 (Phase 5 실측).
         */
        const val REMOTE_PROBE_INTERVAL_MS = 30_000L
        const val REMOTE_PROBE_TIMEOUT_MS = 10_000L
        const val REMOTE_PROBE_FAILURES = 2

        /** 이 기기들이 앱을 설치했음을 알리는 CapabilityClient 이름 (res/values/wear.xml). */
        const val CAPABILITY = "watch_baby_monitor"
    }
}
