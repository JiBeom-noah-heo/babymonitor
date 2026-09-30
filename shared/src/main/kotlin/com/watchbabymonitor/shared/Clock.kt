package com.watchbabymonitor.shared

/**
 * 엔진이 쓰는 시간. 테스트에서 시간을 직접 움직이려고 주입한다.
 */
fun interface Clock {
    /** 밀리초. 비교·간격 계산용 (단조 증가가 보장되는 구현을 쓸 것). */
    fun nowMs(): Long

    companion object {
        /** 단조 증가 시계. 벽시계 변경에 영향받지 않음 → 타임아웃·간격 측정용. */
        val MONOTONIC = Clock { System.nanoTime() / 1_000_000 }

        /** 벽시계 (epoch millis). 알림·상태의 `ts` 처럼 사람이 읽는 시각용. */
        val WALL = Clock { System.currentTimeMillis() }
    }
}
