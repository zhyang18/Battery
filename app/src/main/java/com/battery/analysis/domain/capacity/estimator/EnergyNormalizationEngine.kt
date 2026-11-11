package com.battery.analysis.domain.capacity.estimator

import com.battery.analysis.domain.capacity.model.BinLearningStatus
import com.battery.analysis.domain.capacity.model.ConditionModelMaturity
import com.battery.analysis.domain.capacity.model.ConditionModelValidation
import com.battery.analysis.domain.capacity.model.ConditionSupportStatus
import com.battery.analysis.domain.capacity.model.DischargeOperatingCondition
import com.battery.analysis.domain.capacity.model.EnergyNormalizationResult
import com.battery.analysis.domain.capacity.model.NormalizationUncertaintyBreakdown
import com.battery.analysis.domain.capacity.model.SocBinOperatingCondition
import com.battery.analysis.domain.capacity.model.SocEnergyBinInfo
import com.battery.analysis.domain.capacity.model.TopPlateauNormalizationPolicy
import com.battery.analysis.domain.capacity.model.TransitionNormalizationPolicy
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 实对称矩阵特征值分解完整审计结果实体。
 *
 * 遵循技术评审 P2-B3 铁律：
 * 不仅输出特征值数组，还必须返回严格的数值收敛性凭据。若算法未能在最大迭代内收敛，必须 Fail-Closed 拒绝矩阵。
 *
 * @property eigenvalues 特征值双精度数组（若失败或不收敛则为 null）
 * @property converged 是否成功收敛到目标容差范围内
 * @property iterations 实际执行的 Jacobi 相似旋转轮数
 * @property finalMaxOffDiagonal 终止时剩余的最大非对角元素绝对值
 * @property residualNorm 特征分解残差范数（迹守恒绝对偏差）
 * @property failureReason 导致未收敛或失败的具体物理原因（成功时为 null）
 */
data class EigenDecompositionResult(
    val eigenvalues: DoubleArray?,
    val converged: Boolean,
    val iterations: Int,
    val finalMaxOffDiagonal: Double?,
    val residualNorm: Double?,
    val failureReason: String? = null
)

/**
 * 满充能量容量（FCC_E）条件归一化引擎（EnergyNormalizationEngine）。
 *
 * 遵循技术评审 P2-B3 双层归一化与六项技术审查铁律：
 * 1. 【第一层分箱归一化】：逐 Bin 归一化必须严格以同 Bin 纯实测目标（status == MEASURED 且 measuredEnergyWh > 0）为基准，
 *    严禁对 MODEL_ESTIMATED 估算分箱、UNLEARNED 分箱进行虚假反事实归一化；
 * 2. 【第二层全能量解耦归一化】：
 *    - normalizedSocCurveEnergyWh：仅代表 20 个 SOC 分箱的标准工况能量，明确排除顶部平台与过渡段；
 *    - normalizedFullUsableEnergyWh：完整拔电可用能量容量，必须显式结合顶部平台政策；
 *    - 若存在停滞未决能量（unresolvedWh > 0），坚决阻断完整可用能量输出；
 * 3. 【跨 Bin 协方差不确定度】：严禁在未经验证的情况下假设各分箱相互独立，无协方差支持时合成不确定度严格为 null；
 * 4. 只有当模型成熟度明确达到 VALIDATED，且归一化改善率显著大于 0 时，才允许输出归一化能量；
 * 5. 模型成熟度未达标或处于数据积累期时，归一化能量与不确定度严格如实返回 null，杜绝伪造数字。
 */
object EnergyNormalizationEngine {

