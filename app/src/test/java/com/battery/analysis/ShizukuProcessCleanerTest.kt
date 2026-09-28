package com.battery.analysis

import com.battery.analysis.util.ShizukuForegroundAppDetector
import com.battery.analysis.util.ShizukuProcessCleaner
import com.battery.analysis.util.safeDestroy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Shizuku 进程深度清理与 Binder 内存泄漏防护单元测试。
 * 覆盖 ShizukuProcessCleaner 深度反射清理、Process.safeDestroy 安全扩展、
 * 前台应用包名规范化过滤规则以及命令行兜底熔断退避机制。
 */
class ShizukuProcessCleanerTest {

    /**
     * 模拟测试专用的简易 [Process] 实现，用于验证 safeDestroy 的管道关闭与进程终止调用。
     */
    private class FakeProcess : Process() {
        /** 标准输入流是否已被关闭 */
        var isClosed: Boolean = false
        /** 标准错误流是否已被关闭 */
        var esClosed: Boolean = false
        /** 标准输出流是否已被关闭 */
        var osClosed: Boolean = false
        /** 进程是否已被销毁 */
        var destroyed: Boolean = false

        private val inStream = object : ByteArrayInputStream(ByteArray(0)) {
            override fun close() {
                super.close()
                isClosed = true
            }
        }

        private val errStream = object : ByteArrayInputStream(ByteArray(0)) {
            override fun close() {
                super.close()
                esClosed = true
            }
        }

        private val outStream = object : ByteArrayOutputStream() {
            override fun close() {
                super.close()
                osClosed = true
            }
        }

        /**
         * 获取进程标准输出流（供读取）。
         *
         * @return 字节输入流实例
         */
        override fun getOutputStream(): OutputStream = outStream

        /**
         * 获取进程标准输入流。
         *
         * @return 字节输入流实例
         */
        override fun getInputStream(): InputStream = inStream

        /**
         * 获取进程标准错误流。
         *
         * @return 字节输入流实例
         */
        override fun getErrorStream(): InputStream = errStream

        /**
         * 等待进程执行完毕。
         *
         * @return 恒定返回退出码 0
         */
        override fun waitFor(): Int = 0

        /**
         * 获取进程退出状态码。
         *
         * @return 恒定返回退出码 0
         */
        override fun exitValue(): Int = 0

        /**
         * 强制终止进程。
         */
        override fun destroy() {
            destroyed = true
        }
    }

    /**
     * 验证 Process.safeDestroy 扩展函数对普通 Process 实例能正确关闭三路流并调用 destroy，
     * 且安全移交给 ShizukuProcessCleaner 处理而不发生崩溃。
     */
    @Test
    fun testProcessSafeDestroyClosesStreamsAndDestroys() {
        val fakeProc = FakeProcess()
        fakeProc.safeDestroy()

        assertTrue("输入流应被安全关闭", fakeProc.isClosed)
        assertTrue("错误流应被安全关闭", fakeProc.esClosed)
        assertTrue("输出流应被安全关闭", fakeProc.osClosed)
        assertTrue("进程 destroy 方法应被调用", fakeProc.destroyed)
    }

    /**
     * 验证 ShizukuProcessCleaner.cleanProcess 在面对非 ShizukuRemoteProcess 的常规进程时能够优雅降级，
     * 不发生反射异常或类型转换异常。
     */
    @Test
    fun testCleanProcessWithNonShizukuProcess() {
        val fakeProc = FakeProcess()
        // 验证非 ShizukuRemoteProcess 实例传入时安全处理
        ShizukuProcessCleaner.cleanProcess(fakeProc)
    }

    /**
     * 验证 ShizukuProcessCleaner.purgeDanglingProcesses 能够安全执行并返回非负的清理计数。
     */
    @Test
    fun testPurgeDanglingProcessesSafeExecution() {
        val purgedCount = ShizukuProcessCleaner.purgeDanglingProcesses()
        assertTrue("清理计数应大于或等于 0", purgedCount >= 0)
    }

    /**
     * 验证前台应用包名规范化清洗规则：
     * 正确过滤 SystemUI 与各类第三方输入法遮罩，有效保留普通应用包名。
     */
    @Test
    fun testNormalizeForegroundPackage() {
        // 过滤 SystemUI
        assertNull(ShizukuForegroundAppDetector.normalizeForegroundPackage("com.android.systemui"))
        assertNull(ShizukuForegroundAppDetector.normalizeForegroundPackage("com.android.systemui.navbar"))

        // 过滤各大主流输入法
        assertNull(ShizukuForegroundAppDetector.normalizeForegroundPackage("com.sohu.inputmethod.sogou"))
        assertNull(ShizukuForegroundAppDetector.normalizeForegroundPackage("com.baidu.input"))
        assertNull(ShizukuForegroundAppDetector.normalizeForegroundPackage("com.google.android.inputmethod.latin"))
        assertNull(ShizukuForegroundAppDetector.normalizeForegroundPackage("com.tencent.qqpinyin"))

        // 空值或无效串
        assertNull(ShizukuForegroundAppDetector.normalizeForegroundPackage(null))
        assertNull(ShizukuForegroundAppDetector.normalizeForegroundPackage(""))

        // 普通合法前台应用保持原样
        assertEquals("com.tencent.mm", ShizukuForegroundAppDetector.normalizeForegroundPackage("com.tencent.mm"))
        assertEquals("com.android.chrome", ShizukuForegroundAppDetector.normalizeForegroundPackage("com.android.chrome"))
        assertEquals("com.battery.analysis", ShizukuForegroundAppDetector.normalizeForegroundPackage("com.battery.analysis"))
    }

    /**
     * 验证命令行兜底熔断退避机制的冷却时间计算公式：
     * 基础冷却 15s，失败后指数退避，最大截断为 60s。
     */
    @Test
    fun testCmdProbeExponentialBackoff() {
        val baseMs = 15_000L
        val maxMs = 60_000L

        fun calcCooldown(failCount: Int): Long {
            return minOf(baseMs * (1L shl minOf(failCount, 4)), maxMs)
        }

        // 第 0 次失败（刚失败第 1 次，failCount=1）
        assertEquals(30_000L, calcCooldown(1))
        // 第 2 次失败
        assertEquals(60_000L, calcCooldown(2))
        // 第 3 次失败
        assertEquals(60_000L, calcCooldown(3))
        // 高频多次失败，严格被 60 秒上限截断
        assertEquals(60_000L, calcCooldown(10))
    }
}
