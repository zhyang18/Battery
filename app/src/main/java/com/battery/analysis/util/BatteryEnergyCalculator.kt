package com.battery.analysis.util

import java.util.Locale
import com.battery.analysis.manager.PowerOverviewStats
import com.battery.analysis.manager.PowerUsageManager

/**
 * 设备电池物理总能量及获取方式详情实体类。
 *
 * @property totalWh 电池满电物理总能量（单位：Wh）
 * @property sourceDescription 总能量获取方式或底层物理依据描述（如“硬件原生能量计数器”、“硬件实时电荷计数器”、“真实满充容量”、“出厂设计容量”等）
 * @property equivalentMah 对应的等效基准电量（单位：mAh）
 */
data class BatteryTotalEnergyInfo(
    val totalWh: Float,
    val sourceDescription: String,
    val equivalentMah: Float
)

/**
 * 电池剩余能量（瓦时 Wh）高精度计算器。
 * 封装并统一多级能量计算策略，优先采用硬件原生能量/电荷计数器，并支持历史满充容量与设计容量多级降级推算。
 */
object BatteryEnergyCalculator {

    // 纳瓦时转瓦时系数 (1 Wh = 10^9 nWh)
    private const val NWH_TO_WH_FACTOR = 1_000_000_000f

    // 默认锂电池标称工作电压（单位：V，标准锂离子/锂聚合物电池标称中位放电电压为 3.85V）
    const val DEFAULT_NOMINAL_VOLTAGE_VOLTS = 3.85f

    // 核心功耗与放电速度卡片行类型常量标识（与 PowerUsageFragment 严格保持一致：0为亮屏，1为息屏，2为全局）
    const val ROW_SCREEN_ON = 0
    const val ROW_SCREEN_OFF = 1
    const val ROW_GLOBAL = 2

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
     * 计算设备电池总能量详情（单位：瓦时 Wh）及获取方式描述。
     *
     * 遵循底层物理优先级逻辑：
     * 1. 硬件原生能量计数器 [hardwareEnergyNwh] 结合当前电量百分比 [batteryPercent] 反推 100% 满电物理总能量；
     * 2. 硬件实时电荷计数器 [hardwareChargeCounterUah] 结合当前电量百分比与标称电压折算满电总能量；
     * 3. 基于设备有效基准容量（真实满充容量 FCC 或出厂设计容量）与标称电压物理换算。
     * 若均无法获取则如实返回 null，忠实反映系统真实状态（严禁伪造假数据）。
     *
     * @param hardwareEnergyNwh 硬件层读取的纳瓦时能量计数器（BATTERY_PROPERTY_ENERGY_COUNTER），若不支持传入 null
     * @param hardwareChargeCounterUah 硬件层读取的微安时电荷计数器（BATTERY_PROPERTY_CHARGE_COUNTER），若不支持传入 null
     * @param batteryPercent 当前电量百分比（1-100）
     * @param effectiveCapacityMah 设备有效基准容量（单位：mAh）
     * @param capacitySource 有效基准容量的来源描述（如“真实满充容量”、“出厂设计容量”，可选）
     * @param nominalVoltageVolts 电池标称工作电压（单位：V，默认 3.85V）
     * @return 包含总能量数值与来源方式的详情对象 [BatteryTotalEnergyInfo]，若无法获取则返回 null
     */
    fun calculateTotalEnergyInfo(
        hardwareEnergyNwh: Long? = null,
        hardwareChargeCounterUah: Int? = null,
        batteryPercent: Int? = null,
        effectiveCapacityMah: Float,
        capacitySource: String? = null,
        nominalVoltageVolts: Float = DEFAULT_NOMINAL_VOLTAGE_VOLTS
    ): BatteryTotalEnergyInfo? {
        val safeNominalVoltage = if (nominalVoltageVolts > 0f) nominalVoltageVolts else DEFAULT_NOMINAL_VOLTAGE_VOLTS
        val safePercent = batteryPercent?.coerceIn(1, 100)

        // 策略 1：硬件能量计数器结合当前电量反推满电物理总能量 (nWh -> Wh)
        if (hardwareEnergyNwh != null && hardwareEnergyNwh > 0L && safePercent != null && safePercent > 0) {
            val remainWh = hardwareEnergyNwh / NWH_TO_WH_FACTOR
            val totalWh = (remainWh / safePercent) * 100f
            if (totalWh > 0f) {
                val mah = (totalWh * 1000f) / safeNominalVoltage
                return BatteryTotalEnergyInfo(
                    totalWh = totalWh,
                    sourceDescription = "硬件原生能量计数器",
                    equivalentMah = mah
                )
            }
        }

        // 策略 2：硬件实时电荷计数器结合当前电量与标称电压折算满电总能量
        if (hardwareChargeCounterUah != null && hardwareChargeCounterUah > 0 && safePercent != null && safePercent > 0) {
            val currentMah = if (hardwareChargeCounterUah < 100000) {
                hardwareChargeCounterUah.toFloat()
            } else {
                hardwareChargeCounterUah / 1000f
            }
            val fullMah = (currentMah / safePercent) * 100f
            if (fullMah > 0f) {
                val totalWh = (fullMah * safeNominalVoltage) / 1000f
                if (totalWh > 0f) {
                    return BatteryTotalEnergyInfo(
                        totalWh = totalWh,
                        sourceDescription = "硬件实时电荷计数器",
                        equivalentMah = fullMah
                    )
                }
            }
        }

        // 策略 3：基于有效基准容量（FCC 或设计容量）结合标称电压物理折算
        if (effectiveCapacityMah > 0f) {
            val totalWh = (effectiveCapacityMah * safeNominalVoltage) / 1000f
            if (totalWh > 0f) {
                val source = if (!capacitySource.isNullOrBlank() && capacitySource != "未知") {
                    capacitySource
                } else {
                    "有效基准容量"
                }
                return BatteryTotalEnergyInfo(
                    totalWh = totalWh,
                    sourceDescription = source,
                    equivalentMah = effectiveCapacityMah
                )
            }
        }

        return null
    }

