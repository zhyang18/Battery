package com.battery.analysis.domain.estimator

import com.battery.analysis.model.CandidateSessionType
import com.battery.analysis.model.CapacityCandidate
import com.battery.analysis.model.MaturityLevel
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 稳健满充容量（FCC）融合估计结果聚合实体。
 *
 * @property estimatedFccMah 加权稳健估计出的电芯真实满充容量（单位：mAh，无样本时为 null）
 * @property sampleStdDevMah 样本加权离散标准差（单位：mAh）
 * @property dispersionPercent 变异系数 CV（百分比，越小表示样本自洽度越高）
 * @property maturityLevel 当前学习成熟度等级 [MaturityLevel]
 * @property validCandidateCount 参与最终加权计算的有效样本数
 * @property chargeCandidateCount 参与的有效充电样本数
 * @property dischargeCandidateCount 参与的有效放电样本数
 * @property totalSocSpanPercent 累计覆盖的电量变化跨度百分比总和
 * @property chargeDischargeConflictPercent 充放电跨方向相对冲突差异率 D（百分比，若仅单方向数据则为 null）
 * @property confidenceIntervalMah 基于样本方差计算的 95% 置信区间半径（单位：mAh）
 */
data class RobustFccResult(
    val estimatedFccMah: Double?,
    val sampleStdDevMah: Double?,
    val dispersionPercent: Double?,
    val maturityLevel: MaturityLevel,
    val validCandidateCount: Int,
    val chargeCandidateCount: Int,
    val dischargeCandidateCount: Int,
    val totalSocSpanPercent: Int,
    val chargeDischargeConflictPercent: Double?,
    val confidenceIntervalMah: Double?
)

/**
 * 工业级稳健满充容量（FCC）融合估计纯领域引擎（RobustFccEstimator）。
 *
 * 核心特性：
 * 1. 采用计量学一阶泰勒展开误差传递模型计算每个候选样本的理论不确定度方差；
 * 2. 结合中位数绝对偏差（MAD）执行稳健离群点过滤，消除接触不良与系统异常跳变造成的脏数据污染；
 * 3. 执行加权最小二乘回归（WLS），对充放电双向候选集进行科学融合；
 * 4. 计算充放电跨方向相对冲突率 D，评估传感器比例漂移风险；
 * 5. 严格恪守零假数据原则：在无样本或样本严重不足时如实返回 null 或 PENDING，绝不伪造虚假的固定数字。
 */
object RobustFccEstimator {

    /** 两个独立采样点整数 SOC 均匀截断误差合并方差常数: 2 * (0.01^2 / 12) */
    private const val VARIANCE_DELTA_Z: Double = 1.6666666666666667e-5

    /** 传感器电流安时微积分基准相对不确定度（保守取 2.0%） */
    private const val CURRENT_INTEGRAL_RELATIVE_UNCERTAINTY: Double = 0.02

    /**
     * 计算单次候选会话的一阶泰勒展开方差（误差传递不确定度）。
     *
     * 公式推导：
     * \sigma_{Q_i}^2 \approx \frac{\sigma_{\Delta Q_i}^2}{(\Delta z_i)^2} + \frac{(\Delta Q_i)^2 \cdot \sigma_{\Delta z_i}^2}{(\Delta z_i)^4}
     *
     * @param netChargeMah 电池端净电荷变化量（单位：mAh）
     * @param normalizedSocSpan 归一化电量变化跨度 Δz（0.0 至 1.0）
     * @return 该样本的一阶传递方差（单位：mAh^2）
     */
    fun calculateSampleVariance(netChargeMah: Double, normalizedSocSpan: Double): Double {
        val dz = normalizedSocSpan.coerceIn(0.01, 1.0)
        val dq = abs(netChargeMah).coerceAtLeast(1.0)

        // 1. 电荷积分项带来的方差贡献: (1 / dz^2) * (sigma_dq^2)
        val sigmaDq = CURRENT_INTEGRAL_RELATIVE_UNCERTAINTY * dq
        val term1 = (sigmaDq / dz).pow(2.0)

        // 2. SOC 整数截断误差带来的方差贡献: (dq^2 / dz^4) * sigma_dz^2
        val term2 = (dq.pow(2.0) / dz.pow(4.0)) * VARIANCE_DELTA_Z

        return term1 + term2
    }

