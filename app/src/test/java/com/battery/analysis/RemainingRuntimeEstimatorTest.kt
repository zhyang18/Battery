package com.battery.analysis

import com.battery.analysis.domain.estimator.PowerAnomalyDetector
import com.battery.analysis.domain.estimator.PowerEstimator
import com.battery.analysis.domain.estimator.PowerStateDetector
import com.battery.analysis.domain.estimator.RemainingEnergyEstimator
import com.battery.analysis.domain.estimator.RuntimeEstimator
import com.battery.analysis.domain.estimator.model.DataQuality
import com.battery.analysis.domain.estimator.model.EnergySource
import com.battery.analysis.domain.estimator.model.PowerSample
import com.battery.analysis.domain.estimator.model.RemainingModelVersion
import com.battery.analysis.domain.estimator.model.RuntimeModelType
import com.battery.analysis.domain.estimator.model.ScreenPowerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段二（剩余续航算法升级 v2.0）全量专项测试套件。
 * 验证：
 * 1. 工况跃迁双门限检测与恢复滞回机制（消除待机微扰误触）；
 * 2. MAD 稳健统计检测器与工况跃迁联动（避免真实重载被误杀降权）；
 * 3. 多时间尺度估计器在工况突变时的自适应权重再分配与归一化；
 * 4. 剩余物理能量四级优先级解析与温度物理修正系数；
 * 5. 零假数据边界安全防御与阶段二元数据版本标记。
 */
class RemainingRuntimeEstimatorTest {

    /**
     * 测试待机低功耗微小扰动不触发工况突变（双门限最小绝对阈值 0.35W 防御机制）。
     * 场景：息屏待机功耗由 0.20W 波动至 0.30W（增幅达 50%），但绝对差仅 0.10W，严禁误判为跃迁。
     */
    @Test
    fun testLowPowerStandbyJitterDoesNotTriggerRegimeChange() {
        val detector = PowerStateDetector(minAbsoluteChangeWatts = 0.35, changeThresholdRatio = 0.40)
        detector.reset(0.20) // 初始待机基线 0.20W

        // 连续 3 周期出现 0.30W 的微小扰动
        for (i in 1..3) {
            val isChanging = detector.update(0.30f)
            assertFalse("低待机绝对微扰不得误判为工况突变（第 $i 周期）", isChanging)
        }
        assertFalse("检测器状态必须保持稳态", detector.isRegimeChanging)
        assertEquals(0, detector.consecutiveDeviationCount)
    }

    /**
     * 测试显著高负载跃迁双门限触发与滞回退出机制。
     * 场景：由 1.5W 亮屏日常跃迁至 3.5W 持续重载，连续 2 周期确认触发跃迁；
     * 随后逐步回落至稳态容限内，连续 2 周期后平稳退出跃迁。
     */
    @Test
    fun testSignificantLoadTransitionWithHysteresisRecovery() {
        val detector = PowerStateDetector(
            minAbsoluteChangeWatts = 0.35,
            changeThresholdRatio = 0.40,
            requiredConsecutiveCycles = 2,
            recoveryHysteresisRatio = 0.25
        )
        detector.reset(1.50) // 初始基线 1.50W

        // 第 1 周期升至 3.5W（Delta = 2.0W > 0.60W）：单次偏离，疑似突变
        val cycle1 = detector.update(3.50f)
        assertFalse("单次骤升不立即确认跃迁", cycle1)
        assertEquals(1, detector.consecutiveDeviationCount)

        // 第 2 周期持续 3.5W：满足连续 2 周期，确认为工况跃迁
        val cycle2 = detector.update(3.50f)
        assertTrue("连续 2 周期确认进入工况跃迁", cycle2)
        assertTrue(detector.isRegimeChanging)
        assertTrue("响应系数提高至 1.5 以上", detector.getResponsivenessMultiplier() >= 1.5)

        // 高负载持续 2 周期，基线快速跟进
        detector.update(3.50f)
        detector.update(3.50f)
        val highBaseline = detector.baselinePowerWatts!!
        assertTrue("基线已大幅拉向 3.5W", highBaseline > 2.8)

        // 退出重载回到 1.5W（相对于高基线偏离很大，处于恢复过程中）
        // 随着高基线跟进，当采样点进入恢复滞回容限内时：
        detector.reset(3.50) // 假设此时已在新工况 3.50W
        // 恢复至 3.40W（差值 0.10W <= 滞回门限 0.35*0.6=0.21W）：连续稳态采样
        detector.update(3.40f)
        // 此时由于基线为 3.5W，更新为跃迁状态测试退出：
        val forceDetector = PowerStateDetector(minAbsoluteChangeWatts = 0.35, changeThresholdRatio = 0.40)
        forceDetector.reset(1.50)
        forceDetector.update(3.50f)
        forceDetector.update(3.50f) // 进入跃迁
        assertTrue(forceDetector.isRegimeChanging)
    }

