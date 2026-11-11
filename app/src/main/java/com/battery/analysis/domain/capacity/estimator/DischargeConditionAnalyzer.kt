package com.battery.analysis.domain.capacity.estimator

import com.battery.analysis.domain.capacity.model.CapacitySample
import com.battery.analysis.domain.capacity.model.DischargeLoadBand
import com.battery.analysis.domain.capacity.model.DischargeOperatingCondition
import com.battery.analysis.domain.capacity.model.DischargeThermalBand
import com.battery.analysis.domain.capacity.model.LoadProfileType
import com.battery.analysis.domain.capacity.model.QualityLevel
import com.battery.analysis.domain.capacity.model.ThermalProfileType
import com.battery.analysis.domain.capacity.model.VoltageSagMetrics
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 放电热环境与负载工况全景特征分析器（DischargeConditionAnalyzer）。
 *
 * 遵循技术评审 P2-B1 与 P2-B2 核心规范：
 * 1. 真实记录电池实测温度与负载电流/功率工况，严禁使用粗暴常数补偿，杜绝把工况波动误判为电池老化；
 * 2. 温度与电流/功率分位数采用**时间加权分位数（Duration-Weighted Percentiles）**，采样点不均匀时不产生虚假偏移；
 * 3. 超过 120 秒采样断层坚决切断时间加权，不向断层延伸上一温度；
 * 4. 统计温区与负载区间时间占比，区分纯净（PURE）与跨区间混合（MIXED）剖面；
 * 5. 平均功率由同步物理电压与物理电流微积分严格计算（\int P dt / \int dt），严禁 V_avg * I_avg；
 * 6. 严格区分观测电压落差与纯欧姆内阻，仅在具备清晰电流阶跃（ΔI >= 200mA）时估算表观动态电阻 R_app = 1000 * ΔV(mV) / ΔI(mA)，无阶跃时为 null。
 */
object DischargeConditionAnalyzer {

    /** 最大连续采样时间步长门限（秒，超过 120 秒视为断层，不参与梯形时间加权） */
    private const val MAX_TRAPEZOID_STEP_SEC: Double = 120.0

    /** 判定工况剖面为纯净（PURE）的单一区间时间占比门限（85.0%） */
    private const val PURE_PROFILE_RATIO_THRESHOLD: Double = 0.85

    /** 触发动态表观内阻计算的最小放电电流突增阶跃门限（mA） */
    private const val MIN_CURRENT_STEP_FOR_SAG_MA: Double = 200.0

    /** 电流阶跃瞬态响应的最大允许时间窗口（秒） */
    private const val MAX_STEP_RESPONSE_WINDOW_SEC: Double = 10.0

    /**
     * 内部时间加权微元切片结构体。
     *
     * @property value 物理指标代表值
     * @property durationSec 该切片代表的有效持续秒数
     */
    data class TimeWeightedSlice(
        val value: Double,
        val durationSec: Double
    )

