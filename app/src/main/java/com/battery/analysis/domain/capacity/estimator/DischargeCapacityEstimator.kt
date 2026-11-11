package com.battery.analysis.domain.capacity.estimator

import com.battery.analysis.domain.capacity.integration.CoulombIntegrator
import com.battery.analysis.domain.capacity.model.CapacityChannel
import com.battery.analysis.domain.capacity.model.CapacitySample
import com.battery.analysis.domain.capacity.model.Confidence
import com.battery.analysis.domain.capacity.model.DischargeQuality
import com.battery.analysis.domain.capacity.model.FccCandidateSample
import com.battery.analysis.domain.capacity.model.FccChannelEstimate
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 放电容量主估计通道（DischargeCapacityEstimator）。
 *
 * 遵循技术方案与第二阶段（P1-A / P1-B）评审规范：
 * 1. 物理测量优先：直接观测电池向系统释放的电荷量，最贴近“用户实际可用容量”；
 * 2. 100% 顶部平台期（Top Plateau）解耦：平台期放电量独立累计，严禁计入有效放电 FCC 计算分子；
 * 3. 梯形安时积分 Q_valid 与严格同步对齐的可靠 SOC 锚点变化 Δz（从首次低于 100% 的 t_effective 起算）；
 * 4. P1-B 多维会话质检与断层-SOC 绑定：有效时间覆盖率 >= 80% 且严禁采样断层跨越未观测 SOC（未观测丢失 <= 1.5%），杜绝覆盖率 80% 造成 FCC 严重低估；
 * 5. 量纲解耦：FCC_Q 与 FCC_E 独立质检门控，电压缺失仅排除能量通道，不影响安时容量评估；
 * 6. P1-B CHARGE_COUNTER 硬件交叉检验：起点严格对齐首次 99% 的历史读数，独立印证，不强行覆盖微积分结果；
 * 7. 一阶泰勒展开误差传递方差评估。
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
     * 公式：\sigma_{res}^2 \approx (\sigma_Q / \Delta z)^2 + (Q^2 / \Delta z^4) * \sigma_{\Delta z}^2
     *
     * @param chargeMah 有效区间测得的物理电荷量（mAh）
     * @param deltaSoc 有效区间对应的 SOC 变化比例（0.0 ~ 1.0）
     * @return 方差估计值（mAh^2）
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
     * 从一组放电采样点中评估并构建单次放电候选样本（集成顶部平台解耦与 P1-B 细粒度质检、断层-SOC绑定、Q/E解耦与 Counter 交叉验证）。
     *
     * @param sessionId 唯一会话 ID
     * @param samples 连续采样序列（放电电流 I_discharge > 0）
     * @param startSoc 原始起始 SOC（0.0 ~ 1.0）
     * @param endSoc 原始结束 SOC（0.0 ~ 1.0）
     * @param isDualCell 是否串联双电芯
     * @param authoritativeDischargedMah 来自主账本双重物理锚定核算出的权威真实放电量（单位：mAh，用于防止长待机采样漂移）
     * @param authoritativeEnergyWh 来自主账本双重物理锚定核算出的权威真实放电能量（单位：Wh）
     * @return 候选样本实体 [FccCandidateSample]
     */
    fun buildCandidate(
        sessionId: String,
        samples: List<CapacitySample>,
        startSoc: Double,
        endSoc: Double,
        isDualCell: Boolean = false,
        authoritativeDischargedMah: Double? = null,
        authoritativeEnergyWh: Double? = null
    ): FccCandidateSample {
        // 1. 执行 100% 顶部平台解耦与状态机切分
        val decoupled = DischargeSessionDecoupler.decouple(samples, startSoc, endSoc)

        // 若整段会话无有效放电区间（如全程处于 100% 满电平台，或有效采样点不足）
        if (decoupled.effectiveSamples.size < 2 || decoupled.effectiveDeltaSoc <= 0.0) {
            return FccCandidateSample(
                sessionId = sessionId,
                channel = CapacityChannel.DISCHARGE,
                startSoc = decoupled.effectiveStartSoc,
                endSoc = decoupled.effectiveEndSoc,
                deltaSoc = decoupled.effectiveDeltaSoc,
                chargeMah = 0.0,
                energyWh = 0.0,
                fccObservedMah = 0.0,
                varianceMah2 = Double.MAX_VALUE,
                avgVoltageVolts = null,
                avgTemperatureC = null,
                maxTemperatureC = samples.mapNotNull { it.temperatureC }.maxOrNull(),
                durationSec = decoupled.topPlateau.durationSeconds.toDouble(),
                coverageRatio = 1.0,
                isExcluded = true,
                exclusionReason = decoupled.anomalyReason ?: "放电会话无有效放电采样区间",
                topPlateau = decoupled.topPlateau,
                qualityLevel = DischargeQuality.REJECTED,
                isEnergyExcluded = true,
                energyExclusionReason = "无有效放电采样区间",
                fccEQuality = DischargeQuality.REJECTED
            )
        }

        // 2. 仅对有效放电区间执行梯形安时积分与能量积分（严禁计入 100% 满电平台电量）
        val segment = CoulombIntegrator.integrate(decoupled.effectiveSamples)

        // 物理主账本锚定与长待机采样漂移去噪校准：
        // 若提供了主账本权威核算的真实物理放电量与能量（经过亮屏微积分与待机底噪去噪严格闭环），
        // 优先采用权威物理值扣除 100% 顶部平台与过渡区后的有效区间电量，
        // 坚决杜绝息屏深度休眠期间唤醒大电流被过宽 dt 盲目累加导致的虚假膨胀（Standby Current Inflation）
        val (qMah, eWh) = if (authoritativeDischargedMah != null && authoritativeDischargedMah > 0.0) {
            val qEffective = kotlin.math.max(0.0, authoritativeDischargedMah - decoupled.topPlateau.plateauMah - decoupled.topPlateau.transitionMah)
            val eEffective = if (authoritativeEnergyWh != null && authoritativeEnergyWh > 0.0) {
                kotlin.math.max(0.0, authoritativeEnergyWh - decoupled.topPlateau.plateauWh - decoupled.topPlateau.transitionWh)
            } else {
                segment.energyWh
            }
            Pair(qEffective, eEffective)
        } else {
            Pair(segment.chargeMah, segment.energyWh)
        }

        val dz = decoupled.effectiveDeltaSoc.coerceIn(0.001, 1.0)
        val fccObserved = if (decoupled.effectiveDeltaSoc > 0.0) qMah / dz else 0.0
        val variance = calculateSampleVariance(qMah, dz)

        // 3. P1-B 多维细粒度质检评估（包含断层未观测 SOC 丢失检查、Q/E 独立评估）
        val qualityResult = DischargeSessionQualityEvaluator.evaluate(
            allSamples = samples,
            effectiveSamples = decoupled.effectiveSamples,
            effectiveDeltaSoc = decoupled.effectiveDeltaSoc,
            plateauMetrics = decoupled.topPlateau,
            anomalyReason = decoupled.anomalyReason
        )

        // 4. P1-B 电量计 CHARGE_COUNTER 交叉一致性检验（起点与首次 99% 的历史读数严格对齐）
        val counterResult = DischargeCounterConsistencyChecker.check(
            effectiveSamples = decoupled.effectiveSamples,
            qIntegralMah = qMah
        )

        // 5. P2-B 热环境与负载工况全景特征提取
        val conditionProfile = DischargeConditionAnalyzer.analyze(samples)

        val maxTemp = decoupled.effectiveSamples.mapNotNull { it.temperatureC }.maxOrNull()

        // 物理合理性基础检验
        val isRangeDefective = fccObserved < 500.0 || fccObserved > 25000.0
        val isPhysicallyInconsistent = !CoulombIntegrator.isPhysicalConsistencyValid(qMah, eWh, isDualCell)

        val finalQRejections = mutableSetOf<String>().apply {
            addAll(qualityResult.dualAssessment.fccQRejectionReasons)
            if (isRangeDefective) add("推算容量超出物理合理范围")
            if (isPhysicallyInconsistent) add("电荷与能量自洽性异常")
        }

        val finalERejections = mutableSetOf<String>().apply {
            addAll(qualityResult.dualAssessment.fccERejectionReasons)
            if (isRangeDefective) add("推算容量超出物理合理范围")
            if (isPhysicallyInconsistent) add("电荷与能量自洽性异常")
        }

        val isExcluded = finalQRejections.isNotEmpty()
        val exclusionReason = finalQRejections.joinToString("; ")

        val isEnergyExcluded = finalERejections.isNotEmpty()
        val energyExclusionReason = finalERejections.joinToString("; ")

        return FccCandidateSample(
            sessionId = sessionId,
            channel = CapacityChannel.DISCHARGE,
            startSoc = decoupled.effectiveStartSoc,
            endSoc = decoupled.effectiveEndSoc,
            deltaSoc = decoupled.effectiveDeltaSoc,
            chargeMah = qMah,
            energyWh = eWh,
            fccObservedMah = fccObserved,
            varianceMah2 = variance,
            avgVoltageVolts = segment.avgVoltageVolts,
            avgTemperatureC = segment.avgTemperatureC,
            maxTemperatureC = maxTemp,
            durationSec = segment.validDurationSec,
            coverageRatio = qualityResult.effectiveIntervalCoverage,
            isExcluded = isExcluded,
            exclusionReason = exclusionReason,
            topPlateau = decoupled.topPlateau,
            qualityLevel = qualityResult.dualAssessment.fccQQuality,
            counterConsistency = counterResult.consistency,
            counterQuality = counterResult.counterQuality,
            effectiveIntervalCoverage = qualityResult.effectiveIntervalCoverage,
            topPlateauCoverage = qualityResult.topPlateauCoverage,
            wholeSessionCoverage = qualityResult.wholeSessionCoverage,
            isEnergyExcluded = isEnergyExcluded,
            energyExclusionReason = energyExclusionReason,
            fccEQuality = qualityResult.dualAssessment.fccEQuality,
            operatingCondition = conditionProfile
        )
    }

    /**
     * 针对放电候选样本集执行独立通道加权最小二乘估计。
     *
     * @param candidates 放电候选样本列表
     * @return 放电通道独立估计结果 [FccChannelEstimate]
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
