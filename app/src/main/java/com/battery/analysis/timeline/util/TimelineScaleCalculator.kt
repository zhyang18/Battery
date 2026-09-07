package com.battery.analysis.timeline.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 时间轴尺度与时间刻度计算工具类。
 * 负责时间戳与画布 X 坐标之间的精确双向转换，并根据当前可视时间跨度动态生成 4 ~ 8 个不重叠的时间刻度点。
 */
object TimelineScaleCalculator {

    /**
     * 时间刻度标线实体数据类。
     *
     * @property timestamp 刻度对应的物理时间戳（毫秒）
     * @property xRatio 刻度在当前可视区域内的归一化比例（0.0f ~ 1.0f）
     * @property label 刻度展示文本（如 "14:52" 或 "14:58:30"）
     */
    data class TimeTick(
        val timestamp: Long,
        val xRatio: Float,
        val label: String
    )

    // 候选刻度步长数组（毫秒）：1m, 2m, 5m, 10m, 15m, 30m, 1h, 2h, 4h, 6h, 12h, 24h（最小 1 分钟，不显示秒）
    private val CANDIDATE_INTERVALS_MS = longArrayOf(
        60_000L,          // 1m
        120_000L,         // 2m
        300_000L,         // 5m
        600_000L,         // 10m
        900_000L,         // 15m
        1_800_000L,       // 30m
        3_600_000L,       // 1h
        7_200_000L,       // 2h
        14_400_000L,      // 4h
        21_600_000L,      // 6h
        43_200_000L,      // 12h
        86_400_000L       // 24h
    )

    /**
     * 将物理时间戳转换为当前可视画布上的绝对 X 像素坐标。
     *
     * @param timestamp 目标时间戳（毫秒）
     * @param visibleStartTs 可视区域起始时间戳（毫秒）
     * @param visibleEndTs 可视区域结束时间戳（毫秒）
     * @param width 画布可视绘制区宽度（像素）
     * @param leftMargin 左边距偏移量（像素，默认 0f）
     * @return 对应的画布 X 坐标（像素）
     */
    fun timeToX(
        timestamp: Long,
        visibleStartTs: Long,
        visibleEndTs: Long,
        width: Float,
        leftMargin: Float = 0f
    ): Float {
        val totalSpan = (visibleEndTs - visibleStartTs).coerceAtLeast(1L)
        val progress = (timestamp - visibleStartTs).toFloat() / totalSpan
        return leftMargin + progress * width
    }

    /**
     * 将画布上的 X 像素坐标反向转换为物理时间戳。
     *
     * @param x 画布 X 坐标（像素）
     * @param visibleStartTs 可视区域起始时间戳（毫秒）
     * @param visibleEndTs 可视区域结束时间戳（毫秒）
     * @param width 画布可视绘制区宽度（像素）
     * @param leftMargin 左边距偏移量（像素，默认 0f）
     * @return 对应的物理时间戳（毫秒）
     */
    fun xToTime(
        x: Float,
        visibleStartTs: Long,
        visibleEndTs: Long,
        width: Float,
        leftMargin: Float = 0f
    ): Long {
        if (width <= 0f) return visibleStartTs
        val ratio = ((x - leftMargin) / width).coerceIn(0f, 1f)
        val totalSpan = (visibleEndTs - visibleStartTs).coerceAtLeast(1L)
        return visibleStartTs + (ratio * totalSpan).toLong()
    }

    /**
     * 动态计算当前可视时间范围内的展示时间刻度序列：
     * 起点固定显示放电开始时间（visibleStartTs），终点固定显示当前时间点（visibleEndTs），
     * 中间根据时间跨度自适应分布 0 ~ 3 个不重叠的时间刻度。
     * 时间格式统一采用 "HH:mm"（跨天则采用 "MM/dd HH:mm"），不显示秒。
     *
     * @param visibleStartTs 可视起始时间戳（毫秒）
     * @param visibleEndTs 可视结束时间戳（毫秒）
     * @return 计算得到的刻度点序列 [List<TimeTick>]
     */
    fun calculateTicks(
        visibleStartTs: Long,
        visibleEndTs: Long
    ): List<TimeTick> {
        val duration = (visibleEndTs - visibleStartTs).coerceAtLeast(1000L)

        // 时间格式统一采用 "HH:mm"（跨天则采用 "MM/dd HH:mm"），不显示秒
        val formatter = if (duration > 24 * 3_600_000L) {
            SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
        } else {
            SimpleDateFormat("HH:mm", Locale.getDefault())
        }

        val startLabel = formatter.format(Date(visibleStartTs))
        val endLabel = formatter.format(Date(visibleEndTs))

        val ticks = mutableListOf<TimeTick>()

        // 1. 固定添加起点（放电开始时间，xRatio = 0.0f）
        ticks.add(
            TimeTick(
                timestamp = visibleStartTs,
                xRatio = 0.0f,
                label = startLabel
            )
        )

        // 2. 根据时间跨度计算中间最佳刻度（避免与起点和终点重叠）
        var bestInterval = CANDIDATE_INTERVALS_MS[0]
        var minDiff = Int.MAX_VALUE
        val targetTickCount = 4

        for (interval in CANDIDATE_INTERVALS_MS) {
            val count = (duration / interval).toInt()
            val diff = kotlin.math.abs(count - targetTickCount)
            if (diff < minDiff && count in 2..6) {
                minDiff = diff
                bestInterval = interval
            }
        }

        // 向上对齐到步长整数倍起始时刻
        val firstTickTime = ((visibleStartTs / bestInterval) + 1) * bestInterval
        var currentTs = firstTickTime
        var lastRatio = 0.0f

        val intermediateTicks = mutableListOf<TimeTick>()
        while (currentTs < visibleEndTs) {
            val ratio = (currentTs - visibleStartTs).toFloat() / duration
            val label = formatter.format(Date(currentTs))
            // 确保与起点 (0.0f) 和终点 (1.0f) 保持足够的横向安全距离（至少 18% 间距），且与两端标签不重复
            if (ratio in 0.18f..0.82f && (ratio - lastRatio) >= 0.18f && label != startLabel && label != endLabel) {
                intermediateTicks.add(
                    TimeTick(
                        timestamp = currentTs,
                        xRatio = ratio,
                        label = label
                    )
                )
                lastRatio = ratio
            }
            currentTs += bestInterval
        }

        ticks.addAll(intermediateTicks)

        // 3. 固定添加终点（当前时间点，xRatio = 1.0f）
        ticks.add(
            TimeTick(
                timestamp = visibleEndTs,
                xRatio = 1.0f,
                label = endLabel
            )
        )

        return ticks
    }
}
