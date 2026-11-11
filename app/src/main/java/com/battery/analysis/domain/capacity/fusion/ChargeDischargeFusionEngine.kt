package com.battery.analysis.domain.capacity.fusion

import com.battery.analysis.domain.capacity.estimator.ChargeCapacityEstimator
import com.battery.analysis.domain.capacity.estimator.DischargeCapacityEstimator
import com.battery.analysis.domain.capacity.estimator.SocEnergyCurveLearner
import com.battery.analysis.domain.capacity.model.BoundaryMatchStatus
import com.battery.analysis.domain.capacity.model.AcceptedFccQ
import com.battery.analysis.domain.capacity.model.CapacityBoundary
import com.battery.analysis.domain.capacity.model.CapacityChannel
import com.battery.analysis.domain.capacity.model.Confidence
import com.battery.analysis.domain.capacity.model.EnergyCapacityTriad
import com.battery.analysis.domain.capacity.model.FccCandidateSample
import com.battery.analysis.domain.capacity.model.FccChannelEstimate
import com.battery.analysis.domain.capacity.model.FccQChannelResult
import com.battery.analysis.domain.capacity.model.FccUnifiedEstimate
import com.battery.analysis.domain.capacity.model.FullEndpointQuality
import com.battery.analysis.domain.capacity.model.LearningMaturity
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 充放电双向质量感知仲裁与鲁棒融合引擎（ChargeDischargeFusionEngine）。
 *
 * 遵循技术方案规范与阶段三（P2）架构设计：
 * 1. 三通道独立评估：
 *    - 通道一（放电电荷容量 FCC_Q_dis）：电池侧真实微安电流积分、100% 满电平台解耦、SOC 边界严格对齐、多维质检门控；
 *    - 通道二（充电电荷容量 FCC_Q_chg）：充电电流积分、端点识别、库仑效率标定（未标定时成熟度最高为 INITIAL）；
 *    - 通道三（SOC 能量容量 FCC_E）：真实 V×I 积分、20 个 5% SOC 分箱、纯物理直接实测与模型切分估算严格分离；
 * 2. 冲突仲裁与融合冻结：
 *    - 当充放电两通道差异超过 10% 或超出统计不确定度可解释范围时，坚决判定为 DISAGREEMENT；
 *    - 冻结自动融合（isFusionFrozen = true），杜绝异常充电数据覆盖放电主容量，以放电物理实测通道为基准；
 * 3. 能量通道与健康度独立解耦：
 *    - 能量通道未全域 20 段实测学满时，全量 FCC_E 如实为 null；
 *    - SOH_Q 与 SOH_E 分开独立计算，若无全量 FCC_E 则 SOH_E 为 null，严禁拿 SOH_Q 代替；
 * 4. 避免同源电量计方差过度收缩（融合不确定度不低于单通道的 90%）。
 */
object ChargeDischargeFusionEngine {

    /** 触发通道分歧（DISAGREEMENT）的相对差异率门限（10.0%） */
    const val CHANNEL_CONFLICT_THRESHOLD_PERCENT: Double = 10.0

