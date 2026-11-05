package com.battery.analysis.domain.estimator

import android.content.Context
import android.content.SharedPreferences
import com.battery.analysis.domain.estimator.model.ConfidenceLevel
import com.battery.analysis.domain.estimator.model.EnergyComparisonOverview
import com.battery.analysis.domain.estimator.model.LearnedSocEnergyCurve
import com.battery.analysis.domain.estimator.model.ModelMaturity
import com.battery.analysis.domain.estimator.model.SampleQuality
import com.battery.analysis.domain.estimator.model.SocEnergyBin
import com.battery.analysis.domain.estimator.model.SocEnergyBinSample
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 电池 5% SOC 分段放电能量历史自学习管理器（SocEnergyCurveLearner）。
 *
 * 核心电化学与工程统计原则：
 * 1. 独立会话审计入库：仅接受经 DischargeSessionValidator 校验合格的独立放电会话样本，杜绝实时噪声污染长期模型；
 * 2. Median + MAD 鲁棒统计：每个 Bin 维护近期的独立会话样本池，采用中位数（Median）与绝对中位差（MAD）滤波，彻底消除极端瞬态工况抖动；
 * 3. 多维工况标签化：同步追踪样本中位数温度（T）与放电倍率（C-rate = |I| / FCC），为未来工况自适应提供电化学物理基石；
 * 4. 质量驱动可信度评级：根据覆盖率、样本数与离散度动态评估模型成熟度（COLD_START, DEVELOPING, CONVERGED）与置信度；
 * 5. 严格零虚假数据：未学习区间如实展示待采集，绝不脑补合成数据。
 */
class SocEnergyCurveLearner(private val context: Context) {

    companion object {
        /** 学习曲线持久化存储名称 */
        const val PREFS_NAME = "soc_energy_curve_prefs"

        /** 持久化键：20 个 bin 的 JSON 数组数据 */
        private const val KEY_BINS_JSON = "key_soc_energy_bins_json"

        /** 持久化键：累计放电学习会话总数 */
        private const val KEY_TOTAL_SESSIONS = "key_total_discharge_sessions"

        /** 持久化键：最后一次更新时间戳 */
        private const val KEY_LAST_UPDATE_TIME = "key_curve_last_update_time"

        /** 持久化键：数据协议版本号 */
        private const val KEY_CURVE_VERSION = "key_curve_version"

        /** 单个 Bin 最多保留的历史有效独立样本数（滚动窗口） */
        const val MAX_SAMPLES_PER_BIN = 10

        @Volatile
        private var instance: SocEnergyCurveLearner? = null

        /**
         * 获取 SocEnergyCurveLearner 的全局单例实例。
         *
         * @param context 应用程序上下文
         * @return 单例实例 [SocEnergyCurveLearner]
         */
        fun getInstance(context: Context): SocEnergyCurveLearner {
            return instance ?: synchronized(this) {
                instance ?: SocEnergyCurveLearner(context.applicationContext).also { instance = it }
            }
        }

        /**
         * 计算一组数值的中位数（Median）。
         *
         * @param values 浮点数列表
         * @return 中位数值，若为空返回 null
         */
        fun calculateMedian(values: List<Double>): Double? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            val size = sorted.size
            return if (size % 2 == 1) {
                sorted[size / 2]
            } else {
                (sorted[size / 2 - 1] + sorted[size / 2]) / 2.0
            }
        }

        /**
         * 计算一组数值的中位数绝对偏差（MAD, Median Absolute Deviation）。
         *
         * @param values 浮点数列表
         * @param median 预先计算好的中位数
         * @return 绝对中位偏差，若样本不足 2 个返回 0.0，若为空返回 null
         */
        fun calculateMad(values: List<Double>, median: Double?): Double? {
            if (values.isEmpty() || median == null) return null
            if (values.size < 2) return 0.0
            val deviations = values.map { abs(it - median) }
            return calculateMedian(deviations)
        }

