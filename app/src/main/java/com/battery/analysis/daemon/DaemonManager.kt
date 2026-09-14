package com.battery.analysis.daemon

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader

/**
 * 独立特权守护进程（Daemon）在宿主 App 进程中的调度管理与状态探针。
 *
 * 负责检测守护进程存活状态、通过 Root (su) 或 Shizuku (newProcess) 启动守护进程、
 * 发送终止指令以及生成标准 ADB Shell 启动命令行。
 */
object DaemonManager {

    /**
     * 特权守护进程的运行时状态模型。
     *
     * @property isRunning 是否处于活跃运行状态（心跳新鲜且状态文件有效）
     * @property pid 守护进程的 Linux PID，未运行则为 -1
     * @property uid 守护进程的 Linux 用户 UID，未运行则为 -1
     * @property startTime 守护进程启动的时间戳（毫秒），未运行则为 0L
     * @property lastHeartbeat 最近一次上报心跳的时间戳（毫秒），未运行则为 0L
     * @property reviveCount 守护进程累计自愈拉活主应用服务的次数
     * @property version 守护进程架构版本号（v2 起彻底移除 Shell 通知，v1 为含通知的历史版本）
     */
    data class DaemonStatus(
        val isRunning: Boolean = false,
        val pid: Int = -1,
        val uid: Int = -1,
        val startTime: Long = 0L,
        val lastHeartbeat: Long = 0L,
        val reviveCount: Int = 0,
        val version: Int = 1
    ) {
        /**
         * 当前守护进程是否运行在 Root (UID 0) 模式下。
         *
         * @return 若 UID 为 0 返回 true，否则返回 false
         */
        fun isRoot(): Boolean = uid == 0

        /**
         * 当前守护进程是否运行在 Shell (UID 2000) 模式下。
         *
         * @return 若 UID 为 2000 返回 true，否则返回 false
         */
        fun isShell(): Boolean = uid == 2000

        /**
         * 判断当前运行中的守护进程是否为遗留历史版本（v1，包含冗余 Shell 通知）。
         *
         * @return 若处于运行中且版本号小于 2 返回 true，否则返回 false
         */
        fun isLegacyVersion(): Boolean = isRunning && version < 2
    }

    /** 应用外部私有存储共享状态文件路径（解决普通应用读取 /data/local/tmp 权限受限问题） */
    const val EXTERNAL_STATUS_FILE_PATH = "/sdcard/Android/data/com.battery.analysis/files/battery_daemon.status"

    /**
     * 探测并解析当前特权守护进程的运行状态。
     *
     * 优先读取外部私有存储状态文件，回退读取 /data/local/tmp/battery_daemon.status 并校验最近心跳时间戳，
     * 确定守护进程是否在真实活跃运行。
     *
     * @return 解析得到的守护进程运行状态对象 [DaemonStatus]
     */
    fun getDaemonStatus(): DaemonStatus {
        val extStatusFile = File(EXTERNAL_STATUS_FILE_PATH)
        val tmpStatusFile = File(BatteryDaemonServer.STATUS_FILE_PATH)

        val statusFile = when {
            extStatusFile.exists() && extStatusFile.canRead() -> extStatusFile
            tmpStatusFile.exists() && tmpStatusFile.canRead() -> tmpStatusFile
            else -> return DaemonStatus(isRunning = false)
        }

        return try {
            val content = statusFile.readText(Charsets.UTF_8).trim()
            if (content.isEmpty()) {
                return DaemonStatus(isRunning = false)
            }

            val json = JSONObject(content)
            val version = json.optInt("version", 1)
            val pid = json.optInt("pid", -1)
            val uid = json.optInt("uid", -1)
            val startTime = json.optLong("startTime", 0L)
            val lastHeartbeat = json.optLong("lastHeartbeat", 0L)
            val reviveCount = json.optInt("reviveCount", 0)

            // 心跳超时阈值：如果超过 12 秒没有更新心跳，判定进程已脱离或被杀死
            val now = System.currentTimeMillis()
            val isAlive = (now - lastHeartbeat) < 12000L && pid > 0

            DaemonStatus(
                isRunning = isAlive,
                pid = pid,
                uid = uid,
                startTime = startTime,
                lastHeartbeat = lastHeartbeat,
                reviveCount = reviveCount,
                version = version
            )
        } catch (_: Exception) {
            DaemonStatus(isRunning = false)
        }
    }

    /**
     * 检测特权守护进程当前是否正在活跃运行。
     *
     * @return 若处于活跃运行状态返回 true，否则返回 false
     */
    fun isDaemonRunning(): Boolean {
        return getDaemonStatus().isRunning
    }

