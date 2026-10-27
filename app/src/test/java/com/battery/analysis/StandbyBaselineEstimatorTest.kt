package com.battery.analysis

import com.battery.analysis.domain.estimator.StandbyBaselineEstimator
import com.battery.analysis.domain.estimator.model.StandbyBaselineConfig
import com.battery.analysis.domain.estimator.model.StandbyBaselineSource
import com.battery.analysis.domain.estimator.model.StandbyConfidenceLevel
import com.battery.analysis.manager.PowerUsageManager
import com.battery.analysis.model.PowerDischargePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 息屏待机基线功率稳健估计器（StandbyBaselineEstimator）单元测试套件。
 * 验证单点异常噪声防御、时间窗口聚合对高频唤醒选择偏差的免疫、
 * 污染熔断与回退先验机制，以及坚守零假数据原则的合规性。
 */
class StandbyBaselineEstimatorTest {

    /**
     * 辅助方法：生成指定时间戳与功率的放电采样点。
     *
     * @param timestamp 采样绝对时间戳（毫秒）
     * @param powerWatts 瞬时放电功耗（瓦特 W）
     * @param isScreenOn 是否亮屏（默认 false 即息屏）
     * @return 构造的放电采样点实体 [PowerDischargePoint]
     */
    private fun createPoint(timestamp: Long, powerWatts: Float, isScreenOn: Boolean = false): PowerDischargePoint {
        return PowerDischargePoint(
            timestamp = timestamp,
            elapsedHours = (timestamp / 3600000f),
            batteryLevel = 80,
            voltageVolts = 3.85f,
            temperature = 25f,
            powerWatts = powerWatts,
            isScreenOn = isScreenOn
        )
    }

    /**
     * 测试单点偶然极小值（如 I2C 总线抖动产生 0.005W）绝对不被采纳为基线，
     * 彻底消灭单点极小值支配数小时长断层导致的严重虚低能耗 Bug。
     */
    @Test
    fun testSingleMinPowerNoiseDoesNotDominateBaseline() {
        val samples = mutableListOf<PowerDischargePoint>()
        val startTs = 1000000L
        val config = StandbyBaselineConfig(
            baseWindowDurationMs = 60_000L,
            minSamplesPerWindow = 2
        )

        // 生成 6 个时间窗口（每个窗口 60 秒，内含 3 个点），真实待机功耗在 0.12W ~ 0.14W 之间
        for (w in 0 until 6) {
            val windowBase = startTs + w * 60_000L
            samples.add(createPoint(windowBase + 5_000L, 0.12f))
            samples.add(createPoint(windowBase + 25_000L, 0.13f))
            samples.add(createPoint(windowBase + 45_000L, 0.14f))
        }

        // 人为在其中一个瞬间注入一个单点极端噪声（0.005W）
        samples.add(createPoint(startTs + 30_000L, 0.005f))

        val result = StandbyBaselineEstimator.estimate(
            samples = samples,
            recordIntervalMs = 1000L,
            config = config
        )

        assertEquals("必须采信实测窗口 P50 典型基线", StandbyBaselineSource.MEASURED_WINDOW_P50, result.source)
        assertEquals("原始极小值必须忠实记录为 0.005W 供诊断", 0.005, result.rawMinWatts ?: 0.0, 0.001)
        // 关键断言：最终基线必须落在 0.12W 附近，绝对不能被 0.005W 绑架！
        assertTrue(
            "基线功率(${result.baselinePowerWatts})必须高于 0.10W，杜绝单点噪点穿透",
            result.baselinePowerWatts >= 0.10
        )
        assertTrue(
            "基线功率(${result.baselinePowerWatts})必须接近窗口 P50(0.12W~0.14W)",
            result.baselinePowerWatts in 0.11..0.15
        )
        assertEquals(0.12, result.floorWatts ?: 0.0, 0.02)
    }

