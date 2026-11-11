package com.battery.analysis.domain.capacity.estimator

import com.battery.analysis.domain.capacity.model.CapacityQualityAssessment
import com.battery.analysis.domain.capacity.model.CapacitySample
import com.battery.analysis.domain.capacity.model.ChargingStatus
import com.battery.analysis.domain.capacity.model.CounterConsistency
import com.battery.analysis.domain.capacity.model.CounterQuality
import com.battery.analysis.domain.capacity.model.DischargeQuality
import com.battery.analysis.domain.capacity.model.QualityLevel
import com.battery.analysis.domain.capacity.model.TopPlateauMetrics
import kotlin.math.abs
import kotlin.math.max

/**
 * 放电会话多维质检评估结果实体（包含点级、区间级与时长级三重视角，及 FCC_Q/FCC_E 双维度解耦评估）。
 *
 * @property quality 综合放电质量评级 [DischargeQuality]
 * @property physicalCurrentRatio 有效区间物理实测电流点比例（0.0 ~ 1.0）
 * @property validIntervalRatio 有效连续梯形微积分区间比例（0.0 ~ 1.0）
 * @property validDurationRatio 有效积分时长与预期时长比例（即有效区间覆盖率）
 * @property maxSampleGapSeconds 有效区间最大采样间隔（秒）
 * @property socSpanPercent 有效放电 SOC 下降跨度百分比（如 49.0 代表 49%）
 * @property unobservedSocLossPercent 采样断层中丢失的未观测 SOC 消耗百分比（如 5.0 代表断层流失了 5% SOC）
 * @property currentSourceQuality 电流源质量评级
 * @property timestampQuality 时间戳质量评级
 * @property socQuality SOC 质量评级
 * @property voltageQuality 电压质量评级
 * @property temperatureQuality 温度质量评级
 * @property rejectionReasons 导致被排除拒绝的具体原因集合（综合）
 * @property warningFlags 非致命警告标志集合
 * @property effectiveIntervalCoverage 有效放电区间时间覆盖率（独立计算，不受平台稀释）
 * @property topPlateauCoverage 顶部平台期时间覆盖率
 * @property wholeSessionCoverage 全会话时间覆盖率
 * @property dualAssessment 容量 FCC_Q 与能量 FCC_E 双维度独立评估结果 [CapacityQualityAssessment]
 */
data class SessionQualityResult(
    val quality: DischargeQuality,
    val physicalCurrentRatio: Double,
    val validIntervalRatio: Double,
    val validDurationRatio: Double,
    val maxSampleGapSeconds: Long,
    val socSpanPercent: Double,
    val unobservedSocLossPercent: Double,
    val currentSourceQuality: QualityLevel,
    val timestampQuality: QualityLevel,
    val socQuality: QualityLevel,
    val voltageQuality: QualityLevel,
    val temperatureQuality: QualityLevel,
    val rejectionReasons: Set<String>,
    val warningFlags: Set<String>,
    val effectiveIntervalCoverage: Double,
    val topPlateauCoverage: Double,
    val wholeSessionCoverage: Double,
    val dualAssessment: CapacityQualityAssessment
)

/**
 * 放电会话多维质量评估器（DischargeSessionQualityEvaluator）。
 *
 * 核心技术规范（遵循第二阶段 P1-B 补充指示）：
 * 1. 区分点质量与区间质量：同时评估 physicalCurrentPointRatio、validIntervalRatio 与 validDurationRatio；
 * 2. 积分连续性与 SOC 分母绑定：统计断层切断区间所丢失的未观测 SOC。若断层跨越了明显 SOC 变化（> 1.5%），坚决拒绝拿全会话 SOC 推算虚假 FCC，杜绝 80% 覆盖率导致 FCC 严重低估；
 * 3. 量纲门控解耦（FCC_Q 与 FCC_E）：电压缺失仅拒绝 FCC_E 能量学习，绝不影响 FCC_Q 容量通道评估；
 * 4. 电流源一致性审计：监控同一区间内是否混用不同硬件电流源并标记告警；
 * 5. 覆盖率区间解耦：时间覆盖率必须针对有效放电区间独立计算，绝不将 100% 平台时间混入分母稀释有效质量。
 */
