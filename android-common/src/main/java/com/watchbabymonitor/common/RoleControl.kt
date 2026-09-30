package com.watchbabymonitor.common

import android.content.Context
import android.util.Log
import com.watchbabymonitor.common.service.ListenerService
import com.watchbabymonitor.common.service.MonitorService
import com.watchbabymonitor.shared.Constants
import com.watchbabymonitor.shared.DetectionPreset
import com.watchbabymonitor.shared.Role

private val TAG = Constants.logTag("RoleControl")

/** 역할·프리셋 변경. 화면에서 호출. */
object RoleControl {

    /** 역할을 바꾸기 전에 이전 역할의 서비스(모니터링 / 라이브 듣기)를 멈춘다. */
    fun switchRole(context: Context, role: Role) {
        val current = RoleStore.current(context)
        if (current == role) return
        when (current) {
            Role.SENSOR -> MonitorService.stop(context)
            Role.RECEIVER -> ListenerService.stop(context)
        }
        RoleStore.setRole(context, role)
        Log.i(TAG, "role changed $current -> $role")
    }

    fun setPreset(context: Context, preset: DetectionPreset) {
        RoleStore.setPreset(context, preset)
        Engines.sensor.setPreset(preset)
    }
}
