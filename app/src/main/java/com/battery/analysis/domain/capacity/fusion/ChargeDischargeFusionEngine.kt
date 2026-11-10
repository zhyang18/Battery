package com.battery.analysis.domain.capacity.fusion

import com.battery.analysis.domain.capacity.estimator.ChargeCapacityEstimator
import com.battery.analysis.domain.capacity.estimator.DischargeCapacityEstimator
import com.battery.analysis.domain.capacity.estimator.SocEnergyCurveLearner
import com.battery.analysis.domain.capacity.model.BoundaryMatchStatus
import com.battery.analysis.domain.capacity.model.CapacityBoundary
import com.battery.analysis.domain.capacity.model.CapacityChannel
import com.battery.analysis.domain.capacity.model.Confidence
import com.battery.analysis.domain.capacity.model.FccCandidateSample
import com.battery.analysis.domain.capacity.model.FccChannelEstimate
import com.battery.analysis.domain.capacity.model.FccUnifiedEstimate
import com.battery.analysis.domain.capacity.model.LearningMaturity
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 充放电双向质量感知鲁棒融合引擎（ChargeDischargeFusionEngine）。
 *
 * 遵循技术方案规范：
 * 1. 独立估计放电主通道与充电辅助通道；
 * 2. 误差方差反比加权 + Huber 鲁棒损失降低异常样本扰动；
 * 3. 通道冲突仲裁：当充放电差异超过统计不确定度可解释范围时，判定为 DISAGREEMENT，严禁强行算术平均；
 * 4. 严谨状态机：PENDING -> INITIAL -> CONVERGED -> STABLE / DISAGREEMENT；
 * 5. 独立能量通道闭环，提供 FCC_E 与 SOH_E；
 * 6. 区分 SYSTEM_USABLE 边界与电芯化学容量。
 */
object ChargeDischargeFusionEngine {

    /** 触发通道分歧（DISAGREEMENT）的相对差异率门限（10%） */
    const val CHANNEL_CONFLICT_THRESHOLD_PERCENT: Double = 10.0

    /**
     * 采用 Huber 鲁棒损失对样本集执行单通道迭代加权最小二乘回归。
     * 求解 \arg\min_F \sum_i w_i \rho(Q_i - F \Delta z_i)
     */
    fun huberWeightedRegression(candidates: List<FccCandidateSample>, maxIterations: Int = 5): Pair<Double?, Double?> {
        val valid = candidates.filter { !it.isExcluded && it.fccObservedMah > 0.0 && it.deltaSoc > 0.0 }
        if (valid.isEmpty()) return Pair(null, null)

        // 初始估计：加权均值
        var currentFcc = valid.sumOf { (1.0 / it.varianceMah2.coerceAtLeast(1.0)) * it.fccObservedMah } /
                valid.sumOf { 1.0 / it.varianceMah2.coerceAtLeast(1.0) }

        // Huber 门限 δ: 取 1.345 * 中位数残差
        for (iter in 0 until maxIterations) {
            val residuals = valid.map { abs(it.chargeMah - currentFcc * it.deltaSoc) }
            val medianResidual = calculateMedian(residuals) ?: 100.0
            val delta = max(50.0, 1.345 * medianResidual)

            var sumW = 0.0
            var sumWVal = 0.0

            for (c in valid) {
                val r = abs(c.chargeMah - currentFcc * c.deltaSoc)
                // Huber 权重系数: r <= delta 为 1.0, r > delta 为 delta / r
                val huberWeight = if (r <= delta) 1.0 else (delta / r)
                val varianceWeight = 1.0 / c.varianceMah2.coerceAtLeast(1.0)
                val totalWeight = varianceWeight * huberWeight

                sumW += totalWeight * c.deltaSoc * c.deltaSoc
                sumWVal += totalWeight * c.deltaSoc * c.chargeMah
            }

            if (sumW > 0.0) {
                currentFcc = sumWVal / sumW
            }
        }

        // 计算合成标准差
        var weightedVarSum = 0.0
        var totalW = 0.0
        for (c in valid) {
            val w = 1.0 / c.varianceMah2.coerceAtLeast(1.0)
            val diff = c.fccObservedMah - currentFcc
            weightedVarSum += w * diff * diff
            totalW += w
        }
        val uncertainty = if (valid.size > 1 && totalW > 0.0) {
            (1.96 * sqrt(weightedVarSum / totalW)) / sqrt(valid.size.toDouble())
        } else {
            sqrt(valid.first().varianceMah2)
        }

        return Pair(currentFcc, uncertainty)
    }

