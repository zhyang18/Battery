package com.battery.analysis.domain.estimator

import com.battery.analysis.domain.estimator.model.*
import com.battery.analysis.manager.PowerOverviewStats
import com.battery.analysis.model.PowerUsageRecord
import kotlin.math.roundToLong

/**
 * 满电理论续航核心推算引擎（FullChargeRuntimeEstimator v2.0）。
 * 基于 168 小时（7 天物理使用时间）真实历史放电区间构建工况条件化典型行为模型，
 * 遵循严格物理守恒：T_full = E_full,usable / P_typical,future。
 * 当工况类别覆盖充分时启用行为条件化高阶模型；当样本分布不足时平滑安全降级至全局加权均值基准，
 * 严格秉持零虚构数据原则：无能量数据或历史不足时如实返回 null。
 */
class FullChargeRuntimeEstimator(
    private val energyEstimator: FullChargeEnergyEstimator = FullChargeEnergyEstimator(),
    private val sampleNormalizer: PowerSampleNormalizer = PowerSampleNormalizer(),
    private val classifier: PowerRegimeClassifier = PowerRegimeClassifier(),
    private val profileEstimator: UsageProfileEstimator = UsageProfileEstimator(),
    private val regimePowerEstimator: RegimePowerEstimator = RegimePowerEstimator(),
    private val windowSelector: HistoricalWindowSelector = DefaultHistoricalWindowSelector()
) {

    /**
     * 基于预先提取并裁剪后的有效放电物理区间集合，推算设备满电理论可用续航结果。
     * 遵循严格物理守恒模型（FullChargeRuntimeEstimator v2.1）：
     * 1. 分离亮屏/息屏时间占比（第一层）与亮屏内部工况占比（第二层）；
     * 2. 各工况独立计算实际做功放电功率 P_i = Sum(w_k * E_k) / Sum(w_k * Delta t_k)；
     * 3. 典型亮屏功耗 = Sum(R_i * P_i)；
     * 4. 满电续航严格按 T_full = E_full / P_typical 推导并支持全链路追溯。
     *
     * @param intervals 经过自适应历史时间窗口提取并带有时间衰减权重的有效放电物理区间列表
     * @param energyEstimate 满充物理可用能量估算结果 [FullChargeEnergyEstimate]
     * @param windowMetrics 可选的自适应窗口元信息指标 [AdaptiveWindowMetrics]
     * @return 满电续航统一推算结果聚合实体 [FullChargeRuntimeResult]
     */
    fun estimateFromIntervals(
        intervals: List<DischargeInterval>,
        energyEstimate: FullChargeEnergyEstimate,
        windowMetrics: AdaptiveWindowMetrics? = null
    ): FullChargeRuntimeResult {
        val fullWh = energyEstimate.energyWh
        if (fullWh == null || fullWh <= 0.0 || energyEstimate.source == EnergySource.UNAVAILABLE) {
            return FullChargeRuntimeResult(
                fullRuntimeMs = null,
                fullEnergyWh = null,
                predictedPowerWatts = null,
                modelVersion = RuntimeModelVersion.FULL_RUNTIME_V2_1,
                confidenceLevel = RuntimeConfidenceLevel.UNAVAILABLE,
                energySource = energyEstimate.source,
                effectiveHistoryMs = 0L,
                usedRegimeModel = false,
                fallbackReason = "满充物理总能量不可用",
                historyWindowDays = windowMetrics?.windowDays ?: 7.0,
                effectiveDischargeDurationMs = 0L,
                dataSufficiency = DataSufficiencyLevel.INSUFFICIENT,
                profile = null
            )
        }

        val totalHistoryMs = intervals.sumOf { it.durationMs }
        val weightedHistoryMs = intervals.sumOf { it.durationMs.toDouble() * it.ageWeight }
        val weightedHistoryWh = intervals.sumOf { it.energyWh * it.ageWeight }

        val windowDays = windowMetrics?.windowDays ?: 7.0
        val windowDaysInt = windowDays.toInt().coerceAtLeast(7)

        // 若历史有效放电记录不足 10 分钟，无法建立统计模型，如实返回未知
        if (totalHistoryMs < 600_000L || weightedHistoryMs <= 0.0 || weightedHistoryWh <= 0.0) {
            return FullChargeRuntimeResult(
                fullRuntimeMs = null,
                fullEnergyWh = fullWh,
                predictedPowerWatts = null,
                modelVersion = RuntimeModelVersion.FULL_RUNTIME_V2_1,
                confidenceLevel = RuntimeConfidenceLevel.UNAVAILABLE,
                energySource = energyEstimate.source,
                effectiveHistoryMs = totalHistoryMs,
                usedRegimeModel = false,
                fallbackReason = "历史有效放电时长不足 10 分钟",
                historyWindowDays = windowDays,
                effectiveDischargeDurationMs = totalHistoryMs,
                dataSufficiency = DataSufficiencyLevel.INSUFFICIENT,
                profile = null
            )
        }

        // 1. 全局历史物理加权平均功率 P_global 作为基准底线 (用于比对与工况缺失时的安全降级)
        val pGlobal = weightedHistoryWh / (weightedHistoryMs / 3600000.0)

        // 提取设备实测亮屏中位基准功率与息屏待机底噪基准功率，动态适配不同机型功耗水平（折叠屏、平板或低功耗小屏）
        val onPowersAll = intervals.filter { it.isScreenOn && it.powerWatts > 0.0 && it.powerWatts.isFinite() }.map { it.powerWatts }
        val offPowersAll = intervals.filter { !it.isScreenOn && it.powerWatts > 0.0 && it.powerWatts.isFinite() }.map { it.powerWatts }

        val dynamicMedianOnWatts = RobustPowerStatistics.calculateMedian(onPowersAll)
            ?.takeIf { it > 0.0 } ?: PowerRegimeClassifier.DEFAULT_MEDIAN_ON_WATTS
        val dynamicBaselineOffWatts = RobustPowerStatistics.calculateMedian(offPowersAll)
            ?.takeIf { it > 0.0 } ?: PowerRegimeClassifier.DEFAULT_BASELINE_OFF_WATTS

        // 2. 按工况细分归集有效放电物理区间 (累计加权持续时长与物理时长)
        val intervalsByRegime = mutableMapOf<PowerRegime, MutableList<DischargeInterval>>()
        val rawDurByRegime = mutableMapOf<PowerRegime, Long>()
        val weightedDurByRegime = mutableMapOf<PowerRegime, Double>()

        for (interval in intervals) {
            val regime = classifier.classify(
                interval = interval,
                baselineOffWatts = dynamicBaselineOffWatts,
                medianOnWatts = dynamicMedianOnWatts
            )
            intervalsByRegime.getOrPut(regime) { mutableListOf() }.add(interval)
            rawDurByRegime[regime] = (rawDurByRegime[regime] ?: 0L) + interval.durationMs
            val weightedDur = interval.durationMs.toDouble() * interval.ageWeight
            weightedDurByRegime[regime] = (weightedDurByRegime[regime] ?: 0.0) + weightedDur
        }

        // 3. 计算各工况代表功率 (单工况累计有效时长 >= 60s)
        val regimePowers = regimePowerEstimator.estimateAllRegimePowers(intervalsByRegime)

        // 4. 第一层时间占比：亮屏 vs 息屏时间占比计算 (按持续时长加权计算，严禁按采样点数计算)
        val onIntervals = intervals.filter { it.isScreenOn }
        val offIntervals = intervals.filter { !it.isScreenOn }

        val onDurMs = onIntervals.sumOf { it.durationMs }
        val offDurMs = offIntervals.sumOf { it.durationMs }

        val onDurWeighted = onIntervals.sumOf { it.durationMs.toDouble() * it.ageWeight }
        val offDurWeighted = offIntervals.sumOf { it.durationMs.toDouble() * it.ageWeight }
        val totalDurWeighted = onDurWeighted + offDurWeighted

        val screenOnRatio = if (totalDurWeighted > 0.0) (onDurWeighted / totalDurWeighted) else 0.0
        val screenOffRatio = if (totalDurWeighted > 0.0) (offDurWeighted / totalDurWeighted) else 0.0

        // 5. 第二层时间占比与各工况功耗：亮屏内部工况占比分解 (ON_LIGHT, ON_NORMAL, ON_HEAVY)
        val lightMs = rawDurByRegime[PowerRegime.ON_LIGHT] ?: 0L
        val normalMs = rawDurByRegime[PowerRegime.ON_NORMAL] ?: 0L
        val heavyMs = rawDurByRegime[PowerRegime.ON_HEAVY] ?: 0L

        val lightDurWeighted = weightedDurByRegime[PowerRegime.ON_LIGHT] ?: 0.0
        val normalDurWeighted = weightedDurByRegime[PowerRegime.ON_NORMAL] ?: 0.0
        val heavyDurWeighted = weightedDurByRegime[PowerRegime.ON_HEAVY] ?: 0.0

        val (lightRatio, normalRatio, heavyRatio) = if (onDurWeighted > 0.0) {
            val rL = lightDurWeighted / onDurWeighted
            val rN = normalDurWeighted / onDurWeighted
            val rH = heavyDurWeighted / onDurWeighted
            val rSum = rL + rN + rH
            if (rSum > 0.0) {
                Triple(rL / rSum, rN / rSum, rH / rSum)
            } else {
                Triple(0.0, 0.0, 0.0)
            }
        } else {
            Triple(0.0, 0.0, 0.0)
        }

        val lightPower = regimePowers[PowerRegime.ON_LIGHT]
        val normalPower = regimePowers[PowerRegime.ON_NORMAL]
        val heavyPower = regimePowers[PowerRegime.ON_HEAVY]

        // 亮屏稳定工况计数 (达到最小有效时长 60s 且具有有效功率)
        var stableOnRegimeCount = 0
        if (lightPower != null && lightPower > 0.0) stableOnRegimeCount++
        if (normalPower != null && normalPower > 0.0) stableOnRegimeCount++
        if (heavyPower != null && heavyPower > 0.0) stableOnRegimeCount++

        val onEnergyWeighted = onIntervals.sumOf { it.energyWh * it.ageWeight }
        val pGlobalOn = if (onDurWeighted > 0.0 && onEnergyWeighted > 0.0) {
            onEnergyWeighted / (onDurWeighted / 3600000.0)
        } else null

        // 典型亮屏功耗推算：P_typical,on = R_light * P_light + R_normal * P_normal + R_heavy * P_heavy
        val pTypicalScreenOn = if (onDurMs >= 60_000L && onDurWeighted > 0.0) {
            var weightedPowerSum = 0.0
            var effectiveRatioSum = 0.0
            if (lightPower != null && lightPower > 0.0 && lightRatio > 0.0) {
                weightedPowerSum += lightRatio * lightPower
                effectiveRatioSum += lightRatio
            }
            if (normalPower != null && normalPower > 0.0 && normalRatio > 0.0) {
                weightedPowerSum += normalRatio * normalPower
                effectiveRatioSum += normalRatio
            }
            if (heavyPower != null && heavyPower > 0.0 && heavyRatio > 0.0) {
                weightedPowerSum += heavyRatio * heavyPower
                effectiveRatioSum += heavyRatio
            }

            if (effectiveRatioSum > 0.0) {
                weightedPowerSum / effectiveRatioSum
            } else {
                pGlobalOn
            }
        } else {
            null
        }

        // 6. 息屏内部工况占比分解 (OFF_LOW, OFF_HIGH)
        val offLowMs = rawDurByRegime[PowerRegime.OFF_LOW] ?: 0L
        val offHighMs = rawDurByRegime[PowerRegime.OFF_HIGH] ?: 0L
        val offLowDurWeighted = weightedDurByRegime[PowerRegime.OFF_LOW] ?: 0.0
        val offHighDurWeighted = weightedDurByRegime[PowerRegime.OFF_HIGH] ?: 0.0

        val (offLowRatio, offHighRatio) = if (offDurWeighted > 0.0) {
            val rL = offLowDurWeighted / offDurWeighted
            val rH = offHighDurWeighted / offDurWeighted
            val rSum = rL + rH
            if (rSum > 0.0) {
                Pair(rL / rSum, rH / rSum)
            } else {
                Pair(0.0, 0.0)
            }
        } else {
            Pair(0.0, 0.0)
        }

        val offLowPower = regimePowers[PowerRegime.OFF_LOW]
        val offHighPower = regimePowers[PowerRegime.OFF_HIGH]

        val offEnergyWeighted = offIntervals.sumOf { it.energyWh * it.ageWeight }
        val pGlobalOff = if (offDurWeighted > 0.0 && offEnergyWeighted > 0.0) {
            offEnergyWeighted / (offDurWeighted / 3600000.0)
        } else null

        // 典型息屏功耗推算：P_typical,off = R_off_low * P_off_low + R_off_high * P_off_high
        val pTypicalScreenOff = if (offDurMs >= 60_000L && offDurWeighted > 0.0) {
            var weightedOffPowerSum = 0.0
            var effectiveOffRatioSum = 0.0
            if (offLowPower != null && offLowPower > 0.0 && offLowRatio > 0.0) {
                weightedOffPowerSum += offLowRatio * offLowPower
                effectiveOffRatioSum += offLowRatio
            }
            if (offHighPower != null && offHighPower > 0.0 && offHighRatio > 0.0) {
                weightedOffPowerSum += offHighRatio * offHighPower
                effectiveOffRatioSum += offHighRatio
            }

            if (effectiveOffRatioSum > 0.0) {
                weightedOffPowerSum / effectiveOffRatioSum
            } else {
                pGlobalOff
            }
        } else {
            null
        }

        // 7. 全局综合典型功耗：P_global = R_on * P_on + (1 - R_on) * P_off (方案第 21 节)
        var totalValidRegimeCount = 0
        if (lightPower != null && lightPower > 0.0) totalValidRegimeCount++
        if (normalPower != null && normalPower > 0.0) totalValidRegimeCount++
        if (heavyPower != null && heavyPower > 0.0) totalValidRegimeCount++
        if (offLowPower != null && offLowPower > 0.0) totalValidRegimeCount++
        if (offHighPower != null && offHighPower > 0.0) totalValidRegimeCount++

        // 严格遵循两态工况融合要求：仅当亮屏与息屏均具备有效代表功耗且时间占比合法时，方可启用综合融合模型；
        // 若任一核心场景工况数据缺失，严禁将亮屏功耗冒充为整机日常综合功耗，必须安全降级至全局大样本物理历史加权均值 pGlobal
        val hasBothScenePowers = pTypicalScreenOn != null && pTypicalScreenOn > 0.0 &&
                pTypicalScreenOff != null && pTypicalScreenOff > 0.0 &&
                screenOnRatio in 0.0..1.0 && screenOffRatio in 0.0..1.0

        val pGlobalComposite = if (hasBothScenePowers) {
            screenOnRatio * pTypicalScreenOn!! + screenOffRatio * pTypicalScreenOff!!
        } else {
            pGlobal
        }

        val canUseRegimeModel = hasBothScenePowers && totalValidRegimeCount >= 2 && pGlobalComposite > 0.0 && pGlobalComposite.isFinite()
        val finalPredictedPower = if (canUseRegimeModel) pGlobalComposite else pGlobal
        if (finalPredictedPower <= 0.0 || !finalPredictedPower.isFinite()) {
            return FullChargeRuntimeResult(
                fullRuntimeMs = null,
                fullEnergyWh = fullWh,
                predictedPowerWatts = null,
                modelVersion = RuntimeModelVersion.FULL_RUNTIME_V2_2,
                confidenceLevel = RuntimeConfidenceLevel.UNAVAILABLE,
                energySource = energyEstimate.source,
                effectiveHistoryMs = totalHistoryMs,
                usedRegimeModel = false,
                fallbackReason = "计算预测功率非有限正数",
                historyWindowDays = windowDays,
                effectiveDischargeDurationMs = totalHistoryMs,
                dataSufficiency = DataSufficiencyLevel.INSUFFICIENT,
                profile = null
            )
        }

        // 8. 物理续航推导：T_full = E_full / P_typical
        val fullHours = fullWh / finalPredictedPower
        val fullMs = (fullHours * 3600.0 * 1000.0).roundToLong()

        val screenOnMsResult = if (pTypicalScreenOn != null && pTypicalScreenOn > 0.0) {
            ((fullWh / pTypicalScreenOn) * 3600.0 * 1000.0).roundToLong()
        } else null

        val screenOffMsResult = if (pTypicalScreenOff != null && pTypicalScreenOff > 0.0) {
            ((fullWh / pTypicalScreenOff) * 3600.0 * 1000.0).roundToLong()
        } else null

        // 9. 数据充分性与置信度多维评定（亮屏、息屏、全局三态解耦与时长门槛提升）
        // 亮屏：<24h 为低/较少，24h~72h 为中/可用，>=72h 为高/充足
        val screenOnConfidence = RuntimeConfidenceLevel.evaluateByDuration(com.battery.analysis.util.BatteryEnergyCalculator.ROW_SCREEN_ON, onDurMs)
        val screenOnSufficiency = DataSufficiencyLevel.evaluateByDuration(com.battery.analysis.util.BatteryEnergyCalculator.ROW_SCREEN_ON, onDurMs)

        // 息屏：同亮屏一致（<24h 为低/较少，24h~72h 为中/可用，>=72h 为高/充足）
        val screenOffConfidence = RuntimeConfidenceLevel.evaluateByDuration(com.battery.analysis.util.BatteryEnergyCalculator.ROW_SCREEN_OFF, offDurMs)
        val screenOffSufficiency = DataSufficiencyLevel.evaluateByDuration(com.battery.analysis.util.BatteryEnergyCalculator.ROW_SCREEN_OFF, offDurMs)

        // 全局：<48h 为低/较少，48h~120h 为中/可用，>=120h 为高/充足
        val globalConfidence = RuntimeConfidenceLevel.evaluateByDuration(com.battery.analysis.util.BatteryEnergyCalculator.ROW_GLOBAL, totalHistoryMs)
        val globalSufficiency = DataSufficiencyLevel.evaluateByDuration(com.battery.analysis.util.BatteryEnergyCalculator.ROW_GLOBAL, totalHistoryMs)

        // 10. 构建 RuntimeProfile 画像实体 (方案第 18 节)
        val nowMs = intervals.maxOfOrNull { it.endTimeMs } ?: System.currentTimeMillis()
        val windowStartMs = nowMs - (windowDays * 86_400_000.0).roundToLong()
        val profile = RuntimeProfile(
            windowStartMs = windowStartMs,
            windowEndMs = nowMs,
            windowDays = windowDaysInt,
            effectiveDischargeMs = totalHistoryMs,
            screenOnMs = onDurMs,
            screenOffMs = offDurMs,
            screenOnRatio = screenOnRatio,
            screenOffRatio = screenOffRatio,
            lightMs = lightMs,
            normalMs = normalMs,
            heavyMs = heavyMs,
            lightRatio = lightRatio,
            normalRatio = normalRatio,
            heavyRatio = heavyRatio,
            lightPowerW = lightPower,
            normalPowerW = normalPower,
            heavyPowerW = heavyPower,
            typicalScreenOnPowerW = pTypicalScreenOn,
            offLowMs = offLowMs,
            offHighMs = offHighMs,
            offLowRatio = offLowRatio,
            offHighRatio = offHighRatio,
            offLowPowerW = offLowPower,
            offHighPowerW = offHighPower,
            typicalScreenOffPowerW = pTypicalScreenOff,
            typicalGlobalPowerW = finalPredictedPower,
            confidence = globalConfidence
        )

        // 11. 构建质量说明与追溯依据 (方案第 12、13 节)
        val qualityReasons = mutableListOf<String>()
        qualityReasons.addAll(energyEstimate.notes)

        if (windowDays > 7.1) {
            val daysStr = String.format(java.util.Locale.US, "%.1f", windowDays)
            qualityReasons.add("7天样本不足，自适应扩展至近 $daysStr 天 (时间衰减加权)")
        } else {
            qualityReasons.add("基于最近 7 天完整周期作息建模")
        }

        if (canUseRegimeModel) {
            val lightPct = String.format(java.util.Locale.US, "%.1f%%", lightRatio * 100.0)
            val normalPct = String.format(java.util.Locale.US, "%.1f%%", normalRatio * 100.0)
            val heavyPct = String.format(java.util.Locale.US, "%.1f%%", heavyRatio * 100.0)
            qualityReasons.add("覆盖 $stableOnRegimeCount 类亮屏工况建模 (轻载 $lightPct, 普通 $normalPct, 重载 $heavyPct)")
        } else {
            val reason = when {
                !hasBothScenePowers -> "缺乏完整的亮屏或息屏工况样本，安全平滑降级至全局历史物理均值"
                else -> "工况类别不足($stableOnRegimeCount 类)，安全平滑降级至全局历史物理均值"
            }
            qualityReasons.add(reason)
        }

        if (pTypicalScreenOn != null && pGlobalOn != null && kotlin.math.abs(pTypicalScreenOn - pGlobalOn) < 0.05) {
            qualityReasons.add("亮屏工况典型功耗贴合历史加权均值，新旧模型预测一致")
        }

        val fallbackReason = when {
            canUseRegimeModel -> null
            !hasBothScenePowers -> "缺乏完整的亮屏或息屏工况数据，已安全降级至全局加权均值"
            else -> "工况样本类别不足，已安全降级至全局加权均值"
        }

        return FullChargeRuntimeResult(
            fullRuntimeMs = if (fullMs > 0L) fullMs else null,
            fullEnergyWh = fullWh,
            predictedPowerWatts = finalPredictedPower,
            modelVersion = RuntimeModelVersion.FULL_RUNTIME_V2_2,
            confidenceLevel = globalConfidence,
            energySource = energyEstimate.source,
            effectiveHistoryMs = totalHistoryMs,
            usedRegimeModel = canUseRegimeModel,
            fallbackReason = fallbackReason,
            screenOnFullRuntimeMs = if (screenOnMsResult != null && screenOnMsResult > 0L) screenOnMsResult else null,
            screenOffFullRuntimeMs = if (screenOffMsResult != null && screenOffMsResult > 0L) screenOffMsResult else null,
            predictedScreenOnPowerWatts = pTypicalScreenOn,
            predictedScreenOffPowerWatts = pTypicalScreenOff,
            qualityReasons = qualityReasons,
            historyWindowDays = windowDays,
            effectiveDischargeDurationMs = totalHistoryMs,
            dataSufficiency = globalSufficiency,
            screenOnEffectiveMs = onDurMs,
            screenOffEffectiveMs = offDurMs,
            screenOnConfidenceLevel = screenOnConfidence,
            screenOffConfidenceLevel = screenOffConfidence,
            screenOnDataSufficiency = screenOnSufficiency,
            screenOffDataSufficiency = screenOffSufficiency,
            profile = profile
        )
    }

    /**
     * 一站式整合估算接口：从当前会话与历史快照直接提取自适应历史区间、估算能量并推算满电理论续航。
     * 自动应用 7~30 天自适应窗口展开、时间指数衰减与工况条件化典型行为模型。
     *
     * @param currentOverview 当前正在进行的活跃放电会话统计（若无传入 null）
     * @param allRecords 设备本地持久化的历史放电记录列表
     * @param nativeEnergyNwh 硬件底层能量计数器（纳瓦时 nWh）
     * @param batteryPercent 当前电池电量百分比 (1..100)
     * @param fccMah 电量计实测真实满充容量 (mAh)
     * @param designCapacityMah 电池标称出厂设计容量 (mAh)
     * @param sohPercent 电池健康度百分比 (0.0..100.0)
     * @param baseTimestamp 统计基准时间戳（毫秒）
     * @return 满电续航统一推算结果聚合实体 [FullChargeRuntimeResult]
     */
    fun estimateFullRuntime(
        currentOverview: PowerOverviewStats?,
        allRecords: List<PowerUsageRecord>,
        nativeEnergyNwh: Long? = null,
        batteryPercent: Int? = null,
        fccMah: Float? = null,
        designCapacityMah: Float? = null,
        sohPercent: Float? = null,
        baseTimestamp: Long = System.currentTimeMillis()
    ): FullChargeRuntimeResult {
        val energyEstimate = energyEstimator.estimateFullEnergy(
            nativeEnergyNwh = nativeEnergyNwh,
            batteryPercent = batteryPercent,
            fccMah = fccMah,
            designCapacityMah = designCapacityMah,
            sohPercent = sohPercent
        )

        val window = windowSelector.select(
            nowMs = baseTimestamp,
            records = allRecords,
            currentOverview = currentOverview
        )

        return estimateFromIntervals(window.intervals, energyEstimate, window.metrics)
    }
}
