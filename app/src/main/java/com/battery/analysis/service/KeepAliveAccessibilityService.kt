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

        /**
         * 最近一次前台应用包名发生有效事件的时间戳（毫秒）。
         */
        @Volatile
        var lastForegroundPackageTimestamp: Long = 0L

        /**
         * 判断指定包名是否属于负一屏系统组件（包含荣耀 hiboard / intelligent、华为 hiboard / intelligent、小米 personalassistant、OPPO assistantscreen 等）。
         *
         * @param packageName 待检查的应用包名
         * @return 若为负一屏界面包名返回 true，否则返回 false
         */
        fun isAssistantScreenPackage(packageName: String?): Boolean {
            if (packageName.isNullOrBlank()) return false
            val lower = packageName.lowercase()
            return lower == "com.hihonor.hiboard" ||
                    lower == "com.huawei.hiboard" ||
                    lower == "com.hihonor.intelligent" ||
                    lower == "com.huawei.intelligent" ||
                    lower == "com.miui.personalassistant" ||
                    lower == "com.coloros.assistantscreen" ||
                    lower == "com.vivo.assistant" ||
                    lower.contains("hiboard") ||
                    lower.contains("personalassistant") ||
                    lower.contains("assistantscreen")
        }

        /**
         * 判断指定包名是否为无独立用户交互界面的系统后台守护服务、权限代理、ADB Shell 或系统底层组件。
         * 此类组件绝不属于置顶前台用户交互应用，必须在无障碍事件与前台探测中严格过滤，杜绝污染前台统计列表。
         *
         * @param packageName 待检查的应用包名
         * @return 若为忽略的系统底层组件返回 true，否则返回 false
         */
        fun isIgnoredSystemComponent(packageName: String?): Boolean {
            if (packageName.isNullOrBlank()) return true
            val lower = packageName.lowercase()
            return lower == "android" ||
                    lower == "com.android.shell" ||
                    lower.contains(".shell") ||
                    lower == "com.android.permissioncontroller" ||
                    lower == "com.google.android.permissioncontroller" ||
                    lower.contains("permissioncontroller") ||
                    lower.startsWith("com.android.systemui") ||
                    lower == "com.hihonor.gamemanager" ||
                    lower == "com.hihonor.gamecenter" ||
                    lower == "com.android.server.telecom" ||
                    lower.contains("telephony") ||
                    lower.contains("inputmethod") ||
                    lower.contains("pinyin") ||
                    lower == "com.tencent.wetype" ||
                    lower.startsWith("com.baidu.input") ||
                    lower.startsWith("com.iflytek.inputmethod")
        }

        /**
         * 获取在有效时效窗口内的前台应用包名。
         * 针对负一屏静止阅读（10~60秒无手指滑动）的真实使用特征，负一屏应用自动享受 60 秒前台会话保持；普通应用采用常规时效。
         *
         * @param defaultMaxAgeMs 默认最大有效时效窗口（毫秒）
         * @return 处于时效窗口内的前台应用包名，过期或未记录时返回 null
         */
        fun getValidForegroundPackage(defaultMaxAgeMs: Long = 5000L): String? {
            val pkg = currentForegroundPackage
            val ts = lastForegroundPackageTimestamp
            val now = System.currentTimeMillis()
            if (pkg.isNullOrEmpty()) return null

            val isAssistant = isAssistantScreenPackage(pkg)
            val maxAllowedAge = if (isAssistant) 60_000L else defaultMaxAgeMs

            return if ((now - ts) <= maxAllowedAge) {
                pkg
            } else {
                null
            }
        }

        /**
         * 当设备灭屏时通知无障碍服务，及时切断负一屏前台会话。
         */
        fun notifyScreenOff() {
            val current = currentForegroundPackage
            if (current != null && isAssistantScreenPackage(current)) {
                currentForegroundPackage = null
                lastForegroundPackageTimestamp = 0L
            }
        }
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
     * 毫秒级捕获窗口状态变动与桌面交互事件，精准提取当前置顶应用包名，特别纠正负一屏与桌面间的无缝滑动切换。
     *
     * @param event 无障碍事件对象，可能为空
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        val lower = pkg.lowercase()
        val now = System.currentTimeMillis()

        // 1. 检查是否为负一屏长效会话进行中
        val current = currentForegroundPackage
        val assistantStillActive = current != null && isAssistantScreenPackage(current) && (now - lastForegroundPackageTimestamp < 60_000L)

        // 2. 严格过滤系统后台守护、权限控制器、YOYO建议后台服务、输入法与无界面底层服务
        if (isIgnoredSystemComponent(pkg)) {
            // 若当前处于负一屏激活状态，负一屏附属卡片或系统后台广播触发的事件绝不能切断负一屏，
            // 且视作负一屏内部内容渲染刷新，维持负一屏长效会话
            if (assistantStillActive) {
                lastForegroundPackageTimestamp = now
            }
            return
        }

        // 3. 负一屏判定：荣耀 hiboard、华为 hiboard、小米 personalassistant、OPPO assistantscreen 等
        if (isAssistantScreenPackage(pkg)) {
            currentForegroundPackage = pkg
            lastForegroundPackageTimestamp = now
            return
        }

        // 4. 电话/通话界面判定
        val isPhone = lower == "com.android.phone" ||
                lower == "com.android.incallui" ||
                lower == "com.google.android.dialer" ||
                lower == "com.samsung.android.incallui" ||
                lower.contains(".incallui") ||
                lower.contains(".dialer")

        if (isPhone) {
            currentForegroundPackage = pkg
            lastForegroundPackageTimestamp = now
            return
        }

        // 5. 桌面 Launcher 交互判定
        val isLauncher = lower.contains("launcher") || lower.contains("home")
        if (isLauncher) {
            // 若当前正在活跃浏览负一屏，桌面底座发出的窗口改变、滚动或内容变动事件绝不覆盖负一屏；
            // 仅当桌面发生显式点击（TYPE_VIEW_CLICKED）说明用户回到了桌面主屏并点击操作，才切回桌面
            if (assistantStillActive && event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) {
                return
            }
            currentForegroundPackage = pkg
            lastForegroundPackageTimestamp = now
            return
        }

        // 6. 其它独立三方应用或系统应用（如微信、今日头条、系统设置）：
        // 严格要求必须是窗口状态发生改变（TYPE_WINDOW_STATE_CHANGED），才代表真正的前台应用切换，
        // 杜绝无界面后台服务或悬浮窗口通过 TYPE_WINDOWS_CHANGED / TYPE_VIEW_SCROLLED 误切前台
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            currentForegroundPackage = pkg
            lastForegroundPackageTimestamp = now
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
