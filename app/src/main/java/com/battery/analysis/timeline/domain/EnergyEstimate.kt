package com.battery.analysis.timeline.domain

/**
 * 功耗与能量估算结果实体数据类。
 * 封装单项或总体功耗计算的能耗总量、平均功率、峰值功率及可信度与数据来源。
 *
 * @property energyMwh 消耗或释放的总能量（单位：毫瓦时 mWh）
 * @property averagePowerMw 对应时间跨度内的平均功率（单位：毫瓦 mW）
 * @property peakPowerMw 对应时间跨度内的瞬时峰值功率（单位：毫瓦 mW）
 * @property confidence 数据估算可信度级别
 * @property source 数据来源渠道
 */
data class EnergyEstimate(
    val energyMwh: Double,
    val averagePowerMw: Double,
    val peakPowerMw: Double,
    val confidence: ConfidenceLevel,
    val source: EnergySource
) {
    /**
     * 获取以瓦时（Wh）为单位的总能量。
     *
     * @return 总能量（Wh）
     */
    fun getEnergyWh(): Double {
        return energyMwh / 1000.0
    }

    /**
     * 获取以瓦特（W）为单位的平均功率。
     *
     * @return 平均功率（W）
     */
    fun getAveragePowerWatts(): Double {
        return averagePowerMw / 1000.0
    }

    /**
     * 获取以瓦特（W）为单位的峰值功率。
     *
     * @return 峰值功率（W）
     */
    fun getPeakPowerWatts(): Double {
        return peakPowerMw / 1000.0
    }
}
