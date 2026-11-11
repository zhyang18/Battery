package com.battery.analysis.domain.capacity.model

/**
 * 放电会话阶段状态机枚举。
 * 针对放电过程进行细粒度阶段划分，解耦 100% 满电平台期与有效库仑放电区间。
 */
enum class DischargeSessionPhase {
    /** 初始空闲或等待判定阶段 */
    IDLE,
    /** 100% SOC 顶部满电平台期（电量计维持 100% 不动，底层持续输出真实物理电荷） */
    TOP_PLATEAU,
    /** 100% 向首次稳定低于 100% 转换过程中的单步过渡区间（隔离边界不确定性） */
    TOP_TRANSITION,
    /** 稳定有效放电库仑积分区间（起止时间戳与 SOC 变化严格同步对齐） */
    VALID_DISCHARGE,
    /** 充电混入或数据无效阶段 */
    INVALID
}

/**
 * 100% 满电顶部平台独立度量实体。
 *
 * @property plateauMah 100% 顶部平台维持期间释放的独立物理电荷量（单位：mAh），严禁计入有效放电 FCC 计算分子
 * @property plateauWh 100% 顶部平台维持期间释放的独立物理能量（单位：Wh）
 * @property transitionMah 100% 向稳定 <100% 转换过渡区间的物理电荷量（单位：mAh），隔离边界不确定性
 * @property transitionWh 100% 向稳定 <100% 转换过渡区间的物理能量（单位：Wh）
 * @property durationSeconds 顶部满电平台维持的总秒数
 * @property sampleCount 顶部满电平台期间记录的有效物理采样点数量
 */

/**
 * SOC 边界与跳变质量枚举。
 */
enum class SocBoundaryQuality {
    /** 边界稳定平滑 */
    STABLE,
    /** 出现 100% -> 99% -> 100% 读数回弹抖动（电荷已如实吸收，标记防抖） */
    JITTER,
    /** 处于过渡区间转换 */
    TRANSITION,
    /** 出现恶性起始突跳（从 100% 突跌至 97% 以下） */
    START_JUMP,
    /** 未知或未评估 */
    UNKNOWN
}

/**
 * 细分物理维度质检评级枚举。
 */
enum class QualityLevel {
    EXCELLENT,
    GOOD,
    FAIR,
    POOR,
    INVALID
}

/**
 * 放电会话综合质检分级枚举。
 */
enum class DischargeQuality {
    HIGH,
    MEDIUM,
    LOW,
    REJECTED
}

/**
 * 电量计硬件 CHARGE_COUNTER 交叉一致性检验结果枚举。
 */
enum class CounterConsistency {
    /** 积分结果与 Counter 变化相对偏差 <= 5%，互相印证一致 */
    AGREE,
    /** 相对偏差 > 5%，出现明显分歧 */
    DIVERGE,
    /** 设备硬件不支持或未上报 Counter 读数 */
    UNAVAILABLE,
    /** 会话期间 Counter 发生回绕、清零或数据无效 */
    INVALID
}

/**
 * 电量计硬件 CHARGE_COUNTER 数据质量状态枚举。
 */
enum class CounterQuality {
    /** 正常单调变化且定期更新 */
    NORMAL,
    /** 放电已产生可观测电荷，但 Counter 长时间读数停滞未刷新 */
    STALE,
    /** 放电期间 Counter 发生异常逆向增大 */
    NON_MONOTONIC,
    /** Counter 发生大幅突变、清零或回绕 */
    RESET,
    /** 硬件不支持或未上报 */
    UNAVAILABLE
}

/**
 * 容量（FCC_Q）与能量（FCC_E）双维度解耦质检评估结果实体。
 *
 * @property fccQQuality 安时容量通道综合质量分级 [DischargeQuality]
 * @property fccEQuality 瓦时能量通道综合质量分级 [DischargeQuality]
 * @property fccQRejectionReasons 导致 FCC_Q 被排除的具体原因集合
 * @property fccERejectionReasons 导致 FCC_E 被排除的具体原因集合
 * @property counterConsistency CHARGE_COUNTER 交叉验证一致性状态 [CounterConsistency]
 * @property counterQuality CHARGE_COUNTER 硬件数据质量状态 [CounterQuality]
 * @property warningFlags 非致命警告标志集合
 */
data class CapacityQualityAssessment(
    val fccQQuality: DischargeQuality,
    val fccEQuality: DischargeQuality,
    val fccQRejectionReasons: Set<String>,
    val fccERejectionReasons: Set<String>,
    val counterConsistency: CounterConsistency,
    val counterQuality: CounterQuality,
    val warningFlags: Set<String> = emptySet()
)


data class TopPlateauMetrics(
    val plateauMah: Double = 0.0,
    val plateauWh: Double = 0.0,
    val transitionMah: Double = 0.0,
    val transitionWh: Double = 0.0,
    val durationSeconds: Long = 0L,
    val sampleCount: Int = 0,
    val boundaryQuality: SocBoundaryQuality = SocBoundaryQuality.STABLE
)

/**
 * 充电工作状态枚举。
 */
enum class ChargingStatus {
    CHARGING,
    DISCHARGING,
    FULL,
    NOT_CHARGING,
    UNKNOWN
}

/**
 * 电池物理容量边界口径枚举。
 */
enum class CapacityBoundary {
    /** 裸电芯化学全容量 */
    CELL,
    /** 电池包标称规格容量 */
    PACK,
    /** 手机系统向用户开放的工作可用区间容量（默认最现实、可验证的目标） */
    SYSTEM_USABLE,
    /** 未确认边界 */
    UNKNOWN
}

/**
 * 设计容量与实测容量边界匹配状态枚举。
 */
enum class BoundaryMatchStatus {
    /** 口径完全匹配，允许输出明确的 SOH 百分比 */
    MATCHED,
    /** 口径未确认，输出“相对设计容量比例（边界未确认）”，杜绝将系统锁容误判为电芯老化 */
    UNCONFIRMED,
    /** 口径明确不匹配 */
    MISMATCHED
}

/**
 * 容量学习成熟度状态机枚举。
 */
enum class LearningMaturity {
    /** 数据积累中，无合格样本 */
    PENDING,
    /** 少量样本形成初步估计，置信度低/中 */
    INITIAL,
    /** 多个独立会话样本自洽收敛 */
    CONVERGED,
    /** 长期持续稳定一致，覆盖主要工况 */
    STABLE,
    /** 充放电两通道差异超过各自不确定度可解释范围，暂停融合仲裁 */
    DISAGREEMENT
}

/**
 * 估计置信度等级。
 */
enum class Confidence {
    LOW,
    MEDIUM,
    HIGH
}

/**
 * 容量与能量学习通道枚举。
 */
enum class CapacityChannel {
    /** 放电主估计通道 */
    DISCHARGE,
    /** 充电辅助估计通道 */
    CHARGE,
    /** 双向鲁棒融合 */
    FUSION,
    /** 独立能量通道 */
    ENERGY
}

/**
 * 满充端点质量评级枚举。
 */
enum class FullEndpointQuality {
    /** 优秀：电压达标、电流进入末期锥形递减、系统状态转 FULL */
    EXCELLENT,
    /** 良好：具备明确的电量计满充标识或平稳末期 */
    GOOD,
    /** 一般：仅有系统 status=FULL，无精细电流/电压终止标志 */
    FAIR,
    /** 较差：充途中拔出，端点不稳定 */
    POOR,
    /** 未知/未满充 */
    UNKNOWN
}

/**
 * 统一标准化实时采样点。
 *
 * @property timestampMs 单调或绝对时间戳（ms）
 * @property socFraction 0.0 ~ 1.0 的 SOC 比例值
 * @property currentAmp 电池端标准化净电流（A）：放电时为正 I_discharge > 0，充电时为正 I_charge > 0
 * @property voltageVolt 电池端标准化电压（V）
 * @property temperatureC 电芯温度（℃）
 * @property isScreenOn 是否亮屏
 * @property chargingStatus 当前充电状态
 * @property currentSource 电流读取源标识
 * @property socSource SOC 读取源标识
 */
data class CapacitySample(
    val timestampMs: Long,
    val socFraction: Double?,
    val currentAmp: Double?,
    val voltageVolt: Double?,
    val temperatureC: Double?,
    val isScreenOn: Boolean = false,
    val chargingStatus: ChargingStatus = ChargingStatus.UNKNOWN,
    val currentSource: String = "",
    val socSource: String = "",
    val chargeCounterUah: Long? = null
)

/**
 * 梯形微积分切段聚合结果。
 */
data class IntegratedSegment(
    val chargeMah: Double,
    val energyWh: Double,
    val validDurationSec: Double,
    val expectedDurationSec: Double,
    val maxGapSec: Double,
    val coverageRatio: Double,
    val avgVoltageVolts: Double? = null,
    val avgTemperatureC: Double? = null
)

/**
 * 单次充放电会话生成的容量候选样本实体。
 *
 * @property sessionId 唯一会话 ID
 * @property channel 容量学习通道
 * @property startSoc 会话有效起始 SOC（0.0 ~ 1.0）
 * @property endSoc 会话有效结束 SOC（0.0 ~ 1.0）
 * @property deltaSoc 会话有效 SOC 跨度（0.0 ~ 1.0）
 * @property chargeMah 有效区间测得的净物理电荷量（mAh）
 * @property energyWh 有效区间测得的物理能量（Wh）
 * @property fccObservedMah 本次会话推算出的满充容量单点观测值（mAh）
 * @property varianceMah2 基于一阶泰勒展开误差传递的方差评估值（mAh^2）
 * @property avgVoltageVolts 平均工作电压（V）
 * @property avgTemperatureC 加权平均温度（℃）
 * @property maxTemperatureC 最高温度（℃）
 * @property durationSec 有效积分持续秒数
 * @property coverageRatio 采样时间覆盖率（0.0 ~ 1.0）
 * @property endpointQuality 满充端点质量评级
 * @property isExcluded 是否被质检过滤器或离群点算法排除
 * @property exclusionReason 被排除的具体原因说明
 * @property topPlateau 100% 满电顶部平台指标（若存在，放电通道专用）
 */
