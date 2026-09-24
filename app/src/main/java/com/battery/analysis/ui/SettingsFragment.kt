package com.battery.analysis.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
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
import com.battery.analysis.MainActivity
import com.battery.analysis.R
import com.battery.analysis.daemon.DaemonManager
import com.battery.analysis.databinding.FragmentSettingsBinding
import com.battery.analysis.service.KeepAliveAccessibilityService
import com.battery.analysis.manager.LanguageManager
import com.battery.analysis.model.BackupData
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
    }

    /**
     * 配置设置界面滚动与手势监听，联动控制 MainActivity 底部页签栏的显示与隐藏。
     */
    private fun setupScrollListener() {
        binding.scrollView.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            val dy = scrollY - oldScrollY
            if (dy > 4) {
                // 手指向上滑动列表，灵敏联动隐藏底部页签栏
                (activity as? MainActivity)?.setBottomNavigationVisibility(false)
            } else if (dy < -8 || (dy < 0 && scrollY <= 0)) {
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
            tvRawCurrent.text = String.format(java.util.Locale.getDefault(), "%.1f mA", Math.abs(cachedRawCurrentMa))
            tvCalibratedCurrent.text = String.format(java.util.Locale.getDefault(), "%.1f mA", calibratedMa)
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

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

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
        applyDialogWindowStyle(dialog)
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
     * 为现代化美化弹窗统一应用 Window 样式，包括透明系统背景、92% 屏宽与居中布局。
     *
     * @param dialog 待配置样式的 [AlertDialog] 实例
     */
    private fun applyDialogWindowStyle(dialog: AlertDialog) {
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            val width = (resources.displayMetrics.widthPixels * 0.92).toInt()
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setGravity(android.view.Gravity.CENTER)
        }
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
        if (s.notificationDisplayEnabled != null || s.screenOnIntervalMs != null || s.screenOffIntervalMs != null) {
            settingsList.add(getString(R.string.settings_keep_alive_category))
        }
        if (s.bootAutoStartEnabled != null) settingsList.add(getString(R.string.settings_boot_start_title))

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
     * 弹出现代化 ADB 特权守护进程启动指南对话框，展示命令行并支持一键复制。
     */
    private fun showDaemonAdbGuideDialog() {
        val ctx = context ?: return
        val dialogView = layoutInflater.inflate(R.layout.dialog_daemon_adb_guide, null)

        val tvCmdPreview = dialogView.findViewById<TextView>(R.id.tv_daemon_adb_cmd_preview)
        val btnCopyCmd = dialogView.findViewById<TextView>(R.id.btn_copy_adb_cmd)
        val btnCopyStopCmd = dialogView.findViewById<TextView>(R.id.btn_copy_adb_stop_cmd)
        val btnClose = dialogView.findViewById<TextView>(R.id.btn_dialog_close)

        val adbLaunchCmd = DaemonManager.getAdbCommand()
        val adbStopCmd = DaemonManager.getAdbStopCommand()

        tvCmdPreview.text = adbLaunchCmd

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
        applyDialogWindowStyle(dialog)
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
        applyDialogWindowStyle(dialog)
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
