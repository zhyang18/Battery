package com.battery.analysis

import com.battery.analysis.domain.estimator.DischargeEnergyIntegrator
import com.battery.analysis.domain.estimator.RemainingEnergyEstimator
import com.battery.analysis.domain.estimator.SocEnergyCurveLearner
import com.battery.analysis.domain.estimator.model.EnergyEstimateSource
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

    /**
     * 测试混合放电能量估算算法（实测学习区间累加 + 未学习区间标称补偿）。
     * 验证冷启动零假数据保护、部分区间覆盖时的补偿准确性以及全部学习收敛后的精确度。
     */
    @Test
    fun testMixedRemainingEnergyEstimation() {
        val emptyCurve = LearnedSocEnergyCurve.createEmpty()

        // 1. 冷启动测试：没有任何区间学习到数据时，严格返回 null 保持零假数据原则
        val coldStartEstimate = emptyCurve.estimateRemainingEnergyWhMixed(
            currentSocPercent = 65.0f,
            nominalWhPerBin = 0.9625
        )
        assertNull(coldStartEstimate)

        // 2. 部分学习测试：假设仅学习到 bin 0 (0%~5%)，当前电量 10% (目标需要 bin 0 和 bin 1 共 2 个区间)
        val partiallyLearnedCurve = SocEnergyCurveLearner.updateCurveWithDeltas(
            emptyCurve,
            mapOf(0 to DischargeEnergyIntegrator.BinEnergyDelta(0, 1.20, 1000L, 5f, 0f)),
            1000L
        )

        val partialEstimate = partiallyLearnedCurve.estimateRemainingEnergyWhMixed(
            currentSocPercent = 10.0f,
            nominalWhPerBin = 1.0
        )
        assertNotNull(partialEstimate)
        assertEquals(2, partialEstimate!!.totalBinCount)
        assertEquals(1, partialEstimate.learnedBinCount)
        assertEquals(1.20, partialEstimate.learnedWh, 0.001)
        assertEquals(1.00, partialEstimate.nominalCompensatedWh, 0.001)
        // 总估算 = 实测 1.20 + 标称 1.00 = 2.20 Wh
        assertEquals(2.20, partialEstimate.totalWh, 0.001)
        assertFalse(partialEstimate.isFullyLearned)
        assertEquals(0.5f, partialEstimate.learnedCoverage, 0.001f)

        // 3. 完整学习测试：再学习 bin 1 (5%~10%)
        val fullyLearnedCurve = SocEnergyCurveLearner.updateCurveWithDeltas(
            partiallyLearnedCurve,
            mapOf(1 to DischargeEnergyIntegrator.BinEnergyDelta(1, 1.10, 1000L, 10f, 5f)),
            2000L
        )

        val fullEstimate = fullyLearnedCurve.estimateRemainingEnergyWhMixed(
            currentSocPercent = 10.0f,
            nominalWhPerBin = 1.0
        )
        assertNotNull(fullEstimate)
        assertEquals(2, fullEstimate!!.totalBinCount)
        assertEquals(2, fullEstimate.learnedBinCount)
        assertEquals(2.30, fullEstimate.learnedWh, 0.001)
        assertEquals(0.00, fullEstimate.nominalCompensatedWh, 0.001)
        assertEquals(2.30, fullEstimate.totalWh, 0.001)
        assertTrue(fullEstimate.isFullyLearned)
        assertEquals(1.0f, fullEstimate.learnedCoverage, 0.001f)
    }

    /**
     * 测试低 SOC 拐点陡降区（0%~15%，bin 0..2）相邻插值保护禁令。
     * 验证即使双侧均具备实测样本，低 SOC 拐点区依然强制禁止线性插值，自动降级为非线性物理先验模型。
     */
    @Test
    fun testHybridRemainingEnergyLowSocProtection() {
        var curve = LearnedSocEnergyCurve.createEmpty()
        // 学习 bin 0 (0%~5%) 与 bin 2 (10%~15%)，中间 bin 1 (5%~10%) 缺失
        curve = SocEnergyCurveLearner.updateCurveWithDeltas(
            curve,
            mapOf(
                0 to DischargeEnergyIntegrator.BinEnergyDelta(0, 0.90, 1000L, 5f, 0f),
                2 to DischargeEnergyIntegrator.BinEnergyDelta(2, 1.10, 1000L, 15f, 10f)
            ),
            1000L
        )

        val resolvedBins = curve.resolveAllBins(
            effectiveCapacityMah = 5000.0,
            nominalVoltageVolts = 3.85
        )

        val bin1 = resolvedBins[1]
        // 核心验证：bin 1 处于 5%~10%（index < 3），绝不能被判定为 INTERPOLATED！
        assertEquals(EnergyEstimateSource.MODEL_PRIOR, bin1.source)
        assertNull(bin1.measuredEnergyWh)
        assertNotNull(bin1.effectiveEnergyWh)
        assertTrue(bin1.rationale.contains("物理先验模型"))
    }

    /**
     * 测试平台稳定区（SOC >= 15%）在双侧实测支撑下的相邻电压线性插值功能。
     */
    @Test
    fun testHybridRemainingEnergyPlatformInterpolation() {
        var curve = LearnedSocEnergyCurve.createEmpty()
        // 学习 bin 7 (35%~40%，1.20 Wh) 与 bin 9 (45%~50%，1.30 Wh)，bin 8 (40%~45%) 孤立缺失
        curve = SocEnergyCurveLearner.updateCurveWithDeltas(
            curve,
            mapOf(
                7 to DischargeEnergyIntegrator.BinEnergyDelta(7, 1.20, 1000L, 40f, 35f),
                9 to DischargeEnergyIntegrator.BinEnergyDelta(9, 1.30, 1000L, 50f, 45f)
            ),
            1000L
        )

        val resolvedBins = curve.resolveAllBins(
            effectiveCapacityMah = 5000.0,
            nominalVoltageVolts = 3.85
        )

        val bin8 = resolvedBins[8]
        // 核心验证：bin 8 处于 40%~45% 平台区，且双侧有实测，应成功插值！
        assertEquals(EnergyEstimateSource.INTERPOLATED, bin8.source)
        // 插值做功应为 (1.20 + 1.30) / 2 = 1.25 Wh
        assertEquals(1.25, bin8.effectiveEnergyWh!!, 0.001)
        assertEquals(1.25, bin8.modeledEnergyWh!!, 0.001)
        assertNull(bin8.measuredEnergyWh)
    }

    /**
     * 测试首尾单侧边界（如 bin 0 与 bin 19）缺失时严禁盲目外推为插值。
     */
    @Test
    fun testHybridRemainingEnergyBoundaryNoExtrapolation() {
        var curve = LearnedSocEnergyCurve.createEmpty()
        // 仅学习 bin 1 (5%~10%)，bin 0 (0%~5%) 缺失（左侧无相邻区间）
        curve = SocEnergyCurveLearner.updateCurveWithDeltas(
            curve,
            mapOf(1 to DischargeEnergyIntegrator.BinEnergyDelta(1, 1.0, 1000L, 10f, 5f)),
            1000L
        )

        val resolvedBins = curve.resolveAllBins(
            effectiveCapacityMah = 5000.0,
            nominalVoltageVolts = 3.85
        )

        val bin0 = resolvedBins[0]
        // 核心验证：bin 0 缺失无双侧支撑，绝不允许外推为插值，必须为 MODEL_PRIOR
        assertEquals(EnergyEstimateSource.MODEL_PRIOR, bin0.source)
    }

    /**
     * 针对用户提出的 65% SOC 真实场景进行综合验证：
     * 场景说明：
     * - 当前 SOC：65%（需覆盖 0%~65% 共 13 个 bin，索引 0..12）
     * - 已学习 8 个 bin（例如 25%~65%，即 bin 5..12），实测累加能量恰好约为 10.43 Wh
     * - 未学习 5 个 bin（0%~25%，即 bin 0..4）
     * 验证目标：
     * 1. 传统纯实测累加确实出现约 5 Wh 漏计（仅得 10.43 Wh）；
     * 2. 三层混合估算能够通过物理先验模型对缺失的 5 个 bin 精准补全约 5 Wh；
     * 3. 补全后的总剩余能量与预期接近（约 15.43 Wh 左右），彻底修复系统性低估；
     * 4. 分块相关不确定度合理计算且来源完全透明。
     */
    @Test
    fun testHybridEstimationFixesSystematicUnderestimation() {
        var curve = LearnedSocEnergyCurve.createEmpty()

        // 构造已学习的 8 个 bin (bin 5..12，对应 25%~65%)，每个释放约 1.30375 Wh，合计 10.43 Wh
        val learnedDeltas = mutableMapOf<Int, DischargeEnergyIntegrator.BinEnergyDelta>()
        val whPerLearnedBin = 10.43 / 8.0 // 1.30375 Wh
        for (i in 5..12) {
            val minSoc = i * 5f
            val maxSoc = minSoc + 5f
            learnedDeltas[i] = DischargeEnergyIntegrator.BinEnergyDelta(
                binIndex = i,
                energyWh = whPerLearnedBin,
                durationMs = 60_000L,
                startSocPercent = maxSoc,
                endSocPercent = minSoc
            )
        }
        curve = SocEnergyCurveLearner.updateCurveWithDeltas(curve, learnedDeltas, 1000L)

        // 1. 验证旧的纯实测累加确实存在系统性低估（仅 10.43 Wh）
        val rawLearnedOnlyWh = curve.estimateRemainingEnergyWh(65.0f)
        assertNotNull(rawLearnedOnlyWh)
        assertEquals(10.43, rawLearnedOnlyWh!!, 0.01)

        // 2. 使用全新三层混合估算（基准容量假设 5000 mAh，名义电压 3.85V）
        val hybridEstimate = curve.estimateHybridRemainingEnergy(
            currentSocPercent = 65.0f,
            effectiveCapacityMah = 5000.0,
            nominalVoltageVolts = 3.85
        )

        assertNotNull(hybridEstimate)
        assertEquals(10.43, hybridEstimate!!.measuredEnergyWh, 0.01)
        // 5 个缺失区间通过物理先验模型补全（平均每段约 0.9Wh~1.0Wh，合计约 4.5Wh~5.0Wh）
        assertTrue("模型补全应在 4.0 Wh 到 5.5 Wh 之间", hybridEstimate.modeledEnergyWh in 4.0..5.5)

        // 总剩余能量约为 15.0 Wh 左右，成功消除 5 Wh 漏计断层
        val totalWh = hybridEstimate.remainingEnergyWh!!
        assertTrue("总剩余能量应在 14.5 Wh 到 16.0 Wh 之间", totalWh in 14.5..16.0)

        // 验证覆盖率指标：已实测 8/13 ≈ 61.5%，模型补全 5/13 ≈ 38.5%
        assertEquals(8f / 13f, hybridEstimate.measuredSocCoverage, 0.01f)
        assertEquals(5f / 13f, hybridEstimate.modeledSocCoverage, 0.01f)
        assertEquals(0.0f, hybridEstimate.unknownSocCoverage, 0.001f)

        // 验证不确定度传播：具备正数不确定度
        assertNotNull(hybridEstimate.uncertaintyWh)
        assertTrue(hybridEstimate.uncertaintyWh!! > 0.0)

        // 3. 验证通过 RemainingEnergyEstimator 评估时，策略 3 能够输出补全后的真实物理电能
        val estimator = RemainingEnergyEstimator()
        val usableEstimate = estimator.estimateUsableRemainingEnergy(
            batteryLevelPercent = 65.0f,
            effectiveCapacityMah = 5000.0f,
            learnedCurve = curve,
            nominalVoltage = 3.85
        )

        assertNotNull(usableEstimate.usableEnergyWh)
        // 确认没有被 10.43 Wh 截断，而是输出 15.x Wh
        assertTrue(usableEstimate.usableEnergyWh!! > 14.0)
    }
}
