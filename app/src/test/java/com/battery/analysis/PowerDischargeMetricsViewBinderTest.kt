package com.battery.analysis

import com.battery.analysis.manager.PowerOverviewStats
import com.battery.analysis.ui.PowerDischargeMetricsViewBinder
import com.battery.analysis.util.BatteryEnergyCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 放电速度与息屏休眠指标卡片通用视图绑定辅助工具类单元测试套件。
 * 验证息屏、唤醒与深度睡眠卡片时长在超过 1 小时时不显示秒数、不足 1 小时时显示秒数，
 * 以及卡片事件点击弹框详情保持正常显示秒数不被篡改的完整业务规范。
 */
class PowerDischargeMetricsViewBinderTest {

    /**
     * 验证当持续时间超过 1 小时时，卡片时间格式化输出时分格式（不显示秒数）。
     */
    @Test
    fun testFormatSleepAwakeCardDuration_ExceedingOneHour_HidesSeconds() {
        // 6 小时 53 分 10 秒 (24,790,000 毫秒) -> 超过小时，不显示秒数，期望 "06h53m"
        val dur1Ms = (6 * 3600 + 53 * 60 + 10) * 1000L
        assertEquals("06h53m", PowerDischargeMetricsViewBinder.formatSleepAwakeCardDuration(dur1Ms))

        // 6 小时 34 分 09 秒 (23,649,000 毫秒) -> 超过小时，不显示秒数，期望 "06h34m"
        val dur2Ms = (6 * 3600 + 34 * 60 + 9) * 1000L
        assertEquals("06h34m", PowerDischargeMetricsViewBinder.formatSleepAwakeCardDuration(dur2Ms))

        // 整 1 小时 (3,600,000 毫秒) -> 期望 "01h00m"
        val dur3Ms = 3600 * 1000L
        assertEquals("01h00m", PowerDischargeMetricsViewBinder.formatSleepAwakeCardDuration(dur3Ms))

        // 1 小时 0 分 45 秒 (3,645,000 毫秒) -> 超过小时，不显示秒数，期望 "01h00m"
        val dur4Ms = (3600 + 45) * 1000L
        assertEquals("01h00m", PowerDischargeMetricsViewBinder.formatSleepAwakeCardDuration(dur4Ms))

        // 25 小时 12 分 30 秒 (90,750,000 毫秒) -> 超过小时，期望 "25h12m"
        val dur5Ms = (25 * 3600 + 12 * 60 + 30) * 1000L
        assertEquals("25h12m", PowerDischargeMetricsViewBinder.formatSleepAwakeCardDuration(dur5Ms))
    }

    /**
     * 验证当持续时间不足 1 小时且大于等于 1 分钟时，卡片时间格式化输出分秒格式（正常显示秒数）。
     */
    @Test
    fun testFormatSleepAwakeCardDuration_LessThanOneHour_ShowsSeconds() {
        // 19 分 01 秒 (1,141,000 毫秒) -> 不足小时，显示秒数，期望 "19m01s"
        val dur1Ms = (19 * 60 + 1) * 1000L
        assertEquals("19m01s", PowerDischargeMetricsViewBinder.formatSleepAwakeCardDuration(dur1Ms))

        // 整 19 分钟 (1,140,000 毫秒) -> 期望 "19m00s"
        val dur2Ms = 19 * 60 * 1000L
        assertEquals("19m00s", PowerDischargeMetricsViewBinder.formatSleepAwakeCardDuration(dur2Ms))

        // 5 分 30 秒 (330,000 毫秒) -> 期望 "05m30s"
        val dur3Ms = (5 * 60 + 30) * 1000L
        assertEquals("05m30s", PowerDischargeMetricsViewBinder.formatSleepAwakeCardDuration(dur3Ms))

        // 1 分 0 秒 (60,000 毫秒) -> 期望 "01m00s"
        val dur4Ms = 60 * 1000L
        assertEquals("01m00s", PowerDischargeMetricsViewBinder.formatSleepAwakeCardDuration(dur4Ms))
    }

