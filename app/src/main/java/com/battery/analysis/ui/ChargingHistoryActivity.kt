package com.battery.analysis.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.battery.analysis.R
import com.battery.analysis.databinding.ActivityChargingHistoryBinding
import com.battery.analysis.db.ChargingHistoryDbHelper
import com.battery.analysis.model.ChargingHistoryRecord
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
    private lateinit var dbHelper: ChargingHistoryDbHelper

    /**
     * 当前从数据库全量加载的历史记录内存快照列表。
     */
    private var allRecordList: List<ChargingHistoryRecord> = emptyList()

    /**
     * 当前选中的电量净增量筛选阈值（例如 20, 50, 80），若为 null 则表示展示全部。
     */
    private var selectedGainFilter: Int? = null

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

        setupRecyclerView()
        setupListeners()
        setupFilterChips()
        setupBackPressedHandler()
    }

    /**
     * 界面恢复生命周期回调，自动触发最新历史列表重载（保障从详情页返回时数据一致）。
     */
    override fun onResume() {
        super.onResume()
        loadHistoryList()
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
     * 初始化 RecyclerView 控件与数据适配器，绑定点击跳转详情、长按删除及选中状态变化回调。
     */
    private fun setupRecyclerView() {
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

        binding.rvChargingHistory.layoutManager = LinearLayoutManager(this)
        binding.rvChargingHistory.adapter = adapter
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
            loadHistoryList()
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
     * 初始化页面下方条件查询胶囊（≥20%、≥50%、≥80%）的点击监听。
     */
    private fun setupFilterChips() {
        val chips = listOf(
            Pair(binding.btnFilterGain20, 20),
            Pair(binding.btnFilterGain50, 50),
            Pair(binding.btnFilterGain80, 80)
        )

        for ((view, threshold) in chips) {
            view.setOnClickListener {
                if (selectedGainFilter == threshold) {
                    selectedGainFilter = null
                } else {
                    selectedGainFilter = threshold
                }
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
            Pair(binding.btnFilterGain20, 20),
            Pair(binding.btnFilterGain50, 50),
            Pair(binding.btnFilterGain80, 80)
        )

        val highlightColor = Color.parseColor("#8AB4F8")
        val normalColor = Color.parseColor("#9CA3AF")

        for ((view, threshold) in chips) {
            if (selectedGainFilter == threshold) {
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
     * 根据当前的筛选条件对全量数据进行过滤，并刷新 RecyclerView 与空状态展示。
     */
    private fun applyFilterAndSubmit() {
        val filter = selectedGainFilter
        val filteredList = if (filter == null) {
            allRecordList
        } else {
            allRecordList.filter { it.levelGain >= filter }
        }

        if (filteredList.isEmpty()) {
            binding.rvChargingHistory.visibility = View.GONE
            binding.layoutEmptyHistory.visibility = View.VISIBLE
            if (allRecordList.isEmpty()) {
                binding.tvEmptyTitle.text = getString(R.string.charging_history_empty_title)
                binding.tvEmptyDesc.text = getString(R.string.charging_history_empty_desc)
            } else {
                binding.tvEmptyTitle.text = "未找到符合条件的充电记录"
                binding.tvEmptyDesc.text = "暂无电量增量 ≥$filter% 的充电记录"
            }
        } else {
            binding.rvChargingHistory.visibility = View.VISIBLE
            binding.layoutEmptyHistory.visibility = View.GONE
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
     * 退出多选删除模式，清空选中集并隐藏全选框。
     */
    private fun exitSelectionMode() {
        adapter.setSelectionMode(false)
        binding.layoutSelectAll.visibility = View.GONE
        binding.cbSelectAll.isChecked = false
        binding.tvTitle.text = getString(R.string.charging_history_title)
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
            binding.tvTitle.text = getString(R.string.charging_history_title)
        }
    }

    /**
     * 异步从本地数据库检索所有历史充电记录并刷新列表与空状态占位。
     */
    private fun loadHistoryList() {
        binding.swipeRefresh.isRefreshing = true
        lifecycleScope.launch(Dispatchers.IO) {
            val list = dbHelper.getAllRecords()
            withContext(Dispatchers.Main) {
                binding.swipeRefresh.isRefreshing = false
                allRecordList = list
                applyFilterAndSubmit()
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
        startActivity(intent)
    }

    /**
     * 为自定义对话框应用居中、半透明背景及适屏宽度的窗口样式。
     *
     * @param dialog 待配置样式的 [AlertDialog] 实例
     */
    private fun applyDialogWindowStyle(dialog: AlertDialog) {
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            val width = (resources.displayMetrics.widthPixels * 0.92).toInt()
            window.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
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
        val sign = if (record.levelGain >= 0) "+${record.levelGain}%" else "${record.levelGain}%"
        tvPreviewSummary.text = "⚡ $sign (${record.startLevel}% → ${record.endLevel}%)   •   ⏱️ ${record.getFormattedDuration()}"

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
}

