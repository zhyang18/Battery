package com.battery.analysis.model

import org.json.JSONArray
import org.json.JSONObject

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
 * @property samplePointsJson 充电全过程采样物理点序列化 JSON 字符串（包含功率、电量、温度等）
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
    val screenOffEnergyWh: Float = 0f,
    val samplePointsJson: String = ""
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

    /**
     * 获取充电采样的真实物理点列表。
     * 若未持久化采样点数据，则返回真实空列表，严禁伪造生成模拟曲线。
     *
     * @return 采样物理点真实集合 [List]
     */
    fun getSamplePoints(): List<ChargingSamplePoint> {
        val result = mutableListOf<ChargingSamplePoint>()
        if (samplePointsJson.isNotEmpty()) {
            try {
                val array = JSONArray(samplePointsJson)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    result.add(
                        ChargingSamplePoint(
                            timestamp = obj.optLong("ts", 0L),
                            powerWatts = obj.optDouble("pw", 0.0).toFloat(),
                            batteryLevel = obj.optInt("lv", 0),
                            temperature = obj.optDouble("tp", 0.0).toFloat(),
                            voltageVolts = obj.optDouble("vt", 0.0).toFloat(),
                            currentMa = obj.optDouble("cm", 0.0).toFloat(),
                            isScreenOn = obj.optBoolean("so", true)
                        )
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return result
    }

    companion object {
        /**
         * 将内存中的充电采样物理点列表序列化压缩为 JSON 字符串以持久化存储。
         * 若采样点数量较多，将进行均匀等距抽样（最多保留 200 个最具代表性节点），兼顾存储能耗与折线精细度。
         *
         * @param points 待序列化的采样点原始集合
         * @return 序列化生成的 JSON Array 字符串
         */
        fun pointsToJson(points: List<ChargingSamplePoint>): String {
            if (points.isEmpty()) return ""
            return try {
                val array = JSONArray()
                val targetPoints = if (points.size > 200) {
                    val sampled = mutableListOf<ChargingSamplePoint>()
                    val step = (points.size - 1).toFloat() / 199f
                    for (i in 0 until 200) {
                        val index = (i * step).toInt().coerceIn(0, points.size - 1)
                        sampled.add(points[index])
                    }
                    sampled
                } else {
                    points
                }

                for (p in targetPoints) {
                    val obj = JSONObject().apply {
                        put("ts", p.timestamp)
                        put("pw", p.powerWatts.toDouble())
                        put("lv", p.batteryLevel)
                        put("tp", p.temperature.toDouble())
                        put("vt", p.voltageVolts.toDouble())
                        put("cm", p.currentMa.toDouble())
                        put("so", p.isScreenOn)
                    }
                    array.put(obj)
                }
                array.toString()
            } catch (e: Exception) {
                e.printStackTrace()
                ""
            }
        }
    }
}

