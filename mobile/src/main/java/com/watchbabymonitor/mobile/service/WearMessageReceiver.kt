package com.watchbabymonitor.mobile.service

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.watchbabymonitor.mobile.notification.NoiseNotifications
import com.watchbabymonitor.shared.Constants
import com.watchbabymonitor.shared.NoiseAlert

private val TAG = Constants.logTag("WearMessageReceiver")

/**
 * 워치 → 폰 메시지 수신. 앱이 꺼져 있어도 Play Services 가 깨운다.
 */
class WearMessageReceiver : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            Constants.Paths.ALERT -> onAlert(event)
            else -> Log.w(TAG, "unexpected message path=${event.path} from=${event.sourceNodeId}")
        }
    }

    private fun onAlert(event: MessageEvent) {
        val alert = try {
            NoiseAlert.fromBytes(event.data)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "malformed /alert (${event.data.size} bytes) from=${event.sourceNodeId}", e)
            return
        }
        Log.i(TAG, "alert level=${alert.level} ts=${alert.ts} from=${event.sourceNodeId}")
        AlertEvents.emit(alert)
        NoiseNotifications.show(this, alert)
    }
}
