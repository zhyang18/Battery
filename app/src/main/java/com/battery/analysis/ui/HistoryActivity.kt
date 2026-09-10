package com.battery.analysis.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.battery.analysis.R
import com.battery.analysis.databinding.ActivityHistoryBinding
import com.battery.analysis.model.DecayStatistics
import com.battery.analysis.model.HistoryRecord
import com.battery.analysis.model.PeriodicDecayItem
import com.battery.analysis.viewmodel.BatteryViewModel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 历史记录独立详情页面 Activity。
 * 承载电池健康度与充满容量历史快照列表，提供四大等宽分类筛选（全部、系统api、Shizuku、错误报告），
 * 当分类标签为“全部”时隐藏衰减趋势图卡片，在特定数据源分类下展示平滑贝塞尔健康度趋势图与日/月/年衰减速率，
 * 支持左右滑动删除、一键清空、保存当前快照、衰减明细弹窗以及载入快照至主界面查看。
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private val viewModel: BatteryViewModel by viewModels()
    private lateinit var adapter: HistoryAdapter

    private var shouldScrollToTopOnUpdate = false

    /**
     * 活动初始化生命周期回调，配置沉浸式状态栏、视图绑定、列表组件及事件监听。
     *
     * @param savedInstanceState 状态恢复 Bundle
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateSystemBarAppearance()

        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupCategoryTabs()
        setupListeners()
        observeData()

        viewModel.loadHistoryRecords(this)
    }

    /**
     * 界面恢复可见时的生命周期回调，自动触发最新历史数据库重载。
     */
    override fun onResume() {
        super.onResume()
        viewModel.loadHistoryRecords(this)
    }

    /**
     * 根据系统当前日夜间模式适配状态栏与导航栏图标色彩。
     */
    private fun updateSystemBarAppearance() {
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        val isNight = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        insetsController.isAppearanceLightStatusBars = !isNight
        insetsController.isAppearanceLightNavigationBars = !isNight
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

        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        binding.rvHistory.adapter = adapter
        binding.rvHistory.itemAnimator = DefaultItemAnimator()

        val swipeCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            private val bgPaint = Paint().apply {
                color = Color.parseColor("#EF4444")
                isAntiAlias = true
            }
            private val deleteIcon: Drawable? = ContextCompat.getDrawable(this@HistoryActivity, R.drawable.ic_delete)?.apply {
                setTint(ContextCompat.getColor(this@HistoryActivity, R.color.white))
            }

            /**
             * 列表项拖拽排序回调（当前禁用）。
             *
             * @param recyclerView 宿主 RecyclerView
             * @param viewHolder 被拖拽项 ViewHolder
             * @param target 目标位置 ViewHolder
             * @return 固定返回 false
             */
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            /**
             * 列表项滑动触发回调，弹出删除确认弹窗并在取消时复位条目。
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
             * 滑动过程中的背景与垃圾桶图标自定义绘制。
             *
             * @param c 绘图画布
             * @param recyclerView 宿主 RecyclerView
             * @param viewHolder 正在绘制的 ViewHolder
             * @param dX 水平位移距离
             * @param dY 垂直位移距离
             * @param actionState 交互状态
             * @param isCurrentlyActive 是否处于活跃触摸状态
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
                val itemMarginHorizontal = 16 * resources.displayMetrics.density
                val itemMarginVertical = 6 * resources.displayMetrics.density
                val cornerRadius = 16 * resources.displayMetrics.density

                if (dX < 0) {
                    val rectF = RectF(
                        itemView.right + dX - 20,
                        itemView.top + itemMarginVertical,
                        itemView.right - itemMarginHorizontal,
                        itemView.bottom - itemMarginVertical
                    )
                    c.drawRoundRect(rectF, cornerRadius, cornerRadius, bgPaint)

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
     * 初始化四大等宽分类筛选标签（全部、系统api、Shizuku、错误报告）。
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
                textView.setTextColor(ContextCompat.getColor(this, R.color.nav_item_selected))
                textView.setTypeface(null, android.graphics.Typeface.BOLD)
            } else {
                textView.setBackgroundResource(R.drawable.bg_filter_chip_normal)
                textView.setTextColor(Color.parseColor("#9CA3AF"))
                textView.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
        }
    }

    /**
     * 初始化顶部返回键、保存快照、清空按钮及下拉刷新手势监听器。
     */
    private fun setupListeners() {
        // 0. 返回按钮点击退出页面
        binding.btnBack.setOnClickListener {
            finish()
        }

        // 1. 顶部“保存快照”按钮
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
                Toast.makeText(this, getString(R.string.empty_history_title), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showClearAllConfirmDialog()
        }

        // 4. 下拉刷新重载历史数据库
        binding.swipeRefreshHistory.setColorSchemeColors(Color.parseColor("#2196F3"))
        binding.swipeRefreshHistory.setOnRefreshListener {
            viewModel.loadHistoryRecords(this)
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
        // 1. 观察当前选中的分类，更新标签外观并控制趋势图显隐
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.selectedCategory.collect { category ->
                    updateCategoryTabsState(category)
                    updateTrendChartCardVisibility(category, viewModel.healthTrendPoints.value)
                }
            }
        }

        // 2. 观察筛选过滤后的历史记录列表并同步更新顶部角标
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.filteredHistoryRecords.collect { list ->
                    binding.tvHistoryCount.text = getString(R.string.history_count_format, list.size)
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

        // 3. 观察当前分类下用于绘制趋势图的数据点集，若分类为“全部”则始终不显示图表卡片
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.healthTrendPoints.collect { points ->
                    binding.chartHealthTrend.setData(points)

                    val curCategory = viewModel.selectedCategory.value
                    updateTrendChartCardVisibility(curCategory, points)

                    if (curCategory != "全部" && points.isNotEmpty()) {
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
                    }
                }
            }
        }

        // 4. 观察电池健康度衰减统计数据流，更新每日、每月、每年衰减小卡片
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.decayStatistics.collect { stats ->
                    updateDecayStatCards(stats)
                }
            }
        }
    }

    /**
     * 根据当前选中的标签分类以及数据点集合动态控制电池健康度衰减趋势图卡片的显示或隐藏。
     * 当分类为“全部”时强制隐藏卡片；仅当分类为非“全部”且具备有效数据点时展示卡片。
     *
     * @param category 当前选中的分类标签字符串（如“全部”、“系统api”等）
     * @param points 当前分类计算得出的健康度折线趋势点列表
     */
    private fun updateTrendChartCardVisibility(category: String, points: List<com.battery.analysis.model.HealthTrendPoint>) {
        if (category == "全部" || points.isEmpty()) {
            binding.layoutTrendChartCard.visibility = View.GONE
        } else {
            binding.layoutTrendChartCard.visibility = View.VISIBLE
        }
    }

    /**
     * 更新趋势图下方的每日、每月、每年健康度衰减速率卡片展示内容及文字色彩。
     *
     * @param stats 当前计算得出的衰减统计结果对象
     */
    private fun updateDecayStatCards(stats: DecayStatistics) {
        if (stats.hasSufficientData) {
            stats.dailyDecayRate?.let { rate ->
                binding.tvStatDailyDecay.text = formatDecayValue(rate, 3)
                binding.tvStatDailyDecay.setTextColor(getDecayTextColor(rate))
            } ?: run {
                binding.tvStatDailyDecay.text = "--"
                binding.tvStatDailyDecay.setTextColor(Color.parseColor("#9CA3AF"))
            }

            stats.monthlyDecayRate?.let { rate ->
                binding.tvStatMonthlyDecay.text = formatDecayValue(rate, 2)
                binding.tvStatMonthlyDecay.setTextColor(getDecayTextColor(rate))
            } ?: run {
                binding.tvStatMonthlyDecay.text = "--"
                binding.tvStatMonthlyDecay.setTextColor(Color.parseColor("#9CA3AF"))
            }

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
        val numStr = if (rate > 0.0001f) {
            String.format(Locale.getDefault(), "+%.${decimals}f", rate)
        } else if (rate < -0.0001f) {
            String.format(Locale.getDefault(), "%.${decimals}f", rate)
        } else {
            String.format(Locale.getDefault(), "%.${decimals}f", 0f)
        }
        return getString(R.string.period_cycle_single_format, numStr).replace("•", "").trim()
    }

    /**
     * 根据衰减速率的正负返回对应的高亮警示或平稳色彩。
     *
     * @param rate 衰减速率数值
     * @return 颜色整数值 [Int]
     */
    private fun getDecayTextColor(rate: Float): Int {
        return when {
            rate > 0.0001f -> Color.parseColor("#EF4444")
            rate < -0.0001f -> Color.parseColor("#10B981")
            else -> Color.parseColor("#10B981")
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
                getString(R.string.decay_stat_decay_format, stats.totalDecay)
            } else if (stats.totalDecay < -0.001f) {
                getString(R.string.decay_stat_gain_format, -stats.totalDecay)
            } else {
                getString(R.string.decay_stat_flat)
            }
            val startShort = if ((stats.firstTime?.length ?: 0) >= 10) stats.firstTime!!.substring(0, 10) else (stats.firstTime ?: "")
            val lastShort = if ((stats.lastTime?.length ?: 0) >= 10) stats.lastTime!!.substring(0, 10) else (stats.lastTime ?: "")

            val cycleDetailStr = if (stats.totalCycleChange != null) {
                val sign = if (stats.totalCycleChange >= 0) "+" else ""
                val range = if (stats.firstCycle != null && stats.lastCycle != null) {
                    getString(R.string.decay_stat_cycle_range_format, stats.firstCycle, stats.lastCycle)
                } else ""
                getString(R.string.decay_stat_cycle_detail_template, sign, stats.totalCycleChange, range)
            } else ""

            tvStatDetails.text = getString(
                R.string.decay_stat_details_template,
                stats.totalPoints,
                spanText,
                startShort,
                lastShort,
                decayText,
                cycleDetailStr
            )
        } else {
            tvStatDetails.text = getString(R.string.decay_stat_insufficient_hint, stats.totalPoints)
        }

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

                    tvTitle.text = item.getFormattedPeriodLabel(this@HistoryActivity)

                    val cycleStr = if (item.cycleChange != null) {
                        val sign = if (item.cycleChange >= 0) "+${item.cycleChange}" else "${item.cycleChange}"
                        if (item.startCycle != null && item.endCycle != null && item.startCycle != item.endCycle) {
                            getString(R.string.period_cycle_range_format, item.startCycle, item.endCycle, sign)
                        } else {
                            getString(R.string.period_cycle_single_format, sign)
                        }
                    } else ""

                    tvSubInfo.text = "${getString(R.string.period_sample_count_format, item.sampleCount)}$cycleStr"
                    tvDecayBadge.text = item.formatDecay(this@HistoryActivity)
                    tvDecayBadge.setTextColor(getDecayTextColor(item.decay))
                    tvRangeText.text = String.format(Locale.getDefault(), "%.2f%% → %.2f%%", item.startHealth, item.endHealth)

                    if (index == items.size - 1) {
                        divider.visibility = View.GONE
                    }

                    container.addView(rowView)
                }
            }
        }

        fun updateTabUI(tab: String) {
            val tabs = listOf(
                Triple(btnTabDay, "DAY", stats.dailyItems),
                Triple(btnTabMonth, "MONTH", stats.monthlyItems),
                Triple(btnTabYear, "YEAR", stats.yearlyItems)
            )

            for ((button, t, items) in tabs) {
                if (t == tab) {
                    button.setBackgroundResource(R.drawable.bg_filter_chip_selected)
                    button.setTextColor(ContextCompat.getColor(this, R.color.nav_item_selected))
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

        val dialog = AlertDialog.Builder(this)
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
        shouldScrollToTopOnUpdate = true
        viewModel.saveAllSnapshots(this) { savedCategories ->
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
                Toast.makeText(this, getString(R.string.toast_save_snapshots_format, catStr), Toast.LENGTH_SHORT).show()
                binding.rvHistory.post {
                    binding.rvHistory.scrollToPosition(0)
                }
            } else {
                shouldScrollToTopOnUpdate = false
                Toast.makeText(this, getString(R.string.toast_no_data_to_save), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 弹出单条历史记录的完整指标详情对话框，并支持一键载入至健康度主界面查看。
     *
     * @param record 待查看的历史记录对象
     */
    private fun showRecordDetailsDialog(record: HistoryRecord) {
        val detailsText = record.formatFullDetails(this)
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

        val pairs = record.getDetailPairs(this)
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

            if (index == pairs.size - 1) {
                divider.visibility = View.GONE
            }

            container.addView(rowView)
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        btnCopy.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Battery History Record", detailsText)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, getString(R.string.toast_copy_success), Toast.LENGTH_SHORT).show()
        }

        val btnLoad = dialogView.findViewById<TextView>(R.id.btn_dialog_detail_load)
        btnLoad.setOnClickListener {
            dialog.dismiss()
            val resultIntent = Intent().apply {
                putExtra(EXTRA_LOAD_RECORD, record)
            }
            setResult(RESULT_LOAD_TO_MAIN, resultIntent)
            finish()
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
     * 弹出删除单条记录的确认对话框。
     *
     * @param record 待删除的历史记录对象
     */
    private fun showDeleteConfirmDialog(record: HistoryRecord) {
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

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnConfirm.setOnClickListener {
            viewModel.deleteHistoryRecord(this, record.id)
            Toast.makeText(this, getString(R.string.toast_delete_success), Toast.LENGTH_SHORT).show()
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
     * 弹出清空全部历史记录的警告对话框。
     */
    private fun showClearAllConfirmDialog() {
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

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnConfirm.setOnClickListener {
            viewModel.clearAllHistory(this)
            Toast.makeText(this, getString(R.string.toast_clear_success), Toast.LENGTH_SHORT).show()
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

    companion object {
        /**
         * 载入历史快照返回主页面的 Extra 键名。
         */
        const val EXTRA_LOAD_RECORD = "extra_load_record"

        /**
         * 载入历史快照至主界面的 ResultCode。
         */
        const val RESULT_LOAD_TO_MAIN = 2001
    }
}
