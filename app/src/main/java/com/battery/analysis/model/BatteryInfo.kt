package com.battery.analysis.model

import android.content.Context
import com.battery.analysis.R
import com.battery.analysis.util.BatteryEnergyCalculator
import java.util.Locale

/**
 * 电池信息数据实体类。
 * 用于存储从不同渠道获取或融合后的电池状态数据，涵盖电量、健康度、电压、电流等各项运行与设计参数。
 */
data class BatteryInfo(
    /**
     * 电池健康度，通常为百分比（例如 95.50 表示 95.50%，可大于 100%）。
     */
    val batteryHealth: Float? = null,
    
    /**
     * 电池健康状态描述（如 "良好", "过热", "损坏" 等）。
     */
    val healthStatus: String? = null,

    /**
     * 电池电量百分比（如 72%）。
     */
    val level: Int? = null,

    /**
     * 电池工作状态（如 "充电中", "放电中", "已充满", "未充电"）。
     */
    val status: String? = null,

    /**
     * 电池总设计容量（单位：mAh）。
     */
    val designCapacity: Float? = null,
    
    /**
     * 电池当前剩余容量（单位：mAh）。
     */
    val currentCapacity: Float? = null,
    
    /**
     * 电池充满电时的容量（单位：mAh）。
     */
    val fullChargeCapacity: Float? = null,
    
    /**
     * 电池循环次数。
     */
    val cycleCount: Int? = null,

    /**
     * 电池温度（单位：℃）。
     */
    val temperature: Float? = null,

    /**
     * 电池电压（单位：mV）。
     */
    val voltage: Float? = null,

    /**
     * 当前电流（单位：mA，正数为充电电流，负数为放电电流）。
     */
    val currentNow: Float? = null,

    /**
     * 实时功率（单位：W）。
     */
    val powerWatts: Float? = null,

    /**
     * 是否为双电芯结构。
     */
    val isDualCell: Boolean? = null,

    /**
     * 电池技术类型（如 Li-ion）。
     */
    val technology: String? = null,

    /**
     * 获取数据的方法/来源（例如 "BatteryManager", "sysfs", "dumpsys", "Fusion"）。
     */
    val source: String = "",

    /**
     * 抓取/采集报告的日期时间（如 "2024-05-18 15:30:22"）。
     */
    val captureTime: String? = null
) {
    /**
     * 将电池信息格式化为包含键名与键值的多行字符串。
     *
     * @return 包含各项参数的格式化字符串
     */
    /**
     * 将电池信息格式化为包含键名与键值的多行字符串（支持自适应多语言）。
     *
     * @param context 可选 Android 上下文环境
     * @return 包含各项参数的格式化多行字符串
     */
    fun formatToString(context: Context? = null): String {
        val isZh = context?.resources?.configuration?.locales?.get(0)?.language?.startsWith("zh")
            ?: Locale.getDefault().language.startsWith("zh")
        val unknownText = context?.getString(R.string.unknown) ?: if (isZh) "未知" else "Unknown"
        val realtimeText = if (isZh) "实时" else "Realtime"
        val yesText = context?.getString(R.string.yes) ?: if (isZh) "是" else "Yes"
        val noText = context?.getString(R.string.no) ?: if (isZh) "否" else "No"

        val labelTime = context?.getString(R.string.param_capture_time) ?: if (isZh) "⏱️ 检测时间" else "Time"
        val labelLevel = context?.getString(R.string.param_level) ?: if (isZh) "🔋 当前电量" else "Level"
        val labelStatus = context?.getString(R.string.param_status) ?: if (isZh) "⚡ 电池状态" else "Status"
        val labelHealth = context?.getString(R.string.param_health_status) ?: if (isZh) "🩺 电池健康" else "Health"
        val labelHealthPct = context?.getString(R.string.param_health_percent) ?: if (isZh) "❤️ 健康度" else "SOH"
        val labelCycle = context?.getString(R.string.param_cycle_count) ?: if (isZh) "🔄 循环计数" else "Cycles"
        val labelTemp = context?.getString(R.string.param_temperature) ?: if (isZh) "🌡️ 电池温度" else "Temp"
        val labelVolt = context?.getString(R.string.param_voltage) ?: if (isZh) "🔋 电池电压" else "Volt"
        val labelCur = context?.getString(R.string.param_current) ?: if (isZh) "⚡ 瞬时电流" else "Current"
        val labelDual = context?.getString(R.string.param_dual_cell) ?: if (isZh) "🔋 双电芯" else "Dual Cell"
        val labelPower = context?.getString(R.string.param_power) ?: if (isZh) "⚡ 电池功率" else "Power"
        val labelDesign = context?.getString(R.string.param_design_capacity) ?: if (isZh) "🔋 设计容量" else "Design Cap"
        val labelFull = context?.getString(R.string.param_full_capacity) ?: if (isZh) "🔋 充满容量" else "Full Cap"
        val labelCurCap = context?.getString(R.string.param_current_capacity) ?: if (isZh) "🔋 当前容量" else "Current Cap"
        val labelTech = context?.getString(R.string.param_technology) ?: if (isZh) "🔬 电池技术" else "Tech"

        return """
            $labelTime: ${captureTime ?: realtimeText}
            $labelLevel: ${level?.let { "$it%" } ?: unknownText}
            $labelStatus: ${status ?: unknownText}
            $labelHealth: ${healthStatus ?: unknownText}
            $labelHealthPct: ${batteryHealth?.let { String.format(Locale.getDefault(), "%.2f%%", it) } ?: unknownText}
            $labelCycle: ${cycleCount ?: unknownText}
            $labelTemp: ${temperature?.let { String.format(Locale.getDefault(), "%.1f℃", it) } ?: unknownText}
            $labelVolt: ${voltage?.let { String.format(Locale.getDefault(), "%.0f mV", it) } ?: unknownText}
            $labelCur: ${currentNow?.let { if (it > 0f) String.format(Locale.getDefault(), "+%.0f mA", it) else String.format(Locale.getDefault(), "%.0f mA", it) } ?: unknownText}
            $labelDual: ${isDualCell?.let { if (it) yesText else noText } ?: noText}
            $labelPower: ${powerWatts?.let { if (it > 0f) String.format(Locale.getDefault(), "+%.2f W", it) else String.format(Locale.getDefault(), "%.2f W", it) } ?: unknownText}
            $labelDesign: ${BatteryEnergyCalculator.formatCapacityWithNominalWh(designCapacity) ?: unknownText}
            $labelFull: ${BatteryEnergyCalculator.formatCapacityWithNominalWh(fullChargeCapacity) ?: unknownText}
            $labelCurCap: ${BatteryEnergyCalculator.formatCapacityWithNominalWh(currentCapacity) ?: unknownText}
            $labelTech: ${technology ?: unknownText}
        """.trimIndent()
    }

    /**
     * 仅将电池信息的数据值格式化为多行字符串（用于多列对齐展示，支持自适应多语言）。
     *
     * @param context 可选 Android 上下文环境
     * @return 仅包含数据值的多行文本
     */
    fun formatValuesOnly(context: Context? = null): String {
        val isZh = context?.resources?.configuration?.locales?.get(0)?.language?.startsWith("zh")
            ?: Locale.getDefault().language.startsWith("zh")
        val unknownText = context?.getString(R.string.unknown) ?: if (isZh) "未知" else "Unknown"
        val realtimeText = if (isZh) "实时" else "Realtime"
        val yesText = context?.getString(R.string.yes) ?: if (isZh) "是" else "Yes"
        val noText = context?.getString(R.string.no) ?: if (isZh) "否" else "No"

        return """
            ${captureTime ?: realtimeText}
            ${level?.let { "$it%" } ?: unknownText}
            ${status ?: unknownText}
            ${healthStatus ?: unknownText}
            ${batteryHealth?.let { String.format(Locale.getDefault(), "%.2f%%", it) } ?: unknownText}
            ${cycleCount ?: unknownText}
            ${temperature?.let { String.format(Locale.getDefault(), "%.1f℃", it) } ?: unknownText}
            ${voltage?.let { String.format(Locale.getDefault(), "%.0f mV", it) } ?: unknownText}
            ${currentNow?.let { if (it > 0f) String.format(Locale.getDefault(), "+%.0f mA", it) else String.format(Locale.getDefault(), "%.0f mA", it) } ?: unknownText}
            ${isDualCell?.let { if (it) yesText else noText } ?: noText}
            ${powerWatts?.let { if (it > 0f) String.format(Locale.getDefault(), "+%.2f W", it) else String.format(Locale.getDefault(), "%.2f W", it) } ?: unknownText}
            ${BatteryEnergyCalculator.formatCapacityWithNominalWh(designCapacity) ?: unknownText}
            ${BatteryEnergyCalculator.formatCapacityWithNominalWh(fullChargeCapacity) ?: unknownText}
            ${BatteryEnergyCalculator.formatCapacityWithNominalWh(currentCapacity) ?: unknownText}
            ${technology ?: unknownText}
        """.trimIndent()
    }
}
