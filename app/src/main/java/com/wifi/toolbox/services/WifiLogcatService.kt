package com.wifi.toolbox.services

import android.util.Log
import android.content.Context
import com.wifi.toolbox.R
import com.wifi.toolbox.ToolboxApp
import com.wifi.toolbox.structs.PojieSettings
import com.wifi.toolbox.structs.WifiLogData
import com.wifi.toolbox.utils.AidlServiceHelper
import com.wifi.toolbox.utils.CommandRunner
import com.wifi.toolbox.utils.ShizukuUtil
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.function.Consumer

class WifiLogcatService(
    private val context: Context, private val pojieSettings: PojieSettings
) : AutoCloseable {
    var stopFunc: Runnable? = null

    private val _logFlow = MutableSharedFlow<WifiLogData>(extraBufferCapacity = 64)
    val logFlow = _logFlow.asSharedFlow()

    private var currentSsid: String? = null
    private var connectStartTime: Long = 0L
    private var handshakeStartTime: Long = 0L
    private var handshakeCount: Int = 0

    override fun close() {
        stopFunc?.run()
        stopFunc = null
    }

    private fun decodeHexSsid(hexSsid: String): String {
        if (!hexSsid.contains("\\x")) return hexSsid
        return try {
            val bais = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < hexSsid.length) {
                if (hexSsid[i] == '\\' && i + 3 < hexSsid.length && hexSsid[i + 1] == 'x') {
                    val hex = hexSsid.substring(i + 2, i + 4)
                    bais.write(hex.toInt(16))
                    i += 4
                } else {
                    bais.write(hexSsid[i].code)
                    i++
                }
            }
            bais.toString("UTF-8")
        } catch (_: Exception) {
            hexSsid
        }
    }

    private fun executeCommand(
        command: String,
        onOutputReceived: Consumer<String>?,
        onCommandFinished: Consumer<CommandRunner.CommandResult>?
    ): Runnable {
        val app = context.applicationContext as ToolboxApp
        return when (val method = pojieSettings.commandMethod) {
            0 -> throw Exception(context.getString(R.string.command_method_empty))
            1 -> ShizukuUtil.executeCommand(command, onOutputReceived, onCommandFinished)
            2 -> AidlServiceHelper.executeCommand(app, command, onOutputReceived, onCommandFinished)
            3 -> CommandRunner.executeCommand(command, true, onOutputReceived, onCommandFinished)
            else -> throw Exception(context.getString(R.string.tip_not_completed) + "(commandMethod=$method)")
        }
    }

    fun executeCommandSync(command: String): CommandRunner.CommandResult {
        val app = context.applicationContext as ToolboxApp
        return when (val method = pojieSettings.commandMethod) {
            0 -> throw Exception(context.getString(R.string.command_method_empty))
            1 -> ShizukuUtil.executeCommandSync(command)
            2 -> AidlServiceHelper.executeCommandSync(app, command)
            3 -> CommandRunner.executeCommandSync(command, true)
            else -> throw Exception(context.getString(R.string.tip_not_completed) + "(commandMethod=$method)")
        }
    }

    init {
        executeCommandSync("logcat -c")
        stopFunc = executeCommand(
            command = "logcat -s WifiService:D wpa_supplicant:D DhcpClient:D",
            onOutputReceived = onOutputReceived@ { line ->
                // 性能说明两点：
                // 1) 原先每一行日志都要 Log.d 回写 logd，破解时 wpa_supplicant 每秒能吐出上百行，
                //    这些回写纯属debug残留，改为仅在 debug 包保留；
                // 2) 先做一次廉价粗筛，绝大多数行与结果判定无关，避免每行都跑满 6 次 contains。
                if (com.wifi.toolbox.BuildConfig.DEBUG) Log.d("WifiLogcatService", line)
                if (!isInterestingLine(line)) return@onOutputReceived
                when {
                    line.contains("Trying to associate with SSID") -> {
                        val match = Regex("SSID '(.*?)'").find(line)
                        if (match != null) {
                            currentSsid = decodeHexSsid(match.groupValues[1])
                            connectStartTime = System.currentTimeMillis()
                            handshakeStartTime = 0L
                            handshakeCount = 0
                        }
                    }

                    line.contains("WifiService: enableNetwork") -> {
                        connectStartTime = System.currentTimeMillis()
                        handshakeStartTime = 0L
                        handshakeCount = 0
                    }

                    line.contains("WPA: RX message 1 of 4-Way Handshake from") -> {
                        if (handshakeStartTime == 0L) handshakeStartTime =
                            System.currentTimeMillis()
                    }

                    line.contains("WPA: Key negotiation completed with") -> {
                        Log.d("WifiLogcatService", "连接成功")
                        _logFlow.tryEmit(
                            WifiLogData(
                                WifiLogData.EVENT_WIFI_CONNECTED, connectStartTime, currentSsid
                            )
                        )
                    }

                    line.contains("Sending EAPOL-Key 2/4") -> {
                        handshakeCount++
                        val useTime =
                            if (handshakeStartTime > 0L) (System.currentTimeMillis() - handshakeStartTime).toInt() else 0
                        _logFlow.tryEmit(
                            WifiLogData(
                                WifiLogData.EVENT_HANDSHAKE,
                                connectStartTime,
                                currentSsid,
                                useTime,
                                handshakeCount
                            )
                        )
                    }

                    line.contains("WPA: 4-Way Handshake failed") -> {
                        _logFlow.tryEmit(
                            WifiLogData(
                                WifiLogData.EVENT_CONNECT_FAILED, connectStartTime, currentSsid
                            )
                        )
                    }

                    line.contains("CTRL-EVENT-ASSOC-REJECT") -> {
                        _logFlow.tryEmit(
                            WifiLogData(
                                WifiLogData.EVENT_CONNECT_ERROR, connectStartTime, currentSsid
                            )
                        )
                    }
                }
            },
            onCommandFinished = {
                //注：这里正常不应被执行
            })
    }

    /**
     * 粗筛：只放行可能命中判定的日志行。
     * 命中关键词与下方 when 分支一一对应，新增分支时记得同步这里。
     */
    private fun isInterestingLine(line: String): Boolean {
        return line.contains("WPA:")
                || line.contains("WifiService")
                || line.contains("Trying to associate")
                || line.contains("EAPOL-Key")
                || line.contains("CTRL-EVENT-ASSOC-REJECT")
    }

}