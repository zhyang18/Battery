package com.battery.analysis.model

/**
 * 电池健康度衰减趋势数据点模型。
 * 用于图表绘制坐标计算与交互式 Tooltip 气泡展示。
 *
 * @property id 原始历史记录唯一 ID
 * @property captureTime 完整时间戳字符串（如 "2026-08-18 16:15:46"）
 * @property displayTime 用于 X 轴或 Tooltip 展示的精简时间字符串（如 "08-18 16:15"）
 * @property health 电池健康度百分比数值（如 97.42f）
 * @property cycleCount 电池充放电循环次数（可选）
 * @property fullCapacity 当前充满有效容量（单位：mAh，可选）
 * @property designCapacity 出厂设计额定容量（单位：mAh，可选）
 * @property category 数据源分类名称（如 "系统api"、"Shizuku"、"错误报告"）
 */
data class HealthTrendPoint(
    val id: Long,
    val captureTime: String,
    val displayTime: String,
    val health: Float,
    val cycleCount: Int? = null,
    val fullCapacity: Float? = null,
    val designCapacity: Float? = null,
    val category: String = "系统api"
)
