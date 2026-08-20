package com.battery.analysis.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.battery.analysis.R
import com.battery.analysis.databinding.FragmentHistoryBinding
import com.battery.analysis.model.DecayStatistics
import com.battery.analysis.model.HistoryRecord
import com.battery.analysis.model.PeriodicDecayItem
import com.battery.analysis.viewmodel.BatteryViewModel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 历史记录顶级页面 Fragment。
 * 负责展示电池健康度与充满容量历史快照列表，提供四大等宽分类筛选（全部、系统api、Shizuku、错误报告），
 * 集成平滑贝塞尔健康度趋势折线图、日/月/年衰减速率卡片及全量多维衰减明细弹窗，
 * 并支持左右滑动删除、一键清空与多语言动态适配。
 */
class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: BatteryViewModel by activityViewModels()
    private lateinit var adapter: HistoryAdapter

    private var shouldScrollToTopOnUpdate = false

    /**
     * 创建 Fragment 的视图层级。
     *
     * @param inflater 布局填充器
     * @param container 父容器视图
     * @param savedInstanceState 状态保存 Bundle
     * @return 初始化的根视图 [View]
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    /**
     * 视图创建完毕后的生命周期回调，配置 RecyclerView、分类按钮与数据观察。
     *
     * @param view 创建完成的根视图
     * @param savedInstanceState 状态保存 Bundle
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupCategoryTabs()
        setupListeners()
        observeData()
    }

    /**
     * 界面变为可见时的生命周期回调，自动触发一次本地数据库历史记录的最新重载。
     */
    override fun onResume() {
        super.onResume()
        val ctx = context ?: return
        viewModel.loadHistoryRecords(ctx)
    }

    /**
     * 初始化 RecyclerView 列表控件、适配器及左滑手势删除监听。
     */
    private fun setupRecyclerView() {
        adapter = HistoryAdapter(
            onItemClick = { record ->
                showRecordDetailsDialog(record)
            },
            onDeleteClick = { record ->
                showDeleteConfirmDialog(record)
            }
        )

        binding.rvHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.rvHistory.adapter = adapter
        binding.rvHistory.itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator()

        // 配置左滑手势直接删除
        val swipeCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            private val bgPaint = Paint().apply {
                color = Color.parseColor("#EF4444")
                isAntiAlias = true
            }
            private val deleteIcon: Drawable? = ContextCompat.getDrawable(requireContext(), R.drawable.ic_delete)?.apply {
                setTint(ContextCompat.getColor(requireContext(), R.color.white))
            }

            /**
             * 拖拽排序移动回调（本页面禁用上下拖拽）。
             *
             * @param recyclerView 目标 RecyclerView
             * @param viewHolder 被拖拽的 ViewHolder
             * @param target 目标位置 ViewHolder
             * @return 是否消费移动
             */
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            /**
             * 侧滑动作触发回调，弹出删除确认弹窗并在取消时复位条目。
             *
             * @param viewHolder 滑动的 ViewHolder
             * @param direction 滑动方向
             */
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val record = adapter.currentList.getOrNull(position)
                    if (record != null) {
                        adapter.notifyItemChanged(position)
                        showDeleteConfirmDialog(record)
                    }
                }
            }

            /**
             * 自定义绘制滑动背景及居中垃圾桶图标。
             *
             * @param c 画布
             * @param recyclerView 目标 RecyclerView
             * @param viewHolder 当前正在滑动的 ViewHolder
             * @param dX X轴位移距离
             * @param dY Y轴位移距离
             * @param actionState 动作状态
             * @param isCurrentlyActive 是否处于手势活跃中
             */
            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                val itemView = viewHolder.itemView
                if (dX < 0) { // 向左滑动
                    val cornerRadius = 12 * resources.displayMetrics.density
                    val rectF = RectF(
                        itemView.right + dX,
                        itemView.top.toFloat(),
                        itemView.right.toFloat(),
                        itemView.bottom.toFloat()
                    )
                    c.drawRoundRect(rectF, cornerRadius, cornerRadius, bgPaint)

                    // 绘制白色垃圾桶图标
                    deleteIcon?.let { icon ->
                        val iconMargin = (itemView.height - icon.intrinsicHeight) / 2
                        val iconTop = itemView.top + iconMargin
                        val iconBottom = iconTop + icon.intrinsicHeight
                        val iconRight = itemView.right - 24 * resources.displayMetrics.density.toInt()
                        val iconLeft = iconRight - icon.intrinsicWidth

                        if (itemView.right + dX < iconLeft) {
                            icon.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                            icon.draw(c)
                        }
                    }
                }
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        }

        ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.rvHistory)
    }

    /**
     * 初始化等宽均分的四大分类筛选标签（全部、系统api、Shizuku、错误报告）。
     */
    private fun setupCategoryTabs() {
        binding.btnFilterAll.setOnClickListener {
            viewModel.setSelectedCategory("全部")
        }
        binding.btnFilterNormal.setOnClickListener {
            viewModel.setSelectedCategory("系统api")
        }
        binding.btnFilterShizuku.setOnClickListener {
            viewModel.setSelectedCategory("Shizuku")
        }
        binding.btnFilterBugreport.setOnClickListener {
            viewModel.setSelectedCategory("错误报告")
        }
    }

    /**
     * 更新四大等宽分类按钮的选中高亮状态。
     *
     * @param selectedCategory 当前选中的分类名称
     */
    private fun updateCategoryTabsState(selectedCategory: String) {
        val tabs = listOf(
            Pair(binding.btnFilterAll, "全部"),
            Pair(binding.btnFilterNormal, "系统api"),
            Pair(binding.btnFilterShizuku, "Shizuku"),
            Pair(binding.btnFilterBugreport, "错误报告")
        )

        for ((textView, cat) in tabs) {
            val isSelected = (cat == selectedCategory)
            if (isSelected) {
                textView.setBackgroundResource(R.drawable.bg_filter_chip_selected)
                textView.setTextColor(ContextCompat.getColor(requireContext(), R.color.nav_item_selected))
                textView.setTypeface(null, android.graphics.Typeface.BOLD)
            } else {
                textView.setBackgroundResource(R.drawable.bg_filter_chip_normal)
                textView.setTextColor(Color.parseColor("#9CA3AF"))
                textView.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
        }
    }

    /**
     * 初始化顶部保存、清空按钮及下拉刷新手势监听器。
     */
    private fun setupListeners() {
        // 1. 顶部“保存快照”按钮：一键同时保存三种数据
        binding.btnSaveSnapshot.setOnClickListener {
            handleSaveAllSnapshots()
        }

        // 2. 空状态下的“立即保存当前快照”按钮
        binding.btnEmptySaveSnapshot.setOnClickListener {
            handleSaveAllSnapshots()
        }

        // 3. 顶部“一键清空全部”按钮
        binding.btnClearHistory.setOnClickListener {
            val count = viewModel.historyRecords.value.size
            if (count == 0) {
                Toast.makeText(requireContext(), getString(R.string.empty_history_title), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showClearAllConfirmDialog()
        }

        // 4. 下拉刷新重载历史数据库
        binding.swipeRefreshHistory.setColorSchemeColors(Color.parseColor("#2196F3"))
        binding.swipeRefreshHistory.setOnRefreshListener {
            val ctx = context
            if (ctx == null) {
                binding.swipeRefreshHistory.isRefreshing = false
                return@setOnRefreshListener
            }
            viewModel.loadHistoryRecords(ctx)
            binding.swipeRefreshHistory.isRefreshing = false
        }

        // 5. 衰减小卡片点击与明细按钮点击交互
        binding.btnViewDecayDetail.setOnClickListener {
            showDecayStatisticsDialog(viewModel.decayStatistics.value, "DAY")
        }
        binding.cardDailyDecay.setOnClickListener {
            showDecayStatisticsDialog(viewModel.decayStatistics.value, "DAY")
        }
        binding.cardMonthlyDecay.setOnClickListener {
            showDecayStatisticsDialog(viewModel.decayStatistics.value, "MONTH")
        }
        binding.cardYearlyDecay.setOnClickListener {
            showDecayStatisticsDialog(viewModel.decayStatistics.value, "YEAR")
        }
    }

    /**
     * 观察 ViewModel 中的历史数据流、选中分类流以及衰减与循环统计流，刷新界面状态。
     */
    private fun observeData() {
        // 1. 观察当前选中的分类，更新标签外观
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.selectedCategory.collect { category ->
                    updateCategoryTabsState(category)
                }
            }
        }

        // 2. 观察筛选过滤后的历史记录列表
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.filteredHistoryRecords.collect { list ->
                    adapter.submitList(list) {
                        if (shouldScrollToTopOnUpdate) {
                            binding.rvHistory.scrollToPosition(0)
                            shouldScrollToTopOnUpdate = false
                        }
                    }

                    // 控制空状态与列表显示
                    if (list.isEmpty()) {
                        binding.rvHistory.visibility = View.GONE
                        binding.layoutEmptyHistory.visibility = View.VISIBLE

                        val curCategory = viewModel.selectedCategory.value
                        if (curCategory == "全部") {
                            binding.tvEmptyTitle.text = getString(R.string.empty_history_title)
                            binding.tvEmptyDesc.text = getString(R.string.empty_history_desc)
                        } else {
                            val localizedCat = when (curCategory) {
                                "系统api" -> getString(R.string.tab_normal_api)
                                "Shizuku" -> getString(R.string.tab_shizuku)
                                "错误报告" -> getString(R.string.tab_bugreport)
                                else -> curCategory
                            }
                            binding.tvEmptyTitle.text = getString(R.string.empty_history_category_title, localizedCat)
                            binding.tvEmptyDesc.text = getString(R.string.empty_history_category_desc)
                        }
                    } else {
                        binding.rvHistory.visibility = View.VISIBLE
                        binding.layoutEmptyHistory.visibility = View.GONE
                    }
                }
            }
        }

        // 3. 观察总记录条数，更新顶部角标
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.filteredHistoryRecords.collect { list ->
                    binding.tvHistoryCount.text = getString(R.string.history_count_format, list.size)
                }
            }
        }

        // 4. 观察当前分类下用于绘制趋势图的数据点集
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.healthTrendPoints.collect { points ->
                    binding.chartHealthTrend.setData(points)

                    if (points.isNotEmpty()) {
                        binding.layoutTrendChartCard.visibility = View.VISIBLE
                        val latestHealth = points.last().health
                        if (points.size >= 2) {
                            val firstHealth = points.first().health
                            val delta = latestHealth - firstHealth
                            if (delta < 0) {
                                binding.tvTrendSummary.text = String.format(Locale.getDefault(), getString(R.string.trend_latest_decay_format), latestHealth, delta)
                                binding.tvTrendSummary.setTextColor(Color.parseColor("#EF4444"))
                            } else {
                                binding.tvTrendSummary.text = String.format(Locale.getDefault(), getString(R.string.trend_latest_format), latestHealth)
                                binding.tvTrendSummary.setTextColor(Color.parseColor("#10B981"))
                            }
                        } else {
                            binding.tvTrendSummary.text = String.format(Locale.getDefault(), getString(R.string.trend_current_format), latestHealth)
                            binding.tvTrendSummary.setTextColor(Color.parseColor("#10B981"))
                        }
                    } else {
                        binding.layoutTrendChartCard.visibility = View.GONE
                    }
                }
            }
        }

        // 5. 观察电池健康度衰减统计数据流，更新每日、每月、每年衰减小卡片
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.decayStatistics.collect { stats ->
                    updateDecayStatCards(stats)
                }
            }
        }
    }

    /**
     * 更新趋势图下方的每日、每月、每年健康度衰减速率卡片展示内容及文字色彩。
     *
     * @param stats 当前计算得出的衰减统计结果对象
     */
    private fun updateDecayStatCards(stats: DecayStatistics) {
        if (stats.hasSufficientData) {
            // 每日平均
            stats.dailyDecayRate?.let { rate ->
                binding.tvStatDailyDecay.text = formatDecayValue(rate, 3)
                binding.tvStatDailyDecay.setTextColor(getDecayTextColor(rate))
            } ?: run {
                binding.tvStatDailyDecay.text = "--"
                binding.tvStatDailyDecay.setTextColor(Color.parseColor("#9CA3AF"))
            }

            // 每月平均
            stats.monthlyDecayRate?.let { rate ->
                binding.tvStatMonthlyDecay.text = formatDecayValue(rate, 2)
                binding.tvStatMonthlyDecay.setTextColor(getDecayTextColor(rate))
            } ?: run {
                binding.tvStatMonthlyDecay.text = "--"
                binding.tvStatMonthlyDecay.setTextColor(Color.parseColor("#9CA3AF"))
            }

            // 每年预估
            stats.yearlyDecayRate?.let { rate ->
                binding.tvStatYearlyDecay.text = formatDecayValue(rate, 2)
                binding.tvStatYearlyDecay.setTextColor(getDecayTextColor(rate))
            } ?: run {
                binding.tvStatYearlyDecay.text = "--"
                binding.tvStatYearlyDecay.setTextColor(Color.parseColor("#9CA3AF"))
            }
        } else {
            binding.tvStatDailyDecay.text = "--"
            binding.tvStatDailyDecay.setTextColor(Color.parseColor("#9CA3AF"))
            binding.tvStatMonthlyDecay.text = "--"
            binding.tvStatMonthlyDecay.setTextColor(Color.parseColor("#9CA3AF"))
            binding.tvStatYearlyDecay.text = "--"
            binding.tvStatYearlyDecay.setTextColor(Color.parseColor("#9CA3AF"))
        }
    }

    /**
     * 格式化衰减数值带符号（衰减显示为负号，增益显示为正号）。
     *
     * @param rate 衰减速率数值（正数代表健康度下降损耗）
     * @param decimals 小数点保留位数
     * @return 格式化后的带符号文本（如 "-0.02%"、"+0.01%" 或 "0.00%"）
     */
    private fun formatDecayValue(rate: Float, decimals: Int): String {
        return if (rate > 0.0001f) {
            String.format(Locale.getDefault(), "-%.${decimals}f%%", rate)
        } else if (rate < -0.0001f) {
            String.format(Locale.getDefault(), "+%.${decimals}f%%", -rate)
        } else {
            String.format(Locale.getDefault(), "%.${decimals}f%%", 0f)
        }
    }

    /**
     * 格式化充放电循环次数消耗速率带符号。
     *
     * @param rate 循环消耗速率数值（正数代表循环消耗增长）
     * @param decimals 小数点保留位数
     * @return 格式化后的带符号文本（如 "+0.50 次"、"+15.2 次" 或 "0 次"）
     */
    private fun formatCycleRate(rate: Float, decimals: Int): String {
        return if (rate > 0.0001f) {
            String.format(Locale.getDefault(), "+%.${decimals}f 次", rate)
        } else if (rate < -0.0001f) {
            String.format(Locale.getDefault(), "%.${decimals}f 次", rate)
        } else {
            String.format(Locale.getDefault(), "%.${decimals}f 次", 0f)
        }
    }

    /**
     * 根据衰减速率的正负返回对应的高亮警示或平稳色彩。
     *
     * @param rate 衰减速率数值
     * @return 颜色整数值 [Int]
     */
    private fun getDecayTextColor(rate: Float): Int {
        return when {
            rate > 0.0001f -> Color.parseColor("#EF4444") // 下降衰减标红
            rate < -0.0001f -> Color.parseColor("#10B981") // 上升波动标绿
            else -> Color.parseColor("#10B981") // 持平标绿
        }
    }

    /**
     * 弹出电池健康度衰减与充放电循环消耗全量统计与按日/月/年周期明细对话框。
     *
     * @param stats 电池衰减与循环统计数据对象
     * @param initialTab 初始选中的周期分类 Tab（"DAY"、"MONTH" 或 "YEAR"）
     */
    private fun showDecayStatisticsDialog(
        stats: DecayStatistics,
        initialTab: String = "DAY"
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_decay_statistics, null)

        val tvDailyVal = dialogView.findViewById<TextView>(R.id.tv_modal_daily_val)
        val tvMonthlyVal = dialogView.findViewById<TextView>(R.id.tv_modal_monthly_val)
        val tvYearlyVal = dialogView.findViewById<TextView>(R.id.tv_modal_yearly_val)

        val tvDailyCycle = dialogView.findViewById<TextView>(R.id.tv_modal_daily_cycle)
        val tvMonthlyCycle = dialogView.findViewById<TextView>(R.id.tv_modal_monthly_cycle)
        val tvYearlyCycle = dialogView.findViewById<TextView>(R.id.tv_modal_yearly_cycle)

        val tvStatDetails = dialogView.findViewById<TextView>(R.id.tv_modal_stat_details)

        val btnTabDay = dialogView.findViewById<TextView>(R.id.btn_tab_day)
        val btnTabMonth = dialogView.findViewById<TextView>(R.id.btn_tab_month)
        val btnTabYear = dialogView.findViewById<TextView>(R.id.btn_tab_year)
        val container = dialogView.findViewById<ViewGroup>(R.id.layout_periodic_items_container)
        val tvEmptyHint = dialogView.findViewById<TextView>(R.id.tv_periodic_empty_hint)
        val btnClose = dialogView.findViewById<TextView>(R.id.btn_dialog_decay_close)

        val isZh = Locale.getDefault().language.startsWith("zh")

        // 1. 填充健康度衰减卡片数值
        if (stats.hasSufficientData) {
            stats.dailyDecayRate?.let {
                tvDailyVal.text = formatDecayValue(it, 3)
                tvDailyVal.setTextColor(getDecayTextColor(it))
            }
            stats.monthlyDecayRate?.let {
                tvMonthlyVal.text = formatDecayValue(it, 2)
                tvMonthlyVal.setTextColor(getDecayTextColor(it))
            }
            stats.yearlyDecayRate?.let {
                tvYearlyVal.text = formatDecayValue(it, 2)
                tvYearlyVal.setTextColor(getDecayTextColor(it))
            }
        } else {
            tvDailyVal.text = "--"
            tvDailyVal.setTextColor(Color.parseColor("#9CA3AF"))
            tvMonthlyVal.text = "--"
            tvMonthlyVal.setTextColor(Color.parseColor("#9CA3AF"))
            tvYearlyVal.text = "--"
            tvYearlyVal.setTextColor(Color.parseColor("#9CA3AF"))
        }

        // 2. 填充循环消耗卡片数值
        if (stats.hasCycleData) {
            stats.dailyCycleRate?.let {
                tvDailyCycle.text = formatCycleRate(it, 2)
                tvDailyCycle.setTextColor(Color.parseColor("#3B82F6"))
            }
            stats.monthlyCycleRate?.let {
                tvMonthlyCycle.text = formatCycleRate(it, 1)
                tvMonthlyCycle.setTextColor(Color.parseColor("#3B82F6"))
            }
            stats.yearlyCycleRate?.let {
                tvYearlyCycle.text = formatCycleRate(it, 0)
                tvYearlyCycle.setTextColor(Color.parseColor("#3B82F6"))
            }
        } else {
            tvDailyCycle.text = "--"
            tvDailyCycle.setTextColor(Color.parseColor("#9CA3AF"))
            tvMonthlyCycle.text = "--"
            tvMonthlyCycle.setTextColor(Color.parseColor("#9CA3AF"))
            tvYearlyCycle.text = "--"
            tvYearlyCycle.setTextColor(Color.parseColor("#9CA3AF"))
        }

        // 3. 填充辅助详细信息
        if (stats.hasSufficientData || stats.hasCycleData) {
            val spanText = String.format(Locale.getDefault(), "%.1f", stats.spanDays)
            val decayText = if (stats.totalDecay > 0.001f) {
                if (isZh) String.format(Locale.getDefault(), "衰减 %.2f%%", stats.totalDecay) else String.format(Locale.getDefault(), "Decay %.2f%%", stats.totalDecay)
            } else if (stats.totalDecay < -0.001f) {
                if (isZh) String.format(Locale.getDefault(), "上升 %.2f%%", -stats.totalDecay) else String.format(Locale.getDefault(), "Gain %.2f%%", -stats.totalDecay)
            } else {
                if (isZh) "持平 (0.00%)" else "Unchanged (0.00%)"
            }
            val startShort = if ((stats.firstTime?.length ?: 0) >= 10) stats.firstTime!!.substring(0, 10) else (stats.firstTime ?: "")
            val lastShort = if ((stats.lastTime?.length ?: 0) >= 10) stats.lastTime!!.substring(0, 10) else (stats.lastTime ?: "")

            val cycleDetailStr = if (stats.totalCycleChange != null) {
                val sign = if (stats.totalCycleChange >= 0) "+" else ""
                val range = if (stats.firstCycle != null && stats.lastCycle != null) " (${stats.firstCycle} 次 → ${stats.lastCycle} 次)" else ""
                if (isZh) "\n• 累计循环消耗: $sign${stats.totalCycleChange} 次$range" else "\n• Total Cycle Change: $sign${stats.totalCycleChange} cycles$range"
            } else ""

            if (isZh) {
                tvStatDetails.text = "• 有效采样快照: ${stats.totalPoints} 次\n• 时间跨度: $spanText 天 ($startShort ~ $lastShort)\n• 累计健康度变化: $decayText$cycleDetailStr"
            } else {
                tvStatDetails.text = "• Valid Snapshots: ${stats.totalPoints} records\n• Time Span: $spanText days ($startShort ~ $lastShort)\n• Total Health Change: $decayText$cycleDetailStr"
            }
        } else {
            if (isZh) {
                tvStatDetails.text = "• 有效采样快照: ${stats.totalPoints} 次\n• 提示: 需至少保存 2 次以上不同时间的健康度快照方可测算日/月/年衰减与循环消耗速率。"
            } else {
                tvStatDetails.text = "• Valid Snapshots: ${stats.totalPoints} records\n• Note: Save at least 2 snapshots from different timestamps to calculate decay and cycle consumption rates."
            }
        }

        // 4. 动态渲染周期明细方法
        fun renderPeriodicItems(items: List<PeriodicDecayItem>) {
            container.removeAllViews()
            if (items.isEmpty()) {
                tvEmptyHint.visibility = View.VISIBLE
            } else {
                tvEmptyHint.visibility = View.GONE
                items.forEachIndexed { index, item ->
                    val rowView = layoutInflater.inflate(R.layout.item_dialog_periodic_decay_row, container, false)
                    val tvTitle = rowView.findViewById<TextView>(R.id.tv_periodic_title)
                    val tvSubInfo = rowView.findViewById<TextView>(R.id.tv_periodic_sub_info)
                    val tvDecayBadge = rowView.findViewById<TextView>(R.id.tv_periodic_decay_badge)
                    val tvRangeText = rowView.findViewById<TextView>(R.id.tv_periodic_range_text)
                    val divider = rowView.findViewById<View>(R.id.divider_periodic_row)

                    tvTitle.text = item.periodLabel

                    val cycleStr = if (item.cycleChange != null) {
                        if (item.startCycle != null && item.endCycle != null && item.startCycle != item.endCycle) {
                            val sign = if (item.cycleChange >= 0) "+${item.cycleChange}" else "${item.cycleChange}"
                            if (isZh) " • 循环 ${item.startCycle} → ${item.endCycle} ($sign 次)" else " • Cycles ${item.startCycle} → ${item.endCycle} ($sign)"
                        } else {
                            val sign = if (item.cycleChange >= 0) "+${item.cycleChange}" else "${item.cycleChange}"
                            if (isZh) " • 循环 $sign 次" else " • Cycles $sign"
                        }
                    } else ""

                    if (isZh) {
                        tvSubInfo.text = "共 ${item.sampleCount} 次检测$cycleStr"
                    } else {
                        tvSubInfo.text = "${item.sampleCount} checks$cycleStr"
                    }

                    tvDecayBadge.text = item.formatDecay()
                    tvDecayBadge.setTextColor(getDecayTextColor(item.decay))

                    tvRangeText.text = String.format(Locale.getDefault(), "%.2f%% → %.2f%%", item.startHealth, item.endHealth)

                    if (index == items.size - 1) {
                        divider.visibility = View.GONE
                    }

                    container.addView(rowView)
                }
            }
        }

        // 5. Tab 切换状态处理（按日、按月、按年顺序排列）
        fun updateTabUI(tab: String) {
            val tabs = listOf(
                Triple(btnTabDay, "DAY", stats.dailyItems),
                Triple(btnTabMonth, "MONTH", stats.monthlyItems),
                Triple(btnTabYear, "YEAR", stats.yearlyItems)
            )

            for ((button, t, items) in tabs) {
                if (t == tab) {
                    button.setBackgroundResource(R.drawable.bg_filter_chip_selected)
                    button.setTextColor(ContextCompat.getColor(requireContext(), R.color.nav_item_selected))
                    button.setTypeface(null, android.graphics.Typeface.BOLD)
                    renderPeriodicItems(items)
                } else {
                    button.setBackgroundResource(R.drawable.bg_filter_chip_normal)
                    button.setTextColor(Color.parseColor("#9CA3AF"))
                    button.setTypeface(null, android.graphics.Typeface.NORMAL)
                }
            }
        }

        btnTabDay.setOnClickListener { updateTabUI("DAY") }
        btnTabMonth.setOnClickListener { updateTabUI("MONTH") }
        btnTabYear.setOnClickListener { updateTabUI("YEAR") }

        updateTabUI(initialTab)

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()

        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            val displayMetrics = resources.displayMetrics
            val width = (displayMetrics.widthPixels * 0.92).toInt()
            val height = (displayMetrics.heightPixels * 0.95).toInt()
            window.setLayout(width, height)
            window.setGravity(android.view.Gravity.CENTER)

            // 使用符合 FrameLayout 父容器要求的 FrameLayout.LayoutParams (支持 MarginLayoutParams)，彻底杜绝 ClassCastException 并锁定高度
            dialogView.layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            dialogView.minimumHeight = height
        }
    }

    /**
     * 一键同时保存系统api、Shizuku、错误报告三种分类的历史快照，并在成功后自动滚动列表至顶部。
     */
    private fun handleSaveAllSnapshots() {
        val ctx = context ?: return
        shouldScrollToTopOnUpdate = true
        viewModel.saveAllSnapshots(ctx) { savedCategories ->
            if (savedCategories.isNotEmpty()) {
                val localizedList = savedCategories.map { cat ->
                    when (cat) {
                        "系统api" -> getString(R.string.tab_normal_api)
                        "Shizuku" -> getString(R.string.tab_shizuku)
                        "错误报告" -> getString(R.string.tab_bugreport)
                        else -> cat
                    }
                }
                val catStr = localizedList.joinToString("、")
                Toast.makeText(ctx, getString(R.string.toast_save_snapshots_format, catStr), Toast.LENGTH_SHORT).show()
                binding.rvHistory.post {
                    binding.rvHistory.scrollToPosition(0)
                }
            } else {
                shouldScrollToTopOnUpdate = false
                Toast.makeText(ctx, getString(R.string.toast_no_data_to_save), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 弹出单条历史记录的完整 14 项指标大圆角自定义详情对话框（键值左右分开排列）。
     *
     * @param record 待查看的历史记录对象
     */
    private fun showRecordDetailsDialog(record: HistoryRecord) {
        val detailsText = record.formatFullDetails(requireContext())
        val dialogView = layoutInflater.inflate(R.layout.dialog_history_detail, null)

        val tvTitle = dialogView.findViewById<TextView>(R.id.tv_dialog_detail_title)
        val container = dialogView.findViewById<ViewGroup>(R.id.layout_detail_items_container)
        val btnClose = dialogView.findViewById<TextView>(R.id.btn_dialog_detail_close)
        val btnCopy = dialogView.findViewById<TextView>(R.id.btn_dialog_detail_copy)

        val localizedCat = when (record.category) {
            "系统api" -> getString(R.string.tab_normal_api)
            "Shizuku" -> getString(R.string.tab_shizuku)
            "错误报告" -> getString(R.string.tab_bugreport)
            else -> record.category
        }
        tvTitle.text = getString(R.string.history_detail_title_format, localizedCat)
        container.removeAllViews()

        val pairs = record.getDetailPairs(requireContext())
        pairs.forEachIndexed { index, triple ->
            val rowView = layoutInflater.inflate(R.layout.item_dialog_detail_row, container, false)
            val tvLabel = rowView.findViewById<TextView>(R.id.tv_row_label)
            val tvValue = rowView.findViewById<TextView>(R.id.tv_row_value)
            val divider = rowView.findViewById<View>(R.id.divider_row)

            tvLabel.text = triple.first
            tvValue.text = triple.second
            triple.third?.let { colorHex ->
                tvValue.setTextColor(Color.parseColor(colorHex))
            }

            // 最后一行隐藏分割线
            if (index == pairs.size - 1) {
                divider.visibility = View.GONE
            }

            container.addView(rowView)
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        btnCopy.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Battery History Record", detailsText)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), getString(R.string.toast_copy_success), Toast.LENGTH_SHORT).show()
        }

        dialog.show()

        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            val width = (resources.displayMetrics.widthPixels * 0.92).toInt()
            window.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setGravity(android.view.Gravity.CENTER)
        }
    }

    /**
     * 弹出删除单条记录的现代化精致二次确认对话框。
     *
     * @param record 待删除的历史记录对象
     */
    private fun showDeleteConfirmDialog(record: HistoryRecord) {
        val ctx = context ?: return
        val dialogView = layoutInflater.inflate(R.layout.dialog_custom_delete_confirm, null)

        val tvTitle = dialogView.findViewById<TextView>(R.id.tv_dialog_delete_title)
        val tvDesc = dialogView.findViewById<TextView>(R.id.tv_dialog_delete_desc)
        val layoutPreview = dialogView.findViewById<View>(R.id.layout_delete_item_preview)
        val tvPreviewCat = dialogView.findViewById<TextView>(R.id.tv_preview_cat)
        val tvPreviewTime = dialogView.findViewById<TextView>(R.id.tv_preview_time)
        val tvPreviewSummary = dialogView.findViewById<TextView>(R.id.tv_preview_summary)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btn_dialog_delete_cancel)
        val btnConfirm = dialogView.findViewById<TextView>(R.id.btn_dialog_delete_confirm)

        tvTitle.text = getString(R.string.dialog_delete_title)
        tvDesc.text = getString(R.string.dialog_delete_desc)
        layoutPreview.visibility = View.VISIBLE

        val localizedCat = when (record.category) {
            "系统api" -> getString(R.string.tab_normal_api)
            "Shizuku" -> getString(R.string.tab_shizuku)
            "错误报告" -> getString(R.string.tab_bugreport)
            else -> record.category
        }
        tvPreviewCat.text = localizedCat
        when {
            record.category.contains("Shizuku", ignoreCase = true) -> tvPreviewCat.setTextColor(Color.parseColor("#818CF8"))
            record.category.contains("错误报告", ignoreCase = true) -> tvPreviewCat.setTextColor(Color.parseColor("#10B981"))
            else -> tvPreviewCat.setTextColor(Color.parseColor("#2196F3"))
        }
        tvPreviewTime.text = record.captureTime

        val levelStr = record.level?.let { "🔋 $it%" } ?: "🔋 ${getString(R.string.unknown)}"
        val healthStr = record.batteryHealth?.let { "   💚 ${String.format(Locale.getDefault(), "%.2f%%", it)}" } ?: ""
        val cycleStr = record.cycleCount?.let { "   🔄 $it" } ?: ""
        tvPreviewSummary.text = "$levelStr$healthStr$cycleStr"

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnConfirm.setOnClickListener {
            viewModel.deleteHistoryRecord(ctx, record.id)
            Toast.makeText(ctx, getString(R.string.toast_delete_success), Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()

        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            val width = (resources.displayMetrics.widthPixels * 0.92).toInt()
            window.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setGravity(android.view.Gravity.CENTER)
        }
    }

    /**
     * 弹出清空全部历史记录的高颜值危险操作警告对话框。
     */
    private fun showClearAllConfirmDialog() {
        val ctx = context ?: return
        val totalCount = viewModel.historyRecords.value.size
        val dialogView = layoutInflater.inflate(R.layout.dialog_custom_delete_confirm, null)

        val tvTitle = dialogView.findViewById<TextView>(R.id.tv_dialog_delete_title)
        val tvDesc = dialogView.findViewById<TextView>(R.id.tv_dialog_delete_desc)
        val layoutPreview = dialogView.findViewById<View>(R.id.layout_delete_item_preview)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btn_dialog_delete_cancel)
        val btnConfirm = dialogView.findViewById<TextView>(R.id.btn_dialog_delete_confirm)

        tvTitle.text = getString(R.string.dialog_clear_all_title)
        tvDesc.text = getString(R.string.dialog_clear_all_desc, totalCount)
        layoutPreview.visibility = View.GONE
        btnConfirm.text = getString(R.string.clear_all)

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnConfirm.setOnClickListener {
            viewModel.clearAllHistory(ctx)
            Toast.makeText(ctx, getString(R.string.toast_clear_success), Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()

        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            val width = (resources.displayMetrics.widthPixels * 0.92).toInt()
            window.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setGravity(android.view.Gravity.CENTER)
        }
    }

    /**
     * 视图销毁时的清理工作。
     */
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /**
         * 静态工厂方法，用于创建 [HistoryFragment] 实例。
         *
         * @return 新建的 [HistoryFragment]
         */
        fun newInstance(): HistoryFragment {
            return HistoryFragment()
        }
    }
}
