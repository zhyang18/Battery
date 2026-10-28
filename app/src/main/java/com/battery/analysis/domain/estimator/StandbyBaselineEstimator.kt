package com.battery.analysis.domain.estimator

import com.battery.analysis.domain.estimator.model.StandbyBaselineConfig
import com.battery.analysis.domain.estimator.model.StandbyBaselineEstimate
import com.battery.analysis.domain.estimator.model.StandbyBaselineSource
import com.battery.analysis.domain.estimator.model.StandbyConfidenceLevel
import com.battery.analysis.model.PowerDischargePoint
import java.util.Locale

/**
 * 息屏待机基线与深睡功耗多阶稳健估计器。
 *
 * 核心设计目标与物理原则：
 * 1. 三阶指标体系显式分离：
 *    - P_floor (P10)：有效深睡固定窗口功耗分布的低分位参考值，用于低功耗水平诊断，不代表硬件理论下限；
 *    - P_base (P20)：固定长度窗口代表功率的第 20 百分位数，代表系统在深睡挂起期的典型静态基线候选；
 *    - P_sleep_mean：状态明确且连续采样覆盖率达标区间内的梯形时间积分观测均值，覆盖不足时如实返回 null。
 * 2. 状态筛选与质量筛选分离：
 *    - 质量筛选仅剔除物理无效、溢出或传感器故障读数；
 *    - 状态筛选依据系统息屏状态过滤，全额保留真实基带寻呼脉冲与周期性活动，绝不使用经验门限粗暴切除真实高耗电。
 * 3. 严格限制大断层插值：
 *    - 连续两点采样间隔超过最大允许插值门限（默认 30 秒）时，归入 UNKNOWN_GAP（疑似挂起或数据缺失），严禁跨断层首尾直接连线插值虚构能耗。
 *    - 彻底废除 rawMinWatts 篡权覆盖基线的逻辑，典型基线由窗口级 P20 决定。
 * 5. 坚守零假数据原则：
 *    - 覆盖不足时如实返回 null 并采用基线推算，绝不伪造全量时间积分实测均值。
 */
object StandbyBaselineEstimator {

