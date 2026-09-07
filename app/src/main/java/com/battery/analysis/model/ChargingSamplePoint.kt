package com.battery.analysis.model

/**
 * 充电过程实时采样点数据实体类。
 * 用于记录充电过程中某一时刻的瞬时物理指标，支撑三合一趋势图表与动态数据刷新。
 *
 * @property timestamp 采样时刻的时间戳（毫秒）
 * @property powerWatts 实时充电功率（单位：W）
 * @property batteryLevel 电池电量百分比（0~100）
 * @property temperature 电池实时温度（单位：℃）
 * @property voltageVolts 电池端电压（单位：V）
 * @property currentMa 充电瞬时电流（单位：mA，充电时通常为正值）
 */
data class ChargingSamplePoint(
    val timestamp: Long,
    val powerWatts: Float,
    val batteryLevel: Int,
    val temperature: Float,
    val voltageVolts: Float,
    val currentMa: Float
)