    /**
     * 计算设备电池总能量（单位：瓦时 Wh）。
     *
     * 遵循系统底层物理能量获取优先级逻辑：
     * 1. 硬件原生能量计数器 [hardwareEnergyNwh] 结合当前电量百分比 [batteryPercent] 反推 100% 满电物理总能量；
     * 2. 硬件实时电荷计数器 [hardwareChargeCounterUah] 结合当前电量百分比与标称电压折算满电总能量；
     * 3. 基于设备有效基准容量（满充真实容量 FCC 或出厂设计容量）与标称电压物理换算：(有效容量 * V_nominal) / 1000。
     * 若均缺失或非正数，如实返回 null，忠实反映系统真实状态（严禁伪造假数据）。
     *
     * @param hardwareEnergyNwh 硬件层读取的纳瓦时能量计数器（BATTERY_PROPERTY_ENERGY_COUNTER），若不支持可传入 null
     * @param hardwareChargeCounterUah 硬件层读取的微安时电荷计数器（BATTERY_PROPERTY_CHARGE_COUNTER），若不支持可传入 null
     * @param batteryPercent 当前电量百分比（1-100）
     * @param effectiveCapacityMah 设备有效基准容量（单位：mAh）
     * @param nominalVoltageVolts 电池标称工作电压（单位：V，默认 3.85V）
     * @return 计算得到的设备电池总能量（单位：Wh），若容量缺失或无效则返回 null
     */
    fun calculateTotalEnergyWh(
        hardwareEnergyNwh: Long? = null,
        hardwareChargeCounterUah: Int? = null,
        batteryPercent: Int? = null,
        effectiveCapacityMah: Float,
        nominalVoltageVolts: Float = DEFAULT_NOMINAL_VOLTAGE_VOLTS
    ): Float? {
        return calculateTotalEnergyInfo(
            hardwareEnergyNwh = hardwareEnergyNwh,
            hardwareChargeCounterUah = hardwareChargeCounterUah,
            batteryPercent = batteryPercent,
            effectiveCapacityMah = effectiveCapacityMah,
            capacitySource = null,
            nominalVoltageVolts = nominalVoltageVolts
        )?.totalWh
    }

    /**
     * 计算设备电池总能量（单位：瓦时 Wh）。
     * 保持向后兼容的单参数与双参数便捷重载方法，基于有效基准容量折算。
     *
     * @param effectiveCapacityMah 设备有效基准容量（单位：mAh）
     * @param nominalVoltageVolts 电池标称工作电压（单位：V，默认 3.85V）
     * @return 计算得到的设备电池总能量（单位：Wh），若容量缺失或无效则返回 null
     */
    fun calculateTotalEnergyWh(
        effectiveCapacityMah: Float,
        nominalVoltageVolts: Float = DEFAULT_NOMINAL_VOLTAGE_VOLTS
    ): Float? {
        return calculateTotalEnergyWh(
            hardwareEnergyNwh = null,
            hardwareChargeCounterUah = null,
            batteryPercent = null,
            effectiveCapacityMah = effectiveCapacityMah,
            nominalVoltageVolts = nominalVoltageVolts
        )
    }