    /**
     * 获取拉起特权守护进程的纯 Shell 脚本命令（脱离终端并在后台运行 app_process）。
     *
     * 避免使用部分定制 ROM 未预装的 nohup 命令，改用标准 POSIX 子进程重定向与环境变量语法。
     *
     * @param context 应用程序上下文，用于获取 APK 安装包绝对路径
     * @return 组装完成的可执行 Shell 命令字符串
     */
    /** 应用外部私有存储共享心跳文件路径（普通应用沙箱具备读写豁免权限，守护进程亦可读写） */
    const val EXTERNAL_ALIVE_FILE_PATH = "/sdcard/Android/data/com.battery.analysis/files/battery_app.alive"

    /** 应用外部私有存储共享采样流文件路径（守护进程独立高精采样日志） */
    const val EXTERNAL_SAMPLES_FILE_PATH = "/sdcard/Android/data/com.battery.analysis/files/battery_samples.stream"

    /**
     * 获取主应用与特权守护进程共享的活跃状态心跳文件对象。
     *
     * 优先返回无需运行时权限的应用外部私有文件路径，确保普通应用在沙箱内写入无权限阻碍。
     *
     * @param context 应用程序上下文
     * @return 共享心跳 [File] 对象
     */
    fun getSharedAliveFile(context: Context): File {
        val extDir = context.getExternalFilesDir(null)
        return if (extDir != null) {
            File(extDir, "battery_app.alive")
        } else {
            File(EXTERNAL_ALIVE_FILE_PATH)
        }
    }

    /**
     * 获取特权守护进程持久化写入的电池物理采样流文件。
     *
     * @param context 应用程序上下文
     * @return 共享采样流 [File] 对象
     */
    fun getSharedSamplesFile(context: Context): File {
        val extDir = context.getExternalFilesDir(null)
        return if (extDir != null) {
            File(extDir, "battery_samples.stream")
        } else {
            File(EXTERNAL_SAMPLES_FILE_PATH)
        }
    }

    /**
     * 获取拉起特权守护进程的纯 Shell 脚本命令（脱离终端并在后台运行 app_process）。
     *
     * 使用 nohup 及后台重定向语法，指定 nice-name 为 battery_daemon，彻底脱离父 Shell 进程生命周期。
     *
     * @param context 应用程序上下文，用于获取 APK 安装包绝对路径
     * @return 组装完成的可执行 Shell 命令字符串
     */
    fun getLaunchShellCommand(context: Context): String {
        val apkPath = context.applicationInfo.sourceDir
        return "pkill -f battery_daemon; pkill -f com.battery.analysis.daemon.BatteryDaemonServer; export CLASSPATH=$apkPath; nohup /system/bin/app_process /system/bin --nice-name=battery_daemon com.battery.analysis.daemon.BatteryDaemonServer </dev/null >/dev/null 2>&1 &"
    }

    /**
     * 获取供用户在电脑终端直接执行的完整 ADB Shell 启动命令。
     *
     * 动态使用 `pm path com.battery.analysis` 解析 APK 路径，兼容 Windows CMD/PowerShell 与 Linux/macOS 终端。
     * 启动前优先终止旧进程并设置进程名。
     *
     * @return 供在电脑执行的标准 adb shell 命令
     */
    fun getAdbCommand(): String {
        return "adb shell \"pkill -f battery_daemon; pkill -f com.battery.analysis.daemon.BatteryDaemonServer; export CLASSPATH=\\$(pm path com.battery.analysis | head -n 1 | cut -d: -f2); nohup /system/bin/app_process /system/bin --nice-name=battery_daemon com.battery.analysis.daemon.BatteryDaemonServer </dev/null >/dev/null 2>&1 &\""
    }

    /**
     * 获取供在电脑执行的标准 ADB Shell 停止守护进程命令。
     *
     * @return 停止守护进程的 adb shell 命令
     */
    fun getAdbStopCommand(): String {
        return "adb shell \"touch /data/local/tmp/battery_daemon.stop && touch /sdcard/Android/data/com.battery.analysis/files/battery_daemon.stop && pkill -f battery_daemon; pkill -f com.battery.analysis.daemon.BatteryDaemonServer && cmd notification cancel battery_daemon_tag\""
    }

