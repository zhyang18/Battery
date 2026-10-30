package com.battery.analysis.util

import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 气泡提示弹框排版辅助类单元测试。
 * 验证对齐方式、悬挂缩进以及子级描述行对齐父级圆点正文的跨行排版规范。
 */
class BubbleTooltipHelperTest {

    /**
     * 自定义测试用 Paint，精确模拟字符测量宽度，用于严格验证数学与排版几何对齐。
     */
    private class TestPaint : Paint() {
        override fun measureText(text: String?): Float {
            if (text == null) return 0f
            var width = 0f
            var i = 0
            while (i < text.length) {
                val codePoint = text.codePointAt(i)
                width += when {
                    codePoint == '•'.code -> 12f
                    codePoint == ' '.code -> 4f
                    codePoint == 0x1F4A1 -> 16f // 💡 emoji
                    else -> 14f // 模拟汉字或普通英文字符宽度
                }
                i += Character.charCount(codePoint)
            }
            return width
        }

        override fun measureText(text: CharSequence?, start: Int, end: Int): Float {
            if (text == null) return 0f
            return measureText(text.subSequence(start, end).toString())
        }
    }

    /**
     * 测试子级描述行计算出的最大期望宽度已包含前置差额边距，确保气泡总宽度不会提前折行。
     */
    @Test
    fun testCalculateMaxDesiredLineWidth_IncludesSubItemExtraIndent() {
        val paint = TestPaint()
        // "• " 宽度 = 12 + 4 = 16px
        // "  统计范围：最近 7 天" 宽度 = (4*2) + 14*11 = 8 + 154 = 162px
        // 前置补齐边距 extraIndent = 16 - 8 = 8px
        // 期望该行总绘制宽度 = 162 + 8 = 170px
        val rawMessage = "• 升级模型(v2.2)：16h49m35s\n  统计范围：最近 7 天"
        val maxWidth = BubbleTooltipHelper.calculateMaxDesiredLineWidth(paint, rawMessage)

        // 第一行: "• " (16px) + "升级模型(v2.2)：16h49m35s" (14*20 = 280px) -> 296px
        // 第二行: 170px
        // 最大宽度应为第一行 296px
        assertEquals(296f, maxWidth, 0.01f)

        // 若第二行文本本身非常长，验证 extraIndent 确实增加到第二行宽度中
        val longSubItemMessage = "  统计范围：自适应扩展至近 18.5 天超长描述测试"
        // text: 4个空格(16px) + 23个其他字符(322px) = 338px
        // extraIndent = 16 - 8 = 8px
        // 期望总宽度 = 338 + 8 = 346px
        val subMaxWidth = BubbleTooltipHelper.calculateMaxDesiredLineWidth(paint, longSubItemMessage)
        assertEquals(346f, subMaxWidth, 0.01f)
    }

    /**
     * 测试空串与空白文案安全处理。
     */
    @Test
    fun testCalculateMaxDesiredLineWidth_EmptyStringReturnsZero() {
        val paint = TestPaint()
        assertEquals(0f, BubbleTooltipHelper.calculateMaxDesiredLineWidth(paint, ""), 0.01f)
    }

    /**
     * 测试当指定 forceAbove 为 true 时，无论上下空间几何大小，决策结果始终为展示在上方。
     */
    @Test
    fun testDecideBubblePlacement_ForceAboveAlwaysTrue() {
        val result = BubbleTooltipHelper.decideBubblePlacement(
            forceAbove = true,
            spaceAbove = 100,
            spaceBelow = 1000,
            idealHeight = 500
        )
        assertEquals(true, result)
    }

    /**
     * 测试当上方空间充裕且可完整容纳气泡理想高度时，优先展示在上方。
     */
    @Test
    fun testDecideBubblePlacement_SpaceAboveSufficient() {
        val result = BubbleTooltipHelper.decideBubblePlacement(
            forceAbove = false,
            spaceAbove = 600,
            spaceBelow = 800,
            idealHeight = 400
        )
        assertEquals(true, result)
    }

