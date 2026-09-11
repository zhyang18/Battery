package com.battery.analysis.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.battery.analysis.manager.ChargingStatsManager
import com.battery.analysis.manager.PowerUsageManager
import com.battery.analysis.model.PowerUsageRecord
import com.battery.analysis.service.BatteryMonitorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 电池电源状态广播接收器（静态注册，进程被强杀后依然生效）。
 *
 * 参考 BatteryRecorder 核心技术路线实现双层兜底机制：
 * 1. 静态广播（AndroidManifest 声明）：即使 App 进程被强杀，系统也会在插拔电源时唤醒进程触发此接收器；
 * 2. WakeLock 持锁机制：唤醒进程后立即申请 WakeLock，防止 CPU 在异步 IO 完成前进入 Deep Sleep；
 * 3. 自动重启前台服务：充放电事件处理完成后，自动重新拉起 BatteryMonitorService 恢复持续监控；
 * 4. 开机自愈对齐：BOOT_COMPLETED 时执行状态自愈，修复进程被杀期间的充放电状态断层。
 *
 * 静态监听系统电源连接 [Intent.ACTION_POWER_CONNECTED]、断开 [Intent.ACTION_POWER_DISCONNECTED]
 * 以及开机广播 [Intent.ACTION_BOOT_COMPLETED]。
 */
class BatteryUnplugReceiver : BroadcastReceiver() {