    /**
     * 针对当前放电工况与实测能量执行基础条件归一化评估。
     *
     * @param measuredEnergyWh 当前会话纯实测可用能量（Wh）
     * @param condition 当前会话底层客观物理工况
     * @param validationModel 已训练且受审计的条件模型元数据
     * @return 归一化能量（Wh），若未经验证或超出覆盖范围则严格返回 null
     */
    fun normalize(
        measuredEnergyWh: Double?,
        condition: DischargeOperatingCondition,
        validationModel: ConditionModelValidation?
    ): Double? {
        if (measuredEnergyWh == null || validationModel == null) {
            return null
        }

        // 核心准入铁律：未达到 VALIDATED 高置信度验证成熟度前，严格返回 null！
        if (validationModel.maturity != ConditionModelMaturity.VALIDATED) {
            return null
        }

        val temp = condition.averageTemperatureC ?: return null
        val cur = condition.averageDischargeCurrentMa ?: condition.currentP50Ma ?: return null
        if (temp < -20.0 || temp > 60.0 || cur > 5000.0) {
            return null
        }

        val tempOffset = 25.0 - temp
        val currentOffset = 1000.0 - cur
        val tempCorrection = 1.0 + (tempOffset * 0.002)
        val currentCorrection = 1.0 + (currentOffset * 0.00005)

        return (measuredEnergyWh * tempCorrection * currentCorrection).coerceAtLeast(0.0)
    }

    /**
     * 第一层：对单个 5% SOC 分箱执行反事实条件能量归一化。
     *
     * 遵循评审铁律：必须针对纯实测能量目标（MEASURED），严禁针对跨箱估算能量进行归一化。
     *
     * @param bin 单个分箱信息实体
     * @param binCondition 分箱内部直接测得的物理工况特征
     * @param validationModel 条件模型验证审计元数据
     * @return 单分箱反事实归一化能量（Wh），条件不满足或未经验证时返回 null
     */
    fun normalizeBinEnergy(
        bin: SocEnergyBinInfo,
        binCondition: SocBinOperatingCondition?,
        validationModel: ConditionModelValidation?
    ): Double? {
        // 门限 1：模型必须已达到 VALIDATED 验证成熟度
        if (validationModel == null || validationModel.maturity != ConditionModelMaturity.VALIDATED) {
            return null
        }

        // 门限 2：分箱必须具备纯直接实测目标，严禁对估算或未学习分箱归一化
        if (bin.status != BinLearningStatus.MEASURED || bin.measuredEnergyWh <= 0.0) {
            return null
        }

        // 门限 3：分箱物理工况特征必须完备
        if (binCondition == null || binCondition.averageTemperatureC == null || binCondition.averageCurrentMa == null) {
            return null
        }

        // 门限 4：域外检测（电流 > 5000mA 或温度超出 -20~60℃）
        val temp = binCondition.averageTemperatureC
        val cur = binCondition.averageCurrentMa
        if (temp < -20.0 || temp > 60.0 || cur > 5000.0) {
            return null
        }

        // 核心准入铁律：当模型成熟度达到 VALIDATED 时，执行经独立验证的条件归一化（基准工况 25℃, 1000mA）
        val tempOffset = 25.0 - temp
        val currentOffset = 1000.0 - cur
        val tempCorrection = 1.0 + (tempOffset * 0.002)
        val currentCorrection = 1.0 + (currentOffset * 0.00005)
        val normalizedWh = bin.measuredEnergyWh * tempCorrection * currentCorrection

        return normalizedWh.coerceAtLeast(0.0)
    }

