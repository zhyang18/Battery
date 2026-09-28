package com.battery.analysis

import com.battery.analysis.manager.AppLifecycleTracker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 全局应用生命周期状态追踪器 [AppLifecycleTracker] 单元测试套件。
 * 验证应用内部切页、子页面返回、切出后台切回以及冷启动在 1 分钟时效门限前后的刷新判定策略。
 */
class AppLifecycleTrackerTest {

    /**
     * 每个测试用例执行前的初始化配置，重置 [AppLifecycleTracker] 内部状态。
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
     * 测试界面尚未渲染过任何数据且无可用缓存时，必须触发首次数据加载刷新。
     */
    @Test
    fun testInitialStartWithoutDataMustRefresh() {
        AppLifecycleTracker.resetForTest(
            refreshTime = 0L,
            startedCount = 1,
            hasTransition = true
        )
        val shouldRefresh = AppLifecycleTracker.shouldRefreshPowerStats(hasRenderedData = false)
        assertTrue("界面无任何有效数据或缓存时必须触发首次刷新", shouldRefresh)
    }

    /**
     * 测试在 App 内部切换底部页签或从子页面按返回键回到耗电页时，绝不触发刷新。
     */
    @Test
    fun testInternalNavigationOrTabSwitchDoesNotRefresh() {
        val now = System.currentTimeMillis()
        AppLifecycleTracker.resetForTest(
            refreshTime = now - 5_000L,
            startedCount = 1,
            hasTransition = false
        )

        val shouldRefresh = AppLifecycleTracker.shouldRefreshPowerStats(hasRenderedData = true)
        assertFalse("App 内部页签切换或页面返回时绝不触发数据刷新", shouldRefresh)
    }

    /**
     * 测试从后台重新切回前台（前后台切换）且在 1 分钟内时，不触发耗电统计刷新。
     */
    @Test
    fun testForegroundTransitionWithinOneMinuteDoesNotRefresh() {
        val now = System.currentTimeMillis()
        // 模拟上次刷新在 30 秒前
        AppLifecycleTracker.resetForTest(
            refreshTime = now - 30_000L,
            startedCount = 1,
            hasTransition = true
        )

        val shouldRefresh = AppLifecycleTracker.shouldRefreshPowerStats(hasRenderedData = true)
        assertFalse("从后台切回前台在 1 分钟之内时不应触发刷新", shouldRefresh)
    }

    /**
     * 测试从后台重新切回前台且距上次刷新已超过 1 分钟时，必须正常触发数据刷新。
     */
    @Test
    fun testForegroundTransitionAfterOneMinuteMustRefresh() {
        val now = System.currentTimeMillis()
        // 模拟上次刷新在 75 秒前（超过 60 秒）
        AppLifecycleTracker.resetForTest(
            refreshTime = now - 75_000L,
            startedCount = 1,
            hasTransition = true
        )

        val shouldRefresh = AppLifecycleTracker.shouldRefreshPowerStats(hasRenderedData = true)
        assertTrue("从后台切回前台超过 1 分钟后必须正常触发数据刷新", shouldRefresh)
    }

    /**
     * 测试冷启动进入应用，在持有本地持久化缓存且距上次刷新在 1 分钟内时，不触发重复刷新。
     */
    @Test
    fun testColdStartWithinOneMinuteWithCachedDataDoesNotRefresh() {
        val now = System.currentTimeMillis()
        // 模拟冷启动：hasForegroundTransition 为 true，上次刷新在 20 秒前
        AppLifecycleTracker.resetForTest(
            refreshTime = now - 20_000L,
            startedCount = 1,
            hasTransition = true
        )

        val shouldRefresh = AppLifecycleTracker.shouldRefreshPowerStats(hasRenderedData = true)
        assertFalse("冷启动在 1 分钟内持有有效缓存时不应触发重复刷新", shouldRefresh)
    }

    /**
     * 测试冷启动进入应用且距上次刷新超过 1 分钟时，必须正常触发全量刷新。
     */
    @Test
    fun testColdStartAfterOneMinuteMustRefresh() {
        val now = System.currentTimeMillis()
        // 模拟冷启动：hasForegroundTransition 为 true，上次刷新在 120 秒前
        AppLifecycleTracker.resetForTest(
            refreshTime = now - 120_000L,
            startedCount = 1,
            hasTransition = true
        )

        val shouldRefresh = AppLifecycleTracker.shouldRefreshPowerStats(hasRenderedData = true)
        assertTrue("冷启动超过 1 分钟后必须正常触发全量刷新", shouldRefresh)
    }

    /**
     * 测试从未记录过刷新时间（时间戳 <= 0）时，进入前台必须正常触发首次刷新。
     */
    @Test
    fun testNeverRefreshedMustRefreshOnForeground() {
        AppLifecycleTracker.resetForTest(
            refreshTime = 0L,
            startedCount = 1,
            hasTransition = true
        )

        val shouldRefresh = AppLifecycleTracker.shouldRefreshPowerStats(hasRenderedData = true)
        assertTrue("从未记录过刷新时间时进入前台必须正常刷新", shouldRefresh)
    }
}
