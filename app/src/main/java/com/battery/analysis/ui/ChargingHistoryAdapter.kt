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

            // 5. 点击整行条目触发跳转详情页面
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
