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
class ChargingHistoryDbHelper private constructor(private val context: Context) :
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
        db.beginTransaction()
        try {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $COL_SAMPLE_POINTS_JSON TEXT")
            }
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            db.endTransaction()
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
     * 将充电历史记录对象映射构建为用于 SQLite 操作的 [ContentValues]。
     *
     * @param record 充电历史实体对象
     * @param targetId 指定的主键 ID（可选，若传入则覆盖实体自身 ID）
     * @return 填充完成的 [ContentValues] 键值对象
     */
    private fun buildContentValues(record: ChargingHistoryRecord, targetId: Long? = null): ContentValues {
        return ContentValues().apply {
            put(COL_ID, if (targetId != null && targetId > 0L) targetId else record.id)
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
    }

    /**
     * 事务批量插入充电历史记录列表（用于合并去重恢复）。
     *
     * @param records 待插入的充电历史记录列表
     * @return 实际成功写入或更新的记录条数
     */
    fun insertRecords(records: List<ChargingHistoryRecord>): Int {
        if (records.isEmpty()) return 0
        val db = writableDatabase
        var insertedCount = 0
        db.beginTransaction()
        try {
            for (record in records) {
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
     * 在单个事务中清空现有充电历史记录并写入新的记录列表（用于覆盖式数据恢复）。
     *
     * @param records 待恢复的充电历史记录列表
     * @return 实际成功写入的记录条数
     */
    fun replaceRecords(records: List<ChargingHistoryRecord>): Int {
        val db = writableDatabase
        var insertedCount = 0
        db.beginTransaction()
        try {
            db.delete(TABLE_NAME, null, null)
            for (record in records) {
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
     *
     * @return 清理移除的重复数据条数
     */
    fun deduplicateRecords(): Int {
        return sanitizeAndDeduplicateRecords()
    }

    /**
     * 自动检测、清洗与自愈历史充电记录中的异常数据与重复项。
     * 针对因断电未闭合或后台唤醒导致两次充电跨越十几个小时串联成一条长记录的异常脏数据：
     * 1. 识别具有相同或相近开始时间的冲突记录对，解析异常长记录内部的采样点断层，
     *    忠实还原并提取出后一次真实充电独立入库，彻底移除虚假的串联长记录；
     * 2. 识别单条内部存在巨大断层（> 30分钟且电量跌落）的异常长记录并根据真实物理采样点拆分还原；
     * 3. 清理起止时间重叠且电量相同的完全重复记录。
     *
     * @return 实际修复和清理的异常记录条数
     */
    fun sanitizeAndDeduplicateRecords(): Int {
        val db = writableDatabase
        var affectedCount = 0

        val rawRecords = readAllRawRecordsInternal(db)
        if (rawRecords.isEmpty()) return 0

        val idsToDelete = mutableSetOf<Long>()
        val recordsToInsert = mutableListOf<ChargingHistoryRecord>()

        // 1. 检查具有相同或相近开始时间戳的冲突记录对（例如一条 24 分钟正常记录，一条 12 小时串联长记录）
        for (i in 0 until rawRecords.size) {
            val r1 = rawRecords[i]
            for (j in i + 1 until rawRecords.size) {
                val r2 = rawRecords[j]
                val isSameStart = r1.startTimestamp > 0L && r2.startTimestamp > 0L &&
                        kotlin.math.abs(r1.startTimestamp - r2.startTimestamp) < 60_000L

                if (isSameStart && r1.id != r2.id) {
                    val shortRecord = if (r1.durationMs <= r2.durationMs) r1 else r2
                    val longRecord = if (r1.durationMs > r2.durationMs) r1 else r2

                    // 若长记录比短记录长超过 5 分钟，判定长记录为串联了后续充电的异常记录
                    if (longRecord.durationMs > shortRecord.durationMs + 300_000L) {
                        idsToDelete.add(longRecord.id)

                        // 检查长记录的采样点是否包含后半段断层真实充电
                        val points = longRecord.getSamplePoints()
                        val breakIdx = findSampleBreakIndex(points)
                        if (breakIdx > 0 && breakIdx < points.size) {
                            val secondHalfPoints = points.subList(breakIdx, points.size)
                            if (secondHalfPoints.size >= 2) {
                                val extractedRecord = buildRecordFromPoints(secondHalfPoints, longRecord.chargeType)
                                if (extractedRecord != null) {
                                    recordsToInsert.add(extractedRecord)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. 检查单条独立异常长记录（时长超过 2 小时且采样点内部存在超过 30 分钟断层）
        for (record in rawRecords) {
            if (record.id in idsToDelete) continue
            if (record.durationMs > 7200_000L) {
                val points = record.getSamplePoints()
                val breakIdx = findSampleBreakIndex(points)
                if (breakIdx > 0 && breakIdx < points.size) {
                    val firstHalfPoints = points.subList(0, breakIdx)
                    val secondHalfPoints = points.subList(breakIdx, points.size)

                    idsToDelete.add(record.id)
                    val firstRec = buildRecordFromPoints(firstHalfPoints, record.chargeType)
                    val secondRec = buildRecordFromPoints(secondHalfPoints, record.chargeType)
                    if (firstRec != null) recordsToInsert.add(firstRec)
                    if (secondRec != null) recordsToInsert.add(secondRec)
                }
            }
        }

        // 3. 常规完全重复记录去重（30秒内起止时间相同且起止电量相同）
        val keptRecords = mutableListOf<ChargingHistoryRecord>()
        for (record in rawRecords) {
            if (record.id in idsToDelete) continue
            var isDuplicate = false
            for (kept in keptRecords) {
                val isSameStart = record.startTimestamp > 0L && kept.startTimestamp > 0L &&
                        kotlin.math.abs(record.startTimestamp - kept.startTimestamp) < 30_000L
                val isSameEnd = record.endTimestamp > 0L && kept.endTimestamp > 0L &&
                        kotlin.math.abs(record.endTimestamp - kept.endTimestamp) < 30_000L
                val isSameLevel = record.startLevel == kept.startLevel && record.endLevel == kept.endLevel

                if ((isSameStart || isSameEnd) && isSameLevel) {
                    isDuplicate = true
                    break
                }
            }
            if (isDuplicate) {
                idsToDelete.add(record.id)
            } else {
                keptRecords.add(record)
            }
        }

        // 4. 执行数据库事务持久化
        if (idsToDelete.isNotEmpty() || recordsToInsert.isNotEmpty()) {
            db.beginTransaction()
            try {
                for (delId in idsToDelete) {
                    val rows = db.delete(TABLE_NAME, "$COL_ID = ?", arrayOf(delId.toString()))
                    if (rows > 0) affectedCount++
                }
                for (newRec in recordsToInsert) {
                    val values = buildContentValues(newRec, null)
                    val rowId = db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE)
                    if (rowId != -1L) affectedCount++
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        return affectedCount
    }

    /**
     * 在采样点时序序列中检测是否存在时间跳跃或电量跌落的断层索引位置。
     *
     * @param points 采样点集合
     * @return 断层首个新点索引；若无断层返回 -1
     */
    private fun findSampleBreakIndex(points: List<com.battery.analysis.model.ChargingSamplePoint>): Int {
        if (points.size < 2) return -1
        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val curr = points[i]
            val dtMs = curr.timestamp - prev.timestamp
            // 时间间隔超过 30 分钟，或电量发生非充电的异常跌落（跌落超过 2%），判定为两次物理充电之间的断层
            if (dtMs > 1800_000L || (prev.batteryLevel in 1..100 && curr.batteryLevel in 1..100 && curr.batteryLevel < prev.batteryLevel - 2)) {
                return i
            }
        }
        return -1
    }

    /**
     * 从连续的有效充电采样物理点切片中重建独立真实的充电历史快照记录。
     *
     * @param points 连续采样物理点序列
     * @param defaultChargeType 默认充电接口类型描述
     * @return 构建成功的 [ChargingHistoryRecord] 实体，若数据不足则返回 null
     */
    private fun buildRecordFromPoints(
        points: List<com.battery.analysis.model.ChargingSamplePoint>,
        defaultChargeType: String
    ): ChargingHistoryRecord? {
        if (points.size < 2) return null
        val startPoint = points.first()
        val endPoint = points.last()
        val startTs = startPoint.timestamp
        val endTs = endPoint.timestamp
        val durationMs = (endTs - startTs).coerceAtLeast(0L)
        val startLevel = startPoint.batteryLevel
        val endLevel = points.maxOfOrNull { it.batteryLevel } ?: endPoint.batteryLevel
        val levelGain = (endLevel - startLevel).coerceAtLeast(0)

        if (durationMs < 10_000L && levelGain <= 0) return null

        val energyWh = calculateEnergyFromPoints(points)
        val durationHours = durationMs / 3600000.0f
        val avgPower = if (durationHours > 0.001f && energyWh > 0f) {
            energyWh / durationHours
        } else {
            val positivePowers = points.map { it.powerWatts }.filter { it > 0f }
            if (positivePowers.isNotEmpty()) positivePowers.average().toFloat() else 0f
        }

        val maxPower = points.maxOfOrNull { it.powerWatts } ?: 0f
        val maxTemp = points.maxOfOrNull { it.temperature } ?: 0f
        val recordTime = com.battery.analysis.manager.ChargingStatsManager.dateFormatter.get()!!.format(java.util.Date(endTs))

        return ChargingHistoryRecord(
            id = endTs,
            recordTime = recordTime,
            startTimestamp = startTs,
            endTimestamp = endTs,
            durationMs = durationMs,
            startLevel = startLevel,
            endLevel = endLevel,
            levelGain = levelGain,
            chargedEnergyWh = energyWh,
            avgPowerWatts = avgPower,
            maxPowerWatts = maxPower,
            maxTemperature = maxTemp,
            chargeType = defaultChargeType.ifEmpty { "外部供电" },
            samplePointsJson = ChargingHistoryRecord.pointsToJson(points)
        )
    }

    /**
     * 基于真实物理采样点列表通过梯形时间数值积分计算充入能量 Wh。
     *
     * @param points 采样物理点集合
     * @return 充入能量 Wh
     */
    private fun calculateEnergyFromPoints(points: List<com.battery.analysis.model.ChargingSamplePoint>): Float {
        if (points.size < 2) return 0f
        var energyWh = 0f
        for (i in 1 until points.size) {
            val p0 = points[i - 1]
            val p1 = points[i]
            val dtMs = p1.timestamp - p0.timestamp
            if (dtMs in 100L..1800_000L) {
                val avgP = (p0.powerWatts.coerceAtLeast(0f) + p1.powerWatts.coerceAtLeast(0f)) / 2f
                energyWh += (avgP * (dtMs / 3600000.0)).toFloat()
            }
        }
        if (energyWh > 0.005f) {
            return energyWh
        }
        val designCapMah = com.battery.analysis.provider.NormalApiProvider.getDesignCapacity(context) ?: 0f
        val startLevel = points.first().batteryLevel
        val endLevel = points.last().batteryLevel
        val gain = (endLevel - startLevel).coerceAtLeast(0)
        val validVolts = points.map { it.voltageVolts }.filter { it > 0f }
        val avgVolt = if (validVolts.isNotEmpty()) validVolts.average().toFloat() else 3.85f
        return if (designCapMah > 0f && gain > 0) {
            (gain / 100.0f) * designCapMah * avgVolt / 1000.0f
        } else {
            0f
        }
    }

    /**
     * 内部读取数据库全量原始记录，不触发额外自愈逻辑。
     *
     * @param db 可读数据库实例
     * @return 全量原始充电历史记录列表
     */
    private fun readAllRawRecordsInternal(db: SQLiteDatabase): List<ChargingHistoryRecord> {
        val list = mutableListOf<ChargingHistoryRecord>()
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
                list.add(
                    ChargingHistoryRecord(
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
                )
            }
        }
        return list
    }

    /**
     * 查询所有已持久化的充电历史记录，按时间倒序（最新在前）排列。
     * 查询前自动执行一次轻量自愈与去重，确保返回的账本完全纯净真实。
     *
     * @return 充电历史记录列表
     */
    fun getAllRecords(): List<ChargingHistoryRecord> {
        try {
            sanitizeAndDeduplicateRecords()
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
                val rawRecord = ChargingHistoryRecord(
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
                val effectiveEnd = rawRecord.getEffectiveEndLevel()
                val effectiveGain = rawRecord.getEffectiveLevelGain()
                val record = if (effectiveEnd != rawRecord.endLevel || effectiveGain != rawRecord.levelGain) {
                    val corrected = rawRecord.copy(endLevel = effectiveEnd, levelGain = effectiveGain)
                    try {
                        val updateValues = ContentValues().apply {
                            put(COL_END_LEVEL, effectiveEnd)
                            put(COL_LEVEL_GAIN, effectiveGain)
                        }
                        db.update(TABLE_NAME, updateValues, "$COL_ID = ?", arrayOf(rawRecord.id.toString()))
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    corrected
                } else {
                    rawRecord
                }
                list.add(record)
            }
        }
        return list
    }

    /**
     * 根据主键 ID 精确查询单条充电历史记录。
     *
     * @param id 充电记录的主键 ID
     * @return 对应的充电历史记录实体，不存在时返回 null
     */
    fun getRecordById(id: Long): ChargingHistoryRecord? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_NAME,
            null,
            "$COL_ID = ?",
            arrayOf(id.toString()),
            null,
            null,
            null
        )
        return cursor.use {
            if (it.moveToNext()) {
                val sampleIdx = it.getColumnIndex(COL_SAMPLE_POINTS_JSON)
                ChargingHistoryRecord(
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
            } else null
        }
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
     * 根据主键 ID 集合批量删除指定的充电历史记录。
     *
     * @param ids 待批量删除的记录主键 ID 集合
     * @return 实际成功删除的记录总行数
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
     * 清空数据库中所有的充电历史记录。
     *
     * @return 受影响的删除行数
     */
    fun clearAll(): Int {
        val db = writableDatabase
        return db.delete(TABLE_NAME, null, null)
    }
}
