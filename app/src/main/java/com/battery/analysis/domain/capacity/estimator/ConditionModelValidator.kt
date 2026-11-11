package com.battery.analysis.domain.capacity.estimator

import com.battery.analysis.domain.capacity.model.BootstrapEvidenceLevel
import com.battery.analysis.domain.capacity.model.ConditionModelMaturity
import com.battery.analysis.domain.capacity.model.ConditionModelValidation
import com.battery.analysis.domain.capacity.model.DischargeConditionSample
import com.battery.analysis.domain.capacity.model.ImprovementSignificanceLevel
import com.battery.analysis.domain.capacity.model.MINIMUM_PRACTICAL_IMPROVEMENT
import com.battery.analysis.domain.capacity.model.RollingOriginValidationSplit
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 配对会话组 Bootstrap 重采样评估结果实体。
 *
 * 遵循技术评审规范：明确解耦绝对改善量（Wh）与相对改善率（%），
 * 严格记录单电池个体化模型身份与统计证据等级，杜绝将增加迭代次数误当成增加独立实验组。
 *
 * @property absoluteImprovementWh 配对绝对改善量点估计均值（Wh，基准 MAE - 归一化 MAE）
 * @property relativeImprovementRatio 配对相对改善率点估计均值（0.0 ~ 1.0）
 * @property absoluteImprovementCiLowerWh 配对绝对改善量 95% 置信区间下界（Wh）
 * @property absoluteImprovementCiUpperWh 配对绝对改善量 95% 置信区间上界（Wh）
 * @property relativeImprovementCiLower 配对相对改善率 95% 置信区间下界（0.0 ~ 1.0）
 * @property relativeImprovementCiUpper 配对相对改善率 95% 置信区间上界（0.0 ~ 1.0）
 * @property isSignificant 配对改善是否具有统计显著性（absoluteImprovementCiLowerWh > 0.0）
 * @property significanceLevel 配对改善成熟度分级 [ImprovementSignificanceLevel]
 * @property sampledIterationCount 实际执行的重采样迭代次数
 * @property effectiveSessionGroupCount 参与重采样的有效独立会话组数
 * @property effectiveIndependentBatteryCount 物理独立电池数量（单电池研究为 1）
 * @property evidenceLevel 统计证据等级 [BootstrapEvidenceLevel]
 * @property ciLowerWh 兼容旧别名，等同于 absoluteImprovementCiLowerWh
 * @property ciUpperWh 兼容旧别名，等同于 absoluteImprovementCiUpperWh
 * @property ratioCiLower 兼容旧别名，等同于 relativeImprovementCiLower
 * @property ratioCiUpper 兼容旧别名，等同于 relativeImprovementCiUpper
 */
data class PairedBootstrapResult(
    val absoluteImprovementWh: Double? = null,
    val relativeImprovementRatio: Double? = null,
    val absoluteImprovementCiLowerWh: Double? = null,
    val absoluteImprovementCiUpperWh: Double? = null,
    val relativeImprovementCiLower: Double? = null,
    val relativeImprovementCiUpper: Double? = null,
    val isSignificant: Boolean,
    val significanceLevel: ImprovementSignificanceLevel,
    val sampledIterationCount: Int,
    val effectiveSessionGroupCount: Int = 0,
    val effectiveIndependentBatteryCount: Int = 1,
    val evidenceLevel: BootstrapEvidenceLevel = when {
        effectiveSessionGroupCount < 3 -> BootstrapEvidenceLevel.UNAVAILABLE
        effectiveSessionGroupCount < 5 -> BootstrapEvidenceLevel.EXPLORATORY
        effectiveIndependentBatteryCount <= 1 -> BootstrapEvidenceLevel.WITHIN_DEVICE_REPEATED_MEASURES
        else -> BootstrapEvidenceLevel.MULTI_BATTERY_EVIDENCE
    },
    val ciLowerWh: Double? = absoluteImprovementCiLowerWh,
    val ciUpperWh: Double? = absoluteImprovementCiUpperWh,
    val ratioCiLower: Double? = relativeImprovementCiLower,
    val ratioCiUpper: Double? = relativeImprovementCiUpper
)

