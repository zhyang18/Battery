package com.battery.analysis.domain.estimator.model

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 能量模型与置信度质量评估等级枚举。
 */
enum class ConfidenceLevel {
    /** 高置信度：覆盖率高、样本充足且离散度低 */
    HIGH,
    /** 中置信度：已有若干独立样本，正在向稳态收敛 */
    MEDIUM,
    /** 低置信度：冷启动初期或样本稀少 */
    LOW,
    /** 不可靠：数据严重缺失或离散度过大 */
    UNRELIABLE
}

/**
 * 历史放电能量曲线模型成熟度阶段枚举。
 */
enum class ModelMaturity {
    /** 冷启动阶段：尚无足够独立会话样本 */
    COLD_START,
    /** 发展收敛中：已有部分区间样本，正在扩展覆盖 */
    DEVELOPING,
    /** 已收敛成熟：关键放电区间覆盖完备且统计稳定 */
    CONVERGED
}

/**
 * 单次放电会话在单个 SOC 区间的采样质量等级枚举。
 */
enum class SampleQuality {
    /** 完整区间样本：在该区间有效覆盖率达到 90% 以上且无采样断层 */
    COMPLETE_BIN,
    /** 部分覆盖切片：有效覆盖率低于 90%，仅作为部分碎片暂存 */
    PARTIAL_BIN,
    /** 不可靠样本：存在超长大跨度深睡断层或异常功率抖动 */
    UNRELIABLE
}

/**
 * SOC 区间能量估计来源枚举。
 * 遵循严格的数据血统追踪原则，明确区分实测、插值与物理先验来源。
 */
enum class EnergyEstimateSource {
    /** 历史独立放电会话真实梯形微积分实测统计 */
    MEASURED_LEARNED,
    /** 基于双侧相邻稳定区间的放电电压线性插值推算（仅限平台区且具备双侧实测） */
    INTERPOLATED,
    /** 基于基准容量与动态工况电压先验物理模型补全 */
    MODEL_PRIOR,
    /** 经设备交叉验证可信的硬件能量计直读折算 */
    HARDWARE_VALIDATED,
    /** 缺乏可靠实测与先验依据，如实未知（严禁伪造数据） */
    UNKNOWN
}

/**
 * 运行时动态解析后的单段 SOC 能量区间实体（ResolvedSocEnergyBin）。
 * 在估算期或看板展示时由算法在内存中动态推导生成，严禁写入持久化实测样本库，
 * 从架构底层确保历史样本数据的纯洁性与可溯源性。
 *
 * @property binIndex 区间索引号（0..19，0 对应 0%~5%，19 对应 95%~100%）
 * @property minSocPercent 区间下界电量百分比（如 60.0f）
 * @property maxSocPercent 区间上界电量百分比（如 65.0f）
 * @property measuredEnergyWh 真实历史实测能量（单位：Wh，若无样本为 null）
 * @property modeledEnergyWh 相邻插值或物理先验模型推演补全能量（单位：Wh，未启用为 null）
 * @property effectiveEnergyWh 当前最终采用的有效估计能量（单位：Wh，若未知为 null）
 * @property source 本区间能量的数据来源 [EnergyEstimateSource]
 * @property uncertaintyWh 本区间估计的不确定度标准差（单位：Wh，若无法评估为 null）
 * @property sampleCount 历史实测有效样本计数
 * @property rationale 算法决策依据简述
 */
data class ResolvedSocEnergyBin(
    val binIndex: Int,
    val minSocPercent: Float,
    val maxSocPercent: Float,
    val measuredEnergyWh: Double?,
    val modeledEnergyWh: Double?,
    val effectiveEnergyWh: Double?,
    val source: EnergyEstimateSource,
    val uncertaintyWh: Double? = null,
    val sampleCount: Int = 0,
    val rationale: String = ""
) {
    /**
     * 格式化获取该区间的显示标签（例如 "95% - 100%"）。
     *
     * @return 区间电量范围格式化字符串
     */
    fun getLabel(): String {
        return String.format(Locale.US, "%.0f%% - %.0f%%", minSocPercent, maxSocPercent)
    }
}