    /**
     * 验证当持续时间不足 1 分钟时，卡片时间格式化输出秒数格式（正常显示秒数）。
     */
    @Test
    fun testFormatSleepAwakeCardDuration_LessThanOneMinute_ShowsSeconds() {
        // 45 秒 (45,000 毫秒) -> 期望 "45s"
        assertEquals("45s", PowerDischargeMetricsViewBinder.formatSleepAwakeCardDuration(45_000L))

        // 5 秒 (5,000 毫秒) -> 期望 "05s"
        assertEquals("05s", PowerDischargeMetricsViewBinder.formatSleepAwakeCardDuration(5_000L))

        // 0 毫秒 -> 期望 "00s"
        assertEquals("00s", PowerDischargeMetricsViewBinder.formatSleepAwakeCardDuration(0L))

        // 负数毫秒 -> 期望 "00s"
        assertEquals("00s", PowerDischargeMetricsViewBinder.formatSleepAwakeCardDuration(-500L))
    }

    /**
     * 验证当毫秒值小于等于 0 且提供有效备用文本 fallbackText 时，
     * 正确解析并依据小时阈值规则格式化。
     */
    @Test
    fun testFormatSleepAwakeCardDuration_FallbackTextParsing() {
        // 毫秒为 0，文本为 "06h53m10s" -> 超过小时，输出 "06h53m"
        assertEquals("06h53m", PowerDischargeMetricsViewBinder.formatSleepAwakeCardDuration(0L, "06h53m10s"))

        // 毫秒为 0，文本为 "19m01s" -> 不足小时，输出 "19m01s"
        assertEquals("19m01s", PowerDischargeMetricsViewBinder.formatSleepAwakeCardDuration(0L, "19m01s"))

        // 毫秒为 0，文本为 "00s" -> 输出 "00s"
        assertEquals("00s", PowerDischargeMetricsViewBinder.formatSleepAwakeCardDuration(0L, "00s"))

        // 毫秒为 0，文本为 "0s" -> 输出 "00s"
        assertEquals("00s", PowerDischargeMetricsViewBinder.formatSleepAwakeCardDuration(0L, "0s"))

        // 毫秒为 0，文本为空 -> 输出 "00s"
        assertEquals("00s", PowerDischargeMetricsViewBinder.formatSleepAwakeCardDuration(0L, ""))
    }

    /**
     * 验证卡片事件点击弹框详情中依然保持正常显示秒数，未被卡片紧凑格式化篡改。
     */
    @Test
    fun testCardDetailDialogs_RetainFullSecondsDisplay() {
        val screenOffDurationMs = (6 * 3600 + 53 * 60 + 10) * 1000L // 06h53m10s
        val awakeDurationMs = (19 * 60 + 1) * 1000L // 19m01s
        val deepSleepDurationMs = (6 * 3600 + 34 * 60 + 9) * 1000L // 06h34m09s

        val overview = PowerOverviewStats(
            avgPowerWatts = 0.5f,
            screenOffDurationMs = screenOffDurationMs,
            screenOffAwakeDurationMs = awakeDurationMs,
            screenOffDeepSleepDurationMs = deepSleepDurationMs,
            screenOffPercent = 9.5f,
            screenOffAwakePercent = 0.9f,
            screenOffDeepSleepPercent = 8.5f,
            screenOffEnergyWh = 2.924f,
            screenOffAwakeEnergyWh = 0.292f,
            screenOffDeepSleepEnergyWh = 2.632f,
            screenOffDrainMah = 759.4f,
            screenOffAwakeDrainMah = 75.7f,
            screenOffDeepSleepDrainMah = 683.7f,
            screenOffPowerWatts = 0.42f,
            screenOffAwakePowerWatts = 0.92f,
            screenOffDeepSleepPowerWatts = 0.40f,
            isScreenOffDecomposedAvailable = true
        )

        // 1. 息屏弹框：必须包含完整的 "06h53m10s"，正常显示秒数
        val screenOffMessage = BatteryEnergyCalculator.formatScreenOffCardDetailMessage(overview, 30.0f)
        assertTrue("息屏卡片弹框详情必须正常显示秒数 06h53m10s", screenOffMessage.contains("06h53m10s"))

        // 2. 唤醒弹框：必须包含完整的 "19m01s"，正常显示秒数
        val awakeMessage = BatteryEnergyCalculator.formatAwakeCardDetailMessage(overview, 30.0f)
        assertTrue("唤醒卡片弹框详情必须正常显示秒数 19m01s", awakeMessage.contains("19m01s"))

        // 3. 深度睡眠弹框：必须包含完整的 "06h34m09s"，正常显示秒数
        val deepSleepMessage = BatteryEnergyCalculator.formatDeepSleepCardDetailMessage(overview, 30.0f)
        assertTrue("深度睡眠卡片弹框详情必须正常显示秒数 06h34m09s", deepSleepMessage.contains("06h34m09s"))
    }

