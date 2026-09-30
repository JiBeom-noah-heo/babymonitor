package com.watchbabymonitor.common.datalayer

import android.content.Context
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.watchbabymonitor.shared.Constants
import com.watchbabymonitor.shared.ControlCommand
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

/** 수신기 → 감지기 `/control` 요청 (MessageClient.sendRequest, ADR 002). */
class ControlClient(context: Context) {
    private val messageClient = Wearable.getMessageClient(context)
    private val nodeClient = Wearable.getNodeClient(context)

    suspend fun connectedNodes(): List<Node> = nodeClient.connectedNodes.await()

    /**
     * [cmd] 를 보내고 응답 문자열을 돌려준다.
     * @throws kotlinx.coroutines.TimeoutCancellationException [timeoutMs] 안에 응답이 없을 때
     */
    suspend fun send(
        nodeId: String,
        cmd: ControlCommand,
        timeoutMs: Long = Constants.CONTROL_REQUEST_TIMEOUT_MS,
    ): String = withTimeout(timeoutMs) {
        messageClient.sendRequest(nodeId, Constants.Paths.CONTROL, cmd.toBytes()).await()
    }.toString(Charsets.UTF_8)
}
