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
    private val dp28 = dpToPx(28f)
    private val dp30 = dpToPx(30f)
    private val dp32 = dpToPx(32f)
    private val dp35 = dpToPx(35f)
    private val dp36 = dpToPx(36f)

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
        textAlign = Paint.Align.CENTER
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
        color = Color.parseColor("#FF3B30") // 息屏红（精确到秒）
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
    private val barClipPath = Path()
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
     * 测量时间轴 View 尺寸，以图标纵向叠加高度超出为准动态计算并自适应图表高度。
     *
     * @param widthMeasureSpec 宽度测量规格
     * @param heightMeasureSpec 高度测量规格
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val defaultBaseH = dpToPx(240f).toInt()
        val maxRow = cachedSlotItems.maxOfOrNull { it.rowIndex } ?: -1
        val maxRowsCount = maxRow + 1

        // 仅当图标纵向叠加层数极多超出基础高度范围时，才动态扩充高度
        val iconStackHeight = (maxRowsCount * (dp15 + dp1)).toInt()
        val requiredHeight = dpToPx(80f).toInt() + iconStackHeight
        val desiredHeight = maxOf(defaultBaseH, requiredHeight)

        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val finalHeight: Int = when (heightMode) {
            MeasureSpec.EXACTLY -> if (desiredHeight > heightSize) desiredHeight else heightSize
            MeasureSpec.AT_MOST -> minOf(desiredHeight, heightSize)
            else -> desiredHeight
        }
        setMeasuredDimension(width, finalHeight)
    }

    /**
     * 重新计算 App 图标的时间槽平铺与多行纵向堆叠排布（上下间距 1dp）。
     */
    private fun recalculateLayout() {
        val previousMaxRow = cachedSlotItems.maxOfOrNull { it.rowIndex } ?: -1
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
            rowGapPx = dp1,
            maxRows = Int.MAX_VALUE,
            leftMarginPx = contentLeft
        )
        cachedSlotItems.addAll(laidOut)
        val currentMaxRow = cachedSlotItems.maxOfOrNull { it.rowIndex } ?: -1
        if (currentMaxRow != previousMaxRow) {
            requestLayout()
        }
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
     * 2. 多选曲线自适应锚点绘制（功耗、电量阶梯折线及百分比点标、温度阶梯折线及数值点标、电压阶梯折线及数值点标）；
     * 3. App 活动分槽平铺图标（从下往上纵向堆叠，允许与曲线区域产生视觉交叠）；
     * 4. 底部时间轴屏幕状态实线条与秒级精确时间刻度文字。
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

        // 曲线区域使用完整图表高度，允许与下方纵向堆叠的应用图标产生自然的视觉交叠
        val mainChartHeight = screenBarTop - dp12
        val topPadding = dp36
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

        // 2. 绘制垂直时间网格虚线与底部时间刻度文字（以时间点为中心严格居中对齐）
        drawTimeGridAndTicks(canvas, contentLeft, contentWidth, mainChartHeight, screenBarBottom, timeTextY, visibleStart, visibleEnd)

        // 3. 绘制 App 活动分槽平铺徽章（置于底层，使后续所有曲线覆盖在应用小图标之上）
        val metrics = timelineState.selectedMetrics
        if (metrics.contains(TimelineMetric.APP)) {
            drawAppEventsLayer(canvas)
        }

        // 4. 多选/反选模式：以平滑三次贝塞尔曲线绘制各指标曲线（全部覆盖在应用小图标之上）
        if (metrics.contains(TimelineMetric.POWER)) {
            drawPowerCurve(canvas, contentLeft, contentWidth, topPadding, availableH, visibleStart, visibleEnd, maxScaleW, rawSamples)
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

        // 5. 绘制横贯全宽的固定底图时间轴屏幕状态实线条（亮屏绿 / 息屏红）
        drawScreenStateBar(canvas, contentLeft, contentRight, screenBarTop, screenBarBottom, visibleStart, visibleEnd)

        // 6. 若长按处于活跃状态，绘制垂直游标并将信息固定在图表顶部展示（无遮挡弹框）
        if (isCursorActive) {
            drawCursorAndHeaderInfo(canvas, contentLeft, contentRight, contentWidth, screenBarTop, visibleStart, visibleEnd)
        }
    }

    /**
     * 绘制图表左侧功耗 Y 轴数值刻度（如 30W, 20W, 10W, 0W）与横向网格虚线。
     *
     * @param canvas 绘图画布 [Canvas]
     * @param contentLeft 图表左边界 X 坐标
     * @param contentRight 图表右边界 X 坐标
     * @param topPadding 顶部安全间距
     * @param availableH 有效高度
     * @param maxScaleW 最大功耗刻度值
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
     * 绘制时间轴网格虚线与底部时间刻度文字（以对应时间点为中心严格居中对齐）。
     *
     * @param canvas 绘图画布 [Canvas]
     * @param contentLeft 图表左边界 X 坐标
     * @param contentWidth 图表有效宽度
     * @param mainHeight 曲线主体高度
     * @param tickTop 刻度短线上边缘 Y 坐标
     * @param textY 时间文字基线 Y 坐标
     * @param visibleStart 视窗起始时间戳
     * @param visibleEnd 视窗结束时间戳
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
        val w = width.toFloat()
        for (tick in ticks) {
            val x = contentLeft + tick.xRatio * contentWidth
            // 垂直虚线网格
            canvas.drawLine(x, dp36, x, mainHeight, gridPaint)
            // 刻度小短线
            canvas.drawLine(x, tickTop, x, tickTop + dp3, gridPaint)
            // 时间文本以对应时间点 x 为中心严格居中对齐绘制，并在屏幕边缘做安全防截断
            val textWidth = textPaint.measureText(tick.label)
            val halfWidth = textWidth / 2f
            val textX = x.coerceIn(halfWidth + dp2, w - halfWidth - dp2)
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
     * 绘制功耗波动平滑曲线（采用三次贝塞尔平滑算法，始终自左侧 contentLeft 开始并横跨全宽）。
     *
     * @param canvas 目标绘制画布 [Canvas]
     * @param contentLeft 内容区左边缘 X 坐标
     * @param contentWidth 内容区宽度
     * @param topPadding 顶部安全边距
     * @param availableH 曲线有效绘制高度
     * @param visibleStart 可视起始时间戳
     * @param visibleEnd 可视结束时间戳
     * @param maxScaleW 当前可视区间最大功耗刻度值
     * @param rawSamples 原始物理采样点集合
     */
    private fun drawPowerCurve(
        canvas: Canvas,
        contentLeft: Float,
        contentWidth: Float,
        topPadding: Float,
        availableH: Float,
        visibleStart: Long,
        visibleEnd: Long,
        maxScaleW: Double,
        rawSamples: List<BatterySample>
    ) {
        val downsampled = ChartDownsampler.downsample(rawSamples, targetMaxPoints = 1200)
        if (downsampled.isEmpty()) return

        val contentRight = contentLeft + contentWidth
        val strokeColor = Color.parseColor("#90CAF9")

        linePaint.color = strokeColor
        curvePath.reset()

        val firstSample = downsampled.first()
        val firstPW = (abs(firstSample.powerMw) / 1000.0).toFloat().coerceIn(0f, maxScaleW.toFloat())
        val firstY = topPadding + (1f - (firstPW / maxScaleW).toFloat()) * availableH

        // 曲线始终自最左侧起点 (contentLeft, firstY) 开始
        curvePath.moveTo(contentLeft, firstY)

        var lastX = contentLeft
        var lastY = firstY

        for (s in downsampled) {
            val x = (contentLeft + TimelineScaleCalculator.timeToX(s.timestamp, visibleStart, visibleEnd, contentWidth)).coerceIn(contentLeft, contentRight)
            val pW = (abs(s.powerMw) / 1000.0).toFloat().coerceIn(0f, maxScaleW.toFloat())
            val y = topPadding + (1f - (pW / maxScaleW).toFloat()) * availableH

            if (x > lastX) {
                val cX = (lastX + x) / 2f
                curvePath.cubicTo(cX, lastY, cX, y, x, y)
                lastX = x
                lastY = y
            }
        }

        // 确保功耗曲线一直延伸至最右侧终点 contentRight
        if (lastX < contentRight) {
            val cX = (lastX + contentRight) / 2f
            curvePath.cubicTo(cX, lastY, cX, lastY, contentRight, lastY)
        }

        canvas.drawPath(curvePath, linePaint)
    }

    /**
     * 绘制电量平滑衰减曲线、拐点圆点及百分比数值标签（鲜绿色，始终自左侧 contentLeft 起步并贯穿整个时间轴）。
     *
     * @param canvas 绘图画布 [Canvas]
     * @param contentLeft 内容区左边界 X 坐标
     * @param contentWidth 内容区宽度
     * @param topPadding 顶部安全间距
     * @param availableH 曲线有效可用高度
     * @param visibleStart 视窗起始时间戳
     * @param visibleEnd 视窗结束时间戳
     * @param rawSamples 原始采样点数据列表
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
        val downsampled = ChartDownsampler.downsample(rawSamples, targetMaxPoints = 800)
        if (downsampled.isEmpty()) return

        val contentRight = contentLeft + contentWidth
        val color = Color.parseColor("#4CAF50")
        linePaint.color = color
        metricDotPaint.color = color
        metricLabelPaint.color = color

        curvePath.reset()
        val firstSample = downsampled.first()
        // 电量曲线分布在图表上方区间 (0.05 ~ 0.45)，与下方功耗曲线天然隔离
        val firstNorm = (firstSample.batteryLevel / 100f).coerceIn(0f, 1f) * 0.45f + 0.50f
        val firstY = topPadding + (1f - firstNorm) * availableH

        // 无论处于何种时间跨度，曲线始终从最左侧起点 (contentLeft, firstY) 开始
        curvePath.moveTo(contentLeft, firstY)

        val pointMarkers = mutableListOf<Triple<Float, Float, String>>()
        // 起点数值标签固定在左侧起点 (contentLeft)
        pointMarkers.add(Triple(contentLeft, firstY, "${firstSample.batteryLevel}%"))

        var lastX = contentLeft
        var lastY = firstY
        var lastLevel = firstSample.batteryLevel

        for (s in downsampled) {
            val x = (contentLeft + TimelineScaleCalculator.timeToX(s.timestamp, visibleStart, visibleEnd, contentWidth)).coerceIn(contentLeft, contentRight)
            val norm = (s.batteryLevel / 100f).coerceIn(0f, 1f) * 0.45f + 0.50f
            val y = topPadding + (1f - norm) * availableH

            if (x > lastX) {
                val cX = (lastX + x) / 2f
                curvePath.cubicTo(cX, lastY, cX, y, x, y)
                if (s.batteryLevel != lastLevel) {
                    pointMarkers.add(Triple(x, y, "${s.batteryLevel}%"))
                }
                lastX = x
                lastY = y
                lastLevel = s.batteryLevel
            }
        }

        // 确保平滑曲线始终横贯延伸至最右侧终点 (contentRight, lastY)
        if (lastX < contentRight) {
            val cX = (lastX + contentRight) / 2f
            curvePath.cubicTo(cX, lastY, cX, lastY, contentRight, lastY)
        }

        canvas.drawPath(curvePath, linePaint)

        // 绘制关键节点圆点与电量百分比文字标签（应用防重叠安全间距避让）
        drawNonOverlappingMarkers(canvas, pointMarkers, metricLabelPaint, metricDotPaint, contentLeft, contentRight, -dp4, dp20)
    }

    /**
     * 绘制温度平滑温升/降温曲线、拐点圆点及摄氏度数值标签（珊瑚橙色，始终自左侧 contentLeft 起步并贯穿整个时间轴）。
     *
     * @param canvas 绘图画布 [Canvas]
     * @param contentLeft 内容区左边界 X 坐标
     * @param contentWidth 内容区宽度
     * @param topPadding 顶部安全间距
     * @param availableH 曲线有效可用高度
     * @param visibleStart 视窗起始时间戳
     * @param visibleEnd 视窗结束时间戳
     * @param rawSamples 原始采样点数据列表
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
        val downsampled = ChartDownsampler.downsample(rawSamples, targetMaxPoints = 800)
        if (downsampled.isEmpty()) return

        val contentRight = contentLeft + contentWidth
        val color = Color.parseColor("#FF7043")
        linePaint.color = color
        metricDotPaint.color = color
        metricLabelPaint.color = color

        curvePath.reset()
        val firstSample = downsampled.first()
        // 将 15℃ ~ 45℃ 映射至中上层区间 (0.45 ~ 0.85)，彻底避免与底部待机低功耗线条发生视觉黏连与重叠
        val firstNorm = (((firstSample.temperatureC - 15.0) / 30.0).coerceIn(0.0, 1.0) * 0.40 + 0.45).toFloat()
        val firstY = topPadding + (1f - firstNorm) * availableH

        // 平滑曲线始终从最左侧起点 (contentLeft, firstY) 开始
        curvePath.moveTo(contentLeft, firstY)

        val pointMarkers = mutableListOf<Triple<Float, Float, String>>()
        // 起点温度标签固定在左侧起点 (contentLeft)
        pointMarkers.add(Triple(contentLeft, firstY, String.format(Locale.getDefault(), "%.1f℃", firstSample.temperatureC)))

        var lastX = contentLeft
        var lastY = firstY
        var lastTempInt = (firstSample.temperatureC * 2).toInt()

        for (s in downsampled) {
            val x = (contentLeft + TimelineScaleCalculator.timeToX(s.timestamp, visibleStart, visibleEnd, contentWidth)).coerceIn(contentLeft, contentRight)
            val norm = (((s.temperatureC - 15.0) / 30.0).coerceIn(0.0, 1.0) * 0.40 + 0.45).toFloat()
            val y = topPadding + (1f - norm) * availableH

            if (x > lastX) {
                val cX = (lastX + x) / 2f
                curvePath.cubicTo(cX, lastY, cX, y, x, y)
                val tempInt = (s.temperatureC * 2).toInt()
                if (tempInt != lastTempInt && pointMarkers.size < 6) {
                    pointMarkers.add(Triple(x, y, String.format(Locale.getDefault(), "%.1f℃", s.temperatureC)))
                }
                lastX = x
                lastY = y
                lastTempInt = tempInt
            }
        }

        // 确保平滑曲线始终横贯延伸至最右侧终点 (contentRight, lastY)
        if (lastX < contentRight) {
            val cX = (lastX + contentRight) / 2f
            curvePath.cubicTo(cX, lastY, cX, lastY, contentRight, lastY)
        }

        canvas.drawPath(curvePath, linePaint)

        // 绘制关键节点圆点与温度文字标签（应用防重叠安全间距避让）
        drawNonOverlappingMarkers(canvas, pointMarkers, metricLabelPaint, metricDotPaint, contentLeft, contentRight, dp10, dp24)
    }

    /**
     * 绘制电压平滑变化曲线、拐点圆点及伏特数值标签（金黄色，始终自左侧 contentLeft 起步并贯穿整个时间轴）。
     *
     * @param canvas 绘图画布 [Canvas]
     * @param contentLeft 内容区左边界 X 坐标
     * @param contentWidth 内容区宽度
     * @param topPadding 顶部安全间距
     * @param availableH 曲线有效可用高度
     * @param visibleStart 视窗起始时间戳
     * @param visibleEnd 视窗结束时间戳
     * @param rawSamples 原始采样点数据列表
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
        val downsampled = ChartDownsampler.downsample(rawSamples, targetMaxPoints = 800)
        if (downsampled.isEmpty()) return

        val contentRight = contentLeft + contentWidth
        val color = Color.parseColor("#FFCA28")
        linePaint.color = color
        metricDotPaint.color = color
        metricLabelPaint.color = color

        curvePath.reset()
        val firstSample = downsampled.first()
        val firstVoltV = firstSample.voltageMv / 1000f
        // 将 3.4V ~ 4.4V 电压映射至中层区间 (0.35 ~ 0.70)
        val firstNorm = (((firstVoltV - 3.4f) / 1.0f).coerceIn(0f, 1f) * 0.35f + 0.35f)
        val firstY = topPadding + (1f - firstNorm) * availableH

        // 平滑曲线始终从最左侧起点 (contentLeft, firstY) 开始
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
            val norm = (((voltV - 3.4f) / 1.0f).coerceIn(0f, 1f) * 0.35f + 0.35f)
            val y = topPadding + (1f - norm) * availableH

            if (x > lastX) {
                val cX = (lastX + x) / 2f
                curvePath.cubicTo(cX, lastY, cX, y, x, y)
                if (abs(voltV - lastVolt) >= 0.05f && pointMarkers.size < 6) {
                    pointMarkers.add(Triple(x, y, String.format(Locale.getDefault(), "%.3f V", voltV)))
                }
                lastX = x
                lastY = y
                lastVolt = voltV
            }
        }

        // 确保平滑曲线始终横贯延伸至最右侧终点 (contentRight, lastY)
        if (lastX < contentRight) {
            val cX = (lastX + contentRight) / 2f
            curvePath.cubicTo(cX, lastY, cX, lastY, contentRight, lastY)
        }

        canvas.drawPath(curvePath, linePaint)

        // 绘制关键节点圆点与电压文字标签（应用防重叠安全间距避让）
        drawNonOverlappingMarkers(canvas, pointMarkers, metricLabelPaint, metricDotPaint, contentLeft, contentRight, -dp4, dp28)
    }

    /**
     * 绘制曲线关键节点圆点与数值文字标签，并应用横向安全间距防重叠避让算法。
     * 确保相邻文字标签之间具有充足的横向间距，彻底消除文字左右重叠。
     *
     * @param canvas 绘制目标画布 [Canvas]
     * @param candidates 候选节点列表（X坐标, Y坐标, 标签文本）
     * @param paint 文本画笔 [Paint]
     * @param dotPaint 圆点画笔 [Paint]
     * @param contentLeft 内容区左边缘 X 坐标
     * @param contentRight 内容区右边缘 X 坐标
     * @param yOffset 文本相对于 Y 坐标的纵向偏移像素（向上为负，向下为正）
     * @param minSpacingPx 相邻标签之间的最小安全横向像素间距
     */
    private fun drawNonOverlappingMarkers(
        canvas: Canvas,
        candidates: List<Triple<Float, Float, String>>,
        paint: Paint,
        dotPaint: Paint,
        contentLeft: Float,
        contentRight: Float,
        yOffset: Float,
        minSpacingPx: Float = dp32
    ) {
        if (candidates.isEmpty()) return

        var lastDrawnRight = -Float.MAX_VALUE
        for (i in candidates.indices) {
            val (x, y, text) = candidates[i]
            val textWidth = paint.measureText(text)
            val halfW = textWidth / 2f
            val textLeft = (x - halfW).coerceIn(contentLeft, contentRight - textWidth)
            val textRight = textLeft + textWidth

            // 判断是否与上一个已绘制的标签发生横向重叠（保留起点和具有足够安全间距的关键拐点）
            val isFirst = (i == 0)
            val hasEnoughSpace = (textLeft - lastDrawnRight) >= minSpacingPx

            if (isFirst || hasEnoughSpace) {
                // 绘制节点小圆点
                canvas.drawCircle(x, y, dp2_5, dotPaint)
                // 绘制文本
                canvas.drawText(text, textLeft, y + yOffset, paint)
                lastDrawnRight = textRight
            }
        }
    }

    /**
     * 绘制横贯全宽的固定时间轴屏幕状态条（亮屏绿色，息屏红色，精确到秒）。
     *
     * @param canvas 绘图画布 [Canvas]
     * @param contentLeft 图表内容区域左边界 X 坐标（像素）
     * @param contentRight 图表内容区域右边界 X 坐标（像素）
     * @param top 屏幕状态条顶部 Y 坐标（像素）
     * @param bottom 屏幕状态条底部 Y 坐标（像素）
     * @param visibleStart 当前视窗起始时间戳（毫秒）
     * @param visibleEnd 当前视窗结束时间戳（毫秒）
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

        val mergedScreens = TimelineEventMerger.mergeScreenEvents(timelineState.screenEvents)
        if (mergedScreens.isEmpty()) {
            tempRectF.set(contentLeft, top, contentRight, bottom)
            canvas.drawRoundRect(tempRectF, dp1_5, dp1_5, screenOnBarPaint)
            return
        }

        // 使用 Path 裁切圆角边界，使左右两端呈现平滑圆角，内部颜色精确无缝分段
        val saveCount = canvas.save()
        tempRectF.set(contentLeft, top, contentRight, bottom)
        barClipPath.reset()
        barClipPath.addRoundRect(tempRectF, dp1_5, dp1_5, Path.Direction.CW)
        canvas.clipPath(barClipPath)

        // 默认全铺息屏红底色
        canvas.drawRect(tempRectF, screenOffBarPaint)

        // 精确绘制每个亮屏/息屏分段（精确到秒）
        for (event in mergedScreens) {
            if (event.endTime < visibleStart || event.startTime > visibleEnd) continue
            val left = (contentLeft + TimelineScaleCalculator.timeToX(event.startTime, visibleStart, visibleEnd, contentWidth)).coerceIn(contentLeft, contentRight)
            val right = (contentLeft + TimelineScaleCalculator.timeToX(event.endTime, visibleStart, visibleEnd, contentWidth)).coerceIn(contentLeft, contentRight)
            if (right > left) {
                tempRectF.set(left, top, right, bottom)
                val paint = if (event.isScreenOn) screenOnBarPaint else screenOffBarPaint
                canvas.drawRect(tempRectF, paint)
            }
        }

        canvas.restoreToCount(saveCount)
    }

    /**
     * 绘制长按垂直游标线并将探查到的时间、电量、功耗、温度、电压与前台应用信息分两行换行固定绘制在图表顶部（无遮挡弹框）。
     *
     * @param canvas 绘图画布 [Canvas]
     * @param contentLeft 图表左边界 X 坐标
     * @param contentRight 图表右边界 X 坐标
     * @param contentWidth 图表内容有效宽度
     * @param mainHeight 曲线区域高度
     * @param visibleStart 视窗起始时间戳
     * @param visibleEnd 视窗结束时间戳
     */
    private fun drawCursorAndHeaderInfo(
        canvas: Canvas,
        contentLeft: Float,
        contentRight: Float,
        contentWidth: Float,
        mainHeight: Float,
        visibleStart: Long,
        visibleEnd: Long
    ) {
        val clampedX = cursorX.coerceIn(contentLeft, contentRight)
        // 1. 垂直虚线游标
        canvas.drawLine(clampedX, dp32, clampedX, mainHeight + dp6, cursorPaint)

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

        // 3. 将信息分两行换行排布：第 1 行为时间、电量、功耗；第 2 行为温度、电压、前台应用
        val line1Items = listOfNotNull(
            timeStr.takeIf { it.isNotEmpty() },
            levelStr.takeIf { it.isNotEmpty() },
            powerStr.takeIf { it.isNotEmpty() }
        )
        val line2Items = listOfNotNull(
            tempStr.takeIf { it.isNotEmpty() },
            voltStr.takeIf { it.isNotEmpty() },
            appStr.takeIf { it.isNotEmpty() }
        )
        val line1Text = line1Items.joinToString("   ")
        val line2Text = line2Items.joinToString("   ")

        // 4. 固定在图表顶部绘制两行背景胶囊与文本
        val headerTop = dp2
        val headerBottom = dp32
        tooltipRect.set(contentLeft, headerTop, contentRight, headerBottom)
        canvas.drawRoundRect(tooltipRect, dp4, dp4, tooltipBgPaint)

        val line1Y = headerTop + sp9_5 * 1.15f + dp2
        val line2Y = line1Y + sp9_5 * 1.35f
        canvas.drawText(line1Text, contentLeft + dp8, line1Y, tooltipTextPaint)
        if (line2Text.isNotEmpty()) {
            canvas.drawText(line2Text, contentLeft + dp8, line2Y, tooltipTextPaint)
        }
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
