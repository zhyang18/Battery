package com.battery.analysis

import com.battery.analysis.domain.estimator.PowerEstimator
import com.battery.analysis.domain.estimator.RollingDischargeBuffer
import com.battery.analysis.domain.estimator.RuntimeEstimator
import com.battery.analysis.domain.estimator.model.RuntimeModelType
import com.battery.analysis.manager.PowerOverviewStats
import com.battery.analysis.model.PowerDischargePoint
import com.battery.analysis.model.SevenDaysDischargeStats
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 物理级自适应剩余续航估计引擎（RuntimeEstimator）单元测试套件。
 * 验证温度修正系数、可用能量计算、亮屏/息屏/全局三维物理推算，
 * 以及零假数据边界保护与数据缺失安全降级。
 */
class RuntimeEstimatorTest {

    private val estimator = RuntimeEstimator()

    @org.junit.Before
    fun setUp() {
        RollingDischargeBuffer.resetForTesting()
        com.battery.analysis.domain.estimator.EnduranceRuntimeSmoother.getInstance().reset()
    }

    @org.junit.After
    fun tearDown() {
        RollingDischargeBuffer.resetForTesting()
        com.battery.analysis.domain.estimator.EnduranceRuntimeSmoother.getInstance().reset()
    }

    /**
     * 测试电芯工作温度修正系数 K_T 在常温、极低温与极端高温下的物理衰减特性。
     */
    @Test
    fun testTemperatureFactor() {
        // 常温 25℃ 系数为 1.0（无损耗）
        assertEquals(1.0, RuntimeEstimator.calculateTemperatureFactor(25.0f), 0.001)

        // 极低温 -10℃ 活性受限，系数衰减至 0.75
        assertEquals(0.75, RuntimeEstimator.calculateTemperatureFactor(-10.0f), 0.001)

        // 0℃ 时介于 0.75 与 1.0 之间
        val factor0 = RuntimeEstimator.calculateTemperatureFactor(0.0f)
        assertTrue("0℃ 介于 0.75 至 1.0", factor0 in 0.75..1.0)

        // 高温 50℃ 发热内阻损耗，轻度衰减至 0.95
        assertEquals(0.95, RuntimeEstimator.calculateTemperatureFactor(50.0f), 0.001)
    }

    /**
     * 测试有效可用物理能量换算（支持直接能量值与容量电量换算两路输入）。
     */
    @Test
    fun testCalculateUsableEnergyWh() {
        // 直接读取硬件剩余能量 10.0 Wh
        val eDirect = estimator.calculateUsableEnergyWh(
            remainingEnergyWh = 10.0f,
            batteryLevelPercent = null,
            totalCapacityWh = null,
            tempCelsius = 25.0f
        )
        assertNotNull(eDirect)
        assertEquals(10.0, eDirect!!, 0.01)

        // 通过 50% 电量与 20.0 Wh 总能量换算 -> 10.0 Wh
        val eCalculated = estimator.calculateUsableEnergyWh(
            remainingEnergyWh = null,
            batteryLevelPercent = 50.0f,
            totalCapacityWh = 20.0f,
            tempCelsius = 25.0f
        )
        assertNotNull(eCalculated)
        assertEquals(10.0, eCalculated!!, 0.01)

        // 数据全缺失时严格返回 null，严禁假数据
        val eMissing = estimator.calculateUsableEnergyWh(null, null, null)
        assertNull(eMissing)
    }

    /**
     * 测试持续亮屏续航推算（T_on = E_usable / P_on）。
     */
    @Test
    fun testEstimateScreenOn() {
        val onPowerEstimator = PowerEstimator()
        onPowerEstimator.seedCurrentSession(durationMs = 1_800_000L, powerWatts = 3.85f)

        // 可用能量 19.25 Wh，亮屏功率 3.85 W -> 理论续航恰好 5 小时 = 18,000,000 ms
        val result = estimator.estimateScreenOn(
            usableEnergyWh = 19.25,
            onPowerEstimator = onPowerEstimator,
            batteryLevelPercent = 100.0f
        )

        assertNotNull(result.remainingMs)
        assertEquals(18_000_000L, result.remainingMs!!)
        assertEquals(3.85, result.predictedPowerWatts!!, 0.01)
        assertTrue("具有较高置信度", result.confidence >= 0.60)
        assertEquals(RuntimeModelType.MULTI_TIMESCALE, result.modelType)
    }

    /**
     * 测试持续息屏待机续航推算（T_off = E_usable / P_off）。
     */
    @Test
    fun testEstimateScreenOff() {
        val offPowerEstimator = PowerEstimator()
        offPowerEstimator.seedCurrentSession(durationMs = 3_600_000L, powerWatts = 0.385f)

        // 可用能量 19.25 Wh，息屏待机功率 0.385 W -> 理论待机 50 小时 = 180,000,000 ms
        val result = estimator.estimateScreenOff(
            usableEnergyWh = 19.25,
            offPowerEstimator = offPowerEstimator,
            batteryLevelPercent = 100.0f
        )

        assertNotNull(result.remainingMs)
        assertTrue("待机时长在 50 小时容差范围内", kotlin.math.abs(180_000_000L - result.remainingMs!!) <= 10L)
        assertEquals(0.385, result.predictedPowerWatts!!, 0.001)
    }

    /**
     * 测试全局综合续航推算（P_global = r_on * P_on + (1 - r_on) * P_off）。
     */
    @Test
    fun testEstimateGlobalComposite() {
        val onEstimator = PowerEstimator()
        onEstimator.seedCurrentSession(1_800_000L, 4.0f) // 亮屏 4.0W

        val offEstimator = PowerEstimator()
        offEstimator.seedCurrentSession(3_600_000L, 0.4f) // 息屏 0.4W

        // 用户日常 20% 亮屏 + 80% 息屏
        // P_global = 0.2 * 4.0 + 0.8 * 0.4 = 0.8 + 0.32 = 1.12 W
        // 可用能量 11.2 Wh -> 理论综合续航 10 小时 = 36,000,000 ms
        val result = estimator.estimateGlobal(
            usableEnergyWh = 11.2,
            screenOnRatio = 0.20,
            onPowerEstimator = onEstimator,
            offPowerEstimator = offEstimator
        )

        assertNotNull(result.remainingMs)
        assertEquals(36_000_000L, result.remainingMs!!)
        assertEquals(1.12, result.predictedPowerWatts!!, 0.01)
        assertEquals(RuntimeModelType.GLOBAL_COMPOSITE, result.modelType)
    }

