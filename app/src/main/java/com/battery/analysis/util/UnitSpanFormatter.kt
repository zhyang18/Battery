package com.battery.analysis.util

import android.content.res.Resources
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.MetricAffectingSpan
import android.util.TypedValue
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * 电池数据单位缩小格式化辅助工具类。
 * 针对项目中所有带单位的数据展示，自动识别数值后紧随的各类物理与工程单位，
 * 并将单位部分的字体大小比数值缩小指定号数（默认缩小 2sp），以实现专业、精致的图文混排排版层级。
 */
object UnitSpanFormatter {

    /**
     * 默认相比主数值缩小的字号量（单位：sp）。缩小 2 号即为缩小 2sp。
     */
    const val DEFAULT_REDUCE_SP = 2f

    /**
     * 允许缩小的最低字号下限（单位：sp），避免字号过小导致无法辨认。
     */
    const val MIN_UNIT_SP = 6f

    /**
     * 匹配紧跟在数字（允许存在 0 个或多个空格）后面的物理量、工程与时间单位。
     * 优先匹配复合单位与较长单位，杜绝短单位前缀截断问题。
     */
    val UNIT_REGEX = Regex(
        """(?<=\d)\s*(%/h|%/min|mAh/h|Wh/h|W/h|mW/h|mAh|mWh|kWh|Ah|Wh|mV|mA|mW|mΩ|uA|μA|uV|μV|°C|℃|°F|℉|kW|min|ms|V|v|W|J|Ω|%|h|d|s|m)(?![a-zA-Z0-9])"""
    )

