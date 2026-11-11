package com.battery.analysis.domain.capacity.estimator

import com.battery.analysis.domain.capacity.model.AcceptedFccQ
import com.battery.analysis.domain.capacity.model.AgingTrendStatus
import com.battery.analysis.domain.capacity.model.BinLearningStatus
import com.battery.analysis.domain.capacity.model.CommonSocConditionComparison
import com.battery.analysis.domain.capacity.model.CommonSocCoverage
import com.battery.analysis.domain.capacity.model.DischargeConditionSample
import com.battery.analysis.domain.capacity.model.SocOverlapStatus
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 放电工况比较与共同 SOC 分箱能量比对器（ConditionComparisonMatcher）。
 *
 * 遵循技术评审 P2-B2.1 物理铁律：
 * 1. 3×3 工况矩阵严禁直接比较不同起点、不同终点、不同区间的整会话总能量（Wh）；
 * 2. 敏感度分析必须比较共同实测 SOC 分箱（Common Measured Bins），即双方都达到 MEASURED 状态的分箱；
 * 3. 坚决排除 MODEL_ESTIMATED 估算分箱、UNRESOLVED 停滞能量、100% 顶部平台与过渡能量；
 * 4. 共同实测覆盖跨度不足（< 20%）时坚决返回 INSUFFICIENT_SOC_OVERLAP，禁止输出虚假敏感度；
 * 5. 严格区分同一电池/设备的重复测量（Repeated Measures）与跨设备独立验证；
 * 6. 时间序列前向验证：训练集时间戳必须严格早于验证集，杜绝将未来数据泄露到过去模型。
 */
object ConditionComparisonMatcher {

    /** 默认判定共同 SOC 覆盖充分的最小百分比跨度门限（20.0%，即至少 4 个 5% 分箱） */
    const val DEFAULT_MIN_COMMON_SOC_SPAN_PERCENT: Double = 20.0

    /**
     * 计算共同实测 SOC 分箱覆盖的连续性与区间分段度量。
     *
     * 遵循技术评审 P2-B3 核心准则：检验分箱在物理 SOC 轴上的连续性，
     * 识别并记录连续分段列表，杜绝将离散孤岛分箱（如 10%~15% 与 80%~85%）误判为连续放电区间。
     *
     * @param bins 共同实测分箱索引集合
     * @return 共同 SOC 分箱覆盖度量实体 [CommonSocCoverage]
     */
    fun calculateCommonSocCoverage(bins: Set<Int>): CommonSocCoverage {
        if (bins.isEmpty()) {
            return CommonSocCoverage(
                commonBinIndices = emptySet(),
                commonBinCount = 0,
                totalCommonCoveragePercent = 0.0,
                maxContiguousCoveragePercent = 0.0,
                contiguousSegments = emptyList(),
                isContiguous = false
            )
        }

        val sorted = bins.sorted()
        val segments = mutableListOf<ClosedRange<Int>>()
        var segStart = sorted[0]
        var segEnd = sorted[0]

        for (i in 1 until sorted.size) {
            val current = sorted[i]
            if (current == segEnd + 1) {
                segEnd = current
            } else {
                segments.add(segStart..segEnd)
                segStart = current
                segEnd = current
            }
        }
        segments.add(segStart..segEnd)

        val maxSegLength = segments.maxOf { it.endInclusive - it.start + 1 }
        val maxContiguousCoverage = maxSegLength * 5.0
        val totalCoverage = sorted.size * 5.0
        val isContiguous = segments.size == 1

        return CommonSocCoverage(
            commonBinIndices = bins,
            commonBinCount = bins.size,
            totalCommonCoveragePercent = totalCoverage,
            maxContiguousCoveragePercent = maxContiguousCoverage,
            contiguousSegments = segments,
            isContiguous = isContiguous
        )
    }

