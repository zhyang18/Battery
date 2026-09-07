package com.battery.analysis.timeline.util

import com.battery.analysis.timeline.domain.AppTimelineEvent
import com.battery.analysis.timeline.domain.TimelineEventMerger
import kotlin.math.max

/**
 * 时间轴 App 图标按时间槽平铺（Slot Tiling）与从下往上纵向堆叠排布算法工具类。
 * 将可视时间范围划分为均等的时间槽，在每个时间槽内检查处于活动状态的应用，并采用从底层向上的纵向堆叠策略，
 * 确保同一时间段内 App 持续使用时水平连续平铺显示小方块徽章，多个应用并发时从 Row 0 向上堆叠，左右与上下完全无重叠。
 */
object TimelineLayoutCalculator {

    /**
     * 带有时间槽索引与行号的 App 徽章渲染单元实体类。
     *
     * @property event 对应的原始 App 时间轴事件
     * @property slotIndex 对应的时间槽索引（从左到右）
     * @property rowIndex 分配的行索引（0 为最底行，依次向上递增）
     * @property left 徽章左边界像素坐标
     * @property top 徽章上边界像素坐标
     * @property right 徽章右边界像素坐标
     * @property bottom 徽章下边界像素坐标
     * @property centerX 徽章中心 X 像素坐标
     * @property centerY 徽章中心 Y 像素坐标
     */
    data class LaidOutAppSlotItem(
        val event: AppTimelineEvent,
        val slotIndex: Int,
        val rowIndex: Int,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val centerX: Float,
        val centerY: Float
    )

    /**
     * 计算 App 事件在时间轴上的分槽平铺与多行纵向堆叠排布。
     *
     * @param events App 时间轴事件列表 [List<AppTimelineEvent>]
     * @param visibleStartTs 当前可视起始时间戳（毫秒）
     * @param visibleEndTs 当前可视结束时间戳（毫秒）
     * @param canvasWidth 画布宽度（像素）
     * @param baseBottomY 最底行（Row 0）徽章的下边缘 Y 坐标
     * @param slotSizePx 每个 App 徽章方块的尺寸（宽高相等，像素）
     * @param slotGapPx 相邻时间槽之间的水平间距（像素）
     * @param rowGapPx 上下行之间的垂直间距（像素）
     * @param maxRows 允许向上堆叠的最大行数（默认 4 行）
     * @return 经过分槽和多行堆叠排布后的徽章渲染单元列表 [List<LaidOutAppSlotItem>]
     */
    fun calculateSlotItems(
        events: List<AppTimelineEvent>,
        visibleStartTs: Long,
        visibleEndTs: Long,
        canvasWidth: Float,
        baseBottomY: Float,
        slotSizePx: Float,
        slotGapPx: Float = 3f,
        rowGapPx: Float = 3f,
        maxRows: Int = 4
    ): List<LaidOutAppSlotItem> {
        if (events.isEmpty() || canvasWidth <= 0f || visibleEndTs <= visibleStartTs) return emptyList()

        val stepX = slotSizePx + slotGapPx
        if (stepX <= 0f) return emptyList()

        // 计算当前画布宽度下可容纳的时间槽总数量
        val numSlots = max(1, ((canvasWidth + slotGapPx) / stepX).toInt())
        val totalWidth = numSlots * stepX - slotGapPx
        val leftOffset = max(0f, (canvasWidth - totalWidth) / 2f)

        val totalTimeSpan = visibleEndTs - visibleStartTs
        val result = mutableListOf<LaidOutAppSlotItem>()

        // 先按包名深度合并同一应用的连续碎片时段
        val mergedEvents = TimelineEventMerger.mergeAppEvents(events)

        // 过滤保留在可视时间范围内的事件
        val visibleEvents = mergedEvents.filter {
            it.endTime >= visibleStartTs && it.startTime <= visibleEndTs
        }
        if (visibleEvents.isEmpty()) return emptyList()

        for (slotIndex in 0 until numSlots) {
            val slotLeft = leftOffset + slotIndex * stepX
            val slotRight = slotLeft + slotSizePx

            // 计算该时间槽对应的精确起始与结束时间戳
            val slotStartRatio = (slotLeft / canvasWidth).coerceIn(0f, 1f)
            val slotEndRatio = (slotRight / canvasWidth).coerceIn(0f, 1f)
            val slotStartTs = visibleStartTs + (totalTimeSpan * slotStartRatio).toLong()
            val slotEndTs = visibleStartTs + (totalTimeSpan * slotEndRatio).toLong()

            // 查找在该时间槽区间内处于活跃状态的应用
            val activeEvents = visibleEvents.filter {
                it.startTime <= slotEndTs && it.endTime >= slotStartTs
            }

            if (activeEvents.isNotEmpty()) {
                // 按包名去重并保留该包名下使用最长的事件
                val distinctEvents: List<AppTimelineEvent> = activeEvents.groupBy { it.packageName }
                    .values
                    .map { group: List<AppTimelineEvent> -> group.maxByOrNull { it.durationMs } ?: group.first() }

                // 确定性排序：起始时间更早的优先排在 Row 0（最底行），保持在时间推进过程中的行位置稳定性
                val sortedEvents = distinctEvents.sortedWith(
                    compareBy<AppTimelineEvent>({ it.startTime }, { it.packageName })
                )

                // 从 Row 0 向上堆叠排布
                for ((rowIndex, event) in sortedEvents.take(maxRows).withIndex()) {
                    val bottom = baseBottomY - rowIndex * (slotSizePx + rowGapPx)
                    val top = bottom - slotSizePx
                    val centerX = (slotLeft + slotRight) / 2f
                    val centerY = (top + bottom) / 2f

                    result.add(
                        LaidOutAppSlotItem(
                            event = event,
                            slotIndex = slotIndex,
                            rowIndex = rowIndex,
                            left = slotLeft,
                            top = top,
                            right = slotRight,
                            bottom = bottom,
                            centerX = centerX,
                            centerY = centerY
                        )
                    )
                }
            }
        }

        return result
    }
}
