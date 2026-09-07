package com.battery.analysis.ui.view

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * 充电电量环形进度条自定义视图。
 * 遵循设计图样式，绘制底色背景圆环轨道与天蓝色实时电量进度圆弧，支持自适应暗色与亮色主题。
 */
class BatteryCircleProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 当前电量百分比进度（0 ~ 100）
    private var progress: Int = 0

    // 背景圆环画笔
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    // 进度圆弧画笔
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#1E88E5")
    }

    // 绘制圆弧的边界矩形
    private val arcBounds = RectF()

    // 圆环描边宽度（像素）
    private var strokeWidthPx: Float = dpToPx(11f)

    init {
        updateColors()
    }

    /**
     * 根据当前系统深色/浅色模式配置自动更新轨道画笔颜色。
     */
    private fun updateColors() {
        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        trackPaint.color = if (isNight) Color.parseColor("#25272C") else Color.parseColor("#E0E0E0")
        trackPaint.strokeWidth = strokeWidthPx
        progressPaint.strokeWidth = strokeWidthPx
    }

    /**
     * 测量并确定视图的宽高尺寸，保持正方形宽高比例。
     *
     * @param widthMeasureSpec 水平方向的测量规格
     * @param heightMeasureSpec 垂直方向的测量规格
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val size = if (width > 0 && height > 0) min(width, height) else if (width > 0) width else height
        val finalSize = if (size > 0) size else dpToPx(105f).toInt()
        setMeasuredDimension(finalSize, finalSize)
    }

    /**
     * 视图尺寸发生变更时重新计算绘制外接矩形范围。
     *
     * @param w 变更后的当前宽度
     * @param h 变更后的当前高度
     * @param oldw 变更前的旧宽度
     * @param oldh 变更前的旧高度
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = strokeWidthPx / 2f + dpToPx(2f)
        arcBounds.set(inset, inset, w - inset, h - inset)
    }

    /**
     * 执行视图绘制流程，依次绘制底色圆环轨道与前景色电量进度弧。
     *
     * @param canvas 绘制画布对象
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (arcBounds.width() <= 0f || arcBounds.height() <= 0f) return

        // 1. 绘制底色完整圆环轨道
        canvas.drawArc(arcBounds, 0f, 360f, false, trackPaint)

        // 2. 绘制前景电量进度圆弧（从顶部 270 度位置开始顺时针扫过）
        val sweepAngle = (progress.coerceIn(0, 100) / 100f) * 360f
        if (sweepAngle > 0f) {
            canvas.drawArc(arcBounds, 270f, sweepAngle, false, progressPaint)
        }
    }

    /**
     * 动态设置当前电量百分比进度并触发界面重绘。
     *
     * @param value 电量百分比数值（0 ~ 100）
     */
    fun setProgress(value: Int) {
        val clamped = value.coerceIn(0, 100)
        if (this.progress != clamped) {
            this.progress = clamped
            invalidate()
        }
    }

    /**
     * 获取当前视图呈现的电量百分比数值。
     *
     * @return 当前电量百分比整数值（0 ~ 100）
     */
    fun getProgress(): Int {
        return this.progress
    }

    /**
     * 工具方法：将独立像素（dp）数值转换为设备屏幕实际物理像素（px）。
     *
     * @param dp 独立像素数值
     * @return 转换后的设备物理像素数值
     */
    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }
}
