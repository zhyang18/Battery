package com.battery.analysis.manager

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.battery.analysis.db.CapacityCandidateDbHelper
import com.battery.analysis.db.ChargingHistoryDbHelper
import com.battery.analysis.domain.estimator.BatteryChargeIntegrator
import com.battery.analysis.domain.estimator.IntegrablePoint
import com.battery.analysis.domain.estimator.RobustFccEstimator
import com.battery.analysis.model.CandidateSessionType
import com.battery.analysis.model.CapacityCandidate
import com.battery.analysis.model.CapacityEstimateResult
import com.battery.analysis.model.CapacitySourceTier
import com.battery.analysis.model.ChargingHistoryRecord
import com.battery.analysis.model.MaturityLevel
import com.battery.analysis.model.PowerUsageRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import kotlin.math.abs

/**
 * 电池容量与满充自学习全生命周期业务管理中枢（BatteryCapacityManager）。
 *
 * 核心职能：
 * 1. 协调底层硬件源能力探测与历史候选样本数据库；
 * 2. 监听充电与放电会话归档，提取真实采样点序列进行严格梯形微积分，生成并持久化候选样本；
 * 3. 调用纯领域算法引擎 [RobustFccEstimator] 执行稳健融合，实时刷新容量与健康度状态；
 * 4. 暴露响应式 [StateFlow] 驱动 UI 呈现，全流程杜绝假数据与循环校准。
 *
 * @param context 应用程序上下文
 */
