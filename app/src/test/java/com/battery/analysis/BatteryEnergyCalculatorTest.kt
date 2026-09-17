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
}
