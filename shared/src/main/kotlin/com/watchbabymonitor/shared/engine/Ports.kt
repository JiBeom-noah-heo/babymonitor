package com.watchbabymonitor.shared.engine

import com.watchbabymonitor.shared.NoiseAlert
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * 엔진이 플랫폼에 요구하는 기능 (포트). 구현은 android-common / 각 앱에 있다.
 * 엔진은 이 인터페이스만 알고 Android API 는 모른다 (CLAUDE.md §4-7).
 */

/** 알림 전송 결과. [delivered] 가 0 이면 아무 기기에도 못 보냄. */
data class Delivery(val delivered: Int, val total: Int)

/** 감지기 → 수신기 `/alert` 전송. */
fun interface AlertSink {
    /** 연결된 수신기들에 보낸다. 실패는 예외 대신 [Delivery] 로 알려도 되고 예외를 던져도 된다. */
    suspend fun send(alert: NoiseAlert): Delivery
}

/** 수신기 하나로 가는 `/audio` 스트림. */
interface StreamSink {
    /**
     * [frames] 가 닫히거나 [abort] 될 때까지 보낸다.
     * 전송 실패(상대가 채널을 닫음, 연결 끊김)는 예외로 끝난다.
     */
    suspend fun send(frames: ReceiveChannel<ShortArray>)

    /** 즉시 중단. 끊긴 연결에서 write 가 멈춰 있어도 풀리도록 (CLAUDE.md §8). 어느 스레드에서나 호출 가능. */
    fun abort()
}

fun interface StreamSinkFactory {
    fun create(nodeId: String): StreamSink
}

/** 로그. Android 에서는 `WBM/<클래스명>` 태그로 연결. 오디오 내용은 절대 넘기지 않는다. */
interface EngineLog {
    fun i(msg: String)
    fun w(msg: String, t: Throwable? = null)
    fun e(msg: String, t: Throwable? = null)

    companion object {
        val NONE = object : EngineLog {
            override fun i(msg: String) = Unit
            override fun w(msg: String, t: Throwable?) = Unit
            override fun e(msg: String, t: Throwable?) = Unit
        }
    }
}