data class FccCandidateSample(
    val sessionId: String,
    val channel: CapacityChannel,
    val startSoc: Double,
    val endSoc: Double,
    val deltaSoc: Double,
    val chargeMah: Double,
    val energyWh: Double,
    val fccObservedMah: Double,
    val varianceMah2: Double,
    val avgVoltageVolts: Double? = null,
    val avgTemperatureC: Double? = null,
    val maxTemperatureC: Double? = null,
    val durationSec: Double = 0.0,
    val coverageRatio: Double = 1.0,
    val endpointQuality: FullEndpointQuality = FullEndpointQuality.UNKNOWN,
    val isExcluded: Boolean = false,
    val exclusionReason: String = "",
    val topPlateau: TopPlateauMetrics? = null,
    val qualityLevel: DischargeQuality = DischargeQuality.LOW,
    val counterConsistency: CounterConsistency = CounterConsistency.UNAVAILABLE,
    val counterQuality: CounterQuality = CounterQuality.UNAVAILABLE,
    val effectiveIntervalCoverage: Double = 1.0,
    val topPlateauCoverage: Double = 1.0,
    val wholeSessionCoverage: Double = 1.0,
    val isEnergyExcluded: Boolean = false,
    val energyExclusionReason: String = "",
    val fccEQuality: DischargeQuality = DischargeQuality.LOW,
    val operatingCondition: DischargeOperatingCondition? = null
)

/**
 * SOC 能量分箱学习状态枚举。
 */
enum class BinLearningStatus {
    /** 未学习（无实测数据或覆盖跨度极微，严禁填 0 或脑补伪造数值） */
    UNLEARNED,
    /** 部分学习（已观测放电做功，但有效实测 SOC 覆盖跨度不足，不确定度较高） */
    PARTIAL,
    /** 充分学习（已累计足够跨度且具备闭合梯形做功的纯实测数据） */
    MEASURED,
    /** 基于电化学模型/先验曲线辅助估计（保留扩展） */
    MODEL_ESTIMATED
}

/**
 * 分箱分配质量与证据类型枚举。
 */
enum class BinAllocationQuality {
    /** 同 Bin 内部且具备明确物理台阶下降的直接实测分配 */
    DIRECT_MEASURED,
    /** 靠近分箱边缘，存在整数 SOC 量化不确定性 */
    BOUNDARY_AMBIGUOUS,
    /** 跨多个分箱边界的梯形切分与比例分配（模型估算） */
    MODEL_ESTIMATED,
    /** SOC 假死停滞超标或未知物理归属 */
    UNRESOLVED
}

/**
 * 能量跨区间分配与 SOC 停滞质量评级枚举。
 */
enum class EnergyAllocationQuality {
    /** 分配正常（单步在箱内或平滑跨边界切分） */
    NORMAL,
    /** 跨边界多箱精确分配 */
    CROSS_BOUNDARY_SPLIT,
    /** SOC 长期未刷新导致放电做功积累超标，判定为未决状态（UNRESOLVED），做功保留并记入未决段 */
    UNRESOLVED_SOC_STALL,
    /** SOC 异常倒流/回弹 */
    SOC_REGRESSION
}

/**
 * 未知归属或 SOC 停滞异常能量段实体。
 *
 * @property measuredEnergyWh 实际测得的物理放电做功（Wh）
 * @property measuredChargeMah 实际测得的物理放电电荷（mAh）
 * @property startTimestamp 起始时间戳（ms）
 * @property endTimestamp 结束时间戳（ms）
 * @property startSoc 起始 SOC（%）
 * @property endSoc 结束 SOC（%）
 * @property reason 未能归档到具体分箱的原因（如 SOC 假死停滞）
 */
data class UnresolvedEnergySegment(
    val measuredEnergyWh: Double,
    val measuredChargeMah: Double,
    val startTimestamp: Long,
    val endTimestamp: Long,
    val startSoc: Double?,
    val endSoc: Double?,
    val reason: String
)

/**
 * SOC 能量曲线综合容量学习与统一能量账本结果实体。
 *
 * 遵循能量守恒账本规范：各部分物理做功互不重叠，满足严格守恒闭环：
 * totalObservedEnergyWh = measuredBinsEnergyWh + estimatedBinsEnergyWh + topPlateauEnergyWh + transitionEnergyWh + unresolvedEnergyWh
 *
 * @property totalObservedEnergyWh 全局已观测物理能量总账本（Wh）
 * @property measuredBinsEnergyWh 20 段分箱纯物理实测标准做功总和（Wh）
 * @property estimatedBinsEnergyWh 20 段分箱跨箱估算标准做功总和（Wh）
 * @property topPlateauEnergyWh 100% 满电平台维持期间释放的独立物理能量（Wh）
 * @property transitionEnergyWh 100% -> 99% 边界过渡期间释放的独立物理能量（Wh）
 * @property unresolvedEnergyWh 单点停滞超标或未知归属的物理能量（Wh）
 * @property estimatedMissingEnergyWh 缺失区间的预估能量（若不可用则为 null）
 * @property estimatedFullEnergyWh 全量可用能量（全域充分实测时输出，未学满时为 null）
 * @property coveragePercent 充分实测覆盖百分比（0.0 ~ 100.0%）
 * @property measuredBinCount 达到 MEASURED 充分实测的分箱数量 (0..20)
 * @property estimatedBinCount 包含 MODEL_ESTIMATED 估算的分箱数量 (0..20)
 * @property unlearnedBinCount 处于 UNLEARNED 未学习状态的分箱数量 (0..20)
 * @property uncertaintyWh 能量不确定度（Wh）
 * @property maturity 能量通道成熟度状态
 * @property observedEnergyWh 兼容旧接口别名，等价于 totalObservedEnergyWh
 * @property measuredEnergyWh 兼容旧接口别名，等价于 measuredBinsEnergyWh
 */
data class EnergyCapacityResult(
    val totalObservedEnergyWh: Double,
    val measuredBinsEnergyWh: Double,
    val estimatedBinsEnergyWh: Double = 0.0,
    val topPlateauEnergyWh: Double = 0.0,
    val transitionEnergyWh: Double = 0.0,
    val unresolvedEnergyWh: Double = 0.0,
    val estimatedMissingEnergyWh: Double? = null,
    val estimatedFullEnergyWh: Double? = null,
    val coveragePercent: Double,
    val measuredBinCount: Int,
    val estimatedBinCount: Int,
    val unlearnedBinCount: Int,
    val uncertaintyWh: Double? = null,
    val maturity: LearningMaturity,
    val observedEnergyWh: Double = totalObservedEnergyWh,
    val measuredEnergyWh: Double = measuredBinsEnergyWh
)

/**
 * 单个 5% SOC 能量分箱信息（0%~100% 共 20 段）。
 *
 * 遵循技术评审规范：纯实测能量与模型估算能量严格分开存储，严禁混淆证据来源。
 *
 * @property binIndex 分箱序号 (0..19)
 * @property startSocPercent 起始 SOC 百分比（例如 0.0, 5.0, ...）
 * @property endSocPercent 结束 SOC 百分比（例如 5.0, 10.0, ...）
 * @property chargeMah 标准化到完整 5% 分箱的总物理电荷量（实测+估算，mAh）
 * @property energyWh 标准化到完整 5% 分箱的总物理能量（实测+估算，Wh）
 * @property avgVoltageVolts 分箱内加权平均电压（V）
 * @property avgTemperatureC 分箱内加权平均温度（℃）
 * @property sampleCount 吸收的有效采样会话/采样段计数
 * @property isLearned 是否已完成充分学习（严格等价于 status == MEASURED）
 * @property status 分箱学习状态（[BinLearningStatus.UNLEARNED], [BinLearningStatus.PARTIAL], [BinLearningStatus.MEASURED], [BinLearningStatus.MODEL_ESTIMATED]）
 * @property measuredSpanPercent 该分箱内纯物理实测的有效 SOC 覆盖跨度百分比（0.0 ~ 5.0）
 * @property rawEnergyWh 该分箱实际测得的未归一化物理做功总和（Wh）
 * @property measuredEnergyWh 纯物理实测标准归一化能量（Wh），仅由同 Bin 直接实测累加
 * @property estimatedEnergyWh 跨分箱插值或比例切分的模型估算归一化能量（Wh）
 * @property measuredChargeMah 纯物理实测电荷量（mAh）
 * @property estimatedChargeMah 模型估算电荷量（mAh）
 * @property estimatedSpanPercent 跨分箱估算的 SOC 覆盖跨度百分比（0.0 ~ 5.0）
 * @property unresolvedEnergyWh 该分箱停滞未决的物理能量（Wh）
 * @property measuredSessionCount 纯物理实测会话计数
 * @property estimatedSessionCount 模型估算会话计数
 * @property estimatedEnergySigmaWh 模型估算能量不确定度标准差（Wh，若无严密统计依据则保持为 null）
 * @property allocationMethod 能量分配方法标识（如 DIRECT_MEASURED, CROSS_BIN_INTERPOLATION）
 * @property allocationAlgorithmVersion 分箱分配算法版本标识（如 FCC-v1.1）
 */
data class SocEnergyBinInfo(
    val binIndex: Int,
    val startSocPercent: Double,
    val endSocPercent: Double,
    val chargeMah: Double = 0.0,
    val energyWh: Double = 0.0,
    val avgVoltageVolts: Double? = null,
    val avgTemperatureC: Double? = null,
    val sampleCount: Int = 0,
    val isLearned: Boolean = false,
    val status: BinLearningStatus = if (isLearned) BinLearningStatus.MEASURED else BinLearningStatus.UNLEARNED,
    val measuredSpanPercent: Double = 0.0,
    val rawEnergyWh: Double = energyWh,
    val measuredEnergyWh: Double = if (status == BinLearningStatus.MEASURED) energyWh else 0.0,
    val estimatedEnergyWh: Double = if (status == BinLearningStatus.MODEL_ESTIMATED) energyWh else 0.0,
    val measuredChargeMah: Double = if (status == BinLearningStatus.MEASURED) chargeMah else 0.0,
    val estimatedChargeMah: Double = if (status == BinLearningStatus.MODEL_ESTIMATED) chargeMah else 0.0,
    val estimatedSpanPercent: Double = 0.0,
    val unresolvedEnergyWh: Double = 0.0,
    val measuredSessionCount: Int = if (status == BinLearningStatus.MEASURED) sampleCount else 0,
    val estimatedSessionCount: Int = if (status == BinLearningStatus.MODEL_ESTIMATED) sampleCount else 0,
    val estimatedEnergySigmaWh: Double? = null,
    val allocationMethod: String? = null,
    val allocationAlgorithmVersion: String = "FCC-v1.1",
    val binCondition: SocBinOperatingCondition? = null
) {
    /**
     * 获取分箱区间格式化标签。
     *
     * @return 形如 "0% - 5%" 的区间描述字符串
     */
    fun getLabel(): String = String.format(java.util.Locale.US, "%.0f%% - %.0f%%", startSocPercent, endSocPercent)
}

