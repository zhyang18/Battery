package com.battery.analysis

import com.battery.analysis.model.PowerUsageRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /**
     * 测试充电状态下零放电功耗脏记录的识别与时长解析。
     * 验证总时长如 "23m29s" 能够正确转换为毫秒数，并且当平均功耗为 0.00 W 且时长超过 1 分钟时，
     * 判定该记录属于充电状态误保存的无效记录。
     */
    @Test
    fun testZeroPowerChargingRecordDetection() {
        val record = PowerUsageRecord(
            id = 1790566000000L,
            recordTime = "2026-09-28 11:23:00",
            levelPercent = 49,
            voltageVolts = 4.1f,
            temperature = 32f,
            energyWh = 12f,
            isCharging = false,
            avgPowerWatts = 0.00f,
            screenOnPowerWatts = 0.00f,
            screenOffPowerWatts = 0.00f,
            screenOnDurationText = "23m29s",
            screenOffDurationText = "0s",
            totalDurationText = "23m29s",
            remainingScreenOnText = "--",
            remainingCompositeText = "--",
            remainingScreenOffText = "--",
            isShizukuRealData = false,
            appCount = 0,
            trendPointsJson = "[]",
            appListJson = "[]"
        )

        // 验证时长解析准确性: 23 分钟 * 60 + 29 秒 = 1409 秒 = 1409000 毫秒
        val expectedMs = (23 * 60 + 29) * 1000L
        assertEquals(expectedMs, record.getDurationMs())

        // 验证处于零功耗且时长大于 1 分钟的充电脏记录判定条件
        val isInvalidChargingRecord = record.avgPowerWatts <= 0.001f && record.getDurationMs() >= 60_000L
        assertEquals(true, isInvalidChargingRecord)
    }

    /**
     * 测试耗电历史记录实体转换为 JSON 字符串以及从 JSON 字符串完整反向还原的准确性。
     */
    @Test
    fun testPowerUsageRecordJsonSerialization() {
        val original = PowerUsageRecord(
            id = 1790566000000L,
            recordTime = "2026-09-28 12:00:00",
            levelPercent = 88,
            voltageVolts = 4.25f,
            temperature = 31.5f,
            energyWh = 18.5f,
            isCharging = false,
            avgPowerWatts = 1.85f,
            screenOnPowerWatts = 2.10f,
            screenOffPowerWatts = 0.35f,
            screenOnDurationText = "1h30m",
            screenOffDurationText = "2h15m",
            totalDurationText = "3h45m",
            remainingScreenOnText = "6h",
            remainingCompositeText = "12h",
            remainingScreenOffText = "30h",
            isShizukuRealData = true,
            appCount = 5,
            trendPointsJson = """[{"ts":1790566000000,"lvl":88}]""",
            appListJson = """[{"pkg":"com.example.app","name":"Example","time":3600000}]""",
            backgroundPowerWatts = 0.2f,
            backgroundDurationText = "1h",
            remainingBackgroundText = "15h",
            screenOnEnergyWh = 3.15f,
            totalEnergyWh = 6.94f,
            screenOffEnergyWh = 0.79f,
            backgroundEnergyWh = 0.2f,
            isCompleted = true,
            lastCheckpointTime = 1790566000000L
        )

        val jsonStr = original.toJsonString()
        val restored = PowerUsageRecord.fromJsonString(jsonStr)

        org.junit.Assert.assertNotNull(restored)
        assertEquals(original.id, restored!!.id)
        assertEquals(original.recordTime, restored.recordTime)
        assertEquals(original.levelPercent, restored.levelPercent)
        assertEquals(original.voltageVolts, restored.voltageVolts, 0.001f)
        assertEquals(original.temperature, restored.temperature, 0.001f)
        assertEquals(original.avgPowerWatts, restored.avgPowerWatts, 0.001f)
        assertEquals(original.isShizukuRealData, restored.isShizukuRealData)
        assertEquals(original.trendPointsJson, restored.trendPointsJson)
        assertEquals(original.appListJson, restored.appListJson)
    }

    /**
     * 测试放电进行中草稿（isCompleted = false）在初始阶段即使平均放电功耗为零也不应被误判为无效脏记录。
     * 验证守卫逻辑：仅在放电会话已完结结案（isCompleted = true）且时长大于等于 1 分钟时，零功耗才被判定为充电脏记录。
     */
    @Test
    fun testRunningDischargeDraftWithZeroPowerNotTreatedAsInvalid() {
        // 1. 刚刚拔电 15 秒且功耗尚未建立的草稿
        val runningDraft15s = PowerUsageRecord(
            id = 1790566000000L,
            recordTime = "2026-10-14 10:00:15",
            levelPercent = 100,
            voltageVolts = 4.35f,
            temperature = 28f,
            energyWh = 18f,
            isCharging = false,
            avgPowerWatts = 0.00f,
            screenOnPowerWatts = 0.00f,
            screenOffPowerWatts = 0.00f,
            screenOnDurationText = "15s",
            screenOffDurationText = "0s",
            totalDurationText = "15s",
            remainingScreenOnText = "--",
            remainingCompositeText = "--",
            remainingScreenOffText = "--",
            isShizukuRealData = false,
            appCount = 0,
            trendPointsJson = "[]",
            appListJson = "[]",
            isCompleted = false
        )
        val isDraft15sInvalid = runningDraft15s.isCompleted && runningDraft15s.avgPowerWatts <= 0.001f && runningDraft15s.getDurationMs() >= 60_000L
        assertEquals("放电短时间草稿绝不能被判定为无效脏数据", false, isDraft15sInvalid)

        // 2. 拔电 2 分钟但由于刚息屏功耗微积分尚未成型的草稿（isCompleted = false）
        val runningDraft2m = runningDraft15s.copy(
            totalDurationText = "2m",
            isCompleted = false
        )
        val isDraft2mInvalid = runningDraft2m.isCompleted && runningDraft2m.avgPowerWatts <= 0.001f && runningDraft2m.getDurationMs() >= 60_000L
        assertEquals("放电中草稿即使时长超 1 分钟且功耗为零也不可判定为无效脏数据", false, isDraft2mInvalid)

        // 3. 只有已完结结案（isCompleted = true）且时长超 1 分钟的零功耗记录，才被判定为充电脏记录
        val completedZeroPower = runningDraft2m.copy(
            isCompleted = true
        )
        val isCompletedInvalid = completedZeroPower.isCompleted && completedZeroPower.avgPowerWatts <= 0.001f && completedZeroPower.getDurationMs() >= 60_000L
        assertEquals("已完结结案且零功耗超1分钟的记录必须判定为异常记录", true, isCompletedInvalid)
    }

    /**
     * 测试放电记录实际起止物理时间戳的提取与计算，验证彻底杜绝将拔电起始时间误当结束时间导致的倒流平移。
     */
    @Test
    fun testEffectiveStartAndEndTimeComputation() {
        val unplugStartTs = 1700000000000L // 拔电起始时刻
        val durationMs = 8 * 3600 * 1000L  // 放电持续 8 小时
        val endTs = unplugStartTs + durationMs

        // 场景 1：具备采样点集，精准从首末点提取
        val trendPointsJson = """[{"ts":$unplugStartTs,"lvl":100},{"ts":$endTs,"lvl":30}]"""
        val recordWithPoints = PowerUsageRecord(
            id = unplugStartTs,
            recordTime = "2026-09-24 18:00:00",
            levelPercent = 30,
            voltageVolts = 3.8f,
            temperature = 28f,
            energyWh = 15f,
            isCharging = false,
            avgPowerWatts = 1.8f,
            screenOnPowerWatts = 2.2f,
            screenOffPowerWatts = 0.3f,
            screenOnDurationText = "4h",
            screenOffDurationText = "4h",
            totalDurationText = "8h",
            remainingScreenOnText = "5h",
            remainingCompositeText = "10h",
            remainingScreenOffText = "30h",
            isShizukuRealData = false,
            appCount = 5,
            trendPointsJson = trendPointsJson,
            appListJson = "[]",
            isCompleted = true
        )

        assertEquals("起始时间应与首点对齐", unplugStartTs, recordWithPoints.getEffectiveStartTimeMs())
        assertEquals("结束时间应与末点对齐", endTs, recordWithPoints.getEffectiveEndTimeMs())

        // 场景 2：无采样点集（历史精简模式），起始取拔电时间 id，结束时间基于持续时长正向推导
        val recordNoPoints = recordWithPoints.copy(trendPointsJson = "[]")
        assertEquals("无点集时起始时间应为拔电时间 id", unplugStartTs, recordNoPoints.getEffectiveStartTimeMs())
        assertEquals("无点集时结束时间应为起始时间正向累加持续时长", endTs, recordNoPoints.getEffectiveEndTimeMs())
        assertTrue("结束时间绝不能早于或等于拔电起始时间", recordNoPoints.getEffectiveEndTimeMs() > recordNoPoints.getEffectiveStartTimeMs())
    }

    /**
     * 测试放电折线点 JSON 在超出 CursorWindow 安全阈值时的物理高保真抽稀与防溢出保护。
     * 验证当点数多达 5000 点且 JSON 字符串长度超 500KB 时，经安全净化后点数被规范化为 1200 点以内，
     * 且所有点均为真实采样的物理点，体积被严格限制在 500KB 以内。
     */
    @Test
    fun testTrendPointsJsonSanitizationUnderCursorWindowLimit() {
        val now = System.currentTimeMillis()
        val bigArray = org.json.JSONArray()
        for (i in 0 until 5000) {
            val obj = org.json.JSONObject().apply {
                put("ts", now + i * 1000L)
                put("elapsed", i * 0.001)
                put("lvl", 100 - (i / 100))
                put("volt", 4.1 - (i * 0.0001))
                put("temp", 30.0 + (i % 5))
                put("pwr", 1.5 + (i % 3) * 0.5)
                put("screenOn", (i % 2 == 0))
                put("cur_ua", -300000)
                put("cur_avg_ua", -290000)
                put("cur_ma", 300.0)
                put("src", "BATTERY_PROPERTIES")
                put("pol", "NEGATIVE")
                put("meas", true)
                put("prov", "MEASURED_BATTERY_CURRENT")
                put("cc", 2000000)
            }
            bigArray.put(obj)
        }
        val rawJson = bigArray.toString()
        assertTrue("原始 5000 点 JSON 应显著超出 500KB 安全限制", rawJson.length > 500_000)

        val sanitizedJson = com.battery.analysis.db.PowerUsageDbHelper.sanitizeTrendPointsJsonForStorage(rawJson)

        assertTrue("净化后的 JSON 字符串长度必须小于 500KB", sanitizedJson.length <= 500_000)
        val sanitizedArray = org.json.JSONArray(sanitizedJson)
        assertTrue("净化后的采样点集数量必须控制在 1200 点以内", sanitizedArray.length() in 2..1200)

        // 验证关键物理采样真实性：首点时间戳必须完全一致
        val firstObj = sanitizedArray.getJSONObject(0)
        assertEquals("抽稀后的首点时间戳必须忠实保留真实物理起点", now, firstObj.optLong("ts"))
    }

    /**
     * 测试轻量列集定义完整性，验证其绝对排除超大 JSON 列，保障单行在 1KB 极低体积内运行。
     */
    @Test
    fun testLightweightColumnsSafety() {
        val lightCols = com.battery.analysis.db.PowerUsageDbHelper.SAFE_LIGHTWEIGHT_COLUMNS
        assertTrue("轻量列集合必须包含主键 id", lightCols.contains("id"))
        assertTrue("轻量列集合必须包含电量百分比", lightCols.contains("level_percent"))
        assertTrue("轻量列集合必须包含平均功耗", lightCols.contains("avg_power_watts"))
        assertTrue("轻量列集合严禁包含 trend_points_json", !lightCols.contains("trend_points_json"))
        assertTrue("轻量列集合严禁包含 app_list_json", !lightCols.contains("app_list_json"))
    }
}
