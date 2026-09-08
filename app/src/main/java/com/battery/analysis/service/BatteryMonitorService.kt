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
import com.battery.analysis.db.HistoryDbHelper
import com.battery.analysis.db.PowerUsageDbHelper
import com.battery.analysis.manager.ChargingStatsManager
import com.battery.analysis.manager.PowerUsageManager
import com.battery.analysis.model.HistoryRecord
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
    private var chargingSampleJob: Job? = null
    private lateinit var notificationManager: NotificationManager

    /**
     * 内部动态广播接收器，用于在前台服务存活期间毫秒级捕获充放电广播与电池状态变动。
     */
    private val powerReceiver = object : BroadcastReceiver() {
        /**
         * 接收到系统电池广播时的处理逻辑。
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
                Intent.ACTION_BATTERY_CHANGED -> {
                    updateNotification()
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
        }
        registerReceiver(powerReceiver, filter)

        // 若服务启动时已处于充电状态，自动开启后台周期性充电指标采样
        val chargingManager = ChargingStatsManager.getInstance(this)
        if (chargingManager.isCharging()) {
            startChargingSamplingLoop()
        }
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
        chargingSampleJob?.cancel()
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

                // 1. 归档上一个放电周期的耗电账本
                val lastUnplugTime = powerManager.getLastUnplugTime()
                val now = System.currentTimeMillis()
                if (lastUnplugTime in 1 until now && (now - lastUnplugTime) > 30000L) {
                    val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(now))
                    val currentMode = powerManager.getSelectedMode()
                    val fullPackage = powerManager.loadPowerData(currentMode)
                    val powerRecord = PowerUsageRecord.fromFullPowerPackage(
                        fullPackage = fullPackage,
                        recordTime = timeStr,
                        id = now
                    )
                    PowerUsageDbHelper.getInstance(context).insertRecord(powerRecord)
                }

                // 2. 开启全新充电会话
                chargingManager.onPowerConnected(currentStatus.levelPercent, type)

                // 3. 开启后台充电连续采样
                startChargingSamplingLoop()

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
                val now = System.currentTimeMillis()
                val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(now))

                // 1. 停止后台充电采样
                chargingSampleJob?.cancel()

                // 2. 固化保存充电历史记录
                chargingManager.onPowerDisconnected()

                // 3. 提取常规检测快照保存至检测历史数据库
                val normalInfo = NormalApiProvider().getBatteryInfo(context)
                val normalRecord = HistoryRecord.fromBatteryInfo(
                    info = normalInfo,
                    category = "系统api",
                    id = now,
                    note = context.getString(R.string.note_auto_unplug)
                ).copy(captureTime = timeStr)
                HistoryDbHelper.getInstance(context).insertRecord(normalRecord)

                // 4. 开启全新放电统计周期
                val currentStatus = powerManager.getCurrentBatteryStatus()
                powerManager.onPowerDisconnected(currentStatus.levelPercent)

                // 5. 更新常驻通知
                updateNotification()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 开启充电期间后台采样轮询协程。
     * 根据设备屏幕交互状态自适应调整采样周期（亮屏时 3 秒一次，息屏待机时 15 秒一次），
     * 兼顾能耗与充电轨迹精度。
     */
    private fun startChargingSamplingLoop() {
        chargingSampleJob?.cancel()
        chargingSampleJob = serviceScope.launch(Dispatchers.IO) {
            val chargingManager = ChargingStatsManager.getInstance(applicationContext)
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager

            while (isActive && chargingManager.isCharging()) {
                chargingManager.sampleCurrentPoint()
                updateNotification()

                val isInteractive = pm?.isInteractive ?: true
                val sleepInterval = if (isInteractive) 3000L else 15000L
                delay(sleepInterval)
            }
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
            getString(R.string.service_notification_discharging_title, batteryStatus.levelPercent)
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
