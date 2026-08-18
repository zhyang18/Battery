package com.battery.analysis.model

/**
 * 存储从错误报告中解析出的精简表格项。
 *
 * @property itemTitle 项目名称（包含图标，例如 🔋 电池电压）
 * @property currentValue 当前测量或映射后的值
 * @property description 字段说明含义
 */
data class HealthInfoItem(
    val itemTitle: String,
    val currentValue: String,
    val description: String
)

/**
 * 错误报告解析结果封装类。
 *
 * @property tableItems 精简表格项列表
 * @property rawHealthInfoText 提取到的原始 getHealthInfo 文本日志
 * @property hasRealData 是否成功提取到了真实的电池核心参数
 * @property parsedBatteryInfo 提取出的结构化 [BatteryInfo] 对象
 */
data class BugreportResult(
    val tableItems: List<HealthInfoItem>,
    val rawHealthInfoText: String,
    val hasRealData: Boolean = false,
    val parsedBatteryInfo: BatteryInfo? = null
)
