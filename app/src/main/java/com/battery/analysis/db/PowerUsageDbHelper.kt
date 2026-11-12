package com.battery.analysis.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.CursorWindow
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.battery.analysis.manager.PowerUsageManager
import com.battery.analysis.model.PowerDischargePoint
import com.battery.analysis.model.PowerUsageRecord
import org.json.JSONArray
import org.json.JSONObject

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
                $COL_BACKGROUND_ENERGY_WH REAL DEFAULT 0,
                $COL_IS_COMPLETED INTEGER NOT NULL DEFAULT 1,
                $COL_LAST_CHECKPOINT_TIME INTEGER DEFAULT 0,
                $COL_SCREEN_OFF_AWAKE_ENERGY_WH REAL DEFAULT 0,
                $COL_SCREEN_OFF_DEEP_SLEEP_ENERGY_WH REAL DEFAULT 0,
                $COL_SCREEN_OFF_AWAKE_DURATION_MS INTEGER DEFAULT 0,
                $COL_SCREEN_OFF_DEEP_SLEEP_DURATION_MS INTEGER DEFAULT 0,
                $COL_SCREEN_OFF_AWAKE_DRAIN_MAH REAL DEFAULT 0,
                $COL_SCREEN_OFF_DEEP_SLEEP_DRAIN_MAH REAL DEFAULT 0,
                $COL_IS_SCREEN_OFF_DECOMPOSED INTEGER DEFAULT 1
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
        db.beginTransaction()
        try {
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
            if (oldVersion < 3) {
                try {
                    db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $COL_IS_COMPLETED INTEGER NOT NULL DEFAULT 1")
                    db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $COL_LAST_CHECKPOINT_TIME INTEGER DEFAULT 0")
                } catch (_: Exception) {}
            }
            if (oldVersion < 4) {
                try {
                    db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $COL_SCREEN_OFF_AWAKE_ENERGY_WH REAL DEFAULT 0")
                    db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $COL_SCREEN_OFF_DEEP_SLEEP_ENERGY_WH REAL DEFAULT 0")
                    db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $COL_SCREEN_OFF_AWAKE_DURATION_MS INTEGER DEFAULT 0")
                    db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $COL_SCREEN_OFF_DEEP_SLEEP_DURATION_MS INTEGER DEFAULT 0")
                    db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $COL_SCREEN_OFF_AWAKE_DRAIN_MAH REAL DEFAULT 0")
                    db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $COL_SCREEN_OFF_DEEP_SLEEP_DRAIN_MAH REAL DEFAULT 0")
                    db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $COL_IS_SCREEN_OFF_DECOMPOSED INTEGER DEFAULT 1")
                } catch (_: Exception) {}
            }
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 检查数据库中是否存在指定主键 ID 的记录。
     *
     * @param db SQLite 数据库实例
     * @param id 目标记录 ID
     * @return 若存在返回 true，否则返回 false
     */
    fun checkRecordExistsById(db: SQLiteDatabase, id: Long): Boolean {
        if (id <= 0L) return false
        val cursor = db.rawQuery("SELECT 1 FROM $TABLE_NAME WHERE $COL_ID = ? LIMIT 1", arrayOf(id.toString()))
        return cursor.use { it.moveToFirst() }
    }

    /**
     * 插入一条新的耗电历史记录或增量更新已有会话草稿。
     * 具备精确主键匹配与防重检测机制：
     * 1. 严格守卫：若记录处于充电状态，严禁保存为耗电历史记录，直接返回 -1；
     * 2. 严格守卫：若记录平均放电功耗 <= 0 且持续时长超过 1 分钟，判定为充电状态无放电采样的无效记录，严禁保存，直接返回 -1；
     * 3. 优先匹配自身 Session ID，若已存在直接覆写更新（支持放电会话的增量 Checkpoint 原地更新，不产生历史碎片）；
     * 4. 若自身 ID 未入库，则检测是否存在时间极相近（< 30 秒）且电量、总时长相同的相近记录并覆写；
     * 5. 否则作为全新记录插入。
     *
     * @param record 待持久化的耗电历史实体对象
     * @return 插入或更新成功返回行 ID，若处于充电状态或无效记录返回 -1
     */
    fun insertRecord(record: PowerUsageRecord): Long {
        // 核心守卫：若记录处于充电状态，严禁保存为耗电历史记录
        if (record.isCharging) {
            return -1L
        }

        // 核心守卫：若记录已完结归档且平均放电功耗 <= 0 且时长大于 1 分钟，说明实际处于充电状态或无有效放电采样，严禁保存为耗电历史
        if (record.isCompleted && record.avgPowerWatts <= 0.001f && record.getDurationMs() >= 60_000L) {
            return -1L
        }

        val db = writableDatabase
        val existsById = checkRecordExistsById(db, record.id)
        val existingId = if (existsById) record.id else findDuplicateRecordId(db, record)

        val values = buildContentValues(record, existingId)

        return if (existingId != null && existingId > 0L) {
            val updated = db.update(TABLE_NAME, values, "$COL_ID = ?", arrayOf(existingId.toString()))
            if (updated > 0) existingId else db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        } else {
            db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    /**
     * 将耗电历史记录对象映射构建为用于 SQLite 操作的 [ContentValues]。
     * 具备字段容错能力与超大 JSON 安全防护守卫，坚决防止写入超出 CursorWindow 限制的膨胀数据。
     *
     * @param record 耗电历史实体对象
     * @param targetId 指定的主键 ID（可选，若传入则覆盖实体自身 ID）
     * @return 填充完成的 [ContentValues] 键值对象
     */
    private fun buildContentValues(record: PowerUsageRecord, targetId: Long? = null): ContentValues {
        val safeTrendJson = sanitizeTrendPointsJsonForStorage(record.trendPointsJson)
        val safeAppListJson = sanitizeAppListJsonForStorage(record.appListJson)

        return ContentValues().apply {
            put(COL_ID, if (targetId != null && targetId > 0L) targetId else record.id)
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
            put(COL_TREND_POINTS_JSON, safeTrendJson)
            put(COL_APP_LIST_JSON, safeAppListJson)
            put(COL_BACKGROUND_POWER_WATTS, record.backgroundPowerWatts)
            put(COL_BACKGROUND_DURATION, record.backgroundDurationText)
            put(COL_REM_BACKGROUND, record.remainingBackgroundText)
            put(COL_SCREEN_ON_ENERGY_WH, record.screenOnEnergyWh)
            put(COL_TOTAL_ENERGY_WH, record.totalEnergyWh)
            put(COL_SCREEN_OFF_ENERGY_WH, record.screenOffEnergyWh)
            put(COL_BACKGROUND_ENERGY_WH, record.backgroundEnergyWh)
            put(COL_IS_COMPLETED, if (record.isCompleted) 1 else 0)
            put(COL_LAST_CHECKPOINT_TIME, record.lastCheckpointTime)
            put(COL_SCREEN_OFF_AWAKE_ENERGY_WH, record.screenOffAwakeEnergyWh)
            put(COL_SCREEN_OFF_DEEP_SLEEP_ENERGY_WH, record.screenOffDeepSleepEnergyWh)
            put(COL_SCREEN_OFF_AWAKE_DURATION_MS, record.screenOffAwakeDurationMs)
            put(COL_SCREEN_OFF_DEEP_SLEEP_DURATION_MS, record.screenOffDeepSleepDurationMs)
            put(COL_SCREEN_OFF_AWAKE_DRAIN_MAH, record.screenOffAwakeDrainMah)
            put(COL_SCREEN_OFF_DEEP_SLEEP_DRAIN_MAH, record.screenOffDeepSleepDrainMah)
            put(COL_IS_SCREEN_OFF_DECOMPOSED, if (record.isScreenOffDecomposedAvailable) 1 else 0)
        }
    }

    /**
     * 事务批量插入耗电历史记录列表（用于合并去重恢复）。
     * 自动过滤剔除任何处于充电状态或平均放电功耗为零的异常记录。
     *
     * @param records 待插入的耗电历史记录列表
     * @return 实际成功写入或更新的有效非充电记录条数
     */
    fun insertRecords(records: List<PowerUsageRecord>): Int {
        val validRecords = records.filter { !it.isCharging && !(it.isCompleted && it.avgPowerWatts <= 0.001f && it.getDurationMs() >= 60_000L) }
        if (validRecords.isEmpty()) return 0

        val db = writableDatabase
        var insertedCount = 0
        db.beginTransaction()
        try {
            for (record in validRecords) {
                val existingId = findDuplicateRecordId(db, record)
                val values = buildContentValues(record, existingId)
                val rowId = if (existingId != null && existingId > 0L) {
                    val updated = db.update(TABLE_NAME, values, "$COL_ID = ?", arrayOf(existingId.toString()))
                    if (updated > 0) existingId else db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE)
                } else {
                    db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE)
                }
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
     * 在单个事务中清空现有耗电历史记录并写入新的记录列表（用于覆盖式数据恢复）。
     * 自动过滤剔除任何处于充电状态或平均放电功耗为零的异常记录。
     *
     * @param records 待恢复的耗电历史记录列表
     * @return 实际成功写入的有效非充电记录条数
     */
    fun replaceRecords(records: List<PowerUsageRecord>): Int {
        val validRecords = records.filter { !it.isCharging && !(it.isCompleted && it.avgPowerWatts <= 0.001f && it.getDurationMs() >= 60_000L) }
        val db = writableDatabase
        var insertedCount = 0
        db.beginTransaction()
        try {
            db.delete(TABLE_NAME, null, null)
            for (record in validRecords) {
                val values = buildContentValues(record, null)
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
     * 删除数据库中未完结（RUNNING 状态，即 is_completed = 0）的放电草稿。
     * 用于接入外部电源、处于充电状态或自愈清理历史残留脏状态时，彻底清理未完结草稿，严防生成错误耗电历史。
     *
     * @param exceptId 允许保留的目标会话 ID（可选，若传入该 ID 则跳过删除）
     * @return 实际被删除的记录条数
     */
    fun deleteRunningRecords(exceptId: Long? = null): Int {
        val db = writableDatabase
        val whereClause = if (exceptId != null && exceptId > 0L) {
            "$COL_IS_COMPLETED = 0 AND $COL_ID != ?"
        } else {
            "$COL_IS_COMPLETED = 0"
        }
        val whereArgs = if (exceptId != null && exceptId > 0L) {
            arrayOf(exceptId.toString())
        } else {
            null
        }
        return db.delete(TABLE_NAME, whereClause, whereArgs)
    }

    /**
     * 将数据库中未完结（RUNNING 状态，即 is_completed = 0）的放电草稿批量标记为已结案（is_completed = 1）。
     * 用于开启新会话、接入外部电源或自愈清理历史残留脏状态时，杜绝产生多个“放电中”条目。
     *
     * @param exceptId 允许保留未完结状态的目标会话 ID（可选，若传入该 ID 则跳过对其结案）
     * @return 实际被更新为已完成状态的记录条数
     */
    fun finalizeRunningRecords(exceptId: Long? = null): Int {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_IS_COMPLETED, 1)
        }
        val whereClause = if (exceptId != null && exceptId > 0L) {
            "$COL_IS_COMPLETED = 0 AND $COL_ID != ?"
        } else {
            "$COL_IS_COMPLETED = 0"
        }
        val whereArgs = if (exceptId != null && exceptId > 0L) {
            arrayOf(exceptId.toString())
        } else {
            null
        }
        return db.update(TABLE_NAME, values, whereClause, whereArgs)
    }

    /**
     * 查询所有已持久化的耗电历史记录，按时间从近到远倒序排列。
     * 具备双层容灾防护：若单条历史记录由于早期版本遗留超大 JSON 导致底层 SQLiteBlobTooBigException，
     * 自动无缝降级为分条安全自愈模式，确保界面正常加载耗电历史并后台自动重置修复超大脏数据。
     * 自动执行单会话进行中自愈守护：物理上整机在同一时刻至多只允许存在一个处于“放电中”（RUNNING）的活跃会话。
     * 若历史数据中检测到多条未完结草稿，自动保留最新一条为进行中，其余多余草稿物理删除清理。
     * 自动过滤剔除并清理任何处于充电状态（is_charging = 1）或放电功耗为零且时长超1分钟的异常脏记录，保证耗电历史绝对纯净。
     *
     * @return 纯净有效的耗电历史快照记录列表
     */
    fun getAllRecords(): List<PowerUsageRecord> {
        val list = mutableListOf<PowerUsageRecord>()
        val db = writableDatabase

        // 自动清理历史累积遗留的充电异常脏记录，保证耗电历史绝对纯净：
        // 1. is_charging = 1 的记录
        // 2. 平均放电功耗 <= 0 且时长大于 1 分钟的已完结虚假耗电记录（实际在充电状态下误生成的记录）
        // 严格保护：严禁误杀正在进行中（is_completed = 0）的放电草稿，草稿在放电初始阶段功耗尚未形成微积分采样属于正常物理过程
        try {
            db.delete(
                TABLE_NAME,
                "$COL_IS_CHARGING = 1 OR ($COL_IS_COMPLETED = 1 AND $COL_AVG_POWER_WATTS <= 0.001 AND ($COL_TOTAL_DURATION LIKE '%m%' OR $COL_TOTAL_DURATION LIKE '%h%' OR $COL_TOTAL_DURATION LIKE '%d%'))",
                null
            )
        } catch (_: Exception) {}

        var loadedSuccessfully = false
        try {
            val cursor = db.query(
                TABLE_NAME,
                null,
                "$COL_IS_CHARGING = 0 AND NOT ($COL_IS_COMPLETED = 1 AND $COL_AVG_POWER_WATTS <= 0.001 AND ($COL_TOTAL_DURATION LIKE '%m%' OR $COL_TOTAL_DURATION LIKE '%h%' OR $COL_TOTAL_DURATION LIKE '%d%'))",
                null,
                null,
                null,
                "$COL_ID DESC"
            )
            cursor.use { c ->
                if (c.moveToFirst()) {
                    do {
                        list.add(parseRecordFromCursor(c))
                    } while (c.moveToNext())
                }
            }
            loadedSuccessfully = true
        } catch (e: Exception) {
            Log.e(TAG, "全量查询耗电历史抛出异常，触发逐行安全加载与自愈净化模式", e)
        }

        if (!loadedSuccessfully) {
            list.clear()
            list.addAll(recoverOversizedAllRecords(db))
        }

        var foundFirstRunning = false
        var needDbFix = false
        val fixedList = ArrayList<PowerUsageRecord>(list.size)
        for (record in list) {
            if (!record.isCompleted) {
                if (!foundFirstRunning) {
                    foundFirstRunning = true
                    fixedList.add(record)
                } else {
                    // 多余的未完结草稿直接标记为需物理清理，绝不能覆写为已完成
                    needDbFix = true
                }
            } else {
                fixedList.add(record)
            }
        }

        if (needDbFix) {
            val keepId = fixedList.firstOrNull { !it.isCompleted }?.id
            try {
                deleteRunningRecords(exceptId = keepId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return fixedList
    }

    /**
     * 查询当前最新的一条未完成（RUNNING 状态，即 is_completed = 0）的放电会话记录。
     * 具备 SQLiteBlobTooBigException 自愈防护：当单行体积超出 CursorWindow 时，
     * 自动切换轻量安全列读取基础放电指标，并就地重置超大 JSON 字段完成自愈，杜绝主线程崩溃。
     * 严格排除处于充电状态的异常记录。
     *
     * @return 未完结且处于非充电状态的放电草稿实体 [PowerUsageRecord]，若不存在返回 null
     */
    fun getRunningDischargeRecord(): PowerUsageRecord? {
        val db = readableDatabase
        return try {
            val cursor = db.query(
                TABLE_NAME,
                null,
                "$COL_IS_COMPLETED = 0 AND $COL_IS_CHARGING = 0",
                null,
                null,
                null,
                "$COL_ID DESC",
                "1"
            )
            cursor.use { c ->
                if (c.moveToFirst()) {
                    parseRecordFromCursor(c)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "查询进行中放电草稿抛出异常，触发安全降级自愈", e)
            recoverOversizedRunningRecord(db)
        }
    }

    /**
     * 根据主键 ID 精确查询单条耗电快照记录。
     * 具备 SQLiteBlobTooBigException 自愈防护，严格排除处于充电状态的记录。
     *
     * @param id 目标记录唯一主键 ID
     * @return 匹配到的非充电耗电快照实体 [PowerUsageRecord]，若未查到或属于充电状态返回 null
     */
    fun getRecordById(id: Long): PowerUsageRecord? {
        if (id <= 0L) return null
        val db = readableDatabase
        return try {
            val cursor = db.query(
                TABLE_NAME,
                null,
                "$COL_ID = ? AND $COL_IS_CHARGING = 0",
                arrayOf(id.toString()),
                null,
                null,
                null,
                "1"
            )
            cursor.use { c ->
                if (c.moveToFirst()) {
                    parseRecordFromCursor(c)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "根据 ID 查询耗电记录抛出异常，执行安全自愈降级，ID: $id", e)
            recoverOversizedRecordById(db, id)
        }
    }

    /**
     * 当常规查询因单行数据过大（抛出 SQLiteBlobTooBigException）时，触发安全降级查询并就地自愈修复未结案草稿。
     * 仅查询轻量基础物理指标列，杜绝 CursorWindow 溢出，并自动将数据库中异常膨胀的 JSON 字段重置为安全空集合。
     *
     * @param db SQLite 数据库实例
     * @return 还原的基础放电草稿实体 [PowerUsageRecord]，若无记录则返回 null
     */
    private fun recoverOversizedRunningRecord(db: SQLiteDatabase): PowerUsageRecord? {
        val lightCursor = try {
            db.query(
                TABLE_NAME,
                SAFE_LIGHTWEIGHT_COLUMNS,
                "$COL_IS_COMPLETED = 0 AND $COL_IS_CHARGING = 0",
                null,
                null,
                null,
                "$COL_ID DESC",
                "1"
            )
        } catch (e: Exception) {
            Log.e(TAG, "安全轻量列查询未完成放电草稿失败", e)
            null
        }

        val record = lightCursor?.use { c ->
            if (c.moveToFirst()) {
                parseRecordFromCursor(c)
            } else {
                null
            }
        }

        if (record != null) {
            try {
                val fixValues = ContentValues().apply {
                    put(COL_TREND_POINTS_JSON, "[]")
                    put(COL_APP_LIST_JSON, "[]")
                }
                val writeDb = try { writableDatabase } catch (_: Exception) { db }
                writeDb.update(TABLE_NAME, fixValues, "$COL_ID = ?", arrayOf(record.id.toString()))
                Log.w(TAG, "已成功就地自愈并净化超大放电草稿，ID: ${record.id}")
            } catch (e: Exception) {
                Log.e(TAG, "自愈重置超大草稿失败，ID: ${record.id}", e)
            }
        }

        return record
    }

    /**
     * 当根据 ID 单条查询因单行数据过大抛出异常时，执行轻量列降级查询并就地自愈净化。
     *
     * @param db SQLite 数据库实例
     * @param id 目标记录 ID
     * @return 还原的基础放电记录实体 [PowerUsageRecord]，若未查到则返回 null
     */
    private fun recoverOversizedRecordById(db: SQLiteDatabase, id: Long): PowerUsageRecord? {
        val lightCursor = try {
            db.query(
                TABLE_NAME,
                SAFE_LIGHTWEIGHT_COLUMNS,
                "$COL_ID = ? AND $COL_IS_CHARGING = 0",
                arrayOf(id.toString()),
                null,
                null,
                null,
                "1"
            )
        } catch (e: Exception) {
            Log.e(TAG, "安全轻量列根据 ID 查询记录失败: $id", e)
            null
        }

        val record = lightCursor?.use { c ->
            if (c.moveToFirst()) {
                parseRecordFromCursor(c)
            } else {
                null
            }
        }

        if (record != null) {
            try {
                val fixValues = ContentValues().apply {
                    put(COL_TREND_POINTS_JSON, "[]")
                    put(COL_APP_LIST_JSON, "[]")
                }
                val writeDb = try { writableDatabase } catch (_: Exception) { db }
                writeDb.update(TABLE_NAME, fixValues, "$COL_ID = ?", arrayOf(record.id.toString()))
                Log.w(TAG, "已成功就地自愈并净化超大历史记录，ID: ${record.id}")
            } catch (e: Exception) {
                Log.e(TAG, "自愈重置超大历史记录失败，ID: ${record.id}", e)
            }
        }

        return record
    }

    /**
     * 当全量历史列表查询因包含超大单行记录抛出异常时，触发逐行安全加载与全库自愈。
     * 优先通过仅检索 ID 集合后逐行安全读取基础物理指标，并自动修复其中的超大脏行。
     *
     * @param db SQLite 数据库实例
     * @return 完整有效的耗电历史记录列表
     */
    private fun recoverOversizedAllRecords(db: SQLiteDatabase): List<PowerUsageRecord> {
        val list = mutableListOf<PowerUsageRecord>()
        val ids = mutableListOf<Long>()
        try {
            val idCursor = db.query(
                TABLE_NAME,
                arrayOf(COL_ID),
                "$COL_IS_CHARGING = 0 AND NOT ($COL_IS_COMPLETED = 1 AND $COL_AVG_POWER_WATTS <= 0.001 AND ($COL_TOTAL_DURATION LIKE '%m%' OR $COL_TOTAL_DURATION LIKE '%h%' OR $COL_TOTAL_DURATION LIKE '%d%'))",
                null,
                null,
                null,
                "$COL_ID DESC"
            )
            idCursor.use { c ->
                while (c.moveToNext()) {
                    ids.add(c.getLong(0))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "降级检索记录 ID 列表失败", e)
            return emptyList()
        }

        for (id in ids) {
            val rec = getRecordById(id)
            if (rec != null) {
                list.add(rec)
            }
        }
        return list
    }

    /**
     * 将 Cursor 当前指向的数据行解析构建为 [PowerUsageRecord] 数据实体。
     * 具备字段容错能力，向下平滑兼容缺省列，并对超大字符串列具备异常隔离保护。
     *
     * @param c 移动到有效行位置的数据库 [Cursor]
     * @return 还原构建的 [PowerUsageRecord] 实例
     */
    private fun parseRecordFromCursor(c: Cursor): PowerUsageRecord {
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
        val idxPts = c.getColumnIndex(COL_TREND_POINTS_JSON)
        val idxApps = c.getColumnIndex(COL_APP_LIST_JSON)
        val idxBgPwr = c.getColumnIndex(COL_BACKGROUND_POWER_WATTS)
        val idxBgDur = c.getColumnIndex(COL_BACKGROUND_DURATION)
        val idxRemBg = c.getColumnIndex(COL_REM_BACKGROUND)
        val idxOnWh = c.getColumnIndex(COL_SCREEN_ON_ENERGY_WH)
        val idxTotWh = c.getColumnIndex(COL_TOTAL_ENERGY_WH)
        val idxOffWh = c.getColumnIndex(COL_SCREEN_OFF_ENERGY_WH)
        val idxBgWh = c.getColumnIndex(COL_BACKGROUND_ENERGY_WH)
        val idxCompleted = c.getColumnIndex(COL_IS_COMPLETED)
        val idxCheckpoint = c.getColumnIndex(COL_LAST_CHECKPOINT_TIME)
        val idxAwakeWh = c.getColumnIndex(COL_SCREEN_OFF_AWAKE_ENERGY_WH)
        val idxSleepWh = c.getColumnIndex(COL_SCREEN_OFF_DEEP_SLEEP_ENERGY_WH)
        val idxAwakeDur = c.getColumnIndex(COL_SCREEN_OFF_AWAKE_DURATION_MS)
        val idxSleepDur = c.getColumnIndex(COL_SCREEN_OFF_DEEP_SLEEP_DURATION_MS)
        val idxAwakeMah = c.getColumnIndex(COL_SCREEN_OFF_AWAKE_DRAIN_MAH)
        val idxSleepMah = c.getColumnIndex(COL_SCREEN_OFF_DEEP_SLEEP_DRAIN_MAH)
        val idxDecomposed = c.getColumnIndex(COL_IS_SCREEN_OFF_DECOMPOSED)

        val trendPointsJson = if (idxPts >= 0) {
            try {
                c.getString(idxPts) ?: "[]"
            } catch (_: Exception) {
                "[]"
            }
        } else {
            "[]"
        }

        val appListJson = if (idxApps >= 0) {
            try {
                c.getString(idxApps) ?: "[]"
            } catch (_: Exception) {
                "[]"
            }
        } else {
            "[]"
        }

        return PowerUsageRecord(
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
            trendPointsJson = trendPointsJson,
            appListJson = appListJson,
            backgroundPowerWatts = if (idxBgPwr >= 0) c.getFloat(idxBgPwr) else 0f,
            backgroundDurationText = if (idxBgDur >= 0) c.getString(idxBgDur) ?: "" else "",
            remainingBackgroundText = if (idxRemBg >= 0) c.getString(idxRemBg) ?: "" else "",
            screenOnEnergyWh = if (idxOnWh >= 0) c.getFloat(idxOnWh) else 0f,
            totalEnergyWh = if (idxTotWh >= 0) c.getFloat(idxTotWh) else 0f,
            screenOffEnergyWh = if (idxOffWh >= 0) c.getFloat(idxOffWh) else 0f,
            backgroundEnergyWh = if (idxBgWh >= 0) c.getFloat(idxBgWh) else 0f,
            isCompleted = if (idxCompleted >= 0) c.getInt(idxCompleted) == 1 else true,
            lastCheckpointTime = if (idxCheckpoint >= 0) c.getLong(idxCheckpoint) else 0L,
            screenOffAwakeEnergyWh = if (idxAwakeWh >= 0) c.getFloat(idxAwakeWh) else 0f,
            screenOffDeepSleepEnergyWh = if (idxSleepWh >= 0) c.getFloat(idxSleepWh) else 0f,
            screenOffAwakeDurationMs = if (idxAwakeDur >= 0) c.getLong(idxAwakeDur) else 0L,
            screenOffDeepSleepDurationMs = if (idxSleepDur >= 0) c.getLong(idxSleepDur) else 0L,
            screenOffAwakeDrainMah = if (idxAwakeMah >= 0) c.getFloat(idxAwakeMah) else 0f,
            screenOffDeepSleepDrainMah = if (idxSleepMah >= 0) c.getFloat(idxSleepMah) else 0f,
            isScreenOffDecomposedAvailable = if (idxDecomposed >= 0) c.getInt(idxDecomposed) == 1 else true
        )
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
     * 根据主键 ID 集合批量删除指定的耗电历史记录。
     *
     * @param ids 待批量删除的记录主键 ID 集合
     * @return 实际成功删除的记录总条数
     */
    fun deleteRecords(ids: Collection<Long>): Int {
        if (ids.isEmpty()) return 0
        val db = writableDatabase
        var deletedCount = 0
        db.beginTransaction()
        try {
            for (id in ids) {
                deletedCount += db.delete(TABLE_NAME, "$COL_ID = ?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return deletedCount
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
        private const val TAG = "PowerUsageDbHelper"
        private const val DATABASE_NAME = "power_usage_history.db"
        private const val DATABASE_VERSION = 4
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
        private const val COL_IS_COMPLETED = "is_completed"
        private const val COL_LAST_CHECKPOINT_TIME = "last_checkpoint_time"
        private const val COL_SCREEN_OFF_AWAKE_ENERGY_WH = "screen_off_awake_energy_wh"
        private const val COL_SCREEN_OFF_DEEP_SLEEP_ENERGY_WH = "screen_off_deep_sleep_energy_wh"
        private const val COL_SCREEN_OFF_AWAKE_DURATION_MS = "screen_off_awake_duration_ms"
        private const val COL_SCREEN_OFF_DEEP_SLEEP_DURATION_MS = "screen_off_deep_sleep_duration_ms"
        private const val COL_SCREEN_OFF_AWAKE_DRAIN_MAH = "screen_off_awake_drain_mah"
        private const val COL_SCREEN_OFF_DEEP_SLEEP_DRAIN_MAH = "screen_off_deep_sleep_drain_mah"
        private const val COL_IS_SCREEN_OFF_DECOMPOSED = "is_screen_off_decomposed"

        /**
         * 剔除了超大 JSON 字段（[COL_TREND_POINTS_JSON] 与 [COL_APP_LIST_JSON]）的安全轻量数据列集。
         * 仅包含数值与简短文本指标，单行数据体积小于 1KB，用于单行数据异常膨胀触发 CursorWindow 溢出时的安全降级自愈查询。
         */
        val SAFE_LIGHTWEIGHT_COLUMNS = arrayOf(
            COL_ID,
            COL_RECORD_TIME,
            COL_LEVEL_PERCENT,
            COL_VOLTAGE_VOLTS,
            COL_TEMPERATURE,
            COL_ENERGY_WH,
            COL_IS_CHARGING,
            COL_AVG_POWER_WATTS,
            COL_SCREEN_ON_POWER_WATTS,
            COL_SCREEN_OFF_POWER_WATTS,
            COL_SCREEN_ON_DURATION,
            COL_SCREEN_OFF_DURATION,
            COL_TOTAL_DURATION,
            COL_REM_SCREEN_ON,
            COL_REM_COMPOSITE,
            COL_REM_SCREEN_OFF,
            COL_IS_SHIZUKU_REAL_DATA,
            COL_APP_COUNT,
            COL_BACKGROUND_POWER_WATTS,
            COL_BACKGROUND_DURATION,
            COL_REM_BACKGROUND,
            COL_SCREEN_ON_ENERGY_WH,
            COL_TOTAL_ENERGY_WH,
            COL_SCREEN_OFF_ENERGY_WH,
            COL_BACKGROUND_ENERGY_WH,
            COL_IS_COMPLETED,
            COL_LAST_CHECKPOINT_TIME,
            COL_SCREEN_OFF_AWAKE_ENERGY_WH,
            COL_SCREEN_OFF_DEEP_SLEEP_ENERGY_WH,
            COL_SCREEN_OFF_AWAKE_DURATION_MS,
            COL_SCREEN_OFF_DEEP_SLEEP_DURATION_MS,
            COL_SCREEN_OFF_AWAKE_DRAIN_MAH,
            COL_SCREEN_OFF_DEEP_SLEEP_DRAIN_MAH,
            COL_IS_SCREEN_OFF_DECOMPOSED
        )

        init {
            tryAdjustCursorWindowSize()
        }

        /**
         * 尝试反射调大 SQLite CursorWindow 的全局静态默认缓冲区分配大小（从系统默认 2MB 提升至 16MB）。
         * 作为底层 Native 缓冲区的预防性防溢出加固措施。
         */
        private fun tryAdjustCursorWindowSize() {
            try {
                val field = CursorWindow::class.java.getDeclaredField("sCursorWindowSize")
                field.isAccessible = true
                val currentSize = field.get(null) as? Int ?: 0
                val targetSize = 16 * 1024 * 1024
                if (currentSize < targetSize) {
                    field.set(null, targetSize)
                }
            } catch (_: Throwable) {
                // 忽略非公开 API 反射限制异常，由上层轻量列安全查询与写入端体积控制提供可靠兜底
            }
        }

        /**
         * 针对持久化存储执行放电折线点 JSON 字符串的安全体积检查与物理高保真抽稀。
         * 若 JSON 长度在 500KB 以内直接放行；若超出安全上限，利用统一均匀采样算法保留关键真实波峰波谷点集，
         * 确保入库体积远低于 CursorWindow 的 2MB 硬限制，杜绝产生 CursorWindow 溢出毒药行。
         *
         * @param trendPointsJson 原始 JSON 字符串
         * @return 经过安全体积校验后的纯净 JSON 字符串
         */
        @JvmStatic
        fun sanitizeTrendPointsJsonForStorage(trendPointsJson: String?): String {
            if (trendPointsJson.isNullOrBlank() || trendPointsJson == "[]") return "[]"
            if (trendPointsJson.length <= 500_000) return trendPointsJson

            return try {
                val jsonArray = JSONArray(trendPointsJson)
                val len = jsonArray.length()
                if (len <= 1200) return trendPointsJson

                val points = mutableListOf<PowerDischargePoint>()
                for (i in 0 until len) {
                    val obj = jsonArray.getJSONObject(i)
                    points.add(
                        PowerDischargePoint(
                            timestamp = obj.optLong("ts", 0L),
                            elapsedHours = obj.optDouble("elapsed", 0.0).toFloat(),
                            batteryLevel = obj.optInt("lvl", 0),
                            voltageVolts = obj.optDouble("volt", 0.0).toFloat(),
                            temperature = obj.optDouble("temp", 0.0).toFloat(),
                            powerWatts = obj.optDouble("pwr", 0.0).toFloat(),
                            isScreenOn = obj.optBoolean("screenOn", false)
                        )
                    )
                }
                val sampled = PowerUsageManager.downsampleDischargeSamplesUniformly(points, targetCount = 1200)
                // 工业级严格硬上限保障：若极端工况跳变点极其密集导致保留点数仍超出 1200 点，
                // 实施等距物理点锁定抽取，保证最终持久化点数绝对不超过 1200 点且首末点严格保留真实数据
                val finalPoints = if (sampled.size > 1200) {
                    val step = (sampled.size - 1).toDouble() / 1199.0
                    val reduced = mutableListOf<PowerDischargePoint>()
                    for (idx in 0 until 1200) {
                        val sIdx = (idx * step).toInt().coerceIn(0, sampled.size - 1)
                        reduced.add(sampled[sIdx])
                    }
                    reduced
                } else {
                    sampled
                }
                val outArray = JSONArray()
                for (pt in finalPoints) {
                    val obj = JSONObject().apply {
                        put("ts", pt.timestamp)
                        put("elapsed", pt.elapsedHours.toDouble())
                        put("lvl", pt.batteryLevel)
                        put("volt", pt.voltageVolts.toDouble())
                        put("temp", pt.temperature.toDouble())
                        put("pwr", pt.powerWatts.toDouble())
                        put("screenOn", pt.isScreenOn)
                    }
                    outArray.put(obj)
                }
                outArray.toString()
            } catch (_: Exception) {
                "[]"
            }
        }

        /**
         * 针对持久化存储执行应用列表 JSON 字符串的安全体积检查。
         *
         * @param appListJson 原始应用列表 JSON 字符串
         * @return 经过安全体积校验后的 JSON 字符串
         */
        @JvmStatic
        fun sanitizeAppListJsonForStorage(appListJson: String?): String {
            if (appListJson.isNullOrBlank() || appListJson == "[]") return "[]"
            if (appListJson.length <= 300_000) return appListJson
            return try {
                val jsonArray = JSONArray(appListJson)
                val len = jsonArray.length()
                if (len <= 100) return appListJson
                val outArray = JSONArray()
                for (i in 0 until minOf(len, 100)) {
                    outArray.put(jsonArray.getJSONObject(i))
                }
                outArray.toString()
            } catch (_: Exception) {
                "[]"
            }
        }

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
