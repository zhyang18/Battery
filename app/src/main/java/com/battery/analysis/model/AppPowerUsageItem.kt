package com.battery.analysis.model

import android.graphics.drawable.Drawable

/**
 * 应用功耗与使用场景实体数据类。
 * 封装单个应用程序的前台运行耗时、后台运行耗时、前台消耗能量、后台消耗能量、
 * 亮屏平均功耗、运行温度及应用图标等展示信息。
 *
 * @property packageName 应用程序包名
 * @property appName 应用程序显示名称（如系统桌面、QQ、微信等）
 * @property icon 应用程序矢量图标或位图缓存 Drawable
 * @property foregroundTimeMs 前台活跃运行时长（单位：毫秒）
 * @property avgPowerWatts 亮屏平均运行功耗（基于前台时间与前台能耗真实计算，单位：瓦特 W）
 * @property avgTemperature 平均运行温度（单位：摄氏度 ℃）
 * @property maxTemperature 最高运行温度（单位：摄氏度 ℃）
 * @property lastUsedTimeMs 最近一次前台使用的时间戳（单位：毫秒）
 * @property directEnergyWh 底层系统直接测量或换算的总消耗电量（单位：瓦时 Wh，可选）
 * @property backgroundTimeMs 后台运行活跃时长（单位：毫秒，默认 0L）
 * @property foregroundEnergyWh 前台活跃状态下消耗的能量（单位：瓦时 Wh，默认 0f）
 * @property backgroundEnergyWh 后台活跃/休眠状态下消耗的能量（单位：瓦时 Wh，默认 0f）
 * @property cpuTimeMs CPU 运行时长（单位：毫秒，默认 0L）
 * @property networkBytes 网络数据传输总量（单位：字节 Byte，默认 0L）
 * @property wakelockTimeMs 持有唤醒锁时长（单位：毫秒，默认 0L）
 * @property gpsTimeMs GPS 定位使用时长（单位：毫秒，默认 0L）
 * @property foregroundPowerWatts 前台活跃点亮屏幕综合平均放电功耗（单位：瓦特 W，默认 0f）
 * @property backgroundPowerWatts 后台运行活跃放电平均功耗（单位：瓦特 W，默认 0f）
 */
