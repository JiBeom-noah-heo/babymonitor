package com.watchbabymonitor.wear.audio

import android.Manifest
import androidx.annotation.RequiresPermission
import com.watchbabymonitor.shared.AudioLevel
import com.watchbabymonitor.shared.Constants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [AudioCapture] 프레임(100ms)마다 dBFS 를 계산해 [dbfs] 로 내보낸다.
 * 계산 자체는 shared 의 [AudioLevel].
 */
class LevelMeter(private val capture: AudioCapture = AudioCapture()) {

    private val _dbfs = MutableStateFlow(Constants.Audio.MIN_DBFS)
    val dbfs: StateFlow<Float> = _dbfs.asStateFlow()

    /** 취소될 때까지 측정. 녹음 실패 시 예외를 그대로 던진다. */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun run() {
        try {
            capture.frames().collect { frame ->
                _dbfs.value = AudioLevel.dbfs(frame)
            }
        } finally {
            _dbfs.value = Constants.Audio.MIN_DBFS
        }
    }
}
