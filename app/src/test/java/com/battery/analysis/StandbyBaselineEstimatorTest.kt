package com.battery.analysis

import com.battery.analysis.domain.estimator.StandbyBaselineEstimator
import com.battery.analysis.domain.estimator.model.StandbyBaselineConfig
import com.battery.analysis.domain.estimator.model.StandbyBaselineSource
import com.battery.analysis.domain.estimator.model.StandbyConfidenceLevel
import com.battery.analysis.domain.estimator.model.StandbyReasonCode
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

        assertEquals("必须采信实测窗口 P20 典型基线", StandbyBaselineSource.MEASURED_WINDOW_P20, result.source)
        assertEquals("原始极小值必须忠实记录为 0.005W 供诊断", 0.005, result.rawMinWatts ?: 0.0, 0.001)
        // 关键断言：最终基线必须落在 0.12W 附近，绝对不能被 0.005W 绑架！
        assertTrue(
            "基线功率(${result.baselinePowerWatts})必须高于 0.10W，杜绝单点噪点穿透",
            result.baselinePowerWatts >= 0.10
        )
        assertTrue(
            "基线功率(${result.baselinePowerWatts})必须接近窗口 P20(0.12W~0.14W)",
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

        assertEquals("必须采信实测窗口 P20 典型基线", StandbyBaselineSource.MEASURED_WINDOW_P20, result.source)
        // 关键断言：经过时间窗口聚合，高频唤醒仅占 2 个窗口，稳态深睡占 6 个窗口，
        // 故窗口级 P20 成功锚定在 0.10W，未被 120 个高频唤醒点吞噬！
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
        assertEquals(StandbyConfidenceLevel.UNAVAILABLE, result.confidence)
        assertEquals(0.15, result.baselinePowerWatts, 0.001)
        assertNotNull(result.fallbackReason)
        assertTrue(result.reasonCodes.contains(com.battery.analysis.domain.estimator.model.StandbyReasonCode.INSUFFICIENT_CANDIDATE_WINDOWS))
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
            config = StandbyBaselineConfig(
                baseWindowDurationMs = 60_000L,
                minWindowsForMediumConfidence = 3
            )
        )

        assertEquals("中等置信度依然忠实采信实测 P20 典型基线", StandbyBaselineSource.MEASURED_WINDOW_P20, result.source)
        assertEquals(StandbyConfidenceLevel.MEDIUM, result.confidence)
        // 关键断言：结果必须是真实的实测值，不包含 (1-alpha)*0.15 的任何人工保底合成掺水
        assertEquals(0.128, result.baselinePowerWatts, 0.01)
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
        assertEquals("置信度必须为 UNAVAILABLE", StandbyConfidenceLevel.UNAVAILABLE, result.confidence)
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
        // 典型基线由固定窗口 P20 给出 (0.10W 与 0.12W 的 P20 = 0.104W)
        assertEquals(0.104, result.baselinePowerWatts, 0.01)

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

    /**
     * 验证 P20 算法相比 P30 与 P50 在真实设备有较多偶发唤醒窗口时，
     * 能够进一步穿透中高位唤醒窗口，深度锁定真实深睡静态底噪（0.05W），
     * 彻底解决用户实测反馈深睡功耗达到 0.55W 依然偏高的问题。
     */
    @Test
    fun testP20SuppressesWakelockTailBeyondP30() {
        val samples = mutableListOf<PowerDischargePoint>()
        val startTs = 7000000L
        val windowDurationMs = 60_000L

        // 构造 10 个窗口：
        // 3 个窗口处于深睡真实静态底噪 (0.05W)
        // 4 个窗口处于中等后台活跃唤醒 (0.40W)
        // 3 个窗口处于重度唤醒突发 (0.60W)
        // 排序后的窗口代表功耗序列: [0.05, 0.05, 0.05, 0.40, 0.40, 0.40, 0.40, 0.60, 0.60, 0.60]
        for (w in 0 until 10) {
            val power = when {
                w < 3 -> 0.05f
                w < 7 -> 0.40f
                else -> 0.60f
            }
            val baseTs = startTs + w * windowDurationMs
            samples.add(createPoint(baseTs + 10_000L, power))
            samples.add(createPoint(baseTs + 40_000L, power))
        }

        val resultP50 = StandbyBaselineEstimator.estimate(
            samples = samples,
            config = StandbyBaselineConfig(baselineQuantile = 0.50)
        )
        val resultP30 = StandbyBaselineEstimator.estimate(
            samples = samples,
            config = StandbyBaselineConfig(baselineQuantile = 0.30)
        )
        val resultP20 = StandbyBaselineEstimator.estimate(
            samples = samples
            // 默认配置即为 baselineQuantile = 0.20 (P20 主估计)
        )
        val resultP15 = StandbyBaselineEstimator.estimate(
            samples = samples,
            config = StandbyBaselineConfig(baselineQuantile = 0.15)
        )

        assertEquals(StandbyBaselineSource.MEASURED_WINDOW_P20, resultP20.source)
        assertEquals(StandbyBaselineSource.MEASURED_WINDOW_P15, resultP15.source)
        // P50 = 0.40W，被中等唤醒严重拉高
        assertEquals(0.40, resultP50.baselinePowerWatts, 0.01)
        // P30 = 0.295W (0.05*0.3 + 0.40*0.7)，依然偏高
        assertEquals(0.295, resultP30.baselinePowerWatts, 0.01)
        // P20 = 0.05W，精准下潜锁定在深睡真值！
        assertEquals(0.05, resultP20.baselinePowerWatts, 0.01)
        // P15 = 0.05W，深度锁定在深睡真值！
        assertEquals(0.05, resultP15.baselinePowerWatts, 0.01)

        assertTrue(
            "P20 基线必须显著低于 P30 基线，压制唤醒残留",
            resultP20.baselinePowerWatts < resultP30.baselinePowerWatts
        )
        assertTrue(
            "P30 基线必须显著低于 P50 基线",
            resultP30.baselinePowerWatts < resultP50.baselinePowerWatts
        )
    }

    /**
     * 验证当唤醒窗口占比达到 15%~20% 时，P15 算法相比 P20 具备更强的抗唤醒拖尾穿透力，
     * 能精准剔除轻度活跃窗口，深度锚定芯片纯净静止底噪。
     */
    @Test
    fun testP15SuppressesWakelockTailBeyondP20UnderMarginalAwakeWindows() {
        val samples = mutableListOf<PowerDischargePoint>()
        val startTs = 8000000L
        val windowDurationMs = 60_000L

        // 构造 20 个窗口：
        // 前 4 个窗口为纯净深睡底噪 (0.05W) -> 占比 20%
        // 接下来的 2 个窗口为轻度唤醒过渡 (0.22W) -> 占比 10%
        // 剩余 14 个窗口为中高唤醒活跃 (0.45W)
        // 每个窗口内采集 2 个点（间隔 5 秒，满足窗口内最少采样点），窗口之间间隔 55 秒（> 30 秒插值断层门限，
        // 模拟真实睡眠挂起导致的非连续断续采样，测试固定窗口 P20 基线提取算法）
        for (w in 0 until 20) {
            val power = when {
                w < 4 -> 0.05f
                w < 6 -> 0.22f
                else -> 0.45f
            }
            val baseTs = startTs + w * windowDurationMs
            samples.add(createPoint(baseTs + 10_000L, power))
            samples.add(createPoint(baseTs + 15_000L, power))
        }

        val resultP20 = StandbyBaselineEstimator.estimate(
            samples = samples
            // 默认配置 baselineQuantile = 0.20
        )
        val resultP15 = StandbyBaselineEstimator.estimate(
            samples = samples,
            config = StandbyBaselineConfig(baselineQuantile = 0.15)
        )

        assertEquals(StandbyBaselineSource.MEASURED_WINDOW_P15, resultP15.source)
        assertEquals(StandbyBaselineSource.MEASURED_WINDOW_P20, resultP20.source)
        // P15 精准锁定在 0.05W 深睡真值，而 P20 (20%) 被 0.22W 的活跃窗口拉高
        assertEquals(0.05, resultP15.baselinePowerWatts, 0.02)
        assertTrue(
            "P15 必须有效穿透 15%~20% 边缘唤醒，功率显著低于 P20",
            resultP15.baselinePowerWatts < resultP20.baselinePowerWatts
        )
    }

    /**
     * 验证 P20 主估计 + P30 校验平坦度测试：
     * 当处于稳定纯净深睡平台时，P20 与 P30 差距极小，校验通过，且无 P20_P30_GAP_HIGH 原因码。
     */
    @Test
    fun testP20MainEstimationWithP30FlatnessVerificationPass() {
        val samples = mutableListOf<PowerDischargePoint>()
        val startTs = 9000000L
        val windowDurationMs = 60_000L

        // 构造 10 个稳定平坦深睡窗口，功耗在 0.040W ~ 0.045W 之间微幅波动
        for (w in 0 until 10) {
            val power = 0.040f + (w % 3) * 0.002f
            val baseTs = startTs + w * windowDurationMs
            samples.add(createPoint(baseTs + 10_000L, power))
            samples.add(createPoint(baseTs + 40_000L, power))
        }

        val result = StandbyBaselineEstimator.estimate(
            samples = samples,
            config = StandbyBaselineConfig(
                minWindowsForMediumConfidence = 10,
                baseWindowDurationMs = 60_000L
            )
        )

        assertEquals("必须采信 P20 作为主基线", StandbyBaselineSource.MEASURED_WINDOW_P20, result.source)
        assertEquals("达到 10 个窗口且平坦校验通过，置信度为 MEDIUM", StandbyConfidenceLevel.MEDIUM, result.confidence)
        assertNotNull(result.quantileGapW)
        assertNotNull(result.relativeQuantileGap)
        assertTrue("P30 与 P20 差距极小(< 0.01W)", result.quantileGapW!! <= 0.01)
        assertTrue("相对偏差极小(< 15%)", result.relativeQuantileGap!! < 0.15)
        assertTrue("平坦平台不应触发 P20_P30_GAP_HIGH 告警", result.reasonCodes.none { it == StandbyReasonCode.P20_P30_GAP_HIGH })
    }

    /**
     * 验证 P30 与 P20 差距过大时触发平坦度告警并降低置信度：
     * 分布陡峭（P20=0.050W, P30=0.141W，相对差距 > 40% 且绝对差距 > 0.05W），
     * 触发 P20_P30_GAP_HIGH，置信度被严格降级至 LOW。
     */
    @Test
    fun testP30GapExcessiveDowngradesConfidenceAndAddsReasonCode() {
        val samples = mutableListOf<PowerDischargePoint>()
        val startTs = 10000000L
        val windowDurationMs = 60_000L

        // 构造 10 个窗口：
        // 3 个窗口处于 0.05W (P20 在此处)
        // 7 个窗口处于 0.18W (P30 在此处)
        for (w in 0 until 10) {
            val power = if (w < 3) 0.05f else 0.18f
            val baseTs = startTs + w * windowDurationMs
            samples.add(createPoint(baseTs + 10_000L, power))
            samples.add(createPoint(baseTs + 40_000L, power))
        }

        val result = StandbyBaselineEstimator.estimate(
            samples = samples,
            config = StandbyBaselineConfig(
                minWindowsForMediumConfidence = 10,
                baseWindowDurationMs = 60_000L,
                maxAllowedRelativeQuantileGap = 0.40,
                maxAllowedAbsoluteQuantileGapWatts = 0.05
            )
        )

        assertTrue("必须包含 P20_P30_GAP_HIGH 原因码", result.reasonCodes.contains(StandbyReasonCode.P20_P30_GAP_HIGH))
        assertEquals("平台不稳，置信度降级至 LOW", StandbyConfidenceLevel.LOW, result.confidence)
        assertTrue("绝对差必须大于 0.05W", result.quantileGapW!! > 0.05)
        assertTrue("相对差必须大于 40%", result.relativeQuantileGap!! > 0.40)
    }

    /**
     * 验证有效窗口数严重不足 (< 3 个窗口) 时，返回 UNAVAILABLE 置信度并包含原因码。
     */
    @Test
    fun testInsufficientWindowsReturnsUnavailable() {
        val samples = listOf(
            createPoint(1000000L, 0.05f),
            createPoint(1020000L, 0.05f) // 仅有 1 个 60 秒窗口
        )

        val result = StandbyBaselineEstimator.estimate(
            samples = samples,
            config = StandbyBaselineConfig(
                baseWindowDurationMs = 60_000L,
                minWindowsForLowConfidence = 3
            )
        )

        assertEquals("窗口不足必须返回 UNAVAILABLE", StandbyConfidenceLevel.UNAVAILABLE, result.confidence)
        assertEquals("回退至默认先验", StandbyBaselineSource.DEFAULT_STANDBY_PRIOR, result.source)
        assertTrue("原因码必须包含 INSUFFICIENT_CANDIDATE_WINDOWS", result.reasonCodes.contains(StandbyReasonCode.INSUFFICIENT_CANDIDATE_WINDOWS))
    }

    /**
     * 验证 60 秒窗口内秒级短暂唤醒脉冲（如 3 秒 2.0W 突发）不会破坏窗口代表功率中位数，
     * 证明采用窗口内中位数相比均值具备卓越的抗秒级脉冲能力。
     */
    @Test
    fun testShortBurstWakeLockDoesNotRuinMedianWindowRepresentative() {
        val samples = mutableListOf<PowerDischargePoint>()
        val startTs = 11000000L

        // 构造 3 个窗口，每个窗口内 55 秒处于 0.04W，最后 5 秒出现 2.0W 瞬态脉冲
        for (w in 0 until 3) {
            val baseTs = startTs + w * 60_000L
            for (sec in 0 until 55 step 5) {
                samples.add(createPoint(baseTs + sec * 1000L, 0.04f))
            }
            // 瞬态秒级尖峰
            samples.add(createPoint(baseTs + 56_000L, 2.0f))
            samples.add(createPoint(baseTs + 58_000L, 2.0f))
        }

        val result = StandbyBaselineEstimator.estimate(
            samples = samples,
            config = StandbyBaselineConfig(
                baseWindowDurationMs = 60_000L,
                minWindowsForLowConfidence = 3
            )
        )

        // 窗口内稳健中位数完全过滤掉 2.0W 瞬时尖峰，基线牢牢锁定在 0.04W
        assertEquals("基线功率必须稳定在 0.04W 纯净底噪，不受瞬时脉冲污染", 0.04, result.baselinePowerWatts, 0.005)
    }

    @Test
    fun testGenerateScenarioNumericalComparison() {
        println("=== 常见工况数值表现对比测试 START ===")

        fun evaluateScenario(name: String, samples: List<PowerDischargePoint>, config: StandbyBaselineConfig = StandbyBaselineConfig()) {
            val resP15 = StandbyBaselineEstimator.estimate(samples, config = config.copy(baselineQuantile = 0.15, checkQuantile = 0.25))
            val resP10 = StandbyBaselineEstimator.estimate(samples, config = config.copy(baselineQuantile = 0.10, checkQuantile = 0.20))
            val resP20 = StandbyBaselineEstimator.estimate(samples, config = config.copy(baselineQuantile = 0.20, checkQuantile = 0.30))
            val resP30 = StandbyBaselineEstimator.estimate(samples, config = config.copy(baselineQuantile = 0.30, checkQuantile = 0.40))
            val resP50 = StandbyBaselineEstimator.estimate(samples, config = config.copy(baselineQuantile = 0.50, checkQuantile = 0.60))
            val rawMin = samples.minOfOrNull { it.powerWatts } ?: 0f

            println("【$name】")
            println("  原始极小值 RawMin: ${String.format(java.util.Locale.US, "%.4f", rawMin)} W")
            println("  P10 基线: ${String.format(java.util.Locale.US, "%.4f", resP10.baselinePowerWatts)} W | 校验差(P20-P10): ${String.format(java.util.Locale.US, "%.4f", resP10.quantileGapW ?: 0.0)} W (相对: ${String.format(java.util.Locale.US, "%.1f", (resP10.relativeQuantileGap ?: 0.0) * 100)}%) | 置信度: ${resP10.confidence}")
            println("  P15 基线: ${String.format(java.util.Locale.US, "%.4f", resP15.baselinePowerWatts)} W | 校验差(P25-P15): ${String.format(java.util.Locale.US, "%.4f", resP15.quantileGapW ?: 0.0)} W (相对: ${String.format(java.util.Locale.US, "%.1f", (resP15.relativeQuantileGap ?: 0.0) * 100)}%) | 置信度: ${resP15.confidence}")
            println("  P20 基线: ${String.format(java.util.Locale.US, "%.4f", resP20.baselinePowerWatts)} W")
            println("  P30 基线: ${String.format(java.util.Locale.US, "%.4f", resP30.baselinePowerWatts)} W")
            println("  P50 基线: ${String.format(java.util.Locale.US, "%.4f", resP50.baselinePowerWatts)} W")
            println("  P15 原因码: ${resP15.reasonCodes}")
            println("  8h外推能耗对比: RawMin=${String.format(java.util.Locale.US, "%.3f", rawMin * 8.0)}Wh | P10=${String.format(java.util.Locale.US, "%.3f", resP10.baselinePowerWatts * 8.0)}Wh | P15=${String.format(java.util.Locale.US, "%.3f", resP15.baselinePowerWatts * 8.0)}Wh | P30=${String.format(java.util.Locale.US, "%.3f", resP30.baselinePowerWatts * 8.0)}Wh | P50=${String.format(java.util.Locale.US, "%.3f", resP50.baselinePowerWatts * 8.0)}Wh")
            println("--------------------------------------------------------------------------------")
        }

        // 工况 1：纯净稳态深睡（30 窗口，40~45mW，单点噪点 0.005W）
        val s1 = mutableListOf<PowerDischargePoint>()
        for (w in 0 until 30) {
            val p = 0.040f + (w % 5) * 0.001f
            s1.add(createPoint(w * 60_000L + 10_000L, p))
            s1.add(createPoint(w * 60_000L + 40_000L, p))
        }
        s1.add(createPoint(15_000L, 0.005f))
        evaluateScenario("工况 1：纯净稳态深睡 (飞行模式/夜间极度静默，含单点噪声)", s1)
        val r1 = StandbyBaselineEstimator.estimate(s1)
        assertEquals(0.0408, r1.baselinePowerWatts, 0.001)
        assertEquals(StandbyConfidenceLevel.HIGH, r1.confidence)
        assertTrue(r1.source == StandbyBaselineSource.MEASURED_TRAPEZOIDAL_INTEGRAL || r1.source == StandbyBaselineSource.MEASURED_WINDOW_P20)

        // 工况 2：偶发轻度唤醒（30 窗口：25 个 48mW，3 个 200mW，2 个 400mW）
        val s2 = mutableListOf<PowerDischargePoint>()
        for (w in 0 until 30) {
            val p = when {
                w < 25 -> 0.048f + (w % 3) * 0.001f
                w < 28 -> 0.200f
                else -> 0.400f
            }
            s2.add(createPoint(w * 60_000L + 10_000L, p))
            s2.add(createPoint(w * 60_000L + 40_000L, p))
        }
        evaluateScenario("工况 2：偶发轻度唤醒 (日常夜间微信心跳/推送，唤醒占比 ~16%)", s2)
        val r2 = StandbyBaselineEstimator.estimate(s2)
        assertEquals(0.048, r2.baselinePowerWatts, 0.001)
        assertEquals(StandbyConfidenceLevel.HIGH, r2.confidence)

        // 工况 3：中度频繁唤醒 (20 窗口：4 个 50mW，5 个 220mW，11 个 450mW)
        val s3 = mutableListOf<PowerDischargePoint>()
        for (w in 0 until 20) {
            val p = when {
                w < 4 -> 0.050f
                w < 9 -> 0.220f
                else -> 0.450f
            }
            s3.add(createPoint(w * 60_000L + 10_000L, p))
            s3.add(createPoint(w * 60_000L + 40_000L, p))
        }
        evaluateScenario("工况 3：中度频繁唤醒 (弱网重连/频繁邮件同步，深睡仅占 20%)", s3)
        val r3 = StandbyBaselineEstimator.estimate(s3)
        assertEquals(0.186, r3.baselinePowerWatts, 0.01)
        assertTrue(r3.source == StandbyBaselineSource.MEASURED_TRAPEZOIDAL_INTEGRAL || r3.source == StandbyBaselineSource.MEASURED_WINDOW_P20)

        // 工况 4：严重污染后台活跃 (20 窗口：全部 350mW ~ 550mW)
        val s4 = mutableListOf<PowerDischargePoint>()
        for (w in 0 until 20) {
            val p = 0.350f + (w % 5) * 0.040f
            s4.add(createPoint(w * 60_000L + 10_000L, p))
            s4.add(createPoint(w * 60_000L + 40_000L, p))
        }
        evaluateScenario("工况 4：严重污染后台活跃 (息屏播放音频/热点共享，全程无深睡)", s4)
        val r4 = StandbyBaselineEstimator.estimate(s4)
        assertEquals(StandbyBaselineSource.DEFAULT_STANDBY_PRIOR, r4.source)
        assertEquals(0.150, r4.baselinePowerWatts, 0.001)
        assertTrue(r4.reasonCodes.contains(StandbyReasonCode.SUSPICIOUS_POLLUTION_DETECTED))

        // 工况 5：超长大断层非连续挂起 (10 窗口 50mW，随后 7.5 小时大断层，断层后醒来点 100mW)
        val s5 = mutableListOf<PowerDischargePoint>()
        for (w in 0 until 10) {
            s5.add(createPoint(w * 60_000L + 10_000L, 0.050f))
            s5.add(createPoint(w * 60_000L + 40_000L, 0.050f))
        }
        s5.add(createPoint(10 * 60_000L + 7L * 3600_000L, 0.100f))
        evaluateScenario("工况 5：超长大断层挂起 (Android 深度 Doze 停摆 7 小时无采样)", s5)
        val r5 = StandbyBaselineEstimator.estimate(s5)
        assertEquals(0.050, r5.baselinePowerWatts, 0.001)
        assertTrue(r5.reasonCodes.contains(StandbyReasonCode.LOW_DATA_COVERAGE))

        // 工况 6：微唤醒抖动过渡阶梯 (10 窗口：从 50mW 平缓爬升到 150mW)
        val s6 = mutableListOf<PowerDischargePoint>()
        for (w in 0 until 10) {
            val p = 0.050f + w * 0.010f // 0.05, 0.06, ..., 0.14
            s6.add(createPoint(w * 60_000L + 10_000L, p))
            s6.add(createPoint(w * 60_000L + 40_000L, p))
        }
        evaluateScenario("工况 6：微唤醒斜坡过渡 (SoC 温度上升或微负载平缓爬坡)", s6)
        val r6 = StandbyBaselineEstimator.estimate(s6)
        assertEquals(0.068, r6.baselinePowerWatts, 0.005)
        assertTrue(r6.quantileGapW!! <= 0.015)

        println("=== 常见工况数值表现对比测试 END ===")
    }
}