    /**
     * 测试短时间高频唤醒突发（产生数百个高功耗点）不会碾压低频稳态深睡点。
     * 验证时间窗口聚合成功消除了采样频率不均带来的选择偏差。
     */
    @Test
    fun testHighFrequencyAwakeBurstDoesNotOverwhelmDeepSleep() {
        val samples = mutableListOf<PowerDischargePoint>()
        var currentTs = 2000000L
        val config = StandbyBaselineConfig(baseWindowDurationMs = 60_000L)

        // 阶段 1：前 2 分钟发生网络唤醒，高频采样（每 1 秒 1 个点，共 120 个点，功耗 0.45W）
        for (i in 0 until 120) {
            samples.add(createPoint(currentTs, 0.45f))
            currentTs += 1000L
        }

        // 阶段 2：后 6 分钟进入稳态深度休眠边缘（每 15 秒 1 个点，共 24 个点，功耗 0.10W）
        for (i in 0 until 24) {
            samples.add(createPoint(currentTs, 0.10f))
            currentTs += 15000L
        }

        val result = StandbyBaselineEstimator.estimate(
            samples = samples,
            recordIntervalMs = 1000L,
            config = config
        )

        assertEquals("必须采信实测窗口 P50 典型基线", StandbyBaselineSource.MEASURED_WINDOW_P50, result.source)
        // 关键断言：经过时间窗口聚合，高频唤醒仅占 2 个窗口，稳态深睡占 6 个窗口，
        // 故窗口级 P50 成功锚定在 0.10W，未被 120 个高频唤醒点吞噬！
        assertEquals(0.10, result.baselinePowerWatts, 0.02)
        assertEquals(0.10, result.floorWatts ?: 0.0, 0.02)
    }

    /**
     * 测试当所有样本均处于后台活跃放电水平（> 0.20W），且缺乏低功耗窗口重复支持时，
     * 触发污染熔断，安全回退到默认先验（0.15W），并记录详细污染原因。
     */
    @Test
    fun testPollutionFallbackToDefaultPrior() {
        val samples = mutableListOf<PowerDischargePoint>()
        val startTs = 3000000L
        val config = StandbyBaselineConfig(
            defaultPriorWatts = 0.15,
            suspiciousPollutionThresholdWatts = 0.20
        )

        // 生成 5 个窗口，所有点均在 0.35W ~ 0.60W（模拟后台播放音频或热点常开）
        for (w in 0 until 5) {
            val windowBase = startTs + w * 60_000L
            samples.add(createPoint(windowBase + 10_000L, 0.38f))
            samples.add(createPoint(windowBase + 40_000L, 0.52f))
        }

        val result = StandbyBaselineEstimator.estimate(
            samples = samples,
            recordIntervalMs = 1000L,
            config = config
        )

        assertEquals("污染场景必须回退至默认先验", StandbyBaselineSource.DEFAULT_STANDBY_PRIOR, result.source)
        assertEquals("污染场景置信度必须为 LOW", StandbyConfidenceLevel.LOW, result.confidence)
        assertEquals("必须回退至默认 0.15W", 0.15, result.baselinePowerWatts, 0.001)
        assertNotNull("必须附带污染回退原因说明", result.fallbackReason)
        assertTrue(result.fallbackReason!!.contains("疑似受活跃任务严重污染"))
    }

    /**
     * 测试有效窗口数过少（不足 2 个窗口）时，无法建立稳健统计分布，安全回退先验。
     */
    @Test
    fun testInsufficientWindowsFallback() {
        val samples = listOf(
            createPoint(1000000L, 0.12f),
            createPoint(1010000L, 0.13f)
        )

        val result = StandbyBaselineEstimator.estimate(
            samples = samples,
            recordIntervalMs = 1000L,
            config = StandbyBaselineConfig(baseWindowDurationMs = 60_000L)
        )

        assertEquals("窗口不足必须回退先验", StandbyBaselineSource.DEFAULT_STANDBY_PRIOR, result.source)
        assertEquals(StandbyConfidenceLevel.LOW, result.confidence)
        assertEquals(0.15, result.baselinePowerWatts, 0.001)
        assertNotNull(result.fallbackReason)
    }

