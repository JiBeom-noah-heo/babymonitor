package com.watchbabymonitor.mobile.service

import android.util.Log
import com.watchbabymonitor.shared.Clock
import com.watchbabymonitor.shared.Constants
import com.watchbabymonitor.shared.engine.EngineLog
import com.watchbabymonitor.shared.engine.ReceiverEngine

/**
 * 폰 프로세스의 수신기 엔진 (하나만). [ListenerService] 와 [WearMessageReceiver] 가 이벤트를 넘기고,
 * 화면이 상태를 읽는다.
 */
object Receiver {
    val engine = ReceiverEngine(clock = Clock.MONOTONIC, log = AndroidLog("ListenerService"))
}

// TODO(refactor 5단계): android-common 으로 옮겨 wear 의 것과 합친다
class AndroidLog(name: String) : EngineLog {
    private val tag = Constants.logTag(name)
    override fun i(msg: String) {
        Log.i(tag, msg)
    }
    override fun w(msg: String, t: Throwable?) {
        Log.w(tag, msg, t)
    }
    override fun e(msg: String, t: Throwable?) {
        Log.e(tag, msg, t)
    }
}