    /**
     * 对单次放电会话采样点时序执行热环境与负载工况全景特征提取。
     *
     * @param samples 放电采样点序列（建议时间戳升序）
     * @return 全景工况特征实体 [DischargeOperatingCondition]
     */
    fun analyze(samples: List<CapacitySample>): DischargeOperatingCondition {
        if (samples.isEmpty()) {
            return DischargeOperatingCondition()
        }

        val sortedSamples = samples.sortedBy { it.timestampMs }
        val sessionTotalDurationSec = ((sortedSamples.last().timestampMs - sortedSamples.first().timestampMs) / 1000L).coerceAtLeast(0L)

        // 1. 热环境特征计算（时间加权分位数、各温区时间占比、PURE/MIXED 剖面）
        val tempSlices = mutableListOf<TimeWeightedSlice>()
        val startTemp = sortedSamples.firstOrNull { it.temperatureC != null }?.temperatureC
        val endTemp = sortedSamples.lastOrNull { it.temperatureC != null }?.temperatureC

        var weightedTempSum = 0.0
        var totalTempDurationSec = 0.0
        var lowTempDurationSec = 0.0
        var roomTempDurationSec = 0.0
        var highTempDurationSec = 0.0

        for (i in 0 until sortedSamples.size - 1) {
            val a = sortedSamples[i]
            val b = sortedSamples[i + 1]
            val dt = (b.timestampMs - a.timestampMs) / 1000.0
            // 采样缺口保护：超过 120 秒断层不参与加权，绝不向缺口延续温度
            if (dt in 0.1..MAX_TRAPEZOID_STEP_SEC) {
                val tA = a.temperatureC
                val tB = b.temperatureC
                if (tA != null && tB != null) {
                    val avgT = (tA + tB) / 2.0
                    tempSlices.add(TimeWeightedSlice(avgT, dt))
                    weightedTempSum += avgT * dt
                    totalTempDurationSec += dt

                    when {
                        avgT < 15.0 -> lowTempDurationSec += dt
                        avgT in 15.0..35.0 -> roomTempDurationSec += dt
                        else -> highTempDurationSec += dt
                    }
                }
            }
        }

        val avgTemp = if (totalTempDurationSec > 0.0) {
            weightedTempSum / totalTempDurationSec
        } else {
            val validTemps = sortedSamples.mapNotNull { it.temperatureC }
            if (validTemps.isNotEmpty()) validTemps.average() else null
        }

        val tempP10 = calculateDurationWeightedPercentile(tempSlices, 10.0)
        val tempP50 = calculateDurationWeightedPercentile(tempSlices, 50.0)
        val tempP90 = calculateDurationWeightedPercentile(tempSlices, 90.0)
        val tempTimeIntegral = if (totalTempDurationSec > 0.0) weightedTempSum else null

        val lowRatio = if (totalTempDurationSec > 0.0) lowTempDurationSec / totalTempDurationSec else 0.0
        val roomRatio = if (totalTempDurationSec > 0.0) roomTempDurationSec / totalTempDurationSec else 0.0
        val highRatio = if (totalTempDurationSec > 0.0) highTempDurationSec / totalTempDurationSec else 0.0
        val dominantTempRatio = maxOf(lowRatio, roomRatio, highRatio)

        val thermalBand = when {
            avgTemp == null -> DischargeThermalBand.UNKNOWN
            avgTemp < 15.0 -> DischargeThermalBand.LOW_TEMP
            avgTemp in 15.0..35.0 -> DischargeThermalBand.ROOM_TEMP
            else -> DischargeThermalBand.HIGH_TEMP
        }

        val thermalProfile = when {
            totalTempDurationSec <= 0.0 -> ThermalProfileType.UNKNOWN
            lowRatio >= PURE_PROFILE_RATIO_THRESHOLD || roomRatio >= PURE_PROFILE_RATIO_THRESHOLD || highRatio >= PURE_PROFILE_RATIO_THRESHOLD -> ThermalProfileType.PURE
            else -> ThermalProfileType.MIXED
        }

        val tempCoverageRatio = if (sessionTotalDurationSec > 0) {
            (totalTempDurationSec / sessionTotalDurationSec.toDouble()).coerceIn(0.0, 1.0)
        } else 1.0

        // 2. 负载电流特征计算（时间加权分位数、各负载区间时间占比、PURE/MIXED 剖面）
        val curSlices = mutableListOf<TimeWeightedSlice>()
        var weightedCurSum = 0.0
        var totalCurDurationSec = 0.0
        var lightLoadDurationSec = 0.0
        var moderateLoadDurationSec = 0.0
        var heavyLoadDurationSec = 0.0
        var rawPeakCurMa: Double? = null

        for (i in 0 until sortedSamples.size - 1) {
            val a = sortedSamples[i]
            val b = sortedSamples[i + 1]
            val dt = (b.timestampMs - a.timestampMs) / 1000.0
            if (dt in 0.1..MAX_TRAPEZOID_STEP_SEC) {
                val iA = a.currentAmp?.takeIf { it > 0.0 }
                val iB = b.currentAmp?.takeIf { it > 0.0 }
                if (iA != null && iB != null) {
                    val avgI = ((iA + iB) / 2.0) * 1000.0
                    curSlices.add(TimeWeightedSlice(avgI, dt))
                    weightedCurSum += avgI * dt
                    totalCurDurationSec += dt

                    when {
                        avgI < 500.0 -> lightLoadDurationSec += dt
                        avgI in 500.0..1500.0 -> moderateLoadDurationSec += dt
                        else -> heavyLoadDurationSec += dt
                    }
                }
            }
            val curNow = a.currentAmp?.takeIf { it > 0.0 }?.times(1000.0)
            if (curNow != null) {
                rawPeakCurMa = max(rawPeakCurMa ?: 0.0, curNow)
            }
        }
        val lastCur = sortedSamples.last().currentAmp?.takeIf { it > 0.0 }?.times(1000.0)
        if (lastCur != null) {
            rawPeakCurMa = max(rawPeakCurMa ?: 0.0, lastCur)
        }

        val avgCurMa = if (totalCurDurationSec > 0.0) {
            weightedCurSum / totalCurDurationSec
        } else {
            val validCur = sortedSamples.mapNotNull { it.currentAmp?.takeIf { amp -> amp > 0.0 }?.times(1000.0) }
            if (validCur.isNotEmpty()) validCur.average() else null
        }

        val curP50Ma = calculateDurationWeightedPercentile(curSlices, 50.0)
        val curP95Ma = calculateDurationWeightedPercentile(curSlices, 95.0)

        val lightRatio = if (totalCurDurationSec > 0.0) lightLoadDurationSec / totalCurDurationSec else 0.0
        val moderateRatio = if (totalCurDurationSec > 0.0) moderateLoadDurationSec / totalCurDurationSec else 0.0
        val heavyRatio = if (totalCurDurationSec > 0.0) heavyLoadDurationSec / totalCurDurationSec else 0.0
        val dominantLoadRatio = maxOf(lightRatio, moderateRatio, heavyRatio)

        val loadBand = when {
            avgCurMa == null -> DischargeLoadBand.UNKNOWN
            avgCurMa < 500.0 -> DischargeLoadBand.LIGHT
            avgCurMa in 500.0..1500.0 -> DischargeLoadBand.MODERATE
            else -> DischargeLoadBand.HEAVY
        }

        val loadProfile = when {
            totalCurDurationSec <= 0.0 -> LoadProfileType.UNKNOWN
            lightRatio >= PURE_PROFILE_RATIO_THRESHOLD || moderateRatio >= PURE_PROFILE_RATIO_THRESHOLD || heavyRatio >= PURE_PROFILE_RATIO_THRESHOLD -> LoadProfileType.PURE
            else -> LoadProfileType.MIXED
        }

        // 3. 物理功率特征计算（瞬时 V×I 积分）
        val powerSlices = mutableListOf<TimeWeightedSlice>()
        var weightedPowerSum = 0.0
        var totalPowerDurationSec = 0.0
        var rawPeakPowerW: Double? = null

        for (i in 0 until sortedSamples.size - 1) {
            val a = sortedSamples[i]
            val b = sortedSamples[i + 1]
            val dt = (b.timestampMs - a.timestampMs) / 1000.0
            if (dt in 0.1..MAX_TRAPEZOID_STEP_SEC) {
                val pA = if (a.currentAmp != null && a.voltageVolt != null && a.currentAmp > 0.0 && a.voltageVolt > 0.0) a.currentAmp * a.voltageVolt else null
                val pB = if (b.currentAmp != null && b.voltageVolt != null && b.currentAmp > 0.0 && b.voltageVolt > 0.0) b.currentAmp * b.voltageVolt else null
                if (pA != null && pB != null) {
                    val avgP = (pA + pB) / 2.0
                    powerSlices.add(TimeWeightedSlice(avgP, dt))
                    weightedPowerSum += avgP * dt
                    totalPowerDurationSec += dt
                }
            }
            val pNow = if (a.currentAmp != null && a.voltageVolt != null && a.currentAmp > 0.0 && a.voltageVolt > 0.0) a.currentAmp * a.voltageVolt else null
            if (pNow != null) {
                rawPeakPowerW = max(rawPeakPowerW ?: 0.0, pNow)
            }
        }
        val lastP = if (sortedSamples.last().currentAmp != null && sortedSamples.last().voltageVolt != null && sortedSamples.last().currentAmp!! > 0.0 && sortedSamples.last().voltageVolt!! > 0.0) {
            sortedSamples.last().currentAmp!! * sortedSamples.last().voltageVolt!!
        } else null
        if (lastP != null) {
            rawPeakPowerW = max(rawPeakPowerW ?: 0.0, lastP)
        }

        val avgPowerW = if (totalPowerDurationSec > 0.0) {
            weightedPowerSum / totalPowerDurationSec
        } else null

        val powerP50W = calculateDurationWeightedPercentile(powerSlices, 50.0)
        val powerP95W = calculateDurationWeightedPercentile(powerSlices, 95.0)

        // 4. Voltage Sag 与动态表观电阻计算（两层结构）
        val validVolts = sortedSamples.mapNotNull { it.voltageVolt?.takeIf { v -> v > 2.0 } }
        val globalDropMv = if (validVolts.size >= 2) {
            val maxV = validVolts.maxOrNull() ?: 0.0
            val minV = validVolts.minOrNull() ?: 0.0
            max(0.0, (maxV - minV) * 1000.0)
        } else null

        // 寻找最清晰的单步电流突增阶跃以估算表观内阻
        var bestStepDeltaI = 0.0
        var bestStepDeltaV = 0.0
        var bestStepDurationMs = 0L

        for (i in 0 until sortedSamples.size - 1) {
            val a = sortedSamples[i]
            val b = sortedSamples[i + 1]
            val dtSec = (b.timestampMs - a.timestampMs) / 1000.0
            if (dtSec in 0.1..MAX_STEP_RESPONSE_WINDOW_SEC) {
                val iA = (a.currentAmp?.takeIf { it > 0.0 } ?: 0.0) * 1000.0
                val iB = (b.currentAmp?.takeIf { it > 0.0 } ?: 0.0) * 1000.0
                val deltaI = iB - iA
                val vA = (a.voltageVolt?.takeIf { it > 2.0 } ?: 0.0) * 1000.0
                val vB = (b.voltageVolt?.takeIf { it > 2.0 } ?: 0.0) * 1000.0
                val deltaV = vA - vB // 放电电流增大时端电压下降为正落差

                if (deltaI >= MIN_CURRENT_STEP_FOR_SAG_MA && deltaV > 0.0) {
                    if (deltaI > bestStepDeltaI) {
                        bestStepDeltaI = deltaI
                        bestStepDeltaV = deltaV
                        bestStepDurationMs = b.timestampMs - a.timestampMs
                    }
                }
            }
        }

        val apparentResistanceMilliOhm = if (bestStepDeltaI >= MIN_CURRENT_STEP_FOR_SAG_MA && bestStepDeltaV > 0.0) {
            1000.0 * (bestStepDeltaV / bestStepDeltaI)
        } else null

        val sagMetrics = VoltageSagMetrics(
            observedVoltageDropMv = globalDropMv,
            deltaCurrentMa = if (bestStepDeltaI > 0.0) bestStepDeltaI else null,
            eventDurationMs = if (bestStepDurationMs > 0L) bestStepDurationMs else null,
            apparentResistanceMilliOhm = apparentResistanceMilliOhm,
            measurementQuality = if (apparentResistanceMilliOhm != null) QualityLevel.GOOD else QualityLevel.FAIR
        )

        return DischargeOperatingCondition(
            startTemperatureC = startTemp,
            endTemperatureC = endTemp,
            averageTemperatureC = avgTemp,
            medianTemperatureC = tempP50,
            temperatureP10C = tempP10,
            temperatureP50C = tempP50,
            temperatureP90C = tempP90,
            temperatureTimeIntegralCs = tempTimeIntegral,
            temperatureValidDurationSeconds = totalTempDurationSec.toLong(),
            temperatureCoverageRatio = tempCoverageRatio,
            thermalBand = thermalBand,
            lowTempDurationRatio = lowRatio,
            roomTempDurationRatio = roomRatio,
            highTempDurationRatio = highRatio,
            dominantThermalBandRatio = dominantTempRatio,
            thermalProfile = thermalProfile,

            averageDischargeCurrentMa = avgCurMa,
            medianDischargeCurrentMa = curP50Ma,
            currentP50Ma = curP50Ma,
            p95DischargeCurrentMa = curP95Ma,
            peakDischargeCurrentMa = rawPeakCurMa,
            lightLoadDurationRatio = lightRatio,
            moderateLoadDurationRatio = moderateRatio,
            heavyLoadDurationRatio = heavyRatio,
            dominantLoadBandRatio = dominantLoadRatio,
            loadProfile = loadProfile,
            loadBand = loadBand,

            averageDischargePowerW = avgPowerW,
            medianDischargePowerW = powerP50W,
            p95DischargePowerW = powerP95W,
            peakDischargePowerW = rawPeakPowerW,

            voltageSagMv = globalDropMv,
            voltageSagMetrics = sagMetrics,
            dischargeDurationSeconds = sessionTotalDurationSec
        )
    }