    /**
     * 接收到系统广播时的回调处理入口。
     *
     * @param context 应用程序上下文
     * @param intent 接收到的系统广播意图
     */
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            Intent.ACTION_POWER_CONNECTED -> {
                handlePowerConnected(context)
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                handlePowerDisconnected(context)
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                handleBootCompleted(context)
            }
        }
    }

    /**
     * 执行连接外部电源后的防抖校验与充电统计会话初始化。
     * 参考 BatteryRecorder：申请 WakeLock 后通过 goAsync 在后台线程完成 IO，
     * 最终释放 WakeLock 并重新拉起前台监控服务。
     *
     * @param context 应用程序上下文
     */
    private fun handlePowerConnected(context: Context) {
        val appContext = context.applicationContext
        val currentTime = System.currentTimeMillis()

        synchronized(lock) {
            if (currentTime - lastConnectedTimestamp < DEBOUNCE_INTERVAL_MS) {
                return
            }
            lastConnectedTimestamp = currentTime
        }

        // 申请 WakeLock，防止异步 IO 期间 CPU 进入 Deep Sleep
        val wakeLock = acquireWakeLock(appContext, "battery:power_connected")
        val pendingResult = goAsync()
        coroutineScope.launch(Dispatchers.IO) {
            try {
                recordOnPowerConnected(appContext, currentTime)
            } finally {
                pendingResult.finish()
                wakeLock?.release()
                // 广播处理完成后，自动重启前台监控服务（BatteryRecorder 核心策略）
                restartServiceIfNeeded(appContext)
            }
        }
    }

    /**
     * 执行连接电源后的结算处理：归档上一个放电周期的耗电账本，并开启全新充电会话。
     * 采用 PowerUsageManager.archiveDischargeSession 进行原子防重归档，避免并发双份记录。
     *
     * @param context 应用程序上下文
     * @param timestamp 触发插电时的时间戳毫秒值
     */
    private fun recordOnPowerConnected(context: Context, timestamp: Long) {
        try {
            val powerManager = PowerUsageManager.getInstance(context)
            val chargingManager = ChargingStatsManager.getInstance(context)

            // 1. 归档上一个放电周期的耗电账本快照（若放电持续时间大于 30 秒且未归档过）
            val powerRecord = powerManager.archiveDischargeSession(timestamp)

            // 2. 开启全新充电会话
            val currentBattery = powerManager.getCurrentBatteryStatus()
            val (_, chargeType) = chargingManager.checkCurrentSystemChargingState()
            chargingManager.onPowerConnected(currentBattery.levelPercent, chargeType)

            // 3. 通知前台界面（若当前处于前台活跃状态）
            mainHandler.post {
                if (powerRecord != null) {
                    onPowerUsageRecordedListener?.invoke(powerRecord)
                }
                onPowerConnectedListener?.invoke()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 执行断开电源后的防抖校验、电池快照异步提取与数据库保存。
     * 参考 BatteryRecorder：申请 WakeLock 后通过 goAsync 在后台线程完成 IO，
     * 最终释放 WakeLock 并重新拉起前台监控服务。
     *
     * @param context 应用程序上下文
     */
    private fun handlePowerDisconnected(context: Context) {
        val appContext = context.applicationContext
        val currentTime = System.currentTimeMillis()

        // 3秒防抖控制，避免物理插拔瞬间抖动产生重复记录
        synchronized(lock) {
            if (currentTime - lastRecordedTimestamp < DEBOUNCE_INTERVAL_MS) {
                return
            }
            lastRecordedTimestamp = currentTime
        }

        // 申请 WakeLock，防止异步 IO 期间 CPU 进入 Deep Sleep
        val wakeLock = acquireWakeLock(appContext, "battery:power_disconnected")
        val pendingResult = goAsync()
        coroutineScope.launch(Dispatchers.IO) {
            try {
                recordBatterySnapshotOnUnplug(appContext)
            } finally {
                pendingResult.finish()
                wakeLock?.release()
                // 广播处理完成后，自动重启前台监控服务（BatteryRecorder 核心策略）
                restartServiceIfNeeded(appContext)
            }
        }
    }

    /**
     * 执行断开电源后的充放电结算处理：固化上一个充电周期的历史账本，并重置开启全新放电统计周期。
     * 健康度快照仅在用户主动于电池健康页检测时保存，充放电过程不自动生成健康度记录。
     * 拔电作为放电统计周期的起点，不生成耗电快照，耗电历史账本仅在下次插电（放电周期结束）时归档结算。
     *
     * @param context 应用程序上下文
     */
    private fun recordBatterySnapshotOnUnplug(context: Context) {
        // 1. 关键闭环：当断开充电器时，固化并保存本次充电历史记录入库
        try {
            val chargingManager = ChargingStatsManager.getInstance(context)
            chargingManager.onPowerDisconnected()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. 关键步骤：开启全新放电统计周期，记录断电电量与时刻，重置底层 batterystats
        try {
            val powerManager = PowerUsageManager.getInstance(context)
            val currentBattery = powerManager.getCurrentBatteryStatus()
            powerManager.onPowerDisconnected(currentBattery.levelPercent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 处理设备开机启动事件，执行状态自愈核对，并在配置了自启时唤醒后台监控服务。
     * 参考 BatteryRecorder：开机时检测离线期间是否有充放电状态断层并自动修复。
     *
     * @param context 应用程序上下文
     */
    private fun handleBootCompleted(context: Context) {
        val appContext = context.applicationContext
        val wakeLock = acquireWakeLock(appContext, "battery:boot_completed")
        val pendingResult = goAsync()
        coroutineScope.launch(Dispatchers.IO) {
            try {
                // 1. 执行充放电状态自愈对齐（修复进程被杀期间的状态断层）
                ChargingStatsManager.getInstance(appContext).checkAndReconcileChargingState()
                PowerUsageManager.getInstance(appContext).checkAndReconcileDischargeState()

                // 2. 若用户开启了后台常驻服务与开机自启，恢复启动前台监控服务
                if (BatteryMonitorService.isServiceEnabled(appContext) &&
                    BatteryMonitorService.isBootAutoStartEnabled(appContext)) {
                    BatteryMonitorService.start(appContext)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                pendingResult.finish()
                wakeLock?.release()
            }
        }
    }

    /**
     * 参考 BatteryRecorder 核心策略：充放电事件结算完成后，尝试重新拉起前台监控服务。
     * 若服务已配置为启用，无论是否被杀，均尝试重启，确保持续采样不中断。
     *
     * @param context 应用程序上下文
     */
    private fun restartServiceIfNeeded(context: Context) {
        try {
            if (BatteryMonitorService.isServiceEnabled(context)) {
                BatteryMonitorService.start(context)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 申请一个超时安全的 WakeLock，防止 CPU 在异步 IO 期间进入 Deep Sleep。
     * 设置 30 秒最大超时作为安全兜底，防止极端情况下 WakeLock 泄漏。
     *
     * @param context 应用程序上下文
     * @param tag WakeLock 标识标签
     * @return 已申请并激活的 [PowerManager.WakeLock]，失败返回 null
     */
    private fun acquireWakeLock(context: Context, tag: String): PowerManager.WakeLock? {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)?.also { wl ->
                // 30 秒最大超时，防止极端情况下 WakeLock 泄漏导致设备无法休眠
                wl.acquire(WAKELOCK_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    companion object {
        /** 防抖间隔：3 秒内重复广播直接忽略，避免物理抖动产生重复记录 */
        private const val DEBOUNCE_INTERVAL_MS = 3000L

        /** WakeLock 最大持锁超时：30 秒，足够完成所有数据库 IO */
        private const val WAKELOCK_TIMEOUT_MS = 30_000L

        private var lastRecordedTimestamp = 0L
        private var lastConnectedTimestamp = 0L
        private val lock = Any()

        private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val mainHandler = Handler(Looper.getMainLooper())

        /**
         * 监听断开电源自动保存常规电池检测记录的回调监听器。
         */
        var onRecordInsertedListener: (() -> Unit)? = null

        /**
         * 监听断开电源自动保存耗电历史记录的回调监听器（供前台 PowerUsageFragment 注册实时更新列表）。
         */
        var onPowerUsageRecordedListener: ((PowerUsageRecord) -> Unit)? = null

        /**
         * 监听连接电源的回调监听器（供前台界面实时感知并更新）。
         */
        var onPowerConnectedListener: (() -> Unit)? = null
    }
}
