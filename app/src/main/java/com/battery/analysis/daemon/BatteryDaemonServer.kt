package com.battery.analysis.daemon

import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 特权独立守护进程核心服务（Root / ADB 终极防杀运行时）。
 *
 * 本类脱离 Android 原生应用组件树，由 /system/bin/app_process 直接拉起并托管于 Linux init (PID 1) 进程下。
 * 核心机制：
 * 1. 内核级 OOM 分数置顶：向 /proc/self/oom_score_adj 写入 -1000，免疫系统低内存查杀；
 * 2. 破除现代 Android cgroup freezer 进程冻结：逃逸至根 cgroup 节点；
 * 3. 持久化状态与双向心跳：写入状态至 /data/local/tmp/battery_daemon.status，读取 /data/local/tmp/battery_app.alive；
 * 4. ContentProvider 深度反向穿透拉活：破除 Android 12+ 前台服务后台启动限制，瞬间自愈拉活主应用前台监控服务；
 * 5. 纯粹底层守护：彻底摒弃 Shell 伪装常驻通知，通知完全由主应用规范呈现，守护进程专注于内核物理采样与底层防杀保活。
 */
class BatteryDaemonServer {

    companion object {
        private const val TAG = "BatteryDaemonServer"
        private const val PACKAGE_NAME = "com.battery.analysis"
        private const val SERVICE_COMPONENT = "com.battery.analysis/.service.BatteryMonitorService"
        private const val PROVIDER_URI = "content://com.battery.analysis.daemon.provider/revive"
        
        const val STATUS_FILE_PATH = "/data/local/tmp/battery_daemon.status"
        const val EXTERNAL_STATUS_FILE_PATH = "/sdcard/Android/data/com.battery.analysis/files/battery_daemon.status"
        const val STOP_FILE_PATH = "/data/local/tmp/battery_daemon.stop"
        const val EXTERNAL_STOP_FILE_PATH = "/sdcard/Android/data/com.battery.analysis/files/battery_daemon.stop"
        const val APP_ALIVE_FILE_PATH = "/data/local/tmp/battery_app.alive"
        const val EXTERNAL_ALIVE_FILE_PATH = "/sdcard/Android/data/com.battery.analysis/files/battery_app.alive"
        const val EXTERNAL_SAMPLES_FILE_PATH = "/sdcard/Android/data/com.battery.analysis/files/battery_samples.stream"
        const val APP_MANUAL_STOP_PATH = "/data/local/tmp/battery_app.manual_stop"
        const val EXTERNAL_MANUAL_STOP_PATH = "/sdcard/Android/data/com.battery.analysis/files/battery_app.manual_stop"

        private const val NOTIFICATION_TAG = "battery_daemon_persistent"
        private const val NOTIFICATION_TAG_LEGACY = "battery_daemon_tag"
        private const val NOTIFICATION_ID = 2020
        private const val CHECK_INTERVAL_MS = 1000L
        private const val ALIVE_TIMEOUT_MS = 3000L

        @Volatile
        private var lastKnownBatteryInfo: String = "⚡ 电池监控持续运行中"

        /**
         * 守护进程独立主入口函数，由 app_process 命令行直接调用。
         *
         * @param args 命令行启动参数数组
         */
        @JvmStatic
        fun main(args: Array<String>) {
            logInfo("BatteryDaemonServer starting...")

            // 1. 如果存在退出标记文件，先清理它
            val stopFile = File(STOP_FILE_PATH)
            if (stopFile.exists()) {
                stopFile.delete()
            }
            val extStopFile = File(EXTERNAL_STOP_FILE_PATH)
            if (extStopFile.exists()) {
                extStopFile.delete()
            }

            // 2. 清理可能残留的历史 Shell 常驻通知，保持通知栏纯净
            removeShellNotification()

            // 3. 提升 OOM 分数为 -1000（内核最高免疫级别）
            setOomScoreAdj(-1000)

            // 4. 逃逸 cgroup freezer 进程冻结组
            escapeCgroups()

            // 5. 预赋权共享文件，解除普通应用沙箱限制
            prepareSharedFiles()

            val myPid = getMyProcessId()
            val myUid = getMyUid()
            val startTime = System.currentTimeMillis()
            var reviveCount = 0

            logInfo("Daemon initialized. PID=$myPid, UID=$myUid, StartTime=$startTime")

            // 6. 初始写入状态文件
            updateStatusFile(myPid, myUid, startTime, System.currentTimeMillis(), reviveCount, "RUNNING")

            // 7. 核心守护、硬件物理采样与反向穿透拉活主循环（完全由宿主应用规范展示通知，守护进程不再发送 Shell 伪装通知）
            while (true) {
                try {
                    // 检查是否有外部停止信号文件
                    if (File(STOP_FILE_PATH).exists() || File(EXTERNAL_STOP_FILE_PATH).exists()) {
                        logInfo("Stop signal received. Cleaning up and exiting.")
                        File(STOP_FILE_PATH).delete()
                        File(EXTERNAL_STOP_FILE_PATH).delete()
                        File(STATUS_FILE_PATH).delete()
                        File(EXTERNAL_STATUS_FILE_PATH).delete()
                        removeShellNotification()
                        System.exit(0)
                        return
                    }

                    // 检查用户是否在主应用设置中主动关闭了监控服务
                    val isManualStopped = File(APP_MANUAL_STOP_PATH).exists() || File(EXTERNAL_MANUAL_STOP_PATH).exists()

                    if (!isManualStopped) {
                        // 1. 特权独立硬件物理采样引擎持续落盘（彻底消灭划杀中断断层，独立于宿主应用耗电模式）
                        recordPhysicalSamplePoint()

                        // 2. 检测主应用监控服务是否处于存活状态
                        val isAppAlive = isHostAlive()
                        if (!isAppAlive) {
                            logInfo("Host service is NOT alive! Triggering instantaneous penetration revive...")
                            val success = reviveService()
                            if (success) {
                                reviveCount++
                                logInfo("Revived successfully! Total count: $reviveCount")
                            } else {
                                logInfo("Revive commands dispatched, waiting for state sync.")
                            }
                        }
                    } else {
                        // 用户主动停止服务时移除残留通知
                        removeShellNotification()
                    }

                    // 更新心跳状态
                    updateStatusFile(myPid, myUid, startTime, System.currentTimeMillis(), reviveCount, "RUNNING")

                    // 休眠指定周期（1 秒高速巡检）
                    Thread.sleep(CHECK_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    logInfo("Daemon loop interrupted: ${e.message}")
                    break
                } catch (e: Throwable) {
                    logInfo("Unexpected error in daemon loop: ${e.message}")
                    try {
                        Thread.sleep(CHECK_INTERVAL_MS)
                    } catch (_: Exception) {}
                }
            }

            // 退出清理
            File(STATUS_FILE_PATH).delete()
            File(EXTERNAL_STATUS_FILE_PATH).delete()
            removeShellNotification()
            logInfo("BatteryDaemonServer terminated.")
        }

        /**
         * 预先在共享路径创建心跳文件并赋予全局可读写权限 (0666)，解决普通应用在 /data/local/tmp 权限受限的问题。
         */
        private fun prepareSharedFiles() {
            try {
                val file = File(APP_ALIVE_FILE_PATH)
                if (!file.exists()) {
                    file.createNewFile()
                }
                file.setReadable(true, false)
                file.setWritable(true, false)
                executeShellCommand("chmod 666 $APP_ALIVE_FILE_PATH")
            } catch (_: Exception) {}
        }

        /**
         * 获取当前最新的电池物理参数显示文本。
         * 优先从宿主应用写入的心跳共享文件中读取；
         * 若心跳文件不存在或超时（如宿主应用正在拉活中），则直接由特权守护进程读取 Linux 内核 sysfs 节点。
         *
         * @return 格式化好的单行电池监控文本（如 -3.8W | 4.05V | 28.5℃）
         */
        private fun getLatestBatteryInfo(): String {
            // 1. 尝试从应用外部私有心跳文件读取
            val extAliveFile = File(EXTERNAL_ALIVE_FILE_PATH)
            val infoFromExt = readInfoFromAliveFile(extAliveFile)
            if (!infoFromExt.isNullOrBlank()) {
                return infoFromExt
            }

            // 2. 尝试从 /data/local/tmp 心跳文件读取
            val tmpAliveFile = File(APP_ALIVE_FILE_PATH)
            val infoFromTmp = readInfoFromAliveFile(tmpAliveFile)
            if (!infoFromTmp.isNullOrBlank()) {
                return infoFromTmp
            }

            // 3. 回退策略：直接读取 Linux 内核 sysfs 电池硬件节点（特权独立直采）
            val infoFromKernel = readKernelBatteryInfo()
            if (!infoFromKernel.isNullOrBlank()) {
                return infoFromKernel
            }

            return lastKnownBatteryInfo
        }

        /**
         * 从指定的心跳文件中解析最新上报的电池参数文本。
         *
         * @param file 目标心跳文件
         * @return 解析得到的电池参数文本，超时或无效返回 null
         */
        private fun readInfoFromAliveFile(file: File): String? {
            if (!file.exists() || !file.canRead()) return null
            return try {
                val content = file.readText(Charsets.UTF_8).trim()
                val parts = content.split(":")
                if (parts.size >= 3) {
                    val timestamp = parts[0].toLongOrNull() ?: 0L
                    val info = parts[2].trim()
                    val diff = System.currentTimeMillis() - timestamp
                    if (diff in 0..ALIVE_TIMEOUT_MS && info.isNotBlank()) {
                        lastKnownBatteryInfo = info
                        return info
                    }
                }
                null
            } catch (_: Exception) {
                null
            }
        }

        /**
         * 直接通过 Linux 内核 sysfs 硬件节点读取当前电池功率、电压与温度。
         * 在宿主进程被划杀、冷启动阶段提供零延迟物理参数兜底。
         *
         * @return 单行电池监控文本，读取失败返回 null
         */
        private fun readKernelBatteryInfo(): String? {
            return try {
                val basePath = "/sys/class/power_supply/battery"
                val currentFile = File(basePath, "current_now")
                val voltageFile = File(basePath, "voltage_now")
                val tempFile = File(basePath, "temp")
                val statusFile = File(basePath, "status")

                if (!currentFile.exists() || !voltageFile.exists()) return null

                val currentMicroA = currentFile.readText().trim().toLongOrNull() ?: return null
                val voltageMicroV = voltageFile.readText().trim().toLongOrNull() ?: return null
                val tempTenthC = if (tempFile.exists()) tempFile.readText().trim().toIntOrNull() ?: 250 else 250
                val statusStr = if (statusFile.exists()) statusFile.readText().trim() else ""
                val isCharging = statusStr.equals("Charging", ignoreCase = true)

                val voltageVolts = voltageMicroV / 1_000_000.0f
                val currentAmps = kotlin.math.abs(currentMicroA) / 1_000_000.0f
                val powerWatts = voltageVolts * currentAmps
                val tempC = tempTenthC / 10.0f

                val powerStr = if (powerWatts > 0.05f) {
                    if (isCharging) {
                        String.format(Locale.getDefault(), "%.1fW", powerWatts)
                    } else {
                        String.format(Locale.getDefault(), "-%.1fW", powerWatts)
                    }
                } else if (isCharging) {
                    "0.0W"
                } else {
                    "--W"
                }
                val voltStr = String.format(Locale.getDefault(), "%.2fV", voltageVolts)
                val tempStr = String.format(Locale.getDefault(), "%.1f℃", tempC)
                val singleLine = "$powerStr | $voltStr | $tempStr"
                lastKnownBatteryInfo = singleLine
                singleLine
            } catch (_: Exception) {
                null
            }
        }

        /**
         * 判定主应用前台监控服务当前是否真实存活。
         * 采用 PID 瞬时探针配合高频心跳时间戳，实现 0 延迟秒级确诊。
         *
         * @return 若主服务在最近超时窗口内保持活跃返回 true，否则返回 false
         */
        private fun isHostAlive(): Boolean {
            for (path in listOf(EXTERNAL_ALIVE_FILE_PATH, APP_ALIVE_FILE_PATH)) {
                val file = File(path)
                if (file.exists() && file.canRead()) {
                    try {
                        val content = file.readText(Charsets.UTF_8).trim()
                        val parts = content.split(":")
                        if (parts.size >= 2) {
                            val timestamp = parts[0].toLongOrNull() ?: 0L
                            val pid = parts[1].toIntOrNull() ?: -1
                            if (parts.size >= 3 && parts[2].isNotBlank()) {
                                lastKnownBatteryInfo = parts[2].trim()
                            }

                            // 瞬时 PID 探针：如果记录的 PID 已从内核进程表中销毁，0 延迟即刻确诊死亡
                            if (pid > 0 && !isPidDirectoryAlive(pid)) {
                                return false
                            }

                            val diff = System.currentTimeMillis() - timestamp
                            if (diff in 0..ALIVE_TIMEOUT_MS) {
                                return true
                            }
                        }
                    } catch (_: Exception) {}
                }
            }

            // 心跳文件不存在或超时，辅助通过 pidof 校验
            return isProcessRunningPidof(PACKAGE_NAME)
        }

        /**
         * 探测指定 Linux PID 目录是否存在以确认进程是否仍然存活。
         *
         * @param pid 目标进程 PID
         * @return 若进程目录存在返回 true，否则返回 false
         */
        private fun isPidDirectoryAlive(pid: Int): Boolean {
            return try {
                File("/proc/$pid").exists()
            } catch (_: Exception) {
                false
            }
        }

        /**
         * 通过 pidof 命令行快速检测包名主进程是否存活。
         *
         * @param targetPackage 目标应用包名
         * @return 若进程存活返回 true，否则返回 false
         */
        private fun isProcessRunningPidof(targetPackage: String): Boolean {
            return try {
                val process = Runtime.getRuntime().exec(arrayOf("pidof", targetPackage))
                val output = BufferedReader(InputStreamReader(process.inputStream)).readText().trim()
                process.waitFor()
                output.isNotEmpty()
            } catch (_: Exception) {
                false
            }
        }

        /**
         * 通过多重穿透拉活策略自愈唤醒主应用前台监控服务。
         * 核心使用 ContentProvider 反向穿透拉活，突破 Android 12+ 前台服务后台启动限制。
         *
         * @return 是否成功分发拉活指令
         */
        private fun reviveService(): Boolean {
            return try {
                // 1. 解除应用的后台限制与待机挂起
                executeShellCommand("cmd activity set-inactive $PACKAGE_NAME false")
                executeShellCommand("cmd appops set $PACKAGE_NAME RUN_IN_BACKGROUND allow")

                // 2. 核心：通过 ContentProvider 反向穿透拉活（AMS 无条件拉起目标进程）
                val contentSuccess = executeShellCommand("content query --uri $PROVIDER_URI")

                // 3. 补充：直接唤醒前台服务
                executeShellCommand("am start-foreground-service -n $SERVICE_COMPONENT")
                executeShellCommand("am startservice -n $SERVICE_COMPONENT")

                // 4. 广播兜底（携带包括已停止包名的系统广播标志）
                executeShellCommand("am broadcast -a android.intent.action.BOOT_COMPLETED -p $PACKAGE_NAME -f 0x01000000")

                contentSuccess
            } catch (e: Exception) {
                logInfo("Error reviving service: ${e.message}")
                false
            }
        }

        /**
         * 特权独立硬件物理采样引擎。
         * 直接从 Linux 内核 sysfs 节点读取瞬时电流、电压、温度、充放电状态与电量，
         * 写入外部私有存储共享采样流文件 [EXTERNAL_SAMPLES_FILE_PATH]。
         * 独立常驻运行于 init 下，不受应用多任务划杀影响，保障时间线轨迹零断层。
         */
        private fun recordPhysicalSamplePoint() {
            try {
                val basePath = "/sys/class/power_supply/battery"
                val currentFile = File(basePath, "current_now")
                val voltageFile = File(basePath, "voltage_now")
                val tempFile = File(basePath, "temp")
                val statusFile = File(basePath, "status")
                val capacityFile = File(basePath, "capacity")

                if (!currentFile.exists() || !voltageFile.exists()) return

                val currentMicroA = currentFile.readText().trim().toLongOrNull() ?: return
                val voltageMicroV = voltageFile.readText().trim().toLongOrNull() ?: return
                val tempTenthC = if (tempFile.exists()) tempFile.readText().trim().toIntOrNull() ?: 250 else 250
                val statusStr = if (statusFile.exists()) statusFile.readText().trim() else ""
                val capacity = if (capacityFile.exists()) capacityFile.readText().trim().toIntOrNull() ?: 100 else 100
                val isCharging = statusStr.equals("Charging", ignoreCase = true)

                val voltageVolts = voltageMicroV / 1_000_000.0f
                val currentMa = currentMicroA / 1000.0f
                val powerWatts = (voltageVolts * kotlin.math.abs(currentMa)) / 1000.0f
                val tempC = tempTenthC / 10.0f
                val now = System.currentTimeMillis()

                val line = String.format(
                    Locale.US,
                    "%d,%d,%.3f,%.1f,%.2f,%b,%.2f\n",
                    now, capacity, voltageVolts, tempC, currentMa, isCharging, powerWatts
                )

                val targetFile = File(EXTERNAL_SAMPLES_FILE_PATH)
                val parent = targetFile.parentFile
                if (parent != null && !parent.exists()) {
                    parent.mkdirs()
                }

                FileOutputStream(targetFile, true).use { fos ->
                    fos.write(line.toByteArray(Charsets.UTF_8))
                    fos.flush()
                }

                // 定期轮转修剪（超过 20000 行保留最新 10000 行，避免文件无限增大）
                trimSampleStreamFileIfNeeded(targetFile)
            } catch (_: Exception) {}
        }

        /**
         * 检查并修剪采样流文件，当记录超过 20,000 点时保留最新 10,000 点。
         *
         * @param file 目标采样流文件
         */
        private fun trimSampleStreamFileIfNeeded(file: File) {
            try {
                if (!file.exists() || file.length() < 2 * 1024 * 1024) return
                val lines = file.readLines(Charsets.UTF_8)
                if (lines.size > 20000) {
                    val keepLines = lines.takeLast(10000)
                    FileOutputStream(file, false).use { fos ->
                        for (l in keepLines) {
                            fos.write((l + "\n").toByteArray(Charsets.UTF_8))
                        }
                        fos.flush()
                    }
                }
            } catch (_: Exception) {}
        }

        /**
         * 移除由 Shell 发送的历史常驻通知。
         * 优先使用 Android 系统内部 INotificationManager 反射撤销，并结合 cmd notification cancel 双保险，兼容各版本 Android 原生系统。
         */
        private fun removeShellNotification() {
            try {
                val smClass = Class.forName("android.os.ServiceManager")
                val getService = smClass.getMethod("getService", String::class.java)
                val binder = getService.invoke(null, "notification") as? android.os.IBinder
                if (binder != null) {
                    val stubClass = Class.forName("android.app.INotificationManager\$Stub")
                    val asInterface = stubClass.getMethod("asInterface", android.os.IBinder::class.java)
                    val nm = asInterface.invoke(null, binder)
                    for (tag in listOf(NOTIFICATION_TAG, NOTIFICATION_TAG_LEGACY)) {
                        for (m in nm.javaClass.methods) {
                            if (m.name == "cancelNotificationWithTag") {
                                val pts = m.parameterTypes
                                if (pts.size == 4 && pts[0] == String::class.java && pts[1] == String::class.java) {
                                    m.invoke(nm, "com.android.shell", tag, NOTIFICATION_ID, 0)
                                    break
                                } else if (pts.size == 5 && pts[0] == String::class.java && pts[1] == String::class.java) {
                                    m.invoke(nm, "com.android.shell", "com.android.shell", tag, NOTIFICATION_ID, 0)
                                    break
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
            try {
                executeShellCommand("cmd notification cancel $NOTIFICATION_TAG")
                executeShellCommand("cmd notification cancel $NOTIFICATION_TAG_LEGACY")
            } catch (_: Exception) {}
        }

        /**
         * 执行原生 Linux Shell 命令行。
         *
         * @param cmd 要执行的 Shell 命令行
         * @return 命令执行退出码是否为 0
         */
        private fun executeShellCommand(cmd: String): Boolean {
            return try {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                val exitCode = process.waitFor()
                exitCode == 0
            } catch (_: Exception) {
                false
            }
        }

        /**
         * 向 /proc/self/oom_score_adj 写入指定的分数值，将当前进程的 OOM 查杀保护级别提升至最高。
         *
         * @param score 要设定的 OOM 分数（如 -1000 代表完全免疫 LMK）
         * @return 是否成功写入指定分数
         */
        private fun setOomScoreAdj(score: Int): Boolean {
            return try {
                val file = File("/proc/self/oom_score_adj")
                if (file.exists() && file.canWrite()) {
                    FileOutputStream(file).use { fos ->
                        fos.write(score.toString().toByteArray())
                        fos.flush()
                    }
                    logInfo("Successfully set oom_score_adj to $score")
                    true
                } else {
                    executeShellCommand("echo $score > /proc/self/oom_score_adj")
                }
            } catch (e: Exception) {
                logInfo("Failed to set oom_score_adj: ${e.message}")
                false
            }
        }

        /**
         * 逃逸现代 Android cgroup freezer 进程冻结组，将当前进程加入系统根 cgroup 节点。
         *
         * @return 是否成功迁移至少一个 cgroup 节点
         */
        private fun escapeCgroups(): Boolean {
            var success = false
            val pid = getMyProcessId().toString()
            val candidatePaths = listOf(
                "/sys/fs/cgroup/cgroup.procs",
                "/dev/cg2_bpf/cgroup.procs",
                "/acct/cgroup.procs",
                "/dev/cpuset/cgroup.procs",
                "/sys/fs/cgroup/freezer/cgroup.procs"
            )

            for (path in candidatePaths) {
                try {
                    val file = File(path)
                    if (file.exists() && file.canWrite()) {
                        FileOutputStream(file, true).use { fos ->
                            fos.write(pid.toByteArray())
                            fos.write("\n".toByteArray())
                            fos.flush()
                        }
                        logInfo("Appended PID $pid to $path")
                        success = true
                    }
                } catch (_: Exception) {}
            }
            return success
        }

        /**
         * 获取当前进程的 Linux PID。
         *
         * @return 当前进程 PID 整数值
         */
        private fun getMyProcessId(): Int {
            return try {
                File("/proc/self").canonicalFile.name.toInt()
            } catch (_: Exception) {
                try {
                    android.os.Process.myPid()
                } catch (_: Exception) {
                    -1
                }
            }
        }

        /**
         * 获取当前进程的 Linux 用户 UID。
         *
         * @return 当前进程 UID 整数值
         */
        private fun getMyUid(): Int {
            return try {
                val statusFile = File("/proc/self/status")
                if (statusFile.exists()) {
                    val lines = statusFile.readLines()
                    for (line in lines) {
                        if (line.startsWith("Uid:")) {
                            val parts = line.split(Regex("\\s+"))
                            if (parts.size >= 2) {
                                return parts[1].toInt()
                            }
                        }
                    }
                }
                android.os.Process.myUid()
            } catch (_: Exception) {
                -1
            }
        }

        /**
         * 将守护进程状态以 JSON 格式持久化更新到指定的状态文件，供宿主 App 探测。
         *
         * @param pid 守护进程 PID
         * @param uid 守护进程 UID
         * @param startTime 启动时间毫秒戳
         * @param lastHeartbeat 最近心跳时间毫秒戳
         * @param reviveCount 累计自愈拉活次数
         * @param state 运行状态描述字符串
         */
        private fun updateStatusFile(
            pid: Int,
            uid: Int,
            startTime: Long,
            lastHeartbeat: Long,
            reviveCount: Int,
            state: String
        ) {
            try {
                val json = buildString {
                    append("{\n")
                    append("  \"version\": 2,\n")
                    append("  \"pid\": $pid,\n")
                    append("  \"uid\": $uid,\n")
                    append("  \"startTime\": $startTime,\n")
                    append("  \"lastHeartbeat\": $lastHeartbeat,\n")
                    append("  \"reviveCount\": $reviveCount,\n")
                    append("  \"state\": \"$state\"\n")
                    append("}\n")
                }

                val targetFiles = listOf(
                    File(EXTERNAL_STATUS_FILE_PATH),
                    File(STATUS_FILE_PATH)
                )

                for (targetFile in targetFiles) {
                    try {
                        val parent = targetFile.parentFile
                        if (parent != null && !parent.exists()) {
                            parent.mkdirs()
                        }

                        // 写入状态文件并赋予通用可读可写权限
                        FileOutputStream(targetFile).use { fos ->
                            fos.write(json.toByteArray(Charsets.UTF_8))
                            fos.flush()
                        }
                        targetFile.setReadable(true, false)
                        targetFile.setWritable(true, false)
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }

        /**
         * 格式化并输出守护进程诊断日志至标准输出。
         *
         * @param msg 需要记录的日志文本
         */
        private fun logInfo(msg: String) {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            println("[$time] [$TAG] $msg")
        }
    }
}