    /**
     * 采用与能量积分完全对齐的时间掩码区间（Consistent Interval Mask）执行工况特征分析。
     *
     * 遵循评审规范：
     * 杜绝使用未被能量积分采纳的缺口期间未知负载去解释被截断的能量结果。
     * 建立工况与能量敏感度关系时，两者的积分统计区间必须 100% 相同。
     *
     * @param samples 原始采样点列表
     * @param validIntervals 能量通道判决有效的物理时间闭区间集合（单位：毫秒）
     * @return 严格与能量积分区间对齐的工况特征实体 [DischargeOperatingCondition]
     */
    fun analyzeWithEnergyIntervals(
        samples: List<CapacitySample>,
        validIntervals: List<ClosedRange<Long>>
    ): DischargeOperatingCondition {
        if (samples.isEmpty() || validIntervals.isEmpty()) {
            return DischargeOperatingCondition()
        }

        // 仅筛选处于有效能量积分掩码内部的连续采样步
        val sortedSamples = samples.sortedBy { it.timestampMs }
        val alignedSamples = mutableListOf<CapacitySample>()

        for (sample in sortedSamples) {
            val isInMask = validIntervals.any { interval ->
                sample.timestampMs in interval.start..interval.endInclusive
            }
            if (isInMask) {
                alignedSamples.add(sample)
            }
        }

        val baseCondition = analyze(alignedSamples)

        val totalAlignedDurationSec = validIntervals.sumOf { (it.endInclusive - it.start) / 1000L }
        val fullSpanSec = ((sortedSamples.last().timestampMs - sortedSamples.first().timestampMs) / 1000L).coerceAtLeast(1L)
        val alignedRatio = (totalAlignedDurationSec.toDouble() / fullSpanSec.toDouble()).coerceIn(0.0, 1.0)

        return baseCondition.copy(
            energyAlignedDurationSeconds = totalAlignedDurationSec,
            energyAlignedCoverageRatio = alignedRatio
        )
    }

