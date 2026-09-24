package com.battery.analysis

import com.battery.analysis.model.ChargingHistoryRecord
import com.battery.analysis.model.ChargingSamplePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 充电历史记录实体与采样点序列化、平滑点自愈补齐单元测试。
 */
class ChargingHistoryRecordTest {

    /**
     * 测试采样点列表序列化为 JSON 以及从 JSON 反序列化解析的正确性。
     */
    @Test
    fun testSamplePointsSerializationAndDeserialization() {
        val points = listOf(
            ChargingSamplePoint(
                timestamp = 1700000000000L,
                powerWatts = 18.5f,
                batteryLevel = 30,
                temperature = 32.0f,
                voltageVolts = 4.1f,
                currentMa = 4500f
            ),
            ChargingSamplePoint(
                timestamp = 1700000060000L,
                powerWatts = 22.0f,
                batteryLevel = 35,
                temperature = 33.5f,
                voltageVolts = 4.2f,
                currentMa = 5200f
            )
        )

        val json = ChargingHistoryRecord.pointsToJson(points)
        assertTrue(json.isNotEmpty())

        val record = ChargingHistoryRecord(
            id = 1700000060000L,
            recordTime = "2026-09-09 22:30:00",
            startTimestamp = 1700000000000L,
            endTimestamp = 1700000060000L,
            durationMs = 60000L,
            startLevel = 30,
            endLevel = 35,
            levelGain = 5,
            chargedEnergyWh = 2.5f,
            avgPowerWatts = 20.0f,
            maxPowerWatts = 22.0f,
            maxTemperature = 33.5f,
            chargeType = "交流快充",
            samplePointsJson = json
        )

        val parsedPoints = record.getSamplePoints()
        assertEquals(2, parsedPoints.size)
        assertEquals(18.5f, parsedPoints[0].powerWatts, 0.01f)
        assertEquals(30, parsedPoints[0].batteryLevel)
        assertTrue(parsedPoints[0].isScreenOn)
        assertEquals(22.0f, parsedPoints[1].powerWatts, 0.01f)
        assertEquals(35, parsedPoints[1].batteryLevel)
        assertTrue(parsedPoints[1].isScreenOn)
    }

    /**
     * 测试历史老数据（无采样点 JSON）如实返回空列表，不伪造假采样点。
     */
    @Test
    fun testLegacyRecordPointsNoSynthesizedPoints() {
        val legacyRecord = ChargingHistoryRecord(
            id = 1700000060000L,
            recordTime = "2026-09-09 22:30:00",
            startTimestamp = 1700000000000L,
            endTimestamp = 1700000600000L,
            durationMs = 600000L,
            startLevel = 20,
            endLevel = 60,
            levelGain = 40,
            chargedEnergyWh = 8.5f,
            avgPowerWatts = 15.68f,
            maxPowerWatts = 30.0f,
            maxTemperature = 38.0f,
            chargeType = "交流快充",
            samplePointsJson = "" // 历史数据无点
        )

        val points = legacyRecord.getSamplePoints()
        assertTrue(points.isEmpty())
    }

