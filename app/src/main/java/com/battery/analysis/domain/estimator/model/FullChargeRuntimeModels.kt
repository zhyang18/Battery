package com.battery.analysis.domain.estimator.model

import android.content.Context
import com.battery.analysis.R

/**
 * 满充电池能量的物理获取来源枚举。
 * 遵循系统底层物理数据可靠性优先级定义，真实反映硬件与电量计层的数据来源。
 *
 * @property description 能量来源的可读描述
 */
enum class EnergySource(val description: String) {
    /** 硬件电量计底层原生能量计数器读取推算 (nWh -> Wh) */
    NATIVE_FULL_ENERGY("硬件原生能量计数器"),
    /** 真实满充容量 FCC × 标称工作电压 (Ah * V -> Wh，FCC 已反映电池实际老化衰减) */
    FCC_TIMES_NOMINAL_VOLTAGE("真实满充容量(FCC)折算"),
    /** 出厂设计容量 × 真实健康度 SOH × 标称工作电压 */
    DESIGN_CAPACITY_TIMES_HEALTH("设计容量与健康度折算"),
    /** 能量来源不可用，无法进行物理推导（无假数据兜底） */
    UNAVAILABLE("未知/不可用")
}

/**
 * 样本数据或估计结果的数据质量等级枚举。
 */
enum class DataQuality {
    /** 高质量，具备完整连续采样与明确物理依据 */
    VALID,
    /** 低质量，存在采样间隔偏大、缺乏部分上下文或通过工程近似推导，可降权参与 */
    LOW_QUALITY,
    /** 无效数据，包含非物理值、时间倒退或充电异常，严禁参与模型统计 */
    INVALID
}

/**
 * 满充可用物理能量估算结果实体类。
 *
 * @property energyWh 估算的满充可用物理能量（单位：Wh，若无法推算则为 null，严禁假数据）
 * @property source 满充能量的物理获取来源 [EnergySource]
 * @property quality 数据质量等级 [DataQuality]
 * @property notes 相关的计算依据或降级说明标签集合
 */
data class FullChargeEnergyEstimate(
    val energyWh: Double?,
    val source: EnergySource,
    val quality: DataQuality,
    val notes: Set<String> = emptySet()
)

/**
 * 设备日常放电运行的核心功耗工况分类枚举。
 *
 * @property label 工况的可读标签描述
 */
enum class PowerRegime(val label: String) {
    /** 息屏低载待机（深度休眠底噪） */
    OFF_LOW("息屏待机"),
    /** 息屏活跃唤醒（后台媒体、下载、唤醒锁活跃） */
    OFF_HIGH("息屏唤醒"),
    /** 亮屏轻载（阅读、静态信息浏览） */
    ON_LIGHT("亮屏轻载"),
    /** 亮屏日常中载（即时通信、短视频流、普通应用交互） */
    ON_NORMAL("亮屏日常"),
    /** 亮屏重载（3D 大型游戏、高分辨率视频录制、持续渲染高运算） */
    ON_HEAVY("亮屏重载"),
    /** 未知工况或数据不足以划分类别 */
    UNKNOWN("未知工况")
}

/**
 * 历史样本数据充分性等级枚举。
 * 遵循真实样本质量与工况覆盖度评定，绝不简单以日历天数代替真实样本量。
 *
 * @property description 可读充分性描述文本
 */
enum class DataSufficiencyLevel(val description: String) {
    /** 样本充足，时间与多工况覆盖全面，具备极高统计代表性 */
    SUFFICIENT("数据充足"),
    /** 样本基本可用，具备核心放电工况，可提供可靠参考基准 */
    MODERATE("基本可用"),
    /** 样本偏少（如新机刚用一两天），处于数据积累初期，已如实降权 */
    INSUFFICIENT("样本较少");

    /**
     * 获取符合当前系统或用户界面语言设置的充分性等级可读描述。
     *
     * @param context 应用程序上下文环境，可为 null
     * @return 对应语言环境的充分性等级描述文本
     */
    fun getDescription(context: Context?): String {
        if (context == null) return description
        return when (this) {
            SUFFICIENT -> context.getString(R.string.data_sufficiency_sufficient)
            MODERATE -> context.getString(R.string.data_sufficiency_moderate)
            INSUFFICIENT -> context.getString(R.string.data_sufficiency_insufficient)
        }
    }

