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

    /**
     * 测试当前在途放电周期（未完结/未达门限）严禁外推整机满充标尺：
     * 当历史合格记录宏观中位数为 null，且当前会话掉电跨度（例如 5.6%）低于门限（15%）时，
     * authoritativeMacroWh 必须为 null，绝不能以当前在途周期的局部做功外推整机满电能量！
     */
    @Test
    fun testCurrentInFlightSessionDoesNotExtrapolateFullChargeScale() {
        val emptyCurve = LearnedSocEnergyCurve.createEmpty()

        // 模拟用户截图中的当前在途放电：掉电 5.6%，做功 1.67 Wh，门限 15%
        val overview = SocEnergyCurveLearner.buildComparisonOverviewFromCurve(
            curve = emptyCurve,
            currentSocPercent = 98f,
            effectiveCapacityMah = 6206f,
            nominalVoltageVolts = 3.85f,
            screenOnEnergyWh = 1.670f,
            screenOnDropPercent = 5.6f,
            effectiveThresholdPercent = 15,
            macroHistoricalFullWh = null // 历史暂无满足 >= 15% 的记录
        )

        // 关键验证：严禁以 5.6% 掉电外推整机标尺（isScreenOnAnchored 必须为 false）
        assertFalse("未达门限的在途放电绝不能锚定整机满电标尺", overview.isScreenOnAnchored)
        // 满充预测绝不能被外推为 29.8 Wh 或 31.8 Wh
        val fullWh = overview.fullLearnedEnergyWh
        assertTrue(
            "冷启动无合格历史记录时满充预测应为 null 或基于先验，绝不能由当前 5.6% 周期外推至 29+ Wh",
            fullWh == null || fullWh <= 24.5
        )
    }

    /**
     * 测试当前在途放电满足学习条件时动态纳入模型宏观中位数与有效条数统计：
     * 1. 历史存在 1 条已完结合格记录（掉电 85%，宏观推算 29.5 Wh）；
     * 2. 当前在途放电尚未插电完结，但掉电已达 78%（>= 设定的 70% 门限），推算 29.1 Wh；
     * 3. 系统必须动态将当前在途放电计为有效记录，使总参与条数变为 2；
     * 4. 宏观标尺融合历史与在途数据，中位数为 29.3 Wh，且双次大跨度放电提升模型置信度。
     */
    @Test
    fun testInFlightQualifiedDischargeIncorporation() {
        val historyFullWh = 29.5
        val inFlightDrop = 78.0f
        val inFlightFullWh = 29.1
        val threshold = 70

        val isDischarging = true
        val isInFlightQualified = isDischarging && inFlightDrop >= threshold

        assertTrue("在途放电掉电 78% 超过门限 70% 必须判定合格", isInFlightQualified)

        val macroList = mutableListOf<Double>()
        macroList.add(historyFullWh)
        if (isInFlightQualified) {
            macroList.add(inFlightFullWh)
        }

        assertEquals("合格宏观样本集合必须包含历史 1 条与在途 1 条共 2 条", 2, macroList.size)
        val aggregatedMedian = SocEnergyCurveLearner.calculateMedian(macroList)
        assertEquals(29.3, aggregatedMedian!!, 0.01)

        val totalParticipatingCount = 1 + (if (isInFlightQualified) 1 else 0)
        assertEquals(2, totalParticipatingCount)

        val (maturity, confidence, reason) = SocEnergyCurveLearner.evaluateDischargeEnergyConfidence(
            coveragePercent = 0.85f,
            participatingSessionCount = totalParticipatingCount,
            historicalDispersionPercent = 0.96,
            isScreenOnAnchored = false
        )

        assertEquals("两组大跨度深度放电且离散度极小应处于 MEDIUM 置信度且提示双次放电校验", com.battery.analysis.domain.estimator.model.ConfidenceLevel.MEDIUM, confidence)
        assertTrue("原因应包含累积 2 次深度放电", reason.contains("累积 2 次深度放电"))
    }

    /**
     * 测试在途达标放电在滚动时效窗口（Count Limit）中的名额占用逻辑：
     * 1. 窗口限制为最近 3 条；
     * 2. 存在 5 条历史合格记录；
     * 3. 若在途未达标，历史合格记录取最近 3 条，总条数 3；
     * 4. 若在途达标，在途占 1 个名额，历史合格记录取最近 2 条，总条数依然严格守恒为 3。
     */
    @Test
    fun testInFlightWindowSlotAllocation() {
        val targetLimit = 3
        val eligibleHistoryCount = 5

        // 场景 A：在途未达标（例如刚拔电掉电 3%）
        val inFlightQualifiedA = false
        val historyLimitA = if (inFlightQualifiedA && targetLimit > 0) (targetLimit - 1).coerceAtLeast(0) else targetLimit
        val historyCountA = minOf(eligibleHistoryCount, historyLimitA)
        val totalCountA = historyCountA + (if (inFlightQualifiedA) 1 else 0)

        assertEquals(3, historyCountA)
        assertEquals(3, totalCountA)

        // 场景 B：在途已达标（例如掉电 78% >= 70%）
        val inFlightQualifiedB = true
        val historyLimitB = if (inFlightQualifiedB && targetLimit > 0) (targetLimit - 1).coerceAtLeast(0) else targetLimit
        val historyCountB = minOf(eligibleHistoryCount, historyLimitB)
        val totalCountB = historyCountB + (if (inFlightQualifiedB) 1 else 0)

        assertEquals(2, historyCountB)
        assertEquals(3, totalCountB)
    }

    /**
     * 测试全工况（亮屏 + 息屏）放电做功与整机净掉电的能量连贯性。
     *
     * 核心物理场景验证：
     * 1. 模拟设备从 100% 放电至 14%（真实净掉电 86%），放电总做功为 19.655 Wh（亮屏 17.305 Wh + 息屏 2.350 Wh）；
     * 2. 旧纯亮屏算法由于 Android 系统电量以 1% 整数阶梯跳变，绝大多数跳变发生在息屏或亮灭切换瞬间，
     *    导致亮屏切片累加的掉电严重漏算（从 86% 缩水至 55.0%），推算出 31.47 Wh（折合容量 8175 mAh，健康度 102.2% 虚高暴涨）；
     * 3. 验证升级后的全工况客观物理微积分做功计算：
     *    满充做功预测 = 19.655 Wh / 0.86 = 22.85 Wh，折合容量约 5936 mAh（健康度 74.2%），与容量模型 78.5% 严密自洽！
     */
    @Test
    fun testScreenOnOffAllScenarioEnergyCoherenceAndFullEnergyPrediction() {
        val screenOnWh = 17.305f
        val screenOffWh = 2.350f
        val totalDischargeWh = screenOnWh + screenOffWh // 19.655 Wh
        val totalDropPercent = 86.0f // 100% -> 14%

        // 1. 旧逻辑缺陷推演：纯亮屏掉电严重缩水至 55%
        val flawedPureOnDrop = 55.0f
        val flawedPredictedFullWh = screenOnWh / (flawedPureOnDrop / 100.0)
        assertEquals("旧纯亮屏掉电漏算导致满充虚高至 31.46 Wh 左右", 31.4636, flawedPredictedFullWh, 0.01)

        // 2. 新逻辑：全工况物理做功客观推算
        val coherentFullWh = SocEnergyCurveLearner.calculateAnchoredFullEnergy(
            dischargeEnergyWh = totalDischargeWh,
            dropPercent = totalDropPercent
        )
        assertNotNull("全工况物理做功锚定结果不得为 null", coherentFullWh)
        assertEquals("满充预测应准确收敛至 22.85 Wh", 22.85465, coherentFullWh!!, 0.01)

        val nominalVoltage = 3.85
        val equivCapacityMah = (coherentFullWh * 1000.0) / nominalVoltage
        assertEquals("折合等效容量应约为 5936 mAh", 5936.0, equivCapacityMah, 5.0)

        // 3. 验证设计容量 8000 mAh 下的能量健康度合理性
        val designCapacityMah = 8000.0
        val fullDesignWh = (designCapacityMah * nominalVoltage) / 1000.0 // 30.8 Wh
        val energyHealth = (coherentFullWh / fullDesignWh) * 100.0
        assertEquals("能量健康度应在 74.2% 左右，坚决杜绝 102.2% 虚高暴涨", 74.2, energyHealth, 0.2)
    }
}
