package com.watchbabymonitor.common

import android.util.Log
import com.watchbabymonitor.shared.Constants
import com.watchbabymonitor.shared.engine.EngineLog

/** 엔진 로그를 `WBM/<이름>` 태그로 (CLAUDE.md §6). 오디오 내용은 절대 넘기지 않는다. */
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
