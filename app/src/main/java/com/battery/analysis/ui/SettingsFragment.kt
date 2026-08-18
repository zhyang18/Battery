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
        binding.tvCurrentInterval.text = "${refreshIntervalMs / 1000} 秒"

        val mainActivity = activity as? MainActivity

        binding.switchAutoRefresh.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_refresh_enabled", isChecked).apply()
            mainActivity?.setAutoRefreshEnabled(isChecked)
            if (isChecked) {
                val currentInterval = prefs.getLong("refresh_interval_ms", 2000L)
                Toast.makeText(requireContext(), "已开启实时自动刷新 (${currentInterval / 1000}秒)", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "已关闭实时自动刷新", Toast.LENGTH_SHORT).show()
            }
        }

        binding.layoutIntervalSetting.setOnClickListener { _ ->
            val intervals = arrayOf("1 秒", "2 秒", "3 秒", "5 秒", "10 秒")
            val intervalValues = arrayOf(1000L, 2000L, 3000L, 5000L, 10000L)
            val currentVal = prefs.getLong("refresh_interval_ms", 2000L)

            val popupView = layoutInflater.inflate(R.layout.popup_interval_picker, null)
            val density = resources.displayMetrics.density
            val popupWidth = (120 * density).toInt()

            val popupWindow = android.widget.PopupWindow(
                popupView,
                popupWidth,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                true
            )

            // 支持外部点击收起与右上角展开动画
            popupWindow.isOutsideTouchable = true
            popupWindow.isFocusable = true
            popupWindow.animationStyle = R.style.Animation_PopupTopRight

            // 获取全部选项并绑定高对比度文字与选中态
            val optionViews = listOf(
                popupView.findViewById<android.widget.TextView>(R.id.tv_opt_1s),
                popupView.findViewById<android.widget.TextView>(R.id.tv_opt_2s),
                popupView.findViewById<android.widget.TextView>(R.id.tv_opt_3s),
                popupView.findViewById<android.widget.TextView>(R.id.tv_opt_5s),
                popupView.findViewById<android.widget.TextView>(R.id.tv_opt_10s)
            )

            val normalColor = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.popup_item_text)
            val activeColor = android.graphics.Color.parseColor("#2196F3")

            optionViews.forEachIndexed { index, textView ->
                val intervalVal = intervalValues[index]
                if (intervalVal == currentVal) {
                    textView.setTextColor(activeColor)
                } else {
                    textView.setTextColor(normalColor)
                }

                textView.setOnClickListener {
                    prefs.edit().putLong("refresh_interval_ms", intervalVal).apply()
                    binding.tvCurrentInterval.text = intervals[index]
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
                    binding.tvShizukuStatus.text = status
                    val isGranted = viewModel.isShizukuGranted.value
                    if (isGranted) {
                        binding.tvShizukuStatus.setTextColor(Color.parseColor("#10B981"))
                        binding.tvShizukuAction.text = "已授权"
                        binding.tvShizukuAction.setTextColor(Color.parseColor("#10B981"))
                    } else {
                        binding.tvShizukuStatus.setTextColor(if (status.contains("未运行")) Color.parseColor("#EF4444") else Color.parseColor("#F59E0B"))
                        binding.tvShizukuAction.text = "去授权"
                        binding.tvShizukuAction.setTextColor(Color.parseColor("#818CF8"))
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
