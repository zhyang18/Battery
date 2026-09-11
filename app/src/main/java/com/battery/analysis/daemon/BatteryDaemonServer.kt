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
 * 5. Shell 特权通知常驻兜底：寄生于 com.android.shell (UID 2000)，在划杀恢复期间保障通知栏持续常驻。
 */
class BatteryDaemonServer {

    companion object {
        private const val TAG = "BatteryDaemonServer"
        private const val PACKAGE_NAME = "com.battery.analysis"
        private const val SERVICE_COMPONENT = "com.battery.analysis/.service.BatteryMonitorService"
        private const val PROVIDER_URI = "content://com.battery.analysis.daemon.provider/revive"
        
        const val STATUS_FILE_PATH = "/data/local/tmp/battery_daemon.status"
        const val STOP_FILE_PATH = "/data/local/tmp/battery_daemon.stop"
        const val APP_ALIVE_FILE_PATH = "/data/local/tmp/battery_app.alive"
        const val APP_MANUAL_STOP_PATH = "/data/local/tmp/battery_app.manual_stop"

        private const val CHECK_INTERVAL_MS = 2500L
        private const val ALIVE_TIMEOUT_MS = 6000L

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

            // 2. 提升 OOM 分数为 -1000（内核最高免疫级别）
            setOomScoreAdj(-1000)

            // 3. 逃逸 cgroup freezer 进程冻结组
            escapeCgroups()

            val myPid = getMyProcessId()
            val myUid = getMyUid()
            val startTime = System.currentTimeMillis()
            var reviveCount = 0

            logInfo("Daemon initialized. PID=$myPid, UID=$myUid, StartTime=$startTime")

            // 4. 初始写入状态文件
            updateStatusFile(myPid, myUid, startTime, System.currentTimeMillis(), reviveCount, "RUNNING")

            // 5. 核心守护与反向穿透拉活主循环
            while (true) {
                try {
                    // 检查是否有外部停止信号文件
                    if (File(STOP_FILE_PATH).exists()) {
                        logInfo("Stop signal received. Cleaning up and exiting.")
                        File(STOP_FILE_PATH).delete()
                        File(STATUS_FILE_PATH).delete()
                        removeShellNotification()
                        System.exit(0)
                        return
                    }

                    // 检查用户是否在主应用设置中主动关闭了监控服务
                    val isManualStopped = File(APP_MANUAL_STOP_PATH).exists()

                    if (!isManualStopped) {
                        // 检测主应用监控服务是否处于存活状态
                        val isAppAlive = isHostAlive()
                        if (!isAppAlive) {
                            logInfo("Host service is NOT alive! Triggering penetration revive...")
                            // 发送 Shell 级备用通知兜底，保障通知栏绝不空白
                            postShellNotification(reviveCount + 1)
                            
                            val success = reviveService()
                            if (success) {
                                reviveCount++
                                logInfo("Revived successfully! Total count: $reviveCount")
                            } else {
                                logInfo("Revive commands dispatched, waiting for state sync.")
                            }
                        } else {
                            // 主服务恢复存活后，撤销 Shell 备用通知，由主服务前台通知接管
                            removeShellNotification()
                        }
                    } else {
                        removeShellNotification()
                    }

                    // 更新心跳状态
                    updateStatusFile(myPid, myUid, startTime, System.currentTimeMillis(), reviveCount, "RUNNING")

                    // 休眠指定周期
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
            removeShellNotification()
            logInfo("BatteryDaemonServer terminated.")
        }

        /**
         * 判定主应用前台监控服务当前是否真实存活。
         * 优先检查高频更新的心跳文件时间戳，并辅助进程 PID 探测。
         *
         * @return 若主服务在最近超时窗口内保持活跃返回 true，否则返回 false
         */
        private fun isHostAlive(): Boolean {
            val aliveFile = File(APP_ALIVE_FILE_PATH)
            if (aliveFile.exists() && aliveFile.canRead()) {
                try {
                    val content = aliveFile.readText(Charsets.UTF_8).trim()
                    val parts = content.split(":")
                    if (parts.isNotEmpty()) {
                        val timestamp = parts[0].toLongOrNull() ?: 0L
                        val diff = System.currentTimeMillis() - timestamp
                        if (diff in 0..ALIVE_TIMEOUT_MS) {
                            return true
                        }
                    }
                } catch (_: Exception) {}
            }

            // 心跳文件不存在或超时，辅助通过 pidof 校验
            return isProcessRunningPidof(PACKAGE_NAME)
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
         * 在宿主进程被划杀期间，以 Shell (UID 2000) 身份向通知栏发送寄生常驻兜底通知。
         *
         * @param count 当前自愈拉活累计计数值
         */
        private fun postShellNotification(count: Int) {
            try {
                val title = "电池监控守护中"
                val text = "应用后台自愈恢复中 (已守护拉活 $count 次)"
                executeShellCommand("cmd notification post -S bigtext -t \"$title\" \"battery_daemon_tag\" \"$text\"")
            } catch (_: Exception) {}
        }

        /**
         * 移除由 Shell 发送的备用通知。
         */
        private fun removeShellNotification() {
            try {
                executeShellCommand("cmd notification cancel \"battery_daemon_tag\"")
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
                    append("  \"pid\": $pid,\n")
                    append("  \"uid\": $uid,\n")
                    append("  \"startTime\": $startTime,\n")
                    append("  \"lastHeartbeat\": $lastHeartbeat,\n")
                    append("  \"reviveCount\": $reviveCount,\n")
                    append("  \"state\": \"$state\"\n")
                    append("}\n")
                }

                val targetFile = File(STATUS_FILE_PATH)
                val parent = targetFile.parentFile
                if (parent != null && !parent.exists()) {
                    parent.mkdirs()
                }

                // 写入状态文件并赋予通用可读权限 (rw-r--r--)
                FileOutputStream(targetFile).use { fos ->
                    fos.write(json.toByteArray(Charsets.UTF_8))
                    fos.flush()
                }
                targetFile.setReadable(true, false)
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
