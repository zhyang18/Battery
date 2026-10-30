package com.battery.analysis.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import com.battery.analysis.MainActivity
import com.battery.analysis.R
import com.battery.analysis.db.PowerUsageDbHelper
import com.battery.analysis.manager.ChargingStatsManager
import com.battery.analysis.manager.PowerUsageManager
import com.battery.analysis.model.PowerUsageRecord
import com.battery.analysis.provider.NormalApiProvider
import com.battery.analysis.receiver.HeartbeatAlarmReceiver
import com.battery.analysis.util.SysfsBatterySampler
import com.battery.analysis.util.ShizukuForegroundAppDetector
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 电池状态与充放电后台实时监控前台服务。
 *
 * - **动态广播**（Service 内注册）：App 存活时毫秒级捕获插拔电源事件与电池状态变化；
 * - **AlarmManager 心跳**：每隔 15 分钟触发一次心跳 Alarm，
 *   若服务意外被 OOM Killer 杀死，Alarm 广播会唤醒进程并重新拉起前台服务，实现自愈重启；
 * - **静态广播兜底**：配合 [com.battery.analysis.receiver.BatteryUnplugReceiver]，
 *   任何插拔电源事件均可唤醒进程并触发充放电结算，与动态广播互为备份。
 *
 * 借助 Foreground Service 前台通知机制提升应用在 Android 系统中的进程优先级，
 * 抵御系统低内存清理（LMK）与后台冻结，确保即使应用退至后台或设备锁屏，
 * 充放电数据及插拔事件依然能被 100% 完整捕捉。
 */
class BatteryMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitorSamplingJob: Job? = null
    private lateinit var notificationManager: NotificationManager

    // 内存缓存最近一次系统广播接收到的电池参数，避免高频 IPC 重复查询
    @Volatile
    private var cachedLevelPercent: Int = 100
    @Volatile
    private var cachedVoltageVolts: Float = 4.0f
    @Volatile
    private var cachedTemperature: Float = 25.0f
    @Volatile
    private var cachedIsCharging: Boolean = false
    @Volatile
    private var cachedDischargePowerWatts: Float? = null
    /** 亮屏状态下最新测得的瞬时放电功率缓存（单位：W） */
    @Volatile
    private var cachedScreenOnDischargePowerWatts: Float? = null
    /** 息屏待机状态下最新测得的瞬时放电功率缓存（单位：W） */
    @Volatile
    private var cachedScreenOffDischargePowerWatts: Float? = null
    @Volatile
    private var cachedSingleLineInfo: String = if (java.util.Locale.getDefault().language.startsWith("zh")) "⚡ 电池监控持续运行中" else "⚡ Battery monitor active"

    /**
     * 屏幕点亮/交互状态内存缓存，由动态广播 [Intent.ACTION_SCREEN_ON] 与 [Intent.ACTION_SCREEN_OFF] 实时维护。
     * 采样循环直接读取此变量，消除每秒调用 [PowerManager.isInteractive] 发起的跨进程 Binder IPC。
     */
    @Volatile
    private var cachedIsInteractive: Boolean = true

    // 内存缓存通知栏上一次推送的内容与时间戳，避免无变化时频繁唤醒 SystemUI 和进行 IPC 通信
    @Volatile
    private var lastNotifiedContent: String? = null
    @Volatile
    private var lastNotifiedTime: Long = 0L
    /** 上次屏幕熄灭时的 elapsedRealtime（毫秒），用于亮屏时差分计算总休眠时长 */
    @Volatile
    private var lastScreenOffRealtime: Long = 0L

    /** 上次屏幕熄灭时的 uptimeMillis（毫秒），用于亮屏时差分计算息屏唤醒时长与深度睡眠时长 */
    @Volatile
    private var lastScreenOffUptime: Long = 0L

    /** 上次屏幕熄灭瞬间硬件芯片库仑计快照（微安时 uAh），用于亮屏时差分计算真实硬件电荷量 */
    @Volatile
    private var lastScreenOffChargeCounterUah: Int = 0

    /** 上次屏幕熄灭瞬间测得的电池端电压（伏特 V），用于与亮屏端电压求动态梯形平均电压 */
    @Volatile
    private var lastScreenOffVoltageVolts: Float = 0f

    /** 系统广播上报的最新一次进入 Doze 深度休眠时的 elapsedRealtime（毫秒） */
    @Volatile
    private var lastDeviceIdleEntryRealtime: Long = 0L

    /** 系统广播上报的最新一次进入 Doze 深度休眠时的墙上时间戳（毫秒） */
    @Volatile
    private var lastDeviceIdleEntryTimestamp: Long = 0L

    /** 当前前台服务是否正挂载在静默渠道 [CHANNEL_ID_SILENT] 呈现“电池后台静默监控”的状态标识 */
    @Volatile
    private var isSilentNotificationActive: Boolean = false

    /**
     * 缓存的通知 PendingIntent，在 onCreate 时初始化一次并复用，
     * 避免每次 buildNotification 都触发 Binder IPC（PendingIntent.getActivity）。
     */
    private var cachedNotificationPendingIntent: PendingIntent? = null

    /**
     * 缓存的通知栏 RemoteViews 实例，只在文本内容变化时更新 setTextViewText，
     * 避免每次刷新通知都重建对象，减少 GC 压力。
     */
    private var cachedRemoteViews: RemoteViews? = null

    /**
     * 从本地存储中恢复可能因后台被系统杀死重启而中断的灭屏时序与硬件基准。
     *
     * @param context 应用程序上下文
     */
    private fun restoreScreenOffBaselineIfNeeded(context: Context) {
        if (lastScreenOffRealtime <= 0L) {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val savedRealtime = prefs.getLong(KEY_LAST_SCREEN_OFF_REALTIME, 0L)
            if (savedRealtime > 0L) {
                val currentRealtime = SystemClock.elapsedRealtime()
                // 核心时钟守卫：SystemClock.elapsedRealtime() 在开机时从 0 重置。
                // 若持久化的 savedRealtime 大于当前 currentRealtime，说明设备经历了关机重启，该时钟基准已失效，必须立即清除
                if (savedRealtime > currentRealtime) {
                    clearPersistedScreenOffBaseline(context)
                    lastScreenOffRealtime = 0L
                    lastScreenOffUptime = 0L
                    lastScreenOffChargeCounterUah = 0
                    lastScreenOffVoltageVolts = 0f
                    return
                }
                lastScreenOffRealtime = savedRealtime
                lastScreenOffUptime = prefs.getLong(KEY_LAST_SCREEN_OFF_UPTIME, 0L)
                lastScreenOffChargeCounterUah = prefs.getInt(KEY_LAST_SCREEN_OFF_COUNTER_UAH, 0)
                lastScreenOffVoltageVolts = prefs.getFloat(KEY_LAST_SCREEN_OFF_VOLTAGE, 0f)
            }
        }
    }

    /**
     * 内部动态广播接收器，用于在前台服务存活期间毫秒级捕获充放电广播、电池状态变动及屏幕亮灭事件。
     */
    private val powerReceiver = object : BroadcastReceiver() {
        /**
         * 接收到系统电池与屏幕状态广播时的处理逻辑。
         *
         * @param context 运行上下文
         * @param intent 包含广播动作的 Intent
         */
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val appContext = context?.applicationContext ?: applicationContext
            when (action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    cachedIsCharging = true
                    lastScreenOffRealtime = 0L
                    lastScreenOffUptime = 0L
                    lastScreenOffChargeCounterUah = 0
                    lastScreenOffVoltageVolts = 0f
                    clearPersistedScreenOffBaseline(appContext)
                    if (com.battery.analysis.manager.FloatingWindowManager.isFloatingEnabled(appContext)) {
                        com.battery.analysis.manager.FloatingWindowManager.updateData(
                            appContext,
                            null,
                            null,
                            isCharging = true
                        )
                    }
                    handlePowerConnected(appContext)
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    cachedIsCharging = false
                    if (!cachedIsInteractive) {
                        lastScreenOffRealtime = SystemClock.elapsedRealtime()
                        lastScreenOffUptime = SystemClock.uptimeMillis()
                        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                        lastScreenOffChargeCounterUah = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: 0
                        lastScreenOffVoltageVolts = cachedVoltageVolts
                        persistScreenOffBaseline(
                            appContext,
                            lastScreenOffRealtime,
                            lastScreenOffUptime,
                            lastScreenOffChargeCounterUah,
                            lastScreenOffVoltageVolts
                        )
                    } else {
                        lastScreenOffRealtime = 0L
                        lastScreenOffUptime = 0L
                        lastScreenOffChargeCounterUah = 0
                        lastScreenOffVoltageVolts = 0f
                        clearPersistedScreenOffBaseline(appContext)
                    }
                    if (com.battery.analysis.manager.FloatingWindowManager.isFloatingEnabled(appContext)) {
                        com.battery.analysis.manager.FloatingWindowManager.updateData(
                            appContext,
                            null,
                            null,
                            isCharging = false
                        )
                    }
                    handlePowerDisconnected(appContext)
                }
                Intent.ACTION_SCREEN_ON -> {
                    // 屏幕点亮瞬间：标记屏幕状态、结算本次息屏的唤醒与深度睡眠时长、通过硬件库仑计快照差分结算真实物理电荷与能量
                    cachedIsInteractive = true
                    restoreScreenOffBaselineIfNeeded(appContext)
                    if (lastScreenOffRealtime > 0L) {
                        val screenOffRealtimeMs = (SystemClock.elapsedRealtime() - lastScreenOffRealtime).coerceAtLeast(0L)
                        val screenOffUptimeMs = (SystemClock.uptimeMillis() - lastScreenOffUptime).coerceAtLeast(0L)
                        val deepSleepMs = (screenOffRealtimeMs - screenOffUptimeMs).coerceAtLeast(0L)

                        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                        val screenOnCounterUah = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: 0
                        val screenOnVolt = cachedVoltageVolts

                        var offHwMah = 0f
                        var offHwWh = 0f
                        if (lastScreenOffChargeCounterUah > 0 && screenOnCounterUah > 0 && lastScreenOffChargeCounterUah >= screenOnCounterUah) {
                            val effCap = PowerUsageManager.getInstance(appContext).getEffectiveDeviceCapacityMah().toDouble()
                            val rawMah = calculateScreenOffDischargeMah(
                                offCounter = lastScreenOffChargeCounterUah,
                                onCounter = screenOnCounterUah,
                                effectiveCapacity = effCap,
                                batteryPercent = cachedLevelPercent
                            )
                            val avgVolt = if (lastScreenOffVoltageVolts > 0f && screenOnVolt > 0f) {
                                (lastScreenOffVoltageVolts + screenOnVolt) / 2f
                            } else if (screenOnVolt > 0f) {
                                screenOnVolt
                            } else {
                                com.battery.analysis.util.BatteryEnergyCalculator.DEFAULT_NOMINAL_VOLTAGE_VOLTS
                            }
                            val rawWh = (rawMah * avgVolt) / 1000f

                            // 忠实记录硬件芯片库仑计差分放电量（有效正数即采纳，严禁人为虚构上限丢弃真实放电数据）
                            if (rawMah > 0f && rawWh > 0f) {
                                offHwMah = rawMah
                                offHwWh = rawWh
                            }
                        }

                        val idleEntry = lastDeviceIdleEntryRealtime
                        lastDeviceIdleEntryRealtime = 0L
                        lastDeviceIdleEntryTimestamp = 0L
                        PowerUsageManager.getInstance(appContext).recordScreenOffSleepInterval(
                            screenOffRealtimeMs = screenOffRealtimeMs,
                            screenOffAwakeMs = screenOffUptimeMs,
                            deepSleepMs = deepSleepMs,
                            hwDrainMah = offHwMah,
                            hwEnergyWh = offHwWh,
                            deviceIdleEntryRealtime = idleEntry
                        )
                        lastScreenOffRealtime = 0L
                        lastScreenOffUptime = 0L
                        lastScreenOffChargeCounterUah = 0
                        lastScreenOffVoltageVolts = 0f
                        clearPersistedScreenOffBaseline(appContext)
                    }

                    // 屏幕点亮瞬间：立即采集一次当前硬件瞬态放电状态并上报，结清息屏期间的过渡微积分切片
                    if (!cachedIsCharging) {
                        val powerManager = PowerUsageManager.getInstance(appContext)
                        val hwSample = SysfsBatterySampler.sampleHardwareDischarge(
                            context = this@BatteryMonitorService,
                            fallbackVoltageVolts = cachedVoltageVolts,
                            fallbackTempCelsius = cachedTemperature,
                            allowProcessFork = false
                        )
                        val pWatts = hwSample?.powerWatts ?: 0f
                        val curVolt = hwSample?.voltageVolts ?: cachedVoltageVolts
                        val curTemp = hwSample?.temperatureCelsius ?: cachedTemperature
                        if (hwSample?.voltageVolts != null) cachedVoltageVolts = curVolt
                        if (hwSample?.temperatureCelsius != null) cachedTemperature = curTemp
                        if (hwSample?.powerWatts != null) {
                            cachedScreenOnDischargePowerWatts = pWatts
                            cachedDischargePowerWatts = pWatts
                        }

                        powerManager.recordDischargeRealtimeSample(
                            timestamp = System.currentTimeMillis(),
                            batteryLevel = cachedLevelPercent,
                            voltageVolts = curVolt,
                            temperature = curTemp,
                            powerWatts = pWatts,
                            isScreenOn = true
                        )
                    }

                    startMonitorSamplingLoop()
                    updateNotification(force = true)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    // 屏幕熄灭瞬间：
                    cachedIsInteractive = false
                    lastScreenOffRealtime = SystemClock.elapsedRealtime()
                    lastScreenOffUptime = SystemClock.uptimeMillis()
                    KeepAliveAccessibilityService.notifyScreenOff()
                    lastKnownForegroundPackage = null

                    // 1. 在灭屏瞬间立即采样并记录息屏物理采样点，截断亮屏放电状态，消除休眠大断层被误判为亮屏能耗的问题
                    if (!cachedIsCharging) {
                        val powerManager = PowerUsageManager.getInstance(appContext)
                        val hwSample = SysfsBatterySampler.sampleHardwareDischarge(
                            context = this@BatteryMonitorService,
                            fallbackVoltageVolts = cachedVoltageVolts,
                            fallbackTempCelsius = cachedTemperature,
                            allowProcessFork = false
                        )
                        val pWatts = hwSample?.powerWatts ?: 0f
                        val curVolt = hwSample?.voltageVolts ?: cachedVoltageVolts
                        val curTemp = hwSample?.temperatureCelsius ?: cachedTemperature
                        if (hwSample?.voltageVolts != null) cachedVoltageVolts = curVolt
                        if (hwSample?.temperatureCelsius != null) cachedTemperature = curTemp
                        if (hwSample?.powerWatts != null) {
                            if (pWatts < 1.0f) {
                                cachedScreenOffDischargePowerWatts = pWatts
                            }
                            cachedDischargePowerWatts = pWatts
                        }

                        // 记录灭屏瞬间芯片硬件库仑计快照与端电压（进阶方案 1 & 2）
                        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                        lastScreenOffChargeCounterUah = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: 0
                        lastScreenOffVoltageVolts = curVolt

                        persistScreenOffBaseline(
                            appContext,
                            lastScreenOffRealtime,
                            lastScreenOffUptime,
                            lastScreenOffChargeCounterUah,
                            lastScreenOffVoltageVolts
                        )

                        powerManager.recordDischargeRealtimeSample(
                            timestamp = System.currentTimeMillis(),
                            batteryLevel = cachedLevelPercent,
                            voltageVolts = curVolt,
                            temperature = curTemp,
                            powerWatts = pWatts,
                            isScreenOn = false
                        )
                    }

                    // 2. 将内存采样点异步刷盘固化，防止异常退出导致轨迹丢失
                    PowerUsageManager.getInstance(appContext).flushDischargeSamplesToDisk()

                    // 3. 屏幕熄灭瞬间请求一次 Checkpoint，确保刚刚结束的亮屏使用账本及时落盘
                    if (!cachedIsCharging) {
                        PowerUsageManager.getInstance(appContext).requestCheckpoint(force = false)
                    }

                    // 4. 检查息屏待机策略：若为智能省电模式（<=0L），无论是充电还是放电，彻底停止轮询协程，完全释放 CPU 休眠
                    val screenOffInterval = getScreenOffIntervalMs(appContext)
                    if (screenOffInterval <= 0L) {
                        monitorSamplingJob?.cancel()
                        monitorSamplingJob = null
                    }
                }
                Intent.ACTION_BATTERY_CHANGED -> {
                    // 解析系统电池广播并更新内存缓存
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, cachedLevelPercent)
                    val voltRaw = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                    val tempRaw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                    val statusRaw = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val pluggedRaw = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                    if (level > 0) cachedLevelPercent = level
                    if (voltRaw > 0) cachedVoltageVolts = com.battery.analysis.util.BatteryUnitNormalizer.normalizeVoltageVolts(voltRaw.toLong())
                    if (tempRaw > 0) cachedTemperature = tempRaw / 10f
                    val prevIsCharging = cachedIsCharging
                    val newIsCharging = (statusRaw == BatteryManager.BATTERY_STATUS_CHARGING ||
                            statusRaw == BatteryManager.BATTERY_STATUS_FULL ||
                            pluggedRaw > 0)
                    cachedIsCharging = newIsCharging

                    val isInteractive = cachedIsInteractive
                    val screenOffInterval = getScreenOffIntervalMs(appContext)

                    // 核心状态跃迁守卫：无线充电连接或断开时，系统往往只发送 ACTION_BATTERY_CHANGED 广播，
                    // 绝不保证派发 ACTION_POWER_CONNECTED / ACTION_POWER_DISCONNECTED。
                    // 因此必须在此精确检测充电状态变化并触发对应的连接/断开事件处理！
                    if (!prevIsCharging && newIsCharging) {
                        clearPersistedScreenOffBaseline(appContext)
                        if (com.battery.analysis.manager.FloatingWindowManager.isFloatingEnabled(appContext)) {
                            com.battery.analysis.manager.FloatingWindowManager.updateData(
                                appContext,
                                null,
                                null,
                                isCharging = true
                            )
                        }
                        handlePowerConnected(appContext)
                        // 若处于息屏且配置为智能省电(0L)，立即补齐记录首个瞬时充电采样点
                        if (!isInteractive && screenOffInterval == 0L) {
                            ChargingStatsManager.getInstance(appContext).sampleCurrentPoint()
                        }
                    } else if (prevIsCharging && !newIsCharging) {
                        if (com.battery.analysis.manager.FloatingWindowManager.isFloatingEnabled(appContext)) {
                            com.battery.analysis.manager.FloatingWindowManager.updateData(
                                appContext,
                                null,
                                null,
                                isCharging = false
                            )
                        }
                        handlePowerDisconnected(appContext)
                    } else if (newIsCharging) {
                        // 稳定充电状态下：若处于息屏且配置为智能省电(0L)，借由系统电池状态广播被动记录采样点，零主动能耗
                        if (!isInteractive && screenOffInterval == 0L) {
                            ChargingStatsManager.getInstance(appContext).sampleCurrentPoint()
                        }
                    } else {
                        val powerManager = PowerUsageManager.getInstance(appContext)

                        // 检测电量下降，按 5% 步进阈值防抖触发放电 Checkpoint 增量持久化
                        powerManager.requestCheckpoint(force = false, currentLevel = cachedLevelPercent)

                        // 同步记录放电温度点
                        if (tempRaw > 0) {
                            powerManager.recordDischargeTempSample(
                                System.currentTimeMillis(),
                                tempRaw / 10f
                            )
                        }

                        // 智能省电模式（零唤醒）：息屏期间借由系统硬件状态变化广播的时机被动记录一个瞬时点，不持有 WakeLock，零主动功耗
                        // 若配置为不采样（INTERVAL_NEVER 即 -1L），则不记录采样点
                        if (!isInteractive && screenOffInterval == 0L) {
                            val hwSample = SysfsBatterySampler.sampleHardwareDischarge(
                                context = this@BatteryMonitorService,
                                fallbackVoltageVolts = cachedVoltageVolts,
                                fallbackTempCelsius = cachedTemperature,
                                allowProcessFork = true
                            )
                            val pWatts = hwSample?.powerWatts ?: 0f
                            val curVolt = hwSample?.voltageVolts ?: cachedVoltageVolts
                            val curTemp = hwSample?.temperatureCelsius ?: cachedTemperature
                            if (hwSample?.voltageVolts != null) cachedVoltageVolts = curVolt
                            if (hwSample?.temperatureCelsius != null) cachedTemperature = curTemp
                            if (hwSample?.powerWatts != null) {
                                if (pWatts < 1.0f) {
                                    cachedScreenOffDischargePowerWatts = pWatts
                                }
                                cachedDischargePowerWatts = pWatts
                            }

                            powerManager.recordDischargeRealtimeSample(
                                timestamp = System.currentTimeMillis(),
                                batteryLevel = cachedLevelPercent,
                                voltageVolts = curVolt,
                                temperature = curTemp,
                                powerWatts = pWatts,
                                isScreenOn = false
                            )
                        }
                    }
                }
                Intent.ACTION_BATTERY_LOW -> {
                    // 系统低电量预警：手机即将电量耗尽自动关机，强制触发一次紧急 Checkpoint 存盘
                    if (!cachedIsCharging) {
                        PowerUsageManager.getInstance(appContext).requestCheckpoint(force = true, currentLevel = cachedLevelPercent)
                    }
                }
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    // 系统进入或退出 Doze 深度休眠广播
                    val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    val isIdle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        pm?.isDeviceIdleMode == true
                    } else {
                        false
                    }
                    if (isIdle) {
                        lastDeviceIdleEntryRealtime = SystemClock.elapsedRealtime()
                        lastDeviceIdleEntryTimestamp = System.currentTimeMillis()
                    }
                }
                Intent.ACTION_SHUTDOWN -> {
                    // 系统关机广播：同步执行放电瞬时采样点与累加器落盘，并执行一次 Checkpoint 存盘（保持 RUNNING 状态，绝不标记为 COMPLETED）
                    if (!cachedIsCharging) {
                        try {
                            val pm = PowerUsageManager.getInstance(appContext)
                            pm.flushDischargeSamplesToDiskSync()
                            pm.checkpointDischargeSession(isFinal = false)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        }
    }

    /**
     * 安全调用 startForeground，严格兼容 Android 10+ (API 29) 至 Android 15 (API 35/36) 前台服务规范。
     * 指定特殊用途前台服务类型 [android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE]，
     * 确保向系统完全履行前台服务契约，根除 ForegroundServiceDidNotStartInTimeException。
     *
     * @param notification 前台服务绑定的系统通知对象
     */
    private fun safeStartForeground(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (_: Exception) {}
        }
    }

    /**
     * 服务创建生命周期回调，完成通知渠道构建、启动前台通知、注册动态广播接收器，并启动心跳 Alarm。
     */
    override fun onCreate() {
        super.onCreate()
        activeServiceRef = java.lang.ref.WeakReference(this)
        isServiceActive = true
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        cachedSingleLineInfo = getString(R.string.service_notification_default_desc)

        // 核心保护：开机或服务创建第一时间确保放电采样点与物理能量累加器已从本地持久化加载至内存，
        // 杜绝后续采样协程直接向空集合写入并误覆盖磁盘历史文件
        PowerUsageManager.getInstance(applicationContext).ensureDischargeSamplesLoaded()

        // 即时同步最新系统底层电池状态（电量、电压、温度、充电状态与初始硬件功耗），消除冷启动 10 秒空窗
        syncInstantBatteryStatus()

        // 1. 无条件第一步调用 startForeground 履行系统前台服务契约，杜绝任何提早退出导致的超时崩溃
        // 若开启常驻通知栏显示，挂载实时监控卡片；若关闭，挂载静默渠道通知“电池后台静默监控”，绝对不降级、不停止采样服务
        val isDisplayEnabled = isNotificationDisplayEnabled(this)
        val initialNotification = if (isDisplayEnabled) {
            buildNotification(CHANNEL_ID)
        } else {
            buildSilentNotification()
        }
        safeStartForeground(initialNotification)
        isSilentNotificationActive = !isDisplayEnabled

        // 2. 履约后检查业务守卫：若无需运行，安全退出
        if (!shouldServiceRun(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            notificationManager.cancel(NOTIFICATION_ID)
            stopSelf()
            return
        }

        // 3. 初始初始化充电状态，后续完全由动态广播事件就地维护，避免后续轮询循环中重复跨进程注册 Receiver
        val (initCharging, _) = ChargingStatsManager.getInstance(this).checkCurrentSystemChargingState()
        cachedIsCharging = initCharging

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_SHUTDOWN)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            }
        }
        registerReceiver(powerReceiver, filter)

        // 无论服务启动时处于充电还是放电状态，均自动开启全时态自适应采样轮询
        startMonitorSamplingLoop()

        // 核心策略：启动 AlarmManager 心跳，每 15 分钟触发一次
        // 若服务被 OOM Killer 杀死，心跳 Alarm 唤醒进程后会自动重启服务，实现自愈拉活
        scheduleHeartbeatAlarm(this)

        // 服务启动时主动清查一次历史可能残留的悬挂 Shizuku 远程进程
        try {
            com.battery.analysis.util.ShizukuProcessCleaner.purgeDanglingProcesses()
        } catch (_: Exception) {}

        // 服务启动时异步预热已安装应用的原生小图标与显示名称，确保卸载后 100% 完整保留
        try {
            com.battery.analysis.util.AppIconCacheManager.warmUpInstalledAppsAsync(this)
        } catch (_: Exception) {}

        if (com.battery.analysis.manager.FloatingWindowManager.isFloatingEnabled(this) &&
            com.battery.analysis.manager.FloatingWindowManager.canDrawOverlays(this)) {
            com.battery.analysis.manager.FloatingWindowManager.show(this)
        }
    }

    /**
     * 服务指令下发入口，处理启动意图（包括心跳 Alarm 触发）并保持前台服务常驻。
     * 每次被启动时均续期心跳 Alarm，防止 Alarm 链断裂。
     *
     * @param intent 启动 Intent，action 为 [ACTION_HEARTBEAT_ALARM] 时为心跳触发
     * @param flags 启动标志
     * @param startId 启动请求唯一标识 ID
     * @return 保持服务常驻的返回值 [START_STICKY]
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 快速同步最新底层电池物理状态，确保首次构建并推送通知时数据准确完整
        syncInstantBatteryStatus()

        // 1. 任何通过 startForegroundService 的调用或唤醒，第一步强制调用 startForeground 续期前台状态
        val isDisplayEnabled = isNotificationDisplayEnabled(this)
        val notification = if (isDisplayEnabled) {
            buildNotification(CHANNEL_ID)
        } else {
            buildSilentNotification()
        }
        safeStartForeground(notification)
        isSilentNotificationActive = !isDisplayEnabled

        // 2. 检查业务守卫：若无需运行，安全退出
        if (!shouldServiceRun(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            notificationManager.cancel(NOTIFICATION_ID)
            stopSelf()
            return START_NOT_STICKY
        }

        scheduleHeartbeatAlarm(this)
        startMonitorSamplingLoop()

        // 3. 心跳触发时间兜底检查点：若处于放电且已满 15 分钟未持久化，触发一次非阻塞 Checkpoint
        if (!cachedIsCharging) {
            PowerUsageManager.getInstance(applicationContext).requestCheckpoint(force = false)
        }

        updateNotification(force = true)
        return START_STICKY
    }

    /**
     * 绑定服务接口，本服务为纯后台运行服务，不支持 IPC 绑定。
     *
     * @param intent 绑定意图
     * @return 恒定返回 null
     */
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    /**
     * 任务被划掉时回调，不取消心跳 Alarm，让 Alarm 继续触发以在后续自愈重启服务。
     *
     * @param rootIntent 根 Intent
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 任务被划掉时：不取消 Alarm，让心跳继续触发以自愈重启
        scheduleHeartbeatAlarm(this)
    }

    /**
     * 服务销毁生命周期回调，注销广播接收器并释放后台采样协程。
     * 仅在用户主动关闭服务时调用 [cancelHeartbeatAlarm] 停止心跳；
     * 被 OOM Killer 杀死时系统不调用 onDestroy，Alarm 保持有效，从而实现自愈重启。
     */
    override fun onDestroy() {
        super.onDestroy()
        if (activeServiceRef?.get() === this) {
            activeServiceRef = null
        }
        isServiceActive = false
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
            notificationManager.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {}
        try {
            PowerUsageManager.getInstance(applicationContext).flushDischargeSamplesToDiskSync()
        } catch (_: Exception) {}
        try {
            PowerUsageManager.getInstance(applicationContext).shutdownDiskIoExecutor()
        } catch (_: Exception) {}
        try {
            unregisterReceiver(powerReceiver)
        } catch (_: Exception) {}
        monitorSamplingJob?.cancel()
        serviceScope.cancel()
        com.battery.analysis.timeline.util.DrawableBitmapCache.trimToLevel(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        try {
            com.battery.analysis.util.ShizukuProcessCleaner.purgeDanglingProcesses()
        } catch (_: Exception) {}
    }

    /**
     * 响应系统内存修剪广播，按系统内存紧缩等级主动释放进程内的图形与图片缓存。
     *
     * @param level 系统内存修剪级别，参见 [android.content.ComponentCallbacks2]
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        com.battery.analysis.timeline.util.DrawableBitmapCache.trimToLevel(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            try {
                com.battery.analysis.util.ShizukuProcessCleaner.purgeDanglingProcesses()
            } catch (_: Exception) {}
        }
    }

    /**
     * 处理连接外部电源（插入充电器）事件。
     *
     * @param context 应用程序上下文
     */
    private fun handlePowerConnected(context: Context) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val powerManager = PowerUsageManager.getInstance(context)
                val chargingManager = ChargingStatsManager.getInstance(context)
                val currentStatus = powerManager.getCurrentBatteryStatus()
                val (_, type) = chargingManager.checkCurrentSystemChargingState(force = true)

                // 1. 归档上一个放电周期的耗电账本并清空当前放电周期统计数据
                powerManager.onPowerConnected()

                // 2. 开启全新充电会话
                chargingManager.onPowerConnected(currentStatus.levelPercent, type)

                // 3. 开启后台全时态连续采样
                startMonitorSamplingLoop()

                // 4. 更新常驻通知
                updateNotification()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 处理断开外部电源（拔掉充电器）事件。
     *
     * @param context 应用程序上下文
     */
    private fun handlePowerDisconnected(context: Context) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val chargingManager = ChargingStatsManager.getInstance(context)
                val powerManager = PowerUsageManager.getInstance(context)

                // 1. 固化保存充电历史记录
                chargingManager.onPowerDisconnected()

                // 2. 开启全新放电统计周期（强制生效，跳过系统粘性广播未刷新延迟）
                val currentStatus = powerManager.getCurrentBatteryStatus()
                powerManager.onPowerDisconnected(currentStatus.levelPercent, force = true)

                // 3. 确保持续进行放电采样轮询
                startMonitorSamplingLoop()

                // 4. 更新常驻通知
                updateNotification()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 开启后台全时态自适应采样轮询协程。
     * 支持在充电与放电状态下无缝自适应轮询：
     * 1. 充电中：每周期采集瞬时充电轨迹数据点，持续更新功率走势；
     * 2. 放电中：根据亮屏/息屏配置间隔周期性采集温度与瞬时放电功耗；
     * 3. 智能省电：息屏且非充电状态下若配置为智能省电（0L），退出轮询协程，完全释放 CPU 休眠；
     * 4. 亮屏/息屏不采样：若对应模式配置为不采样（-1L），则跳过该状态下的放电数据采样；
     * 5. 绝对时钟步进与防连击保护：基于绝对时间戳锚点推进循环节拍，
     *    在硬件采样就绪瞬间第一时间将数据派发给悬浮窗，消除前台包名探测和通知栏重载的串行拖累；
     *    并在系统偶发卡顿超时后跳过落后节拍并设置平滑防抖窗口，彻底杜绝悬浮窗瞬间连跳两次的“忽快忽慢”现象。
     */
    private fun startMonitorSamplingLoop() {
        monitorSamplingJob?.cancel()
        monitorSamplingJob = serviceScope.launch(Dispatchers.IO) {
            val chargingManager = ChargingStatsManager.getInstance(applicationContext)
            val powerManager = PowerUsageManager.getInstance(applicationContext)

            // 在协程启动时读取一次采样间隔配置，循环内不重复读取 SharedPreferences。
            // 配置变更时调用 startMonitorSamplingLoop() 会取消并重建本协程，无需内循环轮询配置。
            val screenOnInterval = getScreenOnIntervalMs(applicationContext)
            val screenOffInterval = getScreenOffIntervalMs(applicationContext)

            // 绝对时钟节拍锚点时间戳（SystemClock.elapsedRealtime 毫秒）
            var nextTickRealtime = SystemClock.elapsedRealtime()

            while (isActive) {
                val isCharging = cachedIsCharging
                val isInteractive = cachedIsInteractive

                // 若亮屏且配置为不采样(-1L)，直接退出轮询协程，彻底杜绝 5 秒无意义空转
                if (isInteractive && screenOnInterval == INTERVAL_NEVER) {
                    break
                }

                // 智能省电零唤醒优化或不采样：息屏状态下若配置为 0L 或不采样(-1L)，直接结束轮询，释放 CPU Deep Sleep
                if (!isInteractive && screenOffInterval <= 0L) {
                    break
                }

                val targetInterval = if (isInteractive) screenOnInterval else screenOffInterval
                if (targetInterval <= 0L) {
                    break
                }

                val shouldSample = if (isInteractive) {
                    screenOnInterval != INTERVAL_NEVER
                } else {
                    screenOffInterval > 0L
                }

                if (shouldSample) {
                    try {
                        if (isCharging) {
                            val samplePoint = chargingManager.sampleCurrentPoint()
                            val curPower = samplePoint.powerWatts
                            val curVolt = samplePoint.voltageVolts
                            val curTemp = samplePoint.temperature
                            cachedVoltageVolts = curVolt
                            cachedTemperature = curTemp
                            cachedLevelPercent = samplePoint.batteryLevel

                            // 极速直通悬浮窗：第一时间将最新硬件功率与温度派发至悬浮窗 UI，零阻塞
                            if (com.battery.analysis.manager.FloatingWindowManager.isFloatingEnabled(this@BatteryMonitorService)) {
                                com.battery.analysis.manager.FloatingWindowManager.updateData(
                                    this@BatteryMonitorService,
                                    curPower,
                                    curTemp,
                                    isCharging = true
                                )
                            }

                            // 随后更新系统常驻通知栏
                            if (isInteractive && isNotificationDisplayEnabled(this@BatteryMonitorService)) {
                                updateNotificationWithSample(curPower, curVolt, curTemp)
                            }
                        } else {
                            // 1. 优先极速采集硬件放电瞬态物理指标（微秒级 JNI/BatteryManager 直读）
                            val hwSample = SysfsBatterySampler.sampleHardwareDischarge(
                                context = this@BatteryMonitorService,
                                fallbackVoltageVolts = cachedVoltageVolts,
                                fallbackTempCelsius = cachedTemperature,
                                allowProcessFork = false // 亮屏 1 秒高频采样严格禁止 Fork 进程，杜绝拉高 CPU 频率
                            )
                            val pWatts = hwSample?.powerWatts ?: 0f
                            val currentVolt = hwSample?.voltageVolts ?: cachedVoltageVolts
                            val currentTemp = hwSample?.temperatureCelsius ?: cachedTemperature

                            // 同步刷新本地缓存
                            if (hwSample?.voltageVolts != null) cachedVoltageVolts = currentVolt
                            if (hwSample?.temperatureCelsius != null) cachedTemperature = currentTemp
                            if (hwSample?.powerWatts != null) {
                                if (isInteractive) {
                                    cachedScreenOnDischargePowerWatts = pWatts
                                } else {
                                    cachedScreenOffDischargePowerWatts = pWatts
                                }
                                cachedDischargePowerWatts = pWatts
                            }

                            // 2. 极速直通悬浮窗：在执行任何可能产生跨进程延迟的前台包名探测或通知栏更新前，
                            // 第一时间直接驱动桌面悬浮窗刷新，保证视觉更新严格对齐时钟节拍
                            if (com.battery.analysis.manager.FloatingWindowManager.isFloatingEnabled(this@BatteryMonitorService)) {
                                com.battery.analysis.manager.FloatingWindowManager.updateData(
                                    this@BatteryMonitorService,
                                    pWatts,
                                    currentTemp,
                                    isCharging = false
                                )
                            }

                            // 3. 在悬浮窗已刷新完毕后，再进行前台应用包名探测，绝不拖累悬浮窗
                            val currentPkg = if (isInteractive) getForegroundPackageName() else null

                            // 4. 严格跟随放电采样服务数据同步更新常驻通知栏
                            if (isInteractive && isNotificationDisplayEnabled(this@BatteryMonitorService)) {
                                updateNotificationWithSample(pWatts, currentVolt, currentTemp)
                            }

                            // 5. 记录放电瞬时采样点并进行梯形积分累加
                            powerManager.recordDischargeRealtimeSample(
                                timestamp = System.currentTimeMillis(),
                                batteryLevel = cachedLevelPercent,
                                voltageVolts = currentVolt,
                                temperature = currentTemp,
                                powerWatts = pWatts,
                                isScreenOn = isInteractive,
                                packageName = currentPkg
                            )
                        }
                    } catch (e: Throwable) {
                        e.printStackTrace()
                    }
                }

                // 绝对时钟节拍调度推进算法（Fixed-Interval Ticker）
                val nowRealtime = SystemClock.elapsedRealtime()
                if (nextTickRealtime <= 0L || nextTickRealtime < nowRealtime - targetInterval * 2) {
                    nextTickRealtime = nowRealtime + targetInterval
                } else {
                    nextTickRealtime += targetInterval
                }

                var sleepMs = nextTickRealtime - SystemClock.elapsedRealtime()
                // 防连击与防雪崩保护（Burst Protection）：
                // 若由于系统 Binder 拥塞、低内存 GC 等异常导致单次采样耗时严重超出周期（剩余等待不足 100ms），
                // 绝不允许 delay(0) 连击执行下一轮！直接丢弃落后帧并重新对齐到当前时钟的下一个周期，
                // 同时保留平滑防抖最小间隔（targetInterval 的 80%），彻底根除瞬间连跳两次的忽快忽慢现象
                val minSmoothIntervalMs = (targetInterval * 0.8f).toLong().coerceAtLeast(200L)
                if (sleepMs < 100L) {
                    nextTickRealtime = SystemClock.elapsedRealtime() + targetInterval
                    sleepMs = minSmoothIntervalMs
                }
                delay(sleepMs.coerceAtLeast(0L))
            }
        }
    }

    /** 最近一次成功探测到的置顶前台应用包名缓存 */
    @Volatile
    private var lastKnownForegroundPackage: String? = null

    /** 最近一次查询置顶前台应用包名的时间戳（毫秒） */
    @Volatile
    private var lastForegroundQueryTime: Long = 0L

    /** 前台包名短效内存缓存有效时长（毫秒），设为 2 秒，兼顾采样周期及时响应与避免高频 Binder IPC */
    private val FOREGROUND_CACHE_EXPIRE_MS = 2_000L

    /**
     * 获取当前处于系统最前台运行的应用包名。
     * 全面事件驱动与低能耗架构：
     * 1. 最高优先级：若宿主本应用正处于前台可见活跃状态（用户正在浏览本 App），直接返回当前应用包名，0 毫秒、0 任何系统查询与 Binder IPC；
     * 2. 次优先级：采用无障碍服务事件驱动捕获的置顶应用（若用户开启了无障碍，纯事件驱动，0 轮询开销，0 跨进程 Binder）；
     * 3. 亮屏持续静止保持：若此前已确定前台应用且在 2 秒短缓存窗口内，直接沿用内存缓存，杜绝秒级频繁触发系统跨进程 IPC 与 CPU 唤醒；
     * 4. 兜底策略：仅在初次无缓存或窗口超时后，通过特权 Binder 或 UsageStatsManager 增量事件探测；
     * 5. 严格反映物理与系统真实数据：严禁在未检测到前台应用或应用暂停时无依据回退或伪造系统桌面保底数据。
     *
     * @return 当前置顶前台应用包名，若无法获取真实数据则返回 null
     */
    private fun getForegroundPackageName(): String? {
        // 0. 宿主应用在前台：极速短路返回，彻底消除前台静止不动时的所有 Binder IPC
        if (isHostAppForeground) {
            lastKnownForegroundPackage = packageName
            return packageName
        } else if (lastKnownForegroundPackage == packageName) {
            // 用户已切离宿主本应用，立即清除缓存的本应用包名，严禁切回桌面或锁屏后继续被采样判定为本 App
            lastKnownForegroundPackage = null
        }

        // 1. 无障碍服务活动窗口动态探测：
        // 实时探测当前系统活动窗口：若当前活动窗口明确是负一屏，持续认定为负一屏，保证静止阅读看卡片期间 100% 稳定采样！
        val activeWindowPkg = KeepAliveAccessibilityService.getActiveWindowPackage()
        if (activeWindowPkg != null) {
            if (KeepAliveAccessibilityService.isAssistantScreenPackage(activeWindowPkg)) {
                lastKnownForegroundPackage = activeWindowPkg
                lastForegroundQueryTime = System.currentTimeMillis()
                return activeWindowPkg
            } else if (KeepAliveAccessibilityService.isAssistantScreenPackage(lastKnownForegroundPackage)) {
                // 用户此前在负一屏，但当前活动窗口已经变回桌面或其他 App，立即解除负一屏前台状态！
                lastKnownForegroundPackage = null
            }
        }

        // 2. 无障碍服务事件驱动：仅在事件处于有效时效窗口内时优先采纳（纯内存读取，0 Binder IPC，0 轮询）
        val accessibilityPkg = KeepAliveAccessibilityService.getValidForegroundPackage(3000L)
        if (!accessibilityPkg.isNullOrEmpty()) {
            lastKnownForegroundPackage = accessibilityPkg
            return accessibilityPkg
        }

        // 关键自愈：若此前记录的前台包名为负一屏，但当前无障碍服务已未检测到任何负一屏活跃事件且当前活动窗口不在负一屏，
        // 明确表明用户已离开负一屏回到桌面或退出，必须立即清空负一屏历史缓存，
        // 绝不允许继续沿用负一屏旧记录，彻底根除桌面停留时间被无限记入负一屏的恶性缺陷
        if (lastKnownForegroundPackage != null && KeepAliveAccessibilityService.isAssistantScreenPackage(lastKnownForegroundPackage)) {
            lastKnownForegroundPackage = null
        }

        val now = System.currentTimeMillis()
        if (now - lastForegroundQueryTime < FOREGROUND_CACHE_EXPIRE_MS) {
            if (lastKnownForegroundPackage != null && lastKnownForegroundPackage != packageName) {
                return lastKnownForegroundPackage
            } else if (lastKnownForegroundPackage == null) {
                // 在 2 秒短缓存窗口内保持已知空态（如处于桌面启动器或未探知状态），
                // 彻底杜绝在桌面停留期间每秒重复触发 UsageStatsManager.queryEvents 30 秒大窗口跨进程遍历
                return null
            }
        }
        lastForegroundQueryTime = now

        // 2. 通过 Shizuku 特权 Binder 直调 IActivityTaskManager (无需无障碍)
        val shizukuPkg = ShizukuForegroundAppDetector.getForegroundPackageName(this)
        if (!shizukuPkg.isNullOrEmpty()) {
            lastKnownForegroundPackage = shizukuPkg
            return shizukuPkg
        }

        // 3. 兜底策略：基于 UsageStatsManager 增量事件探测
        // 若此前已存在有效前台包名，仅查询最近 10 秒增量事件；仅在首次冷启动无缓存时查询最近 30 秒窗口，杜绝 120 秒全量大遍历
        try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            if (usm != null) {
                val windowMs = if (lastKnownForegroundPackage != null) 10_000L else 30_000L
                val events = usm.queryEvents(now - windowMs, now)
                val event = UsageEvents.Event()
                var latestResumedPkg: String? = null
                var latestResumedTs = 0L
                var hasAnyEvent = false

                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    val pkg = event.packageName
                    if (pkg.isNullOrEmpty() || KeepAliveAccessibilityService.isIgnoredSystemComponent(pkg)) continue
                    hasAnyEvent = true

                    if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                        if (event.timeStamp >= latestResumedTs) {
                            latestResumedPkg = pkg
                            latestResumedTs = event.timeStamp
                        }
                    }
                }

                // 若增量窗口内未发生任何应用切换生命周期事件，直接沿用上一已知有效应用，0 额外开销
                if (!hasAnyEvent && lastKnownForegroundPackage != null && lastKnownForegroundPackage != packageName) {
                    return lastKnownForegroundPackage
                }

                if (!latestResumedPkg.isNullOrEmpty()) {
                    lastKnownForegroundPackage = latestResumedPkg
                    return latestResumedPkg
                }
            }
        } catch (_: Throwable) {
        }

        // 4. 亮屏持续运行状态保持：若本周期内无新切换事件，持续沿用上一已知有效前台应用；无法获取真实数据时如实返回 null
        if (lastKnownForegroundPackage != null && lastKnownForegroundPackage != packageName) {
            return lastKnownForegroundPackage
        }
        return null
    }



    /**
     * 计算并格式化当前瞬时电池监控信息文本摘要（格式：功率 | 电压 | 温度）。
     * 遵循规范：充电状态下功率显示为正数（净放电为负数），放电耗电状态下功率显示为负数。
     *
     * @return 紧凑单行电池监控文本摘要
     */
    private fun computeSingleLineInfo(): String {
        val isCharging = cachedIsCharging
        val chargingManager = ChargingStatsManager.getInstance(this)

        val powerWatts = if (isCharging) {
            val chargingPoint = chargingManager.getSamplePoints().lastOrNull()
            chargingPoint?.powerWatts
        } else {
            cachedDischargePowerWatts
        }

        val powerStr = if (powerWatts != null && abs(powerWatts) > 0.05f) {
            if (isCharging) {
                // 充电状态：正常充电为正数（如 18.0W），净放电时带负号（如 -2.1W）
                if (powerWatts > 0f) {
                    String.format(Locale.getDefault(), "%.1fW", powerWatts)
                } else {
                    String.format(Locale.getDefault(), "-%.1fW", abs(powerWatts))
                }
            } else {
                // 放电耗电状态：显示为负数（如 -2.5W）
                String.format(Locale.getDefault(), "-%.1fW", abs(powerWatts))
            }
        } else if (isCharging) {
            "0.0W"
        } else {
            "--W"
        }

        val voltStr = String.format(Locale.getDefault(), "%.2fV", cachedVoltageVolts)
        val tempStr = String.format(Locale.getDefault(), "%.1f℃", cachedTemperature)
        val singleLineInfo = "$powerStr | $voltStr | $tempStr"
        cachedSingleLineInfo = singleLineInfo
        return singleLineInfo
    }

    /**
     * 构建或刷新系统前台通知栏对象。
     * 采用自定义 [RemoteViews] 紧凑单行布局，彻底去除系统默认模板的小标题与多余换行，
     * 统一居中呈现单行实时监控数据：功率 | 电压 | 温度。
     *
     * 优化：PendingIntent 在 onCreate 时缓存后复用（[cachedNotificationPendingIntent]），
     * RemoteViews 首次创建后持续复用（[cachedRemoteViews]），每次只调用 setTextViewText
     * 更新文本内容，避免高频通知刷新产生重复 Binder IPC 与对象分配。
     *
     * @param channelId 目标通知渠道 ID（默认为 [CHANNEL_ID]）
     * @param infoText 预先计算好的单行文本内容，若为 null 则实时计算
     * @return 配置完毕的单行紧凑前台系统通知 [Notification]
     */
    private fun buildNotification(channelId: String = CHANNEL_ID, infoText: String? = null): Notification {
        // 复用已缓存的 PendingIntent，若尚未初始化则创建并缓存（仅在 onCreate / 首次调用时发生一次 Binder IPC）
        val pendingIntent = cachedNotificationPendingIntent ?: PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        ).also { cachedNotificationPendingIntent = it }

        val singleLineInfo = infoText ?: computeSingleLineInfo()

        // 复用已缓存的 RemoteViews 实例，仅更新文本，避免每次通知刷新都重建对象触发 GC
        val remoteViews = cachedRemoteViews ?: RemoteViews(packageName, R.layout.layout_notification_battery_single_line)
            .also { cachedRemoteViews = it }
        remoteViews.setTextViewText(R.id.notification_text, singleLineInfo)

        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_bolt)
            .setCustomContentView(remoteViews)
            .setContentTitle(singleLineInfo)
            .setContentText(null)
            .setStyle(null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    /**
     * 构建后台静默前台通知对象。
     * 当用户关闭常驻通知栏展示时使用，挂载至最低重要度静默渠道 [CHANNEL_ID_SILENT]（[NotificationManager.IMPORTANCE_MIN]），
     * 明确展示标题为“电池后台静默监控”，内容为“后台电池监控静默运行中”，
     * 既履行系统前台服务契约保持最高保活特权且绝对不降级、不停止后台采样服务，又折叠于系统静默通知区域不打扰用户。
     *
     * @return 静默前台系统通知对象 [Notification]
     */
    private fun buildSilentNotification(): Notification {
        val pendingIntent = cachedNotificationPendingIntent ?: PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        ).also { cachedNotificationPendingIntent = it }

        return NotificationCompat.Builder(this, CHANNEL_ID_SILENT)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle(getString(R.string.service_notification_silent_channel_name))
            .setContentText(getString(R.string.service_notification_silent_desc))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(pendingIntent)
            .build()
    }

    /**
     * 严格根据采样服务当次采样的真实物理数据刷新系统通知栏。
     * 杜绝轮询与随机异步广播干扰，完全由底层采样协程产生的新物理采样点就地触发，确保通知栏刷新频率与采样服务严格对齐一致。
     * 若用户关闭了常驻通知栏展示，则维持静默渠道通知“电池后台静默监控”以履行前台保活特权，且不执行高频界面刷新。
     *
     * @param powerWatts 采样服务测得的瞬时充放电功率（单位：W）
     * @param voltageVolts 采样服务测得的端电压（单位：V）
     * @param tempCelsius 采样服务测得的电池温度（单位：℃）
     */
    fun updateNotificationWithSample(
        powerWatts: Float?,
        voltageVolts: Float,
        tempCelsius: Float
    ) {
        try {
            val isDisplayEnabled = isNotificationDisplayEnabled(this)
            if (!isDisplayEnabled) {
                if (!isSilentNotificationActive) {
                    val silentNotification = buildSilentNotification()
                    safeStartForeground(silentNotification)
                    notificationManager.notify(NOTIFICATION_ID, silentNotification)
                    isSilentNotificationActive = true
                    lastNotifiedContent = null
                }
                return
            }
            if (!cachedIsInteractive) {
                return
            }

            val isCharging = cachedIsCharging
            val powerStr = if (powerWatts != null && abs(powerWatts) > 0.05f) {
                if (isCharging) {
                    if (powerWatts > 0f) {
                        String.format(Locale.getDefault(), "%.1fW", powerWatts)
                    } else {
                        String.format(Locale.getDefault(), "-%.1fW", abs(powerWatts))
                    }
                } else {
                    String.format(Locale.getDefault(), "-%.1fW", abs(powerWatts))
                }
            } else if (isCharging) {
                "0.0W"
            } else {
                "--W"
            }

            val voltStr = String.format(Locale.getDefault(), "%.2fV", voltageVolts)
            val tempStr = String.format(Locale.getDefault(), "%.1f℃", tempCelsius)
            val singleLineInfo = "$powerStr | $voltStr | $tempStr"
            if (singleLineInfo == lastNotifiedContent && !isSilentNotificationActive) {
                return
            }
            cachedSingleLineInfo = singleLineInfo
            lastNotifiedContent = singleLineInfo
            lastNotifiedTime = SystemClock.elapsedRealtime()

            val notification = buildNotification(CHANNEL_ID, singleLineInfo)
            safeStartForeground(notification)
            notificationManager.notify(NOTIFICATION_ID, notification)
            isSilentNotificationActive = false
        } catch (_: Exception) {}
    }

    /**
     * 刷新并推送电池状态通知至系统通知栏。
     * 当用户开启常驻通知栏时，挂载实时监控通知卡片并由采样服务真实数据驱动刷新；
     * 当用户关闭常驻通知栏时，挂载至静默渠道 [CHANNEL_ID_SILENT] 呈现“电池后台静默监控”，
     * 严格履行前台服务保活契约，保持最高保活特权，绝对不降级且不停止后台采样服务。
     *
     * @param force 是否强制触发系统通知栏刷新（如点亮屏幕瞬间或切换开关配置后）
     */
    fun updateNotification(force: Boolean = false) {
        try {
            val isDisplayEnabled = isNotificationDisplayEnabled(this)
            if (!isDisplayEnabled) {
                if (!isSilentNotificationActive || force) {
                    val silentNotification = buildSilentNotification()
                    safeStartForeground(silentNotification)
                    notificationManager.notify(NOTIFICATION_ID, silentNotification)
                    isSilentNotificationActive = true
                    lastNotifiedContent = null
                }
                return
            }

            if (!cachedIsInteractive && !force) {
                return
            }

            val singleLineInfo = computeSingleLineInfo()
            lastNotifiedContent = singleLineInfo
            lastNotifiedTime = SystemClock.elapsedRealtime()

            val notification = buildNotification(CHANNEL_ID, singleLineInfo)
            safeStartForeground(notification)
            notificationManager.notify(NOTIFICATION_ID, notification)
            isSilentNotificationActive = false
        } catch (_: Exception) {}
    }

    /**
     * 即时同步底层系统与硬件电池的最新物理状态（包含电量百分比、电压、温度、充放电状态及瞬时功率）。
     * 解决冷启动或开关切换时需等待轮询协程周期的延迟问题，确保首帧前台通知显示时数据即时准确。
     */
    private fun syncInstantBatteryStatus() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            cachedIsInteractive = pm?.isInteractive ?: true

            val stickyIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (stickyIntent != null) {
                val level = stickyIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, cachedLevelPercent)
                val voltRaw = stickyIntent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                val tempRaw = stickyIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                val statusRaw = stickyIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val pluggedRaw = stickyIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)

                if (level > 0) cachedLevelPercent = level
                if (voltRaw > 0) cachedVoltageVolts = com.battery.analysis.util.BatteryUnitNormalizer.normalizeVoltageVolts(voltRaw.toLong())
                if (tempRaw > 0) cachedTemperature = tempRaw / 10f
                cachedIsCharging = (statusRaw == BatteryManager.BATTERY_STATUS_CHARGING ||
                        statusRaw == BatteryManager.BATTERY_STATUS_FULL ||
                        pluggedRaw > 0)
            }

            if (!cachedIsCharging) {
                val hwSample = SysfsBatterySampler.sampleHardwareDischarge(
                    context = this,
                    fallbackVoltageVolts = cachedVoltageVolts,
                    fallbackTempCelsius = cachedTemperature,
                    allowProcessFork = false
                )
                if (hwSample?.voltageVolts != null) cachedVoltageVolts = hwSample.voltageVolts
                if (hwSample?.temperatureCelsius != null) cachedTemperature = hwSample.temperatureCelsius
                if (hwSample?.powerWatts != null) {
                    cachedDischargePowerWatts = hwSample.powerWatts
                    if (cachedIsInteractive) {
                        cachedScreenOnDischargePowerWatts = hwSample.powerWatts
                    } else {
                        if (hwSample.powerWatts < 1.0f) {
                            cachedScreenOffDischargePowerWatts = hwSample.powerWatts
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * 创建前台服务通知渠道（适配 Android 8.0 及以上系统）。
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.service_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.service_notification_channel_desc)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)

            val silentChannel = NotificationChannel(
                CHANNEL_ID_SILENT,
                getString(R.string.service_notification_silent_channel_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = getString(R.string.service_notification_silent_desc)
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(silentChannel)
        }
    }

    companion object {
        const val CHANNEL_ID = "channel_battery_monitor"
        const val CHANNEL_ID_SILENT = "channel_battery_monitor_silent"
        const val NOTIFICATION_ID = 10001
        const val ACTION_UPDATE_NOTIFICATION_DISPLAY = "com.battery.analysis.action.UPDATE_NOTIFICATION_DISPLAY"
        private const val PREF_NAME = "battery_service_prefs"
        private const val KEY_SERVICE_ENABLED = "pref_battery_service_enabled"
        private const val KEY_BOOT_AUTO_START = "pref_battery_boot_auto_start"
        const val KEY_NOTIFICATION_DISPLAY_ENABLED = "pref_notification_display_enabled"
        const val KEY_SCREEN_ON_INTERVAL_MS = "pref_screen_on_interval_ms"
        const val KEY_SCREEN_OFF_INTERVAL_MS = "pref_screen_off_interval_ms"
        const val KEY_CHARGE_DISCHARGE_STATS_ENABLED = "pref_charge_discharge_stats_enabled"
        const val INTERVAL_NEVER = -1L
        const val DEFAULT_SCREEN_ON_INTERVAL_MS = 1000L
        /** 息屏放电采样默认间隔（毫秒）：默认为 1 秒（1000L）高频精准采集 */
        const val DEFAULT_SCREEN_OFF_INTERVAL_MS = 1000L

        private const val KEY_LAST_SCREEN_OFF_REALTIME = "pref_last_screen_off_realtime"
        private const val KEY_LAST_SCREEN_OFF_UPTIME = "pref_last_screen_off_uptime"
        private const val KEY_LAST_SCREEN_OFF_COUNTER_UAH = "pref_last_screen_off_counter_uah"
        private const val KEY_LAST_SCREEN_OFF_VOLTAGE = "pref_last_screen_off_voltage"

        /**
         * 将屏幕熄灭时刻的时序与硬件基准持久化至本地存储，防止后台服务被系统杀死自愈后丢失息屏时基。
         *
         * @param context 应用程序上下文
         * @param realtimeMs 灭屏时刻的 elapsedRealtime（毫秒）
         * @param uptimeMs 灭屏时刻的 uptimeMillis（毫秒）
         * @param counterUah 灭屏时刻硬件库仑计读数（微安时 uAh）
         * @param voltageVolts 灭屏时刻端电压（伏特 V）
         */
        fun persistScreenOffBaseline(
            context: Context,
            realtimeMs: Long,
            uptimeMs: Long,
            counterUah: Int,
            voltageVolts: Float
        ) {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putLong(KEY_LAST_SCREEN_OFF_REALTIME, realtimeMs)
                .putLong(KEY_LAST_SCREEN_OFF_UPTIME, uptimeMs)
                .putInt(KEY_LAST_SCREEN_OFF_COUNTER_UAH, counterUah)
                .putFloat(KEY_LAST_SCREEN_OFF_VOLTAGE, voltageVolts)
                .apply()
        }

        /**
         * 清除已完成结算的屏幕熄灭基准持久化数据。
         *
         * @param context 应用程序上下文
         */
        fun clearPersistedScreenOffBaseline(context: Context) {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .remove(KEY_LAST_SCREEN_OFF_REALTIME)
                .remove(KEY_LAST_SCREEN_OFF_UPTIME)
                .remove(KEY_LAST_SCREEN_OFF_COUNTER_UAH)
                .remove(KEY_LAST_SCREEN_OFF_VOLTAGE)
                .apply()
        }

        /** 宿主应用（MainActivity）当前是否处于最前台可见活跃状态的全局内存指示器 */
        @Volatile
        private var isHostAppForeground: Boolean = false

        /**
         * 标记宿主应用（MainActivity）当前是否处于最前台可见活跃状态。
         *
         * @param foreground 是否处于前台活跃状态（true 为前台活跃，false 为后台或离开）
         */
        fun setHostAppForeground(foreground: Boolean) {
            isHostAppForeground = foreground
        }

        /**
         * 查询宿主应用当前是否处于最前台可见活跃状态。
         *
         * @return 若处于最前台返回 true，否则返回 false
         */
        fun isHostAppForeground(): Boolean = isHostAppForeground

        /** 后台电池监控服务当前是否处于活跃运行状态的全局指示器 */
        @Volatile
        private var isServiceActive: Boolean = false

        /** 当前存活的服务实例弱引用，用于前台包名探测等逻辑访问上下文 */
        @Volatile
        private var activeServiceRef: java.lang.ref.WeakReference<BatteryMonitorService>? = null

        /**
         * 查询后台电池监控服务当前是否处于真实活跃运行状态。
         * 前台 UI 可根据此状态决定是否启动独立采样，杜绝前后台双重并发采样造成的电量浪费。
         *
         * @return 若服务当前已创建且处于活跃运行状态返回 true，否则返回 false
         */
        fun isServiceActive(): Boolean = isServiceActive

        /**
         * 获取当前后台服务内存中缓存的最新瞬时放电/充电物理功率（瓦特 W）。
         * 忠实反映服务最新采样的底层硬件数据，无数据时返回 null。
         *
         * @return 瞬时充放电功率（瓦特 W），若服务未运行或尚未采样则返回 null
         */
        fun getLatestCachedPowerWatts(): Float? {
            val service = activeServiceRef?.get() ?: return null
            return if (service.cachedIsCharging) {
                ChargingStatsManager.getInstance(service.applicationContext).getSamplePoints().lastOrNull()?.powerWatts
            } else {
                service.cachedDischargePowerWatts
            }
        }

        /**
         * 获取当前后台服务内存中缓存的最新电池温度（摄氏度 ℃）。
         * 忠实反映服务最新采样的底层硬件数据，无数据时返回 null。
         *
         * @return 瞬时电池温度（摄氏度 ℃），若服务未运行或无数据则返回 null
         */
        fun getLatestCachedTemperature(): Float? {
            val service = activeServiceRef?.get() ?: return null
            return if (service.cachedTemperature > 0f) service.cachedTemperature else null
        }

        /**
         * 获取当前设备是否处于外部电源连接充电状态。
         * 优先读取活跃服务内存缓存；若服务未激活则通过系统粘性广播读取硬件真实状态。
         *
         * @param context 运行上下文
         * @return 若当前处于充电状态返回 true，放电状态返回 false
         */
        fun isCurrentlyCharging(context: Context): Boolean {
            val service = activeServiceRef?.get()
            if (service != null) {
                return service.cachedIsCharging
            }
            return try {
                val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL ||
                        plugged > 0
            } catch (_: Exception) {
                false
            }
        }

        /**
         * 获取用户是否在设置中开启了充、放电统计功能（默认关闭）。
         *
         * @param context 应用程序上下文
         * @return 若已启用充、放电统计返回 true，未启用返回 false（默认 false）
         */
        fun isChargeDischargeStatsEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_CHARGE_DISCHARGE_STATS_ENABLED, false)
        }

        /**
         * 设置并持久化充、放电统计功能的启用状态。
         *
         * @param context 应用程序上下文
         * @param enabled 是否开启充、放电统计功能（true 为开启，false 为关闭）
         */
        fun setChargeDischargeStatsEnabled(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_CHARGE_DISCHARGE_STATS_ENABLED, enabled).apply()
        }

        /**
         * 动态通知正在运行的后台服务更新常驻通知栏的显示状态。
         * 若用户关闭显示，挂载至静默渠道 [CHANNEL_ID_SILENT] 并呈现“电池后台静默监控”，
         * 严格履行前台保活契约，保持最高保活特权，绝对不降级且不停止后台采样服务；
         * 若用户开启显示，挂载实时监控通知卡片并由采样服务真实数据驱动刷新。
         *
         * @param context 应用程序上下文
         */
        fun updateNotificationVisibility(context: Context) {
            val service = activeServiceRef?.get()
            if (service != null) {
                service.updateNotification(force = true)
                return
            }
            val intent = Intent(context, BatteryMonitorService::class.java).apply {
                action = ACTION_UPDATE_NOTIFICATION_DISPLAY
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }

        /**
         * 获取常驻通知栏显示是否开启。
         *
         * @param context 应用程序上下文
         * @return 若允许在系统通知栏显示常驻通知卡片返回 true，否则返回 false（默认 true）
         */
        fun isNotificationDisplayEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_NOTIFICATION_DISPLAY_ENABLED, true)
        }

        /**
         * 设置并持久化常驻通知栏显示开关。
         *
         * @param context 应用程序上下文
         * @param enabled 是否允许在系统通知栏常驻显示
         */
        fun setNotificationDisplayEnabled(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_NOTIFICATION_DISPLAY_ENABLED, enabled).apply()
        }

        /**
         * 判断后台电池监控服务当前是否应该开启运行。
         * 满足以下任一条件时允许运行服务：
         * 1. 用户开启了桌面悬浮窗显示，必须依靠后台服务作为唯一采样数据源；
         * 2. 后台服务处于启用状态（[isServiceEnabled] 默认开启，保持前台服务契约与后台采样）；
         * 3. 用户开启了“启用充、放电统计”开关，且亮屏或息屏中开启了任意一项采样（!= INTERVAL_NEVER）。
         * 注意：关闭常驻通知栏显示开关仅切换通知形态为“电池后台静默监控”，绝对不停止后台服务与采样。
         *
         * @param context 应用程序上下文
         * @return 若需要启动服务返回 true，否则返回 false
         */
        fun shouldServiceRun(context: Context): Boolean {
            val isFloating = com.battery.analysis.manager.FloatingWindowManager.isFloatingEnabled(context) &&
                    com.battery.analysis.manager.FloatingWindowManager.canDrawOverlays(context)
            if (isFloating) {
                return true
            }
            if (isServiceEnabled(context)) {
                return true
            }
            if (isChargeDischargeStatsEnabled(context)) {
                val onInterval = getScreenOnIntervalMs(context)
                val offInterval = getScreenOffIntervalMs(context)
                return onInterval != INTERVAL_NEVER || offInterval != INTERVAL_NEVER
            }
            return false
        }

        /**
         * 获取配置的亮屏状态下常驻监控刷新间隔（毫秒）。
         *
         * @param context 应用程序上下文
         * @return 刷新间隔毫秒数（默认 1000L，-1L 表示不采样）
         */
        fun getScreenOnIntervalMs(context: Context): Long {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return prefs.getLong(KEY_SCREEN_ON_INTERVAL_MS, DEFAULT_SCREEN_ON_INTERVAL_MS)
        }

        /**
         * 设置并持久化亮屏状态下的常驻监控刷新间隔（毫秒）。
         *
         * @param context 应用程序上下文
         * @param intervalMs 刷新间隔毫秒数（-1L 表示不采样）
         */
        fun setScreenOnIntervalMs(context: Context, intervalMs: Long) {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit().putLong(KEY_SCREEN_ON_INTERVAL_MS, intervalMs).apply()
        }

        /**
         * 获取配置的息屏待机状态下放电监控采样间隔（毫秒）。
         *
         * @param context 应用程序上下文
         * @return 采样间隔毫秒数（默认 1000L 为 1 秒，0L 为智能省电，-1L 为不采样）
         */
        fun getScreenOffIntervalMs(context: Context): Long {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return prefs.getLong(KEY_SCREEN_OFF_INTERVAL_MS, DEFAULT_SCREEN_OFF_INTERVAL_MS)
        }

        /**
         * 设置并持久化息屏待机状态下的放电监控采样间隔（毫秒）。
         *
         * @param context 应用程序上下文
         * @param intervalMs 采样间隔毫秒数（0L 为智能省电，-1L 为不采样）
         */
        fun setScreenOffIntervalMs(context: Context, intervalMs: Long) {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit().putLong(KEY_SCREEN_OFF_INTERVAL_MS, intervalMs).apply()
        }

        /**
         * 启动后台电池实时监控前台服务，并标记用户配置为已开启。
         * 仅在 [shouldServiceRun] 为 true 时启动，严格保障关闭状态下无后台监控服务运行。
         *
         * @param context 应用程序上下文
         */
        fun start(context: Context) {
            if (!shouldServiceRun(context)) {
                return
            }
            val intent = Intent(context, BatteryMonitorService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                setServiceEnabled(context, true)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        /**
         * 停止后台电池实时监控前台服务，同时取消心跳 Alarm，防止服务被意外重启。
         *
         * @param context 应用程序上下文
         */
        fun stop(context: Context) {
            val intent = Intent(context, BatteryMonitorService::class.java)
            context.stopService(intent)
            setServiceEnabled(context, false)
            // 主动关闭时取消心跳 Alarm，终止自愈链
            cancelHeartbeatAlarm(context)
        }

        /**
         * 获取用户是否配置了开启后台常驻服务。
         *
         * @param context 应用程序上下文
         * @return 若后台常驻监控已启用返回 true，否则返回 false（默认 true 以保证后台采样与保活）
         */
        fun isServiceEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_SERVICE_ENABLED, true)
        }

        /**
         * 持久化设置后台常驻服务的启用状态。
         *
         * @param context 应用程序上下文
         * @param enabled 是否开启服务
         */
        fun setServiceEnabled(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_SERVICE_ENABLED, enabled).apply()
        }

        /**
         * 获取是否开启设备重启后开机自启后台监控服务。
         *
         * @param context 应用程序上下文
         * @return 若开启开机自启返回 true，否则返回 false（默认 true）
         */
        fun isBootAutoStartEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_BOOT_AUTO_START, true)
        }

        /**
         * 设置并持久化开机自启开关配置。
         *
         * @param context 应用程序上下文
         * @param enabled 是否开机自启
         */
        fun setBootAutoStartEnabled(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_BOOT_AUTO_START, enabled).apply()
        }

        // ─── 核心策略：AlarmManager 心跳拉活 ───────────────────

        /** 心跳广播 Action，由 [com.battery.analysis.receiver.HeartbeatAlarmReceiver] 接收 */
        const val ACTION_HEARTBEAT_ALARM = "com.battery.analysis.action.HEARTBEAT_ALARM"

        /** 心跳 Alarm 间隔：15 分钟，足够在 OOM Killer 杀死服务后及时恢复 */
        private const val HEARTBEAT_INTERVAL_MS = 15 * 60 * 1000L

        /** 心跳 Alarm 的 requestCode，用于唯一标识 PendingIntent */
        private const val HEARTBEAT_REQUEST_CODE = 20001

        /**
         * 调度下一次心跳 Alarm。
         * 使用精确单次 Alarm，每次触发后由接收器或服务本身继续链式续期，
         * 兼容 Android 6.0+ 低功耗 Doze 模式（使用 setExactAndAllowWhileIdle）。
         *
         * @param context 应用程序上下文
         */
        fun scheduleHeartbeatAlarm(context: Context) {
            try {
                val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                val pi = getHeartbeatPendingIntent(context) ?: return
                val triggerAt = SystemClock.elapsedRealtime() + HEARTBEAT_INTERVAL_MS
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                } else {
                    am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        /**
         * 取消心跳 Alarm（用户主动关闭服务时调用）。
         *
         * @param context 应用程序上下文
         */
        /**
         * 计算息屏单段硬件电荷计数器差分电量（单位：毫安时 mAh）。
         * 自适应归一化标准 uAh 设备与国产 ROM 直报 mAh 设备，杜绝千倍缩水或放大缺陷。
         *
         * @param offCounter 熄屏瞬间记录的原始电荷计数器读数
         * @param onCounter 亮屏瞬间记录的原始电荷计数器读数
         * @param effectiveCapacity 设备已知基准容量（单位：mAh，无则传 null）
         * @param batteryPercent 当前电池电量百分比（1..100，无则传 null）
         * @return 归一化后的单段息屏放电量（单位：毫安时 mAh）
         */
        fun calculateScreenOffDischargeMah(
            offCounter: Int,
            onCounter: Int,
            effectiveCapacity: Double?,
            batteryPercent: Int?
        ): Float {
            if (offCounter <= 0 || onCounter <= 0 || offCounter < onCounter) return 0f
            val cap = if (effectiveCapacity != null && effectiveCapacity > 0.0) effectiveCapacity else null
            val pct = if (batteryPercent != null && batteryPercent in 1..100) batteryPercent.toDouble() else null
            val offNorm = com.battery.analysis.domain.estimator.BatteryUnitNormalizer.normalizeChargeCounterToMah(
                rawCounter = offCounter.toLong(),
                effectiveCapacityMah = cap,
                batteryPercent = pct
            )
            val onNorm = com.battery.analysis.domain.estimator.BatteryUnitNormalizer.normalizeChargeCounterToMah(
                rawCounter = onCounter.toLong(),
                effectiveCapacityMah = cap,
                batteryPercent = pct
            )
            return if (offNorm != null && onNorm != null && offNorm >= onNorm) {
                (offNorm - onNorm).toFloat()
            } else {
                val rawDiff = (offCounter - onCounter).toFloat()
                if (rawDiff <= 15000f && offCounter <= 25000) {
                    rawDiff
                } else {
                    rawDiff / 1000f
                }
            }
        }

        fun cancelHeartbeatAlarm(context: Context) {
            try {
                val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                val pi = getHeartbeatPendingIntent(context) ?: return
                am.cancel(pi)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        /**
         * 构建或获取心跳 Alarm 所使用的 [PendingIntent]，指向 [HeartbeatAlarmReceiver]。
         *
         * @param context 应用程序上下文
         * @return 已构建的 [PendingIntent]，失败返回 null
         */
        private fun getHeartbeatPendingIntent(context: Context): PendingIntent? {
            return try {
                val intent = Intent(context, HeartbeatAlarmReceiver::class.java).apply {
                    action = ACTION_HEARTBEAT_ALARM
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
                PendingIntent.getBroadcast(context, HEARTBEAT_REQUEST_CODE, intent, flags)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
}
