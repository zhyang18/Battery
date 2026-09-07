package com.battery.analysis.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.BatteryManager
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.battery.analysis.MainActivity
import com.battery.analysis.R
import com.battery.analysis.databinding.FragmentPowerUsageBinding
import com.battery.analysis.manager.ChargingStatsManager
import com.battery.analysis.manager.FullPowerDataPackage
import com.battery.analysis.manager.PowerUsageManager
import com.battery.analysis.model.ChargingSamplePoint
import com.battery.analysis.model.ChargingSessionSummary
import com.battery.analysis.ui.view.ChargingChartView
import com.battery.analysis.timeline.presentation.AppEnergyDetailBottomSheetDialog
import com.battery.analysis.timeline.presentation.TimelineMetric
import android.os.PowerManager
import com.battery.analysis.provider.NormalApiProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 电池统计顶级页签 Fragment。
 * 智能自动检测耗电与充电状态，自适应展示【耗电统计】与【充电统计】双模界面。
 * 在充电状态下，实时采集并更新功率、电量、温度三合一图表及方块图例标识；
 * 支持在设置中动态调整检测模式，并通过 onResume 自动响应最新配置。
 */
class PowerUsageFragment : Fragment() {

    private var _binding: FragmentPowerUsageBinding? = null
    private val binding get() = _binding!!

    private lateinit var powerManager: PowerUsageManager
    private lateinit var chargingManager: ChargingStatsManager
    private val adapter = AppPowerUsageAdapter()

    // 当前展示界面模式：0 为耗电统计，1 为充电统计
    private var currentDisplayTab: Int = 0

    // 充电数据实时采样轮询后台协程
    private var chargingPollingJob: Job? = null

    // 0: 按时长, 1: 按功耗, 2: 按名称
    private var currentSortIndex = 0

    // 当前活跃的工作模式：MODE_SHIZUKU 或 MODE_NORMAL
    private var currentMode: Int = PowerUsageManager.MODE_SHIZUKU

    // 首次引导选择时暂存的模式状态
    private var tempSelectedSetupMode: Int = PowerUsageManager.MODE_SHIZUKU

    // 历史快照查看模式状态
    private var isViewingSnapshot: Boolean = false
    private var currentLoadedSnapshotTime: String? = null

    // 当前界面呈现的完整耗电数据包缓存
    private var lastRenderedPackage: FullPowerDataPackage? = null

    private val SHIZUKU_POWER_REQUEST_CODE = 2001

