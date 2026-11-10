package com.battery.analysis.domain.estimator

import com.battery.analysis.domain.estimator.model.LearnedSocEnergyCurve
import com.battery.analysis.domain.estimator.model.PowerSample
import com.battery.analysis.domain.estimator.model.ScreenPowerState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 放电端电压与电流梯形微积分分配引擎（DischargeEnergyIntegrator）。
 *
 * 核心电化学与物理微积分原则：
 * 1. 纯亮屏第一性原理：放电能量模型仅吸收高信噪比纯亮屏物理做功（isScreenOn == true），
 *    彻底隔离充电与息屏待机状态，坚决杜绝数十小时待机微电流底噪累加造成的满充外推失真；
 * 2. 梯形积分做功公式：ΔE = ((P1 + P2) / 2) * (Δt / 3600)，单位为瓦时（Wh）；
 * 3. 亮屏采样连续性审计：当纯亮屏连续采样点时间差超过门限（180 秒）时，判定亮屏采样轮询中断或系统卡顿，
 *    严禁将大跨度中断按首尾粗暴积分，忠实截断微积分，并将该时段跨越的 SOC 区间显式标记为未观测区间（Unobserved）；
 * 4. 边界时刻时间插值分段积分：当离散采样点跨越 5% 整数边界时，首先求解跨越边界时刻的时间戳 tb，
 *    并对边界时刻的瞬时功率 Pb 实施时间线性插值，边界前后分别执行梯形数值微积分，确保能量严格守恒且吻合实际功率演进。
 */
object DischargeEnergyIntegrator {

    /** 连续时序微积分单微元最大有效时间跨度门限（单位：毫秒，默认 180 秒，超过此门限判定为纯亮屏工况超时断层） */
    const val MAX_INTEGRATION_INTERVAL_MS: Long = 180_000L

    /** 纯亮屏最低可信物理放电功率门限（单位：瓦特 W）。低于此门限判定为熄屏休眠待机底噪伪装，坚决隔离 */
    const val MIN_SCREEN_ON_PHYSICAL_WATTS: Float = 0.40f

    /** 最小有效时间间隔（单位：毫秒，防止并发打点微小除零） */
    const val MIN_INTEGRATION_INTERVAL_MS: Long = 50L

    /**
     * 单个 5% SOC 区间在一次放电积分中累加的微元做功结果。
     *
     * @property binIndex 5% 区间索引号（0..19）
     * @property energyWh 本次放电切片在该区间释放的物理能量（瓦时 Wh）
     * @property durationMs 本次放电切片在该区间累计的有效时长（毫秒）
     * @property startSocPercent 本次切片在该区间的起始电量百分比
     * @property endSocPercent 本次切片在该区间的结束电量百分比
     * @property coveredSocSpan 该切片在该区间实际走过的电量跨度绝对值
     * @property averageCurrentAmperes 该切片在区间的加权平均放电电流（安培 A，可选）
     * @property averageTemperatureCelsius 该切片在区间的加权平均温度（摄氏度 ℃，可选）
     * @property chargeMah 本次切片在该区间释放的物理电荷量（毫安时 mAh）
     * @property measuredAvgVoltageVolts 本次切片在区间的加权平均工作电压（伏特 V，可选）
     */
    data class BinEnergyDelta(
        val binIndex: Int,
        val energyWh: Double,
        val durationMs: Long,
        val startSocPercent: Float,
        val endSocPercent: Float,
        val coveredSocSpan: Float = abs(startSocPercent - endSocPercent),
        val averageCurrentAmperes: Float? = null,
        val averageTemperatureCelsius: Float? = null,
        val chargeMah: Double = 0.0,
        val measuredAvgVoltageVolts: Float? = null
    )

    /**
     * 因采样中断或超长息屏深睡而未获得有效连续微积分采样的 SOC 电量缺损区间。
     *
     * @property startSocPercent 缺损区间的起始电量
     * @property endSocPercent 缺损区间的结束电量
     * @property gapDurationMs 中断断层的持续时间（毫秒）
     */
    data class UnobservedSocRange(
        val startSocPercent: Float,
        val endSocPercent: Float,
        val gapDurationMs: Long
    )