    /**
     * 验证剩余续航时长仅格式化数值部分（不含说明标签后缀），
     * 并在数据为 null 或无效时如实返回 "--"，不进行任何捏造伪造。
     */
    @Test
    fun testFormatRemainingDurationOnly() {
        // 3 小时 16 分钟 (3 * 3600 + 16 * 60) * 1000 = 11,760,000 ms -> 期望 "03h16m"
        val dur1Ms = (3 * 3600 + 16 * 60) * 1000L
        assertEquals("03h16m", PowerDischargeMetricsViewBinder.formatRemainingDurationOnly(dur1Ms))

        // 14 小时 13 分钟 -> 期望 "14h13m"
        val dur2Ms = (14 * 3600 + 13 * 60) * 1000L
        assertEquals("14h13m", PowerDischargeMetricsViewBinder.formatRemainingDurationOnly(dur2Ms))

        // null 或 <= 0L -> 真实返回 "--"
        assertEquals("--", PowerDischargeMetricsViewBinder.formatRemainingDurationOnly(null))
        assertEquals("--", PowerDischargeMetricsViewBinder.formatRemainingDurationOnly(0L))
        assertEquals("--", PowerDischargeMetricsViewBinder.formatRemainingDurationOnly(-1000L))
    }

    /**
     * 验证有效放电速率计算优先采用物理微积分功率与电池满充总能量换算，
     * 彻底消除粗粒度整型电量 1% 跳变阶梯带来的量化噪声（如把 2.1%/h 放大至 4.4%/h 的缺陷）。
     */
    @Test
    fun testGetEffectiveDischargeRate_PrioritizesPhysicalPowerCalculus() {
        val totalCapacityWh = 29.62f
        val overview = PowerOverviewStats(
            avgPowerWatts = 1.51f,
            totalDurationMs = 27120_000L,
            screenOffDurationMs = 2586_000L, // 43m06s
            screenOffEnergyWh = 0.453f,
            screenOffPowerWatts = 0.63f,
            screenOffPercent = 1.5f
        )

        val rate = PowerDischargeMetricsViewBinder.getEffectiveDischargeRate(
            rowType = BatteryEnergyCalculator.ROW_SCREEN_OFF,
            totalCapacityWh = totalCapacityWh,
            overview = overview
        )

        org.junit.Assert.assertNotNull("实测放电速度不应为 null", rate)
        // 0.63W / 29.62Wh * 100% ≈ 2.127%/h
        assertEquals(2.13f, rate!!, 0.05f)
    }

