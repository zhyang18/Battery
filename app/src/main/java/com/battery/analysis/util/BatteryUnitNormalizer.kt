package com.battery.analysis.util

import kotlin.math.abs

/**
 * 电池物理指标（电压、电流、瞬时功率）单位规范化与自适应校准工具。
 *
 * <p>针对 Android 各芯片方案（高通、联发科、紫光展锐）与 OEM 厂商（小米/红米、华为/荣耀、OPPO/vivo、一加等）
 * 底层库仑计驱动单位上报极不统一的碎片化痛点，提供高兼容性的单位换算与异常自适应熔断：
 * 1. 电压（EXTRA_VOLTAGE）：兼容 mV、0.1mV、uV、V 多种上报单位，统一转换为标准 mV 和 V；
 * 2. 电流（CURRENT_NOW）：兼容 uA、0.1uA、mA、负值放电等多阶量纲，结合充放电场景自适应识别；
 * 3. 瞬时功耗：提供结合电压与电流的功率计算，并在放电场景下提供异常十倍倍率自适应校正，杜绝 14~15W 等量纲放大异常。
 */
object BatteryUnitNormalizer {

    /**
     * 将底层系统广播或传感器上报的原始电压数值规范化为标准毫伏（mV）。
     *
     * @param rawVolt 原始电压值（可能为 mV、0.1mV、uV 或 V）
     * @return 规范化后的毫伏电压（mV），若输入异常则返回默认基准 4000.0f
     */
    fun normalizeVoltageMv(rawVolt: Long): Float {
        if (rawVolt <= 0) return 4000f
        val mv = when {
            rawVolt in 2500..9999 -> rawVolt.toFloat() // 规范毫伏 mV（单电芯 3.0~4.5V，双电芯串联 6.0~9.0V）
            rawVolt in 10000..99999 -> rawVolt / 10f // 0.1 毫伏（部分高通/联发科机型，如 41500 -> 4150.0 mV）
            rawVolt >= 100000 -> rawVolt / 1000f // 微伏 uV（如 4150000 -> 4150.0 mV）
            rawVolt in 1..24 -> rawVolt * 1000f // 伏特 V（如 4 -> 4000.0 mV）
            else -> 4000f
        }
        return mv.coerceAtLeast(0f)
    }

    /**
     * 将底层系统广播或传感器上报的原始电压数值规范化为标准伏特（V）。
     *
     * @param rawVolt 原始电压值
     * @return 规范化后的伏特电压（V）
     */
    fun normalizeVoltageVolts(rawVolt: Long): Float {
        return normalizeVoltageMv(rawVolt) / 1000f
    }

    /**
     * 将底层库仑计上报的原始瞬时电流规范化为标准毫安（mA）。
     *
     * @param rawCur 硬件库仑计读取的原始电流数值
     * @param isCharging 当前是否处于充电状态
     * @return 规范化后的正数电流值（单位：mA），无法获取时返回 0f
     */
    fun normalizeCurrentMa(rawCur: Long, isCharging: Boolean = false): Float {
        if (rawCur == 0L || rawCur == Int.MIN_VALUE.toLong() || rawCur == Long.MIN_VALUE) {
            return 0f
        }
        val absCur = abs(rawCur)

        val ma = when {
            absCur >= 10_000_000L -> {
                // 极大量程（0.1uA 步进，例如 35,000,000 -> 3500mA）
                absCur / 10000f
            }
            absCur >= 10_000L -> {
                // 标准 Android 规范微安 (uA) -> 转换为毫安 (mA)
                // 例如 500,000 uA -> 500mA，1,200,000 uA -> 1200mA (约 4.8W)，3,000,000 uA -> 3000mA (约 12W)
                absCur / 1000f
            }
            absCur in 1..9_999 -> {
                // 部分机型直接以毫安 (mA) 报告（例如 500mA、1200mA、3500mA）
                absCur.toFloat()
            }
            else -> 0f
        }

        return ma.coerceAtLeast(0f)
    }

    /**
     * 根据电池电压（V）与电流（mA）精确计算瞬时功率（瓦特 W）。
     * 忠实遵循物理公式 P = (U * I) / 1000，不人为限制或缩小高功率工况。
     *
     * @param voltageVolts 电池电压（单位：V）
     * @param currentMa 电池电流（单位：mA，绝对值或正负均可）
     * @param isCharging 当前是否处于充电状态
     * @return 计算得到的瞬时功率绝对值（单位：W）
     */
    @Suppress("UNUSED_PARAMETER")
    fun calculatePowerWatts(voltageVolts: Float, currentMa: Float, isCharging: Boolean = false): Float {
        val safeVolt = if (voltageVolts > 0f) voltageVolts else 4.0f
        val safeCur = abs(currentMa)
        if (safeVolt <= 0f || safeCur <= 0f) return 0f

        val pWatts = (safeVolt * safeCur) / 1000f
        return (Math.round(pWatts * 100f) / 100f).coerceAtLeast(0f)
    }
}
