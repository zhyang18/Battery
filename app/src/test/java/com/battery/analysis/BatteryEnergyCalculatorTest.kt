package com.battery.analysis

import com.battery.analysis.util.BatteryEnergyCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 电池剩余能量（瓦时 Wh）高精度计算器单元测试套件。
 * 验证硬件原生能量计数器、硬件电荷计数器、多级容量降级推算以及异常边界保护算法的准确性。
 */
class BatteryEnergyCalculatorTest {

    /**
     * 测试硬件层原生支持能量计数器（BATTERY_PROPERTY_ENERGY_COUNTER，纳瓦时）时的精准直接换算。
     */
    @Test
    fun testHardwareEnergyCounterDirectConversion() {
        // 10.6 Wh 对应的纳瓦时为 10,600,000,000 nWh
        val energyNwh = 10_600_000_000L
        val resultWh = BatteryEnergyCalculator.calculateRemainingEnergyWh(
            hardwareEnergyNwh = energyNwh,
            hardwareChargeCounterUah = null,
            batteryPercent = 55,
            nominalVoltageVolts = 3.85f,
            effectiveCapacityMah = 5000f
        )
        assertEquals(10.6f, resultWh, 0.01f)
    }

    /**
     * 测试硬件能量计数器不可用时，通过硬件电荷计数器（微安时 uAh）结合标称电压的精确折算。
     */
    @Test
    fun testHardwareChargeCounterConversion() {
        // 2700 mAh (2,700,000 uAh), 55% 电量, 5000mAh 基准容量, 标称电压 3.85V
        val chargeUah = 2_700_000
        val resultWh = BatteryEnergyCalculator.calculateRemainingEnergyWh(
            hardwareEnergyNwh = null,
            hardwareChargeCounterUah = chargeUah,
            batteryPercent = 55,
            nominalVoltageVolts = 3.85f,
            effectiveCapacityMah = 5000f
        )
        // 理论值: 2700 * 3.85 / 1000 = 10.395 Wh
        val expected = (2700f * 3.85f) / 1000f
        assertEquals(expected, resultWh, 0.01f)
    }

    /**
     * 测试部分芯片直接返回毫安时（小于 100,000）时的电荷计数器折算。
     */
    @Test
    fun testHardwareChargeCounterAsMahDirectly() {
        // 2500 mAh 直接作为数值返回
        val chargeMah = 2500
        val resultWh = BatteryEnergyCalculator.calculateRemainingEnergyWh(
            hardwareEnergyNwh = null,
            hardwareChargeCounterUah = chargeMah,
            batteryPercent = 50,
            nominalVoltageVolts = 3.85f,
            effectiveCapacityMah = 5000f
        )
        // 理论值: 2500 * 3.85 / 1000 = 9.625 Wh
        val expected = (2500f * 3.85f) / 1000f
        assertEquals(expected, resultWh, 0.01f)
    }

    /**
     * 测试硬件电荷计数器直接忠实换算，不设人为离群值强行拦截。
     */
    @Test
    fun testHardwareChargeCounterDirectCalculation() {
        val chargeUah = 50
        val resultWh = BatteryEnergyCalculator.calculateRemainingEnergyWh(
            hardwareEnergyNwh = null,
            hardwareChargeCounterUah = chargeUah,
            batteryPercent = 80,
            nominalVoltageVolts = 3.85f,
            effectiveCapacityMah = 5000f
        )
        // 忠实采用 50mAh * 3.85V / 1000 = 0.1925 Wh
        val expected = (50f * 3.85f) / 1000f
        assertEquals(expected, resultWh, 0.001f)
    }

    /**
     * 测试硬件计数器均不可用时，使用经过健康度衰减计算的真实满充容量（FCC）结合标称电压推算剩余能量。
     */
    @Test
    fun testDegradedFullChargeCapacityFallback() {
        // 手机出厂 5000mAh，老化后 FCC 为 4200mAh，当前电量 60%，标称电压 3.85V
        val fcc = 4200f
        val resultWh = BatteryEnergyCalculator.calculateRemainingEnergyWh(
            hardwareEnergyNwh = null,
            hardwareChargeCounterUah = null,
            batteryPercent = 60,
            nominalVoltageVolts = 3.85f,
            effectiveCapacityMah = fcc
        )
        // 理论值: 4200 * 0.6 * 3.85 / 1000 = 9.702 Wh
        val expected = (4200f * 0.6f * 3.85f) / 1000f
        assertEquals(expected, resultWh, 0.01f)
    }

