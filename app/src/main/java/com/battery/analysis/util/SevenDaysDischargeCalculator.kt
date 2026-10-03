package com.battery.analysis.util

import android.content.Context
import com.battery.analysis.db.PowerUsageDbHelper
import com.battery.analysis.manager.PowerOverviewStats
import com.battery.analysis.model.SevenDaysDischargeStats

/**
 * 过去 7 天核心放电统计指标与充满电可用时长物理计算器。
 * 严格基于设备本地数据库真实历史快照记录与当前正在进行的放电会话，
 * 统计 7 天滑动窗口（7 * 24h）内的能量积分与持续物理时长，
 * 严谨换算亮屏、息屏与全局 7 天加权平均放电速率（%/h）并推导充满电理论使用时长。
 */
object SevenDaysDischargeCalculator {

    /**
     * 7 天物理毫秒时长常量（7 * 24 * 3600 * 1000 毫秒）。
     */
    private const val SEVEN_DAYS_MS = 7L * 24L * 3600L * 1000L

    /**
     * 计算过去 7 天的亮屏、息屏与全局加权放电速度及充满电可用时长。
     *
     * @param context 应用程序上下文
     * @param currentOverview 当前正在进行的放电会话统计数据（可为空）
     * @param totalCapacityWh 电池标称总能量容量（单位：Wh）
     * @param baseTimestamp 统计基准时间戳（毫秒，默认当前系统时间）
     * @return 过去 7 天统计计算结果实体 [SevenDaysDischargeStats]
     */
    fun calculateSevenDaysStats(
        context: Context,
        currentOverview: PowerOverviewStats?,
        totalCapacityWh: Float?,
        baseTimestamp: Long = System.currentTimeMillis()
    ): SevenDaysDischargeStats {
        if (totalCapacityWh == null || totalCapacityWh <= 0f) {
            return SevenDaysDischargeStats()
        }

        val startTimeThreshold = baseTimestamp - SEVEN_DAYS_MS
        val dbHelper = PowerUsageDbHelper.getInstance(context)
        val allRecords = dbHelper.getAllRecords()

        // 筛选出在 7 天时间窗口内的有效放电记录（排除充电异常记录）
        val recentRecords = allRecords.filter { record ->
            !record.isCharging && record.id in startTimeThreshold..(baseTimestamp + 60_000L)
        }

        var totalOnEnergyWh = 0.0
        var totalOnDurationMs = 0L

        var totalOffEnergyWh = 0.0
        var totalOffDurationMs = 0L

        var totalGlobalEnergyWh = 0.0
        var totalGlobalDurationMs = 0L

        // 1. 遍历历史归档记录进行加权累加
        for (record in recentRecords) {
            // 若历史记录中包含未完结的检查点草稿，且当前存在实时会话 currentOverview，则跳过旧草稿以当前最新数据为准
            if (!record.isCompleted && currentOverview != null) {
                continue
            }

            // 全局放电指标
            val gDur = record.getDurationMs()
            val gEnergy = if (record.totalEnergyWh > 0f) {
                record.totalEnergyWh.toDouble()
            } else if (record.avgPowerWatts > 0f && gDur > 0L) {
                record.avgPowerWatts.toDouble() * (gDur / 3600000.0)
            } else {
                0.0
            }
            if (gDur > 0L && gEnergy > 0.0) {
                totalGlobalDurationMs += gDur
                totalGlobalEnergyWh += gEnergy
            }

            // 亮屏放电指标
            val onDur = record.getScreenOnDurationMs()
            val onEnergy = if (record.screenOnEnergyWh > 0f) {
                record.screenOnEnergyWh.toDouble()
            } else if (record.screenOnPowerWatts > 0f && onDur > 0L) {
                record.screenOnPowerWatts.toDouble() * (onDur / 3600000.0)
            } else {
                0.0
            }
            if (onDur > 0L && onEnergy > 0.0) {
                totalOnDurationMs += onDur
                totalOnEnergyWh += onEnergy
            }

            // 息屏放电指标
            val offDur = record.getScreenOffDurationMs()
            val offEnergy = if (record.screenOffEnergyWh > 0f) {
                record.screenOffEnergyWh.toDouble()
            } else if (record.screenOffPowerWatts > 0f && offDur > 0L) {
                record.screenOffPowerWatts.toDouble() * (offDur / 3600000.0)
            } else {
                0.0
            }
            if (offDur > 0L && offEnergy > 0.0) {
                totalOffDurationMs += offDur
                totalOffEnergyWh += offEnergy
            }
        }

        // 2. 累加当前正在进行的活跃放电会话
        if (currentOverview != null) {
            val curGDur = if (currentOverview.totalDurationMs > 0L) currentOverview.totalDurationMs else parseDurationTextToMs(currentOverview.totalDurationText)
            val curGEnergy = if (currentOverview.totalEnergyWh > 0f) {
                currentOverview.totalEnergyWh.toDouble()
            } else if (currentOverview.avgPowerWatts > 0f && curGDur > 0L) {
                currentOverview.avgPowerWatts.toDouble() * (curGDur / 3600000.0)
            } else {
                0.0
            }
            if (curGDur > 0L && curGEnergy > 0.0) {
                totalGlobalDurationMs += curGDur
                totalGlobalEnergyWh += curGEnergy
            }

            val curOnDur = if (currentOverview.screenOnDurationMs > 0L) currentOverview.screenOnDurationMs else parseDurationTextToMs(currentOverview.screenOnDurationText)
            val curOnEnergy = if (currentOverview.screenOnEnergyWh > 0f) {
                currentOverview.screenOnEnergyWh.toDouble()
            } else if (currentOverview.screenOnPowerWatts > 0f && curOnDur > 0L) {
                currentOverview.screenOnPowerWatts.toDouble() * (curOnDur / 3600000.0)
            } else {
                0.0
            }
            if (curOnDur > 0L && curOnEnergy > 0.0) {
                totalOnDurationMs += curOnDur
                totalOnEnergyWh += curOnEnergy
            }

            val curOffDur = if (currentOverview.screenOffDurationMs > 0L) currentOverview.screenOffDurationMs else parseDurationTextToMs(currentOverview.screenOffDurationText)
            val curOffEnergy = if (currentOverview.screenOffEnergyWh > 0f) {
                currentOverview.screenOffEnergyWh.toDouble()
            } else if (currentOverview.screenOffPowerWatts > 0f && curOffDur > 0L) {
                currentOverview.screenOffPowerWatts.toDouble() * (curOffDur / 3600000.0)
            } else {
                0.0
            }
            if (curOffDur > 0L && curOffEnergy > 0.0) {
                totalOffDurationMs += curOffDur
                totalOffEnergyWh += curOffEnergy
            }
        }

        // 3. 计算 7 天加权平均放电速率（%/h）与充满电可用时长（毫秒）
        val screenOnRate = computeRate(totalOnEnergyWh, totalOnDurationMs, totalCapacityWh)
        val screenOffRate = computeRate(totalOffEnergyWh, totalOffDurationMs, totalCapacityWh)
        val globalRate = computeRate(totalGlobalEnergyWh, totalGlobalDurationMs, totalCapacityWh)

        val fullOnDuration = computeFullDurationMs(screenOnRate)
        val fullOffDuration = computeFullDurationMs(screenOffRate)
        val fullGlobalDuration = computeFullDurationMs(globalRate)

        // 实际有效统计时长（有多少显示多少，最长不超过 7 天）
        val actualOnDur = if (screenOnRate != null && totalOnDurationMs > 0L) totalOnDurationMs.coerceAtMost(SEVEN_DAYS_MS) else null
        val actualOffDur = if (screenOffRate != null && totalOffDurationMs > 0L) totalOffDurationMs.coerceAtMost(SEVEN_DAYS_MS) else null
        val actualGlobalDur = if (globalRate != null && totalGlobalDurationMs > 0L) totalGlobalDurationMs.coerceAtMost(SEVEN_DAYS_MS) else null

        return SevenDaysDischargeStats(
            screenOnDischargeRatePercentPerHour = screenOnRate,
            screenOffDischargeRatePercentPerHour = screenOffRate,
            globalDischargeRatePercentPerHour = globalRate,
            screenOnDurationMs = actualOnDur,
            screenOffDurationMs = actualOffDur,
            globalDurationMs = actualGlobalDur,
            fullChargeScreenOnDurationMs = fullOnDuration,
            fullChargeScreenOffDurationMs = fullOffDuration,
            fullChargeGlobalDurationMs = fullGlobalDuration
        )
    }