    /**
     * 测试梯形微元数值微积分在长周期充电过程中的累计充入能量与平均功率计算。
     */
    @Test
    fun testTrapezoidalEnergyIntegrationAndAveragePower() {
        val startTs = 1700000000000L
        val points = mutableListOf<ChargingSamplePoint>()
        val totalMinutes = 86 // 1h26m
        val durationMs = totalMinutes * 60 * 1000L
        val avgPowerW = 4.7f

        // 模拟 1 小时 26 分钟的采样数据
        for (i in 0..totalMinutes) {
            val ts = startTs + i * 60 * 1000L
            val level = 41 + (25 * i / totalMinutes)
            points.add(
                ChargingSamplePoint(
                    timestamp = ts,
                    powerWatts = avgPowerW,
                    batteryLevel = level,
                    temperature = 33.0f,
                    voltageVolts = 3.9f,
                    currentMa = (avgPowerW * 1000f / 3.9f)
                )
            )
        }

        // 计算微元积分累计能量
        var integratedEnergyWh = 0.0
        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]
            val dtHours = (p2.timestamp - p1.timestamp).coerceAtLeast(0L) / 3600000.0
            val avgSlicePower = (p1.powerWatts + p2.powerWatts) / 2.0
            integratedEnergyWh += avgSlicePower * dtHours
        }

        val durationHours = durationMs / 3600000.0f
        val calculatedAvgPower = (integratedEnergyWh / durationHours).toFloat()

        // 验证 1h26m 以 4.7W 充电得到的能量约为 6.73 Wh
        assertEquals(6.737f, integratedEnergyWh.toFloat(), 0.05f)
        assertEquals(4.7f, calculatedAvgPower, 0.05f)
    }

    /**
     * 测试当采样点序列进行全局抽稀时，时间轴跨度保持完整（首尾时间戳不丢失）。
     */
    @Test
    fun testGlobalTimelineDecimationPreservesSpan() {
        val startTs = 1700000000000L
        val originalPoints = mutableListOf<ChargingSamplePoint>()
        for (i in 0 until 6000) {
            originalPoints.add(
                ChargingSamplePoint(
                    timestamp = startTs + i * 1000L,
                    powerWatts = 5.0f,
                    batteryLevel = 50 + (i / 600),
                    temperature = 30f,
                    voltageVolts = 4.0f,
                    currentMa = 1250f
                )
            )
        }

        // 模拟抽稀逻辑
        val downsampled = mutableListOf<ChargingSamplePoint>()
        downsampled.add(originalPoints.first())
        for (i in 1 until originalPoints.size - 1 step 2) {
            downsampled.add(originalPoints[i])
        }
        downsampled.add(originalPoints.last())

        assertEquals(originalPoints.first().timestamp, downsampled.first().timestamp)
        assertEquals(originalPoints.last().timestamp, downsampled.last().timestamp)
        assertTrue(downsampled.size < originalPoints.size)
    }

    /**
     * 测试充电过程中出现的放电尖峰（负功率，如 -12.3W）不污染峰值充电功率与充入能量。
     */
    @Test
    fun testDischargeSpikesDoNotContaminateMaxChargingPower() {
        val points = listOf(
            ChargingSamplePoint(timestamp = 1000L, powerWatts = 5.0f, batteryLevel = 50, temperature = 30f, voltageVolts = 4.0f, currentMa = 1250f),
            ChargingSamplePoint(timestamp = 2000L, powerWatts = -12.3f, batteryLevel = 50, temperature = 30.5f, voltageVolts = 3.9f, currentMa = -3150f),
            ChargingSamplePoint(timestamp = 3000L, powerWatts = 8.0f, batteryLevel = 51, temperature = 31f, voltageVolts = 4.1f, currentMa = 1950f)
        )

        // 验证峰值充电功率仅统计正向功率 (>0)
        var maxP = 0f
        for (p in points) {
            if (p.powerWatts > maxP && p.powerWatts > 0f) {
                maxP = p.powerWatts
            }
        }
        assertEquals(8.0f, maxP, 0.01f)

        // 验证放电切片不计入充入能量微积分
        var positiveEnergyWh = 0.0
        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]
            val dtHours = (p2.timestamp - p1.timestamp).coerceAtLeast(0L) / 3600000.0
            val avgSlicePower = (p1.powerWatts.coerceAtLeast(0f) + p2.powerWatts.coerceAtLeast(0f)) / 2.0
            positiveEnergyWh += avgSlicePower * dtHours
        }
        assertTrue(positiveEnergyWh > 0.0)
    }

    /**
     * 测试亮屏充电时长的物理差值计算及多时段友好文本格式化输出。
     */
    @Test
    fun testScreenOnDurationCalculationAndFormatting() {
        val record1 = ChargingHistoryRecord(
            id = 1700000000000L,
            recordTime = "2026-09-22 17:00:00",
            startTimestamp = 1700000000000L,
            endTimestamp = 1700000430000L,
            durationMs = 430000L,
            startLevel = 50,
            endLevel = 55,
            levelGain = 5,
            chargedEnergyWh = 2.0f,
            avgPowerWatts = 18.0f,
            maxPowerWatts = 20.0f,
            maxTemperature = 35.0f,
            chargeType = "交流快充",
            screenOffDurationMs = 0L
        )
        assertEquals(430000L, record1.getScreenOnDurationMs())
        assertEquals("7m10s", record1.getFormattedScreenOnDuration())

        val record2 = record1.copy(
            durationMs = 5130000L,
            screenOffDurationMs = 1210000L
        )
        assertEquals(3920000L, record2.getScreenOnDurationMs())
        assertEquals("1h5m20s", record2.getFormattedScreenOnDuration())
    }

    /**
     * 测试充电起止时间范围格式化输出（同日仅显一次日期、同年跨天省略结束年份、跨年保留双年份）。
     */
    @Test
    fun testFormattedTimeRangeFormat() {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())

        // 1. 同一天起止：yyyy/MM/dd HH:mm~HH:mm
        val sameDayStart = sdf.parse("2026-09-24 12:02:00")!!.time
        val sameDayEnd = sdf.parse("2026-09-24 12:10:00")!!.time
        val recordSameDay = ChargingHistoryRecord(
            id = sameDayEnd,
            recordTime = "2026-09-24 12:10:00",
            startTimestamp = sameDayStart,
            endTimestamp = sameDayEnd,
            durationMs = sameDayEnd - sameDayStart,
            startLevel = 40,
            endLevel = 50,
            levelGain = 10,
            chargedEnergyWh = 5f,
            avgPowerWatts = 15f,
            maxPowerWatts = 18f,
            maxTemperature = 35f,
            chargeType = "交流快充"
        )
        assertEquals("2026/09/24 12:02~12:10", recordSameDay.getFormattedTimeRange())

        // 2. 同年跨天起止：yyyy/MM/dd HH:mm~MM/dd HH:mm（省略结束时间年份）
        val crossDayStart = sdf.parse("2026-09-23 19:50:00")!!.time
        val crossDayEnd = sdf.parse("2026-09-24 09:28:00")!!.time
        val recordCrossDay = recordSameDay.copy(
            startTimestamp = crossDayStart,
            endTimestamp = crossDayEnd,
            durationMs = crossDayEnd - crossDayStart
        )
        assertEquals("2026/09/23 19:50~09/24 09:28", recordCrossDay.getFormattedTimeRange())

        // 3. 跨年起止：yyyy/MM/dd HH:mm~yyyy/MM/dd HH:mm（保留完整年份）
        val crossYearStart = sdf.parse("2025-12-31 23:30:00")!!.time
        val crossYearEnd = sdf.parse("2026-01-01 01:15:00")!!.time
        val recordCrossYear = recordSameDay.copy(
            startTimestamp = crossYearStart,
            endTimestamp = crossYearEnd,
            durationMs = crossYearEnd - crossYearStart
        )
        assertEquals("2025/12/31 23:30~2026/01/01 01:15", recordCrossYear.getFormattedTimeRange())
    }
}

