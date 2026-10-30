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
}