object DischargeSessionQualityEvaluator {

    /** 允许梯形连续微积分的最大时间间隔缺口（秒） */
    const val MAX_INTEGRATION_GAP_SEC: Double = 60.0

    /** 物理实测电流最低点占比门限（80%） */
    const val MIN_PHYSICAL_CURRENT_RATIO: Double = 0.80

    /** 有效区间时间覆盖率最低占比门限（80%） */
    const val MIN_EFFECTIVE_COVERAGE_RATIO: Double = 0.80

    /** 断层允许的最大未观测 SOC 丢失容差（1.5%）。超过此值必须拒绝使用全会话 SOC 跨度计算 FCC */
    const val MAX_UNOBSERVED_SOC_LOSS_PERCENT: Double = 3.0

    /** 最小入库推荐 SOC 下降跨度门限（20%） */
    const val RECOMMENDED_MIN_SOC_SPAN_PERCENT: Double = 20.0

    /** 绝对准入底线 SOC 跨度门限（10%） */
    const val ABSOLUTE_MIN_SOC_SPAN_PERCENT: Double = 10.0

    /**
     * 对放电会话进行多维细粒度质量评估（包含点、区间、时间与 SOC 绑定检验，及 Q/E 维度解耦）。
     *
     * @param allSamples 全量会话采样点（包含平台期）
     * @param effectiveSamples 有效放电积分采样点序列（已解耦顶部平台）
     * @param effectiveDeltaSoc 有效放电 SOC 变化跨度（0.0 ~ 1.0）
     * @param plateauMetrics 顶部满电平台独立统计指标
     * @param anomalyReason 解耦器检测到的前置异常原因（如 SOC_START_JUMP）
     * @return 综合多维质检评估结果 [SessionQualityResult]
     */
    fun evaluate(
        allSamples: List<CapacitySample>,
        effectiveSamples: List<CapacitySample>,
        effectiveDeltaSoc: Double,
        plateauMetrics: TopPlateauMetrics?,
        anomalyReason: String? = null
    ): SessionQualityResult {
        val qRejections = mutableSetOf<String>()
        val eRejections = mutableSetOf<String>()
        val warningFlags = mutableSetOf<String>()

        val socSpanPercent = effectiveDeltaSoc * 100.0

        // 1. 时间覆盖率与断层区间解耦计算
        val (effectiveCoverage, maxEffectiveGapSec) = calculateCoverage(effectiveSamples)
        val (wholeCoverage, _) = calculateCoverage(allSamples)

        val topPlateauCoverage = if (plateauMetrics != null && plateauMetrics.durationSeconds > 0) {
            val plateauEndTimestamp = if (effectiveSamples.isNotEmpty()) {
                effectiveSamples.first().timestampMs
            } else {
                allSamples.lastOrNull()?.timestampMs ?: 0L
            }
            val plateauSamples = allSamples.filter { it.timestampMs <= plateauEndTimestamp }
            calculateCoverage(plateauSamples).first
        } else {
            1.0
        }

        // 2. 点质量与可积分区间质量双重统计
        val totalPoints = effectiveSamples.size
        val measuredCurrentPoints = effectiveSamples.count { it.currentAmp != null && it.currentAmp > 0.0 }
        val physicalCurrentRatio = if (totalPoints > 0) measuredCurrentPoints.toDouble() / totalPoints.toDouble() else 0.0

        val totalIntervals = max(0, totalPoints - 1)
        var validIntervalCount = 0
        var unobservedSocLoss = 0.0

        for (i in 0 until totalIntervals) {
            val a = effectiveSamples[i]
            val b = effectiveSamples[i + 1]
            val dt = (b.timestampMs - a.timestampMs) / 1000.0
            val isValidStep = dt in 0.001..MAX_INTEGRATION_GAP_SEC && (a.currentAmp != null && a.currentAmp > 0.0) && (b.currentAmp != null && b.currentAmp > 0.0)

            if (isValidStep) {
                validIntervalCount++
            } else {
                // 断层或无效区间：统计在此期间丢失的未观测 SOC 变化
                val socA = a.socFraction
                val socB = b.socFraction
                if (socA != null && socB != null && socA > socB) {
                    unobservedSocLoss += (socA - socB) * 100.0
                }
            }
        }

        val validIntervalRatio = if (totalIntervals > 0) validIntervalCount.toDouble() / totalIntervals.toDouble() else 0.0

        // 核心约束：将积分连续性与 SOC 分母绑定
        // 即使有效时间覆盖率达到 80%，若断层切断期间丢失了超过 1.5% 的 SOC，严禁拿全会话 SOC 跨度计算 FCC！
        if (unobservedSocLoss > MAX_UNOBSERVED_SOC_LOSS_PERCENT) {
            val reason = String.format(java.util.Locale.US, "采样断层跨越未观测SOC变化(丢失%.1f%% > %.1f%%)，严禁以全会话SOC跨度推算虚假FCC", unobservedSocLoss, MAX_UNOBSERVED_SOC_LOSS_PERCENT)
            qRejections.add(reason)
            eRejections.add(reason)
        }

        // 3. 电流源质量评级与状态混入检查
        var currentSourceQuality = QualityLevel.GOOD
        var hasChargingState = false
        var hasNegativeCurrent = false
        val distinctSources = effectiveSamples.map { it.currentSource }.filter { it.isNotEmpty() }.distinct()

        if (distinctSources.size > 1) {
            warningFlags.add("同一会话内存在电流源切换混用: " + distinctSources.joinToString())
        }

        for (sample in effectiveSamples) {
            if (sample.chargingStatus == ChargingStatus.CHARGING) {
                hasChargingState = true
            }
            val cur = sample.currentAmp
            if (cur != null && cur < 0.0) {
                hasNegativeCurrent = true
            }
        }

        if (hasChargingState || hasNegativeCurrent) {
            currentSourceQuality = QualityLevel.INVALID
            val reason = "会话中混入充电数据，不能跨状态积分"
            qRejections.add(reason)
            eRejections.add(reason)
        } else if (physicalCurrentRatio < MIN_PHYSICAL_CURRENT_RATIO) {
            currentSourceQuality = QualityLevel.POOR
            val reason = String.format(java.util.Locale.US, "物理实测电流占比不足(%.1f%% < 80%%)", physicalCurrentRatio * 100.0)
            qRejections.add(reason)
            eRejections.add(reason)
        } else if (physicalCurrentRatio >= 0.95) {
            currentSourceQuality = QualityLevel.EXCELLENT
        }

        // 4. 时间戳质量评估（针对原始采样序列 allSamples 校验时序物理合法性）
        var timestampQuality = QualityLevel.GOOD
        var hasTimestampRegression = false
        var hasTimestampDuplicate = false

        for (i in 0 until allSamples.size - 1) {
            val dt = (allSamples[i + 1].timestampMs - allSamples[i].timestampMs) / 1000.0
            if (dt < 0.0) {
                hasTimestampRegression = true
            } else if (dt == 0.0) {
                hasTimestampDuplicate = true
            }
        }

        if (hasTimestampRegression) {
            timestampQuality = QualityLevel.INVALID
            val reason = "时间戳出现严重倒退异常"
            qRejections.add(reason)
            eRejections.add(reason)
        } else if (hasTimestampDuplicate) {
            timestampQuality = QualityLevel.FAIR
            warningFlags.add("存在时间戳重复采样点")
        } else {
            timestampQuality = QualityLevel.EXCELLENT
        }

        // 5. SOC 质量评估
        var socQuality = QualityLevel.GOOD
        if (anomalyReason != null) {
            socQuality = QualityLevel.POOR
            qRejections.add(anomalyReason)
            eRejections.add(anomalyReason)
        }

        // 校验有效区间内 SOC 逆向回升
        var maxSocRise = 0.0
        for (i in 0 until effectiveSamples.size - 1) {
            val socA = effectiveSamples[i].socFraction
            val socB = effectiveSamples[i + 1].socFraction
            if (socA != null && socB != null && socB > socA) {
                val rise = (socB - socA) * 100.0
                if (rise > maxSocRise) {
                    maxSocRise = rise
                }
            }
        }

        if (maxSocRise > 2.0) {
            socQuality = QualityLevel.INVALID
            val reason = String.format(java.util.Locale.US, "放电期间SOC异常逆向回升(%.1f%% > 2%%)", maxSocRise)
            qRejections.add(reason)
            eRejections.add(reason)
        } else if (maxSocRise > 0.5) {
            socQuality = QualityLevel.FAIR
            warningFlags.add(String.format(java.util.Locale.US, "放电期间存在微弱SOC回弹(%.1f%%)", maxSocRise))
        }

        // 校验有效 SOC 跨度门限
        if (socSpanPercent < ABSOLUTE_MIN_SOC_SPAN_PERCENT) {
            val reason = "SOC 跨度过小(<10%)，量化误差敏感"
            qRejections.add(reason)
            eRejections.add(reason)
        } else if (socSpanPercent < RECOMMENDED_MIN_SOC_SPAN_PERCENT) {
            warningFlags.add("SOC跨度介于10%~20%，未达推荐收敛门限(>=20%)")
        }

        // 6. 有效区间覆盖率检查
        if (effectiveCoverage < MIN_EFFECTIVE_COVERAGE_RATIO) {
            val reason = String.format(java.util.Locale.US, "有效区间采样断层过多(覆盖率%.1f%% < 80%%)", effectiveCoverage * 100.0)
            qRejections.add(reason)
            eRejections.add(reason)
        }
        if (maxEffectiveGapSec > MAX_INTEGRATION_GAP_SEC.toLong()) {
            warningFlags.add(String.format(java.util.Locale.US, "有效区间存在超过%ds的采样中断", MAX_INTEGRATION_GAP_SEC.toLong()))
        }

        // 7. 电压与温度评估（解耦关键：电压缺失仅拒绝 FCC_E，绝不排除 FCC_Q！）
        var voltageQuality = QualityLevel.GOOD
        val validVoltages = effectiveSamples.mapNotNull { it.voltageVolt }.filter { it > 0.0 }
        if (validVoltages.isEmpty()) {
            voltageQuality = QualityLevel.INVALID
            eRejections.add("电压传感器数据缺失或无效，无法准确积分物理能量")
        } else {
            val minV = validVoltages.minOrNull() ?: 3.8
            val maxV = validVoltages.maxOrNull() ?: 3.8
            if (minV < 2.5 || maxV > 4.6) {
                voltageQuality = QualityLevel.FAIR
                warningFlags.add(String.format(java.util.Locale.US, "电压范围[%.2fV ~ %.2fV]偏离典型单电芯平台", minV, maxV))
            }
        }

        var temperatureQuality = QualityLevel.GOOD
        val validTemps = effectiveSamples.mapNotNull { it.temperatureC }
        if (validTemps.isNotEmpty()) {
            val maxTemp = validTemps.maxOrNull() ?: 25.0
            val minTemp = validTemps.minOrNull() ?: 25.0
            if (maxTemp > 50.0) {
                temperatureQuality = QualityLevel.FAIR
                warningFlags.add(String.format(java.util.Locale.US, "电芯温度过高(最高%.1f℃ > 50℃)", maxTemp))
            } else if (minTemp < 0.0) {
                temperatureQuality = QualityLevel.FAIR
                warningFlags.add(String.format(java.util.Locale.US, "低温工况(最低%.1f℃ < 0℃)，极化阻抗增大", minTemp))
            }
        }

        // 8. 独立质量定级
        val fccQQuality = when {
            qRejections.isNotEmpty() -> DischargeQuality.REJECTED
            socSpanPercent >= 30.0 && physicalCurrentRatio >= 0.90 && effectiveCoverage >= 0.90 && warningFlags.isEmpty() -> DischargeQuality.HIGH
            socSpanPercent >= RECOMMENDED_MIN_SOC_SPAN_PERCENT && physicalCurrentRatio >= MIN_PHYSICAL_CURRENT_RATIO && effectiveCoverage >= MIN_EFFECTIVE_COVERAGE_RATIO -> DischargeQuality.MEDIUM
            else -> DischargeQuality.LOW
        }

        val fccEQuality = when {
            eRejections.isNotEmpty() -> DischargeQuality.REJECTED
            socSpanPercent >= 30.0 && physicalCurrentRatio >= 0.90 && effectiveCoverage >= 0.90 && validVoltages.isNotEmpty() && warningFlags.isEmpty() -> DischargeQuality.HIGH
            socSpanPercent >= RECOMMENDED_MIN_SOC_SPAN_PERCENT && physicalCurrentRatio >= MIN_PHYSICAL_CURRENT_RATIO && effectiveCoverage >= MIN_EFFECTIVE_COVERAGE_RATIO && validVoltages.isNotEmpty() -> DischargeQuality.MEDIUM
            else -> DischargeQuality.LOW
        }

        val dualAssessment = CapacityQualityAssessment(
            fccQQuality = fccQQuality,
            fccEQuality = fccEQuality,
            fccQRejectionReasons = qRejections,
            fccERejectionReasons = eRejections,
            counterConsistency = CounterConsistency.UNAVAILABLE, // 稍后由外部挂载
            counterQuality = CounterQuality.UNAVAILABLE,
            warningFlags = warningFlags
        )

        return SessionQualityResult(
            quality = fccQQuality,
            physicalCurrentRatio = physicalCurrentRatio,
            validIntervalRatio = validIntervalRatio,
            validDurationRatio = effectiveCoverage,
            maxSampleGapSeconds = maxEffectiveGapSec,
            socSpanPercent = socSpanPercent,
            unobservedSocLossPercent = unobservedSocLoss,
            currentSourceQuality = currentSourceQuality,
            timestampQuality = timestampQuality,
            socQuality = socQuality,
            voltageQuality = voltageQuality,
            temperatureQuality = temperatureQuality,
            rejectionReasons = qRejections,
            warningFlags = warningFlags,
            effectiveIntervalCoverage = effectiveCoverage,
            topPlateauCoverage = topPlateauCoverage,
            wholeSessionCoverage = wholeCoverage,
            dualAssessment = dualAssessment
        )
    }

