package com.watchbabymonitor.mobile

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.watchbabymonitor.mobile.ui.HomeScreen
import com.watchbabymonitor.mobile.ui.theme.WatchBabyMonitorTheme
import com.watchbabymonitor.shared.Constants

private val TAG = Constants.logTag("MainActivity")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "phone app started")
        enableEdgeToEdge()
        setContent {
            WatchBabyMonitorTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    HomeScreen(Modifier.padding(innerPadding))
                }
            }
        }
    }
}
