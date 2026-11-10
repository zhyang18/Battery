package com.battery.analysis.domain.capacity.estimator

import com.battery.analysis.domain.capacity.integration.CoulombIntegrator
import com.battery.analysis.domain.capacity.model.CapacityChannel
import com.battery.analysis.domain.capacity.model.CapacitySample
import com.battery.analysis.domain.capacity.model.ChargingStatus
import com.battery.analysis.domain.capacity.model.Confidence
import com.battery.analysis.domain.capacity.model.FccCandidateSample
import com.battery.analysis.domain.capacity.model.FccChannelEstimate
import com.battery.analysis.domain.capacity.model.FullEndpointQuality
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 充电容量辅助估计通道（ChargeCapacityEstimator）。
 *
 * 遵循技术方案规范：
 * 1. 对电池侧净充入电流进行库仑积分；
 * 2. 估计充电表观容量 FCC_apparent = Q_chg / Δz_chg；
 * 3. 库仑效率未校准前保留表观值与较大不确定度，严禁硬编码固定效率；
 * 4. 严谨审计满充端点质量（电压平台、电流锥形递减、系统状态 FULL）；
 * 5. 跨度门限与方差评估。
 */
object ChargeCapacityEstimator {

    private const val VARIANCE_DELTA_Z: Double = 1.6666666666666667e-5

    /** 充电过程电流积分基准不确定度（考虑效率未校准，保守取 3.5%） */
    private const val RELATIVE_UNCERTAINTY_CHARGE_Q: Double = 0.035

    const val MIN_ELIGIBLE_SOC_SPAN: Double = 0.10

    /**
     * 计算充电单次候选样本方差。
     */
    fun calculateSampleVariance(chargeMah: Double, deltaSoc: Double): Double {
        val dz = deltaSoc.coerceIn(0.01, 1.0)
        val dq = abs(chargeMah).coerceAtLeast(1.0)

        val sigmaDq = RELATIVE_UNCERTAINTY_CHARGE_Q * dq
        val term1 = (sigmaDq / dz).pow(2.0)
        val term2 = (dq.pow(2.0) / dz.pow(4.0)) * VARIANCE_DELTA_Z
        return term1 + term2
    }

    /**
     * 评估充电会话的满充端点质量（Section 7.3）。
     */
    fun evaluateEndpointQuality(
        endSoc: Double,
        lastVoltage: Double?,
        lastCurrentAmp: Double?,
        lastStatus: ChargingStatus,
        isDualCell: Boolean = false
    ): FullEndpointQuality {
        val isFullSoc = endSoc >= 0.99
        val isFullStatus = lastStatus == ChargingStatus.FULL

        val minPlatformV = if (isDualCell && (lastVoltage ?: 0.0) > 6.0) 8.4 else 4.20
        val isVoltageAtPlatform = lastVoltage != null && lastVoltage >= minPlatformV

        val isCurrentTapered = lastCurrentAmp != null && lastCurrentAmp in 0.005..0.50

        return when {
            isFullSoc && (isFullStatus || (isVoltageAtPlatform && isCurrentTapered)) -> FullEndpointQuality.EXCELLENT
            isFullSoc && isVoltageAtPlatform -> FullEndpointQuality.GOOD
            isFullStatus -> FullEndpointQuality.FAIR
            endSoc >= 0.85 -> FullEndpointQuality.POOR
            else -> FullEndpointQuality.UNKNOWN
        }
    }