/**
 * 电池当前剩余可用能量混合估算最终实体（HybridRemainingEnergyEstimate）。
 * 遵循严格的数据血统追踪与零虚构数据原则：未知能量严禁默认为 0，如实标注为 null。
 *
 * @property remainingEnergyWh 当前剩余可用总能量（单位：Wh，若关键区间存在未知且无法可靠估算则为 null）
 * @property measuredEnergyWh 纯实测历史学习区间的能量累加贡献（单位：Wh）
 * @property modeledEnergyWh 相邻插值与物理模型推演的能量累加贡献（单位：Wh）
 * @property unknownEnergyWh 缺失且无法可靠估计的能量部分（严禁假数据填 0，未知必须为 null）
 * @property uncertaintyWh 剩余能量综合合成不确定度（单位：Wh，采用分块相关模型计算）
 * @property measuredSocCoverage 当前所需 SOC 范围中实测区间的覆盖比例（0.0f ~ 1.0f）
 * @property modeledSocCoverage 当前所需 SOC 范围中模型补全区间的覆盖比例（0.0f ~ 1.0f）
 * @property unknownSocCoverage 当前所需 SOC 范围中未知区间的覆盖比例（0.0f ~ 1.0f）
 * @property confidence 综合质量置信度评级 [ConfidenceLevel]
 * @property maturity 模型成熟度 [ModelMaturity]
 * @property resolvedBins 当前 SOC 范围所涉及的所有已解析区间列表
 * @property sourceBreakdown 各数据源能量贡献分解映射表
 */
data class HybridRemainingEnergyEstimate(
    val remainingEnergyWh: Double?,
    val measuredEnergyWh: Double,
    val modeledEnergyWh: Double,
    val unknownEnergyWh: Double?,
    val uncertaintyWh: Double?,
    val measuredSocCoverage: Float,
    val modeledSocCoverage: Float,
    val unknownSocCoverage: Float,
    val confidence: ConfidenceLevel,
    val maturity: ModelMaturity,
    val resolvedBins: List<ResolvedSocEnergyBin>,
    val sourceBreakdown: Map<EnergyEstimateSource, Double>
) {
    /** 是否全部来自实测学习（无插值或模型补全），即覆盖完整 */
    val isFullyLearned: Boolean
        get() = modeledEnergyWh == 0.0 && unknownEnergyWh == null && measuredSocCoverage >= 0.999f
}

/**
 * 单次放电会话在单个 5% SOC 物理区间捕获的独立样本实体。
 *
 * @property binIndex 区间索引号（0..19）
 * @property energyWh 本次会话在该区间释放的物理能量（单位：Wh）
 * @property durationMs 本次会话在该区间的有效放电时长（毫秒）
 * @property startSocPercent 本次切片在该区间的起始电量百分比
 * @property endSocPercent 本次切片在该区间的结束电量百分比
 * @property coverageFraction 该切片在该区间实际走过的有效电量跨度比例（0.0 至 1.0）
 * @property temperatureC 本次切片在区间的加权平均温度（℃，可选）
 * @property averageDischargeCRate 本次切片相对于电池容量的平均放电倍率（C-rate = |I| / FCC，可选）
 * @property sessionId 产生该样本的独立放电会话标识 ID
 * @property quality 样本物理质量等级 [SampleQuality]
 */
data class SocEnergyBinSample(
    val binIndex: Int,
    val energyWh: Double,
    val durationMs: Long,
    val startSocPercent: Float,
    val endSocPercent: Float,
    val coverageFraction: Double,
    val temperatureC: Double? = null,
    val averageDischargeCRate: Double? = null,
    val sessionId: String = "",
    val quality: SampleQuality = SampleQuality.COMPLETE_BIN
)

/**
 * 单个 5% SOC 物理放电区间历史做功与能量统计实体。
 * 将 0.0% 至 100.0% 的电量划分为 20 个固定的 5% 微分区间（binIndex: 0 至 19）。
 * 遵循严格的物理能量守恒：区间释放能量来自端电压与放电电流梯形微积分实测累加，严禁虚构。
 *
 * @property binIndex 区间索引号（取值范围 0..19，0 对应 0%~5%，19 对应 95%~100%）
 * @property minSocPercent 区间下界电量百分比（如 95.0f）
 * @property maxSocPercent 区间上界电量百分比（如 100.0f）
 * @property learnedEnergyWh 基于独立合格放电会话历史样本鲁棒统计得出的释放能量（单位：Wh，若无样本则为 null）
 * @property sampleCount 该区间累计捕获的合格独立放电会话样本总数（绝非零散采样点数）
 * @property coverageFraction 历史样本对该区间的平均有效电量覆盖比例（0.0 至 1.0）
 * @property dispersionWh 历史样本释放能量的中位数绝对偏差（MAD），表征工况离散度（单位：Wh，可选）
 * @property medianTemperatureC 历史合格样本的中位数工作温度（单位：℃，可选）
 * @property medianDischargeCRate 历史合格样本的中位数放电倍率（C-rate，可选）
 * @property lastUpdatedTimestamp 最近一次更新该区间统计数据的系统时间戳（毫秒）
 * @property minObservedWh 历史记录中该区间单次放电释放能量的物理极小值（单位：Wh，可选）
 * @property maxObservedWh 历史记录中该区间单次放电释放能量的物理极大值（单位：Wh，可选）
 */