    /**
     * 根据瞬时放电物理采样点序列与底层采样周期，多阶综合估算待机基线与深睡功耗。
     *
     * @param samples 瞬时放电物理采样点列表 [List<PowerDischargePoint>]
     * @param recordIntervalMs 底层采样轮询基础周期（毫秒，默认 1000ms）
     * @param config 待机基线估计配置参数类 [StandbyBaselineConfig]
     * @return 待机基线与深睡功耗多阶估计结果对象 [StandbyBaselineEstimate]
     */
    fun estimate(
        samples: List<PowerDischargePoint>,
        recordIntervalMs: Long = 1000L,
        config: StandbyBaselineConfig = StandbyBaselineConfig()
    ): StandbyBaselineEstimate {
        // 1. 双层筛选管道
        // 第一层：质量筛选（仅剔除物理无效、NaN、<=0 或脱离锂电池物理特性的严重溢出坏数据）
        val qualityPassedSamples = samples
            .filter { sample ->
                sample.powerWatts.isFinite() &&
                        sample.powerWatts > 0f &&
                        sample.timestamp > 0L &&
                        (sample.voltageVolts <= 0f || sample.voltageVolts in 2.5f..4.6f)
            }
            .sortedBy { it.timestamp }

        // 第二层：状态筛选（按系统息屏状态筛选，全额保留所有物理读数，绝不使用 0.35W 切除真实脉冲）
        val validOffSamples = qualityPassedSamples.filter { !it.isScreenOn }

        val rawMinWatts = validOffSamples.minOfOrNull { it.powerWatts.toDouble() }

        // 核心守卫：若有效息屏样本量不足以划分时间窗口，直接安全回退默认先验
        if (validOffSamples.size < config.minSamplesPerWindow) {
            return StandbyBaselineEstimate(
                baselinePowerWatts = config.defaultPriorWatts,
                source = StandbyBaselineSource.DEFAULT_STANDBY_PRIOR,
                confidence = StandbyConfidenceLevel.LOW,
                validWindowCount = 0,
                coverageDurationMs = 0L,
                floorWatts = null,
                sleepMeanWatts = null,
                sleepCoverageRatio = 0.0,
                isMeanObserved = false,
                rawMinWatts = rawMinWatts,
                fallbackReason = "有效息屏采样点不足(${validOffSamples.size} < ${config.minSamplesPerWindow})"
            )
        }

        val totalTimeSpanMs = validOffSamples.last().timestamp - validOffSamples.first().timestamp

        // 2. 梯形时间积分与大断层阻断（严禁跨越 30 秒大断层首尾直接连线插值）
        var coveredDurationMs = 0L
        var observedEnergyJoules = 0.0

        for (i in 0 until validOffSamples.size - 1) {
            val curr = validOffSamples[i]
            val next = validOffSamples[i + 1]
            val dtMs = next.timestamp - curr.timestamp

            // 关键断层切断逻辑：若两点间隔超过最大允许插值间隔（默认 30 秒），属于 UNKNOWN_GAP，严禁连线插值
            if (dtMs in 1..config.maxInterpolationGapMs) {
                val avgWatts = (curr.powerWatts.toDouble() + next.powerWatts.toDouble()) / 2.0
                val segmentJoules = avgWatts * (dtMs.toDouble() / 1000.0)
                observedEnergyJoules += segmentJoules
                coveredDurationMs += dtMs
            }
        }

        val coverageRatio = if (totalTimeSpanMs > 0L) {
            (coveredDurationMs.toDouble() / totalTimeSpanMs.toDouble()).coerceIn(0.0, 1.0)
        } else {
            0.0
        }

        // 3. 固定长度时间窗口划分：
        // 采用固定步长窗口划分，确保每个窗口时长完全一致且具备统计可比性
        val windowDurationMs = maxOf(config.baseWindowDurationMs, recordIntervalMs * 3)
        val minSamplesPerWindow = if (recordIntervalMs >= 20_000L) 1 else config.minSamplesPerWindow

        val windows = aggregateIntoTimeWindows(
            samples = validOffSamples,
            windowDurationMs = windowDurationMs,
            minSamplesPerWindow = minSamplesPerWindow
        )

        // 若有效窗口数不足以支撑稳健分位数统计
        if (windows.size < config.minWindowsForMediumConfidence) {
            return StandbyBaselineEstimate(
                baselinePowerWatts = config.defaultPriorWatts,
                source = StandbyBaselineSource.DEFAULT_STANDBY_PRIOR,
                confidence = StandbyConfidenceLevel.LOW,
                validWindowCount = windows.size,
                coverageDurationMs = coveredDurationMs,
                floorWatts = null,
                sleepMeanWatts = null,
                sleepCoverageRatio = coverageRatio,
                isMeanObserved = false,
                rawMinWatts = rawMinWatts,
                fallbackReason = "有效时间窗口数不足(${windows.size} < ${config.minWindowsForMediumConfidence})"
            )
        }

        // 4. 计算固定窗口级代表功率稳健统计量（P5, P10, P20, P30, P50, MAD）
        val windowPowers = windows.map { it.representativePowerWatts }.sorted()
        val p5Watts = calculatePercentile(windowPowers, config.diagnosticLowerQuantile)
        val p10Watts = calculatePercentile(windowPowers, config.floorQuantile)
        val p20Watts = calculatePercentile(windowPowers, config.baselineQuantile)
        val p30Watts = calculatePercentile(windowPowers, 0.30)
        val p50Watts = calculatePercentile(windowPowers, 0.50)
        val madWatts = calculateMad(windowPowers, p50Watts)

        // 5. 多窗口低功耗支持度评估与活跃污染熔断检查
        // 统计有多少个独立固定时间窗口的代表功率处于纯净待机合理水平（<= 0.20W）
        val lowPowerWindows = windowPowers.filter { it <= config.suspiciousPollutionThresholdWatts }
        val lowPowerSupportCount = lowPowerWindows.size

        // 物理准则：若所有窗口的代表功率普遍偏高（P10 > 0.20W），且几乎没有低功耗窗口重复支持，
        // 说明息屏期间设备持续处于后台活跃状态（如音频播放、热点或高频唤醒），样本池被严重污染
        if (p10Watts > config.suspiciousPollutionThresholdWatts && lowPowerSupportCount < config.minLowPowerSupportWindows) {
            return StandbyBaselineEstimate(
                baselinePowerWatts = config.defaultPriorWatts,
                source = StandbyBaselineSource.DEFAULT_STANDBY_PRIOR,
                confidence = StandbyConfidenceLevel.LOW,
                validWindowCount = windows.size,
                coverageDurationMs = coveredDurationMs,
                floorWatts = p10Watts,
                sleepMeanWatts = null,
                sleepCoverageRatio = coverageRatio,
                isMeanObserved = false,
                p5Watts = p5Watts,
                p10Watts = p10Watts,
                p20Watts = p20Watts,
                p30Watts = p30Watts,
                p50Watts = p50Watts,
                madWatts = madWatts,
                rawMinWatts = rawMinWatts,
                fallbackReason = "息屏样本疑似受活跃任务严重污染(P10=${String.format(Locale.US, "%.2f", p10Watts)}W > ${config.suspiciousPollutionThresholdWatts}W 且缺乏低功耗窗口重复支持)"
            )
        }

        // 6. 三阶指标确定（彻底废除 rawMinWatts 篡权覆盖基线的逻辑）
        // 典型静态待机基线候选值采用固定窗口 P20（第 20 百分位数）
        val candidateBaselineWatts = p20Watts

        // 7. 时间积分观测均值与覆盖率门禁（70% 覆盖率与 15 分钟覆盖时长）
        val isCoverageSufficient = coverageRatio >= config.minCoverageRatioForMean && coveredDurationMs >= 15 * 60 * 1000L
        val (sleepMeanWatts, isObservedMean) = if (isCoverageSufficient && coveredDurationMs > 0L) {
            val meanWatts = observedEnergyJoules / (coveredDurationMs.toDouble() / 1000.0)
            Pair(meanWatts, true)
        } else {
            // 覆盖不足如实置空，绝不冒充实测均值
            Pair(null, false)
        }

        // 8. 确定置信等级与来源
        val isHighConfidence = isCoverageSufficient &&
                windows.size >= config.minWindowsForHighConfidence &&
                madWatts <= 0.10

        val confidenceLevel = when {
            isHighConfidence -> StandbyConfidenceLevel.HIGH
            windows.size >= config.minWindowsForMediumConfidence -> StandbyConfidenceLevel.MEDIUM
            else -> StandbyConfidenceLevel.LOW
        }

        val baselineSource = when {
            isObservedMean -> StandbyBaselineSource.MEASURED_TRAPEZOIDAL_INTEGRAL
            else -> StandbyBaselineSource.MEASURED_WINDOW_P20
        }

        val reason = if (!isObservedMean) {
            "连续采样覆盖率不足(${String.format(Locale.US, "%.1f", coverageRatio * 100)}% < ${String.format(Locale.US, "%.0f", config.minCoverageRatioForMean * 100)}%)，如实置空观测均值并采用固定窗口P20分位数作为典型待机基线"
        } else {
            null
        }

        return StandbyBaselineEstimate(
            baselinePowerWatts = candidateBaselineWatts,
            source = baselineSource,
            confidence = confidenceLevel,
            validWindowCount = windows.size,
            coverageDurationMs = coveredDurationMs,
            floorWatts = p10Watts,
            sleepMeanWatts = sleepMeanWatts,
            sleepCoverageRatio = coverageRatio,
            isMeanObserved = isObservedMean,
            p5Watts = p5Watts,
            p10Watts = p10Watts,
            p20Watts = p20Watts,
            p30Watts = p30Watts,
            p50Watts = p50Watts,
            madWatts = madWatts,
            rawMinWatts = rawMinWatts,
            fallbackReason = reason
        )
    }

