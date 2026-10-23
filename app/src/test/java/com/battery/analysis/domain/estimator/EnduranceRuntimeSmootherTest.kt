package com.battery.analysis.domain.estimator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 电池剩余续航时间时序平滑器 [EnduranceRuntimeSmoother] 单元测试用例集。
 * 覆盖稳态短时刷新防抖、长时放电单调递减、重载突变敏捷收敛、轻载退出非对称阻尼与充电隔离等核心物理场景。
 */
class EnduranceRuntimeSmootherTest {

    private lateinit var smoother: EnduranceRuntimeSmoother

    /**
     * 测试套件初始化准备，每次测试前获取平滑器实例并重置所有时序状态。
     */
    @Before
    fun setUp() {
        smoother = EnduranceRuntimeSmoother.getInstance()
        smoother.reset()
    }

    /**
     * 测试同一分钟内连续刷新时，剩余续航严格跟随挂钟时间单调线性流逝，绝不发生暴涨跳变。
     * 针对图 1 与图 2 现场：原始模型从 10h47m 突变成 11h32m，平滑器必须刚性扣除流逝时间。
     */
    @Test
    fun testSameMinuteRefresh_strictlyMonotonicDecay_noSuddenJump() {
        val baseTs = 1700000000_000L
        val initialRemainingMs = (10 * 3600 + 47 * 60 + 34) * 1000L // 10h47m34s

        // 第一次刷新（确立基线）
        val res1 = smoother.smoothRemainingRuntime(
            dimension = EnduranceRuntimeSmoother.DIMENSION_SCREEN_ON,
            rawTargetRemainingMs = initialRemainingMs,
            currentTimestampMs = baseTs,
            isRegimeChanging = false,
            recoveryDecayRatio = 0.0,
            batteryLevelPercent = 93.0f
        )
        assertNotNull(res1)
        assertEquals(initialRemainingMs, res1)

        // 2 分 17 秒后（137 秒后，对应图 2），原始算法因瞬态扰动暴涨至 11h32m13s
        val jumpRawTargetMs = (11 * 3600 + 32 * 60 + 13) * 1000L // 11h32m13s (+45分钟)
        val elapsedMs = 137_000L
        val secondTs = baseTs + elapsedMs

        val res2 = smoother.smoothRemainingRuntime(
            dimension = EnduranceRuntimeSmoother.DIMENSION_SCREEN_ON,
            rawTargetRemainingMs = jumpRawTargetMs,
            currentTimestampMs = secondTs,
            isRegimeChanging = false,
            recoveryDecayRatio = 0.0,
            batteryLevelPercent = 93.0f
        )
        assertNotNull(res2)

        // 核心断言：平滑器必须严格扣除真实流逝的 137 秒，绝不被暴涨的 45 分钟带偏
        val expectedDecayedMs = initialRemainingMs - elapsedMs
        assertEquals("同一分钟内刷新必须严格线性自然衰减", expectedDecayedMs, res2)
    }

    /**
     * 测试使用 1 小时后（模拟图 3 到图 4），即使由于低负载导致瞬时模型目标变大，
     * 平滑器严格执行非对称时间阻尼，剩余续航绝不逆向增长。
     */
    @Test
    fun testOneHourUsage_asymmetricDamping_neverIncreases() {
        val baseTs = 1700000000_000L
        val initialRemainingMs = (10 * 3600 + 11 * 60) * 1000L // 10h11m (图3)

        smoother.smoothRemainingRuntime(
            dimension = EnduranceRuntimeSmoother.DIMENSION_SCREEN_ON,
            rawTargetRemainingMs = initialRemainingMs,
            currentTimestampMs = baseTs,
            isRegimeChanging = false,
            recoveryDecayRatio = 0.0,
            batteryLevelPercent = 86.0f
        )

        // 1 小时后（3600 秒后，图 4），电量降至 78%，但原始推算出现 10h19m（+8分钟）
        val oneHourLaterTs = baseTs + 3600_000L
        val invertedRawTargetMs = (10 * 3600 + 19 * 60) * 1000L // 10h19m

        val resultAfterOneHour = smoother.smoothRemainingRuntime(
            dimension = EnduranceRuntimeSmoother.DIMENSION_SCREEN_ON,
            rawTargetRemainingMs = invertedRawTargetMs,
            currentTimestampMs = oneHourLaterTs,
            isRegimeChanging = false,
            recoveryDecayRatio = 0.0,
            batteryLevelPercent = 78.0f
        )
        assertNotNull(resultAfterOneHour)

        // 核心断言：输出必须小于等于上一次的结果，彻底杜绝逆向倒涨
        assertTrue(
            "真实放电1小时后剩余续航必须单调递减，当前结果: $resultAfterOneHour, 初始值: $initialRemainingMs",
            resultAfterOneHour!! < initialRemainingMs
        )
    }

