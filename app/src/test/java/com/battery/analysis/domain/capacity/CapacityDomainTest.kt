package com.battery.analysis.domain.capacity

import com.battery.analysis.domain.capacity.estimator.ChargeCapacityEstimator
import com.battery.analysis.domain.capacity.estimator.DischargeCapacityEstimator
import com.battery.analysis.domain.capacity.estimator.SocEnergyCurveLearner
import com.battery.analysis.domain.capacity.fusion.ChargeDischargeFusionEngine
import com.battery.analysis.domain.capacity.integration.CoulombIntegrator
import com.battery.analysis.domain.capacity.model.CapacityChannel
import com.battery.analysis.domain.capacity.model.CapacitySample
import com.battery.analysis.domain.capacity.model.ChargingStatus
import com.battery.analysis.domain.capacity.model.Confidence
import com.battery.analysis.domain.capacity.model.FccCandidateSample
import com.battery.analysis.domain.capacity.model.FullEndpointQuality
import com.battery.analysis.domain.capacity.model.LearningMaturity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FCC 充放电双向容量学习纯领域核心算法与物理模型单元测试套件。
 */
class CapacityDomainTest {

    @Test
    fun testCoulombIntegrator_constantCurrent_trapezoidalAccuracy() {
        // 恒定放电电流 2.0A，持续 1 小时 (3600s)，电压 4.0V
        val samples = mutableListOf<CapacitySample>()
        for (sec in 0..3600 step 10) {
            samples.add(
                CapacitySample(
                    timestampMs = sec * 1000L,
                    socFraction = 1.0 - (sec / 3600.0) * 0.5,
                    currentAmp = 2.0,
                    voltageVolt = 4.0,
                    temperatureC = 25.0
                )
            )
        }

        val result = CoulombIntegrator.integrate(samples)
        // 2.0A * 1h = 2000 mAh
        assertEquals(2000.0, result.chargeMah, 1.0)
        // 4.0V * 2.0A * 1h = 8.0 Wh
        assertEquals(8.0, result.energyWh, 0.05)
        assertEquals(3600.0, result.validDurationSec, 0.1)
        assertEquals(1.0, result.coverageRatio, 0.01)
        assertEquals(4.0, result.avgVoltageVolts ?: 0.0, 0.01)
        assertTrue(CoulombIntegrator.isPhysicalConsistencyValid(result.chargeMah, result.energyWh))
    }

    @Test
    fun testCoulombIntegrator_gapCutoff_doesNotInterpolateOver60Seconds() {
        // 两点之间时间间隔为 120 秒（大于 60 秒阈值）
        val samples = listOf(
            CapacitySample(timestampMs = 0L, socFraction = 0.9, currentAmp = 1.0, voltageVolt = 4.0, temperatureC = 25.0),
            CapacitySample(timestampMs = 120_000L, socFraction = 0.8, currentAmp = 1.0, voltageVolt = 4.0, temperatureC = 25.0),
            CapacitySample(timestampMs = 130_000L, socFraction = 0.79, currentAmp = 1.0, voltageVolt = 4.0, temperatureC = 25.0)
        )

        val result = CoulombIntegrator.integrate(samples, maxGapSec = 60.0)
        // 0~120s 这一段被切断，只有 120~130s (10s) 参与积分
        // 1.0A * 10s = 1.0 * (10/3600) * 1000 = 2.777 mAh
        assertEquals(2.78, result.chargeMah, 0.05)
        assertEquals(10.0, result.validDurationSec, 0.1)
        assertEquals(120.0, result.maxGapSec, 0.1)
    }

