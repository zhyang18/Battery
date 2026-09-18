package com.battery.analysis.timeline

import com.battery.analysis.timeline.domain.AppTimelineEvent
import com.battery.analysis.timeline.domain.ConfidenceLevel
import com.battery.analysis.timeline.domain.EnergySource
import com.battery.analysis.timeline.util.TimelineLayoutCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时间轴槽位排布算法单元测试套件。
 * 验证应用图标槽位在 0dp 间距模式下的无缝贴合特性，以及时序末尾槽位的连续覆盖能力。
 */
class TimelineLayoutCalculatorTest {

    /**
     * 辅助构造应用时间轴测试事件。
     *
     * @param pkg 应用包名
     * @param start 起始时间戳（毫秒）
     * @param end 结束时间戳（毫秒）
     * @return 构造的应用时间轴事件实例 [AppTimelineEvent]
     */
    private fun createTestEvent(pkg: String, start: Long, end: Long): AppTimelineEvent {
        return AppTimelineEvent(
            packageName = pkg,
            uid = 10001,
            appName = "测试应用",
            icon = null,
            startTime = start,
            endTime = end,
            durationMs = end - start,
            screenOn = true,
            energyMwh = 10.0,
            averagePowerMw = 1500.0,
            peakPowerMw = 2000.0,
            cpuTimeMs = 100L,
            networkBytes = 1024L,
            wakelockTimeMs = 0L,
            gpsTimeMs = 0L,
            confidence = ConfidenceLevel.HIGH,
            source = EnergySource.BATTERY_STATS
        )
    }

    /**
     * 验证当 slotGapPx 设定为 0dp 时，相邻图标槽位的右边缘与左边缘无缝紧贴，水平间距严格为 0dp。
     */
    @Test
    fun testZeroGapSlotLayout() {
        val startTs = 1000L
        val endTs = 60000L
        val event = createTestEvent("com.battery.analysis", startTs, endTs)

        val slotSize = 30f
        val leftMargin = 10f
        val canvasWidth = 300f

        val items = TimelineLayoutCalculator.calculateSlotItems(
            events = listOf(event),
            visibleStartTs = startTs,
            visibleEndTs = endTs,
            canvasWidth = canvasWidth,
            baseBottomY = 100f,
            slotSizePx = slotSize,
            slotGapPx = 0f,
            rowGapPx = 0f,
            leftMarginPx = leftMargin
        )

        assertTrue("在全时间跨度活跃时应生成槽位项", items.isNotEmpty())

        // 验证每一个相邻时间槽之间右左坐标严格相等（0dp 间距）
        for (i in 0 until items.size - 1) {
            val current = items[i]
            val next = items[i + 1]
            assertEquals("相邻槽位间距必须为 0dp（当前右边界等于下一槽位左边界）", current.right, next.left, 0.001f)
        }
    }

    /**
     * 验证当应用持续活跃至时序末尾（如 16:43:59）时，最后一个时间槽正确捕获并生成 App 徽章项。
     */
    @Test
    fun testEndTimeSlotCoverage() {
        val startTs = 1758184980000L // 16:43:00
        val endTs = 1758185039000L   // 16:43:59
        val event = createTestEvent("com.battery.analysis", startTs, endTs)

        val items = TimelineLayoutCalculator.calculateSlotItems(
            events = listOf(event),
            visibleStartTs = startTs,
            visibleEndTs = endTs,
            canvasWidth = 330f,
            baseBottomY = 100f,
            slotSizePx = 30f,
            slotGapPx = 0f,
            rowGapPx = 0f,
            leftMarginPx = 14f
        )

        assertTrue("在覆盖全程时槽位列表不为空", items.isNotEmpty())
        val lastItem = items.last()
        assertTrue("最后一个时间槽必须有效覆盖且属于目标应用", lastItem.event.packageName == "com.battery.analysis")
    }
}