    /**
     * 测试 estimateTripleRuntimes 一站式三维续航推算入口。
     */
    @Test
    fun testEstimateTripleRuntimesOneStop() {
        val overview = PowerOverviewStats(
            avgPowerWatts = 1.12f,
            screenOnPowerWatts = 4.0f,
            screenOffPowerWatts = 0.4f,
            screenOnDurationMs = 1_800_000L,
            screenOffDurationMs = 7_200_000L,
            totalDurationMs = 9_000_000L
        )

        val sevenDays = SevenDaysDischargeStats(
            screenOnDischargeRatePercentPerHour = 20.0f, // 20%/h 在 20Wh 对应 4.0W
            screenOffDischargeRatePercentPerHour = 2.0f,  // 2%/h 在 20Wh 对应 0.4W
            globalDischargeRatePercentPerHour = 5.6f,
            sceneScreenOnRatio = 0.20f
        )

        val triple = estimator.estimateTripleRuntimes(
            overview = overview,
            sevenDaysStats = sevenDays,
            batteryLevelPercent = 50.0f,
            totalCapacityWh = 20.0f,
            tempCelsius = 25.0f
        )

        // 剩余 10Wh
        assertNotNull(triple.screenOnEstimate.remainingMs)
        assertNotNull(triple.screenOffEstimate.remainingMs)
        assertNotNull(triple.globalEstimate.remainingMs)
    }

    /**
     * 验证数据缺失时严谨返回 null，严禁伪造硬编码假数据。
     */
    @Test
    fun testMissingDataReturnsNull() {
        val onEstimator = PowerEstimator()
        val result = estimator.estimateScreenOn(null, onEstimator)
        assertNull("无能量时续航必须为 null", result.remainingMs)
        assertEquals(RuntimeModelType.INSUFFICIENT_DATA, result.modelType)
    }

    /**
     * 测试会话时长连续线性插值与凸组合工况跃迁响应机制（v2.1 升级特性）。
     * 验证：
     * 1. 验证 0m、5m、10m、20m、30m、60m、120m 这 7 级时间锚点的精确权重值与理论设计完全一致；
     * 2. 在所有区间分界点前后（如 5m、10m、20m、30m、60m、120m）权重连续平滑过渡，无阶跃跳变；
     * 3. 在无 7 天历史基线（Long 为 null）时，Fast 与 Medium 依据既定插值比率自适应重归一化；
     * 4. 验证功耗模型置信度（Confidence）随 7 级时间尺度平滑递增。
     */
    @Test
    fun testContinuousWeightInterpolationAndConvexCombinationRegimeChange() {
        val estimator = PowerEstimator()

        // 1. 验证关键锚点的精确权重值（误差 < 1e-6）
        val expectedAnchors = listOf(
            0L to Triple(0.05, 0.15, 0.80),
            300_000L to Triple(0.10, 0.20, 0.70),      // 5 min
            600_000L to Triple(0.12, 0.28, 0.60),      // 10 min
            1_200_000L to Triple(0.15, 0.35, 0.50),    // 20 min
            1_800_000L to Triple(0.15, 0.45, 0.40),    // 30 min
            3_600_000L to Triple(0.15, 0.55, 0.30),    // 60 min (1h)
            7_200_000L to Triple(0.15, 0.60, 0.25),    // 120 min (亮屏终态)
            10_800_000L to Triple(0.15, 0.60, 0.25)    // 180 min (超出120min保持亮屏终态)
        )

        for ((dur, expected) in expectedAnchors) {
            val (wf, wm, wl) = estimator.interpolateBaselineWeights(dur)
            assertEquals("会话时长 $dur ms 的 Fast 权重匹配", expected.first, wf, 1e-6)
            assertEquals("会话时长 $dur ms 的 Medium 权重匹配", expected.second, wm, 1e-6)
            assertEquals("会话时长 $dur ms 的 Long 权重匹配", expected.third, wl, 1e-6)
            assertEquals("会话时长 $dur ms 的权重归一化和恒等于 1.0", 1.0, wf + wm + wl, 1e-9)
            assertTrue("各分量非负", wf >= 0.0 && wm >= 0.0 && wl >= 0.0)
        }

        // 2. 验证各个时间锚点边界前后的平滑连续性（前后差值残差 < 0.005）
        val boundaryChecks = listOf(
            300_000L,   // 5 min
            600_000L,   // 10 min
            1_200_000L, // 20 min
            1_800_000L, // 30 min
            3_600_000L, // 60 min
            7_200_000L  // 120 min
        )

        for (boundary in boundaryChecks) {
            val (wfBefore, wmBefore, wlBefore) = estimator.interpolateBaselineWeights(boundary - 1_000L)
            val (wfAfter, wmAfter, wlAfter) = estimator.interpolateBaselineWeights(boundary + 1_000L)
            assertTrue("边界 ${boundary}ms 前后的 Fast 权重平滑连续", kotlin.math.abs(wfBefore - wfAfter) < 0.005)
            assertTrue("边界 ${boundary}ms 前后的 Medium 权重平滑连续", kotlin.math.abs(wmBefore - wmAfter) < 0.005)
            assertTrue("边界 ${boundary}ms 前后的 Long 权重平滑连续", kotlin.math.abs(wlBefore - wlAfter) < 0.005)
        }

        // 3. 验证无 Long 历史基准时的自适应比率降级归一化
        estimator.reset(initialLongPowerWatts = null)
        // 0min: baseWf = 0.05, baseWm = 0.15 -> 降级归一化为 0.05/0.20 = 0.25, 0.15/0.20 = 0.75
        estimator.seedCurrentSession(durationMs = 0L, powerWatts = 3.0f)
        val (wf0, wm0, wl0) = estimator.calculateAdaptiveWeights()
        assertEquals(0.0, wl0, 1e-6)
        assertEquals(0.25, wf0, 1e-3)
        assertEquals(0.75, wm0, 1e-3)
        assertEquals(1.0, wf0 + wm0, 1e-9)

        // 120min: baseWf = 0.15, baseWm = 0.60 -> 降级归一化为 0.15/0.75 = 0.20, 0.60/0.75 = 0.80
        estimator.seedCurrentSession(durationMs = 7_200_000L, powerWatts = 3.0f)
        val (wf120, wm120, wl120) = estimator.calculateAdaptiveWeights()
        assertEquals(0.0, wl120, 1e-6)
        assertEquals(0.20, wf120, 1e-3)
        assertEquals(0.80, wm120, 1e-3)
        assertEquals(1.0, wf120 + wm120, 1e-9)
    }

    /**
     * 验证多时间尺度模型置信度（Confidence）随 7 级时间尺度平滑递增的特性。
     */
    @Test
    fun testConfidenceScaleProgression() {
        val estimator = PowerEstimator()
        estimator.reset(initialLongPowerWatts = 3.0)

        // 验证置信度阶梯：0min -> 5min -> 10min -> 20min -> 30min -> 60min -> 120min
        val durationsAndExpectedScores = listOf(
            0L to 0.35,          // 基础 0.30 + Long 0.05
            100_000L to 0.40,    // 0~5min: 基础 0.35 + Long 0.05
            300_000L to 0.50,    // 5~10min: 基础 0.45 + Long 0.05
            600_000L to 0.60,    // 10~20min: 基础 0.55 + Long 0.05
            1_200_000L to 0.70,  // 20~30min: 基础 0.65 + Long 0.05
            1_800_000L to 0.80,  // 30~60min: 基础 0.75 + Long 0.05
            3_600_000L to 0.90,  // 60~120min: 基础 0.85 + Long 0.05
            7_200_000L to 1.00   // >=120min: 基础 0.95 + Long 0.05 = 1.00
        )

        for ((dur, expected) in durationsAndExpectedScores) {
            estimator.seedCurrentSession(durationMs = dur, powerWatts = 3.0f)
            val conf = estimator.getConfidence()
            assertEquals("会话时长 $dur ms 的置信度应为 $expected", expected, conf, 1e-6)
        }
    }

