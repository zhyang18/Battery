package com.battery.analysis.util

import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView
import com.battery.analysis.R

/**
 * 气泡提示弹框辅助工具类。
 * 用于替代传统系统 Toast，在指定视图或触摸坐标附近展示优雅自适应的气泡弹窗，支持自动定时关闭与边界防溢出。
 */
object BubbleTooltipHelper {

    /**
     * 在目标锚点视图附近弹出气泡说明框。
     * 自动计算屏幕安全边距、支持基于触摸坐标或视图中点对齐，支持强制在上方展示与自定义超时关闭。
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

        val tvMessage = popupView.findViewById<TextView>(R.id.tv_bubble_message)
        tvMessage.text = message

        val popupWindow = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            isFocusable = true
            animationStyle = android.R.style.Animation_Toast
        }

        // 点击气泡本身立即关闭
        popupView.setOnClickListener {
            if (popupWindow.isShowing) {
                popupWindow.dismiss()
            }
        }

        // 测量气泡尺寸
        popupView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popupWidth = popupView.measuredWidth
        val popupHeight = popupView.measuredHeight

        val density = context.resources.displayMetrics.density
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels
        val margin = (12 * density).toInt()
        val gap = (4 * density).toInt()

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

        // 垂直方向：优先在锚点上方展示，若上方空间不足则展示在下方（支持 forceAbove 强制在上方）
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
        val targetScreenY = if (forceAbove || spaceAbove >= popupHeight + gap) {
            anchorTopY - popupHeight - gap
        } else {
            (anchorBottomY + gap).coerceAtMost(screenHeight - popupHeight - margin)
        }

        val finalScreenY = targetScreenY.coerceIn(margin, screenHeight - popupHeight - margin)

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
}
