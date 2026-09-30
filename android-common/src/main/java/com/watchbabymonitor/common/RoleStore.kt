package com.watchbabymonitor.common

import android.content.Context
import com.watchbabymonitor.shared.DetectionPreset
import com.watchbabymonitor.shared.Role
import com.watchbabymonitor.shared.engine.DeviceKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 이 기기의 설정 (역할, 감지 프리셋). SharedPreferences 에 저장, StateFlow 로 공개.
 * 처음 실행이면 시나리오 A 기본값 (워치 = 감지기, 폰 = 수신기).
 */
object RoleStore {
    private const val PREFS = "wbm_settings"
    private const val KEY_ROLE = "role"
    private const val KEY_PRESET = "preset"

    @Volatile
    private var roleFlow: MutableStateFlow<Role>? = null

    @Volatile
    private var presetFlow: MutableStateFlow<DetectionPreset>? = null

    fun role(context: Context): StateFlow<Role> = roleState(context)

    fun current(context: Context): Role = roleState(context).value

    fun setRole(context: Context, role: Role) {
        prefs(context).edit().putString(KEY_ROLE, role.name).apply()
        roleState(context).value = role
    }

    fun preset(context: Context): StateFlow<DetectionPreset> = presetState(context)

    fun setPreset(context: Context, preset: DetectionPreset) {
        prefs(context).edit().putString(KEY_PRESET, preset.name).apply()
        presetState(context).value = preset
    }

    fun defaultRole(context: Context): Role = when (DeviceInfo.kind(context)) {
        DeviceKind.WATCH -> Role.DEFAULT_WATCH
        DeviceKind.PHONE -> Role.DEFAULT_PHONE
    }

    private fun roleState(context: Context): MutableStateFlow<Role> =
        roleFlow ?: synchronized(this) {
            roleFlow ?: MutableStateFlow(
                prefs(context).getString(KEY_ROLE, null)
                    ?.let { runCatching { Role.valueOf(it) }.getOrNull() }
                    ?: defaultRole(context),
            ).also { roleFlow = it }
        }

    private fun presetState(context: Context): MutableStateFlow<DetectionPreset> =
        presetFlow ?: synchronized(this) {
            presetFlow ?: MutableStateFlow(
                prefs(context).getString(KEY_PRESET, null)
                    ?.let { runCatching { DetectionPreset.valueOf(it) }.getOrNull() }
                    ?: DetectionPreset.DEFAULT,
            ).also { presetFlow = it }
        }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
