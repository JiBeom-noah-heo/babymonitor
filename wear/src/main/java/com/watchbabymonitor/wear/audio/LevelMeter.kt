package com.watchbabymonitor.wear.audio

import android.Manifest
import androidx.annotation.RequiresPermission
import com.watchbabymonitor.shared.AudioLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [AudioCapture] 프레임(100ms)마다 dBFS 를 계산한다. 계산 자체는 shared 의 [AudioLevel].
 * 상태 보관은 호출 측([com.watchbabymonitor.wear.service.MonitorStatus]) 담당.
 */
class LevelMeter(private val capture: AudioCapture = AudioCapture()) {

    /** collect 하는 동안 녹음. 녹음 실패 시 예외가 Flow 로 전파된다. */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun levels(): Flow<Float> = capture.frames().map { AudioLevel.dbfs(it) }
}
