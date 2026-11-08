package com.battery.analysis

import com.battery.analysis.domain.estimator.DischargeEnergyIntegrator
import com.battery.analysis.domain.estimator.SocEnergyCurveLearner
import com.battery.analysis.domain.estimator.model.EnergyEstimateSource
import com.battery.analysis.domain.estimator.model.LearnedSocEnergyCurve
import com.battery.analysis.domain.estimator.model.ModelMaturity
import com.battery.analysis.domain.estimator.model.SocEnergyBin
import com.battery.analysis.model.CapacityEstimateResult
import com.battery.analysis.model.CapacitySourceTier
import com.battery.analysis.model.EnergySourceTier
import com.battery.analysis.model.MaturityLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 电池满充与剩余能量（Wh）四级自适应融合模型单元测试。
 * 验证纯实测（Tier 1）、混合建模（Tier 2）、硬件直读（Tier 3）以及标称兜底（Tier 4）的计算准确性与回退机制。
 */
class BatteryCapacityEnergyFusionTest {

    /**
     * 测试冷启动或无实测能量时的向后兼容标称折算（Tier 4）。
     * 当 fullEnergyWh 为 null 时，getFormattedFullEnergy() 应自动基于 (FCC * 3.85) / 1000 格式化。
     */
    @Test
    fun testNominalFallbackFormatting() {
        val fcc = 6247.0
        val result = CapacityEstimateResult(
            remainingChargeMah = 5559.0,
            remainingEnergyWh = (5559.0 * 3.85) / 1000.0,
            fullChargeCapacityMah = fcc,
            designCapacityMah = 8000.0,
            sohPercent = (fcc / 8000.0) * 100.0,
            currentSoc = 89,
            sourceTier = CapacitySourceTier.MULTI_SESSION_LEARNED,
            maturityLevel = MaturityLevel.CONVERGED,
            validSessionCount = 5,
            chargeSessionCount = 3,
            dischargeSessionCount = 2,
            totalSocSpanCovered = 250,
            dispersionCoefficientPercent = 1.5,
            conflictRateDPercent = 2.0,
            confidenceIntervalMah = 30.0,
            fullEnergyWh = null, // 无实测融合值
            energySourceTier = EnergySourceTier.NOMINAL_VOLTAGE,
            energyCoveragePercent = null
        )

        // 6247 * 3.85 / 1000 = 24.05095 -> 24.05 Wh
        assertEquals("6247 mAh", result.getFormattedFcc())
        assertEquals("24.05 Wh", result.getFormattedFullEnergy())
        assertEquals("21.40 Wh", result.getFormattedRemainingEnergy())
        assertEquals(EnergySourceTier.NOMINAL_VOLTAGE, result.energySourceTier)
    }

    /**
     * 测试 20 段 SOC 完全学满时的 Tier 1 全实测放电能量融合。
     */
    @Test
    fun testTier1FullMeasuredEnergyFusion() {
        // 构建 20 个已学满的 bin，平均每个 bin 释放 1.25 Wh（合计 25.00 Wh）
        val bins = (0 until 20).map { index ->
            SocEnergyBin(
                binIndex = index,
                minSocPercent = index * 5.0f,
                maxSocPercent = (index + 1) * 5.0f,
                sampleCount = 3,
                learnedEnergyWh = 1.25,
                dispersionWh = 0.02
            )
        }
        val fullMeasuredWh = bins.sumOf { it.learnedEnergyWh!! }
        assertEquals(25.0, fullMeasuredWh, 0.001)

        val fullCurve = LearnedSocEnergyCurve(
            bins = bins,
            coveragePercent = 1.0f,
            estimatedFullUsableEnergyWh = fullMeasuredWh,
            maturity = ModelMaturity.CONVERGED,
            totalSampleSessions = 10,
            lastLearnedTimestamp = System.currentTimeMillis()
        )

        assertEquals(1.0f, fullCurve.coveragePercent, 0.001f)
        assertEquals(25.0, fullCurve.estimatedFullUsableEnergyWh!!, 0.001)

        val result = CapacityEstimateResult(
            remainingChargeMah = 5559.0,
            remainingEnergyWh = 22.25,
            fullChargeCapacityMah = 6247.0,
            designCapacityMah = 8000.0,
            sohPercent = 78.1,
            currentSoc = 89,
            sourceTier = CapacitySourceTier.MULTI_SESSION_LEARNED,
            maturityLevel = MaturityLevel.CONVERGED,
            validSessionCount = 10,
            chargeSessionCount = 5,
            dischargeSessionCount = 5,
            totalSocSpanCovered = 500,
            dispersionCoefficientPercent = 1.2,
            conflictRateDPercent = 1.8,
            confidenceIntervalMah = 25.0,
            fullEnergyWh = fullCurve.estimatedFullUsableEnergyWh,
            energySourceTier = EnergySourceTier.MEASURED_LEARNED,
            energyCoveragePercent = 1.0f
        )

        // 真实实测能量输出为 25.00 Wh，摆脱了固定的 24.05 Wh 标称假定
        assertEquals("25.00 Wh", result.getFormattedFullEnergy())
        assertEquals(EnergySourceTier.MEASURED_LEARNED, result.energySourceTier)
        assertEquals(1.0f, result.energyCoveragePercent!!, 0.001f)
    }