data class AppPowerUsageItem(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val foregroundTimeMs: Long,
    val avgPowerWatts: Float,
    val avgTemperature: Float,
    val maxTemperature: Float,
    val lastUsedTimeMs: Long,
    val directEnergyWh: Float? = null,
    val backgroundTimeMs: Long = 0L,
    val foregroundEnergyWh: Float = 0f,
    val backgroundEnergyWh: Float = 0f,
    val cpuTimeMs: Long = 0L,
    val networkBytes: Long = 0L,
    val wakelockTimeMs: Long = 0L,
    val gpsTimeMs: Long = 0L,
    val foregroundPowerWatts: Float = 0f,
    val backgroundPowerWatts: Float = 0f
) {
    /**
     * 计算该应用消耗的总电量（单位：瓦时 Wh）。
     * 优先使用前后台消耗能量之和，其次使用 directEnergyWh，若均无直接测量值则基于平均功耗与运行总时长计算。
     */
    val energyWh: Float
        get() = if (foregroundEnergyWh > 0f || backgroundEnergyWh > 0f) {
            foregroundEnergyWh + backgroundEnergyWh
        } else {
            directEnergyWh ?: (avgPowerWatts * ((foregroundTimeMs + backgroundTimeMs) / 3600000f)).coerceAtLeast(0f)
        }

    /**
     * 格式化指定毫秒时长为人类可读字符串（如 "19m38s"、"58s"、"2h15m"）。
     *
     * @param durationMs 待格式化的时间毫秒数
     * @return 格式化后的时间字符串
     */
    private fun formatDurationMs(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
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

    /**
     * 格式化指定的瓦时能量为文本（精确到三位小数，如 "0.123Wh"、"<0.001Wh"、"0.000Wh"）。
     *
     * @param wh 待格式化的瓦时能量值
     * @return 格式化后的能量文本
     */
    private fun formatSingleEnergyWh(wh: Float): String {
        return if (wh <= 0f) {
            "0.000Wh"
        } else if (wh < 0.001f) {
            "<0.001Wh"
        } else {
            String.format(java.util.Locale.getDefault(), "%.3fWh", wh)
        }
    }

    /**
     * 获取格式化后的总电量消耗文本（如 "0.12Wh" 或 "<0.01Wh"）。
     *
     * @return 格式化后的总电量消耗文本
     */
    fun getFormattedEnergyWh(): String {
        val wh = energyWh
        return formatSingleEnergyWh(wh)
    }

    /**
     * 获取前台与后台组合能量消耗展示文本（格式："前台能量 | 后台能量"，如 "0.25Wh | 0.08Wh"）。
     * 如实反映系统真实测得的前台与后台能耗；当底层仅提供未分拆总能耗时，依据实际活动时长客观归属，杜绝将后台能耗误计入前台。
     *
     * @return 格式化后的组合能量文本
     */
    fun getFormattedCombinedEnergyWh(): String {
        val (fg, bg) = if (foregroundEnergyWh > 0f || backgroundEnergyWh > 0f) {
            // 如实反映真测的前台与后台能量
            Pair(foregroundEnergyWh, backgroundEnergyWh)
        } else {
            val total = energyWh
            if (total <= 0f) {
                Pair(0f, 0f)
            } else if (foregroundTimeMs <= 0L && backgroundTimeMs > 0L) {
                // 实测仅在后台运行，总能量如实归属于后台
                Pair(0f, total)
            } else if (foregroundTimeMs > 0L && backgroundTimeMs <= 0L) {
                // 实测仅在前台运行，总能量如实归属于前台
                Pair(total, 0f)
            } else if (foregroundTimeMs > 0L && backgroundTimeMs > 0L) {
                // 均有时长记录，按实测时长比例客观分配
                val totalTime = (foregroundTimeMs + backgroundTimeMs).toDouble()
                val fgRatio = (foregroundTimeMs / totalTime).toFloat()
                val fgVal = total * fgRatio
                Pair(fgVal, (total - fgVal).coerceAtLeast(0f))
            } else {
                // 均无活动时长，默认归属于后台基础底噪
                Pair(0f, total)
            }
        }
        val fgStr = formatSingleEnergyWh(fg)
        val bgStr = formatSingleEnergyWh(bg)
        return "$fgStr | $bgStr"
    }

    /**
     * 获取格式化后的前台使用时长字符串（如 19m38s、58s、2h15m）。
     *
     * @return 格式化后的前台使用时长文本
     */
    fun getFormattedDuration(): String {
        return formatDurationMs(foregroundTimeMs)
    }

    /**
     * 获取格式化后的后台运行活跃时长字符串（如 1h20m、30s）。
     *
     * @return 格式化后的后台运行活跃时长文本
     */
    fun getFormattedBackgroundDuration(): String {
        return formatDurationMs(backgroundTimeMs)
    }

    /**
     * 获取前台使用时长与后台运行活跃时长的组合展示文本（格式："前台时长 | 后台时长"，如 "19m38s | 1h20m"）。
     *
     * @return 格式化后的组合时长文本
     */
    fun getFormattedCombinedDuration(): String {
        val fgStr = formatDurationMs(foregroundTimeMs)
        val bgStr = formatDurationMs(backgroundTimeMs)
        return "$fgStr | $bgStr"
    }

    /**
     * 格式化指定的瓦特功率值为人类可读文本（如 "1.44W"、"0.01W" 或 "--"）。
     * 当功率低于有效统计门槛（0.005W，即保留两位小数时无法达到 0.01W）时显示为 "--"，杜绝微小底噪误报与伪零显示。
     *
     * @param watts 待格式化的功率值（单位：W）
     * @return 格式化后的功率文本
     */
    private fun formatWatts(watts: Float): String {
        return if (watts >= 0.005f) {
            String.format(java.util.Locale.getDefault(), "%.2fW", watts)
        } else {
            "--"
        }
    }

    /**
     * 获取前台亮屏平均功耗与后台活跃放电平均功耗的组合展示文本（格式："前台AVG | 后台AVG"，如 "1.44W | 0.08W"、"1.44W | --"、"-- | 0.12W"、"-- | --"）。
     * 如实区分前台点亮屏幕运行工况与后台活跃放电工况；当应用在对应场景未运行或时长不足有效统计门槛（1秒）时，如实显示为 "--"。
     *
     * @return 格式化后的组合平均功耗展示文本
     */
    fun getFormattedCombinedAvgWatts(): String {
        val fgPwr = if (foregroundPowerWatts > 0f) {
            foregroundPowerWatts
        } else if (foregroundTimeMs >= 1000L && foregroundEnergyWh > 0f) {
            (foregroundEnergyWh / (foregroundTimeMs / 3600000f)).coerceAtLeast(0f)
        } else if (foregroundTimeMs >= 1000L && avgPowerWatts > 0f) {
            avgPowerWatts
        } else {
            0f
        }

        val bgPwr = if (backgroundPowerWatts > 0f) {
            backgroundPowerWatts
        } else if (backgroundTimeMs >= 1000L && backgroundEnergyWh > 0f) {
            (backgroundEnergyWh / (backgroundTimeMs / 3600000f)).coerceAtLeast(0f)
        } else if (foregroundTimeMs <= 0L && backgroundTimeMs >= 1000L && avgPowerWatts > 0f) {
            avgPowerWatts
        } else {
            0f
        }

        return "${formatWatts(fgPwr)} | ${formatWatts(bgPwr)}"
    }
}