    /**
     * 从一组充电采样点中评估并构建单次充电候选样本。
     */
    fun buildCandidate(
        sessionId: String,
        samples: List<CapacitySample>,
        startSoc: Double,
        endSoc: Double,
        isDualCell: Boolean = false
    ): FccCandidateSample {
        val deltaSoc = abs(endSoc - startSoc)
        val segment = CoulombIntegrator.integrate(samples)

        val qMah = segment.chargeMah
        val eWh = segment.energyWh
        val dz = deltaSoc.coerceIn(0.001, 1.0)
        val fccApparent = if (deltaSoc > 0.0) qMah / dz else 0.0
        val variance = calculateSampleVariance(qMah, dz)

        val lastPt = samples.lastOrNull()
        val endpointQuality = evaluateEndpointQuality(
            endSoc = endSoc,
            lastVoltage = lastPt?.voltageVolt,
            lastCurrentAmp = lastPt?.currentAmp,
            lastStatus = lastPt?.chargingStatus ?: ChargingStatus.UNKNOWN,
            isDualCell = isDualCell
        )

        val maxTemp = samples.mapNotNull { it.temperatureC }.maxOrNull()

        val isSpanTooSmall = deltaSoc < MIN_ELIGIBLE_SOC_SPAN
        val isRangeDefective = fccApparent < 500.0 || fccApparent > 25000.0
        val isCoveragePoor = segment.coverageRatio < 0.60
        val isReverseDrain = endSoc <= startSoc

        val isExcluded = isSpanTooSmall || isRangeDefective || isCoveragePoor || isReverseDrain
        val exclusionReason = when {
            isReverseDrain -> "充电期间出现逆向净掉电"
            isSpanTooSmall -> "充电跨度不足(<10%)"
            isRangeDefective -> "推算表观容量超出物理合理范围"
            isCoveragePoor -> "充电采样断层过多(覆盖率<60%)"
            else -> ""
        }

        return FccCandidateSample(
            sessionId = sessionId,
            channel = CapacityChannel.CHARGE,
            startSoc = startSoc,
            endSoc = endSoc,
            deltaSoc = deltaSoc,
            chargeMah = qMah,
            energyWh = eWh,
            fccObservedMah = fccApparent,
            varianceMah2 = variance,
            avgVoltageVolts = segment.avgVoltageVolts,
            avgTemperatureC = segment.avgTemperatureC,
            maxTemperatureC = maxTemp,
            durationSec = segment.validDurationSec,
            coverageRatio = segment.coverageRatio,
            endpointQuality = endpointQuality,
            isExcluded = isExcluded,
            exclusionReason = exclusionReason
        )
    }

    /**
     * 执行充电辅助通道独立估计。
     */
    fun estimateChannel(candidates: List<FccCandidateSample>): FccChannelEstimate {
        val valid = candidates.filter { !it.isExcluded && it.fccObservedMah > 0.0 && it.channel == CapacityChannel.CHARGE }
        if (valid.isEmpty()) {
            return FccChannelEstimate(
                channel = CapacityChannel.CHARGE,
                fccQMah = null,
                uncertaintyMah = null,
                sampleCount = 0,
                totalSocSpan = 0.0,
                confidence = Confidence.LOW,
                apparentOnly = true,
                details = "暂无合格充电样本"
            )
        }

        var sumWeight = 0.0
        var sumWeightedFcc = 0.0
        var totalSpan = 0.0
        var tempSum = 0.0
        var tempSpanSum = 0.0

        for (c in valid) {
            val weight = 1.0 / c.varianceMah2.coerceAtLeast(1.0)
            sumWeight += weight
            sumWeightedFcc += weight * c.fccObservedMah
            totalSpan += c.deltaSoc
            if (c.avgTemperatureC != null) {
                tempSum += c.avgTemperatureC * c.deltaSoc
                tempSpanSum += c.deltaSoc
            }
        }

        val estimatedFcc = sumWeightedFcc / sumWeight

        var weightedVarSum = 0.0
        for (c in valid) {
            val w = 1.0 / c.varianceMah2.coerceAtLeast(1.0)
            val diff = c.fccObservedMah - estimatedFcc
            weightedVarSum += w * diff * diff
        }
        val weightedStdDev = sqrt(weightedVarSum / sumWeight)
        val uncertainty = if (valid.size > 1) {
            (1.96 * weightedStdDev) / sqrt(valid.size.toDouble())
        } else {
            sqrt(valid.first().varianceMah2)
        }

        val avgTemp = if (tempSpanSum > 0.0) tempSum / tempSpanSum else null

        val confidence = when {
            valid.size >= 4 && totalSpan >= 1.5 && (uncertainty / estimatedFcc) < 0.05 -> Confidence.HIGH
            valid.size >= 2 && totalSpan >= 0.5 -> Confidence.MEDIUM
            else -> Confidence.LOW
        }

        val predominantQuality = valid.map { it.endpointQuality }.minByOrNull { it.ordinal } ?: FullEndpointQuality.UNKNOWN

        return FccChannelEstimate(
            channel = CapacityChannel.CHARGE,
            fccQMah = estimatedFcc,
            uncertaintyMah = uncertainty,
            sampleCount = valid.size,
            totalSocSpan = totalSpan,
            avgTemperatureC = avgTemp,
            confidence = confidence,
            apparentOnly = true,
            endpointQuality = predominantQuality,
            details = String.format(java.util.Locale.US, "充电表观样本: %d 条, 跨度: %.0f%% (库仑效率未校准)", valid.size, totalSpan * 100.0)
        )
    }
}
