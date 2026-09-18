package com.battery.analysis

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * 刷新与采样间隔自定义秒数与毫秒转换测试类。
 * 验证亮屏刷新间隔与息屏采样间隔自定义小数秒（精确至小数点 1 位）的数值计算与格式化逻辑。
 */
class IntervalSettingsTest {

    /**
     * 辅助格式化时间间隔文案（模拟 UI 逻辑）。
     *
     * @param intervalMs 间隔毫秒数
     * @return 格式化后的展示文案
     */
    private fun formatInterval(intervalMs: Long): String {
        return when (intervalMs) {
            -1L -> "不采样"
            0L -> "智能省电"
            1000L -> "1 秒"
            3000L -> "3 秒"
            5000L -> "5 秒"
            10000L -> "10 秒"
            30000L -> "30 秒"
            else -> {
                if (intervalMs % 1000L == 0L) {
                    "${intervalMs / 1000L} 秒"
                } else {
                    String.format(Locale.getDefault(), "%.1f 秒", intervalMs / 1000.0)
                }
            }
        }
    }

    /**
     * 验证自定义输入秒数（支持 1 位小数）转换为毫秒数的精确性。
     */
    @Test
    fun testCustomSecondsToMillisecondsConversion() {
        val testInputs = listOf(
            0.5 to 500L,
            1.0 to 1000L,
            1.5 to 1500L,
            3.2 to 3200L,
            10.0 to 10000L,
            30.5 to 30500L
        )

        for ((inputSec, expectedMs) in testInputs) {
            val roundedSec = Math.round(inputSec * 10.0) / 10.0
            val actualMs = Math.round(roundedSec * 1000.0)
            assertEquals("秒数转毫秒精度不匹配", expectedMs, actualMs)
        }
    }

    /**
     * 验证刷新间隔文案格式化展示（包括预设档位与一位小数自定义档位）。
     */
    @Test
    fun testFormatIntervalDisplay() {
        assertEquals("不采样", formatInterval(-1L))
        assertEquals("智能省电", formatInterval(0L))
        assertEquals("1 秒", formatInterval(1000L))
        assertEquals("3 秒", formatInterval(3000L))
        assertEquals("5 秒", formatInterval(5000L))
        assertEquals("10 秒", formatInterval(10000L))
        assertEquals("30 秒", formatInterval(30000L))
        assertEquals("1.5 秒", formatInterval(1500L))
        assertEquals("0.5 秒", formatInterval(500L))
        assertEquals("2.8 秒", formatInterval(2800L))
    }
}
