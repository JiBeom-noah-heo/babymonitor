package com.watchbabymonitor.wear.service

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.WearableListenerService
import com.watchbabymonitor.shared.Constants
import com.watchbabymonitor.shared.ControlCommand

private val TAG = Constants.logTag("ControlReceiver")

/**
 * 폰에서 오는 `/control` 요청(MessageClient.sendRequest)을 받아 응답한다.
 * 앱이 꺼져 있어도 Play Services 가 이 서비스를 깨운다.
 */
class ControlReceiver : WearableListenerService() {

    override fun onRequest(nodeId: String, path: String, request: ByteArray): Task<ByteArray>? {
        if (path != Constants.Paths.CONTROL) {
            Log.w(TAG, "unexpected request path=$path from=$nodeId")
            return null
        }

        val reply = when (val cmd = ControlCommand.fromBytes(request)) {
            ControlCommand.Ping -> {
                Log.i(TAG, "PING from=$nodeId -> PONG")
                ControlEvents.onPing(nodeId)
                Constants.CONTROL_REPLY_PONG
            }
            null -> {
                Log.w(TAG, "unknown control command (${request.size} bytes) from=$nodeId")
                "${Constants.CONTROL_REPLY_ERROR_PREFIX}UNKNOWN"
            }
            else -> {
                // START / STOP / SET_THRESHOLD 는 Phase 3~4 에서 구현
                Log.w(TAG, "unsupported command=${cmd.encode()} from=$nodeId")
                "${Constants.CONTROL_REPLY_ERROR_PREFIX}UNSUPPORTED"
            }
        }
        return Tasks.forResult(reply.toByteArray(Charsets.UTF_8))
    }
}
