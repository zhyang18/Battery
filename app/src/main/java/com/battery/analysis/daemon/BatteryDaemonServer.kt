package com.battery.analysis.daemon

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 特权独立守护进程核心服务（Root / ADB 终极防杀运行时）。
 *
 * 本类脱离 Android 原生应用组件树，由 /system/bin/app_process 直接拉起并托管于 Linux init (PID 1) 进程下。
 * 核心机制：
 * 1. 内核级单实例独占锁：通过 /data/local/tmp/battery_daemon.lock 独占排他锁，彻底杜绝重复拉起与多实例争抢；
 * 2. 内核级 OOM 分数置顶：向 /proc/self/oom_score_adj 写入 -1000，免疫系统低内存查杀；
 * 3. 破除现代 Android cgroup freezer 进程冻结：逃逸至根 cgroup 节点；
 * 4. 持久化状态与心跳：写入状态至 /data/local/tmp/battery_daemon.status，支持双向探针；
 * 5. 毫秒级反向穿透拉活：后台死循环轮询宿主 App 进程与 BatteryMonitorService 存活状态，
 *    一旦检测到被用户划杀或被系统管家清理，立即调用特权 am start-foreground-service 瞬间自愈拉活。
 */
class BatteryDaemonServer {

    companion object {
        private const val TAG = "BatteryDaemonServer"
        private const val PACKAGE_NAME = "com.battery.analysis"
        private const val SERVICE_COMPONENT = "com.battery.analysis/.service.BatteryMonitorService"
        
        const val STATUS_FILE_PATH = "/data/local/tmp/battery_daemon.status"
        const val STOP_FILE_PATH = "/data/local/tmp/battery_daemon.stop"
        const val LOCK_FILE_PATH = "/data/local/tmp/battery_daemon.lock"

        private const val CHECK_INTERVAL_MS = 3000L

        private var lockRaf: RandomAccessFile? = null
        private var lockChannel: FileChannel? = null
        private var fileLock: FileLock? = null

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

            // 2. 双重单实例防重校验：先检查既有活跃实例状态与 PID 存活性
            if (isExistingInstanceAlive()) {
                logInfo("Another active BatteryDaemonServer instance is already running. Exiting cleanly.")
                System.exit(0)
                return
            }

            // 3. 抢占内核级文件排他锁，抢占失败说明已有并发实例正在运行
            if (!acquireProcessLock()) {
                logInfo("Failed to acquire process lock ($LOCK_FILE_PATH). Another instance is running. Exiting cleanly.")
                System.exit(0)
                return
            }

            // 4. 提升 OOM 分数为 -1000（内核最高免疫级别）
            setOomScoreAdj(-1000)

            // 5. 逃逸 cgroup freezer 进程冻结组
            escapeCgroups()

            val myPid = getMyProcessId()
            val myUid = getMyUid()
            val startTime = System.currentTimeMillis()
            var reviveCount = 0

            logInfo("Daemon initialized. PID=$myPid, UID=$myUid, StartTime=$startTime")

            // 6. 初始写入状态文件
            updateStatusFile(myPid, myUid, startTime, System.currentTimeMillis(), reviveCount, "RUNNING")

            // 7. 核心守护与反向拉活主循环
            while (true) {
                try {
                    // 检查是否有外部停止信号文件
                    if (File(STOP_FILE_PATH).exists()) {
                        logInfo("Stop signal received. Cleaning up and exiting.")
                        File(STOP_FILE_PATH).delete()
                        File(STATUS_FILE_PATH).delete()
                        releaseProcessLock()
                        System.exit(0)
                        return
                    }

                    // 检测主应用进程是否处于存活状态
                    val isAppAlive = isPackageRunning(PACKAGE_NAME)
                    if (!isAppAlive) {
                        logInfo("Host process $PACKAGE_NAME is NOT running! Reviving...")
                        val success = reviveService()
                        if (success) {
                            reviveCount++
                            logInfo("Revived successfully! Total count: $reviveCount")
                        } else {
                            logInfo("Revive command executed, waiting for state change.")
                        }
                    }

                    // 更新心跳状态
                    updateStatusFile(myPid, myUid, startTime, System.currentTimeMillis(), reviveCount, "RUNNING")

                    // 避免频繁占用 CPU，休眠指定周期
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
            releaseProcessLock()
            logInfo("BatteryDaemonServer terminated.")
        }

        /**
         * 探测系统中是否已有正在活跃运行的守护进程实例。
         *
         * 读取 /data/local/tmp/battery_daemon.status 中的 PID 与心跳时间戳，
         * 并校验 /proc/<pid>/cmdline 是否包含 BatteryDaemonServer 且心跳在 12 秒有效期内。
         *
         * @return 若已有活跃实例运行返回 true，否则返回 false
         */
        private fun isExistingInstanceAlive(): Boolean {
            val statusFile = File(STATUS_FILE_PATH)
            if (!statusFile.exists() || !statusFile.canRead()) return false
            return try {
                val content = statusFile.readText(Charsets.UTF_8).trim()
                if (content.isEmpty()) return false
                val json = JSONObject(content)
                val pid = json.optInt("pid", -1)
                val lastHeartbeat = json.optLong("lastHeartbeat", 0L)
                val now = System.currentTimeMillis()
                if (pid > 0 && (now - lastHeartbeat) < 12000L && pid != getMyProcessId()) {
                    val cmdlineFile = File("/proc/$pid/cmdline")
                    if (cmdlineFile.exists() && cmdlineFile.canRead()) {
                        val cmd = cmdlineFile.readBytes()
                        val cmdStr = String(cmd).replace("\u0000", " ")
                        if (cmdStr.contains("BatteryDaemonServer")) {
                            return true
                        }
                    }
                }
                false
            } catch (_: Exception) {
                false
            }
        }

        /**
         * 尝试获取全局单实例 Linux 内核文件排他独占锁。
         *
         * 若获取成功，会将当前进程真实 PID 写入锁文件；
         * 若锁已被其他进程持有，则说明已有存活的守护进程实例正在运行。
         * 内核级保证：持有锁的进程被杀或退出时，内核会自动释放文件锁，绝不造成死锁。
         *
         * @return 获取独占锁成功返回 true，获取失败返回 false
         */
        private fun acquireProcessLock(): Boolean {
            return try {
                val lockFile = File(LOCK_FILE_PATH)
                val parent = lockFile.parentFile
                if (parent != null && !parent.exists()) {
                    parent.mkdirs()
                }
                val raf = RandomAccessFile(lockFile, "rw")
                lockRaf = raf
                val channel = raf.channel
                lockChannel = channel
                val lock = channel.tryLock()
                if (lock != null && lock.isValid) {
                    fileLock = lock
                    raf.setLength(0)
                    val pidStr = "${getMyProcessId()}\n"
                    raf.write(pidStr.toByteArray(Charsets.UTF_8))
                    lockFile.setReadable(true, false)
                    true
                } else {
                    releaseProcessLock()
                    false
                }
            } catch (_: Exception) {
                releaseProcessLock()
                false
            }
        }

        /**
         * 释放全局单实例文件独占锁并清理锁资源。
         */
        private fun releaseProcessLock() {
            try {
                fileLock?.release()
            } catch (_: Exception) {}
            fileLock = null

            try {
                lockChannel?.close()
            } catch (_: Exception) {}
            lockChannel = null

            try {
                lockRaf?.close()
            } catch (_: Exception) {}
            lockRaf = null

            try {
                File(LOCK_FILE_PATH).delete()
            } catch (_: Exception) {}
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
                    // 尝试通过 shell 命令写入
                    Runtime.getRuntime().exec(arrayOf("sh", "-c", "echo $score > /proc/self/oom_score_adj")).waitFor()
                    logInfo("Executed echo $score > /proc/self/oom_score_adj")
                    true
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
         * 遍历 Linux /proc 文件系统检测目标应用包名是否当前正在运行。
         *
         * @param targetPackage 目标应用包名
         * @return 若应用进程正在运行返回 true，否则返回 false
         */
        private fun isPackageRunning(targetPackage: String): Boolean {
            return try {
                val procDir = File("/proc")
                val files = procDir.listFiles() ?: return false
                for (file in files) {
                    if (!file.isDirectory) continue
                    val name = file.name
                    // 仅检查纯数字目录名（即 PID 目录）
                    if (name.all { it.isDigit() }) {
                        val cmdlineFile = File(file, "cmdline")
                        if (cmdlineFile.exists() && cmdlineFile.canRead()) {
                            val cmd = cmdlineFile.readBytes()
                            if (cmd.isNotEmpty()) {
                                // cmdline 中参数以 null 字符 (\0) 分隔
                                val cmdStr = String(cmd).replace("\u0000", " ").trim()
                                if (cmdStr == targetPackage || cmdStr.startsWith("$targetPackage:")) {
                                    return true
                                }
                            }
                        }
                    }
                }
                false
            } catch (e: Exception) {
                false
            }
        }

        /**
         * 通过系统特权调用 am 启动命令，瞬间反向拉活主应用的前台监控服务。
         *
         * @return 命令是否成功触发并返回成功退出码
         */
        private fun reviveService(): Boolean {
            return try {
                // 优先尝试前台服务拉起（Android 8.0+ 适配）
                val commands = arrayOf(
                    "am start-foreground-service -n $SERVICE_COMPONENT",
                    "am startservice -n $SERVICE_COMPONENT",
                    "am broadcast -a android.intent.action.BOOT_COMPLETED -p $PACKAGE_NAME"
                )

                var executed = false
                for (cmd in commands) {
                    try {
                        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                        val exitCode = process.waitFor()
                        if (exitCode == 0) {
                            executed = true
                            break
                        }
                    } catch (_: Exception) {}
                }
                executed
            } catch (e: Exception) {
                logInfo("Error reviving service: ${e.message}")
                false
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
