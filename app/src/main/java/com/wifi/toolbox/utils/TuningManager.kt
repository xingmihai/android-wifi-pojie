package com.wifi.toolbox.utils

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.wifi.toolbox.structs.DeviceTuning

/**
 * 机型特调参数的持久化。
 *
 * 首次启动（或用户点了「恢复识别结果」）直接用自动识别的配置，
 * 不写库；用户手动改过才落盘，避免 ROM 升级后旧值一直覆盖新识别结果。
 */
class TuningManager(context: Context) {

    private val prefs =
        context.getSharedPreferences(DeviceTuning.PREFS_NAME, Context.MODE_PRIVATE)

    var current by mutableStateOf(load())
        private set

    val isCustomized: Boolean
        get() = prefs.contains(DeviceTuning.KEY_PROFILE)

    private fun load(): DeviceTuning {
        val detected = DeviceTuning.detect()
        if (!prefs.contains(DeviceTuning.KEY_PROFILE)) return detected

        val storedId = prefs.getString(DeviceTuning.KEY_PROFILE, detected.profileId)
            ?: detected.profileId
        val base = if (storedId == DeviceTuning.PROFILE_COLOROS) {
            DeviceTuning.COLOROS
        } else {
            DeviceTuning.GENERIC
        }

        return DeviceTuning(
            profileId = storedId,
            logcatTags = prefs.getString(DeviceTuning.KEY_TAGS, base.logcatTags)
                ?: base.logcatTags,
            earlyRejectEnabled = prefs.getBoolean(
                DeviceTuning.KEY_EARLY_REJECT,
                base.earlyRejectEnabled
            ),
            earlyRejectWindowMs = prefs.getInt(
                DeviceTuning.KEY_EARLY_REJECT_WINDOW,
                base.earlyRejectWindowMs
            ),
            skipDisconnectBetweenTries = prefs.getBoolean(
                DeviceTuning.KEY_SKIP_DISCONNECT,
                base.skipDisconnectBetweenTries
            ),
            forceStopPackages = base.forceStopPackages
        )
    }

    fun update(action: (DeviceTuning) -> DeviceTuning) {
        val new = action(current)
        current = new
        prefs.edit {
            putString(DeviceTuning.KEY_PROFILE, new.profileId)
            putString(DeviceTuning.KEY_TAGS, new.logcatTags)
            putBoolean(DeviceTuning.KEY_EARLY_REJECT, new.earlyRejectEnabled)
            putInt(DeviceTuning.KEY_EARLY_REJECT_WINDOW, new.earlyRejectWindowMs)
            putBoolean(DeviceTuning.KEY_SKIP_DISCONNECT, new.skipDisconnectBetweenTries)
        }
    }

    /** 丢弃手动覆盖，回到自动识别结果 */
    fun reset() {
        current = DeviceTuning.detect()
        prefs.edit { clear() }
    }
}