    /**
     * 测试无大样本画像与有大样本画像时，范围包络（minRemainingMs / maxRemainingMs）的自适应物理推导。
     * 验证：彻底杜绝绝对硬编码功率常数，当提供 profile 时严格采用 profile 实测功率，无 profile 时动态按比例自适应伸缩。
     */
    @Test
    fun testAdaptiveRangeEnvelopesWithoutHardcodedConstants() {
        val overview = PowerOverviewStats(
            avgPowerWatts = 2.0f,
            screenOnPowerWatts = 2.5f,
            screenOffPowerWatts = 0.2f,
            screenOnDurationMs = 3_600_000L,
            screenOffDurationMs = 3_600_000L,
            totalDurationMs = 7_200_000L
        )

        // 1. 无 profile：验证亮屏输出的 min / max 续航区间满足 min <= normal <= max
        val tripleNoProfile = estimator.estimateTripleRuntimes(
            overview = overview,
            sevenDaysStats = null,
            batteryLevelPercent = 50.0f,
            totalCapacityWh = 20.0f,
            tempCelsius = 25.0f
        )
        val onEst = tripleNoProfile.screenOnEstimate
        assertNotNull(onEst.remainingMs)
        assertNotNull(onEst.minRemainingMs)
        assertNotNull(onEst.maxRemainingMs)
        assertTrue("最小亮屏续航 <= 预测亮屏续航", onEst.minRemainingMs!! <= onEst.remainingMs!!)
        assertTrue("预测亮屏续航 <= 最大亮屏续航", onEst.remainingMs!! <= onEst.maxRemainingMs!!)

        // 2. 有 profile：传入画像中的实测重载与轻载功率
        val profile = com.battery.analysis.domain.estimator.model.RuntimeProfile(
            windowStartMs = 0L,
            windowEndMs = 0L,
            windowDays = 7,
            effectiveDischargeMs = 3600_000L,
            screenOnMs = 1800_000L,
            screenOffMs = 1800_000L,
            screenOnRatio = 0.5,
            screenOffRatio = 0.5,
            lightMs = 600_000L,
            normalMs = 600_000L,
            heavyMs = 600_000L,
            lightRatio = 0.33,
            normalRatio = 0.33,
            heavyRatio = 0.34,
            lightPowerW = 1.25,
            normalPowerW = 2.5,
            heavyPowerW = 5.0,
            typicalScreenOnPowerW = 2.5,
            offLowPowerW = 0.05,
            offHighPowerW = 0.5,
            typicalScreenOffPowerW = 0.2
        )
        val fullChargeResult = com.battery.analysis.domain.estimator.model.FullChargeRuntimeResult(
            fullRuntimeMs = 36_000_000L,
            fullEnergyWh = 20.0,
            predictedPowerWatts = 2.0,
            confidenceLevel = com.battery.analysis.domain.estimator.model.RuntimeConfidenceLevel.HIGH,
            energySource = com.battery.analysis.domain.estimator.model.EnergySource.FCC_TIMES_NOMINAL_VOLTAGE,
            effectiveHistoryMs = 3600_000L,
            usedRegimeModel = true,
            profile = profile
        )
        val sevenDaysStatsWithProfile = SevenDaysDischargeStats(
            v2Result = fullChargeResult
        )

        val tripleWithProfile = estimator.estimateTripleRuntimes(
            overview = overview,
            sevenDaysStats = sevenDaysStatsWithProfile,
            batteryLevelPercent = 50.0f,
            totalCapacityWh = 20.0f,
            tempCelsius = 25.0f
        )
        val onProfileEst = tripleWithProfile.screenOnEstimate
        assertNotNull(onProfileEst.minRemainingMs)
        assertNotNull(onProfileEst.maxRemainingMs)
        // 剩余能量 10.0Wh，重载 5.0W -> 2小时 = 7,200,000 ms
        // 轻载 1.25W -> 8小时 = 28,800,000 ms
        assertEquals(7_200_000L, onProfileEst.minRemainingMs!!)
        assertEquals(28_800_000L, onProfileEst.maxRemainingMs!!)
    }

    /**
     * 测试当数据不足导致主剩余续航为 null 时，minRemainingMs 与 maxRemainingMs 同步为 null。
     * 验证绝不在主续航为 null 时泄露虚假范围区间，确保 UI 展示与底层数据一致性。
     */
    @Test
    fun testRemainingMsNull_BoundsMustAlsoBeNull() {
        val emptyEstimator = PowerEstimator()

        // 1. 亮屏估计器功率为 null
        val onResult = estimator.estimateScreenOn(
            usableEnergyWh = 10.0,
            onPowerEstimator = emptyEstimator
        )
        assertNull("亮屏主续航必须为 null", onResult.remainingMs)
        assertNull("亮屏最小续航必须同步为 null", onResult.minRemainingMs)
        assertNull("亮屏最大续航必须同步为 null", onResult.maxRemainingMs)

        // 2. 息屏估计器功率为 null
        val offResult = estimator.estimateScreenOff(
            usableEnergyWh = 10.0,
            offPowerEstimator = emptyEstimator
        )
        assertNull("息屏主续航必须为 null", offResult.remainingMs)
        assertNull("息屏最小续航必须同步为 null", offResult.minRemainingMs)
        assertNull("息屏最大续航必须同步为 null", offResult.maxRemainingMs)

        // 3. 全局估计数据不足
        val globalResult = estimator.estimateGlobal(
            usableEnergyWh = 10.0,
            screenOnRatio = null,
            onPowerEstimator = emptyEstimator,
            offPowerEstimator = emptyEstimator,
            fallbackGlobalEstimator = null
        )
        assertNull("全局主续航必须为 null", globalResult.remainingMs)
        assertNull("全局最小续航必须同步为 null", globalResult.minRemainingMs)
        assertNull("全局最大续航必须同步为 null", globalResult.maxRemainingMs)
    }

    /**
     * 测试全局续航降级至单项或全局直接估计器时，置信度与样本数量动态继承自真实被选中的估计器。
     * 验证彻底消除 0.40 硬编码虚构置信度与 0 样本数漏洞。
     */
    @Test
    fun testEstimateGlobalFallback_UsesSelectedEstimatorConfidenceAndSampleCount() {
        val onEstimator = PowerEstimator()
        // 注入亮屏稳态样本，产生明确的置信度与样本数
        onEstimator.seedCurrentSession(durationMs = 1_800_000L, powerWatts = 3.0f)
        val expectedConfidence = onEstimator.getConfidence()

        val emptyOffEstimator = PowerEstimator()

        // 细分场景不全（缺乏有效 off 数据与 ratio），降级至 onEstimator
        val fallbackResult = estimator.estimateGlobal(
            usableEnergyWh = 15.0,
            screenOnRatio = null,
            onPowerEstimator = onEstimator,
            offPowerEstimator = emptyOffEstimator,
            fallbackGlobalEstimator = null,
            batteryLevelPercent = 50.0f
        )

        assertNotNull("降级主续航有效", fallbackResult.remainingMs)
        assertEquals(3.0, fallbackResult.predictedPowerWatts!!, 0.001)
        assertEquals("置信度必须真实继承自被选中的 onEstimator，绝非硬编码 0.40", expectedConfidence, fallbackResult.confidence, 0.001)
        assertEquals("样本数必须继承自被选中的 onEstimator", onEstimator.sampleCount, fallbackResult.sampleCount)
    }

