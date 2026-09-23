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

    // 标记当前充电会话是否已完成数据库归档持久化，防止多源并发广播导致重复入库
    @Volatile
    private var hasPersistedCurrentSession: Boolean = false

    // 上一次持久化归档的会话起始时间戳，辅助进行双重幂等防重拦截
    @Volatile
    private var lastPersistedStartTimestamp: Long = 0L

    // 上一次采样时刻与电量、瞬时功率，用于精准核算梯形微元能量与息屏增量
    @Volatile
    private var lastSampleTimestamp: Long = 0L
    @Volatile
    private var lastSampleLevel: Int = -1
    @Volatile
    private var lastSamplePower: Float = 0f

    companion object {
        private const val PREF_KEY_SAVED_SUMMARY = "pref_last_charging_summary"
        private const val PREF_KEY_SAVED_POINTS = "pref_last_charging_points"
        private const val MAX_SAMPLE_POINTS = 5000

        @Volatile
        private var instance: ChargingStatsManager? = null

        /**
         * 线程安全的时间格式化器，采用 ThreadLocal 实现每线程单例，
         * 避免在充电会话持久化等高频调用场景内重复创建 SimpleDateFormat 对象，
         * 降低 GC 分配压力。SimpleDateFormat 非线程安全，ThreadLocal 保证每线程独占实例。
         */
        val dateFormatter: ThreadLocal<java.text.SimpleDateFormat> = ThreadLocal.withInitial {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        }

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

        /**
         * 将 JSON 字符串反序列化为充电会话摘要。
         *
         * @param jsonStr 摘要 JSON 字符串
         * @return 充电会话摘要实体 [ChargingSessionSummary]
         */
        fun parseSummaryFromJson(jsonStr: String): ChargingSessionSummary {
            if (jsonStr.isEmpty()) return ChargingSessionSummary()
            return try {
                val json = org.json.JSONObject(jsonStr)
                ChargingSessionSummary(
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
            } catch (_: Throwable) {
                ChargingSessionSummary()
            }
        }

        /**
         * 将 JSON 字符串反序列化为充电采样点列表。
         *
         * @param jsonStr 采样点 JSON 数组文本
         * @return 充电采样点列表 [List<ChargingSamplePoint>]
         */
        fun parseSamplePointsFromJson(jsonStr: String): List<ChargingSamplePoint> {
            if (jsonStr.isEmpty() || jsonStr == "[]") return emptyList()
            val list = mutableListOf<ChargingSamplePoint>()
            try {
                val array = org.json.JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        ChargingSamplePoint(
                            timestamp = obj.optLong("ts"),
                            powerWatts = obj.optDouble("pw").toFloat(),
                            batteryLevel = obj.optInt("lv"),
                            temperature = obj.optDouble("tp").toFloat(),
                            voltageVolts = obj.optDouble("vt").toFloat(),
                            currentMa = obj.optDouble("cm").toFloat(),
                            isScreenOn = obj.optBoolean("so", true)
                        )
                    )
                }
            } catch (_: Throwable) {}
            return list
        }
    }

    /**
     * 充电采样点后台异步持久化单线程池，消除在采样主线程/协程中执行大 JSON 序列化与写盘造成的 CPU 阻塞。
     */
    private val chargingDiskExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    @Volatile
    private var unsavedChargingPointsCount = 0

    @Volatile
    private var lastChargingSaveTimeMs = 0L

    /**
     * 系统电池运行状态轻量内存缓存实体，用于 3000ms 防抖复用。
     *
     * @property timestamp 缓存获取时间戳（毫秒）
     * @property isCharging 是否处于充电连接状态
     * @property chargeType 充电接口类型描述
     * @property level 当前电量百分比
     * @property voltageVolts 实时电压（伏特 V）
     * @property temperatureCelsius 实时温度（摄氏度 ℃）
     * @property currentMa 实时电流（毫安 mA）
     */
    private data class CachedSystemBatteryStatus(
        val timestamp: Long,
        val isCharging: Boolean,
        val chargeType: String,
        val level: Int,
        val voltageVolts: Float?,
        val temperatureCelsius: Float?,
        val currentMa: Float?
    )

    @Volatile
    private var cachedSystemStatus: CachedSystemBatteryStatus? = null

    init {
        // 恢复保存在本地的最近一次充电记录
        loadSavedChargingSession()
        // 检测系统当前初始充电状态
        checkCurrentSystemChargingState()
        // 执行充放电状态自愈对齐检测
        checkAndReconcileChargingState()
    }

    /**
     * 校验并自愈充放电状态断层。
     * 当应用被强杀、进程被杀死或设备重启后再次启动时，比对持久化的充电摘要状态与当前系统底层实际电源状态：
     * 1. 若持久化显示处于充电中（isCharging == true），但当前系统实际已断开外部电源，
     *    说明在应用离线期间发生了断开电源事件，自动将未闭合的充电会话以合理指标归档存入数据库；
     * 2. 若当前系统正处于充电中，但内存会话标记为未充电，自动根据系统状态补齐开启充电采样；
     * 3. 若处于未充电状态，但自上次离线记录以来电量发生了跳跃式大幅增加（增量 >= 3%），
     *    说明应用在离线被杀期间曾插上充过电且已被拔掉，自动合成并归档一条“离线补齐充电记录”。
     *
     * @return 若检测并执行了状态自愈修复返回 true，否则返回 false
     */
    fun checkAndReconcileChargingState(): Boolean {
        val (nowCharging, currentChargeType) = checkCurrentSystemChargingState()
        val provider = NormalApiProvider()
        val info = provider.getBatteryInfo(context)
        val currentLevel = info.level ?: 0
        val now = System.currentTimeMillis()
        var reconciled = false

        // 场景 1：持久化显示还在充电中，但实际已经拔掉充电器
        if (currentSummary.isCharging && !nowCharging) {
            val duration = (now - currentSummary.startTimestamp).coerceAtLeast(0L)
            val levelGain = (currentLevel - currentSummary.startLevel).coerceAtLeast(0)
            val finalChargedEnergyWh = currentSummary.chargedEnergyWh

            val durationHours = duration / 3600000.0f
            val finalAvgPower = if (durationHours > 0.001f && finalChargedEnergyWh > 0f) {
                finalChargedEnergyWh / durationHours
            } else {
                currentSummary.avgPowerWatts
            }

            val alreadyPersisted = hasPersistedCurrentSession || (currentSummary.startTimestamp > 0L && currentSummary.startTimestamp == lastPersistedStartTimestamp)
            if (!alreadyPersisted && (duration >= 10000L || finalChargedEnergyWh > 0.005f || levelGain > 0)) {
                try {
                    val recordTime = dateFormatter.get()!!.format(Date(now))
                    val snapshotPoints = synchronized(samplePoints) { samplePoints.toList() }
                    val pointsJson = ChargingHistoryRecord.pointsToJson(snapshotPoints)

                    val record = ChargingHistoryRecord(
                        id = now,
                        recordTime = recordTime,
                        startTimestamp = currentSummary.startTimestamp,
                        endTimestamp = now,
                        durationMs = duration,
                        startLevel = currentSummary.startLevel,
                        endLevel = currentLevel,
                        levelGain = levelGain,
                        chargedEnergyWh = finalChargedEnergyWh,
                        avgPowerWatts = finalAvgPower,
                        maxPowerWatts = currentSummary.maxPowerWatts,
                        maxTemperature = currentSummary.maxTemperature,
                        chargeType = currentSummary.chargeType,
                        screenOffDurationMs = currentSummary.screenOffDurationMs,
                        screenOffLevelGain = currentSummary.screenOffLevelGain,
                        screenOffEnergyWh = currentSummary.screenOffEnergyWh,
                        samplePointsJson = pointsJson
                    )
                    ChargingHistoryDbHelper.getInstance(context).insertRecord(record)
                    hasPersistedCurrentSession = true
                    lastPersistedStartTimestamp = currentSummary.startTimestamp
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            currentSummary = currentSummary.copy(
                endTimestamp = now,
                currentLevel = currentLevel,
                isCharging = false,
                chargedEnergyWh = finalChargedEnergyWh,
                avgPowerWatts = finalAvgPower
            )
            saveChargingSessionToPrefs()
            reconciled = true
        } else if (!currentSummary.isCharging && nowCharging) {
            // 场景 2：当前实际在充电，但上次记录为未充电
            onPowerConnected(currentLevel, currentChargeType)
            reconciled = true
        } else if (!currentSummary.isCharging && !nowCharging) {
            // 场景 3：均未充电，仅同步当前真实电量，严禁伪造离线充电记录
            if (currentSummary.currentLevel != currentLevel && currentLevel > 0) {
                currentSummary = currentSummary.copy(
                    currentLevel = currentLevel
                )
                saveChargingSessionToPrefs()
            }
        }

        return reconciled
    }


    /**
     * 查询系统底层当前最新的电池广播运行参数，内部具备 3000ms 短期内存防抖缓存，
     * 单次广播读取同时解析充电状态、接口类型、电量、电压、温度与电流，
     * 彻底消除每秒多次重复向系统跨进程注册 [Intent.ACTION_BATTERY_CHANGED] 粘性广播造成的锁争用与 CPU 唤醒。
     *
     * @param force 是否强制跨进程刷新而不读取缓存
     * @return 电池最新运行参数实体 [CachedSystemBatteryStatus]
     */
    private fun getOrRefreshSystemBatteryStatus(force: Boolean = false): CachedSystemBatteryStatus {
        val now = System.currentTimeMillis()
        val cached = cachedSystemStatus
        if (!force && cached != null && (now - cached.timestamp) < 3_000L) {
            return cached
        }
        return synchronized(this) {
            val inner = cachedSystemStatus
            if (!force && inner != null && (now - inner.timestamp) < 3_000L) {
                return inner
            }
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

            val rawLevel = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val level = if (rawLevel in 0..100) rawLevel else 0
            val tempRaw = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            val temp = if (tempRaw > 0) tempRaw / 10f else null
            val voltRaw = batteryStatus?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
            val volt = if (voltRaw > 0) com.battery.analysis.util.BatteryUnitNormalizer.normalizeVoltageVolts(voltRaw.toLong()) else null

            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val rawCurrent = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
            val currentMa = if (rawCurrent != 0 && rawCurrent != Int.MIN_VALUE) {
                val curMa = com.battery.analysis.util.BatteryUnitNormalizer.normalizeCurrentMa(rawCurrent.toLong(), charging)
                val isNetDischarging = rawCurrent < 0 || status == BatteryManager.BATTERY_STATUS_DISCHARGING || status == BatteryManager.BATTERY_STATUS_NOT_CHARGING
                if (isNetDischarging) -curMa else curMa
            } else null

            isCurrentlyCharging = charging
            val newStatus = CachedSystemBatteryStatus(
                timestamp = now,
                isCharging = charging,
                chargeType = chargeType,
                level = level,
                voltageVolts = volt,
                temperatureCelsius = temp,
                currentMa = currentMa
            )
            cachedSystemStatus = newStatus
            newStatus
        }
    }

    /**
     * 实时检测系统当前是否处于充电状态及充电接口类型（复用 3000ms 缓存）。
     *
     * @return 包含Pair(是否在充电, 充电类型文本)
     */
    fun checkCurrentSystemChargingState(): Pair<Boolean, String> {
        val status = getOrRefreshSystemBatteryStatus()
        return Pair(status.isCharging, status.chargeType)
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
        hasPersistedCurrentSession = false

        val sysStatus = getOrRefreshSystemBatteryStatus(force = true)
        val fallbackTemp = sysStatus.temperatureCelsius
        val fallbackVolt = sysStatus.voltageVolts

        // 优先采用JNI / sysfs 硬件直读通道获取无滤波瞬时快充物理指标
        val hwSample = com.battery.analysis.util.SysfsBatterySampler.sampleHardwareCharging(
            context = context,
            fallbackVoltageVolts = fallbackVolt,
            fallbackTempCelsius = fallbackTemp
        )

        val currentVolt = hwSample?.voltageVolts ?: (fallbackVolt ?: 0f)
        val currentMa = hwSample?.currentMa ?: (sysStatus.currentMa ?: 0f)
        val currentTemp = hwSample?.temperatureCelsius ?: (fallbackTemp ?: 0f)
        val rawPower = hwSample?.powerWatts ?: com.battery.analysis.util.BatteryUnitNormalizer.calculatePowerWatts(currentVolt, currentMa, isCharging = true)
        val currentPower = rawPower

        lastSampleTimestamp = now
        lastSampleLevel = initialLevel
        lastSamplePower = currentPower

        synchronized(samplePoints) {
            samplePoints.clear()
        }

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isInteractive = pm?.isInteractive ?: true

        val firstPoint = ChargingSamplePoint(
            timestamp = now,
            powerWatts = currentPower,
            batteryLevel = initialLevel,
            temperature = currentTemp,
            voltageVolts = currentVolt,
            currentMa = currentMa,
            isScreenOn = isInteractive
        )
        synchronized(samplePoints) {
            samplePoints.add(firstPoint)
        }

        currentSummary = ChargingSessionSummary(
            startTimestamp = now,
            endTimestamp = now,
            startLevel = initialLevel,
            currentLevel = initialLevel,
            maxPowerWatts = max(0f, currentPower),
            avgPowerWatts = max(0f, currentPower),
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
     * 优先通过 JNI / sysfs 原生内核文件描述符直读芯片寄存器，
     * 绕过 Android Framework 低通滤波，并在权限受限时自动平滑降级至 Shizuku 与 BatteryManager 兜底。
     *
     * @return 采样生成的最新 [ChargingSamplePoint] 数据点，若未在充电则返回最新合成点
     */
    fun sampleCurrentPoint(): ChargingSamplePoint {
        val now = System.currentTimeMillis()
        val sysStatus = getOrRefreshSystemBatteryStatus()
        val charging = sysStatus.isCharging
        val type = sysStatus.chargeType

        val level = sysStatus.level
        val fallbackTemp = sysStatus.temperatureCelsius
        val fallbackVolt = sysStatus.voltageVolts

        //  JNI / sysfs 硬件直读通道获取真实瞬时快充物理指标
        val hwSample = com.battery.analysis.util.SysfsBatterySampler.sampleHardwareCharging(
            context = context,
            fallbackVoltageVolts = fallbackVolt,
            fallbackTempCelsius = fallbackTemp
        )

        val volt = hwSample?.voltageVolts ?: (fallbackVolt ?: 0f)
        val curMa = hwSample?.currentMa ?: (sysStatus.currentMa ?: 0f)
        val temp = hwSample?.temperatureCelsius ?: (fallbackTemp ?: 0f)
        val rawPower = hwSample?.powerWatts ?: com.battery.analysis.util.BatteryUnitNormalizer.calculatePowerWatts(volt, curMa, charging)
        val power = rawPower

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isInteractive = pm?.isInteractive ?: true

        val point = ChargingSamplePoint(
            timestamp = now,
            powerWatts = power,
            batteryLevel = level,
            temperature = temp,
            voltageVolts = volt,
            currentMa = curMa,
            isScreenOn = isInteractive
        )

        // 梯形数值积分微元：计算本采样周期的能量增量 dE
        var deltaEnergyWh = 0f
        if (lastSampleTimestamp > 0L && now > lastSampleTimestamp) {
            val dtMs = (now - lastSampleTimestamp).coerceAtLeast(0L)
            if (dtMs in 100L..1800000L) { // 0.1秒到30分钟内的有效采样切片
                val avgSlicePower = (lastSamplePower.coerceAtLeast(0f) + power.coerceAtLeast(0f)) / 2f
                val dtHours = dtMs / 3600000.0
                val powerEnergy = (avgSlicePower * dtHours).toFloat()

                val designCapMah = NormalApiProvider.getDesignCapacity(context) ?: 0f
                val deltaLevel = (level - lastSampleLevel).coerceAtLeast(0)
                val levelEnergy = if (designCapMah > 0f && volt > 0f) {
                    (deltaLevel / 100.0f) * designCapMah * volt / 1000.0f
                } else 0f

                // 若物理功率微积分有效优先采用；若硬件传感器受限读数为0且电量有净增，则使用电量增量物理守恒兜底
                deltaEnergyWh = if (powerEnergy > 0.00001f) {
                    powerEnergy
                } else if (deltaLevel > 0) {
                    levelEnergy
                } else {
                    0f
                }
            }
        }

        // 息屏采样与增量累计逻辑
        var deltaScreenOffMs = 0L
        var deltaScreenOffEnergy = 0f
        var deltaScreenOffGain = 0

        if (!isInteractive && charging) {
            if (lastSampleTimestamp > 0L) {
                val dt = (now - lastSampleTimestamp).coerceIn(0L, 120000L)
                deltaScreenOffMs = dt
                deltaScreenOffEnergy = deltaEnergyWh
            }
            if (lastSampleLevel in 0..100 && level > lastSampleLevel) {
                deltaScreenOffGain = level - lastSampleLevel
            }
        }
        lastSampleTimestamp = now
        lastSampleLevel = level
        lastSamplePower = power

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
            // 优化：上限从 5000 降至 2000，同时改为原地逆向删除奇数索引点，
            // 彻底消除创建等大临时 List 导致的双倍内存峰值
            if (samplePoints.size > 2000) {
                val lastIdx = samplePoints.size - 1
                var i = lastIdx - 1
                while (i >= 1) {
                    if (i % 2 == 1) {
                        samplePoints.removeAt(i)
                    }
                    i--
                }
            }
        }

        // 重新计算并汇总指标
        updateSummaryMetrics(point, type, charging, deltaEnergyWh, deltaScreenOffMs, deltaScreenOffGain, deltaScreenOffEnergy)

        // 极致低功耗设计：日常采样纯内存追加，每积累 60 个点（约 1~2 分钟）或超过 2 分钟才异步落盘一次，
        // 彻底消除每秒为 500 个点频繁全量创建 JSONObject 与 SharedPreferences 频繁写盘开销
        unsavedChargingPointsCount++
        val nowMs = System.currentTimeMillis()
        if (unsavedChargingPointsCount >= 60 || (nowMs - lastChargingSaveTimeMs >= 120_000L && unsavedChargingPointsCount >= 10)) {
            unsavedChargingPointsCount = 0
            lastChargingSaveTimeMs = nowMs
            flushChargingSessionToPrefsAsync()
        }

        return point
    }

    /**
     * 异步持久化充电会话与采样点至本地 SharedPreferences，避免阻塞采样轮询线程。
     */
    fun flushChargingSessionToPrefsAsync() {
        chargingDiskExecutor.execute {
            saveChargingSessionToPrefs()
        }
    }

    /**
     * 根据新加入的采样点动态更新内存中的会话摘要指标，并累加息屏统计数据。
     * 采用标准的梯形微元时间积分持续累加充入能量与时间加权平均功率，避免重新遍历局部抽稀列表导致的能量丢失；
     * 当硬件电流传感器受限导致瞬时功率为 0 但电量实际增长时，基于电量增量与电池有效容量进行物理守恒核算。
     *
     * 优化：将原先的 O(n) 全量遍历改为 O(1) 增量更新，仅对新采样点与当前 summary 中的最大值
     * 直接比较更新，无需每次重新遍历数千个历史采样点，显著降低 CPU 占用与 GC 压力。
     *
     * @param latestPoint 最新采样的物理数据点
     * @param chargeType 当前充电类型
     * @param isCharging 是否正在充电
     * @param deltaEnergyWh 本周期新增充入能量（Wh）
     * @param deltaScreenOffMs 本周期新增息屏时长（毫秒）
     * @param deltaScreenOffGain 本周期新增息屏充入电量百分比
     * @param deltaScreenOffEnergy 本周期新增息屏充入能量（Wh）
     */
    private fun updateSummaryMetrics(
        latestPoint: ChargingSamplePoint,
        chargeType: String,
        isCharging: Boolean,
        deltaEnergyWh: Float,
        deltaScreenOffMs: Long = 0L,
        deltaScreenOffGain: Int = 0,
        deltaScreenOffEnergy: Float = 0f
    ) {
        // O(1) 增量更新：仅与当前最大值比较，无需遍历全部历史点
        val newMaxP = if (latestPoint.powerWatts > 0f && latestPoint.powerWatts > currentSummary.maxPowerWatts) {
            latestPoint.powerWatts
        } else {
            currentSummary.maxPowerWatts
        }
        val newMaxT = if (latestPoint.temperature > currentSummary.maxTemperature) {
            latestPoint.temperature
        } else {
            currentSummary.maxTemperature
        }

        // 采用时序微元持续累加充入能量 Wh，保证单调递增，绝不因前端或内存抽稀而丢失已累计能量
        val accumulatedEnergyWh = (currentSummary.chargedEnergyWh + deltaEnergyWh).coerceAtLeast(0f)

        val levelGain = (latestPoint.batteryLevel - currentSummary.startLevel).coerceAtLeast(0)
        val durationHours = ((latestPoint.timestamp - currentSummary.startTimestamp).coerceAtLeast(0L)) / 3600000.0f
        val designCapMah = NormalApiProvider.getDesignCapacity(context) ?: 0f
        val levelGainEnergyWh = if (designCapMah > 0f && latestPoint.voltageVolts > 0f) {
            (levelGain / 100.0f) * designCapMah * latestPoint.voltageVolts / 1000.0f
        } else {
            0f
        }

        val finalChargedWh = if (accumulatedEnergyWh > 0.005f) {
            accumulatedEnergyWh
        } else if (levelGain > 0) {
            levelGainEnergyWh
        } else {
            0f
        }

        val avgP = if (durationHours > 0.001f && finalChargedWh > 0f) {
            finalChargedWh / durationHours
        } else if (latestPoint.powerWatts > 0f) {
            latestPoint.powerWatts
        } else {
            currentSummary.avgPowerWatts
        }

        // O(1) 更新平均温度：用已存的 avgTemperature 和新点做指数平滑（避免全量求和）
        val pointCount = synchronized(samplePoints) { samplePoints.size }.coerceAtLeast(1)
        val newAvgT = if (pointCount <= 1) {
            latestPoint.temperature
        } else {
            // 指数移动平均（EMA）近似：新均值 = 旧均值 * (n-1)/n + 新点/n
            currentSummary.avgTemperature * ((pointCount - 1).toFloat() / pointCount) +
                    latestPoint.temperature / pointCount
        }

        currentSummary = currentSummary.copy(
            endTimestamp = latestPoint.timestamp,
            currentLevel = latestPoint.batteryLevel,
            maxPowerWatts = newMaxP,
            avgPowerWatts = avgP,
            maxTemperature = newMaxT,
            avgTemperature = newAvgT,
            chargedEnergyWh = finalChargedWh,
            chargeType = if (chargeType.isNotEmpty()) chargeType else currentSummary.chargeType,
            isCharging = isCharging,
            screenOffDurationMs = currentSummary.screenOffDurationMs + deltaScreenOffMs,
            screenOffLevelGain = currentSummary.screenOffLevelGain + deltaScreenOffGain,
            screenOffEnergyWh = currentSummary.screenOffEnergyWh + deltaScreenOffEnergy
        )
    }

    /**
     * 当断开充电器（拔出电源）时触发，固化本次充电周期的完整数据，并自动归档至充电历史数据库中。
     * 内部具备线程级互斥加锁（@Synchronized）与会话持久化状态守卫，杜绝 Service 与 Receiver
     * 并发广播导致的重复归档，确保每次拔电仅生成唯一一份充电历史记录。
     */
    @Synchronized
    fun onPowerDisconnected() {
        // 1. 若当前会话已被归档持久化过，或者与上一次已归档的会话起始时间相同，直接拦截退出
        if (hasPersistedCurrentSession || (currentSummary.startTimestamp > 0L && currentSummary.startTimestamp == lastPersistedStartTimestamp)) {
            isCurrentlyCharging = false
            return
        }

        // 2. 若当前并未处于充电中状态，直接退出
        if (!isCurrentlyCharging && !currentSummary.isCharging) {
            return
        }

        isCurrentlyCharging = false
        val now = System.currentTimeMillis()
        val duration = currentSummary.getDurationMs()
        val levelGain = currentSummary.getLevelGain()

        val finalEnergy = currentSummary.chargedEnergyWh

        val durationHours = duration / 3600000.0f
        val finalAvgPower = if (durationHours > 0.001f && finalEnergy > 0f) {
            finalEnergy / durationHours
        } else {
            currentSummary.avgPowerWatts
        }

        currentSummary = currentSummary.copy(
            endTimestamp = now,
            isCharging = false,
            chargedEnergyWh = finalEnergy,
            avgPowerWatts = finalAvgPower
        )
        saveChargingSessionToPrefs()

        // 3. 充电持续时长超过 10 秒或充入能量大于 0.005Wh 或有电量增量时，自动持久化至充电历史数据库
        if (duration >= 10000L || finalEnergy > 0.005f || levelGain > 0) {
            try {
                val recordTime = dateFormatter.get()!!.format(Date(now))
                val snapshotPoints = synchronized(samplePoints) { samplePoints.toList() }
                val pointsJson = ChargingHistoryRecord.pointsToJson(snapshotPoints)

                val record = ChargingHistoryRecord(
                    id = now,
                    recordTime = recordTime,
                    startTimestamp = currentSummary.startTimestamp,
                    endTimestamp = now,
                    durationMs = duration,
                    startLevel = currentSummary.startLevel,
                    endLevel = currentSummary.currentLevel,
                    levelGain = levelGain,
                    chargedEnergyWh = finalEnergy,
                    avgPowerWatts = finalAvgPower,
                    maxPowerWatts = currentSummary.maxPowerWatts,
                    maxTemperature = currentSummary.maxTemperature,
                    chargeType = currentSummary.chargeType,
                    screenOffDurationMs = currentSummary.screenOffDurationMs,
                    screenOffLevelGain = currentSummary.screenOffLevelGain,
                    screenOffEnergyWh = currentSummary.screenOffEnergyWh,
                    samplePointsJson = pointsJson
                )
                ChargingHistoryDbHelper.getInstance(context).insertRecord(record)
                hasPersistedCurrentSession = true
                lastPersistedStartTimestamp = currentSummary.startTimestamp
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
     * 获取当前充电会话摘要的 JSON 字符串表示，供 AIDL 跨进程传输使用。
     *
     * @return 序列化生成的摘要 JSON 字符串
     */
    fun getCurrentSummaryAsJson(): String {
        val s = currentSummary
        val obj = org.json.JSONObject().apply {
            put("startTimestamp", s.startTimestamp)
            put("endTimestamp", s.endTimestamp)
            put("startLevel", s.startLevel)
            put("currentLevel", s.currentLevel)
            put("maxPowerWatts", s.maxPowerWatts.toDouble())
            put("avgPowerWatts", s.avgPowerWatts.toDouble())
            put("maxTemperature", s.maxTemperature.toDouble())
            put("avgTemperature", s.avgTemperature.toDouble())
            put("chargedEnergyWh", s.chargedEnergyWh.toDouble())
            put("chargeType", s.chargeType)
            put("isCharging", s.isCharging)
            put("screenOffDurationMs", s.screenOffDurationMs)
            put("screenOffLevelGain", s.screenOffLevelGain)
            put("screenOffEnergyWh", s.screenOffEnergyWh.toDouble())
        }
        return obj.toString()
    }

    /**
     * 获取当前充电会话采样点列表的 JSON 字符串表示，供 AIDL 跨进程传输使用。
     *
     * @return 序列化生成的采样点 JSON 数组字符串
     */
    fun getSamplePointsAsJson(): String {
        val points = synchronized(samplePoints) { samplePoints.toList() }
        val array = org.json.JSONArray()
        for (p in points) {
            val obj = org.json.JSONObject().apply {
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
        return array.toString()
    }

    /**
     * 将当前内存中的充电会话摘要与采样点立即持久化刷入磁盘存储。
     */
    fun flushChargingSamplesToDisk() {
        saveChargingSessionToPrefs()
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
        lastSamplePower = 0f
        prefs.edit().clear().apply()
    }

    /**
     * 将当前充电会话摘要与采样点持久化存入本地 SharedPreferences。
     * 采样点采用跨越全局时间轴的等距均匀抽样，最多保存 500 个点，确保图表恢复后完整呈现从头至尾的时间跨度。
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

            // 全局等距均匀抽样保留至多 300 个点，跨越完整起止时间轴
            val pointsArray = JSONArray()
            val pointsToSave = synchronized(samplePoints) {
                if (samplePoints.size > 300) {
                    val sampled = mutableListOf<ChargingSamplePoint>()
                    val step = (samplePoints.size - 1).toFloat() / 299f
                    for (i in 0 until 300) {
                        val index = (i * step).toInt().coerceIn(0, samplePoints.size - 1)
                        sampled.add(samplePoints[index])
                    }
                    sampled
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
                    put("so", p.isScreenOn)
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
                                currentMa = obj.optDouble("cm").toFloat(),
                                isScreenOn = obj.optBoolean("so", true)
                            )
                        )
                    }
                }
            }

            lastSampleTimestamp = currentSummary.endTimestamp
            lastSampleLevel = currentSummary.currentLevel
            lastSamplePower = synchronized(samplePoints) {
                samplePoints.lastOrNull()?.powerWatts ?: currentSummary.avgPowerWatts
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
