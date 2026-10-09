package com.battery.analysis.util

import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
     * 自动适配上方/下方弹出平滑动画与自定义超时关闭机制。
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

        val density = context.resources.displayMetrics.density
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels
        val margin = (12 * density).toInt()
        val gap = (2 * density).toInt() // 箭头与依附 View 边缘的自然间隙

        // 限制气泡最大宽度不超过屏幕可用宽度
        val maxAvailableWidth = (screenWidth - 2 * margin).coerceAtLeast(0)

        // 初步测量气泡尺寸
        popupView.measure(
            View.MeasureSpec.makeMeasureSpec(maxAvailableWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        var popupWidth = popupView.measuredWidth
        var popupHeight = popupView.measuredHeight

        val anchorLoc = IntArray(2)
        anchorView.getLocationOnScreen(anchorLoc)

        // 水平方向：对齐触摸点或视图中心，并限制在屏幕安全边界内
        val anchorCenterX = if (touchX != null) {
            anchorLoc[0] + touchX.toInt()
        } else {
            anchorLoc[0] + anchorView.width / 2
        }
        var targetScreenX = anchorCenterX - popupWidth / 2
        val maxScreenX = (screenWidth - popupWidth - margin).coerceAtLeast(margin)
        targetScreenX = targetScreenX.coerceIn(margin, maxScreenX)

        // 垂直方向：计算展示方位
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

        val spaceAbove = anchorTopY - margin
        val isAbove = forceAbove || (spaceAbove >= popupHeight + gap)

        // 配置依附 View 指向箭头方位与相对 X 坐标
        val arrowDirection = if (isAbove) {
            BubbleTooltipLayout.ArrowDirection.DOWN
        } else {
            BubbleTooltipLayout.ArrowDirection.UP
        }
        var relativeArrowX = (anchorCenterX - targetScreenX).toFloat()
        bubbleLayout?.setArrow(arrowDirection, relativeArrowX)

        // 重新测量以纳入箭头高度内边距
        popupView.measure(
            View.MeasureSpec.makeMeasureSpec(maxAvailableWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        popupWidth = popupView.measuredWidth
        popupHeight = popupView.measuredHeight

        val reMaxScreenX = (screenWidth - popupWidth - margin).coerceAtLeast(margin)
        targetScreenX = (anchorCenterX - popupWidth / 2).coerceIn(margin, reMaxScreenX)
        relativeArrowX = (anchorCenterX - targetScreenX).toFloat()
        bubbleLayout?.setArrow(arrowDirection, relativeArrowX)

        // 最终垂直屏幕位置
        val targetScreenY = if (isAbove) {
            anchorTopY - popupHeight - gap
        } else {
            (anchorBottomY + gap).coerceAtMost(screenHeight - popupHeight - margin)
        }
        val finalScreenY = targetScreenY.coerceIn(margin, screenHeight - popupHeight - margin)

        val popupWindow = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
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
     * 将气泡说明文本格式化为包含优雅悬挂缩进的富文本对象。
     * 当段落以标识符号（如 "• "、"💡 "、"- "、"* " 等）开头且内容发生折行时，
     * 折行后的后续行文本自动向右缩进避开首行标识符号，与首行的正文字符完美垂直对齐。
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
        for (line in lines) {
            val lineLength = line.length
            val lineEnd = currentStart + lineLength

            // 识别段落开头的标识符号（如 "• "、"•"、"💡 "、"- " 等）
            val prefixLength = when {
                line.startsWith("• ") -> 2
                line.startsWith("•") -> 1
                line.startsWith("💡 ") -> 2
                line.startsWith("💡") -> 1
                line.startsWith("- ") -> 2
                line.startsWith("* ") -> 2
                else -> 0
            }

            if (prefixLength > 0 && lineLength > prefixLength) {
                val prefixStr = line.substring(0, prefixLength)
                val indentWidth = paint.measureText(prefixStr).toInt()
                // 为该段落应用悬挂缩进：首行 0 缩进，折行后的文本自动缩进 indentWidth 像素与首行正文对齐
                spannable.setSpan(
                    LeadingMarginSpan.Standard(0, indentWidth),
                    currentStart,
                    lineEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            currentStart = lineEnd + 1 // +1 跳过换行符 '\n'
        }
        return spannable
    }
}
