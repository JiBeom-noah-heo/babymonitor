package com.watchbabymonitor.shared.engine

import com.watchbabymonitor.shared.Clock
import com.watchbabymonitor.shared.Constants
import com.watchbabymonitor.shared.NoiseAlert
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

enum class StreamPhase {
    IDLE,
    CONNECTING,
    PLAYING,
    STALLED,

    /** 끊긴 뒤 자동으로 다시 연결하려고 기다리는 중 (Phase 5). */
    RECONNECTING,
}

/** 라이브 듣기 세션이 끝난 이유. 사용자 문구는 [ReceiverEngine] 이 만든다. */
enum class StreamEnd {
    USER_STOPPED,
    NO_PEER,
    NO_RESPONSE,
    SENSOR_NOT_MONITORING,
    REJECTED,
    SENSOR_ENDED,
    DISCONNECTED,
    FAILED,
}

data class ReceiverState(
    val phase: StreamPhase = StreamPhase.IDLE,
    val peerName: String? = null,
    /** 수신기 재생 대기량 = 추정 지연 중 수신기 몫 (ms). */
    val backlogMs: Long = 0,
    /** 최근 구간 수신량 (kbps). 정상이면 약 256. */
    val kbps: Int = 0,
    /** 재생할 데이터가 없어 끊긴 횟수. */
    val underruns: Int = 0,
    /** 지연을 따라잡으려고 버린 오디오 (ms). */
    val droppedMs: Long = 0,
    /** 대기량이 너무 쌓여 오래된 소리를 비운 횟수. */
    val resyncs: Int = 0,
    /** 마지막 세션이 오류로 끝났으면 사용자 문구. */
    val error: String? = null,
    /** 받은 알림 (중복 제외). 수신기 화면 표시용. */
    val alertCount: Int = 0,
    val lastAlert: NoiseAlert? = null,
)

/** 재생기(AudioTrack 래퍼)가 알려주는 현재 수치. */
data class PlaybackStats(val backlogMs: Long, val underruns: Int, val droppedMs: Long, val resyncs: Int)

/** 재생 시작 전 모을 양과 대기량 상한. */
data class Buffering(val prebufferMs: Int, val maxBacklogMs: Int)

enum class WatchdogAction {
    NONE,

    /** 데이터가 [Constants.Stream.STALL_TIMEOUT_MS] 넘게 없음 → "끊김" 표시 (엔진이 상태 반영). */
    STALLED,

    /** [Constants.Stream.DISCONNECT_TIMEOUT_MS] 넘게 없음 → 채널을 닫고 세션 종료 (CLAUDE.md §8). */
    DISCONNECT,
}

enum class DeviceKind { PHONE, WATCH }

enum class AlertAction {
    /** 폰: heads-up 알림 (소리·진동). */
    NOTIFY_HEADS_UP,

    /** 워치: 진동 우선 (CLAUDE.md §4-7). */
    VIBRATE,

    /** 이미 처리한 알림이 다시 옴. */
    IGNORE_DUPLICATE,
}

/**
 * 수신기 역할 로직 (CLAUDE.md §3, §4-7). 순수 Kotlin.
 *
 * - 라이브 듣기 세션 상태, 수신량·지연 통계, 무데이터 감시 판단, 끝난 이유 → 사용자 문구
 * - 받은 `/alert` 를 어떻게 알릴지
 *
 * 채널·AudioTrack·알림 같은 실제 동작은 플랫폼이 하고, 이벤트를 엔진에 알려 판단을 받는다.
 * [onData] 는 읽기 스레드, [watchdog] 은 다른 코루틴에서 호출해도 된다.
 */
