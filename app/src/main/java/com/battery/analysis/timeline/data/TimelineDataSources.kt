package com.battery.analysis.timeline.data

import com.battery.analysis.timeline.domain.AppTimelineEvent
import com.battery.analysis.timeline.domain.BatterySample
import com.battery.analysis.timeline.domain.ScreenEvent

/**
 * 电池物理采样数据源接口。
 */
interface BatteryDataSource {
    /**
     * 获取指定时间范围内的电池物理采样点集合。
     *
     * @param startTime 起始时间戳（毫秒）
     * @param endTime 结束时间戳（毫秒）
     * @return 采样点列表 [List<BatterySample>]
     */
    suspend fun getBatterySamples(startTime: Long, endTime: Long): List<BatterySample>
}

/**
 * BatteryStats 系统权威能耗数据源接口。
 */
interface BatteryStatsDataSource {
    /**
     * 获取指定时间范围内的 BatteryStats 权威功耗概要。
     *
     * @param startTime 起始时间戳（毫秒）
     * @param endTime 结束时间戳（毫秒）
     * @return 映射到应用时间轴事件的列表 [List<AppTimelineEvent>]
     */
    suspend fun getBatteryStatsEvents(startTime: Long, endTime: Long): List<AppTimelineEvent>
}

/**
 * UsageStats 系统应用使用事件数据源接口。
 */
interface UsageStatsDataSource {
    /**
     * 获取系统 UsageStats 记录的应用前台切换与活动事件。
     *
     * @param startTime 起始时间戳（毫秒）
     * @param endTime 结束时间戳（毫秒）
     * @return 应用活跃事件列表 [List<AppTimelineEvent>]
     */
    suspend fun getUsageEvents(startTime: Long, endTime: Long): List<AppTimelineEvent>

    /**
     * 获取系统屏幕亮屏与息屏时间区间。
     *
     * @param startTime 起始时间戳（毫秒）
     * @param endTime 结束时间戳（毫秒）
     * @return 屏幕事件列表 [List<ScreenEvent>]
     */
    suspend fun getScreenEvents(startTime: Long, endTime: Long): List<ScreenEvent>
}

/**
 * Linux 内核 sysfs 电源节点数据源接口。
 */
interface SysfsBatteryDataSource {
    /**
     * 读取内核级实时电压（mV）。
     *
     * @return 电压（mV）
     */
    fun readVoltageMv(): Int?

    /**
     * 读取内核级实时电流（mA）。
     *
     * @return 电流（mA）
     */
    fun readCurrentMa(): Double?

    /**
     * 读取内核级电池温度（℃）。
     *
     * @return 温度（℃）
     */
    fun readTemperatureC(): Double?
}

/**
 * 整合后的应用时间轴事件数据提供者接口。
 */
interface AppUsageDataSource {
    /**
     * 获取指定时间段内的应用活动合并时间轴事件。
     *
     * @param startTime 起始时间戳（毫秒）
     * @param endTime 结束时间戳（毫秒）
     * @return 应用时间轴事件列表 [List<AppTimelineEvent>]
     */
    suspend fun getAppEvents(startTime: Long, endTime: Long): List<AppTimelineEvent>
}