    /**
     * 对两个放电工况样本在共同实测 SOC 分箱区间进行严谨物理能量比对。
     *
     * @param sampleA 会话 A 样本实体
     * @param sampleB 会话 B 样本实体
     * @param minCommonSpanPercent 允许能量比较的最小共同 SOC 覆盖跨度百分比
     * @return 共同 SOC 分箱能量比对结果实体 [CommonSocConditionComparison]
     */
    fun compareCommonSocBins(
        sampleA: DischargeConditionSample,
        sampleB: DischargeConditionSample,
        minCommonSpanPercent: Double = DEFAULT_MIN_COMMON_SOC_SPAN_PERCENT
    ): CommonSocConditionComparison {
        val binsA = sampleA.binMeasuredEnergiesWh
        val binsB = sampleB.binMeasuredEnergiesWh
        val statusA = sampleA.binLearningStatuses
        val statusB = sampleB.binLearningStatuses

        // 仅筛选双方在该分箱都具备纯实测数据，且学习状态均为 MEASURED 的共同分箱
        // 彻底杜绝 MODEL_ESTIMATED、PARTIAL 或 UNLEARNED 混入比较
        val commonBins = (0..19).filter { binIdx ->
            val hasMeasuredEnergyA = (binsA[binIdx] ?: 0.0) > 0.0
            val hasMeasuredEnergyB = (binsB[binIdx] ?: 0.0) > 0.0
            val isMeasuredA = statusA[binIdx] == BinLearningStatus.MEASURED || (statusA.isEmpty() && hasMeasuredEnergyA)
            val isMeasuredB = statusB[binIdx] == BinLearningStatus.MEASURED || (statusB.isEmpty() && hasMeasuredEnergyB)
            hasMeasuredEnergyA && hasMeasuredEnergyB && isMeasuredA && isMeasuredB
        }.sorted()

        val coverage = calculateCommonSocCoverage(commonBins.toSet())
        val commonSpanPercent = coverage.totalCommonCoveragePercent

        if (commonBins.isEmpty()) {
            return CommonSocConditionComparison(
                sessionAId = sampleA.sessionId,
                sessionBId = sampleB.sessionId,
                commonMeasuredBinIndices = emptyList(),
                commonSocCoveragePercent = 0.0,
                commonMeasuredEnergyWhA = 0.0,
                commonMeasuredEnergyWhB = 0.0,
                energyDeltaWh = 0.0,
                energyDeltaPercent = 0.0,
                status = SocOverlapStatus.NO_OVERLAP,
                comparisonDetails = "无共同 MEASURED 充分实测 SOC 分箱，无法进行物理做功比对",
                commonCoverage = coverage
            )
        }

        if (commonSpanPercent < minCommonSpanPercent) {
            return CommonSocConditionComparison(
                sessionAId = sampleA.sessionId,
                sessionBId = sampleB.sessionId,
                commonMeasuredBinIndices = commonBins,
                commonSocCoveragePercent = commonSpanPercent,
                commonMeasuredEnergyWhA = 0.0,
                commonMeasuredEnergyWhB = 0.0,
                energyDeltaWh = 0.0,
                energyDeltaPercent = 0.0,
                status = SocOverlapStatus.INSUFFICIENT_SOC_OVERLAP,
                comparisonDetails = "INSUFFICIENT_SOC_OVERLAP: 共同实测跨度仅 ${commonSpanPercent}%，低于 ${minCommonSpanPercent}% 门限，严禁直接比较整条会话能量",
                commonCoverage = coverage
            )
        }

        // 计算双方在共同实测分箱区间的纯实测物理做功总和：E_common = \sum_{k \in B_common} E_k
        val energySumA = commonBins.sumOf { binsA[it] ?: 0.0 }
        val energySumB = commonBins.sumOf { binsB[it] ?: 0.0 }
        val deltaWh = energySumB - energySumA
        val deltaPercent = if (energySumA > 0.0) (deltaWh / energySumA) * 100.0 else 0.0

        return CommonSocConditionComparison(
            sessionAId = sampleA.sessionId,
            sessionBId = sampleB.sessionId,
            commonMeasuredBinIndices = commonBins,
            commonSocCoveragePercent = commonSpanPercent,
            commonMeasuredEnergyWhA = energySumA,
            commonMeasuredEnergyWhB = energySumB,
            energyDeltaWh = deltaWh,
            energyDeltaPercent = deltaPercent,
            status = SocOverlapStatus.SUFFICIENT,
            comparisonDetails = "成功完成共同实测分箱比较：覆盖跨度 ${commonSpanPercent}%（共 ${commonBins.size} 个分箱）",
            commonCoverage = coverage
        )
    }