/**
 * FCC_Q 单通道质量准入与估计输入数据结构。
 *
 * @property channel 估计通道（放电主估计或充电辅助估计）
 * @property fccMah 推算出的单通道容量观测值（mAh）
 * @property uncertaintyMah 误差不确定度（mAh）
 * @property sampleCount 有效候选样本数
 * @property cumulativeSocSpan 累计有效 SOC 跨度（0.0 ~ 1.0）
 * @property maturity 通道成熟度
 * @property quality 综合物理质量评级
 * @property isCalibrated 库仑效率或端点是否完成标定
 */
data class FccQChannelResult(
    val channel: CapacityChannel,
    val fccMah: Double?,
    val uncertaintyMah: Double?,
    val sampleCount: Int,
    val cumulativeSocSpan: Double,
    val maturity: LearningMaturity,
    val quality: DischargeQuality = DischargeQuality.MEDIUM,
    val isCalibrated: Boolean = false
)

/**
 * 单通道独立估计输出。
 */
data class FccChannelEstimate(
    val channel: CapacityChannel,
    val fccQMah: Double?,
    val uncertaintyMah: Double?,
    val sampleCount: Int,
    val totalSocSpan: Double,
    val avgTemperatureC: Double? = null,
    val confidence: Confidence = Confidence.LOW,
    val apparentOnly: Boolean = false,
    val endpointQuality: FullEndpointQuality? = null,
    val details: String = ""
)

/**
 * FCC 充放电双向容量学习与 SOH 估算最终统一输出实体。
 */
data class FccUnifiedEstimate(
    val fccQMah: Double?,
    val fccQUncertaintyMah: Double?,
    val fccEWh: Double?,
    val fccEUncertaintyWh: Double?,
    val sohQPercent: Double?,
    val sohEPercent: Double?,
    val remainingQMah: Double?,
    val remainingEWh: Double?,
    val currentSocPercent: Int?,
    val confidence: Confidence,
    val maturity: LearningMaturity,
    val boundary: CapacityBoundary = CapacityBoundary.SYSTEM_USABLE,
    val boundaryMatchStatus: BoundaryMatchStatus = BoundaryMatchStatus.UNCONFIRMED,
    val designCapacityMah: Double? = null,
    val designEnergyWh: Double? = null,
    val dischargeChannel: FccChannelEstimate? = null,
    val chargeChannel: FccChannelEstimate? = null,
    val energyBins: List<SocEnergyBinInfo> = emptyList(),
    val energyCurveCoverage: Double = 0.0,
    val channelConflictPercent: Double? = null,
    val isSameGaugeSource: Boolean = true,
    val algorithmVersion: String = "FCC-v1.1",
    val updatedTimestampMs: Long = System.currentTimeMillis(),
    val energyCapacityResult: EnergyCapacityResult? = null,
    val isFusionFrozen: Boolean = false,
    val fusionFreezeReason: String? = null,
    val previousAcceptedFccQMah: Double? = null,
    val acceptedFccQ: AcceptedFccQ? = null,
    val energyTriad: EnergyCapacityTriad? = null
)

/**
 * 已确认收敛的历史可信满充容量实体（具备完备算法版本与审计追溯）。
 *
 * 遵循技术评审 P2-A 核心准则：历史可信值不仅是一个孤立数字，还必须附带版本、
 * 样本数、成熟度及采纳证据说明，算法升级时绝不静默覆盖原可信容量。
 *
 * @property fccMah 已确认的可信 FCC 容量（mAh）
 * @property uncertaintyMah 容量评估合成不确定度标准差（mAh）
 * @property algorithmVersion 生成该可信容量的算法版本（如 "FCC-v1.1"）
 * @property dataSchemaVersion 对应的数据结构版本号（如 3）
 * @property acceptedTimestampMs 确认采纳的时间戳（ms）
 * @property maturity 当时达到的成熟度状态 [LearningMaturity]
 * @property sampleCount 参与该估计的有效样本数
 * @property cumulativeSocSpan 累计支撑该估计的 SOC 跨度
 * @property acceptanceReason 正式采纳的物理证据与仲裁原因
 */
data class AcceptedFccQ(
    val fccMah: Double,
    val uncertaintyMah: Double?,
    val algorithmVersion: String,
    val dataSchemaVersion: Int,
    val acceptedTimestampMs: Long,
    val maturity: LearningMaturity,
    val sampleCount: Int = 0,
    val cumulativeSocSpan: Double = 0.0,
    val acceptanceReason: String = ""
)

/**
 * 放电热环境区间枚举（基于实测电池温度划分，纯分类标记，严禁未经检验前引入人为补偿）。
 */
enum class DischargeThermalBand {
    /** 低温工况（< 15℃） */
    LOW_TEMP,
    /** 常温工况（15℃ ~ 35℃，标准参考基准区间） */
    ROOM_TEMP,
    /** 高温工况（> 35℃） */
    HIGH_TEMP,
    /** 未知或缺失温度传感器读数 */
    UNKNOWN
}

/**
 * 放电热环境剖面类型枚举（区分单温区纯净会话与跨温区混合会话）。
 */
enum class ThermalProfileType {
    /** 纯净温区（单一温区有效时间占比 >= 85%） */
    PURE,
    /** 混合温区（跨越低温、常温或高温区间） */
    MIXED,
    /** 未知/温度缺失 */
    UNKNOWN
}

/**
 * 放电负载与倍率区间枚举（基于实测 mA 电流划分，杜绝虚构未校准 C-rate）。
 */
enum class DischargeLoadBand {
    /** 轻负载（平均放电电流 < 500 mA，待机/轻度息屏/微功耗） */
    LIGHT,
    /** 中等负载（平均放电电流 500 mA ~ 1500 mA，日常亮屏操作） */
    MODERATE,
    /** 重负载（平均放电电流 > 1500 mA，高亮/重度游戏/高负荷） */
    HEAVY,
    /** 未知或电流缺失 */
    UNKNOWN
}

/**
 * 放电负载剖面类型枚举（区分单负载纯净会话与脉冲混合会话）。
 */
enum class LoadProfileType {
    /** 纯净负载（单一负载区间有效时间占比 >= 85%） */
    PURE,
    /** 混合负载（兼有轻载、中载或脉冲重负载） */
    MIXED,
    /** 未知/电流缺失 */
    UNKNOWN
}

/**
 * 负载阶跃引起的工作电压跌落与表观动态内阻度量实体。
 *
 * 遵循评审规范：严格区分观测电压落差与纯欧姆内阻，
 * 仅在具备清晰电流阶跃（ΔI >= 200mA）且阶跃迅速时计算表观动态电阻 R_app = 1000 * ΔV(mV) / ΔI(mA)，
 * 否则表观电阻如实为 null，绝不冒充电池真实内部阻抗。
 *
 * @property observedVoltageDropMv 观测到的局部最大电压跌落幅值（mV）
 * @property deltaCurrentMa 对应触发电压跌落的瞬时放电电流增量阶跃（mA）
 * @property eventDurationMs 电压跌落观测时间窗口持续时长（ms）
 * @property apparentResistanceMilliOhm 估算的动态表观电阻（mΩ），条件不足时严格为 null
 * @property measurementQuality 阶跃与压降物理测量质量评级
 */
data class VoltageSagMetrics(
    val observedVoltageDropMv: Double?,
    val deltaCurrentMa: Double? = null,
    val eventDurationMs: Long? = null,
    val apparentResistanceMilliOhm: Double? = null,
    val measurementQuality: QualityLevel = QualityLevel.GOOD
)

/**
 * 单次放电会话的底层物理热环境与负载工况全景特征实体。
 *
 * 遵循技术评审 P2-B 核心准则：真实记录客观放电条件，杜绝粗暴常数折算，
 * 严禁将温度或负载导致的暂时性可用能量波动误判为电池老化。
 *
 * @property startTemperatureC 起始电池温度（℃）
 * @property endTemperatureC 结束电池温度（℃）
 * @property averageTemperatureC 时间加权平均电池温度（℃，以电池侧实测温度为准，杜绝拿 CPU/SoC 温度替代）
 * @property medianTemperatureC 电池温度中位数（℃）
 * @property temperatureP10C 电池温度 10 分位数 P10（℃，时间加权）
 * @property temperatureP50C 电池温度 50 分位数 P50（℃，时间加权）
 * @property temperatureP90C 电池温度 90 分位数 P90（℃，时间加权）
 * @property temperatureTimeIntegralCs 电池温度对时间的微分积分值（℃·s）
 * @property temperatureValidDurationSeconds 有效温度统计总时长（秒，已扣除采样断层）
 * @property temperatureCoverageRatio 温度有效时间覆盖率（0.0 ~ 1.0）
 * @property thermalBand 热环境分类 [DischargeThermalBand]
 * @property lowTempDurationRatio 低温持续时间占比（0.0 ~ 1.0）
 * @property roomTempDurationRatio 常温持续时间占比（0.0 ~ 1.0）
 * @property highTempDurationRatio 高温持续时间占比（0.0 ~ 1.0）
 * @property thermalProfile 热环境剖面类型 [ThermalProfileType]
 *
 * @property averageDischargeCurrentMa 时间加权平均物理放电电流（mA）
 * @property medianDischargeCurrentMa 放电电流中位数（mA）
 * @property currentP50Ma 放电电流时间加权 50 分位数（mA）
 * @property p95DischargeCurrentMa 放电电流时间加权 95 分位数 P95（mA）
 * @property peakDischargeCurrentMa 最大峰值放电电流（mA）
 * @property lightLoadDurationRatio 轻载持续时间占比（0.0 ~ 1.0）
 * @property moderateLoadDurationRatio 中载持续时间占比（0.0 ~ 1.0）
 * @property heavyLoadDurationRatio 重载持续时间占比（0.0 ~ 1.0）
 * @property loadProfile 负载剖面类型 [LoadProfileType]
 * @property loadBand 负载分类 [DischargeLoadBand]
 *
 * @property averageDischargePowerW 时间加权平均物理放电功率（W，由瞬时 V×I 积分计算）
 * @property medianDischargePowerW 放电功率中位数（W）
 * @property p95DischargePowerW 放电功率 95 分位数 P95（W）
 * @property peakDischargePowerW 最大峰值放电功率（W）
 *
 * @property voltageSagMv 最大负载或极化电压跌落深度（mV）
 * @property voltageSagMetrics 两层电压压降与表观电阻度量 [VoltageSagMetrics]
 * @property dischargeDurationSeconds 放电有效持续总秒数
 */
