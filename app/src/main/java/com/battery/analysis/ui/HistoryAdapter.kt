package com.battery.analysis.ui

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.battery.analysis.R
import com.battery.analysis.databinding.ItemHistoryRecordBinding
import com.battery.analysis.model.HistoryRecord
import java.util.Locale

/**
 * 电池检测历史记录单行列表适配器。
 * 负责展示紧凑美观的单行历史快照条目，支持 DiffUtil 局部刷新与点击查看 14 项完整指标详情，并适配多语言显示。
 *
 * @param onItemClick 点击条目回调，用于弹出全量指标详情对话框
 * @param onDeleteClick 侧滑删除条目回调
 */
class HistoryAdapter(
    private val onItemClick: (HistoryRecord) -> Unit,
    private val onDeleteClick: (HistoryRecord) -> Unit
) : ListAdapter<HistoryRecord, HistoryAdapter.ViewHolder>(HistoryDiffCallback()) {

    /**
     * 创建 ViewHolder 视图持有者。
     *
     * @param parent 父视图容器
     * @param viewType 视图类型
     * @return 构建成功的 [ViewHolder] 实例
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemHistoryRecordBinding.inflate(
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
     * @param position 列表位置索引
     */
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val record = getItem(position)
        holder.bind(record)
    }

    /**
     * 历史记录单行列表 ViewHolder。
     *
     * @param binding 视图绑定对象
     */
    inner class ViewHolder(
        private val binding: ItemHistoryRecordBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        /**
         * 将历史记录实体数据绑定到单行条目控件。
         *
         * @param record 待展示的历史记录对象
         */
        fun bind(record: HistoryRecord) {
            val context: Context = binding.root.context

            // 1. 数据来源彩色徽章与时间戳
            val localizedCat = when (record.category) {
                "系统api" -> context.getString(R.string.tab_normal_api)
                "Shizuku" -> context.getString(R.string.tab_shizuku)
                "错误报告" -> context.getString(R.string.tab_bugreport)
                else -> record.category
            }
            binding.tvHistorySource.text = localizedCat

            when {
                record.category.contains("Shizuku", ignoreCase = true) -> {
                    binding.tvHistorySource.setTextColor(COLOR_SHIZUKU)
                }
                record.category.contains("错误报告", ignoreCase = true) -> {
                    binding.tvHistorySource.setTextColor(COLOR_BUGREPORT)
                }
                else -> {
                    binding.tvHistorySource.setTextColor(COLOR_NORMAL_API)
                }
            }
            binding.tvHistoryTime.text = record.captureTime

            // 2. 充满容量 / 设计容量展示
            val fullCapStr = record.fullChargeCapacity?.let { String.format(Locale.getDefault(), "%.0f", it) } ?: "-"
            val designCapStr = record.designCapacity?.let { String.format(Locale.getDefault(), "%.0f", it) } ?: "-"
            val capacityDisplay = if (fullCapStr != "-" || designCapStr != "-") {
                "$fullCapStr / $designCapStr mAh"
            } else {
                val levelStr = record.level?.let { "$it%" } ?: context.getString(R.string.unknown)
                val statusStr = record.status?.let { " ($it)" } ?: ""
                "$levelStr$statusStr"
            }
            binding.tvHistoryCapacity.text = capacityDisplay

            // 3. 电池健康度
            binding.tvHistoryHealth.text = record.batteryHealth?.let { String.format(Locale.getDefault(), "%.2f%%", it) } ?: "--"

            // 4. 循环次数
            binding.tvHistoryCycle.text = record.cycleCount?.let {
                context.getString(R.string.trend_tooltip_cycle_format, it)
            } ?: "${context.getString(R.string.history_detail_cycle)}: ${context.getString(R.string.unknown)}"

            // 5. 点击事件监听
            binding.root.setOnClickListener {
                onItemClick(record)
            }
        }
    }

    /**
     * 历史记录数据项差异比对回调，用于高效局部刷新。
     */
    class HistoryDiffCallback : DiffUtil.ItemCallback<HistoryRecord>() {
        /**
         * 判断两个条目是否代表同一条记录。
         *
         * @param oldItem 旧记录项
         * @param newItem 新记录项
         * @return 是否为同一实体
         */
        override fun areItemsTheSame(oldItem: HistoryRecord, newItem: HistoryRecord): Boolean {
            return oldItem.id == newItem.id
        }

        /**
         * 判断两个条目的内容是否完全相同。
         *
         * @param oldItem 旧记录项
         * @param newItem 新记录项
         * @return 内容是否完全一致
         */
        override fun areContentsTheSame(oldItem: HistoryRecord, newItem: HistoryRecord): Boolean {
            return oldItem == newItem
        }
    }

    companion object {
        private const val COLOR_SHIZUKU = 0xFF818CF8.toInt()
        private const val COLOR_BUGREPORT = 0xFF10B981.toInt()
        private const val COLOR_NORMAL_API = 0xFF2196F3.toInt()
    }
}