    /**
     * 测试 estimateTripleRuntimes 优先对接 FullChargeRuntimeResult 的 v2 高阶预测功耗作为 Long 尺度基准。
     * 验证两套模型在物理基准功率上的科学协同与一致性。
     */
    @Test
    fun testEstimateTripleRuntimes_PrefersV2PredictedPowerAsLongBaseline() {
        // v2 高阶模型测算的科学预测功耗（例如亮屏 2.2W，息屏 0.15W，全局 1.1W）
        val v2Result = com.battery.analysis.domain.estimator.model.FullChargeRuntimeResult(
            fullRuntimeMs = 40_000_000L,
            fullEnergyWh = 20.0,
            predictedPowerWatts = 1.1,
            predictedScreenOnPowerWatts = 2.2,
            predictedScreenOffPowerWatts = 0.15,
            confidenceLevel = com.battery.analysis.domain.estimator.model.RuntimeConfidenceLevel.HIGH,
            energySource = com.battery.analysis.domain.estimator.model.EnergySource.FCC_TIMES_NOMINAL_VOLTAGE,
            effectiveHistoryMs = 7 * 86400 * 1000L,
            usedRegimeModel = true
        )

        // 故意设置不同的线性统计放电速率（若未优先对接 v2，则会算出不同的线性粗略值）
        val sevenDays = SevenDaysDischargeStats(
            screenOnDischargeRatePercentPerHour = 20.0f, // 20% * 20Wh = 4.0W（粗暴线性算法）
            screenOffDischargeRatePercentPerHour = 2.0f,
            globalDischargeRatePercentPerHour = 10.0f,
            v2Result = v2Result
        )

        // 会话数据为空，纯依赖 Long 尺度历史基线
        val result = estimator.estimateTripleRuntimes(
            overview = null,
            sevenDaysStats = sevenDays,
            batteryLevelPercent = 50.0f,
            totalCapacityWh = 20.0f,
            tempCelsius = 25.0f
        )

        assertEquals("亮屏 Long 尺度基线必须优先采纳 v2Result 高阶建模功率 2.2W", 2.2, result.screenOnEstimate.predictedPowerWatts!!, 0.001)
        assertEquals("息屏 Long 尺度基线必须优先采纳 v2Result 高阶建模功率 0.15W", 0.15, result.screenOffEstimate.predictedPowerWatts!!, 0.001)
    }

    /**
     * 测试从 v2Result.profile.screenOnRatio 优先提取占空比并推算全局综合续航。
     * 验证当历史简单统计 sceneScreenOnRatio 为 null 时，算法依然能精准融合亮屏与待机模型，
     * 杜绝退化为单项亮屏功耗的断层缺陷。
     */
    @Test
    fun testProfileScreenOnRatioPriorityAndGlobalComposite() {
        val profile = com.battery.analysis.domain.estimator.model.RuntimeProfile(
            windowStartMs = 1700000000000L,
            windowEndMs = 1700604800000L,
            windowDays = 7,
            effectiveDischargeMs = 7 * 86400 * 1000L,
            screenOnMs = 2 * 86400 * 1000L,
            screenOffMs = 5 * 86400 * 1000L,
            screenOnRatio = 0.25, // 25% 亮屏，75% 息屏
            screenOffRatio = 0.75,
            lightMs = 10000L, normalMs = 10000L, heavyMs = 10000L,
            lightRatio = 0.3, normalRatio = 0.4, heavyRatio = 0.3,
            lightPowerW = 1.5, normalPowerW = 2.5, heavyPowerW = 4.5,
            typicalScreenOnPowerW = 2.8,
            offLowPowerW = 0.10, offHighPowerW = 0.30,
            typicalScreenOffPowerW = 0.15
        )

        val v2Result = com.battery.analysis.domain.estimator.model.FullChargeRuntimeResult(
            fullRuntimeMs = 36_000_000L,
            fullEnergyWh = 20.0,
            predictedPowerWatts = 0.8125,
            predictedScreenOnPowerWatts = 2.8,
            predictedScreenOffPowerWatts = 0.15,
            modelVersion = com.battery.analysis.domain.estimator.model.RuntimeModelVersion.FULL_RUNTIME_V2_2,
            confidenceLevel = com.battery.analysis.domain.estimator.model.RuntimeConfidenceLevel.HIGH,
            energySource = com.battery.analysis.domain.estimator.model.EnergySource.FCC_TIMES_NOMINAL_VOLTAGE,
            effectiveHistoryMs = 7 * 86400 * 1000L,
            usedRegimeModel = true,
            profile = profile
        )

        // sceneScreenOnRatio 故意为 null
        val sevenDays = SevenDaysDischargeStats(
            screenOnDischargeRatePercentPerHour = null,
            screenOffDischargeRatePercentPerHour = null,
            globalDischargeRatePercentPerHour = null,
            sceneScreenOnRatio = null,
            v2Result = v2Result
        )

        val overview = PowerOverviewStats(
            avgPowerWatts = 0.81f,
            screenOnPowerWatts = 2.8f,
            screenOffPowerWatts = 0.15f,
            screenOnDurationMs = 1_800_000L,
            screenOffDurationMs = 5_400_000L,
            totalDurationMs = 7_200_000L
        )

        val result = estimator.estimateTripleRuntimes(
            overview = overview,
            sevenDaysStats = sevenDays,
            batteryLevelPercent = 50.0f,
            totalCapacityWh = 20.0f,
            tempCelsius = 25.0f
        )

        // 验证全局预测采用复合场景融合：0.25 * 2.8 + 0.75 * 0.15 = 0.70 + 0.1125 = 0.8125W
        assertEquals("全局预测模型类型应为复合融合模型", RuntimeModelType.GLOBAL_COMPOSITE, result.globalEstimate.modelType)
        assertEquals("全局功耗应基于 profile 占空比精确加权", 0.8125, result.globalEstimate.predictedPowerWatts!!, 0.01)
        assertNotNull("全局综合续航绝不能为 null", result.globalEstimate.remainingMs)
    }

