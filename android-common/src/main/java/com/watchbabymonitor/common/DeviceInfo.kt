package com.watchbabymonitor.common

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.watchbabymonitor.shared.engine.DeviceKind

object DeviceInfo {
    fun kind(context: Context): DeviceKind =
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)) DeviceKind.WATCH else DeviceKind.PHONE

    /** 앱 이름 (알림 제목용). 라이브러리라 앱 리소스를 직접 참조하지 않는다. */
    fun appLabel(context: Context): String =
        context.applicationInfo.loadLabel(context.packageManager).toString()

    /** 앱 첫 화면을 여는 인텐트. 라이브러리는 각 앱의 MainActivity 클래스를 모른다. */
    fun launchIntent(context: Context, action: String? = null): Intent =
        (context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent())
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { if (action != null) this.action = action }

    /** "탭해서 모니터링 시작" 알림에서 열렸을 때의 액션. 각 앱 MainActivity 가 처리한다. */
    const val ACTION_START_MONITORING = "com.watchbabymonitor.action.START_MONITORING"
}
