package com.battery.analysis.ui

import android.content.Context
import com.battery.analysis.R
import com.battery.analysis.databinding.LayoutBatteryParamItemsBinding
import com.battery.analysis.model.BatteryInfo

/**
 * 电池详细参数视图绑定辅助工具类。
 * 负责将 [BatteryInfo] 实体中的电量、健康度、电压、电流等各项数据格式化并绑定至通用的参数列表 UI 布局，
 * 且全面支持根据当前多语言环境进行本地化状态文本映射（中英文自适应）。
 */
object BatteryParamViewBinder {

    /**
     * 将给定的电池数据对象绑定渲染到指定的视图绑定对象上。
     *
     * @param binding 包含通用参数展示控件的 [LayoutBatteryParamItemsBinding]
     * @param info 待展示的电池信息对象，若为 null 则展示未获取数据的占位文本
     */
    fun bind(binding: LayoutBatteryParamItemsBinding, info: BatteryInfo?) {
        val context: Context = binding.root.context
        val unknownText = context.getString(R.string.unknown)

        if (info == null) {
            binding.tvValLevel.text = "—"
            binding.tvValStatus.text = "—"
            binding.tvValHealthStatus.text = "—"
            binding.tvValHealthPercent.text = "—"
            binding.tvValCycleCount.text = "—"
            binding.tvValTemperature.text = "—"
            binding.tvValVoltage.text = "—"
            binding.tvValCurrent.text = "—"
            binding.tvValPower.text = "—"
            binding.tvValDesignCapacity.text = "—"
            binding.tvValFullCapacity.text = "—"
            binding.tvValCurrentCapacity.text = "—"
            binding.tvValDualCell.text = "—"
            binding.tvValTechnology.text = "—"
            binding.dividerCaptureTime.visibility = android.view.View.GONE
            binding.layoutRowCaptureTime.visibility = android.view.View.GONE
            return
        }

        // 详细参数列表绑定
        binding.tvValLevel.text = info.level?.let { "$it%" } ?: unknownText
        binding.tvValStatus.text = localizeBatteryStatus(context, info.status) ?: unknownText
        binding.tvValHealthStatus.text = localizeBatteryHealthStatus(context, info.healthStatus) ?: unknownText
        binding.tvValHealthPercent.text = info.batteryHealth?.let { String.format("%.2f%%", it) } ?: unknownText
        binding.tvValCycleCount.text = info.cycleCount?.let { "$it" } ?: unknownText
        binding.tvValTemperature.text = info.temperature?.let { String.format("%.1f℃", it) } ?: unknownText
        binding.tvValVoltage.text = info.voltage?.let { String.format("%.0f mV (%.3f V)", it, it / 1000f) } ?: unknownText
        binding.tvValCurrent.text = info.currentNow?.let { String.format("%.0f mA", it) } ?: unknownText
        binding.tvValPower.text = info.powerWatts?.let { String.format("%.2f W", it) } ?: unknownText
        binding.tvValDesignCapacity.text = info.designCapacity?.let { String.format("%.1f mAh", it) } ?: unknownText
        binding.tvValFullCapacity.text = info.fullChargeCapacity?.let { String.format("%.1f mAh", it) } ?: unknownText
        binding.tvValCurrentCapacity.text = info.currentCapacity?.let { String.format("%.1f mAh", it) } ?: unknownText
        binding.tvValDualCell.text = info.isDualCell?.let {
            if (it) context.getString(R.string.yes) else context.getString(R.string.no)
        } ?: unknownText
        binding.tvValTechnology.text = info.technology ?: unknownText

        // 抓取/报告时间展示 (仅在错误报告或有采集时间时展示)
        if (!info.captureTime.isNullOrBlank()) {
            binding.dividerCaptureTime.visibility = android.view.View.VISIBLE
            binding.layoutRowCaptureTime.visibility = android.view.View.VISIBLE
            binding.tvValCaptureTime.text = info.captureTime
        } else {
            binding.dividerCaptureTime.visibility = android.view.View.GONE
            binding.layoutRowCaptureTime.visibility = android.view.View.GONE
        }
    }

    /**
     * 将电池充电状态（如 Charging、Discharging、充电中、放电中等）转换为符合当前语言环境的本地化文本。
     *
     * @param context 应用程序上下文
     * @param rawStatus 原始状态字符串
     * @return 本地化后的电池状态文本
     */
    private fun localizeBatteryStatus(context: Context, rawStatus: String?): String? {
        if (rawStatus.isNullOrBlank()) return null
        return when {
            rawStatus.contains("充电中", ignoreCase = true) || rawStatus.contains("Charging", ignoreCase = true) && !rawStatus.contains("Not", ignoreCase = true) && !rawStatus.contains("Dis", ignoreCase = true) -> {
                context.getString(R.string.battery_status_charging)
            }
            rawStatus.contains("放电中", ignoreCase = true) || rawStatus.contains("Discharging", ignoreCase = true) -> {
                context.getString(R.string.battery_status_discharging)
            }
            rawStatus.contains("未充电", ignoreCase = true) || rawStatus.contains("Not charging", ignoreCase = true) -> {
                context.getString(R.string.battery_status_not_charging)
            }
            rawStatus.contains("充满", ignoreCase = true) || rawStatus.contains("Full", ignoreCase = true) -> {
                context.getString(R.string.battery_status_full)
            }
            else -> rawStatus
        }
    }

    /**
     * 将电池健康状态（如 Good、Overheat、良好、过热等）转换为符合当前语言环境的本地化文本。
     *
     * @param context 应用程序上下文
     * @param rawHealth 原始健康状态字符串
     * @return 本地化后的电池健康状态文本
     */
    private fun localizeBatteryHealthStatus(context: Context, rawHealth: String?): String? {
        if (rawHealth.isNullOrBlank()) return null
        return when {
            rawHealth.contains("良好", ignoreCase = true) || rawHealth.contains("Good", ignoreCase = true) -> {
                context.getString(R.string.battery_health_good)
            }
            rawHealth.contains("过热", ignoreCase = true) || rawHealth.contains("Overheat", ignoreCase = true) -> {
                context.getString(R.string.battery_health_overheat)
            }
            rawHealth.contains("损坏", ignoreCase = true) || rawHealth.contains("Dead", ignoreCase = true) -> {
                context.getString(R.string.battery_health_dead)
            }
            rawHealth.contains("过压", ignoreCase = true) || rawHealth.contains("Over voltage", ignoreCase = true) -> {
                context.getString(R.string.battery_health_over_voltage)
            }
            rawHealth.contains("低温", ignoreCase = true) || rawHealth.contains("Cold", ignoreCase = true) -> {
                context.getString(R.string.battery_health_cold)
            }
            else -> rawHealth
        }
    }
}
