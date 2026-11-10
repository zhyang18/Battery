package com.battery.analysis.domain.capacity.estimator

import com.battery.analysis.domain.capacity.integration.CoulombIntegrator
import com.battery.analysis.domain.capacity.model.CapacityChannel
import com.battery.analysis.domain.capacity.model.CapacitySample
import com.battery.analysis.domain.capacity.model.Confidence
import com.battery.analysis.domain.capacity.model.FccCandidateSample
import com.battery.analysis.domain.capacity.model.FccChannelEstimate
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 放电容量主估计通道（DischargeCapacityEstimator）。
 *
 * 遵循技术方案规范：
 * 1. 直接观测电池向系统释放的电荷量，最贴近“用户实际可用容量”；
 * 2. 梯形安时积分 Q_dis 与可靠 SOC 锚点变化 Δz；
 * 3. 跨度分级：>= 30% (A类), 20%~30% (B类), 10%~20% (C类), < 10% (拒绝)；
 * 4. 一阶泰勒展开误差传递方差评估；
 * 5. 记录电荷加权平均温度、电压范围与放电工况。
 */
object DischargeCapacityEstimator {

    /** 两个独立采样点整数 SOC 均匀截断误差合并方差常数: 2 * (0.01^2 / 12) */
    private const val VARIANCE_DELTA_Z: Double = 1.6666666666666667e-5

    /** 传感器电流安时微积分基准相对不确定度（保守取 2.0%） */
    private const val RELATIVE_UNCERTAINTY_Q: Double = 0.02

    /** 最小物理入库 SOC 跨度门限（10%） */
    const val MIN_ELIGIBLE_SOC_SPAN: Double = 0.10

    /**
     * 计算单次候选样本的一阶泰勒展开方差（误差传递不确定度）。
     * \sigma_{res}^2 \approx (\sigma_Q / \Delta z)^2 + (Q^2 / \Delta z^4) * \sigma_{\Delta z}^2
     */
    fun calculateSampleVariance(chargeMah: Double, deltaSoc: Double): Double {
        val dz = deltaSoc.coerceIn(0.01, 1.0)
        val dq = abs(chargeMah).coerceAtLeast(1.0)

        val sigmaDq = RELATIVE_UNCERTAINTY_Q * dq
        val term1 = (sigmaDq / dz).pow(2.0)
        val term2 = (dq.pow(2.0) / dz.pow(4.0)) * VARIANCE_DELTA_Z
        return term1 + term2
    }

    /**
     * 从一组放电采样点中评估并构建单次放电候选样本。
     *
     * @param sessionId 唯一会话 ID
     * @param samples 连续采样序列（放电电流 I_discharge > 0）
     * @param startSoc 起始 SOC（0.0 ~ 1.0）
     * @param endSoc 结束 SOC（0.0 ~ 1.0）
     * @param isDualCell 是否串联双电芯
     * @return 候选样本实体 [FccCandidateSample]
     */
    fun buildCandidate(
        sessionId: String,
        samples: List<CapacitySample>,
        startSoc: Double,
        endSoc: Double,
        isDualCell: Boolean = false
    ): FccCandidateSample {
        val deltaSoc = abs(startSoc - endSoc)
        val segment = CoulombIntegrator.integrate(samples)

        val qMah = segment.chargeMah
        val eWh = segment.energyWh
        val dz = deltaSoc.coerceIn(0.001, 1.0)
        val fccObserved = if (deltaSoc > 0.0) qMah / dz else 0.0
        val variance = calculateSampleVariance(qMah, dz)

        val maxTemp = samples.mapNotNull { it.temperatureC }.maxOrNull()

        // 质量缺陷检查
        val isSpanTooSmall = deltaSoc < MIN_ELIGIBLE_SOC_SPAN
        val isRangeDefective = fccObserved < 500.0 || fccObserved > 25000.0
        val isCoveragePoor = segment.coverageRatio < 0.60
        val isPhysicallyInconsistent = !CoulombIntegrator.isPhysicalConsistencyValid(qMah, eWh, isDualCell)

        val isExcluded = isSpanTooSmall || isRangeDefective || isCoveragePoor || isPhysicallyInconsistent
        val exclusionReason = when {
            isSpanTooSmall -> "SOC 跨度过小(<10%)，量化误差敏感"
            isRangeDefective -> "推算容量超出物理合理范围"
            isCoveragePoor -> "采样断层过多(覆盖率<60%)"
            isPhysicallyInconsistent -> "电荷与能量自洽性异常"
            else -> ""
        }

        return FccCandidateSample(
            sessionId = sessionId,
            channel = CapacityChannel.DISCHARGE,
            startSoc = startSoc,
            endSoc = endSoc,
            deltaSoc = deltaSoc,
            chargeMah = qMah,
            energyWh = eWh,
            fccObservedMah = fccObserved,
            varianceMah2 = variance,
            avgVoltageVolts = segment.avgVoltageVolts,
            avgTemperatureC = segment.avgTemperatureC,
            maxTemperatureC = maxTemp,
            durationSec = segment.validDurationSec,
            coverageRatio = segment.coverageRatio,
            isExcluded = isExcluded,
            exclusionReason = exclusionReason
        )
    }

