package com.battery.analysis

import com.battery.analysis.model.PowerUsageRecord
import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 耗电历史记录实体单元测试。
 * 验证起止时间范围格式化输出（同日、同年跨天去掉年份、跨年保留完整年份）逻辑。
 */
class PowerUsageRecordTest {

    /**
     * 测试耗电历史记录起止时间范围格式化输出：
     * 1. 起止同一天：仅展示一次日期（如 2026/09/24 12:02~12:10）；
     * 2. 起止同年跨天：结束时间省略年份（如 2026/09/23 19:50~09/24 09:28）；
     * 3. 起止跨年：结束时间保留完整年份（如 2025/12/31 23:00~2026/01/01 01:00）。
     */
    @Test
    fun testPowerUsageRecordFormattedTimeRange() {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        // 1. 同一天起止：yyyy/MM/dd HH:mm~HH:mm
        val sameDayStart = sdf.parse("2026-09-24 12:02:00")!!.time
        val sameDayEnd = sdf.parse("2026-09-24 12:10:00")!!.time
        val trendPointsSameDay = """[{"ts":$sameDayStart,"lvl":100},{"ts":$sameDayEnd,"lvl":42}]"""
        val recordSameDay = PowerUsageRecord(
            id = sameDayStart,
            recordTime = "2026-09-24 12:10:00",
            levelPercent = 42,
            voltageVolts = 4.0f,
            temperature = 30f,
            energyWh = 10f,
            isCharging = false,
            avgPowerWatts = 2.03f,
            screenOnPowerWatts = 2.03f,
            screenOffPowerWatts = 0.5f,
            screenOnDurationText = "8m",
            screenOffDurationText = "0m",
            totalDurationText = "8m",
            remainingScreenOnText = "5h",
            remainingCompositeText = "10h",
            remainingScreenOffText = "24h",
            isShizukuRealData = false,
            appCount = 0,
            trendPointsJson = trendPointsSameDay,
            appListJson = "[]"
        )
        assertEquals("2026/09/24 12:02~12:10", recordSameDay.getFormattedTimeRange())

        // 2. 同年跨天起止：yyyy/MM/dd HH:mm~MM/dd HH:mm（省略结束时间年份）
        val crossDayStart = sdf.parse("2026-09-23 19:50:00")!!.time
        val crossDayEnd = sdf.parse("2026-09-24 09:28:00")!!.time
        val trendPointsCrossDay = """[{"ts":$crossDayStart,"lvl":90},{"ts":$crossDayEnd,"lvl":39}]"""
        val recordCrossDay = recordSameDay.copy(
            id = crossDayStart,
            recordTime = "2026-09-24 09:28:00",
            trendPointsJson = trendPointsCrossDay
        )
        assertEquals("2026/09/23 19:50~09/24 09:28", recordCrossDay.getFormattedTimeRange())

        // 3. 跨年起止：yyyy/MM/dd HH:mm~yyyy/MM/dd HH:mm（保留完整年份）
        val crossYearStart = sdf.parse("2025-12-31 23:30:00")!!.time
        val crossYearEnd = sdf.parse("2026-01-01 01:15:00")!!.time
        val trendPointsCrossYear = """[{"ts":$crossYearStart,"lvl":80},{"ts":$crossYearEnd,"lvl":70}]"""
        val recordCrossYear = recordSameDay.copy(
            id = crossYearStart,
            recordTime = "2026-01-01 01:15:00",
            trendPointsJson = trendPointsCrossYear
        )
        assertEquals("2025/12/31 23:30~2026/01/01 01:15", recordCrossYear.getFormattedTimeRange())
    }
}
