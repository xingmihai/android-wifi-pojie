package com.wifi.toolbox.services.pojie

import android.content.Context
import androidx.compose.runtime.snapshotFlow
import com.wifi.toolbox.R
import com.wifi.toolbox.ToolboxApp
import com.wifi.toolbox.services.PojieService
import com.wifi.toolbox.structs.*
import com.wifi.toolbox.utils.AidlServiceHelper
import com.wifi.toolbox.utils.ShizukuUtil
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.scan
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.pow

/**
 * 负责管理全部任务排队运行
 */
class PojieTaskManager(
    private val service: PojieService,
    private val scope: CoroutineScope,
    private val worker: ConnectWorker
) {

    private var mainLoopJob: Job? = null
    private var currentWorkerJob: Job? = null
    private var settingsMonitorJob: Job? = null

    @Volatile
    private var currentWorkingSsid: String? = null
    private val handledSsids = mutableSetOf<String>()

    /** 已写入过全量历史（含密码表）的 ssid，之后只更新进度 */
    private val historyEnsured = mutableSetOf<String>()
    private val lastHistoryWriteAt = mutableMapOf<String, Long>()

    /**
     * 弹窗压制：需要杀一次「设置」时为 true，由保镖循环消费。
     * 原实现每 250ms 无条件执行一次 am force-stop（每次都要 fork 进程并同步等待返回），
     * 相当于每秒 4 次进程创建常驻在 Default 线程池上，和真正的连接任务抢线程。
     */
    private val pendingDialogSuppression = AtomicBoolean(false)

    @Volatile
    private var lastSuppressionKillTime = 0L

    companion object {
        private const val SUPPRESS_CMD = "am force-stop com.android.settings"
        private const val SUPPRESS_POLL_INTERVAL = 300L
        private const val SUPPRESS_MIN_INTERVAL = 2000L

        /**
         * 进度写库的最小间隔。
         * Room 每次写 history_metadata 都会让 historyFlow 重跑一次「全表 + 密码表 JOIN」，
         * 破解时进度每秒变好几次，不做节流就等于每尝试一个密码都重查几千行。
         */
        private const val HISTORY_WRITE_MIN_INTERVAL = 3000L
    }

    fun isWorking(): Boolean = mainLoopJob != null && mainLoopJob!!.isActive

    fun cancelCurrentJob() {
        settingsMonitorJob?.cancel()
        currentWorkerJob?.cancel()
        mainLoopJob?.cancel()
    }

    fun startLoop(settings: PojieSettings) {
        val app = service.applicationContext as ToolboxApp
        val safeScope = scope + SupervisorJob()

        settingsMonitorJob = safeScope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    if (settings.connectMode != 4 && pendingDialogSuppression.getAndSet(false)) {
                        val now = System.currentTimeMillis()
                        if (now - lastSuppressionKillTime >= SUPPRESS_MIN_INTERVAL) {
                            lastSuppressionKillTime = now
                            runCatching {
                                if (AidlServiceHelper.executeCommandSync(
                                        app,
                                        SUPPRESS_CMD
                                    ).exitCode != 0
                                ) {
                                    ShizukuUtil.executeCommandSync(SUPPRESS_CMD)
                                }
                            }
                        }
                    }
                    delay(SUPPRESS_POLL_INTERVAL)
                }
            } finally {
            }
        }

        mainLoopJob = scope.launch {
            launch {
                snapshotFlow { app.runningPojieTasks.toList() }
                    .scan(listOf<PojieRunInfo>()) { old, new ->
                        val removed = old.map { it.ssid }.toSet() - new.map { it.ssid }.toSet()
                        removed.forEach {
                            if (!handledSsids.remove(it)) {
                                if (worker.forgetNetwork(settings, it)) {
                                    service.log(service.getString(R.string.forget_network_log, it))
                                } else {
                                    service.log(
                                        service.getString(
                                            R.string.forget_network_failed,
                                            it
                                        )
                                    )
                                }
                            }
                        }

                        val targetSsid = currentWorkingSsid
                        val isCurrentTaskRemoved =
                            targetSsid != null && new.none { it.ssid == targetSsid }
                        if (isCurrentTaskRemoved || new.isEmpty() || new.size > old.size) {
                            currentWorkerJob?.cancel()
                        }
                        new
                    }
                    .collect()
            }

            while (isActive) {
                try {
                    if (app.runningPojieTasks.isEmpty()) {
                        service.stop()
                        break
                    }
                    val task = findNextReadyTask(app)
                    if (task == null) {
                        handleCooldown(app)
                        continue
                    }
                    executeTaskAttempt(app, task, settings)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    service.log(service.getString(R.string.error_string, e.message ?: ""))
                    delay(100)
                }
            }
        }
    }

    private suspend fun handleCooldown(app: ToolboxApp): Boolean {
        val now = System.currentTimeMillis()
        val tasks = app.runningPojieTasks
        if (tasks.isEmpty()) return false

        val nextAvailableTime = tasks.minOf {
            val waitTime = if (it.retryCount > 0) {
                (2.0.pow(it.retryCount.toDouble() - 1).toLong()) * app.pojieConfig.doublingBase
            } else 0L
            it.lastTryTime + waitTime
        }

        val waitMs = maxOf(0L, nextAvailableTime - now)
        if (waitMs > 0) {
            val cooldownJob = scope.launch {
                service.log(service.getString(R.string.cooldown_log, waitMs))
                PojieNotification.update(
                    context = service,
                    contentText = service.getString(R.string.cooldown_status),
                    subText = ""
                )
                delay(waitMs)
            }
            currentWorkerJob = cooldownJob
            try {
                cooldownJob.join()
            } catch (_: CancellationException) {
                return false
            } finally {
                currentWorkerJob = null
            }
        }
        return true
    }

    private fun findNextReadyTask(app: ToolboxApp): PojieRunInfo? {
        val now = System.currentTimeMillis()
        val config = app.pojieConfig
        return app.runningPojieTasks.filter {
            val waitTime = if (it.retryCount > 0) {
                (2.0.pow(it.retryCount.toDouble() - 1).toLong()) * config.doublingBase
            } else 0L
            now - it.lastTryTime >= waitTime
        }.minByOrNull { -(System.currentTimeMillis() - it.lastTryTime) }
    }

    private suspend fun executeTaskAttempt(
        app: ToolboxApp,
        task: PojieRunInfo,
        settings: PojieSettings
    ) {
        // 仅在提示文案真正变化时才写状态：每次写入都会触发 snapshot 变更 -> snapshotFlow -> 任务列表重组
        app.runningPojieTasks.forEach {
            val newTip = service.getString(if (it.ssid == task.ssid) R.string.task_attempting else R.string.task_queuing)
            if (it.textTip != newTip) {
                app.pojieTask.edit(it.ssid) { state ->
                    state.copy(textTip = newTip)
                }
            }
        }

        pendingDialogSuppression.set(true)

        currentWorkingSsid = task.ssid
        val currentPass =
            task.tryList.getOrNull(task.tryIndex) ?: service.getString(R.string.unknown)

        app.pojieTask.edit(task.ssid) {
            it.copy(
                textTip = service.getString(R.string.task_attempting_with_pass, currentPass),
                lastTryTime = System.currentTimeMillis()
            )
        }

        service.log(
            service.getString(
                R.string.task_log_format,
                worker.getLogTime(),
                task.ssid,
                currentPass
            ), true
        )

        val totalRemainingTime = app.runningPojieTasks
            .map {
                it.let { info ->
                    PojieRunInfo.calculateAverageSpeed(info)
                        ?.times(info.tryList.size - info.tryIndex)
                }
            }
            .let { list -> if (list.any { it == null }) null else list.filterNotNull().sum() }

        PojieNotification.update(
            context = service,
            contentText = service.getString(R.string.notif_attempting, task.ssid, currentPass),
            subText = formatDuration(app, totalRemainingTime)
        )

        val startTime = System.currentTimeMillis()
        val deferredResult = scope.async(Dispatchers.Default) {
            try {
                worker.performTaskLogic(app, SinglePojieTask(task.ssid, currentPass), settings)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                service.log(service.getString(R.string.error_string, e.message ?: ""))
                SinglePojieTask.RESULT_ERROR
            }
        }

        currentWorkerJob = deferredResult
        var taskResult: Int
        try {
            taskResult = deferredResult.await()
        } catch (_: CancellationException) {
            taskResult = SinglePojieTask.RESULT_CANCEL
            deferredResult.cancel()
        } catch (_: Exception) {
            taskResult = SinglePojieTask.RESULT_ERROR
        }

        val duration = System.currentTimeMillis() - startTime
        handleAttemptResult(app, task, currentPass, taskResult, settings, duration)
        currentWorkingSsid = null
        currentWorkerJob = null
    }

    fun formatDuration(context: Context, ms: Long?): String {
        if (ms == null) {
            return context.getString(R.string.calculating_time)
        }
        val totalMinutes = ms / 60000
        if (totalMinutes < 1) return context.getString(R.string.time_unit_less_than_minute)

        val days = totalMinutes / (24 * 60)
        val hours = (totalMinutes % (24 * 60)) / 60
        val minutes = totalMinutes % 60

        val result = mutableListOf<String>()
        if (days > 0) result.add(context.getString(R.string.time_unit_day, days))
        if (hours > 0 || days > 0) result.add(context.getString(R.string.time_unit_hour, hours))
        result.add(context.getString(R.string.time_unit_minute, minutes))

        return context.getString(R.string.remaining_time_format, result.joinToString(" "))
    }

    private fun handleAttemptResult(
        app: ToolboxApp,
        task: PojieRunInfo,
        pass: String,
        result: Int,
        settings: PojieSettings,
        duration: Long
    ) {
        val timeTag = worker.getLogTime()
        var resultStr = service.getString(
            when (result) {
                SinglePojieTask.RESULT_CANCEL -> R.string.result_interrupted
                SinglePojieTask.RESULT_SUCCESS -> R.string.result_success
                SinglePojieTask.RESULT_FAILED -> R.string.result_failed
                SinglePojieTask.RESULT_TIMEOUT -> R.string.result_timeout
                SinglePojieTask.RESULT_ERROR_TRANSIENT -> R.string.result_rejected
                else -> R.string.result_error
            }
        )
        if (result == SinglePojieTask.RESULT_FAILED) resultStr += app.getString(
            R.string.fail_time,
            duration
        )

        app.logState.setLine(
            service.getString(
                R.string.result_log_format,
                timeTag,
                task.ssid,
                pass,
                resultStr
            )
        )

        when (result) {
            SinglePojieTask.RESULT_CANCEL -> app.finishedPojieTasksTip[task.ssid] =
                service.getString(R.string.tip_interrupted, task.tryIndex)

            SinglePojieTask.RESULT_SUCCESS -> app.finishedPojieTasksTip[task.ssid] =
                service.getString(R.string.tip_success, pass)

            else -> {
                if (result != SinglePojieTask.RESULT_FAILED && result != SinglePojieTask.RESULT_TIMEOUT && result != SinglePojieTask.RESULT_ERROR_TRANSIENT) {
                    app.finishedPojieTasksTip[task.ssid] =
                        service.getString(R.string.tip_error_view_output)
                }
            }
        }

        worker.cleanConnection(settings)

        if (result == SinglePojieTask.RESULT_CANCEL) return

        // 密码错误后部分 ROM（如小米）会弹出提示窗，事后补杀一次
        if (result != SinglePojieTask.RESULT_SUCCESS) pendingDialogSuppression.set(true)

        when (result) {
            SinglePojieTask.RESULT_SUCCESS -> {
                service.log("${service.getString(R.string.result_success)}: (${task.ssid}, $pass)")
                app.pojieHistory.addOrUpdateHistory(
                    PojieHistoryItem(
                        task.ssid,
                        task.tryList,
                        task.tryIndex,
                        pass
                    )
                )
                handledSsids.add(task.ssid)
                // 任务已结束，下次若用新字典需重新写入全量
                historyEnsured.remove(task.ssid)
                app.pojieTask.stop(task.ssid)
            }

            SinglePojieTask.RESULT_FAILED -> {
                processTaskCompletion(app, task.ssid)
                app.pojieTask.edit(task.ssid) {
                    val newList = it.costList.toMutableList().apply {
                        add(duration)
                        if (size > 20) removeAt(0)
                    }
                    it.copy(
                        costList = newList,
                        retryCount = 0
                    )
                }
            }

            SinglePojieTask.RESULT_TIMEOUT, SinglePojieTask.RESULT_ERROR_TRANSIENT -> {
                app.pojieTask.edit(task.ssid) {
                    val newList = it.costList.toMutableList()
                    if (newList.isNotEmpty()) {
                        newList[newList.size - 1] += duration
                    }
                    it.copy(
                        costList = newList,
                        retryCount = it.retryCount + 1
                    )
                }
                getTask(app, task.ssid)?.let { updated ->
                    if (app.pojieConfig.retryCountType <= 5 && updated.retryCount > app.pojieConfig.retryCountType) {
                        app.pojieTask.edit(task.ssid) { it.copy(retryCount = 0) }
                        processTaskCompletion(app, task.ssid)
                    }
                }
            }

            else -> app.pojieTask.stop(task.ssid)
        }

        buildHistoryWrite(app, task.ssid)?.let { write ->
            scope.launch(Dispatchers.IO) { write() }
        }
    }

    private fun processTaskCompletion(app: ToolboxApp, ssid: String) {
        val task = getTask(app, ssid) ?: return
        val nextIndex = task.tryIndex + 1
        app.pojieTask.edit(ssid) { it.copy(tryIndex = nextIndex) }
        if (nextIndex >= task.tryList.size) {
            app.finishedPojieTasksTip[ssid] =
                service.getString(R.string.tip_all_failed, task.tryList.size)
            historyEnsured.remove(ssid)
            // 收尾时补一次全量写，避免被节流跳过的最后一次进度丢失
            app.pojieHistory.addOrUpdateHistory(
                PojieHistoryItem(task.ssid, task.tryList, nextIndex)
            )
            app.pojieTask.stop(ssid)
        }
    }

    private fun getTask(app: ToolboxApp, ssid: String): PojieRunInfo? =
        app.runningPojieTasks.find { it.ssid == ssid }

    /**
     * 构造一次历史写入动作；无需写入时返回 null。
     *
     * 全部共享状态只在这里（调度协程内）访问，DB 调用再丢到 IO 线程执行，避免加锁。
     */
    private fun buildHistoryWrite(app: ToolboxApp, ssid: String): (() -> Unit)? {
        val task = getTask(app, ssid) ?: return null
        if (historyEnsured.add(task.ssid)) {
            // 首次：连整份密码表一起写入，之后只更新进度
            val item = PojieHistoryItem(task.ssid, task.tryList, task.tryIndex)
            lastHistoryWriteAt[task.ssid] = System.currentTimeMillis()
            return { app.pojieHistory.addOrUpdateHistory(item) }
        }
        val now = System.currentTimeMillis()
        if (now - (lastHistoryWriteAt[task.ssid] ?: 0L) < HISTORY_WRITE_MIN_INTERVAL) return null
        lastHistoryWriteAt[task.ssid] = now
        val progress = task.tryIndex
        return { app.pojieHistory.updateProgressOnly(task.ssid, progress) }
    }
}