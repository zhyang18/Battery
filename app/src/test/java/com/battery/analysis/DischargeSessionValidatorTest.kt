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
        // 关键断言：跨度 4.6% 时的微元能量 1.25Wh 应被标准化折算为 5.0% 满区间能量
        val expectedNormalizedWh = 1.25 * (5.0 / 4.6)
        assertEquals(expectedNormalizedWh, completeBin19.energyWh, 0.0001)

        // 放电倍率: 0.5A / 5.0A = 0.1C
        assertNotNull(completeBin19.averageDischargeCRate)
        assertEquals(0.1, completeBin19.averageDischargeCRate!!, 0.001)

        assertEquals(1, validation.partialSamples.size)
        val partialBin18 = validation.partialSamples[0]
        assertEquals(18, partialBin18.binIndex)
        assertEquals(SampleQuality.PARTIAL_BIN, partialBin18.quality)
    }

    /**
     * 测试 5% 满跨度归一化折算的精度：
     * 1. 4.5% 跨度（门限临界值）测得 1.08 Wh，归一化后必须严格为 1.20 Wh；
     * 2. 5.0% 满跨度测得 1.20 Wh，归一化后保持原值 1.20 Wh。
     */
    @Test
    fun testSpanNormalizationPrecision() {
        val deltas = mapOf(
            10 to DischargeEnergyIntegrator.BinEnergyDelta(
                binIndex = 10,
                energyWh = 1.08,
                durationMs = 90_000L,
                startSocPercent = 54.5f,
                endSocPercent = 50.0f,
                coveredSocSpan = 4.5f
            ),
            11 to DischargeEnergyIntegrator.BinEnergyDelta(
                binIndex = 11,
                energyWh = 1.20,
                durationMs = 100_000L,
                startSocPercent = 60.0f,
                endSocPercent = 55.0f,
                coveredSocSpan = 5.0f
            )
        )
        val integrationResult = DischargeEnergyIntegrator.DischargeIntegrationResult(
            binDeltas = deltas,
            unobservedRanges = emptyList(),
            totalValidDurationMs = 190_000L,
            totalIntegratedEnergyWh = 2.28
        )

        val validation = DischargeSessionValidator.validateDischargeSession(
            integrationResult = integrationResult,
            sessionId = "session_norm_test",
            effectiveCapacityMah = 6247.0
        )

        assertEquals(2, validation.validCompleteSamples.size)
        val s10 = validation.validCompleteSamples.find { it.binIndex == 10 }!!
        val s11 = validation.validCompleteSamples.find { it.binIndex == 11 }!!

        // 1.08 * (5.0 / 4.5) = 1.20
        assertEquals(1.20, s10.energyWh, 0.0001)
        // 满 5.0% 跨度无额外倍数
        assertEquals(1.20, s11.energyWh, 0.0001)
    }

    /**
     * 测试废除极端一票否决拉黑：
     * 当某区间仅存在微小的失联断层（例如缺损 0.2%），
     * 系统绝不能因为点接触或微小断层将整段拉黑，正常有效跨度（4.8% >= 4.5%）必须正常评定为 COMPLETE_BIN。
     */
    @Test
    fun testMinorGapNotCausingOneVoteVetoRejection() {
        val deltas = mapOf(
            5 to DischargeEnergyIntegrator.BinEnergyDelta(
                binIndex = 5, // 25%~30%
                energyWh = 1.40,
                durationMs = 600_000L,
                startSocPercent = 30.0f,
                endSocPercent = 25.2f,
                coveredSocSpan = 4.8f // 覆盖 4.8%，缺损 0.2%
            )
        )
        val minorGaps = listOf(
            DischargeEnergyIntegrator.UnobservedSocRange(
                startSocPercent = 25.2f,
                endSocPercent = 25.0f, // 仅缺损 0.2%
                gapDurationMs = 200_000L
            )
        )
        val integrationResult = DischargeEnergyIntegrator.DischargeIntegrationResult(
            binDeltas = deltas,
            unobservedRanges = minorGaps,
            totalValidDurationMs = 600_000L,
            totalIntegratedEnergyWh = 1.40
        )

        val validation = DischargeSessionValidator.validateDischargeSession(
            integrationResult = integrationResult,
            sessionId = "session_minor_gap",
            effectiveCapacityMah = 8000.0
        )

        // 缺损 0.2% 远小于 1.0% 的损毁门限，bin 5 绝不能被拉黑拒绝
        assertFalse(validation.rejectedBinIndices.contains(5))
        assertEquals(1, validation.validCompleteSamples.size)
        val s5 = validation.validCompleteSamples[0]
        assertEquals(5, s5.binIndex)
        assertEquals(SampleQuality.COMPLETE_BIN, s5.quality)
        // 1.40 * (5.0 / 4.8) = 1.4583 Wh
        assertEquals(1.40 * (5.0 / 4.8), s5.energyWh, 0.001)
    }

    /**
     * 测试 100% 满充浮充虚假电量做功异常点（如 3.356 Wh）被电化学能量守恒物理守卫严格拦截。
     * 单电芯满充截止电压物理上限为 4.45V，对于 8000mAh 电池，单段 5% 物理做功理论上限仅为：
     * (8000 * 0.05 * 4.45 * 1.05) / 1000 = 1.869 Wh。
     * 3.356 Wh 严重违背物理规律，必须被坚决过滤，绝不允许学入模型污染曲线。
     *
     * @see DischargeSessionValidator.validateDischargeSession
     */
    @Test
    fun testTopOffBufferingEnergyRejection() {
        val deltas = mapOf(
            19 to DischargeEnergyIntegrator.BinEnergyDelta(
                binIndex = 19,
                energyWh = 3.356,
                durationMs = 1_800_000L,
                startSocPercent = 100.0f,
                endSocPercent = 95.0f,
                coveredSocSpan = 5.0f,
                averageCurrentAmperes = 0.5f,
                averageTemperatureCelsius = 32.0f
            )
        )
        val integrationResult = DischargeEnergyIntegrator.DischargeIntegrationResult(
            binDeltas = deltas,
            unobservedRanges = emptyList(),
            totalValidDurationMs = 1_800_000L,
            totalIntegratedEnergyWh = 3.356
        )

        val validation = DischargeSessionValidator.validateDischargeSession(
            integrationResult = integrationResult,
            sessionId = "session_top_off_anomaly",
            effectiveCapacityMah = 8000.0,
            isDualCell = false
        )

        // 3.356 Wh 严重超出单段 5% 物理极限，坚决被拦截，合格样本集必须为空
        assertTrue(validation.validCompleteSamples.isEmpty())
        assertTrue(validation.partialSamples.isEmpty())
    }
}