    /**
     * 测试通过滑动时序缓冲区计算 24 小时全局与 12 小时息屏放电速度。
     * 验证物理微积分功率至放电速率的无损折算与时间尺度扩展后的高准确度。
     */
    @Test
    fun testGetEffectiveDischargeRate_With24hGlobalAnd12hScreenOff() {
        val testDir = java.io.File(System.getProperty("java.io.tmpdir"), "test_binder_rolling_" + System.currentTimeMillis())
        testDir.mkdirs()
        com.battery.analysis.domain.estimator.RollingDischargeBuffer.resetForTesting()
        val buffer = com.battery.analysis.domain.estimator.RollingDischargeBuffer.initForTesting(testDir)

        val baseTs = 1_700_000_000_000L
        val points = mutableListOf<com.battery.analysis.model.PowerDischargePoint>()
        // 模拟 24 小时全局放电数据（每 5 分钟 1 点，共 288 点）
        // 8小时夜间休眠（0.20W 息屏） + 16小时日间（其中 8h 亮屏 2.0W，8h 白天待机 0.30W）
        for (i in 0 until 288) {
            val ts = baseTs + i * 300_000L
            val min = (i * 5) % 1440
            val (isScreenOn, watts) = when {
                min < 480 -> false to 0.20f // 8h 夜间深睡
                min in 480 until 960 -> true to 2.00f // 8h 白天用机
                else -> false to 0.30f // 8h 白天待机
            }
            points.add(
                com.battery.analysis.model.PowerDischargePoint(
                    timestamp = ts,
                    elapsedHours = (i * 300_000L) / 3600_000f,
                    batteryLevel = 100 - (i / 4),
                    voltageVolts = 4.0f,
                    temperature = 28.0f,
                    powerWatts = watts,
                    activeAppIcons = emptyList(),
                    isScreenOn = isScreenOn,
                    activeAppNames = emptyList()
                )
            )
        }
        buffer.appendSamplesBatch(points)

        val totalCapacityWh = 30.0f

        // 1. 全局 24 小时滑动窗口测试
        // 理论全天平均功率：(8h * 0.20W + 8h * 2.00W + 8h * 0.30W) / 24h = (1.6 + 16.0 + 2.4) / 24 = 20.0 / 24 ≈ 0.833W
        // 对应放电速率：0.833W / 30.0Wh * 100% ≈ 2.78%/h
        val globalRate = PowerDischargeMetricsViewBinder.getEffectiveDischargeRate(
            rowType = BatteryEnergyCalculator.ROW_GLOBAL,
            totalCapacityWh = totalCapacityWh,
            overview = null
        )
        org.junit.Assert.assertNotNull("24小时全局滑动放电速度不应为 null", globalRate)
        assertEquals(2.78f, globalRate!!, 0.08f)

        // 2. 息屏 12 小时滑动窗口测试
        // 逆序回溯最近 12 小时（全为息屏待机 0.30W 或混合后段深睡）
        val offRate = PowerDischargeMetricsViewBinder.getEffectiveDischargeRate(
            rowType = BatteryEnergyCalculator.ROW_SCREEN_OFF,
            totalCapacityWh = totalCapacityWh,
            overview = null
        )
        org.junit.Assert.assertNotNull("12小时息屏滑动放电速度不应为 null", offRate)
        assertTrue("息屏放电速度应处于真实待机区间", offRate!! > 0f && offRate < 2.0f)

        testDir.deleteRecursively()
    }

    /**
     * 测试当前会话实测放电速度计算，验证基于功率做功积分物理速率与会话有效时长的精准度。
     */
    @Test
    fun testCalculateSessionDischargeRate() {
        val totalCapacityWh = 20.0f
        val overview = PowerOverviewStats(
            avgPowerWatts = 1.0f,
            totalDurationMs = 3600_000L * 3,
            screenOnDurationMs = 3600_000L + 60_000L * 15 + 1000L * 30,
            screenOnPowerWatts = 2.0f,
            screenOffDurationMs = 3600_000L + 60_000L * 44 + 1000L * 30,
            screenOffPowerWatts = 0.4f
        )

        // 亮屏：2.0W / 20.0Wh * 100% = 10.0%/h
        val (onRate, onDur) = PowerDischargeMetricsViewBinder.calculateSessionDischargeRate(
            rowType = BatteryEnergyCalculator.ROW_SCREEN_ON,
            totalCapacityWh = totalCapacityWh,
            overview = overview
        )
        org.junit.Assert.assertNotNull(onRate)
        assertEquals(10.0f, onRate!!, 0.01f)
        assertEquals(3600_000L + 60_000L * 15 + 1000L * 30, onDur)

        // 息屏：0.4W / 20.0Wh * 100% = 2.0%/h
        val (offRate, offDur) = PowerDischargeMetricsViewBinder.calculateSessionDischargeRate(
            rowType = BatteryEnergyCalculator.ROW_SCREEN_OFF,
            totalCapacityWh = totalCapacityWh,
            overview = overview
        )
        org.junit.Assert.assertNotNull(offRate)
        assertEquals(2.0f, offRate!!, 0.01f)
        assertEquals(3600_000L + 60_000L * 44 + 1000L * 30, offDur)

        // 全局：1.0W / 20.0Wh * 100% = 5.0%/h
        val (globalRate, globalDur) = PowerDischargeMetricsViewBinder.calculateSessionDischargeRate(
            rowType = BatteryEnergyCalculator.ROW_GLOBAL,
            totalCapacityWh = totalCapacityWh,
            overview = overview
        )
        org.junit.Assert.assertNotNull(globalRate)
        assertEquals(5.0f, globalRate!!, 0.01f)
        assertEquals(3600_000L * 3, globalDur)
    }

