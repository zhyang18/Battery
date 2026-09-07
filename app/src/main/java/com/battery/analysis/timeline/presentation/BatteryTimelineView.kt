package com.battery.analysis.timeline.presentation

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.view.GestureDetectorCompat
import com.battery.analysis.timeline.domain.AppTimelineEvent
import com.battery.analysis.timeline.domain.BatterySample
import com.battery.analysis.timeline.domain.TimelineEventMerger
import com.battery.analysis.timeline.util.ChartDownsampler
import com.battery.analysis.timeline.util.DrawableBitmapCache
import com.battery.analysis.timeline.util.TimelineLayoutCalculator
import com.battery.analysis.timeline.util.TimelineScaleCalculator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 功耗时间轴主可视化交互式自定义 View 组件。
 * 融合“从下往上纵向堆叠 App 活动事件 + 固定底图时间轴线/屏幕状态条 + 动态时间刻度网格 + 四维电池指标多选/反选多曲线叠加”可视化引擎。
 * 深度支持横向手势平移、双指多级缩放、图标点击探查与长按垂直游标测距。
 *
 * @param context Android 上下文环境
 * @param attrs XML 属性集合
 * @param defStyleAttr 默认样式属性
 */
class BatteryTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /**
     * App 事件点击回调接口。
     */
    fun interface OnAppEventListener {
        /**
         * 当用户点击时间轴上的特定应用图标时触发。
         *
         * @param event 被点击的 App 时间轴事件 [AppTimelineEvent]
         */
        fun onAppClick(event: AppTimelineEvent)
    }

    /**
     * 长按游标探查状态监听器接口。
     */
    interface OnCursorInspectListener {
        /**
         * 当游标位置更新时回调。
         *
         * @param timestamp 游标指向的物理时间戳（毫秒）
         * @param sample 临近的物理采样点（若有）
         * @param activeApp 临近活跃的应用事件（若有）
         */
        fun onCursorMove(timestamp: Long, sample: BatterySample?, activeApp: AppTimelineEvent?)

        /**
         * 当游标探查结束释放时回调。
         */
        fun onCursorDismiss()
    }

    // 内部状态维护
    private var timelineState = BatteryTimelineState()
    private var onAppEventListener: OnAppEventListener? = null
    private var onCursorInspectListener: OnCursorInspectListener? = null

    // 预计算 DP 标量
    private val dp0_5 = dpToPx(0.5f)
    private val dp1 = dpToPx(1f)
    private val dp1_5 = dpToPx(1.5f)
    private val dp2 = dpToPx(2f)
    private val dp2_5 = dpToPx(2.5f)
    private val dp3 = dpToPx(3f)
    private val dp3_5 = dpToPx(3.5f)
    private val dp4 = dpToPx(4f)
    private val dp6 = dpToPx(6f)
    private val dp8 = dpToPx(8f)
    private val dp10 = dpToPx(10f)
    private val dp12 = dpToPx(12f)
    private val dp14 = dpToPx(14f)
    private val dp15 = dpToPx(15f)
    private val dp16 = dpToPx(16f)
    private val dp18 = dpToPx(18f)
    private val dp20 = dpToPx(20f)
    private val dp22 = dpToPx(22f)
    private val dp24 = dpToPx(24f)
    private val dp32 = dpToPx(32f)
    private val dp35 = dpToPx(35f)

    private val sp8_5 = spToPx(8.5f)
    private val sp9_5 = spToPx(9.5f)
    private val sp10_5 = spToPx(10.5f)

    // 画笔体系
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp1_5
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
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

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp9_5
        color = Color.parseColor("#9E9E9E")
    }

    private val yAxisTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp9_5
        color = Color.parseColor("#8E9AA8")
        textAlign = Paint.Align.RIGHT
    }

    private val metricDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val metricLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp8_5
        textAlign = Paint.Align.CENTER
    }

    private val screenOnBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#34C759") // 亮屏绿
    }

    private val screenOffBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#3A3A3C") // 息屏深灰
    }

    private val iconBadgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#2B3542") // 徽章圆角深蓝灰底色
    }

    private val iconBadgeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp1
        color = Color.parseColor("#3A4758") // 徽章微暗描边
    }

    private val iconBadgeSelectedStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp1_5
        color = Color.parseColor("#2196F3") // 选中项高亮蓝边
    }

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        isDither = true
    }

    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp1_5
        color = Color.parseColor("#2196F3")
        pathEffect = DashPathEffect(floatArrayOf(dp3, dp3), 0f)
    }

    private val tooltipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#EE1F2937")
    }

    private val tooltipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp10_5
        color = Color.parseColor("#F3F4F6")
    }

    // 绘制复用 Path 与 Rect
    private val curvePath = Path()
    private val fillPath = Path()
    private val tempRectF = RectF()
    private val tempDstRectF = RectF()
    private val tempSrcRect = Rect()
    private val tooltipRect = RectF()
    private val cachedSlotItems = mutableListOf<TimelineLayoutCalculator.LaidOutAppSlotItem>()

    // 手势与交互状态
    private var isCursorActive = false
    private var cursorX = 0f
    private var isDragging = false
    private var lastTouchX = 0f

    private val timeFormatterTooltip = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // 双指缩放手势检测器
    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            val focusX = detector.focusX
            applyZoom(scaleFactor, focusX)
            return true
        }
    })

    // 单指手势检测器（点击与长按）
    private val gestureDetector = GestureDetectorCompat(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            return handleSingleTap(e.x, e.y)
        }

        override fun onLongPress(e: MotionEvent) {
            isCursorActive = true
            cursorX = e.x.coerceIn(0f, width.toFloat())
            parent?.requestDisallowInterceptTouchEvent(true)
            invalidate()
            notifyCursorMove(cursorX)
        }
    })

    init {
        setWillNotDraw(false)
    }

    /**
     * 设置时间轴最新状态并触发重绘。
     *
     * @param state 最新的时间轴状态 [BatteryTimelineState]
     */
    fun setState(state: BatteryTimelineState) {
        val now = System.currentTimeMillis()
        val start = if (state.startTimestamp > 0L) state.startTimestamp else (now - 1800_000L)
        val end = if (state.endTimestamp > start) state.endTimestamp else (start + 1800_000L)

        val vStart = if (state.visibleStartTimestamp in 1 until end) state.visibleStartTimestamp else start
        val vEnd = if (state.visibleEndTimestamp > vStart) state.visibleEndTimestamp else end

        this.timelineState = state.copy(
            startTimestamp = start,
            endTimestamp = end,
            visibleStartTimestamp = vStart,
            visibleEndTimestamp = vEnd
        )
        recalculateLayout()
        invalidate()
    }

    /**
     * 设置当前多选选中的指标集合。
     *
     * @param metrics 目标指标集合 [Set<TimelineMetric>]
     */
    fun setSelectedMetrics(metrics: Set<TimelineMetric>) {
        if (timelineState.selectedMetrics != metrics) {
            timelineState = timelineState.copy(selectedMetrics = metrics)
            if (metrics.contains(TimelineMetric.APP) && cachedSlotItems.isEmpty()) {
                recalculateLayout()
            }
            invalidate()
        }
    }

    /**
     * 切换指定指标项的选中/反选状态。
     *
     * @param metric 目标指标 [TimelineMetric]
     */
    fun toggleMetric(metric: TimelineMetric) {
        val updated = timelineState.selectedMetrics.toMutableSet()
        if (updated.contains(metric)) {
            updated.remove(metric)
        } else {
            updated.add(metric)
        }
        setSelectedMetrics(updated)
    }

    /**
     * 兼容设置单选主指标类型。
     *
     * @param metric 目标指标 [TimelineMetric]
     */
    fun setMetric(metric: TimelineMetric) {
        setSelectedMetrics(setOf(metric))
    }

    /**
     * 设置应用事件点击监听器。
     *
     * @param listener 监听器实例
     */
    fun setOnAppEventListener(listener: OnAppEventListener?) {
        this.onAppEventListener = listener
    }

    /**
     * 设置长按游标探查监听器。
     *
     * @param listener 监听器实例
     */
    fun setOnCursorInspectListener(listener: OnCursorInspectListener?) {
        this.onCursorInspectListener = listener
    }

    /**
     * 重新计算 App 图标的时间槽平铺与多行纵向堆叠排布。
     */
    private fun recalculateLayout() {
        cachedSlotItems.clear()
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val contentLeft = dp32
        val contentRight = w - dp6
        val contentWidth = max(0f, contentRight - contentLeft)

        val visibleStart = timelineState.visibleStartTimestamp
        val visibleEnd = max(timelineState.visibleEndTimestamp, visibleStart + 60_000L)

        val timeTickTop = h - dp18
        val screenBarBottom = timeTickTop - dp2
        val screenBarTop = screenBarBottom - dp3_5
        val baseBottomY = screenBarTop - dp3

        val laidOut = TimelineLayoutCalculator.calculateSlotItems(
            events = timelineState.appEvents,
            visibleStartTs = visibleStart,
            visibleEndTs = visibleEnd,
            canvasWidth = contentWidth,
            baseBottomY = baseBottomY,
            slotSizePx = dp15,
            slotGapPx = dp2_5,
            rowGapPx = dp2_5,
            maxRows = 4,
            leftMarginPx = contentLeft
        )
        cachedSlotItems.addAll(laidOut)
    }

    /**
     * 视图尺寸发生改变时重新计算排布。
     *
     * @param w 新宽度
     * @param h 新高度
     * @param oldw 旧宽度
     * @param oldh 旧高度
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recalculateLayout()
    }

    /**
     * 核心 Canvas 绘制流程：
     * 1. 左侧绘制功耗 Y 轴数值刻度（0W, 10W, 20W, 30W...）及横向基准虚线；
     * 2. 多选曲线自适应锚点绘制（功耗面积图、电量阶梯折线及百分比点标、温度阶梯折线及数值点标、电压阶梯折线及数值点标）；
     * 3. App 活动分槽平铺图标（从下往上纵向堆叠）；
     * 4. 底部时间轴屏幕状态实线条与无秒级时间刻度文字。
     *
     * @param canvas 目标绘制画布 [Canvas]
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val now = System.currentTimeMillis()
        var visibleStart = timelineState.visibleStartTimestamp
        var visibleEnd = timelineState.visibleEndTimestamp

        if (visibleStart <= 0L) visibleStart = now - 1800_000L
        if (visibleEnd <= visibleStart) visibleEnd = visibleStart + 1800_000L

        val contentLeft = dp32
        val contentRight = w - dp6
        val contentWidth = max(0f, contentRight - contentLeft)

        // 底部向上严格锚定布局：
        val timeTextY = h - dp4
        val timeTickTop = h - dp18
        val screenBarBottom = timeTickTop - dp2
        val screenBarTop = screenBarBottom - dp3_5
        val mainChartHeight = screenBarTop - dp35
        val topPadding = dp8
        val bottomPadding = dp6
        val availableH = max(1f, mainChartHeight - topPadding - bottomPadding)

        // 确保 App 图标排布已就绪
        if (cachedSlotItems.isEmpty() && timelineState.appEvents.isNotEmpty()) {
            recalculateLayout()
        }

        // 计算当前可视区间内的最大功耗刻度值（自动归整为 10W, 20W, 30W 等整十阶梯）
        val rawSamples = timelineState.batterySamples.filter { it.timestamp in visibleStart..visibleEnd }
        val maxRawPowerW = rawSamples.maxOfOrNull { abs(it.powerMw) / 1000.0 } ?: 15.0
        val maxScaleW = when {
            maxRawPowerW <= 5.0 -> 6.0
            maxRawPowerW <= 10.0 -> 10.0
            maxRawPowerW <= 20.0 -> 20.0
            maxRawPowerW <= 30.0 -> 30.0
            maxRawPowerW <= 40.0 -> 40.0
            maxRawPowerW <= 60.0 -> 60.0
            else -> kotlin.math.ceil(maxRawPowerW / 10.0) * 10.0
        }

        // 1. 绘制左侧 Y 轴功耗数值刻度与横向基准网格虚线
        drawYAxisAndGrid(canvas, contentLeft, contentRight, topPadding, availableH, maxScaleW)

        // 2. 绘制垂直时间网格虚线与底部时间刻度文字
        drawTimeGridAndTicks(canvas, contentLeft, contentWidth, mainChartHeight, screenBarBottom, timeTextY, visibleStart, visibleEnd)

        // 3. 多选/反选模式：根据选中的指标集合依次绘制各曲线（支持自动计算画线锚点与阶梯展示）
        val metrics = timelineState.selectedMetrics

        if (metrics.contains(TimelineMetric.POWER)) {
            drawPowerCurve(canvas, contentLeft, contentWidth, topPadding, availableH, mainChartHeight, visibleStart, visibleEnd, maxScaleW, rawSamples)
        }
        if (metrics.contains(TimelineMetric.BATTERY)) {
            drawBatteryCurve(canvas, contentLeft, contentWidth, topPadding, availableH, visibleStart, visibleEnd, rawSamples)
        }
        if (metrics.contains(TimelineMetric.TEMPERATURE)) {
            drawTemperatureCurve(canvas, contentLeft, contentWidth, topPadding, availableH, visibleStart, visibleEnd, rawSamples)
        }
        if (metrics.contains(TimelineMetric.VOLTAGE)) {
            drawVoltageCurve(canvas, contentLeft, contentWidth, topPadding, availableH, visibleStart, visibleEnd, rawSamples)
        }

        // 4. 绘制 App 活动分槽平铺徽章（从下往上纵向堆叠，Row 0 紧贴绿色状态条上方）
        if (metrics.contains(TimelineMetric.APP)) {
            drawAppEventsLayer(canvas)
        }

        // 5. 绘制横贯全宽的固定底图时间轴屏幕状态实线条（亮屏绿 / 息屏暗灰）
        drawScreenStateBar(canvas, contentLeft, contentRight, screenBarTop, screenBarBottom, visibleStart, visibleEnd)

        // 6. 若长按处于活跃状态，绘制十字游标与悬浮气泡
        if (isCursorActive) {
            drawCursorAndTooltip(canvas, contentLeft, contentRight, contentWidth, screenBarTop, visibleStart, visibleEnd)
        }
    }

    /**
     * 绘制图表左侧功耗 Y 轴数值刻度（如 30W, 20W, 10W, 0W）与横向网格虚线。
     */
    private fun drawYAxisAndGrid(
        canvas: Canvas,
        contentLeft: Float,
        contentRight: Float,
        topPadding: Float,
        availableH: Float,
        maxScaleW: Double
    ) {
        val steps = listOf(1.0, 0.6667, 0.3333, 0.0)
        for (stepRatio in steps) {
            val v = maxScaleW * stepRatio
            val y = topPadding + (1f - stepRatio.toFloat()) * availableH
            val label = "${v.toInt()} W"
            canvas.drawText(label, contentLeft - dp4, y + sp9_5 * 0.35f, yAxisTextPaint)
            canvas.drawLine(contentLeft, y, contentRight, y, gridPaint)
        }
    }

    /**
     * 绘制时间轴网格虚线与底部时间刻度文字。
     */
    private fun drawTimeGridAndTicks(
        canvas: Canvas,
        contentLeft: Float,
        contentWidth: Float,
        mainHeight: Float,
        tickTop: Float,
        textY: Float,
        visibleStart: Long,
        visibleEnd: Long
    ) {
        val ticks = TimelineScaleCalculator.calculateTicks(visibleStart, visibleEnd)
        for (tick in ticks) {
            val x = contentLeft + tick.xRatio * contentWidth
            // 垂直虚线网格
            canvas.drawLine(x, dp4, x, mainHeight, gridPaint)
            // 刻度小短线
            canvas.drawLine(x, tickTop, x, tickTop + dp3, gridPaint)
            // 时间文本绘制：起点左对齐，终点右对齐，中间居中
            val textWidth = textPaint.measureText(tick.label)
            val textX = when {
                tick.xRatio <= 0.01f -> contentLeft
                tick.xRatio >= 0.99f -> contentLeft + contentWidth - textWidth
                else -> (x - textWidth / 2f).coerceIn(contentLeft, contentLeft + contentWidth - textWidth)
            }
            canvas.drawText(tick.label, textX, textY, textPaint)
        }
    }

    /**
     * 绘制应用时间轴按时间槽平铺的 App 图标（从下往上纵向堆叠，Row 0 紧贴绿色状态条上方）。
     *
     * @param canvas 绘制画布 [Canvas]
     */
    private fun drawAppEventsLayer(canvas: Canvas) {
        val cornerRadius = dp3
        val iconRenderSize = dp15.toInt().coerceAtLeast(1)

        for (item in cachedSlotItems) {
            val event = item.event
            tempRectF.set(item.left, item.top, item.right, item.bottom)

            // 1. 若当前应用被选中，绘制高亮聚焦外框
            if (timelineState.selectedApp?.packageName == event.packageName) {
                canvas.drawRoundRect(tempRectF, cornerRadius, cornerRadius, iconBadgeSelectedStrokePaint)
            }

            // 2. 直接绘制 App 图标
            val bmp = DrawableBitmapCache.getOrConvertBitmap(event.packageName, event.icon, iconRenderSize)
            if (bmp != null && !bmp.isRecycled) {
                tempSrcRect.set(0, 0, bmp.width, bmp.height)
                canvas.drawBitmap(bmp, tempSrcRect, tempRectF, bitmapPaint)
            }
        }
    }

    /**
     * 绘制功耗波动折线与渐变阴影填充（淡蓝色，始终自左侧 contentLeft 开始并横跨全宽）。
     */
    private fun drawPowerCurve(
        canvas: Canvas,
        contentLeft: Float,
        contentWidth: Float,
        topPadding: Float,
        availableH: Float,
        mainHeight: Float,
        visibleStart: Long,
        visibleEnd: Long,
        maxScaleW: Double,
        rawSamples: List<BatterySample>
    ) {
        val downsampled = ChartDownsampler.downsample(rawSamples, targetMaxPoints = 1200)
        if (downsampled.isEmpty()) return

        val contentRight = contentLeft + contentWidth
        val strokeColor = Color.parseColor("#90CAF9")
        val fillColorTop = Color.parseColor("#4090CAF9")

        linePaint.color = strokeColor
        fillPaint.shader = LinearGradient(
            0f, topPadding, 0f, mainHeight,
            fillColorTop, Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )

        curvePath.reset()
        fillPath.reset()

        val firstSample = downsampled.first()
        val firstPW = (abs(firstSample.powerMw) / 1000.0).toFloat().coerceIn(0f, maxScaleW.toFloat())
        val firstY = topPadding + (1f - (firstPW / maxScaleW).toFloat()) * availableH

        // 曲线与阴影始终自最左侧起点 (contentLeft, firstY) 开始
        curvePath.moveTo(contentLeft, firstY)
        fillPath.moveTo(contentLeft, mainHeight)
        fillPath.lineTo(contentLeft, firstY)

        var lastX = contentLeft
        var lastY = firstY

        for (s in downsampled) {
            val x = (contentLeft + TimelineScaleCalculator.timeToX(s.timestamp, visibleStart, visibleEnd, contentWidth)).coerceIn(contentLeft, contentRight)
            val pW = (abs(s.powerMw) / 1000.0).toFloat().coerceIn(0f, maxScaleW.toFloat())
            val y = topPadding + (1f - (pW / maxScaleW).toFloat()) * availableH

            if (x > lastX) {
                curvePath.lineTo(x, y)
                fillPath.lineTo(x, y)
                lastX = x
                lastY = y
            }
        }

        // 确保功耗曲线与阴影一直延伸至最右侧终点 contentRight
        if (lastX < contentRight) {
            curvePath.lineTo(contentRight, lastY)
            fillPath.lineTo(contentRight, lastY)
            lastX = contentRight
        }

        fillPath.lineTo(lastX, mainHeight)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(curvePath, linePaint)
    }

    /**
     * 绘制电量阶梯曲线、拐点圆点及百分比数值标签（鲜绿色，始终自左侧 contentLeft 起步并贯穿整个时间轴）。
     */
    private fun drawBatteryCurve(
        canvas: Canvas,
        contentLeft: Float,
        contentWidth: Float,
        topPadding: Float,
        availableH: Float,
        visibleStart: Long,
        visibleEnd: Long,
        rawSamples: List<BatterySample>
    ) {
        if (rawSamples.isEmpty()) return
        val downsampled = ChartDownsampler.downsample(rawSamples, targetMaxPoints = 300)
        if (downsampled.isEmpty()) return

        val contentRight = contentLeft + contentWidth
        val color = Color.parseColor("#4CAF50")
        linePaint.color = color
        metricDotPaint.color = color
        metricLabelPaint.color = color

        curvePath.reset()
        val firstSample = downsampled.first()
        val firstY = topPadding + (1f - (firstSample.batteryLevel / 100f).coerceIn(0f, 1f)) * availableH

        // 无论处于何种时间跨度，折线始终从最左侧起点 (contentLeft, firstY) 开始
        curvePath.moveTo(contentLeft, firstY)

        val pointMarkers = mutableListOf<Triple<Float, Float, String>>()
        // 起点数值标签固定在左侧起点 (contentLeft)
        pointMarkers.add(Triple(contentLeft, firstY, "${firstSample.batteryLevel}%"))

        var lastX = contentLeft
        var lastY = firstY
        var lastLevel = firstSample.batteryLevel

        for (s in downsampled) {
            val x = (contentLeft + TimelineScaleCalculator.timeToX(s.timestamp, visibleStart, visibleEnd, contentWidth)).coerceIn(contentLeft, contentRight)
            val y = topPadding + (1f - (s.batteryLevel / 100f).coerceIn(0f, 1f)) * availableH

            if (x > lastX) {
                curvePath.lineTo(x, lastY)
                curvePath.lineTo(x, y)
                if (s.batteryLevel != lastLevel) {
                    pointMarkers.add(Triple(x, y, "${s.batteryLevel}%"))
                }
                lastX = x
                lastY = y
                lastLevel = s.batteryLevel
            }
        }

        // 确保折线始终横贯延伸至最右侧终点 (contentRight, lastY)
        if (lastX < contentRight) {
            curvePath.lineTo(contentRight, lastY)
        }

        canvas.drawPath(curvePath, linePaint)

        // 绘制关键节点圆点与电量百分比文字标签
        for ((x, y, text) in pointMarkers) {
            canvas.drawCircle(x, y, dp2_5, metricDotPaint)
            val textWidth = metricLabelPaint.measureText(text)
            val textX = when {
                x <= contentLeft + dp4 -> contentLeft
                x >= contentRight - dp4 -> contentRight - textWidth
                else -> (x - textWidth / 2f).coerceIn(contentLeft, contentRight - textWidth)
            }
            canvas.drawText(text, textX, y - dp4, metricLabelPaint)
        }
    }

    /**
     * 绘制温度阶梯折线、拐点圆点及摄氏度数值标签（珊瑚橙色，始终自左侧 contentLeft 起步并贯穿整个时间轴）。
     */
    private fun drawTemperatureCurve(
        canvas: Canvas,
        contentLeft: Float,
        contentWidth: Float,
        topPadding: Float,
        availableH: Float,
        visibleStart: Long,
        visibleEnd: Long,
        rawSamples: List<BatterySample>
    ) {
        if (rawSamples.isEmpty()) return
        val downsampled = ChartDownsampler.downsample(rawSamples, targetMaxPoints = 300)
        if (downsampled.isEmpty()) return

        val contentRight = contentLeft + contentWidth
        val color = Color.parseColor("#FF7043")
        linePaint.color = color
        metricDotPaint.color = color
        metricLabelPaint.color = color

        curvePath.reset()
        val firstSample = downsampled.first()
        val firstNorm = ((firstSample.temperatureC - 20.0) / 30.0).coerceIn(0.05, 0.95).toFloat()
        val firstY = topPadding + (1f - firstNorm) * availableH

        // 折线始终从最左侧起点 (contentLeft, firstY) 开始
        curvePath.moveTo(contentLeft, firstY)

        val pointMarkers = mutableListOf<Triple<Float, Float, String>>()
        // 起点温度标签固定在左侧起点 (contentLeft)
        pointMarkers.add(Triple(contentLeft, firstY, String.format(Locale.getDefault(), "%.1f℃", firstSample.temperatureC)))

        var lastX = contentLeft
        var lastY = firstY
        var lastTempInt = (firstSample.temperatureC * 2).toInt()

        for (s in downsampled) {
            val x = (contentLeft + TimelineScaleCalculator.timeToX(s.timestamp, visibleStart, visibleEnd, contentWidth)).coerceIn(contentLeft, contentRight)
            val norm = ((s.temperatureC - 20.0) / 30.0).coerceIn(0.05, 0.95).toFloat()
            val y = topPadding + (1f - norm) * availableH

            if (x > lastX) {
                curvePath.lineTo(x, lastY)
                curvePath.lineTo(x, y)
                val tempInt = (s.temperatureC * 2).toInt()
                if (tempInt != lastTempInt && pointMarkers.size < 6) {
                    pointMarkers.add(Triple(x, y, String.format(Locale.getDefault(), "%.1f℃", s.temperatureC)))
                }
                lastX = x
                lastY = y
                lastTempInt = tempInt
            }
        }

        // 确保折线始终横贯延伸至最右侧终点 (contentRight, lastY)
        if (lastX < contentRight) {
            curvePath.lineTo(contentRight, lastY)
        }

        canvas.drawPath(curvePath, linePaint)

        // 绘制关键节点圆点与温度文字标签
        for ((x, y, text) in pointMarkers) {
            canvas.drawCircle(x, y, dp2_5, metricDotPaint)
            val textWidth = metricLabelPaint.measureText(text)
            val textX = when {
                x <= contentLeft + dp4 -> contentLeft
                x >= contentRight - dp4 -> contentRight - textWidth
                else -> (x - textWidth / 2f).coerceIn(contentLeft, contentRight - textWidth)
            }
            canvas.drawText(text, textX, y + dp10, metricLabelPaint)
        }
    }

    /**
     * 绘制电压阶梯折线、拐点圆点及伏特数值标签（金黄色，始终自左侧 contentLeft 起步并贯穿整个时间轴）。
     */
    private fun drawVoltageCurve(
        canvas: Canvas,
        contentLeft: Float,
        contentWidth: Float,
        topPadding: Float,
        availableH: Float,
        visibleStart: Long,
        visibleEnd: Long,
        rawSamples: List<BatterySample>
    ) {
        if (rawSamples.isEmpty()) return
        val downsampled = ChartDownsampler.downsample(rawSamples, targetMaxPoints = 300)
        if (downsampled.isEmpty()) return

        val contentRight = contentLeft + contentWidth
        val color = Color.parseColor("#FFCA28")
        linePaint.color = color
        metricDotPaint.color = color
        metricLabelPaint.color = color

        curvePath.reset()
        val firstSample = downsampled.first()
        val firstVoltV = firstSample.voltageMv / 1000f
        val firstNorm = ((firstVoltV - 3.4f) / (4.4f - 3.4f)).coerceIn(0.1f, 0.9f)
        val firstY = topPadding + (1f - firstNorm) * availableH

        // 折线始终从最左侧起点 (contentLeft, firstY) 开始
        curvePath.moveTo(contentLeft, firstY)

        val pointMarkers = mutableListOf<Triple<Float, Float, String>>()
        // 起点电压标签固定在左侧起点 (contentLeft)
        pointMarkers.add(Triple(contentLeft, firstY, String.format(Locale.getDefault(), "%.3f V", firstVoltV)))

        var lastX = contentLeft
        var lastY = firstY
        var lastVolt = firstVoltV

        for (s in downsampled) {
            val x = (contentLeft + TimelineScaleCalculator.timeToX(s.timestamp, visibleStart, visibleEnd, contentWidth)).coerceIn(contentLeft, contentRight)
            val voltV = s.voltageMv / 1000f
            val norm = ((voltV - 3.4f) / (4.4f - 3.4f)).coerceIn(0.1f, 0.9f)
            val y = topPadding + (1f - norm) * availableH

            if (x > lastX) {
                curvePath.lineTo(x, lastY)
                curvePath.lineTo(x, y)
                if (abs(voltV - lastVolt) >= 0.05f && pointMarkers.size < 6) {
                    pointMarkers.add(Triple(x, y, String.format(Locale.getDefault(), "%.3f V", voltV)))
                }
                lastX = x
                lastY = y
                lastVolt = voltV
            }
        }

        // 确保折线始终横贯延伸至最右侧终点 (contentRight, lastY)
        if (lastX < contentRight) {
            curvePath.lineTo(contentRight, lastY)
        }

        canvas.drawPath(curvePath, linePaint)

        // 绘制关键节点圆点与电压文字标签
        for ((x, y, text) in pointMarkers) {
            canvas.drawCircle(x, y, dp2_5, metricDotPaint)
            val textWidth = metricLabelPaint.measureText(text)
            val textX = when {
                x <= contentLeft + dp4 -> contentLeft
                x >= contentRight - dp4 -> contentRight - textWidth
                else -> (x - textWidth / 2f).coerceIn(contentLeft, contentRight - textWidth)
            }
            canvas.drawText(text, textX, y - dp4, metricLabelPaint)
        }
    }

    /**
     * 绘制横贯全宽的固定时间轴屏幕状态条（亮屏绿色，息屏暗灰色）。
     */
    private fun drawScreenStateBar(
        canvas: Canvas,
        contentLeft: Float,
        contentRight: Float,
        top: Float,
        bottom: Float,
        visibleStart: Long,
        visibleEnd: Long
    ) {
        val contentWidth = contentRight - contentLeft
        if (contentWidth <= 0f) return

        // 先铺设一条亮屏绿色底线，确保 100% 可见
        tempRectF.set(contentLeft, top, contentRight, bottom)
        canvas.drawRoundRect(tempRectF, dp1_5, dp1_5, screenOnBarPaint)

        val mergedScreens = TimelineEventMerger.mergeScreenEvents(timelineState.screenEvents)
        for (event in mergedScreens) {
            if (event.endTime < visibleStart || event.startTime > visibleEnd) continue
            // 仅对息屏区间进行覆盖绘制
            if (!event.isScreenOn) {
                val left = (contentLeft + TimelineScaleCalculator.timeToX(event.startTime, visibleStart, visibleEnd, contentWidth)).coerceIn(contentLeft, contentRight)
                val right = (contentLeft + TimelineScaleCalculator.timeToX(event.endTime, visibleStart, visibleEnd, contentWidth)).coerceIn(contentLeft, contentRight)
                if (right > left) {
                    tempRectF.set(left, top, right, bottom)
                    canvas.drawRect(tempRectF, screenOffBarPaint)
                }
            }
        }
    }

    /**
     * 绘制长按垂直十字游标与信息悬浮气泡卡片。
     */
    private fun drawCursorAndTooltip(
        canvas: Canvas,
        contentLeft: Float,
        contentRight: Float,
        contentWidth: Float,
        mainHeight: Float,
        visibleStart: Long,
        visibleEnd: Long
    ) {
        val clampedX = cursorX.coerceIn(contentLeft, contentRight)
        // 1. 垂直虚线
        canvas.drawLine(clampedX, 0f, clampedX, mainHeight + dp6, cursorPaint)

        // 2. 查询当前游标时刻对应的数据
        val curTs = TimelineScaleCalculator.xToTime(clampedX, visibleStart, visibleEnd, contentWidth, contentLeft)
        val curSample = timelineState.batterySamples.minByOrNull { abs(it.timestamp - curTs) }
        val curApp = timelineState.appEvents.find { it.startTime <= curTs && it.endTime >= curTs }

        val timeStr = timeFormatterTooltip.format(Date(curTs))
        val powerStr = curSample?.let {
            val pWatts = it.getPowerWatts()
            val signedPower = if (pWatts > 0) -pWatts else pWatts
            String.format(Locale.getDefault(), "功耗: %.2fW", signedPower)
        } ?: ""
        val levelStr = curSample?.let { "电量: ${it.batteryLevel}%" } ?: ""
        val voltStr = curSample?.let { String.format(Locale.getDefault(), "电压: %.3fV", it.getVoltageVolts()) } ?: ""
        val tempStr = curSample?.let { String.format(Locale.getDefault(), "温度: %.1f℃", it.temperatureC) } ?: ""
        val appStr = curApp?.let { "应用: ${it.appName}" } ?: ""

        val line1 = "$timeStr  $levelStr"
        val line2 = "$powerStr  $voltStr"
        val line3 = if (appStr.isNotEmpty()) "$tempStr  $appStr" else tempStr

        // 3. 计算气泡尺寸与位置
        val padding = dp8
        val textH = sp10_5 * 1.3f
        val boxWidth = dp14 * 10
        val boxHeight = textH * 3 + padding * 2

        var boxLeft = clampedX - boxWidth / 2f
        if (boxLeft < contentLeft) boxLeft = contentLeft
        if (boxLeft + boxWidth > contentRight) boxLeft = contentRight - boxWidth
        val boxTop = dp4

        tooltipRect.set(boxLeft, boxTop, boxLeft + boxWidth, boxTop + boxHeight)
        canvas.drawRoundRect(tooltipRect, dp6, dp6, tooltipBgPaint)

        canvas.drawText(line1, boxLeft + padding, boxTop + padding + textH, tooltipTextPaint)
        canvas.drawText(line2, boxLeft + padding, boxTop + padding + textH * 2, tooltipTextPaint)
        canvas.drawText(line3, boxLeft + padding, boxTop + padding + textH * 3, tooltipTextPaint)
    }

    /**
     * 处理单指点击，检测是否命中 App 图标徽章。
     *
     * @param x 点击 X 坐标
     * @param y 点击 Y 坐标
     * @return 是否成功处理点击事件
     */
    private fun handleSingleTap(x: Float, y: Float): Boolean {
        if (!timelineState.selectedMetrics.contains(TimelineMetric.APP)) return false
        val touchSlop = dp4

        for (item in cachedSlotItems) {
            if (x >= item.left - touchSlop && x <= item.right + touchSlop &&
                y >= item.top - touchSlop && y <= item.bottom + touchSlop) {
                onAppEventListener?.onAppClick(item.event)
                return true
            }
        }
        return false
    }

    /**
     * 触摸与手势事件分发处理。
     *
     * @param event 触摸事件 [MotionEvent]
     * @return 是否消费触摸事件
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val scaleHandled = scaleGestureDetector.onTouchEvent(event)
        val gestureHandled = gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (isCursorActive) {
                    cursorX = event.x.coerceIn(0f, width.toFloat())
                    notifyCursorMove(cursorX)
                    invalidate()
                    return true
                }

                if (!scaleGestureDetector.isInProgress && event.pointerCount == 1) {
                    val dx = event.x - lastTouchX
                    if (abs(dx) > dp4 || isDragging) {
                        isDragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        applyScroll(dx)
                        lastTouchX = event.x
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isCursorActive) {
                    isCursorActive = false
                    onCursorInspectListener?.onCursorDismiss()
                    invalidate()
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                isDragging = false
            }
        }

        return scaleHandled || gestureHandled || isDragging || isCursorActive || super.onTouchEvent(event)
    }

    /**
     * 应用双指时间缩放算法。
     *
     * @param factor 缩放比例因子
     * @param focusX 缩放中心焦点 X 坐标
     */
    private fun applyZoom(factor: Float, focusX: Float) {
        val w = width.toFloat()
        if (w <= 0f) return

        val currentStart = timelineState.visibleStartTimestamp
        val currentEnd = timelineState.visibleEndTimestamp
        val currentSpan = (currentEnd - currentStart).coerceAtLeast(1000L)

        val totalStart = timelineState.startTimestamp
        val totalEnd = timelineState.endTimestamp
        val totalSpan = (totalEnd - totalStart).coerceAtLeast(1000L)

        // 缩放范围限制：最小显示 30 秒，最大显示全部数据
        val minSpan = 30_000L
        val maxSpan = totalSpan

        val newSpan = (currentSpan / factor).toLong().coerceIn(minSpan, maxSpan)
        val focusRatio = (focusX / w).coerceIn(0f, 1f)

        val focusTime = currentStart + (currentSpan * focusRatio).toLong()
        var newStart = focusTime - (newSpan * focusRatio).toLong()
        var newEnd = newStart + newSpan

        if (newStart < totalStart) {
            newStart = totalStart
            newEnd = min(totalEnd, newStart + newSpan)
        }
        if (newEnd > totalEnd) {
            newEnd = totalEnd
            newStart = max(totalStart, newEnd - newSpan)
        }

        val newZoom = (totalSpan.toFloat() / newSpan.toFloat()).coerceIn(1.0f, 32.0f)
        timelineState = timelineState.copy(
            visibleStartTimestamp = newStart,
            visibleEndTimestamp = newEnd,
            zoomScale = newZoom
        )
        recalculateLayout()
        invalidate()
    }

    /**
     * 应用横向拖拽时间滚动偏移。
     *
     * @param dx 水平位移像素
     */
    private fun applyScroll(dx: Float) {
        val w = width.toFloat()
        if (w <= 0f) return

        val currentStart = timelineState.visibleStartTimestamp
        val currentEnd = timelineState.visibleEndTimestamp
        val currentSpan = currentEnd - currentStart

        val totalStart = timelineState.startTimestamp
        val totalEnd = timelineState.endTimestamp

        val dt = (-dx / w * currentSpan).toLong()
        var newStart = currentStart + dt
        var newEnd = currentEnd + dt

        if (newStart < totalStart) {
            newStart = totalStart
            newEnd = newStart + currentSpan
        }
        if (newEnd > totalEnd) {
            newEnd = totalEnd
            newStart = newEnd - currentSpan
        }

        timelineState = timelineState.copy(
            visibleStartTimestamp = newStart,
            visibleEndTimestamp = newEnd
        )
        recalculateLayout()
    }

    /**
     * 通知游标移动回调。
     *
     * @param x 游标当前 X 像素坐标
     */
    private fun notifyCursorMove(x: Float) {
        val w = width.toFloat()
        if (w <= 0f) return
        val ts = TimelineScaleCalculator.xToTime(x, timelineState.visibleStartTimestamp, timelineState.visibleEndTimestamp, w)
        val sample = timelineState.batterySamples.minByOrNull { abs(it.timestamp - ts) }
        val app = timelineState.appEvents.find { it.startTime <= ts && it.endTime >= ts }
        onCursorInspectListener?.onCursorMove(ts, sample, app)
    }

    /**
     * 将 dp 数值转换为 px。
     *
     * @param dp dp 标量
     * @return 像素值 px
     */
    private fun dpToPx(dp: Float): Float {
        return dp * context.resources.displayMetrics.density
    }

    /**
     * 将 sp 数值转换为 px。
     *
     * @param sp sp 标量
     * @return 像素值 px
     */
    private fun spToPx(sp: Float): Float {
        return sp * context.resources.displayMetrics.scaledDensity
    }
}
