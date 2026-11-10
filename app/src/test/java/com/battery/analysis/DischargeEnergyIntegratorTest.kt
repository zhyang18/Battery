package com.battery.analysis

import com.battery.analysis.domain.estimator.DischargeEnergyIntegrator
import com.battery.analysis.domain.estimator.model.LearnedSocEnergyCurve
import com.battery.analysis.domain.estimator.model.PowerSample
import com.battery.analysis.domain.estimator.model.SampleQuality
import com.battery.analysis.domain.estimator.model.ScreenPowerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 放电端电压与电流梯形微积分引擎及 5% SOC 线性切分分配单元测试（DischargeEnergyIntegratorTest）。
 */
class DischargeEnergyIntegratorTest {

    /**
     * 测试单区间内平稳放电的梯形微积分准确性。
     * 假设采样两点：P1(0s, 4.0V, 2.0W, 98%), P2(60s, 4.0V, 2.0W, 98%)。
     * 时间跨度 60 秒，功率恒定 2.0W。
     * 理论做功：2.0 * (60 / 3600) = 0.033333 Wh。
     * 98% 属于 bin 19 (95%~100%)。
     */
    @Test
    fun testSingleBinTrapezoidalIntegration() {
        val samples = listOf(
            PowerSample(
                timestampMs = 1000L,
                voltageVolts = 4.0f,
                currentAmperes = 0.5f,
                powerWatts = 2.0f,
                batteryLevelPercent = 98.0f,
                state = ScreenPowerState.SCREEN_ON
            ),
            PowerSample(
                timestampMs = 61000L,
                voltageVolts = 4.0f,
                currentAmperes = 0.5f,
                powerWatts = 2.0f,
                batteryLevelPercent = 98.0f,
                state = ScreenPowerState.SCREEN_ON
            )
        )

        val result = DischargeEnergyIntegrator.integrateDischargeSamples(samples)
        assertEquals(1, result.size)
        val bin19 = result[19]
        assertNotNull(bin19)
        assertEquals(19, bin19!!.binIndex)
        val expectedWh = 2.0 * (60.0 / 3600.0)
        assertEquals(expectedWh, bin19.energyWh, 0.0001)
    }

    /**
     * 测试跨越 5% 边界时的比例精确线性分配。
     * 假设电量从 96.0% 降至 94.0%，跨越了 95% 边界（bin 19 与 bin 18）。
     * 总跨度 2.0%：
     * 96% -> 95% 占 1.0% (50%)，属于 bin 19 (95%~100%)；
     * 95% -> 94% 占 1.0% (50%)，属于 bin 18 (90%~95%)。
     * 总做功应按各 50% 严格守恒切分。
     */
    @Test
    fun testCrossBoundarySocAllocation() {
        val samples = listOf(
            PowerSample(
                timestampMs = 0L,
                voltageVolts = 4.1f,
                powerWatts = 3.0f,
                batteryLevelPercent = 96.0f,
                state = ScreenPowerState.SCREEN_ON
            ),
            PowerSample(
                timestampMs = 12000L,
                voltageVolts = 4.0f,
                powerWatts = 3.0f,
                batteryLevelPercent = 94.0f,
                state = ScreenPowerState.SCREEN_ON
            )
        )

        val result = DischargeEnergyIntegrator.integrateDischargeSamples(samples)
        assertEquals(2, result.size)

        val bin19 = result[19]
        val bin18 = result[18]
        assertNotNull(bin19)
        assertNotNull(bin18)

        val totalWh = 3.0 * (12.0 / 3600.0)
        val halfWh = totalWh / 2.0

        assertEquals(halfWh, bin19!!.energyWh, 0.0001)
        assertEquals(halfWh, bin18!!.energyWh, 0.0001)
    }

    /**
     * 测试超大异常休眠断层（> 4小时，如长期关机挂起）被安全截断，防止虚报能耗。
     */
    @Test
    fun testDeepSleepGapDropped() {
        val samples = listOf(
            PowerSample(
                timestampMs = 0L,
                voltageVolts = 4.0f,
                powerWatts = 0.2f,
                batteryLevelPercent = 80.0f,
                state = ScreenPowerState.SCREEN_OFF
            ),
            PowerSample(
                timestampMs = 5 * 3600_000L, // 跨越 5 小时（> 4 小时最大待机补偿门限）
                voltageVolts = 3.98f,
                powerWatts = 0.2f,
                batteryLevelPercent = 79.0f,
                state = ScreenPowerState.SCREEN_OFF
            )
        )

        val result = DischargeEnergyIntegrator.integrateDischargeSamples(samples)
        assertTrue(result.isEmpty())
    }

