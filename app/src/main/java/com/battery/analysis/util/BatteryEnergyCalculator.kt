package com.battery.analysis.util

/**
 * 电池剩余能量（瓦时 Wh）高精度计算器。
 * 封装并统一多级能量计算策略，优先采用硬件原生能量/电荷计数器，并支持历史满充容量与设计容量多级降级推算。
 */
object BatteryEnergyCalculator {

    // 硬件能量计数器（纳瓦时 nWh）有效范围阈值：0.1Wh ~ 120Wh (1e8 ~ 1.2e11 nWh)
    private const val MIN_VALID_ENERGY_NWH = 100_000_000L
    private const val MAX_VALID_ENERGY_NWH = 120_000_000_000L

    // 纳瓦时转瓦时系数 (1 Wh = 10^9 nWh)
    private const val NWH_TO_WH_FACTOR = 1_000_000_000f

    // 默认兜底基准容量（mAh）
    const val DEFAULT_FALLBACK_CAPACITY_MAH = 5000f

    /**
     * 计算当前电池高精度剩余能量（单位：瓦时 Wh）。
     *
     * 计算优先级策略：
     * 1. 硬件原生能量计数器 [hardwareEnergyNwh]：若硬件支持且数值处于合理物理区间（0.1Wh ~ 120Wh），直接转为 Wh；
     * 2. 硬件实时电荷计数器 [hardwareChargeCounterUah]：获取当前硬件实时剩余毫安时（mAh），进行合理性校验后结合实时电压折算：(mAh * V) / 1000；
     * 3. 基准容量与百分比推算降级：以当前设备基准容量（优先满充真实容量 FCC，其次设计容量）结合百分比与实时电压推算：(基准容量 * 百分比 * 电压) / 1000。
     *
     * @param hardwareEnergyNwh 硬件层读取的纳瓦时能量计数器（BATTERY_PROPERTY_ENERGY_COUNTER），若不支持可传入 null
     * @param hardwareChargeCounterUah 硬件层读取的微安时电荷计数器（BATTERY_PROPERTY_CHARGE_COUNTER），若不支持可传入 null
     * @param batteryPercent 当前电量百分比（0-100）
     * @param voltageVolts 当前电池实时电压（单位：V）
     * @param effectiveCapacityMah 设备有效基准容量（单位：mAh）
     * @return 计算得到的当前电池剩余能量（单位：Wh）
     */
    fun calculateRemainingEnergyWh(
        hardwareEnergyNwh: Long?,
        hardwareChargeCounterUah: Int?,
        batteryPercent: Int,
        voltageVolts: Float,
        effectiveCapacityMah: Float
    ): Float {
        // 安全保护：电压异常或为 0 时限制在常规工作电压下限 3.0V，避免算得 0 或负数
        val safeVoltage = if (voltageVolts > 0.5f) voltageVolts else 3.85f
        val safePercent = batteryPercent.coerceIn(0, 100)
        val safeCapacity = if (effectiveCapacityMah in 500f..30000f) effectiveCapacityMah else DEFAULT_FALLBACK_CAPACITY_MAH

        // 策略 1：硬件能量计数器直读 (nWh -> Wh)
        if (hardwareEnergyNwh != null && hardwareEnergyNwh in MIN_VALID_ENERGY_NWH..MAX_VALID_ENERGY_NWH) {
            val wh = hardwareEnergyNwh / NWH_TO_WH_FACTOR
            if (wh > 0.05f) {
                return wh
            }
        }

        // 策略 2：硬件实时电荷计数器 (uAh/mAh -> Wh)
        if (hardwareChargeCounterUah != null && hardwareChargeCounterUah > 0) {
            val currentMah = if (hardwareChargeCounterUah < 100000) {
                hardwareChargeCounterUah.toFloat()
            } else {
                hardwareChargeCounterUah / 1000f
            }

            // 校验当前读取的剩余电量是否在物理可信区间内，防止个别 HAL 返回常数满电或异常数值
            val expectedMah = safeCapacity * (safePercent / 100f)
            val isValidReading = if (expectedMah > 0f) {
                // 允许与理论电量存在合理容差（适应电池老化、动态估算波动），且在 10 ~ 25000 mAh 之间
                currentMah in (expectedMah * 0.4f)..(expectedMah * 1.6f + 100f) && currentMah in 10f..25000f
            } else {
                currentMah in 0f..safeCapacity * 1.5f
            }

            if (isValidReading) {
                val wh = (currentMah * safeVoltage) / 1000f
                if (wh > 0.05f) {
                    return wh
                }
            }
        }

        // 策略 3：标准基准容量与电量百分比结合实时电压计算
        val remainingMah = safeCapacity * (safePercent / 100f)
        return (remainingMah * safeVoltage) / 1000f
    }
}
