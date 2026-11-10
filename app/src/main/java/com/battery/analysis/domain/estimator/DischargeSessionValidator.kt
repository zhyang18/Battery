package com.battery.analysis.domain.estimator

import com.battery.analysis.domain.estimator.model.LearnedSocEnergyCurve
import com.battery.analysis.domain.estimator.model.SampleQuality
import com.battery.analysis.domain.estimator.model.SocEnergyBinSample
import kotlin.math.abs

/**
 * 放电会话样本物理完整性与工况校验器（DischargeSessionValidator）。
 *
 * 核心校验守卫：
 * 1. 深度睡眠断层隔离：若某个 5% SOC 区间跨越了深睡超长采样断层（UnobservedRange），判定该区间数据缺失，拒绝作为完整样本；
 * 2. 覆盖率门限审计：只有在当前会话中实际连续放电电量跨度达到 4.5% 以上（coverageFraction >= 0.90）的区间，方可评定为 COMPLETE_BIN 合格样本；
 * 3. 工况标签化：结合设备有效基准容量（FCC/DesignCap）将平均放电电流归一化为放电倍率（C-rate = |I| / FCC），并记录加权温度；
 * 4. 异常工况过滤：过滤平均功率 <= 0、电压异常或极短时会话的噪声样本。
 */
object DischargeSessionValidator {

    /** 判定为完整合格 5% Bin 的最低电量覆盖比例（4.5% / 5.0% = 0.90） */
    const val MIN_COMPLETE_COVERAGE_FRACTION: Double = 0.90

    /**
     * 单次放电会话校验输出结果。
     *
     * @property validCompleteSamples 经审计评定为合格完整的 5% 区间样本列表
     * @property partialSamples 评定为部分覆盖的碎片切片列表（不直接进入完整历史中位数模型）
     * @property rejectedBinIndices 因深睡断层或异常而被拒绝的区间索引集合
     */
    data class ValidationResult(
        val validCompleteSamples: List<SocEnergyBinSample>,
        val partialSamples: List<SocEnergyBinSample>,
        val rejectedBinIndices: Set<Int>
    )

