package com.battery.analysis.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.battery.analysis.model.CandidateSessionType
import com.battery.analysis.model.CapacityCandidate

/**
 * 满充容量（FCC）候选样本持久化 SQLite 数据库助手类。
 * 负责在本地存储用户充放电会话生成的有效容量样本，支持全量查询、有效样本筛选与单例管理。
 *
 * @param context 应用程序上下文
 */
class CapacityCandidateDbHelper private constructor(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "battery_capacity_candidates.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_NAME = "capacity_candidates"

        private const val COL_ID = "id"
        private const val COL_SESSION_TYPE = "session_type"
        private const val COL_START_SOC = "start_soc"
        private const val COL_END_SOC = "end_soc"
        private const val COL_SOC_SPAN = "soc_span"
        private const val COL_NET_CHARGE_MAH = "net_charge_mah"
        private const val COL_FCC_OBSERVED_MAH = "fcc_observed_mah"
        private const val COL_START_TIME = "start_time"
        private const val COL_END_TIME = "end_time"
        private const val COL_DURATION_MS = "duration_ms"
        private const val COL_AVG_TEMP = "avg_temp"
        private const val COL_MAX_TEMP = "max_temp"
        private const val COL_SAMPLE_COUNT = "sample_count"
        private const val COL_QUALITY_SCORE = "quality_score"
        private const val COL_IS_EXCLUDED = "is_excluded"
        private const val COL_EXCLUSION_REASON = "exclusion_reason"

        @Volatile
        private var instance: CapacityCandidateDbHelper? = null

        /**
         * 获取 CapacityCandidateDbHelper 数据库单例。
         *
         * @param context 应用程序上下文
         * @return [CapacityCandidateDbHelper] 单例对象
         */
        fun getInstance(context: Context): CapacityCandidateDbHelper {
            return instance ?: synchronized(this) {
                instance ?: CapacityCandidateDbHelper(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * 数据库初次创建时的结构定义。
     *
     * @param db 数据库实例
     */
    override fun onCreate(db: SQLiteDatabase) {
        val createSql = """
            CREATE TABLE IF NOT EXISTS $TABLE_NAME (
                $COL_ID INTEGER PRIMARY KEY,
                $COL_SESSION_TYPE TEXT NOT NULL,
                $COL_START_SOC INTEGER NOT NULL,
                $COL_END_SOC INTEGER NOT NULL,
                $COL_SOC_SPAN INTEGER NOT NULL,
                $COL_NET_CHARGE_MAH REAL NOT NULL,
                $COL_FCC_OBSERVED_MAH REAL NOT NULL,
                $COL_START_TIME INTEGER NOT NULL,
                $COL_END_TIME INTEGER NOT NULL,
                $COL_DURATION_MS INTEGER NOT NULL,
                $COL_AVG_TEMP REAL NOT NULL,
                $COL_MAX_TEMP REAL NOT NULL,
                $COL_SAMPLE_COUNT INTEGER NOT NULL,
                $COL_QUALITY_SCORE REAL NOT NULL,
                $COL_IS_EXCLUDED INTEGER NOT NULL DEFAULT 0,
                $COL_EXCLUSION_REASON TEXT NOT NULL DEFAULT ''
            )
        """.trimIndent()
        db.execSQL(createSql)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_candidate_time ON $TABLE_NAME ($COL_END_TIME DESC)")
    }

    /**
     * 数据库版本升级回调。
     *
     * @param db 数据库实例
     * @param oldVersion 旧版本号
     * @param newVersion 新版本号
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 当前为 V1 版本，预留升级平滑迁移
    }

    /**
     * 插入一条新的容量候选样本。
     * 具备严格的 SQLite 异常捕获，磁盘满或发生 IO 故障时安全返回 -1L，杜绝应用闪退。
     *
     * @param candidate 待写入的候选样本实体 [CapacityCandidate]
     * @return 插入成功的行 ID，失败返回 -1L
     */
    fun insertCandidate(candidate: CapacityCandidate): Long {
        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put(COL_ID, candidate.id)
                put(COL_SESSION_TYPE, candidate.sessionType.name)
                put(COL_START_SOC, candidate.startSoc)
                put(COL_END_SOC, candidate.endSoc)
                put(COL_SOC_SPAN, candidate.socSpanPercent)
                put(COL_NET_CHARGE_MAH, candidate.netBatteryChargeMah)
                put(COL_FCC_OBSERVED_MAH, candidate.fccObservedMah)
                put(COL_START_TIME, candidate.startTimestamp)
                put(COL_END_TIME, candidate.endTimestamp)
                put(COL_DURATION_MS, candidate.durationMs)
                put(COL_AVG_TEMP, candidate.avgTemperatureCelsius)
                put(COL_MAX_TEMP, candidate.maxTemperatureCelsius)
                put(COL_SAMPLE_COUNT, candidate.sampleCount)
                put(COL_QUALITY_SCORE, candidate.qualityScore)
                put(COL_IS_EXCLUDED, if (candidate.isExcluded) 1 else 0)
                put(COL_EXCLUSION_REASON, candidate.exclusionReason)
            }
            db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (e: Exception) {
            e.printStackTrace()
            -1L
        }
    }

    /**
     * 查询本地持久化的所有容量候选样本（按时间倒序排列）。
     * 具备异常降级防护，发生读取异常时安全返回空列表。
     *
     * @return 历史候选样本列表
     */
    fun getAllCandidates(): List<CapacityCandidate> {
        val list = mutableListOf<CapacityCandidate>()
        return try {
            val db = readableDatabase
            val cursor = db.query(
                TABLE_NAME,
                null,
                null,
                null,
                null,
                null,
                "$COL_END_TIME DESC"
            )
            cursor.use { c ->
                val idxId = c.getColumnIndexOrThrow(COL_ID)
                val idxType = c.getColumnIndexOrThrow(COL_SESSION_TYPE)
                val idxStartSoc = c.getColumnIndexOrThrow(COL_START_SOC)
                val idxEndSoc = c.getColumnIndexOrThrow(COL_END_SOC)
                val idxSpan = c.getColumnIndexOrThrow(COL_SOC_SPAN)
                val idxNet = c.getColumnIndexOrThrow(COL_NET_CHARGE_MAH)
                val idxFcc = c.getColumnIndexOrThrow(COL_FCC_OBSERVED_MAH)
                val idxStartT = c.getColumnIndexOrThrow(COL_START_TIME)
                val idxEndT = c.getColumnIndexOrThrow(COL_END_TIME)
                val idxDur = c.getColumnIndexOrThrow(COL_DURATION_MS)
                val idxAvgTemp = c.getColumnIndexOrThrow(COL_AVG_TEMP)
                val idxMaxTemp = c.getColumnIndexOrThrow(COL_MAX_TEMP)
                val idxCount = c.getColumnIndexOrThrow(COL_SAMPLE_COUNT)
                val idxScore = c.getColumnIndexOrThrow(COL_QUALITY_SCORE)
                val idxExcluded = c.getColumnIndexOrThrow(COL_IS_EXCLUDED)
                val idxReason = c.getColumnIndexOrThrow(COL_EXCLUSION_REASON)

                while (c.moveToNext()) {
                    val typeStr = c.getString(idxType)
                    val type = try {
                        CandidateSessionType.valueOf(typeStr)
                    } catch (e: Exception) {
                        CandidateSessionType.CHARGE
                    }

                    list.add(
                        CapacityCandidate(
                            id = c.getLong(idxId),
                            sessionType = type,
                            startSoc = c.getInt(idxStartSoc),
                            endSoc = c.getInt(idxEndSoc),
                            socSpanPercent = c.getInt(idxSpan),
                            netBatteryChargeMah = c.getDouble(idxNet),
                            fccObservedMah = c.getDouble(idxFcc),
                            startTimestamp = c.getLong(idxStartT),
                            endTimestamp = c.getLong(idxEndT),
                            durationMs = c.getLong(idxDur),
                            avgTemperatureCelsius = c.getFloat(idxAvgTemp),
                            maxTemperatureCelsius = c.getFloat(idxMaxTemp),
                            sampleCount = c.getInt(idxCount),
                            qualityScore = c.getDouble(idxScore),
                            isExcluded = c.getInt(idxExcluded) == 1,
                            exclusionReason = c.getString(idxReason)
                        )
                    )
                }
            }
            list
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}
