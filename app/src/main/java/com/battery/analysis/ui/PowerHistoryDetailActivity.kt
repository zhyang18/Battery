package com.battery.analysis.ui

import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.battery.analysis.R
import com.battery.analysis.databinding.ActivityPowerHistoryDetailBinding
import com.battery.analysis.db.PowerUsageDbHelper
import com.battery.analysis.model.PowerUsageRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 耗电历史快照详情展示 Activity。
 * 完整呈现单次拔电放电会话的四维数据卡片：起止时段与电池状态、三维核心功耗与理论续航看板、放电折线轨迹图表以及各应用前台耗电排行榜列表。
 * 提供单条快照删除以及一键载入至主页查看功能。
 */
class PowerHistoryDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPowerHistoryDetailBinding
    private var recordId: Long = -1L
    private var currentRecord: PowerUsageRecord? = null
    private lateinit var appAdapter: AppPowerUsageAdapter

    /**
     * 活动初始化生命周期回调，配置状态栏、获取传入快照 ID 并触发全量数据加载。
     *
     * @param savedInstanceState 状态恢复 Bundle
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateSystemBarAppearance()

        binding = ActivityPowerHistoryDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        recordId = intent.getLongExtra(EXTRA_RECORD_ID, -1L)

        setupAppRecyclerView()
        setupListeners()
        loadRecordData()
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
     * 初始化应用耗电排行榜列表控件与适配器。
     */
    private fun setupAppRecyclerView() {
        appAdapter = AppPowerUsageAdapter()
        binding.recyclerAppUsage.layoutManager = LinearLayoutManager(this)
        binding.recyclerAppUsage.adapter = appAdapter
    }

    /**
     * 配置返回、删除快照与载入至主页的按钮点击监听。
     */
    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnDelete.setOnClickListener {
            showDeleteConfirmDialog()
        }

        binding.btnLoadToMain.setOnClickListener {
            currentRecord?.let { record ->
                PowerUsageFragment.pendingSnapshotRecord = record
                setResult(RESULT_LOAD_TO_MAIN)
                finish()
            }
        }
    }

    /**
     * 异步从本地数据库检索指定 ID 的耗电记录并渲染呈现。
     */
    private fun loadRecordData() {
        if (recordId <= 0L) {
            finish()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val db = PowerUsageDbHelper.getInstance(this@PowerHistoryDetailActivity)
            val list = db.getAllRecords()
            val record = list.firstOrNull { it.id == recordId }

            withContext(Dispatchers.Main) {
                if (record != null) {
                    currentRecord = record
                    renderRecordDetails(record)
                } else {
                    finish()
                }
            }
        }
    }

    /**
     * 将解析后的完整耗电数据包绑定并渲染至卡片、图表与列表中。
     *
     * @param record 耗电历史快照数据实体
     */
    private fun renderRecordDetails(record: PowerUsageRecord) {
        // 1. 卡片 1：时段、徽章与电池状态
        binding.tvDetailTimeRange.text = record.getFormattedTimeRange()

        if (record.isShizukuRealData) {
            binding.tvModeBadge.text = "Shizuku"
            binding.tvModeBadge.setTextColor(Color.parseColor("#2196F3"))
            binding.tvModeBadge.setBackgroundResource(R.drawable.bg_history_badge)
        } else {
            binding.tvModeBadge.text = getString(R.string.power_mode_normal)
            binding.tvModeBadge.setTextColor(Color.parseColor("#9CA3AF"))
            binding.tvModeBadge.setBackgroundResource(R.drawable.bg_dialog_btn_cancel)
        }

        binding.tvDetailLevel.text = "${record.levelPercent}%"
        binding.tvDetailTemp.text = String.format(Locale.getDefault(), "%.1f ℃", record.temperature)
        binding.tvDetailVoltage.text = String.format(Locale.getDefault(), "%.2f V", record.voltageVolts)
        binding.tvDetailEnergy.text = String.format(Locale.getDefault(), "%.1f Wh", record.energyWh)

        // 2. 卡片 2：三维指标
        val onPwrStr = if (record.screenOnPowerWatts > 0.001f) String.format(Locale.getDefault(), "%.2fW", record.screenOnPowerWatts) else "--"
        val avgPwrStr = String.format(Locale.getDefault(), "%.2fW", record.avgPowerWatts)
        val offPwrStr = if (record.screenOffPowerWatts > 0.001f) String.format(Locale.getDefault(), "%.2fW", record.screenOffPowerWatts) else "--"

        binding.tvMetricPowerScreenOn.text = String.format(Locale.getDefault(), getString(R.string.power_format_screen_on), onPwrStr)
        binding.tvMetricPowerAvg.text = String.format(Locale.getDefault(), getString(R.string.power_format_avg), avgPwrStr)
        binding.tvMetricPowerScreenOff.text = String.format(Locale.getDefault(), getString(R.string.power_format_screen_off), offPwrStr)

        binding.tvMetricTimeScreenOn.text = String.format(Locale.getDefault(), getString(R.string.power_format_screen_on), record.screenOnDurationText)
        binding.tvMetricTimeTotal.text = String.format(Locale.getDefault(), getString(R.string.power_format_total), record.totalDurationText)
        binding.tvMetricTimeScreenOff.text = String.format(Locale.getDefault(), getString(R.string.power_format_screen_off), record.screenOffDurationText)

        binding.tvMetricRemScreenOn.text = String.format(Locale.getDefault(), getString(R.string.power_format_screen_on), record.remainingScreenOnText)
        binding.tvMetricRemComposite.text = String.format(Locale.getDefault(), getString(R.string.power_format_composite), record.remainingCompositeText)
        binding.tvMetricRemScreenOff.text = String.format(Locale.getDefault(), getString(R.string.power_format_screen_off), record.remainingScreenOffText)

        // 3. 卡片 3 与 4：反序列化全量数据包加载走势图与应用排行榜
        lifecycleScope.launch(Dispatchers.IO) {
            val fullPackage = record.toFullPowerPackage(this@PowerHistoryDetailActivity)
            withContext(Dispatchers.Main) {
                binding.powerChartView.setData(fullPackage.trendPoints)
                binding.tvAppListTitle.text = getString(R.string.power_history_app_count_format, fullPackage.appList.size)
                appAdapter.submitList(fullPackage.appList)
            }
        }
    }

    /**
     * 弹出删除单条耗电历史记录确认对话框。
     */
    private fun showDeleteConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete))
            .setMessage(getString(R.string.power_history_clear_message))
            .setPositiveButton(getString(R.string.confirm)) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val db = PowerUsageDbHelper.getInstance(this@PowerHistoryDetailActivity)
                    db.deleteRecord(recordId)
                    withContext(Dispatchers.Main) {
                        setResult(RESULT_OK)
                        finish()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    companion object {
        const val EXTRA_RECORD_ID = "extra_record_id"
        const val RESULT_LOAD_TO_MAIN = 1002
    }
}
