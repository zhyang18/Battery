package com.battery.analysis.ui

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.battery.analysis.R
import com.battery.analysis.databinding.ItemPowerHistoryRecordBinding
import com.battery.analysis.model.PowerUsageRecord
import java.util.Locale

/**
 * 耗电历史记录单行列表适配器。
 * 负责在弹窗中展示每次断电时自动保存的历史耗电快照列表，
 * 支持点击直接将快照加载到主页面全屏查看，并支持单条删除。
 *
 * @param onItemClick 点击列表项回调函数，触发快照数据加载
 * @param onDeleteClick 点击删除图标回调函数，触发单条删除逻辑
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
         * 将耗电历史数据绑定到各 UI 控件。
         *
         * @param record 待展示的耗电记录实体对象
         */
        fun bind(record: PowerUsageRecord) {
            val context: Context = binding.root.context

            // 1. 记录生成时间
            binding.tvHistoryTime.text = record.recordTime

            // 2. 模式徽章展示
            if (record.isShizukuRealData) {
                binding.tvHistoryModeBadge.text = "Shizuku"
                binding.tvHistoryModeBadge.setTextColor(Color.parseColor("#2196F3"))
                binding.tvHistoryModeBadge.setBackgroundResource(R.drawable.bg_history_badge)
            } else {
                binding.tvHistoryModeBadge.text = context.getString(R.string.power_mode_normal)
                binding.tvHistoryModeBadge.setTextColor(Color.parseColor("#9CA3AF"))
                binding.tvHistoryModeBadge.setBackgroundResource(R.drawable.bg_dialog_btn_cancel)
            }

            // 3. 核心概要信息组合（放电时长 • 平均功耗 • 应用数）
            val durationStr = if (record.totalDurationText.isNotBlank()) {
                "${context.getString(R.string.power_used_time)} ${record.totalDurationText}"
            } else {
                "${context.getString(R.string.power_used_time)} --"
            }
            val powerStr = String.format(Locale.getDefault(), "平均 %.2fW", record.avgPowerWatts)
            val appCntStr = context.getString(R.string.power_history_app_count_format, record.appCount)

            binding.tvHistorySubInfo.text = "$durationStr • $powerStr • $appCntStr"

            // 4. 电量百分比
            binding.tvHistoryLevel.text = "${record.levelPercent}%"

            // 5. 点击整行条目触发加载查看
            binding.root.setOnClickListener {
                onItemClick(record)
            }

            // 6. 点击删除图标触发删除确认
            binding.btnDeleteItem.setOnClickListener {
                onDeleteClick(record)
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
