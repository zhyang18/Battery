package com.battery.analysis.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.battery.analysis.model.ChargingHistoryRecord

/**
 * 充电历史记录 SQLite 本地轻量数据库助手。
 * 负责本地存储用户每次拔出充电器时自动归档的完整充电记录，支持倒序查询、单条删除与全量清空。
 *
 * @param context 应用程序上下文
 */
class ChargingHistoryDbHelper private constructor(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "battery_charging_history.db"
        private const val DATABASE_VERSION = 2
        private const val TABLE_NAME = "charging_history"

        private const val COL_ID = "id"
        private const val COL_RECORD_TIME = "record_time"
        private const val COL_START_TIMESTAMP = "start_timestamp"
        private const val COL_END_TIMESTAMP = "end_timestamp"
        private const val COL_DURATION_MS = "duration_ms"
        private const val COL_START_LEVEL = "start_level"
        private const val COL_END_LEVEL = "end_level"
        private const val COL_LEVEL_GAIN = "level_gain"
        private const val COL_CHARGED_ENERGY_WH = "charged_energy_wh"
        private const val COL_AVG_POWER_WATTS = "avg_power_watts"
        private const val COL_MAX_POWER_WATTS = "max_power_watts"
        private const val COL_MAX_TEMPERATURE = "max_temperature"
        private const val COL_CHARGE_TYPE = "charge_type"
        private const val COL_SCREEN_OFF_DURATION_MS = "screen_off_duration_ms"
        private const val COL_SCREEN_OFF_LEVEL_GAIN = "screen_off_level_gain"
        private const val COL_SCREEN_OFF_ENERGY_WH = "screen_off_energy_wh"
        private const val COL_SAMPLE_POINTS_JSON = "sample_points_json"

        @Volatile
        private var instance: ChargingHistoryDbHelper? = null

        /**
         * 获取 ChargingHistoryDbHelper 数据库单例。
         *
         * @param context 应用程序上下文
         * @return [ChargingHistoryDbHelper] 单例实例
         */
        fun getInstance(context: Context): ChargingHistoryDbHelper {
            return instance ?: synchronized(this) {
                instance ?: ChargingHistoryDbHelper(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * 数据库表初次创建时的结构定义。
     *
     * @param db 数据库实例
     */
    override fun onCreate(db: SQLiteDatabase) {
        val createSql = """
            CREATE TABLE IF NOT EXISTS $TABLE_NAME (
                $COL_ID INTEGER PRIMARY KEY,
                $COL_RECORD_TIME TEXT NOT NULL,
                $COL_START_TIMESTAMP INTEGER,
                $COL_END_TIMESTAMP INTEGER,
                $COL_DURATION_MS INTEGER,
                $COL_START_LEVEL INTEGER,
                $COL_END_LEVEL INTEGER,
                $COL_LEVEL_GAIN INTEGER,
                $COL_CHARGED_ENERGY_WH REAL,
                $COL_AVG_POWER_WATTS REAL,
                $COL_MAX_POWER_WATTS REAL,
                $COL_MAX_TEMPERATURE REAL,
                $COL_CHARGE_TYPE TEXT,
                $COL_SCREEN_OFF_DURATION_MS INTEGER,
                $COL_SCREEN_OFF_LEVEL_GAIN INTEGER,
                $COL_SCREEN_OFF_ENERGY_WH REAL,
                $COL_SAMPLE_POINTS_JSON TEXT
            )
        """.trimIndent()
        db.execSQL(createSql)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_charging_history_time ON $TABLE_NAME ($COL_ID DESC)")
    }

    /**
     * 数据库升级回调。
     * 支持版本 1 到版本 2 的平滑结构升级，无损增加 sample_points_json 字段。
     *
     * @param db 数据库实例
     * @param oldVersion 旧版本号
     * @param newVersion 新版本号
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $COL_SAMPLE_POINTS_JSON TEXT")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 插入一条新的充电历史会话记录。
     * 内部具备防重检测：若数据库中已存在相同或相邻开始时刻（< 30 秒）且起止电量相同的记录，
     * 自动覆写更新已有记录，彻底杜绝并发归档插入重复数据。
     *
     * @param record 待持久化的充电历史实体对象
     * @return 插入或更新成功返回行 ID，失败返回 -1
     */
    fun insertRecord(record: ChargingHistoryRecord): Long {
        val db = writableDatabase

        // 1. 业务防重检测：查询是否已有起止时间相同或相近（30秒内）且电量增量相同的已归档记录
        val existingId = findDuplicateRecordId(db, record)

        val values = ContentValues().apply {
            put(COL_ID, if (existingId != null && existingId > 0L) existingId else record.id)
            put(COL_RECORD_TIME, record.recordTime)
            put(COL_START_TIMESTAMP, record.startTimestamp)
            put(COL_END_TIMESTAMP, record.endTimestamp)
            put(COL_DURATION_MS, record.durationMs)
            put(COL_START_LEVEL, record.startLevel)
            put(COL_END_LEVEL, record.endLevel)
            put(COL_LEVEL_GAIN, record.levelGain)
            put(COL_CHARGED_ENERGY_WH, record.chargedEnergyWh)
            put(COL_AVG_POWER_WATTS, record.avgPowerWatts)
            put(COL_MAX_POWER_WATTS, record.maxPowerWatts)
            put(COL_MAX_TEMPERATURE, record.maxTemperature)
            put(COL_CHARGE_TYPE, record.chargeType)
            put(COL_SCREEN_OFF_DURATION_MS, record.screenOffDurationMs)
            put(COL_SCREEN_OFF_LEVEL_GAIN, record.screenOffLevelGain)
            put(COL_SCREEN_OFF_ENERGY_WH, record.screenOffEnergyWh)
            put(COL_SAMPLE_POINTS_JSON, record.samplePointsJson)
        }

        return if (existingId != null && existingId > 0L) {
            val updated = db.update(TABLE_NAME, values, "$COL_ID = ?", arrayOf(existingId.toString()))
            if (updated > 0) existingId else db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        } else {
            db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    /**
     * 检索数据库中是否已存在与待插入记录同属单次会话的重复记录 ID。
     *
     * @param db SQLite 数据库实例
     * @param record 待比较的充电记录实体
     * @return 匹配到的重复记录 ID，若无则返回 null
     */
    private fun findDuplicateRecordId(db: SQLiteDatabase, record: ChargingHistoryRecord): Long? {
        val startTs = record.startTimestamp
        val endTs = record.endTimestamp
        if (startTs <= 0L && endTs <= 0L) return null

        val cursor = db.query(
            TABLE_NAME,
            arrayOf(COL_ID, COL_START_TIMESTAMP, COL_END_TIMESTAMP, COL_START_LEVEL, COL_END_LEVEL),
            null,
            null,
            null,
            null,
            "$COL_ID DESC",
            "30"
        )

        cursor.use {
            while (it.moveToNext()) {
                val existingId = it.getLong(0)
                val existingStart = it.getLong(1)
                val existingEnd = it.getLong(2)
                val existingStartLevel = it.getInt(3)
                val existingEndLevel = it.getInt(4)

                val isSameStart = startTs > 0L && existingStart > 0L && kotlin.math.abs(startTs - existingStart) < 30000L
                val isSameEnd = endTs > 0L && existingEnd > 0L && kotlin.math.abs(endTs - existingEnd) < 30000L
                val isSameLevel = existingStartLevel == record.startLevel && existingEndLevel == record.endLevel

                if ((isSameStart || isSameEnd) && isSameLevel) {
                    return existingId
                }
            }
        }
        return null
    }

    /**
     * 自动检索并清洗历史已存在的成对重复充电记录。
     * 识别开始时间或结束时间相差在 30 秒以内且电量相同的相邻项，仅保留最具代表性的最新记录，从根源净化历史账本。
     *
     * @return 清理移除的重复数据条数
     */
    fun deduplicateRecords(): Int {
        val db = writableDatabase
        var deletedCount = 0
        val cursor = db.query(
            TABLE_NAME,
            arrayOf(COL_ID, COL_START_TIMESTAMP, COL_END_TIMESTAMP, COL_START_LEVEL, COL_END_LEVEL, COL_SAMPLE_POINTS_JSON),
            null,
            null,
            null,
            null,
            "$COL_ID DESC"
        )

        val idToDelete = mutableListOf<Long>()
        val keptRecords = mutableListOf<ChargingHistoryRecord>()

        cursor.use {
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val startTs = it.getLong(1)
                val endTs = it.getLong(2)
                val startLevel = it.getInt(3)
                val endLevel = it.getInt(4)
                val pointsJson = it.getString(5) ?: ""

                var isDuplicate = false
                for (kept in keptRecords) {
                    val isSameStart = startTs > 0L && kept.startTimestamp > 0L && kotlin.math.abs(startTs - kept.startTimestamp) < 30000L
                    val isSameEnd = endTs > 0L && kept.endTimestamp > 0L && kotlin.math.abs(endTs - kept.endTimestamp) < 30000L
                    val isSameLevel = startLevel == kept.startLevel && endLevel == kept.endLevel

                    if ((isSameStart || isSameEnd) && isSameLevel) {
                        isDuplicate = true
                        break
                    }
                }

                if (isDuplicate) {
                    idToDelete.add(id)
                } else {
                    keptRecords.add(
                        ChargingHistoryRecord(
                            id = id,
                            recordTime = "",
                            startTimestamp = startTs,
                            endTimestamp = endTs,
                            durationMs = 0L,
                            startLevel = startLevel,
                            endLevel = endLevel,
                            levelGain = 0,
                            chargedEnergyWh = 0f,
                            avgPowerWatts = 0f,
                            maxPowerWatts = 0f,
                            maxTemperature = 0f,
                            chargeType = "",
                            samplePointsJson = pointsJson
                        )
                    )
                }
            }
        }

        for (delId in idToDelete) {
            val rows = db.delete(TABLE_NAME, "$COL_ID = ?", arrayOf(delId.toString()))
            if (rows > 0) deletedCount++
        }

        return deletedCount
    }

    /**
     * 查询所有已持久化的充电历史记录，按时间倒序（最新在前）排列。
     * 查询前自动执行轻量去重自愈检测，保障列表呈现纯净无冗余。
     *
     * @return 充电历史记录列表
     */
    fun getAllRecords(): List<ChargingHistoryRecord> {
        // 轻量去重自愈
        try {
            deduplicateRecords()
        } catch (_: Exception) {}

        val list = mutableListOf<ChargingHistoryRecord>()
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
        cursor.use {
            val sampleIdx = it.getColumnIndex(COL_SAMPLE_POINTS_JSON)
            while (it.moveToNext()) {
                val record = ChargingHistoryRecord(
                    id = it.getLong(it.getColumnIndexOrThrow(COL_ID)),
                    recordTime = it.getString(it.getColumnIndexOrThrow(COL_RECORD_TIME)),
                    startTimestamp = it.getLong(it.getColumnIndexOrThrow(COL_START_TIMESTAMP)),
                    endTimestamp = it.getLong(it.getColumnIndexOrThrow(COL_END_TIMESTAMP)),
                    durationMs = it.getLong(it.getColumnIndexOrThrow(COL_DURATION_MS)),
                    startLevel = it.getInt(it.getColumnIndexOrThrow(COL_START_LEVEL)),
                    endLevel = it.getInt(it.getColumnIndexOrThrow(COL_END_LEVEL)),
                    levelGain = it.getInt(it.getColumnIndexOrThrow(COL_LEVEL_GAIN)),
                    chargedEnergyWh = it.getFloat(it.getColumnIndexOrThrow(COL_CHARGED_ENERGY_WH)),
                    avgPowerWatts = it.getFloat(it.getColumnIndexOrThrow(COL_AVG_POWER_WATTS)),
                    maxPowerWatts = it.getFloat(it.getColumnIndexOrThrow(COL_MAX_POWER_WATTS)),
                    maxTemperature = it.getFloat(it.getColumnIndexOrThrow(COL_MAX_TEMPERATURE)),
                    chargeType = it.getString(it.getColumnIndexOrThrow(COL_CHARGE_TYPE)) ?: "",
                    screenOffDurationMs = it.getLong(it.getColumnIndexOrThrow(COL_SCREEN_OFF_DURATION_MS)),
                    screenOffLevelGain = it.getInt(it.getColumnIndexOrThrow(COL_SCREEN_OFF_LEVEL_GAIN)),
                    screenOffEnergyWh = it.getFloat(it.getColumnIndexOrThrow(COL_SCREEN_OFF_ENERGY_WH)),
                    samplePointsJson = if (sampleIdx >= 0) it.getString(sampleIdx) ?: "" else ""
                )
                list.add(record)
            }
        }
        return list
    }

    /**
     * 根据主键 ID 删除指定的单条充电历史记录。
     *
     * @param id 待删除记录的主键 ID
     * @return 受影响的行数
     */
    fun deleteRecord(id: Long): Int {
        val db = writableDatabase
        return db.delete(TABLE_NAME, "$COL_ID = ?", arrayOf(id.toString()))
    }

    /**
     * 清空数据库中所有的充电历史记录。
     *
     * @return 受影响的删除行数
     */
    fun clearAll(): Int {
        val db = writableDatabase
        return db.delete(TABLE_NAME, null, null)
    }
}
