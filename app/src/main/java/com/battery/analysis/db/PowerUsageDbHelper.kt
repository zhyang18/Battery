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
                $COL_APP_LIST_JSON TEXT,
                $COL_BACKGROUND_POWER_WATTS REAL DEFAULT 0,
                $COL_BACKGROUND_DURATION TEXT DEFAULT '',
                $COL_REM_BACKGROUND TEXT DEFAULT '',
                $COL_SCREEN_ON_ENERGY_WH REAL DEFAULT 0,
                $COL_TOTAL_ENERGY_WH REAL DEFAULT 0,
                $COL_SCREEN_OFF_ENERGY_WH REAL DEFAULT 0,
                $COL_BACKGROUND_ENERGY_WH REAL DEFAULT 0
            )
        """.trimIndent()
        db.execSQL(createSql)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_power_history_time ON $TABLE_NAME ($COL_ID DESC)")
    }

    /**
     * 数据库升级回调，按版本平滑迁移历史表结构。
     *
     * @param db 数据库实例
     * @param oldVersion 旧版本号
     * @param newVersion 新版本号
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $COL_BACKGROUND_POWER_WATTS REAL DEFAULT 0")
                db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $COL_BACKGROUND_DURATION TEXT DEFAULT ''")
                db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $COL_REM_BACKGROUND TEXT DEFAULT ''")
                db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $COL_SCREEN_ON_ENERGY_WH REAL DEFAULT 0")
                db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $COL_TOTAL_ENERGY_WH REAL DEFAULT 0")
                db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $COL_SCREEN_OFF_ENERGY_WH REAL DEFAULT 0")
                db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $COL_BACKGROUND_ENERGY_WH REAL DEFAULT 0")
            } catch (_: Exception) {}
        }
    }

    /**
     * 插入一条新的耗电历史记录。
     * 具备防重检测机制：若数据库中已存在时间极相近（< 30 秒）且电量、总时长相同的记录，
     * 自动更新覆写已有记录，避免并发归档生成重复数据。
     *
     * @param record 待持久化的耗电历史实体对象
     * @return 插入或更新成功返回行 ID，失败返回 -1
     */
    fun insertRecord(record: PowerUsageRecord): Long {
        val db = writableDatabase
        val existingId = findDuplicateRecordId(db, record)

        val values = ContentValues().apply {
            put(COL_ID, if (existingId != null && existingId > 0L) existingId else record.id)
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
            put(COL_BACKGROUND_POWER_WATTS, record.backgroundPowerWatts)
            put(COL_BACKGROUND_DURATION, record.backgroundDurationText)
            put(COL_REM_BACKGROUND, record.remainingBackgroundText)
            put(COL_SCREEN_ON_ENERGY_WH, record.screenOnEnergyWh)
            put(COL_TOTAL_ENERGY_WH, record.totalEnergyWh)
            put(COL_SCREEN_OFF_ENERGY_WH, record.screenOffEnergyWh)
            put(COL_BACKGROUND_ENERGY_WH, record.backgroundEnergyWh)
        }

        return if (existingId != null && existingId > 0L) {
            val updated = db.update(TABLE_NAME, values, "$COL_ID = ?", arrayOf(existingId.toString()))
            if (updated > 0) existingId else db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        } else {
            db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    /**
     * 检索数据库中是否已存在与待插入记录同属单次放电周期的重复记录 ID。
     * 判定准则（满足任一即视为重复）：
     * 1. 结束时间戳相差在 60 秒以内，且终止电量与总时长相同；
     * 2. 或结束时间戳相差在 30 秒以内，且终止电量或总时长相同；
     * 3. 或格式化时间字符串完全一致。
     *
     * @param db SQLite 数据库实例
     * @param record 待比较的耗电记录实体
     * @return 匹配到的重复记录 ID，若无则返回 null
     */
    private fun findDuplicateRecordId(db: SQLiteDatabase, record: PowerUsageRecord): Long? {
        val cursor = db.query(
            TABLE_NAME,
            arrayOf(COL_ID, COL_LEVEL_PERCENT, COL_TOTAL_DURATION, COL_RECORD_TIME),
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
                val existingLevel = it.getInt(1)
                val existingDuration = it.getString(2) ?: ""
                val existingTime = it.getString(3) ?: ""

                val timeDiff = kotlin.math.abs(record.id - existingId)
                val isSameStats = existingLevel == record.levelPercent && existingDuration == record.totalDurationText
                val isDuplicate = (timeDiff < 60000L && isSameStats) ||
                        (timeDiff < 30000L && (existingLevel == record.levelPercent || existingDuration == record.totalDurationText)) ||
                        (existingTime.isNotEmpty() && existingTime == record.recordTime)

                if (isDuplicate) {
                    return existingId
                }
            }
        }
        return null
    }

    /**
     * 自动检索并清洗历史已存在的成对重复耗电记录。
     * 识别时间相差在 60 秒以内且终止电量、总耗时文本相近的相邻项，仅保留最新一条，净化历史数据。
     *
     * @return 清理移除的重复数据条数
     */
    fun deduplicateRecords(): Int {
        val db = writableDatabase
        var deletedCount = 0
        val cursor = db.query(
            TABLE_NAME,
            arrayOf(COL_ID, COL_LEVEL_PERCENT, COL_TOTAL_DURATION, COL_RECORD_TIME),
            null,
            null,
            null,
            null,
            "$COL_ID DESC"
        )

        val idToDelete = mutableListOf<Long>()
        val keptList = mutableListOf<Array<String>>()

        cursor.use {
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val level = it.getInt(1)
                val duration = it.getString(2) ?: ""
                val recordTime = it.getString(3) ?: ""

                var isDuplicate = false
                for (kept in keptList) {
                    val keptId = kept[0].toLongOrNull() ?: 0L
                    val keptLevel = kept[1].toIntOrNull() ?: 0
                    val keptDuration = kept[2]
                    val keptRecordTime = kept[3]

                    val timeDiff = kotlin.math.abs(id - keptId)
                    val isSameStats = level == keptLevel && duration == keptDuration
                    if ((timeDiff < 60000L && isSameStats) ||
                        (timeDiff < 30000L && (level == keptLevel || duration == keptDuration)) ||
                        (recordTime.isNotEmpty() && recordTime == keptRecordTime)
                    ) {
                        isDuplicate = true
                        break
                    }
                }

                if (isDuplicate) {
                    idToDelete.add(id)
                } else {
                    keptList.add(arrayOf(id.toString(), level.toString(), duration, recordTime))
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
     * 查询所有已持久化的耗电历史记录，按时间从近到远倒序排列。
     * 查询前自动执行轻量去重自愈检测，保障列表呈现纯净无冗余。
     *
     * @return 耗电历史快照记录列表
     */
    fun getAllRecords(): List<PowerUsageRecord> {
        // 轻量去重自愈
        try {
            deduplicateRecords()
        } catch (_: Exception) {}

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
                val idxBgPwr = c.getColumnIndex(COL_BACKGROUND_POWER_WATTS)
                val idxBgDur = c.getColumnIndex(COL_BACKGROUND_DURATION)
                val idxRemBg = c.getColumnIndex(COL_REM_BACKGROUND)
                val idxOnWh = c.getColumnIndex(COL_SCREEN_ON_ENERGY_WH)
                val idxTotWh = c.getColumnIndex(COL_TOTAL_ENERGY_WH)
                val idxOffWh = c.getColumnIndex(COL_SCREEN_OFF_ENERGY_WH)
                val idxBgWh = c.getColumnIndex(COL_BACKGROUND_ENERGY_WH)

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
                            appListJson = c.getString(idxApps) ?: "[]",
                            backgroundPowerWatts = if (idxBgPwr >= 0) c.getFloat(idxBgPwr) else 0f,
                            backgroundDurationText = if (idxBgDur >= 0) c.getString(idxBgDur) ?: "" else "",
                            remainingBackgroundText = if (idxRemBg >= 0) c.getString(idxRemBg) ?: "" else "",
                            screenOnEnergyWh = if (idxOnWh >= 0) c.getFloat(idxOnWh) else 0f,
                            totalEnergyWh = if (idxTotWh >= 0) c.getFloat(idxTotWh) else 0f,
                            screenOffEnergyWh = if (idxOffWh >= 0) c.getFloat(idxOffWh) else 0f,
                            backgroundEnergyWh = if (idxBgWh >= 0) c.getFloat(idxBgWh) else 0f
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
        private const val DATABASE_VERSION = 2
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
        private const val COL_BACKGROUND_POWER_WATTS = "background_power_watts"
        private const val COL_BACKGROUND_DURATION = "background_duration"
        private const val COL_REM_BACKGROUND = "rem_background"
        private const val COL_SCREEN_ON_ENERGY_WH = "screen_on_energy_wh"
        private const val COL_TOTAL_ENERGY_WH = "total_energy_wh"
        private const val COL_SCREEN_OFF_ENERGY_WH = "screen_off_energy_wh"
        private const val COL_BACKGROUND_ENERGY_WH = "background_energy_wh"

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