    /**
     * 测试工况预期范围的严谨物理行为：
     * 1. 当无大样本工况实测画像时，基于当前预测功率自适应推导动态包络（min <= normal <= max）；
     * 2. 当提供真实工况画像（如 heavyPowerW = 5.0W, lightPowerW = 2.0W）时，精准按画像计算上下限区间。
     */
    @Test
    fun testZeroFakeDataEnvelopeBounds() {
        val onEstimator = PowerEstimator()
        onEstimator.seedCurrentSession(1_800_000L, 2.5f) // 亮屏 2.5W

        // 1. 无大样本画像时：基于预测放电功率推导自适应动态包络
        val noProfileResult = estimator.estimateScreenOn(
            usableEnergyWh = 10.0,
            onPowerEstimator = onEstimator,
            batteryLevelPercent = 50.0f,
            heavyPowerW = null,
            lightPowerW = null
        )
        assertNotNull("主预测续航必须真实有效", noProfileResult.remainingMs)
        assertNotNull("无大样本画像时应具备动态自适应下限", noProfileResult.minRemainingMs)
        assertNotNull("无大样本画像时应具备动态自适应上限", noProfileResult.maxRemainingMs)
        assertTrue("下限 <= 主预测 <= 上限",
            noProfileResult.minRemainingMs!! <= noProfileResult.remainingMs!! &&
            noProfileResult.remainingMs!! <= noProfileResult.maxRemainingMs!!
        )

        // 2. 具备真实实测工况画像时：根据实测功率推导区间
        // usableEnergyWh = 10.0, heavy = 5.0W -> min = 2h = 7,200,000 ms
        // light = 2.0W -> max = 5h = 18,000,000 ms
        val withProfileResult = estimator.estimateScreenOn(
            usableEnergyWh = 10.0,
            onPowerEstimator = onEstimator,
            batteryLevelPercent = 50.0f,
            heavyPowerW = 5.0,
            lightPowerW = 2.0
        )
        assertNotNull("具备实测工况时 minRemainingMs 应有效", withProfileResult.minRemainingMs)
        assertNotNull("具备实测工况时 maxRemainingMs 应有效", withProfileResult.maxRemainingMs)
        assertEquals(7_200_000L, withProfileResult.minRemainingMs!!)
        assertEquals(18_000_000L, withProfileResult.maxRemainingMs!!)
        assertTrue("min 应小于等于主预测，max 应大于等于主预测",
            withProfileResult.minRemainingMs!! <= withProfileResult.remainingMs!! &&
            withProfileResult.maxRemainingMs!! >= withProfileResult.remainingMs!!
        )
    }

    /**
     * 测试大样本会话下剩余续航预测的物理稳健性：长程稳态物理均值（如 2.0W）占主导，
     * 绝不因瞬间离线采样点噪声而发生续航腰斩或每次刷新剧烈跳动，保证与卡片放电速率高度物理自洽。
     */
    @Test
    fun testSessionSteadyStateDominanceAndNoiseImmunity() {
        val overview = PowerOverviewStats(
            avgPowerWatts = 1.0f,
            screenOnPowerWatts = 2.0f,
            screenOffPowerWatts = 0.2f,
            screenOnDurationMs = 3_600_000L,
            totalDurationMs = 7_200_000L
        )

        val result = estimator.estimateTripleRuntimes(
            overview = overview,
            sevenDaysStats = null,
            batteryLevelPercent = 50.0f,
            totalCapacityWh = 20.0f,
            tempCelsius = 25.0f
        )

        // 剩余可用能量 10Wh / 2W = 5 小时 = 18,000,000 ms
        assertNotNull("亮屏预测续航有效", result.screenOnEstimate.remainingMs)
        assertEquals("亮屏预测续航应严格由长程稳态均值主导 (10Wh / 2.0W = 5h)", 18_000_000L, result.screenOnEstimate.remainingMs!!)
        assertEquals("亮屏预测功率应忠实反映会话物理功率 2.0W", 2.0, result.screenOnEstimate.predictedPowerWatts!!, 0.05)
    }

    /**
     * 测试 PowerEstimator 在线流式接收采样点时的多时间尺度平滑与工况突变识别。
     */
    @Test
    fun testPowerEstimatorStreamingUpdate() {
        val streamEstimator = PowerEstimator()
        streamEstimator.seedCurrentSession(3_600_000L, 2.0f)
        val initialPower = streamEstimator.getPredictedPower()
        assertNotNull(initialPower)
        assertEquals(2.0, initialPower!!, 0.01)

        // 模拟在线流式连续接收高负载采样
        val now = System.currentTimeMillis()
        for (i in 1..5) {
            streamEstimator.update(
                com.battery.analysis.domain.estimator.model.PowerSample(
                    timestampMs = now + i * 10_000L,
                    voltageVolts = 3.8f,
                    currentAmperes = null,
                    powerWatts = 5.0f,
                    batteryLevelPercent = 50f,
                    state = com.battery.analysis.domain.estimator.model.ScreenPowerState.SCREEN_ON
                )
            )
        }
        val updatedPower = streamEstimator.getPredictedPower()
        assertNotNull(updatedPower)
        assertTrue("在线流式持续输入高负载后预测功率应逐步上升", updatedPower!! > initialPower)
    }

    /**
     * 测试 estimateTripleRuntimes 入口在传入当前周期细粒度采样点集合时，
     * 能够成功激活各维度的 Fast 尺度，感知高负载工况并自适应修正预测功耗与剩余续航。
     */
    @Test
    fun testEstimateTripleRuntimesWithCurrentTrendPoints() {
        val overview = PowerOverviewStats(
            avgPowerWatts = 1.8f,
            screenOnPowerWatts = 2.0f,
            screenOffPowerWatts = 0.2f,
            screenOnDurationMs = 1_800_000L,
            screenOffDurationMs = 3_600_000L,
            totalDurationMs = 5_400_000L
        )

        val now = 10_000_000L
        // 模拟最近出现的游戏高负载采样点（5.0W 持续亮屏）
        val trendPoints = (0 until 5).map { i ->
            com.battery.analysis.model.PowerDischargePoint(
                timestamp = now + i * 10_000L,
                elapsedHours = 1.5f,
                batteryLevel = 50,
                voltageVolts = 3.85f,
                powerWatts = 5.0f,
                isScreenOn = true
            )
        }

        // 1. 无采样点 baseline：亮屏仅依靠宏观均值 2.0W
        val baseResult = estimator.estimateTripleRuntimes(
            overview = overview,
            sevenDaysStats = null,
            batteryLevelPercent = 50f,
            remainingEnergyWh = 10.0f,
            totalCapacityWh = 20.0f,
            currentTrendPoints = null
        )

        // 重置平滑器时序记忆，隔离测试两组独立输入下的直接推算结果
        com.battery.analysis.domain.estimator.EnduranceRuntimeSmoother.getInstance().reset()

        // 2. 带细粒度采样点：应灵敏响应近期的 5.0W 高负载
        val trendResult = estimator.estimateTripleRuntimes(
            overview = overview,
            sevenDaysStats = null,
            batteryLevelPercent = 50f,
            remainingEnergyWh = 10.0f,
            totalCapacityWh = 20.0f,
            currentTrendPoints = trendPoints
        )

        assertNotNull(baseResult.screenOnEstimate.predictedPowerWatts)
        assertNotNull(trendResult.screenOnEstimate.predictedPowerWatts)
        assertTrue(
            "接入近期高负载细粒度点集后，亮屏预测放电功率应敏捷升高 (基础: ${baseResult.screenOnEstimate.predictedPowerWatts}, 接入点集后: ${trendResult.screenOnEstimate.predictedPowerWatts})",
            trendResult.screenOnEstimate.predictedPowerWatts!! > baseResult.screenOnEstimate.predictedPowerWatts!!
        )

        assertNotNull(baseResult.screenOnEstimate.remainingMs)
        assertNotNull(trendResult.screenOnEstimate.remainingMs)
        assertTrue(
            "功耗升高后预测剩余可用时间应相应缩减 (基础: ${baseResult.screenOnEstimate.remainingMs}, 接入点集后: ${trendResult.screenOnEstimate.remainingMs})",
            trendResult.screenOnEstimate.remainingMs!! < baseResult.screenOnEstimate.remainingMs!!
        )
    }

