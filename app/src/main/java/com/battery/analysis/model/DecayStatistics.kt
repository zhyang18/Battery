package com.battery.analysis.model

/**
 * 电池健康度衰减统计结果数据模型。
 * 封装根据健康度趋势采样点计算出的每日衰减率、每月衰减率、每年衰减率以及按自然周期的明细统计。
 *
 * @property totalPoints 有效采样点总数
 * @property firstTime 起始采样时间字符串（如 "2026-08-18 16:15:46"）
 * @property lastTime 最新采样时间字符串
 * @property spanDays 采样总时间跨度（单位：天，保留合理小数）
 * @property totalDecay 总健康度变化量（单位：%，正数代表健康度下降/衰减，负数代表健康度上升）
 * @property dailyDecayRate 每日平均衰减值（单位：%/天，若样本不足则为 null）
 * @property monthlyDecayRate 每月平均衰减值（单位：%/月，按 30.4375 天折算，若样本不足则为 null）
 * @property yearlyDecayRate 每年平均衰减值（单位：%/年，按 365.25 天折算，若样本不足则为 null）
 * @property dailyItems 按自然日分组的实际衰减明细列表
 * @property monthlyItems 按自然月分组的实际衰减明细列表
 * @property yearlyItems 按自然年分组的实际衰减明细列表
 * @property hasSufficientData 是否有足够的数据点（至少 2 个点且跨度有效）用于计算速率
 */
data class DecayStatistics(
    val totalPoints: Int = 0,
    val firstTime: String? = null,
    val lastTime: String? = null,
    val spanDays: Double = 0.0,
    val totalDecay: Float = 0f,
    val dailyDecayRate: Float? = null,
    val monthlyDecayRate: Float? = null,
    val yearlyDecayRate: Float? = null,
    val dailyItems: List<PeriodicDecayItem> = emptyList(),
    val monthlyItems: List<PeriodicDecayItem> = emptyList(),
    val yearlyItems: List<PeriodicDecayItem> = emptyList(),
    val hasSufficientData: Boolean = false
) {
    /**
     * 获取每日平均衰减值的格式化显示文本。
     *
     * @return 格式化后的每日衰减文本（如 "-0.02%/天" 或 "--"）
     */
    fun formatDailyDecay(): String {
        return dailyDecayRate?.let { rate ->
            if (rate > 0f) {
                String.format("-%.3f%%/天", rate)
            } else if (rate < 0f) {
                String.format("+%.3f%%/天", -rate)
            } else {
                "0.000%/天"
            }
        } ?: "--"
    }

    /**
     * 获取每月平均衰减值的格式化显示文本。
     *
     * @return 格式化后的每月衰减文本（如 "-0.61%/月" 或 "--"）
     */
    fun formatMonthlyDecay(): String {
        return monthlyDecayRate?.let { rate ->
            if (rate > 0f) {
                String.format("-%.2f%%/月", rate)
            } else if (rate < 0f) {
                String.format("+%.2f%%/月", -rate)
            } else {
                "0.00%/月"
            }
        } ?: "--"
    }

    /**
     * 获取每年平均/预估衰减值的格式化显示文本。
     *
     * @return 格式化后的每年衰减文本（如 "-7.30%/年" 或 "--"）
     */
    fun formatYearlyDecay(): String {
        return yearlyDecayRate?.let { rate ->
            if (rate > 0f) {
                String.format("-%.2f%%/年", rate)
            } else if (rate < 0f) {
                String.format("+%.2f%%/年", -rate)
            } else {
                "0.00%/年"
            }
        } ?: "--"
    }
}

/**
 * 自然周期实际健康度衰减明细项模型。
 * 用于呈现某个具体日期、月份或年份内的起止健康度与实际衰减变化。
 *
 * @property periodKey 周期唯一标识键（如 "2026-08-19"、"2026-08"、"2026"）
 * @property periodLabel 友好展示标题（如 "2026年08月19日"、"2026年08月"、"2026年度"）
 * @property startHealth 周期初始健康度百分比
 * @property endHealth 周期末尾健康度百分比
 * @property decay 该周期内健康度实际衰减值（= startHealth - endHealth，正数代表下降）
 * @property sampleCount 该周期内的采样快照次数
 * @property cycleChange 该周期内充放电循环次数的变化量（可选）
 */
data class PeriodicDecayItem(
    val periodKey: String,
    val periodLabel: String,
    val startHealth: Float,
    val endHealth: Float,
    val decay: Float,
    val sampleCount: Int,
    val cycleChange: Int? = null
) {
    /**
     * 获取该周期内衰减值的格式化字符串。
     *
     * @return 格式化后的衰减文本（如 "衰减 0.25%" 或 "上升 0.10%" 或 "持平"）
     */
    fun formatDecay(): String {
        return when {
            decay > 0.001f -> String.format("-%.2f%%", decay)
            decay < -0.001f -> String.format("+%.2f%%", -decay)
            else -> "持平 (0.00%)"
        }
    }
}
