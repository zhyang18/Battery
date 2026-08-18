package com.battery.analysis

import com.battery.analysis.databinding.LayoutBatteryParamItemsBinding

/**
 * 电池详细参数视图绑定辅助工具类。
 * 负责将 [BatteryInfo] 实体中的电量、健康度、电压、电流等各项数据格式化并绑定至通用的参数列表 UI 布局。
 */
object BatteryParamViewBinder {

    /**
     * 将给定的电池数据对象绑定渲染到指定的视图绑定对象上。
     *
     * @param binding 包含通用参数展示控件的 [LayoutBatteryParamItemsBinding]
     * @param info 待展示的电池信息对象，若为 null 则展示未获取数据的占位文本
     */
    fun bind(binding: LayoutBatteryParamItemsBinding, info: BatteryInfo?) {
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
            binding.tvValCurrentCapacity.text = "—"
            binding.tvValFullCapacity.text = "—"
            binding.tvValDualCell.text = "—"
            binding.tvValTechnology.text = "—"
            return
        }

        // 详细参数列表绑定
        binding.tvValLevel.text = info.level?.let { "$it%" } ?: "未知"
        binding.tvValStatus.text = info.status ?: "未知"
        binding.tvValHealthStatus.text = info.healthStatus ?: "未知"
        binding.tvValHealthPercent.text = info.batteryHealth?.let { String.format("%.2f%%", it) } ?: "未知"
        binding.tvValCycleCount.text = info.cycleCount?.let { "$it" } ?: "未知"
        binding.tvValTemperature.text = info.temperature?.let { String.format("%.1f℃", it) } ?: "未知"
        binding.tvValVoltage.text = info.voltage?.let { String.format("%.0f mV (%.3f V)", it, it / 1000f) } ?: "未知"
        binding.tvValCurrent.text = info.currentNow?.let { String.format("%.0f mA", it) } ?: "未知"
        binding.tvValPower.text = info.powerWatts?.let { String.format("%.2f W", it) } ?: "未知"
        binding.tvValDesignCapacity.text = info.designCapacity?.let { String.format("%.1f mAh", it) } ?: "未知"
        binding.tvValCurrentCapacity.text = info.currentCapacity?.let { String.format("%.1f mAh", it) } ?: "未知"
        binding.tvValFullCapacity.text = info.fullChargeCapacity?.let { String.format("%.1f mAh", it) } ?: "未知"
        binding.tvValDualCell.text = info.isDualCell?.let { if (it) "是" else "否" } ?: "未知"
        binding.tvValTechnology.text = info.technology ?: "未知"
    }
}