    companion object {
        /**
         * 根据累积物理使用时长与行分类标识推导数据充分性等级。
         * 亮屏/息屏：<24h 为样本较少，24h~72h 为基本可用，>=72h 为数据充足；
         * 全局：<48h 为样本较少，48h~120h 为基本可用，>=120h 为数据充足。
         *
         * @param rowType 行分类标识（[com.battery.analysis.util.BatteryEnergyCalculator.ROW_SCREEN_ON] 为亮屏，[ROW_SCREEN_OFF] 为息屏，[ROW_GLOBAL] 为全局）
         * @param accumulatedDurationMs 对应维度的累积物理使用时长（单位：毫秒，缺失或未获取传入 null）
         * @return 对应的数据充分性等级 [DataSufficiencyLevel]
         */
        fun evaluateByDuration(rowType: Int, accumulatedDurationMs: Long?): DataSufficiencyLevel {
            if (accumulatedDurationMs == null || accumulatedDurationMs <= 0L) {
                return INSUFFICIENT
            }
            val hours = accumulatedDurationMs / 3600_000.0
            return when (rowType) {
                com.battery.analysis.util.BatteryEnergyCalculator.ROW_SCREEN_ON,
                com.battery.analysis.util.BatteryEnergyCalculator.ROW_SCREEN_OFF -> when {
                    hours >= 72.0 -> SUFFICIENT
                    hours >= 24.0 -> MODERATE
                    else -> INSUFFICIENT
                }
                com.battery.analysis.util.BatteryEnergyCalculator.ROW_GLOBAL -> when {
                    hours >= 120.0 -> SUFFICIENT
                    hours >= 48.0 -> MODERATE
                    else -> INSUFFICIENT
                }
                else -> when {
                    hours >= 72.0 -> SUFFICIENT
                    hours >= 24.0 -> MODERATE
                    else -> INSUFFICIENT
                }
            }
        }
    }
}

/**
 * 历史放电物理区间实体类。
 * 表达一段不重叠、时间单调的有效放电时间段，作为工况划分与能量积分的基石。
 *
 * @property startTimeMs 区间起始系统时间戳（毫秒）
 * @property endTimeMs 区间结束系统时间戳（毫秒）
 * @property durationMs 区间物理持续时长（毫秒，必须大于 0）
 * @property energyWh 区间内整机消耗的物理能量（单位：Wh）
 * @property powerWatts 区间平均放电物理功率（单位：W）
 * @property isScreenOn 屏幕是否处于亮屏状态（true 为亮屏，false 为息屏）
 * @property quality 区间数据质量等级 [DataQuality]
 * @property ageWeight 自适应历史时间衰减权重 w_age（取值 0.0 ~ 1.0，越旧的样本权重越低）
 */
data class DischargeInterval(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val durationMs: Long,
    val energyWh: Double,
    val powerWatts: Double,
    val isScreenOn: Boolean,
    val quality: DataQuality = DataQuality.VALID,
    val ageWeight: Double = 1.0
)

/**
 * 自适应历史窗口统计元信息指标实体类。
 * 封装窗口提取过程中实际覆盖的自然时间跨度、真实有效放电累计时长与数据充分性评定。
 *
 * @property windowDurationMs 实际覆盖的日历自然时间跨度（单位：毫秒，7天至30天之间）
 * @property windowDays 实际覆盖的日历自然天数（如 7.0 天或 18.5 天）
 * @property effectiveDischargeDurationMs 实际参与统计的有效放电累计物理时长（单位：毫秒）
 * @property screenOnDurationMs 参与统计的有效亮屏累计时长（单位：毫秒）
 * @property screenOffDurationMs 参与统计的有效息屏累计时长（单位：毫秒）
 * @property sufficiency 数据充分性评级 [DataSufficiencyLevel]
 * @property regimeCount 覆盖的核心放电工况类别数量
 */
data class AdaptiveWindowMetrics(
    val windowDurationMs: Long,
    val windowDays: Double,
    val effectiveDischargeDurationMs: Long,
    val screenOnDurationMs: Long,
    val screenOffDurationMs: Long,
    val sufficiency: DataSufficiencyLevel,
    val regimeCount: Int
)

/**
 * 满电续航算法模型版本标识枚举。
 */
