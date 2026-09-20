package com.battery.analysis.manager

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.battery.analysis.db.HistoryDbHelper
import com.battery.analysis.db.PowerUsageDbHelper
import com.battery.analysis.model.AppPowerUsageItem
import com.battery.analysis.model.PowerDischargePoint
import com.battery.analysis.model.PowerUsageRecord
import com.battery.analysis.provider.NormalApiProvider
import com.battery.analysis.provider.ShizukuBatteryStatsParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.battery.analysis.timeline.domain.AppTimelineEvent
import com.battery.analysis.timeline.domain.BatterySample
import com.battery.analysis.timeline.domain.ConfidenceLevel
import com.battery.analysis.timeline.domain.EnergyCalculator
import com.battery.analysis.timeline.domain.EnergySource
import com.battery.analysis.timeline.domain.ScreenEvent
import com.battery.analysis.timeline.presentation.BatteryTimelineState
import com.battery.analysis.timeline.presentation.TimelineMetric
import com.battery.analysis.util.BatteryEnergyCalculator
import com.battery.analysis.util.NetworkStatsHelper
import android.os.PowerManager
import com.battery.analysis.service.KeepAliveAccessibilityService
import com.battery.analysis.util.ShizukuForegroundAppDetector
import rikka.shizuku.Shizuku
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 耗电统计与应用使用场景业务管理器。
 * 负责调度 Shizuku 高精度模式与标准模式两种数据源，从系统底层提取各应用真实放电消耗、前台时长、
 * 计算实时平均功耗与理论续航，并生成放电过程走势轨迹点。
 */
class PowerUsageManager private constructor(private val context: Context) {

    /**
     * 应用前台活动与亮屏运行时间区间数据类。
     *
     * @property packageName 目标应用包名
     * @property startTs 前台活跃起始时间戳（毫秒）
     * @property endTs 前台活跃结束时间戳（毫秒）
     */
    private data class AppActivityInterval(
        val packageName: String,
        val startTs: Long,
        val endTs: Long
    )

    /**
     * 屏幕点亮交互时间区间数据类。
     *
     * @property startTs 屏幕点亮起始时间戳（毫秒）
     * @property endTs 屏幕关闭熄灭时间戳（毫秒）
     */
    private data class ScreenInteractiveInterval(
        val startTs: Long,
        val endTs: Long
    )

    companion object {
        const val MODE_SHIZUKU = 0
        const val MODE_NORMAL = 1

        private const val PREF_KEY_POWER_MODE = "pref_power_stats_mode"
        private const val PREF_KEY_POWER_CONFIGURED = "pref_power_mode_configured"
        const val PREF_KEY_LAST_UNPLUG_TIME = "pref_last_unplug_time"
        const val PREF_KEY_LAST_UNPLUG_LEVEL = "pref_last_unplug_level"
        const val PREF_KEY_LAST_UNPLUG_CHARGE_COUNTER = "pref_last_unplug_charge_counter"
        private const val PREF_KEY_UNPLUG_USAGE_SNAPSHOT = "pref_unplug_usage_snapshot"
        private const val PREF_KEY_UNPLUG_BG_SERVICE_SNAPSHOT = "pref_unplug_bg_service_snapshot"
        private const val PREF_KEY_REALTIME_SAMPLES_JSON = "pref_discharge_realtime_samples_json"

        /** 连续时序微积分单微元最大有效跨度门限（单位：毫秒，默认 2 分钟，杜绝息屏 Deep Sleep 断层插值放大） */
        const val MAX_INTEGRATION_INTERVAL_MS = 120_000L

        @Volatile
        private var instance: PowerUsageManager? = null

        /**
         * 获取 PowerUsageManager 单例实例。
         *
         * @param context 应用程序上下文
         * @return 单例实例 [PowerUsageManager]
         */
        fun getInstance(context: Context): PowerUsageManager {
            return instance ?: synchronized(this) {
                instance ?: PowerUsageManager(context.applicationContext).also { instance = it }
            }
        }

        /**
         * 基于硬件放电时序采样点（时间戳、瞬时电压、瞬时电流）的时间切片数值微积分模型。
         * 遵循物理能量守恒定律与微积分定义（对标 BatteryRecorder 硬件放电积分实现）：
         * E = ∫ P(t) dt = Σ [ ((P(i) + P(i+1)) / 2) * Δt ]
         * 采用梯形数值积分法，计算放电过程中的真实物理能耗与平均放电功率。
         *
         * @param points 时序采样点三元组列表（Triple(时间戳毫秒, 瞬时电压伏特 V, 瞬时放电电流毫安 mA)）
         * @return 计算得到的放电能量（单位：瓦时 Wh）与平均放电功率（单位：瓦特 W）的 [Pair]
         */
        fun calculatePhysicalIntegratedEnergyAndPower(
            points: List<Triple<Long, Float, Float>>
        ): Pair<Float, Float> {
            if (points.size < 2) {
                if (points.size == 1) {
                    val p = (points[0].second * (points[0].third / 1000f)).coerceAtLeast(0f)
                    return Pair(0f, p)
                }
                return Pair(0f, 0f)
            }

            var totalEnergyJoules = 0.0
            var totalDurationSeconds = 0.0

            for (i in 0 until points.size - 1) {
                val p1 = points[i]
                val p2 = points[i + 1]

                val dtMillis = p2.first - p1.first
                if (dtMillis <= 0L || dtMillis > MAX_INTEGRATION_INTERVAL_MS) continue

                val dtSeconds = dtMillis / 1000.0
                // 瞬时功率 P = U (V) * I (A)，电流为 mA 时需除以 1000
                val power1Watts = (p1.second * (p1.third / 1000f)).coerceAtLeast(0f)
                val power2Watts = (p2.second * (p2.third / 1000f)).coerceAtLeast(0f)

                // 梯形面积微元积分: dE = ((P1 + P2) / 2) * dt (焦耳 J)
                val avgSegmentPower = (power1Watts + power2Watts) / 2.0
                val segmentJoules = avgSegmentPower * dtSeconds

                totalEnergyJoules += segmentJoules
                totalDurationSeconds += dtSeconds
            }

            // 1 瓦时 (Wh) = 3600 焦耳 (J)
            val energyWh = (totalEnergyJoules / 3600.0).toFloat()
            val avgWatts = if (totalDurationSeconds > 0.0) {
                (totalEnergyJoules / totalDurationSeconds).toFloat()
            } else {
                0f
            }

            return Pair(energyWh, avgWatts)
        }
    }

    private val prefs = context.getSharedPreferences("power_stats_prefs", Context.MODE_PRIVATE)
    private val shizukuParser = ShizukuBatteryStatsParser(context)
    private val networkStatsHelper = NetworkStatsHelper(context)

    // 标记当前放电周期（lastUnplugTime）是否已归档持久化，杜绝并发广播与进程重启重复插入
    @Volatile
    private var lastArchivedUnplugTime: Long = prefs.getLong("pref_last_archived_unplug_time", 0L)

    /**
     * 放电期间实时时序温度采样点列表（时间戳 -> 摄氏度）。
     * 用于在普通模式或 dumpsys history 缺损时，为各 App 精准匹配前台活跃期间的真实平均与最高温度。
     */
    private val dischargeTempPoints = mutableListOf<Pair<Long, Float>>()

    /**
     * 放电期间秒级瞬时采样点列表。
     * 记录放电过程中的每一个瞬时物理采样点：时间戳、电量、瞬时真实电压、瞬时真实温度、瞬时放电功耗、亮息屏状态。
     */
    private val dischargeRealtimeSamples = mutableListOf<PowerDischargePoint>()

    /**
     * 异步后台 I/O 线程池，用于执行大采样点序列的持久化存储，杜绝主线程与轮询线程阻塞。
     */
    private val diskIoExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /**
     * 未持久化采样点脏数据计数器。
     */
    @Volatile
    private var unsavedDischargeSamplesCount = 0

    /**
     * 上一次放电瞬时采样点持久化落盘的时间戳（毫秒）。
     */
    @Volatile
    private var lastDischargeSaveTimeMs = 0L

    /**
     * 游戏应用包名内存缓存，避免高频重复调用 PackageManager 进行 IPC Binder 查询。
     */
    private val gameAppCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * 记录放电过程中的实时瞬时采样点。
     * 包含秒级时间戳、瞬时电量、电压、温度、瞬时功耗及屏幕开关状态。
     * 采用纯内存快速追加与批处理异步刷盘机制，确保极低 CPU 与磁盘 I/O 消耗。
     *
     * @param timestamp 采样时间戳（毫秒）
     * @param batteryLevel 当前电量百分比 (1..100)
     * @param voltageVolts 实时电压（伏特 V）
     * @param temperature 实时温度（摄氏度 ℃）
     * @param powerWatts 实时瞬时放电功耗（瓦特 W）
     * @param isScreenOn 屏幕是否处于点亮唤醒状态
     * @param packageName 采样瞬间处于置顶前台的应用包名（可为 null）
     */
    @Synchronized
    fun recordDischargeRealtimeSample(
        timestamp: Long,
        batteryLevel: Int,
        voltageVolts: Float,
        temperature: Float,
        powerWatts: Float,
        isScreenOn: Boolean,
        packageName: String? = null
    ) {
        val lastPoint = dischargeRealtimeSamples.lastOrNull()
        // 1 秒内防抖，避免同一秒内密集重复写入
        if (lastPoint != null && (timestamp - lastPoint.timestamp) < 1000L) {
            return
        }

        val startTs = getLastUnplugTime().let { if (it > 0L) it else timestamp }
        val elapsedHours = (timestamp - startTs).coerceAtLeast(0L) / 3600000f

        val point = PowerDischargePoint(
            timestamp = timestamp,
            elapsedHours = elapsedHours,
            batteryLevel = batteryLevel.coerceIn(0, 100),
            voltageVolts = (Math.round(voltageVolts * 1000f) / 1000f).coerceAtLeast(0f),
            temperature = (Math.round(temperature * 10f) / 10f),
            powerWatts = (Math.round(powerWatts * 100f) / 100f).coerceAtLeast(0f),
            activeAppIcons = emptyList(),
            isScreenOn = isScreenOn,
            activeAppNames = emptyList(),
            packageName = packageName
        )
        dischargeRealtimeSamples.add(point)
        // 达到容量上限时进行全局等距抽稀（50%），确保从拔电初始到当前时刻的放电走势时间轴完整保留
        if (dischargeRealtimeSamples.size > 5000) {
            val downsampled = mutableListOf<PowerDischargePoint>()
            downsampled.add(dischargeRealtimeSamples.first())
            for (i in 1 until dischargeRealtimeSamples.size - 1 step 2) {
                downsampled.add(dischargeRealtimeSamples[i])
            }
            downsampled.add(dischargeRealtimeSamples.last())
            dischargeRealtimeSamples.clear()
            dischargeRealtimeSamples.addAll(downsampled)
        }

        // 同步记录时序温度点
        recordDischargeTempSample(timestamp, temperature)

        // 极致低功耗设计：日常采样纯内存追加，当积累达到 600 个点或超过 10 分钟且有新数据时才在后台异步持久化一次，
        // 彻底杜绝每百点频繁全量序列化造成的 CPU 占用与 GC 抖动。关键生命周期节点（息屏、插拔、退出）由外部主动 flush
        unsavedDischargeSamplesCount++
        val now = System.currentTimeMillis()
        if (unsavedDischargeSamplesCount >= 600 || (now - lastDischargeSaveTimeMs >= 600_000L && unsavedDischargeSamplesCount >= 60)) {
            unsavedDischargeSamplesCount = 0
            saveDischargeSamplesToPrefsAsync()
        }
    }

    /**
     * 异步持久化放电采样点至本地存储，避免阻塞调用线程。
     */
    fun saveDischargeSamplesToPrefsAsync() {
        diskIoExecutor.execute {
            saveDischargeSamplesToPrefs()
        }
    }

    /**
     * 主动将当前内存中的放电瞬时采样点序列刷入本地持久化存储。
     * 适合在息屏休眠、电源插拔、清空重置等关键生命周期节点调用。
     */
    fun flushDischargeSamplesToDisk() {
        saveDischargeSamplesToPrefsAsync()
    }

    /**
     * 获取当前放电周期记录的所有秒级瞬时采样点列表。
     * 若内存中为空，则自动尝试从本地持久化中恢复读取。
     *
     * @return 瞬时物理采样点列表 [List<PowerDischargePoint>]
     */
    @Synchronized
    fun getDischargeRealtimeSamples(): List<PowerDischargePoint> {
        if (dischargeRealtimeSamples.isEmpty()) {
            loadDischargeSamplesFromPrefs()
        }
        return dischargeRealtimeSamples.toList()
    }

    /**
     * 重置当前放电周期的秒级瞬时采样点列表，并注入初始起点数据。
     *
     * @param timestamp 起始时间戳（毫秒）
     * @param initialLevel 起始电量百分比
     * @param initialVoltage 起始电压（伏特 V）
     * @param initialTemp 起始温度（摄氏度 ℃）
     * @param initialPower 起始放电功耗（瓦特 W）
     * @param isScreenOn 起始屏幕状态
     */
    @Synchronized
    fun resetDischargeRealtimeSamples(
        timestamp: Long,
        initialLevel: Int,
        initialVoltage: Float,
        initialTemp: Float,
        initialPower: Float = 0f,
        isScreenOn: Boolean = true
    ) {
        dischargeRealtimeSamples.clear()
        unsavedDischargeSamplesCount = 0
        val firstPoint = PowerDischargePoint(
            timestamp = timestamp,
            elapsedHours = 0f,
            batteryLevel = initialLevel.coerceIn(0, 100),
            voltageVolts = initialVoltage.coerceAtLeast(0f),
            temperature = initialTemp,
            powerWatts = initialPower.coerceAtLeast(0f),
            activeAppIcons = emptyList(),
            isScreenOn = isScreenOn,
            activeAppNames = emptyList()
        )
        dischargeRealtimeSamples.add(firstPoint)
        saveDischargeSamplesToPrefsAsync()
    }

    /**
     * 将当前放电周期的秒级瞬时采样点序列持久化保存至专属私有文件，避免膨胀主 SharedPreferences。
     * 采用轻量流式 [StringBuilder] 纯文本格式化输出，彻底消除高频创建数万个 [org.json.JSONObject]
     * 与哈希表节点带来的巨量堆内存分配与垃圾回收（GC）暂停开销。
     */
    @Synchronized
    private fun saveDischargeSamplesToPrefs() {
        try {
            lastDischargeSaveTimeMs = System.currentTimeMillis()
            val snapshot = ArrayList(dischargeRealtimeSamples)
            if (snapshot.isEmpty()) {
                val targetFile = java.io.File(context.filesDir, "discharge_samples.json")
                if (targetFile.exists()) targetFile.delete()
                return
            }

            val sb = java.lang.StringBuilder(snapshot.size * 90)
            sb.append('[')
            for (i in snapshot.indices) {
                val p = snapshot[i]
                if (i > 0) sb.append(',')
                sb.append("{\"ts\":").append(p.timestamp)
                    .append(",\"elapsed\":").append(p.elapsedHours)
                    .append(",\"lvl\":").append(p.batteryLevel)
                    .append(",\"volt\":").append(p.voltageVolts)
                    .append(",\"temp\":").append(p.temperature)
                    .append(",\"pwr\":").append(p.powerWatts)
                    .append(",\"screenOn\":").append(p.isScreenOn)
                if (!p.packageName.isNullOrEmpty()) {
                    sb.append(",\"pkg\":\"").append(p.packageName.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"")
                }
                sb.append('}')
            }
            sb.append(']')

            val targetFile = java.io.File(context.filesDir, "discharge_samples.json")
            val tempFile = java.io.File(context.filesDir, "discharge_samples.json.tmp")
            tempFile.writeText(sb.toString(), Charsets.UTF_8)
            if (tempFile.exists()) {
                if (targetFile.exists()) targetFile.delete()
                tempFile.renameTo(targetFile)
            }
            // 若旧 SharedPreferences 中仍残留旧超大键值，予以清理瘦身
            if (prefs.contains(PREF_KEY_REALTIME_SAMPLES_JSON)) {
                prefs.edit().remove(PREF_KEY_REALTIME_SAMPLES_JSON).apply()
            }
        } catch (_: Exception) {}
    }

