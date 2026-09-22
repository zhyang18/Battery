package com.battery.analysis.model

/**
 * 应用偏好设置备份数据实体类。
 * 完整承载应用内所有用户设置项，包含多语言、主题、充放电统计、屏幕常亮、耗电模式、Shizuku 偏好、保活监控与采样频率等配置。
 *
 * @property languageMode 语言设置模式（0 跟随系统，1 简体中文，2 English）
 * @property themeMode 主题模式（-1 跟随系统，1 浅色模式，2 深色模式）
 * @property autoRefreshEnabled 是否开启实时自动刷新（保留旧版本兼容）
 * @property refreshIntervalMs 自动刷新时间间隔毫秒值（保留旧版本兼容）
 * @property chargeDischargeStatsEnabled 是否启用充、放电统计功能
 * @property chargingKeepScreenOn 充电时是否保持屏幕常亮
 * @property powerStatsMode 耗电统计检测模式（0 为 Shizuku 模式，1 为标准模式）
 * @property powerModeConfigured 耗电统计检测模式是否已完成初始化引导配置
 * @property shizukuUserDisabled 用户是否主动停用 Shizuku 提权读取
 * @property notificationDisplayEnabled 常驻通知栏监控是否开启显示
 * @property screenOnIntervalMs 亮屏监控刷新间隔（毫秒，-1 为不采样）
 * @property screenOffIntervalMs 息屏待机采样间隔（毫秒，0 为智能省电，-1 为不采样）
 * @property bootAutoStartEnabled 设备重启后是否自动启动后台监控服务
 */
data class BackupSettings(
    val languageMode: Int? = null,
    val themeMode: Int? = null,
    val autoRefreshEnabled: Boolean? = null,
    val refreshIntervalMs: Long? = null,
    val chargeDischargeStatsEnabled: Boolean? = null,
    val chargingKeepScreenOn: Boolean? = null,
    val powerStatsMode: Int? = null,
    val powerModeConfigured: Boolean? = null,
    val shizukuUserDisabled: Boolean? = null,
    val notificationDisplayEnabled: Boolean? = null,
    val screenOnIntervalMs: Long? = null,
    val screenOffIntervalMs: Long? = null,
    val bootAutoStartEnabled: Boolean? = null
)

/**
 * 数据备份与恢复计数汇总实体类。
 * 记录电池检测快照、充电历史与放电历史的条目数量。
 *
 * @property historyCount 电池检测快照记录条数
 * @property chargingCount 充电历史会话记录条数
 * @property powerUsageCount 放电耗电历史记录条数
 */
data class BackupRestoreSummary(
    val historyCount: Int = 0,
    val chargingCount: Int = 0,
    val powerUsageCount: Int = 0
) {
    /**
     * 计算三类记录的总条数。
     *
     * @return 历史记录总条目数量
     */
    fun getTotalCount(): Int {
        return historyCount + chargingCount + powerUsageCount
    }
}

/**
 * 电池数据备份文件根结构实体类。
 * 承载备份协议版本、应用元信息、全量应用偏好配置，以及电池快照、充电历史和放电历史记录列表。
 *
 * @property version 备份协议版本号（当前最新为 2）
 * @property appVersion 导出该备份的应用版本名称
 * @property backupTime 格式化的备份生成时间（例如 "2026-08-19 13:30:00"）
 * @property backupTimestamp 备份生成时的时间戳毫秒值
 * @property settings 应用偏好配置
 * @property historyRecords 电池检测历史快照列表
 * @property chargingRecords 充电历史会话记录列表
 * @property powerUsageRecords 放电耗电历史记录列表
 */
data class BackupData(
    val version: Int = 2,
    val appVersion: String,
    val backupTime: String,
    val backupTimestamp: Long = System.currentTimeMillis(),
    val settings: BackupSettings,
    val historyRecords: List<HistoryRecord> = emptyList(),
    val chargingRecords: List<ChargingHistoryRecord> = emptyList(),
    val powerUsageRecords: List<PowerUsageRecord> = emptyList()
)