    /**
     * 完整放电采样序列的微积分与质量审计聚合结果实体。
     *
     * @property binDeltas 各 5% SOC 区间实测累加的做功微元映射表
     * @property unobservedRanges 本次会话期间记录的深睡超长采样断层缺损区间列表
     * @property totalValidDurationMs 本次会话累计的有效积分总时长（毫秒）
     * @property totalIntegratedEnergyWh 本次会话实测释放的纯亮屏做功总能量（瓦时 Wh）
     * @property totalIntegratedChargeMah 本次会话实测释放的纯亮屏做功总电荷量（毫安时 mAh）
     * @property screenOnEnergyWh 纯亮屏实测总做功（Wh）
     * @property screenOnChargeMah 纯亮屏实测总电荷量（mAh）
     * @property screenOffEnergyWh 息屏待机实测总耗能（Wh）
     * @property screenOffChargeMah 息屏待机实测总耗电荷（mAh）
     * @property totalSessionEnergyWh 放电周期整机实测释放总能量（亮屏 + 息屏，Wh）
     * @property totalSessionChargeMah 放电周期整机实测释放总电荷量（亮屏 + 息屏，mAh，用于电荷守恒无损分母分配）
     */
    data class DischargeIntegrationResult(
        val binDeltas: Map<Int, BinEnergyDelta>,
        val unobservedRanges: List<UnobservedSocRange>,
        val totalValidDurationMs: Long,
        val totalIntegratedEnergyWh: Double,
        val totalIntegratedChargeMah: Double = 0.0,
        val screenOnEnergyWh: Double = totalIntegratedEnergyWh,
        val screenOnChargeMah: Double = totalIntegratedChargeMah,
        val screenOffEnergyWh: Double = 0.0,
        val screenOffChargeMah: Double = 0.0,
        val totalSessionEnergyWh: Double = totalIntegratedEnergyWh + screenOffEnergyWh,
        val totalSessionChargeMah: Double = totalIntegratedChargeMah + screenOffChargeMah
    )

