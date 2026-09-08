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
import com.battery.analysis.manager.PowerUsageManager
import com.battery.analysis.model.PowerUsageRecord
import com.battery.analysis.timeline.presentation.AppEnergyDetailBottomSheetDialog
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

        // 功耗时间轴指标多选/反选监听
        binding.metricSelectorView.setOnMetricsChangedListener { selectedMetrics ->
            binding.batteryTimelineView.setSelectedMetrics(selectedMetrics)
        }

        // 功耗时间轴 App 图标点击监听
        binding.batteryTimelineView.setOnAppEventListener { event ->
            AppEnergyDetailBottomSheetDialog(this@PowerHistoryDetailActivity, event).show()
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

        // 2. 卡片 2：三维指标（低于 0.05W 统一规范展示为 "--" 杜绝显示 0.00W 误导用户）
        val onPwrStr = if (record.screenOnPowerWatts >= 0.05f) String.format(Locale.getDefault(), "%.2fW", record.screenOnPowerWatts) else "--"
        val avgPwrStr = if (record.avgPowerWatts >= 0.05f) String.format(Locale.getDefault(), "%.2fW", record.avgPowerWatts) else "--"
        val offPwrStr = if (record.screenOffPowerWatts >= 0.05f) String.format(Locale.getDefault(), "%.2fW", record.screenOffPowerWatts) else "--"

        binding.tvMetricPowerScreenOn.text = String.format(Locale.getDefault(), getString(R.string.power_format_screen_on), onPwrStr)
        binding.tvMetricPowerAvg.text = String.format(Locale.getDefault(), getString(R.string.power_format_avg), avgPwrStr)
        binding.tvMetricPowerScreenOff.text = String.format(Locale.getDefault(), getString(R.string.power_format_screen_off), offPwrStr)

        binding.tvMetricTimeScreenOn.text = String.format(Locale.getDefault(), getString(R.string.power_format_screen_on), record.screenOnDurationText)
        binding.tvMetricTimeTotal.text = String.format(Locale.getDefault(), getString(R.string.power_format_total), record.totalDurationText)
        binding.tvMetricTimeScreenOff.text = String.format(Locale.getDefault(), getString(R.string.power_format_screen_off), record.screenOffDurationText)

        binding.tvMetricRemScreenOn.text = String.format(Locale.getDefault(), getString(R.string.power_format_screen_on), record.remainingScreenOnText)
        binding.tvMetricRemComposite.text = String.format(Locale.getDefault(), getString(R.string.power_format_composite), record.remainingCompositeText)
        binding.tvMetricRemScreenOff.text = String.format(Locale.getDefault(), getString(R.string.power_format_screen_off), record.remainingScreenOffText)

        // 3. 卡片 3 与 4：反序列化全量数据包加载功耗时间轴与应用排行榜
        lifecycleScope.launch(Dispatchers.IO) {
            val fullPackage = record.toFullPowerPackage(this@PowerHistoryDetailActivity)
            val powerMgr = PowerUsageManager.getInstance(this@PowerHistoryDetailActivity)
            val selectedMetrics = binding.metricSelectorView.getSelectedMetrics()
            val timelineState = powerMgr.buildTimelineState(fullPackage).copy(selectedMetrics = selectedMetrics)
            withContext(Dispatchers.Main) {
                binding.batteryTimelineView.setState(timelineState)
                binding.tvAppListTitle.text = getString(R.string.power_history_app_count_format, fullPackage.appList.size)
                appAdapter.submitList(fullPackage.appList)
            }
        }
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
     * 弹出删除单条耗电历史记录的高颜值确认对话框。
     */
    private fun showDeleteConfirmDialog() {
        val record = currentRecord ?: return
        val dialogView = layoutInflater.inflate(R.layout.dialog_custom_delete_confirm, null)
        val tvTitle = dialogView.findViewById<android.widget.TextView>(R.id.tv_dialog_delete_title)
        val tvDesc = dialogView.findViewById<android.widget.TextView>(R.id.tv_dialog_delete_desc)
        val tvPreviewCat = dialogView.findViewById<android.widget.TextView>(R.id.tv_preview_cat)
        val tvPreviewTime = dialogView.findViewById<android.widget.TextView>(R.id.tv_preview_time)
        val tvPreviewSummary = dialogView.findViewById<android.widget.TextView>(R.id.tv_preview_summary)
        val btnCancel = dialogView.findViewById<android.widget.TextView>(R.id.btn_dialog_delete_cancel)
        val btnConfirm = dialogView.findViewById<android.widget.TextView>(R.id.btn_dialog_delete_confirm)

        tvTitle.text = "确认删除此耗电快照？"
        tvDesc.text = "删除后该条放电快照记录将从本地永久移除，无法找回。"
        tvPreviewCat.text = if (record.isShizukuRealData) "Shizuku" else "系统模式"
        tvPreviewTime.text = record.recordTime
        tvPreviewSummary.text = "🔋 终止电量 ${record.levelPercent}%   •   ⏱️ 持续 ${record.totalDurationText}"

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnConfirm.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val db = PowerUsageDbHelper.getInstance(this@PowerHistoryDetailActivity)
                db.deleteRecord(recordId)
                withContext(Dispatchers.Main) {
                    setResult(RESULT_OK)
                    finish()
                }
            }
        }

        dialog.show()
        applyDialogWindowStyle(dialog)
    }

    companion object {
        const val EXTRA_RECORD_ID = "extra_record_id"
        const val RESULT_LOAD_TO_MAIN = 1002
    }
}
