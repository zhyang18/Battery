package com.battery.analysis.domain.capacity.estimator

import com.battery.analysis.domain.capacity.model.ConditionMatrixCell
import com.battery.analysis.domain.capacity.model.ConditionMatrixResult
import com.battery.analysis.domain.capacity.model.ConditionModelMaturity
import com.battery.analysis.domain.capacity.model.ConditionSupportStatus
import com.battery.analysis.domain.capacity.model.ContinuousConditionBounds
import com.battery.analysis.domain.capacity.model.DischargeConditionSample
import com.battery.analysis.domain.capacity.model.DischargeLoadBand
import com.battery.analysis.domain.capacity.model.DischargeOperatingCondition
import com.battery.analysis.domain.capacity.model.DischargeQuality
import com.battery.analysis.domain.capacity.model.DischargeThermalBand
import com.battery.analysis.domain.capacity.model.JointConditionSupport
import com.battery.analysis.domain.capacity.model.LoadProfileType
import com.battery.analysis.domain.capacity.model.SessionBootstrapResult
import com.battery.analysis.domain.capacity.model.ThermalProfileType
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 放电工况条件敏感度学习器（ConditionSensitivityLearner）。
 *
 * 遵循技术评审 P2-B2 核心规范：
 * 1. 建立 3×3 工况矩阵（温度 3 档 × 负载 3 档），基于独立会话样本统计能量中位数、MAD 与分布区间；
 * 2. 严格控制混杂变量：必须比较相似负载不同温度，或相似温度不同负载。若缺乏交叉覆盖，坚决标记 INSUFFICIENT_CONDITION_OVERLAP；
 * 3. 严格遵循成熟度状态机（PENDING -> DATA_COLLECTION -> EXPLORATORY -> VALIDATING -> VALIDATED / OUT_OF_DOMAIN）；
 * 4. 严禁未经数据验证前输出虚构归一化能量；超出训练覆盖工况时严格返回 null。
 */
object ConditionSensitivityLearner {