    /**
     * 测试零假数据原则合规性：在中等置信度下，输出纯粹反映实测 P10，绝不进行 α 加权平滑合成。
     */
    @Test
    fun testZeroFakeDataComplianceNoSyntheticBlend() {
        val samples = mutableListOf<PowerDischargePoint>()
        val startTs = 4000000L

        // 生成 3 个有效窗口（达到中置信度，但未达到高置信度的 5 个窗口要求），窗口代表功率分别为 0.12W, 0.14W, 0.16W
        samples.add(createPoint(startTs + 10_000L, 0.12f))
        samples.add(createPoint(startTs + 40_000L, 0.12f))

        samples.add(createPoint(startTs + 70_000L, 0.14f))
        samples.add(createPoint(startTs + 100_000L, 0.14f))

        samples.add(createPoint(startTs + 130_000L, 0.16f))
        samples.add(createPoint(startTs + 160_000L, 0.16f))

        val result = StandbyBaselineEstimator.estimate(
            samples = samples,
            recordIntervalMs = 1000L,
            config = StandbyBaselineConfig(baseWindowDurationMs = 60_000L)
        )

        assertEquals("中等置信度依然忠实采信实测 P50 典型基线", StandbyBaselineSource.MEASURED_WINDOW_P50, result.source)
        assertEquals(StandbyConfidenceLevel.MEDIUM, result.confidence)
        // 关键断言：结果必须是真实的实测值，不包含 (1-alpha)*0.15 的任何人工保底合成掺水
        assertEquals(0.14, result.baselinePowerWatts, 0.01)
        assertEquals(0.12, result.floorWatts ?: 0.0, 0.01)
    }

    /**
     * 验证物理做功守恒：低功耗基线（P_baseline）准确转换为长断层的累计真实能耗（E_gap = P_baseline * Δt_gap），
     * 并与置信连续区间的梯形微积分无缝合并，能量与平均放电功率 100% 物理闭环守恒。
     */
    @Test
    fun testStandbyBaselineConvertsToLongGapEnergyAccurately() {
        val samples = mutableListOf<PowerDischargePoint>()
        val startTs = 5000000L

        // 1. 亮屏切片：持续 60 秒，功率 2.0W
        samples.add(createPoint(startTs, 2.0f, isScreenOn = true))
        samples.add(createPoint(startTs + 60_000L, 2.0f, isScreenOn = true))

        // 2. 灭屏，进入连续置信息屏观测区间：持续 6 个 60 秒时间窗口（共 360 秒 = 0.1 小时），功率恒定为 0.10W
        // 理论置信微积分能耗 = 0.10W * (360s / 3600s) = 0.010 Wh
        var curTs = startTs + 61_000L
        for (w in 0 until 6) {
            val windowBase = curTs + w * 60_000L
            samples.add(createPoint(windowBase, 0.10f, isScreenOn = false))
            samples.add(createPoint(windowBase + 20_000L, 0.10f, isScreenOn = false))
            samples.add(createPoint(windowBase + 40_000L, 0.10f, isScreenOn = false))
        }

        // 3. 进入深度休眠大断层：持续整整 6 小时 (6 * 3600 * 1000 = 21,600,000 毫秒)
        val gapDurationMs = 6L * 3600_000L
        val lastConfidentTs = curTs + 5 * 60_000L + 40_000L
        val wakeUpTs = lastConfidentTs + gapDurationMs

        // 4. 断层结束醒来瞬间采一个点（0.10W）
        samples.add(createPoint(wakeUpTs, 0.10f, isScreenOn = false))

        // 调用整机放电微积分与工况分析引擎
        val stats = com.battery.analysis.manager.PowerUsageManager.computeDischargePowerStats(
            samples = samples,
            recordIntervalMs = 1000L
        )

        assertNotNull("放电统计结果不应为空", stats)

        // 物理理论值验证：
        // (1) 待机基线推导为 0.10W
        // (2) 6 小时长断层补算能耗 = 0.10W * 6.0h = 0.600 Wh
        // (3) 置信连续区间能耗 ≈ 0.10W * (360s / 3600s) ≈ 0.010 Wh
        // (4) 息屏总能耗 = 0.010 Wh + 0.600 Wh = 0.610 Wh
        val expectedGapEnergyWh = 0.10 * 6.0 // 0.600 Wh
        val expectedTotalOffEnergyWh = 0.010 + expectedGapEnergyWh // 0.610 Wh

        // 允许梯形积分与离散时间切片间微小的纳瓦时级浮点误差 (0.015 Wh 以内)
        assertEquals(
            "息屏总展示能耗必须等于置信实测微积分能耗与长断层外推能耗之和",
            expectedTotalOffEnergyWh,
            stats!!.screenOffDisplayEnergyWh.toDouble(),
            0.015
        )

        // 息屏平均放电功率 = 总息屏能耗 / 总息屏时长 (约 6.1 小时)
        // 0.610 Wh / 6.1 h = 0.100 W
        assertEquals(
            "息屏平均放电功率必须严格由总能耗除以总时长闭环回推，等于 0.10W",
            0.10,
            stats.screenOffPowerWatts.toDouble(),
            0.01
        )
    }

