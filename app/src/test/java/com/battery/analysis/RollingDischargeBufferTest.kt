package com.battery.analysis

import com.battery.analysis.domain.estimator.RollingDischargeBuffer
import com.battery.analysis.domain.estimator.RuntimeEstimator
import com.battery.analysis.domain.estimator.model.PowerSample
import com.battery.analysis.domain.estimator.model.ScreenPowerState
import com.battery.analysis.manager.PowerOverviewStats
import com.battery.analysis.model.PowerDischargePoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 线性向前滑动放电时序缓冲区（RollingDischargeBuffer）与无缝预测纯 JVM 单元测试套件。
 * 验证充电样本物理隔离拦截、亮屏/息屏/全局独立时间轴线性回溯以及跨拔电会话零冷启动平滑预测。
 */
class RollingDischargeBufferTest {

    private lateinit var testDir: File
    private lateinit var buffer: RollingDischargeBuffer

    /**
     * 单元测试初始化准备。
     * 创建临时数据存储目录并初始化 [RollingDischargeBuffer]。
     */
    @Before
    fun setUp() {
        testDir = File(System.getProperty("java.io.tmpdir"), "rolling_test_${System.currentTimeMillis()}")
        testDir.mkdirs()
        buffer = RollingDischargeBuffer.initForTesting(testDir)
    }

    /**
     * 单元测试环境清理。
     * 重置单例并清理临时数据文件。
     */
    @After
    fun tearDown() {
        RollingDischargeBuffer.resetForTesting()
        testDir.deleteRecursively()
    }

    /**
     * 测试充电状态下的采样点被严格拦截隔离，绝对不污染放电滑动缓冲区。
     */
    @Test
    fun testChargingSamplesAreStrictlyIsolated() {
        val baseTs = 1_700_000_000_000L
        val chargingPoint = PowerDischargePoint(
            timestamp = baseTs,
            elapsedHours = 1.0f,
            batteryLevel = 80,
            voltageVolts = 4.35f,
            temperature = 35.0f,
            powerWatts = 25.0f, // 25W 充电功率
            activeAppIcons = emptyList(),
            isScreenOn = true,
            activeAppNames = emptyList()
        )

        buffer.addSample(chargingPoint, isCharging = true)
        val window = buffer.extractRollingScreenOnWindow(300_000L)
        assertNull("充电点绝不进入放电滑动窗口", window)
    }

    /**
     * 测试沿亮屏放电时序向前线性回溯指定目标时长（如 30 分钟）的切片做功微积分。
     */
    @Test
    fun testExtractRollingScreenOnWindow_CalculatesCorrectAverageWatts() {
        val baseTs = 1_700_000_000_000L
        val points = mutableListOf<PowerDischargePoint>()

        // 模拟过去 30 分钟内每 30 秒一个亮屏采样点（平均功耗稳定在 0.86W）
        for (i in 0 until 60) {
            points.add(
                PowerDischargePoint(
                    timestamp = baseTs + i * 30_000L,
                    elapsedHours = (i * 30_000L) / 3600_000f,
                    batteryLevel = 80 - (i / 10),
                    voltageVolts = 3.98f,
                    temperature = 30.0f,
                    powerWatts = 0.86f,
                    activeAppIcons = emptyList(),
                    isScreenOn = true,
                    activeAppNames = emptyList()
                )
            )
        }

        buffer.appendSamplesBatch(points)

        // 线性向前回溯 15 分钟（900,000 ms）亮屏数据，每 30 秒一点，15 分钟应为 31 点
        val window15m = buffer.extractRollingScreenOnWindow(900_000L)
        assertNotNull("滑动窗口提取不可为空", window15m)
        assertTrue("有效回溯时长应覆盖 15 分钟", window15m!!.effectiveDurationMs >= 900_000L)
        assertEquals(0.86f, window15m.avgPowerWatts, 0.05f)
        assertTrue("15分钟切片采样点应约为 31 点", window15m.samples.size in 30..32)

        // 线性向前回溯 30 分钟（1,800,000 ms）全量亮屏数据
        val window30m = buffer.extractRollingScreenOnWindow(1_800_000L)
        assertNotNull("30分钟滑动窗口提取不可为空", window30m)
        assertEquals(60, window30m!!.samples.size)
        assertEquals(0.86f, window30m.avgPowerWatts, 0.05f)
    }

