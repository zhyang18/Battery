package com.battery.analysis.ui.view

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs

/**
 * 专为嵌套 [ViewPager2] 场景定制的智能下拉刷新容器。
 *
 * 解决原生 [SwipeRefreshLayout] 在包含左右滑动的 [ViewPager2] 时存在的手势冲突问题：
 * 1. 原生组件在手指斜向或微带向下角度左右滑动时，因仅判断 Y 轴位移容易误判为下拉刷新，本组件通过精准计算 X/Y 轴位移比率消除误触；
 * 2. 深度感知当前活跃子页面的滚动状态，解决子页内容向上滑动后下拉可能误判为顶部的问题。
 */
class ViewPagerSwipeRefreshLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SwipeRefreshLayout(context, attrs) {

    private var startX = 0f
    private var startY = 0f
    private var isHorizontalDragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    /**
     * 触摸事件拦截调度。通过对手势初始滑动方向的比例判定，精准阻断横滑误触下拉刷新。
     *
     * @param ev 待分发的触摸手势事件 [MotionEvent]
     * @return 若当前控件拦截消费该手势返回 `true`，否则返回 `false` 传递给子视图
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x
                startY = ev.y
                isHorizontalDragging = false
                // 将 ACTION_DOWN 正常透传给父类以初始化内部手势追踪状态
                super.onInterceptTouchEvent(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                if (isHorizontalDragging) {
                    return false
                }
                val dx = abs(ev.x - startX)
                val dy = abs(ev.y - startY)

                // 当 X 轴横向位移大于滑动阈值且横向位移大于纵向位移时，判定为横向切换 Tab 手势
                if (dx > touchSlop && dx > dy) {
                    isHorizontalDragging = true
                    return false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isHorizontalDragging = false
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    /**
     * 判断当前子视图是否可以在垂直方向上继续向上滚动。
     * 自动穿透 [ViewPager2] 并检测当前活跃 Tab 内部滚动控件（如 NestedScrollView）的实际滚动偏移。
     *
     * @return 若子视图内容未到达顶部（可继续向上滚动）返回 `true`，若已到达顶部返回 `false`
     */
    override fun canChildScrollUp(): Boolean {
        if (canActiveChildScrollUp()) {
            return true
        }
        return super.canChildScrollUp()
    }

    /**
     * 递归探测 [ViewPager2] 内部当前正在展示的子页面是否可以向上滚动。
     *
     * @return 若当前活跃子页面可继续向上滚动返回 `true`，否则返回 `false`
     */
    private fun canActiveChildScrollUp(): Boolean {
        val viewPager = findChildViewPager2(this) ?: return false
        val currentItem = viewPager.currentItem
        val recyclerView = viewPager.getChildAt(0) as? RecyclerView ?: return false
        val viewHolder = recyclerView.findViewHolderForAdapterPosition(currentItem)
        val itemView = viewHolder?.itemView ?: return false
        return canViewScrollUp(itemView)
    }

    private var cachedViewPager: java.lang.ref.WeakReference<ViewPager2>? = null

    /**
     * 辅助查找当前容器树下的 [ViewPager2] 实例。
     * 优先使用弱引用缓存，避免每一帧手势滑动都触发整棵视图树的深度递归遍历。
     *
     * @param group 待检索的父级视图容器 [ViewGroup]
     * @return 查找到的 [ViewPager2] 实例，若未找到则返回 `null`
     */
    private fun findChildViewPager2(group: ViewGroup): ViewPager2? {
        val cached = cachedViewPager?.get()
        if (cached != null && cached.isAttachedToWindow) {
            return cached
        }
        val found = searchChildViewPager2(group)
        if (found != null) {
            cachedViewPager = java.lang.ref.WeakReference(found)
        }
        return found
    }

    /**
     * 深度优先遍历检索子视图树中的 [ViewPager2] 实例。
     *
     * @param group 目标父容器
     * @return 匹配到的 [ViewPager2] 对象，未找到返回 null
     */
    private fun searchChildViewPager2(group: ViewGroup): ViewPager2? {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child is ViewPager2) {
                return child
            } else if (child is ViewGroup) {
                val found = searchChildViewPager2(child)
                if (found != null) return found
            }
        }
        return null
    }

    /**
     * 递归探测指定视图及其子视图是否支持并能够向上滚动。
     *
     * @param view 待检测的目标视图 [View]
     * @param depth 当前递归深度（默认 0，上限 6 层以保证快速剪枝）
     * @return 若目标视图或其子视图可继续向上滚动返回 `true`，否则返回 `false`
     */
    private fun canViewScrollUp(view: View, depth: Int = 0): Boolean {
        if (view.canScrollVertically(-1)) {
            return true
        }
        if (depth < 6 && view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if (canViewScrollUp(child, depth + 1)) {
                    return true
                }
            }
        }
        return false
    }
}