data class SocEnergyBin(
    val binIndex: Int,
    val minSocPercent: Float,
    val maxSocPercent: Float,
    val learnedEnergyWh: Double?,
    val sampleCount: Int,
    val coverageFraction: Double = 0.0,
    val dispersionWh: Double? = null,
    val medianTemperatureC: Double? = null,
    val medianDischargeCRate: Double? = null,
    val lastUpdatedTimestamp: Long = 0L,
    val minObservedWh: Double? = null,
    val maxObservedWh: Double? = null
) {
    /**
     * 判断当前区间是否已具备可信的学习样本数据。
     *
     * @return 若独立样本数大于 0 且学习能量有效且为正有限值返回 true，否则返回 false
     */
    fun hasLearnedData(): Boolean {
        return sampleCount > 0 && learnedEnergyWh != null && learnedEnergyWh > 0.0 && learnedEnergyWh.isFinite()
    }

    /**
     * 格式化获取该区间的显示标签（例如 "95% - 100%"）。
     *
     * @return 区间电量范围格式化字符串
     */
    fun getLabel(): String {
        return String.format(java.util.Locale.US, "%.0f%% - %.0f%%", minSocPercent, maxSocPercent)
    }
}

/**
 * 完整 20 段 SOC 放电能量学习曲线聚合实体。
 * 基于设备端电压与电流实测微积分以及历史独立放电会话自适应学习建立。
 *
 * @property bins 20 个 5% SOC 区间的统计列表（固定按 binIndex 升序排列，长度恒为 20）
 * @property coveragePercent 20 个区间中已建立有效学习数据的覆盖率（0.0f ~ 1.0f）
 * @property estimatedFullUsableEnergyWh 各区间能量在基准工况下组合推算出的电池满放电总可用能量（单位：Wh，未全覆盖时为 null）
 * @property confidence 当前学习曲线的整体数据置信度评级 [ConfidenceLevel]
 * @property maturity 当前学习曲线的模型成熟度评级 [ModelMaturity]
 * @property curveVersion 数据结构与存储协议版本号（当前为版本 2）
 * @property totalSampleSessions 参与构建曲线的历史独立放电会话总次数
 * @property lastLearnedTimestamp 学习曲线整体最后一次更新的系统时间戳
 */
