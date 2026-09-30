package com.watchbabymonitor.wear.service

import android.util.Log
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

        val cmd = legacyToStream(ControlCommand.fromBytes(request))
        if (cmd == ControlCommand.Ping) ControlEvents.onPing(nodeId)

        // TODO(refactor 6단계): 원격 START 로 모니터링 시작 (앱이 화면에 있을 때만) + 사용자 알림
        val result = Sensor.engine.onControl(cmd, nodeId, canStartMonitoring = false)
        Log.i(TAG, "${cmd?.encode() ?: "UNKNOWN(${request.size}B)"} from=$nodeId -> ${result.reply}")
        when (result.effect) {
            ControlEffect.STOP_MONITORING -> MonitorService.stop(this)
            ControlEffect.START_MONITORING, ControlEffect.PROMPT_USER_TO_START, null -> Unit
        }
        return Tasks.forResult(result.reply.toByteArray(Charsets.UTF_8))
    }

    /**
     * TODO(refactor 6단계에서 제거): 폰은 아직 스트리밍 제어에 START/STOP 을 보낸다.
     * 폰이 STREAM_ON/OFF 로 바뀌기 전까지 옛 의미로 해석.
     */
    private fun legacyToStream(cmd: ControlCommand?): ControlCommand? = when (cmd) {
        ControlCommand.Start -> ControlCommand.StreamOn
        ControlCommand.Stop -> ControlCommand.StreamOff
        else -> cmd
    }
}
