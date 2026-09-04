package com.battery.analysis.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.battery.analysis.model.PowerUsageRecord

/**
 * 耗电历史快照 SQLite 本地轻量数据库助手。
 * 负责本地存储用户每次拔掉电源时抓取的完整耗电账本快照，支持倒序查询、单条删除与全量清空。
 *
 * @param context 应用程序上下文
 */
class PowerUsageDbHelper private constructor(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    /**
     * 数据库表初次创建时的表结构定义。
     *
     * @param db 数据库实例
     */
    override fun onCreate(db: SQLiteDatabase) {
        val createSql = """
            CREATE TABLE IF NOT EXISTS $TABLE_NAME (
                $COL_ID INTEGER PRIMARY KEY,
                $COL_RECORD_TIME TEXT NOT NULL,
                $COL_LEVEL_PERCENT INTEGER,
                $COL_VOLTAGE_VOLTS REAL,
                $COL_TEMPERATURE REAL,
                $COL_ENERGY_WH REAL,
                $COL_IS_CHARGING INTEGER,
                $COL_AVG_POWER_WATTS REAL,
                $COL_SCREEN_ON_POWER_WATTS REAL,
                $COL_SCREEN_OFF_POWER_WATTS REAL,
                $COL_SCREEN_ON_DURATION TEXT,
                $COL_SCREEN_OFF_DURATION TEXT,
                $COL_TOTAL_DURATION TEXT,
                $COL_REM_SCREEN_ON TEXT,
                $COL_REM_COMPOSITE TEXT,
                $COL_REM_SCREEN_OFF TEXT,
                $COL_IS_SHIZUKU_REAL_DATA INTEGER,
                $COL_APP_COUNT INTEGER,
                $COL_TREND_POINTS_JSON TEXT,
                $COL_APP_LIST_JSON TEXT
            )
        """.trimIndent()
        db.execSQL(createSql)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_power_history_time ON $TABLE_NAME ($COL_ID DESC)")
    }

    /**
     * 数据库升级回调。
     *
     * @param db 数据库实例
     * @param oldVersion 旧版本号
     * @param newVersion 新版本号
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 当前为初版，暂无需升级迁移
    }

    /**
     * 插入一条新的耗电历史记录。
     *
     * @param record 待持久化的耗电历史实体对象
     * @return 插入成功返回行 ID，失败返回 -1
     */
    fun insertRecord(record: PowerUsageRecord): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_ID, record.id)
            put(COL_RECORD_TIME, record.recordTime)
            put(COL_LEVEL_PERCENT, record.levelPercent)
            put(COL_VOLTAGE_VOLTS, record.voltageVolts)
            put(COL_TEMPERATURE, record.temperature)
            put(COL_ENERGY_WH, record.energyWh)
            put(COL_IS_CHARGING, if (record.isCharging) 1 else 0)
            put(COL_AVG_POWER_WATTS, record.avgPowerWatts)
            put(COL_SCREEN_ON_POWER_WATTS, record.screenOnPowerWatts)
            put(COL_SCREEN_OFF_POWER_WATTS, record.screenOffPowerWatts)
            put(COL_SCREEN_ON_DURATION, record.screenOnDurationText)
            put(COL_SCREEN_OFF_DURATION, record.screenOffDurationText)
            put(COL_TOTAL_DURATION, record.totalDurationText)
            put(COL_REM_SCREEN_ON, record.remainingScreenOnText)
            put(COL_REM_COMPOSITE, record.remainingCompositeText)
            put(COL_REM_SCREEN_OFF, record.remainingScreenOffText)
            put(COL_IS_SHIZUKU_REAL_DATA, if (record.isShizukuRealData) 1 else 0)
            put(COL_APP_COUNT, record.appCount)
            put(COL_TREND_POINTS_JSON, record.trendPointsJson)
            put(COL_APP_LIST_JSON, record.appListJson)
        }
        return db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * 查询所有已持久化的耗电历史记录，按时间从近到远倒序排列。
     *
     * @return 耗电历史快照记录列表
     */
    fun getAllRecords(): List<PowerUsageRecord> {
        val list = mutableListOf<PowerUsageRecord>()
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
            if (c.moveToFirst()) {
                val idxId = c.getColumnIndexOrThrow(COL_ID)
                val idxTime = c.getColumnIndexOrThrow(COL_RECORD_TIME)
                val idxLvl = c.getColumnIndexOrThrow(COL_LEVEL_PERCENT)
                val idxVolt = c.getColumnIndexOrThrow(COL_VOLTAGE_VOLTS)
                val idxTemp = c.getColumnIndexOrThrow(COL_TEMPERATURE)
                val idxWh = c.getColumnIndexOrThrow(COL_ENERGY_WH)
                val idxChg = c.getColumnIndexOrThrow(COL_IS_CHARGING)
                val idxAvgPwr = c.getColumnIndexOrThrow(COL_AVG_POWER_WATTS)
                val idxOnPwr = c.getColumnIndexOrThrow(COL_SCREEN_ON_POWER_WATTS)
                val idxOffPwr = c.getColumnIndexOrThrow(COL_SCREEN_OFF_POWER_WATTS)
                val idxOnDur = c.getColumnIndexOrThrow(COL_SCREEN_ON_DURATION)
                val idxOffDur = c.getColumnIndexOrThrow(COL_SCREEN_OFF_DURATION)
                val idxTotDur = c.getColumnIndexOrThrow(COL_TOTAL_DURATION)
                val idxRemOn = c.getColumnIndexOrThrow(COL_REM_SCREEN_ON)
                val idxRemComp = c.getColumnIndexOrThrow(COL_REM_COMPOSITE)
                val idxRemOff = c.getColumnIndexOrThrow(COL_REM_SCREEN_OFF)
                val idxShizuku = c.getColumnIndexOrThrow(COL_IS_SHIZUKU_REAL_DATA)
                val idxAppCnt = c.getColumnIndexOrThrow(COL_APP_COUNT)
                val idxPts = c.getColumnIndexOrThrow(COL_TREND_POINTS_JSON)
                val idxApps = c.getColumnIndexOrThrow(COL_APP_LIST_JSON)

                do {
                    list.add(
                        PowerUsageRecord(
                            id = c.getLong(idxId),
                            recordTime = c.getString(idxTime),
                            levelPercent = c.getInt(idxLvl),
                            voltageVolts = c.getFloat(idxVolt),
                            temperature = c.getFloat(idxTemp),
                            energyWh = c.getFloat(idxWh),
                            isCharging = c.getInt(idxChg) == 1,
                            avgPowerWatts = c.getFloat(idxAvgPwr),
                            screenOnPowerWatts = c.getFloat(idxOnPwr),
                            screenOffPowerWatts = c.getFloat(idxOffPwr),
                            screenOnDurationText = c.getString(idxOnDur) ?: "",
                            screenOffDurationText = c.getString(idxOffDur) ?: "",
                            totalDurationText = c.getString(idxTotDur) ?: "",
                            remainingScreenOnText = c.getString(idxRemOn) ?: "",
                            remainingCompositeText = c.getString(idxRemComp) ?: "",
                            remainingScreenOffText = c.getString(idxRemOff) ?: "",
                            isShizukuRealData = c.getInt(idxShizuku) == 1,
                            appCount = c.getInt(idxAppCnt),
                            trendPointsJson = c.getString(idxPts) ?: "[]",
                            appListJson = c.getString(idxApps) ?: "[]"
                        )
                    )
                } while (c.moveToNext())
            }
        }
        return list
    }

    /**
     * 根据主键 ID 删除指定的单条耗电历史记录。
     *
     * @param id 目标记录唯一 ID
     * @return 成功删除的记录条数
     */
    fun deleteRecord(id: Long): Int {
        val db = writableDatabase
        return db.delete(TABLE_NAME, "$COL_ID = ?", arrayOf(id.toString()))
    }

    /**
     * 清空全部已存储的耗电历史记录。
     *
     * @return 成功删除的记录总条数
     */
    fun clearAll(): Int {
        val db = writableDatabase
        return db.delete(TABLE_NAME, null, null)
    }

    companion object {
        private const val DATABASE_NAME = "power_usage_history.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_NAME = "power_usage_history"

        private const val COL_ID = "id"
        private const val COL_RECORD_TIME = "record_time"
        private const val COL_LEVEL_PERCENT = "level_percent"
        private const val COL_VOLTAGE_VOLTS = "voltage_volts"
        private const val COL_TEMPERATURE = "temperature"
        private const val COL_ENERGY_WH = "energy_wh"
        private const val COL_IS_CHARGING = "is_charging"
        private const val COL_AVG_POWER_WATTS = "avg_power_watts"
        private const val COL_SCREEN_ON_POWER_WATTS = "screen_on_power_watts"
        private const val COL_SCREEN_OFF_POWER_WATTS = "screen_off_power_watts"
        private const val COL_SCREEN_ON_DURATION = "screen_on_duration"
        private const val COL_SCREEN_OFF_DURATION = "screen_off_duration"
        private const val COL_TOTAL_DURATION = "total_duration"
        private const val COL_REM_SCREEN_ON = "rem_screen_on"
        private const val COL_REM_COMPOSITE = "rem_composite"
        private const val COL_REM_SCREEN_OFF = "rem_screen_off"
        private const val COL_IS_SHIZUKU_REAL_DATA = "is_shizuku_real_data"
        private const val COL_APP_COUNT = "app_count"
        private const val COL_TREND_POINTS_JSON = "trend_points_json"
        private const val COL_APP_LIST_JSON = "app_list_json"

        @Volatile
        private var instance: PowerUsageDbHelper? = null

        /**
         * 获取 [PowerUsageDbHelper] 单例实例。
         *
         * @param context 应用程序上下文
         * @return 单例实例 [PowerUsageDbHelper]
         */
        fun getInstance(context: Context): PowerUsageDbHelper {
            return instance ?: synchronized(this) {
                instance ?: PowerUsageDbHelper(context.applicationContext).also { instance = it }
            }
        }
    }
}