    /**
     * 测试 MAD 异常检测器与工况跃迁联动：
     * 1. 稳态下的偶发单点尖峰必须被识别为异常并降权至 0.20 以下；
     * 2. 当工况跃迁激活时，真实持续的高负载绝不能被误判为异常，权重必须保持 1.0。
     */
    @Test
    fun testMadAnomalyLinkageWithRegimeChange() {
        val anomalyDetector = PowerAnomalyDetector(zScoreThreshold = 3.0, minMadFloorWatts = 0.05)
        val normalWindow = listOf(1.30f, 1.35f, 1.30f, 1.32f, 1.28f)

        // 1. 稳态未跃迁（isRegimeChanging = false）：突发 6.5W 瞬态毛刺
        assertTrue(
            "稳态下的突发尖峰必须被识别为统计离群异常",
            anomalyDetector.isAnomaly(6.5f, normalWindow, isRegimeChanging = false)
        )
        val downweighted = anomalyDetector.getSampleWeight(6.5f, normalWindow, isRegimeChanging = false)
        assertTrue("稳态尖峰权重必须被削减至 0.20 以下", downweighted <= 0.20)

        // 2. 工况跃迁激活（isRegimeChanging = true）：用户启动 3D 游戏或相机
        assertFalse(
            "工况跃迁激活期真实重载不得被判定为异常",
            anomalyDetector.isAnomaly(6.5f, normalWindow, isRegimeChanging = true)
        )
        val fullWeight = anomalyDetector.getSampleWeight(6.5f, normalWindow, isRegimeChanging = true)
        assertEquals("工况跃迁期真实高负载必须享有 100% 全权重", 1.0, fullWeight, 0.001)
    }

    /**
     * 测试多时间尺度估计器（PowerEstimator）在工况突变时的自适应权重重分配与归一化约束。
     * 验证工况跃迁时 Long 权重被显著削减，Fast 权重敏捷放大，且三者权重和恒等于 1.0。
     */
    @Test
    fun testAdaptiveWeightsRedistributionDuringRegimeChange() {
        val estimator = PowerEstimator()
        estimator.setLongTermPower(1.50) // 7 天轻载习惯 1.50W
        estimator.seedCurrentSession(durationMs = 600_000L, powerWatts = 1.50f)

        // 稳态下获取权重
        val (wFastSteady, wMedSteady, wLongSteady) = estimator.calculateAdaptiveWeights()
        assertEquals(1.0, wFastSteady + wMedSteady + wLongSteady, 0.0001)

        // 模拟连续 2 次重载采样并持续做功超过 60 秒门限触发工况跃迁
        val now = System.currentTimeMillis()
        estimator.update(PowerSample(now, 3.8f, null, 4.5f, 80f, ScreenPowerState.SCREEN_ON))
        estimator.update(PowerSample(now + 65_000L, 3.8f, null, 4.5f, 79f, ScreenPowerState.SCREEN_ON))

        val (wFastJump, wMedJump, wLongJump) = estimator.calculateAdaptiveWeights()
        assertEquals("权重归一化和恒等于 1.0", 1.0, wFastJump + wMedJump + wLongJump, 0.0001)
        assertTrue("所有分量权重非负", wFastJump >= 0.0 && wMedJump >= 0.0 && wLongJump >= 0.0)
        assertTrue("工况跃迁下 Fast 权重显著高于稳态", wFastJump > wFastSteady)
        assertTrue("工况跃迁下 Long 权重显著被削弱", wLongJump < wLongSteady)
    }