    /**
     * 将已通过双层筛选的时间序列采样点划分为固定步长的时间窗口，并提取每个有效窗口的代表功率（中位数）。
     *
     * @param samples 已按时间排序的有效采样点列表
     * @param windowDurationMs 单个时间窗口的固定时长（毫秒）
     * @param minSamplesPerWindow 判定为有效时间窗口所需的最少采样点数
     * @return 有效时间窗口聚合结果列表 [List<StandbyTimeWindow>]
     */
    private fun aggregateIntoTimeWindows(
        samples: List<PowerDischargePoint>,
        windowDurationMs: Long,
        minSamplesPerWindow: Int
    ): List<StandbyTimeWindow> {
        if (samples.isEmpty() || windowDurationMs <= 0L) return emptyList()

        val result = mutableListOf<StandbyTimeWindow>()
        val startTs = samples.first().timestamp
        val bucketMap = LinkedHashMap<Long, MutableList<Double>>()

        for (sample in samples) {
            val bucketIndex = (sample.timestamp - startTs) / windowDurationMs
            val bucketList = bucketMap.getOrPut(bucketIndex) { mutableListOf() }
            bucketList.add(sample.powerWatts.toDouble())
        }

        for ((index, powers) in bucketMap) {
            if (powers.size >= minSamplesPerWindow) {
                powers.sort()
                val medianWatts = calculateMedian(powers)
                val windowStart = startTs + index * windowDurationMs
                result.add(
                    StandbyTimeWindow(
                        startTs = windowStart,
                        endTs = windowStart + windowDurationMs,
                        sampleCount = powers.size,
                        representativePowerWatts = medianWatts
                    )
                )
            }
        }

        return result
    }