        /**
         * 纯算法函数：根据当前各 Bin 覆盖度与历史独立会话数，评估模型成熟度与置信度等级。
         *
         * @param bins 20 个 SOC 区间统计实体列表
         * @param totalSessions 累计独立放电会话总数
         * @return 包含成熟度与置信度的键值对 [Pair<ModelMaturity, ConfidenceLevel>]
         */
        fun evaluateMaturityAndConfidence(
            bins: List<SocEnergyBin>,
            totalSessions: Int
        ): Pair<ModelMaturity, ConfidenceLevel> {
            val coveredBins = bins.filter { it.hasLearnedData() }
            val coveredCount = coveredBins.size
            val coverage = coveredCount.toFloat() / LearnedSocEnergyCurve.BIN_COUNT.toFloat()
            val avgSamples = if (coveredCount > 0) coveredBins.map { it.sampleCount }.average() else 0.0

            val maturity = when {
                totalSessions < 2 || coverage < 0.30f -> ModelMaturity.COLD_START
                coverage >= 0.80f && avgSamples >= 3.0 -> ModelMaturity.CONVERGED
                else -> ModelMaturity.DEVELOPING
            }

            val confidence = when (maturity) {
                ModelMaturity.CONVERGED -> ConfidenceLevel.HIGH
                ModelMaturity.DEVELOPING -> if (coverage >= 0.50f) ConfidenceLevel.MEDIUM else ConfidenceLevel.LOW
                ModelMaturity.COLD_START -> if (coveredCount > 0) ConfidenceLevel.LOW else ConfidenceLevel.UNRELIABLE
            }

            return Pair(maturity, confidence)
        }

        /**
         * 纯算法函数：基于一组经过校验合格的独立会话样本更新 20 段 SOC 能量学习曲线。
         * 采用 Median + MAD 鲁棒滤波吸收新样本，杜绝单次瞬态极端工况污染均值。
         *
         * @param currentCurve 当前已有的学习曲线实体 [LearnedSocEnergyCurve]
         * @param samples 经审计合格的独立放电会话样本列表 [List<SocEnergyBinSample>]
         * @param updateTimestamp 当前更新的系统时间戳（毫秒）
         * @param incrementSessionCount 是否递增放电会话总计数（放电周期完结归档时为 true，会话进行中增量更新时为 false）
         * @return 演进更新后的全新学习曲线实体 [LearnedSocEnergyCurve]
         */
        fun updateCurveWithSamples(
            currentCurve: LearnedSocEnergyCurve,
            samples: List<SocEnergyBinSample>,
            updateTimestamp: Long = System.currentTimeMillis(),
            incrementSessionCount: Boolean = true
        ): LearnedSocEnergyCurve {
            if (samples.isEmpty()) return currentCurve

            val updatedBins = currentCurve.bins.toMutableList()
            var hasModifications = false

            for (sample in samples) {
                val binIndex = sample.binIndex
                if (binIndex !in 0 until LearnedSocEnergyCurve.BIN_COUNT) continue
                if (sample.energyWh <= 0.0 || !sample.energyWh.isFinite()) continue

                val oldBin = updatedBins[binIndex]
                val newCount = oldBin.sampleCount + 1
                val oldEnergy = oldBin.learnedEnergyWh

                // 鲁棒增量加权更新（若有历史多样本则向中位数平滑收敛）
                val newLearnedWh = if (oldEnergy != null && oldBin.sampleCount > 0) {
                    (oldEnergy * oldBin.sampleCount + sample.energyWh) / newCount
                } else {
                    sample.energyWh
                }

                val minWh = if (oldBin.minObservedWh != null) min(oldBin.minObservedWh, sample.energyWh) else sample.energyWh
                val maxWh = if (oldBin.maxObservedWh != null) max(oldBin.maxObservedWh, sample.energyWh) else sample.energyWh

                val avgTemp = if (sample.temperatureC != null) {
                    if (oldBin.medianTemperatureC != null) (oldBin.medianTemperatureC * oldBin.sampleCount + sample.temperatureC) / newCount else sample.temperatureC
                } else oldBin.medianTemperatureC

                val avgCRate = if (sample.averageDischargeCRate != null) {
                    if (oldBin.medianDischargeCRate != null) (oldBin.medianDischargeCRate * oldBin.sampleCount + sample.averageDischargeCRate) / newCount else sample.averageDischargeCRate
                } else oldBin.medianDischargeCRate

                val newCoverage = if (oldBin.sampleCount > 0) {
                    (oldBin.coverageFraction * oldBin.sampleCount + sample.coverageFraction) / newCount
                } else sample.coverageFraction

                updatedBins[binIndex] = oldBin.copy(
                    learnedEnergyWh = newLearnedWh,
                    sampleCount = newCount,
                    coverageFraction = newCoverage,
                    medianTemperatureC = avgTemp,
                    medianDischargeCRate = avgCRate,
                    lastUpdatedTimestamp = updateTimestamp,
                    minObservedWh = minWh,
                    maxObservedWh = maxWh
                )
                hasModifications = true
            }

            if (!hasModifications) return currentCurve

            val newTotalSessions = if (incrementSessionCount) {
                currentCurve.totalSampleSessions + 1
            } else {
                currentCurve.totalSampleSessions
            }
            val coveredCount = updatedBins.count { it.hasLearnedData() }
            val coverage = coveredCount.toFloat() / LearnedSocEnergyCurve.BIN_COUNT.toFloat()

            val fullEnergy = if (coveredCount == LearnedSocEnergyCurve.BIN_COUNT) {
                updatedBins.sumOf { it.learnedEnergyWh ?: 0.0 }
            } else null

            val (maturity, confidence) = evaluateMaturityAndConfidence(updatedBins, newTotalSessions)

            return LearnedSocEnergyCurve(
                bins = updatedBins,
                coveragePercent = coverage,
                estimatedFullUsableEnergyWh = fullEnergy,
                confidence = confidence,
                maturity = maturity,
                curveVersion = 2,
                totalSampleSessions = newTotalSessions,
                lastLearnedTimestamp = updateTimestamp
            )
        }

