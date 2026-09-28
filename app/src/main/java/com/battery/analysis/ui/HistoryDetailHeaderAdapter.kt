package com.battery.analysis.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.battery.analysis.databinding.ItemHistoryDetailHeaderBinding

/**
 * 耗电历史快照详情页头部组合卡片适配器。
 * 承载放电时段、核心三维功耗指标看板、放电走势折线图与使用列表操作栏，
 * 作为 ConcatAdapter 的首个条目挂载至主 RecyclerView，避免局部嵌套与重复测量。
 *
 * @param onBindingReady 当头部视图绑定创建就绪时的回调函数（用于初始化监听与初次绑定）
 * @param onBind 当头部视图重新绑定时的回调函数（用于时序安全的数据刷新）
 */
class HistoryDetailHeaderAdapter(
    private val onBindingReady: (ItemHistoryDetailHeaderBinding) -> Unit,
    private val onBind: (ItemHistoryDetailHeaderBinding) -> Unit
) : RecyclerView.Adapter<HistoryDetailHeaderAdapter.HeaderViewHolder>() {

    var headerBinding: ItemHistoryDetailHeaderBinding? = null
        private set

    /**
     * 头部卡片视图持有者，禁用回收以保护折线图的手势交互与绘制状态。
     *
     * @param binding 头部组合视图绑定对象
     */
    inner class HeaderViewHolder(val binding: ItemHistoryDetailHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            setIsRecyclable(false)
        }
    }

    /**
     * 创建头部 ViewHolder 实例。
     *
     * @param parent 父容器视图组
     * @param viewType 视图类型
     * @return 初始化的 [HeaderViewHolder]
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HeaderViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemHistoryDetailHeaderBinding.inflate(inflater, parent, false)
        headerBinding = binding
        onBindingReady(binding)
        return HeaderViewHolder(binding)
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
}
