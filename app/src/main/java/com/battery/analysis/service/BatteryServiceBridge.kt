package com.battery.analysis.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.battery.analysis.manager.BatteryStatusSnapshot
import com.battery.analysis.manager.ChargingStatsManager
import com.battery.analysis.manager.PowerUsageManager
import com.battery.analysis.model.ChargingSamplePoint
import com.battery.analysis.model.ChargingSessionSummary
import com.battery.analysis.model.PowerDischargePoint
import kotlinx.coroutines.launch

/**
 * 电池后台监控服务跨进程通信桥梁（客户端 IPC 代理）。
 *
 * 运行在主 UI 进程（com.battery.analysis），负责与独立后台监控进程（:monitor）中的
 * [BatteryMonitorService] 建立与维护 AIDL Binder 连接。
 *
 * 核心机制：
 * 1. 负责生命周期绑定（[bindService]）与按需解绑（[unbindService]），在主进程退后台或无需交互时解耦；
 * 2. 注册 [IBinder.DeathRecipient] 死亡监听，当后台服务进程意外被杀或重启时自动标记重连，保障通信健壮性；
 * 3. 具备优雅本地回退能力：当后台服务尚未绑定或 Binder 断开时，直接读取共享磁盘私有文件中的持久化数据，绝不阻塞或抛出崩溃；
 * 4. 遵守真实数据原则，绝不伪造或虚拟填充任何未获取的硬件指标。
 */
object BatteryServiceBridge {

    @Volatile
    private var serviceBinder: IBatteryMonitorService? = null

    @Volatile
    private var isBound: Boolean = false

    @Volatile
    private var lastAppContext: Context? = null

    private val rebindScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    /**
     * 远端服务死亡通知接收器，在后台进程被系统强制终止（如 OOM / LMK）时触发，
     * 负责重置连接状态并调度自愈重连。
     */
    private val deathRecipient = IBinder.DeathRecipient {
        synchronized(this) {
            serviceBinder = null
            isBound = false
        }
        triggerAutoRebind()
    }

    private val connectionListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /**
     * 当远端服务异常断开时触发的自愈重连调度方法。
     * 若检测到系统配置仍要求服务处于开启运行状态，则在后台协程中重新拉起服务并重建 Binder 绑定。
     */
    private fun triggerAutoRebind() {
        val ctx = lastAppContext ?: return
        if (BatteryMonitorService.shouldServiceRun(ctx)) {
            rebindScope.launch {
                kotlinx.coroutines.delay(1000L)
                if (!isConnected()) {
                    BatteryMonitorService.start(ctx)
                    bindService(ctx)
                }
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        /**
         * 与后台服务建立 IPC Binder 连接时的系统回调。
         *
         * @param name 组件名称
         * @param service 远端 Binder 对象
         */
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            var connected = false
            synchronized(this@BatteryServiceBridge) {
                if (service != null) {
                    try {
                        service.linkToDeath(deathRecipient, 0)
                        serviceBinder = IBatteryMonitorService.Stub.asInterface(service)
                        isBound = true
                        connected = true
                    } catch (_: Throwable) {
                        serviceBinder = null
                        isBound = false
                    }
                }
            }
            if (connected) {
                for (listener in connectionListeners) {
                    try {
                        listener()
                    } catch (_: Throwable) {}
                }
            }
        }

        /**
         * 与后台服务的 IPC 连接意外断开时的系统回调（例如后台进程被系统 OOM 终止）。
         *
         * @param name 组件名称
         */
        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(this@BatteryServiceBridge) {
                serviceBinder = null
                isBound = false
            }
            triggerAutoRebind()
        }
    }

    /**
     * 注册后台监控服务 Binder 连接就绪监听器。
     * 若当前已经连接就绪，则立即同步回调一次。
     *
     * @param listener 连接建立时的回调闭包
     */
    fun addOnConnectedListener(listener: () -> Unit) {
        connectionListeners.add(listener)
        if (isConnected()) {
            try {
                listener()
            } catch (_: Throwable) {}
        }
    }

    /**
     * 注销后台监控服务 Binder 连接就绪监听器。
     *
     * @param listener 待注销的回调闭包
     */
    fun removeOnConnectedListener(listener: () -> Unit) {
        connectionListeners.remove(listener)
    }

