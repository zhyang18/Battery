package com.battery.analysis.domain.estimator

import com.battery.analysis.domain.estimator.model.ConfidenceLevel
import com.battery.analysis.domain.estimator.model.DataQuality
import com.battery.analysis.domain.estimator.model.EnergySource
import com.battery.analysis.domain.estimator.model.LearnedSocEnergyCurve
import com.battery.analysis.domain.estimator.model.ModelMaturity
// BatteryUnitNormalizer is in the same package

/**
 * 电池剩余估算可用电能（Estimated Usable Energy）结果实体类。
 * 遵循严格物理能量守恒：E_usable = E_base * K_T。
 *
 * @property usableEnergyWh 经过平滑温度修正后的估算可用电能（单位：Wh，若无法推算则为 null，严禁假数据）
 * @property rawEnergyWh 未经温度修正的基准估算电能 E_base（单位：Wh，若无法推算则为 null）
 * @property source 剩余电能的获取来源 [EnergySource]
 * @property quality 数据质量等级 [DataQuality]
 * @property temperatureFactor 当前环境与电芯温度平滑修正系数 K_T（取值 0.70 至 1.0 之间）
 * @property notes 相关的计算依据或降级说明标签集合
 * @property energyConfidence 能量数据源质量与置信度评分（取值 0.0 至 1.0 之间）
 */
data class UsableRemainingEnergyEstimate(
    val usableEnergyWh: Double?,
    val rawEnergyWh: Double?,
    val source: EnergySource,
    val quality: DataQuality,
    val temperatureFactor: Double,
    val notes: Set<String> = emptySet(),
    val energyConfidence: Double = 1.0
)

/**
 * 电池当前剩余估算可用电能（瓦时 Wh）计算器（RemainingEnergyEstimator）。
 * 遵循质量驱动的数据源动态仲裁机制：统筹硬件原生能量寄存器、实测放电历史学习曲线、
 * 实时电荷计数器与基准容量电量折算，严格秉持零虚构数据原则：无有效数据源时如实返回 null。
 */
class RemainingEnergyEstimator {

    companion object {
        /** 默认锂电池放电平台标称电压 (单位：V) */
        const val DEFAULT_NOMINAL_VOLTAGE: Double = 3.85

        /** 纳瓦时转瓦时的物理除数常数 (1 Wh = 10^9 nWh) */
        private const val NWH_TO_WH_FACTOR: Double = 1_000_000_000.0

        /**
         * 计算环境与电芯温度对电池实际可用电能的经验性平滑修正系数 K_T。
         * 在常温区间（15℃ 至 38℃）系数为 1.0；
         * 在低温区间（锂离子活性受限）平滑过渡（-15℃ 为 0.70，15℃ 为 1.0）；
         * 在高温区间（38℃ 至 50℃）平滑连续过渡至 0.95，彻底消除分段台阶跳变。
         *
         * @param tempCelsius 当前电池温度（单位：摄氏度 ℃，缺失时传入 null）
         * @return 可用电能平滑温度修正系数（未启用标定时返回 1.0）
         */
        fun calculateTemperatureFactor(tempCelsius: Float?): Double {
            if (tempCelsius == null) return 1.0
            return when {
                tempCelsius in 15.0f..38.0f -> 1.0
                tempCelsius < 15.0f -> {
                    val clamped = tempCelsius.coerceIn(-15.0f, 15.0f)
                    0.70 + ((clamped - (-15.0f)) / 30.0f) * 0.30
                }
                tempCelsius > 38.0f -> {
                    val clamped = tempCelsius.coerceIn(38.0f, 50.0f)
                    1.0 - ((clamped - 38.0f) / 12.0f) * 0.05
                }
                else -> 1.0
            }
        }
    }

