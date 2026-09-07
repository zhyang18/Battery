package com.battery.analysis.timeline.domain

/**
 * 电池物理指标采样点实体数据类。
 * 记录特定时间点的电池电量、电压、电流、温度及计算得到的瞬时功率。
 *
 * @property timestamp 物理采样的毫秒级绝对时间戳
 * @property batteryLevel 电池电量百分比（0 ~ 100）
 * @property voltageMv 电池端电压（单位：毫伏 mV）
 * @property currentMa 瞬时放电/充电电流（单位：毫安 mA，放电为正值）
 * @property temperatureC 电池温度（单位：摄氏度 ℃）
 * @property powerMw 瞬时功率（单位：毫瓦 mW，由电压与电流换算）
 */
data class BatterySample(
    val timestamp: Long,
    val batteryLevel: Int,
    val voltageMv: Int,
    val currentMa: Double,
    val temperatureC: Double,
    val powerMw: Double
) {
    /**
     * 获取以伏特（V）为单位的电压值。
     *
     * @return 电压（V）
     */
    fun getVoltageVolts(): Double {
        return voltageMv / 1000.0
    }

    /**
     * 获取以瓦特（W）为单位的功率值。
     *
     * @return 功率（W）
     */
    fun getPowerWatts(): Double {
        return powerMw / 1000.0
    }
}
