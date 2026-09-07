package com.battery.analysis.timeline.domain

import kotlin.math.max

/**
 * 时间轴事件合并引擎。
 * 负责将同一应用程序在临近时间点或交替产生的离散活动记录按包名进行深度合并，
 * 彻底消除高频采样与前台快速切换产生的密集冗余 App 图标。
 */
object TimelineEventMerger {

    // 允许合并同一应用碎片事件的最大时间间隔容差（毫秒，120 秒内的同应用碎片均合并为单一区间）
    private const val DEFAULT_MERGE_TOLERANCE_MS = 120_000L

    /**
     * 将输入的事件列表按应用包名与时间窗口进行深度去重合并。
     *
     * @param rawEvents 原始离散时间轴事件列表 [List<AppTimelineEvent>]
     * @param mergeToleranceMs 判定同应用连续事件的最大时间间隔容差（毫秒）
     * @return 合并去重后的精炼事件列表 [List<AppTimelineEvent>]
     */
    fun mergeAppEvents(
        rawEvents: List<AppTimelineEvent>,
        mergeToleranceMs: Long = DEFAULT_MERGE_TOLERANCE_MS
    ): List<AppTimelineEvent> {
        if (rawEvents.isEmpty()) return emptyList()

        // 1. 先按包名分组，对同一应用的离散时段进行区间归并
        val groupedByPkg = rawEvents.groupBy { it.packageName }
        val mergedGroupedList = mutableListOf<AppTimelineEvent>()

        for ((_, eventsForPkg) in groupedByPkg) {
            val sorted = eventsForPkg.sortedBy { it.startTime }
            var current = sorted[0]

            for (i in 1 until sorted.size) {
                val next = sorted[i]
                if (next.startTime <= (current.endTime + mergeToleranceMs)) {
                    val newEndTime = max(current.endTime, next.endTime)
                    val newDuration = (newEndTime - current.startTime).coerceAtLeast(0L)
                    val totalEnergy = if (current.energyMwh != null || next.energyMwh != null) {
                        (current.energyMwh ?: 0.0) + (next.energyMwh ?: 0.0)
                    } else null
                    val peakPower = max(current.peakPowerMw ?: 0.0, next.peakPowerMw ?: 0.0).takeIf { it > 0.0 }
                    val avgPower = if (totalEnergy != null && newDuration > 0L) {
                        EnergyCalculator.calculateAveragePowerMw(totalEnergy, newDuration)
                    } else {
                        current.averagePowerMw ?: next.averagePowerMw
                    }

                    current = current.copy(
                        endTime = newEndTime,
                        durationMs = newDuration,
                        screenOn = current.screenOn || next.screenOn,
                        energyMwh = totalEnergy,
                        averagePowerMw = avgPower,
                        peakPowerMw = peakPower,
                        cpuTimeMs = current.cpuTimeMs + next.cpuTimeMs,
                        networkBytes = current.networkBytes + next.networkBytes,
                        wakelockTimeMs = current.wakelockTimeMs + next.wakelockTimeMs,
                        gpsTimeMs = current.gpsTimeMs + next.gpsTimeMs
                    )
                } else {
                    mergedGroupedList.add(current)
                    current = next
                }
            }
            mergedGroupedList.add(current)
        }

        // 2. 将所有合并后的事件按起始时间全局升序排列返回
        return mergedGroupedList.sortedBy { it.startTime }
    }

    /**
     * 合并屏幕亮灭状态时间区间，消除细微闪烁断点。
     *
     * @param screenEvents 原始屏幕状态事件列表 [List<ScreenEvent>]
     * @return 合并后的屏幕状态事件列表 [List<ScreenEvent>]
     */
    fun mergeScreenEvents(screenEvents: List<ScreenEvent>): List<ScreenEvent> {
        if (screenEvents.isEmpty()) return emptyList()
        val sorted = screenEvents.sortedBy { it.startTime }
        val result = mutableListOf<ScreenEvent>()

        var current = sorted[0]
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (current.isScreenOn == next.isScreenOn && next.startTime <= current.endTime + 2000L) {
                current = current.copy(endTime = max(current.endTime, next.endTime))
            } else {
                result.add(current)
                current = next
            }
        }
        result.add(current)
        return result
    }
}
