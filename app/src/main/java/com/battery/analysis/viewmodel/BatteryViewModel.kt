package com.battery.analysis.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.battery.analysis.db.HistoryDbHelper
import com.battery.analysis.manager.BackupManager
import com.battery.analysis.model.BackupData
import com.battery.analysis.model.BatteryInfo
import com.battery.analysis.model.BugreportResult
import com.battery.analysis.model.HealthTrendPoint
import com.battery.analysis.model.HistoryRecord
import com.battery.analysis.provider.BugreportParser
import com.battery.analysis.provider.NormalApiProvider
import com.battery.analysis.provider.ShizukuProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 电池数据共享与业务状态管理的 ViewModel。
 * 统一管理系统原生 API、Shizuku 底层驱动节点、错误报告解析以及按数据源分类的历史持久化等业务数据流。
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

    // 7. 电池历史记录全量列表
    private val _historyRecords = MutableStateFlow<List<HistoryRecord>>(emptyList())
    val historyRecords: StateFlow<List<HistoryRecord>> = _historyRecords.asStateFlow()

    // 8. 固定三大数据源分类体系（全部、系统api、Shizuku、错误报告）
    private val _selectedCategory = MutableStateFlow("全部")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    val categoryList: List<String> = listOf("全部", "系统api", "Shizuku", "错误报告")

    /**
     * 根据当前选中的数据源分类自动筛选过滤后的历史记录流。
     */
    val filteredHistoryRecords: StateFlow<List<HistoryRecord>> = combine(_historyRecords, _selectedCategory) { records, category ->
        if (category == "全部") {
            records
        } else {
            records.filter { it.category == category }
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /**
     * 根据当前分类筛选结果提取的健康度衰减趋势数据点列表（按采样时间从早到晚升序排列）。
     * 采用单次反向遍历高效构建，消除原有多重中间 List 分配与额外排序开销。
     */
    val healthTrendPoints: StateFlow<List<HealthTrendPoint>> = filteredHistoryRecords.map { records ->
        if (records.isEmpty()) return@map emptyList()
        val result = ArrayList<HealthTrendPoint>(records.size)
        // records 在数据库查询时按 id DESC 倒序输出，反向遍历即为从早到晚自然升序
        for (i in records.indices.reversed()) {
            val record = records[i]
            val health = record.batteryHealth
            if (health != null && health > 0f) {
                val displayTime = if (record.captureTime.length >= 16) {
                    record.captureTime.substring(5, 16)
                } else {
                    record.captureTime
                }
                result.add(
                    HealthTrendPoint(
                        id = record.id,
                        captureTime = record.captureTime,
                        displayTime = displayTime,
                        health = health,
                        cycleCount = record.cycleCount,
                        fullCapacity = record.fullChargeCapacity,
                        designCapacity = record.designCapacity,
                        category = record.category
                    )
                )
            }
        }
        result
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /**
     * 根据当前分类筛选下的健康度趋势数据点自动计算的衰减统计流（包含日/月/年衰减值与周期明细）。
     */
    val decayStatistics: StateFlow<com.battery.analysis.model.DecayStatistics> = healthTrendPoints.map { points ->
        com.battery.analysis.util.BatteryDecayCalculator.calculate(points)
    }.stateIn(viewModelScope, SharingStarted.Lazily, com.battery.analysis.model.DecayStatistics())

    /**
     * 更新当前处于活跃展示状态的子 Tab 索引。
     *
     * @param position 当前选中的子 Tab 索引
     */
    fun updateActiveTab(position: Int) {
        _activeTabPosition.value = position
    }

    /**
     * 切换历史记录的当前选中筛选分类（全部、系统api、Shizuku、错误报告）。
     *
     * @param category 目标分类名称
     */
    fun setSelectedCategory(category: String) {
        _selectedCategory.value = category
    }

    /**
     * 独立刷新系统普通 API 电池数据。
     * 完全解耦，极速读取 (< 5ms)，非打断式并发保护。
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
        val appCtx = context.applicationContext
        bugreportJob?.cancel()
        _isParsingBugreport.value = true
        _bugreportProgress.value = 0
        _bugreportStatus.value = appCtx.getString(com.battery.analysis.R.string.bugreport_status_indexing)

        bugreportJob = viewModelScope.launch {
            val result = bugreportParser.parseHealthInfo(appCtx, uri) { progress ->
                viewModelScope.launch(Dispatchers.Main) {
                    if (_isParsingBugreport.value) {
                        _bugreportProgress.value = progress
                        _bugreportStatus.value = appCtx.getString(com.battery.analysis.R.string.bugreport_status_parsing, progress)
                    }
                }
            }

            _isParsingBugreport.value = false

            if (result != null && result.hasRealData) {
                _bugreportResult.value = result
                _bugreportStatus.value = appCtx.getString(com.battery.analysis.R.string.bugreport_status_success)
            } else {
                if (result != null && result.rawHealthInfoText.isNotEmpty()) {
                    _bugreportResult.value = result
                }
                _bugreportStatus.value = appCtx.getString(com.battery.analysis.R.string.bugreport_status_failed)
            }
        }
    }

    /**
     * 取消当前正在进行的错误报告解析任务。
     *
     * @param context 可选应用程序上下文
     */
    fun cancelBugreportParsing(context: Context? = null) {
        if (_isParsingBugreport.value) {
            bugreportJob?.cancel()
            bugreportJob = null
            _isParsingBugreport.value = false
            _bugreportStatus.value = context?.getString(com.battery.analysis.R.string.bugreport_status_cancelled) ?: "已取消错误报告解析"
        }
    }

    /**
     * 初始化加载保存快照中最新一条错误报告数据。
     * 若当前未处于解析中且未导入新的错误报告，自动从数据库载入最新快照呈现。
     *
     * @param context 应用程序上下文
     */
    fun loadLatestBugreportFromHistory(context: Context) {
        if (_isParsingBugreport.value) {
            return
        }
        if (_bugreportResult.value != null && _bugreportResult.value?.hasRealData == true) {
            return
        }
        val appCtx = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            val dbHelper = HistoryDbHelper.getInstance(appCtx)
            val latestRecord = dbHelper.getLatestRecordByCategory("错误报告")
            if (latestRecord != null) {
                val batteryInfo = latestRecord.toBatteryInfo(appCtx.getString(com.battery.analysis.R.string.tab_bugreport) + " (" + appCtx.getString(com.battery.analysis.R.string.nav_history) + ")")
                val isZh = Locale.getDefault().language.startsWith("zh")
                val header = if (isZh) {
                    "【历史快照载入】\n检测时间: ${latestRecord.captureTime}\n数据来源: 错误报告快照\n"
                } else {
                    "[History Snapshot Loaded]\nCapture Time: ${latestRecord.captureTime}\nSource: Bugreport Snapshot\n"
                }
                val bugreportResult = BugreportResult(
                    tableItems = emptyList(),
                    rawHealthInfoText = "$header${latestRecord.formatFullDetails(appCtx)}",
                    hasRealData = true,
                    parsedBatteryInfo = batteryInfo
                )
                withContext(Dispatchers.Main) {
                    if (_bugreportResult.value == null || !_bugreportResult.value!!.hasRealData) {
                        _bugreportResult.value = bugreportResult
                        _bugreportStatus.value = appCtx.getString(com.battery.analysis.R.string.bugreport_status_auto_loaded, latestRecord.captureTime)
                    }
                }
            }
        }
    }

    /**
     * 从本地 SQLite 数据库中异步加载全部历史记录。
     *
     * @param context 应用程序上下文
     */
    fun loadHistoryRecords(context: Context) {
        val appCtx = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            val dbHelper = HistoryDbHelper.getInstance(appCtx)
            val list = dbHelper.getAllRecords()
            withContext(Dispatchers.Main) {
                _historyRecords.value = list
            }
        }
    }

    /**
     * 一次性同时保存系统api、Shizuku、错误报告三种分类的电池快照数据。
     *
     * @param context 应用程序上下文
     * @param onComplete 回调函数，返回本次成功同时保存的分类名称列表（如 ["系统api", "Shizuku"]）
     */
    fun saveAllSnapshots(context: Context, onComplete: (List<String>) -> Unit) {
        val appCtx = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            val baseTime = System.currentTimeMillis()
            val recordsToInsert = mutableListOf<HistoryRecord>()
            val savedCategories = mutableListOf<String>()

            // 1. 系统 API 数据
            val normalInfo = _normalBatteryInfo.value ?: normalApiProvider.getBatteryInfo(appCtx)
            if (normalInfo.level != null || normalInfo.voltage != null) {
                recordsToInsert.add(HistoryRecord.fromBatteryInfo(normalInfo, "系统api", baseTime))
                savedCategories.add("系统api")
            }

            // 2. Shizuku 底层驱动数据
            val shizukuInfo = _shizukuBatteryInfo.value
            if (shizukuInfo != null && (shizukuInfo.designCapacity != null || shizukuInfo.cycleCount != null || shizukuInfo.level != null)) {
                recordsToInsert.add(HistoryRecord.fromBatteryInfo(shizukuInfo, "Shizuku", baseTime + 1))
                savedCategories.add("Shizuku")
            }

            // 3. 错误报告数据
            val bugreportInfo = _bugreportResult.value?.parsedBatteryInfo
            if (bugreportInfo != null && (bugreportInfo.designCapacity != null || bugreportInfo.cycleCount != null || bugreportInfo.level != null)) {
                recordsToInsert.add(HistoryRecord.fromBatteryInfo(bugreportInfo, "错误报告", baseTime + 2))
                savedCategories.add("错误报告")
            }

            if (recordsToInsert.isNotEmpty()) {
                val dbHelper = HistoryDbHelper.getInstance(appCtx)
                dbHelper.insertRecords(recordsToInsert)
                val updatedList = dbHelper.getAllRecords()
                withContext(Dispatchers.Main) {
                    _historyRecords.value = updatedList
                    onComplete(savedCategories)
                }
            } else {
                withContext(Dispatchers.Main) {
                    onComplete(emptyList())
                }
            }
        }
    }

    /**
     * 根据主键 ID 删除指定的单条历史记录并刷新列表。
     *
     * @param context 应用程序上下文
     * @param id 目标历史记录 ID
     */
    fun deleteHistoryRecord(context: Context, id: Long) {
        val appCtx = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            val dbHelper = HistoryDbHelper.getInstance(appCtx)
            dbHelper.deleteRecord(id)
            val updatedList = dbHelper.getAllRecords()
            withContext(Dispatchers.Main) {
                _historyRecords.value = updatedList
            }
        }
    }

    /**
     * 清空全部电池检测历史记录。
     *
     * @param context 应用程序上下文
     */
    fun clearAllHistory(context: Context) {
        val appCtx = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            val dbHelper = HistoryDbHelper.getInstance(appCtx)
            dbHelper.clearAll()
            withContext(Dispatchers.Main) {
                _historyRecords.value = emptyList()
            }
        }
    }

    /**
     * 导出全量电池检测记录及应用设置至指定的 SAF 备份文件 URI。
     *
     * @param context 应用程序上下文
     * @param uri 用户选定的目标保存文件 URI
     * @param onResult 导出完成回调函数，包含成功导出的记录数或异常信息
     */
    fun exportBackup(context: Context, uri: Uri, onResult: (Result<Int>) -> Unit) {
        val appCtx = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            val dbHelper = HistoryDbHelper.getInstance(appCtx)
            val allRecords = dbHelper.getAllRecords()
            val result = BackupManager.getInstance().exportBackupToUri(appCtx, uri, allRecords)
            withContext(Dispatchers.Main) {
                onResult(result)
            }
        }
    }

    /**
     * 读取并解析指定 URI 中的备份文件元数据，供前端展示恢复预览与确认弹窗。
     *
     * @param context 应用程序上下文
     * @param uri 用户选定的备份文件 URI
     * @param onResult 读取解析完成回调函数，包含解析出的 [BackupData] 或异常信息
     */
    fun readBackupPreview(context: Context, uri: Uri, onResult: (Result<BackupData>) -> Unit) {
        val appCtx = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            val result = BackupManager.getInstance().importBackupPreview(appCtx, uri)
            withContext(Dispatchers.Main) {
                onResult(result)
            }
        }
    }

    /**
     * 执行数据恢复：将备份数据写入本地数据库，按需同步设置，并即时刷新全量历史数据流。
     *
     * @param context 应用程序上下文
     * @param backupData 待恢复的备份数据对象
     * @param isOverwrite 是否覆盖现有数据（true 为清空后重写，false 为合并去重）
     * @param restoreSettings 是否同步恢复应用偏好配置
     * @param onResult 恢复完成回调函数，包含恢复成功的记录数或异常信息
     */
    fun restoreBackup(
        context: Context,
        backupData: BackupData,
        isOverwrite: Boolean,
        restoreSettings: Boolean,
        onResult: (Result<Int>) -> Unit
    ) {
        val appCtx = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            val result = BackupManager.getInstance().restoreBackup(appCtx, backupData, isOverwrite, restoreSettings)
            val dbHelper = HistoryDbHelper.getInstance(appCtx)
            val updatedList = dbHelper.getAllRecords()
            withContext(Dispatchers.Main) {
                _historyRecords.value = updatedList
                onResult(result)
            }
        }
    }
}