    /**
     * 估算当前电池剩余状态下整机可释放的真实物理可用能量（单位：Wh）。
     *
     * 质量驱动仲裁逻辑：
     * 1. 硬件直接上报的剩余能量 Wh（若是正有限值直接采用）；
     * 2. 硬件原生纳瓦时能量寄存器直读（BATTERY_PROPERTY_ENERGY_COUNTER，nWh -> Wh）；
     * 3. 实测放电历史学习曲线（若具备成熟度且覆盖当前 SOC，按实测梯形积分模型输出）；
     * 4. 硬件电量计实时剩余电荷计数器（BATTERY_PROPERTY_CHARGE_COUNTER，严格除以 1000 规范为 mAh 结合电压折算）；
     * 5. 基准容量（优先当前真实满充容量 FCC，其次设计容量）结合当前电量百分比与标称电压推算；
     * 6. 零假数据原则：缺乏可靠数据源时忠实返回 null，绝不合成伪造数据。
     *
     * @param remainingEnergyWh 硬件直接换算或上报的剩余能量（单位：Wh，可选）
     * @param nativeEnergyNwh 硬件底层能量计数器（单位：纳瓦时 nWh，可选）
     * @param chargeCounterUah 硬件底层电荷计数器（单位：微安时 uAh，可选）
     * @param batteryLevelPercent 当前电池剩余电量百分比（0.0 ~ 100.0）
     * @param effectiveCapacityMah 设备有效基准容量（单位：mAh，可选）
     * @param tempCelsius 电池当前温度（单位：℃，可选）
     * @param nominalVoltage 电池标称工作平台电压（单位：V，默认 3.85V）
     * @param learnedCurve 电池 5% SOC 实测放电历史自学习曲线模型（可选）
     * @param enableTemperatureDerating 是否启用实验性温度折损乘数（默认 false，防止对电量计读数二次惩罚）
     * @return 剩余可用物理能量估算实体 [UsableRemainingEnergyEstimate]
     */
    fun estimateUsableRemainingEnergy(
        remainingEnergyWh: Float? = null,
        nativeEnergyNwh: Long? = null,
        chargeCounterUah: Int? = null,
        batteryLevelPercent: Float? = null,
        effectiveCapacityMah: Float? = null,
        tempCelsius: Float? = null,
        nominalVoltage: Double = DEFAULT_NOMINAL_VOLTAGE,
        learnedCurve: LearnedSocEnergyCurve? = null,
        enableTemperatureDerating: Boolean = false
    ): UsableRemainingEnergyEstimate {
        val safeVolt = if (nominalVoltage > 0.0 && nominalVoltage.isFinite()) nominalVoltage else DEFAULT_NOMINAL_VOLTAGE
        // 默认不进行未经设备标定的通用硬编码温度折损，忠实反映底层硬件真实数据
        val kt = if (enableTemperatureDerating) calculateTemperatureFactor(tempCelsius) else 1.0

        val maxFullWh = if (effectiveCapacityMah != null && effectiveCapacityMah > 0f) {
            (effectiveCapacityMah.toDouble() * safeVolt) / 1000.0
        } else null
        val expectedFccRemainingWh = if (maxFullWh != null && batteryLevelPercent != null && batteryLevelPercent > 0f) {
            maxFullWh * (batteryLevelPercent.toDouble().coerceIn(0.0, 100.0) / 100.0)
        } else null

        // 策略 1：直接传入的有效硬件剩余能量 Wh
        if (remainingEnergyWh != null && remainingEnergyWh > 0f && remainingEnergyWh.isFinite()) {
            val raw = remainingEnergyWh.toDouble()
            // 防御性校验：若传入的剩余能量超过了当前基准满电总容量（说明硬件上报基于出厂设计容量未校准），强制校准至预期 FCC 剩余能量
            val safeRaw = if (maxFullWh != null && raw > maxFullWh) {
                expectedFccRemainingWh ?: maxFullWh
            } else {
                raw
            }
            val usable = safeRaw * kt
            return UsableRemainingEnergyEstimate(
                usableEnergyWh = if (usable > 0.0 && usable.isFinite()) usable else null,
                rawEnergyWh = safeRaw,
                source = EnergySource.NATIVE_FULL_ENERGY,
                quality = DataQuality.VALID,
                temperatureFactor = kt,
                notes = setOf("基于直接硬件剩余能量上报"),
                energyConfidence = 1.0
            )
        }

        // 策略 2：硬件原生纳瓦时能量寄存器直读 (nWh -> Wh)
        if (nativeEnergyNwh != null && nativeEnergyNwh != Long.MIN_VALUE && nativeEnergyNwh > 0L) {
            val raw = nativeEnergyNwh.toDouble() / NWH_TO_WH_FACTOR
            if (raw > 0.0 && raw.isFinite()) {
                val safeRaw = if (maxFullWh != null && raw > maxFullWh) {
                    expectedFccRemainingWh ?: maxFullWh
                } else {
                    raw
                }
                val usable = safeRaw * kt
                return UsableRemainingEnergyEstimate(
                    usableEnergyWh = if (usable > 0.0 && usable.isFinite()) usable else null,
                    rawEnergyWh = safeRaw,
                    source = EnergySource.NATIVE_FULL_ENERGY,
                    quality = DataQuality.VALID,
                    temperatureFactor = kt,
                    notes = setOf("基于硬件电量计原生纳瓦时能量寄存器"),
                    energyConfidence = 1.0
                )
            }
        }

        // 策略 3：实测放电历史学习曲线累加推算（绕开厂商锁容与固化标压黑盒）
        if (learnedCurve != null && batteryLevelPercent != null && batteryLevelPercent > 0f) {
            val hybrid = learnedCurve.estimateHybridRemainingEnergy(
                currentSocPercent = batteryLevelPercent,
                effectiveCapacityMah = effectiveCapacityMah?.toDouble(),
                nominalVoltageVolts = safeVolt,
                temperatureCelsius = tempCelsius
            )
            val learnedWh = hybrid?.remainingEnergyWh ?: learnedCurve.estimateRemainingEnergyWh(batteryLevelPercent)
            if (learnedWh != null && learnedWh > 0.0 && learnedWh.isFinite()) {
                val usable = learnedWh * kt
                val effectiveConfidence = hybrid?.confidence ?: learnedCurve.confidence
                val confidenceScore = when (effectiveConfidence) {
                    ConfidenceLevel.HIGH -> 0.95
                    ConfidenceLevel.MEDIUM -> 0.85
                    ConfidenceLevel.LOW -> 0.70
                    ConfidenceLevel.UNRELIABLE -> 0.50
                }
                return UsableRemainingEnergyEstimate(
                    usableEnergyWh = if (usable > 0.0 && usable.isFinite()) usable else null,
                    rawEnergyWh = learnedWh,
                    source = EnergySource.LEARNED_SOC_CURVE,
                    quality = if (learnedCurve.maturity == ModelMaturity.CONVERGED) DataQuality.VALID else DataQuality.LOW_QUALITY,
                    temperatureFactor = kt,
                    notes = setOf(
                        if (hybrid != null && !hybrid.isFullyLearned) {
                            val measCov = String.format(java.util.Locale.US, "%.0f%%", hybrid.measuredSocCoverage * 100f)
                            val modCov = String.format(java.util.Locale.US, "%.0f%%", hybrid.modeledSocCoverage * 100f)
                            "基于 5% SOC 放电混合估算模型 (实测覆盖 $measCov + 模型推演 $modCov, ${hybrid.maturity.name})"
                        } else {
                            "基于 5% SOC 历史放电实测微积分学习模型 (${learnedCurve.maturity.name})"
                        }
                    ),
                    energyConfidence = confidenceScore
                )
            }
        }

        // 策略 4：硬件实时电荷计数器（结合物理量级合理性检验自适应归一化为 mAh 结合电压模型折算）
        if (chargeCounterUah != null && chargeCounterUah > 0) {
            val currentMah = BatteryUnitNormalizer.normalizeChargeCounterToMah(
                rawCounter = chargeCounterUah.toLong(),
                effectiveCapacityMah = effectiveCapacityMah?.toDouble(),
                batteryPercent = batteryLevelPercent?.toDouble()
            )
            if (currentMah != null && currentMah > 0.0) {
                val raw = (currentMah * safeVolt) / 1000.0
                if (raw > 0.0 && raw.isFinite()) {
                    val usable = raw * kt
                    return UsableRemainingEnergyEstimate(
                        usableEnergyWh = if (usable > 0.0 && usable.isFinite()) usable else null,
                        rawEnergyWh = raw,
                        source = EnergySource.FCC_TIMES_NOMINAL_VOLTAGE,
                        quality = DataQuality.VALID,
                        temperatureFactor = kt,
                        notes = setOf("基于硬件实时电荷计数器与标称电压折算"),
                        energyConfidence = 0.80
                    )
                }
            }
        }

        // 策略 5：基准容量结合电量百分比粗算
        if (batteryLevelPercent != null && batteryLevelPercent > 0f && effectiveCapacityMah != null && effectiveCapacityMah > 0f) {
            val safePercent = batteryLevelPercent.coerceIn(0.0f, 100.0f).toDouble()
            val remainingMah = effectiveCapacityMah.toDouble() * (safePercent / 100.0)
            val raw = (remainingMah * safeVolt) / 1000.0
            if (raw > 0.0 && raw.isFinite()) {
                val usable = raw * kt
                return UsableRemainingEnergyEstimate(
                    usableEnergyWh = if (usable > 0.0 && usable.isFinite()) usable else null,
                    rawEnergyWh = raw,
                    source = EnergySource.FCC_TIMES_NOMINAL_VOLTAGE,
                    quality = DataQuality.VALID,
                    temperatureFactor = kt,
                    notes = setOf("基于有效基准容量与电量百分比粗算"),
                    energyConfidence = 0.65
                )
            }
        }

        // 兜底：无有效能量数据，如实返回不可用（严禁假数据）
        return UsableRemainingEnergyEstimate(
            usableEnergyWh = null,
            rawEnergyWh = null,
            source = EnergySource.UNAVAILABLE,
            quality = DataQuality.INVALID,
            temperatureFactor = kt,
            notes = setOf("缺乏有效的硬件能量或容量电量数据"),
            energyConfidence = 0.0
        )
    }
}
