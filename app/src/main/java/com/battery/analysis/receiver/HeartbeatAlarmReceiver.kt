package com.battery.analysis.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.battery.analysis.service.BatteryMonitorService

/**
 * AlarmManager 心跳广播接收器（参考 BatteryRecorder 核心技术路线）。
 *
 * 工作原理：
 * - [BatteryMonitorService] 启动时通过 AlarmManager 注册一个 15 分钟后触发的单次精确 Alarm；
 * - 服务正常运行时，每次收到 Alarm 触发（[onStartCommand]）后会续期下一次 Alarm，形成链式心跳；
 * - 若服务被 OOM Killer 强杀，进程会在 15 分钟内被此 Alarm 重新唤醒，
 *   [onReceive] 检测到服务已配置为启用后，自动重新拉起 [BatteryMonitorService]，实现无人值守自愈重启；
 * - 用户主动关闭服务时，[BatteryMonitorService.cancelHeartbeatAlarm] 会取消 Alarm，心跳链终止。
 */
class HeartbeatAlarmReceiver : BroadcastReceiver() {

    /**
     * 接收到心跳 Alarm 广播时的处理回调。
     * 若服务配置为应运行（用户未主动关闭），则自动重新拉起前台监控服务。
     *
     * @param context 应用程序上下文
     * @param intent 包含心跳 Alarm action 的广播意图
     */
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != BatteryMonitorService.ACTION_HEARTBEAT_ALARM) return
        val appContext = context.applicationContext
        try {
            if (BatteryMonitorService.isServiceEnabled(appContext)) {
                // 服务仍配置为启用：无论当前是否在运行，均尝试启动（系统会幂等处理已运行的情况）
                BatteryMonitorService.start(appContext)
            }
            // 服务未配置为启用（用户主动关闭）：不重启，心跳链自然终止
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
