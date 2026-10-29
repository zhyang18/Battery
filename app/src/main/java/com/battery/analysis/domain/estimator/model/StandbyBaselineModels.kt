package com.battery.analysis.domain.estimator.model

/**
 * 息屏时间切片状态分类枚举。
 * 明确时钟差分与采样断层的物理能力边界，分离确认状态与不确定断层。
 */
enum class StandbyStateClassification {
    /** 确认处于系统唤醒活跃期（亮屏交互、亮灭屏过渡或明确 WakeLock 运行） */
    CONFIRMED_AWAKE,

    /** 确认处于有效深睡静默窗口（经过双层筛选并提取代表值） */
    CONFIRMED_SLEEP,

    /** 采样断层或调度延迟区间（两点间隔超过最大允许插值时间，禁止跨段插值） */
    UNKNOWN_GAP
}

/**
 * 息屏待机基线功率来源枚举。
 * 遵循系统零假数据原则，严格标注待机基线是来源于真实实测窗口统计，还是在数据缺失时回退的客观先验。
 */
enum class StandbyBaselineSource {
    /** 来源于有效深睡固定长度窗口第 20 百分位数 (P20) 典型基线 */
    MEASURED_WINDOW_P20,

    /** 来源于有效深睡固定长度窗口第 15 百分位数 (P15) 典型基线 */
    MEASURED_WINDOW_P15,

    /** 来源于有效深睡固定长度窗口第 30 百分位数 (P30) 典型基线（保留兼容） */
    @Deprecated("已升级为 P20 算法，推荐使用 MEASURED_WINDOW_P20")
    MEASURED_WINDOW_P30,

    /** 来源于有效深睡固定长度窗口中位数 (P50) 典型基线（保留兼容） */
    @Deprecated("已升级为 P20 算法，推荐使用 MEASURED_WINDOW_P20")
    MEASURED_WINDOW_P50,

    /** 来源于高/中置信实测时间窗口第 10 百分位数 (P10，诊断参考) */
    MEASURED_WINDOW_P10,

    /** 来源于高覆盖率有效深睡区间梯形时间积分实测观测均值 */
    MEASURED_TRAPEZOIDAL_INTEGRAL,

    /** 设备历史实测校准先验 (保留扩展) */
    DEVICE_CALIBRATED_PRIOR,

    /** 默认初始先验 (无可靠实测样本或样本被严重污染时回退的初始先验基线) */
    DEFAULT_STANDBY_PRIOR
}

/**
 * 息屏待机基线置信度等级枚举。
 */
enum class StandbyConfidenceLevel {
    /**
     * 高置信度：
     * 有效时间窗口数量充足 (>= 30 个窗口)、连续采样时间覆盖率达标 (>= 70%)、
     * 低功耗基线平台平坦度稳定 (P20 与 P10 差距在允许范围内) 且分布离散度稳定。
     */
    HIGH,

    /**
     * 中置信度：
     * 具备一定数量的有效时间窗口支持 (>= 10 个窗口)，样本未检测到明显的异常污染，
     * 允许直接采信实测值，但在诊断信息中保留中等置信度标识（绝不进行人为平滑合成）。
     */
    MEDIUM,

    /**
     * 低置信度：
     * 窗口数量偏少 (3 ~ 9 个窗口) 或 P20/P10 分位数差距过大，
     * 输出临时短时试算估计值，但提示置信度低或平台波动。
     */
    LOW,

    /**
     * 不可用：
     * 有效采样点或有效时间窗口严重不足 (< 3 个窗口)，或全部样本均受活跃任务严重污染，
     * 无法从当前样本中提取可靠基线或均值，必须安全回退至先验并记录原因。
     */
    UNAVAILABLE
}

/**
 * 息屏待机基线诊断原因码枚举。
 * 遵循结构化质量诊断原则，忠实记录算法决策与门禁拦截原因。
 */
enum class StandbyReasonCode {
    /** 原始有效息屏采样点不足 */
    INSUFFICIENT_SAMPLES,

    /** 有效时间窗口数量不足 */
    INSUFFICIENT_CANDIDATE_WINDOWS,

