package com.battery.analysis.ui.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.battery.analysis.model.ChargingSamplePoint
import com.battery.analysis.timeline.domain.AppTimelineEvent
import com.battery.analysis.timeline.presentation.TimelineMetric
import com.battery.analysis.timeline.util.DrawableBitmapCache
import com.battery.analysis.timeline.util.TimelineLayoutCalculator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * 充电过程三合一（功率、电量、温度）动态折线图自定义控件。
 * 在同一图表内采用三种不同高辨识度颜色直线连接呈现充电瞬时功率、电池电量与电池温度，
 * 支持动态采样实时绘制、折线峰谷值小数字标注、顶部固定探查看板与手势标尺探查。
 *
 * @param context Android 上下文环境
 * @param attrs XML 属性集合
 * @param defStyleAttr 默认样式属性
 */
class ChargingChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /**
     * 触控采样点探查选择监听器接口。
     */
    interface OnPointSelectedListener {
        /**
         * 当手势滑动探查选中采样点或手势离开时触发。
         *
         * @param point 当前选中的采样点实体，手势抬起离开时传入 null
         */
        fun onPointSelected(point: ChargingSamplePoint?)
    }

    private val dataPoints = mutableListOf<ChargingSamplePoint>()
    private val powerPoints = mutableListOf<PointF>()
    private val levelPoints = mutableListOf<PointF>()
    private val tempPoints = mutableListOf<PointF>()

    // 预计算物理像素尺寸
    private val dp0 = dpToPx(0f)
    private val dp0_5 = dpToPx(0.5f)
    private val dp1 = dpToPx(1f)
    private val dp1_5 = dpToPx(1.5f)
    private val dp2 = dpToPx(2f)
    private val dp2_2 = dpToPx(2.2f)
    private val dp3 = dpToPx(3f)
    private val dp4 = dpToPx(4f)
    private val dp5 = dpToPx(5f)
    private val dp6 = dpToPx(6f)
    private val dp8 = dpToPx(8f)
    private val dp10 = dpToPx(10f)
    private val dp11 = dpToPx(11f)
    private val dp12 = dpToPx(12f)
    private val dp13 = dpToPx(13f)
    private val dp14 = dpToPx(14f)
    private val dp16 = dpToPx(16f)
    private val dp18 = dpToPx(18f)
    private val dp22 = dpToPx(22f)
    private val dp24 = dpToPx(24f)
    private val dp26 = dpToPx(26f)
    private val dp28 = dpToPx(28f)
    private val dp30 = dpToPx(30f)
    private val dp32 = dpToPx(32f)
    private val dp40 = dpToPx(40f)
    private val dp50 = dpToPx(50f)

    private val sp5 = spToPx(5f)
    private val sp6 = spToPx(6f)
    private val sp7 = spToPx(7f)
    private val sp7_5 = spToPx(7.5f)
    private val sp8 = spToPx(8f)
    private val sp8_5 = spToPx(8.5f)
    private val sp9 = spToPx(9f)
    private val sp9_5 = spToPx(9.5f)
    private val sp10 = spToPx(10f)
    private val sp11 = spToPx(11f)

    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val axisTimeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())

    // 颜色配置（淡蓝: 充电功率, 橙色: 放电功率, 蓝色: 电量 3A7FF0, 红色: 温度, 黄色: 电压）
    val colorPowerCharge = Color.parseColor("#90CAF9")
    val colorPowerDischarge = Color.parseColor("#FF9800")
    val colorPower = colorPowerCharge
    val colorLevel = Color.parseColor("#3A7FF0")
    val colorTemp = Color.parseColor("#FF5252")
    val colorVoltage = Color.parseColor("#FFD54F")

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

    // 绘制画笔：功率曲线（折线宽度全部统一为 1.5dp）
    private val powerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp1_5
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = colorPowerCharge
    }

    // 0W 充放电分界基准虚线画笔
    private val zeroLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp1
        color = Color.parseColor("#40888888")
        pathEffect = DashPathEffect(floatArrayOf(dp4, dp2), 0f)
    }

    // 0W 标识文字画笔
    private val zeroTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp9
        color = Color.parseColor("#888888")
        textAlign = Paint.Align.LEFT
    }

    // 绘制画笔：电量曲线（折线宽度全部统一为 1.5dp）
    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp1_5
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = colorLevel
    }

    // 绘制画笔：温度曲线（折线宽度全部统一为 1.5dp）
    private val tempPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp1_5
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = colorTemp
    }

    // 绘制画笔：电压曲线（新增黄色折线，折线宽度全部统一为 1.5dp）
    private val voltagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp1_5
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = colorVoltage
    }

    // 背景参考网格线画笔
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp1
        color = Color.parseColor("#18888888")
        pathEffect = DashPathEffect(floatArrayOf(dp3, dp3), 0f)
    }

    // 坐标轴时间文本画笔
    private val axisTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp9_5
        color = Color.parseColor("#888888")
        textAlign = Paint.Align.CENTER
    }

    // 手势探查垂直标尺画笔
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp1
        color = Color.parseColor("#2196F3")
        pathEffect = DashPathEffect(floatArrayOf(dp3, dp3), 0f)
    }

    // 探查端点光环画笔
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // 探查端点白色内圈画笔
    private val dotInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    // 顶部固定信息栏背景画笔
    private val headerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#12888888")
    }

    // 顶部固定信息栏边框画笔
    private val headerBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp1
        color = Color.parseColor("#1F888888")
    }

    // 顶部固定信息栏文本画笔
    private val headerTimePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp9_5
        color = Color.parseColor("#9E9E9E")
    }

    private val headerMetricValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp9_5
        isFakeBoldText = true
    }

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        isDither = true
    }

    // 峰谷值微型标签光晕描边画笔（双层绘制，确保在折线和网格上方文字清晰）
    private val badgeHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp2_2
        color = Color.parseColor("#D9212121")
        textSize = sp9
        textAlign = Paint.Align.CENTER
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // 峰谷值微型标签实体填充画笔
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = sp9
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val powerPath = Path()
    private val levelPath = Path()
    private val tempPath = Path()
    private val voltagePath = Path()
    private val gridPath = Path()
    private val headerRect = RectF()

    // 成员对象复用池，彻底消除 onDraw 中高频循环 new PointF 引发的 GC 内存抖动与功耗卡顿
    private val powerPointPool = mutableListOf<PointF>()
    private val levelPointPool = mutableListOf<PointF>()
    private val tempPointPool = mutableListOf<PointF>()
    private val voltagePointPool = mutableListOf<PointF>()
    private val voltagePoints = mutableListOf<PointF>()

    // 前台活跃应用时间轴事件集合、时间槽排布缓存与图标绘制矩形复用
    private val appEvents = mutableListOf<AppTimelineEvent>()
    private var cachedSlotItems: List<TimelineLayoutCalculator.LaidOutAppSlotItem> = emptyList()
    private var lastSlotCalcMinTs: Long = 0L
    private var lastSlotCalcMaxTs: Long = 0L
    private var lastSlotCalcWidth: Float = 0f
    private var lastSlotCalcEventsHash: Int = 0
    private val iconSrcRect = android.graphics.Rect()
    private val iconDstRect = RectF()

    // 绘制空态提示画笔复用
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp11
        color = Color.parseColor("#757575")
        textAlign = Paint.Align.CENTER
    }

    // 亮灭屏指示条外轮廓 Path 复用
    private val screenBarPath = Path()

    // 溢出 +N 徽章绘制画笔
    private val overflowBadgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#E6263238") // 深灰蓝半透明背景
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
    private val tooltipRect = RectF()
    private val tooltipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#12888888")
    }
    private val tooltipBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp1
        color = Color.parseColor("#1F888888")
    }

    /**
     * 从预分配对象池中复用或扩容获取 PointF 坐标对象，避免高频创建对象。
     *
     * @param pool 目标对象池
     * @param index 数据点下标索引
     * @param x 横坐标数值
     * @param y 纵坐标数值
     * @return 赋予新坐标的 PointF 实例
     */
    private fun obtainPointF(pool: MutableList<PointF>, index: Int, x: Float, y: Float): PointF {
        return if (index < pool.size) {
            pool[index].apply { set(x, y) }
        } else {
            PointF(x, y).also { pool.add(it) }
        }
    }

    // 手势交互状态（严格遵循“图表中的触摸事件只有在长按时触发”的交互规范）
    private var isTouching = false
    private var touchX = 0f
    private var touchY = 0f
    private var selectedIndex = -1

    private var touchDownX = 0f
    private var touchDownY = 0f
    private var isTouchMoved = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong().coerceAtLeast(400L)

    /**
     * 长按触发任务：用户在图表区域内按住不动达到长按门限后，激活垂直标尺并触发轻微震动反馈。
     */
    private val longPressRunnable = Runnable {
        if (!isAttachedToWindow || isTouchMoved) return@Runnable
        isTouching = true
        parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        touchX = touchDownX
        findClosestIndex(touchX)
        invalidate()
    }

    /**
     * 取消尚未触发的长按检测定时器。
     */
    private fun cancelLongPressTimer() {
        removeCallbacks(longPressRunnable)
    }


    private var pointSelectedListener: OnPointSelectedListener? = null

    /**
     * 设置触控采样点探查选择监听器。
     *
     * @param listener 监听器实例
     */
    fun setOnPointSelectedListener(listener: OnPointSelectedListener?) {
        this.pointSelectedListener = listener
    }

    /**
     * 设置并更新充电采样点数据集，触发重新测量与视图重绘。
     *
     * @param points 最新的采样点列表
     */
    fun setData(points: List<ChargingSamplePoint>) {
        dataPoints.clear()
        dataPoints.addAll(points)
        invalidate()
    }

    /**
     * 获取当前图表内部实际已加载的采样数据点数量。
     *
     * @return 当前图表中持有的采样点总数
     */
    fun getPointsCount(): Int {
        return dataPoints.size
    }

    /**
     * 向现有采样数据集末尾实时追加单点并执行重绘。
     * 忠实保留全量采样历史数据，严禁人为截断导致充电早期数据丢失或时间轴错乱。
     *
     * @param point 最新的采样物理点实体 [ChargingSamplePoint]
     */
    fun appendPoint(point: ChargingSamplePoint) {
        dataPoints.add(point)
        invalidate()
    }

    /**
     * 向现有采样数据集末尾批量追加新采样点并执行重绘。
     * 忠实保留全量采样历史数据，严禁人为截断导致充电早期数据丢失或时间轴错乱。
     *
     * @param newPoints 待追加的新采样数据点列表 [List<ChargingSamplePoint>]
     */
    fun appendPoints(newPoints: List<ChargingSamplePoint>) {
        if (newPoints.isEmpty()) return
        dataPoints.addAll(newPoints)
        invalidate()
    }

    /**
     * 设置并更新充电期间活跃的前台应用时间轴事件集合，触发图表打点图标重绘。
     *
     * @param events 前台应用时间轴事件集合
     */
    fun setAppEvents(events: List<AppTimelineEvent>) {
        appEvents.clear()
        appEvents.addAll(events)
        cachedSlotItems = emptyList()
        lastSlotCalcEventsHash = 0
        invalidate()
    }

    /**
     * 获取当前图表中持有的前台应用时间轴事件数量。
     *
     * @return 前台应用时间轴事件总数
     */
    fun getAppEventsCount(): Int {
        return appEvents.size
    }

    // 激活展示的指标多选集合（默认全选五维：电量、功率、温度、电压、应用）
    private var selectedMetrics: Set<TimelineMetric> = setOf(
        TimelineMetric.BATTERY,
        TimelineMetric.POWER,
        TimelineMetric.TEMPERATURE,
        TimelineMetric.VOLTAGE,
        TimelineMetric.APP
    )

    /**
     * 设置充电走势图当前激活展示的指标多选集合，支持动态隐藏与呈现指定折线与应用图标。
     *
     * @param metrics 选中的指标集合 [Set<TimelineMetric>]
     */
    fun setSelectedMetrics(metrics: Set<TimelineMetric>) {
        this.selectedMetrics = metrics
        invalidate()
    }

    /**
     * 获取充电走势图当前激活展示的指标集合。
     *
     * @return 当前选中的指标集合 [Set<TimelineMetric>]
     */
    fun getSelectedMetrics(): Set<TimelineMetric> = selectedMetrics

    /**
     * 清空图表内所有走势数据并恢复空态。
     */
    fun clearData() {
        dataPoints.clear()
        powerPoints.clear()
        levelPoints.clear()
        tempPoints.clear()
        voltagePoints.clear()
        appEvents.clear()
        cachedSlotItems = emptyList()
        lastSlotCalcEventsHash = 0
        selectedIndex = -1
        pointSelectedListener?.onPointSelected(null)
        invalidate()
    }

    /**
     * 核心绘制流程：顶部固定信息看板、三维坐标映射、网格虚线、三色平滑曲线、峰谷值小数字与触控探查标尺。
     *
     * @param canvas 绘制画布
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val paddingLeft = dp14
        val paddingRight = dp14
        // 预留顶部固定看板高度 (dp2 ~ dp24)，折线图绘制区始于 dp30
        val chartTop = dp30
        val chartBottom = h - dp24

        val chartWidth = w - paddingLeft - paddingRight
        val chartHeight = chartBottom - chartTop

        if (chartWidth <= 0f || chartHeight <= 0f) return

        // 1. 在图表最顶部固定绘制读数看板（非浮动弹框，固定在此位置）
        drawFixedTopHeader(canvas, w, paddingLeft, paddingRight)

        // 2. 绘制水平参考虚线网格
        val gridCount = 4
        for (i in 0..gridCount) {
            val y = chartTop + chartHeight * (i.toFloat() / gridCount)
            gridPath.reset()
            gridPath.moveTo(paddingLeft, y)
            gridPath.lineTo(w - paddingRight, y)
            canvas.drawPath(gridPath, gridPaint)
        }

        if (dataPoints.isEmpty()) {
            val emptyText = "正在连接并采集充电数据..."
            canvas.drawText(emptyText, w / 2f, chartTop + chartHeight / 2f, emptyPaint)
            return
        }

        // 3. 统计各维度数据范围（分别统计充电正功率与放电负功率）
        val minTs = dataPoints.first().timestamp
        val maxTs = if (dataPoints.size > 1) maxOf(dataPoints.last().timestamp, minTs + 60000L) else (minTs + 60000L)
        val tsRange = (maxTs - minTs).toFloat()

        var maxChargeP = 0f
        var maxDischargeP = 0f
        var minT = Float.MAX_VALUE
        var maxT = Float.MIN_VALUE
        var minVolt = Float.MAX_VALUE
        var maxVolt = Float.MIN_VALUE
        for (p in dataPoints) {
            if (p.powerWatts > 0f) {
                if (p.powerWatts > maxChargeP) maxChargeP = p.powerWatts
            } else if (p.powerWatts < 0f) {
                val absP = abs(p.powerWatts)
                if (absP > maxDischargeP) maxDischargeP = absP
            }
            if (p.temperature > 0f) {
                if (p.temperature < minT) minT = p.temperature
                if (p.temperature > maxT) maxT = p.temperature
            }
            if (p.voltageVolts > 0.5f) {
                if (p.voltageVolts < minVolt) minVolt = p.voltageVolts
                if (p.voltageVolts > maxVolt) maxVolt = p.voltageVolts
            }
        }
        val hasCharge = maxChargeP > 0.05f
        val hasDischarge = maxDischargeP > 0.05f

        val safeMaxChargeP = (maxChargeP * 1.15f).coerceAtLeast(5f)
        val safeMaxDischargeP = (maxDischargeP * 1.15f).coerceAtLeast(3f)

        val safeMinT = if (minT < Float.MAX_VALUE) minT - 1f else 15f
        val safeMaxT = if (maxT > Float.MIN_VALUE) (maxT + 1f).coerceAtLeast(safeMinT + 2f) else 45f
        val tempRange = (safeMaxT - safeMinT).coerceAtLeast(1f)

        val safeMinVolt = if (minVolt < Float.MAX_VALUE) minVolt * 0.98f else 3.4f
        val safeMaxVolt = if (maxVolt > Float.MIN_VALUE) maxVolt * 1.02f else 4.5f
        val voltRange = (safeMaxVolt - safeMinVolt).coerceAtLeast(0.1f)

        // 功率波段在归一化纵向坐标系中的范围（0.02 ~ 0.45）
        val powerBandBottom = 0.02f
        val powerBandTop = 0.45f

        // 计算 0W 基准线位置：若全为充电锚定在底部，若全为放电锚定在顶部，兼具时根据充放电最大功率比例分配
        val zeroPowerNorm = when {
            hasCharge && !hasDischarge -> powerBandBottom + 0.01f
            !hasCharge && hasDischarge -> powerBandTop - 0.03f
            hasCharge && hasDischarge -> {
                val ratio = safeMaxDischargeP / (safeMaxChargeP + safeMaxDischargeP)
                (powerBandBottom + ratio * (powerBandTop - powerBandBottom)).coerceIn(0.10f, 0.32f)
            }
            else -> powerBandBottom + 0.01f
        }
        val zeroPowerY = chartTop + chartHeight * (1f - zeroPowerNorm)

        // 4. 计算各曲线在屏幕上的防重叠纵向分层映射坐标点（完全复用对象池，零堆内存分配）
        powerPoints.clear()
        levelPoints.clear()
        tempPoints.clear()
        voltagePoints.clear()

        for (i in dataPoints.indices) {
            val p = dataPoints[i]
            val x = if (dataPoints.size == 1) {
                paddingLeft + chartWidth / 2f
            } else {
                val ratio = ((p.timestamp - minTs).toFloat() / tsRange).coerceIn(0f, 1f)
                paddingLeft + chartWidth * ratio
            }

            // 电量曲线：映射至顶部区间 (0.05 ~ 0.45)，防止与中间温度和底部功率重叠
            val levelNorm = (p.batteryLevel / 100f).coerceIn(0f, 1f) * 0.40f + 0.55f
            val levelY = chartTop + chartHeight * (1f - levelNorm)
            levelPoints.add(obtainPointF(levelPointPool, i, x, levelY))

            // 温度曲线：映射至中层区间 (0.35 ~ 0.65)
            val tempNorm = if (p.temperature > 0f) {
                (((p.temperature - safeMinT) / tempRange).coerceIn(0f, 1f) * 0.30f + 0.35f)
            } else {
                0.35f
            }
            val tempY = chartTop + chartHeight * (1f - tempNorm)
            tempPoints.add(obtainPointF(tempPointPool, i, x, tempY))

            // 电压曲线：映射至区间 (0.22 ~ 0.52)，黄色平滑呈现电池端电压动态走势
            val voltNorm = if (p.voltageVolts > 0.5f) {
                (((p.voltageVolts - safeMinVolt) / voltRange).coerceIn(0f, 1f) * 0.30f + 0.22f)
            } else {
                0.22f
            }
            val voltY = chartTop + chartHeight * (1f - voltNorm)
            voltagePoints.add(obtainPointF(voltagePointPool, i, x, voltY))

            // 功率曲线：充电(>=0W)向上延展至功率上限；放电(<0W)向下延展，耗电越大越往下走
            val powerNorm = if (p.powerWatts >= 0f) {
                val ratio = (p.powerWatts / safeMaxChargeP).coerceIn(0f, 1f)
                zeroPowerNorm + ratio * (powerBandTop - zeroPowerNorm)
            } else {
                val ratio = (abs(p.powerWatts) / safeMaxDischargeP).coerceIn(0f, 1f)
                zeroPowerNorm - ratio * (zeroPowerNorm - powerBandBottom)
            }
            val powerY = chartTop + chartHeight * (1f - powerNorm)
            powerPoints.add(obtainPointF(powerPointPool, i, x, powerY))
        }

        // 当存在放电数据时，绘制 0W 辅助基准虚线与标识
        if (hasDischarge) {
            gridPath.reset()
            gridPath.moveTo(paddingLeft, zeroPowerY)
            gridPath.lineTo(w - paddingRight, zeroPowerY)
            canvas.drawPath(gridPath, zeroLinePaint)
            canvas.drawText("0W", paddingLeft + dp2, zeroPowerY - dp2, zeroTextPaint)
        }

        // 5. 根据充放电分布动态配置功率折线画笔颜色与渐变
        if (hasCharge && hasDischarge) {
            val pTopY = chartTop + chartHeight * (1f - powerBandTop)
            val pBottomY = chartTop + chartHeight * (1f - powerBandBottom)
            val zeroFrac = ((zeroPowerY - pTopY) / (pBottomY - pTopY)).coerceIn(0.01f, 0.99f)
            powerPaint.shader = android.graphics.LinearGradient(
                0f, pTopY, 0f, pBottomY,
                intArrayOf(colorPowerCharge, colorPowerCharge, colorPowerDischarge, colorPowerDischarge),
                floatArrayOf(0f, zeroFrac, zeroFrac, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
        } else if (!hasCharge && hasDischarge) {
            powerPaint.shader = null
            powerPaint.color = colorPowerDischarge
        } else {
            powerPaint.shader = null
            powerPaint.color = colorPowerCharge
        }

        // 直线绘制四色折线（功率、电量、温度、电压），根据选中的指标动态显示/隐藏，并执行图表内部区域裁剪保护
        canvas.save()
        canvas.clipRect(paddingLeft, chartTop, w - paddingRight, chartBottom)
        if (selectedMetrics.contains(TimelineMetric.POWER)) {
            drawStraightLine(canvas, powerPoints, powerPath, powerPaint)
        }
        if (selectedMetrics.contains(TimelineMetric.BATTERY)) {
            drawStraightLine(canvas, levelPoints, levelPath, levelPaint)
        }
        if (selectedMetrics.contains(TimelineMetric.TEMPERATURE)) {
            drawStraightLine(canvas, tempPoints, tempPath, tempPaint)
        }
        if (selectedMetrics.contains(TimelineMetric.VOLTAGE)) {
            drawStraightLine(canvas, voltagePoints, voltagePath, voltagePaint)
        }
        canvas.restore()

        // 6. 在曲线的关键位置绘制峰谷值小数字
        drawPeakAndValleyBadges(canvas, chartTop, chartBottom, paddingLeft, w - paddingRight)

        // 7. 绘制图表底部横向亮屏/息屏指示条（绿色表示亮屏，红色表示息屏待机）
        drawScreenOnOffIndicator(canvas, paddingLeft, chartWidth, chartBottom)

        // 7.5. 绘制充电期间活跃的前台应用小图标紧凑堆叠
        if (selectedMetrics.contains(TimelineMetric.APP)) {
            drawAppIconStacks(canvas, paddingLeft, chartWidth, chartBottom)
        }

        // 8. 绘制 X 轴时间刻度线与时间刻度文字（严格对应时间轴刻度居中显示）
        val timeStepCount = 4
        for (step in 0..timeStepCount) {
            val ratio = step.toFloat() / timeStepCount
            val tickX = paddingLeft + chartWidth * ratio
            val tickTs = minTs + (tsRange * ratio).toLong()
            val timeText = axisTimeFormatter.format(Date(tickTs))

            // 垂直虚线网格
            gridPath.reset()
            gridPath.moveTo(tickX, chartTop)
            gridPath.lineTo(tickX, chartBottom)
            canvas.drawPath(gridPath, gridPaint)

            // 刻度小短线
            canvas.drawLine(tickX, chartBottom, tickX, chartBottom + dp2, gridPaint)

            // 文字以刻度点 tickX 为中心严格居中对齐，并在左右屏幕物理边缘做防截断保护
            val textWidth = axisTextPaint.measureText(timeText)
            val halfW = textWidth / 2f
            val minTextX = halfW + dp2
            val maxTextX = maxOf(minTextX, w - halfW - dp2)
            val textX = tickX.coerceIn(minTextX, maxTextX)
            canvas.drawText(timeText, textX, h - dp5, axisTextPaint)
        }

        // 9. 绘制触控标尺（取消浮动弹框，仅保留垂直十字标尺线与曲线上高亮点）
        if (isTouching && selectedIndex in dataPoints.indices) {
            drawTouchRuler(
                canvas = canvas,
                chartTop = chartTop,
                chartHeight = chartHeight
            )
        }
    }

    /**
     * 绘制图表底部横向亮屏/息屏指示条（绿色表示亮屏，红色表示息屏，整条连续平滑一体无分段断裂缝隙）。
     *
     * @param canvas 绘制画布
     * @param chartLeft 图表左边界 X 坐标
     * @param chartWidth 图表净宽
     * @param gridBottomY 图表主网格底线 Y 坐标
     */
    private fun drawScreenOnOffIndicator(
        canvas: Canvas,
        chartLeft: Float,
        chartWidth: Float,
        gridBottomY: Float
    ) {
        if (dataPoints.isEmpty() || powerPoints.isEmpty()) return

        val barTop = gridBottomY + dp2
        val barBottom = barTop + dp3
        val cornerRadius = dp1_5

        val n = dataPoints.size
        // 1. 裁剪整个长条底线的外轮廓圆角矩形，保证两端圆润而内部各状态区间无缝连接
        screenBarPath.reset()
        screenBarPath.addRoundRect(
            chartLeft,
            barTop,
            chartLeft + chartWidth,
            barBottom,
            cornerRadius,
            cornerRadius,
            Path.Direction.CW
        )

        canvas.save()
        canvas.clipPath(screenBarPath)

        // 2. 将相邻相同状态的采样点合并为一个连续完整色块，彻底消除逐点画圆角带来的分段与黑缝隙
        var curStatus = dataPoints[0].isScreenOn
        var segStartX = chartLeft

        for (i in 1 until n) {
            val pt = dataPoints[i]
            if (pt.isScreenOn != curStatus) {
                val segEndX = if (i < powerPoints.size) {
                    (powerPoints[i - 1].x + powerPoints[i].x) / 2f
                } else {
                    chartLeft + chartWidth * (i.toFloat() / n)
                }
                val paint = if (curStatus) screenOnBarPaint else screenOffBarPaint
                canvas.drawRect(segStartX, barTop, segEndX, barBottom, paint)
                curStatus = pt.isScreenOn
                segStartX = segEndX
            }
        }

        // 绘制末尾最后一段连续区间
        val lastPaint = if (curStatus) screenOnBarPaint else screenOffBarPaint
        canvas.drawRect(segStartX, barTop, chartLeft + chartWidth, barBottom, lastPaint)

        canvas.restore()
    }

    /**
     * 绘制充电期间活跃的前台应用小图标按时间槽平铺与多行纵向堆叠排布。
     * 采用与耗电趋势图完全一致的分槽对齐与按包名去重算法，同一时间段内同一 App 绝不重复堆叠，
     * 多个不同应用并发时自底向上垂直堆叠，持续使用时水平时间槽连续平铺；
     * 若超出纵向最大可用显示行数，最顶层自动呈现微型 "+N" 徽章提示存在更多应用。
     *
     * @param canvas 绘制画布
     * @param chartLeft 图表左边界 X 坐标
     * @param chartWidth 图表净宽
     * @param gridBottomY 图表主网格底线 Y 坐标
     */
    private fun drawAppIconStacks(
        canvas: Canvas,
        chartLeft: Float,
        chartWidth: Float,
        gridBottomY: Float
    ) {
        if (appEvents.isEmpty() || dataPoints.isEmpty()) return

        val minTs = dataPoints.first().timestamp
        val maxTs = if (dataPoints.size > 1) maxOf(dataPoints.last().timestamp, minTs + 60000L) else (minTs + 60000L)
        if (maxTs <= minTs) return

        val slotSizePx = dp11
        val iconRenderSize = dp11.toInt().coerceAtLeast(1)
        val baseBottomY = gridBottomY + dp2
        val topLimitY = dp50
        val availableHeight = (baseBottomY - topLimitY).coerceAtLeast(slotSizePx)
        val maxDisplayRows = (availableHeight / slotSizePx).toInt().coerceAtLeast(1)

        // 计算或复用布局排布结果，避免手势探查重绘时重复执行分槽计算
        val currentEventsHash = appEvents.hashCode()
        if (cachedSlotItems.isEmpty() ||
            lastSlotCalcMinTs != minTs ||
            lastSlotCalcMaxTs != maxTs ||
            lastSlotCalcWidth != chartWidth ||
            lastSlotCalcEventsHash != currentEventsHash
        ) {
            cachedSlotItems = TimelineLayoutCalculator.calculateSlotItems(
                events = appEvents,
                visibleStartTs = minTs,
                visibleEndTs = maxTs,
                canvasWidth = chartWidth,
                baseBottomY = baseBottomY,
                slotSizePx = slotSizePx,
                slotGapPx = 0f,
                rowGapPx = 0f,
                maxRows = maxDisplayRows,
                leftMarginPx = chartLeft
            )
            lastSlotCalcMinTs = minTs
            lastSlotCalcMaxTs = maxTs
            lastSlotCalcWidth = chartWidth
            lastSlotCalcEventsHash = currentEventsHash
        }

        for (item in cachedSlotItems) {
            // 向上堆叠边界保护，防止超出图表上方读数面板
            if (item.top < topLimitY) continue

            if (item.overflowCount > 0) {
                // 绘制顶层微型 +N 溢出折叠角标
                drawOverflowBadge(canvas, item)
            } else {
                val event = item.event
                val bmp = DrawableBitmapCache.getOrLoadBitmap(
                    context,
                    event.packageName,
                    iconRenderSize,
                    event.icon
                )
                if (bmp != null && !bmp.isRecycled) {
                    iconSrcRect.set(0, 0, bmp.width, bmp.height)
                    iconDstRect.set(
                        item.left,
                        item.top,
                        item.right,
                        item.bottom
                    )
                    canvas.drawBitmap(bmp, iconSrcRect, iconDstRect, null)
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
        iconDstRect.set(item.left, item.top, item.right, item.bottom)
        canvas.drawRoundRect(iconDstRect, dp2, dp2, overflowBadgeBgPaint)
        canvas.drawRoundRect(iconDstRect, dp2, dp2, overflowBadgeStrokePaint)

        val text = "+${item.overflowCount}"
        val textY = item.centerY - (overflowTextPaint.descent() + overflowTextPaint.ascent()) / 2f
        canvas.drawText(text, item.centerX, textY, overflowTextPaint)
    }

    /**
     * 在图表最顶部固定区域绘制读数指示看板。
     * 时间靠左对齐距左 10dp，应用图标靠右对齐距右 10dp，中间 4 个指标（电量、功率、温度、电压）等比例 4 等分居中排布（无分隔符，无中文字符前缀）。
     *
     * @param canvas 绘制画布 [Canvas]
     * @param w 视图总宽度（像素）
     * @param paddingLeft 图表左内边距（像素）
     * @param paddingRight 图表右内边距（像素）
     */
    private fun drawFixedTopHeader(canvas: Canvas, w: Float, paddingLeft: Float, paddingRight: Float) {
        val headerTop = dp2
        val headerBottom = dp24
        headerRect.set(paddingLeft, headerTop, w - paddingRight, headerBottom)

        // 绘制轻微圆角卡片背景与外框
        canvas.drawRoundRect(headerRect, dp4, dp4, headerBgPaint)
        canvas.drawRoundRect(headerRect, dp4, dp4, headerBorderPaint)

        if (dataPoints.isEmpty()) {
            headerTimePaint.textAlign = Paint.Align.CENTER
            headerTimePaint.color = Color.parseColor("#888888")
            val textY = headerTop + (headerBottom - headerTop) / 2f - (headerTimePaint.descent() + headerTimePaint.ascent()) / 2f
            canvas.drawText("等待充电数据采样...", w / 2f, textY, headerTimePaint)
            return
        }

        val point = if (isTouching && selectedIndex in dataPoints.indices) {
            dataPoints[selectedIndex]
        } else {
            dataPoints.last()
        }

        val curTs = point.timestamp
        val curApp = appEvents.find { it.startTime <= curTs && it.endTime >= curTs } ?: appEvents.lastOrNull()

        val timeStr = timeFormatter.format(Date(curTs))
        val levelStr = "${point.batteryLevel}%"
        val pWatts = point.powerWatts
        val pLabel = if (pWatts >= 0f) {
            String.format(Locale.getDefault(), "+%.2fW", pWatts)
        } else {
            String.format(Locale.getDefault(), "-%.2fW", abs(pWatts))
        }
        val pColor = if (pWatts >= 0f) colorPowerCharge else colorPowerDischarge
        val tempStr = String.format(Locale.getDefault(), "%.1f℃", point.temperature)
        val voltStr = if (point.voltageVolts > 0.5f) {
            String.format(Locale.getDefault(), "%.3fV", point.voltageVolts)
        } else {
            "--"
        }

        val textY = headerTop + (headerBottom - headerTop) / 2f - (headerTimePaint.descent() + headerTimePaint.ascent()) / 2f
        val paddingH = dp5
        val iconSize = dp12

        // 1. 绘制时间（靠左对齐，距左 10dp）
        headerTimePaint.textAlign = Paint.Align.LEFT
        headerTimePaint.color = Color.parseColor("#9E9E9E")
        canvas.drawText(timeStr, paddingLeft + paddingH, textY, headerTimePaint)
        val timeWidth = headerTimePaint.measureText(timeStr)

        // 2. 绘制应用图标（靠右对齐，距右 10dp）
        val iconLeft = w - paddingRight - paddingH - iconSize
        val iconTop = headerTop + (headerBottom - headerTop - iconSize) / 2f
        if (curApp != null) {
            val renderIconSize = iconSize.toInt().coerceAtLeast(1)
            val bmp = DrawableBitmapCache.getOrLoadBitmap(context, curApp.packageName, renderIconSize, curApp.icon)
            if (bmp != null && !bmp.isRecycled) {
                iconSrcRect.set(0, 0, bmp.width, bmp.height)
                iconDstRect.set(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
                canvas.drawBitmap(bmp, iconSrcRect, iconDstRect, bitmapPaint)
            }
        }

        // 3. 中间 4 个指标（电量、功率、温度、电压）在时间与图标之间按比例等分居中排布（无分隔符，无中文字符前缀）
        val middleLeft = paddingLeft + paddingH + timeWidth + dp6
        val middleRight = w - paddingRight - paddingH - iconSize - dp6
        val middleWidth = maxOf(0f, middleRight - middleLeft)
        val colWidth = middleWidth / 4f

        headerMetricValuePaint.textAlign = Paint.Align.CENTER

        // 绘制电量（深天蓝 #3A7FF0，第 1 列居中）
        headerMetricValuePaint.color = colorLevel
        canvas.drawText(levelStr, middleLeft + colWidth * 0.5f, textY, headerMetricValuePaint)

        // 绘制功率（充电浅天蓝 #90CAF9 / 放电橙色 #FF9800，第 2 列居中）
        headerMetricValuePaint.color = pColor
        canvas.drawText(pLabel, middleLeft + colWidth * 1.5f, textY, headerMetricValuePaint)

        // 绘制温度（红色 #FF5252，第 3 列居中）
        headerMetricValuePaint.color = colorTemp
        canvas.drawText(tempStr, middleLeft + colWidth * 2.5f, textY, headerMetricValuePaint)

        // 绘制电压（金黄 #FFD54F，第 4 列居中）
        headerMetricValuePaint.color = colorVoltage
        canvas.drawText(voltStr, middleLeft + colWidth * 3.5f, textY, headerMetricValuePaint)
    }

    /**
     * 将点集以直线线段逐点连接并绘制折线。
     *
     * @param canvas 画布
     * @param points 待绘制的屏幕坐标点集
     * @param path 折线复用 Path
     * @param paint 绘制画笔
     */
    private fun drawStraightLine(canvas: Canvas, points: List<PointF>, path: Path, paint: Paint) {
        if (points.isEmpty()) return
        path.reset()
        path.moveTo(points[0].x, points[0].y)

        if (points.size == 1) {
            canvas.drawCircle(points[0].x, points[0].y, dp3, paint)
            return
        }

        for (i in 1 until points.size) {
            path.lineTo(points[i].x, points[i].y)
        }
        canvas.drawPath(path, paint)
    }

    /**
     * 计算并绘制功率、电量与温度三条走势曲线上的峰值（Max）和谷值（Min）小数字。
     * 功率维度自动区分充电峰值（绿色正数）与放电峰值（橙色负数）。
     *
     * @param canvas 画布
     * @param chartTop 图表绘制有效区顶部
     * @param chartBottom 图表绘制有效区底部
     * @param chartLeft 图表绘制有效区左边界
     * @param chartRight 图表绘制有效区右边界
     */
    private fun drawPeakAndValleyBadges(
        canvas: Canvas,
        chartTop: Float,
        chartBottom: Float,
        chartLeft: Float,
        chartRight: Float
    ) {
        if (dataPoints.isEmpty() || powerPoints.isEmpty()) return

        // 1. 功率曲线：寻找充电正向峰值/谷值与放电负向峰值/谷值
        if (selectedMetrics.contains(TimelineMetric.POWER)) {
            var maxPosPIdx = -1
            var maxPosPVal = 0f
            var minPosPIdx = -1
            var minPosPVal = Float.MAX_VALUE

            var maxNegPIdx = -1
            var maxNegPVal = 0f // 绝对值最大放电耗电点
            var minNegPIdx = -1
            var minNegPVal = Float.MAX_VALUE // 绝对值最小放电耗电点

            for (i in dataPoints.indices) {
                val p = dataPoints[i].powerWatts
                if (p >= 0f) {
                    if (p > maxPosPVal) {
                        maxPosPVal = p
                        maxPosPIdx = i
                    }
                    if (p < minPosPVal) {
                        minPosPVal = p
                        minPosPIdx = i
                    }
                } else {
                    val absP = abs(p)
                    if (absP > maxNegPVal) {
                        maxNegPVal = absP
                        maxNegPIdx = i
                    }
                    if (absP < minNegPVal) {
                        minNegPVal = absP
                        minNegPIdx = i
                    }
                }
            }

            // 绘制充电正向最高功率峰值（波峰向上）
            if (maxPosPIdx >= 0 && maxPosPVal > 0.05f) {
                val peakPowerText = String.format(Locale.getDefault(), "+%.1fW", maxPosPVal)
                val peakPPoint = powerPoints[maxPosPIdx]
                drawSingleBadge(canvas, peakPowerText, peakPPoint.x, peakPPoint.y, colorPowerCharge, true, chartTop, chartBottom, chartLeft, chartRight)

                // 若全为充电点且存在明显落差，绘制充电低谷点
                if (maxNegPIdx < 0 && minPosPIdx >= 0 && minPosPIdx != maxPosPIdx && (maxPosPVal - minPosPVal) >= 0.5f) {
                    val valleyPowerText = String.format(Locale.getDefault(), "+%.1fW", minPosPVal)
                    val valleyPPoint = powerPoints[minPosPIdx]
                    drawSingleBadge(canvas, valleyPowerText, valleyPPoint.x, valleyPPoint.y, colorPowerCharge, false, chartTop, chartBottom, chartLeft, chartRight)
                }
            }

            // 绘制放电负向最大耗电点（波谷向下）
            if (maxNegPIdx >= 0 && maxNegPVal > 0.05f) {
                val maxDrainText = String.format(Locale.getDefault(), "-%.1fW", maxNegPVal)
                val maxDrainPoint = powerPoints[maxNegPIdx]
                drawSingleBadge(canvas, maxDrainText, maxDrainPoint.x, maxDrainPoint.y, colorPowerDischarge, false, chartTop, chartBottom, chartLeft, chartRight)

                // 若全为放电点且存在明显落差，绘制最小耗电点
                if (maxPosPIdx < 0 && minNegPIdx >= 0 && minNegPIdx != maxNegPIdx && (maxNegPVal - minNegPVal) >= 0.5f) {
                    val minDrainText = String.format(Locale.getDefault(), "-%.1fW", minNegPVal)
                    val minDrainPoint = powerPoints[minNegPIdx]
                    drawSingleBadge(canvas, minDrainText, minDrainPoint.x, minDrainPoint.y, colorPowerDischarge, true, chartTop, chartBottom, chartLeft, chartRight)
                }
            }
        }

        // 2. 电量曲线峰谷值寻找与绘制
        if (selectedMetrics.contains(TimelineMetric.BATTERY) && levelPoints.isNotEmpty()) {
            var maxLIdx = 0
            var minLIdx = 0
            var maxLVal = dataPoints[0].batteryLevel
            var minLVal = dataPoints[0].batteryLevel

            for (i in dataPoints.indices) {
                val lvl = dataPoints[i].batteryLevel
                if (lvl > maxLVal) {
                    maxLVal = lvl
                    maxLIdx = i
                }
                if (lvl < minLVal) {
                    minLVal = lvl
                    minLIdx = i
                }
            }

            // 绘制最高电量
            val peakLevelText = "$maxLVal%"
            val peakLPoint = levelPoints[maxLIdx]
            drawSingleBadge(canvas, peakLevelText, peakLPoint.x, peakLPoint.y, colorLevel, true, chartTop, chartBottom, chartLeft, chartRight)

            // 若起止电量有增长且不是同一个点，绘制起始/最低电量
            if (maxLIdx != minLIdx && maxLVal != minLVal) {
                val valleyLevelText = "$minLVal%"
                val valleyLPoint = levelPoints[minLIdx]
                drawSingleBadge(canvas, valleyLevelText, valleyLPoint.x, valleyLPoint.y, colorLevel, false, chartTop, chartBottom, chartLeft, chartRight)
            }
        }

        // 3. 温度曲线峰谷值寻找与绘制
        if (selectedMetrics.contains(TimelineMetric.TEMPERATURE) && tempPoints.isNotEmpty()) {
            var maxTIdx = 0
            var minTIdx = 0
            var maxTVal = dataPoints[0].temperature
            var minTVal = dataPoints[0].temperature

            for (i in dataPoints.indices) {
                val t = dataPoints[i].temperature
                if (t > maxTVal) {
                    maxTVal = t
                    maxTIdx = i
                }
                if (t < minTVal) {
                    minTVal = t
                    minTIdx = i
                }
            }

            // 绘制最高温度
            val peakTempText = String.format(Locale.getDefault(), "%.1f℃", maxTVal)
            val peakTPoint = tempPoints[maxTIdx]
            drawSingleBadge(canvas, peakTempText, peakTPoint.x, peakTPoint.y, colorTemp, true, chartTop, chartBottom, chartLeft, chartRight)

            // 若最高与最低温度有温差且不是同一个点，绘制最低温度
            if (maxTIdx != minTIdx && abs(maxTVal - minTVal) >= 0.5f) {
                val valleyTempText = String.format(Locale.getDefault(), "%.1f℃", minTVal)
                val valleyTPoint = tempPoints[minTIdx]
                drawSingleBadge(canvas, valleyTempText, valleyTPoint.x, valleyTPoint.y, colorTemp, false, chartTop, chartBottom, chartLeft, chartRight)
            }
        }
    }

    /**
     * 在指定屏幕坐标点附近绘制带有光晕描边的小数字标签，具备边界防溢出保护。
     *
     * @param canvas 画布
     * @param text 小数字标签字符串
     * @param pointX 数据点 X 坐标
     * @param pointY 数据点 Y 坐标
     * @param textColor 文本主色
     * @param isPeak 是否为波峰最高点（true: 默认在点上方, false: 默认在点下方）
     * @param chartTop 绘图区上边界
     * @param chartBottom 绘图区下边界
     * @param chartLeft 绘图区左边界
     * @param chartRight 绘图区右边界
     */
    private fun drawSingleBadge(
        canvas: Canvas,
        text: String,
        pointX: Float,
        pointY: Float,
        textColor: Int,
        isPeak: Boolean,
        chartTop: Float,
        chartBottom: Float,
        chartLeft: Float,
        chartRight: Float
    ) {
        badgeTextPaint.color = textColor
        val textWidth = badgeTextPaint.measureText(text)
        val halfW = textWidth / 2f

        // 水平防溢出
        val minDrawX = chartLeft + halfW + dp2
        val maxDrawX = maxOf(minDrawX, chartRight - halfW - dp2)
        val drawX = pointX.coerceIn(minDrawX, maxDrawX)

        // 垂直排版：峰值位于点上方，谷值位于点下方；如贴近边界则智能翻转
        val drawY = if (isPeak) {
            if (pointY - dp8 < chartTop) {
                pointY + dp12
            } else {
                pointY - dp4
            }
        } else {
            if (pointY + dp12 > chartBottom) {
                pointY - dp4
            } else {
                pointY + dp10
            }
        }

        // 双层绘制：外层微暗描边（Halo 光晕）+ 内层主色文本，确保跨曲线时依然清晰
        canvas.drawText(text, drawX, drawY, badgeHaloPaint)
        canvas.drawText(text, drawX, drawY, badgeTextPaint)
    }

    /**
     * 绘制手势滑动时的垂直标尺指示线与各曲线上高亮点（取消跟随手指浮动的气泡卡片）。
     *
     * @param canvas 画布
     * @param chartTop 图表内容起始顶部
     * @param chartHeight 图表实际内容高度
     */
    private fun drawTouchRuler(
        canvas: Canvas,
        chartTop: Float,
        chartHeight: Float
    ) {
        val pX = powerPoints[selectedIndex].x

        // 垂直虚线十字标尺
        gridPath.reset()
        gridPath.moveTo(pX, chartTop)
        gridPath.lineTo(pX, chartTop + chartHeight)
        canvas.drawPath(gridPath, cursorPaint)

        // 各曲线上高亮圆圈打点（外层彩色光环 + 内层白色圆心）
        val powerY = powerPoints[selectedIndex].y
        val levelY = levelPoints[selectedIndex].y
        val tempY = tempPoints[selectedIndex].y

        val curPoint = dataPoints[selectedIndex]
        val pColor = if (curPoint.powerWatts >= 0f) colorPowerCharge else colorPowerDischarge

        if (selectedMetrics.contains(TimelineMetric.POWER) && powerPoints.isNotEmpty() && selectedIndex in powerPoints.indices) {
            drawHighLightDot(canvas, pX, powerY, pColor)
        }
        if (selectedMetrics.contains(TimelineMetric.BATTERY) && levelPoints.isNotEmpty() && selectedIndex in levelPoints.indices) {
            drawHighLightDot(canvas, pX, levelY, colorLevel)
        }
        if (selectedMetrics.contains(TimelineMetric.TEMPERATURE) && tempPoints.isNotEmpty() && selectedIndex in tempPoints.indices) {
            drawHighLightDot(canvas, pX, tempY, colorTemp)
        }
        if (selectedMetrics.contains(TimelineMetric.VOLTAGE) && voltagePoints.isNotEmpty() && selectedIndex in voltagePoints.indices) {
            val voltY = voltagePoints[selectedIndex].y
            drawHighLightDot(canvas, pX, voltY, colorVoltage)
        }

        // 当触控命中存在 +N 溢出应用的时间槽时，在顶部固定探查看板下方显示纯图标气泡卡片
        drawTouchTooltip(canvas, pX)
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
        if (!selectedMetrics.contains(TimelineMetric.APP) || cachedSlotItems.isEmpty()) return

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
        tooltipRect.set(boxLeft, boxTop, boxLeft + boxWidth, boxTop + boxHeight)
        canvas.drawRoundRect(tooltipRect, dp4, dp4, tooltipBgPaint)
        canvas.drawRoundRect(tooltipRect, dp4, dp4, tooltipBorderPaint)

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
                iconSrcRect.set(0, 0, bmp.width, bmp.height)
                iconDstRect.set(
                    curIconLeft,
                    curIconTop,
                    curIconLeft + iconSize,
                    curIconTop + iconSize
                )
                canvas.drawBitmap(bmp, iconSrcRect, iconDstRect, null)
            }
        }
    }

    /**
     * 在指定坐标点绘制高亮光环指示圆点。
     *
     * @param canvas 画布
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
     * 视图从窗口脱附时的生命周期回调，安全移除长按定时任务并重置触控状态。
     */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelLongPressTimer()
        if (isTouching) {
            isTouching = false
            pointSelectedListener?.onPointSelected(null)
        }
    }

    /**
     * 处理用户手势交互事件。
     * 严格遵循“图表中的触摸事件只有在长按时触发”的交互规范：
     * 1. 手指按下时不拦截父容器，启动长按倒计时，确保列表垂直滚动具备最高优先级；
     * 2. 未长按状态下，手指发生任何移动（dx > touchSlop || dy > touchSlop）立即取消长按定时器并完全放行给列表滚动，绝对不误触图表；
     * 3. 只有当用户按住不动达到长按门限后，才激活标尺探查并锁定父容器，跟随手指横向探查数据；
     * 4. 手指抬起或取消时重置长按状态并释放父容器。
     *
     * @param event 触摸手势事件 [MotionEvent]
     * @return 消耗事件返回 true，放行返回 false
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (dataPoints.isEmpty()) return super.onTouchEvent(event)

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
                // 若标尺已长按激活，跟随手指横向移动更新选点与顶部看板
                if (isTouching) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    touchX = event.x
                    touchY = event.y
                    findClosestIndex(touchX)
                    invalidate()
                    return true
                }

                if (isTouchMoved) {
                    return false
                }

                // 未长按状态下：检测位移。只要手指发生任何有效移动，立即彻底取消长按判定并完全放行给列表滚动
                val totalDx = abs(event.x - touchDownX)
                val totalDy = abs(event.y - touchDownY)
                if (totalDx > touchSlop || totalDy > touchSlop) {
                    isTouchMoved = true
                    cancelLongPressTimer()
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return false
                }

                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelLongPressTimer()
                if (isTouching) {
                    isTouching = false
                    touchY = 0f
                    parent?.requestDisallowInterceptTouchEvent(false)
                    pointSelectedListener?.onPointSelected(null)
                    invalidate()
                    return true
                }
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return isTouching || super.onTouchEvent(event)
    }

    /**
     * 根据触摸横坐标二分或就近查找距离最近的采样数据点索引并触发回调。
     *
     * @param targetX 触摸 X 物理像素坐标
     */
    private fun findClosestIndex(targetX: Float) {
        if (powerPoints.isEmpty()) {
            selectedIndex = -1
            pointSelectedListener?.onPointSelected(null)
            return
        }
        var minDiff = Float.MAX_VALUE
        var bestIndex = 0
        for (i in powerPoints.indices) {
            val diff = abs(powerPoints[i].x - targetX)
            if (diff < minDiff) {
                minDiff = diff
                bestIndex = i
            }
        }
        selectedIndex = bestIndex
        if (selectedIndex in dataPoints.indices) {
            pointSelectedListener?.onPointSelected(dataPoints[selectedIndex])
        }
    }

    /**
     * 将 dp 数值转换为物理像素 px。
     *
     * @param dp 密度无关像素值
     * @return 物理像素值
     */
    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density

    /**
     * 将 sp 数值转换为物理像素 px。
     *
     * @param sp 可缩放像素值
     * @return 物理像素值
     */
    private fun spToPx(sp: Float): Float = sp * resources.displayMetrics.scaledDensity
}