    /**
     * 计算指定能耗与时长下的每小时放电百分比速率（%/h）。
     *
     * @param totalEnergyWh 消耗总能量（单位：Wh）
     * @param durationMs 对应总时长（单位：毫秒）
     * @param totalCapacityWh 电池标称总能量容量（单位：Wh）
     * @return 每小时放电速率百分比（%/h），无数据或无效时返回 null
     */
    private fun computeRate(totalEnergyWh: Double, durationMs: Long, totalCapacityWh: Float): Float? {
        if (durationMs <= 0L || totalEnergyWh <= 0.0 || totalCapacityWh <= 0f) {
            return null
        }
        val durationHours = durationMs / 3600000.0
        val avgPowerWatts = totalEnergyWh / durationHours
        val rate = (avgPowerWatts / totalCapacityWh) * 100.0
        return if (rate > 0.0) rate.toFloat() else null
    }

    /**
     * 根据放电速率严谨推算充满电（100%）理论可用物理时长（毫秒）。
     *
     * @param ratePercentPerHour 每小时放电百分比（%/h）
     * @return 充满电理论续航物理毫秒数，速率缺失或无效时返回 null
     */
    private fun computeFullDurationMs(ratePercentPerHour: Float?): Long? {
        if (ratePercentPerHour == null || ratePercentPerHour <= 0f) {
            return null
        }
        val fullHours = 100.0 / ratePercentPerHour
        val fullMs = (fullHours * 3600.0 * 1000.0).toLong()
        return if (fullMs > 0L) fullMs else null
    }

    /**
     * 将紧凑时长字符串解析还原为物理毫秒数。
     *
     * @param text 格式化时长字符串
     * @return 解析得到的物理毫秒数
     */
    private fun parseDurationTextToMs(text: String): Long {
        if (text.isBlank() || text == "--") return 0L
        var totalMs = 0L
        val dMatch = Regex("(\\d+)d").find(text)
        val hMatch = Regex("(\\d+)h").find(text)
        val mMatch = Regex("(\\d+)m").find(text)
        val sMatch = Regex("(\\d+)s").find(text)

        dMatch?.groupValues?.get(1)?.toLongOrNull()?.let { totalMs += it * 86400000L }
        hMatch?.groupValues?.get(1)?.toLongOrNull()?.let { totalMs += it * 3600000L }
        mMatch?.groupValues?.get(1)?.toLongOrNull()?.let { totalMs += it * 60000L }
        sMatch?.groupValues?.get(1)?.toLongOrNull()?.let { totalMs += it * 1000L }
        return totalMs
    }
}