enum class RuntimeModelVersion {
    /** 现有基线算法：168 小时历史全局能量加权平均线性推算 */
    FULL_RUNTIME_V1,
    /** 升级算法：有效历史窗口 + 工况条件化行为占比与稳健能量模型 */
    FULL_RUNTIME_V2,
    /** 阶段三修正升级版本（v2.1）：自适应历史窗口(7~30天) + 时间衰减权重 + FCC满电优先级 + 严谨数据质量追溯 */
    FULL_RUNTIME_V2_1,
    /** 阶段四细粒度升级版本（v2.2）：基于时序采样点工况连续切片 + 能量守恒校准 + 细粒度行为重构 */
    FULL_RUNTIME_V2_2
}

/**
 * 满电续航估计置信度等级枚举。
 *
 * @property description 可读置信度描述
 */
enum class RuntimeConfidenceLevel(val description: String) {
    /** 具备多天充足历史且工况覆盖完整 */
    HIGH("可信度高"),
    /** 历史数据覆盖数小时以上，具备典型参考价值 */
    MEDIUM("可信度中等"),
    /** 历史样本较少，数据处于积累阶段 */
    LOW("可信度较低"),
    /** 数据严重缺失或未获取到能量，无法可靠估算 */
    UNAVAILABLE("数据不足");

    /**
     * 获取符合当前系统或用户界面语言设置的置信度等级可读描述。
     *
     * @param context 应用程序上下文环境，可为 null
     * @return 对应语言环境的置信度等级描述文本
     */
    fun getDescription(context: Context?): String {
        if (context == null) return description
        return when (this) {
            HIGH -> context.getString(R.string.conf_level_high)
            MEDIUM -> context.getString(R.string.conf_level_medium)
            LOW -> context.getString(R.string.conf_level_low)
            UNAVAILABLE -> context.getString(R.string.conf_level_unavailable)
        }
    }

    companion object {
        /**
         * 根据连续打分（0.0 ~ 1.0）推导对应的可信度等级枚举。
         *
         * @param score 介于 0.0 至 1.0 之间的置信度分数
         * @return 对应的可信度等级枚举 [RuntimeConfidenceLevel]
         */
        fun fromScore(score: Double): RuntimeConfidenceLevel {
            return when {
                score <= 0.0 -> UNAVAILABLE
                score >= 0.70 -> HIGH
                score >= 0.45 -> MEDIUM
                else -> LOW
            }
        }

        /**
         * 根据累积物理使用时长与卡片分类维度（亮屏、息屏、全局）推导对应的置信度等级。
         * 严格遵循工业级准则：
         * 1. 亮屏（ROW_SCREEN_ON）/ 息屏（ROW_SCREEN_OFF）：小于 24 小时为低，大于等于 24 小时且小于 72 小时为中，大于等于 72 小时为高；
         * 2. 全局（ROW_GLOBAL）：小于 48 小时为低，大于等于 48 小时且小于 120 小时为中，大于等于 120 小时为高；
         * 3. 时长缺失或非正数时如实返回数据不足 [UNAVAILABLE]。
         *
         * @param rowType 行分类标识（[com.battery.analysis.util.BatteryEnergyCalculator.ROW_SCREEN_ON] 为亮屏，[ROW_SCREEN_OFF] 为息屏，[ROW_GLOBAL] 为全局）
         * @param accumulatedDurationMs 对应维度的累积物理使用时长（单位：毫秒，缺失或未获取传入 null）
         * @return 对应的置信度等级枚举 [RuntimeConfidenceLevel]
         */
        fun evaluateByDuration(rowType: Int, accumulatedDurationMs: Long?): RuntimeConfidenceLevel {
            if (accumulatedDurationMs == null || accumulatedDurationMs <= 0L) {
                return UNAVAILABLE
            }
            val hours = accumulatedDurationMs / 3600_000.0
            return when (rowType) {
                com.battery.analysis.util.BatteryEnergyCalculator.ROW_SCREEN_ON,
                com.battery.analysis.util.BatteryEnergyCalculator.ROW_SCREEN_OFF -> when {
                    hours >= 72.0 -> HIGH
                    hours >= 24.0 -> MEDIUM
                    else -> LOW
                }
                com.battery.analysis.util.BatteryEnergyCalculator.ROW_GLOBAL -> when {
                    hours >= 120.0 -> HIGH
                    hours >= 48.0 -> MEDIUM
                    else -> LOW
                }
                else -> when {
                    hours >= 72.0 -> HIGH
                    hours >= 24.0 -> MEDIUM
                    else -> LOW
                }
            }
        }
    }
}