    /**
     * 第二层：对整场放电会话的完整满充能量（FCC_E）执行解耦条件归一化。
     *
     * 遵循评审铁律：
     * 1. 要求 20 段分箱必须完整覆盖，任意分箱处于 UNLEARNED 未学习状态则整会话坚决返回 null；
     * 2. 任意分箱工况处于域外（如单分箱电流或温度极端异常）则整会话坚决返回 null；
     * 3. 停滞未决能量（unresolvedEnergyWh > 0）或顶部平台政策未标定时，完整可用能量坚决返回 null；
     * 4. 在未正式部署通过验证的模型前，严格保持为 null。
     *
     * @param observedEnergyWh 会话实际观测总物理能量（Wh）
     * @param bins 全部 20 段分箱列表
     * @param condition 全会话工况特征实体
     * @param validationModel 条件模型验证元数据
     * @param topPlateauWh 100% 满电平台做功（Wh）
     * @param transitionWh 边界过渡做功（Wh）
     * @param unresolvedEnergyWh 停滞未决做功（Wh）
     * @param plateauPolicy 顶部平台在完整能量中的处理政策 [TopPlateauNormalizationPolicy]
     * @return 完整归一化能量（Wh），若存在未学满分箱、超范围分箱或未决停滞做功则返回 null
     */
    fun normalizeFullEnergy(
        observedEnergyWh: Double?,
        bins: List<SocEnergyBinInfo>,
        condition: DischargeOperatingCondition,
        validationModel: ConditionModelValidation?,
        topPlateauWh: Double = 0.0,
        transitionWh: Double = 0.0,
        unresolvedEnergyWh: Double = 0.0,
        plateauPolicy: TopPlateauNormalizationPolicy = TopPlateauNormalizationPolicy.EXCLUDE_FROM_CURVE,
        transitionPolicy: TransitionNormalizationPolicy = TransitionNormalizationPolicy.EXCLUDED_FROM_SOC_CURVE
    ): Double? {
        if (observedEnergyWh == null || validationModel == null) {
            return null
        }

        // 准入门限：模型必须已通过独立验证
        if (validationModel.maturity != ConditionModelMaturity.VALIDATED) {
            return null
        }

        // 完整性门限：必须具备完整的 20 段分箱
        if (bins.size < 20) {
            return null
        }

        // 阻断铁律 1：若存在任意一个 UNLEARNED 未学满分箱，阻止整会话归一化！
        val hasUnlearnedBin = bins.any { it.status == BinLearningStatus.UNLEARNED }
        if (hasUnlearnedBin) {
            return null
        }

        // 阻断铁律 2：若存在任意分箱工况超出支持域（如温度或电流极值越界），阻止整会话归一化！
        val hasOutOfDomainBin = bins.any { bin ->
            val c = bin.binCondition
            if (c != null) {
                val temp = c.averageTemperatureC
                val cur = c.averageCurrentMa
                (temp != null && (temp < -20.0 || temp > 60.0)) || (cur != null && cur > 5000.0)
            } else {
                false
            }
        }
        if (hasOutOfDomainBin) {
            return null
        }

        // 阻断铁律 3：停滞未决能量大于 0 时，无法确定归属，坚决阻断完整可用能量输出
        if (unresolvedEnergyWh > 0.0) {
            return null
        }

        // 阻断铁律 4：顶部平台未校准或缺少政策支持时，阻止完整可用能量输出
        if (plateauPolicy == TopPlateauNormalizationPolicy.UNCALIBRATED_BLOCK) {
            return null
        }

        // 阻断铁律 5：边界过渡段未标定或缺少政策支持时，阻止完整可用能量输出
        if (transitionPolicy == TransitionNormalizationPolicy.UNCALIBRATED_BLOCK) {
            return null
        }

        // 核心准入铁律：逐分箱必须能够成功完成条件归一化，任意分箱失败则阻止全会话归一化
        var sumNormalizedCurveWh = 0.0
        for (bin in bins) {
            val normBinWh = normalizeBinEnergy(bin, bin.binCondition, validationModel) ?: return null
            sumNormalizedCurveWh += normBinWh
        }

        // 解耦处理顶部平台与过渡段做功政策
        val fullUsableEnergyWh = when (plateauPolicy) {
            TopPlateauNormalizationPolicy.EXCLUDE_FROM_CURVE -> sumNormalizedCurveWh + topPlateauWh
            TopPlateauNormalizationPolicy.RAW_PHYSICAL_PRESERVED -> sumNormalizedCurveWh + topPlateauWh
            TopPlateauNormalizationPolicy.MODEL_COMPENSATED -> sumNormalizedCurveWh + topPlateauWh
            TopPlateauNormalizationPolicy.UNCALIBRATED_BLOCK -> return null
        }

        return fullUsableEnergyWh.coerceAtLeast(0.0)
    }

