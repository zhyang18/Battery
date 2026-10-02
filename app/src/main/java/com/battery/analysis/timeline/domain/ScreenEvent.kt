package com.battery.analysis.timeline.domain

/**
 * 屏幕亮灭与休眠状态时间区间实体数据类。
 * 用于在功耗时间轴上渲染亮屏（绿色）、息屏唤醒（浅红色）与深度睡眠（深红色）三色状态条。
 *
 * @property startTime 区间起始时间戳（毫秒）
 * @property endTime 区间结束时间戳（毫秒）
 * @property isScreenOn 是否处于亮屏状态（true: 亮屏, false: 息屏待机）
 * @property isDeepSleep 当处于息屏状态时，是否处于深度睡眠挂起状态（true: 深度睡眠深红色, false: 息屏唤醒活跃浅红色）
 */
data class ScreenEvent(
    val startTime: Long,
    val endTime: Long,
    val isScreenOn: Boolean,
    val isDeepSleep: Boolean = false
) {
    /**
     * 获取当前屏幕状态区间的持续时长（毫秒）。
     *
     * @return 持续时长（毫秒）
     */
    fun getDurationMs(): Long {
        return (endTime - startTime).coerceAtLeast(0L)
    }
}
