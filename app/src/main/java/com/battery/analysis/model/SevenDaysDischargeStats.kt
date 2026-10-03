package com.battery.analysis.model

/**
 * 过去 7 天核心放电速度、实际统计时长与满电续航统计实体类。
 * 封装亮屏、息屏与全局三个维度的 7 天加权平均放电速率（%/h）、实际有效统计时长（毫秒）及满电可用时长（毫秒）。
 *
 * @property screenOnDischargeRatePercentPerHour 亮屏 7 天加权平均放电速度（单位：%/h），数据缺失时为 null
 * @property screenOffDischargeRatePercentPerHour 息屏 7 天加权平均放电速度（单位：%/h），数据缺失时为 null
 * @property globalDischargeRatePercentPerHour 全局 7 天加权平均放电速度（单位：%/h），数据缺失时为 null
 * @property screenOnDurationMs 亮屏在 7 天滑动窗口内实际有效统计时长（单位：毫秒，上限 7 天），无数据时为 null
 * @property screenOffDurationMs 息屏在 7 天滑动窗口内实际有效统计时长（单位：毫秒，上限 7 天），无数据时为 null
 * @property globalDurationMs 全局在 7 天滑动窗口内实际有效统计时长（单位：毫秒，上限 7 天），无数据时为 null
 * @property fullChargeScreenOnDurationMs 基于 7 天亮屏放电速度推算的充满电理论使用时长（单位：毫秒），数据缺失时为 null
 * @property fullChargeScreenOffDurationMs 基于 7 天息屏放电速度推算的充满电理论待机时长（单位：毫秒），数据缺失时为 null
 * @property fullChargeGlobalDurationMs 基于 7 天全局放电速度推算的充满电理论综合使用时长（单位：毫秒），数据缺失时为 null
 */
data class SevenDaysDischargeStats(
    val screenOnDischargeRatePercentPerHour: Float? = null,
    val screenOffDischargeRatePercentPerHour: Float? = null,
    val globalDischargeRatePercentPerHour: Float? = null,
    val screenOnDurationMs: Long? = null,
    val screenOffDurationMs: Long? = null,
    val globalDurationMs: Long? = null,
    val fullChargeScreenOnDurationMs: Long? = null,
    val fullChargeScreenOffDurationMs: Long? = null,
    val fullChargeGlobalDurationMs: Long? = null
)