    /**
     * 测试零电量、满电量及异常电压等边界场景下的安全计算与标称电压兜底保护机制。
     */
    @Test
    fun testBoundaryAndSafetyProtection() {
        // 0% 电量
        val zeroWh = BatteryEnergyCalculator.calculateRemainingEnergyWh(
            hardwareEnergyNwh = null,
            hardwareChargeCounterUah = null,
            batteryPercent = 0,
            nominalVoltageVolts = 3.85f,
            effectiveCapacityMah = 5000f
        )
        assertEquals(0f, zeroWh, 0.001f)

        // 100% 满电，异常 0V 电压（自动采用标称电压 3.85V 保护）
        val fullWh = BatteryEnergyCalculator.calculateRemainingEnergyWh(
            hardwareEnergyNwh = null,
            hardwareChargeCounterUah = null,
            batteryPercent = 100,
            nominalVoltageVolts = 0f,
            effectiveCapacityMah = 5000f
        )
        assertEquals(19.25f, fullWh, 0.01f) // 5000 * 1.0 * 3.85 / 1000 = 19.25 Wh

        // 硬件返回 Long.MIN_VALUE 或无效负数
        val safeWh = BatteryEnergyCalculator.calculateRemainingEnergyWh(
            hardwareEnergyNwh = Long.MIN_VALUE,
            hardwareChargeCounterUah = -1,
            batteryPercent = 50,
            nominalVoltageVolts = 3.85f,
            effectiveCapacityMah = 5000f
        )
        assertEquals((5000f * 0.5f * 3.85f) / 1000f, safeWh, 0.01f)
    }

    /**
     * 测试移除 120Wh 人工物理上限后，超大容量电池或外设（如 150Wh）的原生硬件能量正常读取不被降级。
     */
    @Test
    fun testUncappedLargeHardwareEnergy() {
        // 150 Wh 对应的纳瓦时为 150,000,000,000 nWh
        val largeEnergyNwh = 150_000_000_000L
        val resultWh = BatteryEnergyCalculator.calculateRemainingEnergyWh(
            hardwareEnergyNwh = largeEnergyNwh,
            hardwareChargeCounterUah = null,
            batteryPercent = 100,
            nominalVoltageVolts = 15.0f,
            effectiveCapacityMah = 10000f
        )
        assertEquals(150.0f, resultWh, 0.01f)
    }

    /**
     * 测试电池总能量的标称换算准确性（基于有效容量与标称电压）。
     */
    @Test
    fun testCalculateTotalEnergyWhNormal() {
        // 5000 mAh, 默认标称电压 3.85V -> 5000 * 3.85 / 1000 = 19.25 Wh
        val totalEnergy = BatteryEnergyCalculator.calculateTotalEnergyWh(5000f)
        org.junit.Assert.assertNotNull(totalEnergy)
        assertEquals(19.25f, totalEnergy!!, 0.01f)

        // 自定义标称电压 3.87V, 6000 mAh -> 6000 * 3.87 / 1000 = 23.22 Wh
        val totalEnergyCustomVolt = BatteryEnergyCalculator.calculateTotalEnergyWh(6000f, 3.87f)
        org.junit.Assert.assertNotNull(totalEnergyCustomVolt)
        assertEquals(23.22f, totalEnergyCustomVolt!!, 0.01f)
    }

    /**
     * 测试底层硬件能量计数器（nWh）结合当前电量百分比反推满电总能量的准确性。
     */
    @Test
    fun testCalculateTotalEnergyWhWithHardwareEnergyNwh() {
        // 50% 电量时剩余 9.625 Wh (9_625_000_000 nWh)，反推 100% 满电总能量应为 19.25 Wh
        val totalEnergy = BatteryEnergyCalculator.calculateTotalEnergyWh(
            hardwareEnergyNwh = 9_625_000_000L,
            hardwareChargeCounterUah = null,
            batteryPercent = 50,
            effectiveCapacityMah = 0f
        )
        org.junit.Assert.assertNotNull(totalEnergy)
        assertEquals(19.25f, totalEnergy!!, 0.01f)
    }

