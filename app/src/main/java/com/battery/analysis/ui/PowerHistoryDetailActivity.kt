package com.battery.analysis.ui

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
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
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import com.battery.analysis.R
import com.battery.analysis.databinding.ActivityPowerHistoryDetailBinding
import com.battery.analysis.databinding.ItemHistoryDetailHeaderBinding
import com.battery.analysis.db.PowerUsageDbHelper
import com.battery.analysis.manager.PowerOverviewStats
import com.battery.analysis.manager.PowerUsageManager
import com.battery.analysis.model.PowerDisplayUnit
import com.battery.analysis.model.PowerUsageRecord
import com.battery.analysis.model.SevenDaysDischargeStats
import com.battery.analysis.timeline.presentation.AppEnergyDetailBottomSheetDialog
import com.battery.analysis.timeline.presentation.BatteryTimelineState
import com.battery.analysis.util.BatteryEnergyCalculator
import com.battery.analysis.util.BatteryTotalEnergyInfo
import com.battery.analysis.util.BubbleTooltipHelper
import com.battery.analysis.util.SevenDaysDischargeCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.battery.analysis.timeline.util.SubIntervalOverviewStats
import com.battery.analysis.timeline.util.TimelinePrefixData
import com.battery.analysis.timeline.util.TimelinePrefixSumCalculator
import java.util.Date
import java.util.Locale

/**
 * 耗电历史快照详情展示 Activity。
 * 完整呈现单次拔电放电会话的四维数据卡片：起止时段与电池状态、三维核心功耗与理论续航看板、放电折线轨迹图表以及各应用前台耗电排行榜列表。
 * 采用全局单一主 RecyclerView 与 ConcatAdapter 扁平化架构，彻底实现应用 ViewHolder 原生虚拟化与视图复用。
 * 提供单条快照删除以及一键载入至主页查看功能。
 */
class PowerHistoryDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPowerHistoryDetailBinding
    private var headerBinding: ItemHistoryDetailHeaderBinding? = null
    private lateinit var headerAdapter: HistoryDetailHeaderAdapter
    private lateinit var appAdapter: AppPowerUsageAdapter

    private var recordId: Long = -1L
    private var currentRecord: PowerUsageRecord? = null
    private var cachedTimelineState: BatteryTimelineState? = null
    private var cachedDisplayAppCount: Int = 0
    private var currentSortIndex: Int = 1
    private var currentDisplayUnit: PowerDisplayUnit = PowerDisplayUnit.WH

    /**
     * 当前快照对应的核心功耗指标概览数据实体缓存。
     */
    private var cachedOverviewStats: PowerOverviewStats? = null

    /**
     * 快照截止时刻前累积 7 天统计放电速度与充满电理论时长数据缓存。
     */
    private var cachedSevenDaysStats: SevenDaysDischargeStats? = null

    /**
     * 设备电池满电物理总能量缓存（单位：Wh）。
     */
    private var cachedTotalCapacityWh: Float? = null

    /**
     * 设备电池物理总能量及获取方式详情缓存。
     */
    private var cachedTotalEnergyInfo: BatteryTotalEnergyInfo? = null

    /**
     * 当前时间轴前缀和预计算索引缓存，支撑 120Hz 游标探查二分查询。
     */
    private var currentPrefixData: com.battery.analysis.timeline.util.TimelinePrefixData? = null

    /**
     * 游标滑动时刻格式化器（HH:mm:ss），采用 ThreadLocal 实现每线程单例复用，消除高频滑动 GC 压力。
     */
    private val threadLocalCursorTimeFormatter = ThreadLocal.withInitial {
        java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    }


    /**
     * 活跃对话框跟踪列表，防止退入后台或界面销毁时遗留悬挂 Window 导致内存泄漏。
     */
    private val activeDialogs = java.util.concurrent.CopyOnWriteArrayList<android.app.Dialog>()

    /**
     * 活跃气泡弹窗跟踪列表，防止退入后台或界面销毁时遗留悬挂 Window 导致内存泄漏。
     */
    private val activePopups = java.util.concurrent.CopyOnWriteArrayList<PopupWindow>()

    /**
     * 统一跟踪并显示对话框，在生命周期结束或退出前台时集中安全关闭以根除 Window 泄漏。
     *
     * @param dialog 待跟踪并显示的 [android.app.Dialog] 对话框实例
     * @return 传入的对话框实例
     */
    private fun <T : android.app.Dialog> showAndTrackDialog(dialog: T): T {
        activeDialogs.add(dialog)
        dialog.setOnDismissListener {
            activeDialogs.remove(dialog)
        }
        dialog.show()
        return dialog
    }

    /**
     * 统一跟踪气泡弹窗，在生命周期结束或退出前台时集中安全关闭以根除 Window 泄漏。
     *
     * @param popup 待跟踪的 [PopupWindow] 气泡弹窗实例
     * @return 传入的气泡弹窗实例
     */
    private fun trackPopup(popup: PopupWindow): PopupWindow {
        activePopups.add(popup)
        popup.setOnDismissListener {
            activePopups.remove(popup)
        }
        return popup
    }

    /**
     * 强制安全清理所有正在展示的 Dialog 与 PopupWindow，彻底释放 ViewRootImpl 与系统 GraphicBuffer 内存。
     */
    private fun dismissAllActiveWindows() {
        activePopups.forEach { popup ->
            try {
                if (popup.isShowing) {
                    popup.dismiss()
                }
            } catch (_: Exception) {}
        }
        activePopups.clear()

        activeDialogs.forEach { dialog ->
            try {
                if (dialog.isShowing) {
                    dialog.dismiss()
                }
            } catch (_: Exception) {}
        }
        activeDialogs.clear()
    }

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
     * 界面恢复可见时的生命周期回调。
     * 同步读取耗电主页设定的最新电量显示单位（Wh / mAh），若单位发生变更则自动重绘头部各指标看板与应用列表。
     */
    override fun onResume() {
        super.onResume()
        syncDisplayUnitFromPreferences()
    }

    /**
     * 从持久化偏好设置中同步耗电主页设定的电量显示单位，并在发生变更时刷新界面各指标。
     * 严格遵从耗电主页的电量显示单位设置，确保全应用展示完全一致。
     */
    private fun syncDisplayUnitFromPreferences() {
        val prefs = getSharedPreferences(PowerDisplayUnit.PREFS_NAME, Context.MODE_PRIVATE)
        val latestUnit = PowerDisplayUnit.fromId(prefs.getInt(PowerDisplayUnit.PREF_KEY_POWER_DISPLAY_UNIT, PowerDisplayUnit.WH.id))
        val unitChanged = (latestUnit != currentDisplayUnit)
        currentDisplayUnit = latestUnit
        if (::appAdapter.isInitialized) {
            appAdapter.updateDisplayUnit(latestUnit)
        }
        if (unitChanged) {
            headerBinding?.batteryTimelineView?.setDisplayUnit(latestUnit)
            val record = currentRecord
            val hBinding = headerBinding
            if (record != null && hBinding != null) {
                renderHeaderDetails(record, hBinding)
                bindMetricsCards(hBinding)
            }
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
     * 初始化主列表控件与 ConcatAdapter，将头部各卡片与纯应用列表串联，彻底恢复 ViewHolder 虚拟化与视图复用。
     */
    private fun setupAppRecyclerView() {
        headerAdapter = HistoryDetailHeaderAdapter(
            onBindingReady = { hBinding ->
                headerBinding = hBinding
                setupHeaderListeners(hBinding)
                bindHeaderAll(hBinding)
            },
            onBind = { hBinding ->
                headerBinding = hBinding
                bindHeaderAll(hBinding)
            }
        )

        val prefs = getSharedPreferences(PowerDisplayUnit.PREFS_NAME, Context.MODE_PRIVATE)
        currentDisplayUnit = PowerDisplayUnit.fromId(prefs.getInt(PowerDisplayUnit.PREF_KEY_POWER_DISPLAY_UNIT, PowerDisplayUnit.WH.id))
        appAdapter = AppPowerUsageAdapter().apply {
            isPureAppListMode = true
            updateDisplayUnit(this@PowerHistoryDetailActivity.currentDisplayUnit)
        }
        appAdapter.onListCountChangedListener = { count ->
            updateUsageListTitle(count)
        }
        appAdapter.onItemClickListener = { item ->
            val isShizuku = currentRecord?.isShizukuRealData ?: true
            val rangeStr = currentRecord?.let { record ->
                "${record.getFormattedTimeRange()} (${record.totalDurationText})"
            }
            val dialog = AppUsageDetailBottomSheetDialog(this, item, isShizuku, rangeStr)
            showAndTrackDialog(dialog)
        }

        val concatAdapter = ConcatAdapter(headerAdapter, appAdapter)
        binding.recyclerHistoryDetail.layoutManager = LinearLayoutManager(this)
        binding.recyclerHistoryDetail.adapter = concatAdapter
        binding.recyclerHistoryDetail.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: androidx.recyclerview.widget.RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                val isScrolling = (newState != androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE)
                headerBinding?.batteryTimelineView?.setScrolling(isScrolling)
            }
        })
    }

    /**
     * 配置顶部导航栏（返回、删除快照与载入至主页）按钮点击监听。
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
     * 配置头部卡片内部的交互监听（后台统计开关、排序漏斗、图表指标与事件）。
     *
     * @param hBinding 头部视图绑定对象
     */
    private fun setupHeaderListeners(hBinding: ItemHistoryDetailHeaderBinding) {
        val statsPrefs = getSharedPreferences(PowerUsageFragment.PREFS_POWER_STATS, Context.MODE_PRIVATE)
        val isBgStatsEnabled = statsPrefs.getBoolean(PowerUsageFragment.PREF_KEY_ENABLE_BACKGROUND_STATS, false)

        hBinding.switchBackgroundStats.setOnCheckedChangeListener(null)
        hBinding.switchBackgroundStats.isChecked = isBgStatsEnabled
        appAdapter.setShowBackgroundStats(isBgStatsEnabled)

        hBinding.switchBackgroundStats.setOnCheckedChangeListener { buttonView, isChecked ->
            if (buttonView.isPressed) {
                statsPrefs.edit().putBoolean(PowerUsageFragment.PREF_KEY_ENABLE_BACKGROUND_STATS, isChecked).apply()
                appAdapter.setShowBackgroundStats(isChecked)
            }
        }

        hBinding.btnSceneSort.setOnClickListener { v ->
            showSortChoiceDialog(v)
        }

        hBinding.metricSelectorView.setOnMetricsChangedListener { selectedMetrics ->
            cachedTimelineState = cachedTimelineState?.copy(selectedMetrics = selectedMetrics)
            hBinding.batteryTimelineView.setSelectedMetrics(selectedMetrics)
        }

        hBinding.batteryTimelineView.setOnAppEventListener { event ->
            val dialog = AppEnergyDetailBottomSheetDialog(this@PowerHistoryDetailActivity, event)
            showAndTrackDialog(dialog)
        }

        hBinding.batteryTimelineView.setOnEnergyClickListener { view, touchX, touchY ->
            showEnergyTooltip(view, touchX, touchY)
        }

        hBinding.batteryTimelineView.setOnCursorInspectListener(object : com.battery.analysis.timeline.presentation.BatteryTimelineView.OnCursorInspectListener {
            override fun onCursorMove(
                timestamp: Long,
                sample: com.battery.analysis.timeline.domain.BatterySample?,
                activeApp: com.battery.analysis.timeline.domain.AppTimelineEvent?
            ) {
                val prefix = currentPrefixData ?: return
                val subStats = com.battery.analysis.timeline.util.TimelinePrefixSumCalculator.querySubIntervalStats(prefix, timestamp)
                this@PowerHistoryDetailActivity.updateCursorPowerMetrics(hBinding, subStats)
                PowerDischargeMetricsViewBinder.updateCursorInspectionStats(
                    speedBinding = hBinding.layoutDischargeSpeed,
                    sleepBinding = hBinding.layoutSleepAwake,
                    subStats = subStats,
                    unit = currentDisplayUnit
                )
            }

            override fun onCursorDismiss() {
                val record = currentRecord ?: return
                renderHeaderDetails(record, hBinding)
                bindMetricsCards(hBinding)
                binding.tvTitle.text = getString(R.string.power_detail_title)
                binding.tvCursorTime.text = ""
                binding.tvCursorTime.visibility = View.GONE
            }
        })
    }

    /**
     * 弹出快照趋势图上方能量指标的详细信息气泡弹窗提示。
     * 忠实呈现当前快照剩余能量、电池总能量、拔电时初始能量以及该放电周期已消耗能量，
     * 缺失时如实显示未知占位符，严禁伪造假数据。
     * 强制在能量指标所在视图上方展示，且不启用自动关闭定时器，仅在点击外部或气泡自身时关闭。
     *
     * @param anchorView 触发气泡弹窗的目标锚点视图
     * @param touchX 相对 anchorView 的点击 X 坐标（可选）
     * @param touchY 相对 anchorView 的点击 Y 坐标（可选）
     */
    private fun showEnergyTooltip(anchorView: View, touchX: Float? = null, touchY: Float? = null) {
        val record = currentRecord
        val powerManager = PowerUsageManager.getInstance(this)
        val totalCapMah = powerManager.getEffectiveDeviceCapacityMah()

        val totalWh = if (totalCapMah > 0f) {
            totalCapMah / 1000f * BatteryEnergyCalculator.DEFAULT_NOMINAL_VOLTAGE_VOLTS
        } else {
            null
        }

        val currentWh = record?.energyWh?.takeIf { it > 0f }
            ?: (if (totalCapMah > 0f && record != null) {
                record.levelPercent / 100f * (totalCapMah / 1000f * BatteryEnergyCalculator.DEFAULT_NOMINAL_VOLTAGE_VOLTS)
            } else null)

        val consumedWh = record?.totalEnergyWh?.takeIf { it > 0f }
        val unplugWh = if (currentWh != null && consumedWh != null) {
            currentWh + consumedWh
        } else {
            null
        }

        val currentStr = currentWh?.let { BatteryEnergyCalculator.formatDisplayEnergy(it, currentDisplayUnit) } ?: "--"
        val totalStr = totalWh?.let { BatteryEnergyCalculator.formatDisplayEnergy(it, currentDisplayUnit) } ?: "--"
        val unplugStr = unplugWh?.let { BatteryEnergyCalculator.formatDisplayEnergy(it.toFloat(), currentDisplayUnit) } ?: "--"
        val consumedStr = consumedWh?.let { BatteryEnergyCalculator.formatDisplayEnergy(it, currentDisplayUnit) } ?: "--"

        val message = getString(R.string.power_tooltip_energy, currentStr, totalStr, unplugStr, consumedStr)
        showBubbleTooltip(anchorView, message, touchX, touchY ?: 0f, autoDismissMs = 0L, forceAbove = true)
    }

    /**
     * 在目标锚点视图附近弹出气泡提示框并自动纳管生命周期以防内存泄漏。
     *
     * @param anchorView 触发气泡弹窗的目标锚点视图
     * @param message 待呈现的提示内容
     * @param touchX 相对 anchorView 的点击 X 坐标（可选）
     * @param touchY 相对 anchorView 的点击 Y 坐标（可选）
     * @param autoDismissMs 自动关闭倒计时毫秒数（<= 0 时不自动关闭）
     * @param forceAbove 是否强制在锚点视图上方展示
     */
    private fun showBubbleTooltip(
        anchorView: View,
        message: CharSequence,
        touchX: Float? = null,
        touchY: Float? = null,
        autoDismissMs: Long = 2800L,
        forceAbove: Boolean = false
    ) {
        BubbleTooltipHelper.showBubble(
            anchorView = anchorView,
            message = message,
            touchX = touchX,
            touchY = touchY,
            autoDismissMs = autoDismissMs,
            forceAbove = forceAbove
        )?.also {
            trackPopup(it)
        }
    }

    /**
     * 弹出选择排序方式下拉气泡菜单。
     * 支持按使用时长、按功耗、按消耗电量或按名称进行排序切换，并联动刷新应用列表。
     *
     * @param anchor 触发下拉菜单的目标锚点视图，为空时使用头部排序按钮或根视图兜底
     */
    private fun showSortChoiceDialog(anchor: View? = null) {
        val popupView = layoutInflater.inflate(R.layout.popup_power_sort_picker, null)
        val density = resources.displayMetrics.density
        val popupWidth = (170 * density).toInt()

        val popupWindow = trackPopup(
            PopupWindow(
                popupView,
                popupWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
            )
        )

        popupWindow.isOutsideTouchable = true
        popupWindow.isFocusable = true
        popupWindow.animationStyle = R.style.Animation_PopupTopRight

        val tvSortEnergy = popupView.findViewById<TextView>(R.id.tv_sort_energy)
        tvSortEnergy.text = if (currentDisplayUnit == PowerDisplayUnit.MAH) "按消耗电量 (mAh)" else "按消耗能量 (Wh)"

        val sortViews = listOf(
            popupView.findViewById<TextView>(R.id.tv_sort_duration),
            popupView.findViewById<TextView>(R.id.tv_sort_power),
            tvSortEnergy,
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

        val targetAnchor = anchor ?: headerBinding?.btnSceneSort ?: binding.root
        popupWindow.showAsDropDown(
            targetAnchor,
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
        cachedDisplayAppCount = count
        headerBinding?.tvAppListTitle?.text = getString(R.string.power_usage_list_format, count)
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
     * 将快照数据实体的时段状态与三维核心功耗看板渲染至头部绑定视图中。
     *
     * @param record 耗电历史快照数据实体
     * @param hBinding 头部视图绑定对象
     */
    private fun renderHeaderDetails(record: PowerUsageRecord, hBinding: ItemHistoryDetailHeaderBinding) {
        // 1. 卡片 1：时段、徽章与电池状态
        hBinding.tvDetailTimeRange.text = record.getFormattedTimeRange()

        if (record.isShizukuRealData) {
            hBinding.tvModeBadge.text = "Shizuku"
            hBinding.tvModeBadge.setTextColor(Color.parseColor("#2196F3"))
            hBinding.tvModeBadge.setBackgroundResource(R.drawable.bg_history_badge)
        } else {
            hBinding.tvModeBadge.text = getString(R.string.power_mode_normal)
            hBinding.tvModeBadge.setTextColor(Color.parseColor("#9CA3AF"))
            hBinding.tvModeBadge.setBackgroundResource(R.drawable.bg_dialog_btn_cancel)
        }

        hBinding.tvDetailLevel.text = "${record.levelPercent}%"
        hBinding.tvDetailTemp.text = String.format(Locale.getDefault(), "%.1f\u00A0℃", record.temperature)
        hBinding.tvDetailVoltage.text = String.format(Locale.getDefault(), "%.2f\u00A0V", record.voltageVolts)
        hBinding.tvDetailEnergy.text = BatteryEnergyCalculator.formatHistoryDetailOverviewEnergy(record.energyWh, currentDisplayUnit)

        // 2. 卡片 2：三维核心功耗与续航指标（按亮屏 / 息屏 / 全局三行呈现）
        val onEnergy = record.screenOnEnergyWh
        val offEnergy = record.screenOffEnergyWh
        val totalEnergy = record.totalEnergyWh

        val powerMgr = PowerUsageManager.getInstance(this@PowerHistoryDetailActivity)
        val totalEnergyInfo = cachedTotalEnergyInfo ?: powerMgr.getTotalEnergyInfo()
        val totalCapacityWh = cachedTotalCapacityWh
            ?: currentPrefixData?.totalCapacityWh?.takeIf { it > 0f }
            ?: totalEnergyInfo?.totalWh
            ?: powerMgr.getTotalEnergyWh()
            ?: powerMgr.getEffectiveDeviceCapacityMah().takeIf { it > 0f }?.let {
                BatteryEnergyCalculator.calculateTotalEnergyWh(it)
            }
        val baseTotalEnergy = totalCapacityWh?.takeIf { it > 0f }

        val onEnergyRatioStr = if (baseTotalEnergy != null) {
            val ratio = (onEnergy / baseTotalEnergy * 100f)
            String.format(Locale.getDefault(), "%.1f%%", ratio)
        } else {
            "--%"
        }

        val offEnergyRatioStr = if (baseTotalEnergy != null) {
            val ratio = (offEnergy / baseTotalEnergy * 100f)
            String.format(Locale.getDefault(), "%.1f%%", ratio)
        } else {
            "--%"
        }

        val globalEnergyRatioStr = if (baseTotalEnergy != null) {
            val ratio = (totalEnergy / baseTotalEnergy * 100f)
            String.format(Locale.getDefault(), "%.1f%%", ratio)
        } else {
            "--%"
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

        val overview = cachedOverviewStats ?: PowerOverviewStats(
            avgPowerWatts = record.avgPowerWatts,
            screenOnPowerWatts = record.screenOnPowerWatts,
            screenOffPowerWatts = record.screenOffPowerWatts,
            screenOnDurationMs = onDurationMs,
            screenOffDurationMs = offDurationMs,
            totalDurationMs = totalDurationMs
        )
        val (onSessionRate, _) = PowerDischargeMetricsViewBinder.calculateSessionDischargeRate(
            BatteryEnergyCalculator.ROW_SCREEN_ON, totalCapacityWh, overview
        )
        val (offSessionRate, _) = PowerDischargeMetricsViewBinder.calculateSessionDischargeRate(
            BatteryEnergyCalculator.ROW_SCREEN_OFF, totalCapacityWh, overview
        )
        val (globalSessionRate, _) = PowerDischargeMetricsViewBinder.calculateSessionDischargeRate(
            BatteryEnergyCalculator.ROW_GLOBAL, totalCapacityWh, overview
        )

        // 第一行：亮屏数据（前置亮色太阳图标，时长占比 / 能量占比 / 功耗+放电速度）
        val onEnergyText = BatteryEnergyCalculator.formatDisplayEnergy(onEnergy, currentDisplayUnit)
        hBinding.tvMetricScreenOnTime.text = formatValueWithSmallPercent(onDurationStr, onDurationRatioStr)
        hBinding.tvMetricScreenOnEnergy.text = formatValueWithSmallPercent(onEnergyText, onEnergyRatioStr)
        hBinding.tvMetricScreenOnPower.text = onPowerStr
        hBinding.tvMetricScreenOnSpeed.text = PowerDischargeMetricsViewBinder.formatDischargeRateSpannable(onSessionRate)

        // 第二行：息屏数据（前置暗色太阳图标，时长占比 / 能量占比 / 功耗+放电速度）
        val offEnergyText = BatteryEnergyCalculator.formatDisplayEnergy(offEnergy, currentDisplayUnit)
        hBinding.tvMetricScreenOffTime.text = formatValueWithSmallPercent(offDurationStr, offDurationRatioStr)
        hBinding.tvMetricScreenOffEnergy.text = formatValueWithSmallPercent(offEnergyText, offEnergyRatioStr)
        hBinding.tvMetricScreenOffPower.text = offPowerStr
        hBinding.tvMetricScreenOffSpeed.text = PowerDischargeMetricsViewBinder.formatDischargeRateSpannable(offSessionRate)

        // 第三行：全局数据（前置半亮半暗太阳图标，时长占比 / 能量占比 / 功耗+放电速度）
        val totalEnergyText = BatteryEnergyCalculator.formatDisplayEnergy(totalEnergy, currentDisplayUnit)
        hBinding.tvMetricGlobalTime.text = formatValueWithSmallPercent(totalDurationStr, "100%")
        hBinding.tvMetricGlobalEnergy.text = formatValueWithSmallPercent(totalEnergyText, globalEnergyRatioStr)
        hBinding.tvMetricGlobalPower.text = avgPowerStr
        hBinding.tvMetricGlobalSpeed.text = PowerDischargeMetricsViewBinder.formatDischargeRateSpannable(globalSessionRate)

        // 指标卡片三行点击气泡提示（亮屏 / 息屏 / 全局）
        hBinding.layoutMetricScreenOnRow.setOnClickListener {
            showBubbleTooltip(hBinding.layoutMetricScreenOnRow, "亮屏：时间、平均功耗、放电速度、能量")
        }
        hBinding.layoutMetricScreenOffRow.setOnClickListener {
            showBubbleTooltip(hBinding.layoutMetricScreenOffRow, "息屏：时间、平均功耗、放电速度、能量")
        }
        hBinding.layoutMetricGlobalRow.setOnClickListener {
            showBubbleTooltip(hBinding.layoutMetricGlobalRow, "全局：时间、平均功耗、放电速度、能量")
        }

        // 核心功耗指标卡片能量数值点击弹出等效电量（mAh）详细说明气泡弹框
        hBinding.tvMetricScreenOnEnergy.setOnClickListener {
            val message = BatteryEnergyCalculator.formatEnergyConversionMessage(
                title = "亮屏",
                energyWh = onEnergy,
                ratioStr = onEnergyRatioStr.takeIf { it != "--%" },
                totalEnergyInfo = totalEnergyInfo
            )
            showBubbleTooltip(hBinding.tvMetricScreenOnEnergy, message, autoDismissMs = 0L)
        }
        hBinding.tvMetricScreenOffEnergy.setOnClickListener {
            val message = BatteryEnergyCalculator.formatEnergyConversionMessage(
                title = "息屏",
                energyWh = offEnergy,
                ratioStr = offEnergyRatioStr.takeIf { it != "--%" },
                totalEnergyInfo = totalEnergyInfo
            )
            showBubbleTooltip(hBinding.tvMetricScreenOffEnergy, message, autoDismissMs = 0L)
        }
        hBinding.tvMetricGlobalEnergy.setOnClickListener {
            val message = BatteryEnergyCalculator.formatEnergyConversionMessage(
                title = "全局",
                energyWh = totalEnergy,
                ratioStr = globalEnergyRatioStr.takeIf { it != "--%" },
                totalEnergyInfo = totalEnergyInfo
            )
            showBubbleTooltip(hBinding.tvMetricGlobalEnergy, message, autoDismissMs = 0L)
        }
    }

    /**
     * 响应时间轴触摸点滑动，以零 GC、直接视图文本赋值方式实时刷新快照详情页核心三维功耗卡片与顶部标题。
     *
     * @param hBinding 头部视图绑定对象
     * @param subStats 阶段累计统计快照
     */
    private fun updateCursorPowerMetrics(
        hBinding: ItemHistoryDetailHeaderBinding,
        subStats: SubIntervalOverviewStats
    ) {
        val totalDurationMs = subStats.totalDurationMs
        val onDurationMs = subStats.screenOnDurationMs
        val offDurationMs = subStats.screenOffDurationMs

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

        val onDurationStr = formatCardDuration(onDurationMs, "")
        val offDurationStr = formatCardDuration(offDurationMs, "")
        val totalDurationStr = formatCardDuration(totalDurationMs, "")

        val baseTotalEnergy = currentPrefixData?.totalCapacityWh?.takeIf { it > 0f }
            ?: cachedTotalCapacityWh?.takeIf { it > 0f }
            ?: run {
                val powerMgr = PowerUsageManager.getInstance(this@PowerHistoryDetailActivity)
                cachedTotalEnergyInfo?.totalWh
                    ?: powerMgr.getTotalEnergyWh()
                    ?: powerMgr.getEffectiveDeviceCapacityMah().takeIf { it > 0f }?.let {
                        BatteryEnergyCalculator.calculateTotalEnergyWh(it)
                    }
            }?.takeIf { it > 0f }

        val onEnergyRatioStr = if (baseTotalEnergy != null && baseTotalEnergy > 0f) {
            val ratio = (subStats.screenOnEnergyWh / baseTotalEnergy * 100f)
            String.format(Locale.getDefault(), "%.1f%%", ratio)
        } else {
            "--%"
        }

        val offEnergyRatioStr = if (baseTotalEnergy != null && baseTotalEnergy > 0f) {
            val ratio = (subStats.screenOffEnergyWh / baseTotalEnergy * 100f)
            String.format(Locale.getDefault(), "%.1f%%", ratio)
        } else {
            "--%"
        }

        val globalEnergyRatioStr = if (baseTotalEnergy != null && baseTotalEnergy > 0f) {
            val ratio = (subStats.totalEnergyWh / baseTotalEnergy * 100f)
            String.format(Locale.getDefault(), "%.1f%%", ratio)
        } else {
            "--%"
        }

        val onPowerStr = if (subStats.screenOnPowerWatts >= 0.05f) {
            String.format(Locale.getDefault(), "%.2fW", subStats.screenOnPowerWatts)
        } else {
            "--"
        }
        val avgPowerStr = if (subStats.avgPowerWatts >= 0.05f) {
            String.format(Locale.getDefault(), "%.2fW", subStats.avgPowerWatts)
        } else {
            "--"
        }
        val offPowerStr = if (subStats.screenOffPowerWatts >= 0.05f) {
            String.format(Locale.getDefault(), "%.2fW", subStats.screenOffPowerWatts)
        } else {
            "--"
        }

        // 第一行：亮屏数据（时长占比 / 能量占比 / 功耗+放电速度）
        val onEnergyText = BatteryEnergyCalculator.formatDisplayEnergy(subStats.screenOnEnergyWh, currentDisplayUnit)
        hBinding.tvMetricScreenOnTime.text = formatValueWithSmallPercent(onDurationStr, onDurationRatioStr)
        hBinding.tvMetricScreenOnEnergy.text = formatValueWithSmallPercent(onEnergyText, onEnergyRatioStr)
        hBinding.tvMetricScreenOnPower.text = onPowerStr
        hBinding.tvMetricScreenOnSpeed.text = PowerDischargeMetricsViewBinder.formatDischargeRateSpannable(subStats.screenOnDischargeRate)

        // 第二行：息屏数据（时长占比 / 能量占比 / 功耗+放电速度）
        val offEnergyText = BatteryEnergyCalculator.formatDisplayEnergy(subStats.screenOffEnergyWh, currentDisplayUnit)
        hBinding.tvMetricScreenOffTime.text = formatValueWithSmallPercent(offDurationStr, offDurationRatioStr)
        hBinding.tvMetricScreenOffEnergy.text = formatValueWithSmallPercent(offEnergyText, offEnergyRatioStr)
        hBinding.tvMetricScreenOffPower.text = offPowerStr
        hBinding.tvMetricScreenOffSpeed.text = PowerDischargeMetricsViewBinder.formatDischargeRateSpannable(subStats.screenOffDischargeRate)

        // 第三行：全局数据（时长占比 / 能量占比 / 功耗+放电速度）
        val totalEnergyText = BatteryEnergyCalculator.formatDisplayEnergy(subStats.totalEnergyWh, currentDisplayUnit)
        hBinding.tvMetricGlobalTime.text = formatValueWithSmallPercent(totalDurationStr, "100%")
        hBinding.tvMetricGlobalEnergy.text = formatValueWithSmallPercent(totalEnergyText, globalEnergyRatioStr)
        hBinding.tvMetricGlobalPower.text = avgPowerStr
        hBinding.tvMetricGlobalSpeed.text = PowerDischargeMetricsViewBinder.formatDischargeRateSpannable(subStats.globalDischargeRate)

        // 动态指示当前查看阶段的时间点，在耗电详情标题后方单行展示
        val timeStr = threadLocalCursorTimeFormatter.get()?.format(Date(subStats.targetTimestamp)) ?: ""
        binding.tvTitle.text = getString(R.string.power_detail_title)
        if (timeStr.isNotEmpty()) {
            binding.tvCursorTime.text = "($timeStr)"
            binding.tvCursorTime.visibility = View.VISIBLE
        } else {
            binding.tvCursorTime.text = ""
            binding.tvCursorTime.visibility = View.GONE
        }
    }

    /**
     * 将当前缓存的全部快照数据（时段状态、三维核心看板、6个放电与息屏指标卡片、走势折线图、列表操作栏与条目数量）完整绑定至头部视图。
     * 无论后台数据反序列化与 ViewHolder 创建的先后时序如何，均能确保所有卡片与折线图数据完整无遗漏地呈现。
     *
     * @param hBinding 头部组合视图绑定对象
     */
    private fun bindHeaderAll(hBinding: ItemHistoryDetailHeaderBinding) {
        val record = currentRecord ?: return
        renderHeaderDetails(record, hBinding)
        bindMetricsCards(hBinding)

        hBinding.batteryTimelineView.setDisplayUnit(currentDisplayUnit)
        cachedTimelineState?.let { state ->
            hBinding.batteryTimelineView.setState(state.copy(displayUnit = currentDisplayUnit))
        }

        hBinding.layoutBackgroundStatsContainer.visibility =
            if (record.isShizukuRealData) View.VISIBLE else View.GONE

        val count = if (cachedDisplayAppCount > 0) cachedDisplayAppCount else appAdapter.getDisplayItemCount()
        if (count > 0) {
            hBinding.tvAppListTitle.text = getString(R.string.power_usage_list_format, count)
        }
    }

    /**
     * 将放电速度矩阵（亮屏 / 息屏 / 全局）与息屏状态矩阵（息屏 / 唤醒 / 深度睡眠）这 6 个核心卡片数据绑定至头部视图，
     * 并挂载轻触气泡弹窗交互监听。
     *
     * @param hBinding 头部组合视图绑定对象
     */
    private fun bindMetricsCards(hBinding: ItemHistoryDetailHeaderBinding) {
        val batteryLevel = currentRecord?.levelPercent?.toFloat()
        val remainingEnergyWh = currentRecord?.energyWh
        val tempCelsius = currentRecord?.temperature
        val trendPoints = currentRecord?.parseTrendPointsList()
        PowerDischargeMetricsViewBinder.bindDischargeSpeed(
            binding = hBinding.layoutDischargeSpeed,
            overview = cachedOverviewStats,
            totalCapacityWh = cachedTotalCapacityWh,
            sevenDaysStats = cachedSevenDaysStats,
            batteryLevelPercent = batteryLevel,
            remainingEnergyWh = remainingEnergyWh,
            tempCelsius = tempCelsius,
            currentTrendPoints = trendPoints,
            onCardClick = { anchorView, rowType ->
                showDischargeSpeedDetailBubble(anchorView, rowType)
            }
        )

        PowerDischargeMetricsViewBinder.bindSleepAwakeMetrics(
            binding = hBinding.layoutSleepAwake,
            overview = cachedOverviewStats,
            unit = currentDisplayUnit,
            onCardClick = { anchorView, cardType ->
                showSleepAwakeCardDetailBubble(anchorView, cardType)
            }
        )
    }

    /**
     * 弹出放电速度卡片（亮屏、息屏、全局）的详细信息说明气泡弹框。
     * 呈现当前放电速度、历史快照截止前 7 天统计放电速度与有效时长、满电可用时长、当前剩余续航（精确到秒）以及对应原理说明；
     * 并在亮屏卡片弹框中呈现电池真实总容量（Wh 与 mAh）。气泡弹窗不自动超时关闭，需用户手动轻触关闭。
     *
     * @param anchorView 触发气泡弹窗的目标锚点卡片视图
     * @param rowType 行分类标识（[BatteryEnergyCalculator.ROW_SCREEN_ON] 为亮屏行，[BatteryEnergyCalculator.ROW_SCREEN_OFF] 为息屏行，[BatteryEnergyCalculator.ROW_GLOBAL] 为全局行）
     */
    private fun showDischargeSpeedDetailBubble(anchorView: View, rowType: Int) {
        val overview = cachedOverviewStats
        val powerMgr = PowerUsageManager.getInstance(this)
        val totalEnergyInfo = cachedTotalEnergyInfo ?: powerMgr.getTotalEnergyInfo()
        val totalCapacityWh = cachedTotalCapacityWh
            ?: totalEnergyInfo?.totalWh
            ?: powerMgr.getTotalEnergyWh()
            ?: powerMgr.getEffectiveDeviceCapacityMah().takeIf { it > 0f }?.let {
                BatteryEnergyCalculator.calculateTotalEnergyWh(it)
            }
        val capacityMah = totalEnergyInfo?.equivalentMah
            ?: powerMgr.getEffectiveDeviceCapacityMah().takeIf { it > 0f }
            ?: totalCapacityWh?.takeIf { it > 0f }?.let { (it * 1000f) / BatteryEnergyCalculator.DEFAULT_NOMINAL_VOLTAGE_VOLTS }

        val sevenDays = cachedSevenDaysStats
        val currentRate = PowerDischargeMetricsViewBinder.getEffectiveDischargeRate(rowType, totalCapacityWh, overview, sevenDays)
        val (sevenDaysRate, sevenDaysDurationMs, fullDurationMs) = when (rowType) {
            BatteryEnergyCalculator.ROW_SCREEN_ON -> Triple(
                sevenDays?.screenOnDischargeRatePercentPerHour,
                sevenDays?.screenOnDurationMs,
                sevenDays?.fullChargeScreenOnDurationMs
            )
            BatteryEnergyCalculator.ROW_SCREEN_OFF -> Triple(
                sevenDays?.screenOffDischargeRatePercentPerHour,
                sevenDays?.screenOffDurationMs,
                sevenDays?.fullChargeScreenOffDurationMs
            )
            BatteryEnergyCalculator.ROW_GLOBAL -> Triple(
                sevenDays?.globalDischargeRatePercentPerHour,
                sevenDays?.globalDurationMs,
                sevenDays?.fullChargeGlobalDurationMs
            )
            else -> Triple(null, null, null)
        }

        val batteryPercent = currentRecord?.levelPercent?.toFloat()
        val remainingEnergyWh = currentRecord?.energyWh
        val trendPoints = currentRecord?.parseTrendPointsList()
        val tripleResult = BatteryEnergyCalculator.estimateTripleRuntimes(
            overview = overview,
            sevenDaysStats = sevenDays,
            batteryLevelPercent = batteryPercent,
            remainingEnergyWh = remainingEnergyWh,
            totalCapacityWh = totalCapacityWh,
            tempCelsius = currentRecord?.temperature,
            currentTrendPoints = trendPoints
        )

        val remEstimate = when (rowType) {
            BatteryEnergyCalculator.ROW_SCREEN_ON -> tripleResult.screenOnEstimate
            BatteryEnergyCalculator.ROW_SCREEN_OFF -> tripleResult.screenOffEstimate
            BatteryEnergyCalculator.ROW_GLOBAL -> tripleResult.globalEstimate
            else -> null
        }
        val remDurationMs = remEstimate?.remainingMs

        val (sessionRate, sessionDurationMs) = PowerDischargeMetricsViewBinder.calculateSessionDischargeRate(
            rowType = rowType,
            totalCapacityWh = totalCapacityWh,
            overview = overview
        )

        val message = BatteryEnergyCalculator.formatDischargeSpeedCardDetailMessage(
            rowType = rowType,
            currentRatePercentPerHour = currentRate,
            sevenDaysRatePercentPerHour = sevenDaysRate,
            sevenDaysDurationMs = sevenDaysDurationMs,
            fullDurationMs = fullDurationMs,
            predictedCapacityWh = totalCapacityWh,
            predictedCapacityMah = capacityMah,
            sceneScreenOnRatio = if (rowType == BatteryEnergyCalculator.ROW_GLOBAL) sevenDays?.sceneScreenOnRatio else null,
            sceneFullDurationMs = if (rowType == BatteryEnergyCalculator.ROW_GLOBAL) sevenDays?.fullChargeSceneCompositeDurationMs else null,
            remainingDurationMs = remDurationMs,
            v2Result = sevenDays?.v2Result,
            remainingEstimate = remEstimate,
            displayUnit = currentDisplayUnit,
            nominalVoltageVolts = BatteryEnergyCalculator.DEFAULT_NOMINAL_VOLTAGE_VOLTS,
            sessionRatePercentPerHour = sessionRate,
            sessionDurationMs = sessionDurationMs
        )
        if (message.isNotBlank()) {
            showBubbleTooltip(anchorView, message, autoDismissMs = 0L)
        }
    }

    /**
     * 弹出息屏、唤醒与深度睡眠三状态指标卡片的详细信息说明气泡弹框。
     * 呈现息屏总耗电与各状态能耗占比、时长与能量、消耗电量（mAh）及平均放电功耗，
     * 并详细阐述计算依据与系统底层物理休眠机制。气泡弹窗不自动超时关闭，需用户手动轻触关闭。
     *
     * @param anchorView 触发气泡弹窗的目标锚点卡片视图
     * @param cardType 卡片分类标识（[PowerDischargeMetricsViewBinder.CARD_SCREEN_OFF] 为息屏卡片，[PowerDischargeMetricsViewBinder.CARD_AWAKE] 为唤醒卡片，[PowerDischargeMetricsViewBinder.CARD_DEEP_SLEEP] 为深度睡眠卡片）
     */
    private fun showSleepAwakeCardDetailBubble(anchorView: View, cardType: Int) {
        val overview = cachedOverviewStats
        if (overview == null) {
            showBubbleTooltip(anchorView, getString(R.string.power_metric_no_record), autoDismissMs = 0L)
            return
        }

        val powerMgr = PowerUsageManager.getInstance(this)
        val totalEnergyInfo = cachedTotalEnergyInfo ?: powerMgr.getTotalEnergyInfo()
        val totalCapacityWh = cachedTotalCapacityWh
            ?: totalEnergyInfo?.totalWh
            ?: powerMgr.getTotalEnergyWh()
            ?: powerMgr.getEffectiveDeviceCapacityMah().takeIf { it > 0f }?.let {
                BatteryEnergyCalculator.calculateTotalEnergyWh(it)
            }
        val nominalTotalWh = totalCapacityWh?.takeIf { it > 0f }
        val nominalVoltage = BatteryEnergyCalculator.DEFAULT_NOMINAL_VOLTAGE_VOLTS

        val message = when (cardType) {
            PowerDischargeMetricsViewBinder.CARD_SCREEN_OFF -> {
                BatteryEnergyCalculator.formatScreenOffCardDetailMessage(
                    overview = overview,
                    nominalTotalWh = nominalTotalWh,
                    nominalVoltageVolts = nominalVoltage
                )
            }
            PowerDischargeMetricsViewBinder.CARD_AWAKE -> {
                BatteryEnergyCalculator.formatAwakeCardDetailMessage(
                    overview = overview,
                    nominalTotalWh = nominalTotalWh,
                    nominalVoltageVolts = nominalVoltage
                )
            }
            PowerDischargeMetricsViewBinder.CARD_DEEP_SLEEP -> {
                BatteryEnergyCalculator.formatDeepSleepCardDetailMessage(
                    overview = overview,
                    nominalTotalWh = nominalTotalWh,
                    nominalVoltageVolts = nominalVoltage
                )
            }
            else -> return
        }

        showBubbleTooltip(anchorView, message, autoDismissMs = 0L)
    }

    /**
     * 将解析后的完整耗电数据包绑定并渲染至卡片、图表与列表中。
     *
     * @param record 耗电历史快照数据实体
     */
    private fun renderRecordDetails(record: PowerUsageRecord) {
        currentRecord = record
        headerBinding?.let { bindHeaderAll(it) }

        // 反序列化全量数据包加载功耗时间轴、应用排行榜以及 7 天统计放电速度
        lifecycleScope.launch(Dispatchers.IO) {
            val fullPackage = record.toFullPowerPackage(this@PowerHistoryDetailActivity)
            val powerMgr = PowerUsageManager.getInstance(this@PowerHistoryDetailActivity)
            val totalEnergyInfo = fullPackage.batterySnapshot?.totalEnergyInfo ?: powerMgr.getTotalEnergyInfo()
            val totalCapacityWh = fullPackage.batterySnapshot?.totalEnergyWh
                ?: totalEnergyInfo?.totalWh
                ?: powerMgr.getTotalEnergyWh()
                ?: powerMgr.getEffectiveDeviceCapacityMah().takeIf { it > 0f }?.let {
                    BatteryEnergyCalculator.calculateTotalEnergyWh(it)
                }
            val sevenDaysStats = SevenDaysDischargeCalculator.calculateSevenDaysStats(
                context = applicationContext,
                currentOverview = null,
                totalCapacityWh = totalCapacityWh,
                baseTimestamp = record.getEffectiveEndTimeMs()
            )
            val selectedMetrics = headerBinding?.metricSelectorView?.getSelectedMetrics()
                ?: cachedTimelineState?.selectedMetrics
                ?: com.battery.analysis.timeline.presentation.TimelineMetric.entries.toSet()
            val timelineState = powerMgr.buildTimelineState(fullPackage, isHistoryRecord = true).copy(
                selectedMetrics = selectedMetrics,
                displayUnit = currentDisplayUnit
            )
            val prefixData = TimelinePrefixSumCalculator.buildPrefixData(
                timelineState = timelineState,
                overviewStats = fullPackage.overviewStats,
                totalCapacityWh = totalCapacityWh
            )

            withContext(Dispatchers.Main) {
                currentPrefixData = prefixData
                cachedOverviewStats = fullPackage.overviewStats
                cachedSevenDaysStats = sevenDaysStats
                cachedTotalCapacityWh = totalCapacityWh
                cachedTotalEnergyInfo = totalEnergyInfo
                cachedTimelineState = timelineState
                appAdapter.updateDisplayUnit(currentDisplayUnit)
                appAdapter.submitList(fullPackage.appList)
                cachedDisplayAppCount = appAdapter.getDisplayItemCount()
                headerBinding?.let { hBinding ->
                    bindHeaderAll(hBinding)
                } ?: run {
                    headerAdapter.notifyItemChanged(0)
                }
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
        val spannable = SpannableString(fullText)
        val start = mainText.length
        val end = fullText.length
        spannable.setSpan(AbsoluteSizeSpan(8, true), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return spannable
    }

    /**
     * 将时长毫秒数格式化为紧凑卡片展示文本（如 "01h03m" 或 "49m55s"）。
     *
     * @param durationMs 时长毫秒数值
     * @param rawText 原始文本兜底
     * @return 紧凑格式化时长文本
     */
    private fun formatCardDuration(durationMs: Long, rawText: String): String {
        if (durationMs <= 0L) return if (rawText.isNotBlank()) rawText else "00s"
        val totalSec = durationMs / 1000L
        val hours = totalSec / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val seconds = totalSec % 60L
        return when {
            hours > 0 -> String.format(Locale.getDefault(), "%02dh%02dm", hours, minutes)
            minutes > 0 -> String.format(Locale.getDefault(), "%02dm%02ds", minutes, seconds)
            else -> String.format(Locale.getDefault(), "%02ds", seconds)
        }
    }

    /**
     * 将中文或冒号格式的时长文本解析换算为对应的毫秒数。
     *
     * @param text 待解析的时长文本内容
     * @return 解析得出的时长毫秒数值
     */
    private fun parseDurationTextToMs(text: String): Long {
        if (text.isBlank()) return 0L
        var totalMs = 0L
        try {
            val dMatch = Regex("(\\d+)\\s*(?:天|d)").find(text)
            val hMatch = Regex("(\\d+)\\s*(?:小时|h)").find(text)
            val mMatch = Regex("(\\d+)\\s*(?:分|m)").find(text)
            val sMatch = Regex("(\\d+)\\s*(?:秒|s)").find(text)

            if (dMatch != null || hMatch != null || mMatch != null || sMatch != null) {
                dMatch?.groupValues?.get(1)?.toLongOrNull()?.let { totalMs += it * 86400000L }
                hMatch?.groupValues?.get(1)?.toLongOrNull()?.let { totalMs += it * 3600000L }
                mMatch?.groupValues?.get(1)?.toLongOrNull()?.let { totalMs += it * 60000L }
                sMatch?.groupValues?.get(1)?.toLongOrNull()?.let { totalMs += it * 1000L }
                return totalMs
            }

            val parts = text.split(":")
            if (parts.size == 3) {
                val h = parts[0].toLongOrNull() ?: 0L
                val m = parts[1].toLongOrNull() ?: 0L
                val s = parts[2].toLongOrNull() ?: 0L
                return (h * 3600 + m * 60 + s) * 1000L
            } else if (parts.size == 2) {
                val m = parts[0].toLongOrNull() ?: 0L
                val s = parts[1].toLongOrNull() ?: 0L
                return (m * 60 + s) * 1000L
            }
        } catch (_: Exception) {}
        return 0L
    }

    /**
     * 弹出删除单条耗电历史记录的高颜值确认对话框。
     */
    private fun showDeleteConfirmDialog() {
        val record = currentRecord ?: return
        val dialogView = layoutInflater.inflate(R.layout.dialog_custom_delete_confirm, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tv_dialog_delete_title)
        val tvDesc = dialogView.findViewById<TextView>(R.id.tv_dialog_delete_desc)
        val tvPreviewCat = dialogView.findViewById<TextView>(R.id.tv_preview_cat)
        val tvPreviewTime = dialogView.findViewById<TextView>(R.id.tv_preview_time)
        val tvPreviewSummary = dialogView.findViewById<TextView>(R.id.tv_preview_summary)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btn_dialog_delete_cancel)
        val btnConfirm = dialogView.findViewById<TextView>(R.id.btn_dialog_delete_confirm)

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
                db.deleteRecord(record.id)
                withContext(Dispatchers.Main) {
                    setResult(RESULT_OK)
                    finish()
                }
            }
        }

        showAndTrackDialog(dialog)
        applyDialogWindowStyle(dialog)
    }

    /**
     * 为自定义对话框应用统一的居中、宽度、半透明背景及系统级平滑弹出与收起动画的窗口样式。
     *
     * @param dialog 待配置样式的 [AlertDialog] 实例
     */
    private fun applyDialogWindowStyle(dialog: AlertDialog) {
        dialog.window?.let { window ->
            window.setWindowAnimations(R.style.Animation_Dialog_CenterPop)
            window.setBackgroundDrawableResource(android.R.color.transparent)
            val width = (resources.displayMetrics.widthPixels * 0.88).toInt()
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setGravity(Gravity.CENTER)
        }
    }

    /**
     * 活动不可见生命周期回调。
     * 安全关闭所有活跃弹窗并收起长列表至初始状态，释放系统图形缓冲与冗余 View。
     */
    override fun onStop() {
        super.onStop()
        dismissAllActiveWindows()
        if (::appAdapter.isInitialized) {
            appAdapter.collapseToInitial()
        }
    }

    /**
     * 活动销毁生命周期回调，强制安全关闭所有残留 Window。
     */
    override fun onDestroy() {
        dismissAllActiveWindows()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RECORD_ID = "extra_record_id"
        const val RESULT_LOAD_TO_MAIN = 1002
    }
}
