package com.battery.analysis.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.battery.analysis.model.HistoryRecord

/**
 * 电池历史记录轻量 SQLite 数据库管理助手。
 * 负责本地历史数据表管理，支持按系统api、Shizuku、错误报告三大分类进行事务批量写入、分类查询、单条删除与全量清空。
 *
 * @param context 应用程序上下文
 */
class HistoryDbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    /**
     * 数据库初次创建时的表结构初始化。
     *
     * @param db 数据库实例
     */
    override fun onCreate(db: SQLiteDatabase) {
        val createTableSql = """
            CREATE TABLE IF NOT EXISTS $TABLE_NAME (
                $COL_ID INTEGER PRIMARY KEY,
                $COL_CAPTURE_TIME TEXT NOT NULL,
                $COL_SOURCE TEXT NOT NULL,
                $COL_CATEGORY TEXT NOT NULL DEFAULT '系统api',
                $COL_NOTE TEXT,
                $COL_BATTERY_HEALTH REAL,
                $COL_HEALTH_STATUS TEXT,
                $COL_LEVEL INTEGER,
                $COL_STATUS TEXT,
                $COL_DESIGN_CAPACITY REAL,
                $COL_CURRENT_CAPACITY REAL,
                $COL_FULL_CHARGE_CAPACITY REAL,
                $COL_CYCLE_COUNT INTEGER,
                $COL_TEMPERATURE REAL,
                $COL_VOLTAGE REAL,
                $COL_CURRENT_NOW REAL,
                $COL_POWER_WATTS REAL,
                $COL_IS_DUAL_CELL INTEGER,
                $COL_TECHNOLOGY TEXT
            )
        """.trimIndent()
        db.execSQL(createTableSql)
    }

    /**
     * 数据库版本升级处理。
     *
     * @param db 数据库实例
     * @param oldVersion 旧版本号
     * @param newVersion 新版本号
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $COL_CATEGORY TEXT NOT NULL DEFAULT '系统api'")
                db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $COL_NOTE TEXT")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 插入一条新的电池检测历史记录。
     *
     * @param record 要保存的历史记录对象
     * @return 插入成功返回行 ID，失败返回 -1
     */
    fun insertRecord(record: HistoryRecord): Long {
        val db = writableDatabase
        val values = buildContentValues(record)
        return db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * 事务批量插入多条历史记录（用于一次性同时保存三大数据源）。
     *
     * @param records 待保存的历史记录列表
     * @return 成功插入的条数
     */
    fun insertRecords(records: List<HistoryRecord>): Int {
        if (records.isEmpty()) return 0
        val db = writableDatabase
        var insertedCount = 0
        db.beginTransaction()
        try {
            for (record in records) {
                val values = buildContentValues(record)
                val rowId = db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE)
                if (rowId != -1L) {
                    insertedCount++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return insertedCount
    }

    /**
     * 在单个事务中清空现有历史记录并写入新的历史记录列表（用于覆盖式数据恢复）。
     *
     * @param records 待恢复的历史记录列表
     * @return 成功写入的记录条数
     */
    fun replaceRecords(records: List<HistoryRecord>): Int {
        val db = writableDatabase
        var insertedCount = 0
        db.beginTransaction()
        try {
            db.delete(TABLE_NAME, null, null)
            for (record in records) {
                val values = buildContentValues(record)
                val rowId = db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE)
                if (rowId != -1L) {
                    insertedCount++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return insertedCount
    }

    /**
     * 获取全部历史记录，按时间倒序排列（最新记录在前）。
     *
     * @return 历史记录列表
     */
    fun getAllRecords(): List<HistoryRecord> {
        val recordList = mutableListOf<HistoryRecord>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_NAME,
            null,
            null,
            null,
            null,
            null,
            "$COL_ID DESC"
        )

        cursor.use { c ->
            while (c.moveToNext()) {
                recordList.add(parseRecordFromCursor(c))
            }
        }
        return recordList
    }

    /**
     * 根据分类名称查询最新的一条历史快照记录。
     *
     * @param category 目标数据分类（例如 "错误报告"、"Shizuku"）
     * @return 最新的 [HistoryRecord] 实例，若无记录则返回 null
     */
    fun getLatestRecordByCategory(category: String): HistoryRecord? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_NAME,
            null,
            "$COL_CATEGORY = ?",
            arrayOf(category),
            null,
            null,
            "$COL_ID DESC",
            "1"
        )

        cursor.use { c ->
            if (c.moveToFirst()) {
                return parseRecordFromCursor(c)
            }
        }
        return null
    }

    /**
     * 根据主键 ID 删除指定的单条历史记录。
     *
     * @param id 要删除的记录 ID
     * @return 受影响的行数
     */
    fun deleteRecord(id: Long): Int {
        val db = writableDatabase
        return db.delete(TABLE_NAME, "$COL_ID = ?", arrayOf(id.toString()))
    }

    /**
     * 清空全部电池历史记录。
     *
     * @return 删除的记录总行数
     */
    fun clearAll(): Int {
        val db = writableDatabase
        return db.delete(TABLE_NAME, null, null)
    }

    /**
     * 将 [HistoryRecord] 实体映射为数据库 [ContentValues]。
     */
    private fun buildContentValues(record: HistoryRecord): ContentValues {
        return ContentValues().apply {
            put(COL_ID, record.id)
            put(COL_CAPTURE_TIME, record.captureTime)
            put(COL_SOURCE, record.source)
            put(COL_CATEGORY, record.category)
            record.note?.let { put(COL_NOTE, it) }
            record.batteryHealth?.let { put(COL_BATTERY_HEALTH, it) }
            record.healthStatus?.let { put(COL_HEALTH_STATUS, it) }
            record.level?.let { put(COL_LEVEL, it) }
            record.status?.let { put(COL_STATUS, it) }
            record.designCapacity?.let { put(COL_DESIGN_CAPACITY, it) }
            record.currentCapacity?.let { put(COL_CURRENT_CAPACITY, it) }
            record.fullChargeCapacity?.let { put(COL_FULL_CHARGE_CAPACITY, it) }
            record.cycleCount?.let { put(COL_CYCLE_COUNT, it) }
            record.temperature?.let { put(COL_TEMPERATURE, it) }
            record.voltage?.let { put(COL_VOLTAGE, it) }
            record.currentNow?.let { put(COL_CURRENT_NOW, it) }
            record.powerWatts?.let { put(COL_POWER_WATTS, it) }
            record.isDualCell?.let { put(COL_IS_DUAL_CELL, if (it) 1 else 0) }
            record.technology?.let { put(COL_TECHNOLOGY, it) }
        }
    }

    /**
     * 从 Cursor 解析一条 [HistoryRecord] 实体。
     */
    private fun parseRecordFromCursor(c: android.database.Cursor): HistoryRecord {
        val id = c.getLong(c.getColumnIndexOrThrow(COL_ID))
        val captureTime = c.getString(c.getColumnIndexOrThrow(COL_CAPTURE_TIME))
        val source = c.getString(c.getColumnIndexOrThrow(COL_SOURCE))
        val category = if (c.getColumnIndex(COL_CATEGORY) != -1 && !c.isNull(c.getColumnIndexOrThrow(COL_CATEGORY))) {
            c.getString(c.getColumnIndexOrThrow(COL_CATEGORY))
        } else source
        val note = if (c.getColumnIndex(COL_NOTE) != -1 && !c.isNull(c.getColumnIndexOrThrow(COL_NOTE))) {
            c.getString(c.getColumnIndexOrThrow(COL_NOTE))
        } else null

        val batteryHealth = if (!c.isNull(c.getColumnIndexOrThrow(COL_BATTERY_HEALTH))) c.getFloat(c.getColumnIndexOrThrow(COL_BATTERY_HEALTH)) else null
        val healthStatus = if (!c.isNull(c.getColumnIndexOrThrow(COL_HEALTH_STATUS))) c.getString(c.getColumnIndexOrThrow(COL_HEALTH_STATUS)) else null
        val level = if (!c.isNull(c.getColumnIndexOrThrow(COL_LEVEL))) c.getInt(c.getColumnIndexOrThrow(COL_LEVEL)) else null
        val status = if (!c.isNull(c.getColumnIndexOrThrow(COL_STATUS))) c.getString(c.getColumnIndexOrThrow(COL_STATUS)) else null
        val designCapacity = if (!c.isNull(c.getColumnIndexOrThrow(COL_DESIGN_CAPACITY))) c.getFloat(c.getColumnIndexOrThrow(COL_DESIGN_CAPACITY)) else null
        val currentCapacity = if (!c.isNull(c.getColumnIndexOrThrow(COL_CURRENT_CAPACITY))) c.getFloat(c.getColumnIndexOrThrow(COL_CURRENT_CAPACITY)) else null
        val fullChargeCapacity = if (!c.isNull(c.getColumnIndexOrThrow(COL_FULL_CHARGE_CAPACITY))) c.getFloat(c.getColumnIndexOrThrow(COL_FULL_CHARGE_CAPACITY)) else null
        val cycleCount = if (!c.isNull(c.getColumnIndexOrThrow(COL_CYCLE_COUNT))) c.getInt(c.getColumnIndexOrThrow(COL_CYCLE_COUNT)) else null
        val temperature = if (!c.isNull(c.getColumnIndexOrThrow(COL_TEMPERATURE))) c.getFloat(c.getColumnIndexOrThrow(COL_TEMPERATURE)) else null
        val voltage = if (!c.isNull(c.getColumnIndexOrThrow(COL_VOLTAGE))) c.getFloat(c.getColumnIndexOrThrow(COL_VOLTAGE)) else null
        val currentNow = if (!c.isNull(c.getColumnIndexOrThrow(COL_CURRENT_NOW))) c.getFloat(c.getColumnIndexOrThrow(COL_CURRENT_NOW)) else null
        val powerWatts = if (!c.isNull(c.getColumnIndexOrThrow(COL_POWER_WATTS))) c.getFloat(c.getColumnIndexOrThrow(COL_POWER_WATTS)) else null
        val isDualCell = if (!c.isNull(c.getColumnIndexOrThrow(COL_IS_DUAL_CELL))) c.getInt(c.getColumnIndexOrThrow(COL_IS_DUAL_CELL)) == 1 else null
        val technology = if (!c.isNull(c.getColumnIndexOrThrow(COL_TECHNOLOGY))) c.getString(c.getColumnIndexOrThrow(COL_TECHNOLOGY)) else null

        return HistoryRecord(
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
    }

    companion object {
        private const val DATABASE_NAME = "battery_history.db"
        private const val DATABASE_VERSION = 2
        private const val TABLE_NAME = "tb_battery_history"

        private const val COL_ID = "id"
        private const val COL_CAPTURE_TIME = "capture_time"
        private const val COL_SOURCE = "source"
        private const val COL_CATEGORY = "category"
        private const val COL_NOTE = "note"
        private const val COL_BATTERY_HEALTH = "battery_health"
        private const val COL_HEALTH_STATUS = "health_status"
        private const val COL_LEVEL = "level"
        private const val COL_STATUS = "status"
        private const val COL_DESIGN_CAPACITY = "design_capacity"
        private const val COL_CURRENT_CAPACITY = "current_capacity"
        private const val COL_FULL_CHARGE_CAPACITY = "full_charge_capacity"
        private const val COL_CYCLE_COUNT = "cycle_count"
        private const val COL_TEMPERATURE = "temperature"
        private const val COL_VOLTAGE = "voltage"
        private const val COL_CURRENT_NOW = "current_now"
        private const val COL_POWER_WATTS = "power_watts"
        private const val COL_IS_DUAL_CELL = "is_dual_cell"
        private const val COL_TECHNOLOGY = "technology"

        @Volatile
        private var instance: HistoryDbHelper? = null

        /**
         * 获取 [HistoryDbHelper] 单例实例。
         *
         * @param context 应用程序上下文
         * @return 单例 [HistoryDbHelper] 实例
         */
        fun getInstance(context: Context): HistoryDbHelper {
            return instance ?: synchronized(this) {
                instance ?: HistoryDbHelper(context.applicationContext).also { instance = it }
            }
        }
    }
}
