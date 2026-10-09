package com.battery.analysis.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.battery.analysis.R

/**
 * 带有依附视图指向箭头的高质感自适应气泡容器布局。
 * 用于呈现指向目标锚点视图的气泡弹窗，支持向上或向下的等腰三角指示箭头动态平移对齐、
 * 圆角矩形自适应裁剪、边框与主题背景统一填充及 Material 原生阴影渲染。
 */
class BubbleTooltipLayout : FrameLayout {

    /**
     * 气泡指向箭头的方位枚举。
     */
    enum class ArrowDirection {
        /**
         * 箭头位于气泡顶部并朝上指向上方锚点视图（气泡位于锚点下方）。
         */
        UP,

        /**
         * 箭头位于气泡底部并朝下指向下方锚点视图（气泡位于锚点上方）。
         */
        DOWN,

        /**
         * 不绘制指示箭头，呈现标准圆角矩形气泡。
         */
        NONE
    }

    private var arrowDirection: ArrowDirection = ArrowDirection.NONE
    private var arrowCenterX: Float = 0f

    private val arrowWidthPx: Float
    private val arrowHeightPx: Float
    private val cornerRadiusPx: Float
    private val strokeWidthPx: Float

    private var initialPaddingLeft: Int = 0
    private var initialPaddingTop: Int = 0
    private var initialPaddingRight: Int = 0
    private var initialPaddingBottom: Int = 0

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#33888888")
    }

    private val bubblePath = Path()
    private val tempRectF = RectF()

    /**
     * 代码动态创建实例时调用的构造函数。
     *
     * @param context Android 上下文对象
     */
    constructor(context: Context) : this(context, null)

    /**
     * XML 布局解析时调用的构造函数。
     *
     * @param context Android 上下文对象
     * @param attrs XML 属性集
     */
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)

    /**
     * 包含默认样式属性的完整构造函数。
     *
     * @param context Android 上下文对象
     * @param attrs XML 属性集
     * @param defStyleAttr 默认样式属性资源标识
     */
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        val density = context.resources.displayMetrics.density
        arrowWidthPx = 14f * density
        arrowHeightPx = 7f * density
        cornerRadiusPx = 8f * density
        strokeWidthPx = 1f * density

        strokePaint.strokeWidth = strokeWidthPx
        fillPaint.color = ContextCompat.getColor(context, R.color.popup_bg)

        // FrameLayout 默认不调用 onDraw，需显式开启
        setWillNotDraw(false)

        initialPaddingLeft = paddingLeft
        initialPaddingTop = paddingTop
        initialPaddingRight = paddingRight
        initialPaddingBottom = paddingBottom

        // 配置 Material 原生阴影轮廓提供器
        outlineProvider = object : ViewOutlineProvider() {
            /**
             * 提取当前气泡主体的投影轮廓。
             *
             * @param view 当前视图对象
             * @param outline 待配置的轮廓对象
             */
            override fun getOutline(view: View, outline: Outline) {
                val top = if (arrowDirection == ArrowDirection.UP) arrowHeightPx.toInt() else 0
                val bottom = if (arrowDirection == ArrowDirection.DOWN) (height - arrowHeightPx).toInt() else height
                outline.setRoundRect(0, top, width, bottom.coerceAtLeast(top), cornerRadiusPx)
            }
        }
        clipToOutline = false
    }

    /**
     * 重写测量方法，限制气泡最大宽度不超过屏幕安全边界，确保内部多行长文本能自适应折行。
     *
     * @param widthMeasureSpec 宽度测量规范
     * @param heightMeasureSpec 高度测量规范
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = context.resources.displayMetrics.density
        val screenWidth = context.resources.displayMetrics.widthPixels
        val margin = (12 * density).toInt()
        val maxWidth = (screenWidth - 2 * margin).coerceAtLeast(0)

        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)

        val targetWidthSpec = when (widthMode) {
            MeasureSpec.UNSPECIFIED -> MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST)
            MeasureSpec.AT_MOST -> MeasureSpec.makeMeasureSpec(widthSize.coerceAtMost(maxWidth), MeasureSpec.AT_MOST)
            else -> widthMeasureSpec
        }
        super.onMeasure(targetWidthSpec, heightMeasureSpec)
    }

    /**
     * 配置箭头的指向方位与相对当前气泡左边缘的水平绝对 X 坐标。
     * 自动根据箭头高度调节内边距，确保内部内容文本不被箭头遮挡。
     *
     * @param direction 箭头指示方位（[ArrowDirection.UP]、[ArrowDirection.DOWN] 或 [ArrowDirection.NONE]）
     * @param targetArrowCenterX 箭头顶点相对本视图左侧的 X 坐标像素值
     */
    fun setArrow(direction: ArrowDirection, targetArrowCenterX: Float) {
        this.arrowDirection = direction
        this.arrowCenterX = targetArrowCenterX
        updatePaddingForArrow()
        if (width > 0 && height > 0) {
            rebuildBubblePath(width.toFloat(), height.toFloat())
        } else {
            bubblePath.reset()
        }
        invalidateOutline()
        invalidate()
    }

    /**
     * 依据当前箭头方位动态调整上下内边距，使内容区域避开尖角区域。
     */
    private fun updatePaddingForArrow() {
        val extraTop = if (arrowDirection == ArrowDirection.UP) arrowHeightPx.toInt() else 0
        val extraBottom = if (arrowDirection == ArrowDirection.DOWN) arrowHeightPx.toInt() else 0
        super.setPadding(
            initialPaddingLeft,
            initialPaddingTop + extraTop,
            initialPaddingRight,
            initialPaddingBottom + extraBottom
        )
    }

    /**
     * 重写设置内边距方法，记忆初始基准内边距。
     *
     * @param left 左侧内边距（像素）
     * @param top 顶部内边距（像素）
     * @param right 右侧内边距（像素）
     * @param bottom 底部内边距（像素）
     */
    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        initialPaddingLeft = left
        initialPaddingTop = top
        initialPaddingRight = right
        initialPaddingBottom = bottom
        updatePaddingForArrow()
    }

    /**
     * 响应视图尺寸发生改变事件，重新构建闭合气泡绘制路径。
     *
     * @param w 新宽度像素值
     * @param h 新高度像素值
     * @param oldw 旧宽度像素值
     * @param oldh 旧高度像素值
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildBubblePath(w.toFloat(), h.toFloat())
    }

    /**
     * 重建包含圆角矩形主体与依附箭头的连续平滑闭合路径。
     *
     * @param width 视图宽度像素
     * @param height 视图高度像素
     */
    private fun rebuildBubblePath(width: Float, height: Float) {
        bubblePath.reset()
        if (width <= 0f || height <= 0f) return

        val halfStroke = strokeWidthPx / 2f
        val rectLeft = halfStroke
        val rectRight = width - halfStroke
        val r = cornerRadiusPx
        val aw = arrowWidthPx
        val ah = arrowHeightPx

        when (arrowDirection) {
            ArrowDirection.UP -> {
                val rectTop = ah + halfStroke
                val rectBottom = height - halfStroke
                val minArrowX = rectLeft + r + aw / 2f
                val maxArrowX = rectRight - r - aw / 2f
                val effectiveArrowX = if (minArrowX <= maxArrowX) {
                    arrowCenterX.coerceIn(minArrowX, maxArrowX)
                } else {
                    width / 2f
                }

                bubblePath.moveTo(rectLeft, rectTop + r)
                // 左上圆角
                tempRectF.set(rectLeft, rectTop, rectLeft + 2 * r, rectTop + 2 * r)
                bubblePath.arcTo(tempRectF, 180f, 90f, false)

                // 顶边到箭头左侧
                bubblePath.lineTo(effectiveArrowX - aw / 2f, rectTop)
                // 箭头向上尖角
                bubblePath.lineTo(effectiveArrowX, halfStroke)
                // 箭头右侧到顶边
                bubblePath.lineTo(effectiveArrowX + aw / 2f, rectTop)

                // 顶边到右上圆角
                bubblePath.lineTo(rectRight - r, rectTop)
                // 右上圆角
                tempRectF.set(rectRight - 2 * r, rectTop, rectRight, rectTop + 2 * r)
                bubblePath.arcTo(tempRectF, 270f, 90f, false)

                // 右边线
                bubblePath.lineTo(rectRight, rectBottom - r)
                // 右下圆角
                tempRectF.set(rectRight - 2 * r, rectBottom - 2 * r, rectRight, rectBottom)
                bubblePath.arcTo(tempRectF, 0f, 90f, false)

                // 底边线
                bubblePath.lineTo(rectLeft + r, rectBottom)
                // 左下圆角
                tempRectF.set(rectLeft, rectBottom - 2 * r, rectLeft + 2 * r, rectBottom)
                bubblePath.arcTo(tempRectF, 90f, 90f, false)

                bubblePath.close()
            }
            ArrowDirection.DOWN -> {
                val rectTop = halfStroke
                val rectBottom = height - ah - halfStroke
                val minArrowX = rectLeft + r + aw / 2f
                val maxArrowX = rectRight - r - aw / 2f
                val effectiveArrowX = if (minArrowX <= maxArrowX) {
                    arrowCenterX.coerceIn(minArrowX, maxArrowX)
                } else {
                    width / 2f
                }

                bubblePath.moveTo(rectLeft, rectTop + r)
                // 左上圆角
                tempRectF.set(rectLeft, rectTop, rectLeft + 2 * r, rectTop + 2 * r)
                bubblePath.arcTo(tempRectF, 180f, 90f, false)

                // 顶边线
                bubblePath.lineTo(rectRight - r, rectTop)
                // 右上圆角
                tempRectF.set(rectRight - 2 * r, rectTop, rectRight, rectTop + 2 * r)
                bubblePath.arcTo(tempRectF, 270f, 90f, false)

                // 右边线
                bubblePath.lineTo(rectRight, rectBottom - r)
                // 右下圆角
                tempRectF.set(rectRight - 2 * r, rectBottom - 2 * r, rectRight, rectBottom)
                bubblePath.arcTo(tempRectF, 0f, 90f, false)

                // 底边到箭头右侧
                bubblePath.lineTo(effectiveArrowX + aw / 2f, rectBottom)
                // 箭头向下尖角
                bubblePath.lineTo(effectiveArrowX, height - halfStroke)
                // 箭头左侧到底边
                bubblePath.lineTo(effectiveArrowX - aw / 2f, rectBottom)

                // 底边到左下圆角
                bubblePath.lineTo(rectLeft + r, rectBottom)
                // 左下圆角
                tempRectF.set(rectLeft, rectBottom - 2 * r, rectLeft + 2 * r, rectBottom)
                bubblePath.arcTo(tempRectF, 90f, 90f, false)

                bubblePath.close()
            }
            ArrowDirection.NONE -> {
                val rectTop = halfStroke
                val rectBottom = height - halfStroke
                tempRectF.set(rectLeft, rectTop, rectRight, rectBottom)
                bubblePath.addRoundRect(tempRectF, r, r, Path.Direction.CW)
            }
        }
    }

    /**
     * 绘制气泡的背景与外描边。
     *
     * @param canvas 绘制画布实例
     */
    override fun onDraw(canvas: Canvas) {
        if (bubblePath.isEmpty) {
            rebuildBubblePath(width.toFloat(), height.toFloat())
        }
        canvas.drawPath(bubblePath, fillPaint)
        canvas.drawPath(bubblePath, strokePaint)
        super.onDraw(canvas)
    }
}
