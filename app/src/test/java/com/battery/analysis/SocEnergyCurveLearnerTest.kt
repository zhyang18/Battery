package com.battery.analysis

import com.battery.analysis.domain.estimator.DischargeEnergyIntegrator
import com.battery.analysis.domain.estimator.SocEnergyCurveLearner
import com.battery.analysis.domain.estimator.model.LearnedSocEnergyCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 电池 5% SOC 分段历史放电能量曲线学习器算法与对比看板单元测试（SocEnergyCurveLearnerTest）。
 * 验证加权移动平均、多会话演进与三源对比计算的物理严密性。
 */
class SocEnergyCurveLearnerTest {

    /**
     * 测试初始状态下 20 个 bin 均为待采集，覆盖率为 0。
     */
    @Test
    fun testInitialCurveIsEmpty() {
        val curve = LearnedSocEnergyCurve.createEmpty()

        assertEquals(20, curve.bins.size)
        assertEquals(0.0f, curve.coveragePercent, 0.001f)
        assertEquals(0, curve.totalSampleSessions)

        for (bin in curve.bins) {
            assertFalse(bin.hasLearnedData())
            assertEquals(0, bin.sampleCount)
            assertNull(bin.learnedEnergyWh)
        }
    }

    /**
     * 测试增量微积分样本学习与加权移动平均（EMA/WMA）收敛更新。
     */
    @Test
    fun testIncrementalLearningAndWeightedAverage() {
        val initialCurve = LearnedSocEnergyCurve.createEmpty()

        // 第一次放电会话：bin 19 (95%~100%) 释放 1.40 Wh
        val session1 = mapOf(
            19 to DischargeEnergyIntegrator.BinEnergyDelta(
                binIndex = 19,
                energyWh = 1.40,
                durationMs = 120_000L,
                startSocPercent = 100.0f,
                endSocPercent = 95.0f
            )
        )
        val curve1 = SocEnergyCurveLearner.updateCurveWithDeltas(initialCurve, session1, 1000L)
        val bin19Round1 = curve1.bins[19]
        assertTrue(bin19Round1.hasLearnedData())
        assertEquals(1, bin19Round1.sampleCount)
        assertEquals(1.40, bin19Round1.learnedEnergyWh!!, 0.001)
        assertEquals(1.0f / 20.0f, curve1.coveragePercent, 0.001f)

        // 第二次放电会话：bin 19 释放 1.30 Wh
        val session2 = mapOf(
            19 to DischargeEnergyIntegrator.BinEnergyDelta(
                binIndex = 19,
                energyWh = 1.30,
                durationMs = 110_000L,
                startSocPercent = 100.0f,
                endSocPercent = 95.0f
            )
        )
        val curve2 = SocEnergyCurveLearner.updateCurveWithDeltas(curve1, session2, 2000L)
        val bin19Round2 = curve2.bins[19]
        assertEquals(2, bin19Round2.sampleCount)
        // 加权平均做功：(1.40 + 1.30) / 2 = 1.35 Wh
        assertEquals(1.35, bin19Round2.learnedEnergyWh!!, 0.001)
        assertEquals(1.30, bin19Round2.minObservedWh!!, 0.001)
        assertEquals(1.40, bin19Round2.maxObservedWh!!, 0.001)
    }