    /**
     * 计算已排序数值列表的中位数。
     *
     * @param sortedList 已升序排序的双精度浮点数列表
     * @return 中位数值，若列表为空返回 0.0
     */
    private fun calculateMedian(sortedList: List<Double>): Double {
        if (sortedList.isEmpty()) return 0.0
        val size = sortedList.size
        return if (size % 2 == 1) {
            sortedList[size / 2]
        } else {
            (sortedList[size / 2 - 1] + sortedList[size / 2]) / 2.0
        }
    }

    /**
     * 计算已排序数值列表的任意指定百分位数（采用标准线性插值法）。
     *
     * @param sortedList 已升序排序的双精度浮点数列表
     * @param percentile 目标百分位点（0.0 ~ 1.0）
     * @return 线性插值推导出的百分位数值，若列表为空返回 0.0
     */
    private fun calculatePercentile(sortedList: List<Double>, percentile: Double): Double {
        if (sortedList.isEmpty()) return 0.0
        if (sortedList.size == 1) return sortedList.first()
        val clampedP = percentile.coerceIn(0.0, 1.0)
        val rank = clampedP * (sortedList.size - 1)
        val lowerIndex = rank.toInt()
        val upperIndex = minOf(lowerIndex + 1, sortedList.size - 1)
        val weight = rank - lowerIndex
        return sortedList[lowerIndex] * (1.0 - weight) + sortedList[upperIndex] * weight
    }

    /**
     * 计算已排序数值列表关于中位数的绝对中位差（MAD, Median Absolute Deviation）。
     *
     * @param sortedList 已升序排序的双精度浮点数列表
     * @param median 已计算出的中位数值
     * @return 绝对中位差数值
     */
    private fun calculateMad(sortedList: List<Double>, median: Double): Double {
        if (sortedList.isEmpty()) return 0.0
        val deviations = sortedList.map { kotlin.math.abs(it - median) }.sorted()
        return calculateMedian(deviations)
    }

    /**
     * 内部时间窗口聚合实体类。
     *
     * @property startTs 窗口起始绝对时间戳（毫秒）
     * @property endTs 窗口结束绝对时间戳（毫秒）
     * @property sampleCount 窗口内容纳的有效物理采样点数
     * @property representativePowerWatts 窗口代表功率（中位数值，单位：W）
     */
    private data class StandbyTimeWindow(
        val startTs: Long,
        val endTs: Long,
        val sampleCount: Int,
        val representativePowerWatts: Double
    )
}