class BatteryCapacityManager private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val candidateDbHelper = CapacityCandidateDbHelper.getInstance(context)
    private val estimateMutex = Mutex()

    private val _capacityState = MutableStateFlow<CapacityEstimateResult?>(null)
    /**
     * 响应式容量估算与自学习全景结果状态流。
     */
    val capacityState: StateFlow<CapacityEstimateResult?> = _capacityState.asStateFlow()

    companion object {
        const val PREFS_NAME = "capacity_prefs"
        const val PREF_KEY_EFFECTIVE_SOC_THRESHOLD = "pref_capacity_effective_soc_threshold"
        const val DEFAULT_EFFECTIVE_SOC_THRESHOLD: Int = 15

        /** 纳入满充容量自学习样本池的最小物理入库门限（10%） */
        private const val MIN_SOC_SPAN_THRESHOLD: Int = 10

        /** 默认锂电池放电标称平台中位电压（单位：V） */
        private const val NOMINAL_VOLTAGE_VOLTS: Double = 3.85

        @Volatile
        private var instance: BatteryCapacityManager? = null

        /**
         * 获取 BatteryCapacityManager 单例。
         *
         * @param context 应用程序上下文
         * @return [BatteryCapacityManager] 业务单例
         */
        fun getInstance(context: Context): BatteryCapacityManager {
            return instance ?: synchronized(this) {
                instance ?: BatteryCapacityManager(context.applicationContext).also { instance = it }
            }
        }
    }

    init {
        // 初始化时回溯历史合格记录并触发异步估算刷新
        syncHistoricalChargingRecordsIfNeeded()
        refreshEstimateAsync()
    }

    /**
     * 获取当前配置的有效自学习数据电量跨度门限百分比（默认 15%）。
     */
    fun getEffectiveSocSpanThreshold(): Int {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getInt(PREF_KEY_EFFECTIVE_SOC_THRESHOLD, DEFAULT_EFFECTIVE_SOC_THRESHOLD)
    }

    /**
     * 设置有效自学习数据电量跨度门限百分比，并立即持久化触发模型重新计算。
     *
     * @param thresholdPercent 有效门限百分比（10 至 100）
     */
    fun setEffectiveSocSpanThreshold(thresholdPercent: Int) {
        val clamped = thresholdPercent.coerceIn(10, 100)
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putInt(PREF_KEY_EFFECTIVE_SOC_THRESHOLD, clamped).apply()
        refreshEstimateAsync()
    }

    /**
     * 异步触发容量模型重新计算与状态更新。
     */
    fun refreshEstimateAsync() {
        scope.launch {
            refreshEstimateInternal()
        }
    }

    /**
     * 执行全量容量融合计算内部逻辑。
     * 由 [estimateMutex] 互斥锁严格保护，杜绝多协程并发引发的状态时序覆写与竞态条件。
     *
     * @return 最新的容量状态结果 [CapacityEstimateResult]
     */
    private suspend fun refreshEstimateInternal(): CapacityEstimateResult = estimateMutex.withLock {
        // 1. 探测硬件能力与出厂设计容量
        val capability = BatterySourceProbe.probeCapability(context)
        val designCapMah = capability.designCapacityMah?.toDouble()

        // 2. 从本地 SQLite 查询所有候选样本
        val allCandidates = candidateDbHelper.getAllCandidates()

        // 依据用户设置的有效数据百分比门限进行加权样本动态筛选
        val threshold = getEffectiveSocSpanThreshold()
        val eligibleCandidates = allCandidates.filter { it.socSpanPercent >= threshold }

        // 3. 执行纯领域稳健融合估计
        val robustResult = RobustFccEstimator.estimateFusedFcc(eligibleCandidates)

        // 4. 读取系统底层当前的即时电量与电压（严格遵循零脏数据与真实电量比例）
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryIntent = context.registerReceiver(null, intentFilter)
        val rawLevel = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val rawScale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val safeSoc: Int? = if (rawLevel >= 0 && rawScale > 0) {
            ((rawLevel.toFloat() / rawScale.toFloat()) * 100f).toInt().coerceIn(0, 100)
        } else if (rawLevel in 0..100) {
            rawLevel
        } else {
            null
        }

        // 5. 按照工业级可信度分级策略确定基准 FCC 与数据来源等级
        // 关键物理防御：防范厂商 sysfs charge_full 节点仅为单电芯或固定未标定的静态值
        val isHardwareFccPlausible = capability.hardwareFccMah?.let { hwFcc ->
            if (hwFcc <= 0f) false
            else if (designCapMah != null && designCapMah > 0.0) {
                // 与设计容量对比，必须处于合理物理区间（65% ~ 150%），排除单电芯上报（如 50%）或越界非法大值
                val ratio = hwFcc / designCapMah
                ratio in 0.65..1.50
            } else {
                hwFcc in 1000f..15000f
            }
        } ?: false

        val isModelConverged = robustResult.maturityLevel == MaturityLevel.CONVERGED ||
                robustResult.maturityLevel == MaturityLevel.STABLE_TRACKING

        val (effectiveFcc, sourceTier, maturity) = when {
            // 分支 1：多次充放电会话自学习达到高置信度收敛，实测微积分结果优先
            isModelConverged && robustResult.estimatedFccMah != null && robustResult.estimatedFccMah > 0.0 -> {
                Triple(
                    robustResult.estimatedFccMah,
                    CapacitySourceTier.MULTI_SESSION_LEARNED,
                    robustResult.maturityLevel
                )
            }
            // 分支 2：硬件具备合理且经过物理区间校验的直读电量计 FCC
            isHardwareFccPlausible && capability.hardwareFccMah != null -> {
                Triple(
                    capability.hardwareFccMah.toDouble(),
                    CapacitySourceTier.HARDWARE_VERIFIED,
                    MaturityLevel.CONVERGED
                )
            }
            // 分支 3：自学习初步观察阶段（1~2 次样本）
            robustResult.estimatedFccMah != null && robustResult.estimatedFccMah > 0.0 -> {
                Triple(
                    robustResult.estimatedFccMah,
                    CapacitySourceTier.MULTI_SESSION_LEARNED,
                    robustResult.maturityLevel
                )
            }
            // 分支 4：出厂设计规格底线参考
            designCapMah != null && designCapMah > 0.0 -> {
                Triple(
                    designCapMah,
                    CapacitySourceTier.DESIGN_SPEC_PROFILE,
                    MaturityLevel.PENDING
                )
            }
            // 均不可用时忠实返回 null
            else -> {
                Triple(null, CapacitySourceTier.DESIGN_SPEC_PROFILE, MaturityLevel.PENDING)
            }
        }

        // 6. 计算剩余电荷量（mAh）与剩余可用能量（Wh）
        // 允许 safeSoc == 0（完全合法且真实的 0% 状态），仅在 effectiveFcc 或 safeSoc 为 null 时返回 null
        val remainingMah = if (effectiveFcc != null && effectiveFcc > 0.0 && safeSoc != null) {
            effectiveFcc * (safeSoc / 100.0)
        } else {
            null
        }

        val remainingWh = if (remainingMah != null) {
            (remainingMah * NOMINAL_VOLTAGE_VOLTS) / 1000.0
        } else {
            null
        }

        // 7. 计算容量健康度（SOH）
        val soh = if (effectiveFcc != null && designCapMah != null && designCapMah > 0.0) {
            (effectiveFcc / designCapMah) * 100.0
        } else {
            null
        }

        val finalResult = CapacityEstimateResult(
            remainingChargeMah = remainingMah,
            remainingEnergyWh = remainingWh,
            fullChargeCapacityMah = effectiveFcc,
            designCapacityMah = designCapMah,
            sohPercent = soh,
            currentSoc = safeSoc,
            sourceTier = sourceTier,
            maturityLevel = maturity,
            validSessionCount = robustResult.validCandidateCount,
            chargeSessionCount = robustResult.chargeCandidateCount,
            dischargeSessionCount = robustResult.dischargeCandidateCount,
            totalSocSpanCovered = robustResult.totalSocSpanPercent,
            dispersionCoefficientPercent = robustResult.dispersionPercent,
            conflictRateDPercent = robustResult.chargeDischargeConflictPercent,
            confidenceIntervalMah = robustResult.confidenceIntervalMah,
            updateTimestampMs = System.currentTimeMillis(),
            isTemperatureModelCalibrated = false
        )

        _capacityState.value = finalResult
        return finalResult
    }

    /**
     * 内部适配类：将充电采样点适配为可积分点接口。
     */
    private data class IntegrableChargingPointAdapter(
        override val elapsedRealtimeMs: Long,
        override val netCurrentMa: Float
    ) : IntegrablePoint

    /**
     * 当一次充电会话正式结束归档时调用。
     * 自动从会话持久化采样数据中提取电池端净电流时序，执行梯形安时微积分并生成候选样本。
     *
     * @param record 归档的充电历史记录实体 [ChargingHistoryRecord]
     */
    fun onChargingSessionArchived(record: ChargingHistoryRecord) {
        scope.launch {
            try {
                val startSoc = record.startLevel
                val endSoc = record.getEffectiveEndLevel()

                // 物理方向自洽性校验：充电会话结束电量必须严格高于起始电量（杜绝边充边重度耗电导致的逆向净掉电样本）
                if (endSoc <= startSoc) {
                    return@launch
                }
                val span = endSoc - startSoc

                // 门槛 1：电量跨度必须达到最小要求，避免微量插拔引入量化误差
                if (span < MIN_SOC_SPAN_THRESHOLD) {
                    return@launch
                }

                val points = record.getSamplePoints()
                if (points.size < 2) {
                    return@launch
                }

                // 转换为可积分点序列（采用电池端测得的真实物理代数净电流，净流入为正，瞬时净流出为负）
                val integrablePoints = points.map { pt ->
                    IntegrableChargingPointAdapter(
                        elapsedRealtimeMs = pt.timestamp,
                        netCurrentMa = pt.currentMa
                    )
                }

                // 梯形安时积分
                val netChargeMah = BatteryChargeIntegrator.integrateChargeMah(integrablePoints)
                if (netChargeMah <= 10.0) {
                    return@launch
                }

                val normalizedSpan = span / 100.0
                val fccObserved = netChargeMah / normalizedSpan

                // 质检连续性评分
                val continuityScore = BatteryChargeIntegrator.evaluateContinuityScore(integrablePoints)
                val isContinuityDefective = continuityScore < 0.4
                val isRangeDefective = fccObserved <= 500.0 || fccObserved >= 20000.0
                val isExcluded = isRangeDefective || isContinuityDefective
                val exclusionReason = when {
                    isRangeDefective -> "推算容量超出物理电芯合理界限"
                    isContinuityDefective -> "采样断层严重，数据连续性不足(${String.format(Locale.getDefault(), "%.0f%%", continuityScore * 100)})"
                    else -> ""
                }

                val candidate = CapacityCandidate(
                    id = record.id,
                    sessionType = CandidateSessionType.CHARGE,
                    startSoc = startSoc,
                    endSoc = endSoc,
                    socSpanPercent = span,
                    netBatteryChargeMah = netChargeMah,
                    fccObservedMah = fccObserved,
                    startTimestamp = record.startTimestamp,
                    endTimestamp = record.endTimestamp,
                    durationMs = record.durationMs,
                    avgTemperatureCelsius = record.maxTemperature,
                    maxTemperatureCelsius = record.maxTemperature,
                    sampleCount = points.size,
                    qualityScore = continuityScore,
                    isExcluded = isExcluded,
                    exclusionReason = exclusionReason
                )

                candidateDbHelper.insertCandidate(candidate)
                // 触发重新学习更新
                refreshEstimateInternal()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 当一次放电会话正式完结归档时调用。
     * 从整机放电记录中提取电量掉电跨度与积分能量，计算并持久化放电容量候选样本。
     *
     * @param record 归档的放电耗电记录实体 [PowerUsageRecord]
     */
    fun onDischargeSessionArchived(record: PowerUsageRecord) {
        scope.launch {
            try {
                val start = record.getStartBatteryLevel()
                val end = record.levelPercent
                if (start <= end) {
                    return@launch
                }
                val span = start - end
                if (span < MIN_SOC_SPAN_THRESHOLD) {
                    return@launch
                }

                val netDischargeMah = if (record.totalEnergyWh > 0.005f) {
                    (record.totalEnergyWh.toDouble() / NOMINAL_VOLTAGE_VOLTS) * 1000.0
                } else {
                    return@launch
                }

                val normalizedSpan = span / 100.0
                val fccObserved = netDischargeMah / normalizedSpan
                val isRangeDefective = fccObserved <= 500.0 || fccObserved >= 20000.0
                val isExcluded = isRangeDefective
                val exclusionReason = if (isRangeDefective) "推算容量超出物理电芯合理界限" else ""

                val startTs = record.id
                val endTs = if (record.lastCheckpointTime > startTs) record.lastCheckpointTime else (startTs + 60_000L)
                val duration = (endTs - startTs).coerceAtLeast(60_000L)

                val candidate = CapacityCandidate(
                    id = record.id,
                    sessionType = CandidateSessionType.DISCHARGE,
                    startSoc = start,
                    endSoc = end,
                    socSpanPercent = span,
                    netBatteryChargeMah = netDischargeMah,
                    fccObservedMah = fccObserved,
                    startTimestamp = startTs,
                    endTimestamp = endTs,
                    durationMs = duration,
                    avgTemperatureCelsius = record.temperature,
                    maxTemperatureCelsius = record.temperature,
                    sampleCount = 1,
                    qualityScore = 0.85,
                    isExcluded = isExcluded,
                    exclusionReason = exclusionReason
                )

                candidateDbHelper.insertCandidate(candidate)
                refreshEstimateInternal()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 自动回溯同步历史充电记录中的合格样本至候选库（若候选库为空或需要补全）。
     */
    fun syncHistoricalChargingRecordsIfNeeded() {
        scope.launch {
            try {
                val existingCandidates = candidateDbHelper.getAllCandidates()
                val existingIds = existingCandidates.map { it.id }.toSet()
                val records = ChargingHistoryDbHelper.getInstance(context).getAllRecords()
                var insertedAny = false
                for (record in records) {
                    if (existingIds.contains(record.id)) continue
                    val startSoc = record.startLevel
                    val endSoc = record.getEffectiveEndLevel()
                    if (endSoc <= startSoc) continue
                    val span = endSoc - startSoc
                    if (span < MIN_SOC_SPAN_THRESHOLD) continue
                    val points = record.getSamplePoints()
                    if (points.size < 2) continue
                    val integrablePoints = points.map { pt ->
                        IntegrableChargingPointAdapter(
                            elapsedRealtimeMs = pt.timestamp,
                            netCurrentMa = pt.currentMa
                        )
                    }
                    val netChargeMah = BatteryChargeIntegrator.integrateChargeMah(integrablePoints)
                    if (netChargeMah <= 10.0) continue
                    val normalizedSpan = span / 100.0
                    val fccObserved = netChargeMah / normalizedSpan
                    val continuityScore = BatteryChargeIntegrator.evaluateContinuityScore(integrablePoints)
                    val isContinuityDefective = continuityScore < 0.4
                    val isRangeDefective = fccObserved <= 500.0 || fccObserved >= 20000.0
                    val isExcluded = isRangeDefective || isContinuityDefective
                    val exclusionReason = when {
                        isRangeDefective -> "推算容量超出物理电芯合理界限"
                        isContinuityDefective -> "采样断层严重，数据连续性不足(${String.format(Locale.getDefault(), "%.0f%%", continuityScore * 100)})"
                        else -> ""
                    }
                    val candidate = CapacityCandidate(
                        id = record.id,
                        sessionType = CandidateSessionType.CHARGE,
                        startSoc = startSoc,
                        endSoc = endSoc,
                        socSpanPercent = span,
                        netBatteryChargeMah = netChargeMah,
                        fccObservedMah = fccObserved,
                        startTimestamp = record.startTimestamp,
                        endTimestamp = record.endTimestamp,
                        durationMs = record.durationMs,
                        avgTemperatureCelsius = record.maxTemperature,
                        maxTemperatureCelsius = record.maxTemperature,
                        sampleCount = points.size,
                        qualityScore = continuityScore,
                        isExcluded = isExcluded,
                        exclusionReason = exclusionReason
                    )
                    candidateDbHelper.insertCandidate(candidate)
                    insertedAny = true
                }
                if (insertedAny) {
                    refreshEstimateInternal()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