data class DischargeOperatingCondition(
    val startTemperatureC: Double? = null,
    val endTemperatureC: Double? = null,
    val averageTemperatureC: Double? = null,
    val medianTemperatureC: Double? = null,
    val temperatureP10C: Double? = null,
    val temperatureP50C: Double? = medianTemperatureC,
    val temperatureP90C: Double? = null,
    val temperatureTimeIntegralCs: Double? = null,
    val temperatureValidDurationSeconds: Long = 0L,
    val temperatureCoverageRatio: Double = 1.0,
    val thermalBand: DischargeThermalBand = DischargeThermalBand.UNKNOWN,
    val lowTempDurationRatio: Double = 0.0,
    val roomTempDurationRatio: Double = 0.0,
    val highTempDurationRatio: Double = 0.0,
    val dominantThermalBandRatio: Double = 0.0,
    val thermalProfile: ThermalProfileType = ThermalProfileType.UNKNOWN,

    val averageDischargeCurrentMa: Double? = null,
    val medianDischargeCurrentMa: Double? = null,
    val currentP50Ma: Double? = medianDischargeCurrentMa,
    val p95DischargeCurrentMa: Double? = null,
    val peakDischargeCurrentMa: Double? = null,
    val lightLoadDurationRatio: Double = 0.0,
    val moderateLoadDurationRatio: Double = 0.0,
    val heavyLoadDurationRatio: Double = 0.0,
    val dominantLoadBandRatio: Double = 0.0,
    val loadProfile: LoadProfileType = LoadProfileType.UNKNOWN,
    val loadBand: DischargeLoadBand = DischargeLoadBand.UNKNOWN,

    val averageDischargePowerW: Double? = null,
    val medianDischargePowerW: Double? = null,
    val p95DischargePowerW: Double? = null,
    val peakDischargePowerW: Double? = null,

    val voltageSagMv: Double? = null,
    val voltageSagMetrics: VoltageSagMetrics? = null,
    val dischargeDurationSeconds: Long = 0L,
    val energyAlignedDurationSeconds: Long? = null,
    val energyAlignedCoverageRatio: Double? = null
)

/**
 * 满充能量容量（FCC_E）的三态模型解耦实体。
 *
 * 遵循技术评审 P2-B 核心准则：区分当前工况直接测量能量、模型归一化能量与基准能量，
 * 在没有经过充分数据验证的温度/负载模型前，归一化与基准能量如实为 null，绝不虚构数字。
 * 同时严格解耦纯 SOC 曲线归一化能量（[normalizedSocCurveEnergyWh]）与拔电满电完整可用能量（[normalizedFullUsableEnergyWh]）。
 *
 * @property observedEnergyWh 本次有效物理区间实际直接积分观测到的总能量（Wh，只要有积分就不为 null）
 * @property measuredEnergyWh 满足全域 20 段完备直接实测条件的实测 FCC_E（Wh，未学满时为 null）
 * @property normalizedEnergyWh 经充分验证的温度和负载敏感度模型归一化到标准参考条件的能量（Wh，无可靠模型时为 null，等价于 normalizedSocCurveEnergyWh）
 * @property referenceEnergyWh 在标准参考工况（如 25℃、中等负载）下建立的基准能量（Wh，未建立基准前为 null）
 * @property normalizationModelConfidence 归一化模型置信度（0.0 ~ 1.0，未建立模型时为 null）
 * @property conditionProfile 对应的放电工况特征 [DischargeOperatingCondition]
 * @property normalizedSocCurveEnergyWh 仅代表 20 个 SOC 分箱的标准工况能量（Wh，不含顶部平台和过渡段）
 * @property normalizedFullUsableEnergyWh 完整拔电可用能量反事实归一化估计（Wh，包含经政策处理的顶部平台与过渡做功）
 * @property normalizationResult 完整的归一化结果与审计实体 [EnergyNormalizationResult]
 */
data class EnergyCapacityTriad(
    val observedEnergyWh: Double? = null,
    val measuredEnergyWh: Double?,
    val normalizedEnergyWh: Double? = null,
    val referenceEnergyWh: Double? = null,
    val normalizationModelConfidence: Double? = null,
    val conditionProfile: DischargeOperatingCondition? = null,
    val normalizedSocCurveEnergyWh: Double? = normalizedEnergyWh,
    val normalizedFullUsableEnergyWh: Double? = null,
    val normalizationResult: EnergyNormalizationResult? = null
)

/**
 * 工况敏感度模型成熟度状态机枚举。
 */
enum class ConditionModelMaturity {
    /** 尚无有效会话样本 */
    PENDING,
    /** 正在积累不同温度与负载区间的会话数据 */
    DATA_COLLECTION,
    /** 具备初步多工况分布，仅供统计观察，严禁用于容量校正 */
    EXPLORATORY,
    /** 正在通过留一法或时序独立会话验证模型泛化能力 */
    VALIDATING,
    /** 经过验证并在已覆盖工况内具备高置信度，允许输出归一化结果 */
    VALIDATED,
    /** 当前会话工况超出模型有效训练范围，严禁外推归一化 */
    OUT_OF_DOMAIN
}

/**
 * 单条完整放电会话的独立工况学习样本实体。
 *
 * 铁律：一条完整放电会话严格作为一条独立样本，严禁单会话切碎虚标样本量。
 *
 * @property sessionId 独立放电会话 ID
 * @property observedEnergyWh 本次有效物理区间实际观测能量（Wh）
 * @property measuredEnergyCoverage 分箱实测覆盖百分比（0.0 ~ 100.0%）
 * @property unresolvedEnergyWh SOC 假死停滞未决能量（Wh）
 * @property condition 工况全景特征实体 [DischargeOperatingCondition]
 * @property startSoc 起始 SOC（%）
 * @property endSoc 结束 SOC（%）
 * @property socSpanPercent SOC 变化跨度（%）
 * @property quality 放电质检质量评级 [DischargeQuality]
 * @property counterConsistency 电量计硬件 Counter 印证评级 [CounterConsistency]
 * @property cycleCount 电池当前循环计数（若有）
 * @property sessionTimestampMs 会话时间戳（ms）
 * @property effectiveStartTimestampMs 有效积分起始时间戳（ms）
 * @property effectiveEndTimestampMs 有效积分结束时间戳（ms）
 * @property topPlateauMah 100% 满电平台电荷（mAh）
 * @property topPlateauWh 100% 满电平台能量（Wh）
 * @property algorithmVersion 算法版本标识
 * @property dataSchemaVersion 数据结构版本
 */
data class DischargeConditionSample(
    val sessionId: String,
    val observedEnergyWh: Double?,
    val measuredEnergyCoverage: Double,
    val unresolvedEnergyWh: Double,
    val condition: DischargeOperatingCondition,
    val startSoc: Int,
    val endSoc: Int,
    val socSpanPercent: Int,
    val quality: DischargeQuality,
    val counterConsistency: CounterConsistency,
    val cycleCount: Int? = null,
    val sessionTimestampMs: Long = System.currentTimeMillis(),
    val effectiveStartTimestampMs: Long = sessionTimestampMs,
    val effectiveEndTimestampMs: Long = sessionTimestampMs,
    val topPlateauMah: Double = 0.0,
    val topPlateauWh: Double = 0.0,
    val algorithmVersion: String = "FCC-v1.1",
    val dataSchemaVersion: Int = 3,
    val identity: ConditionSampleIdentity? = null,
    val binMeasuredEnergiesWh: Map<Int, Double> = emptyMap(),
    val binLearningStatuses: Map<Int, BinLearningStatus> = emptyMap(),
    val binConditions: Map<Int, SocBinOperatingCondition> = emptyMap(),
    val isReferenceSession: Boolean = false,
    val referenceProtocol: EnergyReferenceProtocol? = null
)

/**
 * 单个 5% SOC 能量分箱内部直接测得的物理热环境与负载工况特征实体。
 *
 * 遵循技术评审 P2-B3 核心准则：不仅能量要按共同 MEASURED 分箱比对，
 * 每个分箱的温度、电流、功率和采样覆盖率也必须独立记录并同步对齐，
 * 彻底杜绝全局平均相似但局部区间工况相反的虚假匹配。
 *
 * @property binIndex 分箱索引 (0..19)
 * @property averageTemperatureC 分箱内时间加权平均电池温度（℃）
 * @property temperatureP50C 分箱内电池温度中位数（℃）
 * @property temperatureP90C 分箱内电池温度 90 分位数（℃）
 * @property averageCurrentMa 分箱内时间加权平均放电电流（mA）
 * @property currentP95Ma 分箱内放电电流 95 分位数（mA）
 * @property averagePowerW 分箱内时间加权平均做功功率（W）
 * @property powerP95W 分箱内做功功率 95 分位数（W）
 * @property validDurationSeconds 分箱内有效物理采样持续秒数
 * @property physicalCoverageRatio 分箱内有效物理采样覆盖率（0.0 ~ 1.0）
 */
