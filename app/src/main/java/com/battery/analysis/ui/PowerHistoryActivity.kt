package com.battery.analysis.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.battery.analysis.R
import com.battery.analysis.databinding.ActivityPowerHistoryBinding
import com.battery.analysis.db.PowerUsageDbHelper
import com.battery.analysis.model.PowerUsageRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 耗电历史记录列表展示 Activity。
 * 遵循设计图样式渲染双行双列卡片，提供下拉刷新、全量清空与长按单条删除，支持点击任意条目跳转至耗电快照详情 Activity。
 */
class PowerHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPowerHistoryBinding
    private lateinit var adapter: PowerHistoryAdapter
    private lateinit var dbHelper: PowerUsageDbHelper

    private val detailLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == PowerHistoryDetailActivity.RESULT_LOAD_TO_MAIN) {
            setResult(PowerHistoryDetailActivity.RESULT_LOAD_TO_MAIN)
            finish()
        }
    }

    /**
     * 活动初始化生命周期回调，配置沉浸式状态栏、初始化 RecyclerView 与事件监听器。
     *
     * @param savedInstanceState 状态恢复 Bundle
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateSystemBarAppearance()

        binding = ActivityPowerHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dbHelper = PowerUsageDbHelper.getInstance(this)

        setupRecyclerView()
        setupListeners()
    }

    /**
     * 界面恢复生命周期回调，自动触发最新历史列表重载。
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
     * 初始化 RecyclerView 列表控件与数据适配器。
     */
    private fun setupRecyclerView() {
        adapter = PowerHistoryAdapter(
            onItemClick = { record ->
                openPowerDetail(record)
            },
            onDeleteClick = { record ->
                showDeleteRecordDialog(record)
            }
        )

        binding.rvPowerHistory.layoutManager = LinearLayoutManager(this)
        binding.rvPowerHistory.adapter = adapter
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
     * 异步从本地数据库检索所有历史耗电快照记录并刷新列表与空状态占位。
     */
    private fun loadHistoryList() {
        binding.swipeRefresh.isRefreshing = true
        lifecycleScope.launch(Dispatchers.IO) {
            val list = dbHelper.getAllRecords()
            withContext(Dispatchers.Main) {
                binding.swipeRefresh.isRefreshing = false
                if (list.isEmpty()) {
                    binding.rvPowerHistory.visibility = View.GONE
                    binding.layoutEmptyHistory.visibility = View.VISIBLE
                } else {
                    binding.rvPowerHistory.visibility = View.VISIBLE
                    binding.layoutEmptyHistory.visibility = View.GONE
                    adapter.submitList(list)
                }
            }
        }
    }

    /**
     * 打开单次耗电快照的详情展示 Activity。
     *
     * @param record 待查看详情的耗电快照记录实体
     */
    private fun openPowerDetail(record: PowerUsageRecord) {
        val intent = Intent(this, PowerHistoryDetailActivity::class.java).apply {
            putExtra(PowerHistoryDetailActivity.EXTRA_RECORD_ID, record.id)
        }
        detailLauncher.launch(intent)
    }

    /**
     * 弹出删除单条耗电记录确认对话框。
     *
     * @param record 待删除的耗电快照记录实体
     */
    private fun showDeleteRecordDialog(record: PowerUsageRecord) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete))
            .setMessage(getString(R.string.power_history_clear_message))
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
     * 弹出清空全部耗电历史快照确认对话框。
     */
    private fun showClearAllDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.power_history_clear_title))
            .setMessage(getString(R.string.power_history_clear_message))
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
