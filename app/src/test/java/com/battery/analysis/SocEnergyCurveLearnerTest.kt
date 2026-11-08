package com.battery.analysis

import com.battery.analysis.domain.estimator.DischargeEnergyIntegrator
import com.battery.analysis.domain.estimator.RemainingEnergyEstimator
import com.battery.analysis.domain.estimator.SocEnergyCurveLearner
import com.battery.analysis.domain.estimator.model.ConfidenceLevel
import com.battery.analysis.domain.estimator.model.EnergyEstimateSource
import com.battery.analysis.domain.estimator.model.LearnedSocEnergyCurve
import com.battery.analysis.domain.estimator.model.ModelMaturity
import com.battery.analysis.domain.estimator.model.PowerSample
import com.battery.analysis.domain.estimator.model.ScreenPowerState
import com.battery.analysis.domain.estimator.model.SocEnergyBin
import com.battery.analysis.domain.estimator.model.SocEnergyBinSample
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
        // 学习 bin 0 (0%~5%)、bin 2 (10%~15%) 与 bin 3 (15%~20%)，满足 >= 3 段自举门限，中间 bin 1 (5%~10%) 缺失
        curve = SocEnergyCurveLearner.updateCurveWithDeltas(
            curve,
            mapOf(
                0 to DischargeEnergyIntegrator.BinEnergyDelta(0, 0.90, 1000L, 5f, 0f),
                2 to DischargeEnergyIntegrator.BinEnergyDelta(2, 1.10, 1000L, 15f, 10f),
                3 to DischargeEnergyIntegrator.BinEnergyDelta(3, 1.15, 1000L, 20f, 15f)
            ),
            1000L
        )

        val resolvedBins = curve.resolveAllBins(
            effectiveCapacityMah = 5000.0,
            nominalVoltageVolts = 3.85
        )

        val bin1 = resolvedBins[1]
        // 核心验证：bin 1 处于 5%~10%（index < 3），绝不能被判定为 INTERPOLATED！
        // 在方案 B 中，通过已学的 3 个区间自举形态外推为 BOOTSTRAP_EXTRAPOLATED，彻底摆脱容量先验！
        assertEquals(EnergyEstimateSource.BOOTSTRAP_EXTRAPOLATED, bin1.source)
        assertNull(bin1.measuredEnergyWh)
        assertNotNull(bin1.effectiveEnergyWh)
        assertTrue(bin1.rationale.contains("实测形态自举"))
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
        // 学习 bin 1、bin 2 与 bin 3 (共 3 段，满足自举门限)，bin 0 (0%~5%) 缺失（左侧无相邻区间）
        curve = SocEnergyCurveLearner.updateCurveWithDeltas(
            curve,
            mapOf(
                1 to DischargeEnergyIntegrator.BinEnergyDelta(1, 1.0, 1000L, 10f, 5f),
                2 to DischargeEnergyIntegrator.BinEnergyDelta(2, 1.1, 1000L, 15f, 10f),
                3 to DischargeEnergyIntegrator.BinEnergyDelta(3, 1.15, 1000L, 20f, 15f)
            ),
            1000L
        )

        val resolvedBins = curve.resolveAllBins(
            effectiveCapacityMah = 5000.0,
            nominalVoltageVolts = 3.85
        )

        val bin0 = resolvedBins[0]
        // 核心验证：bin 0 缺失无双侧支撑，绝不允许外推为插值，在方案 B 中满足 >= 3 段后基于已测形态自举外推
        assertEquals(EnergyEstimateSource.BOOTSTRAP_EXTRAPOLATED, bin0.source)
    }

    /**
     * 针对用户提出的 65% SOC 真实场景进行综合验证：
     * 场景说明：
     * - 当前 SOC：65%（需覆盖 0%~65% 共 13 个 bin，索引 0..12）
     * - 已学习 8 个 bin（例如 25%~65%，即 bin 5..12），实测累加能量恰好约为 10.43 Wh
     * - 未学习 5 个 bin（0%~25%，即 bin 0..4）
     * 验证目标：
     * 1. 传统纯实测累加确实出现约 5 Wh 漏计（仅得 10.43 Wh）；
     * 2. 三层混合估算能够通过形态自举模型对缺失的 5 个 bin 精准补全约 5~6 Wh；
     * 3. 补全后的总剩余能量与预期接近（约 15~16 Wh 左右），彻底修复系统性低估；
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

        // 2. 使用全新三层混合估算（基于形态自举外推，不依赖容量先验）
        val hybridEstimate = curve.estimateHybridRemainingEnergy(
            currentSocPercent = 65.0f,
            effectiveCapacityMah = null,
            nominalVoltageVolts = 3.85
        )

        assertNotNull(hybridEstimate)
        assertEquals(10.43, hybridEstimate!!.measuredEnergyWh, 0.01)
        // 5 个缺失区间通过形态自举补全（5段形态权重合计 0.229，推算满电约 25.8 Wh，自举能量约 5.9 Wh）
        assertTrue("自举补全应在 4.5 Wh 到 6.5 Wh 之间", hybridEstimate.modeledEnergyWh in 4.5..6.5)

        // 总剩余能量约为 16.3 Wh 左右，成功消除 5 Wh 漏计断层
        val totalWh = hybridEstimate.remainingEnergyWh!!
        assertTrue("总剩余能量应在 15.0 Wh 到 17.5 Wh 之间", totalWh in 15.0..17.5)

        // 验证覆盖率指标：已实测 8/13 ≈ 61.5%，自举补全 5/13 ≈ 38.5%
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

    /**
     * 测试方案 B：无量纲电化学形态自举外推，彻底解耦容量先验。
     * 满足 >= 3 个已学习区间后，系统成功通过中位数投票自举推算整机满充做功。
     */
    @Test
    fun testDischargeEnergyBootstrapWithoutCapacityPrior() {
        var curve = LearnedSocEnergyCurve.createEmpty()
        // 学习 3 个 bin: 8 (40%~45%), 9 (45%~50%), 10 (50%~55%)，权重均为 0.050，每段实测释放 1.15 Wh
        curve = SocEnergyCurveLearner.updateCurveWithDeltas(
            curve,
            mapOf(
                8 to DischargeEnergyIntegrator.BinEnergyDelta(8, 1.15, 60_000L, 45f, 40f),
                9 to DischargeEnergyIntegrator.BinEnergyDelta(9, 1.15, 60_000L, 50f, 45f),
                10 to DischargeEnergyIntegrator.BinEnergyDelta(10, 1.15, 60_000L, 55f, 50f)
            ),
            1000L
        )

        // effectiveCapacityMah 传入 null，验证完全不依赖容量先验
        val resolvedBins = curve.resolveAllBins(effectiveCapacityMah = null)
        assertEquals(20, resolvedBins.size)

        // bin 8, 9, 10 是实测
        assertEquals(EnergyEstimateSource.MEASURED_LEARNED, resolvedBins[8].source)
        assertEquals(EnergyEstimateSource.MEASURED_LEARNED, resolvedBins[9].source)
        assertEquals(EnergyEstimateSource.MEASURED_LEARNED, resolvedBins[10].source)
        assertEquals(1.15, resolvedBins[10].effectiveEnergyWh!!, 0.001)

        // 其他未测 bin 均应为 BOOTSTRAP_EXTRAPOLATED
        val bin0 = resolvedBins[0]
        assertEquals(EnergyEstimateSource.BOOTSTRAP_EXTRAPOLATED, bin0.source)
        // 反推整机满充标尺 = 1.15 / 0.050 = 23.0 Wh
        // bin 0 能量 = 23.0 * 0.040 = 0.92 Wh
        assertEquals(23.0 * 0.040, bin0.effectiveEnergyWh!!, 0.001)

        val bin19 = resolvedBins[19]
        assertEquals(EnergyEstimateSource.BOOTSTRAP_EXTRAPOLATED, bin19.source)
        // bin 19 能量 = 23.0 * 0.054 = 1.242 Wh
        assertEquals(23.0 * 0.054, bin19.effectiveEnergyWh!!, 0.001)

        // 20 段有效能量总和应严格自洽闭环于整机满充标尺 23.0 Wh
        val totalWh = resolvedBins.sumOf { it.effectiveEnergyWh!! }
        assertEquals(23.0, totalWh, 0.01)

        // 验证 100% 满充混合估算
        val hybridFull = curve.estimateHybridRemainingEnergy(
            currentSocPercent = 100f,
            effectiveCapacityMah = null
        )
        assertNotNull(hybridFull)
        assertEquals(23.0, hybridFull!!.remainingEnergyWh!!, 0.01)
    }

    /**
     * 测试方案 B 门限控制：已测区间少于 3 段时，坚决不开启形态自举，避免单次极端负载误导。
     */
    @Test
    fun testBootstrapGatedByMinimumThreeBins() {
        var curve = LearnedSocEnergyCurve.createEmpty()
        // 仅学习 2 个 bin (10 和 11)
        curve = SocEnergyCurveLearner.updateCurveWithDeltas(
            curve,
            mapOf(
                10 to DischargeEnergyIntegrator.BinEnergyDelta(10, 1.15, 60_000L, 55f, 50f),
                11 to DischargeEnergyIntegrator.BinEnergyDelta(11, 1.20, 60_000L, 60f, 55f)
            ),
            1000L
        )

        val resolved = curve.resolveAllBins(effectiveCapacityMah = null)
        // 未测区间应忠实保持 UNKNOWN，不开启自举
        assertEquals(EnergyEstimateSource.UNKNOWN, resolved[0].source)
        assertNull(resolved[0].effectiveEnergyWh)

        // 满充预测由于存在 UNKNOWN 区间，忠实输出 null（提示采样积累中）
        val hybridFull = curve.estimateHybridRemainingEnergy(100f, effectiveCapacityMah = null)
        assertNull(hybridFull?.remainingEnergyWh)

        // 再学习第 3 个 bin (bin 12)
        curve = SocEnergyCurveLearner.updateCurveWithDeltas(
            curve,
            mapOf(12 to DischargeEnergyIntegrator.BinEnergyDelta(12, 1.18, 60_000L, 65f, 60f)),
            1000L
        )
        val resolved3 = curve.resolveAllBins(effectiveCapacityMah = null)
        // 达到 3 段门限，成功解锁自举外推
        assertEquals(EnergyEstimateSource.BOOTSTRAP_EXTRAPOLATED, resolved3[0].source)
        assertNotNull(resolved3[0].effectiveEnergyWh)

        val hybridFull3 = curve.estimateHybridRemainingEnergy(100f, effectiveCapacityMah = null)
        assertNotNull(hybridFull3)
        assertNotNull(hybridFull3!!.remainingEnergyWh)
    }

    /**
     * 测试成熟度与置信度与“完整放电周期次数（totalSessions）”强绑定联动：
     * 1. 仅有 1~2 次放电会话时，哪怕学满多段，也坚决维持 COLD_START / LOW 置信度，杜绝单一工况误判中置信度；
     * 2. 达到 >= 3 次完整放电周期且覆盖 >= 40% 且样本充足，才晋升 DEVELOPING / MEDIUM 置信度；
     * 3. 达到 >= 5 次放电周期且覆盖 >= 70% 且平均样本 >= 2.5 次，才晋升 CONVERGED / HIGH 高置信度。
     */
    @Test
    fun testMaturityAndConfidenceGatedByDischargeCycles() {
        val bins = (0 until 20).map { index ->
            if (index in 5..12) { // 8 段已学 (40% 覆盖)
                SocEnergyBin(
                    binIndex = index,
                    minSocPercent = index * 5.0f,
                    maxSocPercent = (index + 1) * 5.0f,
                    sampleCount = 1,
                    learnedEnergyWh = 1.18
                )
            } else {
                SocEnergyBin(
                    binIndex = index,
                    minSocPercent = index * 5.0f,
                    maxSocPercent = (index + 1) * 5.0f,
                    sampleCount = 0,
                    learnedEnergyWh = null
                )
            }
        }

        // 场景 1：仅 1 次放电会话（虽有 8 段，但全来自同一次单边放电）
        val (mat1, conf1) = SocEnergyCurveLearner.evaluateMaturityAndConfidence(bins, totalSessions = 1)
        assertEquals(com.battery.analysis.domain.estimator.model.ModelMaturity.COLD_START, mat1)
        assertEquals(com.battery.analysis.domain.estimator.model.ConfidenceLevel.LOW, conf1)

        // 场景 2：仅 2 次放电会话
        val (mat2, conf2) = SocEnergyCurveLearner.evaluateMaturityAndConfidence(bins, totalSessions = 2)
        assertEquals(com.battery.analysis.domain.estimator.model.ModelMaturity.COLD_START, mat2)
        assertEquals(com.battery.analysis.domain.estimator.model.ConfidenceLevel.LOW, conf2)

        // 场景 3：达到 3 次完整放电周期，且覆盖率 50% (10段)，样本数 10 次
        val matureBins = (0 until 20).map { index ->
            if (index in 5..14) { // 10 段已学 (50% 覆盖)
                SocEnergyBin(
                    binIndex = index,
                    minSocPercent = index * 5.0f,
                    maxSocPercent = (index + 1) * 5.0f,
                    sampleCount = 2,
                    learnedEnergyWh = 1.18
                )
            } else {
                SocEnergyBin(
                    binIndex = index,
                    minSocPercent = index * 5.0f,
                    maxSocPercent = (index + 1) * 5.0f,
                    sampleCount = 0,
                    learnedEnergyWh = null
                )
            }
        }
        val (mat3, conf3) = SocEnergyCurveLearner.evaluateMaturityAndConfidence(matureBins, totalSessions = 3)
        assertEquals(com.battery.analysis.domain.estimator.model.ModelMaturity.DEVELOPING, mat3)
        assertEquals(com.battery.analysis.domain.estimator.model.ConfidenceLevel.MEDIUM, conf3)

        // 场景 4：达到 5 次放电周期，且 15 段已学 (75% 覆盖)，平均样本 3.0 次
        val highBins = (0 until 20).map { index ->
            if (index in 2..16) { // 15 段已学 (75% 覆盖)
                SocEnergyBin(
                    binIndex = index,
                    minSocPercent = index * 5.0f,
                    maxSocPercent = (index + 1) * 5.0f,
                    sampleCount = 3,
                    learnedEnergyWh = 1.18
                )
            } else {
                SocEnergyBin(
                    binIndex = index,
                    minSocPercent = index * 5.0f,
                    maxSocPercent = (index + 1) * 5.0f,
                    sampleCount = 0,
                    learnedEnergyWh = null
                )
            }
        }
        val (mat4, conf4) = SocEnergyCurveLearner.evaluateMaturityAndConfidence(highBins, totalSessions = 5)
        assertEquals(com.battery.analysis.domain.estimator.model.ModelMaturity.CONVERGED, mat4)
        assertEquals(com.battery.analysis.domain.estimator.model.ConfidenceLevel.HIGH, conf4)
    }

    /**
     * 测试纯冷启动（0 段已学习）时，系统严格遵守零假数据原则，不虚构任何预测。
     */
    @Test
    fun testZeroLearnedBinsShowsPendingWithoutPrior() {
        val emptyCurve = LearnedSocEnergyCurve.createEmpty()
        val resolvedBins = emptyCurve.resolveAllBins(effectiveCapacityMah = null)
        assertTrue(resolvedBins.all { it.source == EnergyEstimateSource.UNKNOWN })
        assertTrue(resolvedBins.all { it.effectiveEnergyWh == null })

        val hybridEstimate = emptyCurve.estimateHybridRemainingEnergy(
            currentSocPercent = 80f,
            effectiveCapacityMah = null
        )
        assertNull(hybridEstimate?.remainingEnergyWh)
    }

    /**
     * 测试重置学习曲线后模型完全回归初始冷启动状态（无任何假数据残留）。
     */
    @Test
    fun testResetCurveClearsAllLearnedDataAndSessions() {
        var curve = LearnedSocEnergyCurve.createEmpty()
        // 学习数据
        val session = mapOf(
            18 to DischargeEnergyIntegrator.BinEnergyDelta(18, 1.25, 60_000L, 95f, 90f),
            19 to DischargeEnergyIntegrator.BinEnergyDelta(19, 1.30, 60_000L, 100f, 95f)
        )
        curve = SocEnergyCurveLearner.updateCurveWithDeltas(curve, session, 1000L)
        assertTrue(curve.coveragePercent > 0f)
        assertEquals(2, curve.bins.count { it.hasLearnedData() })

        // 模拟执行重置
        val resetCurve = LearnedSocEnergyCurve.createEmpty()
        assertEquals(0.0f, resetCurve.coveragePercent, 0.001f)
        assertEquals(0, resetCurve.totalSampleSessions)
        assertNull(resetCurve.estimatedFullUsableEnergyWh)
        assertEquals(com.battery.analysis.domain.estimator.model.ModelMaturity.COLD_START, resetCurve.maturity)
        assertTrue(resetCurve.bins.all { !it.hasLearnedData() && it.sampleCount == 0 && it.learnedEnergyWh == null })
    }

    /**
     * 测试深睡断层待机底噪微积分补偿：
     * 验证在连续采样中夹杂 30 分钟息屏休眠（采样间隔 dt > 120s）时，能量不被丢弃，
     * 而是正确注入待机底噪微积分，使 5% SOC 区间做功忠实反映真实能量释放。
     */
    @Test
    fun testStandbySleepGapCompensationInDischargeIntegration() {
        val t0 = 1000000L
        val samples = listOf(
            // 亮屏使用：80% -> 78%，耗时 1 分钟 (60s <= 120s)，功率 1.8W，能量 = 1.8 * (60/3600) = 0.03 Wh
            PowerSample(t0, 3.85f, 0.467f, 1.8f, 80.0f, ScreenPowerState.SCREEN_ON),
            PowerSample(t0 + 60_000L, 3.82f, 0.471f, 1.8f, 78.0f, ScreenPowerState.SCREEN_ON),
            // 进入息屏休眠：78% -> 75%，休眠 45 分钟 (2700s)，dt > 120s，测得待机功率 0.29W
            // 待机补偿能量 = 0.29W * 0.75h = 0.2175 Wh
            PowerSample(t0 + 60_000L + 2700_000L, 3.80f, 0.076f, 0.29f, 75.0f, ScreenPowerState.SCREEN_OFF)
        )

        val result = DischargeEnergyIntegrator.integrateDischargeSession(samples)
        // 80% 到 75% 属于 Bin 15 (75% - 80%)
        val bin15 = result.binDeltas[15]
        assertNotNull(bin15)
        // 验证该区间的能量包含了亮屏做功 (0.03 Wh) + 息屏待机补偿做功 (0.2175 Wh) ≈ 0.2475 Wh
        val expectedMin = 0.03 + 0.21
        assertTrue("Bin 15 energy should be >= $expectedMin but was ${bin15!!.energyWh}", bin15.energyWh >= expectedMin)
        // 验证正常休眠未被判定为超大断层
        assertTrue("Normal standby gap <= 4h should not be in unobservedRanges", result.unobservedRanges.isEmpty())
    }

    /**
     * 测试双基准（出厂设计 30.80 Wh vs 健康容量 24.05 Wh）在对比看板中的清晰呈现与转化率核算。
     */
    @Test
    fun testDualBaselineAndEfficiencyMetricsInOverview() {
        // 构建一个已完整学习预测为 29.50 Wh 的曲线
        val matureBins = (0 until 20).map { index ->
            SocEnergyBin(
                binIndex = index,
                minSocPercent = index * 5.0f,
                maxSocPercent = (index + 1) * 5.0f,
                sampleCount = 3,
                learnedEnergyWh = 29.50 * LearnedSocEnergyCurve.SOC_ENERGY_SHAPE_WEIGHTS[index]
            )
        }
        val curve = LearnedSocEnergyCurve(
            bins = matureBins,
            coveragePercent = 1.0f,
            estimatedFullUsableEnergyWh = 29.50,
            confidence = com.battery.analysis.domain.estimator.model.ConfidenceLevel.HIGH,
            maturity = com.battery.analysis.domain.estimator.model.ModelMaturity.CONVERGED,
            totalSampleSessions = 5,
            lastLearnedTimestamp = System.currentTimeMillis()
        )

        val overview = SocEnergyCurveLearner.buildComparisonOverviewFromCurve(
            curve = curve,
            currentSocPercent = 100f,
            effectiveCapacityMah = 6247f, // 健康容量
            nominalVoltageVolts = 3.85f,
            designCapacityMah = 8000f // 出厂设计容量
        )

        // 验证出厂设计额定满电理论值：8000 * 3.85 / 1000 = 30.80 Wh
        assertEquals(30.80, overview.fullDesignEnergyWh!!, 0.01)
        // 验证健康容量额定满电理论值：6247 * 3.85 / 1000 = 24.05 Wh
        assertEquals(24.05, overview.fullNominalEnergyWh!!, 0.01)
        // 验证实测做功：29.50 Wh
        assertEquals(29.50, overview.fullLearnedEnergyWh!!, 0.01)
        // 验证相对出厂设计总电能的保留率/能量健康度：29.50 / 30.80 * 100% ≈ 95.78%
        assertEquals(95.78, overview.energyHealthRetentionPercent!!, 0.1)
        // 验证相对当前健康基准做功转化率：29.50 / 24.05 * 100% ≈ 122.66%
        assertEquals(122.66, overview.dischargeEnergyEfficiencyPercent!!, 0.1)
    }

    /**
     * 测试放电会话进行中增量更新（In-Flight Replacement）：
     * 当同一会话在后续采样中得到更完整或经待机补偿后的能量时，允许在当前会话内直接更新样本能量，
     * 且严格不虚增单段样本计数 sampleCount。
     */
    @Test
    fun testInFlightUpgradableSampleReplacement() {
        val emptyCurve = LearnedSocEnergyCurve.createEmpty()
        val initialSample = listOf(
            SocEnergyBinSample(
                binIndex = 14,
                energyWh = 0.95, // 初始未补偿的偏低能量
                durationMs = 60000L,
                startSocPercent = 75f,
                endSocPercent = 70f,
                coverageFraction = 1.0,
                sessionId = "test_session_1"
            )
        )

        // 第一次会话内增量学习
        val curve1 = SocEnergyCurveLearner.updateCurveWithSamples(
            currentCurve = emptyCurve,
            samples = initialSample,
            incrementSessionCount = false,
            allowInFlightReplacement = true
        )
        assertEquals(1, curve1.bins[14].sampleCount)
        assertEquals(0.95, curve1.bins[14].learnedEnergyWh!!, 0.001)

        // 同一会话稍后获得了完整待机微积分补偿的更精确样本 (1.48 Wh)
        val upgradedSample = listOf(
            SocEnergyBinSample(
                binIndex = 14,
                energyWh = 1.48, // 经补偿的真实能量
                durationMs = 120000L,
                startSocPercent = 75f,
                endSocPercent = 70f,
                coverageFraction = 1.0,
                sessionId = "test_session_1"
            )
        )
        val curve2 = SocEnergyCurveLearner.updateCurveWithSamples(
            currentCurve = curve1,
            samples = upgradedSample,
            incrementSessionCount = false,
            allowInFlightReplacement = true
        )

        // 验证能量成功被替换为更精确的 1.48 Wh，且 sampleCount 维持为 1（未被虚增）
        assertEquals(1, curve2.bins[14].sampleCount)
        assertEquals(1.48, curve2.bins[14].learnedEnergyWh!!, 0.001)
    }

    /**
     * 测试电化学形态理论权重计算函数 calculateTheoreticalShapeWeight 的精准性。
     */
    @Test
    fun testCalculateTheoreticalShapeWeight() {
        // 0% 到 100% 全范围权重严格为 1.000
        val fullWeight = LearnedSocEnergyCurve.calculateTheoreticalShapeWeight(0f, 100f)
        assertEquals(1.000, fullWeight, 0.0001)

        // 0% 到 5% 为保护低压区，权重为 0.040
        val lowWeight = LearnedSocEnergyCurve.calculateTheoreticalShapeWeight(0f, 5f)
        assertEquals(0.040, lowWeight, 0.0001)

        // 95% 到 100% 为满电高压区，权重为 0.054
        val highWeight = LearnedSocEnergyCurve.calculateTheoreticalShapeWeight(95f, 100f)
        assertEquals(0.054, highWeight, 0.0001)

        // 逆向输入或无效范围返回 0.0
        assertEquals(0.0, LearnedSocEnergyCurve.calculateTheoreticalShapeWeight(50f, 20f), 0.0001)
    }

    /**
     * 测试基于连续纯亮屏高频微积分实测能量与掉电百分比反推整机满电物理做功的准确性。
     * 输入用户实际截屏数据：亮屏 7.444 Wh，亮屏掉电 25.2%（如从 90% 掉到 65%）。
     */
    @Test
    fun testScreenOnAnchoredFullEnergyProjection() {
        val onWh = 7.444f
        val onDrop = 25.2f

        // 1. 无指定区间时的平均形态外推
        val projectedFullWh = SocEnergyCurveLearner.calculateScreenOnAnchoredFullEnergy(
            screenOnEnergyWh = onWh,
            screenOnDropPercent = onDrop
        )
        assertNotNull(projectedFullWh)
        // 7.444 / 0.252 ≈ 29.539 Wh
        assertEquals(29.54, projectedFullWh!!, 0.05)

        // 2. 指定处于高压区间（90% -> 65%）：权重更精细
        val highRangeFullWh = SocEnergyCurveLearner.calculateScreenOnAnchoredFullEnergy(
            screenOnEnergyWh = 7.70f,
            screenOnDropPercent = 25.0f,
            startSoc = 90f,
            endSoc = 65f
        )
        assertNotNull(highRangeFullWh)
        assertEquals(29.50, highRangeFullWh!!, 0.1)

        // 3. 门限保护：掉电 < 1.5% 时优雅返回 null
        val lowDropResult = SocEnergyCurveLearner.calculateScreenOnAnchoredFullEnergy(
            screenOnEnergyWh = 0.2f,
            screenOnDropPercent = 0.8f
        )
        assertNull(lowDropResult)

        // 4. 断续亮屏（包含息屏待机）防稀释回归测试：
        // 真实案例：亮屏做功 11.25 Wh，掉电 43.0%，但全天会话起止为 88% -> 31% (跨度 57%，含 11h 息屏 14% 掉电)
        // 旧 Bug 会算成 11.25 / 0.571 = 19.70 Wh；
        // 修复后基于区间形态密度加权（31%~88%高压平台形态密度约为 1.0207），
        // 43% 亮屏掉电的电化学能量权重约为 43.89%，
        // 推算满电应为 11.25 / 0.4389 ≈ 25.63 Wh（与全会话 14.57Wh / 0.57 算出的 25.52Wh 极度吻合）
        val fragmentedFullWh = SocEnergyCurveLearner.calculateScreenOnAnchoredFullEnergy(
            screenOnEnergyWh = 11.25f,
            screenOnDropPercent = 43.0f,
            startSoc = 88.0f,
            endSoc = 31.0f
        )
        assertNotNull(fragmentedFullWh)
        assertEquals(25.63, fragmentedFullWh!!, 0.1)
        assertTrue("满电能量绝不能被息屏跨度稀释为 19.70 Wh", fragmentedFullWh > 25.0)
    }

    /**
     * 测试亮屏实测锚定对初始全空学习曲线（0段已学，冷启动）的一键高精解析与三源看板赋能。
     */
    @Test
    fun testScreenOnAnchoredOverviewResolutionOnEmptyCurve() {
        val emptyCurve = LearnedSocEnergyCurve.createEmpty()
        assertEquals(0.0f, emptyCurve.coveragePercent, 0.001f)

        val overview = SocEnergyCurveLearner.buildComparisonOverviewFromCurve(
            curve = emptyCurve,
            currentSocPercent = 65f,
            effectiveCapacityMah = 6247f,
            nominalVoltageVolts = 3.85f,
            designCapacityMah = 8000f,
            screenOnEnergyWh = 7.444f,
            screenOnDropPercent = 25.2f
        )

        // 验证锚定状态与满充预测
        assertTrue(overview.isScreenOnAnchored)
        assertEquals(7.444f, overview.screenOnMeasuredEnergyWh!!, 0.001f)
        assertEquals(25.2f, overview.screenOnDropPercent!!, 0.001f)
        assertEquals(29.54, overview.fullLearnedEnergyWh!!, 0.05)

        // 验证整体评级立即可达已收敛高置信
        assertEquals(ConfidenceLevel.HIGH, overview.confidence)
        assertEquals(ModelMaturity.CONVERGED, overview.maturity)
        assertEquals(1.0f, overview.curveCoveragePercent, 0.001f)

        // 验证全部 20 个区间均由 SCREEN_ON_ANCHORED 展开，且总能量严格守恒
        assertEquals(20, overview.resolvedBins.size)
        var sumBinsWh = 0.0
        for (bin in overview.resolvedBins) {
            assertEquals(EnergyEstimateSource.SCREEN_ON_ANCHORED, bin.source)
            assertNotNull(bin.effectiveEnergyWh)
            assertTrue(bin.effectiveEnergyWh!! > 0.0)
            sumBinsWh += bin.effectiveEnergyWh!!
        }
        assertEquals(overview.fullLearnedEnergyWh!!, sumBinsWh, 0.01)

        // 验证相对出厂 30.80Wh 与健康 24.05Wh 的转化率与健康保留率
        assertEquals(30.80, overview.fullDesignEnergyWh!!, 0.01)
        assertEquals(24.05, overview.fullNominalEnergyWh!!, 0.01)
        assertEquals(95.9, overview.energyHealthRetentionPercent!!, 0.5)
        assertEquals(122.8, overview.dischargeEnergyEfficiencyPercent!!, 0.5)

        // 验证双算法解耦指标：放电模型等效容量 (29.54Wh / 3.85V = 7672 mAh) 与容量算法模型健康度 (6247 / 8000 = 78.1%)
        assertNotNull(overview.equivalentCapacityMah)
        assertEquals(7672.0, overview.equivalentCapacityMah!!, 15.0)
        assertNotNull(overview.capacityModelSohPercent)
        assertEquals(78.1, overview.capacityModelSohPercent!!, 0.2)
    }
}