    /**
     * 采用 Huber 鲁棒损失对样本集执行单通道迭代加权最小二乘回归。
     *
     * 求解公式：\arg\min_F \sum_i w_i \rho(Q_i - F \Delta z_i)
     *
     * @param candidates 候选样本列表
     * @param maxIterations 最大迭代次数（默认 5 次）
     * @return Pair(加权回归容量值 mAh, 不确定度标准差 mAh)，无合格样本时返回 Pair(null, null)
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
     * 充放电双向全流程仲裁与融合决策函数。
     *
     * 遵循 P2 核心准则：发生分歧或充电未标定时冻结融合，保持 DISAGREEMENT，主输出绝不冒充将放电通道当作绝对真值（保留上次确认可信值，无历史则为 null）。
     *
     * @param candidates 充放电候选样本列表
     * @param energyLearner 20 段 SOC 能量曲线自学习器
     * @param designCapacityMah 电池设计标称容量（mAh）
     * @param designVoltageVolts 电池标称电压（V，默认 3.85V）
     * @param currentSocPercent 当前电量计 SOC 百分比 (0..100)
     * @param previousAcceptedFccQMah 上次已确认收敛的可信历史容量数值（mAh，可选兼容入参）
     * @param acceptedFccQ 上次已确认收敛的可信历史容量实体（附带版本、成熟度与审计追溯）
     * @return 最终全景统一容量评估输出实体 [FccUnifiedEstimate]
     */
    fun fuse(
        candidates: List<FccCandidateSample>,
        energyLearner: SocEnergyCurveLearner,
        designCapacityMah: Double?,
        designVoltageVolts: Double = 3.85,
        currentSocPercent: Int?,
        previousAcceptedFccQMah: Double? = null,
        acceptedFccQ: AcceptedFccQ? = null
    ): FccUnifiedEstimate {
        val dischargeCandidates = candidates.filter { it.channel == CapacityChannel.DISCHARGE }
        val chargeCandidates = candidates.filter { it.channel == CapacityChannel.CHARGE }

        val dischargeEstimate = DischargeCapacityEstimator.estimateChannel(dischargeCandidates)
        val chargeEstimate = ChargeCapacityEstimator.estimateChannel(chargeCandidates)

        val disFcc = dischargeEstimate.fccQMah
        val disUncertainty = dischargeEstimate.uncertaintyMah ?: 300.0

        val chgFcc = chargeEstimate.fccQMah
        val chgUncertainty = chargeEstimate.uncertaintyMah ?: 400.0

        // 统一提取历史可信基准
        val effectiveHistoryFcc = acceptedFccQ?.fccMah ?: previousAcceptedFccQMah
        val effectiveHistoryUncertainty = acceptedFccQ?.uncertaintyMah ?: disUncertainty

        // 检查充电辅助通道是否完成端点和库仑效率标定
        val isChargeCalibrated = chargeCandidates.any {
            !it.isExcluded && (it.endpointQuality == FullEndpointQuality.EXCELLENT || it.endpointQuality == FullEndpointQuality.GOOD)
        }

        var conflictPercent: Double? = null
        var isDisagreement = false
        var isFusionFrozen = false
        var freezeReason: String? = null

        val (fusedFcc, fusedUncertainty) = when {
            // 两通道均有估计值：严格执行分歧仲裁检验
            disFcc != null && chgFcc != null -> {
                val mean = (disFcc + chgFcc) / 2.0
                val diff = abs(disFcc - chgFcc)
                val cPercent = if (mean > 0.0) (diff / mean) * 100.0 else 0.0
                conflictPercent = cPercent

                val combinedSigma = sqrt(disUncertainty * disUncertainty + chgUncertainty * chgUncertainty)
                val isSignificantDivergence = (cPercent > CHANNEL_CONFLICT_THRESHOLD_PERCENT && diff > 2.0 * combinedSigma)

                if (isSignificantDivergence) {
                    // 发生严重物理分歧（例如相对差异 > 10% 且超出 2-sigma）：
                    // 双方差异超出各自测量不确定度解释范围，放电通道本身亦不可作为绝对真值
                    isDisagreement = true
                    isFusionFrozen = true
                    freezeReason = String.format(
                        Locale.US,
                        "充放电两通道相对差异%.2f%%超过10%%容差门限，判定为DISAGREEMENT分歧，已冻结自动融合。保留上次确认可信值，放电与充电候选值独立展示",
                        cPercent
                    )
                    // 绝不冒充将放电通道当作绝对真值：保留上次确认可信值，无历史则为 null
                    if (effectiveHistoryFcc != null && effectiveHistoryFcc > 0.0) {
                        Pair(effectiveHistoryFcc, effectiveHistoryUncertainty)
                    } else {
                        Pair(null, null)
                    }
                } else if (!isChargeCalibrated) {
                    // 充电通道未完成标定，暂停双通道融合加权，以放电物理主通道为准
                    isFusionFrozen = true
                    freezeReason = String.format(
                        Locale.US,
                        "充放电两通道相对差异%.2f%%，且充电通道尚未完成满充端点与库仑效率标定，已冻结融合更新，以放电物理主估计为准",
                        cPercent
                    )
                    Pair(effectiveHistoryFcc ?: disFcc, disUncertainty)
                } else {
                    // 两通道高度自洽且均合格：执行逆方差加权融合
                    val wDis = 1.0 / (disUncertainty * disUncertainty).coerceAtLeast(1.0)
                    val wChg = 1.0 / (chgUncertainty * chgUncertainty).coerceAtLeast(1.0)
                    val fused = (disFcc * wDis + chgFcc * wChg) / (wDis + wChg)
                    val rawSigma = 1.0 / sqrt(wDis + wChg)
                    // 同源电量计避免方差过度收缩（不能低于单通道不确定度的 90%）
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
        val totalValidCount = dischargeEstimate.sampleCount + chargeEstimate.sampleCount
        val totalSocSpan = dischargeEstimate.totalSocSpan + chargeEstimate.totalSocSpan
        val rawMaturity = when {
            isDisagreement -> LearningMaturity.DISAGREEMENT
            fusedFcc == null -> LearningMaturity.PENDING
            totalValidCount >= 6 && totalSocSpan >= 2.0 -> LearningMaturity.STABLE
            totalValidCount >= 3 && totalSocSpan >= 0.80 -> LearningMaturity.CONVERGED
            totalValidCount >= 1 -> LearningMaturity.INITIAL
            else -> LearningMaturity.PENDING
        }

        // 规范约束：若缺少放电主估计通道验证，纯充电辅助通道估计成熟度最高限制为 INITIAL
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

        // 能量通道独立评估快照
        val energyResult = energyLearner.getEnergyCapacityResult()
        val (fullEWh, uncertaintyEWh) = energyLearner.estimateFullEnergyWh(fusedFcc)
        val energyCoverage = energyLearner.getCoverageRatio()

        // SOH_Q 计算（以系统可用边界 SYSTEM_USABLE 输出）
        val sohQ = if (fusedFcc != null && designCapacityMah != null && designCapacityMah > 0.0) {
            (fusedFcc / designCapacityMah) * 100.0
        } else null

        val designEnergyWh = if (designCapacityMah != null && designCapacityMah > 0.0) {
            (designCapacityMah * designVoltageVolts) / 1000.0
        } else null

        // 遵循技术评审 P2-B3 与 P2-C 铁律：
        // SOH_E（能量健康度）必须基于经充分验证的标准工况归一化能量（normalizedFullUsableEnergyWh），
        // 严禁拿未经工况补偿的非标准直接实测能量计算，更严禁拿 SOH_Q 偷梁换柱！
        // 在能量归一化模型未达到 VALIDATED 高置信度验证前，SOH_E 严格保持为 null 冻结状态。
        val normalizedFullEWh: Double? = null
        val sohE = if (normalizedFullEWh != null && designEnergyWh != null && designEnergyWh > 0.0) {
            (normalizedFullEWh / designEnergyWh) * 100.0
        } else null

        // 剩余电量与能量
        val safeSoc = currentSocPercent?.coerceIn(0, 100)
        val remainingQ = if (fusedFcc != null && safeSoc != null) {
            fusedFcc * (safeSoc / 100.0)
        } else null

        val remainingE = if (safeSoc != null) {
            energyLearner.estimateRemainingEnergyWh(safeSoc, fullEWh)
        } else null

        // P2-B 能量容量三态模型构建（在模型验证完成前归一化与基准能量如实为 null，绝不虚构）
        val latestCondition = dischargeCandidates.lastOrNull()?.operatingCondition
        val triad = EnergyCapacityTriad(
            observedEnergyWh = energyResult.observedEnergyWh,
            measuredEnergyWh = fullEWh,
            normalizedEnergyWh = null,
            referenceEnergyWh = null,
            normalizationModelConfidence = null,
            conditionProfile = latestCondition
        )

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
            algorithmVersion = "FCC-v1.1",
            updatedTimestampMs = System.currentTimeMillis(),
            energyCapacityResult = energyResult,
            isFusionFrozen = isFusionFrozen,
            fusionFreezeReason = freezeReason,
            previousAcceptedFccQMah = effectiveHistoryFcc,
            acceptedFccQ = acceptedFccQ,
            energyTriad = triad
        )
    }

    /**
     * 计算一组数值的中位数。
     *
     * @param values 浮点数列表
     * @return 中位数值，列表为空时返回 null
     */
    fun calculateMedian(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val s = sorted.size
        return if (s % 2 == 1) sorted[s / 2] else (sorted[s / 2 - 1] + sorted[s / 2]) / 2.0
    }
}
