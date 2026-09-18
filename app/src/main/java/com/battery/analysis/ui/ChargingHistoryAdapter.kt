package com.battery.analysis.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.battery.analysis.databinding.ItemChargingHistoryRecordBinding
import com.battery.analysis.model.ChargingHistoryRecord
import java.util.Locale

/**
 * 充电历史记录 RecyclerView 适配器。
 * 遵循现代化双行双列设计图样式渲染，展示充电起止时段、充电时长与电量增量区间、平均充电功率数值与说明标签。
 * 支持点击条目跳转至充电详情 Activity，并支持长按条目触发删除操作。
 *
 * @param onItemClick 点击历史记录条目时的业务回调，用于打开详情页面
 * @param onDeleteClick 长按条目时的业务回调，用于触发单条删除逻辑
 */
class ChargingHistoryAdapter(
    private val onItemClick: (ChargingHistoryRecord) -> Unit,
    private val onDeleteClick: (ChargingHistoryRecord) -> Unit
) : ListAdapter<ChargingHistoryRecord, ChargingHistoryAdapter.ViewHolder>(ChargingHistoryDiffCallback()) {

    /**
     * 是否处于多选删除模式标记。
     */
    var isSelectionMode: Boolean = false
        private set

    /**
     * 当前已选中的记录 ID 集合。
     */
    val selectedIds = mutableSetOf<Long>()

    /**
     * 选择状态变化时的业务回调通知（参数依次为：当前选中数、当前列表总数）。
     */
    var onSelectionChanged: ((Int, Int) -> Unit)? = null

    /**
     * 开启或退出多选删除模式，并重置选中状态与刷新列表视图。
     *
     * @param enabled 是否开启多选模式
     */
    fun setSelectionMode(enabled: Boolean) {
        if (isSelectionMode != enabled) {
            isSelectionMode = enabled
            if (!enabled) {
                selectedIds.clear()
            }
            notifyDataSetChanged()
            onSelectionChanged?.invoke(selectedIds.size, currentList.size)
        }
    }

    /**
     * 切换指定记录 ID 的选中与未选中状态。
     *
     * @param id 目标记录 ID
     */
    fun toggleSelection(id: Long) {
        if (selectedIds.contains(id)) {
            selectedIds.remove(id)
        } else {
            selectedIds.add(id)
        }
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedIds.size, currentList.size)
    }

    /**
     * 将传入的所有记录 ID 全量选中。
     *
     * @param allIds 当前可见的记录 ID 集合
     */
    fun selectAll(allIds: Collection<Long>) {
        selectedIds.clear()
        selectedIds.addAll(allIds)
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedIds.size, currentList.size)
    }

    /**
     * 清空当前所有选中项。
     */
    fun deselectAll() {
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedIds.size, currentList.size)
    }

    /**
     * 对传入的所有记录 ID 执行反选操作（选中的取消，未选中的选中）。
     *
     * @param allIds 当前可见的记录 ID 集合
     */
    fun invertSelection(allIds: Collection<Long>) {
        val newSelected = mutableSetOf<Long>()
        for (id in allIds) {
            if (!selectedIds.contains(id)) {
                newSelected.add(id)
            }
        }
        selectedIds.clear()
        selectedIds.addAll(newSelected)
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedIds.size, currentList.size)
    }

    /**
     * 判定当前传入的所有记录 ID 是否已全部处于选中状态。
     *
     * @param allIds 当前可见的记录 ID 集合
     * @return 若集合非空且所有 ID 均被选中则返回 true，否则返回 false
     */
    fun isAllSelected(allIds: Collection<Long>): Boolean {
        if (allIds.isEmpty()) return false
        return selectedIds.containsAll(allIds)
    }

    /**
     * 获取当前所有已选中的记录 ID 集合。
     *
     * @return 已选记录 ID 集合
     */
    fun getSelectedIdSet(): Set<Long> {
        return selectedIds.toSet()
    }

    /**
     * 创建 ViewHolder 实例并绑定视图布局。
     *
     * @param parent 包含该视图的父容器
     * @param viewType 视图类型
     * @return 绑定的 [ViewHolder] 实例
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChargingHistoryRecordBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    /**
     * 将指定索引位置的充电历史实体绑定到视图中。
     *
     * @param holder 待绑定的 ViewHolder
     * @param position 列表数据索引
     */
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val record = getItem(position)
        holder.bind(record)
    }

    /**
     * 充电历史记录 ViewHolder 实体类。
     *
     * @param binding 列表单项的数据绑定对象
     */
    inner class ViewHolder(
        private val binding: ItemChargingHistoryRecordBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        /**
         * 填充历史记录数据到对应 UI 控件中。
         *
         * @param record 充电历史快照记录对象
         */
        fun bind(record: ChargingHistoryRecord) {
            // 1. 左上：充电起止时间范围（增加结束时间显示，如 "2026/09/07 11:28 ~ 11:53"）
            binding.tvHistoryTime.text = record.getFormattedTimeRange()

            // 2. 右上：平均充电功率数值（如 "18.50 W"）
            binding.tvHistoryPowerValue.text = String.format(
                Locale.getDefault(),
                "%.2f W",
                record.avgPowerWatts
            )

            // 3. 左下：充电时长与电量增量区间（如 "25m · 20%~85%(+65%)"）
            binding.tvHistorySubInfo.text = record.getFormattedDurationAndGain()

            // 4. 右下：功率说明标签（"平均充电功率"）
            binding.tvHistoryPowerLabel.text = record.getDisplayPowerLabel()

            // 5. 多选删除选择框显示与选中联动
            if (isSelectionMode) {
                binding.cbSelectItem.visibility = android.view.View.VISIBLE
                binding.cbSelectItem.isChecked = selectedIds.contains(record.id)

                binding.root.setOnClickListener {
                    toggleSelection(record.id)
                }
                binding.root.setOnLongClickListener(null)
            } else {
                binding.cbSelectItem.visibility = android.view.View.GONE

                // 点击整行条目触发跳转详情页面
                binding.root.setOnClickListener {
                    onItemClick(record)
                }

                // 长按条目触发删除确认弹窗
                binding.root.setOnLongClickListener {
                    onDeleteClick(record)
                    true
                }
            }
        }
    }


    /**
     * 充电历史数据实体 DiffUtil 比较回调。
     */
    private class ChargingHistoryDiffCallback : DiffUtil.ItemCallback<ChargingHistoryRecord>() {
        /**
         * 判定两项记录是否指向同一数据库主键 ID。
         *
         * @param oldItem 旧记录实体
         * @param newItem 新记录实体
         * @return 是否相同记录
         */
        override fun areItemsTheSame(
            oldItem: ChargingHistoryRecord,
            newItem: ChargingHistoryRecord
        ): Boolean {
            return oldItem.id == newItem.id
        }

        /**
         * 判定两项记录的内容数据是否完全一致。
         *
         * @param oldItem 旧记录实体
         * @param newItem 新记录实体
         * @return 内容是否一致
         */
        override fun areContentsTheSame(
            oldItem: ChargingHistoryRecord,
            newItem: ChargingHistoryRecord
        ): Boolean {
            return oldItem == newItem
        }
    }
}
