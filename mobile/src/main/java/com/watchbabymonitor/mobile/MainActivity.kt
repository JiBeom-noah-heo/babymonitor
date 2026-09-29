package com.watchbabymonitor.mobile

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
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

@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = "${Constants.APP_NAME} (phone)", style = MaterialTheme.typography.titleLarge)
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    WatchBabyMonitorTheme { HomeScreen() }
}
