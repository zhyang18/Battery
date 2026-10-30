package com.battery.analysis.model

import android.content.Context
import com.battery.analysis.R
import java.util.Locale
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
            String.format(java.util.Locale.getDefault(), "%02dh%02dm%02ds", hours, minutes, seconds)
        } else {
            String.format(java.util.Locale.getDefault(), "%02dm%02ds", minutes, seconds)
        }
    }

    /**
     * 计算亮屏充电持续时长（毫秒）。
     * 由总充电时长与息屏充电时长的物理差值计算得到。
     *
     * @return 亮屏充电持续时长（毫秒）
     */
    fun getScreenOnDurationMs(): Long {
        return (durationMs - screenOffDurationMs).coerceAtLeast(0L)
    }

    /**
     * 格式化输出亮屏充电时长的友好文本（如 "10m15s" 或 "01h20m15s"）。
     *
     * @return 格式化后的亮屏持续时长字符串
     */
    fun getFormattedScreenOnDuration(): String {
        val totalSec = (getScreenOnDurationMs() / 1000L).coerceAtLeast(0L)
        val hours = totalSec / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val seconds = totalSec % 60L
        return if (hours > 0L) {
            String.format(java.util.Locale.getDefault(), "%02dh%02dm%02ds", hours, minutes, seconds)
        } else {
            String.format(java.util.Locale.getDefault(), "%02dm%02ds", minutes, seconds)
        }
    }

    /**
     * 获取经过物理守恒与真实采样点自愈校准后的真实结束电量百分比。
     * 当历史数据因进程断电异常被放电电量污染时，依据真实采样点终点值与息屏充入物理增量自愈恢复真实结束电量。
     *
     * @return 校准后的真实结束电量百分比
     */
    fun getEffectiveEndLevel(): Int {
        val points = getSamplePoints()
        val sampleLastLevel = points.lastOrNull()?.batteryLevel ?: -1
        val sampleMaxLevel = if (points.isNotEmpty()) points.maxOf { it.batteryLevel } else -1
        val candidateLevel = maxOf(endLevel, sampleLastLevel, sampleMaxLevel)
        return maxOf(candidateLevel, startLevel + screenOffLevelGain).coerceIn(0, 100)
    }

    /**
     * 获取经过校准后的总充入电量增量百分比。
     * 确保总充入电量不低于息屏充入电量，忠实反映真实充电增量。
     *
     * @return 真实充入电量增量百分比
     */
    fun getEffectiveLevelGain(): Int {
        val effectiveEnd = getEffectiveEndLevel()
        val computedGain = (effectiveEnd - startLevel).coerceAtLeast(0)
        return maxOf(levelGain, computedGain, screenOffLevelGain)
    }

    /**
     * 计算亮屏充电充入电量百分比。
     * 由总充入电量增量与息屏充入电量增量的差值计算得出，确保符合物理守恒（非负）。
     *
     * @return 亮屏充电充入电量增量百分比
     */
    fun getScreenOnLevelGain(): Int {
        val effectiveGain = getEffectiveLevelGain()
        return (effectiveGain - screenOffLevelGain).coerceAtLeast(0)
    }

    /**
     * 格式化输出息屏充电时长的友好文本（如 "10m15s" 或 "01h20m15s"）。
     *
     * @return 格式化后的息屏持续时长字符串
     */
    fun getFormattedScreenOffDuration(): String {
        val totalSec = (screenOffDurationMs / 1000L).coerceAtLeast(0L)
        val hours = totalSec / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val seconds = totalSec % 60L
        return if (hours > 0L) {
            String.format(java.util.Locale.getDefault(), "%02dh%02dm%02ds", hours, minutes, seconds)
        } else {
            String.format(java.util.Locale.getDefault(), "%02dm%02ds", minutes, seconds)
        }
    }

    /**
     * 本地缓存的起止时间范围文本（通过 lazy 懒加载仅计算一次，消除列表滑动复用时的重复计算与 GC 开销）。
     */
    @delegate:Transient
    private val cachedFormattedTimeRange: String by lazy { computeFormattedTimeRange() }

    /**
     * 本地缓存的时长及电量增量摘要文本（通过 lazy 懒加载仅计算一次）。
     */
    @delegate:Transient
    private val cachedFormattedDurationAndGain: String by lazy { computeFormattedDurationAndGain() }

    /**
     * 解析并获取本次充电记录的起止时间范围文本（包含开始时间与结束时间）。
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
        val startTs = if (startTimestamp > 0L) startTimestamp else (endTimestamp - durationMs).coerceAtLeast(0L)
        val endTs = if (endTimestamp > 0L) endTimestamp else id

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
     * 获取按设计图样式的时长及电量增量摘要文本（如 "25m · 20%~85%(+65%)" 或 "01h20m · 20%~85%(+65%)"）。
     *
     * @return 格式化后的时长与电量变化字符串
     */
    fun getFormattedDurationAndGain(): String {
        return cachedFormattedDurationAndGain
    }

    /**
     * 实际计算时长及电量增量摘要文本的内部方法。
     *
     * @return 格式化后的时长与电量变化字符串
     */
    private fun computeFormattedDurationAndGain(): String {
        val totalSec = (durationMs / 1000L).coerceAtLeast(0L)
        val hours = totalSec / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val shortDur = if (hours > 0L) {
            String.format(java.util.Locale.getDefault(), "%02dh%02dm", hours, minutes)
        } else {
            String.format(java.util.Locale.getDefault(), "%02dm", minutes)
        }
        val effectiveEnd = getEffectiveEndLevel()
        val effectiveGain = getEffectiveLevelGain()
        val gainSign = if (effectiveGain >= 0) "+$effectiveGain%" else "$effectiveGain%"
        return "$shortDur · $startLevel%~$effectiveEnd%($gainSign)"
    }

    /**
     * 获取设计图右下角展示的功耗说明标签名称（支持国际化自适应）。
     *
     * @param context 应用程序上下文，用于读取本地化字符串
     * @return 标签文本（如 "平均充电功率" 或 "Avg Charging Power"）
     */
    fun getDisplayPowerLabel(context: Context? = null): String {
        val isZh = context?.resources?.configuration?.locales?.get(0)?.language?.startsWith("zh")
            ?: Locale.getDefault().language.startsWith("zh")
        return context?.getString(R.string.charging_avg_power_label) ?: if (isZh) "平均充电功率" else "Avg Charging Power"
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

    /**
     * 获取符合当前系统或界面语言环境的充电连接类型本地化显示文本。
     *
     * @param context Android 应用程序上下文
     * @return 本地化后的充电类型展示文本（如 "AC Fast Charge"、"交流快充"）
     */
    fun getLocalizedChargeType(context: Context): String {
        return getLocalizedChargeType(context, chargeType)
    }

    companion object {
        /**
         * 将原始充电连接类型描述转化为符合当前语言环境的本地化显示文本。
         *
         * @param context Android 上下文环境
         * @param rawChargeType 底层记录的原始充电类型字符串（如 "交流快充"、"USB充电" 等）
         * @return 本地化后的友好显示文本（如 "AC Fast Charge"、"USB Charging"）
         */
        fun getLocalizedChargeType(context: Context, rawChargeType: String): String {
            val trimmed = rawChargeType.trim()
            return when {
                trimmed.contains("交流") || trimmed.contains("AC", ignoreCase = true) -> context.getString(R.string.charge_type_ac_fast)
                trimmed.contains("USB", ignoreCase = true) -> context.getString(R.string.charge_type_usb)
                trimmed.contains("无线") || trimmed.contains("Wireless", ignoreCase = true) -> context.getString(R.string.charge_type_wireless)
                trimmed.contains("外部") || trimmed.contains("External", ignoreCase = true) -> context.getString(R.string.charge_type_external)
                trimmed.contains("未充电") || trimmed.contains("Discharging", ignoreCase = true) -> context.getString(R.string.charge_type_none)
                trimmed.isEmpty() -> context.getString(R.string.charge_type_external)
                else -> rawChargeType
            }
        }
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