/**
 * 用户典型用机工况画像与时间占比物理统计实体类（RuntimeProfile v2.1）。
 * 完整记录自适应历史时间窗口内的有效放电物理时长、亮屏/息屏时间占比、
 * 亮屏内部轻/中/重载时间与占比、各工况代表放电功率及最终典型加权功耗，
 * 为满电理论续航提供完整、可溯源的物理依据。
 *
 * @property windowStartMs 统计窗口起始绝对系统时间戳（毫秒）
 * @property windowEndMs 统计窗口结束绝对系统时间戳（毫秒）
 * @property windowDays 实际选定的日历历史窗口天数（7, 14 或 30 天）
 * @property effectiveDischargeMs 窗口内参与统计的有效放电累计物理时长（毫秒）
 * @property screenOnMs 亮屏有效放电物理时长（毫秒）
 * @property screenOffMs 息屏有效放电物理时长（毫秒）
 * @property screenOnRatio 亮屏有效时长占总有效放电时长的物理比例（0.0 ~ 1.0）
 * @property screenOffRatio 息屏有效时长占总有效放电时长的物理比例（0.0 ~ 1.0）
 * @property lightMs 亮屏轻载工况累计有效物理时长（毫秒）
 * @property normalMs 亮屏日常中载工况累计有效物理时长（毫秒）
 * @property heavyMs 亮屏重载工况累计有效物理时长（毫秒）
 * @property lightRatio 亮屏轻载工况在亮屏内部的时间占比（0.0 ~ 1.0）
 * @property normalRatio 亮屏日常中载工况在亮屏内部的时间占比（0.0 ~ 1.0）
 * @property heavyRatio 亮屏重载工况在亮屏内部的时间占比（0.0 ~ 1.0）
 * @property lightPowerW 亮屏轻载工况实测代表放电功率（单位：W，若样本不足为 null）
 * @property normalPowerW 亮屏日常中载工况实测代表放电功率（单位：W，若样本不足为 null）
 * @property heavyPowerW 亮屏重载工况实测代表放电功率（单位：W，若样本不足为 null）
 * @property typicalScreenOnPowerW 亮屏典型加权放电功耗（单位：W，若样本不足为 null）
 * @property offLowMs 息屏深度休眠低载工况累计有效物理时长（毫秒）
 * @property offHighMs 息屏活跃唤醒高载工况累计有效物理时长（毫秒）
 * @property offLowRatio 息屏低载在息屏内部的时间占比（0.0 ~ 1.0）
 * @property offHighRatio 息屏高载在息屏内部的时间占比（0.0 ~ 1.0）
 * @property offLowPowerW 息屏低载工况代表放电功率（单位：W，若样本不足为 null）
 * @property offHighPowerW 息屏高载工况代表放电功率（单位：W，若样本不足为 null）
 * @property typicalScreenOffPowerW 息屏典型加权放电功耗（单位：W，若样本不足为 null）
 * @property typicalGlobalPowerW 全局综合典型加权放电功耗（单位：W，若样本不足为 null）
 * @property confidence 数据与工况置信度等级 [RuntimeConfidenceLevel]
 */
data class RuntimeProfile(
    val windowStartMs: Long,
    val windowEndMs: Long,
    val windowDays: Int,
    val effectiveDischargeMs: Long,
    val screenOnMs: Long,
    val screenOffMs: Long,
    val screenOnRatio: Double,
    val screenOffRatio: Double,
    val lightMs: Long,
    val normalMs: Long,
    val heavyMs: Long,
    val lightRatio: Double,
    val normalRatio: Double,
    val heavyRatio: Double,
    val lightPowerW: Double?,
    val normalPowerW: Double?,
    val heavyPowerW: Double?,
    val typicalScreenOnPowerW: Double?,
    val offLowMs: Long = 0L,
    val offHighMs: Long = 0L,
    val offLowRatio: Double = 0.0,
    val offHighRatio: Double = 0.0,
    val offLowPowerW: Double? = null,
    val offHighPowerW: Double? = null,
    val typicalScreenOffPowerW: Double? = null,
    val typicalGlobalPowerW: Double? = null,
    val confidence: RuntimeConfidenceLevel = RuntimeConfidenceLevel.UNAVAILABLE
)

