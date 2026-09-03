package com.battery.analysis.ui.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.battery.analysis.R
import com.battery.analysis.model.HealthTrendPoint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 电池健康度衰减趋势折线图自定义 View。
 * 基于原生 Canvas 硬件加速绘制平滑贝塞尔拟合曲线、渐变阴影填充、自适应刻度网格及手势交互 Tooltip 悬浮气泡卡片。
 *
 * @param context Android 上下文环境
 * @param attrs XML 属性集合
 * @param defStyleAttr 默认样式属性
 */
class HealthTrendChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dataPoints = mutableListOf<HealthTrendPoint>()

    // 复用坐标点对象池与着色器缓存，实现重绘与拖拽手势过程中的零 GC 分配
    private val cachedPointList = mutableListOf<PointF>()
    private val tooltipRect = RectF()
    private var lastGradientTopY = Float.NaN
    private var lastGradientBottomY = Float.NaN

    // 预计算缓存尺寸标量，避免在每一帧 onDraw 中重复计算 displayMetrics
    private val dp1 = dpToPx(1f)
    private val dp2_2 = dpToPx(2.2f)
    private val dp2_5 = dpToPx(2.5f)
    private val dp3 = dpToPx(3f)
    private val dp3_5 = dpToPx(3.5f)
    private val dp4 = dpToPx(4f)
    private val dp4_5 = dpToPx(4.5f)
    private val dp6 = dpToPx(6f)
    private val dp8 = dpToPx(8f)
    private val dp8_5 = dpToPx(8.5f)
    private val dp10 = dpToPx(10f)
    private val dp12 = dpToPx(12f)
    private val dp14 = dpToPx(14f)
    private val dp16 = dpToPx(16f)
    private val dp20 = dpToPx(20f)
    private val dp28 = dpToPx(28f)
    private val dp38 = dpToPx(38f)
    private val dp46 = dpToPx(46f)
    private val dp62 = dpToPx(62f)

    private val sp10 = spToPx(10f)
    private val sp10_5 = spToPx(10.5f)
    private val sp11 = spToPx(11f)
    private val sp14 = spToPx(14f)

    // 绘制画笔
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp2_5
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#10B981")
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val pointOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#10B981")
    }

    private val pointInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.white)
    }

    private val pointHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#3310B981")
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp1
        color = Color.parseColor("#1F888888")
        pathEffect = DashPathEffect(floatArrayOf(dp4, dp4), 0f)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp10_5
        color = Color.parseColor("#9CA3AF")
    }

    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(1.2f)
        color = Color.parseColor("#818CF8")
        pathEffect = DashPathEffect(floatArrayOf(dp3, dp3), 0f)
    }

    private val tooltipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#EE1F2937")
    }

    private val tooltipStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp1
        color = Color.parseColor("#40818CF8")
    }

    private val tooltipTextTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp11
        color = Color.parseColor("#F9FAFB")
        isFakeBoldText = true
    }

    private val tooltipTextDescPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp10_5
        color = Color.parseColor("#D1D5DB")
    }

    // 复用 Path 对象降低滑动过程中的 GC 开销
    private val linePath = Path()
    private val fillPath = Path()
    private val gridPath = Path()

    // 交互状态
    private var selectedIndex: Int = -1
    private var isTouching: Boolean = false

    // 动态边界
    private var minY = 80f
    private var maxY = 100f

    /**
     * 设置并更新健康度趋势数据列表。
     *
     * @param points 按时间正序排列的趋势点列表
     */
    fun setData(points: List<HealthTrendPoint>) {
        dataPoints.clear()
        dataPoints.addAll(points)
        selectedIndex = -1
        calculateBoundaries()
        invalidate()
    }

    /**
     * 根据当前数据动态计算 Y 轴上下边界范围。
     */
    private fun calculateBoundaries() {
        if (dataPoints.isEmpty()) {
            minY = 80f
            maxY = 100f
            return
        }

        var minVal = Float.MAX_VALUE
        var maxVal = Float.MIN_VALUE

        for (pt in dataPoints) {
            if (pt.health < minVal) minVal = pt.health
            if (pt.health > maxVal) maxVal = pt.health
        }

        // 留出适度安全区间
        minY = max(0f, (minVal - 3f).coerceAtMost(90f))
        maxY = min(100f, (maxVal + 2f).coerceAtLeast(100f))

        if (maxY - minY < 5f) {
            minY = max(0f, maxY - 10f)
        }
    }

    /**
     * 测量并绘制折线图。
     *
     * @param canvas 画布
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val paddingLeft = dpToPx(38f)
        val paddingRight = dpToPx(16f)
        val paddingTop = dpToPx(20f)
        val paddingBottom = dpToPx(28f)

        val chartWidth = width - paddingLeft - paddingRight
        val chartHeight = height - paddingTop - paddingBottom

        if (chartWidth <= 0 || chartHeight <= 0) return

        // 1. 绘制 Y 轴辅助网格虚线与文字刻度
        drawGridAndLabels(canvas, paddingLeft, paddingTop, chartWidth, chartHeight)

        if (dataPoints.isEmpty()) {
            drawEmptyHint(canvas, paddingLeft + chartWidth / 2f, paddingTop + chartHeight / 2f)
            return
        }

        // 2. 计算每个数据点的坐标
        val coords = calculateCoordinates(paddingLeft, paddingTop, chartWidth, chartHeight)

        // 3. 绘制渐变填充区域与贝塞尔折线
        drawChartCurveAndFill(canvas, coords, paddingTop + chartHeight)

        // 4. 绘制数据节点圆点
        drawDataNodes(canvas, coords)

        // 5. 绘制手势选中时的十字标尺与 Tooltip 浮窗
        if (isTouching && selectedIndex in coords.indices) {
            drawTooltip(canvas, coords[selectedIndex], dataPoints[selectedIndex], paddingTop, chartHeight)
        }
    }

    /**
     * 绘制 Y 轴虚线网格与刻度标签。
     *
     * @param canvas 画布
     * @param left 左起始偏移
     * @param top 顶部偏移
     * @param chartWidth 图表净宽
     * @param chartHeight 图表净高
     */
    private fun drawGridAndLabels(canvas: Canvas, left: Float, top: Float, chartWidth: Float, chartHeight: Float) {
        val steps = 3
        for (i in 0..steps) {
            val ratio = i.toFloat() / steps
            val y = top + chartHeight * (1f - ratio)
            val value = minY + (maxY - minY) * ratio

            // 绘制横向网格虚线
            gridPath.reset()
            gridPath.moveTo(left, y)
            gridPath.lineTo(left + chartWidth, y)
            canvas.drawPath(gridPath, gridPaint)

            // 绘制左侧刻度文字
            val label = "${value.toInt()}%"
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(label, left - dpToPx(6f), y + dpToPx(3.5f), textPaint)
        }
    }

    /**
     * 当暂无数据时在中心绘制温馨提示。
     *
     * @param canvas 画布
     * @param centerX 中心X
     * @param centerY 中心Y
     */
    private fun drawEmptyHint(canvas: Canvas, centerX: Float, centerY: Float) {
        textPaint.textAlign = Paint.Align.CENTER
        val hint = context.getString(com.battery.analysis.R.string.trend_empty_hint)
        canvas.drawText(hint, centerX, centerY, textPaint)
    }

    /**
     * 计算数据点在屏幕上的具体像素坐标。
     * 复用预分配的 cachedPointList 对象池，杜绝在每一帧绘制时分配临时 List 与 PointF 对象。
     *
     * @param left 左偏移
     * @param top 上偏移
     * @param chartWidth 宽度
     * @param chartHeight 高度
     * @return 复用的像素坐标点列表视图
     */
    private fun calculateCoordinates(left: Float, top: Float, chartWidth: Float, chartHeight: Float): List<PointF> {
        val size = dataPoints.size
        while (cachedPointList.size < size) {
            cachedPointList.add(PointF())
        }

        if (size == 1) {
            val pt = dataPoints[0]
            val x = left + chartWidth / 2f
            val rangeY = (maxY - minY).let { if (it <= 0f) 1f else it }
            val yRatio = ((pt.health - minY) / rangeY).coerceIn(0f, 1f)
            val y = top + chartHeight * (1f - yRatio)
            cachedPointList[0].set(x, y)
            return cachedPointList.subList(0, 1)
        }

        val stepX = chartWidth / (size - 1).toFloat()
        val rangeY = (maxY - minY).let { if (it <= 0f) 1f else it }
        for (i in 0 until size) {
            val pt = dataPoints[i]
            val x = left + i * stepX
            val yRatio = ((pt.health - minY) / rangeY).coerceIn(0f, 1f)
            val y = top + chartHeight * (1f - yRatio)
            cachedPointList[i].set(x, y)
        }

        return cachedPointList.subList(0, size)
    }

    /**
     * 绘制贝塞尔曲线与下方半透明翡翠绿渐变填充。
     * 直接传递浮点控制点坐标，消除临时 PointF 分配；并且仅在坐标边界发生变化时更新 Shader。
     *
     * @param canvas 画布
     * @param coords 坐标列表
     * @param bottomY 底部基准线Y坐标
     */
    private fun drawChartCurveAndFill(canvas: Canvas, coords: List<PointF>, bottomY: Float) {
        if (coords.size <= 1) return

        linePath.reset()
        fillPath.reset()

        val firstPoint = coords[0]
        linePath.moveTo(firstPoint.x, firstPoint.y)
        fillPath.moveTo(firstPoint.x, bottomY)
        fillPath.lineTo(firstPoint.x, firstPoint.y)

        var minYCoord = firstPoint.y

        for (i in 1 until coords.size) {
            val p0 = coords[i - 1]
            val p1 = coords[i]
            if (p1.y < minYCoord) minYCoord = p1.y

            val midX = p0.x + (p1.x - p0.x) / 2f

            linePath.cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
            fillPath.cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
        }

        fillPath.lineTo(coords.last().x, bottomY)
        fillPath.close()

        // 仅在 Y 轴跨度发生改变时重建底层 Skia LinearGradient 着色器
        if (minYCoord != lastGradientTopY || bottomY != lastGradientBottomY) {
            lastGradientTopY = minYCoord
            lastGradientBottomY = bottomY
            fillPaint.shader = LinearGradient(
                0f, minYCoord,
                0f, bottomY,
                Color.parseColor("#3810B981"),
                Color.parseColor("#0210B981"),
                Shader.TileMode.CLAMP
            )
        }

        // 绘制渐变填充与折线
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)
    }

    /**
     * 绘制数据节点圆点及 X 轴采样时间标签。
     *
     * @param canvas 画布
     * @param coords 坐标列表
     */
    private fun drawDataNodes(canvas: Canvas, coords: List<PointF>) {
        for (i in coords.indices) {
            val p = coords[i]
            val isLatest = (i == coords.size - 1)

            // 最新点展示呼吸光晕
            if (isLatest) {
                canvas.drawCircle(p.x, p.y, dp8_5, pointHaloPaint)
            }

            canvas.drawCircle(p.x, p.y, dp4_5, pointOuterPaint)
            canvas.drawCircle(p.x, p.y, dp2_2, pointInnerPaint)

            // 绘制 X 轴时间标签（首点、末点或间隔绘制）
            if (coords.size <= 5 || i == 0 || i == coords.size - 1 || i == coords.size / 2) {
                textPaint.textAlign = Paint.Align.CENTER
                val displayTime = dataPoints[i].displayTime
                canvas.drawText(displayTime, p.x, height - dp8, textPaint)
            }
        }
    }

    /**
     * 绘制手指触控交互时的垂直标尺虚线与悬浮 Tooltip 气泡卡片。
     * 复用预分配的 tooltipRect，避免每帧分配 RectF。
     *
     * @param canvas 画布
     * @param p 选中的数据点屏幕坐标
     * @param pt 数据实体
     * @param top 顶部偏移
     * @param chartHeight 图表净高
     */
    private fun drawTooltip(canvas: Canvas, p: PointF, pt: HealthTrendPoint, top: Float, chartHeight: Float) {
        // 1. 绘制垂直标尺
        canvas.drawLine(p.x, top, p.x, top + chartHeight, cursorPaint)

        // 2. 准备 Tooltip 文本
        val localizedCat = when (pt.category) {
            "系统api" -> context.getString(com.battery.analysis.R.string.tab_normal_api)
            "Shizuku" -> "Shizuku"
            "错误报告" -> context.getString(com.battery.analysis.R.string.tab_bugreport)
            else -> pt.category
        }
        val titleText = "【$localizedCat】${String.format(java.util.Locale.getDefault(), "%.2f%%", pt.health)}"
        val timeText = pt.captureTime
        val cycleText = pt.cycleCount?.let { context.getString(com.battery.analysis.R.string.trend_tooltip_cycle_format, it) } ?: ""
        val capText = pt.fullCapacity?.let { context.getString(com.battery.analysis.R.string.trend_tooltip_full_format, it) } ?: ""
        val extraText = listOf(cycleText, capText).filter { it.isNotBlank() }.joinToString(" | ")

        // 3. 计算气泡尺寸与位置
        val paddingH = dp10
        val paddingV = dp8
        val titleWidth = tooltipTextTitlePaint.measureText(titleText)
        val timeWidth = tooltipTextDescPaint.measureText(timeText)
        val extraWidth = if (extraText.isNotBlank()) tooltipTextDescPaint.measureText(extraText) else 0f

        val boxWidth = max(titleWidth, max(timeWidth, extraWidth)) + paddingH * 2
        val boxHeight = if (extraText.isNotBlank()) dp62 else dp46

        var boxLeft = p.x - boxWidth / 2f
        var boxTop = p.y - boxHeight - dp12

        // 边界防溢出纠偏
        if (boxLeft < dp8) boxLeft = dp8
        if (boxLeft + boxWidth > width - dp8) boxLeft = width - dp8 - boxWidth
        if (boxTop < dp4) boxTop = p.y + dp14

        tooltipRect.set(boxLeft, boxTop, boxLeft + boxWidth, boxTop + boxHeight)
        val cornerRadius = dp8

        // 4. 绘制卡片背景与描边
        canvas.drawRoundRect(tooltipRect, cornerRadius, cornerRadius, tooltipBgPaint)
        canvas.drawRoundRect(tooltipRect, cornerRadius, cornerRadius, tooltipStrokePaint)

        // 5. 绘制卡片内部文字
        var textY = boxTop + paddingV + sp10
        canvas.drawText(titleText, boxLeft + paddingH, textY, tooltipTextTitlePaint)

        textY += sp14
        canvas.drawText(timeText, boxLeft + paddingH, textY, tooltipTextDescPaint)

        if (extraText.isNotBlank()) {
            textY += sp14
            canvas.drawText(extraText, boxLeft + paddingH, textY, tooltipTextDescPaint)
        }
    }

    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDraggingHorizontally = false

    /**
     * 处理手势触控事件，仅在用户明确横向滑动探查图表时拦截消费，垂直滑动完全放行给父容器以确保列表滚动丝滑不冲突。
     * 手势跟踪采用 O(1) 索引算法，彻底免去滑动每帧重新计算与遍历点集的开销。
     *
     * @param event 触摸事件
     * @return 若消费横向手势返回 true，否则返回 false 放行给列表滚动
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (dataPoints.isEmpty()) return super.onTouchEvent(event)

        val paddingLeft = dp38
        val paddingRight = dp16
        val chartWidth = width - paddingLeft - paddingRight

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialTouchX = event.x
                initialTouchY = event.y
                isDraggingHorizontally = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = abs(event.x - initialTouchX)
                val dy = abs(event.y - initialTouchY)

                if (!isDraggingHorizontally) {
                    if (dx > touchSlop && dx > dy * 1.2f) {
                        isDraggingHorizontally = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    } else if (dy > touchSlop && dy > dx) {
                        parent?.requestDisallowInterceptTouchEvent(false)
                        return false
                    }
                }

                if (isDraggingHorizontally) {
                    isTouching = true
                    val touchX = event.x
                    val size = dataPoints.size
                    val closestIndex = if (size <= 1) {
                        0
                    } else {
                        val stepX = chartWidth / (size - 1).toFloat()
                        Math.round((touchX - paddingLeft) / stepX).toInt().coerceIn(0, size - 1)
                    }

                    if (selectedIndex != closestIndex) {
                        selectedIndex = closestIndex
                        invalidate()
                    }
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDraggingHorizontally) {
                    isDraggingHorizontally = false
                    isTouching = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    postDelayed({
                        if (!isTouching) {
                            selectedIndex = -1
                            invalidate()
                        }
                    }, 1500)
                    return true
                }
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return super.onTouchEvent(event)
    }

    private fun dpToPx(dp: Float): Float =
        android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)

    private fun spToPx(sp: Float): Float =
        android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
}