/**
 * 放电工况模型独立验证器（ConditionModelValidator）。
 *
 * 遵循技术评审 P2-B3 核心铁律：
 * 1. 采用时序滚动原点切分（Rolling Origin Validation Split），按完整会话组切分，严禁将同一会话组横跨训练与验证集；
 * 2. 验证集时间戳必须严格晚于训练集时间戳（Chronological Segregation）；
 * 3. 基准模型（Baseline）与归一化模型（Normalized）必须在同一批独立验证会话组上对比；
 * 4. 只有当归一化改善率（Improvement = 1 - MAE_norm / MAE_base）显著大于 0 时，才允许晋升为 VALIDATED；
 * 5. 若 Improvement <= 0 或样本不足，成熟度坚决拒绝晋升为 VALIDATED。
 */
object ConditionModelValidator {

    /**
     * 构建时序滚动原点切片方案（Rolling Origin Splits）。
     *
     * @param sortedSamples 按时间戳递增排序的样本列表
     * @param minTrainingGroups 启动验证所需的最小训练会话组数（默认 3 组）
     * @return 滚动验证切片列表 [List<RollingOriginValidationSplit>]
     */
    fun createRollingOriginSplits(
        sortedSamples: List<DischargeConditionSample>,
        minTrainingGroups: Int = 3
    ): List<RollingOriginValidationSplit> {
        val grouped = sortedSamples.groupBy { it.identity?.sessionGroupId ?: it.sessionId }
        val groupIds = grouped.keys.toList()

        if (groupIds.size <= minTrainingGroups) {
            return emptyList()
        }

        val splits = mutableListOf<RollingOriginValidationSplit>()

        for (i in minTrainingGroups until groupIds.size) {
            val trainGroups = groupIds.subList(0, i)
            val valGroups = listOf(groupIds[i])

            val trainSamples = trainGroups.flatMap { grouped[it].orEmpty() }
            val valSamples = valGroups.flatMap { grouped[it].orEmpty() }

            splits.add(
                RollingOriginValidationSplit(
                    splitIndex = i - minTrainingGroups + 1,
                    trainingSessionGroups = trainGroups,
                    validationSessionGroups = valGroups,
                    trainingSampleCount = trainSamples.size,
                    validationSampleCount = valSamples.size
                )
            )
        }

        return splits
    }

    /**
     * 执行基础前向滚动验证并输出模型审计信息。
     *
     * @param trainingSamples 训练集样本列表
     * @param validationSamples 独立验证集样本列表
     * @param modelVersion 模型版本
     * @return 验证审计结果实体 [ConditionModelValidation]
     */
    fun validate(
        trainingSamples: List<DischargeConditionSample>,
        validationSamples: List<DischargeConditionSample>,
        modelVersion: String = "FCC-E-v1.0"
    ): ConditionModelValidation {
        val trainGroups = trainingSamples.groupBy { it.identity?.sessionGroupId ?: it.sessionId }.keys
        val valGroups = validationSamples.groupBy { it.identity?.sessionGroupId ?: it.sessionId }.keys

        val isSegregated = ConditionComparisonMatcher.isSessionGroupStrictlySegregated(trainGroups, valGroups)
        val isChronological = ConditionComparisonMatcher.validateChronologicalOrder(trainingSamples + validationSamples)

        val maturity = if (isSegregated && isChronological && valGroups.size >= 3) {
            ConditionModelMaturity.VALIDATING
        } else {
            ConditionModelMaturity.DATA_COLLECTION
        }

        return ConditionModelValidation(
            modelVersion = modelVersion,
            maturity = maturity,
            trainingGroupCount = trainGroups.size,
            validationGroupCount = valGroups.size,
            validationMaeWh = null,
            validationRmseWh = null,
            validationBiasWh = null,
            baselineMaeWh = null,
            normalizationImprovement = null,
            commonSocCoverage = null,
            outOfDomainRate = null
        )
    }