    /** 连续物理采样覆盖率不足 (低于门限) */
    LOW_DATA_COVERAGE,

    /** P10 与 P20 分位数差距过大，低功耗底噪平台平坦度不足，疑似微唤醒频繁（保留兼容） */
    P10_P20_GAP_HIGH,

    /** P15 与 P25 分位数差距过大，低功耗底噪平台平坦度不足，疑似微唤醒频繁（保留兼容） */
    P15_P25_GAP_HIGH,

    /** P20 与 P30 分位数差距过大，低功耗底噪平台平坦度不足，疑似微唤醒频繁 */
    P20_P30_GAP_HIGH,

    /** 息屏期间疑似受到后台任务严重污染 (全窗口功率偏高且无低功耗窗口支持) */
    SUSPICIOUS_POLLUTION_DETECTED,

    /** 传感器数据异常、离散度过大或量化台阶明显 */
    SENSOR_ABNORMAL_OR_QUANTIZED,

    /** 存在大跨度时间断层或断续休眠挂起 */
    DISCRETE_TIME_GAP_DETECTED
}

/**
 * 息屏待机基线与深睡功耗多阶综合估计结果数据类。
 * 遵循严格的数据独立性与物理语义分离原则，严禁残差互甩或将估计值伪造为实测。
 *
 * @property baselinePowerWatts 从经过有效状态筛选的固定长度窗口代表功率计算的 P20 主基线（单位：瓦特 W，典型深睡静态基线候选）
 * @property source 待机基线功率来源 [StandbyBaselineSource]
 * @property confidence 估计结果置信度等级 [StandbyConfidenceLevel]
 * @property validWindowCount 参与有效统计的时间窗口总数
 * @property coverageDurationMs 实际连续覆盖的物理采样时长（单位：毫秒）
 * @property floorWatts 有效深睡固定窗口功耗分布的低分位参考值（单位：W，P10，用于低功耗水平诊断，不代表硬件理论下限）
 * @property sleepMeanWatts 状态明确且覆盖可靠区间内的梯形时间积分观测平均功耗（单位：W，覆盖不足时为 null，绝不伪造）
 * @property sleepCoverageRatio 有效无重叠采样时间覆盖率（0.0 ~ 1.0）
 * @property isMeanObserved 均值是否为真实时间积分观测值（true: 梯形微积分直接观测; false: 覆盖不足/无实测均值）
 * @property p5Watts 窗口级代表功率第 5 百分位数（单位：W，用于诊断低端离散度与下界，可为 null）
 * @property p10Watts 窗口级代表功率第 10 百分位数（单位：W，诊断用低分位参考，可为 null）
 * @property p15Watts 窗口级代表功率第 15 百分位数（单位：W，诊断用低位基线值，可为 null）
 * @property p20Watts 窗口级代表功率第 20 百分位数（单位：W，主基线值，可为 null）
 * @property p25Watts 窗口级代表功率第 25 百分位数（单位：W，诊断用低位功耗基准，可为 null）
 * @property p30Watts 窗口级代表功率第 30 百分位数（单位：W，用于 P20 平坦度校验基准，可为 null）
 * @property p50Watts 窗口级代表功率中位数（单位：W，诊断用窗口中位功耗，可为 null）
 * @property madWatts 窗口级代表功率绝对中位差 MAD（单位：W，衡量窗口间稳健离散度，可为 null）
 * @property quantileGapW P30 与 P20 的绝对分位数差（单位：W，衡量低功耗平台平坦度）
 * @property relativeQuantileGap P30 与 P20 的相对分位数差（(P30 - P20) / max(P30, epsilon)）
 * @property rawMinWatts 原始采样点中的物理极小值（单位：W，仅用于硬件底噪与异常诊断，严禁直接支配基线）
 * @property reasonCodes 触发门禁、降级或回退的结构化原因码列表
 * @property fallbackReason 触发回退先验或覆盖不足的具体原因文本说明（兼容旧接口）
 */