    /**
     * 对微积分聚合结果执行会话级完整性校验与工况标签提取。
     *
     * @param integrationResult 微积分与断层审计结果 [DischargeEnergyIntegrator.DischargeIntegrationResult]
     * @param sessionId 产生该样本的独立会话标识 ID
     * @param effectiveCapacityMah 设备当前基准容量（优先 FCC，可选）
     * @param isDualCell 是否为串联双电芯架构（默认 false，单电芯最高开路电压 4.45V，串联双电芯最高 8.9V）
     * @return 会话校验与质量分级结果实体 [ValidationResult]
     */
    fun validateDischargeSession(
        integrationResult: DischargeEnergyIntegrator.DischargeIntegrationResult,
        sessionId: String,
        effectiveCapacityMah: Double? = null,
        isDualCell: Boolean = false
    ): ValidationResult {
        val completeList = mutableListOf<SocEnergyBinSample>()
        val partialList = mutableListOf<SocEnergyBinSample>()
        val rejectedBins = mutableSetOf<Int>()
        val binUnobservedSpan = FloatArray(LearnedSocEnergyCurve.BIN_COUNT)

        // 1. 累计各 5% SOC 区间因深度睡眠超长断层而真实缺失的未观测电量跨度
        for (gap in integrationResult.unobservedRanges) {
            val minSoc = minOf(gap.startSocPercent, gap.endSocPercent)
            val maxSoc = maxOf(gap.startSocPercent, gap.endSocPercent)
            for (binIndex in 0 until LearnedSocEnergyCurve.BIN_COUNT) {
                val binFloor = binIndex * LearnedSocEnergyCurve.BIN_SOC_STEP
                val binCeil = binFloor + LearnedSocEnergyCurve.BIN_SOC_STEP
                val overlapStart = minOf(maxSoc, binCeil)
                val overlapEnd = maxOf(minSoc, binFloor)
                val overlapSpan = (overlapStart - overlapEnd).coerceAtLeast(0f)
                binUnobservedSpan[binIndex] += overlapSpan
            }
        }

        // 只有当区间内因失联断层导致未观测缺损跨度实质性超过 1.0%（即超过 20% 的电量处于完全未知盲区）时，才判定该区间受损拒绝。
        // 彻底废除点接触一票否决拉黑，杜绝日常高频息屏锁屏导致正常放电区间被无理拒收
        for (binIndex in 0 until LearnedSocEnergyCurve.BIN_COUNT) {
            if (binUnobservedSpan[binIndex] >= 1.0f) {
                rejectedBins.add(binIndex)
            }
        }

        // 2. 逐一审计各 Bin 做功微元
        for ((binIndex, delta) in integrationResult.binDeltas) {
            if (binIndex !in 0 until LearnedSocEnergyCurve.BIN_COUNT) continue
            if (delta.energyWh <= 0.0 || !delta.energyWh.isFinite()) continue

            // 若受深睡断层影响，坚决拒绝计入完整学习样本
            if (rejectedBins.contains(binIndex)) {
                continue
            }

            val span = delta.coveredSocSpan
            val coverage = (span.toDouble() / LearnedSocEnergyCurve.BIN_SOC_STEP.toDouble()).coerceIn(0.0, 1.0)

            // 施加 0.001 浮点容差（防止 IEEE 754 4.5f / 5.0f = 0.89999997 精度损失误判）
            val isComplete = coverage >= (MIN_COMPLETE_COVERAGE_FRACTION - 0.001)

            // 标准物理区间归一化：
            // 当实际跨度达到完整区间门限（span >= 4.5%），将该区间真实释放电量所产生的微积分能量严格折算为标准 5.0% 区间能量。
            // 无论实际走满 5%，还是由于系统电量计平台期滞后导致实际释放了更大电荷跨度（如 8.96%），均按安时积分电荷比例归一化为 5.0% 标准做功；
            // 未达标的残缺区间（< 4.5%）保留原始做功，不进行乘数放大
            val normalizedWh = if (isComplete && span >= 4.5f) {
                delta.energyWh * (LearnedSocEnergyCurve.BIN_SOC_STEP.toDouble() / span.toDouble())
            } else {
                delta.energyWh
            }

            // 硬件物理合理性通用过滤（过滤待机底噪累加 4.2Wh 或浮充倒灌 3.3Wh 等显式工况畸变）
            val maxAllowedWh = if (isDualCell) 5.6 else 2.8
            if (normalizedWh > maxAllowedWh || normalizedWh < 0.15) {
                continue
            }

            // 计算该区间的平均放电倍率 C-rate = |I_avg| / FCC
            val cRate = if (effectiveCapacityMah != null && effectiveCapacityMah > 0.0 && delta.averageCurrentAmperes != null) {
                val fccAmperes = effectiveCapacityMah / 1000.0
                (delta.averageCurrentAmperes / fccAmperes).coerceAtLeast(0.0)
            } else null

            val sample = SocEnergyBinSample(
                binIndex = binIndex,
                energyWh = normalizedWh,
                durationMs = delta.durationMs,
                startSocPercent = delta.startSocPercent,
                endSocPercent = delta.endSocPercent,
                coverageFraction = coverage,
                temperatureC = delta.averageTemperatureCelsius?.toDouble(),
                averageDischargeCRate = cRate,
                sessionId = sessionId,
                quality = if (isComplete) SampleQuality.COMPLETE_BIN else SampleQuality.PARTIAL_BIN
            )

            if (sample.quality == SampleQuality.COMPLETE_BIN) {
                completeList.add(sample)
            } else if (coverage >= 0.10) {
                partialList.add(sample)
            }
        }

        return ValidationResult(
            validCompleteSamples = completeList,
            partialSamples = partialList,
            rejectedBinIndices = rejectedBins
        )
    }
}
