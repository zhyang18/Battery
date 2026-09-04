package com.battery.analysis.model

import android.graphics.drawable.Drawable

/**
 * 应用功耗与使用场景实体数据类。
 * 封装单个应用程序的前台运行耗时、平均功耗、运行温度及应用图标等展示信息。
 *
 * @property packageName 应用程序包名
 * @property appName 应用程序显示名称（如系统桌面、QQ、微信等）
 * @property icon 应用程序矢量图标或位图缓存 Drawable
 * @property foregroundTimeMs 前台活跃运行时长（毫秒）
 * @property avgPowerWatts 平均运行功耗（单位：瓦特 W）
 * @property avgTemperature 平均运行温度（单位：摄氏度 ℃）
 * @property maxTemperature 最高运行温度（单位：摄氏度 ℃）
 * @property lastUsedTimeMs 最近一次前台使用的时间戳（毫秒）
 */
data class AppPowerUsageItem(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val foregroundTimeMs: Long,
    val avgPowerWatts: Float,
    val avgTemperature: Int,
    val maxTemperature: Int,
    val lastUsedTimeMs: Long
) {
    /**
     * 计算该应用消耗的总电量（单位：瓦时 Wh）。
     * Wh = 平均功耗 (W) * 前台运行小时数 (h)。
     */
    val energyWh: Float
        get() = (avgPowerWatts * (foregroundTimeMs / 3600000f)).coerceAtLeast(0f)

    /**
     * 获取格式化后的电量消耗文本（如 "0.12Wh" 或 "<0.01Wh"）。
     *
     * @return 格式化后的电量消耗文本
     */
    fun getFormattedEnergyWh(): String {
        val wh = energyWh
        return if (wh < 0.01f && wh > 0f) {
            "<0.01Wh"
        } else {
            String.format(java.util.Locale.getDefault(), "%.2fWh", wh)
        }
    }

    /**
     * 获取格式化后的前台使用时长字符串（如 19m38s、58s、2h15m）。
     *
     * @return 格式化后的使用时长文本
     */
    fun getFormattedDuration(): String {
        val totalSeconds = foregroundTimeMs / 1000
        if (totalSeconds < 60) {
            return "${totalSeconds}s"
        }
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        val remainingSeconds = totalSeconds % 60
        return if (hours > 0) {
            "${hours}h${minutes}m"
        } else {
            "${minutes}m${remainingSeconds}s"
        }
    }
}
