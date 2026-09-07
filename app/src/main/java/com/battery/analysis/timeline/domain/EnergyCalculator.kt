package com.battery.analysis.timeline.domain

/**
 * 电池能耗与功率核心计算器。
 * 提供瞬时功率、时间积分能耗、平均功率以及基于硬件指标的多源功耗归因算法。
 */
object EnergyCalculator {

    /**
     * 计算特定时间采样点的瞬时功率（毫瓦 mW）。
     *
     * @param voltageMv 电池电压（毫伏 mV）
     * @param currentMa 电池电流（毫安 mA）
     * @return 瞬时功率（毫瓦 mW）
     */
    fun calculatePowerMw(voltageMv: Int, currentMa: Double): Double {
        return (voltageMv.toDouble() * currentMa) / 1000.0
    }

    /**
     * 计算特定时间切片内消耗的能量积分（毫瓦时 mWh）。
     * 积分公式：Energy(mWh) = Power(mW) * Δt(seconds) / 3600
     *
     * @param powerMw 平均功率（毫瓦 mW）
     * @param deltaSeconds 持续时间（秒）
     * @return 消耗能量（毫瓦时 mWh）
     */
    fun calculateEnergyMwh(powerMw: Double, deltaSeconds: Double): Double {
        if (deltaSeconds <= 0.0 || powerMw <= 0.0) return 0.0
        return (powerMw * deltaSeconds) / 3600.0
    }

    /**
     * 计算连续采样点序列的总积分能耗（毫瓦时 mWh）。
     *
     * @param samples 采样点序列 [List<BatterySample>]
     * @return 序列总能耗（毫瓦时 mWh）
     */
    fun integrateEnergyMwh(samples: List<BatterySample>): Double {
        if (samples.size < 2) return 0.0
        var totalEnergyMwh = 0.0
        for (i in 0 until samples.size - 1) {
            val current = samples[i]
            val next = samples[i + 1]
            val dtSeconds = (next.timestamp - current.timestamp).coerceAtLeast(0L) / 1000.0
            if (dtSeconds in 0.1..300.0) { // 限制异常过长间隔
                val avgPower = (current.powerMw + next.powerMw) / 2.0
                totalEnergyMwh += calculateEnergyMwh(avgPower, dtSeconds)
            }
        }
        return totalEnergyMwh
    }

    /**
     * 根据总能量与时间计算平均功率（毫瓦 mW）。
     * 公式：AveragePower(mW) = Energy(mWh) / Duration(hours)
     *
     * @param energyMwh 消耗能量（毫瓦时 mWh）
     * @param durationMs 持续时长（毫秒）
     * @return 平均功率（毫瓦 mW）
     */
    fun calculateAveragePowerMw(energyMwh: Double, durationMs: Long): Double {
        if (durationMs <= 0L || energyMwh <= 0.0) return 0.0
        val durationHours = durationMs.toDouble() / (1000.0 * 3600.0)
        return energyMwh / durationHours
    }

    /**
     * 估算应用在特定时间段内的功耗归因。
     * 避免将手机总功耗直接归因于单一前台应用，结合 CPU、网络、Wakelock 与屏幕状态进行合理权重切分。
     *
     * @param appDurationMs 应用活跃时长（毫秒）
     * @param totalDurationMs 时间轴总观测时长（毫秒）
     * @param totalSystemEnergyMwh 系统总耗能（毫瓦时 mWh）
     * @param isScreenOn 是否亮屏
     * @param directMah BatteryStats 直接统计的 mAh（若有）
     * @param nominalVoltageMv 参考标称电压（毫伏 mV，默认 3850）
     * @return 功耗与能量估算结果 [EnergyEstimate]
     */
    fun attributeAppEnergy(
        appDurationMs: Long,
        totalDurationMs: Long,
        totalSystemEnergyMwh: Double,
        isScreenOn: Boolean,
        directMah: Double? = null,
        nominalVoltageMv: Int = 3850
    ): EnergyEstimate {
        // 1. 若底层 BatteryStats 提供了权威 directMah，优先以此为基准（HIGH 可信度）
        if (directMah != null && directMah > 0.0) {
            val energyMwh = directMah * (nominalVoltageMv / 1000.0)
            val avgPowerMw = calculateAveragePowerMw(energyMwh, appDurationMs)
            val peakPowerMw = avgPowerMw * 1.5 // 估算峰值
            return EnergyEstimate(
                energyMwh = energyMwh,
                averagePowerMw = avgPowerMw,
                peakPowerMw = peakPowerMw,
                confidence = ConfidenceLevel.HIGH,
                source = EnergySource.BATTERY_STATS
            )
        }

        // 2. 否则基于时间占比和屏幕状态权重估算（MEDIUM / ESTIMATED）
        val timeRatio = if (totalDurationMs > 0L) {
            (appDurationMs.toDouble() / totalDurationMs.toDouble()).coerceIn(0.0, 1.0)
        } else {
            0.0
        }

        // 前台亮屏应用承担基础前台开销（扣除整机屏幕与待机基底）
        val screenWeight = if (isScreenOn) 0.65 else 0.2
        val estimatedEnergyMwh = totalSystemEnergyMwh * timeRatio * screenWeight
        val avgPowerMw = calculateAveragePowerMw(estimatedEnergyMwh, appDurationMs)
        val peakPowerMw = avgPowerMw * 1.8

        return EnergyEstimate(
            energyMwh = estimatedEnergyMwh,
            averagePowerMw = avgPowerMw,
            peakPowerMw = peakPowerMw,
            confidence = ConfidenceLevel.MEDIUM,
            source = EnergySource.ESTIMATED
        )
    }
}
