package com.battery.analysis.manager

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.battery.analysis.db.HistoryDbHelper
import com.battery.analysis.model.BackupData
import com.battery.analysis.model.BackupSettings
import com.battery.analysis.model.HistoryRecord
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 电池数据备份与恢复业务管理类。
 * 负责将历史快照数据库记录及应用偏好配置序列化为标准 JSON 格式并写入外部存储，
 * 以及从备份文件流中读取、校验、反序列化并按覆盖或合并策略恢复至本地数据库与设置中。
 */
class BackupManager private constructor() {

    /**
     * 获取当前应用程序的版本名称（例如 "1.2.1"）。
     *
     * @param context 应用程序上下文
     * @return 应用版本名称字符串
     */
    fun getAppVersionName(context: Context): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    /**
     * 将当前应用配置与历史记录列表序列化为格式化缩进的 JSON 字符串。
     *
     * @param context 应用程序上下文
     * @param historyRecords 待导出的历史快照列表
     * @return 格式化后的 JSON 字符串
     */
    fun generateBackupJson(context: Context, historyRecords: List<HistoryRecord>): String {
        val prefs = context.getSharedPreferences("battery_app_settings", Context.MODE_PRIVATE)
        val now = Date()
        val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val backupTime = timeFormat.format(now)

        val rootJson = JSONObject()
        rootJson.put("version", BACKUP_SCHEMA_VERSION)
        rootJson.put("app_version", getAppVersionName(context))
        rootJson.put("backup_time", backupTime)
        rootJson.put("backup_timestamp", now.time)

        // 1. 设置配置项打包
        val settingsJson = JSONObject()
        if (prefs.contains("theme_mode")) {
            settingsJson.put("theme_mode", prefs.getInt("theme_mode", -1))
        }
        if (prefs.contains("auto_refresh_enabled")) {
            settingsJson.put("auto_refresh_enabled", prefs.getBoolean("auto_refresh_enabled", false))
        }
        if (prefs.contains("refresh_interval_ms")) {
            settingsJson.put("refresh_interval_ms", prefs.getLong("refresh_interval_ms", 2000L))
        }
        rootJson.put("settings", settingsJson)

        // 2. 历史记录列表打包
        val recordsArray = JSONArray()
        for (record in historyRecords) {
            val recordJson = JSONObject()
            recordJson.put("id", record.id)
            recordJson.put("capture_time", record.captureTime)
            recordJson.put("source", record.source)
            recordJson.put("category", record.category)
            record.note?.let { recordJson.put("note", it) }
            record.batteryHealth?.let { recordJson.put("battery_health", it.toDouble()) }
            record.healthStatus?.let { recordJson.put("health_status", it) }
            record.level?.let { recordJson.put("level", it) }
            record.status?.let { recordJson.put("status", it) }
            record.designCapacity?.let { recordJson.put("design_capacity", it.toDouble()) }
            record.currentCapacity?.let { recordJson.put("current_capacity", it.toDouble()) }
            record.fullChargeCapacity?.let { recordJson.put("full_charge_capacity", it.toDouble()) }
            record.cycleCount?.let { recordJson.put("cycle_count", it) }
            record.temperature?.let { recordJson.put("temperature", it.toDouble()) }
            record.voltage?.let { recordJson.put("voltage", it.toDouble()) }
            record.currentNow?.let { recordJson.put("current_now", it.toDouble()) }
            record.powerWatts?.let { recordJson.put("power_watts", it.toDouble()) }
            record.isDualCell?.let { recordJson.put("is_dual_cell", it) }
            record.technology?.let { recordJson.put("technology", it) }
            recordsArray.put(recordJson)
        }
        rootJson.put("history_records", recordsArray)

        return rootJson.toString(2)
    }