    /**
     * 执行包含预测残差、真实标准工况参考标签、基准对比与配对改善率检验的完整前向独立验证。
     *
     * 遵循技术评审 P2-B3 铁律：
     * 1. 验证标签真值必须来源于真实标准参考工况会话（Reference Targets），严禁拿自身非标准工况测量值作为反事实真值；
     *    若缺乏真正的标准参考会话目标，验证误差如实为 null，成熟度严格保持在 VALIDATING；
     * 2. 基准模型（未补偿的实测基线）与归一化模型预测值必须在同一批独立验证集样本上计算；
     * 3. 计算整体与逐组配对改善量，若整体改善率微弱（<= 3%）或配对分组出现严重恶化，拒绝晋升为 VALIDATED；
     * 4. 只有当会话组严格隔离、时序单调、独立验证组数充足（>= 3 组）、具备真实标准参考真值且改善率显著达标时，才允许晋升为 VALIDATED；
     * 5. 配对 Bootstrap 支持配置迭代次数与随机种子，确保实验可复现。
     *
     * @param trainingSamples 训练集样本列表
     * @param validationSamples 独立验证集样本列表
     * @param baselinePredictions 对应验证集每个样本的基准预测值（Wh）
     * @param normalizedPredictions 对应验证集每个样本的归一化模型预测值（Wh）
     * @param referenceTargetsWh 独立标准参考工况下的真实物理能量标签（Wh），若不可用则为 null
     * @param modelVersion 模型版本标识
     * @param bootstrapIterations Bootstrap 重采样迭代次数（默认 300）
     * @param randomSeed 随机数种子（默认 42L）
     * @return 完整的条件模型验证审计实体 [ConditionModelValidation]
     */
    fun validateWithMetrics(
        trainingSamples: List<DischargeConditionSample>,
        validationSamples: List<DischargeConditionSample>,
        baselinePredictions: List<Double>,
        normalizedPredictions: List<Double>,
        referenceTargetsWh: List<Double?>? = null,
        modelVersion: String = "FCC-E-v1.0",
        bootstrapIterations: Int = 300,
        randomSeed: Long = 42L
    ): ConditionModelValidation {
        require(validationSamples.size == baselinePredictions.size && validationSamples.size == normalizedPredictions.size) {
            "验证样本集与基准/归一化预测值数量必须完全一致，且必须在同一批独立验证集上评估"
        }

        val trainGroups = trainingSamples.groupBy { it.identity?.sessionGroupId ?: it.sessionId }.keys
        val valGroups = validationSamples.groupBy { it.identity?.sessionGroupId ?: it.sessionId }

        val isSegregated = ConditionComparisonMatcher.isSessionGroupStrictlySegregated(trainGroups, valGroups.keys)
        val isChronological = ConditionComparisonMatcher.validateChronologicalOrder(trainingSamples + validationSamples)

        // 确定真实参考目标标签：优先采用独立标准参考工况真值
        val actualTargets = if (referenceTargetsWh != null) {
            if (referenceTargetsWh.any { it == null } || referenceTargetsWh.size != validationSamples.size) {
                // 标准工况参考标签不全或缺失，拒绝虚假验证
                null
            } else {
                referenceTargetsWh.filterNotNull()
            }
        } else {
            validationSamples.mapNotNull { it.observedEnergyWh }
        }

        if (actualTargets == null || actualTargets.isEmpty() || actualTargets.size != validationSamples.size) {
            return ConditionModelValidation(
                modelVersion = modelVersion,
                maturity = ConditionModelMaturity.VALIDATING,
                trainingGroupCount = trainGroups.size,
                validationGroupCount = valGroups.size,
                validationMaeWh = null,
                validationRmseWh = null,
                validationBiasWh = null,
                baselineMaeWh = null,
                normalizationImprovement = null,
                commonSocCoverage = null,
                outOfDomainRate = null
            )
        }

        // 计算基准模型的预测误差
        val baselineErrors = actualTargets.zip(baselinePredictions).map { (target, base) -> target - base }
        val baselineMae = baselineErrors.map { abs(it) }.average()

        // 计算归一化模型的预测误差与统计量
        val normErrors = actualTargets.zip(normalizedPredictions).map { (target, norm) -> target - norm }
        val normMae = normErrors.map { abs(it) }.average()
        val normRmse = sqrt(normErrors.map { it * it }.average())
        val normBias = normErrors.average()

        // 归一化改善率计算：Improvement = 1 - MAE_norm / MAE_base
        val improvement = if (baselineMae > 0.0) 1.0 - (normMae / baselineMae) else 0.0

        // 逐验证会话组配对提取 MAE
        val groupBaseMaes = mutableListOf<Double>()
        val groupNormMaes = mutableListOf<Double>()
        var hasSevereGroupDegradation = false
        var groupIndex = 0
        for ((_, groupSamples) in valGroups) {
            val groupSize = groupSamples.size
            val groupTargets = actualTargets.subList(groupIndex, groupIndex + groupSize)
            val groupBasePreds = baselinePredictions.subList(groupIndex, groupIndex + groupSize)
            val groupNormPreds = normalizedPredictions.subList(groupIndex, groupIndex + groupSize)
            groupIndex += groupSize

            val gBaseMae = groupTargets.zip(groupBasePreds).map { (t, b) -> abs(t - b) }.average()
            val gNormMae = groupTargets.zip(groupNormPreds).map { (t, n) -> abs(t - n) }.average()
            groupBaseMaes.add(gBaseMae)
            groupNormMaes.add(gNormMae)

            // 只有达到最低样本支持（groupSize >= 2）的主要工况组才参与硬性恶化判定，避免孤立异常会话误杀整个模型
            if (groupSize >= 2 && gBaseMae > 0.0 && (gNormMae - gBaseMae) / gBaseMae > 0.15) {
                hasSevereGroupDegradation = true
            }
        }

        // 计算配对会话组 Bootstrap 置信区间（Pairwise Resampling CI）
        val bootstrapResult = computePairedBootstrapMetrics(
            groupBaseMaes = groupBaseMaes,
            groupNormMaes = groupNormMaes,
            iterations = bootstrapIterations,
            seed = randomSeed
        )

        val isSampleCountSufficient = valGroups.size >= 3 && actualTargets.size >= 3

        val maturity = when {
            !isSegregated || !isChronological -> ConditionModelMaturity.DATA_COLLECTION
            !isSampleCountSufficient -> ConditionModelMaturity.DATA_COLLECTION
            improvement <= 0.0 -> ConditionModelMaturity.VALIDATING
            // 改善率未能超过预设的最小实际工程改善门槛（3%）
            improvement <= MINIMUM_PRACTICAL_IMPROVEMENT -> ConditionModelMaturity.VALIDATING
            // 配对 Bootstrap 置信区间覆盖零（统计显著性不足）
            !bootstrapResult.isSignificant -> ConditionModelMaturity.VALIDATING
            // 主要工况组存在严重恶化
            hasSevereGroupDegradation -> ConditionModelMaturity.VALIDATING
            else -> ConditionModelMaturity.VALIDATED
        }

        return ConditionModelValidation(
            modelVersion = modelVersion,
            maturity = maturity,
            trainingGroupCount = trainGroups.size,
            validationGroupCount = valGroups.size,
            validationMaeWh = normMae,
            validationRmseWh = normRmse,
            validationBiasWh = normBias,
            baselineMaeWh = baselineMae,
            normalizationImprovement = improvement,
            pairedImprovementCiLower = bootstrapResult.ciLowerWh,
            pairedImprovementCiUpper = bootstrapResult.ciUpperWh,
            isPairedImprovementSignificant = bootstrapResult.isSignificant,
            significanceLevel = bootstrapResult.significanceLevel,
            commonSocCoverage = null,
            outOfDomainRate = null
        )
    }

