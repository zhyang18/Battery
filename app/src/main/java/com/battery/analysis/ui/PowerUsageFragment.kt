package com.battery.analysis.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.battery.analysis.R
import com.battery.analysis.databinding.FragmentPowerUsageBinding
import com.battery.analysis.manager.FullPowerDataPackage
import com.battery.analysis.manager.PowerUsageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.util.Locale

/**
 * 耗电统计顶级页签 Fragment。
 * 遵循设计图清爽布局，首次进入引导用户选择检测模式，确认后展示“使用过程”放电曲线、核心功耗指标与各应用使用场景排行。
 * 支持在设置中动态调整检测模式，并通过 onResume 自动响应最新配置。
 */
class PowerUsageFragment : Fragment() {

    private var _binding: FragmentPowerUsageBinding? = null
    private val binding get() = _binding!!

    private lateinit var powerManager: PowerUsageManager
    private val adapter = AppPowerUsageAdapter()

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

        // 注册断开电源自动生成耗电快照回调监听，非快照模式下自动更新当前数据
        com.battery.analysis.receiver.BatteryUnplugReceiver.onPowerUsageRecordedListener = { _ ->
            if (!isViewingSnapshot && isResumed) {
                loadData()
            }
        }
    }

    /**
     * 检查用户是否已配置过耗电模式：若为初次进入则展示模式引导，若已配置则展示耗电详情。
     */
    private fun checkFirstTimeConfiguration() {
        if (powerManager.isPowerModeConfigured()) {
            binding.layoutFirstTimeSetup.visibility = View.GONE
            binding.layoutPowerContent.visibility = View.VISIBLE
            loadData()
        } else {
            binding.layoutFirstTimeSetup.visibility = View.VISIBLE
            binding.layoutPowerContent.visibility = View.GONE
            updateSetupCardSelection(tempSelectedSetupMode)
        }
    }

    /**
     * 界面恢复可见时的生命周期回调，同步设置页可能修改的最新模式及权限状态。
     */
    override fun onResume() {
        super.onResume()
        if (powerManager.isPowerModeConfigured()) {
            val latestMode = powerManager.getSelectedMode()
            if (latestMode != currentMode) {
                currentMode = latestMode
                loadData()
            }
            updateShizukuBannerState()
            checkNormalPermissionBanner()
        }
    }

    /**
     * 界面销毁生命周期回调，注销 Shizuku 监听并释放 ViewBinding。
     */
    override fun onDestroyView() {
        super.onDestroyView()
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
        // 顶部说明文档图标点击
        binding.btnPowerGuide.setOnClickListener {
            showPowerGuideDialog()
        }

        // 顶部历史记录按钮点击 (右上角删除按钮旁边就是历史记录按钮)
        binding.btnPowerHistory.setOnClickListener {
            showPowerHistoryDialog()
        }

        // 顶部清空重置图标点击
        binding.btnPowerClear.setOnClickListener {
            showClearConfirmDialog()
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
    }

    /**
     * 异步加载电池状态、放电曲线与应用使用场景数据（按当前活跃模式读取真实数据）。
     */
    fun loadData() {
        binding.swipeRefreshLayout.isRefreshing = true
        updateShizukuBannerState()
        checkNormalPermissionBanner()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val fullPackage = powerManager.loadPowerData(currentMode)

            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
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
        binding.powerChartView.setData(fullPackage.trendPoints)

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
        val onPowerStr = String.format(Locale.getDefault(), "%.2fW", overview.screenOnPowerWatts)
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
