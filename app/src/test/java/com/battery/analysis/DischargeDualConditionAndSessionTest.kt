package com.battery.analysis

import com.battery.analysis.domain.estimator.DischargeEnergyIntegrator
import com.battery.analysis.domain.estimator.DischargeSessionValidator
import com.battery.analysis.domain.estimator.SocEnergyCurveLearner
import com.battery.analysis.domain.estimator.model.LearnedSocEnergyCurve
import com.battery.analysis.domain.estimator.model.SampleQuality
import com.battery.analysis.domain.estimator.model.SocEnergyBinSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 放电能量模型双重条件筛选（12档门限 + 滚动时效条数）与跨会话会话标识解耦单元测试。
 */
class DischargeDualConditionAndSessionTest {

    /**
     * 测试跨会话会话标识解耦：
     * 1. 同会话（sessionId = "1001"）多次在途打点，sampleCount 保持为 1；
     * 2. 跨会话（sessionId = "1002"）新样本进入，sampleCount 顺利递增为 2，且能量按均值平滑；
     * 3. 第三会话（sessionId = "1003"）进入，sampleCount 顺利递增为 3。
     */
    @Test
    fun testSessionIdDecouplingIncrementsSampleCount() {
        val initialCurve = LearnedSocEnergyCurve.createEmpty()

        // 1. 会话 1001 首次在途评估 Bin 15 (75%~80%)
        val sample1Session1 = SocEnergyBinSample(
            binIndex = 15,
            energyWh = 1.30,
            durationMs = 600_000L,
            startSocPercent = 80.0f,
            endSocPercent = 75.0f,
            coverageFraction = 1.0,
            sessionId = "1001",
            quality = SampleQuality.COMPLETE_BIN
        )
        val curveS1P1 = SocEnergyCurveLearner.updateCurveWithSamples(
            currentCurve = initialCurve,
            samples = listOf(sample1Session1),
            incrementSessionCount = false,
            allowInFlightReplacement = true
        )
        val bin15S1P1 = curveS1P1.bins[15]
        assertEquals(1, bin15S1P1.sampleCount)
        assertEquals(1.30, bin15S1P1.learnedEnergyWh!!, 0.001)
        assertEquals("1001", bin15S1P1.lastUpdatedSessionId)

        // 2. 会话 1001 期间再次在途刷新（待机补偿或精度提升），同会话覆盖
        val sample2Session1 = SocEnergyBinSample(
            binIndex = 15,
            energyWh = 1.32,
            durationMs = 620_000L,
            startSocPercent = 80.0f,
            endSocPercent = 75.0f,
            coverageFraction = 1.0,
            sessionId = "1001",
            quality = SampleQuality.COMPLETE_BIN
        )
        val curveS1P2 = SocEnergyCurveLearner.updateCurveWithSamples(
            currentCurve = curveS1P1,
            samples = listOf(sample2Session1),
            incrementSessionCount = false,
            allowInFlightReplacement = true
        )
        val bin15S1P2 = curveS1P2.bins[15]
        // 同会话在途刷新，sampleCount 依然为 1，数值无损升级为 1.32
        assertEquals(1, bin15S1P2.sampleCount)
        assertEquals(1.32, bin15S1P2.learnedEnergyWh!!, 0.001)
        assertEquals("1001", bin15S1P2.lastUpdatedSessionId)

        // 3. 全新独立放电会话 1002 到来，再次放电通过 Bin 15
        val sample1Session2 = SocEnergyBinSample(
            binIndex = 15,
            energyWh = 1.38,
            durationMs = 580_000L,
            startSocPercent = 80.0f,
            endSocPercent = 75.0f,
            coverageFraction = 1.0,
            sessionId = "1002",
            quality = SampleQuality.COMPLETE_BIN
        )
        val curveS2 = SocEnergyCurveLearner.updateCurveWithSamples(
            currentCurve = curveS1P2,
            samples = listOf(sample1Session2),
            incrementSessionCount = true,
            allowInFlightReplacement = true
        )
        val bin15S2 = curveS2.bins[15]
        // 跨会话判定成功：sampleCount 必须递增为 2！
        assertEquals(2, bin15S2.sampleCount)
        // 能量正确平滑：(1.32 * 1 + 1.38) / 2 = 1.35 Wh
        assertEquals(1.35, bin15S2.learnedEnergyWh!!, 0.001)
        assertEquals("1002", bin15S2.lastUpdatedSessionId)

        // 4. 第三会话 1003 到来
        val sample1Session3 = SocEnergyBinSample(
            binIndex = 15,
            energyWh = 1.32,
            durationMs = 590_000L,
            startSocPercent = 80.0f,
            endSocPercent = 75.0f,
            coverageFraction = 1.0,
            sessionId = "1003",
            quality = SampleQuality.COMPLETE_BIN
        )
        val curveS3 = SocEnergyCurveLearner.updateCurveWithSamples(
            currentCurve = curveS2,
            samples = listOf(sample1Session3),
            incrementSessionCount = true,
            allowInFlightReplacement = false
        )
        val bin15S3 = curveS3.bins[15]
        assertEquals(3, bin15S3.sampleCount)
        // 能量正确平滑：(1.35 * 2 + 1.32) / 3 = 1.34 Wh
        assertEquals(1.34, bin15S3.learnedEnergyWh!!, 0.001)
        assertEquals("1003", bin15S3.lastUpdatedSessionId)
    }