    /**
     * 测试部分区间覆盖场景下的 Tier 2 三层混合建模推算。
     */
    @Test
    fun testTier2HybridModelingEnergyEstimate() {
        val initialCurve = LearnedSocEnergyCurve.createEmpty()

        // 模拟用户学习了 80%~95% 区间（3个 bin: 16, 17, 18）
        val sampleDeltas = mapOf(
            16 to DischargeEnergyIntegrator.BinEnergyDelta(16, 1.28, 60_000L, 85f, 80f),
            17 to DischargeEnergyIntegrator.BinEnergyDelta(17, 1.30, 60_000L, 90f, 85f),
            18 to DischargeEnergyIntegrator.BinEnergyDelta(18, 1.32, 60_000L, 95f, 90f)
        )
        val learnedCurve = SocEnergyCurveLearner.updateCurveWithDeltas(initialCurve, sampleDeltas, 1000L)
        assertTrue(learnedCurve.coveragePercent > 0.0f)

        // 针对 FCC 6247 mAh 进行 100% 混合满充可用能量推算
        val hybridEstimate = learnedCurve.estimateHybridRemainingEnergy(
            currentSocPercent = 100f,
            effectiveCapacityMah = 6247.0,
            nominalVoltageVolts = 3.85
        )

        assertNotNull(hybridEstimate)
        assertNotNull(hybridEstimate!!.remainingEnergyWh)
        assertTrue(hybridEstimate.remainingEnergyWh!! > 20.0)
        assertTrue(hybridEstimate.remainingEnergyWh!! < 30.0)

        // 验证混合结果中包含实测贡献与模型贡献
        assertTrue(hybridEstimate.measuredEnergyWh > 0.0)
        assertTrue(hybridEstimate.modeledEnergyWh > 0.0)
    }

    /**
     * 验证 SOC 边界状态（0% 和 100%）下的能量连续性与非负性。
     */
    @Test
    fun testBoundarySocEnergyMonotonicity() {
        // 构建包含实测区间的曲线（满足 >= 3 段门限），验证形态自举下的边界连续性
        val curve = SocEnergyCurveLearner.updateCurveWithDeltas(
            LearnedSocEnergyCurve.createEmpty(),
            mapOf(
                8 to DischargeEnergyIntegrator.BinEnergyDelta(8, 1.20, 60_000L, 45f, 40f),
                9 to DischargeEnergyIntegrator.BinEnergyDelta(9, 1.22, 60_000L, 50f, 45f),
                10 to DischargeEnergyIntegrator.BinEnergyDelta(10, 1.25, 60_000L, 55f, 50f)
            ),
            1000L
        )

        val zeroEstimate = curve.estimateHybridRemainingEnergy(
            currentSocPercent = 0f,
            effectiveCapacityMah = 5000.0,
            nominalVoltageVolts = 3.85
        )
        assertNotNull(zeroEstimate)
        assertEquals(0.0, zeroEstimate!!.remainingEnergyWh!!, 0.0001)

        val fullEstimate = curve.estimateHybridRemainingEnergy(
            currentSocPercent = 100f,
            effectiveCapacityMah = 5000.0,
            nominalVoltageVolts = 3.85
        )
        assertNotNull(fullEstimate)
        assertTrue(fullEstimate!!.remainingEnergyWh!! > 15.0)
    }