    /**
     * 测试气泡弹窗关键预测数据正则表达式匹配。
     * 验证能够精准提取升级模型满电续航时长、预测基准放电速度数值以及当前剩余（或待机）续航数值，且不包含多余标签或括号。
     */
    @Test
    fun testFormatDischargeSpeedBubbleMessage_RegexMatchesExpectedFields() {
        val sampleText = """
            【亮屏续航预测】
            • 当前会话放电速度：5.6%/h (亮屏 05h36m44s)
            • 近7天放电速度：5.6%/h (亮屏 02d22h)
            • 满电亮屏续航：17h41m59s
            • 升级模型(v2.2)：15h41m53s (可信度高)
            • 预测基准放电速度：7.4%/h
            • 当前剩余续航：07h22m44s (可信度高)
        """.trimIndent()

        val v2Match = PowerDischargeMetricsViewBinder.REGEX_V2_MODEL_DURATION.find(sampleText)
        org.junit.Assert.assertNotNull(v2Match)
        assertEquals("15h41m53s", v2Match!!.groups[1]?.value)

        val speedMatch = PowerDischargeMetricsViewBinder.REGEX_PREDICTION_BENCHMARK_RATE.find(sampleText)
        org.junit.Assert.assertNotNull(speedMatch)
        assertEquals("7.4%/h", speedMatch!!.groups[1]?.value)

        val remMatch = PowerDischargeMetricsViewBinder.REGEX_CURRENT_REMAINING_DURATION.find(sampleText)
        org.junit.Assert.assertNotNull(remMatch)
        assertEquals("07h22m44s", remMatch!!.groups[1]?.value)

        val sampleOffText = """
            【息屏续航预测】
            • 升级模型(v2.2)：01d01h00m00s (可信度高)
            • 预测基准放电速度：2.8%/h
            • 当前待机续航：01d01h00m00s (可信度高)
        """.trimIndent()

        val offV2Match = PowerDischargeMetricsViewBinder.REGEX_V2_MODEL_DURATION.find(sampleOffText)
        org.junit.Assert.assertNotNull(offV2Match)
        assertEquals("01d01h00m00s", offV2Match!!.groups[1]?.value)

        val offSpeedMatch = PowerDischargeMetricsViewBinder.REGEX_PREDICTION_BENCHMARK_RATE.find(sampleOffText)
        org.junit.Assert.assertNotNull(offSpeedMatch)
        assertEquals("2.8%/h", offSpeedMatch!!.groups[1]?.value)

        val offRemMatch = PowerDischargeMetricsViewBinder.REGEX_CURRENT_REMAINING_DURATION.find(sampleOffText)
        org.junit.Assert.assertNotNull(offRemMatch)
        assertEquals("01d01h00m00s", offRemMatch!!.groups[1]?.value)
    }

    /**
     * 测试气泡弹窗预测数据格式化方法，验证空串安全返回且非空输入返回非空富文本对象。
     */
    @Test
    fun testFormatDischargeSpeedBubbleMessage_PreservesTextContent() {
        val emptyResult = PowerDischargeMetricsViewBinder.formatDischargeSpeedBubbleMessage("")
        assertEquals("", emptyResult.toString())

        val rawText = "【亮屏续航预测】\n• 预测基准放电速度：7.4%/h\n• 当前剩余续航：07h22m44s"
        val formatted = PowerDischargeMetricsViewBinder.formatDischargeSpeedBubbleMessage(rawText)
        org.junit.Assert.assertNotNull(formatted)
    }
}

