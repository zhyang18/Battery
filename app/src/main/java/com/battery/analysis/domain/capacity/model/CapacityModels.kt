package com.battery.analysis.domain.capacity.model

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
    val socSource: String = ""
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
    val exclusionReason: String = ""
)

/**
 * 单个 5% SOC 能量分箱信息（0%~100% 共 20 段）。
 */
data class SocEnergyBinInfo(
    val binIndex: Int,
    val startSocPercent: Double,
    val endSocPercent: Double,
    val chargeMah: Double,
    val energyWh: Double,
    val avgVoltageVolts: Double?,
    val avgTemperatureC: Double?,
    val sampleCount: Int,
    val isLearned: Boolean
) {
    fun getLabel(): String = String.format(java.util.Locale.US, "%.0f%% - %.0f%%", startSocPercent, endSocPercent)
}

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
    val algorithmVersion: String = "FCC-v1.0",
    val updatedTimestampMs: Long = System.currentTimeMillis()
)