    /**
     * 测试硬件电荷计数器（uAh）结合当前电量百分比与标称电压推算满电总能量的准确性。
     */
    @Test
    fun testCalculateTotalEnergyWhWithChargeCounterUah() {
        // 50% 电量时剩余 2500 mAh (2_500_000 uAh)，反推满电 5000 mAh，按 3.85V 折算应为 19.25 Wh
        val totalEnergy = BatteryEnergyCalculator.calculateTotalEnergyWh(
            hardwareEnergyNwh = null,
            hardwareChargeCounterUah = 2_500_000,
            batteryPercent = 50,
            effectiveCapacityMah = 0f
        )
        org.junit.Assert.assertNotNull(totalEnergy)
        assertEquals(19.25f, totalEnergy!!, 0.01f)
    }

    /**
     * 测试电池基准容量无效或缺失时如实返回 null，严禁假数据。
     */
    @Test
    fun testCalculateTotalEnergyWhInvalidCapacity() {
        // 容量为 0 且无硬件计数器时返回 null
        val zeroResult = BatteryEnergyCalculator.calculateTotalEnergyWh(0f)
        org.junit.Assert.assertNull(zeroResult)

        // 负数容量返回 null
        val negativeResult = BatteryEnergyCalculator.calculateTotalEnergyWh(-100f)
        org.junit.Assert.assertNull(negativeResult)
    }

    /**
     * 测试 formatCapacityWithNominalWh 在输入有效容量时，正确格式化输出 mAh 与按标压折算的 Wh 数据。
     */
    @Test
    fun testFormatCapacityWithNominalWhValid() {
        val result5000 = BatteryEnergyCalculator.formatCapacityWithNominalWh(5000f)
        assertEquals("5000.0 mAh (19.25 Wh)", result5000)

        val result4920 = BatteryEnergyCalculator.formatCapacityWithNominalWh(4920f)
        assertEquals("4920.0 mAh (18.94 Wh)", result4920)

        val result4250 = BatteryEnergyCalculator.formatCapacityWithNominalWh(4250f)
        assertEquals("4250.0 mAh (16.36 Wh)", result4250)
    }

    /**
     * 测试 formatCapacityWithNominalWh 在容量为 null 或非正数时的异常边界保护，严格杜绝虚假能量数据。
     */
    @Test
    fun testFormatCapacityWithNominalWhEdgeCases() {
        // null 输入应如实返回 null
        val nullResult = BatteryEnergyCalculator.formatCapacityWithNominalWh(null)
        org.junit.Assert.assertNull(nullResult)

        // 0 输入或负数输入不应捏造正数 Wh
        val zeroResult = BatteryEnergyCalculator.formatCapacityWithNominalWh(0f)
        assertEquals("0.0 mAh", zeroResult)

        val negativeResult = BatteryEnergyCalculator.formatCapacityWithNominalWh(-500f)
        assertEquals("-500.0 mAh", negativeResult)
    }