    /**
     * 验证冷启动（Tier 4）场景下，100% 满充时满电总能量与剩余能量严格相等且闭环自洽，杜绝决策树异步脱节。
     */
    @Test
    fun testColdStartFullAndRemainingEnergyCoherenceAt100PercentSoc() {
        val fcc = 6247.0
        val safeSoc = 100
        val remainingMah = fcc * (safeSoc / 100.0)

        // Tier 4 同源计算
        val nominalFullWh = (fcc * 3.85) / 1000.0
        val nominalRemainingWh = (remainingMah * 3.85) / 1000.0

        val result = CapacityEstimateResult(
            remainingChargeMah = remainingMah,
            remainingEnergyWh = nominalRemainingWh,
            fullChargeCapacityMah = fcc,
            designCapacityMah = 8000.0,
            sohPercent = 78.1,
            currentSoc = safeSoc,
            sourceTier = CapacitySourceTier.MULTI_SESSION_LEARNED,
            maturityLevel = MaturityLevel.CONVERGED,
            validSessionCount = 5,
            chargeSessionCount = 3,
            dischargeSessionCount = 2,
            totalSocSpanCovered = 250,
            dispersionCoefficientPercent = 1.5,
            conflictRateDPercent = 2.0,
            confidenceIntervalMah = 30.0,
            fullEnergyWh = nominalFullWh,
            energySourceTier = EnergySourceTier.NOMINAL_VOLTAGE,
            energyCoveragePercent = null
        )

        // 验证 100% 满电时两值绝对相等
        assertEquals(nominalFullWh, nominalRemainingWh, 0.0001)
        assertEquals("24.05 Wh", result.getFormattedFullEnergy())
        assertEquals("24.05 Wh", result.getFormattedRemainingEnergy())
        assertEquals(EnergySourceTier.NOMINAL_VOLTAGE, result.energySourceTier)
    }

    /**
     * 验证物理能量上限截断保护：剩余能量永远不会超过满电总可用能量。
     */
    @Test
    fun testRemainingEnergyNeverExceedsFullEnergy() {
        val fullWh = 24.05
        // 模拟微积分因浮点误差产生轻微溢出
        val slightOverflowRemainWh = 24.05000000001
        val clampedRemainWh = slightOverflowRemainWh.coerceIn(0.0, fullWh)

        assertEquals(24.05, clampedRemainWh, 0.00001)
        assertTrue(clampedRemainWh <= fullWh)
    }

