package com.battery.analysis

import com.battery.analysis.model.BatteryInfo
import com.battery.analysis.model.BugreportResult
import com.battery.analysis.model.HistoryRecord
import com.battery.analysis.viewmodel.BatteryViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 电池健康度历史快照三大数据源记录与全局缓存单元测试套件。
 * 验证系统api、Shizuku、错误报告三条记录的构建完整性以及 ViewModel 跨界面伴生对象缓存功能。
 */
class HistorySnapshotTest {

    /**
     * 测试从不同数据源的 BatteryInfo 构建 HistoryRecord 实体对象的正确性。
     */
    @Test
    fun testFromBatteryInfoThreeCategories() {
        val baseTime = 1750000000000L

        // 1. 系统 API 数据对象
        val normalInfo = BatteryInfo(
            level = 85,
            voltage = 4200f,
            temperature = 30.5f,
            status = "放电中",
            healthStatus = "良好",
            source = "系统标准广播"
        )
        val normalRecord = HistoryRecord.fromBatteryInfo(normalInfo, "系统api", baseTime)
        assertEquals("系统api", normalRecord.category)
        assertEquals("系统api", normalRecord.source)
        assertEquals(85, normalRecord.level)
        assertEquals(4200f, normalRecord.voltage)
        assertEquals(baseTime, normalRecord.id)

        // 2. Shizuku 数据对象
        val shizukuInfo = BatteryInfo(
            level = 85,
            voltage = 4200f,
            temperature = 30.5f,
            designCapacity = 5000f,
            fullChargeCapacity = 4800f,
            cycleCount = 120,
            batteryHealth = 96.0f,
            source = "Shizuku"
        )
        val shizukuRecord = HistoryRecord.fromBatteryInfo(shizukuInfo, "Shizuku", baseTime + 1)
        assertEquals("Shizuku", shizukuRecord.category)
        assertEquals(5000f, shizukuRecord.designCapacity)
        assertEquals(4800f, shizukuRecord.fullChargeCapacity)
        assertEquals(120, shizukuRecord.cycleCount)
        assertEquals(96.0f, shizukuRecord.batteryHealth)
        assertEquals(baseTime + 1, shizukuRecord.id)

        // 3. 错误报告数据对象
        val bugreportInfo = BatteryInfo(
            level = 85,
            voltage = 4200f,
            temperature = 30.5f,
            designCapacity = 5000f,
            fullChargeCapacity = 4820f,
            cycleCount = 118,
            batteryHealth = 96.4f,
            source = "错误报告"
        )
        val bugreportRecord = HistoryRecord.fromBatteryInfo(bugreportInfo, "错误报告", baseTime + 2)
        assertEquals("错误报告", bugreportRecord.category)
        assertEquals(4820f, bugreportRecord.fullChargeCapacity)
        assertEquals(118, bugreportRecord.cycleCount)
        assertEquals(baseTime + 2, bugreportRecord.id)
    }

    /**
     * 测试 BatteryViewModel 伴生对象的 Shizuku 与 错误报告 跨界面全局缓存功能。
     */
    @Test
    fun testViewModelCompanionCaching() {
        val testShizukuInfo = BatteryInfo(
            level = 90,
            designCapacity = 5000f,
            fullChargeCapacity = 4900f,
            cycleCount = 50
        )
        BatteryViewModel.setCachedShizukuBatteryInfo(testShizukuInfo)
        assertEquals(testShizukuInfo, BatteryViewModel.getCachedShizukuBatteryInfo())

        val testBugreportResult = BugreportResult(
            tableItems = emptyList(),
            rawHealthInfoText = "raw log",
            hasRealData = true,
            parsedBatteryInfo = BatteryInfo(designCapacity = 5000f, cycleCount = 50)
        )
        BatteryViewModel.setCachedBugreportResult(testBugreportResult)
        assertEquals(testBugreportResult, BatteryViewModel.getCachedBugreportResult())

        // 清理缓存验证
        BatteryViewModel.setCachedShizukuBatteryInfo(null)
        assertNull(BatteryViewModel.getCachedShizukuBatteryInfo())
        BatteryViewModel.setCachedBugreportResult(null)
        assertNull(BatteryViewModel.getCachedBugreportResult())
    }

    /**
     * 测试当未导入错误报告且无历史快照时，错误报告补齐逻辑优先继承 Shizuku 获取的真实底层数据。
     */
    @Test
    fun testBugreportFallbackToShizuku() {
        val baseTime = 1750000000000L
        val shizukuInfo = BatteryInfo(
            level = 88,
            voltage = 4150f,
            temperature = 31.0f,
            designCapacity = 5200f,
            fullChargeCapacity = 5000f,
            cycleCount = 80,
            batteryHealth = 96.15f,
            source = "Shizuku"
        )

        // 模拟错误报告缺失时由 Shizuku 真实数据补齐
        val fallbackBugreportInfo = shizukuInfo.copy(source = "错误报告")
        val bugreportRecord = HistoryRecord.fromBatteryInfo(fallbackBugreportInfo, "错误报告", baseTime + 2)

        assertEquals("错误报告", bugreportRecord.category)
        assertEquals("错误报告", bugreportRecord.source)
        assertEquals(5200f, bugreportRecord.designCapacity)
        assertEquals(5000f, bugreportRecord.fullChargeCapacity)
        assertEquals(80, bugreportRecord.cycleCount)
        assertEquals(96.15f, bugreportRecord.batteryHealth)
        assertEquals(88, bugreportRecord.level)
        assertEquals(baseTime + 2, bugreportRecord.id)
    }
}
