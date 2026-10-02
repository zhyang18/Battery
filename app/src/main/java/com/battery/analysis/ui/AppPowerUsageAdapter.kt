package com.battery.analysis.ui

import android.graphics.Color
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.battery.analysis.R
import com.battery.analysis.databinding.ItemAppPowerUsageBinding
import com.battery.analysis.databinding.ItemPowerChargingContentBinding
import com.battery.analysis.databinding.ItemPowerDischargeSpeedBinding
import com.battery.analysis.databinding.ItemPowerFirstTimeSetupBinding
import com.battery.analysis.databinding.ItemPowerSceneHeaderBinding
import com.battery.analysis.databinding.ItemPowerShizukuGuideBinding
import com.battery.analysis.databinding.ItemPowerSleepAwakeMetricsBinding
import com.battery.analysis.databinding.ItemPowerSnapshotBannerBinding
import com.battery.analysis.databinding.ItemPowerUsageOverviewBinding
import com.battery.analysis.databinding.LayoutChargingStatsBinding
import com.battery.analysis.manager.PowerOverviewStats
import com.battery.analysis.manager.PowerUsageManager
import com.battery.analysis.model.AppPowerUsageItem
import com.battery.analysis.model.PowerUsageItem
import com.battery.analysis.timeline.presentation.BatteryTimelineState
import com.battery.analysis.timeline.util.DrawableBitmapCache
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.ShapeAppearanceModel
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import androidx.core.text.HtmlCompat
import java.util.Locale

/**
 * 电池统计页面主 RecyclerView 多类型列表适配器。
 * 统一调度首次模式引导、历史快照横幅、Shizuku 权限提示、功耗时间轴概览卡片、
 * 使用场景操作栏、具体的应用能耗条目以及充电统计卡片，彻底根除 NestedScrollView 嵌套，
 * 实现应用列表完整的 ViewHolder 虚拟化与高效复用。
 */
class AppPowerUsageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TAG = "PowerUsageRecycler"

        /** 视图类型：首次模式选择引导卡片 */
        const val TYPE_FIRST_TIME_SETUP = 1
        /** 视图类型：历史快照提示横幅 */
        const val TYPE_SNAPSHOT_BANNER = 2
        /** 视图类型：Shizuku 权限与状态提示卡片 */
        const val TYPE_SHIZUKU_GUIDE = 3
        /** 视图类型：使用过程核心概览卡片（四维时间轴 + 瞬时指标） */
        const val TYPE_USAGE_OVERVIEW = 4
        /** 视图类型：使用场景列表标题行（含后台开关与排序按钮） */
        const val TYPE_SCENE_HEADER = 5
        /** 视图类型：单个应用能耗列表项（支持虚拟化复用） */
        const val TYPE_APP_USAGE = 6
        /** 视图类型：充电统计卡片（三合一走势折线图） */
        const val TYPE_CHARGING_CONTENT = 7
        /** 视图类型：放电速度概览卡片（包含全局、亮屏、息屏放电速度三个主要模块） */
        const val TYPE_DISCHARGE_SPEED = 8
        /** 视图类型：唤醒与深度睡眠耗电指标双卡片 */
        const val TYPE_SLEEP_AWAKE_METRICS = 9
    }

    // 当前供 RecyclerView 绑定的完整平铺条目数据列表
    private val items = mutableListOf<PowerUsageItem>()

    // 原始完整应用功耗数据集合
    private val allAppItems = mutableListOf<AppPowerUsageItem>()

    // 当前经筛选与排序后供列表渲染呈现的应用数据集合
    private val displayAppItems = mutableListOf<AppPowerUsageItem>()

    // 排序模式：0-按使用时长降序，1-按平均功耗升序，2-按消耗电量(Wh)降序，3-按应用名称升序
    private var sortMode: Int = 0

    // 是否展示后台统计数据及后台运行应用（默认开启）
    private var showBackgroundStats: Boolean = true

    // 当前页面模式：0 为耗电统计，1 为充电统计
    private var currentDisplayTab: Int = 0

    // 是否显示首次配置引导
    private var showFirstTimeSetup: Boolean = false
    private var setupSelectedMode: Int = 0
    private var isShizukuInstalled: Boolean = true

    // 是否显示历史快照横幅及提示文本
    private var showSnapshotBanner: Boolean = false
    private var snapshotHintText: String = ""

    // 是否显示 Shizuku 引导卡片及提示文本
    private var showShizukuGuide: Boolean = false
    private var shizukuGuideTitle: String = ""
    private var shizukuGuideDesc: String = ""
    private var shizukuGuideActionText: String = ""

    // 普通模式权限横幅显隐
    private var showPermissionBanner: Boolean = false

    // 概览卡片最新缓存数据
    private var cachedEnergyWh: Float = 0f
    private var cachedTotalEnergyWh: Float? = null
    private var cachedLastUnplugWh: Float? = null
    private var cachedTemperature: Float = 0f
    private var cachedVoltageVolts: Float = 0f
    private var cachedIsCharging: Boolean = false
    private var cachedTimelineState: BatteryTimelineState? = null
    // 放电速度概览卡片最新缓存数据
    private var cachedOverviewStats: PowerOverviewStats? = null

    // 弱保持当前活跃的卡片 ViewHolder 引用以提供平滑桥接
    var dischargeSpeedHolder: DischargeSpeedViewHolder? = null
        private set
    var sleepAwakeHolder: SleepAwakeViewHolder? = null
        private set
    var overviewHolder: UsageOverviewViewHolder? = null
        private set
    var sceneHeaderHolder: SceneHeaderViewHolder? = null
        private set
    var chargingHolder: ChargingContentViewHolder? = null
        private set
    var setupHolder: FirstTimeSetupViewHolder? = null
        private set

    // 交互监听回调
    var onItemClickListener: ((AppPowerUsageItem) -> Unit)? = null
    var onListCountChangedListener: ((Int) -> Unit)? = null
    var onBackgroundStatsChangedListener: ((Boolean) -> Unit)? = null
    var onSortButtonClickedListener: ((View) -> Unit)? = null
    var onHelpButtonClickedListener: (() -> Unit)? = null
    var onPermissionGrantClickedListener: (() -> Unit)? = null
    var onRestoreRealtimeClickedListener: (() -> Unit)? = null
    var onShizukuActionClickedListener: (() -> Unit)? = null
    var onSetupModeSelectedListener: ((Int) -> Unit)? = null
    var onSetupConfirmListener: (() -> Unit)? = null

    // MetricSelectorView 与 Charging 图表的外部监听器保持
    var onMetricsChangedListener: ((Set<com.battery.analysis.timeline.presentation.TimelineMetric>) -> Unit)? = null
    var onEnergyClickListener: ((View, Float, Float) -> Unit)? = null
    var onMetricModuleClickedListener: ((View, Int) -> Unit)? = null

    // ==================== ViewHolder 定义 ====================

    /**
     * 首次进入模式引导卡片 ViewHolder。
     *
     * @param binding 视图绑定对象
     */
    inner class FirstTimeSetupViewHolder(val binding: ItemPowerFirstTimeSetupBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            setIsRecyclable(false)
        }
    }

    /**
     * 历史快照提示横幅 ViewHolder。
     *
     * @param binding 视图绑定对象
     */
    inner class SnapshotBannerViewHolder(val binding: ItemPowerSnapshotBannerBinding) :
        RecyclerView.ViewHolder(binding.root)

    /**
     * Shizuku 授权与状态提示引导卡片 ViewHolder。
     *
     * @param binding 视图绑定对象
     */
    inner class ShizukuGuideViewHolder(val binding: ItemPowerShizukuGuideBinding) :
        RecyclerView.ViewHolder(binding.root)

    /**
     * 放电速度概览卡片 ViewHolder（展示全局、亮屏、息屏放电速度三个主要模块）。
     *
     * @param binding 放电速度概览卡片视图绑定对象
     */
    inner class DischargeSpeedViewHolder(val binding: ItemPowerDischargeSpeedBinding) :
        RecyclerView.ViewHolder(binding.root)

    /**
     * 息屏唤醒与深度睡眠双卡片 ViewHolder（展示唤醒与深度睡眠耗电占比与能量）。
     *
     * @param binding 唤醒与深度睡眠卡片视图绑定对象
     */
    inner class SleepAwakeViewHolder(val binding: ItemPowerSleepAwakeMetricsBinding) :
        RecyclerView.ViewHolder(binding.root)

    /**
     * 耗电模式使用过程概览卡片 ViewHolder。
     *
     * @param binding 视图绑定对象
     */
    inner class UsageOverviewViewHolder(val binding: ItemPowerUsageOverviewBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            setIsRecyclable(false)
        }
    }

    /**
     * 使用场景列表标题栏与操作按钮组 ViewHolder。
     *
     * @param binding 视图绑定对象
     */
    inner class SceneHeaderViewHolder(val binding: ItemPowerSceneHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            setIsRecyclable(false)
        }
    }

    /**
     * 单个应用能耗列表项 ViewHolder。
     *
     * @param binding 视图绑定对象
     */
    inner class AppViewHolder(val binding: ItemAppPowerUsageBinding) :
        RecyclerView.ViewHolder(binding.root)

    /**
     * 充电统计卡片 ViewHolder。
     *
     * @param binding 视图绑定对象
     */
    inner class ChargingContentViewHolder(val binding: ItemPowerChargingContentBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            setIsRecyclable(false)
        }
    }

    // ==================== 生命周期与复用实现 ====================

    /**
     * 获取指定索引位置条目的 ViewType 枚举标识。
     *
     * @param position 列表项下标
     * @return 条目对应的 ViewType 整型常量
     */
    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is PowerUsageItem.FirstTimeSetup -> TYPE_FIRST_TIME_SETUP
            is PowerUsageItem.SnapshotBanner -> TYPE_SNAPSHOT_BANNER
            is PowerUsageItem.ShizukuGuide -> TYPE_SHIZUKU_GUIDE
            is PowerUsageItem.DischargeSpeedMetrics -> TYPE_DISCHARGE_SPEED
            is PowerUsageItem.SleepAwakeMetrics -> TYPE_SLEEP_AWAKE_METRICS
            is PowerUsageItem.UsageOverview -> TYPE_USAGE_OVERVIEW
            is PowerUsageItem.SceneHeader -> TYPE_SCENE_HEADER
            is PowerUsageItem.AppUsage -> TYPE_APP_USAGE
            is PowerUsageItem.ChargingContent -> TYPE_CHARGING_CONTENT
        }
    }

    /**
     * 创建对应 ViewType 的 ViewHolder 实例。
     * 在创建 App ViewHolder 时输出调试日志以验证虚拟化与复用机制。
     *
     * @param parent 父容器视图
     * @param viewType 视图类型
     * @return 创建的 ViewHolder 实例
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_FIRST_TIME_SETUP -> {
                val binding = ItemPowerFirstTimeSetupBinding.inflate(inflater, parent, false)
                FirstTimeSetupViewHolder(binding).also { setupHolder = it }
            }
            TYPE_SNAPSHOT_BANNER -> {
                val binding = ItemPowerSnapshotBannerBinding.inflate(inflater, parent, false)
                SnapshotBannerViewHolder(binding)
            }
            TYPE_SHIZUKU_GUIDE -> {
                val binding = ItemPowerShizukuGuideBinding.inflate(inflater, parent, false)
                ShizukuGuideViewHolder(binding)
            }
            TYPE_DISCHARGE_SPEED -> {
                val binding = ItemPowerDischargeSpeedBinding.inflate(inflater, parent, false)
                DischargeSpeedViewHolder(binding).also { dischargeSpeedHolder = it }
            }
            TYPE_SLEEP_AWAKE_METRICS -> {
                val binding = ItemPowerSleepAwakeMetricsBinding.inflate(inflater, parent, false)
                SleepAwakeViewHolder(binding).also { sleepAwakeHolder = it }
            }
            TYPE_USAGE_OVERVIEW -> {
                val binding = ItemPowerUsageOverviewBinding.inflate(inflater, parent, false)
                UsageOverviewViewHolder(binding).also {
                    overviewHolder = it
                    it.binding.metricSelectorView.setOnMetricsChangedListener { metrics ->
                        onMetricsChangedListener?.invoke(metrics)
                    }
                    it.binding.batteryTimelineView.setOnEnergyClickListener { view, x, y ->
                        onEnergyClickListener?.invoke(view, x, y)
                    }
                }
            }
            TYPE_SCENE_HEADER -> {
                val binding = ItemPowerSceneHeaderBinding.inflate(inflater, parent, false)
                SceneHeaderViewHolder(binding).also {
                    sceneHeaderHolder = it
                    it.binding.btnSceneSort.setOnClickListener { v ->
                        onSortButtonClickedListener?.invoke(v)
                    }
                    it.binding.btnSceneHelp.setOnClickListener {
                        onHelpButtonClickedListener?.invoke()
                    }
                    it.binding.btnGrantPermission.setOnClickListener {
                        onPermissionGrantClickedListener?.invoke()
                    }
                }
            }
            TYPE_APP_USAGE -> {
                val binding = ItemAppPowerUsageBinding.inflate(inflater, parent, false)
                val shapeModel = ShapeAppearanceModel.builder()
                    .setAllCorners(CornerFamily.ROUNDED, 24f)
                    .build()
                binding.ivAppIcon.shapeAppearanceModel = shapeModel
                val holder = AppViewHolder(binding)
                Log.d(TAG, "create ViewHolder: ${holder.hashCode()}")
                holder
            }
            TYPE_CHARGING_CONTENT -> {
                val binding = ItemPowerChargingContentBinding.inflate(inflater, parent, false)
                ChargingContentViewHolder(binding).also { chargingHolder = it }
            }
            else -> throw IllegalArgumentException("Unsupported viewType: $viewType")
        }
    }

    /**
     * 将数据实体绑定至对应的 ViewHolder 视图组件中。
     * 在绑定 App ViewHolder 时输出复用验证日志。
     *
     * @param holder 视图持有者
     * @param position 列表项下标
     */
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is PowerUsageItem.FirstTimeSetup -> {
                bindFirstTimeSetup(holder as FirstTimeSetupViewHolder)
            }
            is PowerUsageItem.SnapshotBanner -> {
                bindSnapshotBanner(holder as SnapshotBannerViewHolder, item.hintText)
            }
            is PowerUsageItem.ShizukuGuide -> {
                bindShizukuGuide(holder as ShizukuGuideViewHolder, item)
            }
            is PowerUsageItem.DischargeSpeedMetrics -> {
                val speedHolder = holder as DischargeSpeedViewHolder
                dischargeSpeedHolder = speedHolder
                bindDischargeSpeed(speedHolder)
            }
            is PowerUsageItem.SleepAwakeMetrics -> {
                val sleepHolder = holder as SleepAwakeViewHolder
                sleepAwakeHolder = sleepHolder
                bindSleepAwakeMetrics(sleepHolder)
            }
            is PowerUsageItem.UsageOverview -> {
                bindUsageOverview(holder as UsageOverviewViewHolder)
            }
            is PowerUsageItem.SceneHeader -> {
                bindSceneHeader(holder as SceneHeaderViewHolder, item)
            }
            is PowerUsageItem.AppUsage -> {
                val appHolder = holder as AppViewHolder
                Log.d(TAG, "bind position=$position holder=${appHolder.hashCode()}")
                val isLastRow = position == items.size - 1 || (position + 1 < items.size && items[position + 1] !is PowerUsageItem.AppUsage)
                bindAppUsage(appHolder, item.data, isLastRow)
            }
            is PowerUsageItem.ChargingContent -> {
                // 充电视图内部由 Fragment 的 renderChargingData 直接驱动
            }
        }
    }

    /**
     * 返回当前列表中的条目总数。
     *
     * @return 条目数量
     */
    override fun getItemCount(): Int = items.size

    // ==================== 具体条目绑定逻辑 ====================

    /**
     * 绑定首次配置引导卡片数据与事件。
     *
     * @param holder 首次配置 ViewHolder
     */
    private fun bindFirstTimeSetup(holder: FirstTimeSetupViewHolder) {
        with(holder.binding) {
            val context = root.context
            if (isShizukuInstalled) {
                tvSetupShizukuTitle.text = context.getString(R.string.power_mode_shizuku)
                cardSetupModeShizuku.alpha = 1.0f
            } else {
                tvSetupShizukuTitle.text = context.getString(R.string.power_mode_shizuku_not_installed)
                cardSetupModeShizuku.alpha = 0.55f
            }

            if (setupSelectedMode == 0 && isShizukuInstalled) { // MODE_SHIZUKU
                cardSetupModeShizuku.strokeColor = Color.parseColor("#2196F3")
                cardSetupModeShizuku.strokeWidth = (2 * context.resources.displayMetrics.density).toInt()
                radioSetupShizuku.isChecked = true

                cardSetupModeNormal.strokeColor = Color.parseColor("#18888888")
                cardSetupModeNormal.strokeWidth = (1 * context.resources.displayMetrics.density).toInt()
                radioSetupNormal.isChecked = false
            } else {
                cardSetupModeNormal.strokeColor = Color.parseColor("#2196F3")
                cardSetupModeNormal.strokeWidth = (2 * context.resources.displayMetrics.density).toInt()
                radioSetupNormal.isChecked = true

                cardSetupModeShizuku.strokeColor = Color.parseColor("#18888888")
                cardSetupModeShizuku.strokeWidth = (1 * context.resources.displayMetrics.density).toInt()
                radioSetupShizuku.isChecked = false
            }

            cardSetupModeShizuku.setOnClickListener {
                if (isShizukuInstalled) {
                    onSetupModeSelectedListener?.invoke(0)
                }
            }
            cardSetupModeNormal.setOnClickListener {
                onSetupModeSelectedListener?.invoke(1)
            }
            btnSetupConfirm.setOnClickListener {
                onSetupConfirmListener?.invoke()
            }
        }
    }

    /**
     * 绑定快照提示横幅数据与点击事件。
     *
     * @param holder 快照横幅 ViewHolder
     * @param hintText 提示文字
     */
    private fun bindSnapshotBanner(holder: SnapshotBannerViewHolder, hintText: String) {
        holder.binding.tvPowerSnapshotHint.text = hintText
        holder.binding.btnPowerRestoreRealtime.setOnClickListener {
            onRestoreRealtimeClickedListener?.invoke()
        }
    }

    /**
     * 绑定 Shizuku 引导卡片数据与事件。
     *
     * @param holder Shizuku 引导卡片 ViewHolder
     * @param item 引导数据实体
     */
    private fun bindShizukuGuide(holder: ShizukuGuideViewHolder, item: PowerUsageItem.ShizukuGuide) {
        holder.binding.tvShizukuGuideTitle.text = item.title
        holder.binding.tvShizukuGuideDesc.text = item.desc
        holder.binding.btnShizukuAction.text = item.actionText
        holder.binding.btnShizukuAction.setOnClickListener {
            onShizukuActionClickedListener?.invoke()
        }
    }

    /**
     * 绑定放电速度核心概览卡片（呈现亮屏放电速度、息屏放电速度、全局放电速度三个主要模块的每小时放电占比百分比）。
     *
     * @param holder 放电速度概览卡片 ViewHolder
     */
    private fun bindDischargeSpeed(holder: DischargeSpeedViewHolder) {
        val overview = cachedOverviewStats
        with(holder.binding) {
            if (overview == null) {
                tvModuleScreenOnSpeed.text = "--"
                tvModuleScreenOffSpeed.text = "--"
                tvModuleGlobalSpeed.text = "--"
            } else {
                val totalDurationMs = if (overview.totalDurationMs > 0L) overview.totalDurationMs else parseDurationTextToMs(overview.totalDurationText)
                val onDurationMs = if (overview.screenOnDurationMs > 0L) overview.screenOnDurationMs else parseDurationTextToMs(overview.screenOnDurationText)
                val offDurationMs = if (overview.screenOffDurationMs > 0L) overview.screenOffDurationMs else parseDurationTextToMs(overview.screenOffDurationText)

                // 核心指标计算：每小时放电占比百分比（%/h），以硬件微积分真值真实换算
                val onRate = calculateDischargeRatePercentPerHour(overview.screenOnPowerWatts, cachedTotalEnergyWh, onDurationMs)
                val offRate = calculateDischargeRatePercentPerHour(overview.screenOffPowerWatts, cachedTotalEnergyWh, offDurationMs)
                val globalRate = calculateDischargeRatePercentPerHour(overview.avgPowerWatts, cachedTotalEnergyWh, totalDurationMs)

                // 模块 1：亮屏放电速度
                tvModuleScreenOnSpeed.text = formatDischargeRateSpannable(onRate)

                // 模块 2：息屏放电速度
                tvModuleScreenOffSpeed.text = formatDischargeRateSpannable(offRate)

                // 模块 3：全局放电速度（最后显示）
                tvModuleGlobalSpeed.text = formatDischargeRateSpannable(globalRate)
            }

            layoutModuleScreenOn.setOnClickListener {
                onMetricModuleClickedListener?.invoke(layoutModuleScreenOn, PowerUsageFragment.ROW_SCREEN_ON)
            }
            layoutModuleScreenOff.setOnClickListener {
                onMetricModuleClickedListener?.invoke(layoutModuleScreenOff, PowerUsageFragment.ROW_SCREEN_OFF)
            }
            layoutModuleGlobal.setOnClickListener {
                onMetricModuleClickedListener?.invoke(layoutModuleGlobal, PowerUsageFragment.ROW_GLOBAL)
            }
        }
    }

    /**
     * 绑定息屏、唤醒与深度睡眠三状态指标卡片数据。
     * 包含息屏总耗电、息屏唤醒活跃耗电与深度睡眠挂起耗电三个等宽紧凑卡片，
     * 严格遵循物理能量守恒定律，采用 4m10s 紧凑时间格式与 16sp 粗体百分比对齐。
     *
     * @param holder 息屏、唤醒与深度睡眠卡片 ViewHolder
     */
    private fun bindSleepAwakeMetrics(holder: SleepAwakeViewHolder) {
        val overview = cachedOverviewStats
        with(holder.binding) {
            val noRecordText = root.context.getString(R.string.power_metric_no_record)
            if (overview == null) {
                tvScreenOffPercent.text = "--"
                tvScreenOffSubtitle.text = noRecordText
                tvAwakePercent.text = "--"
                tvAwakeSubtitle.text = noRecordText
                tvSleepPercent.text = "--"
                tvSleepSubtitle.text = noRecordText
                return
            }

            // 1. 息屏卡片
            if (overview.screenOffDurationMs <= 0L && overview.screenOffEnergyWh <= 0f) {
                tvScreenOffPercent.text = "--"
                tvScreenOffSubtitle.text = noRecordText
            } else {
                val offPercent = if (overview.screenOffPercent > 0f) {
                    overview.screenOffPercent
                } else {
                    overview.screenOffAwakePercent + overview.screenOffDeepSleepPercent
                }
                tvScreenOffPercent.text = if (offPercent > 0f) String.format(Locale.US, "%.1f%%", offPercent) else "--"

                val durText = if (overview.screenOffDurationMs > 0L) {
                    PowerUsageManager.formatCompactDuration(overview.screenOffDurationMs)
                } else {
                    overview.screenOffDurationText.ifBlank { "0s" }
                }
                val energyText = String.format(Locale.US, "%.3fWh", overview.screenOffEnergyWh)
                tvScreenOffSubtitle.text = "$durText $energyText"
            }

            // 2. 唤醒卡片
            if (overview.screenOffAwakeDurationMs <= 0L && overview.screenOffAwakeEnergyWh <= 0f) {
                tvAwakePercent.text = "--"
                tvAwakeSubtitle.text = noRecordText
            } else {
                val awakePercentStr = if (overview.screenOffAwakePercent > 0f) {
                    String.format(Locale.US, "%.1f%%", overview.screenOffAwakePercent)
                } else {
                    "--"
                }
                tvAwakePercent.text = awakePercentStr

                val durText = if (overview.screenOffAwakeDurationMs > 0L) {
                    PowerUsageManager.formatCompactDuration(overview.screenOffAwakeDurationMs)
                } else {
                    overview.screenOffAwakeDurationText.ifBlank { "0s" }
                }
                val energyText = String.format(Locale.US, "%.3fWh", overview.screenOffAwakeEnergyWh)
                val subtitleHtml = "<font color=\"#FFA4A4\">$durText</font> <font color=\"#FFA4A4\">$energyText</font>"
                tvAwakeSubtitle.text = HtmlCompat.fromHtml(subtitleHtml, HtmlCompat.FROM_HTML_MODE_LEGACY)
            }

            // 3. 深度睡眠卡片
            if (overview.screenOffDeepSleepDurationMs <= 0L && overview.screenOffDeepSleepEnergyWh <= 0f) {
                tvSleepPercent.text = "--"
                tvSleepSubtitle.text = noRecordText
            } else {
                val sleepPercentStr = if (overview.screenOffDeepSleepPercent > 0f) {
                    String.format(Locale.US, "%.1f%%", overview.screenOffDeepSleepPercent)
                } else {
                    "--"
                }
                tvSleepPercent.text = sleepPercentStr

                val durText = if (overview.screenOffDeepSleepDurationMs > 0L) {
                    PowerUsageManager.formatCompactDuration(overview.screenOffDeepSleepDurationMs)
                } else {
                    overview.screenOffDeepSleepDurationText.ifBlank { "0s" }
                }
                val energyText = String.format(Locale.US, "%.3fWh", overview.screenOffDeepSleepEnergyWh)
                val subtitleHtml = "<font color=\"#B71C1C\">$durText</font> <font color=\"#B71C1C\">$energyText</font>"
                tvSleepSubtitle.text = HtmlCompat.fromHtml(subtitleHtml, HtmlCompat.FROM_HTML_MODE_LEGACY)
            }
        }
    }

    /**
     * 将放电速度数值与单位转换为复合富文本，数值部分保持较大字号（16sp），
     * 单位部分（"%/h"）缩小至 11sp，以图文混排高质感样式呈现。
     *
     * @param rate 放电速率百分比数值（单位：%/h），若为空或非正数则返回 "--"
     * @return 格式化后的富文本对象 [CharSequence]
     */
    private fun formatDischargeRateSpannable(rate: Float?): CharSequence {
        if (rate == null || rate <= 0f) {
            return "--"
        }
        val numberStr = String.format(Locale.getDefault(), "%.1f", rate)
        val unitStr = "%/h"
        val fullText = "$numberStr$unitStr"
        val spannable = SpannableString(fullText)
        spannable.setSpan(
            AbsoluteSizeSpan(11, true),
            numberStr.length,
            fullText.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return spannable
    }

    /**
     * 根据放电平均功耗与电池总容量能量计算每小时放电百分比速率（%/h）。
     *
     * 遵循物理学定义：
     * 功率 P (W) = 能量 E (Wh) / 时间 t (h)；
     * 每小时放电百分比 Rate (%/h) = (P (W) / 电池总容量 E_total (Wh)) * 100%。
     * 严禁编写任何假数据或虚拟钳位，当功率或电池总能量无效时返回 null。
     *
     * @param powerWatts 放电平均功率（单位：W）
     * @param totalCapacityWh 电池标称/满充总能量容量（单位：Wh）
     * @param durationMs 对应时段实际持续时长（毫秒）
     * @return 计算得到的每小时放电百分比（单位：%/h），若数据缺失或非正数则返回 null
     */
    private fun calculateDischargeRatePercentPerHour(
        powerWatts: Float,
        totalCapacityWh: Float?,
        durationMs: Long
    ): Float? {
        if (totalCapacityWh == null || totalCapacityWh <= 0f) {
            return null
        }
        if (powerWatts <= 0f || durationMs <= 0L) {
            return null
        }
        val rate = (powerWatts / totalCapacityWh) * 100f
        return if (rate > 0f) rate else null
    }

    /**
     * 将毫秒时长按友好格式转化为紧凑展示文本。
     *
     * @param ms 持续物理毫秒值
     * @param fallbackText 当毫秒值为 0 或无效时的备用文本
     * @return 格式化后的紧凑时长字符串（如 "0m0s"、"13m44s"）
     */
    private fun formatCardDuration(ms: Long, fallbackText: String): String {
        if (ms <= 0L) {
            return if (fallbackText.isNotBlank()) fallbackText else "--"
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
     * 从时长文本字符串中解析出物理毫秒值。
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
     * 绑定使用过程概览卡片（四维时间轴与底部指标选择器）。
     *
     * @param holder 概览卡片 ViewHolder
     */
    private fun bindUsageOverview(holder: UsageOverviewViewHolder) {
        with(holder.binding) {
            cachedTimelineState?.let { state ->
                batteryTimelineView.setState(state)
            }
        }
    }

    /**
     * 绑定使用场景操作栏数据与状态。
     *
     * @param holder 场景头部 ViewHolder
     * @param item 场景数据实体
     */
    private fun bindSceneHeader(holder: SceneHeaderViewHolder, item: PowerUsageItem.SceneHeader) {
        with(holder.binding) {
            val context = root.context
            tvSceneTitle.text = context.getString(R.string.power_usage_list_format, item.count)
            // 先解绑监听器，防止被动数据绑定触发 onCheckedChanged 导致在 RecyclerView 布局计算期间递归刷新
            switchBackgroundStats.setOnCheckedChangeListener(null)
            switchBackgroundStats.isChecked = item.showBackgroundStats
            switchBackgroundStats.setOnCheckedChangeListener { buttonView, isChecked ->
                // 仅当用户主动触摸操作开关时派发事件
                if (buttonView.isPressed) {
                    onBackgroundStatsChangedListener?.invoke(isChecked)
                }
            }
            layoutPermissionBanner.visibility = if (showPermissionBanner) View.VISIBLE else View.GONE
        }
    }

    /**
     * 绑定单个应用条目的视图数据。
     * 当处于使用列表最后一行时，为背景添加左下、右下 14dp 圆角。
     *
     * @param holder 应用条目 ViewHolder
     * @param item 应用能耗数据实体
     * @param isLastRow 是否为使用列表的最后一行数据
     */
    private fun bindAppUsage(holder: AppViewHolder, item: AppPowerUsageItem, isLastRow: Boolean) {
        val context = holder.itemView.context
        val density = context.resources.displayMetrics.density

        val bgRes = if (isLastRow) {
            R.drawable.bg_item_app_usage_bottom_rounded
        } else {
            R.drawable.bg_item_app_usage_normal
        }
        val padStart = (10 * density).toInt()
        val padEnd = (10 * density).toInt()
        val padTop = (5 * density).toInt()
        val padBottom = if (isLastRow) (8 * density).toInt() else (5 * density).toInt()

        holder.itemView.setBackgroundResource(bgRes)
        holder.itemView.setPaddingRelative(padStart, padTop, padEnd, padBottom)
        holder.itemView.clipToOutline = isLastRow

        holder.itemView.setOnClickListener {
            onItemClickListener?.invoke(item)
        }
        with(holder.binding) {
            val targetIconPx = (holder.itemView.context.resources.displayMetrics.density * 42f).toInt()
            val cachedBitmap = DrawableBitmapCache.getOrLoadBitmap(
                holder.itemView.context,
                item.packageName,
                targetIconPx,
                item.icon
            )
            if (cachedBitmap != null && !cachedBitmap.isRecycled) {
                ivAppIcon.setImageBitmap(cachedBitmap)
            } else {
                ivAppIcon.setImageResource(R.mipmap.ic_launcher)
            }

            if (item.foregroundTimeMs > 0L) {
                viewStatusDot.setBackgroundResource(R.drawable.bg_dot_green)
            } else {
                viewStatusDot.setBackgroundResource(R.drawable.bg_dot_blue)
            }

            tvAppName.text = item.appName
            val pwrStr = if (showBackgroundStats) {
                item.getFormattedCombinedAvgWatts()
            } else {
                item.getFormattedForegroundAvgWatts()
            }
            val avgTempStr = if (item.avgTemperature > 0f) {
                String.format(Locale.getDefault(), "%.1f℃", item.avgTemperature)
            } else {
                "--"
            }
            tvAvgInfo.text = "AVG: $pwrStr, $avgTempStr"

            val energyStr = if (showBackgroundStats) {
                item.getFormattedCombinedEnergyWh()
            } else {
                item.getFormattedForegroundEnergyWh()
            }
            val maxTempStr = if (item.maxTemperature > 0f) {
                String.format(Locale.getDefault(), "MAX: %.1f℃", item.maxTemperature)
            } else {
                "MAX: --"
            }
            tvAppEnergy.text = "$maxTempStr | $energyStr"

            val hasFg = item.foregroundTimeMs > 0L
            val hasBg = showBackgroundStats && item.backgroundTimeMs > 0L

            if (hasFg) {
                layoutFgDuration.visibility = View.VISIBLE
                tvFgDuration.text = item.getFormattedDuration()
            } else if (!hasBg) {
                layoutFgDuration.visibility = View.VISIBLE
                tvFgDuration.text = "0s"
            } else {
                layoutFgDuration.visibility = View.GONE
            }

            tvDurationDivider.visibility = if (hasFg && hasBg) View.VISIBLE else View.GONE

            if (hasBg) {
                layoutBgDuration.visibility = View.VISIBLE
                tvBgDuration.text = item.getFormattedBackgroundDuration()
            } else {
                layoutBgDuration.visibility = View.GONE
            }
        }
    }

    // ==================== 页面数据构建与外部接口 ====================

    private var attachedRecyclerView: RecyclerView? = null

    /**
     * 适配器挂载至 RecyclerView 时回调，保存视图引用以支持安全布局状态检查。
     *
     * @param recyclerView 关联的 RecyclerView 实例
     */
    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
    }

    /**
     * 适配器与 RecyclerView 解绑时回调，释放视图引用防止内存泄漏。
     *
     * @param recyclerView 解绑的 RecyclerView 实例
     */
    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        attachedRecyclerView = null
    }

    /**
     * 安全地触发数据集全量刷新通知。
     * 检查关联的 RecyclerView 是否正处于布局计算或滚动中，若是则自动通过 post 延期到下一帧安全执行，
     * 彻底消除 IllegalStateException: Cannot call this method while RecyclerView is computing a layout or scrolling。
     */
    fun safeNotifyDataSetChanged() {
        val rv = attachedRecyclerView
        if (rv != null && (rv.isComputingLayout || rv.isAnimating)) {
            rv.post {
                if (attachedRecyclerView != null) {
                    notifyDataSetChanged()
                }
            }
        } else {
            notifyDataSetChanged()
        }
    }

    /**
     * 是否处于纯应用列表模式。
     * 当作为独立组件被 ConcatAdapter 或其它只展示应用排行榜的页面（如快照详情页）复用时设为 true，
     * 此时 rebuildItems 仅平铺 App 能耗条目，绝不额外注入主页专用的概览卡片或头部操作栏。
     */
    var isPureAppListMode: Boolean = false

    /**
     * 重新构建平铺的 RecyclerView 条目列表并安全通知刷新。
     */
    fun rebuildItems() {
        items.clear()
        if (isPureAppListMode) {
            for (app in displayAppItems) {
                items.add(PowerUsageItem.AppUsage(app))
            }
            safeNotifyDataSetChanged()
            return
        }

        if (showFirstTimeSetup) {
            items.add(PowerUsageItem.FirstTimeSetup)
            safeNotifyDataSetChanged()
            return
        }

        if (currentDisplayTab == 1) {
            items.add(PowerUsageItem.ChargingContent)
            safeNotifyDataSetChanged()
            return
        }

        // 耗电模式
        if (showSnapshotBanner) {
            items.add(PowerUsageItem.SnapshotBanner(snapshotHintText))
        }
        if (showShizukuGuide) {
            items.add(PowerUsageItem.ShizukuGuide(shizukuGuideTitle, shizukuGuideDesc, shizukuGuideActionText))
        }
        items.add(PowerUsageItem.DischargeSpeedMetrics)
        items.add(PowerUsageItem.SleepAwakeMetrics)
        items.add(PowerUsageItem.UsageOverview)
        items.add(PowerUsageItem.SceneHeader(displayAppItems.size, showBackgroundStats))
        for (app in displayAppItems) {
            items.add(PowerUsageItem.AppUsage(app))
        }
        safeNotifyDataSetChanged()
    }

    /**
     * 提交并更新应用功耗原始列表，执行排序与过滤并平滑刷新主列表。
     *
     * @param newItems 最新应用列表
     */
    fun submitList(newItems: List<AppPowerUsageItem>) {
        allAppItems.clear()
        allAppItems.addAll(newItems)
        applyFilterAndSort()
        rebuildItems()
    }

    /**
     * 获取当前过滤排序后的展示应用总数。
     *
     * @return 展示应用数量
     */
    fun getDisplayItemCount(): Int = displayAppItems.size

    /**
     * 设置是否展示后台统计数据。
     *
     * @param show 是否展示后台数据
     */
    fun setShowBackgroundStats(show: Boolean) {
        if (showBackgroundStats != show) {
            showBackgroundStats = show
            applyFilterAndSort()
            rebuildItems()
        }
    }

    /**
     * 切换排序模式。
     *
     * @param mode 0-按时长，1-按功耗，2-按电量，3-按名称
     */
    fun setSortMode(mode: Int) {
        sortMode = mode
        applyFilterAndSort()
        rebuildItems()
    }

    /**
     * 设置当前页面 Tab 模式（0 为耗电，1 为充电）。
     *
     * @param tab 页面模式索引
     */
    fun setDisplayTab(tab: Int) {
        if (currentDisplayTab != tab) {
            currentDisplayTab = tab
            rebuildItems()
        }
    }

    /**
     * 设置首次配置引导显隐与状态。
     *
     * @param visible 是否显示引导
     * @param selectedMode 当前选中模式
     * @param isInstalled Shizuku 是否已安装
     */
    fun setFirstTimeSetup(visible: Boolean, selectedMode: Int = 0, isInstalled: Boolean = true) {
        showFirstTimeSetup = visible
        setupSelectedMode = selectedMode
        isShizukuInstalled = isInstalled
        rebuildItems()
    }

    /**
     * 设置历史快照横幅显隐与提示。
     *
     * @param visible 是否显示
     * @param hint 提示文字
     */
    fun setSnapshotBanner(visible: Boolean, hint: String = "") {
        showSnapshotBanner = visible
        snapshotHintText = hint
        rebuildItems()
    }

    /**
     * 设置 Shizuku 授权引导卡片显隐与文字。
     *
     * @param visible 是否显示
     * @param title 标题
     * @param desc 描述
     * @param actionText 按钮文字
     */
    fun setShizukuGuide(visible: Boolean, title: String = "", desc: String = "", actionText: String = "") {
        showShizukuGuide = visible
        shizukuGuideTitle = title
        shizukuGuideDesc = desc
        shizukuGuideActionText = actionText
        rebuildItems()
    }

    /**
     * 设置普通模式权限提示横幅显隐。
     *
     * @param visible 是否显示权限横幅
     */
    fun setPermissionBannerVisible(visible: Boolean) {
        if (showPermissionBanner != visible) {
            showPermissionBanner = visible
            sceneHeaderHolder?.binding?.layoutPermissionBanner?.visibility =
                if (visible) View.VISIBLE else View.GONE
        }
    }

    /**
     * 更新使用过程概览卡片的指标与时间轴状态。
     *
     * @param energyWh 能量消耗 Wh
     * @param totalEnergyWh 电池总能量 Wh
     * @param lastUnplugWh 拔电能量 Wh
     * @param temp 电池温度
     * @param volt 电池电压
     * @param isCharging 是否处于充电中
     * @param timelineState 时间轴状态实体
     */
    fun updateOverviewMetrics(
        energyWh: Float,
        totalEnergyWh: Float?,
        lastUnplugWh: Float?,
        temp: Float,
        volt: Float,
        isCharging: Boolean,
        timelineState: BatteryTimelineState? = null
    ) {
        cachedEnergyWh = energyWh
        cachedTotalEnergyWh = totalEnergyWh
        cachedLastUnplugWh = lastUnplugWh
        cachedTemperature = temp
        cachedVoltageVolts = volt
        cachedIsCharging = isCharging
        if (timelineState != null) {
            cachedTimelineState = timelineState
        }
        overviewHolder?.let { bindUsageOverview(it) }
    }

    /**
     * 更新时间轴组件状态。
     *
     * @param timelineState 时间轴状态
     */
    fun updateTimelineState(timelineState: BatteryTimelineState) {
        cachedTimelineState = timelineState
        overviewHolder?.binding?.batteryTimelineView?.setState(timelineState)
    }

    /**
     * 更新放电核心指标数据并刷新放电速度卡片视图。
     *
     * @param overviewStats 最新的放电核心指标数据实体
     * @param totalCapacityWh 设备有效满充总能量容量（单位：Wh），若为 null 则尝试复用已缓存容量
     */
    fun updateOverviewStats(overviewStats: PowerOverviewStats?, totalCapacityWh: Float? = null) {
        cachedOverviewStats = overviewStats
        if (totalCapacityWh != null && totalCapacityWh > 0f) {
            cachedTotalEnergyWh = totalCapacityWh
        }
        dischargeSpeedHolder?.let { bindDischargeSpeed(it) }
        sleepAwakeHolder?.let { bindSleepAwakeMetrics(it) }
    }

    /**
     * 获取充电卡片中的内部控件绑定实例，供充电实时数据更新。
     *
     * @return 充电卡片内部绑定对象，若尚未创建则返回 null
     */
    fun getChargingBinding(): LayoutChargingStatsBinding? {
        return chargingHolder?.binding?.layoutChargingContent
    }

    /**
     * 将应用列表状态收起或重置至初始状态。
     * 当前页面架构已切换为原生 RecyclerView 虚拟化与视图复用机制，
     * 屏幕中常驻 ViewHolder 数量自然维持在 8~12 个，此处保留幂等方法供生命周期调用兼容。
     */
    fun collapseToInitial() {
        // 当前架构已通过原生虚拟化复用完全解决内存与 View 膨胀，此方法提供向后兼容
    }

    /**
     * 对应用列表执行过滤与排序。
     */
    private fun applyFilterAndSort() {
        val fgList = allAppItems.filter { it.foregroundTimeMs > 0L }.toMutableList()
        val bgList = if (showBackgroundStats) {
            allAppItems.filter { it.foregroundTimeMs <= 0L }.toMutableList()
        } else {
            mutableListOf()
        }

        when (sortMode) {
            0 -> {
                fgList.sortByDescending { it.foregroundTimeMs }
                bgList.sortByDescending { it.backgroundTimeMs }
            }
            1 -> {
                fgList.sortBy { it.avgPowerWatts }
                bgList.sortBy { if (it.backgroundPowerWatts > 0f) it.backgroundPowerWatts else it.avgPowerWatts }
            }
            2 -> {
                fgList.sortByDescending { it.getForegroundEnergyValue() }
                bgList.sortByDescending { it.backgroundEnergyWh }
            }
            3 -> {
                fgList.sortBy { it.appName }
                bgList.sortBy { it.appName }
            }
        }

        displayAppItems.clear()
        displayAppItems.addAll(fgList)
        displayAppItems.addAll(bgList)
        onListCountChangedListener?.invoke(displayAppItems.size)
    }
}
