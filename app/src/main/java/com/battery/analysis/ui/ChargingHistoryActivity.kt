package com.battery.analysis.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
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
 * 遵循设计图样式渲染双行双列卡片，提供下拉刷新、全量清空与条目长按删除，支持点击任意条目跳转至充电详情 Activity。
 */
class ChargingHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChargingHistoryBinding
    private lateinit var adapter: ChargingHistoryAdapter
    private lateinit var dbHelper: ChargingHistoryDbHelper

    /**
     * 活动初始化生命周期回调，配置状态栏、初始化 RecyclerView 及交互监听。
     *
     * @param savedInstanceState 状态恢复 Bundle
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateSystemBarAppearance()

        binding = ActivityChargingHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dbHelper = ChargingHistoryDbHelper.getInstance(this)

        setupRecyclerView()
        setupListeners()
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
     * 初始化 RecyclerView 控件与数据适配器，绑定点击跳转详情与长按删除事件。
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

        binding.rvChargingHistory.layoutManager = LinearLayoutManager(this)
        binding.rvChargingHistory.adapter = adapter
    }

    /**
     * 设置导航返回、全量清空与下拉刷新交互监听器。
     */
    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnClearAll.setOnClickListener {
            showClearAllDialog()
        }

        binding.swipeRefresh.setColorSchemeResources(R.color.nav_item_selected)
        binding.swipeRefresh.setOnRefreshListener {
            loadHistoryList()
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
                if (list.isEmpty()) {
                    binding.rvChargingHistory.visibility = View.GONE
                    binding.layoutEmptyHistory.visibility = View.VISIBLE
                } else {
                    binding.rvChargingHistory.visibility = View.VISIBLE
                    binding.layoutEmptyHistory.visibility = View.GONE
                    adapter.submitList(list)
                }
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
        val tvTitle = dialogView.findViewById<android.widget.TextView>(R.id.tv_dialog_delete_title)
        val tvDesc = dialogView.findViewById<android.widget.TextView>(R.id.tv_dialog_delete_desc)
        val tvPreviewCat = dialogView.findViewById<android.widget.TextView>(R.id.tv_preview_cat)
        val tvPreviewTime = dialogView.findViewById<android.widget.TextView>(R.id.tv_preview_time)
        val tvPreviewSummary = dialogView.findViewById<android.widget.TextView>(R.id.tv_preview_summary)
        val btnCancel = dialogView.findViewById<android.widget.TextView>(R.id.btn_dialog_delete_cancel)
        val btnConfirm = dialogView.findViewById<android.widget.TextView>(R.id.btn_dialog_delete_confirm)

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
     * 弹出清空全部充电历史记录的高颜值确认对话框。
     */
    private fun showClearAllDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_custom_delete_confirm, null)
        val tvTitle = dialogView.findViewById<android.widget.TextView>(R.id.tv_dialog_delete_title)
        val tvDesc = dialogView.findViewById<android.widget.TextView>(R.id.tv_dialog_delete_desc)
        val layoutPreview = dialogView.findViewById<View>(R.id.layout_delete_item_preview)
        val btnCancel = dialogView.findViewById<android.widget.TextView>(R.id.btn_dialog_delete_cancel)
        val btnConfirm = dialogView.findViewById<android.widget.TextView>(R.id.btn_dialog_delete_confirm)

        tvTitle.text = getString(R.string.charging_history_clear_title)
        tvDesc.text = getString(R.string.charging_history_clear_message)
        layoutPreview.visibility = View.GONE

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnConfirm.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                dbHelper.clearAll()
                withContext(Dispatchers.Main) {
                    loadHistoryList()
                    dialog.dismiss()
                }
            }
        }

        dialog.show()
        applyDialogWindowStyle(dialog)
    }
}