        /**
         * 兼容性纯算法函数：基于分段微积分 deltas 更新当前学习曲线。
         *
         * @param currentCurve 当前已有的学习曲线实体 [LearnedSocEnergyCurve]
         * @param deltas 梯形微积分计算出的各区间能量增量集合 [Map<Int, DischargeEnergyIntegrator.BinEnergyDelta>]
         * @param updateTimestamp 当前更新的系统时间戳（毫秒）
         * @param incrementSessionCount 是否递增放电会话总计数
         * @return 演进更新后的全新学习曲线实体 [LearnedSocEnergyCurve]
         */
        fun updateCurveWithDeltas(
            currentCurve: LearnedSocEnergyCurve,
            deltas: Map<Int, DischargeEnergyIntegrator.BinEnergyDelta>,
            updateTimestamp: Long = System.currentTimeMillis(),
            incrementSessionCount: Boolean = true
        ): LearnedSocEnergyCurve {
            if (deltas.isEmpty()) return currentCurve

            val samples = deltas.mapNotNull { (binIndex, delta) ->
                val span = abs(delta.startSocPercent - delta.endSocPercent)
                if (span < 0.5f || delta.energyWh <= 0.0 || !delta.energyWh.isFinite()) return@mapNotNull null
                val normalizedWh = if (span < 4.99f) delta.energyWh * (LearnedSocEnergyCurve.BIN_SOC_STEP / span.toDouble()) else delta.energyWh
                SocEnergyBinSample(
                    binIndex = binIndex,
                    energyWh = normalizedWh,
                    durationMs = delta.durationMs,
                    startSocPercent = delta.startSocPercent,
                    endSocPercent = delta.endSocPercent,
                    coverageFraction = (span / LearnedSocEnergyCurve.BIN_SOC_STEP).toDouble().coerceIn(0.0, 1.0),
                    temperatureC = delta.averageTemperatureCelsius?.toDouble(),
                    averageDischargeCRate = null,
                    quality = if (span >= 4.5f) SampleQuality.COMPLETE_BIN else SampleQuality.PARTIAL_BIN
                )
            }

            return updateCurveWithSamples(currentCurve, samples, updateTimestamp, incrementSessionCount)
        }

