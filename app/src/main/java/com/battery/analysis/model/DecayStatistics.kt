package com.battery.analysis.model

import android.content.Context
import com.battery.analysis.R
import java.util.Locale

/**
 * 电池健康度衰减与充放电循环消耗统计结果数据模型。
 * 封装根据历史采样点计算出的健康度每日衰减率、每月衰减率、每年衰减率，
 * 以及循环次数的每日消耗量、每月消耗量、每年预估消耗量与按自然周期的明细统计。
 *
 * @property totalPoints 有效采样点总数
 * @property firstTime 起始采样时间字符串（如 "2026-08-18 16:15:46"）
 * @property lastTime 最新采样时间字符串
 * @property spanDays 采样总时间跨度（单位：天，保留合理小数）
 * @property totalDecay 总健康度变化量（单位：%，正数代表健康度下降/衰减，负数代表健康度上升）
 * @property dailyDecayRate 每日平均健康度衰减值（单位：%/天，若样本不足则为 null）
 * @property monthlyDecayRate 每月平均健康度衰减值（单位：%/月，按 30.4375 天折算，若样本不足则为 null）
 * @property yearlyDecayRate 每年平均健康度衰减值（单位：%/年，按 365.25 天折算，若样本不足则为 null）
 * @property firstCycle 起始有效快照的循环次数（可选）
 * @property lastCycle 最新有效快照的循环次数（可选）
 * @property totalCycleChange 有效采样跨度内充放电循环次数的总消耗增量（最新循环 - 初始循环，若不足则为 null）
 * @property dailyCycleRate 每日平均充放电循环消耗量（单位：次/天，若样本不足则为 null）
 * @property monthlyCycleRate 每月平均充放电循环消耗量（单位：次/月，按 30.4375 天折算，若样本不足则为 null）
 * @property yearlyCycleRate 每年预估充放电循环消耗量（单位：次/年，按 365.25 天折算，若样本不足则为 null）
 * @property dailyItems 按自然日分组的实际衰减与循环明细列表
 * @property monthlyItems 按自然月分组的实际衰减与循环明细列表
 * @property yearlyItems 按自然年分组的实际衰减与循环明细列表
 * @property hasSufficientData 是否有足够的数据点（至少 2 个点且跨度有效）用于计算健康度速率
 * @property hasCycleData 是否有足够有效的循环次数数据（至少 2 个含循环计数的快照点）用于计算循环消耗速率
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
    val firstCycle: Int? = null,
    val lastCycle: Int? = null,
    val totalCycleChange: Int? = null,
    val dailyCycleRate: Float? = null,
    val monthlyCycleRate: Float? = null,
    val yearlyCycleRate: Float? = null,
    val dailyItems: List<PeriodicDecayItem> = emptyList(),
    val monthlyItems: List<PeriodicDecayItem> = emptyList(),
    val yearlyItems: List<PeriodicDecayItem> = emptyList(),
    val hasSufficientData: Boolean = false,
    val hasCycleData: Boolean = false
) {
    /**
     * 获取每日平均健康度衰减值的格式化显示文本。
     *
     * @return 格式化后的每日衰减文本（如 "-0.020%/天" 或 "--"）
     */
    fun formatDailyDecay(): String {
        return dailyDecayRate?.let { rate ->
            if (rate > 0f) {
                String.format(Locale.getDefault(), "-%.3f%%/天", rate)
            } else if (rate < 0f) {
                String.format(Locale.getDefault(), "+%.3f%%/天", -rate)
            } else {
                "0.000%/天"
            }
        } ?: "--"
    }

    /**
     * 获取每月平均健康度衰减值的格式化显示文本。
     *
     * @return 格式化后的每月衰减文本（如 "-0.61%/月" 或 "--"）
     */
    fun formatMonthlyDecay(): String {
        return monthlyDecayRate?.let { rate ->
            if (rate > 0f) {
                String.format(Locale.getDefault(), "-%.2f%%/月", rate)
            } else if (rate < 0f) {
                String.format(Locale.getDefault(), "+%.2f%%/月", -rate)
            } else {
                "0.00%/月"
            }
        } ?: "--"
    }

    /**
     * 获取每年平均/预估健康度衰减值的格式化显示文本。
     *
     * @return 格式化后的每年衰减文本（如 "-7.30%/年" 或 "--"）
     */
    fun formatYearlyDecay(): String {
        return yearlyDecayRate?.let { rate ->
            if (rate > 0f) {
                String.format(Locale.getDefault(), "-%.2f%%/年", rate)
            } else if (rate < 0f) {
                String.format(Locale.getDefault(), "+%.2f%%/年", -rate)
            } else {
                "0.00%/年"
            }
        } ?: "--"
    }

    /**
     * 获取每日平均循环消耗量的格式化显示文本。
     *
     * @return 格式化后的每日循环消耗文本（如 "+0.52 次/天" 或 "--"）
     */
    fun formatDailyCycle(): String {
        return dailyCycleRate?.let { rate ->
            if (rate > 0.0001f) {
                String.format(Locale.getDefault(), "+%.2f 次/天", rate)
            } else if (rate < -0.0001f) {
                String.format(Locale.getDefault(), "%.2f 次/天", rate)
            } else {
                "0.00 次/天"
            }
        } ?: "--"
    }

    /**
     * 获取每月平均循环消耗量的格式化显示文本。
     *
     * @return 格式化后的每月循环消耗文本（如 "+15.8 次/月" 或 "--"）
     */
    fun formatMonthlyCycle(): String {
        return monthlyCycleRate?.let { rate ->
            if (rate > 0.0001f) {
                String.format(Locale.getDefault(), "+%.1f 次/月", rate)
            } else if (rate < -0.0001f) {
                String.format(Locale.getDefault(), "%.1f 次/月", rate)
            } else {
                "0.0 次/月"
            }
        } ?: "--"
    }

    /**
     * 获取每年预估循环消耗量的格式化显示文本。
     *
     * @return 格式化后的每年预估循环消耗文本（如 "+190 次/年" 或 "--"）
     */
    fun formatYearlyCycle(): String {
        return yearlyCycleRate?.let { rate ->
            if (rate > 0.0001f) {
                String.format(Locale.getDefault(), "+%.0f 次/年", rate)
            } else if (rate < -0.0001f) {
                String.format(Locale.getDefault(), "%.0f 次/年", rate)
            } else {
                "0 次/年"
            }
        } ?: "--"
    }
}

