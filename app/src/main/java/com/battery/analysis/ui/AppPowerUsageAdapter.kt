package com.battery.analysis.ui

import androidx.recyclerview.widget.DiffUtil
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
 *
 * 优化：使用 [DiffUtil] 替代 notifyDataSetChanged 全量刷新，
 * 仅对真正变化的条目执行增量更新，消除无意义的全量重绘开销。
 */
class AppPowerUsageAdapter : RecyclerView.Adapter<AppPowerUsageAdapter.ViewHolder>() {

    // 原始完整应用功耗数据集合
    private val allItems = mutableListOf<AppPowerUsageItem>()

    // 当前经筛选与排序后供列表渲染呈现的应用数据集合
    private val displayItems = mutableListOf<AppPowerUsageItem>()

    // 排序模式：0-按使用时长降序，1-按平均功耗降序，2-按消耗电量(Wh)降序，3-按应用名称升序
    private var sortMode: Int = 0

    // 是否展示后台统计数据及后台运行应用（默认开启）
    private var showBackgroundStats: Boolean = true

    /**
     * 列表项点击事件回调监听器，向调用方传递被点击的应用使用场景数据实体。
     */
    var onItemClickListener: ((AppPowerUsageItem) -> Unit)? = null

    /**
     * 设置是否展示后台统计数据及后台运行应用，并刷新列表视图。
     * 当开启时，显示全部应用（包含后台运行应用）及各应用的后台统计指标；
     * 当关闭时，过滤掉纯后台运行应用，仅显示前台运行应用（foregroundTimeMs > 0L）。
     *
     * @param show 是否展示后台运行应用及各应用的后台指标
     */
    fun setShowBackgroundStats(show: Boolean) {
        if (showBackgroundStats != show) {
            showBackgroundStats = show
            applyFilterAndSortWithDiff()
        }
    }

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
        val item = displayItems[position]
        holder.itemView.setOnClickListener {
            onItemClickListener?.invoke(item)
        }
        with(holder.binding) {
            if (item.icon != null) {
                ivAppIcon.setImageDrawable(item.icon)
            } else {
                ivAppIcon.setImageResource(R.mipmap.ic_launcher)
            }

            // 前后台状态标识圆点：前台运行应用显示绿色，纯后台应用显示蓝色
            if (item.foregroundTimeMs > 0L) {
                viewStatusDot.setBackgroundResource(R.drawable.bg_dot_green)
            } else {
                viewStatusDot.setBackgroundResource(R.drawable.bg_dot_blue)
            }

            tvAppName.text = item.appName
            val pwrStr = if (showBackgroundStats) {
                item.getFormattedCombinedAvgWatts()
            } else {
                item.getFormattedForegroundAvgWatts()
            }
            tvAvgInfo.text = String.format(
                Locale.getDefault(),
                "AVG: %s, %.1f℃",
                pwrStr,
                item.avgTemperature
            )
            val energyStr = if (showBackgroundStats) {
                item.getFormattedCombinedEnergyWh()
            } else {
                item.getFormattedForegroundEnergyWh()
            }
            tvAppEnergy.text = String.format(
                Locale.getDefault(),
//                "%s | MAX: %.1f℃",
                "MAX: %.1f℃ | %s",
                item.maxTemperature,
                energyStr,
            )
            tvDuration.text = if (showBackgroundStats) {
                item.getFormattedCombinedDuration()
            } else {
                item.getFormattedDuration()
            }
        }
    }

    /**
     * 获取数据列表总条数。
     *
     * @return 列表大小
     */
    override fun getItemCount(): Int = displayItems.size

    /**
     * 提交并更新应用功耗列表原始数据源，并根据当前过滤规则与排序方式以差量方式刷新展示列表。
     *
     * @param newItems 新的应用功耗列表
     */
    fun submitList(newItems: List<AppPowerUsageItem>) {
        allItems.clear()
        allItems.addAll(newItems)
        applyFilterAndSortWithDiff()
    }

    /**
     * 切换排序模式并以差量方式重新排序刷新列表。
     *
     * @param mode 0-按时长排序，1-按功耗升序，2-按消耗电量降序，3-按名称升序
     */
    fun setSortMode(mode: Int) {
        sortMode = mode
        applyFilterAndSortWithDiff()
    }

    /**
     * 根据后台统计开关对应用列表进行过滤，并依据当前排序模式对展示列表进行排序。
     * 排序规则：前台运行应用（foregroundTimeMs > 0L）始终排在前面，纯后台运行应用（foregroundTimeMs <= 0L）始终排在前台应用后面；
     * - 排序模式 0（按时长）：前台应用按前台时长降序，纯后台应用在后按后台时长降序；
     * - 排序模式 1（按功耗）：前台应用按功耗升序，纯后台应用在后按功耗升序；
     * - 排序模式 2（按电量）：前台应用按前台电量降序，纯后台应用在后按后台电量降序；
     * - 排序模式 3（按名称）：前台应用按名称升序，纯后台应用在后按名称升序；
     * 若关闭后台统计（showBackgroundStats == false），纯后台应用直接被过滤不予展示。
     */
    private fun applyFilterAndSort() {
        val fgList = allItems.filter { it.foregroundTimeMs > 0L }.toMutableList()
        val bgList = if (showBackgroundStats) {
            allItems.filter { it.foregroundTimeMs <= 0L }.toMutableList()
        } else {
            mutableListOf()
        }

        when (sortMode) {
            0 -> {
                fgList.sortByDescending { it.foregroundTimeMs }
                bgList.sortByDescending { it.backgroundTimeMs }
            }
            1 -> {
                fgList.sortBy { it.avgPowerWatts }
                bgList.sortBy { if (it.backgroundPowerWatts > 0f) it.backgroundPowerWatts else it.avgPowerWatts }
            }
            2 -> {
                fgList.sortByDescending { it.getForegroundEnergyValue() }
                bgList.sortByDescending { it.backgroundEnergyWh }
            }
            3 -> {
                fgList.sortBy { it.appName }
                bgList.sortBy { it.appName }
            }
        }

        displayItems.clear()
        displayItems.addAll(fgList)
        displayItems.addAll(bgList)
    }

    /**
     * 应用过滤与排序后，使用 [DiffUtil] 计算新旧列表差异，并以增量方式通知 RecyclerView 更新。
     * 相比 notifyDataSetChanged，仅对实际变化的条目执行插入、删除与变更动画，消除全量重绘开销。
     */
    private fun applyFilterAndSortWithDiff() {
        val oldList = displayItems.toList()
        applyFilterAndSort()
        val newList = displayItems.toList()

        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            /**
             * 返回旧列表大小。
             *
             * @return 旧列表条目数
             */
            override fun getOldListSize(): Int = oldList.size

            /**
             * 返回新列表大小。
             *
             * @return 新列表条目数
             */
            override fun getNewListSize(): Int = newList.size

            /**
             * 判断两个位置的条目是否代表同一对象（以包名为唯一标识）。
             *
             * @param oldItemPosition 旧列表中的位置
             * @param newItemPosition 新列表中的位置
             * @return 是否为同一应用条目
             */
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldList[oldItemPosition].packageName == newList[newItemPosition].packageName
            }

            /**
             * 判断两个条目的内容是否完全相同（用于决定是否触发 onBindViewHolder 刷新）。
             *
             * @param oldItemPosition 旧列表中的位置
             * @param newItemPosition 新列表中的位置
             * @return 内容是否相同（包括功耗、时长、温度等关键字段）
             */
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val old = oldList[oldItemPosition]
                val new = newList[newItemPosition]
                return old.packageName == new.packageName
                        && old.avgPowerWatts == new.avgPowerWatts
                        && old.energyWh == new.energyWh
                        && old.foregroundTimeMs == new.foregroundTimeMs
                        && old.backgroundTimeMs == new.backgroundTimeMs
                        && old.avgTemperature == new.avgTemperature
                        && old.maxTemperature == new.maxTemperature
            }
        })
        diffResult.dispatchUpdatesTo(this)
    }
}