    /**
     * 规则 1 回归测试：深睡样本无论如何变化（0.03W、0.08W、0.20W 等），
     * 唤醒功率与唤醒能耗严格保持 100% 冻结不变，彻底根绝残差互甩。
     */
    @Test
    fun testAwakeStatsStrictlyFrozenRegardlessOfDeepSleepSampleVariation() {
        val screenOffMs = 3 * 3600_000L // 3 小时
        val awakeMs = 1800_000L // 30 分钟 (0.5h)
        val deepSleepMs = screenOffMs - awakeMs // 2.5 小时

        // 场景 A：深睡期间功耗读数为 0.05W
        val samplesA = listOf(
            createPoint(1000L, 0.05f),
            createPoint(2000L, 0.05f),
            createPoint(3000L, 0.05f),
            createPoint(4000L, 1.20f), // 唤醒活跃采样
            createPoint(5000L, 1.20f)  // 唤醒活跃采样
        )

        val resultA = PowerUsageManager.calculateScreenOffAwakeAndDeepSleepEnergy(
            offEnergyWh = 0.725f, // 0.05W * 2.5h + 1.20W * 0.5h = 0.725Wh
            screenOffMs = screenOffMs,
            deepSleepMs = deepSleepMs,
            awakeMs = awakeMs,
            samples = samplesA
        )

        // 场景 B：深睡期间功耗读数上升至 0.18W，深睡样本发生剧烈变化
        val samplesB = listOf(
            createPoint(1000L, 0.18f),
            createPoint(2000L, 0.18f),
            createPoint(3000L, 0.18f),
            createPoint(4000L, 1.20f), // 唤醒活跃采样（保持恒定 1.20W）
            createPoint(5000L, 1.20f)  // 唤醒活跃采样（保持恒定 1.20W）
        )

        val resultB = PowerUsageManager.calculateScreenOffAwakeAndDeepSleepEnergy(
            offEnergyWh = 1.050f, // 0.18W * 2.5h + 1.20W * 0.5h = 1.050Wh
            screenOffMs = screenOffMs,
            deepSleepMs = deepSleepMs,
            awakeMs = awakeMs,
            samples = samplesB
        )

        // 核心回归断言：深睡样本变动后，唤醒功率与唤醒能耗必须 100% 完全冻结一致！
        assertEquals("唤醒功率必须完全冻结等于实测活跃功耗(1.20W)", 1.20f, resultA.awakeWatts, 0.01f)
        assertEquals("唤醒功率必须完全冻结等于实测活跃功耗(1.20W)", 1.20f, resultB.awakeWatts, 0.01f)
        assertEquals("两次计算的唤醒能耗必须严格恒等", resultA.awakeEnergyWh, resultB.awakeEnergyWh, 0.0001f)
    }