    /**
     * 测试重载工况突变激活时（启动大型 3D 游戏，isRegimeChanging = true），
     * 平滑器敏捷且连续地向重载低续航平滑收敛。
     */
    @Test
    fun testHeavyRegimeChange_agileConvergenceToLowEndurance() {
        val baseTs = 1700000000_000L
        val normalRemainingMs = 10 * 3600_000L // 10 小时

        smoother.smoothRemainingRuntime(
            dimension = EnduranceRuntimeSmoother.DIMENSION_SCREEN_ON,
            rawTargetRemainingMs = normalRemainingMs,
            currentTimestampMs = baseTs,
            isRegimeChanging = false
        )

        // 启动大型游戏持续重载做功 60 秒后，瞬时目标续航降至 4 小时
        val gamingTargetMs = 4 * 3600_000L // 4 小时
        val sixtySecLaterTs = baseTs + 60_000L

        val resultDuringGame = smoother.smoothRemainingRuntime(
            dimension = EnduranceRuntimeSmoother.DIMENSION_SCREEN_ON,
            rawTargetRemainingMs = gamingTargetMs,
            currentTimestampMs = sixtySecLaterTs,
            isRegimeChanging = true // 确立工况跃迁
        )
        assertNotNull(resultDuringGame)

        // 核心断言：平滑器敏捷向 4 小时方向快速收敛，且不发生瞬态断崖（介于 4 小时与 10 小时之间）
        assertTrue("必须快速向下收敛响应游戏高功耗", resultDuringGame!! < normalRemainingMs - 60_000L)
        assertTrue("必须平滑过渡而不直接断崖暴跌至4小时", resultDuringGame >= gamingTargetMs)
    }

    /**
     * 测试物理能量外源注入场景（电池被真实充入电量，电量增加）：
     * 平滑器感知到底层物理做功资本增加，自动以充入后的新电量与新目标确立新基线。
     */
    @Test
    fun testEnergyInjection_resetsBaselineAutomatically() {
        val baseTs = 1700000000_000L
        // 初始电量 50%，剩余续航 5 小时
        smoother.smoothRemainingRuntime(
            dimension = EnduranceRuntimeSmoother.DIMENSION_SCREEN_ON,
            rawTargetRemainingMs = 5 * 3600_000L,
            currentTimestampMs = baseTs,
            batteryLevelPercent = 50.0f
        )

        // 充入电量，电量上升到 80%，根据新电量算出的目标续航为 8 小时
        val afterChargedTs = baseTs + 1800_000L // 30分钟后
        val chargedTargetMs = 8 * 3600_000L

        val resultAfterCharged = smoother.smoothRemainingRuntime(
            dimension = EnduranceRuntimeSmoother.DIMENSION_SCREEN_ON,
            rawTargetRemainingMs = chargedTargetMs,
            currentTimestampMs = afterChargedTs,
            batteryLevelPercent = 80.0f // 电量被充高
        )

        // 核心断言：感知到能量注入，自动以充入后的物理做功资本确立新基线
        assertEquals("电量增加后必须以新物理能量确立基线", chargedTargetMs, resultAfterCharged)
    }

    /**
     * 测试短时外接连接（如电脑数据传输、车机连接，短时插拔但电量未增加）：
     * 平滑器时序记忆严密保留，绝不发生重置丢失记忆导致初始抖动。
     */
    @Test
    fun testShortPlugWithoutCharge_preservesSmoothingMemory() {
        val baseTs = 1700000000_000L
        val initialMs = 10 * 3600_000L

        smoother.smoothRemainingRuntime(
            dimension = EnduranceRuntimeSmoother.DIMENSION_SCREEN_ON,
            rawTargetRemainingMs = initialMs,
            currentTimestampMs = baseTs,
            batteryLevelPercent = 70.0f
        )

        // 10 秒后，短时插拔但电量依然是 70%（未充入电量）
        val tenSecLaterTs = baseTs + 10_000L
        val noisyTargetMs = 11 * 3600_000L // 瞬态扰动尝试跳高到 11 小时

        val result = smoother.smoothRemainingRuntime(
            dimension = EnduranceRuntimeSmoother.DIMENSION_SCREEN_ON,
            rawTargetRemainingMs = noisyTargetMs,
            currentTimestampMs = tenSecLaterTs,
            batteryLevelPercent = 70.0f // 电量未变
        )

        // 核心断言：时序记忆严密保留，严格扣除流逝的 10 秒自然时间，绝不跳高
        assertEquals(initialMs - 10_000L, result)
    }

