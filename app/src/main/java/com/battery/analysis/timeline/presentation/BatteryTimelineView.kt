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
    private val dp35 = dpToPx(35f)

    private val sp9_5 = spToPx(9.5f)
    private val sp10_5 = spToPx(10.5f)

    // 画笔体系
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp2
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
            canvasWidth = w,
            baseBottomY = baseBottomY,
            slotSizePx = dp15,
            slotGapPx = dp2_5,
            rowGapPx = dp2_5,
            maxRows = 4
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
     * 核心 Canvas 绘制流程：底部向上锚定，时间轴线固定在功耗标签正上方。
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

        // 底部向上严格锚定布局：
        // 1. 时间文字位于最底部 (h - dp4)
        // 2. 时间刻度线位于 (h - dp20)
        // 3. 屏幕状态实线位于 (h - dp26 到 h - dp22.5) 紧贴刻度上方，横贯全图
        // 4. 主图表与 App 图标区位于 (dp8 到 h - dp30)
        val timeTextY = h - dp4
        val timeTickTop = h - dp18
        val screenBarBottom = timeTickTop - dp2
        val screenBarTop = screenBarBottom - dp3_5
        val mainChartHeight = screenBarTop - dp35

        // 确保 App 图标排布已就绪
        if (cachedSlotItems.isEmpty() && timelineState.appEvents.isNotEmpty()) {
            recalculateLayout()
        }

        // 1. 绘制垂直时间网格虚线与底部时间刻度文字
        drawTimeGridAndTicks(canvas, w, mainChartHeight, screenBarBottom, timeTextY, visibleStart, visibleEnd)

        // 2. 多选/反选模式：根据选中的指标集合依次绘制各曲线（支持叠加展示）
        val metrics = timelineState.selectedMetrics

        if (metrics.contains(TimelineMetric.POWER)) {
            drawMetricCurve(canvas, w, mainChartHeight, visibleStart, visibleEnd, TimelineMetric.POWER)
        }
        if (metrics.contains(TimelineMetric.BATTERY)) {
            drawMetricCurve(canvas, w, mainChartHeight, visibleStart, visibleEnd, TimelineMetric.BATTERY)
        }
        if (metrics.contains(TimelineMetric.TEMPERATURE)) {
            drawMetricCurve(canvas, w, mainChartHeight, visibleStart, visibleEnd, TimelineMetric.TEMPERATURE)
        }
        if (metrics.contains(TimelineMetric.VOLTAGE)) {
            drawMetricCurve(canvas, w, mainChartHeight, visibleStart, visibleEnd, TimelineMetric.VOLTAGE)
        }

        // 3. 绘制 App 活动分槽平铺徽章（从下往上纵向堆叠，Row 0 紧贴绿色状态条上方）
        if (metrics.contains(TimelineMetric.APP)) {
            drawAppEventsLayer(canvas)
        }

        // 4. 绘制横贯全宽的固定底图时间轴屏幕状态实线条（亮屏绿 / 息屏暗灰）
        drawScreenStateBar(canvas, w, screenBarTop, screenBarBottom, visibleStart, visibleEnd)

        // 5. 若长按处于活跃状态，绘制十字游标与悬浮气泡
        if (isCursorActive) {
            drawCursorAndTooltip(canvas, w, screenBarTop, visibleStart, visibleEnd)
        }
    }

    /**
     * 绘制时间轴网格虚线与底部时间刻度文字。
     */
    private fun drawTimeGridAndTicks(
        canvas: Canvas,
        w: Float,
        mainHeight: Float,
        tickTop: Float,
        textY: Float,
        visibleStart: Long,
        visibleEnd: Long
    ) {
        val ticks = TimelineScaleCalculator.calculateTicks(visibleStart, visibleEnd)
        for (tick in ticks) {
            val x = tick.xRatio * w
            // 垂直虚线网格
            canvas.drawLine(x, dp4, x, mainHeight, gridPaint)
            // 刻度小短线
            canvas.drawLine(x, tickTop, x, tickTop + dp3, gridPaint)
            // 时间文本居中绘制
            val textWidth = textPaint.measureText(tick.label)
            val textX = (x - textWidth / 2f).coerceIn(dp4, w - textWidth - dp4)
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
     * 绘制物理指标曲线（功耗/电量/温度/电压）与渐变阴影填充。
     */
    private fun drawMetricCurve(
        canvas: Canvas,
        w: Float,
        mainHeight: Float,
        visibleStart: Long,
        visibleEnd: Long,
        metric: TimelineMetric
    ) {
        val rawSamples = timelineState.batterySamples.filter {
            it.timestamp in visibleStart..visibleEnd
        }
        if (rawSamples.isEmpty()) return

        // 降采样保真
        val downsampled = ChartDownsampler.downsample(rawSamples, targetMaxPoints = 1200)
        if (downsampled.isEmpty()) return

        // 计算指标极大极小值与颜色主题
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE

        for (s in downsampled) {
            val value = when (metric) {
                TimelineMetric.POWER -> s.powerMw.toFloat()
                TimelineMetric.BATTERY -> s.batteryLevel.toFloat()
                TimelineMetric.TEMPERATURE -> s.temperatureC.toFloat()
                TimelineMetric.VOLTAGE -> s.voltageMv.toFloat()
                else -> 0f
            }
            if (value < minY) minY = value
            if (value > maxY) maxY = value
        }

        if (minY >= maxY) {
            maxY = minY + 1f
        }
        val ySpan = maxY - minY
        val topPadding = dp8
        val bottomPadding = dp6
        val availableH = mainHeight - topPadding - bottomPadding

        val (strokeColor, fillColorTop) = when (metric) {
            TimelineMetric.POWER -> Pair(Color.parseColor("#90CAF9"), Color.parseColor("#3390CAF9")) // 淡蓝功耗
            TimelineMetric.BATTERY -> Pair(Color.parseColor("#4CAF50"), Color.parseColor("#334CAF50")) // 鲜绿电量
            TimelineMetric.TEMPERATURE -> Pair(Color.parseColor("#FF8A65"), Color.parseColor("#33FF8A65")) // 珊瑚橙温度
            TimelineMetric.VOLTAGE -> Pair(Color.parseColor("#FFD54F"), Color.parseColor("#33FFD54F")) // 金黄电压
            else -> Pair(Color.WHITE, Color.TRANSPARENT)
        }

        linePaint.color = strokeColor
        fillPaint.shader = LinearGradient(
            0f, topPadding, 0f, mainHeight,
            fillColorTop, Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )

        curvePath.reset()
        fillPath.reset()

        var firstPoint = true
        var lastX = 0f

        for (s in downsampled) {
            val x = TimelineScaleCalculator.timeToX(s.timestamp, visibleStart, visibleEnd, w)
            val value = when (metric) {
                TimelineMetric.POWER -> s.powerMw.toFloat()
                TimelineMetric.BATTERY -> s.batteryLevel.toFloat()
                TimelineMetric.TEMPERATURE -> s.temperatureC.toFloat()
                TimelineMetric.VOLTAGE -> s.voltageMv.toFloat()
                else -> 0f
            }
            val y = topPadding + (1f - (value - minY) / ySpan) * availableH

            if (firstPoint) {
                curvePath.moveTo(x, y)
                fillPath.moveTo(x, mainHeight)
                fillPath.lineTo(x, y)
                firstPoint = false
            } else {
                curvePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
            lastX = x
        }

        fillPath.lineTo(lastX, mainHeight)
        fillPath.close()

        // 绘制阴影填充与折线
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(curvePath, linePaint)
    }

    /**
     * 绘制横贯全宽的固定时间轴屏幕状态条（亮屏绿色，息屏暗灰色）。
     */
    private fun drawScreenStateBar(
        canvas: Canvas,
        w: Float,
        top: Float,
        bottom: Float,
        visibleStart: Long,
        visibleEnd: Long
    ) {
        // 先铺设一条横贯全图的亮屏绿色底线，确保 100% 可见
        tempRectF.set(0f, top, w, bottom)
        canvas.drawRoundRect(tempRectF, dp1_5, dp1_5, screenOnBarPaint)

        val mergedScreens = TimelineEventMerger.mergeScreenEvents(timelineState.screenEvents)
        for (event in mergedScreens) {
            if (event.endTime < visibleStart || event.startTime > visibleEnd) continue
            // 仅对息屏区间进行覆盖绘制
            if (!event.isScreenOn) {
                val left = TimelineScaleCalculator.timeToX(event.startTime, visibleStart, visibleEnd, w).coerceIn(0f, w)
                val right = TimelineScaleCalculator.timeToX(event.endTime, visibleStart, visibleEnd, w).coerceIn(0f, w)
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
        w: Float,
        mainHeight: Float,
        visibleStart: Long,
        visibleEnd: Long
    ) {
        // 1. 垂直虚线
        canvas.drawLine(cursorX, 0f, cursorX, mainHeight + dp6, cursorPaint)

        // 2. 查询当前游标时刻对应的数据
        val curTs = TimelineScaleCalculator.xToTime(cursorX, visibleStart, visibleEnd, w)
        val curSample = timelineState.batterySamples.minByOrNull { abs(it.timestamp - curTs) }
        val curApp = timelineState.appEvents.find { it.startTime <= curTs && it.endTime >= curTs }

        val timeStr = timeFormatterTooltip.format(Date(curTs))
        val powerStr = curSample?.let { String.format(Locale.getDefault(), "功耗: %.2fW", it.getPowerWatts()) } ?: ""
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

        var boxLeft = cursorX - boxWidth / 2f
        if (boxLeft < dp4) boxLeft = dp4
        if (boxLeft + boxWidth > w - dp4) boxLeft = w - dp4 - boxWidth
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
