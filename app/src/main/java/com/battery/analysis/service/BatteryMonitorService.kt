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
                    handlePowerConnected(appContext)
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    handlePowerDisconnected(appContext)
                }
                Intent.ACTION_SCREEN_ON -> {
                    // 屏幕点亮瞬间：立即恢复轮询协程并强制刷新一次通知，消除用户视觉滞后
                    startMonitorSamplingLoop()
                    updateNotification(force = true)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    // 屏幕熄灭瞬间：
                    // 1. 将内存采样点异步刷盘固化，防止异常退出导致轨迹丢失
                    PowerUsageManager.getInstance(appContext).flushDischargeSamplesToDisk()

                    // 2. 检查息屏待机策略：若为智能省电模式（<=0L），彻底停止轮询协程，完全释放 CPU 休眠
                    val screenOffInterval = getScreenOffIntervalMs(appContext)
                    val chargingManager = ChargingStatsManager.getInstance(appContext)
                    if (!chargingManager.isCharging() && screenOffInterval <= 0L) {
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
                    if (level > 0) cachedLevelPercent = level
                    if (voltRaw > 0) cachedVoltageVolts = voltRaw / 1000f
                    if (tempRaw > 0) cachedTemperature = tempRaw / 10f
                    cachedIsCharging = (statusRaw == BatteryManager.BATTERY_STATUS_CHARGING || statusRaw == BatteryManager.BATTERY_STATUS_FULL)

                    // 极致省电：仅亮屏时刷新通知，息屏直接跳过
                    updateNotification(force = false)

                    val chargingManager = ChargingStatsManager.getInstance(appContext)
                    if (!chargingManager.isCharging()) {
                        val powerManager = PowerUsageManager.getInstance(appContext)
                        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                        val isInteractive = pm?.isInteractive ?: true

                        // 同步记录放电温度点
                        if (tempRaw > 0) {
                            powerManager.recordDischargeTempSample(
                                System.currentTimeMillis(),
                                tempRaw / 10f
                            )
                        }

                        // 智能省电模式（零唤醒）：息屏期间借由系统硬件状态变化广播的时机被动记录一个瞬时点，不持有 WakeLock，零主动功耗
                        // 若配置为不采样（INTERVAL_NEVER 即 -1L），则不记录采样点
                        val screenOffInterval = getScreenOffIntervalMs(appContext)
                        if (!isInteractive && screenOffInterval == 0L) {
                            powerManager.recordDischargeRealtimeSample(
                                timestamp = System.currentTimeMillis(),
                                batteryLevel = cachedLevelPercent,
                                voltageVolts = cachedVoltageVolts,
                                temperature = cachedTemperature,
                                powerWatts = 0f,
                                isScreenOn = false
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * 服务创建生命周期回调，完成通知渠道构建、启动前台通知、注册动态广播接收器，并启动心跳 Alarm。
     */
    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        val initialNotification = buildNotification()
        startForeground(NOTIFICATION_ID, initialNotification)
        if (!isNotificationDisplayEnabled(this)) {
            // 若用户关闭了常驻通知栏显示，合规完成前台服务绑定后立即从通知栏彻底移除通知
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            notificationManager.cancel(NOTIFICATION_ID)
        }

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
        // 无论何种原因启动/重启，均续期下一次心跳
        scheduleHeartbeatAlarm(this)
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

                // 1. 归档上一个放电周期的耗电账本（调用底层原子防重归档方法）
                powerManager.archiveDischargeSession()

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
     */
    private fun startMonitorSamplingLoop() {
        monitorSamplingJob?.cancel()
        monitorSamplingJob = serviceScope.launch(Dispatchers.IO) {
            val chargingManager = ChargingStatsManager.getInstance(applicationContext)
            val powerManager = PowerUsageManager.getInstance(applicationContext)
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager

            while (isActive) {
                val isCharging = chargingManager.isCharging()
                val isInteractive = pm?.isInteractive ?: true
                val screenOnInterval = getScreenOnIntervalMs(applicationContext)
                val screenOffInterval = getScreenOffIntervalMs(applicationContext)

                // 若亮屏与息屏均配置为不采样且非充电中，直接终止轮询协程
                if (!isCharging && screenOnInterval == INTERVAL_NEVER && screenOffInterval == INTERVAL_NEVER) {
                    break
                }

                // 智能省电零唤醒优化：息屏且非充电状态下若配置为 0L 或不采样(-1L)，直接结束轮询，释放 CPU Deep Sleep
                if (!isInteractive && !isCharging && screenOffInterval <= 0L) {
                    break
                }

                if (isCharging) {
                    chargingManager.sampleCurrentPoint()
                } else {
                    val shouldSample = if (isInteractive) {
                        screenOnInterval != INTERVAL_NEVER
                    } else {
                        screenOffInterval > 0L
                    }

                    if (shouldSample) {
                        val pWatts = getDischargePowerWatts() ?: 0f
                        powerManager.recordDischargeRealtimeSample(
                            timestamp = System.currentTimeMillis(),
                            batteryLevel = cachedLevelPercent,
                            voltageVolts = cachedVoltageVolts,
                            temperature = cachedTemperature,
                            powerWatts = pWatts,
                            isScreenOn = isInteractive
                        )
                    }
                }

                // 息屏期间自动跳过通知刷新，亮屏期间才刷新
                updateNotification(force = false)

                val sleepInterval = if (isInteractive) {
                    if (screenOnInterval == INTERVAL_NEVER) 5000L else screenOnInterval
                } else {
                    if (isCharging) 15000L else screenOffInterval.coerceAtLeast(15000L)
                }
                delay(sleepInterval)
            }
        }
    }

    /**
     * 获取设备当前实时的瞬时放电功耗（单位：瓦特 W）。
     * 直接读取底层硬件库仑计电流寄存器并结合缓存电压推算，
     * 准确按照 Android 规范单位换算（微安 uA 转换为毫安 mA），杜绝临时注册广播引发的 IPC 开销。
     *
     * @return 瞬时放电功耗数值（绝对值，单位：W），若不可用则返回 null
     */
    private fun getDischargePowerWatts(): Float? {
        return try {
            val batteryManager = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
            val rawCurrent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            if (rawCurrent == 0 || rawCurrent == Int.MIN_VALUE) return null
            val absCur = abs(rawCurrent)
            // Android 官方 BatteryManager.BATTERY_PROPERTY_CURRENT_NOW 规范单位为微安 (uA)
            // 当数值大于等于 1000 时，代表微安并准确转换为毫安 (mA)；若极小老旧机型以毫安报告则保持原值
            val curMa: Float = if (absCur >= 1000) (absCur / 1000f) else absCur.toFloat()
            val voltage: Float = cachedVoltageVolts
            if (voltage > 0f && curMa > 0f) {
                (voltage * curMa) / 1000f
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 构建或刷新系统前台通知栏对象。
     * 采用自定义 [RemoteViews] 紧凑单行布局，彻底去除系统默认模板的小标题与多余换行，
     * 统一居中呈现单行实时监控数据：功率 | 电压 | 温度。
     *
     * @return 配置完毕的单行紧凑前台系统通知 [Notification]
     */
    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val chargingManager = ChargingStatsManager.getInstance(this)
        val isCharging = chargingManager.isCharging()

        val powerWatts = if (isCharging) {
            val chargingPoint = chargingManager.getSamplePoints().lastOrNull()
            if (chargingPoint != null && chargingPoint.powerWatts > 0.05f) {
                chargingPoint.powerWatts
            } else {
                getDischargePowerWatts() ?: 0f
            }
        } else {
            getDischargePowerWatts()
        }

        val powerStr = if (powerWatts != null && powerWatts > 0.05f) {
            String.format(Locale.getDefault(), "%.1fW", powerWatts)
        } else if (isCharging) {
            "0.0W"
        } else {
            "--W"
        }

        val voltStr = String.format(Locale.getDefault(), "%.2fV", cachedVoltageVolts)
        val tempStr = String.format(Locale.getDefault(), "%.1f℃", cachedTemperature)
        val singleLineInfo = "$powerStr | $voltStr | $tempStr"

        val remoteViews = RemoteViews(packageName, R.layout.layout_notification_battery_single_line).apply {
            setTextViewText(R.id.notification_text, singleLineInfo)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
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
     * 刷新并推送最新的电池状态通知至系统通知栏。
     * 若用户关闭了常驻通知栏显示，则彻底从系统通知栏移除前台通知（stopForeground + cancel），通知栏完全关闭不显示，绝不在通知栏打扰用户；
     * 若处于息屏期间且非强制刷新，自动跳过以消除 SystemUI 绘制开销与 CPU 唤醒。
     *
     * @param force 是否强制触发系统通知栏刷新（如点亮屏幕瞬间或切换开关配置后）
     */
    fun updateNotification(force: Boolean = false) {
        try {
            val isDisplayEnabled = isNotificationDisplayEnabled(this)
            if (!isDisplayEnabled) {
                // 用户关闭了常驻通知栏显示：从系统通知栏彻底移除通知，彻底关闭通知栏显示，绝不在通知栏打扰用户
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                notificationManager.cancel(NOTIFICATION_ID)
                return
            }

            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isInteractive = pm?.isInteractive ?: true
            if (!isInteractive && !force) {
                return
            }
            val notification = buildNotification()
            startForeground(NOTIFICATION_ID, notification)
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
        const val INTERVAL_NEVER = -1L
        const val DEFAULT_SCREEN_ON_INTERVAL_MS = 3000L
        const val DEFAULT_SCREEN_OFF_INTERVAL_MS = 0L

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
         * 只要亮屏监控或息屏待机中开启了任意一项采样（!= INTERVAL_NEVER），即需要开启服务；
         * 若亮屏与息屏均配置为不采样（== INTERVAL_NEVER），则无需开启服务。
         *
         * @param context 应用程序上下文
         * @return 若需要启动服务返回 true，否则返回 false
         */
        fun shouldServiceRun(context: Context): Boolean {
            val onInterval = getScreenOnIntervalMs(context)
            val offInterval = getScreenOffIntervalMs(context)
            return onInterval != INTERVAL_NEVER || offInterval != INTERVAL_NEVER
        }

        /**
         * 获取配置的亮屏状态下常驻监控刷新间隔（毫秒）。
         *
         * @param context 应用程序上下文
         * @return 刷新间隔毫秒数（默认 3000L，-1L 表示不采样）
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
         * 启动后台电池实时监控前台服务。
         *
         * @param context 应用程序上下文
         */
        fun start(context: Context) {
            val intent = Intent(context, BatteryMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            setServiceEnabled(context, true)
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
         * 获取用户是否配置了开启后台常驻服务（兼容旧接口，等价于 shouldServiceRun）。
         *
         * @param context 应用程序上下文
         * @return 若需要运行返回 true，否则返回 false
         */
        fun isServiceEnabled(context: Context): Boolean {
            return shouldServiceRun(context)
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