    /**
     * 测试单向死锁陷阱修复（重载游戏切入低功耗看书持续数小时）：
     * 验证平滑器在短时间内保持死区单调扣除，但在长时间持续轻载稳态下能够以平缓变率跟进恢复，
     * 绝不发生一路死扣到 0 小时的缺陷。
     */
    @Test
    fun testMonotonicTrapAvoidance_prolongedReadingRecoversSmoothly() {
        val baseTs = 1700000000_000L
        // 游戏重载下的低续航 4 小时
        smoother.smoothRemainingRuntime(
            dimension = EnduranceRuntimeSmoother.DIMENSION_SCREEN_ON,
            rawTargetRemainingMs = 4 * 3600_000L,
            currentTimestampMs = baseTs,
            batteryLevelPercent = 80.0f
        )

        // 持续看书 1 小时后，瞬时目标为 15 小时（轻载），电量掉到 78%
        val oneHourTs = baseTs + 3600_000L
        val readingTargetMs = 15 * 3600_000L

        val resultAfterOneHour = smoother.smoothRemainingRuntime(
            dimension = EnduranceRuntimeSmoother.DIMENSION_SCREEN_ON,
            rawTargetRemainingMs = readingTargetMs,
            currentTimestampMs = oneHourTs,
            batteryLevelPercent = 78.0f
        )
        assertNotNull(resultAfterOneHour)

        // 核心断言 1：必须高于单纯扣减 1 小时的值 (3小时)，证明正在以极平缓变率向轻载收敛，死锁已被打破！
        assertTrue("必须打破死锁并高于纯自然衰减的3小时", resultAfterOneHour!! > 3 * 3600_000L)
        // 核心断言 2：上升速率严格受限，1小时最多上升 15 分钟，绝对不能直接阶跃跳到 15 小时
        assertTrue("上升速度受限，绝不直接跳跃至15小时", resultAfterOneHour <= 4 * 3600_000L + 900_000L)

        // 持续看书第 2 小时
        val twoHourTs = baseTs + 7200_000L
        val resultAfterTwoHours = smoother.smoothRemainingRuntime(
            dimension = EnduranceRuntimeSmoother.DIMENSION_SCREEN_ON,
            rawTargetRemainingMs = readingTargetMs,
            currentTimestampMs = twoHourTs,
            batteryLevelPercent = 76.0f
        )
        assertNotNull(resultAfterTwoHours)
        assertTrue("长时间轻载下平稳平滑渐近", resultAfterTwoHours!! > resultAfterOneHour)
    }

    /**
     * 测试大负载停止后电芯开路电压自然微小回弹（< 0.5%）不被误判为充能注入。
     */
    @Test
    fun testVoltageBounceSmallHysteresis_doesNotResetBaseline() {
        val baseTs = 1700000000_000L
        val initialMs = 10 * 3600_000L

        smoother.smoothRemainingRuntime(
            dimension = EnduranceRuntimeSmoother.DIMENSION_SCREEN_ON,
            rawTargetRemainingMs = initialMs,
            currentTimestampMs = baseTs,
            batteryLevelPercent = 80.0f
        )

        // 30 秒后，大负载停止导致电压空载回弹 0.2%（80.0% -> 80.2%）
        val bounceTs = baseTs + 30_000L
        val noisyTargetMs = 12 * 3600_000L

        val result = smoother.smoothRemainingRuntime(
            dimension = EnduranceRuntimeSmoother.DIMENSION_SCREEN_ON,
            rawTargetRemainingMs = noisyTargetMs,
            currentTimestampMs = bounceTs,
            batteryLevelPercent = 80.2f // 回弹 0.2%，未达到 0.5% 注入门限
        )

        // 核心断言：未达到 0.5% 门限，不触发重锚，依然严格扣除 30 秒自然时间
        assertEquals("微小电压回弹不重置基线，保持时序平滑", initialMs - 30_000L, result)
    }