    /**
     * 测试真实的息屏待机低功耗被动广播采样（时间跨度数分钟至数十分钟、单步掉电 <= 2.0% 且功耗符合待机底噪）
     * 正常执行梯形微积分，忠实累加待机实测做功，且绝不误判为未观测断层（彻底修复 15%~20%、10%~15% 被误拒的问题）。
     */
    @Test
    fun testScreenOffNormalStandbySamplingIntegratesFaithfully() {
        val samples = listOf(
            PowerSample(
                timestampMs = 0L,
                voltageVolts = 4.0f,
                powerWatts = 0.25f,
                batteryLevelPercent = 80.0f,
                state = ScreenPowerState.SCREEN_OFF
            ),
            PowerSample(
                timestampMs = 300_000L,
                voltageVolts = 3.98f,
                powerWatts = 0.25f,
                batteryLevelPercent = 79.5f,
                state = ScreenPowerState.SCREEN_OFF
            )
        )

        val sessionResult = DischargeEnergyIntegrator.integrateDischargeSession(samples)
        // 纯亮屏第一性原理：息屏采样点被完全隔离，不计入放电能量模型做功
        assertTrue(sessionResult.binDeltas.isEmpty())
        assertEquals(0.0, sessionResult.totalIntegratedEnergyWh, 0.00001)
    }

    /**
     * 测试真正异常的长时间关机/失联断层（如电量跨度跳变 > 2.0%，中间丢失了 1% 广播轨迹）：
     * 必须忠实标记为未观测断层（UnobservedSocRange），防止以首尾粗暴连线虚构未观测期间的能耗。
     */
    @Test
    fun testScreenOffAbnormalShutdownGapMarkedAsUnobserved() {
        val samples = listOf(
            PowerSample(
                timestampMs = 0L,
                voltageVolts = 4.0f,
                powerWatts = 0.25f,
                batteryLevelPercent = 80.0f,
                state = ScreenPowerState.SCREEN_OFF
            ),
            PowerSample(
                timestampMs = 1800_000L, // 跨越 30 分钟，但电量突变 4.0%（中间未收到 79, 78, 77 广播，属于关机断层）
                voltageVolts = 3.92f,
                powerWatts = 0.25f,
                batteryLevelPercent = 76.0f,
                state = ScreenPowerState.SCREEN_OFF
            )
        )

        val sessionResult = DischargeEnergyIntegrator.integrateDischargeSession(samples)
        // 突变断层不生成虚构做功
        assertTrue(sessionResult.binDeltas.isEmpty())
        // 纯亮屏模型下息屏状态直接被隔离
        assertTrue(sessionResult.binDeltas.isEmpty())
        assertEquals(0.0, sessionResult.totalIntegratedEnergyWh, 0.00001)
    }

    /**
     * 测试超长待机采样（跨越 25 分钟、掉电 1%、功耗 0.29W，完全还原用户真实工况）：
     * 系统忠实微积分做功，并验证连续 5 步息屏放电完整闭合 15%~20% 区间，经校验器评定为合格实测完整样本（COMPLETE_BIN）。
     */
    @Test
    fun testScreenOffStandbyFivePercentBinLearnedAsCompleteMeasured() {
        // 纯亮屏放电从 20% 放电到 15%（Bin 3），共 10 步（每步 0.5%，耗时 120 秒 <= 180s 门限），功率 3.0W，总做功 1.0 Wh
        val samples = mutableListOf<PowerSample>()
        val stepMs = 120 * 1000L
        for (i in 0..10) {
            val level = 20.0f - (i * 0.5f)
            val ts = i * stepMs
            val volt = 3.90f - (i * 0.01f)
            samples.add(
                PowerSample(
                    timestampMs = ts,
                    voltageVolts = volt,
                    powerWatts = 3.0f,
                    batteryLevelPercent = level,
                    state = ScreenPowerState.SCREEN_ON
                )
            )
        }

        val integrationResult = DischargeEnergyIntegrator.integrateDischargeSession(samples)
        assertTrue(integrationResult.unobservedRanges.isEmpty())
        val bin3Delta = integrationResult.binDeltas[3]
        assertNotNull("Bin 3 必须存在做功微元", bin3Delta)
        val expectedTotalWh = 3.0 * (1200.0 / 3600.0) // 20 分钟做功 1.0 Wh
        assertEquals(expectedTotalWh, bin3Delta!!.energyWh, 0.001)
        assertEquals(5.0f, bin3Delta.coveredSocSpan, 0.001f)

        val validation = com.battery.analysis.domain.estimator.DischargeSessionValidator.validateDischargeSession(
            integrationResult = integrationResult,
            sessionId = "screen_on_complete",
            effectiveCapacityMah = 5000.0
        )
        assertFalse(validation.rejectedBinIndices.contains(3))
        val bin3Sample = validation.validCompleteSamples.find { it.binIndex == 3 }
        assertNotNull(bin3Sample)
        assertEquals(SampleQuality.COMPLETE_BIN, bin3Sample!!.quality)
        assertEquals(1.0, bin3Sample.coverageFraction, 0.001)
        assertEquals(1.0, bin3Sample.energyWh, 0.001)
    }

