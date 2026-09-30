package com.watchbabymonitor.wear.service

import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.WearableListenerService
import com.watchbabymonitor.shared.Constants
import com.watchbabymonitor.shared.ControlCommand
import com.watchbabymonitor.shared.engine.ControlEffect

private val TAG = Constants.logTag("ControlReceiver")

/**
 * 수신기에서 오는 `/control` 요청(MessageClient.sendRequest)을 [Sensor.engine] 으로 넘기고 응답한다.
 * 앱이 꺼져 있어도 Play Services 가 이 서비스를 깨운다.
 */
class ControlReceiver : WearableListenerService() {

    override fun onRequest(nodeId: String, path: String, request: ByteArray): Task<ByteArray>? {
        if (path != Constants.Paths.CONTROL) {
            Log.w(TAG, "unexpected request path=$path from=$nodeId")
            return null
        }

        val cmd = ControlCommand.fromBytes(request)
        if (cmd == ControlCommand.Ping) ControlEvents.onPing(nodeId)

        val result = Sensor.engine.onControl(cmd, nodeId, canStartMonitoring = isAppVisible())
        Log.i(TAG, "${cmd?.encode() ?: "UNKNOWN(${request.size}B)"} from=$nodeId -> ${result.reply}")
        when (result.effect) {
            ControlEffect.START_MONITORING -> MonitorService.start(this)
            ControlEffect.STOP_MONITORING -> MonitorService.stop(this)
            ControlEffect.PROMPT_USER_TO_START -> StartPrompt.show(this)
            null -> Unit
        }
        return Tasks.forResult(result.reply.toByteArray(Charsets.UTF_8))
    }

    /**
     * 앱 화면이 떠 있으면 마이크 포그라운드 서비스를 시작할 수 있다.
     * 백그라운드면 Android 가 막으므로 사용자에게 알림으로 요청한다 (ADR 004).
     */
    private fun isAppVisible(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
}