data class LearnedSocEnergyCurve(
    val bins: List<SocEnergyBin>,
    val coveragePercent: Float,
    val estimatedFullUsableEnergyWh: Double?,
    val confidence: ConfidenceLevel = ConfidenceLevel.LOW,
    val maturity: ModelMaturity = ModelMaturity.COLD_START,
    val curveVersion: Int = 2,
    val totalSampleSessions: Int,
    val lastLearnedTimestamp: Long
) {
    /** 兼容旧代码的别名属性：满可用能量推算值（Wh） */
    val totalLearnedEnergyWh: Double?
        get() = estimatedFullUsableEnergyWh

    /**
     * 根据当前设备的 SOC 电量百分比，累加推算所有小于等于当前 SOC 的区间历史学习可用剩余能量。
     * 累加公式：E_remain(SOC) = sum_{bin < currentBin} E_bin + (当前所在 bin 的部分线性切片比例 * 该 bin 能量)。
     * 遵循物理单调性与零假数据：若所涉及的关键区间缺少数据，返回 null。
     *
     * @param currentSocPercent 当前设备电量百分比（0.0f ~ 100.0f）
     * @return 基于实测放电历史学习累加的剩余可用能量（单位：Wh，若关键数据完全缺失返回 null）
     */
    fun estimateRemainingEnergyWh(currentSocPercent: Float): Double? {
        if (currentSocPercent <= 0.0f) return 0.0
        val clampedSoc = currentSocPercent.coerceIn(0.0f, 100.0f)

        var totalWh = 0.0
        var coveredSegments = 0
        var targetSegments = 0

        for (bin in bins) {
            if (bin.maxSocPercent <= clampedSoc) {
                // 完全处于当前剩余电量以下的整区间
                targetSegments++
                if (bin.hasLearnedData()) {
                    totalWh += bin.learnedEnergyWh!!
                    coveredSegments++
                }
            } else if (bin.minSocPercent < clampedSoc) {
                // 当前 SOC 正好落在此区间内部，按区间内部电量比例切分累加
                targetSegments++
                if (bin.hasLearnedData()) {
                    val ratio = (clampedSoc - bin.minSocPercent) / (bin.maxSocPercent - bin.minSocPercent)
                    totalWh += bin.learnedEnergyWh!! * ratio.coerceIn(0.0f, 1.0f)
                    coveredSegments++
                }
            }
        }

        // 若所需区间完全没有学习到数据，忠实返回 null
        if (targetSegments == 0 || coveredSegments == 0) {
            return null
        }
        return totalWh
    }

    /**
     * 纯算法函数：动态解析全部 20 个 SOC 区间的能量估算状态（运行时解析态）。
     * 严格遵循“物理模型补全不等于伪造实测数据”的铁律：补全结果仅在内存中即时生成，
     * 严禁写入本地历史实测样本库，确保历史数据纯洁性与可溯源性。
     *
     * 优先级仲裁逻辑：
     * 1. 真实实测（MEASURED_LEARNED）：区间已累积合格放电会话样本；
     * 2. 相邻插值（INTERPOLATED）：仅限处于平台稳定区（SOC >= 15.0%，即 binIndex >= 3），且左侧与右侧均具备合格实测数据；低 SOC 拐点区（0%~15%）与首尾单侧边界严禁线性插值；
     * 3. 物理先验模型（MODEL_PRIOR）：在无法插值但基准容量有效时，基于 FCC、非线性经验放电电压与末端截止裕度推演；
     * 4. 无法估计（UNKNOWN）：若无实测且基准容量不可用，如实标记为未知。
     *
     * @param effectiveCapacityMah 设备有效满充基准容量（单位：mAh，无可用容量时传入 null）
     * @param nominalVoltageVolts 电池标称电压（单位：V，默认 3.85V）
     * @param currentDischargeCurrentMa 当前实时放电电流（单位：mA，用于动态工况内阻压降微调，可选）
     * @param temperatureCelsius 当前电池温度（单位：℃，可选）
     * @return 20 个区间解析后的实体列表 [List<ResolvedSocEnergyBin>]
     */
    fun resolveAllBins(
        effectiveCapacityMah: Double? = null,
        nominalVoltageVolts: Double = 3.85,
        currentDischargeCurrentMa: Double? = null,
        temperatureCelsius: Float? = null
    ): List<ResolvedSocEnergyBin> {
        return bins.map { bin ->
            val index = bin.binIndex
            if (bin.hasLearnedData()) {
                val measured = bin.learnedEnergyWh!!
                // 实测不确定度：基于历史样本 MAD 标准误估计，若样本不足 2 个取 3%
                val uMeas = if (bin.dispersionWh != null && bin.dispersionWh > 0.0 && bin.sampleCount >= 2) {
                    (1.4826 * bin.dispersionWh) / sqrt(bin.sampleCount.toDouble())
                } else {
                    measured * 0.03
                }
                ResolvedSocEnergyBin(
                    binIndex = index,
                    minSocPercent = bin.minSocPercent,
                    maxSocPercent = bin.maxSocPercent,
                    measuredEnergyWh = measured,
                    modeledEnergyWh = null,
                    effectiveEnergyWh = measured,
                    source = EnergyEstimateSource.MEASURED_LEARNED,
                    uncertaintyWh = uMeas,
                    sampleCount = bin.sampleCount,
                    rationale = "历史放电实测 (${bin.sampleCount}次样本)"
                )
            } else {
                // 门限 1：低 SOC 拐点陡降区（0%~15%，即 binIndex < 3）严禁线性插值
                // 门限 2：首尾单侧边界（index == 0 或 index == BIN_COUNT - 1）严禁无支撑外推
                // 门限 3：双侧相邻区间必须同时具备合格实测数据
                val canInterpolate = index in 3 until (BIN_COUNT - 1) &&
                        bins[index - 1].hasLearnedData() &&
                        bins[index + 1].hasLearnedData()

                if (canInterpolate) {
                    val leftWh = bins[index - 1].learnedEnergyWh!!
                    val rightWh = bins[index + 1].learnedEnergyWh!!
                    val interpWh = (leftWh + rightWh) / 2.0
                    ResolvedSocEnergyBin(
                        binIndex = index,
                        minSocPercent = bin.minSocPercent,
                        maxSocPercent = bin.maxSocPercent,
                        measuredEnergyWh = null,
                        modeledEnergyWh = interpWh,
                        effectiveEnergyWh = interpWh,
                        source = EnergyEstimateSource.INTERPOLATED,
                        uncertaintyWh = interpWh * 0.06, // 插值结构不确定度取 6%
                        sampleCount = 0,
                        rationale = "双侧实测相邻电压线性插值"
                    )
                } else if (effectiveCapacityMah != null && effectiveCapacityMah > 0.0 && effectiveCapacityMah.isFinite()) {
                    // 物理先验模型补全
                    var deltaQMah = effectiveCapacityMah * (BIN_SOC_STEP / 100.0)
                    // 0%~5% 拐点区考虑厂商 BMS 锁容与截止保护，施加保守收敛裕度系数 0.90
                    if (index == 0) {
                        deltaQMah *= 0.90
                    }

                    // 电化学经验电压模型
                    val baseVoltage = when (index) {
                        0 -> 3.45 // 0%~5% 接近截止电压
                        1 -> 3.60 // 5%~10% 陡降过渡
                        2 -> 3.70 // 10%~15% 拐点初段
                        in 3..5 -> 3.75 // 15%~30% 平台前部
                        in 6..14 -> nominalVoltageVolts.coerceIn(3.70, 3.90) // 30%~75% 主平坦区
                        in 15..17 -> 3.95 // 75%~90% 高压区
                        else -> 4.05 // 90%~100% 满电高压区
                    }

                    // 动态工况端电压微调（Load-adaptive IR drop，结合温度对电解液活性与内阻的电化学规律）
                    val adjustedVoltage = if (currentDischargeCurrentMa != null && currentDischargeCurrentMa > 0.0) {
                        val rInternal = when {
                            temperatureCelsius == null -> 0.04
                            temperatureCelsius < 0f -> 0.10 // 零度以下电解液黏度激增，内阻上升至 ~100mΩ
                            temperatureCelsius < 15f -> 0.06 // 低温过渡区 ~60mΩ
                            temperatureCelsius > 40f -> 0.035 // 高温活性提升，内阻略微降低至 ~35mΩ
                            else -> 0.04 // 常温基准 ~40mΩ
                        }
                        val irDrop = (currentDischargeCurrentMa / 1000.0) * rInternal
                        (baseVoltage - irDrop).coerceIn(3.10, 4.35)
                    } else {
                        baseVoltage
                    }

                    val priorWh = (deltaQMah * adjustedVoltage) / 1000.0
                    ResolvedSocEnergyBin(
                        binIndex = index,
                        minSocPercent = bin.minSocPercent,
                        maxSocPercent = bin.maxSocPercent,
                        measuredEnergyWh = null,
                        modeledEnergyWh = priorWh,
                        effectiveEnergyWh = priorWh,
                        source = EnergyEstimateSource.MODEL_PRIOR,
                        uncertaintyWh = priorWh * 0.10, // 先验模型不确定度保守取 10%
                        sampleCount = 0,
                        rationale = "物理先验模型 (容量 + 经验电压)"
                    )
                } else {
                    // 无法可靠推算，严格遵循零假数据原则如实返回未知
                    ResolvedSocEnergyBin(
                        binIndex = index,
                        minSocPercent = bin.minSocPercent,
                        maxSocPercent = bin.maxSocPercent,
                        measuredEnergyWh = null,
                        modeledEnergyWh = null,
                        effectiveEnergyWh = null,
                        source = EnergyEstimateSource.UNKNOWN,
                        uncertaintyWh = null,
                        sampleCount = 0,
                        rationale = "无有效实测样本且基准容量不可用"
                    )
                }
            }
        }
    }

    /**
     * 纯算法函数：基于三层混合估算机制（Hybrid Remaining Energy Estimation）计算当前剩余可用电能。
     *
     * 核心算法与物理准则：
     * 1. 20 段 SOC 区间细粒度血统解析：已学习优先实测，平台区孤立缺失相邻插值，非线性拐点区与连续缺失使用物理模型，无依据区间如实未知；
     * 2. 分块相关不确定度传播（Block-Correlated Uncertainty）：实测区间按方差独立累加，共享容量模型的推演区间按线性强相关绝对累加，合成严谨总不确定度；
     * 3. 严格零虚假数据原则：若所需覆盖的区间中包含任何 UNKNOWN 未知区间，总剩余能量忠实返回 null，绝不假定为 0 Wh 或粗暴放大。
     *
     * @param currentSocPercent 当前设备电量百分比（0.0f ~ 100.0f）
     * @param effectiveCapacityMah 设备基准满充容量（单位：mAh，可选）
     * @param nominalVoltageVolts 电池标称电压（单位：V，默认 3.85V）
     * @param currentDischargeCurrentMa 当前实时放电电流（单位：mA，用于动态工况内阻压降微调，可选）
     * @param temperatureCelsius 当前电池温度（单位：℃，可选）
     * @return 混合剩余能量估算结果 [HybridRemainingEnergyEstimate]，若当前 SOC <= 0 或全无依据返回 null
     */
    fun estimateHybridRemainingEnergy(
        currentSocPercent: Float,
        effectiveCapacityMah: Double? = null,
        nominalVoltageVolts: Double = 3.85,
        currentDischargeCurrentMa: Double? = null,
        temperatureCelsius: Float? = null
    ): HybridRemainingEnergyEstimate? {
        if (currentSocPercent <= 0.0f) {
            return HybridRemainingEnergyEstimate(
                remainingEnergyWh = 0.0,
                measuredEnergyWh = 0.0,
                modeledEnergyWh = 0.0,
                unknownEnergyWh = null,
                uncertaintyWh = 0.0,
                measuredSocCoverage = 0.0f,
                modeledSocCoverage = 0.0f,
                unknownSocCoverage = 0.0f,
                confidence = ConfidenceLevel.HIGH,
                maturity = maturity,
                resolvedBins = emptyList(),
                sourceBreakdown = emptyMap()
            )
        }

        val clampedSoc = currentSocPercent.coerceIn(0.0f, 100.0f)
        val allResolved = resolveAllBins(
            effectiveCapacityMah = effectiveCapacityMah,
            nominalVoltageVolts = nominalVoltageVolts,
            currentDischargeCurrentMa = currentDischargeCurrentMa,
            temperatureCelsius = temperatureCelsius
        )

        var measuredWh = 0.0
        var modeledWh = 0.0
        var measuredSocSpan = 0.0f
        var modeledSocSpan = 0.0f
        var unknownSocSpan = 0.0f
        var hasUnknown = false

        var measuredVarianceSum = 0.0
        var modeledLinearUncertaintySum = 0.0

        val relevantBins = mutableListOf<ResolvedSocEnergyBin>()
        val breakdown = mutableMapOf<EnergyEstimateSource, Double>()

        for (resolved in allResolved) {
            val ratio: Float
            when {
                resolved.maxSocPercent <= clampedSoc -> ratio = 1.0f
                resolved.minSocPercent < clampedSoc -> {
                    ratio = ((clampedSoc - resolved.minSocPercent) / BIN_SOC_STEP).coerceIn(0.0f, 1.0f)
                }
                else -> continue
            }

            relevantBins.add(resolved)
            val span = BIN_SOC_STEP * ratio

            when (resolved.source) {
                EnergyEstimateSource.MEASURED_LEARNED -> {
                    val partWh = (resolved.effectiveEnergyWh ?: 0.0) * ratio
                    measuredWh += partWh
                    measuredSocSpan += span
                    val u = (resolved.uncertaintyWh ?: (partWh * 0.03)) * ratio
                    measuredVarianceSum += (u * u)
                    breakdown[resolved.source] = (breakdown[resolved.source] ?: 0.0) + partWh
                }
                EnergyEstimateSource.INTERPOLATED,
                EnergyEstimateSource.MODEL_PRIOR -> {
                    val partWh = (resolved.effectiveEnergyWh ?: 0.0) * ratio
                    modeledWh += partWh
                    modeledSocSpan += span
                    val u = (resolved.uncertaintyWh ?: (partWh * 0.10)) * ratio
                    modeledLinearUncertaintySum += u
                    breakdown[resolved.source] = (breakdown[resolved.source] ?: 0.0) + partWh
                }
                EnergyEstimateSource.HARDWARE_VALIDATED -> {
                    val partWh = (resolved.effectiveEnergyWh ?: 0.0) * ratio
                    measuredWh += partWh
                    measuredSocSpan += span
                    breakdown[resolved.source] = (breakdown[resolved.source] ?: 0.0) + partWh
                }
                EnergyEstimateSource.UNKNOWN -> {
                    unknownSocSpan += span
                    hasUnknown = true
                }
            }
        }

        if (relevantBins.isEmpty()) return null

        val measuredSocCov = (measuredSocSpan / clampedSoc).coerceIn(0.0f, 1.0f)
        val modeledSocCov = (modeledSocSpan / clampedSoc).coerceIn(0.0f, 1.0f)
        val unknownSocCov = (unknownSocSpan / clampedSoc).coerceIn(0.0f, 1.0f)

        // 若存在无法可靠估计的 UNKNOWN 区间，总剩余能量忠实返回 null，绝不隐瞒或虚构
        val totalWh = if (hasUnknown) null else (measuredWh + modeledWh)
        val uncertainty = if (hasUnknown) null else sqrt(measuredVarianceSum + (modeledLinearUncertaintySum * modeledLinearUncertaintySum))

        // 动态置信度评估
        val evaluatedConfidence = when {
            hasUnknown -> ConfidenceLevel.UNRELIABLE
            measuredSocCov >= 0.80f && maturity == ModelMaturity.CONVERGED -> ConfidenceLevel.HIGH
            measuredSocCov >= 0.40f -> ConfidenceLevel.MEDIUM
            measuredSocCov > 0.0f || modeledSocCov > 0.0f -> ConfidenceLevel.LOW
            else -> ConfidenceLevel.UNRELIABLE
        }

        return HybridRemainingEnergyEstimate(
            remainingEnergyWh = totalWh,
            measuredEnergyWh = measuredWh,
            modeledEnergyWh = modeledWh,
            unknownEnergyWh = null,
            uncertaintyWh = uncertainty,
            measuredSocCoverage = measuredSocCov,
            modeledSocCoverage = modeledSocCov,
            unknownSocCoverage = unknownSocCov,
            confidence = evaluatedConfidence,
            maturity = maturity,
            resolvedBins = relevantBins,
            sourceBreakdown = breakdown
        )
    }

    /**
     * 混合估算模式：已学习区间采用实测能量，未学习区间用标称折算补偿（方案A）。
     *
     * 物理依据：E_nominal_per_bin = FCC(mAh) × V_nominal(V) × 5% / 1000.0
     * 未学习区间按标称折算代入，确保总剩余能量贴近真实值，同时标注补偿来源。
     * 仅在有至少一个已学习区间时返回非 null；完全冷启动无数据时仍返回 null 保持零假数据原则。
     *
     * @param currentSocPercent 当前设备电量百分比（0.0f ~ 100.0f）
     * @param nominalWhPerBin 每个完整 5% 区间对应的标称折算能量（单位：Wh）；传入 null 则退化为严格实测模式
     * @return 混合估算结果 [MixedEnergyEstimate]，若完全无学习数据返回 null
     */
    fun estimateRemainingEnergyWhMixed(
        currentSocPercent: Float,
        nominalWhPerBin: Double?
    ): MixedEnergyEstimate? {
        if (currentSocPercent <= 0.0f) {
            return MixedEnergyEstimate(
                totalWh = 0.0,
                learnedWh = 0.0,
                nominalCompensatedWh = 0.0,
                learnedBinCount = 0,
                totalBinCount = 0
            )
        }
        val clampedSoc = currentSocPercent.coerceIn(0.0f, 100.0f)

        var learnedWh = 0.0
        var compensatedWh = 0.0
        var learnedBinCount = 0
        var totalBinCount = 0

        for (bin in bins) {
            val ratio: Float = when {
                bin.maxSocPercent <= clampedSoc -> 1.0f
                bin.minSocPercent < clampedSoc -> {
                    ((clampedSoc - bin.minSocPercent) / (bin.maxSocPercent - bin.minSocPercent))
                        .coerceIn(0.0f, 1.0f)
                }
                else -> continue
            }

            totalBinCount++
            if (bin.hasLearnedData()) {
                learnedWh += bin.learnedEnergyWh!! * ratio
                learnedBinCount++
            } else if (nominalWhPerBin != null) {
                // 未学习区间：用标称折算值按比例补偿，明确标注为估算来源
                compensatedWh += nominalWhPerBin * ratio
            }
        }

        // 完全冷启动：没有任何已学习区间，严格返回 null 保持零假数据原则
        if (learnedBinCount == 0) return null

        return MixedEnergyEstimate(
            totalWh = learnedWh + compensatedWh,
            learnedWh = learnedWh,
            nominalCompensatedWh = compensatedWh,
            learnedBinCount = learnedBinCount,
            totalBinCount = totalBinCount
        )
    }

    /**
     * 混合估算结果数据类。
     *
     * @property totalWh 实测学习能量与标称补偿能量之和（单位：Wh）
     * @property learnedWh 仅来自已学习区间的实测累加能量（单位：Wh）
     * @property nominalCompensatedWh 未学习区间标称折算的补偿能量（单位：Wh，若全部学习则为 0.0）
     * @property learnedBinCount 已学习的区间数量（≥1 时结果有效）
     * @property totalBinCount 当前 SOC 以下所需覆盖的总区间数量
     */
    data class MixedEnergyEstimate(
        val totalWh: Double,
        val learnedWh: Double,
        val nominalCompensatedWh: Double,
        val learnedBinCount: Int,
        val totalBinCount: Int
    ) {
        /** 是否全部来自实测学习（无标称补偿），即覆盖完整 */
        val isFullyLearned: Boolean get() = nominalCompensatedWh == 0.0 && learnedBinCount == totalBinCount
        /** 已学习区间占目标区间的比例（0.0 ~ 1.0） */
        val learnedCoverage: Float get() = if (totalBinCount > 0) learnedBinCount.toFloat() / totalBinCount else 0f
    }

    companion object {
        /** 总分段数（固定为 20 个 5% 区间） */
        const val BIN_COUNT = 20

        /** 单个区间的 SOC 步长百分比 */
        const val BIN_SOC_STEP = 5.0f

        /**
         * 创建一个初始全空的 20 段 SOC 能量学习曲线实例（用于冷启动初始化）。
         *
         * @return 初始化的学习曲线实体，所有区间状态均为待采集
         */
        fun createEmpty(): LearnedSocEnergyCurve {
            val emptyBins = (0 until BIN_COUNT).map { index ->
                val min = index * BIN_SOC_STEP
                val max = min + BIN_SOC_STEP
                SocEnergyBin(
                    binIndex = index,
                    minSocPercent = min,
                    maxSocPercent = max,
                    learnedEnergyWh = null,
                    sampleCount = 0,
                    coverageFraction = 0.0,
                    dispersionWh = null,
                    medianTemperatureC = null,
                    medianDischargeCRate = null,
                    lastUpdatedTimestamp = 0L
                )
            }
            return LearnedSocEnergyCurve(
                bins = emptyBins,
                coveragePercent = 0.0f,
                estimatedFullUsableEnergyWh = null,
                confidence = ConfidenceLevel.LOW,
                maturity = ModelMaturity.COLD_START,
                curveVersion = 2,
                totalSampleSessions = 0,
                lastLearnedTimestamp = 0L
            )
        }
    }
}