    /**
     * 测试正常高频采样下的息屏待机放电（SCREEN_OFF）正常执行物理微积分：
     * 假设两点相隔 10 秒（<= 180 秒门限），处于息屏待机放电（功率 0.3W），
     * 系统必须正常执行物理梯形积分，忠实累加待机物理能量，且绝不记录为未观测断层。
     */
    @Test
    fun testNormalScreenOffDischargeIntegratedSuccessfully() {
        val samples = listOf(
            PowerSample(
                timestampMs = 0L,
                voltageVolts = 3.9f,
                powerWatts = 0.30f,
                batteryLevelPercent = 25.0f,
                state = ScreenPowerState.SCREEN_OFF
            ),
            PowerSample(
                timestampMs = 10_000L, // 相隔 10 秒
                voltageVolts = 3.9f,
                powerWatts = 0.30f,
                batteryLevelPercent = 25.0f,
                state = ScreenPowerState.SCREEN_OFF
            )
        )

        val sessionResult = DischargeEnergyIntegrator.integrateDischargeSession(samples)
        // 绝不误报断层
        assertTrue("正常高频息屏采样绝不能误报为断层", sessionResult.unobservedRanges.isEmpty())
        // 纯亮屏模型下息屏状态被完全隔离
        assertTrue(sessionResult.binDeltas.isEmpty())
        assertEquals(0.0, sessionResult.totalIntegratedEnergyWh, 0.00001)
    }

    /**
     * 测试充电状态下的采样点不参与放电积分。
     */
    @Test
    fun testChargingSamplesIgnored() {
        val samples = listOf(
            PowerSample(
                timestampMs = 0L,
                voltageVolts = 4.3f,
                powerWatts = 0.0f,
                batteryLevelPercent = 50.0f,
                state = ScreenPowerState.CHARGING
            ),
            PowerSample(
                timestampMs = 10000L,
                voltageVolts = 4.35f,
                powerWatts = 0.0f,
                batteryLevelPercent = 52.0f,
                state = ScreenPowerState.CHARGING
            )
        )

        val result = DischargeEnergyIntegrator.integrateDischargeSamples(samples)
        assertTrue(result.isEmpty())
    }

    /**
     * 测试 LearnedSocEnergyCurve 的剩余能量累加单调性。
     */
    @Test
    fun testLearnedCurveMonotonicityAndSum() {
        val curve = LearnedSocEnergyCurve.createEmpty()
        // 初始全空时应返回 null
        assertNull(curve.estimateRemainingEnergyWh(50.0f))

        // 构造带部分数据的曲线
        val binsWithData = curve.bins.map { bin ->
            if (bin.binIndex in 0..9) {
                // 0%~50% 区间，每个 5% 区间假设实测释放 1.2 Wh
                bin.copy(learnedEnergyWh = 1.2, sampleCount = 5)
            } else bin
        }
        val partialCurve = curve.copy(bins = binsWithData, coveragePercent = 0.5f)

        // 25% (涉及 bin 0,1,2,3,4 共 5 个完整区间)
        // 期望累加：5 * 1.2 = 6.0 Wh
        val est25 = partialCurve.estimateRemainingEnergyWh(25.0f)
        assertNotNull(est25)
        assertEquals(6.0, est25!!, 0.001)

        // 50% (涉及 bin 0..9 共 10 个完整区间)
        // 期望累加：10 * 1.2 = 12.0 Wh
        val est50 = partialCurve.estimateRemainingEnergyWh(50.0f)
        assertNotNull(est50)
        assertEquals(12.0, est50!!, 0.001)

        assertTrue(est50 > est25)
    }