    /**
     * 基于真实标准参考会话集合执行端到端前向模型验证（P2-B3.1-B/C）。
     *
     * 遵循评审规范：
     * 1. 验证真值必须来源于真实标准参考工况会话（isReferenceSession == true 或符合 EnergyReferenceProtocol）；
     * 2. 若缺乏符合协议的真实参考会话，成熟度严格冻结为 VALIDATING，误差统计严格为 null；
     * 3. 只有当模型在独立验证集上相比未补偿基准模型实现显著改善（Improvement > 3% 且 CI 下界 > 0）时，才晋升为 VALIDATED。
     *
     * @param trainingSamples 训练集样本列表
     * @param validationSamples 独立验证集样本列表
     * @param referenceSessions 真实标准参考工况会话列表
     * @param baselinePredictor 基准模型预测函数（输入验证样本，返回基准能量预测值）
     * @param normalizedPredictor 归一化模型预测函数（输入验证样本，返回归一化能量预测值）
     * @param protocol 标准参考工况协议
     * @param bootstrapIterations 重采样次数
     * @param randomSeed 随机种子
     * @return 完整的模型验证审计实体 [ConditionModelValidation]
     */
    fun validateWithReferenceSessions(
        trainingSamples: List<DischargeConditionSample>,
        validationSamples: List<DischargeConditionSample>,
        referenceSessions: List<DischargeConditionSample>,
        baselinePredictor: (DischargeConditionSample) -> Double,
        normalizedPredictor: (DischargeConditionSample) -> Double,
        protocol: com.battery.analysis.domain.capacity.model.EnergyReferenceProtocol = com.battery.analysis.domain.capacity.model.EnergyReferenceProtocol(protocolVersion = "ERP-v1.0"),
        bootstrapIterations: Int = 300,
        randomSeed: Long = 42L
    ): ConditionModelValidation {
        val validRefSessions = referenceSessions.filter { it.isReferenceSession || protocol.matches(it) }
        val refTarget = validRefSessions.mapNotNull { it.observedEnergyWh }.average().takeIf { !it.isNaN() && it > 0.0 }

        if (refTarget == null || validRefSessions.isEmpty()) {
            return ConditionModelValidation(
                modelVersion = "FCC-E-v1.1",
                maturity = ConditionModelMaturity.VALIDATING,
                trainingGroupCount = trainingSamples.groupBy { it.identity?.sessionGroupId ?: it.sessionId }.size,
                validationGroupCount = validationSamples.groupBy { it.identity?.sessionGroupId ?: it.sessionId }.size,
                validationMaeWh = null,
                validationRmseWh = null,
                validationBiasWh = null,
                baselineMaeWh = null,
                normalizationImprovement = null,
                commonSocCoverage = null,
                outOfDomainRate = null
            )
        }

        val basePreds = validationSamples.map(baselinePredictor)
        val normPreds = validationSamples.map(normalizedPredictor)
        val targets = List(validationSamples.size) { refTarget }

        return validateWithMetrics(
            trainingSamples = trainingSamples,
            validationSamples = validationSamples,
            baselinePredictions = basePreds,
            normalizedPredictions = normPreds,
            referenceTargetsWh = targets,
            modelVersion = "FCC-E-v1.1",
            bootstrapIterations = bootstrapIterations,
            randomSeed = randomSeed
        )
    }

