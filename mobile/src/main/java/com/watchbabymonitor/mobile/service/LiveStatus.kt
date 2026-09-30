package com.watchbabymonitor.mobile.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class LivePhase {
    IDLE,
    CONNECTING,
    PLAYING,
    STALLED,
}

data class LiveState(
    val phase: LivePhase = LivePhase.IDLE,
    val watchName: String? = null,
    /** 폰 재생 대기량 = 추정 지연 중 폰 쪽 몫 (ms). */
    val backlogMs: Long = 0,
    /** 최근 1초 수신량 (kbps). 정상이면 약 256. */
    val kbps: Int = 0,
    /** AudioTrack 이 재생할 데이터가 없어 끊긴 횟수. */
    val underruns: Int = 0,
    /** 지연을 따라잡으려고 버린 오디오 (ms). */
    val droppedMs: Long = 0,
    /** 대기량이 너무 쌓여 오래된 소리를 비운 횟수. */
    val resyncs: Int = 0,
    /** 마지막 세션이 오류로 끝났으면 이유. */
    val error: String? = null,
)

/** [ListenerService] 상태를 화면으로 전달하는 프로세스 내 저장소. */
object LiveStatus {
    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state.asStateFlow()

    internal fun update(block: (LiveState) -> LiveState) = _state.update(block)

    internal fun connecting() = _state.update { LiveState(phase = LivePhase.CONNECTING) }

    internal fun stopped(error: String? = null) = _state.update {
        it.copy(phase = LivePhase.IDLE, backlogMs = 0, kbps = 0, error = error)
    }
}
