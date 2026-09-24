package com.battery.analysis.model

import android.content.Context
import android.graphics.drawable.Drawable
import com.battery.analysis.manager.BatteryStatusSnapshot
import com.battery.analysis.manager.FullPowerDataPackage
import com.battery.analysis.manager.PowerOverviewStats
import org.json.JSONArray
import org.json.JSONObject

/**
 * 耗电检测历史快照数据实体类。
 * 用于本地持久化存储用户每次拔掉电源时抓取的完整耗电账本快照，
 * 包含电池状态、放电核心三维指标、各应用场景排行列表以及放电折线轨迹点集。
 *
 * @property id 唯一记录主键（采用记录创建时的时间戳毫秒值）
 * @property recordTime 记录生成的格式化日期时间（如 "2026-09-04 13:30:22"）
 * @property levelPercent 记录时的电池电量百分比
 * @property voltageVolts 记录时的电池电压（单位：伏特 V）
 * @property temperature 记录时的电池温度（单位：摄氏度 ℃）
 * @property energyWh 记录时的估算电池能量（单位：瓦时 Wh）
 * @property isCharging 记录时的充电状态
 * @property avgPowerWatts 综合平均放电功耗（单位：瓦特 W）
 * @property screenOnPowerWatts 亮屏平均放电功耗（单位：瓦特 W）
 * @property screenOffPowerWatts 息屏待机放电功耗（单位：瓦特 W）
 * @property screenOnDurationText 亮屏持续时长文本（如 "3h20m"）
 * @property screenOffDurationText 息屏待机时长文本（如 "5h15m"）
 * @property totalDurationText 放电总计耗时文本（如 "8h35m"）
 * @property remainingScreenOnText 持续亮屏理论续航文本
 * @property remainingCompositeText 综合混合理论续航文本
 * @property remainingScreenOffText 纯息屏待机理论续航文本
 * @property isShizukuRealData 是否来自 Shizuku 底层高精度数据源
 * @property appCount 记录中包含的应用数量
 * @property trendPointsJson 放电折线数据点集的 JSON 序列化字符串
 * @property appListJson 应用耗电场景列表的 JSON 序列化字符串
 * @property backgroundPowerWatts 息屏后台常驻平均放电功耗（单位：瓦特 W）
 * @property backgroundDurationText 后台常驻服务持续时长文本（如 "2h15m"）
 * @property remainingBackgroundText 纯后台服务理论续航文本
 * @property screenOnEnergyWh 亮屏使用总消耗物理能量（单位：瓦时 Wh）
 * @property totalEnergyWh 放电周期整机总消耗物理能量（单位：瓦时 Wh）
 * @property screenOffEnergyWh 息屏待机总消耗物理能量（单位：瓦时 Wh）
 * @property backgroundEnergyWh 后台服务总消耗物理能量（单位：瓦时 Wh）
 * @property isCompleted 是否已结束放电并正式归档（true 为已完成，false 为进行中 RUNNING 检查点草稿）
 * @property lastCheckpointTime 最近一次 Checkpoint 检查点增量持久化时间戳（毫秒）
 */
