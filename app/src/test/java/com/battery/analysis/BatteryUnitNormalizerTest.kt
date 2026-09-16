package com.battery.analysis

import com.battery.analysis.util.BatteryUnitNormalizer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 电池物理指标单位规范化器 [BatteryUnitNormalizer] 单元测试。
 *
 * 验证各品牌芯片（高通/联发科/定制平台）电压、电流与瞬时放电功率十倍量纲异常的自适应校准能力。
 */
class BatteryUnitNormalizerTest {

    /**
     * 测试标准毫伏 (mV)、0.1 毫伏 (0.1mV) 及微伏 (uV) 的电压单位归一化准确性。
     */
    @Test
    fun testNormalizeVoltage() {
        // 标准毫伏 (例如 3950 mV)
        assertEquals(3950f, BatteryUnitNormalizer.normalizeVoltageMv(3950L), 0.01f)
        assertEquals(3.95f, BatteryUnitNormalizer.normalizeVoltageVolts(3950L), 0.001f)

        // 0.1 毫伏 (例如某些高通/联发科机型 41500 -> 4150 mV = 4.15V)
        assertEquals(4150f, BatteryUnitNormalizer.normalizeVoltageMv(41500L), 0.01f)
        assertEquals(4.15f, BatteryUnitNormalizer.normalizeVoltageVolts(41500L), 0.001f)

        // 微伏 (例如 4000000 uV -> 4000 mV = 4.0V)
        assertEquals(4000f, BatteryUnitNormalizer.normalizeVoltageMv(4000000L), 0.01f)
        assertEquals(4.0f, BatteryUnitNormalizer.normalizeVoltageVolts(4000000L), 0.001f)
    }

    /**
     * 测试放电工况下库仑计电流异常十倍量纲（0.1uA 步进）时的智能自适应降级校准。
     */
    @Test
    fun testNormalizeCurrentDischarging() {
        // 标准微安 (350,000 uA -> 350 mA)
        val normalCur = BatteryUnitNormalizer.normalizeCurrentMa(350_000L, isCharging = false)
        assertEquals(350f, normalCur, 0.01f)

        // 真实高负载放电 (3,500,000 uA -> 3500 mA)
        val heavyCur = BatteryUnitNormalizer.normalizeCurrentMa(3_500_000L, isCharging = false)
        assertEquals(3500f, heavyCur, 0.01f)

        // 极大量程 0.1uA (35,000,000 -> 识别为 0.1uA，除以 10000 恢复为 3500mA)
        val inflatedCur = BatteryUnitNormalizer.normalizeCurrentMa(35_000_000L, isCharging = false)
        assertEquals(3500f, inflatedCur, 0.01f)

        // 负数放电输入测试
        val negativeCur = BatteryUnitNormalizer.normalizeCurrentMa(-350_000L, isCharging = false)
        assertEquals(350f, negativeCur, 0.01f)
    }

    /**
     * 测试放电与充电功率计算忠实反映物理公式 P = (U * I) / 1000，不人为压缩高功率放电。
     */
    @Test
    fun testCalculatePowerWattsPhysicalCalculation() {
        // 正常日常放电：4.0V, 350mA -> 1.4W
        val normalWatts = BatteryUnitNormalizer.calculatePowerWatts(4.0f, 350f, isCharging = false)
        assertEquals(1.4f, normalWatts, 0.05f)

        // 重载/烤机真实高功率放电：4.0V, 3500mA -> 14.0W（忠实保留真实功率，不人为限制）
        val heavyLoadWatts = BatteryUnitNormalizer.calculatePowerWatts(4.0f, 3500f, isCharging = false)
        assertEquals(14.0f, heavyLoadWatts, 0.05f)

        // 充电状态下（例如 65W 快充）
        val chargingWatts = BatteryUnitNormalizer.calculatePowerWatts(9.0f, 5000f, isCharging = true)
        assertEquals(45.0f, chargingWatts, 0.1f)
    }

    /**
     * 测试移除所有人为物理上限后，极大功率充电（如 240W 快充）与高电压不被 150W 或 12V 截断。
     */
    @Test
    fun testUncappedHighPowerAndVoltage() {
        // 20V, 12000mA (12A) 对应 240W 超级快充，忠实反映真实 240.0W，不再被 150W 截断
        val ultraChargingWatts = BatteryUnitNormalizer.calculatePowerWatts(20.0f, 12000f, isCharging = true)
        assertEquals(240.0f, ultraChargingWatts, 0.1f)

        // 15V 原生伏特测试 (1..24 -> rawVolt * 1000f)
        val directVoltsMv = BatteryUnitNormalizer.normalizeVoltageMv(15L) // 15 V -> 15000 mV
        assertEquals(15000f, directVoltsMv, 0.01f)
        assertEquals(15.0f, BatteryUnitNormalizer.normalizeVoltageVolts(15L), 0.001f)
    }
}
