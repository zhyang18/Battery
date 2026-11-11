package com.battery.analysis.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.battery.analysis.databinding.ItemHistoryDetailHeaderBinding

/**
 * 耗电历史快照详情页头部组合卡片适配器。
 * 承载放电时段、核心三维功耗指标看板、放电走势折线图与使用列表操作栏，
 * 作为 ConcatAdapter 的首个条目挂载至主 RecyclerView，避免局部嵌套与重复测量。
 * 采用单例 ViewHolder 实例复用与原生回收机制，杜绝重复 inflate 导致的滑动严重卡顿。
 *
 * @param onBindingReady 当头部视图绑定首次创建就绪时的回调函数（用于初始化全局一次性交互监听）
 * @param onBind 当头部视图重新绑定时的回调函数（用于时序安全的数据刷新）
 */
class HistoryDetailHeaderAdapter(
    private val onBindingReady: (ItemHistoryDetailHeaderBinding) -> Unit,
    private val onBind: (ItemHistoryDetailHeaderBinding) -> Unit
) : RecyclerView.Adapter<HistoryDetailHeaderAdapter.HeaderViewHolder>() {

    var headerBinding: ItemHistoryDetailHeaderBinding? = null
        private set

    /**
     * 单例缓存的头部 ViewHolder 实例，避免长列表滚动再回滑时重复膨胀超大 XML 视图树。
     */
    private var cachedViewHolder: HeaderViewHolder? = null

    /**
     * 头部卡片视图持有者。
     * 保持原生可回收特性（严禁调用 setIsRecyclable(false)），使视图能够正常被 RecyclerView 缓存和复用。
     *
     * @param binding 头部组合视图绑定对象
     */
    class HeaderViewHolder(val binding: ItemHistoryDetailHeaderBinding) :
        RecyclerView.ViewHolder(binding.root)

    /**
     * 创建头部 ViewHolder 实例。
     * 若已存在单例 ViewHolder，则直接剥离并复用既有实例；仅在首次创建时执行布局 inflate 与监听器挂载。
     *
     * @param parent 父容器视图组
     * @param viewType 视图类型
     * @return 初始化的 [HeaderViewHolder]
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HeaderViewHolder {
        val existing = cachedViewHolder
        if (existing != null) {
            (existing.itemView.parent as? ViewGroup)?.removeView(existing.itemView)
            headerBinding = existing.binding
            return existing
        }
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemHistoryDetailHeaderBinding.inflate(inflater, parent, false)
        headerBinding = binding
        val holder = HeaderViewHolder(binding)
        cachedViewHolder = holder
        onBindingReady(binding)
        return holder
    }

    /**
     * 绑定头部视图数据，在 ViewHolder 首次挂载或外部通知刷新时被回调。
     *
     * @param holder 头部视图持有者
     * @param position 列表项下标
     */
    override fun onBindViewHolder(holder: HeaderViewHolder, position: Int) {
        headerBinding = holder.binding
        onBind(holder.binding)
    }

    /**
     * 返回头部条目数量，固定为 1。
     *
     * @return 条目数 1
     */
    override fun getItemCount(): Int = 1

    /**
     * 安全清理单例 ViewHolder 引用，防止在 Activity 销毁时遗留视图引用导致内存泄漏。
     */
    fun clear() {
        cachedViewHolder = null
        headerBinding = null
    }
}
