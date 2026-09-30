package com.watchbabymonitor.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.watchbabymonitor.common.DeviceInfo
import com.watchbabymonitor.common.RoleStore
import com.watchbabymonitor.common.notification.NoiseNotifications
import com.watchbabymonitor.common.service.MonitorService
import com.watchbabymonitor.common.service.StartPrompt
import com.watchbabymonitor.mobile.ui.AppRoot
import com.watchbabymonitor.mobile.ui.theme.WatchBabyMonitorTheme
import com.watchbabymonitor.shared.Constants
import com.watchbabymonitor.shared.Role

private val TAG = Constants.logTag("MainActivity")

class MainActivity : ComponentActivity() {

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> Log.i(TAG, "POST_NOTIFICATIONS granted=$granted") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "phone app started, role=${RoleStore.current(this)}")
        NoiseNotifications.ensureChannel(this)
        requestNotificationPermissionIfNeeded()

        enableEdgeToEdge()
        setContent {
            WatchBabyMonitorTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AppRoot(Modifier.padding(innerPadding))
                }
            }
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** 폰이 감지기일 때 "탭해서 모니터링 시작" 알림에서 열렸으면, 화면이 떠 있는 지금 시작 (ADR 004). */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action != DeviceInfo.ACTION_START_MONITORING) return
        StartPrompt.dismiss(this)
        if (RoleStore.current(this) != Role.SENSOR) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "start monitoring from remote request")
            MonitorService.start(this)
        } else {
            Log.w(TAG, "remote start: RECORD_AUDIO not granted yet")
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
