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
 * 统一管理系统 API、Shizuku 底层节点及错误报告解析等三源数据流，供各 Tab 页面协同观察与更新。
 */
class BatteryViewModel : ViewModel() {

    private val normalApiProvider = NormalApiProvider()
    private val shizukuProvider = ShizukuProvider()
    private val bugreportParser = BugreportParser()

    private var bugreportJob: Job? = null

    // 1. 系统 API 电池数据流
    private val _normalBatteryInfo = MutableStateFlow<BatteryInfo?>(null)
    val normalBatteryInfo: StateFlow<BatteryInfo?> = _normalBatteryInfo.asStateFlow()

    // 2. Shizuku 电池数据流
    private val _shizukuBatteryInfo = MutableStateFlow<BatteryInfo?>(null)
    val shizukuBatteryInfo: StateFlow<BatteryInfo?> = _shizukuBatteryInfo.asStateFlow()

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

    /**
     * 刷新普通系统 API 与 Shizuku 底层电池数据。
     *
     * @param context 应用程序上下文
     */
    fun refreshData(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val normal = normalApiProvider.getBatteryInfo(context)
            val shizuku = shizukuProvider.getBatteryInfo(context)

            withContext(Dispatchers.Main) {
                _normalBatteryInfo.value = normal
                _shizukuBatteryInfo.value = shizuku
            }
        }
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
