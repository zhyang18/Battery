package com.battery.analysis

import com.battery.analysis.domain.estimator.DischargeEnergyIntegrator
import com.battery.analysis.domain.estimator.DischargeSessionValidator
import com.battery.analysis.domain.estimator.model.SampleQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 放电会话样本物理完整性与工况校验器单元测试（DischargeSessionValidatorTest）。
 */
class DischargeSessionValidatorTest {

    /**
     * 测试深睡超长断层波及的区间被严格标记并拒绝计入完整学习样本。
     * 假设深睡跨越 80% 到 75%（波及 bin 15: 75%~80%），该 Bin 必须被拒绝。
     */
    @Test
    fun testDeepSleepGapRejection() {
        val deltas = mapOf(
            15 to DischargeEnergyIntegrator.BinEnergyDelta(
                binIndex = 15,
                energyWh = 0.5,
                durationMs = 60_000L,
                startSocPercent = 80.0f,
                endSocPercent = 75.0f
            ),
            14 to DischargeEnergyIntegrator.BinEnergyDelta(
                binIndex = 14,
                energyWh = 1.3,
                durationMs = 120_000L,
                startSocPercent = 75.0f,
                endSocPercent = 70.0f
            )
        )
        val gaps = listOf(
            DischargeEnergyIntegrator.UnobservedSocRange(
                startSocPercent = 80.0f,
                endSocPercent = 75.0f,
                gapDurationMs = 300_000L
            )
        )
        val integrationResult = DischargeEnergyIntegrator.DischargeIntegrationResult(
            binDeltas = deltas,
            unobservedRanges = gaps,
            totalValidDurationMs = 180_000L,
            totalIntegratedEnergyWh = 1.8
        )

        val validation = DischargeSessionValidator.validateDischargeSession(
            integrationResult = integrationResult,
            sessionId = "session_1"
        )

        // bin 15 受断层影响，被拒绝入库
        assertTrue(validation.rejectedBinIndices.contains(15))
        assertFalse(validation.validCompleteSamples.any { it.binIndex == 15 })

        // bin 14 完整且未受断层影响，应评定为合格完整样本
        val bin14Sample = validation.validCompleteSamples.find { it.binIndex == 14 }
        assertNotNull(bin14Sample)
        assertEquals(SampleQuality.COMPLETE_BIN, bin14Sample!!.quality)
        assertEquals(1.0, bin14Sample.coverageFraction, 0.001)
    }

    /**
     * 测试覆盖率门限分级：覆盖率 >= 90% 判定为 COMPLETE_BIN，部分覆盖判定为 PARTIAL_BIN。
     */
    @Test
    fun testCoverageFractionClassification() {
        val deltas = mapOf(
            // bin 19: 跨度 4.6% (4.6 / 5.0 = 92% >= 90%)
            19 to DischargeEnergyIntegrator.BinEnergyDelta(
                binIndex = 19,
                energyWh = 1.25,
                durationMs = 100_000L,
                startSocPercent = 99.6f,
                endSocPercent = 95.0f,
                coveredSocSpan = 4.6f,
                averageCurrentAmperes = 0.5f,
                averageTemperatureCelsius = 26.0f
            ),
            // bin 18: 跨度 2.0% (2.0 / 5.0 = 40% < 90%)
            18 to DischargeEnergyIntegrator.BinEnergyDelta(
                binIndex = 18,
                energyWh = 0.50,
                durationMs = 40_000L,
                startSocPercent = 95.0f,
                endSocPercent = 93.0f,
                coveredSocSpan = 2.0f
            )
        )
        val integrationResult = DischargeEnergyIntegrator.DischargeIntegrationResult(
            binDeltas = deltas,
            unobservedRanges = emptyList(),
            totalValidDurationMs = 140_000L,
            totalIntegratedEnergyWh = 1.75
        )

        val validation = DischargeSessionValidator.validateDischargeSession(
            integrationResult = integrationResult,
            sessionId = "session_2",
            effectiveCapacityMah = 5000.0 // 5000 mAh = 5.0 A
        )

        assertEquals(1, validation.validCompleteSamples.size)
        val completeBin19 = validation.validCompleteSamples[0]
        assertEquals(19, completeBin19.binIndex)
        assertEquals(SampleQuality.COMPLETE_BIN, completeBin19.quality)
        // 放电倍率: 0.5A / 5.0A = 0.1C
        assertNotNull(completeBin19.averageDischargeCRate)
        assertEquals(0.1, completeBin19.averageDischargeCRate!!, 0.001)

        assertEquals(1, validation.partialSamples.size)
        val partialBin18 = validation.partialSamples[0]
        assertEquals(18, partialBin18.binIndex)
        assertEquals(SampleQuality.PARTIAL_BIN, partialBin18.quality)
    }
}