    /**
     * 将 JSON 字符串解析为 [BackupData] 实体对象。
     *
     * @param jsonString 待解析的备份 JSON 字符串
     * @return 解析完成的 [BackupData] 对象
     * @throws IllegalArgumentException 当 JSON 缺少关键标识或格式异常时抛出
     */
    fun parseBackupJson(jsonString: String): BackupData {
        val rootJson = JSONObject(jsonString)
        val version = rootJson.optInt("version", 1)
        val appVersion = rootJson.optString("app_version", "1.0.0")
        val backupTime = rootJson.optString("backup_time", "")
        val backupTimestamp = rootJson.optLong("backup_timestamp", System.currentTimeMillis())

        val settingsJson = rootJson.optJSONObject("settings")
        val settings = if (settingsJson != null) {
            BackupSettings(
                themeMode = if (settingsJson.has("theme_mode")) settingsJson.getInt("theme_mode") else null,
                autoRefreshEnabled = if (settingsJson.has("auto_refresh_enabled")) settingsJson.getBoolean("auto_refresh_enabled") else null,
                refreshIntervalMs = if (settingsJson.has("refresh_interval_ms")) settingsJson.getLong("refresh_interval_ms") else null
            )
        } else {
            BackupSettings()
        }

        val recordsArray = rootJson.optJSONArray("history_records")
        val historyRecords = mutableListOf<HistoryRecord>()

        if (recordsArray != null) {
            for (i in 0 until recordsArray.length()) {
                val item = recordsArray.getJSONObject(i)
                val id = item.optLong("id", System.currentTimeMillis() + i)
                val captureTime = item.optString("capture_time", "")
                val source = item.optString("source", "系统api")
                val category = item.optString("category", source)
                val note = if (item.has("note") && !item.isNull("note")) item.getString("note") else null
                val batteryHealth = if (item.has("battery_health") && !item.isNull("battery_health")) item.getDouble("battery_health").toFloat() else null
                val healthStatus = if (item.has("health_status") && !item.isNull("health_status")) item.getString("health_status") else null
                val level = if (item.has("level") && !item.isNull("level")) item.getInt("level") else null
                val status = if (item.has("status") && !item.isNull("status")) item.getString("status") else null
                val designCapacity = if (item.has("design_capacity") && !item.isNull("design_capacity")) item.getDouble("design_capacity").toFloat() else null
                val currentCapacity = if (item.has("current_capacity") && !item.isNull("current_capacity")) item.getDouble("current_capacity").toFloat() else null
                val fullChargeCapacity = if (item.has("full_charge_capacity") && !item.isNull("full_charge_capacity")) item.getDouble("full_charge_capacity").toFloat() else null
                val cycleCount = if (item.has("cycle_count") && !item.isNull("cycle_count")) item.getInt("cycle_count") else null
                val temperature = if (item.has("temperature") && !item.isNull("temperature")) item.getDouble("temperature").toFloat() else null
                val voltage = if (item.has("voltage") && !item.isNull("voltage")) item.getDouble("voltage").toFloat() else null
                val currentNow = if (item.has("current_now") && !item.isNull("current_now")) item.getDouble("current_now").toFloat() else null
                val powerWatts = if (item.has("power_watts") && !item.isNull("power_watts")) item.getDouble("power_watts").toFloat() else null
                val isDualCell = if (item.has("is_dual_cell") && !item.isNull("is_dual_cell")) item.getBoolean("is_dual_cell") else null
                val technology = if (item.has("technology") && !item.isNull("technology")) item.getString("technology") else null

                historyRecords.add(
                    HistoryRecord(
                        id = id,
                        captureTime = captureTime,
                        source = source,
                        category = category,
                        note = note,
                        batteryHealth = batteryHealth,
                        healthStatus = healthStatus,
                        level = level,
                        status = status,
                        designCapacity = designCapacity,
                        currentCapacity = currentCapacity,
                        fullChargeCapacity = fullChargeCapacity,
                        cycleCount = cycleCount,
                        temperature = temperature,
                        voltage = voltage,
                        currentNow = currentNow,
                        powerWatts = powerWatts,
                        isDualCell = isDualCell,
                        technology = technology
                    )
                )
            }
        }

        return BackupData(
            version = version,
            appVersion = appVersion,
            backupTime = backupTime,
            backupTimestamp = backupTimestamp,
            settings = settings,
            historyRecords = historyRecords
        )
    }

    /**
     * 将全量历史记录与应用设置导出并写入由 Storage Access Framework 指定的目标 URI 文件中。
     *
     * @param context 应用程序上下文
     * @param uri 用户选定的目标保存文件 URI
     * @param historyRecords 待导出的历史快照列表
     * @return 包含导出记录条数的 [Result] 结果包装
     */
    fun exportBackupToUri(context: Context, uri: Uri, historyRecords: List<HistoryRecord>): Result<Int> {
        return try {
            val jsonContent = generateBackupJson(context, historyRecords)
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                OutputStreamWriter(outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(jsonContent)
                    writer.flush()
                }
            } ?: return Result.failure(Exception("无法打开文件写入流"))
            Result.success(historyRecords.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 从用户选定的备份文件 URI 中读取内容并解析出 [BackupData] 元数据供预览。
     *
     * @param context 应用程序上下文
     * @param uri 用户选定的备份文件 URI
     * @return 包含解析结果 [BackupData] 的 [Result] 包装
     */
    fun importBackupPreview(context: Context, uri: Uri): Result<BackupData> {
        return try {
            val stringBuilder = java.lang.StringBuilder()
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                    var line: String? = reader.readLine()
                    while (line != null) {
                        stringBuilder.append(line).append("\n")
                        line = reader.readLine()
                    }
                }
            } ?: return Result.failure(Exception("无法打开文件读取流"))

            val jsonStr = stringBuilder.toString()
            if (jsonStr.isBlank()) {
                return Result.failure(Exception("备份文件内容为空"))
            }

            val backupData = parseBackupJson(jsonStr)
            Result.success(backupData)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 执行数据恢复：将 [BackupData] 中的数据写入数据库并按需同步应用偏好设置。
     *
     * @param context 应用程序上下文
     * @param backupData 待恢复的备份数据对象
     * @param isOverwrite 是否采用覆盖模式（true 为清空当前数据库后写入，false 为合并去重写入）
     * @param restoreSettings 是否同步恢复应用偏好设置
     * @return 包含成功恢复的记录条数的 [Result] 包装
     */
    fun restoreBackup(
        context: Context,
        backupData: BackupData,
        isOverwrite: Boolean,
        restoreSettings: Boolean
    ): Result<Int> {
        return try {
            val dbHelper = HistoryDbHelper.getInstance(context)
            val count = if (isOverwrite) {
                dbHelper.replaceRecords(backupData.historyRecords)
            } else {
                dbHelper.insertRecords(backupData.historyRecords)
            }

            if (restoreSettings) {
                val prefs = context.getSharedPreferences("battery_app_settings", Context.MODE_PRIVATE)
                val editor = prefs.edit()
                backupData.settings.themeMode?.let { editor.putInt("theme_mode", it) }
                backupData.settings.autoRefreshEnabled?.let { editor.putBoolean("auto_refresh_enabled", it) }
                backupData.settings.refreshIntervalMs?.let { editor.putLong("refresh_interval_ms", it) }
                editor.apply()
            }

            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        private const val BACKUP_SCHEMA_VERSION = 1

        @Volatile
        private var instance: BackupManager? = null

        /**
         * 获取 [BackupManager] 单例实例。
         *
         * @return 单例 [BackupManager] 对象
         */
        fun getInstance(): BackupManager {
            return instance ?: synchronized(this) {
                instance ?: BackupManager().also { instance = it }
            }
        }
    }
}
