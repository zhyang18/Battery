package com.battery.analysis.util

/**
 * 电池剩余能量（瓦时 Wh）高精度计算器。
 * 封装并统一多级能量计算策略，优先采用硬件原生能量/电荷计数器，并支持历史满充容量与设计容量多级降级推算。
 */
object BatteryEnergyCalculator {

    // 纳瓦时转瓦时系数 (1 Wh = 10^9 nWh)
    private const val NWH_TO_WH_FACTOR = 1_000_000_000f

    // 默认锂电池标称工作电压（单位：V，标准锂离子/锂聚合物电池标称中位放电电压为 3.85V）
    const val DEFAULT_NOMINAL_VOLTAGE_VOLTS = 3.85f

    /**
     * 计算当前电池高精度剩余能量（单位：瓦时 Wh）。
     *
     * 计算优先级策略：
     * 1. 硬件原生能量计数器 [hardwareEnergyNwh]：若硬件支持且数值为正数，直接转为 Wh；
     * 2. 硬件实时电荷计数器 [hardwareChargeCounterUah]：获取当前硬件实时剩余毫安时（mAh），基于电池标称电压折算：(mAh * V_nominal) / 1000；
     * 3. 基准容量与百分比推算降级：以当前设备基准容量（优先满充真实容量 FCC，其次设计容量）结合百分比与电池标称电压推算：(基准容量 * 百分比 * V_nominal) / 1000。
     *
     * @param hardwareEnergyNwh 硬件层读取的纳瓦时能量计数器（BATTERY_PROPERTY_ENERGY_COUNTER），若不支持可传入 null
     * @param hardwareChargeCounterUah 硬件层读取的微安时电荷计数器（BATTERY_PROPERTY_CHARGE_COUNTER），若不支持可传入 null
     * @param batteryPercent 当前电量百分比（0-100）
     * @param nominalVoltageVolts 电池标称工作电压（单位：V，默认 3.85V，避免瞬时端电压与负载波动导致能量虚高或跳变）
     * @param effectiveCapacityMah 设备有效基准容量（单位：mAh）
     * @return 计算得到的当前电池剩余能量（单位：Wh）
     */
    fun calculateRemainingEnergyWh(
        hardwareEnergyNwh: Long?,
        hardwareChargeCounterUah: Int?,
        batteryPercent: Int,
        nominalVoltageVolts: Float = DEFAULT_NOMINAL_VOLTAGE_VOLTS,
        effectiveCapacityMah: Float
    ): Float {
        val safeNominalVoltage = if (nominalVoltageVolts > 0f) nominalVoltageVolts else DEFAULT_NOMINAL_VOLTAGE_VOLTS
        val safePercent = batteryPercent.coerceIn(0, 100)

        // 策略 1：硬件能量计数器直读 (nWh -> Wh)，忠实遵循硬件上报
        if (hardwareEnergyNwh != null && hardwareEnergyNwh > 0L) {
            val wh = hardwareEnergyNwh / NWH_TO_WH_FACTOR
            if (wh > 0f) {
                return wh
            }
        }

        // 策略 2：硬件实时电荷计数器 (uAh/mAh -> Wh)，基于标称电压折算
        if (hardwareChargeCounterUah != null && hardwareChargeCounterUah > 0) {
            val currentMah = if (hardwareChargeCounterUah < 100000) {
                hardwareChargeCounterUah.toFloat()
            } else {
                hardwareChargeCounterUah / 1000f
            }

            if (currentMah > 0f) {
                val wh = (currentMah * safeNominalVoltage) / 1000f
                if (wh > 0f) {
                    return wh
                }
            }
        }

        // 策略 3：标准基准容量与电量百分比结合标称电压计算
        if (effectiveCapacityMah <= 0f) {
            return 0f
        }
        val remainingMah = effectiveCapacityMah * (safePercent / 100f)
        return (remainingMah * safeNominalVoltage) / 1000f
    }

    /**
     * 计算设备电池总能量（单位：瓦时 Wh）。
     * 基于设备有效基准容量（满充真实容量 FCC 或出厂设计容量）与电池标称电压折算：(基准容量 * V_nominal) / 1000。
     * 若基准容量缺失或非正数，如实返回 null，忠实反映系统真实状态（严禁伪造假数据）。
     *
     * @param effectiveCapacityMah 设备有效基准容量（单位：mAh）
     * @param nominalVoltageVolts 电池标称工作电压（单位：V，默认 3.85V）
     * @return 计算得到的电池总能量（单位：Wh），若容量缺失或无效则返回 null
     */
    fun calculateTotalEnergyWh(
        effectiveCapacityMah: Float,
        nominalVoltageVolts: Float = DEFAULT_NOMINAL_VOLTAGE_VOLTS
    ): Float? {
        if (effectiveCapacityMah <= 0f) {
            return null
        }
        val safeNominalVoltage = if (nominalVoltageVolts > 0f) nominalVoltageVolts else DEFAULT_NOMINAL_VOLTAGE_VOLTS
        val totalWh = (effectiveCapacityMah * safeNominalVoltage) / 1000f
        return if (totalWh > 0f) totalWh else null
    }
}

