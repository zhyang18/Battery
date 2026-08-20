package com.battery.analysis.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
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
        setupShizukuSettings()
        setupBackupRestoreSettings()
        setupHelpSection()
        setupAboutSection()
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
                viewModel.shizukuStatus.collect { status ->
                    val isGranted = viewModel.isShizukuGranted.value
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
                        binding.tvShizukuAction.setTextColor(Color.parseColor("#818CF8"))
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
                Toast.makeText(ctx, "Export failed: ${exception.message}", Toast.LENGTH_LONG).show()
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