    @Test
    fun testDischargeCapacityEstimator_candidateClassificationAndVariance() {
        // 放电 1000 mAh，SOC 从 80% 降到 55% (Δz = 0.25，B类候选)，采样步长 30s
        val samples = mutableListOf<CapacitySample>()
        for (sec in 0..3600 step 30) {
            samples.add(
                CapacitySample(
                    timestampMs = sec * 1000L,
                    socFraction = 0.80 - (sec / 3600.0) * 0.25,
                    currentAmp = 1.0,
                    voltageVolt = 3.85,
                    temperatureC = 25.0
                )
            )
        }

        val candidate = DischargeCapacityEstimator.buildCandidate(
            sessionId = "s1",
            samples = samples,
            startSoc = 0.80,
            endSoc = 0.55
        )

        assertFalse(candidate.exclusionReason, candidate.isExcluded)
        assertEquals(0.25, candidate.deltaSoc, 0.001)
        // 1000 / 0.25 = 4000 mAh
        assertEquals(4000.0, candidate.fccObservedMah, 10.0)
        assertTrue(candidate.varianceMah2 > 0.0)

        // 测试跨度不足 10% 被拒绝
        val smallSamples = mutableListOf<CapacitySample>()
        for (sec in 0..360 step 30) {
            smallSamples.add(
                CapacitySample(
                    timestampMs = sec * 1000L,
                    socFraction = 0.80 - (sec / 360.0) * 0.05,
                    currentAmp = 1.0,
                    voltageVolt = 3.85,
                    temperatureC = 25.0
                )
            )
        }
        val smallCandidate = DischargeCapacityEstimator.buildCandidate(
            sessionId = "s2",
            samples = smallSamples,
            startSoc = 0.80,
            endSoc = 0.75
        )
        assertTrue(smallCandidate.isExcluded)
        assertTrue(smallCandidate.exclusionReason.contains("SOC 跨度过小"))
    }

    @Test
    fun testChargeCapacityEstimator_apparentAndEndpointQuality() {
        val q = ChargeCapacityEstimator.evaluateEndpointQuality(
            endSoc = 1.0,
            lastVoltage = 4.38,
            lastCurrentAmp = 0.15,
            lastStatus = ChargingStatus.FULL
        )
        assertEquals(FullEndpointQuality.EXCELLENT, q)

        val poorQ = ChargeCapacityEstimator.evaluateEndpointQuality(
            endSoc = 0.70,
            lastVoltage = 3.90,
            lastCurrentAmp = 1.5,
            lastStatus = ChargingStatus.CHARGING
        )
        assertEquals(FullEndpointQuality.UNKNOWN, poorQ)
    }

    @Test
    fun testSocEnergyCurveLearner_piecewiseBinsAndCoverage() {
        val learner = SocEnergyCurveLearner()

        // 模拟放电覆盖 80%~95% (对应 Bin 16, 17, 18)
        val samples = mutableListOf<CapacitySample>()
        for (i in 0..30) {
            val soc = 0.95 - (i / 30.0) * 0.15 // 95% -> 80%
            samples.add(
                CapacitySample(
                    timestampMs = i * 20_000L,
                    socFraction = soc,
                    currentAmp = 1.0,
                    voltageVolt = 3.90,
                    temperatureC = 26.0
                )
            )
        }

        learner.absorbDischargeSession(samples)
        val bins = learner.getEnergyBins()
        assertEquals(20, bins.size)

        // 验证覆盖率 3 / 20 = 0.15
        assertEquals(0.15, learner.getCoverageRatio(), 0.05)

        // 覆盖不足 70% 时，全量 FCC_E 如实返回 null（显示学习中）
        val (fullE, _) = learner.estimateFullEnergyWh()
        assertNull(fullE)

        // 未学习的区间应标注 isLearned = false，不能填 0 作为已测
        val unlearnedBin = bins[0] // 0%~5%
        assertFalse(unlearnedBin.isLearned)
    }

    @Test
    fun testChargeDischargeFusionEngine_disagreementDetection() {
        val energyLearner = SocEnergyCurveLearner()

        // 模拟放电通道估计为 8000 mAh，而充电通道异常偏高为 10000 mAh（差异 25% > 10% 冲突门限）
        val dischargeCandidate = FccCandidateSample(
            sessionId = "d1",
            channel = CapacityChannel.DISCHARGE,
            startSoc = 0.90,
            endSoc = 0.40,
            deltaSoc = 0.50,
            chargeMah = 4000.0,
            energyWh = 15.4,
            fccObservedMah = 8000.0,
            varianceMah2 = 400.0 // sigma = 20
        )

        val chargeCandidate = FccCandidateSample(
            sessionId = "c1",
            channel = CapacityChannel.CHARGE,
            startSoc = 0.30,
            endSoc = 0.80,
            deltaSoc = 0.50,
            chargeMah = 5000.0,
            energyWh = 21.0,
            fccObservedMah = 10000.0,
            varianceMah2 = 400.0 // sigma = 20
        )

        val unified = ChargeDischargeFusionEngine.fuse(
            candidates = listOf(dischargeCandidate, chargeCandidate),
            energyLearner = energyLearner,
            designCapacityMah = 8000.0,
            currentSocPercent = 60
        )

        // 发生严重冲突时，状态机必须转为 DISAGREEMENT，禁止无脑算术平均
        assertEquals(LearningMaturity.DISAGREEMENT, unified.maturity)
        assertEquals(Confidence.LOW, unified.confidence)
        assertNotNull(unified.channelConflictPercent)
        assertTrue(unified.channelConflictPercent!! > 10.0)
    }