    /**
     * 验证真机截图真实工况下的物理自洽性与预测高精度：
     * 对应用户真机工况（剩余 51%，可用 15.18Wh，当前拔电 4h43m）：
     * - 实测亮屏：1.56W (03h59m / 84.8%)
     * - 实测息屏：0.63W (43m06s / 15.2%)
     * - 实测全局：1.42W (04h43m)
     * - 7天大样本画像：典型亮屏 1.80W，典型待机 0.57W（休眠0.33W 65.2%，唤醒1.02W 34.8%），典型全局 1.25W，作息亮屏比 53.6%
     *
     * 严格断言：
     * 1. 息屏预测待机功耗自然收敛在 0.57W ~ 0.63W 稳态区间（坚决杜绝误算为 1.22W），待机续航恢复至 24h ~ 26h；
     * 2. 全局综合功耗自然收敛在 1.25W ~ 1.42W（坚决杜绝 1.64W 倒挂），综合续航恢复至 10h ~ 12h；
     * 3. 严格满足真实物理单调性：P_off < P_global < P_on，且 T_on < T_global < T_off。
     */
    @Test
    fun testRealDeviceScreenshotScenarioPhysicalPrecision() {
        val profile = com.battery.analysis.domain.estimator.model.RuntimeProfile(
            windowStartMs = 1700000000000L,
            windowEndMs = 1700604800000L,
            windowDays = 7,
            effectiveDischargeMs = 7 * 86400 * 1000L,
            screenOnMs = (7 * 86400 * 1000L * 0.536).toLong(),
            screenOffMs = (7 * 86400 * 1000L * 0.464).toLong(),
            screenOnRatio = 0.536,
            screenOffRatio = 0.464,
            lightMs = 10000L, normalMs = 10000L, heavyMs = 10000L,
            lightRatio = 0.3, normalRatio = 0.4, heavyRatio = 0.3,
            lightPowerW = 1.00, normalPowerW = 1.80, heavyPowerW = 2.80,
            typicalScreenOnPowerW = 1.80,
            offLowPowerW = 0.33, offHighPowerW = 1.02,
            typicalScreenOffPowerW = 0.57
        )
        val v2Result = com.battery.analysis.domain.estimator.model.FullChargeRuntimeResult(
            fullRuntimeMs = 24 * 3600_000L,
            fullEnergyWh = 29.76,
            predictedPowerWatts = 1.25,
            predictedScreenOnPowerWatts = 1.80,
            predictedScreenOffPowerWatts = 0.57,
            modelVersion = com.battery.analysis.domain.estimator.model.RuntimeModelVersion.FULL_RUNTIME_V2_2,
            confidenceLevel = com.battery.analysis.domain.estimator.model.RuntimeConfidenceLevel.HIGH,
            energySource = com.battery.analysis.domain.estimator.model.EnergySource.FCC_TIMES_NOMINAL_VOLTAGE,
            effectiveHistoryMs = 7 * 86400 * 1000L,
            usedRegimeModel = true,
            profile = profile
        )
        val sevenDays = SevenDaysDischargeStats(
            screenOnDischargeRatePercentPerHour = null,
            screenOffDischargeRatePercentPerHour = 1.6f,
            globalDischargeRatePercentPerHour = 3.8f,
            sceneScreenOnRatio = 0.536f,
            v2Result = v2Result
        )

        // 当前拔电放电会话物理做功真实实测统计
        val overview = PowerOverviewStats(
            avgPowerWatts = 1.42f,
            screenOnPowerWatts = 1.56f,
            screenOffPowerWatts = 0.63f,
            screenOnDurationMs = 14_340_000L, // 03h59m
            screenOffDurationMs = 2_586_000L,  // 43m06s
            totalDurationMs = 16_980_000L     // 04h43m
        )

        val result = estimator.estimateTripleRuntimes(
            overview = overview,
            sevenDaysStats = sevenDays,
            batteryLevelPercent = 51.0f,
            remainingEnergyWh = 15.18f,
            totalCapacityWh = 29.76f,
            tempCelsius = 28.0f
        )

        val pOn = result.screenOnEstimate.predictedPowerWatts
        val pOff = result.screenOffEstimate.predictedPowerWatts
        val pGlobal = result.globalEstimate.predictedPowerWatts

        val tOn = result.screenOnEstimate.remainingMs
        val tOff = result.screenOffEstimate.remainingMs
        val tGlobal = result.globalEstimate.remainingMs

        assertNotNull("亮屏预测功耗非空", pOn)
        assertNotNull("息屏预测功耗非空", pOff)
        assertNotNull("全局综合预测功耗非空", pGlobal)

        assertNotNull("亮屏预测续航非空", tOn)
        assertNotNull("息屏预测续航非空", tOff)
        assertNotNull("全局综合预测续航非空", tGlobal)

        // 1. 息屏待机功耗断言：自然处于 7 天典型 0.57W 与当前会话实测 0.63W 稳态区间
        assertTrue(
            "息屏待机功耗应在 0.57W ~ 0.63W 稳态区间内，绝不能虚高到 1.22W！实测为: $pOff W",
            pOff!! in 0.56..0.64
        )
        val offHours = tOff!! / 3600_000.0
        assertTrue(
            "息屏待机理论续航应达到 23h ~ 27h 左右（51%电量在1.6%/h待机下正常续航），实测为: ${offHours}h",
            offHours in 23.0..27.0
        )

        // 2. 全局综合功耗断言：在 24 小时大周期阻尼作用下，平稳自然处于 1.10W ~ 1.45W 区间，绝不被短时高负载拉抬超越实测！
        assertTrue(
            "全局综合功耗应在 1.10W ~ 1.45W 之间，实测为: $pGlobal W",
            pGlobal!! in 1.10..1.45
        )
        val globalHours = tGlobal!! / 3600_000.0
        assertTrue(
            "全局综合续航应在 10h ~ 14.0h 之间，实测为: ${globalHours}h",
            globalHours in 10.0..14.0
        )

        // 3. 严格物理单调性断言：息屏待机功耗 < 全局综合功耗 < 亮屏功耗
        assertTrue("物理单调性不成立：pOff ($pOff) 应严格小于 pGlobal ($pGlobal)", pOff < pGlobal)
        assertTrue("物理单调性不成立：pGlobal ($pGlobal) 应严格小于 pOn ($pOn)", pGlobal < pOn!!)

        // 续航时间单调性：持续亮屏续航 < 全局综合日常续航 < 持续息屏待机续航
        assertTrue("续航物理单调性不成立：tOn ($tOn) 应严格小于 tGlobal ($tGlobal)", tOn!! < tGlobal)
        assertTrue("续航物理单调性不成立：tGlobal ($tGlobal) 应严格小于 tOff ($tOff)", tGlobal < tOff)
    }

