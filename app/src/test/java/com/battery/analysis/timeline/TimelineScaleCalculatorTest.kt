package com.battery.analysis.timeline

import com.battery.analysis.timeline.util.TimelineScaleCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时间轴尺度与坐标转换计算器单元测试套件。
 * 验证时间戳与屏幕像素坐标之间的双向高精度投影、边界对齐及零死区特性。
 */
class TimelineScaleCalculatorTest {

    /**
     * 验证图表左右对称边距（如各 8dp）下，起始与结束时间戳与内容边界像素完全对称对齐。
     */
    @Test
    fun testBoundaryAlignment_NoDeadzone() {
        val startTs = 1_000_000L
        val endTs = 2_000_000L // 跨度 1000 秒
        val contentLeft = 20f   // 对应 8dp (2.5 密度)
        val contentWidth = 900f // 有效绘图宽度

        // 1. 起始点时间戳应当精确投影到 contentLeft
        val startX = TimelineScaleCalculator.timeToX(startTs, startTs, endTs, contentWidth, contentLeft)
        assertEquals(contentLeft, startX, 0.001f)

        // 2. 起始点坐标反向映射时间戳应当严格等于 startTs
        val mappedStartTs = TimelineScaleCalculator.xToTime(contentLeft, startTs, endTs, contentWidth, contentLeft)
        assertEquals(startTs, mappedStartTs)

        // 3. 终点坐标反向映射时间戳应当严格等于 endTs
        val contentRight = contentLeft + contentWidth
        val mappedEndTs = TimelineScaleCalculator.xToTime(contentRight, startTs, endTs, contentWidth, contentLeft)
        assertEquals(endTs, mappedEndTs)
    }

    /**
     * 验证微小位移下的时间戳单调递增性，杜绝因边距不一致导致的左侧盲区或卡死现象。
     */
    @Test
    fun testMicroMovementResponsiveness_StrictlyMonotonic() {
        val startTs = 1_000_000L
        val endTs = 4_300_000L // 3300 秒（约 55 分钟）
        val contentLeft = 20f   // 8dp
        val contentWidth = 900f

        var lastTs = startTs
        // 从 contentLeft 开始，每次右移 2 像素，验证时间戳严格单调递增
        for (step in 1..20) {
            val curX = contentLeft + step * 2f
            val curTs = TimelineScaleCalculator.xToTime(curX, startTs, endTs, contentWidth, contentLeft)
            assertTrue(
                "在 X=$curX 时时间戳应当严格大于前一时刻，当前=$curTs，前值=$lastTs",
                curTs > lastTs
            )
            lastTs = curTs
        }
    }

    /**
     * 验证用户上报的真实场景：起始时刻 13:56:24，总时长 55 分钟，游标在左侧区域时不被死区截断。
     */
    @Test
    fun testRealUserScenario_CursorAtLeftAreaNotClamped() {
        // 13:56:24 对应的基准时间戳
        val startTs = 1_700_000_000_000L
        val endTs = startTs + 55 * 60 * 1000L // 55 分钟后 (14:51:24)
        val density = 2.75f
        val contentLeft = 8f * density // 22px
        val totalWidth = 1080f
        val contentRight = totalWidth - 8f * density // 1058px
        val contentWidth = contentRight - contentLeft

        // 目标时间 13:59:18（相差 2分54秒，即 174 秒）
        val targetTs = startTs + 174 * 1000L
        val expectedX = TimelineScaleCalculator.timeToX(targetTs, startTs, endTs, contentWidth, contentLeft)

        // 模拟游标触摸：使用修正后的对称边距 contentLeft 和 contentRight 进行截断
        val clampedX = expectedX.coerceIn(contentLeft, contentRight)
        val computedTs = TimelineScaleCalculator.xToTime(clampedX, startTs, endTs, contentWidth, contentLeft)

        // 验证计算出的时间戳与目标时间戳完全一致（允许毫秒取整误差 < 1000ms）
        val deltaMs = Math.abs(computedTs - targetTs)
        assertTrue("计算时间戳应当与 13:59:18 误差在 1 秒以内，实际差值=${deltaMs}ms", deltaMs < 1000L)
    }
}
