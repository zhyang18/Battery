package com.battery.analysis.ui

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
import com.battery.analysis.databinding.FragmentSettingsBinding
import com.battery.analysis.manager.LanguageManager
import com.battery.analysis.model.BackupData
import com.battery.analysis.viewmodel.BatteryViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
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
        setupRefreshSettings()
        setupPowerModeSettings()
        setupSamplingModeSettings()
        setupShizukuSettings()
        setupKeepAliveSettings()
        setupBackupRestoreSettings()
        setupHelpSection()
        setupAboutSection()
    }

    /**
     * 界面恢复至前台时的生命周期回调，同步最新的耗电模式、采样精度副标题及 Shizuku 连接与权限状态。
     */
    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.updateShizukuStatusState()
        val powerManager = com.battery.analysis.manager.PowerUsageManager.getInstance(requireContext())
        updatePowerModeDisplay(powerManager.getSelectedMode())
        updateSamplingModeDisplay(powerManager.getSamplingMode())

        val chargingPrefs = requireContext().getSharedPreferences("charging_stats_prefs", Context.MODE_PRIVATE)
        binding.switchChargingKeepScreenOn.isChecked = chargingPrefs.getBoolean("pref_charging_keep_screen_on", false)

        binding.switchKeepAliveService.isChecked = com.battery.analysis.service.BatteryMonitorService.isServiceEnabled(requireContext())
        binding.switchBootAutoStart.isChecked = com.battery.analysis.service.BatteryMonitorService.isBootAutoStartEnabled(requireContext())
        updateBatteryOptimizationDisplay()
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
            val popupWidth = (230 * density).toInt()

            val popupWindow = android.widget.PopupWindow(
                popupView,
                popupWidth,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                true
            )

            popupWindow.isOutsideTouchable = true
            popupWindow.isFocusable = true
            popupWindow.animationStyle = R.style.Animation_PopupTopRight

            val tvShizuku = popupView.findViewById<TextView>(R.id.tv_mode_shizuku)
            val tvNormal = popupView.findViewById<TextView>(R.id.tv_mode_normal)

            val normalColor = ContextCompat.getColor(requireContext(), R.color.popup_item_text)
            val activeColor = Color.parseColor("#2196F3")

            tvShizuku.setTextColor(if (currentMode == com.battery.analysis.manager.PowerUsageManager.MODE_SHIZUKU) activeColor else normalColor)
            tvNormal.setTextColor(if (currentMode == com.battery.analysis.manager.PowerUsageManager.MODE_NORMAL) activeColor else normalColor)

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
                selectMode(com.battery.analysis.manager.PowerUsageManager.MODE_SHIZUKU)
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
     *
     * @param mode 当前工作模式
     */
    private fun updatePowerModeDisplay(mode: Int) {
        binding.tvCurrentPowerMode.text = if (mode == com.battery.analysis.manager.PowerUsageManager.MODE_SHIZUKU) {
            "⚡ Shizuku"
        } else {
            getString(R.string.power_mode_normal)
        }
    }

    /**
     * 初始化曲线采样精度设置项与下拉气泡弹窗交互。
     * 点击时弹出气泡菜单，供用户在极限省电模式、标准智能模式与极客高精模式间自由切换。
     */
    private fun setupSamplingModeSettings() {
        val powerManager = com.battery.analysis.manager.PowerUsageManager.getInstance(requireContext())
        updateSamplingModeDisplay(powerManager.getSamplingMode())

        binding.layoutSamplingModeSetting.setOnClickListener {
            val currentMode = powerManager.getSamplingMode()

            val popupView = layoutInflater.inflate(R.layout.popup_sampling_mode_picker, null)
            val density = resources.displayMetrics.density
            val popupWidth = (230 * density).toInt()

            val popupWindow = android.widget.PopupWindow(
                popupView,
                popupWidth,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                true
            )

            popupWindow.isOutsideTouchable = true
            popupWindow.isFocusable = true
            popupWindow.animationStyle = R.style.Animation_PopupTopRight

            val tvPowerSave = popupView.findViewById<TextView>(R.id.tv_sampling_power_save)
            val tvBalanced = popupView.findViewById<TextView>(R.id.tv_sampling_balanced)
            val tvHighPrecision = popupView.findViewById<TextView>(R.id.tv_sampling_high_precision)

            val normalColor = ContextCompat.getColor(requireContext(), R.color.popup_item_text)
            val activeColor = Color.parseColor("#2196F3")

            tvPowerSave.setTextColor(if (currentMode == com.battery.analysis.manager.PowerUsageManager.SAMPLING_MODE_POWER_SAVE) activeColor else normalColor)
            tvBalanced.setTextColor(if (currentMode == com.battery.analysis.manager.PowerUsageManager.SAMPLING_MODE_BALANCED) activeColor else normalColor)
            tvHighPrecision.setTextColor(if (currentMode == com.battery.analysis.manager.PowerUsageManager.SAMPLING_MODE_HIGH_PRECISION) activeColor else normalColor)

            val selectMode = { which: Int ->
                powerManager.setSamplingMode(which)
                updateSamplingModeDisplay(which)
                popupWindow.dismiss()
                val tip = when (which) {
                    com.battery.analysis.manager.PowerUsageManager.SAMPLING_MODE_HIGH_PRECISION -> getString(R.string.sampling_mode_tip_high_precision)
                    com.battery.analysis.manager.PowerUsageManager.SAMPLING_MODE_BALANCED -> getString(R.string.sampling_mode_tip_balanced)
                    else -> getString(R.string.sampling_mode_tip_power_save)
                }
                Toast.makeText(requireContext(), tip, Toast.LENGTH_SHORT).show()
            }

            tvPowerSave.setOnClickListener {
                selectMode(com.battery.analysis.manager.PowerUsageManager.SAMPLING_MODE_POWER_SAVE)
            }

            tvBalanced.setOnClickListener {
                selectMode(com.battery.analysis.manager.PowerUsageManager.SAMPLING_MODE_BALANCED)
            }

            tvHighPrecision.setOnClickListener {
                selectMode(com.battery.analysis.manager.PowerUsageManager.SAMPLING_MODE_HIGH_PRECISION)
            }

            popupWindow.showAsDropDown(
                binding.layoutSamplingModeSetting,
                0,
                (4 * density).toInt(),
                android.view.Gravity.END
            )
        }
    }

    /**
     * 更新曲线采样精度副标题展示文本。
     *
     * @param mode 当前配置的采样模式常量
     */
    private fun updateSamplingModeDisplay(mode: Int) {
        binding.tvCurrentSamplingMode.text = when (mode) {
            com.battery.analysis.manager.PowerUsageManager.SAMPLING_MODE_HIGH_PRECISION -> getString(R.string.sampling_mode_high_precision_short)
            com.battery.analysis.manager.PowerUsageManager.SAMPLING_MODE_BALANCED -> getString(R.string.sampling_mode_balanced_short)
            else -> getString(R.string.sampling_mode_power_save_short)
        }
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

            val popupWindow = android.widget.PopupWindow(
                popupView,
                popupWidth,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                true
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

        dialog.show()

        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            val width = (resources.displayMetrics.widthPixels * 0.92).toInt()
            window.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setGravity(android.view.Gravity.CENTER)
        }
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
     * 初始化实时刷新与间隔时间设置。
     */
    private fun setupRefreshSettings() {
        val isAutoRefreshEnabled = prefs.getBoolean("auto_refresh_enabled", false)
        val refreshIntervalMs = prefs.getLong("refresh_interval_ms", 2000L)

        binding.switchAutoRefresh.isChecked = isAutoRefreshEnabled
        binding.tvCurrentInterval.text = getIntervalDisplay(refreshIntervalMs)

        val mainActivity = activity as? MainActivity

        binding.switchAutoRefresh.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_refresh_enabled", isChecked).apply()
            mainActivity?.setAutoRefreshEnabled(isChecked)
        }

        val chargingPrefs = requireContext().getSharedPreferences("charging_stats_prefs", Context.MODE_PRIVATE)
        binding.switchChargingKeepScreenOn.isChecked = chargingPrefs.getBoolean("pref_charging_keep_screen_on", false)
        binding.switchChargingKeepScreenOn.setOnCheckedChangeListener { _, isChecked ->
            chargingPrefs.edit().putBoolean("pref_charging_keep_screen_on", isChecked).apply()
        }
        binding.layoutChargingKeepScreenOnSetting.setOnClickListener {
            val newChecked = !binding.switchChargingKeepScreenOn.isChecked
            binding.switchChargingKeepScreenOn.isChecked = newChecked
        }

        binding.layoutIntervalSetting.setOnClickListener { _ ->
            val intervalValues = arrayOf(1000L, 2000L, 3000L, 5000L, 10000L)
            val currentVal = prefs.getLong("refresh_interval_ms", 2000L)

            val popupView = layoutInflater.inflate(R.layout.popup_interval_picker, null)
            val density = resources.displayMetrics.density
            val popupWidth = (140 * density).toInt()

            val popupWindow = android.widget.PopupWindow(
                popupView,
                popupWidth,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                true
            )

            popupWindow.isOutsideTouchable = true
            popupWindow.isFocusable = true
            popupWindow.animationStyle = R.style.Animation_PopupTopRight

            val optionViews = listOf(
                popupView.findViewById<TextView>(R.id.tv_opt_1s),
                popupView.findViewById<TextView>(R.id.tv_opt_2s),
                popupView.findViewById<TextView>(R.id.tv_opt_3s),
                popupView.findViewById<TextView>(R.id.tv_opt_5s),
                popupView.findViewById<TextView>(R.id.tv_opt_10s)
            )

            // 多语言刷新间隔文本配置
            optionViews[0].text = getString(R.string.interval_1s)
            optionViews[1].text = getString(R.string.interval_2s)
            optionViews[2].text = getString(R.string.interval_3s)
            optionViews[3].text = getString(R.string.interval_5s)
            optionViews[4].text = getString(R.string.interval_10s)

            val normalColor = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.popup_item_text)
            val activeColor = Color.parseColor("#2196F3")

            optionViews.forEachIndexed { index, textView ->
                val intervalVal = intervalValues[index]
                if (intervalVal == currentVal) {
                    textView.setTextColor(activeColor)
                } else {
                    textView.setTextColor(normalColor)
                }

                textView.setOnClickListener {
                    prefs.edit().putLong("refresh_interval_ms", intervalVal).apply()
                    binding.tvCurrentInterval.text = getIntervalDisplay(intervalVal)
                    mainActivity?.updateRefreshInterval(intervalVal)
                    popupWindow.dismiss()
                }
            }

            popupWindow.showAsDropDown(
                binding.layoutIntervalSetting,
                0,
                (4 * density).toInt(),
                android.view.Gravity.END
            )
        }
    }

    /**
     * 根据当前选定的毫秒数获取本地化刷新间隔文案。
     *
     * @param intervalMs 刷新时间间隔毫秒数
     * @return 本地化文案
     */
    private fun getIntervalDisplay(intervalMs: Long): String {
        return when (intervalMs) {
            1000L -> getString(R.string.interval_1s)
            2000L -> getString(R.string.interval_2s)
            3000L -> getString(R.string.interval_3s)
            5000L -> getString(R.string.interval_5s)
            10000L -> getString(R.string.interval_10s)
            else -> "${intervalMs / 1000}s"
        }
    }

    /**
     * 初始化 Shizuku 权限与状态观察与授权交互。
     */
    private fun setupShizukuSettings() {
        val mainActivity = activity as? MainActivity

        binding.layoutShizukuAuth.setOnClickListener {
            mainActivity?.requestShizukuAuth()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.shizukuStatus, viewModel.isShizukuGranted) { status, isGranted ->
                    Pair(status, isGranted)
                }.collect { (status, isGranted) ->
                    if (isGranted) {
                        binding.tvShizukuStatus.text = getString(R.string.shizuku_status_authorized)
                        binding.tvShizukuStatus.setTextColor(Color.parseColor("#10B981"))
                        binding.tvShizukuAction.text = getString(R.string.shizuku_action_authorized)
                        binding.tvShizukuAction.setTextColor(Color.parseColor("#10B981"))
                    } else {
                        val isNotRunning = status.contains("未运行", ignoreCase = true) || status.contains("Not Running", ignoreCase = true)
                        binding.tvShizukuStatus.text = if (isNotRunning) getString(R.string.shizuku_status_not_running) else getString(R.string.shizuku_status_unauthorized)
                        binding.tvShizukuStatus.setTextColor(if (isNotRunning) Color.parseColor("#EF4444") else Color.parseColor("#F59E0B"))
                        binding.tvShizukuAction.text = getString(R.string.shizuku_action_authorize)
                        binding.tvShizukuAction.setTextColor(ContextCompat.getColor(requireContext(), R.color.nav_item_selected))
                    }
                }
            }
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
            result.onSuccess { count ->
                Toast.makeText(ctx, getString(R.string.toast_backup_success, count), Toast.LENGTH_LONG).show()
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
        tvRecordsCount.text = getString(R.string.history_count_format, backupData.historyRecords.size)

        val settingsList = mutableListOf<String>()
        if (backupData.settings.themeMode != null) settingsList.add(getString(R.string.setting_follow_system_theme))
        if (backupData.settings.autoRefreshEnabled != null) settingsList.add(getString(R.string.setting_auto_refresh))
        if (backupData.settings.refreshIntervalMs != null) settingsList.add(getString(R.string.setting_refresh_interval))

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
                result.onSuccess { count ->
                    if (restoreSettings) {
                        syncSettingsUiState()
                    }
                    val modeText = if (isOverwrite) getString(R.string.dialog_restore_mode_overwrite) else getString(R.string.dialog_restore_mode_merge)
                    Toast.makeText(ctx, getString(R.string.toast_restore_success, modeText, count), Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                }.onFailure { exception ->
                    Toast.makeText(ctx, getString(R.string.toast_restore_failed, exception.message), Toast.LENGTH_LONG).show()
                }
            }
        }

        dialog.show()

        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            val width = (resources.displayMetrics.widthPixels * 0.92).toInt()
            window.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setGravity(android.view.Gravity.CENTER)
        }
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
        binding.switchAutoRefresh.setOnCheckedChangeListener(null)

        binding.switchFollowSystem.isChecked = isFollowSystem
        binding.switchDarkMode.isChecked = isDarkMode
        binding.switchDarkMode.isEnabled = !isFollowSystem
        binding.layoutDarkMode.alpha = if (isFollowSystem) 0.5f else 1.0f

        val isAutoRefreshEnabled = prefs.getBoolean("auto_refresh_enabled", false)
        val refreshIntervalMs = prefs.getLong("refresh_interval_ms", 2000L)
        binding.switchAutoRefresh.isChecked = isAutoRefreshEnabled
        binding.tvCurrentInterval.text = getIntervalDisplay(refreshIntervalMs)

        setupLanguageSettings()
        setupThemeSettings()
        setupRefreshSettings()

        AppCompatDelegate.setDefaultNightMode(currentThemeMode)
        val mainActivity = activity as? MainActivity
        mainActivity?.setAutoRefreshEnabled(isAutoRefreshEnabled)
        mainActivity?.updateRefreshInterval(refreshIntervalMs)
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
     * 绑定前台服务监控开关、开机自启动开关、电池优化白名单申请与防杀加锁教程弹窗。
     */
    private fun setupKeepAliveSettings() {
        binding.switchKeepAliveService.isChecked = com.battery.analysis.service.BatteryMonitorService.isServiceEnabled(requireContext())
        binding.switchKeepAliveService.setOnCheckedChangeListener { _, isChecked ->
            com.battery.analysis.service.BatteryMonitorService.setServiceEnabled(requireContext(), isChecked)
            if (isChecked) {
                (activity as? MainActivity)?.checkAndStartBatteryMonitorService()
            } else {
                com.battery.analysis.service.BatteryMonitorService.stop(requireContext())
            }
        }

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
     */
    private fun showLockRecentsGuideDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.dialog_lock_recents_title)
            .setMessage(R.string.dialog_lock_recents_content)
            .setPositiveButton(R.string.understood, null)
            .show()
    }

    /**
     * 视图销毁时的清理工作。
     */
    override fun onDestroyView() {
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