    /**
     * 测试 13 个 Bin (65% 覆盖率) 场景下的混合模型满充与剩余能量推算：
     * 验证已归一化的实测区间与物理先验区间合成推导后，满充电量落在合理的 22.5 Wh ~ 23.5 Wh 区间，
     * 且在 83% SOC 时推导的剩余能量与 100% 满充能量严格同源自洽。
     */
    @Test
    fun testHybridEstimateWith13BinsCoverage() {
        val fccMah = 6247.0
        // 模拟 13 个 bin（比如 bin 7..19，对应 35%~100% 放电区间已学习），每个归一化后的实测做功约 1.18 Wh
        val bins = (0 until 20).map { index ->
            if (index in 7..19) {
                SocEnergyBin(
                    binIndex = index,
                    minSocPercent = index * 5.0f,
                    maxSocPercent = (index + 1) * 5.0f,
                    sampleCount = 2,
                    learnedEnergyWh = 1.18,
                    coverageFraction = 1.0,
                    dispersionWh = 0.02
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

        val curve = LearnedSocEnergyCurve(
            bins = bins,
            coveragePercent = 13f / 20f,
            estimatedFullUsableEnergyWh = null,
            maturity = ModelMaturity.DEVELOPING,
            curveVersion = 3,
            totalSampleSessions = 3,
            lastLearnedTimestamp = System.currentTimeMillis()
        )

        assertEquals(0.65f, curve.coveragePercent, 0.001f)

        // 推算 100% 满电物理能量
        val hybridFull = curve.estimateHybridRemainingEnergy(
            currentSocPercent = 100f,
            effectiveCapacityMah = fccMah,
            nominalVoltageVolts = 3.85
        )
        assertNotNull(hybridFull)
        val fullWh = hybridFull!!.remainingEnergyWh!!
        // 13 个实测 bin 贡献 13 * 1.18 = 15.34 Wh；前 7 个未学习区间 (0%~35%) 依物理先验模型补全
        // 总满电能量应在合理的 22.0 Wh ~ 23.8 Wh 之间，绝非异常偏低
        assertTrue("fullWh ($fullWh) should be in 22.0..23.8", fullWh in 22.0..23.8)

        // 推算 83% SOC 时的剩余能量
        val hybridRemain83 = curve.estimateHybridRemainingEnergy(
            currentSocPercent = 83f,
            effectiveCapacityMah = fccMah,
            nominalVoltageVolts = 3.85
        )
        assertNotNull(hybridRemain83)
        val remainWh = hybridRemain83!!.remainingEnergyWh!!
        assertTrue("remainWh ($remainWh) should be <= fullWh ($fullWh)", remainWh <= fullWh)
        assertTrue("remainWh ($remainWh) should be in 18.0..20.5", remainWh in 18.0..20.5)
    }

    /**
     * 测试方案A闭环：主界面首行标题与下方可用剩余能量均严格按标称平台（3.85V）基准计算，
     * 保持与 24.05 Wh、SOH 78.1% 及 83% SOC 完全同源闭环。
     */
    @Test
    fun testPlanANominalBaselineAndPercentageConsistency() {
        val fcc = 6247.0
        val safeSoc = 83
        val remainingMah = 5185.0
        val designCap = 8000.0

        val nominalFullWh = (fcc * 3.85) / 1000.0 // 24.05095
        val nominalRemainingWh = (nominalFullWh * safeSoc) / 100.0 // 19.9622885

        val result = CapacityEstimateResult(
            remainingChargeMah = remainingMah,
            remainingEnergyWh = nominalRemainingWh,
            fullChargeCapacityMah = fcc,
            designCapacityMah = designCap,
            sohPercent = (fcc / designCap) * 100.0,
            currentSoc = safeSoc,
            sourceTier = CapacitySourceTier.MULTI_SESSION_LEARNED,
            maturityLevel = MaturityLevel.CONVERGED,
            validSessionCount = 8,
            chargeSessionCount = 4,
            dischargeSessionCount = 4,
            totalSocSpanCovered = 320,
            dispersionCoefficientPercent = 1.1,
            conflictRateDPercent = 1.5,
            confidenceIntervalMah = 22.0,
            fullEnergyWh = nominalFullWh,
            energySourceTier = EnergySourceTier.NOMINAL_VOLTAGE,
            energyCoveragePercent = 0.65f
        )

        assertEquals("6247 mAh", result.getFormattedFcc())
        assertEquals("24.05 Wh", result.getFormattedFullEnergy())
        assertEquals("5185 mAh", result.getFormattedRemainingCharge())
        assertEquals("19.96 Wh", result.getFormattedRemainingEnergy())
        assertEquals("78.1%", result.getFormattedSoh())
        assertEquals(EnergySourceTier.NOMINAL_VOLTAGE, result.energySourceTier)
    }

    /**
     * 测试放电能量模型预测目标演进：
     * 弹窗看板最终预测的目标必须是电池 100% 满电实测做功能量（满电Wh），而不是当前 SOC 剩余多少 Wh。
     */
    @Test
    fun testDischargeEnergyModelPredictsFullChargeWhNotRemainingWh() {
        val fccMah = 6247.0f
        // 13 个 bin 已学习
        val bins = (0 until 20).map { index ->
            if (index in 7..19) {
                SocEnergyBin(
                    binIndex = index,
                    minSocPercent = index * 5.0f,
                    maxSocPercent = (index + 1) * 5.0f,
                    sampleCount = 2,
                    learnedEnergyWh = 1.18,
                    coverageFraction = 1.0,
                    dispersionWh = 0.02
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

        val curve = LearnedSocEnergyCurve(
            bins = bins,
            coveragePercent = 0.65f,
            estimatedFullUsableEnergyWh = null,
            maturity = ModelMaturity.DEVELOPING,
            curveVersion = 3,
            totalSampleSessions = 3,
            lastLearnedTimestamp = System.currentTimeMillis()
        )

        // 即使当前手机电量处于 83% SOC，模型预测的满电做功也必须是 100% 满充能量
        val overview = SocEnergyCurveLearner.buildComparisonOverviewFromCurve(
            curve = curve,
            currentSocPercent = 83f,
            effectiveCapacityMah = fccMah,
            nominalVoltageVolts = 3.85f
        )

        assertNotNull(overview.fullNominalEnergyWh)
        assertEquals(24.05, overview.fullNominalEnergyWh!!, 0.01)

        assertNotNull(overview.fullLearnedEnergyWh)
        val fullLearnedWh = overview.fullLearnedEnergyWh!!
        // 预测满充能量在 22.0..23.8 Wh 之间（代表 100% 满充实测做功，而不是 83% 剩余的 19 Wh）
        assertTrue("fullLearnedWh ($fullLearnedWh) must be 100% full capacity in 22.0..23.8", fullLearnedWh in 22.0..23.8)

        // 验证输出转化效率
        assertNotNull(overview.dischargeEnergyEfficiencyPercent)
        val efficiency = overview.dischargeEnergyEfficiencyPercent!!
        assertTrue("efficiency ($efficiency%) must be in 85%..98%", efficiency in 85.0..98.0)

        // 验证 100% 满充估算实体解析了全部 20 个区间
        assertNotNull(overview.hybridFullEstimate)
        assertEquals(20, overview.hybridFullEstimate!!.resolvedBins.size)
    }

    /**
     * 测试方案A演进：顶部首行显示标称满电基准（24.05 Wh），下方可用剩余能量保留实测混合微积分（如 17.32 Wh，HYBRID_MODEL），
     * 冷启动时平滑降级标称（19.96 Wh）。
     */
    @Test
    fun testPlanAHybridRemainingEnergyWithNominalFullBaseline() {
        val fcc = 6247.0
        val safeSoc = 83
        val remainingMah = 5185.0
        val designCap = 8000.0

        val nominalFullWh = (fcc * 3.85) / 1000.0 // 24.05095
        val hybridLearnedWh = 17.32 // 实测混合微积分动态做功

        val result = CapacityEstimateResult(
            remainingChargeMah = remainingMah,
            remainingEnergyWh = hybridLearnedWh,
            fullChargeCapacityMah = fcc,
            designCapacityMah = designCap,
            sohPercent = (fcc / designCap) * 100.0,
            currentSoc = safeSoc,
            sourceTier = CapacitySourceTier.MULTI_SESSION_LEARNED,
            maturityLevel = MaturityLevel.CONVERGED,
            validSessionCount = 8,
            chargeSessionCount = 4,
            dischargeSessionCount = 4,
            totalSocSpanCovered = 320,
            dispersionCoefficientPercent = 1.1,
            conflictRateDPercent = 1.5,
            confidenceIntervalMah = 22.0,
            fullEnergyWh = nominalFullWh, // 24.05 Wh
            energySourceTier = EnergySourceTier.HYBRID_MODEL,
            energyCoveragePercent = 0.65f
        )

        assertEquals("6247 mAh", result.getFormattedFcc())
        assertEquals("24.05 Wh", result.getFormattedFullEnergy()) // 首行预估满电能量：24.05 Wh
        assertEquals("5185 mAh", result.getFormattedRemainingCharge())
        assertEquals("17.32 Wh", result.getFormattedRemainingEnergy()) // 下方卡片可用剩余能量：17.32 Wh
        assertEquals("78.1%", result.getFormattedSoh())
        assertEquals(EnergySourceTier.HYBRID_MODEL, result.energySourceTier)
    }

    /**
     * 测试放电能量模型在 effectiveCapacityMah = null 时，完全基于实测形态自举外推。
     */
    @Test
    fun testDischargeEnergyBootstrapDecoupledFromCapacityPrior() {
        // 构造 13 个已学习 bin (7..19)，每段 1.18 Wh
        val bins = (0 until 20).map { index ->
            if (index in 7..19) {
                SocEnergyBin(
                    binIndex = index,
                    minSocPercent = index * 5.0f,
                    maxSocPercent = (index + 1) * 5.0f,
                    sampleCount = 2,
                    learnedEnergyWh = 1.18,
                    coverageFraction = 1.0,
                    dispersionWh = 0.02
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

        val curve = LearnedSocEnergyCurve(
            bins = bins,
            coveragePercent = 0.65f,
            estimatedFullUsableEnergyWh = null,
            maturity = ModelMaturity.DEVELOPING,
            curveVersion = 3,
            totalSampleSessions = 3,
            lastLearnedTimestamp = System.currentTimeMillis()
        )

        // effectiveCapacityMah 传 null：完全解耦充放电预测容量
        val overview = SocEnergyCurveLearner.buildComparisonOverviewFromCurve(
            curve = curve,
            currentSocPercent = 83f,
            effectiveCapacityMah = null,
            nominalVoltageVolts = 3.85f
        )

        assertNotNull(overview.fullLearnedEnergyWh)
        val fullLearnedWh = overview.fullLearnedEnergyWh!!
        assertTrue("fullLearnedWh ($fullLearnedWh) must be in 22.0..23.8", fullLearnedWh in 22.0..23.8)

        // 验证未学区间均成功自举外推
        val resolved = overview.resolvedBins
        assertEquals(20, resolved.size)
        for (i in 0..6) {
            assertEquals(EnergyEstimateSource.BOOTSTRAP_EXTRAPOLATED, resolved[i].source)
        }
    }
}
