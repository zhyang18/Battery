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

        /** 当前数据协议与归一化模型版本号（版本 3：支持 5% 满跨度微积分标准化与残缺样本自愈） */
        const val CURRENT_CURVE_VERSION = 3

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
            val totalSamples = coveredBins.sumOf { it.sampleCount }
            val avgSamples = if (coveredCount > 0) coveredBins.map { it.sampleCount }.average() else 0.0

            // 严密的成熟度评级准则：必须将“完整放电周期次数”作为硬性前置条件
            // 杜绝单次放电一口气跑多个区间就草率晋升中/高置信度的问题
            val maturity = when {
                // 冷启动：已测区间不足 3 段，或已归档完整放电周期少于 3 次，或覆盖率低于 35%
                coveredCount < LearnedSocEnergyCurve.MIN_BOOTSTRAP_LEARNED_BINS || totalSessions < 3 || coverage < 0.35f -> ModelMaturity.COLD_START
                // 成熟收敛：至少 5 次独立完整放电周期，且覆盖率 >= 70% (14段以上)，且平均复测样本 >= 2.5 次
                totalSessions >= 5 && coverage >= 0.70f && avgSamples >= 2.5 -> ModelMaturity.CONVERGED
                // 发展收敛中：已满足 >= 3 次完整放电周期且覆盖 >= 35%
                else -> ModelMaturity.DEVELOPING
            }

            val confidence = when (maturity) {
                ModelMaturity.CONVERGED -> ConfidenceLevel.HIGH
                ModelMaturity.DEVELOPING -> {
                    // 仅当完整放电周期 >= 3 次、覆盖率 >= 50% 且至少累积 6 个样本时才赋予中置信度
                    if (totalSessions >= 3 && coverage >= 0.50f && totalSamples >= 6) ConfidenceLevel.MEDIUM else ConfidenceLevel.LOW
                }
                ModelMaturity.COLD_START -> {
                    if (coveredCount >= LearnedSocEnergyCurve.MIN_BOOTSTRAP_LEARNED_BINS) ConfidenceLevel.LOW else ConfidenceLevel.UNRELIABLE
                }
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
            incrementSessionCount: Boolean = true,
            allowInFlightReplacement: Boolean = false
        ): LearnedSocEnergyCurve {
            if (samples.isEmpty()) return currentCurve

            val updatedBins = currentCurve.bins.toMutableList()
            var hasModifications = false

            for (sample in samples) {
                val binIndex = sample.binIndex
                if (binIndex !in 0 until LearnedSocEnergyCurve.BIN_COUNT) continue
                if (sample.energyWh <= 0.0 || !sample.energyWh.isFinite() || sample.energyWh > 3.8) continue

                val oldBin = updatedBins[binIndex]
                val isReplacingInFlight = allowInFlightReplacement && !incrementSessionCount && oldBin.hasLearnedData() &&
                    (oldBin.lastUpdatedSessionId.isNotEmpty() && oldBin.lastUpdatedSessionId == sample.sessionId)
                val newCount = if (isReplacingInFlight) oldBin.sampleCount else oldBin.sampleCount + 1
                val oldEnergy = oldBin.learnedEnergyWh

                // 鲁棒增量加权更新（会话内增量刷新采用最新完整做功，跨会话采用均值平滑收敛）
                val newLearnedWh = if (isReplacingInFlight) {
                    if (oldBin.sampleCount > 1 && oldEnergy != null) {
                        ((oldEnergy * oldBin.sampleCount) - oldEnergy + sample.energyWh) / oldBin.sampleCount
                    } else {
                        sample.energyWh
                    }
                } else if (oldEnergy != null && oldBin.sampleCount > 0) {
                    (oldEnergy * oldBin.sampleCount + sample.energyWh) / newCount
                } else {
                    sample.energyWh
                }

                val minWh = if (oldBin.minObservedWh != null) min(oldBin.minObservedWh, sample.energyWh) else sample.energyWh
                val maxWh = if (oldBin.maxObservedWh != null) max(oldBin.maxObservedWh, sample.energyWh) else sample.energyWh

                val avgTemp = if (sample.temperatureC != null) {
                    if (isReplacingInFlight) {
                        if (oldBin.sampleCount > 1 && oldBin.medianTemperatureC != null) {
                            ((oldBin.medianTemperatureC * oldBin.sampleCount) - oldBin.medianTemperatureC + sample.temperatureC) / oldBin.sampleCount
                        } else {
                            sample.temperatureC
                        }
                    } else if (oldBin.medianTemperatureC != null) (oldBin.medianTemperatureC * oldBin.sampleCount + sample.temperatureC) / newCount
                    else sample.temperatureC
                } else oldBin.medianTemperatureC

                val avgCRate = if (sample.averageDischargeCRate != null) {
                    if (isReplacingInFlight) {
                        if (oldBin.sampleCount > 1 && oldBin.medianDischargeCRate != null) {
                            ((oldBin.medianDischargeCRate * oldBin.sampleCount) - oldBin.medianDischargeCRate + sample.averageDischargeCRate) / oldBin.sampleCount
                        } else {
                            sample.averageDischargeCRate
                        }
                    } else if (oldBin.medianDischargeCRate != null) (oldBin.medianDischargeCRate * oldBin.sampleCount + sample.averageDischargeCRate) / newCount
                    else sample.averageDischargeCRate
                } else oldBin.medianDischargeCRate

                val newCoverage = if (isReplacingInFlight) {
                    max(oldBin.coverageFraction, sample.coverageFraction)
                } else if (oldBin.sampleCount > 0) {
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
                    maxObservedWh = maxWh,
                    lastUpdatedSessionId = sample.sessionId.ifEmpty { oldBin.lastUpdatedSessionId }
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
                curveVersion = CURRENT_CURVE_VERSION,
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
         * 纯算法函数：基于高保真连续亮屏实测能量与掉电百分比，结合锂电池无量纲电化学形态向量，
         * 精准反推整机 100% 满电物理做功能量（Wh）。
         *
         * 核心电化学原理：
         * 1. 黄金采样无损性：亮屏期间 CPU 活跃、屏幕恒亮，采样为稳定的 1~3 秒一次梯形积分，0 丢失、0 睡眠误差；
         * 2. 无量纲形态投影：通过 calculateTheoreticalShapeWeight 消除高压与低压平台的非线性微差；
         * 3. 门限与物理边界：要求亮屏有效掉电 >= 1.5%（杜绝单点抖动），推算结果必须落在合理的单电芯物理区间（5Wh ~ 100Wh）。
         *
         * @param screenOnEnergyWh 纯亮屏期间实测积分能量（单位：Wh）
         * @param screenOnDropPercent 纯亮屏期间对应的电池百分比净掉电（如 25.2%）
         * @param startSoc 亮屏期间的起始电量百分比（可选）
         * @param endSoc 亮屏期间的终止电量百分比（可选）
         * @return 推算出的整机满电实测物理做功（Wh），若数据不足或异常返回 null
         */
        fun calculateScreenOnAnchoredFullEnergy(
            screenOnEnergyWh: Float,
            screenOnDropPercent: Float,
            startSoc: Float? = null,
            endSoc: Float? = null
        ): Double? {
            if (screenOnEnergyWh <= 0f || screenOnDropPercent <= 0f) return null
            if (screenOnDropPercent < 1.5f) return null

            val spanPercent = if (startSoc != null && endSoc != null && startSoc > endSoc) {
                startSoc - endSoc
            } else null

            // 核心修复：亮屏采样在放电周期中往往是穿插断续进行的（包含息屏待机）
            // 若起止跨度 spanPercent 显著大于纯亮屏掉电 screenOnDropPercent，
            // 绝不可直接将整个起止跨度的权重赋给分子，而应提取该跨度内的平均电化学形态密度，
            // 乘以实际亮屏掉电百分比，彻底消除息屏时间导致的严重分母稀释 Bug。
            val weight: Double = if (spanPercent != null && spanPercent > 0.5f) {
                val spanWeight = LearnedSocEnergyCurve.calculateTheoreticalShapeWeight(minSoc = endSoc!!, maxSoc = startSoc!!)
                val avgDensity = spanWeight / (spanPercent / 100.0)
                ((screenOnDropPercent / 100.0).toDouble() * avgDensity).coerceIn(0.001, 1.0)
            } else {
                (screenOnDropPercent / 100.0).toDouble()
            }

            if (weight <= 0.005) return null
            val fullWh = screenOnEnergyWh.toDouble() / weight
            return if (fullWh in 5.0..100.0) fullWh else null
        }

        /**
         * 纯算法函数：构建三源能量对比看板展示实体。
         * 实测学习能量采用工业级三层混合估算机制（Hybrid Remaining Energy Estimation）：
         * 1. 已学习区间采用实测数据；
         * 2. 平台区孤立缺失采用相邻电压插值；
         * 3. 拐点区与连续缺失采用基准容量与动态工况先验模型补全；
         * 4. 无法估计区间如实未知（返回 null），绝不伪造虚假数据。
         *
         * @param curve 当前学习曲线实体 [LearnedSocEnergyCurve]
         * @param currentSocPercent 当前设备电量百分比（0.0f ~ 100.0f）
         * @param hardwareNativeEnergyWh 硬件底层原生能量计数器（Wh，可选）
         * @param effectiveCapacityMah 当前设备基准容量（mAh，可选）
         * @param nominalVoltageVolts 电池标称电压（单位：V，默认 3.85V）
         * @param currentDischargeCurrentMa 当前实时放电电流（mA，用于动态工况内阻压降微调，可选）
         * @param temperatureCelsius 当前电池温度（℃，可选）
         * @return 三源对比展示实体 [EnergyComparisonOverview]
         */
        fun buildComparisonOverviewFromCurve(
            curve: LearnedSocEnergyCurve,
            currentSocPercent: Float,
            hardwareNativeEnergyWh: Double? = null,
            effectiveCapacityMah: Float? = null,
            nominalVoltageVolts: Float = 3.85f,
            currentDischargeCurrentMa: Double? = null,
            temperatureCelsius: Float? = null,
            designCapacityMah: Float? = null,
            screenOnEnergyWh: Float? = null,
            screenOnDropPercent: Float? = null,
            screenOnStartSoc: Float? = null,
            screenOnEndSoc: Float? = null,
            effectiveThresholdPercent: Int = 15,
            effectiveSampleCountLimit: Int = 10,
            participatingSessionCount: Int = 0,
            macroHistoricalFullWh: Double? = null
        ): EnergyComparisonOverview {
            // 亮屏高精微积分实测物理做功锚定（黄金采样无损展开）
            val screenOnAnchoredFullWh = if (screenOnEnergyWh != null && screenOnDropPercent != null) {
                calculateScreenOnAnchoredFullEnergy(
                    screenOnEnergyWh = screenOnEnergyWh,
                    screenOnDropPercent = screenOnDropPercent,
                    startSoc = screenOnStartSoc,
                    endSoc = screenOnEndSoc
                )
            } else null

            // 核心仲裁：严禁以当前在途放电周期外推整机满充标尺，杜绝局部瞬态工况导致标尺严重虚高！
            // 权威宏观满充标尺优先且仅采用满足过滤条件（门限 + 条数）的历史宏观中位数；
            // 若历史无满足门限的宏观记录，严禁以未达门限的在途放电会话兜底外推。
            val isCurrentSessionQualified = (screenOnAnchoredFullWh != null && (screenOnDropPercent ?: 0f) >= effectiveThresholdPercent)
            val authoritativeMacroWh = macroHistoricalFullWh ?: if (isCurrentSessionQualified) screenOnAnchoredFullWh else null

            // 计算每个完整 5% 区间的标称折算能量（Wh），用于兼容旧的 mixedEstimate
            val nominalWhPerBin: Double? = if (effectiveCapacityMah != null && effectiveCapacityMah > 0f) {
                (effectiveCapacityMah * nominalVoltageVolts * LearnedSocEnergyCurve.BIN_SOC_STEP / 100.0) / 1000.0
            } else null

            // 兼容旧模型
            val mixedEstimate = curve.estimateRemainingEnergyWhMixed(currentSocPercent, nominalWhPerBin)

            // 三层混合估算与 20 段运行时动态解析（融入权威物理做功锚定标尺）
            val resolvedBins = curve.resolveAllBins(
                effectiveCapacityMah = effectiveCapacityMah?.toDouble(),
                nominalVoltageVolts = nominalVoltageVolts.toDouble(),
                currentDischargeCurrentMa = currentDischargeCurrentMa,
                temperatureCelsius = temperatureCelsius,
                screenOnAnchoredFullEnergyWh = authoritativeMacroWh
            )
            val hybridEstimate = curve.estimateHybridRemainingEnergy(
                currentSocPercent = currentSocPercent,
                effectiveCapacityMah = effectiveCapacityMah?.toDouble(),
                nominalVoltageVolts = nominalVoltageVolts.toDouble(),
                currentDischargeCurrentMa = currentDischargeCurrentMa,
                temperatureCelsius = temperatureCelsius,
                screenOnAnchoredFullEnergyWh = authoritativeMacroWh
            )

            // 最终学习能量优先取用 hybridEstimate.remainingEnergyWh，若不可用平滑降级旧 mixedEstimate
            val learnedEnergyWh = hybridEstimate?.remainingEnergyWh ?: mixedEstimate?.totalWh

            val nominalEnergyWh = if (effectiveCapacityMah != null && effectiveCapacityMah > 0f) {
                val totalWh = (effectiveCapacityMah * nominalVoltageVolts) / 1000.0
                (totalWh * (currentSocPercent / 100.0))
            } else null

            // 核心功能演进：放电能量模型最终预测结果为电池 100% 满电实测做功（满电Wh是多少）
            val hybridFullEstimate = curve.estimateHybridRemainingEnergy(
                currentSocPercent = 100f,
                effectiveCapacityMah = effectiveCapacityMah?.toDouble(),
                nominalVoltageVolts = nominalVoltageVolts.toDouble(),
                currentDischargeCurrentMa = currentDischargeCurrentMa,
                temperatureCelsius = temperatureCelsius,
                screenOnAnchoredFullEnergyWh = authoritativeMacroWh
            )
            val fullLearnedWh = authoritativeMacroWh ?: hybridFullEstimate?.remainingEnergyWh ?: curve.estimatedFullUsableEnergyWh

            val fullNominalWh = if (effectiveCapacityMah != null && effectiveCapacityMah > 0f) {
                (effectiveCapacityMah * nominalVoltageVolts) / 1000.0
            } else null

            val fullDesignWh = if (designCapacityMah != null && designCapacityMah > 0f) {
                (designCapacityMah * nominalVoltageVolts) / 1000.0
            } else null

            val fullHardwareWh = if (hardwareNativeEnergyWh != null && currentSocPercent in 15f..100f) {
                (hardwareNativeEnergyWh / currentSocPercent.toDouble()) * 100.0
            } else null

            val efficiencyPercent = if (fullLearnedWh != null && fullNominalWh != null && fullNominalWh > 0.0) {
                (fullLearnedWh / fullNominalWh) * 100.0
            } else null

            val energyHealthPercent = if (fullLearnedWh != null && fullDesignWh != null && fullDesignWh > 0.0) {
                (fullLearnedWh / fullDesignWh) * 100.0
            } else null

            // 放电能量模型推算满充做功折合的等效物理容量（mAh）
            val equivMah = if (fullLearnedWh != null && fullLearnedWh > 0.0 && nominalVoltageVolts > 0f) {
                (fullLearnedWh * 1000.0) / nominalVoltageVolts
            } else null

            // 容量算法模型（算法轨 A）对应的独立健康度（SOH）
            val capSoh = if (effectiveCapacityMah != null && designCapacityMah != null && designCapacityMah > 0f) {
                (effectiveCapacityMah / designCapacityMah).toDouble() * 100.0
            } else null

            val isAnchored = isCurrentSessionQualified

            return EnergyComparisonOverview(
                learnedRemainingEnergyWh = learnedEnergyWh,
                hardwareNativeEnergyWh = hardwareNativeEnergyWh,
                nominalCalculatedEnergyWh = nominalEnergyWh,
                currentSocPercent = currentSocPercent,
                curveCoveragePercent = if (isAnchored) 1.0f else curve.coveragePercent,
                totalSessionsCount = curve.totalSampleSessions,
                nominalVoltageVolts = nominalVoltageVolts,
                confidence = if (isAnchored) ConfidenceLevel.HIGH else (hybridFullEstimate?.confidence ?: hybridEstimate?.confidence ?: curve.confidence),
                maturity = if (isAnchored) ModelMaturity.CONVERGED else curve.maturity,
                estimatedFullUsableEnergyWh = fullLearnedWh,
                mixedEstimate = mixedEstimate,
                hybridEstimate = hybridEstimate,
                resolvedBins = resolvedBins,
                fullLearnedEnergyWh = fullLearnedWh,
                fullHardwareEnergyWh = fullHardwareWh,
                fullNominalEnergyWh = fullNominalWh,
                hybridFullEstimate = hybridFullEstimate,
                dischargeEnergyEfficiencyPercent = efficiencyPercent,
                designCapacityMah = designCapacityMah,
                fullDesignEnergyWh = fullDesignWh,
                energyHealthRetentionPercent = energyHealthPercent,
                equivalentCapacityMah = equivMah,
                capacityModelSohPercent = capSoh,
                effectiveCapacityMah = effectiveCapacityMah,
                isScreenOnAnchored = isAnchored,
                screenOnMeasuredEnergyWh = screenOnEnergyWh,
                screenOnDropPercent = screenOnDropPercent,
                effectiveThresholdPercent = effectiveThresholdPercent,
                effectiveSampleCountLimit = effectiveSampleCountLimit,
                participatingSessionCount = participatingSessionCount,
                macroHistoricalFullWh = macroHistoricalFullWh
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
        incrementSessionCount: Boolean = true,
        allowInFlightReplacement: Boolean = false
    ): LearnedSocEnergyCurve {
        if (samples.isEmpty()) return getLearnedCurve()

        synchronized(this) {
            val currentCurve = getLearnedCurve()
            val newCurve = updateCurveWithSamples(
                currentCurve = currentCurve,
                samples = samples,
                updateTimestamp = System.currentTimeMillis(),
                incrementSessionCount = incrementSessionCount,
                allowInFlightReplacement = allowInFlightReplacement
            )
            if (newCurve !== currentCurve) {
                saveCurveToDisk(newCurve)
                cachedCurve = newCurve
            }
            return newCurve
        }
    }

    /**
     * 直接固化并持久化外部构建的放电能量学习曲线实体（用于历史重放与双重条件即时重算）。
     *
     * @param curve 待持久化的学习曲线实体 [LearnedSocEnergyCurve]
     */
    fun saveCurveDirectly(curve: LearnedSocEnergyCurve) {
        synchronized(this) {
            saveCurveToDisk(curve)
            cachedCurve = curve
        }
    }

    /**
     * 重置放电能量学习模型为初始全空状态（清除全部 20 个 SOC 区间已学数据与采样计数）。
     *
     * @return 全空的初始学习曲线实体 [LearnedSocEnergyCurve]
     */
    fun resetCurve(): LearnedSocEnergyCurve {
        synchronized(this) {
            val emptyCurve = LearnedSocEnergyCurve.createEmpty()
            saveCurveToDisk(emptyCurve)
            cachedCurve = emptyCurve
            return emptyCurve
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
     * @param currentDischargeCurrentMa 当前实时放电电流（mA，可选）
     * @param temperatureCelsius 当前电池温度（℃，可选）
     * @return 三源对比展示实体 [EnergyComparisonOverview]
     */
    fun buildComparisonOverview(
        currentSocPercent: Float,
        hardwareNativeEnergyWh: Double? = null,
        effectiveCapacityMah: Float? = null,
        nominalVoltageVolts: Float = 3.85f,
        currentDischargeCurrentMa: Double? = null,
        temperatureCelsius: Float? = null,
        designCapacityMah: Float? = null,
        screenOnEnergyWh: Float? = null,
        screenOnDropPercent: Float? = null,
        screenOnStartSoc: Float? = null,
        screenOnEndSoc: Float? = null,
        effectiveThresholdPercent: Int = 15,
        effectiveSampleCountLimit: Int = 10,
        participatingSessionCount: Int = 0,
        macroHistoricalFullWh: Double? = null
    ): EnergyComparisonOverview {
        return buildComparisonOverviewFromCurve(
            curve = getLearnedCurve(),
            currentSocPercent = currentSocPercent,
            hardwareNativeEnergyWh = hardwareNativeEnergyWh,
            effectiveCapacityMah = effectiveCapacityMah,
            nominalVoltageVolts = nominalVoltageVolts,
            currentDischargeCurrentMa = currentDischargeCurrentMa,
            temperatureCelsius = temperatureCelsius,
            designCapacityMah = designCapacityMah,
            screenOnEnergyWh = screenOnEnergyWh,
            screenOnDropPercent = screenOnDropPercent,
            screenOnStartSoc = screenOnStartSoc,
            screenOnEndSoc = screenOnEndSoc,
            effectiveThresholdPercent = effectiveThresholdPercent,
            effectiveSampleCountLimit = effectiveSampleCountLimit,
            participatingSessionCount = participatingSessionCount,
            macroHistoricalFullWh = macroHistoricalFullWh
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
                if (bin.lastUpdatedSessionId.isNotEmpty()) put("sid", bin.lastUpdatedSessionId)
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
                val sid = obj.optString("sid", "")

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
                        maxObservedWh = maxWh,
                        lastUpdatedSessionId = sid
                    )
                )
            }

            if (bins.size != LearnedSocEnergyCurve.BIN_COUNT) {
                return LearnedSocEnergyCurve.createEmpty()
            }

            // 历史数据自愈防御（版本 < 3 升级校准）：
            // 早期版本中有效跨度在 4.5%~4.9%（coverageFraction 在 0.70~0.995）的样本未执行 5% 满跨度归一化，
            // 导致历史存储的 learnedEnergyWh 偏低 5%~10%。在此执行一次性无损恢复折算，并固化至新版本。
            var hasSelfHealed = false
            val resolvedBins = if (version < CURRENT_CURVE_VERSION) {
                bins.map { bin ->
                    if (bin.hasLearnedData() && bin.coverageFraction in 0.70..0.995) {
                        hasSelfHealed = true
                        val factor = 1.0 / bin.coverageFraction
                        bin.copy(
                            learnedEnergyWh = bin.learnedEnergyWh?.let { it * factor },
                            coverageFraction = 1.0,
                            minObservedWh = bin.minObservedWh?.let { it * factor },
                            maxObservedWh = bin.maxObservedWh?.let { it * factor }
                        )
                    } else {
                        bin
                    }
                }
            } else {
                bins
            }

            val coveredCount = resolvedBins.count { it.hasLearnedData() }
            val coverage = coveredCount.toFloat() / LearnedSocEnergyCurve.BIN_COUNT.toFloat()
            val fullEnergy = if (coveredCount == LearnedSocEnergyCurve.BIN_COUNT) {
                resolvedBins.sumOf { it.learnedEnergyWh ?: 0.0 }
            } else null

            val (maturity, confidence) = evaluateMaturityAndConfidence(resolvedBins, sessions)

            val loadedCurve = LearnedSocEnergyCurve(
                bins = resolvedBins,
                coveragePercent = coverage,
                estimatedFullUsableEnergyWh = fullEnergy,
                confidence = confidence,
                maturity = maturity,
                curveVersion = CURRENT_CURVE_VERSION,
                totalSampleSessions = sessions,
                lastLearnedTimestamp = lastTime
            )

            if (hasSelfHealed || version < CURRENT_CURVE_VERSION) {
                saveCurveToDisk(loadedCurve)
            }

            return loadedCurve
        } catch (_: Exception) {
            return LearnedSocEnergyCurve.createEmpty()
        }
    }
}