    /**
     * 使用中位数绝对偏差（MAD）算法识别并过滤候选样本集中的离群突变脏数据。
     *
     * @param candidates 原始候选样本列表
     * @return 过滤并剔除离群样本后的干净样本列表
     */
    fun filterOutliersByMad(candidates: List<CapacityCandidate>): List<CapacityCandidate> {
        if (candidates.size < 3) {
            return candidates.filter { !it.isExcluded && it.fccObservedMah > 0.0 }
        }

        val validList = candidates.filter { !it.isExcluded && it.fccObservedMah > 0.0 }
        if (validList.size < 3) {
            return validList
        }

        // 1. 计算样本中位数 M
        val sortedFcc = validList.map { it.fccObservedMah }.sorted()
        val median = calculateMedian(sortedFcc)

        // 2. 计算绝对中位差 MAD = median(|x_i - M|)
        val deviations = validList.map { abs(it.fccObservedMah - median) }.sorted()
        val mad = calculateMedian(deviations)

        // 若全部样本极其一致（MAD 趋近 0），直接返回全部有效样本
        if (mad < 1e-4) {
            return validList
        }

        // 3. 采用正态分布等效尺度门限: 3 * 1.4826 * MAD
        val threshold = 3.0 * 1.4826 * mad

        return validList.filter { candidate ->
            val dev = abs(candidate.fccObservedMah - median)
            dev <= threshold
        }
    }