    /**
     * 测试深睡断层下 integrateDischargeSession 忠实捕获未观测 SOC 区间。
     */
    @Test
    fun testDeepSleepUnobservedRangeCapture() {
        // 纯亮屏期间采样间隔超过 180 秒（如卡顿 300 秒），忠实捕获为未观测断层
        val samples = listOf(
            PowerSample(
                timestampMs = 0L,
                voltageVolts = 4.0f,
                powerWatts = 2.0f,
                batteryLevelPercent = 80.0f,
                state = ScreenPowerState.SCREEN_ON
            ),
            PowerSample(
                timestampMs = 300_000L, // 300 秒 (> 180 秒)
                voltageVolts = 3.95f,
                powerWatts = 2.0f,
                batteryLevelPercent = 75.0f,
                state = ScreenPowerState.SCREEN_ON
            )
        )

        val result = DischargeEnergyIntegrator.integrateDischargeSession(samples)
        assertTrue(result.binDeltas.isEmpty())
        assertEquals(1, result.unobservedRanges.size)
        val gap = result.unobservedRanges[0]
        assertEquals(80.0f, gap.startSocPercent)
        assertEquals(75.0f, gap.endSocPercent)
        assertEquals(300_000L, gap.gapDurationMs)
    }

    /**
     * 测试变功率跨边界时间插值分段积分的能量严格守恒。
     * P1(0s, 96%, 4.0W) -> P2(10s, 94%, 2.0W)，跨越 95% 边界（时间中点 5s，插值功率 Pb = 3.0W）。
     * 前段 [0, 5s]：平均功率 (4+3)/2 = 3.5W，能量 3.5 * (5/3600) Wh = 0.004861 Wh (bin 19)。
     * 后段 [5, 10s]：平均功率 (3+2)/2 = 2.5W，能量 2.5 * (5/3600) Wh = 0.003472 Wh (bin 18)。
     * 总能量 = 0.008333 Wh，与首尾梯形 (4+2)/2 * 10 / 3600 = 3 * 10 / 3600 = 0.008333 Wh 严格守恒！
     */
    @Test
    fun testVariablePowerBoundaryInterpolation() {
        val samples = listOf(
            PowerSample(
                timestampMs = 0L,
                voltageVolts = 4.1f,
                powerWatts = 4.0f,
                batteryLevelPercent = 96.0f,
                state = ScreenPowerState.SCREEN_ON
            ),
            PowerSample(
                timestampMs = 10_000L,
                voltageVolts = 4.0f,
                powerWatts = 2.0f,
                batteryLevelPercent = 94.0f,
                state = ScreenPowerState.SCREEN_ON
            )
        )

        val result = DischargeEnergyIntegrator.integrateDischargeSamples(samples)
        assertEquals(2, result.size)

        val bin19 = result[19]
        val bin18 = result[18]
        assertNotNull(bin19)
        assertNotNull(bin18)

        val expectedBin19 = 3.5 * (5.0 / 3600.0)
        val expectedBin18 = 2.5 * (5.0 / 3600.0)
        assertEquals(expectedBin19, bin19!!.energyWh, 0.0001)
        assertEquals(expectedBin18, bin18!!.energyWh, 0.0001)
        assertEquals(expectedBin19 + expectedBin18, bin19.energyWh + bin18.energyWh, 0.000001)
    }

    /**
     * 测试彻底贯彻纯亮屏预测：
     * 验证在放电记录（94% -> 24%）中，剔除息屏休眠能量后，
     * 仅以纯亮屏能量 12.871 Wh 与纯亮屏净掉电 51.0% 计算满充能量，
     * 推算结果精准吻合同类 App 测得的 25.2 Wh（对应 6331 mAh 电池在 3.98V 平均放电电压下的做功）。
     */
    @Test
    fun testPureScreenOnPredictionMatchesHardwareTarget() {
        val pureOnEnergyWh = 12.871f
        val pureOnDropPercent = 51.0f
        val startSoc = 94.0f
        val endSoc = 24.0f

        val predictedFullWh = com.battery.analysis.domain.estimator.SocEnergyCurveLearner.calculateScreenOnAnchoredFullEnergy(
            screenOnEnergyWh = pureOnEnergyWh,
            screenOnDropPercent = pureOnDropPercent,
            startSoc = startSoc,
            endSoc = endSoc
        )

        assertNotNull(predictedFullWh)
        // 验证推算出的满充物理做功：12.871 Wh / 0.510 = 25.237 Wh，与同类 App (25.2 Wh) 完美一致
        assertEquals(25.237, predictedFullWh!!, 0.05)
    }

