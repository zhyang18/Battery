package com.battery.analysis.manager

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.battery.analysis.db.CapacityCandidateDbHelper
import com.battery.analysis.db.ChargingHistoryDbHelper
import com.battery.analysis.db.PowerUsageDbHelper
import com.battery.analysis.domain.capacity.estimator.ChargeCapacityEstimator
import com.battery.analysis.domain.capacity.estimator.DischargeCapacityEstimator
import com.battery.analysis.domain.capacity.estimator.SocEnergyCurveLearner as FccSocEnergyCurveLearner
import com.battery.analysis.domain.capacity.fusion.ChargeDischargeFusionEngine
import com.battery.analysis.domain.capacity.model.CapacityChannel
import com.battery.analysis.domain.capacity.model.CapacitySample
import com.battery.analysis.domain.capacity.model.ChargingStatus
import com.battery.analysis.domain.capacity.model.FccCandidateSample
import com.battery.analysis.domain.capacity.model.FccUnifiedEstimate
import com.battery.analysis.domain.estimator.BatteryChargeIntegrator
import com.battery.analysis.domain.estimator.IntegrablePoint
import com.battery.analysis.domain.estimator.RobustFccEstimator
import com.battery.analysis.domain.estimator.model.ModelMaturity
import com.battery.analysis.model.CandidateSessionType
import com.battery.analysis.model.CapacityCandidate
import com.battery.analysis.model.CapacityEstimateResult
import com.battery.analysis.model.CapacitySourceTier
import com.battery.analysis.model.EnergySourceTier
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
 * 3. 采用充放电双向鲁棒融合引擎 [ChargeDischargeFusionEngine] 执行加权融合，实时刷新容量与健康度状态；
 * 4. 暴露响应式 [StateFlow] 驱动 UI 呈现，全流程杜绝假数据与循环校准。
 *
 * @param context 应用程序上下文
 */
