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
 * 遵循现代化双行双列设计图样式渲染，展示起止时间范围、放电时长与电量变化区间、平均亮屏功耗数值与说明标签。
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
            // 1. 左上：起止时间范围（增加结束时间显示，如 "2026/09/07 12:26 ~ 13:12"）
            binding.tvHistoryTime.text = record.getFormattedTimeRange()

            // 2. 右上：功耗数值（如 "2.48 W"）
            binding.tvHistoryPowerValue.text = String.format(
                Locale.getDefault(),
                "%.2f W",
                record.getDisplayPowerWatts()
            )

            // 3. 左下：时长与电量变化区间（如 "46m · 76%~69%(-7%)"）
            binding.tvHistorySubInfo.text = record.getFormattedDurationAndLevel()

            // 4. 右下：功耗说明标签（如 "平均亮屏功耗"）
            binding.tvHistoryPowerLabel.text = record.getDisplayPowerLabel()

            // 5. 点击整行条目触发跳转详情查看
            binding.root.setOnClickListener {
                onItemClick(record)
            }

            // 6. 长按条目触发删除确认弹窗
            binding.root.setOnLongClickListener {
                onDeleteClick(record)
                true
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
