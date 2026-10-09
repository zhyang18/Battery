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
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration

import com.battery.analysis.timeline.domain.AppTimelineEvent
import com.battery.analysis.timeline.domain.BatterySample
import com.battery.analysis.timeline.domain.ScreenEvent
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
     * 顶部固定看板能量指标点击回调接口。
     */
    fun interface OnEnergyClickListener {
        /**
         * 当用户点击顶部固定看板上的能量指标文本时触发。
         *
         * @param view 触发点击事件的视图实例 [View]
         * @param touchX 相对视图的触摸 X 坐标
         * @param touchY 相对视图的触摸 Y 坐标
         */
        fun onEnergyClick(view: View, touchX: Float, touchY: Float)
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
    private var onEnergyClickListener: OnEnergyClickListener? = null
    private var onCursorInspectListener: OnCursorInspectListener? = null

    // 预计算 DP 标量
    private val dp0 = dpToPx(0f)
    private val dp0_5 = dpToPx(0.5f)
    private val dp1 = dpToPx(1f)
    private val dp1_5 = dpToPx(1.5f)
    private val dp2 = dpToPx(2f)
    private val dp2_5 = dpToPx(2.5f)
    private val dp3 = dpToPx(3f)
    private val dp3_5 = dpToPx(3.5f)
    private val dp4 = dpToPx(4f)
    private val dp5 = dpToPx(5f)
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
    private val dp26 = dpToPx(26f)
    private val dp28 = dpToPx(28f)
    private val dp30 = dpToPx(30f)
    private val dp32 = dpToPx(32f)
    private val dp35 = dpToPx(35f)
    private val dp36 = dpToPx(36f)
    private val dp40 = dpToPx(40f)
    private val dp50 = dpToPx(50f)

    private val sp5 = spToPx(5f)
    private val sp6 = spToPx(6f)
    private val sp7 = spToPx(7f)
    private val sp7_5 = spToPx(7.5f)
    private val sp8_5 = spToPx(8.5f)
    private val sp9_5 = spToPx(9.5f)
    private val sp10_5 = spToPx(10.5f)

    // 溢出 +N 徽章绘制画笔
    private val overflowBadgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#E6263238")
    }
    private val overflowBadgeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp0_5
        color = Color.parseColor("#455A64")
    }
    private val overflowTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp6
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    // 触控悬浮 Tooltip 气泡卡片绘制画笔（仅在 +N 溢出时展示应用图标）
    private val popupTooltipRect = RectF()
    private val popupTooltipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#12888888")
    }
    private val popupTooltipBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp1
        color = Color.parseColor("#1F888888")
    }

    // 画笔体系（趋势折线统一设置为 1.5dp）
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp1_5
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // 专用于功耗峰谷瞬时垂直落差线段的高性能画笔（采用 BUTT 端点，彻底消除 GPU 数万三角圆头扇面细分开销）
    private val powerVerticalLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp1_5
        strokeCap = Paint.Cap.BUTT
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
        color = Color.parseColor("#FFA4A4") // 息屏唤醒浅红（兼容引用）
    }

    private val screenOffAwakeBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFA4A4") // 息屏唤醒浅红（精确到秒，柔和浅红）
    }

    private val screenOffDeepSleepBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#B71C1C") // 息屏深度睡眠暗红（精确到秒，经典暗红）
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

    // 顶部固定读数看板分段绘制画笔体系
    private val headerTimePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp9_5
        color = Color.parseColor("#9E9E9E")
    }

    private val headerSeparatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp9_5
        color = Color.parseColor("#55888888")
    }

    private val headerMetricValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp9_5
        isFakeBoldText = true
    }

    // 各指标折线主题颜色（与充电趋势图规范完全统一）
    private val colorPower = Color.parseColor("#90CAF9")
    private val colorBattery = Color.parseColor("#3A7FF0")
    private val colorTemp = Color.parseColor("#FF5252")
    private val colorVoltage = Color.parseColor("#FFD54F")

    // 探查游标折线交点彩色外圈画笔
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // 探查游标折线交点白色内圈圆心画笔
    private val dotInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
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
    private val energyTouchRect = RectF()
    private val cachedSlotItems = mutableListOf<TimelineLayoutCalculator.LaidOutAppSlotItem>()

    /**
     * 预计算排布完成的标注绘制单元，零 onDraw 测量与堆内存分配开销。
     *
     * @property marker 原始标注数据
     * @property textX 文本左边缘 X 坐标
     * @property textY 文本绘制基线 Y 坐标
     */
    private class LaidOutMarker(
        val marker: CurveMarker,
        val textX: Float,
        val textY: Float
    )

    /**
     * 曲线与标注点的预计算绘制缓存数据实体。
     *
     * @property path 预先构建完成的三次贝塞尔平滑路径或连续平滑基线路径
     * @property verticalPath 专用于功耗波动等垂直瞬时峰谷落差线段的高性能绘制路径
     * @property markers 原始节点标注集合
     * @property laidOutMarkers 预先排布且完成碰撞避让的像素级绘制标注单元列表
     */
    private class CachedCurveData {
        val path = Path()
        val verticalPath = Path()
        val markers = mutableListOf<CurveMarker>()
        val laidOutMarkers = mutableListOf<LaidOutMarker>()
    }

    /**
     * 图表顶部看板预格式化静态数据缓存，避免滚动时每帧触发日期格式化与字符串拼接。
     *
     * @property timeStr 时间文本
     * @property levelStr 电量百分比文本
     * @property energyStr 能量文本
     * @property powerStr 功耗文本
     * @property tempStr 温度文本
     * @property voltStr 电压文本
     * @property curApp 前台应用实体
     */
    private class CachedHeaderData(
        val timeStr: String,
        val levelStr: String?,
        val energyStr: String?,
        val powerStr: String?,
        val tempStr: String?,
        val voltStr: String?,
        val curApp: AppTimelineEvent?
    )

    private val cachedPowerCurve = CachedCurveData()
    private val cachedBatteryCurve = CachedCurveData()
    private val cachedTempCurve = CachedCurveData()
    private val cachedVoltCurve = CachedCurveData()

    /** 标记曲线与图表绘制缓存是否有效，避免列表滚动时重复重绘与重复 LTTB 计算 */
    private var isCurveCacheValid = false
    private var cachedRawSamples = emptyList<BatterySample>()
    private var cachedMaxScaleW = 15.0
    private var cachedMinTemp = 15.0
    private var cachedMaxTemp = 45.0
    private var cachedMinVoltV = 3.4f
    private var cachedMaxVoltV = 4.4f

    /** 缓存的合并后屏幕状态事件序列，避免 onDraw 循环重复执行 mergeScreenEvents */
    private var cachedMergedScreenEvents = emptyList<ScreenEvent>()

    /** 缓存的时间刻度信息，避免 onDraw 循环中重复计算与分配对象 */
    private var cachedTicks = emptyList<TimelineScaleCalculator.TimeTick>()

    /** 缓存的默认看板数据 */
    private var cachedDefaultHeader: CachedHeaderData? = null

    /**
     * 将曲线与图表绘制缓存标记为失效，触发下一次绘制前的按需重新预计算。
     */
    private fun invalidateCurveCache() {
        isCurveCacheValid = false
        cachedDefaultHeader = null
    }

    // 滑动与滚动静默状态
    private var isParentScrolling = false
    private var pendingTimelineState: BatteryTimelineState? = null

    // 手势与交互状态（严格遵循“图表中的触摸事件只有在长按时触发”的交互规范）
    private var isCursorActive = false
    private var cursorX = 0f
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var isTouchMoved = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong().coerceAtLeast(400L)

    /**
     * 长按触发任务：用户在图表区域内按住不动达到长按门限后，激活垂直游标并触发轻微震动反馈。
     */
    private val longPressRunnable = Runnable {
        if (!isAttachedToWindow || isParentScrolling || isTouchMoved) return@Runnable
        isCursorActive = true
        parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        cursorX = touchDownX.coerceIn(0f, width.toFloat())
        notifyCursorMove(cursorX)
        invalidate()
    }

    /**
     * 取消尚未触发的长按检测定时器。
     */
    private fun cancelLongPressTimer() {
        removeCallbacks(longPressRunnable)
    }

    private val timeFormatterTooltip = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /**
     * 设置父级列表当前是否处于上、下滑动过程中。
     * 在上、下滑动期间彻底冻结图表的所有重绘、重载与计算，彻底消除滑动卡顿掉帧。
     *
     * @param scrolling 是否正在上下滑动
     */
    fun setScrolling(scrolling: Boolean) {
        if (isParentScrolling == scrolling) return
        isParentScrolling = scrolling
        if (scrolling) {
            cancelLongPressTimer()
            if (isCursorActive) {
                isCursorActive = false
                onCursorInspectListener?.onCursorDismiss()
                invalidate()
            }
        } else {
            // 滑动完全停止后，若在滑动期间积累了新的待更新状态，则在静止时恢复并执行必要更新
            pendingTimelineState?.let { pending ->
                pendingTimelineState = null
                setState(pending)
            }
        }
    }

    // 双指缩放手势检测器
    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            val focusX = detector.focusX
            applyZoom(scaleFactor, focusX)
            return true
        }
    })

    init {
        setWillNotDraw(false)
    }

    /**
     * 设置时间轴最新状态并触发重绘。
     * 具备严格的状态一致性校验与曲线预计算缓存保护：当数据实体、时间视窗与选中指标未改变且既有曲线缓存有效时，
     * 直接复用已就绪的 Path 与标注，绝不销毁缓存或重复重绘，彻底根除列表上下滑动经过图表时的掉帧卡顿与重新加载现象。
     *
     * @param state 最新的时间轴状态 [BatteryTimelineState]
     */
    fun setState(state: BatteryTimelineState) {
        val now = System.currentTimeMillis()
        val start = if (state.startTimestamp > 0L) state.startTimestamp else (now - 1800_000L)
        val end = if (state.endTimestamp > start) state.endTimestamp else (start + 1800_000L)

        val vStart = if (state.visibleStartTimestamp in 1 until end) state.visibleStartTimestamp else start
        val vEnd = if (state.visibleEndTimestamp > vStart) state.visibleEndTimestamp else end

        // 精准判断时序采样点数据是否实质一致（避免因外部集合浅拷贝引用不同而误判为数据变化）
        val samplesIdentical = this.timelineState.batterySamples === state.batterySamples || (
            this.timelineState.batterySamples.size == state.batterySamples.size &&
            (this.timelineState.batterySamples.isEmpty() || (
                this.timelineState.batterySamples.first().timestamp == state.batterySamples.first().timestamp &&
                this.timelineState.batterySamples.last().timestamp == state.batterySamples.last().timestamp &&
                this.timelineState.batterySamples.last().powerMw == state.batterySamples.last().powerMw
            ))
        )

        val appEventsIdentical = this.timelineState.appEvents === state.appEvents || (
            this.timelineState.appEvents.size == state.appEvents.size
        )

        val screenEventsIdentical = this.timelineState.screenEvents === state.screenEvents || (
            this.timelineState.screenEvents.size == state.screenEvents.size
        )

        // 核心性能保护：在 RecyclerView 垂直滚动中，若数据内容、时间视窗与选中指标完全未改变，直接复用既有预计算缓存
        val isIdentical = (this.timelineState === state || (
            this.timelineState.startTimestamp == start &&
            this.timelineState.endTimestamp == end &&
            this.timelineState.visibleStartTimestamp == vStart &&
            this.timelineState.visibleEndTimestamp == vEnd &&
            this.timelineState.selectedMetrics == state.selectedMetrics &&
            this.timelineState.selectedApp == state.selectedApp &&
            samplesIdentical &&
            appEventsIdentical &&
            screenEventsIdentical
        ))

        this.timelineState = state.copy(
            startTimestamp = start,
            endTimestamp = end,
            visibleStartTimestamp = vStart,
            visibleEndTimestamp = vEnd
        )

        // 关键修复：当数据与视窗未变且既有曲线缓存有效时，直接复用既有渲染缓存，绝对不重复加载！
        if (isIdentical && isCurveCacheValid) {
            val needLayoutApps = state.selectedMetrics.contains(TimelineMetric.APP) &&
                state.appEvents.isNotEmpty() &&
                cachedSlotItems.isEmpty()
            if (needLayoutApps) {
                recalculateLayout(canRequestLayout = false)
                invalidate()
            }
            return
        }

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
     * 设置顶部固定看板能量指标点击监听器。
     *
     * @param listener 能量指标点击监听器实例 [OnEnergyClickListener]
     */
    fun setOnEnergyClickListener(listener: OnEnergyClickListener?) {
        this.onEnergyClickListener = listener
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
        val defaultBaseH = dpToPx(270f).toInt()
        val maxRow = cachedSlotItems.maxOfOrNull { it.rowIndex } ?: -1
        val maxRowsCount = maxRow + 1

        // 仅当图标纵向叠加层数极多超出基础高度范围时，才动态扩充高度（纵向上下间距为 0）
        val iconStackHeight = (maxRowsCount * dp11).toInt()
        val requiredHeight = dpToPx(50f).toInt() + iconStackHeight
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

        val topLimitY = dp40
        val availableHeight = (baseBottomY - topLimitY).coerceAtLeast(dp11)
        val maxDisplayRows = (availableHeight / dp11).toInt().coerceAtLeast(1)

        val laidOut = TimelineLayoutCalculator.calculateSlotItems(
            events = timelineState.appEvents,
            visibleStartTs = visibleStart,
            visibleEndTs = visibleEnd,
            canvasWidth = contentWidth,
            baseBottomY = baseBottomY,
            slotSizePx = dp11,
            slotGapPx = 0f,
            rowGapPx = 0f,
            maxRows = maxDisplayRows,
            leftMarginPx = contentLeft,
            screenEvents = timelineState.screenEvents
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
        if ((w == oldw && h == oldh) || w <= 0 || h <= 0) return
        invalidateCurveCache()
        recalculateLayout()
    }

    /**
     * 视图从窗口脱附时的回调。
     * 在 RecyclerView 垂直滚动时 ViewHolder 脱附视野属于高频正常生命周期，
     * 严禁在此处清空图标 Bitmap、时间槽排布及曲线 Path 预计算缓存，确保滑回视野时直接 0 耗时复用既有绘制缓存。
     * 脱附时立即移除未执行的长按任务并重置游标状态。
     */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelLongPressTimer()
        if (isCursorActive) {
            isCursorActive = false
            onCursorInspectListener?.onCursorDismiss()
        }
    }

    /**
     * 视图重新附着至窗口时的回调。
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 保持并复用已有缓存，绝不主动清空
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

        // 曲线区域顶部预留顶部常驻看板高度，避免与折线重合
        val mainChartHeight = screenBarTop - dp6
        val topPadding = dp28
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
        drawTimeGridAndTicks(canvas, contentLeft, contentWidth, mainChartHeight, screenBarBottom, timeTextY)

        // 3. 绘制 App 活动分槽平铺徽章（置于底层，使后续所有曲线覆盖在应用小图标之上）
        val metrics = timelineState.selectedMetrics
        if (metrics.contains(TimelineMetric.APP)) {
            drawAppEventsLayer(canvas)
        }

        // 4. 多选/反选模式：以平滑三次贝塞尔曲线绘制各指标曲线（直接复用预计算 Path 与 Markers）
        if (metrics.contains(TimelineMetric.POWER)) {
            drawPowerCurve(canvas, cachedPowerCurve)
        }
        if (metrics.contains(TimelineMetric.BATTERY)) {
            drawBatteryCurve(canvas, cachedBatteryCurve)
        }
        if (metrics.contains(TimelineMetric.TEMPERATURE)) {
            drawTemperatureCurve(canvas, cachedTempCurve)
        }
        if (metrics.contains(TimelineMetric.VOLTAGE)) {
            drawVoltageCurve(canvas, cachedVoltCurve)
        }

        // 5. 绘制横贯全宽的固定底图时间轴屏幕状态实线条（亮屏绿 / 息屏红）
        drawScreenStateBar(canvas, contentLeft, contentRight, screenBarTop, screenBarBottom, visibleStart, visibleEnd)

        // 6. 在图表上方固定常驻绘制读数指示看板（默认显示当前刷新时间，触摸时实时更新为游标时刻数据）
        drawFixedTopHeaderAndCursor(canvas, contentLeft, contentRight, contentWidth, screenBarTop, visibleStart, visibleEnd, topPadding, availableH)
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
     */
    private fun drawTimeGridAndTicks(
        canvas: Canvas,
        contentLeft: Float,
        contentWidth: Float,
        mainHeight: Float,
        tickTop: Float,
        textY: Float
    ) {
        val ticks = cachedTicks
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
            val minTextX = halfWidth + dp2
            val maxTextX = maxOf(minTextX, w - halfWidth - dp2)
            val textX = x.coerceIn(minTextX, maxTextX)
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
            if (item.top < dp30) continue

            if (item.overflowCount > 0) {
                // 绘制顶层微型 +N 溢出折叠角标
                drawOverflowBadge(canvas, item)
            } else {
                val event = item.event
                tempRectF.set(item.left, item.top, item.right, item.bottom)

                // 1. 若当前应用被选中，绘制高亮聚焦外框
                if (timelineState.selectedApp?.packageName == event.packageName) {
                    canvas.drawRoundRect(tempRectF, cornerRadius, cornerRadius, iconBadgeSelectedStrokePaint)
                }

                // 2. 直接绘制 App 图标
                val bmp = DrawableBitmapCache.getOrLoadBitmap(context, event.packageName, iconRenderSize, event.icon)
                if (bmp != null && !bmp.isRecycled) {
                    tempSrcRect.set(0, 0, bmp.width, bmp.height)
                    canvas.drawBitmap(bmp, tempSrcRect, tempRectF, bitmapPaint)
                }
            }
        }
    }

    /**
     * 绘制时间槽顶层应用溢出指示徽章（+N 方块，提示用户此处存在更多活跃应用）。
     *
     * @param canvas 画布
     * @param item 对应的时间槽排布单元实体
     */
    private fun drawOverflowBadge(canvas: Canvas, item: TimelineLayoutCalculator.LaidOutAppSlotItem) {
        tempDstRectF.set(item.left, item.top, item.right, item.bottom)
        canvas.drawRoundRect(tempDstRectF, dp2, dp2, overflowBadgeBgPaint)
        canvas.drawRoundRect(tempDstRectF, dp2, dp2, overflowBadgeStrokePaint)

        val text = "+${item.overflowCount}"
        val textY = item.centerY - (overflowTextPaint.descent() + overflowTextPaint.ascent()) / 2f
        canvas.drawText(text, item.centerX, textY, overflowTextPaint)
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
     * 在按时间戳升序排列的采样点列表中，通过二分查找定位时间戳大于等于指定时间的首个元素下标。
     *
     * @param list 采样点列表 [List<BatterySample>]
     * @param targetTs 目标起始时间戳（毫秒）
     * @return 符合条件的首个元素下标（若均小于 targetTs 则返回 list.size）
     */
    private fun findFirstIndexAfter(list: List<BatterySample>, targetTs: Long): Int {
        var low = 0
        var high = list.size - 1
        var ans = list.size
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (list[mid].timestamp >= targetTs) {
                ans = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        return ans
    }

    /**
     * 在按时间戳升序排列的采样点列表中，通过二分查找定位时间戳小于等于指定时间的末个元素下标。
     *
     * @param list 采样点列表 [List<BatterySample>]
     * @param targetTs 目标结束时间戳（毫秒）
     * @return 符合条件的末个元素下标（若均大于 targetTs 则返回 -1）
     */
    private fun findLastIndexBefore(list: List<BatterySample>, targetTs: Long): Int {
        var low = 0
        var high = list.size - 1
        var ans = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (list[mid].timestamp <= targetTs) {
                ans = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return ans
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
        val allSamples = timelineState.batterySamples
        val rangeStartTs = visibleStart - 5000L
        val rangeEndTs = visibleEnd + 5000L

        // 利用时序单调性通过二分查找快速截取视窗切片，零全量过滤与内存分配开销
        val startIndex = findFirstIndexAfter(allSamples, rangeStartTs)
        val endIndex = findLastIndexBefore(allSamples, rangeEndTs)

        val rawSamples = if (startIndex in allSamples.indices && endIndex >= startIndex && endIndex < allSamples.size) {
            allSamples.subList(startIndex, endIndex + 1)
        } else {
            emptyList()
        }
        cachedRawSamples = rawSamples

        // 单次轻量循环同时提取功耗、温度、电压各维度的物理极值，杜绝多次遍历与临时对象产生
        val sampleSource = if (rawSamples.isNotEmpty()) rawSamples else allSamples
        var maxRawPowerW = 0.0
        var minT = Double.MAX_VALUE
        var maxT = -Double.MAX_VALUE
        var minV = Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        var hasValidTemp = false
        var hasValidVolt = false

        for (s in sampleSource) {
            val pW = abs(s.powerMw) / 1000.0
            if (pW > maxRawPowerW) maxRawPowerW = pW

            if (s.temperatureC > 0.0) {
                if (s.temperatureC < minT) minT = s.temperatureC
                if (s.temperatureC > maxT) maxT = s.temperatureC
                hasValidTemp = true
            }

            if (s.voltageMv > 500) {
                val v = s.voltageMv / 1000f
                if (v < minV) minV = v
                if (v > maxV) maxV = v
                hasValidVolt = true
            }
        }
        if (maxRawPowerW <= 0.0) maxRawPowerW = 15.0

        cachedMaxScaleW = when {
            maxRawPowerW <= 5.0 -> 6.0
            maxRawPowerW <= 10.0 -> 10.0
            maxRawPowerW <= 20.0 -> 20.0
            maxRawPowerW <= 30.0 -> 30.0
            maxRawPowerW <= 40.0 -> 40.0
            maxRawPowerW <= 60.0 -> 60.0
            else -> kotlin.math.ceil(maxRawPowerW / 10.0) * 10.0
        }

        // 动态自适应温度量程计算（基于底层真实硬件物理数据，杜绝硬编码与虚拟钳位截断）
        if (hasValidTemp) {
            val rangeT = maxT - minT
            if (rangeT < 1.0) {
                // 极差过小时居中平滑展开，上下预留 1.5℃ 视野，避免除以 0 导致曲线畸变
                cachedMinTemp = minT - 1.5
                cachedMaxTemp = maxT + 1.5
            } else {
                // 留出 10% 呼吸裕量
                val marginT = rangeT * 0.1
                cachedMinTemp = minT - marginT
                cachedMaxTemp = maxT + marginT
            }
        } else {
            cachedMinTemp = 15.0
            cachedMaxTemp = 45.0
        }

        // 动态自适应电压量程计算（基于底层真实硬件物理数据，兼容高压单电芯与多电芯串联）
        if (hasValidVolt) {
            val rangeV = maxV - minV
            if (rangeV < 0.05f) {
                // 极差过小时居中平滑展开，上下预留 0.05V 视野，避免除以 0
                cachedMinVoltV = minV - 0.05f
                cachedMaxVoltV = maxV + 0.05f
            } else {
                // 留出 8% 呼吸裕量
                val marginV = rangeV * 0.08f
                cachedMinVoltV = minV - marginV
                cachedMaxVoltV = maxV + marginV
            }
        } else {
            cachedMinVoltV = 3.4f
            cachedMaxVoltV = 4.4f
        }

        val bottomBound = topPadding + availableH
        val contentRight = contentLeft + contentWidth

        val sharedOccupiedRects = mutableListOf<RectF>()
        val metrics = timelineState.selectedMetrics
        if (metrics.contains(TimelineMetric.BATTERY)) {
            buildBatteryCurveCache(cachedBatteryCurve, contentLeft, contentWidth, topPadding, availableH, visibleStart, visibleEnd, rawSamples)
            layoutSmartMarkers(cachedBatteryCurve, metricLabelPaint, contentLeft, contentRight, topPadding, bottomBound, sharedOccupiedRects)
        }
        if (metrics.contains(TimelineMetric.POWER)) {
            buildPowerCurveCache(cachedPowerCurve, contentLeft, contentWidth, topPadding, availableH, visibleStart, visibleEnd, cachedMaxScaleW, rawSamples)
            layoutSmartMarkers(cachedPowerCurve, metricLabelPaint, contentLeft, contentRight, topPadding, bottomBound, sharedOccupiedRects)
        }
        if (metrics.contains(TimelineMetric.TEMPERATURE)) {
            buildTemperatureCurveCache(cachedTempCurve, contentLeft, contentWidth, topPadding, availableH, visibleStart, visibleEnd, rawSamples)
            layoutSmartMarkers(cachedTempCurve, metricLabelPaint, contentLeft, contentRight, topPadding, bottomBound, sharedOccupiedRects)
        }
        if (metrics.contains(TimelineMetric.VOLTAGE)) {
            buildVoltageCurveCache(cachedVoltCurve, contentLeft, contentWidth, topPadding, availableH, visibleStart, visibleEnd, rawSamples)
            layoutSmartMarkers(cachedVoltCurve, metricLabelPaint, contentLeft, contentRight, topPadding, bottomBound, sharedOccupiedRects)
        }

        cachedMergedScreenEvents = TimelineEventMerger.mergeScreenEvents(timelineState.screenEvents)
        cachedTicks = TimelineScaleCalculator.calculateTicks(visibleStart, visibleEnd)
        updateDefaultHeaderCache()
    }

    /**
     * 预先计算并缓存非游标状态下的顶部常驻看板展示文本与应用图标，彻底杜绝列表滚动时每帧触发日期格式化与对象检索。
     */
    private fun updateDefaultHeaderCache() {
        val lastSample = timelineState.batterySamples.lastOrNull()
        val latestTs = lastSample?.timestamp ?: timelineState.endTimestamp.takeIf { it > 0L } ?: System.currentTimeMillis()
        val timeStr = timeFormatterTooltip.format(Date(latestTs))
        val levelStr = lastSample?.let { "${it.batteryLevel}%" }
        val energyStr = lastSample?.let { s ->
            s.energyWh?.let { String.format(Locale.getDefault(), "%.1fWh", it) }
                ?: timelineState.totalEnergyWh?.takeIf { it > 0f }?.let {
                    String.format(Locale.getDefault(), "%.1fWh", s.batteryLevel / 100f * it)
                }
        }
        val powerStr = lastSample?.let {
            val pWatts = it.getPowerWatts()
            val signedPower = if (pWatts > 0) -pWatts else pWatts
            String.format(Locale.getDefault(), "%.2fW", signedPower)
        }
        val tempStr = lastSample?.let { String.format(Locale.getDefault(), "%.1f℃", it.temperatureC) }
        val voltStr = lastSample?.let { String.format(Locale.getDefault(), "%.3fV", it.getVoltageVolts()) }
        val curApp = timelineState.appEvents.find { it.startTime <= latestTs && it.endTime >= latestTs } ?: timelineState.appEvents.lastOrNull()
        cachedDefaultHeader = CachedHeaderData(timeStr, levelStr, energyStr, powerStr, tempStr, voltStr, curApp)
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
    /**
     * 预计算并构建功耗波动时间轴高保真峰谷线段 Path 与关键极值标注点集合。
     * 彻底摒弃由于采样数据动态追加分桶导致的曲线漂移抖动缺陷；
     * 采用工业级按屏幕物理像素列（Pixel Column）分桶 Min-Max 峰谷线段聚合算法，
     * 保持每个像素点内物理真实的瞬时波峰与波谷极值用垂直线段展示，同时维持相邻像素点基线的连续平滑，
     * 彻底消除每次手动刷新时功耗线段随机变动的现象。
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
        cache.verticalPath.reset()
        cache.markers.clear()
        if (rawSamples.isEmpty() || contentWidth <= 0f) return

        val contentRight = contentLeft + contentWidth

        // 功耗纵向区间在顶部预留 20dp 专用安全呼吸空间，专门供波峰数值标签居中悬浮排布呈现
        val powerTopReserved = dp20
        val effectivePowerH = max(1f, availableH - powerTopReserved)

        fun calcPowerY(powerW: Float): Float {
            val ratio = (powerW / maxScaleW.toFloat()).coerceIn(0f, 1f)
            return topPadding + powerTopReserved + (1f - ratio) * effectivePowerH
        }

        // 1. 过滤当前视窗时间范围内的有效样本（前后适度延伸 5 秒以确保边缘闭合）
        val visibleSamples = rawSamples.filter { it.timestamp in (visibleStart - 5000L)..(visibleEnd + 5000L) }
        if (visibleSamples.isEmpty()) return

        // 2. 收集全局关键极值 Marker 节点（全周期绝对最大峰值、绝对最小谷值与显著独立大峰值）
        val maxSample = visibleSamples.maxByOrNull { abs(it.powerMw) }
        val minSample = visibleSamples.minByOrNull { abs(it.powerMw) }

        fun toMarker(s: BatterySample, isPriority: Boolean): CurveMarker {
            val x = (contentLeft + TimelineScaleCalculator.timeToX(s.timestamp, visibleStart, visibleEnd, contentWidth)).coerceIn(contentLeft, contentRight)
            val pW = (abs(s.powerMw) / 1000.0).toFloat().coerceIn(0f, maxScaleW.toFloat())
            val y = calcPowerY(pW)
            val label = formatPowerWatts(pW)
            return CurveMarker(x, y, label, isPriority)
        }

        if (maxSample != null) {
            cache.markers.add(toMarker(maxSample, isPriority = true))
        }
        if (minSample != null && minSample != maxSample) {
            cache.markers.add(toMarker(minSample, isPriority = true))
        }

        // 仅在存在显著独立大尖峰时，智能保留至多 1~2 个关键次级峰值（过滤平缓日常小起伏，避免杂乱数值干扰图表）
        if (maxSample != null) {
            val maxPW = (abs(maxSample.powerMw) / 1000.0).toFloat()
            val minSignificantW = maxOf(4.0f, maxPW * 0.4f)
            val timeSpan = (visibleEnd - visibleStart).coerceAtLeast(1L)
            val minTimeGap = (timeSpan * 0.12).toLong()

            val candidates = visibleSamples.filter { s ->
                val pW = (abs(s.powerMw) / 1000.0).toFloat()
                s !== maxSample && s !== minSample &&
                    pW >= minSignificantW &&
                    abs(s.timestamp - maxSample.timestamp) >= minTimeGap &&
                    (minSample == null || abs(s.timestamp - minSample.timestamp) >= minTimeGap)
            }.sortedByDescending { abs(it.powerMw) }

            val chosenSecondary = mutableListOf<BatterySample>()
            for (c in candidates) {
                if (chosenSecondary.size >= 2) break
                val tooClose = chosenSecondary.any { abs(it.timestamp - c.timestamp) < minTimeGap }
                if (!tooClose) {
                    chosenSecondary.add(c)
                }
            }

            for (s in chosenSecondary) {
                cache.markers.add(toMarker(s, isPriority = false))
            }
        }

        // 3. 屏幕横向物理像素列（Pixel Column）分桶极值聚合：
        // 每个像素点 x 固定对应屏幕的一个物理像素，完全锁定历史采样数据，彻底根除动态抽稀算法在刷新时造成的全局抖动
        val numPixels = contentWidth.toInt().coerceAtLeast(1)
        val minWattsArray = FloatArray(numPixels) { Float.MAX_VALUE }
        val maxWattsArray = FloatArray(numPixels) { -Float.MAX_VALUE }
        val hasSampleArray = BooleanArray(numPixels)

        for (s in visibleSamples) {
            val relX = TimelineScaleCalculator.timeToX(s.timestamp, visibleStart, visibleEnd, contentWidth)
            val px = relX.toInt().coerceIn(0, numPixels - 1)
            val pW = (abs(s.powerMw) / 1000.0).toFloat().coerceIn(0f, maxScaleW.toFloat())

            if (pW < minWattsArray[px]) minWattsArray[px] = pW
            if (pW > maxWattsArray[px]) maxWattsArray[px] = pW
            hasSampleArray[px] = true
        }

        // 4. 构建高保真像素峰谷线段与连续平滑基线 Path：
        // 彻底根除断层缺陷：基线首点起笔后全局连续贯通，绝无任何草率切断；
        // 瞬时峰谷落差线段使用直角 BUTT 端点绘制，彻底消除数万 GPU 圆头网格生成负担，兼顾高保真呈现与 120fps 极速渲染。
        var isFirstPoint = true

        for (px in 0 until numPixels) {
            if (!hasSampleArray[px]) continue

            val x = contentLeft + px.toFloat()
            val minW = minWattsArray[px]
            val maxW = maxWattsArray[px]

            val yPeak = calcPowerY(maxW)
            val yValley = calcPowerY(minW)

            // 1. 连续波谷基线构建：首点起笔后全线连续贯通连接，确保无论采样跨度多大，基线全局无任何断档
            if (isFirstPoint) {
                cache.path.moveTo(x, yValley)
                isFirstPoint = false
            } else {
                cache.path.lineTo(x, yValley)
            }

            // 2. 垂直峰谷落差线段：每个物理采样点均如实展现峰谷落差与微小存在感，彻底消除断层空洞
            val segmentHeight = yValley - yPeak
            if (segmentHeight >= dp0_5) {
                cache.verticalPath.moveTo(x, yValley)
                cache.verticalPath.lineTo(x, yPeak)
            } else {
                cache.verticalPath.moveTo(x, yValley)
                cache.verticalPath.lineTo(x, yValley - dp1)
            }
        }
    }

    /**
     * 绘制功耗波动平滑曲线与标注节点（直接复用预计算 Path 与预排布 Markers）。
     * 遵循经典 70% 透明度色彩规范（#B390CAF9）与直角 BUTT 高性能画笔，
     * 保持原生视觉质感的同时彻底消除 GPU 圆头扇面网格开销，确保列表滑动保持 120fps 极速渲染。
     *
     * @param canvas 绘制画布 [Canvas]
     * @param cache 功耗曲线缓存对象 [CachedCurveData]
     */
    private fun drawPowerCurve(
        canvas: Canvas,
        cache: CachedCurveData
    ) {
        val strokeColor = Color.parseColor("#B390CAF9")
        linePaint.color = strokeColor

        // 1. 绘制连续平滑基线（GPU 单一几何三角带，性能飞跃）
        canvas.drawPath(cache.path, linePaint)

        // 2. 绘制显著峰谷垂直落差线段（BUTT 端点，零额外圆头扇面开销）
        if (!cache.verticalPath.isEmpty) {
            powerVerticalLinePaint.color = strokeColor
            canvas.drawPath(cache.verticalPath, powerVerticalLinePaint)
        }

        metricDotPaint.color = strokeColor
        metricLabelPaint.color = strokeColor
        drawSmartMarkers(canvas, cache, metricLabelPaint, metricLabelHaloPaint, metricDotPaint)
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
        val targetPoints = (contentWidth * 0.6f).toInt().coerceIn(200, 600)
        val downsampled = ChartDownsampler.downsampleByMetric(rawSamples, targetPoints) { it.batteryLevel.toDouble() }
        if (downsampled.isEmpty()) return

        val contentRight = contentLeft + contentWidth
        val firstSample = downsampled.first()
        val firstNorm = (firstSample.batteryLevel / 100f).coerceIn(0f, 1f) * 0.45f + 0.50f
        val firstY = topPadding + (1f - firstNorm) * availableH

        cache.path.moveTo(contentLeft, firstY)
        cache.markers.add(CurveMarker(contentLeft, firstY, "${firstSample.batteryLevel}%", isPriority = true))

        var lastX = contentLeft
        var lastY = firstY
        var lastLevel = firstSample.batteryLevel

        for (s in downsampled) {
            val x = (contentLeft + TimelineScaleCalculator.timeToX(s.timestamp, visibleStart, visibleEnd, contentWidth)).coerceIn(contentLeft, contentRight)
            val norm = (s.batteryLevel / 100f).coerceIn(0f, 1f) * 0.45f + 0.50f
            val y = topPadding + (1f - norm) * availableH

            // 像素级防抖：横向位移大于 1.0px 或电量阶跃或到达末点才追加贝塞尔段
            if (x > lastX + 1.0f || s === downsampled.last() || s.batteryLevel != lastLevel) {
                val cX = (lastX + x) / 2f
                cache.path.cubicTo(cX, lastY, cX, y, x, y)
                // 仅对每隔 10% 的大整数点或关键拐点添加 Marker，控制总数避免碰撞检测卡顿
                if (s.batteryLevel != lastLevel && (s.batteryLevel % 10 == 0 || cache.markers.size < 5)) {
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

        // 末点高优先级 Marker
        val lastSample = downsampled.last()
        if (cache.markers.none { abs(it.x - contentRight) < dp10 }) {
            val lastNorm = (lastSample.batteryLevel / 100f).coerceIn(0f, 1f) * 0.45f + 0.50f
            val endY = topPadding + (1f - lastNorm) * availableH
            cache.markers.add(CurveMarker(contentRight, endY, "${lastSample.batteryLevel}%", isPriority = true))
        }
    }

    /**
     * 绘制电量平滑衰减曲线与数值标签（直接复用预计算 Path 与预排布 Markers）。
     *
     * @param canvas 绘制画布 [Canvas]
     * @param cache 电量曲线缓存对象 [CachedCurveData]
     */
    private fun drawBatteryCurve(
        canvas: Canvas,
        cache: CachedCurveData
    ) {
        val color = Color.parseColor("#B33A7FF0")
        linePaint.color = color
        metricDotPaint.color = Color.parseColor("#3A7FF0")
        metricLabelPaint.color = color

        canvas.drawPath(cache.path, linePaint)
        drawSmartMarkers(canvas, cache, metricLabelPaint, metricLabelHaloPaint, metricDotPaint)
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
        val targetPoints = (contentWidth * 0.75f).toInt().coerceIn(300, 800)
        val downsampled = ChartDownsampler.downsampleByMetric(rawSamples, targetPoints) { it.temperatureC }
        if (downsampled.isEmpty()) return

        val contentRight = contentLeft + contentWidth
        val firstSample = downsampled.first()
        val tempRange = (cachedMaxTemp - cachedMinTemp).coerceAtLeast(0.1)
        val firstNorm = (((firstSample.temperatureC - cachedMinTemp) / tempRange) * 0.40 + 0.45).toFloat()
        val firstY = topPadding + (1f - firstNorm) * availableH

        cache.path.moveTo(contentLeft, firstY)
        cache.markers.add(CurveMarker(contentLeft, firstY, String.format(Locale.getDefault(), "%.1f℃", firstSample.temperatureC), isPriority = false))

        var lastX = contentLeft
        var lastY = firstY
        var lastTempInt = (firstSample.temperatureC * 2).toInt()

        for (s in downsampled) {
            val x = (contentLeft + TimelineScaleCalculator.timeToX(s.timestamp, visibleStart, visibleEnd, contentWidth)).coerceIn(contentLeft, contentRight)
            val norm = (((s.temperatureC - cachedMinTemp) / tempRange) * 0.40 + 0.45).toFloat()
            val y = topPadding + (1f - norm) * availableH

            // 像素级防抖：横向位移大于 1.0px 或到达末点才追加贝塞尔段
            if (x > lastX + 1.0f || s === downsampled.last()) {
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
     * 绘制温度平滑温升/降温曲线与数值标签（直接复用预计算 Path 与预排布 Markers）。
     *
     * @param canvas 绘制画布 [Canvas]
     * @param cache 温度曲线缓存对象 [CachedCurveData]
     */
    private fun drawTemperatureCurve(
        canvas: Canvas,
        cache: CachedCurveData
    ) {
        val color = Color.parseColor("#B3FF5252")
        linePaint.color = color
        metricDotPaint.color = color
        metricLabelPaint.color = color

        canvas.drawPath(cache.path, linePaint)
        drawSmartMarkers(canvas, cache, metricLabelPaint, metricLabelHaloPaint, metricDotPaint)
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
        val targetPoints = (contentWidth * 0.75f).toInt().coerceIn(300, 800)
        val downsampled = ChartDownsampler.downsampleByMetric(rawSamples, targetPoints) { it.voltageMv.toDouble() }
        if (downsampled.isEmpty()) return

        val contentRight = contentLeft + contentWidth
        val firstSample = downsampled.first()
        val firstVoltV = firstSample.voltageMv / 1000f
        val voltRange = (cachedMaxVoltV - cachedMinVoltV).coerceAtLeast(0.01f)
        val firstNorm = (((firstVoltV - cachedMinVoltV) / voltRange) * 0.35f + 0.35f)
        val firstY = topPadding + (1f - firstNorm) * availableH

        cache.path.moveTo(contentLeft, firstY)
        cache.markers.add(CurveMarker(contentLeft, firstY, String.format(Locale.getDefault(), "%.3f V", firstVoltV), isPriority = false))

        var lastX = contentLeft
        var lastY = firstY
        var lastVolt = firstVoltV

        for (s in downsampled) {
            val x = (contentLeft + TimelineScaleCalculator.timeToX(s.timestamp, visibleStart, visibleEnd, contentWidth)).coerceIn(contentLeft, contentRight)
            val voltV = s.voltageMv / 1000f
            val norm = (((voltV - cachedMinVoltV) / voltRange) * 0.35f + 0.35f)
            val y = topPadding + (1f - norm) * availableH

            // 像素级防抖：横向位移大于 1.0px 或到达末点才追加贝塞尔段
            if (x > lastX + 1.0f || s === downsampled.last()) {
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
     * 绘制电压平滑变化曲线与数值标签（直接复用预计算 Path 与预排布 Markers）。
     *
     * @param canvas 绘制画布 [Canvas]
     * @param cache 电压曲线缓存对象 [CachedCurveData]
     */
    private fun drawVoltageCurve(
        canvas: Canvas,
        cache: CachedCurveData
    ) {
        val color = Color.parseColor("#B3FFCA28")
        linePaint.color = color
        metricDotPaint.color = color
        metricLabelPaint.color = color

        canvas.drawPath(cache.path, linePaint)
        drawSmartMarkers(canvas, cache, metricLabelPaint, metricLabelHaloPaint, metricDotPaint)
    }

    /**
     * 计算指定排版方位下的文本外接矩形区域。
     * 支持左侧、上方、右侧、下方、右下方与左下方 6 种排布方位，
     * 无论何种方位均实施严格的全视窗防溢出边界保护，确保文字绝不被图表边缘截断。
     *
     * @param marker 待绘制的数值标注节点 [CurveMarker]
     * @param textWidth 文本测量物理宽度（像素）
     * @param textHeight 文本测量物理高度（像素）
     * @param orientation 排版目标方位：0 表示左侧，1 表示上方，2 表示右侧，3 表示下方，4 表示右下方，5 表示左下方
     * @param contentLeft 图表内容区域左边界 X 坐标（像素）
     * @param contentRight 图表内容区域右边界 X 坐标（像素）
     * @param topBound 图表内容区域上边界 Y 坐标（像素）
     * @param bottomBound 图表内容区域下边界 Y 坐标（像素）
     * @return 对应排版方位下严格防溢出的文本外接矩形 [RectF]
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
        val rawRect = when (orientation) {
            0 -> { // 左侧 (Left)：优先显示在点左侧偏上
                val right = marker.x - dotRadius - spacing
                val left = right - textWidth
                val top = marker.y - textHeight / 2f - dp1
                RectF(left, top, right, top + textHeight)
            }
            1 -> { // 上方 (Top)：居中显示在点上方
                val bottom = marker.y - dotRadius - spacing
                val top = bottom - textHeight
                val left = marker.x - textWidth / 2f
                RectF(left, top, left + textWidth, bottom)
            }
            2 -> { // 右侧 (Right)：显示在点右侧偏上
                val left = marker.x + dotRadius + spacing
                val top = marker.y - textHeight / 2f - dp1
                RectF(left, top, left + textWidth, top + textHeight)
            }
            3 -> { // 下方 (Bottom)：居中显示在点下方
                val top = marker.y + dotRadius + spacing
                val left = marker.x - textWidth / 2f
                RectF(left, top, left + textWidth, top + textHeight)
            }
            4 -> { // 右下方 (Right-Bottom)：显示在点右侧偏下（波峰高点最佳排布）
                val left = marker.x + dotRadius + spacing
                val top = marker.y + dotRadius + dp1
                RectF(left, top, left + textWidth, top + textHeight)
            }
            else -> { // 左下方 (Left-Bottom)：显示在点左侧偏下
                val right = marker.x - dotRadius - spacing
                val left = right - textWidth
                val top = marker.y + dotRadius + dp1
                RectF(left, top, right, top + textHeight)
            }
        }

        // 全局严格防溢出钳位：确保文字绝不超出图表有效安全区域
        val safeLeft = rawRect.left.coerceIn(contentLeft, contentRight - textWidth)
        val safeTop = rawRect.top.coerceIn(topBound, bottomBound - textHeight)
        return RectF(safeLeft, safeTop, safeLeft + textWidth, safeTop + textHeight)
    }

    /**
     * 智能探测候选节点的最佳排版方位。
     * 根据节点所处的物理几何空间（是否靠近顶部边界、底部边界、左侧或右侧边界），
     * 动态自适应调整方位试探序列：
     * 1. 上方物理空间充足（含波峰高点）：优先在正上方居中悬浮排布（开阔清晰，杜绝压入下方折线丛）；
     * 2. 贴顶极限受阻节点：在上方确实无法容纳文本时智能避让至右下方、左下方或下方；
     * 3. 底部波谷低点（如最小待机功耗 0.03W）：优先探测上方与右侧/左侧，杜绝下溢出界；
     * 4. 边缘节点：自适应向图表内部收敛。
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
        val canFitTop = (marker.y - textHeight - dp4 >= topBound)
        val canFitBottom = (marker.y + textHeight + dp4 <= bottomBound)
        val isNearLeft = (marker.x - contentLeft) < textWidth
        val isNearRight = (contentRight - marker.x) < textWidth

        val orientations = when {
            // 上方物理空间充足（含波峰高点）：上方永远是最佳第一首选，绝不压入下方折线丛
            canFitTop -> when {
                !canFitBottom -> when {
                    isNearLeft -> listOf(1, 2, 0) // 底部波谷偏左：上方 -> 右侧 -> 左侧
                    isNearRight -> listOf(1, 0, 2) // 底部波谷偏右：上方 -> 左侧 -> 右侧
                    else -> listOf(1, 2, 0) // 底部波谷居中：上方 -> 右侧 -> 左侧
                }
                isNearLeft -> listOf(1, 2, 4, 3) // 偏左：上方 -> 右侧 -> 右下 -> 下方
                isNearRight -> listOf(1, 0, 5, 3) // 偏右：上方 -> 左侧 -> 左下 -> 下方
                else -> listOf(1, 2, 0, 4, 5, 3) // 居中波峰：上方 -> 右侧 -> 左侧 -> 右下 -> 左下 -> 下方
            }
            // 上方极端贴顶空间不足（如 100% 满充贴顶）：避让上方，探测右下/左下/下方
            else -> when {
                isNearLeft -> listOf(4, 2, 3) // 贴顶偏左：右下 -> 右侧 -> 下方
                isNearRight -> listOf(5, 0, 3) // 贴顶偏右：左下 -> 左侧 -> 下方
                else -> listOf(4, 5, 3, 2, 0) // 贴顶居中：右下 -> 左下 -> 下方 -> 右侧 -> 左侧
            }
        }

        for (ori in orientations) {
            val rect = calculateMarkerTextRect(marker, textWidth, textHeight, ori, contentLeft, contentRight, topBound, bottomBound)
            // 视窗真实避让检查：如果选上方但点本就在顶部且空间不足，则不推荐强制贴顶
            if (ori == 1 && marker.y - textHeight - dp4 < topBound) continue
            if (ori == 3 && marker.y + textHeight + dp4 > bottomBound) continue

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
     * 预计算曲线关键节点的排布坐标与文字基线，并进行多曲线全局避让与防碰撞检测。
     * 针对最高峰值与最低谷值赋予绝对高优先级排布呈现，
     * 智能避让已绘制曲线及同曲线的已排布标签，并将排布坐标固化于缓存中。
     *
     * @param cache 目标曲线缓存对象 [CachedCurveData]
     * @param paint 标注文本测量画笔 [Paint]
     * @param contentLeft 内容区左边缘 X 坐标
     * @param contentRight 内容区右边缘 X 坐标
     * @param topBound 内容区上边缘 Y 坐标
     * @param bottomBound 内容区下边缘 Y 坐标
     * @param occupiedRects 跨曲线全局共享的已占用屏幕矩形集合 [MutableList<RectF>]
     */
    private fun layoutSmartMarkers(
        cache: CachedCurveData,
        paint: Paint,
        contentLeft: Float,
        contentRight: Float,
        topBound: Float,
        bottomBound: Float,
        occupiedRects: MutableList<RectF> = mutableListOf()
    ) {
        cache.laidOutMarkers.clear()
        if (cache.markers.isEmpty()) return

        paint.textAlign = Paint.Align.LEFT
        val fontMetrics = paint.fontMetrics
        val textHeight = fontMetrics.descent - fontMetrics.ascent

        // 1. 第一阶段：最峰（Max）与最谷（Min）绝对高优先级排布（绝不被过滤丢弃）
        val priorityMarkers = cache.markers.filter { it.isPriority }
        for (m in priorityMarkers) {
            val textWidth = paint.measureText(m.text)
            var bestRect = determineBestMarkerRect(m, textWidth, textHeight, contentLeft, contentRight, topBound, bottomBound, occupiedRects, checkCollision = true)
            if (bestRect == null) {
                bestRect = determineBestMarkerRect(m, textWidth, textHeight, contentLeft, contentRight, topBound, bottomBound, occupiedRects, checkCollision = false)
                    ?: run {
                        val fallbackOri = if (m.y - topBound >= textHeight + dp6) 1 else 3
                        calculateMarkerTextRect(m, textWidth, textHeight, fallbackOri, contentLeft, contentRight, topBound, bottomBound)
                    }
            }

            val baseline = bestRect.top - fontMetrics.ascent
            cache.laidOutMarkers.add(LaidOutMarker(m, bestRect.left, baseline))

            // 占位矩形附加安全缓冲呼吸空间（左右 4dp，上下 2dp）
            occupiedRects.add(RectF(bestRect.left - dp4, bestRect.top - dp2, bestRect.right + dp4, bestRect.bottom + dp2))
        }

        // 2. 第二阶段：次要参考节点在无碰撞冲突的前提下补充排布
        val secondaryMarkers = cache.markers.filter { !it.isPriority }
        for (m in secondaryMarkers) {
            val textWidth = paint.measureText(m.text)
            val bestRect = determineBestMarkerRect(m, textWidth, textHeight, contentLeft, contentRight, topBound, bottomBound, occupiedRects, checkCollision = true)
            if (bestRect != null) {
                val baseline = bestRect.top - fontMetrics.ascent
                cache.laidOutMarkers.add(LaidOutMarker(m, bestRect.left, baseline))

                occupiedRects.add(RectF(bestRect.left - dp4, bestRect.top - dp2, bestRect.right + dp4, bestRect.bottom + dp2))
            }
        }
    }

    /**
     * 绘制曲线预排布的关键节点圆点与小数字标签。
     * 直接遍历已完成避让排布的 [CachedCurveData.laidOutMarkers]，零 onDraw 测量与堆内存分配开销。
     *
     * @param canvas 绘制目标画布 [Canvas]
     * @param cache 包含预排布标注节点的曲线缓存 [CachedCurveData]
     * @param paint 文本主色填充画笔 [Paint]
     * @param haloPaint 文本微暗描边光晕画笔 [Paint]
     * @param dotPaint 节点小圆点画笔 [Paint]
     */
    private fun drawSmartMarkers(
        canvas: Canvas,
        cache: CachedCurveData,
        paint: Paint,
        haloPaint: Paint,
        dotPaint: Paint
    ) {
        if (cache.laidOutMarkers.isEmpty()) return

        paint.textAlign = Paint.Align.LEFT
        haloPaint.textAlign = Paint.Align.LEFT

        val dotRadius = dp2_5
        for (item in cache.laidOutMarkers) {
            // 绘制圆点
            canvas.drawCircle(item.marker.x, item.marker.y, dotRadius, dotPaint)

            // 双层清晰绘制：微暗描边光晕 + 主题前景色
            canvas.drawText(item.marker.text, item.textX, item.textY, haloPaint)
            canvas.drawText(item.marker.text, item.textX, item.textY, paint)
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

        val mergedScreens = cachedMergedScreenEvents
        if (mergedScreens.isEmpty()) {
            tempRectF.set(contentLeft, top, contentRight, bottom)
            canvas.drawRoundRect(tempRectF, dp1_5, dp1_5, screenOnBarPaint)
            return
        }

        // 1. 底层先绘制完整圆角底条（默认全铺深度睡眠深红）
        tempRectF.set(contentLeft, top, contentRight, bottom)
        canvas.drawRoundRect(tempRectF, dp1_5, dp1_5, screenOffDeepSleepBarPaint)

        // 2. 依次精确绘制亮屏（亮绿）、息屏深度睡眠（深红）与息屏唤醒（浅红）三色段
        for (event in mergedScreens) {
            if (event.endTime < visibleStart || event.startTime > visibleEnd) continue

            val left = (contentLeft + TimelineScaleCalculator.timeToX(event.startTime, visibleStart, visibleEnd, contentWidth)).coerceIn(contentLeft, contentRight)
            val right = (contentLeft + TimelineScaleCalculator.timeToX(event.endTime, visibleStart, visibleEnd, contentWidth)).coerceIn(contentLeft, contentRight)

            if (event.isScreenOn) {
                drawScreenSegment(canvas, left, right, top, bottom, contentLeft, contentRight, screenOnBarPaint)
            } else if (event.isDeepSleep) {
                drawScreenSegment(canvas, left, right, top, bottom, contentLeft, contentRight, screenOffDeepSleepBarPaint)
            } else {
                drawScreenSegment(canvas, left, right, top, bottom, contentLeft, contentRight, screenOffAwakeBarPaint)
            }
        }
    }

    /**
     * 绘制屏幕状态单条分段（支持首尾圆角与中间矩形）。
     *
     * @param canvas 绘图画布 [Canvas]
     * @param left 分段左边缘 X 坐标
     * @param right 分段右边缘 X 坐标
     * @param top 分段上边缘 Y 坐标
     * @param bottom 分段下边缘 Y 坐标
     * @param contentLeft 状态条总左边界 X 坐标
     * @param contentRight 状态条总右边界 X 坐标
     * @param paint 绘制画笔 [Paint]
     */
    private fun drawScreenSegment(
        canvas: Canvas,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        contentLeft: Float,
        contentRight: Float,
        paint: Paint
    ) {
        if (right > left) {
            tempRectF.set(left, top, right, bottom)
            val isAtLeft = left <= contentLeft + dp0_5
            val isAtRight = right >= contentRight - dp0_5
            if (isAtLeft && isAtRight) {
                canvas.drawRoundRect(tempRectF, dp1_5, dp1_5, paint)
            } else if (isAtLeft) {
                tempPath.reset()
                tempPath.addRoundRect(tempRectF, floatArrayOf(dp1_5, dp1_5, 0f, 0f, 0f, 0f, dp1_5, dp1_5), Path.Direction.CW)
                canvas.drawPath(tempPath, paint)
            } else if (isAtRight) {
                tempPath.reset()
                tempPath.addRoundRect(tempRectF, floatArrayOf(0f, 0f, dp1_5, dp1_5, dp1_5, dp1_5, 0f, 0f), Path.Direction.CW)
                canvas.drawPath(tempPath, paint)
            } else {
                canvas.drawRect(tempRectF, paint)
            }
        }
    }

    /**
     * 绘制图表顶部常驻固定的单行读数指示看板（默认显示当前刷新时间与最新指标，触摸时实时更新为游标时刻指标），
     * 并当触控处于活跃状态时绘制垂直虚线游标与各曲线交点彩色高亮点。
     * 单行紧凑展示当前时刻指标，中间以 " | " 分隔；
     * 指标颜色严格对应下方标签颜色（电量/能量为深天蓝 #3A7FF0，功耗为浅天蓝 #90CAF9，温度为红色 #FF5252，电压为金黄 #FFD54F）；
     * 对应时刻前台活跃应用通过真实应用图标在末尾居中绘制（不使用文本名称）。
     *
     * @param canvas 绘制画布 [Canvas]
     * @param contentLeft 图表内容左边界 X 坐标
     * @param contentRight 图表内容右边界 X 坐标
     * @param contentWidth 图表内容有效宽度
     * @param mainHeight 曲线区域高度
     * @param visibleStart 视窗起始时间戳
     * @param visibleEnd 视窗结束时间戳
     * @param topPadding 曲线绘制区域顶部安全间距
     * @param availableH 曲线有效可用绘制高度
     */
    private fun drawFixedTopHeaderAndCursor(
        canvas: Canvas,
        contentLeft: Float,
        contentRight: Float,
        contentWidth: Float,
        mainHeight: Float,
        visibleStart: Long,
        visibleEnd: Long,
        topPadding: Float,
        availableH: Float
    ) {
        val clampedX = if (isCursorActive) {
            cursorX.coerceIn(contentLeft, contentRight)
        } else {
            contentRight
        }

        // 1. 查询目标时刻对应的数据实体（触摸时取游标时刻临近点，非触摸时直接复用预计算静态看板缓存）
        val timeStr: String
        val levelStr: String?
        val energyStr: String?
        val powerStr: String?
        val tempStr: String?
        val voltStr: String?
        val curApp: AppTimelineEvent?
        val curSample: BatterySample?

        if (isCursorActive) {
            val curTs = TimelineScaleCalculator.xToTime(clampedX, visibleStart, visibleEnd, contentWidth, contentLeft)
            curSample = timelineState.batterySamples.minByOrNull { abs(it.timestamp - curTs) }
            curApp = timelineState.appEvents.find { it.startTime <= curTs && it.endTime >= curTs }
            timeStr = timeFormatterTooltip.format(Date(curTs))
            levelStr = curSample?.let { "${it.batteryLevel}%" }
            energyStr = curSample?.let { s ->
                s.energyWh?.let { String.format(Locale.getDefault(), "%.1fWh", it) }
                    ?: timelineState.totalEnergyWh?.takeIf { it > 0f }?.let {
                        String.format(Locale.getDefault(), "%.1fWh", s.batteryLevel / 100f * it)
                    }
            }
            powerStr = curSample?.let {
                val pWatts = it.getPowerWatts()
                val signedPower = if (pWatts > 0) -pWatts else pWatts
                String.format(Locale.getDefault(), "%.2fW", signedPower)
            }
            tempStr = curSample?.let { String.format(Locale.getDefault(), "%.1f℃", it.temperatureC) }
            voltStr = curSample?.let { String.format(Locale.getDefault(), "%.3fV", it.getVoltageVolts()) }
        } else {
            val headerData = cachedDefaultHeader ?: run {
                updateDefaultHeaderCache()
                cachedDefaultHeader!!
            }
            timeStr = headerData.timeStr
            levelStr = headerData.levelStr
            energyStr = headerData.energyStr
            powerStr = headerData.powerStr
            tempStr = headerData.tempStr
            voltStr = headerData.voltStr
            curApp = headerData.curApp
            curSample = timelineState.batterySamples.lastOrNull()
        }

        // 2. 顶部单行轻微圆角背景与描边卡片
        val headerTop = dp2
        val headerBottom = dp24
        tooltipRect.set(contentLeft, headerTop, contentRight, headerBottom)
        canvas.drawRoundRect(tooltipRect, dp4, dp4, tooltipBgPaint)
        canvas.drawRoundRect(tooltipRect, dp4, dp4, tooltipBorderPaint)

        // 单行文字垂直居中排布基线
        val textY = headerTop + (headerBottom - headerTop) / 2f - (headerTimePaint.descent() + headerTimePaint.ascent()) / 2f
        val paddingH = dp5
        val iconSize = dp12

        // 2.1 绘制时间（靠左对齐，距左 5dp）
        headerTimePaint.textAlign = Paint.Align.LEFT
        canvas.drawText(timeStr, contentLeft + paddingH, textY, headerTimePaint)
        val timeWidth = headerTimePaint.measureText(timeStr)

        // 2.2 绘制应用图标（靠右对齐，距右 5dp）
        val iconLeft = contentRight - paddingH - iconSize
        val iconTop = headerTop + (headerBottom - headerTop - iconSize) / 2f
        if (curApp != null) {
            val renderIconSize = iconSize.toInt().coerceAtLeast(1)
            val bmp = DrawableBitmapCache.getOrLoadBitmap(context, curApp.packageName, renderIconSize, curApp.icon)
            if (bmp != null && !bmp.isRecycled) {
                tempSrcRect.set(0, 0, bmp.width, bmp.height)
                tempDstRectF.set(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
                canvas.drawBitmap(bmp, tempSrcRect, tempDstRectF, bitmapPaint)
            }
        }

        // 2.3 中间 5 个指标（电量、能量、功耗、温度、电压）在时间与图标之间按比例等分居中排布（无分隔符）
        val middleLeft = contentLeft + paddingH + timeWidth + dp6
        val middleRight = contentRight - paddingH - iconSize - dp6
        val middleWidth = maxOf(0f, middleRight - middleLeft)
        val colWidth = middleWidth / 5f

        headerMetricValuePaint.textAlign = Paint.Align.CENTER

        // 绘制电量（深天蓝 #3A7FF0，第 1 列居中）
        if (levelStr != null) {
            headerMetricValuePaint.color = colorBattery
            canvas.drawText(levelStr, middleLeft + colWidth * 0.5f, textY, headerMetricValuePaint)
        }

        // 绘制能量（紧随电量之后，同深天蓝 #3A7FF0，第 2 列居中）
        val energyColLeft = middleLeft + colWidth * 1.0f
        val energyColRight = middleLeft + colWidth * 2.0f
        energyTouchRect.set(energyColLeft, 0f, energyColRight, dp28)
        if (energyStr != null) {
            headerMetricValuePaint.color = colorBattery
            canvas.drawText(energyStr, middleLeft + colWidth * 1.5f, textY, headerMetricValuePaint)
        }

        // 绘制功耗（浅天蓝 #90CAF9，第 3 列居中）
        if (powerStr != null) {
            headerMetricValuePaint.color = colorPower
            canvas.drawText(powerStr, middleLeft + colWidth * 2.5f, textY, headerMetricValuePaint)
        }

        // 绘制温度（红色 #FF5252，第 4 列居中）
        if (tempStr != null) {
            headerMetricValuePaint.color = colorTemp
            canvas.drawText(tempStr, middleLeft + colWidth * 3.5f, textY, headerMetricValuePaint)
        }

        // 绘制电压（金黄 #FFD54F，第 5 列居中）
        if (voltStr != null) {
            headerMetricValuePaint.color = colorVoltage
            canvas.drawText(voltStr, middleLeft + colWidth * 4.5f, textY, headerMetricValuePaint)
        }

        // 3. 若处于触控活跃状态，绘制垂直虚线游标与各折线交点高亮点
        if (isCursorActive) {
            canvas.drawLine(clampedX, headerBottom, clampedX, mainHeight + dp6, cursorPaint)

            if (curSample != null) {
                val selected = timelineState.selectedMetrics

                // 功率指标高亮点
                if (selected.contains(TimelineMetric.POWER)) {
                    val pW = (abs(curSample.powerMw) / 1000.0).toFloat().coerceIn(0f, cachedMaxScaleW.toFloat())
                    val powerRatio = (pW / cachedMaxScaleW.toFloat()).coerceIn(0f, 1f)
                    val powerY = topPadding + (1f - powerRatio * 0.85f) * availableH
                    drawHighLightDot(canvas, clampedX, powerY, colorPower)
                }

                // 电量指标高亮点
                if (selected.contains(TimelineMetric.BATTERY)) {
                    val batteryNorm = (curSample.batteryLevel / 100f).coerceIn(0f, 1f) * 0.45f + 0.50f
                    val batteryY = topPadding + (1f - batteryNorm) * availableH
                    drawHighLightDot(canvas, clampedX, batteryY, colorBattery)
                }

                // 温度指标高亮点
                if (selected.contains(TimelineMetric.TEMPERATURE)) {
                    val tempRange = (cachedMaxTemp - cachedMinTemp).coerceAtLeast(0.1)
                    val tempNorm = (((curSample.temperatureC - cachedMinTemp) / tempRange) * 0.40 + 0.45).toFloat()
                    val tempY = topPadding + (1f - tempNorm) * availableH
                    drawHighLightDot(canvas, clampedX, tempY, colorTemp)
                }

                // 电压指标高亮点
                if (selected.contains(TimelineMetric.VOLTAGE)) {
                    val voltV = curSample.voltageMv / 1000f
                    val voltRange = (cachedMaxVoltV - cachedMinVoltV).coerceAtLeast(0.01f)
                    val voltNorm = (((voltV - cachedMinVoltV) / voltRange) * 0.35f + 0.35f)
                    val voltY = topPadding + (1f - voltNorm) * availableH
                    drawHighLightDot(canvas, clampedX, voltY, colorVoltage)
                }
            }

            // 若命中存在 +N 溢出应用的时间槽，在顶部固定探查看板正下方显示纯图标气泡卡片
            drawTouchTooltip(canvas, clampedX)
        }
    }

    /**
     * 绘制触控探查时针对 "+N" 溢出折叠应用的自适应悬浮气泡卡片（Tooltip）。
     * 仅当选中的时间切片内存在超出纵向最大显示行数的 "+N" 溢出应用时才触发显示，
     * 卡片内部仅纯净展示溢出被折叠的 N 款应用图标，并精准定位在顶部探查看板的正下方。
     *
     * @param canvas 绘制画布
     * @param pX 探查数据点横坐标
     */
    private fun drawTouchTooltip(
        canvas: Canvas,
        pX: Float
    ) {
        if (!timelineState.selectedMetrics.contains(TimelineMetric.APP) || cachedSlotItems.isEmpty()) return

        // 1. 就近检索当前触控探查横坐标所对应的时间槽
        val nearestItem = cachedSlotItems.minByOrNull { abs(it.centerX - pX) } ?: return
        if (abs(nearestItem.centerX - pX) > dp11 * 1.5f) return

        // 2. 查找该时间槽内是否存在 +N 溢出折叠项
        val overflowItem = cachedSlotItems.firstOrNull {
            it.slotIndex == nearestItem.slotIndex && it.overflowCount > 0
        } ?: return

        val overflowCount = overflowItem.overflowCount
        if (overflowCount <= 0 || overflowItem.allSlotEvents.isEmpty()) return

        // 3. 提取被折叠在 +N 徽章内的 N 款应用事件列表
        val overflowEvents = overflowItem.allSlotEvents.takeLast(overflowCount)
        if (overflowEvents.isEmpty()) return

        // 4. 计算纯图标气泡卡片尺寸与坐标（固定显示在顶部固定看板下方）
        val iconSize = dp11
        val iconGap = dp0
        val paddingH = dp1
        val paddingV = dp1
        val renderIconSize = iconSize.toInt().coerceAtLeast(1)

        val availableW = (width - dp11 * 2 - paddingH * 2).coerceAtLeast(iconSize)
        val maxCols = ((availableW + iconGap) / (iconSize + iconGap)).toInt().coerceAtLeast(1)
        val cols = minOf(overflowEvents.size, maxCols)
        val rows = (overflowEvents.size + cols - 1) / cols

        val boxWidth = paddingH * 2 + cols * iconSize + (cols - 1).coerceAtLeast(0) * iconGap
        val boxHeight = paddingV * 2 + rows * iconSize + (rows - 1).coerceAtLeast(0) * iconGap

        // 顶部固定看板底部为 dp24，卡片固定定位在 dp26 处（即看板正下方）
        val boxTop = dp26
        val minLeft = dp14
        val maxLeft = maxOf(minLeft, width - dp14 - boxWidth)
        val boxLeft = (pX - boxWidth / 2f).coerceIn(minLeft, maxLeft)

        // 5. 绘制卡片圆角背景与微暗边框
        popupTooltipRect.set(boxLeft, boxTop, boxLeft + boxWidth, boxTop + boxHeight)
        canvas.drawRoundRect(popupTooltipRect, dp4, dp4, popupTooltipBgPaint)
        canvas.drawRoundRect(popupTooltipRect, dp4, dp4, popupTooltipBorderPaint)

        // 6. 依次居中绘制被折叠的 N 款应用图标（支持单行与多行网格自适应）
        for ((index, event) in overflowEvents.withIndex()) {
            val col = index % cols
            val row = index / cols
            val curIconLeft = boxLeft + paddingH + col * (iconSize + iconGap)
            val curIconTop = boxTop + paddingV + row * (iconSize + iconGap)

            val bmp = DrawableBitmapCache.getOrLoadBitmap(
                context,
                event.packageName,
                renderIconSize,
                event.icon
            )
            if (bmp != null && !bmp.isRecycled) {
                tempSrcRect.set(0, 0, bmp.width, bmp.height)
                tempDstRectF.set(
                    curIconLeft,
                    curIconTop,
                    curIconLeft + iconSize,
                    curIconTop + iconSize
                )
                canvas.drawBitmap(bmp, tempSrcRect, tempDstRectF, bitmapPaint)
            }
        }
    }

    /**
     * 在指定坐标点绘制高亮光环指示圆点（外层彩色光环 + 内层白色圆心，与充电趋势图规范完全统一）。
     *
     * @param canvas 绘制画布 [Canvas]
     * @param x 圆心 X 坐标
     * @param y 圆心 Y 坐标
     * @param color 外圈彩色主题色
     */
    private fun drawHighLightDot(canvas: Canvas, x: Float, y: Float, color: Int) {
        dotPaint.color = color
        canvas.drawCircle(x, y, dp5, dotPaint)
        canvas.drawCircle(x, y, dp2, dotInnerPaint)
    }

    /**
     * 处理单指点击，检测是否命中顶部固定看板能量指标或 App 图标徽章。
     *
     * @param x 点击 X 坐标
     * @param y 点击 Y 坐标
     * @return 是否成功处理点击事件
     */
    private fun handleSingleTap(x: Float, y: Float): Boolean {
        if (energyTouchRect.contains(x, y)) {
            onEnergyClickListener?.onEnergyClick(this, x, y)
            return true
        }

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
     * 严格遵循“图表中的触摸事件只有在长按时触发”的交互规范：
     * 1. 父级列表正在滚动时，图表直接忽略所有触摸事件；
     * 2. 多指缩放手势优先识别，并在多指按下时立即取消长按检测；
     * 3. 单指触摸按下时启动长按倒计时，此时不拦截父容器，保障列表垂直滚动的最高优先级；
     * 4. 在未长按状态下，手指发生任何移动（dx > touchSlop || dy > touchSlop）立即取消长按定时器并完全放行给列表滚动，绝对不触发图表触摸事件；
     * 5. 只有在手指保持按住静止达到长按门限后，才激活垂直游标并锁定父容器，跟随手指横向拖动探查数据；
     * 6. 手指抬起或手势取消时重置长按状态并释放父容器拦截。
     *
     * @param event 触摸事件 [MotionEvent]
     * @return 是否消费触摸事件
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 父级列表正在上下滑动过程中，图表严禁响应手势，杜绝抢夺事件与重绘
        if (isParentScrolling) {
            cancelLongPressTimer()
            return false
        }

        // 双指缩放手势优先处理：检测到多指时立即取消长按检测
        if (event.pointerCount > 1) {
            cancelLongPressTimer()
            if (isCursorActive) {
                isCursorActive = false
                onCursorInspectListener?.onCursorDismiss()
                invalidate()
            }
            return scaleGestureDetector.onTouchEvent(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                isTouchMoved = false
                cancelLongPressTimer()
                postDelayed(longPressRunnable, longPressTimeout)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                // 若游标已长按激活，跟随手指横向移动更新游标与读数看板
                if (isCursorActive) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    cursorX = event.x.coerceIn(0f, width.toFloat())
                    notifyCursorMove(cursorX)
                    invalidate()
                    return true
                }

                // 未长按状态下：若已被判定为已移动，直接放行
                if (isTouchMoved) {
                    return false
                }

                // 检测位移：只要手指发生任何有效移动或显现垂直滑动意图，立即彻底取消长按判定并完全放行给列表滚动
                val totalDx = abs(event.x - touchDownX)
                val totalDy = abs(event.y - touchDownY)
                val isVerticalDominant = totalDy > touchSlop * 0.4f && totalDy > totalDx
                if (isVerticalDominant || totalDx > touchSlop || totalDy > touchSlop) {
                    isTouchMoved = true
                    cancelLongPressTimer()
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return false
                }

                // 位移仍在 touchSlop 以内，维持等待长按状态，继续接收后续事件
                return true
            }

            MotionEvent.ACTION_UP -> {
                cancelLongPressTimer()
                if (isCursorActive) {
                    isCursorActive = false
                    onCursorInspectListener?.onCursorDismiss()
                    parent?.requestDisallowInterceptTouchEvent(false)
                    invalidate()
                    return true
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                // 原地轻微点击且未长按、未移动时，判定为单指单击（如点击能量指标或应用徽章）
                if (!isTouchMoved && abs(event.x - touchDownX) <= touchSlop && abs(event.y - touchDownY) <= touchSlop) {
                    return handleSingleTap(event.x, event.y)
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelLongPressTimer()
                if (isCursorActive) {
                    isCursorActive = false
                    onCursorInspectListener?.onCursorDismiss()
                    parent?.requestDisallowInterceptTouchEvent(false)
                    invalidate()
                }
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }

        return isCursorActive || super.onTouchEvent(event)
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
