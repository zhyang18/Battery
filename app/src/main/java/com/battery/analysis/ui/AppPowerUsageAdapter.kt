package com.battery.analysis.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.battery.analysis.R
import com.battery.analysis.databinding.ItemAppPowerUsageBinding
import com.battery.analysis.model.AppPowerUsageItem
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.ShapeAppearanceModel
import java.util.Locale

/**
 * 应用使用场景与功耗列表适配器。
 * 负责展示应用图标、运行状态、平均功耗、温度指标及前台时长，并支持按耗电、功耗及时间动态排序切换。
 */
class AppPowerUsageAdapter : RecyclerView.Adapter<AppPowerUsageAdapter.ViewHolder>() {

    private val items = mutableListOf<AppPowerUsageItem>()

    // 排序模式：0-按使用时长降序，1-按平均功耗降序，2-按应用名称升序
    private var sortMode: Int = 0

    /**
     * 视图持有者，绑定 item_app_power_usage 视图层级。
     *
     * @param binding 视图绑定对象
     */
    inner class ViewHolder(val binding: ItemAppPowerUsageBinding) : RecyclerView.ViewHolder(binding.root)

    /**
     * 创建列表项视图持有者。
     *
     * @param parent 父容器视图
     * @param viewType 视图类型
     * @return 新创建的 [ViewHolder]
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppPowerUsageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        // 给应用图标应用圆角外观
        val shapeModel = ShapeAppearanceModel.builder()
            .setAllCorners(CornerFamily.ROUNDED, 24f)
            .build()
        binding.ivAppIcon.shapeAppearanceModel = shapeModel
        return ViewHolder(binding)
    }

    /**
     * 绑定列表项数据至视图组件。
     *
     * @param holder 视图持有者
     * @param position 数据项索引
     */
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        with(holder.binding) {
            if (item.icon != null) {
                ivAppIcon.setImageDrawable(item.icon)
            } else {
                ivAppIcon.setImageResource(R.mipmap.ic_launcher)
            }

            tvAppName.text = item.appName
            tvAvgInfo.text = String.format(
                Locale.getDefault(),
                "AVG: %.2fW, %d℃",
                item.avgPowerWatts,
                item.avgTemperature
            )
            tvAppEnergy.text = item.getFormattedEnergyWh()
            tvMaxTemp.text = String.format(
                Locale.getDefault(),
                "MAX: %d℃",
                item.maxTemperature
            )
            tvDuration.text = item.getFormattedDuration()
        }
    }

    /**
     * 获取数据列表总条数。
     *
     * @return 列表大小
     */
    override fun getItemCount(): Int = items.size

    /**
     * 提交并更新应用功耗列表数据源。
     *
     * @param newItems 新的应用功耗列表
     */
    fun submitList(newItems: List<AppPowerUsageItem>) {
        items.clear()
        items.addAll(newItems)
        applySort()
        notifyDataSetChanged()
    }

    /**
     * 切换排序模式并重新排序刷新列表。
     *
     * @param mode 0-按时长降序，1-按功耗降序，2-按消耗电量(Wh)降序，3-按名称升序
     */
    fun setSortMode(mode: Int) {
        sortMode = mode
        applySort()
        notifyDataSetChanged()
    }

    /**
     * 根据当前选中的排序模式对内部数据进行排序。
     */
    private fun applySort() {
        when (sortMode) {
            0 -> items.sortByDescending { it.foregroundTimeMs }
            1 -> items.sortByDescending { it.avgPowerWatts }
            2 -> items.sortByDescending { it.energyWh }
            3 -> items.sortBy { it.appName }
        }
    }
}
