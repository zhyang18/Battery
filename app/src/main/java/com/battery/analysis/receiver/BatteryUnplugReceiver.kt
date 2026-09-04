package com.battery.analysis.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.battery.analysis.R
import com.battery.analysis.db.HistoryDbHelper
import com.battery.analysis.model.HistoryRecord
import com.battery.analysis.provider.NormalApiProvider
import com.battery.analysis.provider.ShizukuProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 电池断开外部电源（拔掉电池/充电器）广播接收器。
 * 监听系统 [Intent.ACTION_POWER_DISCONNECTED] 广播，在拔掉充电线或断开电源供电的瞬间，
 * 自动提取当前电池核心参数并持久化存入历史检测记录数据库中，同时通知前台刷新展示。
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
        if (action == Intent.ACTION_POWER_DISCONNECTED) {
            handlePowerDisconnected(context)
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
            val powerManager = com.battery.analysis.manager.PowerUsageManager.getInstance(context)
            val currentMode = powerManager.getSelectedMode()
            val fullPackage = powerManager.loadPowerData(currentMode)
            val powerRecord = com.battery.analysis.model.PowerUsageRecord.fromFullPowerPackage(
                fullPackage = fullPackage,
                recordTime = timeStr,
                id = timestamp
            )
            val powerDbHelper = com.battery.analysis.db.PowerUsageDbHelper.getInstance(context)
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

    companion object {
        private const val DEBOUNCE_INTERVAL_MS = 3000L
        private var lastRecordedTimestamp = 0L
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
        var onPowerUsageRecordedListener: ((com.battery.analysis.model.PowerUsageRecord) -> Unit)? = null
    }
}