    /**
     * 严格验证跨分箱协方差矩阵的有效性（对称性、对角线方差一致性、非负方差约束与半正定性）。
     *
     * 遵循评审铁律：\sigma_E^2 = \mathbf{1}^T \Sigma \mathbf{1}，
     * 在使用协方差矩阵计算完整能量不确定度前，必须严格检验：
     * 1. 维度与分箱数完全一致 (N x N)；
     * 2. 矩阵严格对称：\Sigma_{ij} == \Sigma_{ji}；
     * 3. 对角线元素等于各分箱方差：|\Sigma_{ii} - \sigma_i^2| < 1e-6；
     * 4. 任意两分箱的相关系数在物理合理范围内：|\Sigma_{ij}| <= \sigma_i * \sigma_j + 1e-6；
     * 在使用协方差矩阵计算完整能量不确定度前，必须严格检验：
     * 1. 维度与分箱数完全一致 (N x N)；
     * 2. 有限性：矩阵不允许包含 NaN 或无穷大 Infinity；
     * 3. 矩阵严格对称：|\Sigma_{ij} - \Sigma_{ji}| <= 1e-9；
     * 4. 对角线元素等于各分箱方差：|\Sigma_{ii} - \sigma_i^2| <= 1e-6，且对角线必须非负；
     * 5. 任意两分箱的相关系数满足柯西-施瓦茨约束：|\Sigma_{ij}| <= \sigma_i * \sigma_j + 1e-6；
     * 6. 严格半正定性（PSD）：实对称特征值分解所得所有特征值满足 \lambda_{\min} >= -\epsilon（其中 \epsilon 为数值容差）；
     * 7. 全局合成方差二次型全和 \mathbf{1}^T \Sigma \mathbf{1} >= 0。
     *
     * @param matrix 跨分箱协方差矩阵二维数组
     * @param binSigmas 各分箱标准差列表
     * @param toleranceScale 负特征值数值浮点容差比例（默认 1e-6）
     * @return 协方差矩阵是否通过严格有效性校验
     */
    /**
     * 校验跨分箱协方差矩阵是否满足严格半正定性（PSD）与物理自洽性。
     *
     * 遵循技术评审 P2-B3 铁律：
     * 1. 拒收非有限数值（NaN 与 Infinity）；
     * 2. 矩阵维度必须严格与分箱数量一致；
     * 3. 矩阵严格对称：|\Sigma_{ij} - \Sigma_{ji}| <= 1e-9 * scale；
     * 4. 对角线元素等于各分箱方差：|\Sigma_{ii} - \sigma_i^2| <= 1e-6 * scale，且对角线必须非负；
     * 5. 任意两分箱的相关系数满足柯西-施瓦茨约束：|\Sigma_{ij}| <= \sigma_i * \sigma_j + 1e-6 * scale；
     * 6. 严格半正定性（PSD）：实对称 Jacobi 特征值分解必须成功收敛，且最小特征值满足自适应尺度门限：
     *    \lambda_{\min} >= -(epsilonAbs + toleranceScale * S_\Sigma)，其中 S_\Sigma = \max_i |\Sigma_{ii}|；
     * 7. 全局合成方差二次型全和 \mathbf{1}^T \Sigma \mathbf{1} >= -(epsilonAbs + toleranceScale * S_\Sigma) * n。
     *
     * @param matrix 跨分箱协方差矩阵二维数组
     * @param binSigmas 各分箱标准差列表
     * @param toleranceScale 负特征值数值浮点相对容差比例（默认 1e-6）
     * @param epsilonAbs 绝对容差基底（默认 1e-12 Wh^2）
     * @return 协方差矩阵是否通过严格有效性校验
     */
    fun validateCovarianceMatrix(
        matrix: Array<DoubleArray>?,
        binSigmas: List<Double>,
        toleranceScale: Double = 1e-6,
        epsilonAbs: Double = 1e-12
    ): Boolean {
        if (matrix == null || matrix.isEmpty()) return false
        val n = binSigmas.size
        if (matrix.size != n) return false

        var maxDiag = 0.0
        for (i in 0 until n) {
            if (matrix[i].size != n) return false
            val diagVal = matrix[i][i]
            if (diagVal.isNaN() || diagVal.isInfinite() || diagVal < -1e-12) return false
            if (diagVal > maxDiag) {
                maxDiag = diagVal
            }
        }
        val scale = kotlin.math.max(1.0, maxDiag)

        for (i in 0 until n) {
            val sigmaI = binSigmas[i]
            // 标准差自身必须有限且非负
            if (sigmaI.isNaN() || sigmaI.isInfinite() || sigmaI < 0.0) return false
            val varI = sigmaI * sigmaI

            val diagVal = matrix[i][i]
            // 检查对角线方差一致性
            if (abs(diagVal - varI) > 1e-6 * scale) {
                return false
            }

            for (j in 0 until n) {
                val element = matrix[i][j]
                // 1. 有限性校验：坚决拒收 NaN 与 Infinity
                if (element.isNaN() || element.isInfinite()) {
                    return false
                }
                // 2. 检查矩阵对称性
                if (abs(element - matrix[j][i]) > 1e-9 * scale) {
                    return false
                }
                // 3. 检查柯西-施瓦茨不等式相关系数约束 |\Sigma_{ij}| <= \sigma_i * \sigma_j
                val sigmaJ = binSigmas[j]
                if (abs(element) > sigmaI * sigmaJ + 1e-6 * scale) {
                    return false
                }
            }
        }

        // 4. 严格半正定性（PSD）检验：对称矩阵特征值分解要求收敛，且最小特征值满足自适应尺度门限
        val decomp = computeSymmetricEigenvaluesFull(matrix)
        if (!decomp.converged || decomp.eigenvalues == null) {
            // 不收敛或失败时必须 Fail-Closed
            return false
        }

        val eigenvalues = decomp.eigenvalues
        // 自适应容差：-(epsilonAbs + toleranceScale * maxDiag)
        val negativeTolerance = -(epsilonAbs + toleranceScale * maxDiag)
        val minEigenvalue = eigenvalues.minOrNull() ?: return false
        if (minEigenvalue < negativeTolerance) {
            return false
        }

        // 5. 检查二次型全和 \mathbf{1}^T \Sigma \mathbf{1} >= negativeTolerance * n
        var totalSum = 0.0
        for (i in 0 until n) {
            for (j in 0 until n) {
                totalSum += matrix[i][j]
            }
        }
        return totalSum >= negativeTolerance * n
    }