    /**
     * 对连续的时序放电物理采样点列表执行梯形微积分，输出包含各 Bin 微元与深睡未观测区间的完整会话审计结果。
     * 遵循电荷与能量守恒定律：无损累计纯亮屏电荷与息屏待机电荷，为纯亮屏掉电分母提供绝对守恒基准。
     *
     * @param samples 按时间升序排列的连续物理采样点列表 [List<PowerSample>]
     * @return 详尽积分与未观测缺损审计结果实体 [DischargeIntegrationResult]
     */
    fun integrateDischargeSession(samples: List<PowerSample>): DischargeIntegrationResult {
        if (samples.size < 2) {
            return DischargeIntegrationResult(emptyMap(), emptyList(), 0L, 0.0)
        }

        val binEnergyAcc = DoubleArray(LearnedSocEnergyCurve.BIN_COUNT)
        val binChargeAcc = DoubleArray(LearnedSocEnergyCurve.BIN_COUNT)
        val binDurationAcc = LongArray(LearnedSocEnergyCurve.BIN_COUNT)
        val binMinSoc = FloatArray(LearnedSocEnergyCurve.BIN_COUNT) { 100.0f }
        val binMaxSoc = FloatArray(LearnedSocEnergyCurve.BIN_COUNT) { 0.0f }
        val binScreenOnDropAcc = FloatArray(LearnedSocEnergyCurve.BIN_COUNT)
        val binCurrentWeightSum = DoubleArray(LearnedSocEnergyCurve.BIN_COUNT)
        val binTempWeightSum = DoubleArray(LearnedSocEnergyCurve.BIN_COUNT)
        val binTempDurationAcc = LongArray(LearnedSocEnergyCurve.BIN_COUNT)

        val unobservedList = mutableListOf<UnobservedSocRange>()
        var totalValidDuration = 0L
        var screenOnWh = 0.0
        var screenOnMah = 0.0
        var screenOffWh = 0.0
        var screenOffMah = 0.0

        for (i in 0 until samples.size - 1) {
            val p1 = samples[i]
            val p2 = samples[i + 1]

            val dtMs = p2.timestampMs - p1.timestampMs
            if (dtMs < MIN_INTEGRATION_INTERVAL_MS || dtMs > 300_000L) {
                // 采样间隔低于最小阈值或经历超过 5 分钟的超长深睡断层，跳过
                continue
            }

            val rawWatts1 = if (p1.powerWatts > 0f) p1.powerWatts else (if (p1.voltageVolts > 0f && (p1.currentAmperes ?: 0f) > 0f) p1.voltageVolts * p1.currentAmperes!! else 0f)
            val rawWatts2 = if (p2.powerWatts > 0f) p2.powerWatts else (if (p2.voltageVolts > 0f && (p2.currentAmperes ?: 0f) > 0f) p2.voltageVolts * p2.currentAmperes!! else 0f)
            val avgPhysicalWatts = (rawWatts1 + rawWatts2) / 2.0

            val current1 = p1.currentAmperes?.let { max(0.0f, it) } ?: (if (p1.voltageVolts > 0f) rawWatts1 / p1.voltageVolts else 0.0f)
            val current2 = p2.currentAmperes?.let { max(0.0f, it) } ?: (if (p2.voltageVolts > 0f) rawWatts2 / p2.voltageVolts else 0.0f)
            val avgCurrentAmperes = (current1 + current2) / 2.0

            val segEnergyWh = avgPhysicalWatts * (dtMs.toDouble() / 3600000.0)
            val segChargeMah = avgCurrentAmperes * (dtMs.toDouble() / 3600000.0) * 1000.0

            val isPureScreenOn = (p1.state == ScreenPowerState.SCREEN_ON && p2.state == ScreenPowerState.SCREEN_ON) &&
                    !(rawWatts1 in 0.001f..MIN_SCREEN_ON_PHYSICAL_WATTS || rawWatts2 in 0.001f..MIN_SCREEN_ON_PHYSICAL_WATTS || avgPhysicalWatts in 0.001..MIN_SCREEN_ON_PHYSICAL_WATTS.toDouble())

            if (!isPureScreenOn) {
                // 息屏待机或开灭屏过渡切片：计入息屏电荷与能量，保障整机物理电荷严格守恒
                if (segEnergyWh > 0.0 && segEnergyWh.isFinite()) {
                    screenOffWh += segEnergyWh
                }
                if (segChargeMah > 0.0 && segChargeMah.isFinite()) {
                    screenOffMah += segChargeMah
                }
                continue
            }

            // 亮屏失联断层审计：
            val socDrop = p1.batteryLevelPercent - p2.batteryLevelPercent
            val isAbnormalJump = (dtMs > 60_000L && socDrop >= 1.0f) || (dtMs > 120_000L)
            if (isAbnormalJump) {
                unobservedList.add(
                    UnobservedSocRange(
                        startSocPercent = p1.batteryLevelPercent,
                        endSocPercent = p2.batteryLevelPercent,
                        gapDurationMs = dtMs
                    )
                )
                if (segEnergyWh > 0.0 && segEnergyWh.isFinite()) {
                    screenOffWh += segEnergyWh
                }
                if (segChargeMah > 0.0 && segChargeMah.isFinite()) {
                    screenOffMah += segChargeMah
                }
                continue
            }

            // 纯亮屏切片数值微积分并累加到 20 段 Bin
            allocateSegmentWithTimeInterpolation(
                p1 = p1,
                p2 = p2,
                dtMs = dtMs,
                binEnergyAcc = binEnergyAcc,
                binChargeAcc = binChargeAcc,
                binDurationAcc = binDurationAcc,
                binMinSoc = binMinSoc,
                binMaxSoc = binMaxSoc,
                binScreenOnDropAcc = binScreenOnDropAcc,
                binCurrentWeightSum = binCurrentWeightSum,
                binTempWeightSum = binTempWeightSum,
                binTempDurationAcc = binTempDurationAcc
            )

            totalValidDuration += dtMs
            if (segEnergyWh > 0.0 && segEnergyWh.isFinite()) {
                screenOnWh += segEnergyWh
            }
            if (segChargeMah > 0.0 && segChargeMah.isFinite()) {
                screenOnMah += segChargeMah
            }
        }

        val resultMap = mutableMapOf<Int, BinEnergyDelta>()
        for (index in 0 until LearnedSocEnergyCurve.BIN_COUNT) {
            val energy = binEnergyAcc[index]
            val charge = binChargeAcc[index]
            val duration = binDurationAcc[index]
            if (energy > 0.0 && duration > 0L) {
                val avgCurrent = if (binCurrentWeightSum[index] > 0.0) {
                    (binCurrentWeightSum[index] / duration.toDouble()).toFloat()
                } else null
                val avgTemp = if (binTempDurationAcc[index] > 0L) {
                    (binTempWeightSum[index] / binTempDurationAcc[index].toDouble()).toFloat()
                } else null
                val avgVoltage = if (charge > 0.0) ((energy * 1000.0) / charge).toFloat() else null

                val rawSpan = abs(binMaxSoc[index] - binMinSoc[index])
                val screenOnDrop = binScreenOnDropAcc[index]
                // 纯亮屏第一性原理：分母优先采用微积分过程中真正连续发生且测得做功的亮屏掉电跨度，
                // 彻底隔离中间熄屏待机掉电对分母的拉扯（杜绝 0.642Wh 与 2.205Wh 锯齿断崖）
                val effectiveSpan = if (screenOnDrop > 0.001f) screenOnDrop else rawSpan

                resultMap[index] = BinEnergyDelta(
                    binIndex = index,
                    energyWh = energy,
                    durationMs = duration,
                    startSocPercent = binMaxSoc[index],
                    endSocPercent = binMinSoc[index],
                    coveredSocSpan = effectiveSpan,
                    averageCurrentAmperes = avgCurrent,
                    averageTemperatureCelsius = avgTemp,
                    chargeMah = charge,
                    measuredAvgVoltageVolts = avgVoltage
                )
            }
        }

        return DischargeIntegrationResult(
            binDeltas = resultMap,
            unobservedRanges = unobservedList,
            totalValidDurationMs = totalValidDuration,
            totalIntegratedEnergyWh = screenOnWh,
            totalIntegratedChargeMah = screenOnMah,
            screenOnEnergyWh = screenOnWh,
            screenOnChargeMah = screenOnMah,
            screenOffEnergyWh = screenOffWh,
            screenOffChargeMah = screenOffMah,
            totalSessionEnergyWh = screenOnWh + screenOffWh,
            totalSessionChargeMah = screenOnMah + screenOffMah
        )
    }

