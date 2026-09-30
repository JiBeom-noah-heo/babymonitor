package com.watchbabymonitor.shared.engine

import com.watchbabymonitor.shared.AudioLevel
import com.watchbabymonitor.shared.Clock
import com.watchbabymonitor.shared.Constants
import com.watchbabymonitor.shared.ControlCommand
import com.watchbabymonitor.shared.DetectionConfig
import com.watchbabymonitor.shared.DetectionPreset
import com.watchbabymonitor.shared.NoiseAlert
import com.watchbabymonitor.shared.NoiseDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.max

data class SensorState(
    val running: Boolean = false,
    val dbfs: Float = Constants.Audio.MIN_DBFS,
    /** 현재 감지 설정 (프리셋 + 사용자 조정, Phase 6). */
    val config: DetectionConfig = DetectionConfig(),
    val alertCount: Int = 0,
    val lastAlert: NoiseAlert? = null,
    /** 마지막 알림을 보낸 수신기 수. null = 전송 중, 0 = 실패. */
    val lastAlertDelivered: Int? = null,
    val error: String? = null,
    /** 라이브 오디오를 보내는 중. */
    val streaming: Boolean = false,
    /** 마이크 입력이 완전히 0 — 통화 등으로 다른 앱이 마이크를 가져감 (Phase 5). */
    val micMuted: Boolean = false,
) {
    val thresholdDbfs: Float get() = config.thresholdDbfs
    val preset: DetectionPreset get() = config.preset
}

/** `/control` 처리 후 플랫폼이 해야 할 일. 엔진은 포그라운드 서비스를 직접 켜고 끌 수 없다. */
enum class ControlEffect {
    /** 모니터링 서비스 시작 (앱이 화면에 떠 있어 마이크 FGS 시작 가능할 때만 나옴). */
    START_MONITORING,

    /** 모니터링 서비스 정지. */
    STOP_MONITORING,

    /** 사용자에게 "탭해서 모니터링 시작" 알림 (백그라운드라 직접 시작 불가). */
    PROMPT_USER_TO_START,
}

data class ControlResult(val reply: String, val effect: ControlEffect? = null)

/**
 * 감지기 역할 로직 (CLAUDE.md §3, §4-7). 순수 Kotlin.
 *
 * - [run]: 100ms 프레임마다 레벨 계산 → [NoiseDetector] 판정 → [AlertSink] 로 알림,
 *   스트리밍 중이면 같은 프레임을 [StreamSink] 로도 보낸다 (ADR 004)
 * - [onControl]: `/control` 명령 처리. 모니터링 중이 아닐 때도 호출된다.
 *
 * 앱 프로세스에 하나만 두고, 모니터링 서비스가 [run] 을 돌린다.
 */