    /**
     * 协程挂起等待后台监控服务的 AIDL Binder 连接建立。
     * 在后台 IO 或 Default 协程中调用，若尚未连接则按指定超时时间轮询挂起等待，绝不阻塞主线程。
     *
     * @param timeoutMs 最长挂起等待毫秒数（默认 400ms）
     * @return 若在超时时间内成功建立连接返回 true，否则返回 false
     */
    suspend fun awaitServiceConnected(timeoutMs: Long = 400L): Boolean {
        if (isConnected()) return true
        val startTs = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTs < timeoutMs) {
            if (isConnected()) return true
            kotlinx.coroutines.delay(20L)
        }
        return isConnected()
    }

    /**
     * 在主 UI 进程中发起与独立后台监控服务的异步 AIDL 绑定。
     *
     * @param context 应用程序上下文
     * @return 发起绑定是否成功（返回 true 表示系统已受理绑定请求或当前已就绪）
     */
    fun bindService(context: Context): Boolean {
        val appContext = context.applicationContext
        lastAppContext = appContext
        if (isConnected()) return true
        val intent = Intent(appContext, BatteryMonitorService::class.java)
        return try {
            val bound = appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            isBound = bound
            bound
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 确保后台电池监控服务处于活跃运行状态并在主进程建立 Binder 绑定连接。
     * 适合在主 UI 界面处于前台活跃状态（如 onResume、下拉刷新等看门狗生命周期）时调用。
     * 若用户配置了开启监控，但当前未连接或未运行，则立即拉起服务并触发异步绑定。
     *
     * @param context 应用程序上下文
     * @return 若当前已连接就绪或已成功发起拉起绑定返回 true，否则返回 false
     */
    fun ensureServiceRunningAndBound(context: Context): Boolean {
        val appContext = context.applicationContext
        lastAppContext = appContext
        if (!BatteryMonitorService.shouldServiceRun(appContext)) {
            return false
        }
        if (isConnected()) {
            return true
        }
        BatteryMonitorService.start(appContext)
        return bindService(appContext)
    }

    /**
     * 解除主 UI 进程与后台监控服务的 AIDL 绑定，释放客户端 Binder 句柄。
     *
     * @param context 应用程序上下文
     */
    fun unbindService(context: Context) {
        if (!isBound) return
        val appContext = context.applicationContext
        try {
            serviceBinder?.asBinder()?.unlinkToDeath(deathRecipient, 0)
        } catch (_: Throwable) {}
        try {
            appContext.unbindService(serviceConnection)
        } catch (_: Throwable) {}
        synchronized(this) {
            serviceBinder = null
            isBound = false
        }
    }

    /**
     * 检测主 UI 进程当前是否与独立后台监控服务保持活跃的 IPC 连接。
     *
     * @return 若已连接返回 true，否则返回 false
     */
    fun isConnected(): Boolean {
        val binder = serviceBinder
        return binder != null && binder.asBinder().isBinderAlive
    }

    /**
     * 查询后台监控服务自身是否处于物理采样活跃运行状态。
     * 严格通过 AIDL 从后台服务进程查询真实物理采样状态，未连接时如实返回 false，绝不虚假保底。
     *
     * @param context 应用程序上下文
     * @return 若处于活跃运行中返回 true，否则返回 false
     */
    fun isServiceRunning(context: Context): Boolean {
        lastAppContext = context.applicationContext
        val proxy = serviceBinder
        if (proxy != null && proxy.asBinder().isBinderAlive) {
            try {
                return proxy.isServiceRunning
            } catch (_: Throwable) {}
        }
        return false
    }

    /**
     * 通知后台监控服务当前宿主 UI 界面是否处于最前台可见活跃状态。
     *
     * @param foreground 是否处于前台活跃状态
     */
    fun notifyHostAppForeground(foreground: Boolean) {
        val proxy = serviceBinder
        if (proxy != null && proxy.asBinder().isBinderAlive) {
            try {
                proxy.notifyHostAppForeground(foreground)
            } catch (_: Throwable) {}
        }
        // 同步设置主进程内部标记
        BatteryMonitorService.setHostAppForeground(foreground)
    }

    /**
     * 获取最新瞬时电池物理运行状态快照。
     * 优先通过 AIDL 从后台监控进程读取无滤波实时数据；
     * 若 IPC 未就绪，自动回退到本地单例查询。
     *
     * @param context 应用程序上下文
     * @return 电池物理快照 [PowerUsageManager.BatteryStatusSnapshot]，获取失败返回 null
     */
    fun getLiveBatteryStatus(context: Context): BatteryStatusSnapshot? {
        val proxy = serviceBinder
        if (proxy != null && proxy.asBinder().isBinderAlive) {
            try {
                val json = proxy.liveBatteryStatusJson
                if (!json.isNullOrEmpty()) {
                    val snapshot = PowerUsageManager.parseBatteryStatusFromJson(json)
                    if (snapshot != null) return snapshot
                }
            } catch (_: Throwable) {}
        }
        return try {
            PowerUsageManager.getInstance(context).getCurrentBatteryStatus()
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 获取当前放电周期的秒级瞬时采样点列表。
     * 优先通过 AIDL 从后台监控进程内存中读取最新点集；
     * 若 IPC 未就绪，自动回退到共享磁盘持久化文件加载。
     *
     * @param context 应用程序上下文
     * @return 放电瞬时采样点列表
     */
    fun getDischargeRealtimeSamples(context: Context): List<PowerDischargePoint> {
        val proxy = serviceBinder
        if (proxy != null && proxy.asBinder().isBinderAlive) {
            try {
                val json = proxy.dischargeRealtimeSamplesJson
                if (!json.isNullOrEmpty()) {
                    val points = PowerUsageManager.parseDischargeSamplesFromJson(json)
                    if (points.isNotEmpty()) return points
                }
            } catch (_: Throwable) {}
        }
        return try {
            PowerUsageManager.getInstance(context).getDischargeRealtimeSamples()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * 获取当前放电周期的常驻物理微积分能量累加器状态 JSON 字符串。
     * 优先通过 AIDL 从后台监控进程读取最新累加器数据；
     * 若 IPC 未就绪，自动回退到本地单例查询。
     *
     * @param context 应用程序上下文
     * @return 放电累加器状态序列化的 JSON 字符串，若获取失败返回空字符串
     */
    fun getDischargeAccumulatorJson(context: Context): String {
        val proxy = serviceBinder
        if (proxy != null && proxy.asBinder().isBinderAlive) {
            try {
                val json = proxy.dischargeAccumulatorJson
                if (!json.isNullOrEmpty()) return json
            } catch (_: Throwable) {}
        }
        return try {
            PowerUsageManager.getInstance(context).getDischargeAccumulatorAsJson()
        } catch (_: Throwable) {
            ""
        }
    }

    /**
     * 获取当前或最近一次充电会话的物理摘要信息。
     * 优先通过 AIDL 从后台监控进程获取最新动态摘要；
     * 若 IPC 未就绪，自动回退到本地单例读取。
     *
     * @param context 应用程序上下文
     * @return 充电会话摘要实体 [ChargingSessionSummary]
     */
    fun getChargingSessionSummary(context: Context): ChargingSessionSummary {
        val proxy = serviceBinder
        if (proxy != null && proxy.asBinder().isBinderAlive) {
            try {
                val json = proxy.chargingSessionSummaryJson
                if (!json.isNullOrEmpty()) {
                    return ChargingStatsManager.parseSummaryFromJson(json)
                }
            } catch (_: Throwable) {}
        }
        return try {
            ChargingStatsManager.getInstance(context).getCurrentSummary()
        } catch (_: Throwable) {
            ChargingSessionSummary()
        }
    }

    /**
     * 获取当前充电会话的物理轨迹采样点列表。
     * 优先通过 AIDL 从后台监控进程内存中获取最新轨迹；
     * 若 IPC 未就绪，自动回退到本地单例读取。
     *
     * @param context 应用程序上下文
     * @return 充电轨迹采样点列表
     */
    fun getChargingSamplePoints(context: Context): List<ChargingSamplePoint> {
        val proxy = serviceBinder
        if (proxy != null && proxy.asBinder().isBinderAlive) {
            try {
                val json = proxy.chargingSamplePointsJson
                if (!json.isNullOrEmpty()) {
                    val points = ChargingStatsManager.parseSamplePointsFromJson(json)
                    if (points.isNotEmpty()) return points
                }
            } catch (_: Throwable) {}
        }
        return try {
            ChargingStatsManager.getInstance(context).getSamplePoints()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * 通知后台监控服务强制将当前内存中的所有瞬时数据立即刷入持久化磁盘。
     */
    fun forceFlushToDisk() {
        val proxy = serviceBinder
        if (proxy != null && proxy.asBinder().isBinderAlive) {
            try {
                proxy.forceFlushToDisk()
            } catch (_: Throwable) {}
        }
    }

    /**
     * 通过 AIDL 向后台监控服务推送最新的运行配置参数。
     *
     * @param context 应用程序上下文
     * @param statsEnabled 是否启用充放电统计
     * @param notificationEnabled 是否在通知栏常驻显示
     * @param screenOnIntervalMs 亮屏监控刷新间隔毫秒数（-1L 为不采样）
     * @param screenOffIntervalMs 息屏待机采样间隔毫秒数（0L 为智能省电，-1L 为不采样）
     * @return 若通过 AIDL 成功推送返回 true，若未绑定或调用失败返回 false
     */
    fun updateConfig(
        context: Context,
        statsEnabled: Boolean,
        notificationEnabled: Boolean,
        screenOnIntervalMs: Long,
        screenOffIntervalMs: Long
    ): Boolean {
        val proxy = serviceBinder
        if (proxy != null && proxy.asBinder().isBinderAlive) {
            try {
                proxy.updateConfig(statsEnabled, notificationEnabled, screenOnIntervalMs, screenOffIntervalMs)
                return true
            } catch (_: Throwable) {}
        }
        bindService(context)
        return false
    }
}
