package com.battery.analysis.manager

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import com.battery.analysis.db.ChargingHistoryDbHelper
import com.battery.analysis.model.ChargingHistoryRecord
import com.battery.analysis.model.ChargingSamplePoint
import com.battery.analysis.model.ChargingSessionSummary
import com.battery.analysis.provider.NormalApiProvider
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * 充电过程统计业务管理器。
 * 负责自动检测设备连接充电器与断开状态、周期性采集瞬时充电物理指标（功率、电量、温度、电压与电流）、
 * 维护内存中三维趋势轨迹点，并持久化保存最近一次充电完整摘要供界面随时调阅。
 */
class ChargingStatsManager private constructor(private val context: Context) {

    private val prefs = context.getSharedPreferences("charging_stats_prefs", Context.MODE_PRIVATE)

    // 线程安全的当前会话采样点内存列表
    private val samplePoints = Collections.synchronizedList(mutableListOf<ChargingSamplePoint>())

    // 内存中缓存的当前或最近一次充电会话摘要
    @Volatile
    private var currentSummary: ChargingSessionSummary = ChargingSessionSummary()

    // 标识当前系统是否处于充电中
    @Volatile
    private var isCurrentlyCharging: Boolean = false

    // 上一次采样时刻与电量，用于精准核算息屏增量
    @Volatile
    private var lastSampleTimestamp: Long = 0L
    @Volatile
    private var lastSampleLevel: Int = -1

    companion object {
        private const val PREF_KEY_SAVED_SUMMARY = "pref_last_charging_summary"
        private const val PREF_KEY_SAVED_POINTS = "pref_last_charging_points"
        private const val MAX_SAMPLE_POINTS = 1500

        @Volatile
        private var instance: ChargingStatsManager? = null

        /**
         * 获取 ChargingStatsManager 单例对象。
         *
         * @param context 应用程序上下文
         * @return [ChargingStatsManager] 业务单例
         */
        fun getInstance(context: Context): ChargingStatsManager {
            return instance ?: synchronized(this) {
                instance ?: ChargingStatsManager(context.applicationContext).also { instance = it }
            }
        }
    }

    init {
        // 恢复保存在本地的最近一次充电记录
        loadSavedChargingSession()
        // 检测系统当前初始充电状态
        checkCurrentSystemChargingState()
    }

    /**
     * 实时检测系统当前是否处于充电状态及充电接口类型。
     *
     * @return 包含Pair(是否在充电, 充电类型文本)
     */
    fun checkCurrentSystemChargingState(): Pair<Boolean, String> {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, intentFilter)
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0

        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL ||
                plugged > 0

