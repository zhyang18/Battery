package com.battery.analysis.timeline.domain

/**
 * 屏幕亮灭状态时间区间实体数据类。
 * 用于在功耗时间轴上渲染亮屏（绿色）与息屏（深色/灰色）状态条。
 *
 * @property startTime 区间起始时间戳（毫秒）
 * @property endTime 区间结束时间戳（毫秒）
 * @property isScreenOn 是否处于亮屏状态（true: 亮屏, false: 息屏待机）
 */
data class ScreenEvent(
    val startTime: Long,
    val endTime: Long,
    val isScreenOn: Boolean
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