data class StandbyBaselineEstimate(
    val baselinePowerWatts: Double,
    val source: StandbyBaselineSource,
    val confidence: StandbyConfidenceLevel,
    val validWindowCount: Int,
    val coverageDurationMs: Long,
    val floorWatts: Double? = null,
    val sleepMeanWatts: Double? = null,
    val sleepCoverageRatio: Double = 0.0,
    val isMeanObserved: Boolean = false,
    val p5Watts: Double? = null,
    val p10Watts: Double? = null,
    val p15Watts: Double? = null,
    val p20Watts: Double? = null,
    val p25Watts: Double? = null,
    val p30Watts: Double? = null,
    val p50Watts: Double? = null,
    val madWatts: Double? = null,
    val quantileGapW: Double? = null,
    val relativeQuantileGap: Double? = null,
    val rawMinWatts: Double? = null,
    val reasonCodes: List<StandbyReasonCode> = emptyList(),
    val fallbackReason: String? = null
)

/**
 * 息屏待机基线估计引擎配置参数类。
 *
 * @property defaultPriorWatts 缺少可靠实测样本时的初始默认待机先验功率（单位：W，默认 0.15W）
 * @property suspiciousPollutionThresholdWatts 疑似活跃任务污染判定门限（单位：W，默认 0.20W，高于此门限触发污染风险诊断）
 * @property baselineQuantile 用于作为待机基线的主分位点（默认 0.20 即 P20 主基线分位数）
 * @property checkQuantile 用于校验待机基线平坦度的一致性分位点（默认 0.30 即 P30 校验分位数）
 * @property floorQuantile 用于诊断低功耗下界的分位点（默认 0.10 即 P10）
 * @property diagnosticLowerQuantile 用于诊断极低端离散度的下界分位点（默认 0.05 即 P5）
 * @property maxAllowedRelativeQuantileGap 最大允许的 P30 与 P20 相对偏差率（默认 0.40 即 40%）
 * @property maxAllowedAbsoluteQuantileGapWatts 最大允许的 P30 与 P20 绝对偏差门限（单位：W，默认 0.05W）
 * @property epsilonW 计算相对偏差时的分母保护偏置项（单位：W，默认 0.02W）
 * @property baseWindowDurationMs 基础固定长度时间聚合窗口长度（单位：毫秒，默认 60,000ms 即 1 分钟）
 * @property minSamplesPerWindow 单个时间窗口判定为有效所需的最少有效采样点数（默认 2 个）
 * @property minWindowsForHighConfidence 判定为高置信度所需的最少有效时间窗口数（默认 30 个，约 30 分钟）
 * @property minWindowsForMediumConfidence 判定为中置信度所需的最少有效时间窗口数（默认 10 个，约 10 分钟）
 * @property minWindowsForLowConfidence 判定为低置信度试算所需的最少有效时间窗口数（默认 2 个，少于此数则判定为 UNAVAILABLE）
 * @property minLowPowerSupportWindows 采信实测低基线所需的低功耗有效窗口重复支持数（默认至少 2 个窗口）
 * @property maxInterpolationGapMs 梯形时间积分最大允许插值时间间隔（单位：毫秒，默认 30,000ms 即 30 秒，超时间隔视作断层阻断插值）
 * @property minCoverageRatioForMean 确认输出实测观测均值所需的最低连续采样覆盖率门限（默认 0.70 即 70%）
 */
data class StandbyBaselineConfig(
    val defaultPriorWatts: Double = 0.15,
    val suspiciousPollutionThresholdWatts: Double = 0.20,
    val baselineQuantile: Double = 0.20,
    val checkQuantile: Double = 0.30,
    val floorQuantile: Double = 0.10,
    val diagnosticLowerQuantile: Double = 0.05,
    val maxAllowedRelativeQuantileGap: Double = 0.40,
    val maxAllowedAbsoluteQuantileGapWatts: Double = 0.05,
    val epsilonW: Double = 0.02,
    val baseWindowDurationMs: Long = 60_000L,
    val minSamplesPerWindow: Int = 2,
    val minWindowsForHighConfidence: Int = 30,
    val minWindowsForMediumConfidence: Int = 10,
    val minWindowsForLowConfidence: Int = 2,
    val minLowPowerSupportWindows: Int = 2,
    val maxInterpolationGapMs: Long = 30_000L,
    val minCoverageRatioForMean: Double = 0.70
)