data class PowerUsageRecord(
    val id: Long = System.currentTimeMillis(),
    val recordTime: String,
    val levelPercent: Int,
    val voltageVolts: Float,
    val temperature: Float,
    val energyWh: Float,
    val isCharging: Boolean,
    val avgPowerWatts: Float,
    val screenOnPowerWatts: Float,
    val screenOffPowerWatts: Float,
    val screenOnDurationText: String,
    val screenOffDurationText: String,
    val totalDurationText: String,
    val remainingScreenOnText: String,
    val remainingCompositeText: String,
    val remainingScreenOffText: String,
    val isShizukuRealData: Boolean,
    val appCount: Int,
    val trendPointsJson: String,
    val appListJson: String,
    val backgroundPowerWatts: Float = 0f,
    val backgroundDurationText: String = "",
    val remainingBackgroundText: String = "",
    val screenOnEnergyWh: Float = 0f,
    val totalEnergyWh: Float = 0f,
    val screenOffEnergyWh: Float = 0f,
    val backgroundEnergyWh: Float = 0f,
    val isCompleted: Boolean = true,
    val lastCheckpointTime: Long = 0L
) {

    /**
     * 将本条耗电历史记录反向还原为供耗电界面直接渲染的 [FullPowerDataPackage] 数据包。
     *
     * @param context 应用程序上下文，用于通过包名解析还原各应用专属图标
     * @return 还原构建的完整耗电数据包 [FullPowerDataPackage]
     */
    fun toFullPowerPackage(context: Context): FullPowerDataPackage {
        val pm = context.packageManager

        val batteryTotalEnergyWh = com.battery.analysis.util.BatteryEnergyCalculator.calculateTotalEnergyWh(
            effectiveCapacityMah = com.battery.analysis.manager.PowerUsageManager.getInstance(context).getEffectiveDeviceCapacityMah(),
            nominalVoltageVolts = com.battery.analysis.util.BatteryEnergyCalculator.DEFAULT_NOMINAL_VOLTAGE_VOLTS
        )
        val snapshot = BatteryStatusSnapshot(
            levelPercent = levelPercent,
            voltageVolts = voltageVolts,
            temperature = temperature,
            energyWh = energyWh,
            isCharging = isCharging,
            totalEnergyWh = batteryTotalEnergyWh
        )

        val overview = PowerOverviewStats(
            avgPowerWatts = avgPowerWatts,
            screenOnPowerWatts = screenOnPowerWatts,
            screenOffPowerWatts = screenOffPowerWatts,
            backgroundPowerWatts = backgroundPowerWatts,
            screenOnDurationText = screenOnDurationText,
            screenOffDurationText = screenOffDurationText,
            totalDurationText = totalDurationText,
            backgroundDurationText = backgroundDurationText,
            remainingScreenOnText = remainingScreenOnText,
            remainingCompositeText = remainingCompositeText,
            remainingScreenOffText = remainingScreenOffText,
            remainingBackgroundText = remainingBackgroundText,
            screenOnEnergyWh = screenOnEnergyWh,
            totalEnergyWh = totalEnergyWh,
            screenOffEnergyWh = screenOffEnergyWh,
            backgroundEnergyWh = backgroundEnergyWh,
            usedDurationText = "$screenOnDurationText / $totalDurationText",
            remainingLifeText = remainingCompositeText
        )

        // 反序列化应用列表
        val appList = mutableListOf<AppPowerUsageItem>()
        try {
            val jsonArray = JSONArray(appListJson)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val pkgName = obj.optString("pkg", "")
                val appName = obj.optString("name", pkgName)
                val timeMs = obj.optLong("time", 0L)
                val powerW = obj.optDouble("power", 0.0).toFloat()
                val avgTemp = obj.optDouble("avgTemp", 30.0).toFloat()
                val maxTemp = obj.optDouble("maxTemp", 35.0).toFloat()
                val lastUsed = obj.optLong("lastUsed", 0L)
                val icon: Drawable? = try {
                    pm.getApplicationIcon(pkgName)
                } catch (_: Exception) {
                    null
                }

                val bgTime = obj.optLong("bgTime", 0L)
                val fgsTime = obj.optLong("fgsTime", 0L)
                val fgEnergy = obj.optDouble("fgEnergy", 0.0).toFloat()
                val bgEnergy = obj.optDouble("bgEnergy", 0.0).toFloat()
                val directEnergy = obj.optDouble("directEnergy", (fgEnergy + bgEnergy).toDouble()).toFloat()
                val fgPwr = obj.optDouble("fgPower", if (timeMs > 0L) powerW.toDouble() else 0.0).toFloat()
                val bgPwr = obj.optDouble("bgPower", if (timeMs <= 0L && bgTime > 0L) powerW.toDouble() else 0.0).toFloat()

                appList.add(
                    AppPowerUsageItem(
                        packageName = pkgName,
                        appName = appName,
                        icon = icon,
                        foregroundTimeMs = timeMs,
                        avgPowerWatts = powerW,
                        avgTemperature = avgTemp,
                        maxTemperature = maxTemp,
                        lastUsedTimeMs = lastUsed,
                        directEnergyWh = directEnergy,
                        backgroundTimeMs = bgTime,
                        foregroundEnergyWh = fgEnergy,
                        backgroundEnergyWh = bgEnergy,
                        foregroundPowerWatts = fgPwr,
                        backgroundPowerWatts = bgPwr,
                        fgsDurationMs = fgsTime
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 反序列化放电折线点集
        val trendPoints = mutableListOf<PowerDischargePoint>()
        try {
            val jsonArray = JSONArray(trendPointsJson)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val ts = obj.optLong("ts", 0L)
                val elapsed = obj.optDouble("elapsed", 0.0).toFloat()
                val lvl = obj.optInt("lvl", levelPercent)
                val volt = obj.optDouble("volt", voltageVolts.toDouble()).toFloat()
                val temp = obj.optDouble("temp", temperature.toDouble()).toFloat()
                val pwr = obj.optDouble("pwr", avgPowerWatts.toDouble()).toFloat()
                val isScreenOn = obj.optBoolean("screenOn", true)

                trendPoints.add(
                    PowerDischargePoint(
                        timestamp = ts,
                        elapsedHours = elapsed,
                        batteryLevel = lvl,
                        voltageVolts = volt,
                        temperature = temp,
                        powerWatts = pwr,
                        activeAppIcons = emptyList(),
                        isScreenOn = isScreenOn
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val startLevel = trendPoints.firstOrNull()?.batteryLevel ?: 100
        return FullPowerDataPackage(
            batterySnapshot = snapshot,
            overviewStats = overview,
            appList = appList,
            trendPoints = trendPoints,
            isShizukuRealData = isShizukuRealData,
            startLevelPercent = startLevel
        )
    }

    /**
     * 解析并获取本次耗电记录的起止时间范围文本（包含开始时间与结束时间）。
     * 若起止为同一天则显示为 "yyyy/MM/dd HH:mm~HH:mm"；
     * 若起止为同年跨天则显示为 "yyyy/MM/dd HH:mm~MM/dd HH:mm"（去掉结束时间年份）；
     * 若跨年则显示为 "yyyy/MM/dd HH:mm~yyyy/MM/dd HH:mm"。
     *
     * @return 格式化后的起止时间范围字符串
     */
    fun getFormattedTimeRange(): String {
        val dateFormat = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault())
        val timeOnlyFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        val dayOnlyFormat = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
        val yearOnlyFormat = java.text.SimpleDateFormat("yyyy", java.util.Locale.getDefault())
        val monthDayTimeFormat = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault())

        var startTs = 0L
        var endTs = id

        // 尝试从折线点提取起止时间戳
        try {
            val jsonArray = JSONArray(trendPointsJson)
            if (jsonArray.length() > 0) {
                val firstObj = jsonArray.getJSONObject(0)
                startTs = firstObj.optLong("ts", 0L)
                val lastObj = jsonArray.getJSONObject(jsonArray.length() - 1)
                val lastTs = lastObj.optLong("ts", 0L)
                if (lastTs > 0L) {
                    endTs = lastTs
                }
            }
        } catch (_: Exception) {}

        // 若无法从点集获取开始时间，则尝试从 recordTime 及 totalDurationText 进行推算
        if (startTs <= 0L) {
            val endParsed = try {
                val inputFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                inputFormat.parse(recordTime)?.time ?: id
            } catch (_: Exception) {
                id
            }
            endTs = endParsed
            val durMs = parseDurationToMillis(totalDurationText)
            startTs = (endTs - durMs).coerceAtLeast(0L)
        }

        val startDate = java.util.Date(startTs)
        val endDate = java.util.Date(endTs)

        return if (dayOnlyFormat.format(startDate) == dayOnlyFormat.format(endDate)) {
            "${dateFormat.format(startDate)}~${timeOnlyFormat.format(endDate)}"
        } else if (yearOnlyFormat.format(startDate) == yearOnlyFormat.format(endDate)) {
            "${dateFormat.format(startDate)}~${monthDayTimeFormat.format(endDate)}"
        } else {
            "${dateFormat.format(startDate)}~${dateFormat.format(endDate)}"
        }
    }

    /**
     * 获取按设计图样式的时长及电量变化摘要文本（如 "46m · 76%~69%(-7%)"）。
     *
     * @return 格式化后的时长与电量变化字符串
     */
    fun getFormattedDurationAndLevel(): String {
        var startLevel = 100
        val endLevel = levelPercent

        try {
            val jsonArray = JSONArray(trendPointsJson)
            if (jsonArray.length() > 0) {
                startLevel = jsonArray.getJSONObject(0).optInt("lvl", 100)
            }
        } catch (_: Exception) {}

        val diff = endLevel - startLevel
        val diffSign = if (diff >= 0) "+$diff%" else "$diff%"
        val durText = if (totalDurationText.isNotBlank()) totalDurationText else "--"

        return "$durText · $startLevel%~$endLevel%($diffSign)"
    }

    /**
     * 获取设计图右上角展示的代表性功耗数值（单位：W）。
     * 优先展示平均亮屏放电功耗，若无亮屏功耗数据则回退展示综合平均功耗。
     *
     * @return 展示用的功耗数值
     */
    fun getDisplayPowerWatts(): Float {
        return if (screenOnPowerWatts > 0f) screenOnPowerWatts else avgPowerWatts
    }

    /**
     * 获取设计图右下角展示的功耗说明标签名称。
     *
     * @return 标签文本（如 "平均亮屏功耗" 或 "平均放电功耗"）
     */
    fun getDisplayPowerLabel(): String {
        return if (screenOnPowerWatts > 0f) "平均亮屏功耗" else "平均放电功耗"
    }

    /**
     * 获取设计图右下角展示的亮屏时间文本。
     * 优先使用实体中记录的亮屏时长文本；若为空则尝试从放电折线点集计算亮屏时长，若仍无法获取则返回未知占位符 "--"。
     *
     * @return 格式化后的亮屏时长文本（如 "1h55m"、"46m" 或 "--"）
     */
    fun getDisplayScreenOnDuration(): String {
        if (screenOnDurationText.isNotBlank()) {
            return screenOnDurationText
        }
        try {
            val jsonArray = JSONArray(trendPointsJson)
            if (jsonArray.length() > 1) {
                var totalScreenOnMs = 0L
                for (i in 0 until jsonArray.length() - 1) {
                    val p1 = jsonArray.getJSONObject(i)
                    val p2 = jsonArray.getJSONObject(i + 1)
                    val isScreenOn = p1.optBoolean("screenOn", true)
                    if (isScreenOn) {
                        val dt = (p2.optLong("ts", 0L) - p1.optLong("ts", 0L)).coerceAtLeast(0L)
                        totalScreenOnMs += dt
                    }
                }
                if (totalScreenOnMs > 0L) {
                    val totalSec = totalScreenOnMs / 1000L
                    val days = totalSec / 86400L
                    val hours = (totalSec % 86400L) / 3600L
                    val minutes = (totalSec % 3600L) / 60L
                    val seconds = totalSec % 60L
                    return when {
                        days > 0L -> "${days}d${hours}h"
                        hours > 0L -> "${hours}h${minutes}m"
                        else -> "${minutes}m${seconds}s"
                    }
                }
            }
        } catch (_: Exception) {}
        return "--"
    }

    /**
     * 获取本次放电记录的总持续时长（单位：毫秒）。
     *
     * @return 转换后的总持续时长毫秒数
     */
    fun getDurationMs(): Long {
        return parseDurationToMillis(totalDurationText)
    }

    /**
     * 获取本次放电记录的亮屏持续时长（单位：毫秒）。
     *
     * @return 转换后的亮屏持续时长毫秒数
     */
    fun getScreenOnDurationMs(): Long {
        val directMs = parseDurationToMillis(screenOnDurationText)
        if (directMs > 0L) return directMs
        val displayDur = getDisplayScreenOnDuration()
        return if (displayDur != "--") parseDurationToMillis(displayDur) else 0L
    }

    /**
     * 将时长文本解析还原为毫秒数。
     *
     * @param durationText 时长字符串（如 "1d2h"、"1h20m" 或 "46m30s"）
     * @return 对应的毫秒数
     */
    private fun parseDurationToMillis(durationText: String): Long {
        if (durationText.isBlank()) return 0L
        var totalMs = 0L
        try {
            val dRegex = "(\\d+)d".toRegex()
            val hRegex = "(\\d+)h".toRegex()
            val mRegex = "(\\d+)m".toRegex()
            val sRegex = "(\\d+)s".toRegex()
            dRegex.find(durationText)?.groupValues?.get(1)?.toLongOrNull()?.let {
                totalMs += it * 86400000L
            }
            hRegex.find(durationText)?.groupValues?.get(1)?.toLongOrNull()?.let {
                totalMs += it * 3600000L
            }
            mRegex.find(durationText)?.groupValues?.get(1)?.toLongOrNull()?.let {
                totalMs += it * 60000L
            }
            sRegex.find(durationText)?.groupValues?.get(1)?.toLongOrNull()?.let {
                totalMs += it * 1000L
            }
        } catch (_: Exception) {}
        return totalMs
    }



    companion object {
        /**
         * 从当前的 [FullPowerDataPackage] 耗电数据包装类构建持久化的 [PowerUsageRecord] 实体。
         *
         * @param fullPackage 包含全量功耗指标、放电走势与应用排行的数据包
         * @param recordTime 格式化后的记录时间字符串
         * @param id 自定义唯一记录 ID（默认采用当前时间戳，在放电会话中对齐为拔电起始时间戳）
         * @param isCompleted 放电会话是否已正式归档结案（true 为已完结，false 为进行中 RUNNING 检查点草稿）
         * @param lastCheckpointTime 最近一次检查点持久化落盘时间戳毫秒值
         * @return 构建成功的 [PowerUsageRecord] 实例
         */
        fun fromFullPowerPackage(
            fullPackage: FullPowerDataPackage,
            recordTime: String,
            id: Long = System.currentTimeMillis(),
            isCompleted: Boolean = true,
            lastCheckpointTime: Long = 0L
        ): PowerUsageRecord {
            val snapshot = fullPackage.batterySnapshot
            val overview = fullPackage.overviewStats

            // 序列化应用列表
            val appJsonArray = JSONArray()
            fullPackage.appList.forEach { item ->
                val obj = JSONObject().apply {
                    put("pkg", item.packageName)
                    put("name", item.appName)
                    put("time", item.foregroundTimeMs)
                    put("power", item.avgPowerWatts.toDouble())
                    put("avgTemp", item.avgTemperature)
                    put("maxTemp", item.maxTemperature)
                    put("lastUsed", item.lastUsedTimeMs)
                    put("bgTime", item.backgroundTimeMs)
                    put("fgsTime", item.fgsDurationMs)
                    put("fgEnergy", item.foregroundEnergyWh.toDouble())
                    put("bgEnergy", item.backgroundEnergyWh.toDouble())
                    put("directEnergy", item.energyWh.toDouble())
                    put("fgPower", item.foregroundPowerWatts.toDouble())
                    put("bgPower", item.backgroundPowerWatts.toDouble())
                }
                appJsonArray.put(obj)
            }

            // 序列化放电折线点
            val trendJsonArray = JSONArray()
            fullPackage.trendPoints.forEach { pt ->
                val obj = JSONObject().apply {
                    put("ts", pt.timestamp)
                    put("elapsed", pt.elapsedHours.toDouble())
                    put("lvl", pt.batteryLevel)
                    put("volt", pt.voltageVolts.toDouble())
                    put("temp", pt.temperature.toDouble())
                    put("pwr", pt.powerWatts.toDouble())
                    put("screenOn", pt.isScreenOn)
                }
                trendJsonArray.put(obj)
            }

            return PowerUsageRecord(
                id = id,
                recordTime = recordTime,
                levelPercent = snapshot.levelPercent,
                voltageVolts = snapshot.voltageVolts,
                temperature = snapshot.temperature,
                energyWh = snapshot.energyWh,
                isCharging = snapshot.isCharging,
                avgPowerWatts = overview.avgPowerWatts,
                screenOnPowerWatts = overview.screenOnPowerWatts,
                screenOffPowerWatts = overview.screenOffPowerWatts,
                screenOnDurationText = overview.screenOnDurationText,
                screenOffDurationText = overview.screenOffDurationText,
                totalDurationText = overview.totalDurationText,
                remainingScreenOnText = overview.remainingScreenOnText,
                remainingCompositeText = overview.remainingCompositeText,
                remainingScreenOffText = overview.remainingScreenOffText,
                isShizukuRealData = fullPackage.isShizukuRealData,
                appCount = fullPackage.appList.size,
                trendPointsJson = trendJsonArray.toString(),
                appListJson = appJsonArray.toString(),
                backgroundPowerWatts = overview.backgroundPowerWatts,
                backgroundDurationText = overview.backgroundDurationText,
                remainingBackgroundText = overview.remainingBackgroundText,
                screenOnEnergyWh = overview.screenOnEnergyWh,
                totalEnergyWh = overview.totalEnergyWh,
                screenOffEnergyWh = overview.screenOffEnergyWh,
                backgroundEnergyWh = overview.backgroundEnergyWh,
                isCompleted = isCompleted,
                lastCheckpointTime = lastCheckpointTime
            )
        }
    }
}