    /**
     * 兼容性便捷方法：对连续采样点列表执行积分并返回区间微元映射表。
     *
     * @param samples 连续物理采样点列表 [List<PowerSample>]
     * @return 各区间做功微元映射表 [Map<Int, BinEnergyDelta>]
     */
    fun integrateDischargeSamples(samples: List<PowerSample>): Map<Int, BinEnergyDelta> {
        return integrateDischargeSession(samples).binDeltas
    }

    /**
     * 对两采样点之间的物理放电段实施边界时刻时间插值分段数值积分。
     *
     * @param p1 起始物理采样点
     * @param p2 结束物理采样点
     * @param dtMs 两点间的时间差毫秒数
     * @param binEnergyAcc 能量累加器
     * @param binChargeAcc 电荷量累加器（毫安时 mAh）
     * @param binDurationAcc 时长累加器
     * @param binMinSoc 最小电量记录器
     * @param binMaxSoc 最大电量记录器
     * @param binScreenOnDropAcc 纯亮屏实际掉电量累加器
     * @param binCurrentWeightSum 电流时长加权累加器
     * @param binTempWeightSum 温度时长加权累加器
     * @param binTempDurationAcc 温度有效时长累加器
     */
    private fun allocateSegmentWithTimeInterpolation(
        p1: PowerSample,
        p2: PowerSample,
        dtMs: Long,
        binEnergyAcc: DoubleArray,
        binChargeAcc: DoubleArray,
        binDurationAcc: LongArray,
        binMinSoc: FloatArray,
        binMaxSoc: FloatArray,
        binScreenOnDropAcc: FloatArray,
        binCurrentWeightSum: DoubleArray,
        binTempWeightSum: DoubleArray,
        binTempDurationAcc: LongArray
    ) {
        val s1 = p1.batteryLevelPercent.coerceIn(0.0f, 100.0f)
        val s2 = p2.batteryLevelPercent.coerceIn(0.0f, 100.0f)
        val bin1 = getBinIndexForSoc(s1)
        val bin2 = getBinIndexForSoc(s2)

        // 纯亮屏第一性原理：忠实采用实测物理瞬时功率，零假数据保底，零人为待机功率钳位
        val power1 = max(0.0f, p1.powerWatts)
        val power2 = max(0.0f, p2.powerWatts)

        // 严格遵循物理欧姆与电荷守恒定律：若未上报瞬时电流但具备有效电压与功率，忠实推导物理电流 I = P / U（安培 A）
        val current1 = p1.currentAmperes?.let { max(0.0f, it) } ?: (if (p1.voltageVolts > 0f) power1 / p1.voltageVolts else 0.0f)
        val current2 = p2.currentAmperes?.let { max(0.0f, it) } ?: (if (p2.voltageVolts > 0f) power2 / p2.voltageVolts else 0.0f)

        val temp1 = p1.temperatureCelsius
        val temp2 = p2.temperatureCelsius

        // 场景 1：两点位于同一个 5% Bin 内部，无需边界切分
        if (bin1 == bin2 || abs(s1 - s2) < 0.001f) {
            val targetBin = bin1
            val avgPower = (power1 + power2) / 2.0
            val avgCurrent = (current1 + current2) / 2.0
            val deltaWh = avgPower * (dtMs.toDouble() / 3600000.0)
            val deltaChargeMah = avgCurrent * (dtMs.toDouble() / 3600000.0) * 1000.0
            if (deltaWh > 0.0 && deltaWh.isFinite()) {
                binEnergyAcc[targetBin] += deltaWh
                if (deltaChargeMah > 0.0 && deltaChargeMah.isFinite()) {
                    binChargeAcc[targetBin] += deltaChargeMah
                }
                binDurationAcc[targetBin] += dtMs
                binMinSoc[targetBin] = min(binMinSoc[targetBin], min(s1, s2))
                binMaxSoc[targetBin] = max(binMaxSoc[targetBin], max(s1, s2))
                if (s1 > s2) {
                    binScreenOnDropAcc[targetBin] += (s1 - s2)
                }
                binCurrentWeightSum[targetBin] += avgCurrent * dtMs
                if (temp1 != null && temp2 != null) {
                    binTempWeightSum[targetBin] += ((temp1 + temp2) / 2.0) * dtMs
                    binTempDurationAcc[targetBin] += dtMs
                } else if (temp1 != null) {
                    binTempWeightSum[targetBin] += temp1.toDouble() * dtMs
                    binTempDurationAcc[targetBin] += dtMs
                } else if (temp2 != null) {
                    binTempWeightSum[targetBin] += temp2.toDouble() * dtMs
                    binTempDurationAcc[targetBin] += dtMs
                }
            }
            return
        }

        // 场景 2：跨越 5% 边界，按时间插值切分
        // 收集介于 s1 与 s2 之间的所有 5% 边界阈值（按时间演进顺序排列）
        val boundaries = mutableListOf<Float>()
        val minS = min(s1, s2)
        val maxS = max(s1, s2)
        for (i in 1 until LearnedSocEnergyCurve.BIN_COUNT) {
            val boundary = i * LearnedSocEnergyCurve.BIN_SOC_STEP
            if (boundary > minS && boundary < maxS) {
                boundaries.add(boundary)
            }
        }
        if (s1 > s2) {
            boundaries.sortByDescending { it }
        } else {
            boundaries.sortBy { it }
        }

        val totalSocDelta = abs(s2 - s1)
        var cursorTimeFraction = 0.0
        var cursorSoc = s1
        var cursorPower = power1
        var cursorCurrent = current1
        var cursorTemp = temp1

        for (b in boundaries) {
            val stepFraction = (abs(b - cursorSoc) / totalSocDelta).toDouble().coerceIn(0.0, 1.0)
            val nextTimeFraction = (cursorTimeFraction + stepFraction).coerceIn(0.0, 1.0)
            val subDtMs = ((nextTimeFraction - cursorTimeFraction) * dtMs).toLong()

            if (subDtMs > 0L) {
                val boundaryPower = power1 + (power2 - power1) * nextTimeFraction.toFloat()
                val boundaryCurrent = current1 + (current2 - current1) * nextTimeFraction.toFloat()
                val boundaryTemp = if (temp1 != null && temp2 != null) {
                    temp1 + (temp2 - temp1) * nextTimeFraction.toFloat()
                } else {
                    temp1 ?: temp2
                }

                val midSoc = (cursorSoc + b) / 2.0f
                val subBin = getBinIndexForSoc(midSoc)
                val subAvgPower = max(0.0, ((cursorPower + boundaryPower) / 2.0).toDouble())
                val subAvgCurrent = max(0.0, ((cursorCurrent + boundaryCurrent) / 2.0).toDouble())
                val subWh = subAvgPower * (subDtMs.toDouble() / 3600000.0)
                val subChargeMah = subAvgCurrent * (subDtMs.toDouble() / 3600000.0) * 1000.0

                if (subWh > 0.0 && subWh.isFinite()) {
                    binEnergyAcc[subBin] += subWh
                    if (subChargeMah > 0.0 && subChargeMah.isFinite()) {
                        binChargeAcc[subBin] += subChargeMah
                    }
                    binDurationAcc[subBin] += subDtMs
                    binMinSoc[subBin] = min(binMinSoc[subBin], min(cursorSoc, b))
                    binMaxSoc[subBin] = max(binMaxSoc[subBin], max(cursorSoc, b))
                    val subDrop = abs(cursorSoc - b)
                    if (subDrop > 0f) {
                        binScreenOnDropAcc[subBin] += subDrop
                    }
                    binCurrentWeightSum[subBin] += subAvgCurrent * subDtMs
                    if (cursorTemp != null && boundaryTemp != null) {
                        binTempWeightSum[subBin] += ((cursorTemp + boundaryTemp) / 2.0) * subDtMs
                        binTempDurationAcc[subBin] += subDtMs
                    } else if (cursorTemp != null) {
                        binTempWeightSum[subBin] += cursorTemp.toDouble() * subDtMs
                        binTempDurationAcc[subBin] += subDtMs
                    } else if (boundaryTemp != null) {
                        binTempWeightSum[subBin] += boundaryTemp.toDouble() * subDtMs
                        binTempDurationAcc[subBin] += subDtMs
                    }
                }

                cursorTimeFraction = nextTimeFraction
                cursorSoc = b
                cursorPower = boundaryPower
                cursorCurrent = boundaryCurrent
                cursorTemp = boundaryTemp
            }
        }

        // 最终尾段
        val tailDtMs = dtMs - (cursorTimeFraction * dtMs).toLong()
        if (tailDtMs > 0L) {
            val midSoc = (cursorSoc + s2) / 2.0f
            val tailBin = getBinIndexForSoc(midSoc)
            val tailAvgPower = max(0.0, ((cursorPower + power2) / 2.0).toDouble())
            val tailAvgCurrent = max(0.0, ((cursorCurrent + current2) / 2.0).toDouble())
            val tailWh = tailAvgPower * (tailDtMs.toDouble() / 3600000.0)
            val tailChargeMah = tailAvgCurrent * (tailDtMs.toDouble() / 3600000.0) * 1000.0

            if (tailWh > 0.0 && tailWh.isFinite()) {
                binEnergyAcc[tailBin] += tailWh
                if (tailChargeMah > 0.0 && tailChargeMah.isFinite()) {
                    binChargeAcc[tailBin] += tailChargeMah
                }
                binDurationAcc[tailBin] += tailDtMs
                binMinSoc[tailBin] = min(binMinSoc[tailBin], min(cursorSoc, s2))
                binMaxSoc[tailBin] = max(binMaxSoc[tailBin], max(cursorSoc, s2))
                val tailDrop = abs(cursorSoc - s2)
                if (tailDrop > 0f) {
                    binScreenOnDropAcc[tailBin] += tailDrop
                }
                binCurrentWeightSum[tailBin] += tailAvgCurrent * tailDtMs
                if (cursorTemp != null && temp2 != null) {
                    binTempWeightSum[tailBin] += ((cursorTemp + temp2) / 2.0) * tailDtMs
                    binTempDurationAcc[tailBin] += tailDtMs
                } else if (cursorTemp != null) {
                    binTempWeightSum[tailBin] += cursorTemp.toDouble() * tailDtMs
                    binTempDurationAcc[tailBin] += tailDtMs
                } else if (temp2 != null) {
                    binTempWeightSum[tailBin] += temp2.toDouble() * tailDtMs
                    binTempDurationAcc[tailBin] += tailDtMs
                }
            }
        }
    }

    /**
     * 根据给定的 SOC 电量百分比，计算其对应的 5% 区间索引号（0..19）。
     *
     * @param socPercent 电池电量百分比（0.0f ~ 100.0f）
     * @return 对应的区间索引（0 代表 0%~5%，19 代表 95%~100%）
     */
    fun getBinIndexForSoc(socPercent: Float): Int {
        val clamped = socPercent.coerceIn(0.0f, 100.0f)
        if (clamped >= 100.0f) return LearnedSocEnergyCurve.BIN_COUNT - 1
        val index = (clamped / LearnedSocEnergyCurve.BIN_SOC_STEP).toInt()
        return index.coerceIn(0, LearnedSocEnergyCurve.BIN_COUNT - 1)
    }
}
