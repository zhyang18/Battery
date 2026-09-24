package com.battery.analysis.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.battery.analysis.databinding.ItemPowerHistoryRecordBinding
import com.battery.analysis.model.PowerUsageRecord
import java.util.Locale

/**
 * 耗电历史记录单行列表适配器。
 * 遵循现代化双行双列设计图样式渲染，展示起止时间范围、放电时长与电量变化区间、平均放电功耗数值与亮屏时间显示。
 * 支持点击条目跳转详情页面，并支持长按条目触发删除操作。
 *
 * @param onItemClick 点击列表项回调函数，触发详情页面跳转或快照载入
 * @param onDeleteClick 长按或点击删除回调函数，触发单条删除逻辑
 */
class PowerHistoryAdapter(
    private val onItemClick: (PowerUsageRecord) -> Unit,
    private val onDeleteClick: (PowerUsageRecord) -> Unit
) : ListAdapter<PowerUsageRecord, PowerHistoryAdapter.ViewHolder>(PowerHistoryDiffCallback()) {

    /**
     * 是否处于多选删除模式标记。
     */
    var isSelectionMode: Boolean = false
        private set

    /**
     * 当前已选中的耗电记录 ID 集合。
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
     * 创建 ViewHolder 视图持有者。
     *
     * @param parent 父容器视图
     * @param viewType 视图类型
     * @return 构建的 [ViewHolder] 实例
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPowerHistoryRecordBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    /**
     * 绑定指定位置的历史记录数据到 ViewHolder。
     *
     * @param holder 视图持有者
     * @param position 列表条目索引
     */
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val record = getItem(position)
        holder.bind(record)
    }

    /**
     * 耗电历史单行记录 ViewHolder。
     *
     * @property binding 条目视图绑定对象
     */
    inner class ViewHolder(
        private val binding: ItemPowerHistoryRecordBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        /**
         * 将耗电历史数据绑定到各 UI 控件中。
         *
         * @param record 待展示的耗电记录实体对象
         */
        fun bind(record: PowerUsageRecord) {
            // 1. 左上：起止时间范围（若处于进行中放电草稿状态，展示进行中前缀）
            val baseTimeRange = record.getFormattedTimeRange()
            binding.tvHistoryTime.text = if (!record.isCompleted) "⚡ $baseTimeRange" else baseTimeRange

            // 2. 右上：功耗数值（如 "2.48 W"）
            binding.tvHistoryPowerValue.text = String.format(
                Locale.getDefault(),
                "%.2f W",
                record.getDisplayPowerWatts()
            )

            // 3. 左下：时长与电量变化区间（如 "46m · 76%~69%(-7%)"）
            val baseSubInfo = record.getFormattedDurationAndLevel()
            binding.tvHistorySubInfo.text = if (!record.isCompleted) "$baseSubInfo · 进行中" else baseSubInfo

            // 4. 右下：亮屏时间数值（与右上角平均功耗值保持相同的大字粗体样式，如 "1h55m"）
            binding.tvHistoryScreenOnTime.text = record.getDisplayScreenOnDuration()

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

                // 点击整行条目触发跳转详情查看
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
     * 耗电历史列表条目差异比对回调类。
     */
    class PowerHistoryDiffCallback : DiffUtil.ItemCallback<PowerUsageRecord>() {
        /**
         * 判断两个条目是否代表同一条记录。
         *
         * @param oldItem 旧记录对象
         * @param newItem 新记录对象
         * @return 是否为同一条实体记录
         */
        override fun areItemsTheSame(oldItem: PowerUsageRecord, newItem: PowerUsageRecord): Boolean {
            return oldItem.id == newItem.id
        }

        /**
         * 判断两个条目的内容是否完全相同。
         *
         * @param oldItem 旧记录对象
         * @param newItem 新记录对象
         * @return 内容是否完全一致
         */
        override fun areContentsTheSame(oldItem: PowerUsageRecord, newItem: PowerUsageRecord): Boolean {
            return oldItem == newItem
        }
    }
}
