package com.wifi.toolbox.structs

import android.os.Build

/**
 * 机型特调参数。
 *
 * 背景：不同 ROM / Android 版本下，wpa_supplicant 的日志输出、系统弹窗包名、
 * 单次连接耗时都不一样，写死一套参数必然在某些机器上偏慢或误判。
 * 这里把影响「单次尝试耗时」的关键项抽出来，按机型给不同默认值。
 *
 * 全部默认值都可以被用户在「运行设置 - 参数」里覆盖，覆盖后写入 settings_tuning。
 */
data class DeviceTuning(
    val profileId: String = PROFILE_GENERIC,

    /** 传给 `logcat -s` 的 tag 列表 */
    val logcatTags: String = DEFAULT_TAGS,

    /**
     * 提前判负：收到第一次 4 次握手（EAPOL-Key 2/4）后，
     * 若在窗口内没有出现 "Key negotiation completed"，立刻判负换下一个密码。
     *
     * 原理：密码正确时，msg3/msg4 通常在几十毫秒内就走完；密码错误时 AP 不会回 msg3，
     * wpa_supplicant 要等一个重试周期（常见 1s 左右，部分驱动更长）才会再发一次 2/4。
     * 原来必须等第二次 2/4 才判负（maxHandshakeCount=1），白白多等一整个重试周期。
     *
     * 风险：窗口设得过小，可能把「握手慢但密码正确」的情况误杀。
     * 实测建议 400–600ms，弱信号/老旧路由器上调。
     */
    val earlyRejectEnabled: Boolean = false,
    val earlyRejectWindowMs: Int = 500,

    /** 两次尝试之间是否跳过 disconnect（省一次 supplicant IPC），可能残留旧状态 */
    val skipDisconnectBetweenTries: Boolean = false,

    /** 运行时需要压制弹窗的包名（密码错误时部分 ROM 会弹系统提示） */
    val forceStopPackages: List<String> = listOf("com.android.settings")
) {
    companion object {
        const val PROFILE_GENERIC = "generic"
        const val PROFILE_COLOROS = "coloros"

        /**
         * 注：Android 16 起 wpa_supplicant HAL 已迁到 AIDL，但 logcat 里的 tag
         * 依旧是 `wpa_supplicant`，握手相关日志仍然可读，"握手超次"判定依然可用。
         */
        const val DEFAULT_TAGS = "WifiService:D wpa_supplicant:D DhcpClient:D"

        const val PREFS_NAME = "settings_tuning"
        const val KEY_PROFILE = "profile_id"
        const val KEY_TAGS = "logcat_tags"
        const val KEY_EARLY_REJECT = "early_reject"
        const val KEY_EARLY_REJECT_WINDOW = "early_reject_window"
        const val KEY_SKIP_DISCONNECT = "skip_disconnect"

        /** OPPO / 一加 / realme（ColorOS、OxygenOS、realme UI 同源） */
        val COLOROS = DeviceTuning(
            profileId = PROFILE_COLOROS,
            logcatTags = DEFAULT_TAGS,
            earlyRejectEnabled = true,
            earlyRejectWindowMs = 450,
            skipDisconnectBetweenTries = false,
            // ColorOS 部分机型的 WiFi 设置页在独立包内；force-stop 不存在的包不会报错
            forceStopPackages = listOf("com.android.settings", "com.coloros.wirelesssettings")
        )

        val GENERIC = DeviceTuning(profileId = PROFILE_GENERIC)

        fun detect(): DeviceTuning {
            val candidates = listOf(
                Build.MANUFACTURER,
                Build.BRAND,
                Build.PRODUCT,
                Build.DEVICE,
                Build.DISPLAY
            ).map { it.lowercase() }

            val isColorOs = candidates.any {
                it.contains("realme") || it.contains("oppo")
                        || it.contains("oneplus") || it.contains("oplus")
                        || it.contains("coloros")
            }
            return if (isColorOs) COLOROS else GENERIC
        }
    }
}