    /**
     * 针对放电候选样本集执行独立通道加权最小二乘估计。
     *
     * @param candidates 放电候选样本列表
     * @return 放电通道估计结果 [FccChannelEstimate]
     */
    fun estimateChannel(candidates: List<FccCandidateSample>): FccChannelEstimate {
        val valid = candidates.filter { !it.isExcluded && it.fccObservedMah > 0.0 && it.channel == CapacityChannel.DISCHARGE }
        if (valid.isEmpty()) {
            return FccChannelEstimate(
                channel = CapacityChannel.DISCHARGE,
                fccQMah = null,
                uncertaintyMah = null,
                sampleCount = 0,
                totalSocSpan = 0.0,
                confidence = Confidence.LOW,
                details = "暂无合格放电样本"
            )
        }

        var sumWeight = 0.0
        var sumWeightedFcc = 0.0
        var totalSpan = 0.0
        var tempSum = 0.0
        var tempSpanSum = 0.0

        for (c in valid) {
            val weight = 1.0 / c.varianceMah2.coerceAtLeast(1.0)
            sumWeight += weight
            sumWeightedFcc += weight * c.fccObservedMah
            totalSpan += c.deltaSoc
            if (c.avgTemperatureC != null) {
                tempSum += c.avgTemperatureC * c.deltaSoc
                tempSpanSum += c.deltaSoc
            }
        }

        val estimatedFcc = sumWeightedFcc / sumWeight

        // 估计标准误差与置信区间 (95% 置信度: 1.96 * SE)
        var weightedVarSum = 0.0
        for (c in valid) {
            val w = 1.0 / c.varianceMah2.coerceAtLeast(1.0)
            val diff = c.fccObservedMah - estimatedFcc
            weightedVarSum += w * diff * diff
        }
        val weightedStdDev = sqrt(weightedVarSum / sumWeight)
        val uncertainty = if (valid.size > 1) {
            (1.96 * weightedStdDev) / sqrt(valid.size.toDouble())
        } else {
            sqrt(valid.first().varianceMah2)
        }

        val avgTemp = if (tempSpanSum > 0.0) tempSum / tempSpanSum else null

        val confidence = when {
            valid.size >= 4 && totalSpan >= 1.5 && (uncertainty / estimatedFcc) < 0.04 -> Confidence.HIGH
            valid.size >= 2 && totalSpan >= 0.5 -> Confidence.MEDIUM
            else -> Confidence.LOW
        }

        return FccChannelEstimate(
            channel = CapacityChannel.DISCHARGE,
            fccQMah = estimatedFcc,
            uncertaintyMah = uncertainty,
            sampleCount = valid.size,
            totalSocSpan = totalSpan,
            avgTemperatureC = avgTemp,
            confidence = confidence,
            details = String.format(java.util.Locale.US, "放电样本: %d 条, 跨度: %.0f%%", valid.size, totalSpan * 100.0)
        )
    }
}