data class SocBinOperatingCondition(
    val binIndex: Int,
    val averageTemperatureC: Double?,
    val temperatureP50C: Double? = averageTemperatureC,
    val temperatureP90C: Double? = null,
    val averageCurrentMa: Double?,
    val currentP95Ma: Double? = null,
    val averagePowerW: Double? = null,
    val powerP95W: Double? = null,
    val validDurationSeconds: Long = 0L,
    val physicalCoverageRatio: Double = 1.0
)

/**
 * 放电工况样本物理身份与分层溯源实体（Sample / Battery / Device / Session Group）。
 *
 * 遵循技术评审 P2-B2.1 统计原则：
 * 明确区分单次放电会话（Sample）、同一块电芯（Battery）、物理设备（Device）与关联会话组（Session Group），
 * 绝不能把同一电池在连续时间内的多次重复测量简单等同于统计独立的物理实验。
 *
 * @property sessionId 独立放电会话唯一标识
 * @property deviceId 设备匿名内部标识符
 * @property batteryIdentity 电池内部物理身份标识（若可获取）
 * @property sessionGroupId 关联会话组标识（按实验周期、自然周或循环批次聚类）
 * @property sessionStartTimestamp 会话开始绝对时间戳（ms）
 * @property sessionEndTimestamp 会话结束绝对时间戳（ms）
 * @property cycleCount 电池当前循环次数
 * @property calendarAgeDays 电池日历寿命天数
 */
data class ConditionSampleIdentity(
    val sessionId: String,
    val deviceId: String = "local_device",
    val batteryIdentity: String? = null,
    val sessionGroupId: String? = null,
    val sessionStartTimestamp: Long = 0L,
    val sessionEndTimestamp: Long = 0L,
    val cycleCount: Int? = null,
    val calendarAgeDays: Double? = null
)

/**
 * SOC 共同实测区间重叠状态枚举。
 */
enum class SocOverlapStatus {
    /** 共同实测 SOC 跨度充分（>= 门限如 20%），允许进行条件能量敏感度比较 */
    SUFFICIENT,
    /** 共同覆盖跨度不足，坚决不直接比较整条会话能量 */
    INSUFFICIENT_SOC_OVERLAP,
    /** 无共同实测分箱区间 */
    NO_OVERLAP
}

/**
 * 共同实测 SOC 分箱覆盖的连续性与区间分段度量实体。
 *
 * 遵循技术评审 P2-B3 核心准则：不仅统计总覆盖跨度，还必须检验分箱在物理 SOC 轴上的连续性，
 * 严禁将离散孤岛分箱（如 10%~15% 与 80%~85%）伪装成连续放电区间。
 *
 * @property commonBinIndices 共同实测分箱索引集合
 * @property commonBinCount 共同分箱总数
 * @property totalCommonCoveragePercent 共同覆盖累计 SOC 百分比跨度（如 40.0%）
 * @property maxContiguousCoveragePercent 最大连续覆盖 SOC 百分比跨度（如 30.0%）
 * @property contiguousSegments 连续分段的区间范围列表（如 [1..3, 10..12]）
 * @property isContiguous 是否构成单一连续区间
 */
data class CommonSocCoverage(
    val commonBinIndices: Set<Int>,
    val commonBinCount: Int,
    val totalCommonCoveragePercent: Double,
    val maxContiguousCoveragePercent: Double,
    val contiguousSegments: List<ClosedRange<Int>>,
    val isContiguous: Boolean
)

/**
 * 两个放电会话在共同实测 SOC 分箱区间的严谨物理能量比对结果实体。
 *
 * 遵循评审铁律：工况敏感度学习优先比较共同 SOC 分箱，严禁直接拿不同起点终点的总 Wh 相减。
 *
 * @property sessionAId 会话 A ID
 * @property sessionBId 会话 B ID
 * @property commonMeasuredBinIndices 双方皆为纯实测（MEASURED）的共同分箱索引列表
 * @property commonSocCoveragePercent 共同实测的累计 SOC 百分比跨度（如 45.0%）
 * @property commonMeasuredEnergyWhA 会话 A 在共同分箱内的纯实测物理能量总和（Wh）
 * @property commonMeasuredEnergyWhB 会话 B 在共同分箱内的纯实测物理能量总和（Wh）
 * @property energyDeltaWh 共同分箱能量差值 (B - A)
 * @property energyDeltaPercent 相对差异比例 (B - A) / A * 100%
 * @property status 重叠评估状态 [SocOverlapStatus]
 * @property comparisonDetails 比对说明与排除理由
 * @property commonCoverage 共同分箱连续性与分段覆盖详细度量 [CommonSocCoverage]
 */
data class CommonSocConditionComparison(
    val sessionAId: String,
    val sessionBId: String,
    val commonMeasuredBinIndices: List<Int>,
    val commonSocCoveragePercent: Double,
    val commonMeasuredEnergyWhA: Double,
    val commonMeasuredEnergyWhB: Double,
    val energyDeltaWh: Double,
    val energyDeltaPercent: Double,
    val status: SocOverlapStatus,
    val comparisonDetails: String = "",
    val commonCoverage: CommonSocCoverage? = null
)

/**
 * 连续工况特征边界与联合支持度实体。
 *
 * 遵循评审原则：OUT_OF_DOMAIN 需检查连续特征范围（温度、电流、功率、压降），
 * 绝不能仅凭离散 3×3 单元格有无样本就认为整个区域都已覆盖。
 *
 * @property minTemperatureC 训练集最低实测温度（℃）
 * @property maxTemperatureC 训练集最高实测温度（℃）
 * @property minCurrentMa 训练集最小平均放电电流（mA）
 * @property maxCurrentMa 训练集最大平均放电电流（mA）
 * @property minPowerW 训练集最小平均放电功率（W）
 * @property maxPowerW 训练集最大平均放电功率（W）
 * @property maxObservedVoltageDropMv 训练集最大观测电压跌落（mV）
 */
data class ContinuousConditionBounds(
    val minTemperatureC: Double,
    val maxTemperatureC: Double,
    val minCurrentMa: Double,
    val maxCurrentMa: Double,
    val minPowerW: Double,
    val maxPowerW: Double,
    val maxObservedVoltageDropMv: Double
)

/**
 * 基于完整放电会话组 Bootstrap 不确定度评估的统计证据等级枚举。
 *
 * 遵循评审铁律：单电池内的重复测量（Repeated Measures）即使会话组很多，
 * 也只能代表该单体电池的行为，绝不能泛化为跨设备/跨电池的通用证据。
 */
enum class BootstrapEvidenceLevel {
    /** 不可用或样本组数不足 (< 3 组) */
    UNAVAILABLE,
    /** 探索性阶段（3 ~ 4 组，可计算但统计上不可靠） */
    EXPLORATORY,
    /** 单电池内多次会话组重复测量可靠证据（>= 5 组，effectiveIndependentBatteryCount = 1） */
    WITHIN_DEVICE_REPEATED_MEASURES,
    /** 跨多个物理独立电池的泛化证据（>= 5 组且 effectiveIndependentBatteryCount > 1） */
    MULTI_BATTERY_EVIDENCE
}

/**
 * 电池长期容量老化趋势与日历漂移评估状态机。
 *
 * 遵循评审规范：解耦固定的 30 天日历寿命阈值，
 * 综合依据循环计数、日历跨度、AcceptedFccQ 历史方差动态判定，
 * 严禁将长期容量衰退与瞬时温度/负载引起的工况能量波动混为一谈。
 */
enum class AgingTrendStatus {
    /** 老化状态稳定，在评估周期内容量方差在容差内，未观测到显著老化衰退 */
    AGING_STABLE,
    /** 观测到可比会话多次持续下降或超出测量不确定度的疑似老化趋势 */
    AGING_SUSPECTED,
    /** 观测到明显的长期老化衰退趋势（容量下降确定超出测量不确定度且趋势持续） */
    AGING_DETECTED,
    /** 处于临界区或单次测量下降，可能由短期工况波动引起，老化趋势不确定 */
    AGING_UNCERTAIN,
    /** 历史跨度或有效 AcceptedFccQ 样本不足，无法评估长期老化 */
    AGING_DATA_INSUFFICIENT
}

/**
 * 顶部平台（Top Plateau）在完整能量容量归一化中的物理处理政策枚举。
 */
enum class TopPlateauNormalizationPolicy {
    /** 纯 SOC 曲线能量：明确将顶部平台与过渡段排除在分箱归一化之外 */
    EXCLUDE_FROM_CURVE,
    /** 保留原始实测物理做功（不随环境工况外推放大） */
    RAW_PHYSICAL_PRESERVED,
    /** 基于经验或电化学模型补偿（目前未验证，严禁使用） */
    MODEL_COMPENSATED,
    /** 缺少参考工况政策标定，阻断完整可用能量输出 */
    UNCALIBRATED_BLOCK
}

/**
 * 满电 100% -> 稳定 <100% 边界过渡段在完整能量容量归一化中的物理处理政策枚举。
 */
enum class TransitionNormalizationPolicy {
    /** 依照标准参考工况测量值进行对齐归属 */
    MEASURED_REFERENCE,
    /** 经过模型充分验证补偿 */
    MODEL_VALIDATED,
    /** 明确排除在有效 SOC 曲线之外 */
    EXCLUDED_FROM_SOC_CURVE,
    /** 缺少参考工况政策标定，阻断完整可用能量输出 */
    UNCALIBRATED_BLOCK
}

