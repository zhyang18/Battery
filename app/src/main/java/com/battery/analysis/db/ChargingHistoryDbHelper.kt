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
        private const val DATABASE_VERSION = 1
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
                $COL_SCREEN_OFF_ENERGY_WH REAL
            )
        """.trimIndent()
        db.execSQL(createSql)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_charging_history_time ON $TABLE_NAME ($COL_ID DESC)")
    }

    /**
     * 数据库升级回调。
     *
     * @param db 数据库实例
     * @param oldVersion 旧版本号
     * @param newVersion 新版本号
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 当前为初版，暂无需迁移逻辑
    }

    /**
     * 插入一条新的充电历史会话记录。
     *
     * @param record 待持久化的充电历史实体对象
     * @return 插入成功返回行 ID，失败返回 -1
     */
    fun insertRecord(record: ChargingHistoryRecord): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_ID, record.id)
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
        }
        return db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * 查询所有已持久化的充电历史记录，按时间倒序（最新在前）排列。
     *
     * @return 充电历史记录列表
     */
    fun getAllRecords(): List<ChargingHistoryRecord> {
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
                    screenOffEnergyWh = it.getFloat(it.getColumnIndexOrThrow(COL_SCREEN_OFF_ENERGY_WH))
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
