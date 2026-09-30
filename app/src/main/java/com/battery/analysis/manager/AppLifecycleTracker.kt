package com.battery.analysis.manager

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * 全局应用生命周期状态追踪器。
 * 通过监听全局 Activity 的启动与停止，精确判定应用是处于前台、后台还是正在经历前后台切换（冷启动与后台唤醒）。
 * 同时统一维护耗电统计上次刷新的时间戳，支撑“冷启动或后台启动 1 分钟内防重复刷新、应用内切换或返回不刷新”的业务策略。
 */
object AppLifecycleTracker : Application.ActivityLifecycleCallbacks {

    /** 耗电统计偏好配置文件名称 */
    private const val PREFS_NAME = "power_lifecycle_tracker_prefs"

    /** 耗电统计上次刷新时间戳持久化存储键名 */
    private const val KEY_LAST_DISCHARGE_REFRESH_TIME = "last_discharge_refresh_time"

    /** 耗电统计防重复刷新最小时间门限阈值（60,000毫秒，即 1 分钟） */
    const val REFRESH_INTERVAL_THRESHOLD_MS = 60_000L

    /** 应用程序全局上下文引用缓存 */
    private var applicationContext: Application? = null

    /** 活跃处于 started 状态的 Activity 计数器 */
    private var startedActivityCount: Int = 0

    /** 标记是否刚经历冷启动或从后台切换回前台 */
    @Volatile
    private var hasForegroundTransition: Boolean = true

    /** 耗电统计页面上一次成功执行刷新的绝对时间戳（毫秒） */
    @Volatile
    var lastDischargeRefreshTimeMillis: Long = 0L

    /** 标记全局监听器是否已向 Application 完成注册 */
    private var isRegistered: Boolean = false

    /**
     * 向目标 Application 实例初始化并注册全局 Activity 生命周期监听。
     * 同步从本地持久化偏好设置中恢复上次刷新时间戳，确保应用在经历进程被杀或冷启动后依然持有真实历史刷新时刻。
     *
     * @param application 应用程序全局上下文实例
     */
    fun init(application: Application) {
        this.applicationContext = application
        if (!isRegistered) {
            application.registerActivityLifecycleCallbacks(this)
            isRegistered = true
        }

        val prefs = application.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        lastDischargeRefreshTimeMillis = prefs.getLong(KEY_LAST_DISCHARGE_REFRESH_TIME, 0L)
    }

    /**
     * 判定当前界面可见回调（onResume）是否应当执行耗电数据刷新。
     *
     * 规则：
     * 1. 若当前界面尚未加载过任何数据且无法获取缓存（首屏初次冷启动且未渲染），必须执行刷新；
     * 2. 若当前仅仅是在 App 内部页面跳转返回或底部页签切换（未切出后台），不刷新；
     * 3. 若为冷启动或从后台启动重新进入前台：
     *    - 距离上次刷新时间超过 1 分钟（60,000ms）或从未刷新过，正常刷新；
     *    - 距离上次刷新时间在 1 分钟内，不刷新。
     *
     * @param hasRenderedData 当前界面是否已持有有效且渲染完毕的耗电数据包（包含从持久化缓存恢复的数据）
     * @return 若满足刷新时机返回 true，否则返回 false
     */
    fun shouldRefreshPowerStats(hasRenderedData: Boolean): Boolean {
        if (!hasRenderedData) {
            hasForegroundTransition = false
            return true
        }

        // 检查是否发生过前后台切换（冷启动或后台切回）
        if (hasForegroundTransition) {
            val now = System.currentTimeMillis()
            val timeSinceLastRefresh = now - lastDischargeRefreshTimeMillis

            // 无论是否刷新，消费本次切前台事件
            hasForegroundTransition = false

            // 上次刷新距今超过 1 分钟（或从未刷新过），允许正常刷新
            return lastDischargeRefreshTimeMillis <= 0L || timeSinceLastRefresh > REFRESH_INTERVAL_THRESHOLD_MS
        }

        // 处于纯 App 内部导航/返回或底部页签切换状态，不触发刷新
        return false
    }

    /**
     * 记录耗电统计数据刚刚完成了一次刷新并成功渲染。
     * 更新内存时间戳的同时，将时间戳异步持久化至本地磁盘 SharedPreferences 中。
     *
     * @param timestamp 刷新完成的绝对时间戳（毫秒），默认取系统当前时间
     */
    fun markDischargeRefreshed(timestamp: Long = System.currentTimeMillis()) {
        lastDischargeRefreshTimeMillis = timestamp
        applicationContext?.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            ?.edit()
            ?.putLong(KEY_LAST_DISCHARGE_REFRESH_TIME, timestamp)
            ?.apply()
    }

    /**
     * Activity 创建生命周期回调。
     *
     * @param activity 目标 Activity 实例
     * @param savedInstanceState 保存的状态 Bundle
     */
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

    /**
     * Activity 可见生命周期回调。累加前台 Activity 计数，并在从 0 变为 1 时捕获进入前台事件。
     *
     * @param activity 目标 Activity 实例
     */
    override fun onActivityStarted(activity: Activity) {
        if (startedActivityCount == 0) {
            // 从后台切回前台（或冷启动）
            hasForegroundTransition = true
        }
        startedActivityCount++
    }

    /**
     * Activity 恢复交互生命周期回调。
     *
     * @param activity 目标 Activity 实例
     */
    override fun onActivityResumed(activity: Activity) {}

    /**
     * Activity 暂停交互生命周期回调。
     *
     * @param activity 目标 Activity 实例
     */
    override fun onActivityPaused(activity: Activity) {}

    /**
     * Activity 退出可见生命周期回调。递减前台计数，并在降为 0 时捕获退到后台事件。
     *
     * @param activity 目标 Activity 实例
     */
    override fun onActivityStopped(activity: Activity) {
        startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
    }

    /**
     * Activity 状态持久化生命周期回调。
     *
     * @param activity 目标 Activity 实例
     * @param outState 输出状态 Bundle
     */
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    /**
     * Activity 销毁生命周期回调。
     *
     * @param activity 目标 Activity 实例
     */
    override fun onActivityDestroyed(activity: Activity) {}

    /**
     * 重置内部状态供单元测试使用。
     *
     * @param refreshTime 模拟设定的上次刷新时间戳（毫秒）
     * @param startedCount 模拟当前处于 started 状态的 Activity 计数
     * @param hasTransition 模拟是否发生过切前台事件
     */
    fun resetForTest(
        refreshTime: Long = 0L,
        startedCount: Int = 0,
        hasTransition: Boolean = true
    ) {
        lastDischargeRefreshTimeMillis = refreshTime
        startedActivityCount = startedCount
        hasForegroundTransition = hasTransition
    }
}