    /**
     * 测试跨拔电会话预测：刚拔电 10 秒钟时，当前会话样本极少，
     * 但通过滑动窗口无缝回溯拔电前 30 分钟的 0.86W 稳态做功，
     * RuntimeEstimator 预测功率平稳保持在 1.1W~1.3W，剩余续航绝不腰斩或剧烈跳变。
     */
    @Test
    fun testRollingDischargeWindow_EliminatesColdStartOscillationAfterUnplug() {
        val baseTs = 1_700_000_000_000L
        val points = mutableListOf<PowerDischargePoint>()

        // 1. 拔电前 35 分钟的稳定亮屏放电数据（0.86W）
        for (i in 0 until 70) {
            points.add(
                PowerDischargePoint(
                    timestamp = baseTs + i * 30_000L,
                    elapsedHours = (i * 30_000L) / 3600_000f,
                    batteryLevel = 80 - (i / 10),
                    voltageVolts = 3.98f,
                    temperature = 30.0f,
                    powerWatts = 0.86f,
                    activeAppIcons = emptyList(),
                    isScreenOn = true,
                    activeAppNames = emptyList()
                )
            )
        }
        buffer.appendSamplesBatch(points)

        // 2. 模拟刚拔电 10 秒：当前会话统计时长仅有 10 秒，且刚触发 App 冷启动瞬态（17.9W）
        val unplugTs = baseTs + 70 * 30_000L
        val coldStartSample = PowerDischargePoint(
            timestamp = unplugTs + 10_000L,
            elapsedHours = 0.002f,
            batteryLevel = 73,
            voltageVolts = 3.85f,
            temperature = 31.0f,
            powerWatts = 17.9f, // 冷启动瞬态脉冲
            activeAppIcons = emptyList(),
            isScreenOn = true,
            activeAppNames = emptyList()
        )
        buffer.addSample(coldStartSample, isCharging = false)

        val currentSessionOverview = PowerOverviewStats(
            screenOnDurationMs = 10_000L, // 刚拔电 10 秒
            screenOffDurationMs = 0L,
            totalDurationMs = 10_000L,
            screenOnPowerWatts = 17.9f,   // 当前会话仅有单点冷启动
            screenOffPowerWatts = 0f,
            avgPowerWatts = 17.9f
        )

        val runtimeEstimator = RuntimeEstimator()
        val result = runtimeEstimator.estimateTripleRuntimes(
            overview = currentSessionOverview,
            sevenDaysStats = null,
            batteryLevelPercent = 73f,
            remainingEnergyWh = 16.89f,
            totalCapacityWh = 29.77f,
            tempCelsius = 31f,
            currentTrendPoints = listOf(coldStartSample)
        )

        val screenOnResult = result.screenOnEstimate
        assertNotNull("亮屏预测不可为空", screenOnResult.remainingMs)
        val predictedWatts = screenOnResult.predictedPowerWatts
        assertNotNull("亮屏预测功率不可为空", predictedWatts)

        // 核心断言：由于滑动窗口成功回溯拔电前 35 分钟的 0.86W 稳态，
        // 预测功率受到 0.86W 强力锚定，绝不能被单点 17.9W 绑架飙到 3.5W+
        assertTrue("预测功率应稳定在 1.30W 以内（当前: $predictedWatts）", predictedWatts!! <= 1.30)

        // 剩余续航平稳保持在 12 小时以上，彻底杜绝从 14h 腰斩至 4h48m
        val hours = (screenOnResult.remainingMs!!.toDouble() / 3600_000.0)
        assertTrue("剩余续航应平稳保持在 12 小时以上（当前: ${hours}h）", hours >= 12.0)
    }

    /**
     * 测试 4 小时息屏待机滑动窗口（覆盖多次 Doze 深睡与唤醒维护窗口），
     * 验证微积分平均功耗能够平滑滤除周期性唤醒脉冲。
     */
    @Test
    fun testExtractRollingScreenOffWindow_Covers4HoursWithDozePulses() {
        val baseTs = 1_700_000_000_000L
        val points = mutableListOf<PowerDischargePoint>()

        // 模拟 4 小时（240分钟）息屏待机：每分钟 1 个点
        // 绝大多数点为深睡（0.12W），每 20 分钟发生一次维持唤醒脉冲（1.2W）
        for (m in 0 until 240) {
            val isWakeup = (m % 20 == 0)
            val watts = if (isWakeup) 1.2f else 0.12f
            points.add(
                PowerDischargePoint(
                    timestamp = baseTs + m * 60_000L,
                    elapsedHours = (m * 60_000L) / 3600_000f,
                    batteryLevel = 90 - (m / 60),
                    voltageVolts = 4.05f,
                    temperature = 28.0f,
                    powerWatts = watts,
                    activeAppIcons = emptyList(),
                    isScreenOn = false,
                    activeAppNames = emptyList()
                )
            )
        }

        buffer.appendSamplesBatch(points)

        // 线性向前回溯 4 小时（14,400,000 ms）息屏待机时序
        val window4h = buffer.extractRollingScreenOffWindow(14400_000L)
        assertNotNull("4小时息屏滑动窗口提取不可为空", window4h)
        assertTrue("有效回溯时长应覆盖 4 小时", window4h!!.effectiveDurationMs >= 14300_000L)

        // 理论加权平均：深睡 0.12W 占 19/20，唤醒 1.2W 占 1/20，均值约 0.174W
        assertTrue("4小时待机平均功耗应平滑在 0.15W~0.22W 之间（当前: ${window4h.avgPowerWatts}W）",
            window4h.avgPowerWatts in 0.15f..0.22f)
    }