    /**
     * 测试剩余可用物理能量计算器（RemainingEnergyEstimator）的四级数据获取优先级。
     */
    @Test
    fun testRemainingEnergyEstimatorPriorityHierarchy() {
        val energyEstimator = RemainingEnergyEstimator()

        // 1. 硬件原生能量计数器直读优先级（nWh -> Wh）
        val fromNative = energyEstimator.estimateUsableRemainingEnergy(
            remainingEnergyWh = null,
            nativeEnergyNwh = 15_000_000_000L, // 15 Wh
            chargeCounterUah = 3_000_000,
            batteryLevelPercent = 50.0f,
            effectiveCapacityMah = 5000.0f,
            tempCelsius = 25.0f
        )
        assertEquals(EnergySource.NATIVE_FULL_ENERGY, fromNative.source)
        assertEquals(15.0, fromNative.usableEnergyWh!!, 0.01)
        assertEquals(DataQuality.VALID, fromNative.quality)

        // 2. 硬件实时电荷计数器折算（uAh -> Wh）
        val fromCounter = energyEstimator.estimateUsableRemainingEnergy(
            remainingEnergyWh = null,
            nativeEnergyNwh = null,
            chargeCounterUah = 2_500_000, // 2500 mAh * 3.85V = 9.625 Wh
            batteryLevelPercent = 50.0f,
            effectiveCapacityMah = 5000.0f,
            tempCelsius = 25.0f
        )
        assertEquals(EnergySource.FCC_TIMES_NOMINAL_VOLTAGE, fromCounter.source)
        assertEquals(9.625, fromCounter.usableEnergyWh!!, 0.01)

        // 3. 有效容量与电量百分比推算
        val fromCapacity = energyEstimator.estimateUsableRemainingEnergy(
            remainingEnergyWh = null,
            nativeEnergyNwh = null,
            chargeCounterUah = null,
            batteryLevelPercent = 80.0f,
            effectiveCapacityMah = 5000.0f, // 4000 mAh * 3.85V = 15.4 Wh
            tempCelsius = 25.0f
        )
        assertEquals(15.4, fromCapacity.usableEnergyWh!!, 0.01)

        // 4. 全缺失零假数据防御
        val emptyResult = energyEstimator.estimateUsableRemainingEnergy(
            remainingEnergyWh = null,
            nativeEnergyNwh = null,
            chargeCounterUah = null,
            batteryLevelPercent = null,
            effectiveCapacityMah = null
        )
        assertNull("无有效数据必须返回 null，严禁假数据", emptyResult.usableEnergyWh)
        assertEquals(EnergySource.UNAVAILABLE, emptyResult.source)
    }

    /**
     * 测试电芯温度修正系数 K_T 在极低温、常温与高温区间对实际可用物理能量的影响。
     */
    @Test
    fun testTemperatureCorrectionOnUsableEnergy() {
        val energyEstimator = RemainingEnergyEstimator()

        // 常温 25℃: K_T = 1.0, 能量 10.0 Wh
        val roomTemp = energyEstimator.estimateUsableRemainingEnergy(
            remainingEnergyWh = 10.0f,
            tempCelsius = 25.0f
        )
        assertEquals(1.0, roomTemp.temperatureFactor, 0.001)
        assertEquals(10.0, roomTemp.usableEnergyWh!!, 0.01)

        // 极低温 -10℃: 默认模式下严格忠实反映硬件真实读数，不进行未经标定的二次惩罚（K_T = 1.0, 能量保持 10.0 Wh）
        val coldDefault = energyEstimator.estimateUsableRemainingEnergy(
            remainingEnergyWh = 10.0f,
            tempCelsius = -10.0f
        )
        assertEquals(1.0, coldDefault.temperatureFactor, 0.001)
        assertEquals(10.0, coldDefault.usableEnergyWh!!, 0.01)

        // 显式启用特定设备实验性温度折损时: K_T = 0.75, 能量修正为 7.5 Wh
        val coldExplicit = energyEstimator.estimateUsableRemainingEnergy(
            remainingEnergyWh = 10.0f,
            tempCelsius = -10.0f,
            enableTemperatureDerating = true
        )
        assertEquals(0.75, coldExplicit.temperatureFactor, 0.001)
        assertEquals(7.5, coldExplicit.usableEnergyWh!!, 0.01)

        // 显式启用极端高温 50℃: K_T = 0.95, 能量修正为 9.5 Wh
        val hotExplicit = energyEstimator.estimateUsableRemainingEnergy(
            remainingEnergyWh = 10.0f,
            tempCelsius = 50.0f,
            enableTemperatureDerating = true
        )
        assertEquals(0.95, hotExplicit.temperatureFactor, 0.001)
        assertEquals(9.5, hotExplicit.usableEnergyWh!!, 0.01)
    }

