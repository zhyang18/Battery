package com.battery.analysis.util

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method

/**
 * Shizuku 特权 Shell 命令受控执行工具类。
 *
 * 集中管理通过 Shizuku 创建的特权子进程生命周期，
 * 在标准 `finally` 块中 100% 强制调用 [Process.destroy] 并安全关闭所有流句柄，
 * 从根源上杜绝 `IShizukuProcess` 上的 [android.os.IBinder.DeathRecipient] 泄漏
 * 与 Linux 内核 Binder 驱动中僵死节点堆积问题。
 */
object ShizukuShellExecutor {

    @Volatile
    private var cachedNewProcessMethod: Method? = null

    @Volatile
    private var hasCheckedNewProcessMethod: Boolean = false

    /**
     * 获取或初始化已缓存的 Shizuku.newProcess 反射 Method 实例。
     *
     * @return 成功解析出的 Method 实例，若反射失败或不可用则返回 null
     */
    private fun getNewProcessMethod(): Method? {
        if (hasCheckedNewProcessMethod) return cachedNewProcessMethod
        return synchronized(this) {
            if (hasCheckedNewProcessMethod) return cachedNewProcessMethod
            try {
                cachedNewProcessMethod = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                ).apply { isAccessible = true }
            } catch (_: Throwable) {
                cachedNewProcessMethod = null
            }
            hasCheckedNewProcessMethod = true
            cachedNewProcessMethod
        }
    }

    /**
     * 校验当前 Shizuku 服务是否处于已激活且已授权可用状态。
     *
     * @return 若已授权并就绪返回 true，否则返回 false
     */
    fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 执行单条特权 Shell 命令并完整读取返回文本结果。
     * 内部保证无论执行成功、异常还是超时，均会在 `finally` 块中强制调用 [Process.destroy]，
     * 并关闭所有标准输入、输出与错误流，保证 0 句柄残留。
     *
     * @param command 要在特权 Shell 中执行的完整命令字符串
     * @return 命令标准输出文本内容（去除首尾空白字符），若失败或无权限则返回空字符串
     */
    fun execute(command: String): String {
        return execute(arrayOf("sh", "-c", command))
    }

    /**
     * 执行命令数组并完整读取返回文本结果，严格履行进程销毁契约。
     *
     * @param cmdArray 命令及其参数组成的字符串数组
     * @return 命令标准输出文本内容，若失败则返回空字符串
     */
    fun execute(cmdArray: Array<String>): String {
        if (!isAvailable()) return ""
        val method = getNewProcessMethod() ?: return ""

        var process: Process? = null
        return try {
            process = method.invoke(null, cmdArray, null, null) as? Process ?: return ""
            val output = StringBuilder()
            val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8), 8192)
            try {
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    output.append(line).append('\n')
                }
            } finally {
                try { reader.close() } catch (_: Throwable) {}
            }
            process.waitFor()
            output.toString().trim()
        } catch (_: Throwable) {
            ""
        } finally {
            if (process != null) {
                safeDestroyProcess(process)
            }
        }
    }

    /**
     * 执行单条特权 Shell 命令并按行读取返回列表。
     *
     * @param command 要在特权 Shell 中执行的完整命令字符串
     * @return 包含各行输出文本的列表，若失败返回空列表
     */
    fun executeLines(command: String): List<String> {
        return executeLines(arrayOf("sh", "-c", command))
    }

    /**
     * 执行命令数组并按行读取返回列表，严格履行进程与流资源回收契约。
     *
     * @param cmdArray 命令及其参数组成的字符串数组
     * @return 包含各行输出文本的列表，若失败返回空列表
     */
    fun executeLines(cmdArray: Array<String>): List<String> {
        if (!isAvailable()) return emptyList()
        val method = getNewProcessMethod() ?: return emptyList()

        var process: Process? = null
        return try {
            process = method.invoke(null, cmdArray, null, null) as? Process ?: return emptyList()
            val lines: List<String>
            val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8), 8192)
            try {
                lines = reader.readLines()
            } finally {
                try { reader.close() } catch (_: Throwable) {}
            }
            process.waitFor()
            lines
        } catch (_: Throwable) {
            emptyList()
        } finally {
            if (process != null) {
                safeDestroyProcess(process)
            }
        }
    }

    /**
     * 安全关闭进程的所有流并强制销毁进程，确保远程 Binder 彻底注销 DeathRecipient。
     *
     * @param process 需要销毁的进程实例
     */
    fun safeDestroyProcess(process: Process) {
        try {
            process.inputStream?.close()
        } catch (_: Throwable) {}
        try {
            process.errorStream?.close()
        } catch (_: Throwable) {}
        try {
            process.outputStream?.close()
        } catch (_: Throwable) {}
        try {
            process.destroy()
        } catch (_: Throwable) {}
    }
}
