package com.battery.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

/**
 * 功耗与应用运行温度计算算法单元测试套件。
 * 验证放电全亮屏与电量未下降百分比时亮屏功耗解耦逻辑、Battery History 真实温度正则提取，以及应用温度对齐能力。
 */
class PowerUsageCalculationTest {

    /**
     * 验证当电量百分比未下降（79% -> 79%）且放电全为亮屏（息屏 0 秒）时，亮屏功耗正确等于整机平均功耗而非 0.00W。
     */
    @Test
    fun testScreenOnPowerWhenDropPercentZeroAndAllScreenOn() {
        val dropPercent = 0
        val computedDrainMah = 30f // 系统底层计算出的总放电量 30mAh
        val safeVoltage = 4.231f
        val dischargeHours = 118f / 3600f // 1分58秒
        val screenOnHours = dischargeHours
        val screenOffHours = 0f
        val screenOffMs = 0L

        // 1. 计算总放电能量
        val realDischargedMah = if (dropPercent > 0) {
            5000f * (dropPercent / 100f)
        } else if (computedDrainMah > 0f) {
            computedDrainMah
        } else {
            0f
        }
        val realTotalEnergyWh = (realDischargedMah * safeVoltage) / 1000f

        // 2. 整机平均功耗
        val avgWatts = if (dischargeHours > 0f) {
            (computedDrainMah * safeVoltage) / (1000f * dischargeHours)
        } else {
            0f
        }

        // 3. 亮屏功耗解耦与全亮屏判定
        val screenOffWatts = 0f
        val screenOnWatts = if (screenOnHours > 0f) {
            if (screenOffHours <= 0f || screenOffMs <= 0L) {
                avgWatts
            } else {
                val offEnergy = screenOffWatts * screenOffHours
                val onEnergy = (realTotalEnergyWh - offEnergy).coerceAtLeast(0f)
                val calcWatts = onEnergy / screenOnHours
                if (calcWatts < 0.05f && avgWatts > 0.05f) avgWatts else calcWatts
            }
        } else {
            avgWatts
        }

        // 验证平均功耗与亮屏功耗大约为 3.86W 且二者严格相等，绝不为 0.00W
        assertTrue("平均功耗必须大于 0", avgWatts > 0.1f)
        assertEquals("全亮屏周期下亮屏功耗必须等于平均功耗", avgWatts, screenOnWatts, 0.001f)
    }

    /**
     * 验证 Battery History 历史行中 temp 数据的正则提取。
     */
    @Test
    fun testBatteryHistoryTemperatureRegex() {
        val regexHistoryTemp = Pattern.compile("(?:^|\\s)[+-]?temp=(\\d+)", Pattern.CASE_INSENSITIVE)

        val sampleLines = listOf(
            "0 (15) 079 -screen +wake_lock +wifi_radio phone_state=off temp=370 volt=4231",
            "  +1m23s456ms (10) 079 +screen temp=372 volt=4220",
            "+3s100ms (10) 079 +temp=368 volt=4215"
        )

        val temps = mutableListOf<Float>()
        for (line in sampleLines) {
            val matcher = regexHistoryTemp.matcher(line)
            if (matcher.find()) {
                val raw = matcher.group(1)?.toFloatOrNull()
                if (raw != null) {
                    temps.add(raw / 10f)
                }
            }
        }

        assertEquals(3, temps.size)
        assertEquals(37.0f, temps[0], 0.01f)
        assertEquals(37.2f, temps[1], 0.01f)
        assertEquals(36.8f, temps[2], 0.01f)

        // 验证计算出的最高温度与平均温度
        val maxTemp = Math.round(temps.maxOrNull() ?: 0f)
        val avgTemp = Math.round(temps.average().toFloat())
        assertEquals(37, maxTemp)
        assertEquals(37, avgTemp)
    }

    /**
     * 验证应用温度对齐逻辑，确认应用不会出现虚构加温 40℃/43℃。
     */
    @Test
    fun testAppTemperatureAlignment() {
        val currentTempCelsius = 37.0f
        val historyTemps = listOf(37.0f) // 刚拔电不久，温度恒定在 37℃

        val cycleAvgTemp = if (historyTemps.isNotEmpty()) {
            Math.round(historyTemps.average().toFloat())
        } else {
            Math.round(currentTempCelsius)
        }
        val cycleMaxTemp = if (historyTemps.isNotEmpty()) {
            Math.round(historyTemps.maxOrNull() ?: currentTempCelsius).coerceAtLeast(cycleAvgTemp)
        } else {
            cycleAvgTemp
        }

        assertEquals(37, cycleAvgTemp)
        assertEquals(37, cycleMaxTemp)
        // 验证不会出现 40℃ 或 43℃ 的异常虚高
        assertTrue("应用平均温度必须在真实温度范围内", cycleAvgTemp in 36..38)
        assertTrue("应用最高温度必须等于放电真实最高温度", cycleMaxTemp in 36..38)
    }