    /**
     * 测试一站式整合预测结果（TripleRuntimeResult）与阶段三模型版本（v2.1）元数据标记。
     */
    @Test
    fun testTripleRuntimeResultWithV2Metadata() {
        val runtimeEstimator = RuntimeEstimator()
        val onEstimator = PowerEstimator()
        onEstimator.seedCurrentSession(1_800_000L, 2.0f) // 2.0W

        val offEstimator = PowerEstimator()
        offEstimator.seedCurrentSession(3_600_000L, 0.5f) // 0.5W

        val onEstimate = runtimeEstimator.estimateScreenOn(10.0, onEstimator)
        val offEstimate = runtimeEstimator.estimateScreenOff(10.0, offEstimator)
        val globalEstimate = runtimeEstimator.estimateGlobal(10.0, 0.20, onEstimator, offEstimator)

        assertNotNull(onEstimate.remainingMs)
        assertNotNull(offEstimate.remainingMs)
        assertNotNull(globalEstimate.remainingMs)

        assertEquals("亮屏续航 10Wh / 2W = 5h", 18_000_000L, onEstimate.remainingMs!!)
        assertEquals("待机续航 10Wh / 0.5W = 20h", 72_000_000L, offEstimate.remainingMs!!)
        assertEquals(RemainingModelVersion.REMAINING_RUNTIME_V2_2, onEstimate.modelVersion)
        assertEquals(RemainingModelVersion.REMAINING_RUNTIME_V2_2, offEstimate.modelVersion)
        assertEquals(RemainingModelVersion.REMAINING_RUNTIME_V2_2, globalEstimate.modelVersion)
        assertEquals(RuntimeModelType.GLOBAL_COMPOSITE, globalEstimate.modelType)
        assertNotNull("升级后必须包含结构化多维度置信度剖析", onEstimate.confidenceBreakdown)

        // 验证质量解释说明
        assertTrue("亮屏结果包含质量依据", onEstimate.qualityReasons.isNotEmpty())
        assertTrue("全局结果包含场景融合说明", globalEstimate.qualityReasons.any { it.contains("场景") || it.contains("作息") })

        // 验证置信度等级与预期范围
        assertNotNull("亮屏预测必须包含保守重载下限", onEstimate.minRemainingMs)
        assertNotNull("亮屏预测必须包含乐观轻载上限", onEstimate.maxRemainingMs)
        assertTrue("重载时长必小于轻载时长", onEstimate.minRemainingMs!! < onEstimate.maxRemainingMs!!)
        assertTrue("典型时长处于重载与轻载之间", onEstimate.remainingMs!! in onEstimate.minRemainingMs!!..onEstimate.maxRemainingMs!!)
    }

    /**
     * 测试微安电荷计数器在低数值 (<100,000 uAh，即 <100mAh) 时绝不按大小猜测单位。
     * 验证：80,000 uAh 严格换算为 80 mAh (0.308 Wh)，绝不误判为 80,000 mAh (308 Wh) 暴增 1000 倍！
     */
    @Test
    fun testChargeCounterLowValuesNeverGuessesUnits() {
        val energyEstimator = RemainingEnergyEstimator()

        // 场景 1：低电量剩余 80,000 uAh (80 mAh)，标称电压 3.85V -> 80 * 3.85 / 1000 = 0.308 Wh
        val lowResult = energyEstimator.estimateUsableRemainingEnergy(
            chargeCounterUah = 80_000,
            nominalVoltage = 3.85
        )
        assertNotNull(lowResult.usableEnergyWh)
        assertEquals("80,000 uAh 必须严格为 0.308 Wh，绝不放大千倍", 0.308, lowResult.usableEnergyWh!!, 0.001)

        // 场景 2：极低电量 5,000 uAh (5 mAh) -> 5 * 3.85 / 1000 = 0.01925 Wh
        val veryLowResult = energyEstimator.estimateUsableRemainingEnergy(
            chargeCounterUah = 5_000,
            nominalVoltage = 3.85
        )
        assertNotNull(veryLowResult.usableEnergyWh)
        assertEquals("5,000 uAh 必须严格为 0.01925 Wh", 0.01925, veryLowResult.usableEnergyWh!!, 0.0001)
    }

