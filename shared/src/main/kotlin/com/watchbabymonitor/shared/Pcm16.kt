package com.watchbabymonitor.shared

/**
 * `/audio` 스트림의 바이트 포맷: PCM16 little-endian, 헤더 없음 (CLAUDE.md §3).
 * Android 의 AudioTrack.write(ByteArray) 도 little-endian 으로 해석한다.
 */
object Pcm16 {

    /** [samples] 앞 [count] 개를 [out] 에 LE 로 쓴다. @return 쓴 바이트 수 */
    fun encodeLe(samples: ShortArray, out: ByteArray, count: Int = samples.size): Int {
        require(count in 0..samples.size) { "count=$count, size=${samples.size}" }
        val bytes = count * Constants.Audio.BYTES_PER_SAMPLE
        require(out.size >= bytes) { "out too small: ${out.size} < $bytes" }
        for (i in 0 until count) {
            val s = samples[i].toInt()
            out[2 * i] = (s and 0xFF).toByte()
            out[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    /** LE 바이트 [length] 개(짝수)를 샘플로. */
    fun decodeLe(bytes: ByteArray, length: Int = bytes.size): ShortArray {
        require(length in 0..bytes.size && length % 2 == 0) { "length=$length, size=${bytes.size}" }
        return ShortArray(length / 2) { i ->
            ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
        }
    }

    /** 바이트 수 → 재생 시간(ms). */
    fun bytesToMs(bytes: Long): Long =
        bytes * 1000 / (Constants.Audio.SAMPLE_RATE_HZ * Constants.Audio.BYTES_PER_SAMPLE)

    /** 재생 시간(ms) → 바이트 수 (샘플 경계로 맞춤). */
    fun msToBytes(ms: Int): Int =
        Constants.Audio.SAMPLE_RATE_HZ * ms / 1000 * Constants.Audio.BYTES_PER_SAMPLE
}
