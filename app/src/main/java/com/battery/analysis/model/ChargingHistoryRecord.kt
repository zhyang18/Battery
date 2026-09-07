package com.battery.analysis.model

/**
 * 充电历史快照记录实体类。
 * 用于本地 SQLite 持久化记录用户每次充电会话的完整历史账本，包括起止时间、电量增量、充入能量、均值/峰值物理参数及息屏统计指标。
 *
 * @property id 唯一自增主键或时间戳主键
 * @property recordTime 记录生成时刻的友好格式化字符串（如 "2026-09-07 11:39:20"）
 * @property startTimestamp 充电开始时间戳（毫秒）
 * @property endTimestamp 充电结束时间戳（毫秒）
 * @property durationMs 充电总持续时长（毫秒）
 * @property startLevel 接入充电时的电量百分比（0~100）
 * @property endLevel 断开充电时的电量百分比（0~100）
 * @property levelGain 本次充电充入的电量净增量百分比
 * @property chargedEnergyWh 本次充电累计充入的能量（单位：Wh）
 * @property avgPowerWatts 充电过程中的平均功率（单位：W）
 * @property maxPowerWatts 充电过程中的峰值功率（单位：W）
 * @property maxTemperature 充电过程中的最高电池温度（单位：℃）
 * @property chargeType 充电连接类型描述（如 "交流快充"、"USB充电" 等）
 * @property screenOffDurationMs 息屏充电持续时长（毫秒）
 * @property screenOffLevelGain 息屏充电充入电量百分比
 * @property screenOffEnergyWh 息屏充电充入能量（单位：Wh）
 */
data class ChargingHistoryRecord(
    val id: Long = System.currentTimeMillis(),
    val recordTime: String,
    val startTimestamp: Long,
    val endTimestamp: Long,
    val durationMs: Long,
    val startLevel: Int,
    val endLevel: Int,
    val levelGain: Int,
    val chargedEnergyWh: Float,
    val avgPowerWatts: Float,
    val maxPowerWatts: Float,
    val maxTemperature: Float,
    val chargeType: String,
    val screenOffDurationMs: Long = 0L,
    val screenOffLevelGain: Int = 0,
    val screenOffEnergyWh: Float = 0f
) {
    /**
     * 格式化输出本次充电总持续时长的友好文本（如 "15m20s" 或 "1h20m15s"）。
     *
     * @return 格式化后的持续时长字符串
     */
    fun getFormattedDuration(): String {
        val totalSec = (durationMs / 1000L).coerceAtLeast(0L)
        val hours = totalSec / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val seconds = totalSec % 60L
        return if (hours > 0L) {
            "${hours}h${minutes}m${seconds}s"
        } else {
            "${minutes}m${seconds}s"
        }
    }

    /**
     * 格式化输出息屏充电时长的友好文本（如 "10m15s"）。
     *
     * @return 格式化后的息屏持续时长字符串
     */
    fun getFormattedScreenOffDuration(): String {
        val totalSec = (screenOffDurationMs / 1000L).coerceAtLeast(0L)
        val hours = totalSec / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val seconds = totalSec % 60L
        return if (hours > 0L) {
            "${hours}h${minutes}m${seconds}s"
        } else {
            "${minutes}m${seconds}s"
        }
    }

    /**
     * 解析并获取本次充电记录的起止时间范围文本（包含开始时间与结束时间）。
     * 若起止为同一天则显示为 "yyyy/MM/dd HH:mm ~ HH:mm"；若跨天则显示为 "yyyy/MM/dd HH:mm ~ yyyy/MM/dd HH:mm"。
     *
     * @return 格式化后的起止时间范围字符串
     */
    fun getFormattedTimeRange(): String {
        val dateFormat = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault())
        val timeOnlyFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        val dayOnlyFormat = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())

        val startTs = if (startTimestamp > 0L) startTimestamp else (endTimestamp - durationMs).coerceAtLeast(0L)
        val endTs = if (endTimestamp > 0L) endTimestamp else id

        val startDate = java.util.Date(startTs)
        val endDate = java.util.Date(endTs)

        return if (dayOnlyFormat.format(startDate) == dayOnlyFormat.format(endDate)) {
            "${dateFormat.format(startDate)}~${timeOnlyFormat.format(endDate)}"
        } else {
            "${dateFormat.format(startDate)}~${dateFormat.format(endDate)}"
        }
    }

    /**
     * 获取按设计图样式的时长及电量增量摘要文本（如 "25m · 20%~85%(+65%)"）。
     *
     * @return 格式化后的时长与电量变化字符串
     */
    fun getFormattedDurationAndGain(): String {
        val totalSec = (durationMs / 1000L).coerceAtLeast(0L)
        val hours = totalSec / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val shortDur = if (hours > 0L) {
            "${hours}h${minutes}m"
        } else {
            "${minutes}m"
        }
        val gainSign = if (levelGain >= 0) "+$levelGain%" else "$levelGain%"
        return "$shortDur · $startLevel%~$endLevel%($gainSign)"
    }

    /**
     * 获取设计图右下角展示的功耗说明标签名称。
     *
     * @return 标签文本（"平均充电功率"）
     */
    fun getDisplayPowerLabel(): String {
        return "平均充电功率"
    }
}