    /**
     * 测试当上方空间不足但下方空间充裕能容纳时，智能选择展示在下方。
     */
    @Test
    fun testDecideBubblePlacement_SpaceBelowSufficient() {
        val result = BubbleTooltipHelper.decideBubblePlacement(
            forceAbove = false,
            spaceAbove = 200,
            spaceBelow = 800,
            idealHeight = 500
        )
        assertEquals(false, result)
    }

    /**
     * 测试当内容过多导致上方与下方均无法完整容纳气泡时，自动择优选择垂直空间更充裕的一侧展示。
     */
    @Test
    fun testDecideBubblePlacement_OverflowPrefersLargerSpace() {
        // 下方空间显著大于上方空间时，选择下方
        val resultBelow = BubbleTooltipHelper.decideBubblePlacement(
            forceAbove = false,
            spaceAbove = 250,
            spaceBelow = 1200,
            idealHeight = 1800
        )
        assertEquals(false, resultBelow)

        // 上方空间显著大于下方空间时，选择上方
        val resultAbove = BubbleTooltipHelper.decideBubblePlacement(
            forceAbove = false,
            spaceAbove = 1300,
            spaceBelow = 300,
            idealHeight = 1800
        )
        assertEquals(true, resultAbove)
    }

    /**
     * 测试气泡最大高度限制计算，确保既不突破对应方向可用空间，也不超出屏幕比例安全阈值。
     */
    @Test
    fun testCalculateMaxAllowedHeight_LimitsByAvailableSpaceAndScreenRatio() {
        val density = 3.0f
        val screenHeight = 2400
        // 屏幕 70% 为 1680px

        // 情况 1：空间小于 70% 屏幕，受限于可用空间（例如 900px）
        val height1 = BubbleTooltipHelper.calculateMaxAllowedHeight(
            isAbove = false,
            spaceAbove = 300,
            spaceBelow = 900,
            screenHeight = screenHeight,
            density = density
        )
        assertEquals(900, height1)

        // 情况 2：空间大于 70% 屏幕（例如 2000px），受限于 70% 阈值（1680px）
        val height2 = BubbleTooltipHelper.calculateMaxAllowedHeight(
            isAbove = false,
            spaceAbove = 200,
            spaceBelow = 2000,
            screenHeight = screenHeight,
            density = density
        )
        assertEquals(1680, height2)

        // 情况 3：空间极小，保证最低基础高度 (60 * density = 180px)
        val height3 = BubbleTooltipHelper.calculateMaxAllowedHeight(
            isAbove = true,
            spaceAbove = 50,
            spaceBelow = 1000,
            screenHeight = screenHeight,
            density = density
        )
        assertEquals(180, height3)
    }

    /**
     * 测试最终屏幕 Y 坐标计算，验证展示在上方或下方时紧贴锚点边界，杜绝侵占锚点或倒错。
     */
    @Test
    fun testCalculateFinalScreenY_PositionsCorrectlyWithoutOverlappingAnchor() {
        val margin = 30
        val gap = 6
        val screenHeight = 2400

        // 场景 A：气泡在下方展示（anchorTopY=400, anchorBottomY=600, popupHeight=1000）
        // 气泡顶部紧贴在 anchorBottomY + gap = 606，气泡底部 606 + 1000 = 1606 <= 2400 - 30
        val yBelow = BubbleTooltipHelper.calculateFinalScreenY(
            isAbove = false,
            anchorTopY = 400,
            anchorBottomY = 600,
            popupHeight = 1000,
            gap = gap,
            margin = margin,
            screenHeight = screenHeight
        )
        assertEquals(606, yBelow)

        // 场景 B：气泡在上方展示（anchorTopY=1800, anchorBottomY=2000, popupHeight=1000）
        // 气泡底部紧贴在 anchorTopY - gap = 1794，气泡顶部 1800 - 1000 - 6 = 794 >= 30
        val yAbove = BubbleTooltipHelper.calculateFinalScreenY(
            isAbove = true,
            anchorTopY = 1800,
            anchorBottomY = 2000,
            popupHeight = 1000,
            gap = gap,
            margin = margin,
            screenHeight = screenHeight
        )
        assertEquals(794, yAbove)
    }
}
