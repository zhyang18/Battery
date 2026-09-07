package com.battery.analysis.timeline.presentation

import com.battery.analysis.timeline.domain.AppTimelineEvent
import com.battery.analysis.timeline.domain.BatterySample
import com.battery.analysis.timeline.domain.ScreenEvent

/**
 * 功耗时间轴主指标类型枚举。
 */
enum class TimelineMetric {
    /**
     * 实时功率（mW / W）曲线。
     */
    POWER,

    /**
     * 电池电量百分比（%）曲线。
     */
    BATTERY,

    /**
     * 电池温度（℃）曲线。
     */
    TEMPERATURE,

    /**
     * 电池电压（V / mV）曲线。
     */
    VOLTAGE,

    /**
     * 应用程序时间轴事件与图标堆叠。
     */
    APP
}

/**
 * 功耗时间轴视图状态数据模型。
 * 统一管理时间范围、可视缩放窗口、采样点集、屏幕状态、App 事件及当前选中的指标。
 *
 * @property startTimestamp 数据全集起始物理时间戳（毫秒）
 * @property endTimestamp 数据全集结束物理时间戳（毫秒）
 * @property visibleStartTimestamp 当前屏幕可视区域的起始时间戳（毫秒）
 * @property visibleEndTimestamp 当前屏幕可视区域的结束时间戳（毫秒）
 * @property zoomScale 当前缩放倍率（1.0f ~ 32.0f）
 * @property scrollOffset 当前可视区域滚动偏移比率（0.0f ~ 1.0f）
 * @property screenEvents 屏幕亮灭区间列表
 * @property appEvents 应用活跃事件列表
 * @property batterySamples 物理采样点序列
 * @property selectedMetric 兼容保留的主展示指标
 * @property selectedMetrics 当前选中的指标集合（支持多选与反选叠加展示）
 * @property selectedApp 当前被点击选中的 App 事件（若有）
 */
data class BatteryTimelineState(
    val startTimestamp: Long = 0L,
    val endTimestamp: Long = 0L,
    val visibleStartTimestamp: Long = 0L,
    val visibleEndTimestamp: Long = 0L,
    val zoomScale: Float = 1.0f,
    val scrollOffset: Float = 0.0f,
    val screenEvents: List<ScreenEvent> = emptyList(),
    val appEvents: List<AppTimelineEvent> = emptyList(),
    val batterySamples: List<BatterySample> = emptyList(),
    val selectedMetric: TimelineMetric = TimelineMetric.POWER,
    val selectedMetrics: Set<TimelineMetric> = setOf(TimelineMetric.POWER, TimelineMetric.APP),
    val selectedApp: AppTimelineEvent? = null
) {
    /**
     * 获取数据全集的总时间跨度（毫秒）。
     *
     * @return 总时间跨度（毫秒）
     */
    fun getTotalDurationMs(): Long {
        return (endTimestamp - startTimestamp).coerceAtLeast(1000L)
    }

    /**
     * 获取当前可视区域的时间跨度（毫秒）。
     *
     * @return 可视时间跨度（毫秒）
     */
    fun getVisibleDurationMs(): Long {
        return (visibleEndTimestamp - visibleStartTimestamp).coerceAtLeast(1000L)
    }
}
