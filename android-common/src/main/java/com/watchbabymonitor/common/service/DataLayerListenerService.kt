package com.watchbabymonitor.common.service

import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.watchbabymonitor.common.DeviceInfo
import com.watchbabymonitor.common.Engines
import com.watchbabymonitor.common.RoleStore
import com.watchbabymonitor.common.notification.NoiseNotifications
import com.watchbabymonitor.shared.Constants
import com.watchbabymonitor.shared.ControlCommand
import com.watchbabymonitor.shared.NoiseAlert
import com.watchbabymonitor.shared.Role
import com.watchbabymonitor.shared.engine.AlertAction
import com.watchbabymonitor.shared.engine.ControlEffect

private val TAG = Constants.logTag("DataLayerListener")

/**
 * 상대 기기에서 오는 Data Layer 이벤트. 앱이 꺼져 있어도 Play Services 가 깨운다.
 * 역할에 따라 처리한다 (CLAUDE.md §3):
 * - `/control` 요청 → 감지기면 [Engines.sensor] 로. `PING` 은 역할과 무관하게 `PONG`
 * - `/alert` 메시지 → 수신기면 [Engines.receiver] 가 정한 방식으로 알림
 */
class DataLayerListenerService : WearableListenerService() {

    override fun onRequest(nodeId: String, path: String, request: ByteArray): Task<ByteArray>? {
        if (path != Constants.Paths.CONTROL) {
            Log.w(TAG, "unexpected request path=$path from=$nodeId")
            return null
        }
        val cmd = ControlCommand.fromBytes(request)
        val role = RoleStore.current(this)
        val reply = when {
            cmd == ControlCommand.Ping -> {
                ControlEvents.onPing(nodeId)
                Constants.CONTROL_REPLY_PONG
            }
            role != Role.SENSOR -> Constants.CONTROL_REPLY_WRONG_ROLE
            else -> {
                val result = Engines.sensor.onControl(cmd, nodeId, canStartMonitoring = isAppVisible())
                when (result.effect) {
                    ControlEffect.START_MONITORING -> MonitorService.start(this)
                    ControlEffect.STOP_MONITORING -> MonitorService.stop(this)
                    ControlEffect.PROMPT_USER_TO_START -> StartPrompt.show(this)
                    null -> Unit
                }
                result.reply
            }
        }
        Log.i(TAG, "${cmd?.encode() ?: "UNKNOWN(${request.size}B)"} from=$nodeId role=$role -> $reply")
        return Tasks.forResult(reply.toByteArray(Charsets.UTF_8))
    }

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
        val role = RoleStore.current(this)
        if (role != Role.RECEIVER) {
            // 둘 다 감지기인 역할 충돌. 화면 경고는 /status 로 (CLAUDE.md §3)
            Log.w(TAG, "alert ignored: role=$role level=${alert.level} from=${event.sourceNodeId}")
            return
        }
        Log.i(TAG, "alert level=${alert.level} ts=${alert.ts} from=${event.sourceNodeId}")
        when (Engines.receiver.onAlert(alert, DeviceInfo.kind(this))) {
            AlertAction.NOTIFY_HEADS_UP, AlertAction.VIBRATE -> NoiseNotifications.show(this, alert)
            AlertAction.IGNORE_DUPLICATE -> Log.i(TAG, "duplicate alert ignored ts=${alert.ts}")
        }
    }

    /**
     * 앱 화면이 떠 있으면 마이크 포그라운드 서비스를 시작할 수 있다.
     * 백그라운드면 Android 가 막으므로 사용자에게 알림으로 요청한다 (ADR 004).
     */
    private fun isAppVisible(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
}
