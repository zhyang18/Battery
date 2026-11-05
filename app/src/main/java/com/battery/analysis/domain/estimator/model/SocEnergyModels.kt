package com.battery.analysis.domain.estimator.model

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
 * @property learnedRemainingEnergyWh 基于 5% SOC 历史放电曲线累加得出的实测剩余能量（单位：Wh，若无样本为 null）
 * @property hardwareNativeEnergyWh 硬件原生能量计数器直读的剩余能量（单位：Wh，如无硬件支持为 null）
 * @property nominalCalculatedEnergyWh 传统标称工作电压（如 3.85V）粗算得出的剩余能量（单位：Wh，若容量缺失为 null）
 * @property currentSocPercent 当前设备电量百分比（0.0f ~ 100.0f）
 * @property curveCoveragePercent 20 个 SOC 区间已建立学习档案的覆盖率（0.0f ~ 1.0f）
 * @property totalSessionsCount 累计参与学习的放电采样会话总数
 * @property nominalVoltageVolts 折算采用的标称电压（单位：V，默认 3.85V）
 * @property confidence 学习模型当前整体数据置信度评级 [ConfidenceLevel]
 * @property maturity 学习模型当前成熟度评级 [ModelMaturity]
 * @property estimatedFullUsableEnergyWh 组合推算的整机满可用电能（单位：Wh，未收敛时为 null）
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
    val estimatedFullUsableEnergyWh: Double? = null
)