    /**
     * 针对共同实测 SOC 分箱同步执行分箱工况特征对齐匹配（Per-Bin Condition Matching）。
     *
     * 遵循评审规范：
     * 杜绝仅凭全会话平均电流相似就误判局部区间工况相同（如会话 A 在 Bin 15 重载而在 Bin 14 轻载，会话 B 恰好相反）；
     * 必须保证共同比较的每个分箱的局部温度与局部负载均符合受控匹配要求。
     *
     * @param sampleA 会话 A 样本实体
     * @param sampleB 会话 B 样本实体
     * @param minCommonSpanPercent 最小共同 SOC 覆盖跨度百分比
     * @param maxTempDiffC 允许的分箱最大温度差异（℃，默认 5.0℃）
     * @param maxCurrentDiffRatio 允许的分箱最大电流差异相对比例（默认 0.35）
     * @return 共同 SOC 分箱比对结果 [CommonSocConditionComparison]
     */
    fun compareCommonSocBinsWithConditionMatching(
        sampleA: DischargeConditionSample,
        sampleB: DischargeConditionSample,
        minCommonSpanPercent: Double = DEFAULT_MIN_COMMON_SOC_SPAN_PERCENT,
        maxTempDiffC: Double = 5.0,
        maxCurrentDiffRatio: Double = 0.35
    ): CommonSocConditionComparison {
        val baseComp = compareCommonSocBins(sampleA, sampleB, minCommonSpanPercent)
        if (baseComp.status != SocOverlapStatus.SUFFICIENT) {
            return baseComp
        }

        val condA = sampleA.binConditions
        val condB = sampleB.binConditions

        // 进一步筛选工况特征匹配的分箱
        val conditionMatchedBins = baseComp.commonMeasuredBinIndices.filter { binIdx ->
            val cA = condA[binIdx]
            val cB = condB[binIdx]
            if (cA == null || cB == null) {
                // 若分箱工况缺失，则遵循保守原则不作为受控对照分箱
                false
            } else {
                val tempA = cA.averageTemperatureC ?: cA.temperatureP50C
                val tempB = cB.averageTemperatureC ?: cB.temperatureP50C
                val curA = cA.averageCurrentMa ?: cA.currentP95Ma
                val curB = cB.averageCurrentMa ?: cB.currentP95Ma

                val tempMatches = if (tempA != null && tempB != null) abs(tempA - tempB) <= maxTempDiffC else true
                val curMatches = if (curA != null && curB != null && curA > 0.0) {
                    abs(curA - curB) / curA <= maxCurrentDiffRatio
                } else true

                tempMatches && curMatches
            }
        }

        val conditionCoverage = calculateCommonSocCoverage(conditionMatchedBins.toSet())
        val matchedSpanPercent = conditionCoverage.totalCommonCoveragePercent

        if (matchedSpanPercent < minCommonSpanPercent) {
            return CommonSocConditionComparison(
                sessionAId = sampleA.sessionId,
                sessionBId = sampleB.sessionId,
                commonMeasuredBinIndices = conditionMatchedBins,
                commonSocCoveragePercent = matchedSpanPercent,
                commonMeasuredEnergyWhA = 0.0,
                commonMeasuredEnergyWhB = 0.0,
                energyDeltaWh = 0.0,
                energyDeltaPercent = 0.0,
                status = SocOverlapStatus.INSUFFICIENT_SOC_OVERLAP,
                comparisonDetails = "INSUFFICIENT_SOC_OVERLAP: 共同分箱中工况严格匹配的跨度仅 ${matchedSpanPercent}%，低于 ${minCommonSpanPercent}% 门限（存在局部区间工况相反）",
                commonCoverage = conditionCoverage
            )
        }

        val energySumA = conditionMatchedBins.sumOf { sampleA.binMeasuredEnergiesWh[it] ?: 0.0 }
        val energySumB = conditionMatchedBins.sumOf { sampleB.binMeasuredEnergiesWh[it] ?: 0.0 }
        val deltaWh = energySumB - energySumA
        val deltaPercent = if (energySumA > 0.0) (deltaWh / energySumA) * 100.0 else 0.0

        return CommonSocConditionComparison(
            sessionAId = sampleA.sessionId,
            sessionBId = sampleB.sessionId,
            commonMeasuredBinIndices = conditionMatchedBins,
            commonSocCoveragePercent = matchedSpanPercent,
            commonMeasuredEnergyWhA = energySumA,
            commonMeasuredEnergyWhB = energySumB,
            energyDeltaWh = deltaWh,
            energyDeltaPercent = deltaPercent,
            status = SocOverlapStatus.SUFFICIENT,
            comparisonDetails = "成功完成分箱工况严格对齐比较：受控匹配跨度 ${matchedSpanPercent}%（共 ${conditionMatchedBins.size} 个分箱）",
            commonCoverage = conditionCoverage
        )
    }

