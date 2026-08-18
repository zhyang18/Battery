package com.battery.analysis.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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
import com.battery.analysis.viewmodel.BatteryViewModel
import kotlinx.coroutines.launch

/**
 * 设置顶级页面 Fragment。
 * 负责管理应用主题模式、实时数据刷新开关与间隔、Shizuku 提权状态与授权，以及应用关于信息。
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: BatteryViewModel by activityViewModels()
    private lateinit var prefs: SharedPreferences

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

        setupThemeSettings()
        setupRefreshSettings()
        setupShizukuSettings()
    }

    /**
     * 初始化主题与深色模式设置。
     */
    private fun setupThemeSettings() {
        binding.rgThemeMode.setOnCheckedChangeListener(null)
        val currentThemeMode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        when (currentThemeMode) {
            AppCompatDelegate.MODE_NIGHT_NO -> binding.rbThemeLight.isChecked = true
            AppCompatDelegate.MODE_NIGHT_YES -> binding.rbThemeDark.isChecked = true
            else -> binding.rbThemeSystem.isChecked = true
        }

        binding.rgThemeMode.setOnCheckedChangeListener { _, checkedId ->
            val newMode = when (checkedId) {
                R.id.rb_theme_light -> AppCompatDelegate.MODE_NIGHT_NO
                R.id.rb_theme_dark -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            val currentSaved = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            if (newMode != currentSaved) {
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
        binding.tvCurrentInterval.text = "${refreshIntervalMs / 1000} 秒"

        val mainActivity = activity as? MainActivity

        binding.switchAutoRefresh.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_refresh_enabled", isChecked).apply()
            mainActivity?.setAutoRefreshEnabled(isChecked)
            if (isChecked) {
                val currentInterval = prefs.getLong("refresh_interval_ms", 2000L)
                Toast.makeText(requireContext(), "已开启实时刷新 (${currentInterval / 1000}秒)", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "已关闭实时刷新", Toast.LENGTH_SHORT).show()
            }
        }

        binding.layoutIntervalSetting.setOnClickListener {
            val intervals = arrayOf("1 秒", "2 秒", "3 秒", "5 秒", "10 秒")
            val intervalValues = arrayOf(1000L, 2000L, 3000L, 5000L, 10000L)
            val currentVal = prefs.getLong("refresh_interval_ms", 2000L)
            val currentIndex = intervalValues.indexOf(currentVal).coerceAtLeast(0)

            AlertDialog.Builder(requireContext())
                .setTitle("选择刷新时间间隔")
                .setSingleChoiceItems(intervals, currentIndex) { dialog, which ->
                    val selectedInterval = intervalValues[which]
                    prefs.edit().putLong("refresh_interval_ms", selectedInterval).apply()
                    binding.tvCurrentInterval.text = intervals[which]
                    mainActivity?.updateRefreshInterval(selectedInterval)
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    /**
     * 初始化 Shizuku 权限与状态观察与授权交互。
     */
    private fun setupShizukuSettings() {
        val mainActivity = activity as? MainActivity

        binding.btnAuthShizuku.setOnClickListener {
            mainActivity?.requestShizukuAuth()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.shizukuStatus.collect { status ->
                    binding.tvShizukuStatus.text = status
                    val isGranted = viewModel.isShizukuGranted.value
                    if (isGranted) {
                        binding.tvShizukuStatus.setTextColor(Color.GREEN)
                        binding.btnAuthShizuku.text = "已授权"
                        binding.btnAuthShizuku.isEnabled = false
                    } else {
                        binding.tvShizukuStatus.setTextColor(if (status.contains("未运行")) Color.RED else Color.parseColor("#FFA500"))
                        binding.btnAuthShizuku.text = "请求授权"
                        binding.btnAuthShizuku.isEnabled = true
                    }
                }
            }
        }
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
