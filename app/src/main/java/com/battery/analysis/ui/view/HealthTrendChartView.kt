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

    // 绘制画笔
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2.5f)
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
        color = Color.WHITE
    }

    private val pointHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#3310B981")
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(1f)
        color = Color.parseColor("#1F888888")
        pathEffect = DashPathEffect(floatArrayOf(dpToPx(4f), dpToPx(4f)), 0f)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = spToPx(10.5f)
        color = Color.parseColor("#9CA3AF")
    }

    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(1.2f)
        color = Color.parseColor("#818CF8")
        pathEffect = DashPathEffect(floatArrayOf(dpToPx(3f), dpToPx(3f)), 0f)
    }

    private val tooltipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#EE1F2937")
    }

    private val tooltipStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(1f)
        color = Color.parseColor("#40818CF8")
    }

    private val tooltipTextTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = spToPx(11f)
        color = Color.parseColor("#F9FAFB")
        isFakeBoldText = true
    }

    private val tooltipTextDescPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = spToPx(10.5f)
        color = Color.parseColor("#D1D5DB")
    }

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
            val path = Path().apply {
                moveTo(left, y)
                lineTo(left + chartWidth, y)
            }
            canvas.drawPath(path, gridPaint)

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
        canvas.drawText("暂无健康度快照，保存快照后将自动生成衰减趋势图", centerX, centerY, textPaint)
    }

    /**
     * 计算数据点在屏幕上的具体像素坐标。
     *
     * @param left 左偏移
     * @param top 上偏移
     * @param chartWidth 宽度
     * @param chartHeight 高度
     * @return 像素坐标点列表
     */
    private fun calculateCoordinates(left: Float, top: Float, chartWidth: Float, chartHeight: Float): List<PointF> {
        val size = dataPoints.size
        val coords = mutableListOf<PointF>()

        if (size == 1) {
            val pt = dataPoints[0]
            val x = left + chartWidth / 2f
            val yRatio = ((pt.health - minY) / (maxY - minY)).coerceIn(0f, 1f)
            val y = top + chartHeight * (1f - yRatio)
            coords.add(PointF(x, y))
            return coords
        }

        val stepX = chartWidth / (size - 1).toFloat()
        for (i in 0 until size) {
            val pt = dataPoints[i]
            val x = left + i * stepX
            val yRatio = ((pt.health - minY) / (maxY - minY)).coerceIn(0f, 1f)
            val y = top + chartHeight * (1f - yRatio)
            coords.add(PointF(x, y))
        }

        return coords
    }

    /**
     * 绘制贝塞尔曲线与下方半透明翡翠绿渐变填充。
     *
     * @param canvas 画布
     * @param coords 坐标列表
     * @param bottomY 底部基准线Y坐标
     */
    private fun drawChartCurveAndFill(canvas: Canvas, coords: List<PointF>, bottomY: Float) {
        if (coords.isEmpty()) return

        if (coords.size == 1) {
            return
        }

        val linePath = Path()
        val fillPath = Path()

        linePath.moveTo(coords[0].x, coords[0].y)
        fillPath.moveTo(coords[0].x, bottomY)
        fillPath.lineTo(coords[0].x, coords[0].y)

        for (i in 1 until coords.size) {
            val p0 = coords[i - 1]
            val p1 = coords[i]
            val controlPoint1 = PointF(p0.x + (p1.x - p0.x) / 2f, p0.y)
            val controlPoint2 = PointF(p0.x + (p1.x - p0.x) / 2f, p1.y)

            linePath.cubicTo(
                controlPoint1.x, controlPoint1.y,
                controlPoint2.x, controlPoint2.y,
                p1.x, p1.y
            )
            fillPath.cubicTo(
                controlPoint1.x, controlPoint1.y,
                controlPoint2.x, controlPoint2.y,
                p1.x, p1.y
            )
        }

        fillPath.lineTo(coords.last().x, bottomY)
        fillPath.close()

        // 渐变着色器
        fillPaint.shader = LinearGradient(
            0f, coords.minOf { it.y },
            0f, bottomY,
            Color.parseColor("#3810B981"),
            Color.parseColor("#0210B981"),
            Shader.TileMode.CLAMP
        )

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
        val radiusOuter = dpToPx(4.5f)
        val radiusInner = dpToPx(2.2f)
        val radiusHalo = dpToPx(8.5f)

        for (i in coords.indices) {
            val p = coords[i]
            val isLatest = (i == coords.size - 1)

            // 最新点展示呼吸光晕
            if (isLatest) {
                canvas.drawCircle(p.x, p.y, radiusHalo, pointHaloPaint)
            }

            canvas.drawCircle(p.x, p.y, radiusOuter, pointOuterPaint)
            canvas.drawCircle(p.x, p.y, radiusInner, pointInnerPaint)

            // 绘制 X 轴时间标签（首点、末点或间隔绘制）
            if (coords.size <= 5 || i == 0 || i == coords.size - 1 || i == coords.size / 2) {
                textPaint.textAlign = Paint.Align.CENTER
                val displayTime = dataPoints[i].displayTime
                canvas.drawText(displayTime, p.x, height - dpToPx(8f), textPaint)
            }
        }
    }

    /**
     * 绘制手指触控交互时的垂直标尺虚线与悬浮 Tooltip 气泡卡片。
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
        val titleText = "【${pt.category}】${String.format("%.2f%%", pt.health)}"
        val timeText = pt.captureTime
        val cycleText = pt.cycleCount?.let { "循环 $it 次" } ?: ""
        val capText = pt.fullCapacity?.let { "充满 ${String.format("%.0f", it)} mAh" } ?: ""
        val extraText = listOf(cycleText, capText).filter { it.isNotBlank() }.joinToString(" | ")

        // 3. 计算气泡尺寸与位置
        val paddingH = dpToPx(10f)
        val paddingV = dpToPx(8f)
        val titleWidth = tooltipTextTitlePaint.measureText(titleText)
        val timeWidth = tooltipTextDescPaint.measureText(timeText)
        val extraWidth = if (extraText.isNotBlank()) tooltipTextDescPaint.measureText(extraText) else 0f

        val boxWidth = max(titleWidth, max(timeWidth, extraWidth)) + paddingH * 2
        val boxHeight = if (extraText.isNotBlank()) dpToPx(62f) else dpToPx(46f)

        var boxLeft = p.x - boxWidth / 2f
        var boxTop = p.y - boxHeight - dpToPx(12f)

        // 边界防溢出纠偏
        if (boxLeft < dpToPx(8f)) boxLeft = dpToPx(8f)
        if (boxLeft + boxWidth > width - dpToPx(8f)) boxLeft = width - dpToPx(8f) - boxWidth
        if (boxTop < dpToPx(4f)) boxTop = p.y + dpToPx(14f)

        val boxRect = RectF(boxLeft, boxTop, boxLeft + boxWidth, boxTop + boxHeight)
        val cornerRadius = dpToPx(8f)

        // 4. 绘制卡片背景与描边
        canvas.drawRoundRect(boxRect, cornerRadius, cornerRadius, tooltipBgPaint)
        canvas.drawRoundRect(boxRect, cornerRadius, cornerRadius, tooltipStrokePaint)

        // 5. 绘制卡片内部文字
        var textY = boxTop + paddingV + spToPx(10f)
        canvas.drawText(titleText, boxLeft + paddingH, textY, tooltipTextTitlePaint)

        textY += spToPx(14f)
        canvas.drawText(timeText, boxLeft + paddingH, textY, tooltipTextDescPaint)

        if (extraText.isNotBlank()) {
            textY += spToPx(14f)
            canvas.drawText(extraText, boxLeft + paddingH, textY, tooltipTextDescPaint)
        }
    }

    /**
     * 处理手势触控事件，支持手指滑动探查每个快照点的详细健康度参数。
     *
     * @param event 触摸事件
     * @return 始终返回 true 消费手势
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (dataPoints.isEmpty()) return super.onTouchEvent(event)

        val paddingLeft = dpToPx(38f)
        val paddingRight = dpToPx(16f)
        val chartWidth = width - paddingLeft - paddingRight

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                isTouching = true
                parent?.requestDisallowInterceptTouchEvent(true)

                val touchX = event.x
                val coords = calculateCoordinates(paddingLeft, dpToPx(20f), chartWidth, height - dpToPx(48f))

                // 寻找 X 轴距离最近的数据点
                var closestIndex = 0
                var minDistance = Float.MAX_VALUE
                for (i in coords.indices) {
                    val dist = abs(coords[i].x - touchX)
                    if (dist < minDistance) {
                        minDistance = dist
                        closestIndex = i
                    }
                }

                if (selectedIndex != closestIndex) {
                    selectedIndex = closestIndex
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
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
        }
        return super.onTouchEvent(event)
    }

    private fun dpToPx(dp: Float): Float =
        android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)

    private fun spToPx(sp: Float): Float =
        android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
}