    @Test
    fun testChargeDischargeFusionEngine_consistentConvergence() {
        val energyLearner = SocEnergyCurveLearner()

        // 放电 7950 mAh，充电 8050 mAh，高度自洽
        val candidates = listOf(
            FccCandidateSample("d1", CapacityChannel.DISCHARGE, 0.90, 0.30, 0.60, 4770.0, 18.0, 7950.0, 900.0),
            FccCandidateSample("d2", CapacityChannel.DISCHARGE, 0.80, 0.20, 0.60, 4770.0, 18.0, 7950.0, 900.0),
            FccCandidateSample("c1", CapacityChannel.CHARGE, 0.20, 0.80, 0.60, 4830.0, 20.0, 8050.0, 1200.0)
        )

        val unified = ChargeDischargeFusionEngine.fuse(
            candidates = candidates,
            energyLearner = energyLearner,
            designCapacityMah = 8000.0,
            currentSocPercent = 50
        )

        assertEquals(LearningMaturity.CONVERGED, unified.maturity)
        assertEquals(Confidence.MEDIUM, unified.confidence)
        // 融合值应落在 7950 与 8050 之间
        assertTrue(unified.fccQMah!! in 7950.0..8050.0)
        // SOH_Q 应约为 7990 / 8000 ≈ 99.8%
        assertTrue(unified.sohQPercent!! in 99.0..101.0)
    }

    @Test
    fun testSocEnergyCurveLearner_multiSessionNormalization() {
        val learner = SocEnergyCurveLearner()

        // 模拟会话 1：在 75%~80% (Bin 15) 内放电 2% (79% -> 77%)，持续 600s (20 个 30s 间隔)，释放做功约 0.64 Wh
        val s1 = mutableListOf<CapacitySample>()
        for (i in 0..20) {
            val soc = 0.79 - (i / 20.0) * 0.02
            s1.add(CapacitySample(i * 30_000L, soc, 1.0, 3.85, 25.0))
        }
        learner.absorbDischargeSession(s1)

        // 模拟会话 2：在 75%~80% (Bin 15) 内放电 3% (78% -> 75%)，持续 900s (30 个 30s 间隔)，释放做功约 0.96 Wh
        val s2 = mutableListOf<CapacitySample>()
        for (i in 0..30) {
            val soc = 0.78 - (i / 30.0) * 0.03
            s2.add(CapacitySample(i * 30_000L, soc, 1.0, 3.85, 25.0))
        }
        learner.absorbDischargeSession(s2)

        val bins = learner.getEnergyBins()
        val bin15 = bins[15] // 75% - 80%

        assertTrue(bin15.isLearned)
        assertEquals(2, bin15.sampleCount)
        // 累计覆盖 2% + 3% = 5% SOC，总释放 0.64 + 0.96 = 1.60 Wh。归一化到 5% 分箱做功应约为 1.60 Wh，绝不能被错误除以 2 变成 0.80 Wh
        assertEquals(1.60, bin15.energyWh, 0.1)
    }

