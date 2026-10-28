package com.battery.analysis.ui

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.battery.analysis.R
import com.battery.analysis.databinding.ItemAppPowerUsageBinding
import com.battery.analysis.databinding.ItemPowerCapacityOverviewBinding
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
import com.battery.analysis.model.CapacityEstimateResult
import com.battery.analysis.model.CapacitySourceTier
import com.battery.analysis.model.MaturityLevel
import com.battery.analysis.model.PowerDischargePoint
import com.battery.analysis.model.PowerDisplayUnit
import com.battery.analysis.model.PowerUsageItem
import com.battery.analysis.model.SevenDaysDischargeStats
import com.battery.analysis.timeline.presentation.BatteryTimelineState
import com.battery.analysis.timeline.util.DrawableBitmapCache
import com.battery.analysis.util.setUnitText
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.ShapeAppearanceModel
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.core.text.HtmlCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.transition.ChangeBounds
import androidx.transition.Fade
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import com.battery.analysis.util.BatteryEnergyCalculator
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
        /** 视图类型：电池容量储备与自学习核心看板卡片（位于放电速度卡片正上方） */
        const val TYPE_CAPACITY_OVERVIEW = 10

        /** 睡眠卡片点击分类标识：息屏卡片 */
        const val CARD_SCREEN_OFF = 1
        /** 睡眠卡片点击分类标识：唤醒卡片 */
        const val CARD_AWAKE = 2
        /** 睡眠卡片点击分类标识：深度睡眠卡片 */
        const val CARD_DEEP_SLEEP = 3
    }

    // 当前供 RecyclerView 绑定的完整平铺条目数据列表
    private val items = mutableListOf<PowerUsageItem>()

    // 原始完整应用功耗数据集合
    private val allAppItems = mutableListOf<AppPowerUsageItem>()

    // 当前经筛选与排序后供列表渲染呈现的应用数据集合
    private val displayAppItems = mutableListOf<AppPowerUsageItem>()

    // 当前展示应用列表的有效前台总能量缓存（单位：Wh），用于极速计算单应用耗电百分比
    private var cachedTotalAppEnergyWh: Float = 0f

    // 排序模式：0-按使用时长降序，1-按平均功耗升序，2-按消耗电量降序，3-按应用名称升序
    private var sortMode: Int = 0

    /**
     * 当前耗电页面电量呈现单位（瓦时 Wh 或 毫安时 mAh）。
     */
    var currentDisplayUnit: PowerDisplayUnit = PowerDisplayUnit.WH
        private set

    /**
     * 更新全局电量呈现单位，并在单位变更时触发当前可见列表项的全量重新绑定与安全刷新。
     * 同步更新常驻图表看板与核心指标卡片的显示单位。
     *
     * @param newUnit 最新目标电量显示单位 [PowerDisplayUnit]
     */
    fun updateDisplayUnit(newUnit: PowerDisplayUnit) {
        if (currentDisplayUnit != newUnit) {
            currentDisplayUnit = newUnit
            overviewHolder?.binding?.batteryTimelineView?.setDisplayUnit(newUnit)
            chargingHolder?.binding?.layoutChargingContent?.chargingChartView?.setDisplayUnit(newUnit)
            sleepAwakeHolder?.let { bindSleepAwakeMetrics(it) }
            dischargeSpeedHolder?.let { bindDischargeSpeed(it, forceRecalculate = false, animateProgress = false) }
            safeNotifyDataSetChanged()
        }
    }

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
    // 过去 7 天放电统计速度与充满电使用时间最新缓存数据
    private var cachedSevenDaysStats: SevenDaysDischargeStats? = null
    // 当前电池电量百分比与剩余能量
    private var cachedBatteryLevelPercent: Float? = null
    private var cachedRemainingEnergyWh: Float? = null
    // 当前放电会话最近细粒度趋势点集缓存
    private var cachedTrendPoints: List<PowerDischargePoint>? = null
    /** 最近一次经合法刷新（手动下拉或后台超2分钟自动刷新）计算得出的三维度剩余续航结果缓存，杜绝非刷新周期或列表滚动时重复重算跳动 */
    private var cachedTripleRuntimeResult: com.battery.analysis.domain.estimator.TripleRuntimeResult? = null

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

    /**
     * 息屏、唤醒与深度睡眠卡片点击事件监听回调。
     * 参数 1：触发点击的卡片锚点 View
     * 参数 2：卡片分类标识（[CARD_SCREEN_OFF] 为息屏卡片，[CARD_AWAKE] 为唤醒卡片，[CARD_DEEP_SLEEP] 为深度睡眠卡片）
     */
    var onSleepAwakeCardClickedListener: ((View, Int) -> Unit)? = null

    /**
     * 图表长按游标探查状态监听器，向外部提供时间轴移动与释放回调。
     */
    var onCursorInspectListener: com.battery.analysis.timeline.presentation.BatteryTimelineView.OnCursorInspectListener? = null
        set(value) {
            field = value
            overviewHolder?.binding?.batteryTimelineView?.setOnCursorInspectListener(value)
        }

    /**
     * 点击进入全景科学诊断 BottomSheet 的回调监听器。
     */
    var onOpenCapacityDiagnosisListener: (() -> Unit)? = null

    // 当前最新的容量与自学习结果
    private var capacityEstimateResult: CapacityEstimateResult? = null

    // 容量核心卡片是否处于展开态（默认读取 SharedPreferences 偏好）
    private var isCapacityCardExpanded: Boolean? = null

    // 容量核心卡片展开/收缩交互防抖时间戳（单位：毫秒）
    private var lastCapacityToggleTime: Long = 0L

    private var capacityHolder: CapacityOverviewViewHolder? = null

    /**
     * 更新最新的电池容量与自学习状态结果，并安全刷新对应卡片。
     *
     * @param result 最新的容量估计结果实体 [CapacityEstimateResult]
     */
    fun updateCapacityEstimate(result: CapacityEstimateResult?) {
        capacityEstimateResult = result
        capacityHolder?.let { bindCapacityOverview(it) }
    }


    // ==================== ViewHolder 定义 ====================

    /**
     * 电池容量储备与自学习核心卡片 ViewHolder。
     * 支持单行折叠与双轨展开过渡，并在本地持久化用户的展开偏好。
     *
     * @param binding 电池容量卡片视图绑定对象
     */
    inner class CapacityOverviewViewHolder(val binding: ItemPowerCapacityOverviewBinding) :
        RecyclerView.ViewHolder(binding.root)

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
            is PowerUsageItem.CapacityOverview -> TYPE_CAPACITY_OVERVIEW
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
            TYPE_CAPACITY_OVERVIEW -> {
                val binding = ItemPowerCapacityOverviewBinding.inflate(inflater, parent, false)
                CapacityOverviewViewHolder(binding).also { capacityHolder = it }
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
                val existing = overviewHolder
                if (existing != null) {
                    (existing.itemView.parent as? ViewGroup)?.removeView(existing.itemView)
                    existing
                } else {
                    val binding = ItemPowerUsageOverviewBinding.inflate(inflater, parent, false)
                    UsageOverviewViewHolder(binding).also {
                        overviewHolder = it
                        it.binding.metricSelectorView.setOnMetricsChangedListener { metrics ->
                            cachedTimelineState = cachedTimelineState?.copy(selectedMetrics = metrics)
                            it.binding.batteryTimelineView.setSelectedMetrics(metrics)
                            onMetricsChangedListener?.invoke(metrics)
                        }
                        it.binding.batteryTimelineView.setOnEnergyClickListener { view, x, y ->
                            onEnergyClickListener?.invoke(view, x, y)
                        }
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
            is PowerUsageItem.CapacityOverview -> {
                val capHolder = holder as CapacityOverviewViewHolder
                capacityHolder = capHolder
                bindCapacityOverview(capHolder)
            }
            is PowerUsageItem.DischargeSpeedMetrics -> {
                val speedHolder = holder as DischargeSpeedViewHolder
                dischargeSpeedHolder = speedHolder
                bindDischargeSpeed(speedHolder, forceRecalculate = false, animateProgress = false)
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
     * 绑定电池容量储备与自学习核心卡片视图（方案 A：可折叠双轨仪表盘风格）。
     * 支持单行极简速览与双轨详情展开过渡，自动从本地配置还原用户的折叠偏好。
     * 严格遵循物理数据实测原则，无数据时呈现待校准与未就绪状态。
     *
     * @param holder 容量核心看板 ViewHolder [CapacityOverviewViewHolder]
     */
    private fun bindCapacityOverview(holder: CapacityOverviewViewHolder) {
        val binding = holder.binding
        val context = binding.root.context

        // 1. 初始化或恢复展开状态偏好（默认折叠为单行极简模式）
        if (isCapacityCardExpanded == null) {
            val sp = context.getSharedPreferences("capacity_prefs", Context.MODE_PRIVATE)
            isCapacityCardExpanded = sp.getBoolean("pref_capacity_card_expanded", false)
        }
        val isExpanded = isCapacityCardExpanded ?: false
        updateCapacityCardExpandState(holder, isExpanded, animate = false)

        // 2. 绑定点击切换展开/折叠交互事件（同时支持点击速览栏与卡片主体空白区，具备 300ms 防抖保护）
        val toggleListener = View.OnClickListener {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastCapacityToggleTime < 300L) {
                return@OnClickListener
            }
            lastCapacityToggleTime = now

            val nextState = !(isCapacityCardExpanded ?: false)
            isCapacityCardExpanded = nextState
            val sp = context.getSharedPreferences("capacity_prefs", Context.MODE_PRIVATE)
            sp.edit().putBoolean("pref_capacity_card_expanded", nextState).apply()
            updateCapacityCardExpandState(holder, nextState, animate = true)
        }
        binding.layoutCollapsedHeader.setOnClickListener(toggleListener)
        binding.root.setOnClickListener(toggleListener)

        // 3. 绑定全景科学诊断 BottomSheet 弹窗入口
        binding.btnOpenDiagnosis.setOnClickListener {
            onOpenCapacityDiagnosisListener?.invoke()
        }

        // 4. 数据绑定：严格遵循无假数据原则
        val result = capacityEstimateResult
        val prefix = "预估满电容量: "
        val learningColor = Color.parseColor("#f59e0b") // 醒目的琥珀橙色，用于明确区分自学习中状态

        if (result == null) {
            // 未就绪/正在初始化状态
            val fullText = "${prefix}学习中"
            val spannable = SpannableString(fullText).apply {
                setSpan(
                    ForegroundColorSpan(learningColor),
                    prefix.length,
                    fullText.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            binding.tvCollapsedCharge.text = spannable
            binding.tvCollapsedEnergy.visibility = View.GONE
            binding.tvCollapsedBadge.text = "初始化中"
            binding.tvCollapsedBadge.setTextColor(Color.parseColor("#64748b"))
            setRoundedBadgeBackground(binding.tvCollapsedBadge, Color.parseColor("#64748b"), 6f)

            binding.tvValRemainingCharge.setUnitText("-- mAh")
            binding.tvSubRemainingCharge.text = "等待充电数据"
            binding.tvValRemainingEnergy.setUnitText("-- Wh")
            binding.tvSubRemainingEnergy.text = "标称工作平台"
            binding.tvValFccBaseline.setUnitText("-- mAh")
            binding.tvValFccBadge.text = "未就绪"
            binding.tvValFccBadge.setTextColor(Color.parseColor("#64748b"))
            setRoundedBadgeBackground(binding.tvValFccBadge, Color.parseColor("#64748b"), 5f)
            return
        }

        // 首行速览数据渲染：按“预估满电容量：满电容量 | 满电能量（学习中则加颜色区分）”标准定制，且单位比数值小 2 号
        val isLearning = (result.maturityLevel == MaturityLevel.PENDING)
        if (isLearning) {
            val fullText = "${prefix}学习中"
            val spannable = SpannableString(fullText).apply {
                setSpan(
                    ForegroundColorSpan(learningColor),
                    prefix.length,
                    fullText.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            binding.tvCollapsedCharge.text = spannable
        } else {
            val capacityText = "$prefix${result.getFormattedFcc()} | ${result.getFormattedFullEnergy()}"
            binding.tvCollapsedCharge.setUnitText(capacityText)
        }
        binding.tvCollapsedEnergy.visibility = View.GONE

        // 状态徽章与颜色渲染（设计容量缺失时智能自适应以 FCC 为基准；首行极简单行呈现，去除冗余“· 观察”后缀）
        val (badgeText, badgeColor) = when (result.maturityLevel) {
            MaturityLevel.STABLE_TRACKING,
            MaturityLevel.CONVERGED -> {
                val label = if (result.sohPercent != null) "SOH ${result.getFormattedSoh()}" else "FCC ${result.getFormattedFcc()}"
                label to Color.parseColor("#10b981")
            }
            MaturityLevel.INITIAL_OBSERVATION -> {
                val label = if (result.sohPercent != null) "SOH ${result.getFormattedSoh()}" else "FCC ${result.getFormattedFcc()}"
                label to Color.parseColor("#f59e0b")
            }
            MaturityLevel.PENDING -> {
                "自学习中(${result.validSessionCount}/5)" to Color.parseColor("#64748b")
            }
        }
        binding.tvCollapsedBadge.text = badgeText
        binding.tvCollapsedBadge.setTextColor(badgeColor)
        setRoundedBadgeBackground(binding.tvCollapsedBadge, badgeColor, 6f)

        // 展开区双轨数据渲染：单位统一比数值小 2 号
        binding.tvValRemainingCharge.setUnitText(result.getFormattedRemainingCharge())
        binding.tvSubRemainingCharge.text = if (result.currentSoc != null) {
            "物理电荷 · ${result.currentSoc}%"
        } else {
            "物理电荷"
        }

        binding.tvValRemainingEnergy.setUnitText(result.getFormattedRemainingEnergy())
        binding.tvSubRemainingEnergy.text = "标称电压物理能量"

        // 满充基准与诊断标识
        binding.tvValFccBaseline.setUnitText(result.getFormattedFcc())
        binding.tvValFccBadge.text = when (result.maturityLevel) {
            MaturityLevel.STABLE_TRACKING,
            MaturityLevel.CONVERGED -> "🛡️ 收敛"
            MaturityLevel.INITIAL_OBSERVATION -> "⏳ 观察"
            MaturityLevel.PENDING -> "🌱 自学习中"
        }
        binding.tvValFccBadge.setTextColor(badgeColor)
        setRoundedBadgeBackground(binding.tvValFccBadge, badgeColor, 5f)
    }

    /**
     * 为文本徽章动态配置带圆角的半透明胶囊背景。
     * 解决原生调用 [View.setBackgroundColor] 会冲掉 XML 定义的圆角、退化为直角矩形的问题。
     *
     * @param view 目标徽章文本视图
     * @param textColor 徽章前景主题色
     * @param cornerRadiusDp 圆角半径（单位：dp，默认为 6dp）
     */
    private fun setRoundedBadgeBackground(view: TextView, textColor: Int, cornerRadiusDp: Float = 6f) {
        val density = view.resources.displayMetrics.density
        val alphaColor = Color.argb(32, Color.red(textColor), Color.green(textColor), Color.blue(textColor))
        val drawable = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(alphaColor)
            setCornerRadius(cornerRadiusDp * density)
        }
        view.background = drawable
    }

    /**
     * 更新容量卡片的折叠/展开外观状态。
     * 具备与列表下方卡片（如放电速度卡片等）严丝合缝的同轨联动动画：
     * 1. 将场景根节点锚定至父级 [RecyclerView]，通过 [ChangeBounds] 同步驱动当前卡片尺寸收缩与下方所有卡片平滑上浮/下沉；
     * 2. 采用 [TransitionSet.ORDERING_TOGETHER] 并发编排，彻底消除分步执行导致的迟滞与下部卡片突变脱节；
     * 3. 针对展开内容采用独立渐变控制，收缩时快速隐退（120ms）防止截断破绽，展开时优雅淡入（240ms）；
     * 4. 同步执行指示箭头 0 度与 180 度平滑属性旋转。
     *
     * @param holder 容量核心看板 ViewHolder [CapacityOverviewViewHolder]
     * @param isExpanded 当前目标展开状态，true 为双轨展开态，false 为单行速览态
     * @param animate 是否执行平滑过渡动画
     */
    private fun updateCapacityCardExpandState(
        holder: CapacityOverviewViewHolder,
        isExpanded: Boolean,
        animate: Boolean
    ) {
        val binding = holder.binding
        if (animate) {
            // 将过渡动画的根节点锚定至包裹所有卡片的父级 RecyclerView（若异常则兜底至 binding.root）
            // 确保当前卡片的高度变换与下方放电速度三卡片等兄弟视图的位置位移同轨同步渲染
            val sceneRoot = (holder.itemView.parent as? ViewGroup) ?: (binding.root as ViewGroup)
            val interpolator = FastOutSlowInInterpolator()

            val transitionSet = TransitionSet().apply {
                ordering = TransitionSet.ORDERING_TOGETHER
                // 1. 物理位置与尺寸变换：本卡片收缩与下方所有卡片平移严格以 280ms 同步缓动
                addTransition(ChangeBounds().apply {
                    duration = 280L
                    this.interpolator = interpolator
                })
                // 2. 仅对展开区域执行淡隐淡出，严禁影响下方其他卡片导致半透明闪烁
                // 收缩时 120ms 快速淡出，避免卡片收拢时内部文本被下边框截断挤压；展开时 240ms 优雅淡入
                addTransition(Fade().apply {
                    duration = if (isExpanded) 240L else 120L
                    this.interpolator = interpolator
                    addTarget(binding.layoutExpandedContent)
                })
            }
            TransitionManager.beginDelayedTransition(sceneRoot, transitionSet)
        }

        binding.layoutExpandedContent.visibility = if (isExpanded) View.VISIBLE else View.GONE
        val targetRotation = if (isExpanded) 180f else 0f
        if (animate) {
            binding.ivExpandArrow.animate()
                .rotation(targetRotation)
                .setDuration(280L)
                .setInterpolator(FastOutSlowInInterpolator())
                .start()
        } else {
            binding.ivExpandArrow.rotation = targetRotation
        }
    }

    /**
     * 绑定放电速度核心概览三卡片（呈现亮屏、息屏、全局放电速度三个独立卡片，包含实时放电速率、7天统计放电速度与满电使用时长）。
     * 遵循下拉刷新机制严格对齐原则：默认消费已缓存的 [cachedTripleRuntimeResult]；
     * 仅当强制要求重算 [forceRecalculate] 或首次尚无缓存时，才执行物理级剩余续航模型测算并刷新缓存。
     *
     * @param holder 放电速度概览卡片 ViewHolder
     * @param forceRecalculate 是否强制重新计算剩余续航时间（在全量下拉刷新触发时传入 true）
     * @param animateProgress 是否启用进度条平滑动画（仅在主动数据刷新就绪时为 true，列表滚动复用时为 false）
     */
    private fun bindDischargeSpeed(
        holder: DischargeSpeedViewHolder,
        forceRecalculate: Boolean = false,
        animateProgress: Boolean = false
    ) {
        val precalculated = if (forceRecalculate) null else cachedTripleRuntimeResult
        val result = PowerDischargeMetricsViewBinder.bindDischargeSpeed(
            binding = holder.binding,
            overview = cachedOverviewStats,
            totalCapacityWh = cachedTotalEnergyWh,
            sevenDaysStats = cachedSevenDaysStats,
            batteryLevelPercent = cachedBatteryLevelPercent,
            remainingEnergyWh = cachedRemainingEnergyWh,
            tempCelsius = cachedTemperature.takeIf { it != 0f },
            currentTrendPoints = cachedTrendPoints,
            precalculatedRuntimeResult = precalculated,
            animateProgress = animateProgress,
            onCardClick = { anchorView, rowType ->
                onMetricModuleClickedListener?.invoke(anchorView, rowType)
            }
        )
        cachedTripleRuntimeResult = result
    }

    /**
     * 绑定息屏、唤醒与深度睡眠三状态指标卡片数据。
     * 委托给 [PowerDischargeMetricsViewBinder.bindSleepAwakeMetrics]，
     * 严格根据当前所选电量单位 [currentDisplayUnit] 动态调整第二行（时长与主指标）与第三行（对照副指标与功率）的主次排版与数值呈现。
     *
     * @param holder 息屏细分状态卡片 ViewHolder
     */
    private fun bindSleepAwakeMetrics(holder: SleepAwakeViewHolder) {
        PowerDischargeMetricsViewBinder.bindSleepAwakeMetrics(
            binding = holder.binding,
            overview = cachedOverviewStats,
            unit = currentDisplayUnit,
            onCardClick = { anchorView, cardType ->
                onSleepAwakeCardClickedListener?.invoke(anchorView, cardType)
            }
        )
    }

    /**
     * 将满电使用时间物理毫秒数格式化为紧凑易读的时长文本。
     * 当持续时间大于等于 1 天时输出为 "XdXXh" 格式（如 5d05h）；
     * 当持续时间大于等于 1 小时时输出为 "XhXXm" 格式（如 19h14m）；
     * 否则输出为 "Xm" 格式（如 45m）。
     *
     * @param ms 充满电可用物理时长毫秒数
     * @return 格式化后的紧凑时长字符串，数据无效时返回 "--"
     */
    private fun formatFullDurationText(ms: Long): String {
        if (ms <= 0L) return "--"
        val totalSec = ms / 1000L
        val days = totalSec / 86400L
        val hours = (totalSec % 86400L) / 3600L
        val minutes = (totalSec % 3600L) / 60L
        return when {
            days > 0L -> String.format(Locale.getDefault(), "%02dd%02dh", days, hours)
            hours > 0L -> String.format(Locale.getDefault(), "%02dh%02dm", hours, minutes)
            else -> String.format(Locale.getDefault(), "%02dm", minutes)
        }
    }

    /**
     * 将 7 天滑动窗口内实际有效统计时长（毫秒）格式化为紧凑时长文本。
     * 有多少显示多少，最长不超过 7 天。
     * 当持续时间大于等于 7 天时显示为 "7d"；
     * 当持续时间大于等于 1 天时显示为 "XdXXh" 格式（如 01d08h、03d12h）；
     * 当持续时间大于等于 1 小时时显示为 "XhXXm" 格式（如 04h30m、18h20m）；
     * 否则显示为 "Xm" 格式（如 25m、05m）。
     *
     * @param ms 实际有效统计时长物理毫秒数
     * @return 格式化后的紧凑时长字符串，数据无效时返回空字符串
     */
    private fun formatActualStatsDurationText(ms: Long?): String {
        if (ms == null || ms <= 0L) return ""
        val totalSec = ms / 1000L
        val days = totalSec / 86400L
        val hours = (totalSec % 86400L) / 3600L
        val minutes = (totalSec % 3600L) / 60L
        return when {
            days >= 7L -> "7d"
            days > 0L -> String.format(Locale.getDefault(), "%02dd%02dh", days, hours)
            hours > 0L -> String.format(Locale.getDefault(), "%02dh%02dm", hours, minutes)
            else -> String.format(Locale.getDefault(), "%02dm", minutes.coerceAtLeast(1L))
        }
    }

    /**
     * 将实际统计放电速度与有效时长组合格式化为复合富文本。
     * 放电百分比数值与单位的文字样式大小完全对齐第一行放电速度百分比（数值 16sp 加粗高亮突出，"%/h" 单位 11sp 加粗微缩），
     * 实际有效统计时长紧随其后（如 "5.2%/h 1d08h"），采用次要文本颜色与紧凑字号（10sp 常规字重）呈现。
     * 若数据缺失则展示 "--"。
     *
     * @param context Android 上下文对象，用于解析系统主题次要文字颜色
     * @param rate 统计放电速率百分比（单位：%/h）
     * @param durationMs 7 天窗口内实际有效统计时长（单位：毫秒）
     * @return 格式化后的富文本对象 [CharSequence]
     */
    private fun formatSecondaryDischargeRateSpannable(
        context: Context,
        rate: Float?,
        durationMs: Long?
    ): CharSequence {
        if (rate == null || rate <= 0f) {
            return "--"
        }
        val numberStr = String.format(Locale.getDefault(), "%.1f", rate)
        val unitStr = "%/h"
        val durStr = formatActualStatsDurationText(durationMs)
        val fullText = if (durStr.isNotBlank()) "$numberStr$unitStr $durStr" else "$numberStr$unitStr"
        val spannable = SpannableString(fullText)

        // "%/h" 单位字号微缩至 11sp，与第一行放电速度单位样式及大小（11sp bold）完全一致
        spannable.setSpan(
            AbsoluteSizeSpan(11, true),
            numberStr.length,
            numberStr.length + unitStr.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        // 时长部分保持次要紧凑字号（10sp）、常规字重与主题次要文本颜色
        if (durStr.isNotBlank()) {
            val durStart = numberStr.length + unitStr.length + 1
            val typedArray = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary))
            val secondaryColor = typedArray.getColor(0, 0x8A000000.toInt())
            typedArray.recycle()

            spannable.setSpan(
                AbsoluteSizeSpan(10, true),
                durStart,
                fullText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannable.setSpan(
                StyleSpan(Typeface.NORMAL),
                durStart,
                fullText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannable.setSpan(
                ForegroundColorSpan(secondaryColor),
                durStart,
                fullText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return spannable
    }

    /**
     * 将充满电可用时长与满电说明标签组合格式化为紧凑富文本。
     * 时长数值位于前面（如 "19h14m"），满电说明标签位于后面（如 "19h14m 满电"）。
     * 若时长无效或数据缺失则展示 "--"。
     *
     * @param context Android 上下文对象
     * @param ms 充满电可用物理时长毫秒数
     * @return 格式化后的富文本对象 [CharSequence]
     */
    private fun formatFullDurationTextWithSuffix(context: Context, ms: Long?): CharSequence {
        if (ms == null || ms <= 0L) {
            return "--"
        }
        val durText = formatFullDurationText(ms)
        if (durText == "--") return "--"
        val label = context.getString(R.string.power_discharge_full_label)
        val fullText = "$durText $label"
        val spannable = SpannableString(fullText)

        // 时长数值加粗突出
        spannable.setSpan(
            android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
            0,
            durText.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        // 满电标签字号微缩至 10sp
        val labelStart = durText.length + 1
        spannable.setSpan(
            AbsoluteSizeSpan(10, true),
            labelStart,
            fullText.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return spannable
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
    fun calculateDischargeRatePercentPerHour(
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
            days > 0L -> String.format(Locale.getDefault(), "%02dd%02dh", days, hours)
            hours > 0L -> String.format(Locale.getDefault(), "%02dh%02dm", hours, minutes)
            else -> String.format(Locale.getDefault(), "%02dm%02ds", minutes, seconds)
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
     * 同步恢复指标选择器当前的实际选中状态，杜绝列表上下滑动复用时指标状态被旧缓存重置。
     * 在主列表上下滑动的过程中，绝对不重载、重绘或刷新图表数据，直接复用既有视图。
     *
     * @param holder 概览卡片 ViewHolder
     */
    private fun bindUsageOverview(holder: UsageOverviewViewHolder) {
        overviewHolder = holder
        with(holder.binding) {
            batteryTimelineView.setDisplayUnit(currentDisplayUnit)
            batteryTimelineView.setOnCursorInspectListener(onCursorInspectListener)
            val currentSelected = metricSelectorView.getSelectedMetrics()
            cachedTimelineState?.let { state ->
                val syncState = state.copy(selectedMetrics = currentSelected, displayUnit = currentDisplayUnit)
                cachedTimelineState = syncState
                batteryTimelineView.setState(syncState)
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
            } else if (item.isUninstalledApp()) {
                // 已卸载应用且磁盘尚未命中原生图标时，展示专属已卸载矢量图标，杜绝误展示为本应用自身图标
                ivAppIcon.setImageResource(R.drawable.ic_uninstalled_app)
            } else {
                // 未命中真实应用图标时，展示专属极简默认应用矢量图标，杜绝误展示为桌面启动图标
                ivAppIcon.setImageResource(R.drawable.ic_default_app)
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
            val maxTempStr = if (item.maxTemperature > 0f) {
                String.format(Locale.getDefault(), "Max:%.1f℃", item.maxTemperature)
            } else {
                "Max:--"
            }
            tvAvgInfo.setUnitText("Avg:$pwrStr, $avgTempStr | $maxTempStr")

            val effectiveTotalEnergy = if (cachedTotalAppEnergyWh > 0f) {
                cachedTotalAppEnergyWh
            } else {
                displayAppItems.sumOf { it.getForegroundEnergyValue().toDouble() }.toFloat()
            }
            tvAppEnergy.setUnitText(formatAppEnergyWithPercentage(item, effectiveTotalEnergy, currentDisplayUnit))

            val hasFg = item.foregroundTimeMs > 0L
            val hasBg = showBackgroundStats && item.backgroundTimeMs > 0L

            if (hasFg) {
                layoutFgDuration.visibility = View.VISIBLE
                tvFgDuration.setUnitText(item.getFormattedDuration())
            } else if (!hasBg) {
                layoutFgDuration.visibility = View.VISIBLE
                tvFgDuration.setUnitText("00s")
            } else {
                layoutFgDuration.visibility = View.GONE
            }

            tvDurationDivider.visibility = if (hasFg && hasBg) View.VISIBLE else View.GONE

            if (hasBg) {
                layoutBgDuration.visibility = View.VISIBLE
                tvBgDuration.setUnitText(item.getFormattedBackgroundDuration())
            } else {
                layoutBgDuration.visibility = View.GONE
            }
        }
    }

    /**
     * 格式化应用消耗能量与总能耗百分占比展示文本（如 "0.088Wh · 51.5%" 或 "--"）。
     * 严格遵循物理守恒与真实数据原则，以中间点符号 " · " 分割，百分比精确到一位小数；
     * 若应用无有效放电能量消耗或数据缺失，则诚实展示未知状态 "--"，绝不捏造虚假占位数据。
     *
     * @param item 待计算的目标应用程序实体 [AppPowerUsageItem]
     * @param totalEnergyWh 当前所有展示应用的有效总放电能量（单位：Wh）
     * @param displayUnit 目标电量显示单位 [PowerDisplayUnit]
     * @return 格式化后的能量及百分比组合展示文本
     */
    fun formatAppEnergyWithPercentage(
        item: AppPowerUsageItem,
        totalEnergyWh: Float,
        displayUnit: PowerDisplayUnit
    ): String {
        val energyStr = item.getFormattedDisplayEnergy(displayUnit)
        val fgEnergy = item.getForegroundEnergyValue()
        val percentText = if (fgEnergy > 0f && totalEnergyWh > 0f) {
            val ratio = (fgEnergy / totalEnergyWh * 100f).coerceIn(0f, 100f)
            String.format(Locale.getDefault(), "%.1f%%", ratio)
        } else if (energyStr != "--") {
            "0.0%"
        } else {
            null
        }
        return if (percentText != null) {
            "$energyStr · $percentText"
        } else {
            "--"
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
                    try {
                        notifyDataSetChanged()
                    } catch (_: Exception) {}
                }
            }
        } else {
            try {
                notifyDataSetChanged()
            } catch (_: Exception) {}
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
        items.add(PowerUsageItem.CapacityOverview)
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
            val currentSelected = overviewHolder?.binding?.metricSelectorView?.getSelectedMetrics()
                ?: cachedTimelineState?.selectedMetrics
                ?: timelineState.selectedMetrics
            cachedTimelineState = timelineState.copy(selectedMetrics = currentSelected, displayUnit = currentDisplayUnit)
        }
        overviewHolder?.let { bindUsageOverview(it) }
    }

    /**
     * 更新适配器缓存的指标集合，防止列表滑动重建时指标状态被旧缓存重置。
     *
     * @param metrics 最新选中的指标集合 [Set<com.battery.analysis.timeline.presentation.TimelineMetric>]
     */
    fun updateCachedMetrics(metrics: Set<com.battery.analysis.timeline.presentation.TimelineMetric>) {
        cachedTimelineState = cachedTimelineState?.copy(selectedMetrics = metrics)
    }

    /**
     * 获取当前选中的指标集合。
     * 优先从当前挂载的 Overview 视图中获取，其次从缓存的时间轴状态中获取，若均无则从本地持久化中加载。
     * 严禁无脑 fallback 到全选指标，确保用户反选状态绝对忠实保留。
     *
     * @param context Android 上下文环境
     * @return 当前选中的指标集合 [Set<com.battery.analysis.timeline.presentation.TimelineMetric>]
     */
    fun getSelectedMetrics(context: Context): Set<com.battery.analysis.timeline.presentation.TimelineMetric> {
        val current = overviewHolder?.binding?.metricSelectorView?.getSelectedMetrics()
        if (current != null && current.isNotEmpty()) {
            return current
        }
        val cached = cachedTimelineState?.selectedMetrics
        if (cached != null && cached.isNotEmpty()) {
            return cached
        }
        val sp = context.getSharedPreferences("timeline_metrics_prefs", Context.MODE_PRIVATE)
        val saved = sp.getStringSet("saved_timeline_metrics", null)
        if (saved != null && saved.isNotEmpty()) {
            val result = mutableSetOf<com.battery.analysis.timeline.presentation.TimelineMetric>()
            for (name in saved) {
                try {
                    result.add(com.battery.analysis.timeline.presentation.TimelineMetric.valueOf(name))
                } catch (_: Exception) {}
            }
            if (result.isNotEmpty()) {
                return result
            }
        }
        return setOf(
            com.battery.analysis.timeline.presentation.TimelineMetric.POWER,
            com.battery.analysis.timeline.presentation.TimelineMetric.APP
        )
    }

    /** 列表是否处于上下滑动中 */
    private var isListScrolling: Boolean = false

    /**
     * 设置当前主列表是否正处于上下滑动的过程中。
     * 滑动期间完全冻结图表视图的任何重绘、重载与数据刷新，消除滑动经过图表时的掉帧卡顿。
     *
     * @param scrolling 是否正在上下滑动
     */
    fun setScrolling(scrolling: Boolean) {
        if (isListScrolling == scrolling) return
        isListScrolling = scrolling
        overviewHolder?.binding?.batteryTimelineView?.setScrolling(scrolling)
    }

    /**
     * 更新时间轴组件状态。
     *
     * @param timelineState 时间轴状态
     */
    fun updateTimelineState(timelineState: BatteryTimelineState) {
        val currentSelected = overviewHolder?.binding?.metricSelectorView?.getSelectedMetrics()
            ?: cachedTimelineState?.selectedMetrics
            ?: timelineState.selectedMetrics
        val syncState = timelineState.copy(selectedMetrics = currentSelected, displayUnit = currentDisplayUnit)
        cachedTimelineState = syncState
        overviewHolder?.binding?.batteryTimelineView?.setDisplayUnit(currentDisplayUnit)
        overviewHolder?.binding?.batteryTimelineView?.setState(syncState)
    }

    /**
     * 更新放电核心指标数据并刷新放电速度卡片与睡眠卡片视图。
     *
     * @param overviewStats 最新的放电核心指标数据实体
     * @param totalCapacityWh 设备有效满充总能量容量（单位：Wh），若为 null 则尝试复用已缓存容量
     * @param sevenDaysStats 过去 7 天核心放电统计指标实体（可选），若传入则一并更新
     * @param batteryLevelPercent 当前电池电量百分比（可选）
     * @param remainingEnergyWh 当前电池剩余能量（Wh，可选）
     * @param tempCelsius 当前电池温度（单位：℃，可选）
     * @param trendPoints 当前周期的细粒度趋势采样点集合（可选）
     * @param forceRecalculateRuntime 是否同步强制重算并刷新剩余续航预测结果缓存（在全量下拉刷新触发时传入 true）
     */
    fun updateOverviewStats(
        overviewStats: PowerOverviewStats?,
        totalCapacityWh: Float? = null,
        sevenDaysStats: SevenDaysDischargeStats? = null,
        batteryLevelPercent: Float? = null,
        remainingEnergyWh: Float? = null,
        tempCelsius: Float? = null,
        trendPoints: List<PowerDischargePoint>? = null,
        forceRecalculateRuntime: Boolean = false
    ) {
        cachedOverviewStats = overviewStats
        if (totalCapacityWh != null && totalCapacityWh > 0f) {
            cachedTotalEnergyWh = totalCapacityWh
        }
        if (sevenDaysStats != null) {
            cachedSevenDaysStats = sevenDaysStats
        }
        if (batteryLevelPercent != null && batteryLevelPercent > 0f) {
            cachedBatteryLevelPercent = batteryLevelPercent
        }
        if (remainingEnergyWh != null && remainingEnergyWh > 0f) {
            cachedRemainingEnergyWh = remainingEnergyWh
        }
        if (tempCelsius != null && tempCelsius != 0f) {
            cachedTemperature = tempCelsius
        }
        if (trendPoints != null) {
            cachedTrendPoints = trendPoints
        }
        val shouldAnimate = !forceRecalculateRuntime && cachedSevenDaysStats != null
        dischargeSpeedHolder?.let { bindDischargeSpeed(it, forceRecalculate = forceRecalculateRuntime, animateProgress = shouldAnimate) }
        sleepAwakeHolder?.let { bindSleepAwakeMetrics(it) }
    }

    /**
     * 更新过去 7 天核心放电统计指标与充满电可用时长，并定向刷新放电速度卡片。
     *
     * @param stats 7 天核心放电统计实体 [SevenDaysDischargeStats]，可为 null
     * @param forceRecalculateRuntime 是否同步强制重算剩余续航预测结果缓存（全量下拉刷新完成时为 true）
     */
    fun updateSevenDaysStats(stats: SevenDaysDischargeStats?, forceRecalculateRuntime: Boolean = false) {
        cachedSevenDaysStats = stats
        dischargeSpeedHolder?.let { bindDischargeSpeed(it, forceRecalculate = forceRecalculateRuntime, animateProgress = true) }
    }

    /**
     * 获取当前适配器内缓存的过去 7 天核心放电统计实体。
     *
     * @return 缓存的 [SevenDaysDischargeStats] 实例，若尚未统计或数据为空则返回 null
     */
    fun getCachedSevenDaysStats(): SevenDaysDischargeStats? = cachedSevenDaysStats

    /**
     * 获取当前适配器内缓存的三维度剩余续航预测结果实体。
     *
     * @return 缓存的 [com.battery.analysis.domain.estimator.TripleRuntimeResult] 实例，若尚未估算或数据为空则返回 null
     */
    fun getCachedTripleRuntimeResult(): com.battery.analysis.domain.estimator.TripleRuntimeResult? = cachedTripleRuntimeResult

    /**
     * 显式更新并持久同步三维度剩余续航预测结果实体缓存，并安全刷新可见卡片。
     *
     * @param result 最新的 [com.battery.analysis.domain.estimator.TripleRuntimeResult] 实体，或传 null 进行清除
     */
    fun updateTripleRuntimeResult(result: com.battery.analysis.domain.estimator.TripleRuntimeResult?) {
        cachedTripleRuntimeResult = result
        dischargeSpeedHolder?.let { bindDischargeSpeed(it, forceRecalculate = false, animateProgress = true) }
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
        val fgList = allAppItems.filter { it.foregroundTimeMs > 0L || (it.isUninstalledApp() && it.hasEffectiveEnergy()) }.toMutableList()
        val bgList = if (showBackgroundStats) {
            allAppItems.filter { it.foregroundTimeMs <= 0L && !it.isUninstalledApp() }.toMutableList()
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
        cachedTotalAppEnergyWh = displayAppItems.sumOf { it.getForegroundEnergyValue().toDouble() }.toFloat()
        onListCountChangedListener?.invoke(displayAppItems.size)
    }

    /**
     * 响应时间轴游标滑动事件，以极轻量无重排方式高频局部刷新放电速度与息屏睡眠卡片。
     * 仅执行文本级直接更新，不调用 notifyDataSetChanged，零 GC、零测量抖动。
     * 支持根据当前所选电量单位 [currentDisplayUnit] 自适应切换息屏、唤醒与深度睡眠卡片的第一行主显单位。
     *
     * @param subStats 阶段子区间累计统计快照对象
     */
    fun updateCursorInspectionStats(subStats: com.battery.analysis.timeline.util.SubIntervalOverviewStats) {
        val speedHolder = dischargeSpeedHolder
        val sleepHolder = sleepAwakeHolder
        if (speedHolder != null && sleepHolder != null) {
            PowerDischargeMetricsViewBinder.updateCursorInspectionStats(
                speedBinding = speedHolder.binding,
                sleepBinding = sleepHolder.binding,
                subStats = subStats,
                unit = currentDisplayUnit
            )
        }
    }

    /**
     * 当游标释放或探查结束时，平滑还原放电速度概览卡片与息屏睡眠卡片的全周期常规统计数据。
     */
    fun restoreOverviewAndSleepStats() {
        dischargeSpeedHolder?.let { bindDischargeSpeed(it, forceRecalculate = false, animateProgress = false) }
        sleepAwakeHolder?.let { bindSleepAwakeMetrics(it) }
    }
}