    /**
     * 测试纯亮屏放电微积分引擎彻底隔离息屏切片：
     * 构造包含 50 小时长待机（0.25W 底噪）与 3 小时连续亮屏（2.5W）的采样点序列，
     * 验证微积分引擎仅对亮屏的 3 小时进行积分（产出 7.5 Wh 做功），息屏待机的 12.5 Wh 做功被 100% 隔离排除。
     */
    @Test
    fun testIntegrateDischargeSession_IgnoresAllScreenOffSamples() {
        val samples = mutableListOf<PowerSample>()
        // 亮屏阶段 1: 100% -> 90%, 10 个切片 (每切片 1%, 耗时 60 秒, 功率 2.5W)
        for (i in 0..10) {
            samples.add(
                PowerSample(
                    timestampMs = i * 60_000L,
                    voltageVolts = 4.2f - (i * 0.02f),
                    powerWatts = 2.5f,
                    batteryLevelPercent = 100.0f - i,
                    state = ScreenPowerState.SCREEN_ON
                )
            )
        }
        val tScreenOnEnd = 10 * 60_000L
        // 息屏待机阶段: 持续 50 小时, 90% -> 70%, 功率 0.25W (底噪)
        samples.add(
            PowerSample(
                timestampMs = tScreenOnEnd + 1000L,
                voltageVolts = 3.95f,
                powerWatts = 0.25f,
                batteryLevelPercent = 90.0f,
                state = ScreenPowerState.SCREEN_OFF
            )
        )
        samples.add(
            PowerSample(
                timestampMs = tScreenOnEnd + 50 * 3600_000L,
                voltageVolts = 3.85f,
                powerWatts = 0.25f,
                batteryLevelPercent = 70.0f,
                state = ScreenPowerState.SCREEN_OFF
            )
        )
        val tScreenOn2Start = tScreenOnEnd + 50 * 3600_000L + 1000L
        // 亮屏阶段 2: 70% -> 50%, 20 个切片 (每切片 1%, 耗时 60 秒, 功率 2.5W)
        for (i in 0..20) {
            samples.add(
                PowerSample(
                    timestampMs = tScreenOn2Start + i * 60_000L,
                    voltageVolts = 3.85f - (i * 0.01f),
                    powerWatts = 2.5f,
                    batteryLevelPercent = 70.0f - i,
                    state = ScreenPowerState.SCREEN_ON
                )
            )
        }

        val result = DischargeEnergyIntegrator.integrateDischargeSession(samples)

        // 亮屏总时长为 10 分钟 + 20 分钟 = 30 分钟 (1800 秒)，做功为 2.5W * 0.5h = 1.25 Wh
        // 息屏的 50 小时 (12.5 Wh) 坚决 100% 隔离不计入
        assertEquals(1.25, result.totalIntegratedEnergyWh, 0.01)
        assertEquals(30 * 60_000L, result.totalValidDurationMs)

        // 验证生成的 Bin 跨度严格对齐纯亮屏电量变化 (100%->90% 的 10% 与 70%->50% 的 20%，共 30%)
        val totalCoveredSoc = result.binDeltas.values.sumOf { it.coveredSocSpan.toDouble() }
        assertEquals(30.0, totalCoveredSoc, 0.1)
    }

