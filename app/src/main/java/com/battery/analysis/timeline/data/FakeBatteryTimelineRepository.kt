package com.battery.analysis.timeline.data

import android.content.Context
import androidx.core.content.ContextCompat
import com.battery.analysis.R
import com.battery.analysis.timeline.domain.AppTimelineEvent
import com.battery.analysis.timeline.domain.BatterySample
import com.battery.analysis.timeline.domain.ConfidenceLevel
import com.battery.analysis.timeline.domain.EnergySource
import com.battery.analysis.timeline.domain.ScreenEvent

// Preview / Demo only
/**
 * 仅用于 UI 预览、设计打磨与无权限降级演示的测试仓库。
 * 正式运行环境中禁止通过随机数产生真实功耗数据。
 */
class FakeBatteryTimelineRepository(private val context: Context) {

    /**
     * 生成用于 Preview 演示的完整测试数据。
     *
     * @param durationMs 演示数据的时间跨度（毫秒，默认 1 小时）
     * @return 包含电池采样点、屏幕状态与 App 活动事件的三元组
     */
    fun createDemoData(
        durationMs: Long = 3600_000L
    ): Triple<List<BatterySample>, List<ScreenEvent>, List<AppTimelineEvent>> {
        val now = System.currentTimeMillis()
        val startTs = now - durationMs

        // 1. 模拟电池采样点
        val samples = mutableListOf<BatterySample>()
        val pointCount = 60
        val stepMs = durationMs / pointCount
        for (i in 0..pointCount) {
            val ts = startTs + i * stepMs
            val level = (100 - (i.toDouble() / pointCount) * 15).toInt()
            val volt = 4150 - (i * 2)
            val current = 450.0 + (i % 7) * 80.0
            val temp = 32.0 + (i % 5) * 0.6
            val power = (volt * current) / 1000.0

            samples.add(
                BatterySample(
                    timestamp = ts,
                    batteryLevel = level,
                    voltageMv = volt,
                    currentMa = current,
                    temperatureC = temp,
                    powerMw = power
                )
            )
        }

        // 2. 模拟屏幕亮灭事件 (亮屏: 0~35m, 息屏: 35~45m, 亮屏: 45~60m)
        val screenEvents = listOf(
            ScreenEvent(startTs, startTs + (durationMs * 0.6).toLong(), true),
            ScreenEvent(startTs + (durationMs * 0.6).toLong(), startTs + (durationMs * 0.75).toLong(), false),
            ScreenEvent(startTs + (durationMs * 0.75).toLong(), now, true)
        )

        // 3. 模拟 App 活动事件
        val appEvents = mutableListOf<AppTimelineEvent>()
        val icon = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)

        // App 1: 微信
        val wxStart = startTs + 300_000L
        val wxEnd = wxStart + 720_000L
        appEvents.add(
            AppTimelineEvent(
                packageName = "com.tencent.mm",
                uid = 10185,
                appName = "微信",
                icon = icon,
                startTime = wxStart,
                endTime = wxEnd,
                screenOn = true,
                energyMwh = 38.4,
                averagePowerMw = 960.0,
                peakPowerMw = 1650.0,
                cpuTimeMs = 120_000L,
                networkBytes = 1024 * 1024 * 15L,
                wakelockTimeMs = 25_000L,
                gpsTimeMs = 0L,
                confidence = ConfidenceLevel.HIGH,
                source = EnergySource.BATTERY_STATS
            )
        )

        // App 2: 地图
        val mapStart = wxStart + 500_000L
        val mapEnd = mapStart + 900_000L
        appEvents.add(
            AppTimelineEvent(
                packageName = "com.autonavi.minimap",
                uid = 10190,
                appName = "高德地图",
                icon = icon,
                startTime = mapStart,
                endTime = mapEnd,
                screenOn = true,
                energyMwh = 62.0,
                averagePowerMw = 1480.0,
                peakPowerMw = 2300.0,
                cpuTimeMs = 310_000L,
                networkBytes = 1024 * 1024 * 28L,
                wakelockTimeMs = 150_000L,
                gpsTimeMs = 800_000L,
                confidence = ConfidenceLevel.HIGH,
                source = EnergySource.BATTERY_STATS
            )
        )

        // App 3: 相机
        val camStart = mapEnd + 120_000L
        val camEnd = camStart + 400_000L
        appEvents.add(
            AppTimelineEvent(
                packageName = "com.android.camera",
                uid = 10020,
                appName = "相机",
                icon = icon,
                startTime = camStart,
                endTime = camEnd,
                screenOn = true,
                energyMwh = 45.0,
                averagePowerMw = 2100.0,
                peakPowerMw = 2950.0,
                cpuTimeMs = 280_000L,
                networkBytes = 0L,
                wakelockTimeMs = 80_000L,
                gpsTimeMs = 100_000L,
                confidence = ConfidenceLevel.MEDIUM,
                source = EnergySource.ESTIMATED
            )
        )

        return Triple(samples, screenEvents, appEvents)
    }
}