    @Test
    fun testChargeDischargeFusionEngine_chargeOnlyCapsAtInitial() {
        val energyLearner = SocEnergyCurveLearner()

        // 仅有 4 条充电样本，跨度达 200%，但无任何放电主通道样本
        val chargeCandidates = (1..4).map { i ->
            FccCandidateSample("c$i", CapacityChannel.CHARGE, 0.20, 0.80, 0.60, 4800.0, 20.0, 8000.0, 400.0)
        }

        val unified = ChargeDischargeFusionEngine.fuse(
            candidates = chargeCandidates,
            energyLearner = energyLearner,
            designCapacityMah = 8000.0,
            currentSocPercent = 50
        )

        // 遵循 Section 11.4 规范：缺少放电主估计通道时，纯充电通道估计成熟度最高限制为 INITIAL
        assertEquals(LearningMaturity.INITIAL, unified.maturity)
    }

    @Test
    fun testChargeDischargeFusionEngine_avoidsExcessiveVarianceShrinkage() {
        val energyLearner = SocEnergyCurveLearner()

        // 放电 sigma = 50 mAh，充电 sigma = 50 mAh
        val candidates = listOf(
            FccCandidateSample("d1", CapacityChannel.DISCHARGE, 0.80, 0.30, 0.50, 4000.0, 15.0, 8000.0, 2500.0),
            FccCandidateSample("c1", CapacityChannel.CHARGE, 0.30, 0.80, 0.50, 4000.0, 16.0, 8000.0, 2500.0)
        )

        val unified = ChargeDischargeFusionEngine.fuse(
            candidates = candidates,
            energyLearner = energyLearner,
            designCapacityMah = 8000.0,
            currentSocPercent = 50
        )

        // 遵循 Section 11.4 规则 5：同源电量计避免方差过度收缩（不能低于单通道不确定度的 90% = 45 mAh）
        assertTrue("Fused uncertainty should be >= 45 mAh", unified.fccQUncertaintyMah!! >= 45.0)
    }

    @Test
    fun testCoulombIntegrator_physicalConsistencyWithMissingVoltage() {
        // 无电压信息时 (energyWh = 0.0)，不能误判为物理自洽性异常而剔除样本
        assertTrue(CoulombIntegrator.isPhysicalConsistencyValid(chargeMah = 2000.0, energyWh = 0.0))
        // 正常电压范围 3.85V 校验通过
        assertTrue(CoulombIntegrator.isPhysicalConsistencyValid(chargeMah = 2000.0, energyWh = 7.7))
        // 异常单位（如 mV 导致算出的电压高达 3800V）校验失败
        assertFalse(CoulombIntegrator.isPhysicalConsistencyValid(chargeMah = 2000.0, energyWh = 7700.0))
    }

    @Test
    fun testSocEnergyCurveLearner_estimateRemainingEnergyWh_includesAllBinsAt100Percent() {
        val learner = SocEnergyCurveLearner()

        // 模拟放电覆盖完整的 100% -> 0% (所有 20 个 Bin 全部实测)
        val samples = mutableListOf<CapacitySample>()
        for (i in 0..200) {
            val soc = 1.0 - (i / 200.0)
            samples.add(CapacitySample(i * 30_000L, soc, 1.0, 3.85, 25.0))
        }
        learner.absorbDischargeSession(samples)

        val bins = learner.getEnergyBins()
        val learnedBins = bins.filter { it.isLearned }
        assertEquals(20, learnedBins.size)

        val totalAllBinsWh = bins.sumOf { it.energyWh }
        assertTrue(totalAllBinsWh > 0.0)

        // 验证 100% SOC 时的剩余可用能量必须精确等于全部 20 个 Bin 的总和，绝不能遗漏 Bin 19 (95%~100%)
        val remainingAt100 = learner.estimateRemainingEnergyWh(100)
        assertNotNull(remainingAt100)
        assertEquals(totalAllBinsWh, remainingAt100!!, 0.001)

        // 验证 0% SOC 时剩余能量精确为 0.0
        val remainingAt0 = learner.estimateRemainingEnergyWh(0)
        assertEquals(0.0, remainingAt0 ?: -1.0, 0.001)

        // 验证 50% SOC 时剩余能量大约为前半段 10 个 Bin (0%~50%) 的总和
        val halfBinsWh = bins.take(10).sumOf { it.energyWh }
        val remainingAt50 = learner.estimateRemainingEnergyWh(50)
        assertNotNull(remainingAt50)
        assertEquals(halfBinsWh, remainingAt50!!, 0.001)
    }
}
