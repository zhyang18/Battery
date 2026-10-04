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
 * @property fgsDurationMs 前台服务（Foreground Service）常驻挂载时长（单位：毫秒，默认 0L）
 * @property isUninstalled 显式标记当前应用是否属于已卸载应用（默认 false）
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
    val backgroundPowerWatts: Float = 0f,
    val fgsDurationMs: Long = 0L,
    val isUninstalled: Boolean = false
) {
    /**
     * 计算该应用消耗的总电量（单位：瓦时 Wh）。
     * 物理积分规范：功耗与能量仅统计应用前台物理放电切片，后台不统计虚拟能耗。
     */
    val energyWh: Float
        get() = if (foregroundEnergyWh > 0f) {
            foregroundEnergyWh
        } else if (directEnergyWh != null && directEnergyWh > 0f && foregroundTimeMs > 0L) {
            directEnergyWh
        } else if (avgPowerWatts > 0f && foregroundTimeMs > 0L) {
            (avgPowerWatts * (foregroundTimeMs / 3600000f)).coerceAtLeast(0f)
        } else {
            0f
        }

    /**
     * 判断当前应用是否为已卸载应用条目。
     *
     * @return 若为已卸载应用返回 true，否则返回 false
     */
    fun isUninstalledApp(): Boolean {
        return isUninstalled || isUninstalledPackage(packageName)
    }

    /**
     * 检查当前应用条目是否具备真实有效的物理放电能量（大于等于 0.0005Wh，在界面上至少呈现为 0.001Wh）。
     * 用于杜绝底层量化噪声与 0 耗电无效条目在主列表中产生脏数据刷屏。
     *
     * @return 若具备有效物理能耗返回 true，否则返回 false
     */
    fun hasEffectiveEnergy(): Boolean {
        val direct = directEnergyWh ?: 0f
        return direct >= 0.0005f || energyWh >= 0.0005f || foregroundEnergyWh >= 0.0005f || backgroundEnergyWh >= 0.0005f
    }

    /**
     * 将另一项同应用实体（如卸载前与重装后的分段统计实体）的数据进行物理守恒合并。
     * 累加前后台工时、放电能量、CPU 运算时间与硬件网络/锁/GPS 开销，并重新计算综合平均功耗。
     *
     * @param other 待合并的同应用功耗实体
     * @return 包含两者累计物理开销的合并新实体 [AppPowerUsageItem]
     */
    fun mergeWith(other: AppPowerUsageItem): AppPowerUsageItem {
        val mergedFgMs = this.foregroundTimeMs + other.foregroundTimeMs
        val mergedBgMs = this.backgroundTimeMs + other.backgroundTimeMs
        val mergedFgEnergy = this.foregroundEnergyWh + other.foregroundEnergyWh
        val mergedBgEnergy = this.backgroundEnergyWh + other.backgroundEnergyWh
        val mergedDirect = if (this.directEnergyWh != null || other.directEnergyWh != null) {
            (this.directEnergyWh ?: 0f) + (other.directEnergyWh ?: 0f)
        } else {
            null
        }
        val mergedCpu = this.cpuTimeMs + other.cpuTimeMs
        val mergedNet = this.networkBytes + other.networkBytes
        val mergedWake = this.wakelockTimeMs + other.wakelockTimeMs
        val mergedGps = this.gpsTimeMs + other.gpsTimeMs
        val mergedFgs = this.fgsDurationMs + other.fgsDurationMs
        val mergedLastUsed = maxOf(this.lastUsedTimeMs, other.lastUsedTimeMs)
        val mergedMaxTemp = maxOf(this.maxTemperature, other.maxTemperature)
        val mergedAvgTemp = if (this.avgTemperature > 0f && other.avgTemperature > 0f) {
            (this.avgTemperature + other.avgTemperature) / 2f
        } else {
            maxOf(this.avgTemperature, other.avgTemperature)
        }
        val mergedIsUninstalled = this.isUninstalled || other.isUninstalled ||
                isUninstalledPackage(this.packageName) || isUninstalledPackage(other.packageName)

        val fgHours = if (mergedFgMs > 0L) mergedFgMs / 3600000.0 else 0.0
        val bgHours = if (mergedBgMs > 0L) mergedBgMs / 3600000.0 else 0.0
        val newFgWatts = if (fgHours > 0.0 && mergedFgEnergy > 0f) (mergedFgEnergy / fgHours).toFloat() else 0f
        val newBgWatts = if (bgHours > 0.0 && mergedBgEnergy > 0f) (mergedBgEnergy / bgHours).toFloat() else 0f
        val newAvgWatts = if (newFgWatts > 0f) newFgWatts else newBgWatts

        val effectiveIcon = this.icon ?: other.icon
        val effectiveName = if (!this.appName.startsWith("uninstalled_") && this.appName != this.packageName) {
            this.appName
        } else {
            other.appName
        }

        return this.copy(
            appName = effectiveName,
            icon = effectiveIcon,
            foregroundTimeMs = mergedFgMs,
            backgroundTimeMs = mergedBgMs,
            directEnergyWh = mergedDirect,
            foregroundEnergyWh = mergedFgEnergy,
            backgroundEnergyWh = mergedBgEnergy,
            avgPowerWatts = newAvgWatts,
            foregroundPowerWatts = newFgWatts,
            backgroundPowerWatts = newBgWatts,
            cpuTimeMs = mergedCpu,
            networkBytes = mergedNet,
            wakelockTimeMs = mergedWake,
            gpsTimeMs = mergedGps,
            fgsDurationMs = mergedFgs,
            lastUsedTimeMs = mergedLastUsed,
            avgTemperature = mergedAvgTemp,
            maxTemperature = mergedMaxTemp,
            isUninstalled = mergedIsUninstalled
        )
    }

    companion object {
        /** 标识未解析出包名的已卸载应用的虚拟包名前缀 */
        const val PACKAGE_UNINSTALLED_PREFIX = "uninstalled_uid_"

        /** 标识亮屏状态下系统桌面、锁屏或未识别前台交互界面的系统级功耗归集条目包名 */
        const val PACKAGE_SYSTEM_UI_STANDBY = "system:launcher_or_ui"

        /**
         * 判断给定包名是否为已卸载应用虚拟包名。
         *
         * @param packageName 待检查的应用程序包名
         * @return 若属于已卸载应用返回 true，否则返回 false
         */
        fun isUninstalledPackage(packageName: String?): Boolean {
            if (packageName.isNullOrBlank()) return false
            return packageName.startsWith(PACKAGE_UNINSTALLED_PREFIX) ||
                    packageName.startsWith("uninstalled_") ||
                    packageName.startsWith("com.battery.analysis.uninstalled.")
        }

        /**
         * 判断给定包名是否为系统界面/桌面待机系统级条目。
         *
         * @param packageName 待检查的应用程序包名
         * @return 若属于系统界面/桌面待机条目返回 true，否则返回 false
         */
        fun isSystemUiStandbyPackage(packageName: String?): Boolean {
            return packageName == PACKAGE_SYSTEM_UI_STANDBY
        }
    }

    /**
     * 格式化指定毫秒时长为人类可读字符串（如 "19m38s"、"05s"、"02h15m"、"00s" 或 "350ms"）。
     * 对少于 1 秒的毫秒级运行时长精确展示（如 "350ms"），杜绝被粗暴丢弃或显示为 "00s"。
     *
     * @param durationMs 待格式化的时间毫秒数
     * @return 格式化后的时间字符串
     */
    private fun formatDurationMs(durationMs: Long): String {
        if (durationMs <= 0L) return "00s"
        if (durationMs < 1000L) return "${durationMs}ms"
        val totalSeconds = durationMs / 1000
        if (totalSeconds < 60) {
            return String.format(java.util.Locale.getDefault(), "%02ds", totalSeconds)
        }
        val totalMinutes = totalSeconds / 60
        val remainingSeconds = totalSeconds % 60
        val hours = totalMinutes / 60
        val remainingMinutes = totalMinutes % 60

        return if (hours > 0) {
            if (remainingMinutes > 0) String.format(java.util.Locale.getDefault(), "%02dh%02dm", hours, remainingMinutes)
            else String.format(java.util.Locale.getDefault(), "%02dh", hours)
        } else {
            if (remainingSeconds > 0) String.format(java.util.Locale.getDefault(), "%02dm%02ds", remainingMinutes, remainingSeconds)
            else String.format(java.util.Locale.getDefault(), "%02dm", remainingMinutes)
        }
    }

    /**
     * 格式化单项瓦时能量数值为易读字符串，精确到小数点后三位（如 "0.250Wh" 或 "<0.001Wh"）。
     * 若数值大于等于 0.0005Wh 则保留三位小数格式化；若数值介于 0.00001Wh 与 0.0005Wh 之间则显示为 "<0.001Wh"；若无有效能量消耗则如实显示 "--"。
     *
     * @param wh 待格式化的瓦时能量数值（单位：Wh）
     * @return 格式化后的精确能量文本
     */
    private fun formatSingleEnergyWh(wh: Float): String {
        return if (wh >= 0.0005f) {
            String.format(java.util.Locale.getDefault(), "%.3fWh", wh)
        } else if (wh > 0.00001f) {
            "<0.001Wh"
        } else {
            "--"
        }
    }

    /**
     * 获取格式化后的总电量消耗文本（如 "0.250Wh" 或 "<0.001Wh"）。
     *
     * @return 格式化后的总电量消耗文本
     */
    fun getFormattedEnergyWh(): String {
        val wh = energyWh
        return formatSingleEnergyWh(wh)
    }

    /**
     * 获取前后台模式下的能量消耗展示文本。
     * 功耗与能量仅统计前台真实物理放电；
     * 若应用在前台运行则展示真实前台能量（精确到小数点后三位），纯后台运行应用如实显示为 "--"，杜绝虚假发配后台电量。
     *
     * @return 格式化后的能量展示文本
     */
    fun getFormattedCombinedEnergyWh(): String {
        val fgEnergy = getForegroundEnergyValue()
        return formatSingleEnergyWh(fgEnergy)
    }

    /**
     * 获取格式化后的前台使用时长字符串（如 19m38s、05s、02h15m、00s）。
     *
     * @return 格式化后的前台使用时长文本
     */
    fun getFormattedDuration(): String {
        return formatDurationMs(foregroundTimeMs)
    }

    /**
     * 获取格式化后的后台运行活跃时长字符串（如 01h20m、30s、00s）。
     *
     * @return 格式化后的后台运行活跃时长文本
     */
    fun getFormattedBackgroundDuration(): String {
        return formatDurationMs(backgroundTimeMs)
    }

    /**
     * 获取格式化后的前台服务常驻挂载时长字符串（如 12h30m、15m、00s）。
     *
     * @return 格式化后的前台服务常驻时长文本
     */
    fun getFormattedFgsDuration(): String {
        return formatDurationMs(fgsDurationMs)
    }

    /**
     * 获取前台使用时长与后台运行活跃时长的组合展示文本（格式："前台时长 | 后台 后台时长" 或 "后台: xx"）。
     *
     * @return 格式化后的组合时长文本
     */
    fun getFormattedCombinedDuration(): String {
        val hasFg = foregroundTimeMs > 0L
        val hasBg = backgroundTimeMs > 0L
        return when {
            hasFg && hasBg -> "${formatDurationMs(foregroundTimeMs)} | 后台 ${formatDurationMs(backgroundTimeMs)}"
            hasFg -> formatDurationMs(foregroundTimeMs)
            hasBg -> "后台: ${formatDurationMs(backgroundTimeMs)}"
            else -> "00s"
        }
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
     * 获取前后台模式下的平均放电功耗展示文本。
     * 平均功耗仅基于前台屏幕点亮与独占交互物理切片计算；
     * 纯后台运行应用因在硬件上无法切出独立放电电流，诚实显示为 "--"，杜绝模糊估算。
     *
     * @return 格式化后的平均功耗展示文本
     */
    fun getFormattedCombinedAvgWatts(): String {
        return getFormattedForegroundAvgWatts()
    }

    /**
     * 计算并获取纯前台活跃状态下消耗的能量值（单位：瓦时 Wh）。
     * 若已明确记录前台或后台能量，直接返回前台能量；否则按前后台活跃时长比例客观拆分总能耗。
     *
     * @return 前台活跃消耗能量（单位：瓦时 Wh）
     */
    fun getForegroundEnergyValue(): Float {
        return if (foregroundEnergyWh > 0f || backgroundEnergyWh > 0f) {
            foregroundEnergyWh
        } else {
            val total = energyWh
            if (total <= 0f) {
                0f
            } else if (foregroundTimeMs <= 0L && backgroundTimeMs > 0L) {
                0f
            } else if (foregroundTimeMs > 0L && backgroundTimeMs <= 0L) {
                total
            } else if (foregroundTimeMs > 0L && backgroundTimeMs > 0L) {
                val totalTime = (foregroundTimeMs + backgroundTimeMs).toDouble()
                val fgRatio = (foregroundTimeMs / totalTime).toFloat()
                total * fgRatio
            } else {
                0f
            }
        }
    }

    /**
     * 获取纯前台亮屏运行平均功耗展示文本（如 "1.44W" 或 "--"）。
     * 只要应用在前台产生过活跃工时（包含少于 1 秒的短时启动），均如实计算并展示其前台平均功耗；
     * 纯后台运行应用因未独占屏幕交互且无法切出硬件放电切片，诚实显示为 "--"。
     *
     * @return 格式化后的纯前台平均功耗文本
     */
    fun getFormattedForegroundAvgWatts(): String {
        val fgPwr = if (foregroundPowerWatts > 0f) {
            foregroundPowerWatts
        } else if (foregroundTimeMs > 0L && foregroundEnergyWh > 0f) {
            (foregroundEnergyWh / (foregroundTimeMs / 3600000f)).coerceAtLeast(0f)
        } else if (foregroundTimeMs > 0L && avgPowerWatts > 0f) {
            avgPowerWatts
        } else {
            0f
        }
        return formatWatts(fgPwr)
    }

    /**
     * 获取纯前台活跃状态下消耗能量的展示文本（格式如 "0.250Wh"）。
     *
     * @return 格式化后的纯前台能量消耗文本
     */
    fun getFormattedForegroundEnergyWh(): String {
        val fg = getForegroundEnergyValue()
        return formatSingleEnergyWh(fg)
    }
}


