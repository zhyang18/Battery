package com.battery.analysis.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.transition.TransitionManager
import com.battery.analysis.MainActivity
import com.battery.analysis.R
import com.battery.analysis.daemon.DaemonManager
import com.battery.analysis.databinding.FragmentSettingsBinding
import com.battery.analysis.service.KeepAliveAccessibilityService
import com.battery.analysis.manager.LanguageManager
import com.battery.analysis.model.BackupData
import com.battery.analysis.util.setUnitText
import com.battery.analysis.viewmodel.BatteryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置顶级页面 Fragment。
 * 负责管理应用多语言切换、主题模式、实时数据刷新开关与间隔、Shizuku 提权状态与授权、数据备份与恢复，以及应用关于信息。
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: BatteryViewModel by activityViewModels()
    private lateinit var prefs: SharedPreferences

    /**
     * 活跃对话框跟踪列表，防止退入后台或界面销毁时遗留悬挂 Window 导致内存泄漏。
     */
    private val activeDialogs = java.util.concurrent.CopyOnWriteArrayList<android.app.Dialog>()

    /**
     * 活跃气泡弹窗跟踪列表，防止退入后台或界面销毁时遗留悬挂 Window 导致内存泄漏。
     */
    private val activePopups = java.util.concurrent.CopyOnWriteArrayList<android.widget.PopupWindow>()

    /**
     * 桌面悬浮窗子设置项当前是否处于展示（展开或收缩）状态。
     * 为 null 时表示使用默认策略（当悬浮窗已开启且有权限时默认展开展示，关闭时默认收缩）；
     * 当用户点击“开启悬浮窗显示”这一整项 View 时，手动切换其展开与收缩状态。
     */
    private var isFloatingSubSettingsExpanded: Boolean? = null

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
     * @param popup 待跟踪的 [android.widget.PopupWindow] 气泡弹窗实例
     * @return 传入的气泡弹窗实例
     */
    private fun trackPopup(popup: android.widget.PopupWindow): android.widget.PopupWindow {
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
     * SAF 导出备份文件选择保存器 Launcher。
     */
    private val exportBackupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) {
            handleExportBackup(uri)
        }
    }

    /**
     * SAF 导入备份文件选择器 Launcher。
     */
    private val restoreBackupLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            handleRestoreBackupFileSelected(uri)
        }
    }

    /**
     * 申请系统悬浮窗权限结果回调 Launcher。
     */
    private val overlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val ctx = context ?: return@registerForActivityResult
        if (com.battery.analysis.manager.FloatingWindowManager.canDrawOverlays(ctx)) {
            com.battery.analysis.manager.FloatingWindowManager.setFloatingEnabled(ctx, true)
            isFloatingSubSettingsExpanded = true
            updateFloatingWindowDisplay()
            Toast.makeText(ctx, getString(R.string.toast_floating_window_enabled), Toast.LENGTH_SHORT).show()
        } else {
            isFloatingSubSettingsExpanded = false
            updateFloatingWindowDisplay()
        }
    }

    /**
     * 创建 Fragment 的视图层级。
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
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    /**
     * 视图创建完毕后的生命周期回调，配置各项设置监听器与数据绑定。
     *
     * @param view 创建完成的根视图
     * @param savedInstanceState 状态保存 Bundle
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefs = requireContext().getSharedPreferences("battery_app_settings", Context.MODE_PRIVATE)

        setupLanguageSettings()
        setupThemeSettings()
        setupFloatingWindowSettings()
        setupChargeDischargeStatsSettings()
        setupChargingKeepScreenOnSettings()
        setupPowerModeSettings()
        setupCurrentCalibrationSettings()
        setupShizukuSettings()
        setupKeepAliveSettings()
        setupBackupRestoreSettings()
        setupHelpSection()
        setupAboutSection()
        setupScrollListener()

        // 注册底部安全内边距监听，动态分配底边距防系统导航键拦截
        (activity as? MainActivity)?.registerBottomInsetListener(bottomInsetListener)
    }

    private val bottomInsetListener: (Int) -> Unit = { bottomInset ->
        _binding?.let { b ->
            val density = resources.displayMetrics.density
            val totalBottomPadding = bottomInset + (20 * density).toInt()
            b.scrollView.clipToPadding = false
            b.scrollView.setPaddingRelative(
                b.scrollView.paddingStart,
                b.scrollView.paddingTop,
                b.scrollView.paddingEnd,
                totalBottomPadding
            )
        }
    }

    /**
     * 配置设置界面滚动与手势监听，联动控制 MainActivity 底部页签栏的显示与隐藏。
     */
    private fun setupScrollListener() {
        binding.scrollView.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            val dy = scrollY - oldScrollY
            if (dy > 12) {
                // 手指向上滑动列表，灵敏联动隐藏底部页签栏
                (activity as? MainActivity)?.setBottomNavigationVisibility(false)
            } else if (dy < -12 || !binding.scrollView.canScrollVertically(-1)) {
                // 手指向下滑动列表或已回滚至最顶端，恢复展示底部页签栏
                (activity as? MainActivity)?.setBottomNavigationVisibility(true)
            }
        }

        var startTouchY = 0f
        binding.scrollView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startTouchY = event.rawY
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val deltaY = event.rawY - startTouchY
                    if (deltaY < -12f) {
                        // 手指向上拖动，确保立即联动隐藏底部导航栏，避免遮挡底部内容
                        (activity as? MainActivity)?.setBottomNavigationVisibility(false)
                        startTouchY = event.rawY
                    } else if (deltaY > 15f && binding.scrollView.scrollY <= 0) {
                        // 处于最顶部且手指向下拉动，恢复展示底部导航栏
                        (activity as? MainActivity)?.setBottomNavigationVisibility(true)
                        startTouchY = event.rawY
                    }
                }
            }
            false
        }
    }

    /**
     * 界面恢复至前台时的生命周期回调，同步最新的耗电模式及 Shizuku 连接与权限状态。
     */
    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.updateShizukuStatusState()
        val powerManager = com.battery.analysis.manager.PowerUsageManager.getInstance(requireContext())
        updatePowerModeDisplay(powerManager.getSelectedMode())
        updateCurrentCalibrationDisplay()

        val isStatsEnabled = com.battery.analysis.service.BatteryMonitorService.isChargeDischargeStatsEnabled(requireContext())
        if (binding.switchChargeDischargeStats.isChecked != isStatsEnabled) {
            binding.switchChargeDischargeStats.isChecked = isStatsEnabled
        }

        val chargingPrefs = requireContext().getSharedPreferences("charging_stats_prefs", Context.MODE_PRIVATE)
        binding.switchChargingKeepScreenOn.isChecked = chargingPrefs.getBoolean("pref_charging_keep_screen_on", false)

        val isNotificationDisplayEnabled = com.battery.analysis.service.BatteryMonitorService.isNotificationDisplayEnabled(requireContext())
        if (binding.switchKeepAliveService.isChecked != isNotificationDisplayEnabled) {
            binding.switchKeepAliveService.isChecked = isNotificationDisplayEnabled
        }
        binding.switchBootAutoStart.isChecked = com.battery.analysis.service.BatteryMonitorService.isBootAutoStartEnabled(requireContext())
        updateKeepAliveIntervalDisplay()
        updateBatteryOptimizationDisplay()
        updateAccessibilityStatusDisplay()
        updateDaemonStatusDisplay()
        updateFloatingWindowDisplay()
    }

    /**
     * 初始化耗电统计检测模式设置项与下拉气泡弹窗交互。
     * 点击时弹出与刷新时间间隔样式一致的气泡菜单，供用户切换 Shizuku 模式与标准模式。
     */
    private fun setupPowerModeSettings() {
        val powerManager = com.battery.analysis.manager.PowerUsageManager.getInstance(requireContext())
        updatePowerModeDisplay(powerManager.getSelectedMode())

        binding.layoutPowerModeSetting.setOnClickListener {
            val currentMode = powerManager.getSelectedMode()

            val popupView = layoutInflater.inflate(R.layout.popup_power_mode_picker, null)
            val density = resources.displayMetrics.density
            val popupWidth = (130 * density).toInt()

            val popupWindow = trackPopup(
                android.widget.PopupWindow(
                    popupView,
                    popupWidth,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    true
                )
            )

            popupWindow.isOutsideTouchable = true
            popupWindow.isFocusable = true
            popupWindow.animationStyle = R.style.Animation_PopupTopRight

            val tvShizuku = popupView.findViewById<TextView>(R.id.tv_mode_shizuku)
            val tvNormal = popupView.findViewById<TextView>(R.id.tv_mode_normal)

            val normalColor = ContextCompat.getColor(requireContext(), R.color.popup_item_text)
            val activeColor = Color.parseColor("#2196F3")

            val isShizukuInstalled = powerManager.isShizukuInstalled()
            if (!isShizukuInstalled) {
                tvShizuku.text = getString(R.string.power_mode_shizuku_not_installed)
                tvShizuku.alpha = 0.5f
            } else {
                tvShizuku.text = getString(R.string.power_mode_shizuku)
                tvShizuku.alpha = 1.0f
            }

            tvShizuku.setTextColor(if (currentMode == com.battery.analysis.manager.PowerUsageManager.MODE_SHIZUKU && isShizukuInstalled) activeColor else normalColor)
            tvNormal.setTextColor(if (currentMode == com.battery.analysis.manager.PowerUsageManager.MODE_NORMAL || !isShizukuInstalled) activeColor else normalColor)

            val selectMode = { which: Int ->
                powerManager.setSelectedMode(which)
                powerManager.setPowerModeConfigured(true)
                updatePowerModeDisplay(which)
                popupWindow.dismiss()
                val tip = if (which == com.battery.analysis.manager.PowerUsageManager.MODE_SHIZUKU) {
                    getString(R.string.power_mode_tip_shizuku)
                } else {
                    getString(R.string.power_mode_tip_normal)
                }
                Toast.makeText(requireContext(), tip, Toast.LENGTH_SHORT).show()
            }

            tvShizuku.setOnClickListener {
                if (!powerManager.isShizukuInstalled()) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.toast_shizuku_not_installed_tip),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    com.battery.analysis.manager.ShizukuManager.setUserDisabled(requireContext(), false)
                    (activity as? MainActivity)?.updateShizukuStatusState()
                    selectMode(com.battery.analysis.manager.PowerUsageManager.MODE_SHIZUKU)
                }
            }

            tvNormal.setOnClickListener {
                selectMode(com.battery.analysis.manager.PowerUsageManager.MODE_NORMAL)
            }

            popupWindow.showAsDropDown(
                binding.layoutPowerModeSetting,
                0,
                (4 * density).toInt(),
                android.view.Gravity.END
            )
        }
    }

    /**
     * 更新耗电统计检测模式副标题文本。
     * 若未安装 Shizuku，自适应显示为标准模式。
     *
     * @param mode 当前工作模式
     */
    private fun updatePowerModeDisplay(mode: Int) {
        val ctx = context ?: return
        val isShizukuInstalled = com.battery.analysis.manager.PowerUsageManager.getInstance(ctx).isShizukuInstalled()
        binding.tvCurrentPowerMode.text = if (mode == com.battery.analysis.manager.PowerUsageManager.MODE_SHIZUKU && isShizukuInstalled) {
            "⚡ Shizuku"
        } else {
            getString(R.string.power_mode_normal)
        }
    }

    /**
     * 初始化硬件电流校准设置项交互逻辑。
     * 绑定条目点击事件并弹出现代化电流校准弹窗，支持倍率配置（1.0x / 2.0x / 0.5x）、极性反转及库仑计差分推算。
     */
    private fun setupCurrentCalibrationSettings() {
        updateCurrentCalibrationDisplay()
        binding.layoutCurrentCalibrationSetting.setOnClickListener {
            showCurrentCalibrationDialog()
        }
    }

    /**
     * 刷新电流校准条目的摘要文本显示（如 "1x"、"10x · 双电芯"、"-1x · 双电芯 (反转)"）。
     */
    private fun updateCurrentCalibrationDisplay() {
        val ctx = context ?: return
        val mult = com.battery.analysis.manager.CurrentCalibrationManager.getMultiplier(ctx)
        val isDual = com.battery.analysis.manager.CurrentCalibrationManager.isDualCellEnabled(ctx)
        val invert = com.battery.analysis.manager.CurrentCalibrationManager.isInvertPolarity(ctx)
        val text = buildString {
            val formattedMult = if (mult % 1f == 0f) "${mult.toInt()}x" else "${mult}x"
            append(formattedMult)
            if (isDual) {
                append(" · ").append(getString(R.string.current_calibration_dual_cell_badge))
            }
            if (invert) {
                append(" (").append(getString(R.string.current_calibration_invert_badge)).append(")")
            }
        }
        binding.tvCurrentCalibrationSummary.text = text
    }

    /**
     * 弹出硬件电流校准对话框，支持 10 的倍数倍率档位、独立开启双电芯开关及实时瞬时采样动态预览计算。
     */
    private fun showCurrentCalibrationDialog() {
        val ctx = context ?: return
        val dialogView = layoutInflater.inflate(R.layout.dialog_current_calibration, null)

        val tvRawCurrent = dialogView.findViewById<TextView>(R.id.tv_raw_current)
        val tvCalibratedCurrent = dialogView.findViewById<TextView>(R.id.tv_calibrated_current)

        // 8 个 10 的倍数倍率单选胶囊按钮
        val rbPos1 = dialogView.findViewById<RadioButton>(R.id.rb_mult_pos_1)
        val rbPos10 = dialogView.findViewById<RadioButton>(R.id.rb_mult_pos_10)
        val rbPos100 = dialogView.findViewById<RadioButton>(R.id.rb_mult_pos_100)
        val rbPos1000 = dialogView.findViewById<RadioButton>(R.id.rb_mult_pos_1000)
        val rbNeg1 = dialogView.findViewById<RadioButton>(R.id.rb_mult_neg_1)
        val rbNeg10 = dialogView.findViewById<RadioButton>(R.id.rb_mult_neg_10)
        val rbNeg100 = dialogView.findViewById<RadioButton>(R.id.rb_mult_neg_100)
        val rbNeg1000 = dialogView.findViewById<RadioButton>(R.id.rb_mult_neg_1000)

        // 开关组件
        val switchDualCell = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_dual_cell)
        val switchInvert = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_invert_polarity)
        val switchCoulomb = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_coulomb_fallback)

        // 底部确认与取消按钮
        val btnCancel = dialogView.findViewById<TextView>(R.id.btn_cancel)
        val btnConfirm = dialogView.findViewById<TextView>(R.id.btn_confirm)

        val multiplierMap = listOf(
            rbPos1 to 1f,
            rbPos10 to 10f,
            rbPos100 to 100f,
            rbPos1000 to 1000f,
            rbNeg1 to -1f,
            rbNeg10 to -10f,
            rbNeg100 to -100f,
            rbNeg1000 to -1000f
        )

        var selectedMult = com.battery.analysis.manager.CurrentCalibrationManager.getMultiplier(ctx)
        var matched = false
        multiplierMap.forEach { (rb, value) ->
            val isMatch = (value == selectedMult)
            rb.isChecked = isMatch
            if (isMatch) matched = true
        }
        if (!matched) {
            rbPos1.isChecked = true
            selectedMult = 1f
        }

        switchDualCell.isChecked = com.battery.analysis.manager.CurrentCalibrationManager.isDualCellEnabled(ctx)
        switchInvert.isChecked = com.battery.analysis.manager.CurrentCalibrationManager.isInvertPolarity(ctx)
        switchCoulomb.isChecked = com.battery.analysis.manager.CurrentCalibrationManager.isCoulombFallbackEnabled(ctx)

        var cachedRawCurrentMa = 0.0f

        /**
         * 根据当前选中的倍率与双电芯开关状态，动态刷新弹窗内的采样与校准电流文本。
         */
        fun updatePreview() {
            val dualFactor = if (switchDualCell.isChecked) 2.0f else 1.0f
            val calibratedMa = Math.abs(cachedRawCurrentMa * selectedMult * dualFactor)
            tvRawCurrent.setUnitText(String.format(java.util.Locale.getDefault(), "%.1f mA", Math.abs(cachedRawCurrentMa)))
            tvCalibratedCurrent.setUnitText(String.format(java.util.Locale.getDefault(), "%.1f mA", calibratedMa))
        }

        // 单选互斥逻辑与即时刷新预览
        multiplierMap.forEach { (rb, value) ->
            rb.setOnClickListener {
                selectedMult = value
                multiplierMap.forEach { (otherRb, otherVal) ->
                    otherRb.isChecked = (otherVal == value)
                }
                updatePreview()
            }
        }

        // 切换双电芯开关时即时联动计算刷新预览
        switchDualCell.setOnCheckedChangeListener { _, _ ->
            updatePreview()
        }

        // 异步读取底层硬件真实电流并更新界面预览
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val sample = com.battery.analysis.util.SysfsBatterySampler.sampleHardwareBattery(
                context = ctx,
                isCharging = false,
                allowProcessFork = false
            )
            withContext(Dispatchers.Main) {
                if (sample != null) {
                    val initialMult = com.battery.analysis.manager.CurrentCalibrationManager.getMultiplier(ctx)
                    val initialDual = if (com.battery.analysis.manager.CurrentCalibrationManager.isDualCellEnabled(ctx)) 2.0f else 1.0f
                    val initialFactor = Math.abs(initialMult) * initialDual
                    cachedRawCurrentMa = if (initialFactor > 0f) sample.currentMa / initialFactor else sample.currentMa
                } else {
                    cachedRawCurrentMa = 0f
                }
                updatePreview()
            }
        }

        val dialog = AlertDialog.Builder(ctx)
            .setView(dialogView)
            .create()

        applyDialogWindowStyle(dialog)

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnConfirm.setOnClickListener {
            com.battery.analysis.manager.CurrentCalibrationManager.setMultiplier(ctx, selectedMult)
            com.battery.analysis.manager.CurrentCalibrationManager.setDualCellEnabled(ctx, switchDualCell.isChecked)
            com.battery.analysis.manager.CurrentCalibrationManager.setInvertPolarity(ctx, switchInvert.isChecked)
            com.battery.analysis.manager.CurrentCalibrationManager.setCoulombFallbackEnabled(ctx, switchCoulomb.isChecked)

            updateCurrentCalibrationDisplay()
            dialog.dismiss()
            Toast.makeText(ctx, getString(R.string.setting_current_calibration) + "已保存", Toast.LENGTH_SHORT).show()
        }

        showAndTrackDialog(dialog)
    }



    /**
     * 初始化多语言切换设置项与右上角展开气泡弹窗交互。
     */
    private fun setupLanguageSettings() {
        val currentMode = LanguageManager.getLanguageMode(requireContext())
        binding.tvCurrentLanguage.text = LanguageManager.getLanguageTitle(requireContext(), currentMode)

        binding.layoutLanguageSetting.setOnClickListener {
            val popupView = layoutInflater.inflate(R.layout.popup_language_picker, null)
            val density = resources.displayMetrics.density
            val popupWidth = (180 * density).toInt()

            val popupWindow = trackPopup(
                android.widget.PopupWindow(
                    popupView,
                    popupWidth,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    true
                )
            )

            popupWindow.isOutsideTouchable = true
            popupWindow.isFocusable = true
            popupWindow.animationStyle = R.style.Animation_PopupTopRight

            val tvFollowSystem = popupView.findViewById<TextView>(R.id.tv_lang_follow_system)
            val tvZh = popupView.findViewById<TextView>(R.id.tv_lang_zh)
            val tvEn = popupView.findViewById<TextView>(R.id.tv_lang_en)

            val normalColor = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.popup_item_text)
            val activeColor = Color.parseColor("#2196F3")

            val activeMode = LanguageManager.getLanguageMode(requireContext())
            tvFollowSystem.setTextColor(if (activeMode == LanguageManager.MODE_FOLLOW_SYSTEM) activeColor else normalColor)
            tvZh.setTextColor(if (activeMode == LanguageManager.MODE_SIMPLIFIED_CHINESE) activeColor else normalColor)
            tvEn.setTextColor(if (activeMode == LanguageManager.MODE_ENGLISH) activeColor else normalColor)

            tvFollowSystem.setOnClickListener {
                LanguageManager.setLanguageMode(requireContext(), LanguageManager.MODE_FOLLOW_SYSTEM)
                binding.tvCurrentLanguage.text = LanguageManager.getLanguageTitle(requireContext(), LanguageManager.MODE_FOLLOW_SYSTEM)
                popupWindow.dismiss()
            }

            tvZh.setOnClickListener {
                LanguageManager.setLanguageMode(requireContext(), LanguageManager.MODE_SIMPLIFIED_CHINESE)
                binding.tvCurrentLanguage.text = LanguageManager.getLanguageTitle(requireContext(), LanguageManager.MODE_SIMPLIFIED_CHINESE)
                popupWindow.dismiss()
            }

            tvEn.setOnClickListener {
                LanguageManager.setLanguageMode(requireContext(), LanguageManager.MODE_ENGLISH)
                binding.tvCurrentLanguage.text = LanguageManager.getLanguageTitle(requireContext(), LanguageManager.MODE_ENGLISH)
                popupWindow.dismiss()
            }

            popupWindow.showAsDropDown(
                binding.layoutLanguageSetting,
                0,
                (4 * density).toInt(),
                android.view.Gravity.END
            )
        }
    }

    /**
     * 初始化帮助与说明板块，包含电池健康度计算原理与评级标准弹窗展示。
     */
    private fun setupHelpSection() {
        binding.layoutHealthCalcInfo.setOnClickListener {
            showHealthCalculationGuideDialog()
        }
    }

    /**
     * 弹出高颜值现代化电池健康度计算方式与数据源原理解析对话框。
     */
    private fun showHealthCalculationGuideDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_health_calculation_guide, null)
        val btnClose = dialogView.findViewById<View>(R.id.btn_dialog_guide_close)

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        showAndTrackDialog(dialog)
        applyLongContentDialogStyle(dialog, dialogView)
    }

    /**
     * 初始化主题与深色模式开关设置。
     */
    private fun setupThemeSettings() {
        val currentThemeMode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        val isFollowSystem = currentThemeMode == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        val isDarkMode = currentThemeMode == AppCompatDelegate.MODE_NIGHT_YES

        // 避免初次绑定回调触发重建
        binding.switchFollowSystem.setOnCheckedChangeListener(null)
        binding.switchDarkMode.setOnCheckedChangeListener(null)

        binding.switchFollowSystem.isChecked = isFollowSystem
        binding.switchDarkMode.isChecked = isDarkMode
        binding.switchDarkMode.isEnabled = !isFollowSystem
        binding.layoutDarkMode.alpha = if (isFollowSystem) 0.5f else 1.0f

        binding.switchFollowSystem.setOnCheckedChangeListener { _, isChecked ->
            binding.switchDarkMode.isEnabled = !isChecked
            binding.layoutDarkMode.alpha = if (isChecked) 0.5f else 1.0f

            val newMode = if (isChecked) {
                AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            } else {
                if (binding.switchDarkMode.isChecked) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            }

            val saved = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            if (newMode != saved) {
                prefs.edit().putInt("theme_mode", newMode).apply()
                AppCompatDelegate.setDefaultNightMode(newMode)
            }
        }

        binding.switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            val newMode = if (isChecked) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            val saved = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            if (newMode != saved) {
                prefs.edit().putInt("theme_mode", newMode).apply()
                AppCompatDelegate.setDefaultNightMode(newMode)
            }
        }
    }

    /**
     * 初始化桌面悬浮窗设置项与子设置项交互逻辑。
     * 管理悬浮窗总开关（含系统权限检测与引导）、实时功率子开关、实时温度子开关以及位置与外观弹窗。
     * 点击悬浮窗主设置条目时触发子设置项的展示与收缩，悬浮窗开关事件完全由右侧 Switch 按钮负责。
     */
    private fun setupFloatingWindowSettings() {
        updateFloatingWindowDisplay()

        binding.switchFloatingWindow.setOnCheckedChangeListener { _, isChecked ->
            handleFloatingWindowSwitchChanged(isChecked)
        }

        binding.layoutFloatingWindowSetting.setOnClickListener {
            toggleFloatingSubSettingsVisibility()
        }

        binding.switchFloatingShowPower.setOnCheckedChangeListener { _, isChecked ->
            val ctx = requireContext()
            com.battery.analysis.manager.FloatingWindowManager.setShowPower(ctx, isChecked)
        }
        binding.layoutFloatingShowPower.setOnClickListener {
            binding.switchFloatingShowPower.isChecked = !binding.switchFloatingShowPower.isChecked
        }

        binding.switchFloatingShowTemp.setOnCheckedChangeListener { _, isChecked ->
            val ctx = requireContext()
            com.battery.analysis.manager.FloatingWindowManager.setShowTemp(ctx, isChecked)
        }
        binding.layoutFloatingShowTemp.setOnClickListener {
            binding.switchFloatingShowTemp.isChecked = !binding.switchFloatingShowTemp.isChecked
        }

        binding.layoutFloatingPositionSetting.setOnClickListener {
            showFloatingWindowSettingsDialog()
        }
    }

    /**
     * 处理桌面悬浮窗总开关状态变更事件。
     * 校验系统“显示在其他应用上层”权限，更新悬浮窗启用状态并同步子设置项的展示状态。
     *
     * @param isChecked 开关当前的目标选中状态
     */
    private fun handleFloatingWindowSwitchChanged(isChecked: Boolean) {
        val ctx = context ?: return
        val currentEnabled = com.battery.analysis.manager.FloatingWindowManager.isFloatingEnabled(ctx) &&
                com.battery.analysis.manager.FloatingWindowManager.canDrawOverlays(ctx)
        if (isChecked == currentEnabled) return

        if (isChecked) {
            if (com.battery.analysis.manager.FloatingWindowManager.canDrawOverlays(ctx)) {
                com.battery.analysis.manager.FloatingWindowManager.setFloatingEnabled(ctx, true)
                isFloatingSubSettingsExpanded = true
                updateFloatingWindowDisplay()
                Toast.makeText(ctx, getString(R.string.toast_floating_window_enabled), Toast.LENGTH_SHORT).show()
            } else {
                binding.switchFloatingWindow.isChecked = false
                showFloatingPermissionDialog()
            }
        } else {
            com.battery.analysis.manager.FloatingWindowManager.setFloatingEnabled(ctx, false)
            isFloatingSubSettingsExpanded = false
            updateFloatingWindowDisplay()
            Toast.makeText(ctx, getString(R.string.toast_floating_window_disabled), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 切换桌面悬浮窗子设置项区域的展示与收缩状态。
     * 伴随平滑过渡动画，点击主设置条目时仅收起或展开子选项列表，不触碰悬浮窗启闭开关。
     */
    private fun toggleFloatingSubSettingsVisibility() {
        val currentlyExpanded = isFloatingSubSettingsExpanded
            ?: (binding.layoutFloatingSubSettings.visibility == View.VISIBLE)
        val newExpanded = !currentlyExpanded
        isFloatingSubSettingsExpanded = newExpanded

        val parent = binding.layoutFloatingSubSettings.parent as? ViewGroup
        if (parent != null) {
            TransitionManager.beginDelayedTransition(parent)
        }
        binding.layoutFloatingSubSettings.visibility = if (newExpanded) View.VISIBLE else View.GONE
    }

    /**
     * 同步刷新悬浮窗设置项与子设置项的 UI 呈现状态。
     */
    private fun updateFloatingWindowDisplay() {
        val ctx = context ?: return
        val isEnabled = com.battery.analysis.manager.FloatingWindowManager.isFloatingEnabled(ctx) &&
                com.battery.analysis.manager.FloatingWindowManager.canDrawOverlays(ctx)

        binding.switchFloatingWindow.setOnCheckedChangeListener(null)
        binding.switchFloatingWindow.isChecked = isEnabled
        binding.switchFloatingWindow.setOnCheckedChangeListener { _, isChecked ->
            handleFloatingWindowSwitchChanged(isChecked)
        }

        val isExpanded = isFloatingSubSettingsExpanded ?: isEnabled
        binding.layoutFloatingSubSettings.visibility = if (isExpanded) View.VISIBLE else View.GONE

        binding.switchFloatingShowPower.setOnCheckedChangeListener(null)
        binding.switchFloatingShowPower.isChecked = com.battery.analysis.manager.FloatingWindowManager.isShowPower(ctx)
        binding.switchFloatingShowPower.setOnCheckedChangeListener { _, isChecked ->
            com.battery.analysis.manager.FloatingWindowManager.setShowPower(ctx, isChecked)
        }

        binding.switchFloatingShowTemp.setOnCheckedChangeListener(null)
        binding.switchFloatingShowTemp.isChecked = com.battery.analysis.manager.FloatingWindowManager.isShowTemp(ctx)
        binding.switchFloatingShowTemp.setOnCheckedChangeListener { _, isChecked ->
            com.battery.analysis.manager.FloatingWindowManager.setShowTemp(ctx, isChecked)
        }

        updateFloatingPositionSummary()
    }

    /**
     * 刷新悬浮窗位置与外观副标题摘要文本。
     */
    private fun updateFloatingPositionSummary() {
        val ctx = context ?: return
        val isStatusBar = com.battery.analysis.manager.FloatingWindowManager.isAlignStatusBar(ctx)
        val opacity = com.battery.analysis.manager.FloatingWindowManager.getOpacity(ctx)
        val fontSize = com.battery.analysis.manager.FloatingWindowManager.getFontSize(ctx)
        val currentY = com.battery.analysis.manager.FloatingWindowManager.getPosY(ctx)
        val sbHeight = com.battery.analysis.manager.FloatingWindowManager.getStatusBarHeight(ctx)
        val showUnit = com.battery.analysis.manager.FloatingWindowManager.isShowUnit(ctx)
        val showBackground = com.battery.analysis.manager.FloatingWindowManager.isShowBackground(ctx)
        val summary = buildString {
            if (isStatusBar && currentY < sbHeight) {
                append("状态栏位置")
            } else {
                append("自定义位置")
            }
            append(" · ").append(fontSize).append("sp字号")
            append(" · ").append(opacity).append("%透明度")
            val isBold = com.battery.analysis.manager.FloatingWindowManager.isFontBold(ctx)
            if (!isBold) {
                append(" · 常规体")
            }
            if (!showBackground) {
                append(" · 无边框")
            }
            if (!showUnit) {
                append(" · 无单位")
            }
        }
        binding.tvFloatingPositionSummary.text = summary
    }

    /**
     * 弹出高颜值悬浮窗权限授予引导弹窗，引导用户前往系统设置开启“显示在其他应用上层”权限。
     * 采用自定义现代化卡片设计与圆角风格，包含权限用途说明与确认取消交互。
     */
    private fun showFloatingPermissionDialog() {
        val ctx = context ?: return
        val dialogView = layoutInflater.inflate(R.layout.dialog_floating_permission, null)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btn_dialog_permission_cancel)
        val btnGrant = dialogView.findViewById<TextView>(R.id.btn_dialog_permission_grant)

        val dialog = AlertDialog.Builder(ctx)
            .setView(dialogView)
            .create()

        btnCancel.setOnClickListener {
            binding.switchFloatingWindow.isChecked = false
            dialog.dismiss()
        }

        btnGrant.setOnClickListener {
            dialog.dismiss()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${ctx.packageName}")
                    )
                    overlayPermissionLauncher.launch(intent)
                } catch (_: Exception) {
                    com.battery.analysis.manager.FloatingWindowManager.requestOverlayPermission(ctx)
                }
            }
        }

        dialog.setOnCancelListener {
            binding.switchFloatingWindow.isChecked = false
        }

        showAndTrackDialog(dialog)
        applyDialogWindowStyle(dialog)
    }

    /**
     * 弹出高颜值悬浮窗位置与外观设置弹窗。
     * 支持配置状态栏显示范围、垂直与水平精确滑块微调（带 +、- 步进按钮）、文字大小调整、透明度调节及明暗主题联动，
     * 打开状态栏开关时允许显示于状态栏且不强制自动贴齐，关闭时限制不可位于状态栏上，
     * 调节时与屏幕悬浮窗所见即所得实时响应。
     */
    private fun showFloatingWindowSettingsDialog() {
        val ctx = context ?: return
        val dialogView = layoutInflater.inflate(R.layout.dialog_floating_window_settings, null)

        val switchStatusBar = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_align_status_bar)
        val seekVertical = dialogView.findViewById<android.widget.SeekBar>(R.id.seekbar_vertical_pos)
        val tvVerticalValue = dialogView.findViewById<TextView>(R.id.tv_vertical_pos_value)
        val btnVerticalMinus = dialogView.findViewById<TextView>(R.id.btn_vertical_minus)
        val btnVerticalPlus = dialogView.findViewById<TextView>(R.id.btn_vertical_plus)

        val rbLeft = dialogView.findViewById<RadioButton>(R.id.rb_align_left)
        val rbCenter = dialogView.findViewById<RadioButton>(R.id.rb_align_center)
        val rbRight = dialogView.findViewById<RadioButton>(R.id.rb_align_right)
        val seekHorizontal = dialogView.findViewById<android.widget.SeekBar>(R.id.seekbar_horizontal_pos)
        val tvHorizontalValue = dialogView.findViewById<TextView>(R.id.tv_horizontal_pos_value)
        val btnHorizontalMinus = dialogView.findViewById<TextView>(R.id.btn_horizontal_minus)
        val btnHorizontalPlus = dialogView.findViewById<TextView>(R.id.btn_horizontal_plus)

        val seekFontSize = dialogView.findViewById<android.widget.SeekBar>(R.id.seekbar_font_size)
        val tvFontSizeValue = dialogView.findViewById<TextView>(R.id.tv_font_size_value)
        val btnFontSizeMinus = dialogView.findViewById<TextView>(R.id.btn_font_size_minus)
        val btnFontSizePlus = dialogView.findViewById<TextView>(R.id.btn_font_size_plus)

        val seekOpacity = dialogView.findViewById<android.widget.SeekBar>(R.id.seekbar_opacity)
        val tvOpacityValue = dialogView.findViewById<TextView>(R.id.tv_opacity_value)
        val btnOpacityMinus = dialogView.findViewById<TextView>(R.id.btn_opacity_minus)
        val btnOpacityPlus = dialogView.findViewById<TextView>(R.id.btn_opacity_plus)

        val layoutAlignStatusBar = dialogView.findViewById<android.view.View>(R.id.layout_floating_align_status_bar)
        val layoutFontBold = dialogView.findViewById<android.view.View>(R.id.layout_floating_font_bold)
        val switchFontBold = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_font_bold)

        val layoutShowUnit = dialogView.findViewById<android.view.View>(R.id.layout_floating_show_unit)
        val switchShowUnit = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_show_unit)

        val layoutShowBackground = dialogView.findViewById<android.view.View>(R.id.layout_floating_show_background)
        val switchShowBackground = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_show_background)

        val rbThemeFollow = dialogView.findViewById<RadioButton>(R.id.rb_theme_follow_system)
        val rbThemeDark = dialogView.findViewById<RadioButton>(R.id.rb_theme_dark)
        val rbThemeLight = dialogView.findViewById<RadioButton>(R.id.rb_theme_light)

        val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_floating_dialog_cancel)
        val btnConfirm = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_floating_dialog_confirm)

        val (screenWidth, screenHeight) = com.battery.analysis.manager.FloatingWindowManager.getRealScreenSize(ctx)
        val statusBarHeight = com.battery.analysis.manager.FloatingWindowManager.getStatusBarHeight(ctx)

        // 记录打开弹框前的初始位置与外观配置，便于取消时完美恢复
        val initialAlignStatusBar = com.battery.analysis.manager.FloatingWindowManager.isAlignStatusBar(ctx)
        val initialRawY = com.battery.analysis.manager.FloatingWindowManager.getPosY(ctx)
        val initialAlign = com.battery.analysis.manager.FloatingWindowManager.getHorizontalAlign(ctx)
        val initialX = com.battery.analysis.manager.FloatingWindowManager.getPosX(ctx)
        val initialOffsetX = com.battery.analysis.manager.FloatingWindowManager.getOffsetX(ctx)
        val initialPercentX = com.battery.analysis.manager.FloatingWindowManager.getPercentX(ctx)
        val initialPercentY = com.battery.analysis.manager.FloatingWindowManager.getPercentY(ctx)
        val initialFontSize = com.battery.analysis.manager.FloatingWindowManager.getFontSize(ctx)
        val initialFontBold = com.battery.analysis.manager.FloatingWindowManager.isFontBold(ctx)
        val initialOpacity = com.battery.analysis.manager.FloatingWindowManager.getOpacity(ctx)
        val initialThemeMode = com.battery.analysis.manager.FloatingWindowManager.getThemeMode(ctx)
        val initialShowUnit = com.battery.analysis.manager.FloatingWindowManager.isShowUnit(ctx)
        val initialShowBackground = com.battery.analysis.manager.FloatingWindowManager.isShowBackground(ctx)

        val isAlignStatusBar = initialAlignStatusBar
        switchStatusBar.isChecked = isAlignStatusBar
        seekVertical.max = (screenHeight * 0.95f).toInt()
        val currentY = initialRawY
        val initialY = if (!isAlignStatusBar && currentY < statusBarHeight) {
            statusBarHeight
        } else {
            currentY
        }.coerceIn(0, seekVertical.max)
        seekVertical.progress = initialY

        val updateVerticalText = { y: Int ->
            val inSb = y < statusBarHeight
            tvVerticalValue.text = if (inSb) {
                "Y: $y px (状态栏)"
            } else {
                "Y: $y px"
            }
        }
        updateVerticalText(initialY)

        val getMinY = { if (switchStatusBar.isChecked) 0 else statusBarHeight }

        switchStatusBar.setOnCheckedChangeListener { _, isChecked ->
            com.battery.analysis.manager.FloatingWindowManager.setAlignStatusBar(ctx, isChecked)
            if (isChecked) {
                // 开关打开时：代表可以显示在状态栏上，但是不要自动贴齐屏幕状态栏，保持当前位置
                updateVerticalText(seekVertical.progress)
            } else {
                // 开关关闭时：不允许显示在状态栏位置上，若当前在状态栏高度内，则下移至状态栏下方边缘
                if (seekVertical.progress < statusBarHeight) {
                    seekVertical.progress = statusBarHeight
                    com.battery.analysis.manager.FloatingWindowManager.setPosY(ctx, statusBarHeight)
                }
                updateVerticalText(seekVertical.progress)
            }
            com.battery.analysis.manager.FloatingWindowManager.updatePositionAndAppearance(ctx)
        }

        seekVertical.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val minY = getMinY()
                    val targetY = if (progress < minY) minY else progress
                    if (targetY != progress) {
                        seekBar?.progress = targetY
                        return
                    }
                    com.battery.analysis.manager.FloatingWindowManager.setPosY(ctx, targetY)
                    updateVerticalText(targetY)
                    com.battery.analysis.manager.FloatingWindowManager.updatePositionAndAppearance(ctx)
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        // 垂直位置 - 微调按钮
        setupRepeatClick(btnVerticalMinus) {
            val minY = getMinY()
            val targetY = (seekVertical.progress - 1).coerceAtLeast(minY)
            if (targetY != seekVertical.progress) {
                seekVertical.progress = targetY
                com.battery.analysis.manager.FloatingWindowManager.setPosY(ctx, targetY)
                updateVerticalText(targetY)
                com.battery.analysis.manager.FloatingWindowManager.updatePositionAndAppearance(ctx)
            }
        }

        // 垂直位置 + 微调按钮
        setupRepeatClick(btnVerticalPlus) {
            val targetY = (seekVertical.progress + 1).coerceAtMost(seekVertical.max)
            if (targetY != seekVertical.progress) {
                seekVertical.progress = targetY
                com.battery.analysis.manager.FloatingWindowManager.setPosY(ctx, targetY)
                updateVerticalText(targetY)
                com.battery.analysis.manager.FloatingWindowManager.updatePositionAndAppearance(ctx)
            }
        }

        seekHorizontal.max = screenWidth
        val centerProgress = screenWidth / 2
        val currentAlign = com.battery.analysis.manager.FloatingWindowManager.getHorizontalAlign(ctx)
        val currentOffset = com.battery.analysis.manager.FloatingWindowManager.getOffsetX(ctx)
        seekHorizontal.progress = when (currentAlign) {
            com.battery.analysis.manager.FloatingWindowManager.ALIGN_LEFT -> 0
            com.battery.analysis.manager.FloatingWindowManager.ALIGN_RIGHT -> screenWidth
            com.battery.analysis.manager.FloatingWindowManager.ALIGN_CENTER -> centerProgress
            else -> (centerProgress + currentOffset).coerceIn(0, screenWidth)
        }

        val updateHorizontalCapsules = { align: Int ->
            rbLeft.isChecked = (align == com.battery.analysis.manager.FloatingWindowManager.ALIGN_LEFT)
            rbCenter.isChecked = (align == com.battery.analysis.manager.FloatingWindowManager.ALIGN_CENTER)
            rbRight.isChecked = (align == com.battery.analysis.manager.FloatingWindowManager.ALIGN_RIGHT)
            tvHorizontalValue.text = when (align) {
                com.battery.analysis.manager.FloatingWindowManager.ALIGN_LEFT -> "X: " + getString(R.string.floating_horizontal_align_left)
                com.battery.analysis.manager.FloatingWindowManager.ALIGN_CENTER -> "X: " + getString(R.string.floating_horizontal_align_center)
                com.battery.analysis.manager.FloatingWindowManager.ALIGN_RIGHT -> "X: " + getString(R.string.floating_horizontal_align_right)
                else -> {
                    val offset = com.battery.analysis.manager.FloatingWindowManager.getOffsetX(ctx)
                    if (offset == 0) {
                        "X: " + getString(R.string.floating_horizontal_align_center)
                    } else if (offset < 0) {
                        getString(R.string.floating_horizontal_align_center_left, Math.abs(offset))
                    } else {
                        getString(R.string.floating_horizontal_align_center_right, offset)
                    }
                }
            }
        }
        updateHorizontalCapsules(currentAlign)

        rbLeft.setOnClickListener {
            com.battery.analysis.manager.FloatingWindowManager.setHorizontalAlign(ctx, com.battery.analysis.manager.FloatingWindowManager.ALIGN_LEFT)
            seekHorizontal.progress = 0
            updateHorizontalCapsules(com.battery.analysis.manager.FloatingWindowManager.ALIGN_LEFT)
            com.battery.analysis.manager.FloatingWindowManager.updatePositionAndAppearance(ctx)
        }
        rbCenter.setOnClickListener {
            com.battery.analysis.manager.FloatingWindowManager.setHorizontalAlign(ctx, com.battery.analysis.manager.FloatingWindowManager.ALIGN_CENTER)
            com.battery.analysis.manager.FloatingWindowManager.setOffsetX(ctx, 0)
            seekHorizontal.progress = centerProgress
            updateHorizontalCapsules(com.battery.analysis.manager.FloatingWindowManager.ALIGN_CENTER)
            com.battery.analysis.manager.FloatingWindowManager.updatePositionAndAppearance(ctx)
        }
        rbRight.setOnClickListener {
            com.battery.analysis.manager.FloatingWindowManager.setHorizontalAlign(ctx, com.battery.analysis.manager.FloatingWindowManager.ALIGN_RIGHT)
            seekHorizontal.progress = screenWidth
            updateHorizontalCapsules(com.battery.analysis.manager.FloatingWindowManager.ALIGN_RIGHT)
            com.battery.analysis.manager.FloatingWindowManager.updatePositionAndAppearance(ctx)
        }

        seekHorizontal.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val offset = progress - centerProgress
                    com.battery.analysis.manager.FloatingWindowManager.setOffsetX(ctx, offset)
                    val newAlign = if (offset == 0) {
                        com.battery.analysis.manager.FloatingWindowManager.ALIGN_CENTER
                    } else {
                        com.battery.analysis.manager.FloatingWindowManager.ALIGN_CUSTOM
                    }
                    com.battery.analysis.manager.FloatingWindowManager.setHorizontalAlign(ctx, newAlign)
                    updateHorizontalCapsules(newAlign)
                    com.battery.analysis.manager.FloatingWindowManager.updatePositionAndAppearance(ctx)
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        // 水平位置 - 微调按钮
        setupRepeatClick(btnHorizontalMinus) {
            val targetProgress = (seekHorizontal.progress - 1).coerceAtLeast(0)
            if (targetProgress != seekHorizontal.progress) {
                seekHorizontal.progress = targetProgress
                val offset = targetProgress - centerProgress
                com.battery.analysis.manager.FloatingWindowManager.setOffsetX(ctx, offset)
                val newAlign = if (offset == 0) {
                    com.battery.analysis.manager.FloatingWindowManager.ALIGN_CENTER
                } else {
                    com.battery.analysis.manager.FloatingWindowManager.ALIGN_CUSTOM
                }
                com.battery.analysis.manager.FloatingWindowManager.setHorizontalAlign(ctx, newAlign)
                updateHorizontalCapsules(newAlign)
                com.battery.analysis.manager.FloatingWindowManager.updatePositionAndAppearance(ctx)
            }
        }

        // 水平位置 + 微调按钮
        setupRepeatClick(btnHorizontalPlus) {
            val targetProgress = (seekHorizontal.progress + 1).coerceAtMost(seekHorizontal.max)
            if (targetProgress != seekHorizontal.progress) {
                seekHorizontal.progress = targetProgress
                val offset = targetProgress - centerProgress
                com.battery.analysis.manager.FloatingWindowManager.setOffsetX(ctx, offset)
                val newAlign = if (offset == 0) {
                    com.battery.analysis.manager.FloatingWindowManager.ALIGN_CENTER
                } else {
                    com.battery.analysis.manager.FloatingWindowManager.ALIGN_CUSTOM
                }
                com.battery.analysis.manager.FloatingWindowManager.setHorizontalAlign(ctx, newAlign)
                updateHorizontalCapsules(newAlign)
                com.battery.analysis.manager.FloatingWindowManager.updatePositionAndAppearance(ctx)
            }
        }

        // 文字大小调节 (范围 5sp 至 20sp)
        val minFontSize = com.battery.analysis.manager.FloatingWindowManager.MIN_FONT_SIZE
        val maxFontSize = com.battery.analysis.manager.FloatingWindowManager.MAX_FONT_SIZE
        val currentFontSize = com.battery.analysis.manager.FloatingWindowManager.getFontSize(ctx)
        seekFontSize.max = maxFontSize - minFontSize
        seekFontSize.progress = currentFontSize - minFontSize
        tvFontSizeValue.text = "${currentFontSize} sp"

        seekFontSize.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val sizeSp = progress + minFontSize
                tvFontSizeValue.text = "${sizeSp} sp"
                if (fromUser) {
                    com.battery.analysis.manager.FloatingWindowManager.setFontSize(ctx, sizeSp)
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        // 文字大小 - 微调按钮
        setupRepeatClick(btnFontSizeMinus) {
            val targetSize = (seekFontSize.progress + minFontSize - 1).coerceAtLeast(minFontSize)
            val newProgress = targetSize - minFontSize
            if (newProgress != seekFontSize.progress) {
                seekFontSize.progress = newProgress
                tvFontSizeValue.text = "${targetSize} sp"
                com.battery.analysis.manager.FloatingWindowManager.setFontSize(ctx, targetSize)
            }
        }

        // 文字大小 + 微调按钮
        setupRepeatClick(btnFontSizePlus) {
            val targetSize = (seekFontSize.progress + minFontSize + 1).coerceAtMost(maxFontSize)
            val newProgress = targetSize - minFontSize
            if (newProgress != seekFontSize.progress) {
                seekFontSize.progress = newProgress
                tvFontSizeValue.text = "${targetSize} sp"
                com.battery.analysis.manager.FloatingWindowManager.setFontSize(ctx, targetSize)
            }
        }

        // 置于状态栏整行点击交互
        layoutAlignStatusBar?.setOnClickListener {
            switchStatusBar.toggle()
        }

        // 是否开启粗体显示开关交互
        switchFontBold.isChecked = initialFontBold
        switchFontBold.setOnCheckedChangeListener { _, isChecked ->
            com.battery.analysis.manager.FloatingWindowManager.setFontBold(ctx, isChecked)
        }
        layoutFontBold?.setOnClickListener {
            switchFontBold.toggle()
        }

        // 是否显示单位开关交互
        switchShowUnit.isChecked = initialShowUnit
        switchShowUnit.setOnCheckedChangeListener { _, isChecked ->
            com.battery.analysis.manager.FloatingWindowManager.setShowUnit(ctx, isChecked)
        }
        layoutShowUnit?.setOnClickListener {
            switchShowUnit.toggle()
        }

        val currentOpacity = com.battery.analysis.manager.FloatingWindowManager.getOpacity(ctx)
        seekOpacity.progress = currentOpacity
        tvOpacityValue.setUnitText("${currentOpacity}%")

        seekOpacity.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val validProgress = progress.coerceIn(20, 100)
                tvOpacityValue.setUnitText("${validProgress}%")
                if (fromUser) {
                    com.battery.analysis.manager.FloatingWindowManager.setOpacity(ctx, validProgress)
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        // 透明度加减步进微调按钮 (步长 5%)
        btnOpacityMinus.setOnClickListener {
            val current = seekOpacity.progress
            val target = (current - 5).coerceIn(20, 100)
            if (target != current) {
                seekOpacity.progress = target
                tvOpacityValue.setUnitText("${target}%")
                com.battery.analysis.manager.FloatingWindowManager.setOpacity(ctx, target)
            }
        }
        btnOpacityPlus.setOnClickListener {
            val current = seekOpacity.progress
            val target = (current + 5).coerceIn(20, 100)
            if (target != current) {
                seekOpacity.progress = target
                tvOpacityValue.setUnitText("${target}%")
                com.battery.analysis.manager.FloatingWindowManager.setOpacity(ctx, target)
            }
        }

        // 是否显示背景和边框开关交互
        switchShowBackground.isChecked = initialShowBackground
        switchShowBackground.setOnCheckedChangeListener { _, isChecked ->
            com.battery.analysis.manager.FloatingWindowManager.setShowBackground(ctx, isChecked)
        }
        layoutShowBackground?.setOnClickListener {
            switchShowBackground.toggle()
        }

        val updateThemeCapsules = { mode: Int ->
            rbThemeFollow.isChecked = (mode == com.battery.analysis.manager.FloatingWindowManager.THEME_FOLLOW_SYSTEM)
            rbThemeDark.isChecked = (mode == com.battery.analysis.manager.FloatingWindowManager.THEME_DARK)
            rbThemeLight.isChecked = (mode == com.battery.analysis.manager.FloatingWindowManager.THEME_LIGHT)
        }
        val currentThemeMode = com.battery.analysis.manager.FloatingWindowManager.getThemeMode(ctx)
        updateThemeCapsules(currentThemeMode)

        rbThemeFollow.setOnClickListener {
            com.battery.analysis.manager.FloatingWindowManager.setThemeMode(ctx, com.battery.analysis.manager.FloatingWindowManager.THEME_FOLLOW_SYSTEM)
            updateThemeCapsules(com.battery.analysis.manager.FloatingWindowManager.THEME_FOLLOW_SYSTEM)
            com.battery.analysis.manager.FloatingWindowManager.updatePositionAndAppearance(ctx)
        }
        rbThemeDark.setOnClickListener {
            com.battery.analysis.manager.FloatingWindowManager.setThemeMode(ctx, com.battery.analysis.manager.FloatingWindowManager.THEME_DARK)
            updateThemeCapsules(com.battery.analysis.manager.FloatingWindowManager.THEME_DARK)
            com.battery.analysis.manager.FloatingWindowManager.updatePositionAndAppearance(ctx)
        }
        rbThemeLight.setOnClickListener {
            com.battery.analysis.manager.FloatingWindowManager.setThemeMode(ctx, com.battery.analysis.manager.FloatingWindowManager.THEME_LIGHT)
            updateThemeCapsules(com.battery.analysis.manager.FloatingWindowManager.THEME_LIGHT)
            com.battery.analysis.manager.FloatingWindowManager.updatePositionAndAppearance(ctx)
        }

        val restoreInitialSettings = {
            com.battery.analysis.manager.FloatingWindowManager.setAlignStatusBar(ctx, initialAlignStatusBar)
            com.battery.analysis.manager.FloatingWindowManager.setPosY(ctx, initialRawY)
            com.battery.analysis.manager.FloatingWindowManager.setHorizontalAlign(ctx, initialAlign)
            com.battery.analysis.manager.FloatingWindowManager.setPosX(ctx, initialX)
            com.battery.analysis.manager.FloatingWindowManager.setOffsetX(ctx, initialOffsetX)
            com.battery.analysis.manager.FloatingWindowManager.setPercentX(ctx, initialPercentX)
            com.battery.analysis.manager.FloatingWindowManager.setPercentY(ctx, initialPercentY)
            com.battery.analysis.manager.FloatingWindowManager.setFontSize(ctx, initialFontSize)
            com.battery.analysis.manager.FloatingWindowManager.setFontBold(ctx, initialFontBold)
            com.battery.analysis.manager.FloatingWindowManager.setShowUnit(ctx, initialShowUnit)
            com.battery.analysis.manager.FloatingWindowManager.setOpacity(ctx, initialOpacity)
            com.battery.analysis.manager.FloatingWindowManager.setShowBackground(ctx, initialShowBackground)
            com.battery.analysis.manager.FloatingWindowManager.setThemeMode(ctx, initialThemeMode)
            com.battery.analysis.manager.FloatingWindowManager.updatePositionAndAppearance(ctx)
            updateFloatingPositionSummary()
        }

        val dialog = AlertDialog.Builder(ctx)
            .setView(dialogView)
            .create()

        btnCancel.setOnClickListener {
            restoreInitialSettings()
            dialog.dismiss()
        }

        dialog.setOnCancelListener {
            restoreInitialSettings()
        }

        btnConfirm.setOnClickListener {
            updateFloatingPositionSummary()
            dialog.dismiss()
        }

        showAndTrackDialog(dialog)
        applyLongContentDialogStyle(dialog, dialogView)
    }

    /**
     * 为按钮设置单击与长按自动连续触发动作监听。
     *
     * @param view 目标点击视图控件
     * @param action 步进触发操作 Lambda
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupRepeatClick(view: View, action: () -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        var isRepeating = false
        val repeatRunnable = object : Runnable {
            override fun run() {
                if (isRepeating) {
                    action()
                    handler.postDelayed(this, 50L)
                }
            }
        }

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    action()
                    isRepeating = true
                    handler.postDelayed(repeatRunnable, 350L)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isRepeating = false
                    handler.removeCallbacks(repeatRunnable)
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 初始化启用充、放电统计开关设置与联动控制。
     * 默认关闭，关闭时底部页签栏不显示充、耗电统计页签，且不开启充放电相关服务监测功能；
     * 打开后即时恢复页签显示并开启充放电相关服务监测。
     */
    private fun setupChargeDischargeStatsSettings() {
        val isEnabled = com.battery.analysis.service.BatteryMonitorService.isChargeDischargeStatsEnabled(requireContext())
        binding.switchChargeDischargeStats.isChecked = isEnabled

        binding.switchChargeDischargeStats.setOnCheckedChangeListener { _, isChecked ->
            if (com.battery.analysis.service.BatteryMonitorService.isChargeDischargeStatsEnabled(requireContext()) != isChecked) {
                com.battery.analysis.service.BatteryMonitorService.setChargeDischargeStatsEnabled(requireContext(), isChecked)
                (activity as? MainActivity)?.onChargeDischargeStatsToggled(isChecked)
                val tip = if (isChecked) {
                    getString(R.string.toast_charge_discharge_stats_enabled)
                } else {
                    getString(R.string.toast_charge_discharge_stats_disabled)
                }
                Toast.makeText(requireContext(), tip, Toast.LENGTH_SHORT).show()
            }
        }

        binding.layoutChargeDischargeStatsSetting.setOnClickListener {
            val newChecked = !binding.switchChargeDischargeStats.isChecked
            binding.switchChargeDischargeStats.isChecked = newChecked
        }
    }

    /**
     * 初始化充电时保持屏幕常亮设置交互。
     */
    private fun setupChargingKeepScreenOnSettings() {
        val chargingPrefs = requireContext().getSharedPreferences("charging_stats_prefs", Context.MODE_PRIVATE)
        binding.switchChargingKeepScreenOn.isChecked = chargingPrefs.getBoolean("pref_charging_keep_screen_on", false)
        binding.switchChargingKeepScreenOn.setOnCheckedChangeListener { _, isChecked ->
            chargingPrefs.edit().putBoolean("pref_charging_keep_screen_on", isChecked).apply()
        }
        binding.layoutChargingKeepScreenOnSetting.setOnClickListener {
            val newChecked = !binding.switchChargingKeepScreenOn.isChecked
            binding.switchChargingKeepScreenOn.isChecked = newChecked
        }
    }

    /**
     * 根据当前选定的毫秒数获取本地化刷新间隔文案。
     * 支持预设秒数及精确到小数点后 1 位的自定义秒数。
     *
     * @param intervalMs 刷新时间间隔毫秒数
     * @return 本地化文案
     */
    private fun getIntervalDisplay(intervalMs: Long): String {
        return when (intervalMs) {
            1000L -> getString(R.string.interval_1s)
            3000L -> getString(R.string.interval_3s)
            5000L -> getString(R.string.interval_5s)
            10000L -> getString(R.string.interval_10s)
            30000L -> getString(R.string.interval_30s)
            else -> {
                if (intervalMs % 1000L == 0L) {
                    "${intervalMs / 1000L} 秒"
                } else {
                    String.format(Locale.getDefault(), "%.1f 秒", intervalMs / 1000.0)
                }
            }
        }
    }

    /**
     * 初始化 Shizuku 权限与状态观察，并支持申请授权与解除已授权交互。
     */
    private fun setupShizukuSettings() {
        val mainActivity = activity as? MainActivity

        binding.layoutShizukuAuth.setOnClickListener {
            val isGranted = viewModel.isShizukuGranted.value
            if (isGranted) {
                showRevokeShizukuDialog()
            } else {
                mainActivity?.requestShizukuAuth()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.shizukuStatus, viewModel.isShizukuGranted) { status, isGranted ->
                    Pair(status, isGranted)
                }.collect { (status, isGranted) ->
                    if (isGranted) {
                        binding.tvShizukuStatus.text = getString(R.string.shizuku_status_authorized)
                        binding.tvShizukuStatus.setTextColor(Color.parseColor("#10B981"))
                        binding.tvShizukuAction.text = getString(R.string.shizuku_action_revoke)
                        binding.tvShizukuAction.setTextColor(Color.parseColor("#EF4444"))
                    } else {
                        val isNotRunning = status.contains("未运行", ignoreCase = true) || status.contains("Not Running", ignoreCase = true)
                        binding.tvShizukuStatus.text = if (isNotRunning) getString(R.string.shizuku_status_not_running) else getString(R.string.shizuku_status_unauthorized)
                        binding.tvShizukuStatus.setTextColor(if (isNotRunning) Color.parseColor("#EF4444") else Color.parseColor("#F59E0B"))
                        binding.tvShizukuAction.text = if (isNotRunning) getString(R.string.power_shizuku_btn_open) else getString(R.string.shizuku_action_authorize)
                        binding.tvShizukuAction.setTextColor(ContextCompat.getColor(requireContext(), R.color.nav_item_selected))
                    }
                }
            }
        }
    }

    /**
     * 弹窗提示用户确认是否解除已授权的 Shizuku 提权。
     * 采用统一高颜值卡片化美化布局，详细展示解除提权对内核电量计与 dumpsys 耗电账本的影响，
     * 并提供确认解除、前往 Shizuku 官方应用管理以及取消操作。
     */
    private fun showRevokeShizukuDialog() {
        val ctx = context ?: return
        val mainActivity = activity as? MainActivity ?: return

        val dialogView = layoutInflater.inflate(R.layout.dialog_shizuku_revoke_confirm, null)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btn_dialog_revoke_cancel)
        val btnConfirm = dialogView.findViewById<TextView>(R.id.btn_dialog_revoke_confirm)
        val btnOpenManager = dialogView.findViewById<TextView>(R.id.btn_dialog_revoke_open_manager)

        val dialog = AlertDialog.Builder(ctx)
            .setView(dialogView)
            .create()

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnConfirm.setOnClickListener {
            dialog.dismiss()
            mainActivity.revokeShizukuAuth()
        }

        btnOpenManager.setOnClickListener {
            dialog.dismiss()
            mainActivity.openShizukuApp()
        }

        showAndTrackDialog(dialog)
        applyDialogWindowStyle(dialog)
    }

    /**
     * 为现代化美化弹窗统一应用 Window 样式，包括系统级平滑弹出与收起动画、透明系统背景、92% 屏宽与居中布局，并支持指定弹窗高度屏幕占比。
     *
     * @param dialog 待配置样式的 [AlertDialog] 实例
     * @param heightRatio 弹窗高度占屏幕高度比例（若为空则使用 WRAP_CONTENT）
     */
    private fun applyDialogWindowStyle(dialog: AlertDialog, heightRatio: Float? = null) {
        dialog.window?.let { window ->
            window.setWindowAnimations(R.style.Animation_Dialog_CenterPop)
            window.setBackgroundDrawableResource(android.R.color.transparent)
            val displayMetrics = resources.displayMetrics
            val width = (displayMetrics.widthPixels * 0.92).toInt()
            val height = if (heightRatio != null && heightRatio > 0f) {
                (displayMetrics.heightPixels * heightRatio).toInt()
            } else {
                ViewGroup.LayoutParams.WRAP_CONTENT
            }
            window.setLayout(width, height)
            window.setGravity(android.view.Gravity.CENTER)
        }
    }

    /**
     * 为弹窗统一配置窗口样式，智能适配内容自适应包裹（未超 90%）与最大 90% 高度限制（超出时内部平滑滚动）。
     *
     * @param dialog 待配置样式的 [AlertDialog] 实例
     * @param dialogView 弹窗的根视图 [View]
     * @param widthRatio 弹窗宽度相对屏幕可用物理宽度的比例（默认为 0.92）
     */
    private fun applyLongContentDialogStyle(
        dialog: AlertDialog,
        dialogView: View,
        widthRatio: Double = 0.92
    ) {
        com.battery.analysis.util.DialogWindowHelper.applyAdaptiveDialogWindowStyle(
            dialog = dialog,
            dialogView = dialogView,
            widthRatio = widthRatio,
            maxHeightRatio = 0.90f
        )
    }

    /**
     * 初始化数据备份与恢复板块的点击事件。
     */
    private fun setupBackupRestoreSettings() {
        // 导出数据备份
        binding.layoutExportBackup.setOnClickListener {
            val timeStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val defaultFileName = "battery_backup_$timeStr.json"
            exportBackupLauncher.launch(defaultFileName)
        }

        // 导入并恢复数据
        binding.layoutRestoreBackup.setOnClickListener {
            restoreBackupLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*"))
        }
    }

    /**
     * 处理导出数据备份写入目标 URI。
     *
     * @param uri 用户选定的保存文件目标 URI
     */
    private fun handleExportBackup(uri: Uri) {
        val ctx = context ?: return
        viewModel.exportBackup(ctx, uri) { result ->
            result.onSuccess { summary ->
                Toast.makeText(
                    ctx,
                    getString(R.string.toast_backup_success, summary.historyCount, summary.chargingCount, summary.powerUsageCount),
                    Toast.LENGTH_LONG
                ).show()
            }.onFailure { exception ->
                Toast.makeText(ctx, getString(R.string.toast_backup_failed, exception.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 处理用户选择备份文件后的读取与预览。
     *
     * @param uri 用户选定的备份文件 URI
     */
    private fun handleRestoreBackupFileSelected(uri: Uri) {
        val ctx = context ?: return
        viewModel.readBackupPreview(ctx, uri) { result ->
            result.onSuccess { backupData ->
                showRestoreConfirmDialog(backupData)
            }.onFailure { exception ->
                Toast.makeText(ctx, getString(R.string.toast_restore_failed, exception.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 弹出现代化数据恢复确认对话框，展示备份元信息并支持模式选择。
     *
     * @param backupData 待恢复的备份数据对象
     */
    private fun showRestoreConfirmDialog(backupData: BackupData) {
        val ctx = context ?: return
        val dialogView = layoutInflater.inflate(R.layout.dialog_restore_backup_confirm, null)

        val tvTime = dialogView.findViewById<TextView>(R.id.tv_restore_backup_time)
        val tvAppVersion = dialogView.findViewById<TextView>(R.id.tv_restore_app_version)
        val tvRecordsCount = dialogView.findViewById<TextView>(R.id.tv_restore_records_count)
        val tvSettingsInfo = dialogView.findViewById<TextView>(R.id.tv_restore_settings_info)
        val rbOverwrite = dialogView.findViewById<RadioButton>(R.id.rb_restore_overwrite)
        val cbRestoreSettings = dialogView.findViewById<CheckBox>(R.id.cb_restore_settings)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btn_dialog_restore_cancel)
        val btnConfirm = dialogView.findViewById<TextView>(R.id.btn_dialog_restore_confirm)

        tvTime.text = backupData.backupTime.ifBlank { getString(R.string.unknown) }
        tvAppVersion.text = "v${backupData.appVersion}"
        tvRecordsCount.text = getString(
            R.string.restore_preview_records_format,
            backupData.historyRecords.size,
            backupData.chargingRecords.size,
            backupData.powerUsageRecords.size
        )

        val settingsList = mutableListOf<String>()
        val s = backupData.settings
        if (s.languageMode != null) settingsList.add(getString(R.string.setting_language))
        if (s.themeMode != null) settingsList.add(getString(R.string.setting_follow_system_theme))
        if (s.chargeDischargeStatsEnabled != null) settingsList.add(getString(R.string.setting_charge_discharge_stats))
        if (s.chargingKeepScreenOn != null) settingsList.add(getString(R.string.setting_charging_keep_screen_on))
        if (s.powerStatsMode != null) settingsList.add(getString(R.string.setting_power_mode))
        if (s.notificationDisplayEnabled != null || s.screenOnIntervalMs != null || s.screenOffIntervalMs != null || s.batteryServiceEnabled != null) {
            settingsList.add(getString(R.string.settings_keep_alive_category))
        }
        if (s.bootAutoStartEnabled != null) settingsList.add(getString(R.string.settings_boot_start_title))
        if (s.floatingEnabled != null || s.floatingShowPower != null || s.floatingShowTemp != null || s.floatingOpacity != null || s.floatingFontSize != null || s.floatingFontBold != null || s.floatingPosPercentX != null || s.floatingPosPercentY != null) {
            settingsList.add(getString(R.string.setting_restore_floating_window))
        }
        if (s.currentCalibrationMultiplier != null || s.currentCalibrationDualCell != null || s.currentCalibrationInvertPolarity != null || s.currentCalibrationCoulombFallback != null) {
            settingsList.add(getString(R.string.setting_restore_current_calibration))
        }
        if (s.powerDisplayUnit != null || s.enableBackgroundStats != null || s.powerUnitDialogDoNotShow != null) {
            settingsList.add(getString(R.string.setting_restore_power_usage_pref))
        }
        if ((s.timelineSavedMetrics != null && s.timelineSavedMetrics.isNotEmpty()) ||
            (s.timelineSavedChargingMetrics != null && s.timelineSavedChargingMetrics.isNotEmpty())) {
            settingsList.add(getString(R.string.setting_restore_timeline_metrics))
        }

        cbRestoreSettings.text = getString(R.string.dialog_restore_sync_settings)
        if (settingsList.isNotEmpty()) {
            tvSettingsInfo.text = settingsList.joinToString("、")
            cbRestoreSettings.isEnabled = true
            cbRestoreSettings.isChecked = true
        } else {
            tvSettingsInfo.text = "—"
            cbRestoreSettings.isEnabled = false
            cbRestoreSettings.isChecked = false
        }

        val dialog = AlertDialog.Builder(ctx)
            .setView(dialogView)
            .create()

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnConfirm.setOnClickListener {
            val isOverwrite = rbOverwrite.isChecked
            val restoreSettings = cbRestoreSettings.isChecked

            viewModel.restoreBackup(ctx, backupData, isOverwrite, restoreSettings) { result ->
                result.onSuccess { summary ->
                    if (restoreSettings) {
                        syncSettingsUiState()
                    }
                    val modeText = if (isOverwrite) getString(R.string.dialog_restore_mode_overwrite) else getString(R.string.dialog_restore_mode_merge)
                    Toast.makeText(
                        ctx,
                        getString(R.string.toast_restore_success, modeText, summary.historyCount, summary.chargingCount, summary.powerUsageCount),
                        Toast.LENGTH_LONG
                    ).show()
                    dialog.dismiss()
                }.onFailure { exception ->
                    Toast.makeText(ctx, getString(R.string.toast_restore_failed, exception.message), Toast.LENGTH_LONG).show()
                }
            }
        }

        showAndTrackDialog(dialog)
        applyDialogWindowStyle(dialog)
    }

    /**
     * 在恢复偏好配置后同步刷新当前设置界面的 Switch 与文本等控件状态。
     */
    private fun syncSettingsUiState() {
        val currentThemeMode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        val isFollowSystem = currentThemeMode == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        val isDarkMode = currentThemeMode == AppCompatDelegate.MODE_NIGHT_YES

        binding.switchFollowSystem.setOnCheckedChangeListener(null)
        binding.switchDarkMode.setOnCheckedChangeListener(null)

        binding.switchFollowSystem.isChecked = isFollowSystem
        binding.switchDarkMode.isChecked = isDarkMode
        binding.switchDarkMode.isEnabled = !isFollowSystem
        binding.layoutDarkMode.alpha = if (isFollowSystem) 0.5f else 1.0f

        setupLanguageSettings()
        setupThemeSettings()
        setupChargingKeepScreenOnSettings()

        // 同步充放电统计开关及底部导航栏
        val isStatsEnabled = com.battery.analysis.service.BatteryMonitorService.isChargeDischargeStatsEnabled(requireContext())
        binding.switchChargeDischargeStats.isChecked = isStatsEnabled
        (activity as? MainActivity)?.onChargeDischargeStatsToggled(isStatsEnabled)

        // 同步耗电检测模式副标题
        val powerManager = com.battery.analysis.manager.PowerUsageManager.getInstance(requireContext())
        updatePowerModeDisplay(powerManager.getSelectedMode())

        // 同步常驻通知栏监控开关与刷新采样间隔展示
        val isNotificationDisplayEnabled = com.battery.analysis.service.BatteryMonitorService.isNotificationDisplayEnabled(requireContext())
        binding.switchKeepAliveService.isChecked = isNotificationDisplayEnabled
        binding.switchBootAutoStart.isChecked = com.battery.analysis.service.BatteryMonitorService.isBootAutoStartEnabled(requireContext())
        updateKeepAliveIntervalDisplay()

        // 同步悬浮窗与硬件电流校准控件展示
        updateFloatingWindowDisplay()
        updateCurrentCalibrationDisplay()

        AppCompatDelegate.setDefaultNightMode(currentThemeMode)
    }


    /**
     * 初始化关于信息与应用版本号展示。
     * 动态读取 PackageManager 中的 versionName 字段进行展示。
     */
    private fun setupAboutSection() {
        val versionName = try {
            val pInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            pInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
        binding.tvAppVersion.text = "v$versionName"
    }

    /**
     * 初始化后台常驻与保活防杀设置交互逻辑。
     * 绑定前台服务通知栏显示开关、亮屏与息屏刷新间隔选择气泡、开机自启动开关、电池优化白名单申请与防杀加锁教程弹窗。
     */
    private fun setupKeepAliveSettings() {
        setupAccessibilityKeepAliveSettings()
        setupPrivilegedDaemonSettings()

        val isDisplayEnabled = com.battery.analysis.service.BatteryMonitorService.isNotificationDisplayEnabled(requireContext())
        binding.switchKeepAliveService.isChecked = isDisplayEnabled
        updateKeepAliveIntervalDisplay()

        binding.switchKeepAliveService.setOnCheckedChangeListener { _, isChecked ->
            if (com.battery.analysis.service.BatteryMonitorService.isNotificationDisplayEnabled(requireContext()) != isChecked) {
                com.battery.analysis.service.BatteryMonitorService.setNotificationDisplayEnabled(requireContext(), isChecked)
                if (isChecked) {
                    com.battery.analysis.service.BatteryMonitorService.start(requireContext())
                }
                // 无论开启还是关闭，绝对不停止后台服务与采样，履行前台服务契约，仅切换通知形态（开: 实时监控; 关: 电池后台静默监控）
                com.battery.analysis.service.BatteryMonitorService.updateNotificationVisibility(requireContext())
            }
        }

        setupScreenOnIntervalPicker()
        setupScreenOffIntervalPicker()

        binding.switchBootAutoStart.isChecked = com.battery.analysis.service.BatteryMonitorService.isBootAutoStartEnabled(requireContext())
        binding.switchBootAutoStart.setOnCheckedChangeListener { _, isChecked ->
            com.battery.analysis.service.BatteryMonitorService.setBootAutoStartEnabled(requireContext(), isChecked)
        }

        updateBatteryOptimizationDisplay()
        binding.layoutBatteryOptimization.setOnClickListener {
            requestIgnoreBatteryOptimization()
        }

        binding.layoutLockRecentsGuide.setOnClickListener {
            showLockRecentsGuideDialog()
        }
    }

    /**
     * 初始化无障碍秒级自愈保活（方案一：免 Root / 免 ADB）板块的交互与控制逻辑。
     * 绑定一键跳转系统无障碍设置页面的点击事件。
     */
    private fun setupAccessibilityKeepAliveSettings() {
        updateAccessibilityStatusDisplay()

        binding.btnAccessibilityToggle.setOnClickListener {
            KeepAliveAccessibilityService.openAccessibilitySettings(requireContext())
        }
        binding.cardAccessibilityKeepAlive.setOnClickListener {
            KeepAliveAccessibilityService.openAccessibilitySettings(requireContext())
        }
    }

    /**
     * 刷新无障碍秒级自愈保活在界面上的运行状态徽章与按钮文案。
     */
    private fun updateAccessibilityStatusDisplay() {
        val isEnabled = KeepAliveAccessibilityService.isAccessibilityEnabled(requireContext())
        if (isEnabled) {
            binding.tvAccessibilityStatus.text = getString(R.string.settings_accessibility_status_enabled)
            binding.tvAccessibilityStatus.setTextColor(Color.parseColor("#10B981"))
            binding.tvAccessibilityStatus.setBackgroundResource(R.drawable.bg_badge_btn)

            binding.btnAccessibilityToggle.text = getString(R.string.settings_accessibility_btn_manage)
            binding.btnAccessibilityToggle.setBackgroundResource(R.drawable.bg_badge_btn)
            binding.btnAccessibilityToggle.setTextColor(Color.parseColor("#10B981"))
        } else {
            binding.tvAccessibilityStatus.text = getString(R.string.settings_accessibility_status_disabled)
            binding.tvAccessibilityStatus.setTextColor(Color.parseColor("#9CA3AF"))
            binding.tvAccessibilityStatus.setBackgroundResource(R.drawable.bg_setting_card_item)

            binding.btnAccessibilityToggle.text = getString(R.string.settings_accessibility_btn_enable)
            binding.btnAccessibilityToggle.setBackgroundResource(R.drawable.bg_dialog_btn_primary)
            binding.btnAccessibilityToggle.setTextColor(Color.WHITE)
        }
    }

    /**
     * 初始化特权独立守护进程（方案二：终极防杀）板块的交互与控制逻辑。
     * 绑定启动/停止按钮点击事件与 ADB 启动指南弹窗。
     */
    private fun setupPrivilegedDaemonSettings() {
        updateDaemonStatusDisplay()

        binding.btnDaemonToggle.setOnClickListener {
            val status = DaemonManager.getDaemonStatus()
            if (status.isRunning) {
                // 当前正在运行，执行停止
                val result = DaemonManager.stopDaemon()
                result.onSuccess {
                    Toast.makeText(requireContext(), getString(R.string.toast_daemon_stopped), Toast.LENGTH_SHORT).show()
                    updateDaemonStatusDisplay()
                }.onFailure { err ->
                    Toast.makeText(requireContext(), err.message ?: "停止失败", Toast.LENGTH_SHORT).show()
                }
            } else {
                // 当前未运行，尝试智能提权拉起
                if (DaemonManager.isRootAvailable()) {
                    val result = DaemonManager.startWithRoot(requireContext())
                    result.onSuccess {
                        Toast.makeText(requireContext(), getString(R.string.toast_daemon_started), Toast.LENGTH_SHORT).show()
                        view?.postDelayed({ updateDaemonStatusDisplay() }, 1000L)
                        view?.postDelayed({ updateDaemonStatusDisplay() }, 2500L)
                    }.onFailure { err ->
                        Toast.makeText(requireContext(), err.message ?: "Root 启动失败", Toast.LENGTH_LONG).show()
                    }
                } else if (DaemonManager.isShizukuAvailable()) {
                    val result = DaemonManager.startWithShizuku(requireContext())
                    result.onSuccess {
                        Toast.makeText(requireContext(), getString(R.string.toast_daemon_started), Toast.LENGTH_SHORT).show()
                        view?.postDelayed({ updateDaemonStatusDisplay() }, 1000L)
                        view?.postDelayed({ updateDaemonStatusDisplay() }, 2500L)
                    }.onFailure { err ->
                        Toast.makeText(requireContext(), err.message ?: "Shizuku 启动失败", Toast.LENGTH_LONG).show()
                    }
                } else {
                    // 既无 Root 也无可用 Shizuku，弹出 ADB 指南引导
                    showDaemonAdbGuideDialog()
                }
            }
        }

        binding.btnDaemonAdbGuide.setOnClickListener {
            showDaemonAdbGuideDialog()
        }
    }

    /**
     * 刷新特权独立守护进程在界面上的运行状态徽章与详细信息文本。
     */
    private fun updateDaemonStatusDisplay() {
        val status = DaemonManager.getDaemonStatus()
        if (status.isRunning) {
            val statusText = if (status.isRoot()) {
                getString(R.string.settings_daemon_status_running_root)
            } else {
                getString(R.string.settings_daemon_status_running_shell)
            }
            binding.tvDaemonStatus.text = statusText
            binding.tvDaemonStatus.setTextColor(Color.parseColor("#10B981"))
            binding.tvDaemonStatus.setBackgroundResource(R.drawable.bg_badge_btn)

            binding.tvDaemonInfo.visibility = View.VISIBLE
            binding.tvDaemonInfo.text = getString(R.string.settings_daemon_info_format, status.pid, status.reviveCount)

            binding.btnDaemonToggle.text = getString(R.string.settings_daemon_btn_stop)
            binding.btnDaemonToggle.setBackgroundResource(R.drawable.bg_setting_card_item)
            binding.btnDaemonToggle.setTextColor(Color.parseColor("#EF4444"))
        } else {
            binding.tvDaemonStatus.text = getString(R.string.settings_daemon_status_stopped)
            binding.tvDaemonStatus.setTextColor(Color.parseColor("#9CA3AF"))
            binding.tvDaemonStatus.setBackgroundResource(R.drawable.bg_setting_card_item)

            binding.tvDaemonInfo.visibility = View.GONE

            val startBtnText = if (DaemonManager.isRootAvailable()) {
                getString(R.string.settings_daemon_btn_start_root)
            } else if (DaemonManager.isShizukuAvailable()) {
                getString(R.string.settings_daemon_btn_start_shizuku)
            } else {
                getString(R.string.settings_daemon_btn_start)
            }
            binding.btnDaemonToggle.text = startBtnText
            binding.btnDaemonToggle.setBackgroundResource(R.drawable.bg_dialog_btn_primary)
            binding.btnDaemonToggle.setTextColor(Color.WHITE)
        }
    }

    /**
     * 弹出现代化 ADB 特权守护进程启动指南对话框，展示启动与停止命令行，高度占比 90% 且底部操作栏吸底。
     */
    private fun showDaemonAdbGuideDialog() {
        val ctx = context ?: return
        val dialogView = layoutInflater.inflate(R.layout.dialog_daemon_adb_guide, null)

        val tvCmdPreview = dialogView.findViewById<TextView>(R.id.tv_daemon_adb_cmd_preview)
        val tvStopCmdPreview = dialogView.findViewById<TextView>(R.id.tv_daemon_adb_stop_cmd_preview)
        val btnCopyCmd = dialogView.findViewById<TextView>(R.id.btn_copy_adb_cmd)
        val btnCopyStopCmd = dialogView.findViewById<TextView>(R.id.btn_copy_adb_stop_cmd)
        val btnClose = dialogView.findViewById<TextView>(R.id.btn_dialog_close)

        val adbLaunchCmd = DaemonManager.getAdbCommand()
        val adbStopCmd = DaemonManager.getAdbStopCommand()

        tvCmdPreview.text = adbLaunchCmd
        tvStopCmdPreview?.text = adbStopCmd

        val dialog = AlertDialog.Builder(ctx)
            .setView(dialogView)
            .create()

        btnCopyCmd.setOnClickListener {
            copyToClipboard(adbLaunchCmd, "ADB Launch Command")
            Toast.makeText(ctx, getString(R.string.toast_copy_success), Toast.LENGTH_SHORT).show()
        }

        btnCopyStopCmd.setOnClickListener {
            copyToClipboard(adbStopCmd, "ADB Stop Command")
            Toast.makeText(ctx, getString(R.string.toast_copy_success), Toast.LENGTH_SHORT).show()
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        showAndTrackDialog(dialog)
        applyLongContentDialogStyle(dialog, dialogView)
    }

    /**
     * 将指定文本内容复制到系统剪贴板。
     *
     * @param text 要复制的字符串文本
     * @param label 剪贴板内容标签
     */
    private fun copyToClipboard(text: String, label: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
    }

    /**
     * 更新亮屏刷新间隔与息屏采样间隔的副标题文本。
     * 常驻通知栏显示设置不影响亮屏和息屏采样间隔的可用状态。
     */
    private fun updateKeepAliveIntervalDisplay() {
        val onInterval = com.battery.analysis.service.BatteryMonitorService.getScreenOnIntervalMs(requireContext())
        val offInterval = com.battery.analysis.service.BatteryMonitorService.getScreenOffIntervalMs(requireContext())

        binding.tvCurrentScreenOnInterval.text = when (onInterval) {
            com.battery.analysis.service.BatteryMonitorService.INTERVAL_NEVER -> getString(R.string.interval_never)
            else -> getIntervalDisplay(onInterval)
        }
        binding.tvCurrentScreenOffInterval.text = when (offInterval) {
            com.battery.analysis.service.BatteryMonitorService.INTERVAL_NEVER -> getString(R.string.interval_never)
            0L -> getString(R.string.interval_smart_eco)
            else -> getIntervalDisplay(offInterval)
        }

        // 常驻通知栏显示不影响亮屏和息屏采样间隔，保持始终可用
        binding.layoutScreenOnInterval.alpha = 1.0f
        binding.layoutScreenOffInterval.alpha = 1.0f
        binding.layoutScreenOnInterval.isEnabled = true
        binding.layoutScreenOffInterval.isEnabled = true
    }

    /**
     * 初始化亮屏监控刷新间隔选择气泡菜单。
     * 支持在不采样(-1L)、1秒、3秒、5秒、10秒、30秒及自定义间自由切换。
     */
    private fun setupScreenOnIntervalPicker() {
        val intervalValues = listOf(
            com.battery.analysis.service.BatteryMonitorService.INTERVAL_NEVER,
            1000L, 3000L, 5000L, 10000L, 30000L
        )
        binding.layoutScreenOnInterval.setOnClickListener {
            val currentVal = com.battery.analysis.service.BatteryMonitorService.getScreenOnIntervalMs(requireContext())
            val popupView = layoutInflater.inflate(R.layout.popup_interval_picker, null)
            val density = resources.displayMetrics.density
            val popupWidth = (160 * density).toInt()

            val popupWindow = trackPopup(
                android.widget.PopupWindow(
                    popupView,
                    popupWidth,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    true
                ).apply {
                    isOutsideTouchable = true
                    isFocusable = true
                    animationStyle = R.style.Animation_PopupTopRight
                }
            )

            val optionViews = listOf(
                popupView.findViewById<TextView>(R.id.tv_opt_never),
                popupView.findViewById<TextView>(R.id.tv_opt_1s),
                popupView.findViewById<TextView>(R.id.tv_opt_3s),
                popupView.findViewById<TextView>(R.id.tv_opt_5s),
                popupView.findViewById<TextView>(R.id.tv_opt_10s),
                popupView.findViewById<TextView>(R.id.tv_opt_30s)
            )
            val tvCustom = popupView.findViewById<TextView>(R.id.tv_opt_custom)

            optionViews[0].text = getString(R.string.interval_never)
            optionViews[1].text = getString(R.string.interval_1s)
            optionViews[2].text = getString(R.string.interval_3s)
            optionViews[3].text = getString(R.string.interval_5s)
            optionViews[4].text = getString(R.string.interval_10s)
            optionViews[5].text = getString(R.string.interval_30s)

            val normalColor = ContextCompat.getColor(requireContext(), R.color.popup_item_text)
            val activeColor = Color.parseColor("#2196F3")

            val isCustomActive = currentVal !in intervalValues
            tvCustom.setTextColor(if (isCustomActive) activeColor else normalColor)
            tvCustom.setOnClickListener {
                popupWindow.dismiss()
                showCustomIntervalDialog(isScreenOn = true)
            }

            optionViews.forEachIndexed { index, textView ->
                val intervalVal = intervalValues[index]
                textView.setTextColor(if (intervalVal == currentVal) activeColor else normalColor)
                textView.setOnClickListener {
                    com.battery.analysis.service.BatteryMonitorService.setScreenOnIntervalMs(requireContext(), intervalVal)
                    updateKeepAliveIntervalDisplay()
                    (activity as? MainActivity)?.checkAndStartBatteryMonitorService()
                    popupWindow.dismiss()
                }
            }

            popupWindow.showAsDropDown(
                binding.layoutScreenOnInterval,
                0,
                (4 * density).toInt(),
                android.view.Gravity.END
            )
        }
    }

    /**
     * 初始化息屏待机采样间隔选择气泡菜单。
     * 支持在不采样(-1L)、智能省电(0L)、1秒、3秒、5秒、10秒、30秒及自定义间自由切换。
     */
    private fun setupScreenOffIntervalPicker() {
        val intervalValues = listOf(
            com.battery.analysis.service.BatteryMonitorService.INTERVAL_NEVER,
            0L, 1000L, 3000L, 5000L, 10000L, 30000L
        )
        binding.layoutScreenOffInterval.setOnClickListener {
            val currentVal = com.battery.analysis.service.BatteryMonitorService.getScreenOffIntervalMs(requireContext())
            val popupView = layoutInflater.inflate(R.layout.popup_screen_off_interval_picker, null)
            val density = resources.displayMetrics.density
            val popupWidth = (180 * density).toInt()

            val popupWindow = trackPopup(
                android.widget.PopupWindow(
                    popupView,
                    popupWidth,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    true
                ).apply {
                    isOutsideTouchable = true
                    isFocusable = true
                    animationStyle = R.style.Animation_PopupTopRight
                }
            )

            val optionViews = listOf(
                popupView.findViewById<TextView>(R.id.tv_opt_never),
                popupView.findViewById<TextView>(R.id.tv_opt_smart_eco),
                popupView.findViewById<TextView>(R.id.tv_opt_1s),
                popupView.findViewById<TextView>(R.id.tv_opt_3s),
                popupView.findViewById<TextView>(R.id.tv_opt_5s),
                popupView.findViewById<TextView>(R.id.tv_opt_10s),
                popupView.findViewById<TextView>(R.id.tv_opt_30s)
            )
            val tvCustom = popupView.findViewById<TextView>(R.id.tv_opt_custom)

            optionViews[0].text = getString(R.string.interval_never)
            optionViews[1].text = getString(R.string.interval_smart_eco)
            optionViews[2].text = getString(R.string.interval_1s)
            optionViews[3].text = getString(R.string.interval_3s)
            optionViews[4].text = getString(R.string.interval_5s)
            optionViews[5].text = getString(R.string.interval_10s)
            optionViews[6].text = getString(R.string.interval_30s)

            val normalColor = ContextCompat.getColor(requireContext(), R.color.popup_item_text)
            val activeColor = Color.parseColor("#2196F3")

            val isCustomActive = currentVal !in intervalValues
            tvCustom.setTextColor(if (isCustomActive) activeColor else normalColor)
            tvCustom.setOnClickListener {
                popupWindow.dismiss()
                showCustomIntervalDialog(isScreenOn = false)
            }

            optionViews.forEachIndexed { index, textView ->
                val intervalVal = intervalValues[index]
                textView.setTextColor(if (intervalVal == currentVal) activeColor else normalColor)
                textView.setOnClickListener {
                    com.battery.analysis.service.BatteryMonitorService.setScreenOffIntervalMs(requireContext(), intervalVal)
                    updateKeepAliveIntervalDisplay()
                    (activity as? MainActivity)?.checkAndStartBatteryMonitorService()
                    popupWindow.dismiss()
                }
            }

            popupWindow.showAsDropDown(
                binding.layoutScreenOffInterval,
                0,
                (4 * density).toInt(),
                android.view.Gravity.END
            )
        }
    }

    /**
     * 弹出自定义刷新或采样间隔设置对话框，支持精确到小数点后 1 位，以秒为单位。
     *
     * @param isScreenOn 是否针对亮屏监控刷新间隔配置（true: 亮屏, false: 息屏）
     */
    private fun showCustomIntervalDialog(isScreenOn: Boolean) {
        val ctx = requireContext()
        val currentValMs = if (isScreenOn) {
            com.battery.analysis.service.BatteryMonitorService.getScreenOnIntervalMs(ctx)
        } else {
            com.battery.analysis.service.BatteryMonitorService.getScreenOffIntervalMs(ctx)
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_custom_interval, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tv_dialog_interval_title)
        val etSeconds = dialogView.findViewById<android.widget.EditText>(R.id.et_interval_seconds)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btn_dialog_interval_cancel)
        val btnConfirm = dialogView.findViewById<TextView>(R.id.btn_dialog_interval_confirm)

        tvTitle.text = if (isScreenOn) {
            getString(R.string.dialog_custom_screen_on_interval_title)
        } else {
            getString(R.string.dialog_custom_screen_off_interval_title)
        }

        if (currentValMs > 0L) {
            val secDouble = currentValMs / 1000.0
            val initText = if (currentValMs % 1000L == 0L) {
                "${currentValMs / 1000L}"
            } else {
                String.format(Locale.getDefault(), "%.1f", secDouble)
            }
            etSeconds.setText(initText)
            etSeconds.setSelection(initText.length)
        }

        val dialog = AlertDialog.Builder(ctx)
            .setView(dialogView)
            .create()

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnConfirm.setOnClickListener {
            val inputStr = etSeconds.text?.toString()?.trim() ?: ""
            val seconds = inputStr.toDoubleOrNull()
            if (seconds == null || seconds <= 0.0) {
                Toast.makeText(ctx, getString(R.string.dialog_custom_interval_invalid), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val roundedSeconds = Math.round(seconds * 10.0) / 10.0
            val targetMs = Math.round(roundedSeconds * 1000.0)

            if (isScreenOn) {
                com.battery.analysis.service.BatteryMonitorService.setScreenOnIntervalMs(ctx, targetMs)
            } else {
                com.battery.analysis.service.BatteryMonitorService.setScreenOffIntervalMs(ctx, targetMs)
            }

            updateKeepAliveIntervalDisplay()
            (activity as? MainActivity)?.checkAndStartBatteryMonitorService()
            dialog.dismiss()
        }

        showAndTrackDialog(dialog)
        applyDialogWindowStyle(dialog)
    }

    /**
     * 更新系统电池优化白名单状态文字与高亮颜色展示。
     */
    private fun updateBatteryOptimizationDisplay() {
        val isIgnoring = isIgnoringBatteryOptimizations()
        if (isIgnoring) {
            binding.tvBatteryOptimizationStatus.text = getString(R.string.toast_battery_optimization_granted)
            binding.tvBatteryOptimizationStatus.setTextColor(Color.parseColor("#10B981"))
        } else {
            binding.tvBatteryOptimizationStatus.text = getString(R.string.settings_battery_optimization_btn)
            binding.tvBatteryOptimizationStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.nav_item_selected))
        }
    }

    /**
     * 检测当前应用是否已处于系统忽略电池优化（即已加入电池优化白名单）状态。
     *
     * @return 若已在白名单中返回 true，否则返回 false
     */
    private fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = requireContext().getSystemService(Context.POWER_SERVICE) as? PowerManager
            return pm?.isIgnoringBatteryOptimizations(requireContext().packageName) ?: false
        }
        return true
    }

    /**
     * 主动发起申请加入系统电池优化白名单弹窗或跳转至设置页。
     */
    private fun requestIgnoreBatteryOptimization() {
        if (isIgnoringBatteryOptimizations()) {
            Toast.makeText(requireContext(), getString(R.string.toast_battery_optimization_granted), Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Toast.makeText(requireContext(), getString(R.string.toast_battery_optimization_request), Toast.LENGTH_SHORT).show()
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${requireContext().packageName}")
                }
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(fallbackIntent)
                } catch (_: Exception) {
                    Toast.makeText(requireContext(), e.message ?: "", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 弹出各主流手机厂商后台防杀与多任务卡片加锁图文教程对话框。
     * 采用现代化卡片布局展示各系统防杀步骤，并提供一键前往系统应用设置页面的快捷入口。
     */
    private fun showLockRecentsGuideDialog() {
        val ctx = context ?: return
        val dialogView = layoutInflater.inflate(R.layout.dialog_lock_recents_guide, null)
        val dialog = AlertDialog.Builder(ctx)
            .setView(dialogView)
            .create()

        val btnSettings = dialogView.findViewById<TextView>(R.id.btn_dialog_lock_recents_settings)
        val btnClose = dialogView.findViewById<TextView>(R.id.btn_dialog_lock_recents_close)

        btnSettings.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", ctx.packageName, null)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(ctx, e.message ?: "", Toast.LENGTH_SHORT).show()
            }
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        showAndTrackDialog(dialog)
        applyLongContentDialogStyle(dialog, dialogView)
    }


    /**
     * 界面不可见生命周期回调。
     * 强制关闭所有前台活跃对话框与气泡弹窗以释放系统 Window 与图形缓冲区。
     */
    override fun onStop() {
        super.onStop()
        dismissAllActiveWindows()
    }

    /**
     * 视图销毁时的清理工作，释放所有活跃 Window 并清空视图绑定引用。
     */
    override fun onDestroyView() {
        dismissAllActiveWindows()
        (activity as? MainActivity)?.unregisterBottomInsetListener(bottomInsetListener)
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /**
         * 静态工厂方法，用于创建 [SettingsFragment] 实例。
         *
         * @return 新建的 [SettingsFragment]
         */
        fun newInstance(): SettingsFragment {
            return SettingsFragment()
        }
    }
}