    /**
     * 充放电双向全流程融合决策函数。
     */
    fun fuse(
        candidates: List<FccCandidateSample>,
        energyLearner: SocEnergyCurveLearner,
        designCapacityMah: Double?,
        designVoltageVolts: Double = 3.85,
        currentSocPercent: Int?
    ): FccUnifiedEstimate {
        val dischargeCandidates = candidates.filter { it.channel == CapacityChannel.DISCHARGE }
        val chargeCandidates = candidates.filter { it.channel == CapacityChannel.CHARGE }

        val dischargeEstimate = DischargeCapacityEstimator.estimateChannel(dischargeCandidates)
        val chargeEstimate = ChargeCapacityEstimator.estimateChannel(chargeCandidates)

        val disFcc = dischargeEstimate.fccQMah
        val disUncertainty = dischargeEstimate.uncertaintyMah ?: 300.0

        val chgFcc = chargeEstimate.fccQMah
        val chgUncertainty = chargeEstimate.uncertaintyMah ?: 400.0

        var conflictPercent: Double? = null
        var isDisagreement = false

        val (fusedFcc, fusedUncertainty) = when {
            // 两通道均有效：检验自洽性
            disFcc != null && chgFcc != null -> {
                val mean = (disFcc + chgFcc) / 2.0
                val diff = abs(disFcc - chgFcc)
                val cPercent = if (mean > 0.0) (diff / mean) * 100.0 else 0.0
                conflictPercent = cPercent

                val combinedSigma = sqrt(disUncertainty * disUncertainty + chgUncertainty * chgUncertainty)
                // 冲突判决：相对差异大于门限且差异超过 2 倍合并方差
                if (cPercent > CHANNEL_CONFLICT_THRESHOLD_PERCENT && diff > 2.0 * combinedSigma) {
                    isDisagreement = true
                    // 发生冲突时，放电主估计通道代表实际放电做功，以此为主，但不缩小不确定度，并标记冲突
                    Pair(disFcc, disUncertainty)
                } else {
                    // 正常逆方差加权融合。
                    // 遵循 Section 11.4 规则 5：两通道共享同一电量计链路，融合已避免方差过度收缩（不能低于单通道不确定度的 90%）
                    val wDis = 1.0 / (disUncertainty * disUncertainty).coerceAtLeast(1.0)
                    val wChg = 1.0 / (chgUncertainty * chgUncertainty).coerceAtLeast(1.0)
                    val fused = (disFcc * wDis + chgFcc * wChg) / (wDis + wChg)
                    val rawSigma = 1.0 / sqrt(wDis + wChg)
                    val fusedSigma = max(rawSigma, min(disUncertainty, chgUncertainty) * 0.90)
                    Pair(fused, fusedSigma)
                }
            }
            // 仅放电主通道有效
            disFcc != null -> Pair(disFcc, disUncertainty)
            // 仅充电辅助通道有效
            chgFcc != null -> Pair(chgFcc, chgUncertainty)
            // 均无有效估计
            else -> Pair(null, null)
        }

        // 成熟度状态机判决
        val totalValidCount = (dischargeEstimate.sampleCount) + (chargeEstimate.sampleCount)
        val totalSocSpan = dischargeEstimate.totalSocSpan + chargeEstimate.totalSocSpan
        val rawMaturity = when {
            isDisagreement -> LearningMaturity.DISAGREEMENT
            fusedFcc == null -> LearningMaturity.PENDING
            totalValidCount >= 6 && totalSocSpan >= 2.0 -> LearningMaturity.STABLE
            totalValidCount >= 3 && totalSocSpan >= 0.80 -> LearningMaturity.CONVERGED
            totalValidCount >= 1 -> LearningMaturity.INITIAL
            else -> LearningMaturity.PENDING
        }

        // 遵循 Section 11.4 规范：若缺少放电主估计通道验证，纯充电辅助通道估计成熟度最高限制为 INITIAL
        val maturity = if (dischargeEstimate.sampleCount == 0 && rawMaturity in listOf(LearningMaturity.CONVERGED, LearningMaturity.STABLE)) {
            LearningMaturity.INITIAL
        } else {
            rawMaturity
        }

        val confidence = when {
            isDisagreement -> Confidence.LOW
            maturity == LearningMaturity.STABLE && dischargeEstimate.confidence == Confidence.HIGH -> Confidence.HIGH
            maturity == LearningMaturity.CONVERGED -> Confidence.MEDIUM
            else -> Confidence.LOW
        }

        // 能量通道估计
        val (fullEWh, uncertaintyEWh) = energyLearner.estimateFullEnergyWh(fusedFcc)
        val energyCoverage = energyLearner.getCoverageRatio()

        // 计算健康度 SOH（以系统可用边界 SYSTEM_USABLE 输出）
        val sohQ = if (fusedFcc != null && designCapacityMah != null && designCapacityMah > 0.0) {
            (fusedFcc / designCapacityMah) * 100.0
        } else null

        val designEnergyWh = if (designCapacityMah != null && designCapacityMah > 0.0) {
            (designCapacityMah * designVoltageVolts) / 1000.0
        } else null

        val sohE = if (fullEWh != null && designEnergyWh != null && designEnergyWh > 0.0) {
            (fullEWh / designEnergyWh) * 100.0
        } else null

        // 剩余容量与能量计算
        val safeSoc = currentSocPercent?.coerceIn(0, 100)
        val remainingQ = if (fusedFcc != null && safeSoc != null) {
            fusedFcc * (safeSoc / 100.0)
        } else null

        val fallbackWh = fullEWh ?: fusedFcc?.let { (it * designVoltageVolts) / 1000.0 }
        val remainingE = if (safeSoc != null) {
            energyLearner.estimateRemainingEnergyWh(safeSoc, fallbackWh)
        } else null

        return FccUnifiedEstimate(
            fccQMah = fusedFcc,
            fccQUncertaintyMah = fusedUncertainty,
            fccEWh = fullEWh,
            fccEUncertaintyWh = uncertaintyEWh,
            sohQPercent = sohQ,
            sohEPercent = sohE,
            remainingQMah = remainingQ,
            remainingEWh = remainingE,
            currentSocPercent = safeSoc,
            confidence = confidence,
            maturity = maturity,
            boundary = CapacityBoundary.SYSTEM_USABLE,
            boundaryMatchStatus = BoundaryMatchStatus.UNCONFIRMED,
            designCapacityMah = designCapacityMah,
            designEnergyWh = designEnergyWh,
            dischargeChannel = dischargeEstimate,
            chargeChannel = chargeEstimate,
            energyBins = energyLearner.getEnergyBins(),
            energyCurveCoverage = energyCoverage,
            channelConflictPercent = conflictPercent,
            isSameGaugeSource = true,
            algorithmVersion = "FCC-v1.0",
            updatedTimestampMs = System.currentTimeMillis()
        )
    }

    private fun calculateMedian(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val s = sorted.size
        return if (s % 2 == 1) sorted[s / 2] else (sorted[s / 2 - 1] + sorted[s / 2]) / 2.0
    }
}
