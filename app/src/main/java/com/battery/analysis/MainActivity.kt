package com.battery.analysis

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.GravityCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.battery.analysis.databinding.ActivityMainBinding
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * 电池检测应用主界面 Activity。
 * 承载上方三大 Tab 页签（系统api、Shizuku、错误报告）与 ViewPager2 滑动容器，负责数据轮询调度、主题管理与全局抽屉设置。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private val viewModel: BatteryViewModel by viewModels()

    private val SHIZUKU_REQUEST_CODE = 1001

    private var autoRefreshJob: Job? = null
    private var isAutoRefreshEnabled: Boolean = false
    private var refreshIntervalMs: Long = 2000L

    /**
     * Shizuku 权限请求结果监听器。
     */
    private val requestPermissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_REQUEST_CODE) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Shizuku 授权成功！", Toast.LENGTH_SHORT).show()
                viewModel.refreshData(this)
            } else {
                Toast.makeText(this, "Shizuku 授权被拒绝", Toast.LENGTH_SHORT).show()
            }
            updateShizukuStatusState()
        }
    }

    /**
     * Shizuku 服务绑定成功监听器。
     */
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        updateShizukuStatusState()
        viewModel.refreshData(this)
    }

    /**
     * Shizuku 服务死亡监听器。
     */
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        updateShizukuStatusState()
        viewModel.refreshData(this)
    }

    /**
     * 活动创建入口，负责界面视图绑定、ViewPager2 与 TabLayout 联动、监听器注册及初始刷新。
     *
     * @param savedInstanceState 状态恢复 Bundle
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateSystemBarAppearance()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("battery_app_settings", Context.MODE_PRIVATE)

        val savedThemeMode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(savedThemeMode)

        isAutoRefreshEnabled = prefs.getBoolean("auto_refresh_enabled", false)
        refreshIntervalMs = prefs.getLong("refresh_interval_ms", 2000L)

        // 1. 初始化 ViewPager2 与 TabLayout
        setupTabsAndViewPager()

        // 2. 注册 Shizuku 监听器
        Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        // 3. 顶部操作栏事件
        binding.btnRefresh.setOnClickListener {
            viewModel.refreshData(this)
            Toast.makeText(this, "数据已刷新", Toast.LENGTH_SHORT).show()
        }

        // 4. 打开右侧抽屉设置
        binding.btnSettings.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.END)
        }

        // 5. 关闭抽屉按钮
        binding.btnCloseDrawer.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.END)
        }

        // 6. 处理返回键拦截（若抽屉打开则优先关闭抽屉）
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.END)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.END)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // 7. 初始化右侧抽屉设置面板
        setupDrawerSettings()

        // 8. 初始刷新数据与状态
        updateShizukuStatusState()
        viewModel.refreshData(this)
    }

    /**
     * 初始化 ViewPager2 适配器及 TabLayoutMediator 联动绑定。
     */
    private fun setupTabsAndViewPager() {
        val pagerAdapter = MainPagerAdapter(this)
        binding.viewPager.adapter = pagerAdapter

        // 设置预加载页数，保证左右滑动流畅
        binding.viewPager.offscreenPageLimit = 2

        val tabTitles = arrayOf("系统api", "Shizuku", "错误报告")

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = tabTitles.getOrElse(position) { "" }
        }.attach()
    }

    /**
     * 初始化右侧抽屉设置板块（主题设置、实时刷新、Shizuku授权）。
     */
    private fun setupDrawerSettings() {
        // 1. 主题/深色模式
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
            prefs.edit().putInt("theme_mode", newMode).apply()
            AppCompatDelegate.setDefaultNightMode(newMode)
        }

        // 2. 实时刷新
        binding.switchAutoRefreshDrawer.isChecked = isAutoRefreshEnabled
        binding.tvCurrentIntervalDrawer.text = "${refreshIntervalMs / 1000} 秒"

        binding.switchAutoRefreshDrawer.setOnCheckedChangeListener { _, isChecked ->
            isAutoRefreshEnabled = isChecked
            prefs.edit().putBoolean("auto_refresh_enabled", isChecked).apply()
            if (isChecked) {
                startAutoRefresh()
                Toast.makeText(this, "已开启实时刷新 (${refreshIntervalMs / 1000}秒)", Toast.LENGTH_SHORT).show()
            } else {
                stopAutoRefresh()
                Toast.makeText(this, "已关闭实时刷新", Toast.LENGTH_SHORT).show()
            }
        }

        binding.layoutIntervalSettingDrawer.setOnClickListener {
            val intervals = arrayOf("1 秒", "2 秒", "3 秒", "5 秒", "10 秒")
            val intervalValues = arrayOf(1000L, 2000L, 3000L, 5000L, 10000L)
            val currentIndex = intervalValues.indexOf(refreshIntervalMs).coerceAtLeast(0)

            AlertDialog.Builder(this)
                .setTitle("选择刷新时间间隔")
                .setSingleChoiceItems(intervals, currentIndex) { dialog, which ->
                    refreshIntervalMs = intervalValues[which]
                    prefs.edit().putLong("refresh_interval_ms", refreshIntervalMs).apply()
                    binding.tvCurrentIntervalDrawer.text = intervals[which]
                    if (isAutoRefreshEnabled) {
                        startAutoRefresh()
                    }
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 3. Shizuku 授权与状态
        binding.btnAuthShizukuDrawer.setOnClickListener {
            requestShizukuAuth()
        }
    }

    /**
     * 更新 Shizuku 的连接状态，并同步至 ViewModel 与抽屉视图。
     */
    private fun updateShizukuStatusState() {
        if (!Shizuku.pingBinder()) {
            val statusText = "Shizuku: 未运行"
            binding.tvShizukuStatusDrawer.text = statusText
            binding.tvShizukuStatusDrawer.setTextColor(Color.RED)
            viewModel.updateShizukuStatus(statusText, false)
        } else {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                val statusText = "Shizuku: 已授权"
                binding.tvShizukuStatusDrawer.text = statusText
                binding.tvShizukuStatusDrawer.setTextColor(Color.GREEN)
                viewModel.updateShizukuStatus(statusText, true)
            } else {
                val statusText = "Shizuku: 未授权"
                binding.tvShizukuStatusDrawer.text = statusText
                binding.tvShizukuStatusDrawer.setTextColor(Color.parseColor("#FFA500"))
                viewModel.updateShizukuStatus(statusText, false)
            }
        }
    }

    /**
     * 根据当前日夜间模式与主题，自动调整状态栏及导航栏图标的明暗颜色。
     */
    private fun updateSystemBarAppearance() {
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        val isNight = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        insetsController.isAppearanceLightStatusBars = !isNight
        insetsController.isAppearanceLightNavigationBars = !isNight
    }

    /**
     * 界面变为可见时的生命周期回调。
     */
    override fun onStart() {
        super.onStart()
        if (isAutoRefreshEnabled) {
            startAutoRefresh()
        }
    }

    /**
     * 界面退到后台时的生命周期回调。
     */
    override fun onStop() {
        super.onStop()
        stopAutoRefresh()
    }

    /**
     * 活动销毁生命周期回调，注销 Shizuku 监听器与协程。
     */
    override fun onDestroy() {
        super.onDestroy()
        stopAutoRefresh()
        Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
    }

    /**
     * 主动发起 Shizuku 授权请求或引导用户启动 Shizuku App。
     */
    fun requestShizukuAuth() {
        if (Shizuku.pingBinder()) {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                try {
                    Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
                } catch (e: Exception) {
                    Toast.makeText(this, "请求授权失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "已经授权，无需再次请求", Toast.LENGTH_SHORT).show()
                updateShizukuStatusState()
            }
        } else {
            Toast.makeText(this, "Shizuku 尚未连接，尝试打开 Shizuku App...", Toast.LENGTH_SHORT).show()
            try {
                val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                if (intent != null) {
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "未找到 Shizuku App，请先安装并启动", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            updateShizukuStatusState()
        }
    }

    /**
     * 启动实时自动刷新的后台协程。
     */
    private fun startAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                viewModel.refreshData(this@MainActivity)
                delay(refreshIntervalMs)
            }
        }
    }

    /**
     * 停止实时自动刷新协程。
     */
    private fun stopAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }
}
