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
     * @return 会话校验与质量分级结果实体 [ValidationResult]
     */
    fun validateDischargeSession(
        integrationResult: DischargeEnergyIntegrator.DischargeIntegrationResult,
        sessionId: String,
        effectiveCapacityMah: Double? = null
    ): ValidationResult {
        val completeList = mutableListOf<SocEnergyBinSample>()
        val partialList = mutableListOf<SocEnergyBinSample>()
        val rejectedBins = mutableSetOf<Int>()

        // 1. 提取所有深睡断层所波及的 SOC 区间索引
        for (gap in integrationResult.unobservedRanges) {
            val minSoc = minOf(gap.startSocPercent, gap.endSocPercent)
            val maxSoc = maxOf(gap.startSocPercent, gap.endSocPercent)
            for (binIndex in 0 until LearnedSocEnergyCurve.BIN_COUNT) {
                val binFloor = binIndex * LearnedSocEnergyCurve.BIN_SOC_STEP
                val binCeil = binFloor + LearnedSocEnergyCurve.BIN_SOC_STEP
                // 若断层与该 Bin 存在交集，将该 Bin 列入受损拒绝名单
                if (maxSoc > binFloor && minSoc < binCeil) {
                    rejectedBins.add(binIndex)
                }
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
            val coverage = (span / LearnedSocEnergyCurve.BIN_SOC_STEP).toDouble().coerceIn(0.0, 1.0)

            // 计算该区间的平均放电倍率 C-rate = |I_avg| / FCC
            val cRate = if (effectiveCapacityMah != null && effectiveCapacityMah > 0.0 && delta.averageCurrentAmperes != null) {
                val fccAmperes = effectiveCapacityMah / 1000.0
                (delta.averageCurrentAmperes / fccAmperes).coerceAtLeast(0.0)
            } else null

            val sample = SocEnergyBinSample(
                binIndex = binIndex,
                energyWh = delta.energyWh,
                durationMs = delta.durationMs,
                startSocPercent = delta.startSocPercent,
                endSocPercent = delta.endSocPercent,
                coverageFraction = coverage,
                temperatureC = delta.averageTemperatureCelsius?.toDouble(),
                averageDischargeCRate = cRate,
                sessionId = sessionId,
                quality = if (coverage >= MIN_COMPLETE_COVERAGE_FRACTION) SampleQuality.COMPLETE_BIN else SampleQuality.PARTIAL_BIN
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
