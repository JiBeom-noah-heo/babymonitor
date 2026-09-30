package com.watchbabymonitor.common.audio

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.watchbabymonitor.shared.Constants
import com.watchbabymonitor.shared.Pcm16
import com.watchbabymonitor.shared.engine.StreamSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

private val TAG = Constants.logTag("Streamer")

/**
 * 수신기 하나로 가는 `/audio` 스트림: ChannelClient 채널의 OutputStream 에 PCM16 LE 바이트를 쓴다.
 * 어떤 프레임을 보낼지(대기열, 버림)는 SensorEngine 이 정한다.
 */
class Streamer(context: Context, private val nodeId: String) : StreamSink {

    private val channelClient = Wearable.getChannelClient(context)

    @Volatile
    private var channel: ChannelClient.Channel? = null

    @Volatile
    private var aborted = false

    override suspend fun send(frames: ReceiveChannel<ShortArray>) = withContext(Dispatchers.IO) {
        val ch = channelClient.openChannel(nodeId, Constants.Paths.AUDIO).await()
        channel = ch
        if (aborted) {
            closeChannel()
            return@withContext
        }
        Log.i(TAG, "channel opened to $nodeId")
        var sentBytes = 0L
        try {
            channelClient.getOutputStream(ch).await().use { out ->
                val bytes = ByteArray(Constants.Audio.BYTES_PER_WINDOW)
                for (frame in frames) {
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

    /** 끊긴 연결에서 write 가 멈춰 있을 수 있으므로(CLAUDE.md §8) 채널을 직접 닫아 푼다. */
    override fun abort() {
        aborted = true
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
