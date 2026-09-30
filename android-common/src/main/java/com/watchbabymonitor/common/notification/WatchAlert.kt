package com.watchbabymonitor.common.notification

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.watchbabymonitor.shared.Constants
import com.watchbabymonitor.shared.NoiseAlert

private val TAG = Constants.logTag("WatchAlert")

/**
 * 워치가 수신기일 때의 알림: 진동 우선 + 화면 켜기 (CLAUDE.md §4-7, §8 "워치 스피커는 작음").
 * 알림(레벨·시각)은 [NoiseNotifications] 가 함께 띄운다.
 */
object WatchAlert {
    /** 길게-짧게 세 번. 일반 알림 진동과 구분되게. */
    private val PATTERN = longArrayOf(0, 700, 200, 700, 200, 700)

    private const val SCREEN_ON_MS = 5_000L

    fun show(context: Context, alert: NoiseAlert) {
        vibrate(context)
        wakeScreen(context)
        NoiseNotifications.show(context, alert)
        Log.i(TAG, "vibrated for alert level=${alert.level}")
    }

    private fun vibrate(context: Context) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        val effect = VibrationEffect.createWaveform(PATTERN, -1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 알람 용도로 → 방해 금지·무음 설정에서도 가능한 한 울리도록
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            vibrator.vibrate(effect)
        }
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