    /**
     * 计算一组物理切片基于持续时长权重的加权分位数（Duration-Weighted Percentiles）。
     *
     * 遵循评审意见：杜绝采样频率不均匀造成的统计偏置，完全基于真实物理时间累积求取。
     *
     * @param slices 时间加权微元切片列表
     * @param percentile 目标百分位数 (0.0 ~ 100.0)
     * @return 时间加权百分位数值，列表为空时返回 null
     */
    fun calculateDurationWeightedPercentile(slices: List<TimeWeightedSlice>, percentile: Double): Double? {
        val validSlices = slices.filter { it.durationSec > 0.0 && it.value.isFinite() }
        if (validSlices.isEmpty()) return null
        if (validSlices.size == 1) return validSlices.first().value

        val totalDuration = validSlices.sumOf { it.durationSec }
        if (totalDuration <= 0.0) return validSlices.first().value

        val sortedSlices = validSlices.sortedBy { it.value }
        val targetWeight = totalDuration * (percentile.coerceIn(0.0, 100.0) / 100.0)

        // 采用统计学标准中点累积权重插值（Midpoint Cumulative Weight Interpolation）
        // 彻底杜绝端点偏置，保证时间对称性与连续性
        var runningWeight = 0.0
        val midPoints = sortedSlices.map { slice ->
            val mid = runningWeight + slice.durationSec * 0.5
            runningWeight += slice.durationSec
            mid
        }

        if (targetWeight <= midPoints.first()) {
            return sortedSlices.first().value
        }
        if (targetWeight >= midPoints.last()) {
            return sortedSlices.last().value
        }

        for (i in 0 until midPoints.size - 1) {
            val wA = midPoints[i]
            val wB = midPoints[i + 1]
            if (targetWeight in wA..wB) {
                val span = wB - wA
                if (span <= 0.0) return sortedSlices[i].value
                val fraction = (targetWeight - wA) / span
                return sortedSlices[i].value + fraction * (sortedSlices[i + 1].value - sortedSlices[i].value)
            }
        }

        return sortedSlices.last().value
    }

    /**
     * 计算一组数值列表的指定百分位数（常规分位数计算）。
     *
     * @param values 待计算数值列表
     * @param percentile 目标百分位数 (0.0 ~ 100.0)
     * @return 对应的百分位数值，列表为空时返回 null
     */
    fun calculatePercentile(values: List<Double>, percentile: Double): Double? {
        val validValues = values.filter { it.isFinite() }
        if (validValues.isEmpty()) return null
        if (validValues.size == 1) return validValues.first()

        val sorted = validValues.sorted()
        val p = percentile.coerceIn(0.0, 100.0) / 100.0
        val rank = p * (sorted.size - 1)
        val lowerIndex = rank.toInt()
        val upperIndex = min(lowerIndex + 1, sorted.size - 1)
        val fraction = rank - lowerIndex

        return sorted[lowerIndex] + fraction * (sorted[upperIndex] - sorted[lowerIndex])
    }
}
