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
    private val dp11 = dpToPx(11f)
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

    // 画笔体系（趋势折线改小一号为 dp1）
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
        textAlign = Paint.Align.LEFT
    }

    private val metricLabelHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp8_5
        textAlign = Paint.Align.LEFT
        style = Paint.Style.STROKE
        strokeWidth = dp2
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#CC121820")
    }

    /**
     * 曲线关键节点小数字标签数据载体。
     *
     * @property x 物理绘制横坐标（像素）
     * @property y 物理绘制纵坐标（像素）
     * @property text 格式化数值显示文本
     * @property isPriority 是否为高优先级关键极值（整条曲线最峰、最谷为 true，享有绝对优先绘制权）
     */
    private data class CurveMarker(
        val x: Float,
        val y: Float,
        val text: String,
        val isPriority: Boolean = false
    )

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
        color = Color.parseColor("#12888888")
    }

    private val tooltipBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp1
        color = Color.parseColor("#1F888888")
    }

    private val tooltipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp9_5
        color = Color.parseColor("#E0E0E0")
    }

    // 绘制复用 Path 与 Rect
    private val curvePath = Path()
    private val fillPath = Path()
    private val barClipPath = Path()
    private val tempPath = Path()
    private val tempRectF = RectF()
    private val tempDstRectF = RectF()
    private val tempSrcRect = Rect()
    private val tooltipRect = RectF()
    private val cachedSlotItems = mutableListOf<TimelineLayoutCalculator.LaidOutAppSlotItem>()

    /**
     * 曲线与标注点的预计算绘制缓存数据实体。
     *
     * @property path 预先构建完成的三次贝塞尔平滑路径
     * @property markers 预先排布且完成碰撞避让的节点标注集合
     */
    private class CachedCurveData {
        val path = Path()
        val markers = mutableListOf<CurveMarker>()
    }

    private val cachedPowerCurve = CachedCurveData()
    private val cachedBatteryCurve = CachedCurveData()
    private val cachedTempCurve = CachedCurveData()
    private val cachedVoltCurve = CachedCurveData()

    /** 标记曲线与图表绘制缓存是否有效，避免列表滚动时重复重绘与重复 LTTB 计算 */
    private var isCurveCacheValid = false
    private var cachedRawSamples = emptyList<BatterySample>()
    private var cachedMaxScaleW = 15.0

    /**
     * 将曲线与图表绘制缓存标记为失效，触发下一次绘制前的按需重新预计算。
     */
    private fun invalidateCurveCache() {
        isCurveCacheValid = false
    }

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
        invalidateCurveCache()
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
            invalidateCurveCache()
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

        // 仅当图标纵向叠加层数极多超出基础高度范围时，才动态扩充高度（纵向上下间距为 0）
        val iconStackHeight = (maxRowsCount * dp11).toInt()
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
     * 重新计算 App 图标的时间槽平铺与多行纵向堆叠排布（小图标尺寸紧凑为 11dp，纵向上下间距为 0）。
     */
    private fun recalculateLayout(canRequestLayout: Boolean = true) {
        val previousMaxRow = cachedSlotItems.maxOfOrNull { it.rowIndex } ?: -1
        cachedSlotItems.clear()
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val contentLeft = dp14
        val contentRight = w - dp14
        val contentWidth = max(0f, contentRight - contentLeft)

        val visibleStart = timelineState.visibleStartTimestamp
        val visibleEnd = if (timelineState.visibleEndTimestamp > visibleStart) timelineState.visibleEndTimestamp else (visibleStart + 60_000L)

        val timeTickTop = h - dp18
        val screenBarBottom = timeTickTop - dp2
        val screenBarTop = screenBarBottom - dp3_5
        val baseBottomY = screenBarTop - dp2

        val laidOut = TimelineLayoutCalculator.calculateSlotItems(
            events = timelineState.appEvents,
            visibleStartTs = visibleStart,
            visibleEndTs = visibleEnd,
            canvasWidth = contentWidth,
            baseBottomY = baseBottomY,
            slotSizePx = dp11,
            slotGapPx = 0f,
            rowGapPx = 0f,
            maxRows = Int.MAX_VALUE,
            leftMarginPx = contentLeft
        )
        cachedSlotItems.addAll(laidOut)
        val currentMaxRow = cachedSlotItems.maxOfOrNull { it.rowIndex } ?: -1
        if (canRequestLayout && currentMaxRow != previousMaxRow) {
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
        invalidateCurveCache()
        recalculateLayout()
    }

    /**
     * 视图从窗口脱附时的回调（如页签切走、Fragment 销毁等）。
     * 主动调用 [DrawableBitmapCache.trimToLevel] 释放 50% 图标 Bitmap，
     * 降低时间轴页面不可见期间的后台内存占用。
     */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        DrawableBitmapCache.trimToLevel(20)
    }

    /**
     * 视图重新附着至窗口时的回调（如页签切回）。
     * 使曲线缓存失效，确保下次 onDraw 时重新计算绘制路径。
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        invalidateCurveCache()
    }

    /**
     * 核心 Canvas 绘制流程：
     * 1. 绘制横向基准虚线网格；
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

        val contentLeft = dp14
        val contentRight = w - dp14
        val contentWidth = max(0f, contentRight - contentLeft)

        // 底部向上严格锚定布局：
        val timeTextY = h - dp4
        val timeTickTop = h - dp18
        val screenBarBottom = timeTickTop - dp2
        val screenBarTop = screenBarBottom - dp3_5

        // 曲线区域顶部紧凑排布，平时不触摸时不占用多余空间；触摸 View 直接悬浮在顶部
        val mainChartHeight = screenBarTop - dp6
        val topPadding = dp6
        val bottomPadding = dp4
        val availableH = max(1f, mainChartHeight - topPadding - bottomPadding)

        // 确保 App 图标排布已就绪（禁止在 onDraw 阶段触发 requestLayout）
        if (cachedSlotItems.isEmpty() && timelineState.appEvents.isNotEmpty()) {
            recalculateLayout(canRequestLayout = false)
        }

        // 确保曲线与图表数据预计算就绪（列表垂直滚动时直接复用，零每帧 CPU 计算开销）
        if (!isCurveCacheValid) {
            updateCurveCache(contentLeft, contentWidth, topPadding, availableH, visibleStart, visibleEnd)
            isCurveCacheValid = true
        }

        // 1. 绘制横向基准网格虚线（已去除 Y 轴死板数值刻度文本）
        drawYAxisAndGrid(canvas, contentLeft, contentRight, topPadding, availableH)

        // 2. 绘制垂直时间网格虚线与底部时间刻度文字（以时间点为中心严格居中对齐）
        drawTimeGridAndTicks(canvas, contentLeft, contentWidth, mainChartHeight, screenBarBottom, timeTextY, visibleStart, visibleEnd)

        // 3. 绘制 App 活动分槽平铺徽章（置于底层，使后续所有曲线覆盖在应用小图标之上）
        val metrics = timelineState.selectedMetrics
        if (metrics.contains(TimelineMetric.APP)) {
            drawAppEventsLayer(canvas)
        }

        // 4. 多选/反选模式：以平滑三次贝塞尔曲线绘制各指标曲线（直接复用预计算 Path 与 Markers）
        if (metrics.contains(TimelineMetric.POWER)) {
            drawPowerCurve(canvas, cachedPowerCurve, topPadding, availableH, contentLeft, contentRight)
        }
        if (metrics.contains(TimelineMetric.BATTERY)) {
            drawBatteryCurve(canvas, cachedBatteryCurve, topPadding, availableH, contentLeft, contentRight)
        }
        if (metrics.contains(TimelineMetric.TEMPERATURE)) {
            drawTemperatureCurve(canvas, cachedTempCurve, topPadding, availableH, contentLeft, contentRight)
        }
        if (metrics.contains(TimelineMetric.VOLTAGE)) {
            drawVoltageCurve(canvas, cachedVoltCurve, topPadding, availableH, contentLeft, contentRight)
        }

        // 5. 绘制横贯全宽的固定底图时间轴屏幕状态实线条（亮屏绿 / 息屏红）
        drawScreenStateBar(canvas, contentLeft, contentRight, screenBarTop, screenBarBottom, visibleStart, visibleEnd)

        // 6. 若长按处于活跃状态，绘制垂直游标并将信息固定在图表顶部展示（无遮挡弹框）
        if (isCursorActive) {
            drawCursorAndHeaderInfo(canvas, contentLeft, contentRight, contentWidth, screenBarTop, visibleStart, visibleEnd)
        }
    }

    /**
     * 绘制图表横向基准参考网格虚线（已按用户需求去除 Y 轴显示的 0w, 6w, 13w 等死板刻度文本）。
     *
     * @param canvas 绘图画布 [Canvas]
     * @param contentLeft 图表左边界 X 坐标
     * @param contentRight 图表右边界 X 坐标
     * @param topPadding 顶部安全间距
     * @param availableH 有效高度
     */
    private fun drawYAxisAndGrid(
        canvas: Canvas,
        contentLeft: Float,
        contentRight: Float,
        topPadding: Float,
        availableH: Float
    ) {
        val steps = listOf(1.0, 0.6667, 0.3333, 0.0)
        for (stepRatio in steps) {
            val y = topPadding + (1f - stepRatio.toFloat()) * availableH
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
            canvas.drawLine(x, dp6, x, mainHeight, gridPaint)
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
        val cornerRadius = dp1_5
        val iconRenderSize = dp11.toInt().coerceAtLeast(1)
        val cLeft = dp14
        val cRight = width.toFloat() - dp14

        for (item in cachedSlotItems) {
            // 视口横向可见性过滤裁剪（Culling）：完全超出内容边界的图标直接跳过
            if (item.right < cLeft || item.left > cRight) continue

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
     * 格式化瞬时功耗数值，待机微弱功耗（< 1.0W）保留两位小数，日常功耗（>= 1.0W）保留一位小数。
     *
     * @param pWatts 瞬时功耗数值（W）
     * @return 格式化后的功耗描述文本
     */
    private fun formatPowerWatts(pWatts: Float): String {
        return if (pWatts < 1.0f) {
            String.format(Locale.getDefault(), "%.2fW", pWatts)
        } else {
            String.format(Locale.getDefault(), "%.1fW", pWatts)
        }
    }

    /**
     * 根据当前视窗与数据预计算并构建各选中曲线的平滑 Path 与关键标注点集合。
     * 仅在视窗平移缩放、指标切换或数据重载时执行一次，杜绝列表上下滑动时每帧重复计算。
     *
     * @param contentLeft 图表内容区域左边界 X 坐标（像素）
     * @param contentWidth 图表内容区域有效宽度（像素）
     * @param topPadding 曲线顶部安全边距（像素）
     * @param availableH 曲线有效可用高度（像素）
     * @param visibleStart 视窗起始时间戳（毫秒）
     * @param visibleEnd 视窗结束时间戳（毫秒）
     */
    private fun updateCurveCache(
        contentLeft: Float,
        contentWidth: Float,
        topPadding: Float,
        availableH: Float,
        visibleStart: Long,
        visibleEnd: Long
    ) {
        val rawSamples = timelineState.batterySamples.filter { it.timestamp in visibleStart..visibleEnd }
        cachedRawSamples = rawSamples

        val maxRawPowerW = rawSamples.maxOfOrNull { abs(it.powerMw) / 1000.0 } ?: 15.0
        cachedMaxScaleW = when {
            maxRawPowerW <= 5.0 -> 6.0
            maxRawPowerW <= 10.0 -> 10.0
            maxRawPowerW <= 20.0 -> 20.0
            maxRawPowerW <= 30.0 -> 30.0
            maxRawPowerW <= 40.0 -> 40.0
            maxRawPowerW <= 60.0 -> 60.0
            else -> kotlin.math.ceil(maxRawPowerW / 10.0) * 10.0
        }

        val metrics = timelineState.selectedMetrics
        if (metrics.contains(TimelineMetric.POWER)) {
            buildPowerCurveCache(cachedPowerCurve, contentLeft, contentWidth, topPadding, availableH, visibleStart, visibleEnd, cachedMaxScaleW, rawSamples)
        }
        if (metrics.contains(TimelineMetric.BATTERY)) {
            buildBatteryCurveCache(cachedBatteryCurve, contentLeft, contentWidth, topPadding, availableH, visibleStart, visibleEnd, rawSamples)
        }
        if (metrics.contains(TimelineMetric.TEMPERATURE)) {
            buildTemperatureCurveCache(cachedTempCurve, contentLeft, contentWidth, topPadding, availableH, visibleStart, visibleEnd, rawSamples)
        }
        if (metrics.contains(TimelineMetric.VOLTAGE)) {
            buildVoltageCurveCache(cachedVoltCurve, contentLeft, contentWidth, topPadding, availableH, visibleStart, visibleEnd, rawSamples)
        }
    }

    /**
     * 预计算并构建功耗波动平滑曲线 Path 与标注点集合（采用轻量 LTTB 降采样，杜绝过度网格细分）。
     *
     * @param cache 目标功耗曲线缓存对象 [CachedCurveData]
     * @param contentLeft 内容区左边缘 X 坐标
     * @param contentWidth 内容区宽度
     * @param topPadding 顶部安全边距
     * @param availableH 曲线有效绘制高度
     * @param visibleStart 可视起始时间戳
     * @param visibleEnd 可视结束时间戳
     * @param maxScaleW 当前可视区间最大功耗刻度值
     * @param rawSamples 原始物理采样点集合
     */
    private fun buildPowerCurveCache(
        cache: CachedCurveData,
        contentLeft: Float,
        contentWidth: Float,
        topPadding: Float,
        availableH: Float,
        visibleStart: Long,
        visibleEnd: Long,
        maxScaleW: Double,
        rawSamples: List<BatterySample>
    ) {
        cache.path.reset()
        cache.markers.clear()
        val downsampled = ChartDownsampler.downsample(rawSamples, targetMaxPoints = 300)
        if (downsampled.isEmpty()) return

        val contentRight = contentLeft + contentWidth
        val firstSample = downsampled.first()
        val firstPW = (abs(firstSample.powerMw) / 1000.0).toFloat().coerceIn(0f, maxScaleW.toFloat())
        val firstY = topPadding + (1f - (firstPW / maxScaleW).toFloat()) * availableH

        cache.path.moveTo(contentLeft, firstY)
        var lastX = contentLeft
        var lastY = firstY

        for (s in downsampled) {
            val x = (contentLeft + TimelineScaleCalculator.timeToX(s.timestamp, visibleStart, visibleEnd, contentWidth)).coerceIn(contentLeft, contentRight)
            val pW = (abs(s.powerMw) / 1000.0).toFloat().coerceIn(0f, maxScaleW.toFloat())
            val y = topPadding + (1f - (pW / maxScaleW).toFloat()) * availableH

            if (x > lastX) {
                val cX = (lastX + x) / 2f
                cache.path.cubicTo(cX, lastY, cX, y, x, y)
                lastX = x
                lastY = y
            }
        }

        if (lastX < contentRight) {
            val cX = (lastX + contentRight) / 2f
            cache.path.cubicTo(cX, lastY, cX, lastY, contentRight, lastY)
        }

        val maxSample = downsampled.maxByOrNull { abs(it.powerMw) }
        val minSample = downsampled.minByOrNull { abs(it.powerMw) }

        fun toMarker(s: BatterySample, isPriority: Boolean): CurveMarker {
            val x = (contentLeft + TimelineScaleCalculator.timeToX(s.timestamp, visibleStart, visibleEnd, contentWidth)).coerceIn(contentLeft, contentRight)
            val pW = (abs(s.powerMw) / 1000.0).toFloat().coerceIn(0f, maxScaleW.toFloat())
            val y = topPadding + (1f - (pW / maxScaleW).toFloat()) * availableH
            val label = formatPowerWatts(pW)
            return CurveMarker(x, y, label, isPriority)
        }

        if (maxSample != null) {
            cache.markers.add(toMarker(maxSample, isPriority = true))
        }
        if (minSample != null && minSample != maxSample) {
            cache.markers.add(toMarker(minSample, isPriority = true))
        }

        val secondarySamples = mutableListOf<BatterySample>()
        if (firstSample != maxSample && firstSample != minSample) {
            secondarySamples.add(firstSample)
        }

        val bucketCount = 5
        val timeSpan = (visibleEnd - visibleStart).coerceAtLeast(1L)
        val bucketDuration = timeSpan / bucketCount
        for (b in 0 until bucketCount) {
            val bStart = visibleStart + b * bucketDuration
            val bEnd = bStart + bucketDuration
            val bucketSamples = downsampled.filter { it.timestamp in bStart..bEnd }
            if (bucketSamples.isNotEmpty()) {
                val peakInBucket = bucketSamples.maxByOrNull { abs(it.powerMw) }
                if (peakInBucket != null && peakInBucket != maxSample && peakInBucket != minSample && !secondarySamples.contains(peakInBucket)) {
                    secondarySamples.add(peakInBucket)
                }
            }
        }

        secondarySamples.sortBy { it.timestamp }
        for (s in secondarySamples) {
            cache.markers.add(toMarker(s, isPriority = false))
        }
    }

    /**
     * 绘制功耗波动平滑曲线与标注节点（直接复用预计算 Path 与 Markers）。
     *
     * @param canvas 绘制画布 [Canvas]
     * @param cache 功耗曲线缓存对象 [CachedCurveData]
     * @param topPadding 顶部安全边距
     * @param availableH 曲线有效绘制高度
     * @param contentLeft 内容区左边缘 X 坐标
     * @param contentRight 内容区右边缘 X 坐标
     */
    private fun drawPowerCurve(
        canvas: Canvas,
        cache: CachedCurveData,
        topPadding: Float,
        availableH: Float,
        contentLeft: Float,
        contentRight: Float
    ) {
        val strokeColor = Color.parseColor("#B390CAF9")
        linePaint.color = strokeColor
        canvas.drawPath(cache.path, linePaint)

        metricDotPaint.color = strokeColor
        metricLabelPaint.color = strokeColor
        val bottomBound = topPadding + availableH
        drawSmartMarkers(canvas, cache.markers, metricLabelPaint, metricLabelHaloPaint, metricDotPaint, contentLeft, contentRight, topPadding, bottomBound)
    }

    /**
     * 预计算并构建电量平滑衰减曲线 Path 与标注点集合。
     *
     * @param cache 目标电量曲线缓存对象 [CachedCurveData]
     * @param contentLeft 内容区左边界 X 坐标
     * @param contentWidth 内容区宽度
     * @param topPadding 顶部安全间距
     * @param availableH 曲线有效可用高度
     * @param visibleStart 视窗起始时间戳
     * @param visibleEnd 视窗结束时间戳
     * @param rawSamples 原始采样点数据列表
     */
    private fun buildBatteryCurveCache(
        cache: CachedCurveData,
        contentLeft: Float,
        contentWidth: Float,
        topPadding: Float,
        availableH: Float,
        visibleStart: Long,
        visibleEnd: Long,
        rawSamples: List<BatterySample>
    ) {
        cache.path.reset()
        cache.markers.clear()
        if (rawSamples.isEmpty()) return
        val downsampled = ChartDownsampler.downsample(rawSamples, targetMaxPoints = 300)
        if (downsampled.isEmpty()) return

        val contentRight = contentLeft + contentWidth
        val firstSample = downsampled.first()
        val firstNorm = (firstSample.batteryLevel / 100f).coerceIn(0f, 1f) * 0.45f + 0.50f
        val firstY = topPadding + (1f - firstNorm) * availableH

        cache.path.moveTo(contentLeft, firstY)
        cache.markers.add(CurveMarker(contentLeft, firstY, "${firstSample.batteryLevel}%", isPriority = false))

        var lastX = contentLeft
        var lastY = firstY
        var lastLevel = firstSample.batteryLevel

        for (s in downsampled) {
            val x = (contentLeft + TimelineScaleCalculator.timeToX(s.timestamp, visibleStart, visibleEnd, contentWidth)).coerceIn(contentLeft, contentRight)
            val norm = (s.batteryLevel / 100f).coerceIn(0f, 1f) * 0.45f + 0.50f
            val y = topPadding + (1f - norm) * availableH

            if (x > lastX) {
                val cX = (lastX + x) / 2f
                cache.path.cubicTo(cX, lastY, cX, y, x, y)
                if (s.batteryLevel != lastLevel) {
                    cache.markers.add(CurveMarker(x, y, "${s.batteryLevel}%", isPriority = false))
                }
                lastX = x
                lastY = y
                lastLevel = s.batteryLevel
            }
        }

        if (lastX < contentRight) {
            val cX = (lastX + contentRight) / 2f
            cache.path.cubicTo(cX, lastY, cX, lastY, contentRight, lastY)
        }
    }

    /**
     * 绘制电量平滑衰减曲线与数值标签（直接复用预计算 Path 与 Markers）。
     *
     * @param canvas 绘制画布 [Canvas]
     * @param cache 电量曲线缓存对象 [CachedCurveData]
     * @param topPadding 顶部安全间距
     * @param availableH 曲线有效可用高度
     * @param contentLeft 内容区左边界 X 坐标
     * @param contentRight 内容区右边界 X 坐标
     */
    private fun drawBatteryCurve(
        canvas: Canvas,
        cache: CachedCurveData,
        topPadding: Float,
        availableH: Float,
        contentLeft: Float,
        contentRight: Float
    ) {
        val color = Color.parseColor("#B34CAF50")
        linePaint.color = color
        metricDotPaint.color = color
        metricLabelPaint.color = color

        canvas.drawPath(cache.path, linePaint)
        val batteryBottomBound = topPadding + availableH
        drawSmartMarkers(canvas, cache.markers, metricLabelPaint, metricLabelHaloPaint, metricDotPaint, contentLeft, contentRight, topPadding, batteryBottomBound)
    }

    /**
     * 预计算并构建温度平滑温升/降温曲线 Path 与标注点集合。
     *
     * @param cache 目标温度曲线缓存对象 [CachedCurveData]
     * @param contentLeft 内容区左边界 X 坐标
     * @param contentWidth 内容区宽度
     * @param topPadding 顶部安全间距
     * @param availableH 曲线有效可用高度
     * @param visibleStart 视窗起始时间戳
     * @param visibleEnd 视窗结束时间戳
     * @param rawSamples 原始采样点数据列表
     */
    private fun buildTemperatureCurveCache(
        cache: CachedCurveData,
        contentLeft: Float,
        contentWidth: Float,
        topPadding: Float,
        availableH: Float,
        visibleStart: Long,
        visibleEnd: Long,
        rawSamples: List<BatterySample>
    ) {
        cache.path.reset()
        cache.markers.clear()
        if (rawSamples.isEmpty()) return
        val downsampled = ChartDownsampler.downsample(rawSamples, targetMaxPoints = 300)
        if (downsampled.isEmpty()) return

        val contentRight = contentLeft + contentWidth
        val firstSample = downsampled.first()
        val firstNorm = (((firstSample.temperatureC - 15.0) / 30.0).coerceIn(0.0, 1.0) * 0.40 + 0.45).toFloat()
        val firstY = topPadding + (1f - firstNorm) * availableH

        cache.path.moveTo(contentLeft, firstY)
        cache.markers.add(CurveMarker(contentLeft, firstY, String.format(Locale.getDefault(), "%.1f℃", firstSample.temperatureC), isPriority = false))

        var lastX = contentLeft
        var lastY = firstY
        var lastTempInt = (firstSample.temperatureC * 2).toInt()

        for (s in downsampled) {
            val x = (contentLeft + TimelineScaleCalculator.timeToX(s.timestamp, visibleStart, visibleEnd, contentWidth)).coerceIn(contentLeft, contentRight)
            val norm = (((s.temperatureC - 15.0) / 30.0).coerceIn(0.0, 1.0) * 0.40 + 0.45).toFloat()
            val y = topPadding + (1f - norm) * availableH

            if (x > lastX) {
                val cX = (lastX + x) / 2f
                cache.path.cubicTo(cX, lastY, cX, y, x, y)
                val tempInt = (s.temperatureC * 2).toInt()
                if (tempInt != lastTempInt && cache.markers.size < 6) {
                    cache.markers.add(CurveMarker(x, y, String.format(Locale.getDefault(), "%.1f℃", s.temperatureC), isPriority = false))
                }
                lastX = x
                lastY = y
                lastTempInt = tempInt
            }
        }

        if (lastX < contentRight) {
            val cX = (lastX + contentRight) / 2f
            cache.path.cubicTo(cX, lastY, cX, lastY, contentRight, lastY)
        }
    }

    /**
     * 绘制温度平滑温升/降温曲线与数值标签（直接复用预计算 Path 与 Markers）。
     *
     * @param canvas 绘制画布 [Canvas]
     * @param cache 温度曲线缓存对象 [CachedCurveData]
     * @param topPadding 顶部安全间距
     * @param availableH 曲线有效可用高度
     * @param contentLeft 内容区左边界 X 坐标
     * @param contentRight 内容区右边界 X 坐标
     */
    private fun drawTemperatureCurve(
        canvas: Canvas,
        cache: CachedCurveData,
        topPadding: Float,
        availableH: Float,
        contentLeft: Float,
        contentRight: Float
    ) {
        val color = Color.parseColor("#B3FF7043")
        linePaint.color = color
        metricDotPaint.color = color
        metricLabelPaint.color = color

        canvas.drawPath(cache.path, linePaint)
        val tempBottomBound = topPadding + availableH
        drawSmartMarkers(canvas, cache.markers, metricLabelPaint, metricLabelHaloPaint, metricDotPaint, contentLeft, contentRight, topPadding, tempBottomBound)
    }

    /**
     * 预计算并构建电压平滑变化曲线 Path 与标注点集合。
     *
     * @param cache 目标电压曲线缓存对象 [CachedCurveData]
     * @param contentLeft 内容区左边界 X 坐标
     * @param contentWidth 内容区宽度
     * @param topPadding 顶部安全间距
     * @param availableH 曲线有效可用高度
     * @param visibleStart 视窗起始时间戳
     * @param visibleEnd 视窗结束时间戳
     * @param rawSamples 原始采样点数据列表
     */
    private fun buildVoltageCurveCache(
        cache: CachedCurveData,
        contentLeft: Float,
        contentWidth: Float,
        topPadding: Float,
        availableH: Float,
        visibleStart: Long,
        visibleEnd: Long,
        rawSamples: List<BatterySample>
    ) {
        cache.path.reset()
        cache.markers.clear()
        if (rawSamples.isEmpty()) return
        val downsampled = ChartDownsampler.downsample(rawSamples, targetMaxPoints = 300)
        if (downsampled.isEmpty()) return

        val contentRight = contentLeft + contentWidth
        val firstSample = downsampled.first()
        val firstVoltV = firstSample.voltageMv / 1000f
        val firstNorm = (((firstVoltV - 3.4f) / 1.0f).coerceIn(0f, 1f) * 0.35f + 0.35f)
        val firstY = topPadding + (1f - firstNorm) * availableH

        cache.path.moveTo(contentLeft, firstY)
        cache.markers.add(CurveMarker(contentLeft, firstY, String.format(Locale.getDefault(), "%.3f V", firstVoltV), isPriority = false))

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
                cache.path.cubicTo(cX, lastY, cX, y, x, y)
                if (abs(voltV - lastVolt) >= 0.05f && cache.markers.size < 6) {
                    cache.markers.add(CurveMarker(x, y, String.format(Locale.getDefault(), "%.3f V", voltV), isPriority = false))
                }
                lastX = x
                lastY = y
                lastVolt = voltV
            }
        }

        if (lastX < contentRight) {
            val cX = (lastX + contentRight) / 2f
            cache.path.cubicTo(cX, lastY, cX, lastY, contentRight, lastY)
        }
    }

    /**
     * 绘制电压平滑变化曲线与数值标签（直接复用预计算 Path 与 Markers）。
     *
     * @param canvas 绘制画布 [Canvas]
     * @param cache 电压曲线缓存对象 [CachedCurveData]
     * @param topPadding 顶部安全间距
     * @param availableH 曲线有效可用高度
     * @param contentLeft 内容区左边界 X 坐标
     * @param contentRight 内容区右边界 X 坐标
     */
    private fun drawVoltageCurve(
        canvas: Canvas,
        cache: CachedCurveData,
        topPadding: Float,
        availableH: Float,
        contentLeft: Float,
        contentRight: Float
    ) {
        val color = Color.parseColor("#B3FFCA28")
        linePaint.color = color
        metricDotPaint.color = color
        metricLabelPaint.color = color

        canvas.drawPath(cache.path, linePaint)
        val voltBottomBound = topPadding + availableH
        drawSmartMarkers(canvas, cache.markers, metricLabelPaint, metricLabelHaloPaint, metricDotPaint, contentLeft, contentRight, topPadding, voltBottomBound)
    }

    /**
     * 计算指定排版方位下的文本外接矩形区域。
     *
     * @param marker 待绘制的数值标注节点 [CurveMarker]
     * @param textWidth 文本测量物理宽度（像素）
     * @param textHeight 文本测量物理高度（像素）
     * @param orientation 排版目标方位：0 表示左侧（Left），1 表示上方（Top），2 表示右侧（Right）
     * @param contentLeft 图表内容区域左边界 X 坐标（像素）
     * @param contentRight 图表内容区域右边界 X 坐标（像素）
     * @param topBound 图表内容区域上边界 Y 坐标（像素）
     * @param bottomBound 图表内容区域下边界 Y 坐标（像素）
     * @return 对应排版方位下的文本外接矩形 [RectF]
     */
    private fun calculateMarkerTextRect(
        marker: CurveMarker,
        textWidth: Float,
        textHeight: Float,
        orientation: Int,
        contentLeft: Float,
        contentRight: Float,
        topBound: Float,
        bottomBound: Float
    ): RectF {
        val dotRadius = dp2_5
        val spacing = dp3
        return when (orientation) {
            0 -> { // 左侧 (Left)：优先显示在点左侧偏上
                val right = marker.x - dotRadius - spacing
                val left = right - textWidth
                val top = (marker.y - textHeight / 2f - dp1).coerceIn(topBound, bottomBound - textHeight)
                RectF(left, top, right, top + textHeight)
            }
            1 -> { // 上方 (Top)：居中显示在点上方
                val bottom = marker.y - dotRadius - spacing
                val top = bottom - textHeight
                val left = (marker.x - textWidth / 2f).coerceIn(contentLeft, contentRight - textWidth)
                RectF(left, top, left + textWidth, bottom)
            }
            else -> { // 右侧 (Right)：显示在点右侧偏上
                val left = marker.x + dotRadius + spacing
                val right = (left + textWidth).coerceAtMost(contentRight)
                val correctedLeft = (right - textWidth).coerceAtLeast(contentLeft)
                val top = (marker.y - textHeight / 2f - dp1).coerceIn(topBound, bottomBound - textHeight)
                RectF(correctedLeft, top, correctedLeft + textWidth, top + textHeight)
            }
        }
    }

    /**
     * 智能探测候选节点的最佳排版方位。
     * 按照“左侧 -> 上方 -> 右侧”优先级进行智能试探：若左侧显示不下则探测上方，若上方显示不下（如峰值顶格）则探测右侧。
     *
     * @param marker 待绘制的数值标注节点 [CurveMarker]
     * @param textWidth 文本物理宽度（像素）
     * @param textHeight 文本物理高度（像素）
     * @param contentLeft 图表内容区域左边界 X 坐标（像素）
     * @param contentRight 图表内容区域右边界 X 坐标（像素）
     * @param topBound 图表内容区域上边界 Y 坐标（像素）
     * @param bottomBound 图表内容区域下边界 Y 坐标（像素）
     * @param occupiedRects 已被其他标签占用的带安全间距的屏幕矩形列表
     * @param checkCollision 是否强制要求不与已占用矩形相交碰撞
     * @return 智能计算出的最佳排版矩形 [RectF]，若所有方位均发生遮挡且强制检查碰撞则返回 null
     */
    private fun determineBestMarkerRect(
        marker: CurveMarker,
        textWidth: Float,
        textHeight: Float,
        contentLeft: Float,
        contentRight: Float,
        topBound: Float,
        bottomBound: Float,
        occupiedRects: List<RectF>,
        checkCollision: Boolean
    ): RectF? {
        val orientations = listOf(0, 1, 2)
        for (ori in orientations) {
            val rect = calculateMarkerTextRect(marker, textWidth, textHeight, ori, contentLeft, contentRight, topBound, bottomBound)
            // 1. 视窗边界检查：确保文字完整落在可视区域内，绝不发生边缘截断
            val inBounds = when (ori) {
                0 -> rect.left >= contentLeft && rect.top >= topBound && rect.bottom <= bottomBound
                1 -> rect.top >= topBound && rect.bottom <= bottomBound && rect.left >= contentLeft && rect.right <= contentRight
                else -> rect.right <= contentRight && rect.top >= topBound && rect.bottom <= bottomBound
            }
            if (!inBounds) continue

            // 2. 防视觉叠压碰撞检查
            if (checkCollision) {
                val collision = occupiedRects.any { occupied ->
                    RectF.intersects(rect, occupied)
                }
                if (collision) continue
            }

            return rect
        }
        return null
    }

    /**
     * 智能计算并绘制曲线关键节点圆点与小数字标签。
     * 针对曲线最峰（最高点）、最谷（最低点）赋予绝对优先权确保 100% 呈现，
     * 并针对每个节点智能判断放置方位：优先居左，若左侧空间不足则智能切换至上方或右侧，彻底杜绝边界截断与标签重叠。
     *
     * @param canvas 绘制目标画布 [Canvas]
     * @param markers 候选标注节点列表 [CurveMarker]
     * @param paint 文本主色填充画笔 [Paint]
     * @param haloPaint 文本微暗描边光晕画笔 [Paint]
     * @param dotPaint 节点小圆点画笔 [Paint]
     * @param contentLeft 内容区左边缘 X 坐标
     * @param contentRight 内容区右边缘 X 坐标
     * @param topBound 内容区上边缘 Y 坐标
     * @param bottomBound 内容区下边缘 Y 坐标
     */
    private fun drawSmartMarkers(
        canvas: Canvas,
        markers: List<CurveMarker>,
        paint: Paint,
        haloPaint: Paint,
        dotPaint: Paint,
        contentLeft: Float,
        contentRight: Float,
        topBound: Float,
        bottomBound: Float
    ) {
        if (markers.isEmpty()) return

        paint.textAlign = Paint.Align.LEFT
        haloPaint.textAlign = Paint.Align.LEFT

        val fontMetrics = paint.fontMetrics
        val textHeight = fontMetrics.descent - fontMetrics.ascent
        val dotRadius = dp2_5
        val occupiedRects = mutableListOf<RectF>()

        // 1. 第一阶段：最峰（Max）与最谷（Min）绝对高优先级绘制（绝不被过滤丢弃）
        val priorityMarkers = markers.filter { it.isPriority }
        for (m in priorityMarkers) {
            val textWidth = paint.measureText(m.text)
            var bestRect = determineBestMarkerRect(m, textWidth, textHeight, contentLeft, contentRight, topBound, bottomBound, occupiedRects, checkCollision = true)
            if (bestRect == null) {
                bestRect = determineBestMarkerRect(m, textWidth, textHeight, contentLeft, contentRight, topBound, bottomBound, occupiedRects, checkCollision = false)
                    ?: calculateMarkerTextRect(m, textWidth, textHeight, 1, contentLeft, contentRight, topBound, bottomBound)
            }

            // 绘制圆点
            canvas.drawCircle(m.x, m.y, dotRadius, dotPaint)

            // 双层清晰绘制：微暗描边光晕 + 主题前景色
            val baseline = bestRect.top - fontMetrics.ascent
            canvas.drawText(m.text, bestRect.left, baseline, haloPaint)
            canvas.drawText(m.text, bestRect.left, baseline, paint)

            // 占位矩形附加安全缓冲呼吸空间（左右 4dp，上下 2dp）
            occupiedRects.add(RectF(bestRect.left - dp4, bestRect.top - dp2, bestRect.right + dp4, bestRect.bottom + dp2))
        }

        // 2. 第二阶段：次要参考节点在无碰撞冲突的前提下补充绘制
        val secondaryMarkers = markers.filter { !it.isPriority }
        for (m in secondaryMarkers) {
            val textWidth = paint.measureText(m.text)
            val bestRect = determineBestMarkerRect(m, textWidth, textHeight, contentLeft, contentRight, topBound, bottomBound, occupiedRects, checkCollision = true)
            if (bestRect != null) {
                canvas.drawCircle(m.x, m.y, dotRadius, dotPaint)

                val baseline = bestRect.top - fontMetrics.ascent
                canvas.drawText(m.text, bestRect.left, baseline, haloPaint)
                canvas.drawText(m.text, bestRect.left, baseline, paint)

                occupiedRects.add(RectF(bestRect.left - dp4, bestRect.top - dp2, bestRect.right + dp4, bestRect.bottom + dp2))
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

        // 1. 底层先绘制完整圆角底条（默认全铺息屏红）
        tempRectF.set(contentLeft, top, contentRight, bottom)
        canvas.drawRoundRect(tempRectF, dp1_5, dp1_5, screenOffBarPaint)

        // 2. 精确绘制每个亮屏分段（亮屏绿色段覆盖在息屏红色底条之上，彻底废除 clipPath 恢复 GPU 硬件加速批处理）
        for (event in mergedScreens) {
            if (!event.isScreenOn) continue
            if (event.endTime < visibleStart || event.startTime > visibleEnd) continue

            val left = (contentLeft + TimelineScaleCalculator.timeToX(event.startTime, visibleStart, visibleEnd, contentWidth)).coerceIn(contentLeft, contentRight)
            val right = (contentLeft + TimelineScaleCalculator.timeToX(event.endTime, visibleStart, visibleEnd, contentWidth)).coerceIn(contentLeft, contentRight)
            if (right > left) {
                tempRectF.set(left, top, right, bottom)
                val isAtLeft = left <= contentLeft + dp0_5
                val isAtRight = right >= contentRight - dp0_5
                if (isAtLeft && isAtRight) {
                    canvas.drawRoundRect(tempRectF, dp1_5, dp1_5, screenOnBarPaint)
                } else if (isAtLeft) {
                    tempPath.reset()
                    tempPath.addRoundRect(tempRectF, floatArrayOf(dp1_5, dp1_5, 0f, 0f, 0f, 0f, dp1_5, dp1_5), Path.Direction.CW)
                    canvas.drawPath(tempPath, screenOnBarPaint)
                } else if (isAtRight) {
                    tempPath.reset()
                    tempPath.addRoundRect(tempRectF, floatArrayOf(0f, 0f, dp1_5, dp1_5, dp1_5, dp1_5, 0f, 0f), Path.Direction.CW)
                    canvas.drawPath(tempPath, screenOnBarPaint)
                } else {
                    canvas.drawRect(tempRectF, screenOnBarPaint)
                }
            }
        }
    }

    /**
     * 绘制手势长按或触控滑动时的垂直虚线游标，以及顶部单行读数指示看板（与充电趋势图触摸看板样式保持一致）。
     * 单行紧凑展示当前时刻指标，中间以 " | " 分隔，去除指标名称标签。
     * 格式形如：10:10:02 :  60% | -1.35W | 38.5℃ | 3.941V | 电池统计
     *
     * @param canvas 绘制画布
     * @param contentLeft 图表内容左边界
     * @param contentRight 图表内容右边界
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

        // 1. 查询当前游标时刻对应的数据
        val curTs = TimelineScaleCalculator.xToTime(clampedX, visibleStart, visibleEnd, contentWidth, contentLeft)
        val curSample = timelineState.batterySamples.minByOrNull { abs(it.timestamp - curTs) }
        val curApp = timelineState.appEvents.find { it.startTime <= curTs && it.endTime >= curTs }

        val timeStr = timeFormatterTooltip.format(Date(curTs))
        val levelStr = curSample?.let { "${it.batteryLevel}%" }
        val powerStr = curSample?.let {
            val pWatts = it.getPowerWatts()
            val signedPower = if (pWatts > 0) -pWatts else pWatts
            String.format(Locale.getDefault(), "%.2fW", signedPower)
        }
        val tempStr = curSample?.let { String.format(Locale.getDefault(), "%.1f℃", it.temperatureC) }
        val voltStr = curSample?.let { String.format(Locale.getDefault(), "%.3fV", it.getVoltageVolts()) }
        val appStr = curApp?.appName?.takeIf { it.isNotBlank() }

        // 2. 将数据合并为单行展示，中间以 " | " 间隔，去除指标名称
        val metricsList = listOfNotNull(levelStr, powerStr, tempStr, voltStr, appStr)
        val fullText = if (metricsList.isNotEmpty()) {
            "$timeStr :  ${metricsList.joinToString(" | ")}"
        } else {
            timeStr
        }

        // 3. 触摸显示的 view 修改为充电趋势图触摸显示 view 样式：顶部单行轻微圆角背景与描边卡片
        val headerTop = dp2
        val headerBottom = dp24
        tooltipRect.set(contentLeft, headerTop, contentRight, headerBottom)
        canvas.drawRoundRect(tooltipRect, dp4, dp4, tooltipBgPaint)
        canvas.drawRoundRect(tooltipRect, dp4, dp4, tooltipBorderPaint)

        // 单行文字垂直居中排布
        val textY = headerTop + (headerBottom - headerTop) / 2f - (tooltipTextPaint.descent() + tooltipTextPaint.ascent()) / 2f
        canvas.drawText(fullText, contentLeft + dp8, textY, tooltipTextPaint)

        // 4. 垂直虚线游标从顶部悬浮卡片下方引出延伸至图表底部
        canvas.drawLine(clampedX, headerBottom, clampedX, mainHeight + dp6, cursorPaint)
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
        invalidateCurveCache()
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
        invalidateCurveCache()
        recalculateLayout()
        invalidate()
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
