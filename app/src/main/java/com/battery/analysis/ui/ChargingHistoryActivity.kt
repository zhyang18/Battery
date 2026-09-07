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
     * 弹出删除单条充电记录确认对话框。
     *
     * @param record 待删除的充电历史记录实体
     */
    private fun showDeleteRecordDialog(record: ChargingHistoryRecord) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete))
            .setMessage(getString(R.string.charging_history_clear_message))
            .setPositiveButton(getString(R.string.confirm)) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    dbHelper.deleteRecord(record.id)
                    withContext(Dispatchers.Main) {
                        loadHistoryList()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /**
     * 弹出清空全部充电历史记录确认对话框。
     */
    private fun showClearAllDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.charging_history_clear_title))
            .setMessage(getString(R.string.charging_history_clear_message))
            .setPositiveButton(getString(R.string.confirm)) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    dbHelper.clearAll()
                    withContext(Dispatchers.Main) {
                        loadHistoryList()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
}
