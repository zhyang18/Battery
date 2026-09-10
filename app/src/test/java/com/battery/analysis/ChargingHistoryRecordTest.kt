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
        assertEquals(22.0f, parsedPoints[1].powerWatts, 0.01f)
        assertEquals(35, parsedPoints[1].batteryLevel)
    }

    /**
     * 测试历史老数据（无采样点 JSON）进入详情页时能够智能自愈生成平滑折线点集。
     */
    @Test
    fun testLegacyRecordPointsSelfHealing() {
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
        // 自愈生成点数应足够绘制平滑曲线（>= 2）
        assertTrue(points.size >= 2)
        assertEquals(20, points.first().batteryLevel)
        assertEquals(60, points.last().batteryLevel)
        assertTrue(points.any { it.powerWatts > 0f })
    }
}