    /**
     * 测试底层物理优先级逻辑获取总能量详情（BatteryTotalEnergyInfo）的多级判定策略。
     */
    @Test
    fun testCalculateTotalEnergyInfoMultiTier() {
        // 1. 硬件原生能量计数器优先
        val hwEnergyInfo = BatteryEnergyCalculator.calculateTotalEnergyInfo(
            hardwareEnergyNwh = 9_625_000_000L,
            hardwareChargeCounterUah = 2_500_000,
            batteryPercent = 50,
            effectiveCapacityMah = 4500f,
            capacitySource = "出厂设计容量"
        )
        org.junit.Assert.assertNotNull(hwEnergyInfo)
        assertEquals("硬件原生能量计数器", hwEnergyInfo!!.sourceDescription)
        assertEquals(19.25f, hwEnergyInfo.totalWh, 0.01f)
        assertEquals(5000f, hwEnergyInfo.equivalentMah, 0.1f)

        // 2. 硬件电荷计数器次级优先
        val chargeInfo = BatteryEnergyCalculator.calculateTotalEnergyInfo(
            hardwareEnergyNwh = null,
            hardwareChargeCounterUah = 2_500_000,
            batteryPercent = 50,
            effectiveCapacityMah = 4500f,
            capacitySource = "出厂设计容量"
        )
        org.junit.Assert.assertNotNull(chargeInfo)
        assertEquals("硬件实时电荷计数器", chargeInfo!!.sourceDescription)
        assertEquals(19.25f, chargeInfo.totalWh, 0.01f)
        assertEquals(5000f, chargeInfo.equivalentMah, 0.1f)

        // 3. 有效容量降级（出厂设计容量）
        val designInfo = BatteryEnergyCalculator.calculateTotalEnergyInfo(
            hardwareEnergyNwh = null,
            hardwareChargeCounterUah = null,
            batteryPercent = null,
            effectiveCapacityMah = 2960.8f,
            capacitySource = "出厂设计容量"
        )
        org.junit.Assert.assertNotNull(designInfo)
        assertEquals("出厂设计容量", designInfo!!.sourceDescription)
        assertEquals(11.40f, designInfo.totalWh, 0.01f)
        assertEquals(2960.8f, designInfo.equivalentMah, 0.1f)

        // 4. 有效容量降级（真实满充容量）
        val fccInfo = BatteryEnergyCalculator.calculateTotalEnergyInfo(
            hardwareEnergyNwh = null,
            hardwareChargeCounterUah = null,
            batteryPercent = null,
            effectiveCapacityMah = 4800f,
            capacitySource = "真实满充容量"
        )
        org.junit.Assert.assertNotNull(fccInfo)
        assertEquals("真实满充容量", fccInfo!!.sourceDescription)
        assertEquals(18.48f, fccInfo.totalWh, 0.01f)

        // 5. 数据缺失返回 null
        val nullInfo = BatteryEnergyCalculator.calculateTotalEnergyInfo(
            hardwareEnergyNwh = null,
            hardwareChargeCounterUah = null,
            batteryPercent = null,
            effectiveCapacityMah = 0f
        )
        org.junit.Assert.assertNull(nullInfo)
    }

    /**
     * 测试 formatEnergyConversionMessage 在传入 totalEnergyInfo 时正确渲染总能量数据行。
     */
    @Test
    fun testFormatEnergyConversionMessageWithTotalEnergyInfo() {
        val totalInfo = com.battery.analysis.util.BatteryTotalEnergyInfo(
            totalWh = 11.399f,
            sourceDescription = "出厂设计容量",
            equivalentMah = 2960.8f
        )
        val message = BatteryEnergyCalculator.formatEnergyConversionMessage(
            title = "全局",
            energyWh = 3.226f,
            ratioStr = "28.3%",
            totalEnergyInfo = totalInfo
        )

        org.junit.Assert.assertTrue("包含全局消耗能量与占比", message.contains("全局消耗能量：3.226Wh (28.3%)"))
        org.junit.Assert.assertTrue("包含折算等效电量", message.contains("折算等效电量：约 837.9 mAh (≈ 838mAh)"))
        org.junit.Assert.assertTrue("包含换算基准标称电压", message.contains("换算基准：标称电压 3.85V"))
        org.junit.Assert.assertTrue("包含出厂设计容量获得的总能量", message.contains("出厂设计容量获得的总能量：11.399Wh (≈ 2961mAh)"))
        org.junit.Assert.assertTrue("包含换算说明", message.contains("💡 换算说明："))
    }

    /**
     * 测试亮屏放电速度卡片弹框详细格式化，验证包含“最近7天内”、满电续航及“预测真实容量：多少Wh（多少mAh）”。
     */
    @Test
    fun testFormatScreenOnDischargeSpeedDetailMessage() {
        val message = BatteryEnergyCalculator.formatDischargeSpeedCardDetailMessage(
            rowType = BatteryEnergyCalculator.ROW_SCREEN_ON,
            currentRatePercentPerHour = 6.3f,
            sevenDaysRatePercentPerHour = 6.1f,
            sevenDaysDurationMs = (2 * 86400 + 5 * 3600) * 1000L, // 02d05h
            fullDurationMs = (16 * 3600 + 24 * 60) * 1000L, // 16h24m
            predictedCapacityWh = 19.25f,
            predictedCapacityMah = 5000f
        )

        assertTrue("标题包含亮屏放电速度", message.contains("【亮屏放电速度】"))
        assertTrue("包含当前放电速度", message.contains("• 当前放电速度：6.3%/h"))
        assertTrue("包含最近7天内放电速度与统计时长", message.contains("• 最近7天内放电速度：6.1%/h（统计时长 02d05h）"))
        assertTrue("包含满电亮屏续航", message.contains("• 满电亮屏续航：16h24m"))
        assertTrue("包含预测真实容量（多少Wh（多少mAh））", message.contains("• 预测真实容量：19.25 Wh (5000 mAh)"))
        assertTrue("包含说明模块", message.contains("💡 说明："))
    }

