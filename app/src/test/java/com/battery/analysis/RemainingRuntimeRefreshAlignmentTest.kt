package com.battery.analysis

import com.battery.analysis.domain.estimator.TripleRuntimeResult
import com.battery.analysis.domain.estimator.model.RuntimeEstimate
import com.battery.analysis.domain.estimator.model.RuntimeModelType
import com.battery.analysis.manager.AppLifecycleTracker
import com.battery.analysis.ui.PowerDischargeMetricsViewBinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 剩余续航时间更新机制与下拉刷新机制对齐专项单元测试套件。
 * 验证手动下拉刷新与后台自动刷新（2分钟时效门限）的触发判定，
 * 以及防抖期内/内部切页时剩余续航时间维持稳定、预计算缓存精准复用且杜绝漂移的核心逻辑。
 */
class RemainingRuntimeRefreshAlignmentTest {

    /**
     * 每个测试用例执行前的初始化配置，重置生命周期追踪器状态。
     */
    @Before
    fun setUp() {
        AppLifecycleTracker.resetForTest(
            refreshTime = 0L,
            startedCount = 0,
            hasTransition = true
        )
    }

    /**
     * 验证首次冷启动进入耗电页面且无缓存时，必须触发全量刷新并允许初次计算剩余续航。
     */
    @Test
    fun testInitialColdStart_RequiresFullRefresh_CalculatesInitialRuntime() {
        AppLifecycleTracker.resetForTest(
            refreshTime = 0L,
            startedCount = 1,
            hasTransition = true
        )

        val shouldRefresh = AppLifecycleTracker.shouldRefreshPowerStats(hasRenderedData = false)
        assertTrue("首次冷启动无可用渲染数据时必须触发全量数据加载与续航初次计算", shouldRefresh)
    }

    /**
     * 验证从后台切回前台在 2 分钟防抖期内时，禁止自动触发刷新，严格保持已有续航数据冻结。
     */
    @Test
    fun testForegroundTransition_WithinTwoMinutes_SuppressesRefresh_KeepsRuntimeFrozen() {
        val now = System.currentTimeMillis()
        // 模拟上次全量刷新发生在 45 秒前（处于 120 秒防抖期内）
        AppLifecycleTracker.resetForTest(
            refreshTime = now - 45_000L,
            startedCount = 1,
            hasTransition = true
        )

        val shouldRefresh = AppLifecycleTracker.shouldRefreshPowerStats(hasRenderedData = true)
        assertFalse("从后台切回前台在 2 分钟防抖期内绝不触发数据重载与续航重复刷新", shouldRefresh)
    }

    /**
     * 验证从后台切回前台超过 2 分钟防抖门限时，自动触发全量刷新，并允许同步更新剩余续航时间。
     */
    @Test
    fun testForegroundTransition_AfterTwoMinutes_TriggersAutoRefresh_AllowsRuntimeUpdate() {
        val now = System.currentTimeMillis()
        // 模拟上次全量刷新发生在 150 秒前（超过 120 秒门限）
        AppLifecycleTracker.resetForTest(
            refreshTime = now - 150_000L,
            startedCount = 1,
            hasTransition = true
        )

        val shouldRefresh = AppLifecycleTracker.shouldRefreshPowerStats(hasRenderedData = true)
        assertTrue("从后台切回前台超过 2 分钟防抖门限时必须自动触发全量刷新并同步更新续航", shouldRefresh)
    }

    /**
     * 验证应用内部切换底部页签或二级页面返回时，绝不触发刷新，保障界面稳定。
     */
    @Test
    fun testInternalNavigationAndTabSwitch_NeverTriggersRefresh() {
        val now = System.currentTimeMillis()
        // 模拟应用内导航：未发生前后台切换 (hasForegroundTransition = false)
        AppLifecycleTracker.resetForTest(
            refreshTime = now - 30_000L,
            startedCount = 1,
            hasTransition = false
        )

        val shouldRefresh = AppLifecycleTracker.shouldRefreshPowerStats(hasRenderedData = true)
        assertFalse("应用内部页面返回或底部页签切换绝不触发刷新", shouldRefresh)
    }

    /**
     * 验证预先计算并缓存的 [TripleRuntimeResult] 在直接消费时毫秒数与外显时长精准无损复用，
     * 杜绝列表滚动或重复渲染导致的数值漂移。
     */
    @Test
    fun testPrecalculatedRuntimeResult_DirectlyConsumedWithoutDrift() {
        val cachedOnMs = 17 * 3600_000L + 46 * 60_000L // 17h46m
        val cachedOffMs = 21 * 3600_000L + 12 * 60_000L // 21h12m
        val cachedGlobalMs = 23 * 3600_000L + 30 * 60_000L // 23h30m

        val precalculated = TripleRuntimeResult(
            screenOnEstimate = RuntimeEstimate(
                remainingMs = cachedOnMs,
                predictedPowerWatts = 1.5,
                remainingEnergyWh = 26.6,
                confidence = 0.95,
                modelType = RuntimeModelType.MULTI_TIMESCALE
            ),
            screenOffEstimate = RuntimeEstimate(
                remainingMs = cachedOffMs,
                predictedPowerWatts = 0.25,
                remainingEnergyWh = 11.3,
                confidence = 0.90,
                modelType = RuntimeModelType.MULTI_TIMESCALE
            ),
            globalEstimate = RuntimeEstimate(
                remainingMs = cachedGlobalMs,
                predictedPowerWatts = 0.85,
                remainingEnergyWh = 20.0,
                confidence = 0.92,
                modelType = RuntimeModelType.GLOBAL_COMPOSITE
            )
        )

        // 验证时长格式化仅消费预缓存数据，无任何物理漂移
        val onText = PowerDischargeMetricsViewBinder.formatRemainingDurationOnly(precalculated.screenOnEstimate.remainingMs)
        val offText = PowerDischargeMetricsViewBinder.formatRemainingDurationOnly(precalculated.screenOffEstimate.remainingMs)
        val globalText = PowerDischargeMetricsViewBinder.formatRemainingDurationOnly(precalculated.globalEstimate.remainingMs)

        assertEquals("17h46m", onText)
        assertEquals("21h12m", offText)
        assertEquals("23h30m", globalText)

        // 额外验证跨天大时长（如 45 小时 -> 01d21h）格式化
        val crossDayMs = 45 * 3600_000L + 12 * 60_000L
        assertEquals("01d21h", PowerDischargeMetricsViewBinder.formatRemainingDurationOnly(crossDayMs))
    }

    /**
     * 验证全量刷新完成后调用 markDischargeRefreshed 能够正确更新并记录刷新时间戳。
     */
    @Test
    fun testMarkDischargeRefreshed_UpdatesTimestamp() {
        val beforeTime = System.currentTimeMillis()
        AppLifecycleTracker.markDischargeRefreshed(beforeTime)
        assertEquals(beforeTime, AppLifecycleTracker.lastDischargeRefreshTimeMillis)

        // 刷新刚完成后，再次从后台切回（立即切回，间隔 0 毫秒），判定必然处于防抖期内
        AppLifecycleTracker.resetForTest(
            refreshTime = beforeTime,
            startedCount = 1,
            hasTransition = true
        )
        val shouldRefreshImmediately = AppLifecycleTracker.shouldRefreshPowerStats(hasRenderedData = true)
        assertFalse("全量刷新刚完成后切回前台应处于防抖期内，不触发重复刷新", shouldRefreshImmediately)
    }
}