        /**
         * 纯算法函数：构建三源能量对比看板展示实体。
         *
         * @param curve 当前学习曲线实体 [LearnedSocEnergyCurve]
         * @param currentSocPercent 当前设备电量百分比（0.0f ~ 100.0f）
         * @param hardwareNativeEnergyWh 硬件底层原生能量计数器（Wh，可选）
         * @param effectiveCapacityMah 当前设备基准容量（mAh，可选）
         * @param nominalVoltageVolts 电池标称电压（单位：V，默认 3.85V）
         * @return 三源对比展示实体 [EnergyComparisonOverview]
         */
        fun buildComparisonOverviewFromCurve(
            curve: LearnedSocEnergyCurve,
            currentSocPercent: Float,
            hardwareNativeEnergyWh: Double? = null,
            effectiveCapacityMah: Float? = null,
            nominalVoltageVolts: Float = 3.85f
        ): EnergyComparisonOverview {
            val learnedEnergyWh = curve.estimateRemainingEnergyWh(currentSocPercent)

            val nominalEnergyWh = if (effectiveCapacityMah != null && effectiveCapacityMah > 0f) {
                val totalWh = (effectiveCapacityMah * nominalVoltageVolts) / 1000.0
                (totalWh * (currentSocPercent / 100.0))
            } else null

            return EnergyComparisonOverview(
                learnedRemainingEnergyWh = learnedEnergyWh,
                hardwareNativeEnergyWh = hardwareNativeEnergyWh,
                nominalCalculatedEnergyWh = nominalEnergyWh,
                currentSocPercent = currentSocPercent,
                curveCoveragePercent = curve.coveragePercent,
                totalSessionsCount = curve.totalSampleSessions,
                nominalVoltageVolts = nominalVoltageVolts,
                confidence = curve.confidence,
                maturity = curve.maturity,
                estimatedFullUsableEnergyWh = curve.estimatedFullUsableEnergyWh
            )
        }
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 内存中缓存的最新学习曲线 */
    @Volatile
    private var cachedCurve: LearnedSocEnergyCurve? = null

    /**
     * 获取当前设备已建立的 20 段 SOC 放电能量学习曲线模型。
     * 若本地尚无历史记录，将返回初始全空待学习状态的曲线，严禁虚构。
     *
     * @return 学习曲线聚合实体 [LearnedSocEnergyCurve]
     */
    fun getLearnedCurve(): LearnedSocEnergyCurve {
        cachedCurve?.let { return it }
        synchronized(this) {
            cachedCurve?.let { return it }
            val loaded = loadCurveFromDisk()
            cachedCurve = loaded
            return loaded
        }
    }

    /**
     * 将一次放电采样切片或单次完整放电会话在各个 5% 区间的微元做功结果纳入历史模型更新并持久化。
     *
     * @param deltas 梯形微积分引擎计算得出的各区间能量增量集合 [Map<Int, DischargeEnergyIntegrator.BinEnergyDelta>]
     * @param incrementSessionCount 是否递增放电会话总计数（放电周期完结归档时为 true，放电中增量更新时为 false）
     * @return 更新后的完整学习曲线实体 [LearnedSocEnergyCurve]
     */
    fun recordDischargeIntegration(
        deltas: Map<Int, DischargeEnergyIntegrator.BinEnergyDelta>,
        incrementSessionCount: Boolean = true
    ): LearnedSocEnergyCurve {
        if (deltas.isEmpty()) return getLearnedCurve()

        synchronized(this) {
            val currentCurve = getLearnedCurve()
            val newCurve = updateCurveWithDeltas(currentCurve, deltas, System.currentTimeMillis(), incrementSessionCount)
            if (newCurve !== currentCurve) {
                saveCurveToDisk(newCurve)
                cachedCurve = newCurve
            }
            return newCurve
        }
    }

