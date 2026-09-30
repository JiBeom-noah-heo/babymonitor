package com.watchbabymonitor.mobile.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.watchbabymonitor.shared.Constants
import com.watchbabymonitor.shared.Pcm16
import com.watchbabymonitor.shared.engine.PlaybackStats
import com.watchbabymonitor.shared.engine.ReceiverEngine

private val TAG = Constants.logTag("AudioPlayer")

/**
 * 16kHz mono PCM16(LE) 스트림 재생용 AudioTrack 래퍼.
 *
 * - 처음(그리고 재동기화 후) [Constants.Stream.PREBUFFER_MS] 만큼 모은 뒤 재생 시작
 * - 재생 대기량이 [Constants.Stream.MAX_PLAYBACK_BACKLOG_MS] 를 넘으면 트랙에 쌓인 **오래된** 소리를
 *   버리고(트랙 재생성) 최신 소리부터 다시 재생한다 → 지연이 쌓이지 않음
 *   (pause+flush 는 재생 위치가 play() 이후에야 0 으로 바뀌어 대기량 계산이 어긋남)
 *
 * 받은 데이터는 항상 트랙에 쓴다. 네트워크가 잠깐 늦어 트랙이 비면 AudioFlinger 가 트랙을
 * 비활성화하는데, 다음 write 가 있어야 다시 켜지기 때문 (devlog Phase 4 참고).
 *
 * 한 스레드에서만 호출한다.
 */
class AudioPlayer {

    private var track: AudioTrack = newTrack()
    private val prebufferBytes = Pcm16.msToBytes(Constants.Stream.PREBUFFER_MS)

    // 현재 트랙에 쓴 바이트
    private var writtenBytes = 0L
    private var started = false

    // 이전 트랙들의 끊김 횟수 합
    private var underrunsBefore = 0

    // 홀수 바이트로 끊겨 들어온 경우 다음 write 로 넘길 1바이트
    private var carry: Byte? = null
    private val scratch = ByteArray(IO_CHUNK + 1)

    /** 지연을 줄이려고 버린 바이트 수. */
    var droppedBytes = 0L
        private set

    /** 대기량 초과로 재동기화한 횟수. */
    var resyncs = 0
        private set

    private fun newTrack(): AudioTrack {
        val minBytes = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        check(minBytes > 0) { "AudioTrack.getMinBufferSize failed: $minBytes" }
        // 대기량 상한보다 넉넉하게 → write 가 막히지 않음
        val bufferBytes = maxOf(minBytes * 2, Pcm16.msToBytes(Constants.Stream.MAX_PLAYBACK_BACKLOG_MS * 2))

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL)
                    .setEncoding(ENCODING)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw IllegalStateException("AudioTrack init failed")
        }

        // AudioFlinger 는 기본적으로 버퍼가 "가득" 찰 때까지 재생을 시작하지 않는다.
        // 대기량 상한(0.6초)이 버퍼(1.2초)보다 작아서 영영 시작 안 되고 BUFFER TIMEOUT 으로
        // 비활성화됐음 → prebuffer 만큼만 차면 시작하도록 (devlog Phase 4)
        val prebufferFrames = Pcm16.msToBytes(Constants.Stream.PREBUFFER_MS) / Constants.Audio.BYTES_PER_SAMPLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            track.setStartThresholdInFrames(prebufferFrames)
        } else {
            // API 29~30: 시작 기준을 못 바꾸므로 유효 버퍼를 대기량 상한으로 줄임
            track.setBufferSizeInFrames(
                Pcm16.msToBytes(Constants.Stream.MAX_PLAYBACK_BACKLOG_MS) / Constants.Audio.BYTES_PER_SAMPLE,
            )
        }
        return track
    }

    private fun headBytes(): Long =
        (track.playbackHeadPosition.toLong() and 0xFFFFFFFFL) * Constants.Audio.BYTES_PER_SAMPLE

    /** 폰에서 아직 재생되지 않은 양 (ms). */
    val backlogMs: Long
        get() = Pcm16.bytesToMs((writtenBytes - headBytes()).coerceAtLeast(0))

    val underruns: Int get() = underrunsBefore + track.underrunCount

    fun stats() = PlaybackStats(
        backlogMs = backlogMs,
        underruns = underruns,
        droppedMs = Pcm16.bytesToMs(droppedBytes),
        resyncs = resyncs,
    )


    /** 수신한 바이트를 재생 대기열에 넣는다. [length] 는 홀수여도 된다. */
    fun write(data: ByteArray, length: Int) {
        var offset = 0
        while (offset < length) {
            val n = minOf(IO_CHUNK, length - offset)
            writeChunk(data, offset, n)
            offset += n
        }
    }

    private fun writeChunk(data: ByteArray, offset: Int, length: Int) {
        var size = 0
        carry?.let { scratch[size++] = it }
        carry = null
        System.arraycopy(data, offset, scratch, size, length)
        size += length
        if (size % 2 == 1) {
            carry = scratch[size - 1]
            size--
        }
        if (size == 0) return

        if (ReceiverEngine.shouldResync(started, backlogMs)) resync()

        val written = track.write(scratch, 0, size, AudioTrack.WRITE_BLOCKING)
        check(written >= 0) { "AudioTrack.write error: $written" }
        writtenBytes += written

        if (!started && writtenBytes >= prebufferBytes) {
            track.play()
            started = true
        }
    }

    /** 쌓인 오래된 소리를 버리고 새 트랙으로 다시 prebuffer 부터. */
    private fun resync() {
        val backlogBytes = Pcm16.msToBytes(backlogMs.toInt()).toLong()
        underrunsBefore += track.underrunCount
        release()
        track = newTrack()
        droppedBytes += backlogBytes
        resyncs++
        writtenBytes = 0
        started = false
        Log.i(TAG, "resync #$resyncs: dropped ${Pcm16.bytesToMs(backlogBytes)}ms backlog")
    }

    fun release() {
        try {
            if (started) track.stop()
        } finally {
            track.release()
        }
    }

    private companion object {
        const val SAMPLE_RATE = Constants.Audio.SAMPLE_RATE_HZ
        const val CHANNEL = AudioFormat.CHANNEL_OUT_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val IO_CHUNK = 4096
    }
}
