package com.battery.analysis

import com.battery.analysis.daemon.DaemonManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 特权守护进程管理器 [DaemonManager] 单元测试。
 *
 * 验证 ADB 命令构造、状态模型属性判断及心跳逻辑。
 */
class DaemonManagerTest {

    /**
     * 测试 ADB 启动命令是否包含正确的参数、主类名与脱离终端后台标记。
     */
    @Test
    fun testAdbLaunchCommandFormat() {
        val adbCmd = DaemonManager.getAdbCommand()
        assertTrue("命令必须以 adb shell 开头", adbCmd.startsWith("adb shell "))
        assertTrue("必须包含 nohup 脱离终端", adbCmd.contains("nohup /system/bin/app_process"))
        assertTrue("必须指定守护服务主入口类", adbCmd.contains("com.battery.analysis.daemon.BatteryDaemonServer"))
        assertTrue("必须包含后台运行符号 &", adbCmd.endsWith("&\""))
    }

    /**
     * 测试 ADB 停止命令是否包含清理标记文件和终止目标进程名。
     */
    @Test
    fun testAdbStopCommandFormat() {
        val stopCmd = DaemonManager.getAdbStopCommand()
        assertTrue("停止命令必须包含 adb shell", stopCmd.startsWith("adb shell "))
        assertTrue("必须包含创建 stop 标记文件", stopCmd.contains("touch /data/local/tmp/battery_daemon.stop"))
        assertTrue("必须包含 pkill 终止目标类", stopCmd.contains("pkill -f com.battery.analysis.daemon.BatteryDaemonServer"))
    }

    /**
     * 测试守护进程状态模型的权限模式判断属性。
     */
    @Test
    fun testDaemonStatusModelPrivileges() {
        val rootStatus = DaemonManager.DaemonStatus(
            isRunning = true,
            pid = 1234,
            uid = 0,
            startTime = 1000L,
            lastHeartbeat = 2000L,
            reviveCount = 5
        )
        assertTrue("UID 为 0 时必须判定为 Root 模式", rootStatus.isRoot())
        assertFalse("UID 为 0 时不应判定为 Shell 模式", rootStatus.isShell())

        val shellStatus = DaemonManager.DaemonStatus(
            isRunning = true,
            pid = 5678,
            uid = 2000,
            startTime = 1000L,
            lastHeartbeat = 2000L,
            reviveCount = 2
        )
        assertFalse("UID 为 2000 时不应判定为 Root 模式", shellStatus.isRoot())
        assertTrue("UID 为 2000 时必须判定为 Shell 模式", shellStatus.isShell())
        assertEquals(5678, shellStatus.pid)
        assertEquals(2, shellStatus.reviveCount)
    }

    /**
     * 测试未运行状态下的默认属性配置。
     */
    @Test
    fun testDaemonStatusStoppedDefault() {
        val stoppedStatus = DaemonManager.DaemonStatus(isRunning = false)
        assertFalse("状态必须为未运行", stoppedStatus.isRunning)
        assertEquals(-1, stoppedStatus.pid)
        assertEquals(-1, stoppedStatus.uid)
        assertFalse(stoppedStatus.isRoot())
        assertFalse(stoppedStatus.isShell())
    }
}
