package com.battery.analysis.model

/**
 * 充电会话全景统计摘要数据实体类。
 * 汇总从连接充电器到断开充电或当前时刻的完整统计指标，包括充入电量、能量、均值与极值等。
 *
 * @property startTimestamp 充电开始时间戳（毫秒）
 * @property endTimestamp 充电结束或最近更新时间戳（毫秒）
 * @property startLevel 开始充电时的电池电量百分比（0~100）
 * @property currentLevel 当前或结束时的电池电量百分比（0~100）
 * @property maxPowerWatts 充电过程中的峰值功率（单位：W）
 * @property avgPowerWatts 充电过程中的平均功率（单位：W）
 * @property maxTemperature 充电过程中的最高电池温度（单位：℃）
 * @property avgTemperature 充电过程中的平均电池温度（单位：℃）
 * @property chargedEnergyWh 本次充电累计充入能量（单位：Wh）
 * @property chargeType 充电连接类型描述（如 "交流快充"、"USB充电"、"无线充电" 等）
 * @property isCharging 当前是否仍处于充电连接状态
 * @property screenOffDurationMs 充电过程中处于息屏状态的累计时长（毫秒）
 * @property screenOffLevelGain 息屏期间充入的电池电量百分比增量
 * @property screenOffEnergyWh 息屏期间累计充入的能量（单位：Wh）
 */
data class ChargingSessionSummary(
    val startTimestamp: Long = 0L,
    val endTimestamp: Long = 0L,
    val startLevel: Int = 0,
    val currentLevel: Int = 0,
    val maxPowerWatts: Float = 0f,
    val avgPowerWatts: Float = 0f,
    val maxTemperature: Float = 0f,
    val avgTemperature: Float = 0f,
    val chargedEnergyWh: Float = 0f,
    val chargeType: String = "",
    val isCharging: Boolean = false,
    val screenOffDurationMs: Long = 0L,
    val screenOffLevelGain: Int = 0,
    val screenOffEnergyWh: Float = 0f
) {
    /**
     * 计算本次充电累计已持续的时长（毫秒）。
     *
     * @return 充电持续时长（毫秒）
     */
    fun getDurationMs(): Long {
        return (endTimestamp - startTimestamp).coerceAtLeast(0L)
    }

    /**
     * 格式化输出充电已持续时长的友好文本（如 "01:25:30" 或 "15分20秒"）。
     *
     * @return 格式化后的时长文本
     */
    fun getFormattedDuration(): String {
        val totalSec = getDurationMs() / 1000L
        val hours = totalSec / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val seconds = totalSec % 60L
        return if (hours > 0) {
            String.format(java.util.Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(java.util.Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    /**
     * 获取本次充电增加的电量百分比净增量。
     *
     * @return 电量增量百分比（例如 +35）
     */
    fun getLevelGain(): Int {
        return (currentLevel - startLevel).coerceAtLeast(0)
    }
}
