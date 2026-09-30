package com.watchbabymonitor.common

import android.content.Context
import com.watchbabymonitor.shared.DetectionConfig
import com.watchbabymonitor.shared.DetectionPreset
import com.watchbabymonitor.shared.WbmJson
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
    private const val KEY_PRESET = "preset" // Phase 3.5~5, 읽기만 (config 로 이전)
    private const val KEY_CONFIG = "config"
    private const val KEY_AUTO_LISTEN = "auto_listen"
    private const val KEY_ALLOW_REMOTE_LIVE = "allow_remote_live"

    @Volatile
    private var roleFlow: MutableStateFlow<Role>? = null

    @Volatile
    private var configFlow: MutableStateFlow<DetectionConfig>? = null

    @Volatile
    private var receiverFlow: MutableStateFlow<ReceiverPrefs>? = null

    fun role(context: Context): StateFlow<Role> = roleState(context)

    fun current(context: Context): Role = roleState(context).value

    fun setRole(context: Context, role: Role) {
        prefs(context).edit().putString(KEY_ROLE, role.name).apply()
        roleState(context).value = role
    }

    /** 감지 설정 (프리셋 + 임계값·쿨다운 조정, Phase 6). 이 기기가 감지기일 때 쓰인다. */
    fun config(context: Context): StateFlow<DetectionConfig> = configState(context)

    fun setConfig(context: Context, config: DetectionConfig) {
        if (configState(context).value == config) return
        prefs(context).edit().putString(KEY_CONFIG, WbmJson.encodeToString(DetectionConfig.serializer(), config)).apply()
        configState(context).value = config
    }

    /** 수신기 설정 (Phase 6). */
    fun receiverPrefs(context: Context): StateFlow<ReceiverPrefs> = receiverState(context)

    fun setReceiverPrefs(context: Context, prefs: ReceiverPrefs) {
        prefs(context).edit()
            .putBoolean(KEY_AUTO_LISTEN, prefs.autoListen)
            .putBoolean(KEY_ALLOW_REMOTE_LIVE, prefs.allowRemoteLive)
            .apply()
        receiverState(context).value = prefs
    }

    private fun receiverState(context: Context): MutableStateFlow<ReceiverPrefs> =
        receiverFlow ?: synchronized(this) {
            receiverFlow ?: MutableStateFlow(
                ReceiverPrefs(
                    autoListen = prefs(context).getBoolean(KEY_AUTO_LISTEN, false),
                    allowRemoteLive = prefs(context).getBoolean(KEY_ALLOW_REMOTE_LIVE, false),
                ),
            ).also { receiverFlow = it }
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

    private fun configState(context: Context): MutableStateFlow<DetectionConfig> =
        configFlow ?: synchronized(this) {
            configFlow ?: MutableStateFlow(loadConfig(context)).also { configFlow = it }
        }

    private fun loadConfig(context: Context): DetectionConfig {
        val p = prefs(context)
        p.getString(KEY_CONFIG, null)?.let { json ->
            runCatching { WbmJson.decodeFromString(DetectionConfig.serializer(), json) }.getOrNull()
                ?.let { return it.clamped() }
        }
        // 예전 버전은 프리셋 이름만 저장했다
        val preset = p.getString(KEY_PRESET, null)
            ?.let { runCatching { DetectionPreset.valueOf(it) }.getOrNull() }
            ?: DetectionPreset.DEFAULT
        return DetectionConfig.of(preset)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * 수신기 설정 (Phase 6 설정 화면).
 * @property autoListen 알림이 오면 라이브 듣기 자동 시작. 앱이 화면에 있을 때만 바로 시작되고,
 *   아니면 알림의 "듣기" 버튼 (백그라운드 재생 FGS 시작 제한)
 * @property allowRemoteLive 원격(클라우드) 연결에서도 라이브 듣기. 아이 소리가 구글 서버를 거치므로 기본 꺼짐 (CLAUDE.md §8)
 */
data class ReceiverPrefs(val autoListen: Boolean = false, val allowRemoteLive: Boolean = false)