    /**
     * 从专属私有文件（及兼容旧 SharedPreferences）恢复加载已保存的秒级瞬时放电采样点序列。
     */
    @Synchronized
    private fun loadDischargeSamplesFromPrefs() {
        try {
            val targetFile = java.io.File(context.filesDir, "discharge_samples.json")
            val jsonStr = if (targetFile.exists() && targetFile.canRead()) {
                targetFile.readText(Charsets.UTF_8)
            } else {
                val legacyStr = prefs.getString(PREF_KEY_REALTIME_SAMPLES_JSON, null)
                if (legacyStr != null) {
                    // 平滑迁移至私有文件并清理旧 Preference
                    try {
                        targetFile.writeText(legacyStr, Charsets.UTF_8)
                        prefs.edit().remove(PREF_KEY_REALTIME_SAMPLES_JSON).apply()
                    } catch (_: Exception) {}
                }
                legacyStr
            } ?: return

            val jsonArray = org.json.JSONArray(jsonStr)
            dischargeRealtimeSamples.clear()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val pkgName = obj.optString("pkg").takeIf { it.isNotEmpty() }
                dischargeRealtimeSamples.add(
                    PowerDischargePoint(
                        timestamp = obj.optLong("ts", 0L),
                        elapsedHours = obj.optDouble("elapsed", 0.0).toFloat(),
                        batteryLevel = obj.optInt("lvl", 100),
                        voltageVolts = obj.optDouble("volt", 3.85).toFloat(),
                        temperature = obj.optDouble("temp", 30.0).toFloat(),
                        powerWatts = obj.optDouble("pwr", 2.0).toFloat(),
                        isScreenOn = obj.optBoolean("screenOn", true),
                        packageName = pkgName
                    )
                )
            }
        } catch (_: Exception) {}
    }

    /**
     * 记录放电期间的一个电池温度采样点。
     * 内置 10 秒时间或 0.2℃ 温差防抖过滤，防止无意义重复采样。
     *
     * @param timestamp 采样时间戳（毫秒）
     * @param tempCelsius 采集到的温度数值（摄氏度）
     */
    @Synchronized
    fun recordDischargeTempSample(timestamp: Long, tempCelsius: Float) {
        val formatted = (Math.round(tempCelsius * 10f) / 10f)
        val lastPoint = dischargeTempPoints.lastOrNull()
        if (lastPoint == null || (timestamp - lastPoint.first) >= 10000L || Math.abs(formatted - lastPoint.second) >= 0.2f) {
            dischargeTempPoints.add(Pair(timestamp, formatted))
            // 达到容量上限时进行全局等距抽稀（50%），确保放电温度时间轴完整
            if (dischargeTempPoints.size > 3000) {
                val downsampled = mutableListOf<Pair<Long, Float>>()
                downsampled.add(dischargeTempPoints.first())
                for (i in 1 until dischargeTempPoints.size - 1 step 2) {
                    downsampled.add(dischargeTempPoints[i])
                }
                downsampled.add(dischargeTempPoints.last())
                dischargeTempPoints.clear()
                dischargeTempPoints.addAll(downsampled)
            }
        }
    }

    /**
     * 获取当前放电周期记录的所有时序温度采样点。
     *
     * @return 时序温度采样点列表 [List<Pair<Long, Float>>]
     */
    @Synchronized
    fun getDischargeTempPoints(): List<Pair<Long, Float>> {
        return dischargeTempPoints.toList()
    }

    /**
     * 清空当前放电周期的时序温度采样点并设置初始起点。
     *
     * @param timestamp 起始时间戳（毫秒）
     * @param initialTemp 初始温度（摄氏度）
     */
    @Synchronized
    fun resetDischargeTempPoints(timestamp: Long, initialTemp: Float) {
        dischargeTempPoints.clear()
        val formatted = (Math.round(initialTemp * 10f) / 10f)
        dischargeTempPoints.add(Pair(timestamp, formatted))
    }

    /**
     * 当外部电源断开（拔掉充电器）或用户手动重置时触发，重置当前放电统计周期基准。
     *
     * @param unplugLevel 断开电源时刻的电池电量百分比
     */
    fun onPowerDisconnected(unplugLevel: Int) {
        val now = System.currentTimeMillis()
        val status = getCurrentBatteryStatus()
        resetDischargeTempPoints(now, status.temperature)
        resetDischargeRealtimeSamples(now, unplugLevel, status.voltageVolts, status.temperature, 0f, true)

        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val counterUah = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: 0
        val editor = prefs.edit()
            .putLong(PREF_KEY_LAST_UNPLUG_TIME, now)
            .putInt(PREF_KEY_LAST_UNPLUG_LEVEL, unplugLevel.coerceIn(0, 100))
            .putLong("pref_last_archived_unplug_time", 0L)
            .putLong("last_reset_time", now)
            .remove(PREF_KEY_UNPLUG_USAGE_SNAPSHOT)
            .remove(PREF_KEY_UNPLUG_BG_SERVICE_SNAPSHOT)
        if (counterUah > 0) {
            editor.putInt(PREF_KEY_LAST_UNPLUG_CHARGE_COUNTER, counterUah)
        }
        editor.apply()

        // 重置放电周期归档防重标记，开启全新放电周期
        lastArchivedUnplugTime = 0L

        // 记录断电瞬间各应用使用时间基准快照，用于精确计算自拔电以来的实际增量时长
        saveUnplugUsageSnapshot()

        // 若已取得 Shizuku 授权，主动执行底层 dumpsys batterystats --reset 清零
        if (isShizukuAuthorized()) {
            shizukuParser.resetBatteryStats()
        }
    }

    /**
     * 当外部电源连接（插入充电器）时触发，结算并归档上一个放电周期的耗电账本快照，并清空当前放电周期统计数据。
     *
     * @param timestamp 触发连接电源时的时间戳毫秒值，默认取当前系统时间
     * @return 成功归档的耗电快照记录 [PowerUsageRecord]，若未满足归档条件则返回 null
     */
    @Synchronized
    fun onPowerConnected(timestamp: Long = System.currentTimeMillis()): PowerUsageRecord? {
        // 1. 归档上一个放电周期的耗电账本快照
        val record = archiveDischargeSession(timestamp)

        // 2. 彻底重置放电采样点与屏幕/应用使用基准快照，确保充电期间不污染旧放电账本
        resetPowerStats()

        return record
    }

    /**
     * 结算并归档当前放电周期的完整耗电账本快照入库。
     * 具备线程互斥（@Synchronized）与放电时间戳幂等防重守卫，杜绝 Service 与 Receiver 并发广播导致重复入库。
     *
     * @param now 触发插电或结算时刻的时间戳毫秒值
     * @return 成功归档的 [PowerUsageRecord] 快照实体，若周期不足30秒或已归档过则返回 null
     */
    @Synchronized
    fun archiveDischargeSession(now: Long = System.currentTimeMillis()): PowerUsageRecord? {
        val lastUnplugTime = getLastUnplugTime()
        if (lastUnplugTime <= 0L || (now - lastUnplugTime) <= 30000L) {
            return null
        }
        // 关键幂等防重：同一拔电周期的放电账本只允许归档一次
        if (lastArchivedUnplugTime == lastUnplugTime) {
            return null
        }

        return try {
            val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(now))
            val currentMode = getSelectedMode()
            val fullPackage = loadPowerData(currentMode)
            val powerRecord = PowerUsageRecord.fromFullPowerPackage(
                fullPackage = fullPackage,
                recordTime = timeStr,
                id = now
            )
            val powerDbHelper = PowerUsageDbHelper.getInstance(context)
            powerDbHelper.insertRecord(powerRecord)
            lastArchivedUnplugTime = lastUnplugTime
            prefs.edit().putLong("pref_last_archived_unplug_time", lastUnplugTime).apply()
            powerRecord
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 校验并自愈放电统计断层状态。
     * 当应用被强杀或长时间离线再次启动时：
     * 1. 若当前设备未在充电，且当前电量大于先前记录的拔电基准电量（发生离线充电且未被捕捉到拔电），
     *    自动将拔电基准电量校准为当前电量（或通过 Shizuku 回溯真实拔电时刻），并刷新应用使用基准快照，
     *    防止因负掉电量导致放电功耗失真；
     * 2. 若拔电时间戳距离当前已超过 48 小时且无底层 dumpsys 支撑，自动平滑对齐基准。
     *
     * @return 若执行了自愈校准返回 true，否则返回 false
     */
    fun checkAndReconcileDischargeState(): Boolean {
        val batterySnapshot = getCurrentBatteryStatus()
        val currentLevel = batterySnapshot.levelPercent
        val isCharging = batterySnapshot.isCharging
        val lastUnplugTime = getLastUnplugTime()
        val lastUnplugLevel = getLastUnplugLevel()
        val now = System.currentTimeMillis()
        var reconciled = false

        if (!isCharging) {
            // 异常场景：离线期间充过电，导致当前电量高于上次记录的拔电电量
            if (currentLevel > lastUnplugLevel) {
                // 尝试通过 Shizuku 探测真实拔电时刻与电量
                var syncedViaShizuku = false
                if (isShizukuAuthorized()) {
                    try {
                        val stats = shizukuParser.parseChargedBatteryStats(
                            batterySnapshot.voltageVolts,
                            batterySnapshot.temperature,
                            lastUnplugTime,
                            getDischargeTempPoints()
                        )
                        if (stats.detectedUnplugLevel != null && stats.detectedUnplugLevel >= currentLevel) {
                            val detectedTime = if (stats.detectedUnplugTs != null && stats.detectedUnplugTs > 0L) {
                                stats.detectedUnplugTs
                            } else {
                                now - stats.dischargeDurationMs
                            }
                            val shouldAdopt = (lastUnplugTime <= 0L) || (detectedTime > lastUnplugTime && detectedTime <= now)
                            if (shouldAdopt) {
                                prefs.edit()
                                    .putInt(PREF_KEY_LAST_UNPLUG_LEVEL, stats.detectedUnplugLevel)
                                    .putLong(PREF_KEY_LAST_UNPLUG_TIME, detectedTime)
                                    .apply()
                                saveUnplugUsageSnapshot()
                                syncedViaShizuku = true
                                reconciled = true
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                if (!syncedViaShizuku) {
                    // 普通模式兜底自愈：重置基准电量为当前电量，拔电时间重置为当前时刻
                    onPowerDisconnected(currentLevel)
                    reconciled = true
                }
            } else if (lastUnplugTime <= 0L || (now - lastUnplugTime) > 48 * 3600000L) {
                // 首次进入无记录或长期未更新拔电时间，自动修正基准
                var syncedViaShizuku = false
                if (isShizukuAuthorized()) {
                    try {
                        val stats = shizukuParser.parseChargedBatteryStats(
                            batterySnapshot.voltageVolts,
                            batterySnapshot.temperature,
                            0L,
                            getDischargeTempPoints()
                        )
                        if (stats.detectedUnplugLevel != null && stats.detectedUnplugLevel >= currentLevel) {
                            val detectedTime = if (stats.detectedUnplugTs != null && stats.detectedUnplugTs > 0L) {
                                stats.detectedUnplugTs
                            } else {
                                now - stats.dischargeDurationMs
                            }
                            val shouldAdopt = (lastUnplugTime <= 0L) || (detectedTime > lastUnplugTime && detectedTime <= now)
                            if (shouldAdopt) {
                                prefs.edit()
                                    .putInt(PREF_KEY_LAST_UNPLUG_LEVEL, stats.detectedUnplugLevel)
                                    .putLong(PREF_KEY_LAST_UNPLUG_TIME, detectedTime)
                                    .apply()
                                saveUnplugUsageSnapshot()
                                syncedViaShizuku = true
                                reconciled = true
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                if (!syncedViaShizuku) {
                    onPowerDisconnected(currentLevel)
                    reconciled = true
                }
            }
        }

        return reconciled
    }

    /**
     * 保存断开外部电源瞬间系统所有应用的前台使用时长与前台服务时长基准快照。
     * 为自拔电以来的增量计算提供可靠基准，杜绝历史后台服务时长跨周期渗透。
     */
    fun saveUnplugUsageSnapshot() {
        if (!hasUsageStatsPermission()) return
        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
            val now = System.currentTimeMillis()
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -1)
            val statsMap = usm.queryAndAggregateUsageStats(cal.timeInMillis, now)
            val fgJson = org.json.JSONObject()
            val bgServiceJson = org.json.JSONObject()
            for ((pkg, usage) in statsMap) {
                if (usage.totalTimeInForeground > 0L) {
                    fgJson.put(pkg, usage.totalTimeInForeground)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && usage.totalTimeForegroundServiceUsed > 0L) {
                    bgServiceJson.put(pkg, usage.totalTimeForegroundServiceUsed)
                }
            }
            prefs.edit()
                .putString(PREF_KEY_UNPLUG_USAGE_SNAPSHOT, fgJson.toString())
                .putString(PREF_KEY_UNPLUG_BG_SERVICE_SNAPSHOT, bgServiceJson.toString())
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 计算自拔电断开以来各应用真正增加的前台使用时长（毫秒）及最后活跃时间。
     * 严格校验最后活跃时间 lastTimeUsed >= unplugTime，防止历史未使用应用渗透。
     *
     * @return 映射包名到 Pair(增量毫秒数, 最后活跃时间戳) 的字典
     */
    fun getUnplugUsageDeltas(): Map<String, Pair<Long, Long>> {
        val result = mutableMapOf<String, Pair<Long, Long>>()
        if (!hasUsageStatsPermission()) return result

        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return result
            val now = System.currentTimeMillis()
            val unplugTime = getLastUnplugTime()
            val elapsedSinceUnplug = if (unplugTime > 0L) (now - unplugTime).coerceAtLeast(1000L) else 86400000L
            val startTime = if (unplugTime > 0L) unplugTime else (now - elapsedSinceUnplug)

            // 优先直接通过基于事件状态机的精确查询，获取自拔电/重置以来的各应用真实前台活跃时长
            val preciseTimes = queryPreciseForegroundTimes(usm, startTime, now)
            if (preciseTimes.isNotEmpty()) {
                for ((pkg, timeMs) in preciseTimes) {
                    if (timeMs > 0L) {
                        result[pkg] = Pair(timeMs, now)
                    }
                }
                return result
            }

            // 若事件流无返回（兜底查询 UsageStats），需增加严格的 lastTimeUsed 过滤
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -1)
            val currentStatsMap = usm.queryAndAggregateUsageStats(cal.timeInMillis, now)

            val snapshotStr = prefs.getString(PREF_KEY_UNPLUG_USAGE_SNAPSHOT, null)
            val snapshotJson = if (!snapshotStr.isNullOrEmpty()) org.json.JSONObject(snapshotStr) else null

            for ((pkg, usage) in currentStatsMap) {
                // 关键校验：必须满足在自拔电/重置以来确实活跃过，杜绝把历史未使用的应用算入当前周期
                if (unplugTime > 0L && usage.lastTimeUsed < unplugTime) {
                    continue
                }

                val currentFg = usage.totalTimeInForeground
                val baselineFg = snapshotJson?.optLong(pkg, 0L) ?: 0L

                val delta = if (snapshotJson != null) {
                    (currentFg - baselineFg).coerceAtLeast(0L)
                } else {
                    if (unplugTime > 0L && usage.lastTimeUsed >= unplugTime) {
                        currentFg.coerceAtMost(elapsedSinceUnplug)
                    } else {
                        0L
                    }
                }

                val finalDelta = delta.coerceAtMost(elapsedSinceUnplug)
                val lastUsed = if (usage.lastTimeUsed > 0L) usage.lastTimeUsed else now
                if (finalDelta > 0L) {
                    result[pkg] = Pair(finalDelta, lastUsed)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return result
    }

    /**
     * 获取最近一次拔掉电源的时间戳（毫秒值）。
     *
     * @return 最近拔电时间戳，若无记录则返回 0L
     */
    fun getLastUnplugTime(): Long {
        return prefs.getLong(PREF_KEY_LAST_UNPLUG_TIME, 0L)
    }

    /**
     * 获取最近一次拔掉电源时的初始电量百分比。
     * 若首次启动尚未产生拔电记录，以当前设备实际电量作为初始基准，避免误判为 100% 导致虚高功耗。
     *
     * @return 拔电时的初始电量百分比
     */
    fun getLastUnplugLevel(): Int {
        val saved = prefs.getInt(PREF_KEY_LAST_UNPLUG_LEVEL, -1)
        return if (saved in 1..100) saved else getCurrentBatteryStatus().levelPercent
    }

    /**
     * 判断用户是否已完成过耗电统计检测模式的首次选择配置。
     *
     * @return 若已明确选择过模式返回 true，若为首次进入尚未配置返回 false
     */
    fun isPowerModeConfigured(): Boolean {
        return prefs.getBoolean(PREF_KEY_POWER_CONFIGURED, false)
    }

    /**
     * 标记或更新耗电检测模式已完成配置状态。
     *
     * @param configured 是否已完成配置
     */
    fun setPowerModeConfigured(configured: Boolean) {
        prefs.edit().putBoolean(PREF_KEY_POWER_CONFIGURED, configured).apply()
    }

    /**
     * 检查当前系统环境是否已安装 Shizuku 应用程序。
     *
     * @return 若已安装返回 true，未安装或查询异常返回 false
     */
    fun isShizukuInstalled(): Boolean {
        // 优先检查 Binder：若服务已连接运行，则必然已安装
        if (isShizukuRunning()) {
            return true
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    "moe.shizuku.privileged.api",
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Exception) {
            // 兼容性降级：尝试通过 LaunchIntent 确认安装状态
            try {
                context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api") != null
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * 获取当前选中的工作模式。
     * 若用户未曾主动配置过偏好，则根据当前设备是否安装 Shizuku 智能选择默认模式：
     * 安装了 Shizuku 则优先使用 [MODE_SHIZUKU]，未安装则降级为 [MODE_NORMAL]。
     *
     * @return 当前生效的工作模式（[MODE_SHIZUKU] 或 [MODE_NORMAL]）
     */
    fun getSelectedMode(): Int {
        val defaultMode = if (isShizukuInstalled()) MODE_SHIZUKU else MODE_NORMAL
        val mode = prefs.getInt(PREF_KEY_POWER_MODE, defaultMode)
        // 防呆校准：若之前记录为 Shizuku 模式但检测到设备未安装 Shizuku，自动降级为普通模式
        return if (mode == MODE_SHIZUKU && !isShizukuInstalled()) {
            MODE_NORMAL
        } else {
            mode
        }
    }

    /**
     * 设置并持久化用户选择的工作模式。
     *
     * @param mode 工作模式（[MODE_SHIZUKU] 或 [MODE_NORMAL]）
     */
    fun setSelectedMode(mode: Int) {
        prefs.edit().putInt(PREF_KEY_POWER_MODE, mode).apply()
    }



    /**
     * 检查 Shizuku 服务当前是否正在运行且已连接。
     *
     * @return 若服务正常返回 true，否则返回 false
     */
    fun isShizukuRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 检查当前应用是否已被授予 Shizuku 权限（且未被用户主动停用）。
     *
     * @return 若已授权返回 true，否则返回 false
     */
    fun isShizukuAuthorized(): Boolean {
        return ShizukuManager.isAuthorized(context)
    }

    /**
     * 检查当前应用是否已被用户授予“有权查看使用情况的应用”权限。
     *
     * @return 若已授权返回 true，否则返回 false
     */
    fun hasUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * 当取得 Shizuku 权限后，自动通过 ADB Shell 提权为本应用授予系统“使用情况访问权限” (GET_USAGE_STATS)。
     * 免除用户手动跳转系统深层设置界面的繁琐操作，实现开箱即用的高精度前台使用统计。
     * 若已拥有权限则直接短路返回，避免冷启动与每次刷新时无谓执行昂贵的 Shell 子进程。
     *
     * @return 赋权操作后是否已成功拥有使用情况访问权限
     */
    fun grantUsageStatsPermissionViaShizuku(): Boolean {
        // 先验短路检查：若当前应用已拥有使用情况访问权限，立即返回 true，避免耗费 600ms~1500ms 重复执行 Shell 命令
        if (hasUsageStatsPermission()) {
            return true
        }
        if (!isShizukuAuthorized()) {
            return false
        }
        try {
            val pkg = context.packageName
            // 合并为单次 Shell 调用执行，减少两次子进程创建与 Binder 交互开销
            shizukuParser.executeShizukuShellCommand("appops set $pkg GET_USAGE_STATS allow && appops set $pkg android:get_usage_stats allow && pm grant $pkg android.permission.PACKAGE_USAGE_STATS")
        } catch (_: Exception) {
        }
        return hasUsageStatsPermission()
    }

    /**
     * 读取当前系统的实时电池状态参数。
     *
     * @return 包含当前电量百分比、电压(V)、温度(℃)、能量(Wh)及充电状态的五元组
     */
    fun getCurrentBatteryStatus(): BatteryStatusSnapshot {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 65) ?: 65
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (scale > 0) (level * 100 / scale) else level

        val rawVoltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 3972) ?: 3972
        val voltageVolts = com.battery.analysis.util.BatteryUnitNormalizer.normalizeVoltageVolts(rawVoltage.toLong())

        val rawTemp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 305) ?: 305
        val tempCelsius = rawTemp / 10f

        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING)
            ?: BatteryManager.BATTERY_STATUS_DISCHARGING
        val isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)

        // 精确计算当前剩余能量（优先硬件计数器，其次多级真实容量推算）
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val hardwareEnergyNwh = bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
        val hardwareChargeCounterUah = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)

        val effectiveCapacity = getEffectiveDeviceCapacityMah()
        val energyWh = BatteryEnergyCalculator.calculateRemainingEnergyWh(
            hardwareEnergyNwh = hardwareEnergyNwh,
            hardwareChargeCounterUah = hardwareChargeCounterUah,
            batteryPercent = percent,
            nominalVoltageVolts = BatteryEnergyCalculator.DEFAULT_NOMINAL_VOLTAGE_VOLTS,
            effectiveCapacityMah = effectiveCapacity
        )
        val totalEnergyWh = BatteryEnergyCalculator.calculateTotalEnergyWh(
            effectiveCapacityMah = effectiveCapacity,
            nominalVoltageVolts = BatteryEnergyCalculator.DEFAULT_NOMINAL_VOLTAGE_VOLTS
        )

        return BatteryStatusSnapshot(
            levelPercent = percent,
            voltageVolts = voltageVolts,
            temperature = tempCelsius,
            energyWh = energyWh,
            isCharging = isCharging,
            totalEnergyWh = totalEnergyWh
        )
    }

    @Volatile
    private var cachedEffectiveCapacity: Float? = null
    @Volatile
    private var lastCapacityCachedTime: Long = 0L

    /**
     * 获取设备当前最精准的基准电池容量（优先实际满充容量 FCC，其次设计容量）。
     *
     * 优先级策略：
     * 1. 历史数据库中最新记录的真实满充容量 [HistoryRecord.fullChargeCapacity]（反映真实电池健康衰减）；
     * 2. 历史数据库中最新记录的出厂设计容量 [HistoryRecord.designCapacity]；
     * 3. 系统底层 PowerProfile 反射读取的电池额定容量；
     * 4. 若均无法获取则如实返回 0f（不伪造保底数据）。
     * 内部具备 60 秒轻量内存缓存与单例复用，消除高频统计计算中的重复数据库 I/O 开销与连接泄漏。
     *
     * @return 设备基准电池容量（单位：mAh）
     */
    fun getEffectiveDeviceCapacityMah(): Float {
        val now = SystemClock.elapsedRealtime()
        val cached = cachedEffectiveCapacity
        if (cached != null && (now - lastCapacityCachedTime) < 60_000L) {
            return cached
        }

        var capacity = 0f
        // 1. 优先从历史快照记录中获取经过算法融合或 Shizuku/Bugreport 提取到的真实满充容量与设计容量
        try {
            val dbHelper = HistoryDbHelper.getInstance(context)
            val latestRecord = dbHelper.getLatestRecord()
            val fcc = latestRecord?.fullChargeCapacity
            if (fcc != null && fcc > 0f) {
                capacity = fcc
            } else {
                val design = latestRecord?.designCapacity
                if (design != null && design > 0f) {
                    capacity = design
                }
            }
        } catch (e: Exception) {
            // 数据库读取容错
        }

        // 2. 尝试从系统 PowerProfile 反射获取出厂设计容量
        if (capacity <= 0f) {
            val powerProfileCap = NormalApiProvider.getDesignCapacity(context)
            if (powerProfileCap != null && powerProfileCap > 0f) {
                capacity = powerProfileCap
            }
        }

        // 3. 若均无法获取则如实返回 0f（不伪造保底数据）
        cachedEffectiveCapacity = capacity
        lastCapacityCachedTime = now
        return capacity
    }

    /**
     * 加载当前模式下的完整耗电数据（包含各应用耗电列表与三大核心指标）。
     *
     * @param mode 当前指定的工作模式（[MODE_SHIZUKU] 或 [MODE_NORMAL]）
     * @return 完整的功耗与应用列表数据包装 [FullPowerDataPackage]
     */
    fun loadPowerData(mode: Int): FullPowerDataPackage {
        val batterySnapshot = getCurrentBatteryStatus()
        val unplugTime = getLastUnplugTime()
        val unplugLevel = getLastUnplugLevel().coerceIn(0, 100)
        val now = System.currentTimeMillis()

        // 1. Shizuku 模式且已获得授权
        if (mode == MODE_SHIZUKU && isShizukuAuthorized()) {
            val stats = shizukuParser.parseChargedBatteryStats(
                batteryVoltageVolts = batterySnapshot.voltageVolts,
                batteryTempCelsius = batterySnapshot.temperature,
                unplugTime = unplugTime,
                localHistoryTempPoints = getDischargeTempPoints()
            )

            if (stats.appList.isNotEmpty() || stats.dischargeDurationMs > 0L) {
                // 若探测到底层真实拔电时刻与电量，动态同步至本地存储，纠正首次进入缺乏记录时的错误默认值
                var effectiveUnplugLevel = unplugLevel
                var effectiveUnplugTime = unplugTime
                if (stats.detectedUnplugLevel != null && stats.detectedUnplugLevel >= batterySnapshot.levelPercent) {
                    val detectedTs = stats.detectedUnplugTs
                    val shouldAdoptDetected = (effectiveUnplugTime <= 0L) ||
                            (detectedTs != null && detectedTs > effectiveUnplugTime && detectedTs <= now)
                    if (shouldAdoptDetected) {
                        effectiveUnplugLevel = stats.detectedUnplugLevel
                        if (detectedTs != null && detectedTs > 0L) {
                            effectiveUnplugTime = detectedTs
                        }
                        prefs.edit()
                            .putInt(PREF_KEY_LAST_UNPLUG_LEVEL, effectiveUnplugLevel)
                            .putLong(PREF_KEY_LAST_UNPLUG_TIME, effectiveUnplugTime)
                            .apply()
                    }
                }

                val durationMs = if (effectiveUnplugTime in 1..now && (now - effectiveUnplugTime) <= (48 * 3600_000L)) {
                    (now - effectiveUnplugTime).coerceAtLeast(1000L)
                } else {
                    stats.dischargeDurationMs.coerceAtLeast(1000L)
                }

                val startTs = now - durationMs
                val (appIntervals, screenIntervals) = queryUsageIntervals(startTs, now)

                // 优先通过高精度系统屏幕事件流校准真实亮屏与息屏时长，彻底杜绝息屏时长被误判为 0
                val eventScreenOnMs = calculateScreenOnDurationFromIntervals(screenIntervals, startTs, now)
                val eventScreenOffMs = (durationMs - eventScreenOnMs).coerceAtLeast(0L)

                val screenOnMs: Long
                val screenOffMs: Long
                if (screenIntervals.isNotEmpty() && eventScreenOnMs > 0L) {
                    screenOnMs = eventScreenOnMs.coerceIn(0L, durationMs)
                    screenOffMs = eventScreenOffMs.coerceIn(0L, durationMs)
                } else if (stats.screenOffDurationMs > 0L) {
                    screenOffMs = stats.screenOffDurationMs.coerceAtMost(durationMs)
                    screenOnMs = stats.screenOnDurationMs.coerceIn(0L, (durationMs - screenOffMs).coerceAtLeast(0L))
                } else {
                    screenOnMs = stats.screenOnDurationMs.coerceIn(0L, durationMs)
                    screenOffMs = (durationMs - screenOnMs).coerceAtLeast(0L)
                }

                val screenOnStr = formatDuration(screenOnMs)
                val screenOffStr = formatDuration(screenOffMs)
                val totalDurationStr = formatDuration(durationMs)
                val durationStr = "$screenOnStr / $totalDurationStr"

                val effectiveCapacity = getEffectiveDeviceCapacityMah()
                // 能量计算统一遵循工业标准与标称工作电压（标压 3.85V），杜绝端电压瞬时压降与回弹波动对累计能耗产生干扰
                val nominalVoltageVolts = BatteryEnergyCalculator.DEFAULT_NOMINAL_VOLTAGE_VOLTS
                val dischargeHours = durationMs / 3600000f
                val screenOnHours = screenOnMs / 3600000f
                val screenOffHours = screenOffMs / 3600000f

                // 起始电量：优先取探测到的真实拔电电量，其次取有效拔电记录，仅在本次放电时间区间内回溯历史电量，最后以当前电量保底
                val intervalHistoryMax = stats.historyLevelPoints
                    .filter { it.first >= startTs }
                    .maxOfOrNull { it.second }
                val startLevel = when {
                    stats.detectedUnplugLevel != null && stats.detectedUnplugLevel >= batterySnapshot.levelPercent -> {
                        stats.detectedUnplugLevel
                    }
                    effectiveUnplugTime > 0L && effectiveUnplugLevel >= batterySnapshot.levelPercent -> {
                        effectiveUnplugLevel
                    }
                    intervalHistoryMax != null && intervalHistoryMax >= batterySnapshot.levelPercent -> {
                        intervalHistoryMax
                    }
                    else -> {
                        batterySnapshot.levelPercent
                    }
                }
                val currentLevel = batterySnapshot.levelPercent
                val dropPercent = (startLevel - currentLevel).coerceAtLeast(0)

                // 1. 优先使用系统 dumpsys batterystats 权威连续放电量 computedDrainMah（高精度浮点），
                // 杜绝整数百分比跳变导致的短期虚假功耗尖峰（如 1% 掉电除以微小时长导致虚高 10W+）；
                // 仅在无底层连续放电量记录时，依硬件电荷计数器、应用能耗累加或百分比变化回退
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                val currentCounterUah = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: 0
                val lastCounterUah = prefs.getInt(PREF_KEY_LAST_UNPLUG_CHARGE_COUNTER, 0)
                val hwDischargedMah = if (lastCounterUah > 0 && currentCounterUah > 0 && lastCounterUah >= currentCounterUah) {
                    (lastCounterUah - currentCounterUah) / 1000f
                } else {
                    0f
                }

                // 物理守恒底线校验：整机放电量必不小于各应用实耗电量与屏幕硬件耗电量之和（整体大于等于部分之和）
                // 核心防护：若某些应用包含跨周期历史累积时长（如后台 13 小时），在当前放电周期（如 18 秒）内，
                // 其真实消耗必须按当前放电周期时长进行时间窗口归一，杜绝历史能耗污染当前周期引发 89W 虚假尖峰
                val currentPeriodAppEnergyWh = stats.appList.sumOf { item ->
                    val totalAppTimeMs = item.foregroundTimeMs + item.backgroundTimeMs
                    if (totalAppTimeMs > durationMs && totalAppTimeMs > 0L) {
                        (item.energyWh * (durationMs.toDouble() / totalAppTimeMs.toDouble())).coerceAtMost(item.energyWh.toDouble())
                    } else {
                        item.energyWh.toDouble()
                    }
                }.toFloat()
                val appEnergyMah = if (currentPeriodAppEnergyWh > 0f) (currentPeriodAppEnergyWh * 1000f / nominalVoltageVolts) else 0f
                val minPhysicalMah = appEnergyMah + stats.screenDrainMah

                // 真实放电量计算
                val smoothedDropMah = if (dropPercent > 0) {
                    effectiveCapacity * (dropPercent / 100f)
                } else {
                    0f
                }

                // 物理守恒基础：硬件芯片库仑计差值与纯应用前台实耗电量，绝不采用 dumpsys computedDrainMah 软件估算
                val validHwMah = if (hwDischargedMah > 0f) hwDischargedMah else 0f
                val physicalDrainMah = when {
                    validHwMah > 0f -> validHwMah
                    dropPercent > 0 -> smoothedDropMah
                    minPhysicalMah > 0f -> minPhysicalMah
                    else -> 0f
                }
                val physicalTotalEnergyWh = (physicalDrainMah * nominalVoltageVolts) / 1000f

                // 硬件时序切片微积分：通过底层 sysfs 采样点直接计算物理能耗与功率
                val recentSamples = getDischargeRealtimeSamples().filter { it.timestamp in (startTs - 60_000L)..now }
                val (intTotalEnergyWh, intTotalPowerWatts) = calculatePhysicalIntegratedEnergyAndPower(recentSamples, filterScreenOn = null)
                val (intOnEnergyWh, intOnPowerWatts) = calculatePhysicalIntegratedEnergyAndPower(recentSamples, filterScreenOn = true)
                val (intOffEnergyWh, intOffPowerWatts) = calculatePhysicalIntegratedEnergyAndPower(recentSamples, filterScreenOn = false)

                val hasValidHardwareIntegration = recentSamples.size >= 2 &&
                        (intTotalEnergyWh > 0f || intTotalPowerWatts > 0f || intOnPowerWatts > 0f || intOffPowerWatts > 0f)

                // 采样密度评估：计算平均采样间隔（毫秒），用于评估时序微积分的离散拟合可信度
                val avgSampleIntervalMs = if (recentSamples.size >= 2) {
                    val sampleSpanMs = recentSamples.last().timestamp - recentSamples.first().timestamp
                    if (sampleSpanMs > 0L) (sampleSpanMs / (recentSamples.size - 1)) else 0L
                } else {
                    0L
                }
                // 高频密集采样判定：平均采样间隔 <= 5000ms（如默认的 1 秒/2 秒等高频监控模式）
                val isHighFrequencySampling = hasValidHardwareIntegration && (avgSampleIntervalMs in 1L..5000L)

                var realTotalEnergyWh: Float
                var realDischargedMah: Float
                val avgWatts: Float
                val screenOnWatts: Float
                val screenOffWatts: Float
                val onEnergyWh: Float
                val offEnergyWh: Float

                if (isHighFrequencySampling) {
                    // 1. 高频密集硬件采样区间（<= 5 秒，完美捕捉瞬时脉冲细节，对标 BatteryRecorder）：
                    // 100% 采用时序梯形微积分算出的物理功耗与累积能耗
                    if (screenOffHours <= 0f || screenOffMs < 30000L) {
                        screenOnWatts = if (intOnPowerWatts > 0f) intOnPowerWatts else intTotalPowerWatts
                        screenOffWatts = 0f
                        offEnergyWh = 0f
                        onEnergyWh = if (intOnEnergyWh > 0f) intOnEnergyWh else if (intTotalEnergyWh > 0f) intTotalEnergyWh else (screenOnWatts * screenOnHours)
                        realTotalEnergyWh = onEnergyWh
                        avgWatts = screenOnWatts
                    } else if (screenOnHours <= 0f) {
                        screenOnWatts = 0f
                        onEnergyWh = 0f
                        screenOffWatts = if (intOffPowerWatts > 0f) intOffPowerWatts else intTotalPowerWatts
                        offEnergyWh = if (intOffEnergyWh > 0f) intOffEnergyWh else if (intTotalEnergyWh > 0f) intTotalEnergyWh else (screenOffWatts * screenOffHours)
                        realTotalEnergyWh = offEnergyWh
                        avgWatts = screenOffWatts
                    } else {
                        screenOnWatts = if (intOnPowerWatts > 0f) intOnPowerWatts else if (screenOnHours > 0f && intOnEnergyWh > 0f) (intOnEnergyWh / screenOnHours) else 0f
                        onEnergyWh = if (intOnEnergyWh > 0f) intOnEnergyWh else (screenOnWatts * screenOnHours)

                        if (intOffPowerWatts > 0f || intOffEnergyWh > 0f) {
                            screenOffWatts = if (intOffPowerWatts > 0f) intOffPowerWatts else if (screenOffHours > 0f && intOffEnergyWh > 0f) (intOffEnergyWh / screenOffHours) else 0f
                            offEnergyWh = if (intOffEnergyWh > 0f) intOffEnergyWh else (screenOffWatts * screenOffHours)
                        } else {
                            val baselineEnergyWh = physicalTotalEnergyWh
                            offEnergyWh = (baselineEnergyWh - onEnergyWh).coerceAtLeast(0f)
                            screenOffWatts = if (screenOffHours > 0f) offEnergyWh / screenOffHours else 0f
                        }

                        realTotalEnergyWh = onEnergyWh + offEnergyWh
                        avgWatts = if (dischargeHours > 0f) realTotalEnergyWh / dischargeHours else 0f
                    }
                    realDischargedMah = (realTotalEnergyWh * 1000f) / nominalVoltageVolts
                } else if (physicalTotalEnergyWh > 0.001f) {
                    // 2. 长间隔稀疏采样（如 10秒/30秒/60秒）或微积分未运行，但底层主板硬件芯片库仑计/实际掉电已有可信物理变化：
                    // 总能量绝对锚定主板芯片硬件库仑计或实际掉电量（100% 包含屏幕及所有外设），微积分仅作为亮灭屏相对功耗比例解耦
                    realDischargedMah = physicalDrainMah
                    realTotalEnergyWh = physicalTotalEnergyWh
                    avgWatts = if (dischargeHours > 0f) realTotalEnergyWh / dischargeHours else 0f

                    if (screenOffHours <= 0f || screenOffMs < 30000L) {
                        offEnergyWh = 0f
                        screenOffWatts = 0f
                        onEnergyWh = realTotalEnergyWh
                        screenOnWatts = if (screenOnHours > 0f) onEnergyWh / screenOnHours else avgWatts
                    } else if (screenOnHours <= 0f) {
                        onEnergyWh = 0f
                        screenOnWatts = 0f
                        offEnergyWh = realTotalEnergyWh
                        screenOffWatts = if (screenOffHours > 0f) offEnergyWh / screenOffHours else avgWatts
                    } else {
                        // 既有亮屏又有息屏：利用微积分测得的典型工况功率进行相对比例解耦，绝不虚构
                        if (intOnPowerWatts > 0.05f && intOffPowerWatts > 0.05f) {
                            val estOnE = intOnPowerWatts * screenOnHours
                            val estOffE = intOffPowerWatts * screenOffHours
                            val sumEstE = estOnE + estOffE
                            if (sumEstE > 0f) {
                                onEnergyWh = (realTotalEnergyWh * (estOnE / sumEstE)).coerceAtLeast(0f)
                                offEnergyWh = (realTotalEnergyWh - onEnergyWh).coerceAtLeast(0f)
                            } else {
                                val ratio = (screenOnHours / dischargeHours).coerceIn(0f, 1f)
                                onEnergyWh = realTotalEnergyWh * ratio
                                offEnergyWh = (realTotalEnergyWh - onEnergyWh).coerceAtLeast(0f)
                            }
                            screenOnWatts = if (screenOnHours > 0f) onEnergyWh / screenOnHours else intOnPowerWatts
                            screenOffWatts = if (screenOffHours > 0f) offEnergyWh / screenOffHours else intOffPowerWatts
                        } else if (intOnPowerWatts > 0.05f) {
                            val rawOnEnergy = intOnPowerWatts * screenOnHours
                            if (rawOnEnergy < realTotalEnergyWh) {
                                onEnergyWh = rawOnEnergy
                                offEnergyWh = realTotalEnergyWh - onEnergyWh
                            } else {
                                val ratio = (screenOnHours / dischargeHours).coerceIn(0f, 1f)
                                onEnergyWh = realTotalEnergyWh * ratio
                                offEnergyWh = (realTotalEnergyWh - onEnergyWh).coerceAtLeast(0f)
                            }
                            screenOnWatts = intOnPowerWatts
                            screenOffWatts = if (screenOffHours > 0f) offEnergyWh / screenOffHours else 0f
                        } else {
                            val fgTotalEnergy = stats.appList.sumOf { it.foregroundEnergyWh.toDouble() }.toFloat()
                            if (fgTotalEnergy in 0.001f..realTotalEnergyWh) {
                                onEnergyWh = fgTotalEnergy
                                offEnergyWh = realTotalEnergyWh - onEnergyWh
                            } else {
                                val ratio = (screenOnHours / dischargeHours).coerceIn(0f, 1f)
                                onEnergyWh = realTotalEnergyWh * ratio
                                offEnergyWh = (realTotalEnergyWh - onEnergyWh).coerceAtLeast(0f)
                            }
                            screenOnWatts = if (screenOnHours > 0f) onEnergyWh / screenOnHours else avgWatts
                            screenOffWatts = if (screenOffHours > 0f) offEnergyWh / screenOffHours else 0f
                        }
                    }
                } else if (hasValidHardwareIntegration) {
                    // 3. 刚拔电短时间内（物理电量/硬件库仑计尚未产生步进掉电），以微积分作为冷启动初始放电参考，杜绝展示为 0W
                    if (screenOffHours <= 0f || screenOffMs < 30000L) {
                        screenOnWatts = if (intOnPowerWatts > 0f) intOnPowerWatts else intTotalPowerWatts
                        screenOffWatts = 0f
                        offEnergyWh = 0f
                        onEnergyWh = if (intOnEnergyWh > 0f) intOnEnergyWh else if (intTotalEnergyWh > 0f) intTotalEnergyWh else (screenOnWatts * screenOnHours)
                        realTotalEnergyWh = onEnergyWh
                        avgWatts = screenOnWatts
                    } else if (screenOnHours <= 0f) {
                        screenOnWatts = 0f
                        onEnergyWh = 0f
                        screenOffWatts = if (intOffPowerWatts > 0f) intOffPowerWatts else intTotalPowerWatts
                        offEnergyWh = if (intOffEnergyWh > 0f) intOffEnergyWh else if (intTotalEnergyWh > 0f) intTotalEnergyWh else (screenOffWatts * screenOffHours)
                        realTotalEnergyWh = offEnergyWh
                        avgWatts = screenOffWatts
                    } else {
                        screenOnWatts = if (intOnPowerWatts > 0f) intOnPowerWatts else if (screenOnHours > 0f && intOnEnergyWh > 0f) (intOnEnergyWh / screenOnHours) else 0f
                        onEnergyWh = if (intOnEnergyWh > 0f) intOnEnergyWh else (screenOnWatts * screenOnHours)
                        screenOffWatts = if (intOffPowerWatts > 0f) intOffPowerWatts else if (screenOffHours > 0f && intOffEnergyWh > 0f) (intOffEnergyWh / screenOffHours) else 0f
                        offEnergyWh = if (intOffEnergyWh > 0f) intOffEnergyWh else (screenOffWatts * screenOffHours)
                        realTotalEnergyWh = onEnergyWh + offEnergyWh
                        avgWatts = if (dischargeHours > 0f) realTotalEnergyWh / dischargeHours else 0f
                    }
                    realDischargedMah = (realTotalEnergyWh * 1000f) / nominalVoltageVolts
                } else {
                    // 4. 完全无硬件采样且未掉电的极冷启动（刚拔电数秒内）
                    realDischargedMah = 0f
                    realTotalEnergyWh = 0f
                    avgWatts = 0f
                    screenOnWatts = 0f
                    screenOffWatts = 0f
                    onEnergyWh = 0f
                    offEnergyWh = 0f
                }

                // 4. 计算理论剩余续航：当前能量 Wh / 对应工况功耗 W（低于 0.05W 显示 "--" 杜绝荒谬的超长续航）
                // 4. 计算理论剩余续航：当前能量 Wh / 对应工况功耗 W（低于 0.05W 显示 "--" 杜绝荒谬的超长续航）
                val energy = batterySnapshot.energyWh
                val remCompositeStr = if (avgWatts >= 0.05f) formatHoursToText(energy / avgWatts) else "--"
                val remScreenOnStr = if (screenOnWatts >= 0.05f) formatHoursToText(energy / screenOnWatts) else "--"
                val remScreenOffStr = if (screenOffWatts >= 0.05f) formatHoursToText(energy / screenOffWatts) else "--"

                // 不限制在亮屏总时长内，上限以本次放电周期的实际总时长 durationMs 为准
                // 若刚拔电或重置（小于5分钟），仅清除明显超出放电总时长的跨周期超大脏数据（>300秒），采样轻微超出则截断为 durationMs
                val maxAllowedMs = durationMs
                val rawAppList = stats.appList.map { item ->
                    if (item.foregroundTimeMs > maxAllowedMs) {
                        if (effectiveUnplugTime > 0L && (now - effectiveUnplugTime) < 300000L && item.foregroundTimeMs > 300000L) {
                            val totalE = item.energyWh
                            item.copy(
                                foregroundTimeMs = 0L,
                                foregroundEnergyWh = 0f,
                                backgroundEnergyWh = totalE,
                                directEnergyWh = totalE
                            )
                        } else {
                            item.copy(foregroundTimeMs = maxAllowedMs)
                        }
                    } else {
                        item
                    }
                }.filter { it.foregroundTimeMs > 0L || it.backgroundTimeMs > 0L || it.energyWh > 0.001f }

                // 严格对标 BatteryRecorder 算法：基于应用前台时间切片与硬件时序瞬时采样点（dischargeRealtimeSamples）
                // 采用梯形数值微积分（E = ∫ P(t) dt）精准归集各前台应用的真实物理平均放电功耗与能量，彻底废除 dumpsys 的拼凑模型
                val validatedAppList = calculateAppPowerAndTempWithTimeSlices(
                    appItems = rawAppList,
                    appIntervals = appIntervals,
                    historyTempPoints = stats.historyTempPoints,
                    realtimeSamples = recentSamples,
                    screenOnWatts = screenOnWatts,
                    defaultTempCelsius = batterySnapshot.temperature
                )

                // 4. 后台所有应用能耗、后台真实活跃时长、平均功耗与剩余续航统计
                val rawAllBgEnergyWh = validatedAppList.sumOf { it.backgroundEnergyWh.toDouble() }.toFloat()
                // 物理能量守恒天花板：后台所有应用消耗的能量之和，绝不能超过整机息屏待机总放电量
                val maxAllowedBgEnergyWh = if (offEnergyWh > 0f) offEnergyWh else realTotalEnergyWh
                val allBgEnergyWh = rawAllBgEnergyWh.coerceAtMost(maxAllowedBgEnergyWh)

                // 整机后台放电时长严格从拔电时刻计算：
                // 物理上整机后台待机时长严格对应本次放电周期内的息屏时间 screenOffMs，
                // 且总时长守恒: 亮屏时长 screenOnMs + 息屏/后台时长 screenOffMs == durationMs。
                // 严禁使用未受周期截断的单应用后台时长反向污染整机后台时长！
                val rawBgMs = screenOffMs.coerceIn(0L, durationMs)
                val effectiveBgMs = if (rawBgMs >= 1000L) {
                    rawBgMs
                } else if (durationMs > screenOnMs) {
                    (durationMs - screenOnMs).coerceIn(0L, durationMs)
                } else if (allBgEnergyWh > 0.001f) {
                    // 全亮屏场景：屏幕虽持续点亮，但后台常驻应用与系统服务伴随整机全周期持续运行并消耗电量
                    durationMs
                } else {
                    0L
                }
                val effectiveBgHours = effectiveBgMs / 3600000f
                val calcBgWatts = if (effectiveBgHours > 0f && allBgEnergyWh > 0f) (allBgEnergyWh / effectiveBgHours) else 0f
                val bgWatts = if (screenOffWatts > 0.05f && screenOffMs >= 1000L) {
                    minOf(calcBgWatts, screenOffWatts)
                } else if (calcBgWatts >= 0.005f) {
                    if (screenOffWatts > 0f) minOf(calcBgWatts, screenOffWatts) else calcBgWatts
                } else {
                    0f
                }
                val remBackgroundStr = if (bgWatts >= 0.05f && energy > 0f) formatHoursToText(energy / bgWatts) else "--"
                val bgDurationStr = formatDuration(effectiveBgMs)

                val overview = PowerOverviewStats(
                    avgPowerWatts = avgWatts,
                    screenOnPowerWatts = screenOnWatts,
                    screenOffPowerWatts = screenOffWatts,
                    backgroundPowerWatts = bgWatts,
                    screenOnDurationText = screenOnStr,
                    screenOffDurationText = screenOffStr,
                    totalDurationText = totalDurationStr,
                    backgroundDurationText = bgDurationStr,
                    remainingScreenOnText = remScreenOnStr,
                    remainingCompositeText = remCompositeStr,
                    remainingScreenOffText = remScreenOffStr,
                    remainingBackgroundText = remBackgroundStr,
                    screenOnEnergyWh = onEnergyWh,
                    totalEnergyWh = realTotalEnergyWh,
                    screenOffEnergyWh = offEnergyWh,
                    backgroundEnergyWh = allBgEnergyWh,
                    usedDurationText = durationStr,
                    remainingLifeText = remCompositeStr
                )

                val points = getDischargeTrendPoints(
                    startLevel = startLevel,
                    currentLevel = batterySnapshot.levelPercent,
                    appItems = validatedAppList,
                    durationMs = durationMs,
                    screenOnDurationMs = screenOnMs,
                    historyLevelPoints = stats.historyLevelPoints,
                    screenOnPowerWatts = screenOnWatts,
                    screenOffPowerWatts = screenOffWatts,
                    historyTempPoints = stats.historyTempPoints,
                    currentVoltageVolts = batterySnapshot.voltageVolts,
                    defaultTempCelsius = batterySnapshot.temperature
                )

                return FullPowerDataPackage(
                    batterySnapshot = batterySnapshot,
                    overviewStats = overview,
                    appList = validatedAppList,
                    trendPoints = points,
                    isShizukuRealData = true,
                    startLevelPercent = startLevel
                )
            }
        }

        // 2. 普通标准模式（基于 UsageStats）或 Shizuku 备用降级
        val appUsageList = getAppUsageStatsList(currentTempCelsius = batterySnapshot.temperature)
        val overview = calculateOverviewStats(batterySnapshot.levelPercent, appUsageList)
        val elapsedMs = if (unplugTime > 0L && (now - unplugTime) < 24 * 3600000L) {
            (now - unplugTime).coerceAtLeast(1000L)
        } else {
            3600000L * 24L
        }
        val normalScreenOnMs = appUsageList.sumOf { it.foregroundTimeMs }.coerceAtMost(elapsedMs)
        val maxAllowedNormalMs = elapsedMs
        val rawNormalList = appUsageList.map { item ->
            val safeFg = if (item.foregroundTimeMs > maxAllowedNormalMs) {
                if (unplugTime > 0L && (now - unplugTime) < 300000L && item.foregroundTimeMs > 300000L) {
                    0L
                } else {
                    maxAllowedNormalMs
                }
            } else {
                item.foregroundTimeMs
            }
            // 单应用放电物理守恒：前台时长与后台时长之和绝不可超过当前周期放电总时长
            val maxBgForApp = (maxAllowedNormalMs - safeFg).coerceAtLeast(0L)
            val safeBg = item.backgroundTimeMs.coerceIn(0L, maxBgForApp)
            item.copy(foregroundTimeMs = safeFg, backgroundTimeMs = safeBg)
        }.filter { it.foregroundTimeMs > 0L || it.backgroundTimeMs > 0L || it.energyWh > 0.001f }

        // 普通模式物理完善：
        // 各前台应用运行时屏幕始终点亮，整机放电速率即为当前亮屏平均功耗。
        val normalScreenWatts = if (overview.screenOnPowerWatts > 0f) {
            overview.screenOnPowerWatts
        } else if (overview.avgPowerWatts > 0f) {
            overview.avgPowerWatts
        } else {
            0f
        }

        // 严格遵循 BatteryRecorder 算法标准：
        // 分 App 功耗与能量严格基于前台独占运行的硬件物理放电采样切片；
        // 后台由于多应用交替唤醒且与射频基带深度交织，单 App 后台只统计运行工时（活跃工时与常驻时长），
        val startTs = now - elapsedMs
        val (appIntervals, _) = queryUsageIntervals(startTs, now)
        val localTempPoints = getDischargeTempPoints()
        val recentSamples = getDischargeRealtimeSamples().filter { it.timestamp in (startTs - 60_000L)..now }

        // 采用前台活跃时间切片与本地硬件瞬时采样点（梯形数值微积分），精准计算各 App 运行时真实放电功耗、能耗与电池温度
        val finalAppList = calculateAppPowerAndTempWithTimeSlices(
            appItems = rawNormalList,
            appIntervals = appIntervals,
            historyTempPoints = localTempPoints,
            realtimeSamples = recentSamples,
            screenOnWatts = normalScreenWatts,
            defaultTempCelsius = batterySnapshot.temperature
        )

        // 重新基于已核验的前后台能量生成精准的普通模式概览卡片指标（确保包含后台平均功耗与能量）
        val finalOverview = calculateOverviewStats(batterySnapshot.levelPercent, finalAppList)

        val startLevel = if (unplugTime > 0L && unplugLevel >= batterySnapshot.levelPercent) unplugLevel else batterySnapshot.levelPercent
        val points = getDischargeTrendPoints(
            startLevel = startLevel,
            currentLevel = batterySnapshot.levelPercent,
            appItems = finalAppList,
            durationMs = elapsedMs,
            screenOnDurationMs = normalScreenOnMs,
            screenOnPowerWatts = finalOverview.screenOnPowerWatts,
            screenOffPowerWatts = finalOverview.screenOffPowerWatts,
            historyTempPoints = localTempPoints,
            currentVoltageVolts = batterySnapshot.voltageVolts,
            defaultTempCelsius = batterySnapshot.temperature
        )

        return FullPowerDataPackage(
            batterySnapshot = batterySnapshot,
            overviewStats = finalOverview,
            appList = finalAppList,
            trendPoints = points,
            isShizukuRealData = false,
            startLevelPercent = startLevel
        )
    }

    /**
     * 基于内存中后台常驻服务累积的最新物理放电采样点，轻量级刷新各前台应用的时间切片微积分、持续时长与瞬时指标。
     *
     * 该方法完全不调用底层的 dumpsys batterystats 进程，无任何特权 fork 开销，
     * 仅基于内存中的时序物理采样点（[dischargeRealtimeSamples]）重新计算各应用的真实微积分平均功耗与能量，
     * 为前台 UI 提供极致低功耗的秒级动态刷新支持。
     *
     * @param basePackage 界面当前呈现的基础完整耗电数据包
     * @return 包含最新切片微积分应用列表与瞬时指标的更新数据包，若无新采样数据则返回 null
     */
    fun refreshRealtimeDischargePackage(basePackage: FullPowerDataPackage): FullPowerDataPackage? {
        val now = System.currentTimeMillis()
        val allSamples = getDischargeRealtimeSamples()
        if (allSamples.isEmpty()) return null

        val unplugTime = getLastUnplugTime()
        val durationMs = if (unplugTime in 1..now && (now - unplugTime) in 1000L..(48 * 3600_000L)) {
            (now - unplugTime).coerceAtLeast(1000L)
        } else {
            60_000L
        }
        val startTs = now - durationMs
        val recentSamples = allSamples.filter { it.timestamp in (startTs - 60_000L)..now }
        if (recentSamples.isEmpty()) return null

        val currentBattery = getCurrentBatteryStatus()
        val (_, intTotalPowerWatts) = calculatePhysicalIntegratedEnergyAndPower(recentSamples, filterScreenOn = null)
        val (_, intOnPowerWatts) = calculatePhysicalIntegratedEnergyAndPower(recentSamples, filterScreenOn = true)
        val (_, intOffPowerWatts) = calculatePhysicalIntegratedEnergyAndPower(recentSamples, filterScreenOn = false)

        val updatedAppList = calculateAppPowerAndTempWithTimeSlices(
            appItems = basePackage.appList,
            appIntervals = emptyList(),
            historyTempPoints = emptyList(),
            realtimeSamples = recentSamples,
            screenOnWatts = if (intOnPowerWatts > 0.05f) intOnPowerWatts else basePackage.overviewStats.screenOnPowerWatts,
            defaultTempCelsius = currentBattery.temperature
        )

        val newAvgWatts = if (intTotalPowerWatts > 0.05f) intTotalPowerWatts else basePackage.overviewStats.avgPowerWatts
        val newScreenOnWatts = if (intOnPowerWatts > 0.05f) intOnPowerWatts else basePackage.overviewStats.screenOnPowerWatts
        val newScreenOffWatts = if (intOffPowerWatts > 0.05f) intOffPowerWatts else basePackage.overviewStats.screenOffPowerWatts

        val updatedOverview = basePackage.overviewStats.copy(
            avgPowerWatts = newAvgWatts,
            screenOnPowerWatts = newScreenOnWatts,
            screenOffPowerWatts = newScreenOffWatts
        )

        val updatedSnapshot = basePackage.batterySnapshot.copy(
            voltageVolts = currentBattery.voltageVolts,
            temperature = currentBattery.temperature,
            levelPercent = currentBattery.levelPercent
        )

        return basePackage.copy(
            batterySnapshot = updatedSnapshot,
            overviewStats = updatedOverview,
            appList = updatedAppList
        )
    }

    /**
     * 判断指定包名是否为用户应用（三方应用、可更新系统应用、有桌面启动入口或属于桌面启动器）。
     *
     * @param packageName 目标包名
     * @return 若为用户交互应用返回 true，否则返回 false
     */
    fun isUserInstalledApp(packageName: String): Boolean {
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystem = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            val hasLauncher = pm.getLaunchIntentForPackage(packageName) != null
            val isHome = isHomeLauncher(packageName)
            !isSystem || isUpdatedSystem || hasLauncher || isHome
        } catch (_: Exception) {
            false
        }
    }

    @Volatile
    private var cachedDefaultHomePackage: String? = null

    /**
     * 获取当前系统默认桌面（Home Launcher）包名。
     * 优先直接读取静态缓存；若未命中则通过 PackageManager 解析 Intent.CATEGORY_HOME 意图，
     * 确保秒级精准获取当前生效的系统桌面包名（如荣耀桌面 com.hihonor.android.launcher）。
     *
     * @return 默认桌面启动器包名，若无法解析则返回 null
     */
    fun getDefaultHomeLauncherPackage(): String? {
        val cached = cachedDefaultHomePackage
        if (!cached.isNullOrEmpty()) return cached

        return try {
            val pm = context.packageManager
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolveInfo = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            val pkg = resolveInfo?.activityInfo?.packageName
            if (!pkg.isNullOrEmpty() && pkg != "android") {
                cachedDefaultHomePackage = pkg
                pkg
            } else {
                val resolveInfos = pm.queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
                val found = resolveInfos.firstOrNull {
                    val p = it.activityInfo?.packageName ?: ""
                    p.isNotEmpty() && p != "android"
                }?.activityInfo?.packageName
                if (!found.isNullOrEmpty()) {
                    cachedDefaultHomePackage = found
                }
                found
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 检查指定包名是否为系统内置或当前的桌面启动器（Launcher / Home）。
     *
     * @param packageName 目标应用包名
     * @return 若为桌面启动器返回 true，否则返回 false
     */
    fun isHomeLauncher(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val defaultHome = getDefaultHomeLauncherPackage()
        if (defaultHome == packageName) return true

        return try {
            val pm = context.packageManager
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolveInfos = pm.queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            resolveInfos.any { it.activityInfo?.packageName == packageName } ||
                    packageName.contains("launcher", ignoreCase = true) ||
                    packageName.contains("home", ignoreCase = true)
        } catch (_: Exception) {
            packageName.contains("launcher", ignoreCase = true) ||
                    packageName.contains("home", ignoreCase = true)
        }
    }

    /**
     * 判断指定包名是否为游戏应用。
     * 优先从内存缓存中获取；若未命中则通过 PackageManager 检查系统内置类别 CATEGORY_GAME 或应用清单中的 FLAG_IS_GAME 标识；
     * 并容错检查常见游戏特质包名（如包含 .game、.clash 等）。
     *
     * @param packageName 目标应用包名
     * @return 若为游戏应用返回 true，否则返回 false
     */
    @Suppress("DEPRECATION")
    fun isGameApp(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val cached = gameAppCache[packageName]
        if (cached != null) return cached

        val result = try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val isCatGame = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                appInfo.category == ApplicationInfo.CATEGORY_GAME
            } else {
                false
            }
            val isFlagGame = (appInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0
            val isNameGame = packageName.contains(".game", ignoreCase = true) ||
                    packageName.contains(".clash", ignoreCase = true)
            isCatGame || isFlagGame || isNameGame
        } catch (_: Exception) {
            packageName.contains(".game", ignoreCase = true) ||
                    packageName.contains(".clash", ignoreCase = true)
        }
        gameAppCache[packageName] = result
        return result
    }

    /**
     * 基于 UsageEvents 精准提取指定时间区间 [startTime, endTime] 内各应用的前台活跃毫秒数。
     * 采用严格的单前台应用生命周期状态机，仅认准 ACTIVITY_RESUMED 至 ACTIVITY_PAUSED，
     * 并将前台应用 PAUSED 后到下一个应用 RESUMED 之间的亮屏交互时长准确归集至系统桌面 Launcher，
     * 彻底杜绝桌面停留时长丢失导致的功耗计算失真。
     *
     * @param usm UsageStatsManager 实例
     * @param startTime 统计起始时间戳（毫秒）
     * @param endTime 统计结束时间戳（毫秒）
     * @return 包名对应的前台活跃时长（毫秒）映射表
     */
    fun queryPreciseForegroundTimes(
        usm: UsageStatsManager,
        startTime: Long,
        endTime: Long
    ): Map<String, Long> {
        val resultMap = mutableMapOf<String, Long>()
        if (startTime >= endTime) return resultMap

        val defaultHome = getDefaultHomeLauncherPackage()

        try {
            // 向前回溯探测在 startTime 瞬间正处于前台活跃状态的应用（最多回溯 15 分钟）
            val lookbackStart = (startTime - 15 * 60 * 1000L).coerceAtLeast(0L)
            val events = usm.queryEvents(lookbackStart, endTime)
            val event = UsageEvents.Event()
            var currentForegroundPkg: String? = null
            var currentForegroundStartTs: Long = 0L
            var isScreenOn = true

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val pkg = event.packageName
                val ts = event.timeStamp

                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        if (!pkg.isNullOrEmpty()) {
                            if (currentForegroundPkg != null) {
                                val activeStart = maxOf(currentForegroundStartTs, startTime)
                                val activeEnd = minOf(ts, endTime)
                                if (activeEnd > activeStart) {
                                    resultMap[currentForegroundPkg] = (resultMap[currentForegroundPkg] ?: 0L) + (activeEnd - activeStart)
                                }
                            }
                            currentForegroundPkg = pkg
                            currentForegroundStartTs = ts
                        }
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED -> {
                        if (currentForegroundPkg == pkg) {
                            val activeStart = maxOf(currentForegroundStartTs, startTime)
                            val activeEnd = minOf(ts, endTime)
                            if (activeEnd > activeStart) {
                                resultMap[pkg] = (resultMap[pkg] ?: 0L) + (activeEnd - activeStart)
                            }
                            // 切出当前应用后，若屏幕处于亮屏状态，自动归属为系统桌面
                            if (isScreenOn && !defaultHome.isNullOrEmpty()) {
                                currentForegroundPkg = defaultHome
                                currentForegroundStartTs = ts
                            } else {
                                currentForegroundPkg = null
                                currentForegroundStartTs = 0L
                            }
                        }
                    }
                    UsageEvents.Event.SCREEN_INTERACTIVE -> {
                        isScreenOn = true
                        if (currentForegroundPkg == null && !defaultHome.isNullOrEmpty()) {
                            currentForegroundPkg = defaultHome
                            currentForegroundStartTs = ts
                        }
                    }
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                        isScreenOn = false
                        if (currentForegroundPkg != null) {
                            val activeStart = maxOf(currentForegroundStartTs, startTime)
                            val activeEnd = minOf(ts, endTime)
                            if (activeEnd > activeStart) {
                                resultMap[currentForegroundPkg] = (resultMap[currentForegroundPkg] ?: 0L) + (activeEnd - activeStart)
                            }
                            currentForegroundPkg = null
                            currentForegroundStartTs = 0L
                        }
                    }
                }
            }

            // 处理在 endTime 时刻仍然驻留前台的应用（包含桌面）
            if (currentForegroundPkg != null) {
                val activeStart = maxOf(currentForegroundStartTs, startTime)
                val activeEnd = endTime
                if (activeEnd > activeStart) {
                    resultMap[currentForegroundPkg] = (resultMap[currentForegroundPkg] ?: 0L) + (activeEnd - activeStart)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return resultMap
    }

    /**
     * 基于 UsageEvents 事件流精准查询指定时间区间内各应用前台服务（后台运行保活）的真实活跃耗时（毫秒）。
     * 严格从 startTime（拔电时刻）开始截取，彻底杜绝把拔电前的历史前台服务累积时间计入当前放电周期。
     *
     * @param usm 系统 UsageStatsManager 实例
     * @param startTime 统计起始时间戳（毫秒，通常为断开外部电源的瞬间）
     * @param endTime 统计结束时间戳（毫秒，通常为当前系统时间）
     * @return 包名对应的前台服务运行活跃时长（毫秒）映射表
     */
    fun queryPreciseForegroundServiceTimes(
        usm: UsageStatsManager,
        startTime: Long,
        endTime: Long
    ): Map<String, Long> {
        val resultMap = mutableMapOf<String, Long>()
        if (startTime >= endTime || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return resultMap

        try {
            val lookbackStart = (startTime - 15 * 60 * 1000L).coerceAtLeast(0L)
            val events = usm.queryEvents(lookbackStart, endTime)
            val event = UsageEvents.Event()
            val runningServiceStartMap = mutableMapOf<String, Long>()

            val eventFgStart = 19 // UsageEvents.Event.FOREGROUND_SERVICE_START
            val eventFgStop = 20  // UsageEvents.Event.FOREGROUND_SERVICE_STOP

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val pkg = event.packageName ?: continue
                val ts = event.timeStamp

                when (event.eventType) {
                    eventFgStart -> {
                        runningServiceStartMap[pkg] = ts
                    }
                    eventFgStop -> {
                        val startTs = runningServiceStartMap.remove(pkg)
                        if (startTs != null) {
                            val activeStart = maxOf(startTs, startTime)
                            val activeEnd = minOf(ts, endTime)
                            if (activeEnd > activeStart) {
                                resultMap[pkg] = (resultMap[pkg] ?: 0L) + (activeEnd - activeStart)
                            }
                        }
                    }
                }
            }

            // 处理在 endTime 时刻仍然处于运行中的前台服务
            for ((pkg, startTs) in runningServiceStartMap) {
                val activeStart = maxOf(startTs, startTime)
                val activeEnd = endTime
                if (activeEnd > activeStart) {
                    resultMap[pkg] = (resultMap[pkg] ?: 0L) + (activeEnd - activeStart)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return resultMap
    }

    /**
     * 基于 UsageStatsManager 获取普通模式下的应用使用场景与耗电排行列表。
     * 若存在有效的拔电时间戳，则仅截取断电以来的应用使用数据；否则统计过去 24 小时。
     *
     * @param customStartTime 可选的自定义统计起始时间戳（毫秒）
     * @param currentTempCelsius 可选的当前电池温度（摄氏度），用于校准应用平均与最高运行温度
     * @return 应用耗电列表 [List<AppPowerUsageItem>]
     */
    fun getAppUsageStatsList(
        customStartTime: Long? = null,
        currentTempCelsius: Float? = null
    ): List<AppPowerUsageItem> {
        val pm = context.packageManager
        val resultList = mutableListOf<AppPowerUsageItem>()

        if (hasUsageStatsPermission()) {
            val unplugTime = getLastUnplugTime()
            val now = System.currentTimeMillis()
            val startTime = customStartTime ?: if (unplugTime > 0L) unplugTime else (now - 86400000L)
            val elapsedMs = (now - startTime).coerceAtLeast(1000L)

            // 优先通过自拔电以来的精确增量数据
            val deltas = getUnplugUsageDeltas()
            val bgServiceTimes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                    val eventTimes = if (usm != null) queryPreciseForegroundServiceTimes(usm, startTime, now) else emptyMap()
                    if (eventTimes.isNotEmpty()) {
                        eventTimes
                    } else if (usm != null) {
                        val bgSnapshotStr = prefs.getString(PREF_KEY_UNPLUG_BG_SERVICE_SNAPSHOT, null)
                        val bgSnapshotJson = if (!bgSnapshotStr.isNullOrEmpty()) org.json.JSONObject(bgSnapshotStr) else null
                        val cal = Calendar.getInstance()
                        cal.add(Calendar.DAY_OF_YEAR, -1)
                        val currentStats = usm.queryAndAggregateUsageStats(cal.timeInMillis, now)
                        val deltaMap = mutableMapOf<String, Long>()
                        for ((pkg, usage) in currentStats) {
                            val curService = usage.totalTimeForegroundServiceUsed
                            val baseline = bgSnapshotJson?.optLong(pkg, 0L) ?: 0L
                            val diff = if (bgSnapshotJson != null) (curService - baseline).coerceAtLeast(0L) else 0L
                            if (diff > 0L) {
                                deltaMap[pkg] = diff
                            }
                        }
                        deltaMap
                    } else {
                        emptyMap()
                    }
                } catch (_: Exception) {
                    emptyMap()
                }
            } else {
                emptyMap()
            }

            if (deltas.isNotEmpty()) {
                val baseTemp = (currentTempCelsius ?: getCurrentBatteryStatus().temperature)
                val formattedBaseTemp = (Math.round(baseTemp * 10f) / 10f)
                for ((pkgName, pair) in deltas) {
                    val timeMs = pair.first.coerceAtMost(elapsedMs)
                    val lastUsed = pair.second
                    if (timeMs > 0L && isUserInstalledApp(pkgName)) {
                        try {
                            val appInfo = pm.getApplicationInfo(pkgName, 0)
                            val appName = pm.getApplicationLabel(appInfo).toString()
                            val icon = pm.getApplicationIcon(appInfo)

                            val avgTemp = formattedBaseTemp
                            val maxTemp = formattedBaseTemp

                            val serviceBgMs = bgServiceTimes[pkgName] ?: 0L
                            // 物理时间守恒：应用在本次放电周期内的后台服务存活时间，上限不超过当前周期的非前台时长
                            val maxAllowedBg = (elapsedMs - timeMs).coerceAtLeast(0L)
                            val effectiveFgsMs = serviceBgMs.coerceIn(0L, maxAllowedBg)
                            val activeBgMs = effectiveFgsMs

                            // 不再使用包名哈希伪随机功耗。时长数据为精确采集，
                            // avgPowerWatts 与能量字段将在 loadPowerData 计算整机功耗后按前台时长占比分配。
                            resultList.add(
                                AppPowerUsageItem(
                                    packageName = pkgName,
                                    appName = appName,
                                    icon = icon,
                                    foregroundTimeMs = timeMs,
                                    avgPowerWatts = 0f,
                                    avgTemperature = avgTemp,
                                    maxTemperature = maxTemp,
                                    lastUsedTimeMs = lastUsed,
                                    directEnergyWh = null,
                                    backgroundTimeMs = activeBgMs,
                                    foregroundEnergyWh = 0f,
                                    backgroundEnergyWh = 0f,
                                    fgsDurationMs = effectiveFgsMs
                                )
                            )
                        } catch (_: PackageManager.NameNotFoundException) {
                        }
                    }
                }
            }
        }

        resultList.sortByDescending { it.foregroundTimeMs }
        return resultList
    }

    /**
     * 基于应用前台时间切片与底层时序硬件放电采样点（dischargeRealtimeSamples / historyTempPoints），
     * 采用梯形数值微积分（E = ∫ P(t) dt）精准计算各前台应用运行期间的真实物理放电平均功耗、消耗能量与真实温度。
     * 严格对标 BatteryRecorder 算法标准：将整机电池在各应用前台活跃区间的物理瞬时放电功率进行微积分归集，
     * 彻底废除 dumpsys 的静态 UID 能耗与屏幕底座估算拼凑。纯后台运行应用仅统计工时，功耗与能量归零。
     *
     * @param appItems 原始解析出的应用耗电实体列表
     * @param appIntervals 各应用的前台活跃时间切片区间列表
     * @param historyTempPoints 系统底层记录的时序温度采样点列表（时间戳 -> 摄氏度）
     * @param realtimeSamples 硬件放电时序瞬时采样点列表（时间戳、电压、瞬时放电功率、亮屏状态等）
     * @param screenOnWatts 整机亮屏综合平均功耗（单位：W，用于极短时长未命中采样点时的基准保底）
     * @param defaultTempCelsius 默认/基准电池温度（摄氏度）
     * @return 经过梯形数值微积分与切片温度归集后的应用耗电实体列表 [List<AppPowerUsageItem>]
     */
    private fun calculateAppPowerAndTempWithTimeSlices(
        appItems: List<AppPowerUsageItem>,
        appIntervals: List<AppActivityInterval>,
        historyTempPoints: List<Pair<Long, Float>>,
        realtimeSamples: List<PowerDischargePoint>,
        screenOnWatts: Float,
        defaultTempCelsius: Float
    ): List<AppPowerUsageItem> {
        val intervalMap = appIntervals.groupBy { it.packageName }

        // 微积分聚合器（对标 BatteryRecorder RecordAppStatsComputer）
        class SliceAccumulator {
            var sampledDurationMs: Long = 0L
            var sampledEnergyWs: Double = 0.0 // 瓦秒 W*s
            var tempDurationMs: Long = 0L
            var weightedTempSum: Double = 0.0
            var maxTempCelsius: Float? = null
        }
        val appSliceMap = mutableMapOf<String, SliceAccumulator>()

        val sortedSamples = realtimeSamples.sortedBy { it.timestamp }
        if (sortedSamples.size >= 2) {
            var prev = sortedSamples[0]
            for (i in 1 until sortedSamples.size) {
                val curr = sortedSamples[i]
                val dt = curr.timestamp - prev.timestamp

                // 仅对合理时间间隔（1ms ~ 120s）进行连续切片数值梯形微积分
                if (dt in 1L..120_000L) {
                    val sliceStart = prev.timestamp
                    val sliceEnd = curr.timestamp
                    val midTs = (sliceStart + sliceEnd) / 2
                    val avgPower = (prev.powerWatts + curr.powerWatts) * 0.5
                    val avgT = (prev.temperature + curr.temperature) * 0.5
                    val stepMax = maxOf(prev.temperature, curr.temperature)

                    // 1. 优先在系统底层精确记录的前台活跃区间中匹配重叠应用
                    val overlappingIntervals = appIntervals.filter { interval ->
                        maxOf(sliceStart, interval.startTs) < minOf(sliceEnd, interval.endTs)
                    }

                    if (overlappingIntervals.isNotEmpty()) {
                        var allocatedOverlapMs = 0L
                        for (interval in overlappingIntervals) {
                            val overlapStart = maxOf(sliceStart, interval.startTs)
                            val overlapEnd = minOf(sliceEnd, interval.endTs)
                            val overlapMs = (overlapEnd - overlapStart).coerceAtLeast(0L)
                            if (overlapMs > 0L) {
                                allocatedOverlapMs += overlapMs
                                val acc = appSliceMap.getOrPut(interval.packageName) { SliceAccumulator() }
                                val dEnergyWs = avgPower * (overlapMs / 1000.0)
                                acc.sampledDurationMs += overlapMs
                                acc.sampledEnergyWs += dEnergyWs
                                acc.tempDurationMs += overlapMs
                                acc.weightedTempSum += avgT * overlapMs
                                val currentMax = acc.maxTempCelsius
                                acc.maxTempCelsius = if (currentMax == null) stepMax else maxOf(currentMax, stepMax)
                            }
                        }
                        // 若重叠分配后仍有剩余未覆盖的时间切片，且采样点自带包名，则归入该采样点包名
                        val remainingMs = dt - allocatedOverlapMs
                        if (remainingMs > 0L) {
                            val fallbackPkg = curr.packageName?.takeIf { it.isNotEmpty() } ?: prev.packageName?.takeIf { it.isNotEmpty() }
                            if (fallbackPkg != null && !overlappingIntervals.any { it.packageName == fallbackPkg }) {
                                val acc = appSliceMap.getOrPut(fallbackPkg) { SliceAccumulator() }
                                val dEnergyWs = avgPower * (remainingMs / 1000.0)
                                acc.sampledDurationMs += remainingMs
                                acc.sampledEnergyWs += dEnergyWs
                                acc.tempDurationMs += remainingMs
                                acc.weightedTempSum += avgT * remainingMs
                                val currentMax = acc.maxTempCelsius
                                acc.maxTempCelsius = if (currentMax == null) stepMax else maxOf(currentMax, stepMax)
                            }
                        }
                    } else {
                        // 2. 无重叠区间时（如 UsageStats 未采集到或未授权）：使用采样点前台包名
                        val matchedPkg = curr.packageName?.takeIf { it.isNotEmpty() }
                            ?: prev.packageName?.takeIf { it.isNotEmpty() }
                            ?: appIntervals.firstOrNull { midTs in it.startTs..it.endTs }?.packageName

                        if (matchedPkg != null) {
                            val acc = appSliceMap.getOrPut(matchedPkg) { SliceAccumulator() }
                            val dEnergyWs = avgPower * (dt / 1000.0)
                            acc.sampledDurationMs += dt
                            acc.sampledEnergyWs += dEnergyWs
                            acc.tempDurationMs += dt
                            acc.weightedTempSum += avgT * dt
                            val currentMax = acc.maxTempCelsius
                            acc.maxTempCelsius = if (currentMax == null) stepMax else maxOf(currentMax, stepMax)
                        }
                    }
                }
                prev = curr
            }
        }

        // 针对单点采样落入区间的补充统计（如果梯形时长为0，但有点落在区间内或自带包名）
        if (sortedSamples.isNotEmpty()) {
            for (sample in sortedSamples) {
                if (sample.powerWatts > 0f) {
                    for (interval in appIntervals) {
                        if (sample.timestamp in interval.startTs..interval.endTs) {
                            val acc = appSliceMap.getOrPut(interval.packageName) { SliceAccumulator() }
                            if (acc.sampledDurationMs <= 0L) {
                                val intervalDurationMs = (interval.endTs - interval.startTs).coerceAtLeast(1L)
                                acc.sampledDurationMs = intervalDurationMs
                                acc.sampledEnergyWs = sample.powerWatts * (intervalDurationMs / 1000.0)
                            }
                            if (acc.maxTempCelsius == null) {
                                acc.maxTempCelsius = sample.temperature
                            }
                        }
                    }
                    if (!sample.packageName.isNullOrEmpty()) {
                        val acc = appSliceMap.getOrPut(sample.packageName) { SliceAccumulator() }
                        if (acc.sampledDurationMs <= 0L) {
                            acc.sampledDurationMs = 1000L
                            acc.sampledEnergyWs = sample.powerWatts * 1.0
                        }
                        if (acc.maxTempCelsius == null) {
                            acc.maxTempCelsius = sample.temperature
                        }
                    }
                }
            }
        }

        return appItems.map { item ->
            val fgHours = if (item.foregroundTimeMs > 0L) item.foregroundTimeMs / 3600000f else 0f
            val acc = appSliceMap[item.packageName]

            val finalFgWatts: Float
            val finalFgEnergy: Float

            if (item.foregroundTimeMs > 0L) {
                if (acc != null && acc.sampledDurationMs > 0L && acc.sampledEnergyWs > 0.0) {
                    // 1. 梯形微积分算出的真实硬件平均放电功耗：总焦耳 (W*s) / 总采样秒数 (s)
                    val sampledWatts = (acc.sampledEnergyWs / (acc.sampledDurationMs / 1000.0)).toFloat()
                    finalFgWatts = (Math.round(sampledWatts * 100f) / 100f).coerceAtLeast(0f)
                    finalFgEnergy = (finalFgWatts * fgHours).coerceAtLeast(0f)
                } else {
                    // 2. 短时运行应用（如切片采样点不足）：严格在该应用活跃的时间区间内查找真实亮屏瞬时采样均值，杜绝 30 秒大窗口污染
                    val appIntervalList = intervalMap[item.packageName] ?: emptyList()
                    val windowSamples = if (appIntervalList.isNotEmpty()) {
                        // 在应用各段活跃区间（前后仅允许 1500ms 采样时钟相位微调容差）内检索真实的放电采样点
                        sortedSamples.filter { sample ->
                            sample.isScreenOn && sample.powerWatts > 0f &&
                                appIntervalList.any { interval ->
                                    sample.timestamp in (interval.startTs - 1500L)..(interval.endTs + 1500L)
                                }
                        }
                    } else {
                        // 无切片记录时，严格以最近使用时间及其前台时长为窗口
                        val refTs = item.lastUsedTimeMs
                        val windowStart = refTs - item.foregroundTimeMs - 1500L
                        val windowEnd = refTs + 1500L
                        sortedSamples.filter { it.isScreenOn && it.powerWatts > 0f && it.timestamp in windowStart..windowEnd }
                    }
                    val windowAvgWatts = if (windowSamples.isNotEmpty()) {
                        windowSamples.map { it.powerWatts }.average().toFloat()
                    } else {
                        null
                    }

                    val selfCalcWatts = if (item.foregroundPowerWatts > 0.05f) {
                        item.foregroundPowerWatts
                    } else if (item.foregroundEnergyWh > 0.0001f && fgHours > 0f) {
                        (item.foregroundEnergyWh / fgHours).toFloat()
                    } else if (item.avgPowerWatts > 0.05f && !isHomeLauncher(item.packageName)) {
                        item.avgPowerWatts
                    } else {
                        null
                    }

                    val avgSampleScreenWatts = sortedSamples.filter { it.isScreenOn && it.powerWatts > 0f }
                        .map { it.powerWatts }.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f
                    val baselineWatts = if (screenOnWatts > 0.05f) screenOnWatts else if (avgSampleScreenWatts > 0.05f) avgSampleScreenWatts else 0f
                    val fallbackWatts = windowAvgWatts ?: selfCalcWatts ?: baselineWatts

                    finalFgWatts = (Math.round(fallbackWatts * 100f) / 100f).coerceAtLeast(0f)
                    finalFgEnergy = (finalFgWatts * fgHours).coerceAtLeast(0f)
                }
            } else {
                // 纯后台应用：功耗与能量一律置 0
                finalFgWatts = 0f
                finalFgEnergy = 0f
            }

            // 计算真实电池温度（优先采用该应用前台切片采样加权平均温度与最高温度）
            val avgTemp: Float
            val maxTemp: Float
            if (acc != null && acc.tempDurationMs > 0L) {
                val rawAvg = (acc.weightedTempSum / acc.tempDurationMs.toDouble()).toFloat()
                avgTemp = (Math.round(rawAvg * 10f) / 10f)
                maxTemp = (Math.round((acc.maxTempCelsius ?: avgTemp) * 10f) / 10f).coerceAtLeast(avgTemp)
            } else {
                val intervals = intervalMap[item.packageName] ?: emptyList()
                val matchedTemps = if (intervals.isNotEmpty() && historyTempPoints.isNotEmpty()) {
                    historyTempPoints.filter { (ts, _) ->
                        intervals.any { interval -> ts in (interval.startTs - 1500L)..(interval.endTs + 1500L) }
                    }.map { it.second }
                } else {
                    emptyList()
                }
                if (matchedTemps.isNotEmpty()) {
                    val rawAvg = matchedTemps.average().toFloat()
                    val rawMax = matchedTemps.maxOrNull() ?: rawAvg
                    avgTemp = (Math.round(rawAvg * 10f) / 10f)
                    maxTemp = (Math.round(rawMax * 10f) / 10f).coerceAtLeast(avgTemp)
                } else if (historyTempPoints.isNotEmpty()) {
                    val refTs = intervals.lastOrNull()?.endTs ?: item.lastUsedTimeMs
                    val closestTemp = historyTempPoints.minByOrNull { Math.abs(it.first - refTs) }?.second ?: defaultTempCelsius
                    val formattedTemp = (Math.round(closestTemp * 10f) / 10f)
                    avgTemp = formattedTemp
                    maxTemp = formattedTemp
                } else {
                    val formattedTemp = (Math.round(defaultTempCelsius * 10f) / 10f)
                    avgTemp = formattedTemp
                    maxTemp = formattedTemp
                }
            }

            item.copy(
                avgPowerWatts = finalFgWatts,
                foregroundPowerWatts = finalFgWatts,
                backgroundPowerWatts = 0f,
                avgTemperature = avgTemp,
                maxTemperature = maxTemp,
                foregroundEnergyWh = finalFgEnergy,
                backgroundEnergyWh = 0f,
                directEnergyWh = finalFgEnergy
            )
        }
    }

    /**
     * 从系统 UsageStatsManager 检索并提取指定时间范围内的全部应用前台活动区间及屏幕点亮区间。
     *
     * @param startTime 检索起始时间戳（毫秒）
     * @param endTime 检索结束时间戳（毫秒）
     * @return 包含应用活动区间列表与屏幕点亮区间列表的二元组 [Pair]
     */
    private fun queryUsageIntervals(
        startTime: Long,
        endTime: Long
    ): Pair<List<AppActivityInterval>, List<ScreenInteractiveInterval>> {
        val appIntervals = mutableListOf<AppActivityInterval>()
        val screenIntervals = mutableListOf<ScreenInteractiveInterval>()
        if (startTime >= endTime) return Pair(appIntervals, screenIntervals)

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return Pair(appIntervals, screenIntervals)

        val defaultHome = getDefaultHomeLauncherPackage()

        try {
            val lookbackStart = (startTime - 60 * 60 * 1000L).coerceAtLeast(0L)
            val events = usm.queryEvents(lookbackStart, endTime)
            val event = UsageEvents.Event()
            var currentForegroundPkg: String? = null
            var currentForegroundStartTs: Long = 0L
            var screenOnStart: Long? = null

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val ts = event.timeStamp
                val pkg = event.packageName

                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        if (screenOnStart == null) {
                            screenOnStart = ts
                        }
                        if (!pkg.isNullOrEmpty()) {
                            if (currentForegroundPkg != null) {
                                val activeStart = maxOf(currentForegroundStartTs, startTime)
                                val activeEnd = minOf(ts, endTime)
                                if (activeEnd > activeStart) {
                                    appIntervals.add(AppActivityInterval(currentForegroundPkg, activeStart, activeEnd))
                                }
                            }
                            currentForegroundPkg = pkg
                            currentForegroundStartTs = ts
                        }
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED -> {
                        if (currentForegroundPkg == pkg) {
                            val activeStart = maxOf(currentForegroundStartTs, startTime)
                            val activeEnd = minOf(ts, endTime)
                            if (activeEnd > activeStart) {
                                appIntervals.add(AppActivityInterval(pkg, activeStart, activeEnd))
                            }
                            // 暂存应用切换断点，保留暂停时间戳，不盲目判定为系统桌面，杜绝应用内部切换 Activity 导致时序断流
                            currentForegroundPkg = null
                            currentForegroundStartTs = ts
                        }
                    }
                    UsageEvents.Event.SCREEN_INTERACTIVE -> {
                        screenOnStart = ts
                        if (currentForegroundPkg == null && !defaultHome.isNullOrEmpty()) {
                            currentForegroundPkg = defaultHome
                            currentForegroundStartTs = ts
                        }
                    }
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                        val onStart = screenOnStart ?: startTime
                        val activeStart = maxOf(onStart, startTime)
                        val activeEnd = minOf(ts, endTime)
                        if (activeEnd > activeStart) {
                            screenIntervals.add(ScreenInteractiveInterval(activeStart, activeEnd))
                        }
                        if (currentForegroundPkg != null) {
                            val fgStart = maxOf(currentForegroundStartTs, startTime)
                            val fgEnd = minOf(ts, endTime)
                            if (fgEnd > fgStart) {
                                appIntervals.add(AppActivityInterval(currentForegroundPkg, fgStart, fgEnd))
                            }
                            currentForegroundPkg = null
                            currentForegroundStartTs = 0L
                        }
                        screenOnStart = null
                    }
                }
            }

            // 处理在 endTime 时刻未结束的应用与屏幕常亮事件
            var finalFgPkg = currentForegroundPkg
            var finalFgStartTs = currentForegroundStartTs

            val now = System.currentTimeMillis()
            val isNearCurrentTime = abs(endTime - now) <= 120_000L
            val isInteractive = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive == true

            // 若查询终点接近当前真实时刻且屏幕亮屏，校准当前系统的真实前台置顶应用（消除系统 UsageStats 缓冲区异步写入延迟）
            if (isNearCurrentTime && (screenOnStart != null || isInteractive)) {
                val realPkg = ShizukuForegroundAppDetector.getForegroundPackageName(context)
                    ?: KeepAliveAccessibilityService.currentForegroundPackage
                    ?: context.packageName

                if (!realPkg.isNullOrEmpty() && realPkg != defaultHome) {
                    if (finalFgPkg == realPkg) {
                        // 延续当前前台应用
                    } else {
                        if (finalFgPkg != null && finalFgStartTs > 0L && finalFgStartTs < endTime) {
                            val activeStart = maxOf(finalFgStartTs, startTime)
                            if (endTime > activeStart) {
                                appIntervals.add(AppActivityInterval(finalFgPkg, activeStart, endTime))
                            }
                        }
                        finalFgPkg = realPkg
                        finalFgStartTs = if (finalFgStartTs > 0L) finalFgStartTs else (screenOnStart ?: startTime)
                    }
                }
            }

            if (finalFgPkg != null) {
                val activeStart = maxOf(finalFgStartTs, startTime)
                val activeEnd = endTime
                if (activeEnd > activeStart) {
                    appIntervals.add(AppActivityInterval(finalFgPkg, activeStart, activeEnd))
                }
            }
            if (screenOnStart != null && endTime > screenOnStart) {
                val activeStart = maxOf(screenOnStart, startTime)
                if (endTime > activeStart) {
                    screenIntervals.add(ScreenInteractiveInterval(activeStart, endTime))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return Pair(appIntervals, screenIntervals)
    }

    /**
     * 根据合并后的屏幕交互区间列表，计算指定时间范围 [startTs, endTs] 内的真实亮屏总时长（毫秒）。
     *
     * @param screenIntervals 屏幕点亮时间区间列表
     * @param startTs 统计起始时间戳（毫秒）
     * @param endTs 统计结束时间戳（毫秒）
     * @return 范围内的真实亮屏毫秒数
     */
    private fun calculateScreenOnDurationFromIntervals(
        screenIntervals: List<ScreenInteractiveInterval>,
        startTs: Long,
        endTs: Long
    ): Long {
        if (screenIntervals.isEmpty() || startTs >= endTs) return 0L
        val clamped = screenIntervals.mapNotNull {
            val s = maxOf(it.startTs, startTs)
            val e = minOf(it.endTs, endTs)
            if (s < e) ScreenInteractiveInterval(s, e) else null
        }.sortedBy { it.startTs }

        if (clamped.isEmpty()) return 0L

        var totalOnMs = 0L
        var curStart = clamped[0].startTs
        var curEnd = clamped[0].endTs

        for (i in 1 until clamped.size) {
            val next = clamped[i]
            if (next.startTs <= curEnd) {
                curEnd = maxOf(curEnd, next.endTs)
            } else {
                totalOnMs += (curEnd - curStart)
                curStart = next.startTs
                curEnd = next.endTs
            }
        }
        totalOnMs += (curEnd - curStart)
        return totalOnMs.coerceIn(0L, endTs - startTs)
    }

    /**
     * 计算放电过程走势轨迹采样点列表（严格根据各时间对应的真实电量精确划线，包含活跃应用图标垂直堆叠及亮屏/息屏状态指示）。
     * 彻底废除假数据拟合，优先使用系统内核历史记录中各时刻对应的真实电量；无历史记录时基于各时间切片亮/息屏真实能耗积分推导。
     *
     * @param startLevel 放电起始电量百分比（默认 100）
     * @param currentLevel 当前电量百分比
     * @param appItems 活跃应用列表
     * @param durationMs 放电跨度毫秒数
     * @param screenOnDurationMs 亮屏持续总毫秒数（默认 -1L，若小于0则自动估算）
     * @param historyLevelPoints 系统底层解析得到的历史各时刻真实电量点序列（时间戳 -> 电量）
     * @param screenOnPowerWatts 亮屏平均功耗（W）
     * @param screenOffPowerWatts 息屏平均功耗（W）
     * @param historyTempPoints 系统底层记录的历史温度采样点序列（时间戳 -> 摄氏度），用于插值各时刻温度
     * @param currentVoltageVolts 当前实时电池电压（V），用于替换趋势点中的硬编码电压
     * @param defaultTempCelsius 无历史温度点时的默认温度（摄氏度）
     * @return 放电趋势点序列 [List<PowerDischargePoint>]
     */
    fun getDischargeTrendPoints(
        startLevel: Int = 100,
        currentLevel: Int,
        appItems: List<AppPowerUsageItem>,
        durationMs: Long,
        screenOnDurationMs: Long = -1L,
        historyLevelPoints: List<Pair<Long, Int>> = emptyList(),
        screenOnPowerWatts: Float = 0f,
        screenOffPowerWatts: Float = 0f,
        historyTempPoints: List<Pair<Long, Float>> = emptyList(),
        currentVoltageVolts: Float = 0f,
        defaultTempCelsius: Float = 0f
    ): List<PowerDischargePoint> {
        val points = mutableListOf<PowerDischargePoint>()
        val duration = durationMs.coerceAtLeast(60000L)
        val steps = 40 // 40 个密集采样时间切片，实现紧凑的俄罗斯方块柱状排布效果

        val targetLevel = currentLevel.coerceIn(0, 100)
        val actualStartLevel = max(startLevel.coerceIn(0, 100), targetLevel)
        val delta = (actualStartLevel - targetLevel).coerceAtLeast(0)
        val totalDischargeHours = duration / 3600000f
        val effectiveDeviceCapacity = getEffectiveDeviceCapacityMah()
        val nominalVoltageVolts = BatteryEnergyCalculator.DEFAULT_NOMINAL_VOLTAGE_VOLTS
        val defaultAvgWatts = if (totalDischargeHours > 0f && delta > 0) {
            val rawMah = effectiveDeviceCapacity * (delta / 100f)
            (rawMah * nominalVoltageVolts) / (1000f * totalDischargeHours)
        } else {
            0f
        }
        val effectiveOnWatts = if (screenOnPowerWatts > 0f) screenOnPowerWatts else defaultAvgWatts
        val effectiveOffWatts = if (screenOffPowerWatts > 0f) screenOffPowerWatts else 0f

        val now = System.currentTimeMillis()
        val startTs = now - duration

        // 1. 查询真实应用活动时间区间与屏幕点亮区间
        val (appIntervals, screenIntervals) = queryUsageIntervals(startTs, now)

        // 应用图标与名称缓存，优先利用已解析好的 appItems
        val pm = context.packageManager
        val appInfoMap = mutableMapOf<String, Pair<android.graphics.drawable.Drawable?, String>>()
        for (item in appItems) {
            appInfoMap[item.packageName] = Pair(item.icon, item.appName)
        }
        if (!appInfoMap.containsKey(context.packageName)) {
            try {
                val ai = pm.getApplicationInfo(context.packageName, 0)
                appInfoMap[context.packageName] = Pair(pm.getApplicationIcon(ai), pm.getApplicationLabel(ai).toString())
            } catch (_: Exception) {}
        }

        // 确定放电周期的前台主力应用（时长最长的主活跃应用，优先选择有桌面入口的用户三方应用）
        val candidateApps = appItems.filter { it.foregroundTimeMs > 0 }
        val userApps = candidateApps.filter { isUserInstalledApp(it.packageName) }
        val primaryPkg = (userApps.maxByOrNull { it.foregroundTimeMs }
            ?: candidateApps.maxByOrNull { it.foregroundTimeMs })?.packageName
            ?: context.packageName
        val otherApps = candidateApps.filter { it.packageName != primaryPkg && isUserInstalledApp(it.packageName) }

        if (!appInfoMap.containsKey(primaryPkg)) {
            try {
                val ai = pm.getApplicationInfo(primaryPkg, 0)
                appInfoMap[primaryPkg] = Pair(pm.getApplicationIcon(ai), pm.getApplicationLabel(ai).toString())
            } catch (_: Exception) {}
        }

        // 优先采用放电期间后台精准采集的秒级瞬时物理数据点（包含瞬时实时功率、温度、电压与电量）
        val realtimeSamples = getDischargeRealtimeSamples().filter { it.timestamp in (startTs - 15000L)..now }
        if (realtimeSamples.size >= 2) {
            val sortedSamples = realtimeSamples.sortedBy { it.timestamp }
            for (s in sortedSamples) {
                val pointTs = s.timestamp
                val elapsedHours = (pointTs - startTs).coerceAtLeast(0L) / 3600000f

                var isScreenOn = s.isScreenOn
                for (screenInt in screenIntervals) {
                    if (pointTs in screenInt.startTs..screenInt.endTs) {
                        isScreenOn = true
                        break
                    }
                }

                var matchedPkg: String? = null
                for (interval in appIntervals) {
                    if (pointTs in interval.startTs..interval.endTs) {
                        matchedPkg = interval.packageName
                        break
                    }
                }
                if (matchedPkg == null && isScreenOn) {
                    matchedPkg = primaryPkg
                }

                val icons = mutableListOf<android.graphics.drawable.Drawable>()
                val names = mutableListOf<String>()
                if (matchedPkg != null) {
                    val info = appInfoMap[matchedPkg]
                    if (info?.first != null) {
                        icons.add(info.first!!)
                        names.add(info.second)
                    }
                }

                points.add(
                    PowerDischargePoint(
                        timestamp = pointTs,
                        elapsedHours = elapsedHours,
                        batteryLevel = s.batteryLevel,
                        voltageVolts = s.voltageVolts,
                        temperature = s.temperature,
                        powerWatts = s.powerWatts,
                        activeAppIcons = icons,
                        isScreenOn = isScreenOn,
                        activeAppNames = names
                    )
                )
            }

            // 若最新采样点距今超过 2 秒，自动闭合注入当前瞬时终点
            val lastSample = sortedSamples.last()
            if (now > lastSample.timestamp + 2000L) {
                val currentStatus = getCurrentBatteryStatus()
                val latestWatts = if (lastSample.powerWatts > 0f) lastSample.powerWatts else defaultAvgWatts
                points.add(
                    PowerDischargePoint(
                        timestamp = now,
                        elapsedHours = (now - startTs) / 3600000f,
                        batteryLevel = currentStatus.levelPercent,
                        voltageVolts = currentStatus.voltageVolts,
                        temperature = currentStatus.temperature,
                        powerWatts = latestWatts,
                        activeAppIcons = emptyList(),
                        isScreenOn = true,
                        activeAppNames = emptyList()
                    )
                )
            }

            return points
        }

        // 2. 计算每个采样步长对应的时间切片范围 [slotStart, slotEnd]
        val stepSpan = duration.toDouble() / steps
        val stepIconsMap = mutableMapOf<Int, MutableList<android.graphics.drawable.Drawable>>()
        val stepNamesMap = mutableMapOf<Int, MutableList<String>>()
        val isScreenOnArray = BooleanArray(steps + 1) { false }

        // A. 依据系统事件探测亮屏区间
        for (i in 0..steps) {
            stepIconsMap[i] = mutableListOf()
            stepNamesMap[i] = mutableListOf()

            val slotStart = (startTs + i * stepSpan).toLong()
            val slotEnd = (startTs + (i + 1) * stepSpan).toLong().coerceAtMost(now)

            var isScreenOn = false
            for (screenInt in screenIntervals) {
                if (max(screenInt.startTs, slotStart) < kotlin.math.min(screenInt.endTs, slotEnd)) {
                    isScreenOn = true
                    break
                }
            }
            isScreenOnArray[i] = isScreenOn
        }

        // B. 校准亮屏指示条总占比，保证与权威 screenOnDurationMs 吻合
        // 仅当息屏时间极短（<= 3000ms）时全亮屏；若已精确探测到亮屏与息屏区间，保留真实物理切片状态
        if ((durationMs - screenOnDurationMs) <= 3000L) {
            for (i in 0..steps) isScreenOnArray[i] = true
        } else if (screenIntervals.isEmpty()) {
            val screenOnCount = isScreenOnArray.count { it }
            val effectiveScreenOnMs = if (screenOnDurationMs >= 0L) {
                screenOnDurationMs
            } else {
                candidateApps.sumOf { it.foregroundTimeMs }
            }
            val targetScreenOnSteps = if (duration > 0L) {
                ((effectiveScreenOnMs.toFloat() / duration) * (steps + 1)).roundToInt().coerceIn(0, steps + 1)
            } else {
                0
            }
            if (screenOnCount < targetScreenOnSteps) {
                for (i in (steps downTo 0)) {
                    if (!isScreenOnArray[i]) {
                        isScreenOnArray[i] = true
                        if (isScreenOnArray.count { it } >= targetScreenOnSteps) break
                    }
                }
            }
        }

        // 3. 为每个切片按真实时序精准分配前台活跃应用（严格遵循单前台原则与实际使用时长连续平铺）
        val assignedPkgArray = arrayOfNulls<String>(steps + 1)

        // A. 基础填充：所有处于亮屏状态的时间切片，默认均由主力前台应用（如持续亮屏使用的“电池检测”）连续充盈
        for (i in 0..steps) {
            if (isScreenOnArray[i]) {
                assignedPkgArray[i] = primaryPkg
            }
        }

        // B. 依据系统事件高精度区间，替换特定时间段内明确运行的其他前台应用
        for (i in 0..steps) {
            if (!isScreenOnArray[i]) continue
            val slotStart = (startTs + i * stepSpan).toLong()
            val slotEnd = (startTs + (i + 1) * stepSpan).toLong().coerceAtMost(now)

            for (interval in appIntervals) {
                if (interval.packageName != primaryPkg && (isUserInstalledApp(interval.packageName))) {
                    if (max(interval.startTs, slotStart) < kotlin.math.min(interval.endTs, slotEnd)) {
                        assignedPkgArray[i] = interval.packageName
                        break
                    }
                }
            }
        }

        // C. 辅助应用（如短暂使用的桌面、录制器）依据其实际前台耗时比例，在对应时段分配独立切片
        for (otherApp in otherApps) {
            val pkg = otherApp.packageName
            val currentAssignedCount = assignedPkgArray.count { it == pkg }
            val targetStepsCount = max(1, ((otherApp.foregroundTimeMs.toFloat() / duration) * steps).roundToInt())
            val neededSteps = targetStepsCount - currentAssignedCount
            if (neededSteps > 0) {
                val lastUsed = otherApp.lastUsedTimeMs
                val centerRatio = if (lastUsed in startTs..now) {
                    ((lastUsed - startTs).toFloat() / duration).coerceIn(0.05f, 0.95f)
                } else {
                    0.5f
                }
                val centerStep = (centerRatio * steps).roundToInt().coerceIn(0, steps)
                var allocated = 0
                for (offset in 0..steps) {
                    val stepIdx = if (offset % 2 == 0) (centerStep - offset / 2).coerceIn(0, steps) else (centerStep + (offset + 1) / 2).coerceIn(0, steps)
                    if (isScreenOnArray[stepIdx] && assignedPkgArray[stepIdx] == primaryPkg) {
                        assignedPkgArray[stepIdx] = pkg
                        allocated++
                        if (allocated >= neededSteps) break
                    }
                }
            }
        }

        // D. 将分配到的各切片应用图标填入对应步长，保证连续性与单前台纯净性
        for (i in 0..steps) {
            val pkg = assignedPkgArray[i] ?: continue
            var cached = appInfoMap[pkg]
            if (cached == null) {
                val icon = try {
                    val ai = pm.getApplicationInfo(pkg, 0)
                    pm.getApplicationIcon(ai)
                } catch (_: Exception) {
                    null
                }
                val name = try {
                    val ai = pm.getApplicationInfo(pkg, 0)
                    pm.getApplicationLabel(ai).toString()
                } catch (_: Exception) {
                    pkg.substringAfterLast('.')
                }
                cached = Pair(icon, name)
                appInfoMap[pkg] = cached
            }

            val icon = cached.first
            if (icon != null && !stepIconsMap[i]!!.contains(icon)) {
                stepIconsMap[i]!!.add(icon)
                stepNamesMap[i]!!.add(cached.second)
            }
        }

        // 5. 预先计算各时间切片的能耗积分权重（当缺乏系统底层逐点历史时，按各时段亮息屏真实能耗积分推导真实电量，息屏平缓微降，亮屏陡降）
        val accumEnergyArray = FloatArray(steps + 1) { 0f }
        var currentAccumEnergy = 0f
        val stepHours = (stepSpan / 3600000.0).toFloat()
        for (j in 0 until steps) {
            val sliceWatts = if (isScreenOnArray[j]) effectiveOnWatts else effectiveOffWatts
            currentAccumEnergy += sliceWatts * stepHours
            accumEnergyArray[j + 1] = currentAccumEnergy
        }
        val totalAccumEnergy = currentAccumEnergy

        // 6. 生成走势曲线数据点（严格按每个时间对应的真实电量划线）
        val sortedHistory = historyLevelPoints.filter { it.second in 1..100 }.sortedBy { it.first }
        // 预先对历史温度采样点排序，用于后续按时间插值计算各趋势点的真实温度（取代硬编码 30.5f）
        val sortedHistoryTemps = historyTempPoints.sortedBy { it.first }

        for (i in 0..steps) {
            val ratio = i.toFloat() / steps
            val pointTs = (startTs + duration * ratio).toLong()
            val elapsedHours = (duration * ratio) / 3600000f

            val level = if (delta == 0) {
                targetLevel
            } else if (sortedHistory.isNotEmpty()) {
                // 方案 A：从系统 Battery History 获取该时刻对应的真实电量（前后区间线性插值）
                if (pointTs <= sortedHistory.first().first) {
                    sortedHistory.first().second
                } else if (pointTs >= sortedHistory.last().first) {
                    sortedHistory.last().second
                } else {
                    val nextIdx = sortedHistory.indexOfFirst { it.first >= pointTs }
                    if (nextIdx > 0) {
                        val pPrev = sortedHistory[nextIdx - 1]
                        val pNext = sortedHistory[nextIdx]
                        val tSpan = (pNext.first - pPrev.first).coerceAtLeast(1L)
                        val tRatio = (pointTs - pPrev.first).toFloat() / tSpan
                        (pPrev.second + (pNext.second - pPrev.second) * tRatio).roundToInt()
                    } else {
                        sortedHistory.first().second
                    }
                }
            } else {
                // 方案 B：严格按各时间段亮/息屏真实能耗积分推导每个时刻的电量
                val energyRatio = if (totalAccumEnergy > 0f) {
                    (accumEnergyArray[i] / totalAccumEnergy).coerceIn(0f, 1f)
                } else {
                    ratio
                }
                (actualStartLevel - delta * energyRatio).roundToInt().coerceIn(targetLevel, actualStartLevel)
            }

            val iconsForPoint = stepIconsMap[i] ?: emptyList()
            val namesForPoint = stepNamesMap[i] ?: emptyList()
            val isScreenOn = isScreenOnArray[i]

            // 按亮/息屏状态动态计算该时刻功耗
            val pointPowerWatts = if (isScreenOn) effectiveOnWatts else effectiveOffWatts

            // 从历史温度采样点线性插值当前时刻温度（取代硬编码 30.5f）
            val pointTemp: Float = if (sortedHistoryTemps.isNotEmpty()) {
                when {
                    pointTs <= sortedHistoryTemps.first().first -> sortedHistoryTemps.first().second
                    pointTs >= sortedHistoryTemps.last().first -> sortedHistoryTemps.last().second
                    else -> {
                        val nextIdx = sortedHistoryTemps.indexOfFirst { it.first >= pointTs }
                        if (nextIdx > 0) {
                            val p1 = sortedHistoryTemps[nextIdx - 1]
                            val p2 = sortedHistoryTemps[nextIdx]
                            val tSpan = (p2.first - p1.first).coerceAtLeast(1L)
                            val tRatio = (pointTs - p1.first).toFloat() / tSpan
                            p1.second + (p2.second - p1.second) * tRatio
                        } else sortedHistoryTemps.first().second
                    }
                }
            } else {
                defaultTempCelsius
            }

            points.add(
                PowerDischargePoint(
                    timestamp = pointTs,
                    elapsedHours = elapsedHours,
                    batteryLevel = level,
                    voltageVolts = currentVoltageVolts,   // 传入的实时电压（取代硬编码 3.972f）
                    temperature = pointTemp,               // 插值温度（取代硬编码 30.5f）
                    powerWatts = pointPowerWatts,          // 动态亮/息屏功耗（取代硬编码 2.10f）
                    activeAppIcons = iconsForPoint,
                    isScreenOn = isScreenOn,
                    activeAppNames = namesForPoint
                )
            )
        }

        return points
    }

    /**
     * 重置当前放电周期的采样记录与基准快照。
     */
    fun resetPowerStats() {
        val now = System.currentTimeMillis()
        val curStatus = getCurrentBatteryStatus()
        resetDischargeTempPoints(now, curStatus.temperature)
        resetDischargeRealtimeSamples(now, curStatus.levelPercent, curStatus.voltageVolts, curStatus.temperature, 0f, true)

        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val counterUah = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: 0
        val editor = prefs.edit()
            .putLong(PREF_KEY_LAST_UNPLUG_TIME, now)
            .putInt(PREF_KEY_LAST_UNPLUG_LEVEL, curStatus.levelPercent.coerceIn(0, 100))
            .putLong("pref_last_archived_unplug_time", 0L)
            .putLong("last_reset_time", now)
            .remove(PREF_KEY_UNPLUG_USAGE_SNAPSHOT)
            .remove(PREF_KEY_UNPLUG_BG_SERVICE_SNAPSHOT)
        if (counterUah > 0) {
            editor.putInt(PREF_KEY_LAST_UNPLUG_CHARGE_COUNTER, counterUah)
        }
        editor.apply()

        lastArchivedUnplugTime = 0L
        saveUnplugUsageSnapshot()

        if (isShizukuAuthorized()) {
            shizukuParser.resetBatteryStats()
        }
    }

    /**
     * 计算普通模式下的三大指标概览数据（基于真实电池衰减损耗有效容量与物理能量严格守恒）。
     *
     * @param currentLevel 当前电量百分比
     * @param appList 应用列表
     * @return 功耗指标对象 [PowerOverviewStats]
     */
    fun calculateOverviewStats(
        currentLevel: Int,
        appList: List<AppPowerUsageItem>
    ): PowerOverviewStats {
        val now = System.currentTimeMillis()
        val unplugTime = getLastUnplugTime()
        val totalMs = if (unplugTime > 0L && (now - unplugTime) < 24 * 3600000L) {
            (now - unplugTime).coerceAtLeast(1000L)
        } else {
            android.os.SystemClock.elapsedRealtime().coerceAtLeast(3600000L)
        }
        val startTs = now - totalMs
        val (_, screenIntervals) = queryUsageIntervals(startTs, now)
        val eventScreenOnMs = calculateScreenOnDurationFromIntervals(screenIntervals, startTs, now)
        val fgSum = appList.sumOf { it.foregroundTimeMs }
        val screenOnMs = if (screenIntervals.isNotEmpty() && eventScreenOnMs > 0L) {
            eventScreenOnMs.coerceIn(0L, totalMs)
        } else if (fgSum > 0) {
            fgSum.coerceAtMost(totalMs)
        } else {
            0L
        }
        val screenOffMs = (totalMs - screenOnMs).coerceAtLeast(0L)

        val screenOnStr = formatDuration(screenOnMs)
        val screenOffStr = formatDuration(screenOffMs)
        val totalStr = formatDuration(totalMs)

        val effectiveCapacity = getEffectiveDeviceCapacityMah()
        val unplugLevel = getLastUnplugLevel().coerceIn(0, 100)
        val startLevel = if (unplugLevel >= currentLevel) unplugLevel else currentLevel
        val dropPercent = (startLevel - currentLevel).coerceAtLeast(0)
        val dischargeHours = totalMs / 3600000f
        val screenOnHours = screenOnMs / 3600000f
        val screenOffHours = screenOffMs / 3600000f
        // 能量计算统一遵循工业标准与标称工作电压（标压 3.85V），杜绝端电压瞬时压降与回弹波动对累计能耗产生干扰
        val nominalVoltageVolts = BatteryEnergyCalculator.DEFAULT_NOMINAL_VOLTAGE_VOLTS

        // 基于真实电量变化独立计算整机总能耗，不依赖 App 能耗列表
        // （普通模式 App 能耗由此处整机功耗反向分配，不可循环依赖）
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val currentCounterUah = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: 0
        val lastCounterUah = prefs.getInt(PREF_KEY_LAST_UNPLUG_CHARGE_COUNTER, 0)
        val hwDischargedMah = if (lastCounterUah > 0 && currentCounterUah > 0 && lastCounterUah >= currentCounterUah) {
            (lastCounterUah - currentCounterUah) / 1000f
        } else {
            0f
        }

        // 核心防护：若某些应用包含跨周期历史累积时长（如后台 13 小时），在当前放电周期（如 18 秒）内，
        // 其真实消耗必须按当前放电周期时长进行时间窗口归一，杜绝历史能耗污染当前周期引发虚假尖峰
        val currentPeriodAppEnergyWh = appList.sumOf { item ->
            val totalAppTimeMs = item.foregroundTimeMs + item.backgroundTimeMs
            if (totalAppTimeMs > totalMs && totalAppTimeMs > 0L) {
                (item.energyWh * (totalMs.toDouble() / totalAppTimeMs.toDouble())).coerceAtMost(item.energyWh.toDouble())
            } else {
                item.energyWh.toDouble()
            }
        }.toFloat()
        val minPhysicalMah = if (currentPeriodAppEnergyWh > 0f) (currentPeriodAppEnergyWh * 1000f / nominalVoltageVolts) else 0f

        val smoothedDropMah = if (dropPercent > 0) {
            effectiveCapacity * (dropPercent / 100f)
        } else {
            0f
        }

        // 物理守恒基础：硬件芯片库仑计差值与纯应用前台实耗电量，绝不采用 dumpsys computedDrainMah 软件估算
        val validHwMah = if (hwDischargedMah > 0f) {
            hwDischargedMah
        } else {
            0f
        }
        val physicalDrainMah = when {
            validHwMah > 0f -> validHwMah
            dropPercent > 0 -> smoothedDropMah
            minPhysicalMah > 0f -> minPhysicalMah
            else -> 0f
        }
        val physicalTotalEnergyWh = (physicalDrainMah * nominalVoltageVolts) / 1000f

        // 硬件时序切片微积分：直接对底层物理放电采样点按亮灭屏做数值微积分
        val recentSamples = getDischargeRealtimeSamples().filter { it.timestamp in startTs..now }
        val (intTotalEnergyWh, intTotalPowerWatts) = calculatePhysicalIntegratedEnergyAndPower(recentSamples, filterScreenOn = null)
        val (intOnEnergyWh, intOnPowerWatts) = calculatePhysicalIntegratedEnergyAndPower(recentSamples, filterScreenOn = true)
        val (intOffEnergyWh, intOffPowerWatts) = calculatePhysicalIntegratedEnergyAndPower(recentSamples, filterScreenOn = false)

        val hasValidHardwareIntegration = recentSamples.size >= 2 &&
                (intTotalEnergyWh > 0f || intTotalPowerWatts > 0f || intOnPowerWatts > 0f || intOffPowerWatts > 0f)

        // 采样密度评估：计算平均采样间隔（毫秒），用于评估时序微积分的离散拟合可信度
        val avgSampleIntervalMs = if (recentSamples.size >= 2) {
            val sampleSpanMs = recentSamples.last().timestamp - recentSamples.first().timestamp
            if (sampleSpanMs > 0L) (sampleSpanMs / (recentSamples.size - 1)) else 0L
        } else {
            0L
        }
        // 高频密集采样判定：平均采样间隔 <= 5000ms（如默认的 1 秒/2 秒等高频监控模式）
        val isHighFrequencySampling = hasValidHardwareIntegration && (avgSampleIntervalMs in 1L..5000L)

        var realTotalEnergyWh: Float
        var realDischargedMah: Float
        val avgPower: Float
        val screenOnPower: Float
        val screenOffPower: Float
        val onEnergyWh: Float
        val offEnergyWh: Float

        if (isHighFrequencySampling) {
            // 1. 高频密集硬件采样区间（<= 5 秒，完美捕捉瞬时脉冲细节，对标 BatteryRecorder）：
            // 100% 采用时序梯形微积分算出的物理功耗与累积能耗
            if (screenOffHours <= 0f || screenOffMs < 30000L) {
                screenOnPower = if (intOnPowerWatts > 0f) intOnPowerWatts else intTotalPowerWatts
                screenOffPower = 0f
                offEnergyWh = 0f
                onEnergyWh = if (intOnEnergyWh > 0f) intOnEnergyWh else if (intTotalEnergyWh > 0f) intTotalEnergyWh else (screenOnPower * screenOnHours)
                realTotalEnergyWh = onEnergyWh
                avgPower = screenOnPower
            } else if (screenOnHours <= 0f) {
                screenOnPower = 0f
                onEnergyWh = 0f
                screenOffPower = if (intOffPowerWatts > 0f) intOffPowerWatts else intTotalPowerWatts
                offEnergyWh = if (intOffEnergyWh > 0f) intOffEnergyWh else if (intTotalEnergyWh > 0f) intTotalEnergyWh else (screenOffPower * screenOffHours)
                realTotalEnergyWh = offEnergyWh
                avgPower = screenOffPower
            } else {
                screenOnPower = if (intOnPowerWatts > 0f) intOnPowerWatts else if (screenOnHours > 0f && intOnEnergyWh > 0f) (intOnEnergyWh / screenOnHours) else 0f
                onEnergyWh = if (intOnEnergyWh > 0f) intOnEnergyWh else (screenOnPower * screenOnHours)

                if (intOffPowerWatts > 0f || intOffEnergyWh > 0f) {
                    screenOffPower = if (intOffPowerWatts > 0f) intOffPowerWatts else if (screenOffHours > 0f && intOffEnergyWh > 0f) (intOffEnergyWh / screenOffHours) else 0f
                    offEnergyWh = if (intOffEnergyWh > 0f) intOffEnergyWh else (screenOffPower * screenOffHours)
                } else {
                    val baselineEnergyWh = physicalTotalEnergyWh
                    offEnergyWh = (baselineEnergyWh - onEnergyWh).coerceAtLeast(0f)
                    screenOffPower = if (screenOffHours > 0f) offEnergyWh / screenOffHours else 0f
                }

                realTotalEnergyWh = onEnergyWh + offEnergyWh
                avgPower = if (dischargeHours > 0f) realTotalEnergyWh / dischargeHours else 0f
            }
            realDischargedMah = (realTotalEnergyWh * 1000f) / nominalVoltageVolts
        } else if (physicalTotalEnergyWh > 0.001f) {
            // 2. 长间隔稀疏采样（如 10秒/30秒/60秒）或微积分未运行，但底层主板硬件芯片库仑计/实际掉电已有可信物理变化：
            // 总能量绝对锚定主板芯片硬件库仑计或实际掉电量（100% 包含屏幕及所有外设），微积分仅作为亮灭屏相对功耗比例解耦
            realDischargedMah = physicalDrainMah
            realTotalEnergyWh = physicalTotalEnergyWh
            avgPower = if (dischargeHours > 0f) realTotalEnergyWh / dischargeHours else 0f

            if (screenOffHours <= 0f || screenOffMs < 30000L) {
                screenOnPower = avgPower
                screenOffPower = 0f
                onEnergyWh = realTotalEnergyWh
                offEnergyWh = 0f
            } else if (screenOnHours <= 0f) {
                screenOffPower = avgPower.coerceAtLeast(0f)
                screenOnPower = 0f
                offEnergyWh = realTotalEnergyWh
                onEnergyWh = 0f
            } else {
                // 既有亮屏又有息屏：利用微积分测得的典型工况功率进行相对比例解耦，绝不虚构
                if (intOnPowerWatts > 0.05f && intOffPowerWatts > 0.05f) {
                    val estOnE = intOnPowerWatts * screenOnHours
                    val estOffE = intOffPowerWatts * screenOffHours
                    val sumEstE = estOnE + estOffE
                    if (sumEstE > 0f) {
                        onEnergyWh = (realTotalEnergyWh * (estOnE / sumEstE)).coerceAtLeast(0f)
                        offEnergyWh = (realTotalEnergyWh - onEnergyWh).coerceAtLeast(0f)
                    } else {
                        val ratio = (screenOnHours / dischargeHours).coerceIn(0f, 1f)
                        onEnergyWh = realTotalEnergyWh * ratio
                        offEnergyWh = (realTotalEnergyWh - onEnergyWh).coerceAtLeast(0f)
                    }
                    screenOnPower = if (screenOnHours > 0f) onEnergyWh / screenOnHours else intOnPowerWatts
                    screenOffPower = if (screenOffHours > 0f) offEnergyWh / screenOffHours else intOffPowerWatts
                } else if (intOnPowerWatts > 0.05f) {
                    val rawOnEnergy = intOnPowerWatts * screenOnHours
                    if (rawOnEnergy < realTotalEnergyWh) {
                        onEnergyWh = rawOnEnergy
                        offEnergyWh = realTotalEnergyWh - onEnergyWh
                    } else {
                        val ratio = (screenOnHours / dischargeHours).coerceIn(0f, 1f)
                        onEnergyWh = realTotalEnergyWh * ratio
                        offEnergyWh = (realTotalEnergyWh - onEnergyWh).coerceAtLeast(0f)
                    }
                    screenOnPower = intOnPowerWatts
                    screenOffPower = if (screenOffHours > 0f) offEnergyWh / screenOffHours else 0f
                } else {
                    val fgTotalEnergy = appList.sumOf { it.foregroundEnergyWh.toDouble() }.toFloat()
                    if (fgTotalEnergy in 0.001f..realTotalEnergyWh) {
                        onEnergyWh = fgTotalEnergy
                        offEnergyWh = realTotalEnergyWh - onEnergyWh
                    } else {
                        val onRatio = (screenOnHours / dischargeHours).coerceIn(0f, 1f)
                        onEnergyWh = realTotalEnergyWh * onRatio
                        offEnergyWh = realTotalEnergyWh - onEnergyWh
                    }
                    screenOnPower = if (screenOnHours > 0f) onEnergyWh / screenOnHours else avgPower
                    screenOffPower = if (screenOffHours > 0f) offEnergyWh / screenOffHours else 0f
                }
            }
        } else if (hasValidHardwareIntegration) {
            // 3. 刚拔电短时间内（物理电量/硬件库仑计尚未产生步进掉电），以微积分作为冷启动初始放电参考，杜绝展示为 0W
            if (screenOffHours <= 0f || screenOffMs < 30000L) {
                screenOnPower = if (intOnPowerWatts > 0f) intOnPowerWatts else intTotalPowerWatts
                screenOffPower = 0f
                offEnergyWh = 0f
                onEnergyWh = if (intOnEnergyWh > 0f) intOnEnergyWh else if (intTotalEnergyWh > 0f) intTotalEnergyWh else (screenOnPower * screenOnHours)
                realTotalEnergyWh = onEnergyWh
                avgPower = screenOnPower
            } else if (screenOnHours <= 0f) {
                screenOnPower = 0f
                onEnergyWh = 0f
                screenOffPower = if (intOffPowerWatts > 0f) intOffPowerWatts else intTotalPowerWatts
                offEnergyWh = if (intOffEnergyWh > 0f) intOffEnergyWh else if (intTotalEnergyWh > 0f) intTotalEnergyWh else (screenOffPower * screenOffHours)
                realTotalEnergyWh = offEnergyWh
                avgPower = screenOffPower
            } else {
                screenOnPower = if (intOnPowerWatts > 0f) intOnPowerWatts else if (screenOnHours > 0f && intOnEnergyWh > 0f) (intOnEnergyWh / screenOnHours) else 0f
                onEnergyWh = if (intOnEnergyWh > 0f) intOnEnergyWh else (screenOnPower * screenOnHours)
                screenOffPower = if (intOffPowerWatts > 0f) intOffPowerWatts else if (screenOffHours > 0f && intOffEnergyWh > 0f) (intOffEnergyWh / screenOffHours) else 0f
                offEnergyWh = if (intOffEnergyWh > 0f) intOffEnergyWh else (screenOffPower * screenOffHours)
                realTotalEnergyWh = onEnergyWh + offEnergyWh
                avgPower = if (dischargeHours > 0f) realTotalEnergyWh / dischargeHours else 0f
            }
            realDischargedMah = (realTotalEnergyWh * 1000f) / nominalVoltageVolts
        } else {
            // 4. 完全无硬件采样且未掉电的极冷启动（刚拔电数秒内）
            realDischargedMah = 0f
            realTotalEnergyWh = 0f
            avgPower = 0f
            screenOnPower = 0f
            screenOffPower = 0f
            onEnergyWh = 0f
            offEnergyWh = 0f
        }

        // 剩余续航时间科学推算（基于剩余电量可用能量及各工况功耗真实推算，能量按标压折算）
        val remainingTotalHours = if (avgPower >= 0.05f) {
            ((effectiveCapacity * (currentLevel / 100f) * nominalVoltageVolts) / 1000f) / avgPower
        } else {
            0f
        }

        val remCompStr = if (remainingTotalHours > 0f) formatHoursToText(remainingTotalHours) else "--"
        val remOnStr = if (screenOnPower >= 0.05f && remainingTotalHours > 0f) {
            formatHoursToText(remainingTotalHours * (avgPower / screenOnPower))
        } else {
            "--"
        }
        val remOffStr = if (screenOffPower >= 0.05f && remainingTotalHours > 0f) {
            formatHoursToText(remainingTotalHours * (avgPower / screenOffPower))
        } else {
            "--"
        }

        // 严格遵循 BatteryRecorder 算法标准：分 App 不统计后台能耗与平均功耗，整机待机放电统一忠实由息屏三态承载
        val allBgEnergyWh = 0f
        val bgWatts = 0f
        val remBgStr = "--"
        val rawBgMs = screenOffMs.coerceIn(0L, totalMs)
        val bgDurationStr = formatDuration(rawBgMs)

        return PowerOverviewStats(
            avgPowerWatts = avgPower,
            screenOnPowerWatts = screenOnPower,
            screenOffPowerWatts = screenOffPower,
            backgroundPowerWatts = bgWatts,
            screenOnDurationText = screenOnStr,
            screenOffDurationText = screenOffStr,
            totalDurationText = totalStr,
            backgroundDurationText = bgDurationStr,
            remainingScreenOnText = remOnStr,
            remainingCompositeText = remCompStr,
            remainingScreenOffText = remOffStr,
            remainingBackgroundText = remBgStr,
            screenOnEnergyWh = onEnergyWh,
            totalEnergyWh = realTotalEnergyWh,
            screenOffEnergyWh = offEnergyWh,
            backgroundEnergyWh = allBgEnergyWh,
            usedDurationText = "$screenOnStr / $totalStr",
            remainingLifeText = remCompStr
        )
    }

    /**
     * 将浮点小时数格式化为 "14h46m" 或 "2d18h" 等友好文本。
     * 若小时数异常过大（大于 720 小时即 30 天）或为无效数值，返回 "--"。
     *
     * @param hours 小时浮点数
     * @return 格式化后的时间文本
     */
    private fun formatHoursToText(hours: Float): String {
        if (hours <= 0f || hours.isNaN() || hours.isInfinite() || hours > 720f) {
            return "--"
        }
        val totalMinutes = (hours * 60).toLong().coerceAtLeast(1L)
        val days = totalMinutes / 1440
        val h = (totalMinutes % 1440) / 60
        val m = totalMinutes % 60
        return when {
            days > 0 -> "${days}d${h}h"
            h > 0 -> "${h}h${m}m"
            else -> "${m}m"
        }
    }

    /**
     * 将毫秒时长格式化为 "1d 2h 30m" 或 "58m45s" 友善文本。
     *
     * @param ms 毫秒数
     * @return 格式化文本
     */
    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val days = totalSec / 86400
        val hours = (totalSec % 86400) / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60

        return when {
            days > 0 -> "${days}d${hours}h"
            hours > 0 -> "${hours}h${minutes}m"
            else -> "${minutes}m${seconds}s"
        }
    }

    /**
     * 将全量耗电数据包转换为功耗时间轴状态模型。
     * 时间轴严格以开始这次放电时间（最近一次拔电时刻）为起点，当前时刻为终点。
     *
     * @param fullPackage 包含采样点与应用列表的完整数据包 [FullPowerDataPackage]
     * @param metric 默认选中的指标类型，默认为 POWER [TimelineMetric]
     * @return 转换后的时间轴状态模型 [BatteryTimelineState]
     */
    fun buildTimelineState(
        fullPackage: FullPowerDataPackage,
        metric: TimelineMetric = TimelineMetric.POWER
    ): BatteryTimelineState {
        val points = fullPackage.trendPoints
        val now = System.currentTimeMillis()
        val unplugTime = getLastUnplugTime()

        // 判断是否为历史快照记录（只有在显式快照或点集首尾均在24小时以前时）
        val isHistoryRecord = points.isNotEmpty() && points.last().timestamp < (now - 3600_000L * 24)

        // 确定时间轴起点与终点：以开始这次放电时间为起点，当前时间为终点
        val startTs: Long
        val endTs: Long

        if (isHistoryRecord) {
            startTs = points.first().timestamp
            endTs = points.last().timestamp
        } else {
            val effectiveUnplug = if (unplugTime in 1..now) unplugTime else (points.firstOrNull()?.timestamp ?: (now - 3600_000L))
            startTs = effectiveUnplug
            endTs = max(now, startTs + 1000L)
        }

        // 1. 转换物理采样点（确保 startTs 与 endTs 100% 闭合覆盖）
        val samples = mutableListOf<BatterySample>()
        if (points.isNotEmpty()) {
            val firstPt = points.first()
            val initialLevel = getLastUnplugLevel()
            val snap = fullPackage.batterySnapshot
            val lastPt = points.last()

            // 根据整机平均功耗与当前电压估算放电电流（取代硬编码 500mA）
            val estAvgCurrentMa = if (snap.voltageVolts > 0.5f) {
                (fullPackage.overviewStats.avgPowerWatts * 1000.0) / snap.voltageVolts
            } else {
                500.0
            }

            // 必须包含起点采样点 (startTs)
            samples.add(
                BatterySample(
                    timestamp = startTs,
                    batteryLevel = if (initialLevel in 1..100) initialLevel else firstPt.batteryLevel,
                    voltageMv = (firstPt.voltageVolts * 1000).toInt(),
                    currentMa = if (firstPt.voltageVolts > 0.5f) (firstPt.powerWatts * 1000.0 / firstPt.voltageVolts) else estAvgCurrentMa,
                    temperatureC = firstPt.temperature.toDouble(),
                    powerMw = (firstPt.powerWatts * 1000).toDouble()
                )
            )

            for (pt in points) {
                if (pt.timestamp > startTs && pt.timestamp < endTs) {
                    val vMv = (pt.voltageVolts * 1000).toInt()
                    val pMw = (pt.powerWatts * 1000).toDouble()
                    val cMa = if (pt.voltageVolts > 0f) (pMw / pt.voltageVolts) else estAvgCurrentMa
                    samples.add(
                        BatterySample(
                            timestamp = pt.timestamp,
                            batteryLevel = pt.batteryLevel,
                            voltageMv = vMv,
                            currentMa = cMa,
                            temperatureC = pt.temperature.toDouble(),
                            powerMw = pMw
                        )
                    )
                }
            }

            // 必须包含终点采样点 (endTs)
            // 包含终点采样点 (endTs)
            if (endTs > startTs) {
                val latestPwrMw = if (lastPt.powerWatts > 0.05f) {
                    (lastPt.powerWatts * 1000).toDouble()
                } else if (fullPackage.overviewStats.avgPowerWatts > 0f) {
                    (fullPackage.overviewStats.avgPowerWatts * 1000).toDouble()
                } else {
                    0.0
                }
                samples.add(
                    BatterySample(
                        timestamp = endTs,
                        batteryLevel = snap.levelPercent,
                        voltageMv = if (snap.voltageVolts > 0f) (snap.voltageVolts * 1000).toInt() else (lastPt.voltageVolts * 1000).toInt(),
                        currentMa = estAvgCurrentMa,
                        temperatureC = snap.temperature.toDouble(),
                        powerMw = latestPwrMw
                    )
                )
            }
        }

        // 2. 查询高精度前台应用与屏幕状态区间
        val (appIntervals, screenIntervals) = queryUsageIntervals(startTs, endTs)

        // 3. 构建高精度屏幕状态区间（亮屏绿色 / 息屏红色，精确到秒）
        val screenEvents = mutableListOf<ScreenEvent>()
        val onIntervals = mutableListOf<Pair<Long, Long>>()
        for (s in screenIntervals) {
            val st = maxOf(s.startTs, startTs)
            val et = minOf(s.endTs, endTs)
            if (et > st) onIntervals.add(Pair(st, et))
        }
        for (a in appIntervals) {
            val st = maxOf(a.startTs, startTs)
            val et = minOf(a.endTs, endTs)
            if (et > st) onIntervals.add(Pair(st, et))
        }

        if (onIntervals.isNotEmpty()) {
            val sortedOn = onIntervals.sortedBy { it.first }
            val mergedOn = mutableListOf<Pair<Long, Long>>()
            var cur = sortedOn[0]
            for (i in 1 until sortedOn.size) {
                val nxt = sortedOn[i]
                if (nxt.first <= cur.second + 1000L) {
                    cur = Pair(cur.first, maxOf(cur.second, nxt.second))
                } else {
                    mergedOn.add(cur)
                    cur = nxt
                }
            }
            mergedOn.add(cur)

            var cursor = startTs
            for (onSpan in mergedOn) {
                if (onSpan.first > cursor) {
                    // 息屏区间（精确到秒）
                    screenEvents.add(ScreenEvent(cursor, onSpan.first, false))
                }
                val onStart = maxOf(onSpan.first, cursor)
                if (onSpan.second > onStart) {
                    // 亮屏区间（精确到秒）
                    screenEvents.add(ScreenEvent(onStart, onSpan.second, true))
                }
                cursor = maxOf(cursor, onSpan.second)
            }
            if (cursor < endTs) {
                // 尾部息屏区间
                screenEvents.add(ScreenEvent(cursor, endTs, false))
            }
        } else if (points.isNotEmpty()) {
            // 回退到 points 中的亮/息屏标记
            var currentScreenOn = points[0].isScreenOn
            var segmentStart = startTs
            for (i in 1 until points.size) {
                val pt = points[i]
                if (pt.isScreenOn != currentScreenOn) {
                    screenEvents.add(ScreenEvent(segmentStart, pt.timestamp, currentScreenOn))
                    segmentStart = pt.timestamp
                    currentScreenOn = pt.isScreenOn
                }
            }
            screenEvents.add(ScreenEvent(segmentStart, endTs, currentScreenOn))
        }

        // 4. 构建 App 活动时间轴事件列表
        val appMap = fullPackage.appList.associateBy { it.packageName }
        val pm = context.packageManager
        val appEvents = mutableListOf<AppTimelineEvent>()

        // 预先批量一次性查询时间轴全区间内的双通道网络流量，过滤掉无流量 UID 的高频跨进程 IPC
        val totalNetMap = if (appIntervals.isNotEmpty() && startTs < endTs) {
            networkStatsHelper.getAllUidsNetworkBytes(startTs, endTs)
        } else {
            emptyMap()
        }

        for (interval in appIntervals) {
            val pkg = interval.packageName
            val item = appMap[pkg]
            val duration = (interval.endTs - interval.startTs).coerceAtLeast(0L)
            val appName = item?.appName ?: try {
                val ai = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(ai).toString()
            } catch (_: Exception) {
                pkg
            }
            val icon = item?.icon ?: try {
                val ai = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationIcon(ai)
            } catch (_: Exception) {
                null
            }

            val uid = try {
                pm.getApplicationInfo(pkg, 0).uid
            } catch (_: Exception) {
                10000
            }

            val directWh = item?.energyWh?.toDouble()
            val directMwh = directWh?.times(1000.0)
            val avgMw = item?.let { it.avgPowerWatts * 1000.0 } ?: (fullPackage.overviewStats.avgPowerWatts * 1000.0)
            val peakMw = avgMw // 无瞬时时序切片时峰值等于平均功率

            val appCpuMs = if (duration > 0L && item != null && item.foregroundTimeMs > 0L) {
                ((item.cpuTimeMs * duration) / item.foregroundTimeMs).coerceAtMost(duration)
            } else {
                item?.cpuTimeMs ?: 0L
            }

            // 若全周期内该 UID 零流量，则子时间片直接为 0L，跳过跨进程查询
            val appNetBytes = if (totalNetMap.isNotEmpty() && (totalNetMap[uid] ?: 0L) <= 0L) {
                0L
            } else {
                networkStatsHelper.getUidNetworkBytes(uid, interval.startTs, interval.endTs)
            }
            val appWakeMs = item?.wakelockTimeMs ?: 0L
            val appGpsMs = item?.gpsTimeMs ?: 0L

            appEvents.add(
                AppTimelineEvent(
                    packageName = pkg,
                    uid = uid,
                    appName = appName,
                    icon = icon,
                    startTime = interval.startTs,
                    endTime = interval.endTs,
                    durationMs = duration,
                    screenOn = true,
                    energyMwh = directMwh,
                    averagePowerMw = avgMw,
                    peakPowerMw = peakMw,
                    cpuTimeMs = appCpuMs,
                    networkBytes = appNetBytes,
                    wakelockTimeMs = appWakeMs,
                    gpsTimeMs = appGpsMs,
                    confidence = if (fullPackage.isShizukuRealData) ConfidenceLevel.HIGH else ConfidenceLevel.MEDIUM,
                    source = if (fullPackage.isShizukuRealData) EnergySource.BATTERY_STATS else EnergySource.ESTIMATED
                )
            )
        }

        // 5. 实时监控状态闭合校准：确保最新时刻（endTs / now）若处于亮屏状态，前台应用事件与 endTs 完全闭合对齐
        if (!isHistoryRecord && endTs >= now - 120_000L && screenEvents.any { it.isScreenOn && it.endTime >= endTs - 10_000L }) {
            val lastAppEvent = appEvents.maxByOrNull { it.endTime }
            if (lastAppEvent != null && lastAppEvent.endTime < endTs && lastAppEvent.endTime >= endTs - 60_000L) {
                // 若末尾最后一个事件与当前时间接近，直接闭合其 endTime 到 endTs
                val idx = appEvents.indexOf(lastAppEvent)
                if (idx >= 0) {
                    appEvents[idx] = lastAppEvent.copy(
                        endTime = endTs,
                        durationMs = max(0L, endTs - lastAppEvent.startTime)
                    )
                }
            } else if (lastAppEvent == null || lastAppEvent.endTime < endTs - 10_000L) {
                // 若时序末尾缺失前台事件，查询系统当前置顶应用进行对齐闭合
                val currentPkg = ShizukuForegroundAppDetector.getForegroundPackageName(context)
                    ?: KeepAliveAccessibilityService.currentForegroundPackage
                    ?: context.packageName

                val defaultHome = getDefaultHomeLauncherPackage()
                if (!currentPkg.isNullOrEmpty() && currentPkg != defaultHome) {
                    val fallbackStart = maxOf(lastAppEvent?.endTime ?: startTs, endTs - 60_000L, startTs)
                    if (endTs > fallbackStart) {
                        val item = appMap[currentPkg]
                        val appName = item?.appName ?: try {
                            val ai = pm.getApplicationInfo(currentPkg, 0)
                            pm.getApplicationLabel(ai).toString()
                        } catch (_: Exception) {
                            currentPkg
                        }
                        val icon = item?.icon ?: try {
                            val ai = pm.getApplicationInfo(currentPkg, 0)
                            pm.getApplicationIcon(ai)
                        } catch (_: Exception) {
                            null
                        }
                        val uid = try {
                            pm.getApplicationInfo(currentPkg, 0).uid
                        } catch (_: Exception) {
                            10000
                        }
                        appEvents.add(
                            AppTimelineEvent(
                                packageName = currentPkg,
                                uid = uid,
                                appName = appName,
                                icon = icon,
                                startTime = fallbackStart,
                                endTime = endTs,
                                durationMs = endTs - fallbackStart,
                                screenOn = true,
                                energyMwh = null,
                                averagePowerMw = fullPackage.overviewStats.avgPowerWatts * 1000.0,
                                peakPowerMw = fullPackage.overviewStats.avgPowerWatts * 1000.0,
                                cpuTimeMs = 0L,
                                networkBytes = 0L,
                                wakelockTimeMs = 0L,
                                gpsTimeMs = 0L,
                                confidence = ConfidenceLevel.HIGH,
                                source = EnergySource.BATTERY_STATS
                            )
                        )
                    }
                }
            }
        }

        return BatteryTimelineState(
            startTimestamp = startTs,
            endTimestamp = endTs,
            visibleStartTimestamp = startTs,
            visibleEndTimestamp = endTs,
            zoomScale = 1.0f,
            scrollOffset = 0.0f,
            screenEvents = screenEvents,
            appEvents = appEvents,
            batterySamples = samples,
            selectedMetric = metric
        )
    }

    /**
     * 基于时序物理放电采样点切片微积分设备在指定时间区间内的真实消耗能量（单位：瓦时 Wh）与平均功率（单位：瓦特 W）。
     * 遵循物理数值梯形微积分模型：E = ∫ P(t) dt ≈ ∑ [ (P_i + P_{i-1}) / 2 * Δt_i ]，
     * 严格对齐 BatteryRecorder 与真实电池库仑计硬件积分逻辑。
     *
     * @param samples 物理放电时序采样点列表
     * @param filterScreenOn 过滤亮灭屏条件，null 表示全部，true 仅亮屏，false 仅息屏
     * @return 包含真实积分能量（Wh）与平均功耗（W）的键值对 [Pair<Float, Float>]
     */
    fun calculatePhysicalIntegratedEnergyAndPower(
        samples: List<PowerDischargePoint>,
        filterScreenOn: Boolean? = null
    ): Pair<Float, Float> {
        if (samples.size < 2) {
            val single = samples.firstOrNull()
            return if (single != null && (filterScreenOn == null || single.isScreenOn == filterScreenOn)) {
                Pair(0f, single.powerWatts)
            } else {
                Pair(0f, 0f)
            }
        }

        var totalEnergyWh = 0.0
        var totalDurationMs = 0L

        for (i in 1 until samples.size) {
            val p0 = samples[i - 1]
            val p1 = samples[i]
            val dtMs = (p1.timestamp - p0.timestamp).coerceAtLeast(0L)
            // 连续性硬约束：仅对连续采样周期（<= 2分钟）内的有效切片进行梯形数值微积分，
            // 严禁将系统休眠断层（数十分钟至两小时未知黑盒）直接按唤醒瞬间功率线性插值，杜绝息屏功耗虚高
            if (dtMs in 1L..MAX_INTEGRATION_INTERVAL_MS) {
                val match = when (filterScreenOn) {
                    null  -> true
                    true  -> p0.isScreenOn && p1.isScreenOn
                    false -> !p0.isScreenOn && !p1.isScreenOn
                }
                if (match) {
                    val avgWatts = ((p0.powerWatts + p1.powerWatts) / 2.0).coerceAtLeast(0.0)
                    val dtHours = dtMs / 3600000.0
                    totalEnergyWh += avgWatts * dtHours
                    totalDurationMs += dtMs
                }
            }
        }

        val totalHours = totalDurationMs / 3600000.0
        val finalPower = if (totalHours > 0.0) (totalEnergyWh / totalHours).toFloat() else 0f
        return Pair(totalEnergyWh.toFloat(), finalPower)
    }
}

/**
 * 实时电池状态快照数据类。
 *
 * @property levelPercent 电量百分比
 * @property voltageVolts 电压
 * @property temperature 温度
 * @property energyWh 能量
 * @property isCharging 充电状态
 * @property totalEnergyWh 电池总能量（单位：Wh，若无法获取真实基准容量则为 null）
 */
data class BatteryStatusSnapshot(
    val levelPercent: Int,
    val voltageVolts: Float,
    val temperature: Float,
    val energyWh: Float,
    val isCharging: Boolean,
    val totalEnergyWh: Float? = null
)

/**
 * 功耗指标概览数据类（支持功耗、时间、续航三大卡片的四行精准数值呈现，包含后台功耗与各阶段能量）。
 *
 * @property avgPowerWatts 综合平均放电功耗（单位：W）
 * @property screenOnPowerWatts 亮屏平均放电功耗（单位：W）
 * @property screenOffPowerWatts 息屏待机放电功耗（单位：W）
 * @property backgroundPowerWatts 后台运行平均放电功耗（单位：W）
 * @property screenOnDurationText 亮屏持续时长文本（如 "5h5m"）
 * @property screenOffDurationText 息屏待机时长文本（如 "8h39m"）
 * @property totalDurationText 放电总计耗时文本（如 "13h44m"）
 * @property backgroundDurationText 后台运行总时长文本（如 "8h39m"）
 * @property remainingScreenOnText 持续亮屏理论续航（如 "6h30m"）
 * @property remainingCompositeText 综合混合理论续航（如 "14h46m"）
 * @property remainingScreenOffText 纯息屏待机理论续航（如 "2d18h"）
 * @property remainingBackgroundText 纯后台待机理论续航（如 "3d12h"）
 * @property screenOnEnergyWh 亮屏期间消耗总能量（单位：瓦时 Wh）
 * @property totalEnergyWh 全局放电消耗总能量（单位：瓦时 Wh）
 * @property screenOffEnergyWh 息屏期间消耗总能量（单位：瓦时 Wh）
 * @property backgroundEnergyWh 后台运行期间消耗总能量（单位：瓦时 Wh）
 * @property usedDurationText 兼容保留字段
 * @property remainingLifeText 兼容保留字段
 */
data class PowerOverviewStats(
    val avgPowerWatts: Float,
    val screenOnPowerWatts: Float = 1.65f,
    val screenOffPowerWatts: Float = 0.15f,
    val backgroundPowerWatts: Float = 0f,
    val screenOnDurationText: String = "",
    val screenOffDurationText: String = "",
    val totalDurationText: String = "",
    val backgroundDurationText: String = "",
    val remainingScreenOnText: String = "",
    val remainingCompositeText: String = "",
    val remainingScreenOffText: String = "",
    val remainingBackgroundText: String = "",
    val screenOnEnergyWh: Float = 0f,
    val totalEnergyWh: Float = 0f,
    val screenOffEnergyWh: Float = 0f,
    val backgroundEnergyWh: Float = 0f,
    val usedDurationText: String = "",
    val remainingLifeText: String = ""
)

/**
 * 完整耗电统计数据包。
 *
 * @property batterySnapshot 电池基本参数
 * @property overviewStats 核心指标
 * @property appList 各应用耗电数据
 * @property trendPoints 放电折线图点集
 * @property isShizukuRealData 是否为 Shizuku 真实解析数据
 * @property startLevelPercent 开始记录放电时的电量百分比（默认 100）
 */
data class FullPowerDataPackage(
    val batterySnapshot: BatteryStatusSnapshot,
    val overviewStats: PowerOverviewStats,
    val appList: List<AppPowerUsageItem>,
    val trendPoints: List<PowerDischargePoint>,
    val isShizukuRealData: Boolean,
    val startLevelPercent: Int = 100
)