    /**
     * 将给定的文本中所有紧随数值的单位部分缩小显示（比单位前面的数值缩小 2 号）。
     * 若文本为 null 或空，则原样返回；若不含匹配单位，则保留原文本对象。
     *
     * @param text 待格式化的原始文本对象 [CharSequence]，可包含现有 Span
     * @param targetUnitSp 指定单位的目标绝对字号（单位：sp）。若为 null，则采用动态自适应的 [SmallerUnitSpan]
     * @param reduceSp 当未指定 [targetUnitSp] 时，单位相比主文本缩小的字号量（单位：sp，默认 2sp）
     * @return 格式化后的富文本对象 [CharSequence]
     */
    fun format(
        text: CharSequence?,
        targetUnitSp: Float? = null,
        reduceSp: Float = DEFAULT_REDUCE_SP
    ): CharSequence {
        if (text == null || text.isEmpty()) {
            return text ?: ""
        }

        val rawStr = text.toString()
        val matches = UNIT_REGEX.findAll(rawStr).toList()
        if (matches.isEmpty()) {
            return text
        }

        val builder = if (text is Spannable) {
            SpannableStringBuilder(text)
        } else {
            SpannableStringBuilder(rawStr)
        }

        for (match in matches) {
            val start = match.range.first
            val end = match.range.last + 1

            // 1. 检查当前单位区间是否已存在显式绝对字号 Span
            val unitSpans = if (builder is Spanned) {
                builder.getSpans(start, end, AbsoluteSizeSpan::class.java)
            } else {
                emptyArray()
            }

            // 2. 检查单位紧邻的前置字符（数值末位）是否处于更小字号的 Span 区间（如括号内的子数值）
            val precedingSpans = if (builder is Spanned && start > 0) {
                builder.getSpans(start - 1, start, AbsoluteSizeSpan::class.java)
            } else {
                emptyArray()
            }

            val existingUnitSize = unitSpans.minOfOrNull { it.size }
            val existingPrecedingSize = precedingSpans.minOfOrNull { it.size }

            // 若单位本身已经具备小于等于最低下限或比前置主字号更紧凑的专用字号 Span（如 6sp），严禁放大覆盖
            if (existingUnitSize != null && existingPrecedingSize != null && existingUnitSize < existingPrecedingSize) {
                continue
            }

            val span = if (targetUnitSp != null) {
                // 若前置数值本身具有局部缩小字号（例如括号内数值为 8sp），单位字号应基于该局部数值缩小，严禁盲目采用全局 targetUnitSp
                val effectiveUnitSp = if (existingPrecedingSize != null && existingPrecedingSize.toFloat() < targetUnitSp) {
                    (existingPrecedingSize.toFloat() - reduceSp).coerceAtLeast(MIN_UNIT_SP)
                } else if (existingUnitSize != null && existingUnitSize.toFloat() < targetUnitSp) {
                    (existingUnitSize.toFloat() - reduceSp).coerceAtLeast(MIN_UNIT_SP)
                } else if (isInsideParentheses(rawStr, start, end)) {
                    // 若位于括号内且无显式 Span，则单位相比 targetUnitSp 进一步缩小，避免括号内单位显得过于突兀
                    (targetUnitSp - reduceSp).coerceAtLeast(MIN_UNIT_SP)
                } else {
                    targetUnitSp.coerceAtLeast(MIN_UNIT_SP)
                }
                AbsoluteSizeSpan(effectiveUnitSp.roundToInt(), true)
            } else {
                SmallerUnitSpan(reduceSp)
            }
            builder.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        return builder
    }

    /**
     * 判断指定文本区间中的单位是否处于未闭合的括号内。
     *
     * @param text 完整待检测字符串
     * @param start 单位起始下标
     * @param end 单位结束下标
     * @return 若该单位被成对的圆括号包裹则返回 true，否则返回 false
     */
    fun isInsideParentheses(text: CharSequence, start: Int, end: Int): Boolean {
        var openCount = 0
        for (i in 0 until start) {
            if (text[i] == '(') openCount++
            else if (text[i] == ')') openCount = (openCount - 1).coerceAtLeast(0)
        }
        if (openCount <= 0) return false
        for (i in end until text.length) {
            if (text[i] == ')') return true
            else if (text[i] == '(') return false
        }
        return false
    }

    /**
     * 将包含前置主要数值与括号百分比的文本（如 "13m44s" 与 "45.1%" 或 "0.406Wh" 与 "45.1%"）
     * 转换为复合图文混排富文本。
     * 主文本中的物理单位比主数值缩小 2 号（默认 10sp），括号及其内部数值缩小为 8sp，
     * 且括号内的百分比符号 '%' 进一步缩小至 6sp（比括号内数值再缩小 2 号），彻底解决括号内百分号符号突兀偏大的视觉问题。
     *
     * @param mainText 前置主要数值文本（如 "13m44s" 或 "0.406Wh"）
     * @param percentText 括号内的百分比文本（如 "45.1%" 或 "100%"）
     * @param parenthesisSp 括号及内部内容基准字号（单位：sp，默认 8sp）
     * @param reduceSp 单位相对数值缩小的字号量（单位：sp，默认 2sp）
     * @return 格式化后的富文本对象 [CharSequence]
     */
    fun formatValueWithSmallPercent(
        mainText: String,
        percentText: String,
        parenthesisSp: Float = 8f,
        reduceSp: Float = DEFAULT_REDUCE_SP
    ): CharSequence {
        val fullText = "$mainText($percentText)"
        val startIndex = mainText.length
        val spannable = SpannableStringBuilder(fullText)

        // 1. 括号及内部内容基准字号缩小至 8sp
        spannable.setSpan(
            AbsoluteSizeSpan(parenthesisSp.roundToInt(), true),
            startIndex,
            fullText.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        // 2. 括号内的百分比符号 '%' 单独缩小至 6sp（比括号内数值再缩小 2 号，杜绝突兀偏大）
        val percentSymbolSp = (parenthesisSp - reduceSp).coerceAtLeast(MIN_UNIT_SP).roundToInt()
        val percentIndex = fullText.lastIndexOf('%')
        if (percentIndex >= startIndex) {
            spannable.setSpan(
                AbsoluteSizeSpan(percentSymbolSp, true),
                percentIndex,
                percentIndex + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        // 3. 针对前置 mainText 中的物理与时间单位进行缩小排版（如 05h31m 中的 h、m）
        return format(spannable, reduceSp = reduceSp)
    }

    /**
     * 将包含单一数值与单位的字符串拆分为纯数值部分与纯单位部分。
     * 用于 Canvas 自定义 View 绘制时分别测量和绘制不同字号的数值与单位。
     *
     * @param text 待拆分的完整文本，例如 "+18.50W"、"35.2℃"、"5000mAh"
     * @return 若成功匹配到单位则返回由数值与单位组成的 [Pair]，若无单位或文本为空则返回 null
     */
    fun splitValueAndUnit(text: String?): Pair<String, String>? {
        if (text.isNullOrBlank()) return null
        val match = UNIT_REGEX.findAll(text).lastOrNull() ?: return null
        val unitStart = match.range.first
        val valuePart = text.substring(0, unitStart).trimEnd()
        val unitPart = text.substring(unitStart).trim()
        return if (valuePart.isNotEmpty() && unitPart.isNotEmpty()) {
            Pair(valuePart, unitPart)
        } else {
            null
        }
    }

    /**
     * 自定义度量影响 Span，在文本排版测量与绘制阶段动态获取当前画笔实际像素大小并减去指定 sp 像素量。
     * 适用于无法直接获取具体目标 [TextView] 实例的通用文本格式化场景。
     *
     * @param reduceSp 相对主数值缩小的字号量（单位：sp，默认 2sp）
     */
    class SmallerUnitSpan(
        private val reduceSp: Float = DEFAULT_REDUCE_SP
    ) : MetricAffectingSpan() {

        /**
         * 更新文本测量状态，减小测量画笔的字体尺寸。
         *
         * @param textPaint 用于文本测量的画笔对象
         */
        override fun updateMeasureState(textPaint: TextPaint) {
            apply(textPaint)
        }

        /**
         * 更新文本绘制状态，减小实际渲染画笔的字体尺寸。
         *
         * @param tp 用于文本绘制渲染的画笔对象
         */
        override fun updateDrawState(tp: TextPaint) {
            apply(tp)
        }

        /**
         * 核心执行函数：按系统显示度量标准将 reduceSp 换算为像素并从当前 textSize 中扣除。
         *
         * @param paint 目标文字画笔对象
         */
        private fun apply(paint: TextPaint) {
            val metrics = Resources.getSystem().displayMetrics
            val reducePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, reduceSp, metrics)
            val minPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, MIN_UNIT_SP, metrics)
            paint.textSize = (paint.textSize - reducePx).coerceAtLeast(minPx)
        }
    }
}

/**
 * [TextView] 扩展函数：设置带单位的数据文本，并自动将所有物理单位字号比当前数值缩小 2 号（即 2sp）。
 * 自动读取当前 TextView 的设置字号并精确计算出目标单位字号。
 *
 * @param text 待展示的文本内容，若为 null 则清空视图内容
 * @param reduceSp 单位比当前数值缩小的字号量（单位：sp，默认为 2sp）
 */
fun TextView.setUnitText(text: CharSequence?, reduceSp: Float = UnitSpanFormatter.DEFAULT_REDUCE_SP) {
    if (text == null) {
        this.text = null
        return
    }
    val scaledDensity = resources.displayMetrics.scaledDensity.takeIf { it > 0f } ?: 1f
    val currentSp = this.textSize / scaledDensity
    val targetUnitSp = (currentSp - reduceSp).coerceAtLeast(UnitSpanFormatter.MIN_UNIT_SP)
    this.text = UnitSpanFormatter.format(text, targetUnitSp = targetUnitSp)
}