    /**
     * 针对工况样本集构建 3×3 工况比较矩阵，评估交叉覆盖与成熟度。
     *
     * @param samples 独立会话工况样本列表
     * @return 3×3 工况矩阵分析结果实体 [ConditionMatrixResult]
     */
    fun evaluateMatrix(samples: List<DischargeConditionSample>): ConditionMatrixResult {
        // 过滤质检不合格或排除样本
        val validSamples = samples.filter { it.quality != DischargeQuality.REJECTED && it.observedEnergyWh != null && it.observedEnergyWh > 0.0 }

        val thermalBands = listOf(DischargeThermalBand.LOW_TEMP, DischargeThermalBand.ROOM_TEMP, DischargeThermalBand.HIGH_TEMP)
        val loadBands = listOf(DischargeLoadBand.LIGHT, DischargeLoadBand.MODERATE, DischargeLoadBand.HEAVY)

        val cells = mutableListOf<ConditionMatrixCell>()

        for (tBand in thermalBands) {
            for (lBand in loadBands) {
                val matched = validSamples.filter {
                    it.condition.thermalBand == tBand && it.condition.loadBand == lBand
                }
                val energies = matched.mapNotNull { it.observedEnergyWh }.sorted()
                val medianE = calculateMedian(energies)
                val madE = if (medianE != null && energies.isNotEmpty()) {
                    val diffs = energies.map { abs(it - medianE) }.sorted()
                    calculateMedian(diffs)
                } else null

                val p10E = if (energies.isNotEmpty()) DischargeConditionAnalyzer.calculatePercentile(energies, 10.0) else null
                val p90E = if (energies.isNotEmpty()) DischargeConditionAnalyzer.calculatePercentile(energies, 90.0) else null
                val avgDrop = matched.mapNotNull { it.condition.voltageSagMv }.takeIf { it.isNotEmpty() }?.average()

                cells.add(
                    ConditionMatrixCell(
                        thermalBand = tBand,
                        loadBand = lBand,
                        sampleCount = matched.size,
                        medianEnergyWh = medianE,
                        energyMadWh = madE,
                        energyP10Wh = p10E,
                        energyP90Wh = p90E,
                        avgVoltageDropMv = avgDrop
                    )
                )
            }
        }

        // 检查温度与负载是否有交叉覆盖（至少在同一负载档位下有不同温区，或同一温区下有不同负载档位）
        val populatedCells = cells.filter { it.sampleCount >= 1 }
        val populatedThermalBands = populatedCells.map { it.thermalBand }.toSet()
        val populatedLoadBands = populatedCells.map { it.loadBand }.toSet()

        // 检查是否存在可比较的一致性对照组
        val hasLoadControlledComparison = thermalBands.any { t ->
            cells.filter { it.thermalBand == t && it.sampleCount >= 1 }.size >= 2
        }
        val hasThermalControlledComparison = loadBands.any { l ->
            cells.filter { it.loadBand == l && it.sampleCount >= 1 }.size >= 2
        }

        val hasCrossOverlap = (hasLoadControlledComparison || hasThermalControlledComparison) && populatedThermalBands.size >= 2 && populatedLoadBands.size >= 2

        val message = when {
            validSamples.size < 3 -> "数据积累中：有效独立放电会话不足 3 条"
            !hasCrossOverlap -> "INSUFFICIENT_CONDITION_OVERLAP: 缺乏温度与负载的独立对照覆盖组，温度与负载混杂不可分"
            else -> "已具备温度与负载的初步对照交叉覆盖，可进行统计观察"
        }

        val maturity = when {
            validSamples.isEmpty() -> ConditionModelMaturity.PENDING
            validSamples.size < 6 || !hasCrossOverlap -> ConditionModelMaturity.DATA_COLLECTION
            populatedCells.size < 4 -> ConditionModelMaturity.EXPLORATORY
            else -> ConditionModelMaturity.VALIDATING
        }

        return ConditionMatrixResult(
            cells = cells,
            hasCrossConditionOverlap = hasCrossOverlap,
            overlapStatusMessage = message,
            maturity = maturity
        )
    }

    /**
     * 评估给定放电工况是否超出当前矩阵模型的已覆盖训练范围（Out-of-Domain 检查）。
     *
     * @param condition 当前会话工况
     * @param matrix 经过评估的工况矩阵
     * @return 是否超出范围，超出时为 true
     */
    fun isOutOfDomain(condition: DischargeOperatingCondition, matrix: ConditionMatrixResult): Boolean {
        if (matrix.maturity in listOf(ConditionModelMaturity.PENDING, ConditionModelMaturity.DATA_COLLECTION)) {
            return true
        }
        // 查找对应单元格
        val targetCell = matrix.cells.firstOrNull {
            it.thermalBand == condition.thermalBand && it.loadBand == condition.loadBand
        }
        // 若对应单元格无有效训练样本，坚决判定为 OUT_OF_DOMAIN
        return targetCell == null || targetCell.sampleCount < 1
    }

    /**
     * 从有效训练样本集中提取连续物理特征边界（温度、电流、功率、压降极值）。
     *
     * @param samples 训练集样本列表
     * @return 连续特征边界实体 [ContinuousConditionBounds]，若无有效样本则返回 null
     */
    fun extractContinuousBounds(samples: List<DischargeConditionSample>): ContinuousConditionBounds? {
        val valid = samples.filter { it.quality != DischargeQuality.REJECTED }
        if (valid.isEmpty()) return null

        val temps = valid.mapNotNull { it.condition.averageTemperatureC }
        val curs = valid.mapNotNull { it.condition.averageDischargeCurrentMa }
        val powers = valid.mapNotNull { it.condition.averageDischargePowerW }
        val sags = valid.mapNotNull { it.condition.voltageSagMv }

        if (temps.isEmpty() || curs.isEmpty()) return null

        return ContinuousConditionBounds(
            minTemperatureC = temps.minOrNull() ?: 0.0,
            maxTemperatureC = temps.maxOrNull() ?: 45.0,
            minCurrentMa = curs.minOrNull() ?: 100.0,
            maxCurrentMa = curs.maxOrNull() ?: 2000.0,
            minPowerW = powers.minOrNull() ?: 0.5,
            maxPowerW = powers.maxOrNull() ?: 10.0,
            maxObservedVoltageDropMv = sags.maxOrNull() ?: 500.0
        )
    }

