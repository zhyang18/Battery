package com.example.batteryapp

/**
 * 存储从错误报告 getHealthInfo 中解析出的一条字段映射项。
 *
 * @property rawField 错误报告中的原始字段名
 * @property displayValue 经过单位转换和语义映射后的展示值
 * @property meaning 该字段在安卓底层与电池硬件中的物理含义
 */
data class HealthInfoItem(
    val rawField: String,
    val displayValue: String,
    val meaning: String
)
