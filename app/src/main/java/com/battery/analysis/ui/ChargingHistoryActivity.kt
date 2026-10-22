package com.battery.analysis.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import com.battery.analysis.R
import com.battery.analysis.databinding.ActivityChargingHistoryBinding
import com.battery.analysis.db.ChargingHistoryDbHelper
import com.battery.analysis.model.ChargingHistoryRecord
import com.battery.analysis.model.ChargingHistorySummary
import com.battery.analysis.util.setUnitText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 充电历史记录列表展示 Activity。
 * 遵循设计图样式渲染双行双列卡片，提供下拉刷新、条件胶囊筛选（≥20%、≥50%、≥80%），
 * 支持右上角进入多选批量删除模式（全选、反选、多选与删除确认弹窗）以及点击条目跳转至充电详情。
 */
class ChargingHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChargingHistoryBinding
    private lateinit var adapter: ChargingHistoryAdapter
    private lateinit var headerAdapter: ChargingHistoryHeaderAdapter
    private lateinit var dbHelper: ChargingHistoryDbHelper

    /**
     * 当前从数据库全量加载的历史记录内存快照列表。
     */
    private var allRecordList: List<ChargingHistoryRecord> = emptyList()

    /**
     * 标记当前是否正在异步加载或计算历史记录数据。
     */
    private var isDataLoading = false

    /**
     * 标记充电历史记录数据是否已完成初次加载，避免从详情页或后台返回时重复触发全量刷新与下拉进度动画。
     */
    private var isInitialLoaded = false

    private val detailLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            loadHistoryList(isPullToRefresh = false)
        }
    }

    /**
     * 充电历史记录电量增量筛选条件类型枚举。
     *
     * @property label 胶囊按钮与空状态提示文本
     * @property isLessThan 是否为小于条件（true 为 <，false 为 ≥）
     * @property threshold 筛选比较的增量阈值（百分比）
     */
    enum class ChargingGainFilterType(
        val label: String,
        val isLessThan: Boolean,
        val threshold: Int
    ) {
        GTE_20("≥20%", false, 20),
        GTE_50("≥50%", false, 50),
        GTE_80("≥80%", false, 80),
        LT_30("<30%", true, 30),
        LT_20("<20%", true, 20),
        LT_10("<10%", true, 10);

        /**
         * 判断指定充电记录的电量增量是否满足当前筛选条件。
         *
         * @param levelGain 待检查的电量增量百分比
         * @return 若满足条件返回 true，否则返回 false
         */
        fun matches(levelGain: Int): Boolean {
            return if (isLessThan) {
                levelGain < threshold
            } else {
                levelGain >= threshold
            }
        }
    }

    /**
     * 当前选中的电量净增量筛选类型，若为 null 则表示展示全部。
     */
    private var selectedGainFilter: ChargingGainFilterType? = null

    /**
     * 活动初始化生命周期回调，配置状态栏、初始化 RecyclerView、交互监听及系统返回拦截。
     *
     * @param savedInstanceState 状态恢复 Bundle
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateSystemBarAppearance()

        binding = ActivityChargingHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupEdgeToEdgeInsets()

        dbHelper = ChargingHistoryDbHelper.getInstance(this)
        com.battery.analysis.manager.ChargingStatsManager.getInstance(this).checkAndReconcileChargingState()

        setupRecyclerView()
        setupListeners()
        setupFilterChips()
        setupBackPressedHandler()
    }

    /**
     * 界面恢复生命周期回调。仅在初次进入页面时触发历史列表重载并展示下拉刷新进度指示器，
     * 从二级页面（如详情页）返回或切回前台时不再重复刷新。
     */
    override fun onResume() {
        super.onResume()
        com.battery.analysis.manager.ChargingStatsManager.getInstance(this).checkAndReconcileChargingState()
        if (!isInitialLoaded) {
            isInitialLoaded = true
            loadHistoryList(isPullToRefresh = true)
        }
    }

    /**
     * 根据当前日夜间主题适配状态栏明暗图标色彩。
     */
    private fun updateSystemBarAppearance() {
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        val isNight = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        insetsController.isAppearanceLightStatusBars = !isNight
        insetsController.isAppearanceLightNavigationBars = !isNight
    }

    /**
     * 配置全面屏边到边（Edge-to-Edge）沉浸式窗口边距自适应分发。
     * 针对 Android 15+ (API 35/36) 强制开启的 Edge-to-Edge 机制，动态为根布局设置状态栏与导航栏安全边距。
     */
    private fun setupEdgeToEdgeInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    /**
     * 初始化 RecyclerView 控件与数据适配器，通过 ConcatAdapter 挂载总结看板列表头，
     * 绑定点击跳转详情、长按删除、头部点击明细及选中状态变化回调。
     */
    private fun setupRecyclerView() {
        headerAdapter = ChargingHistoryHeaderAdapter(
            onHeaderClick = { summary ->
                showSummaryDetailDialog(summary)
            }
        )

        adapter = ChargingHistoryAdapter(
            onItemClick = { record ->
                openChargingDetail(record)
            },
            onDeleteClick = { record ->
                showDeleteRecordDialog(record)
            }
        )

        adapter.onSelectionChanged = { selectedCount, totalCount ->
            updateSelectionUI(selectedCount, totalCount)
        }

        val concatAdapter = ConcatAdapter(headerAdapter, adapter)
        binding.rvChargingHistory.layoutManager = LinearLayoutManager(this)
        binding.rvChargingHistory.adapter = concatAdapter
        binding.rvChargingHistory.setHasFixedSize(true)
        binding.rvChargingHistory.setItemViewCacheSize(20)
    }

    /**
     * 设置导航返回、批量删除操作、全选选择框及下拉刷新交互监听器。
     */
    private fun setupListeners() {
        // 1. 顶部返回按钮：处于多选模式时优先退出多选，否则结束当前 Activity
        binding.btnBack.setOnClickListener {
            if (adapter.isSelectionMode) {
                exitSelectionMode()
            } else {
                finish()
            }
        }

        // 2. 顶部删除图标按钮：未进入多选模式则开启多选，已在多选模式则触发批量删除确认弹窗
        binding.btnClearAll.setOnClickListener {
            if (!adapter.isSelectionMode) {
                if (allRecordList.isEmpty()) {
                    Toast.makeText(this, "暂无记录可删除", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                enterSelectionMode()
            } else {
                val selectedIds = adapter.getSelectedIdSet()
                if (selectedIds.isEmpty()) {
                    Toast.makeText(this, "请先选择要删除的记录", Toast.LENGTH_SHORT).show()
                } else {
                    showBatchDeleteConfirmDialog(selectedIds)
                }
            }
        }

        // 3. 全选选择框点击事件：当前全部选中时取消全选，否则全选当前可见列表
        val selectAllClickListener = View.OnClickListener {
            val visibleIds = adapter.currentList.map { it.id }
            if (adapter.isAllSelected(visibleIds)) {
                adapter.deselectAll()
            } else {
                adapter.selectAll(visibleIds)
            }
        }
        binding.cbSelectAll.setOnClickListener(selectAllClickListener)
        binding.layoutSelectAll.setOnClickListener(selectAllClickListener)

        // 4. 全选选择框长按事件：执行反选操作
        val selectAllLongClickListener = View.OnLongClickListener {
            val visibleIds = adapter.currentList.map { it.id }
            if (visibleIds.isNotEmpty()) {
                adapter.invertSelection(visibleIds)
                Toast.makeText(this, "已反选", Toast.LENGTH_SHORT).show()
            }
            true
        }
        binding.cbSelectAll.setOnLongClickListener(selectAllLongClickListener)
        binding.layoutSelectAll.setOnLongClickListener(selectAllLongClickListener)

        // 5. 下拉刷新
        binding.swipeRefresh.setColorSchemeResources(R.color.nav_item_selected)
        binding.swipeRefresh.setOnRefreshListener {
            loadHistoryList(isPullToRefresh = true)
        }
    }

    /**
     * 配置系统返回按键回调，当处于多选删除模式时优先退出多选模式。
     */
    private fun setupBackPressedHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (adapter.isSelectionMode) {
                    exitSelectionMode()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    /**
     * 初始化页面下方条件查询胶囊（≥20%、≥50%、≥80%、<30%、<20%、<10%）的点击监听。
     */
    private fun setupFilterChips() {
        val chips = listOf(
            Pair(binding.btnFilterGain20, ChargingGainFilterType.GTE_20),
            Pair(binding.btnFilterGain50, ChargingGainFilterType.GTE_50),
            Pair(binding.btnFilterGain80, ChargingGainFilterType.GTE_80),
            Pair(binding.btnFilterGainLt30, ChargingGainFilterType.LT_30),
            Pair(binding.btnFilterGainLt20, ChargingGainFilterType.LT_20),
            Pair(binding.btnFilterGainLt10, ChargingGainFilterType.LT_10)
        )

        for ((view, filterType) in chips) {
            view.setOnClickListener {
                selectedGainFilter = if (selectedGainFilter == filterType) null else filterType
                updateFilterChipsUI()
                applyFilterAndSubmit()
            }
        }
        updateFilterChipsUI()
    }

    /**
     * 刷新底部条件查询胶囊的高亮与选中视觉状态。
     */
    private fun updateFilterChipsUI() {
        val chips = listOf(
            Pair(binding.btnFilterGain20, ChargingGainFilterType.GTE_20),
            Pair(binding.btnFilterGain50, ChargingGainFilterType.GTE_50),
            Pair(binding.btnFilterGain80, ChargingGainFilterType.GTE_80),
            Pair(binding.btnFilterGainLt30, ChargingGainFilterType.LT_30),
            Pair(binding.btnFilterGainLt20, ChargingGainFilterType.LT_20),
            Pair(binding.btnFilterGainLt10, ChargingGainFilterType.LT_10)
        )

        val highlightColor = Color.parseColor("#8AB4F8")
        val normalColor = Color.parseColor("#9CA3AF")

        for ((view, filterType) in chips) {
            if (selectedGainFilter == filterType) {
                view.setBackgroundResource(R.drawable.bg_filter_capsule_selected)
                view.setTextColor(highlightColor)
                view.setTypeface(null, Typeface.BOLD)
            } else {
                view.setBackgroundResource(R.drawable.bg_filter_capsule_normal)
                view.setTextColor(normalColor)
                view.setTypeface(null, Typeface.NORMAL)
            }
        }
    }

    /**
     * 根据当前的筛选条件对全量数据进行过滤，并在后台异步计算总结看板指标后刷新列表。
     */
    private fun applyFilterAndSubmit() {
        val filter = selectedGainFilter
        lifecycleScope.launch(Dispatchers.Default) {
            val filteredList = if (filter == null) {
                allRecordList
            } else {
                allRecordList.filter { filter.matches(it.levelGain) }
            }
            val summary = ChargingHistorySummary.calculate(filteredList, applicationContext)

            // 后台线程预热展示文本，避免主线程滑动复用时耗时
            for (record in filteredList) {
                record.getFormattedTimeRange()
                record.getFormattedDurationAndGain()
            }

            withContext(Dispatchers.Main) {
                updateFilteredUI(filteredList, summary)
            }
        }
    }

    /**
     * 仅在主线程轻量级更新筛选后的列表数据、总结看板及空状态提示。
     *
     * @param filteredList 经过条件过滤的充电记录实体列表
     * @param summary 计算完成的充电汇总统计指标实体
     */
    private fun updateFilteredUI(filteredList: List<ChargingHistoryRecord>, summary: ChargingHistorySummary) {
        val filter = selectedGainFilter
        if (filteredList.isEmpty()) {
            binding.rvChargingHistory.visibility = View.GONE
            binding.layoutEmptyHistory.visibility = View.VISIBLE
            if (allRecordList.isEmpty()) {
                binding.tvEmptyTitle.text = getString(R.string.charging_history_empty_title)
                binding.tvEmptyDesc.text = getString(R.string.charging_history_empty_desc)
            } else {
                binding.tvEmptyTitle.text = "未找到符合条件的充电记录"
                binding.tvEmptyDesc.text = "暂无电量增量 ${filter?.label} 的充电记录"
            }
        } else {
            binding.rvChargingHistory.visibility = View.VISIBLE
            binding.layoutEmptyHistory.visibility = View.GONE
        }

        headerAdapter.updateSummary(summary)

        if (!adapter.isSelectionMode) {
            binding.tvTitle.text = "${getString(R.string.charging_history_title)}(${filteredList.size})"
        }

        adapter.submitList(filteredList) {
            if (adapter.isSelectionMode) {
                val visibleIds = filteredList.map { it.id }
                binding.cbSelectAll.isChecked = adapter.isAllSelected(visibleIds)
            }
        }
    }

    /**
     * 进入多选删除模式，显示全选框并更新标题提示。
     */
    private fun enterSelectionMode() {
        adapter.setSelectionMode(true)
        binding.layoutSelectAll.visibility = View.VISIBLE
        updateSelectionUI(adapter.selectedIds.size, adapter.currentList.size)
    }

    /**
     * 退出多选删除模式，清空选中集并隐藏全选框，恢复展示充电历史记录(列表个数)。
     */
    private fun exitSelectionMode() {
        adapter.setSelectionMode(false)
        binding.layoutSelectAll.visibility = View.GONE
        binding.cbSelectAll.isChecked = false
        binding.tvTitle.text = "${getString(R.string.charging_history_title)}(${adapter.currentList.size})"
    }

    /**
     * 响应选择状态变化，实时更新顶部标题与全选复选框选中状态。
     *
     * @param selectedCount 当前选中的记录条数
     * @param totalCount 当前展示的总记录条数
     */
    private fun updateSelectionUI(selectedCount: Int, totalCount: Int) {
        if (adapter.isSelectionMode) {
            binding.tvTitle.text = if (selectedCount > 0) "已选 ${selectedCount} 项" else "选择记录"
            val visibleIds = adapter.currentList.map { it.id }
            binding.cbSelectAll.isChecked = adapter.isAllSelected(visibleIds)
        } else {
            binding.tvTitle.text = "${getString(R.string.charging_history_title)}($totalCount)"
        }
    }

    /**
     * 异步从本地数据库检索所有历史充电记录并刷新列表与空状态占位。
     * 进入页面或下拉刷新时展示下拉进度指示器，所有数据检索、条件过滤、Summary 统计及字段预热均在 IO 线程池完成。
     *
     * @param isPullToRefresh 是否展示下拉旋转进度指示器（true 时通过 post 安全机制展示下拉刷新进度，false 时静默刷新）
     */
    private fun loadHistoryList(isPullToRefresh: Boolean = true) {
        if (isPullToRefresh) {
            isDataLoading = true
            binding.swipeRefresh.post {
                if (isDataLoading) {
                    binding.swipeRefresh.isRefreshing = true
                }
            }
        }
        lifecycleScope.launch {
            try {
                val (list, filteredList, summary) = withContext(Dispatchers.IO) {
                    val rawList = dbHelper.getAllRecords()
                    val filter = selectedGainFilter
                    val filtered = if (filter == null) {
                        rawList
                    } else {
                        rawList.filter { filter.matches(it.levelGain) }
                    }
                    val calculatedSummary = ChargingHistorySummary.calculate(filtered, applicationContext)

                    // 后台 IO 线程预热各条目的格式化显示文本，彻底消除主线程绘制与滑动绑定的任何开销
                    for (record in filtered) {
                        record.getFormattedTimeRange()
                        record.getFormattedDurationAndGain()
                    }
                    Triple(rawList, filtered, calculatedSummary)
                }

                allRecordList = list
                updateFilteredUI(filteredList, summary)
            } finally {
                isDataLoading = false
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    /**
     * 打开单次充电会话的详情展示 Activity。
     *
     * @param record 待查看详情的充电历史记录实体
     */
    private fun openChargingDetail(record: ChargingHistoryRecord) {
        val intent = Intent(this, ChargingHistoryDetailActivity::class.java).apply {
            putExtra(ChargingHistoryDetailActivity.EXTRA_RECORD_ID, record.id)
        }
        detailLauncher.launch(intent)
    }

    /**
     * 为自定义对话框应用居中、平滑弹窗动效、半透明背景及适屏宽度的窗口样式，支持指定弹窗屏幕高度占比。
     *
     * @param dialog 待配置样式的 [AlertDialog] 实例
     * @param widthRatio 对话框宽度相对屏幕可用物理宽度的比例（默认 0.92）
     * @param heightRatio 对话框高度相对屏幕可用物理高度的比例（若为空则使用 WRAP_CONTENT）
     */
    private fun applyDialogWindowStyle(
        dialog: AlertDialog,
        widthRatio: Double = 0.92,
        heightRatio: Float? = null
    ) {
        dialog.window?.let { window ->
            window.setWindowAnimations(R.style.Animation_Dialog_CenterPop)
            window.setBackgroundDrawableResource(android.R.color.transparent)
            val displayMetrics = resources.displayMetrics
            val width = (displayMetrics.widthPixels * widthRatio).toInt()
            val height = if (heightRatio != null && heightRatio > 0f) {
                (displayMetrics.heightPixels * heightRatio).toInt()
            } else {
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            }
            window.setLayout(width, height)
            window.setGravity(android.view.Gravity.CENTER)
        }
    }

    /**
     * 弹出删除单条充电记录的高颜值确认对话框。
     *
     * @param record 待删除的充电历史记录实体
     */
    private fun showDeleteRecordDialog(record: ChargingHistoryRecord) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_custom_delete_confirm, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tv_dialog_delete_title)
        val tvDesc = dialogView.findViewById<TextView>(R.id.tv_dialog_delete_desc)
        val tvPreviewCat = dialogView.findViewById<TextView>(R.id.tv_preview_cat)
        val tvPreviewTime = dialogView.findViewById<TextView>(R.id.tv_preview_time)
        val tvPreviewSummary = dialogView.findViewById<TextView>(R.id.tv_preview_summary)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btn_dialog_delete_cancel)
        val btnConfirm = dialogView.findViewById<TextView>(R.id.btn_dialog_delete_confirm)

        tvTitle.text = "确认删除此充电记录？"
        tvDesc.text = "删除后该条充电历史记录将从本地永久移除，无法找回。"
        tvPreviewCat.text = if (record.chargeType.isNotEmpty()) record.chargeType else "充电记录"
        tvPreviewTime.text = record.recordTime
        val effEnd = record.getEffectiveEndLevel()
        val effGain = record.getEffectiveLevelGain()
        val sign = if (effGain >= 0) "+${effGain}%" else "${effGain}%"
        tvPreviewSummary.text = "⚡ $sign (${record.startLevel}% → ${effEnd}%)   •   ⏱️ ${record.getFormattedDuration()}"

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnConfirm.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                dbHelper.deleteRecord(record.id)
                withContext(Dispatchers.Main) {
                    loadHistoryList()
                    dialog.dismiss()
                }
            }
        }

        dialog.show()
        applyDialogWindowStyle(dialog)
    }

    /**
     * 弹出批量删除所选充电历史记录的高颜值确认对话框。
     *
     * @param selectedIds 待批量删除的记录主键 ID 集合
     */
    private fun showBatchDeleteConfirmDialog(selectedIds: Set<Long>) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_custom_delete_confirm, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tv_dialog_delete_title)
        val tvDesc = dialogView.findViewById<TextView>(R.id.tv_dialog_delete_desc)
        val layoutPreview = dialogView.findViewById<View>(R.id.layout_delete_item_preview)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btn_dialog_delete_cancel)
        val btnConfirm = dialogView.findViewById<TextView>(R.id.btn_dialog_delete_confirm)

        tvTitle.text = "确认删除选中的 ${selectedIds.size} 条记录？"
        tvDesc.text = "删除后所选的充电历史记录将从本地永久移除，无法找回。"
        layoutPreview.visibility = View.GONE

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnConfirm.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                dbHelper.deleteRecords(selectedIds)
                withContext(Dispatchers.Main) {
                    exitSelectionMode()
                    loadHistoryList()
                    dialog.dismiss()
                    Toast.makeText(this@ChargingHistoryActivity, "已删除 ${selectedIds.size} 条记录", Toast.LENGTH_SHORT).show()
                }
            }
        }

        dialog.show()
        applyDialogWindowStyle(dialog)
    }

    /**
     * 弹出充电历史统计总结的完整详细指标与物理推导依据说明对话框。
     * 以结构化对比表格直观展示亮屏、息屏与总计三维度的时长（含占比）、能量（含等效 mAh）、平均充电功率及充电速度。
     * 所有物理单位统一前置于左侧指标列，右侧数值列展示紧凑纯数字以避免内容截断。
     *
     * @param summary 当前计算的充电历史汇总实体对象 [ChargingHistorySummary]
     */
    private fun showSummaryDetailDialog(summary: ChargingHistorySummary) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_charging_history_summary, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tv_dialog_summary_title)
        val tvSubtitle = dialogView.findViewById<TextView>(R.id.tv_dialog_summary_subtitle)
        val btnClose = dialogView.findViewById<TextView>(R.id.btn_dialog_summary_close)

        tvTitle.text = getString(R.string.charging_history_summary_dialog_title)

        // 1. 范围与累计充入电量百分比
        val gainText = if (summary.totalLevelGainPercent > 0) " · 累计充入 +${summary.totalLevelGainPercent}%" else ""
        tvSubtitle.setUnitText("📊 统计范围：共 ${summary.recordCount} 条充电记录$gainText")

        // 2. 时长行（纯数字时间简写与占比）
        val onDurPct = if (summary.totalDurationMs > 0L) {
            String.format(java.util.Locale.getDefault(), "(%.1f%%)", (summary.screenOnDurationMs.toDouble() / summary.totalDurationMs.toDouble()) * 100.0)
        } else ""
        val offDurPct = if (summary.totalDurationMs > 0L) {
            String.format(java.util.Locale.getDefault(), "(%.1f%%)", (summary.screenOffDurationMs.toDouble() / summary.totalDurationMs.toDouble()) * 100.0)
        } else ""
        dialogView.findViewById<TextView>(R.id.tv_dialog_screen_on_dur).setUnitText(summary.getFormattedScreenOnDuration())
        dialogView.findViewById<TextView>(R.id.tv_dialog_screen_on_dur_pct).setUnitText(onDurPct)
        dialogView.findViewById<TextView>(R.id.tv_dialog_screen_off_dur).setUnitText(summary.getFormattedScreenOffDuration())
        dialogView.findViewById<TextView>(R.id.tv_dialog_screen_off_dur_pct).setUnitText(offDurPct)
        dialogView.findViewById<TextView>(R.id.tv_dialog_total_dur).setUnitText(summary.getFormattedTotalDuration())

        // 3. 能量行（纯数值 Wh 与纯数值等效 mAh，单位已前置于左侧指标列）
        val onMah = summary.convertEnergyWhToMah(summary.screenOnEnergyWh)
        val offMah = summary.convertEnergyWhToMah(summary.screenOffEnergyWh)
        val totalMah = summary.convertEnergyWhToMah(summary.totalEnergyWh)

        dialogView.findViewById<TextView>(R.id.tv_dialog_screen_on_energy).text =
            if (summary.screenOnEnergyWh > 0.0) String.format(java.util.Locale.getDefault(), "%.2f", summary.screenOnEnergyWh) else "--"
        dialogView.findViewById<TextView>(R.id.tv_dialog_screen_on_energy_mah).text =
            if (onMah > 0f) String.format(java.util.Locale.getDefault(), "(%.0f)", onMah) else "--"

        dialogView.findViewById<TextView>(R.id.tv_dialog_screen_off_energy).text =
            if (summary.screenOffEnergyWh > 0.0) String.format(java.util.Locale.getDefault(), "%.2f", summary.screenOffEnergyWh) else "--"
        dialogView.findViewById<TextView>(R.id.tv_dialog_screen_off_energy_mah).text =
            if (offMah > 0f) String.format(java.util.Locale.getDefault(), "(%.0f)", offMah) else "--"

        dialogView.findViewById<TextView>(R.id.tv_dialog_total_energy).text =
            if (summary.totalEnergyWh > 0.0) String.format(java.util.Locale.getDefault(), "%.2f", summary.totalEnergyWh) else "--"
        dialogView.findViewById<TextView>(R.id.tv_dialog_total_energy_mah).text =
            if (totalMah > 0f) String.format(java.util.Locale.getDefault(), "(%.0f)", totalMah) else "--"

        // 4. 功率行（纯数值，单位 W 已前置于指标列）
        dialogView.findViewById<TextView>(R.id.tv_dialog_screen_on_power).text =
            summary.screenOnPowerWatts?.let { String.format(java.util.Locale.getDefault(), "%.2f", it) } ?: "--"
        dialogView.findViewById<TextView>(R.id.tv_dialog_screen_off_power).text =
            summary.screenOffPowerWatts?.let { String.format(java.util.Locale.getDefault(), "%.2f", it) } ?: "--"
        dialogView.findViewById<TextView>(R.id.tv_dialog_total_power).text =
            summary.avgPowerWatts?.let { String.format(java.util.Locale.getDefault(), "%.2f", it) } ?: "--"

        // 5. 充电速度行（纯数值，单位 %/h 已前置于指标列）
        dialogView.findViewById<TextView>(R.id.tv_dialog_screen_on_speed).text =
            summary.screenOnChargingRate?.let { String.format(java.util.Locale.getDefault(), "%.1f", it) } ?: "--"
        dialogView.findViewById<TextView>(R.id.tv_dialog_screen_off_speed).text =
            summary.screenOffChargingRate?.let { String.format(java.util.Locale.getDefault(), "%.1f", it) } ?: "--"
        dialogView.findViewById<TextView>(R.id.tv_dialog_total_speed).text =
            summary.globalChargingRate?.let { String.format(java.util.Locale.getDefault(), "%.1f", it) } ?: "--"

        // 6. 底部最高瞬时功率与最高温升说明
        val tvPeaks = dialogView.findViewById<TextView>(R.id.tv_dialog_charging_peaks)
        val peakParts = mutableListOf<String>()
        summary.maxPowerWatts?.let { peakParts.add(String.format(java.util.Locale.getDefault(), "⚡ 最高充电功率：%.1f W", it)) }
        summary.maxTemperature?.let { peakParts.add(String.format(java.util.Locale.getDefault(), "🌡️ 电池最高温度：%.1f ℃", it)) }
        if (peakParts.isNotEmpty()) {
            tvPeaks.setUnitText(peakParts.joinToString("  •  "))
            tvPeaks.visibility = View.VISIBLE
        } else {
            tvPeaks.visibility = View.GONE
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
        // 对话框卡片加宽至 95% 屏幕宽度，智能自适应高度且最大高度设为 90%，给予 4 列对比表格最舒展充裕的纵横向空间并支持平滑滚动
        com.battery.analysis.util.DialogWindowHelper.applyAdaptiveDialogWindowStyle(
            dialog = dialog,
            dialogView = dialogView,
            widthRatio = 0.95,
            maxHeightRatio = 0.90f
        )
    }
}

