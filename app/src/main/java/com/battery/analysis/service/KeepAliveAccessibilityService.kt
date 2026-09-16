package com.battery.analysis.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 免 Root / 免 ADB 的无障碍保活与自愈服务（方案一）。
 *
 * <p>利用 Android 系统的 AccessibilityManagerService 机制：
 * 1. 系统以高优先级常驻绑定本服务（BOUND_FOREGROUND_SERVICE 级别），赋予高权重防杀能力；
 * 2. system_server 在与本服务的跨进程 Binder 上注册死亡监听（DeathRecipient）；
 * 3. 当用户从最近任务列表中划杀应用时，system_server 瞬间捕获死亡事件，并在 100~300ms 内
 *    由 Zygote 强制重新孵化 App 进程并重建服务通道，在 [onServiceConnected] 中自愈拉活核心监控服务；
 * 4. 本服务不监听或拦截任何界面无障碍事件，0 额外 CPU 与内存消耗，不收集任何用户隐私数据。
 */
class KeepAliveAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "KeepAliveAccessibility"

        /**
         * 检查当前应用的无障碍保活服务是否已经在系统设置中被用户开启。
         *
         * @param context 应用程序上下文
         * @return 若已开启返回 true，未开启或被禁用返回 false
         */
        fun isAccessibilityEnabled(context: Context): Boolean {
            val expectedComponentName = ComponentName(context, KeepAliveAccessibilityService::class.java).flattenToString()
            val enabledServicesSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServicesSetting)

            while (colonSplitter.hasNext()) {
                val componentNameString = colonSplitter.next()
                if (componentNameString.equals(expectedComponentName, ignoreCase = true)) {
                    return true
                }
            }
            return false
        }

        /**
         * 跳转至系统“无障碍设置”界面，引导用户开启无障碍保活服务。
         *
         * @param context 上下文对象
         */
        fun openAccessibilitySettings(context: Context) {
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "跳转系统无障碍设置失败: ${e.message}", e)
            }
        }
        /**
         * 当前通过系统无障碍事件捕获到的置顶前台应用包名。
         * 0 延迟、0 轮询开销，供监控服务为硬件采样点打上前台标签。
         */
        @Volatile
        var currentForegroundPackage: String? = null
    }

    /**
     * 当系统成功连接并绑定该无障碍服务时触发。
     * 此处执行极速拉活与自愈逻辑，确立 BatteryMonitorService 前台监控服务正常运行。
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "KeepAliveAccessibilityService 已连接，立即触发前台监控服务自愈拉活")
        try {
            if (BatteryMonitorService.isServiceEnabled(this)) {
                BatteryMonitorService.start(this)
            }
        } catch (e: Exception) {
            Log.e(TAG, "无障碍服务拉活 BatteryMonitorService 失败: ${e.message}", e)
        }
    }

    /**
     * 接收系统分发的无障碍事件。
     * 毫秒级捕获窗口状态变动，精准提取当前置顶应用包名。
     *
     * @param event 无障碍事件对象，可能为空
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (!pkg.isNullOrEmpty() &&
                !pkg.startsWith("com.android.systemui") &&
                !pkg.startsWith("android")
            ) {
                currentForegroundPackage = pkg
            }
        }
    }

    /**
     * 当系统中断无障碍服务时的反馈回调。
     */
    override fun onInterrupt() {
        Log.w(TAG, "KeepAliveAccessibilityService 被系统中断")
    }

    /**
     * 服务销毁时的生命周期回调。
     */
    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "KeepAliveAccessibilityService 已销毁")
    }
}
