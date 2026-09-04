package com.battery.analysis.model

import android.graphics.drawable.Drawable

/**
 * 放电过程电量走势与采样数据点实体类。
 * 记录特定时间采样点的电量百分比、活跃应用图标列表、电压与温度信息，用于在折线图上绘制走势曲线与应用打点。
 *
 * @property timestamp 采样的绝对时间戳（毫秒）
 * @property elapsedHours 自统计起点以来经过的小时数（浮点数，如 0.5f 表示 30 分钟）
 * @property batteryLevel 电池电量百分比（0 ~ 100）
 * @property voltageVolts 电池电压（单位：伏特 V）
 * @property temperature 电池温度（单位：摄氏度 ℃）
 * @property powerWatts 瞬时放电功耗（单位：瓦特 W）
 * @property activeAppIcons 在当前时间段内活跃的应用图标列表，用于折线图打点堆叠展示
 * @property isScreenOn 当前采样时间段是否处于亮屏状态（true 为亮屏绿色，false 为息屏红色）
 * @property activeAppNames 在当前时间段内活跃的应用名称列表，用于悬浮气泡探查展示
 */
data class PowerDischargePoint(
    val timestamp: Long,
    val elapsedHours: Float,
    val batteryLevel: Int,
    val voltageVolts: Float = 3.97f,
    val temperature: Float = 30.5f,
    val powerWatts: Float = 2.1f,
    val activeAppIcons: List<Drawable> = emptyList(),
    val isScreenOn: Boolean = true,
    val activeAppNames: List<String> = emptyList()
) {
    /**
     * 获取用于 X 轴或浮窗气泡展示的格式化时间描述。
     *
     * @return 格式化后的时间字符串（如 "12h" 或 "1d6h"）
     */
    fun getFormattedTimeLabel(): String {
        val totalHours = elapsedHours.toInt()
        val days = totalHours / 24
        val remHours = totalHours % 24
        return when {
            days > 0 && remHours > 0 -> "${days}d${remHours}h"
            days > 0 -> "${days}d"
            else -> "${remHours}h"
        }
    }
}