    /**
     * 综合评估两次放电样本之间是否存在显著的老化时间趋势漂移（解耦固定天数硬编码）。
     *
     * 遵循评审原则：结合日历天数、循环次数与已采纳历史可信容量（AcceptedFccQ）方差，
     * 动态判定老化状态 [AgingTrendStatus]，防止把容量衰退误判为环境工况敏感度。
     *
     * @param sampleA 样本 A
     * @param sampleB 样本 B
     * @param acceptedHistory 历史可信容量列表
     * @param maxCalendarDaysTolerance 允许的日历漂移天数基础容差
     * @param maxCyclesTolerance 允许的循环次数漂移基础容差
     * @param isComparableHistory 历史容量估计条件是否可比
     * @return 老化趋势评估状态 [AgingTrendStatus]
     */
    fun evaluateAgingTrend(
        sampleA: DischargeConditionSample,
        sampleB: DischargeConditionSample,
        acceptedHistory: List<AcceptedFccQ> = emptyList(),
        maxCalendarDaysTolerance: Double = 30.0,
        maxCyclesTolerance: Int = 20,
        isComparableHistory: Boolean = true
    ): AgingTrendStatus {
        val idA = sampleA.identity
        val idB = sampleB.identity

        // 若具备历史 AcceptedFccQ，优先检验历史容量序列证据
        if (acceptedHistory.size >= 3) {
            val historyStatus = evaluateHistoryAgingTrend(acceptedHistory, isComparableHistory)
            if (historyStatus != AgingTrendStatus.AGING_STABLE) {
                return historyStatus
            }
        }

        // 若缺乏身份信息，返回数据不足
        if (idA == null || idB == null) {
            return if (acceptedHistory.size >= 3) {
                evaluateHistoryAgingTrend(acceptedHistory, isComparableHistory)
            } else {
                AgingTrendStatus.AGING_DATA_INSUFFICIENT
            }
        }

        var daysDiff: Double? = null
        if (idA.calendarAgeDays != null && idB.calendarAgeDays != null) {
            daysDiff = abs(idA.calendarAgeDays - idB.calendarAgeDays)
        } else if (idA.sessionStartTimestamp > 0L && idB.sessionStartTimestamp > 0L) {
            daysDiff = abs(idB.sessionStartTimestamp - idA.sessionStartTimestamp) / (1000.0 * 3600.0 * 24.0)
        }

        var cyclesDiff: Int? = null
        if (idA.cycleCount != null && idB.cycleCount != null) {
            cyclesDiff = abs(idA.cycleCount - idB.cycleCount)
        }

        val isDaysExceeded = daysDiff != null && daysDiff > maxCalendarDaysTolerance
        val isCyclesExceeded = cyclesDiff != null && cyclesDiff > maxCyclesTolerance

        return when {
            // 仅循环次数或日历大幅增加但无容量测量证据时，作为老化暴露怀疑，不直接判定永久老化
            isDaysExceeded || isCyclesExceeded -> AgingTrendStatus.AGING_SUSPECTED
            daysDiff == null && cyclesDiff == null && acceptedHistory.isEmpty() -> AgingTrendStatus.AGING_DATA_INSUFFICIENT
            daysDiff != null && daysDiff > maxCalendarDaysTolerance * 0.7 -> AgingTrendStatus.AGING_UNCERTAIN
            cyclesDiff != null && cyclesDiff > maxCyclesTolerance * 0.7 -> AgingTrendStatus.AGING_UNCERTAIN
            else -> AgingTrendStatus.AGING_STABLE
        }
    }