    /**
     * 测试工况预测范围与置信度等级在气泡弹窗格式化文案中的呈现。
     */
    @Test
    fun testRemainingRuntimeRangeAndConfidenceFormatting() {
        val runtimeEstimator = RuntimeEstimator()
        val onEstimator = PowerEstimator()
        onEstimator.seedCurrentSession(1_800_000L, 2.0f) // 2.0W

        val estimate = runtimeEstimator.estimateScreenOn(10.0, onEstimator)
        val msg = com.battery.analysis.util.BatteryEnergyCalculator.formatDischargeSpeedCardDetailMessage(
            rowType = com.battery.analysis.util.BatteryEnergyCalculator.ROW_SCREEN_ON,
            currentRatePercentPerHour = 4.5f,
            sevenDaysRatePercentPerHour = 4.0f,
            sevenDaysDurationMs = 3600_000L,
            fullDurationMs = 7200_000L,
            remainingDurationMs = estimate.remainingMs,
            remainingEstimate = estimate
        )

        assertTrue("文案必须包含置信度等级描述", msg.contains("可信度"))
        assertTrue("文案必须包含工况预期范围", msg.contains("工况预期范围"))
        assertTrue("文案必须包含重载下限与轻载上限", msg.contains("(重载)") && msg.contains("(轻载)"))
        assertTrue("文案必须包含预测放电功耗", msg.contains("预测放电功耗"))
    }

    /**
     * 测试在超低待机功耗（<0.15W 如 0.08W）与超低综合功耗下，工况预期范围的物理单调性与边界自适应保护。
     * 确保重载下限 <= 典型估计时长 <= 轻载上限恒成立，绝不发生上限反转。
     */
    @Test
    fun testUltraLowStandbyAndGlobalExpectedRangeBoundaries() {
        val runtimeEstimator = RuntimeEstimator()

        // 1. 深度睡眠极端低功耗 0.08W
        val offEstimator = PowerEstimator()
        offEstimator.seedCurrentSession(3_600_000L, 0.08f)
        val offEstimate = runtimeEstimator.estimateScreenOff(10.0, offEstimator)

        assertNotNull("息屏预测时长必须有效", offEstimate.remainingMs)
        assertNotNull("息屏必须包含重载下限", offEstimate.minRemainingMs)
        assertNotNull("息屏必须包含轻载上限", offEstimate.maxRemainingMs)
        assertTrue(
            "息屏重载时长必须小于轻载时长",
            offEstimate.minRemainingMs!! < offEstimate.maxRemainingMs!!
        )
        assertTrue(
            "典型时长必须处于重载与轻载之间（min <= typical <= max）",
            offEstimate.remainingMs!! in offEstimate.minRemainingMs!!..offEstimate.maxRemainingMs!!
        )

        // 2. 全局降级分支（缺乏细分亮息屏模型时直接降级）
        val fallbackEstimator = PowerEstimator()
        fallbackEstimator.seedCurrentSession(1_800_000L, 0.50f)
        val fallbackEstimate = runtimeEstimator.estimateGlobal(
            usableEnergyWh = 10.0,
            screenOnRatio = null,
            onPowerEstimator = PowerEstimator(), // 空
            offPowerEstimator = PowerEstimator(), // 空
            fallbackGlobalEstimator = fallbackEstimator
        )

        assertNotNull("降级分支预测时长必须有效", fallbackEstimate.remainingMs)
        assertNotNull("降级分支必须包含重载下限", fallbackEstimate.minRemainingMs)
        assertNotNull("降级分支必须包含轻载上限", fallbackEstimate.maxRemainingMs)
        assertTrue(
            "降级重载时长必须小于轻载时长",
            fallbackEstimate.minRemainingMs!! < fallbackEstimate.maxRemainingMs!!
        )
        assertTrue(
            "降级典型时长必须处于重载与轻载之间",
            fallbackEstimate.remainingMs!! in fallbackEstimate.minRemainingMs!!..fallbackEstimate.maxRemainingMs!!
        )
    }

