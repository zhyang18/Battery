package com.battery.analysis.util

import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.graphics.Paint
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.widget.PopupWindow
import android.widget.TextView
import com.battery.analysis.R
import com.battery.analysis.ui.view.BubbleTooltipLayout

/**
 * 气泡提示弹框辅助工具类。
 * 用于在指定视图或触摸坐标附近展示高质感自适应气泡弹窗，支持指向依附 View 的指示箭头、
 * 平滑弹出与收起过渡动画、自动定时关闭与屏幕安全边界防溢出处理。
 */
object BubbleTooltipHelper {

    /**
     * 在目标锚点视图附近弹出气泡说明框。
     * 自动计算屏幕安全边距、支持基于触摸坐标或视图中点对齐，动态渲染指向依附 View 的小三角指示器，
     * 自动适配上方/下方弹出平滑动画、内容过多时自适应最大高度与滚动布局及自定义超时关闭机制。
     *
     * @param anchorView 触发气泡弹窗的目标锚点视图
     * @param message 待呈现的提示文本内容
     * @param touchX 相对 anchorView 的点击 X 坐标（可选，为空时默认居中于 anchorView）
     * @param touchY 相对 anchorView 的点击 Y 坐标（可选，为空时默认基于 anchorView 顶部/底部边界）
     * @param autoDismissMs 自动关闭气泡的毫秒数（默认 2800 毫秒，传入 <= 0 时不自动关闭）
     * @param forceAbove 是否强制在锚点上方弹出（默认 false）
     * @param onDismiss 气泡关闭时的回调监听器（可选）
     * @return 构建并呈现的 [PopupWindow] 实例，若视图未附加到窗口则返回 null
     */
    fun showBubble(
        anchorView: View,
        message: CharSequence,
        touchX: Float? = null,
        touchY: Float? = null,
        autoDismissMs: Long = 2800L,
        forceAbove: Boolean = false,
        onDismiss: (() -> Unit)? = null
    ): PopupWindow? {
        if (!anchorView.isAttachedToWindow) return null

        val context = anchorView.context
        val inflater = LayoutInflater.from(context)
        val popupView = inflater.inflate(R.layout.popup_bubble_tip, null)

        val bubbleLayout = (popupView as? BubbleTooltipLayout)
            ?: popupView.findViewById(R.id.layout_bubble_root)

        val tvMessage = popupView.findViewById<TextView>(R.id.tv_bubble_message)
        tvMessage.text = applyHangingIndent(tvMessage, message)

        val scrollContent = popupView.findViewById<View>(R.id.scroll_bubble_content)
        scrollContent?.isVerticalScrollBarEnabled = false
        scrollContent?.isHorizontalScrollBarEnabled = false

        val density = context.resources.displayMetrics.density
        val rootWidth = anchorView.rootView?.width ?: 0
        val rootHeight = anchorView.rootView?.height ?: 0
        val screenWidth = if (rootWidth > 0) rootWidth else context.resources.displayMetrics.widthPixels
        val screenHeight = if (rootHeight > 0) rootHeight else context.resources.displayMetrics.heightPixels
        val margin = (BubbleTooltipLayout.DEFAULT_SCREEN_MARGIN_DP * density).toInt()
        val gap = (2 * density).toInt() // 箭头与依附 View 边缘的自然间隙

        // 限制气泡最大宽度不超过屏幕可用宽度（左右各保留 10dp 安全间距）
        val maxAvailableWidth = (screenWidth - 2 * margin).coerceAtLeast(0)

        // 自适应计算文本各单行不折行所需的最大理想宽度
        val horizontalPadding = (bubbleLayout?.let { it.paddingLeft + it.paddingRight } ?: (16 * density).toInt()) +
            tvMessage.paddingLeft + tvMessage.paddingRight
        val targetBubbleWidth = calculateAdaptiveBubbleWidth(
            textView = tvMessage,
            message = message,
            maxAvailableWidth = maxAvailableWidth,
            horizontalPadding = horizontalPadding,
            density = density
        )

        val anchorLoc = IntArray(2)
        anchorView.getLocationOnScreen(anchorLoc)

        // 水平方向：对齐触摸点或视图中心，并限制在屏幕安全边界内（左右各留出 margin 间距）
        val anchorCenterX = if (touchX != null) {
            anchorLoc[0] + touchX.toInt()
        } else {
            anchorLoc[0] + anchorView.width / 2
        }

        // 垂直方向：计算展示方位基准
        val anchorTopY = if (touchY != null) {
            anchorLoc[1] + touchY.toInt()
        } else {
            anchorLoc[1]
        }
        val anchorBottomY = if (touchY != null) {
            anchorLoc[1] + touchY.toInt()
        } else {
            anchorLoc[1] + anchorView.height
        }

        val spaceAbove = (anchorTopY - margin - gap).coerceAtLeast(0)
        val spaceBelow = (screenHeight - margin - anchorBottomY - gap).coerceAtLeast(0)

        // 1. 先以无高度限制测量自然理想高度（含箭头高度预估）
        bubbleLayout?.setMaxBubbleHeight(Int.MAX_VALUE)
        popupView.measure(
            View.MeasureSpec.makeMeasureSpec(targetBubbleWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val arrowHeightApprox = (7 * density).toInt()
        val idealPopupHeight = popupView.measuredHeight + arrowHeightApprox

        // 2. 依据空间充裕程度与超长内容判定展示方位
        val isAbove = decideBubblePlacement(
            forceAbove = forceAbove,
            spaceAbove = spaceAbove,
            spaceBelow = spaceBelow,
            idealHeight = idealPopupHeight
        )

        // 3. 计算在当前方位下所允许的最大高度并设置给气泡容器
        val maxAllowedHeight = calculateMaxAllowedHeight(
            isAbove = isAbove,
            spaceAbove = spaceAbove,
            spaceBelow = spaceBelow,
            screenHeight = screenHeight,
            density = density
        )
        bubbleLayout?.setMaxBubbleHeight(maxAllowedHeight)

        // 4. 配置依附 View 指向箭头方位与初始相对 X 坐标
        val arrowDirection = if (isAbove) {
            BubbleTooltipLayout.ArrowDirection.DOWN
        } else {
            BubbleTooltipLayout.ArrowDirection.UP
        }

        popupView.measure(
            View.MeasureSpec.makeMeasureSpec(targetBubbleWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxAllowedHeight, View.MeasureSpec.AT_MOST)
        )
        var popupWidth = popupView.measuredWidth

        var maxScreenX = (screenWidth - popupWidth - margin).coerceAtLeast(margin)
        var targetScreenX = (anchorCenterX - popupWidth / 2).coerceIn(margin, maxScreenX)
        var relativeArrowX = (anchorCenterX - targetScreenX).toFloat()
        bubbleLayout?.setArrow(arrowDirection, relativeArrowX)

        // 5. 重新测量以纳入箭头高度内边距
        popupView.measure(
            View.MeasureSpec.makeMeasureSpec(targetBubbleWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxAllowedHeight, View.MeasureSpec.AT_MOST)
        )
        popupWidth = popupView.measuredWidth
        val popupHeight = popupView.measuredHeight

        val reMaxScreenX = (screenWidth - popupWidth - margin).coerceAtLeast(margin)
        targetScreenX = (anchorCenterX - popupWidth / 2).coerceIn(margin, reMaxScreenX)
        relativeArrowX = (anchorCenterX - targetScreenX).toFloat()
        bubbleLayout?.setArrow(arrowDirection, relativeArrowX)

        // 6. 精确计算最终垂直屏幕位置（紧贴锚点外侧，绝不反向遮挡锚点）
        val finalScreenY = calculateFinalScreenY(
            isAbove = isAbove,
            anchorTopY = anchorTopY,
            anchorBottomY = anchorBottomY,
            popupHeight = popupHeight,
            gap = gap,
            margin = margin,
            screenHeight = screenHeight
        )

        val popupWindow = PopupWindow(
            popupView,
            popupWidth,
            popupHeight,
            true
        ).apply {
            isOutsideTouchable = true
            isFocusable = true
            // 依据弹窗位于依附视图上方或下方应用优雅的缩放平滑进出过渡动画
            animationStyle = if (isAbove) {
                R.style.Animation_BubbleTooltip_Above
            } else {
                R.style.Animation_BubbleTooltip_Below
            }
        }

        // 气泡视图消费内部点击事件以防事件穿透，点击气泡内任意点不关闭弹窗（仅点击外部区域或系统返回键取消弹框）
        popupView.isClickable = true

        // 自动倒计时关闭（仅在 autoDismissMs > 0 时启用）
        val handler = Handler(Looper.getMainLooper())
        var dismissRunnable: Runnable? = null
        if (autoDismissMs > 0L) {
            dismissRunnable = Runnable {
                try {
                    if (popupWindow.isShowing) {
                        popupWindow.dismiss()
                    }
                } catch (ignored: Exception) {
                }
            }
            handler.postDelayed(dismissRunnable, autoDismissMs)
        }

        popupWindow.setOnDismissListener {
            dismissRunnable?.let { handler.removeCallbacks(it) }
            onDismiss?.invoke()
        }

        popupWindow.showAtLocation(anchorView, Gravity.NO_GRAVITY, targetScreenX, finalScreenY)
        return popupWindow
    }

    /**
     * 依据锚点上方与下方可用垂直空间、气泡期望理想高度及强制方位参数，智能决策气泡应位于锚点上方或下方。
     * 1. 当 [forceAbove] 为 true 时，无条件返回 true（位于上方）；
     * 2. 当上方空间充裕且能完整容纳气泡理想高度时，优先返回 true；
     * 3. 当上方空间不足但下方空间充裕能完整容纳时，返回 false；
     * 4. 当上下两侧空间均无法完整容纳超长气泡（内容过多）时，选择可用空间更充裕的一侧展示，
     *    配合滚动视图承载超长内容，防止内容过多导致全屏或挤占锚点导致箭头反向倒错。
     *
     * @param forceAbove 是否强制在锚点上方弹出
     * @param spaceAbove 锚点上方可用垂直空间像素值
     * @param spaceBelow 锚点下方可用垂直空间像素值
     * @param idealHeight 气泡未受限制时的期望理想高度像素值
     * @return true 表示应展示在锚点上方（箭头在气泡底部朝下）；false 表示应展示在锚点下方（箭头在气泡顶部朝上）
     */
    fun decideBubblePlacement(
        forceAbove: Boolean,
        spaceAbove: Int,
        spaceBelow: Int,
        idealHeight: Int
    ): Boolean {
        if (forceAbove) return true
        if (spaceAbove >= idealHeight) return true
        if (spaceBelow >= idealHeight) return false
        return spaceAbove > spaceBelow
    }

    /**
     * 根据所选展示方位、对应方向可用空间及屏幕全局安全比例，计算气泡所允许的最大高度限制像素值。
     *
     * @param isAbove 气泡是否展示在锚点上方
     * @param spaceAbove 锚点上方可用垂直空间像素值
     * @param spaceBelow 锚点下方可用垂直空间像素值
     * @param screenHeight 屏幕或可见窗口总高度像素值
     * @param density 屏幕像素密度
     * @param maxScreenRatio 气泡单次弹出所允许占用的屏幕最大比例（默认 0.70f）
     * @return 气泡允许的最大高度像素值
     */
    fun calculateMaxAllowedHeight(
        isAbove: Boolean,
        spaceAbove: Int,
        spaceBelow: Int,
        screenHeight: Int,
        density: Float,
        maxScreenRatio: Float = 0.70f
    ): Int {
        val maxScreenRatioHeight = (screenHeight * maxScreenRatio).toInt()
        val sideSpace = if (isAbove) spaceAbove else spaceBelow
        val minHeight = (60 * density).toInt()
        return sideSpace.coerceAtMost(maxScreenRatioHeight).coerceAtLeast(minHeight)
    }

    /**
     * 精确计算气泡弹窗在屏幕上的最终垂直 Y 坐标。
     * 严格依据展示方位将气泡锚定在锚点上方或下方指定间隙处，杜绝任何侵占锚点或越界位移。
     *
     * @param isAbove 气泡是否展示在锚点上方
     * @param anchorTopY 锚点上边缘 Y 坐标像素值
     * @param anchorBottomY 锚点下边缘 Y 坐标像素值
     * @param popupHeight 气泡测量高度像素值
     * @param gap 气泡与锚点之间的垂直间隙像素值
     * @param margin 屏幕顶部与底部安全边距像素值
     * @param screenHeight 屏幕或可见窗口总高度像素值
     * @return 气泡弹窗在屏幕上的绝对垂直 Y 坐标像素值
     */
    fun calculateFinalScreenY(
        isAbove: Boolean,
        anchorTopY: Int,
        anchorBottomY: Int,
        popupHeight: Int,
        gap: Int,
        margin: Int,
        screenHeight: Int
    ): Int {
        return if (isAbove) {
            (anchorTopY - popupHeight - gap).coerceAtLeast(margin)
        } else {
            (anchorBottomY + gap).coerceAtMost(screenHeight - popupHeight - margin)
        }
    }

    /**
     * 将气泡说明文本格式化为包含优雅悬挂缩进的富文本对象。
     * 1. 当段落以列表标识符号（如 "• "、"💡 "、"- "、"* " 等）开头且内容发生自动折行时，
     *    折行后的后续行文本自动向右缩进避开首行标识符号，与首行的正文字符完美垂直对齐；
     * 2. 当段落以前导空格（如以两个空格 "  " 开头的子级描述行、工况范围行、统计范围行等）开头时，
     *    首行通过设置前置 LeadingMargin 动态补齐空格像素与标准圆点符号（"• "）的宽度差额，
     *    使子级正文首字符与上一行的列表正文字符严格垂直对齐；
     *    折行后的后续行文本自动向右缩进标准符号宽度，确保整个子段落与上方文字完全对齐。
     *
     * @param textView 目标呈现提示文本的 [TextView] 实例，用于测量字体排版像素宽度
     * @param text 待处理的原始文本内容
     * @return 包含悬挂缩进样式的富文本对象 [CharSequence]
     */
    fun applyHangingIndent(textView: TextView, text: CharSequence): CharSequence {
        val str = text.toString()
        if (str.isEmpty()) return text

        val spannable = if (text is Spannable) SpannableString(text) else SpannableString(str)
        val paint = textView.paint

        val lines = str.split("\n")
        var currentStart = 0
        val standardBulletIndent = paint.measureText("• ").toInt()
        for (line in lines) {
            val lineLength = line.length
            val lineEnd = currentStart + lineLength

            val leadingSpaces = line.takeWhile { it == ' ' }.length
            val contentAfterSpaces = line.substring(leadingSpaces)

            // 识别符号前缀长度（紧随前导空格之后）
            val bulletPrefixLength = when {
                contentAfterSpaces.startsWith("• ") -> 2
                contentAfterSpaces.startsWith("•") -> 1
                contentAfterSpaces.startsWith("💡 ") -> 2
                contentAfterSpaces.startsWith("💡") -> 1
                contentAfterSpaces.startsWith("- ") -> 2
                contentAfterSpaces.startsWith("* ") -> 2
                else -> 0
            }

            val totalPrefixLength = leadingSpaces + bulletPrefixLength

            if (lineLength > 0) {
                if (leadingSpaces > 0 && bulletPrefixLength == 0) {
                    // 子级描述行（如以两个空格 "  " 开头的 "统计范围"、"有效放电"、"工况预期范围" 等）：
                    // 首行缩进补齐与父级列表符号对齐的差额，使首字符（正文文字）与上一行正文字符完全垂直对齐；
                    // 自动折行后的后续行文本自动向右缩进 standardBulletIndent 像素，保持全段落垂直统一
                    val spacesWidth = paint.measureText(line.substring(0, leadingSpaces)).toInt()
                    val firstIndent = (standardBulletIndent - spacesWidth).coerceAtLeast(0)
                    val restIndent = standardBulletIndent
                    spannable.setSpan(
                        LeadingMarginSpan.Standard(firstIndent, restIndent),
                        currentStart,
                        lineEnd,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                } else if (totalPrefixLength > 0 && lineLength > totalPrefixLength) {
                    val prefixStr = line.substring(0, totalPrefixLength)
                    val indentWidth = paint.measureText(prefixStr).toInt()
                    // 为该段落应用悬挂缩进：首行 0 缩进（前导空格或符号已在首行字符流中占位），
                    // 自动折行后的后续行文本自动向右缩进 indentWidth 像素，与首行正文字符完美垂直左对齐
                    spannable.setSpan(
                        LeadingMarginSpan.Standard(0, indentWidth),
                        currentStart,
                        lineEnd,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }

            currentStart = lineEnd + 1 // +1 跳过换行符 '\n'
        }
        return spannable
    }

    /**
     * 计算单行文本所能展开的最大期望宽度。
     * 遍历文本中的各段换行行，测量每行文字所需的单行宽度，并自动计入子级描述行的前置对齐边距，返回其中的最大值。
     *
     * @param paint 绘制文本所使用的 [Paint] 对象
     * @param message 待计算的文本内容
     * @return 文本所有自然行中单行绘制所需的最大宽度（像素）
     */
    fun calculateMaxDesiredLineWidth(paint: Paint, message: CharSequence): Float {
        val str = message.toString()
        if (str.isEmpty()) return 0f
        var maxWidth = 0f
        val lines = str.split("\n")
        val standardBulletIndent = paint.measureText("• ")
        for (line in lines) {
            val leadingSpaces = line.takeWhile { it == ' ' }.length
            val contentAfterSpaces = line.substring(leadingSpaces)
            val bulletPrefixLength = when {
                contentAfterSpaces.startsWith("• ") -> 2
                contentAfterSpaces.startsWith("•") -> 1
                contentAfterSpaces.startsWith("💡 ") -> 2
                contentAfterSpaces.startsWith("💡") -> 1
                contentAfterSpaces.startsWith("- ") -> 2
                contentAfterSpaces.startsWith("* ") -> 2
                else -> 0
            }
            val extraIndent = if (leadingSpaces > 0 && bulletPrefixLength == 0) {
                val spacesWidth = paint.measureText(line.substring(0, leadingSpaces))
                (standardBulletIndent - spacesWidth).coerceAtLeast(0f)
            } else {
                0f
            }
            val lineWidth = paint.measureText(line) + extraIndent
            if (lineWidth > maxWidth) {
                maxWidth = lineWidth
            }
        }
        return maxWidth
    }

    /**
     * 依据文本各行的自然单行最大宽度与屏幕可用宽度限制，计算自适应的气泡总宽度。
     * 确保当文本最长行不超过屏幕最大限制时维持单行不提前折行；当超出时自适应扩展至屏幕最大可用宽度。
     *
     * @param textView 用于展示文本的 [TextView]
     * @param message 待呈现的文本内容
     * @param maxAvailableWidth 气泡所能占据的屏幕最大可用宽度（已扣除左右安全边距）
     * @param horizontalPadding 气泡容器与文本视图的左右内边距总和（像素）
     * @param density 屏幕像素密度
     * @return 自适应计算后的气泡视图宽度像素值
     */
    fun calculateAdaptiveBubbleWidth(
        textView: TextView,
        message: CharSequence,
        maxAvailableWidth: Int,
        horizontalPadding: Int,
        density: Float
    ): Int {
        val maxLineWidth = calculateMaxDesiredLineWidth(textView.paint, message)
        // 增加适量的文字缓冲容差（4dp），防止浮点数精度或抗锯齿渲染导致边界字词意外换行
        val safetyBuffer = (4 * density).toInt()
        val idealWidth = (maxLineWidth + horizontalPadding + safetyBuffer).toInt()
        return idealWidth.coerceIn(0, maxAvailableWidth)
    }
}

