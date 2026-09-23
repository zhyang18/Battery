package com.battery.analysis

import android.app.Application
import android.os.Build
import rikka.shizuku.ShizukuProvider

/**
 * 电池统计应用程序全局 Application 类。
 *
 * 负责在各独立进程（包括主 UI 进程与独立后台监控进程 :monitor）启动阶段
 * 统一执行必要的基础设施初始化与 Shizuku 多进程跨进程 Binder 支持配置。
 */
class BatteryApp : Application() {

    /**
     * 获取当前进程名称。
     *
     * @return 当前运行进程名（如 "com.battery.analysis" 或 "com.battery.analysis:monitor"）
     */
    private fun getCurrentProcessName(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            try {
                java.io.File("/proc/self/cmdline").readText().trim().trimEnd('\u0000')
            } catch (_: Throwable) {
                packageName
            }
        }
    }

    /**
     * 应用程序进程创建生命周期回调。
     * 在主 UI 进程与 :monitor 独立进程启动时分别调用，
     * 注册 Shizuku 多进程 ContentProvider 与 Binder 通信支持，
     * 确保各子进程均能正常通过 Shizuku Binder 访问系统底层特权接口。
     */
    override fun onCreate() {
        super.onCreate()
        try {
            val procName = getCurrentProcessName()
            val isMainProcess = (procName == packageName)
            // 启用 Shizuku 内置多进程支持：主进程（持有 ShizukuProvider）传入 true，次进程（如 :monitor）传入 false
            ShizukuProvider.enableMultiProcessSupport(isMainProcess)
            if (!isMainProcess) {
                // 在非 Provider 子进程中主动向主进程请求分发 Shizuku Binder
                ShizukuProvider.requestBinderForNonProviderProcess(this)
            }
        } catch (_: Throwable) {
        }
    }
}
