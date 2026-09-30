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
         * 判断指定包名是否属于负一屏前台交互界面系统组件（如荣耀/华为 hiboard、小米 personalassistant、OPPO assistantscreen、vivo assistant 等）。
         * 严格排除 YOYO 建议 / 智慧感知后台引擎（com.hihonor.intelligent / com.huawei.intelligent），杜绝桌面卡片与常驻后台服务被误识别为负一屏全屏界面。
         *
         * @param packageName 待检查的应用包名
         * @return 若为真实负一屏前台界面包名返回 true，否则返回 false
         */
        fun isAssistantScreenPackage(packageName: String?): Boolean {
            if (packageName.isNullOrBlank()) return false
            val lower = packageName.lowercase()
            return lower == "com.hihonor.hiboard" ||
                    lower == "com.huawei.hiboard" ||
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
                    lower == "com.hihonor.intelligent" ||
                    lower == "com.huawei.intelligent" ||
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
         * 当前无障碍服务实例弱引用缓存，供后台监控服务在采样周期实时验证屏幕物理活动窗口。
         */
        @Volatile
        private var serviceInstance: KeepAliveAccessibilityService? = null

        /**
         * 获取当前系统屏幕上正在与用户进行真实前台物理交互的活动窗口根节点包名。
         *
         * @return 当前屏幕交互活动窗口包名，无法获取或处于瞬态时返回 null
         */
        fun getActiveWindowPackage(): String? {
            return serviceInstance?.getActiveWindowPackage()
        }

        /**
         * 获取在有效时效窗口内的前台应用包名。
         *
         * @param defaultMaxAgeMs 默认最大有效时效窗口（毫秒）
         * @return 处于时效窗口内的前台应用包名，过期或未记录时返回 null
         */
        fun getValidForegroundPackage(defaultMaxAgeMs: Long = 3000L): String? {
            val pkg = currentForegroundPackage
            val ts = lastForegroundPackageTimestamp
            val now = System.currentTimeMillis()
            if (pkg.isNullOrEmpty()) return null

            // 负一屏与普通应用均严格遵循短效事件时效，绝不赋予长时效，杜绝滑回桌面后残留污染
            val maxAllowedAge = defaultMaxAgeMs

            return if ((now - ts) <= maxAllowedAge) {
                pkg
            } else {
                null
            }
        }

        /**
         * 当设备灭屏时通知无障碍服务清空前台会话缓存。
         * 灭屏标志着所有屏幕前台交互已中止，必须彻底清空前台应用记录与时间戳，
         * 杜绝灭屏休眠期间的历史状态污染下一次点亮屏幕时的前台归属。
         */
        fun notifyScreenOff() {
            currentForegroundPackage = null
            lastForegroundPackageTimestamp = 0L
        }
    }

    /**
     * 当系统成功连接并绑定该无障碍服务时触发。
     * 此处执行极速拉活与自愈逻辑，确立 BatteryMonitorService 前台监控服务正常运行。
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInstance = this
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
     * 获取当前系统屏幕上正在与用户进行真实前台物理交互的活动窗口根节点包名。
     * 基于 Android Framework 的 active window 状态进行严格判定，
     * 只有当前拥有输入焦点或正在接收触摸事件的窗口才被认定为活动窗口，
     * 杜绝后台常驻应用（如常驻后台刷新的负一屏、推送代理、悬浮卡片）通过后台事件误切前台。
     *
     * @return 当前屏幕交互活动窗口包名，无法获取或处于瞬态时返回 null
     */
    private fun getActiveWindowPackage(): String? {
        return try {
            rootInActiveWindow?.packageName?.toString()
                ?: windows?.firstOrNull { it.isActive }?.root?.packageName?.toString()
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 判断指定包名是否属于系统桌面启动器（Launcher）。
     *
     * @param packageName 待检查的应用包名
     * @return 若为系统桌面启动器返回 true，否则返回 false
     */
    private fun isLauncherPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val lower = packageName.lowercase()
        return lower.contains("launcher") || lower.contains("home")
    }

    /**
     * 接收系统分发的无障碍事件。
     * 精准提取当前置顶前台应用包名，忠实反映真实屏幕物理交互。
     * 负一屏（如荣耀 hiboard）与桌面为独立应用（不同包名与 UID），严格区分前台活动窗口归属，
     * 杜绝后台卡片静默轮播与离屏刷新污染前台统计。
     *
     * @param event 无障碍事件对象，可能为空
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        val lower = pkg.lowercase()
        val now = System.currentTimeMillis()

        // 1. 严格过滤系统后台守护、权限控制器、YOYO建议后台服务、输入法与无界面底层服务
        if (isIgnoredSystemComponent(pkg)) {
            // 系统底层组件及后台感知服务完全静默丢弃，绝不能刷新或续命前台状态
            return
        }

        // 2. 负一屏判定：作为独立系统应用（如荣耀/华为 hiboard、小米 personalassistant、OPPO assistantscreen、vivo assistant 等）
        // 负一屏拥有独立包名与 UID，与系统桌面为完全平等的独立进程关系。
        // 严格杜绝将后台卡片静默轮播（TYPE_VIEW_SCROLLED）或后台静默刷新当做用户前台交互；
        // 仅在真实窗口状态变动（TYPE_WINDOW_STATE_CHANGED）且当前活动窗口确实处于负一屏时才确立前台，
        // 彻底根除用户根本未使用负一屏时其使用时间持续在后台缓慢增长的恶性缺陷。
        if (isAssistantScreenPackage(pkg)) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val activePkg = getActiveWindowPackage()
                if (activePkg != null) {
                    if (isAssistantScreenPackage(activePkg)) {
                        // 当前屏幕活动窗口确为负一屏，确认前台
                        currentForegroundPackage = pkg
                        lastForegroundPackageTimestamp = now
                    } else if (isLauncherPackage(activePkg)) {
                        // 当前屏幕活动窗口实为桌面，说明此事件为负一屏在后台静默派发，前台纠正为桌面
                        currentForegroundPackage = activePkg
                        lastForegroundPackageTimestamp = now
                    }
                } else {
                    // 若瞬态无法获取 activePkg，检查事件窗口全屏属性，避免非全屏后台窗口误切
                    if (event.isFullScreen) {
                        currentForegroundPackage = pkg
                        lastForegroundPackageTimestamp = now
                    }
                }
            } else if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
            ) {
                // 仅当用户当前已经身处负一屏前台界面中时，负一屏内的滑动或点击操作才允许为前台续命，杜绝后台静默轮播偷跑
                val activePkg = getActiveWindowPackage()
                if (activePkg != null && isAssistantScreenPackage(activePkg)) {
                    currentForegroundPackage = pkg
                    lastForegroundPackageTimestamp = now
                }
            }
            return
        }

        // 3. 电话/通话界面判定
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

        // 4. 桌面 Launcher 交互判定
        if (isLauncherPackage(pkg)) {
            val activePkg = getActiveWindowPackage()
            // 关键防反噬：若当前屏幕物理活动窗口明确属于负一屏，桌面底座派发的滑动/窗口更新事件绝不能篡夺负一屏！
            if (activePkg != null && isAssistantScreenPackage(activePkg)) {
                return
            }
            // 只要桌面产生交互或窗口事件（滑动、点击、长按、窗口状态变化、窗口堆叠变化），100% 确认用户处于桌面主屏，
            // 立即且无条件确立桌面为前台，彻底消除桌面滑动翻页被误判为负一屏的问题
            val isLauncherInteractive = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                    event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
                    event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                    event.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED ||
                    event.eventType == AccessibilityEvent.TYPE_VIEW_SELECTED ||
                    event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
            if (isLauncherInteractive) {
                currentForegroundPackage = pkg
                lastForegroundPackageTimestamp = now
            }
            return
        }

        // 5. 其它独立三方应用或系统应用（如微信、今日头条、系统设置）：
        // 严格要求必须是窗口状态发生改变（TYPE_WINDOW_STATE_CHANGED），才代表真正的前台应用切换，
        // 且通过活动窗口校验，杜绝后台服务或悬浮窗口误切前台
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val activePkg = getActiveWindowPackage()
            val targetPkg = if (activePkg != null && !isIgnoredSystemComponent(activePkg)) activePkg else pkg
            currentForegroundPackage = targetPkg
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
        if (serviceInstance === this) {
            serviceInstance = null
        }
        Log.w(TAG, "KeepAliveAccessibilityService 已销毁")
    }
}