/**
 * 满电续航算法统一输出聚合实体类。
 * 封装全局综合、纯亮屏与纯息屏待机三维度的满电理论可用时长与对应预测功耗。
 *
 * @property fullRuntimeMs 预测的设备满电理论可用续航物理毫秒数（全局综合，数据不足时为 null，严禁假数据）
 * @property fullEnergyWh 计算采用的满充可用物理总能量（单位：Wh，不可用时为 null）
 * @property predictedPowerWatts 预测的未来全局典型放电综合功率（单位：W，不可用时为 null）
 * @property modelVersion 产生本结果的算法模型版本 [RuntimeModelVersion]
 * @property confidenceLevel 预测结果的数据置信度等级 [RuntimeConfidenceLevel]
 * @property energySource 满充物理能量的数据获取来源 [EnergySource]
 * @property effectiveHistoryMs 统计实际覆盖的有效历史放电物理毫秒数
 * @property usedRegimeModel 是否成功启用了工况条件化模型（若为 false 则表明已安全降级至全局基准）
 * @property fallbackReason 降级或降权的具体物理原因说明（若运行完整工况模型则为 null）
 * @property screenOnFullRuntimeMs 基于亮屏工况（轻/中/重载）典型加权推算的满电纯亮屏可用物理毫秒数（无有效亮屏数据为 null）
 * @property screenOffFullRuntimeMs 基于息屏休眠与唤醒解构稳态加权推算的满电纯待机可用物理毫秒数（无有效息屏数据为 null）
 * @property predictedScreenOnPowerWatts 预测的亮屏典型物理放电功率（单位：W，不可用时为 null）
 * @property predictedScreenOffPowerWatts 预测的息屏典型物理放电功率（单位：W，不可用时为 null）
 * @property qualityReasons 预测数据质量与依据说明列表（用于解释预测来源与可靠性）
 * @property historyWindowDays 实际生效的日历自然时间窗口跨度（单位：天，默认 7.0 天）
 * @property effectiveDischargeDurationMs 实际参与统计的有效放电累计物理时长（单位：毫秒）
 * @property dataSufficiency 历史样本数据充分性等级 [DataSufficiencyLevel]
 * @property screenOnEffectiveMs 实际统计的亮屏有效放电时长（单位：毫秒，可选）
 * @property screenOffEffectiveMs 实际统计的息屏有效放电时长（单位：毫秒，可选）
 * @property screenOnConfidenceLevel 亮屏预测结果的数据置信度等级 [RuntimeConfidenceLevel]
 * @property screenOffConfidenceLevel 息屏预测结果的数据置信度等级 [RuntimeConfidenceLevel]
 * @property screenOnDataSufficiency 亮屏历史样本数据充分性等级 [DataSufficiencyLevel]
 * @property screenOffDataSufficiency 息屏历史样本数据充分性等级 [DataSufficiencyLevel]
 * @property profile 详细的自适应用机工况画像与时间占比物理统计实体 [RuntimeProfile]（可选）
 */
data class FullChargeRuntimeResult(
    val fullRuntimeMs: Long?,
    val fullEnergyWh: Double?,
    val predictedPowerWatts: Double?,
    val modelVersion: RuntimeModelVersion = RuntimeModelVersion.FULL_RUNTIME_V2_1,
    val confidenceLevel: RuntimeConfidenceLevel,
    val energySource: EnergySource,
    val effectiveHistoryMs: Long,
    val usedRegimeModel: Boolean,
    val fallbackReason: String? = null,
    val screenOnFullRuntimeMs: Long? = null,
    val screenOffFullRuntimeMs: Long? = null,
    val predictedScreenOnPowerWatts: Double? = null,
    val predictedScreenOffPowerWatts: Double? = null,
    val qualityReasons: List<String> = emptyList(),
    val historyWindowDays: Double = 7.0,
    val effectiveDischargeDurationMs: Long = effectiveHistoryMs,
    val dataSufficiency: DataSufficiencyLevel = DataSufficiencyLevel.SUFFICIENT,
    val screenOnEffectiveMs: Long? = null,
    val screenOffEffectiveMs: Long? = null,
    val screenOnConfidenceLevel: RuntimeConfidenceLevel = confidenceLevel,
    val screenOffConfidenceLevel: RuntimeConfidenceLevel = confidenceLevel,
    val screenOnDataSufficiency: DataSufficiencyLevel = dataSufficiency,
    val screenOffDataSufficiency: DataSufficiencyLevel = dataSufficiency,
    val profile: RuntimeProfile? = null
)