class SensorEngine(
    private val clock: Clock = Clock.WALL,
    private val log: EngineLog = EngineLog.NONE,
) {
    private val _state = MutableStateFlow(SensorState())
    val state: StateFlow<SensorState> = _state.asStateFlow()

    // run 중에만 유효. onControl 은 다른 스레드(Data Layer 리스너)에서 온다.
    @Volatile
    private var runScope: CoroutineScope? = null

    @Volatile
    private var streamSinks: StreamSinkFactory? = null

    @Volatile
    private var stream: ActiveStream? = null

    // 다음 프레임에 적용할 설정. onControl / 화면에서 온다
    @Volatile
    private var pendingConfig: DetectionConfig? = null

    // startStream / stopStream 은 녹음 코루틴과 리스너 스레드에서 동시에 올 수 있음
    private val streamLock = Any()

    private class ActiveStream(
        val nodeId: String,
        val sink: StreamSink,
        val queue: Channel<ShortArray>,
    ) {
        lateinit var job: Job
    }

    /**
     * 취소될 때까지 [frames] 를 처리한다. 녹음 실패는 [SensorState.error] 에 남기고 예외를 다시 던진다
     * (서비스가 스스로 정지하도록).
     */
    suspend fun run(
        frames: Flow<ShortArray>,
        alerts: AlertSink,
        streams: StreamSinkFactory,
        config: DetectionConfig = _state.value.config,
    ) = coroutineScope {
        val startConfig = (pendingConfig ?: config).clamped()
        pendingConfig = null
        var detector = startConfig.newDetector()
        runScope = this
        streamSinks = streams
        _state.update { it.copy(running = true, config = startConfig, error = null) }
        log.i(
            "monitoring started, preset=${startConfig.preset}, threshold=${startConfig.thresholdDbfs} dBFS, " +
                "cooldown=${startConfig.cooldownMs / 1000}s",
        )

        var frameCount = 0L
        var maxSinceHeartbeat = Constants.Audio.MIN_DBFS
        var silentFrames = 0
        try {
            frames.collect { frame ->
                // 라이브 듣기 중이면 같은 프레임을 수신기로도 (대기열이 넘치면 오래된 것부터 버림)
                stream?.queue?.trySend(frame)

                pendingConfig?.let { c ->
                    pendingConfig = null
                    val old = _state.value.config
                    if (c.preset != old.preset) {
                        // 프리셋이 바뀌면 지속 조건도 달라지므로 판정을 새로 시작
                        detector = c.newDetector()
                        log.i("preset changed to ${c.preset}, threshold=${c.thresholdDbfs} dBFS")
                    } else {
                        detector.thresholdDbfs = c.thresholdDbfs
                        detector.cooldownMs = c.cooldownMs
                        log.i("config changed: threshold=${c.thresholdDbfs} dBFS, cooldown=${c.cooldownMs / 1000}s")
                    }
                    _state.update { it.copy(config = c) }
                }

                val dbfs = AudioLevel.dbfs(frame)
                _state.update { it.copy(dbfs = dbfs) }

                // 실제 방 소리는 완전한 0 이 아니다. 0 이 이어지면 마이크를 뺏긴 것 (통화 중 등)
                if (dbfs <= Constants.Audio.MIN_DBFS) silentFrames++ else silentFrames = 0
                val muted = silentFrames >= MUTED_FRAMES
                if (muted != _state.value.micMuted) {
                    log.i(if (muted) "mic muted (no signal for ${Constants.Link.MIC_MUTED_DETECT_MS}ms)" else "mic signal back")
                    _state.update { it.copy(micMuted = muted) }
                }
                detector.onLevel(dbfs, clock.nowMs())?.let { alert ->
                    log.i("noise alert level=${alert.level}")
                    _state.update {
                        it.copy(alertCount = it.alertCount + 1, lastAlert = alert, lastAlertDelivered = null)
                    }
                    launch { deliver(alerts, alert) }
                }

                frameCount++
                maxSinceHeartbeat = max(maxSinceHeartbeat, dbfs)
                if (frameCount % HEARTBEAT_FRAMES == 0L) {
                    log.i(
                        "alive: ${frameCount * Constants.Audio.LEVEL_WINDOW_MS / 1000}s, " +
                            "max=${maxSinceHeartbeat.toInt()} dBFS, alerts=${_state.value.alertCount}",
                    )
                    maxSinceHeartbeat = Constants.Audio.MIN_DBFS
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e("monitoring failed", e)
            _state.update { it.copy(error = "마이크 오류: ${e.message ?: e.javaClass.simpleName}") }
            throw e
        } finally {
            stopStream()
            runScope = null
            streamSinks = null
            _state.update {
                it.copy(running = false, streaming = false, micMuted = false, dbfs = Constants.Audio.MIN_DBFS)
            }
            log.i("monitoring stopped")
        }
    }

    /**
     * `/control` 명령 처리. 응답 문자열과, 플랫폼이 실행할 [ControlEffect] 를 돌려준다.
     * @param canStartMonitoring 감지기 앱이 화면에 떠 있어 마이크 포그라운드 서비스를 시작할 수 있는지
     */
    fun onControl(cmd: ControlCommand?, fromNodeId: String, canStartMonitoring: Boolean): ControlResult {
        val running = _state.value.running
        return when (cmd) {
            ControlCommand.Ping -> ControlResult(Constants.CONTROL_REPLY_PONG)
            ControlCommand.Start -> when {
                running -> ControlResult(Constants.CONTROL_REPLY_OK)
                canStartMonitoring -> ControlResult(Constants.CONTROL_REPLY_OK, ControlEffect.START_MONITORING)
                else -> ControlResult(Constants.CONTROL_REPLY_NEEDS_USER, ControlEffect.PROMPT_USER_TO_START)
            }
            ControlCommand.Stop ->
                if (running) ControlResult(Constants.CONTROL_REPLY_OK, ControlEffect.STOP_MONITORING)
                else ControlResult(Constants.CONTROL_REPLY_OK)
            ControlCommand.StreamOn ->
                if (startStream(fromNodeId)) {
                    log.i("STREAM_ON to=$fromNodeId")
                    ControlResult(Constants.CONTROL_REPLY_OK)
                } else {
                    log.w("STREAM_ON rejected: monitoring not running")
                    ControlResult(Constants.CONTROL_REPLY_NOT_MONITORING)
                }
            ControlCommand.StreamOff -> {
                log.i("STREAM_OFF from=$fromNodeId")
                stopStream()
                ControlResult(Constants.CONTROL_REPLY_OK)
            }
            is ControlCommand.SetThreshold -> {
                setConfig(currentConfig().copy(thresholdDbfs = cmd.db))
                ControlResult(Constants.CONTROL_REPLY_OK)
            }
            is ControlCommand.SetCooldown -> {
                setConfig(currentConfig().copy(cooldownMs = cmd.ms))
                ControlResult(Constants.CONTROL_REPLY_OK)
            }
            is ControlCommand.SetPreset -> {
                setConfig(DetectionConfig.of(cmd.preset))
                ControlResult(Constants.CONTROL_REPLY_OK)
            }
            null -> ControlResult(Constants.CONTROL_REPLY_UNKNOWN)
        }
    }

    /**
     * 감지 프리셋 변경 (집 안 / 차 안). 모니터링 중이면 다음 프레임부터 새 조건으로 판정
     * (판정 구간·쿨다운도 새로 시작).
     */
    fun setPreset(preset: DetectionPreset) = setConfig(DetectionConfig.of(preset))

    /**
     * 감지 설정 변경 (설정 화면, `/control SET_*`). 범위 밖 값은 가장자리로.
     * 모니터링 중이면 다음 프레임부터, 아니면 바로 상태에 반영 (플랫폼이 상태를 보고 저장).
     */
    fun setConfig(config: DetectionConfig) {
        val c = config.clamped()
        if (_state.value.running) {
            pendingConfig = c
        } else {
            _state.update { it.copy(config = c) }
        }
    }

    private fun currentConfig(): DetectionConfig = pendingConfig ?: _state.value.config

    /** 플랫폼 쪽 실패(권한 없음 등)를 상태에 남긴다. */
    fun reportError(message: String) {
        log.e(message)
        _state.update { it.copy(error = message) }
    }

    private fun startStream(nodeId: String): Boolean = synchronized(streamLock) {
        val scope = runScope ?: return false
        val factory = streamSinks ?: return false
        stopStream()

        val s = ActiveStream(
            nodeId = nodeId,
            sink = factory.create(nodeId),
            queue = Channel(Constants.Stream.WATCH_QUEUE_FRAMES, BufferOverflow.DROP_OLDEST),
        )
        stream = s
        _state.update { it.copy(streaming = true, error = null) }
        // job 을 먼저 대입한 뒤 시작 (stopStream 이 lateinit 을 먼저 읽지 않도록)
        s.job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                s.sink.send(s.queue)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 수신기가 채널을 닫은 경우도 여기로 온다
                log.w("streaming to ${s.nodeId} ended: ${e.message}")
            } finally {
                synchronized(streamLock) {
                    if (stream === s) {
                        stream = null
                        _state.update { it.copy(streaming = false) }
                    }
                }
            }
        }
        s.job.start()
        return true
    }

    private fun stopStream() = synchronized(streamLock) {
        val s = stream ?: return
        stream = null
        s.queue.close()
        s.sink.abort()
        s.job.cancel()
        _state.update { it.copy(streaming = false) }
    }

    private suspend fun deliver(alerts: AlertSink, alert: NoiseAlert) {
        val delivered = try {
            val d = alerts.send(alert)
            log.i("alert sent to ${d.delivered}/${d.total} nodes")
            d.delivered
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e("sendAlert failed", e)
            0
        }
        _state.update {
            if (it.lastAlert != alert) {
                it
            } else {
                it.copy(
                    lastAlertDelivered = delivered,
                    error = if (delivered == 0) "수신기에 알림 전달 실패" else it.error,
                )
            }
        }
    }

    private companion object {
        /** 1분마다 동작 로그. */
        const val HEARTBEAT_FRAMES = 60_000L / Constants.Audio.LEVEL_WINDOW_MS

        const val MUTED_FRAMES = Constants.Link.MIC_MUTED_DETECT_MS / Constants.Audio.LEVEL_WINDOW_MS
    }
}