    /**
     * 测试 6 小时全局综合放电滑动窗口（覆盖半天亮屏与息屏交替做功），
     * 验证大跨度放电切片的微积分精度与时长守恒。
     */
    @Test
    fun testExtractRollingGlobalWindow_Covers6HoursCompositeDischarge() {
        val baseTs = 1_700_000_000_000L
        val points = mutableListOf<PowerDischargePoint>()

        // 模拟 6 小时（360分钟）全局放电：每 2 分钟 1 点
        // 前 1/3 为亮屏（1.2W），后 2/3 为息屏待机（0.15W）
        for (m in 0 until 180) {
            val ts = baseTs + m * 120_000L
            val isScreenOn = (m < 60) // 前 2 小时亮屏，后 4 小时息屏
            val watts = if (isScreenOn) 1.20f else 0.15f
            points.add(
                PowerDischargePoint(
                    timestamp = ts,
                    elapsedHours = (m * 120_000L) / 3600_000f,
                    batteryLevel = 95 - (m / 20),
                    voltageVolts = 4.0f,
                    temperature = 29.0f,
                    powerWatts = watts,
                    activeAppIcons = emptyList(),
                    isScreenOn = isScreenOn,
                    activeAppNames = emptyList()
                )
            )
        }

        buffer.appendSamplesBatch(points)

        // 线性向前回溯 6 小时（21,600,000 ms）全局综合放电切片
        val window6h = buffer.extractRollingGlobalWindow(21600_000L)
        assertNotNull("6小时全局滑动窗口提取不可为空", window6h)
        assertTrue("有效回溯时长应覆盖 6 小时", window6h!!.effectiveDurationMs >= 21400_000L)

        // 理论均值：1/3 时间 1.2W + 2/3 时间 0.15W = 0.40W + 0.10W = 0.50W
        assertEquals(0.50f, window6h.avgPowerWatts, 0.05f)
    }

    /**
     * 测试 12 小时（43,200,000 ms）大跨度息屏待机滑动切片。
     * 验证能够完整覆盖一整夜深度休眠（如 8 小时深睡）与半天工作待机，平滑滤除偶发后台唤醒脉冲。
     */
    @Test
    fun testExtractRollingScreenOffWindow_Covers12HoursExtendedSleepAndStandby() {
        val baseTs = 1_700_000_000_000L
        val points = mutableListOf<PowerDischargePoint>()

        // 模拟 12 小时（720分钟）息屏待机：每 1 分钟 1 点（共 720 点）
        // 绝大多数点为深睡（0.12W），每 20 分钟发生一次维持唤醒脉冲（1.2W）
        for (m in 0 until 720) {
            val isWakeup = (m % 20 == 0) // 每 20 分钟发生一次唤醒脉冲
            val watts = if (isWakeup) 1.2f else 0.12f
            points.add(
                PowerDischargePoint(
                    timestamp = baseTs + m * 60_000L,
                    elapsedHours = (m * 60_000L) / 3600_000f,
                    batteryLevel = 90 - (m / 72),
                    voltageVolts = 4.05f,
                    temperature = 28.0f,
                    powerWatts = watts,
                    activeAppIcons = emptyList(),
                    isScreenOn = false,
                    activeAppNames = emptyList()
                )
            )
        }

        buffer.appendSamplesBatch(points)

        // 线性向前回溯 12 小时（43,200,000 ms）息屏待机时序
        val window12h = buffer.extractRollingScreenOffWindow(43200_000L)
        assertNotNull("12小时息屏滑动窗口提取不可为空", window12h)
        assertTrue("有效回溯时长应覆盖 12 小时", window12h!!.effectiveDurationMs >= 43000_000L)

        // 理论加权平均：深睡 0.12W 占 19/20，唤醒 1.2W 占 1/20，均值约 0.174W
        assertTrue("12小时待机平均功耗应平滑在 0.15W~0.22W 之间（当前: ${window12h.avgPowerWatts}W）",
            window12h.avgPowerWatts in 0.15f..0.22f)
    }