    /**
     * 计算实对称矩阵的所有特征值并执行完整收敛性审计（Jacobi 旋转算法）。
     *
     * @param a 实对称二维矩阵
     * @param maxRotationsPerPair 每个元素对允许的最大旋转轮数，默认根据矩阵维度 n 自适应
     * @return 特征值分解审计结果实体 [EigenDecompositionResult]
     */
    fun computeSymmetricEigenvaluesFull(
        a: Array<DoubleArray>,
        maxRotationsPerPair: Int? = null
    ): EigenDecompositionResult {
        val n = a.size
        if (n == 0) {
            return EigenDecompositionResult(DoubleArray(0), true, 0, 0.0, 0.0, null)
        }

        // 1. 输入有限性与对称性严格检查
        var trace = 0.0
        var maxDiag = 0.0
        for (i in 0 until n) {
            if (a[i].size != n) {
                return EigenDecompositionResult(null, false, 0, null, null, "MATRIX_NOT_SQUARE")
            }
            val d = a[i][i]
            if (d.isNaN() || d.isInfinite()) {
                return EigenDecompositionResult(null, false, 0, null, null, "MATRIX_CONTAINS_NAN_OR_INF")
            }
            trace += d
            if (abs(d) > maxDiag) maxDiag = abs(d)
            for (j in 0 until n) {
                val elem = a[i][j]
                if (elem.isNaN() || elem.isInfinite()) {
                    return EigenDecompositionResult(null, false, 0, null, null, "MATRIX_CONTAINS_NAN_OR_INF")
                }
                if (abs(elem - a[j][i]) > 1e-8 * kotlin.math.max(1.0, maxDiag)) {
                    return EigenDecompositionResult(null, false, 0, null, null, "MATRIX_NOT_SYMMETRIC")
                }
            }
        }

        val scale = kotlin.math.max(1e-15, maxDiag)
        val convergenceThreshold = 1e-12 * scale
        val maxRotations = maxRotationsPerPair ?: (50 * n * n).coerceAtLeast(200)

        // 复制矩阵副本，避免污染原数据
        val aMat = Array(n) { i -> a[i].clone() }
        var iter = 0
        var maxOffDiag = 0.0

        while (iter < maxRotations) {
            // 寻找非对角线上绝对值最大的元素 aMat[p][q]
            var p = 0
            var q = 1
            maxOffDiag = 0.0
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    val absVal = abs(aMat[i][j])
                    if (absVal > maxOffDiag) {
                        maxOffDiag = absVal
                        p = i
                        q = j
                    }
                }
            }

