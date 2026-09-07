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
     * @param width 画布可视宽度（像素）
     * @return 对应的画布 X 坐标（像素）
     */
    fun timeToX(
        timestamp: Long,
        visibleStartTs: Long,
        visibleEndTs: Long,
        width: Float
    ): Float {
        val totalSpan = (visibleEndTs - visibleStartTs).coerceAtLeast(1L)
        val progress = (timestamp - visibleStartTs).toFloat() / totalSpan
        return progress * width
    }

    /**
     * 将画布上的 X 像素坐标反向转换为物理时间戳。
     *
     * @param x 画布 X 坐标（像素）
     * @param visibleStartTs 可视区域起始时间戳（毫秒）
     * @param visibleEndTs 可视区域结束时间戳（毫秒）
     * @param width 画布可视宽度（像素）
     * @return 对应的物理时间戳（毫秒）
     */
    fun xToTime(
        x: Float,
        visibleStartTs: Long,
        visibleEndTs: Long,
        width: Float
    ): Long {
        if (width <= 0f) return visibleStartTs
        val ratio = (x / width).coerceIn(0f, 1f)
        val totalSpan = (visibleEndTs - visibleStartTs).coerceAtLeast(1L)
        return visibleStartTs + (ratio * totalSpan).toLong()
    }

    /**
     * 动态计算当前可视时间范围内最适合展示的时间刻度序列（维持在 3 ~ 6 个不重叠刻度，格式为 HH:mm，不包含秒）。
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
        val targetTickCount = 4

        // 寻找使屏幕刻度数在 3..6 个之间的最佳步长（最小为 1 分钟，杜绝秒级展示）
        var bestInterval = CANDIDATE_INTERVALS_MS[0]
        var minDiff = Int.MAX_VALUE

        for (interval in CANDIDATE_INTERVALS_MS) {
            val count = (duration / interval).toInt()
            val diff = kotlin.math.abs(count - targetTickCount)
            if (diff < minDiff && count in 2..6) {
                minDiff = diff
                bestInterval = interval
            }
        }

        // 时间格式统一采用 "HH:mm"（跨天则采用 "MM/dd HH:mm"），不显示秒
        val formatter = if (duration > 24 * 3_600_000L) {
            SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
        } else {
            SimpleDateFormat("HH:mm", Locale.getDefault())
        }

        val ticks = mutableListOf<TimeTick>()
        // 向上对齐到步长整数倍起始时刻
        val firstTickTime = ((visibleStartTs / bestInterval) + 1) * bestInterval

        var currentTs = firstTickTime
        var lastRatio = -1.0f
        var lastLabel = ""

        while (currentTs < visibleEndTs) {
            val ratio = (currentTs - visibleStartTs).toFloat() / duration
            val label = formatter.format(Date(currentTs))
            // 避免刻度贴紧左右边缘，且相邻刻度之间保持至少 15% 的横向距离防重叠，杜绝相同时间文本重复
            if (ratio in 0.05f..0.95f && (ratio - lastRatio) >= 0.14f && label != lastLabel) {
                ticks.add(
                    TimeTick(
                        timestamp = currentTs,
                        xRatio = ratio,
                        label = label
                    )
                )
                lastRatio = ratio
                lastLabel = label
            }
            currentTs += bestInterval
        }

        // 若自然对齐产生的刻度不足 2 个，采用均匀分布保底生成 3~4 个时间刻度
        if (ticks.size < 2) {
            ticks.clear()
            val ratios = floatArrayOf(0.12f, 0.40f, 0.68f, 0.90f)
            lastLabel = ""
            for (r in ratios) {
                val ts = visibleStartTs + (duration * r).toLong()
                val label = formatter.format(Date(ts))
                if (label != lastLabel) {
                    ticks.add(
                        TimeTick(
                            timestamp = ts,
                            xRatio = r,
                            label = label
                        )
                    )
                    lastLabel = label
                }
            }
        }

        return ticks
    }
}