    /**
     * 基于离散单元格与连续联合特征支持度（Joint Feature Support）执行 Out-of-Domain 检查。
     *
     * 遵循评审铁律：不能仅凭常温中载单元格有样本，就认为 5000mA 脉冲重载已被覆盖；
     * 连续特征超出训练边界支持范围时，坚决判定为 OUT_OF_DOMAIN。
     *
     * @param condition 待评估会话工况
     * @param matrix 工况矩阵结果
     * @param bounds 训练集连续特征支持边界
     * @return 是否超出模型支持域，超出时为 true
     */
    fun isOutOfDomainWithBounds(
        condition: DischargeOperatingCondition,
        matrix: ConditionMatrixResult,
        bounds: ContinuousConditionBounds?
    ): Boolean {
        // 1. 基础矩阵单元格检查
        if (isOutOfDomain(condition, matrix)) {
            return true
        }

        // 2. 连续特征联合支持检查
        if (bounds != null) {
            val cur = condition.averageDischargeCurrentMa ?: condition.currentP50Ma
            if (cur != null) {
                // 若平均电流超出训练集最大电流的 1.25 倍，坚决判定为超范围
                if (cur > bounds.maxCurrentMa * 1.25 || cur < bounds.minCurrentMa * 0.75) {
                    return true
                }
            }

            val temp = condition.averageTemperatureC ?: condition.temperatureP50C
            if (temp != null) {
                // 温度超出训练集覆盖范围外延 3℃ 以外时，坚决超范围
                if (temp > bounds.maxTemperatureC + 3.0 || temp < bounds.minTemperatureC - 3.0) {
                    return true
                }
            }

            val pwr = condition.averageDischargePowerW ?: condition.medianDischargePowerW
            if (pwr != null) {
                if (pwr > bounds.maxPowerW * 1.3) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * 评估待测工况相对于训练样本集的多维联合特征支持度（Joint Feature Support）。
     *
     * 遵循评审铁律：温度与电流分别在 Min/Max 范围内，不代表联合工况组合已受支持（局部空白区隐患）；
     * 计算归一化特征空间欧氏距离，若最近训练样本距离超过 1.25，判定为 UNSUPPORTED_COMBINATION。
     *
     * @param condition 待评估工况
     * @param trainingSamples 训练集样本列表
     * @return 多维联合支持度实体 [JointConditionSupport]
     */
    fun evaluateJointConditionSupport(
        condition: DischargeOperatingCondition,
        trainingSamples: List<DischargeConditionSample>
    ): JointConditionSupport {
        val validTraining = trainingSamples.filter { it.quality != DischargeQuality.REJECTED }
        if (validTraining.isEmpty()) {
            return JointConditionSupport(
                temperatureCurrentDistance = null,
                nearestTrainingDistance = null,
                supportStatus = ConditionSupportStatus.OUT_OF_MARGINAL_BOUNDS,
                supportDetails = "训练集无有效样本"
            )
        }

        val targetT = condition.averageTemperatureC ?: condition.temperatureP50C ?: 25.0
        val targetI = condition.averageDischargeCurrentMa ?: condition.currentP50Ma ?: 1000.0

        val temps = validTraining.mapNotNull { it.condition.averageTemperatureC ?: it.condition.temperatureP50C }
        val curs = validTraining.mapNotNull { it.condition.averageDischargeCurrentMa ?: it.condition.currentP50Ma }

        val minT = temps.minOrNull() ?: 0.0
        val maxT = temps.maxOrNull() ?: 45.0
        val minI = curs.minOrNull() ?: 100.0
        val maxI = curs.maxOrNull() ?: 2000.0

        // 边际极值检查
        if (targetT < minT - 3.0 || targetT > maxT + 3.0 || targetI < minI * 0.75 || targetI > maxI * 1.25) {
            return JointConditionSupport(
                temperatureCurrentDistance = null,
                nearestTrainingDistance = null,
                supportStatus = ConditionSupportStatus.OUT_OF_MARGINAL_BOUNDS,
                supportDetails = "单维特征已超出训练集边际极值范围"
            )
        }

        // 计算标准化特征空间中到各训练样本的欧氏距离：
        // 温度归一化基底 10.0℃，电流归一化基底 500.0 mA
        var minDistance = Double.MAX_VALUE
        for (sample in validTraining) {
            val t = sample.condition.averageTemperatureC ?: sample.condition.temperatureP50C
            val i = sample.condition.averageDischargeCurrentMa ?: sample.condition.currentP50Ma
            if (t != null && i != null) {
                val dt = (targetT - t) / 10.0
                val di = (targetI - i) / 500.0
                val dist = sqrt(dt * dt + di * di)
                if (dist < minDistance) {
                    minDistance = dist
                }
            }
        }

        // 联合特征支持门限：若归一化欧式距离超过 1.25（相当于温度差 > 12℃ 且电流差 > 600mA），判定为组合无支持
        val status = if (minDistance <= 1.25) {
            ConditionSupportStatus.SUPPORTED
        } else {
            ConditionSupportStatus.UNSUPPORTED_COMBINATION
        }

        return JointConditionSupport(
            temperatureCurrentDistance = minDistance,
            nearestTrainingDistance = minDistance,
            supportStatus = status,
            supportDetails = if (status == ConditionSupportStatus.SUPPORTED) "具备联合训练样本支持(近邻距离=${String.format(java.util.Locale.US, "%.2f", minDistance)})"
            else "UNSUPPORTED_COMBINATION: 虽在边际范围内，但联合特征在训练集中无相近样本(近邻距离=${String.format(java.util.Locale.US, "%.2f", minDistance)})"
        )
    }

    /**
     * 检验工况样本是否属于纯净工况剖面（PURE）。
     *
     * 遵循评审规范：MIXED 混合工况样本绝不能只凭占比最大的区间被当作纯温度或纯负载样本进行模型训练。
     *
     * @param sample 放电工况样本
     * @return 是否为纯净温区且纯净负载工况
     */
    fun isPureCondition(sample: DischargeConditionSample): Boolean {
        return sample.condition.thermalProfile == ThermalProfileType.PURE &&
                sample.condition.loadProfile == LoadProfileType.PURE
    }

    /**
     * 采用会话组（Session Group）重采样计算 Bootstrap 不确定度。
     *
     * 遵循评审规范：
     * 1. 统计单位是完整放电会话，并考虑同一电池内的重复测量关联性；
     * 2. 样本数量或会话组不足（< 3 组）时，不确定度置信区间如实返回 null，绝不随意伪造；
     * 3. 杜绝按采样点数量伪造样本量的错误统计。
     *
     * @param samples 独立放电会话样本列表
     * @param iterations Bootstrap 重采样迭代次数
     * @param randomSeed 随机种子（确保确定性可复现）
     * @return 会话级 Bootstrap 评估结果实体 [SessionBootstrapResult]
     */
    fun calculateSessionGroupBootstrap(
        samples: List<DischargeConditionSample>,
        iterations: Int = 300,
        randomSeed: Long = 42L
    ): SessionBootstrapResult {
        val validSamples = samples.filter { it.quality != DischargeQuality.REJECTED && (it.observedEnergyWh ?: 0.0) > 0.0 }
        if (validSamples.isEmpty()) {
            return SessionBootstrapResult(
                medianEnergyWh = null,
                madEnergyWh = null,
                p10EnergyWh = null,
                p90EnergyWh = null,
                bootstrapCiLowerWh = null,
                bootstrapCiUpperWh = null,
                effectiveSessionGroups = 0,
                sampleSessionCount = 0,
                isConfident = false
            )
        }

        val energies = validSamples.mapNotNull { it.observedEnergyWh }.sorted()
        val medianE = calculateMedian(energies)
        val madE = if (medianE != null && energies.isNotEmpty()) {
            val diffs = energies.map { abs(it - medianE) }.sorted()
            calculateMedian(diffs)
        } else null
        val p10E = if (energies.isNotEmpty()) DischargeConditionAnalyzer.calculatePercentile(energies, 10.0) else null
        val p90E = if (energies.isNotEmpty()) DischargeConditionAnalyzer.calculatePercentile(energies, 90.0) else null

        // 按 sessionGroupId（或无组时按单个会话）聚类为独立会话组
        val groups = validSamples.groupBy { it.identity?.sessionGroupId ?: it.sessionId }.values.toList()
        val groupCount = groups.size

        // 门限保护：若独立会话组少于 3 组，不足以支撑可靠区间估计，严格返回 null！
        if (groupCount < 3) {
            return SessionBootstrapResult(
                medianEnergyWh = medianE,
                madEnergyWh = madE,
                p10EnergyWh = p10E,
                p90EnergyWh = p90E,
                bootstrapCiLowerWh = null,
                bootstrapCiUpperWh = null,
                effectiveSessionGroups = groupCount,
                sampleSessionCount = validSamples.size,
                effectiveIndependentBatteryCount = 1,
                isComputable = false,
                isStatisticallyReliable = false,
                isConfident = false
            )
        }

        // 执行会话组级 Block Bootstrap 重采样
        val rng = Random(randomSeed)
        val bootstrapMeans = mutableListOf<Double>()

        for (iter in 0 until iterations) {
            val resampledEnergies = mutableListOf<Double>()
            for (g in 0 until groupCount) {
                val pickedGroup = groups[rng.nextInt(groupCount)]
                for (s in pickedGroup) {
                    s.observedEnergyWh?.let { resampledEnergies.add(it) }
                }
            }
            if (resampledEnergies.isNotEmpty()) {
                bootstrapMeans.add(resampledEnergies.average())
            }
        }

        bootstrapMeans.sort()
        val ciLower = DischargeConditionAnalyzer.calculatePercentile(bootstrapMeans, 2.5)
        val ciUpper = DischargeConditionAnalyzer.calculatePercentile(bootstrapMeans, 97.5)

        val isComputable = groupCount >= 3
        val isStatisticallyReliable = groupCount >= 5

        return SessionBootstrapResult(
            medianEnergyWh = medianE,
            madEnergyWh = madE,
            p10EnergyWh = p10E,
            p90EnergyWh = p90E,
            bootstrapCiLowerWh = ciLower,
            bootstrapCiUpperWh = ciUpper,
            effectiveSessionGroups = groupCount,
            sampleSessionCount = validSamples.size,
            effectiveIndependentBatteryCount = 1,
            isComputable = isComputable,
            isStatisticallyReliable = isStatisticallyReliable,
            isConfident = isStatisticallyReliable
        )
    }

    /**
     * 计算一组已排序数值的中位数。
     *
     * @param sorted 升序排序的列表
     * @return 中位数值，列表为空时返回 null
     */
    private fun calculateMedian(sorted: List<Double>): Double? {
        if (sorted.isEmpty()) return null
        val s = sorted.size
        return if (s % 2 == 1) sorted[s / 2] else (sorted[s / 2 - 1] + sorted[s / 2]) / 2.0
    }
}