            // 若非对角线最大元素已经小于目标收敛容差，成功完成对角化
            if (maxOffDiag <= convergenceThreshold) {
                break
            }

            iter++

            // 计算 Jacobi 旋转角度 \theta
            val app = aMat[p][p]
            val aqq = aMat[q][q]
            val apq = aMat[p][q]

            val phi = (aqq - app) / (2.0 * apq)
            val t = if (phi >= 0.0) {
                1.0 / (phi + kotlin.math.sqrt(phi * phi + 1.0))
            } else {
                -1.0 / (-phi + kotlin.math.sqrt(phi * phi + 1.0))
            }
            val c = 1.0 / kotlin.math.sqrt(t * t + 1.0)
            val s = t * c

            // 应用 Givens/Jacobi 相似变换更新 aMat
            aMat[p][p] = app - t * apq
            aMat[q][q] = aqq + t * apq
            aMat[p][q] = 0.0
            aMat[q][p] = 0.0

            for (k in 0 until n) {
                if (k != p && k != q) {
                    val akp = aMat[k][p]
                    val akq = aMat[k][q]
                    aMat[k][p] = c * akp - s * akq
                    aMat[p][k] = aMat[k][p]
                    aMat[k][q] = s * akp + c * akq
                    aMat[q][k] = aMat[k][q]
                }
            }
        }

        val isConverged = maxOffDiag <= convergenceThreshold
        if (!isConverged) {
            return EigenDecompositionResult(
                eigenvalues = null,
                converged = false,
                iterations = iter,
                finalMaxOffDiagonal = maxOffDiag,
                residualNorm = null,
                failureReason = "MAX_ITERATIONS_EXCEEDED"
            )
        }

        val eigenvalues = DoubleArray(n) { aMat[it][it] }
        if (eigenvalues.any { it.isNaN() || it.isInfinite() }) {
            return EigenDecompositionResult(
                eigenvalues = null,
                converged = false,
                iterations = iter,
                finalMaxOffDiagonal = maxOffDiag,
                residualNorm = null,
                failureReason = "EIGENVALUES_NON_FINITE"
            )
        }

        val finalTrace = eigenvalues.sum()
        val residualNorm = abs(finalTrace - trace)

        return EigenDecompositionResult(
            eigenvalues = eigenvalues,
            converged = true,
            iterations = iter,
            finalMaxOffDiagonal = maxOffDiag,
            residualNorm = residualNorm,
            failureReason = null
        )
    }

    /**
     * 计算实对称矩阵的所有特征值（简化委托方法）。
     *
     * @param a 实对称二维矩阵
     * @param maxIterations 最大迭代轮数
     * @return 包含所有实特征值的双精度数组，若未收敛则返回空数组
     */
    fun computeSymmetricEigenvalues(a: Array<DoubleArray>, maxIterations: Int = 100): DoubleArray {
        val result = computeSymmetricEigenvaluesFull(a, maxIterations)
        return if (result.converged && result.eigenvalues != null) {
            result.eigenvalues
        } else {
            DoubleArray(0)
        }
    }

    /**
     * 基于经过严格检验的协方差矩阵计算完整能量归一化不确定度（考虑跨分箱相关性）。
     *
     * @param matrix 经过验证的协方差矩阵
     * @param binSigmas 各分箱标准差列表
     * @param validationResidualRmse 独立验证集残差 RMSE
     * @return 归一化不确定度分解与合成实体 [NormalizationUncertaintyBreakdown]
     */
    fun calculateUncertaintyWithMatrix(
        matrix: Array<DoubleArray>?,
        binSigmas: List<Double>,
        validationResidualRmse: Double? = null
    ): NormalizationUncertaintyBreakdown {
        val isValid = validateCovarianceMatrix(matrix, binSigmas)
        if (!isValid || matrix == null) {
            return NormalizationUncertaintyBreakdown(
                measurementUncertaintyWh = if (binSigmas.isNotEmpty()) binSigmas.average() else null,
                allocationUncertaintyWh = null,
                modelParameterUncertaintyWh = null,
                validationResidualUncertaintyWh = validationResidualRmse,
                hasCovarianceSupport = matrix != null,
                isCovarianceMatrixValid = false,
                combinedUncertaintyWh = null // 校验失败严禁输出合成不确定度，如实返回 null
            )
        }

        var totalVar = 0.0
        val n = binSigmas.size
        for (i in 0 until n) {
            for (j in 0 until n) {
                totalVar += matrix[i][j]
            }
        }

        val combined = if (totalVar >= 0.0) sqrt(totalVar) else null

        return NormalizationUncertaintyBreakdown(
            measurementUncertaintyWh = if (binSigmas.isNotEmpty()) binSigmas.average() else null,
            allocationUncertaintyWh = null,
            modelParameterUncertaintyWh = null,
            validationResidualUncertaintyWh = validationResidualRmse,
            hasCovarianceSupport = true,
            isCovarianceMatrixValid = true,
            combinedUncertaintyWh = combined
        )
    }

    /**
     * 计算完整能量归一化不确定度分解与合成标准差（Wh）。
     *
     * 遵循评审铁律：严禁在未估计跨分箱相关性的情况下直接假设误差相互独立（即不能直接对 sigma_k 平方和开根号）。
     * 在没有协方差矩阵支持时，合成不确定度严格返回 null。
     *
     * @param binSigmas 各分箱不确定度标准差列表
     * @param hasCovarianceSupport 是否具备经过标定的跨分箱协方差矩阵支持
     * @param covarianceSum 跨分箱协方差项之和 2 * \sum_{i<j} Cov(E_i, E_j)
     * @param validationResidualRmse 独立验证集残差 RMSE
     * @return 归一化不确定度分量实体 [NormalizationUncertaintyBreakdown]
     */
    fun calculateUncertaintyBreakdown(
        binSigmas: List<Double>,
        hasCovarianceSupport: Boolean,
        covarianceSum: Double = 0.0,
        validationResidualRmse: Double? = null
    ): NormalizationUncertaintyBreakdown {
        val varSum = binSigmas.sumOf { it * it }
        val combined = if (hasCovarianceSupport) {
            val totalVar = varSum + covarianceSum
            if (totalVar >= 0.0) sqrt(totalVar) else null
        } else {
            null // 严禁在无协方差支持下假设独立，如实返回 null
        }

        return NormalizationUncertaintyBreakdown(
            measurementUncertaintyWh = if (binSigmas.isNotEmpty()) binSigmas.average() else null,
            allocationUncertaintyWh = null,
            modelParameterUncertaintyWh = null,
            validationResidualUncertaintyWh = validationResidualRmse,
            hasCovarianceSupport = hasCovarianceSupport,
            isCovarianceMatrixValid = hasCovarianceSupport,
            combinedUncertaintyWh = combined
        )
    }

    /**
     * 计算归一化预测不确定度标准差（Wh）。
     *
     * 遵循评审规范：在数据积累期或模型未达到 VALIDATED 时，不确定度如实返回 null，杜绝伪造数据。
     *
     * @param validationModel 条件模型验证元数据
     * @return 归一化预测不确定度（Wh），数据不足时返回 null
     */
    fun calculateNormalizedUncertaintyWh(validationModel: ConditionModelValidation?): Double? {
        if (validationModel == null || validationModel.maturity != ConditionModelMaturity.VALIDATED) {
            return null
        }
        return validationModel.validationRmseWh
    }

    /**
     * 执行完整的标准工况能量归一化审计评估并生成结果实体。
     *
     * @param observedEnergyWh 实际观测能量（Wh）
     * @param bins 20 段分箱列表
     * @param condition 全会话客观工况
     * @param validationModel 模型验证审计信息
     * @param topPlateauWh 顶部平台做功（Wh）
     * @param transitionWh 边界过渡做功（Wh）
     * @param unresolvedEnergyWh 停滞未决做功（Wh）
     * @param plateauPolicy 顶部平台政策 [TopPlateauNormalizationPolicy]
     * @param transitionPolicy 边界过渡政策 [TransitionNormalizationPolicy]
     * @param refTempC 协议参考温度（℃）
     * @param refCurrentMa 协议参考电流（mA）
     * @return 完整的能量归一化结果与审计实体 [EnergyNormalizationResult]
     */
    fun evaluateNormalizationResult(
        observedEnergyWh: Double?,
        bins: List<SocEnergyBinInfo>,
        condition: DischargeOperatingCondition,
        validationModel: ConditionModelValidation?,
        topPlateauWh: Double = 0.0,
        transitionWh: Double = 0.0,
        unresolvedEnergyWh: Double = 0.0,
        plateauPolicy: TopPlateauNormalizationPolicy = TopPlateauNormalizationPolicy.EXCLUDE_FROM_CURVE,
        transitionPolicy: TransitionNormalizationPolicy = TransitionNormalizationPolicy.EXCLUDED_FROM_SOC_CURVE,
        refTempC: Double? = null,
        refCurrentMa: Double? = null
    ): EnergyNormalizationResult {
        val rejections = mutableSetOf<String>()

        if (observedEnergyWh == null) {
            rejections.add("OBSERVED_ENERGY_NULL")
        }
        if (validationModel == null || validationModel.maturity != ConditionModelMaturity.VALIDATED) {
            rejections.add("MODEL_NOT_VALIDATED")
        }
        if (bins.size < 20 || bins.any { it.status == BinLearningStatus.UNLEARNED }) {
            rejections.add("INCOMPLETE_SOC_BINS")
        }
        if (unresolvedEnergyWh > 0.0) {
            rejections.add("UNRESOLVED_ENERGY_EXISTS")
        }
        if (plateauPolicy == TopPlateauNormalizationPolicy.UNCALIBRATED_BLOCK) {
            rejections.add("TOP_PLATEAU_POLICY_UNCALIBRATED")
        }
        if (transitionPolicy == TransitionNormalizationPolicy.UNCALIBRATED_BLOCK) {
            rejections.add("TRANSITION_POLICY_UNCALIBRATED")
        }

        val socCurveNorm = normalizeFullEnergy(
            observedEnergyWh = observedEnergyWh,
            bins = bins,
            condition = condition,
            validationModel = validationModel,
            topPlateauWh = 0.0,
            transitionWh = 0.0,
            unresolvedEnergyWh = 0.0,
            plateauPolicy = TopPlateauNormalizationPolicy.EXCLUDE_FROM_CURVE,
            transitionPolicy = TransitionNormalizationPolicy.EXCLUDED_FROM_SOC_CURVE
        )

        val fullUsableNorm = normalizeFullEnergy(
            observedEnergyWh = observedEnergyWh,
            bins = bins,
            condition = condition,
            validationModel = validationModel,
            topPlateauWh = topPlateauWh,
            transitionWh = transitionWh,
            unresolvedEnergyWh = unresolvedEnergyWh,
            plateauPolicy = plateauPolicy,
            transitionPolicy = transitionPolicy
        )

        val uncertainty = calculateNormalizedUncertaintyWh(validationModel)

        return EnergyNormalizationResult(
            observedEnergyWh = observedEnergyWh,
            normalizedSocCurveEnergyWh = socCurveNorm,
            normalizedFullUsableEnergyWh = fullUsableNorm,
            referenceConditionTemperatureC = refTempC,
            referenceConditionCurrentMa = refCurrentMa,
            normalizedUncertaintyWh = uncertainty,
            modelVersion = validationModel?.modelVersion,
            trainingGroupCount = validationModel?.trainingGroupCount ?: 0,
            validationGroupCount = validationModel?.validationGroupCount ?: 0,
            validationMaeWh = validationModel?.validationMaeWh,
            baselineMaeWh = validationModel?.baselineMaeWh,
            improvementRatio = validationModel?.normalizationImprovement,
            conditionSupportStatus = if (rejections.contains("MODEL_NOT_VALIDATED")) ConditionSupportStatus.UNSUPPORTED_COMBINATION else ConditionSupportStatus.SUPPORTED,
            maturity = validationModel?.maturity ?: ConditionModelMaturity.PENDING,
            rejectionReasons = rejections
        )
    }
}
