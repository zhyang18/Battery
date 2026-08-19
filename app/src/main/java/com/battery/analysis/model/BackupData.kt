package com.battery.analysis.model

/**
 * 应用偏好设置备份数据实体类。
 * 包含主题模式、自动刷新状态及刷新时间间隔等配置。
 *
 * @property themeMode 主题模式（跟随系统、深色模式或浅色模式）
 * @property autoRefreshEnabled 是否开启实时自动刷新
 * @property refreshIntervalMs 自动刷新时间间隔（单位：毫秒）
 */
data class BackupSettings(
    val themeMode: Int? = null,
    val autoRefreshEnabled: Boolean? = null,
    val refreshIntervalMs: Long? = null
)

/**
 * 电池数据备份文件根结构实体类。
 * 承载备份元信息、应用配置以及全量历史检测记录列表。
 *
 * @property version 备份协议版本号
 * @property appVersion 导出该备份的应用版本名称
 * @property backupTime 格式化的备份生成时间（例如 "2026-08-19 13:30:00"）
 * @property backupTimestamp 备份生成时的时间戳毫秒值
 * @property settings 应用偏好配置
 * @property historyRecords 电池检测历史快照列表
 */
data class BackupData(
    val version: Int = 1,
    val appVersion: String,
    val backupTime: String,
    val backupTimestamp: Long = System.currentTimeMillis(),
    val settings: BackupSettings,
    val historyRecords: List<HistoryRecord>
)