    /**
     * 测试息屏放电速度卡片弹框详细格式化，验证包含“最近7天内”与对应数值。
     */
    @Test
    fun testFormatScreenOffDischargeSpeedDetailMessage() {
        val message = BatteryEnergyCalculator.formatDischargeSpeedCardDetailMessage(
            rowType = BatteryEnergyCalculator.ROW_SCREEN_OFF,
            currentRatePercentPerHour = null, // 暂无数据 "--"
            sevenDaysRatePercentPerHour = 1.0f,
            sevenDaysDurationMs = (4 * 86400 + 1 * 3600) * 1000L, // 04d01h
            fullDurationMs = (4 * 86400 + 1 * 3600) * 1000L // 04d01h
        )

        assertTrue("标题包含息屏放电速度", message.contains("【息屏放电速度】"))
        assertTrue("当前息屏暂无数据展示--", message.contains("• 当前放电速度：--"))
        assertTrue("包含最近7天内放电速度与统计时长", message.contains("• 最近7天内放电速度：1.0%/h（统计时长 04d01h）"))
        assertTrue("包含满电待机时长", message.contains("• 满电待机时长：04d01h"))
        assertTrue("包含说明模块", message.contains("💡 说明："))
    }

    /**
     * 测试全局放电速度卡片弹框详细格式化，验证包含“最近7天内”与对应数值。
     */
    @Test
    fun testFormatGlobalDischargeSpeedDetailMessage() {
        val message = BatteryEnergyCalculator.formatDischargeSpeedCardDetailMessage(
            rowType = BatteryEnergyCalculator.ROW_GLOBAL,
            currentRatePercentPerHour = 6.3f,
            sevenDaysRatePercentPerHour = 2.8f,
            sevenDaysDurationMs = (6 * 86400 + 7 * 3600) * 1000L, // 06d07h
            fullDurationMs = (1 * 86400 + 11 * 3600) * 1000L // 01d11h
        )

        assertTrue("标题包含全局放电速度", message.contains("【全局放电速度】"))
        assertTrue("包含当前放电速度", message.contains("• 当前放电速度：6.3%/h"))
        assertTrue("包含最近7天内放电速度与统计时长", message.contains("• 最近7天内放电速度：2.8%/h（统计时长 06d07h）"))
        assertTrue("包含满电综合续航", message.contains("• 满电综合续航：01d11h"))
        assertTrue("包含说明模块", message.contains("💡 说明："))
    }

    /**
     * 测试预测真实容量在数据缺失时如实展示“未获取”，绝不虚构数据。
     */
    @Test
    fun testFormatPredictedCapacityWhenDataMissing() {
        val result = BatteryEnergyCalculator.formatPredictedCapacity(null, null)
        assertEquals("数据缺失时如实展示未获取", "未获取", result)

        val resultZero = BatteryEnergyCalculator.formatPredictedCapacity(0f, 0f)
        assertEquals("数值为零时如实展示未获取", "未获取", resultZero)
    }

    /**
     * 验证 BatteryEnergyCalculator 与 PowerUsageFragment 的行分类常量值严格对齐（0/1/2），防止错配导致空内容。
     */
    @Test
    fun testRowConstantsAlignment() {
        assertEquals("亮屏常量严格为0", 0, BatteryEnergyCalculator.ROW_SCREEN_ON)
        assertEquals("息屏常量严格为1", 1, BatteryEnergyCalculator.ROW_SCREEN_OFF)
        assertEquals("全局常量严格为2", 2, BatteryEnergyCalculator.ROW_GLOBAL)
    }
}