/**
 * 自然周期实际健康度衰减与循环消耗明细项模型。
 * 用于呈现某个具体日期、月份或年份内的起止健康度、实际健康度衰减以及起止循环与循环消耗增量。
 *
 * @property periodKey 周期唯一标识键（如 "2026-08-19"、"2026-08"、"2026"）
 * @property periodLabel 友好展示标题（如 "2026年08月19日"、"2026年08月"、"2026年度"）
 * @property startHealth 周期初始健康度百分比
 * @property endHealth 周期末尾健康度百分比
 * @property decay 该周期内健康度实际衰减值（= startHealth - endHealth，正数代表下降）
 * @property sampleCount 该周期内的采样快照次数
 * @property startCycle 该周期起始快照的循环次数（可选）
 * @property endCycle 该周期末尾快照的循环次数（可选）
 * @property cycleChange 该周期内充放电循环次数的变化量（= endCycle - startCycle，可选）
 */
data class PeriodicDecayItem(
    val periodKey: String,
    val periodLabel: String,
    val startHealth: Float,
    val endHealth: Float,
    val decay: Float,
    val sampleCount: Int,
    val startCycle: Int? = null,
    val endCycle: Int? = null,
    val cycleChange: Int? = null
) {
    /**
     * 获取该周期内健康度衰减值的格式化字符串（支持多语言）。
     *
     * @param context 可选 Android 上下文环境
     * @return 格式化后的衰减文本（如 "-0.25%"、"+0.10%" 或 "持平 (0.00%)"）
     */
    fun formatDecay(context: Context? = null): String {
        return when {
            decay > 0.001f -> String.format(Locale.getDefault(), "-%.2f%%", decay)
            decay < -0.001f -> String.format(Locale.getDefault(), "+%.2f%%", -decay)
            else -> context?.getString(R.string.decay_stat_flat) ?: "持平 (0.00%)"
        }
    }

    /**
     * 获取支持当前语言环境的友好周期展示标题。
     *
     * @param context 可选 Android 上下文环境
     * @return 格式化后的本地化周期标题
     */
    fun getFormattedPeriodLabel(context: Context? = null): String {
        return if (periodKey.length == 7 && periodKey.contains("-")) {
            val parts = periodKey.split("-")
            context?.getString(R.string.period_month_format, parts[0], parts[1]) ?: "${parts[0]}年${parts[1]}月"
        } else if (periodKey.length == 4) {
            context?.getString(R.string.period_year_format, periodKey) ?: "${periodKey}年度"
        } else {
            periodKey
        }
    }

    /**
     * 获取该周期内循环次数消耗变化的格式化字符串（支持多语言）。
     *
     * @param context 可选 Android 上下文环境
     * @return 格式化后的循环消耗文本（如 "+2 次"、"-1 次"、"0 次" 或 "--"）
     */
    fun formatCycleChange(context: Context? = null): String {
        return cycleChange?.let { change ->
            if (change > 0) {
                if (context != null) context.getString(R.string.period_cycle_single_format, "+$change") else "+$change 次"
            } else if (change < 0) {
                if (context != null) context.getString(R.string.period_cycle_single_format, "$change") else "$change 次"
            } else {
                context?.getString(R.string.cycle_rate_zero_format) ?: "0 次"
            }
        } ?: "--"
    }
}