    /**
     * 检查当前系统环境是否存在可用的 Root (su) 提权工具。
     *
     * @return 若检测到可用 su 二进制文件返回 true，否则返回 false
     */
    fun isRootAvailable(): Boolean {
        val paths = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/vendor/bin/su",
            "/system/sd/xbin/su"
        )
        for (p in paths) {
            if (File(p).exists()) return true
        }
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 检查当前应用是否拥有活跃且可用的 Shizuku 授权。
     *
     * @return 若 Shizuku 已连接且已获权限返回 true，否则返回 false
     */
    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 通过 Root (su) 特权一键启动独立守护进程。
     *
     * @param context 应用程序上下文
     * @return 执行结果包装对象，若成功返回 Success，失败包含异常说明
     */
    fun startWithRoot(context: Context): Result<Unit> {
        return try {
            val launchCmd = getLaunchShellCommand(context)
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", launchCmd))
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                Result.success(Unit)
            } else {
                val errorMsg = BufferedReader(InputStreamReader(process.errorStream)).readText()
                Result.failure(RuntimeException("Root 启动失败 (退出码 $exitCode): $errorMsg"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 通过 Shizuku 特权一键启动独立守护进程。
     *
     * @param context 应用程序上下文
     * @return 执行结果包装对象，若成功返回 Success，失败包含异常说明
     */
    fun startWithShizuku(context: Context): Result<Unit> {
        if (!isShizukuAvailable()) {
            return Result.failure(IllegalStateException("Shizuku 未运行或尚未授予权限"))
        }

        return try {
            val launchCmd = getLaunchShellCommand(context)
            val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
            val newProcessMethod = shizukuClass.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            newProcessMethod.isAccessible = true

            // 通过 sh -c 运行后台守护命令
            val process = newProcessMethod.invoke(
                null,
                arrayOf("sh", "-c", launchCmd),
                null,
                null
            ) as? Process ?: return Result.failure(RuntimeException("通过 Shizuku 派生进程失败"))

            process.waitFor()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 终止正在运行的特权守护进程。
     *
     * 写入退出标志文件，并在具备 Root 或 Shizuku 权限时直接向进程发送 SIGKILL。
     *
     * @return 操作结果包装对象
     */
    fun stopDaemon(): Result<Unit> {
        return try {
            // 1. 优先尝试写入停止标记文件（外部存储与 tmp 双路径）
            try {
                for (path in listOf(BatteryDaemonServer.STOP_FILE_PATH, BatteryDaemonServer.EXTERNAL_STOP_FILE_PATH)) {
                    val stopFile = File(path)
                    val parent = stopFile.parentFile
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs()
                    }
                    FileOutputStream(stopFile).use { fos ->
                        fos.write("stop\n".toByteArray())
                        fos.flush()
                    }
                }
            } catch (_: Exception) {}

            val status = getDaemonStatus()
            val pid = status.pid

            val cleanupCmd = "pkill -f battery_daemon; pkill -f com.battery.analysis.daemon.BatteryDaemonServer; rm -f ${BatteryDaemonServer.STATUS_FILE_PATH} ${BatteryDaemonServer.STOP_FILE_PATH} ${BatteryDaemonServer.EXTERNAL_STATUS_FILE_PATH} ${BatteryDaemonServer.EXTERNAL_STOP_FILE_PATH}"

            // 2. 若拥有 Root 权限，直接执行 kill
            if (isRootAvailable()) {
                try {
                    val killCmd = if (pid > 0) "kill -9 $pid; $cleanupCmd" else cleanupCmd
                    Runtime.getRuntime().exec(arrayOf("su", "-c", killCmd)).waitFor()
                } catch (_: Exception) {}
            } else if (isShizukuAvailable()) {
                // 3. 若拥有 Shizuku 权限，通过 Shizuku 执行 kill
                try {
                    val killCmd = if (pid > 0) "kill -9 $pid; $cleanupCmd" else cleanupCmd
                    val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
                    val newProcessMethod = shizukuClass.getDeclaredMethod(
                        "newProcess",
                        Array<String>::class.java,
                        Array<String>::class.java,
                        String::class.java
                    )
                    newProcessMethod.isAccessible = true
                    val proc = newProcessMethod.invoke(
                        null,
                        arrayOf("sh", "-c", killCmd),
                        null,
                        null
                    ) as? Process
                    proc?.waitFor()
                } catch (_: Exception) {}
            }

            // 清理本地状态缓存
            try {
                File(BatteryDaemonServer.STATUS_FILE_PATH).delete()
                File(BatteryDaemonServer.EXTERNAL_STATUS_FILE_PATH).delete()
            } catch (_: Exception) {}

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 重启特权独立守护进程。
     * 先优雅终止当前旧进程并彻底撤销通知，再通过可用特权（Root 或 Shizuku）拉起全新无通知的守护服务。
     *
     * @param context 应用程序上下文
     * @return 执行结果包装对象，成功返回 Success，若提权不可用或启动失败包含异常
     */
    fun restartDaemon(context: Context): Result<Unit> {
        return try {
            stopDaemon()
            Thread.sleep(300L)
            if (isRootAvailable()) {
                startWithRoot(context)
            } else if (isShizukuAvailable()) {
                startWithShizuku(context)
            } else {
                Result.failure(IllegalStateException("当前无可用 Root 或 Shizuku 权限，无法自动重启"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