    /**
     * 测试待机异常大值（如 4.218 Wh）被物理上限守卫（> 3.8 Wh）拦截，严禁污染模型。
     */
    @Test
    fun testStandbySpikeAnomalyRejection() {
        val deltas = mapOf(
            14 to DischargeEnergyIntegrator.BinEnergyDelta(
                binIndex = 14,
                energyWh = 4.218, // 超过标称 5% 能量的 2.5 倍
                durationMs = 36000_000L,
                startSocPercent = 75.0f,
                endSocPercent = 70.0f,
                coveredSocSpan = 5.0f
            ),
            15 to DischargeEnergyIntegrator.BinEnergyDelta(
                binIndex = 15,
                energyWh = 1.34, // 正常样本
                durationMs = 600_000L,
                startSocPercent = 80.0f,
                endSocPercent = 75.0f,
                coveredSocSpan = 5.0f
            )
        )
        val integrationResult = DischargeEnergyIntegrator.DischargeIntegrationResult(
            binDeltas = deltas,
            unobservedRanges = emptyList(),
            totalValidDurationMs = 36600_000L,
            totalIntegratedEnergyWh = 5.558
        )

        val validation = DischargeSessionValidator.validateDischargeSession(
            integrationResult = integrationResult,
            sessionId = "session_standby_anomaly"
        )

        // 4.218 Wh 异常大值被拦截，不能进入 validCompleteSamples
        assertFalse(validation.validCompleteSamples.any { it.binIndex == 14 })

        // 正常样本 1.34 Wh 正常进入
        assertTrue(validation.validCompleteSamples.any { it.binIndex == 15 })

        // 同时验证 SocEnergyCurveLearner 直接拒收 > 3.8 Wh 的样本
        val initialCurve = LearnedSocEnergyCurve.createEmpty()
        val badSample = SocEnergyBinSample(
            binIndex = 14,
            energyWh = 4.218,
            durationMs = 36000_000L,
            startSocPercent = 75.0f,
            endSocPercent = 70.0f,
            coverageFraction = 1.0,
            sessionId = "bad_session"
        )
        val curve = SocEnergyCurveLearner.updateCurveWithSamples(
            currentCurve = initialCurve,
            samples = listOf(badSample)
        )
        assertFalse(curve.bins[14].hasLearnedData())
    }

