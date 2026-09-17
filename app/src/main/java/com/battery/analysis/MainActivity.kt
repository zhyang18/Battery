package com.battery.analysis

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.battery.analysis.databinding.ActivityMainBinding
import com.battery.analysis.receiver.BatteryUnplugReceiver
import com.battery.analysis.ui.MainPagerAdapter
import com.battery.analysis.viewmodel.BatteryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * 电池检测应用主界面 Activity。
 * 承载底部三大顶级页签（耗电、检测、设置）导航容器，负责主题管理、前台广播与 Shizuku 授权监听。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private val viewModel: BatteryViewModel by viewModels()

    private val SHIZUKU_REQUEST_CODE = 1001

    private var lastBackPressedTime: Long = 0L

    /**
     * 前台动态注册的拔电广播接收器，保障前台运行期间毫秒级捕获断电事件。
     */
    private val batteryUnplugReceiver = BatteryUnplugReceiver()

    /**
     * Shizuku 权限请求结果监听器。
     */
    private val requestPermissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_REQUEST_CODE) {
            val isGranted = (grantResult == PackageManager.PERMISSION_GRANTED)
            if (isGranted) {
                Toast.makeText(this, getString(R.string.toast_shizuku_success), Toast.LENGTH_SHORT).show()
                val statusText = getString(R.string.shizuku_status_authorized)
                viewModel.updateShizukuStatus(statusText, true)
                viewModel.refreshShizuku(this)
            } else {
                Toast.makeText(this, getString(R.string.toast_shizuku_denied), Toast.LENGTH_SHORT).show()
                val statusText = getString(R.string.shizuku_status_unauthorized)
                viewModel.updateShizukuStatus(statusText, false)
            }
            updateShizukuStatusState()
        }
    }

    /**
     * Shizuku 服务绑定成功监听器。
     */
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        updateShizukuStatusState()
        viewModel.refreshShizuku(this)
    }

    /**
     * Shizuku 服务死亡监听器。
     */
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        updateShizukuStatusState()
        viewModel.refreshShizuku(this)
    }

    /**
     * Android 13+ 系统通知权限请求 Launcher。
     */
    private val requestNotificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted && com.battery.analysis.service.BatteryMonitorService.isServiceEnabled(this)) {
            com.battery.analysis.service.BatteryMonitorService.start(this)
        }
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
        setupEdgeToEdgeInsets()

        prefs = getSharedPreferences("battery_app_settings", Context.MODE_PRIVATE)

        // 0. 初始化并应用保存的语言配置
        com.battery.analysis.manager.LanguageManager.initLanguage(this)

        val savedThemeMode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(savedThemeMode)

        // 1. 初始化顶级 ViewPager2 与底部 NavigationBar 联动
        setupBottomNavigation()

        // 2. 初始化双击返回退出应用监听
        //setupBackPressHandler()

        // 3. 注册 Shizuku 监听器
        Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        // 4. 动态注册充拔电广播接收器（前台双保险，防止部分机型后台静态广播被阻断）
        val unplugFilter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_POWER_DISCONNECTED)
            addAction(android.content.Intent.ACTION_POWER_CONNECTED)
        }
        registerReceiver(batteryUnplugReceiver, unplugFilter)

        // 5. 初始只刷新系统普通 API（因为默认处于系统 API 视图）
        updateShizukuStatusState()
        viewModel.refreshNormalApi(this)

        // 6. 注册断开电源自动记录快照监听器，实时刷新历史数据流
        BatteryUnplugReceiver.onRecordInsertedListener = {
            viewModel.loadHistoryRecords(this)
        }

        // 7. 执行充放电断层自愈对齐检测
        com.battery.analysis.manager.ChargingStatsManager.getInstance(this).checkAndReconcileChargingState()
        com.battery.analysis.manager.PowerUsageManager.getInstance(this).checkAndReconcileDischargeState()

        // 8. 检查并按需启动后台电池监控前台服务
        checkAndStartBatteryMonitorService()
    }

    /**
     * 检查并按需启动后台电池实时监控前台服务，兼容 Android 13+ 运行时通知权限校验。
     */
    fun checkAndStartBatteryMonitorService() {
        if (!com.battery.analysis.service.BatteryMonitorService.shouldServiceRun(this)) {
            com.battery.analysis.service.BatteryMonitorService.stop(this)
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        com.battery.analysis.service.BatteryMonitorService.start(this)
    }

    /**
     * 配置系统返回手势/物理返回键监听，支持连续双击（2秒内）返回彻底退出 App。
     */
    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastBackPressedTime < 2000L) {
                    exitAppAndKillProcess()
                } else {
                    lastBackPressedTime = currentTime
                    Toast.makeText(this@MainActivity, getString(R.string.toast_press_again_exit), Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    /**
     * 彻底退出应用：解绑所有监听、销毁 Activity 栈并安全终止当前应用进程，释放所有资源。
     */
    private fun exitAppAndKillProcess() {
        try {
            Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener)
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
        kotlin.system.exitProcess(0)
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
            setBottomNavigationVisibility(true)
            when (item.itemId) {
                R.id.nav_power -> {
                    binding.mainViewPager.setCurrentItem(0, false)
                    true
                }
                R.id.nav_detection -> {
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
        // 默认选中第一个“耗电”页签
        binding.bottomNavigation.selectedItemId = R.id.nav_power

        // 根据初始充放电状态动态适配首个页签的标题与图标
        val chargingManager = com.battery.analysis.manager.ChargingStatsManager.getInstance(this)
        updateBottomNavPowerTab(chargingManager.isCharging())
    }

    private var isBottomNavVisible: Boolean = true

    /**
     * 设置底部导航栏联动显隐状态，配合平滑动效实现平滑滑入或滑出。
     * 隐藏时自动附加安全冗余位移，防止在高分辨率屏幕或全面屏手势条下残留边缘线条。
     *
     * @param visible 是否显示底部导航栏（true 为显示，false 为隐藏）
     */
    fun setBottomNavigationVisibility(visible: Boolean) {
        if (isBottomNavVisible == visible) return
        isBottomNavVisible = visible
        val containerHeight = binding.layoutBottomNavContainer.height.toFloat().takeIf { it > 0f } ?: 300f
        val targetTranslationY = if (visible) 0f else (containerHeight + 120f)
        binding.layoutBottomNavContainer.animate().cancel()
        binding.layoutBottomNavContainer.animate()
            .translationY(targetTranslationY)
            .setDuration(220L)
            .setInterpolator(androidx.interpolator.view.animation.FastOutSlowInInterpolator())
            .start()
    }

    /**
     * 根据设备充放电状态动态更新底部导航栏首个页签的名称与图标。
     *
     * @param isCharging 是否处于充电状态（true 为充电，false 为耗电）
     */
    fun updateBottomNavPowerTab(isCharging: Boolean) {
        val menuItem = binding.bottomNavigation.menu.findItem(R.id.nav_power) ?: return
        if (isCharging) {
            menuItem.title = getString(R.string.nav_charging)
            menuItem.setIcon(R.drawable.ic_bolt)
        } else {
            menuItem.title = getString(R.string.nav_power)
            menuItem.setIcon(R.drawable.ic_nav_power)
        }
    }

    /**
     * 更新 Shizuku 的连接状态，并同步至 ViewModel。
     */
    fun updateShizukuStatusState() {
        if (!Shizuku.pingBinder()) {
            val statusText = getString(R.string.shizuku_status_not_running)
            viewModel.updateShizukuStatus(statusText, false)
        } else {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                val statusText = getString(R.string.shizuku_status_authorized)
                viewModel.updateShizukuStatus(statusText, true)
            } else {
                val statusText = getString(R.string.shizuku_status_unauthorized)
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
     * 配置全面屏边到边（Edge-to-Edge）沉浸式窗口边距自适应分发。
     * 针对 Android 15+ (API 35/36) 强制开启的 Edge-to-Edge 机制，动态监听状态栏、手势导航栏及异形刘海切口高度，
     * 为顶部 ViewPager2 分发状态栏安全内边距，为底部导航栏容器分发手势条安全内边距，杜绝内容重叠。
     */
    private fun setupEdgeToEdgeInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val displayCutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())

            val topInset = maxOf(statusBarInsets.top, displayCutout.top)
            val leftInset = maxOf(statusBarInsets.left, displayCutout.left, navBarInsets.left)
            val rightInset = maxOf(statusBarInsets.right, displayCutout.right, navBarInsets.right)
            val bottomInset = maxOf(navBarInsets.bottom, displayCutout.bottom)

            binding.mainViewPager.setPadding(leftInset, topInset, rightInset, 0)
            binding.layoutBottomNavContainer.setPadding(leftInset, 0, rightInset, bottomInset)

            insets
        }
    }

    /**
     * 界面变为可见时的生命周期回调。
     */
    override fun onStart() {
        super.onStart()
        updateShizukuStatusState()
    }

    /**
     * 界面恢复到前台运行时的生命周期回调，同步检查并更新 Shizuku 连接与授权状态，并执行自愈校准。
     */
    override fun onResume() {
        super.onResume()
        updateShizukuStatusState()
        com.battery.analysis.manager.ChargingStatsManager.getInstance(this).checkAndReconcileChargingState()
        com.battery.analysis.manager.PowerUsageManager.getInstance(this).checkAndReconcileDischargeState()
        val isCharging = com.battery.analysis.manager.ChargingStatsManager.getInstance(this).isCharging()
        updateBottomNavPowerTab(isCharging)
    }

    /**
     * 活动销毁生命周期回调，注销动态广播接收器与 Shizuku 监听器。
     */
    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(batteryUnplugReceiver)
        } catch (_: Exception) {}
        BatteryUnplugReceiver.onRecordInsertedListener = null
        Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
    }

    /**
     * 启动 Shizuku 管理器应用，供用户查看或管理已授权应用列表。
     *
     * @return 成功唤起应用返回 true，未安装或唤起失败返回 false
     */
    fun openShizukuApp(): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (intent != null) {
                startActivity(intent)
                true
            } else {
                Toast.makeText(this, getString(R.string.toast_shizuku_not_found), Toast.LENGTH_LONG).show()
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, getString(R.string.toast_shizuku_not_found), Toast.LENGTH_LONG).show()
            false
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
                    Toast.makeText(this, getString(R.string.toast_request_auth_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, getString(R.string.toast_already_authorized), Toast.LENGTH_SHORT).show()
                updateShizukuStatusState()
            }
        } else {
            Toast.makeText(this, getString(R.string.toast_shizuku_not_connected), Toast.LENGTH_SHORT).show()
            openShizukuApp()
            updateShizukuStatusState()
        }
    }

    /**
     * 主动解除当前应用已获得的 Shizuku 提权授权。
     * 通过 Shizuku 底层 privileged shell 命令调用系统的 pm revoke 撤销权限，
     * 并反射清理客户端静态缓存与即时更新 ViewModel 状态。
     *
     * @param onComplete 解除完成后的回调函数，包含成功状态以及错误信息说明
     */
    fun revokeShizukuAuth(onComplete: ((Boolean, String?) -> Unit)? = null) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    withContext(Dispatchers.Main) {
                        updateShizukuStatusState()
                        onComplete?.invoke(false, getString(R.string.toast_shizuku_not_connected))
                    }
                    return@launch
                }

                // 1. 通过 Shizuku 反射调用 newProcess 执行系统权限撤销命令
                val newProcessMethod = try {
                    Shizuku::class.java.getDeclaredMethod(
                        "newProcess",
                        Array<String>::class.java,
                        Array<String>::class.java,
                        String::class.java
                    ).apply { isAccessible = true }
                } catch (e: Exception) {
                    null
                }

                val cmd = arrayOf("sh", "-c", "pm revoke $packageName moe.shizuku.manager.permission.API_V23")
                val process = newProcessMethod?.invoke(null, cmd, null, null) as? Process
                val exitCode = process?.waitFor()

                // 2. 清理 Shizuku 客户端内部的 permissionGranted 静态缓存
                try {
                    val field = Shizuku::class.java.getDeclaredField("permissionGranted")
                    field.isAccessible = true
                    field.set(null, false)
                } catch (_: Throwable) {
                }

                withContext(Dispatchers.Main) {
                    // 3. 检查系统权限或 Binder 检查状态
                    val isRevoked = Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED ||
                            checkSelfPermission("moe.shizuku.manager.permission.API_V23") != PackageManager.PERMISSION_GRANTED

                    updateShizukuStatusState()
                    viewModel.refreshShizuku(this@MainActivity)

                    if (isRevoked || exitCode == 0) {
                        Toast.makeText(this@MainActivity, getString(R.string.toast_shizuku_revoke_success), Toast.LENGTH_SHORT).show()
                        onComplete?.invoke(true, null)
                    } else {
                        val errorMsg = getString(R.string.toast_shizuku_revoke_failed, "ExitCode: $exitCode")
                        Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_SHORT).show()
                        onComplete?.invoke(false, errorMsg)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val errorMsg = getString(R.string.toast_shizuku_revoke_failed, e.message ?: "")
                    Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_SHORT).show()
                    onComplete?.invoke(false, errorMsg)
                }
            }
        }
    }

    /**
     * 切换底部主导航页签至指定菜单项（如切换至检测页或记录页）。
     *
     * @param navItemId 底部导航菜单项 ID，如 [R.id.nav_detection]
     */
    fun navigateToNav(navItemId: Int) {
        binding.bottomNavigation.selectedItemId = navItemId
    }

}
