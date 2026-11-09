package com.battery.analysis

import com.battery.analysis.domain.estimator.DischargeEnergyIntegrator
import com.battery.analysis.domain.estimator.model.LearnedSocEnergyCurve
import com.battery.analysis.domain.estimator.model.PowerSample
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
     * 测试息屏待机休眠区间（SCREEN_OFF）坚决不注入虚构保底待机功耗，忠实记录为未观测断层（UnobservedSocRange）。
     */
    @Test
    fun testScreenOffStandbyGapMarkedAsUnobservedWithoutMockData() {
        val samples = listOf(
            PowerSample(
                timestampMs = 0L,
                voltageVolts = 4.0f,
                powerWatts = 0.25f,
                batteryLevelPercent = 80.0f,
                state = ScreenPowerState.SCREEN_OFF
            ),
            PowerSample(
                timestampMs = 300_000L, // 跨越 5 分钟
                voltageVolts = 3.98f,
                powerWatts = 0.25f,
                batteryLevelPercent = 79.5f,
                state = ScreenPowerState.SCREEN_OFF
            )
        )

        val sessionResult = DischargeEnergyIntegrator.integrateDischargeSession(samples)
        // 纯亮屏积分不生成虚构做功 Bin
        assertTrue(sessionResult.binDeltas.isEmpty())
        // 忠实记录息屏未观测区间
        assertEquals(1, sessionResult.unobservedRanges.size)
        assertEquals(80.0f, sessionResult.unobservedRanges[0].startSocPercent, 0.001f)
        assertEquals(79.5f, sessionResult.unobservedRanges[0].endSocPercent, 0.001f)
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
        // 正常产生积分做功：0.30W * (10 / 3600) = 0.0008333 Wh
        assertEquals(1, sessionResult.binDeltas.size)
        val bin5 = sessionResult.binDeltas[5]
        assertNotNull(bin5)
        val expectedWh = 0.30 * (10.0 / 3600.0)
        assertEquals(expectedWh, bin5!!.energyWh, 0.00001)
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
        val samples = listOf(
            PowerSample(
                timestampMs = 0L,
                voltageVolts = 4.0f,
                powerWatts = 0.2f,
                batteryLevelPercent = 80.0f,
                state = ScreenPowerState.SCREEN_OFF
            ),
            PowerSample(
                timestampMs = 5 * 3600_000L, // 5 小时超大断层 (> 4 小时)
                voltageVolts = 3.95f,
                powerWatts = 0.2f,
                batteryLevelPercent = 75.0f,
                state = ScreenPowerState.SCREEN_OFF
            )
        )

        val result = DischargeEnergyIntegrator.integrateDischargeSession(samples)
        assertTrue(result.binDeltas.isEmpty())
        assertEquals(1, result.unobservedRanges.size)
        val gap = result.unobservedRanges[0]
        assertEquals(80.0f, gap.startSocPercent)
        assertEquals(75.0f, gap.endSocPercent)
        assertEquals(5 * 3600_000L, gap.gapDurationMs)
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
}
