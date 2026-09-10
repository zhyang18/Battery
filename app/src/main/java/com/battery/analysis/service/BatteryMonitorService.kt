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
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
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

    /**
     * 内部动态广播接收器，用于在前台服务存活期间毫秒级捕获充放电广播、电池状态变动及屏幕亮起事件。
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
                    // 屏幕点亮瞬间立即更新通知并触发轮询，消除用户视觉滞后
                    updateNotification()
                    startMonitorSamplingLoop()
                }
                Intent.ACTION_BATTERY_CHANGED -> {
                    updateNotification()
                    val chargingManager = ChargingStatsManager.getInstance(appContext)
                    if (!chargingManager.isCharging()) {
                        val rawTemp = intent.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1)
                        if (rawTemp > 0) {
                            PowerUsageManager.getInstance(appContext).recordDischargeTempSample(
                                System.currentTimeMillis(),
                                rawTemp / 10f
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
     * 2. 放电中：周期性采集温度样本至 [PowerUsageManager]，并实时读取底层放电功耗；
     * 3. 自适应刷新：亮屏时 3 秒高频刷新以保证通知栏实时性，息屏待机时充电 15 秒、放电 30 秒以兼顾超低功耗。
     */
    private fun startMonitorSamplingLoop() {
        monitorSamplingJob?.cancel()
        monitorSamplingJob = serviceScope.launch(Dispatchers.IO) {
            val chargingManager = ChargingStatsManager.getInstance(applicationContext)
            val powerManager = PowerUsageManager.getInstance(applicationContext)
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager

            while (isActive) {
                val isCharging = chargingManager.isCharging()
                if (isCharging) {
                    chargingManager.sampleCurrentPoint()
                } else {
                    val status = powerManager.getCurrentBatteryStatus()
                    if (status.temperature > 0f) {
                        powerManager.recordDischargeTempSample(
                            System.currentTimeMillis(),
                            status.temperature
                        )
                    }
                }

                updateNotification()

                val isInteractive = pm?.isInteractive ?: true
                val sleepInterval = if (isInteractive) {
                    3000L
                } else {
                    if (isCharging) 15000L else 30000L
                }
                delay(sleepInterval)
            }
        }
    }

    /**
     * 获取设备当前实时的瞬时放电功耗（单位：瓦特 W）。
     * 优先通过 [NormalApiProvider] 读取底层硬件电流与电压推算瞬时功率，
     * 若读取失败或数值超出合理区间则返回 null。
     *
     * @return 瞬时放电功耗数值（绝对值，单位：W），若不可用则返回 null
     */
    private fun getDischargePowerWatts(): Float? {
        return try {
            val normalApi = NormalApiProvider()
            val info = normalApi.getBatteryInfo(this)
            val power = info.powerWatts
            if (power != null) {
                val absPower = Math.abs(power)
                if (absPower in 0.05f..120.0f) absPower else null
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
        val powerManager = PowerUsageManager.getInstance(this)
        val chargingManager = ChargingStatsManager.getInstance(this)
        val batteryStatus = powerManager.getCurrentBatteryStatus()
        val isCharging = chargingManager.isCharging()

        val title = if (isCharging) {
            val chargingPoint = chargingManager.getSamplePoints().lastOrNull()
            val powerWatts = chargingPoint?.powerWatts ?: 0f
            getString(R.string.service_notification_charging_title, batteryStatus.levelPercent, powerWatts)
        } else {
            val dischargePower = getDischargePowerWatts()
            if (dischargePower != null && dischargePower >= 0.05f) {
                getString(
                    R.string.service_notification_discharging_title_with_power,
                    batteryStatus.levelPercent,
                    dischargePower
                )
            } else {
                getString(R.string.service_notification_discharging_title, batteryStatus.levelPercent)
            }
        }

        val content = getString(
            R.string.service_notification_content,
            batteryStatus.voltageVolts,
            batteryStatus.temperature
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
     */
    private fun updateNotification() {
        try {
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
