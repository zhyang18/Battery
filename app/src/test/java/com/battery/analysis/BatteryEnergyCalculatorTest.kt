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
     * 测试标准微安时电荷计数器（BATTERY_PROPERTY_CHARGE_COUNTER）统一严格除以 1000 规范折算为毫安时（mAh）。
     * 在 RuntimeModel v2.1 规范中，彻底废除根据数值是否小于 100,000 猜测单位的逻辑，
     * 避免在低电量（如 80,000 uAh = 80 mAh）时误猜为 80,000 mAh 导致剩余能量暴涨千倍。
     */
    @Test
    fun testHardwareChargeCounterAsMahDirectly() {
        // 2500 mAh 对应标准硬件微安时数值 2,500,000 uAh
        val chargeUah = 2_500_000
        val resultWh = BatteryEnergyCalculator.calculateRemainingEnergyWh(
            hardwareEnergyNwh = null,
            hardwareChargeCounterUah = chargeUah,
            batteryPercent = 50,
            nominalVoltageVolts = 3.85f,
            effectiveCapacityMah = 5000f
        )
        // 理论值: 2500 * 3.85 / 1000 = 9.625 Wh
        val expected = (2500f * 3.85f) / 1000f
        assertEquals(expected, resultWh, 0.01f)
    }

    /**
     * 测试硬件电荷计数器在低数值区间（如 50,000 uAh，即 50 mAh）时的严格物理换算，
     * 验证其严格除以 1000f 换算为 50 mAh，绝不因数值小于 100,000 而误判为 50,000 mAh。
     */
    @Test
    fun testHardwareChargeCounterDirectCalculation() {
        // 低电量 50 mAh 对应 50,000 uAh
        val chargeUah = 50_000
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
     * 测试亮屏放电速度卡片弹框详细格式化，验证包含“累积7天亮屏放电速度”、满电续航、精确到秒的“当前剩余续航”及“预测真实容量：多少Wh（多少mAh）”。
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
            predictedCapacityMah = 5000f,
            remainingDurationMs = (9 * 3600 + 15 * 60 + 30) * 1000L, // 09h 15m 30s
            sessionRatePercentPerHour = 6.5f,
            sessionDurationMs = 3600_000L * 2 // 02h00m
        )

        assertTrue("标题包含亮屏续航预测", message.contains("【亮屏续航预测】"))
        assertTrue("包含当前会话放电速度", message.contains("• 当前会话放电速度：6.5%/h (亮屏 02h00m00s)"))
        assertTrue("包含近7天放电速度与实际时长", message.contains("• 近7天放电速度：6.1%/h (亮屏 02d05h)"))
        assertTrue("包含满电亮屏续航且精确到秒", message.contains("• 满电亮屏续航：16h24m00s"))
        assertTrue("包含预测基准放电速度", message.contains("• 预测基准放电速度：6.3%/h"))
        assertTrue("包含当前剩余续航且精确到秒", message.contains("• 当前剩余续航：09h15m30s"))
        val speedIndex = message.indexOf("• 预测基准放电速度：6.3%/h")
        val remainingIndex = message.indexOf("• 当前剩余续航：09h15m30s")
        assertTrue("预测基准放电速度必须在当前剩余续航上方", speedIndex in 0 until remainingIndex)
        assertTrue("包含预测真实容量（多少Wh（多少mAh））", message.contains("• 预测真实容量：19.25 Wh (5000 mAh)"))
        assertTrue("包含说明模块", message.contains("💡 说明："))
    }

    /**
     * 测试息屏放电速度卡片弹框详细格式化，验证包含“当前会话放电速度”、“近7天放电速度”、满电待机时长及精确到秒的“当前剩余续航”。
     */
    @Test
    fun testFormatScreenOffDischargeSpeedDetailMessage() {
        val message = BatteryEnergyCalculator.formatDischargeSpeedCardDetailMessage(
            rowType = BatteryEnergyCalculator.ROW_SCREEN_OFF,
            currentRatePercentPerHour = null, // 暂无数据 "--"
            sevenDaysRatePercentPerHour = 1.0f,
            sevenDaysDurationMs = (4 * 86400 + 1 * 3600) * 1000L, // 04d01h
            fullDurationMs = (4 * 86400 + 1 * 3600) * 1000L, // 04d01h
            remainingDurationMs = (2 * 86400 + 3 * 3600 + 40 * 60 + 15) * 1000L // 02d 03h 40m 15s
        )

        assertTrue("标题包含息屏续航预测", message.contains("【息屏续航预测】"))
        assertTrue("当前会话暂无数据展示--", message.contains("• 当前会话放电速度：--"))
        assertTrue("包含近7天放电速度与实际时长", message.contains("• 近7天放电速度：1.0%/h (息屏 04d01h)"))
        assertTrue("包含满电待机时长且精确到秒", message.contains("• 满电待机时长：04d01h00m00s"))
        assertTrue("预测基准放电速度暂无展示--", message.contains("• 预测基准放电速度：--"))
        assertTrue("包含当前剩余续航且精确到秒", message.contains("• 当前剩余续航：02d03h40m15s"))
        assertTrue("包含说明模块", message.contains("💡 说明："))
    }

    /**
     * 测试全局放电速度卡片弹框详细格式化，验证包含“当前会话放电速度”、“7天全局放电速度”、精确到秒的“当前剩余续航”、模式1（全局加权）与模式2（场景融合）双模型推算（精确到秒）。
     */
    @Test
    fun testFormatGlobalDischargeSpeedDetailMessage() {
        val message = BatteryEnergyCalculator.formatDischargeSpeedCardDetailMessage(
            rowType = BatteryEnergyCalculator.ROW_GLOBAL,
            currentRatePercentPerHour = 6.3f,
            sevenDaysRatePercentPerHour = 2.8f,
            sevenDaysDurationMs = (6 * 86400 + 7 * 3600) * 1000L, // 06d07h
            fullDurationMs = (1 * 86400 + 11 * 3600 + 12 * 60) * 1000L, // 01d11h12m
            sceneScreenOnRatio = 0.25f,
            sceneFullDurationMs = (1 * 86400 + 15 * 3600 + 35 * 60) * 1000L, // 01d15h35m
            remainingDurationMs = (1 * 86400 + 2 * 3600 + 10 * 60 + 5) * 1000L, // 01d 02h 10m 05s
            sessionRatePercentPerHour = 4.2f,
            sessionDurationMs = 3600_000L * 5
        )

        assertTrue("标题包含全局续航预测", message.contains("【全局续航预测】"))
        assertTrue("包含当前会话放电速度", message.contains("• 当前会话放电速度：4.2%/h (放电 05h00m00s)"))
        assertTrue("包含近7天放电速度与实际时长", message.contains("• 近7天放电速度：2.8%/h (放电 06d07h)"))
        assertTrue("包含预测基准放电速度", message.contains("• 预测基准放电速度：6.3%/h"))
        assertTrue("包含当前剩余续航且精确到秒", message.contains("• 当前剩余续航：01d02h10m05s"))
        val speedIndex = message.indexOf("• 预测基准放电速度：6.3%/h")
        val remainingIndex = message.indexOf("• 当前剩余续航：01d02h10m05s")
        assertTrue("预测基准放电速度必须在当前剩余续航上方", speedIndex in 0 until remainingIndex)
        assertTrue("包含双模型标题", message.contains("📊 满电综合续航推算"))
        assertTrue("包含模式1全局加权结果（精确到秒）", message.contains("• 模式1（全局加权）：01d11h12m00s"))
        assertTrue("包含模式2场景融合结果（精确到秒）", message.contains("• 模式2（场景融合）：01d15h35m00s"))
        assertTrue("包含模式2动态加权说明", message.contains("按日常习惯 25.0% 亮屏 + 75.0% 息屏加权"))
        assertTrue("包含说明模块", message.contains("💡 说明："))
    }

    /**
     * 测试卡片弹框详细格式化中当前会话时长精确到秒的展示逻辑，
     * 验证如 1 分 23 秒短时长格式化为 "01m23s"，杜绝仅截断展示分钟（如 "01m"）。
     */
    @Test
    fun testFormatDischargeSpeedCardDetailMessage_SessionDurationPreciseToSeconds() {
        val msg = BatteryEnergyCalculator.formatDischargeSpeedCardDetailMessage(
            rowType = BatteryEnergyCalculator.ROW_SCREEN_ON,
            currentRatePercentPerHour = 6.0f,
            sevenDaysRatePercentPerHour = 6.0f,
            sevenDaysDurationMs = 3600_000L * 10,
            fullDurationMs = 3600_000L * 15,
            sessionRatePercentPerHour = 5.6f,
            sessionDurationMs = 83_000L
        )
        assertTrue("会话时长应精确到秒展示为 01m23s", msg.contains("• 当前会话放电速度：5.6%/h (亮屏 01m23s)"))

        val msgSecondsOnly = BatteryEnergyCalculator.formatDischargeSpeedCardDetailMessage(
            rowType = BatteryEnergyCalculator.ROW_SCREEN_OFF,
            currentRatePercentPerHour = 1.0f,
            sevenDaysRatePercentPerHour = 1.0f,
            sevenDaysDurationMs = 3600_000L * 10,
            fullDurationMs = 3600_000L * 50,
            sessionRatePercentPerHour = 1.2f,
            sessionDurationMs = 45_000L
        )
        assertTrue("不足1分钟的会话时长应精确到秒展示为 45s", msgSecondsOnly.contains("• 当前会话放电速度：1.2%/h (息屏 45s)"))
    }

    /**
     * 测试卡片弹框详细格式化中对自适应历史时间窗口结果的三项解耦分离展示：
     * 1. 统计范围（如自适应扩展至近 18.5 天）；
     * 2. 有效放电时长（实际参与统计的有效放电累计时长）；
     * 3. 数据充分性（数据充足 / 基本可用 / 样本较少）。
     */
    @Test
    fun testFormatDischargeSpeedDetailMessageWithAdaptiveWindow() {
        val v2Result = com.battery.analysis.domain.estimator.model.FullChargeRuntimeResult(
            fullRuntimeMs = 72_000_000L, // 20h
            fullEnergyWh = 20.0,
            predictedPowerWatts = 1.0,
            confidenceLevel = com.battery.analysis.domain.estimator.model.RuntimeConfidenceLevel.HIGH,
            energySource = com.battery.analysis.domain.estimator.model.EnergySource.FCC_TIMES_NOMINAL_VOLTAGE,
            effectiveHistoryMs = 18L * 3600_000L,
            usedRegimeModel = true,
            screenOnFullRuntimeMs = 18_000_000L, // 5h
            predictedScreenOnPowerWatts = 4.0,
            historyWindowDays = 18.5,
            effectiveDischargeDurationMs = 18L * 3600_000L,
            dataSufficiency = com.battery.analysis.domain.estimator.model.DataSufficiencyLevel.SUFFICIENT,
            screenOnEffectiveMs = 6L * 3600_000L
        )

        val msg = BatteryEnergyCalculator.formatDischargeSpeedCardDetailMessage(
            rowType = BatteryEnergyCalculator.ROW_SCREEN_ON,
            currentRatePercentPerHour = 5.0f,
            sevenDaysRatePercentPerHour = 4.8f,
            sevenDaysDurationMs = 18L * 3600_000L,
            fullDurationMs = 72_000_000L,
            v2Result = v2Result
        )

        assertTrue("必须包含升级模型v2.1标题", msg.contains("• 升级模型(v2.1)："))
        assertTrue("升级模型推算精确到秒", msg.contains("• 升级模型(v2.1)：05h00m00s (可信度高)"))
        assertTrue("必须包含统计范围且展示自适应扩展天数", msg.contains("统计范围：自适应扩展至近 18.5 天"))
        assertTrue("必须包含实际有效放电时长", msg.contains("有效放电：累计亮屏 06h00m"))
        assertTrue("必须包含数据充分性评级", msg.contains("数据充足"))
    }

    /**
     * 针对用户需求的专项单元测试：
     * 1. 验证满电亮屏续航与升级模型(v2.2)均精确展示到秒；
     * 2. 验证预测放电功耗括号内的可用能量/电量跟随 displayUnit 按钮在 Wh 与 mAh 之间动态切换。
     */
    @Test
    fun testFormatDischargeSpeedDetailMessage_DynamicUnitAndPreciseSeconds() {
        val v2Result = com.battery.analysis.domain.estimator.model.FullChargeRuntimeResult(
            fullRuntimeMs = (18 * 3600 + 53 * 60 + 24) * 1000L,
            fullEnergyWh = 29.67,
            predictedPowerWatts = 1.60,
            confidenceLevel = com.battery.analysis.domain.estimator.model.RuntimeConfidenceLevel.HIGH,
            energySource = com.battery.analysis.domain.estimator.model.EnergySource.FCC_TIMES_NOMINAL_VOLTAGE,
            effectiveHistoryMs = 34L * 3600_000L,
            usedRegimeModel = true,
            screenOnFullRuntimeMs = (18 * 3600 + 53 * 60 + 24) * 1000L, // 18h53m24s
            predictedScreenOnPowerWatts = 1.60,
            historyWindowDays = 7.0,
            effectiveDischargeDurationMs = 34L * 3600_000L,
            dataSufficiency = com.battery.analysis.domain.estimator.model.DataSufficiencyLevel.SUFFICIENT,
            modelVersion = com.battery.analysis.domain.estimator.model.RuntimeModelVersion.FULL_RUNTIME_V2_2
        )

        val remEstimate = com.battery.analysis.domain.estimator.model.RuntimeEstimate(
            remainingMs = (5 * 3600 + 21 * 60 + 6) * 1000L, // 05h21m06s
            confidenceLevel = com.battery.analysis.domain.estimator.model.RuntimeConfidenceLevel.HIGH,
            predictedPowerWatts = 1.47,
            remainingEnergyWh = 7.19,
            confidence = 0.9,
            modelType = com.battery.analysis.domain.estimator.model.RuntimeModelType.MULTI_TIMESCALE,
            minRemainingMs = 2 * 3600_000L,
            maxRemainingMs = 7 * 3600_000L
        )

        // 1. Wh 单位下测试
        val msgWh = BatteryEnergyCalculator.formatDischargeSpeedCardDetailMessage(
            rowType = BatteryEnergyCalculator.ROW_SCREEN_ON,
            currentRatePercentPerHour = 5.0f,
            sevenDaysRatePercentPerHour = 5.4f,
            sevenDaysDurationMs = 34L * 3600_000L,
            fullDurationMs = (18 * 3600 + 53 * 60 + 24) * 1000L,
            v2Result = v2Result,
            remainingEstimate = remEstimate,
            displayUnit = com.battery.analysis.model.PowerDisplayUnit.WH,
            nominalVoltageVolts = 3.85f
        )

        assertTrue("Wh模式下满电亮屏续航精确到秒", msgWh.contains("• 满电亮屏续航：18h53m24s"))
        assertTrue("Wh模式下升级模型(v2.2)精确到秒", msgWh.contains("• 升级模型(v2.2)：18h53m24s (可信度高)"))
        assertTrue("Wh模式下预测放电功耗括号显示 7.19Wh", msgWh.contains("预测放电功耗：1.47W (可用 7.19Wh)"))

        // 2. mAh 单位下测试：7.19 Wh * 1000 / 3.85V ≈ 1867.5 mAh
        val msgMah = BatteryEnergyCalculator.formatDischargeSpeedCardDetailMessage(
            rowType = BatteryEnergyCalculator.ROW_SCREEN_ON,
            currentRatePercentPerHour = 5.0f,
            sevenDaysRatePercentPerHour = 5.4f,
            sevenDaysDurationMs = 34L * 3600_000L,
            fullDurationMs = (18 * 3600 + 53 * 60 + 24) * 1000L,
            v2Result = v2Result,
            remainingEstimate = remEstimate,
            displayUnit = com.battery.analysis.model.PowerDisplayUnit.MAH,
            nominalVoltageVolts = 3.85f
        )

        assertTrue("mAh模式下满电亮屏续航精确到秒", msgMah.contains("• 满电亮屏续航：18h53m24s"))
        assertTrue("mAh模式下升级模型(v2.2)精确到秒", msgMah.contains("• 升级模型(v2.2)：18h53m24s (可信度高)"))
        assertTrue("mAh模式下预测放电功耗括号显示 1867.5mAh", msgMah.contains("预测放电功耗：1.47W (可用 1867.5mAh)"))
    }

    /**
     * 验证全局、亮屏与息屏详情弹窗中，当传入真实电池容量时，
     * 预测放电功耗行能忠实闭环展示折算的等效放电速度（例如 1.925W / 19.25Wh = 10.0%/h）。
     */
    @Test
    fun testFormatDischargeSpeedDetailMessage_GlobalAndScreenShowsPredictedEquivalentRate() {
        val remEstimate = com.battery.analysis.domain.estimator.model.RuntimeEstimate(
            remainingMs = 3600_000L,
            predictedPowerWatts = 2.0,
            remainingEnergyWh = 10.0,
            confidence = 0.9,
            modelType = com.battery.analysis.domain.estimator.model.RuntimeModelType.MULTI_TIMESCALE,
            confidenceLevel = com.battery.analysis.domain.estimator.model.RuntimeConfidenceLevel.HIGH
        )

        // 1. 全局卡片弹窗测试 (电池真实容量 20.0Wh，功耗 2.0W -> 折算速度 10.0%/h)
        val msgGlobal = BatteryEnergyCalculator.formatDischargeSpeedCardDetailMessage(
            rowType = BatteryEnergyCalculator.ROW_GLOBAL,
            currentRatePercentPerHour = 8.5f,
            sevenDaysRatePercentPerHour = 9.0f,
            sevenDaysDurationMs = 24L * 3600_000L,
            fullDurationMs = 11L * 3600_000L,
            predictedCapacityWh = 20.0f,
            remainingEstimate = remEstimate
        )
        assertTrue("全局弹窗应包含折算速度 10.0%/h", msgGlobal.contains("预测综合功耗：2.00W (可用 10.00Wh) · 10.0%/h"))

        // 2. 亮屏卡片弹窗测试
        val msgOn = BatteryEnergyCalculator.formatDischargeSpeedCardDetailMessage(
            rowType = BatteryEnergyCalculator.ROW_SCREEN_ON,
            currentRatePercentPerHour = 10.0f,
            sevenDaysRatePercentPerHour = 9.5f,
            sevenDaysDurationMs = 24L * 3600_000L,
            fullDurationMs = 10L * 3600_000L,
            predictedCapacityWh = 20.0f,
            remainingEstimate = remEstimate
        )
        assertTrue("亮屏弹窗应包含折算速度 10.0%/h", msgOn.contains("预测放电功耗：2.00W (可用 10.00Wh) · 10.0%/h"))

        // 3. 息屏待机卡片弹窗测试 (功耗 0.20W -> 1.0%/h)
        val offEstimate = com.battery.analysis.domain.estimator.model.RuntimeEstimate(
            remainingMs = 36000_000L,
            predictedPowerWatts = 0.20,
            remainingEnergyWh = 10.0,
            confidence = 0.9,
            modelType = com.battery.analysis.domain.estimator.model.RuntimeModelType.MULTI_TIMESCALE,
            confidenceLevel = com.battery.analysis.domain.estimator.model.RuntimeConfidenceLevel.HIGH
        )
        val msgOff = BatteryEnergyCalculator.formatDischargeSpeedCardDetailMessage(
            rowType = BatteryEnergyCalculator.ROW_SCREEN_OFF,
            currentRatePercentPerHour = 0.8f,
            sevenDaysRatePercentPerHour = 1.2f,
            sevenDaysDurationMs = 24L * 3600_000L,
            fullDurationMs = 100L * 3600_000L,
            predictedCapacityWh = 20.0f,
            remainingEstimate = offEstimate
        )
        assertTrue("息屏弹窗应包含折算速度 1.0%/h", msgOff.contains("预测待机功耗：0.20W (可用 10.00Wh) · 1.0%/h"))
    }

    /**
     * 测试息屏耗电、唤醒及深度睡眠三个卡片弹框中均包含独立的放电速度行。
     */
    @Test
    fun testSleepAwakeCardDischargeRateDisplay() {
        val overview = com.battery.analysis.manager.PowerOverviewStats(
            avgPowerWatts = 0.5f,
            screenOffPercent = 6.0f,
            screenOffDurationMs = 5 * 3600 * 1000L, // 5 小时
            screenOffEnergyWh = 1.155f,
            screenOffPowerWatts = 0.231f,
            screenOffDrainMah = 300f,
            screenOffAwakePercent = 3.5f,
            screenOffAwakeDurationMs = 1 * 3600 * 1000L, // 1 小时
            screenOffAwakeEnergyWh = 0.674f,
            screenOffAwakePowerWatts = 0.674f,
            screenOffAwakeDrainMah = 175f,
            screenOffDeepSleepPercent = 2.5f,
            screenOffDeepSleepDurationMs = 4 * 3600 * 1000L, // 4 小时
            screenOffDeepSleepEnergyWh = 0.481f,
            screenOffDeepSleepPowerWatts = 0.12f,
            screenOffDeepSleepDrainMah = 125f,
            isScreenOffDecomposedAvailable = true
        )

        val screenOffMsg = BatteryEnergyCalculator.formatScreenOffCardDetailMessage(overview, 19.25f)
        assertTrue("息屏弹框包含放电速度", screenOffMsg.contains("• 放电速度："))

        val awakeMsg = BatteryEnergyCalculator.formatAwakeCardDetailMessage(overview, 19.25f)
        assertTrue("唤醒弹框包含唤醒放电速度", awakeMsg.contains("• 放电速度："))

        val deepSleepMsg = BatteryEnergyCalculator.formatDeepSleepCardDetailMessage(overview, 19.25f)
        assertTrue("深度睡眠弹框包含深度睡眠放电速度", deepSleepMsg.contains("• 放电速度："))
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

    /**
     * 测试 formatDisplayEnergy 在 Wh 模式下的精确格式化、微弱量化保护与空值处理。
     */
    @Test
    fun testFormatDisplayEnergyWhMode() {
        // 常规数值保留 3 位小数
        val normal = BatteryEnergyCalculator.formatDisplayEnergy(0.25f, com.battery.analysis.model.PowerDisplayUnit.WH)
        assertEquals("0.250Wh", normal)

        // 微量能耗展示 <0.001Wh
        val micro = BatteryEnergyCalculator.formatDisplayEnergy(0.0003f, com.battery.analysis.model.PowerDisplayUnit.WH)
        assertEquals("<0.001Wh", micro)

        // 极微底噪或 0 消耗展示 --
        val zero = BatteryEnergyCalculator.formatDisplayEnergy(0f, com.battery.analysis.model.PowerDisplayUnit.WH)
        assertEquals("--", zero)
    }

    /**
     * 测试 formatDisplayEnergy 在 mAh 模式下基于 3.85V 标称电压的换算精度、微小值保护与空值处理。
     */
    @Test
    fun testFormatDisplayEnergyMahMode() {
        // 0.25Wh / 3.85V * 1000 = 64.935mAh -> 格式化保留 1 位小数 64.9mAh
        val normal = BatteryEnergyCalculator.formatDisplayEnergy(0.25f, com.battery.analysis.model.PowerDisplayUnit.MAH, 3.85f)
        assertEquals("64.9mAh", normal)

        // 微量电量（如 0.0001Wh 对应约 0.026mAh）展示 <0.1mAh
        val micro = BatteryEnergyCalculator.formatDisplayEnergy(0.0001f, com.battery.analysis.model.PowerDisplayUnit.MAH, 3.85f)
        assertEquals("<0.1mAh", micro)

        // 无效值或 0 消耗展示 --
        val zero = BatteryEnergyCalculator.formatDisplayEnergy(0f, com.battery.analysis.model.PowerDisplayUnit.MAH, 3.85f)
        assertEquals("--", zero)
    }

    /**
     * 测试 formatDisplayEnergy 在接收非法负数或 NaN 时的防御性处理。
     */
    @Test
    fun testFormatDisplayEnergyInvalidValues() {
        val negative = BatteryEnergyCalculator.formatDisplayEnergy(-0.5f, com.battery.analysis.model.PowerDisplayUnit.WH)
        assertEquals("--", negative)

        val nanVal = BatteryEnergyCalculator.formatDisplayEnergy(Float.NaN, com.battery.analysis.model.PowerDisplayUnit.MAH)
        assertEquals("--", nanVal)
    }

    /**
     * 测试单位切换科学原理说明文案的完整性与专业物理基准依据。
     */
    @Test
    fun testUnitSwitchExplanation() {
        val (mahTitle, mahMsg) = BatteryEnergyCalculator.getUnitSwitchExplanation(com.battery.analysis.model.PowerDisplayUnit.MAH, 3.85f)
        assertTrue("标题包含毫安时", mahTitle.contains("毫安时"))
        assertTrue("内容包含标称电压换算", mahMsg.contains("3.85V"))
        assertTrue("内容包含电荷量定义", mahMsg.contains("电荷量"))

        val (whTitle, whMsg) = BatteryEnergyCalculator.getUnitSwitchExplanation(com.battery.analysis.model.PowerDisplayUnit.WH)
        assertTrue("标题包含瓦时", whTitle.contains("瓦时"))
        assertTrue("内容包含能量做功定义", whMsg.contains("做功"))
    }

    /**
     * 测试单位切换结构化弹窗内容模型生成与关键字段校验。
     */
    @Test
    fun testUnitSwitchDialogContent() {
        val mahContent = BatteryEnergyCalculator.getUnitSwitchDialogContent(com.battery.analysis.model.PowerDisplayUnit.MAH, 3.85f)
        assertTrue("标题包含毫安时", mahContent.title.contains("毫安时"))
        assertTrue("公式包含换算表达式", mahContent.formula.contains("3.85V"))
        assertTrue("定义包含电荷量", mahContent.definition.contains("电荷量"))
        assertTrue("依据包含物理守恒", mahContent.science.contains("守恒"))

        val whContent = BatteryEnergyCalculator.getUnitSwitchDialogContent(com.battery.analysis.model.PowerDisplayUnit.WH, 3.85f)
        assertTrue("标题包含瓦时", whContent.title.contains("瓦时"))
        assertTrue("定义包含能量物理单位", whContent.definition.contains("能量"))
        assertTrue("依据包含消除电压平台差异", whContent.science.contains("基准单位"))
    }

    /**
     * 测试充电主界面充入能量（formatChargingGainEnergy）在 Wh 与 mAh 模式下的格式化。
     */
    @Test
    fun testFormatChargingGainEnergy() {
        // Wh 模式：保留 1 位小数 + 符号
        val whStr = BatteryEnergyCalculator.formatChargingGainEnergy(0.53f, com.battery.analysis.model.PowerDisplayUnit.WH)
        assertEquals("+0.5Wh", whStr)

        // mAh 模式：0.53Wh / 3.85V * 1000 ≈ 137.66 -> +138mAh
        val mahStr = BatteryEnergyCalculator.formatChargingGainEnergy(0.53f, com.battery.analysis.model.PowerDisplayUnit.MAH, 3.85f)
        assertEquals("+138mAh", mahStr)
    }

    /**
     * 测试充电详情页充入能量（formatChargingDetailEnergy）在 Wh 与 mAh 模式下的格式化。
     */
    @Test
    fun testFormatChargingDetailEnergy() {
        // Wh 模式：保留 2 位小数
        val whStr = BatteryEnergyCalculator.formatChargingDetailEnergy(6.85f, com.battery.analysis.model.PowerDisplayUnit.WH)
        assertEquals("+6.85 Wh", whStr)

        // mAh 模式：6.85Wh / 3.85V * 1000 ≈ 1779.22 -> +1779 mAh
        val mahStr = BatteryEnergyCalculator.formatChargingDetailEnergy(6.85f, com.battery.analysis.model.PowerDisplayUnit.MAH, 3.85f)
        assertEquals("+1779 mAh", mahStr)
    }

    /**
     * 测试历史详情页总能耗大字（formatHistoryDetailOverviewEnergy）在 Wh 与 mAh 模式下的格式化。
     */
    @Test
    fun testFormatHistoryDetailOverviewEnergy() {
        // Wh 模式：保留 1 位小数
        val whStr = BatteryEnergyCalculator.formatHistoryDetailOverviewEnergy(14.23f, com.battery.analysis.model.PowerDisplayUnit.WH)
        assertEquals("14.2\u00A0Wh", whStr)

        // mAh 模式：14.23Wh / 3.85V * 1000 ≈ 3696.1 -> 3696 mAh
        val mahStr = BatteryEnergyCalculator.formatHistoryDetailOverviewEnergy(14.23f, com.battery.analysis.model.PowerDisplayUnit.MAH, 3.85f)
        assertEquals("3696\u00A0mAh", mahStr)
    }

    /**
     * 测试 Wh 与 mAh 相互换算的数学精度与守恒性。
     */
    @Test
    fun testConvertWhToMahAndMahToWh() {
        val energyWh = 19.25f
        val mah = BatteryEnergyCalculator.convertWhToMah(energyWh, 3.85f)
        assertEquals(5000f, mah, 0.01f)

        val convertedWh = BatteryEnergyCalculator.convertMahToWh(mah, 3.85f)
        assertEquals(energyWh, convertedWh, 0.01f)
    }

    /**
     * 测试 formatDurationWithSeconds 精确到秒的时间字符串格式化逻辑，验证不同时间量级及边界空值防御。
     */
    @Test
    fun testFormatDurationWithSeconds() {
        // 无效值与边界
        assertEquals("--", BatteryEnergyCalculator.formatDurationWithSeconds(null))
        assertEquals("--", BatteryEnergyCalculator.formatDurationWithSeconds(0L))
        assertEquals("--", BatteryEnergyCalculator.formatDurationWithSeconds(-1000L))

        // 纯秒级
        assertEquals("05s", BatteryEnergyCalculator.formatDurationWithSeconds(5_000L))
        assertEquals("45s", BatteryEnergyCalculator.formatDurationWithSeconds(45_000L))

        // 分秒级
        assertEquals("01m05s", BatteryEnergyCalculator.formatDurationWithSeconds(65_000L))
        assertEquals("15m30s", BatteryEnergyCalculator.formatDurationWithSeconds((15 * 60 + 30) * 1000L))

        // 时分秒级
        assertEquals("02h05m09s", BatteryEnergyCalculator.formatDurationWithSeconds((2 * 3600 + 5 * 60 + 9) * 1000L))
        assertEquals("17h46m32s", BatteryEnergyCalculator.formatDurationWithSeconds((17 * 3600 + 46 * 60 + 32) * 1000L))

        // 天时分秒级
        assertEquals("01d04h28m10s", BatteryEnergyCalculator.formatDurationWithSeconds((86400 + 4 * 3600 + 28 * 60 + 10) * 1000L))
    }

    /**
     * 测试 formatCardRemainingDuration 卡片紧凑剩余时间格式化逻辑，验证天、小时、分钟分级紧凑呈现与微弱时长兜底保护。
     */
    @Test
    fun testFormatCardRemainingDuration() {
        // 无效值与边界
        assertEquals("--", BatteryEnergyCalculator.formatCardRemainingDuration(null))
        assertEquals("--", BatteryEnergyCalculator.formatCardRemainingDuration(0L))
        assertEquals("--", BatteryEnergyCalculator.formatCardRemainingDuration(-500L))

        // 不足 1 分钟保底展示 01m
        assertEquals("01m", BatteryEnergyCalculator.formatCardRemainingDuration(30_000L))

        // 分钟级
        assertEquals("25m", BatteryEnergyCalculator.formatCardRemainingDuration(25 * 60 * 1000L))

        // 时分级
        assertEquals("02h15m", BatteryEnergyCalculator.formatCardRemainingDuration((2 * 3600 + 15 * 60) * 1000L))
        assertEquals("17h46m", BatteryEnergyCalculator.formatCardRemainingDuration((17 * 3600 + 46 * 60) * 1000L))

        // 天时级
        assertEquals("03d09h", BatteryEnergyCalculator.formatCardRemainingDuration((3 * 86400 + 9 * 3600) * 1000L))
    }

    /**
     * 测试 calculateEstimatedRemainingDurationMs 自适应置信度动态加权算法：
     * 验证统计时长小于 5 分钟、5 至 30 分钟过渡期、大于 30 分钟饱和期的权重分配与防毛刺效果。
     */
    @Test
    fun testCalculateEstimatedRemainingDurationMs_ConfidenceWeighting() {
        val curRate = 10.0f // 当前放电速度 10%/h
        val sevenDaysRate = 5.0f // 7天大样本基准 5%/h
        val batteryPercent = 50.0f // 剩余电量 50%

        // 1. 统计时长 < 5分钟 (如 2分钟 = 120,000ms): 权重 0.15 依靠历史基准
        // 融合速度: 10 * 0.15 + 5 * 0.85 = 1.5 + 4.25 = 5.75%/h
        // 理论续航: 50 / 5.75 = 8.69565 小时 -> 8.69565 * 3600 * 1000 ≈ 31304348 ms
        val shortMs = BatteryEnergyCalculator.calculateEstimatedRemainingDurationMs(
            currentRatePercentPerHour = curRate,
            sevenDaysRatePercentPerHour = sevenDaysRate,
            currentDurationMs = 120_000L,
            remainingBatteryPercent = batteryPercent
        )
        org.junit.Assert.assertNotNull(shortMs)
        assertEquals(31_304_348L, shortMs!!)

        // 2. 统计时长 >= 30分钟 (如 40分钟 = 2,400,000ms): 权重 0.70 紧跟实测
        // 融合速度: 10 * 0.70 + 5 * 0.30 = 7.0 + 1.5 = 8.50%/h
        // 理论续航: 50 / 8.50 = 5.88235 小时 -> 5.88235 * 3600 * 1000 ≈ 21176471 ms
        val longMs = BatteryEnergyCalculator.calculateEstimatedRemainingDurationMs(
            currentRatePercentPerHour = curRate,
            sevenDaysRatePercentPerHour = sevenDaysRate,
            currentDurationMs = 2_400_000L,
            remainingBatteryPercent = batteryPercent
        )
        org.junit.Assert.assertNotNull(longMs)
        assertEquals(21_176_471L, longMs!!)

        // 3. 统计时长 17.5分钟 (1,050,000ms): 线性过渡点进度 0.5，权重 0.15 + 0.55 * 0.5 = 0.425
        // 融合速度: 10 * 0.425 + 5 * 0.575 = 4.25 + 2.875 = 7.125%/h
        // 理论续航: 50 / 7.125 = 7.01754 小时 -> 7.01754 * 3600 * 1000 ≈ 25263158 ms
        val midMs = BatteryEnergyCalculator.calculateEstimatedRemainingDurationMs(
            currentRatePercentPerHour = curRate,
            sevenDaysRatePercentPerHour = sevenDaysRate,
            currentDurationMs = 1_050_000L,
            remainingBatteryPercent = batteryPercent
        )
        org.junit.Assert.assertNotNull(midMs)
        assertEquals(25_263_158L, midMs!!)

        // 4. 单一数据源支持：仅有当前速度时直接按当前速度推算 (50% / 10%/h = 5h = 18,000,000ms)
        val onlyCurMs = BatteryEnergyCalculator.calculateEstimatedRemainingDurationMs(
            currentRatePercentPerHour = 10.0f,
            sevenDaysRatePercentPerHour = null,
            currentDurationMs = 60_000L,
            remainingBatteryPercent = 50.0f
        )
        assertEquals(18_000_000L, onlyCurMs)

        // 5. 单一数据源支持：仅有7天速度时直接按7天速度推算 (50% / 5%/h = 10h = 36,000,000ms)
        val only7dMs = BatteryEnergyCalculator.calculateEstimatedRemainingDurationMs(
            currentRatePercentPerHour = null,
            sevenDaysRatePercentPerHour = 5.0f,
            currentDurationMs = 0L,
            remainingBatteryPercent = 50.0f
        )
        assertEquals(36_000_000L, only7dMs)

        // 6. 无电量百分比但有剩余能量与总容量时自动换算推算 (9.625Wh / 19.25Wh = 50%)
        val energyFallbackMs = BatteryEnergyCalculator.calculateEstimatedRemainingDurationMs(
            currentRatePercentPerHour = 10.0f,
            sevenDaysRatePercentPerHour = null,
            currentDurationMs = 60_000L,
            remainingBatteryPercent = null,
            remainingEnergyWh = 9.625f,
            totalCapacityWh = 19.25f
        )
        assertEquals(18_000_000L, energyFallbackMs)

        // 7. 缺失必要数据返回 null
        val nullResult = BatteryEnergyCalculator.calculateEstimatedRemainingDurationMs(
            currentRatePercentPerHour = null,
            sevenDaysRatePercentPerHour = null,
            currentDurationMs = 0L,
            remainingBatteryPercent = 50.0f
        )
        org.junit.Assert.assertNull(nullResult)
    }

    /**
     * 测试 calculateCompositeRemainingDurationMs 场景占空比融合综合续航算法与异常回退行为。
     */
    @Test
    fun testCalculateCompositeRemainingDurationMs() {
        // 日常习惯 25% 亮屏 (8.0%/h) + 75% 息屏 (1.0%/h)
        // 综合放电速率: 0.25 * 8.0 + 0.75 * 1.0 = 2.0 + 0.75 = 2.75%/h
        // 当前剩余电量 55% -> 理论续航 55 / 2.75 = 20.0 小时 -> 72,000,000 ms
        val compositeMs = BatteryEnergyCalculator.calculateCompositeRemainingDurationMs(
            screenOnRate = 8.0f,
            screenOffRate = 1.0f,
            sceneScreenOnRatio = 0.25f,
            remainingBatteryPercent = 55.0f,
            fallbackGlobalRemainingMs = 50_000_000L
        )
        org.junit.Assert.assertNotNull(compositeMs)
        assertEquals(72_000_000L, compositeMs!!)

        // 场景数据无效或异常比例时，安全降级至 fallbackGlobalRemainingMs
        val fallbackMs = BatteryEnergyCalculator.calculateCompositeRemainingDurationMs(
            screenOnRate = null,
            screenOffRate = 1.0f,
            sceneScreenOnRatio = 0.25f,
            remainingBatteryPercent = 55.0f,
            fallbackGlobalRemainingMs = 50_000_000L
        )
        assertEquals(50_000_000L, fallbackMs)
    }
}


