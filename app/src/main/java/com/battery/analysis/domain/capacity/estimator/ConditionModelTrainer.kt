package com.battery.analysis.domain.capacity.estimator

import com.battery.analysis.domain.capacity.model.ConditionModelMaturity
import com.battery.analysis.domain.capacity.model.ConditionModelValidation
import com.battery.analysis.domain.capacity.model.DischargeConditionSample

/**
 * 放电工况条件归一化模型训练器（ConditionModelTrainer）。
 *
 * 遵循技术评审 P2-B3 核心铁律：
 * 1. 严格使用共同实测 SOC 分箱与分箱工况匹配的真实会话样本；
 * 2. 严禁使用合成数据进行训练模型成熟度晋升；
 * 3. 缺乏充分交叉覆盖组时，成熟度严格限制为 DATA_COLLECTION 或 EXPLORATORY，绝不输出预测模型。
 */
object ConditionModelTrainer {

    /**
     * 评估训练集工况样本并构建前置模型元数据。
     *
     * @param trainingSamples 真实放电会话样本列表
     * @param modelVersion 模型版本标识（默认 FCC-E-v1.0）
     * @return 条件模型验证状态实体 [ConditionModelValidation]
     */
    fun train(
        trainingSamples: List<DischargeConditionSample>,
        modelVersion: String = "FCC-E-v1.0"
    ): ConditionModelValidation {
        val matrix = ConditionSensitivityLearner.evaluateMatrix(trainingSamples)
        val groups = trainingSamples.groupBy { it.identity?.sessionGroupId ?: it.sessionId }

        return ConditionModelValidation(
            modelVersion = modelVersion,
            maturity = matrix.maturity,
            trainingGroupCount = groups.size,
            validationGroupCount = 0,
            validationMaeWh = null,
            validationRmseWh = null,
            validationBiasWh = null,
            commonSocCoverage = null,
            outOfDomainRate = null
        )
    }
}
