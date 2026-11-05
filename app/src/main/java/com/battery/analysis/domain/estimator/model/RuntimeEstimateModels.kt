package com.battery.analysis.domain.estimator.model

/**
 * 屏幕及供电状态枚举，用于区分不同的功耗工作模式。
 */
enum class ScreenPowerState {
    /** 亮屏活跃工作状态 */
    SCREEN_ON,
    /** 息屏待机状态 */
    SCREEN_OFF,
    /** 充电输入状态（充电期间不得更新放电功耗模型） */
    CHARGING
}

/**
 * 剩余续航预测采用的模型类型枚举。
 */
enum class RuntimeModelType {
    /** 仅基于当前有效周期的直接测量推算 */
    CURRENT,
    /** 基于 Fast/Medium/Long 三时间尺度自适应加权与 EWMA 融合推算 */
    MULTI_TIMESCALE,
    /** 基于亮屏与息屏解构及用户作息场景占空比融合推算 */
    GLOBAL_COMPOSITE,
    /** 采样数据缺失或不足，无法推算理论续航（界面如实展示未知占位符） */
    INSUFFICIENT_DATA
}

/**
 * 电池物理采样快照数据模型。
 * 记录单次采样周期中捕获的电荷、电压、电流、功率与时间戳等底层物理参数。
 *
 * @property timestampMs 采样时刻的系统时间戳（单位：毫秒）
 * @property voltageVolts 采样时刻的电池端电压（单位：伏特 V）
 * @property currentAmperes 采样时刻的瞬时放电电流（单位：安培 A，若不可用传入 null）
 * @property powerWatts 采样周期内的放电功率（单位：瓦特 W）
 * @property batteryLevelPercent 采样时刻的电池电量百分比（取值 0.0f ~ 100.0f）
 * @property state 采样时刻的屏幕与供电状态 [ScreenPowerState]
 * @property temperatureCelsius 采样时刻的电池温度（单位：摄氏度 ℃，可选）
 * @property monotonicTimestampNs 采样时刻的单调时钟纳秒数（防系统时间跳变，可选）
 */
data class PowerSample(
    val timestampMs: Long,
    val voltageVolts: Float,
    val currentAmperes: Float? = null,
    val powerWatts: Float,
    val batteryLevelPercent: Float,
    val state: ScreenPowerState,
    val temperatureCelsius: Float? = null,
    val monotonicTimestampNs: Long? = null
)

/**
 * 剩余续航预测算法模型版本标识枚举。
 */
enum class RemainingModelVersion {
    /** 基础基线版本：标准时间尺度加权推算 */
    REMAINING_RUNTIME_V1,
    /** 阶段二升级版本：双门限突变响应 + MAD 工况联动 + 物理可用能量四级优先级与温度修正 */
    REMAINING_RUNTIME_V2,
    /** 阶段三修正升级版本（v2.1）：严格规范单位 + 凸组合跃迁响应 + 连续权重插值 + 忠实物理直读 */
    REMAINING_RUNTIME_V2_1,
    /** 阶段四升级版本（v2.2）：自适应作息融合 + 工况突变平滑衰减 + 多尺度质量因子 + 多因素子置信度 */
    REMAINING_RUNTIME_V2_2
}

/**
 * 剩余续航预测多因素置信度细分评估实体（Confidence Breakdown）。
 * 遵循置信度与主预测时长解耦原则：反映各维度的可靠性与不确定性，绝不直接篡改剩余物理续航数值。
 *
 * @property energyConfidence 电池剩余估算可用电能来源与质量分 (0.0 ~ 1.0)
 * @property powerConfidence 放电功耗多时间尺度一致性与覆盖度分 (0.0 ~ 1.0)
 * @property stateConfidence 工况稳态与突变收敛分 (0.0 ~ 1.0)
 * @property historyConfidence 长期先验大样本充足度分 (0.0 ~ 1.0)
 * @property temperatureConfidence 环境与电芯温度适应度分 (0.0 ~ 1.0)
 * @property totalConfidence 综合多因素加权置信度分 (0.0 ~ 1.0)
 * @property isLowSocRisk 是否处于低电量非线性风险区 (SOC < 10%)
 */
data class ConfidenceBreakdown(
    val energyConfidence: Double,
    val powerConfidence: Double,
    val stateConfidence: Double,
    val historyConfidence: Double,
    val temperatureConfidence: Double,
    val totalConfidence: Double,
    val isLowSocRisk: Boolean = false
)

/**
 * 剩余续航预测输出结果实体类。
 * 封装推算的理论物理可用毫秒数、预测综合功耗、剩余物理能量、置信度指标以及工况情景假设范围。
 *
 * @property remainingMs 预测的设备物理可用剩余时间（单位：毫秒，数据不足时为 null，严禁假数据）
 * @property predictedPowerWatts 算法融合预测的未来放电平均功率（单位：瓦特 W，数据不足时为 null）
 * @property remainingEnergyWh 计算采用的电池有效剩余能量（单位：瓦时 Wh，数据不足时为 null）
 * @property confidence 预测结果的综合可信度指数（取值范围 0.0 至 1.0）
 * @property modelType 产生本次预测结果的算法模型分类 [RuntimeModelType]
 * @property sampleCount 纳入本次预测的有效物理样本总数
 * @property modelVersion 产生本次预测结果的算法模型版本 [RemainingModelVersion]
 * @property confidenceLevel 预测结果的可信度等级枚举 [RuntimeConfidenceLevel]
 * @property minRemainingMs 高负载/重载工况情景假设下的预期较短剩余时长（单位：毫秒，可选）
 * @property maxRemainingMs 轻负载/节能工况情景假设下的预期较长剩余时长（单位：毫秒，可选）
 * @property qualityReasons 预测数据质量与降级原因列表（用于解释置信度与模型依据）
 * @property unavailableReason 数据不可用时的明确物理原因（当 remainingMs 为 null 时说明原因）
 * @property confidenceBreakdown 多因素置信度细分评估实体 [ConfidenceBreakdown]（可选）
 * @property benchmarkPowerWatts 计算该剩余续航实际采用的稳态基准放电功率（单位：W，若无独立基准则为 null）
 * @property actualFastPowerWatts 当前物理采集与即时多时间尺度融合做功功率（单位：W，若无独立基准则为 null）
 * @property drainRateMultiplier 动态能耗流速倍率（即时做功功率 / 稳态基准功率，如 0.75x，若无独立基准则为 null）
 */
data class RuntimeEstimate(
    val remainingMs: Long?,
    val predictedPowerWatts: Double?,
    val remainingEnergyWh: Double?,
    val confidence: Double,
    val modelType: RuntimeModelType,
    val sampleCount: Int = 0,
    val modelVersion: RemainingModelVersion = RemainingModelVersion.REMAINING_RUNTIME_V2_2,
    val confidenceLevel: RuntimeConfidenceLevel = RuntimeConfidenceLevel.fromScore(confidence),
    val minRemainingMs: Long? = null,
    val maxRemainingMs: Long? = null,
    val qualityReasons: List<String> = emptyList(),
    val unavailableReason: String? = null,
    val confidenceBreakdown: ConfidenceBreakdown? = null,
    val benchmarkPowerWatts: Double? = null,
    val actualFastPowerWatts: Double? = null,
    val drainRateMultiplier: Double? = null
)
