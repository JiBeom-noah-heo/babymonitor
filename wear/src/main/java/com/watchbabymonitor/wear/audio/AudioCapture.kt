package com.watchbabymonitor.wear.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import com.watchbabymonitor.shared.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.IOException

private val TAG = Constants.logTag("AudioCapture")

/**
 * 워치 마이크 → PCM16 프레임 Flow.
 * 16kHz / mono / PCM16, 프레임 하나 = [Constants.Audio.SAMPLES_PER_WINDOW] 샘플(100ms).
 *
 * collect 하는 동안만 녹음하고, 취소되면 AudioRecord 를 해제한다.
 */
class AudioCapture {

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun frames(): Flow<ShortArray> = flow {
        val record = createRecord()
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("AudioRecord failed to start (mic busy?)")
            }
            Log.i(TAG, "recording started")

            val frame = ShortArray(Constants.Audio.SAMPLES_PER_WINDOW)
            while (currentCoroutineContext().isActive) {
                readFully(record, frame)
                emit(frame.copyOf())
            }
        } finally {
            record.stop()
            record.release()
            Log.i(TAG, "recording stopped")
        }
    }.flowOn(Dispatchers.IO)

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun createRecord(): AudioRecord {
        val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBytes <= 0) throw IllegalStateException("getMinBufferSize failed: $minBytes")

        // CLAUDE.md §8: 최소 버퍼의 2배 이상. 100ms 프레임 2개 이상도 보장.
        val frameBytes = Constants.Audio.SAMPLES_PER_WINDOW * BYTES_PER_SAMPLE
        val bufferBytes = maxOf(minBytes * 2, frameBytes * 2)

        // VOICE_RECOGNITION: 자동 게인(AGC)·노이즈 억제를 끈 입력 → 레벨 비교가 일정함
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, CHANNEL, ENCODING, bufferBytes,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord init failed")
        }
        Log.i(TAG, "AudioRecord ready: minBuffer=$minBytes, buffer=$bufferBytes bytes")
        return record
    }

    /** [frame] 을 가득 채울 때까지 읽는다. */
    private fun readFully(record: AudioRecord, frame: ShortArray) {
        var offset = 0
        while (offset < frame.size) {
            val read = record.read(frame, offset, frame.size - offset, AudioRecord.READ_BLOCKING)
            if (read < 0) throw IOException("AudioRecord.read error: $read")
            offset += read
        }
    }

    private companion object {
        const val SAMPLE_RATE = Constants.Audio.SAMPLE_RATE_HZ
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val BYTES_PER_SAMPLE = 2
    }
}
