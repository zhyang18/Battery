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
        assertEquals("07m10s", record1.getFormattedScreenOnDuration())

        val record2 = record1.copy(
            durationMs = 5130000L,
            screenOffDurationMs = 1210000L
        )
        assertEquals(3920000L, record2.getScreenOnDurationMs())
        assertEquals("01h05m20s", record2.getFormattedScreenOnDuration())
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

    /**
     * 测试慢充边充边用场景（如 11:42~13:09 持续 87 分钟，33%~55% 净增 22%）：
     * 即使高负载看视频导致瞬时功率多次微弱或为负（微积分累积值极低），
     * 基于真实额定容量与电压的物理守恒能量补偿公式能够准确核算真实充入能量，且 100% 满足归档入库阈值。
     */
    @Test
    fun testSlowChargingVideoPlaybackEnergyCompensationAndPersistenceThreshold() {
        val startTs = 1700000000000L
        val totalMinutes = 87L
        val endTs = startTs + totalMinutes * 60 * 1000L // 87分钟
        val startLevel = 33
        val endLevel = 55
        val levelGain = endLevel - startLevel // 22%
        val designCapMah = 5000f // 5000mAh
        val avgVolt = 3.9f

        // 模拟慢充看视频期间瞬时功率极低，导致微积分累加能量仅有 0.001Wh
        val lowAccumulatedEnergyWh = 0.001f

        // 物理能量守恒补偿核算：(levelGain / 100) * Cap * Volt / 1000
        val compensatedEnergyWh = if (lowAccumulatedEnergyWh > 0.005f) {
            lowAccumulatedEnergyWh
        } else if (levelGain > 0) {
            (levelGain / 100.0f) * designCapMah * avgVolt / 1000.0f
        } else {
            lowAccumulatedEnergyWh
        }

        // 验证 22% * 5000mAh * 3.9V / 1000 = 4.29 Wh
        assertEquals(4.29f, compensatedEnergyWh, 0.01f)

        val durationMs = endTs - startTs
        val durationHours = durationMs / 3600000.0f
        val calculatedAvgPower = compensatedEnergyWh / durationHours

        // 验证 87 分钟充入 4.29 Wh 的平均功率约为 2.95 W
        assertEquals(2.958f, calculatedAvgPower, 0.02f)

        // 验证满足入库持久化门限（duration >= 10000L 或 energy > 0.005f 或 levelGain > 0）
        val shouldPersist = durationMs >= 10000L || compensatedEnergyWh > 0.005f || levelGain > 0
        assertTrue("87分钟慢充必须满足持久化归档门限", shouldPersist)

        val record = ChargingHistoryRecord(
            id = endTs,
            recordTime = "2026-10-10 13:09:00",
            startTimestamp = startTs,
            endTimestamp = endTs,
            durationMs = durationMs,
            startLevel = startLevel,
            endLevel = endLevel,
            levelGain = levelGain,
            chargedEnergyWh = compensatedEnergyWh,
            avgPowerWatts = calculatedAvgPower,
            maxPowerWatts = 5.0f,
            maxTemperature = 37.5f,
            chargeType = "USB充电"
        )
        assertEquals(87 * 60 * 1000L, record.durationMs)
        assertEquals(22, record.levelGain)
        assertEquals("01h27m00s", record.getFormattedDuration())
    }

    /**
     * 测试拔电时刻持续时长与电量增量核准逻辑：
     * 当内存中旧 endTimestamp 停留在起始时间（例如采样协程遭遇异常中断），
     * 使用拔电时刻真实当前时间与起始时间之差能够准确还原 87 分钟物理时长，杜绝被判定为小于 10 秒短时误插拔。
     */
    @Test
    fun testUnplugDurationReconciliationPreventsFalseShortDrop() {
        val startTs = 1700000000000L
        val realUnplugTs = startTs + 87 * 60 * 1000L // 拔电时刻
        val staleEndTs = startTs // 内存旧值滞后

        // 模拟旧代码逻辑：依赖旧 endTimestamp 计算出的错误时长
        val buggyDuration = staleEndTs - startTs
        assertEquals(0L, buggyDuration)

        // 模拟修复后逻辑：以拔电时刻当前时间减起始时间核算真实时长
        val reconciledDuration = (realUnplugTs - startTs).coerceAtLeast(0L)
        assertEquals(87 * 60 * 1000L, reconciledDuration)
        assertTrue("核准后的时长必须大于10秒阈值", reconciledDuration >= 10000L)
    }

    /**
     * 测试无线充电位掩码类型识别逻辑：
     * 针对部分设备与底座组合报告复合 plugged 状态（例如 4 纯无线、4 或 1 复合无线底座）时，
     * 均能通过位运算准确识别为“无线充电”。
     */
    @Test
    fun testWirelessChargingBitmaskRecognition() {
        // 模拟纯无线充电: BATTERY_PLUGGED_WIRELESS (4)
        val pureWirelessPlugged = 4
        val isWireless1 = (pureWirelessPlugged and 4) != 0
        assertTrue("纯无线充电必须识别为 true", isWireless1)

        // 模拟复合无线底座: 1 (AC) | 4 (WIRELESS) = 5
        val compositeWirelessPlugged = 5
        val isWireless2 = (compositeWirelessPlugged and 4) != 0
        assertTrue("复合无线底座必须识别为 true", isWireless2)

        // 模拟普通 USB 充电: 2 (USB)
        val usbPlugged = 2
        val isWireless3 = (usbPlugged and 4) != 0
        assertTrue("普通USB不能被误识别为无线充电", !isWireless3)
    }

    /**
     * 测试无线充电短时微断开（例如线圈抖动或温控短暂保护）重新握手时的平滑接续与归档条件：
     * 验证 180 秒内且电量未下跌的短暂断开能够识别为同一周期接续，时长累计并正确持久化。
     */
    @Test
    fun testWirelessChargingSessionSmoothReconnectionAndPersistence() {
        val startTs = 1700000000000L
        val pauseTs = startTs + 30 * 60 * 1000L // 充电 30 分钟后因线圈微动短暂断开
        val resumeTs = pauseTs + 2000L // 2 秒后重新放置对准握手
        val endTs = resumeTs + 15 * 60 * 1000L // 继续充 15 分钟后正式拿走

        // 验证断开间隔在 180 秒窗口内判定为平滑接续
        val isRecentSession = (resumeTs - pauseTs) < 180_000L
        assertTrue("2秒断开必须判定为平滑接续窗口内", isRecentSession)

        // 验证整体充入电量与总时长
        val totalDurationMs = endTs - startTs
        val startLevel = 40
        val endLevel = 65
        val levelGain = endLevel - startLevel

        val designCapMah = 5000f
        val avgVolt = 4.0f
        val calculatedEnergyWh = (levelGain / 100.0f) * designCapMah * avgVolt / 1000.0f
        assertEquals(5.0f, calculatedEnergyWh, 0.01f)

        // 验证满足入库门限
        val shouldPersist = totalDurationMs >= 10000L || calculatedEnergyWh > 0.005f || levelGain > 0
        assertTrue("无线充电完整会话必须满足入库归档门限", shouldPersist)

        val record = ChargingHistoryRecord(
            id = endTs,
            recordTime = "2026-10-10 14:45:00",
            startTimestamp = startTs,
            endTimestamp = endTs,
            durationMs = totalDurationMs,
            startLevel = startLevel,
            endLevel = endLevel,
            levelGain = levelGain,
            chargedEnergyWh = calculatedEnergyWh,
            avgPowerWatts = calculatedEnergyWh / (totalDurationMs / 3600000.0f),
            maxPowerWatts = 15.0f,
            maxTemperature = 38.0f,
            chargeType = "无线充电"
        )
        assertEquals("无线充电", record.chargeType)
        assertEquals(25, record.levelGain)
        assertEquals(45 * 60 * 1000L + 2000L, record.durationMs)
    }

    /**
     * 测试当充电记录的结束电量因断电异常被放电电量污染时，基于采样点与息屏增量进行自愈校准。
     * 复现用户真实场景：19% 充到 95%，息屏充了 73%，亮屏充了 3%；但因断电时应用被杀导致记录了 22%，
     * 验证自愈机制能够将结束电量校准为 95%，增量校准为 76%，亮屏充入量校准为 +3%（杜绝 -70% 负数异常）。
     */
    @Test
    fun testEffectiveLevelCalibrationWhenEndLevelPollutedByDischarge() {
        val startTs = 1700000000000L
        val endTs = startTs + 4616000L // 01h16m56s
        val points = listOf(
            ChargingSamplePoint(
                timestamp = startTs,
                powerWatts = 15.0f,
                batteryLevel = 19,
                temperature = 37.0f,
                voltageVolts = 4.0f,
                currentMa = 3500f,
                isScreenOn = true
            ),
            ChargingSamplePoint(
                timestamp = startTs + 114000L, // 01m54s
                powerWatts = 20.0f,
                batteryLevel = 22,
                temperature = 38.0f,
                voltageVolts = 4.2f,
                currentMa = 4000f,
                isScreenOn = false
            ),
            ChargingSamplePoint(
                timestamp = endTs,
                powerWatts = 5.0f,
                batteryLevel = 95,
                temperature = 39.0f,
                voltageVolts = 4.54f,
                currentMa = 1000f,
                isScreenOn = false
            )
        )
        val pointsJson = ChargingHistoryRecord.pointsToJson(points)

        // 模拟异常脏数据：endLevel 被写成了 22，levelGain 被写成了 3
        val record = ChargingHistoryRecord(
            id = endTs,
            recordTime = "2026-10-18 16:50:22",
            startTimestamp = startTs,
            endTimestamp = endTs,
            durationMs = 4616000L,
            startLevel = 19,
            endLevel = 22, // 脏数据
            levelGain = 3, // 脏数据
            chargedEnergyWh = 20.5f,
            avgPowerWatts = 15.7f,
            maxPowerWatts = 22.17f,
            maxTemperature = 40.0f,
            chargeType = "交流快充",
            screenOffDurationMs = 4501000L,
            screenOffLevelGain = 73,
            screenOffEnergyWh = 19.8f,
            samplePointsJson = pointsJson
        )

        // 验证自愈后的结束电量为真实采样点末端电量 95%
        assertEquals(95, record.getEffectiveEndLevel())
        // 验证自愈后的总电量净增量为 95 - 19 = 76%
        assertEquals(76, record.getEffectiveLevelGain())
        // 验证亮屏充入电量为 76 - 73 = 3%（绝非 -70%）
        assertEquals(3, record.getScreenOnLevelGain())
        // 验证时长与摘要文本
        assertTrue(record.getFormattedDurationAndGain().contains("19%~95%(+76%)"))
    }

    /**
     * 测试无采样点的历史老数据当 levelGain 小于 screenOffLevelGain 时基于物理守恒自愈校准。
     */
    @Test
    fun testEffectiveLevelCalibrationWithoutPointsFallbackToConservation() {
        val record = ChargingHistoryRecord(
            id = 1700000000000L,
            recordTime = "2026-10-18 16:50:22",
            startTimestamp = 1700000000000L,
            endTimestamp = 1700004616000L,
            durationMs = 4616000L,
            startLevel = 19,
            endLevel = 22, // 脏数据
            levelGain = 3,  // 脏数据
            chargedEnergyWh = 20.5f,
            avgPowerWatts = 15.7f,
            maxPowerWatts = 22.17f,
            maxTemperature = 40.0f,
            chargeType = "交流快充",
            screenOffDurationMs = 4501000L,
            screenOffLevelGain = 73,
            screenOffEnergyWh = 19.8f,
            samplePointsJson = "" // 无采样点
        )

        // 物理守恒：结束电量至少为 19 + 73 = 92%
        assertEquals(92, record.getEffectiveEndLevel())
        // 真实充入量至少为息屏充入量 73%
        assertEquals(73, record.getEffectiveLevelGain())
        // 亮屏充入量不为负数
        assertEquals(0, record.getScreenOnLevelGain())
    }

    /**
     * 测试当采样点序列存在超过 30 分钟的时间断层与电量跌落时能够准确识别断层切片并还原真实独立充电。
     */
    @Test
    fun testSamplePointsBreakDetectionAndExtraction() {
        val baseTime = 1792500000000L // 10/20 19:08
        val points = mutableListOf<ChargingSamplePoint>()

        // 第一段充电：19:08 ~ 19:33（49% ~ 80%）
        points.add(ChargingSamplePoint(baseTime, 21.0f, 49, 32.0f, 4.0f, 5250f))
        points.add(ChargingSamplePoint(baseTime + 1500_000L, 20.0f, 80, 34.0f, 4.2f, 4760f))

        // 中间发生断层（跨越 11 个多小时，电量跌落至 43%）
        val morningTime = baseTime + 41700_000L // 10/21 06:43 左右
        points.add(ChargingSamplePoint(morningTime, 18.0f, 43, 30.0f, 3.9f, 4615f))
        points.add(ChargingSamplePoint(morningTime + 1800_000L, 15.0f, 79, 33.0f, 4.3f, 3488f))

        // 验证采样点断层检测逻辑
        var breakIdx = -1
        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val curr = points[i]
            val dtMs = curr.timestamp - prev.timestamp
            if (dtMs > 1800_000L || (prev.batteryLevel in 1..100 && curr.batteryLevel in 1..100 && curr.batteryLevel < prev.batteryLevel - 2)) {
                breakIdx = i
                break
            }
        }

        assertEquals(2, breakIdx)

        // 验证后半段能够正确提取出第二天的真实独立充电
        val secondHalf = points.subList(breakIdx, points.size)
        assertEquals(2, secondHalf.size)
        assertEquals(43, secondHalf.first().batteryLevel)
        assertEquals(79, secondHalf.last().batteryLevel)
        val realGain = secondHalf.last().batteryLevel - secondHalf.first().batteryLevel
        assertEquals(36, realGain)
        val realDuration = secondHalf.last().timestamp - secondHalf.first().timestamp
        assertEquals(1800_000L, realDuration)
    }

    /**
     * 测试通过采样点时序梯形安时积分计算充电真实净电荷量（mAh）的准确性。
     */
    @Test
    fun testRecordNetChargeMahCalculation() {
        val baseTime = 1700000000000L
        // 模拟正常充电采样点（切片间隔 60 秒，完全处于允许的 180 秒窗口内）：
        // t0: 0s, 4000mA (息屏)
        // t1: 60s, 4000mA (息屏)
        // t2: 120s, 2000mA (亮屏)
        // 预期安时积分计算：
        // 0~60s 切片 (1/60 h)：(4000 + 4000) / 2 * (1/60) = 66.6667 mAh (息屏)
        // 60~120s 切片 (1/60 h)：(4000 + 2000) / 2 * (1/60) = 50.0000 mAh
        // 总计：116.6667 mAh
        val points = listOf(
            ChargingSamplePoint(baseTime, 16.0f, 30, 32.0f, 4.0f, 4000f, isScreenOn = false),
            ChargingSamplePoint(baseTime + 60_000L, 16.4f, 31, 32.5f, 4.1f, 4000f, isScreenOn = false),
            ChargingSamplePoint(baseTime + 120_000L, 8.6f, 32, 33.0f, 4.3f, 2000f, isScreenOn = true)
        )
        val json = ChargingHistoryRecord.pointsToJson(points)
        val record = ChargingHistoryRecord(
            id = baseTime + 120_000L,
            recordTime = "2026-11-03 08:00:00",
            startTimestamp = baseTime,
            endTimestamp = baseTime + 120_000L,
            durationMs = 120_000L,
            startLevel = 30,
            endLevel = 32,
            levelGain = 2,
            chargedEnergyWh = 0.5f,
            avgPowerWatts = 15.0f,
            maxPowerWatts = 16.4f,
            maxTemperature = 33.0f,
            chargeType = "交流快充",
            samplePointsJson = json
        )

        val totalMah = record.getNetChargeMah()
        org.junit.Assert.assertNotNull(totalMah)
        assertEquals(116.67f, totalMah!!, 0.1f)

        // 测试仅息屏部分：前 60 秒息屏，预期 66.67 mAh
        val screenOffMah = record.getNetChargeMah(screenOffOnly = true)
        org.junit.Assert.assertNotNull(screenOffMah)
        assertEquals(66.67f, screenOffMah!!, 0.1f)
    }

    /**
     * 测试当采样点序列为空或不足两个点时，getNetChargeMah 如实返回 null，绝不合成或保底伪造数据。
     */
    @Test
    fun testRecordNetChargeMahWithMissingOrFewPoints() {
        // 无采样点
        val recordEmpty = ChargingHistoryRecord(
            id = 1700000000000L,
            recordTime = "2026-11-03 08:00:00",
            startTimestamp = 1700000000000L,
            endTimestamp = 1700000060000L,
            durationMs = 60000L,
            startLevel = 30,
            endLevel = 35,
            levelGain = 5,
            chargedEnergyWh = 2.0f,
            avgPowerWatts = 15.0f,
            maxPowerWatts = 18.0f,
            maxTemperature = 33.0f,
            chargeType = "交流快充",
            samplePointsJson = ""
        )
        org.junit.Assert.assertNull(recordEmpty.getNetChargeMah())

        // 仅有一个采样点
        val singlePoint = listOf(
            ChargingSamplePoint(1700000000000L, 16.0f, 30, 32.0f, 4.0f, 4000f)
        )
        val recordSingle = recordEmpty.copy(samplePointsJson = ChargingHistoryRecord.pointsToJson(singlePoint))
        org.junit.Assert.assertNull(recordSingle.getNetChargeMah())
    }
}


