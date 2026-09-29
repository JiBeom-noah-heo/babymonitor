package com.watchbabymonitor.shared

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * PCM16 버퍼의 소리 크기 계산. Android 의존성 없음.
 *
 * dBFS = 20 * log10(rms / 32768). 풀스케일 = 0 dBFS, 작을수록 음수.
 * 워치마다 마이크 감도가 달라 절대 dB SPL 이 아니라 상대값으로만 쓴다 (CLAUDE.md §4-2).
 */
object AudioLevel {

    /** 앞에서부터 [count] 개 샘플의 RMS. [count] 가 0 이면 0. */
    fun rms(samples: ShortArray, count: Int = samples.size): Double {
        require(count in 0..samples.size) { "count=$count, size=${samples.size}" }
        if (count == 0) return 0.0
        var sumSquares = 0.0
        for (i in 0 until count) {
            val s = samples[i].toDouble()
            sumSquares += s * s
        }
        return sqrt(sumSquares / count)
    }

    /** RMS → dBFS. 무음이면 [Constants.Audio.MIN_DBFS]. */
    fun rmsToDbfs(rms: Double): Float {
        if (rms <= 0.0) return Constants.Audio.MIN_DBFS
        val db = 20.0 * log10(rms / Constants.Audio.FULL_SCALE)
        return db.toFloat().coerceIn(Constants.Audio.MIN_DBFS, 0f)
    }

    fun dbfs(samples: ShortArray, count: Int = samples.size): Float = rmsToDbfs(rms(samples, count))

    /** 레벨 바용 0..1 값. [floorDbfs] 이하는 0, 0 dBFS 는 1. */
    fun normalize(dbfs: Float, floorDbfs: Float = Constants.Audio.DISPLAY_FLOOR_DBFS): Float {
        require(floorDbfs < 0f) { "floorDbfs must be negative: $floorDbfs" }
        return ((dbfs - floorDbfs) / -floorDbfs).coerceIn(0f, 1f)
    }
}