    /**
     * 将消耗或统计的能量数值（单位：Wh）基于电池标称电压转换为等效电量（单位：mAh），并构建详细的提示说明文案。
     * 换算公式遵循物理定律：电量(mAh) = 能量(Wh) * 1000 / 标称电压(V)。
     *
     * @param title 指标分类标题（如“亮屏”、“息屏”、“全局”）
     * @param energyWh 实际消耗的能量数值（单位：Wh）
     * @param ratioStr 能量占比文本（可选，如 "22.0%"）
     * @param totalEnergyInfo 设备物理总能量及获取来源详情（可选，传入时在文案中展示总能量数据）
     * @param nominalVoltageVolts 折算采用的标称电压（单位：V，默认采用 3.85V）
     * @return 格式化后的详细提示文案
     */
    fun formatEnergyConversionMessage(
        title: String,
        energyWh: Float,
        ratioStr: String? = null,
        totalEnergyInfo: BatteryTotalEnergyInfo? = null,
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
            append("• ${title}消耗能量：$energyWithRatio\n")
            append("• 折算等效电量：约 $mahStr (≈ ${roundedMah}mAh)\n")
            append("• 换算基准：标称电压 ${safeNominalVoltage}V\n")
            if (totalEnergyInfo != null && totalEnergyInfo.totalWh > 0f) {
                val totalWhStr = String.format(java.util.Locale.getDefault(), "%.3fWh", totalEnergyInfo.totalWh)
                val roundedTotalMah = Math.round(totalEnergyInfo.equivalentMah)
                append("• ${totalEnergyInfo.sourceDescription}获得的总能量：$totalWhStr (≈ ${roundedTotalMah}mAh)\n")
            }
            append("\n💡 换算说明：\n")
            append("• 电量(mAh) = 能量(Wh) × 1000 ÷ 标称电压(${safeNominalVoltage}V)；\n")
            append("• 按行业标准标称电压将实际物理释放能量折算为等效电量。")
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

    /**
     * 格式化息屏卡片点击弹框展示的详细说明文本。
     * 全面呈现息屏掉电占比、时长、能量、电量、平均功率及亮屏对比数据，真实反映设备在息屏与亮屏下的能耗分配。
     *
     * @param overview 耗电概览核心统计数据实体
     * @param nominalTotalWh 设备电池满电物理总能量（单位：Wh，若不可用传入 null）
     * @param nominalVoltageVolts 电池标称工作电压（单位：V，默认 3.85V）
     * @return 息屏卡片弹框说明文本字符串
     */
    fun formatScreenOffCardDetailMessage(
        overview: PowerOverviewStats,
        nominalTotalWh: Float? = null,
        nominalVoltageVolts: Float = DEFAULT_NOMINAL_VOLTAGE_VOLTS
    ): String {
        val safeNominalVoltage = if (nominalVoltageVolts > 0f) nominalVoltageVolts else DEFAULT_NOMINAL_VOLTAGE_VOLTS
        val offPercentStr = if (overview.screenOffPercent > 0f) {
            String.format(Locale.getDefault(), "%.1f%%", overview.screenOffPercent)
        } else if (overview.screenOffAwakePercent > 0f || overview.screenOffDeepSleepPercent > 0f) {
            String.format(Locale.getDefault(), "%.1f%%", overview.screenOffAwakePercent + overview.screenOffDeepSleepPercent)
        } else {
            "--"
        }
        val offDurStr = if (overview.screenOffDurationMs > 0L) {
            PowerUsageManager.formatCompactDuration(overview.screenOffDurationMs)
        } else {
            overview.screenOffDurationText.ifBlank { "--" }
        }
        val offEnergyStr = String.format(Locale.getDefault(), "%.3f Wh", overview.screenOffEnergyWh)
        val offMah = if (overview.screenOffDrainMah > 0f) {
            overview.screenOffDrainMah
        } else if (overview.screenOffEnergyWh > 0f) {
            (overview.screenOffEnergyWh * 1000f) / safeNominalVoltage
        } else {
            0f
        }
        val offMahStr = if (offMah > 0f) String.format(Locale.getDefault(), "%.1f mAh", offMah) else "--"
        val offWatts = if (overview.screenOffPowerWatts > 0f) {
            overview.screenOffPowerWatts
        } else if (overview.screenOffDurationMs > 0L && overview.screenOffEnergyWh > 0f) {
            (overview.screenOffEnergyWh / (overview.screenOffDurationMs.toDouble() / 3600000.0)).toFloat()
        } else {
            0f
        }
        val offWattsStr = if (offWatts > 0f) String.format(Locale.getDefault(), "%.2f W", offWatts) else "--"

        // 亮屏总耗电数据对比
        val onPercentStr = if (overview.screenOnPercent > 0f) {
            String.format(Locale.getDefault(), "%.1f%%", overview.screenOnPercent)
        } else if (nominalTotalWh != null && nominalTotalWh > 0f && overview.screenOnEnergyWh > 0f) {
            String.format(Locale.getDefault(), "%.1f%%", (overview.screenOnEnergyWh / nominalTotalWh) * 100f)
        } else {
            "--"
        }
        val onDurStr = if (overview.screenOnDurationMs > 0L) {
            PowerUsageManager.formatCompactDuration(overview.screenOnDurationMs)
        } else {
            overview.screenOnDurationText.ifBlank { "--" }
        }
        val onEnergyStr = String.format(Locale.getDefault(), "%.3f Wh", overview.screenOnEnergyWh)
        val onMah = if (overview.screenOnDrainMah > 0f) {
            overview.screenOnDrainMah
        } else if (overview.screenOnEnergyWh > 0f) {
            (overview.screenOnEnergyWh * 1000f) / safeNominalVoltage
        } else {
            0f
        }
        val onMahStr = if (onMah > 0f) String.format(Locale.getDefault(), "%.1f mAh", onMah) else "--"
        val onWatts = if (overview.screenOnPowerWatts > 0f) {
            overview.screenOnPowerWatts
        } else if (overview.screenOnDurationMs > 0L && overview.screenOnEnergyWh > 0f) {
            (overview.screenOnEnergyWh / (overview.screenOnDurationMs.toDouble() / 3600000.0)).toFloat()
        } else {
            0f
        }
        val onWattsStr = if (onWatts > 0f) String.format(Locale.getDefault(), "%.2f W", onWatts) else "--"

        return buildString {
            append("【息屏耗电】\n")
            append("• 息屏掉电：$offPercentStr（待机 $offDurStr）\n")
            append("• 消耗能量：$offEnergyStr (折合 $offMahStr)\n")
            append("• 平均功率：$offWattsStr\n\n")
            append("【亮屏对比】\n")
            append("• 亮屏掉电：$onPercentStr（时长 $onDurStr）\n")
            append("• 消耗能量：$onEnergyStr (折合 $onMahStr)\n")
            append("• 平均功率：$onWattsStr\n\n")
            append("💡 说明：\n")
            append("• 占比基于整机电池满电总能量基准计算；\n")
            append("• 息屏能耗由后台唤醒与深度休眠底噪构成。")
        }
    }

    /**
     * 格式化唤醒卡片点击弹框展示的详细说明文本。
     * 呈现息屏唤醒掉电占比、唤醒时长、能量、电量、唤醒平均功率以及唤醒总次数与频次等核心数据。
     *
     * @param overview 耗电概览核心统计数据实体
     * @param nominalTotalWh 设备电池满电物理总能量（单位：Wh，若不可用传入 null）
     * @param nominalVoltageVolts 电池标称工作电压（单位：V，默认 3.85V）
     * @return 唤醒卡片弹框说明文本字符串
     */
    fun formatAwakeCardDetailMessage(
        overview: PowerOverviewStats,
        nominalTotalWh: Float? = null,
        nominalVoltageVolts: Float = DEFAULT_NOMINAL_VOLTAGE_VOLTS
    ): String {
        val safeNominalVoltage = if (nominalVoltageVolts > 0f) nominalVoltageVolts else DEFAULT_NOMINAL_VOLTAGE_VOLTS
        val awakePercentStr = if (overview.isScreenOffDecomposedAvailable && overview.screenOffAwakePercent > 0f) {
            String.format(Locale.getDefault(), "%.1f%%", overview.screenOffAwakePercent)
        } else if (nominalTotalWh != null && nominalTotalWh > 0f && overview.screenOffAwakeEnergyWh > 0f) {
            String.format(Locale.getDefault(), "%.1f%%", (overview.screenOffAwakeEnergyWh / nominalTotalWh) * 100f)
        } else if (overview.screenOffAwakeDurationMs > 0L) {
            "0.0%"
        } else {
            "--"
        }
        val awakeDurStr = if (overview.screenOffAwakeDurationMs > 0L) {
            PowerUsageManager.formatCompactDuration(overview.screenOffAwakeDurationMs)
        } else {
            overview.screenOffAwakeDurationText.ifBlank { "0s" }
        }
        val awakeEnergyStr = if (overview.isScreenOffDecomposedAvailable && overview.screenOffAwakeEnergyWh >= 0.001f) {
            String.format(Locale.getDefault(), "%.3f Wh", overview.screenOffAwakeEnergyWh)
        } else if (overview.isScreenOffDecomposedAvailable && overview.screenOffAwakeEnergyWh > 0f) {
            "<0.001 Wh"
        } else if (overview.screenOffAwakeDurationMs > 0L) {
            "0.000 Wh"
        } else {
            "--"
        }
        val awakeMah = if (overview.isScreenOffDecomposedAvailable && overview.screenOffAwakeDrainMah > 0f) {
            overview.screenOffAwakeDrainMah
        } else if (overview.isScreenOffDecomposedAvailable && overview.screenOffAwakeEnergyWh > 0f) {
            (overview.screenOffAwakeEnergyWh * 1000f) / safeNominalVoltage
        } else {
            0f
        }
        val awakeMahStr = if (awakeMah >= 0.1f) {
            String.format(Locale.getDefault(), "%.1f mAh", awakeMah)
        } else if (overview.screenOffAwakeDurationMs > 0L) {
            "0.0 mAh"
        } else {
            "--"
        }
        val awakeWatts = if (overview.screenOffAwakePowerWatts > 0f) {
            overview.screenOffAwakePowerWatts
        } else if (overview.screenOffAwakeDurationMs > 0L && overview.screenOffAwakeEnergyWh > 0f) {
            (overview.screenOffAwakeEnergyWh / (overview.screenOffAwakeDurationMs.toDouble() / 3600000.0)).toFloat()
        } else {
            0f
        }
        val awakeWattsStr = if (awakeWatts > 0f) String.format(Locale.getDefault(), "%.2f W", awakeWatts) else "--"

        val awakeCountStr = if (overview.screenOffAwakeCount != null) {
            val count = overview.screenOffAwakeCount
            val offHours = overview.screenOffDurationMs / 3600000.0
            if (offHours > 0.1) {
                val perHour = count / offHours
                String.format(Locale.getDefault(), "%d 次 (约 %.1f 次/小时)", count, perHour)
            } else {
                String.format(Locale.getDefault(), "%d 次", count)
            }
        } else {
            "未获取"
        }

        val awakeTimeRatioStr = if (overview.screenOffDurationMs > 0L && overview.screenOffAwakeDurationMs >= 0L) {
            val ratio = (overview.screenOffAwakeDurationMs.toDouble() / overview.screenOffDurationMs.toDouble() * 100.0).coerceIn(0.0, 100.0)
            String.format(Locale.getDefault(), "%.1f%%", ratio)
        } else {
            "--"
        }

        return buildString {
            append("【息屏唤醒】\n")
            append("• 唤醒掉电：$awakePercentStr（活跃 $awakeDurStr）\n")
            append("• 消耗能量：$awakeEnergyStr (折合 $awakeMahStr)\n")
            append("• 平均功率：$awakeWattsStr\n")
            append("• 唤醒频次：$awakeCountStr\n")
            append("• 唤醒占比：$awakeTimeRatioStr（占息屏时长）\n\n")
            append("💡 说明：\n")
            append("• 熄屏后被系统广播、推送或定时任务唤醒的活跃状态；\n")
            append("• 唤醒过频或时长过长是待机异常掉电的主要诱因。")
        }
    }

    /**
     * 格式化深度睡眠卡片点击弹框展示的详细说明文本。
     * 呈现深度睡眠掉电占比、休眠时长、能量、电量、底噪功率以及深度睡眠率、进入休眠次数等待机健康度详情数据。
     *
     * @param overview 耗电概览核心统计数据实体
     * @param nominalTotalWh 设备电池满电物理总能量（单位：Wh，若不可用传入 null）
     * @param nominalVoltageVolts 电池标称工作电压（单位：V，默认 3.85V）
     * @return 深度睡眠卡片弹框说明文本字符串
     */
    fun formatDeepSleepCardDetailMessage(
        overview: PowerOverviewStats,
        nominalTotalWh: Float? = null,
        nominalVoltageVolts: Float = DEFAULT_NOMINAL_VOLTAGE_VOLTS
    ): String {
        val safeNominalVoltage = if (nominalVoltageVolts > 0f) nominalVoltageVolts else DEFAULT_NOMINAL_VOLTAGE_VOLTS
        val sleepPercentStr = if (overview.isScreenOffDecomposedAvailable && overview.screenOffDeepSleepPercent > 0f) {
            String.format(Locale.getDefault(), "%.1f%%", overview.screenOffDeepSleepPercent)
        } else if (nominalTotalWh != null && nominalTotalWh > 0f && overview.screenOffDeepSleepEnergyWh > 0f) {
            String.format(Locale.getDefault(), "%.1f%%", (overview.screenOffDeepSleepEnergyWh / nominalTotalWh) * 100f)
        } else if (overview.screenOffDeepSleepDurationMs > 0L) {
            "0.0%"
        } else {
            "--"
        }
        val sleepDurStr = if (overview.screenOffDeepSleepDurationMs > 0L) {
            PowerUsageManager.formatCompactDuration(overview.screenOffDeepSleepDurationMs)
        } else {
            overview.screenOffDeepSleepDurationText.ifBlank { "0s" }
        }
        val sleepEnergyStr = if (overview.isScreenOffDecomposedAvailable && overview.screenOffDeepSleepEnergyWh >= 0.001f) {
            String.format(Locale.getDefault(), "%.3f Wh", overview.screenOffDeepSleepEnergyWh)
        } else if (overview.isScreenOffDecomposedAvailable && overview.screenOffDeepSleepEnergyWh > 0f) {
            "<0.001 Wh"
        } else if (overview.screenOffDeepSleepDurationMs > 0L) {
            "0.000 Wh"
        } else {
            "--"
        }
        val sleepMah = if (overview.isScreenOffDecomposedAvailable && overview.screenOffDeepSleepDrainMah > 0f) {
            overview.screenOffDeepSleepDrainMah
        } else if (overview.isScreenOffDecomposedAvailable && overview.screenOffDeepSleepEnergyWh > 0f) {
            (overview.screenOffDeepSleepEnergyWh * 1000f) / safeNominalVoltage
        } else {
            0f
        }
        val sleepMahStr = if (sleepMah >= 0.1f) {
            String.format(Locale.getDefault(), "%.1f mAh", sleepMah)
        } else if (overview.screenOffDeepSleepDurationMs > 0L) {
            "0.0 mAh"
        } else {
            "--"
        }
        val sleepWatts = if (overview.screenOffDeepSleepPowerWatts > 0f) {
            overview.screenOffDeepSleepPowerWatts
        } else if (overview.screenOffDeepSleepDurationMs > 0L && overview.screenOffDeepSleepEnergyWh > 0f) {
            (overview.screenOffDeepSleepEnergyWh / (overview.screenOffDeepSleepDurationMs.toDouble() / 3600000.0)).toFloat()
        } else {
            0f
        }
        val sleepWattsStr = if (sleepWatts > 0f) String.format(Locale.getDefault(), "%.2f W", sleepWatts) else "--"

        val deepSleepRatio = if (overview.screenOffDurationMs > 0L && overview.screenOffDeepSleepDurationMs >= 0L) {
            (overview.screenOffDeepSleepDurationMs.toDouble() / overview.screenOffDurationMs.toDouble() * 100.0).coerceIn(0.0, 100.0)
        } else {
            null
        }
        val deepSleepRatioStr = if (deepSleepRatio != null) String.format(Locale.getDefault(), "%.1f%%", deepSleepRatio) else "--"

        val awakeCountStr = if (overview.screenOffAwakeCount != null) {
            val count = overview.screenOffAwakeCount
            val offHours = overview.screenOffDurationMs / 3600000.0
            if (offHours > 0.1) {
                val perHour = count / offHours
                String.format(Locale.getDefault(), "%d 次 (约 %.1f 次/小时)", count, perHour)
            } else {
                String.format(Locale.getDefault(), "%d 次", count)
            }
        } else {
            "未获取"
        }

        val sleepCountStr = if (overview.screenOffDeepSleepCount != null) {
            String.format(Locale.getDefault(), "%d 次", overview.screenOffDeepSleepCount)
        } else {
            "未获取"
        }

        val deepSleepHours = overview.screenOffDeepSleepDurationMs / 3600000.0
        val sleepRateStr = if (deepSleepHours > 0.1 && sleepMah > 0f) {
            val mahPerHour = sleepMah / deepSleepHours
            if (overview.screenOffDeepSleepPercent > 0f) {
                val percentPerHour = overview.screenOffDeepSleepPercent / deepSleepHours
                String.format(Locale.getDefault(), "%.2f %%/h (%.1f mAh/h)", percentPerHour, mahPerHour)
            } else {
                String.format(Locale.getDefault(), "%.1f mAh/h", mahPerHour)
            }
        } else {
            "--"
        }

        return buildString {
            append("【深度睡眠】\n")
            append("• 深睡掉电：$sleepPercentStr（累计 $sleepDurStr）\n")
            append("• 消耗能量：$sleepEnergyStr (折合 $sleepMahStr)\n")
            append("• 休眠功率：$sleepWattsStr\n")
            append("• 深度睡眠率：$deepSleepRatioStr\n")
            append("• 唤醒频次：$awakeCountStr\n")
            append("• 进入休眠：$sleepCountStr（速率 $sleepRateStr）\n\n")
            append("💡 说明：\n")
            append("• 灭屏后 CPU 挂起仅留硬件底噪的极低功耗模式；\n")
            append("• 睡眠率越接近 100%、唤醒频次越低，表明待机休眠越充分、耗电越平稳。")
        }
    }

    /**
     * 格式化放电速度卡片（亮屏、息屏、全局）点击弹框展示的详细说明文本。
     * 全面呈现当前放电速度、最近7天内统计放电速度与有效时长、满电可用时长以及详细原理解释，
     * 并在亮屏卡片弹框中特别展示预测真实容量（单位：多少Wh（多少mAh））。
     *
     * @param rowType 行分类标识（[ROW_SCREEN_ON] 为亮屏，[ROW_SCREEN_OFF] 为息屏，[ROW_GLOBAL] 为全局）
     * @param currentRatePercentPerHour 当前周期的放电速率（单位：%/h，缺失时为 null）
     * @param sevenDaysRatePercentPerHour 最近7天内的统计放电速率（单位：%/h，缺失时为 null）
     * @param sevenDaysDurationMs 最近7天内的有效统计时长（单位：毫秒，缺失时为 null）
     * @param fullDurationMs 充满电理论可用时长（单位：毫秒，缺失时为 null）
     * @param predictedCapacityWh 预测电池真实容量能量值（单位：Wh，若不可用传入 null）
     * @param predictedCapacityMah 预测电池真实容量电荷值（单位：mAh，若不可用传入 null）
     * @return 格式化后的详细气泡说明字符串
     */
    fun formatDischargeSpeedCardDetailMessage(
        rowType: Int,
        currentRatePercentPerHour: Float?,
        sevenDaysRatePercentPerHour: Float?,
        sevenDaysDurationMs: Long?,
        fullDurationMs: Long?,
        predictedCapacityWh: Float? = null,
        predictedCapacityMah: Float? = null
    ): String {
        val currentRateStr = if (currentRatePercentPerHour != null && currentRatePercentPerHour > 0f) {
            String.format(Locale.getDefault(), "%.1f%%/h", currentRatePercentPerHour)
        } else {
            "--"
        }

        val sevenDaysRateStr = if (sevenDaysRatePercentPerHour != null && sevenDaysRatePercentPerHour > 0f) {
            String.format(Locale.getDefault(), "%.1f%%/h", sevenDaysRatePercentPerHour)
        } else {
            "--"
        }

        val sevenDaysDurStr = formatStatsDuration(sevenDaysDurationMs)
        val fullDurationStr = formatFullDuration(fullDurationMs)

        return when (rowType) {
            ROW_SCREEN_ON -> {
                val capacityStr = formatPredictedCapacity(predictedCapacityWh, predictedCapacityMah)
                buildString {
                    append("【亮屏放电速度】\n")
                    append("• 当前放电速度：$currentRateStr\n")
                    if (sevenDaysDurStr.isNotBlank()) {
                        append("• 最近7天内放电速度：$sevenDaysRateStr（统计时长 $sevenDaysDurStr）\n")
                    } else {
                        append("• 最近7天内放电速度：$sevenDaysRateStr\n")
                    }
                    append("• 满电亮屏续航：$fullDurationStr\n")
                    append("• 预测真实容量：$capacityStr\n\n")
                    append("💡 说明：\n")
                    append("• 速度由亮屏硬件功耗与电池总容量折算；\n")
                    append("• 满电续航按最近7天实际使用加权推算；\n")
                    append("• 预测容量读取电量计充满容量(FCC)与健康度。")
                }
            }
            ROW_SCREEN_OFF -> {
                buildString {
                    append("【息屏放电速度】\n")
                    append("• 当前放电速度：$currentRateStr\n")
                    if (sevenDaysDurStr.isNotBlank()) {
                        append("• 最近7天内放电速度：$sevenDaysRateStr（统计时长 $sevenDaysDurStr）\n")
                    } else {
                        append("• 最近7天内放电速度：$sevenDaysRateStr\n")
                    }
                    append("• 满电待机时长：$fullDurationStr\n\n")
                    append("💡 说明：\n")
                    append("• 速度反映熄屏待机电量消耗；\n")
                    append("• 待机时长按最近7天实际待机加权推算；\n")
                    append("• 由深度休眠底噪与后台唤醒共同构成。")
                }
            }
            ROW_GLOBAL -> {
                buildString {
                    append("【全局放电速度】\n")
                    append("• 当前放电速度：$currentRateStr\n")
                    if (sevenDaysDurStr.isNotBlank()) {
                        append("• 最近7天内放电速度：$sevenDaysRateStr（统计时长 $sevenDaysDurStr）\n")
                    } else {
                        append("• 最近7天内放电速度：$sevenDaysRateStr\n")
                    }
                    append("• 满电综合续航：$fullDurationStr\n\n")
                    append("💡 说明：\n")
                    append("• 综合反映日常整机实际掉电表现；\n")
                    append("• 综合续航按最近7天综合放电加权推算；\n")
                    append("• 融合亮屏活跃与息屏待机全周期能耗。")
                }
            }
            else -> ""
        }
    }

    /**
     * 将预测真实容量格式化为“多少Wh（多少mAh）”标准展示文本。
     * 忠实反映系统底层硬件数据，缺失时如实返回“未获取”，严禁捏造假数据。
     *
     * @param capacityWh 预测真实能量容量（单位：Wh，若缺失传入 null）
     * @param capacityMah 预测真实电荷容量（单位：mAh，若缺失传入 null）
     * @param nominalVoltageVolts 电池标称电压（单位：V，默认 3.85V）
     * @return 格式化后的容量说明字符串，例如 "19.25 Wh (5000 mAh)" 或 "未获取"
     */
    fun formatPredictedCapacity(
        capacityWh: Float?,
        capacityMah: Float?,
        nominalVoltageVolts: Float = DEFAULT_NOMINAL_VOLTAGE_VOLTS
    ): String {
        val safeNominalVoltage = if (nominalVoltageVolts > 0f) nominalVoltageVolts else DEFAULT_NOMINAL_VOLTAGE_VOLTS
        val effWh = capacityWh?.takeIf { it > 0f }
            ?: capacityMah?.takeIf { it > 0f }?.let { (it * safeNominalVoltage) / 1000f }
        val effMah = capacityMah?.takeIf { it > 0f }
            ?: capacityWh?.takeIf { it > 0f }?.let { (it * 1000f) / safeNominalVoltage }

        if (effWh == null && effMah == null) {
            return "未获取"
        }
        val whStr = effWh?.let { String.format(Locale.getDefault(), "%.2f Wh", it) }
        val mahStr = effMah?.let {
            if (it % 1f == 0f) {
                String.format(Locale.getDefault(), "%.0f mAh", it)
            } else {
                String.format(Locale.getDefault(), "%.1f mAh", it)
            }
        }

        return when {
            whStr != null && mahStr != null -> "$whStr ($mahStr)"
            whStr != null -> whStr
            mahStr != null -> mahStr
            else -> "未获取"
        }
    }

    /**
     * 将 7 天统计窗口内的有效统计时长格式化为友好紧凑字符串。
     *
     * @param ms 实际有效统计时长物理毫秒数
     * @return 格式化后的紧凑时长字符串，数据无效时返回空字符串
     */
    private fun formatStatsDuration(ms: Long?): String {
        if (ms == null || ms <= 0L) return ""
        val totalSec = ms / 1000L
        val days = totalSec / 86400L
        val hours = (totalSec % 86400L) / 3600L
        val minutes = (totalSec % 3600L) / 60L
        return when {
            days >= 7L -> "7d"
            days > 0L -> String.format(Locale.getDefault(), "%02dd%02dh", days, hours)
            hours > 0L -> String.format(Locale.getDefault(), "%02dh%02dm", hours, minutes)
            else -> String.format(Locale.getDefault(), "%02dm", minutes.coerceAtLeast(1L))
        }
    }

    /**
     * 将满电推算可用时长格式化为紧凑字符串。
     *
     * @param ms 充满电可用物理时长毫秒数
     * @return 格式化后的紧凑时长字符串，数据无效时返回 "--"
     */
    private fun formatFullDuration(ms: Long?): String {
        if (ms == null || ms <= 0L) return "--"
        val totalSec = ms / 1000L
        val days = totalSec / 86400L
        val hours = (totalSec % 86400L) / 3600L
        val minutes = (totalSec % 3600L) / 60L
        return when {
            days > 0L -> String.format(Locale.getDefault(), "%02dd%02dh", days, hours)
            hours > 0L -> String.format(Locale.getDefault(), "%02dh%02dm", hours, minutes)
            else -> String.format(Locale.getDefault(), "%02dm", minutes)
        }
    }
}