    /**
     * 对候选样本集执行多样本稳健融合估计。
     *
     * @param rawCandidates 历史累积的所有充放电容量候选样本列表
     * @return 融合估计结果实体 [RobustFccResult]
     */
    fun estimateFusedFcc(rawCandidates: List<CapacityCandidate>): RobustFccResult {
        if (rawCandidates.isEmpty()) {
            return RobustFccResult(
                estimatedFccMah = null,
                sampleStdDevMah = null,
                dispersionPercent = null,
                maturityLevel = MaturityLevel.PENDING,
                validCandidateCount = 0,
                chargeCandidateCount = 0,
                dischargeCandidateCount = 0,
                totalSocSpanPercent = 0,
                chargeDischargeConflictPercent = null,
                confidenceIntervalMah = null
            )
        }

        // 执行 MAD 稳健离群过滤
        val cleanCandidates = filterOutliersByMad(rawCandidates)
        if (cleanCandidates.isEmpty()) {
            return RobustFccResult(
                estimatedFccMah = null,
                sampleStdDevMah = null,
                dispersionPercent = null,
                maturityLevel = MaturityLevel.PENDING,
                validCandidateCount = 0,
                chargeCandidateCount = 0,
                dischargeCandidateCount = 0,
                totalSocSpanPercent = 0,
                chargeDischargeConflictPercent = null,
                confidenceIntervalMah = null
            )
        }

        val chargeList = cleanCandidates.filter { it.sessionType == CandidateSessionType.CHARGE }
        val dischargeList = cleanCandidates.filter { it.sessionType == CandidateSessionType.DISCHARGE }

        var totalWeight = 0.0
        var weightedSumFcc = 0.0
        val weights = mutableListOf<Double>()

        for (candidate in cleanCandidates) {
            val variance = calculateSampleVariance(candidate.netBatteryChargeMah, candidate.getNormalizedSocSpan())
            // 权重与方差成反比，同时乘以此会话的综合质检分
            val weight = (1.0 / variance.coerceAtLeast(1e-6)) * candidate.qualityScore.coerceIn(0.1, 1.0)
            weights.add(weight)
            totalWeight += weight
            weightedSumFcc += weight * candidate.fccObservedMah
        }

        if (totalWeight <= 0.0) {
            return RobustFccResult(
                estimatedFccMah = null,
                sampleStdDevMah = null,
                dispersionPercent = null,
                maturityLevel = MaturityLevel.PENDING,
                validCandidateCount = 0,
                chargeCandidateCount = 0,
                dischargeCandidateCount = 0,
                totalSocSpanPercent = 0,
                chargeDischargeConflictPercent = null,
                confidenceIntervalMah = null
            )
        }

        val finalFcc = weightedSumFcc / totalWeight

        // 计算加权样本标准差
        var weightedVarianceSum = 0.0
        for (i in cleanCandidates.indices) {
            val diff = cleanCandidates[i].fccObservedMah - finalFcc
            weightedVarianceSum += weights[i] * diff * diff
        }
        val weightedStdDev = sqrt(weightedVarianceSum / totalWeight)
        val cvPercent = if (finalFcc > 0.0) (weightedStdDev / finalFcc) * 100.0 else 0.0

        val totalSocSpan = cleanCandidates.sumOf { it.socSpanPercent }

        // 计算充放电跨方向相对冲突差异率 D
        var conflictRateD: Double? = null
        if (chargeList.isNotEmpty() && dischargeList.isNotEmpty()) {
            val chargeFcc = calculateWeightedMean(chargeList)
            val dischargeFcc = calculateWeightedMean(dischargeList)
            val meanFcc = (chargeFcc + dischargeFcc) / 2.0
            if (meanFcc > 0.0) {
                conflictRateD = (abs(chargeFcc - dischargeFcc) / meanFcc) * 100.0
            }
        }

        // 评估学习成熟度等级
        val count = cleanCandidates.size
        val maturity = when {
            count >= 6 && totalSocSpan >= 200 && cvPercent < 3.0 && (conflictRateD == null || conflictRateD < 8.0) -> MaturityLevel.STABLE_TRACKING
            count >= 3 && totalSocSpan >= 80 && cvPercent < 5.0 -> MaturityLevel.CONVERGED
            count >= 1 -> MaturityLevel.INITIAL_OBSERVATION
            else -> MaturityLevel.PENDING
        }

        // 计算 95% 置信区间半径 (t分布近似或 1.96 * stdDev / sqrt(N)，单样本时无统计离散依据故返回 null)
        val confidenceRadius = if (count > 1) {
            (1.96 * weightedStdDev) / sqrt(count.toDouble())
        } else {
            null
        }

        return RobustFccResult(
            estimatedFccMah = finalFcc,
            sampleStdDevMah = weightedStdDev,
            dispersionPercent = cvPercent,
            maturityLevel = maturity,
            validCandidateCount = count,
            chargeCandidateCount = chargeList.size,
            dischargeCandidateCount = dischargeList.size,
            totalSocSpanPercent = totalSocSpan,
            chargeDischargeConflictPercent = conflictRateD,
            confidenceIntervalMah = confidenceRadius
        )
    }

    /**
     * 计算一组样本的加权均值。
     *
     * @param candidates 候选样本列表
     * @return 加权平均后的满充容量（mAh）
     */
    private fun calculateWeightedMean(candidates: List<CapacityCandidate>): Double {
        if (candidates.isEmpty()) return 0.0
        var sumW = 0.0
        var sumWVal = 0.0
        for (c in candidates) {
            val v = calculateSampleVariance(c.netBatteryChargeMah, c.getNormalizedSocSpan())
            val w = 1.0 / v.coerceAtLeast(1e-6)
            sumW += w
            sumWVal += w * c.fccObservedMah
        }
        return if (sumW > 0.0) sumWVal / sumW else 0.0
    }

    /**
     * 计算有序浮点数列表的中位数。
     *
     * @param sortedList 已升序排列的浮点数列表
     * @return 中位数值
     */
    private fun calculateMedian(sortedList: List<Double>): Double {
        if (sortedList.isEmpty()) return 0.0
        val size = sortedList.size
        return if (size % 2 == 1) {
            sortedList[size / 2]
        } else {
            (sortedList[size / 2 - 1] + sortedList[size / 2]) / 2.0
        }
    }
}
