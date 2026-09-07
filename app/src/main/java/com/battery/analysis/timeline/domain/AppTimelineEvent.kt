package com.battery.analysis.timeline.domain

import android.graphics.drawable.Drawable
import java.util.Locale

/**
 * 应用程序时间轴活跃事件实体数据类。
 * 记录特定应用程序在前台或后台活跃的时间区间、屏幕关联状态以及详细的功耗与硬件归因数据。
 *
 * @property packageName 应用程序包名
 * @property uid 应用程序的 Android 系统 UID
 * @property appName 应用程序显示名称
 * @property icon 应用程序图标 Drawable（可空）
 * @property startTime 活跃事件起始时间戳（毫秒）
 * @property endTime 活跃事件结束时间戳（毫秒）
 * @property durationMs 活跃持续时长（毫秒）
 * @property screenOn 活跃期间是否伴随亮屏
 * @property energyMwh 消耗能量估算值（单位：毫瓦时 mWh，可选）
 * @property averagePowerMw 平均运行功率（单位：毫瓦 mW，可选）
 * @property peakPowerMw 峰值运行功率（单位：毫瓦 mW，可选）
 * @property cpuTimeMs CPU 运行时长（毫秒，可选）
 * @property networkBytes 网络传输字节数（可选）
 * @property wakelockTimeMs 持有唤醒锁时长（毫秒，可选）
 * @property gpsTimeMs GPS 定位使用时长（毫秒，可选）
 * @property confidence 数据估算置信度级别
 * @property source 数据来源渠道
 */
data class AppTimelineEvent(
    val packageName: String,
    val uid: Int,
    val appName: String,
    val icon: Drawable? = null,
    val startTime: Long,
    val endTime: Long,
    val durationMs: Long = (endTime - startTime).coerceAtLeast(0L),
    val screenOn: Boolean = true,
    val energyMwh: Double? = null,
    val averagePowerMw: Double? = null,
    val peakPowerMw: Double? = null,
    val cpuTimeMs: Long = 0L,
    val networkBytes: Long = 0L,
    val wakelockTimeMs: Long = 0L,
    val gpsTimeMs: Long = 0L,
    val confidence: ConfidenceLevel = ConfidenceLevel.MEDIUM,
    val source: EnergySource = EnergySource.ESTIMATED
) {
    /**
     * 获取格式化后的持续使用时长字符串（如 1m 22s、45s、2h 10m）。
     *
     * @return 格式化后的使用时长文本
     */
    fun getFormattedDuration(): String {
        val totalSeconds = durationMs / 1000
        if (totalSeconds < 60) {
            return "${totalSeconds}s"
        }
        val minutes = totalSeconds / 60
        val remainingSeconds = totalSeconds % 60
        if (minutes < 60) {
            return if (remainingSeconds > 0) "${minutes}m ${remainingSeconds}s" else "${minutes}m"
        }
        val hours = minutes / 60
        val remainingMinutes = minutes % 60
        return if (remainingMinutes > 0) "${hours}h ${remainingMinutes}m" else "${hours}h"
    }

    /**
     * 获取格式化后的能耗显示文本（如 "18.4 mWh" 或 "1.25 Wh"）。
     *
     * @return 格式化后的能量消耗文本
     */
    fun getFormattedEnergy(): String {
        val mwh = energyMwh ?: return "--"
        return if (mwh >= 1000.0) {
            String.format(Locale.getDefault(), "%.2f Wh", mwh / 1000.0)
        } else {
            String.format(Locale.getDefault(), "%.1f mWh", mwh)
        }
    }

    /**
     * 获取格式化后的平均功率显示文本（如 "806 mW" 或 "1.42 W"）。
     *
     * @return 格式化后的平均功率文本
     */
    fun getFormattedAveragePower(): String {
        val mw = averagePowerMw ?: return "--"
        return if (mw >= 1000.0) {
            String.format(Locale.getDefault(), "%.2f W", mw / 1000.0)
        } else {
            String.format(Locale.getDefault(), "%.0f mW", mw)
        }
    }

    /**
     * 获取格式化后的峰值功率显示文本（如 "1.42 W" 或 "850 mW"）。
     *
     * @return 格式化后的峰值功率文本
     */
    fun getFormattedPeakPower(): String {
        val mw = peakPowerMw ?: return "--"
        return if (mw >= 1000.0) {
            String.format(Locale.getDefault(), "%.2f W", mw / 1000.0)
        } else {
            String.format(Locale.getDefault(), "%.0f mW", mw)
        }
    }

    /**
     * 获取格式化后的置信度中文描述。
     *
     * @return 置信度中文描述
     */
    fun getConfidenceLabel(): String {
        return when (confidence) {
            ConfidenceLevel.HIGH -> "高"
            ConfidenceLevel.MEDIUM -> "中"
            ConfidenceLevel.LOW -> "低"
        }
    }

    /**
     * 获取格式化后的数据来源中文描述。
     *
     * @return 数据来源中文描述
     */
    fun getSourceLabel(): String {
        return when (source) {
            EnergySource.BATTERY_STATS -> "BatteryStats"
            EnergySource.SYSFS -> "Sysfs"
            EnergySource.BATTERY_MANAGER -> "BatteryManager"
            EnergySource.ESTIMATED -> "算法估算"
        }
    }
}