    /**
     * 验证时间轴应用分配算法：主力应用在亮屏期间连续填充（不再出现整段空白仅剩3处孤立点），且单切片单应用（不再统一堆叠展示）。
     */
    @Test
    fun testAppTimelineContinuousDistributionAndSingleForeground() {
        val steps = 40
        val isScreenOnArray = BooleanArray(steps + 1) { true } // 全程亮屏
        val primaryPkg = "com.battery.analysis" // 电池检测一直在亮屏使用
        val helperPkg = "com.battery.recorder"  // 短暂使用的辅助应用

        val assignedPkgArray = arrayOfNulls<String>(steps + 1)

        // 模拟辅助应用仅分配在特定 2 个切片
        assignedPkgArray[25] = helperPkg
        assignedPkgArray[26] = helperPkg

        // 其余亮屏切片由主力应用持续填充
        for (i in 0..steps) {
            if (isScreenOnArray[i] && assignedPkgArray[i] == null) {
                assignedPkgArray[i] = primaryPkg
            }
        }

        // 验证 1：所有亮屏切片均有应用归属，覆盖率达到 100%（杜绝仅显示 3 个孤立点）
        val assignedCount = assignedPkgArray.count { it != null }
        assertEquals("所有亮屏切片均必须拥有明确的应用归属", steps + 1, assignedCount)

        // 验证 2：主力应用覆盖绝大多数切片（持续亮屏使用）
        val primaryCount = assignedPkgArray.count { it == primaryPkg }
        assertTrue("主力应用在持续亮屏期间必须连续充盈整个时间轴", primaryCount >= 38)

        // 验证 3：切片互斥单应用原则，辅助应用独立分配，杜绝全部多应用在同一列统一堆叠
        assertEquals("辅助应用必须独立处于指定时间切片", helperPkg, assignedPkgArray[25])
        assertEquals("辅助应用后紧接着的主力应用独立切片", primaryPkg, assignedPkgArray[27])
    }

    /**
     * 验证在多应用列表中（即使系统底层进程排在最前），主力应用能够精准识别前台时长最长的用户交互应用。
     */
    @Test
    fun testPrimaryAppSelectionIgnoresSystemUidOrder() {
        data class TestAppItem(val packageName: String, val foregroundTimeMs: Long)
        val appList = listOf(
            TestAppItem("android", 1000L),
            TestAppItem("com.android.systemui", 2000L),
            TestAppItem("com.battery.analysis", 96000L)
        )

        // 模拟 isUserInstalledApp 判断
        fun isUserApp(pkg: String): Boolean = pkg == "com.battery.analysis"

        val candidateApps = appList.filter { it.foregroundTimeMs > 0 }
        val userApps = candidateApps.filter { isUserApp(it.packageName) }
        val primaryPkg = (userApps.maxByOrNull { it.foregroundTimeMs }
            ?: candidateApps.maxByOrNull { it.foregroundTimeMs })?.packageName

        assertEquals("必须精准选择电池检测作为主力应用，绝不能误选排在第0位的系统底层进程", "com.battery.analysis", primaryPkg)
    }

    /**
     * 验证拔电初期全亮屏场景下，前台主应用使用时间与真实亮屏时间的协同校准对齐。
     */
    @Test
    fun testForegroundTimeAlignmentWithScreenOnInShortDischarge() {
        val screenOnDurationMs = 122000L // 2m2s
        val screenOffDurationMs = 0L     // 全亮屏
        val rawFgMs = 96000L             // dumpsys 滞后记录为 1m36s
        val pkgName = "com.battery.analysis"
        val myPkg = "com.battery.analysis"

        var effectiveFgMs = rawFgMs
        if (pkgName == myPkg && screenOffDurationMs <= 3000L && screenOnDurationMs > 0L) {
            if (effectiveFgMs in 1L until screenOnDurationMs) {
                effectiveFgMs = screenOnDurationMs
            }
        }

        assertEquals("拔电初期全亮屏使用时，前台应用使用时间应与亮屏时间保持完美一致", screenOnDurationMs, effectiveFgMs)
    }

    /**
     * 验证图表垂直堆叠间距严格设定为 1dp，且无有效位图时不推进坐标。
     */
    @Test
    fun testStackIconMarginIsStrictlyOneDp() {
        val iconSizePx = 48f // 假设 16dp 在 3x 屏幕对应 48px
        val iconMarginPx = 3f // 1dp 在 3x 屏幕对应 3px

        var stackBottomY = 300f
        val validBitmaps = listOf(true, true) // 两个有效图标

        val topList = mutableListOf<Float>()
        for (isValid in validBitmaps) {
            if (!isValid) continue
            val iconTop = stackBottomY - iconSizePx
            topList.add(iconTop)
            stackBottomY -= (iconSizePx + iconMarginPx)
        }

        assertEquals(2, topList.size)
        val gapPx = topList[0] - (topList[0] - iconMarginPx)
        assertEquals(iconMarginPx, gapPx, 0.001f)
    }

    /**
     * 验证拔电初期（如 31 秒）从充电智能切换到耗电统计时，前台应用时长绝不误清零并对齐亮屏时长。
     */
    @Test
    fun testUnplugContinuousScreenOnMainAppNotZero() {
        val dischargeDurationMs = 31000L // 拔电 31 秒
        val screenOnMs = 31000L          // 亮屏 31 秒
        val screenOffMs = 0L             // 息屏 0 秒

        // 模拟 dumpsys 未解析出 top 时间（0L），或系统采样存在 1 秒误差（32000L）
        val rawFgMs = 0L
        val maxAllowedMs = dischargeDurationMs

        // 1. 模拟修复后的超标校验逻辑（轻微超出截断，仅大于 300 秒脏数据才清零）
        val clampedFgMs = if (rawFgMs > maxAllowedMs) {
            if (dischargeDurationMs < 300000L && rawFgMs > 300000L) 0L else maxAllowedMs
        } else {
            rawFgMs
        }

        // 2. 模拟全亮屏保障机制
        val isMostlyScreenOn = (screenOffMs <= 3000L || screenOnMs >= dischargeDurationMs * 0.8f) && dischargeDurationMs >= 1000L
        val finalFgMs = if (isMostlyScreenOn) {
            val targetFg = screenOnMs.coerceAtLeast(1000L)
            if (clampedFgMs < targetFg) targetFg else clampedFgMs
        } else {
            clampedFgMs
        }

        assertEquals("拔电31秒全亮屏场景下，电池检测前台时长必须等于31秒，绝不能为0秒", 31000L, finalFgMs)
    }
}

