package com.watchbabymonitor.common.notification

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.watchbabymonitor.shared.Constants
import com.watchbabymonitor.shared.NoiseAlert

private val TAG = Constants.logTag("WatchAlert")

/**
 * 워치가 수신기일 때의 알림: 진동 우선 + 화면 켜기 (CLAUDE.md §4-7, §8 "워치 스피커는 작음").
 * 진동(알람 용도)과 알림(레벨·시각)은 폰과 같은 [NoiseNotifications] 가 한다.
 */
object WatchAlert {
    private const val SCREEN_ON_MS = 5_000L

    fun show(context: Context, alert: NoiseAlert) {
        wakeScreen(context)
        NoiseNotifications.show(context, alert)
        Log.i(TAG, "vibrated for alert level=${alert.level}")
    }

    /** 화면을 잠깐 켜서 알림 내용(레벨)을 바로 보이게. */
    @Suppress("DEPRECATION") // 화면을 켜는 공개 API 는 이 플래그뿐
    private fun wakeScreen(context: Context) {
        val pm = context.getSystemService(PowerManager::class.java)
        pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "WBM:AlertScreen",
        ).acquire(SCREEN_ON_MS)
    }
}