    /**
     * 规则 2 测试：稀疏偶发采样点不足以构成固定窗口时，不伪造窗口，安全回退先验。
     */
    @Test
    fun testSparseSamplesDoNotForgeWindows() {
        val samples = listOf(
            createPoint(1000L, 0.08f),
            createPoint(70_000L, 0.09f) // 仅有两个点，且跨越了不同 60 秒窗口，每个窗口仅有 1 个点
        )

        val result = StandbyBaselineEstimator.estimate(
            samples = samples,
            config = StandbyBaselineConfig(minSamplesPerWindow = 2)
        )

        // 窗口点数不足 2 个，不能伪造窗口
        assertEquals("有效窗口数应为 0", 0, result.validWindowCount)
        assertEquals("置信度必须为 LOW", StandbyConfidenceLevel.LOW, result.confidence)
        assertEquals("来源回退默认先验", StandbyBaselineSource.DEFAULT_STANDBY_PRIOR, result.source)
    }

    /**
     * 规则 3 & 4 测试：大断层禁止插值，覆盖不足时观测均值为 null，整段深睡能量标记为模型推算。
     */
    @Test
    fun testLargeGapRejectsInterpolationAndMarksInferred() {
        val startTs = 10000000L
        val samples = mutableListOf<PowerDischargePoint>()

        // 窗口 1: 包含连续点 (间隔 2 秒)
        samples.add(createPoint(startTs, 0.10f))
        samples.add(createPoint(startTs + 2000L, 0.10f))
        samples.add(createPoint(startTs + 4000L, 0.10f))

        // 跨越 20 分钟大断层 (1,200,000ms > 30,000ms)，严禁连线插值
        val gapEndTs = startTs + 1_200_000L
        samples.add(createPoint(gapEndTs, 0.12f))
        samples.add(createPoint(gapEndTs + 2000L, 0.12f))
        samples.add(createPoint(gapEndTs + 4000L, 0.12f))

        val result = StandbyBaselineEstimator.estimate(
            samples = samples,
            config = StandbyBaselineConfig(
                baseWindowDurationMs = 60_000L,
                minSamplesPerWindow = 2,
                maxInterpolationGapMs = 30_000L,
                minCoverageRatioForMean = 0.70
            )
        )

        // 连续采样时间仅 4s + 4s = 8s，覆盖率远远低于 70%
        assertTrue("覆盖率极低", result.sleepCoverageRatio < 0.10)
        assertNull("覆盖不足时观测均值必须如实置空为 null，绝不冒充实测", result.sleepMeanWatts)
        assertFalse("观测均值未生效", result.isMeanObserved)
        // 典型基线由固定窗口 P50 给出 (0.10W 与 0.12W 的中位数 = 0.11W)
        assertEquals(0.11, result.baselinePowerWatts, 0.01)

        // 进一步验证分解结果标记 isDeepSleepInferred = true
        val decomposed = PowerUsageManager.calculateScreenOffAwakeAndDeepSleepEnergy(
            offEnergyWh = 0.3f,
            screenOffMs = 1_208_000L,
            deepSleepMs = 1_100_000L,
            awakeMs = 108_000L,
            samples = samples
        )

        assertTrue("整段深睡能量由基线外推时，必须显式标注 isDeepSleepInferred = true", decomposed.isDeepSleepInferred)
        assertNull("观测实测均值字段必须为 null", decomposed.observedSleepMeanWatts)
    }
}
