package com.battery.analysis

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.battery.analysis.databinding.ActivityMainBinding
import com.battery.analysis.ui.MainPagerAdapter
import com.battery.analysis.viewmodel.BatteryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * 电池检测应用主界面 Activity。
 * 承载底部三大顶级页签（检测、记录、设置）导航容器，负责数据轮询调度、主题管理与 Shizuku 监听。
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
     * 活动创建入口，负责界面视图绑定、底部导航栏联动、监听器注册及初始刷新。
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

        // 1. 初始化顶级 ViewPager2 与底部 NavigationBar 联动
        setupBottomNavigation()

        // 2. 注册 Shizuku 监听器
        Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        // 3. 初始刷新数据与状态
        updateShizukuStatusState()
        viewModel.refreshData(this)
    }

    /**
     * 初始化顶级 ViewPager2 与底部 NavigationBar 绑定联动。
     */
    private fun setupBottomNavigation() {
        val pagerAdapter = MainPagerAdapter(this)
        binding.mainViewPager.adapter = pagerAdapter

        // 禁用顶级 ViewPager2 手势横滑，避免干扰内部子 Tab 横滑切换
        binding.mainViewPager.isUserInputEnabled = false
        binding.mainViewPager.offscreenPageLimit = 2

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_detection -> {
                    binding.mainViewPager.setCurrentItem(0, false)
                    true
                }
                R.id.nav_history -> {
                    binding.mainViewPager.setCurrentItem(1, false)
                    true
                }
                R.id.nav_settings -> {
                    binding.mainViewPager.setCurrentItem(2, false)
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 更新 Shizuku 的连接状态，并同步至 ViewModel。
     */
    fun updateShizukuStatusState() {
        if (!Shizuku.pingBinder()) {
            val statusText = "Shizuku: 未运行"
            viewModel.updateShizukuStatus(statusText, false)
        } else {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                val statusText = "Shizuku: 已授权"
                viewModel.updateShizukuStatus(statusText, true)
            } else {
                val statusText = "Shizuku: 未授权"
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
     * 设置是否开启实时自动刷新。
     *
     * @param enabled 是否开启实时自动刷新
     */
    fun setAutoRefreshEnabled(enabled: Boolean) {
        isAutoRefreshEnabled = enabled
        if (enabled) {
            startAutoRefresh()
        } else {
            stopAutoRefresh()
        }
    }

    /**
     * 更新实时刷新时间间隔。
     *
     * @param intervalMs 刷新时间间隔（毫秒）
     */
    fun updateRefreshInterval(intervalMs: Long) {
        refreshIntervalMs = intervalMs
        if (isAutoRefreshEnabled) {
            startAutoRefresh()
        }
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
