package com.battery.analysis.model

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
    fun formatToString(): String {
        return """
            报告时间: ${captureTime ?: "实时"}
            电量: ${level?.let { "$it%" } ?: "未知"}
            电池状态: ${status ?: "未知"}
            电池健康: ${healthStatus ?: "未知"}
            健康度: ${batteryHealth?.let { String.format("%.2f%%", it) } ?: "未知"}
            循环计数: ${cycleCount ?: "未知"}
            电池温度: ${temperature?.let { String.format("%.1f℃", it) } ?: "未知"}
            电池电压: ${voltage?.let { String.format("%.0f mV", it) } ?: "未知"}
            电流: ${currentNow?.let { String.format("%.0f mA", it) } ?: "未知"}
            双电芯: ${isDualCell?.let { if (it) "是" else "否" } ?: "否"}
            电池功率: ${powerWatts?.let { String.format("%.2f W", it) } ?: "未知"}
            设计容量: ${designCapacity?.let { String.format("%.1f mAh", it) } ?: "未知"}
            充满容量: ${fullChargeCapacity?.let { String.format("%.1f mAh", it) } ?: "未知"}
            当前容量: ${currentCapacity?.let { String.format("%.1f mAh", it) } ?: "未知"}
            电池技术: ${technology ?: "未知"}
        """.trimIndent()
    }

    /**
     * 仅将电池信息的数据值格式化为多行字符串（用于多列对齐展示）。
     *
     * @return 仅包含数据值的多行文本
     */
    fun formatValuesOnly(): String {
        return """
            ${captureTime ?: "实时"}
            ${level?.let { "$it%" } ?: "未知"}
            ${status ?: "未知"}
            ${healthStatus ?: "未知"}
            ${batteryHealth?.let { String.format("%.2f%%", it) } ?: "未知"}
            ${cycleCount ?: "未知"}
            ${temperature?.let { String.format("%.1f℃", it) } ?: "未知"}
            ${voltage?.let { String.format("%.0f mV", it) } ?: "未知"}
            ${currentNow?.let { String.format("%.0f mA", it) } ?: "未知"}
            ${isDualCell?.let { if (it) "是" else "否" } ?: "否"}
            ${powerWatts?.let { String.format("%.2f W", it) } ?: "未知"}
            ${designCapacity?.let { String.format("%.1f mAh", it) } ?: "未知"}
            ${fullChargeCapacity?.let { String.format("%.1f mAh", it) } ?: "未知"}
            ${currentCapacity?.let { String.format("%.1f mAh", it) } ?: "未知"}
            ${technology ?: "未知"}
        """.trimIndent()
    }
}
