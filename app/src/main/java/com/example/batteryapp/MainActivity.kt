package com.example.batteryapp

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
 * 负责请求 Shizuku 权限、管理实时刷新任务、触发数据收集流程并分两列更新 UI。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val SHIZUKU_REQUEST_CODE = 1

    /**
     * 实时刷新的后台任务协程引用。
     */
    private var autoRefreshJob: Job? = null

    /**
     * 实时刷新的时间间隔（毫秒），默认为 2000 毫秒（2秒）。
     */
    private var refreshIntervalMs: Long = 2000L

    /**
     * Shizuku 权限请求回调监听器。
     */
    private val requestPermissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_REQUEST_CODE) {
            updateShizukuStatusUI()
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
            updateShizukuStatusUI()
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                refreshBatteryInfo()
            }
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread {
            updateShizukuStatusUI()
        }
    }

    /**
     * 活动创建时调用，初始化绑定、监听器、实时刷新开关并进行首次数据刷新。
     *
     * @param savedInstanceState 包含活动先前保存状态的包
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        binding.btnRefresh.setOnClickListener {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                 Toast.makeText(this, "建议先点击授权 Shizuku 以获取完整数据", Toast.LENGTH_SHORT).show()
            }
            refreshBatteryInfo()
        }

        binding.btnAuthShizuku.setOnClickListener {
            requestShizukuAuth()
        }

        // 实时刷新开关监听
        binding.switchAutoRefresh.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startAutoRefresh()
                Toast.makeText(this, "已开启实时刷新 (${refreshIntervalMs / 1000}秒/次)", Toast.LENGTH_SHORT).show()
            } else {
                stopAutoRefresh()
                Toast.makeText(this, "已关闭实时刷新", Toast.LENGTH_SHORT).show()
            }
        }

        // 刷新间隔设置按钮
        binding.btnInterval.setOnClickListener {
            showIntervalSelectionDialog()
        }

        updateShizukuStatusUI()
        refreshBatteryInfo()
    }

    /**
     * 活动在前台启动时调用，若开关打开则恢复实时刷新任务。
     */
    override fun onStart() {
        super.onStart()
        if (binding.switchAutoRefresh.isChecked) {
            startAutoRefresh()
        }
    }

    /**
     * 活动恢复到前台时调用，更新 Shizuku 状态显示。
     */
    override fun onResume() {
        super.onResume()
        updateShizukuStatusUI()
    }

    /**
     * 活动退入后台时调用，暂停实时刷新协程以避免无谓的电量消耗。
     */
    override fun onStop() {
        super.onStop()
        stopAutoRefresh()
    }

    /**
     * 活动销毁时调用，移除对应的监听器。
     */
    override fun onDestroy() {
        super.onDestroy()
        stopAutoRefresh()
        Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
    }

    /**
     * 弹出单选对话框，允许用户选择实时刷新的时间间隔。
     */
    private fun showIntervalSelectionDialog() {
        val intervals = arrayOf("1 秒", "2 秒", "3 秒", "5 秒", "10 秒")
        val intervalValues = arrayOf(1000L, 2000L, 3000L, 5000L, 10000L)
        val currentIndex = intervalValues.indexOf(refreshIntervalMs).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("设置实时刷新间隔")
            .setSingleChoiceItems(intervals, currentIndex) { dialog, which ->
                refreshIntervalMs = intervalValues[which]
                binding.btnInterval.text = "刷新间隔: ${intervals[which]}"
                dialog.dismiss()
                if (binding.switchAutoRefresh.isChecked) {
                    startAutoRefresh()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 启动实时刷新的协程循环。
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
     * 停止实时刷新的协程任务。
     */
    private fun stopAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    /**
     * 更新界面上关于 Shizuku 连接与授权状态的文本和颜色。
     */
    private fun updateShizukuStatusUI() {
        if (!Shizuku.pingBinder()) {
            binding.tvShizukuStatus.text = "Shizuku 状态: 未运行"
            binding.tvShizukuStatus.setTextColor(android.graphics.Color.RED)
            binding.btnAuthShizuku.isEnabled = true
        } else {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                binding.tvShizukuStatus.text = "Shizuku 状态: 已授权"
                binding.tvShizukuStatus.setTextColor(android.graphics.Color.GREEN)
                binding.btnAuthShizuku.isEnabled = true
            } else {
                binding.tvShizukuStatus.text = "Shizuku 状态: 未授权"
                binding.tvShizukuStatus.setTextColor(android.graphics.Color.parseColor("#FFA500"))
                binding.btnAuthShizuku.isEnabled = true
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
                updateShizukuStatusUI()
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
            updateShizukuStatusUI()
        }
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
