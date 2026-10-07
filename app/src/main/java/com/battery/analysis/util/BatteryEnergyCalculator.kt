package com.battery.analysis.util

import java.util.Locale

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

    /**
     * 将消耗或统计的能量数值（单位：Wh）基于电池标称电压转换为等效电量（单位：mAh），并构建详细的提示说明文案。
     * 换算公式遵循物理定律：电量(mAh) = 能量(Wh) * 1000 / 标称电压(V)。
     *
     * @param title 指标分类标题（如“亮屏”、“息屏”、“全局”）
     * @param energyWh 实际消耗的能量数值（单位：Wh）
     * @param ratioStr 能量占比文本（可选，如 "22.0%"）
     * @param nominalVoltageVolts 折算采用的标称电压（单位：V，默认采用 3.85V）
     * @return 格式化后的详细提示文案
     */
    fun formatEnergyConversionMessage(
        title: String,
        energyWh: Float,
        ratioStr: String? = null,
        nominalVoltageVolts: Float = DEFAULT_NOMINAL_VOLTAGE_VOLTS
    ): String {
        val safeNominalVoltage = if (nominalVoltageVolts > 0f) nominalVoltageVolts else DEFAULT_NOMINAL_VOLTAGE_VOLTS
        val mah = (energyWh * 1000f) / safeNominalVoltage
        val energyStr = String.format(java.util.Locale.getDefault(), "%.3fWh", energyWh)
        val energyWithRatio = if (!ratioStr.isNullOrBlank() && ratioStr != "--%") {
            "$energyStr ($ratioStr)"
        } else {
            energyStr
        }
        val mahStr = String.format(java.util.Locale.getDefault(), "%.1f mAh", mah)
        val roundedMah = Math.round(mah)

        return buildString {
            append("${title}消耗能量：$energyWithRatio\n")
            append("折算等效电量：约 $mahStr (≈ ${roundedMah}mAh)\n")
            append("换算基准：标称电压 ${safeNominalVoltage}V\n\n")
            append("💡 换算说明：\n")
            append("电量(mAh) = 能量(Wh) × 1000 ÷ 标称电压(${safeNominalVoltage}V)。\n")
            append("锂电池物理放电能量由端电压与电荷量积分所得。行业通常基于标准标称电压(${safeNominalVoltage}V)将实际物理能量折算为等效电量；实际放电过程中电池端电压通常随负载与剩余电量在 3.6V~4.4V 之间动态变化。")
        }
    }

    /**
     * 格式化电池容量（单位：mAh），并在其后附加按标准标称工作电压折算得到的能量数据（单位：Wh）。
     * 折算公式：能量(Wh) = (容量(mAh) * V_nominal) / 1000。
     * 若容量有效（大于 0），格式化为 "%.1f mAh (%.2f Wh)"；
     * 若容量存在但非正数，仅格式化为 "%.1f mAh"；
     * 若容量缺失（为 null），返回 null（忠实反映系统真实数据，严禁伪造假数据）。
     *
     * @param capacityMah 电池容量数值（单位：mAh），若缺失传入 null
     * @param nominalVoltageVolts 电池标称工作电压（单位：V，默认采用 [DEFAULT_NOMINAL_VOLTAGE_VOLTS] 即 3.85V）
     * @return 格式化后的容量与能量字符串（如 "5000.0 mAh (19.25 Wh)"），或在数据缺失时返回 null
     */
    fun formatCapacityWithNominalWh(
        capacityMah: Float?,
        nominalVoltageVolts: Float = DEFAULT_NOMINAL_VOLTAGE_VOLTS
    ): String? {
        if (capacityMah == null) {
            return null
        }
        val totalWh = calculateTotalEnergyWh(capacityMah, nominalVoltageVolts)
        return if (totalWh != null) {
            String.format(Locale.getDefault(), "%.1f mAh (%.2f Wh)", capacityMah, totalWh)
        } else {
            String.format(Locale.getDefault(), "%.1f mAh", capacityMah)
        }
    }
}