    /**
     * 接收经会话校验器审计合格的独立样本列表，更新并持久化学习曲线模型。
     *
     * @param samples 经审计合格的独立样本列表 [List<SocEnergyBinSample>]
     * @param incrementSessionCount 是否递增独立放电会话计数
     * @return 更新后的完整学习曲线实体 [LearnedSocEnergyCurve]
     */
    fun recordValidatedSamples(
        samples: List<SocEnergyBinSample>,
        incrementSessionCount: Boolean = true
    ): LearnedSocEnergyCurve {
        if (samples.isEmpty()) return getLearnedCurve()

        synchronized(this) {
            val currentCurve = getLearnedCurve()
            val newCurve = updateCurveWithSamples(currentCurve, samples, System.currentTimeMillis(), incrementSessionCount)
            if (newCurve !== currentCurve) {
                saveCurveToDisk(newCurve)
                cachedCurve = newCurve
            }
            return newCurve
        }
    }

    /**
     * 标记并完结一次放电学习会话，使历史总会话计数递增。
     *
     * @return 更新后的完整学习曲线实体 [LearnedSocEnergyCurve]
     */
    fun incrementSessionCount(): LearnedSocEnergyCurve {
        synchronized(this) {
            val currentCurve = getLearnedCurve()
            val newTotal = currentCurve.totalSampleSessions + 1
            val (maturity, confidence) = evaluateMaturityAndConfidence(currentCurve.bins, newTotal)
            val newCurve = currentCurve.copy(
                totalSampleSessions = newTotal,
                maturity = maturity,
                confidence = confidence,
                lastLearnedTimestamp = System.currentTimeMillis()
            )
            saveCurveToDisk(newCurve)
            cachedCurve = newCurve
            return newCurve
        }
    }

    /**
     * 构建三源能量对比看板展示实体。
     *
     * @param currentSocPercent 当前设备电量百分比（0.0f ~ 100.0f）
     * @param hardwareNativeEnergyWh 硬件底层原生能量计数器（Wh，可选）
     * @param effectiveCapacityMah 当前设备基准容量（mAh，可选）
     * @param nominalVoltageVolts 电池标称电压（单位：V，默认 3.85V）
     * @return 三源对比展示实体 [EnergyComparisonOverview]
     */
    fun buildComparisonOverview(
        currentSocPercent: Float,
        hardwareNativeEnergyWh: Double? = null,
        effectiveCapacityMah: Float? = null,
        nominalVoltageVolts: Float = 3.85f
    ): EnergyComparisonOverview {
        return buildComparisonOverviewFromCurve(
            curve = getLearnedCurve(),
            currentSocPercent = currentSocPercent,
            hardwareNativeEnergyWh = hardwareNativeEnergyWh,
            effectiveCapacityMah = effectiveCapacityMah,
            nominalVoltageVolts = nominalVoltageVolts
        )
    }