    /**
     * 测试全局滑动窗口在遇到夜间跨越硬件深度休眠挂起（Suspend，点间隔超过 120 秒）时，
     * 物理时长能够完整真实累加，绝不会被错误截断为 5 分钟，且做功微积分精确加权。
     */
    @Test
    fun testGlobalWindowNightDeepSleepIntegralNotTruncated() {
        val testDir = File(System.getProperty("java.io.tmpdir"), "test_global_sleep_" + System.currentTimeMillis())
        testDir.mkdirs()
        RollingDischargeBuffer.resetForTesting()
        val buffer = RollingDischargeBuffer.initForTesting(testDir)

        val baseTs = 1_700_000_000_000L
        val points = mutableListOf<PowerDischargePoint>()

        // 模拟夜间深睡场景：晚上 23:00 至次日 07:00，整整 8 小时（28,800,000 ms）
        // 期间系统处于深度休眠挂起，后台唤醒打点每 30 分钟（1,800,000 ms）唤醒一次打一个点（唤醒瞬态 1.2W，其余时间挂起）
        val intervalMs = 1_800_000L // 30 分钟
        val count = 16 // 16 * 30 min = 8 小时
        for (i in 0..count) {
            val ts = baseTs + (i * intervalMs)
            val level = 90 - (i / 4) // 电量平缓下降
            points.add(
                PowerDischargePoint(
                    timestamp = ts,
                    elapsedHours = (i * 0.5).toFloat(),
                    batteryLevel = level,
                    voltageVolts = 3.9f,
                    temperature = 25.0f,
                    powerWatts = 1.2f, // 唤醒时瞬态功耗
                    isScreenOn = false
                )
            )
        }
        buffer.appendSamplesBatch(points)

        // 提取 6 小时全局滑动窗口（目标回溯 21,600,000 ms），休眠底噪设为设备实测 0.30W
        val globalWindow = buffer.extractRollingGlobalWindow(21600_000L, baselineSleepWatts = 0.30)
        assertNotNull("全局滑动窗口应成功提取", globalWindow)

        val durMs = globalWindow!!.effectiveDurationMs
        val durHours = durMs / 3600_000.0
        // 累计时长必须达到目标 6 小时左右（绝不能因为被截断成5分钟导致累计时长只有几十分钟！）
        assertTrue("全局滑动窗口时长应达到约 6 小时，实测为: ${durHours}h", durHours in 5.8..6.5)

        val avgWatts = globalWindow.avgPowerWatts
        // 功耗应由 10s 唤醒脉冲(1.2W)与 0.30W 底噪真实加权，处于 0.30W ~ 0.33W 之间，绝不能被 1.2W 垄断或虚高！
        assertTrue("全局滑动窗口平均功率应真实收敛于休眠底噪区间(0.30W~0.33W)，实测为: $avgWatts W", avgWatts in 0.30f..0.33f)

        testDir.deleteRecursively()
    }

    /**
     * 测试滑动缓冲区在逆序扫描跨越插电充电鸿沟（两点相隔 > 1小时且电量倒挂）时，
     * 能够准确触发物理断开，绝不把插电充电的数小时计入放电时间，且总压降 totalLevelDrop 准确分段累加。
     */
    @Test
    fun testChargingGapPhysicalDisconnectInRollingBuffer() {
        val testDir = File(System.getProperty("java.io.tmpdir"), "test_charging_gap_" + System.currentTimeMillis())
        testDir.mkdirs()
        RollingDischargeBuffer.resetForTesting()
        val buffer = RollingDischargeBuffer.initForTesting(testDir)

        val baseTs = 1_700_000_000_000L
        val points = mutableListOf<PowerDischargePoint>()

        // 1. 充电前放电段：持续 30 分钟（1800 秒），电量从 40% 掉至 35%（压降 5%），平均功率 2.0W 亮屏
        for (i in 0..6) {
            points.add(
                PowerDischargePoint(
                    timestamp = baseTs + (i * 300_000L), // 每 5 分钟
                    elapsedHours = (i * 5.0 / 60.0).toFloat(),
                    batteryLevel = 40 - (i * 5 / 6),
                    voltageVolts = 3.8f,
                    temperature = 30.0f,
                    powerWatts = 2.0f,
                    isScreenOn = true
                )
            )
        }

        // 2. 插电充电过程：持续 2 小时（7,200,000 ms），电量从 35% 充到了 95%
        val afterChargingTs = baseTs + 1800_000L + 7200_000L // 充电 2 小时后

        // 3. 拔电后放电段：持续 20 分钟，电量从 95% 掉至 93%（压降 2%），平均功率 2.5W 亮屏
        for (i in 0..4) {
            points.add(
                PowerDischargePoint(
                    timestamp = afterChargingTs + (i * 300_000L), // 每 5 分钟
                    elapsedHours = ((1800_000L + 7200_000L + i * 300_000L) / 3600_000.0).toFloat(),
                    batteryLevel = 95 - (i * 2 / 4),
                    voltageVolts = 4.2f,
                    temperature = 28.0f,
                    powerWatts = 2.5f,
                    isScreenOn = true
                )
            )
        }

        buffer.appendSamplesBatch(points)

        // 回溯提取 60 分钟亮屏窗口
        val onWindow = buffer.extractRollingScreenOnWindow(3600_000L)
        assertNotNull("亮屏滑动窗口应成功提取", onWindow)

        val durMs = onWindow!!.effectiveDurationMs
        val durMinutes = durMs / 60_000.0
        // 有效放电时间应为拔电后 20 分钟 + 充电前 30 分钟 = 约 50 分钟（绝对不能包含充电的 120 分钟！）
        assertTrue("有效放电时长应为约 50 分钟，绝不能包含充电的 2 小时！实测为: ${durMinutes}分钟", durMinutes in 45.0..55.0)

        // 验证放电速率：总压降应为拔电后 2% + 充电前 5% = 7%，7% / (50/60 h) ≈ 8.4%/h
        val rate = onWindow.dischargeRatePercentPerHour
        assertNotNull("放电速率不应为 null（电量倒挂已被分段压降解决）", rate)
        assertTrue("放电速率应在 7.0%/h ~ 9.5%/h 之间，实测为: $rate %/h", rate!! in 7.0f..9.5f)

        testDir.deleteRecursively()
    }

