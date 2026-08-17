package com.example.batteryapp

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
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
 * 负责展示顶部精简标题栏、双列电池数据对比、管理全局设置（主题/深色模式、Shizuku授权、实时刷新）及生命周期。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private val SHIZUKU_REQUEST_CODE = 1

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
     * 当前打开的设置对话框弱引用或组件，用于动态更新内部 Shizuku 状态。
     */
    private var settingsDialogShizukuStatusTv: TextView? = null

    /**
     * Shizuku 权限请求回调监听器。
     */
    private val requestPermissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_REQUEST_CODE) {
            updateSettingsDialogShizukuStatus()
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
            updateSettingsDialogShizukuStatus()
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                refreshBatteryInfo()
            }
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread {
            updateSettingsDialogShizukuStatus()
        }
    }

    /**
     * 活动创建时调用，加载保存的主题配置、初始化界面并加载电池信息。
     *
     * @param savedInstanceState 包含活动先前保存状态的包
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getSharedPreferences("battery_prefs", Context.MODE_PRIVATE)
        val savedThemeMode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(savedThemeMode)

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        refreshBatteryInfo()
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
        settingsDialogShizukuStatusTv = null
        Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
    }

    /**
     * 弹出全局综合设置对话框（包含主题/深色模式、实时刷新、Shizuku授权）。
     */
    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        // 1. 主题/深色模式设置
        val rgTheme = dialogView.findViewById<RadioGroup>(R.id.rg_theme_mode)
        val rbSystem = dialogView.findViewById<RadioButton>(R.id.rb_theme_system)
        val rbLight = dialogView.findViewById<RadioButton>(R.id.rb_theme_light)
        val rbDark = dialogView.findViewById<RadioButton>(R.id.rb_theme_dark)

        val currentThemeMode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        when (currentThemeMode) {
            AppCompatDelegate.MODE_NIGHT_NO -> rbLight.isChecked = true
            AppCompatDelegate.MODE_NIGHT_YES -> rbDark.isChecked = true
            else -> rbSystem.isChecked = true
        }

        rgTheme.setOnCheckedChangeListener { _, checkedId ->
            val newMode = when (checkedId) {
                R.id.rb_theme_light -> AppCompatDelegate.MODE_NIGHT_NO
                R.id.rb_theme_dark -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            prefs.edit().putInt("theme_mode", newMode).apply()
            AppCompatDelegate.setDefaultNightMode(newMode)
        }

        // 2. 实时刷新设置
        val switchAutoRefresh = dialogView.findViewById<SwitchCompat>(R.id.switch_auto_refresh_dialog)
        val layoutInterval = dialogView.findViewById<LinearLayout>(R.id.layout_interval_setting)
        val tvInterval = dialogView.findViewById<TextView>(R.id.tv_current_interval)

        switchAutoRefresh.isChecked = isAutoRefreshEnabled
        tvInterval.text = "${refreshIntervalMs / 1000} 秒"

        switchAutoRefresh.setOnCheckedChangeListener { _, isChecked ->
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

        layoutInterval.setOnClickListener {
            val intervals = arrayOf("1 秒", "2 秒", "3 秒", "5 秒", "10 秒")
            val intervalValues = arrayOf(1000L, 2000L, 3000L, 5000L, 10000L)
            val currentIndex = intervalValues.indexOf(refreshIntervalMs).coerceAtLeast(0)

            AlertDialog.Builder(this)
                .setTitle("选择刷新时间间隔")
                .setSingleChoiceItems(intervals, currentIndex) { intervalDialog, which ->
                    refreshIntervalMs = intervalValues[which]
                    prefs.edit().putLong("refresh_interval_ms", refreshIntervalMs).apply()
                    tvInterval.text = intervals[which]
                    intervalDialog.dismiss()
                    if (isAutoRefreshEnabled) {
                        startAutoRefresh()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 3. Shizuku 授权与状态
        settingsDialogShizukuStatusTv = dialogView.findViewById(R.id.tv_shizuku_status_dialog)
        val btnAuthShizuku = dialogView.findViewById<Button>(R.id.btn_auth_shizuku_dialog)
        updateSettingsDialogShizukuStatus()

        btnAuthShizuku.setOnClickListener {
            requestShizukuAuth()
        }

        // 4. 关闭完成按钮
        dialogView.findViewById<Button>(R.id.btn_close_settings).setOnClickListener {
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            settingsDialogShizukuStatusTv = null
        }

        dialog.show()
    }

    /**
     * 更新设置弹窗中 Shizuku 状态的提示文本及颜色。
     */
    private fun updateSettingsDialogShizukuStatus() {
        val tv = settingsDialogShizukuStatusTv ?: return
        if (!Shizuku.pingBinder()) {
            tv.text = "Shizuku: 未运行"
            tv.setTextColor(android.graphics.Color.RED)
        } else {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                tv.text = "Shizuku: 已授权"
                tv.setTextColor(android.graphics.Color.GREEN)
            } else {
                tv.text = "Shizuku: 未授权"
                tv.setTextColor(android.graphics.Color.parseColor("#FFA500"))
            }
        }
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
                updateSettingsDialogShizukuStatus()
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
            updateSettingsDialogShizukuStatus()
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
}
