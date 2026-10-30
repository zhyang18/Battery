package com.battery.analysis.model

import android.content.Context
import android.graphics.drawable.Drawable
import com.battery.analysis.R
import java.util.Locale
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
 * @property screenOffAwakeEnergyWh 息屏唤醒期间消耗能量（单位：瓦时 Wh）
 * @property screenOffDeepSleepEnergyWh 深度睡眠期间消耗能量（单位：瓦时 Wh）
 * @property screenOffAwakeDurationMs 息屏唤醒实际持续物理毫秒数
 * @property screenOffDeepSleepDurationMs 深度睡眠实际持续物理毫秒数
 * @property screenOffAwakeDrainMah 息屏唤醒期间消耗电荷量（单位：毫安时 mAh）
 * @property screenOffDeepSleepDrainMah 深度睡眠期间消耗电荷量（单位：毫安时 mAh）
 * @property isScreenOffDecomposedAvailable 息屏唤醒与休眠细分是否具备底层依据并成功分解
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
    val lastCheckpointTime: Long = 0L,
    val screenOffAwakeEnergyWh: Float = 0f,
    val screenOffDeepSleepEnergyWh: Float = 0f,
    val screenOffAwakeDurationMs: Long = 0L,
    val screenOffDeepSleepDurationMs: Long = 0L,
    val screenOffAwakeDrainMah: Float = 0f,
    val screenOffDeepSleepDrainMah: Float = 0f,
    val isScreenOffDecomposedAvailable: Boolean = true
) {

    /**
     * 将本条耗电历史记录反向还原为供耗电界面直接渲染的 [FullPowerDataPackage] 数据包。
     *
     * @param context 应用程序上下文，用于通过包名解析还原各应用专属图标
     * @return 还原构建的完整耗电数据包 [FullPowerDataPackage]
     */
    fun toFullPowerPackage(context: Context): FullPowerDataPackage {
        val pm = context.packageManager

        val totalEnergyInfo = com.battery.analysis.manager.PowerUsageManager.getInstance(context).getTotalEnergyInfo()
        val batteryTotalEnergyWh = totalEnergyInfo?.totalWh ?: com.battery.analysis.util.BatteryEnergyCalculator.calculateTotalEnergyWh(
            effectiveCapacityMah = com.battery.analysis.manager.PowerUsageManager.getInstance(context).getEffectiveDeviceCapacityMah(),
            nominalVoltageVolts = com.battery.analysis.util.BatteryEnergyCalculator.DEFAULT_NOMINAL_VOLTAGE_VOLTS
        )
        val snapshot = BatteryStatusSnapshot(
            levelPercent = levelPercent,
            voltageVolts = voltageVolts,
            temperature = temperature,
            energyWh = energyWh,
            isCharging = isCharging,
            totalEnergyWh = batteryTotalEnergyWh,
            totalEnergyInfo = totalEnergyInfo
        )

        val awakeWatts = if (screenOffAwakeDurationMs > 0L && screenOffAwakeEnergyWh > 0f) {
            (screenOffAwakeEnergyWh / (screenOffAwakeDurationMs.toDouble() / 3600000.0)).toFloat()
        } else {
            0f
        }
        val deepSleepWatts = if (screenOffDeepSleepDurationMs > 0L && screenOffDeepSleepEnergyWh > 0f) {
            (screenOffDeepSleepEnergyWh / (screenOffDeepSleepDurationMs.toDouble() / 3600000.0)).toFloat()
        } else {
            0f
        }

        val onDurationMs = getScreenOnDurationMs()
        val offDurationMs = getScreenOffDurationMs()
        val totalDurationMs = getDurationMs()

        val totalWh = batteryTotalEnergyWh?.takeIf { it > 0f }
        val offPercent = if (totalWh != null && screenOffEnergyWh > 0f) (screenOffEnergyWh / totalWh) * 100f else 0f
        val onPercent = if (totalWh != null && screenOnEnergyWh > 0f) (screenOnEnergyWh / totalWh) * 100f else 0f
        val awakePercent = if (isScreenOffDecomposedAvailable && totalWh != null && screenOffAwakeEnergyWh > 0f) (screenOffAwakeEnergyWh / totalWh) * 100f else 0f
        val deepSleepPercent = if (isScreenOffDecomposedAvailable && totalWh != null && screenOffDeepSleepEnergyWh > 0f) (screenOffDeepSleepEnergyWh / totalWh) * 100f else 0f

        val offDrain = if (screenOffAwakeDrainMah > 0f || screenOffDeepSleepDrainMah > 0f) {
            screenOffAwakeDrainMah + screenOffDeepSleepDrainMah
        } else if (screenOffEnergyWh > 0f) {
            (screenOffEnergyWh * 1000f) / com.battery.analysis.util.BatteryEnergyCalculator.DEFAULT_NOMINAL_VOLTAGE_VOLTS
        } else {
            0f
        }
        val onDrain = if (screenOnEnergyWh > 0f) (screenOnEnergyWh * 1000f) / com.battery.analysis.util.BatteryEnergyCalculator.DEFAULT_NOMINAL_VOLTAGE_VOLTS else 0f
        val awakeDrain = if (screenOffAwakeDrainMah > 0f) {
            screenOffAwakeDrainMah
        } else if (screenOffAwakeEnergyWh > 0f) {
            (screenOffAwakeEnergyWh * 1000f) / com.battery.analysis.util.BatteryEnergyCalculator.DEFAULT_NOMINAL_VOLTAGE_VOLTS
        } else {
            0f
        }
        val deepSleepDrain = if (screenOffDeepSleepDrainMah > 0f) {
            screenOffDeepSleepDrainMah
        } else if (screenOffDeepSleepEnergyWh > 0f) {
            (screenOffDeepSleepEnergyWh * 1000f) / com.battery.analysis.util.BatteryEnergyCalculator.DEFAULT_NOMINAL_VOLTAGE_VOLTS
        } else {
            0f
        }

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
            remainingLifeText = remainingCompositeText,
            screenOnDurationMs = onDurationMs,
            screenOffDurationMs = offDurationMs,
            totalDurationMs = totalDurationMs,
            screenOffAwakeEnergyWh = screenOffAwakeEnergyWh,
            screenOffDeepSleepEnergyWh = screenOffDeepSleepEnergyWh,
            screenOffAwakeDurationMs = screenOffAwakeDurationMs,
            screenOffDeepSleepDurationMs = screenOffDeepSleepDurationMs,
            screenOffAwakePowerWatts = awakeWatts,
            screenOffDeepSleepPowerWatts = deepSleepWatts,
            screenOffAwakePercent = awakePercent,
            screenOffDeepSleepPercent = deepSleepPercent,
            screenOffPercent = offPercent,
            screenOffDrainMah = offDrain,
            screenOffAwakeDrainMah = awakeDrain,
            screenOffDeepSleepDrainMah = deepSleepDrain,
            isScreenOffDecomposedAvailable = isScreenOffDecomposedAvailable,
            screenOnPercent = onPercent,
            screenOnDrainMah = onDrain,
            screenOffAwakeDurationText = if (screenOffAwakeDurationMs > 0L) com.battery.analysis.manager.PowerUsageManager.formatCompactDuration(screenOffAwakeDurationMs) else "",
            screenOffDeepSleepDurationText = if (screenOffDeepSleepDurationMs > 0L) com.battery.analysis.manager.PowerUsageManager.formatCompactDuration(screenOffDeepSleepDurationMs) else ""
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
                val avgTemp = obj.optDouble("avgTemp", 0.0).toFloat()
                val maxTemp = obj.optDouble("maxTemp", 0.0).toFloat()
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
     * 本地缓存的起止时间范围文本（通过 lazy 懒加载仅计算一次，消除列表滑动复用时的重复计算与 GC 开销）。
     */
    @delegate:Transient
    private val cachedFormattedTimeRange: String by lazy { computeFormattedTimeRange() }

    /**
     * 本地缓存的时长及电量变化摘要文本（通过 lazy 懒加载仅计算一次）。
     */
    @delegate:Transient
    private val cachedFormattedDurationAndLevel: String by lazy { computeFormattedDurationAndLevel() }

    /**
     * 本地缓存的展示亮屏时长文本（通过 lazy 懒加载仅计算一次）。
     */
    @delegate:Transient
    private val cachedDisplayScreenOnDuration: String by lazy { computeDisplayScreenOnDuration() }

    /**
     * 本地缓存的总持续时长毫秒数（通过 lazy 懒加载仅计算一次）。
     */
    @delegate:Transient
    private val cachedDurationMs: Long by lazy { parseDurationToMillis(totalDurationText) }

    /**
     * 本地缓存的亮屏持续时长毫秒数（通过 lazy 懒加载仅计算一次）。
     */
    @delegate:Transient
    private val cachedScreenOnDurationMs: Long by lazy { computeScreenOnDurationMs() }

    /**
     * 本地缓存的息屏持续时长毫秒数（通过 lazy 懒加载仅计算一次）。
     */
    @delegate:Transient
    private val cachedScreenOffDurationMs: Long by lazy { computeScreenOffDurationMs() }

    /**
     * 本地缓存的本次放电开始时刻初始电量百分比（通过 lazy 懒加载仅计算一次）。
     */
    @delegate:Transient
    private val cachedStartBatteryLevel: Int by lazy { computeStartBatteryLevel() }

    /**
     * 本地缓存的本次放电会话实际起始物理时间戳（毫秒，通过 lazy 懒加载仅计算一次）。
     */
    @delegate:Transient
    private val cachedEffectiveStartTimeMs: Long by lazy { computeEffectiveStartTimeMs() }

    /**
     * 本地缓存的本次放电会话实际结束物理时间戳（毫秒，通过 lazy 懒加载仅计算一次）。
     */
    @delegate:Transient
    private val cachedEffectiveEndTimeMs: Long by lazy { computeEffectiveEndTimeMs() }

    /**
     * 获取本次放电会话实际起始物理时间戳（单位：毫秒）。
     * 优先提取趋势点集首点物理时间戳，若无点集则采用拔电会话起始时间戳 [id]，
     * 若仍不可用则根据记录完成时间与持续时长反推。
     *
     * @return 实际起始物理时间戳（毫秒值）
     */
    fun getEffectiveStartTimeMs(): Long {
        return cachedEffectiveStartTimeMs
    }

    /**
     * 获取本次放电会话实际结束物理时间戳（单位：毫秒）。
     * 优先提取趋势点集末点物理时间戳；若无点集且存在增量检查点，则取 [lastCheckpointTime]；
     * 若已正式归档完成，则读取 [recordTime] 解析时间；
     * 兜底采用起始时间戳累加本次放电实际持续时长 [getDurationMs]，彻底杜绝将拔电起始时间当成结束时间。
     *
     * @return 实际结束物理时间戳（毫秒值）
     */
    fun getEffectiveEndTimeMs(): Long {
        return cachedEffectiveEndTimeMs
    }

    /**
     * 实际计算本次放电会话起始物理时间戳的内部实现。
     *
     * @return 起始物理时间戳（毫秒值）
     */
    private fun computeEffectiveStartTimeMs(): Long {
        val (fastStartTs, _, _) = extractFirstAndLastTsAndLvl(trendPointsJson)
        if (fastStartTs > 0L) {
            return fastStartTs
        }
        if (id > 0L) {
            return id
        }
        val endParsed = try {
            DATE_FORMAT_INPUT.get()?.parse(recordTime)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
        val durMs = getDurationMs()
        return if (endParsed > 0L && durMs > 0L) {
            (endParsed - durMs).coerceAtLeast(0L)
        } else {
            0L
        }
    }

    /**
     * 实际计算本次放电会话结束物理时间戳的内部实现。
     * 遵循物理时间戳优先原则：
     * 1. 优先提取趋势点集末点物理时间戳；
     * 2. 若存在晚于起始时间的检查点时间戳 [lastCheckpointTime]，则作为结束物理时间戳；
     * 3. 若拔电时间 [id] 与持续时长 [getDurationMs] 均有效，则结束时间严格为 id + durationMs；
     * 4. 若具备单独的 [lastCheckpointTime]，则取之；
     * 5. 仅当物理时间戳均缺失时，回退解析 [recordTime] 日期格式化字符串；
     * 6. 最终兜底返回拔电时间 [id] 或当前系统时间。
     *
     * @return 结束物理时间戳（毫秒值）
     */
    private fun computeEffectiveEndTimeMs(): Long {
        val (_, fastEndTs, _) = extractFirstAndLastTsAndLvl(trendPointsJson)
        if (fastEndTs > 0L) {
            return fastEndTs
        }
        if (lastCheckpointTime > id && lastCheckpointTime > 0L) {
            return lastCheckpointTime
        }
        val durMs = getDurationMs()
        if (id > 0L && durMs > 0L) {
            return id + durMs
        }
        if (lastCheckpointTime > 0L) {
            return lastCheckpointTime
        }
        val endParsed = try {
            DATE_FORMAT_INPUT.get()?.parse(recordTime)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
        if (endParsed > 0L) {
            return endParsed
        }
        if (id > 0L) {
            return id
        }
        return System.currentTimeMillis()
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
        return cachedFormattedTimeRange
    }

    /**
     * 实际计算起止时间范围文本的内部高效推导方法。
     *
     * @return 格式化后的起止时间范围字符串
     */
    private fun computeFormattedTimeRange(): String {
        val startTs = getEffectiveStartTimeMs()
        val endTs = getEffectiveEndTimeMs()

        val startDate = java.util.Date(startTs)
        val endDate = java.util.Date(endTs)

        val dayFmt = DATE_FORMAT_DAY_ONLY.get() ?: java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
        val yearFmt = DATE_FORMAT_YEAR_ONLY.get() ?: java.text.SimpleDateFormat("yyyy", java.util.Locale.getDefault())
        val fullFmt = DATE_FORMAT_FULL.get() ?: java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault())
        val timeFmt = DATE_FORMAT_TIME_ONLY.get() ?: java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        val monthDayFmt = DATE_FORMAT_MONTH_DAY.get() ?: java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault())

        return if (dayFmt.format(startDate) == dayFmt.format(endDate)) {
            "${fullFmt.format(startDate)}~${timeFmt.format(endDate)}"
        } else if (yearFmt.format(startDate) == yearFmt.format(endDate)) {
            "${fullFmt.format(startDate)}~${monthDayFmt.format(endDate)}"
        } else {
            "${fullFmt.format(startDate)}~${fullFmt.format(endDate)}"
        }
    }

    /**
     * 获取按设计图样式的时长及电量变化摘要文本（如 "46m · 76%~69%(-7%)"）。
     *
     * @return 格式化后的时长与电量变化字符串
     */
    fun getFormattedDurationAndLevel(): String {
        return cachedFormattedDurationAndLevel
    }

    /**
     * 实际计算时长及电量变化摘要文本的内部方法。
     *
     * @return 格式化后的时长与电量变化字符串
     */
    private fun computeFormattedDurationAndLevel(): String {
        val startLevel = getStartBatteryLevel()
        val endLevel = levelPercent

        val diff = endLevel - startLevel
        val diffSign = if (diff >= 0) "+$diff%" else "$diff%"
        val durText = if (totalDurationText.isNotBlank()) totalDurationText else "--"

        return "$durText · $startLevel%~$endLevel%($diffSign)"
    }

    /**
     * 获取本次放电记录的起始电池电量百分比（0~100）。
     *
     * @return 起始电量百分比
     */
    fun getStartBatteryLevel(): Int {
        return cachedStartBatteryLevel
    }

    /**
     * 实际提取起始电量百分比的内部计算方法。
     *
     * @return 起始电量百分比
     */
    private fun computeStartBatteryLevel(): Int {
        val (_, _, fastStartLvl) = extractFirstAndLastTsAndLvl(trendPointsJson)
        return fastStartLvl ?: levelPercent
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
     * 获取设计图右下角展示的功耗说明标签名称（支持国际化自适应）。
     *
     * @param context 应用程序上下文，用于读取本地化字符串
     * @return 标签文本（如 "平均亮屏功耗" 或 "平均放电功耗"）
     */
    fun getDisplayPowerLabel(context: Context? = null): String {
        val isZh = context?.resources?.configuration?.locales?.get(0)?.language?.startsWith("zh")
            ?: Locale.getDefault().language.startsWith("zh")
        return if (screenOnPowerWatts > 0f) {
            context?.getString(R.string.power_avg_screen_on_power) ?: if (isZh) "平均亮屏功耗" else "Screen-on Avg Power"
        } else {
            context?.getString(R.string.power_avg_discharge_power) ?: if (isZh) "平均放电功耗" else "Avg Discharge Power"
        }
    }

    /**
     * 获取设计图右下角展示的亮屏时间文本。
     * 优先使用实体中记录的亮屏时长文本；若为空则尝试从放电折线点集计算亮屏时长，若仍无法获取则返回未知占位符 "--"。
     *
     * @return 格式化后的亮屏时长文本（如 "1h55m"、"46m" 或 "--"）
     */
    fun getDisplayScreenOnDuration(): String {
        return cachedDisplayScreenOnDuration
    }

    /**
     * 实际计算亮屏时间展示文本的内部推导方法。
     *
     * @return 格式化后的亮屏时长文本
     */
    private fun computeDisplayScreenOnDuration(): String {
        if (screenOnDurationText.isNotBlank()) {
            return screenOnDurationText
        }
        if (trendPointsJson.isNotEmpty()) {
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
                            days > 0L -> String.format(java.util.Locale.getDefault(), "%02dd%02dh", days, hours)
                            hours > 0L -> String.format(java.util.Locale.getDefault(), "%02dh%02dm", hours, minutes)
                            else -> String.format(java.util.Locale.getDefault(), "%02dm%02ds", minutes, seconds)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return "--"
    }

    /**
     * 获取本次放电记录的总持续时长（单位：毫秒）。
     *
     * @return 转换后的总持续时长毫秒数
     */
    fun getDurationMs(): Long {
        return cachedDurationMs
    }

    /**
     * 获取本次放电记录的亮屏持续时长（单位：毫秒）。
     *
     * @return 转换后的亮屏持续时长毫秒数
     */
    fun getScreenOnDurationMs(): Long {
        return cachedScreenOnDurationMs
    }

    /**
     * 将本记录持久化的 [trendPointsJson] 字符串反序列化为放电走势与采样点列表。
     * 若 JSON 为空或解析失败则返回空列表。
     *
     * @return 解析得出的放电采样点列表 [PowerDischargePoint]
     */
    fun parseTrendPointsList(): List<PowerDischargePoint> {
        if (trendPointsJson.isBlank()) return emptyList()
        val list = mutableListOf<PowerDischargePoint>()
        try {
            val jsonArray = JSONArray(trendPointsJson)
            val len = jsonArray.length()
            val startTs = if (len > 0) jsonArray.getJSONObject(0).optLong("ts", 0L) else 0L
            for (i in 0 until len) {
                val obj = jsonArray.getJSONObject(i)
                val ts = obj.optLong("ts", 0L)
                val elapsedHours = if (startTs > 0L && ts >= startTs) {
                    ((ts - startTs).toDouble() / 3600000.0).toFloat()
                } else 0f
                list.add(
                    PowerDischargePoint(
                        timestamp = ts,
                        elapsedHours = elapsedHours,
                        batteryLevel = obj.optInt("lvl", 0),
                        voltageVolts = obj.optDouble("volt", 0.0).toFloat(),
                        temperature = obj.optDouble("temp", 0.0).toFloat(),
                        powerWatts = obj.optDouble("pwr", 0.0).toFloat(),
                        isScreenOn = obj.optBoolean("screenOn", true)
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    /**
     * 实际计算亮屏持续物理毫秒数的内部方法。
     *
     * @return 转换后的亮屏持续物理毫秒数
     */
    private fun computeScreenOnDurationMs(): Long {
        val directMs = parseDurationToMillis(screenOnDurationText)
        if (directMs > 0L) return directMs
        val displayDur = getDisplayScreenOnDuration()
        return if (displayDur != "--") parseDurationToMillis(displayDur) else 0L
    }

    /**
     * 获取本次放电记录的息屏持续时长（单位：毫秒）。
     * 优先解析直接记录的息屏时长文本；若为空则尝试通过唤醒与深度睡眠时长细分项累加；若仍为空则取总时长减去亮屏时长。
     *
     * @return 转换后的息屏持续物理时长毫秒数
     */
    fun getScreenOffDurationMs(): Long {
        return cachedScreenOffDurationMs
    }

    /**
     * 实际计算息屏持续物理毫秒数的内部方法。
     *
     * @return 转换后的息屏持续物理毫秒数
     */
    private fun computeScreenOffDurationMs(): Long {
        val directMs = parseDurationToMillis(screenOffDurationText)
        if (directMs > 0L) return directMs
        val decomposedMs = screenOffAwakeDurationMs + screenOffDeepSleepDurationMs
        if (decomposedMs > 0L) return decomposedMs
        val totalDur = getDurationMs()
        val onDur = getScreenOnDurationMs()
        return (totalDur - onDur).coerceAtLeast(0L)
    }

    /**
     * 将时长文本解析还原为毫秒数。使用高效匹配避免循环内重复构造正则。
     *
     * @param durationText 时长字符串（如 "1d2h"、"1h20m" 或 "46m30s"）
     * @return 对应的毫秒数
     */
    private fun parseDurationToMillis(durationText: String): Long {
        if (durationText.isBlank()) return 0L
        var totalMs = 0L
        try {
            REGEX_DAYS.find(durationText)?.groupValues?.get(1)?.toLongOrNull()?.let {
                totalMs += it * 86400000L
            }
            REGEX_HOURS.find(durationText)?.groupValues?.get(1)?.toLongOrNull()?.let {
                totalMs += it * 3600000L
            }
            REGEX_MINUTES.find(durationText)?.groupValues?.get(1)?.toLongOrNull()?.let {
                totalMs += it * 60000L
            }
            REGEX_SECONDS.find(durationText)?.groupValues?.get(1)?.toLongOrNull()?.let {
                totalMs += it * 1000L
            }
        } catch (_: Exception) {}
        return totalMs
    }



    companion object {
        private val REGEX_DAYS = "(\\d+)d".toRegex()
        private val REGEX_HOURS = "(\\d+)h".toRegex()
        private val REGEX_MINUTES = "(\\d+)m".toRegex()
        private val REGEX_SECONDS = "(\\d+)s".toRegex()

        private val DATE_FORMAT_FULL = ThreadLocal.withInitial {
            java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault())
        }
        private val DATE_FORMAT_TIME_ONLY = ThreadLocal.withInitial {
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        }
        private val DATE_FORMAT_DAY_ONLY = ThreadLocal.withInitial {
            java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
        }
        private val DATE_FORMAT_YEAR_ONLY = ThreadLocal.withInitial {
            java.text.SimpleDateFormat("yyyy", java.util.Locale.getDefault())
        }
        private val DATE_FORMAT_MONTH_DAY = ThreadLocal.withInitial {
            java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault())
        }
        private val DATE_FORMAT_INPUT = ThreadLocal.withInitial {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        }

        /**
         * 从放电折线 JSON 字符串中以极高效率扫描首尾点的采样时间戳及初始电量。
         * 避免在列表滑动与计算过程中把体积巨大的点集全量反序列化为树状结构，大幅降低 GC 压力。
         *
         * @param json 折线采样点 JSON 字符串
         * @return 包含起止时间戳及初始电量的三元组 (startTs, endTs, startLevel)
         */
        private fun extractFirstAndLastTsAndLvl(json: String): Triple<Long, Long, Int?> {
            if (json.isEmpty() || json.length < 10) return Triple(0L, 0L, null)
            try {
                // 1. 查找首个 "ts":
                val firstTsIdx = json.indexOf("\"ts\":")
                val startTs = if (firstTsIdx >= 0) {
                    val start = firstTsIdx + 5
                    var end = start
                    while (end < json.length && (json[end] in '0'..'9' || json[end] == '-')) {
                        end++
                    }
                    json.substring(start, end).toLongOrNull() ?: 0L
                } else 0L

                // 2. 查找首个 "lvl":
                val firstLvlIdx = json.indexOf("\"lvl\":")
                val startLvl = if (firstLvlIdx >= 0) {
                    val start = firstLvlIdx + 6
                    var end = start
                    while (end < json.length && (json[end] in '0'..'9' || json[end] == '-')) {
                        end++
                    }
                    json.substring(start, end).toIntOrNull()
                } else null

                // 3. 倒序查找末尾 "ts":
                val lastTsIdx = json.lastIndexOf("\"ts\":")
                val endTs = if (lastTsIdx >= 0) {
                    val start = lastTsIdx + 5
                    var end = start
                    while (end < json.length && (json[end] in '0'..'9' || json[end] == '-')) {
                        end++
                    }
                    json.substring(start, end).toLongOrNull() ?: 0L
                } else 0L

                if (startTs > 0L) {
                    return Triple(startTs, endTs, startLvl)
                }
            } catch (_: Exception) {}

            // 若轻量字符扫描异常，平滑回退至标准 JSONArray 解析，确保真实数据零遗漏
            try {
                val jsonArray = JSONArray(json)
                if (jsonArray.length() > 0) {
                    val first = jsonArray.getJSONObject(0)
                    val last = jsonArray.getJSONObject(jsonArray.length() - 1)
                    val sTs = first.optLong("ts", 0L)
                    val eTs = last.optLong("ts", 0L)
                    val sLvl = if (first.has("lvl")) first.optInt("lvl") else null
                    return Triple(sTs, eTs, sLvl)
                }
            } catch (_: Exception) {}

            return Triple(0L, 0L, null)
        }

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
                lastCheckpointTime = lastCheckpointTime,
                screenOffAwakeEnergyWh = overview.screenOffAwakeEnergyWh,
                screenOffDeepSleepEnergyWh = overview.screenOffDeepSleepEnergyWh,
                screenOffAwakeDurationMs = overview.screenOffAwakeDurationMs,
                screenOffDeepSleepDurationMs = overview.screenOffDeepSleepDurationMs,
                screenOffAwakeDrainMah = overview.screenOffAwakeDrainMah,
                screenOffDeepSleepDrainMah = overview.screenOffDeepSleepDrainMah,
                isScreenOffDecomposedAvailable = overview.isScreenOffDecomposedAvailable
            )
        }

        /**
         * 从 JSON 字符串反序列化构建 [PowerUsageRecord] 实例。
         *
         * @param jsonStr 待解析的 JSON 字符串
         * @return 构建成功的 [PowerUsageRecord] 实例，若解析失败返回 null
         */
        fun fromJsonString(jsonStr: String): PowerUsageRecord? {
            return try {
                val obj = JSONObject(jsonStr)
                PowerUsageRecord(
                    id = obj.optLong("id", System.currentTimeMillis()),
                    recordTime = obj.optString("recordTime", ""),
                    levelPercent = obj.optInt("levelPercent", 0),
                    voltageVolts = obj.optDouble("voltageVolts", 0.0).toFloat(),
                    temperature = obj.optDouble("temperature", 0.0).toFloat(),
                    energyWh = obj.optDouble("energyWh", 0.0).toFloat(),
                    isCharging = obj.optBoolean("isCharging", false),
                    avgPowerWatts = obj.optDouble("avgPowerWatts", 0.0).toFloat(),
                    screenOnPowerWatts = obj.optDouble("screenOnPowerWatts", 0.0).toFloat(),
                    screenOffPowerWatts = obj.optDouble("screenOffPowerWatts", 0.0).toFloat(),
                    screenOnDurationText = obj.optString("screenOnDurationText", ""),
                    screenOffDurationText = obj.optString("screenOffDurationText", ""),
                    totalDurationText = obj.optString("totalDurationText", ""),
                    remainingScreenOnText = obj.optString("remainingScreenOnText", ""),
                    remainingCompositeText = obj.optString("remainingCompositeText", ""),
                    remainingScreenOffText = obj.optString("remainingScreenOffText", ""),
                    isShizukuRealData = obj.optBoolean("isShizukuRealData", false),
                    appCount = obj.optInt("appCount", 0),
                    trendPointsJson = obj.optString("trendPointsJson", "[]"),
                    appListJson = obj.optString("appListJson", "[]"),
                    backgroundPowerWatts = obj.optDouble("backgroundPowerWatts", 0.0).toFloat(),
                    backgroundDurationText = obj.optString("backgroundDurationText", ""),
                    remainingBackgroundText = obj.optString("remainingBackgroundText", ""),
                    screenOnEnergyWh = obj.optDouble("screenOnEnergyWh", 0.0).toFloat(),
                    totalEnergyWh = obj.optDouble("totalEnergyWh", 0.0).toFloat(),
                    screenOffEnergyWh = obj.optDouble("screenOffEnergyWh", 0.0).toFloat(),
                    backgroundEnergyWh = obj.optDouble("backgroundEnergyWh", 0.0).toFloat(),
                    isCompleted = obj.optBoolean("isCompleted", true),
                    lastCheckpointTime = obj.optLong("lastCheckpointTime", 0L),
                    screenOffAwakeEnergyWh = obj.optDouble("screenOffAwakeEnergyWh", 0.0).toFloat(),
                    screenOffDeepSleepEnergyWh = obj.optDouble("screenOffDeepSleepEnergyWh", 0.0).toFloat(),
                    screenOffAwakeDurationMs = obj.optLong("screenOffAwakeDurationMs", 0L),
                    screenOffDeepSleepDurationMs = obj.optLong("screenOffDeepSleepDurationMs", 0L),
                    screenOffAwakeDrainMah = obj.optDouble("screenOffAwakeDrainMah", 0.0).toFloat(),
                    screenOffDeepSleepDrainMah = obj.optDouble("screenOffDeepSleepDrainMah", 0.0).toFloat(),
                    isScreenOffDecomposedAvailable = obj.optBoolean("isScreenOffDecomposedAvailable", true)
                )
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    /**
     * 将当前耗电记录实体序列化为 JSON 字符串，便于本地磁盘轻量缓存。
     *
     * @return 序列化后的 JSON 字符串
     */
    fun toJsonString(): String {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("recordTime", recordTime)
        obj.put("levelPercent", levelPercent)
        obj.put("voltageVolts", voltageVolts.toDouble())
        obj.put("temperature", temperature.toDouble())
        obj.put("energyWh", energyWh.toDouble())
        obj.put("isCharging", isCharging)
        obj.put("avgPowerWatts", avgPowerWatts.toDouble())
        obj.put("screenOnPowerWatts", screenOnPowerWatts.toDouble())
        obj.put("screenOffPowerWatts", screenOffPowerWatts.toDouble())
        obj.put("screenOnDurationText", screenOnDurationText)
        obj.put("screenOffDurationText", screenOffDurationText)
        obj.put("totalDurationText", totalDurationText)
        obj.put("remainingScreenOnText", remainingScreenOnText)
        obj.put("remainingCompositeText", remainingCompositeText)
        obj.put("remainingScreenOffText", remainingScreenOffText)
        obj.put("isShizukuRealData", isShizukuRealData)
        obj.put("appCount", appCount)
        obj.put("trendPointsJson", trendPointsJson)
        obj.put("appListJson", appListJson)
        obj.put("backgroundPowerWatts", backgroundPowerWatts.toDouble())
        obj.put("backgroundDurationText", backgroundDurationText)
        obj.put("remainingBackgroundText", remainingBackgroundText)
        obj.put("screenOnEnergyWh", screenOnEnergyWh.toDouble())
        obj.put("totalEnergyWh", totalEnergyWh.toDouble())
        obj.put("screenOffEnergyWh", screenOffEnergyWh.toDouble())
        obj.put("backgroundEnergyWh", backgroundEnergyWh.toDouble())
        obj.put("isCompleted", isCompleted)
        obj.put("lastCheckpointTime", lastCheckpointTime)
        obj.put("screenOffAwakeEnergyWh", screenOffAwakeEnergyWh.toDouble())
        obj.put("screenOffDeepSleepEnergyWh", screenOffDeepSleepEnergyWh.toDouble())
        obj.put("screenOffAwakeDurationMs", screenOffAwakeDurationMs)
        obj.put("screenOffDeepSleepDurationMs", screenOffDeepSleepDurationMs)
        obj.put("screenOffAwakeDrainMah", screenOffAwakeDrainMah.toDouble())
        obj.put("screenOffDeepSleepDrainMah", screenOffDeepSleepDrainMah.toDouble())
        obj.put("isScreenOffDecomposedAvailable", isScreenOffDecomposedAvailable)
        return obj.toString()
    }
}
