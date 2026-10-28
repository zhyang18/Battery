package com.battery.analysis.domain.estimator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 梯形微元安时积分器单元测试类。
 */
class BatteryChargeIntegratorTest {

    /**
     * 辅助测试采样点实体。
     *
     * @property elapsedRealtimeMs 单调时间戳（毫秒）
     * @property netCurrentMa 净电流（毫安）
     */
    private data class TestPoint(
        override val elapsedRealtimeMs: Long,
        override val netCurrentMa: Float
    ) : IntegrablePoint

    /**
     * 测试标准恒定电流持续一小时的积分准确性。
     * 2000 mA 恒流，采用两段采样，每段 1800 秒，传入 maxGapMs = 3600_000L，理论充入物理电量必须严格为 2000.0 mAh。
     */
    @Test
    fun testConstantCurrentIntegration() {
        val points = listOf(
            TestPoint(elapsedRealtimeMs = 0L, netCurrentMa = 2000f),
            TestPoint(elapsedRealtimeMs = 1_800_000L, netCurrentMa = 2000f), // 30 分钟
            TestPoint(elapsedRealtimeMs = 3_600_000L, netCurrentMa = 2000f)  // 60 分钟
        )

        // 显式指定允许的间隔，验证大步长积分数学公式
        val resultMah = BatteryChargeIntegrator.integrateChargeMah(points, maxGapMs = 3_600_000L)
        assertEquals(2000.0, resultMah, 0.001)
    }

    /**
     * 测试默认高频/日常采样间隔（每 10 秒一个点）持续 60 秒的恒流积分。
     * 2000 mA 持续 60 秒，理论充入 2000 * (60 / 3600) = 33.333 mAh。
     */
    @Test
    fun testStandardSamplingRateIntegration() {
        val points = (0..6).map { i ->
            TestPoint(elapsedRealtimeMs = i * 10_000L, netCurrentMa = 2000f)
        }

        val resultMah = BatteryChargeIntegrator.integrateChargeMah(points)
        val expected = 2000.0 * (60.0 / 3600.0)
        assertEquals(expected, resultMah, 0.001)
    }

    /**
     * 测试线性变化电流（从 1000mA 爬坡至 3000mA）持续 60 秒的梯形微积分准确性。
     * 平均电流 2000mA，持续 60 秒，理论充入 33.333 mAh。
     */
    @Test
    fun testRampingCurrentIntegration() {
        val points = listOf(
            TestPoint(elapsedRealtimeMs = 0L, netCurrentMa = 1000f),
            TestPoint(elapsedRealtimeMs = 60_000L, netCurrentMa = 3000f)
        )

        val resultMah = BatteryChargeIntegrator.integrateChargeMah(points)
        val expected = 2000.0 * (60.0 / 3600.0)
        assertEquals(expected, resultMah, 0.001)
    }

    /**
     * 测试超过默认 180 秒的缺样断层是否被自动熔断。
     * 验证系统休眠或服务挂起时，绝不跨越数十分钟长周期产生虚假积分。
     */
    @Test
    fun testSliceGapOverLimitIsSkipped() {
        val points = listOf(
            TestPoint(elapsedRealtimeMs = 0L, netCurrentMa = 1000f),
            TestPoint(elapsedRealtimeMs = 10_000L, netCurrentMa = 1000f), // 10秒有效：1000mA * (10/3600)h = 2.777mAh
            TestPoint(elapsedRealtimeMs = 1_000_000L, netCurrentMa = 1000f) // 间隔 990 秒（>180秒），应被断开忽略
        )

        val resultMah = BatteryChargeIntegrator.integrateChargeMah(points)
        // 仅计算前 10 秒
        val expectedFirstSlice = 1000.0 * (10.0 / 3600.0)
        assertEquals(expectedFirstSlice, resultMah, 0.001)
    }

    /**
     * 测试时间戳倒流或完全重复的异常点自动剔除。
     */
    @Test
    fun testNonMonotonicTimestampsAreRejected() {
        val points = listOf(
            TestPoint(elapsedRealtimeMs = 10_000L, netCurrentMa = 1000f),
            TestPoint(elapsedRealtimeMs = 5_000L, netCurrentMa = 1000f),  // 倒流点，必须丢弃
            TestPoint(elapsedRealtimeMs = 10_000L, netCurrentMa = 1000f), // 重复点，必须丢弃
            TestPoint(elapsedRealtimeMs = 20_000L, netCurrentMa = 1000f)  // 有效点（距离前驱有效点 10秒）
        )

        val resultMah = BatteryChargeIntegrator.integrateChargeMah(points)
        // 仅有 10_000L -> 20_000L 有效切片（10秒）
        val expectedSlice = 1000.0 * (10.0 / 3600.0)
        assertEquals(expectedSlice, resultMah, 0.001)
    }

    /**
     * 测试连续性评分 evaluateContinuityScore：
     * 正常连续点序列得分为 1.0；若出现大断层缺口，覆盖率显著下降；
     * 且即使中间混入时间倒流点，也能正确计算有效单调时段的真实连续覆盖率。
     */
    @Test
    fun testContinuityScoreCalculation() {
        // 1. 完全连续序列（每 10 秒一个点，共 60 秒）
        val continuousPoints = (0..6).map { i ->
            TestPoint(elapsedRealtimeMs = i * 10_000L, netCurrentMa = 1000f)
        }
        val scoreFull = BatteryChargeIntegrator.evaluateContinuityScore(continuousPoints)
        assertEquals(1.0, scoreFull, 0.001)

        // 2. 包含一个 1000 秒断层的序列（断层超过 180 秒）
        val gappedPoints = listOf(
            TestPoint(elapsedRealtimeMs = 0L, netCurrentMa = 1000f),
            TestPoint(elapsedRealtimeMs = 10_000L, netCurrentMa = 1000f),
            TestPoint(elapsedRealtimeMs = 1_000_000L, netCurrentMa = 1000f),
            TestPoint(elapsedRealtimeMs = 1_010_000L, netCurrentMa = 1000f)
        )
        val scoreGapped = BatteryChargeIntegrator.evaluateContinuityScore(gappedPoints)
        // 有效时间 20秒 / 总跨度 1010秒 ≈ 0.0198
        assertTrue("严重断层样本的连续性评分必须低于 0.1", scoreGapped < 0.1)

        // 3. 混入倒流点与乱序点
        val noisyPoints = listOf(
            TestPoint(elapsedRealtimeMs = 0L, netCurrentMa = 1000f),
            TestPoint(elapsedRealtimeMs = 10_000L, netCurrentMa = 1000f),
            TestPoint(elapsedRealtimeMs = 5_000L, netCurrentMa = 1000f), // 倒流点
            TestPoint(elapsedRealtimeMs = 20_000L, netCurrentMa = 1000f)
        )
        val scoreNoisy = BatteryChargeIntegrator.evaluateContinuityScore(noisyPoints)
        assertEquals(1.0, scoreNoisy, 0.001)
    }

    /**
     * 测试空列表或单点时忠实返回 0.0，杜绝假数据。
     */
    @Test
    fun testEmptyOrSinglePointReturnsZero() {
        assertEquals(0.0, BatteryChargeIntegrator.integrateChargeMah(emptyList()), 0.0)
        assertEquals(0.0, BatteryChargeIntegrator.integrateChargeMah(listOf(TestPoint(0L, 500f))), 0.0)
    }
}