    /**
     * 测试 24 小时（86,400,000 ms）全局综合放电滑动切片。
     * 验证能够完整闭环覆盖一整天的昼夜交替周期（夜间 8 小时待机 + 白天 16 小时复合用机），
     * 准确平抑白昼与夜晚之间的相位偏态。
     */
    @Test
    fun testExtractRollingGlobalWindow_Covers24HoursCircadianCompositeDischarge() {
        val baseTs = 1_700_000_000_000L
        val points = mutableListOf<PowerDischargePoint>()

        // 模拟 24 小时（1440分钟）全局放电：每 4 分钟 1 点（共 360 点）
        // 8 小时夜间休眠（0.15W 息屏） + 16 小时日间复合（其中 6h 亮屏 1.8W，10h 白天待机 0.25W）
        for (i in 0 until 360) {
            val ts = baseTs + i * 240_000L
            val minuteOfDay = (i * 4) % 1440
            val (isScreenOn, watts) = when {
                minuteOfDay < 480 -> false to 0.15f // 00:00~08:00 夜间深睡 (8小时)
                minuteOfDay in 480 until 840 -> true to 1.80f // 08:00~14:00 白天高频用机 (6小时亮屏)
                else -> false to 0.25f // 14:00~24:00 白天与晚间空闲待机 (10小时息屏)
            }
            points.add(
                PowerDischargePoint(
                    timestamp = ts,
                    elapsedHours = (i * 240_000L) / 3600_000f,
                    batteryLevel = 100 - (i / 5),
                    voltageVolts = 3.95f,
                    temperature = 28.5f,
                    powerWatts = watts,
                    activeAppIcons = emptyList(),
                    isScreenOn = isScreenOn,
                    activeAppNames = emptyList()
                )
            )
        }

        buffer.appendSamplesBatch(points)

        // 线性向前回溯 24 小时（86,400,000 ms）全局综合时序
        val window24h = buffer.extractRollingGlobalWindow(86400_000L)
        assertNotNull("24小时全局滑动窗口提取不可为空", window24h)
        assertTrue("有效回溯时长应覆盖 24 小时", window24h!!.effectiveDurationMs >= 86000_000L)

        // 理论加权平均：(8h * 0.15W + 6h * 1.80W + 10h * 0.25W) / 24h = (1.2 + 10.8 + 2.5) / 24 = 14.5 / 24 ≈ 0.604W
        assertEquals(0.604f, window24h.avgPowerWatts, 0.05f)
    }

    /**
     * 验证跨夜整夜长充电（如 10 小时插电）后，拔电瞬间滑动缓冲区中昨日的历史放电记忆完好保留。
     * 挂钟时间放宽至 48 小时，杜绝 8 小时修剪导致整夜充电后历史放电点被全部清空的缺陷。
     */
    @Test
    fun testOvernightChargingPreservesDischargeMemory() {
        val baseTs = 1_700_000_000_000L
        val points = mutableListOf<PowerDischargePoint>()

        // 1. 模拟昨天睡前放电 2 小时（120分钟，每分钟 1 点，均值 1.5W）
        for (m in 0 until 120) {
            val ts = baseTs + m * 60_000L
            points.add(
                PowerDischargePoint(
                    timestamp = ts,
                    elapsedHours = (m * 60_000L) / 3600_000f,
                    batteryLevel = 50 - (m / 10),
                    voltageVolts = 3.85f,
                    temperature = 28.0f,
                    powerWatts = 1.50f,
                    activeAppIcons = emptyList(),
                    isScreenOn = true,
                    activeAppNames = emptyList()
                )
            )
        }
        buffer.appendSamplesBatch(points)

        // 2. 模拟整夜长充电 10 小时（此期间没有放电样本入库）
        val morningUnplugTs = baseTs + (120 * 60_000L) + (10 * 3600_000L)

        // 3. 模拟早上拔电后放电第 1 分钟
        val morningPoint = PowerDischargePoint(
            timestamp = morningUnplugTs + 60_000L,
            elapsedHours = 0.016f,
            batteryLevel = 100,
            voltageVolts = 4.35f,
            temperature = 27.0f,
            powerWatts = 1.80f,
            activeAppIcons = emptyList(),
            isScreenOn = true,
            activeAppNames = emptyList()
        )
        buffer.addSample(morningPoint, isCharging = false)

        // 4. 验证缓冲区内昨日历史放电记忆未被裁剪清空
        val screenOnWindow = buffer.extractRollingScreenOnWindow(7200_000L)
        assertNotNull("跨夜长充电后提取历史亮屏窗口不可为空", screenOnWindow)
        assertTrue("应包含昨日历史放电采样点与今早新采样点", screenOnWindow!!.samples.size > 10)
    }