        val chargeType = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "交流快充"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB充电"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "无线充电"
            else -> if (charging) "外部供电" else "未充电"
        }

        isCurrentlyCharging = charging
        return Pair(charging, chargeType)
    }

    /**
     * 判断当前系统是否正在充电。
     *
     * @return 若正在充电返回 true，否则返回 false
     */
    fun isCharging(): Boolean {
        return checkCurrentSystemChargingState().first
    }

    /**
     * 当检测到连接充电器或系统由放电转为充电时调用，初始化全新充电统计周期。
     *
     * @param initialLevel 当前接入时刻的电池电量百分比
     * @param chargeType 充电连接类型
     */
    fun onPowerConnected(initialLevel: Int, chargeType: String) {
        val now = System.currentTimeMillis()
        isCurrentlyCharging = true
        lastSampleTimestamp = now
        lastSampleLevel = initialLevel

        synchronized(samplePoints) {
            samplePoints.clear()
        }

        val provider = NormalApiProvider()
        val info = provider.getBatteryInfo(context)
        val currentPower = info.powerWatts ?: 0f
        val currentTemp = info.temperature ?: 25f
        val currentVolt = (info.voltage ?: 4000f) / 1000f
        val currentMa = abs(info.currentNow ?: 0f)

        val firstPoint = ChargingSamplePoint(
            timestamp = now,
            powerWatts = currentPower,
            batteryLevel = initialLevel,
            temperature = currentTemp,
            voltageVolts = currentVolt,
            currentMa = currentMa
        )
        samplePoints.add(firstPoint)

        currentSummary = ChargingSessionSummary(
            startTimestamp = now,
            endTimestamp = now,
            startLevel = initialLevel,
            currentLevel = initialLevel,
            maxPowerWatts = currentPower,
            avgPowerWatts = currentPower,
            maxTemperature = currentTemp,
            avgTemperature = currentTemp,
            chargedEnergyWh = 0f,
            chargeType = chargeType,
            isCharging = true,
            screenOffDurationMs = 0L,
            screenOffLevelGain = 0,
            screenOffEnergyWh = 0f
        )

        saveChargingSessionToPrefs()
    }

    /**
     * 周期性采样并记录当前瞬时充电指标（功率、电量、温度、电压与电流），同时累计息屏充电数据。
     *
     * @return 采样生成的最新 [ChargingSamplePoint] 数据点，若未在充电则返回最新合成点
     */
    fun sampleCurrentPoint(): ChargingSamplePoint {
        val now = System.currentTimeMillis()
        val (charging, type) = checkCurrentSystemChargingState()

        val provider = NormalApiProvider()
        val info = provider.getBatteryInfo(context)

        val level = info.level ?: 50
        val temp = info.temperature ?: 25f
        val volt = (info.voltage ?: 4000f) / 1000f
        val curMa = abs(info.currentNow ?: 0f)
        val rawPower = info.powerWatts ?: (if (charging) (volt * curMa / 1000f) else -(volt * curMa / 1000f))
        val power = if (charging) abs(rawPower) else -abs(rawPower)

        // 彻底移除 hardcoded power = 10.0f 假数据，忠实记录底层传感器与广播测得的真实功率与电流

        val point = ChargingSamplePoint(
            timestamp = now,
            powerWatts = power,
            batteryLevel = level,
            temperature = temp,
            voltageVolts = volt,
            currentMa = curMa
        )

        // 息屏采样与增量累计逻辑
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isInteractive = pm?.isInteractive ?: true
        var deltaScreenOffMs = 0L
        var deltaScreenOffEnergy = 0f
        var deltaScreenOffGain = 0

        if (!isInteractive && charging) {
            if (lastSampleTimestamp > 0L) {
                val dt = (now - lastSampleTimestamp).coerceIn(0L, 120000L)
                deltaScreenOffMs = dt
                deltaScreenOffEnergy = (power.coerceAtLeast(0f) * (dt / 3600000.0f)).coerceAtLeast(0f)
            }
            if (lastSampleLevel in 0..100 && level > lastSampleLevel) {
                deltaScreenOffGain = level - lastSampleLevel
            }
        }
        lastSampleTimestamp = now
        lastSampleLevel = level

        synchronized(samplePoints) {
            // 若长时间跨度或列表为空，初始化首点
            if (samplePoints.isEmpty()) {
                currentSummary = currentSummary.copy(
                    startTimestamp = now,
                    startLevel = level,
                    chargeType = type,
                    isCharging = charging
                )
            }
            samplePoints.add(point)
            // 控制容量上限，必要时抽稀前部样本
            if (samplePoints.size > MAX_SAMPLE_POINTS) {
                samplePoints.removeAt(0)
            }
        }

        // 重新计算并汇总指标
        updateSummaryMetrics(point, type, charging, deltaScreenOffMs, deltaScreenOffGain, deltaScreenOffEnergy)
        saveChargingSessionToPrefs()

        return point
    }

    /**
     * 根据新加入的采样点动态更新内存中的会话摘要指标，并累加息屏统计数据。
     * 采用标准的梯形时间数值积分法计算充入能量与时间加权平均功率，避免由于采样间隔不均导致算术平均失真；
     * 当硬件电流传感器受限导致瞬时功率为 0 但电量实际增长时，基于电量增量与电池有效容量进行物理守恒核算。
     *
     * @param latestPoint 最新采样的物理数据点
     * @param chargeType 当前充电类型
     * @param isCharging 是否正在充电
     * @param deltaScreenOffMs 本周期新增息屏时长（毫秒）
     * @param deltaScreenOffGain 本周期新增息屏充入电量百分比
     * @param deltaScreenOffEnergy 本周期新增息屏充入能量（Wh）
     */
    private fun updateSummaryMetrics(
        latestPoint: ChargingSamplePoint,
        chargeType: String,
        isCharging: Boolean,
        deltaScreenOffMs: Long = 0L,
        deltaScreenOffGain: Int = 0,
        deltaScreenOffEnergy: Float = 0f
    ) {
        val pointsSnapshot = synchronized(samplePoints) { samplePoints.toList() }
        if (pointsSnapshot.isEmpty()) return

        var maxP = 0f
        var maxT = 0f
        var sumT = 0f

        for (p in pointsSnapshot) {
            if (p.powerWatts > maxP) maxP = p.powerWatts
            if (p.temperature > maxT) maxT = p.temperature
            sumT += p.temperature
        }

        val count = pointsSnapshot.size
        val avgT = if (count > 0) sumT / count else 0f

        // 1. 采用时序梯形积分法计算累计充入能量 Wh，精准应对亮屏高频与息屏低频采样间隔不一致
        var integratedEnergyWh = 0.0
        for (i in 0 until pointsSnapshot.size - 1) {
            val p1 = pointsSnapshot[i]
            val p2 = pointsSnapshot[i + 1]
            val dtHours = (p2.timestamp - p1.timestamp).coerceAtLeast(0L) / 3600000.0
            if (dtHours in 0.0001..0.5) { // 过滤过大异常断层（>30分钟）
                val avgSlicePower = (p1.powerWatts.coerceAtLeast(0f) + p2.powerWatts.coerceAtLeast(0f)) / 2.0
                integratedEnergyWh += avgSlicePower * dtHours
            }
        }

        // 2. 若硬件电流传感器不支持或处于握手盲区导致采样积分偏低，结合电量百分比增量与有效电池容量物理核算
        val levelGain = (latestPoint.batteryLevel - currentSummary.startLevel).coerceAtLeast(0)
        val durationHours = ((latestPoint.timestamp - currentSummary.startTimestamp).coerceAtLeast(0L)) / 3600000.0f
        val designCapMah = NormalApiProvider.getDesignCapacity(context) ?: 5000f
        val effectiveCapMah = if (designCapMah in 500f..30000f) designCapMah else 5000f
        val levelGainEnergyWh = (levelGain / 100.0f) * effectiveCapMah * (latestPoint.voltageVolts.coerceIn(3.0f, 4.5f)) / 1000.0f

        // 充入能量优先取采样时间积分，若采样缺失（如设备不支持电流直读）则基于电量增量补充
        val chargedWh = if (integratedEnergyWh > 0.005) {
            integratedEnergyWh.toFloat()
        } else if (levelGain > 0) {
            levelGainEnergyWh
        } else {
            0f
        }

        // 时间加权平均充电功率
        val avgP = if (durationHours > 0.002f && chargedWh > 0f) {
            chargedWh / durationHours
        } else if (count > 0 && maxP > 0f) {
            pointsSnapshot.map { it.powerWatts.coerceAtLeast(0f) }.filter { it > 0f }.let { validList ->
                if (validList.isNotEmpty()) validList.average().toFloat() else 0f
            }
        } else {
            0f
        }

        currentSummary = currentSummary.copy(
            endTimestamp = latestPoint.timestamp,
            currentLevel = latestPoint.batteryLevel,
            maxPowerWatts = maxP,
            avgPowerWatts = avgP,
            maxTemperature = maxT,
            avgTemperature = avgT,
            chargedEnergyWh = chargedWh,
            chargeType = if (chargeType.isNotEmpty()) chargeType else currentSummary.chargeType,
            isCharging = isCharging,
            screenOffDurationMs = currentSummary.screenOffDurationMs + deltaScreenOffMs,
            screenOffLevelGain = currentSummary.screenOffLevelGain + deltaScreenOffGain,
            screenOffEnergyWh = currentSummary.screenOffEnergyWh + deltaScreenOffEnergy
        )
    }

    /**
     * 当断开充电器（拔出电源）时触发，固化本次充电周期的完整数据，并自动归档至充电历史数据库中。
     */
    fun onPowerDisconnected() {
        isCurrentlyCharging = false
        val now = System.currentTimeMillis()
        currentSummary = currentSummary.copy(
            endTimestamp = now,
            isCharging = false
        )
        saveChargingSessionToPrefs()

        // 充电持续时长超过 10 秒或充入能量大于 0.005Wh 或有电量增量时，自动持久化至充电历史数据库
        val duration = currentSummary.getDurationMs()
        if (duration >= 10000L || currentSummary.chargedEnergyWh > 0.005f || currentSummary.getLevelGain() > 0) {
            try {
                val recordTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(now))
                val record = ChargingHistoryRecord(
                    id = now,
                    recordTime = recordTime,
                    startTimestamp = currentSummary.startTimestamp,
                    endTimestamp = now,
                    durationMs = duration,
                    startLevel = currentSummary.startLevel,
                    endLevel = currentSummary.currentLevel,
                    levelGain = currentSummary.getLevelGain(),
                    chargedEnergyWh = currentSummary.chargedEnergyWh,
                    avgPowerWatts = currentSummary.avgPowerWatts,
                    maxPowerWatts = currentSummary.maxPowerWatts,
                    maxTemperature = currentSummary.maxTemperature,
                    chargeType = currentSummary.chargeType,
                    screenOffDurationMs = currentSummary.screenOffDurationMs,
                    screenOffLevelGain = currentSummary.screenOffLevelGain,
                    screenOffEnergyWh = currentSummary.screenOffEnergyWh
                )
                ChargingHistoryDbHelper.getInstance(context).insertRecord(record)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 获取当前所有充电采样点列表的不可变副本。
     *
     * @return 采样点列表 [List]
     */
    fun getSamplePoints(): List<ChargingSamplePoint> {
        return synchronized(samplePoints) { samplePoints.toList() }
    }

    /**
     * 获取当前或最近一次的充电统计摘要信息。
     *
     * @return 充电摘要实体 [ChargingSessionSummary]
     */
    fun getCurrentSummary(): ChargingSessionSummary {
        return currentSummary
    }

    /**
     * 清空重置当前充电统计数据与图表走势。
     */
    fun resetChargingStats() {
        synchronized(samplePoints) {
            samplePoints.clear()
        }
        val (charging, type) = checkCurrentSystemChargingState()
        val provider = NormalApiProvider()
        val info = provider.getBatteryInfo(context)
        val level = info.level ?: 50
        val now = System.currentTimeMillis()

        currentSummary = ChargingSessionSummary(
            startTimestamp = now,
            endTimestamp = now,
            startLevel = level,
            currentLevel = level,
            chargeType = type,
            isCharging = charging,
            screenOffDurationMs = 0L,
            screenOffLevelGain = 0,
            screenOffEnergyWh = 0f
        )
        lastSampleTimestamp = 0L
        lastSampleLevel = -1
        prefs.edit().clear().apply()
    }

    /**
     * 将当前充电会话摘要与采样点持久化存入本地 SharedPreferences。
     */
    private fun saveChargingSessionToPrefs() {
        try {
            val summaryJson = JSONObject().apply {
                put("startTimestamp", currentSummary.startTimestamp)
                put("endTimestamp", currentSummary.endTimestamp)
                put("startLevel", currentSummary.startLevel)
                put("currentLevel", currentSummary.currentLevel)
                put("maxPowerWatts", currentSummary.maxPowerWatts.toDouble())
                put("avgPowerWatts", currentSummary.avgPowerWatts.toDouble())
                put("maxTemperature", currentSummary.maxTemperature.toDouble())
                put("avgTemperature", currentSummary.avgTemperature.toDouble())
                put("chargedEnergyWh", currentSummary.chargedEnergyWh.toDouble())
                put("chargeType", currentSummary.chargeType)
                put("isCharging", currentSummary.isCharging)
                put("screenOffDurationMs", currentSummary.screenOffDurationMs)
                put("screenOffLevelGain", currentSummary.screenOffLevelGain)
                put("screenOffEnergyWh", currentSummary.screenOffEnergyWh.toDouble())
            }

            // 保存最近 200 个最具代表性的点以节省 IO
            val pointsArray = JSONArray()
            val pointsToSave = synchronized(samplePoints) {
                if (samplePoints.size > 200) {
                    samplePoints.takeLast(200)
                } else {
                    samplePoints.toList()
                }
            }

            for (p in pointsToSave) {
                val item = JSONObject().apply {
                    put("ts", p.timestamp)
                    put("pw", p.powerWatts.toDouble())
                    put("lv", p.batteryLevel)
                    put("tp", p.temperature.toDouble())
                    put("vt", p.voltageVolts.toDouble())
                    put("cm", p.currentMa.toDouble())
                }
                pointsArray.put(item)
            }

            prefs.edit()
                .putString(PREF_KEY_SAVED_SUMMARY, summaryJson.toString())
                .putString(PREF_KEY_SAVED_POINTS, pointsArray.toString())
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 从本地 SharedPreferences 读取恢复先前保存的充电记录。
     */
    private fun loadSavedChargingSession() {
        try {
            val summaryStr = prefs.getString(PREF_KEY_SAVED_SUMMARY, null)
            if (!summaryStr.isNullOrEmpty()) {
                val json = JSONObject(summaryStr)
                currentSummary = ChargingSessionSummary(
                    startTimestamp = json.optLong("startTimestamp", 0L),
                    endTimestamp = json.optLong("endTimestamp", 0L),
                    startLevel = json.optInt("startLevel", 0),
                    currentLevel = json.optInt("currentLevel", 0),
                    maxPowerWatts = json.optDouble("maxPowerWatts", 0.0).toFloat(),
                    avgPowerWatts = json.optDouble("avgPowerWatts", 0.0).toFloat(),
                    maxTemperature = json.optDouble("maxTemperature", 0.0).toFloat(),
                    avgTemperature = json.optDouble("avgTemperature", 0.0).toFloat(),
                    chargedEnergyWh = json.optDouble("chargedEnergyWh", 0.0).toFloat(),
                    chargeType = json.optString("chargeType", "外部供电"),
                    isCharging = json.optBoolean("isCharging", false),
                    screenOffDurationMs = json.optLong("screenOffDurationMs", 0L),
                    screenOffLevelGain = json.optInt("screenOffLevelGain", 0),
                    screenOffEnergyWh = json.optDouble("screenOffEnergyWh", 0.0).toFloat()
                )
            }

            val pointsStr = prefs.getString(PREF_KEY_SAVED_POINTS, null)
            if (!pointsStr.isNullOrEmpty()) {
                val array = JSONArray(pointsStr)
                synchronized(samplePoints) {
                    samplePoints.clear()
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        samplePoints.add(
                            ChargingSamplePoint(
                                timestamp = obj.optLong("ts"),
                                powerWatts = obj.optDouble("pw").toFloat(),
                                batteryLevel = obj.optInt("lv"),
                                temperature = obj.optDouble("tp").toFloat(),
                                voltageVolts = obj.optDouble("vt").toFloat(),
                                currentMa = obj.optDouble("cm").toFloat()
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