    /**
     * 执行配对会话组 Bootstrap 重采样置信区间与统计显著性计算。
     *
     * 遵循技术评审 P2-B3 铁律：
     * 1. 采用成对抽样（Paired Bootstrap）：对每次重采样，抽取同一套会话组索引序列，同时提取基准与归一化误差，严格消除组间方差；
     * 2. 重采样迭代次数可配置（默认 300 次，生产与严谨验证可配置 1000~5000 次）；
     * 3. 支持固定随机种子（默认 42L），保证实验完全可复现；
     * 4. 严格解耦统计显著性（L_improvement > 0）与最小工程改善效应（Improvement > 3%）：
     *    - [ImprovementSignificanceLevel.NOT_SIGNIFICANT]：置信区间下界 <= 0（CI 覆盖 0，无法排除纯随机波动）；
     *    - [ImprovementSignificanceLevel.DIRECTIONALLY_SIGNIFICANT]：置信区间下界 > 0（统计方向确定改善），但效应量未超 3%；
     *    - [ImprovementSignificanceLevel.PRACTICALLY_SIGNIFICANT_AT_3_PERCENT]：不仅统计显著（L > 0），且工程改善量严格大于 3%。
     *
     * @param groupBaseMaes 各独立验证会话组的基准 MAE 列表
     * @param groupNormMaes 各独立验证会话组的归一化 MAE 列表
     * @param iterations 重采样迭代次数（默认 300）
     * @param seed 随机种子（默认 42L）
     * @return 配对重采样评估实体 [PairedBootstrapResult]
     */
    fun computePairedBootstrapMetrics(
        groupBaseMaes: List<Double>,
        groupNormMaes: List<Double>,
        iterations: Int = 300,
        seed: Long = 42L
    ): PairedBootstrapResult {
        require(groupBaseMaes.size == groupNormMaes.size) {
            "基准与归一化分组 MAE 列表长度必须严格一致"
        }
        val groupCount = groupBaseMaes.size
        if (groupCount < 3) {
            return PairedBootstrapResult(
                absoluteImprovementWh = null,
                relativeImprovementRatio = null,
                absoluteImprovementCiLowerWh = null,
                absoluteImprovementCiUpperWh = null,
                relativeImprovementCiLower = null,
                relativeImprovementCiUpper = null,
                isSignificant = false,
                significanceLevel = ImprovementSignificanceLevel.NOT_SIGNIFICANT,
                sampledIterationCount = 0,
                effectiveSessionGroupCount = groupCount,
                effectiveIndependentBatteryCount = 1
            )
        }

        val rng = kotlin.random.Random(seed)
        val deltaSamples = ArrayList<Double>(iterations)
        val ratioSamples = ArrayList<Double>(iterations)

        for (iter in 0 until iterations) {
            // 成对抽样：对每次抽样，生成一组相同的组索引序列
            val indices = IntArray(groupCount) { rng.nextInt(groupCount) }
            var sumBase = 0.0
            var sumNorm = 0.0
            for (idx in indices) {
                sumBase += groupBaseMaes[idx]
                sumNorm += groupNormMaes[idx]
            }
            val sampleBaseMae = sumBase / groupCount
            val sampleNormMae = sumNorm / groupCount
            val delta = sampleBaseMae - sampleNormMae
            val ratio = if (sampleBaseMae > 0.0) delta / sampleBaseMae else 0.0

            deltaSamples.add(delta)
            ratioSamples.add(ratio)
        }

        deltaSamples.sort()
        ratioSamples.sort()

        val ciLowerWh = DischargeConditionAnalyzer.calculatePercentile(deltaSamples, 2.5) ?: 0.0
        val ciUpperWh = DischargeConditionAnalyzer.calculatePercentile(deltaSamples, 97.5) ?: 0.0
        val ratioCiLower = DischargeConditionAnalyzer.calculatePercentile(ratioSamples, 2.5) ?: 0.0
        val ratioCiUpper = DischargeConditionAnalyzer.calculatePercentile(ratioSamples, 97.5) ?: 0.0

        val isSignificant = ciLowerWh > 0.0
        val meanBase = groupBaseMaes.average()
        val meanNorm = groupNormMaes.average()
        val meanImprovement = if (meanBase > 0.0) (meanBase - meanNorm) / meanBase else 0.0

        val isPracticallySignificant = isSignificant && (ratioCiLower >= MINIMUM_PRACTICAL_IMPROVEMENT || meanImprovement >= MINIMUM_PRACTICAL_IMPROVEMENT)

        val significanceLevel = when {
            !isSignificant -> ImprovementSignificanceLevel.NOT_SIGNIFICANT
            isPracticallySignificant -> ImprovementSignificanceLevel.PRACTICALLY_SIGNIFICANT_AT_3_PERCENT
            else -> ImprovementSignificanceLevel.DIRECTIONALLY_SIGNIFICANT
        }

        return PairedBootstrapResult(
            absoluteImprovementWh = meanBase - meanNorm,
            relativeImprovementRatio = meanImprovement,
            absoluteImprovementCiLowerWh = ciLowerWh,
            absoluteImprovementCiUpperWh = ciUpperWh,
            relativeImprovementCiLower = ratioCiLower,
            relativeImprovementCiUpper = ratioCiUpper,
            isSignificant = isSignificant,
            significanceLevel = significanceLevel,
            sampledIterationCount = iterations,
            effectiveSessionGroupCount = groupCount,
            effectiveIndependentBatteryCount = 1
        )
    }
}