    /**
     * 验证针对 Float.NaN 与 Float.POSITIVE_INFINITY 异常浮点数的严格防御，
     * 绝不污染滑动缓冲区，也不引发异常。
     */
    @Test
    fun testNaNAndInfiniteSamplesAreSafelyRejected() {
        val baseTs = 1_700_000_000_000L

        // 1. 单点 NaN 注入
        val nanPoint = PowerDischargePoint(
            timestamp = baseTs,
            elapsedHours = 1.0f,
            batteryLevel = 80,
            voltageVolts = 3.85f,
            temperature = 28.0f,
            powerWatts = Float.NaN,
            activeAppIcons = emptyList(),
            isScreenOn = true,
            activeAppNames = emptyList()
        )
        buffer.addSample(nanPoint, isCharging = false)

        // 2. 单点 Infinity 注入
        val infPoint = PowerDischargePoint(
            timestamp = baseTs + 1000L,
            elapsedHours = 1.0f,
            batteryLevel = 80,
            voltageVolts = 3.85f,
            temperature = 28.0f,
            powerWatts = Float.POSITIVE_INFINITY,
            activeAppIcons = emptyList(),
            isScreenOn = true,
            activeAppNames = emptyList()
        )
        buffer.addSample(infPoint, isCharging = false)

        // 验证滑动窗口无有效样本
        val window = buffer.extractRollingGlobalWindow(60_000L)
        assertNull("NaN 与 Infinity 样本严禁进入有效放电窗口", window)
    }

    /**
     * 验证全局综合滑动窗口在回溯计算时，能够准确累积并输出该窗口内部的真实亮屏有效时长（screenOnDurationMs），
     * 确保全局占空比计算时分子（亮屏时长）与分母（全局总时长）来自严格同源的时间切片。
     */
    @Test
    fun testRollingGlobalWindowTracksAccurateScreenOnDuration() {
        val baseTs = 1_700_000_000_000L
        val points = mutableListOf<PowerDischargePoint>()

        // 模拟 4 小时（240 分钟）交替用机：
        // 前 1 小时（60 分钟）亮屏使用（2.0W），每 2 分钟 1 点
        // 后 3 小时（180 分钟）息屏待机（0.2W），每 2 分钟 1 点
        for (m in 0..120) {
            val ts = baseTs + m * 120_000L
            val isScreenOn = (m <= 30) // 前 60 分钟为亮屏
            val power = if (isScreenOn) 2.0f else 0.2f
            points.add(
                PowerDischargePoint(
                    timestamp = ts,
                    elapsedHours = (m * 120_000L) / 3600_000f,
                    batteryLevel = 100 - (m / 10),
                    voltageVolts = 4.0f,
                    temperature = 28.0f,
                    powerWatts = power,
                    activeAppIcons = emptyList(),
                    isScreenOn = isScreenOn,
                    activeAppNames = emptyList()
                )
            )
        }
        buffer.appendSamplesBatch(points)

        // 提取 4 小时（14,400,000 ms）全局滑动窗口
        val globalWindow = buffer.extractRollingGlobalWindow(14400_000L)
        assertNotNull("全局滑动窗口应成功提取", globalWindow)

        val totalDurMs = globalWindow!!.effectiveDurationMs
        val screenOnDurMs = globalWindow.screenOnDurationMs

        assertTrue("全局总时长应覆盖约 4 小时 (14,400,000 ms)", totalDurMs in 14300_000L..14500_000L)
        assertTrue("同窗口内的亮屏时长应覆盖约 1 小时 (3,600,000 ms)", screenOnDurMs in 3500_000L..3700_000L)

        val ratio = screenOnDurMs.toDouble() / totalDurMs.toDouble()
        assertEquals("实测亮屏占比应接近 25% (1h / 4h = 0.25)", 0.25, ratio, 0.03)
    }
}