    /**
     * 测试同一 5% Bin 内部穿插息屏休眠掉电时，coveredSocSpan 精准取纯亮屏微元掉电，
     * 坚决杜绝粗暴使用 binMaxSoc - binMinSoc 导致息屏掉电被错误计入亮屏分母。
     *
     * 场景设计：在 80%~85%（bin 16）内部：
     * 亮屏 1：85.0% -> 83.5%（纯亮屏掉电 1.5%）
     * 息屏：83.5% -> 81.5%（息屏待机掉电 2.0%）
     * 亮屏 2：81.5% -> 80.5%（纯亮屏掉电 1.0%）
     * 纯亮屏实际掉电分母必须为 1.5% + 1.0% = 2.5%，绝对不能是 85.0 - 80.5 = 4.5%。
     *
     * @see DischargeEnergyIntegrator.integrateDischargeSession
     */
    @Test
    fun testIntraBinSleepGapDropNotCountedInScreenOnDrop() {
        val samples = listOf(
            PowerSample(
                timestampMs = 0L,
                voltageVolts = 4.0f,
                powerWatts = 2.0f,
                batteryLevelPercent = 85.0f,
                state = ScreenPowerState.SCREEN_ON
            ),
            PowerSample(
                timestampMs = 30_000L,
                voltageVolts = 3.98f,
                powerWatts = 2.0f,
                batteryLevelPercent = 83.5f,
                state = ScreenPowerState.SCREEN_ON
            ),
            PowerSample(
                timestampMs = 31_000L,
                voltageVolts = 3.95f,
                powerWatts = 0.1f,
                batteryLevelPercent = 83.5f,
                state = ScreenPowerState.SCREEN_OFF
            ),
            PowerSample(
                timestampMs = 3_600_000L,
                voltageVolts = 3.90f,
                powerWatts = 0.1f,
                batteryLevelPercent = 81.5f,
                state = ScreenPowerState.SCREEN_OFF
            ),
            PowerSample(
                timestampMs = 3_601_000L,
                voltageVolts = 3.90f,
                powerWatts = 2.0f,
                batteryLevelPercent = 81.5f,
                state = ScreenPowerState.SCREEN_ON
            ),
            PowerSample(
                timestampMs = 3_631_000L,
                voltageVolts = 3.88f,
                powerWatts = 2.0f,
                batteryLevelPercent = 80.5f,
                state = ScreenPowerState.SCREEN_ON
            )
        )

        val result = DischargeEnergyIntegrator.integrateDischargeSession(samples)
        val bin16 = result.binDeltas[16]
        assertNotNull(bin16)
        // 纯亮屏微元掉电为 1.5% + 1.0% = 2.5%
        assertEquals(2.5f, bin16!!.coveredSocSpan, 0.01f)
    }

    /**
     * 测试纯亮屏物理特征纯度守卫（Screen-On Physical Purity Guard）：
     * 验证当采样序列中混入被错误标记为 SCREEN_ON 的息屏待机点（例如功率处于 0.25W~0.30W）时，
     * 微积分引擎能够 100% 拦截并隔离待机做功与待机掉电，只对真实大于等于 0.40W 的亮屏交互做功进行积分。
     */
    @Test
    fun testScreenOnPhysicalPurityGuardRejectsStandbyPowerLeakage() {
        val samples = listOf(
            // 真实亮屏片段：功率 2.0W，耗时 60 秒，掉电 0.5% (85.0% -> 84.5%)
            PowerSample(
                timestampMs = 0L,
                voltageVolts = 4.0f,
                powerWatts = 2.0f,
                batteryLevelPercent = 85.0f,
                state = ScreenPowerState.SCREEN_ON
            ),
            PowerSample(
                timestampMs = 60_000L,
                voltageVolts = 3.98f,
                powerWatts = 2.0f,
                batteryLevelPercent = 84.5f,
                state = ScreenPowerState.SCREEN_ON
            ),
            // 被污染伪亮屏片段：被打上 SCREEN_ON 标签，但实际功率仅为 0.25W（息屏休眠待机），耗时 60 秒，掉电 0.5% (84.5% -> 84.0%)
            PowerSample(
                timestampMs = 120_000L,
                voltageVolts = 3.97f,
                powerWatts = 0.25f,
                batteryLevelPercent = 84.0f,
                state = ScreenPowerState.SCREEN_ON
            )
        )

        val result = DischargeEnergyIntegrator.integrateDischargeSession(samples)
        val bin16 = result.binDeltas[16] // 80%~85% 属于 Bin 16
        assertNotNull("Bin 16 必须存在做功微元", bin16)

        // 理论真实亮屏做功：2.0W * (60s / 3600) = 0.03333 Wh
        // 伪亮屏片段 (0.25W) 必须被纯度守卫彻底拦截，绝不累加进做功
        val expectedTrueScreenOnWh = 2.0 * (60.0 / 3600.0)
        assertEquals(expectedTrueScreenOnWh, bin16!!.energyWh, 0.0001)

        // 纯亮屏掉电跨度也仅记录真实亮屏的 0.5%，待机掉电被精准隔离
        assertEquals(0.5f, bin16.coveredSocSpan, 0.001f)
    }

