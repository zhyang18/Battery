package com.battery.analysis.ui

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.view.Gravity
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
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
    private var currentSortIndex: Int = 1

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
        setupEdgeToEdgeInsets()

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
     * 初始化应用耗电排行榜列表控件与适配器，并注册列表项点击弹出前后台深度能耗详情 BottomSheet 弹窗监听。
     */
    private fun setupAppRecyclerView() {
        appAdapter = AppPowerUsageAdapter()
        appAdapter.onListCountChangedListener = { count ->
            updateUsageListTitle(count)
        }
        appAdapter.onItemClickListener = { item ->
            val isShizuku = currentRecord?.isShizukuRealData ?: true
            val rangeStr = currentRecord?.let { record ->
                "${record.getFormattedTimeRange()} (${record.totalDurationText})"
            }
            AppUsageDetailBottomSheetDialog(this, item, isShizuku, rangeStr).show()
        }
        binding.recyclerAppUsage.layoutManager = LinearLayoutManager(this)
        binding.recyclerAppUsage.adapter = appAdapter
    }

    /**
     * 配置返回、删除快照与载入至主页的按钮点击监听，以及后台开关和排序菜单。
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

        // 场景后台统计开关：控制是否展示各应用后台数据及后台应用
        val statsPrefs = getSharedPreferences(PowerUsageFragment.PREFS_POWER_STATS, Context.MODE_PRIVATE)
        val isBgStatsEnabled = statsPrefs.getBoolean(PowerUsageFragment.PREF_KEY_ENABLE_BACKGROUND_STATS, false)
        binding.switchBackgroundStats.isChecked = isBgStatsEnabled
        appAdapter.setShowBackgroundStats(isBgStatsEnabled)

        binding.switchBackgroundStats.setOnCheckedChangeListener { _, isChecked ->
            statsPrefs.edit().putBoolean(PowerUsageFragment.PREF_KEY_ENABLE_BACKGROUND_STATS, isChecked).apply()
            appAdapter.setShowBackgroundStats(isChecked)
        }

        // 场景排序菜单按钮（漏斗）：弹出多选排序气泡弹窗
        binding.btnSceneSort.setOnClickListener {
            showSortChoiceDialog()
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
     * 弹出选择排序方式下拉气泡菜单。
     * 支持按使用时长、按功耗、按消耗电量或按名称进行排序切换，并联动刷新应用列表。
     */
    private fun showSortChoiceDialog() {
        val popupView = layoutInflater.inflate(R.layout.popup_power_sort_picker, null)
        val density = resources.displayMetrics.density
        val popupWidth = (170 * density).toInt()

        val popupWindow = PopupWindow(
            popupView,
            popupWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )

        popupWindow.isOutsideTouchable = true
        popupWindow.isFocusable = true
        popupWindow.animationStyle = R.style.Animation_PopupTopRight

        val sortViews = listOf(
            popupView.findViewById<TextView>(R.id.tv_sort_duration),
            popupView.findViewById<TextView>(R.id.tv_sort_power),
            popupView.findViewById<TextView>(R.id.tv_sort_energy),
            popupView.findViewById<TextView>(R.id.tv_sort_name)
        )

        val normalColor = ContextCompat.getColor(this, R.color.popup_item_text)
        val activeColor = Color.parseColor("#2196F3")

        sortViews.forEachIndexed { index, textView ->
            textView.setTextColor(if (index == currentSortIndex) activeColor else normalColor)
            textView.setOnClickListener {
                currentSortIndex = index
                appAdapter.setSortMode(index)
                popupWindow.dismiss()
            }
        }

        popupWindow.showAsDropDown(
            binding.btnSceneSort,
            0,
            (4 * density).toInt(),
            Gravity.END
        )
    }

    /**
     * 更新应用使用列表卡片标题，展示当前实际呈现的应用数量（如“使用列表(12)”）。
     *
     * @param count 当前列表展示的应用条目总数
     */
    private fun updateUsageListTitle(count: Int) {
        binding.tvAppListTitle.text = getString(R.string.power_usage_list_format, count)
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

        // 2. 卡片 2：三维核心功耗与续航指标（与耗电页顶部卡片一致，按亮屏 / 息屏 / 全局三行呈现）
        val onEnergy = record.screenOnEnergyWh
        val offEnergy = record.screenOffEnergyWh
        val totalEnergy = record.totalEnergyWh

        val onEnergyRatioStr = if (totalEnergy > 0f) {
            val ratio = (onEnergy / totalEnergy * 100f).coerceIn(0f, 100f)
            String.format(Locale.getDefault(), "%.1f%%", ratio)
        } else {
            "0.0%"
        }

        val offEnergyRatioStr = if (totalEnergy > 0f) {
            val ratio = (offEnergy / totalEnergy * 100f).coerceIn(0f, 100f)
            String.format(Locale.getDefault(), "%.1f%%", ratio)
        } else {
            "0.0%"
        }

        val onDurationMs = parseDurationTextToMs(record.screenOnDurationText)
        val offDurationMs = parseDurationTextToMs(record.screenOffDurationText)
        val totalDurationMs = parseDurationTextToMs(record.totalDurationText)

        val onDurationRatioStr = if (totalDurationMs > 0L) {
            val ratio = (onDurationMs.toDouble() / totalDurationMs.toDouble() * 100.0).coerceIn(0.0, 100.0)
            String.format(Locale.getDefault(), "%.1f%%", ratio)
        } else {
            "0.0%"
        }

        val offDurationRatioStr = if (totalDurationMs > 0L) {
            val ratio = (offDurationMs.toDouble() / totalDurationMs.toDouble() * 100.0).coerceIn(0.0, 100.0)
            String.format(Locale.getDefault(), "%.1f%%", ratio)
        } else {
            "0.0%"
        }

        val onDurationStr = formatCardDuration(onDurationMs, record.screenOnDurationText)
        val offDurationStr = formatCardDuration(offDurationMs, record.screenOffDurationText)
        val totalDurationStr = formatCardDuration(totalDurationMs, record.totalDurationText)

        val onPowerStr = if (record.screenOnPowerWatts >= 0.05f) {
            String.format(Locale.getDefault(), "%.2fW", record.screenOnPowerWatts)
        } else {
            "--"
        }
        val avgPowerStr = if (record.avgPowerWatts >= 0.05f) {
            String.format(Locale.getDefault(), "%.2fW", record.avgPowerWatts)
        } else {
            "--"
        }
        val offPowerStr = if (record.screenOffPowerWatts >= 0.05f) {
            String.format(Locale.getDefault(), "%.2fW", record.screenOffPowerWatts)
        } else {
            "--"
        }

        // 第一行：亮屏数据（前置亮色太阳图标，时长占比 / 能量占比 / 功耗 / 续航）
        binding.tvMetricScreenOnTime.text = formatValueWithSmallPercent(onDurationStr, onDurationRatioStr)
        binding.tvMetricScreenOnEnergy.text = formatValueWithSmallPercent(String.format(Locale.getDefault(), "%.3fWh", onEnergy), onEnergyRatioStr)
        binding.tvMetricScreenOnPower.text = onPowerStr
        binding.tvMetricScreenOnRemaining.text = record.remainingScreenOnText

        // 第二行：息屏数据（前置暗色太阳图标，时长占比 / 能量占比 / 功耗 / 续航）
        binding.tvMetricScreenOffTime.text = formatValueWithSmallPercent(offDurationStr, offDurationRatioStr)
        binding.tvMetricScreenOffEnergy.text = formatValueWithSmallPercent(String.format(Locale.getDefault(), "%.3fWh", offEnergy), offEnergyRatioStr)
        binding.tvMetricScreenOffPower.text = offPowerStr
        binding.tvMetricScreenOffRemaining.text = record.remainingScreenOffText

        // 第三行：全局数据（前置半亮半暗太阳图标，时长占比 / 能量占比 / 功耗 / 续航）
        binding.tvMetricGlobalTime.text = formatValueWithSmallPercent(totalDurationStr, "100%")
        binding.tvMetricGlobalEnergy.text = formatValueWithSmallPercent(String.format(Locale.getDefault(), "%.3fWh", totalEnergy), "100%")
        binding.tvMetricGlobalPower.text = avgPowerStr
        binding.tvMetricGlobalRemaining.text = record.remainingCompositeText

        // 指标卡片三行点击提示（亮屏 / 息屏 / 全局）
        binding.layoutMetricScreenOnRow.setOnClickListener {
            val joules = onEnergy * 3600f
            val timeText = if (onDurationRatioStr != "0.0%") "$onDurationStr($onDurationRatioStr)" else onDurationStr
            val energyText = "${String.format(Locale.getDefault(), "%.1fJ", joules)}(${String.format(Locale.getDefault(), "%.3fWh", onEnergy)})"
            val remainingText = record.remainingScreenOnText.ifBlank { "--" }
            Toast.makeText(this, "亮屏：时间 $timeText、平均功耗 $onPowerStr、能量 $energyText、续航时间 $remainingText", Toast.LENGTH_SHORT).show()
        }
        binding.layoutMetricScreenOffRow.setOnClickListener {
            val joules = offEnergy * 3600f
            val timeText = if (offDurationRatioStr != "0.0%") "$offDurationStr($offDurationRatioStr)" else offDurationStr
            val energyText = "${String.format(Locale.getDefault(), "%.1fJ", joules)}(${String.format(Locale.getDefault(), "%.3fWh", offEnergy)})"
            val remainingText = record.remainingScreenOffText.ifBlank { "--" }
            Toast.makeText(this, "息屏：时间 $timeText、平均功耗 $offPowerStr、能量 $energyText、续航时间 $remainingText", Toast.LENGTH_SHORT).show()
        }
        binding.layoutMetricGlobalRow.setOnClickListener {
            val joules = totalEnergy * 3600f
            val timeText = "$totalDurationStr(100%)"
            val energyText = "${String.format(Locale.getDefault(), "%.1fJ", joules)}(${String.format(Locale.getDefault(), "%.3fWh", totalEnergy)})"
            val remainingText = record.remainingCompositeText.ifBlank { "--" }
            Toast.makeText(this, "全局：时间 $timeText、平均功耗 $avgPowerStr、能量 $energyText、续航时间 $remainingText", Toast.LENGTH_SHORT).show()
        }

        // 3. 卡片 3 与 4：反序列化全量数据包加载功耗时间轴与应用排行榜
        lifecycleScope.launch(Dispatchers.IO) {
            val fullPackage = record.toFullPowerPackage(this@PowerHistoryDetailActivity)
            val powerMgr = PowerUsageManager.getInstance(this@PowerHistoryDetailActivity)
            val selectedMetrics = binding.metricSelectorView.getSelectedMetrics()
            val timelineState = powerMgr.buildTimelineState(fullPackage, isHistoryRecord = true).copy(selectedMetrics = selectedMetrics)
            withContext(Dispatchers.Main) {
                binding.batteryTimelineView.setState(timelineState)
                binding.layoutBackgroundStatsContainer.visibility = if (record.isShizukuRealData) android.view.View.VISIBLE else android.view.View.GONE
                appAdapter.submitList(fullPackage.appList)
                updateUsageListTitle(appAdapter.getDisplayItemCount())
            }
        }
    }

    /**
     * 将包含数值和括号百分比的文本（如 "13m44s(45.1%)" 或 "0.406Wh(45.1%)"）转换为富文本，
     * 将括号及内部百分比部分的字号缩小两号（由 12sp 缩至 8dp），突出主数值可读性。
     *
     * @param mainText 前置主要数值文本（如 "13m44s" 或 "0.406Wh"）
     * @param percentText 括号内的百分比文本（如 "45.1%" 或 "100%"）
     * @return 格式化后的富文本对象 [CharSequence]
     */
    private fun formatValueWithSmallPercent(mainText: String, percentText: String): CharSequence {
        val fullText = "$mainText($percentText)"
        val startIndex = mainText.length
        val spannable = SpannableString(fullText)
        spannable.setSpan(
            AbsoluteSizeSpan(8, true),
            startIndex,
            fullText.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return spannable
    }

    /**
     * 将毫秒时长按 "0m0s" 规范格式化为紧凑友好文本。
     * 当小于 1 小时时展示为分秒格式（如 "0m0s"、"13m44s"、"49m55s"）；
     * 当大于等于 1 小时且小于 1 天时展示为时分格式（如 "1h03m"）；
     * 当大于等于 1 天时展示为天时格式（如 "1d03h"）。
     *
     * @param ms 物理持续时长毫秒值
     * @param fallbackText 当毫秒值为 0 或无效时的备用文本
     * @return 格式化后的紧凑时长字符串（如 "0m0s"、"13m44s"）
     */
    private fun formatCardDuration(ms: Long, fallbackText: String): String {
        if (ms <= 0L) {
            return if (fallbackText.isNotBlank()) fallbackText else "0m0s"
        }
        val totalSec = ms / 1000L
        val days = totalSec / 86400L
        val hours = (totalSec % 86400L) / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val seconds = totalSec % 60L
        return when {
            days > 0L -> String.format(Locale.getDefault(), "%dd%02dh", days, hours)
            hours > 0L -> String.format(Locale.getDefault(), "%dh%02dm", hours, minutes)
            else -> "${minutes}m${seconds}s"
        }
    }

    /**
     * 从历史快照等字符串中解析时长文本为物理毫秒值。
     * 支持形如 "1d03h"、"1h03m"、"13m44s" 或 "13:44" 等格式。
     *
     * @param text 格式化时长字符串
     * @return 解析出的物理毫秒数，无法解析时返回 0L
     */
    private fun parseDurationTextToMs(text: String): Long {
        if (text.isBlank() || text == "--") return 0L
        var totalMs = 0L
        val dayMatch = Regex("(\\d+)d").find(text)
        val hourMatch = Regex("(\\d+)h").find(text)
        val minMatch = Regex("(\\d+)m").find(text)
        val secMatch = Regex("(\\d+)s").find(text)
        val colonMatch = Regex("(\\d+):(\\d+)(?::(\\d+))?").find(text)

        if (colonMatch != null) {
            val parts = colonMatch.destructured
            if (parts.component3().isNotEmpty()) {
                val h = parts.component1().toLongOrNull() ?: 0L
                val m = parts.component2().toLongOrNull() ?: 0L
                val s = parts.component3().toLongOrNull() ?: 0L
                return (h * 3600L + m * 60L + s) * 1000L
            } else {
                val m = parts.component1().toLongOrNull() ?: 0L
                val s = parts.component2().toLongOrNull() ?: 0L
                return (m * 60L + s) * 1000L
            }
        }

        dayMatch?.groupValues?.get(1)?.toLongOrNull()?.let { totalMs += it * 86400000L }
        hourMatch?.groupValues?.get(1)?.toLongOrNull()?.let { totalMs += it * 3600000L }
        minMatch?.groupValues?.get(1)?.toLongOrNull()?.let { totalMs += it * 60000L }
        secMatch?.groupValues?.get(1)?.toLongOrNull()?.let { totalMs += it * 1000L }
        return totalMs
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
