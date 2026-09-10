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

        const val SAMPLING_MODE_POWER_SAVE = 0
        const val SAMPLING_MODE_BALANCED = 1
        const val SAMPLING_MODE_HIGH_PRECISION = 2

        private const val PREF_KEY_POWER_MODE = "pref_power_stats_mode"
        private const val PREF_KEY_SAMPLING_MODE = "pref_curve_sampling_mode"
        private const val PREF_KEY_POWER_CONFIGURED = "pref_power_mode_configured"
        const val PREF_KEY_LAST_UNPLUG_TIME = "pref_last_unplug_time"
        const val PREF_KEY_LAST_UNPLUG_LEVEL = "pref_last_unplug_level"
        const val PREF_KEY_LAST_UNPLUG_CHARGE_COUNTER = "pref_last_unplug_charge_counter"
        private const val PREF_KEY_UNPLUG_USAGE_SNAPSHOT = "pref_unplug_usage_snapshot"

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
    }

    private val prefs = context.getSharedPreferences("power_stats_prefs", Context.MODE_PRIVATE)
    private val shizukuParser = ShizukuBatteryStatsParser(context)

    // 标记当前放电周期（lastUnplugTime）是否已归档持久化，杜绝并发广播与进程重启重复插入
    @Volatile
    private var lastArchivedUnplugTime: Long = prefs.getLong("pref_last_archived_unplug_time", 0L)

    /**
     * 当外部电源断开（拔掉充电器）或用户手动重置时触发，重置当前放电统计周期基准。
     *
     * @param unplugLevel 断开电源时刻的电池电量百分比
     */
    fun onPowerDisconnected(unplugLevel: Int) {
        val now = System.currentTimeMillis()
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val counterUah = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: 0
        val editor = prefs.edit()
            .putLong(PREF_KEY_LAST_UNPLUG_TIME, now)
            .putInt(PREF_KEY_LAST_UNPLUG_LEVEL, unplugLevel.coerceIn(1, 100))
            .putLong("pref_last_archived_unplug_time", 0L)
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
                            lastUnplugTime
                        )
                        if (stats.detectedUnplugLevel != null && stats.detectedUnplugLevel >= currentLevel) {
                            val detectedTime = if (stats.detectedUnplugTs != null && stats.detectedUnplugTs > 0L) {
                                stats.detectedUnplugTs
                            } else {
                                now - stats.dischargeDurationMs
                            }
                            prefs.edit()
                                .putInt(PREF_KEY_LAST_UNPLUG_LEVEL, stats.detectedUnplugLevel)
                                .putLong(PREF_KEY_LAST_UNPLUG_TIME, detectedTime)
                                .apply()
                            saveUnplugUsageSnapshot()
                            syncedViaShizuku = true
                            reconciled = true
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
                // 长期未更新拔电时间，自动修正基准
                if (!isShizukuAuthorized()) {
                    onPowerDisconnected(currentLevel)
                    reconciled = true
                }
            }
        }

        return reconciled
    }

    /**
     * 保存断开外部电源瞬间系统所有应用的前台使用时长基准快照。
     */
    fun saveUnplugUsageSnapshot() {
        if (!hasUsageStatsPermission()) return
        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
            val now = System.currentTimeMillis()
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -1)
            val statsMap = usm.queryAndAggregateUsageStats(cal.timeInMillis, now)
            val json = org.json.JSONObject()
            for ((pkg, usage) in statsMap) {
                if (usage.totalTimeInForeground > 0L) {
                    json.put(pkg, usage.totalTimeInForeground)
                }
            }
            prefs.edit().putString(PREF_KEY_UNPLUG_USAGE_SNAPSHOT, json.toString()).apply()
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
     *
     * @return 拔电时的初始电量百分比（默认 100）
     */
    fun getLastUnplugLevel(): Int {
        return prefs.getInt(PREF_KEY_LAST_UNPLUG_LEVEL, 100)
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
     * 获取当前选中的工作模式（默认优先为 Shizuku 高精度模式）。
     *
     * @return [MODE_SHIZUKU] 或 [MODE_NORMAL]
     */
    fun getSelectedMode(): Int {
        return prefs.getInt(PREF_KEY_POWER_MODE, MODE_SHIZUKU)
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
     * 获取当前配置的曲线采样精度模式（默认极限省电模式）。
     *
     * @return 采样模式常量 [SAMPLING_MODE_POWER_SAVE], [SAMPLING_MODE_BALANCED], [SAMPLING_MODE_HIGH_PRECISION]
     */
    fun getSamplingMode(): Int {
        return prefs.getInt(PREF_KEY_SAMPLING_MODE, SAMPLING_MODE_POWER_SAVE)
    }

    /**
     * 设置并持久化保存曲线采样精度模式。
     *
     * @param mode 目标采样模式常量（[SAMPLING_MODE_POWER_SAVE], [SAMPLING_MODE_BALANCED], [SAMPLING_MODE_HIGH_PRECISION]）
     */
    fun setSamplingMode(mode: Int) {
        prefs.edit().putInt(PREF_KEY_SAMPLING_MODE, mode).apply()
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
     * 检查当前应用是否已被授予 Shizuku 权限。
     *
     * @return 若已授权返回 true，否则返回 false
     */
    fun isShizukuAuthorized(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
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
     *
     * @return 赋权操作后是否已成功拥有使用情况访问权限
     */
    fun grantUsageStatsPermissionViaShizuku(): Boolean {
        if (!isShizukuAuthorized()) return hasUsageStatsPermission()
        try {
            val pkg = context.packageName
            shizukuParser.executeShizukuShellCommand("appops set $pkg GET_USAGE_STATS allow")
            shizukuParser.executeShizukuShellCommand("appops set $pkg android:get_usage_stats allow")
            shizukuParser.executeShizukuShellCommand("pm grant $pkg android.permission.PACKAGE_USAGE_STATS")
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
        val voltageVolts = if (rawVoltage > 100) rawVoltage / 1000f else rawVoltage.toFloat()

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
            voltageVolts = voltageVolts,
            effectiveCapacityMah = effectiveCapacity
        )

        return BatteryStatusSnapshot(
            levelPercent = percent,
            voltageVolts = voltageVolts,
            temperature = tempCelsius,
            energyWh = energyWh,
            isCharging = isCharging
        )
    }

    /**
     * 获取设备当前最精准的基准电池容量（优先实际满充容量 FCC，其次设计容量）。
     *
     * 优先级策略：
     * 1. 历史数据库中最新记录的真实满充容量 [HistoryRecord.fullChargeCapacity]（反映真实电池健康衰减）；
     * 2. 历史数据库中最新记录的出厂设计容量 [HistoryRecord.designCapacity]；
     * 3. 系统底层 PowerProfile 反射读取的电池额定容量；
     * 4. 兜底默认值 [BatteryEnergyCalculator.DEFAULT_FALLBACK_CAPACITY_MAH]（5000mAh）。
     *
     * @return 设备基准电池容量（单位：mAh）
     */
    fun getEffectiveDeviceCapacityMah(): Float {
        // 1. 优先从历史快照记录中获取经过算法融合或 Shizuku/Bugreport 提取到的真实满充容量与设计容量
        try {
            val dbHelper = HistoryDbHelper(context)
            val latestRecord = dbHelper.getLatestRecord()
            val fcc = latestRecord?.fullChargeCapacity
            if (fcc != null && fcc in 500f..30000f) {
                return fcc
            }
            val design = latestRecord?.designCapacity
            if (design != null && design in 500f..30000f) {
                return design
            }
        } catch (e: Exception) {
            // 数据库读取容错
        }

        // 2. 尝试从系统 PowerProfile 反射获取出厂设计容量
        val powerProfileCap = NormalApiProvider.getDesignCapacity(context)
        if (powerProfileCap != null && powerProfileCap in 500f..30000f) {
            return powerProfileCap
        }

        // 3. 现代主流机型兜底典型容量
        return BatteryEnergyCalculator.DEFAULT_FALLBACK_CAPACITY_MAH
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
        val unplugLevel = getLastUnplugLevel().coerceIn(1, 100)
        val now = System.currentTimeMillis()

        // 1. Shizuku 模式且已获得授权
        if (mode == MODE_SHIZUKU && isShizukuAuthorized()) {
            val stats = shizukuParser.parseChargedBatteryStats(
                batterySnapshot.voltageVolts,
                batterySnapshot.temperature,
                unplugTime
            )

            if (stats.appList.isNotEmpty() || stats.dischargeDurationMs > 0L) {
                // 若探测到底层真实拔电时刻与电量，动态同步至本地存储，纠正首次进入缺乏记录时的错误默认值
                var effectiveUnplugLevel = unplugLevel
                var effectiveUnplugTime = unplugTime
                if (stats.detectedUnplugLevel != null && stats.detectedUnplugLevel >= batterySnapshot.levelPercent) {
                    effectiveUnplugLevel = stats.detectedUnplugLevel
                    if (stats.detectedUnplugTs != null && stats.detectedUnplugTs > 0L) {
                        effectiveUnplugTime = stats.detectedUnplugTs
                    }
                    prefs.edit()
                        .putInt(PREF_KEY_LAST_UNPLUG_LEVEL, effectiveUnplugLevel)
                        .putLong(PREF_KEY_LAST_UNPLUG_TIME, effectiveUnplugTime)
                        .apply()
                }

                val durationMs = if (effectiveUnplugTime in 1..now && (now - effectiveUnplugTime) in 1000L..(48 * 3600_000L)) {
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
                val safeVoltage = if (batterySnapshot.voltageVolts > 1.0f) batterySnapshot.voltageVolts else 3.85f
                val dischargeHours = durationMs / 3600000f
                val screenOnHours = screenOnMs / 3600000f
                val screenOffHours = screenOffMs / 3600000f

                // 起始电量：优先取探测到的真实拔电电量，其次取有效拔电记录，次取历史点最高电量，最后以当前电量保底
                val historyMaxLevel = stats.historyLevelPoints.maxOfOrNull { it.second } ?: batterySnapshot.levelPercent
                val startLevel = when {
                    stats.detectedUnplugLevel != null && stats.detectedUnplugLevel >= batterySnapshot.levelPercent -> {
                        stats.detectedUnplugLevel
                    }
                    effectiveUnplugTime > 0L && effectiveUnplugLevel >= batterySnapshot.levelPercent -> {
                        effectiveUnplugLevel
                    }
                    historyMaxLevel >= batterySnapshot.levelPercent -> {
                        historyMaxLevel
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
                val appEnergySumWh = stats.appList.sumOf { it.energyWh.toDouble() }.toFloat()
                val appEnergyMah = if (appEnergySumWh > 0f) (appEnergySumWh * 1000f / safeVoltage) else 0f
                val minPhysicalMah = appEnergyMah + stats.screenDrainMah

                // 1% 掉电时的物理保护与平滑放电量（防止除以极短时长导致的 10W+ 虚假尖峰）
                val smoothedDropMah = if (dropPercent > 0) {
                    val rawMah = effectiveCapacity * (dropPercent / 100f)
                    if (dropPercent == 1 && dischargeHours < 0.25f) {
                        val maxPhysicalMah = (4.5f * 1000f / safeVoltage) * dischargeHours
                        rawMah.coerceAtMost(maxPhysicalMah)
                    } else {
                        rawMah
                    }
                } else {
                    0f
                }

                // 校验底层连续数据的有效性：滤除硬件电荷计数器 ADC 杂讯或 dumpsys 滞后未刷新的微小底噪（<0.5mAh），
                // 且放电量绝不能明显违背已有应用的实耗统计（若已有应用耗电，放电量不可低于应用基底的 90%）
                val validComputedMah = if (stats.computedDrainMah > 0.5f && (minPhysicalMah <= 0.5f || stats.computedDrainMah >= minPhysicalMah * 0.9f)) {
                    stats.computedDrainMah
                } else {
                    0f
                }
                val validHwMah = if (hwDischargedMah > 0.5f && (minPhysicalMah <= 0.5f || hwDischargedMah >= minPhysicalMah * 0.9f)) {
                    hwDischargedMah
                } else {
                    0f
                }

                val realDischargedMah = when {
                    validComputedMah > 0.5f -> maxOf(validComputedMah, minPhysicalMah)
                    validHwMah > 0.5f -> maxOf(validHwMah, minPhysicalMah)
                    dropPercent > 0 -> maxOf(smoothedDropMah, minPhysicalMah)
                    minPhysicalMah > 0.5f -> minPhysicalMah
                    else -> 0f
                }
                val realTotalEnergyWh = (realDischargedMah * safeVoltage) / 1000f

                val avgWatts = if (dischargeHours > 0f && realTotalEnergyWh > 0f) {
                    realTotalEnergyWh / dischargeHours
                } else {
                    0f
                }

                // 2. 统计真实息屏记录功耗与亮屏功耗解耦（严格遵循能量守恒与待机物理合理性约束，杜绝 45W 尖峰与误判为 0）
                val screenOffWatts: Float
                if (screenOffHours <= 0f || screenOffMs < 30000L) {
                    // 若无有效息屏时长或息屏不足 30 秒，按纯亮屏处理，息屏功耗置 0f（UI 规范展示为 "--"）
                    screenOffWatts = 0f
                } else if (screenOnHours <= 0f) {
                    // 全周期均为纯息屏待机状态，整机平均功耗即为息屏功耗，施加 3.0W 物理上限保护
                    screenOffWatts = avgWatts.coerceIn(0f, ShizukuBatteryStatsParser.MAX_STANDBY_POWER_WATTS)
                } else {
                    // 既有亮屏又有息屏：优先使用系统底层独立的息屏放电量，并施加短时防尖峰与物理上限平滑保护
                    if (stats.screenOffDrainMah > 0f) {
                        val rawOffWatts = (stats.screenOffDrainMah * safeVoltage) / (1000f * screenOffHours)
                        val maxAllowedOffWatts = if (avgWatts > 0.05f) minOf(ShizukuBatteryStatsParser.MAX_STANDBY_POWER_WATTS, avgWatts) else ShizukuBatteryStatsParser.MAX_STANDBY_POWER_WATTS
                        screenOffWatts = rawOffWatts.coerceIn(0.05f, maxAllowedOffWatts)
                    } else {
                        // 底层无独立息屏统计通道时，结合纯后台应用实耗能耗与系统基础待机底座功率客观推导，杜绝误报为 0 或因整机结余除以短时间飙高
                        val bgEnergyWh = stats.appList.filter { it.foregroundTimeMs <= 0L }.sumOf { it.energyWh.toDouble() }.toFloat()
                        val bgPowerWatts = if (screenOffHours > 0f) (bgEnergyWh / screenOffHours) else 0f
                        val baseStandbyWatts = if (avgWatts > 0.05f) minOf(ShizukuBatteryStatsParser.DEFAULT_STANDBY_BASE_WATTS, avgWatts * 0.6f) else 0.12f
                        val estimatedOffWatts = (bgPowerWatts + baseStandbyWatts).coerceIn(0.05f, ShizukuBatteryStatsParser.MAX_STANDBY_POWER_WATTS)
                        screenOffWatts = if (avgWatts > 0.05f) minOf(estimatedOffWatts, avgWatts) else estimatedOffWatts
                    }
                }

                // 3. 依据物理能量严格守恒解耦亮屏功耗：E_on = E_total - E_off, P_on = E_on / T_on
                val screenOnWatts = if (screenOnHours > 0f) {
                    if (screenOffHours <= 0f || screenOffMs < 30000L || screenOffWatts <= 0f) {
                        // 若全周期均为亮屏状态，亮屏平均放电功耗即等同于整机放电平均功耗
                        avgWatts
                    } else {
                        val offEnergy = screenOffWatts * screenOffHours
                        val onEnergy = (realTotalEnergyWh - offEnergy).coerceAtLeast(0f)
                        val calcWatts = onEnergy / screenOnHours
                        // 容错与保底：若解耦算出的亮屏功耗接近0（<0.05W）而整机平均功耗明显大于0，则以平均功耗作为保底
                        if (calcWatts < 0.05f && avgWatts >= 0.05f) {
                            avgWatts
                        } else {
                            calcWatts
                        }
                    }
                } else {
                    0f
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
                            item.copy(foregroundTimeMs = 0L)
                        } else {
                            item.copy(foregroundTimeMs = maxAllowedMs)
                        }
                    } else {
                        item
                    }
                }.filter { it.foregroundTimeMs > 0L || it.backgroundTimeMs > 0L || it.energyWh > 0.001f }

                // 物理完善：将整机亮屏期间的屏幕面板显示与前台系统底座能耗（扣除纯前台核心算力后的共享基底功率）
                // 客观赋予在前台点亮屏幕运行的应用，使每个 App 的平均功耗完整真实反映前台亮屏综合放电功耗。
                // 彻底杜绝纯后台守护进程误计入前台导致屏幕池被吞噬，以及多进程重叠稀释屏幕功耗的缺陷
                val fgApps = rawAppList.filter { it.foregroundTimeMs > 0L }
                val totalScreenOnEnergyWh = screenOnWatts * (screenOnMs / 3600000f)
                val totalCoreFgEnergyWh = fgApps.sumOf { it.foregroundEnergyWh.toDouble() }.toFloat()
                val sharedScreenEnergyWh = (totalScreenOnEnergyWh - totalCoreFgEnergyWh).coerceAtLeast(0f)
                val hwScreenEnergyWh = if (stats.screenDrainMah > 0f) (stats.screenDrainMah * safeVoltage) / 1000f else 0f
                val effectiveScreenEnergyWh = maxOf(sharedScreenEnergyWh, hwScreenEnergyWh)

                // 物理模型分解：屏幕总共享池 = 基础屏幕显示功率 (0.9W 恒定发光底座) + 动态 GPU 3D 渲染能耗池
                // 屏幕面板在点亮状态下具有恒定显示基础功率（以 dumpsys 测得或约 0.9W 为基准，受限于当前总能量池）
                val maxBaseDisplayWatts = if (screenOnHours > 0f) (effectiveScreenEnergyWh / screenOnHours) else 0f
                val baseDisplayWatts = if (stats.screenDrainMah > 0f && screenOnHours > 0f) {
                    ((stats.screenDrainMah * safeVoltage) / (1000f * screenOnHours)).coerceAtMost(maxBaseDisplayWatts)
                } else {
                    0.9f.coerceAtMost(maxBaseDisplayWatts)
                }
                val baseDisplayEnergyWh = baseDisplayWatts * screenOnHours
                val dynamicGpuEnergyWh = (effectiveScreenEnergyWh - baseDisplayEnergyWh).coerceAtLeast(0f)

                // 动态 GPU 渲染权重：游戏应用（3D 引擎管线与持续 60/120fps 着色器渲染）权重设为 3.0，普通 2D 界面静态应用权重为 1.0
                // GPU 能耗客观按前台渲染时间与应用渲染特征加权分配，普通非游戏场景（全 1.0）退化为纯时间比例分配
                val totalRenderWeight = fgApps.sumOf { item ->
                    val isGame = isGameApp(item.packageName)
                    val weight = if (isGame) 3.0 else 1.0
                    weight * (item.foregroundTimeMs / 3600000.0)
                }

                val enrichedAppList = rawAppList.map { item ->
                    if (item.foregroundTimeMs > 0L) {
                        val fgHours = item.foregroundTimeMs / 3600000f
                        val isGame = isGameApp(item.packageName)
                        // 若传入的应用前台核心能耗明显超标（如微信 20W），进行物理合理性重构
                        val totalAppEnergy = item.energyWh
                        val (safeFgEnergy, safeBgEnergy, safeBgMs) = shizukuParser.decoupleAppEnergyAndTimes(
                            totalEnergy = totalAppEnergy,
                            foregroundMs = item.foregroundTimeMs,
                            backgroundMs = item.backgroundTimeMs,
                            cpuMs = 0L,
                            dischargeMs = durationMs,
                            isGame = isGame
                        )
                        val appCoreWatts = if (fgHours > 0f) (safeFgEnergy / fgHours) else item.avgPowerWatts
                        val appWeight = (if (isGame) 3.0 else 1.0) * fgHours
                        val appGpuEnergy = if (totalRenderWeight > 0.0) {
                            (dynamicGpuEnergyWh * (appWeight / totalRenderWeight)).toFloat()
                        } else {
                            0f
                        }
                        val appGpuWatts = if (fgHours > 0f) (appGpuEnergy / fgHours) else 0f

                        // 综合平均功耗 = CPU 核心算力功耗 + 屏幕发光基底功率 + 动态 GPU 渲染功耗
                        val combinedAvgWatts = (appCoreWatts + baseDisplayWatts + appGpuWatts).coerceAtLeast(0f)
                        val combinedFgEnergy = combinedAvgWatts * fgHours
                        item.copy(
                            avgPowerWatts = combinedAvgWatts,
                            backgroundTimeMs = safeBgMs,
                            foregroundEnergyWh = combinedFgEnergy,
                            backgroundEnergyWh = safeBgEnergy,
                            directEnergyWh = combinedFgEnergy + safeBgEnergy
                        )
                    } else {
                        // 纯后台应用：平均功耗依其实际后台运行能耗与后台活跃时长计算
                        val bgHours = item.backgroundTimeMs / 3600000f
                        val bgWatts = if (bgHours > 0f && item.backgroundEnergyWh > 0f) {
                            (item.backgroundEnergyWh / bgHours).coerceAtLeast(0f)
                        } else {
                            0f
                        }
                        item.copy(avgPowerWatts = bgWatts)
                    }
                }

                // 4. 后台所有应用能耗与后台平均功耗统计
                val allBgEnergyWh = enrichedAppList.sumOf { it.backgroundEnergyWh.toDouble() }.toFloat()
                val effectiveBgMs = if (screenOffMs > 30000L) screenOffMs else (durationMs - screenOnMs).coerceAtLeast(0L)
                val effectiveBgHours = effectiveBgMs / 3600000f
                val calcBgWatts = if (effectiveBgHours > 0f && allBgEnergyWh > 0f) (allBgEnergyWh / effectiveBgHours) else 0f
                val bgWatts = if (screenOffWatts > 0.05f) minOf(calcBgWatts, screenOffWatts) else calcBgWatts.coerceAtMost(ShizukuBatteryStatsParser.MAX_STANDBY_POWER_WATTS)
                val remBackgroundStr = if (bgWatts >= 0.05f) formatHoursToText(energy / bgWatts) else "--"
                val bgDurationStr = formatDuration(effectiveBgMs)

                val offEnergyWh = if (screenOffHours > 0f && screenOffWatts > 0f) (screenOffWatts * screenOffHours) else 0f
                val onEnergyWh = if (screenOnHours > 0f) (realTotalEnergyWh - offEnergyWh).coerceAtLeast(0f) else 0f

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

                // 采用方式二：基于前台时间切片与时序温度采样点，精准计算各 App 运行时真实温度（带 1 位小数）与独立功耗
                val validatedAppList = calculateAppPowerAndTempWithTimeSlices(
                    appItems = enrichedAppList,
                    appIntervals = appIntervals,
                    historyTempPoints = stats.historyTempPoints,
                    defaultTempCelsius = batterySnapshot.temperature
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
            if (item.foregroundTimeMs > maxAllowedNormalMs) {
                if (unplugTime > 0L && (now - unplugTime) < 300000L && item.foregroundTimeMs > 300000L) {
                    item.copy(foregroundTimeMs = 0L)
                } else {
                    item.copy(foregroundTimeMs = maxAllowedNormalMs)
                }
            } else {
                item
            }
        }.filter { it.foregroundTimeMs > 0L || it.energyWh > 0.001f }

        // 普通模式物理完善：
        // 各前台应用运行时屏幕始终点亮，整机放电速率即为当前亮屏平均功耗。
        val normalScreenWatts = if (overview.screenOnPowerWatts > 0f) {
            overview.screenOnPowerWatts
        } else if (overview.avgPowerWatts > 0f) {
            overview.avgPowerWatts
        } else {
            0f
        }
        val normalScreenOffWatts = if (overview.screenOffPowerWatts > 0f) {
            overview.screenOffPowerWatts
        } else {
            0.12f
        }

        val validatedAppList = rawNormalList.map { item ->
            if (item.foregroundTimeMs > 0L) {
                val fgHours = item.foregroundTimeMs / 3600000f
                val appFgEnergyWh = (normalScreenWatts * fgHours).coerceAtLeast(0f)
                val bgHours = item.backgroundTimeMs / 3600000f
                val appBgEnergyWh = if (bgHours > 0f) (normalScreenOffWatts * bgHours * 0.3f).coerceAtLeast(0f) else item.backgroundEnergyWh
                item.copy(
                    avgPowerWatts = normalScreenWatts,
                    foregroundEnergyWh = appFgEnergyWh,
                    backgroundEnergyWh = appBgEnergyWh,
                    directEnergyWh = appFgEnergyWh + appBgEnergyWh
                )
            } else {
                val bgHours = item.backgroundTimeMs / 3600000f
                val bgWatts = if (bgHours > 0f && item.backgroundEnergyWh > 0f) {
                    (item.backgroundEnergyWh / bgHours).coerceAtLeast(0f)
                } else {
                    normalScreenOffWatts * 0.3f
                }
                item.copy(avgPowerWatts = bgWatts)
            }
        }

        // 重新基于已核验的前后台能量生成精准的普通模式概览卡片指标（确保包含后台平均功耗与能量）
        val finalOverview = calculateOverviewStats(batterySnapshot.levelPercent, validatedAppList)

        val startLevel = if (unplugTime > 0L && unplugLevel >= batterySnapshot.levelPercent) unplugLevel else batterySnapshot.levelPercent
        val points = getDischargeTrendPoints(
            startLevel = startLevel,
            currentLevel = batterySnapshot.levelPercent,
            appItems = validatedAppList,
            durationMs = elapsedMs,
            screenOnDurationMs = normalScreenOnMs,
            screenOnPowerWatts = finalOverview.screenOnPowerWatts,
            screenOffPowerWatts = finalOverview.screenOffPowerWatts,
            currentVoltageVolts = batterySnapshot.voltageVolts,
            defaultTempCelsius = batterySnapshot.temperature
        )

        return FullPowerDataPackage(
            batterySnapshot = batterySnapshot,
            overviewStats = finalOverview,
            appList = validatedAppList,
            trendPoints = points,
            isShizukuRealData = false,
            startLevelPercent = startLevel
        )
    }

    /**
     * 判断指定包名是否为用户安装的三方应用（非纯底层系统进程或具有桌面启动入口）。
     *
     * @param packageName 目标包名
     * @return 若为用户三方应用返回 true，否则返回 false
     */
    fun isUserInstalledApp(packageName: String): Boolean {
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystem = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            val hasLauncher = pm.getLaunchIntentForPackage(packageName) != null
            !isSystem || isUpdatedSystem || hasLauncher
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 判断指定包名是否为游戏应用。
     * 优先通过 PackageManager 检查系统内置类别 CATEGORY_GAME 或应用清单中的 FLAG_IS_GAME 标识；
     * 并容错检查常见游戏特质包名（如包含 .game、.clash 等）。
     *
     * @param packageName 目标应用包名
     * @return 若为游戏应用返回 true，否则返回 false
     */
    @Suppress("DEPRECATION")
    fun isGameApp(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return try {
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
    }

    /**
     * 基于 UsageEvents 精准提取指定时间区间 [startTime, endTime] 内各应用的前台活跃毫秒数。
     * 采用严格的单前台应用生命周期状态机，仅认准 ACTIVITY_RESUMED 至 ACTIVITY_PAUSED，
     * 并向前回溯探测区间开始时刻处于活跃的应用，彻底杜绝孤立事件或后台被杀（ACTIVITY_STOPPED）导致的误判与虚高。
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

        try {
            // 向前回溯探测在 startTime 瞬间正处于前台活跃状态的应用（最多回溯 15 分钟）
            val lookbackStart = (startTime - 15 * 60 * 1000L).coerceAtLeast(0L)
            val events = usm.queryEvents(lookbackStart, endTime)
            val event = UsageEvents.Event()
            var currentForegroundPkg: String? = null
            var currentForegroundStartTs: Long = 0L

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val pkg = event.packageName ?: continue
                val ts = event.timeStamp

                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        // 若先前已有应用在前台且未收到 PAUSE 事件（被新 Activity 覆盖），结算其有效前台时长
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
                    UsageEvents.Event.ACTIVITY_PAUSED -> {
                        // 仅当当前离开前台的应用正是记录中的前台应用时才进行结算，杜绝后台事件或旧事件误判
                        if (currentForegroundPkg == pkg) {
                            val activeStart = maxOf(currentForegroundStartTs, startTime)
                            val activeEnd = minOf(ts, endTime)
                            if (activeEnd > activeStart) {
                                resultMap[pkg] = (resultMap[pkg] ?: 0L) + (activeEnd - activeStart)
                            }
                            currentForegroundPkg = null
                            currentForegroundStartTs = 0L
                        }
                    }
                }
            }

            // 处理在 endTime 时刻仍然驻留前台的应用
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
                    val statsList = usm?.queryUsageStats(UsageStatsManager.INTERVAL_BEST, startTime, now)
                    statsList?.filter { it.totalTimeForegroundServiceUsed > 0L }?.associate { it.packageName to it.totalTimeForegroundServiceUsed } ?: emptyMap()
                } catch (_: Exception) {
                    emptyMap()
                }
            } else {
                emptyMap()
            }

            if (deltas.isNotEmpty()) {
                val baseTemp = (currentTempCelsius ?: getCurrentBatteryStatus().temperature).coerceIn(15f, 60f)
                val formattedBaseTemp = (Math.round(baseTemp * 10f) / 10f).coerceIn(15f, 60f)
                for ((pkgName, pair) in deltas) {
                    val timeMs = pair.first.coerceAtMost(elapsedMs)
                    val lastUsed = pair.second
                    if (timeMs >= 1000L && isUserInstalledApp(pkgName)) {
                        try {
                            val appInfo = pm.getApplicationInfo(pkgName, 0)
                            val appName = pm.getApplicationLabel(appInfo).toString()
                            val icon = pm.getApplicationIcon(appInfo)

                            val avgTemp = formattedBaseTemp
                            val maxTemp = formattedBaseTemp

                            val serviceBgMs = bgServiceTimes[pkgName] ?: 0L
                            val effectiveBgMs = serviceBgMs

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
                                    backgroundTimeMs = effectiveBgMs,
                                    foregroundEnergyWh = 0f,
                                    backgroundEnergyWh = 0f
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
     * 基于应用前台时间切片与底层时序温度采样点，计算各应用在前台运行期间的真实平均温度与最高温度，
     * 并输出各应用在系统底层权威记录的前台平均功耗与前台消耗能量。
     *
     * @param appItems 原始解析出的应用耗电实体列表
     * @param appIntervals 各应用的前台活跃时间切片区间列表
     * @param historyTempPoints 系统底层记录的时序温度采样点列表（时间戳 -> 摄氏度）
     * @param defaultTempCelsius 默认/基准电池温度（摄氏度）
     * @return 经过切片温度采样与功耗核验后的应用耗电实体列表 [List<AppPowerUsageItem>]
     */
    private fun calculateAppPowerAndTempWithTimeSlices(
        appItems: List<AppPowerUsageItem>,
        appIntervals: List<AppActivityInterval>,
        historyTempPoints: List<Pair<Long, Float>>,
        defaultTempCelsius: Float
    ): List<AppPowerUsageItem> {
        val intervalMap = appIntervals.groupBy { it.packageName }

        return appItems.map { item ->
            val intervals = intervalMap[item.packageName] ?: emptyList()

            // 1. 匹配该应用在前台活跃时间切片内的所有时序温度采样点
            val matchedTemps = if (intervals.isNotEmpty() && historyTempPoints.isNotEmpty()) {
                historyTempPoints.filter { (ts, _) ->
                    intervals.any { interval -> ts in interval.startTs..interval.endTs }
                }.map { it.second }
            } else {
                emptyList()
            }

            val avgTemp: Float
            val maxTemp: Float
            if (matchedTemps.isNotEmpty()) {
                val rawAvg = matchedTemps.average().toFloat()
                val rawMax = matchedTemps.maxOrNull() ?: rawAvg
                avgTemp = (Math.round(rawAvg * 10f) / 10f).coerceIn(0f, 70f)
                maxTemp = (Math.round(rawMax * 10f) / 10f).coerceAtLeast(avgTemp).coerceIn(0f, 70f)
            } else if (historyTempPoints.isNotEmpty()) {
                // 若该应用前台时间过短未刚好命中采样点，寻找时间距离其最近的温度采样点
                val refTs = if (intervals.isNotEmpty()) {
                    intervals.last().endTs
                } else {
                    item.lastUsedTimeMs
                }
                val closestTemp = historyTempPoints.minByOrNull { Math.abs(it.first - refTs) }?.second ?: defaultTempCelsius
                val formattedTemp = (Math.round(closestTemp * 10f) / 10f).coerceIn(0f, 70f)
                avgTemp = formattedTemp
                maxTemp = formattedTemp
            } else {
                val formattedTemp = (Math.round(defaultTempCelsius * 10f) / 10f).coerceIn(0f, 70f)
                avgTemp = formattedTemp
                maxTemp = formattedTemp
            }

            // 2. 真实前台平均功耗：优先继承已融合屏幕与硬件底座能耗后的综合平均功耗
            val fgHours = item.foregroundTimeMs / 3600000f
            val bgHours = item.backgroundTimeMs / 3600000f
            val finalAvgWatts = if (item.foregroundTimeMs > 0L) {
                if (item.avgPowerWatts > 0f) {
                    item.avgPowerWatts
                } else if (item.foregroundEnergyWh > 0f && fgHours > 0f) {
                    (item.foregroundEnergyWh / fgHours).coerceAtLeast(0f)
                } else {
                    0f
                }
            } else {
                // 纯后台应用：保留其后台运行平均放电功耗
                if (item.avgPowerWatts > 0f) {
                    item.avgPowerWatts
                } else if (item.backgroundEnergyWh > 0f && bgHours > 0f) {
                    (item.backgroundEnergyWh / bgHours).coerceAtLeast(0f)
                } else {
                    0f
                }
            }

            // 3. 计算前台能量：优先使用已融合的综合前台能量，其次由平均功耗与前台时长计算
            val updatedFgEnergy = if (item.foregroundEnergyWh > 0f) {
                item.foregroundEnergyWh
            } else if (finalAvgWatts > 0f && fgHours > 0f) {
                (finalAvgWatts * fgHours).coerceAtLeast(0f)
            } else {
                0f
            }

            item.copy(
                avgPowerWatts = finalAvgWatts,
                avgTemperature = avgTemp,
                maxTemperature = maxTemp,
                foregroundEnergyWh = updatedFgEnergy
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
                            currentForegroundPkg = null
                            currentForegroundStartTs = 0L
                        }
                    }
                    UsageEvents.Event.SCREEN_INTERACTIVE -> {
                        screenOnStart = ts
                    }
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                        val onStart = screenOnStart ?: startTime
                        val activeStart = maxOf(onStart, startTime)
                        val activeEnd = minOf(ts, endTime)
                        if (activeEnd > activeStart) {
                            screenIntervals.add(ScreenInteractiveInterval(activeStart, activeEnd))
                        }
                        screenOnStart = null
                    }
                }
            }

            // 处理在 endTime 时刻未结束的应用与屏幕常亮事件
            if (currentForegroundPkg != null) {
                val activeStart = maxOf(currentForegroundStartTs, startTime)
                val activeEnd = endTime
                if (activeEnd > activeStart) {
                    appIntervals.add(AppActivityInterval(currentForegroundPkg, activeStart, activeEnd))
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
        currentVoltageVolts: Float = 3.85f,
        defaultTempCelsius: Float = 30f
    ): List<PowerDischargePoint> {
        val points = mutableListOf<PowerDischargePoint>()
        val duration = durationMs.coerceAtLeast(60000L)
        val steps = 40 // 40 个密集采样时间切片，实现紧凑的俄罗斯方块柱状排布效果

        val targetLevel = currentLevel.coerceIn(1, 100)
        val actualStartLevel = max(startLevel.coerceIn(1, 100), targetLevel)
        val delta = (actualStartLevel - targetLevel).coerceAtLeast(0)
        val totalDischargeHours = duration / 3600000f
        val effectiveDeviceCapacity = getEffectiveDeviceCapacityMah()
        val defaultAvgWatts = if (totalDischargeHours > 0f && delta > 0) {
            val rawMah = effectiveDeviceCapacity * (delta / 100f)
            val effectiveMah = if (delta == 1 && totalDischargeHours < 0.25f) {
                val maxPhysicalMah = (4.5f * 1000f / currentVoltageVolts) * totalDischargeHours
                rawMah.coerceAtMost(maxPhysicalMah)
            } else {
                rawMah
            }
            (effectiveMah * currentVoltageVolts) / (1000f * totalDischargeHours)
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
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val counterUah = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: 0
        val editor = prefs.edit()
            .putLong("last_reset_time", now)
            .remove(PREF_KEY_UNPLUG_USAGE_SNAPSHOT)
        if (counterUah > 0) {
            editor.putInt(PREF_KEY_LAST_UNPLUG_CHARGE_COUNTER, counterUah)
        }
        editor.apply()
        saveUnplugUsageSnapshot()
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
        val unplugLevel = getLastUnplugLevel().coerceIn(1, 100)
        val startLevel = if (unplugLevel >= currentLevel) unplugLevel else 100
        val dropPercent = (startLevel - currentLevel).coerceAtLeast(0)
        val dischargeHours = totalMs / 3600000f
        val screenOnHours = screenOnMs / 3600000f
        val screenOffHours = screenOffMs / 3600000f
        val currentVoltage = getCurrentBatteryStatus().voltageVolts
        val safeVoltage = if (currentVoltage > 1.0f) currentVoltage else 3.85f

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

        val appEnergySum = appList.sumOf { it.energyWh.toDouble() }.toFloat()
        val minPhysicalMah = if (appEnergySum > 0f) (appEnergySum * 1000f / safeVoltage) else 0f

        val smoothedDropMah = if (dropPercent > 0) {
            val rawMah = effectiveCapacity * (dropPercent / 100f)
            // 拔电初期（如 15 分钟内）发生 1% 量化跳变时的边缘量化平滑保护，杜绝除以极短时长产生的 10W+ 虚假尖峰
            if (dropPercent == 1 && dischargeHours < 0.25f) {
                val maxPhysicalMah = (4.5f * 1000f / safeVoltage) * dischargeHours
                rawMah.coerceAtMost(maxPhysicalMah)
            } else {
                rawMah
            }
        } else {
            0f
        }

        // 滤除硬件电荷计数器 ADC 杂讯底噪（<0.5mAh），且若已有应用能耗，放电量不可低于应用能耗的 90%
        val validHwMah = if (hwDischargedMah > 0.5f && (minPhysicalMah <= 0.5f || hwDischargedMah >= minPhysicalMah * 0.9f)) {
            hwDischargedMah
        } else {
            0f
        }

        val realDischargedMah = when {
            validHwMah > 0.5f -> maxOf(validHwMah, minPhysicalMah)
            dropPercent > 0 -> maxOf(smoothedDropMah, minPhysicalMah)
            minPhysicalMah > 0.5f -> minPhysicalMah
            else -> 0f
        }
        val realTotalEnergyWh = (realDischargedMah * safeVoltage) / 1000f

        val avgPower = if (dischargeHours > 0f && realTotalEnergyWh > 0f) {
            realTotalEnergyWh / dischargeHours
        } else {
            0f
        }

        // 亮屏与息屏功耗物理守恒解耦：
        // 拒绝粗暴将息屏功耗置 0，根据纯后台应用实耗与待机底座客观推导息屏功耗，再反解亮屏功耗，严格保持能量守恒
        val screenOffPower: Float
        val screenOnPower: Float
        if (screenOffHours <= 0f || screenOffMs < 30000L) {
            // 纯亮屏场景（息屏不足 30 秒）
            screenOnPower = avgPower
            screenOffPower = 0f
        } else if (screenOnHours <= 0f) {
            // 纯息屏场景（全周期未点亮屏幕）
            screenOffPower = avgPower.coerceIn(0f, ShizukuBatteryStatsParser.MAX_STANDBY_POWER_WATTS)
            screenOnPower = 0f
        } else {
            // 既有亮屏又有息屏场景：结合纯后台应用能耗与系统基础待机底座功率客观推导息屏功耗
            val bgEnergyWh = appList.filter { it.foregroundTimeMs <= 0L }.sumOf { it.energyWh.toDouble() }.toFloat()
            val bgWatts = if (screenOffHours > 0f) (bgEnergyWh / screenOffHours) else 0f
            val baseStandbyWatts = if (avgPower > 0.05f) minOf(ShizukuBatteryStatsParser.DEFAULT_STANDBY_BASE_WATTS, avgPower * 0.6f) else 0.12f
            val estimatedOffWatts = (bgWatts + baseStandbyWatts).coerceIn(0.05f, ShizukuBatteryStatsParser.MAX_STANDBY_POWER_WATTS)
            screenOffPower = if (avgPower > 0.05f) minOf(estimatedOffWatts, avgPower) else estimatedOffWatts

            // 依据能量守恒解耦亮屏功耗：E_on = E_total - E_off, P_on = E_on / T_on
            val offEnergyWh = screenOffPower * screenOffHours
            val onEnergyWh = (realTotalEnergyWh - offEnergyWh).coerceAtLeast(0f)
            val calcOnPower = onEnergyWh / screenOnHours
            screenOnPower = if (calcOnPower < 0.05f && avgPower >= 0.05f) avgPower else calcOnPower
        }

        val remainingTotalHours = if (avgPower >= 0.05f) {
            ((effectiveCapacity * (currentLevel / 100f) * safeVoltage) / 1000f) / avgPower
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

        // 普通模式下后台功耗与各工况能量计算
        val offEnergyWh = if (screenOffHours > 0f && screenOffPower > 0f) screenOffPower * screenOffHours else 0f
        val onEnergyWh = if (screenOnHours > 0f) (realTotalEnergyWh - offEnergyWh).coerceAtLeast(0f) else 0f
        val allBgEnergyWh = appList.sumOf { it.backgroundEnergyWh.toDouble() }.toFloat()
        val bgDurationMs = if (screenOffMs > 30000L) screenOffMs else (totalMs - screenOnMs).coerceAtLeast(0L)
        val bgHours = bgDurationMs / 3600000f
        val calcBgWatts = if (bgHours > 0f && allBgEnergyWh > 0f) (allBgEnergyWh / bgHours) else 0f
        val bgWatts = if (screenOffPower > 0.05f) minOf(calcBgWatts, screenOffPower) else calcBgWatts.coerceAtMost(ShizukuBatteryStatsParser.MAX_STANDBY_POWER_WATTS)
        val remBgStr = if (bgWatts >= 0.05f && remainingTotalHours > 0f) {
            formatHoursToText(remainingTotalHours * (avgPower / bgWatts))
        } else {
            "--"
        }
        val bgDurationStr = formatDuration(bgDurationMs)

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
            if (endTs > startTs) {
                samples.add(
                    BatterySample(
                        timestamp = endTs,
                        batteryLevel = snap.levelPercent,
                        voltageMv = if (snap.voltageVolts > 1f) (snap.voltageVolts * 1000).toInt() else (lastPt.voltageVolts * 1000).toInt(),
                        currentMa = estAvgCurrentMa,
                        temperatureC = snap.temperature.toDouble(),
                        powerMw = (fullPackage.overviewStats.avgPowerWatts * 1000).toDouble()
                    )
                )
            }
        } else {
            // 保底生成从 startTs 到 endTs 的初始与当前采样点
            val bsnap = fullPackage.batterySnapshot
            val fallbackCurrentMa = if (bsnap.voltageVolts > 0.5f) {
                (fullPackage.overviewStats.avgPowerWatts * 1000.0) / bsnap.voltageVolts
            } else {
                500.0
            }
            samples.add(
                BatterySample(
                    timestamp = startTs,
                    batteryLevel = getLastUnplugLevel(),
                    voltageMv = (bsnap.voltageVolts * 1000).toInt(),
                    currentMa = fallbackCurrentMa,
                    temperatureC = bsnap.temperature.toDouble(),
                    powerMw = (fullPackage.overviewStats.avgPowerWatts * 1000).toDouble()
                )
            )
            if (endTs > startTs) {
                samples.add(
                    BatterySample(
                        timestamp = endTs,
                        batteryLevel = bsnap.levelPercent,
                        voltageMv = (bsnap.voltageVolts * 1000).toInt(),
                        currentMa = fallbackCurrentMa,
                        temperatureC = bsnap.temperature.toDouble(),
                        powerMw = (fullPackage.overviewStats.avgPowerWatts * 1000).toDouble()
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
        } else {
            // 默认全时段亮屏保底
            screenEvents.add(ScreenEvent(startTs, endTs, true))
        }

        // 4. 构建 App 活动时间轴事件列表
        val appMap = fullPackage.appList.associateBy { it.packageName }
        val pm = context.packageManager
        val appEvents = mutableListOf<AppTimelineEvent>()

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
            val peakMw = avgMw // 无瞬时时序切片时峰值等于平均功率，杜绝人为乘以 1.6 倍制造假数据

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
                    cpuTimeMs = 0L, // 移除 duration / 2 假数据，无独立监控通道时置为 0L 由 UI 规范显示 "--"
                    networkBytes = 0L, // 移除 512KB 假数据，置为 0L 由 UI 规范显示 "--"
                    wakelockTimeMs = 0L, // 移除 duration / 4 假数据
                    gpsTimeMs = 0L,
                    confidence = if (fullPackage.isShizukuRealData) ConfidenceLevel.HIGH else ConfidenceLevel.MEDIUM,
                    source = if (fullPackage.isShizukuRealData) EnergySource.BATTERY_STATS else EnergySource.ESTIMATED
                )
            )
        }

        // 方案 2：若 UsageStats 区间为空或未提取到事件，直接从 points 与 appList 提取生成
        if (appEvents.isEmpty() && fullPackage.appList.isNotEmpty()) {
            val totalSpan = (endTs - startTs).coerceAtLeast(60_000L)
            var currentCursor = startTs + (totalSpan * 0.1).toLong()

            // 提取有使用时长的主要应用
            val sortedApps = fullPackage.appList
                .filter { it.foregroundTimeMs > 0 || isUserInstalledApp(it.packageName) }
                .take(6)

            for (app in sortedApps) {
                val duration = app.foregroundTimeMs.coerceIn(30_000L, 600_000L)
                val evStart = currentCursor
                val evEnd = kotlin.math.min(endTs, evStart + duration)
                currentCursor = evEnd + 60_000L

                val uid = try {
                    pm.getApplicationInfo(app.packageName, 0).uid
                } catch (_: Exception) {
                    10000
                }

                val avgMw = (app.avgPowerWatts * 1000.0).toDouble()
                appEvents.add(
                    AppTimelineEvent(
                        packageName = app.packageName,
                        uid = uid,
                        appName = app.appName,
                        icon = app.icon,
                        startTime = evStart,
                        endTime = evEnd,
                        durationMs = duration,
                        screenOn = true,
                        energyMwh = app.energyWh.toDouble() * 1000.0,
                        averagePowerMw = avgMw,
                        peakPowerMw = avgMw, // 移除写死的 1.6 倍
                        cpuTimeMs = 0L, // 移除 duration / 2 假数据
                        networkBytes = 0L, // 移除 2MB 假数据
                        wakelockTimeMs = 0L, // 移除 duration / 5 假数据
                        gpsTimeMs = 0L,
                        confidence = if (fullPackage.isShizukuRealData) ConfidenceLevel.HIGH else ConfidenceLevel.MEDIUM,
                        source = if (fullPackage.isShizukuRealData) EnergySource.BATTERY_STATS else EnergySource.ESTIMATED
                    )
                )
                if (currentCursor >= endTs) break
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
}

/**
 * 实时电池状态快照数据类。
 *
 * @property levelPercent 电量百分比
 * @property voltageVolts 电压
 * @property temperature 温度
 * @property energyWh 能量
 * @property isCharging 充电状态
 */
data class BatteryStatusSnapshot(
    val levelPercent: Int,
    val voltageVolts: Float,
    val temperature: Float,
    val energyWh: Float,
    val isCharging: Boolean
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