/**
 * 三源能量对比看板数据实体。
 * 用于在能量模型弹窗看板中直观对比实测学习可用能量、硬件原生寄存器直读与传统标称电压折算。
 *
 * @property learnedRemainingEnergyWh 基于 5% SOC 历史放电曲线（三层混合估算）得出的剩余可用能量（单位：Wh，若无可靠依据为 null）
 * @property hardwareNativeEnergyWh 硬件原生能量计数器直读的剩余能量（单位：Wh，如无硬件支持为 null）
 * @property nominalCalculatedEnergyWh 传统标称工作电压（如 3.85V）粗算得出的剩余能量（单位：Wh，若容量缺失为 null）
 * @property currentSocPercent 当前设备电量百分比（0.0f ~ 100.0f）
 * @property curveCoveragePercent 20 个 SOC 区间已建立实测档案的覆盖率（0.0f ~ 1.0f）
 * @property totalSessionsCount 累计参与学习的放电采样会话总数
 * @property nominalVoltageVolts 折算采用的标称电压（单位：V，默认 3.85V）
 * @property confidence 学习模型当前整体数据置信度评级 [ConfidenceLevel]
 * @property maturity 学习模型当前成熟度评级 [ModelMaturity]
 * @property estimatedFullUsableEnergyWh 组合推算的整机满可用电能（单位：Wh，未收敛时为 null）
 * @property mixedEstimate 旧版本混合估算兼容实体（可选）
 * @property hybridEstimate 最新三层混合估算结果实体，含实测分量、插值/模型推演分量与不确定度（可选）
 * @property resolvedBins 全部 20 个 SOC 区间在当前工况下的动态解析实体列表
 */
data class EnergyComparisonOverview(
    val learnedRemainingEnergyWh: Double?,
    val hardwareNativeEnergyWh: Double?,
    val nominalCalculatedEnergyWh: Double?,
    val currentSocPercent: Float,
    val curveCoveragePercent: Float,
    val totalSessionsCount: Int,
    val nominalVoltageVolts: Float = 3.85f,
    val confidence: ConfidenceLevel = ConfidenceLevel.LOW,
    val maturity: ModelMaturity = ModelMaturity.COLD_START,
    val estimatedFullUsableEnergyWh: Double? = null,
    val mixedEstimate: LearnedSocEnergyCurve.MixedEnergyEstimate? = null,
    val hybridEstimate: HybridRemainingEnergyEstimate? = null,
    val resolvedBins: List<ResolvedSocEnergyBin> = emptyList()
)
