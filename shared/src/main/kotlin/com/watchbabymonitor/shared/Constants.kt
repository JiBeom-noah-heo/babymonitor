package com.watchbabymonitor.shared

/**
 * 워치·폰 공용 상수. 경로, 샘플레이트, 임계값 기본값은 전부 여기에 둔다.
 * (Data Layer 경로는 Phase 1 에서 추가)
 */
object Constants {
    const val APP_NAME = "WatchBabyMonitor"

    /** 로그 태그 접두사. 실제 태그는 `WBM/<클래스명>`. */
    const val LOG_TAG_PREFIX = "WBM"

    fun logTag(className: String): String = "$LOG_TAG_PREFIX/$className"
}