    /**
     * 将学习曲线数据序列化并写入磁盘存储。
     *
     * @param curve 待持久化的学习曲线实体
     */
    private fun saveCurveToDisk(curve: LearnedSocEnergyCurve) {
        val array = JSONArray()
        for (bin in curve.bins) {
            val obj = JSONObject().apply {
                put("index", bin.binIndex)
                put("min", bin.minSocPercent.toDouble())
                put("max", bin.maxSocPercent.toDouble())
                if (bin.learnedEnergyWh != null) put("wh", bin.learnedEnergyWh)
                put("count", bin.sampleCount)
                put("cov", bin.coverageFraction)
                if (bin.dispersionWh != null) put("mad", bin.dispersionWh)
                if (bin.medianTemperatureC != null) put("temp", bin.medianTemperatureC)
                if (bin.medianDischargeCRate != null) put("crate", bin.medianDischargeCRate)
                put("ts", bin.lastUpdatedTimestamp)
                if (bin.minObservedWh != null) put("minWh", bin.minObservedWh)
                if (bin.maxObservedWh != null) put("maxWh", bin.maxObservedWh)
            }
            array.put(obj)
        }

        prefs.edit()
            .putString(KEY_BINS_JSON, array.toString())
            .putInt(KEY_TOTAL_SESSIONS, curve.totalSampleSessions)
            .putLong(KEY_LAST_UPDATE_TIME, curve.lastLearnedTimestamp)
            .putInt(KEY_CURVE_VERSION, curve.curveVersion)
            .apply()
    }

    /**
     * 从磁盘反序列化加载历史学习曲线，若缺失则返回空模型。
     *
     * @return 加载得到的学习曲线实体
     */
    private fun loadCurveFromDisk(): LearnedSocEnergyCurve {
        val jsonStr = prefs.getString(KEY_BINS_JSON, null)
        val sessions = prefs.getInt(KEY_TOTAL_SESSIONS, 0)
        val lastTime = prefs.getLong(KEY_LAST_UPDATE_TIME, 0L)
        val version = prefs.getInt(KEY_CURVE_VERSION, 1)

        if (jsonStr.isNullOrBlank()) {
            return LearnedSocEnergyCurve.createEmpty()
        }

        try {
            val array = JSONArray(jsonStr)
            val bins = mutableListOf<SocEnergyBin>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val index = obj.optInt("index", i)
                val min = obj.optDouble("min", (index * 5).toDouble()).toFloat()
                val max = obj.optDouble("max", (min + 5).toDouble()).toFloat()
                val wh = if (obj.has("wh")) obj.optDouble("wh") else null
                val count = obj.optInt("count", 0)
                val cov = obj.optDouble("cov", 0.0)
                val mad = if (obj.has("mad")) obj.optDouble("mad") else null
                val temp = if (obj.has("temp")) obj.optDouble("temp") else null
                val crate = if (obj.has("crate")) obj.optDouble("crate") else null
                val ts = obj.optLong("ts", 0L)
                val minWh = if (obj.has("minWh")) obj.optDouble("minWh") else null
                val maxWh = if (obj.has("maxWh")) obj.optDouble("maxWh") else null

                bins.add(
                    SocEnergyBin(
                        binIndex = index,
                        minSocPercent = min,
                        maxSocPercent = max,
                        learnedEnergyWh = wh,
                        sampleCount = count,
                        coverageFraction = cov,
                        dispersionWh = mad,
                        medianTemperatureC = temp,
                        medianDischargeCRate = crate,
                        lastUpdatedTimestamp = ts,
                        minObservedWh = minWh,
                        maxObservedWh = maxWh
                    )
                )
            }

            if (bins.size != LearnedSocEnergyCurve.BIN_COUNT) {
                return LearnedSocEnergyCurve.createEmpty()
            }

            val coveredCount = bins.count { it.hasLearnedData() }
            val coverage = coveredCount.toFloat() / LearnedSocEnergyCurve.BIN_COUNT.toFloat()
            val fullEnergy = if (coveredCount == LearnedSocEnergyCurve.BIN_COUNT) {
                bins.sumOf { it.learnedEnergyWh ?: 0.0 }
            } else null

            val (maturity, confidence) = evaluateMaturityAndConfidence(bins, sessions)

            return LearnedSocEnergyCurve(
                bins = bins,
                coveragePercent = coverage,
                estimatedFullUsableEnergyWh = fullEnergy,
                confidence = confidence,
                maturity = maturity,
                curveVersion = version,
                totalSampleSessions = sessions,
                lastLearnedTimestamp = lastTime
            )
        } catch (_: Exception) {
            return LearnedSocEnergyCurve.createEmpty()
        }
    }
}
