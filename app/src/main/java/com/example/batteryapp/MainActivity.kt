package com.example.batteryapp

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.batteryapp.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * 应用程序的主活动界面。
 * 负责展示顶部沉浸式标题栏、三列电池数据对比（系统API、Shizuku、错误报告导入）、管理右侧抽屉设置及生命周期。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private val SHIZUKU_REQUEST_CODE = 1

    /**
     * 错误报告文件选择器启动器。
     */
    private val openBugreportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            importAndParseBugreport(uri)
        }
    }

    /**
     * 实时刷新的后台任务协程引用。
     */
    private var autoRefreshJob: Job? = null

    /**
     * 实时刷新的时间间隔（毫秒），默认 2000 毫秒（2秒）。
     */
    private var refreshIntervalMs: Long = 2000L

    /**
     * 是否开启实时刷新。
     */
    private var isAutoRefreshEnabled: Boolean = false

    /**
     * 当前是否正在解析错误报告。
     */
    private var isParsingBugreport: Boolean = false

    /**
     * 错误报告解析的后台协程任务引用。
     */
    private var bugreportJob: Job? = null

    /**
     * Shizuku 权限请求回调监听器。
     */
    private val requestPermissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_REQUEST_CODE) {
            updateDrawerShizukuStatus()
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Shizuku 授权成功", Toast.LENGTH_SHORT).show()
                refreshBatteryInfo()
            } else {
                Toast.makeText(this, "未授予 Shizuku 权限，将仅使用普通 API", Toast.LENGTH_SHORT).show()
                refreshBatteryInfo()
            }
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread {
            updateDrawerShizukuStatus()
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                refreshBatteryInfo()
            }
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread {
            updateDrawerShizukuStatus()
        }
    }

    /**
     * 活动创建时调用，开启沉浸式状态栏、处理系统栏边距、加载保存的主题与抽屉设置配置。
     *
     * @param savedInstanceState 包含活动先前保存状态的包
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getSharedPreferences("battery_prefs", Context.MODE_PRIVATE)
        val savedThemeMode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(savedThemeMode)

        super.onCreate(savedInstanceState)

        // 开启沉浸式状态栏与全面屏边到边布局
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 处理主界面内容边距（避让状态栏与导航栏）
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainContent) { view, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(
                view.paddingLeft,
                statusBarInsets.top + 16,
                view.paddingRight,
                navBarInsets.bottom + 16
            )
            insets
        }

        // 处理右侧抽屉内部边距（避让状态栏与导航栏）
        ViewCompat.setOnApplyWindowInsetsListener(binding.drawerSettings) { view, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(
                view.paddingLeft,
                statusBarInsets.top + 20,
                view.paddingRight,
                navBarInsets.bottom + 20
            )
            insets
        }

        updateSystemBarAppearance()

        isAutoRefreshEnabled = prefs.getBoolean("auto_refresh_enabled", false)
        refreshIntervalMs = prefs.getLong("refresh_interval_ms", 2000L)

        Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        // 顶部操作栏事件
        binding.btnRefresh.setOnClickListener {
            refreshBatteryInfo()
            Toast.makeText(this, "数据已刷新", Toast.LENGTH_SHORT).show()
        }

        // 打开右侧抽屉设置
        binding.btnSettings.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.END)
        }

        // 关闭抽屉按钮
        binding.btnCloseDrawer.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.END)
        }

        // 处理返回键拦截（若抽屉打开则优先关闭抽屉）
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

        // 错误报告导入与取消按钮事件
        binding.btnImportBugreport.setOnClickListener {
            if (isParsingBugreport) {
                // 用户点击取消当前解析任务
                bugreportJob?.cancel()
                bugreportJob = null
                isParsingBugreport = false
                binding.btnImportBugreport.text = "导入报告"
                binding.tvBugreportStatus.visibility = android.view.View.VISIBLE
                binding.tvBugreportStatus.text = "已取消错误报告解析"
                Toast.makeText(this, "已取消解析", Toast.LENGTH_SHORT).show()
            } else {
                openBugreportLauncher.launch(arrayOf("*/*", "application/zip", "text/plain"))
            }
        }

        // 初始化右侧抽屉设置面板各个功能组件
        setupDrawerSettings()

        refreshBatteryInfo()
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
                .setSingleChoiceItems(intervals, currentIndex) { intervalDialog, which ->
                    refreshIntervalMs = intervalValues[which]
                    prefs.edit().putLong("refresh_interval_ms", refreshIntervalMs).apply()
                    binding.tvCurrentIntervalDrawer.text = intervals[which]
                    intervalDialog.dismiss()
                    if (isAutoRefreshEnabled) {
                        startAutoRefresh()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 3. Shizuku 授权与状态
        updateDrawerShizukuStatus()
        binding.btnAuthShizukuDrawer.setOnClickListener {
            requestShizukuAuth()
        }
    }

    /**
     * 更新右侧抽屉中 Shizuku 状态的提示文本及颜色。
     */
    private fun updateDrawerShizukuStatus() {
        if (!Shizuku.pingBinder()) {
            binding.tvShizukuStatusDrawer.text = "Shizuku: 未运行"
            binding.tvShizukuStatusDrawer.setTextColor(android.graphics.Color.RED)
        } else {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                binding.tvShizukuStatusDrawer.text = "Shizuku: 已授权"
                binding.tvShizukuStatusDrawer.setTextColor(android.graphics.Color.GREEN)
            } else {
                binding.tvShizukuStatusDrawer.text = "Shizuku: 未授权"
                binding.tvShizukuStatusDrawer.setTextColor(android.graphics.Color.parseColor("#FFA500"))
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
     * 活动在前台启动时调用，如果用户开启了实时刷新则自动恢复轮询。
     */
    override fun onStart() {
        super.onStart()
        if (isAutoRefreshEnabled) {
            startAutoRefresh()
        }
    }

    /**
     * 活动退入后台时调用，暂停实时刷新协程以避免电量浪费。
     */
    override fun onStop() {
        super.onStop()
        stopAutoRefresh()
    }

    /**
     * 活动销毁时调用，注销所有监听器。
     */
    override fun onDestroy() {
        super.onDestroy()
        stopAutoRefresh()
        Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
    }

    /**
     * 主动发起 Shizuku 授权请求。
     */
    private fun requestShizukuAuth() {
        if (Shizuku.pingBinder()) {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                try {
                    Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
                } catch (e: Exception) {
                    Toast.makeText(this, "请求授权失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "已经授权，无需再次请求", Toast.LENGTH_SHORT).show()
                updateDrawerShizukuStatus()
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
            updateDrawerShizukuStatus()
        }
    }

    /**
     * 启动实时自动刷新的后台协程。
     */
    private fun startAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val normalInfo = NormalApiProvider().getBatteryInfo(this@MainActivity)
                val shizukuInfo = ShizukuProvider().getBatteryInfo(this@MainActivity)

                withContext(Dispatchers.Main) {
                    binding.tvNormalInfo.text = normalInfo.formatToString()
                        .lines()
                        .filterNot { it.startsWith("数据来源:") }
                        .joinToString("\n")

                    binding.tvShizukuInfo.text = shizukuInfo.formatToString()
                        .lines()
                        .filterNot { it.startsWith("数据来源:") }
                        .joinToString("\n")
                }
                delay(refreshIntervalMs)
            }
        }
    }

    /**
     * 停止实时刷新的后台协程。
     */
    private fun stopAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    /**
     * 在后台线程中单次执行普通 API 和 Shizuku 数据的获取，并在主线程中分两列更新 UI 文本。
     */
    private fun refreshBatteryInfo() {
        binding.tvNormalInfo.text = "正在获取数据..."
        binding.tvShizukuInfo.text = "正在获取数据..."
        lifecycleScope.launch(Dispatchers.IO) {
            val normalInfo = NormalApiProvider().getBatteryInfo(this@MainActivity)
            val shizukuInfo = ShizukuProvider().getBatteryInfo(this@MainActivity)

            withContext(Dispatchers.Main) {
                binding.tvNormalInfo.text = normalInfo.formatToString()
                    .lines()
                    .filterNot { it.startsWith("数据来源:") }
                    .joinToString("\n")

                binding.tvShizukuInfo.text = shizukuInfo.formatToString()
                    .lines()
                    .filterNot { it.startsWith("数据来源:") }
                    .joinToString("\n")
            }
        }
    }

    /**
     * 异步导入并流式解析用户选中的系统错误报告（Bugreport）日志中的 getHealthInfo 结构体。
     * 支持实时进度百分比更新与中途取消。
     *
     * @param uri 选中的错误报告文件 URI
     */
    private fun importAndParseBugreport(uri: Uri) {
        isParsingBugreport = true
        binding.btnImportBugreport.text = "取消 (0%)"
        binding.tvBugreportStatus.visibility = android.view.View.VISIBLE
        binding.tvBugreportStatus.text = "正在流式解析 getHealthInfo 数据 (0%)... 点击按钮可随时取消"
        binding.scrollHealthTable.visibility = android.view.View.GONE

        bugreportJob?.cancel()
        bugreportJob = lifecycleScope.launch(Dispatchers.IO) {
            val healthItems = BugreportParser().parseHealthInfoTable(this@MainActivity, uri) { percent ->
                lifecycleScope.launch(Dispatchers.Main) {
                    if (isParsingBugreport) {
                        binding.btnImportBugreport.text = "取消 ($percent%)"
                        binding.tvBugreportStatus.text = "正在流式解析 getHealthInfo 数据 ($percent%)... 点击按钮可随时取消"
                    }
                }
            }

            withContext(Dispatchers.Main) {
                if (!isParsingBugreport) return@withContext
                isParsingBugreport = false
                if (healthItems.isNotEmpty()) {
                    binding.btnImportBugreport.text = "重新导入"
                    renderHealthInfoTable(healthItems)
                    Toast.makeText(this@MainActivity, "成功解析 getHealthInfo 结构体", Toast.LENGTH_SHORT).show()
                } else {
                    binding.btnImportBugreport.text = "导入报告"
                    binding.tvBugreportStatus.visibility = android.view.View.VISIBLE
                    binding.tvBugreportStatus.text = "未能从该错误报告中解析出 getHealthInfo 数据，请确保是完整的 bugreport 日志"
                    binding.scrollHealthTable.visibility = android.view.View.GONE
                    Toast.makeText(this@MainActivity, "解析未找到数据或已被取消", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 将解析出的 getHealthInfo 列表动态渲染为三列表格（原始字段、字段映射展示、含义）。
     *
     * @param items 解析出的 HealthInfoItem 列表
     */
    private fun renderHealthInfoTable(items: List<HealthInfoItem>) {
        binding.tableHealthInfo.removeAllViews()

        if (items.isEmpty()) {
            binding.scrollHealthTable.visibility = android.view.View.GONE
            binding.tvBugreportStatus.visibility = android.view.View.VISIBLE
            binding.tvBugreportStatus.text = "未能解析出 getHealthInfo 字段"
            return
        }

        binding.tvBugreportStatus.visibility = android.view.View.GONE
        binding.scrollHealthTable.visibility = android.view.View.VISIBLE

        // 1. 添加三列表头行（原始字段、字段映射展示、含义）
        val headerRow = android.widget.TableRow(this)
        headerRow.setBackgroundColor(android.graphics.Color.parseColor("#25888888"))
        headerRow.setPadding(4, 10, 4, 10)

        val thRaw = createTableCell("原始字段", isHeader = true, textColor = android.graphics.Color.parseColor("#FF9800"), minWidthDp = 180)
        val thVal = createTableCell("字段映射展示", isHeader = true, textColor = android.graphics.Color.parseColor("#4CAF50"), minWidthDp = 160)
        val thMean = createTableCell("含义", isHeader = true, textColor = android.graphics.Color.parseColor("#2196F3"), minWidthDp = 220)

        headerRow.addView(thRaw)
        headerRow.addView(thVal)
        headerRow.addView(thMean)
        binding.tableHealthInfo.addView(headerRow)

        // 2. 逐项添加数据行
        for ((index, item) in items.withIndex()) {
            val row = android.widget.TableRow(this)
            if (index % 2 == 1) {
                row.setBackgroundColor(android.graphics.Color.parseColor("#15888888"))
            }
            row.setPadding(4, 8, 4, 8)

            val tdRaw = createTableCell(item.rawField, isHeader = false, minWidthDp = 180)
            val tdVal = createTableCell(item.displayValue, isHeader = false, isBold = true, minWidthDp = 160)
            val tdMean = createTableCell(item.meaning, isHeader = false, minWidthDp = 220)

            row.addView(tdRaw)
            row.addView(tdVal)
            row.addView(tdMean)
            binding.tableHealthInfo.addView(row)
        }
    }

    /**
     * 创建表格单元格 TextView 视图。
     *
     * @param text 单元格显示的文本
     * @param isHeader 是否为表头
     * @param textColor 自定义文字颜色（可选）
     * @param isBold 是否加粗展示
     * @param minWidthDp 单元格最小宽度（dp）
     * @return 格式化后的 TextView 视图组件
     */
    private fun createTableCell(
        text: String,
        isHeader: Boolean = false,
        textColor: Int? = null,
        isBold: Boolean = false,
        minWidthDp: Int = 120
    ): android.widget.TextView {
        val tv = android.widget.TextView(this)
        tv.text = text
        tv.setPadding(16, 12, 16, 12)
        tv.textSize = if (isHeader) 13f else 12f

        val density = resources.displayMetrics.density
        tv.minWidth = (minWidthDp * density).toInt()

        if (textColor != null) {
            tv.setTextColor(textColor)
        } else {
            val typedValue = android.util.TypedValue()
            if (theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)) {
                if (typedValue.resourceId != 0) {
                    tv.setTextColor(androidx.core.content.ContextCompat.getColor(this, typedValue.resourceId))
                } else if (typedValue.data != 0) {
                    tv.setTextColor(typedValue.data)
                } else {
                    val isNight = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                    tv.setTextColor(if (isNight) android.graphics.Color.WHITE else android.graphics.Color.parseColor("#212121"))
                }
            } else {
                val isNight = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                tv.setTextColor(if (isNight) android.graphics.Color.WHITE else android.graphics.Color.parseColor("#212121"))
            }
        }
        if (isHeader || isBold) {
            tv.typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        return tv
    }
}
