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
     * 反序列化解析充电过程采样点列表。
     * 若历史数据中未持久化采样点（如早期版本生成的旧记录），则根据起止时间、电量、功率与温度等已知指标
     * 智能平滑补齐采样点集合，确保三合一折线走势图能够完整优雅呈现。
     *
     * @return 采样物理点集合 [List]
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
                            temperature = obj.optDouble("tp", 25.0).toFloat(),
                            voltageVolts = obj.optDouble("vt", 3.85).toFloat(),
                            currentMa = obj.optDouble("cm", 0.0).toFloat()
                        )
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (result.size >= 2) {
            return result
        }

        // 自愈合成平滑折线点集（针对未存采样点的历史老数据）
        val startTs = if (startTimestamp > 0L) startTimestamp else (endTimestamp - durationMs).coerceAtLeast(0L)
        val endTs = if (endTimestamp > 0L) endTimestamp else (startTs + durationMs.coerceAtLeast(60000L))
        val totalMs = (endTs - startTs).coerceAtLeast(60000L)
        val pointCount = 10

        val peakPower = if (maxPowerWatts > 0.05f) maxPowerWatts else (avgPowerWatts * 1.25f).coerceAtLeast(10f)
        val avgPower = if (avgPowerWatts > 0.05f) avgPowerWatts else 15f
        val peakTemp = if (maxTemperature > 20f) maxTemperature else 36f
        val baseTemp = (peakTemp - 4.5f).coerceAtLeast(26f)

        for (i in 0 until pointCount) {
            val progress = i / (pointCount - 1).toFloat()
            val ts = startTs + (totalMs * progress).toLong()
            val level = (startLevel + (endLevel - startLevel) * progress).toInt().coerceIn(0, 100)

            // 模拟快充前期功率爬升、中期均值、末期涓流缓降曲线
            val power = when {
                progress < 0.2f -> avgPower + (peakPower - avgPower) * (progress / 0.2f)
                progress < 0.7f -> peakPower - (peakPower - avgPower) * ((progress - 0.2f) / 0.5f) * 0.4f
                else -> avgPower * (1.0f - (progress - 0.7f) / 0.3f * 0.6f)
            }.coerceAtLeast(1.5f)

            // 温度随充电进行平缓上升至峰值后小幅回落
            val temp = if (progress < 0.8f) {
                baseTemp + (peakTemp - baseTemp) * (progress / 0.8f)
            } else {
                peakTemp - 1.0f * ((progress - 0.8f) / 0.2f)
            }

            val volt = 3.85f + 0.5f * progress
            val currMa = if (volt > 0.1f) (power * 1000f / volt) else 0f

            result.add(
                ChargingSamplePoint(
                    timestamp = ts,
                    powerWatts = power,
                    batteryLevel = level,
                    temperature = temp,
                    voltageVolts = volt,
                    currentMa = currMa
                )
            )
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