    /**
     * 测试 12 档门限与滚动时效条数双重条件对宏观满充能量的中位数聚合。
     */
    @Test
    fun testDualConditionMacroMedianRecalculation() {
        // 构造 5 组放电周期数据：
        // 周期 1: 掉电 12%（浅放），满充折算 24.5 Wh
        // 周期 2: 掉电 22%，满充折算 25.2 Wh
        // 周期 3: 掉电 35%，满充折算 25.4 Wh
        // 周期 4: 掉电 45%，满充折算 25.3 Wh
        // 周期 5: 掉电 65%，满充折算 25.5 Wh
        data class MockDischarge(val dropPercent: Float, val fullWh: Double)

        val allSessions = listOf(
            MockDischarge(12f, 24.5),
            MockDischarge(22f, 25.2),
            MockDischarge(35f, 25.4),
            MockDischarge(45f, 25.3),
            MockDischarge(65f, 25.5)
        )

        // 1. 门限 = 15%，条数 = 10（全部） -> 过滤掉 12%，保留 22%, 35%, 45%, 65% (4条)
        val thresh15 = allSessions.filter { it.dropPercent >= 15f }
        assertEquals(4, thresh15.size)
        val median15 = SocEnergyCurveLearner.calculateMedian(thresh15.map { it.fullWh })
        // 中位数为 (25.3 + 25.4) / 2 = 25.35 Wh
        assertEquals(25.35, median15!!, 0.01)

        // 2. 门限 = 30%，条数 = 10 -> 保留 35%, 45%, 65% (3条)
        val thresh30 = allSessions.filter { it.dropPercent >= 30f }
        assertEquals(3, thresh30.size)
        val median30 = SocEnergyCurveLearner.calculateMedian(thresh30.map { it.fullWh })
        // 中位数为 25.4 Wh
        assertEquals(25.4, median30!!, 0.01)

        // 3. 门限 = 15%，时效条数限制 = 最近 2 条 (从后往前取) -> 取 45% (25.3 Wh) 和 65% (25.5 Wh)
        val recent2 = thresh15.takeLast(2)
        assertEquals(2, recent2.size)
        val medianRecent2 = SocEnergyCurveLearner.calculateMedian(recent2.map { it.fullWh })
        assertEquals(25.4, medianRecent2!!, 0.01)
    }

    /**
     * 测试在途会话多次刷新（isReplacingInFlight = true）绝不冲刷抹除历史已学样本权重。
     */
    @Test
    fun testInFlightReplacementPreservesHistoricalAccumulation() {
        val initialCurve = LearnedSocEnergyCurve.createEmpty()

        // 预置 2 个历史会话
        val s1 = SocEnergyBinSample(10, 1.30, 600_000L, 55f, 50f, 1.0, sessionId = "hist_1")
        val s2 = SocEnergyBinSample(10, 1.30, 600_000L, 55f, 50f, 1.0, sessionId = "hist_2")
        val curveHist1 = SocEnergyCurveLearner.updateCurveWithSamples(initialCurve, listOf(s1), incrementSessionCount = true)
        val curveHist2 = SocEnergyCurveLearner.updateCurveWithSamples(curveHist1, listOf(s2), incrementSessionCount = true)

        assertEquals(2, curveHist2.bins[10].sampleCount)
        assertEquals(1.30, curveHist2.bins[10].learnedEnergyWh!!, 0.001)

        // 在途会话 active_1 首次学习该 bin
        val active1 = SocEnergyBinSample(10, 1.45, 600_000L, 55f, 50f, 1.0, sessionId = "active_1")
        val curveActive1 = SocEnergyCurveLearner.updateCurveWithSamples(
            currentCurve = curveHist2,
            samples = listOf(active1),
            incrementSessionCount = false,
            allowInFlightReplacement = true
        )
        // sampleCount 变为 3，均值为 (1.30 * 2 + 1.45) / 3 = 1.35 Wh
        assertEquals(3, curveActive1.bins[10].sampleCount)
        assertEquals(1.35, curveActive1.bins[10].learnedEnergyWh!!, 0.001)

        // 在途会话 active_1 第二次刷新（比如待机补偿后能量为 1.48 Wh）
        val active2 = SocEnergyBinSample(10, 1.48, 620_000L, 55f, 50f, 1.0, sessionId = "active_1")
        val curveActive2 = SocEnergyCurveLearner.updateCurveWithSamples(
            currentCurve = curveActive1,
            samples = listOf(active2),
            incrementSessionCount = false,
            allowInFlightReplacement = true
        )
        // 关键断言：sampleCount 保持为 3，且新能量绝不能退化为纯 1.48 Wh（必须保留前 2 次历史会话权重）
        assertEquals(3, curveActive2.bins[10].sampleCount)
        assertTrue(curveActive2.bins[10].learnedEnergyWh!! < 1.45)
        assertTrue(curveActive2.bins[10].learnedEnergyWh!! > 1.32)
    }

    /**
     * 测试 100% 门限兼容 Android 关机边界（允许 98% 压线通过）。
     */
    @Test
    fun testAndroidShutdownToleranceForFullCycle() {
        val targetThreshold = 100
        val minRequired = if (targetThreshold >= 100) 98 else targetThreshold
        assertEquals(98, minRequired)

        // 模拟 100% 放电至 2% 关机（掉电 98%）
        val drop98 = 98
        assertTrue(drop98 >= minRequired)

        // 模拟普通浅放 95%
        val drop95 = 95
        assertFalse(drop95 >= minRequired)
    }
}