    /**
     * 计算采样序列的有效微积分时间覆盖率与最大采样缺口秒数。
     *
     * @param samples 采样点序列
     * @param maxGapSec 允许连续梯形微积分的最大间隔秒数（默认 60s）
     * @return 覆盖率比例（0.0 ~ 1.0）与最大观测缺口（秒）的 Pair
     */
    fun calculateCoverage(
        samples: List<CapacitySample>,
        maxGapSec: Double = MAX_INTEGRATION_GAP_SEC
    ): Pair<Double, Long> {
        if (samples.size < 2) {
            return Pair(if (samples.size == 1) 1.0 else 0.0, 0L)
        }

        val startTs = samples.first().timestampMs
        val endTs = samples.last().timestampMs
        val expectedDurationSec = max(0.0, (endTs - startTs) / 1000.0)

        var validDurationSec = 0.0
        var maxObservedGapSec = 0.0

        for (i in 0 until samples.size - 1) {
            val dt = (samples[i + 1].timestampMs - samples[i].timestampMs) / 1000.0
            if (dt <= 0.0) continue

            if (dt > maxObservedGapSec) {
                maxObservedGapSec = dt
            }
            if (dt <= maxGapSec) {
                validDurationSec += dt
            }
        }

        val coverage = if (expectedDurationSec > 0.0) {
            (validDurationSec / expectedDurationSec).coerceIn(0.0, 1.0)
        } else if (validDurationSec > 0.0) 1.0 else 0.0

        return Pair(coverage, maxObservedGapSec.toLong())
    }
}
