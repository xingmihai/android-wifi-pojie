package com.wifi.toolbox.utils

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class LogState {
    val logs = mutableStateListOf<String>()
    var wordWrap by mutableStateOf(false)
    var autoScroll by mutableStateOf(true)
    var lastAllowEditIndex by mutableIntStateOf(-1)

    fun addLog(log: String, allowEdit: Boolean = false) {
        // 破解过程中日志高频写入，release 包不再回写 logd
        if (com.wifi.toolbox.BuildConfig.DEBUG) Log.d("Log", "addLog:$log")
        if (allowEdit) lastAllowEditIndex = logs.size
        if (logs.size >= 300) {
            logs.removeAt(0)
            if (lastAllowEditIndex != -1) lastAllowEditIndex--
        }
        logs.add(log)
    }

    fun clear() {
        lastAllowEditIndex = -1
        logs.clear()
    }

    fun setLine(log: String) {
        if (com.wifi.toolbox.BuildConfig.DEBUG) Log.d("Log", "setLine:$log")
        if (lastAllowEditIndex != -1) {
            logs[lastAllowEditIndex] = log
        } else {
            addLog(log)
        }
    }
}