/**
 * 标准参考工况实验协议与基准规范实体。
 *
 * 遵循技术评审 P2-B3.1 核心准则：标准工况不能仅凭 25℃、1000mA 经验臆造，
 * 必须具备完整严谨、版本化且可重复的实验协议规范。
 *
 * @property protocolVersion 协议版本标识（如 "REF-PROTO-v1.0"）
 * @property targetTemperatureC 标准参考目标电池温度（℃）
 * @property targetCurrentMa 标准参考目标放电电流（mA）
 * @property targetPowerW 标准参考目标放电功率（W）
 * @property startSocPolicy 起始 SOC 准入协议（如 "START_FROM_100_FULL"）
 * @property dischargeEndPolicy 放电终止事件政策（如 "SHUTDOWN_OR_LOW_VOLTAGE_3000MV"）
 * @property topPlateauPolicy 100% 满电平台处理政策 [TopPlateauNormalizationPolicy]
 * @property transitionPolicy 边界过渡段处理政策 [TransitionNormalizationPolicy]
 * @property requiredMeasuredBins 必须达到 MEASURED 实测的分箱索引集合（通常为 0..19 全域）
 * @property currentSourcePolicy 电流物理证据来源协议（如 "KERNEL_CURRENT_NOW_MA"）
 * @property voltageSourcePolicy 电压物理证据来源协议（如 "KERNEL_VOLTAGE_NOW_UV_NORMALIZED"）
 */
/**
 * 标准参考工况允许物理区间范围实体。
 *
 * 遵循技术评审 P2-B3 铁律：
 * 绝不允许将参考温度（如 25℃）和参考负载（如 1000mA）硬编码为未经验证的单一固定孤立常量，
 * 必须将目标定义为物理自洽的闭区间范围，并严格校验上下限有效性与物理互斥约束。
 *
 * @property temperatureMinC 允许最低参考温度（摄氏度 ℃）
 * @property temperatureMaxC 允许最高参考温度（摄氏度 ℃）
 * @property currentMinMa 允许最小放电电流（毫安 mA，正值）
 * @property currentMaxMa 允许最大放电电流（毫安 mA，正值）
 * @property powerMinW 允许最小做功功率（瓦 W，可选）
 * @property powerMaxW 允许最大做功功率（瓦 W，可选）
 */
data class ReferenceConditionRange(
    val temperatureMinC: Double,
    val temperatureMaxC: Double,
    val currentMinMa: Double,
    val currentMaxMa: Double,
    val powerMinW: Double? = null,
    val powerMaxW: Double? = null
) {
    /**
     * 校验参考工况区间范围的物理有效性与自洽性。
     *
     * @return 若参数无倒置且符合物理客观事实返回 true，否则返回 false
     */
    fun isValid(): Boolean {
        if (temperatureMinC.isNaN() || temperatureMaxC.isNaN() || temperatureMinC > temperatureMaxC) return false
        if (currentMinMa.isNaN() || currentMaxMa.isNaN() || currentMinMa > currentMaxMa || currentMinMa <= 0.0) return false
        if (powerMinW != null && powerMaxW != null) {
            if (powerMinW.isNaN() || powerMaxW.isNaN() || powerMinW > powerMaxW || powerMinW <= 0.0) return false
        }
        return true
    }

    /**
     * 判断指定温度与电流是否落在当前参考物理区间内。
     *
     * @param tempC 电池温度（℃）
     * @param curMa 放电电流（mA）
     * @return 若落在物理允许区间内返回 true，否则返回 false
     */
    fun contains(tempC: Double, curMa: Double): Boolean {
        if (!isValid()) return false
        return tempC in temperatureMinC..temperatureMaxC && curMa in currentMinMa..currentMaxMa
    }
}

/**
 * 放电终止与截止条件强类型枚举协议。
 *
 * 遵循技术评审 P2-B3 铁律：
 * 严禁通用假定 3000mV 作为所有电池的放电截止标准（避免不同负载压降和温度下强制放电造成电池损害），
 * 优先采用设备系统正常的低电量关机事件或 BMS/厂商明确规定的终止协议。
 */
enum class DischargeCutoffPolicy {
    /** 设备自身的正常低电量关机事件（最优先采用的可重复物理事实） */
    NORMAL_SYSTEM_SHUTDOWN,

    /** 电池管理系统（BMS）或操作系统驱动明确报告的低电关机状态 */
    BMS_SPECIFIED_LOW_BATTERY,

    /** 设备实际可观测、可重复的放电稳定终止端点（如 SOC 降至 0% 且端电压稳定） */
    OBSERVED_REPEATABLE_ENDPOINT,

    /** 厂商在技术文档中明确规定且具备严格观测窗口的特定放电截止电压门限（需注明测试工况） */
    MANUFACTURER_STATED_VOLTAGE
}

/**
 * 配对 Bootstrap 改善量统计显著性成熟度分级枚举。
 */
enum class ImprovementSignificanceLevel {
    /** 未达到统计显著性（95% 置信区间跨越或包含零） */
    NOT_SIGNIFICANT,

    /** 统计方向显著改善（置信区间下界 L > 0，改善方向可信），但下界未达到 3% */
    DIRECTIONALLY_SIGNIFICANT,

    /** 严格工程与统计双达标（置信区间下界 L > 3%，在 95% 置信度下真实改善确定超过 3%） */
    PRACTICALLY_SIGNIFICANT_AT_3_PERCENT
}

/**
 * 放电会话起始 SOC 准入政策枚举。
 */
enum class StartDischargePolicy(val description: String) {
    /** 满电起步（要求放电起始 SOC 达到设定门限，默认 ≥ 95%） */
    START_FROM_100_FULL("满电起步（起始 SOC ≥ 95%）"),

    /** 任意电量起步（无起始 SOC 门限要求） */
    ANY_SOC("任意电量起步")
}

/**
 * 放电会话起始电量政策条件设置实体（StartDischargeCondition）。
 *
 * 遵循技术评审铁律：起始电量政策与门限必须严格显式化与可配置，
 * 杜绝将 95% 等门限硬编码为不可变数字。
 *
 * @property policy 起始放电政策枚举 [StartDischargePolicy]
 * @property minStartSocPercent 满电起步模式下允许的最低起始 SOC 百分比（默认 95%）
 */
data class StartDischargeCondition(
    val policy: StartDischargePolicy = StartDischargePolicy.START_FROM_100_FULL,
    val minStartSocPercent: Int = DEFAULT_MIN_START_SOC_PERCENT
) {
    /**
     * 校验起始放电政策设置的合法性。
     */
    fun isValid(): Boolean {
        return minStartSocPercent in 0..100
    }

    /**
     * 判断样本的起始电量是否满足当前起始放电政策。
     *
     * @param startSoc 样本起始电量百分比（0..100）
     * @return 若符合政策准入要求返回 true，否则返回 false
     */
    fun isSatisfied(startSoc: Int): Boolean {
        if (!isValid()) return false
        return when (policy) {
            StartDischargePolicy.START_FROM_100_FULL -> startSoc >= minStartSocPercent
            StartDischargePolicy.ANY_SOC -> true
        }
    }

    companion object {
        /** 默认满电起步最低起始 SOC 门限（95%） */
        const val DEFAULT_MIN_START_SOC_PERCENT: Int = 95
    }
}

/**
 * 放电工况样本数据纯度条件设置实体（DataPurityCondition）。
 *
 * 遵循技术评审 P2-B3 铁律：
 * 将分箱实测覆盖度门限与样本质量拦截准则提取为独立、可配置、自校验的显式条件设置，
 * 杜绝将 80% 实测覆盖率硬编码为单一固定常量。
 *
 * @property minMeasuredCoveragePercent 分箱实测覆盖率最低百分比门限（0.0 ~ 100.0%，默认 80.0%）
 * @property rejectPoorQuality 是否严格拒绝 DischargeQuality.REJECTED 样本（默认 true）
 */
data class DataPurityCondition(
    val minMeasuredCoveragePercent: Double = DEFAULT_MIN_MEASURED_COVERAGE_PERCENT,
    val rejectPoorQuality: Boolean = true
) {
    /**
     * 校验数据纯度条件设置的合法性与物理自洽性。
     */
    fun isValid(): Boolean {
        return !minMeasuredCoveragePercent.isNaN() && minMeasuredCoveragePercent in 0.0..100.0
    }

    /**
     * 判断样本的分箱实测覆盖率与质检评级是否满足当前数据纯度条件。
     *
     * @param measuredEnergyCoverage 分箱实测覆盖率百分比（0.0 ~ 100.0%）
     * @param quality 样本综合质检分级 [DischargeQuality]
     * @return 若满足纯度要求返回 true，否则返回 false
     */
    fun isSatisfied(measuredEnergyCoverage: Double, quality: DischargeQuality): Boolean {
        if (!isValid()) return false
        if (measuredEnergyCoverage < minMeasuredCoveragePercent) return false
        if (rejectPoorQuality && quality == DischargeQuality.REJECTED) return false
        return true
    }

    companion object {
        /** 默认分箱实测覆盖率最低门限（80.0%） */
        const val DEFAULT_MIN_MEASURED_COVERAGE_PERCENT: Double = 80.0
    }
}

/**
 * 标准参考工况实验协议实体（EnergyReferenceProtocol）。
 *
 * 遵循技术评审 P2-B3 铁律：
 * 完整规范标准工况的各项物理政策与可信准入标准，
 * 包含参考工况物理允许区间、起始放电政策、数据纯度门限、放电截止政策、100% 满电平台政策、边界过渡段政策及实测分箱覆盖要求。
 *
 * @property protocolVersion 协议版本号（如 "ERP-v1.0"）
 * @property targetRange 参考工况物理允许区间范围 [ReferenceConditionRange]
 * @property targetTemperatureC 单点参考温度（摄氏度 ℃，向前兼容可选）
 * @property targetCurrentMa 单点参考电流（毫安 mA，向前兼容可选）
 * @property targetPowerW 单点参考功率（瓦 W，可选）
 * @property startSocCondition 起始放电政策条件设置 [StartDischargeCondition]
 * @property dataPurityCondition 数据纯度条件设置 [DataPurityCondition]
 * @property startSocPolicy 起始 SOC 准入协议名称（兼容旧字符串，如 "START_FROM_100_FULL"）
 * @property cutoffPolicy 放电截止条件枚举政策 [DischargeCutoffPolicy]
 * @property dischargeEndPolicy 放电终止事件政策描述文本
 * @property topPlateauPolicy 100% 满电平台处理政策 [TopPlateauNormalizationPolicy]
 * @property transitionPolicy 边界过渡段处理政策 [TransitionNormalizationPolicy]
 * @property requiredMeasuredBins 必须达到 MEASURED 实测的分箱索引集合（通常为 0..19 全域）
 * @property currentSourcePolicy 电流物理证据来源协议（如 "KERNEL_CURRENT_NOW_MA"）
 * @property voltageSourcePolicy 电压物理证据来源协议（如 "KERNEL_VOLTAGE_NOW_UV_NORMALIZED"）
 * @property terminationTimestamp 实际观测到的放电物理终止绝对时间戳（ms）
 * @property terminationReason 放电终止原因（如 "NORMAL_SYSTEM_SHUTDOWN", "BMS_LOW_VOLTAGE_ALARM"）
 * @property terminationSoc 终止时的瞬时 SOC 百分比
 * @property terminationVoltageMv 终止时的端电压（mV）
 * @property terminationCurrentMa 终止时的瞬时电流（mA）
 * @property terminationTemperatureC 终止时的瞬时电池温度（℃）
 * @property terminationObserved 是否具备经完整观测证实的终止物理事实
 */