    /**
     * 测试三源对比看板实体生成准确性。
     */
    @Test
    fun testBuildComparisonOverview() {
        var curve = LearnedSocEnergyCurve.createEmpty()

        // 预设 bin 0 (0%~5%) 和 bin 1 (5%~10%) 各释放 1.0 Wh
        curve = SocEnergyCurveLearner.updateCurveWithDeltas(
            curve,
            mapOf(
                0 to DischargeEnergyIntegrator.BinEnergyDelta(0, 1.0, 1000L, 5f, 0f),
                1 to DischargeEnergyIntegrator.BinEnergyDelta(1, 1.0, 1000L, 10f, 5f)
            ),
            1000L
        )

        val overview = SocEnergyCurveLearner.buildComparisonOverviewFromCurve(
            curve = curve,
            currentSocPercent = 10.0f,
            hardwareNativeEnergyWh = 2.15,
            effectiveCapacityMah = 5000.0f,
            nominalVoltageVolts = 3.85f
        )

        assertNotNull(overview.learnedRemainingEnergyWh)
        // 10% SOC 包含 bin 0 和 bin 1，共 2.0 Wh
        assertEquals(2.0, overview.learnedRemainingEnergyWh!!, 0.01)
        assertEquals(2.15, overview.hardwareNativeEnergyWh!!, 0.001)

        // 传统标压计算：(5000 * 3.85 / 1000) * 10% = 19.25 * 0.10 = 1.925 Wh
        assertNotNull(overview.nominalCalculatedEnergyWh)
        assertEquals(1.925, overview.nominalCalculatedEnergyWh!!, 0.001)
    }

    /**
     * 测试非完整区间归一化折算与极小跨度噪声防抖。
     * 场景 1：跨度仅 0.2%（如 95.0% -> 94.8%），因小于 0.5% 阈值被安全忽略；
     * 场景 2：跨度 2.5%（如 95.0% -> 92.5%），释放做功 0.65 Wh，归一化为完整 5% 步长应为 1.30 Wh。
     */
    @Test
    fun testFractionalBinNormalizationAndNoiseFiltering() {
        val initialCurve = LearnedSocEnergyCurve.createEmpty()

        // 场景 1：噪声忽略测试
        val noiseDelta = mapOf(
            18 to DischargeEnergyIntegrator.BinEnergyDelta(
                binIndex = 18,
                energyWh = 0.05,
                durationMs = 5000L,
                startSocPercent = 95.0f,
                endSocPercent = 94.8f
            )
        )
        val curveAfterNoise = SocEnergyCurveLearner.updateCurveWithDeltas(initialCurve, noiseDelta, 1000L)
        assertFalse(curveAfterNoise.bins[18].hasLearnedData())

        // 场景 2：归一化折算测试 (2.5% 跨度释放 0.65 Wh -> 等效 5% 应为 1.30 Wh)
        val fractionalDelta = mapOf(
            18 to DischargeEnergyIntegrator.BinEnergyDelta(
                binIndex = 18,
                energyWh = 0.65,
                durationMs = 60_000L,
                startSocPercent = 95.0f,
                endSocPercent = 92.5f
            )
        )
        val curveAfterNormalization = SocEnergyCurveLearner.updateCurveWithDeltas(
            initialCurve,
            fractionalDelta,
            2000L,
            incrementSessionCount = false
        )
        val bin18 = curveAfterNormalization.bins[18]
        assertTrue(bin18.hasLearnedData())
        assertEquals(1.30, bin18.learnedEnergyWh!!, 0.001)
        // 增量学习时不递增总会话计数
        assertEquals(0, curveAfterNormalization.totalSampleSessions)
    }

    /**
     * 测试中位数（Median）与绝对中位差（MAD）计算算法的严密性。
     */
    @Test
    fun testMedianAndMadCalculation() {
        val oddValues = listOf(1.10, 1.30, 1.20)
        val medianOdd = SocEnergyCurveLearner.calculateMedian(oddValues)
        assertEquals(1.20, medianOdd!!, 0.0001)

        val evenValues = listOf(1.10, 1.40, 1.20, 1.30)
        val medianEven = SocEnergyCurveLearner.calculateMedian(evenValues)
        // (1.20 + 1.30) / 2 = 1.25
        assertEquals(1.25, medianEven!!, 0.0001)

        val mad = SocEnergyCurveLearner.calculateMad(evenValues, medianEven)
        // 偏差: |1.10-1.25|=0.15, |1.20-1.25|=0.05, |1.30-1.25|=0.05, |1.40-1.25|=0.15
        // 排序: 0.05, 0.05, 0.15, 0.15 -> 中位数 0.10
        assertEquals(0.10, mad!!, 0.0001)
    }
}