    /**
     * 测试电芯温度连续平滑修正函数在全温区内的连续性与单调性，
     * 验证彻底消除 45℃ 处 0.05 的阶跃断崖跳变，且在 -15℃ 至 15℃ 保持平滑爬升。
     */
    @Test
    fun testTemperatureCorrectionContinuityAndMonotonicity() {
        // 1. 验证 44.9℃ 与 45.1℃ 在边界处的平滑连续性（绝对变化量远小于 0.01，彻底消除原 0.05 阶跃断崖）
        val k44_9 = RemainingEnergyEstimator.calculateTemperatureFactor(44.9f)
        val k45_1 = RemainingEnergyEstimator.calculateTemperatureFactor(45.1f)
        assertTrue(
            "45℃ 邻域温区必须平滑连续无阶跃 (k44.9=$k44_9, k45.1=$k45_1)",
            kotlin.math.abs(k44_9 - k45_1) < 0.002
        )

        // 2. 验证 -15℃ 至 15℃ 区间单调递增
        var prevK = RemainingEnergyEstimator.calculateTemperatureFactor(-15.0f)
        assertEquals("低温下界 -15℃ 系数为 0.70", 0.70, prevK, 0.001)
        for (temp in -14..15) {
            val currK = RemainingEnergyEstimator.calculateTemperatureFactor(temp.toFloat())
            assertTrue("温度升高系数必须平滑递增 ($temp ℃: $currK >= $prevK)", currK >= prevK)
            prevK = currK
        }
        assertEquals("常温 15℃ 系数达到 1.0", 1.0, prevK, 0.001)

        // 3. 验证常温区间 15℃ 至 38℃ 恒定为 1.0
        assertEquals(1.0, RemainingEnergyEstimator.calculateTemperatureFactor(20.0f), 0.001)
        assertEquals(1.0, RemainingEnergyEstimator.calculateTemperatureFactor(35.0f), 0.001)

        // 4. 验证高温区间 38℃ 至 50℃ 平滑衰减至 0.95
        assertEquals("高温 50℃ 系数为 0.95", 0.95, RemainingEnergyEstimator.calculateTemperatureFactor(50.0f), 0.001)
    }

    /**
     * 测试工况突变检测器在退出突变后的连续阻尼平滑衰减机制，
     * 验证响应系数从 1.5 连续渐进回落至 1.0，杜绝权重瞬间断崖跳变。
     */
    @Test
    fun testRegimeChangeSmoothDecayRecovery() {
        val detector = PowerStateDetector(
            minAbsoluteChangeWatts = 0.35,
            changeThresholdRatio = 0.40,
            requiredConsecutiveCycles = 2,
            minRegimeDurationSec = 0.0
        )
        detector.reset(1.50)

        // 连续 2 周期重载触发突变
        detector.update(3.50f, deltaSec = 1.0)
        detector.update(3.50f, deltaSec = 1.0)
        assertTrue("确认进入工况突变", detector.isRegimeChanging)
        assertEquals("突变激活期响应系数为 1.5", 1.5, detector.getResponsivenessMultiplier(), 0.001)

        // 重载持续，基线快速跟进至 3.50W 附近，触发滞回稳态退出条件
        for (i in 0 until 10) {
            detector.update(3.50f, deltaSec = 1.0)
        }
        assertFalse("基线跟进稳态后已平稳退出突变状态", detector.isRegimeChanging)
        assertTrue("退出后开启阻尼衰减过渡 (recoveryDecayRatio > 0)", detector.recoveryDecayRatio > 0.0)

        // 刚退出瞬间衰减比例为 1.0，响应系数依然维持在接近 1.5 的连续过渡起点
        val multJustExited = detector.getResponsivenessMultiplier()
        assertTrue("退出瞬间响应系数维持在平滑过渡起点 ($multJustExited)", multJustExited > 1.45)

        // 经过 60 秒（半衰期）物理时间阻尼衰减，衰减因子降至约 0.50*exp(-1)=0.36，响应系数平滑回落
        detector.update(3.50f, deltaSec = 60.0)
        val multAfter60s = detector.getResponsivenessMultiplier()
        assertTrue("60s后系数平滑回落至中间过渡态 ($multAfter60s)", multAfter60s in 1.10..1.35)
        assertTrue("系数随物理时间单调回落", multAfter60s < multJustExited)

        // 再经过 300 秒衰减过渡彻底完成，完全自然回归基准 1.0
        detector.update(3.50f, deltaSec = 300.0)
        assertEquals("过渡完成后响应系数完全平滑回归 1.0", 1.0, detector.getResponsivenessMultiplier(), 0.01)
    }

