package com.watchbabymonitor.wear

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.watchbabymonitor.shared.Constants
import com.watchbabymonitor.wear.ui.WearApp

private val TAG = Constants.logTag("MainActivity")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "watch app started")
        setContent { WearApp() }
    }
}