    /**
     * 系统电源连接、断开与电量广播监听器，实现智能自动状态识别与界面切换。
     */
    private val powerStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    onDevicePowerConnected()
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    onDevicePowerDisconnected()
                }
                Intent.ACTION_BATTERY_CHANGED -> {
                    checkAndSyncChargingStatus()
                }
            }
        }
    }

    /**
     * Shizuku 权限请求监听器。
     */
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_POWER_REQUEST_CODE) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(requireContext(), getString(R.string.toast_shizuku_success), Toast.LENGTH_SHORT).show()
                loadData()
            } else {
                Toast.makeText(requireContext(), getString(R.string.toast_shizuku_denied), Toast.LENGTH_SHORT).show()
                updateShizukuBannerState()
            }
        }
    }

    /**
     * Shizuku 服务绑定状态监听器。
     */
    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        updateShizukuBannerState()
        if (currentMode == PowerUsageManager.MODE_SHIZUKU && powerManager.isPowerModeConfigured()) {
            loadData()
        }
    }

    companion object {
        private const val PREF_KEY_KEEP_SCREEN_ON = "pref_charging_keep_screen_on"

        /**
         * 存储从历史快照详情页面待载入至主页展示的快照记录实体对象。
         */
        var pendingSnapshotRecord: com.battery.analysis.model.PowerUsageRecord? = null

        /**
         * 创建 PowerUsageFragment 实例的工厂方法。
         *
         * @return 新创建的 [PowerUsageFragment] 实例
         */
        fun newInstance(): PowerUsageFragment {
            return PowerUsageFragment()
        }
    }

    /**
     * 创建 Fragment 的视图层级结构。
     *
     * @param inflater 布局填充器
     * @param container 父容器视图
     * @param savedInstanceState 状态保存 Bundle
     * @return 初始化的根视图 [View]
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPowerUsageBinding.inflate(inflater, container, false)
        return binding.root
    }

    /**
     * 视图创建完毕后的生命周期回调，配置控件交互、列表适配器与数据加载。
     *
     * @param view 创建完成的根视图
     * @param savedInstanceState 状态保存 Bundle
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        powerManager = PowerUsageManager.getInstance(requireContext())
        chargingManager = ChargingStatsManager.getInstance(requireContext())
        currentMode = powerManager.getSelectedMode()
        tempSelectedSetupMode = currentMode

        // 注册 Shizuku 监听器
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)

        setupRecyclerView()
        setupSwipeRefresh()
        setupClickListeners()
        setupFirstTimeGuideUI()

        checkFirstTimeConfiguration()

        // 动态注册充放电与电池状态广播
        val powerFilter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        requireContext().registerReceiver(powerStateReceiver, powerFilter)

        // 初始智能感知：检测是否在充电，智能决定初始展示界面并同步底栏页签
        val isCharging = chargingManager.isCharging()
        applySmartChargingMode(isCharging = isCharging, showToast = false)

        // 注册断开电源自动生成耗电快照回调监听，非快照模式下自动更新当前数据
        com.battery.analysis.receiver.BatteryUnplugReceiver.onPowerUsageRecordedListener = { _ ->
            if (!isViewingSnapshot && isResumed) {
                loadData()
            }
        }
    }

    /**
     * 检查用户是否已配置过耗电模式：若为初次进入则展示模式引导，若已配置则展示耗电/充电详情。
     */
    private fun checkFirstTimeConfiguration() {
        if (powerManager.isPowerModeConfigured()) {
            binding.layoutFirstTimeSetup.visibility = View.GONE
            val isCharging = chargingManager.isCharging()
            applySmartChargingMode(isCharging = isCharging, showToast = false)
        } else {
            binding.layoutFirstTimeSetup.visibility = View.VISIBLE
            binding.layoutPowerContent.visibility = View.GONE
            binding.cardPowerMetrics.visibility = View.GONE
            binding.layoutChargingContent.layoutChargingRoot.visibility = View.GONE
            updateSetupCardSelection(tempSelectedSetupMode)
        }
    }

    /**
     * 根据用户偏好及当前充电状态动态应用或清除屏幕常亮窗口标志。
     *
     * @param isCharging 当前是否处于充电状态
     */
    private fun applyKeepScreenOn(isCharging: Boolean) {
        val prefs = context?.getSharedPreferences("charging_stats_prefs", Context.MODE_PRIVATE) ?: return
        val keepOn = prefs.getBoolean(PREF_KEY_KEEP_SCREEN_ON, false)
        if (isCharging && keepOn) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * 界面恢复可见时的生命周期回调，同步设置页可能修改的最新模式、充电采样及底栏页签状态。
     */
    override fun onResume() {
        super.onResume()

        val pending = pendingSnapshotRecord
        if (pending != null) {
            pendingSnapshotRecord = null
            loadSnapshotRecord(pending)
            return
        }

        val isCharging = chargingManager.isCharging()
        applySmartChargingMode(isCharging = isCharging, showToast = false)
        applyKeepScreenOn(isCharging)

        if (powerManager.isPowerModeConfigured()) {
            val latestMode = powerManager.getSelectedMode()
            if (latestMode != currentMode) {
                currentMode = latestMode
                if (!isCharging) {
                    loadData()
                }
            }
            updateShizukuBannerState()
            checkNormalPermissionBanner()
        }
    }

    /**
     * 界面退到后台或暂停时的生命周期回调，暂停高频充电采样协程以节约系统资源，并恢复屏幕休眠。
     */
    override fun onPause() {
        super.onPause()
        stopChargingPolling()
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * 界面销毁生命周期回调，停止采样轮询、恢复屏幕休眠、注销动态广播及 Shizuku 监听并释放 ViewBinding。
     */
    override fun onDestroyView() {
        super.onDestroyView()
        stopChargingPolling()
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try {
            requireContext().unregisterReceiver(powerStateReceiver)
        } catch (_: Exception) {
        }
        com.battery.analysis.receiver.BatteryUnplugReceiver.onPowerUsageRecordedListener = null
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
            Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
        } catch (_: Exception) {
        }
        _binding = null
    }

    /**
     * 初始化首次启动模式选择卡片交互与确认逻辑。
     */
    private fun setupFirstTimeGuideUI() {
        binding.cardSetupModeShizuku.setOnClickListener {
            tempSelectedSetupMode = PowerUsageManager.MODE_SHIZUKU
            updateSetupCardSelection(tempSelectedSetupMode)
        }

        binding.cardSetupModeNormal.setOnClickListener {
            tempSelectedSetupMode = PowerUsageManager.MODE_NORMAL
            updateSetupCardSelection(tempSelectedSetupMode)
        }

        binding.btnSetupConfirm.setOnClickListener {
            currentMode = tempSelectedSetupMode
            powerManager.setSelectedMode(currentMode)
            powerManager.setPowerModeConfigured(true)

            binding.layoutFirstTimeSetup.visibility = View.GONE
            binding.layoutPowerContent.visibility = View.VISIBLE
            binding.cardPowerMetrics.visibility = View.VISIBLE

            val tip = if (currentMode == PowerUsageManager.MODE_SHIZUKU) {
                getString(R.string.power_mode_tip_shizuku)
            } else {
                getString(R.string.power_mode_tip_normal)
            }
            Toast.makeText(requireContext(), tip, Toast.LENGTH_SHORT).show()

            loadData()
        }
    }

    /**
     * 更新初次引导界面中两个模式卡片的选中边框与单选按钮状态。
     *
     * @param selectedMode 选中的模式（[PowerUsageManager.MODE_SHIZUKU] 或 [PowerUsageManager.MODE_NORMAL]）
     */
    private fun updateSetupCardSelection(selectedMode: Int) {
        if (selectedMode == PowerUsageManager.MODE_SHIZUKU) {
            binding.cardSetupModeShizuku.strokeColor = Color.parseColor("#2196F3")
            binding.cardSetupModeShizuku.strokeWidth = (2 * resources.displayMetrics.density).toInt()
            binding.radioSetupShizuku.isChecked = true

            binding.cardSetupModeNormal.strokeColor = Color.parseColor("#18888888")
            binding.cardSetupModeNormal.strokeWidth = (1 * resources.displayMetrics.density).toInt()
            binding.radioSetupNormal.isChecked = false
        } else {
            binding.cardSetupModeNormal.strokeColor = Color.parseColor("#2196F3")
            binding.cardSetupModeNormal.strokeWidth = (2 * resources.displayMetrics.density).toInt()
            binding.radioSetupNormal.isChecked = true

            binding.cardSetupModeShizuku.strokeColor = Color.parseColor("#18888888")
            binding.cardSetupModeShizuku.strokeWidth = (1 * resources.displayMetrics.density).toInt()
            binding.radioSetupShizuku.isChecked = false
        }
    }

    /**
     * 初始化应用耗电 RecyclerView 列表。
     */
    private fun setupRecyclerView() {
        binding.recyclerAppUsage.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerAppUsage.adapter = adapter
    }

    /**
     * 初始化 SwipeRefreshLayout 下拉刷新控件。
     */
    private fun setupSwipeRefresh() {
        binding.swipeRefreshLayout.setColorSchemeColors(Color.parseColor("#1E88E5"))
        binding.swipeRefreshLayout.setOnRefreshListener {
            if (currentDisplayTab == 1) {
                // 充电统计界面下无需下拉刷新，自动停止刷新状态
                binding.swipeRefreshLayout.isRefreshing = false
                return@setOnRefreshListener
            }
            if (isViewingSnapshot) {
                restoreLivePowerData()
                return@setOnRefreshListener
            }
            if (powerManager.isPowerModeConfigured()) {
                loadData()
            } else {
                binding.swipeRefreshLayout.isRefreshing = false
            }
        }
    }

    /**
     * 根据 Shizuku 运行与授权状态更新顶部专属引导卡片。
     */
    private fun updateShizukuBannerState() {
        if (_binding == null) return

        if (currentMode == PowerUsageManager.MODE_SHIZUKU && powerManager.isPowerModeConfigured()) {
            val isRunning = powerManager.isShizukuRunning()
            val isAuthorized = powerManager.isShizukuAuthorized()

            if (!isRunning) {
                binding.cardShizukuGuide.visibility = View.VISIBLE
                binding.tvShizukuGuideTitle.text = getString(R.string.power_shizuku_not_running_title)
                binding.tvShizukuGuideDesc.text = getString(R.string.power_shizuku_not_running_desc)
                binding.btnShizukuAction.text = getString(R.string.power_shizuku_btn_open)
                binding.btnShizukuAction.setOnClickListener {
                    openShizukuApp()
                }
            } else if (!isAuthorized) {
                binding.cardShizukuGuide.visibility = View.VISIBLE
                binding.tvShizukuGuideTitle.text = getString(R.string.power_shizuku_unauthorized_title)
                binding.tvShizukuGuideDesc.text = getString(R.string.power_shizuku_unauthorized_desc)
                binding.btnShizukuAction.text = getString(R.string.power_shizuku_btn_auth)
                binding.btnShizukuAction.setOnClickListener {
                    requestShizukuPermission()
                }
            } else {
                binding.cardShizukuGuide.visibility = View.GONE
            }
        } else {
            binding.cardShizukuGuide.visibility = View.GONE
        }
    }

    /**
     * 检查普通模式下的使用情况权限并动态显示或隐藏授权横幅。
     */
    private fun checkNormalPermissionBanner() {
        if (_binding == null) return
        if (currentMode == PowerUsageManager.MODE_NORMAL && powerManager.isPowerModeConfigured()) {
            val hasPermission = powerManager.hasUsageStatsPermission()
            binding.layoutPermissionBanner.visibility = if (hasPermission) View.GONE else View.VISIBLE
        } else {
            binding.layoutPermissionBanner.visibility = View.GONE
        }
    }

    /**
     * 发起 Shizuku 权限请求。
     */
    private fun requestShizukuPermission() {
        try {
            if (Shizuku.pingBinder()) {
                Shizuku.requestPermission(SHIZUKU_POWER_REQUEST_CODE)
            } else {
                Toast.makeText(requireContext(), getString(R.string.toast_shizuku_not_connected), Toast.LENGTH_SHORT).show()
                openShizukuApp()
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), getString(R.string.toast_request_auth_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 尝试拉起外部已安装的 Shizuku 管理器应用。
     */
    private fun openShizukuApp() {
        try {
            val intent = requireContext().packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (intent != null) {
                startActivity(intent)
            } else {
                Toast.makeText(requireContext(), getString(R.string.toast_shizuku_not_found), Toast.LENGTH_LONG).show()
            }
        } catch (_: Exception) {
        }
    }

    /**
     * 设置各按钮与交互组件的点击事件监听。
     */
    private fun setupClickListeners() {
        // 顶部说明文档图标点击（自适应展示耗电说明或充电说明）
        binding.btnPowerGuide.setOnClickListener {
            if (currentDisplayTab == 0) {
                showPowerGuideDialog()
            } else {
                showChargingGuideDialog()
            }
        }

        // 顶部耗电历史记录按钮点击
        binding.btnPowerHistory.setOnClickListener {
            val intent = Intent(requireContext(), PowerHistoryActivity::class.java)
            startActivity(intent)
        }

        // 顶部充电历史记录按钮点击
        binding.btnChargingHistory.setOnClickListener {
            val intent = Intent(requireContext(), ChargingHistoryActivity::class.java)
            startActivity(intent)
        }

        // 顶部清空重置图标点击（自适应重置放电统计或充电图表数据）
        binding.btnPowerClear.setOnClickListener {
            if (currentDisplayTab == 0) {
                showClearConfirmDialog()
            } else {
                showChargingClearConfirmDialog()
            }
        }

        // 充电图表帮助问号点击
        binding.layoutChargingContent.btnChargingChartHelp.setOnClickListener {
            showChargingGuideDialog()
        }

        // 历史快照横幅恢复实时按钮点击
        binding.btnPowerRestoreRealtime.setOnClickListener {
            restoreLivePowerData()
        }

        // “使用过程 ?” 问号图标点击
        binding.btnProcessHelp.setOnClickListener {
            showPowerProcessGuideDialog()
        }

        // “使用场景 ?” 问号图标点击
        binding.btnSceneHelp.setOnClickListener {
            showPowerSceneGuideDialog()
        }

        // 场景排序切换按钮（双向箭头）：在“按时长”、“按功耗”与“按电量”之间循环快速切换
        binding.btnSceneSwap.setOnClickListener {
            currentSortIndex = (currentSortIndex + 1) % 3
            adapter.setSortMode(currentSortIndex)
            val tip = when (currentSortIndex) {
                0 -> getString(R.string.power_sort_duration)
                1 -> getString(R.string.power_sort_power)
                else -> getString(R.string.power_sort_energy)
            }
            Toast.makeText(requireContext(), tip, Toast.LENGTH_SHORT).show()
        }

        // 场景排序菜单按钮（漏斗）：弹出多选排序弹窗
        binding.btnSceneSort.setOnClickListener {
            showSortChoiceDialog()
        }

        // 普通模式权限授权按钮点击
        binding.btnGrantPermission.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(requireContext(), getString(R.string.toast_request_auth_failed, ""), Toast.LENGTH_SHORT).show()
            }
        }

        // 功耗时间轴底部指标多选/反选监听（功耗 / 电量 / 温度 / 电压 / 应用）
        binding.metricSelectorView.setOnMetricsChangedListener { selectedMetrics ->
            binding.batteryTimelineView.setSelectedMetrics(selectedMetrics)
        }

        // 功耗时间轴 App 图标点击监听：弹出 App 详细能耗 BottomSheet
        binding.batteryTimelineView.setOnAppEventListener { event ->
            AppEnergyDetailBottomSheetDialog(requireContext(), event).show()
        }
    }

    /**
     * 根据设备当前充放电状态智能应用界面模式：
     * 处于充电状态时呈现【充电统计】界面，底部页签动态更新为“充电”；
     * 处于放电状态时呈现【耗电统计】界面，底部页签动态更新为“耗电”。
     *
     * @param isCharging 系统当前是否处于充电状态
     * @param showToast 是否弹出智能切换提示 Toast
     */
    fun applySmartChargingMode(isCharging: Boolean, showToast: Boolean = false) {
        if (_binding == null) return
        currentDisplayTab = if (isCharging) 1 else 0

        // 智能联动更新 MainActivity 底部导航栏第一个页签的标题（充电 / 耗电）与图标
        (activity as? MainActivity)?.updateBottomNavPowerTab(isCharging)

        if (!powerManager.isPowerModeConfigured()) {
            binding.layoutFirstTimeSetup.visibility = View.VISIBLE
            binding.layoutPowerContent.visibility = View.GONE
            binding.cardPowerMetrics.visibility = View.GONE
            binding.layoutChargingContent.layoutChargingRoot.visibility = View.GONE
            return
        }

        binding.layoutFirstTimeSetup.visibility = View.GONE

        if (isCharging) {
            // 智能呈现【充电统计】界面并根据设置开启屏幕常亮
            applyKeepScreenOn(true)
            binding.swipeRefreshLayout.isEnabled = false
            binding.swipeRefreshLayout.isRefreshing = false
            binding.tvPowerTitle.text = getString(R.string.charging_stats_title)
            binding.layoutPowerContent.visibility = View.GONE
            binding.cardPowerMetrics.visibility = View.GONE
            binding.layoutChargingContent.layoutChargingRoot.visibility = View.VISIBLE

            renderChargingData()
            startChargingPolling()

            if (showToast) {
                Toast.makeText(requireContext(), getString(R.string.toast_auto_switch_charging), Toast.LENGTH_SHORT).show()
            }
        } else {
            // 智能呈现【耗电统计】界面并恢复屏幕休眠
            applyKeepScreenOn(false)
            binding.swipeRefreshLayout.isEnabled = true
            stopChargingPolling()
            binding.tvPowerTitle.text = getString(R.string.power_stats_title)
            binding.layoutPowerContent.visibility = View.VISIBLE
            binding.cardPowerMetrics.visibility = View.VISIBLE
            binding.layoutChargingContent.layoutChargingRoot.visibility = View.GONE

            loadData()

            if (showToast) {
                Toast.makeText(requireContext(), getString(R.string.toast_auto_switch_discharging), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 当监听到连接外部电源（插入充电器）时触发，开启全新充电采样并智能展示充电统计界面。
     */
    private fun onDevicePowerConnected() {
        if (_binding == null) return
        val currentLevel = powerManager.getCurrentBatteryStatus().levelPercent
        val (_, type) = chargingManager.checkCurrentSystemChargingState()
        chargingManager.onPowerConnected(currentLevel, type)

        applySmartChargingMode(isCharging = true, showToast = true)
    }

    /**
     * 当监听到断开外部电源（拔掉充电器）时触发，固化充电数据并智能展示耗电统计界面。
     */
    private fun onDevicePowerDisconnected() {
        if (_binding == null) return
        val currentLevel = powerManager.getCurrentBatteryStatus().levelPercent
        powerManager.onPowerDisconnected(currentLevel)
        chargingManager.onPowerDisconnected()

        applySmartChargingMode(isCharging = false, showToast = true)
    }

    /**
     * 响应系统电池广播 ACTION_BATTERY_CHANGED，动态校准当前充电状态与界面展示。
     */
    private fun checkAndSyncChargingStatus() {
        if (_binding == null) return
        val isCharging = chargingManager.isCharging()
        val currentIsChargingTab = (currentDisplayTab == 1)
        if (isCharging != currentIsChargingTab) {
            applySmartChargingMode(isCharging = isCharging, showToast = false)
        } else {
            (activity as? MainActivity)?.updateBottomNavPowerTab(isCharging)
        }
    }

    /**
     * 启动充电数据高频实时采样协程（默认 1.5 秒更新一次），向走势图追加新点并驱动界面实时刷新。
     */
    private fun startChargingPolling() {
        chargingPollingJob?.cancel()
        chargingPollingJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val samplePoint = chargingManager.sampleCurrentPoint()
                val summary = chargingManager.getCurrentSummary()
                val points = chargingManager.getSamplePoints()

                withContext(Dispatchers.Main) {
                    if (_binding != null && currentDisplayTab == 1) {
                        renderChargingData(summary, points, samplePoint)
                    }
                }
                delay(1500L)
            }
        }
    }

    /**
     * 停止正在运行的充电实时采样后台协程。
     */
    private fun stopChargingPolling() {
        chargingPollingJob?.cancel()
        chargingPollingJob = null
    }

    /**
     * 将当前或最新的充电统计数据包渲染更新至充电专属界面各卡片与三合一图表中。
     *
     * @param summary 充电会话汇总数据实体，若为空则由管理器内存获取
     * @param points 采样点历史列表，若为空则由管理器内存获取
     * @param latestPoint 最近一次采样的物理指标点，若为空则由最新点或兜底合成
     */
    private fun renderChargingData(
        summary: ChargingSessionSummary = chargingManager.getCurrentSummary(),
        points: List<ChargingSamplePoint> = chargingManager.getSamplePoints(),
        latestPoint: ChargingSamplePoint? = null
    ) {
        if (_binding == null) return
        val chargingView = binding.layoutChargingContent
        val currentPoint = latestPoint ?: points.lastOrNull() ?: ChargingSamplePoint(
            timestamp = System.currentTimeMillis(),
            powerWatts = summary.maxPowerWatts,
            batteryLevel = summary.currentLevel,
            temperature = summary.maxTemperature,
            voltageVolts = 4.2f,
            currentMa = 2000f
        )

        // 1. 更新三合一走势折线图 (功率: 绿, 电量: 蓝, 温度: 红)
        chargingView.chargingChartView.setData(points)
        chargingView.chargingChartView.setOnPointSelectedListener(object : ChargingChartView.OnPointSelectedListener {
            override fun onPointSelected(point: ChargingSamplePoint?) {
                val targetPoint = point ?: currentPoint
                chargingView.tvLegendPower.text = String.format(Locale.getDefault(), "%.2fW", targetPoint.powerWatts)
                chargingView.tvLegendLevel.text = "${targetPoint.batteryLevel}%"
                chargingView.tvLegendTemp.text = String.format(Locale.getDefault(), "%.1f℃", targetPoint.temperature)
            }
        })

        // 2. 更新图表正下方的三色图例标识与实时读数看板（严格符合用户要求）
        chargingView.tvLegendPower.text = String.format(Locale.getDefault(), "%.2fW", currentPoint.powerWatts)
        chargingView.tvLegendLevel.text = "${currentPoint.batteryLevel}%"
        chargingView.tvLegendTemp.text = String.format(Locale.getDefault(), "%.1f℃", currentPoint.temperature)

        // 3. 填充整合版大卡片：环形进度条与中心大字
        chargingView.circleProgressLevel.setProgress(currentPoint.batteryLevel)
        chargingView.tvChargingCurrentPercent.text = "${currentPoint.batteryLevel}%"

        // 4. 充电状态标题与屏幕常亮灯泡控制
        chargingView.tvChargingStateTitle.text = if (summary.isCharging) {
            "充电中"
        } else {
            "未充电"
        }
        val prefs = requireContext().getSharedPreferences("charging_stats_prefs", Context.MODE_PRIVATE)
        val isKeepScreenOn = prefs.getBoolean(PREF_KEY_KEEP_SCREEN_ON, false)
        chargingView.ivChargingBulb.setColorFilter(
            if (isKeepScreenOn) Color.parseColor("#FFD600") else Color.parseColor("#757575")
        )
        chargingView.ivChargingBulb.setOnClickListener {
            val newKeepOn = !prefs.getBoolean(PREF_KEY_KEEP_SCREEN_ON, false)
            prefs.edit().putBoolean(PREF_KEY_KEEP_SCREEN_ON, newKeepOn).apply()
            if (newKeepOn) {
                activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                chargingView.ivChargingBulb.setColorFilter(Color.parseColor("#FFD600"))
                Toast.makeText(requireContext(), "已开启充电保持屏幕常亮", Toast.LENGTH_SHORT).show()
            } else {
                activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                chargingView.ivChargingBulb.setColorFilter(Color.parseColor("#757575"))
                Toast.makeText(requireContext(), "已关闭充电保持屏幕常亮", Toast.LENGTH_SHORT).show()
            }
        }

        // 5. 核心指标矩阵
        // 行 1：电池实时功率与 USB 充电输入功率
        val pWatts = currentPoint.powerWatts
        val batteryPowerText = if (!summary.isCharging && pWatts > 0f) {
            String.format(Locale.getDefault(), "-%.2fW", pWatts)
        } else {
            String.format(Locale.getDefault(), "%.2fW", pWatts)
        }
        chargingView.tvMetricBatteryPower.text = batteryPowerText

        val usbPowerText = if (summary.isCharging) {
            val estimatedUsb = (pWatts + 1.8f).coerceAtLeast(0f)
            String.format(Locale.getDefault(), "%.1fW?", estimatedUsb)
        } else {
            "0.0W?"
        }
        chargingView.tvMetricUsbPower.text = usbPowerText

        // 行 2（温度上方）：平均充电功率与峰值功率
        chargingView.tvMetricAvgPower.text = String.format(Locale.getDefault(), "%.2fW", summary.avgPowerWatts)
        chargingView.tvMetricMaxPower.text = String.format(Locale.getDefault(), "%.2fW", summary.maxPowerWatts)

        // 行 3：当前温度与充电期间最高温度
        chargingView.tvMetricTemp.text = String.format(Locale.getDefault(), "%.1f℃", currentPoint.temperature)
        chargingView.tvMetricMaxTemp.text = String.format(Locale.getDefault(), "%.1f℃", summary.maxTemperature)

        // 行 4（温度下方）：充电瞬时电流与电池电压
        chargingView.tvMetricCurrent.text = String.format(Locale.getDefault(), "%.0fmA", currentPoint.currentMa)
        chargingView.tvMetricVoltage.text = String.format(Locale.getDefault(), "%.3fv", currentPoint.voltageVolts)

        // 行 5：电池容量与等效能量（如 8000mAh (≈30.9Wh)）
        val capacityMah = NormalApiProvider.getDesignCapacity(requireContext())
        val safeCap = if (capacityMah != null && capacityMah > 100f) capacityMah else 5000f
        val safeWh = (safeCap * 3.86f) / 1000f
        chargingView.tvMetricCapacityEnergy.text = "${safeCap.toInt()}mAh (≈${String.format(Locale.getDefault(), "%.1f", safeWh)}Wh)"

        // 6. 填充下方条形底栏卡片：左侧日期与时间范围换行，右侧亮屏与息屏指标上下严格对齐
        chargingView.tvChargingDate.text = formatChargingDate(summary.startTimestamp)
        chargingView.tvChargingTimeRange.text = formatChargingTimeRangeOnly(summary.startTimestamp, summary.endTimestamp)

        // 亮屏与息屏数据计算
        val screenOnDurationMs = (summary.getDurationMs() - summary.screenOffDurationMs).coerceAtLeast(0L)
        val screenOnLevelGain = (summary.getLevelGain() - summary.screenOffLevelGain).coerceAtLeast(0)
        val screenOnEnergyWh = (summary.chargedEnergyWh - summary.screenOffEnergyWh).coerceAtLeast(0f)

        // 第一行：亮屏数据（时间格式 00:00，百分比与能量）
        chargingView.tvChargingScreenOnDuration.text = formatDurationColon(screenOnDurationMs)
        chargingView.tvChargingScreenOnLevelGain.text = "+$screenOnLevelGain%"
        chargingView.tvChargingScreenOnEnergyGain.text = String.format(Locale.getDefault(), "+%.1fWh", screenOnEnergyWh)

        // 第二行：息屏数据（时间格式 00:00，百分比与能量，与亮屏行严格列对齐）
        chargingView.tvChargingScreenOffDuration.text = formatDurationColon(summary.screenOffDurationMs)
        chargingView.tvChargingScreenOffLevelGain.text = "+${summary.screenOffLevelGain}%"
        chargingView.tvChargingScreenOffEnergyGain.text = String.format(Locale.getDefault(), "+%.1fWh", summary.screenOffEnergyWh)
    }

    /**
     * 将充电起始时间戳格式化为纯日期字符串（如 "2026-09-07"）。
     *
     * @param startTs 充电开始时间戳（毫秒）
     * @return 格式化后的日期文本
     */
    private fun formatChargingDate(startTs: Long): String {
        val start = if (startTs > 0L) startTs else System.currentTimeMillis()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return dateFormat.format(Date(start))
    }

    /**
     * 将充电起止时间戳格式化为纯时间区间字符串（如 "11:28 ~ 11:39"）。
     *
     * @param startTs 充电开始时间戳（毫秒）
     * @param endTs 充电结束或最新采样时间戳（毫秒）
     * @return 格式化后的时间区间文本
     */
    private fun formatChargingTimeRangeOnly(startTs: Long, endTs: Long): String {
        val start = if (startTs > 0L) startTs else System.currentTimeMillis()
        val end = if (endTs >= start) endTs else System.currentTimeMillis()
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        return "${timeFormat.format(Date(start))}~${timeFormat.format(Date(end))}"
    }

    /**
     * 将充电持续时长（毫秒）格式化为冒号分隔的 00:00 或 00:00:00 风格字符串。
     *
     * @param durationMs 持续毫秒数
     * @return 格式化后的冒号分隔时长文本
     */
    private fun formatDurationColon(durationMs: Long): String {
        val totalSec = (durationMs / 1000L).coerceAtLeast(0L)
        val hours = totalSec / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val seconds = totalSec % 60L
        return if (hours > 0L) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    /**
     * 弹出高颜值充电统计全景指南与图表说明对话框。
     */
    private fun showChargingGuideDialog() {
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.charging_guide_title))
            .setMessage(getString(R.string.charging_guide_desc))
            .setPositiveButton(getString(R.string.understood), null)
            .create()
        dialog.show()
        applyDialogWindowStyle(dialog)
    }

    /**
     * 弹出重置充电走势图表与统计数据的二次确认对话框。
     */
    private fun showChargingClearConfirmDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.charging_reset_confirm_title))
            .setMessage(getString(R.string.charging_reset_confirm_msg))
            .setPositiveButton(getString(R.string.confirm)) { _, _ ->
                chargingManager.resetChargingStats()
                binding.layoutChargingContent.chargingChartView.clearData()
                renderChargingData()
                Toast.makeText(requireContext(), getString(R.string.charging_reset_success), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /**
     * 异步加载电池状态、放电曲线与应用使用场景数据（按当前活跃模式读取真实数据）。
     */
    fun loadData() {
        binding.swipeRefreshLayout.isRefreshing = true
        updateShizukuBannerState()
        checkNormalPermissionBanner()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            if (currentMode == PowerUsageManager.MODE_SHIZUKU && powerManager.isShizukuAuthorized()) {
                powerManager.grantUsageStatsPermissionViaShizuku()
            }
            val fullPackage = powerManager.loadPowerData(currentMode)

            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                checkNormalPermissionBanner()
                renderFullPowerData(fullPackage)
                binding.swipeRefreshLayout.isRefreshing = false
            }
        }
    }

    /**
     * 将解析出的完整耗电数据包渲染更新至界面各展示卡片与应用列表中。
     *
     * @param fullPackage 包含电池快照、核心指标、应用列表及走势点的完整数据包
     */
    private fun renderFullPowerData(fullPackage: com.battery.analysis.manager.FullPowerDataPackage) {
        lastRenderedPackage = fullPackage
        val snapshot = fullPackage.batterySnapshot
        val overview = fullPackage.overviewStats

        // 1. 刷新使用过程卡片信息（开始放电电量 → 当前电量）
        val startLevel = (fullPackage.trendPoints.firstOrNull()?.batteryLevel ?: fullPackage.startLevelPercent)
            .coerceAtLeast(snapshot.levelPercent)
        binding.tvBatteryStartPercent.text = "${startLevel}%"
        binding.tvBatteryPercentHeader.text = "${snapshot.levelPercent}%"

        // 构建并绑定功耗时间轴最新状态（多选模式）
        val selectedMetrics = binding.metricSelectorView.getSelectedMetrics()
        val timelineState = powerManager.buildTimelineState(fullPackage).copy(selectedMetrics = selectedMetrics)
        binding.batteryTimelineView.setState(timelineState)

        val energyText = String.format(Locale.getDefault(), getString(R.string.power_wh_format), snapshot.energyWh)
        binding.tvEnergyWh.text = energyText
        val energyTooltip = getString(R.string.power_tooltip_energy, energyText)
        binding.llEnergyContainer.contentDescription = energyTooltip
        binding.llEnergyContainer.setOnClickListener {
            Toast.makeText(requireContext(), energyTooltip, Toast.LENGTH_SHORT).show()
        }
        binding.tvTemperature.text = String.format(Locale.getDefault(), getString(R.string.power_temp_format), snapshot.temperature)
        binding.tvVoltage.text = String.format(Locale.getDefault(), getString(R.string.power_volt_format), snapshot.voltageVolts)
        binding.tvChargingStatus.text = if (snapshot.isCharging) {
            getString(R.string.power_status_charging)
        } else {
            getString(R.string.power_status_unplugged)
        }

        // 2. 刷新核心功耗指标卡片（三大卡片三行精准对应呈现）
        val onPowerStr = if (overview.screenOnPowerWatts > 0.001f) {
            String.format(Locale.getDefault(), "%.2fW", overview.screenOnPowerWatts)
        } else {
            "--"
        }
        val avgPowerStr = String.format(Locale.getDefault(), "%.2fW", overview.avgPowerWatts)
        val offPowerStr = if (overview.screenOffPowerWatts > 0.001f) {
            String.format(Locale.getDefault(), "%.2fW", overview.screenOffPowerWatts)
        } else {
            "--"
        }
        binding.tvPowerScreenOn.text = String.format(Locale.getDefault(), getString(R.string.power_format_screen_on), onPowerStr)
        binding.tvPowerAvg.text = String.format(Locale.getDefault(), getString(R.string.power_format_avg), avgPowerStr)
        binding.tvPowerScreenOff.text = String.format(Locale.getDefault(), getString(R.string.power_format_screen_off), offPowerStr)

        binding.tvTimeScreenOn.text = String.format(Locale.getDefault(), getString(R.string.power_format_screen_on), overview.screenOnDurationText)
        binding.tvTimeScreenOff.text = String.format(Locale.getDefault(), getString(R.string.power_format_screen_off), overview.screenOffDurationText)
        binding.tvTimeTotal.text = String.format(Locale.getDefault(), getString(R.string.power_format_total), overview.totalDurationText)

        binding.tvRemainingScreenOn.text = String.format(Locale.getDefault(), getString(R.string.power_format_screen_on), overview.remainingScreenOnText)
        binding.tvRemainingComposite.text = String.format(Locale.getDefault(), getString(R.string.power_format_composite), overview.remainingCompositeText)
        binding.tvRemainingScreenOff.text = String.format(Locale.getDefault(), getString(R.string.power_format_screen_off), overview.remainingScreenOffText)

        // 3. 刷新应用场景列表
        adapter.submitList(fullPackage.appList)
    }

    /**
     * 弹出耗电历史快照列表弹窗，供用户浏览与选择加载历史快照或删除历史记录。
     */
    private fun showPowerHistoryDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_power_history, null)
        val rvHistory = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_power_history)
        val layoutEmpty = dialogView.findViewById<View>(R.id.layout_empty_history)
        val btnClose = dialogView.findViewById<ImageView>(R.id.btn_dialog_close)
        val btnClearAll = dialogView.findViewById<ImageView>(R.id.btn_dialog_clear_all)

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        val historyDb = com.battery.analysis.db.PowerUsageDbHelper.getInstance(requireContext())
        lateinit var historyAdapter: PowerHistoryAdapter

        fun reloadHistoryList() {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val list = historyDb.getAllRecords()
                withContext(Dispatchers.Main) {
                    if (list.isEmpty()) {
                        rvHistory.visibility = View.GONE
                        layoutEmpty.visibility = View.VISIBLE
                    } else {
                        rvHistory.visibility = View.VISIBLE
                        layoutEmpty.visibility = View.GONE
                        historyAdapter.submitList(list)
                    }
                }
            }
        }

        historyAdapter = PowerHistoryAdapter(
            onItemClick = { record ->
                dialog.dismiss()
                loadSnapshotRecord(record)
            },
            onDeleteClick = { record ->
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.delete))
                    .setMessage(getString(R.string.toast_delete_success))
                    .setPositiveButton(getString(R.string.confirm)) { _, _ ->
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                            historyDb.deleteRecord(record.id)
                            withContext(Dispatchers.Main) {
                                reloadHistoryList()
                            }
                        }
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
        )

        rvHistory.layoutManager = LinearLayoutManager(requireContext())
        rvHistory.adapter = historyAdapter

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        btnClearAll.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.power_history_clear_title))
                .setMessage(getString(R.string.power_history_clear_message))
                .setPositiveButton(getString(R.string.confirm)) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        historyDb.clearAll()
                        withContext(Dispatchers.Main) {
                            reloadHistoryList()
                        }
                    }
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }

        reloadHistoryList()
        dialog.show()

        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            val width = (resources.displayMetrics.widthPixels * 0.92).toInt()
            window.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setGravity(android.view.Gravity.CENTER)
        }
    }

    /**
     * 弹出充电历史记录列表弹窗，支持浏览历史充电会话、查看详情、单条删除与全量清空。
     */
    private fun showChargingHistoryDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_charging_history, null)
        val rvHistory = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_charging_history)
        val layoutEmpty = dialogView.findViewById<View>(R.id.layout_empty_history)
        val btnClose = dialogView.findViewById<ImageView>(R.id.btn_dialog_close)
        val btnClearAll = dialogView.findViewById<ImageView>(R.id.btn_dialog_clear_all)

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        val chargingDb = com.battery.analysis.db.ChargingHistoryDbHelper.getInstance(requireContext())
        lateinit var chargingHistoryAdapter: ChargingHistoryAdapter

        fun reloadChargingHistory() {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val list = chargingDb.getAllRecords()
                withContext(Dispatchers.Main) {
                    if (list.isEmpty()) {
                        rvHistory.visibility = View.GONE
                        layoutEmpty.visibility = View.VISIBLE
                    } else {
                        rvHistory.visibility = View.VISIBLE
                        layoutEmpty.visibility = View.GONE
                        chargingHistoryAdapter.submitList(list)
                    }
                }
            }
        }

        chargingHistoryAdapter = ChargingHistoryAdapter(
            onItemClick = { record ->
                AlertDialog.Builder(requireContext())
                    .setTitle("充电详情 (${record.recordTime})")
                    .setMessage(
                        "充电接口：${record.chargeType}\n" +
                        "总充电时长：${record.getFormattedDuration()}\n" +
                        "息屏充电时长：${record.getFormattedScreenOffDuration()}\n" +
                        "电量变化：${record.startLevel}% → ${record.endLevel}% (+${record.levelGain}%)\n" +
                        "充入能量：+${String.format(Locale.getDefault(), "%.2f", record.chargedEnergyWh)} Wh\n" +
                        "平均功率：${String.format(Locale.getDefault(), "%.2f", record.avgPowerWatts)} W\n" +
                        "峰值功率：${String.format(Locale.getDefault(), "%.2f", record.maxPowerWatts)} W\n" +
                        "最高温度：${String.format(Locale.getDefault(), "%.1f", record.maxTemperature)} ℃"
                    )
                    .setPositiveButton(getString(R.string.understood), null)
                    .show()
            },
            onDeleteClick = { record ->
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.delete))
                    .setMessage("确定要删除本次充电记录吗？")
                    .setPositiveButton(getString(R.string.confirm)) { _, _ ->
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                            chargingDb.deleteRecord(record.id)
                            withContext(Dispatchers.Main) {
                                reloadChargingHistory()
                            }
                        }
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
        )

        rvHistory.layoutManager = LinearLayoutManager(requireContext())
        rvHistory.adapter = chargingHistoryAdapter

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        btnClearAll.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("清空充电历史")
                .setMessage("确定要清空所有已保存的充电历史记录吗？")
                .setPositiveButton(getString(R.string.confirm)) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        chargingDb.clearAll()
                        withContext(Dispatchers.Main) {
                            reloadChargingHistory()
                        }
                    }
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }

        reloadChargingHistory()
        dialog.show()

        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            val width = (resources.displayMetrics.widthPixels * 0.92).toInt()
            window.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setGravity(android.view.Gravity.CENTER)
        }
    }

    /**
     * 将选中的耗电历史记录转换为数据包并全量渲染加载至界面中。
     *
     * @param record 选中的耗电历史快照对象
     */
    private fun loadSnapshotRecord(record: com.battery.analysis.model.PowerUsageRecord) {
        isViewingSnapshot = true
        currentLoadedSnapshotTime = record.recordTime

        binding.tvPowerSnapshotHint.text = getString(R.string.power_history_banner_format, record.recordTime)
        binding.layoutPowerSnapshotBanner.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val fullPackage = record.toFullPowerPackage(requireContext())
            withContext(Dispatchers.Main) {
                if (_binding != null) {
                    renderFullPowerData(fullPackage)
                    Toast.makeText(requireContext(), getString(R.string.power_history_loaded_toast), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 退出耗电历史快照查看模式，恢复实时数据刷新。
     */
    private fun restoreLivePowerData() {
        isViewingSnapshot = false
        currentLoadedSnapshotTime = null
        binding.layoutPowerSnapshotBanner.visibility = View.GONE
        loadData()
        Toast.makeText(requireContext(), getString(R.string.toast_restored_realtime), Toast.LENGTH_SHORT).show()
    }

    /**
     * 为自定义对话框应用统一的居中、宽度与半透明背景窗口样式。
     *
     * @param dialog 待配置样式的 [AlertDialog] 实例
     */
    private fun applyDialogWindowStyle(dialog: AlertDialog) {
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            val width = (resources.displayMetrics.widthPixels * 0.92).toInt()
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setGravity(Gravity.CENTER)
        }
    }

    /**
     * 弹出综合“耗电统计与指标说明”全景指南对话框。
     * 全面解读放电走势曲线、三大核心放电指标、应用场景画像及统计周期机制。
     */
    private fun showPowerGuideDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_power_stats_guide, null)
        val btnClose = dialogView.findViewById<TextView>(R.id.btn_dialog_stats_guide_close)

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        applyDialogWindowStyle(dialog)
    }

    /**
     * 弹出“使用过程”卡片专属的高颜值使用说明与指标解析对话框。
     */
    private fun showPowerProcessGuideDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_power_process_guide, null)
        val btnClose = dialogView.findViewById<TextView>(R.id.btn_dialog_process_guide_close)

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        applyDialogWindowStyle(dialog)
    }

    /**
     * 弹出“使用场景”应用排行卡片专属的高颜值使用说明与能耗分析对话框。
     */
    private fun showPowerSceneGuideDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_power_scene_guide, null)
        val btnClose = dialogView.findViewById<TextView>(R.id.btn_dialog_scene_guide_close)

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        applyDialogWindowStyle(dialog)
    }

    /**
     * 弹出重置当前放电周期耗电统计与采样记录的高颜值警告确认对话框。
     * 动态展示当前电池电量、当前工作模式及已记录的时长和采样点数，并在确认后执行清空重置。
     */
    private fun showClearConfirmDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_power_reset_confirm, null)

        val tvMode = dialogView.findViewById<TextView>(R.id.tv_reset_preview_mode)
        val tvTime = dialogView.findViewById<TextView>(R.id.tv_reset_preview_time)
        val tvSummary = dialogView.findViewById<TextView>(R.id.tv_reset_preview_summary)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btn_dialog_reset_cancel)
        val btnConfirm = dialogView.findViewById<TextView>(R.id.btn_dialog_reset_confirm)

        // 填充当前工作模式
        val modeText = if (currentMode == PowerUsageManager.MODE_SHIZUKU) {
            getString(R.string.power_mode_shizuku)
        } else {
            getString(R.string.power_mode_normal)
        }
        tvMode.text = modeText

        // 填充放电时长与电量信息
        val durationText = lastRenderedPackage?.overviewStats?.totalDurationText ?: "--"
        tvTime.text = "${getString(R.string.dialog_power_reset_recorded_time)}: $durationText"

        val currentLevel = lastRenderedPackage?.batterySnapshot?.levelPercent
            ?: powerManager.getCurrentBatteryStatus().levelPercent
        val pointCount = lastRenderedPackage?.trendPoints?.size ?: 0
        tvSummary.text = "🔋 ${getString(R.string.dialog_power_reset_current_level)} $currentLevel%   •   📊 $pointCount 个采样点"

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnConfirm.setOnClickListener {
            val resetLevel = powerManager.getCurrentBatteryStatus().levelPercent
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                powerManager.onPowerDisconnected(resetLevel)
                powerManager.resetPowerStats()
                withContext(Dispatchers.Main) {
                    if (_binding != null) {
                        Toast.makeText(requireContext(), getString(R.string.power_toast_cleared), Toast.LENGTH_SHORT).show()
                        loadData()
                        dialog.dismiss()
                    }
                }
            }
        }

        dialog.show()
        applyDialogWindowStyle(dialog)
    }

    /**
     * 弹出选择排序方式下拉气泡菜单。
     * 展开与刷新时间间隔样式一致的气泡菜单，支持按使用时长、按功耗、按消耗电量或按名称进行排序切换。
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

        val normalColor = ContextCompat.getColor(requireContext(), R.color.popup_item_text)
        val activeColor = Color.parseColor("#2196F3")

        sortViews.forEachIndexed { index, textView ->
            textView.setTextColor(if (index == currentSortIndex) activeColor else normalColor)
            textView.setOnClickListener {
                currentSortIndex = index
                adapter.setSortMode(index)
                popupWindow.dismiss()
            }
        }

        popupWindow.showAsDropDown(
            binding.btnSceneSort,
            0,
            (4 * density).toInt(),
            android.view.Gravity.END
        )
    }

    /**
     * 弹出通用纯文本说明对话框。
     *
     * @param title 对话框标题
     * @param message 对话框正文说明
     */
    private fun showSimpleDialog(title: String, message: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(getString(R.string.understood), null)
            .show()
    }
}