class ReceiverEngine(
    private val clock: Clock = Clock.MONOTONIC,
    private val log: EngineLog = EngineLog.NONE,
) {
    private val _state = MutableStateFlow(ReceiverState())
    val state: StateFlow<ReceiverState> = _state.asStateFlow()

    private val _alerts = MutableSharedFlow<NoiseAlert>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 받은 알림 (중복 제외). 화면 기록용. */
    val alerts: SharedFlow<NoiseAlert> = _alerts.asSharedFlow()

    private val lastDataAt = AtomicLong(0)

    // onData 호출 스레드에서만 사용
    private var windowStart = 0L
    private var windowBytes = 0L

    @Volatile
    private var lastAlert: NoiseAlert? = null

    // ---- 라이브 듣기 세션 ----

    fun sessionStarted() {
        // 스트림 통계만 초기화, 알림 기록은 유지
        _state.update {
            ReceiverState(phase = StreamPhase.CONNECTING, alertCount = it.alertCount, lastAlert = it.lastAlert)
        }
    }

    fun peerFound(name: String) {
        _state.update { it.copy(peerName = name) }
    }

    /** `STREAM_ON` 응답. 스트림을 기다려도 되면 null, 아니면 세션을 끝낼 이유. */
    fun onStreamOnReply(reply: String): StreamEnd? = when (reply) {
        Constants.CONTROL_REPLY_OK -> null
        Constants.CONTROL_REPLY_NOT_MONITORING -> StreamEnd.SENSOR_NOT_MONITORING
        else -> StreamEnd.REJECTED
    }

    fun channelOpened() {
        val now = clock.nowMs()
        lastDataAt.set(now)
        windowStart = now
        windowBytes = 0
    }

    /**
     * 데이터 [bytes] 를 받아 재생기에 넣은 직후 호출. 통계 구간([STATS_INTERVAL_MS])마다 상태를 갱신한다.
     * @param stats 구간이 찼을 때만 호출됨
     */
    fun onData(bytes: Int, stats: () -> PlaybackStats) {
        val now = clock.nowMs()
        lastDataAt.set(now)
        windowBytes += bytes
        val elapsed = now - windowStart
        if (elapsed < STATS_INTERVAL_MS) return

        val kbps = (windowBytes * 8 / elapsed).toInt()
        val s = stats()
        _state.update {
            it.copy(
                phase = StreamPhase.PLAYING,
                backlogMs = s.backlogMs,
                kbps = kbps,
                underruns = s.underruns,
                droppedMs = s.droppedMs,
                resyncs = s.resyncs,
            )
        }
        windowStart = now
        windowBytes = 0
    }

    /** 주기적으로([WATCHDOG_INTERVAL_MS]) 호출. 무데이터 시간에 따라 할 일을 알려준다. */
    fun watchdog(): WatchdogAction {
        val idle = clock.nowMs() - lastDataAt.get()
        return when {
            idle > Constants.Stream.DISCONNECT_TIMEOUT_MS -> {
                log.w("no data for ${idle}ms, closing channel")
                WatchdogAction.DISCONNECT
            }
            idle > Constants.Stream.STALL_TIMEOUT_MS -> {
                _state.update { it.copy(phase = StreamPhase.STALLED) }
                WatchdogAction.STALLED
            }
            else -> WatchdogAction.NONE
        }
    }

    /** 자동 재연결 대기 표시. */
    fun reconnecting(attempt: Int, delayMs: Long, reason: StreamEnd) {
        val peer = _state.value.peerName ?: "감지기"
        log.i("reconnect #$attempt in ${delayMs}ms after $reason")
        _state.update {
            it.copy(
                phase = StreamPhase.RECONNECTING,
                backlogMs = 0,
                kbps = 0,
                error = "$peer 연결이 끊겨 ${delayMs / 1000}초 뒤 다시 연결해요 (${attempt}번째)",
            )
        }
    }

    /** 세션 종료. [detail] 은 REJECTED(응답 원문) / FAILED(예외 메시지) 에 붙는다. */
    fun sessionEnded(end: StreamEnd, detail: String? = null) {
        val msg = messageFor(end, detail, _state.value.peerName ?: "감지기")
        if (end != StreamEnd.USER_STOPPED) log.w("live session ended: $end ${detail ?: ""}".trimEnd())
        _state.update { it.copy(phase = StreamPhase.IDLE, backlogMs = 0, kbps = 0, error = msg) }
    }

    // ---- 알림 ----

    /** 받은 `/alert` 를 어떻게 알릴지. */
    fun onAlert(alert: NoiseAlert, device: DeviceKind): AlertAction {
        if (alert == lastAlert) return AlertAction.IGNORE_DUPLICATE
        lastAlert = alert
        _state.update { it.copy(alertCount = it.alertCount + 1, lastAlert = alert) }
        _alerts.tryEmit(alert)
        return when (device) {
            DeviceKind.PHONE -> AlertAction.NOTIFY_HEADS_UP
            DeviceKind.WATCH -> AlertAction.VIBRATE
        }
    }

    companion object {
        const val STATS_INTERVAL_MS = 500L
        const val WATCHDOG_INTERVAL_MS = 500L

        /**
         * 재생 대기량이 상한을 넘으면 오래된 소리를 버리고 최신부터 다시 (ADR 004).
         * 재생 시작 전(prebuffer 중)에는 버리지 않는다.
         */
        /**
         * 끊긴 라이브 듣기를 다시 시도할지, 몇 ms 뒤에 할지 (Phase 5).
         * 사용자가 끈 경우, 감지기가 모니터링 중이 아니거나 거절한 경우는 재시도하지 않는다.
         * @param attempt 0 부터
         * @param sinceFirstFailureMs 처음 끊긴 뒤 지난 시간. [Constants.Stream.RECONNECT_GIVE_UP_MS] 넘으면 포기
         * @return 대기 시간, 재시도 안 하면 null
         */
        fun retryDelayMs(end: StreamEnd, attempt: Int, sinceFirstFailureMs: Long): Long? {
            val retryable = when (end) {
                StreamEnd.SENSOR_ENDED, StreamEnd.DISCONNECTED, StreamEnd.NO_RESPONSE,
                StreamEnd.NO_PEER, StreamEnd.FAILED -> true
                StreamEnd.USER_STOPPED, StreamEnd.SENSOR_NOT_MONITORING, StreamEnd.REJECTED -> false
            }
            if (!retryable || sinceFirstFailureMs >= Constants.Stream.RECONNECT_GIVE_UP_MS) return null
            val backoff = Constants.Stream.RECONNECT_FIRST_DELAY_MS shl attempt.coerceAtMost(10)
            return backoff.coerceAtMost(Constants.Stream.RECONNECT_MAX_DELAY_MS)
        }

        fun shouldResync(
            started: Boolean,
            backlogMs: Long,
            maxBacklogMs: Int = Constants.Stream.MAX_PLAYBACK_BACKLOG_MS,
        ): Boolean = started && backlogMs > maxBacklogMs

        /** 연결 경로에 맞는 재생 버퍼 (Phase 5). */
        fun bufferingFor(remote: Boolean): Buffering =
            if (remote) {
                Buffering(Constants.Stream.REMOTE_PREBUFFER_MS, Constants.Stream.REMOTE_MAX_PLAYBACK_BACKLOG_MS)
            } else {
                Buffering(Constants.Stream.PREBUFFER_MS, Constants.Stream.MAX_PLAYBACK_BACKLOG_MS)
            }

        internal fun messageFor(end: StreamEnd, detail: String?, peer: String): String? = when (end) {
            StreamEnd.USER_STOPPED -> null
            StreamEnd.NO_PEER -> "연결된 감지기가 없어요"
            StreamEnd.NO_RESPONSE -> "$peer 응답 없음"
            StreamEnd.SENSOR_NOT_MONITORING -> "${peer}에서 모니터링을 먼저 시작해 주세요"
            StreamEnd.REJECTED -> "$peer 응답: $detail"
            StreamEnd.SENSOR_ENDED -> "${peer}에서 스트리밍이 끝났어요"
            StreamEnd.DISCONNECTED -> "연결 끊김 (${Constants.Stream.DISCONNECT_TIMEOUT_MS / 1000}초 동안 소리 없음)"
            StreamEnd.FAILED -> "오류: $detail"
        }
    }
}
