package com.wifi.toolbox.structs

data class WifiLogData(
    val event: Int,
    val eventStartTime: Long,
    val ssid: String?,
    val handshakeUseTime: Int = 0,
    val handshakeCount: Int = 0
) {
    companion object {
        const val EVENT_WIFI_CONNECTED = 0
        const val EVENT_CONNECT_FAILED = 1
        const val EVENT_HANDSHAKE = 2
        const val EVENT_CONNECT_ERROR = 3

        /** 首次握手后超过观察窗仍无结果（由「提前判负」逻辑合成，非 logcat 原文） */
        const val EVENT_HANDSHAKE_STALE = 4
    }
}