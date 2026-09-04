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
    val appListJson: String
) {

    /**
     * 将本条耗电历史记录反向还原为供耗电界面直接渲染的 [FullPowerDataPackage] 数据包。
     *
     * @param context 应用程序上下文，用于通过包名解析还原各应用专属图标
     * @return 还原构建的完整耗电数据包 [FullPowerDataPackage]
     */
    fun toFullPowerPackage(context: Context): FullPowerDataPackage {
        val pm = context.packageManager

        val snapshot = BatteryStatusSnapshot(
            levelPercent = levelPercent,
            voltageVolts = voltageVolts,
            temperature = temperature,
            energyWh = energyWh,
            isCharging = isCharging
        )

        val overview = PowerOverviewStats(
            avgPowerWatts = avgPowerWatts,
            screenOnPowerWatts = screenOnPowerWatts,
            screenOffPowerWatts = screenOffPowerWatts,
            screenOnDurationText = screenOnDurationText,
            screenOffDurationText = screenOffDurationText,
            totalDurationText = totalDurationText,
            remainingScreenOnText = remainingScreenOnText,
            remainingCompositeText = remainingCompositeText,
            remainingScreenOffText = remainingScreenOffText,
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
                val avgTemp = obj.optInt("avgTemp", 30)
                val maxTemp = obj.optInt("maxTemp", 35)
                val lastUsed = obj.optLong("lastUsed", 0L)

                val icon: Drawable? = try {
                    pm.getApplicationIcon(pkgName)
                } catch (_: Exception) {
                    null
                }

                appList.add(
                    AppPowerUsageItem(
                        packageName = pkgName,
                        appName = appName,
                        icon = icon,
                        foregroundTimeMs = timeMs,
                        avgPowerWatts = powerW,
                        avgTemperature = avgTemp,
                        maxTemperature = maxTemp,
                        lastUsedTimeMs = lastUsed
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

    companion object {
        /**
         * 从当前的 [FullPowerDataPackage] 耗电数据包装类构建持久化的 [PowerUsageRecord] 实体。
         *
         * @param fullPackage 包含全量功耗指标、放电走势与应用排行的数据包
         * @param recordTime 格式化后的记录时间字符串
         * @param id 自定义唯一记录 ID（默认采用当前时间戳）
         * @return 构建成功的 [PowerUsageRecord] 实例
         */
        fun fromFullPowerPackage(
            fullPackage: FullPowerDataPackage,
            recordTime: String,
            id: Long = System.currentTimeMillis()
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
                appListJson = appJsonArray.toString()
            )
        }
    }
}