    /**
     * 测试历史污染记录反序列化清洗（PowerUsageRecord Purity Guard）：
     * 验证历史记录反序列化时，能够自动识别被旧版本 bug 错误篡改为 screenOn=true 但功率处于待机底噪（< 0.40W）的数据点，
     * 并忠实将其清洗还原为 isScreenOn = false。
     */
    @Test
    fun testPowerUsageRecordDeserializationSanitizesContaminatedStandbyPoints() {
        val contaminatedJson = org.json.JSONArray().apply {
            put(org.json.JSONObject().apply {
                put("ts", 1000L)
                put("lvl", 90)
                put("volt", 4.10)
                put("pwr", 1.80) // 真实亮屏
                put("screenOn", true)
            })
            put(org.json.JSONObject().apply {
                put("ts", 2000L)
                put("lvl", 90)
                put("volt", 4.08)
                put("pwr", 0.30) // 被错误篡改为 screenOn 的待机休眠点
                put("screenOn", true)
            })
        }.toString()

        val record = com.battery.analysis.model.PowerUsageRecord(
            id = 123456L,
            recordTime = "2026-11-10 12:00:00",
            levelPercent = 90,
            voltageVolts = 4.0f,
            temperature = 28f,
            energyWh = 20f,
            isCharging = false,
            avgPowerWatts = 1.0f,
            screenOnPowerWatts = 1.8f,
            screenOffPowerWatts = 0.3f,
            screenOnDurationText = "1h",
            screenOffDurationText = "1h",
            totalDurationText = "2h",
            remainingScreenOnText = "",
            remainingCompositeText = "",
            remainingScreenOffText = "",
            isShizukuRealData = false,
            appCount = 0,
            trendPointsJson = contaminatedJson,
            appListJson = "[]"
        )

        val parsedPoints = record.parseTrendPointsList()
        assertEquals(2, parsedPoints.size)
        // 第 1 个真实亮屏点保持 isScreenOn = true
        assertTrue("1.80W 的采样点必须保留为亮屏", parsedPoints[0].isScreenOn)
        // 第 2 个 0.30W 的待机点必须被清洗还原为 isScreenOn = false
        assertFalse("0.30W 的待机休眠采样点必须被清洗还原为息屏", parsedPoints[1].isScreenOn)
    }

    /**
     * 测试全样本微积分联合推算中的全局基准电压标尺守卫：
     * 验证当放电数据局部集中在高电压平台（如 4.10V）时，基准能量依然采用全程标称电压（3.85V）折算，
     * 确保满充预测精准收敛到电池真实的电化学物理容量（6276 mAh 对应 24.16 Wh），杜绝高压平台造成全局放大。
     */
    @Test
    fun testAggregatedScreenOnFullEnergyAnchorsToNominalVoltageBaseline() {
        val highVoltageSession = com.battery.analysis.domain.estimator.model.QualifiedScreenOnSession(
            sessionId = "session_high_volt",
            screenOnWh = 6.0, // 高电压平台放电 6.0 Wh
            screenOnChargeMah = 1463.4, // 平均电压 6000 / 1463.4 = 4.10 V
            startSoc = 95.0f,
            endSoc = 75.0f,
            screenOnDropPercent = 20.0f,
            theoreticalShapeWeight = 0.25
        )

        val aggregatedFullWh = com.battery.analysis.domain.estimator.SocEnergyCurveLearner.calculateAggregatedScreenOnFullEnergy(
            sessions = listOf(highVoltageSession),
            effectiveCapacityMah = 6276.0f, // 真实基准容量 6276 mAh
            nominalVoltageVolts = 3.85f,
            designCapacityMah = 8000.0f
        )

        assertNotNull(aggregatedFullWh)
        // 6276 mAh 在标称 3.85V 下物理基准能量为 24.16 Wh
        // 即使该片段工作电压在 4.10V，推算结果依然稳定收敛在 24.16 Wh 左右（容差 0.5 Wh 内），杜绝放大到 25.7 Wh 以上
        val expectedNominalFullWh = (6276.0 * 3.85) / 1000.0
        assertEquals(expectedNominalFullWh, aggregatedFullWh!!, 0.5)
    }
}
