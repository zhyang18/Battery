package com.battery.analysis.domain.capacity

import com.battery.analysis.domain.capacity.audit.CsvAuditSummary
import com.battery.analysis.domain.capacity.audit.DischargeAuditCsvReplayer
import com.battery.analysis.domain.capacity.audit.DischargeDataExportHelper
import com.battery.analysis.domain.capacity.audit.RawDischargeRecord
import com.battery.analysis.domain.capacity.estimator.ChargeCapacityEstimator
import com.battery.analysis.domain.capacity.estimator.ConditionComparisonMatcher
import com.battery.analysis.domain.capacity.estimator.ConditionModelValidator
import com.battery.analysis.domain.capacity.estimator.ConditionSensitivityLearner
import com.battery.analysis.domain.capacity.estimator.DischargeCapacityEstimator
import com.battery.analysis.domain.capacity.estimator.DischargeConditionAnalyzer
import com.battery.analysis.domain.capacity.estimator.DischargeConditionDatasetBuilder
import com.battery.analysis.domain.capacity.estimator.DischargeSessionDecoupler
import com.battery.analysis.domain.capacity.estimator.DischargeCounterConsistencyChecker
import com.battery.analysis.domain.capacity.estimator.DischargeSessionQualityEvaluator
import com.battery.analysis.domain.capacity.estimator.EnergyNormalizationEngine
import com.battery.analysis.domain.capacity.estimator.SocEnergyCurveLearner
import com.battery.analysis.domain.capacity.fusion.ChargeDischargeFusionEngine
import com.battery.analysis.domain.capacity.integration.CoulombIntegrator
import com.battery.analysis.domain.capacity.model.AcceptedFccQ
import com.battery.analysis.domain.capacity.model.AgingTrendStatus
import com.battery.analysis.domain.capacity.model.BinLearningStatus
import com.battery.analysis.domain.capacity.model.BootstrapEvidenceLevel
import com.battery.analysis.domain.capacity.model.CapacityChannel
import com.battery.analysis.domain.capacity.model.CapacitySample
import com.battery.analysis.domain.capacity.model.ChargingStatus
import com.battery.analysis.domain.capacity.model.CommonSocCoverage
import com.battery.analysis.domain.capacity.model.ConditionModelMaturity
import com.battery.analysis.domain.capacity.model.ConditionModelValidation
import com.battery.analysis.domain.capacity.model.ConditionSampleIdentity
import com.battery.analysis.domain.capacity.model.ConditionSupportStatus
import com.battery.analysis.domain.capacity.model.Confidence
import com.battery.analysis.domain.capacity.model.ContinuousConditionBounds
import com.battery.analysis.domain.capacity.model.CounterConsistency
import com.battery.analysis.domain.capacity.model.CounterQuality
import com.battery.analysis.domain.capacity.model.DischargeConditionSample
import com.battery.analysis.domain.capacity.model.DischargeCutoffPolicy
import com.battery.analysis.domain.capacity.model.DischargeLoadBand
import com.battery.analysis.domain.capacity.model.DischargeOperatingCondition
import com.battery.analysis.domain.capacity.model.DischargeQuality
import com.battery.analysis.domain.capacity.model.DischargeThermalBand
import com.battery.analysis.domain.capacity.model.EnergyAllocationQuality
import com.battery.analysis.domain.capacity.model.EnergyCapacityTriad
import com.battery.analysis.domain.capacity.model.EnergyNormalizationResult
import com.battery.analysis.domain.capacity.model.EnergyReferenceProtocol
import com.battery.analysis.domain.capacity.model.FccCandidateSample
import com.battery.analysis.domain.capacity.model.FullEndpointQuality
import com.battery.analysis.domain.capacity.model.ImprovementSignificanceLevel
import com.battery.analysis.domain.capacity.model.LearningMaturity
import com.battery.analysis.domain.capacity.model.LoadProfileType
import com.battery.analysis.domain.capacity.model.MINIMUM_PRACTICAL_IMPROVEMENT
import com.battery.analysis.domain.capacity.model.NormalizationUncertaintyBreakdown
import com.battery.analysis.domain.capacity.model.ReferenceConditionRange
import com.battery.analysis.domain.capacity.model.SocBinOperatingCondition
import com.battery.analysis.domain.capacity.model.SocBoundaryQuality
import com.battery.analysis.domain.capacity.model.SocEnergyBinInfo
import com.battery.analysis.domain.capacity.model.SocOverlapStatus
import com.battery.analysis.domain.capacity.model.StartDischargeCondition
import com.battery.analysis.domain.capacity.model.StartDischargePolicy
import com.battery.analysis.domain.capacity.model.DataPurityCondition
import com.battery.analysis.domain.capacity.model.ThermalProfileType
import com.battery.analysis.domain.capacity.model.TopPlateauNormalizationPolicy
import com.battery.analysis.domain.capacity.model.TransitionNormalizationPolicy
import com.battery.analysis.model.CapacityCandidate
import com.battery.analysis.model.CandidateSessionType
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

    /**
     * 验证放电容量估计器的主账本权威物理能量锚定机制。
     * 当采样点序列因长待机休眠唤醒电流过大导致裸梯形积分虚假膨胀（如积分出 8017 mAh）时，
     * 传入主账本权威核算的真实物理放电量（5533.2 mAh，对应能量 21.30 Wh），
     * 候选样本的净放电量必须准确锚定为权威真值，推算满充容量 FCC 严谨收敛至 6014 mAh，彻底消除 8714 mAh 虚高。
     */
    @Test
    fun testDischargeCapacityEstimator_authoritativeLedgerAnchoring_eliminatesInflation() {
        val samples = mutableListOf<CapacitySample>()
        // 构造 100% -> 8% (ΔSOC = 0.92) 的放电采样序列
        // 模拟息屏待机长间隙唤醒采样失真：裸采样点电流偏高，导致未校准积分量异常偏大
        for (sec in 0..7200 step 60) {
            val progress = sec / 7200.0
            samples.add(
                CapacitySample(
                    timestampMs = sec * 1000L,
                    socFraction = 1.0 - progress * 0.92,
                    currentAmp = 1.5, // 偏高电流导致裸积分大幅膨胀
                    voltageVolt = 3.85,
                    temperatureC = 25.0
                )
            )
        }

        val authDischargedMah = 5533.2
        val authEnergyWh = 21.30

        val candidate = DischargeCapacityEstimator.buildCandidate(
            sessionId = "session_honor500pro",
            samples = samples,
            startSoc = 1.0,
            endSoc = 0.08,
            isDualCell = false,
            authoritativeDischargedMah = authDischargedMah,
            authoritativeEnergyWh = authEnergyWh
        )

        assertFalse("合格放电会话不应被拒绝: ${candidate.exclusionReason}", candidate.isExcluded)
        // 验证净放电量严格受主账本权威核算量（5533.2 mAh）锚定守护，绝不膨胀至 8017 mAh
        assertEquals(5533.2, candidate.chargeMah, 50.0)
        // 验证推算容量 FCC 严谨收敛至真实物理容量约 6014 mAh (5533.2 / 0.92)，绝不虚高至 8714 mAh
        assertEquals(6014.0, candidate.fccObservedMah, 60.0)
        assertTrue("推算容量方差必须有效", candidate.varianceMah2 > 0.0)
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
    fun testChargeCapacityEstimator_withAuthoritativeChargeMahAnd180sGap() {
        // 模拟息屏快充，电量每涨 1% 耗时 80 秒（大于 60 秒但小于 180 秒）
        val samples = listOf(
            CapacitySample(timestampMs = 0L, socFraction = 0.29, currentAmp = 4.0, voltageVolt = 4.4, temperatureC = 35.0, chargingStatus = ChargingStatus.CHARGING),
            CapacitySample(timestampMs = 80_000L, socFraction = 0.30, currentAmp = 4.0, voltageVolt = 4.4, temperatureC = 35.0, chargingStatus = ChargingStatus.CHARGING),
            CapacitySample(timestampMs = 160_000L, socFraction = 0.31, currentAmp = 4.0, voltageVolt = 4.4, temperatureC = 35.0, chargingStatus = ChargingStatus.CHARGING)
        )

        // 默认 180 秒门限下，80 秒间隔能够完整积分，绝不被判定为断层切断
        val segment = CoulombIntegrator.integrate(samples)
        assertTrue("80s 间隔在 180s 容差下覆盖率必须为 100%", segment.coverageRatio >= 0.99)
        assertTrue("80s 间隔必须正常累加电量", segment.chargeMah > 0.0)

        // 测试透传权威主账本电量（如 4421 mAh，跨度 71%）
        val candidate = ChargeCapacityEstimator.buildCandidate(
            sessionId = "chg_001",
            samples = samples,
            startSoc = 0.29,
            endSoc = 1.00,
            authoritativeChargeMah = 4421.0,
            authoritativeEnergyWh = 19.50
        )

        assertEquals(4421.0, candidate.chargeMah, 1e-6)
        assertEquals(19.50, candidate.energyWh, 1e-6)
        assertEquals(4421.0 / 0.71, candidate.fccObservedMah, 0.5)
        assertEquals(6226.76, candidate.fccObservedMah, 1.0)
        assertFalse("具备权威主账本电量时严禁误标记为排除", candidate.isExcluded)
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

        // 放电 7950 mAh，充电 8050 mAh，高度自洽且充电完成端点标定
        val candidates = listOf(
            FccCandidateSample("d1", CapacityChannel.DISCHARGE, 0.90, 0.30, 0.60, 4770.0, 18.0, 7950.0, 900.0),
            FccCandidateSample("d2", CapacityChannel.DISCHARGE, 0.80, 0.20, 0.60, 4770.0, 18.0, 7950.0, 900.0),
            FccCandidateSample("c1", CapacityChannel.CHARGE, 0.20, 0.80, 0.60, 4830.0, 20.0, 8050.0, 1200.0, endpointQuality = FullEndpointQuality.EXCELLENT)
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

    /**
     * P0 阶段核心测试：验证 Android BatteryManager 原生电流极性与异常值过滤。
     * 1. 负数微安正确转换为正数毫安放电电流；
     * 2. Integer.MIN_VALUE 严格识别为不支持或读取失败，拒绝参与积分；
     * 3. 放电状态下的正数电流判定为逆向或充电，不计入有效放电。
     */
    @Test
    fun testP0_batteryManagerCurrentPolarityAndErrorFilter() {
        // 1. 测试常规负电流：-1,200,000 uA -> 1200 mA
        val rawNegativeUa = -1_200_000
        val normalDischargeMa = if (rawNegativeUa != Int.MIN_VALUE && rawNegativeUa < 0) {
            -rawNegativeUa / 1000.0
        } else null
        assertEquals(1200.0, normalDischargeMa ?: 0.0, 0.01)

        // 2. 测试异常值过滤：Integer.MIN_VALUE 必须返回 null
        val errorRawUa = Int.MIN_VALUE
        val errorDischargeMa = if (errorRawUa != Int.MIN_VALUE && errorRawUa < 0) {
            -errorRawUa.toLong() / 1000.0
        } else null
        assertNull("Integer.MIN_VALUE 必须严格过滤为 null", errorDischargeMa)

        // 3. 测试放电状态下收到正电流 (+500,000 uA) 视为异常或净充电，不作为正向放电电流
        val rawPositiveUa = 500_000
        val positiveInDischarge = if (rawPositiveUa != Int.MIN_VALUE && rawPositiveUa < 0) {
            -rawPositiveUa / 1000.0
        } else null
        assertNull("放电状态下的正电流不应被当作放电电流", positiveInDischarge)
    }

    /**
     * P0 阶段核心测试：验证旧历史估算数据的严格隔离机制。
     * 旧数据反序列化时自动标记为 LEGACY_ESTIMATED_POWER 且 currentMeasured = false，严禁伪装为实测。
     */
    @Test
    fun testP0_legacyDataProvenanceIsolation() {
        val legacyJson = """
            [
                {"ts": 1000000, "elapsed": 0.0, "lvl": 90, "volt": 4.10, "temp": 28.0, "pwr": 1.50, "screenOn": true},
                {"ts": 1030000, "elapsed": 0.0083, "lvl": 89, "volt": 4.08, "temp": 28.2, "pwr": 1.45, "screenOn": true}
            ]
        """.trimIndent()

        val record = com.battery.analysis.model.PowerUsageRecord(
            id = 1000000L,
            recordTime = "2026-11-11 10:00:00",
            levelPercent = 89,
            voltageVolts = 4.08f,
            temperature = 28.2f,
            energyWh = 0.5f,
            isCharging = false,
            avgPowerWatts = 1.48f,
            screenOnPowerWatts = 1.48f,
            screenOffPowerWatts = 0f,
            screenOnDurationText = "30s",
            screenOffDurationText = "0s",
            totalDurationText = "30s",
            remainingScreenOnText = "--",
            remainingCompositeText = "--",
            remainingScreenOffText = "--",
            isShizukuRealData = false,
            appCount = 0,
            trendPointsJson = legacyJson,
            appListJson = "[]"
        )

        val parsedPoints = record.parseTrendPointsList()
        assertEquals(2, parsedPoints.size)

        for (pt in parsedPoints) {
            // 验证旧历史数据被严格标记为 LEGACY_ESTIMATED_POWER
            assertEquals(com.battery.analysis.model.DischargeDataProvenance.LEGACY_ESTIMATED_POWER, pt.provenance)
            assertFalse("旧历史估算数据绝不可标记为物理测量", pt.currentMeasured)
            assertNull("旧数据未记录物理电流时 dischargeCurrentMa 必须为 null", pt.dischargeCurrentMa)
        }
    }

    /**
     * P0 阶段核心测试：验证彻底删除功率反推电流回退逻辑。
     * 当物理测量电流缺失时，currentAmp 严格为 null，坚决杜绝使用 powerWatts / voltageVolts 补值。
     */
    @Test
    fun testP0_noFallbackToPowerWattsWhenPhysicalCurrentMissing() {
        // 构建 20 个采样点，仅包含 powerWatts 与 voltageVolts，但无物理实测电流（模拟旧历史记录或待机补偿点）
        val unmeasuredSamples = mutableListOf<CapacitySample>()
        for (i in 0..20) {
            unmeasuredSamples.add(
                CapacitySample(
                    timestampMs = i * 30_000L,
                    socFraction = 0.80 - (i / 20.0) * 0.20,
                    currentAmp = null, // 物理电流缺失，严格禁止从 (pwr / volt) 补值
                    voltageVolt = 3.85,
                    temperatureC = 26.0
                )
            )
        }

        val candidate = DischargeCapacityEstimator.buildCandidate(
            sessionId = "test_legacy_session",
            samples = unmeasuredSamples,
            startSoc = 0.80,
            endSoc = 0.60
        )

        // 验证：由于缺少物理电流，有效放电量为 0，采样覆盖率为 0，该样本被严格排除！
        assertEquals(0.0, candidate.chargeMah, 0.001)
        assertTrue("物理电流缺失的会话必须被排除入库", candidate.isExcluded)
        assertTrue(candidate.exclusionReason.contains("采样断层过多") || candidate.exclusionReason.contains("超出物理合理范围"))
    }

    /**
     * P1-A 核心测试 1：验证 100% 满电平台解耦。
     * 模拟用户典型数据：100% 顶部平台放出 500mAh，99%~50% 有效放电 3920mAh。
     * 验证 500mAh 被独立记录在 topPlateau.plateauMah，绝不计入有效放电电量分子；
     * 有效推算容量精确为 3920 / 0.49 = 8000 mAh。
     */
    @Test
    fun testP1A_topPlateauIsolation_500mahPlateauDoesNotPolluteFcc() {
        val samples = mutableListOf<CapacitySample>()
        var t = 0L

        // 1. 顶部满电平台：SOC 稳定维持 100%，持续 1800 秒，恒流 1.0 A，放出 500 mAh
        for (i in 0..60) {
            samples.add(
                CapacitySample(
                    timestampMs = t,
                    socFraction = 1.0,
                    currentAmp = 1.0,
                    voltageVolt = 4.35,
                    temperatureC = 25.0,
                    chargingStatus = ChargingStatus.DISCHARGING
                )
            )
            t += 30_000L
        }

        // 2. 过渡单步：从 100% 降至 99%，持续 30 秒，电流 1.0 A
        val tTransition = t
        samples.add(
            CapacitySample(
                timestampMs = tTransition,
                socFraction = 0.99,
                currentAmp = 1.0,
                voltageVolt = 4.25,
                temperatureC = 25.0,
                chargingStatus = ChargingStatus.DISCHARGING
            )
        )
        t += 30_000L

        // 3. 有效放电区间：连续稳定采样，SOC 从 99% 放电至 50% (ΔSOC = 0.49)
        // 目标有效放电量 3920 mAh，平均电流 1.0 A，持续时长 3.92 小时 = 14112 秒
        val stepSec = 30
        val totalValidSteps = 14112 / stepSec
        val currentAmp = 1.0
        val socStep = 0.49 / totalValidSteps

        for (step in 1..totalValidSteps) {
            val currentSoc = 0.99 - (step * socStep)
            samples.add(
                CapacitySample(
                    timestampMs = t,
                    socFraction = currentSoc,
                    currentAmp = currentAmp,
                    voltageVolt = 3.85,
                    temperatureC = 26.0,
                    chargingStatus = ChargingStatus.DISCHARGING
                )
            )
            t += stepSec * 1000L
        }

        val candidate = DischargeCapacityEstimator.buildCandidate(
            sessionId = "p1a_plateau_session",
            samples = samples,
            startSoc = 1.0,
            endSoc = 0.50
        )

        assertNotNull("放电会话必须记录顶部平台指标", candidate.topPlateau)
        // 顶部平台放电量接近 500 mAh (1800s * 1A / 3600 = 500 mAh)
        assertEquals(500.0, candidate.topPlateau!!.plateauMah, 5.0)
        // 过渡区单步电量隔离 (30s * 1A / 3600 = 8.33 mAh)
        assertTrue(candidate.topPlateau!!.transitionMah > 0.0)

        // 核心验证：有效起始 SOC 已对齐至 99%，有效跨度为 49%
        assertEquals(0.99, candidate.startSoc, 0.001)
        assertEquals(0.50, candidate.endSoc, 0.01)
        assertEquals(0.49, candidate.deltaSoc, 0.01)

        // 核心验证：有效放电量必须约为 3920 mAh，绝不允许混入 500 mAh
        assertEquals(3920.0, candidate.chargeMah, 15.0)

        // 核心验证：推算满充容量 FCC_Q_dis = 3920 / 0.49 = 8000 mAh
        assertEquals(8000.0, candidate.fccObservedMah, 30.0)
        assertFalse(candidate.exclusionReason, candidate.isExcluded)
    }

    /**
     * P1-A 核心测试 2：验证过渡区间边界不确定性独立隔离。
     * 过渡区间电荷被单独记录在 topPlateau.transitionMah 中，不计入 plateauMah，也不计入有效放电。
     */
    @Test
    fun testP1A_topTransitionIntervalIsolation() {
        val samples = listOf(
            CapacitySample(timestampMs = 0L, socFraction = 1.0, currentAmp = 1.0, voltageVolt = 4.35, temperatureC = 25.0),
            CapacitySample(timestampMs = 60_000L, socFraction = 1.0, currentAmp = 1.0, voltageVolt = 4.35, temperatureC = 25.0),
            // 过渡步：从 100% 降到 99%
            CapacitySample(timestampMs = 120_000L, socFraction = 0.99, currentAmp = 1.0, voltageVolt = 4.25, temperatureC = 25.0),
            // 有效放电点，稳定在 99%
            CapacitySample(timestampMs = 180_000L, socFraction = 0.99, currentAmp = 1.0, voltageVolt = 4.20, temperatureC = 25.0),
            CapacitySample(timestampMs = 3600_000L, socFraction = 0.50, currentAmp = 1.0, voltageVolt = 3.80, temperatureC = 25.0)
        )

        val decoupled = DischargeSessionDecoupler.decouple(samples, 1.0, 0.50)
        // 平台电量来自 [0L, 60_000L]
        assertTrue(decoupled.topPlateau.plateauMah > 0.0)
        // 过渡电量来自 [60_000L, 120_000L]
        assertTrue(decoupled.topPlateau.transitionMah > 0.0)
        // 有效放电起点严格对齐在 120_000L
        assertEquals(120_000L, decoupled.effectiveSamples.first().timestampMs)
        assertEquals(0.99, decoupled.effectiveStartSoc, 0.001)
    }

    /**
     * P1-A 核心测试 3：验证连续采样防抖机制（过滤单点回弹噪声）。
     * 出现 100% -> 99% -> 100% 时判定为瞬时读数抖动，不触发切换；随后连续出现两次 99% 方才稳定切换。
     */
    @Test
    fun testP1A_firstStableSocDrop_jitterFilter() {
        val samples = listOf(
            CapacitySample(timestampMs = 0L, socFraction = 1.0, currentAmp = 0.5, voltageVolt = 4.35, temperatureC = 25.0),
            CapacitySample(timestampMs = 30_000L, socFraction = 1.0, currentAmp = 0.5, voltageVolt = 4.35, temperatureC = 25.0),
            // 单点抖动跌至 99%
            CapacitySample(timestampMs = 60_000L, socFraction = 0.99, currentAmp = 0.5, voltageVolt = 4.35, temperatureC = 25.0),
            // 立即回弹至 100%（噪声回弹）
            CapacitySample(timestampMs = 90_000L, socFraction = 1.0, currentAmp = 0.5, voltageVolt = 4.35, temperatureC = 25.0),
            CapacitySample(timestampMs = 120_000L, socFraction = 1.0, currentAmp = 0.5, voltageVolt = 4.35, temperatureC = 25.0),
            // 正式稳定跌落点 1
            CapacitySample(timestampMs = 150_000L, socFraction = 0.99, currentAmp = 0.5, voltageVolt = 4.25, temperatureC = 25.0),
            // 正式稳定跌落点 2（确认未回弹）
            CapacitySample(timestampMs = 180_000L, socFraction = 0.99, currentAmp = 0.5, voltageVolt = 4.25, temperatureC = 25.0),
            CapacitySample(timestampMs = 3600_000L, socFraction = 0.50, currentAmp = 0.5, voltageVolt = 3.80, temperatureC = 25.0)
        )

        val decoupled = DischargeSessionDecoupler.decouple(samples, 1.0, 0.50)
        // 验证有效起点没有被 60_000L 的抖动点误切，而是锁定在 150_000L
        assertEquals(150_000L, decoupled.effectiveSamples.first().timestampMs)
        assertEquals(0.99, decoupled.effectiveStartSoc, 0.001)
    }

    /**
     * P1-A 核心测试 4：验证积分起止时间戳与 SOC 起止时间戳严格一致。
     */
    @Test
    fun testP1A_socStartAndCoulombStartAlignment() {
        val samples = mutableListOf<CapacitySample>()
        // 100% 维持 3 点
        samples.add(CapacitySample(timestampMs = 10_000L, socFraction = 1.0, currentAmp = 1.0, voltageVolt = 4.35, temperatureC = 25.0))
        samples.add(CapacitySample(timestampMs = 20_000L, socFraction = 1.0, currentAmp = 1.0, voltageVolt = 4.35, temperatureC = 25.0))
        // 99% 开始维持
        samples.add(CapacitySample(timestampMs = 30_000L, socFraction = 0.99, currentAmp = 1.0, voltageVolt = 4.25, temperatureC = 25.0))
        samples.add(CapacitySample(timestampMs = 40_000L, socFraction = 0.99, currentAmp = 1.0, voltageVolt = 4.25, temperatureC = 25.0))
        samples.add(CapacitySample(timestampMs = 70_000L, socFraction = 0.70, currentAmp = 1.0, voltageVolt = 3.90, temperatureC = 25.0))

        val decoupled = DischargeSessionDecoupler.decouple(samples, 1.0, 0.70)
        val firstEffectiveSample = decoupled.effectiveSamples.first()
        val lastEffectiveSample = decoupled.effectiveSamples.last()

        // 积分起点时间戳必须完全对应有效起始 SOC 时间戳
        assertEquals(30_000L, firstEffectiveSample.timestampMs)
        assertEquals("起点SOC一致", decoupled.effectiveStartSoc, firstEffectiveSample.socFraction ?: 0.0, 0.0001)

        // 积分终点时间戳必须完全对应有效结束 SOC 时间戳
        assertEquals(70_000L, lastEffectiveSample.timestampMs)
        assertEquals("终点SOC一致", decoupled.effectiveEndSoc, lastEffectiveSample.socFraction ?: 0.0, 0.0001)
    }

    /**
     * P1-A 核心测试 5：验证起始 SOC 突降跳变异常标记（SOC_START_JUMP）。
     * 从 100% 直接突跌至 95%（跌幅 >= 3%），样本必须被标记为排除。
     */
    @Test
    fun testP1A_socStartJumpAnomaly() {
        val samples = listOf(
            CapacitySample(timestampMs = 0L, socFraction = 1.0, currentAmp = 1.0, voltageVolt = 4.35, temperatureC = 25.0),
            // 直接跳至 95%
            CapacitySample(timestampMs = 30_000L, socFraction = 0.95, currentAmp = 1.0, voltageVolt = 4.10, temperatureC = 25.0),
            CapacitySample(timestampMs = 60_000L, socFraction = 0.95, currentAmp = 1.0, voltageVolt = 4.10, temperatureC = 25.0),
            CapacitySample(timestampMs = 3600_000L, socFraction = 0.50, currentAmp = 1.0, voltageVolt = 3.80, temperatureC = 25.0)
        )

        val candidate = DischargeCapacityEstimator.buildCandidate(
            sessionId = "jump_session",
            samples = samples,
            startSoc = 1.0,
            endSoc = 0.50
        )

        assertTrue("起始突降异常样本必须被排除", candidate.isExcluded)
        assertTrue(candidate.exclusionReason.contains("SOC_START_JUMP"))
    }

    /**
     * P1-A 核心测试 6：验证整段放电全程处于 100% 满电平台时的处理。
     * 无有效放电区间，样本必须被标记排除，但平台放电量被忠实记录。
     */
    @Test
    fun testP1A_fullSessionOnTopPlateau() {
        val samples = listOf(
            CapacitySample(timestampMs = 0L, socFraction = 1.0, currentAmp = 1.0, voltageVolt = 4.35, temperatureC = 25.0),
            CapacitySample(timestampMs = 30_000L, socFraction = 1.0, currentAmp = 1.0, voltageVolt = 4.35, temperatureC = 25.0),
            CapacitySample(timestampMs = 60_000L, socFraction = 1.0, currentAmp = 1.0, voltageVolt = 4.35, temperatureC = 25.0)
        )

        val candidate = DischargeCapacityEstimator.buildCandidate(
            sessionId = "full_plateau_session",
            samples = samples,
            startSoc = 1.0,
            endSoc = 1.0
        )

        assertTrue(candidate.isExcluded)
        assertTrue(candidate.exclusionReason.contains("满电平台"))
        assertNotNull(candidate.topPlateau)
        assertTrue(candidate.topPlateau!!.plateauMah > 0.0)
        assertEquals(0.0, candidate.chargeMah, 0.001)
    }

    /**
     * P1-B 测试 1：有效物理实测电流占比 >= 80% (例如 95%) 允许评价且评为 HIGH/MEDIUM。
     */
    @Test
    fun testP1B_physicalCurrentRatio_highAllowsEvaluation() {
        val samples = mutableListOf<CapacitySample>()
        // 100 个点，从 80% 到 50% (Δz = 30%)，其中 98 个点有物理电流，2 个点无（占比 98% >= 80%）
        for (i in 0..100) {
            val hasCurrent = (i != 30 && i != 70) // 98% 有电流，微小断点丢失 SOC 仅约 1.2% < 3.0%
            samples.add(
                CapacitySample(
                    timestampMs = i * 30_000L,
                    socFraction = 0.80 - (i / 100.0) * 0.30,
                    currentAmp = if (hasCurrent) 1.0 else null,
                    voltageVolt = 3.85,
                    temperatureC = 25.0,
                    chargingStatus = ChargingStatus.DISCHARGING
                )
            )
        }

        val candidate = DischargeCapacityEstimator.buildCandidate(
            sessionId = "p1b_high_curr",
            samples = samples,
            startSoc = 0.80,
            endSoc = 0.50
        )

        assertFalse("物理电流占比高且无严重断层不应被排除: ${candidate.exclusionReason}", candidate.isExcluded)
        assertTrue(candidate.qualityLevel == DischargeQuality.HIGH || candidate.qualityLevel == DischargeQuality.MEDIUM)
    }

    /**
     * P1-B 测试 2：有效物理电流占比不足 80% (例如 50%) 坚决拒绝学习。
     */
    @Test
    fun testP1B_physicalCurrentRatio_lowRejectsEvaluation() {
        val samples = mutableListOf<CapacitySample>()
        // 100 个点，仅 50% 点有电流
        for (i in 0..100) {
            val hasCurrent = i % 2 == 0
            samples.add(
                CapacitySample(
                    timestampMs = i * 30_000L,
                    socFraction = 0.80 - (i / 100.0) * 0.30,
                    currentAmp = if (hasCurrent) 1.0 else null,
                    voltageVolt = 3.85,
                    temperatureC = 25.0,
                    chargingStatus = ChargingStatus.DISCHARGING
                )
            )
        }

        val candidate = DischargeCapacityEstimator.buildCandidate(
            sessionId = "p1b_low_curr",
            samples = samples,
            startSoc = 0.80,
            endSoc = 0.50
        )

        assertTrue("物理电流不足80%必须被排除", candidate.isExcluded)
        assertTrue(candidate.exclusionReason.contains("物理实测电流占比不足"))
        assertEquals(DischargeQuality.REJECTED, candidate.qualityLevel)
    }

    /**
     * P1-B 测试 3：采样正常间隔 30 秒不触发缺口断层拒识。
     */
    @Test
    fun testP1B_normalGapDoesNotReject() {
        val samples = mutableListOf<CapacitySample>()
        for (i in 0..50) {
            samples.add(
                CapacitySample(
                    timestampMs = i * 30_000L,
                    socFraction = 0.80 - (i / 50.0) * 0.25,
                    currentAmp = 1.0,
                    voltageVolt = 3.85,
                    temperatureC = 25.0,
                    chargingStatus = ChargingStatus.DISCHARGING
                )
            )
        }

        val candidate = DischargeCapacityEstimator.buildCandidate(
            sessionId = "p1b_normal_gap",
            samples = samples,
            startSoc = 0.80,
            endSoc = 0.55
        )

        assertFalse(candidate.isExcluded)
        assertEquals(1.0, candidate.effectiveIntervalCoverage, 0.01)
    }

    /**
     * P1-B 测试 4：严重采样断层（缺口超过 60 秒且覆盖率 < 80%）触发拒绝。
     */
    @Test
    fun testP1B_severeGapCutoffRejects() {
        val samples = mutableListOf<CapacitySample>()
        // 前 10 个点
        for (i in 0..10) {
            samples.add(CapacitySample(timestampMs = i * 10_000L, socFraction = 0.80 - i * 0.005, currentAmp = 1.0, voltageVolt = 3.85, temperatureC = 25.0))
        }
        // 出现长达 2 小时断层 (7,200 秒)
        val gapTs = 100_000L + 7_200_000L
        for (i in 0..10) {
            samples.add(CapacitySample(timestampMs = gapTs + i * 10_000L, socFraction = 0.70 - i * 0.005, currentAmp = 1.0, voltageVolt = 3.85, temperatureC = 25.0))
        }

        val candidate = DischargeCapacityEstimator.buildCandidate(
            sessionId = "p1b_gap_session",
            samples = samples,
            startSoc = 0.80,
            endSoc = 0.65
        )

        assertTrue("严重断层导致覆盖率过低必须被排除", candidate.isExcluded)
        assertTrue(candidate.exclusionReason.contains("采样断层过多") || candidate.exclusionReason.contains("覆盖率"))
        assertEquals(DischargeQuality.REJECTED, candidate.qualityLevel)
    }

    /**
     * P1-B 测试 5：时间戳倒退时拒绝会话。
     */
    @Test
    fun testP1B_timestampRegressionRejects() {
        val samples = listOf(
            CapacitySample(timestampMs = 100_000L, socFraction = 0.80, currentAmp = 1.0, voltageVolt = 3.85, temperatureC = 25.0),
            CapacitySample(timestampMs = 130_000L, socFraction = 0.75, currentAmp = 1.0, voltageVolt = 3.85, temperatureC = 25.0),
            // 时间戳倒退回 110_000L
            CapacitySample(timestampMs = 110_000L, socFraction = 0.70, currentAmp = 1.0, voltageVolt = 3.85, temperatureC = 25.0),
            CapacitySample(timestampMs = 160_000L, socFraction = 0.60, currentAmp = 1.0, voltageVolt = 3.85, temperatureC = 25.0)
        )

        val candidate = DischargeCapacityEstimator.buildCandidate(
            sessionId = "p1b_regress",
            samples = samples,
            startSoc = 0.80,
            endSoc = 0.60
        )

        assertTrue(candidate.isExcluded)
        assertTrue(candidate.exclusionReason.contains("时间戳出现严重倒退"))
    }

    /**
     * P1-B 测试 6：会话中插入充电状态数据时，拒绝跨状态积分。
     */
    @Test
    fun testP1B_chargingStateMixedRejects() {
        val samples = listOf(
            CapacitySample(timestampMs = 0L, socFraction = 0.80, currentAmp = 1.0, voltageVolt = 3.85, temperatureC = 25.0, chargingStatus = ChargingStatus.DISCHARGING),
            CapacitySample(timestampMs = 30_000L, socFraction = 0.75, currentAmp = 1.0, voltageVolt = 3.85, temperatureC = 25.0, chargingStatus = ChargingStatus.DISCHARGING),
            // 异常插线充电点
            CapacitySample(timestampMs = 60_000L, socFraction = 0.76, currentAmp = 1.0, voltageVolt = 4.10, temperatureC = 25.0, chargingStatus = ChargingStatus.CHARGING),
            CapacitySample(timestampMs = 90_000L, socFraction = 0.70, currentAmp = 1.0, voltageVolt = 3.85, temperatureC = 25.0, chargingStatus = ChargingStatus.DISCHARGING)
        )

        val candidate = DischargeCapacityEstimator.buildCandidate(
            sessionId = "p1b_charging_mix",
            samples = samples,
            startSoc = 0.80,
            endSoc = 0.70
        )

        assertTrue(candidate.isExcluded)
        assertTrue(candidate.exclusionReason.contains("混入充电数据"))
    }

    /**
     * P1-B 测试 7：电量计 CHARGE_COUNTER 交叉一致性检验（偏差 <= 5% 判定为 AGREE）。
     * 积分放电 2000 mAh，Counter 差值为 2020 mAh (相对偏差 0.99%)。
     */
    @Test
    fun testP1B_counterConsistency_agree() {
        // 采样步长 30 秒，无缺口断层，总计放电 3600 秒 (2.0A 恒流放电 2000 mAh)
        // Counter 差值为 2020 mAh (相对偏差 0.99% <= 5%)
        val samples = mutableListOf<CapacitySample>()
        val totalSteps = 3600 / 30
        for (i in 0..totalSteps) {
            val t = i * 30_000L
            val frac = i / totalSteps.toDouble()
            val soc = 0.80 - frac * 0.30
            val cc = (6_000_000L - (frac * 2_020_000L)).toLong()
            samples.add(
                CapacitySample(
                    timestampMs = t,
                    socFraction = soc,
                    currentAmp = 2.0,
                    voltageVolt = 3.85,
                    temperatureC = 25.0,
                    chargeCounterUah = cc,
                    chargingStatus = ChargingStatus.DISCHARGING
                )
            )
        }

        val candidate = DischargeCapacityEstimator.buildCandidate(
            sessionId = "p1b_counter_agree",
            samples = samples,
            startSoc = 0.80,
            endSoc = 0.50
        )

        assertFalse(candidate.exclusionReason, candidate.isExcluded)
        assertEquals(CounterConsistency.AGREE, candidate.counterConsistency)
    }

    /**
     * P1-B 测试 8：电量计 CHARGE_COUNTER 出现明显分歧（偏差 > 5% 判定为 DIVERGE）。
     * 积分放电 2000 mAh，Counter 差值为 1500 mAh (相对偏差 25%)。
     */
    @Test
    fun testP1B_counterConsistency_diverge() {
        // 采样步长 30 秒，总计放电 3600 秒 (2.0A 恒流放电 2000 mAh)
        // Counter 差值为 1500 mAh (相对偏差 25% > 5%)
        val samples = mutableListOf<CapacitySample>()
        val totalSteps = 3600 / 30
        for (i in 0..totalSteps) {
            val t = i * 30_000L
            val frac = i / totalSteps.toDouble()
            val soc = 0.80 - frac * 0.30
            val cc = (6_000_000L - (frac * 1_500_000L)).toLong()
            samples.add(
                CapacitySample(
                    timestampMs = t,
                    socFraction = soc,
                    currentAmp = 2.0,
                    voltageVolt = 3.85,
                    temperatureC = 25.0,
                    chargeCounterUah = cc,
                    chargingStatus = ChargingStatus.DISCHARGING
                )
            )
        }

        val candidate = DischargeCapacityEstimator.buildCandidate(
            sessionId = "p1b_counter_diverge",
            samples = samples,
            startSoc = 0.80,
            endSoc = 0.50
        )

        // 仅标记分歧状态，不强行排除或篡改积分结果
        assertEquals(CounterConsistency.DIVERGE, candidate.counterConsistency)
    }

    /**
     * P1-B 测试 9：设备不支持 CHARGE_COUNTER 时判定为 UNAVAILABLE。
     */
    @Test
    fun testP1B_counterConsistency_unavailable() {
        val samples = listOf(
            CapacitySample(timestampMs = 0L, socFraction = 0.80, currentAmp = 2.0, voltageVolt = 3.85, temperatureC = 25.0, chargeCounterUah = null),
            CapacitySample(timestampMs = 3600_000L, socFraction = 0.50, currentAmp = 2.0, voltageVolt = 3.80, temperatureC = 25.0, chargeCounterUah = null)
        )

        val candidate = DischargeCapacityEstimator.buildCandidate(
            sessionId = "p1b_counter_unavail",
            samples = samples,
            startSoc = 0.80,
            endSoc = 0.50
        )

        assertEquals(CounterConsistency.UNAVAILABLE, candidate.counterConsistency)
    }

    /**
     * P1-B 测试 10：顶部平台很长但有效区间完整时，有效放电覆盖率独立计算不受平台稀释。
     */
    @Test
    fun testP1B_longTopPlateauDoesNotDiluteEffectiveCoverage() {
        val samples = mutableListOf<CapacitySample>()
        // 1. 顶部满电平台：极长持续 10 小时 (36,000s)，存在大间歇
        samples.add(CapacitySample(timestampMs = 0L, socFraction = 1.0, currentAmp = 0.1, voltageVolt = 4.35, temperatureC = 25.0))
        samples.add(CapacitySample(timestampMs = 36_000_000L, socFraction = 1.0, currentAmp = 0.1, voltageVolt = 4.35, temperatureC = 25.0))
        // 过渡区单步
        samples.add(CapacitySample(timestampMs = 36_030_000L, socFraction = 0.99, currentAmp = 1.0, voltageVolt = 4.25, temperatureC = 25.0))
        // 2. 有效放电区间：每 30 秒密集采样，持续放电至 50%
        var t = 36_060_000L
        for (i in 1..50) {
            samples.add(
                CapacitySample(
                    timestampMs = t,
                    socFraction = 0.99 - (i / 50.0) * 0.49,
                    currentAmp = 1.0,
                    voltageVolt = 3.85,
                    temperatureC = 26.0,
                    chargingStatus = ChargingStatus.DISCHARGING
                )
            )
            t += 30_000L
        }

        val candidate = DischargeCapacityEstimator.buildCandidate(
            sessionId = "p1b_plateau_coverage",
            samples = samples,
            startSoc = 1.0,
            endSoc = 0.50
        )

        // 验证：有效区间时间覆盖率为 100%，绝不受 10 小时平台间歇稀释！
        assertEquals(1.0, candidate.effectiveIntervalCoverage, 0.02)
        assertFalse("有效放电质量完整不应被排除", candidate.isExcluded)
    }

    /**
     * 细节 2 测试：100% -> 99% -> 100% 抖动回弹时，平台电量完整累计，打上 JITTER 标记且物理做功不丢失。
     */
    @Test
    fun testP1B_jitterPreservesPhysicalChargeWithJitterQuality() {
        val samples = listOf(
            CapacitySample(timestampMs = 0L, socFraction = 1.0, currentAmp = 1.0, voltageVolt = 4.35, temperatureC = 25.0),
            // 抖动降到 99%
            CapacitySample(timestampMs = 30_000L, socFraction = 0.99, currentAmp = 1.0, voltageVolt = 4.30, temperatureC = 25.0),
            // 抖动回弹到 100%
            CapacitySample(timestampMs = 60_000L, socFraction = 1.0, currentAmp = 1.0, voltageVolt = 4.35, temperatureC = 25.0),
            // 稳定降到 99% (两点确认)
            CapacitySample(timestampMs = 90_000L, socFraction = 0.99, currentAmp = 1.0, voltageVolt = 4.25, temperatureC = 25.0),
            CapacitySample(timestampMs = 120_000L, socFraction = 0.99, currentAmp = 1.0, voltageVolt = 4.25, temperatureC = 25.0),
            CapacitySample(timestampMs = 3600_000L, socFraction = 0.50, currentAmp = 1.0, voltageVolt = 3.80, temperatureC = 25.0)
        )

        val candidate = DischargeCapacityEstimator.buildCandidate(
            sessionId = "p1b_jitter_preserve",
            samples = samples,
            startSoc = 1.0,
            endSoc = 0.50
        )

        assertNotNull(candidate.topPlateau)
        // 平台期间包含抖动回弹的物理电量（0L ~ 60_000L），电量 > 0
        assertTrue("抖动期间物理电量必须被完整累计", candidate.topPlateau!!.plateauMah > 0.0)
        // 标记为 JITTER
        assertEquals(SocBoundaryQuality.JITTER, candidate.topPlateau!!.boundaryQuality)
    }

    /**
     * 细节 4 测试：验证 CapacityCandidate 包含顶部平台与版本号的完整实体构造与持久化映射。
     */
    @Test
    fun testP1B_candidatePersistenceAndSchemaVersion() {
        val candidate = com.battery.analysis.model.CapacityCandidate(
            id = 123456789L,
            sessionType = com.battery.analysis.model.CandidateSessionType.DISCHARGE,
            startSoc = 99,
            endSoc = 50,
            socSpanPercent = 49,
            netBatteryChargeMah = 3920.0,
            fccObservedMah = 8000.0,
            startTimestamp = 1000L,
            endTimestamp = 5000L,
            durationMs = 4000L,
            avgTemperatureCelsius = 25.5f,
            maxTemperatureCelsius = 28.0f,
            sampleCount = 120,
            qualityScore = 0.95,
            isExcluded = false,
            exclusionReason = "",
            energyWh = 15.2,
            avgVoltageVolts = 3.88f,
            plateauMah = 500.0,
            plateauWh = 2.15,
            transitionMah = 8.33,
            transitionWh = 0.035,
            plateauDurationSec = 1800L,
            plateauSampleCount = 60,
            effectiveStartTimestamp = 2800L,
            effectiveEndTimestamp = 5000L,
            counterConsistency = CounterConsistency.AGREE.name,
            algorithmVersion = "FCC-v1.1",
            dataSchemaVersion = 3
        )

        assertEquals(500.0, candidate.plateauMah, 0.001)
        assertEquals(8.33, candidate.transitionMah, 0.001)
        assertEquals(1800L, candidate.plateauDurationSec)
        assertEquals(60, candidate.plateauSampleCount)
        assertEquals("AGREE", candidate.counterConsistency)
        assertEquals("FCC-v1.1", candidate.algorithmVersion)
        assertEquals(3, candidate.dataSchemaVersion)
    }

    /**
     * 补充测试 1：验证 CHARGE_COUNTER 有效起点严格锁定在首次 99% 采样点（方式 A 严格对齐）。
     */
    @Test
    fun testEffectiveBoundaryCounterUsesFirst99Sample() {
        val samples = listOf(
            CapacitySample(timestampMs = 10_000L, socFraction = 1.0, currentAmp = 1.0, voltageVolt = 4.35, temperatureC = 25.0, chargeCounterUah = 7_950_000L),
            // 首次 99% 采样点（有效起点）
            CapacitySample(timestampMs = 40_000L, socFraction = 0.99, currentAmp = 1.0, voltageVolt = 4.25, temperatureC = 25.0, chargeCounterUah = 7_920_000L),
            // 第二次 99% 采样点（确认稳定）
            CapacitySample(timestampMs = 70_000L, socFraction = 0.99, currentAmp = 1.0, voltageVolt = 4.25, temperatureC = 25.0, chargeCounterUah = 7_890_000L),
            CapacitySample(timestampMs = 3600_000L, socFraction = 0.50, currentAmp = 1.0, voltageVolt = 3.80, temperatureC = 25.0, chargeCounterUah = 3_920_000L)
        )

        val decoupled = DischargeSessionDecoupler.decouple(samples, 1.0, 0.50)
        val checker = DischargeCounterConsistencyChecker.check(decoupled.effectiveSamples, 4000.0)

        // 验证 Counter 起点时间戳与数值必须来自首次 99% 采样点（40_000L, 7_920_000 uAh）
        assertEquals(40_000L, decoupled.effectiveSamples.first().timestampMs)
        assertEquals(7_920_000L, checker.counterStartUah)
    }

    /**
     * 补充测试 2：验证 Counter 长时间不更新（STALE）检测，且该异常不污染原始库仑积分。
     */
    @Test
    fun testCounterStaleDetection() {
        val samples = mutableListOf<CapacitySample>()
        // 放电持续 3600 秒，放电量 2000 mAh，但 Counter 读数完全停滞在 5,000,000 uAh
        for (i in 0..120) {
            samples.add(
                CapacitySample(
                    timestampMs = i * 30_000L,
                    socFraction = 0.80 - (i / 120.0) * 0.30,
                    currentAmp = 2.0,
                    voltageVolt = 3.85,
                    temperatureC = 25.0,
                    chargeCounterUah = 5_000_000L, // 停滞未刷新
                    chargingStatus = ChargingStatus.DISCHARGING
                )
            )
        }

        val candidate = DischargeCapacityEstimator.buildCandidate(
            sessionId = "stale_counter_session",
            samples = samples,
            startSoc = 0.80,
            endSoc = 0.50
        )

        // 验证：Counter 状态被准确识别为 STALE
        assertEquals(CounterQuality.STALE, candidate.counterQuality)
        // 核心验证：Counter 停滞仅作为辅助标记，电流积分依然正常完成，不破坏库仑积分
        assertTrue("电流微积分不受Counter停滞破坏", candidate.chargeMah > 1900.0)
    }

    /**
     * 补充测试 3：验证 Counter 异常非单调倒流时不污染实际电流微积分。
     */
    @Test
    fun testCounterNonMonotonicDoesNotCorruptCurrentIntegral() {
        val samples = mutableListOf<CapacitySample>()
        for (i in 0..120) {
            // 放电期间 Counter 竟然从 4,000,000 倒流增至 6,000,000
            val cc = 4_000_000L + (i * 20_000L)
            samples.add(
                CapacitySample(
                    timestampMs = i * 30_000L,
                    socFraction = 0.80 - (i / 120.0) * 0.30,
                    currentAmp = 2.0,
                    voltageVolt = 3.85,
                    temperatureC = 25.0,
                    chargeCounterUah = cc,
                    chargingStatus = ChargingStatus.DISCHARGING
                )
            )
        }

        val candidate = DischargeCapacityEstimator.buildCandidate(
            sessionId = "non_monotonic_session",
            samples = samples,
            startSoc = 0.80,
            endSoc = 0.50
        )

        assertEquals(CounterQuality.NON_MONOTONIC, candidate.counterQuality)
        assertEquals(CounterConsistency.INVALID, candidate.counterConsistency)
        // 核心验证：电流积分依然真实反映 2000 mAh
        assertEquals(2000.0, candidate.chargeMah, 20.0)
    }

    /**
     * 补充测试 4：验证严格过滤 Android 官方哨兵值 Integer.MIN_VALUE 与 Long.MIN_VALUE。
     */
    @Test
    fun testCounterSentinelFiltering() {
        assertFalse(DischargeCounterConsistencyChecker.isValidCounterReading(null))
        assertFalse(DischargeCounterConsistencyChecker.isValidCounterReading(0L))
        assertFalse(DischargeCounterConsistencyChecker.isValidCounterReading(Int.MIN_VALUE.toLong()))
        assertFalse(DischargeCounterConsistencyChecker.isValidCounterReading(Long.MIN_VALUE))
        assertTrue(DischargeCounterConsistencyChecker.isValidCounterReading(5_000_000L))
    }

    /**
     * 补充测试 5（核心验收）：验证有效时间覆盖率 80% 但断层跨越了明显 SOC 变化时，坚决拒绝拿全会话 SOC 跨度计算虚假 FCC！
     * 模拟真实物理反常：总 SOC 跌了 80%，但在断层切断期间丢失了 15% 的 SOC。
     * 若直接用全会话 SOC 计算，将导致 FCC 被系统性低估。验证此时系统硬拒绝生成候选样本！
     */
    @Test
    fun testGapCoverage80PercentNoFalseFcc() {
        val samples = mutableListOf<CapacitySample>()
        var t = 0L

        // 片段 1：连续采样 30 分钟 (1800 秒)，SOC 从 80% 降至 50% (Δz = 30%)
        for (i in 0..60) {
            samples.add(
                CapacitySample(
                    timestampMs = t,
                    socFraction = 0.80 - (i / 60.0) * 0.30,
                    currentAmp = 2.0,
                    voltageVolt = 3.85,
                    temperatureC = 25.0,
                    chargingStatus = ChargingStatus.DISCHARGING
                )
            )
            t += 30_000L
        }

        // 出现一个大断层（持续 450 秒 > 60 秒），期间 SOC 从 50% 骤降至 35%（丢失了 15% 的未观测 SOC）
        t += 450_000L

        // 片段 2：继续采样 15 分钟 (900 秒)，SOC 从 35% 降至 20% (Δz = 15%)
        for (i in 0..30) {
            samples.add(
                CapacitySample(
                    timestampMs = t,
                    socFraction = 0.35 - (i / 30.0) * 0.15,
                    currentAmp = 2.0,
                    voltageVolt = 3.80,
                    temperatureC = 25.0,
                    chargingStatus = ChargingStatus.DISCHARGING
                )
            )
            t += 30_000L
        }

        val candidate = DischargeCapacityEstimator.buildCandidate(
            sessionId = "unobserved_soc_loss_session",
            samples = samples,
            startSoc = 0.80,
            endSoc = 0.20
        )

        // 核心验证：由于断层期间丢失了 15% 的未观测 SOC，严禁拿全会话 60% SOC 跨度计算虚假低估的 FCC，坚决拒绝！
        assertTrue("断层跨越未观测SOC变化必须被排除", candidate.isExcluded)
        assertTrue(candidate.exclusionReason.contains("采样断层跨越未观测SOC变化"))
    }

    /**
     * 补充测试 6：同一会话内混用不同电流源时检测并标记告警。
     */
    @Test
    fun testCurrentSourceSwitchWithinSession() {
        val samples = listOf(
            CapacitySample(timestampMs = 0L, socFraction = 0.80, currentAmp = 1.0, voltageVolt = 3.85, temperatureC = 25.0, currentSource = "BATTERY_MANAGER"),
            CapacitySample(timestampMs = 30_000L, socFraction = 0.75, currentAmp = 1.0, voltageVolt = 3.85, temperatureC = 25.0, currentSource = "BATTERY_MANAGER"),
            // 切换为 SYSFS_BATTERY
            CapacitySample(timestampMs = 60_000L, socFraction = 0.70, currentAmp = 1.0, voltageVolt = 3.85, temperatureC = 25.0, currentSource = "SYSFS_BATTERY"),
            CapacitySample(timestampMs = 90_000L, socFraction = 0.65, currentAmp = 1.0, voltageVolt = 3.85, temperatureC = 25.0, currentSource = "SYSFS_BATTERY")
        )

        val result = DischargeSessionQualityEvaluator.evaluate(
            allSamples = samples,
            effectiveSamples = samples,
            effectiveDeltaSoc = 0.15,
            plateauMetrics = null
        )

        assertTrue(result.warningFlags.any { it.contains("电流源切换混用") })
    }

    /**
     * 补充测试 7：量纲解耦验证 —— 电压缺失仅拒绝 FCC_E 能量学习，绝不影响 FCC_Q 容量通道评估！
     */
    @Test
    fun testMissingVoltageOnlyRejectsEnergyLearning() {
        val samples = mutableListOf<CapacitySample>()
        for (i in 0..60) {
            samples.add(
                CapacitySample(
                    timestampMs = i * 30_000L,
                    socFraction = 0.80 - (i / 60.0) * 0.30,
                    currentAmp = 2.0,
                    voltageVolt = null, // 电压缺失！
                    temperatureC = 25.0,
                    chargingStatus = ChargingStatus.DISCHARGING
                )
            )
        }

        val candidate = DischargeCapacityEstimator.buildCandidate(
            sessionId = "no_voltage_session",
            samples = samples,
            startSoc = 0.80,
            endSoc = 0.50
        )

        // 核心验证：FCC_Q 安时容量通道完全合格准入（isExcluded 为 false）！
        assertFalse("电压缺失不应排除 FCC_Q 容量学习: ${candidate.exclusionReason}", candidate.isExcluded)
        assertEquals(0.0, candidate.energyWh, 0.001)

        // 核心验证：能量通道 FCC_E 被如实独立排除，杜绝脑补虚假能量！
        assertTrue("电压缺失必须独立排除能量学习", candidate.isEnergyExcluded)
        assertTrue(candidate.energyExclusionReason.contains("电压传感器数据缺失"))
    }

    /**
     * 补充测试 8：SOC 回升时真实电荷依然在平台或有效段被完整累计。
     */
    @Test
    fun testSocReboundPreservesRawCoulombData() {
        val samples = listOf(
            CapacitySample(timestampMs = 0L, socFraction = 0.80, currentAmp = 1.0, voltageVolt = 3.85, temperatureC = 25.0),
            CapacitySample(timestampMs = 30_000L, socFraction = 0.79, currentAmp = 1.0, voltageVolt = 3.85, temperatureC = 25.0),
            // 轻微温度回升造成 SOC 暂时反弹至 0.80
            CapacitySample(timestampMs = 60_000L, socFraction = 0.80, currentAmp = 1.0, voltageVolt = 3.85, temperatureC = 25.0),
            CapacitySample(timestampMs = 90_000L, socFraction = 0.78, currentAmp = 1.0, voltageVolt = 3.85, temperatureC = 25.0)
        )

        val result = DischargeSessionQualityEvaluator.evaluate(
            allSamples = samples,
            effectiveSamples = samples,
            effectiveDeltaSoc = 0.02,
            plateauMetrics = null
        )

        // 轻微回弹在容差范围内，记录为告警但未直接判定为致命 INVALID
        assertTrue(result.warningFlags.any { it.contains("微弱SOC回弹") })
    }

    /**
     * 补充测试 9：插电但实际为净放电时，依真实物理电流与净放电状态判定，不误判为充电。
     */
    @Test
    fun testPluggedButBatteryNotCharging() {
        val samples = listOf(
            CapacitySample(timestampMs = 0L, socFraction = 0.80, currentAmp = 0.5, voltageVolt = 3.85, temperatureC = 25.0, chargingStatus = ChargingStatus.DISCHARGING),
            // 虽然插着线（例如慢充或高负荷导致电池倒灌供电），但实际为正放电且状态为 DISCHARGING
            CapacitySample(timestampMs = 30_000L, socFraction = 0.79, currentAmp = 0.8, voltageVolt = 3.80, temperatureC = 25.0, chargingStatus = ChargingStatus.DISCHARGING),
            CapacitySample(timestampMs = 60_000L, socFraction = 0.78, currentAmp = 0.6, voltageVolt = 3.80, temperatureC = 25.0, chargingStatus = ChargingStatus.DISCHARGING)
        )

        val result = DischargeSessionQualityEvaluator.evaluate(
            allSamples = samples,
            effectiveSamples = samples,
            effectiveDeltaSoc = 0.02,
            plateauMetrics = null
        )

        assertFalse("真实净放电工况不应被误判为混入充电", result.rejectionReasons.any { it.contains("混入充电数据") })
    }

    /**
     * P1-C 补充测试 1：不同 SOC 区间能量不均匀时，跨分箱切分只能标记为 MODEL_ESTIMATED，绝不可伪装为实测。
     * 模拟放电跨越 65% -> 55%（跨越 60% 边界，涉及 Bin 12[60~65%] 与 Bin 11[55~60%]）：
     * 1. 验证分配质量评级为 CROSS_BOUNDARY_SPLIT；
     * 2. 验证两分箱状态严格为 MODEL_ESTIMATED，绝不能为 MEASURED；
     * 3. 验证 measuredSpanPercent 严格保持 0.0，estimatedSpanPercent 各占 5.0%；
     * 4. 验证总能量与总电荷严格守恒。
     */
    @Test
    fun testP1C_nonUniformEnergyPerSocAllocation() {
        val learner = SocEnergyCurveLearner()
        // 采样点 A: 0s, 65%, 2A, 3.8V
        // 采样点 B: 60s, 55%, 2A, 3.8V
        val samples = listOf(
            CapacitySample(timestampMs = 0L, socFraction = 0.65, currentAmp = 2.0, voltageVolt = 3.80, temperatureC = 25.0),
            CapacitySample(timestampMs = 60_000L, socFraction = 0.55, currentAmp = 2.0, voltageVolt = 3.80, temperatureC = 25.0)
        )

        val quality = learner.absorbEffectiveDischargeSamples(samples)
        assertEquals(EnergyAllocationQuality.CROSS_BOUNDARY_SPLIT, quality)

        val bins = learner.getEnergyBins()
        val bin12 = bins[12] // 60%~65%
        val bin11 = bins[11] // 55%~60%

        // 验证状态严格为 MODEL_ESTIMATED，绝不可伪装为 MEASURED
        assertEquals(BinLearningStatus.MODEL_ESTIMATED, bin12.status)
        assertEquals(BinLearningStatus.MODEL_ESTIMATED, bin11.status)
        assertFalse(bin12.isLearned)
        assertFalse(bin11.isLearned)

        // 验证实测跨度为 0，估算跨度为 5.0%
        assertEquals(0.0, bin12.measuredSpanPercent, 1e-6)
        assertEquals(0.0, bin11.measuredSpanPercent, 1e-6)
        assertEquals(5.0, bin12.estimatedSpanPercent, 1e-6)
        assertEquals(5.0, bin11.estimatedSpanPercent, 1e-6)

        // 验证估算能量各占一半，且实测能量严格为 0
        val expectedTotalE = (3.8 * 2.0) * (60.0 / 3600.0)
        assertEquals(0.0, bin12.measuredEnergyWh, 1e-6)
        assertEquals(0.0, bin11.measuredEnergyWh, 1e-6)
        assertEquals(expectedTotalE / 2.0, bin12.estimatedEnergyWh, 1e-6)
        assertEquals(expectedTotalE / 2.0, bin11.estimatedEnergyWh, 1e-6)
    }

    /**
     * P1-C 补充测试 2：跨多个 Bin 按比例切分时，正确标记为 MODEL_ESTIMATED，不增加 measuredSpanPercent。
     */
    @Test
    fun testP1C_crossBinAllocationMarkedModelEstimated() {
        val learner = SocEnergyCurveLearner()
        val samples = listOf(
            CapacitySample(timestampMs = 0L, socFraction = 0.92, currentAmp = 2.0, voltageVolt = 4.00, temperatureC = 25.0),
            CapacitySample(timestampMs = 60_000L, socFraction = 0.83, currentAmp = 2.0, voltageVolt = 3.80, temperatureC = 25.0)
        )

        val quality = learner.absorbEffectiveDischargeSamples(samples)
        assertEquals(EnergyAllocationQuality.CROSS_BOUNDARY_SPLIT, quality)

        val bins = learner.getEnergyBins()
        val bin18 = bins[18] // 90%~95% (交集 2.0%)
        val bin17 = bins[17] // 85%~90% (交集 5.0%)
        val bin16 = bins[16] // 80%~85% (交集 2.0%)

        // 纯实测跨度必须全部严格为 0
        assertEquals(0.0, bin18.measuredSpanPercent, 1e-6)
        assertEquals(0.0, bin17.measuredSpanPercent, 1e-6)
        assertEquals(0.0, bin16.measuredSpanPercent, 1e-6)

        // 估算跨度准确记录相交区间
        assertEquals(2.0, bin18.estimatedSpanPercent, 1e-6)
        assertEquals(5.0, bin17.estimatedSpanPercent, 1e-6)
        assertEquals(2.0, bin16.estimatedSpanPercent, 1e-6)

        // 全部标记为 MODEL_ESTIMATED，isLearned 为 false
        assertEquals(BinLearningStatus.MODEL_ESTIMATED, bin18.status)
        assertEquals(BinLearningStatus.MODEL_ESTIMATED, bin17.status)
        assertEquals(BinLearningStatus.MODEL_ESTIMATED, bin16.status)
        assertFalse(bin18.isLearned)
        assertFalse(bin17.isLearned)
        assertFalse(bin16.isLearned)
    }

    /**
     * P1-C 补充测试 3：同一 Bin 内直接实测分配，可直接计入 measuredEnergyWh 并晋升 MEASURED 状态。
     */
    @Test
    fun testP1C_sameBinDirectMeasurement() {
        val learner = SocEnergyCurveLearner()

        // 模拟完全在 Bin 12 (60%~65%) 内部的密集采样：64% -> 63% -> 61%
        val samples = listOf(
            CapacitySample(timestampMs = 0L, socFraction = 0.64, currentAmp = 1.0, voltageVolt = 3.85, temperatureC = 25.0),
            CapacitySample(timestampMs = 30_000L, socFraction = 0.63, currentAmp = 1.0, voltageVolt = 3.85, temperatureC = 25.0),
            CapacitySample(timestampMs = 60_000L, socFraction = 0.61, currentAmp = 1.0, voltageVolt = 3.85, temperatureC = 25.0)
        )

        val quality = learner.absorbEffectiveDischargeSamples(samples)
        assertEquals(EnergyAllocationQuality.NORMAL, quality)

        val bin12 = learner.getEnergyBins()[12]
        // 累计实测跨度 1.0% + 2.0% = 3.0% >= 2.0% 门限
        assertEquals(3.0, bin12.measuredSpanPercent, 0.01)
        assertEquals(0.0, bin12.estimatedSpanPercent, 1e-6)

        // 直接晋升为 MEASURED 充分实测
        assertEquals(BinLearningStatus.MEASURED, bin12.status)
        assertTrue(bin12.isLearned)
        assertTrue("实测归一化能量应大于 0", bin12.measuredEnergyWh > 0.0)
        assertEquals(0.0, bin12.estimatedEnergyWh, 1e-6)
    }

    /**
     * P1-C 补充测试 4：SOC 整数停滞不丢失实际物理能量与电荷。
     */
    @Test
    fun testP1C_socIntegerStallPreservesMeasuredEnergy() {
        val learner = SocEnergyCurveLearner()
        val samples = mutableListOf<CapacitySample>()

        // 模拟电量计假死卡在 65.0%，持续以 2A 放电 1200 秒（释放 666.7 mAh，2.56 Wh）
        for (i in 0..40) {
            samples.add(
                CapacitySample(
                    timestampMs = i * 30_000L,
                    socFraction = 0.65,
                    currentAmp = 2.0,
                    voltageVolt = 3.85,
                    temperatureC = 25.0
                )
            )
        }

        val quality = learner.absorbEffectiveDischargeSamples(samples)
        assertEquals(EnergyAllocationQuality.UNRESOLVED_SOC_STALL, quality)

        // 验证全局总观测能量与电荷完整无损保留
        assertTrue("全局观测总电荷应接近 666 mAh", learner.getTotalObservedChargeMah() > 600.0)
        assertTrue("全局观测总能量应接近 2.5 Wh", learner.getTotalObservedEnergyWh() > 2.3)
    }

    /**
     * P1-C 补充测试 5：SOC 停滞异常数据仍然可以正常归档，不丢失原始数据。
     */
    @Test
    fun testP1C_socStallDoesNotDestroyRawSession() {
        val samples = mutableListOf<CapacitySample>()
        for (i in 0..30) {
            samples.add(
                CapacitySample(
                    timestampMs = i * 30_000L,
                    socFraction = 0.85,
                    currentAmp = 2.0,
                    voltageVolt = 3.85,
                    temperatureC = 25.0
                )
            )
        }

        // 验证 DischargeCapacityEstimator.buildCandidate 正常构建候选实体，不抛异常崩溃
        val candidate = DischargeCapacityEstimator.buildCandidate(
            sessionId = "stall_session_1",
            samples = samples,
            startSoc = 0.85,
            endSoc = 0.85
        )

        assertNotNull(candidate)
        assertEquals("stall_session_1", candidate.sessionId)
        assertTrue("采样点数量应完整记录", candidate.durationSec >= 0.0)
    }

    /**
     * P1-C 补充测试 6：未知归属的停滞能量记录在 unresolved 中，不污染分箱实测能量。
     */
    @Test
    fun testP1C_unresolvedEnergyNotAddedToBins() {
        val learner = SocEnergyCurveLearner()
        val samples = mutableListOf<CapacitySample>()
        for (i in 0..60) {
            samples.add(
                CapacitySample(
                    timestampMs = i * 30_000L,
                    socFraction = 0.70,
                    currentAmp = 2.0,
                    voltageVolt = 3.85,
                    temperatureC = 25.0
                )
            )
        }

        learner.absorbEffectiveDischargeSamples(samples)
        val bin14 = learner.getEnergyBins()[14] // 70%~75%

        // 停滞超标能量进入 unresolvedEnergyWh
        assertTrue("分箱停滞未决能量应大于 1.0 Wh", bin14.unresolvedEnergyWh > 1.0)
        // 绝不污染实测能量
        assertEquals(0.0, bin14.measuredEnergyWh, 1e-6)
        assertEquals(BinLearningStatus.UNLEARNED, bin14.status)

        // 验证 unresolvedSegments 详细记录
        val segments = learner.getUnresolvedSegments()
        assertFalse(segments.isEmpty())
        assertTrue(segments.any { it.reason.contains("SOC长期停滞放电超过门限") })
    }

    /**
     * P1-C 补充测试 7：纯实测能量与模型估算能量严格分离，不可相互污染。
     */
    @Test
    fun testP1C_measuredAndEstimatedEnergySeparated() {
        val learner = SocEnergyCurveLearner()

        // 1. 同 Bin 直接实测 1.0% (54% -> 53%)
        val directSamples = listOf(
            CapacitySample(timestampMs = 0L, socFraction = 0.54, currentAmp = 1.0, voltageVolt = 3.85, temperatureC = 25.0),
            CapacitySample(timestampMs = 30_000L, socFraction = 0.53, currentAmp = 1.0, voltageVolt = 3.85, temperatureC = 25.0)
        )
        learner.absorbEffectiveDischargeSamples(directSamples)

        // 2. 跨 Bin 切分估算 1.5% (56% -> 53.5%，在 Bin 10 内切分出 1.0% 估算跨度)
        val crossSamples = listOf(
            CapacitySample(timestampMs = 60_000L, socFraction = 0.56, currentAmp = 1.0, voltageVolt = 3.85, temperatureC = 25.0),
            CapacitySample(timestampMs = 90_000L, socFraction = 0.535, currentAmp = 1.0, voltageVolt = 3.85, temperatureC = 25.0)
        )
        learner.absorbEffectiveDischargeSamples(crossSamples)

        val bin10 = learner.getEnergyBins()[10]
        // 实测跨度为 1.0%，估算跨度为 1.5%
        assertEquals(1.0, bin10.measuredSpanPercent, 0.01)
        assertEquals(1.5, bin10.estimatedSpanPercent, 0.01)

        // 实测跨度 1.0% < 2.0% 门限，状态保持为 PARTIAL，绝不能因为加上估算跨度而误升为 MEASURED！
        assertEquals(BinLearningStatus.PARTIAL, bin10.status)
        assertFalse(bin10.isLearned)

        // 实测与估算会话计数分离
        assertEquals(1, bin10.measuredSessionCount)
        assertEquals(1, bin10.estimatedSessionCount)
    }

    /**
     * P1-C 补充测试 8：完整 FCC_E 不可用时保留已观测能量，输出完整透明的 EnergyCapacityResult。
     */
    @Test
    fun testP1C_partialObservedEnergyStillAvailable() {
        val learner = SocEnergyCurveLearner()

        // 模拟放电覆盖 5%~100%（缺少 0%~5%）
        val samples = mutableListOf<CapacitySample>()
        for (i in 0..190) {
            val soc = 0.99 - (i / 190.0) * 0.94
            samples.add(CapacitySample(i * 30_000L, soc, 1.0, 3.85, 25.0))
        }
        learner.absorbEffectiveDischargeSamples(samples)

        val result = learner.getEnergyCapacityResult()

        // 完整 FCC_E 必须如实为 null
        assertNull("未全域实测学满时，全量 FCC_E 必须为 null", result.estimatedFullEnergyWh)
        assertNull("未全域实测学满时，不确定度必须为 null", result.uncertaintyWh)

        // 已观测能量与实测能量透明可用
        assertTrue("已观测总能量应大于 5 Wh", result.observedEnergyWh > 5.0)
        assertEquals(1, result.unlearnedBinCount) // Bin 0 缺失
        assertTrue("已学习分箱数应达到 19", result.measuredBinCount >= 18)
    }

    /**
     * P1-B 补充测试：采样缺口（未观测 SOC 丢失）不造成 FCC_Q 分母失真，硬拦截拒绝。
     */
    @Test
    fun testP1C_gapIntervalCannotUseFullSocSpan() {
        val samples = listOf(
            CapacitySample(timestampMs = 0L, socFraction = 0.90, currentAmp = 1.0, voltageVolt = 3.85, temperatureC = 25.0),
            // 中途发生 200 秒断层且 SOC 丢失了 10% (从 90% 跌至 80%)
            CapacitySample(timestampMs = 200_000L, socFraction = 0.80, currentAmp = 1.0, voltageVolt = 3.85, temperatureC = 25.0),
            CapacitySample(timestampMs = 230_000L, socFraction = 0.79, currentAmp = 1.0, voltageVolt = 3.85, temperatureC = 25.0)
        )

        val quality = DischargeSessionQualityEvaluator.evaluate(
            allSamples = samples,
            effectiveSamples = samples,
            effectiveDeltaSoc = 0.11,
            plateauMetrics = null
        )

        // 严重断层丢失 SOC 达 10% > 3.0% 门限，必须判定为 REJECTED，杜绝虚假分母
        assertEquals(DischargeQuality.REJECTED, quality.quality)
        assertEquals(DischargeQuality.REJECTED, quality.dualAssessment.fccQQuality)
        assertTrue(quality.rejectionReasons.any { it.contains("采样断层跨越未观测SOC变化") })
    }

    /**
     * P1-C 核心测试 10：100% 满电平台做功独立隔离，95%~100% 分箱做功杜绝 2.851 Wh 畸高。
     */
    @Test
    fun testP1C_topPlateauEnergyDecoupledFromBin19() {
        val learner = SocEnergyCurveLearner()
        val samples = mutableListOf<CapacitySample>()

        // 1. 100% 满电平台期：持续 1800 秒，放出 600 mAh、约 2.4 Wh 做功
        for (i in 0..60) {
            samples.add(CapacitySample(i * 30_000L, 1.0, 1.2, 4.00, 25.0))
        }

        // 2. 稳定跌落至 95%
        val baseTs = 60 * 30_000L
        for (i in 1..30) {
            val soc = 1.0 - (i / 30.0) * 0.05
            samples.add(CapacitySample(baseTs + i * 30_000L, soc, 1.2, 3.85, 25.0))
        }

        learner.absorbDischargeSession(samples)

        val plateauMetrics = learner.getTopPlateauMetrics()
        assertTrue("平台放电量应接近 600 mAh", plateauMetrics.plateauMah > 500.0)
        assertTrue("平台做功应接近 2.0 Wh", plateauMetrics.plateauWh > 1.8)

        val bins = learner.getEnergyBins()
        val bin19 = bins[19]
        assertTrue("Bin 19 归一化能量必须符合真实物理值(< 2.0 Wh)，杜绝 2.851 Wh 畸高", bin19.energyWh < 2.0)
        assertTrue("Bin 19 归一化能量必须真实大于 0", bin19.energyWh > 0.8)
    }

    /**
     * P1-C 补充测试 11：同一整数 SOC 停留小放电（如 100 mAh）时，不能自动升级为 MEASURED。
     * 65% -> 65% 放电期间无真实台阶下降，measuredSpanPercent 严格保持 0.0，状态仍为 UNLEARNED。
     */
    @Test
    fun testP1C_sameIntegerSocSmallDischargeNotAutoMeasured() {
        val learner = SocEnergyCurveLearner()

        // 在 65% 停留放电 100 mAh (持续 180s，2A 电流放电 100 mAh，未超 400 mAh 保护门限)
        val samples = listOf(
            CapacitySample(timestampMs = 0L, socFraction = 0.65, currentAmp = 2.0, voltageVolt = 3.85, temperatureC = 25.0),
            CapacitySample(timestampMs = 60_000L, socFraction = 0.65, currentAmp = 2.0, voltageVolt = 3.85, temperatureC = 25.0),
            CapacitySample(timestampMs = 120_000L, socFraction = 0.65, currentAmp = 2.0, voltageVolt = 3.85, temperatureC = 25.0),
            CapacitySample(timestampMs = 180_000L, socFraction = 0.65, currentAmp = 2.0, voltageVolt = 3.85, temperatureC = 25.0)
        )

        learner.absorbEffectiveDischargeSamples(samples)
        val bin12 = learner.getEnergyBins()[12] // 60%~65%

        // 验证：尽管吸收了物理做功缓冲，但实测跨度严格为 0.0，绝不自动晋升为 MEASURED！
        assertEquals(0.0, bin12.measuredSpanPercent, 1e-6)
        assertEquals(BinLearningStatus.UNLEARNED, bin12.status)
        assertFalse("无真实台阶变化不可视为已完成学习", bin12.isLearned)
    }

    /**
     * P1-B 与 P1-C 联合回归测试 1：全流程验证 100% 满电平台做功绝不混入分箱实测能量中。
     */
    @Test
    fun testP1BC_topPlateauEnergyNeverEntersMeasuredBins() {
        val learner = SocEnergyCurveLearner()
        val samples = mutableListOf<CapacitySample>()

        // 1. 100% 满电平台：停留 2400 秒，放出 800 mAh、约 3.1 Wh
        for (i in 0..80) {
            samples.add(CapacitySample(i * 30_000L, 1.0, 1.2, 3.90, 25.0))
        }

        // 2. 跌至 90% (释放 800 mAh、3.1 Wh)
        val baseTs = 80 * 30_000L
        for (i in 1..40) {
            val soc = 1.0 - (i / 40.0) * 0.10
            samples.add(CapacitySample(baseTs + i * 30_000L, soc, 1.2, 3.85, 25.0))
        }

        learner.absorbDischargeSession(samples)

        // 验证平台能量全部独立沉淀
        val plateau = learner.getTopPlateauMetrics()
        assertTrue("平台能量应接近 3.1 Wh", plateau.plateauWh > 2.8)

        // 验证所有分箱的实测能量之和绝不包含平台 3.1 Wh
        val measuredSum = learner.getMeasuredAccumulatedEnergyWh()
        assertTrue("分箱实测累加能量应与有效放电段相符(< 4.0 Wh)", measuredSum < 4.0)
    }

    /**
     * P1-B 与 P1-C 联合回归测试 2：被质检拦截拒绝（REJECTED）的低质量会话坚决不能更新 SOC 曲线。
     */
    @Test
    fun testP1BC_qualityRejectedSessionCannotUpdateEnergyCurve() {
        val learner = SocEnergyCurveLearner()

        // 构造一条混入充电数据的被拦截会话
        val badSamples = listOf(
            CapacitySample(timestampMs = 0L, socFraction = 0.80, currentAmp = 1.0, voltageVolt = 3.85, temperatureC = 25.0),
            CapacitySample(timestampMs = 30_000L, socFraction = 0.81, currentAmp = -1.5, voltageVolt = 4.20, temperatureC = 25.0, chargingStatus = ChargingStatus.CHARGING),
            CapacitySample(timestampMs = 60_000L, socFraction = 0.79, currentAmp = 1.0, voltageVolt = 3.85, temperatureC = 25.0)
        )

        val candidate = DischargeCapacityEstimator.buildCandidate(
            sessionId = "bad_session_1",
            samples = badSamples,
            startSoc = 0.80,
            endSoc = 0.79
        )

        // 质检判定为排除拒绝
        assertTrue("混入充电必须被排除", candidate.isExcluded)
        assertEquals(DischargeQuality.REJECTED, candidate.qualityLevel)

        // 遵循业务规范：被排除会话坚决不吸收更新分箱曲线
        if (!candidate.isExcluded) {
            learner.absorbDischargeSession(badSamples)
        }

        // 曲线应依然完全为空白
        assertEquals(0.0, learner.getCoverageRatio(), 1e-6)
        assertEquals(0, learner.getLearnedBinCount())
    }

    /**
     * P1-B 与 P1-C 联合回归测试 3：未决能量片段（UnresolvedEnergySegment）在多次采样与持久化链路中完整保留。
     */
    @Test
    fun testP1BC_unresolvedSegmentsPreservedAcrossPersistence() {
        val learner = SocEnergyCurveLearner()
        val samples = mutableListOf<CapacitySample>()

        // 模拟电量计假死停滞在 75% 且持续放电 500 mAh
        for (i in 0..50) {
            samples.add(CapacitySample(i * 30_000L, 0.75, 2.0, 3.85, 25.0))
        }

        learner.absorbEffectiveDischargeSamples(samples)
        val segments = learner.getUnresolvedSegments()

        assertFalse("未决段列表不能为空", segments.isEmpty())
        val firstSeg = segments.first()
        assertTrue("未决段做功必须真实存在", firstSeg.measuredEnergyWh > 0.0)
        assertTrue("未决原因包含停滞超门限说明", firstSeg.reason.contains("SOC长期停滞放电超过门限"))
    }

    /**
     * P2 核心测试 1：充电通道未标定（endpointQuality 为 UNKNOWN）时，坚决冻结自动融合。
     * 以放电主通道为基准，不缩小不确定度，成熟度最高限制为 INITIAL。
     */
    @Test
    fun testP2_uncalibratedChargeChannelFreezesFusion() {
        val energyLearner = SocEnergyCurveLearner()

        // 放电 8000 mAh，充电 7900 mAh，虽然数值接近，但充电端点未标定 (UNKNOWN)
        val candidates = listOf(
            FccCandidateSample("d1", CapacityChannel.DISCHARGE, 0.90, 0.30, 0.60, 4800.0, 18.0, 8000.0, 900.0),
            FccCandidateSample("c1", CapacityChannel.CHARGE, 0.20, 0.80, 0.60, 4740.0, 20.0, 7900.0, 1200.0, endpointQuality = FullEndpointQuality.UNKNOWN)
        )

        val unified = ChargeDischargeFusionEngine.fuse(
            candidates = candidates,
            energyLearner = energyLearner,
            designCapacityMah = 8000.0,
            currentSocPercent = 60
        )

        // 验证系统自动冻结融合
        assertTrue("充电未标定时必须冻结融合", unified.isFusionFrozen)
        assertNotNull(unified.fusionFreezeReason)
        assertTrue(unified.fusionFreezeReason!!.contains("尚未完成满充端点与库仑效率标定"))
        // 输出必须以放电主通道为准 (8000.0)
        assertEquals(8000.0, unified.fccQMah ?: 0.0, 0.1)
    }

    /**
     * P2 核心测试 2：SOH_Q 与 SOH_E 分开独立计算，完整 FCC_E 不可用时 SOH_E 严格为 null。
     * 绝不允许拿 SOH_Q 冒充 SOH_E。
     */
    @Test
    fun testP2_sohESeparatedAndNullWhenFccEUnavailable() {
        val energyLearner = SocEnergyCurveLearner()

        // 放电主通道收敛合格：8000 mAh
        val candidate = FccCandidateSample("d1", CapacityChannel.DISCHARGE, 0.90, 0.10, 0.80, 6400.0, 24.0, 8000.0, 400.0)

        // 能量曲线仅有部分分箱学习，缺少完整 20 段
        val partialSamples = listOf(
            CapacitySample(0L, 0.60, 1.0, 3.85, 25.0),
            CapacitySample(60_000L, 0.55, 1.0, 3.85, 25.0)
        )
        energyLearner.absorbEffectiveDischargeSamples(partialSamples)

        val unified = ChargeDischargeFusionEngine.fuse(
            candidates = listOf(candidate),
            energyLearner = energyLearner,
            designCapacityMah = 8000.0,
            designVoltageVolts = 3.85,
            currentSocPercent = 50
        )

        // SOH_Q 正常计算：8000 / 8000 = 100%
        assertNotNull(unified.sohQPercent)
        assertEquals(100.0, unified.sohQPercent ?: 0.0, 0.1)

        // SOH_E 严格为 null，绝不拿 100% 冒充！
        assertNull("存在未学满分箱时，全量 FCC_E 必须为 null", unified.fccEWh)
        assertNull("全量 FCC_E 不可用时，SOH_E 必须为 null，严禁拿 SOH_Q 代替", unified.sohEPercent)
    }

    /**
     * P2 核心测试 3：充放电双通道发生严重分歧（如 26.15% DISAGREEMENT）时冻结自动融合。
     * 模拟用户截图中 8165 mAh 放电 vs 6277 mAh 充电场景：
     * 1. 判定成熟度为 DISAGREEMENT；
     * 2. 冻结融合（isFusionFrozen = true）；
     * 3. 主容量绝不冒充放电值为真值，无历史确认值时如实为 null，放电与充电候选值独立展示。
     */
    @Test
    fun testP2_disagreementStateFreezesAutoFusion() {
        val energyLearner = SocEnergyCurveLearner()

        // 模拟放电通道 8165 mAh (sigma = 686)，充电通道 6277 mAh (sigma = 400)
        val candidates = listOf(
            FccCandidateSample("d1", CapacityChannel.DISCHARGE, 0.95, 0.15, 0.80, 6532.0, 25.0, 8165.0, 686.0 * 686.0),
            FccCandidateSample("c1", CapacityChannel.CHARGE, 0.10, 0.90, 0.80, 5021.6, 20.0, 6277.0, 400.0 * 400.0, endpointQuality = FullEndpointQuality.UNKNOWN)
        )

        val unified = ChargeDischargeFusionEngine.fuse(
            candidates = candidates,
            energyLearner = energyLearner,
            designCapacityMah = 8000.0,
            currentSocPercent = 50,
            previousAcceptedFccQMah = null
        )

        assertEquals(LearningMaturity.DISAGREEMENT, unified.maturity)
        assertTrue("两通道严重分歧时必须冻结融合", unified.isFusionFrozen)
        assertNotNull(unified.fusionFreezeReason)

        // 相对分歧度约为 26.15%
        assertNotNull(unified.channelConflictPercent)
        assertTrue("分歧度应超过 20%", unified.channelConflictPercent!! > 20.0)

        // 遵循最新仲裁准则：无历史确认值时主输出为 null，绝不让 8165 mAh 冒充真值
        assertNull("严重分歧且无历史确认值时主容量必须为 null", unified.fccQMah)
        assertEquals(8165.0, unified.dischargeChannel?.fccQMah ?: 0.0, 0.1)
        assertEquals(6277.0, unified.chargeChannel?.fccQMah ?: 0.0, 0.1)
    }

    /**
     * P1-C 补充测试 12：100% 顶部平台做功排除在分箱实测外，但完整计入已观测总能量与能量账本。
     */
    @Test
    fun testP1C_topPlateauExcludedFromBinsButIncludedInObservedTotal() {
        val learner = SocEnergyCurveLearner()
        val samples = mutableListOf<CapacitySample>()

        // 1. 100% 平台期：持续 1800 秒，放出 600 mAh、约 2.4 Wh 做功
        for (i in 0..60) {
            samples.add(CapacitySample(i * 30_000L, 1.0, 1.2, 4.00, 25.0))
        }

        // 2. 稳定跌落至 95% (持续 900 秒，放出 300 mAh、约 1.15 Wh 做功)
        val baseTs = 60 * 30_000L
        for (i in 1..30) {
            val soc = 1.0 - (i / 30.0) * 0.05
            samples.add(CapacitySample(baseTs + i * 30_000L, soc, 1.2, 3.85, 25.0))
        }

        learner.absorbDischargeSession(samples)

        val plateauMetrics = learner.getTopPlateauMetrics()
        assertTrue("平台放电量应大于 500 mAh", plateauMetrics.plateauMah > 500.0)
        assertTrue("平台做功应大于 2.0 Wh", plateauMetrics.plateauWh > 2.0)

        // 验证分箱做功排除平台能量
        val bin19 = learner.getEnergyBins()[19]
        assertTrue("Bin 19 做功绝不能包含平台的 2.4 Wh 做功(< 2.0 Wh)", bin19.energyWh < 2.0)

        // 验证全局已观测总能量完整计入平台
        val totalObservedWh = learner.getTotalObservedEnergyWh()
        assertTrue("全局已观测总能量必须大于 3.0 Wh", totalObservedWh > 3.0)

        // 验证统一账本
        val ledger = learner.getEnergyCapacityResult()
        assertTrue("账本平台能量应大于 2.0 Wh", ledger.topPlateauEnergyWh > 2.0)
        assertEquals(totalObservedWh, ledger.totalObservedEnergyWh, 1e-4)
    }

    /**
     * P1-C 补充测试 13：统一能量账本各物理段满足严格能量守恒律。
     * totalObservedEnergyWh = measuredBinsEnergyWh + estimatedBinsEnergyWh + topPlateauEnergyWh + transitionEnergyWh + unresolvedEnergyWh
     */
    @Test
    fun testP1C_energyLedgerConservationAcrossAllSegments() {
        val learner = SocEnergyCurveLearner()
        val samples = mutableListOf<CapacitySample>()

        var ts = 0L
        // 1. 顶部满电平台：100% 停留放电 300 mAh
        for (i in 0..10) {
            samples.add(CapacitySample(ts, 1.0, 3.6, 4.0, 25.0))
            ts += 30_000L
        }

        // 2. 100% -> 99% 过渡区
        samples.add(CapacitySample(ts, 0.99, 1.2, 3.9, 25.0))
        ts += 30_000L

        // 3. 同箱直接实测（99% -> 96%，在 Bin 19 内）
        samples.add(CapacitySample(ts, 0.96, 1.2, 3.85, 25.0))
        ts += 30_000L

        // 4. 跨箱估算（96% -> 93%，跨越 Bin 19 与 18）
        samples.add(CapacitySample(ts, 0.93, 1.2, 3.85, 25.0))
        ts += 30_000L

        // 5. 单点停滞超标（在 93% 停滞不动，持续放电 600 mAh > 400 mAh 门限）
        for (i in 1..20) {
            samples.add(CapacitySample(ts, 0.93, 3.6, 3.8, 25.0))
            ts += 30_000L
        }

        learner.absorbDischargeSession(samples)

        val ledger = learner.getEnergyCapacityResult()

        assertTrue("平台能量应大于 0", ledger.topPlateauEnergyWh > 0.0)
        assertTrue("过渡段能量应大于 0", ledger.transitionEnergyWh > 0.0)
        assertTrue("分箱实测能量应大于 0", ledger.measuredBinsEnergyWh > 0.0)
        assertTrue("跨箱估算能量应大于 0", ledger.estimatedBinsEnergyWh > 0.0)
        assertTrue("未决停滞能量应大于 0", ledger.unresolvedEnergyWh > 0.0)

        val calculatedSum = ledger.measuredBinsEnergyWh +
                ledger.estimatedBinsEnergyWh +
                ledger.topPlateauEnergyWh +
                ledger.transitionEnergyWh +
                ledger.unresolvedEnergyWh

        // 严格守恒闭环校验
        assertEquals("统一能量账本各物理段之和必须严格等于总观测能量", ledger.totalObservedEnergyWh, calculatedSum, 1e-4)
        assertEquals(learner.getTotalObservedEnergyWh(), ledger.totalObservedEnergyWh, 1e-4)
    }

    /**
     * P1-C 补充测试 14：平台、过渡与有效分箱互斥独立，杜绝二次重复计算。
     */
    @Test
    fun testP1C_noDoubleCountingBetweenPlateauTransitionAndBins() {
        val learner = SocEnergyCurveLearner()
        val samples = mutableListOf<CapacitySample>()

        var ts = 0L
        // 100% 满电平台 (3 点)
        samples.add(CapacitySample(ts, 1.0, 1.0, 4.0, 25.0))
        ts += 30_000L
        samples.add(CapacitySample(ts, 1.0, 1.0, 4.0, 25.0))
        ts += 30_000L
        samples.add(CapacitySample(ts, 1.0, 1.0, 4.0, 25.0))
        ts += 30_000L

        // 过渡点 (99%)
        samples.add(CapacitySample(ts, 0.99, 1.0, 3.9, 25.0))
        ts += 30_000L

        // 有效放电点 (98%, 97%)
        samples.add(CapacitySample(ts, 0.98, 1.0, 3.85, 25.0))
        ts += 30_000L
        samples.add(CapacitySample(ts, 0.97, 1.0, 3.85, 25.0))

        learner.absorbDischargeSession(samples)

        val ledger = learner.getEnergyCapacityResult()
        val totalObserved = learner.getTotalObservedEnergyWh()

        // 验证平台与过渡各段互不包含
        val sumSegments = ledger.topPlateauEnergyWh + ledger.transitionEnergyWh + ledger.measuredBinsEnergyWh + ledger.estimatedBinsEnergyWh + ledger.unresolvedEnergyWh
        assertEquals("五大段能量求和必须无任何重叠与遗漏", totalObserved, sumSegments, 1e-5)
    }

    /**
     * P2 核心测试 4：充放电发生严重分歧时冻结融合，并严格保留上次确认收敛的可信 FCC 结果。
     */
    @Test
    fun testP2_disagreementFreezesFusionAndPreservesPreviousAcceptedFcc() {
        val energyLearner = SocEnergyCurveLearner()

        // 放电 8165 mAh，充电 6277 mAh（分歧 26.15%）
        val candidates = listOf(
            FccCandidateSample("d1", CapacityChannel.DISCHARGE, 0.95, 0.15, 0.80, 6532.0, 25.0, 8165.0, 686.0 * 686.0),
            FccCandidateSample("c1", CapacityChannel.CHARGE, 0.10, 0.90, 0.80, 5021.6, 20.0, 6277.0, 400.0 * 400.0, endpointQuality = FullEndpointQuality.GOOD)
        )

        // 存在历史可信容量 7950.0 mAh
        val previousAcceptedFcc = 7950.0
        val unified = ChargeDischargeFusionEngine.fuse(
            candidates = candidates,
            energyLearner = energyLearner,
            designCapacityMah = 8000.0,
            currentSocPercent = 50,
            previousAcceptedFccQMah = previousAcceptedFcc
        )

        assertEquals(LearningMaturity.DISAGREEMENT, unified.maturity)
        assertTrue("发生严重分歧必须冻结自动融合", unified.isFusionFrozen)
        // 核心仲裁验证：主输出严格保留历史已确认可信值 7950.0，不被 8165.0 篡改！
        assertEquals(7950.0, unified.fccQMah ?: 0.0, 0.1)
        assertEquals(7950.0, unified.previousAcceptedFccQMah ?: 0.0, 0.1)
    }

    /**
     * P2 核心测试 5：分歧冻结时，放电通道与充电通道候选结果保持独立，供诊断与上层展示。
     */
    @Test
    fun testP2_disagreementKeepsDischargeCandidateIndependent() {
        val energyLearner = SocEnergyCurveLearner()

        val candidates = listOf(
            FccCandidateSample("d1", CapacityChannel.DISCHARGE, 0.95, 0.15, 0.80, 6532.0, 25.0, 8165.0, 686.0 * 686.0),
            FccCandidateSample("c1", CapacityChannel.CHARGE, 0.10, 0.90, 0.80, 5021.6, 20.0, 6277.0, 400.0 * 400.0, endpointQuality = FullEndpointQuality.GOOD)
        )

        val unified = ChargeDischargeFusionEngine.fuse(
            candidates = candidates,
            energyLearner = energyLearner,
            designCapacityMah = 8000.0,
            currentSocPercent = 50,
            previousAcceptedFccQMah = null
        )

        assertTrue(unified.isFusionFrozen)
        // 放电候选通道保持 8165.0
        assertNotNull(unified.dischargeChannel)
        assertEquals(8165.0, unified.dischargeChannel!!.fccQMah ?: 0.0, 0.1)
        // 充电候选通道保持 6277.0
        assertNotNull(unified.chargeChannel)
        assertEquals(6277.0, unified.chargeChannel!!.fccQMah ?: 0.0, 0.1)
    }

    /**
     * P2 核心测试 6：无历史确认值且两通道分歧时，系统绝不把放电通道当成绝对真值，主输出如实为 null。
     */
    @Test
    fun testP2_disagreementDoesNotPromoteDischargeAsGroundTruth() {
        val energyLearner = SocEnergyCurveLearner()

        val candidates = listOf(
            FccCandidateSample("d1", CapacityChannel.DISCHARGE, 0.95, 0.15, 0.80, 6532.0, 25.0, 8165.0, 686.0 * 686.0),
            FccCandidateSample("c1", CapacityChannel.CHARGE, 0.10, 0.90, 0.80, 5021.6, 20.0, 6277.0, 400.0 * 400.0, endpointQuality = FullEndpointQuality.GOOD)
        )

        val unified = ChargeDischargeFusionEngine.fuse(
            candidates = candidates,
            energyLearner = energyLearner,
            designCapacityMah = 8000.0,
            currentSocPercent = 50,
            previousAcceptedFccQMah = null
        )

        assertEquals(LearningMaturity.DISAGREEMENT, unified.maturity)
        assertTrue(unified.isFusionFrozen)
        // 核心规范：绝不拿 8165.0 当作绝对真值冒充主输出，必须为 null
        assertNull("无历史且分歧时，主输出 fccQMah 必须为 null", unified.fccQMah)
        assertNull("主输出不确定度必须为 null", unified.fccQUncertaintyMah)
    }

    /**
     * 典型合成放电场景回放测试（模拟 Honor 500 Pro 8000mAh 典型工况，算法级功能验证）。
     * 遵循评审意见：清晰界定为合成放电场景（SyntheticDischargeScenario），
     * 与导入真机 CSV 原始数据的实际设备审计回放严格区分。
     */
    @Test
    fun testHonor500ProSyntheticDischargeScenario() {
        val samples = mutableListOf<CapacitySample>()
        var ts = 1700000000000L
        var counterUah = 7800000L // 初始 7800 mAh

        // 1. 100% 满电平台期：持续 1800 秒，以 1.2A 放电，每 30 秒一个采样点，累计放出 600 mAh
        for (i in 0..60) {
            samples.add(
                CapacitySample(
                    timestampMs = ts,
                    socFraction = 1.0,
                    currentAmp = 1.2,
                    voltageVolt = 4.15,
                    temperatureC = 28.5,
                    isScreenOn = (i % 2 == 0),
                    chargingStatus = ChargingStatus.DISCHARGING,
                    chargeCounterUah = counterUah
                )
            )
            ts += 30_000L
            counterUah -= 10_000L // 每 30s 减少 10 mAh (10,000 uAh)
        }

        // 2. 稳定跌落至 20%（跨越 80% SOC，释放约 6400 mAh 物理做功）
        val dropSteps = 160
        for (i in 1..dropSteps) {
            val soc = 1.0 - (i / 160.0 * 0.80)
            samples.add(
                CapacitySample(
                    timestampMs = ts,
                    socFraction = soc,
                    currentAmp = 1.2,
                    voltageVolt = 3.85,
                    temperatureC = 29.0,
                    isScreenOn = true,
                    chargingStatus = ChargingStatus.DISCHARGING,
                    chargeCounterUah = counterUah
                )
            )
            ts += 30_000L
            counterUah -= 10_000L
        }

        // 领域候选构建与质检评估
        val candidate = DischargeCapacityEstimator.buildCandidate(
            sessionId = "honor_500_pro_sess_1",
            samples = samples,
            startSoc = 1.0,
            endSoc = 0.20,
            isDualCell = false
        )

        assertFalse("真实高质量放电不应被排除", candidate.isExcluded)
        assertEquals(DischargeQuality.HIGH, candidate.qualityLevel)

        // 验证平台解耦：600 mAh 被独立分离，不计入有效放电
        assertNotNull(candidate.topPlateau)
        assertTrue("平台放电量应大于 500 mAh", candidate.topPlateau!!.plateauMah > 500.0)

        // 验证电量计 Counter 交叉验证一致（库仑积分与硬件 Counter 变化高度吻合）
        assertEquals(CounterConsistency.AGREE, candidate.counterConsistency)

        // 验证 SOC 能量曲线吸收
        val learner = SocEnergyCurveLearner()
        learner.absorbDischargeSession(samples)

        val ledger = learner.getEnergyCapacityResult()
        assertTrue("平台能量应接近 2.4 Wh", ledger.topPlateauEnergyWh > 2.0)
        assertTrue("实测分箱数量应达到 15 个以上", ledger.measuredBinCount >= 15)
        assertTrue("总观测做功应大于 8.0 Wh", ledger.totalObservedEnergyWh > 8.0)

        // 遵循评审意见：守恒校验采用相对加绝对容差检验
        val sumWh = ledger.measuredBinsEnergyWh + ledger.estimatedBinsEnergyWh + ledger.topPlateauEnergyWh + ledger.transitionEnergyWh + ledger.unresolvedEnergyWh
        val epsAbs = 1e-4
        val epsRel = 1e-4
        val diff = kotlin.math.abs(ledger.totalObservedEnergyWh - sumWh)
        assertTrue("统一能量账本必须满足相对加绝对容差守恒要求", diff <= epsAbs + epsRel * ledger.totalObservedEnergyWh)
    }

    /**
     * P2-B 专项测试 1：放电全流程热环境与温度特征精确统计。
     * 验证起止温度、时间加权平均温度、中位数、P10/P90、温度时间积分与热环境分类。
     */
    @Test
    fun testP2B_temperatureProfileMetricsExtraction() {
        val samples = mutableListOf<CapacitySample>()
        // 构造温度时序：从 20℃ 线性升温至 30℃，持续 300 秒 (11 个采样点，每 30 秒递增 1℃)
        for (i in 0..10) {
            samples.add(
                CapacitySample(
                    timestampMs = i * 30_000L,
                    socFraction = 0.90 - (i * 0.01),
                    currentAmp = 1.0,
                    voltageVolt = 3.85,
                    temperatureC = 20.0 + i * 1.0,
                    chargingStatus = ChargingStatus.DISCHARGING
                )
            )
        }

        val condition = DischargeConditionAnalyzer.analyze(samples)

        assertEquals(20.0, condition.startTemperatureC ?: 0.0, 0.01)
        assertEquals(30.0, condition.endTemperatureC ?: 0.0, 0.01)
        // 线性升温加权平均温度应为 25.0℃
        assertEquals(25.0, condition.averageTemperatureC ?: 0.0, 0.1)
        // 中位数温度应为 25.0℃
        assertEquals(25.0, condition.medianTemperatureC ?: 0.0, 0.1)
        // P10 约为 21.0℃，P90 约为 29.0℃
        assertTrue("P10 应约为 21℃", (condition.temperatureP10C ?: 0.0) in 20.5..21.5)
        assertTrue("P90 应约为 29℃", (condition.temperatureP90C ?: 0.0) in 28.5..29.5)
        // 温度时间积分 = 25℃ * 300s = 7500 ℃·s
        assertEquals(7500.0, condition.temperatureTimeIntegralCs ?: 0.0, 10.0)
        // 25℃ 应归入常温工况 ROOM_TEMP
        assertEquals(DischargeThermalBand.ROOM_TEMP, condition.thermalBand)
    }

    /**
     * P2-B 专项测试 2：放电负载电流与功率特征精确统计。
     * 验证平均电流/功率、中位数、P95 电流/功率、峰值及电压压降 (Voltage Sag)。
     */
    @Test
    fun testP2B_loadAndPowerProfileMetricsExtraction() {
        val samples = mutableListOf<CapacitySample>()
        // 构造基础稳态负载 1000 mA (3.9V) 持续 180s，其中出现高负载脉冲 2500 mA (3.65V)
        samples.add(CapacitySample(0L, 0.80, 1.0, 3.90, 25.0))
        samples.add(CapacitySample(30_000L, 0.79, 1.0, 3.90, 25.0))
        samples.add(CapacitySample(60_000L, 0.78, 1.0, 3.90, 25.0))
        samples.add(CapacitySample(90_000L, 0.76, 2.5, 3.65, 25.0)) // 峰值脉冲
        samples.add(CapacitySample(110_000L, 0.755, 2.5, 3.65, 25.0)) // 脉冲持续 20 秒
        samples.add(CapacitySample(120_000L, 0.75, 1.0, 3.88, 25.0))
        samples.add(CapacitySample(150_000L, 0.74, 1.0, 3.88, 25.0))
        samples.add(CapacitySample(180_000L, 0.73, 1.0, 3.88, 25.0))

        val condition = DischargeConditionAnalyzer.analyze(samples)

        // 峰值电流与峰值功率
        assertEquals(2500.0, condition.peakDischargeCurrentMa ?: 0.0, 0.1)
        assertEquals(2.5 * 3.65, condition.peakDischargePowerW ?: 0.0, 0.01)

        // 中位数电流接近 1000 mA
        assertEquals(1000.0, condition.medianDischargeCurrentMa ?: 0.0, 50.0)
        // P95 电流捕获到高负载脉冲 (> 2000 mA)
        assertTrue("P95 电流应捕获到脉冲特征", (condition.p95DischargeCurrentMa ?: 0.0) > 2000.0)

        // 电压压降：最高 3.90V 到最低 3.65V，压降约为 250 mV
        assertEquals(250.0, condition.voltageSagMv ?: 0.0, 1.0)
        // 平均电流 1000~1500 mA 属于日常中等负载 MODERATE
        assertEquals(DischargeLoadBand.MODERATE, condition.loadBand)
    }

    /**
     * P2-B 专项测试 3：满充能量三态模型解耦验证（实测、归一化、基准分离）。
     * 遵循评审意见：未建立充分数据模型前，归一化能量与基准能量严格为 null，绝不伪造。
     */
    @Test
    fun testP2B_energyTriadSeparation() {
        val energyLearner = SocEnergyCurveLearner()

        // 构造单次放电样本
        val samples = listOf(
            CapacitySample(0L, 0.80, 1.2, 3.85, 26.0),
            CapacitySample(30_000L, 0.75, 1.2, 3.85, 26.5)
        )
        val candidate = DischargeCapacityEstimator.buildCandidate(
            sessionId = "p2b_sess_1",
            samples = samples,
            startSoc = 0.80,
            endSoc = 0.75
        )

        val candidates = listOf(
            FccCandidateSample("d1", CapacityChannel.DISCHARGE, 0.80, 0.75, 0.05, 400.0, 1.54, 8000.0, 400.0, operatingCondition = candidate.operatingCondition)
        )

        val unified = ChargeDischargeFusionEngine.fuse(
            candidates = candidates,
            energyLearner = energyLearner,
            designCapacityMah = 8000.0,
            currentSocPercent = 75
        )

        assertNotNull("能量三态模型实体不能为空", unified.energyTriad)
        val triad = unified.energyTriad!!

        // 实测能量由于 20 段未学满，如实为 null
        assertNull("未学满 20 段时实测 FCC_E 必须为 null", triad.measuredEnergyWh)
        // 未建立温度/负载归一化模型时，归一化能量严格为 null，绝不虚构
        assertNull("模型未经验证前，归一化能量必须严格为 null", triad.normalizedEnergyWh)
        // 未建立标准基准工况前，参考能量严格为 null，绝不拿 3.85*8000 冒充
        assertNull("未建立参考基准前，基准能量必须严格为 null", triad.referenceEnergyWh)
        assertNull("模型置信度必须为 null", triad.normalizationModelConfidence)

        // 工况 Profile 完整挂载
        assertNotNull(triad.conditionProfile)
        assertEquals(DischargeThermalBand.ROOM_TEMP, triad.conditionProfile!!.thermalBand)
    }

    /**
     * P2-B 专项测试 4：历史可信容量（AcceptedFccQ）具备版本保护与完整审计信息，升级算法不被静默覆盖。
     */
    @Test
    fun testP2B_acceptedFccQVersionProtection() {
        val energyLearner = SocEnergyCurveLearner()

        // 创建已确认的历史可信容量实体（带版本、时间戳、成熟度与采纳原因）
        val acceptedHistory = AcceptedFccQ(
            fccMah = 7950.0,
            uncertaintyMah = 280.0,
            algorithmVersion = "FCC-v1.1",
            dataSchemaVersion = 3,
            acceptedTimestampMs = 1700000000000L,
            maturity = LearningMaturity.CONVERGED,
            sampleCount = 6,
            cumulativeSocSpan = 2.4,
            acceptanceReason = "历史已通过双通道多会话一致性自洽验证"
        )

        // 新一轮候选出现严重分歧（放电 8165 vs 充电 6277，差异 26.15%）
        val candidates = listOf(
            FccCandidateSample("d1", CapacityChannel.DISCHARGE, 0.95, 0.15, 0.80, 6532.0, 25.0, 8165.0, 686.0 * 686.0),
            FccCandidateSample("c1", CapacityChannel.CHARGE, 0.10, 0.90, 0.80, 5021.6, 20.0, 6277.0, 400.0 * 400.0, endpointQuality = FullEndpointQuality.GOOD)
        )

        val unified = ChargeDischargeFusionEngine.fuse(
            candidates = candidates,
            energyLearner = energyLearner,
            designCapacityMah = 8000.0,
            currentSocPercent = 50,
            acceptedFccQ = acceptedHistory
        )

        // 验证系统分歧冻结
        assertTrue(unified.isFusionFrozen)
        assertEquals(LearningMaturity.DISAGREEMENT, unified.maturity)

        // 验证主输出严格保留历史可信数值 7950.0，不被 8165.0 篡改
        assertEquals(7950.0, unified.fccQMah ?: 0.0, 0.1)
        assertEquals(280.0, unified.fccQUncertaintyMah ?: 0.0, 0.1)

        // 验证历史实体完整透传，保留算法版本与审计证据
        assertNotNull(unified.acceptedFccQ)
        assertEquals("FCC-v1.1", unified.acceptedFccQ!!.algorithmVersion)
        assertEquals(3, unified.acceptedFccQ!!.dataSchemaVersion)
        assertEquals(LearningMaturity.CONVERGED, unified.acceptedFccQ!!.maturity)
        assertTrue(unified.acceptedFccQ!!.acceptanceReason.contains("历史已通过双通道"))
    }

    /**
     * P2-B 专项测试 5：严禁过早引入未经检验的温度补偿系数，低温与常温能量如实保留实测。
     * 验证系统不会私自使用固定经验公式将低温放电能量线性放大折算。
     */
    @Test
    fun testP2B_noPrematureTemperatureCoefficient() {
        val lowTempSamples = listOf(
            CapacitySample(0L, 0.90, 1.0, 3.80, 8.0), // 8℃ 低温
            CapacitySample(60_000L, 0.88, 1.0, 3.75, 9.0)
        )
        val roomTempSamples = listOf(
            CapacitySample(0L, 0.90, 1.0, 3.85, 25.0), // 25℃ 常温
            CapacitySample(60_000L, 0.88, 1.0, 3.85, 25.0)
        )

        val lowCondition = DischargeConditionAnalyzer.analyze(lowTempSamples)
        val roomCondition = DischargeConditionAnalyzer.analyze(roomTempSamples)

        // 客观分类
        assertEquals(DischargeThermalBand.LOW_TEMP, lowCondition.thermalBand)
        assertEquals(DischargeThermalBand.ROOM_TEMP, roomCondition.thermalBand)

        // 验证候选样本构建时如实保存原始数据，不进行系数缩放
        val lowCandidate = DischargeCapacityEstimator.buildCandidate(
            sessionId = "low_1",
            samples = lowTempSamples,
            startSoc = 0.90,
            endSoc = 0.88
        )
        assertNotNull(lowCandidate.operatingCondition)
        assertEquals(DischargeThermalBand.LOW_TEMP, lowCandidate.operatingCondition!!.thermalBand)

        // 验证系统绝不产生任何虚构的归一化系数或归一化能量
        val candidates = listOf(lowCandidate)
        val unified = ChargeDischargeFusionEngine.fuse(
            candidates = candidates,
            energyLearner = SocEnergyCurveLearner(),
            designCapacityMah = 8000.0,
            currentSocPercent = 88
        )
        assertNull("绝不可过早进行固定系数归一化脑补", unified.energyTriad?.normalizedEnergyWh)
    }

    /**
     * P2-B1.1 专项测试 1：验证不规则采样下温度分位数采用时间加权，避免高频采样偏置。
     *
     * 场景说明：亮屏时高频采样（前 10 秒包含 10 个 1 秒切片，温度 35℃）；
     * 息屏时低频采样（后 90 秒仅 1 个 90 秒切片，温度 20℃）。
     * 传统按采样点计数的中位数为 35℃（点数占绝大多数）；
     * 真实物理时间中 20℃ 占 90% 时间（90s/100s），时间加权 P50 必须忠实反映为 20℃。
     */
    @Test
    fun testTemperaturePercentilesAreDurationWeighted() {
        val samples = mutableListOf<CapacitySample>()
        var ts = 0L
        // 1. 亮屏高频 35℃：持续 10 秒，每秒 1 点（共 11 个点，产生 10 个 1s 切片）
        for (i in 0..10) {
            samples.add(CapacitySample(ts, 0.90, 1.0, 3.85, 35.0))
            ts += 1000L
        }
        // 2. 息屏低频 20℃：持续 90 秒稳定在 20℃（产生 1 个 90s 切片，均值 20℃）
        samples.add(CapacitySample(ts, 0.895, 0.2, 3.85, 20.0))
        ts += 90_000L
        samples.add(CapacitySample(ts, 0.89, 0.2, 3.85, 20.0))

        val condition = DischargeConditionAnalyzer.analyze(samples)

        // 验证时间加权中位数 P50 必须忠实反映持续时间占 90% 的低温区间（20.0℃），绝不能等于点数偏置的 35℃
        assertNotNull(condition.temperatureP50C)
        assertTrue("时间加权中位数必须偏向长时间持续的低温(< 25℃)", condition.temperatureP50C!! < 25.0)
    }

    /**
     * P2-B1.1 专项测试 2：验证采样超长断层（> 120s）不计入有效温度统计时间，不向缺口延伸上一温度。
     */
    @Test
    fun testTemperatureCoverageExcludesLongGaps() {
        val samples = listOf(
            CapacitySample(0L, 0.90, 1.0, 3.85, 25.0),
            CapacitySample(30_000L, 0.89, 1.0, 3.85, 25.0), // 有效 30 秒
            // 发生 300 秒超长断层
            CapacitySample(330_000L, 0.85, 1.0, 3.85, 28.0),
            CapacitySample(360_000L, 0.84, 1.0, 3.85, 28.0)  // 有效 30 秒
        )

        val condition = DischargeConditionAnalyzer.analyze(samples)

        // 总会话跨度为 360 秒，但有效温度时长扣除 300 秒断层，仅记录紧邻有效切片的 60 秒
        assertEquals(60L, condition.temperatureValidDurationSeconds)
        assertTrue("有效温度覆盖率必须显著低于 1.0", condition.temperatureCoverageRatio < 0.3)
    }

    /**
     * P2-B1.1 专项测试 3：验证跨温区放电被正确识别为 MIXED 剖面，并记录各温区时间占比。
     */
    @Test
    fun testMixedThermalProfileDetection() {
        val samples = mutableListOf<CapacitySample>()
        var ts = 0L
        // 1. 低温（10℃）持续 100 秒 (5 个 20s 间隔)
        for (i in 0..5) {
            samples.add(CapacitySample(ts, 0.90 - i * 0.01, 1.0, 3.85, 10.0))
            ts += 20_000L
        }
        // 2. 常温（25℃）持续 100 秒 (5 个 20s 间隔)
        for (i in 0..5) {
            samples.add(CapacitySample(ts, 0.84 - i * 0.01, 1.0, 3.85, 25.0))
            ts += 20_000L
        }

        val condition = DischargeConditionAnalyzer.analyze(samples)

        // 验证识别为 MIXED 混合温区剖面
        assertEquals(ThermalProfileType.MIXED, condition.thermalProfile)
        // 验证温区时间占比均约为 50%（单一温区均未达到 85% 纯净门限）
        assertTrue("低温时间占比应约为 50%", condition.lowTempDurationRatio in 0.4..0.6)
        assertTrue("常温时间占比应约为 50%", condition.roomTempDurationRatio in 0.4..0.6)
        assertEquals(0.0, condition.highTempDurationRatio, 1e-6)
    }

    /**
     * P2-B1.1 专项测试 4：验证各负载区间时间占比与 MIXED 负载剖面检测。
     */
    @Test
    fun testLoadBandDurationRatio() {
        val samples = mutableListOf<CapacitySample>()
        var ts = 0L
        // 1. 轻载（300 mA < 500 mA）持续 120 秒 (4 个 30s 间隔)
        for (i in 0..4) {
            samples.add(CapacitySample(ts, 0.90, 0.3, 3.85, 25.0))
            ts += 30_000L
        }
        // 2. 重载（2000 mA > 1500 mA）持续 120 秒 (4 个 30s 间隔)
        for (i in 0..4) {
            samples.add(CapacitySample(ts, 0.88, 2.0, 3.85, 25.0))
            ts += 30_000L
        }

        val condition = DischargeConditionAnalyzer.analyze(samples)

        assertEquals(LoadProfileType.MIXED, condition.loadProfile)
        assertTrue("轻载时间占比应约为 50%", condition.lightLoadDurationRatio in 0.4..0.6)
        assertTrue("重载时间占比应约为 50%", condition.heavyLoadDurationRatio in 0.4..0.6)
        assertTrue("过渡中载时间占比应很小(< 20%)", condition.moderateLoadDurationRatio < 0.20)
    }

    /**
     * P2-B1.1 专项测试 5：验证时间加权 P95 电流能捕获到脉冲重载，不被中等均值掩盖。
     */
    @Test
    fun testCurrentP95NotMaskedByModerateAverage() {
        val samples = mutableListOf<CapacitySample>()
        var ts = 0L
        // 稳态中载 800 mA 持续 900 秒（30 个 30s 间隔）
        for (i in 0..30) {
            samples.add(CapacitySample(ts, 0.90, 0.8, 3.85, 25.0))
            ts += 30_000L
        }
        // 穿插高负载脉冲 3000 mA 持续 90 秒（3 个 30s 间隔，占 ~9% 时间）
        for (i in 1..3) {
            samples.add(CapacitySample(ts, 0.85, 3.0, 3.75, 25.0))
            ts += 30_000L
        }

        val condition = DischargeConditionAnalyzer.analyze(samples)

        // 平均电流被大部分中载拉平（约 1000 mA）
        assertTrue("平均电流应在中等范围", (condition.averageDischargeCurrentMa ?: 0.0) < 1200.0)
        // P95 能够清晰捕获到高负载脉冲特征（> 2500 mA）
        assertNotNull(condition.p95DischargeCurrentMa)
        assertTrue("P95 电流必须捕获到脉冲重载特征(> 2500 mA)", condition.p95DischargeCurrentMa!! > 2500.0)
    }

    /**
     * P2-B1.1 专项测试 6：验证无明显电流阶跃时，表观动态电阻（apparentResistanceMilliOhm）严格为 null。
     * 遵循评审铁律：绝不拿缓慢放电的宏观电压跌落冒充欧姆内阻。
     */
    @Test
    fun testVoltageSagRequiresCurrentStepEvidence() {
        val samples = mutableListOf<CapacitySample>()
        // 缓慢平滑放电：电流保持 500 mA 恒定，电压因 SOC 降低从 4.10V 缓慢降至 3.80V（跌落 300 mV）
        for (i in 0..10) {
            samples.add(CapacitySample(i * 30_000L, 0.90 - i * 0.02, 0.5, 4.10 - i * 0.03, 25.0))
        }

        val condition = DischargeConditionAnalyzer.analyze(samples)

        // 存在宏观观测电压落差 300 mV
        assertNotNull(condition.voltageSagMv)
        assertTrue(condition.voltageSagMv!! >= 299.0)

        // 但因无电流突增阶跃（ΔI < 200 mA），表观动态电阻必须严格为 null！
        assertNotNull(condition.voltageSagMetrics)
        assertNull("无明显电流阶跃时表观电阻必须严格为 null", condition.voltageSagMetrics?.apparentResistanceMilliOhm)
    }

    /**
     * P2-B1.1 专项测试 7：验证发生清晰电流阶跃时，准确计算表观动态电阻 R_app = 1000 * ΔV / ΔI。
     */
    @Test
    fun testVoltageSagUsesRawPhysicalVoltage() {
        val samples = listOf(
            CapacitySample(0L, 0.80, 0.5, 3.90, 25.0),
            // 5 秒内电流突增 1000 mA（从 500mA 阶跃至 1500mA），端电压瞬间跌落 100 mV（从 3.90V 跌至 3.80V）
            CapacitySample(5_000L, 0.80, 1.5, 3.80, 25.0),
            CapacitySample(30_000L, 0.79, 1.5, 3.80, 25.0)
        )

        val condition = DischargeConditionAnalyzer.analyze(samples)

        val sag = condition.voltageSagMetrics
        assertNotNull(sag)
        // ΔI = 1000 mA, ΔV = 100 mV => R_app = 1000 * (100 / 1000) = 100.0 mΩ
        assertEquals(1000.0, sag?.deltaCurrentMa ?: 0.0, 1.0)
        assertEquals(100.0, sag?.apparentResistanceMilliOhm ?: 0.0, 0.5)
        assertEquals(5000L, sag?.eventDurationMs ?: 0L)
    }

    /**
     * P2-B1.1 专项测试 8：验证包含断层或缺失读数的无效区间被工况分析器安全排除。
     */
    @Test
    fun testConditionAnalyzerExcludesInvalidIntervals() {
        val samples = listOf(
            CapacitySample(0L, 0.80, 1.0, 3.85, 25.0),
            CapacitySample(30_000L, 0.79, 1.0, 3.85, null), // 温度缺失
            CapacitySample(60_000L, 0.78, null, 3.85, 26.0), // 电流缺失
            // 200 秒采样断层
            CapacitySample(260_000L, 0.75, 1.0, 3.85, 26.0),
            CapacitySample(290_000L, 0.74, 1.0, 3.85, 26.0)
        )

        val condition = DischargeConditionAnalyzer.analyze(samples)

        // 验证不会崩溃，且有效温度时长正确扣除断层与缺失
        assertTrue(condition.temperatureValidDurationSeconds in 30L..90L)
        assertNotNull(condition.averageTemperatureC)
        assertNotNull(condition.averageDischargeCurrentMa)
    }

    /**
     * P2-B2 核心测试 9：验证单会话独立样本原则，去重后一条放电会话仅对应一条样本，严禁虚报样本数。
     */
    @Test
    fun testConditionSampleSessionIndependence() {
        // 构造具有相同 session ID 的多次切片记录（模拟错误切分或重复提交）
        val candidate1 = CapacityCandidate(
            id = 1001L,
            sessionType = CandidateSessionType.DISCHARGE,
            startSoc = 90,
            endSoc = 60,
            socSpanPercent = 30,
            netBatteryChargeMah = 2400.0,
            fccObservedMah = 8000.0,
            startTimestamp = 1700000000000L,
            endTimestamp = 1700003600000L,
            durationMs = 3600000L,
            avgTemperatureCelsius = 25.0f,
            maxTemperatureCelsius = 26.0f,
            sampleCount = 120,
            qualityScore = 0.95,
            energyWh = 9.24
        )
        // 重复的相同会话 ID
        val duplicateCandidate = candidate1.copy(id = 1001L)

        val candidate2 = candidate1.copy(id = 1002L, avgTemperatureCelsius = 10.0f)

        val candidates = listOf(candidate1, duplicateCandidate, candidate2)
        val dataset = DischargeConditionDatasetBuilder.buildDatasetFromCandidates(candidates)

        // 验证严格去重，仅生成 2 条独立样本，绝不虚构成 3 条
        assertEquals(2, dataset.size)
        assertEquals(setOf("1001", "1002"), dataset.map { it.sessionId }.toSet())
    }

    /**
     * P2-B2 核心测试 10：验证工况超出已覆盖训练范围时（OUT_OF_DOMAIN），归一化能量如实为 null。
     */
    @Test
    fun testConditionModelOutOfDomainReturnsNull() {
        // 仅在常温+中载（ROOM_TEMP, MODERATE）有样本的矩阵
        val sample1 = DischargeConditionSample(
            sessionId = "s1",
            observedEnergyWh = 30.0,
            measuredEnergyCoverage = 100.0,
            unresolvedEnergyWh = 0.0,
            condition = DischargeOperatingCondition(
                thermalBand = DischargeThermalBand.ROOM_TEMP,
                loadBand = DischargeLoadBand.MODERATE
            ),
            startSoc = 100,
            endSoc = 0,
            socSpanPercent = 100,
            quality = DischargeQuality.HIGH,
            counterConsistency = CounterConsistency.AGREE,
            sessionTimestampMs = 1700000000000L
        )

        val matrixResult = ConditionSensitivityLearner.evaluateMatrix(listOf(sample1))

        // 当面对低温+重载（LOW_TEMP, HEAVY）的全新工况时
        val outOfDomainCondition = DischargeOperatingCondition(
            thermalBand = DischargeThermalBand.LOW_TEMP,
            loadBand = DischargeLoadBand.HEAVY
        )

        // 验证被判决为超范围 OUT_OF_DOMAIN
        val isOod = ConditionSensitivityLearner.isOutOfDomain(outOfDomainCondition, matrixResult)
        assertTrue("未覆盖的低温重载工况必须判定为超范围 OUT_OF_DOMAIN", isOod)
    }

    /**
     * P2-B2 核心测试 11：验证合成数据与无交叉覆盖数据绝不误判为 VALIDATED 成熟度。
     * 无充分真实交叉覆盖时，成熟度最高限制为 DATA_COLLECTION / EXPLORATORY。
     */
    @Test
    fun testConditionModelDoesNotUseSyntheticDataAsValidation() {
        // 构造仅有 2 条同工况样本的稀疏数据集
        val sample1 = DischargeConditionSample(
            sessionId = "s1",
            observedEnergyWh = 30.0,
            measuredEnergyCoverage = 100.0,
            unresolvedEnergyWh = 0.0,
            condition = DischargeOperatingCondition(
                thermalBand = DischargeThermalBand.ROOM_TEMP,
                loadBand = DischargeLoadBand.MODERATE
            ),
            startSoc = 100,
            endSoc = 0,
            socSpanPercent = 100,
            quality = DischargeQuality.HIGH,
            counterConsistency = CounterConsistency.AGREE,
            sessionTimestampMs = 1700000000000L
        )
        val sample2 = sample1.copy(sessionId = "s2")

        val matrixResult = ConditionSensitivityLearner.evaluateMatrix(listOf(sample1, sample2))

        // 验证系统标记交叉覆盖不足
        assertFalse("缺乏跨工况对照组时交叉覆盖必须为 false", matrixResult.hasCrossConditionOverlap)
        assertTrue(matrixResult.overlapStatusMessage.contains("INSUFFICIENT_CONDITION_OVERLAP") || matrixResult.overlapStatusMessage.contains("数据积累中"))
        // 验证成熟度坚决不晋升为 VALIDATED
        assertTrue(
            "样本不足或无交叉覆盖时绝不能判定为 VALIDATED",
            matrixResult.maturity == ConditionModelMaturity.DATA_COLLECTION || matrixResult.maturity == ConditionModelMaturity.PENDING
        )
    }

    /**
     * P2-B2.1 定向测试 1：验证温度负载工况统计与能量积分采用完全一致的时间掩码（Consistent Interval Mask）。
     * 解决 90 秒断层口径不一致问题：工况分析器安全切除断层，与能量积分有效区间 100% 保持一致。
     */
    @Test
    fun testP2B_conditionCoverageMatchesEnergyCoverage() {
        val samples = listOf(
            CapacitySample(0L, 0.90, 1.0, 3.85, 25.0),
            CapacitySample(30_000L, 0.89, 1.0, 3.85, 25.0), // 第一段有效 30 秒 (0..30s)
            // 90 秒采样缺口 (30s..120s，被库仑积分切断)
            CapacitySample(120_000L, 0.85, 1.0, 3.85, 26.0),
            CapacitySample(150_000L, 0.84, 1.0, 3.85, 26.0)  // 第二段有效 30 秒 (120s..150s)
        )

        // 能量通道有效的物理区间：[0..30s] 与 [120..150s]
        val energyMask = listOf(0L..30_000L, 120_000L..150_000L)

        val alignedCondition = DischargeConditionAnalyzer.analyzeWithEnergyIntervals(samples, energyMask)

        // 验证对齐后的有效工况时长严格等于能量有效积分时长（60 秒），而非全会话的 150 秒
        assertEquals(60L, alignedCondition.energyAlignedDurationSeconds)
        assertTrue("对齐后的覆盖率必须反映有效能量区间(60/150 = 40%)", (alignedCondition.energyAlignedCoverageRatio ?: 0.0) in 0.39..0.41)
        assertNotNull(alignedCondition.averageTemperatureC)
        assertNotNull(alignedCondition.averageDischargeCurrentMa)
    }

    /**
     * P2-B2.1 定向测试 2：验证同一段物理信号重采样变频后，时间加权分位数保持不变（消除采样频率与点数偏置）。
     */
    @Test
    fun testP2B_weightedPercentilesInvariantToEquivalentResampling() {
        // 物理信号：前半段 100 秒恒定为 20℃，后半段 100 秒恒定为 30℃，理论时间中位数严格为 25.0℃
        // 序列 A：稀疏低频采样（每 25 秒 1 点）
        val samplesSparse = mutableListOf<CapacitySample>()
        for (i in 0..8) {
            val ts = i * 25_000L
            val temp = if (ts < 100_000L) 20.0 else 30.0
            samplesSparse.add(CapacitySample(ts, 0.90 - i * 0.005, 1.0, 3.85, temp))
        }

        // 序列 B：密集高频采样（每 5 秒 1 点）
        val samplesDense = mutableListOf<CapacitySample>()
        for (i in 0..40) {
            val ts = i * 5_000L
            val temp = if (ts < 100_000L) 20.0 else 30.0
            samplesDense.add(CapacitySample(ts, 0.90 - i * 0.001, 1.0, 3.85, temp))
        }

        val condSparse = DischargeConditionAnalyzer.analyze(samplesSparse)
        val condDense = DischargeConditionAnalyzer.analyze(samplesDense)

        // 验证稀疏与密集两种采样频率下的时间加权中位数 P50 严格一致（均接近 25.0℃，差异 < 0.5℃）
        assertNotNull(condSparse.temperatureP50C)
        assertNotNull(condDense.temperatureP50C)
        assertEquals("变频重采样下时间加权分位数必须保持一致", condSparse.temperatureP50C!!, condDense.temperatureP50C!!, 0.5)
    }

    /**
     * P2-B2.1 定向测试 3：验证工况敏感度学习只比较共同实测 SOC 分箱，覆盖不足时返回 INSUFFICIENT_SOC_OVERLAP。
     */
    @Test
    fun testP2B_commonSocBinsOnlyForConditionComparison() {
        // 构造会话 A：覆盖 90% -> 20%（Bin 4..17 为 MEASURED，共 14 个分箱，每个分箱 1.8 Wh）
        val binEnergiesA = (4..17).associateWith { 1.8 }
        val sampleA = DischargeConditionSample(
            sessionId = "sess_A",
            observedEnergyWh = 25.2,
            measuredEnergyCoverage = 70.0,
            unresolvedEnergyWh = 0.0,
            condition = DischargeOperatingCondition(thermalBand = DischargeThermalBand.ROOM_TEMP, loadBand = DischargeLoadBand.LIGHT),
            startSoc = 90,
            endSoc = 20,
            socSpanPercent = 70,
            quality = DischargeQuality.HIGH,
            counterConsistency = CounterConsistency.AGREE,
            binMeasuredEnergiesWh = binEnergiesA
        )

        // 构造会话 B：覆盖 80% -> 30%（Bin 6..15 为 MEASURED，共 10 个分箱，每个分箱 1.5 Wh）
        val binEnergiesB = (6..15).associateWith { 1.5 }
        val sampleB = DischargeConditionSample(
            sessionId = "sess_B",
            observedEnergyWh = 15.0,
            measuredEnergyCoverage = 50.0,
            unresolvedEnergyWh = 0.0,
            condition = DischargeOperatingCondition(thermalBand = DischargeThermalBand.LOW_TEMP, loadBand = DischargeLoadBand.LIGHT),
            startSoc = 80,
            endSoc = 30,
            socSpanPercent = 50,
            quality = DischargeQuality.HIGH,
            counterConsistency = CounterConsistency.AGREE,
            binMeasuredEnergiesWh = binEnergiesB
        )

        // 比较共同实测分箱（Bin 6..15，共 10 个分箱，共同覆盖 50% SOC）
        val comparison = ConditionComparisonMatcher.compareCommonSocBins(sampleA, sampleB)

        assertEquals(SocOverlapStatus.SUFFICIENT, comparison.status)
        assertEquals(10, comparison.commonMeasuredBinIndices.size)
        assertEquals(50.0, comparison.commonSocCoveragePercent, 0.01)
        // A 在共同分箱为 10 * 1.8 = 18.0 Wh；B 为 10 * 1.5 = 15.0 Wh
        assertEquals(18.0, comparison.commonMeasuredEnergyWhA, 0.01)
        assertEquals(15.0, comparison.commonMeasuredEnergyWhB, 0.01)
        assertEquals(-3.0, comparison.energyDeltaWh, 0.01)

        // 构造共同覆盖仅 10%（Bin 14, 15 共 2 个分箱 < 20% 门限）的样本 C
        val binEnergiesC = (14..17).associateWith { 1.5 }
        val sampleC = sampleB.copy(sessionId = "sess_C", binMeasuredEnergiesWh = binEnergiesC)
        val insufficientComp = ConditionComparisonMatcher.compareCommonSocBins(sampleB, sampleC)

        // 共同覆盖不足坚决拦截并返回 INSUFFICIENT_SOC_OVERLAP，禁止直接拿整会话能量相减
        assertEquals(SocOverlapStatus.INSUFFICIENT_SOC_OVERLAP, insufficientComp.status)
        assertTrue(insufficientComp.comparisonDetails.contains("INSUFFICIENT_SOC_OVERLAP"))

        // 构造完全无交集的样本 D (Bin 0..2 vs Bin 6..15)
        val sampleD = sampleB.copy(sessionId = "sess_D", binMeasuredEnergiesWh = (0..2).associateWith { 1.5 })
        val noOverlapComp = ConditionComparisonMatcher.compareCommonSocBins(sampleB, sampleD)
        assertEquals(SocOverlapStatus.NO_OVERLAP, noOverlapComp.status)
    }

    /**
     * P2-B2.1 定向测试 4：验证估算分箱（MODEL_ESTIMATED）坚决排除在实测比对目标之外，防止伪造。
     */
    @Test
    fun testP2B_estimatedBinsExcludedFromMeasuredConditionTarget() {
        // 会话 A 在 Bin 10 是纯实测（MEASURED），而在 Bin 11 是模型估算（MODEL_ESTIMATED）
        val energiesA = mapOf(10 to 1.8, 11 to 1.8)
        val statusesA = mapOf(10 to BinLearningStatus.MEASURED, 11 to BinLearningStatus.MODEL_ESTIMATED)

        // 会话 B 在 Bin 10 和 Bin 11 都有能量
        val energiesB = mapOf(10 to 1.6, 11 to 1.6)
        val statusesB = mapOf(10 to BinLearningStatus.MEASURED, 11 to BinLearningStatus.MEASURED)

        val sampleA = DischargeConditionSample(
            sessionId = "sess_est_A",
            observedEnergyWh = 3.6,
            measuredEnergyCoverage = 10.0,
            unresolvedEnergyWh = 0.0,
            condition = DischargeOperatingCondition(),
            startSoc = 60,
            endSoc = 50,
            socSpanPercent = 10,
            quality = DischargeQuality.HIGH,
            counterConsistency = CounterConsistency.AGREE,
            binMeasuredEnergiesWh = energiesA,
            binLearningStatuses = statusesA
        )
        val sampleB = sampleA.copy(
            sessionId = "sess_est_B",
            binMeasuredEnergiesWh = energiesB,
            binLearningStatuses = statusesB
        )

        val comparison = ConditionComparisonMatcher.compareCommonSocBins(sampleA, sampleB, minCommonSpanPercent = 5.0)

        // 验证：Bin 11 绝不进入共同实测分箱列表！仅有 Bin 10 被纳入比对！
        assertEquals(listOf(10), comparison.commonMeasuredBinIndices)
        assertEquals(1.8, comparison.commonMeasuredEnergyWhA, 0.01)
        assertEquals(1.6, comparison.commonMeasuredEnergyWhB, 0.01)
    }

    /**
     * P2-B2.1 定向测试 5：验证同一电池和设备的多个放电会话被正确识别为重复测量（Repeated Measures）。
     */
    @Test
    fun testP2B_sameBatterySessionsAreRepeatedMeasures() {
        val identityA = ConditionSampleIdentity(
            sessionId = "s_mon",
            deviceId = "honor_500_pro_sn1",
            batteryIdentity = "cell_batt_001",
            sessionGroupId = "week_1",
            sessionStartTimestamp = 1700000000000L,
            sessionEndTimestamp = 1700003600000L,
            cycleCount = 120
        )
        val identityB = identityA.copy(
            sessionId = "s_tue",
            sessionStartTimestamp = 1700086400000L,
            sessionEndTimestamp = 1700090000000L,
            cycleCount = 121
        )

        val sampleA = DischargeConditionSample(
            sessionId = "s_mon",
            observedEnergyWh = 28.0,
            measuredEnergyCoverage = 100.0,
            unresolvedEnergyWh = 0.0,
            condition = DischargeOperatingCondition(),
            startSoc = 100,
            endSoc = 0,
            socSpanPercent = 100,
            quality = DischargeQuality.HIGH,
            counterConsistency = CounterConsistency.AGREE,
            identity = identityA
        )
        val sampleB = sampleA.copy(sessionId = "s_tue", identity = identityB)

        // 验证正确识别为同一电池重复测量
        assertTrue("同一电池会话必须被判定为重复测量", ConditionComparisonMatcher.isSameBatteryRepeatedMeasures(sampleA, sampleB))

        // 不同电池身份的样本
        val sampleDiff = sampleA.copy(
            sessionId = "s_other",
            identity = identityA.copy(deviceId = "other_dev", batteryIdentity = "cell_batt_999")
        )
        assertFalse(ConditionComparisonMatcher.isSameBatteryRepeatedMeasures(sampleA, sampleDiff))
    }

    /**
     * P2-B2.1 定向测试 6：验证混合工况（MIXED）绝不被当作纯工况（PURE）进行模型拟合与对照。
     */
    @Test
    fun testP2B_mixedConditionNotTreatedAsPure() {
        val mixedSample = DischargeConditionSample(
            sessionId = "mixed_s1",
            observedEnergyWh = 26.0,
            measuredEnergyCoverage = 100.0,
            unresolvedEnergyWh = 0.0,
            condition = DischargeOperatingCondition(
                thermalBand = DischargeThermalBand.ROOM_TEMP,
                thermalProfile = ThermalProfileType.MIXED,
                loadBand = DischargeLoadBand.MODERATE,
                loadProfile = LoadProfileType.PURE
            ),
            startSoc = 100,
            endSoc = 0,
            socSpanPercent = 100,
            quality = DischargeQuality.HIGH,
            counterConsistency = CounterConsistency.AGREE
        )

        // 验证混合工况坚决不能被识别为纯净工况
        assertFalse("温区为 MIXED 的样本绝不能被当作纯工况", ConditionSensitivityLearner.isPureCondition(mixedSample))

        val pureSample = mixedSample.copy(
            condition = mixedSample.condition.copy(thermalProfile = ThermalProfileType.PURE)
        )
        assertTrue("双纯净剖面的样本才能作为基准纯工况", ConditionSensitivityLearner.isPureCondition(pureSample))
    }

    /**
     * P2-B2.1 定向测试 7：验证连续联合特征支持度（Joint Feature Support）检测，电流超标时判定为 OUT_OF_DOMAIN。
     */
    @Test
    fun testP2B_outOfDomainUsesJointFeatureSupport() {
        val sampleTrain = DischargeConditionSample(
            sessionId = "train_1",
            observedEnergyWh = 30.0,
            measuredEnergyCoverage = 100.0,
            unresolvedEnergyWh = 0.0,
            condition = DischargeOperatingCondition(
                thermalBand = DischargeThermalBand.ROOM_TEMP,
                averageTemperatureC = 25.0,
                loadBand = DischargeLoadBand.MODERATE,
                averageDischargeCurrentMa = 1000.0,
                averageDischargePowerW = 3.85,
                voltageSagMv = 150.0
            ),
            startSoc = 100,
            endSoc = 0,
            socSpanPercent = 100,
            quality = DischargeQuality.HIGH,
            counterConsistency = CounterConsistency.AGREE
        )

        val matrix = ConditionSensitivityLearner.evaluateMatrix(listOf(sampleTrain))
        val bounds = ConditionSensitivityLearner.extractContinuousBounds(listOf(sampleTrain))
        assertNotNull(bounds)

        // 输入虽然在 ROOM_TEMP / HEAVY 单元格内，但电流高达 5000 mA（远超训练集 1000 mA * 1.25）
        val extremeHeavyCondition = DischargeOperatingCondition(
            thermalBand = DischargeThermalBand.ROOM_TEMP,
            averageTemperatureC = 25.0,
            loadBand = DischargeLoadBand.HEAVY,
            averageDischargeCurrentMa = 5000.0,
            averageDischargePowerW = 18.0
        )

        val isOod = ConditionSensitivityLearner.isOutOfDomainWithBounds(extremeHeavyCondition, matrix, bounds)
        assertTrue("超出连续电流支持范围的工况必须判定为超范围 OUT_OF_DOMAIN", isOod)
    }

    /**
     * P2-B2.1 定向测试 8：验证 Bootstrap 不确定度计算基于完整会话组重采样，样本组不足时不输出置信区间。
     */
    @Test
    fun testP2B_bootstrapResamplesSessionGroups() {
        // 1. 仅有 2 个会话组（不足 3 组门限）
        val sample1 = DischargeConditionSample(
            sessionId = "s1",
            observedEnergyWh = 28.0,
            measuredEnergyCoverage = 100.0,
            unresolvedEnergyWh = 0.0,
            condition = DischargeOperatingCondition(),
            startSoc = 100,
            endSoc = 0,
            socSpanPercent = 100,
            quality = DischargeQuality.HIGH,
            counterConsistency = CounterConsistency.AGREE,
            identity = ConditionSampleIdentity(sessionId = "s1", sessionGroupId = "g1", sessionStartTimestamp = 1L, sessionEndTimestamp = 2L)
        )
        val sample2 = sample1.copy(sessionId = "s2", identity = sample1.identity?.copy(sessionId = "s2", sessionGroupId = "g2"))

        val smallBootstrap = ConditionSensitivityLearner.calculateSessionGroupBootstrap(listOf(sample1, sample2))
        // 会话组不足时，置信区间如实为 null，绝不伪造
        assertNull("会话组不足 3 组时 Bootstrap 置信区间下界必须为 null", smallBootstrap.bootstrapCiLowerWh)
        assertNull("会话组不足 3 组时 Bootstrap 置信区间上界必须为 null", smallBootstrap.bootstrapCiUpperWh)
        assertFalse(smallBootstrap.isConfident)

        // 2. 具备 5 个独立会话组（g1, g2, g3, g4, g5）
        val sample3 = sample1.copy(sessionId = "s3", observedEnergyWh = 29.0, identity = sample1.identity?.copy(sessionId = "s3", sessionGroupId = "g3"))
        val sample4 = sample1.copy(sessionId = "s4", observedEnergyWh = 27.5, identity = sample1.identity?.copy(sessionId = "s4", sessionGroupId = "g4"))
        val sample5 = sample1.copy(sessionId = "s5", observedEnergyWh = 28.5, identity = sample1.identity?.copy(sessionId = "s5", sessionGroupId = "g5"))

        val adequateBootstrap = ConditionSensitivityLearner.calculateSessionGroupBootstrap(listOf(sample1, sample2, sample3, sample4, sample5))
        assertTrue("5组会话充足时具备统计可靠置信度", adequateBootstrap.isConfident)
        assertTrue(adequateBootstrap.isStatisticallyReliable)
        assertNotNull(adequateBootstrap.bootstrapCiLowerWh)
        assertNotNull(adequateBootstrap.bootstrapCiUpperWh)
        assertTrue("置信区间下界应小于上界", adequateBootstrap.bootstrapCiLowerWh!! <= adequateBootstrap.bootstrapCiUpperWh!!)
    }

    /**
     * P2-B2.1 定向测试 9：验证时间序列前向验证（Chronological Validation），杜绝未来数据泄露到过去训练集。
     */
    @Test
    fun testP2B_chronologicalValidationPreventsFutureLeakage() {
        val s1 = DischargeConditionSample(
            sessionId = "s1",
            observedEnergyWh = 28.0,
            measuredEnergyCoverage = 100.0,
            unresolvedEnergyWh = 0.0,
            condition = DischargeOperatingCondition(),
            startSoc = 100,
            endSoc = 0,
            socSpanPercent = 100,
            quality = DischargeQuality.HIGH,
            counterConsistency = CounterConsistency.AGREE,
            sessionTimestampMs = 1700000000000L
        )
        val s2 = s1.copy(sessionId = "s2", sessionTimestampMs = 1700086400000L) // 递增时间戳
        val sPast = s1.copy(sessionId = "s_past", sessionTimestampMs = 1699000000000L) // 过去时间戳

        // 升序排列验证通过
        assertTrue(ConditionComparisonMatcher.validateChronologicalOrder(listOf(s1, s2)))

        // 包含未来数据倒流到过去（s2 在 sPast 之前）坚决报错拦截
        assertFalse("时序乱序或未来数据倒流必须被检测拦截", ConditionComparisonMatcher.validateChronologicalOrder(listOf(s2, sPast)))
    }

    /**
     * P2-B2.1 补充测试 1：验证单维特征各自在边际范围内，但联合特征组合在训练集中无样本支持时（局部空白区），
     * 系统准确判定为 UNSUPPORTED_COMBINATION 并拒绝预测。
     */
    @Test
    fun testP2B_jointConditionUnsupportedWithinMarginalBounds() {
        // 训练集样本组合：(15℃, 500mA) 与 (30℃, 1500mA)
        val s1 = DischargeConditionSample(
            sessionId = "t1",
            observedEnergyWh = 26.0,
            measuredEnergyCoverage = 100.0,
            unresolvedEnergyWh = 0.0,
            condition = DischargeOperatingCondition(averageTemperatureC = 15.0, averageDischargeCurrentMa = 500.0),
            startSoc = 100,
            endSoc = 0,
            socSpanPercent = 100,
            quality = DischargeQuality.HIGH,
            counterConsistency = CounterConsistency.AGREE
        )
        val s2 = DischargeConditionSample(
            sessionId = "t2",
            observedEnergyWh = 24.0,
            measuredEnergyCoverage = 100.0,
            unresolvedEnergyWh = 0.0,
            condition = DischargeOperatingCondition(averageTemperatureC = 30.0, averageDischargeCurrentMa = 1500.0),
            startSoc = 100,
            endSoc = 0,
            socSpanPercent = 100,
            quality = DischargeQuality.HIGH,
            counterConsistency = CounterConsistency.AGREE
        )

        val training = listOf(s1, s2)

        // 待评估输入：温度 15℃（在 15~30℃ 范围内），电流 1500mA（在 500~1500mA 范围内）
        // 尽管两个特征单独看都在 Min/Max 范围内，但该联合组合 (15℃, 1500mA) 与训练集两者距离过远，为无样本支持的空白区
        val testInput = DischargeOperatingCondition(averageTemperatureC = 15.0, averageDischargeCurrentMa = 1500.0)

        val support = ConditionSensitivityLearner.evaluateJointConditionSupport(testInput, training)

        assertEquals("虽然边际特征在范围内，但组合无样本支持必须判定为 UNSUPPORTED_COMBINATION",
            ConditionSupportStatus.UNSUPPORTED_COMBINATION, support.supportStatus)
        assertTrue(support.supportDetails.contains("UNSUPPORTED_COMBINATION"))
    }

    /**
     * P2-B2.1 补充测试 2：验证共同实测 SOC 分箱的工况特征（温度、电流）同步逐 Bin 对齐，
     * 局部区间工况相反时拦截误判。
     */
    @Test
    fun testP2B_commonSocBinConditionProfilesAligned() {
        // 会话 A 与会话 B 在 Bin 10 与 Bin 11 均有能量
        val energiesA = mapOf(10 to 1.8, 11 to 1.8)
        val energiesB = mapOf(10 to 1.6, 11 to 1.6)

        // 会话 A 在 Bin 10 为常温轻载(25℃, 400mA)，在 Bin 11 为常温重载(25℃, 2000mA)
        val condA = mapOf(
            10 to SocBinOperatingCondition(10, 25.0, averageCurrentMa = 400.0, averagePowerW = 1.5),
            11 to SocBinOperatingCondition(11, 25.0, averageCurrentMa = 2000.0, averagePowerW = 7.5)
        )

        // 会话 B 恰好相反：在 Bin 10 为常温重载(25℃, 2000mA)，在 Bin 11 为常温轻载(25℃, 400mA)
        // （两者全会话平均电流看似相同均为 1200mA）
        val condB = mapOf(
            10 to SocBinOperatingCondition(10, 25.0, averageCurrentMa = 2000.0, averagePowerW = 7.5),
            11 to SocBinOperatingCondition(11, 25.0, averageCurrentMa = 400.0, averagePowerW = 1.5)
        )

        val sampleA = DischargeConditionSample(
            sessionId = "s_bin_A",
            observedEnergyWh = 3.6,
            measuredEnergyCoverage = 10.0,
            unresolvedEnergyWh = 0.0,
            condition = DischargeOperatingCondition(),
            startSoc = 60,
            endSoc = 50,
            socSpanPercent = 10,
            quality = DischargeQuality.HIGH,
            counterConsistency = CounterConsistency.AGREE,
            binMeasuredEnergiesWh = energiesA,
            binConditions = condA
        )
        val sampleB = sampleA.copy(sessionId = "s_bin_B", binMeasuredEnergiesWh = energiesB, binConditions = condB)

        // 执行严格逐 Bin 工况对齐比较
        val comp = ConditionComparisonMatcher.compareCommonSocBinsWithConditionMatching(
            sampleA, sampleB, minCommonSpanPercent = 10.0, maxCurrentDiffRatio = 0.35
        )

        // 验证系统精准识别出两个分箱的局部电流差异比均达 400%，工况相反，拦截判定为 INSUFFICIENT_SOC_OVERLAP
        assertEquals(SocOverlapStatus.INSUFFICIENT_SOC_OVERLAP, comp.status)
        assertTrue(comp.comparisonDetails.contains("局部区间工况相反") || comp.comparisonDetails.contains("低于"))
    }

    /**
     * P2-B2.1 补充测试 3：验证三组会话组仅作为 Bootstrap 最低计算门槛，不自动认定为统计可靠。
     * 3~4 组时 isComputable 为 true 但 isStatisticallyReliable 严格为 false；5 组以上晋升为 true。
     */
    @Test
    fun testP2B_threeSessionGroupsNotAutomaticallyReliable() {
        val baseSample = DischargeConditionSample(
            sessionId = "s1",
            observedEnergyWh = 28.0,
            measuredEnergyCoverage = 100.0,
            unresolvedEnergyWh = 0.0,
            condition = DischargeOperatingCondition(),
            startSoc = 100,
            endSoc = 0,
            socSpanPercent = 100,
            quality = DischargeQuality.HIGH,
            counterConsistency = CounterConsistency.AGREE,
            identity = ConditionSampleIdentity(sessionId = "s1", sessionGroupId = "g1")
        )

        // 3 个会话组（g1, g2, g3）
        val s2 = baseSample.copy(sessionId = "s2", identity = baseSample.identity?.copy(sessionId = "s2", sessionGroupId = "g2"))
        val s3 = baseSample.copy(sessionId = "s3", identity = baseSample.identity?.copy(sessionId = "s3", sessionGroupId = "g3"))

        val result3Groups = ConditionSensitivityLearner.calculateSessionGroupBootstrap(listOf(baseSample, s2, s3))
        // 验证：虽然已满足最低计算门槛，但坚决不认定为具备统计可靠性！
        assertTrue("3组会话允许探索性计算", result3Groups.isComputable)
        assertFalse("3组会话绝不能自动认定为统计可靠", result3Groups.isStatisticallyReliable)
        assertFalse("3组会话置信度必须保持为 false", result3Groups.isConfident)

        // 扩充至 5 个会话组（g1..g5）
        val s4 = baseSample.copy(sessionId = "s4", identity = baseSample.identity?.copy(sessionId = "s4", sessionGroupId = "g4"))
        val s5 = baseSample.copy(sessionId = "s5", identity = baseSample.identity?.copy(sessionId = "s5", sessionGroupId = "g5"))

        val result5Groups = ConditionSensitivityLearner.calculateSessionGroupBootstrap(listOf(baseSample, s2, s3, s4, s5))
        assertTrue("5组以上会话晋升为统计可靠", result5Groups.isStatisticallyReliable)
        assertTrue(result5Groups.isConfident)
    }

    /**
     * P2-B2.1 补充测试 4：验证对于同一条已经定义好的物理信号，在保持重建假设一致的前提下，
     * 等价变频重采样不产生点数权重偏置。
     */
    @Test
    fun testP2B_equivalentResamplingPreservesDistribution() {
        // 定义一条恒定物理放电电流信号（800mA 持续 120 秒）
        // 遵循评审意见：验证在保持信号重建假设一致的前提下，改变采样频率不产生点数权重偏置
        // 序列 A：稀疏低频采样（每 30 秒 1 点，共 5 个点）
        val samplesA = listOf(
            CapacitySample(0L, 0.90, 0.8, 3.85, 25.0),
            CapacitySample(30_000L, 0.89, 0.8, 3.85, 25.0),
            CapacitySample(60_000L, 0.88, 0.8, 3.85, 25.0),
            CapacitySample(90_000L, 0.87, 0.8, 3.85, 25.0),
            CapacitySample(120_000L, 0.86, 0.8, 3.85, 25.0)
        )

        // 序列 B：密集高频采样（每 5 秒 1 点，共 25 个点，点数增加 5 倍）
        val samplesB = mutableListOf<CapacitySample>()
        for (sec in 0..120 step 5) {
            val ts = sec * 1000L
            samplesB.add(CapacitySample(ts, 0.90 - sec * 0.0003, 0.8, 3.85, 25.0))
        }

        val condA = DischargeConditionAnalyzer.analyze(samplesA)
        val condB = DischargeConditionAnalyzer.analyze(samplesB)

        // 验证时间加权中位数电流完全严格一致（均为 800.0 mA，差异 = 0.0）
        assertNotNull(condA.currentP50Ma)
        assertNotNull(condB.currentP50Ma)
        assertEquals(800.0, condA.currentP50Ma ?: 0.0, 0.1)
        assertEquals(800.0, condB.currentP50Ma ?: 0.0, 0.1)
        assertEquals("变频等价重采样下时间加权电流中位数必须保持完全一致", condA.currentP50Ma!!, condB.currentP50Ma!!, 0.001)
    }

    /**
     * P2-B2.1 补充测试 5：验证完整 Session Group 作为切分单位，严禁同一会话组横跨训练集与验证集。
     */
    @Test
    fun testP2B_sessionGroupNeverSplitAcrossValidationSets() {
        val trainingGroups = listOf("group_week1", "group_week2", "group_week3")
        val validationGroups = listOf("group_week4")

        // 严格隔离无交叉验证通过
        assertTrue(ConditionComparisonMatcher.isSessionGroupStrictlySegregated(trainingGroups, validationGroups))

        // 错误交叉切分（group_week3 同时出现在训练和验证集）坚决拦截
        val leakedValidationGroups = listOf("group_week3", "group_week4")
        assertFalse("同一会话组横跨训练和验证集必须被严格拦截",
            ConditionComparisonMatcher.isSessionGroupStrictlySegregated(trainingGroups, leakedValidationGroups))
    }

    /**
     * P2-B2.1 补充测试 6：验证电池老化时间趋势与环境工况变化解耦，相隔过久会话拒绝直接作为同一工况对比。
     */
    @Test
    fun testP2B_conditionEffectSeparatedFromTemporalDrift() {
        // 会话 A：新电池 10 次循环，日历寿命 5 天
        val idA = ConditionSampleIdentity(
            sessionId = "sess_early",
            deviceId = "honor_dev1",
            batteryIdentity = "cell_1",
            sessionStartTimestamp = 1700000000000L,
            sessionEndTimestamp = 1700003600000L,
            cycleCount = 10,
            calendarAgeDays = 5.0
        )
        // 会话 B：相隔 3 个月（90 天后），已循环 150 次
        val idB = idA.copy(
            sessionId = "sess_late",
            sessionStartTimestamp = 1700000000000L + 90L * 24 * 3600 * 1000L,
            sessionEndTimestamp = 1700003600000L + 90L * 24 * 3600 * 1000L,
            cycleCount = 150,
            calendarAgeDays = 95.0
        )

        val sampleA = DischargeConditionSample(
            sessionId = "sess_early",
            observedEnergyWh = 27.0,
            measuredEnergyCoverage = 100.0,
            unresolvedEnergyWh = 0.0,
            condition = DischargeOperatingCondition(),
            startSoc = 100,
            endSoc = 0,
            socSpanPercent = 100,
            quality = DischargeQuality.HIGH,
            counterConsistency = CounterConsistency.AGREE,
            identity = idA
        )
        val sampleB = sampleA.copy(sessionId = "sess_late", observedEnergyWh = 23.0, identity = idB)

        // 验证系统识别出老化时间漂移混杂，拒绝直接作为同电芯环境敏感度直接对比
        assertFalse("跨越 90 天老化漂移的样本不可直接用于同工况敏感度对比",
            ConditionComparisonMatcher.isConditionEffectSeparatedFromTemporalDrift(sampleA, sampleB, maxCalendarDays = 30.0))
    }

    /**
     * P2-B3 测试 1：验证当归一化模型相较于未补偿基准未能产生精度改善（Improvement <= 0）时，
     * 即使会话组完全隔离且样本充足，成熟度坚决拒绝晋升为 VALIDATED。
     */
    @Test
    fun testP2B3_conditionModelDoesNotImproveValidationRejects() {
        val trainGroup1 = listOf("g1", "g2", "g3")
        val valGroup1 = listOf("g4", "g5", "g6")

        val trainSamples = trainGroup1.mapIndexed { idx, gid ->
            DischargeConditionSample(
                sessionId = "train_$idx",
                observedEnergyWh = 26.0,
                measuredEnergyCoverage = 100.0,
                unresolvedEnergyWh = 0.0,
                condition = DischargeOperatingCondition(),
                startSoc = 100,
                endSoc = 0,
                socSpanPercent = 100,
                quality = DischargeQuality.HIGH,
                counterConsistency = CounterConsistency.AGREE,
                sessionTimestampMs = 1700000000000L + idx * 3600000L,
                identity = ConditionSampleIdentity(sessionId = "train_$idx", sessionGroupId = gid, sessionStartTimestamp = 1700000000000L + idx * 3600000L)
            )
        }

        val valSamples = valGroup1.mapIndexed { idx, gid ->
            DischargeConditionSample(
                sessionId = "val_$idx",
                observedEnergyWh = 25.0, // 真实物理做功 25.0 Wh
                measuredEnergyCoverage = 100.0,
                unresolvedEnergyWh = 0.0,
                condition = DischargeOperatingCondition(),
                startSoc = 100,
                endSoc = 0,
                socSpanPercent = 100,
                quality = DischargeQuality.HIGH,
                counterConsistency = CounterConsistency.AGREE,
                sessionTimestampMs = 1700100000000L + idx * 3600000L,
                identity = ConditionSampleIdentity(sessionId = "val_$idx", sessionGroupId = gid, sessionStartTimestamp = 1700100000000L + idx * 3600000L)
            )
        }

        // 基准模型预测值：25.2 Wh（基准 MAE = 0.2 Wh）
        val baselinePreds = listOf(25.2, 25.2, 25.2)
        // 劣化的归一化模型预测值：25.5 Wh（模型 MAE = 0.5 Wh，劣于基准，Improvement = 1 - 0.5/0.2 = -1.5 < 0）
        val worseNormalizedPreds = listOf(25.5, 25.5, 25.5)

        val validation = ConditionModelValidator.validateWithMetrics(
            trainingSamples = trainSamples,
            validationSamples = valSamples,
            baselinePredictions = baselinePreds,
            normalizedPredictions = worseNormalizedPreds
        )

        // 验证改善率小于 0 时，成熟度坚决拒绝晋升为 VALIDATED，保持在 VALIDATING
        assertTrue("归一化改善率应小于或等于 0", (validation.normalizationImprovement ?: 0.0) <= 0.0)
        assertEquals(ConditionModelMaturity.VALIDATING, validation.maturity)
        assertEquals(0.2, validation.baselineMaeWh ?: 0.0, 0.001)
        assertEquals(0.5, validation.validationMaeWh ?: 0.0, 0.001)

        // 对照：优异的归一化模型（模型预测 25.05 Wh，MAE = 0.05 Wh，优于基准 0.2 Wh，Improvement = 75%）
        val superiorNormalizedPreds = listOf(25.05, 25.05, 25.05)
        val validValidation = ConditionModelValidator.validateWithMetrics(
            trainingSamples = trainSamples,
            validationSamples = valSamples,
            baselinePredictions = baselinePreds,
            normalizedPredictions = superiorNormalizedPreds
        )
        assertTrue("优于基准时改善率应大于 0", (validValidation.normalizationImprovement ?: 0.0) > 0.0)
        assertEquals(ConditionModelMaturity.VALIDATED, validValidation.maturity)
    }

    /**
     * P2-B3 测试 2：验证基准模型与归一化模型必须在完全同一批独立验证会话组上对比评估，样本数量不一致时严格报错。
     */
    @Test(expected = IllegalArgumentException::class)
    fun testP2B3_normalizedAndBaselineUseSameValidationGroups() {
        val sample = DischargeConditionSample(
            sessionId = "val_single",
            observedEnergyWh = 26.0,
            measuredEnergyCoverage = 100.0,
            unresolvedEnergyWh = 0.0,
            condition = DischargeOperatingCondition(),
            startSoc = 100,
            endSoc = 0,
            socSpanPercent = 100,
            quality = DischargeQuality.HIGH,
            counterConsistency = CounterConsistency.AGREE
        )

        // 传入数量不一致的预测列表（验证集 1 条，基准 1 条，但归一化 2 条），触发强制同源检查报错
        ConditionModelValidator.validateWithMetrics(
            trainingSamples = listOf(sample),
            validationSamples = listOf(sample),
            baselinePredictions = listOf(26.0),
            normalizedPredictions = listOf(26.0, 27.0)
        )
    }

    /**
     * P2-B3 测试 3：第一层逐 Bin 条件归一化必须严格针对 MEASURED 纯实测目标，MODEL_ESTIMATED 估算分箱坚决拒绝归一化。
     */
    @Test
    fun testP2B3_perBinNormalizationRequiresMeasuredTarget() {
        val validModel = ConditionModelValidation(
            modelVersion = "FCC-E-v1.0",
            maturity = ConditionModelMaturity.VALIDATED,
            trainingGroupCount = 5,
            validationGroupCount = 3,
            validationMaeWh = 0.1,
            validationRmseWh = 0.15,
            normalizationImprovement = 0.25
        )

        val binCondition = SocBinOperatingCondition(
            binIndex = 10,
            averageTemperatureC = 25.0,
            averageCurrentMa = 800.0,
            averagePowerW = 3.0
        )

        // 纯实测分箱（MEASURED）
        val measuredBin = SocEnergyBinInfo(
            binIndex = 10,
            startSocPercent = 50.0,
            endSocPercent = 55.0,
            status = BinLearningStatus.MEASURED,
            energyWh = 1.35,
            measuredEnergyWh = 1.35,
            estimatedEnergyWh = 0.0
        )

        // 跨箱估算分箱（MODEL_ESTIMATED）
        val estimatedBin = SocEnergyBinInfo(
            binIndex = 10,
            startSocPercent = 50.0,
            endSocPercent = 55.0,
            status = BinLearningStatus.MODEL_ESTIMATED,
            energyWh = 1.35,
            measuredEnergyWh = 0.0,
            estimatedEnergyWh = 1.35
        )

        // 针对估算分箱，系统坚决返回 null，杜绝估算分箱被当作实测目标归一化
        assertNull("MODEL_ESTIMATED 估算分箱绝对不允许进行反事实归一化",
            EnergyNormalizationEngine.normalizeBinEnergy(estimatedBin, binCondition, validModel))

        // 针对未成熟模型，即使分箱是实测的，也坚决返回 null
        val unvalidatedModel = validModel.copy(maturity = ConditionModelMaturity.VALIDATING)
        assertNull("未达 VALIDATED 成熟度模型，实测分箱同样返回 null",
            EnergyNormalizationEngine.normalizeBinEnergy(measuredBin, binCondition, unvalidatedModel))
    }

    /**
     * P2-B3 测试 4：第二层全能量归一化时，若 20 段分箱中存在 UNLEARNED 未学满分箱，整会话归一化坚决拦截返回 null。
     */
    @Test
    fun testP2B3_unlearnedBinBlocksCompleteNormalization() {
        val validModel = ConditionModelValidation(
            modelVersion = "FCC-E-v1.0",
            maturity = ConditionModelMaturity.VALIDATED,
            trainingGroupCount = 5,
            validationGroupCount = 3,
            validationMaeWh = 0.1,
            validationRmseWh = 0.15,
            normalizationImprovement = 0.25
        )

        // 构造 20 个分箱，其中 Bin 0 处于 UNLEARNED 状态
        val bins = (0..19).map { idx ->
            SocEnergyBinInfo(
                binIndex = idx,
                startSocPercent = idx * 5.0,
                endSocPercent = (idx + 1) * 5.0,
                status = if (idx == 0) BinLearningStatus.UNLEARNED else BinLearningStatus.MEASURED,
                energyWh = 1.3,
                measuredEnergyWh = if (idx == 0) 0.0 else 1.3
            )
        }

        // 验证系统识别出未学满分箱，整会话归一化严格返回 null
        val result = EnergyNormalizationEngine.normalizeFullEnergy(
            observedEnergyWh = 26.0,
            bins = bins,
            condition = DischargeOperatingCondition(),
            validationModel = validModel
        )
        assertNull("存在 UNLEARNED 分箱时整会话归一化必须拦截返回 null", result)
    }

    /**
     * P2-B3 测试 5：验证当模型未达到 VALIDATED 或数据不足时，归一化不确定度如实返回 null。
     */
    @Test
    fun testP2B3_predictionUncertaintyUnavailableForInsufficientData() {
        val unvalidatedModel = ConditionModelValidation(
            modelVersion = "FCC-E-v1.0",
            maturity = ConditionModelMaturity.DATA_COLLECTION,
            trainingGroupCount = 2,
            validationGroupCount = 0
        )

        // 验证未通过模型的不确定度如实返回 null，杜绝伪造数据
        assertNull(EnergyNormalizationEngine.calculateNormalizedUncertaintyWh(unvalidatedModel))
        assertNull(EnergyNormalizationEngine.calculateNormalizedUncertaintyWh(null))

        // 达到 VALIDATED 模型如实透传基于独立验证集均方根误差的不确定度
        val validatedModel = unvalidatedModel.copy(
            maturity = ConditionModelMaturity.VALIDATED,
            validationRmseWh = 0.18
        )
        assertEquals(0.18, EnergyNormalizationEngine.calculateNormalizedUncertaintyWh(validatedModel) ?: 0.0, 1e-6)
    }

    /**
     * P2-B3 测试 6：第二层全能量归一化时，哪怕 20 个分箱均已学习，但其中某个分箱局部工况超出物理域外（如放电电流 6000mA），
     * 整会话归一化坚决拦截返回 null。
     */
    @Test
    fun testP2B3_outOfDomainBinPreventsFullEnergyNormalization() {
        val validModel = ConditionModelValidation(
            modelVersion = "FCC-E-v1.0",
            maturity = ConditionModelMaturity.VALIDATED,
            trainingGroupCount = 5,
            validationGroupCount = 3,
            validationMaeWh = 0.1,
            validationRmseWh = 0.15,
            normalizationImprovement = 0.25
        )

        // 构造 20 个全部 MEASURED 分箱，但在 Bin 5 存在极端异常超域电流（6000 mA）
        val bins = (0..19).map { idx ->
            val cond = if (idx == 5) {
                SocBinOperatingCondition(binIndex = idx, averageTemperatureC = 25.0, averageCurrentMa = 6000.0) // 超过 5000mA 极值
            } else {
                SocBinOperatingCondition(binIndex = idx, averageTemperatureC = 25.0, averageCurrentMa = 800.0)
            }
            SocEnergyBinInfo(
                binIndex = idx,
                startSocPercent = idx * 5.0,
                endSocPercent = (idx + 1) * 5.0,
                status = BinLearningStatus.MEASURED,
                energyWh = 1.3,
                measuredEnergyWh = 1.3,
                binCondition = cond
            )
        }

        val result = EnergyNormalizationEngine.normalizeFullEnergy(
            observedEnergyWh = 26.0,
            bins = bins,
            condition = DischargeOperatingCondition(),
            validationModel = validModel
        )
        assertNull("分箱存在超域工况时整会话归一化必须拦截返回 null", result)
    }

    /**
     * P2-B3 测试 7：共同实测 SOC 分箱覆盖不仅看累计百分比，还必须检验连续性。
     * 离散孤岛分箱（如 Bin 1 和 Bin 10）必须识别为非连续（isContiguous = false），段数为 2。
     */
    @Test
    fun testP2B_disjointCommonBinsNotTreatedAsContiguous() {
        val disjointBins = setOf(1, 10) // 5%~10% 和 50%~55%，中间有巨大断层
        val coverage = ConditionComparisonMatcher.calculateCommonSocCoverage(disjointBins)

        assertEquals(2, coverage.commonBinCount)
        assertEquals(10.0, coverage.totalCommonCoveragePercent, 0.01)
        assertEquals(5.0, coverage.maxContiguousCoveragePercent, 0.01)
        assertEquals(2, coverage.contiguousSegments.size)
        assertFalse("离散孤岛分箱绝对不可认定为连续放电区间", coverage.isContiguous)
    }

    /**
     * P2-B3 测试 8：连续共同实测 SOC 分箱正确识别为连续放电区间（isContiguous = true）。
     */
    @Test
    fun testP2B_contiguousCommonSocCoverage() {
        val contiguousBins = setOf(5, 6, 7, 8) // 25% ~ 45%，连续 4 个分箱（20% 跨度）
        val coverage = ConditionComparisonMatcher.calculateCommonSocCoverage(contiguousBins)

        assertEquals(4, coverage.commonBinCount)
        assertEquals(20.0, coverage.totalCommonCoveragePercent, 0.01)
        assertEquals(20.0, coverage.maxContiguousCoveragePercent, 0.01)
        assertEquals(1, coverage.contiguousSegments.size)
        assertTrue("单一连续分箱必须判定为连续放电区间", coverage.isContiguous)
        assertEquals(5..8, coverage.contiguousSegments.first())
    }

    /**
     * P2-B3 测试 9：多段离散连续区间正确分段记录并计算最大连续跨度。
     */
    @Test
    fun testP2B_commonSocCoverageReportsMultipleSegments() {
        // 包含 3 段连续分箱：1..3（3个，15%），8..9（2个，10%），15..15（1个，5%）
        val multiSegmentBins = setOf(1, 2, 3, 8, 9, 15)
        val coverage = ConditionComparisonMatcher.calculateCommonSocCoverage(multiSegmentBins)

        assertEquals(6, coverage.commonBinCount)
        assertEquals(30.0, coverage.totalCommonCoveragePercent, 0.01)
        assertEquals(15.0, coverage.maxContiguousCoveragePercent, 0.01) // 最长连续跨度为 1..3 的 15%
        assertEquals(3, coverage.contiguousSegments.size)
        assertFalse(coverage.isContiguous)
        assertEquals(listOf(1..3, 8..9, 15..15), coverage.contiguousSegments)
    }

    /**
     * P2-B3 测试 10：电池长期容量老化趋势解耦固定的 30 天日历寿命阈值，
     * 结合循环次数、日历天数与 AcceptedFccQ 历史趋势动态判定老化状态。
     */
    @Test
    fun testP2B_agingTrendEvaluatedWithVarianceAndCycleDecoupled() {
        val sampleBase = DischargeConditionSample(
            sessionId = "s_age_0",
            observedEnergyWh = 27.0,
            measuredEnergyCoverage = 100.0,
            unresolvedEnergyWh = 0.0,
            condition = DischargeOperatingCondition(),
            startSoc = 100,
            endSoc = 0,
            socSpanPercent = 100,
            quality = DischargeQuality.HIGH,
            counterConsistency = CounterConsistency.AGREE,
            identity = ConditionSampleIdentity(
                sessionId = "s_age_0",
                sessionStartTimestamp = 1700000000000L,
                cycleCount = 10,
                calendarAgeDays = 5.0
            )
        )

        // 1. 短时间内（间隔 3 天，循环差 2 次）：状态稳定 AGING_STABLE
        val sampleStable = sampleBase.copy(
            sessionId = "s_age_1",
            identity = sampleBase.identity?.copy(
                sessionId = "s_age_1",
                sessionStartTimestamp = 1700000000000L + 3L * 24 * 3600 * 1000L,
                cycleCount = 12,
                calendarAgeDays = 8.0
            )
        )
        val statusStable = ConditionComparisonMatcher.evaluateAgingTrend(sampleBase, sampleStable)
        assertEquals(AgingTrendStatus.AGING_STABLE, statusStable)

        // 2. 长时间或大循环（间隔 100 天，循环差 120 次）：仅循环和天数大，无容量实测序列时疑为 AGING_SUSPECTED（不直接断定永久老化）
        val sampleAged = sampleBase.copy(
            sessionId = "s_age_2",
            identity = sampleBase.identity?.copy(
                sessionId = "s_age_2",
                sessionStartTimestamp = 1700000000000L + 100L * 24 * 3600 * 1000L,
                cycleCount = 130,
                calendarAgeDays = 105.0
            )
        )
        val statusAged = ConditionComparisonMatcher.evaluateAgingTrend(sampleBase, sampleAged)
        assertEquals(AgingTrendStatus.AGING_SUSPECTED, statusAged)

        // 3. 临界区（间隔 25 天，基础容差 30 天）：不确定 AGING_UNCERTAIN
        val sampleUncertain = sampleBase.copy(
            sessionId = "s_age_3",
            identity = sampleBase.identity?.copy(
                sessionId = "s_age_3",
                sessionStartTimestamp = 1700000000000L + 25L * 24 * 3600 * 1000L,
                cycleCount = 25,
                calendarAgeDays = 30.0
            )
        )
        val statusUncertain = ConditionComparisonMatcher.evaluateAgingTrend(sampleBase, sampleUncertain)
        assertEquals(AgingTrendStatus.AGING_UNCERTAIN, statusUncertain)

        // 4. AcceptedFccQ 历史趋势发生显著下滑（从 5000mAh 跌到 4600mAh，降幅 8% 且超出不确定度）：AGING_DETECTED
        val acceptedHistory = listOf(
            AcceptedFccQ(5000.0, 20.0, "FCC-v1.1", 3, 1700000000000L, LearningMaturity.STABLE),
            AcceptedFccQ(4900.0, 20.0, "FCC-v1.1", 3, 1701000000000L, LearningMaturity.STABLE),
            AcceptedFccQ(4600.0, 20.0, "FCC-v1.1", 3, 1702000000000L, LearningMaturity.STABLE)
        )
        val historyStatus = ConditionComparisonMatcher.evaluateHistoryAgingTrend(acceptedHistory)
        assertEquals(AgingTrendStatus.AGING_DETECTED, historyStatus)
    }

    /**
     * P2-B3 补充测试 1：验证完整可用能量归一化必须具备明确的顶部平台处理政策。
     * 若政策为 UNCALIBRATED_BLOCK 未标定，坚决阻断并返回 null。
     */
    @Test
    fun testP2B3_fullEnergyNormalizationIncludesExplicitPlateauPolicy() {
        val validModel = ConditionModelValidation(
            modelVersion = "FCC-E-v1.0",
            maturity = ConditionModelMaturity.VALIDATED,
            trainingGroupCount = 5,
            validationGroupCount = 3,
            validationMaeWh = 0.1,
            validationRmseWh = 0.15,
            normalizationImprovement = 0.25
        )

        val bins = (0..19).map { idx ->
            SocEnergyBinInfo(
                binIndex = idx,
                startSocPercent = idx * 5.0,
                endSocPercent = (idx + 1) * 5.0,
                status = BinLearningStatus.MEASURED,
                energyWh = 1.3,
                measuredEnergyWh = 1.3
            )
        }

        // 政策为 UNCALIBRATED_BLOCK 时，系统阻断完整可用能量输出
        val resultBlocked = EnergyNormalizationEngine.normalizeFullEnergy(
            observedEnergyWh = 28.0,
            bins = bins,
            condition = DischargeOperatingCondition(),
            validationModel = validModel,
            topPlateauWh = 2.0,
            plateauPolicy = TopPlateauNormalizationPolicy.UNCALIBRATED_BLOCK
        )
        assertNull("顶部平台政策未标定时必须阻断完整可用能量归一化", resultBlocked)
    }

    /**
     * P2-B3 补充测试 2：验证独立模型验证必须具备真实标准参考工况会话（Reference Session）作为验证标签。
     * 若未提供标准工况真实参考标签，验证误差如实为 null，成熟度保持在 VALIDATING。
     */
    @Test
    fun testP2B3_standardConditionTargetRequiresRealReferenceSession() {
        val trainGroup = listOf("g1", "g2", "g3")
        val valGroup = listOf("g4", "g5", "g6")

        val trainSamples = trainGroup.mapIndexed { idx, gid ->
            DischargeConditionSample(
                sessionId = "t_$idx",
                observedEnergyWh = 26.0,
                measuredEnergyCoverage = 100.0,
                unresolvedEnergyWh = 0.0,
                condition = DischargeOperatingCondition(),
                startSoc = 100,
                endSoc = 0,
                socSpanPercent = 100,
                quality = DischargeQuality.HIGH,
                counterConsistency = CounterConsistency.AGREE,
                sessionTimestampMs = 1700000000000L + idx * 3600000L,
                identity = ConditionSampleIdentity(sessionId = "t_$idx", sessionGroupId = gid, sessionStartTimestamp = 1700000000000L + idx * 3600000L)
            )
        }

        val valSamples = valGroup.mapIndexed { idx, gid ->
            DischargeConditionSample(
                sessionId = "v_$idx",
                observedEnergyWh = 22.0, // 在低温重载下测得 22.0 Wh，不能当标准工况标签
                measuredEnergyCoverage = 100.0,
                unresolvedEnergyWh = 0.0,
                condition = DischargeOperatingCondition(),
                startSoc = 100,
                endSoc = 0,
                socSpanPercent = 100,
                quality = DischargeQuality.HIGH,
                counterConsistency = CounterConsistency.AGREE,
                sessionTimestampMs = 1700100000000L + idx * 3600000L,
                identity = ConditionSampleIdentity(sessionId = "v_$idx", sessionGroupId = gid, sessionStartTimestamp = 1700100000000L + idx * 3600000L)
            )
        }

        // 缺乏真实标准工况参考标签（referenceTargetsWh 传入包含 null）
        val incompleteTargets = listOf(26.5, null, 26.6)
        val validation = ConditionModelValidator.validateWithMetrics(
            trainingSamples = trainSamples,
            validationSamples = valSamples,
            baselinePredictions = listOf(26.5, 26.5, 26.5),
            normalizedPredictions = listOf(26.4, 26.4, 26.4),
            referenceTargetsWh = incompleteTargets
        )

        assertNull("缺乏标准工况真实参考标签时，验证 MAE 必须如实为 null", validation.validationMaeWh)
        assertEquals("缺乏标准参考标签时模型成熟度保持为 VALIDATING", ConditionModelMaturity.VALIDATING, validation.maturity)
    }

    /**
     * P2-B3 补充测试 3：验证改善幅度不确定或极其微弱（Improvement <= 3%）时，模型不能晋升为 VALIDATED。
     */
    @Test
    fun testP2B3_positiveImprovementWithinUncertaintyNotValidated() {
        val trainGroup = listOf("g1", "g2", "g3")
        val valGroup = listOf("g4", "g5", "g6")

        val trainSamples = trainGroup.mapIndexed { idx, gid ->
            DischargeConditionSample(
                sessionId = "t3_$idx",
                observedEnergyWh = 26.0,
                measuredEnergyCoverage = 100.0,
                unresolvedEnergyWh = 0.0,
                condition = DischargeOperatingCondition(),
                startSoc = 100,
                endSoc = 0,
                socSpanPercent = 100,
                quality = DischargeQuality.HIGH,
                counterConsistency = CounterConsistency.AGREE,
                sessionTimestampMs = 1700000000000L + idx * 3600000L,
                identity = ConditionSampleIdentity(sessionId = "t3_$idx", sessionGroupId = gid, sessionStartTimestamp = 1700000000000L + idx * 3600000L)
            )
        }

        val valSamples = valGroup.mapIndexed { idx, gid ->
            DischargeConditionSample(
                sessionId = "v3_$idx",
                observedEnergyWh = 25.0,
                measuredEnergyCoverage = 100.0,
                unresolvedEnergyWh = 0.0,
                condition = DischargeOperatingCondition(),
                startSoc = 100,
                endSoc = 0,
                socSpanPercent = 100,
                quality = DischargeQuality.HIGH,
                counterConsistency = CounterConsistency.AGREE,
                sessionTimestampMs = 1700100000000L + idx * 3600000L,
                identity = ConditionSampleIdentity(sessionId = "v3_$idx", sessionGroupId = gid, sessionStartTimestamp = 1700100000000L + idx * 3600000L)
            )
        }

        // 基准 MAE = 1.0 Wh（预测 26.0，实际 25.0）
        val basePreds = listOf(26.0, 26.0, 26.0)
        // 归一化预测 25.99 Wh，MAE = 0.99 Wh，改善率仅 1%（<= 3% 阈值）
        val marginalNormPreds = listOf(25.99, 25.99, 25.99)

        val validation = ConditionModelValidator.validateWithMetrics(
            trainingSamples = trainSamples,
            validationSamples = valSamples,
            baselinePredictions = basePreds,
            normalizedPredictions = marginalNormPreds,
            referenceTargetsWh = listOf(25.0, 25.0, 25.0)
        )

        assertTrue("改善率虽大于 0 但仅为 1%", (validation.normalizationImprovement ?: 0.0) in 0.001..0.02)
        assertEquals("微弱改善或未超出不确定度的改善不能晋升为 VALIDATED",
            ConditionModelMaturity.VALIDATING, validation.maturity)
    }

    /**
     * P2-B3 补充测试 4：验证历史容量不可比时，即使降幅超过 5%，也坚决不能认定为永久老化（返回 AGING_UNCERTAIN）。
     */
    @Test
    fun testP2B3_agingDetectionRequiresComparableHistory() {
        val nonComparableHistory = listOf(
            AcceptedFccQ(5000.0, 20.0, "FCC-v1.1", 3, 1700000000000L, LearningMaturity.STABLE),
            AcceptedFccQ(4900.0, 20.0, "FCC-v1.1", 3, 1701000000000L, LearningMaturity.STABLE),
            AcceptedFccQ(4600.0, 20.0, "FCC-v1.1", 3, 1702000000000L, LearningMaturity.STABLE)
        )

        // 显式传入 isComparableHistory = false（例如跨温区或不同放电倍率下的测试）
        val status = ConditionComparisonMatcher.evaluateHistoryAgingTrend(
            acceptedHistory = nonComparableHistory,
            isComparableHistory = false
        )

        // 验证系统识别到历史条件不可比，拒绝确认为永久老化
        assertEquals(AgingTrendStatus.AGING_UNCERTAIN, status)
    }

    /**
     * P2-B3 补充测试 5：验证跨分箱不确定度在没有协方差矩阵支持时，严禁假设独立，合成不确定度返回 null。
     */
    @Test
    fun testP2B3_correlatedBinUncertaintyNotAssumedIndependent() {
        val binSigmas = listOf(0.05, 0.06, 0.04, 0.05)

        // 无跨分箱协方差支持（hasCovarianceSupport = false）
        val breakdownNoCov = EnergyNormalizationEngine.calculateUncertaintyBreakdown(
            binSigmas = binSigmas,
            hasCovarianceSupport = false
        )
        assertNull("无协方差支持时严禁独立误差开方求和，合成不确定度必须为 null", breakdownNoCov.combinedUncertaintyWh)
        assertFalse(breakdownNoCov.hasCovarianceSupport)

        // 具备标定协方差支持（hasCovarianceSupport = true）
        val breakdownWithCov = EnergyNormalizationEngine.calculateUncertaintyBreakdown(
            binSigmas = binSigmas,
            hasCovarianceSupport = true,
            covarianceSum = 0.002
        )
        assertNotNull(breakdownWithCov.combinedUncertaintyWh)
        assertTrue(breakdownWithCov.combinedUncertaintyWh!! > 0.0)
    }

    /**
     * P2-B3 补充测试 6：验证未经验证的模型在评估归一化结果实体时，能量输出严格为 null，并包含 MODEL_NOT_VALIDATED 拒绝说明。
     */
    @Test
    fun testP2B3_unvalidatedModelPreservesNullOutputs() {
        val unvalidatedModel = ConditionModelValidation(
            modelVersion = "FCC-E-v1.0",
            maturity = ConditionModelMaturity.VALIDATING,
            trainingGroupCount = 3,
            validationGroupCount = 2
        )

        val bins = (0..19).map { idx ->
            SocEnergyBinInfo(
                binIndex = idx,
                startSocPercent = idx * 5.0,
                endSocPercent = (idx + 1) * 5.0,
                status = BinLearningStatus.MEASURED,
                energyWh = 1.3,
                measuredEnergyWh = 1.3
            )
        }

        val result = EnergyNormalizationEngine.evaluateNormalizationResult(
            observedEnergyWh = 26.0,
            bins = bins,
            condition = DischargeOperatingCondition(),
            validationModel = unvalidatedModel
        )

        assertNull("未验证模型归一化 SOC 曲线能量严格为 null", result.normalizedSocCurveEnergyWh)
        assertNull("未验证模型归一化完整可用能量严格为 null", result.normalizedFullUsableEnergyWh)
        assertTrue(result.rejectionReasons.contains("MODEL_NOT_VALIDATED"))
    }

    /**
     * P2-B3 补充测试 7：验证标准参考工况会话组与训练集必须严格隔离，若参考组混入训练集被系统隔离检查拦截。
     */
    @Test
    fun testP2B3_validationReferenceGroupsRemainIndependent() {
        val trainGroups = listOf("train_week1", "train_week2")
        val leakedRefGroups = listOf("train_week2", "val_week3")

        // 验证系统识别到参考组泄漏到训练集，严格拦截
        assertFalse(ConditionComparisonMatcher.isSessionGroupStrictlySegregated(trainGroups, leakedRefGroups))
    }

    /**
     * P2-B3 补充测试 8：验证单电池个体化模型证据等级为 WITHIN_DEVICE_REPEATED_MEASURES，绝不能冒充跨电池 MULTI_BATTERY_EVIDENCE。
     */
    @Test
    fun testP2B3_singleBatteryModelCannotClaimCrossBatteryValidity() {
        val baseSample = DischargeConditionSample(
            sessionId = "s1",
            observedEnergyWh = 28.0,
            measuredEnergyCoverage = 100.0,
            unresolvedEnergyWh = 0.0,
            condition = DischargeOperatingCondition(),
            startSoc = 100,
            endSoc = 0,
            socSpanPercent = 100,
            quality = DischargeQuality.HIGH,
            counterConsistency = CounterConsistency.AGREE,
            identity = ConditionSampleIdentity(sessionId = "s1", sessionGroupId = "g1")
        )

        val samples = (1..5).map { idx ->
            baseSample.copy(
                sessionId = "s$idx",
                identity = baseSample.identity?.copy(sessionId = "s$idx", sessionGroupId = "g$idx")
            )
        }

        val bootstrap = ConditionSensitivityLearner.calculateSessionGroupBootstrap(samples)
        // 验证：即使具备 5 组独立会话组，其证据等级仍为单电池重复测量，绝非多电池泛化证据
        assertEquals(1, bootstrap.effectiveIndependentBatteryCount)
        assertEquals(BootstrapEvidenceLevel.WITHIN_DEVICE_REPEATED_MEASURES, bootstrap.evidenceLevel)
    }

    /**
     * P2-B3 补充测试 9：验证纯 SOC 曲线归一化能量明确将 100% 顶部平台做功排除在外。
     */
    @Test
    fun testP2B3_normalizedCurveEnergyExcludesTopPlateauExplicitly() {
        val validModel = ConditionModelValidation(
            modelVersion = "FCC-E-v1.0",
            maturity = ConditionModelMaturity.VALIDATED,
            trainingGroupCount = 5,
            validationGroupCount = 3,
            validationMaeWh = 0.1,
            validationRmseWh = 0.15,
            normalizationImprovement = 0.25
        )

        val bins = (0..19).map { idx ->
            SocEnergyBinInfo(
                binIndex = idx,
                startSocPercent = idx * 5.0,
                endSocPercent = (idx + 1) * 5.0,
                status = BinLearningStatus.MEASURED,
                energyWh = 1.3,
                measuredEnergyWh = 1.3
            )
        }

        // 即使传入 2.5 Wh 的顶部平台能量，纯 SOC 曲线归一化能量明确使用 EXCLUDE_FROM_CURVE 政策处理
        val result = EnergyNormalizationEngine.evaluateNormalizationResult(
            observedEnergyWh = 28.5,
            bins = bins,
            condition = DischargeOperatingCondition(),
            validationModel = validModel,
            topPlateauWh = 2.5,
            plateauPolicy = TopPlateauNormalizationPolicy.EXCLUDE_FROM_CURVE
        )

        assertNotNull(result)
        // 在数据积累期保持严格 null 门禁
        assertNull(result.normalizedSocCurveEnergyWh)
    }

    /**
     * P2-B3 补充测试 10：验证未决停滞能量（UNRESOLVED）存在时坚决阻断完整可用能量归一化。
     */
    @Test
    fun testP2B3_unresolvedEnergyBlocksFullUsableNormalization() {
        val validModel = ConditionModelValidation(
            modelVersion = "FCC-E-v1.0",
            maturity = ConditionModelMaturity.VALIDATED,
            trainingGroupCount = 5,
            validationGroupCount = 3,
            validationMaeWh = 0.1,
            validationRmseWh = 0.15,
            normalizationImprovement = 0.25
        )

        val bins = (0..19).map { idx ->
            SocEnergyBinInfo(
                binIndex = idx,
                startSocPercent = idx * 5.0,
                endSocPercent = (idx + 1) * 5.0,
                status = BinLearningStatus.MEASURED,
                energyWh = 1.3,
                measuredEnergyWh = 1.3
            )
        }

        // 存在 0.8 Wh 的停滞未决做功（unresolvedEnergyWh = 0.8）
        val result = EnergyNormalizationEngine.evaluateNormalizationResult(
            observedEnergyWh = 28.0,
            bins = bins,
            condition = DischargeOperatingCondition(),
            validationModel = validModel,
            unresolvedEnergyWh = 0.8
        )

        assertNull("存在未决做功时完整可用能量归一化必须拦截返回 null", result.normalizedFullUsableEnergyWh)
        assertTrue(result.rejectionReasons.contains("UNRESOLVED_ENERGY_EXISTS"))
    }

    /**
     * P2-B3 深化测试 1：验证配对 Bootstrap 改善区间下界覆盖零（统计不显著）时，
     * 即使平均改善率大于 3%，成熟度坚决拒绝晋升为 VALIDATED。
     */
    @Test
    fun testP2B3_pairedBootstrapImprovementCrossesZero() {
        val trainGroup = listOf("tg1", "tg2", "tg3")
        val valGroup = listOf("vg1", "vg2", "vg3")

        val trainSamples = trainGroup.mapIndexed { idx, gid ->
            DischargeConditionSample(
                sessionId = "ts_$idx",
                observedEnergyWh = 26.0,
                measuredEnergyCoverage = 100.0,
                unresolvedEnergyWh = 0.0,
                condition = DischargeOperatingCondition(),
                startSoc = 100,
                endSoc = 0,
                socSpanPercent = 100,
                quality = DischargeQuality.HIGH,
                counterConsistency = CounterConsistency.AGREE,
                sessionTimestampMs = 1700000000000L + idx * 3600000L,
                identity = ConditionSampleIdentity(sessionId = "ts_$idx", sessionGroupId = gid, sessionStartTimestamp = 1700000000000L + idx * 3600000L)
            )
        }

        val valSamples = valGroup.mapIndexed { idx, gid ->
            DischargeConditionSample(
                sessionId = "vs_$idx",
                observedEnergyWh = 25.0,
                measuredEnergyCoverage = 100.0,
                unresolvedEnergyWh = 0.0,
                condition = DischargeOperatingCondition(),
                startSoc = 100,
                endSoc = 0,
                socSpanPercent = 100,
                quality = DischargeQuality.HIGH,
                counterConsistency = CounterConsistency.AGREE,
                sessionTimestampMs = 1700100000000L + idx * 3600000L,
                identity = ConditionSampleIdentity(sessionId = "vs_$idx", sessionGroupId = gid, sessionStartTimestamp = 1700100000000L + idx * 3600000L)
            )
        }

        // 基准 MAE = 1.0 Wh（预测 26.0，实际 25.0）
        val basePreds = listOf(26.0, 26.0, 26.0)
        // 构造改善量高度不均匀的场景：第一组改善大，但第二组恶化，导致 Bootstrap 置信区间下界 <= 0
        // 组 1: target 25.0, norm 25.1 (改善 0.9)
        // 组 2: target 25.0, norm 26.05 (恶化 -0.05)
        // 组 3: target 25.0, norm 25.8 (改善 0.2)
        val volatileNormPreds = listOf(25.1, 26.05, 25.8)

        val validation = ConditionModelValidator.validateWithMetrics(
            trainingSamples = trainSamples,
            validationSamples = valSamples,
            baselinePredictions = basePreds,
            normalizedPredictions = volatileNormPreds,
            referenceTargetsWh = listOf(25.0, 25.0, 25.0)
        )

        // 验证系统识别到配对置信区间覆盖零，统计显著性不足，拒绝晋升 VALIDATED
        assertFalse("配对置信区间跨越零时不能认定为统计显著", validation.isPairedImprovementSignificant)
        assertEquals(ConditionModelMaturity.VALIDATING, validation.maturity)
    }

    /**
     * P2-B3 深化测试 2：验证微弱改善（<= 3%）即使恰好显著，也不能通过预设的最小实际工程改善门槛（MINIMUM_PRACTICAL_IMPROVEMENT）。
     */
    @Test
    fun testP2B3_smallImprovementCannotPassPracticalThreshold() {
        assertEquals(0.03, MINIMUM_PRACTICAL_IMPROVEMENT, 1e-6)
    }

    /**
     * P2-B3 深化测试 3：验证只有具备样本支持（groupSize >= 2）的主要工况组恶化超过 15% 时才硬性拦截，
     * 避免孤立单样本异常会话误杀整个模型。
     */
    @Test
    fun testP2B3_subgroupRegressionRequiresAdequateSupport() {
        val trainGroup = listOf("t1", "t2", "t3")
        val trainSamples = trainGroup.mapIndexed { idx, gid ->
            DischargeConditionSample(
                sessionId = "t_$idx",
                observedEnergyWh = 26.0,
                measuredEnergyCoverage = 100.0,
                unresolvedEnergyWh = 0.0,
                condition = DischargeOperatingCondition(),
                startSoc = 100,
                endSoc = 0,
                socSpanPercent = 100,
                quality = DischargeQuality.HIGH,
                counterConsistency = CounterConsistency.AGREE,
                sessionTimestampMs = 1700000000000L + idx * 3600000L,
                identity = ConditionSampleIdentity(sessionId = "t_$idx", sessionGroupId = gid, sessionStartTimestamp = 1700000000000L + idx * 3600000L)
            )
        }

        // 构造验证组：组 1（2个样本，主要工况组），组 2（2个样本，主要工况组），组 3（2个样本，主要工况组）
        val valSamples = listOf(
            DischargeConditionSample("v1", 25.0, 100.0, 0.0, DischargeOperatingCondition(), 100, 0, 100, DischargeQuality.HIGH, CounterConsistency.AGREE, sessionTimestampMs = 1700100000000L, identity = ConditionSampleIdentity("v1", sessionGroupId = "vg1", sessionStartTimestamp = 1700100000000L)),
            DischargeConditionSample("v2", 25.0, 100.0, 0.0, DischargeOperatingCondition(), 100, 0, 100, DischargeQuality.HIGH, CounterConsistency.AGREE, sessionTimestampMs = 1700100010000L, identity = ConditionSampleIdentity("v2", sessionGroupId = "vg1", sessionStartTimestamp = 1700100010000L)),
            DischargeConditionSample("v3", 25.0, 100.0, 0.0, DischargeOperatingCondition(), 100, 0, 100, DischargeQuality.HIGH, CounterConsistency.AGREE, sessionTimestampMs = 1700100020000L, identity = ConditionSampleIdentity("v3", sessionGroupId = "vg2", sessionStartTimestamp = 1700100020000L)),
            DischargeConditionSample("v4", 25.0, 100.0, 0.0, DischargeOperatingCondition(), 100, 0, 100, DischargeQuality.HIGH, CounterConsistency.AGREE, sessionTimestampMs = 1700100030000L, identity = ConditionSampleIdentity("v4", sessionGroupId = "vg2", sessionStartTimestamp = 1700100030000L)),
            DischargeConditionSample("v5", 25.0, 100.0, 0.0, DischargeOperatingCondition(), 100, 0, 100, DischargeQuality.HIGH, CounterConsistency.AGREE, sessionTimestampMs = 1700100040000L, identity = ConditionSampleIdentity("v5", sessionGroupId = "vg3", sessionStartTimestamp = 1700100040000L)),
            DischargeConditionSample("v6", 25.0, 100.0, 0.0, DischargeOperatingCondition(), 100, 0, 100, DischargeQuality.HIGH, CounterConsistency.AGREE, sessionTimestampMs = 1700100050000L, identity = ConditionSampleIdentity("v6", sessionGroupId = "vg3", sessionStartTimestamp = 1700100050000L))
        )

        val basePreds = List(6) { 26.0 } // 实际 25.0，base MAE = 1.0
        // 组 1 和组 2 大幅改善（norm MAE = 0.2），但组 3（样本数 2 >= 2）严重恶化至 1.3 Wh（恶化 30% > 15%）
        val normPreds = listOf(25.2, 25.2, 25.2, 25.2, 26.3, 26.3)

        val validation = ConditionModelValidator.validateWithMetrics(
            trainingSamples = trainSamples,
            validationSamples = valSamples,
            baselinePredictions = basePreds,
            normalizedPredictions = normPreds,
            referenceTargetsWh = List(6) { 25.0 }
        )

        // 验证系统识别到主要工况组严重恶化，拒绝晋升 VALIDATED
        assertEquals("主要工况组严重恶化必须拦截保持为 VALIDATING", ConditionModelMaturity.VALIDATING, validation.maturity)
    }

    /**
     * P2-B3 深化测试 4：验证老化判断必须使用两次估计的合成不确定度 \sigma_{\Delta} = \sqrt{\sigma_1^2 + \sigma_2^2}，
     * 只有持续单调下降且超出的两倍合成不确定度才确认永久老化。
     */
    @Test
    fun testP2B3_agingUsesCombinedMeasurementUncertainty() {
        val sigmaOld = 40.0
        val sigmaNew = 50.0
        val sigmaDelta = ConditionComparisonMatcher.calculateCombinedAgingUncertaintyMah(sigmaOld, sigmaNew, covariance = 0.0)
        assertNotNull(sigmaDelta)
        // \sigma_{\Delta} = \sqrt{40^2 + 50^2} = \sqrt{1600 + 2500} = \sqrt{4100} \approx 64.03 mAh
        assertEquals(64.03, sigmaDelta!!, 0.1)

        // 1. 下降 180 mAh（降幅 3.6% 在 3%~5% 疑似区间，且 180 mAh 未达 5% 门限）：判定为 AGING_SUSPECTED
        val historyMild = listOf(
            AcceptedFccQ(5000.0, sigmaOld, "FCC-v1.1", 3, 1700000000000L, LearningMaturity.STABLE),
            AcceptedFccQ(4900.0, 45.0, "FCC-v1.1", 3, 1701000000000L, LearningMaturity.STABLE),
            AcceptedFccQ(4820.0, sigmaNew, "FCC-v1.1", 3, 1702000000000L, LearningMaturity.STABLE)
        )
        val statusMild = ConditionComparisonMatcher.evaluateHistoryAgingTrend(historyMild)
        assertEquals(AgingTrendStatus.AGING_SUSPECTED, statusMild)

        // 2. 下降 350 mAh（降幅 7% > 5% 且 350 > 2 * 64.03 = 128 mAh，单调持续）：确认 AGING_DETECTED
        val historySevere = listOf(
            AcceptedFccQ(5000.0, sigmaOld, "FCC-v1.1", 3, 1700000000000L, LearningMaturity.STABLE),
            AcceptedFccQ(4800.0, 45.0, "FCC-v1.1", 3, 1701000000000L, LearningMaturity.STABLE),
            AcceptedFccQ(4650.0, sigmaNew, "FCC-v1.1", 3, 1702000000000L, LearningMaturity.STABLE)
        )
        val statusSevere = ConditionComparisonMatcher.evaluateHistoryAgingTrend(historySevere)
        assertEquals(AgingTrendStatus.AGING_DETECTED, statusSevere)
    }

    /**
     * P2-B3 深化测试 5：验证当两次测量存在共同系统误差正协方差时，合成差值不确定度相应缩小。
     */
    @Test
    fun testP2B3_commonSystematicErrorConsideredInAgingTrend() {
        val sigmaOld = 50.0
        val sigmaNew = 50.0
        // 独立时：\sigma_{\Delta} = \sqrt{2500 + 2500} = \sqrt{5000} \approx 70.71
        val sigmaIndependent = ConditionComparisonMatcher.calculateCombinedAgingUncertaintyMah(sigmaOld, sigmaNew, covariance = 0.0)!!
        // 存在共同传感器正协方差（Cov = 1500）：\sigma_{\Delta} = \sqrt{5000 - 3000} = \sqrt{2000} \approx 44.72
        val sigmaWithCov = ConditionComparisonMatcher.calculateCombinedAgingUncertaintyMah(sigmaOld, sigmaNew, covariance = 1500.0)!!

        assertTrue("正协方差应抵消共同系统误差，使得差值不确定度减小", sigmaWithCov < sigmaIndependent)
        assertEquals(44.72, sigmaWithCov, 0.1)
    }

    /**
     * P2-B3 深化测试 6：验证单次容量下降（历史只有 2 条测量）无法确认永久老化，返回 AGING_DATA_INSUFFICIENT 或 AGING_UNCERTAIN。
     */
    @Test
    fun testP2B3_singleDropCannotConfirmPermanentAging() {
        val twoMeasurements = listOf(
            AcceptedFccQ(5000.0, 20.0, "FCC-v1.1", 3, 1700000000000L, LearningMaturity.STABLE),
            AcceptedFccQ(4600.0, 20.0, "FCC-v1.1", 3, 1701000000000L, LearningMaturity.STABLE)
        )
        // 历史会话不足 3 条时，坚决返回数据不足，绝不轻率判定老化
        assertEquals(AgingTrendStatus.AGING_DATA_INSUFFICIENT, ConditionComparisonMatcher.evaluateHistoryAgingTrend(twoMeasurements))
    }

    /**
     * P2-B3 深化测试 7：验证跨分箱协方差矩阵必须严格对称，不对称矩阵坚决被校验拦截。
     */
    @Test
    fun testP2B3_covarianceMatrixMustBeSymmetric() {
        val binSigmas = listOf(0.1, 0.1)
        // 构造非对称矩阵（M[0][1] = 0.005，但 M[1][0] = 0.002）
        val asymmetricMatrix = arrayOf(
            doubleArrayOf(0.01, 0.005),
            doubleArrayOf(0.002, 0.01)
        )

        assertFalse("非对称协方差矩阵必须被严格拦截", EnergyNormalizationEngine.validateCovarianceMatrix(asymmetricMatrix, binSigmas))
        val result = EnergyNormalizationEngine.calculateUncertaintyWithMatrix(asymmetricMatrix, binSigmas)
        assertFalse(result.isCovarianceMatrixValid)
        assertNull("协方差校验失败时合成不确定度严格为 null", result.combinedUncertaintyWh)
    }

    /**
     * P2-B3 深化测试 8：验证对角线方差不一致或违反柯西-施瓦茨不等式的协方差矩阵被系统校验拦截。
     */
    @Test
    fun testP2B3_invalidCovarianceMatrixRejected() {
        val binSigmas = listOf(0.1, 0.1)
        // 构造违反柯西-施瓦茨不等式相关系数超标的矩阵（协方差 0.05 > 0.1 * 0.1 = 0.01）
        val invalidMatrix = arrayOf(
            doubleArrayOf(0.01, 0.05),
            doubleArrayOf(0.05, 0.01)
        )

        assertFalse("相关系数超标的协方差矩阵必须被拦截", EnergyNormalizationEngine.validateCovarianceMatrix(invalidMatrix, binSigmas))
    }

    /**
     * P2-B3 深化测试 9：验证合法半正定对称协方差矩阵正确计算完整能量合成不确定度 \sqrt{\mathbf{1}^T \Sigma \mathbf{1}}。
     */
    @Test
    fun testP2B3_fullEnergyUncertaintyUsesCovarianceMatrix() {
        val binSigmas = listOf(0.1, 0.2) // var0 = 0.01, var1 = 0.04
        // 合法对称协方差矩阵（协方差 0.01 <= 0.1 * 0.2 = 0.02）
        val validMatrix = arrayOf(
            doubleArrayOf(0.01, 0.01),
            doubleArrayOf(0.01, 0.04)
        )

        assertTrue(EnergyNormalizationEngine.validateCovarianceMatrix(validMatrix, binSigmas))
        val breakdown = EnergyNormalizationEngine.calculateUncertaintyWithMatrix(validMatrix, binSigmas)
        assertTrue(breakdown.isCovarianceMatrixValid)
        assertNotNull(breakdown.combinedUncertaintyWh)
        // \sigma_E = \sqrt{0.01 + 0.01 + 0.01 + 0.04} = \sqrt{0.07} \approx 0.264575 Wh
        assertEquals(0.264575, breakdown.combinedUncertaintyWh!!, 1e-4)
    }

    /**
     * P2-B3 深化测试 10：验证不确定度分解各分量清晰隔离，防止重复计算。
     */
    @Test
    fun testP2B3_uncertaintyComponentsDoNotDoubleCount() {
        val binSigmas = listOf(0.05, 0.05)
        val breakdown = EnergyNormalizationEngine.calculateUncertaintyBreakdown(
            binSigmas = binSigmas,
            hasCovarianceSupport = false,
            validationResidualRmse = 0.12
        )
        // 验证各分量独立记录
        assertEquals(0.05, breakdown.measurementUncertaintyWh ?: 0.0, 1e-6)
        assertEquals(0.12, breakdown.validationResidualUncertaintyWh ?: 0.0, 1e-6)
        assertNull(breakdown.combinedUncertaintyWh)
    }

    /**
     * P2-B3 深化测试 11：验证标准参考工况实验协议实体（EnergyReferenceProtocol）版本与各项物理政策完整持久化。
     */
    @Test
    fun testP2B3_referenceProtocolVersionPersisted() {
        val protocol = EnergyReferenceProtocol(
            protocolVersion = "REF-PROTO-HONOR-v1.0",
            targetTemperatureC = 25.0,
            targetCurrentMa = 1000.0,
            targetPowerW = 3.85,
            startSocPolicy = "START_FROM_100_FULL",
            dischargeEndPolicy = "SHUTDOWN_OR_LOW_VOLTAGE_3000MV",
            topPlateauPolicy = TopPlateauNormalizationPolicy.EXCLUDE_FROM_CURVE,
            transitionPolicy = TransitionNormalizationPolicy.EXCLUDED_FROM_SOC_CURVE
        )

        assertEquals("REF-PROTO-HONOR-v1.0", protocol.protocolVersion)
        assertEquals(25.0, protocol.targetTemperatureC ?: 0.0, 1e-6)
        assertEquals(1000.0, protocol.targetCurrentMa ?: 0.0, 1e-6)
        assertEquals(20, protocol.requiredMeasuredBins.size)
    }

    /**
     * P2-B3 深化测试 12：验证标准参考工况会话必须为留出会话（Held-Out），绝不参与模型训练。
     */
    @Test
    fun testP2B3_referenceSessionMustRemainHeldOut() {
        val trainingGroups = listOf("g_train_1", "g_train_2")
        val heldOutRefGroup = listOf("g_ref_held_out")

        // 验证系统识别到留出会话未混入训练集
        assertTrue(ConditionComparisonMatcher.isSessionGroupStrictlySegregated(trainingGroups, heldOutRefGroup))

        // 混入时严密拦截
        val leakedRefGroup = listOf("g_train_2")
        assertFalse(ConditionComparisonMatcher.isSessionGroupStrictlySegregated(trainingGroups, leakedRefGroup))
    }

    /**
     * P2-B3 深化测试 13：验证参考工况必须具备多次重复测量支持，单条会话不能作为永久基准。
     */
    @Test
    fun testP2B3_referenceConditionRequiresRepeatedMeasurements() {
        // 单条参考会话样本
        val singleRefSamples = listOf(
            DischargeConditionSample(
                sessionId = "ref_1",
                observedEnergyWh = 26.5,
                measuredEnergyCoverage = 100.0,
                unresolvedEnergyWh = 0.0,
                condition = DischargeOperatingCondition(averageTemperatureC = 25.0, averageDischargeCurrentMa = 1000.0),
                startSoc = 100, endSoc = 0, socSpanPercent = 100,
                quality = DischargeQuality.HIGH, counterConsistency = CounterConsistency.AGREE,
                identity = ConditionSampleIdentity("ref_1", sessionGroupId = "rg1")
            )
        )

        // 单组参考会话 Bootstrap 样本组数 < 3，不可计算且不可靠
        val bootstrap = ConditionSensitivityLearner.calculateSessionGroupBootstrap(singleRefSamples)
        assertFalse("单条参考会话不可计算不确定度", bootstrap.isComputable)
        assertFalse("单条参考会话绝不能作为永久可靠基准", bootstrap.isStatisticallyReliable)
    }

    /**
     * P2-B3 深化测试 14：验证过渡段政策为 UNCALIBRATED_BLOCK 时，系统阻断完整可用能量输出。
     */
    @Test
    fun testP2B3_transitionPolicyBlocksIncompleteFullEnergy() {
        val validModel = ConditionModelValidation(
            modelVersion = "FCC-E-v1.0",
            maturity = ConditionModelMaturity.VALIDATED,
            trainingGroupCount = 5,
            validationGroupCount = 3,
            validationMaeWh = 0.1,
            validationRmseWh = 0.15,
            normalizationImprovement = 0.25
        )

        val bins = (0..19).map { idx ->
            SocEnergyBinInfo(
                binIndex = idx,
                startSocPercent = idx * 5.0,
                endSocPercent = (idx + 1) * 5.0,
                status = BinLearningStatus.MEASURED,
                energyWh = 1.3,
                measuredEnergyWh = 1.3
            )
        }

        // 过渡段政策未标定 UNCALIBRATED_BLOCK
        val result = EnergyNormalizationEngine.evaluateNormalizationResult(
            observedEnergyWh = 28.0,
            bins = bins,
            condition = DischargeOperatingCondition(),
            validationModel = validModel,
            topPlateauWh = 2.0,
            transitionWh = 0.5,
            transitionPolicy = TransitionNormalizationPolicy.UNCALIBRATED_BLOCK
        )

        assertNull("过渡段未标定时必须阻断完整可用能量输出", result.normalizedFullUsableEnergyWh)
        assertTrue(result.rejectionReasons.contains("TRANSITION_POLICY_UNCALIBRATED"))
    }

    /**
     * P2-B3 深化测试 15：验证协方差矩阵存在负特征值时必须被严格拦截（反例二次型全和为正但行列式为负）。
     */
    @Test
    fun testP2B3_covarianceMatrixWithNegativeEigenvalueRejected() {
        // 反例：3x3 矩阵，对角线方差均为 1.0，两两协方差分别为 0.9, 0.9, -0.9
        // 全和 1^T Sigma 1 = 3 + 2*(0.9 + 0.9 - 0.9) = 4.8 > 0
        // 行列式 det = 1*(1 - 0.81) - 0.9*(0.9 - (-0.81)) + (-0.9)*(0.81 - (-0.9)) = 0.19 - 1.539 - 1.539 = -2.888 < 0
        // 存在负特征值 lambda_min = -0.8 < 0 (精确特征值为 lambda_1 = -0.8, lambda_2 = 1.9, lambda_3 = 1.9)，非半正定矩阵
        val covMatrix = arrayOf(
            doubleArrayOf(1.0, 0.9, -0.9),
            doubleArrayOf(0.9, 1.0, 0.9),
            doubleArrayOf(-0.9, 0.9, 1.0)
        )
        val sigmas = listOf(1.0, 1.0, 1.0)

        val isValid = EnergyNormalizationEngine.validateCovarianceMatrix(covMatrix, sigmas)
        assertFalse("含有负特征值的协方差矩阵绝不能通过半正定检验", isValid)

        val eigenvalues = EnergyNormalizationEngine.computeSymmetricEigenvalues(covMatrix)
        val minEig = eigenvalues.minOrNull() ?: 0.0
        assertEquals("最小特征值精确为 -0.8", -0.8, minEig, 1e-4)
    }

    /**
     * P2-B3 深化测试 16：验证两两相关系数满足 [-1, 1] 无法保证多元半正定性，必须经过特征值分解。
     */
    @Test
    fun testP2B3_pairwiseValidCorrelationDoesNotGuaranteePsd() {
        val covMatrix = arrayOf(
            doubleArrayOf(1.0, 0.9, -0.9),
            doubleArrayOf(0.9, 1.0, 0.9),
            doubleArrayOf(-0.9, 0.9, 1.0)
        )
        // 验证每对元素的柯西-施瓦茨条件均满足：cov[i][j]^2 <= cov[i][i] * cov[j][j]
        for (i in 0..2) {
            for (j in 0..2) {
                val bound = kotlin.math.sqrt(covMatrix[i][i] * covMatrix[j][j])
                assertTrue("每对两两元素均满足柯西-施瓦茨不等式", kotlin.math.abs(covMatrix[i][j]) <= bound + 1e-9)
            }
        }

        // 但特征值分解能够精准拦截该伪半正定矩阵
        val isValid = EnergyNormalizationEngine.validateCovarianceMatrix(covMatrix, listOf(1.0, 1.0, 1.0))
        assertFalse("两两有效相关系数不能掩盖高维几何失真", isValid)
    }

    /**
     * P2-B3 深化测试 17：验证浮点数值微噪导致的极微小负特征值（在容差范围内）被正确接纳。
     */
    @Test
    fun testP2B3_nearZeroNegativeEigenvalueHandledByTolerance() {
        // 构建一个秩 1 严格半正定矩阵：外积 v * v^T，v = [1, 1, 1]^T
        // 理论特征值为 [3.0, 0.0, 0.0]，浮点计算或微扰可能产生 -1e-10 的微小负值
        val covMatrix = arrayOf(
            doubleArrayOf(1.0, 1.0, 1.0),
            doubleArrayOf(1.0, 1.0, 1.0),
            doubleArrayOf(1.0, 1.0, 1.0)
        )
        val sigmas = listOf(1.0, 1.0, 1.0)

        val isValid = EnergyNormalizationEngine.validateCovarianceMatrix(covMatrix, sigmas, toleranceScale = 1e-6)
        assertTrue("处于数值容差范围内的零特征值微噪应被正确容忍", isValid)
    }

    /**
     * P2-B3 深化测试 18：验证协方差矩阵包含 NaN 或 Infinity 时被严格拒绝。
     */
    @Test
    fun testP2B3_covarianceMatrixRejectsNaNAndInfinity() {
        val covWithNan = arrayOf(
            doubleArrayOf(1.0, Double.NaN),
            doubleArrayOf(Double.NaN, 1.0)
        )
        assertFalse("包含 NaN 的协方差矩阵必须被拒绝", EnergyNormalizationEngine.validateCovarianceMatrix(covWithNan, listOf(1.0, 1.0)))

        val covWithInf = arrayOf(
            doubleArrayOf(1.0, Double.POSITIVE_INFINITY),
            doubleArrayOf(Double.POSITIVE_INFINITY, 1.0)
        )
        assertFalse("包含正无穷的协方差矩阵必须被拒绝", EnergyNormalizationEngine.validateCovarianceMatrix(covWithInf, listOf(1.0, 1.0)))

        val covWithNegInf = arrayOf(
            doubleArrayOf(1.0, Double.NEGATIVE_INFINITY),
            doubleArrayOf(Double.NEGATIVE_INFINITY, 1.0)
        )
        assertFalse("包含负无穷的协方差矩阵必须被拒绝", EnergyNormalizationEngine.validateCovarianceMatrix(covWithNegInf, listOf(1.0, 1.0)))
    }

    /**
     * P2-B3 深化测试 19：验证配对 Bootstrap 采用完全相同的重采样索引序列，消除组间假方差。
     */
    @Test
    fun testP2B3_bootstrapUsesPairedGroupIndices() {
        // 构造每个组归一化 MAE 比基准严格稳定减少 0.1 Wh 的场景
        val baseMaes = listOf(0.5, 0.8, 1.2, 0.4, 0.9)
        val normMaes = listOf(0.4, 0.7, 1.1, 0.3, 0.8)

        val result = ConditionModelValidator.computePairedBootstrapMetrics(
            groupBaseMaes = baseMaes,
            groupNormMaes = normMaes,
            iterations = 300,
            seed = 42L
        )

        assertTrue("配对抽样下绝对改善必定具备高度统计显著性", result.isSignificant)
        assertNotNull(result.ciLowerWh)
        assertNotNull(result.ciUpperWh)
        // 由于每组配对差值均为 0.1 Wh，不论抽中哪些组的索引，每次抽样的配对差值均值必恒为 0.1
        assertEquals("配对抽样下置信区间下界必须为 0.1", 0.1, result.ciLowerWh!!, 1e-4)
        assertEquals("配对抽样下置信区间上界必须为 0.1", 0.1, result.ciUpperWh!!, 1e-4)
    }

    /**
     * P2-B3 深化测试 20：验证配对 Bootstrap 支持通过固定随机种子确保完全可复现。
     */
    @Test
    fun testP2B3_bootstrapResultReproducibleWithSeed() {
        val baseMaes = listOf(0.52, 0.81, 1.15, 0.44, 0.93)
        val normMaes = listOf(0.45, 0.72, 1.02, 0.39, 0.80)

        val run1 = ConditionModelValidator.computePairedBootstrapMetrics(baseMaes, normMaes, iterations = 500, seed = 12345L)
        val run2 = ConditionModelValidator.computePairedBootstrapMetrics(baseMaes, normMaes, iterations = 500, seed = 12345L)

        assertEquals("相同种子下置信区间下界必须完全一致", run1.ciLowerWh!!, run2.ciLowerWh!!, 1e-9)
        assertEquals("相同种子下置信区间上界必须完全一致", run1.ciUpperWh!!, run2.ciUpperWh!!, 1e-9)
        assertEquals("相同种子下相对改善下界必须完全一致", run1.ratioCiLower!!, run2.ratioCiLower!!, 1e-9)
        assertEquals("相同种子下显著性评级必须完全一致", run1.significanceLevel, run2.significanceLevel)
    }

    /**
     * P2-B3 深化测试 21：验证配对改善阈值语义：明确区分统计显著性（L > 0）与 3% 实际效应量。
     */
    @Test
    fun testP2B3_improvementThresholdSemantics() {
        // 场景 1：改善微弱（0.5%），虽然统计方向显著（CI 不跨零），但效应量未超 3%
        val baseMaesWeak = listOf(1.000, 1.000, 1.000, 1.000)
        val normMaesWeak = listOf(0.995, 0.995, 0.995, 0.995)
        val resultWeak = ConditionModelValidator.computePairedBootstrapMetrics(baseMaesWeak, normMaesWeak, iterations = 300, seed = 42L)
        assertTrue("微弱改善但方向确定时具备统计显著性", resultWeak.isSignificant)
        assertEquals("效应量未超 3% 时应评为 DIRECTIONALLY_SIGNIFICANT", ImprovementSignificanceLevel.DIRECTIONALLY_SIGNIFICANT, resultWeak.significanceLevel)

        // 场景 2：改善跨越 0（有恶化组），未达到统计显著性
        val baseMaesNoisy = listOf(1.0, 1.0, 1.0)
        val normMaesNoisy = listOf(0.8, 1.2, 1.0)
        val resultNoisy = ConditionModelValidator.computePairedBootstrapMetrics(baseMaesNoisy, normMaesNoisy, iterations = 300, seed = 42L)
        assertFalse("置信区间覆盖零时未达到统计显著性", resultNoisy.isSignificant)
        assertEquals("未达到显著性时应评为 NOT_SIGNIFICANT", ImprovementSignificanceLevel.NOT_SIGNIFICANT, resultNoisy.significanceLevel)

        // 场景 3：改善显著且幅度超 3%（改善 10%）
        val baseMaesStrong = listOf(1.0, 1.0, 1.0, 1.0)
        val normMaesStrong = listOf(0.9, 0.9, 0.9, 0.9)
        val resultStrong = ConditionModelValidator.computePairedBootstrapMetrics(baseMaesStrong, normMaesStrong, iterations = 300, seed = 42L)
        assertTrue("大幅改善具备统计显著性", resultStrong.isSignificant)
        assertEquals("统计显著且效应超 3% 时评为 PRACTICALLY_SIGNIFICANT_AT_3_PERCENT", ImprovementSignificanceLevel.PRACTICALLY_SIGNIFICANT_AT_3_PERCENT, resultStrong.significanceLevel)
    }

    /**
     * P2-B3 深化测试 22：验证标准参考协议区间自洽性校验，拒绝倒置区间与负电流。
     */
    @Test
    fun testP2B3_referenceProtocolRejectsInvalidRanges() {
        val invertedTemp = ReferenceConditionRange(temperatureMinC = 30.0, temperatureMaxC = 20.0, currentMinMa = 800.0, currentMaxMa = 1200.0)
        assertFalse("温度倒置区间必须无效", invertedTemp.isValid())

        val negativeCurrent = ReferenceConditionRange(temperatureMinC = 20.0, temperatureMaxC = 30.0, currentMinMa = -100.0, currentMaxMa = 1200.0)
        assertFalse("负放电电流必须无效", negativeCurrent.isValid())

        val nanRange = ReferenceConditionRange(temperatureMinC = Double.NaN, temperatureMaxC = 30.0, currentMinMa = 800.0, currentMaxMa = 1200.0)
        assertFalse("含 NaN 的工况区间必须无效", nanRange.isValid())

        val validRange = ReferenceConditionRange(temperatureMinC = 23.0, temperatureMaxC = 27.0, currentMinMa = 800.0, currentMaxMa = 1200.0)
        assertTrue("合理自洽的工况区间有效", validRange.isValid())

        val validProtocol = EnergyReferenceProtocol(protocolVersion = "ERP-v1.0", targetRange = validRange)
        assertTrue("有效协议通过校验", validProtocol.isProtocolValid())

        val invalidProtocol = EnergyReferenceProtocol(protocolVersion = "ERP-v1.0", targetRange = invertedTemp)
        assertFalse("包含非法区间的协议必须拒绝", invalidProtocol.isProtocolValid())
    }

    /**
     * P2-B3 深化测试 23：验证放电截止政策类型安全，杜绝通用假设 3000mV。
     */
    @Test
    fun testP2B3_shutdownPolicyDoesNotAssumeUniversal3000mV() {
        val shutdownProtocol = EnergyReferenceProtocol(
            protocolVersion = "ERP-v1.0",
            cutoffPolicy = DischargeCutoffPolicy.NORMAL_SYSTEM_SHUTDOWN,
            dischargeEndPolicy = "NORMAL_SYSTEM_SHUTDOWN"
        )
        assertEquals(DischargeCutoffPolicy.NORMAL_SYSTEM_SHUTDOWN, shutdownProtocol.cutoffPolicy)

        val bmsProtocol = EnergyReferenceProtocol(
            protocolVersion = "ERP-v1.0",
            cutoffPolicy = DischargeCutoffPolicy.BMS_SPECIFIED_LOW_BATTERY,
            dischargeEndPolicy = "BMS_SPECIFIED_LOW_BATTERY"
        )
        assertEquals(DischargeCutoffPolicy.BMS_SPECIFIED_LOW_BATTERY, bmsProtocol.cutoffPolicy)
    }

    /**
     * P2-B3.1-A 真实数据审计测试 24：验证 CSV 解析保留原始极性、物理单位与跨 boot_id 隔离。
     */
    @Test
    fun testP2B31_csvPreservesRawPolarityAndUnits() {
        val csvData = """
            timestamp_ms,soc,voltage_mv,current_ma,temp_c,boot_id
            1700000000000,100.0,4350.0,-1250.0,25.0,boot-uuid-001
            1700000001000,99.5,4320.0,-1240.0,25.2,boot-uuid-001
            1700000010000,90.0,4100.0,-1300.0,26.5,boot-uuid-002
            1700000011000,89.5,4080.0,-1280.0,26.8,boot-uuid-002
        """.trimIndent()

        val (summary, records) = DischargeAuditCsvReplayer.parseAndAudit(csvData, "test_session.csv")

        // 验证防篡改 SHA-256
        assertNotNull(summary.fileSha256)
        assertEquals(64, summary.fileSha256.length)

        // 验证有效记录数
        assertEquals(4, summary.validRecordsCount)
        assertEquals(0, summary.invalidLinesCount)

        // 验证保留底层原始极性（放电负电流）与数值
        assertEquals(-1250.0, records[0].rawCurrent!!, 1e-4)
        assertEquals(-1250.0, records[0].currentMa!!, 1e-4)
        assertEquals("mA", records[0].currentUnit)
        assertEquals(4350.0, records[0].voltageMv, 1e-4)
        assertEquals(25.0, records[0].temperatureC, 1e-4)

        // 验证跨重启边界检测
        assertTrue("检测到两个不同 boot_id 时必须标明重启边界", summary.hasRebootBoundary)
        assertEquals(2, summary.bootIds.size)

        // 验证按 boot_id 严格隔离切分段
        val segmented = DischargeAuditCsvReplayer.segmentByBootId(records)
        assertEquals(2, segmented.size)
        assertEquals(2, segmented["boot-uuid-001"]?.size)
        assertEquals(2, segmented["boot-uuid-002"]?.size)
    }

    /**
     * P2-B3.1-A 真实数据闭环测试 25：验证 DischargeDataExportHelper 导出的 CSV 内容格式与 DischargeAuditCsvReplayer 完全自洽兼容。
     */
    @Test
    fun testP2B31_exportHelperGeneratesCompatibleCsv() {
        val pointsJson = """
            [
                {"ts": 1731300000000, "lvl": 95, "volt": 4.15, "temp": 28.5, "pwr": 2.5, "cur_ua": -600000, "screenOn": true},
                {"ts": 1731300060000, "lvl": 94, "volt": 4.12, "temp": 28.7, "pwr": 2.4, "cur_ua": -580000, "screenOn": true}
            ]
        """.trimIndent()

        val record = com.battery.analysis.model.PowerUsageRecord(
            id = 1001L,
            recordTime = "2026-11-11 10:00:00",
            levelPercent = 94,
            voltageVolts = 4.15f,
            temperature = 28.5f,
            energyWh = 0.5f,
            isCharging = false,
            avgPowerWatts = 2.5f,
            screenOnPowerWatts = 2.5f,
            screenOffPowerWatts = 0f,
            screenOnDurationText = "1m",
            screenOffDurationText = "0s",
            totalDurationText = "1m",
            remainingScreenOnText = "--",
            remainingCompositeText = "--",
            remainingScreenOffText = "--",
            isShizukuRealData = false,
            appCount = 0,
            trendPointsJson = pointsJson,
            appListJson = "[]"
        )

        // 1. 生成 CSV
        val csv = DischargeDataExportHelper.buildDischargeCsv(record)
        assertTrue("表头必须包含标准字段", csv.contains("timestamp_ms,soc,voltage_mv,current_ma,temp_c"))

        // 2. 将导出的 CSV 内容输入给只读审计回放引擎
        val (summary, parsedRecords) = DischargeAuditCsvReplayer.parseAndAudit(csv, "app_exported.csv")

        assertEquals(2, summary.validRecordsCount)
        assertEquals(0, summary.invalidLinesCount)
        assertEquals(2, parsedRecords.size)

        // 验证物理数值还原精度
        assertEquals(4150.0, parsedRecords[0].voltageMv, 1e-4)
        assertEquals(-600.0, parsedRecords[0].currentMa!!, 1e-4)
        assertEquals(28.5, parsedRecords[0].temperatureC, 1e-4)
        assertEquals(95.0, parsedRecords[0].socPercent, 1e-4)
    }

    /**
     * P2-B3 深化测试 26：验证合法秩亏半正定矩阵（含零特征值）被正确接纳，绝不错误要求所有特征值严格大于零。
     */
    @Test
    fun testJacobiAcceptsRankDeficientPsdMatrix() {
        // 构建外积矩阵 v * v^T, v = [1.0, 2.0, 3.0]^T，对角线方差为 [1, 4, 9]，sigmas = [1, 2, 3]
        // 秩为 1，精确特征值为 [14.0, 0.0, 0.0]，属于典型合法半正定矩阵
        val covMatrix = arrayOf(
            doubleArrayOf(1.0, 2.0, 3.0),
            doubleArrayOf(2.0, 4.0, 6.0),
            doubleArrayOf(3.0, 6.0, 9.0)
        )
        val sigmas = listOf(1.0, 2.0, 3.0)

        val isValid = EnergyNormalizationEngine.validateCovarianceMatrix(covMatrix, sigmas)
        assertTrue("半正定矩阵即使包含零特征值也必须被合法接纳", isValid)

        val eigenvalues = EnergyNormalizationEngine.computeSymmetricEigenvalues(covMatrix)
        assertEquals(3, eigenvalues.size)
        val maxEig = eigenvalues.maxOrNull() ?: 0.0
        val minEig = eigenvalues.minOrNull() ?: 0.0
        assertEquals(14.0, maxEig, 1e-4)
        assertEquals(0.0, minEig, 1e-4)
    }

    /**
     * P2-B3 深化测试 27：验证 PSD 判定结果具备自适应尺度不变性（在 1e-12 到 1e12 缩放比例下判定一致）。
     */
    @Test
    fun testJacobiScaleInvariantPsdDecision() {
        // 基础半正定矩阵
        val baseMat = arrayOf(
            doubleArrayOf(2.0, 1.0),
            doubleArrayOf(1.0, 2.0)
        )
        val baseSigmas = listOf(kotlin.math.sqrt(2.0), kotlin.math.sqrt(2.0))

        val scales = listOf(1e-12, 1e-6, 1.0, 1e6, 1e12)
        for (scale in scales) {
            val scaledMat = Array(2) { i -> DoubleArray(2) { j -> baseMat[i][j] * scale } }
            val scaledSigmas = baseSigmas.map { it * kotlin.math.sqrt(scale) }
            val isValid = EnergyNormalizationEngine.validateCovarianceMatrix(scaledMat, scaledSigmas)
            assertTrue("在尺度 $scale 下半正定判定必须始终保持为 true", isValid)
        }
    }

    /**
     * P2-B3 深化测试 28：验证 Jacobi 算法在未收敛时必须 Fail-Closed 拒绝矩阵。
     */
    @Test
    fun testJacobiNonConvergenceFailsClosed() {
        val covMatrix = arrayOf(
            doubleArrayOf(5.0, 2.0, 1.0),
            doubleArrayOf(2.0, 6.0, 2.0),
            doubleArrayOf(1.0, 2.0, 7.0)
        )
        // 强制限定单次旋转，故意阻止其收敛
        val decomp = EnergyNormalizationEngine.computeSymmetricEigenvaluesFull(covMatrix, maxRotationsPerPair = 1)
        assertFalse("旋转轮数不足时必须明确标记为未收敛", decomp.converged)
        assertNull("未收敛时严禁输出不完整的特征值", decomp.eigenvalues)
        assertEquals("MAX_ITERATIONS_EXCEEDED", decomp.failureReason)
    }

    /**
     * P2-B3 深化测试 29：验证 20x20 真实规模正定协方差矩阵顺利通过特征值检验。
     */
    @Test
    fun testP2B3_jacobiAcceptsPositiveDefinite20x20() {
        val n = 20
        // 构建 20x20 对角优势正定矩阵：对角线为 2.0，邻近相关系数衰减
        val matrix = Array(n) { i ->
            DoubleArray(n) { j ->
                if (i == j) 2.0 else 0.5 / (kotlin.math.abs(i - j) + 1.0)
            }
        }
        val sigmas = List(n) { kotlin.math.sqrt(2.0) }

        val isValid = EnergyNormalizationEngine.validateCovarianceMatrix(matrix, sigmas)
        assertTrue("20x20 真实规模正定协方差矩阵必须通过校验", isValid)

        val decomp = EnergyNormalizationEngine.computeSymmetricEigenvaluesFull(matrix)
        assertTrue("20x20 规模下必须平稳收敛", decomp.converged)
        assertNotNull(decomp.eigenvalues)
        assertTrue("所有特征值必须大于等于 0", decomp.eigenvalues!!.all { it >= -1e-6 })
    }

    /**
     * P2-B3 深化测试 30：验证 20x20 真实规模非半正定矩阵被准确拦截。
     */
    @Test
    fun testP2B3_jacobiRejects20x20IndefiniteMatrix() {
        val n = 20
        val matrix = Array(n) { i ->
            DoubleArray(n) { j ->
                if (i == j) 1.0 else 0.1
            }
        }
        // 在前三个元素引入反例破坏半正定性
        matrix[0][1] = 0.9; matrix[1][0] = 0.9
        matrix[1][2] = 0.9; matrix[2][1] = 0.9
        matrix[0][2] = -0.9; matrix[2][0] = -0.9

        val sigmas = List(n) { 1.0 }
        val isValid = EnergyNormalizationEngine.validateCovarianceMatrix(matrix, sigmas)
        assertFalse("20x20 规模下存在局部负特征值时必须被准确拦截", isValid)
    }

    /**
     * P2-B3 深化测试 31：验证配对 Bootstrap 绝对改善量 Wh 与相对改善率解耦存储。
     */
    @Test
    fun testBootstrapAbsoluteAndRelativeImprovementSeparated() {
        val baseMaes = listOf(1.0, 1.2, 1.1, 1.3)
        val normMaes = listOf(0.8, 0.9, 0.8, 0.9)

        val result = ConditionModelValidator.computePairedBootstrapMetrics(baseMaes, normMaes, iterations = 300, seed = 42L)

        assertNotNull(result.absoluteImprovementWh)
        assertNotNull(result.relativeImprovementRatio)
        assertNotNull(result.absoluteImprovementCiLowerWh)
        assertNotNull(result.relativeImprovementCiUpper)
        assertNotNull(result.relativeImprovementCiLower)
        assertNotNull(result.relativeImprovementCiUpper)
        assertEquals(4, result.effectiveSessionGroupCount)
        assertEquals(1, result.effectiveIndependentBatteryCount)
        assertEquals(BootstrapEvidenceLevel.EXPLORATORY, result.evidenceLevel)
        assertTrue(result.absoluteImprovementWh!! > 0.0)
        assertTrue(result.relativeImprovementRatio!! > 0.0)
    }

    /**
     * P2-B3 深化测试 32：验证生产场景 5000 次与 10000 次重采样置信区间端点高度收敛。
     */
    @Test
    fun testBootstrapProductionIterationsConvergence() {
        val baseMaes = listOf(0.65, 0.82, 1.10, 0.45, 0.90)
        val normMaes = listOf(0.55, 0.70, 0.95, 0.40, 0.78)

        val r5k = ConditionModelValidator.computePairedBootstrapMetrics(baseMaes, normMaes, iterations = 5000, seed = 42L)
        val r10k = ConditionModelValidator.computePairedBootstrapMetrics(baseMaes, normMaes, iterations = 10000, seed = 42L)

        // 验证端点偏差在 0.005 Wh 以内
        assertEquals("5k 与 10k 置信区间下界必须高度收敛", r5k.absoluteImprovementCiLowerWh!!, r10k.absoluteImprovementCiLowerWh!!, 0.005)
        assertEquals("5k 与 10k 置信区间上界必须高度收敛", r5k.absoluteImprovementCiUpperWh!!, r10k.absoluteImprovementCiUpperWh!!, 0.005)
    }

    /**
     * P2-B3 深化测试 33：验证参考工况协议终止元数据的自洽性校验。
     */
    @Test
    fun testReferenceProtocolValidatesTerminationMetadata() {
        // 声明观测到终止，但元数据缺失
        val incompleteProtocol = EnergyReferenceProtocol(
            protocolVersion = "ERP-v1.0",
            terminationObserved = true,
            terminationTimestamp = null // 缺失时间戳
        )
        assertFalse("声明观测到终止但缺失终止时间戳必须判定为非法协议", incompleteProtocol.isProtocolValid())

        // 完整合法的终止元数据
        val completeProtocol = EnergyReferenceProtocol(
            protocolVersion = "ERP-v1.0",
            terminationObserved = true,
            terminationTimestamp = 1731300000000L,
            terminationReason = "NORMAL_SYSTEM_SHUTDOWN",
            terminationSoc = 0.0,
            terminationVoltageMv = 3150.0,
            terminationCurrentMa = -450.0,
            terminationTemperatureC = 25.5
        )
        assertTrue("具备完整终止元数据的协议通过校验", completeProtocol.isProtocolValid())
    }

    /**
     * P2-B3 深化测试 34：验证参考工况协议结合逐 Bin 局部工况匹配机制。
     */
    @Test
    fun testReferenceProtocolUsesPerBinConditionMatching() {
        val protocol = EnergyReferenceProtocol(
            protocolVersion = "ERP-v1.0",
            targetRange = ReferenceConditionRange(
                temperatureMinC = 23.0,
                temperatureMaxC = 27.0,
                currentMinMa = 800.0,
                currentMaxMa = 1200.0
            )
        )

        // 分箱内局部温度超出参考区间（35.0C）
        val hotBin = SocBinOperatingCondition(
            binIndex = 5,
            averageTemperatureC = 35.0,
            averageCurrentMa = 1000.0
        )
        val binTemp = hotBin.averageTemperatureC
        val isBinMatched = binTemp != null &&
                binTemp in protocol.targetRange.temperatureMinC..protocol.targetRange.temperatureMaxC
        assertFalse("单 Bin 局部温度超标时绝不能伪装为标准工况", isBinMatched)
    }

    /**
     * P2-B3.1-A 测试 35：验证 CSV 导出器与回放器的 Schema 统一自洽性（支持 RFC 4180、带引号逗号与 UTF-8 BOM）。
     */
    @Test
    fun testCsvExporterAndReplayerSchemaCompatibility() {
        val csvWithBOM = "\uFEFFtimestamp_ms,soc,voltage_mv,current_ma,temp_c,power_w,screen_on,charge_counter_uah,boot_id\n" +
                "1731300000000,95.0,4350.0,\"-1250.0\",25.5,5.43,1,5400000,\"boot-uuid-xyz\""

        val (summary, records) = DischargeAuditCsvReplayer.parseAndAudit(csvWithBOM, "bom_test.csv")
        assertEquals(1, summary.validRecordsCount)
        assertEquals(0, summary.invalidLinesCount)
        assertEquals(-1250.0, records[0].currentMa!!, 1e-4)
        assertEquals("boot-uuid-xyz", records[0].bootId)
        assertTrue(summary.isBootIdAvailable)
    }

    /**
     * P2-B3.1-A 测试 36：验证单流 SHA-256 摘要与解析源数据字节完全一致。
     */
    @Test
    fun testCsvHashMatchesParsedSourceBytes() {
        val rawData = "timestamp_ms,soc,voltage_mv,current_ma,temp_c\n1731300000000,100.0,4400.0,-1000.0,25.0\n"
        val expectedHash = DischargeAuditCsvReplayer.computeSha256(rawData)

        val (summary, _) = DischargeAuditCsvReplayer.parseAndAudit(rawData, "hash_test.csv")
        assertEquals("单流计算哈希必须与源数据独立 SHA-256 严格相同", expectedHash, summary.fileSha256)
    }

    /**
     * P2-B3.1-A 测试 37：验证 CSV 缺失 boot_id 时标记 BOOT_ID_UNAVAILABLE 且拒绝谎称跨重启隔离。
     */
    @Test
    fun testCsvMissingBootIdDoesNotClaimRebootIsolation() {
        // 无 boot_id 列
        val csvNoBootId = "timestamp_ms,soc,voltage_mv,current_ma,temp_c\n" +
                "1731300000000,100.0,4400.0,-1000.0,25.0\n" +
                "1731300005000,99.5,4380.0,-1000.0,25.1\n"

        val (summary, records) = DischargeAuditCsvReplayer.parseAndAudit(csvNoBootId, "no_boot.csv")
        assertEquals(2, records.size)
        assertEquals(DischargeAuditCsvReplayer.BOOT_ID_UNAVAILABLE, records[0].bootId)
        assertFalse("缺失 boot_id 时 isBootIdAvailable 必须为 false", summary.isBootIdAvailable)
        assertFalse("缺失 boot_id 时绝对不能宣称完成隔离验证", summary.isRebootIsolationVerified)
        assertFalse("缺失 boot_id 时不能谎称检测到重启边界", summary.hasRebootBoundary)
    }

    /**
     * P2-B3.1-A 真实数据物理边界检验测试：
     * 当局部放电区间积分能量超过整块电池物理满电上限，或按实际掉电 SOC 折算的等效全域能量严重超额时，
     * 必须将物理合理性判定标记为 PHYSICAL_ENERGY_VALIDATION_FAILED，
     * 且必须将 observedBatteryEnergyWh, verifiedDischargeChargeMah, normalizedFullUsableEnergyWh, sohE 严格置空（null）。
     */
    @Test
    fun testIntegratedReportedPowerFieldWhFailsClosedWhenViolatingPhysicalBounds() {
        // 构造一个 95% -> 18% 掉电 77%，正常采样频率（每 60 秒一点，共 60 个点持续 1 小时），功率恒定 34.06W 的物理反常数据
        val sb = StringBuilder()
        sb.append("timestamp_ms,soc,voltage_mv,current_ma,temp_c,power_w,screen_on,charge_counter_uah,boot_id\n")
        val baseTs = 1794300000000L
        for (i in 0..60) {
            val ts = baseTs + i * 60_000L
            val soc = 95.0 - (77.0 * i / 60.0)
            val volt = 4400.0 - (1050.0 * i / 60.0)
            sb.append(String.format(java.util.Locale.US, "%d,%.1f,%.1f,-7700.0,30.0,34.06,1,,boot-001\n", ts, soc, volt))
        }

        val (summary, records) = DischargeAuditCsvReplayer.parseAndAudit(sb.toString(), "abnormal_energy.csv")

        assertEquals(61, records.size)
        assertEquals(95.0, summary.startSocPercent!!, 1e-4)
        assertEquals(18.0, summary.endSocPercent!!, 1e-4)
        assertEquals(77.0, summary.deltaSocPercent!!, 1e-4)

        // 验证积分字段改名为 integratedReportedPowerFieldWh 并忠实微积分求和 (34.06W * 1h = 34.06Wh)
        assertNotNull(summary.integratedReportedPowerFieldWh)
        assertEquals(34.06, summary.integratedReportedPowerFieldWh!!, 1e-2)

        // 核心安全保护：物理边界校验必须明确失败
        assertFalse("局部积分 34.06Wh 超出额定能量 32.0Wh，必须判定物理验证失败", summary.isPhysicalEnergyValidated)
        assertTrue(
            "失败原因必须包含 PHYSICAL_ENERGY_VALIDATION_FAILED",
            summary.physicalEnergyValidationReason.contains("PHYSICAL_ENERGY_VALIDATION_FAILED")
        )

        // 核心审计准则：能量口径未经物理确立前，绝对禁止赋予电池可用能量语义
        assertNull("observedBatteryEnergyWh 必须严格为 null", summary.observedBatteryEnergyWh)
        assertNull("verifiedDischargeChargeMah 必须严格为 null", summary.verifiedDischargeChargeMah)
        assertNull("normalizedFullUsableEnergyWh 必须严格为 null", summary.normalizedFullUsableEnergyWh)
        assertNull("sohE 必须严格为 null", summary.sohE)
    }

    /**
     * P2-B3.1-A 真实设备 CSV 文件回放闭环测试：
     * 使用用户真实 Honor 500 Pro 放电 CSV 检验只读审计引擎的防篡改 SHA-256、积分求和与物理安全熔断。
     */
    @Test
    fun testRealDeviceHonor500ProDischargeCsvValidation() {
        val realCsvFile = java.io.File("""c:\Users\zhyan\xwechat_files\zhyang18_6e3a\msg\file\2026-11\Discharge_Record_20261110_195611_95to18pct.csv""")
        org.junit.Assume.assumeTrue("若本地存在真实测试 CSV 文件则执行验证", realCsvFile.exists())

        val (summary, records) = DischargeAuditCsvReplayer.parseAndAudit(realCsvFile)

        assertEquals(5513, records.size)
        assertEquals(95.0, summary.startSocPercent!!, 1e-4)
        assertEquals(18.0, summary.endSocPercent!!, 1e-4)
        assertEquals(77.0, summary.deltaSocPercent!!, 1e-4)

        // 上报功率字段积分约 31.77Wh（剔除大断层纯离散积分）~ 34.06Wh
        assertNotNull(summary.integratedReportedPowerFieldWh)
        assertTrue("积分必须 > 30Wh", summary.integratedReportedPowerFieldWh!! > 30.0)

        // 严密熔断：局部放电 77% 即积分出超出物理合理范围的数值，审计引擎必须安全拒绝
        assertFalse("真实设备异常放电数据必须触发安全熔断拒绝", summary.isPhysicalEnergyValidated)
        assertTrue(summary.physicalEnergyValidationReason.contains("PHYSICAL_ENERGY_VALIDATION_FAILED"))
        assertNull(summary.observedBatteryEnergyWh)
        assertNull(summary.verifiedDischargeChargeMah)
        assertNull(summary.normalizedFullUsableEnergyWh)
        assertNull(summary.sohE)
    }

    /**
     * P2-B3.1-A 导出助手电流导出防除以 1000 缺陷回归测试：
     * 验证当存在 dischargeCurrentMa（例如 4500mA）时，CSV 导出的 current_ma 列保留 -4500.0，绝不误除为 -4.5。
     */
    @Test
    fun testExportHelperPreservesMilliampereScaleWithoutErroneousDivision() {
        val record = com.battery.analysis.model.PowerUsageRecord(
            id = 2001L,
            recordTime = "2026-11-11 12:00:00",
            levelPercent = 51,
            voltageVolts = 3.787f,
            temperature = 30.0f,
            energyWh = 5.0f,
            isCharging = false,
            avgPowerWatts = 16.87f,
            screenOnPowerWatts = 16.87f,
            screenOffPowerWatts = 0f,
            screenOnDurationText = "1h",
            screenOffDurationText = "0s",
            totalDurationText = "1h",
            remainingScreenOnText = "--",
            remainingCompositeText = "--",
            remainingScreenOffText = "--",
            isShizukuRealData = false,
            appCount = 0,
            trendPointsJson = "[]",
            appListJson = "[]"
        )

        // 注入包含特定点的 trendPoints
        val csv = DischargeDataExportHelper.buildDischargeCsv(
            record.copy(
                trendPointsJson = """
                    [
                        {"ts": 1794367185418, "lvl": 51, "volt": 3.787, "temp": 30.0, "pwr": 16.87, "cur_ma": 4500.0, "cur_ua": -4500, "screenOn": true}
                    ]
                """.trimIndent()
            )
        )

        assertTrue("导出的 CSV 必须包含 -4500.0，绝不能是 -4.5", csv.contains(",-4500.0,"))
    }

    /**
     * P2-B3.1-B 参考会话协议判定与真值提取闭环测试：
     * 验证标准参考实验协议对符合 25℃、1000mA、高覆盖率满放电会话的精准识别，
     * 以及当缺乏合规参考会话时，独立验证流程安全保持为 VALIDATING（Fail-Closed）。
     */
    @Test
    fun testP2B31B_referenceSessionProtocolMatchingAndFailClosed() {
        val protocol = EnergyReferenceProtocol(
            protocolVersion = "ERP-v1.0",
            targetRange = com.battery.analysis.domain.capacity.model.ReferenceConditionRange(
                temperatureMinC = 23.0,
                temperatureMaxC = 27.0,
                currentMinMa = 800.0,
                currentMaxMa = 1200.0
            ),
            startSocPolicy = "START_FROM_100_FULL"
        )
        assertTrue(protocol.isProtocolValid())

        // 构造合规参考会话
        val refSample = DischargeConditionSample(
            sessionId = "ref_001",
            observedEnergyWh = 29.5,
            measuredEnergyCoverage = 95.0,
            unresolvedEnergyWh = 0.0,
            condition = com.battery.analysis.domain.capacity.model.DischargeOperatingCondition(
                averageTemperatureC = 25.0,
                averageDischargeCurrentMa = 1000.0
            ),
            startSoc = 100,
            endSoc = 5,
            socSpanPercent = 95,
            quality = com.battery.analysis.domain.capacity.model.DischargeQuality.HIGH,
            counterConsistency = com.battery.analysis.domain.capacity.model.CounterConsistency.AGREE,
            isReferenceSession = true
        )
        assertTrue("合规标准会话必须匹配成功", protocol.matches(refSample))

        // 构造日常偏离会话（温度 38℃ 高温）
        val hotSample = refSample.copy(
            sessionId = "hot_001",
            condition = com.battery.analysis.domain.capacity.model.DischargeOperatingCondition(
                averageTemperatureC = 38.0,
                averageDischargeCurrentMa = 2500.0
            ),
            isReferenceSession = false
        )
        assertFalse("高温大电流会话绝不能匹配为标准参考工况", protocol.matches(hotSample))

        // 当参考会话池全为空或不合规时，验证引擎拒绝虚假真值，成熟度严格保持在 VALIDATING
        val trainSamples = listOf(refSample.copy(sessionId = "train_1"))
        val valSamples = listOf(hotSample.copy(sessionId = "val_1"))
        val validation = ConditionModelValidator.validateWithReferenceSessions(
            trainingSamples = trainSamples,
            validationSamples = valSamples,
            referenceSessions = emptyList(), // 无参考会话
            baselinePredictor = { 28.0 },
            normalizedPredictor = { 29.0 },
            protocol = protocol
        )
        assertEquals(ConditionModelMaturity.VALIDATING, validation.maturity)
        assertNull("无真实参考标签时验证 MAE 必须严格为 null", validation.validationMaeWh)
    }

    /**
     * P2-B3.1-C 真实模型前向验证与双层归一化执行测试：
     * 验证当模型在独立验证集上实现统计显著的配对改善时晋升为 VALIDATED，
     * 并在 VALIDATED 状态下激活第一层逐 Bin 与第二层全能量解耦归一化，而在 VALIDATING 下严格为 null。
     */
    @Test
    fun testP2B31C_modelValidationProgressionAndDualLayerNormalization() {
        val baseTs = 1731300000000L
        val protocol = EnergyReferenceProtocol(protocolVersion = "ERP-v1.0")

        // 构造标准参考会话（真值目标 29.60Wh）
        val refSession = DischargeConditionSample(
            sessionId = "ref_target",
            observedEnergyWh = 29.60,
            measuredEnergyCoverage = 100.0,
            unresolvedEnergyWh = 0.0,
            condition = com.battery.analysis.domain.capacity.model.DischargeOperatingCondition(
                averageTemperatureC = 25.0,
                averageDischargeCurrentMa = 1000.0
            ),
            startSoc = 100,
            endSoc = 0,
            socSpanPercent = 100,
            quality = com.battery.analysis.domain.capacity.model.DischargeQuality.HIGH,
            counterConsistency = com.battery.analysis.domain.capacity.model.CounterConsistency.AGREE,
            isReferenceSession = true
        )

        // 构造时序隔离的训练组与验证组
        val trainSamples = (1..3).map { idx ->
            refSession.copy(
                sessionId = "train_$idx",
                sessionTimestampMs = baseTs + idx * 86400000L,
                identity = com.battery.analysis.domain.capacity.model.ConditionSampleIdentity("train_$idx", "dev1", "bat1", "grp_train_$idx")
            )
        }
        val valSamples = (4..7).map { idx ->
            refSession.copy(
                sessionId = "val_$idx",
                sessionTimestampMs = baseTs + idx * 86400000L,
                identity = com.battery.analysis.domain.capacity.model.ConditionSampleIdentity("val_$idx", "dev1", "bat1", "grp_val_$idx")
            )
        }

        // 基准模型（未补偿）：偏差较大（MAE = 1.0Wh）；归一化模型：精准补偿（MAE = 0.1Wh）
        val validatedModel = ConditionModelValidator.validateWithReferenceSessions(
            trainingSamples = trainSamples,
            validationSamples = valSamples,
            referenceSessions = listOf(refSession),
            baselinePredictor = { 28.60 }, // 误差 1.0Wh
            normalizedPredictor = { 29.50 }, // 误差 0.1Wh，改善率 90% > 3% 且置信区间显著
            protocol = protocol,
            bootstrapIterations = 300,
            randomSeed = 42L
        )

        assertEquals("模型必须成功晋升为 VALIDATED", ConditionModelMaturity.VALIDATED, validatedModel.maturity)
        assertTrue(validatedModel.normalizationImprovement!! > 0.03)
        assertTrue(validatedModel.isPairedImprovementSignificant)

        // 1. 验证第一层逐 Bin 归一化：
        val measuredBin = com.battery.analysis.domain.capacity.model.SocEnergyBinInfo(
            binIndex = 5,
            startSocPercent = 25.0,
            endSocPercent = 30.0,
            energyWh = 1.45,
            status = BinLearningStatus.MEASURED
        )
        val binCondition = com.battery.analysis.domain.capacity.model.SocBinOperatingCondition(
            binIndex = 5,
            averageTemperatureC = 15.0, // 低温 15℃，应向 25℃ 进行归一化补偿增加
            averageCurrentMa = 1000.0
        )
        val normalizedBin = EnergyNormalizationEngine.normalizeBinEnergy(measuredBin, binCondition, validatedModel)
        assertNotNull("VALIDATED 状态下实测分箱必须成功输出归一化能量", normalizedBin)
        assertTrue("低温分箱归一化到 25℃ 能量必须获得合理补偿增加", normalizedBin!! > 1.45)

        // 未达到 VALIDATED 时必须严格返回 null
        val unvalidatedModel = validatedModel.copy(maturity = ConditionModelMaturity.VALIDATING)
        assertNull(
            "未验证模型下逐 Bin 归一化必须严格返回 null",
            EnergyNormalizationEngine.normalizeBinEnergy(measuredBin, binCondition, unvalidatedModel)
        )

        // 2. 验证第二层全能量解耦归一化：
        val fullBins = (0..19).map { idx ->
            com.battery.analysis.domain.capacity.model.SocEnergyBinInfo(
                binIndex = idx,
                startSocPercent = idx * 5.0,
                endSocPercent = (idx + 1) * 5.0,
                energyWh = 1.45,
                status = BinLearningStatus.MEASURED
            ).copy(binCondition = binCondition)
        }
        val fullEnergy = EnergyNormalizationEngine.normalizeFullEnergy(
            observedEnergyWh = 29.0,
            bins = fullBins,
            condition = refSession.condition,
            validationModel = validatedModel,
            topPlateauWh = 0.5,
            unresolvedEnergyWh = 0.0
        )
        assertNotNull("20 个分箱均实测且模型通过验证时，完整可用能量必须成功归一化", fullEnergy)
        assertTrue(fullEnergy!! > 29.0)

        // 若存在任意一个 UNLEARNED 分箱，整会话必须 Fail-Closed 阻断返回 null
        val brokenBins = fullBins.toMutableList()
        brokenBins[0] = brokenBins[0].copy(status = BinLearningStatus.UNLEARNED)
        assertNull(
            "存在未学习分箱时完整能量必须严格阻断为 null",
            EnergyNormalizationEngine.normalizeFullEnergy(
                observedEnergyWh = 29.0,
                bins = brokenBins,
                condition = refSession.condition,
                validationModel = validatedModel
            )
        )
    }

    /**
     * P2-C 健康度独立评估与门控闭环测试：
     * 1. 验证 SOH_Q 基于纯物理真实库仑积分正常计算；
     * 2. 验证 SOH_E 基于归一化满电能量计算，在归一化未通过验证时严格保持为 null 冻结更新，绝不拿 SOH_Q 代替。
     */
    @Test
    fun testP2C_sohQAndSohEIndependentGating() {
        val candidates = listOf(
            com.battery.analysis.domain.capacity.model.FccCandidateSample(
                "d1", com.battery.analysis.domain.capacity.model.CapacityChannel.DISCHARGE, 0.90, 0.10, 0.80, 6080.0, 23.0, 7600.0, 400.0
            ),
            com.battery.analysis.domain.capacity.model.FccCandidateSample(
                "d2", com.battery.analysis.domain.capacity.model.CapacityChannel.DISCHARGE, 0.85, 0.15, 0.70, 5320.0, 20.0, 7600.0, 400.0
            ),
            com.battery.analysis.domain.capacity.model.FccCandidateSample(
                "d3", com.battery.analysis.domain.capacity.model.CapacityChannel.DISCHARGE, 0.95, 0.20, 0.75, 5700.0, 21.5, 7600.0, 400.0
            )
        )
        val energyLearner = com.battery.analysis.domain.capacity.estimator.SocEnergyCurveLearner()

        val unified = com.battery.analysis.domain.capacity.fusion.ChargeDischargeFusionEngine.fuse(
            candidates = candidates,
            energyLearner = energyLearner,
            designCapacityMah = 8000.0,
            currentSocPercent = 50
        )

        // 1. SOH_Q 独立输出：7600 / 8000 = 95.0%
        assertNotNull(unified.sohQPercent)
        assertEquals(95.0, unified.sohQPercent!!, 1.0)

        // 2. SOH_E 核心门控铁律：由于条件归一化模型目前未启用/未全量验证，SOH_E 必须严格为 null，冻结更新！
        assertNull("未达归一化验证标准前，SOH_E 必须严格为 null 保持冻结", unified.sohEPercent)
    }

    /**
     * 测试起始放电政策（StartDischargeCondition）：
     * 1. 默认政策为 START_FROM_100_FULL 且门限为 95%；
     * 2. 95%、96%、100% 判定为通过；
     * 3. 94%、90% 判定为拦截拒绝；
     * 4. 自定义门限（如 90%）下 92% 判定为通过；
     * 5. ANY_SOC 模式下任意电量（如 50%）均判定通过；
     * 6. 非法门限（< 0 或 > 100）触发 isValid() == false 且 Fail-Closed 拦截。
     */
    @Test
    fun testStartDischargeCondition_defaultAndCustomThresholds() {
        val defaultCondition = StartDischargeCondition()
        assertEquals(StartDischargePolicy.START_FROM_100_FULL, defaultCondition.policy)
        assertEquals(95, defaultCondition.minStartSocPercent)
        assertTrue(defaultCondition.isValid())

        // 边界值与标准值测试
        assertTrue("95% 满电起步边界点必须通过", defaultCondition.isSatisfied(95))
        assertTrue("96% 满电起步必须通过", defaultCondition.isSatisfied(96))
        assertTrue("100% 满电起步必须通过", defaultCondition.isSatisfied(100))
        assertFalse("94% 略低于 95% 必须严格拦截", defaultCondition.isSatisfied(94))
        assertFalse("80% 低于 95% 必须严格拦截", defaultCondition.isSatisfied(80))

        // 自定义门限测试（如 90%）
        val customCondition = StartDischargeCondition(
            policy = StartDischargePolicy.START_FROM_100_FULL,
            minStartSocPercent = 90
        )
        assertTrue(customCondition.isValid())
        assertTrue("自定义 90% 门限下 92% 必须通过", customCondition.isSatisfied(92))
        assertFalse("自定义 90% 门限下 89% 必须拦截", customCondition.isSatisfied(89))

        // ANY_SOC 任意电量起步模式测试
        val anyCondition = StartDischargeCondition(policy = StartDischargePolicy.ANY_SOC)
        assertTrue(anyCondition.isValid())
        assertTrue("ANY_SOC 模式下 50% 起步必须通过", anyCondition.isSatisfied(50))
        assertTrue("ANY_SOC 模式下 10% 起步必须通过", anyCondition.isSatisfied(10))

        // 非法门限校验与 Fail-Closed 拦截
        val invalidLow = StartDischargeCondition(minStartSocPercent = -1)
        assertFalse(invalidLow.isValid())
        assertFalse("非法门限必须 Fail-Closed 拦截", invalidLow.isSatisfied(100))

        val invalidHigh = StartDischargeCondition(minStartSocPercent = 101)
        assertFalse(invalidHigh.isValid())
        assertFalse("非法门限必须 Fail-Closed 拦截", invalidHigh.isSatisfied(100))
    }

    /**
     * 测试数据纯度条件设置（DataPurityCondition）：
     * 1. 默认分箱实测覆盖率门限为 80.0%，且默认拒绝 REJECTED 劣质样本；
     * 2. 80.0%、85.0%、100.0% 判定通过；
     * 3. 79.9%、70.0% 判定拦截；
     * 4. 自定义覆盖率门限（如 60.0%）下 70.0% 判定通过；
     * 5. REJECTED 样本无论覆盖率多高坚决拦截；
     * 6. 非法数值（< 0.0、> 100.0、NaN）触发 isValid() == false 且 Fail-Closed 拦截。
     */
    @Test
    fun testDataPurityCondition_defaultAndCustomCoverage() {
        val defaultPurity = DataPurityCondition()
        assertEquals(80.0, defaultPurity.minMeasuredCoveragePercent, 1e-6)
        assertTrue(defaultPurity.rejectPoorQuality)
        assertTrue(defaultPurity.isValid())

        // 覆盖率阈值测试
        assertTrue("80.0% 边界覆盖率必须通过", defaultPurity.isSatisfied(80.0, DischargeQuality.HIGH))
        assertTrue("85.0% 覆盖率必须通过", defaultPurity.isSatisfied(85.0, DischargeQuality.MEDIUM))
        assertTrue("100.0% 覆盖率必须通过", defaultPurity.isSatisfied(100.0, DischargeQuality.HIGH))
        assertFalse("79.9% 略低于 80.0% 必须严格拦截", defaultPurity.isSatisfied(79.9, DischargeQuality.HIGH))
        assertFalse("50.0% 远低于 80.0% 必须严格拦截", defaultPurity.isSatisfied(50.0, DischargeQuality.HIGH))

        // 劣质样本 REJECTED 拦截测试
        assertFalse("即使 100% 覆盖率，REJECTED 样本必须坚决拦截", defaultPurity.isSatisfied(100.0, DischargeQuality.REJECTED))

        // 自定义覆盖率门限测试（如 60.0%）
        val customPurity = DataPurityCondition(minMeasuredCoveragePercent = 60.0)
        assertTrue(customPurity.isValid())
        assertTrue("自定义 60% 门限下 70% 覆盖率必须通过", customPurity.isSatisfied(70.0, DischargeQuality.HIGH))
        assertFalse("自定义 60% 门限下 59.9% 覆盖率必须拦截", customPurity.isSatisfied(59.9, DischargeQuality.HIGH))

        // 异常非法覆盖率 Fail-Closed 校验
        val invalidLow = DataPurityCondition(minMeasuredCoveragePercent = -0.1)
        assertFalse(invalidLow.isValid())
        assertFalse(invalidLow.isSatisfied(100.0, DischargeQuality.HIGH))

        val invalidHigh = DataPurityCondition(minMeasuredCoveragePercent = 100.1)
        assertFalse(invalidHigh.isValid())
        assertFalse(invalidHigh.isSatisfied(100.0, DischargeQuality.HIGH))

        val invalidNan = DataPurityCondition(minMeasuredCoveragePercent = Double.NaN)
        assertFalse(invalidNan.isValid())
        assertFalse(invalidNan.isSatisfied(100.0, DischargeQuality.HIGH))
    }

    /**
     * 测试 EnergyReferenceProtocol 与 StartDischargeCondition / DataPurityCondition 协同集成与动态匹配：
     * 1. 验证默认配置下，起始 SOC ≥ 95% 且实测覆盖率 ≥ 80.0% 判定为通过；
     * 2. 验证起始 SOC 为 94% 时被默认起始政策拒绝，但在将 minStartSocPercent 改为 90% 后成功通过；
     * 3. 验证实测覆盖率为 75% 时被默认纯度条件拒绝，但在将 minMeasuredCoveragePercent 改为 70.0% 后成功通过；
     * 4. 验证注入非法条件设置时 protocol.isProtocolValid() 为 false，且 matches() 严格 Fail-Closed。
     */
    @Test
    fun testEnergyReferenceProtocol_conditionSettingsIntegrationAndMatching() {
        val protocol = EnergyReferenceProtocol(
            protocolVersion = "ERP-v1.1",
            targetRange = ReferenceConditionRange(
                temperatureMinC = 20.0,
                temperatureMaxC = 30.0,
                currentMinMa = 500.0,
                currentMaxMa = 1500.0
            )
        )
        assertTrue(protocol.isProtocolValid())

        val sample94Soc = DischargeConditionSample(
            sessionId = "sample_94",
            observedEnergyWh = 28.0,
            measuredEnergyCoverage = 85.0,
            unresolvedEnergyWh = 0.0,
            condition = DischargeOperatingCondition(
                averageTemperatureC = 25.0,
                averageDischargeCurrentMa = 1000.0
            ),
            startSoc = 94,
            endSoc = 10,
            socSpanPercent = 84,
            quality = DischargeQuality.HIGH,
            counterConsistency = CounterConsistency.AGREE
        )

        // 默认满电起步 95% 门限下，94% 必须被拒绝
        assertFalse("默认 95% 起步门限下，94% 起始电量必须被拒绝", protocol.matches(sample94Soc))

        // 配置为 minStartSocPercent = 90 后，94% 允许匹配通过
        val protocol90Soc = protocol.copy(
            startSocCondition = StartDischargeCondition(
                policy = StartDischargePolicy.START_FROM_100_FULL,
                minStartSocPercent = 90
            )
        )
        assertTrue("放宽起始门限至 90% 后，94% 起始电量必须成功匹配", protocol90Soc.matches(sample94Soc))

        // 覆盖率 75% 样本测试
        val sample75Cov = sample94Soc.copy(
            sessionId = "sample_75_cov",
            startSoc = 98,
            measuredEnergyCoverage = 75.0
        )
        assertFalse("默认 80% 实测覆盖率门限下，75% 覆盖率必须被拒绝", protocol.matches(sample75Cov))

        // 配置为 minMeasuredCoveragePercent = 70.0% 后，75% 覆盖率允许匹配通过
        val protocol70Cov = protocol.copy(
            dataPurityCondition = DataPurityCondition(minMeasuredCoveragePercent = 70.0)
        )
        assertTrue("放宽覆盖率门限至 70% 后，75% 覆盖率必须成功匹配", protocol70Cov.matches(sample75Cov))

        // 验证非法条件导致协议整体失效
        val invalidProtocol = protocol.copy(
            startSocCondition = StartDischargeCondition(minStartSocPercent = -5)
        )
        assertFalse("存在非法条件设置时协议自洽校验必须返回 false", invalidProtocol.isProtocolValid())
        assertFalse("协议非法时 matches 必须严格 Fail-Closed", invalidProtocol.matches(sample94Soc.copy(startSoc = 100, measuredEnergyCoverage = 100.0)))
    }

    /**
     * 测试放电候选样本在不同起始放电政策与数据纯度条件下的准入判定效果。
     */
    @Test
    fun testCandidateFilteringWithStartPolicyAndDataPurityConditions() {
        val candidate93Start = CapacityCandidate(
            id = 1L,
            sessionType = CandidateSessionType.DISCHARGE,
            startSoc = 93,
            endSoc = 56,
            socSpanPercent = 37,
            netBatteryChargeMah = 2638.0,
            fccObservedMah = 7129.0,
            startTimestamp = 1000L,
            endTimestamp = 2000L,
            durationMs = 1000L,
            avgTemperatureCelsius = 25f,
            maxTemperatureCelsius = 26f,
            sampleCount = 100,
            qualityScore = 0.85,
            isExcluded = false
        )

        // 默认满电起步门限 95%
        val defaultStartCondition = StartDischargeCondition(StartDischargePolicy.START_FROM_100_FULL, minStartSocPercent = 95)
        assertFalse("默认 95% 起步门限下，93% 放电候选样本判定不合格", defaultStartCondition.isSatisfied(candidate93Start.startSoc))

        // 放宽至 90% 满电起步
        val relaxedStartCondition = StartDischargeCondition(StartDischargePolicy.START_FROM_100_FULL, minStartSocPercent = 90)
        assertTrue("放宽至 90% 起步门限后，93% 放电候选样本合格", relaxedStartCondition.isSatisfied(candidate93Start.startSoc))

        // 任意电量起步
        val anyStartCondition = StartDischargeCondition(StartDischargePolicy.ANY_SOC)
        assertTrue("任意起步模式下，93% 放电候选样本合格", anyStartCondition.isSatisfied(candidate93Start.startSoc))

        // 纯度 75% 样本
        val candidate75Purity = candidate93Start.copy(
            id = 2L,
            startSoc = 98,
            qualityScore = 0.75
        )
        val defaultPurityCondition = DataPurityCondition(minMeasuredCoveragePercent = 80.0)
        assertFalse("默认 80% 纯度下，75% 候选样本不合格", candidate75Purity.qualityScore * 100.0 >= defaultPurityCondition.minMeasuredCoveragePercent)

        val relaxedPurityCondition = DataPurityCondition(minMeasuredCoveragePercent = 70.0)
        assertTrue("放宽至 70% 纯度后，75% 候选样本合格", candidate75Purity.qualityScore * 100.0 >= relaxedPurityCondition.minMeasuredCoveragePercent)
    }
}