    /**
     * 测试滑动窗口支持动态注入真实大样本画像休眠底噪基线 baselineSleepWatts，
     * 验证在不同底噪画像下做功微积分忠实反映设备硬件个性化数据。
     */
    @Test
    fun testDynamicBaselineSleepWattsIntegration() {
        val testDir = File(System.getProperty("java.io.tmpdir"), "test_sleep_profile_" + System.currentTimeMillis())
        testDir.mkdirs()
        RollingDischargeBuffer.resetForTesting()
        val buffer = RollingDischargeBuffer.initForTesting(testDir)

        val baseTs = 1_700_000_000_000L
        val points = mutableListOf<PowerDischargePoint>()
        // 模拟待机 2 小时，打点间隔 15 分钟
        for (i in 0..8) {
            points.add(
                PowerDischargePoint(
                    timestamp = baseTs + (i * 900_000L),
                    elapsedHours = (i * 0.25).toFloat(),
                    batteryLevel = 80 - (i / 4),
                    voltageVolts = 3.9f,
                    temperature = 26.0f,
                    powerWatts = 1.0f,
                    isScreenOn = false
                )
            )
        }
        buffer.appendSamplesBatch(points)

        // 1. 注入真实机型底噪 0.33W
        val window033 = buffer.extractRollingScreenOffWindow(7200_000L, baselineSleepWatts = 0.33)
        assertNotNull(window033)
        val p033 = window033!!.avgPowerWatts
        assertTrue("注入 0.33W 底噪时，加权平均功率应在 0.33W ~ 0.35W 之间，实测为: $p033 W", p033 in 0.33f..0.36f)

        // 2. 注入超低功耗机型底噪 0.10W
        val window010 = buffer.extractRollingScreenOffWindow(7200_000L, baselineSleepWatts = 0.10)
        assertNotNull(window010)
        val p010 = window010!!.avgPowerWatts
        assertTrue("注入 0.10W 底噪时，加权平均功率应在 0.10W ~ 0.13W 之间，实测为: $p010 W", p010 in 0.10f..0.13f)

        // 3. 验证动态注入的物理单调性：底噪更高的机型算出的待机均功率严格更高
        assertTrue("底噪 0.33W 算出的功率应严格高于底噪 0.10W", p033 > p010)

        testDir.deleteRecursively()
    }

    /**
     * 测试 estimateTripleRuntimes 一站式入口在连续刷新时，
     * 亮屏、息屏待机与全局综合三维度均受时序平滑器保护，挂钟时间自然扣除且工况安全包络动态合抱。
     */
    @Test
    fun testEstimateTripleRuntimes_allThreeDimensionsSmoothed_andEnvelopeSafe() {
        com.battery.analysis.domain.estimator.EnduranceRuntimeSmoother.getInstance().reset()

        val profile = com.battery.analysis.domain.estimator.model.RuntimeProfile(
            windowStartMs = 1700000000000L,
            windowEndMs = 1700604800000L,
            windowDays = 7,
            effectiveDischargeMs = 7 * 86400 * 1000L,
            screenOnMs = (7 * 86400 * 1000L * 0.20).toLong(),
            screenOffMs = (7 * 86400 * 1000L * 0.80).toLong(),
            screenOnRatio = 0.20,
            screenOffRatio = 0.80,
            lightMs = 10000L, normalMs = 10000L, heavyMs = 10000L,
            lightRatio = 0.3, normalRatio = 0.4, heavyRatio = 0.3,
            lightPowerW = 2.0, normalPowerW = 5.0, heavyPowerW = 8.0,
            typicalScreenOnPowerW = 5.0,
            offLowPowerW = 0.3, offHighPowerW = 1.0,
            typicalScreenOffPowerW = 0.5
        )
        val v2Result = com.battery.analysis.domain.estimator.model.FullChargeRuntimeResult(
            fullRuntimeMs = 40 * 3600_000L,
            screenOnFullRuntimeMs = 10 * 3600_000L,
            screenOffFullRuntimeMs = 100 * 3600_000L,
            fullEnergyWh = 25.0,
            predictedPowerWatts = 1.25,
            predictedScreenOnPowerWatts = 5.0,
            predictedScreenOffPowerWatts = 0.5,
            modelVersion = com.battery.analysis.domain.estimator.model.RuntimeModelVersion.FULL_RUNTIME_V2_2,
            confidenceLevel = com.battery.analysis.domain.estimator.model.RuntimeConfidenceLevel.HIGH,
            energySource = com.battery.analysis.domain.estimator.model.EnergySource.FCC_TIMES_NOMINAL_VOLTAGE,
            effectiveHistoryMs = 7 * 86400 * 1000L,
            usedRegimeModel = true,
            dataSufficiency = com.battery.analysis.domain.estimator.model.DataSufficiencyLevel.SUFFICIENT,
            profile = profile
        )
        val sevenDays = com.battery.analysis.model.SevenDaysDischargeStats(
            screenOnDurationMs = 3600_000L * 10,
            screenOffDurationMs = 3600_000L * 40,
            globalDurationMs = 3600_000L * 50,
            screenOnDischargeRatePercentPerHour = 10.0f,
            screenOffDischargeRatePercentPerHour = 1.0f,
            globalDischargeRatePercentPerHour = 2.5f,
            v2Result = v2Result
        )

        // 第一次调用（建立三维度基线）
        val res1 = estimator.estimateTripleRuntimes(
            overview = null,
            sevenDaysStats = sevenDays,
            batteryLevelPercent = 80.0f,
            remainingEnergyWh = 20.0f,
            totalCapacityWh = 25.0f
        )

        val on1 = res1.screenOnEstimate.remainingMs
        val off1 = res1.screenOffEstimate.remainingMs
        val global1 = res1.globalEstimate.remainingMs

        assertNotNull(on1)
        assertNotNull(off1)
        assertNotNull(global1)

        // 等待 50 毫秒挂钟时间流逝
        Thread.sleep(50)

        // 第二次调用：模拟瞬态能量微小抖动或模型目标暴涨（如 energy 从 20Wh 虚增至 25Wh）
        val res2 = estimator.estimateTripleRuntimes(
            overview = null,
            sevenDaysStats = sevenDays,
            batteryLevelPercent = 80.0f,
            remainingEnergyWh = 25.0f, // 瞬时原始模型目标大幅变大
            totalCapacityWh = 25.0f
        )

        val on2 = res2.screenOnEstimate.remainingMs
        val off2 = res2.screenOffEstimate.remainingMs
        val global2 = res2.globalEstimate.remainingMs

        assertNotNull(on2)
        assertNotNull(off2)
        assertNotNull(global2)

        // 核心断言 1：短时防抖死区内，三维度剩余续航绝不暴涨，均严格小于等于初次结果
        assertTrue("亮屏续航短时刷新严格单调递减", on2!! <= on1!!)
        assertTrue("息屏待机续航短时刷新严格单调递减", off2!! <= off1!!)
        assertTrue("全局综合续航短时刷新严格单调递减", global2!! <= global1!!)

        // 核心断言 2：三维度各自的安全工况包络 (minRemainingMs ~ maxRemainingMs) 必须严格包裹平滑结果，杜绝倒挂
        val onMin = res2.screenOnEstimate.minRemainingMs
        val onMax = res2.screenOnEstimate.maxRemainingMs
        assertNotNull(onMin)
        assertNotNull(onMax)
        assertTrue("亮屏包络下限包裹", onMin!! <= on2)
        assertTrue("亮屏包络上限包裹", onMax!! >= on2)

        val offMin = res2.screenOffEstimate.minRemainingMs
        val offMax = res2.screenOffEstimate.maxRemainingMs
        assertNotNull(offMin)
        assertNotNull(offMax)
        assertTrue("息屏包络下限包裹", offMin!! <= off2)
        assertTrue("息屏包络上限包裹", offMax!! >= off2)

        val gMin = res2.globalEstimate.minRemainingMs
        val gMax = res2.globalEstimate.maxRemainingMs
        assertNotNull(gMin)
        assertNotNull(gMax)
        assertTrue("全局包络下限包裹", gMin!! <= global2)
        assertTrue("全局包络上限包裹", gMax!! >= global2)
    }
}


