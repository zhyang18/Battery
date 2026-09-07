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
import android.view.MotionEvent
import android.view.View
import com.battery.analysis.model.ChargingSamplePoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * 充电过程三合一（功率、电量、温度）动态折线图自定义控件。
 * 在同一图表内采用三种不同高辨识度颜色平滑曲线呈现充电瞬时功率、电池电量与电池温度，
 * 支持动态采样实时绘制、曲线峰谷值小数字标注、顶部固定探查看板与手势标尺探查。
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
    private val dp1 = dpToPx(1f)
    private val dp2 = dpToPx(2f)
    private val dp2_2 = dpToPx(2.2f)
    private val dp3 = dpToPx(3f)
    private val dp4 = dpToPx(4f)
    private val dp5 = dpToPx(5f)
    private val dp6 = dpToPx(6f)
    private val dp8 = dpToPx(8f)
    private val dp10 = dpToPx(10f)
    private val dp12 = dpToPx(12f)
    private val dp14 = dpToPx(14f)
    private val dp16 = dpToPx(16f)
    private val dp22 = dpToPx(22f)
    private val dp24 = dpToPx(24f)
    private val dp28 = dpToPx(28f)
    private val dp30 = dpToPx(30f)

    private val sp9 = spToPx(9f)
    private val sp9_5 = spToPx(9.5f)
    private val sp10 = spToPx(10f)
    private val sp11 = spToPx(11f)

    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val axisTimeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())

    // 颜色配置（绿色: 功率, 蓝色: 电量, 红色: 温度）
    val colorPower = Color.parseColor("#00C853")
    val colorLevel = Color.parseColor("#2196F3")
    val colorTemp = Color.parseColor("#FF5252")

    // 绘制画笔：功率曲线
    private val powerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp2_2
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = colorPower
    }

    // 绘制画笔：电量曲线
    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp2_2
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = colorLevel
    }

    // 绘制画笔：温度曲线
    private val tempPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp2_2
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = colorTemp
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
    private val headerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp10
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#888888")
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
    private val gridPath = Path()
    private val headerRect = RectF()

    // 手势交互状态
    private var isTouching = false
    private var touchX = 0f
    private var selectedIndex = -1

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
     * 向现有采样数据集末尾实时追加单点并高效局部重绘。
     *
     * @param point 最新的采样物理点
     */
    fun appendPoint(point: ChargingSamplePoint) {
        dataPoints.add(point)
        if (dataPoints.size > 1500) {
            dataPoints.removeAt(0)
        }
        invalidate()
    }

    /**
     * 清空图表内所有走势数据并恢复空态。
     */
    fun clearData() {
        dataPoints.clear()
        powerPoints.clear()
        levelPoints.clear()
        tempPoints.clear()
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
        val chartBottom = h - dp22

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
            val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = sp11
                color = Color.parseColor("#757575")
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(emptyText, w / 2f, chartTop + chartHeight / 2f, emptyPaint)
            return
        }

        // 3. 统计各维度数据范围
        val minTs = dataPoints.first().timestamp
        val maxTs = if (dataPoints.size > 1) dataPoints.last().timestamp else (minTs + 60000L)
        val tsRange = (maxTs - minTs).coerceAtLeast(60000L).toFloat()

        var maxP = 20f
        var maxT = 45f
        for (p in dataPoints) {
            if (p.powerWatts > maxP) maxP = p.powerWatts
            if (p.temperature > maxT) maxT = p.temperature
        }
        maxP = (maxP * 1.15f).coerceAtLeast(10f)
        maxT = (maxT * 1.15f).coerceAtLeast(40f)

        // 4. 计算各曲线在屏幕上的映射坐标点
        powerPoints.clear()
        levelPoints.clear()
        tempPoints.clear()

        for (i in dataPoints.indices) {
            val p = dataPoints[i]
            val x = if (dataPoints.size == 1) {
                paddingLeft + chartWidth / 2f
            } else {
                paddingLeft + chartWidth * ((p.timestamp - minTs).toFloat() / tsRange)
            }

            // 电量曲线：0%~100% 映射
            val levelRatio = (p.batteryLevel / 100f).coerceIn(0f, 1f)
            val levelY = chartTop + chartHeight * (1f - levelRatio)
            levelPoints.add(PointF(x, levelY))

            // 功率曲线：0 ~ maxP 映射
            val powerRatio = (p.powerWatts / maxP).coerceIn(0f, 1f)
            val powerY = chartTop + chartHeight * (1f - powerRatio)
            powerPoints.add(PointF(x, powerY))

            // 温度曲线：20℃ ~ maxT 映射
            val tempRatio = ((p.temperature - 20f) / (maxT - 20f)).coerceIn(0f, 1f)
            val tempY = chartTop + chartHeight * (1f - tempRatio)
            tempPoints.add(PointF(x, tempY))
        }

        // 5. 平滑绘制三色曲线
        drawSmoothCurve(canvas, powerPoints, powerPath, powerPaint)
        drawSmoothCurve(canvas, levelPoints, levelPath, levelPaint)
        drawSmoothCurve(canvas, tempPoints, tempPath, tempPaint)

        // 6. 在曲线的关键位置绘制峰谷值小数字
        drawPeakAndValleyBadges(canvas, chartTop, chartBottom, paddingLeft, w - paddingRight)

        // 7. 绘制 X 轴起止时间刻度
        axisTextPaint.textAlign = Paint.Align.LEFT
        val startTimeText = axisTimeFormatter.format(Date(minTs))
        canvas.drawText(startTimeText, paddingLeft, h - dp6, axisTextPaint)

        axisTextPaint.textAlign = Paint.Align.RIGHT
        val endTimeText = axisTimeFormatter.format(Date(maxTs))
        canvas.drawText(endTimeText, w - paddingRight, h - dp6, axisTextPaint)

        // 8. 绘制触控标尺（取消浮动弹框，仅保留垂直十字标尺线与曲线上高亮点）
        if (isTouching && selectedIndex in dataPoints.indices) {
            drawTouchRuler(
                canvas = canvas,
                chartTop = chartTop,
                chartHeight = chartHeight
            )
        }
    }

    /**
     * 在图表最顶部固定区域绘制读数指示看板（触摸时显示探查点指标，非触摸时显示最新实时读数）。
     *
     * @param canvas 画布
     * @param w 视图总宽度
     * @param paddingLeft 左边距
     * @param paddingRight 右边距
     */
    private fun drawFixedTopHeader(canvas: Canvas, w: Float, paddingLeft: Float, paddingRight: Float) {
        val headerTop = dp2
        val headerBottom = dp24
        headerRect.set(paddingLeft, headerTop, w - paddingRight, headerBottom)

        // 绘制轻微圆角卡片背景与外框
        canvas.drawRoundRect(headerRect, dp4, dp4, headerBgPaint)
        canvas.drawRoundRect(headerRect, dp4, dp4, headerBorderPaint)

        if (dataPoints.isEmpty()) {
            headerTextPaint.color = Color.parseColor("#888888")
            val textY = headerTop + (headerBottom - headerTop) / 2f - (headerTextPaint.descent() + headerTextPaint.ascent()) / 2f
            canvas.drawText("等待充电数据采样...", w / 2f, textY, headerTextPaint)
            return
        }

        val point = if (isTouching && selectedIndex in dataPoints.indices) {
            dataPoints[selectedIndex]
        } else {
            dataPoints.last()
        }

        val timeStr = timeFormatter.format(Date(point.timestamp))
        val levelStr = "${point.batteryLevel}%"
        val powerStr = String.format(Locale.getDefault(), "%.2fW", point.powerWatts)
        val tempStr = String.format(Locale.getDefault(), "%.1f℃", point.temperature)

        val totalHeaderWidth = w - paddingLeft - paddingRight
        val colWidth = totalHeaderWidth / 4f
        val textY = headerTop + (headerBottom - headerTop) / 2f - (headerTextPaint.descent() + headerTextPaint.ascent()) / 2f

        // 列 1：时间（触摸状态加光标符号，非触摸状态加“实时”标识）
        headerTextPaint.textAlign = Paint.Align.CENTER
        headerTextPaint.color = if (isTouching) Color.parseColor("#B0BEC5") else Color.parseColor("#888888")
        val labelTime = if (isTouching) "探查 $timeStr" else "实时 $timeStr"
        canvas.drawText(labelTime, paddingLeft + colWidth * 0.5f, textY, headerTextPaint)

        // 列 2：电量（蓝色）
        headerTextPaint.color = colorLevel
        canvas.drawText("电量 $levelStr", paddingLeft + colWidth * 1.5f, textY, headerTextPaint)

        // 列 3：功率（绿色）
        headerTextPaint.color = colorPower
        canvas.drawText("功率 $powerStr", paddingLeft + colWidth * 2.5f, textY, headerTextPaint)

        // 列 4：温度（红色）
        headerTextPaint.color = colorTemp
        canvas.drawText("温度 $tempStr", paddingLeft + colWidth * 3.5f, textY, headerTextPaint)
    }

    /**
     * 采用三次贝塞尔平滑插值算法将点集连接成平滑曲线并绘制。
     *
     * @param canvas 画布
     * @param points 待绘制的屏幕坐标点集
     * @param path 曲线复用 Path
     * @param paint 绘制画笔
     */
    private fun drawSmoothCurve(canvas: Canvas, points: List<PointF>, path: Path, paint: Paint) {
        if (points.isEmpty()) return
        path.reset()
        path.moveTo(points[0].x, points[0].y)

        if (points.size == 1) {
            canvas.drawCircle(points[0].x, points[0].y, dp3, paint)
            return
        }

        for (i in 0 until points.size - 1) {
            val p0 = if (i > 0) points[i - 1] else points[i]
            val p1 = points[i]
            val p2 = points[i + 1]
            val p3 = if (i + 2 < points.size) points[i + 2] else p2

            val ctrl1X = p1.x + (p2.x - p0.x) * 0.18f
            val ctrl1Y = p1.y + (p2.y - p0.y) * 0.18f
            val ctrl2X = p2.x - (p3.x - p1.x) * 0.18f
            val ctrl2Y = p2.y - (p3.y - p1.y) * 0.18f

            path.cubicTo(ctrl1X, ctrl1Y, ctrl2X, ctrl2Y, p2.x, p2.y)
        }
        canvas.drawPath(path, paint)
    }

    /**
     * 计算并绘制功率、电量与温度三条走势曲线上的峰值（Max）和谷值（Min）小数字。
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

        // 1. 功率曲线峰谷值寻找与绘制
        var maxPIdx = 0
        var minPIdx = 0
        var maxPVal = dataPoints[0].powerWatts
        var minPVal = dataPoints[0].powerWatts

        for (i in dataPoints.indices) {
            val p = dataPoints[i].powerWatts
            if (p > maxPVal) {
                maxPVal = p
                maxPIdx = i
            }
            if (p < minPVal) {
                minPVal = p
                minPIdx = i
            }
        }

        // 绘制功率峰值
        val peakPowerText = String.format(Locale.getDefault(), "%.1fW", maxPVal)
        val peakPPoint = powerPoints[maxPIdx]
        drawSingleBadge(canvas, peakPowerText, peakPPoint.x, peakPPoint.y, colorPower, true, chartTop, chartBottom, chartLeft, chartRight)

        // 若峰谷值存在明显落差且不是同一个点，绘制功率谷值
        if (maxPIdx != minPIdx && abs(maxPVal - minPVal) >= 0.3f) {
            val valleyPowerText = String.format(Locale.getDefault(), "%.1fW", minPVal)
            val valleyPPoint = powerPoints[minPIdx]
            drawSingleBadge(canvas, valleyPowerText, valleyPPoint.x, valleyPPoint.y, colorPower, false, chartTop, chartBottom, chartLeft, chartRight)
        }

        // 2. 电量曲线峰谷值寻找与绘制
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

        // 3. 温度曲线峰谷值寻找与绘制
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
        val drawX = pointX.coerceIn(chartLeft + halfW + dp2, chartRight - halfW - dp2)

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

        drawHighLightDot(canvas, pX, powerY, colorPower)
        drawHighLightDot(canvas, pX, levelY, colorLevel)
        drawHighLightDot(canvas, pX, tempY, colorTemp)
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
     * 处理用户单指滑动探查图表手势。
     *
     * @param event 触摸手势事件
     * @return 消耗事件返回 true
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (dataPoints.isEmpty()) return super.onTouchEvent(event)

        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                isTouching = true
                touchX = event.x
                parent?.requestDisallowInterceptTouchEvent(true)
                findClosestIndex(touchX)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTouching = false
                parent?.requestDisallowInterceptTouchEvent(false)
                pointSelectedListener?.onPointSelected(null)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
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