    /**
     * 计算两次可比 FCC 容量估计差值的合并不确定度标准差（mAh）。
     *
     * 遵循评审铁律：老化判断严禁只使用单次测量的标准差，必须结合两次估计的合成不确定度：
     * \sigma_{\Delta} = \sqrt{\sigma_{\text{old}}^2 + \sigma_{\text{new}}^2 - 2 Cov(\text{old}, \text{new})}
     *
     * @param sigmaOld 历史参考估计的不确定度标准差（mAh）
     * @param sigmaNew 当前最新估计的不确定度标准差（mAh）
     * @param covariance 两次估计之间的共同系统误差协方差（mAh^2，独立时为 0.0）
     * @return 合并不确定度标准差（mAh），若输入不合法则返回 null
     */
    fun calculateCombinedAgingUncertaintyMah(
        sigmaOld: Double?,
        sigmaNew: Double?,
        covariance: Double = 0.0
    ): Double? {
        if (sigmaOld == null || sigmaNew == null || sigmaOld < 0.0 || sigmaNew < 0.0) {
            return null
        }
        val varianceDelta = sigmaOld * sigmaOld + sigmaNew * sigmaNew - 2.0 * covariance
        return if (varianceDelta >= 0.0) sqrt(varianceDelta) else null
    }

    /**
     * 基于已采纳的历史可信容量序列与合并不确定度门限，独立评估长期老化趋势状态。
     *
     * 遵循技术评审 P2-B3 铁律：
     * 1. 单次容量下降不直接断定为老化，视为短期波动判定为 AGING_UNCERTAIN；
     * 2. 多次可比历史会话出现持续下降但尚未确定超出测量合并不确定度时，判定为 AGING_SUSPECTED；
     * 3. 只有当持续下降幅度显著超出估计合并不确定度（|ΔFCC| > 2 * \sigma_{\Delta}）且趋势持续单调稳定时，才确认 AGING_DETECTED；
     * 4. 历史样本不足 3 条时如实返回 AGING_DATA_INSUFFICIENT；
     * 5. 历史测量条件不可比时，坚决返回 AGING_UNCERTAIN，杜绝误判。
     *
     * @param acceptedHistory 历史可信容量列表 [AcceptedFccQ]
     * @param isComparableHistory 历史容量估计条件是否可比
     * @param commonSystematicCovariance 两次估计之间的共同系统误差协方差（mAh^2）
     * @return 老化趋势状态 [AgingTrendStatus]
     */
    fun evaluateHistoryAgingTrend(
        acceptedHistory: List<AcceptedFccQ>,
        isComparableHistory: Boolean = true,
        commonSystematicCovariance: Double = 0.0
    ): AgingTrendStatus {
        if (acceptedHistory.size < 3) return AgingTrendStatus.AGING_DATA_INSUFFICIENT

        // 核心证据门控：历史条件不可比时，坚决不能确认为永久老化
        if (!isComparableHistory) {
            return AgingTrendStatus.AGING_UNCERTAIN
        }

        val values = acceptedHistory.map { it.fccMah }
        val first = values.first()
        val last = values.last()
        val dropRatio = (first - last) / first
        val dropMah = first - last

        // 获取合并不确定度估计 \sigma_{\Delta} = \sqrt{\sigma_{old}^2 + \sigma_{new}^2 - 2Cov}
        val firstUncertainty = acceptedHistory.first().uncertaintyMah
        val lastUncertainty = acceptedHistory.last().uncertaintyMah
        val combinedUncertainty = calculateCombinedAgingUncertaintyMah(
            firstUncertainty, lastUncertainty, commonSystematicCovariance
        ) ?: 60.0

        // 检查是否持续单调递减
        var isMonotonicallyDecreasing = true
        for (i in 0 until values.size - 1) {
            if (values[i] < values[i + 1]) {
                isMonotonicallyDecreasing = false
                break
            }
        }

        val exceedsTwoSigmaDelta = dropMah > (2.0 * combinedUncertainty)

        return when {
            // 下降超过 5% 且明确超出 2 倍测量合并不确定度，并且趋势持续单调递减：确认为 AGING_DETECTED
            dropRatio > 0.05 && exceedsTwoSigmaDelta && isMonotonicallyDecreasing -> AgingTrendStatus.AGING_DETECTED
            // 多次可比会话存在明显持续下降趋势，但降幅在不确定度临界区：疑为 AGING_SUSPECTED
            dropRatio > 0.03 || (dropRatio > 0.05 && !isMonotonicallyDecreasing) -> AgingTrendStatus.AGING_SUSPECTED
            // 异常上升或波动：AGING_UNCERTAIN
            dropRatio < -0.05 -> AgingTrendStatus.AGING_UNCERTAIN
            else -> AgingTrendStatus.AGING_STABLE
        }
    }

