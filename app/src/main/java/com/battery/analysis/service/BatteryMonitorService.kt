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
 * 采用 BatteryRecorder 核心技术路线的双层兜底架构：
 * - **动态广播**（Service 内注册）：App 存活时毫秒级捕获插拔电源事件与电池状态变化；
 * - **AlarmManager 心跳**（BatteryRecorder 策略）：每隔 15 分钟触发一次心跳 Alarm，
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
    private var cachedSingleLineInfo: String = "⚡ 电池监控持续运行中"

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
    @Volatile
    private var isForegroundNotificationRemoved: Boolean = false

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
                    handlePowerConnected(appContext)
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    cachedIsCharging = false
                    handlePowerDisconnected(appContext)
                }
                Intent.ACTION_SCREEN_ON -> {
                    // 屏幕点亮瞬间：标记屏幕状态、恢复轮询协程并强制刷新一次通知
                    cachedIsInteractive = true
                    startMonitorSamplingLoop()
                    updateNotification(force = true)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    // 屏幕熄灭瞬间：
                    cachedIsInteractive = false
                    // 1. 将内存采样点异步刷盘固化，防止异常退出导致轨迹丢失
                    PowerUsageManager.getInstance(appContext).flushDischargeSamplesToDisk()

                    // 2. 检查息屏待机策略：若为智能省电模式（<=0L），无论是充电还是放电，彻底停止轮询协程，完全释放 CPU 休眠
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
                    cachedIsCharging = (statusRaw == BatteryManager.BATTERY_STATUS_CHARGING ||
                            statusRaw == BatteryManager.BATTERY_STATUS_FULL ||
                            pluggedRaw > 0)

                    // 极致省电：仅亮屏时刷新通知，息屏直接跳过
                    updateNotification(force = false)

                    val isInteractive = cachedIsInteractive
                    val screenOffInterval = getScreenOffIntervalMs(appContext)

                    if (cachedIsCharging) {
                        // 充电状态下：若处于息屏且配置为智能省电(0L)，借由系统电池状态广播被动记录采样点，零主动能耗
                        if (!isInteractive && screenOffInterval == 0L) {
                            ChargingStatsManager.getInstance(appContext).sampleCurrentPoint()
                        }
                    } else {
                        val powerManager = PowerUsageManager.getInstance(appContext)

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
                                fallbackTempCelsius = cachedTemperature
                            )
                            // 息屏待机状态：严格使用息屏专属功率缓存，彻底杜绝亮屏高功耗跨状态污染
                            val pWatts = hwSample?.powerWatts ?: (cachedScreenOffDischargePowerWatts ?: 0f)
                            val curVolt = hwSample?.voltageVolts ?: cachedVoltageVolts
                            val curTemp = hwSample?.temperatureCelsius ?: cachedTemperature
                            if (hwSample?.voltageVolts != null) cachedVoltageVolts = curVolt
                            if (hwSample?.temperatureCelsius != null) cachedTemperature = curTemp
                            if (hwSample?.powerWatts != null) {
                                cachedScreenOffDischargePowerWatts = pWatts
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
        isServiceActive = true
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        // 1. 无条件第一步调用 startForeground 履行系统前台服务契约，杜绝任何提早退出导致的超时崩溃
        val isDisplayEnabled = isNotificationDisplayEnabled(this)
        val channelId = if (isDisplayEnabled) CHANNEL_ID else CHANNEL_ID_SILENT
        val initialNotification = buildNotification(channelId)
        safeStartForeground(initialNotification)
        if (!isDisplayEnabled) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            notificationManager.cancel(NOTIFICATION_ID)
        }

        // 2. 履约后检查业务守卫：若未开启充放电统计或无需运行，安全退出
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
        }
        registerReceiver(powerReceiver, filter)

        // 无论服务启动时处于充电还是放电状态，均自动开启全时态自适应采样轮询
        startMonitorSamplingLoop()

        // 参考 BatteryRecorder 核心策略：启动 AlarmManager 心跳，每 15 分钟触发一次
        // 若服务被 OOM Killer 杀死，心跳 Alarm 唤醒进程后会自动重启服务，实现自愈拉活
        scheduleHeartbeatAlarm(this)
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
        // 1. 任何通过 startForegroundService 的调用或唤醒，第一步强制调用 startForeground 续期前台状态
        val isDisplayEnabled = isNotificationDisplayEnabled(this)
        val channelId = if (isDisplayEnabled) CHANNEL_ID else CHANNEL_ID_SILENT
        val notification = buildNotification(channelId)
        safeStartForeground(notification)
        if (!isDisplayEnabled) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            notificationManager.cancel(NOTIFICATION_ID)
        }

        // 2. 检查业务守卫：若未开启充放电统计或无需运行，安全退出
        if (!shouldServiceRun(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            notificationManager.cancel(NOTIFICATION_ID)
            stopSelf()
            return START_NOT_STICKY
        }

        scheduleHeartbeatAlarm(this)
        if (isDisplayEnabled) {
            updateNotification(force = true)
        }
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
        isServiceActive = false
        try {
            PowerUsageManager.getInstance(applicationContext).flushDischargeSamplesToDisk()
        } catch (_: Exception) {}
        try {
            PowerUsageManager.getInstance(applicationContext).shutdownDiskIoExecutor()
        } catch (_: Exception) {}
        try {
            unregisterReceiver(powerReceiver)
        } catch (_: Exception) {}
        monitorSamplingJob?.cancel()
        serviceScope.cancel()
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
                val (_, type) = chargingManager.checkCurrentSystemChargingState()

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

                // 2. 开启全新放电统计周期（健康度快照由用户主动检测时保存，充放电过程不自动生成）
                val currentStatus = powerManager.getCurrentBatteryStatus()
                powerManager.onPowerDisconnected(currentStatus.levelPercent)

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
     * 4. 亮屏/息屏不采样：若对应模式配置为不采样（-1L），则跳过该状态下的放电数据采样。
     *
     * 优化：[screenOnInterval] 和 [screenOffInterval] 在协程启动前读取一次，
     * 循环内不重复读取 SharedPreferences，配置变更时外部会重启本协程以载入新值。
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

                val loopStartRealtime = SystemClock.elapsedRealtime()

                val shouldSample = if (isInteractive) {
                    screenOnInterval != INTERVAL_NEVER
                } else {
                    screenOffInterval > 0L
                }

                if (shouldSample) {
                    if (isCharging) {
                        chargingManager.sampleCurrentPoint()
                    } else {
                        val hwSample = SysfsBatterySampler.sampleHardwareDischarge(
                            context = this@BatteryMonitorService,
                            fallbackVoltageVolts = cachedVoltageVolts,
                            fallbackTempCelsius = cachedTemperature
                        )
                        // 若本次采样未能获取有效功率，严格复用对应屏幕状态的历史缓存（避免息屏错误复用亮屏高功耗）
                        val pWatts = hwSample?.powerWatts ?: if (isInteractive) {
                            cachedScreenOnDischargePowerWatts ?: 0f
                        } else {
                            cachedScreenOffDischargePowerWatts ?: 0f
                        }
                        val currentVolt = hwSample?.voltageVolts ?: cachedVoltageVolts
                        val currentTemp = hwSample?.temperatureCelsius ?: cachedTemperature
                        val currentPkg = if (isInteractive) getForegroundPackageName() else null

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
                }

                // 息屏期间自动跳过通知刷新，亮屏期间才刷新
                updateNotification(force = false)

                // 严格依据当前屏幕状态所配置的真实采样间隔休眠，若为不采样则退出协程
                val targetInterval = if (isInteractive) screenOnInterval else screenOffInterval
                if (targetInterval <= 0L) {
                    break
                }
                val costMs = SystemClock.elapsedRealtime() - loopStartRealtime
                val sleepInterval = (targetInterval - costMs).coerceAtLeast(0L)
                delay(sleepInterval)
            }
        }
    }

    /** 最近一次成功探测到的置顶前台应用包名缓存 */
    @Volatile
    private var lastKnownForegroundPackage: String? = null

    /** 最近一次查询置顶前台应用包名的时间戳（毫秒） */
    @Volatile
    private var lastForegroundQueryTime: Long = 0L

    /** 前台包名短效内存缓存有效时长（毫秒），延长至 8 秒避免高频发起系统跨进程 IPC 查询消耗电量 */
    private val FOREGROUND_CACHE_EXPIRE_MS = 8_000L

    /**
     * 获取当前处于系统最前台运行的应用包名。
     * 多级低功耗高精度探测：
     * 1. 优先使用 8 秒短效内存缓存，杜绝高频重复触发系统跨进程 IPC 与 CPU 唤醒；
     * 2. 其次通过 Shizuku 特权 Binder 直调 IActivityTaskManager（对标 BatteryRecorder 架构，无需无障碍）；
     * 3. 再次通过无障碍服务 [KeepAliveAccessibilityService] 事件驱动毫秒级读取（0 轮询开销）；
     * 4. 兜底策略：基于 [UsageStatsManager] 提取最近 10 秒增量事件并保持状态（若无新事件发生直接沿用上一有效应用），
     *    彻底废除过去 120 秒全量事件大遍历，兼顾极低整机能耗与前台归属准度。
     *
     * @return 当前置顶前台应用包名，若无法获取则返回 null
     */
    private fun getForegroundPackageName(): String? {
        val now = System.currentTimeMillis()
        if (now - lastForegroundQueryTime < FOREGROUND_CACHE_EXPIRE_MS && lastKnownForegroundPackage != null) {
            return lastKnownForegroundPackage
        }
        lastForegroundQueryTime = now

        // 1. 最高优先级：通过 Shizuku 特权 Binder 直调 IActivityTaskManager (对标 BatteryRecorder 架构，无需无障碍)
        val shizukuPkg = ShizukuForegroundAppDetector.getForegroundPackageName(this)
        if (!shizukuPkg.isNullOrEmpty()) {
            lastKnownForegroundPackage = shizukuPkg
            return shizukuPkg
        }

        // 2. 次优先级：采用无障碍服务事件驱动捕获的置顶应用（若用户开启了无障碍，纯事件驱动，0 轮询开销）
        val accessibilityPkg = KeepAliveAccessibilityService.currentForegroundPackage
        if (!accessibilityPkg.isNullOrEmpty()) {
            lastKnownForegroundPackage = accessibilityPkg
            return accessibilityPkg
        }

        // 获取系统当前生效的默认桌面启动器包名
        val defaultHomePkg = ShizukuForegroundAppDetector.getDefaultHomePackage(this)
            ?: PowerUsageManager.getInstance(this).getDefaultHomeLauncherPackage()

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
                var latestPausedTs = 0L
                var hasAnyEvent = false

                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    val pkg = event.packageName
                    if (pkg.isNullOrEmpty() || pkg.startsWith("com.android.systemui")) continue
                    hasAnyEvent = true

                    when (event.eventType) {
                        UsageEvents.Event.ACTIVITY_RESUMED -> {
                            if (event.timeStamp >= latestResumedTs) {
                                latestResumedPkg = pkg
                                latestResumedTs = event.timeStamp
                            }
                        }
                        UsageEvents.Event.ACTIVITY_PAUSED -> {
                            if (event.timeStamp >= latestPausedTs) {
                                latestPausedTs = event.timeStamp
                            }
                        }
                    }
                }

                // 若增量窗口内未发生任何应用切换生命周期事件，直接沿用上一已知有效应用，0 额外开销
                if (!hasAnyEvent && lastKnownForegroundPackage != null) {
                    return lastKnownForegroundPackage
                }

                // 若最新事件为前台应用 PAUSED，且之后没有新的应用 RESUMED，说明用户已切回桌面
                if (latestPausedTs > latestResumedTs && !defaultHomePkg.isNullOrEmpty()) {
                    lastKnownForegroundPackage = defaultHomePkg
                    return defaultHomePkg
                }

                if (!latestResumedPkg.isNullOrEmpty()) {
                    lastKnownForegroundPackage = latestResumedPkg
                    return latestResumedPkg
                }
            }
        } catch (_: Throwable) {
        }

        // 4. 亮屏持续运行状态保持：若本周期内无新切换事件，持续沿用上一已知有效前台应用；若无历史记录则回退至默认桌面
        if (lastKnownForegroundPackage != null) {
            return lastKnownForegroundPackage
        }
        if (!defaultHomePkg.isNullOrEmpty()) {
            lastKnownForegroundPackage = defaultHomePkg
            return defaultHomePkg
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
            .setPriority(if (channelId == CHANNEL_ID_SILENT) NotificationCompat.PRIORITY_MIN else NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    /**
     * 刷新并推送最新的电池状态通知至系统通知栏。
     * 若用户关闭通知栏常驻显示，则立即调用 stopForeground 移除通知并取消系统通知栏展示；
     * 若用户开启通知栏常驻显示，则挂载合法前台 Notification 并维持前台服务优先级。
     *
     * @param force 是否强制触发系统通知栏刷新（如点亮屏幕瞬间或切换开关配置后）
     */
    fun updateNotification(force: Boolean = false) {
        try {
            val isDisplayEnabled = isNotificationDisplayEnabled(this)
            if (!isDisplayEnabled) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                notificationManager.cancel(NOTIFICATION_ID)
                return
            }

            // 优化：直接使用内存缓存的屏幕交互状态，消除 pm.isInteractive 的跨进程 Binder IPC 开销
            if (!cachedIsInteractive && !force) {
                return
            }

            val singleLineInfo = computeSingleLineInfo()
            val now = SystemClock.elapsedRealtime()
            // 非强制刷新：同时校验内容是否变化与 500ms 最小推送间隔，双重节流消除高频无意义 IPC 唤醒 SystemUI
            if (!force && singleLineInfo == lastNotifiedContent) {
                return
            }
            // 追加时间节流：非强制刷新时，距上次推送不足 500ms 则直接跳过
            if (!force && (now - lastNotifiedTime) < 500L) {
                return
            }
            lastNotifiedContent = singleLineInfo
            lastNotifiedTime = now

            val notification = buildNotification(CHANNEL_ID, singleLineInfo)
            safeStartForeground(notification)
            notificationManager.notify(NOTIFICATION_ID, notification)
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
                getString(R.string.service_notification_channel_name),
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
        const val DEFAULT_SCREEN_OFF_INTERVAL_MS = 0L

        /** 后台电池监控服务当前是否处于活跃运行状态的全局指示器 */
        @Volatile
        private var isServiceActive: Boolean = false

        /**
         * 查询后台电池监控服务当前是否处于真实活跃运行状态。
         * 前台 UI 可根据此状态决定是否启动独立采样，杜绝前后台双重并发采样造成的电量浪费。
         *
         * @return 若服务当前已创建且处于活跃运行状态返回 true，否则返回 false
         */
        fun isServiceActive(): Boolean = isServiceActive

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
         * 若用户关闭显示，服务将立即调用 stopForeground 移除通知；
         * 若用户开启显示，服务将立即挂起前台通知进行展示。
         *
         * @param context 应用程序上下文
         */
        fun updateNotificationVisibility(context: Context) {
            if (!shouldServiceRun(context)) {
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
         * 必须在用户开启了“启用充、放电统计”开关的前提下，且亮屏监控或息屏待机中开启了任意一项采样（!= INTERVAL_NEVER），才允许运行服务；
         * 若充、放电统计开关处于关闭状态，或亮屏与息屏均配置为不采样（== INTERVAL_NEVER），则绝不开启服务。
         *
         * @param context 应用程序上下文
         * @return 若需要启动服务返回 true，否则返回 false
         */
        fun shouldServiceRun(context: Context): Boolean {
            if (!isChargeDischargeStatsEnabled(context)) {
                return false
            }
            val onInterval = getScreenOnIntervalMs(context)
            val offInterval = getScreenOffIntervalMs(context)
            return onInterval != INTERVAL_NEVER || offInterval != INTERVAL_NEVER
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
         * @return 采样间隔毫秒数（0L 为智能省电，-1L 为不采样）
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
         * 若用户未开启“启用充、放电统计”开关或无需运行，则直接拦截不予启动，严格保障关闭状态下无后台监控服务运行。
         *
         * @param context 应用程序上下文
         */
        fun start(context: Context) {
            if (!isChargeDischargeStatsEnabled(context) || !shouldServiceRun(context)) {
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
         * 获取用户是否配置了开启后台常驻服务（兼容旧接口，等价于 shouldServiceRun 且充放电统计开关已开启）。
         *
         * @param context 应用程序上下文
         * @return 若需要运行返回 true，否则返回 false
         */
        fun isServiceEnabled(context: Context): Boolean {
            return isChargeDischargeStatsEnabled(context) && shouldServiceRun(context)
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

        // ─── BatteryRecorder 核心策略：AlarmManager 心跳拉活 ───────────────────

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
