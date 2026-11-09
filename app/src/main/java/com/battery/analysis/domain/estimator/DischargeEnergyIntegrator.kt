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
 * 1. 梯形积分做功公式：ΔE = ((P1 + P2) / 2) * (Δt / 3600)，单位为瓦时（Wh）；
 * 2. 息屏深睡采样断层显式标记：当连续采样点时间差超过门限（默认 120 秒）时，判定系统进入长时间休眠挂起，
 *    严禁将大跨度休眠按首尾梯形粗暴积分，忠实截断微积分，并将该时段跨越的 SOC 区间显式标记为未观测区间（Unobserved）；
 * 3. 边界时刻时间插值分段积分：当离散采样点跨越 5% 整数边界时，首先求解跨越边界时刻的时间戳 tb，
 *    并对边界时刻的瞬时功率 Pb 实施时间线性插值，边界前后分别执行梯形数值微积分，确保能量严格守恒且吻合实际功率演进；
 * 4. 充电与异常状态过滤：仅在有效非充电放电状态下执行微积分累加。
 */
object DischargeEnergyIntegrator {

    /** 连续时序微积分单微元最大有效时间跨度门限（单位：毫秒，默认 180 秒，超过此门限判定为纯亮屏工况超时断层） */
    const val MAX_INTEGRATION_INTERVAL_MS: Long = 180_000L

    /** 息屏待机状态下单次被动广播采样最大允许时间跨度门限（单位：毫秒，默认 4 小时，覆盖深度休眠 1% 掉电周期） */
    const val MAX_STANDBY_INTERVAL_MS: Long = 4 * 3600_000L

    /** 息屏待机状态下单次被动广播采样最大允许单步电量变化（单位：%，默认 2.0%，允许系统 1% 广播与防抖边界） */
    const val MAX_STANDBY_STEP_SOC_DELTA: Float = 2.0f

    /** 息屏待机状态下允许的最大待机功耗门限（单位：瓦 W，默认 0.8W，超过此值判定为异常功耗或非纯待机） */
    const val MAX_STANDBY_POWER_WATTS: Float = 0.8f

