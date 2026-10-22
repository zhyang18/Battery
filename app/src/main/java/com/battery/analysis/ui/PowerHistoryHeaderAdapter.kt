package com.battery.analysis.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.battery.analysis.R
import com.battery.analysis.databinding.ItemPowerHistoryHeaderBinding
import com.battery.analysis.model.PowerHistorySummary
import com.battery.analysis.util.UnitSpanFormatter
import com.battery.analysis.util.setUnitText
import java.util.Locale

/**
 * 耗电记录历史列表头部总结看板适配器。
 * 挂载于主 RecyclerView 的顶部第 0 项（配合 ConcatAdapter 扁平化架构），
 * 负责展示指定放电历史集合的亮屏、息屏与总计三维度的时长、平均功耗及放电速度汇总信息，
 * 并支持卡片触控交互弹出高精度统计明细弹窗。
 *
 * @param onHeaderClick 列表头部看板卡片点击事件回调函数，传入当前的统计汇总对象
 */
class PowerHistoryHeaderAdapter(
    private val onHeaderClick: ((PowerHistorySummary) -> Unit)? = null
) : RecyclerView.Adapter<PowerHistoryHeaderAdapter.HeaderViewHolder>() {

    /**
     * 当前展示的耗电历史记录汇总统计实体。
     */
    var summary: PowerHistorySummary = PowerHistorySummary()
        private set

    /**
     * 更新头部看板汇总统计数据，并触发视图增量局部刷新。
     *
     * @param newSummary 最新计算的耗电历史汇总指标实体
     */
    fun updateSummary(newSummary: PowerHistorySummary) {
        this.summary = newSummary
        notifyItemChanged(0)
    }

    /**
     * 头部总结卡片 ViewHolder 视图持有者。
     *
     * @param binding 头部看板视图绑定对象
     */
    inner class HeaderViewHolder(
        val binding: ItemPowerHistoryHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root)

    /**
     * 创建头部 ViewHolder 实例。
     *
     * @param parent 父容器视图组
     * @param viewType 视图类型
     * @return 初始化的 [HeaderViewHolder]
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HeaderViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemPowerHistoryHeaderBinding.inflate(inflater, parent, false)
        return HeaderViewHolder(binding)
    }

    /**
     * 绑定头部看板视图数据。
     *
     * @param holder 头部视图持有者
     * @param position 列表项下标
     */
    override fun onBindViewHolder(holder: HeaderViewHolder, position: Int) {
        val b = holder.binding

        // 1. 亮屏维度（时长、功耗、速度）
        b.tvSummaryScreenOnDuration.setUnitText(summary.getFormattedScreenOnDuration())
        b.tvSummaryScreenOnPower.setUnitText(summary.getFormattedScreenOnPower())
        b.tvSummaryScreenOnSpeed.setUnitText(formatDischargeRateSpannable(summary.screenOnDischargeRate))

        // 3. 息屏维度（时长、功耗、速度）
        b.tvSummaryScreenOffDuration.setUnitText(summary.getFormattedScreenOffDuration())
        b.tvSummaryScreenOffPower.setUnitText(summary.getFormattedScreenOffPower())
        b.tvSummaryScreenOffSpeed.setUnitText(formatDischargeRateSpannable(summary.screenOffDischargeRate))

        // 4. 总计维度（时长、功耗、速度）
        b.tvSummaryTotalDuration.setUnitText(summary.getFormattedTotalDuration())
        b.tvSummaryTotalPower.setUnitText(summary.getFormattedAvgPower())
        b.tvSummaryTotalSpeed.setUnitText(formatDischargeRateSpannable(summary.globalDischargeRate))

        // 5. 点击弹出详细统计明细弹窗
        b.cardPowerHistorySummary.setOnClickListener {
            onHeaderClick?.invoke(summary)
        }
    }

    /**
     * 返回头部条目数量，固定为 1。
     *
     * @return 条目数 1
     */
    override fun getItemCount(): Int = 1

    /**
     * 将放电速度百分比速率格式化为图文混排富文本（单位 "%/h" 比前面的数值缩小 2 号显示）。
     *
     * @param rate 每小时放电百分比速率（单位：%/h），若为空或非正数返回 "--"
     * @return 图文混排的 [CharSequence]
     */
    private fun formatDischargeRateSpannable(rate: Float?): CharSequence {
        if (rate == null || rate <= 0f) {
            return "--"
        }
        val text = String.format(Locale.getDefault(), "%.1f%%/h", rate)
        return UnitSpanFormatter.format(text)
    }
}