data class EnergyReferenceProtocol(
    val protocolVersion: String,
    val targetRange: ReferenceConditionRange = ReferenceConditionRange(
        temperatureMinC = 23.0,
        temperatureMaxC = 27.0,
        currentMinMa = 800.0,
        currentMaxMa = 1200.0,
        powerMinW = 3.0,
        powerMaxW = 5.0
    ),
    val targetTemperatureC: Double? = 25.0,
    val targetCurrentMa: Double? = 1000.0,
    val targetPowerW: Double? = null,
    val startSocCondition: StartDischargeCondition = StartDischargeCondition(),
    val dataPurityCondition: DataPurityCondition = DataPurityCondition(),
    val startSocPolicy: String = startSocCondition.policy.name,
    val cutoffPolicy: DischargeCutoffPolicy = DischargeCutoffPolicy.NORMAL_SYSTEM_SHUTDOWN,
    val dischargeEndPolicy: String = "NORMAL_SYSTEM_SHUTDOWN",
    val topPlateauPolicy: TopPlateauNormalizationPolicy = TopPlateauNormalizationPolicy.EXCLUDE_FROM_CURVE,
    val transitionPolicy: TransitionNormalizationPolicy = TransitionNormalizationPolicy.EXCLUDED_FROM_SOC_CURVE,
    val requiredMeasuredBins: Set<Int> = (0..19).toSet(),
    val currentSourcePolicy: String = "KERNEL_CURRENT_NOW_MA",
    val voltageSourcePolicy: String = "KERNEL_VOLTAGE_NOW_UV_NORMALIZED",
    val terminationTimestamp: Long? = null,
    val terminationReason: String? = null,
    val terminationSoc: Double? = null,
    val terminationVoltageMv: Double? = null,
    val terminationCurrentMa: Double? = null,
    val terminationTemperatureC: Double? = null,
    val terminationObserved: Boolean = false
) {
    /**
     * 实际生效的起始放电政策设置实体。
     * 若调用方通过旧字段 [startSocPolicy] 字符串传入了不同的策略，自适应同步解析更新。
     */
    val effectiveStartSocCondition: StartDischargeCondition
        get() {
            val policyFromStr = try {
                StartDischargePolicy.valueOf(startSocPolicy)
            } catch (_: Exception) {
                startSocCondition.policy
            }
            return if (policyFromStr != startSocCondition.policy) {
                startSocCondition.copy(policy = policyFromStr)
            } else {
                startSocCondition
            }
        }

    /**
     * 校验整套参考实验协议的自洽性与合法性。
     *
     * @return 协议是否自洽合法
     */
    fun isProtocolValid(): Boolean {
        if (protocolVersion.isBlank()) return false
        if (!targetRange.isValid()) return false
        if (!effectiveStartSocCondition.isValid()) return false
        if (!dataPurityCondition.isValid()) return false
        if (requiredMeasuredBins.isEmpty()) return false
        if (terminationObserved) {
            // 若标明已观测终止，必须具备完整的物理终止证据（时间戳、电压、SOC均不可为空且有效）
            if (terminationTimestamp == null || terminationTimestamp <= 0L) return false
            if (terminationSoc == null || terminationSoc !in 0.0..100.0) return false
            if (terminationVoltageMv == null || terminationVoltageMv <= 0.0) return false
        }
        return true
    }

    /**
     * 判断单次放电样本是否符合当前参考实验工况协议要求。
     *
     * 遵循评审铁律：必须严格满足参考工况物理区间（温度、电流）、起始电量政策与实测分箱覆盖要求，
     * 杜绝将偶发的任意日常放电会话错标为参考工况。
     *
     * @param sample 放电工况样本实体 [DischargeConditionSample]
     * @return 若完全满足标准参考工况返回 true，否则返回 false
     */
    fun matches(sample: DischargeConditionSample): Boolean {
        if (!isProtocolValid()) return false

        // 1. 检查物理工况特征是否满足参考区间
        val temp = sample.condition.averageTemperatureC ?: return false
        val cur = sample.condition.averageDischargeCurrentMa ?: sample.condition.currentP50Ma ?: return false
        if (!targetRange.contains(temp, cur)) return false

        // 2. 检查起始与终止 SOC 政策要求（通过 StartDischargeCondition 校验）
        if (!effectiveStartSocCondition.isSatisfied(sample.startSoc)) {
            return false
        }

        // 3. 检查实测分箱覆盖度与质检评级（通过 DataPurityCondition 校验）
        if (!dataPurityCondition.isSatisfied(sample.measuredEnergyCoverage, sample.quality)) {
            return false
        }

        return true
    }
}

/**
 * 归一化能量容量不确定度分量与跨分箱协方差审计实体。
 *
 * 遵循技术评审 P2-B3 铁律：
 * 完整能量的不确定度严禁在未经验证的情况下假设各分箱相互独立（即不能简单根号求和），
 * 必须明确记录测量不确定度、分配不确定度、模型参数不确定度及验证残差不确定度，
 * 并严格检验跨分箱协方差矩阵的对称性与半正定性。
 *
 * @property measurementUncertaintyWh 硬件传感器测量不确定度（Wh）
 * @property allocationUncertaintyWh SOC 映射与分箱分配不确定度（Wh）
 * @property modelParameterUncertaintyWh 条件敏感度模型参数不确定度（Wh）
 * @property validationResidualUncertaintyWh 独立验证集残差 RMSE 不确定度（Wh）
 * @property hasCovarianceSupport 是否具备经过估计的跨分箱协方差矩阵支持
 * @property isCovarianceMatrixValid 协方差矩阵是否通过对称性与半正定严格校验
 * @property combinedUncertaintyWh 合成不确定度（Wh，若无有效协方差支持则严格为 null）
 */
data class NormalizationUncertaintyBreakdown(
    val measurementUncertaintyWh: Double?,
    val allocationUncertaintyWh: Double?,
    val modelParameterUncertaintyWh: Double?,
    val validationResidualUncertaintyWh: Double?,
    val hasCovarianceSupport: Boolean,
    val isCovarianceMatrixValid: Boolean = false,
    val combinedUncertaintyWh: Double? = null
)

/**
 * 标准工况能量归一化结果及其完整审计信息实体。
 *
 * 遵循技术评审 P2-B3 铁律：
 * 1. 严格解耦有效 SOC 分箱曲线标准工况能量（[normalizedSocCurveEnergyWh]）与拔电满电到终止的完整可用能量（[normalizedFullUsableEnergyWh]）；
 * 2. 详细追溯模型版本、训练/验证会话组数、独立验证集 MAE、基准 MAE、改善率以及排除/拦截原因，彻底透明化。
 *
 * @property observedEnergyWh 本次放电会话实际直接观测总能量（Wh）
 * @property normalizedSocCurveEnergyWh 仅代表 20 个标准 SOC 分箱的反事实归一化能量（Wh，不包含顶部平台和过渡段）
 * @property normalizedFullUsableEnergyWh 完整拔电可用能量反事实归一化估计（Wh，包含经政策处理的顶部平台与过渡做功）
 * @property referenceConditionTemperatureC 归一化基准目标温度（℃，源自标准参考协议）
 * @property referenceConditionCurrentMa 归一化基准目标电流（mA，源自标准参考协议）
 * @property normalizedUncertaintyWh 归一化合成不确定度标准差（Wh，考虑跨 Bin 协方差，未估计时为 null）
 * @property uncertaintyBreakdown 不确定度分量与协方差详情 [NormalizationUncertaintyBreakdown]
 * @property modelVersion 所采用的敏感度模型版本标识
 * @property trainingGroupCount 训练独立会话组数
 * @property validationGroupCount 独立验证会话组数
 * @property validationMaeWh 独立验证集归一化模型 MAE（Wh）
 * @property baselineMaeWh 独立验证集基准模型 MAE（Wh）
 * @property improvementRatio 归一化改善率 (1 - MAE_norm / MAE_base)
 * @property conditionSupportStatus 多维联合工况支持状态 [ConditionSupportStatus]
 * @property maturity 模型当前验证成熟度 [ConditionModelMaturity]
 * @property rejectionReasons 导致归一化被拦截或返回 null 的具体原因集合
 */
data class EnergyNormalizationResult(
    val observedEnergyWh: Double?,
    val normalizedSocCurveEnergyWh: Double?,
    val normalizedFullUsableEnergyWh: Double?,
    val referenceConditionTemperatureC: Double?,
    val referenceConditionCurrentMa: Double?,
    val normalizedUncertaintyWh: Double?,
    val uncertaintyBreakdown: NormalizationUncertaintyBreakdown? = null,
    val modelVersion: String?,
    val trainingGroupCount: Int,
    val validationGroupCount: Int,
    val validationMaeWh: Double?,
    val baselineMaeWh: Double?,
    val improvementRatio: Double?,
    val conditionSupportStatus: ConditionSupportStatus,
    val maturity: ConditionModelMaturity,
    val rejectionReasons: Set<String> = emptySet()
)

