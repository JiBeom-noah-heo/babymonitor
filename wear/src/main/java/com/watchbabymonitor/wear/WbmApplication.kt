package com.watchbabymonitor.wear

import android.app.Application
import com.watchbabymonitor.common.StatusHub

class WbmApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // /status 동기화 (역할 충돌 경고, 상대 배터리 등)
        StatusHub.start(this)
    }
}