    /**
     * 验证训练集与验证集的会话组是否严格相互隔离（严禁跨集合切碎会话组）。
     *
     * @param trainingGroups 训练集包含的会话组 ID 列表
     * @param validationGroups 验证集包含的会话组 ID 列表
     * @return 是否严格隔离无交集
     */
    fun isSessionGroupStrictlySegregated(
        trainingGroups: Collection<String>,
        validationGroups: Collection<String>
    ): Boolean {
        if (trainingGroups.isEmpty() || validationGroups.isEmpty()) return true
        val intersection = trainingGroups.intersect(validationGroups.toSet())
        return intersection.isEmpty()
    }

    /**
     * 检验两个放电会话的日历寿命或循环次数跨度是否处于受控范围，防止将长期电池老化误判为环境工况敏感度。
     *
     * @param sampleA 样本 A
     * @param sampleB 样本 B
     * @param maxCalendarDays 最大允许日历天数跨度（默认 30 天）
     * @param maxCycles 最大允许循环次数跨度（默认 20 次）
     * @return 是否排除了长期老化时间漂移的混淆
     */
    fun isConditionEffectSeparatedFromTemporalDrift(
        sampleA: DischargeConditionSample,
        sampleB: DischargeConditionSample,
        maxCalendarDays: Double = 30.0,
        maxCycles: Int = 20
    ): Boolean {
        val idA = sampleA.identity
        val idB = sampleB.identity

        if (idA != null && idB != null) {
            if (idA.calendarAgeDays != null && idB.calendarAgeDays != null) {
                if (abs(idA.calendarAgeDays - idB.calendarAgeDays) > maxCalendarDays) {
                    return false
                }
            }
            if (idA.cycleCount != null && idB.cycleCount != null) {
                if (abs(idA.cycleCount - idB.cycleCount) > maxCycles) {
                    return false
                }
            }
            val timeDiffDays = abs(idB.sessionStartTimestamp - idA.sessionStartTimestamp) / (1000.0 * 3600.0 * 24.0)
            if (timeDiffDays > maxCalendarDays) {
                return false
            }
        }
        return true
    }

    /**
     * 判断两个会话样本是否属于同一电池或同一硬件设备的重复测量（Repeated Measures）。
     *
     * @param sampleA 样本 A
     * @param sampleB 样本 B
     * @return 是否属于同一电池电芯的关联测量
     */
    fun isSameBatteryRepeatedMeasures(
        sampleA: DischargeConditionSample,
        sampleB: DischargeConditionSample
    ): Boolean {
        val idA = sampleA.identity
        val idB = sampleB.identity

        if (idA != null && idB != null) {
            if (idA.batteryIdentity != null && idB.batteryIdentity != null) {
                return idA.batteryIdentity == idB.batteryIdentity
            }
            if (idA.deviceId == idB.deviceId) {
                return true
            }
        }
        return false
    }

    /**
     * 检验工况样本数据集的时间戳顺序是否单调严格递增，防止未来数据泄露到过去训练集。
     *
     * @param samples 按时间顺序排序的样本序列
     * @return 是否严格满足时序单调性
     */
    fun validateChronologicalOrder(samples: List<DischargeConditionSample>): Boolean {
        if (samples.size <= 1) return true
        for (i in 0 until samples.size - 1) {
            val tA = samples[i].identity?.sessionStartTimestamp ?: samples[i].sessionTimestampMs
            val tB = samples[i + 1].identity?.sessionStartTimestamp ?: samples[i + 1].sessionTimestampMs
            if (tA > tB) {
                return false
            }
        }
        return true
    }
}
