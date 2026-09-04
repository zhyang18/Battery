package com.battery.analysis.ui.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.drawable.toBitmap
import com.battery.analysis.model.PowerDischargePoint
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * 电池放电“使用过程”电量趋势折线图自定义 View。
 * 严格按照设计图绘制电量百分比衰减贝塞尔折线、渐变阴影填充、多时间轴刻度网格及活跃应用图标堆叠打点。
 * 支持触控滑动探查垂直标尺与悬浮气泡卡片。
 *
 * @param context Android 上下文环境
 * @param attrs XML 属性集合
 * @param defStyleAttr 默认样式属性
 */
class PowerUsageChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dataPoints = mutableListOf<PowerDischargePoint>()
    private val cachedPointList = mutableListOf<PointF>()
    private val tooltipRect = RectF()

    // 预计算尺寸标量，避免在 onDraw 中重复计算
    private val dp1 = dpToPx(1f)
    private val dp1_5 = dpToPx(1.5f)
    private val dp2 = dpToPx(2f)
    private val dp2_2 = dpToPx(2.2f)
    private val dp2_5 = dpToPx(2.5f)
    private val dp3 = dpToPx(3f)
    private val dp4 = dpToPx(4f)
    private val dp6 = dpToPx(6f)
    private val dp8 = dpToPx(8f)
    private val dp10 = dpToPx(10f)
    private val dp12 = dpToPx(12f)
    private val dp14 = dpToPx(14f)
    private val dp16 = dpToPx(16f)
    private val dp18 = dpToPx(18f)
    private val dp20 = dpToPx(20f)
    private val dp22 = dpToPx(22f)
    private val dp26 = dpToPx(26f)
    private val dp32 = dpToPx(32f)

    private val sp9_5 = spToPx(9.5f)
    private val sp10_5 = spToPx(10.5f)
    private val sp11 = spToPx(11f)

    private val timeFormatterHourMin = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val timeFormatterMonthDay = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    // 图标打点位图缓存
    private val iconBitmapCache = mutableMapOf<Drawable, Bitmap>()
    private val iconDstRect = RectF()
    private val iconBgRect = RectF()

    // 绘制画笔
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp2_2
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#1E88E5")
    }

    // 图标方块胶囊背景画笔（深色微圆角底衬，使方块堆叠整齐美观）
    private val iconTileBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#332A323D")
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp1
        color = Color.parseColor("#18888888")
        pathEffect = DashPathEffect(floatArrayOf(dp3, dp3), 0f)
    }

    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp9_5
        color = Color.parseColor("#9E9E9E")
    }

    // 底部亮屏状态指示条画笔（绿色表示亮屏）
    private val screenOnBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#34C759")
    }

    // 底部息屏状态指示条画笔（红色表示息屏待机）
    private val screenOffBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FF3B30")
    }

    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp1
        color = Color.parseColor("#2196F3")
        pathEffect = DashPathEffect(floatArrayOf(dp3, dp3), 0f)
    }

    private val tooltipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#EE263238")
    }

    private val tooltipTextTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp11
        color = Color.parseColor("#FFFFFF")
        isFakeBoldText = true
    }

    private val tooltipTextDescPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp10_5
        color = Color.parseColor("#CFD8DC")
    }

    private val linePath = Path()
    private val fillPath = Path()
    private val gridPath = Path()

    // 交互手势状态
    private var selectedIndex: Int = -1
    private var isTouching: Boolean = false
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDraggingHorizontally = false

    private var lastGradientTopY = Float.NaN
    private var lastGradientBottomY = Float.NaN

    /**
     * 设置并刷新放电走势数据。
     *
     * @param points 放电趋势点序列
     */
    fun setData(points: List<PowerDischargePoint>) {
        dataPoints.clear()
        dataPoints.addAll(points)
        selectedIndex = -1
        prepareIconBitmaps()
        invalidate()
    }

    /**
     * 预处理应用图标位图缩略图，避免在绘制循环中触发动态分配。
     */
    private fun prepareIconBitmaps() {
        val targetSize = dp18.toInt().coerceAtLeast(32)
        for (pt in dataPoints) {
            for (icon in pt.activeAppIcons) {
                if (!iconBitmapCache.containsKey(icon)) {
                    val bmp = try {
                        if (icon is BitmapDrawable && icon.bitmap != null) {
                            Bitmap.createScaledBitmap(icon.bitmap, targetSize, targetSize, true)
                        } else {
                            icon.toBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
                        }
                    } catch (_: Exception) {
                        null
                    }
                    if (bmp != null) {
                        iconBitmapCache[icon] = bmp
                    }
                }
            }
        }
    }

    /**
     * 测量并绘制折线图、网格与打点图标。
     *
     * @param canvas 绘制画布
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val paddingLeft = dp20
        val paddingRight = dp8
        val paddingTop = dp14
        val paddingBottom = dp20

        val chartWidth = width - paddingLeft - paddingRight
        val chartHeight = height - paddingTop - paddingBottom

        if (chartWidth <= 0 || chartHeight <= 0) return

        // 1. 绘制网格线与 Y 轴 / X 轴刻度标签（时间文字在对应刻度点自适应对齐，扩大水平图表宽度）
        drawGridAndLabels(canvas, paddingLeft, paddingTop, chartWidth, chartHeight)

        if (dataPoints.isEmpty()) return

        // 2. 计算点阵像素坐标
        val coords = calculateCoordinates(paddingLeft, paddingTop, chartWidth, chartHeight)

        // 3. 绘制放电贝塞尔曲线与渐变阴影填充
        drawCurveAndFill(canvas, coords, paddingTop + chartHeight)

        // 4. 绘制图表底部指示器长条（绿色亮屏，红色息屏待机）
        drawScreenOnOffIndicator(canvas, coords, paddingLeft, chartWidth, paddingTop + chartHeight)

        // 5. 绘制应用图标紧密自底向上垂直堆叠（柱状俄罗斯方块排布在亮屏时间段上方，纯净无白底）
        drawAppIconStacks(canvas, coords, paddingTop + chartHeight, paddingLeft, paddingRight)

        // 6. 绘制手势触控时的标尺线与气泡卡片
        if (isTouching && selectedIndex in coords.indices) {
            drawTooltip(canvas, coords[selectedIndex], dataPoints[selectedIndex], paddingTop, chartHeight)
        }
    }

    /**
     * 绘制 0~100 纵轴虚线网格及 X 轴时间标签刻度。
     *
     * @param canvas 画布
     * @param left 左边界偏移
     * @param top 顶边界偏移
     * @param chartWidth 图表净宽
     * @param chartHeight 图表净高
     */
    private fun drawGridAndLabels(
        canvas: Canvas,
        left: Float,
        top: Float,
        chartWidth: Float,
        chartHeight: Float
    ) {
        // Y 轴 6 等分刻度：0, 20, 40, 60, 80, 100（省略 % 符号以保证高密度屏幕及不同字号下数值完整显示）
        val ySteps = 5
        labelTextPaint.textAlign = Paint.Align.RIGHT
        for (i in 0..ySteps) {
            val ratio = i.toFloat() / ySteps
            val y = top + chartHeight * (1f - ratio)
            val percentVal = (ratio * 100).toInt()

            gridPath.reset()
            gridPath.moveTo(left, y)
            gridPath.lineTo(left + chartWidth, y)
            canvas.drawPath(gridPath, gridPaint)

            canvas.drawText("$percentVal", left - dp3, y + dp3, labelTextPaint)
        }

        // X 轴时间刻度：从放电起始绝对时刻到当前绝对时刻
        val startTs = dataPoints.firstOrNull()?.timestamp ?: (System.currentTimeMillis() - 12 * 3600000L)
        val endTs = dataPoints.lastOrNull()?.timestamp ?: System.currentTimeMillis()
        val totalSpan = max(endTs - startTs, 60000L)

        val xSteps = 4 // 5 个时间刻度点：0%, 25%, 50%, 75%, 100%
        val labelY = top + chartHeight + dp20

        for (i in 0..xSteps) {
            val ratio = i.toFloat() / xSteps
            val x = left + chartWidth * ratio

            // 绘制纵向微弱辅助网格虚线
            gridPath.reset()
            gridPath.moveTo(x, top)
            gridPath.lineTo(x, top + chartHeight)
            canvas.drawPath(gridPath, gridPaint)

            val pointTs = startTs + (totalSpan * ratio).toLong()
            val labelStr = formatAxisTime(pointTs)

            when (i) {
                0 -> {
                    labelTextPaint.textAlign = Paint.Align.LEFT
                    canvas.drawText(labelStr, x, labelY, labelTextPaint)
                }
                xSteps -> {
                    labelTextPaint.textAlign = Paint.Align.RIGHT
                    canvas.drawText(labelStr, x, labelY, labelTextPaint)
                }
                else -> {
                    labelTextPaint.textAlign = Paint.Align.CENTER
                    canvas.drawText(labelStr, x, labelY, labelTextPaint)
                }
            }
        }
    }

    /**
     * 绘制图表底部横向指示条（绿色表示亮屏区段，红色表示息屏区段）。
     *
     * @param canvas 绘制画布
     * @param coords 各采样点的画布坐标列表
     * @param chartLeft 图表左边界 X 坐标
     * @param chartWidth 图表净宽
     * @param gridBottomY 图表主网格底线 Y 坐标
     */
    private fun drawScreenOnOffIndicator(
        canvas: Canvas,
        coords: List<PointF>,
        chartLeft: Float,
        chartWidth: Float,
        gridBottomY: Float
    ) {
        if (coords.isEmpty()) return

        val barTop = gridBottomY + dp3
        val barBottom = barTop + dp4
        val cornerRadius = dp1

        val n = coords.size
        for (i in 0 until n) {
            val pt = dataPoints[i]
            val isScreenOn = pt.isScreenOn
            val paint = if (isScreenOn) screenOnBarPaint else screenOffBarPaint

            val segLeft = if (i == 0) {
                chartLeft
            } else {
                (coords[i - 1].x + coords[i].x) / 2f
            }

            val segRight = if (i == n - 1) {
                chartLeft + chartWidth
            } else {
                (coords[i].x + coords[i + 1].x) / 2f
            }

            if (segRight > segLeft) {
                canvas.drawRoundRect(segLeft, barTop, segRight, barBottom, cornerRadius, cornerRadius, paint)
            }
        }
    }

    /**
     * 将采样绝对时间戳格式化为 X 轴时间刻度标签（纯净数字时间 HH:mm，不包含中文前缀）。
     *
     * @param timestamp 目标时间戳（毫秒）
     * @return 格式化后的时间标签字符串（例如 "22:41"、"12:25"）
     */
    private fun formatAxisTime(timestamp: Long): String {
        return timeFormatterHourMin.format(Date(timestamp))
    }

    /**
     * 将业务数据点转换为屏幕画布坐标点序列（严格按照放电起始时刻至当前时刻的真实时间比例全宽平铺）。
     *
     * @param left 左内边距
     * @param top 上内边距
     * @param chartWidth 图表宽度
     * @param chartHeight 图表高度
     * @return 屏幕像素坐标列表 [List<PointF>]
     */
    private fun calculateCoordinates(
        left: Float,
        top: Float,
        chartWidth: Float,
        chartHeight: Float
    ): List<PointF> {
        val size = dataPoints.size
        while (cachedPointList.size < size) {
            cachedPointList.add(PointF())
        }

        if (size == 0) return emptyList()

        val startTs = dataPoints.first().timestamp
        val endTs = dataPoints.last().timestamp
        val totalSpan = max(endTs - startTs, 60000L)

        for (i in 0 until size) {
            val pt = dataPoints[i]
            val xRatio = if (totalSpan > 0) {
                ((pt.timestamp - startTs).toFloat() / totalSpan).coerceIn(0f, 1f)
            } else {
                if (size > 1) i.toFloat() / (size - 1) else 0.5f
            }
            val x = left + chartWidth * xRatio

            val yRatio = (pt.batteryLevel / 100f).coerceIn(0f, 1f)
            val y = top + chartHeight * (1f - yRatio)

            cachedPointList[i].set(x, y)
        }

        return cachedPointList.subList(0, size)
    }

    /**
     * 绘制平滑放电贝塞尔折线及半透明蓝色渐变背景。
     *
     * @param canvas 画布
     * @param coords 坐标列表
     * @param bottomY 底部基准线
     */
    private fun drawCurveAndFill(canvas: Canvas, coords: List<PointF>, bottomY: Float) {
        if (coords.size <= 1) return

        linePath.reset()
        fillPath.reset()

        val p0 = coords[0]
        linePath.moveTo(p0.x, p0.y)
        fillPath.moveTo(p0.x, bottomY)
        fillPath.lineTo(p0.x, p0.y)

        var minY = p0.y
        for (i in 1 until coords.size) {
            val prev = coords[i - 1]
            val curr = coords[i]
            if (curr.y < minY) minY = curr.y

            val midX = prev.x + (curr.x - prev.x) / 2f
            linePath.cubicTo(midX, prev.y, midX, curr.y, curr.x, curr.y)
            fillPath.cubicTo(midX, prev.y, midX, curr.y, curr.x, curr.y)
        }

        fillPath.lineTo(coords.last().x, bottomY)
        fillPath.close()

        if (minY != lastGradientTopY || bottomY != lastGradientBottomY) {
            lastGradientTopY = minY
            lastGradientBottomY = bottomY
            fillPaint.shader = LinearGradient(
                0f, minY,
                0f, bottomY,
                Color.parseColor("#381E88E5"),
                Color.parseColor("#041E88E5"),
                Shader.TileMode.CLAMP
            )
        }

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)
    }

    /**
     * 在对应亮屏时段节点处垂直堆叠绘制活跃应用图标（自底部向上垂直累叠排布在亮屏指示条上方，微圆角方块俄罗斯方块式紧凑堆叠）。
     *
     * @param canvas 绘制画布
     * @param coords 各数据采样点屏幕坐标列表
     * @param gridBottomY 图表主网格底线 Y 坐标
     * @param paddingLeft 图表左内边距
     * @param paddingRight 图表右内边距
     */
    private fun drawAppIconStacks(
        canvas: Canvas,
        coords: List<PointF>,
        gridBottomY: Float,
        paddingLeft: Float,
        paddingRight: Float
    ) {
        val iconSize = dp16
        val iconMargin = dp1

        // 图标自底向上垂直堆叠的起始基准线（紧贴绿/红状态指示条上方）
        val baseBottomY = gridBottomY + dp2

        for (i in coords.indices) {
            val pt = dataPoints[i]
            if (pt.activeAppIcons.isNotEmpty()) {
                val p = coords[i]
                // 限制图标中心 X 轴坐标，避免最左侧或最右侧图标溢出边距
                val minX = paddingLeft + iconSize / 2f
                val maxX = width - paddingRight - iconSize / 2f
                val centerX = p.x.coerceIn(minX, maxX)
                var stackBottomY: Float = baseBottomY

                for (icon in pt.activeAppIcons) {
                    val bmp = iconBitmapCache[icon]
                    val iconLeft = centerX - iconSize / 2f
                    val iconTop = stackBottomY - iconSize

                    // 向上垂直堆叠边界保护，防止超出视图顶部
                    if (iconTop < dp6) break

                    // 1. 绘制规整小方块底衬（与设计图一致的微圆角暗色积木卡片）
                    iconBgRect.set(iconLeft, iconTop, iconLeft + iconSize, stackBottomY)
                    canvas.drawRoundRect(iconBgRect, dp2_5, dp2_5, iconTileBgPaint)

                    // 2. 在方块内部居中绘制应用图标
                    iconDstRect.set(
                        iconLeft + dp1_5,
                        iconTop + dp1_5,
                        iconLeft + iconSize - dp1_5,
                        stackBottomY - dp1_5
                    )

                    if (bmp != null && !bmp.isRecycled) {
                        canvas.drawBitmap(bmp, null, iconDstRect, null)
                    }

                    stackBottomY = stackBottomY - (iconSize + iconMargin)
                }
            }
        }
    }

    /**
     * 绘制交互触控垂直虚线标尺及悬浮信息 Tooltip 气泡（同步展示该时间节点处于活跃状态的前台应用）。
     *
     * @param canvas 画布
     * @param p 选中的屏幕坐标点
     * @param pt 选中的数据实体
     * @param top 顶边界
     * @param chartHeight 净高
     */
    private fun drawTooltip(
        canvas: Canvas,
        p: PointF,
        pt: PowerDischargePoint,
        top: Float,
        chartHeight: Float
    ) {
        // 绘制标尺线
        canvas.drawLine(p.x, top, p.x, top + chartHeight, cursorPaint)

        // 提示文本
        val timeLabel = formatAxisTime(pt.timestamp)
        val titleText = "电量: ${pt.batteryLevel}% ($timeLabel)"
        val timeText = "放电耗时: ${pt.getFormattedTimeLabel()} | ${pt.temperature}℃"
        val appsText = if (pt.activeAppNames.isNotEmpty()) {
            "前台应用: " + pt.activeAppNames.take(3).joinToString(", ")
        } else null

        val paddingH = dp8
        val paddingV = dp6
        val titleW = tooltipTextTitlePaint.measureText(titleText)
        val timeW = tooltipTextDescPaint.measureText(timeText)
        val appsW = if (appsText != null) tooltipTextDescPaint.measureText(appsText) else 0f
        val boxWidth = max(max(titleW, timeW), appsW) + paddingH * 2
        val boxHeight = if (appsText != null) dp26 * 1.75f else dp26 * 1.3f

        var boxLeft = p.x - boxWidth / 2f
        var boxTop = p.y - boxHeight - dp8
        if (boxLeft < dp6) boxLeft = dp6
        if (boxLeft + boxWidth > width - dp6) boxLeft = width - dp6 - boxWidth
        if (boxTop < dp4) boxTop = p.y + dp10

        tooltipRect.set(boxLeft, boxTop, boxLeft + boxWidth, boxTop + boxHeight)
        canvas.drawRoundRect(tooltipRect, dp6, dp6, tooltipBgPaint)

        var textY = boxTop + paddingV + sp10_5
        canvas.drawText(titleText, boxLeft + paddingH, textY, tooltipTextTitlePaint)
        textY += sp11 * 1.2f
        canvas.drawText(timeText, boxLeft + paddingH, textY, tooltipTextDescPaint)
        if (appsText != null) {
            textY += sp11 * 1.2f
            canvas.drawText(appsText, boxLeft + paddingH, textY, tooltipTextDescPaint)
        }
    }

    /**
     * 响应用户手势触控，仅在横向滑动探查图表数据时消费手势，垂直滚动时完全放行给外层滑动容器。
     *
     * @param event 手势事件
     * @return 是否消费触摸事件
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (dataPoints.isEmpty()) return super.onTouchEvent(event)

        val paddingLeft = dp32
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
                    val maxHours = 36f
                    val targetHours = ((touchX - paddingLeft) / chartWidth * maxHours).coerceIn(0f, maxHours)

                    var closestIdx = 0
                    var minDiff = Float.MAX_VALUE
                    for (i in dataPoints.indices) {
                        val diff = abs(dataPoints[i].elapsedHours - targetHours)
                        if (diff < minDiff) {
                            minDiff = diff
                            closestIdx = i
                        }
                    }

                    if (selectedIndex != closestIdx) {
                        selectedIndex = closestIdx
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

    /**
     * 将 dp 转换为像素 px。
     *
     * @param dp 密度独立像素值
     * @return 对应的屏幕像素值
     */
    private fun dpToPx(dp: Float): Float =
        android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)

    /**
     * 将 sp 转换为像素 px。
     *
     * @param sp 缩放独立像素值
     * @return 对应的屏幕像素值
     */
    private fun spToPx(sp: Float): Float =
        android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
}
