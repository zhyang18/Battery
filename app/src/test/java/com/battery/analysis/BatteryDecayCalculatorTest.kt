package com.battery.analysis

import com.battery.analysis.model.HealthTrendPoint
import com.battery.analysis.util.BatteryDecayCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 电池健康度衰减计算器单元测试套件。
 * 验证空数据、单点、跨天多点、月度及年度衰减速率推算与周期分组算法的正确性。
 */
class BatteryDecayCalculatorTest {

    /**
     * 测试空列表输入时返回默认空统计结果。
     */
    @Test
    fun testEmptyPoints() {
        val stats = BatteryDecayCalculator.calculate(emptyList())
        assertEquals(0, stats.totalPoints)
        assertFalse(stats.hasSufficientData)
        assertNull(stats.dailyDecayRate)
        assertNull(stats.monthlyDecayRate)
        assertNull(stats.yearlyDecayRate)
        assertEquals("--", stats.formatDailyDecay())
    }

    /**
     * 测试仅有单条记录时无法推算速率，但保留基本信息与周期项。
     */
    @Test
    fun testSinglePoint() {
        val points = listOf(
            HealthTrendPoint(
                id = 1L,
                captureTime = "2026-08-19 10:00:00",
                displayTime = "08-19 10:00",
                health = 98.5f,
                cycleCount = 100
            )
        )
        val stats = BatteryDecayCalculator.calculate(points)
        assertEquals(1, stats.totalPoints)
        assertFalse(stats.hasSufficientData)
        assertNull(stats.dailyDecayRate)
        assertEquals(1, stats.dailyItems.size)
        assertEquals(1, stats.monthlyItems.size)
        assertEquals(1, stats.yearlyItems.size)
    }

    /**
     * 测试跨越 10 天、健康度从 100% 下降至 99% 的标准衰减速率计算。
     */
    @Test
    fun testMultiPointsDecayCalculation() {
        val points = listOf(
            HealthTrendPoint(
                id = 1L,
                captureTime = "2026-08-01 12:00:00",
                displayTime = "08-01 12:00",
                health = 100.0f,
                cycleCount = 50
            ),
            HealthTrendPoint(
                id = 2L,
                captureTime = "2026-08-06 12:00:00",
                displayTime = "08-06 12:00",
                health = 99.5f,
                cycleCount = 55
            ),
            HealthTrendPoint(
                id = 3L,
                captureTime = "2026-08-11 12:00:00",
                displayTime = "08-11 12:00",
                health = 99.0f,
                cycleCount = 60
            )
        )

        val stats = BatteryDecayCalculator.calculate(points)
        assertTrue(stats.hasSufficientData)
        assertEquals(3, stats.totalPoints)
        assertEquals(10.0, stats.spanDays, 0.05)
        assertEquals(1.0f, stats.totalDecay, 0.001f)

        // 每日衰减 = 1.0% / 10天 = 0.1%/天
        assertNotNull(stats.dailyDecayRate)
        assertEquals(0.1f, stats.dailyDecayRate!!, 0.01f)

        // 每月衰减 = 0.1 * 30.4375 ≈ 3.04%/月
        assertNotNull(stats.monthlyDecayRate)
        assertEquals(3.04f, stats.monthlyDecayRate!!, 0.1f)

        // 每年预估 = 0.1 * 365.25 ≈ 36.53%/年
        assertNotNull(stats.yearlyDecayRate)
        assertEquals(36.53f, stats.yearlyDecayRate!!, 0.2f)

        // 周期分组验证
        assertEquals(3, stats.dailyItems.size)
        assertEquals(1, stats.monthlyItems.size)
        val monthItem = stats.monthlyItems[0]
        assertEquals("2026年08月", monthItem.periodLabel)
        assertEquals(100.0f, monthItem.startHealth, 0.01f)
        assertEquals(99.0f, monthItem.endHealth, 0.01f)
        assertEquals(1.0f, monthItem.decay, 0.01f)
        assertEquals(3, monthItem.sampleCount)
        assertEquals(10, monthItem.cycleChange)
    }

    /**
     * 测试跨月度与跨年度的分组聚合逻辑。
     */
    @Test
    fun testMultiYearAndMonthGrouping() {
        val points = listOf(
            HealthTrendPoint(
                id = 1L,
                captureTime = "2025-12-31 23:00:00",
                displayTime = "12-31 23:00",
                health = 100.0f,
                cycleCount = 10
            ),
            HealthTrendPoint(
                id = 2L,
                captureTime = "2026-01-15 10:00:00",
                displayTime = "01-15 10:00",
                health = 99.2f,
                cycleCount = 25
            ),
            HealthTrendPoint(
                id = 3L,
                captureTime = "2026-02-10 10:00:00",
                displayTime = "02-10 10:00",
                health = 98.5f,
                cycleCount = 40
            )
        )

        val stats = BatteryDecayCalculator.calculate(points)
        assertTrue(stats.hasSufficientData)

        // 月度分组应包含 3 个月（按倒序排：2026-02, 2026-01, 2025-12）
        assertEquals(3, stats.monthlyItems.size)
        assertEquals("2026年02月", stats.monthlyItems[0].periodLabel)
        assertEquals("2026年01月", stats.monthlyItems[1].periodLabel)
        assertEquals("2025年12月", stats.monthlyItems[2].periodLabel)

        // 年度分组应包含 2 个年份（2026年度, 2025年度）
        assertEquals(2, stats.yearlyItems.size)
        assertEquals("2026年度", stats.yearlyItems[0].periodLabel)
        assertEquals("2025年度", stats.yearlyItems[1].periodLabel)
        assertEquals(99.2f, stats.yearlyItems[0].startHealth, 0.01f)
        assertEquals(98.5f, stats.yearlyItems[0].endHealth, 0.01f)
        assertEquals(0.7f, stats.yearlyItems[0].decay, 0.01f)
    }
}
