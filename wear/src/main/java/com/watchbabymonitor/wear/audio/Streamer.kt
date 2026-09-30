package com.watchbabymonitor.wear.audio

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.watchbabymonitor.shared.Constants
import com.watchbabymonitor.shared.Pcm16
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

private val TAG = Constants.logTag("Streamer")

/**
 * PCM16 프레임을 폰으로 보낸다: ChannelClient `/audio` 채널의 OutputStream 에 LE 바이트를 쓴다.
 *
 * 녹음은 [com.watchbabymonitor.wear.service.MonitorService] 가 하고, 여기서는 [offer] 로 받은
 * 프레임을 보내기만 한다. 대기열이 넘치면 오래된 프레임을 버려 지연이 쌓이지 않게 한다.
 */
class Streamer(context: Context, private val nodeId: String) {

    private val channelClient = Wearable.getChannelClient(context)
    private val queue = Channel<ShortArray>(
        capacity = Constants.Stream.WATCH_QUEUE_FRAMES,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    @Volatile
    private var channel: ChannelClient.Channel? = null

    /** 녹음 루프에서 호출. 막히지 않는다. */
    fun offer(frame: ShortArray) {
        queue.trySend(frame)
    }

    /**
     * 채널을 열고 [close] 되거나 취소될 때까지 보낸다.
     * 전송 실패(폰이 채널을 닫음, 연결 끊김)는 예외로 던진다.
     */
    suspend fun run() = withContext(Dispatchers.IO) {
        val ch = channelClient.openChannel(nodeId, Constants.Paths.AUDIO).await()
        channel = ch
        Log.i(TAG, "channel opened to $nodeId")
        var sentBytes = 0L
        try {
            channelClient.getOutputStream(ch).await().use { out ->
                val bytes = ByteArray(Constants.Audio.BYTES_PER_WINDOW)
                for (frame in queue) {
                    val n = Pcm16.encodeLe(frame, bytes)
                    out.write(bytes, 0, n)
                    out.flush()
                    sentBytes += n
                }
            }
        } finally {
            Log.i(TAG, "stream ended, sent ${Pcm16.bytesToMs(sentBytes) / 1000}s of audio")
            withContext(NonCancellable) { closeChannel() }
        }
    }

    /**
     * 전송 중단. 끊긴 연결에서 write 가 멈춰 있을 수 있으므로(CLAUDE.md §8)
     * 코루틴 취소와 별개로 채널을 직접 닫는다.
     */
    fun close() {
        queue.close()
        channel?.let { channelClient.close(it) }
    }

    private suspend fun closeChannel() {
        val ch = channel ?: return
        channel = null
        try {
            channelClient.close(ch).await()
        } catch (e: Exception) {
            Log.w(TAG, "channel close failed (already closed?)", e)
        }
    }
}
