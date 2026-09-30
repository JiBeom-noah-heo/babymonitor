package com.watchbabymonitor.common.datalayer

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.watchbabymonitor.shared.Constants
import com.watchbabymonitor.shared.DeviceStatus
import com.watchbabymonitor.shared.engine.EngineLog
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * `/status` (DataClient) 읽기·쓰기 (CLAUDE.md §3).
 * 각 기기가 자기 노드의 `wear://<nodeId>/status` 에 [DeviceStatus] JSON 을 올리고, 상대 것을 읽는다.
 */
class StatusSync(context: Context, private val log: EngineLog) {
    private val dataClient = Wearable.getDataClient(context)
    private val nodeClient = Wearable.getNodeClient(context)

    suspend fun publish(status: DeviceStatus) {
        val request = PutDataMapRequest.create(Constants.Paths.STATUS).apply {
            dataMap.putByteArray(KEY_JSON, status.toBytes())
        }.asPutDataRequest().setUrgent()
        dataClient.putDataItem(request).await()
    }

    /**
     * 상대 기기의 상태. 처음에 저장된 값을 한 번 내보내고, 바뀔 때마다 다시 내보낸다.
     * 상대가 없거나 아직 안 올렸으면 null.
     */
    fun peer(): Flow<DeviceStatus?> = callbackFlow {
        val localId = nodeClient.localNode.await().id
        var latest: Pair<String, DeviceStatus>? = null

        fun offer(item: DataItem) {
            val nodeId = item.uri.host ?: return
            if (nodeId == localId) return
            val status = decode(item) ?: return
            // 여러 상대가 있으면 가장 최근 것
            if (latest == null || latest!!.second.ts <= status.ts) latest = nodeId to status
            trySend(latest?.second)
        }

        val listener = DataClient.OnDataChangedListener { events ->
            events.forEach { event ->
                if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == Constants.Paths.STATUS) {
                    offer(event.dataItem)
                }
            }
            events.release()
        }
        dataClient.addListener(listener, Uri.parse("wear://*${Constants.Paths.STATUS}"), DataClient.FILTER_LITERAL).await()

        val existing = dataClient.getDataItems(Uri.parse("wear://*${Constants.Paths.STATUS}")).await()
        try {
            existing.forEach { offer(it) }
        } finally {
            existing.release()
        }
        if (latest == null) trySend(null)

        awaitClose { dataClient.removeListener(listener) }
    }

    private fun decode(item: DataItem): DeviceStatus? = try {
        DataMapItem.fromDataItem(item).dataMap.getByteArray(KEY_JSON)?.let(DeviceStatus::fromBytes)
    } catch (e: IllegalArgumentException) {
        log.w("malformed /status from ${item.uri.host}", e)
        null
    }

    private companion object {
        const val KEY_JSON = "json"
    }
}
