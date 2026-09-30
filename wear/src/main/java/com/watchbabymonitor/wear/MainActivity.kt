package com.watchbabymonitor.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import com.watchbabymonitor.shared.Constants
import com.watchbabymonitor.wear.service.MonitorService
import com.watchbabymonitor.wear.service.StartPrompt
import com.watchbabymonitor.wear.ui.WearApp

private val TAG = Constants.logTag("MainActivity")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "watch app started")
        setContent { WearApp() }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** "탭해서 모니터링 시작" 알림에서 열렸으면, 화면이 떠 있는 지금 시작 (ADR 004). */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action != ACTION_START_MONITORING) return
        StartPrompt.dismiss(this)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "start monitoring from remote request")
            MonitorService.start(this)
        } else {
            // 권한 요청은 화면의 "모니터링 시작" 버튼에서
            Log.w(TAG, "remote start: RECORD_AUDIO not granted yet")
        }
    }

    companion object {
        const val ACTION_START_MONITORING = "com.watchbabymonitor.wear.action.START_MONITORING"
    }
}
