package com.battery.analysis.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.battery.analysis.model.BatteryInfo
import com.battery.analysis.model.BugreportResult
import com.battery.analysis.provider.BugreportParser
import com.battery.analysis.provider.NormalApiProvider
import com.battery.analysis.provider.ShizukuProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 电池数据共享与业务状态管理的 ViewModel。
 * 负责将系统原生 API、Shizuku 底层驱动节点与错误报告流式解析彻底解耦，提供独立并发且互不干扰的刷新管道与状态流。
 */
class BatteryViewModel : ViewModel() {

    private val normalApiProvider = NormalApiProvider()
    private val shizukuProvider = ShizukuProvider()
    private val bugreportParser = BugreportParser()

    private var normalApiJob: Job? = null
    private var shizukuJob: Job? = null
    private var bugreportJob: Job? = null

    // 1. 系统 API 电池数据流与刷新状态
    private val _normalBatteryInfo = MutableStateFlow<BatteryInfo?>(null)
    val normalBatteryInfo: StateFlow<BatteryInfo?> = _normalBatteryInfo.asStateFlow()

    private val _isNormalRefreshing = MutableStateFlow(false)
    val isNormalRefreshing: StateFlow<Boolean> = _isNormalRefreshing.asStateFlow()

    // 2. Shizuku 电池数据流与刷新状态
    private val _shizukuBatteryInfo = MutableStateFlow<BatteryInfo?>(null)
    val shizukuBatteryInfo: StateFlow<BatteryInfo?> = _shizukuBatteryInfo.asStateFlow()

    private val _isShizukuRefreshing = MutableStateFlow(false)
    val isShizukuRefreshing: StateFlow<Boolean> = _isShizukuRefreshing.asStateFlow()

    // 3. 错误报告解析结果流
    private val _bugreportResult = MutableStateFlow<BugreportResult?>(null)
    val bugreportResult: StateFlow<BugreportResult?> = _bugreportResult.asStateFlow()

    // 4. 错误报告解析状态
    private val _isParsingBugreport = MutableStateFlow(false)
    val isParsingBugreport: StateFlow<Boolean> = _isParsingBugreport.asStateFlow()

    private val _bugreportProgress = MutableStateFlow(0)
    val bugreportProgress: StateFlow<Int> = _bugreportProgress.asStateFlow()

    private val _bugreportStatus = MutableStateFlow("未导入错误报告日志。点击下方按钮选择 bugreport.zip 或 txt 提取底层数据。")
    val bugreportStatus: StateFlow<String> = _bugreportStatus.asStateFlow()

    // 5. Shizuku 连接与权限状态
    private val _shizukuStatus = MutableStateFlow("检查中...")
    val shizukuStatus: StateFlow<String> = _shizukuStatus.asStateFlow()

    private val _isShizukuGranted = MutableStateFlow(false)
    val isShizukuGranted: StateFlow<Boolean> = _isShizukuGranted.asStateFlow()

    // 6. 当前正展示的活跃子 Tab 索引（0: 系统api, 1: Shizuku, 2: 错误报告）
    private val _activeTabPosition = MutableStateFlow(0)
    val activeTabPosition: StateFlow<Int> = _activeTabPosition.asStateFlow()

    /**
     * 更新当前处于活跃展示状态的子 Tab 索引。
     *
     * @param position 当前选中的子 Tab 索引
     */
    fun updateActiveTab(position: Int) {
        _activeTabPosition.value = position
    }

    /**
     * 独立刷新系统普通 API 电池数据。
     * 完全解耦，极速读取 (< 5ms)，绝不阻塞任何其他后台任务。
     *
     * @param context 应用程序上下文
     */
    fun refreshNormalApi(context: Context) {
        val appCtx = context.applicationContext
        if (normalApiJob?.isActive == true) {
            return
        }
        _isNormalRefreshing.value = true

        normalApiJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val normal = normalApiProvider.getBatteryInfo(appCtx)
                withContext(Dispatchers.Main) {
                    _normalBatteryInfo.value = normal
                }
            } finally {
                withContext(Dispatchers.Main) {
                    _isNormalRefreshing.value = false
                }
            }
        }
    }

    /**
     * 独立刷新 Shizuku 底层驱动节点电池数据。
     * 完全解耦，非打断式异步后台执行，确保每一次读取都能稳定更新至 UI。
     *
     * @param context 应用程序上下文
     */
    fun refreshShizuku(context: Context) {
        val appCtx = context.applicationContext
        if (shizukuJob?.isActive == true) {
            return
        }
        _isShizukuRefreshing.value = true

        shizukuJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val shizuku = shizukuProvider.getBatteryInfo(appCtx)
                withContext(Dispatchers.Main) {
                    _shizukuBatteryInfo.value = shizuku
                }
            } finally {
                withContext(Dispatchers.Main) {
                    _isShizukuRefreshing.value = false
                }
            }
        }
    }

    /**
     * 同时触发系统 API 与 Shizuku 数据的刷新（两个任务完全独立并发执行）。
     *
     * @param context 应用程序上下文
     */
    fun refreshData(context: Context) {
        refreshNormalApi(context)
        refreshShizuku(context)
    }

    /**
     * 更新 Shizuku 服务运行状态与权限授权状态。
     *
     * @param statusText Shizuku 状态描述文本
     * @param isGranted 是否已成功获取 Shizuku 权限
     */
    fun updateShizukuStatus(statusText: String, isGranted: Boolean) {
        _shizukuStatus.value = statusText
        _isShizukuGranted.value = isGranted
    }

    /**
     * 导入并异步解析选定的错误报告文件。
     *
     * @param context 应用程序上下文
     * @param uri 错误报告文件 URI
     */
    fun importBugreport(context: Context, uri: Uri) {
        bugreportJob?.cancel()
        _isParsingBugreport.value = true
        _bugreportProgress.value = 0
        _bugreportStatus.value = "正在快速索引并流式解析错误报告..."

        bugreportJob = viewModelScope.launch {
            val result = bugreportParser.parseHealthInfo(context, uri) { progress ->
                viewModelScope.launch(Dispatchers.Main) {
                    if (_isParsingBugreport.value) {
                        _bugreportProgress.value = progress
                        _bugreportStatus.value = "正在流式解析 getHealthInfo 数据 ($progress%)... 点击可随时取消"
                    }
                }
            }

            _isParsingBugreport.value = false

            if (result != null && result.hasRealData) {
                _bugreportResult.value = result
                _bugreportStatus.value = "已成功提取 getHealthInfo 核心电池数据"
            } else {
                if (result != null && result.rawHealthInfoText.isNotEmpty()) {
                    _bugreportResult.value = result
                }
                _bugreportStatus.value = "未能从该错误报告中解析出完整 getHealthInfo 数据"
            }
        }
    }

    /**
     * 取消当前正在进行的错误报告解析任务。
     */
    fun cancelBugreportParsing() {
        if (_isParsingBugreport.value) {
            bugreportJob?.cancel()
            bugreportJob = null
            _isParsingBugreport.value = false
            _bugreportStatus.value = "已取消错误报告解析"
        }
    }
}
