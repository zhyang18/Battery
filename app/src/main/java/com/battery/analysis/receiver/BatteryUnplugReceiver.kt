package com.battery.analysis.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.battery.analysis.R
import com.battery.analysis.db.ChargingHistoryDbHelper
import com.battery.analysis.db.HistoryDbHelper
import com.battery.analysis.db.PowerUsageDbHelper
import com.battery.analysis.manager.ChargingStatsManager
import com.battery.analysis.manager.PowerUsageManager
import com.battery.analysis.model.HistoryRecord
import com.battery.analysis.model.PowerUsageRecord
import com.battery.analysis.provider.NormalApiProvider
import com.battery.analysis.provider.ShizukuProvider
import com.battery.analysis.service.BatteryMonitorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 电池电源状态广播接收器。
 * 静态监听系统电源连接 [Intent.ACTION_POWER_CONNECTED]、断开 [Intent.ACTION_POWER_DISCONNECTED]
 * 以及开机广播 [Intent.ACTION_BOOT_COMPLETED]。
 * 实现插电瞬间结算上一个放电周期并开启充电采样，拔电瞬间固化保存充电历史记录并开启全新放电周期，
 * 彻底解决 App 被杀或离线期间充放电事件无法检测与账本丢失的问题。
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

        val pendingResult = goAsync()
        coroutineScope.launch(Dispatchers.IO) {
            try {
                recordOnPowerConnected(appContext, currentTime)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * 异步处理连接外部电源事件：归档上一个放电账本并开启全新充电统计会话。
     *
     * @param context 应用程序上下文
     * @param timestamp 触发插电时的时间戳毫秒值
     */
    private fun recordOnPowerConnected(context: Context, timestamp: Long) {
        try {
            val powerManager = PowerUsageManager.getInstance(context)
            val chargingManager = ChargingStatsManager.getInstance(context)
            val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

            // 1. 归档上一个放电周期的耗电账本快照（若放电持续时间大于 30 秒）
            val lastUnplugTime = powerManager.getLastUnplugTime()
            if (lastUnplugTime in 1 until timestamp && (timestamp - lastUnplugTime) > 30000L) {
                val currentMode = powerManager.getSelectedMode()
                val fullPackage = powerManager.loadPowerData(currentMode)
                val powerRecord = PowerUsageRecord.fromFullPowerPackage(
                    fullPackage = fullPackage,
                    recordTime = timeStr,
                    id = timestamp
                )
                PowerUsageDbHelper.getInstance(context).insertRecord(powerRecord)
            }

            // 2. 开启全新充电会话
            val currentBattery = powerManager.getCurrentBatteryStatus()
            val (_, chargeType) = chargingManager.checkCurrentSystemChargingState()
            chargingManager.onPowerConnected(currentBattery.levelPercent, chargeType)

            // 3. 通知前台界面（若当前处于前台活跃状态）
            mainHandler.post {
                onPowerConnectedListener?.invoke()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 执行断开电源后的防抖校验、电池快照异步提取与数据库保存。
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

        val pendingResult = goAsync()
        coroutineScope.launch(Dispatchers.IO) {
            try {
                recordBatterySnapshotOnUnplug(appContext, currentTime)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * 异步采集当前系统的电池数据，构建历史记录并存入 SQLite 数据库。
     *
     * @param context 应用程序上下文
     * @param timestamp 触发断电时的时间戳毫秒值
     */
    private fun recordBatterySnapshotOnUnplug(context: Context, timestamp: Long) {
        val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
        val noteText = context.getString(R.string.note_auto_unplug)
        val recordsToInsert = mutableListOf<HistoryRecord>()

        // 0. 关键闭环：当断开充电器时，固化并保存本次充电历史记录入库
        try {
            val chargingManager = ChargingStatsManager.getInstance(context)
            chargingManager.onPowerDisconnected()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 1. 采集系统原生 API 电池快照数据
        val normalProvider = NormalApiProvider()
        val normalInfo = normalProvider.getBatteryInfo(context)
        val normalRecord = HistoryRecord.fromBatteryInfo(
            info = normalInfo,
            category = "系统api",
            id = timestamp,
            note = noteText
        ).copy(captureTime = timeStr)
        recordsToInsert.add(normalRecord)

        // 2. 若 Shizuku 处于运行且已授权状态，同步采集底层驱动高精度快照
        val isShizukuAvailable = try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }

        if (isShizukuAvailable) {
            try {
                val shizukuProvider = ShizukuProvider()
                val shizukuInfo = shizukuProvider.getBatteryInfo(context)
                if (shizukuInfo.designCapacity != null || shizukuInfo.level != null || shizukuInfo.cycleCount != null) {
                    val shizukuRecord = HistoryRecord.fromBatteryInfo(
                        info = shizukuInfo,
                        category = "Shizuku",
                        id = timestamp + 1,
                        note = noteText
                    ).copy(captureTime = timeStr)
                    recordsToInsert.add(shizukuRecord)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 3. 采集断开前耗电统计完整账本快照并存入 PowerUsageDbHelper 数据库，随后重置开启新放电周期
        try {
            val powerManager = PowerUsageManager.getInstance(context)
            val currentMode = powerManager.getSelectedMode()
            val fullPackage = powerManager.loadPowerData(currentMode)
            val powerRecord = PowerUsageRecord.fromFullPowerPackage(
                fullPackage = fullPackage,
                recordTime = timeStr,
                id = timestamp
            )
            val powerDbHelper = PowerUsageDbHelper.getInstance(context)
            powerDbHelper.insertRecord(powerRecord)

            // 关键步骤：开启全新放电统计周期，记录断电电量与时刻，重置底层 batterystats
            val currentBattery = powerManager.getCurrentBatteryStatus()
            powerManager.onPowerDisconnected(currentBattery.levelPercent)

            mainHandler.post {
                onPowerUsageRecordedListener?.invoke(powerRecord)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 4. 事务批量插入常规电池检测历史数据库
        if (recordsToInsert.isNotEmpty()) {
            val dbHelper = HistoryDbHelper.getInstance(context)
            dbHelper.insertRecords(recordsToInsert)

            // 5. 通知前台界面刷新数据流并提示 Toast
            mainHandler.post {
                onRecordInsertedListener?.invoke()
                try {
                    Toast.makeText(context, context.getString(R.string.toast_unplug_recorded), Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * 处理设备开机启动事件，执行状态自愈核对，并在配置了自启时唤醒后台监控服务。
     *
     * @param context 应用程序上下文
     */
    private fun handleBootCompleted(context: Context) {
        val appContext = context.applicationContext
        coroutineScope.launch(Dispatchers.IO) {
            try {
                // 1. 执行充放电状态自愈对齐
                ChargingStatsManager.getInstance(appContext).checkAndReconcileChargingState()
                PowerUsageManager.getInstance(appContext).checkAndReconcileDischargeState()

                // 2. 若用户开启了后台常驻服务与开机自启，恢复启动前台监控服务
                if (BatteryMonitorService.isServiceEnabled(appContext) && BatteryMonitorService.isBootAutoStartEnabled(appContext)) {
                    BatteryMonitorService.start(appContext)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    companion object {
        private const val DEBOUNCE_INTERVAL_MS = 3000L
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