/**
 * 基于完整放电会话组（Session Group）重采样的 Bootstrap 不确定度评估结果实体。
 *
 * 遵循评审规范：统计单位必须是完整放电会话并考虑同一电池内的重复测量相关性，
 * 绝不能按微观采样点数量虚报样本量与置信度。
 *
 * @property medianEnergyWh 会话能量中位数（Wh）
 * @property madEnergyWh 能量绝对中位差 MAD（Wh）
 * @property p10EnergyWh 能量 10 分位数 P10（Wh）
 * @property p90EnergyWh 能量 90 分位数 P90（Wh）
 * @property bootstrapCiLowerWh Bootstrap 95% 置信区间下界（Wh，样本不足时为 null）
 * @property bootstrapCiUpperWh Bootstrap 95% 置信区间上界（Wh，样本不足时为 null）
 * @property effectiveSessionGroups 独立会话组有效数量
 * @property sampleSessionCount 参与统计的放电会话总数
 * @property effectiveIndependentBatteryCount 物理独立电池数量（Honor 500 Pro 研究为 1）
 * @property isComputable 是否满足最低计算门槛（>= 3 组）
 * @property isStatisticallyReliable 是否具备统计可靠性（>= 5 组）
 * @property isConfident 是否具备统计可信度
 * @property evidenceLevel 统计证据等级 [BootstrapEvidenceLevel]
 */
data class SessionBootstrapResult(
    val medianEnergyWh: Double?,
    val madEnergyWh: Double?,
    val p10EnergyWh: Double?,
    val p90EnergyWh: Double?,
    val bootstrapCiLowerWh: Double?,
    val bootstrapCiUpperWh: Double?,
    val effectiveSessionGroups: Int,
    val sampleSessionCount: Int,
    val effectiveIndependentBatteryCount: Int = 1,
    val isComputable: Boolean = effectiveSessionGroups >= 3,
    val isStatisticallyReliable: Boolean = effectiveSessionGroups >= 5,
    val isConfident: Boolean = isStatisticallyReliable,
    val evidenceLevel: BootstrapEvidenceLevel = when {
        effectiveSessionGroups < 3 -> BootstrapEvidenceLevel.UNAVAILABLE
        effectiveSessionGroups < 5 -> BootstrapEvidenceLevel.EXPLORATORY
        effectiveIndependentBatteryCount <= 1 -> BootstrapEvidenceLevel.WITHIN_DEVICE_REPEATED_MEASURES
        else -> BootstrapEvidenceLevel.MULTI_BATTERY_EVIDENCE
    }
)

/**
 * 多维联合工况支持度评估状态枚举。
 */
enum class ConditionSupportStatus {
    /** 训练集具有足够的局部联合样本密度，工况受模型支持 */
    SUPPORTED,
    /** 单一特征各自在边际范围内，但多维组合在训练集中无样本支持（局部空白区） */
    UNSUPPORTED_COMBINATION,
    /** 超出边际特征极值范围（如电流 > 5000mA） */
    OUT_OF_MARGINAL_BOUNDS
}

/**
 * 多维连续联合特征支持度与近邻距离度量实体。
 *
 * 遵循评审铁律：模型不能只凭温度和电流各自落在训练集 Min/Max 之间，就认为联合工况已经得到支持。
 * 明确 1.25 归一化欧式近邻距离属于未经大规模跨电池标定的初期工程阈值。
 *
 * @property temperatureCurrentDistance 归一化温度-电流联合特征距离
 * @property nearestTrainingDistance 最近训练样本的归一化多维欧氏距离
 * @property supportStatus 联合工况支持状态 [ConditionSupportStatus]
 * @property supportDetails 支持度评估详情与解释
 * @property featureScaleSource 特征缩放尺度来源（如 HISTORICAL_MIN_MAX）
 * @property supportCalibrationMethod 支持度标定方法（如 EMPIRICAL_EUCLIDEAN_NEIGHBORHOOD）
 * @property supportModelVersion 支持度模型版本
 */
data class JointConditionSupport(
    val temperatureCurrentDistance: Double?,
    val nearestTrainingDistance: Double?,
    val supportStatus: ConditionSupportStatus,
    val supportDetails: String = "",
    val featureScaleSource: String = "HISTORICAL_MIN_MAX",
    val supportCalibrationMethod: String = "EMPIRICAL_EUCLIDEAN_NEIGHBORHOOD",
    val supportModelVersion: String = "FCC-v1.1"
)

/**
 * 按完整会话组切分的时序滚动原点前向验证切片实体（Rolling Origin Validation Split）。
 *
 * 遵循评审规范：完整 Session Group 作为切分单位，同一会话组严禁被切碎横跨训练集和验证集。
 *
 * @property splitIndex 切片轮次序号
 * @property trainingSessionGroups 参与训练的会话组 ID 集合
 * @property validationSessionGroups 参与验证的会话组 ID 集合（时间戳严格晚于训练组）
 * @property trainingSampleCount 训练会话样本数
 * @property validationSampleCount 验证会话样本数
 */
data class RollingOriginValidationSplit(
    val splitIndex: Int,
    val trainingSessionGroups: List<String>,
    val validationSessionGroups: List<String>,
    val trainingSampleCount: Int,
    val validationSampleCount: Int
)

/**
 * 最小实际改善幅度工程阈值（MINIMUM_PRACTICAL_IMPROVEMENT = 3%）。
 * 遵循技术评审：此阈值代表预先设定的最小实际工程改善门槛，绝不能误称或等同于统计显著性检验。
 */
const val MINIMUM_PRACTICAL_IMPROVEMENT: Double = 0.03

/**
 * P2-B3 条件敏感度模型验证结果与误差审计实体。
 *
 * 遵循技术评审 P2-B3 铁律：必须在同一批独立验证集上对比基准与归一化误差，
 * 当归一化改善率（Improvement = 1 - MAE_norm / MAE_base）未能超过最小实际改善门槛，
 * 或配对 Bootstrap 置信区间覆盖零时，成熟度坚决拒绝晋升为 VALIDATED。
 *
 * @property modelVersion 模型版本标识
 * @property maturity 成熟度状态 [ConditionModelMaturity]
 * @property trainingGroupCount 训练独立会话组数
 * @property validationGroupCount 独立验证会话组数
 * @property validationMaeWh 平均绝对误差 MAE（Wh，无独立验证数据时严格为 null）
 * @property validationRmseWh 均方根误差 RMSE（Wh，无独立验证数据时严格为 null）
 * @property validationBiasWh 平均偏差 Bias（Wh，无独立验证数据时严格为 null）
 * @property baselineMaeWh 未补偿基准模型的 MAE（Wh）
 * @property normalizationImprovement 归一化改善率 (1 - MAE_norm / MAE_base)
 * @property pairedImprovementCiLower 配对会话组 Bootstrap 改善量 95% 置信区间下界（Wh）
 * @property pairedImprovementCiUpper 配对会话组 Bootstrap 改善量 95% 置信区间上界（Wh）
 * @property isPairedImprovementSignificant 配对改善是否具备严格统计显著性（区间下界 > 0）
 * @property significanceLevel 配对 Bootstrap 改善量统计显著性与工程效应成熟度分级 [ImprovementSignificanceLevel]
 * @property commonSocCoverage 平均共同 SOC 分箱覆盖率
 * @property outOfDomainRate 域外拦截率
 * @property validationTimestamp 验证完成时间戳（ms）
 */
data class ConditionModelValidation(
    val modelVersion: String,
    val maturity: ConditionModelMaturity,
    val trainingGroupCount: Int,
    val validationGroupCount: Int,
    val validationMaeWh: Double? = null,
    val validationRmseWh: Double? = null,
    val validationBiasWh: Double? = null,
    val baselineMaeWh: Double? = null,
    val normalizationImprovement: Double? = null,
    val pairedImprovementCiLower: Double? = null,
    val pairedImprovementCiUpper: Double? = null,
    val isPairedImprovementSignificant: Boolean = false,
    val significanceLevel: ImprovementSignificanceLevel = if (isPairedImprovementSignificant) {
        if ((normalizationImprovement ?: 0.0) > MINIMUM_PRACTICAL_IMPROVEMENT) {
            ImprovementSignificanceLevel.PRACTICALLY_SIGNIFICANT_AT_3_PERCENT
        } else {
            ImprovementSignificanceLevel.DIRECTIONALLY_SIGNIFICANT
        }
    } else {
        ImprovementSignificanceLevel.NOT_SIGNIFICANT
    },
    val commonSocCoverage: Double? = null,
    val outOfDomainRate: Double? = null,
    val validationTimestamp: Long = System.currentTimeMillis()
)

/**
 * 3×3 工况矩阵单个单元格的统计聚合实体。
 *
 * @property thermalBand 热环境分类 [DischargeThermalBand]
 * @property loadBand 负载分类 [DischargeLoadBand]
 * @property sampleCount 独立会话样本总数（严禁切分虚构）
 * @property medianEnergyWh 实测能量中位数（Wh，样本不足时为 null）
 * @property energyMadWh 能量绝对中位差 MAD（Wh，样本不足时为 null）
 * @property energyP10Wh 能量 10 分位数 P10（Wh）
 * @property energyP90Wh 能量 90 分位数 P90（Wh）
 * @property avgVoltageDropMv 平均电压跌落（mV）
 */
data class ConditionMatrixCell(
    val thermalBand: DischargeThermalBand,
    val loadBand: DischargeLoadBand,
    val sampleCount: Int = 0,
    val medianEnergyWh: Double? = null,
    val energyMadWh: Double? = null,
    val energyP10Wh: Double? = null,
    val energyP90Wh: Double? = null,
    val avgVoltageDropMv: Double? = null
)

/**
 * 3×3 工况比较矩阵综合评估结果实体。
 *
 * @property cells 9 个单元格统计列表
 * @property hasCrossConditionOverlap 是否具备充分的温度与负载交叉覆盖
 * @property overlapStatusMessage 交叉覆盖状态说明（若不足则标明 INSUFFICIENT_CONDITION_OVERLAP）
 * @property maturity 当前敏感度模型成熟度状态 [ConditionModelMaturity]
 */
data class ConditionMatrixResult(
    val cells: List<ConditionMatrixCell>,
    val hasCrossConditionOverlap: Boolean,
    val overlapStatusMessage: String,
    val maturity: ConditionModelMaturity
)
