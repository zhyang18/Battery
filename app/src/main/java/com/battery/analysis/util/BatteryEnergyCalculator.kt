package com.battery.analysis.util

import java.util.Locale
import com.battery.analysis.manager.PowerOverviewStats
import com.battery.analysis.manager.PowerUsageManager
import com.battery.analysis.model.PowerDisplayUnit

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

        // 策略 2：硬件实时电荷计数器结合自适应物理量级归一化折算
        // 通过 BatteryUnitNormalizer 兼顾标准 uAh 与国产定制 ROM 直接上报 mAh 的实际情况，杜绝千倍缩水与低电量暴增
        if (hardwareChargeCounterUah != null && hardwareChargeCounterUah > 0) {
            val currentMah = com.battery.analysis.domain.estimator.BatteryUnitNormalizer.normalizeChargeCounterToMah(
                rawCounter = hardwareChargeCounterUah.toLong(),
                effectiveCapacityMah = if (effectiveCapacityMah > 0f) effectiveCapacityMah.toDouble() else null,
                batteryPercent = safePercent.toDouble()
            )?.toFloat()
            if (currentMah != null && currentMah > 0f) {
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
     * 1. 优先基于设备有效基准容量（电量计真实满充容量 FCC 或出厂设计容量）与标称电压物理换算（首选优先级，稳定性高，杜绝 SOC 量化抖动）；
     * 2. 硬件原生能量计数器 [hardwareEnergyNwh] 结合当前电量百分比反推满电总能量（仅在无有效基准容量且 SOC >= 20 时降级采用）；
     * 3. 硬件实时电荷计数器 [hardwareChargeCounterUah] 结合当前电量百分比与标称电压折算满电总能量（严格除以 1000f 换算为 mAh，降级采用）。
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

        // 策略 1：硬件原生能量计数器结合当前电量反推满电物理总能量 (nWh -> Wh)
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
        // 通过 BatteryUnitNormalizer 兼顾标准 uAh 与国产定制 ROM 直接上报 mAh 的实际情况，杜绝千倍缩水与低电量暴增
        if (hardwareChargeCounterUah != null && hardwareChargeCounterUah > 0 && safePercent != null && safePercent > 0) {
            val currentMah = com.battery.analysis.domain.estimator.BatteryUnitNormalizer.normalizeChargeCounterToMah(
                rawCounter = hardwareChargeCounterUah.toLong(),
                effectiveCapacityMah = if (effectiveCapacityMah > 0f) effectiveCapacityMah.toDouble() else null,
                batteryPercent = safePercent.toDouble()
            )?.toFloat()
            if (currentMah != null && currentMah > 0f) {
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
        }

        // 策略 3：基于设备有效基准容量（FCC 或出厂设计容量）结合标称电压物理折算
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

        // 息屏放电速度（%/h 与 mAh/h）
        val offHours = overview.screenOffDurationMs / 3600000.0
        val offRateStr = if (offHours > 0.005) {
            val percentRate = if (overview.screenOffPercent > 0f) {
                overview.screenOffPercent / offHours
            } else if (nominalTotalWh != null && nominalTotalWh > 0f && offWatts > 0f) {
                (offWatts / nominalTotalWh) * 100.0
            } else {
                0.0
            }
            val mahRate = if (offMah > 0f) offMah / offHours else 0.0
            if (percentRate > 0.0 && mahRate > 0.0) {
                String.format(Locale.getDefault(), "%.1f%%/h (约 %.1f mAh/h)", percentRate, mahRate)
            } else if (percentRate > 0.0) {
                String.format(Locale.getDefault(), "%.1f%%/h", percentRate)
            } else if (mahRate > 0.0) {
                String.format(Locale.getDefault(), "约 %.1f mAh/h", mahRate)
            } else {
                "--"
            }
        } else {
            "--"
        }

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

        // 亮屏放电速度（%/h 与 mAh/h）
        val onHours = overview.screenOnDurationMs / 3600000.0
        val onRateStr = if (onHours > 0.005) {
            val percentRate = if (overview.screenOnPercent > 0f) {
                overview.screenOnPercent / onHours
            } else if (nominalTotalWh != null && nominalTotalWh > 0f && onWatts > 0f) {
                (onWatts / nominalTotalWh) * 100.0
            } else {
                0.0
            }
            val mahRate = if (onMah > 0f) onMah / onHours else 0.0
            if (percentRate > 0.0 && mahRate > 0.0) {
                String.format(Locale.getDefault(), "%.1f%%/h (约 %.1f mAh/h)", percentRate, mahRate)
            } else if (percentRate > 0.0) {
                String.format(Locale.getDefault(), "%.1f%%/h", percentRate)
            } else if (mahRate > 0.0) {
                String.format(Locale.getDefault(), "约 %.1f mAh/h", mahRate)
            } else {
                "--"
            }
        } else {
            "--"
        }

        return buildString {
            append("【息屏耗电】\n")
            append("• 息屏掉电：$offPercentStr（待机 $offDurStr）\n")
            append("• 放电速度：$offRateStr\n")
            append("• 消耗能量：$offEnergyStr (折合 $offMahStr)\n")
            append("• 平均功率：$offWattsStr\n\n")
            append("【亮屏对比】\n")
            append("• 亮屏掉电：$onPercentStr（时长 $onDurStr）\n")
            append("• 亮屏放电速度：$onRateStr\n")
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

        // 唤醒放电速度（%/h 与 mAh/h）
        val awakeHours = overview.screenOffAwakeDurationMs / 3600000.0
        val awakeRateStr = if (awakeHours > 0.005) {
            val percentRate = if (overview.screenOffAwakePercent > 0f) {
                overview.screenOffAwakePercent / awakeHours
            } else if (nominalTotalWh != null && nominalTotalWh > 0f && awakeWatts > 0f) {
                (awakeWatts / nominalTotalWh) * 100.0
            } else {
                0.0
            }
            val mahRate = if (awakeMah > 0f) awakeMah / awakeHours else 0.0
            if (percentRate > 0.0 && mahRate > 0.0) {
                String.format(Locale.getDefault(), "%.1f%%/h (约 %.1f mAh/h)", percentRate, mahRate)
            } else if (percentRate > 0.0) {
                String.format(Locale.getDefault(), "%.1f%%/h", percentRate)
            } else if (mahRate > 0.0) {
                String.format(Locale.getDefault(), "约 %.1f mAh/h", mahRate)
            } else {
                "--"
            }
        } else {
            "--"
        }

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
            append("• 放电速度：$awakeRateStr\n")
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

        val sleepCountStr = if (overview.screenOffDeepSleepCount != null) {
            String.format(Locale.getDefault(), "%d 次", overview.screenOffDeepSleepCount)
        } else {
            "未获取"
        }

        val deepSleepHours = overview.screenOffDeepSleepDurationMs / 3600000.0
        val sleepRateStr = if (deepSleepHours > 0.005) {
            val percentRate = if (overview.screenOffDeepSleepPercent > 0f) {
                overview.screenOffDeepSleepPercent / deepSleepHours
            } else if (nominalTotalWh != null && nominalTotalWh > 0f && sleepWatts > 0f) {
                (sleepWatts / nominalTotalWh) * 100.0
            } else {
                0.0
            }
            val mahRate = if (sleepMah > 0f) sleepMah / deepSleepHours else 0.0
            if (percentRate > 0.0 && mahRate > 0.0) {
                String.format(Locale.getDefault(), "%.2f%%/h (约 %.1f mAh/h)", percentRate, mahRate)
            } else if (percentRate > 0.0) {
                String.format(Locale.getDefault(), "%.2f%%/h", percentRate)
            } else if (mahRate > 0.0) {
                String.format(Locale.getDefault(), "约 %.1f mAh/h", mahRate)
            } else {
                "--"
            }
        } else {
            "--"
        }

        return buildString {
            append("【深度睡眠】\n")
            append("• 深睡掉电：$sleepPercentStr（累计 $sleepDurStr）\n")
            append("• 放电速度：$sleepRateStr\n")
            append("• 消耗能量：$sleepEnergyStr (折合 $sleepMahStr)\n")
            append("• 休眠功率：$sleepWattsStr\n")
            append("• 深度睡眠率：$deepSleepRatioStr\n")
            append("• 进入休眠：$sleepCountStr\n\n")
            append("💡 说明：\n")
            append("• 灭屏后 CPU 挂起仅留硬件底噪的极低功耗模式；\n")
            if (sleepWatts > 0.20f) {
                append("• 当前深睡功率偏高（> 0.20W），通常由双卡5G网络寻呼、弱信号基带发射、外设硬件漏电或后台静默拉起引起；\n")
                if (deepSleepRatio != null && deepSleepRatio >= 80.0) {
                    append("• 深睡占息屏总时长 ${String.format(Locale.getDefault(), "%.1f%%", deepSleepRatio)}，整机绝大部分待机电量在此阶段被硬件底噪消耗，符合物理能量守恒；\n")
                }
            }
            append("• 睡眠率越接近 100%，表明待机休眠越充分、耗电越平稳。")
        }
    }

    /**
     * 格式化放电速度卡片（亮屏、息屏、全局）点击弹框展示的详细说明文本。
     * 全面呈现当前会话放电速度（拔电以来）、累积7天物理使用时间的统计放电速度与有效时长、满电可用理论时长以及详细物理原理解释，
     * 在全局卡片中完整展示模式1（全局加权）与模式2（场景占空比融合）两套续航推算结果，
     * 在剩余续航上方紧随展示预测基准放电速度，
     * 并在亮屏卡片弹框中特别展示预测真实容量（单位：多少Wh（多少mAh））。
     *
     * @param rowType 行分类标识（[ROW_SCREEN_ON] 为亮屏，[ROW_SCREEN_OFF] 为息屏，[ROW_GLOBAL] 为全局）
     * @param currentRatePercentPerHour 当前周期的预测基准放电速率（单位：%/h，缺失时为 null）
     * @param sevenDaysRatePercentPerHour 累积7天实际使用时间的统计放电速率（单位：%/h，缺失时为 null）
     * @param sevenDaysDurationMs 累积7天实际使用时间的有效统计时长（单位：毫秒，缺失时为 null）
     * @param fullDurationMs 充满电理论可用时长（模式1全局加权，单位：毫秒，缺失时为 null）
     * @param predictedCapacityWh 预测电池真实容量能量值（单位：Wh，若不可用传入 null）
     * @param predictedCapacityMah 预测电池真实容量电荷值（单位：mAh，若不可用传入 null）
     * @param sceneScreenOnRatio 统计窗口内真实日常亮屏时间占比（取值 0.0f ~ 1.0f，无数据时为 null）
     * @param sceneFullDurationMs 模式2基于日常场景占空比融合推算的充满电理论综合续航（单位：毫秒，缺失时为 null）
     * @param remainingDurationMs 预测当前剩余续航物理时长（精确到秒，单位：毫秒，缺失时为 null）
     * @param v2Result 阶段一升级的满电续航算法（v2.0）综合运算结果实体 [com.battery.analysis.domain.estimator.model.FullChargeRuntimeResult]（可选）
     * @param remainingEstimate 阶段二升级的剩余续航算法（v2.0）预测结果实体 [com.battery.analysis.domain.estimator.model.RuntimeEstimate]（可选，包含置信度与工况预期范围）
     * @param displayUnit 当前用户选定的能量/电量显示单位（[PowerDisplayUnit.WH] 或 [PowerDisplayUnit.MAH]）
     * @param nominalVoltageVolts 电池标称电压（单位：V，默认 3.85V）
     * @param sessionRatePercentPerHour 自本次拔电以来的会话放电速度（单位：%/h，缺失时为 null）
     * @param sessionDurationMs 自本次拔电以来的会话有效统计时长（单位：毫秒，缺失时为 null）
     * @return 格式化后的详细气泡说明字符串
     */
    fun formatDischargeSpeedCardDetailMessage(
        rowType: Int,
        currentRatePercentPerHour: Float?,
        sevenDaysRatePercentPerHour: Float?,
        sevenDaysDurationMs: Long?,
        fullDurationMs: Long?,
        predictedCapacityWh: Float? = null,
        predictedCapacityMah: Float? = null,
        sceneScreenOnRatio: Float? = null,
        sceneFullDurationMs: Long? = null,
        remainingDurationMs: Long? = null,
        v2Result: com.battery.analysis.domain.estimator.model.FullChargeRuntimeResult? = null,
        remainingEstimate: com.battery.analysis.domain.estimator.model.RuntimeEstimate? = null,
        displayUnit: PowerDisplayUnit = PowerDisplayUnit.WH,
        nominalVoltageVolts: Float = DEFAULT_NOMINAL_VOLTAGE_VOLTS,
        sessionRatePercentPerHour: Float? = null,
        sessionDurationMs: Long? = null
    ): String {
        val currentRateStr = if (currentRatePercentPerHour != null && currentRatePercentPerHour > 0f) {
            String.format(Locale.getDefault(), "%.1f%%/h", currentRatePercentPerHour)
        } else {
            "--"
        }

        val sessionRateStr = if (sessionRatePercentPerHour != null && sessionRatePercentPerHour > 0f) {
            String.format(Locale.getDefault(), "%.1f%%/h", sessionRatePercentPerHour)
        } else {
            "--"
        }

        val sevenDaysRateStr = if (sevenDaysRatePercentPerHour != null && sevenDaysRatePercentPerHour > 0f) {
            String.format(Locale.getDefault(), "%.1f%%/h", sevenDaysRatePercentPerHour)
        } else {
            "--"
        }

        val sessionDurStr = if (sessionDurationMs != null && sessionDurationMs > 0L) {
            val dur = formatDurationWithSeconds(sessionDurationMs)
            if (dur != "--") dur else ""
        } else {
            ""
        }
        val sevenDaysDurStr = formatStatsDuration(sevenDaysDurationMs)
        val fullDurationStr = formatDurationWithSeconds(fullDurationMs)
        val remainingDurStr = formatDurationWithSeconds(remainingDurationMs)

        return when (rowType) {
            ROW_SCREEN_ON -> {
                val capacityStr = formatPredictedCapacity(predictedCapacityWh, predictedCapacityMah, nominalVoltageVolts)
                buildString {
                    append("【亮屏续航预测】\n")
                    if (sessionDurStr.isNotBlank()) {
                        append("• 当前会话放电速度：$sessionRateStr (亮屏 $sessionDurStr)\n")
                    } else {
                        append("• 当前会话放电速度：$sessionRateStr\n")
                    }
                    if (sevenDaysDurStr.isNotBlank()) {
                        append("• 近7天放电速度：$sevenDaysRateStr (亮屏 $sevenDaysDurStr)\n")
                    } else {
                        append("• 近7天放电速度：$sevenDaysRateStr\n")
                    }
                    append("• 满电亮屏续航：$fullDurationStr\n")
                    if (v2Result != null && v2Result.screenOnFullRuntimeMs != null) {
                        val v2OnDurStr = formatDurationWithSeconds(v2Result.screenOnFullRuntimeMs)
                        val pOnStr = v2Result.predictedScreenOnPowerWatts?.let { String.format(Locale.US, "%.2fW", it) }
                        val windowStr = if (v2Result.historyWindowDays > 7.1) {
                            String.format(Locale.US, "自适应扩展至近 %.1f 天", v2Result.historyWindowDays)
                        } else {
                            "最近 7 天"
                        }
                        val effOnStr = v2Result.screenOnEffectiveMs?.let { formatFullDuration(it) } ?: formatFullDuration(v2Result.effectiveDischargeDurationMs)
                        val v2Tag = if (v2Result.modelVersion == com.battery.analysis.domain.estimator.model.RuntimeModelVersion.FULL_RUNTIME_V2_2) "升级模型(v2.2)" else "升级模型(v2.1)"
                        append("• $v2Tag：$v2OnDurStr (${v2Result.screenOnConfidenceLevel.description})\n")
                        append("  统计范围：$windowStr\n")
                        append("  有效放电：累计亮屏 $effOnStr (${v2Result.screenOnDataSufficiency.description})\n")
                        val prof = v2Result.profile
                        if (prof != null && prof.screenOnMs > 0L) {
                            val lightPct = String.format(Locale.US, "%.1f%%", prof.lightRatio * 100.0)
                            val normalPct = String.format(Locale.US, "%.1f%%", prof.normalRatio * 100.0)
                            val heavyPct = String.format(Locale.US, "%.1f%%", prof.heavyRatio * 100.0)
                            append("  亮屏工况：轻载 $lightPct · 普通 $normalPct · 重载 $heavyPct\n")
                            val pDetails = mutableListOf<String>()
                            prof.lightPowerW?.let { pDetails.add("轻载 ${String.format(Locale.US, "%.2fW", it)}") }
                            prof.normalPowerW?.let { pDetails.add("普通 ${String.format(Locale.US, "%.2fW", it)}") }
                            prof.heavyPowerW?.let { pDetails.add("重载 ${String.format(Locale.US, "%.2fW", it)}") }
                            if (pDetails.isNotEmpty()) {
                                append("  工况功耗：${pDetails.joinToString(" · ")}\n")
                            }
                        }
                        if (pOnStr != null) {
                            append("  典型功耗：$pOnStr\n")
                        }
                    }
                    append("• 预测基准放电速度：$currentRateStr\n")
                    if (remainingEstimate != null && remainingEstimate.remainingMs != null) {
                        val remStr = formatDurationWithSeconds(remainingEstimate.remainingMs)
                        val effectiveConf = getBoundedRemainingConfidence(
                            rowType = ROW_SCREEN_ON,
                            remainingEstimate = remainingEstimate,
                            accumulatedDurationMs = v2Result?.screenOnEffectiveMs ?: sevenDaysDurationMs
                        )
                        append("• 当前剩余续航：$remStr (${effectiveConf.description})\n")
                        if (remainingEstimate.minRemainingMs != null && remainingEstimate.maxRemainingMs != null) {
                            val minStr = formatFullDuration(remainingEstimate.minRemainingMs)
                            val maxStr = formatFullDuration(remainingEstimate.maxRemainingMs)
                            append("  工况预期范围：$minStr(重载) ~ $maxStr(轻载)\n")
                        }
                        if (remainingEstimate.predictedPowerWatts != null && remainingEstimate.predictedPowerWatts > 0.0) {
                            val predWatts = remainingEstimate.predictedPowerWatts
                            val pStr = String.format(Locale.US, "%.2fW", predWatts)
                            val remEnergyWh = remainingEstimate.remainingEnergyWh
                            val eSuffix = if (remEnergyWh != null && remEnergyWh > 0.0) {
                                val energyText = if (displayUnit == PowerDisplayUnit.MAH) {
                                    val safeV = if (nominalVoltageVolts > 0f) nominalVoltageVolts.toDouble() else DEFAULT_NOMINAL_VOLTAGE_VOLTS.toDouble()
                                    val mah = (remEnergyWh * 1000.0) / safeV
                                    String.format(Locale.US, "%.1fmAh", mah)
                                } else {
                                    String.format(Locale.US, "%.2fWh", remEnergyWh)
                                }
                                " (可用 $energyText)"
                            } else ""
                            val rateSuffix = if (predictedCapacityWh != null && predictedCapacityWh > 0f) {
                                val predRate = (predWatts / predictedCapacityWh) * 100.0
                                String.format(Locale.US, " · %.1f%%/h", predRate)
                            } else ""
                            append("  预测放电功耗：$pStr$eSuffix$rateSuffix\n")
                        }
                    } else {
                        append("• 当前剩余续航：$remainingDurStr\n")
                    }
                    append("• 预测真实容量：$capacityStr\n\n")
                    append("💡 说明：\n")
                    append("• 会话放电速度反映本次拔电以来的实际平均耗电表现；\n")
                    append("• 预测基准放电速度反映近期稳态工况，作为推算当前剩余续航的基准；\n")
                    append("• 满电续航按7天实际亮屏加权推算；\n")
                    append("• 升级模型自适应历史窗口与衰减权重；\n")
                    append("• 剩余续航基于物理能量与自适应多时间尺度推算。")
                }
            }
            ROW_SCREEN_OFF -> {
                buildString {
                    append("【息屏续航预测】\n")
                    if (sessionDurStr.isNotBlank()) {
                        append("• 当前会话放电速度：$sessionRateStr (息屏 $sessionDurStr)\n")
                    } else {
                        append("• 当前会话放电速度：$sessionRateStr\n")
                    }
                    if (sevenDaysDurStr.isNotBlank()) {
                        append("• 近7天放电速度：$sevenDaysRateStr (息屏 $sevenDaysDurStr)\n")
                    } else {
                        append("• 近7天放电速度：$sevenDaysRateStr\n")
                    }
                    append("• 满电待机时长：$fullDurationStr\n")
                    if (v2Result != null && v2Result.screenOffFullRuntimeMs != null) {
                        val v2OffDurStr = formatDurationWithSeconds(v2Result.screenOffFullRuntimeMs)
                        val pOffStr = v2Result.predictedScreenOffPowerWatts?.let { String.format(Locale.US, "%.2fW", it) }
                        val windowStr = if (v2Result.historyWindowDays > 7.1) {
                            String.format(Locale.US, "自适应扩展至近 %.1f 天", v2Result.historyWindowDays)
                        } else {
                            "最近 7 天"
                        }
                        val effOffStr = v2Result.screenOffEffectiveMs?.let { formatFullDuration(it) } ?: formatFullDuration(v2Result.effectiveDischargeDurationMs)
                        val v2Tag = if (v2Result.modelVersion == com.battery.analysis.domain.estimator.model.RuntimeModelVersion.FULL_RUNTIME_V2_2) "升级模型(v2.2)" else "升级模型(v2.1)"
                        append("• $v2Tag：$v2OffDurStr (${v2Result.screenOffConfidenceLevel.description})\n")
                        append("  统计范围：$windowStr\n")
                        append("  有效放电：累计息屏 $effOffStr (${v2Result.screenOffDataSufficiency.description})\n")
                        val prof = v2Result.profile
                        if (prof != null && prof.screenOffMs > 0L) {
                            val lowPct = String.format(Locale.US, "%.1f%%", prof.offLowRatio * 100.0)
                            val highPct = String.format(Locale.US, "%.1f%%", prof.offHighRatio * 100.0)
                            append("  待机工况：休眠 $lowPct · 唤醒 $highPct\n")
                            val pDetails = mutableListOf<String>()
                            prof.offLowPowerW?.let { pDetails.add("休眠 ${String.format(Locale.US, "%.2fW", it)}") }
                            prof.offHighPowerW?.let { pDetails.add("唤醒 ${String.format(Locale.US, "%.2fW", it)}") }
                            if (pDetails.isNotEmpty()) {
                                append("  工况功耗：${pDetails.joinToString(" · ")}\n")
                            }
                        }
                        if (pOffStr != null) {
                            append("  典型待机功耗：$pOffStr\n")
                        }
                    }
                    append("• 预测基准放电速度：$currentRateStr\n")
                    if (remainingEstimate != null && remainingEstimate.remainingMs != null) {
                        val remStr = formatDurationWithSeconds(remainingEstimate.remainingMs)
                        val effectiveConf = getBoundedRemainingConfidence(
                            rowType = ROW_SCREEN_OFF,
                            remainingEstimate = remainingEstimate,
                            accumulatedDurationMs = v2Result?.screenOffEffectiveMs ?: sevenDaysDurationMs
                        )
                        append("• 当前待机续航：$remStr (${effectiveConf.description})\n")
                        if (remainingEstimate.minRemainingMs != null && remainingEstimate.maxRemainingMs != null) {
                            val minStr = formatFullDuration(remainingEstimate.minRemainingMs)
                            val maxStr = formatFullDuration(remainingEstimate.maxRemainingMs)
                            append("  工况预期范围：$minStr(频繁唤醒) ~ $maxStr(深度休眠)\n")
                        }
                        if (remainingEstimate.predictedPowerWatts != null && remainingEstimate.predictedPowerWatts > 0.0) {
                            val predWatts = remainingEstimate.predictedPowerWatts
                            val pStr = String.format(Locale.US, "%.2fW", predWatts)
                            val remEnergyWh = remainingEstimate.remainingEnergyWh
                            val eSuffix = if (remEnergyWh != null && remEnergyWh > 0.0) {
                                val energyText = if (displayUnit == PowerDisplayUnit.MAH) {
                                    val safeV = if (nominalVoltageVolts > 0f) nominalVoltageVolts.toDouble() else DEFAULT_NOMINAL_VOLTAGE_VOLTS.toDouble()
                                    val mah = (remEnergyWh * 1000.0) / safeV
                                    String.format(Locale.US, "%.1fmAh", mah)
                                } else {
                                    String.format(Locale.US, "%.2fWh", remEnergyWh)
                                }
                                " (可用 $energyText)"
                            } else ""
                            val rateSuffix = if (predictedCapacityWh != null && predictedCapacityWh > 0f) {
                                val predRate = (predWatts / predictedCapacityWh) * 100.0
                                String.format(Locale.US, " · %.1f%%/h", predRate)
                            } else ""
                            append("  预测待机功耗：$pStr$eSuffix$rateSuffix\n")
                        }
                    } else {
                        append("• 当前剩余续航：$remainingDurStr\n")
                    }
                    append("\n💡 说明：\n")
                    append("• 会话放电速度反映本次拔电以来的息屏待机耗电表现；\n")
                    append("• 预测基准放电速度反映近期稳态工况，作为推算待机续航的基准；\n")
                    append("• 待机时长按7天实际待机时间加权推算；\n")
                    append("• 升级模型解构深睡底噪与后台唤醒；\n")
                    append("• 待机续航基于物理能量与多时间尺度稳态推算。")
                }
            }
            ROW_GLOBAL -> {
                val sceneFullDurationStr = formatDurationWithSeconds(sceneFullDurationMs)
                buildString {
                    append("【全局续航预测】\n")
                    if (sessionDurStr.isNotBlank()) {
                        append("• 当前会话放电速度：$sessionRateStr (放电 $sessionDurStr)\n")
                    } else {
                        append("• 当前会话放电速度：$sessionRateStr\n")
                    }
                    if (sevenDaysDurStr.isNotBlank()) {
                        append("• 近7天放电速度：$sevenDaysRateStr (放电 $sevenDaysDurStr)\n")
                    } else {
                        append("• 近7天放电速度：$sevenDaysRateStr\n")
                    }
                    append("• 预测基准放电速度：$currentRateStr\n")
                    if (remainingEstimate != null && remainingEstimate.remainingMs != null) {
                        val remStr = formatDurationWithSeconds(remainingEstimate.remainingMs)
                        val effectiveConf = getBoundedRemainingConfidence(
                            rowType = ROW_GLOBAL,
                            remainingEstimate = remainingEstimate,
                            accumulatedDurationMs = v2Result?.effectiveDischargeDurationMs ?: sevenDaysDurationMs
                        )
                        append("• 当前剩余续航：$remStr (${effectiveConf.description})\n")
                        if (remainingEstimate.minRemainingMs != null && remainingEstimate.maxRemainingMs != null) {
                            val minStr = formatFullDuration(remainingEstimate.minRemainingMs)
                            val maxStr = formatFullDuration(remainingEstimate.maxRemainingMs)
                            append("  工况预期范围：$minStr(偏重载) ~ $maxStr(偏轻载)\n")
                        }
                        if (remainingEstimate.predictedPowerWatts != null && remainingEstimate.predictedPowerWatts > 0.0) {
                            val predWatts = remainingEstimate.predictedPowerWatts
                            val pStr = String.format(Locale.US, "%.2fW", predWatts)
                            val remEnergyWh = remainingEstimate.remainingEnergyWh
                            val eSuffix = if (remEnergyWh != null && remEnergyWh > 0.0) {
                                val energyText = if (displayUnit == PowerDisplayUnit.MAH) {
                                    val safeV = if (nominalVoltageVolts > 0f) nominalVoltageVolts.toDouble() else DEFAULT_NOMINAL_VOLTAGE_VOLTS.toDouble()
                                    val mah = (remEnergyWh * 1000.0) / safeV
                                    String.format(Locale.US, "%.1fmAh", mah)
                                } else {
                                    String.format(Locale.US, "%.2fWh", remEnergyWh)
                                }
                                " (可用 $energyText)"
                            } else ""
                            val rateSuffix = if (predictedCapacityWh != null && predictedCapacityWh > 0f) {
                                val predRate = (predWatts / predictedCapacityWh) * 100.0
                                String.format(Locale.US, " · %.1f%%/h", predRate)
                            } else ""
                            append("  预测综合功耗：$pStr$eSuffix$rateSuffix\n")
                        }
                    } else {
                        append("• 当前剩余续航：$remainingDurStr\n")
                    }
                    append("\n📊 满电综合续航推算：\n")
                    append("• 模式1（全局加权）：$fullDurationStr\n")
                    append("  基于7天全局真实功耗线性推算\n")
                    if (sceneFullDurationMs != null && sceneFullDurationMs > 0L && sceneScreenOnRatio != null) {
                        val ratioOnStr = String.format(Locale.getDefault(), "%.1f%%", sceneScreenOnRatio * 100f)
                        val ratioOffStr = String.format(Locale.getDefault(), "%.1f%%", (1.0f - sceneScreenOnRatio) * 100f)
                        append("• 模式2（场景融合）：$sceneFullDurationStr\n")
                        append("  按日常习惯 $ratioOnStr 亮屏 + $ratioOffStr 息屏加权\n")
                    } else {
                        append("• 模式2（场景融合）：$sceneFullDurationStr\n")
                    }
                    if (v2Result != null && v2Result.fullRuntimeMs != null) {
                        val v2DurStr = formatDurationWithSeconds(v2Result.fullRuntimeMs)
                        val modelDesc = if (v2Result.usedRegimeModel) "工况条件化典型模型" else "全局物理基准降级"
                        val windowStr = if (v2Result.historyWindowDays > 7.1) {
                            String.format(Locale.US, "自适应扩展至近 %.1f 天", v2Result.historyWindowDays)
                        } else {
                            "最近 7 天"
                        }
                        val effTotalStr = formatFullDuration(v2Result.effectiveDischargeDurationMs)
                        val v2Tag = if (v2Result.modelVersion == com.battery.analysis.domain.estimator.model.RuntimeModelVersion.FULL_RUNTIME_V2_2) "升级模型(v2.2)" else "升级模型(v2.1)"
                        append("• $v2Tag：$v2DurStr (${v2Result.confidenceLevel.description})\n")
                        append("  统计范围：$windowStr\n")
                        append("  有效放电：实际参与累计 $effTotalStr (${v2Result.dataSufficiency.description})\n")
                        val prof = v2Result.profile
                        if (prof != null) {
                            val onPct = String.format(Locale.US, "%.1f%%", prof.screenOnRatio * 100.0)
                            val offPct = String.format(Locale.US, "%.1f%%", prof.screenOffRatio * 100.0)
                            append("  两态分布：亮屏 $onPct · 息屏 $offPct\n")
                        }
                        append("  工况建模：基于 $modelDesc 推算\n")
                    }
                    append("\n💡 说明：\n")
                    append("• 会话放电速度反映本次拔电以来的全局综合耗电表现；\n")
                    append("• 预测基准放电速度反映近期稳态工况，作为推算综合剩余续航的基准；\n")
                    append("• 模式1忠实反映历史全周期能量总积分；\n")
                    append("• 模式2解构亮屏与待机两态比例；\n")
                    append("• 升级模型自适应历史窗口与工况建模；\n")
                    append("• 剩余续航基于物理能量与多时间尺度综合推算。")
                }
            }
            else -> ""
        }
    }

    /**
     * 根据累积使用时长与行分类维度获取受到物理累积时长约束的有效剩余续航置信度等级。
     * 防止短会话在历史大样本积累不足时虚高显示高可信度。
     *
     * @param rowType 行分类标识（亮屏 [ROW_SCREEN_ON]、息屏 [ROW_SCREEN_OFF]、全局 [ROW_GLOBAL]）
     * @param remainingEstimate 当前剩余续航估计结果实体 [com.battery.analysis.domain.estimator.model.RuntimeEstimate]，无数据时为 null
     * @param accumulatedDurationMs 对应维度的累积物理时长（毫秒，无数据时为 null）
     * @return 最终约束后的置信度等级枚举 [com.battery.analysis.domain.estimator.model.RuntimeConfidenceLevel]
     */
    fun getBoundedRemainingConfidence(
        rowType: Int,
        remainingEstimate: com.battery.analysis.domain.estimator.model.RuntimeEstimate?,
        accumulatedDurationMs: Long?
    ): com.battery.analysis.domain.estimator.model.RuntimeConfidenceLevel {
        val baseConf = remainingEstimate?.confidenceLevel ?: com.battery.analysis.domain.estimator.model.RuntimeConfidenceLevel.UNAVAILABLE
        if (accumulatedDurationMs == null || accumulatedDurationMs <= 0L) {
            return baseConf
        }
        val durationCeiling = com.battery.analysis.domain.estimator.model.RuntimeConfidenceLevel.evaluateByDuration(rowType, accumulatedDurationMs)
        return when (durationCeiling) {
            com.battery.analysis.domain.estimator.model.RuntimeConfidenceLevel.LOW -> {
                if (baseConf == com.battery.analysis.domain.estimator.model.RuntimeConfidenceLevel.HIGH || baseConf == com.battery.analysis.domain.estimator.model.RuntimeConfidenceLevel.MEDIUM) {
                    com.battery.analysis.domain.estimator.model.RuntimeConfidenceLevel.LOW
                } else baseConf
            }
            com.battery.analysis.domain.estimator.model.RuntimeConfidenceLevel.MEDIUM -> {
                if (baseConf == com.battery.analysis.domain.estimator.model.RuntimeConfidenceLevel.HIGH) {
                    com.battery.analysis.domain.estimator.model.RuntimeConfidenceLevel.MEDIUM
                } else baseConf
            }
            else -> baseConf
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
     * 将满电推算可用时长格式化为友好紧凑字符串，精确到分钟（如 "17h07m"、"01d07h15m"）。
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
            days > 0L -> String.format(Locale.getDefault(), "%02dd%02dh%02dm", days, hours, minutes)
            hours > 0L -> String.format(Locale.getDefault(), "%02dh%02dm", hours, minutes)
            else -> String.format(Locale.getDefault(), "%02dm", minutes)
        }
    }

    /**
     * 将剩余续航物理时长格式化为高精度字符串，精确到秒（如 "17h 46m 32s"、"01d 04h 28m 10s"、"45m 12s"、"30s"）。
     * 忠实反映时间物理值，若时长为 null 或小于等于 0 毫秒，返回 "--"。
     *
     * @param ms 剩余续航物理时长（单位：毫秒，若无数据传入 null）
     * @return 格式化后的精确到秒时长字符串，无数据时返回 "--"
     */
    fun formatDurationWithSeconds(ms: Long?): String {
        if (ms == null || ms <= 0L) return "--"
        val totalSec = ms / 1000L
        val days = totalSec / 86400L
        val hours = (totalSec % 86400L) / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val seconds = totalSec % 60L
        return when {
            days > 0L -> String.format(Locale.getDefault(), "%02dd%02dh%02dm%02ds", days, hours, minutes, seconds)
            hours > 0L -> String.format(Locale.getDefault(), "%02dh%02dm%02ds", hours, minutes, seconds)
            minutes > 0L -> String.format(Locale.getDefault(), "%02dm%02ds", minutes, seconds)
            else -> String.format(Locale.getDefault(), "%02ds", seconds)
        }
    }

    /**
     * 将卡片剩余续航可用时长格式化为紧凑友好字符串，精确到分钟（如 "17h46m"、"03d09h"、"25m"）。
     *
     * @param ms 剩余可用物理时长毫秒数
     * @return 格式化后的紧凑时长字符串，数据无效或缺失时返回 "--"
     */
    fun formatCardRemainingDuration(ms: Long?): String {
        if (ms == null || ms <= 0L) return "--"
        val totalSec = ms / 1000L
        val days = totalSec / 86400L
        val hours = (totalSec % 86400L) / 3600L
        val minutes = (totalSec % 3600L) / 60L
        return when {
            days > 0L -> String.format(Locale.getDefault(), "%02dd%02dh", days, hours)
            hours > 0L -> String.format(Locale.getDefault(), "%02dh%02dm", hours, minutes)
            else -> String.format(Locale.getDefault(), "%02dm", minutes.coerceAtLeast(1L))
        }
    }

    /** 自适应多时间尺度剩余续航估计引擎单例委托 */
    val runtimeEstimator by lazy { com.battery.analysis.domain.estimator.RuntimeEstimator() }

    /**
     * 运用自适应多时间尺度剩余续航估计模型 (v2.0) 一站式计算亮屏、息屏与全局三维剩余续航。
     *
     * @param overview 当前放电会话统计数据实体
     * @param sevenDaysStats 过去 7 天核心统计指标实体
     * @param batteryLevelPercent 当前电池电量百分比
     * @param remainingEnergyWh 当前电池剩余能量（单位：Wh）
     * @param totalCapacityWh 设备满充总能量（单位：Wh）
     * @param tempCelsius 当前电池温度（单位：℃）
     * @param currentTrendPoints 当前放电周期的细粒度趋势采样点集合（可选，用于激活 Fast 尺度实时跟踪与工况突变敏捷响应）
     * @return 包含三维度剩余续航预测结果的数据聚合实体 [com.battery.analysis.domain.estimator.TripleRuntimeResult]
     */
    fun estimateTripleRuntimes(
        overview: com.battery.analysis.manager.PowerOverviewStats?,
        sevenDaysStats: com.battery.analysis.model.SevenDaysDischargeStats?,
        batteryLevelPercent: Float?,
        remainingEnergyWh: Float? = null,
        totalCapacityWh: Float? = null,
        tempCelsius: Float? = null,
        currentTrendPoints: List<com.battery.analysis.model.PowerDischargePoint>? = null
    ): com.battery.analysis.domain.estimator.TripleRuntimeResult {
        return runtimeEstimator.estimateTripleRuntimes(
            overview = overview,
            sevenDaysStats = sevenDaysStats,
            batteryLevelPercent = batteryLevelPercent,
            remainingEnergyWh = remainingEnergyWh,
            totalCapacityWh = totalCapacityWh,
            tempCelsius = tempCelsius,
            currentTrendPoints = currentTrendPoints
        )
    }

    /**
     * 基于自适应置信度动态加权融合模型计算理论剩余续航物理毫秒数。
     * 结合当前放电会话的实测放电速度与近 7 天累计历史大样本基准放电速度，
     * 根据当前放电周期的实际统计时长动态计算置信度权重，平滑过滤瞬态噪声与启动尖峰，
     * 并根据剩余电量（或剩余能量）严谨推算剩余续航时长。
     * 严格遵循物理守恒与零虚构原则，数据不足时忠实返回 null。
     *
     * @param currentRatePercentPerHour 当前周期放电速度（单位：%/h，缺失时为 null）
     * @param sevenDaysRatePercentPerHour 过去7天累计使用时间加权放电速度（单位：%/h，缺失时为 null）
     * @param currentDurationMs 当前周期已持续的有效统计时长（单位：毫秒）
     * @param remainingBatteryPercent 当前电池剩余电量百分比（取值 0.0f ~ 100.0f，缺失时为 null）
     * @param remainingEnergyWh 当前电池剩余能量（单位：Wh，可选）
     * @param totalCapacityWh 电池满电总能量容量（单位：Wh，可选）
     * @return 预测的理论剩余续航物理毫秒数，若无法计算则返回 null
     */
    fun calculateEstimatedRemainingDurationMs(
        currentRatePercentPerHour: Float?,
        sevenDaysRatePercentPerHour: Float?,
        currentDurationMs: Long,
        remainingBatteryPercent: Float?,
        remainingEnergyWh: Float? = null,
        totalCapacityWh: Float? = null
    ): Long? {
        val effectivePercent = if (remainingBatteryPercent != null && remainingBatteryPercent > 0f) {
            remainingBatteryPercent
        } else if (remainingEnergyWh != null && remainingEnergyWh > 0f && totalCapacityWh != null && totalCapacityWh > 0f) {
            (remainingEnergyWh / totalCapacityWh) * 100f
        } else {
            null
        } ?: return null

        val curRate = currentRatePercentPerHour?.takeIf { it.isFinite() && it > 0f }
        val sevenDaysRate = sevenDaysRatePercentPerHour?.takeIf { it.isFinite() && it > 0f }

        if (curRate == null && sevenDaysRate == null) {
            return null
        }

        val effectiveRate = if (curRate != null && sevenDaysRate != null) {
            // 当前统计时长置信度平滑加权函数：
            // < 5分钟: 置信度为 0.15 (抑制刚拔电/刚亮屏瞬态毛刺)
            // 5~30分钟: 0.15 至 0.70 线性平滑过渡
            // >= 30分钟: 置信度饱和在 0.70 (70% 紧跟当前真实工况，30% 保留长期习惯基线)
            val weightCurrent = when {
                currentDurationMs < 300_000L -> 0.15f
                currentDurationMs >= 1_800_000L -> 0.70f
                else -> {
                    val progress = (currentDurationMs - 300_000L).toFloat() / 1_500_000f
                    0.15f + 0.55f * progress
                }
            }
            (curRate * weightCurrent) + (sevenDaysRate * (1.0f - weightCurrent))
        } else {
            curRate ?: sevenDaysRate!!
        }

        if (effectiveRate <= 0f) {
            return null
        }

        val remainingHours = effectivePercent.toDouble() / effectiveRate.toDouble()
        val remainingMs = Math.round(remainingHours * 3600.0 * 1000.0)
        return if (remainingMs > 0L) remainingMs else null
    }

    /**
     * 基于日常作息场景占空比融合推算全局综合剩余续航物理毫秒数。
     * 结合亮屏与息屏融合放电速度，按照真实统计的日常亮屏占空比动态加权得出综合放电速率，
     * 进而推算当前剩余电量下的综合可用续航时间。
     *
     * @param screenOnRate 融合后的亮屏放电速度（单位：%/h，缺失时为 null）
     * @param screenOffRate 融合后的息屏放电速度（单位：%/h，缺失时为 null）
     * @param sceneScreenOnRatio 日常亮屏时间占总时间比例（取值 0.0f ~ 1.0f，缺失时为 null）
     * @param remainingBatteryPercent 当前电池剩余电量百分比（取值 0.0f ~ 100.0f，缺失时为 null）
     * @param fallbackGlobalRemainingMs 兜底的全局直接融合剩余续航物理毫秒数（缺失时为 null）
     * @return 综合场景理论剩余续航毫秒数，若无法计算则返回 fallbackGlobalRemainingMs
     */
    fun calculateCompositeRemainingDurationMs(
        screenOnRate: Float?,
        screenOffRate: Float?,
        sceneScreenOnRatio: Float?,
        remainingBatteryPercent: Float?,
        fallbackGlobalRemainingMs: Long?
    ): Long? {
        if (screenOnRate != null && screenOnRate.isFinite() && screenOnRate > 0f &&
            screenOffRate != null && screenOffRate.isFinite() && screenOffRate > 0f &&
            sceneScreenOnRatio != null && sceneScreenOnRatio in 0.0f..1.0f &&
            remainingBatteryPercent != null && remainingBatteryPercent > 0f
        ) {
            val compositeRate = (sceneScreenOnRatio * screenOnRate) + ((1.0f - sceneScreenOnRatio) * screenOffRate)
            if (compositeRate.isFinite() && compositeRate > 0f) {
                val remainingHours = remainingBatteryPercent.toDouble() / compositeRate.toDouble()
                val remainingMs = Math.round(remainingHours * 3600.0 * 1000.0)
                if (remainingMs > 0L) return remainingMs
            }
        }
        return fallbackGlobalRemainingMs
    }

    /**
     * 根据指定的显示单位统一格式化能量/电量数值。
     * 若单位为 Wh，格式化为 "0.250Wh"（数值介于 0.00001Wh 与 0.0005Wh 之间时显示 "<0.001Wh"）；
     * 若单位为 mAh，基于电池标称工作电压严格折算并格式化为 "64.9mAh"（数值介于 0.001mAh 与 0.05mAh 之间时显示 "<0.1mAh"）；
     * 若能量小于等于 0 或为无效浮点数，如实返回 "--"（忠实反映系统真实数据，严禁伪造假数据）。
     *
     * @param energyWh 实际消耗的物理能量数值（单位：Wh）
     * @param unit 目标呈现的显示单位 [PowerDisplayUnit]
     * @param nominalVoltageVolts 折算采用的标称电压（单位：V，默认采用 3.85V 行业标准放电平台中位电压）
     * @return 格式化后的带单位文本（如 "0.250Wh"、"64.9mAh" 或 "--"）
     */
    fun formatDisplayEnergy(
        energyWh: Float,
        unit: PowerDisplayUnit,
        nominalVoltageVolts: Float = DEFAULT_NOMINAL_VOLTAGE_VOLTS
    ): String {
        if (energyWh <= 0f || energyWh.isNaN()) return "--"
        val safeNominalVoltage = if (nominalVoltageVolts > 0f) nominalVoltageVolts else DEFAULT_NOMINAL_VOLTAGE_VOLTS
        return when (unit) {
            PowerDisplayUnit.WH -> {
                if (energyWh >= 0.0005f) {
                    String.format(Locale.getDefault(), "%.3fWh", energyWh)
                } else if (energyWh > 0.00001f) {
                    "<0.001Wh"
                } else {
                    "--"
                }
            }
            PowerDisplayUnit.MAH -> {
                val mah = (energyWh * 1000f) / safeNominalVoltage
                if (mah >= 0.05f) {
                    String.format(Locale.getDefault(), "%.1fmAh", mah)
                } else if (mah > 0.001f) {
                    "<0.1mAh"
                } else {
                    "--"
                }
            }
        }
    }

    /**
     * 电量显示单位切换弹窗的结构化说明内容模型。
     *
     * @property title 弹窗主标题
     * @property subtitle 弹窗副标题
     * @property formula 核心换算公式
     * @property example 换算参考数值示例
     * @property definition 物理定义说明
     * @property science 科学守恒说明
     */
    data class UnitSwitchDialogContent(
        val title: String,
        val subtitle: String,
        val formula: String,
        val example: String,
        val definition: String,
        val science: String
    )

    /**
     * 获取单位切换弹窗的结构化展示内容。
     *
     * @param targetUnit 目标显示单位 [PowerDisplayUnit]
     * @param nominalVoltageVolts 折算基准标称电压（单位：V，默认 3.85V）
     * @return 结构化的弹窗展示数据模型 [UnitSwitchDialogContent]
     */
    fun getUnitSwitchDialogContent(
        targetUnit: PowerDisplayUnit,
        nominalVoltageVolts: Float = DEFAULT_NOMINAL_VOLTAGE_VOLTS
    ): UnitSwitchDialogContent {
        val safeV = if (nominalVoltageVolts > 0f) nominalVoltageVolts else DEFAULT_NOMINAL_VOLTAGE_VOLTS
        return when (targetUnit) {
            PowerDisplayUnit.MAH -> {
                val ratio = 1000f / safeV
                val ratioStr = String.format(Locale.getDefault(), "%.1f", ratio)
                UnitSwitchDialogContent(
                    title = "已切换为【毫安时 (mAh)】显示",
                    subtitle = "展现层单位切换 · 严谨物理守恒",
                    formula = "电量 (mAh) = 能量 (Wh) × 1000 ÷ ${safeV}V",
                    example = "换算参考：1.000 Wh ≈ $ratioStr mAh",
                    definition = "电荷量物理单位（Q = I × t），符合手机电池出厂标称容量（如 5000mAh）的日常直觉感知。",
                    science = "杜绝使用随负载跳变的瞬时端电压逆算，保证所有应用耗电总和与整机掉电在数学与物理上完全守恒。"
                )
            }
            PowerDisplayUnit.WH -> {
                UnitSwitchDialogContent(
                    title = "已切换为【瓦时 (Wh)】显示",
                    subtitle = "展现层单位切换 · 严谨物理守恒",
                    formula = "能量 (Wh) = 电量 (mAh) × ${safeV}V ÷ 1000",
                    example = "换算参考：1000 mAh ≈ ${String.format(Locale.getDefault(), "%.3f", safeV)} Wh",
                    definition = "能量物理单位（E = U × I × t），直接反映电池电芯化学能做功与能量释放，符合工业权威计量标准。",
                    science = "消除不同设备工作电压平台及串联双电芯架构对容量判读的干扰，是专业级硬件能耗分析的基准单位。"
                )
            }
        }
    }

    /**
     * 将能量数值（单位：Wh）基于电池标称工作平台电压换算为等效电量（单位：mAh）。
     * 换算公式遵循物理定律：电量 (mAh) = 能量 (Wh) × 1000 ÷ 标称电压 (V)。
     *
     * @param energyWh 实际能量数值（单位：Wh）
     * @param nominalVoltageVolts 电池标称工作电压（单位：V，默认采用 3.85V 行业标准中位电压）
     * @return 换算得出的等效电量数值（单位：mAh）
     */
    fun convertWhToMah(
        energyWh: Float,
        nominalVoltageVolts: Float = DEFAULT_NOMINAL_VOLTAGE_VOLTS
    ): Float {
        val safeNominalVoltage = if (nominalVoltageVolts > 0f) nominalVoltageVolts else DEFAULT_NOMINAL_VOLTAGE_VOLTS
        return (energyWh * 1000f) / safeNominalVoltage
    }

    /**
     * 将电量数值（单位：mAh）基于电池标称工作平台电压换算为等效能量（单位：Wh）。
     * 换算公式遵循物理定律：能量 (Wh) = 电量 (mAh) × 标称电压 (V) ÷ 1000。
     *
     * @param capacityMah 实际电量数值（单位：mAh）
     * @param nominalVoltageVolts 电池标称工作电压（单位：V，默认采用 3.85V 行业标准中位电压）
     * @return 换算得出的等效能量数值（单位：Wh）
     */
    fun convertMahToWh(
        capacityMah: Float,
        nominalVoltageVolts: Float = DEFAULT_NOMINAL_VOLTAGE_VOLTS
    ): Float {
        val safeNominalVoltage = if (nominalVoltageVolts > 0f) nominalVoltageVolts else DEFAULT_NOMINAL_VOLTAGE_VOLTS
        return (capacityMah * safeNominalVoltage) / 1000f
    }

    /**
     * 格式化充入或增量能量/电量（带正号前缀，用于充电主视图，如 "+0.5Wh" 或 "+130mAh"）。
     *
     * @param energyWh 充入能量数值（单位：Wh）
     * @param unit 目标显示单位 [PowerDisplayUnit]
     * @param nominalVoltageVolts 折算采用的标称电压（单位：V，默认 3.85V）
     * @return 格式化后的带正号文本
     */
    fun formatChargingGainEnergy(
        energyWh: Float,
        unit: PowerDisplayUnit = PowerDisplayUnit.WH,
        nominalVoltageVolts: Float = DEFAULT_NOMINAL_VOLTAGE_VOLTS
    ): String {
        return if (unit == PowerDisplayUnit.MAH) {
            val mah = convertWhToMah(energyWh, nominalVoltageVolts)
            String.format(Locale.getDefault(), "+%.0fmAh", mah)
        } else {
            String.format(Locale.getDefault(), "+%.1fWh", energyWh)
        }
    }

    /**
     * 格式化充电历史详情页的充入能量数值（保留两位小数或整数 mAh，如 "+6.85 Wh" 或 "+1779 mAh"）。
     *
     * @param energyWh 充入能量数值（单位：Wh）
     * @param unit 目标显示单位 [PowerDisplayUnit]
     * @param nominalVoltageVolts 折算采用的标称电压（单位：V，默认 3.85V）
     * @return 格式化后的带符号充入能量文本
     */
    fun formatChargingDetailEnergy(
        energyWh: Float,
        unit: PowerDisplayUnit = PowerDisplayUnit.WH,
        nominalVoltageVolts: Float = DEFAULT_NOMINAL_VOLTAGE_VOLTS
    ): String {
        return if (unit == PowerDisplayUnit.MAH) {
            val mah = convertWhToMah(energyWh, nominalVoltageVolts)
            String.format(Locale.getDefault(), "+%.0f mAh", mah)
        } else {
            String.format(Locale.getDefault(), "+%.2f Wh", energyWh)
        }
    }

    /**
     * 格式化历史详情页顶部大字总消耗能量/电量（如 "14.2 Wh" 或 "3688 mAh"）。
     * 使用不换行空格 (\u00A0) 连接数值与单位，防止排版引擎在窄屏下将其换行折断。
     *
     * @param energyWh 消耗能量数值（单位：Wh）
     * @param unit 目标显示单位 [PowerDisplayUnit]
     * @param nominalVoltageVolts 折算采用的标称电压（单位：V，默认 3.85V）
     * @return 格式化后的总能耗大字文本，保证数值与单位同一行呈现
     */
    fun formatHistoryDetailOverviewEnergy(
        energyWh: Float,
        unit: PowerDisplayUnit = PowerDisplayUnit.WH,
        nominalVoltageVolts: Float = DEFAULT_NOMINAL_VOLTAGE_VOLTS
    ): String {
        return if (unit == PowerDisplayUnit.MAH) {
            val mah = convertWhToMah(energyWh, nominalVoltageVolts)
            String.format(Locale.getDefault(), "%.0f\u00A0mAh", mah)
        } else {
            String.format(Locale.getDefault(), "%.1f\u00A0Wh", energyWh)
        }
    }

    /**
     * 构建切换电量显示单位时的专业科学原理说明文案。
     * 阐明当前单位的物理定义、换算基准与行业科学依据，帮助用户理解两种模式的技术差异。
     *
     * @param targetUnit 目标切换到的显示单位 [PowerDisplayUnit]
     * @param nominalVoltageVolts 折算基准标称电压（单位：V，默认 3.85V）
     * @return 包含标题与正文详情的弹窗说明文案对 [Pair]（First 为弹窗标题，Second 为正文描述）
     */
    fun getUnitSwitchExplanation(
        targetUnit: PowerDisplayUnit,
        nominalVoltageVolts: Float = DEFAULT_NOMINAL_VOLTAGE_VOLTS
    ): Pair<String, String> {
        val safeV = if (nominalVoltageVolts > 0f) nominalVoltageVolts else DEFAULT_NOMINAL_VOLTAGE_VOLTS
        return when (targetUnit) {
            PowerDisplayUnit.MAH -> {
                val title = "已切换为【毫安时 (mAh)】显示"
                val message = buildString {
                    append("已将电量展示切换为【毫安时 (mAh)】模式。\n\n")
                    append("• 物理定义：电荷量单位（Q = I × t），符合绝大多数手机电池出厂标称容量（如 5000mAh）的日常直觉感知；\n")
                    append("• 换算标准：基于国际标准锂电标称工作平台电压（${safeV}V）严格守恒换算：\n")
                    append("  电量(mAh) = 能量(Wh) × 1000 ÷ ${safeV}V；\n")
                    append("• 科学依据：杜绝使用跳变的瞬时端电压，保证应用耗电总和与整机掉电在数学与物理上完全守恒。")
                }
                Pair(title, message)
            }
            PowerDisplayUnit.WH -> {
                val title = "已切换为【瓦时 (Wh)】显示"
                val message = buildString {
                    append("已将电量展示切换为【瓦时 (Wh)】模式。\n\n")
                    append("• 物理定义：真实做功与发热能量单位（E = P × t），直接反映电池化学能做功与能量释放；\n")
                    append("• 核心优势：消除了不同设备工作电压差异及串联双电芯架构对容量判读的干扰，是专业级硬件能耗分析的基准单位；\n")
                    append("• 换算关系：能量(Wh) = 功率(W) × 时长(h)。")
                }
                Pair(title, message)
            }
        }
    }
}



