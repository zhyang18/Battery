package com.battery.analysis.service

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
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import com.battery.analysis.MainActivity
import com.battery.analysis.R
import com.battery.analysis.db.PowerUsageDbHelper
import com.battery.analysis.manager.ChargingStatsManager
import com.battery.analysis.manager.PowerUsageManager
import com.battery.analysis.model.PowerUsageRecord
import com.battery.analysis.provider.NormalApiProvider
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
 * 借助 Foreground Service 前台通知机制提升应用在 Android 系统中的进程优先级，
 * 抵御系统低内存清理（LMK）与后台冻结，动态监听连接电源与断开电源事件，
 * 并支持在充电期间进行低开销周期采样，确保即使应用退至后台或设备锁屏，
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
                        val screenOffInterval = getScreenOffIntervalMs(appContext)
                        if (!isInteractive && screenOffInterval <= 0L) {
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
     * 服务创建生命周期回调，完成通知渠道构建、启动前台通知并注册动态广播接收器。
     */
    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        val initialNotification = buildNotification()
        startForeground(NOTIFICATION_ID, initialNotification)

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
    }

    /**
     * 服务指令下发入口，处理启动意图并保持前台服务常驻。
     *
     * @param intent 启动 Intent
     * @param flags 启动标志
     * @param startId 启动请求唯一标识 ID
     * @return 保持服务常驻的返回值 [START_STICKY]
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateNotification()
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
     * 服务销毁生命周期回调，注销广播接收器并释放后台采样协程。
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
     * 2. 放电中：周期性采集温度样本至 [PowerUsageManager]，并读取底层放电功耗；
     * 3. 智能省电：息屏且非充电状态下若配置为智能省电（0L），直接退出轮询协程，完全释放 CPU 休眠；
     * 4. 亮屏刷新：亮屏时按配置间隔高频刷新以保证通知栏实时性。
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
                val screenOffInterval = getScreenOffIntervalMs(applicationContext)

                // 智能省电零唤醒优化：息屏且非充电状态下若配置为 0L，直接结束轮询，释放 CPU Deep Sleep
                if (!isInteractive && !isCharging && screenOffInterval <= 0L) {
                    break
                }

                if (isCharging) {
                    chargingManager.sampleCurrentPoint()
                } else {
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

                // 息屏期间自动跳过通知刷新，亮屏期间才刷新
                updateNotification(force = false)

                val screenOnInterval = getScreenOnIntervalMs(applicationContext)
                val sleepInterval = if (isInteractive) {
                    screenOnInterval
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
     * 杜绝临时注册广播引发的 IPC 开销，数值超出合理区间则返回 null。
     *
     * @return 瞬时放电功耗数值（绝对值，单位：W），若不可用则返回 null
     */
    private fun getDischargePowerWatts(): Float? {
        return try {
            val batteryManager = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
            val rawCurrent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            if (rawCurrent == 0 || rawCurrent == Int.MIN_VALUE) return null
            val absCur = abs(rawCurrent)
            val curMa: Float = if (absCur < 100000) absCur.toFloat() else (absCur / 1000f)
            val voltage: Float = cachedVoltageVolts
            if (voltage > 0f && curMa > 0f) {
                val power: Float = (voltage * curMa) / 1000f
                if (power in 0.05f..120.0f) power else null
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 构建或刷新系统前台通知栏对象。
     *
     * @return 配置完毕的系统通知 [Notification]
     */
    private fun buildNotification(): Notification {
        val chargingManager = ChargingStatsManager.getInstance(this)
        val isCharging = chargingManager.isCharging()

        val title = if (isCharging) {
            val chargingPoint = chargingManager.getSamplePoints().lastOrNull()
            val powerWatts = chargingPoint?.powerWatts ?: 0f
            getString(R.string.service_notification_charging_title, cachedLevelPercent, powerWatts)
        } else {
            val dischargePower = getDischargePowerWatts()
            if (dischargePower != null && dischargePower >= 0.05f) {
                getString(
                    R.string.service_notification_discharging_title_with_power,
                    cachedLevelPercent,
                    dischargePower
                )
            } else {
                getString(R.string.service_notification_discharging_title, cachedLevelPercent)
            }
        }

        val content = getString(
            R.string.service_notification_content,
            cachedVoltageVolts,
            cachedTemperature
        )

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    /**
     * 刷新并推送最新的电池状态通知至系统通知栏。
     * 在息屏期间且非强制刷新时自动跳过，消除 SystemUI 绘制开销与锁屏唤醒。
     *
     * @param force 是否强制触发系统通知栏刷新（如点亮屏幕瞬间）
     */
    private fun updateNotification(force: Boolean = false) {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isInteractive = pm?.isInteractive ?: true
            if (!isInteractive && !force) {
                return
            }
            notificationManager.notify(NOTIFICATION_ID, buildNotification())
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
        }
    }

    companion object {
        const val CHANNEL_ID = "channel_battery_monitor"
        const val NOTIFICATION_ID = 10001
        private const val PREF_NAME = "battery_service_prefs"
        private const val KEY_SERVICE_ENABLED = "pref_battery_service_enabled"
        private const val KEY_BOOT_AUTO_START = "pref_battery_boot_auto_start"
        const val KEY_SCREEN_ON_INTERVAL_MS = "pref_screen_on_interval_ms"
        const val KEY_SCREEN_OFF_INTERVAL_MS = "pref_screen_off_interval_ms"
        const val DEFAULT_SCREEN_ON_INTERVAL_MS = 3000L
        const val DEFAULT_SCREEN_OFF_INTERVAL_MS = 0L

        /**
         * 获取配置的亮屏状态下常驻监控刷新间隔（毫秒）。
         *
         * @param context 应用程序上下文
         * @return 刷新间隔毫秒数（默认 3000L）
         */
        fun getScreenOnIntervalMs(context: Context): Long {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return prefs.getLong(KEY_SCREEN_ON_INTERVAL_MS, DEFAULT_SCREEN_ON_INTERVAL_MS)
        }

        /**
         * 设置并持久化亮屏状态下的常驻监控刷新间隔（毫秒）。
         *
         * @param context 应用程序上下文
         * @param intervalMs 刷新间隔毫秒数
         */
        fun setScreenOnIntervalMs(context: Context, intervalMs: Long) {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit().putLong(KEY_SCREEN_ON_INTERVAL_MS, intervalMs).apply()
        }

        /**
         * 获取配置的息屏待机状态下放电监控采样间隔（毫秒）。
         *
         * @param context 应用程序上下文
         * @return 采样间隔毫秒数（默认 30000L）
         */
        fun getScreenOffIntervalMs(context: Context): Long {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return prefs.getLong(KEY_SCREEN_OFF_INTERVAL_MS, DEFAULT_SCREEN_OFF_INTERVAL_MS)
        }

        /**
         * 设置并持久化息屏待机状态下的放电监控采样间隔（毫秒）。
         *
         * @param context 应用程序上下文
         * @param intervalMs 采样间隔毫秒数
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
         * 停止后台电池实时监控前台服务。
         *
         * @param context 应用程序上下文
         */
        fun stop(context: Context) {
            val intent = Intent(context, BatteryMonitorService::class.java)
            context.stopService(intent)
            setServiceEnabled(context, false)
        }

        /**
         * 获取用户是否配置了开启后台常驻服务。
         *
         * @param context 应用程序上下文
         * @return 若已开启返回 true，否则返回 false（默认 true 以获得最佳防杀体验）
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
    }
}
