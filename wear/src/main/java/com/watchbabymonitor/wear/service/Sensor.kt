package com.watchbabymonitor.wear.service

import android.util.Log
import com.watchbabymonitor.shared.Clock
import com.watchbabymonitor.shared.Constants
import com.watchbabymonitor.shared.engine.EngineLog
import com.watchbabymonitor.shared.engine.SensorEngine

/**
 * 워치 프로세스의 감지기 엔진 (하나만). [MonitorService] 가 돌리고,
 * [ControlReceiver] 와 화면이 상태를 읽고 명령을 넘긴다.
 */
object Sensor {
    val engine = SensorEngine(clock = Clock.WALL, log = AndroidLog("MonitorService"))
}

/** 엔진 로그를 `WBM/<이름>` 태그로. 기존 로그 형식을 그대로 유지해 회귀 비교가 쉽게. */
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
