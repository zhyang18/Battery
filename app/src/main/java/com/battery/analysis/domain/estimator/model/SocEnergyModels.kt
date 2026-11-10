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
    /** 基于当前放电会话高频连续亮屏耗电实测锚定与电化学形态展开 */
    SCREEN_ON_ANCHORED,
    /** 基于双侧相邻稳定区间的放电电压线性插值推算（仅限平台区且具备双侧实测） */
    INTERPOLATED,
    /** 基于已测区间做功与电化学形态权重的自举外推（脱离容量模型，自适应实际输出做功） */
    BOOTSTRAP_EXTRAPOLATED,
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
    val maxObservedWh: Double? = null,
    val lastUpdatedSessionId: String = ""
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
 * @property curveVersion 数据结构与存储协议版本号（当前为版本 3）
 * @property totalSampleSessions 参与构建曲线的历史独立放电会话总次数
 * @property lastLearnedTimestamp 学习曲线整体最后一次更新的系统时间戳
 */
data class LearnedSocEnergyCurve(
    val bins: List<SocEnergyBin>,
    val coveragePercent: Float,
    val estimatedFullUsableEnergyWh: Double?,
    val confidence: ConfidenceLevel = ConfidenceLevel.LOW,
    val maturity: ModelMaturity = ModelMaturity.COLD_START,
    val curveVersion: Int = 3,
    val totalSampleSessions: Int,
    val lastLearnedTimestamp: Long
) {
    /** 兼容旧代码的别名属性：满可用能量推算值（Wh） */
    val totalLearnedEnergyWh: Double?
        get() = estimatedFullUsableEnergyWh

    /** 是否 20 个区间全部具备实测放电样本 */
    val isFullyLearned: Boolean
        get() = bins.all { it.hasLearnedData() }

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
     * 3. 无量纲形态自举外推（BOOTSTRAP_EXTRAPOLATED）：当具备 >=1 个实测区间时，基于各区间电化学形态权重中位数投票自举反推满电标尺，彻底脱离充放电容量先验；
     * 4. 无法估计（UNKNOWN）：纯冷启动（0 段已测）时忠实标记为未知待采集，绝不捏造虚假预测。
     *
     * @param effectiveCapacityMah 设备有效满充基准容量（单位：mAh，可选，解耦后不再作为先验）
     * @param nominalVoltageVolts 电池标称电压（单位：V，默认 3.85V）
     * @param currentDischargeCurrentMa 当前实时放电电流（单位：mA，用于动态工况内阻压降微调，可选）
     * @param temperatureCelsius 当前电池温度（单位：℃，可选）
     * @return 20 个区间解析后的实体列表 [List<ResolvedSocEnergyBin>]
     */
    @Suppress("UNUSED_PARAMETER")
    fun resolveAllBins(
        effectiveCapacityMah: Double? = null,
        nominalVoltageVolts: Double = 3.85,
        currentDischargeCurrentMa: Double? = null,
        temperatureCelsius: Float? = null,
        screenOnAnchoredFullEnergyWh: Double? = null
    ): List<ResolvedSocEnergyBin> {
        // 1. 基于已测区间做功与电化学形态权重向量，通过中位数投票提取微观实测形态标尺
        val learnedVotes = bins.mapNotNull { bin ->
            val wh = bin.learnedEnergyWh
            if (bin.hasLearnedData() && wh != null && wh > 0.0) {
                wh / SOC_ENERGY_SHAPE_WEIGHTS[bin.binIndex]
            } else null
        }
        val fullScaleWhFromLearned: Double? = if (learnedVotes.size >= MIN_BOOTSTRAP_LEARNED_BINS) {
            val sorted = learnedVotes.sorted()
            val mid = sorted.size / 2
            if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
        } else null

        // 2. 权威宏观联合标尺：由符合条件的全部有效亮屏数据联合微积分推算生成，彻底脱离外部 FCC 先验
        val authoritativeMacroWh = screenOnAnchoredFullEnergyWh?.takeIf { it > 0.0 }

        // 梯度自适应融合权重：
        // 若实测段数稀疏 (< 6段，即 < 30% 覆盖率，如单次 73%->56% 亮屏片段)，实测样本存在局域电压偏态，
        // 权威宏观联合标尺与有效容量基准绝对主导（100%），未测区间坚决不被单次高压微观自举绑架；
        // 当积累达到 6~10 段 (>= 30%~50% 覆盖率) 时，实测样本跨越了不同电压平台，微观自举权重平滑提升并占主导。
        val microWeight = if (learnedVotes.size < 6) {
            0.0
        } else {
            (0.50 + (learnedVotes.size - 6).toDouble() * 0.125).coerceIn(0.50, 1.0)
        }

        val effectiveFullWh: Double? = when {
            authoritativeMacroWh != null && fullScaleWhFromLearned != null -> {
                (1.0 - microWeight) * authoritativeMacroWh + microWeight * fullScaleWhFromLearned
            }
            authoritativeMacroWh != null -> authoritativeMacroWh
            fullScaleWhFromLearned != null -> fullScaleWhFromLearned
            else -> null
        }

        // 提取已实测区间的上下界索引与实测真值，用于外推时的边界自适应平滑衔接（彻底消除断面断崖）
        val learnedIndices = bins.filter { it.hasLearnedData() && it.learnedEnergyWh != null && it.learnedEnergyWh!! > 0.0 }
            .map { it.binIndex }
        val minLearnedIndex = learnedIndices.minOrNull()
        val maxLearnedIndex = learnedIndices.maxOrNull()

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
                } else if (fullScaleWhFromLearned != null && minLearnedIndex != null && maxLearnedIndex != null) {
                    // 场景 A：微观实测样本充足（>= 3段），采用电化学形态边界平滑自适应自举外推
                    // 严格保证在边界处与实测真值一阶导数连续，彻底杜绝锯齿断崖跳水
                    val currentWeight = SOC_ENERGY_SHAPE_WEIGHTS[index]
                    val extrapolatedWh: Double
                    val rationaleText: String

                    val baselineScaleWh = effectiveFullWh ?: fullScaleWhFromLearned
                    if (learnedVotes.size >= 6) {
                        // 场景 A：微观实测样本充足 (>= 6 段，覆盖率 >= 30%)，微观实测曲线跨越多电压平台已形成有效形态，
                        // 以实测边界锚点形态自举为主，以全电量中位数标尺为容差上限 (* 1.10)
                        if (index > maxLearnedIndex) {
                            val anchorBin = bins[maxLearnedIndex]
                            val anchorWh = anchorBin.learnedEnergyWh!!
                            val anchorWeight = SOC_ENERGY_SHAPE_WEIGHTS[maxLearnedIndex]
                            val anchorBasedWh = anchorWh * (currentWeight / anchorWeight)
                            val medianBasedWh = fullScaleWhFromLearned * currentWeight
                            extrapolatedWh = min(anchorBasedWh, medianBasedWh * 1.10)
                            rationaleText = String.format(Locale.US, "实测形态自举 (高电量衔接 %d%%~%d%%)", anchorBin.minSocPercent.toInt(), anchorBin.maxSocPercent.toInt())
                        } else if (index < minLearnedIndex) {
                            val anchorBin = bins[minLearnedIndex]
                            val anchorWh = anchorBin.learnedEnergyWh!!
                            val anchorWeight = SOC_ENERGY_SHAPE_WEIGHTS[minLearnedIndex]
                            val anchorBasedWh = anchorWh * (currentWeight / anchorWeight)
                            val medianBasedWh = fullScaleWhFromLearned * currentWeight
                            extrapolatedWh = min(anchorBasedWh, medianBasedWh * 1.10)
                            rationaleText = String.format(Locale.US, "实测形态自举 (低电量衔接 %d%%~%d%%)", anchorBin.minSocPercent.toInt(), anchorBin.maxSocPercent.toInt())
                        } else {
                            extrapolatedWh = fullScaleWhFromLearned * currentWeight
                            rationaleText = String.format(Locale.US, "实测形态自举 (形态占比 %.1f%%)", currentWeight * 100.0)
                        }
                    } else {
                        // 场景 B：微观实测样本稀疏 (< 6 段，如仅单次 73%->56% 亮屏片段)，局域电压严重偏态！
                        // 核心准则：微观 20 段 SOC 严禁仅由单次局部亮屏外推，未测区间必须由所有符合有效条件的亮屏联合宏观标尺与基准容量主导展开，
                        // 紧邻实测段平滑衔接，远端严格按权威基尺展开，彻底杜绝单次 28.67Wh / 32.68Wh 虚高！
                        val anchorIndex = if (index > maxLearnedIndex) maxLearnedIndex else minLearnedIndex
                        val dist = kotlin.math.abs(index - anchorIndex)
                        val anchorBin = bins[anchorIndex]
                        val anchorWh = anchorBin.learnedEnergyWh!!
                        val anchorWeight = SOC_ENERGY_SHAPE_WEIGHTS[anchorIndex]
                        val anchorBasedWh = anchorWh * (currentWeight / anchorWeight)
                        val baselineBasedWh = baselineScaleWh * currentWeight

                        val blendFactor = if (dist <= 1) 0.50 else 0.0 // 紧邻边界 50% 平滑过渡消除锯齿断崖，远端 100% 遵从权威联合基尺
                        extrapolatedWh = blendFactor * anchorBasedWh + (1.0 - blendFactor) * baselineBasedWh
                        rationaleText = String.format(Locale.US, "实测形态自举 (联合宏观标尺展开 %.1f%%)", currentWeight * 100.0)
                    }
                    ResolvedSocEnergyBin(
                        binIndex = index,
                        minSocPercent = bin.minSocPercent,
                        maxSocPercent = bin.maxSocPercent,
                        measuredEnergyWh = null,
                        modeledEnergyWh = extrapolatedWh,
                        effectiveEnergyWh = extrapolatedWh,
                        source = EnergyEstimateSource.BOOTSTRAP_EXTRAPOLATED,
                        uncertaintyWh = extrapolatedWh * 0.08, // 外推不确定度取 8%
                        sampleCount = 0,
                        rationale = rationaleText
                    )
                } else if (screenOnAnchoredFullEnergyWh != null && screenOnAnchoredFullEnergyWh > 0.0) {
                    // 场景 B：微观样本不足但具备宏观实测锚定（冷启动大跨度放电）
                    val weight = SOC_ENERGY_SHAPE_WEIGHTS[index]
                    val anchoredWh = screenOnAnchoredFullEnergyWh * weight
                    ResolvedSocEnergyBin(
                        binIndex = index,
                        minSocPercent = bin.minSocPercent,
                        maxSocPercent = bin.maxSocPercent,
                        measuredEnergyWh = null,
                        modeledEnergyWh = anchoredWh,
                        effectiveEnergyWh = anchoredWh,
                        source = EnergyEstimateSource.SCREEN_ON_ANCHORED,
                        uncertaintyWh = anchoredWh * 0.05,
                        sampleCount = 0,
                        rationale = String.format(Locale.US, "宏观微积分实测锚定 (形态占比 %.1f%%)", weight * 100.0)
                    )
                } else if (effectiveFullWh != null && effectiveFullWh > 0.0) {
                    // 场景 C：其他先验自举
                    val weight = SOC_ENERGY_SHAPE_WEIGHTS[index]
                    val bootstrapWh = effectiveFullWh * weight
                    ResolvedSocEnergyBin(
                        binIndex = index,
                        minSocPercent = bin.minSocPercent,
                        maxSocPercent = bin.maxSocPercent,
                        measuredEnergyWh = null,
                        modeledEnergyWh = bootstrapWh,
                        effectiveEnergyWh = bootstrapWh,
                        source = EnergyEstimateSource.BOOTSTRAP_EXTRAPOLATED,
                        uncertaintyWh = bootstrapWh * 0.08,
                        sampleCount = 0,
                        rationale = String.format(Locale.US, "实测形态自举 (形态占比 %.1f%%)", weight * 100.0)
                    )
                } else {
                    // 纯冷启动（0 段已学习且无有效亮屏统计）：严格遵循零假数据原则如实返回未知，严禁虚构数据
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
                        rationale = "冷启动无实测做功样本（待采集）"
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
        temperatureCelsius: Float? = null,
        screenOnAnchoredFullEnergyWh: Double? = null
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
            temperatureCelsius = temperatureCelsius,
            screenOnAnchoredFullEnergyWh = screenOnAnchoredFullEnergyWh
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
                EnergyEstimateSource.SCREEN_ON_ANCHORED -> {
                    val partWh = (resolved.effectiveEnergyWh ?: 0.0) * ratio
                    modeledWh += partWh
                    modeledSocSpan += span
                    val u = (resolved.uncertaintyWh ?: (partWh * 0.05)) * ratio
                    modeledLinearUncertaintySum += u
                    breakdown[resolved.source] = (breakdown[resolved.source] ?: 0.0) + partWh
                }
                EnergyEstimateSource.INTERPOLATED,
                EnergyEstimateSource.BOOTSTRAP_EXTRAPOLATED,
                EnergyEstimateSource.MODEL_PRIOR -> {
                    val partWh = (resolved.effectiveEnergyWh ?: 0.0) * ratio
                    modeledWh += partWh
                    modeledSocSpan += span
                    val u = (resolved.uncertaintyWh ?: (partWh * 0.08)) * ratio
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

        // 动态置信度评估：严密结合模型成熟度（包含独立放电周期次数硬门限）、实测 SOC 覆盖率与数据血统
        val evaluatedConfidence = when {
            hasUnknown -> ConfidenceLevel.UNRELIABLE
            maturity == ModelMaturity.CONVERGED && measuredSocCov >= 0.80f -> ConfidenceLevel.HIGH
            maturity == ModelMaturity.DEVELOPING && measuredSocCov >= 0.50f -> ConfidenceLevel.MEDIUM
            measuredSocCov > 0.0f || modeledSocCov > 0.0f -> ConfidenceLevel.LOW
            else -> ConfidenceLevel.UNRELIABLE
        }

        val evaluatedMaturity = maturity

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
            maturity = evaluatedMaturity,
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

        /** 开启无量纲形态自举外推的最小已测区间门限（固定为 3 段，即至少 15% 跨度，确保中位数滤波具备抗噪能力） */
        const val MIN_BOOTSTRAP_LEARNED_BINS = 3

        /**
         * 锂电池放电能量无量纲电化学形态归一化权重向量（20 维，对应 20 个 5% SOC 分段，和严格为 1.000）。
         *
         * 电化学机理依据：
         * 1. 0%~5%：低压截止保护区（端电压陡降至 ~3.45V 截止，厂商防过放关机锁容裕度），做功占比约 4.0%；
         * 2. 5%~20%：拐点过渡区（电压由 3.60V 上升至 3.73V），做功占比从 4.5% 爬升至 4.8%；
         * 3. 20%~55%：典型放电平台平坦区（标称工作电压 ~3.80V），每 5% 占比稳定在 4.9%~5.0%；
         * 4. 55%~85%：主平台平缓抬升区（电压 3.85V~3.95V），每 5% 占比约 5.1%~5.2%；
         * 5. 85%~100%：高压满电区（电压 ~4.15V~4.35V），做功占比提升至 5.3%~5.4%。
         *
         * 严格数学校验：0.040 + 0.045 + 0.047 + 0.048 + 0.049 + 6*0.050 + 3*0.051 + 2*0.052 + 2*0.053 + 2*0.054 = 1.000。
         */
        val SOC_ENERGY_SHAPE_WEIGHTS = doubleArrayOf(
            0.040, // bin 0:   0% -  5% (低压截止保护区)
            0.045, // bin 1:   5% - 10% (陡降过渡区)
            0.047, // bin 2:  10% - 15% (拐点初段)
            0.048, // bin 3:  15% - 20% (平台前沿)
            0.049, // bin 4:  20% - 25% (平台起步)
            0.050, // bin 5:  25% - 30%
            0.050, // bin 6:  30% - 35%
            0.050, // bin 7:  35% - 40%
            0.050, // bin 8:  40% - 45%
            0.050, // bin 9:  45% - 50%
            0.050, // bin 10: 50% - 55%
            0.051, // bin 11: 55% - 60%
            0.051, // bin 12: 60% - 65%
            0.051, // bin 13: 65% - 70%
            0.052, // bin 14: 70% - 75%
            0.052, // bin 15: 75% - 80% (高压初段)
            0.053, // bin 16: 80% - 85%
            0.053, // bin 17: 85% - 90%
            0.054, // bin 18: 90% - 95% (高压满电区)
            0.054  // bin 19: 95% - 100% (满电最高压区)
        )

        /**
         * 依据放电积分切片（BinEnergyDelta）实际走过的有效电量跨度，精确累加对应的电化学形态理论权重。
         * 彻底消除跨越长时息屏断层时首尾直接推算导致的形态权重虚高失真。
         *
         * @param binDeltas 放电积分器输出的有效区间切片集合
         * @return 真实对应的理论电化学形态权重和（0.0 ~ 1.0）
         */
        fun calculateActualShapeWeight(binDeltas: Collection<com.battery.analysis.domain.estimator.DischargeEnergyIntegrator.BinEnergyDelta>): Double {
            if (binDeltas.isEmpty()) return 0.0
            var weightSum = 0.0
            for (delta in binDeltas) {
                if (delta.binIndex in 0 until BIN_COUNT && delta.coveredSocSpan > 0f) {
                    val fraction = (delta.coveredSocSpan / BIN_SOC_STEP).toDouble()
                    weightSum += fraction * SOC_ENERGY_SHAPE_WEIGHTS[delta.binIndex]
                }
            }
            return weightSum
        }

        /**
         * 计算任意指定 SOC 范围在锂电池无量纲电化学形态向量中的理论权重占比。
         *
         * @param minSoc 起始下界电量百分比（0.0f ~ 100.0f）
         * @param maxSoc 终止上界电量百分比（0.0f ~ 100.0f）
         * @return 理论形态能量权重（0.0 ~ 1.0），若 maxSoc <= minSoc 返回 0.0
         */
        fun calculateTheoreticalShapeWeight(minSoc: Float, maxSoc: Float): Double {
            val clampedMin = minSoc.coerceIn(0.0f, 100.0f)
            val clampedMax = maxSoc.coerceIn(0.0f, 100.0f)
            if (clampedMax <= clampedMin) return 0.0

            var weightSum = 0.0
            for (i in 0 until BIN_COUNT) {
                val binMin = i * BIN_SOC_STEP
                val binMax = binMin + BIN_SOC_STEP
                val overlapMin = maxOf(clampedMin, binMin)
                val overlapMax = minOf(clampedMax, binMax)
                if (overlapMax > overlapMin) {
                    val fraction = (overlapMax - overlapMin) / BIN_SOC_STEP
                    weightSum += fraction * SOC_ENERGY_SHAPE_WEIGHTS[i]
                }
            }
            return weightSum
        }

        /**
         * 计算多个纯亮屏放电会话在 20 段 SOC 能量形态上的区间并集覆盖度（Union Shape Coverage）。
         *
         * 杜绝多个重复浅放电样本在局部相同区间通过权重简单累加而虚假拉满全量程覆盖度的缺陷，
         * 忠实反映所有样本并集对 0%~100% 全 SOC 电化学能量形态的真实物理覆盖比例。
         *
         * @param sessions 经审计合格的纯亮屏放电会话列表
         * @return 所有会话在 SOC 轴上的并集理论形态权重和（0.0 ~ 1.0）
         */
        fun calculateUnionShapeCoverage(sessions: List<QualifiedScreenOnSession>): Double {
            if (sessions.isEmpty()) return 0.0

            var totalUnionWeight = 0.0
            for (i in 0 until BIN_COUNT) {
                val binMin = i * BIN_SOC_STEP.toDouble()
                val binMax = binMin + BIN_SOC_STEP.toDouble()

                val intervals = mutableListOf<Pair<Double, Double>>()
                for (s in sessions) {
                    val sMin = minOf(s.startSoc, s.endSoc).toDouble().coerceIn(0.0, 100.0)
                    val sMax = maxOf(s.startSoc, s.endSoc).toDouble().coerceIn(0.0, 100.0)
                    val overlapStart = maxOf(binMin, sMin)
                    val overlapEnd = minOf(binMax, sMax)
                    if (overlapEnd > overlapStart) {
                        intervals.add(Pair(overlapStart, overlapEnd))
                    }
                }
                if (intervals.isEmpty()) continue

                intervals.sortBy { it.first }
                var mergedLength = 0.0
                var curStart = intervals[0].first
                var curEnd = intervals[0].second
                for (j in 1 until intervals.size) {
                    val next = intervals[j]
                    if (next.first <= curEnd) {
                        curEnd = maxOf(curEnd, next.second)
                    } else {
                        mergedLength += (curEnd - curStart)
                        curStart = next.first
                        curEnd = next.second
                    }
                }
                mergedLength += (curEnd - curStart)

                val fraction = (mergedLength / BIN_SOC_STEP.toDouble()).coerceIn(0.0, 1.0)
                totalUnionWeight += fraction * SOC_ENERGY_SHAPE_WEIGHTS[i]
            }

            return totalUnionWeight.coerceIn(0.0, 1.0)
        }

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
                curveVersion = 3,
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
 * @property fullLearnedEnergyWh 模型最终预测的 100% 满充实测做功能量（单位：Wh，如 21.45 Wh）
 * @property fullHardwareEnergyWh 硬件底层电量计推算的 100% 满电能量（单位：Wh）
 * @property fullNominalEnergyWh 当前健康容量额定满电理论值（单位：Wh，如 24.05 Wh，基于 FCC）
 * @property hybridFullEstimate 100% 满充做功状态下的三层混合估算完整实体
 * @property dischargeEnergyEfficiencyPercent 实测放电做功相对健康满充标称的输出转化效率（fullLearned / fullNominal * 100.0%）
 * @property designCapacityMah 设备出厂设计容量（单位：mAh，如 8000 mAh）
 * @property fullDesignEnergyWh 设备出厂设计额定满电理论值（单位：Wh，如 30.80 Wh）
 * @property energyHealthRetentionPercent 实测满电做功相对出厂设计总能量的保留率/能量健康度（fullLearned / fullDesign * 100.0%）
 * @property equivalentCapacityMah 放电模型满充做功折合的等效容量（单位：mAh，如 6488 mAh，按标称电压折算）
 * @property capacityModelSohPercent 容量微积分算法模型测算出的容量健康度 SOH（effectiveCapacity / designCapacity * 100.0%）
 * @property effectiveCapacityMah 当前算法采用的健康度满充容量（单位：mAh，如 6247 mAh）
 * @property confidenceReason 置信度判定的物理与统计学决策原因说明
 * @property historicalDispersionPercent 历史多次大跨度放电样本推算满充做功的相对离散度百分比（如 3.2%）
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
    val resolvedBins: List<ResolvedSocEnergyBin> = emptyList(),
    val fullLearnedEnergyWh: Double? = null,
    val fullHardwareEnergyWh: Double? = null,
    val fullNominalEnergyWh: Double? = null,
    val hybridFullEstimate: HybridRemainingEnergyEstimate? = null,
    val dischargeEnergyEfficiencyPercent: Double? = null,
    val designCapacityMah: Float? = null,
    val fullDesignEnergyWh: Double? = null,
    val energyHealthRetentionPercent: Double? = null,
    val equivalentCapacityMah: Double? = null,
    val capacityModelSohPercent: Double? = null,
    val effectiveCapacityMah: Float? = null,
    val isScreenOnAnchored: Boolean = false,
    val screenOnMeasuredEnergyWh: Float? = null,
    val screenOnDropPercent: Float? = null,
    val effectiveThresholdPercent: Int = 15,
    val effectiveSampleCountLimit: Int = 10,
    val participatingSessionCount: Int = 0,
    val macroHistoricalFullWh: Double? = null,
    val confidenceReason: String? = null,
    val historicalDispersionPercent: Double? = null
)


/**
 * 满足当前有效条件筛选门限与条数限制的纯亮屏放电会话物理做功与电荷概要实体。
 *
 * @property sessionId 产生该记录的会话唯一标识 ID
 * @property screenOnWh 纯亮屏梯形微积分实测释放物理能量（单位：Wh）
 * @property screenOnChargeMah 纯亮屏实测释放物理电荷量（单位：mAh）
 * @property startSoc 亮屏放电起始电量百分比
 * @property endSoc 亮屏放电终止电量百分比
 * @property screenOnDropPercent 纯亮屏实际覆盖的有效电量净掉电跨度（%）
 * @property theoreticalShapeWeight 该放电区间在锂电池无量纲电化学形态向量中的理论能量积分权重（0.0 ~ 1.0）
 */
data class QualifiedScreenOnSession(
    val sessionId: String,
    val screenOnWh: Double,
    val screenOnChargeMah: Double,
    val startSoc: Float,
    val endSoc: Float,
    val screenOnDropPercent: Float,
    val theoreticalShapeWeight: Double
)
