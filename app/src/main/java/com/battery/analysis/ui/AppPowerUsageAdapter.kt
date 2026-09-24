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

import android.view.View
import android.widget.TextView
import com.battery.analysis.timeline.util.DrawableBitmapCache

/**
 * 应用使用场景与功耗列表适配器。
 * 负责展示应用图标、运行状态、平均功耗、温度指标及前台时长，并支持按耗电、功耗及时间动态排序切换。
 *
 * 优化：
 * 1. 采用按需分批/展开呈现机制（默认展示 Top 30 核心项，超量条目通过底部操作卡片按需展开），
 *    彻底根治 NestedScrollView 下一次性实例化上百个应用条目引发的 2000+ View 严重膨胀；
 * 2. 引入 [DrawableBitmapCache] 将应用图标限制在 42dp 像素规范，杜绝全分辨率大图占用 Native 堆；
 * 3. 使用 [DiffUtil] 结合增量更新，消除无意义的全量重绘开销。
 */
class AppPowerUsageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        /** 默认初始最大展示应用条目数，避免 NestedScrollView 无限高度下一口气创建千个 View 导致内存膨胀 */
        const val INITIAL_DISPLAY_LIMIT = 30
        /** 普通应用数据项视图类型 */
        private const val VIEW_TYPE_ITEM = 0
        /** 展开/收起更多应用底部操作卡片视图类型 */
        private const val VIEW_TYPE_EXPAND_FOOTER = 1
    }

    // 原始完整应用功耗数据集合
    private val allItems = mutableListOf<AppPowerUsageItem>()

    // 当前经筛选与排序后供列表渲染呈现的应用数据集合
    private val displayItems = mutableListOf<AppPowerUsageItem>()

    // 排序模式：0-按使用时长降序，1-按平均功耗降序，2-按消耗电量(Wh)降序，3-按应用名称升序
    private var sortMode: Int = 0

    // 是否展示后台统计数据及后台运行应用（默认开启）
    private var showBackgroundStats: Boolean = true

    // 标记是否已展开全部应用列表
    private var isExpanded: Boolean = false

    /**
     * 列表项点击事件回调监听器，向调用方传递被点击的应用使用场景数据实体。
     */
    var onItemClickListener: ((AppPowerUsageItem) -> Unit)? = null

    /**
     * 当前展示列表数据集发生更新（数据提交、过滤、排序切换）时的回调监听器。
     * 向外传递当前经筛选排序后实际渲染在列表中的条目总数。
     */
    var onListCountChangedListener: ((Int) -> Unit)? = null

    /**
     * 获取当前过滤与排序后在列表中实际展示的数据项总数。
     *
     * @return 当前展示的应用项总数
     */
    fun getDisplayItemCount(): Int = displayItems.size

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
     * 收起应用列表展示至初始限制数量（前 30 项），供界面切入后台或内存修剪时主动释放非活跃 Item 视图。
     */
    fun collapseToInitial() {
        if (isExpanded) {
            isExpanded = false
            notifyDataSetChanged()
        }
    }

    /**
     * 视图持有者，绑定 item_app_power_usage 视图层级。
     *
     * @param binding 视图绑定对象
     */
    inner class ViewHolder(val binding: ItemAppPowerUsageBinding) : RecyclerView.ViewHolder(binding.root)

    /**
     * 底部“展开/收起全部应用”操作卡片视图持有者。
     *
     * @param view 底部操作卡片根视图
     */
    inner class FooterViewHolder(view: View) : RecyclerView.ViewHolder(view)

    /**
     * 根据条目位置判断该条目的视图类型（普通应用条目还是展开/收起底部卡片）。
     *
     * @param position 列表项索引下标
     * @return 视图类型枚举值（[VIEW_TYPE_ITEM] 或 [VIEW_TYPE_EXPAND_FOOTER]）
     */
    override fun getItemViewType(position: Int): Int {
        val total = displayItems.size
        if (total > INITIAL_DISPLAY_LIMIT) {
            if (!isExpanded && position == INITIAL_DISPLAY_LIMIT) {
                return VIEW_TYPE_EXPAND_FOOTER
            } else if (isExpanded && position == total) {
                return VIEW_TYPE_EXPAND_FOOTER
            }
        }
        return VIEW_TYPE_ITEM
    }

    /**
     * 创建列表项视图持有者。
     *
     * @param parent 父容器视图
     * @param viewType 视图类型
     * @return 新创建的 [RecyclerView.ViewHolder]
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_EXPAND_FOOTER) {
            val view = LayoutInflater.from(parent.context).inflate(
                R.layout.item_app_power_expand_footer,
                parent,
                false
            )
            FooterViewHolder(view)
        } else {
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
            ViewHolder(binding)
        }
    }

    /**
     * 绑定列表项数据至视图组件。
     *
     * @param holder 视图持有者
     * @param position 数据项索引
     */
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is FooterViewHolder) {
            val tvHint = holder.itemView.findViewById<TextView>(R.id.tv_expand_hint)
            val total = displayItems.size
            if (!isExpanded) {
                val remaining = total - INITIAL_DISPLAY_LIMIT
                tvHint?.text = "查看全部应用 (共 ${total} 个，剩余 ${remaining} 个) ▾"
            } else {
                tvHint?.text = "收起部分应用 (恢复前 ${INITIAL_DISPLAY_LIMIT} 个) ▴"
            }
            holder.itemView.setOnClickListener {
                isExpanded = !isExpanded
                notifyDataSetChanged()
            }
            return
        }

        val itemHolder = holder as? ViewHolder ?: return
        if (position >= displayItems.size) return
        val item = displayItems[position]
        itemHolder.itemView.setOnClickListener {
            onItemClickListener?.invoke(item)
        }
        with(itemHolder.binding) {
            // 图标规格限制与 LRU 缓存复用：按 42dp 像素进行缩放，彻底消灭全分辨率大图占用 Native 堆
            val targetIconPx = (itemHolder.itemView.context.resources.displayMetrics.density * 42f).toInt()
            val cachedBitmap = DrawableBitmapCache.getOrConvertBitmap(
                item.packageName,
                item.icon,
                targetIconPx
            )
            if (cachedBitmap != null && !cachedBitmap.isRecycled) {
                ivAppIcon.setImageBitmap(cachedBitmap)
            } else if (item.icon != null) {
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
            val avgTempStr = if (item.avgTemperature > 0f) {
                String.format(Locale.getDefault(), "%.1f℃", item.avgTemperature)
            } else {
                "--"
            }
            tvAvgInfo.text = "AVG: $pwrStr, $avgTempStr"

            val energyStr = if (showBackgroundStats) {
                item.getFormattedCombinedEnergyWh()
            } else {
                item.getFormattedForegroundEnergyWh()
            }
            val maxTempStr = if (item.maxTemperature > 0f) {
                String.format(Locale.getDefault(), "MAX: %.1f℃", item.maxTemperature)
            } else {
                "MAX: --"
            }
            tvAppEnergy.text = "$maxTempStr | $energyStr"

            val hasFg = item.foregroundTimeMs > 0L
            val hasBg = showBackgroundStats && item.backgroundTimeMs > 0L

            if (hasFg) {
                layoutFgDuration.visibility = android.view.View.VISIBLE
                tvFgDuration.text = item.getFormattedDuration()
            } else if (!hasBg) {
                layoutFgDuration.visibility = android.view.View.VISIBLE
                tvFgDuration.text = "0s"
            } else {
                layoutFgDuration.visibility = android.view.View.GONE
            }

            // 中间竖线分隔符：仅在前后台时长同时显示时呈现
            tvDurationDivider.visibility = if (hasFg && hasBg) android.view.View.VISIBLE else android.view.View.GONE

            if (hasBg) {
                layoutBgDuration.visibility = android.view.View.VISIBLE
                tvBgDuration.text = item.getFormattedBackgroundDuration()
            } else {
                layoutBgDuration.visibility = android.view.View.GONE
            }
        }
    }

    /**
     * 获取数据列表当前实际向 RecyclerView 报告的渲染总条数。
     * 当总条目超出 [INITIAL_DISPLAY_LIMIT] 时：
     * 未展开状态渲染前 30 项 + 展开按钮；已展开状态渲染全部项 + 收起按钮。
     *
     * @return 实际渲染条目大小
     */
    override fun getItemCount(): Int {
        val total = displayItems.size
        return if (total <= INITIAL_DISPLAY_LIMIT) {
            total
        } else if (!isExpanded) {
            INITIAL_DISPLAY_LIMIT + 1
        } else {
            total + 1
        }
    }

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
        onListCountChangedListener?.invoke(displayItems.size)
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
        if (oldList.size > INITIAL_DISPLAY_LIMIT || newList.size > INITIAL_DISPLAY_LIMIT) {
            notifyDataSetChanged()
        } else {
            diffResult.dispatchUpdatesTo(this)
        }
    }
}