    /**
     * 测试亮屏、息屏待机与全局综合三维度在平滑器内部的时序状态完全隔离，互不串扰。
     */
    @Test
    fun testTripleDimensions_independentStateIsolation() {
        val baseTs = 1700000000_000L
        val onMs = 6 * 3600_000L      // 亮屏 6 小时
        val offMs = 30 * 3600_000L    // 息屏待机 30 小时
        val globalMs = 14 * 3600_000L // 全局综合 14 小时

        // 分别建立三维度的基线状态
        val initialOn = smoother.smoothRemainingRuntime(
            dimension = EnduranceRuntimeSmoother.DIMENSION_SCREEN_ON,
            rawTargetRemainingMs = onMs,
            currentTimestampMs = baseTs,
            batteryLevelPercent = 80.0f
        )
        val initialOff = smoother.smoothRemainingRuntime(
            dimension = EnduranceRuntimeSmoother.DIMENSION_SCREEN_OFF,
            rawTargetRemainingMs = offMs,
            currentTimestampMs = baseTs,
            batteryLevelPercent = 80.0f
        )
        val initialGlobal = smoother.smoothRemainingRuntime(
            dimension = EnduranceRuntimeSmoother.DIMENSION_GLOBAL,
            rawTargetRemainingMs = globalMs,
            currentTimestampMs = baseTs,
            batteryLevelPercent = 80.0f
        )

        assertEquals(onMs, initialOn)
        assertEquals(offMs, initialOff)
        assertEquals(globalMs, initialGlobal)

        // 60 秒后，亮屏发生重载突变导致原始推算骤降至 3 小时，但息屏与全局原始物理输入保持稳定
        val secondTs = baseTs + 60_000L
        val heavyOnMs = 3 * 3600_000L

        val smoothedOn = smoother.smoothRemainingRuntime(
            dimension = EnduranceRuntimeSmoother.DIMENSION_SCREEN_ON,
            rawTargetRemainingMs = heavyOnMs,
            currentTimestampMs = secondTs,
            isRegimeChanging = true, // 亮屏重载突变
            batteryLevelPercent = 79.8f
        )
        val smoothedOff = smoother.smoothRemainingRuntime(
            dimension = EnduranceRuntimeSmoother.DIMENSION_SCREEN_OFF,
            rawTargetRemainingMs = offMs,
            currentTimestampMs = secondTs,
            isRegimeChanging = false, // 息屏不突变
            batteryLevelPercent = 79.8f
        )
        val smoothedGlobal = smoother.smoothRemainingRuntime(
            dimension = EnduranceRuntimeSmoother.DIMENSION_GLOBAL,
            rawTargetRemainingMs = globalMs,
            currentTimestampMs = secondTs,
            isRegimeChanging = true, // 全局跟随亮屏突变
            batteryLevelPercent = 79.8f
        )

        assertNotNull(smoothedOn)
        assertNotNull(smoothedOff)
        assertNotNull(smoothedGlobal)

        // 断言 1：亮屏敏捷响应重载，明显低于挂钟扣减后的 6h - 60s
        assertTrue("亮屏重载应敏捷下调", smoothedOn!! < onMs - 60_000L)

        // 断言 2：息屏待机状态完全隔离，未受亮屏突变影响，严格自然线性扣除 60 秒
        assertEquals("息屏待机严格自然线性衰减，免受亮屏突变污染", offMs - 60_000L, smoothedOff)

        // 断言 3：全局综合响应联动突变
        assertTrue("全局维度跟随工况突变敏捷下调", smoothedGlobal!! <= globalMs - 60_000L)
    }

    /**
     * 测试单维度重置接口仅重置指定维度的平滑记忆，其他维度的时序平滑不受任何影响。
     */
    @Test
    fun testResetDimension_onlyAffectsTargetDimension() {
        val baseTs = 1700000000_000L
        val onMs = 8 * 3600_000L
        val offMs = 40 * 3600_000L

        smoother.smoothRemainingRuntime(
            dimension = EnduranceRuntimeSmoother.DIMENSION_SCREEN_ON,
            rawTargetRemainingMs = onMs,
            currentTimestampMs = baseTs,
            batteryLevelPercent = 90.0f
        )
        smoother.smoothRemainingRuntime(
            dimension = EnduranceRuntimeSmoother.DIMENSION_SCREEN_OFF,
            rawTargetRemainingMs = offMs,
            currentTimestampMs = baseTs,
            batteryLevelPercent = 90.0f
        )

        // 单独重置亮屏维度
        smoother.resetDimension(EnduranceRuntimeSmoother.DIMENSION_SCREEN_ON)

        // 30 秒后刷新
        val secondTs = baseTs + 30_000L
        val newOnMs = 7 * 3600_000L
        val secondOn = smoother.smoothRemainingRuntime(
            dimension = EnduranceRuntimeSmoother.DIMENSION_SCREEN_ON,
            rawTargetRemainingMs = newOnMs,
            currentTimestampMs = secondTs,
            batteryLevelPercent = 90.0f
        )
        val secondOff = smoother.smoothRemainingRuntime(
            dimension = EnduranceRuntimeSmoother.DIMENSION_SCREEN_OFF,
            rawTargetRemainingMs = offMs,
            currentTimestampMs = secondTs,
            batteryLevelPercent = 90.0f
        )

        // 核心断言：亮屏因重置而以 newOnMs 为新基线重新确立；息屏保持历史时序并扣除 30 秒自然时间
        assertEquals("亮屏被单独重置后以新值确立基准", newOnMs, secondOn)
        assertEquals("息屏未被重置，保持历史平滑自然扣除30秒", offMs - 30_000L, secondOff)
    }