class BatteryCapacityManager private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val candidateDbHelper = CapacityCandidateDbHelper.getInstance(context)
    private val estimateMutex = Mutex()

    /** 20 段 SOC 能量曲线纯领域学习器实例 */
    val socEnergyCurveLearner = FccSocEnergyCurveLearner()

    private val _unifiedCapacityState = MutableStateFlow<FccUnifiedEstimate?>(null)
    /**
     * 响应式 FCC 充放电双向容量学习与 SOH 估算全景统一输出流。
     */
    val unifiedCapacityState: StateFlow<FccUnifiedEstimate?> = _unifiedCapacityState.asStateFlow()

    private val _capacityState = MutableStateFlow<CapacityEstimateResult?>(null)
    /**
     * 响应式容量估算与自学习全景结果状态流（兼容历史 UI 观察）。
     */
    val capacityState: StateFlow<CapacityEstimateResult?> = _capacityState.asStateFlow()

    /**
     * 获取当前预测（亮屏、息屏、全局续航及放电速率推算）所基于的满电容量与能量（单位：Wh 与 mAh）。
     * 遵循底层物理优先级逻辑：
     * 1. 优先采用算法自学习的预估满电容量（Full Charge Capacity FCC 与 Full Energy Wh）；
     * 2. 若自学习模型尚未就绪（如数据不足、初始化中或未获取），如实降级返回系统底层设备物理基准容量。
     * 若均无法获取则如实返回 null，忠实反映系统真实状态。
     *
     * @param fallbackCapacityWh 降级备选总能量（单位：Wh，若不可用传入 null）
     * @param fallbackCapacityMah 降级备选总电荷量（单位：mAh，若不可用传入 null）
     * @return 包含预测物理能量与电荷量的配对实体 [Pair]，若均不可用返回 Pair(null, null)
     */
    fun getPredictedFullCapacityWithFallback(
        fallbackCapacityWh: Float? = null,
        fallbackCapacityMah: Float? = null
    ): Pair<Float?, Float?> {
        val estimate = _capacityState.value
        if (estimate != null && estimate.hasValidCapacity()) {
            val mah = estimate.fullChargeCapacityMah?.toFloat()
            val wh = (estimate.fullEnergyWh ?: mah?.let { (it * com.battery.analysis.util.BatteryEnergyCalculator.DEFAULT_NOMINAL_VOLTAGE_VOLTS.toDouble()) / 1000.0 })?.toFloat()
            if (wh != null && wh > 0f) {
                return Pair(wh, mah)
            }
        }
        val safeWh = fallbackCapacityWh?.takeIf { it > 0f }
        val safeMah = fallbackCapacityMah?.takeIf { it > 0f }
            ?: safeWh?.let { (it * 1000f) / com.battery.analysis.util.BatteryEnergyCalculator.DEFAULT_NOMINAL_VOLTAGE_VOLTS }
        val derivedWh = safeWh ?: safeMah?.let { (it * com.battery.analysis.util.BatteryEnergyCalculator.DEFAULT_NOMINAL_VOLTAGE_VOLTS) / 1000f }
        return Pair(derivedWh, safeMah)
    }

    companion object {
        const val PREFS_NAME = "capacity_prefs"
        const val PREF_KEY_EFFECTIVE_SOC_THRESHOLD = "pref_capacity_effective_soc_threshold"
        const val DEFAULT_EFFECTIVE_SOC_THRESHOLD: Int = 15

        /** 容量自学习有效历史数据条数限制持久化键（默认最新 10 条，0 为全部） */
        const val PREF_KEY_EFFECTIVE_SAMPLE_COUNT_LIMIT = "pref_capacity_effective_sample_count"
        const val DEFAULT_EFFECTIVE_SAMPLE_COUNT_LIMIT: Int = 10

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
        // 初始化时回溯历史充放电合格记录并触发异步估算刷新（双向学习，不再清理放电候选样本）
        syncHistoricalRecordsIfNeeded()
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
     * 获取当前配置的容量自学习有效数据条数时效窗口（默认 10 条，0 为全部）。
     */
    fun getEffectiveSampleCountLimit(): Int {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getInt(PREF_KEY_EFFECTIVE_SAMPLE_COUNT_LIMIT, DEFAULT_EFFECTIVE_SAMPLE_COUNT_LIMIT)
    }

    /**
     * 设置容量自学习有效数据条数时效窗口（0 为全部，其余为正整数如 3, 5, 10, 15, 20, 30），
     * 并立即持久化触发模型重新计算。
     *
     * @param limit 目标条数
     */
    fun setEffectiveSampleCountLimit(limit: Int) {
        val safeVal = if (limit <= 0) 0 else limit
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putInt(PREF_KEY_EFFECTIVE_SAMPLE_COUNT_LIMIT, safeVal).apply()
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

        // 依据用户设置的有效数据百分比门限与最新有效数据条数进行双重加权样本动态筛选
        // 遵循新方案：充放电双向样本共同入库并独立通道建模，放电为主，充电为辅
        val threshold = getEffectiveSocSpanThreshold()
        val countLimit = getEffectiveSampleCountLimit()
        val eligibleCandidates = allCandidates.filter {
            !it.isExcluded && it.socSpanPercent >= threshold && it.fccObservedMah > 0.0
        }
        val qualifiedCandidates = if (countLimit > 0) eligibleCandidates.take(countLimit) else eligibleCandidates

        // 3. 执行纯领域双向鲁棒融合估计
        val domainCandidates = qualifiedCandidates.map { c ->
            val channel = if (c.sessionType == CandidateSessionType.DISCHARGE) CapacityChannel.DISCHARGE else CapacityChannel.CHARGE
            val deltaSoc = c.socSpanPercent / 100.0
            val variance = if (channel == CapacityChannel.DISCHARGE) {
                DischargeCapacityEstimator.calculateSampleVariance(c.netBatteryChargeMah, deltaSoc)
            } else {
                ChargeCapacityEstimator.calculateSampleVariance(c.netBatteryChargeMah, deltaSoc)
            }
            FccCandidateSample(
                sessionId = c.id.toString(),
                channel = channel,
                startSoc = c.startSoc / 100.0,
                endSoc = c.endSoc / 100.0,
                deltaSoc = deltaSoc,
                chargeMah = c.netBatteryChargeMah,
                energyWh = c.energyWh,
                fccObservedMah = c.fccObservedMah,
                varianceMah2 = variance,
                avgVoltageVolts = c.avgVoltageVolts.toDouble().takeIf { it > 0.0 },
                avgTemperatureC = c.avgTemperatureCelsius.toDouble(),
                maxTemperatureC = c.maxTemperatureCelsius.toDouble(),
                durationSec = c.durationMs / 1000.0,
                isExcluded = c.isExcluded,
                exclusionReason = c.exclusionReason
            )
        }

        val robustResult = RobustFccEstimator.estimateFusedFcc(qualifiedCandidates)
        val unifiedEstimate = ChargeDischargeFusionEngine.fuse(
            candidates = domainCandidates,
            energyLearner = socEnergyCurveLearner,
            designCapacityMah = designCapMah,
            designVoltageVolts = NOMINAL_VOLTAGE_VOLTS,
            currentSocPercent = null // 将在下方获取 safeSoc 后重算剩余量
        )

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

        val isModelConverged = unifiedEstimate.maturity == com.battery.analysis.domain.capacity.model.LearningMaturity.CONVERGED ||
                unifiedEstimate.maturity == com.battery.analysis.domain.capacity.model.LearningMaturity.STABLE

        val (effectiveFcc, sourceTier, maturity) = when {
            // 分支 1：多次充放电会话自学习达到高置信度收敛，实测微积分结果优先
            isModelConverged && unifiedEstimate.fccQMah != null && unifiedEstimate.fccQMah > 0.0 -> {
                Triple(
                    unifiedEstimate.fccQMah,
                    CapacitySourceTier.MULTI_SESSION_LEARNED,
                    if (unifiedEstimate.maturity == com.battery.analysis.domain.capacity.model.LearningMaturity.STABLE) MaturityLevel.STABLE_TRACKING else MaturityLevel.CONVERGED
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
            unifiedEstimate.fccQMah != null && unifiedEstimate.fccQMah > 0.0 -> {
                Triple(
                    unifiedEstimate.fccQMah,
                    CapacitySourceTier.MULTI_SESSION_LEARNED,
                    if (unifiedEstimate.maturity == com.battery.analysis.domain.capacity.model.LearningMaturity.INITIAL) MaturityLevel.INITIAL_OBSERVATION else MaturityLevel.PENDING
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

        // 6. 重新以当前即时 safeSoc 评估最终剩余能量与剩余电荷
        val finalFullWh = unifiedEstimate.fccEWh ?: effectiveFcc?.let { (it * NOMINAL_VOLTAGE_VOLTS) / 1000.0 }
        val finalRemainingWh = if (safeSoc != null) {
            socEnergyCurveLearner.estimateRemainingEnergyWh(safeSoc, finalFullWh)
                ?: (finalFullWh?.let { (it * safeSoc) / 100.0 })
        } else null

        val finalRemainingMah = if (effectiveFcc != null && safeSoc != null) {
            effectiveFcc * (safeSoc / 100.0)
        } else null

        val energyTier = if (unifiedEstimate.fccEWh != null) EnergySourceTier.MEASURED_LEARNED else EnergySourceTier.NOMINAL_VOLTAGE

        // 7. 计算容量健康度（SOH）
        val soh = if (effectiveFcc != null && designCapMah != null && designCapMah > 0.0) {
            (effectiveFcc / designCapMah) * 100.0
        } else {
            null
        }

        val finalResult = CapacityEstimateResult(
            remainingChargeMah = finalRemainingMah,
            remainingEnergyWh = finalRemainingWh,
            fullChargeCapacityMah = effectiveFcc,
            designCapacityMah = designCapMah,
            sohPercent = soh,
            currentSoc = safeSoc,
            sourceTier = sourceTier,
            maturityLevel = maturity,
            validSessionCount = qualifiedCandidates.size,
            chargeSessionCount = qualifiedCandidates.count { it.sessionType == CandidateSessionType.CHARGE },
            dischargeSessionCount = qualifiedCandidates.count { it.sessionType == CandidateSessionType.DISCHARGE },
            totalSocSpanCovered = qualifiedCandidates.sumOf { it.socSpanPercent },
            dispersionCoefficientPercent = unifiedEstimate.fccQUncertaintyMah?.let { if (effectiveFcc != null && effectiveFcc > 0.0) (it / effectiveFcc) * 100.0 else null },
            conflictRateDPercent = unifiedEstimate.channelConflictPercent,
            confidenceIntervalMah = unifiedEstimate.fccQUncertaintyMah,
            updateTimestampMs = System.currentTimeMillis(),
            isTemperatureModelCalibrated = false,
            fullEnergyWh = finalFullWh,
            energySourceTier = energyTier,
            energyCoveragePercent = (unifiedEstimate.energyCurveCoverage * 100.0).toFloat()
        )

        // 同步更新并暴露最新的双向统一容量估计状态 Flow
        val finalUnifiedEstimate = unifiedEstimate.copy(
            remainingQMah = finalRemainingMah,
            remainingEWh = finalRemainingWh,
            currentSocPercent = safeSoc,
            sohQPercent = soh,
            sohEPercent = if (finalFullWh != null && designCapMah != null && designCapMah > 0.0) {
                (finalFullWh / ((designCapMah * NOMINAL_VOLTAGE_VOLTS) / 1000.0)) * 100.0
            } else null
        )
        _unifiedCapacityState.value = finalUnifiedEstimate

        _capacityState.value = finalResult
        return finalResult
    }

    /**
     * 满充总能量与剩余可用能量同源仲裁结果内部结构体。
     */
    private data class EnergyFusionResult(
        val fullEnergyWh: Double?,
        val remainingEnergyWh: Double?,
        val sourceTier: EnergySourceTier,
        val coveragePercent: Float?
    )

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
    /**
     * 当一次充电会话正式结束归档时调用。
     * 自动从会话持久化采样数据中提取电池端净电流时序，执行梯形安时微积分并生成候选样本。
     *
     * @param record 归档的充电历史记录实体 [ChargingHistoryRecord]
     */
    fun onChargingSessionArchived(record: ChargingHistoryRecord) {
        scope.launch {
            try {
                val startSocLvl = record.startLevel
                val endSocLvl = record.getEffectiveEndLevel()
                if (endSocLvl <= startSocLvl) return@launch
                val span = endSocLvl - startSocLvl
                if (span < MIN_SOC_SPAN_THRESHOLD) return@launch

                val points = record.getSamplePoints()
                if (points.size < 2) return@launch

                val startSoc = startSocLvl / 100.0
                val endSoc = endSocLvl / 100.0

                val samples = points.map { pt ->
                    val currentA = if (pt.currentMa > 0f) {
                        (pt.currentMa / 1000.0).toDouble()
                    } else if (pt.voltageVolts > 0f && pt.powerWatts > 0f) {
                        (pt.powerWatts / pt.voltageVolts).toDouble()
                    } else {
                        0.0
                    }
                    CapacitySample(
                        timestampMs = pt.timestamp,
                        socFraction = pt.batteryLevel / 100.0,
                        currentAmp = currentA,
                        voltageVolt = pt.voltageVolts.toDouble(),
                        temperatureC = pt.temperature.toDouble(),
                        isScreenOn = pt.isScreenOn,
                        chargingStatus = ChargingStatus.CHARGING
                    )
                }

                val isDualCell = CurrentCalibrationManager.isDualCellEnabled(context)
                val candidateSample = ChargeCapacityEstimator.buildCandidate(
                    sessionId = record.id.toString(),
                    samples = samples,
                    startSoc = startSoc,
                    endSoc = endSoc,
                    isDualCell = isDualCell
                )

                val candidate = CapacityCandidate(
                    id = record.id,
                    sessionType = CandidateSessionType.CHARGE,
                    startSoc = startSocLvl,
                    endSoc = endSocLvl,
                    socSpanPercent = span,
                    netBatteryChargeMah = candidateSample.chargeMah,
                    fccObservedMah = candidateSample.fccObservedMah,
                    startTimestamp = record.startTimestamp,
                    endTimestamp = record.endTimestamp,
                    durationMs = record.durationMs,
                    avgTemperatureCelsius = candidateSample.avgTemperatureC?.toFloat() ?: record.maxTemperature,
                    maxTemperatureCelsius = candidateSample.maxTemperatureC?.toFloat() ?: record.maxTemperature,
                    sampleCount = points.size,
                    qualityScore = candidateSample.coverageRatio,
                    isExcluded = candidateSample.isExcluded,
                    exclusionReason = candidateSample.exclusionReason,
                    energyWh = candidateSample.energyWh,
                    avgVoltageVolts = candidateSample.avgVoltageVolts?.toFloat() ?: 0f
                )

                candidateDbHelper.insertCandidate(candidate)
                refreshEstimateInternal()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 当一次放电会话正式完结归档时调用。
     *
     * 遵循技术方案新架构：放电会话作为主估计通道（DischargeCapacityEstimator）构建放电候选样本入库，
     * 并同步喂入 20 段 SOC 能量曲线自学习器（SocEnergyCurveLearner）。
     *
     * @param record 归档的放电耗电记录实体 [PowerUsageRecord]
     */
    fun onDischargeSessionArchived(record: PowerUsageRecord) {
        scope.launch {
            try {
                val startSocLvl = record.getStartBatteryLevel()
                val endSocLvl = record.levelPercent
                val span = abs(startSocLvl - endSocLvl)
                if (span < MIN_SOC_SPAN_THRESHOLD) return@launch

                val trendPoints = record.parseTrendPointsList()
                if (trendPoints.size < 2) return@launch

                val startSoc = startSocLvl / 100.0
                val endSoc = endSocLvl / 100.0

                val samples = trendPoints.map { pt ->
                    CapacitySample(
                        timestampMs = pt.timestamp,
                        socFraction = pt.batteryLevel / 100.0,
                        currentAmp = if (pt.voltageVolts > 0f) (pt.powerWatts / pt.voltageVolts).toDouble() else 0.0,
                        voltageVolt = pt.voltageVolts.toDouble(),
                        temperatureC = pt.temperature.toDouble(),
                        isScreenOn = pt.isScreenOn,
                        chargingStatus = ChargingStatus.DISCHARGING
                    )
                }

                val isDualCell = CurrentCalibrationManager.isDualCellEnabled(context)
                val candidateSample = DischargeCapacityEstimator.buildCandidate(
                    sessionId = record.id.toString(),
                    samples = samples,
                    startSoc = startSoc,
                    endSoc = endSoc,
                    isDualCell = isDualCell
                )

                // 吸收至 20 段 SOC 能量曲线
                socEnergyCurveLearner.absorbDischargeSession(samples)

                val candidate = CapacityCandidate(
                    id = record.id,
                    sessionType = CandidateSessionType.DISCHARGE,
                    startSoc = startSocLvl,
                    endSoc = endSocLvl,
                    socSpanPercent = span,
                    netBatteryChargeMah = candidateSample.chargeMah,
                    fccObservedMah = candidateSample.fccObservedMah,
                    startTimestamp = trendPoints.first().timestamp,
                    endTimestamp = trendPoints.last().timestamp,
                    durationMs = (trendPoints.last().timestamp - trendPoints.first().timestamp).coerceAtLeast(0L),
                    avgTemperatureCelsius = candidateSample.avgTemperatureC?.toFloat() ?: record.temperature,
                    maxTemperatureCelsius = candidateSample.maxTemperatureC?.toFloat() ?: record.temperature,
                    sampleCount = samples.size,
                    qualityScore = candidateSample.coverageRatio,
                    isExcluded = candidateSample.isExcluded,
                    exclusionReason = candidateSample.exclusionReason,
                    energyWh = candidateSample.energyWh,
                    avgVoltageVolts = candidateSample.avgVoltageVolts?.toFloat() ?: record.voltageVolts
                )

                candidateDbHelper.insertCandidate(candidate)
                refreshEstimateInternal()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 自动回溯同步历史充电与放电记录中的合格样本至候选库（若候选库为空或需要补全）。
     */
    fun syncHistoricalRecordsIfNeeded() {
        scope.launch {
            try {
                val existingCandidates = candidateDbHelper.getAllCandidates()
                val existingIds = existingCandidates.map { it.id }.toSet()
                var insertedAny = false

                // 1. 回溯同步充电记录
                val chargingRecords = ChargingHistoryDbHelper.getInstance(context).getAllRecords()
                val isDualCell = CurrentCalibrationManager.isDualCellEnabled(context)

                for (record in chargingRecords) {
                    if (existingIds.contains(record.id)) continue
                    val startSocLvl = record.startLevel
                    val endSocLvl = record.getEffectiveEndLevel()
                    if (endSocLvl <= startSocLvl) continue
                    val span = endSocLvl - startSocLvl
                    if (span < MIN_SOC_SPAN_THRESHOLD) continue

                    val points = record.getSamplePoints()
                    if (points.size < 2) continue

                    val startSoc = startSocLvl / 100.0
                    val endSoc = endSocLvl / 100.0
                    val samples = points.map { pt ->
                        val currentA = if (pt.currentMa > 0f) {
                            (pt.currentMa / 1000.0).toDouble()
                        } else if (pt.voltageVolts > 0f && pt.powerWatts > 0f) {
                            (pt.powerWatts / pt.voltageVolts).toDouble()
                        } else {
                            0.0
                        }
                        CapacitySample(
                            timestampMs = pt.timestamp,
                            socFraction = pt.batteryLevel / 100.0,
                            currentAmp = currentA,
                            voltageVolt = pt.voltageVolts.toDouble(),
                            temperatureC = pt.temperature.toDouble(),
                            isScreenOn = pt.isScreenOn,
                            chargingStatus = ChargingStatus.CHARGING
                        )
                    }

                    val candidateSample = ChargeCapacityEstimator.buildCandidate(
                        sessionId = record.id.toString(),
                        samples = samples,
                        startSoc = startSoc,
                        endSoc = endSoc,
                        isDualCell = isDualCell
                    )

                    val candidate = CapacityCandidate(
                        id = record.id,
                        sessionType = CandidateSessionType.CHARGE,
                        startSoc = startSocLvl,
                        endSoc = endSocLvl,
                        socSpanPercent = span,
                        netBatteryChargeMah = candidateSample.chargeMah,
                        fccObservedMah = candidateSample.fccObservedMah,
                        startTimestamp = record.startTimestamp,
                        endTimestamp = record.endTimestamp,
                        durationMs = record.durationMs,
                        avgTemperatureCelsius = candidateSample.avgTemperatureC?.toFloat() ?: record.maxTemperature,
                        maxTemperatureCelsius = candidateSample.maxTemperatureC?.toFloat() ?: record.maxTemperature,
                        sampleCount = points.size,
                        qualityScore = candidateSample.coverageRatio,
                        isExcluded = candidateSample.isExcluded,
                        exclusionReason = candidateSample.exclusionReason,
                        energyWh = candidateSample.energyWh,
                        avgVoltageVolts = candidateSample.avgVoltageVolts?.toFloat() ?: 0f
                    )
                    candidateDbHelper.insertCandidate(candidate)
                    insertedAny = true
                }

                // 2. 回溯同步放电记录与能量曲线
                val dischargeRecords = PowerUsageDbHelper.getInstance(context).getAllRecords()
                for (record in dischargeRecords) {
                    if (!record.isCompleted) continue
                    val startSocLvl = record.getStartBatteryLevel()
                    val endSocLvl = record.levelPercent
                    val span = abs(startSocLvl - endSocLvl)
                    if (span < MIN_SOC_SPAN_THRESHOLD) continue

                    val trendPoints = record.parseTrendPointsList()
                    if (trendPoints.size < 2) continue

                    val samples = trendPoints.map { pt ->
                        CapacitySample(
                            timestampMs = pt.timestamp,
                            socFraction = pt.batteryLevel / 100.0,
                            currentAmp = if (pt.voltageVolts > 0f) (pt.powerWatts / pt.voltageVolts).toDouble() else 0.0,
                            voltageVolt = pt.voltageVolts.toDouble(),
                            temperatureC = pt.temperature.toDouble(),
                            isScreenOn = pt.isScreenOn,
                            chargingStatus = ChargingStatus.DISCHARGING
                        )
                    }

                    // 持续填充 20 段能量曲线
                    socEnergyCurveLearner.absorbDischargeSession(samples)

                    if (existingIds.contains(record.id)) continue

                    val startSoc = startSocLvl / 100.0
                    val endSoc = endSocLvl / 100.0
                    val candidateSample = DischargeCapacityEstimator.buildCandidate(
                        sessionId = record.id.toString(),
                        samples = samples,
                        startSoc = startSoc,
                        endSoc = endSoc,
                        isDualCell = isDualCell
                    )

                    val candidate = CapacityCandidate(
                        id = record.id,
                        sessionType = CandidateSessionType.DISCHARGE,
                        startSoc = startSocLvl,
                        endSoc = endSocLvl,
                        socSpanPercent = span,
                        netBatteryChargeMah = candidateSample.chargeMah,
                        fccObservedMah = candidateSample.fccObservedMah,
                        startTimestamp = trendPoints.first().timestamp,
                        endTimestamp = trendPoints.last().timestamp,
                        durationMs = (trendPoints.last().timestamp - trendPoints.first().timestamp).coerceAtLeast(0L),
                        avgTemperatureCelsius = candidateSample.avgTemperatureC?.toFloat() ?: record.temperature,
                        maxTemperatureCelsius = candidateSample.maxTemperatureC?.toFloat() ?: record.temperature,
                        sampleCount = samples.size,
                        qualityScore = candidateSample.coverageRatio,
                        isExcluded = candidateSample.isExcluded,
                        exclusionReason = candidateSample.exclusionReason,
                        energyWh = candidateSample.energyWh,
                        avgVoltageVolts = candidateSample.avgVoltageVolts?.toFloat() ?: record.voltageVolts
                    )
                    candidateDbHelper.insertCandidate(candidate)
                    insertedAny = true
                }

                // 无论是否新入库样本，因为 20 段 SOC 能量曲线已在内存中全量吸收，均触发一次最新估计刷新
                refreshEstimateInternal()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