    /** 待机保底默认功耗（单位：瓦 W，当设备底层在息屏时未上报有效功率读数时保底使用） */
    const val DEFAULT_STANDBY_BASE_WATTS: Float = 0.15f

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
     */
    data class BinEnergyDelta(
        val binIndex: Int,
        val energyWh: Double,
        val durationMs: Long,
        val startSocPercent: Float,
        val endSocPercent: Float,
        val coveredSocSpan: Float = abs(startSocPercent - endSocPercent),
        val averageCurrentAmperes: Float? = null,
        val averageTemperatureCelsius: Float? = null
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
     * @property totalIntegratedEnergyWh 本次会话实测释放的做功总能量（瓦时 Wh）
     */
    data class DischargeIntegrationResult(
        val binDeltas: Map<Int, BinEnergyDelta>,
        val unobservedRanges: List<UnobservedSocRange>,
        val totalValidDurationMs: Long,
        val totalIntegratedEnergyWh: Double
    )

    /**
     * 对连续的时序放电物理采样点列表执行梯形微积分，输出包含各 Bin 微元与深睡未观测区间的完整会话审计结果。
     *
     * @param samples 按时间升序排列的连续物理采样点列表 [List<PowerSample>]
     * @return 详尽积分与未观测缺损审计结果实体 [DischargeIntegrationResult]
     */
    fun integrateDischargeSession(samples: List<PowerSample>): DischargeIntegrationResult {
        if (samples.size < 2) {
            return DischargeIntegrationResult(emptyMap(), emptyList(), 0L, 0.0)
        }

        val binEnergyAcc = DoubleArray(LearnedSocEnergyCurve.BIN_COUNT)
        val binDurationAcc = LongArray(LearnedSocEnergyCurve.BIN_COUNT)
        val binMinSoc = FloatArray(LearnedSocEnergyCurve.BIN_COUNT) { 100.0f }
        val binMaxSoc = FloatArray(LearnedSocEnergyCurve.BIN_COUNT) { 0.0f }
        val binCurrentWeightSum = DoubleArray(LearnedSocEnergyCurve.BIN_COUNT)
        val binTempWeightSum = DoubleArray(LearnedSocEnergyCurve.BIN_COUNT)

        val unobservedList = mutableListOf<UnobservedSocRange>()
        var totalValidDuration = 0L
        var totalIntegratedWh = 0.0

        for (i in 0 until samples.size - 1) {
            val p1 = samples[i]
            val p2 = samples[i + 1]

            // 1. 状态校验：两点必须均为非充电放电状态
            if (p1.state == ScreenPowerState.CHARGING || p2.state == ScreenPowerState.CHARGING) {
                continue
            }

            // 2. 时间跨度校验与深睡长失联断层审计：
            val dtMs = p2.timestampMs - p1.timestampMs
            if (dtMs < MIN_INTEGRATION_INTERVAL_MS) {
                continue
            }

            val isScreenOffInterval = p1.state == ScreenPowerState.SCREEN_OFF || p2.state == ScreenPowerState.SCREEN_OFF
            val socDelta = abs(p1.batteryLevelPercent - p2.batteryLevelPercent)
            val avgPower = ((max(0.0f, p1.powerWatts) + max(0.0f, p2.powerWatts)) / 2.0)

            // 区分真实的息屏被动广播采样与超长关机/异常失联断层：
            // 1) 正常息屏待机广播采样：至少一端为息屏状态，单步电量变化 <= 2.0%（忠实对应 Android ACTION_BATTERY_CHANGED 1% 广播），
            //    且两点时间差在合理待机时间窗内（<= 4 小时），平均功率符合真实硬件待机底噪（<= 2.5W）。
            //    对于正常的息屏待机广播放电，即使采样间隔因深度休眠达到数十分钟，也忠实执行梯形微积分，正常累加为该区间的实测做功！
            // 2) 真实未观测失联断层：
            //    - 纯亮屏期间采样间隔超过 180 秒（说明系统卡顿、服务被杀或亮屏轮询中断）；
            //    - 息屏期间单步电量跨度跳变 > 2.0%（说明手机中间经历长时间关机或服务被彻底杀掉数小时后重启，丢失了中间 1% 广播轨迹）；
            //    - 息屏期间单步时间 > 4 小时（长期关机封存）；
            //    - 功耗异常超标（> 2.5W）。
            val isNormalStandbySampling = isScreenOffInterval &&
                    socDelta <= MAX_STANDBY_STEP_SOC_DELTA &&
                    dtMs <= MAX_STANDBY_INTERVAL_MS &&
                    avgPower <= MAX_STANDBY_POWER_WATTS

            val isUnobservedGap = if (isScreenOffInterval) {
                !isNormalStandbySampling
            } else {
                dtMs > MAX_INTEGRATION_INTERVAL_MS
            }

            if (isUnobservedGap) {
                unobservedList.add(
                    UnobservedSocRange(
                        startSocPercent = p1.batteryLevelPercent,
                        endSocPercent = p2.batteryLevelPercent,
                        gapDurationMs = dtMs
                    )
                )
                continue
            }

            // 3. 执行边界时刻时间插值分段积分并累加（真实放电梯形微积分）
            allocateSegmentWithTimeInterpolation(
                p1 = p1,
                p2 = p2,
                dtMs = dtMs,
                binEnergyAcc = binEnergyAcc,
                binDurationAcc = binDurationAcc,
                binMinSoc = binMinSoc,
                binMaxSoc = binMaxSoc,
                binCurrentWeightSum = binCurrentWeightSum,
                binTempWeightSum = binTempWeightSum
            )

            totalValidDuration += dtMs
        }

        val resultMap = mutableMapOf<Int, BinEnergyDelta>()
        for (index in 0 until LearnedSocEnergyCurve.BIN_COUNT) {
            val energy = binEnergyAcc[index]
            val duration = binDurationAcc[index]
            if (energy > 0.0 && duration > 0L) {
                val avgCurrent = if (binCurrentWeightSum[index] > 0.0) {
                    (binCurrentWeightSum[index] / duration.toDouble()).toFloat()
                } else null
                val avgTemp = if (binTempWeightSum[index] > 0.0) {
                    (binTempWeightSum[index] / duration.toDouble()).toFloat()
                } else null

                resultMap[index] = BinEnergyDelta(
                    binIndex = index,
                    energyWh = energy,
                    durationMs = duration,
                    startSocPercent = binMaxSoc[index],
                    endSocPercent = binMinSoc[index],
                    coveredSocSpan = abs(binMaxSoc[index] - binMinSoc[index]),
                    averageCurrentAmperes = avgCurrent,
                    averageTemperatureCelsius = avgTemp
                )
                totalIntegratedWh += energy
            }
        }

        return DischargeIntegrationResult(
            binDeltas = resultMap,
            unobservedRanges = unobservedList,
            totalValidDurationMs = totalValidDuration,
            totalIntegratedEnergyWh = totalIntegratedWh
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
     * @param binDurationAcc 时长累加器
     * @param binMinSoc 最小电量记录器
     * @param binMaxSoc 最大电量记录器
     * @param binCurrentWeightSum 电流时长加权累加器
     * @param binTempWeightSum 温度时长加权累加器
     */
    private fun allocateSegmentWithTimeInterpolation(
        p1: PowerSample,
        p2: PowerSample,
        dtMs: Long,
        binEnergyAcc: DoubleArray,
        binDurationAcc: LongArray,
        binMinSoc: FloatArray,
        binMaxSoc: FloatArray,
        binCurrentWeightSum: DoubleArray,
        binTempWeightSum: DoubleArray
    ) {
        val s1 = p1.batteryLevelPercent.coerceIn(0.0f, 100.0f)
        val s2 = p2.batteryLevelPercent.coerceIn(0.0f, 100.0f)
        val bin1 = getBinIndexForSoc(s1)
        val bin2 = getBinIndexForSoc(s2)

        val rawPower1 = max(0.0f, p1.powerWatts)
        val rawPower2 = max(0.0f, p2.powerWatts)
        val isScreenOff = p1.state == ScreenPowerState.SCREEN_OFF || p2.state == ScreenPowerState.SCREEN_OFF

        // 息屏长待机切片保护：当处于息屏状态时，待机功率严禁受到灭屏瞬间亮屏大功率（2W~4W）持续污染，
        // 且缺失读数时严禁回退为对侧的亮屏高功率，必须以真实的物理待机功耗上限（MAX_STANDBY_POWER_WATTS = 0.8W）与基准底噪约束
        val power1 = if (isScreenOff) {
            if (p1.state == ScreenPowerState.SCREEN_OFF && dtMs > MAX_INTEGRATION_INTERVAL_MS) {
                if (rawPower1 <= 0.001f) DEFAULT_STANDBY_BASE_WATTS else min(rawPower1, MAX_STANDBY_POWER_WATTS)
            } else if (rawPower1 <= 0.001f) {
                if (rawPower2 > 0.001f) min(rawPower2, MAX_STANDBY_POWER_WATTS) else DEFAULT_STANDBY_BASE_WATTS
            } else if (dtMs > MAX_INTEGRATION_INTERVAL_MS) {
                min(rawPower1, MAX_STANDBY_POWER_WATTS)
            } else {
                rawPower1
            }
        } else {
            rawPower1
        }

        val power2 = if (isScreenOff) {
            if (p2.state == ScreenPowerState.SCREEN_OFF && dtMs > MAX_INTEGRATION_INTERVAL_MS) {
                if (rawPower2 <= 0.001f) DEFAULT_STANDBY_BASE_WATTS else min(rawPower2, MAX_STANDBY_POWER_WATTS)
            } else if (rawPower2 <= 0.001f) {
                if (rawPower1 > 0.001f) min(rawPower1, MAX_STANDBY_POWER_WATTS) else DEFAULT_STANDBY_BASE_WATTS
            } else if (dtMs > MAX_INTEGRATION_INTERVAL_MS) {
                min(rawPower2, MAX_STANDBY_POWER_WATTS)
            } else {
                rawPower2
            }
        } else {
            rawPower2
        }

        val rawCurrent1 = p1.currentAmperes?.let { max(0.0f, it) } ?: 0.0f
        val rawCurrent2 = p2.currentAmperes?.let { max(0.0f, it) } ?: 0.0f
        val current1 = if (isScreenOff && rawCurrent1 <= 0.0001f && p1.voltageVolts > 0f) power1 / p1.voltageVolts else rawCurrent1
        val current2 = if (isScreenOff && rawCurrent2 <= 0.0001f && p2.voltageVolts > 0f) power2 / p2.voltageVolts else rawCurrent2

        val temp1 = p1.temperatureCelsius ?: 25.0f
        val temp2 = p2.temperatureCelsius ?: 25.0f

        // 场景 1：两点位于同一个 5% Bin 内部，无需边界切分
        if (bin1 == bin2 || abs(s1 - s2) < 0.001f) {
            val targetBin = bin1
            val avgPower = (power1 + power2) / 2.0
            var deltaWh = avgPower * (dtMs.toDouble() / 3600000.0)
            // 物理电荷守恒上限保护：息屏长待机切片在单步内的放电做功不能超过物理最大电荷化学能
            if (isScreenOff && dtMs > MAX_INTEGRATION_INTERVAL_MS) {
                val stepSpan = max(0.3, abs(s1 - s2).toDouble())
                val maxAllowedStepWh = maxOf(0.45 * stepSpan, 0.40)
                deltaWh = min(deltaWh, maxAllowedStepWh)
            }
            if (deltaWh > 0.0 && deltaWh.isFinite()) {
                binEnergyAcc[targetBin] += deltaWh
                binDurationAcc[targetBin] += dtMs
                binMinSoc[targetBin] = min(binMinSoc[targetBin], min(s1, s2))
                binMaxSoc[targetBin] = max(binMaxSoc[targetBin], max(s1, s2))
                binCurrentWeightSum[targetBin] += ((current1 + current2) / 2.0) * dtMs
                binTempWeightSum[targetBin] += ((temp1 + temp2) / 2.0) * dtMs
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
                val boundaryTemp = temp1 + (temp2 - temp1) * nextTimeFraction.toFloat()

                val midSoc = (cursorSoc + b) / 2.0f
                val subBin = getBinIndexForSoc(midSoc)
                val subAvgPower = max(0.0, ((cursorPower + boundaryPower) / 2.0).toDouble())
                var subWh = subAvgPower * (subDtMs.toDouble() / 3600000.0)
                if (isScreenOff && dtMs > MAX_INTEGRATION_INTERVAL_MS) {
                    val subSpan = max(0.3, abs(cursorSoc - b).toDouble())
                    subWh = min(subWh, maxOf(0.45 * subSpan, 0.40))
                }

                if (subWh > 0.0 && subWh.isFinite()) {
                    binEnergyAcc[subBin] += subWh
                    binDurationAcc[subBin] += subDtMs
                    binMinSoc[subBin] = min(binMinSoc[subBin], min(cursorSoc, b))
                    binMaxSoc[subBin] = max(binMaxSoc[subBin], max(cursorSoc, b))
                    binCurrentWeightSum[subBin] += ((cursorCurrent + boundaryCurrent) / 2.0) * subDtMs
                    binTempWeightSum[subBin] += ((cursorTemp + boundaryTemp) / 2.0) * subDtMs
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
            var tailWh = tailAvgPower * (tailDtMs.toDouble() / 3600000.0)
            if (isScreenOff && dtMs > MAX_INTEGRATION_INTERVAL_MS) {
                val tailSpan = max(0.3, abs(cursorSoc - s2).toDouble())
                tailWh = min(tailWh, maxOf(0.45 * tailSpan, 0.40))
            }

            if (tailWh > 0.0 && tailWh.isFinite()) {
                binEnergyAcc[tailBin] += tailWh
                binDurationAcc[tailBin] += tailDtMs
                binMinSoc[tailBin] = min(binMinSoc[tailBin], min(cursorSoc, s2))
                binMaxSoc[tailBin] = max(binMaxSoc[tailBin], max(cursorSoc, s2))
                binCurrentWeightSum[tailBin] += ((cursorCurrent + current2) / 2.0) * tailDtMs
                binTempWeightSum[tailBin] += ((cursorTemp + temp2) / 2.0) * tailDtMs
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