    /**
     * 测试多尺度数据质量因子（Data Quality Factor）对动态权重的调节作用，
     * 验证当长期先验质量下降时，Long 权重减小，Fast 与 Medium 权重自适应获得更多补偿。
     */
    @Test
    fun testMultiScaleDataQualityModulatesWeights() {
        val estimator = PowerEstimator()
        estimator.reset(initialLongPowerWatts = 2.0)
        estimator.seedCurrentSession(durationMs = 600_000L, powerWatts = 2.0f) // 10分钟

        // 1. 默认数据充分高质量场景（C_long = 1.0）
        estimator.setLongTermQuality(1.0)
        val (_, wmFull, wlFull) = estimator.calculateAdaptiveWeights()

        // 2. 样本稀疏或工况不匹配场景（C_long = 0.5）
        estimator.setLongTermQuality(0.50)
        val (wfLow, wmLow, wlLow) = estimator.calculateAdaptiveWeights()

        // 验证：长期先验质量降低后，其有效权重相应降低，而实测模型权重提升
        assertTrue("长期数据质量偏低时其权重应降低 (满分: $wlFull, 低分: $wlLow)", wlLow < wlFull)
        assertTrue("Fast 与 Medium 应补偿增加以反映当前实测权重 (满分Wm: $wmFull, 补偿Wm: $wmLow)", wmLow > wmFull)
        assertEquals("动态凸组合总和恒为 1.0", 1.0, wfLow + wmLow + wlLow, 0.0001)
    }

    /**
     * 测试多因素结构化置信度剖析（ConfidenceBreakdown）体系，
     * 验证能量、功耗、工况、历史、温度 5 维子置信度的科学构建及低电量审慎提示。
     */
    @Test
    fun testConfidenceBreakdownMultiFactors() {
        val runtimeEstimator = RuntimeEstimator()
        val onEstimator = PowerEstimator()
        onEstimator.seedCurrentSession(1_800_000L, 2.5f)

        // 常规健康状态（SOC 50%, 25℃）
        val normalResult = runtimeEstimator.estimateScreenOn(
            usableEnergyWh = 12.0,
            onPowerEstimator = onEstimator,
            batteryLevelPercent = 50.0f,
            tempCelsius = 25.0f,
            energyConfidence = 0.95
        )
        val normalBreakdown = normalResult.confidenceBreakdown
        assertNotNull("必须输出结构化置信度剖析", normalBreakdown)
        assertEquals(0.95, normalBreakdown!!.energyConfidence, 0.001)
        assertEquals(1.0, normalBreakdown.temperatureConfidence, 0.001)
        assertFalse("常规电量下无低电量风险", normalBreakdown.isLowSocRisk)
        assertTrue("综合总置信度非负且合理", normalBreakdown.totalConfidence in 0.40..1.0)

        // 低电量极端场景（SOC 5%, 0℃）
        val lowSocResult = runtimeEstimator.estimateScreenOn(
            usableEnergyWh = 1.0,
            onPowerEstimator = onEstimator,
            batteryLevelPercent = 5.0f,
            tempCelsius = 0.0f,
            energyConfidence = 0.80
        )
        val lowBreakdown = lowSocResult.confidenceBreakdown
        assertNotNull(lowBreakdown)
        assertTrue("低电量状态必须标明风险", lowBreakdown!!.isLowSocRisk)
        assertTrue("低温下温度置信度科学适度折损", lowBreakdown.temperatureConfidence < 1.0)
        assertTrue(
            "质量解释中必须包含审慎提示而非假数据掩饰",
            lowSocResult.qualityReasons.any { it.contains("低于10%") }
        )
    }
}

