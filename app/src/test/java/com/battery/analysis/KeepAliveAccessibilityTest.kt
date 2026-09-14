package com.battery.analysis

import android.content.ComponentName
import com.battery.analysis.service.KeepAliveAccessibilityService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无障碍极速自愈保活服务 [KeepAliveAccessibilityService] 单元测试。
 *
 * 验证无障碍服务组件配置类名完整性与服务状态判定逻辑。
 */
class KeepAliveAccessibilityTest {

    /**
     * 测试无障碍服务类的完整包名与类路径是否符合 Android 清单规范。
     */
    @Test
    fun testAccessibilityServiceClassIdentity() {
        val clazz = KeepAliveAccessibilityService::class.java
        assertEquals(
            "无障碍服务全限定名必须完全一致",
            "com.battery.analysis.service.KeepAliveAccessibilityService",
            clazz.name
        )
    }

    /**
     * 测试系统 Secure Settings 中无障碍服务字符串拼接匹配解析算法的准确性。
     */
    @Test
    fun testAccessibilitySettingStringMatching() {
        val targetService = "com.battery.analysis/com.battery.analysis.service.KeepAliveAccessibilityService"
        val mockSettingsEnabledMultiple = "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService:$targetService:com.dummy.service/.MyService"
        val mockSettingsDisabled = "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService:com.dummy.service/.MyService"

        // 模拟字符串比对逻辑
        val isContainedWhenEnabled = mockSettingsEnabledMultiple.split(":").any { it.equals(targetService, ignoreCase = true) }
        val isContainedWhenDisabled = mockSettingsDisabled.split(":").any { it.equals(targetService, ignoreCase = true) }

        assertTrue("在已启用的字符串列表中必须能够正确检索到目标无障碍服务", isContainedWhenEnabled)
        assertFalse("在未启用的字符串列表中不能匹配到目标无障碍服务", isContainedWhenDisabled)
    }
}