    /**
     * 测试动态能耗流速模型（重载加速流逝）：
     * 玩游戏 5 分钟（300秒），功耗倍率为 3.0x（如 6.0W / 2.0W），
     * 剩余续航应按 3 倍速线性加速消耗 15 分钟（从 8h 00m 降至 7h 45m），
     * 绝不因 5 分钟的短时游戏将整个续航断崖式拉崩至 3 小时。
     */
    @Test
    fun testDynamicDrainRate_heavyWorkloadAcceleratesTimeDecay() {
        val baseTs = 1700000000_000L
        val initialMs = 8 * 3600_000L // 8h 00m

        // 初次确立 8 小时基准
        smoother.smoothRemainingRuntime(
            dimension = EnduranceRuntimeSmoother.DIMENSION_SCREEN_ON,
            rawTargetRemainingMs = initialMs,
            currentTimestampMs = baseTs,
            batteryLevelPercent = 80.0f
        )

        // 玩游戏 5 分钟（300 秒，对应 15 分钟能耗衰减），电量从 80.0% 消耗至 77.5%
        val fiveMinutesTs = baseTs + 300_000L
        val targetBaseMs = (7 * 3600 + 45 * 60) * 1000L // 对应消耗 0.5Wh 后的真实基线 7h 45m

        val resultAfterFiveMinutes = smoother.smoothRemainingRuntime(
            dimension = EnduranceRuntimeSmoother.DIMENSION_SCREEN_ON,
            rawTargetRemainingMs = targetBaseMs,
            currentTimestampMs = fiveMinutesTs,
            isRegimeChanging = true, // 重载突变
            batteryLevelPercent = 77.5f,
            drainRateMultiplier = 3.0 // 3倍速加速消耗
        )
        assertNotNull(resultAfterFiveMinutes)

        // 核心断言 1：经过 5 分钟 3 倍速做功，表显时间精确扣除 15 分钟，稳定在 7h 45m
        assertEquals("5分钟重载游戏按3倍流速消耗15分钟续航", targetBaseMs, resultAfterFiveMinutes)

        // 核心断言 2：绝不发生由于 5 分钟游戏腰斩至 3 小时 15 分的短视外推缺陷
        assertTrue("续航保持稳健，绝不直接腰斩至3.5小时以下", resultAfterFiveMinutes!! > 7 * 3600_000L)
    }

    /**
     * 测试动态能耗流速模型（轻载减速节省）：
     * 退出游戏后看电子书 30 分钟（1800秒），功耗倍率为 0.6x（如 1.2W / 2.0W），
     * 剩余续航仅消耗 18 分钟（从 7h 45m 降至 7h 27m），体现极度省电与耐用性。
     */
    @Test
    fun testDynamicDrainRate_lightWorkloadDeceleratesTimeDecay() {
        val baseTs = 1700000000_000L
        val currentMs = (7 * 3600 + 45 * 60) * 1000L // 7h 45m

        smoother.smoothRemainingRuntime(
            dimension = EnduranceRuntimeSmoother.DIMENSION_SCREEN_ON,
            rawTargetRemainingMs = currentMs,
            currentTimestampMs = baseTs,
            batteryLevelPercent = 77.5f
        )

        // 看电子书 30 分钟（1800 秒），流速 0.6x（等效做功仅流逝 18 分钟 = 1080 秒）
        val thirtyMinutesTs = baseTs + 1800_000L
        val expectedMs = (7 * 3600 + 27 * 60) * 1000L // 7h 27m

        val resultAfterReading = smoother.smoothRemainingRuntime(
            dimension = EnduranceRuntimeSmoother.DIMENSION_SCREEN_ON,
            rawTargetRemainingMs = expectedMs,
            currentTimestampMs = thirtyMinutesTs,
            isRegimeChanging = false,
            batteryLevelPercent = 74.5f,
            drainRateMultiplier = 0.6 // 0.6倍减速消耗
        )
        assertNotNull(resultAfterReading)

        // 核心断言：看书 30 分钟表显仅扣减 18 分钟，极耐用且单调下降
        assertEquals("看书30分钟按0.6倍流速平缓消耗18分钟", expectedMs, resultAfterReading)
    }
}
