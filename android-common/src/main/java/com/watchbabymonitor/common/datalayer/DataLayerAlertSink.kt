package com.watchbabymonitor.common.datalayer

import android.content.Context
import com.google.android.gms.wearable.Wearable
import com.watchbabymonitor.shared.Constants
import com.watchbabymonitor.shared.NoiseAlert
import com.watchbabymonitor.shared.engine.AlertSink
import com.watchbabymonitor.shared.engine.Delivery
import com.watchbabymonitor.shared.engine.EngineLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/** `/alert` 를 연결된 모든 노드에 MessageClient 로 보낸다. */
class DataLayerAlertSink(context: Context, private val log: EngineLog) : AlertSink {
    private val messageClient = Wearable.getMessageClient(context)
    private val nodeClient = Wearable.getNodeClient(context)

    override suspend fun send(alert: NoiseAlert): Delivery {
        val nodes = nodeClient.connectedNodes.await()
        var delivered = 0
        for (node in nodes) {
            try {
                messageClient.sendMessage(node.id, Constants.Paths.ALERT, alert.toBytes()).await()
                delivered++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.e("sendAlert to ${node.displayName} failed", e)
            }
        }
        return Delivery(delivered, nodes.size)
    }
}
