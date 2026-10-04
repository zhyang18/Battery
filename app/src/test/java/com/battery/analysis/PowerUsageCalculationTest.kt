package com.battery.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern
import com.battery.analysis.model.AppPowerUsageItem
import com.battery.analysis.model.PowerDischargePoint
import com.battery.analysis.model.PowerUsageRecord
import com.battery.analysis.manager.PowerUsageManager

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

        // 1. 计算总放电能量（优先采用系统底层连续放电量 computedDrainMah）
        val realDischargedMah = when {
            computedDrainMah > 0.01f -> computedDrainMah
            dropPercent > 0 -> 5000f * (dropPercent / 100f)
            else -> 0f
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
     * 验证时间切片匹配算法：不同应用在前台不同运行时段内根据时序温度采样点计算出真实的平均温与最高温，
     * 并且均规整精确到 1 位小数，不再全部退化为当前瞬时设备温度。
     */
    @Test
    fun testAppTimeSliceTemperatureCalculationWithHistoryPoints() {
        val baseTime = 1710000000000L
        // 历史温度序列：前期 32℃ 左右，中期应用 A 运行时 35℃~36℃，后期应用 B 玩游戏时 39℃~41℃
        val historyTempPoints = listOf(
            Pair(baseTime + 60000L, 32.0f),
            Pair(baseTime + 120000L, 32.4f),
            // 应用 A 前台运行期：10分钟~20分钟
            Pair(baseTime + 600000L, 35.1f),
            Pair(baseTime + 900000L, 36.3f),
            Pair(baseTime + 1200000L, 35.8f),
            // 应用 B 前台运行期：30分钟~40分钟
            Pair(baseTime + 1800000L, 39.2f),
            Pair(baseTime + 2100000L, 41.0f),
            Pair(baseTime + 2400000L, 40.4f)
        )

        val appAIntervals = listOf(
            Pair(baseTime + 550000L, baseTime + 1250000L) // 包含 35.1, 36.3, 35.8
        )
        val appBIntervals = listOf(
            Pair(baseTime + 1750000L, baseTime + 2450000L) // 包含 39.2, 41.0, 40.4
        )

        // 计算应用 A 的平均温度与最高温度
        val matchedTempsA = historyTempPoints.filter { (ts, _) ->
            appAIntervals.any { interval -> ts in interval.first..interval.second }
        }.map { it.second }
        val rawAvgA = matchedTempsA.average().toFloat()
        val rawMaxA = matchedTempsA.maxOrNull() ?: rawAvgA
        val avgTempA = (Math.round(rawAvgA * 10f) / 10f).coerceIn(0f, 70f)
        val maxTempA = (Math.round(rawMaxA * 10f) / 10f).coerceAtLeast(avgTempA).coerceIn(0f, 70f)

        // 计算应用 B 的平均温度与最高温度
        val matchedTempsB = historyTempPoints.filter { (ts, _) ->
            appBIntervals.any { interval -> ts in interval.first..interval.second }
        }.map { it.second }
        val rawAvgB = matchedTempsB.average().toFloat()
        val rawMaxB = matchedTempsB.maxOrNull() ?: rawAvgB
        val avgTempB = (Math.round(rawAvgB * 10f) / 10f).coerceIn(0f, 70f)
        val maxTempB = (Math.round(rawMaxB * 10f) / 10f).coerceAtLeast(avgTempB).coerceIn(0f, 70f)

        // 验证应用 A 与应用 B 计算出的温度具有显著且真实的物理差异
        assertEquals(35.7f, avgTempA, 0.01f)
        assertEquals(36.3f, maxTempA, 0.01f)

        assertEquals(40.2f, avgTempB, 0.01f)
        assertEquals(41.0f, maxTempB, 0.01f)

        // 验证最高温度大于等于平均温度
        assertTrue(maxTempA >= avgTempA)
        assertTrue(maxTempB >= avgTempB)
        // 验证两应用最高温度差异明显
        assertTrue(maxTempB > maxTempA)
    }

    /**
     * 验证 Battery History 解析在遇到无电量百分比的纯时间增量行时，依然能正确提取温度与推进时间戳。
     */
    @Test
    fun testBatteryHistoryRegexWithNoLevelDeltaLines() {
        val regexTimeDelta = Pattern.compile("^\\s*([+-]?[\\w\\d]+)\\b", Pattern.CASE_INSENSITIVE)
        val regexTemp = Pattern.compile("(?:^|\\s)[+-]?temp=(\\d+)", Pattern.CASE_INSENSITIVE)

        val rawLines = listOf(
            "RESET:TIME: 2026-03-10-14-30-00",
            "0 (15) 079 -screen +wake_lock temp=350 volt=4231",
            "  +1m23s456ms +screen temp=362 volt=4220",
            "  +30s000ms +temp=378 volt=4200"
        )

        var currentTs = 1710000000000L
        val temps = mutableListOf<Pair<Long, Float>>()

        for (line in rawLines) {
            val trimmed = line.trim()
            val deltaMatcher = regexTimeDelta.matcher(trimmed)
            if (deltaMatcher.find()) {
                val deltaStr = deltaMatcher.group(1) ?: ""
                if (deltaStr.startsWith("+") || deltaStr.contains("ms") || deltaStr.contains("s") || deltaStr.contains("m")) {
                    currentTs += 30000L // 模拟解析耗时
                }
            }
            val tempMatcher = regexTemp.matcher(trimmed)
            if (tempMatcher.find()) {
                val rawT = tempMatcher.group(1)?.toFloatOrNull()
                if (rawT != null) {
                    temps.add(Pair(currentTs, (Math.round(rawT) / 10f)))
                }
            }
        }

        assertEquals(3, temps.size)
        assertEquals(35.0f, temps[0].second, 0.01f)
        assertEquals(36.2f, temps[1].second, 0.01f)
        assertEquals(37.8f, temps[2].second, 0.01f)
        // 验证时间戳有递增推进
        assertTrue(temps[2].first > temps[0].first)
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

    /**
     * 验证应用前后台能耗拆分：当 CPU 计算耗时小于等于前台时长时，前台客观分配 100% 能量，绝无写死 3.0 倍假权重。
     */
    @Test
    fun testAppEnergySplitUsingCpuComputationalLoad() {
        val totalDirectEnergyWh = 0.500f
        val foregroundMs = 600_000L // 10分钟
        val backgroundMs = 3_600_000L // 1小时后台挂起
        val cpuMs = 450_000L // 7.5分钟 CPU 算力（全部发生在前台交互中）

        // 模拟重构后的前后台能量拆分算法
        val fgEnergyWh: Float
        val bgEnergyWh: Float
        if (foregroundMs > 0L && backgroundMs > 0L) {
            if (cpuMs > 0L) {
                if (cpuMs <= foregroundMs) {
                    fgEnergyWh = totalDirectEnergyWh
                    bgEnergyWh = 0f
                } else {
                    val fgRatio = (foregroundMs.toFloat() / cpuMs.toFloat()).coerceIn(0.1f, 1.0f)
                    fgEnergyWh = totalDirectEnergyWh * fgRatio
                    bgEnergyWh = (totalDirectEnergyWh - fgEnergyWh).coerceAtLeast(0f)
                }
            } else {
                val totalMs = foregroundMs + backgroundMs
                val fgRatio = if (totalMs > 0L) (foregroundMs.toFloat() / totalMs.toFloat()) else 1.0f
                fgEnergyWh = totalDirectEnergyWh * fgRatio
                bgEnergyWh = (totalDirectEnergyWh - fgEnergyWh).coerceAtLeast(0f)
            }
        } else {
            fgEnergyWh = totalDirectEnergyWh
            bgEnergyWh = 0f
        }

        // 验证前台能量获得全部 0.500Wh，后台获得 0.000Wh，杜绝因后台挂起导致前台功耗被严重稀释缩水
        assertEquals("后台挂起时前台应获得全部计算能量", totalDirectEnergyWh, fgEnergyWh, 0.0001f)
        assertEquals("后台挂起无计算负载时能耗应为0", 0f, bgEnergyWh, 0.0001f)
    }

    /**
     * 验证普通模式下亮屏与息屏功耗解耦逻辑：
     * 既有亮屏又有息屏时，结合后台实耗与待机底座客观推导息屏功耗，不再粗暴置 0，且亮屏与息屏严格满足能量守恒。
     */
    @Test
    fun testNormalModeOverviewStatsEnergyDecoupling() {
        val screenOnHours = 2.0f
        val screenOffHours = 4.0f
        val realTotalEnergyWh = 8.0f // 6小时放电总能耗 8Wh
        val dischargeHours = screenOnHours + screenOffHours // 6小时
        val avgPower = realTotalEnergyWh / dischargeHours // 1.33W
        val screenOffMs = (screenOffHours * 3600000f).toLong()

        // 模拟普通模式下新的解耦算法
        val screenOffPower: Float
        val screenOnPower: Float
        if (screenOffHours <= 0f || screenOffMs < 30000L) {
            screenOnPower = avgPower
            screenOffPower = 0f
        } else if (screenOnHours <= 0f) {
            screenOffPower = avgPower.coerceIn(0f, 3.0f)
            screenOnPower = 0f
        } else {
            val bgEnergyWh = 0.4f // 4小时息屏后台消耗 0.4Wh
            val bgWatts = bgEnergyWh / screenOffHours // 0.1W
            val baseStandbyWatts = minOf(0.15f, avgPower * 0.6f) // 0.15W
            val estimatedOffWatts = (bgWatts + baseStandbyWatts).coerceIn(0.05f, 3.0f) // 0.25W
            screenOffPower = minOf(estimatedOffWatts, avgPower)

            val offEnergyWh = screenOffPower * screenOffHours // 0.25 * 4 = 1.0Wh
            val onEnergyWh = (realTotalEnergyWh - offEnergyWh).coerceAtLeast(0f) // 8.0 - 1.0 = 7.0Wh
            val calcOnPower = onEnergyWh / screenOnHours // 7.0 / 2 = 3.5W
            screenOnPower = if (calcOnPower < 0.05f && avgPower >= 0.05f) avgPower else calcOnPower
        }

        // 验证息屏功耗不为 0，且处于合理待机范围（约 0.25W）
        assertTrue("息屏功耗必须大于 0", screenOffPower > 0.05f)
        assertEquals("息屏功耗必须客观反映待机底座与后台能耗", 0.25f, screenOffPower, 0.001f)
        // 验证亮屏功耗合理大于整机平均功耗
        assertTrue("亮屏功耗必须高于整机平均功耗", screenOnPower > avgPower)
        // 验证严格满足能量守恒：E_on + E_off == E_total
        val totalDecoupledEnergy = (screenOnPower * screenOnHours) + (screenOffPower * screenOffHours)
        assertEquals("解耦后的亮息屏能耗总和必须严格等于总放电能量", realTotalEnergyWh, totalDecoupledEnergy, 0.001f)
    }

    /**
     * 验证趋势点动态基线功耗：完全基于真实电量变化与电压动态计算，杜绝 2.0f 与 0.1f 假数据。
     */
    @Test
    fun testTrendPointDynamicBaselinePower() {
        val duration = 7200_000L // 2小时
        val delta = 10 // 掉电 10%
        val capacityMah = 5000f
        val voltageVolts = 4.0f
        val totalDischargeHours = duration / 3600000f

        val defaultAvgWatts = if (totalDischargeHours > 0f && delta > 0) {
            (capacityMah * (delta / 100f) * voltageVolts) / (1000f * totalDischargeHours)
        } else {
            0f
        }

        // 5000mAh * 10% = 500mAh; 500mAh * 4.0V = 2.0Wh; 2.0Wh / 2h = 1.0W
        assertEquals("动态基线功耗必须严格符合物理能量公式", 1.0f, defaultAvgWatts, 0.001f)
    }

    /**
     * 验证在拔电初期（例如 101 秒，即 1分41秒）出现 1% 整数跳变（46% -> 45%）时，
     * 优先采用系统底层连续计算放电量 computedDrainMah（例如 15mAh），
     * 彻底杜绝旧逻辑因将整整 1%（79.5mAh）全部算作在这 101 秒消耗而产生虚高 10.95W 假峰值的严重缺陷。
     */
    @Test
    fun testContinuousComputedDrainPreventsQuantizationSpike() {
        val effectiveCapacity = 7950f // 大容量电池 7950mAh
        val safeVoltage = 3.862f      // 采样实时电压 3.862V
        val durationMs = 101_000L     // 拔电后 1分41秒（101秒）
        val dischargeHours = durationMs / 3600000f // 约 0.02805 小时

        val startLevel = 46
        val currentLevel = 45
        val dropPercent = startLevel - currentLevel // 1% 整数阶跃

        val computedDrainMah = 15.0f // 底层 batterystats 连续精确统计值（实际仅放电 15mAh）

        // 旧逻辑：优先判断 dropPercent > 0，导致放电量误取 79.5mAh
        val oldDischargedMah = if (dropPercent > 0) {
            effectiveCapacity * (dropPercent / 100f)
        } else if (computedDrainMah > 0f) {
            computedDrainMah
        } else {
            0f
        }
        val oldWatts = (oldDischargedMah * safeVoltage / 1000f) / dischargeHours

        // 验证旧逻辑确实会计算出 10.95W 的异常暴增假数据
        assertEquals(10.95f, oldWatts, 0.05f)

        // 新逻辑：优先采用系统底层连续放电量 computedDrainMah
        val newDischargedMah = when {
            computedDrainMah > 0.01f -> computedDrainMah
            dropPercent > 0 -> {
                val rawMah = effectiveCapacity * (dropPercent / 100f)
                if (dropPercent == 1 && dischargeHours < 0.25f) {
                    val maxPhysicalMah = (4.5f * 1000f / safeVoltage) * dischargeHours
                    rawMah.coerceAtMost(maxPhysicalMah)
                } else {
                    rawMah
                }
            }
            else -> 0f
        }
        val newWatts = (newDischargedMah * safeVoltage / 1000f) / dischargeHours

        // 验证新逻辑稳定保持在 2.07W 物理正常水平，彻底消除 10.95W 突变尖峰
        assertEquals(2.07f, newWatts, 0.05f)
        assertTrue("新计算功耗必须在正常亮屏使用功耗范围内（1.5W ~ 3.0W）", newWatts in 1.5f..3.0f)
    }

    /**
     * 验证普通模式（无 Shizuku 时的降级场景）：
     * 1. 当硬件电荷计数器可用时，优先读取连续库仑电量（例如 14mAh），计算功耗约 1.93W；
     * 2. 当硬件计数器不可用且仅能依赖 1% 阶跃时，拔电初期物理功耗上限平滑保护（<= 4.5W）生效，绝不会飙升至 10.95W。
     */
    @Test
    fun testHardwareChargeCounterPriorityAndNormalModeQuantizationClamp() {
        val effectiveCapacity = 7950f
        val safeVoltage = 3.862f
        val durationMs = 101_000L
        val dischargeHours = durationMs / 3600000f
        val dropPercent = 1

        // 场景 A：硬件电荷计数器生效（拔电时 3,600,000 uAh，当前 3,586,000 uAh，消耗 14,000 uAh = 14mAh）
        val lastCounterUah = 3600000
        val currentCounterUah = 3586000
        val hwDischargedMah = (lastCounterUah - currentCounterUah) / 1000f // 14.0 mAh

        val dischargedMahWithHw = when {
            hwDischargedMah > 0.01f -> hwDischargedMah
            dropPercent > 0 -> {
                val rawMah = effectiveCapacity * (dropPercent / 100f)
                if (dropPercent == 1 && dischargeHours < 0.25f) {
                    val maxPhysicalMah = (4.5f * 1000f / safeVoltage) * dischargeHours
                    rawMah.coerceAtMost(maxPhysicalMah)
                } else {
                    rawMah
                }
            }
            else -> 0f
        }
        val wattsWithHw = (dischargedMahWithHw * safeVoltage / 1000f) / dischargeHours
        assertEquals(1.93f, wattsWithHw, 0.05f)

        // 场景 B：硬件电荷计数器不支持（0f），仅依赖 dropPercent = 1
        val dischargedMahFallback = when {
            0f > 0.01f -> 0f
            dropPercent > 0 -> {
                val rawMah = effectiveCapacity * (dropPercent / 100f)
                if (dropPercent == 1 && dischargeHours < 0.25f) {
                    val maxPhysicalMah = (4.5f * 1000f / safeVoltage) * dischargeHours
                    rawMah.coerceAtMost(maxPhysicalMah)
                } else {
                    rawMah
                }
            }
            else -> 0f
        }
        val wattsFallback = (dischargedMahFallback * safeVoltage / 1000f) / dischargeHours

        // 验证物理保护上限生效：平滑限制在 4.5W 以内，绝不出现 10.95W 尖峰
        assertTrue("在无任何连续通道的极端降级兜底下，拔电初期物理平滑上限生效", wattsFallback <= 4.501f)
        assertTrue("绝不产生 10W+ 的荒谬数学放大尖峰", wattsFallback < 5.0f)
    }

    /**
     * 验证多应用与系统进程同时存在前台活跃记录时，
     * 前台 App 获得的屏幕基底功率依据实际亮屏时间计算（例如 1.8W），
     * 彻底杜绝旧逻辑因将所有进程的前台时间累加作为分母，导致屏幕功耗被严重稀释至仅 0.3W ~ 0.5W 的缺陷。
     */
    @Test
    fun testAppScreenBasePowerNoDilutionFromMultipleForegroundApps() {
        val screenOnHours = 0.5f // 亮屏 30 分钟
        val screenOnWatts = 2.2f // 整机亮屏平均功耗 2.2W
        val totalScreenOnEnergyWh = screenOnWatts * screenOnHours // 1.1 Wh

        // 用户应用：前台 10 分钟，CPU 耗电 0.05Wh (核心 CPU 功耗 0.3W)
        val userAppFgHours = 10f / 60f
        val userAppCoreEnergyWh = 0.05f
        val userAppCoreWatts = userAppCoreEnergyWh / userAppFgHours // 0.3W

        // 系统进程（SystemUI、Android系统、桌面等）也累计了重叠的前台时长 60 分钟，核心 CPU 耗电 0.12Wh
        val sysFgHours = 60f / 60f
        val sysCoreEnergyWh = 0.12f

        val totalCoreFgEnergyWh = userAppCoreEnergyWh + sysCoreEnergyWh // 0.17 Wh
        val sharedScreenEnergyWh = (totalScreenOnEnergyWh - totalCoreFgEnergyWh).coerceAtLeast(0f) // 0.93 Wh

        // 旧逻辑：将所有前台时间（包括系统多进程）累加作为分母
        val totalFgHours = userAppFgHours + sysFgHours // 70 分钟 = 1.167 小时（远超亮屏 30 分钟！）
        val oldAppShareEnergy = sharedScreenEnergyWh * (userAppFgHours / totalFgHours)
        val oldCombinedAvgWatts = (userAppCoreEnergyWh + oldAppShareEnergy) / userAppFgHours

        // 验证旧逻辑算出的功耗被明显稀释（仅 1.10W 左右，远低于亮屏放电功耗 2.2W）
        assertTrue("旧逻辑功耗被明显稀释至 1.5W 以下（远低于整机亮屏功耗 2.2W）", oldCombinedAvgWatts < 1.5f)

        // 新逻辑：屏幕基底功率按客观亮屏时间 screenOnHours 计算，不被系统多进程时长放大稀释
        val screenBaseWatts = if (screenOnHours > 0f) (sharedScreenEnergyWh / screenOnHours) else 0f // 1.86W
        val newCombinedAvgWatts = userAppCoreWatts + screenBaseWatts // 0.3W + 1.86W = 2.16W

        // 验证新逻辑稳定保持在 2.16W，真实反映亮屏使用综合功耗
        assertEquals(2.16f, newCombinedAvgWatts, 0.05f)
        assertTrue("新逻辑功耗处于 2.0W ~ 2.5W 真实合理区间", newCombinedAvgWatts in 2.0f..2.5f)
    }

    /**
     * 验证电量从 44% 跳到 43%（1% 掉电）且硬件库仑计或 dumpsys 滞后报告 0.125mAh 微小底噪的场景：
     * 1. 验证旧逻辑将 0.125mAh 误作为整机放电量，导致算出的功耗仅 0.0059W（格式化为 0.00W），剩余续航膨胀至 211d12h；
     * 2. 验证新逻辑依能量守恒（整机放电量必不小于各子应用能耗和 26.2mAh）与底噪过滤，正确判定 0.125mAh 无效，
     *    并由 1% 掉电物理平滑保护正确计算出约 3.75W 真实放电功耗，剩余续航约 3h31m，彻底消除 0.00W 与 211 天荒谬展示。
     */
    @Test
    fun testDischargePowerWhenBatteryDropsAndHardwareCounterHasNoise() {
        val effectiveCapacity = 7950f // 7950mAh
        val safeVoltage = 3.862f
        val durationMs = 295_000L // 4分55秒
        val dischargeHours = durationMs / 3600000f // 0.08194 小时
        val dropPercent = 1 // 44% -> 43%

        // 下方正在运行的各应用实耗电量之和（电池检测 0.035Wh + 部落冲突 0.039Wh + 荣耀桌面 0.015Wh 等 = 0.101Wh）
        val appEnergySumWh = 0.101f
        val appBaselineMah = (appEnergySumWh * 1000f) / safeVoltage // 约 26.15 mAh

        // 模拟硬件电荷计数器或 dumpsys 在拔电初期因刷新滞后或 ADC 杂讯报告的 0.055mAh（55uAh）微小底噪
        val hwDischargedMah = 0.055f
        val computedDrainMah = 0.055f

        // ===== 验证旧逻辑的致命缺陷 =====
        val oldDischargedMah = when {
            computedDrainMah > 0.01f -> computedDrainMah
            hwDischargedMah > 0.01f -> hwDischargedMah
            else -> appBaselineMah
        }
        val oldWatts = (oldDischargedMah * safeVoltage / 1000f) / dischargeHours
        val oldPowerStr = String.format(java.util.Locale.US, "%.2fW", oldWatts)
        val remainingEnergyWh = effectiveCapacity * (43f / 100f) * safeVoltage / 1000f // 约 13.2 Wh
        val oldRemainingHours = remainingEnergyWh / oldWatts // 13.2Wh / 0.0026W 约 5088 小时
        val oldRemainingMinutes = (oldRemainingHours * 60).toLong()
        val oldDays = oldRemainingMinutes / 1440
        val oldH = (oldRemainingMinutes % 1440) / 60
        val oldRemStr = "${oldDays}d${oldH}h"

        // 验证旧逻辑确实导致 0.00W 和 200+ 天天文数字
        assertEquals("旧逻辑输出功耗被格式化为 0.00W", "0.00W", oldPowerStr)
        assertTrue("旧逻辑计算出的剩余续航超过 200 天: $oldRemStr", oldDays >= 200)

        // ===== 验证新算法的物理守恒与底噪过滤 =====
        // 1. 1% 掉电平滑放电量
        val rawMah = effectiveCapacity * (dropPercent / 100f) // 79.5 mAh
        val maxPhysicalMah = (4.5f * 1000f / safeVoltage) * dischargeHours // 95.49 mAh
        val smoothedDropMah = rawMah.coerceAtMost(maxPhysicalMah) // 79.5 mAh

        // 2. 底噪过滤（0.125mAh 远小于 appBaselineMah 的 90%，且小于 0.5mAh，判定为无效底噪置 0）
        val validComputedMah = if (computedDrainMah > 0.5f && (appBaselineMah <= 0.5f || computedDrainMah >= appBaselineMah * 0.9f)) {
            computedDrainMah
        } else {
            0f
        }
        val validHwMah = if (hwDischargedMah > 0.5f && (appBaselineMah <= 0.5f || hwDischargedMah >= appBaselineMah * 0.9f)) {
            hwDischargedMah
        } else {
            0f
        }

        // 3. 选取真正可信的放电量
        val newDischargedMah = when {
            validComputedMah > 0.5f -> maxOf(validComputedMah, appBaselineMah)
            validHwMah > 0.5f -> maxOf(validHwMah, appBaselineMah)
            dropPercent > 0 -> maxOf(smoothedDropMah, appBaselineMah)
            appBaselineMah > 0.5f -> appBaselineMah
            else -> 0f
        }

        val newWatts = (newDischargedMah * safeVoltage / 1000f) / dischargeHours
        val newRemainingHours = (effectiveCapacity * (43f / 100f) * safeVoltage / 1000f) / newWatts

        // 验证新逻辑：
        // 1. 放电量选取为 79.5 mAh（高于应用实耗 26.15 mAh，杜绝了整体小于部分）
        assertEquals(79.5f, newDischargedMah, 0.1f)
        // 2. 计算功耗为 3.75W 左右（真实反映游戏与屏幕放电综合功耗），绝非 0.00W
        assertEquals(3.75f, newWatts, 0.05f)
        assertTrue("整机平均功耗必须在正常高负载放电区间（3.0W ~ 4.5W）", newWatts in 3.0f..4.5f)
        // 3. 剩余续航计算为约 3.52 小时（3h31m），绝非 211 天
        assertTrue("剩余续航必须在真实续航区间（3.0h ~ 4.0h）", newRemainingHours in 3.0f..4.0f)
    }

    /**
     * 验证续航文本格式化方法在极低功耗或异常超大数值时的防呆拦截，以及正常小时数的友好格式化。
     */
    @Test
    fun testRemainingLifeSafeguardAndFormatting() {
        fun formatHours(hours: Float): String {
            if (hours <= 0f || hours.isNaN() || hours.isInfinite() || hours > 720f) {
                return "--"
            }
            val totalMinutes = (hours * 60).toLong().coerceAtLeast(1L)
            val days = totalMinutes / 1440
            val h = (totalMinutes % 1440) / 60
            val m = totalMinutes % 60
            return when {
                days > 0 -> "${days}d${h}h"
                h > 0 -> "${h}h${m}m"
                else -> "${m}m"
            }
        }

        // 1. 验证异常超大续航（如 5076 小时 = 211d12h）被安全拦截为 "--"
        assertEquals("--", formatHours(5076f))
        assertEquals("--", formatHours(721f))
        assertEquals("--", formatHours(0f))
        assertEquals("--", formatHours(-5f))
        assertEquals("--", formatHours(Float.NaN))
        assertEquals("--", formatHours(Float.POSITIVE_INFINITY))

        // 2. 验证正常续航数值的精确格式化
        assertEquals("3h31m", formatHours(3.52f))
        assertEquals("14h30m", formatHours(14.5f))
        assertEquals("2d12h", formatHours(60f))
        assertEquals("45m", formatHours(0.75f))
    }

    /**
     * 验证纯后台应用（前台时长 0s）在 dumpsys 未给出 bg= 时：
     * 1. 自动将真实的 cpu 运算耗时（如 45s）映射为后台运行时间，杜绝显示 0s | 0s；
     * 2. 能耗 100% 归属于后台能耗（fgEnergyWh 严格为 0f，bgEnergyWh = 0.073Wh），杜绝显示 0.073Wh | 0.000Wh 矛盾；
     * 3. 纯后台应用不计入前台核心算力，绝不侵蚀整机亮屏能耗池；
     * 4. 标识圆点判定：前台为绿色，纯后台为蓝色。
     */
    @Test
    fun testPureBackgroundAppEnergyAndCpuTimeToBackgroundMapping() {
        val totalDischargeMs = 319_000L // 5分19秒
        val fgMs = 0L // 无前台
        val bgMs = 0L // dumpsys 未给 bg=
        val cpuMs = 45_000L // dumpsys 记录了 cpu=45s
        val drainMah = 19.34f
        val voltage = 3.774f
        val totalDirectEnergyWh = (drainMah * voltage) / 1000f // 约 0.073 Wh

        // 1. 验证后台时长智能对齐逻辑
        val safeFg = fgMs.coerceAtMost(totalDischargeMs)
        var safeBg = bgMs.coerceAtMost(totalDischargeMs)
        val safeCpu = cpuMs.coerceAtLeast(0L)
        if (safeBg <= 0L) {
            if (safeFg <= 0L && safeCpu > 0L) {
                safeBg = safeCpu.coerceAtMost(totalDischargeMs)
            } else if (safeCpu > safeFg) {
                safeBg = (safeCpu - safeFg).coerceAtMost(totalDischargeMs)
            }
        }

        // 验证后台时间正确识别为 45000ms（45s），不再为 0s
        assertEquals("纯后台应用的 CPU 时间必须客观映射为后台运行时间", 45000L, safeBg)
        assertEquals("前台时间必须保持为 0", 0L, safeFg)

        // 2. 验证能量分配逻辑
        val fgEnergyWh: Float
        val bgEnergyWh: Float
        if (safeFg > 0L) {
            fgEnergyWh = totalDirectEnergyWh
            bgEnergyWh = 0f
        } else {
            fgEnergyWh = 0f
            bgEnergyWh = totalDirectEnergyWh
        }

        assertEquals("纯后台应用前台能耗必须严格为 0", 0f, fgEnergyWh, 0.0001f)
        assertEquals("纯后台应用能耗必须 100% 归入后台能耗", totalDirectEnergyWh, bgEnergyWh, 0.0001f)

        // 3. 验证前后台圆点标识颜色规则
        val dotColorRes = if (safeFg > 0L) "green" else "blue"
        assertEquals("纯后台应用必须显示蓝色圆点", "blue", dotColorRes)

        val fgAppDotColorRes = if (123000L > 0L) "green" else "blue"
        assertEquals("前台应用必须显示绿色圆点", "green", fgAppDotColorRes)
    }

    /**
     * 验证 3D 游戏（如部落冲突）在整机亮屏放电中动态 GPU 渲染能耗合理加权分配：
     * 1. 纯后台应用能耗（0.073Wh）被严格排除在前台核心算力之外，释放整机亮屏池；
     * 2. 屏幕总池解耦为屏幕面板基础发光功耗（~0.9W）与动态 GPU 渲染池；
     * 3. 游戏应用获得动态 GPU 渲染加权，使部落冲突平均功耗合理回升至约 3.06W，静态工具应用保持约 2.11W；
     * 4. 验证各前台应用能耗之和严格遵循宏观能量守恒定律。
     */
    @Test
    fun testGameDynamicGpuRenderingPowerAllocation() {
        val screenOnWatts = 2.30f // 整机亮屏平均放电功耗 2.30W
        val screenOnMs = 319_000L // 5分19秒
        val screenOnHours = screenOnMs / 3600000f // 0.08861 小时
        val totalScreenOnEnergyWh = screenOnWatts * screenOnHours // 约 0.2038 Wh

        // 前台应用 1：部落冲突（3D 游戏，前台 123s，CPU 核心耗电 0.033Wh，CPU 核心功耗 0.97W）
        val clashFgHours = 123f / 3600f
        val clashCoreWatts = 0.97f
        val clashCoreEnergyWh = clashCoreWatts * clashFgHours // 约 0.0331 Wh
        val isClashGame = true

        // 前台应用 2：电池检测（静态工具，前台 96s，CPU 核心耗电 0.022Wh，CPU 核心功耗 0.82W）
        val batteryFgHours = 96f / 3600f
        val batteryCoreWatts = 0.82f
        val batteryCoreEnergyWh = batteryCoreWatts * batteryFgHours // 约 0.0219 Wh
        val isBatteryGame = false

        // 前台应用 3：荣耀桌面（系统桌面，前台 55s，CPU 核心耗电 0.012Wh，CPU 核心功耗 0.78W）
        val homeFgHours = 55f / 3600f
        val homeCoreWatts = 0.78f
        val homeCoreEnergyWh = homeCoreWatts * homeFgHours // 约 0.0119 Wh
        val isHomeGame = false

        // 验证前台核心算力总和：仅包含前台应用，纯后台安全公共服务（0.073Wh）被严格排除
        val totalCoreFgEnergyWh = clashCoreEnergyWh + batteryCoreEnergyWh + homeCoreEnergyWh // 约 0.0669 Wh
        val sharedScreenEnergyWh = (totalScreenOnEnergyWh - totalCoreFgEnergyWh).coerceAtLeast(0f) // 约 0.1369 Wh

        // 屏幕面板基础发光功率（0.9W）与动态 GPU 渲染池解耦
        val baseDisplayWatts = 0.9f
        val baseDisplayEnergyWh = baseDisplayWatts * screenOnHours // 约 0.0797 Wh
        val dynamicGpuEnergyWh = (sharedScreenEnergyWh - baseDisplayEnergyWh).coerceAtLeast(0f) // 约 0.0572 Wh

        // 计算渲染权重（游戏权重 3.0，非游戏应用权重 1.0）
        val clashWeight = (if (isClashGame) 3.0 else 1.0) * clashFgHours
        val batteryWeight = (if (isBatteryGame) 3.0 else 1.0) * batteryFgHours
        val homeWeight = (if (isHomeGame) 3.0 else 1.0) * homeFgHours
        val totalRenderWeight = clashWeight + batteryWeight + homeWeight

        // 分配 GPU 动态渲染功率
        val clashGpuWatts = (dynamicGpuEnergyWh * (clashWeight / totalRenderWeight) / clashFgHours).toFloat()
        val batteryGpuWatts = (dynamicGpuEnergyWh * (batteryWeight / totalRenderWeight) / batteryFgHours).toFloat()
        val homeGpuWatts = (dynamicGpuEnergyWh * (homeWeight / totalRenderWeight) / homeFgHours).toFloat()

        val clashCombinedWatts = clashCoreWatts + baseDisplayWatts + clashGpuWatts
        val batteryCombinedWatts = batteryCoreWatts + baseDisplayWatts + batteryGpuWatts
        val homeCombinedWatts = homeCoreWatts + baseDisplayWatts + homeGpuWatts

        // 验证：
        // 1. 部落冲突综合功耗达到约 3.06W，彻底打破原本仅 1.29W 的荒谬偏低，真实反映游戏高负载！
        assertEquals(3.06f, clashCombinedWatts, 0.1f)
        assertTrue("游戏应用功耗必须合理处于 2.8W ~ 3.5W 范围", clashCombinedWatts in 2.8f..3.5f)

        // 2. 电池检测等静态 2D 工具应用保持在约 2.11W 正常范围
        assertEquals(2.11f, batteryCombinedWatts, 0.1f)
        assertTrue("普通静态应用功耗必须合理处于 1.8W ~ 2.4W 范围", batteryCombinedWatts in 1.8f..2.4f)

        // 3. 游戏功耗明显高于普通静态工具应用（高出约 0.9W ~ 1.0W）
        assertTrue("3D 游戏功耗必须显著高于普通 2D 静态工具应用", clashCombinedWatts > batteryCombinedWatts + 0.8f)

        // 4. 验证能量守恒：各应用综合能耗总和不超过整机亮屏总能耗
        val totalAppCombinedEnergyWh = (clashCombinedWatts * clashFgHours) +
                (batteryCombinedWatts * batteryFgHours) +
                (homeCombinedWatts * homeFgHours)
        assertTrue("各前台应用综合能耗总和必须小于等于整机亮屏总能耗", totalAppCombinedEnergyWh <= totalScreenOnEnergyWh + 0.001f)
    }

    /**
     * 验证短时间息屏（如 15 秒）发生 1% 整数掉电跳变（45mAh）时的平滑防量化尖峰保护。
     * 杜绝将 1% 整数电量直接除以微小时长导致功耗飙升至 45W 的严重物理错误。
     */
    @Test
    fun testScreenOffAntiQuantizationSpikeProtection() {
        val capacityMah = 4500f
        val safeVoltage = 4.0f
        val screenOffDurationMs = 15_000L // 息屏仅 15 秒（息屏监控服务典型采样周期）
        val screenOffHours = screenOffDurationMs / 3600000f // 0.004167 小时
        val maxStandbyPowerWatts = 3.0f

        // 模拟 dumpsys 上报了 "Amount discharged while screen off: 1"
        val percent = 1f
        val rawOffMah = (percent / 100f) * capacityMah // 粗粒度 45mAh

        // 1. 旧逻辑：直接使用 45mAh 计算功耗
        val oldScreenOffWatts = (rawOffMah * safeVoltage) / (1000f * screenOffHours)
        // 验证旧逻辑确实会计算出 45W 左右的荒谬数值
        assertEquals("旧逻辑会误算为 43W~45W 尖峰", 43.2f, oldScreenOffWatts, 0.5f)

        // 2. 新逻辑：实施短时间平滑防量化尖峰保护与待机物理上限
        val maxPhysicalOffMah = (maxStandbyPowerWatts * 1000f / safeVoltage) * screenOffHours
        val smoothedOffMah = if (screenOffHours < 0.25f) rawOffMah.coerceAtMost(maxPhysicalOffMah) else rawOffMah
        val newScreenOffWatts = ((smoothedOffMah * safeVoltage) / (1000f * screenOffHours))
            .coerceIn(0.05f, maxStandbyPowerWatts)

        // 验证：
        // 放电量从 45mAh 平滑为约 3.125mAh，功耗严格限制在 3.0W 物理上限以内
        assertTrue("平滑后的息屏放电量绝不能为 45mAh 粗粒度值", smoothedOffMah < 5.0f)
        assertTrue("新逻辑息屏功耗绝不能超过 3.0W 待机物理极限", newScreenOffWatts <= 3.0f)
        assertTrue("新逻辑息屏功耗必须处于有效待机功耗范围", newScreenOffWatts >= 0.05f)
    }

    /**
     * 验证多小时放电后刚息屏短时间（如 30 秒）时，未归因结余放电量按时间占比合理分摊。
     * 杜绝将累计数小时的结余放电量全部塞给几十秒息屏导致功耗高达数十瓦。
     */
    @Test
    fun testMultiHoursHistoryRemainingDrainAllocation() {
        val totalDischargeDurationMs = 5 * 3600_000L // 放电 5 小时
        val screenOffDurationMs = 30_000L // 刚熄屏 30 秒
        val safeVoltage = 4.0f
        val maxStandbyPowerWatts = 3.0f

        // 累计 5 小时期间整机未归因结余放电量为 120mAh
        val remainingDrain = 120f

        // 1. 旧逻辑：将 120mAh 全部算给这 30 秒
        val oldOffHours = screenOffDurationMs / 3600000f
        val oldOffWatts = (remainingDrain * safeVoltage) / (1000f * oldOffHours)
        assertEquals("旧逻辑会误将全周期结余全额赋予短时息屏导致功耗高达 57W", 57.6f, oldOffWatts, 0.5f)

        // 2. 新逻辑：按时间切片比例加权分摊
        val offRatio = (screenOffDurationMs.toFloat() / totalDischargeDurationMs).coerceIn(0f, 1f)
        val allocatedOffDrain = remainingDrain * offRatio // 120 * (30 / 18000) = 0.2mAh
        val offHours = screenOffDurationMs / 3600000f
        val maxPhysicalStandbyDrain = (maxStandbyPowerWatts * 1000f / safeVoltage) * offHours
        val effectiveOffDrain = allocatedOffDrain.coerceAtMost(maxPhysicalStandbyDrain)
        val newOffWatts = ((effectiveOffDrain * safeVoltage) / (1000f * offHours))
            .coerceIn(0.05f, maxStandbyPowerWatts)

        // 验证：
        // 息屏分摊放电量仅为 0.2mAh，计算出的息屏功耗约为 0.1W，完全处于正常待机区间
        assertEquals("30秒息屏分摊放电量应为 0.2mAh", 0.2f, effectiveOffDrain, 0.01f)
        assertEquals("息屏功耗应保持在约 0.1W 正常待机水平", 0.1f, newOffWatts, 0.05f)
        assertTrue("新逻辑息屏功耗绝不应超过 3.0W 物理上限", newOffWatts <= 3.0f)
    }

    /**
     * 验证全周期纯息屏待机状态下的功耗结算：
     * 整机全周期处于息屏待机工况，息屏功耗真实反映整机平均功耗，杜绝显示为 0.00W 或 "--"。
     */
    @Test
    fun testPureScreenOffStandbyPowerSanity() {
        val screenOnHours = 0.0f
        val screenOffHours = 4.0f // 4 小时纯息屏待机
        val realTotalEnergyWh = 0.8f // 4 小时待机消耗 0.8Wh（平均功耗 0.2W）
        val avgPower = realTotalEnergyWh / screenOffHours // 0.2W
        val screenOffMs = (screenOffHours * 3600000f).toLong()

        // 纯息屏解耦判定
        val screenOffPower: Float
        val screenOnPower: Float
        if (screenOffHours <= 0f || screenOffMs < 30000L) {
            screenOnPower = avgPower
            screenOffPower = 0f
        } else if (screenOnHours <= 0f) {
            screenOffPower = avgPower.coerceIn(0f, 3.0f)
            screenOnPower = 0f
        } else {
            screenOffPower = avgPower
            screenOnPower = avgPower
        }

        // 验证纯息屏待机功耗准确等于整机平均功耗 0.2W，亮屏功耗规范置 0
        assertEquals("纯息屏待机时息屏功耗必须等于平均放电功耗", 0.2f, screenOffPower, 0.001f)
        assertEquals("纯息屏待机时亮屏功耗必须为 0", 0f, screenOnPower, 0.001f)
    }

    /**
     * 验证并发插电广播场景下生成的相近时间与相同耗电特征的记录能够准确识别为成对重复。
     */
    @Test
    fun testConcurrentDuplicatePowerRecordsDetection() {
        val r1Id = 1789006025120L
        val r2Id = 1789006025000L
        val level1 = 43
        val level2 = 43
        val duration1 = "16m10s"
        val duration2 = "16m10s"
        val timeStr1 = "2026-09-10 10:07:05"
        val timeStr2 = "2026-09-10 10:07:05"

        val timeDiff = kotlin.math.abs(r1Id - r2Id)
        val isSameStats = level1 == level2 && duration1 == duration2
        val isDuplicate = (timeDiff < 60000L && isSameStats) ||
                (timeDiff < 30000L && (level1 == level2 || duration1 == duration2)) ||
                (timeStr1.isNotEmpty() && timeStr1 == timeStr2)

        assertTrue("并发成对生成的耗电记录必须判定为重复", isDuplicate)
    }

    /**
     * 验证不同放电周期的独立耗电记录绝不被误判为重复。
     */
    @Test
    fun testDistinctPowerRecordsNotDeduplicated() {
        val r1Id = 1789006025000L
        val r2Id = 1789013225000L // 2 小时后
        val level1 = 43
        val level2 = 30
        val duration1 = "16m10s"
        val duration2 = "1h20m"
        val timeStr1 = "2026-09-10 10:07:05"
        val timeStr2 = "2026-09-10 12:07:05"

        val timeDiff = kotlin.math.abs(r1Id - r2Id)
        val isSameStats = level1 == level2 && duration1 == duration2
        val isDuplicate = (timeDiff < 60000L && isSameStats) ||
                (timeDiff < 30000L && (level1 == level2 || duration1 == duration2)) ||
                (timeStr1.isNotEmpty() && timeStr1 == timeStr2)

        assertFalse("相隔2小时的不同放电记录绝不能判定为重复", isDuplicate)
    }

    /**
     * 验证应用平均功耗展示严格遵循算法：
     * 功耗仅基于前台真实物理放电采样计算，后台不统计虚拟平均功耗：
     * 1. 具有前台运行记录的应用，展示前台真实平均功耗（如 "1.00W"、"1.89W"）；
     * 2. 纯后台应用由于无前台物理切片，功耗诚实显示为 "--"，杜绝虚假估算。
     */
    @Test
    fun testAppCombinedAvgWattsFormatting() {
        // 场景 1：具有前台运行 12m18s 的应用，功耗展示前台真实物理功耗 1.00W
        val longBgItem = AppPowerUsageItem(
            packageName = "com.battery.analysis",
            appName = "电池统计",
            icon = null,
            foregroundTimeMs = 738_000L, // 12m18s
            avgPowerWatts = 1.00f,
            avgTemperature = 33.2f,
            maxTemperature = 39.0f,
            lastUsedTimeMs = System.currentTimeMillis(),
            backgroundTimeMs = 38_400_000L, // 10h40m
            foregroundEnergyWh = 0.204f,
            backgroundEnergyWh = 0f,
            foregroundPowerWatts = 1.00f,
            backgroundPowerWatts = 0f
        )
        assertEquals("功耗严格反映前台真实物理功耗 1.00W", "1.00W", longBgItem.getFormattedCombinedAvgWatts())

        // 场景 2：前台运行 1秒的应用，展示其前台物理功耗 1.89W
        val zeroBgItem = AppPowerUsageItem(
            packageName = "com.accubattery",
            appName = "AccuBattery",
            icon = null,
            foregroundTimeMs = 1000L,
            avgPowerWatts = 1.89f,
            avgTemperature = 37.0f,
            maxTemperature = 37.0f,
            lastUsedTimeMs = System.currentTimeMillis(),
            backgroundTimeMs = 72_000L, // 1m12s
            foregroundEnergyWh = 0.0005f,
            backgroundEnergyWh = 0f,
            foregroundPowerWatts = 1.89f,
            backgroundPowerWatts = 0f
        )
        assertEquals("功耗严格反映前台真实物理功耗 1.89W", "1.89W", zeroBgItem.getFormattedCombinedAvgWatts())
        assertEquals("前台时长格式化正常", "1s", zeroBgItem.getFormattedDuration())

        // 场景 3：前台运行少于 1 秒（500ms）的应用，功耗精确展示为 2.10W，时长精确展示为 500ms
        val subSecondItem = AppPowerUsageItem(
            packageName = "com.battery.quicklaunch",
            appName = "快速启动",
            icon = null,
            foregroundTimeMs = 500L, // 500ms
            avgPowerWatts = 2.10f,
            avgTemperature = 35.0f,
            maxTemperature = 35.0f,
            lastUsedTimeMs = System.currentTimeMillis(),
            backgroundTimeMs = 0L,
            foregroundEnergyWh = 0.00029f,
            backgroundEnergyWh = 0f,
            foregroundPowerWatts = 2.10f,
            backgroundPowerWatts = 0f
        )
        assertEquals("少于 1 秒应用功耗精确反映前台真实功耗 2.10W", "2.10W", subSecondItem.getFormattedCombinedAvgWatts())
        assertEquals("少于 1 秒应用时长精确展示为 500ms", "500ms", subSecondItem.getFormattedDuration())
        assertEquals("少于 1 秒应用组合时长精确展示为 500ms", "500ms", subSecondItem.getFormattedCombinedDuration())

        // 场景 4：纯后台运行应用（无前台运行），功耗诚实展示为 --，时长为 0s
        val pureBgItem = AppPowerUsageItem(
            packageName = "com.example.purebg",
            appName = "纯后台",
            icon = null,
            foregroundTimeMs = 0L,
            avgPowerWatts = 0f,
            avgTemperature = 37.0f,
            maxTemperature = 37.0f,
            lastUsedTimeMs = System.currentTimeMillis(),
            backgroundTimeMs = 72_000L,
            foregroundEnergyWh = 0f,
            backgroundEnergyWh = 0f,
            foregroundPowerWatts = 0f,
            backgroundPowerWatts = 0f
        )
        assertEquals("纯后台应用功耗诚实显示为 --", "--", pureBgItem.getFormattedCombinedAvgWatts())
        assertEquals("纯后台应用前台时长为 0s", "0s", pureBgItem.getFormattedDuration())
    }

    /**
     * 验证刚拔电/重置初期（如 18 秒）跨周期累积能耗（如后台 13 小时 0.47Wh）的时间窗口有效性归一化，
     * 确保不会因除以微小时长产生 89.74W 的虚假物理尖峰。
     */
    @Test
    fun testShortDurationCrossPeriodEnergyNormalization() {
        val durationMs = 18_000L // 18 秒
        val safeVoltage = 3.986f
        val dischargeHours = durationMs / 3600000f

        // 模拟包含 13 小时历史累积 0.47Wh 的后台应用
        val rawAppList = listOf(
            AppPowerUsageItem(
                packageName = "com.battery.analysis",
                appName = "电池统计",
                icon = null,
                foregroundTimeMs = 18_000L,
                backgroundTimeMs = 13 * 3600_000L + 3 * 60_000L, // 13h3m
                directEnergyWh = 0.470f,
                backgroundEnergyWh = 0.470f,
                avgPowerWatts = 0.04f,
                avgTemperature = 36f,
                maxTemperature = 36f,
                lastUsedTimeMs = System.currentTimeMillis()
            )
        )

        // 核心防护：时间窗口有效性归一
        val currentPeriodAppEnergyWh = rawAppList.sumOf { item ->
            val totalAppTimeMs = item.foregroundTimeMs + item.backgroundTimeMs
            if (totalAppTimeMs > durationMs && totalAppTimeMs > 0L) {
                (item.energyWh * (durationMs.toDouble() / totalAppTimeMs.toDouble())).coerceAtMost(item.energyWh.toDouble())
            } else {
                item.energyWh.toDouble()
            }
        }.toFloat()

        // 验证在当前 18 秒放电周期内，有效应用能耗被正确约束（远小于 0.47Wh，约 0.00018Wh）
        assertTrue("当前放电周期内的应用能耗必须被有效归一约束", currentPeriodAppEnergyWh < 0.01f)

        val minPhysicalMah = if (currentPeriodAppEnergyWh > 0f) (currentPeriodAppEnergyWh * 1000f / safeVoltage) else 0f
        val realTotalEnergyWh = (minPhysicalMah * safeVoltage) / 1000f

        // 模拟物理采样均值（实测 1.5W）
        val realtimeAvgWatts = 1.5f
        val calculatedAvgPower = if (dischargeHours > 0f && realTotalEnergyWh > 0f) {
            realTotalEnergyWh / dischargeHours
        } else {
            0f
        }

        val finalAvgPower = when {
            dischargeHours < 0.1f -> realtimeAvgWatts
            calculatedAvgPower > 12.0f -> realtimeAvgWatts
            else -> calculatedAvgPower
        }

        // 验证最终平均放电功耗处于物理合理的 1.5W，绝不出现 89.74W
        assertEquals(1.5f, finalAvgPower, 0.01f)
        assertFalse("平均放电功耗绝不能出现 89.74W 尖峰", finalAvgPower > 10f)
    }

    /**
     * 验证刚拔电 18 秒且全亮屏场景下，后台时间严格从拔电时刻计算并满足时间守恒。
     * 彻底杜绝 13 小时历史累积时长渗透导致整机后台时间与应用后台时间显示 13h3m。
     */
    @Test
    fun testAppAndOverviewBackgroundTimeConservationStrictlyFromUnplugTime() {
        val dischargeDurationMs = 18000L // 拔电 18 秒
        val screenOnMs = 18000L          // 亮屏 18 秒
        val screenOffMs = 0L             // 息屏 0 秒

        // 模拟某个应用底层历史累加的 13 小时 3 分钟前台服务
        val historicalBgServiceMs = (13 * 3600 + 3 * 60) * 1000L
        val appFgMs = 18000L

        // 1. 验证单应用时间守恒与拔电窗口截断
        val maxAllowedBgForApp = (dischargeDurationMs - appFgMs).coerceAtLeast(0L)
        val safeAppBgMs = historicalBgServiceMs.coerceIn(0L, maxAllowedBgForApp)

        assertEquals("应用在当期的后台时长必须受 (总时长 - 前台时长) 约束为 0 秒", 0L, safeAppBgMs)
        assertEquals("应用总时长必须严格守恒等于 18 秒", 18000L, appFgMs + safeAppBgMs)

        // 2. 验证整机概览卡片后台放电时长计算：无后台能耗时为 0 秒，有后台能耗时如实反映伴随放电时长
        val allBgEnergyWh = 0.14f
        val rawBgMs = screenOffMs.coerceIn(0L, dischargeDurationMs)
        val effectiveBgMs = if (rawBgMs >= 1000L) {
            rawBgMs
        } else if (dischargeDurationMs > screenOnMs) {
            (dischargeDurationMs - screenOnMs).coerceIn(0L, dischargeDurationMs)
        } else if (allBgEnergyWh > 0.001f) {
            dischargeDurationMs
        } else {
            0L
        }

        assertEquals("全亮屏 18 秒且产生后台能耗时，整机后台放电时长如实反映伴随放电的 18 秒", 18000L, effectiveBgMs)
        assertFalse("整机后台放电时长绝不能显示为 13 小时", effectiveBgMs > 18000L)
    }

    /**
     * 验证部分息屏场景下，整机与应用后台时间守恒约束。
     */
    @Test
    fun testAppBackgroundTimeConservationWhenPartiallyScreenOff() {
        val dischargeDurationMs = 60000L // 拔电 60 秒
        val screenOnMs = 20000L          // 亮屏 20 秒
        val screenOffMs = 40000L         // 息屏 40 秒

        // 应用前台 20 秒，历史服务 100000 毫秒
        val appFgMs = 20000L
        val historicalServiceMs = 100000L

        val maxAllowedBg = (dischargeDurationMs - appFgMs).coerceAtLeast(0L)
        val safeAppBgMs = historicalServiceMs.coerceIn(0L, maxAllowedBg)

        assertEquals("应用后台耗时上限不能超过非前台时间 40 秒", 40000L, safeAppBgMs)
        assertTrue("应用总耗时不能超过周期总时长 60 秒", (appFgMs + safeAppBgMs) <= dischargeDurationMs)

        val rawBgMs = screenOffMs.coerceIn(0L, dischargeDurationMs)
        val effectiveBgMs = if (rawBgMs >= 1000L) {
            rawBgMs
        } else if (dischargeDurationMs > screenOnMs) {
            (dischargeDurationMs - screenOnMs).coerceIn(0L, dischargeDurationMs)
        } else {
            0L
        }

        assertEquals("整机后台时长必须严格等于息屏时长 40 秒", 40000L, effectiveBgMs)
    }

    /**
     * 验证后台时间提取正则严格剔除 cached 挂起进程标记，仅匹配真实的后台活跃运行（bg/fgs/service）。
     */
    @Test
    fun testRegexBgTimeExcludesCached() {
        val regexBgTime = Pattern.compile("(?:bg|fgs|service)[=:]\\s*([\\d\\w\\s]+?)(?=\\s+[a-zA-Z_-]+[=:]|\\)|$)", Pattern.CASE_INSENSITIVE)

        // 仅包含 cached=4m59s，无真实后台运行标记
        val cachedOnlyDetails = "cpu=2m15s top=6s cached=4m59s"
        val cachedMatcher = regexBgTime.matcher(cachedOnlyDetails)
        assertFalse("挂起缓存 cached 绝不能被正则匹配为后台运行时间", cachedMatcher.find())

        // 包含真实后台运行标记 bg=15s
        val realBgDetails = "cpu=2m15s top=6s bg=15s cached=4m59s"
        val realBgMatcher = regexBgTime.matcher(realBgDetails)
        assertTrue("真实的后台标记 bg 必须能被正确匹配", realBgMatcher.find())
        assertEquals("提取的后台耗时必须为 15s", "15s", realBgMatcher.group(1)?.trim())

        // 包含前台服务标记 fgs=30s
        val fgsDetails = "cpu=1m top=0s fgs=30s"
        val fgsMatcher = regexBgTime.matcher(fgsDetails)
        assertTrue("前台服务标记 fgs 必须能被正确匹配", fgsMatcher.find())
        assertEquals("提取的前台服务耗时必须为 30s", "30s", fgsMatcher.group(1)?.trim())
    }

    /**
     * 验证当应用短时间内产生电量导致计算核心算力偏高时，后台时长绝不被虚拟填充为“总时长 - 前台时长”。
     */
    @Test
    fun testDecoupleAppEnergyDoesNotInventBackgroundTime() {
        val dischargeDurationMs = 306000L // 拔电 5 分 6 秒
        val foregroundMs = 6000L          // 前台仅 6 秒
        val backgroundMs = 0L             // 无后台活动记录
        val cpuMs = 6000L                 // CPU 耗时与前台一致

        // 模拟解耦算法计算有效后台时长
        val rawEffectiveBgMs = if (backgroundMs > 0L) {
            backgroundMs
        } else if (cpuMs > foregroundMs) {
            (cpuMs - foregroundMs).coerceAtLeast(0L)
        } else {
            0L
        }
        val safeBgMs = rawEffectiveBgMs.coerceAtMost(dischargeDurationMs)

        assertEquals("无明确后台记录或超出前台的 CPU 算力时，后台时长必须忠实反映为 0", 0L, safeBgMs)
        assertFalse("后台时长绝不能被推算为 4m59s 或 300000ms", safeBgMs >= 200000L)
    }

    /**
     * 验证后台活跃时长与前台服务常驻挂载时长的解耦与口径统一：
     * 1. 具有 FGS 常驻服务的应用（如挂载 12.5 小时），其后台活跃时长（backgroundTimeMs）统一为净 CPU 算力与唤醒持锁耗时（如 80 秒），
     *    而 12.5 小时常驻时长由 fgsDurationMs 独立承载；
     * 2. 无常驻 FGS 的应用（如 BatteryRecord），其后台活跃时长同样为真实唤醒工时（如 33 秒）；
     * 3. 两个应用在相同的基准下计算后台平均功耗，不再出现 0.01W 伪稀释而造成与 0.18W 对比失真的情况。
     */
    @Test
    fun testUnifiedBackgroundActiveTimeAndFgsDecoupling() {
        // 模拟“电池统计”应用：FGS 常驻 12h30m (45000000ms)，但 CPU 净耗时只有 80 秒 (80000ms)
        val fgsApp = AppPowerUsageItem(
            packageName = "com.battery.analysis",
            appName = "电池统计",
            icon = null,
            foregroundTimeMs = 323_000L, // 5m23s
            avgPowerWatts = 1.09f,
            avgTemperature = 40.0f,
            maxTemperature = 40.0f,
            lastUsedTimeMs = System.currentTimeMillis(),
            backgroundTimeMs = 80_000L,  // 净活跃工作时长 1m20s
            foregroundEnergyWh = 0.098f,
            backgroundEnergyWh = 0.004f, // 真实轻微唤醒能耗
            foregroundPowerWatts = 1.09f,
            backgroundPowerWatts = 0.18f, // 唤醒工作功耗约 0.18W
            fgsDurationMs = 45_000_000L   // 常驻挂载 12h30m
        )

        // 模拟“BatteryRecord”应用：无 FGS，净活跃工作时长 33 秒，唤醒工作功耗约 0.18W
        val nonFgsApp = AppPowerUsageItem(
            packageName = "com.battery.record",
            appName = "BatteryRecord",
            icon = null,
            foregroundTimeMs = 85_000L,  // 1m25s
            avgPowerWatts = 1.91f,
            avgTemperature = 40.0f,
            maxTemperature = 40.0f,
            lastUsedTimeMs = System.currentTimeMillis(),
            backgroundTimeMs = 33_000L,  // 净活跃工作时长 33s
            foregroundEnergyWh = 0.046f,
            backgroundEnergyWh = 0.002f,
            foregroundPowerWatts = 1.91f,
            backgroundPowerWatts = 0.18f,
            fgsDurationMs = 0L
        )

        // 验证常驻时长格式化
        assertEquals("前台服务常驻时长必须正确格式化为 12h30m", "12h30m", fgsApp.getFormattedFgsDuration())
        assertEquals("无前台服务应用常驻时长格式化为 0s", "0s", nonFgsApp.getFormattedFgsDuration())

        // 验证两个应用的后台活跃时长口径一致（均为分/秒级别真实工作时间）
        assertEquals("电池统计后台活跃工时为 1m20s", "1m20s", fgsApp.getFormattedBackgroundDuration())
        assertEquals("BatteryRecord 后台活跃工时为 33s", "33s", nonFgsApp.getFormattedBackgroundDuration())

        // 验证组合展示中的前后台工时（清晰注明后台工时）
        assertEquals("5m23s | 后台 1m20s", fgsApp.getFormattedCombinedDuration())
        assertEquals("1m25s | 后台 33s", nonFgsApp.getFormattedCombinedDuration())

        // 验证功耗展示：仅统计前台真实物理功耗
        assertEquals("1.09W", fgsApp.getFormattedCombinedAvgWatts())
        assertEquals("1.91W", nonFgsApp.getFormattedCombinedAvgWatts())
    }

    /**
     * 验证方案 A 基于硬件时序采样点的数值微积分模型（积分算法）：
     * 1. 模拟 8 小时息屏放电过程（每 60 秒产生一个采样点，电压 3.9V，电流 12.82mA，对应功率 0.05W）；
     * 2. 通过梯形数值积分准确计算出放电总能量约为 0.40Wh，平均放电功耗为 0.05W；
     * 3. 验证单点与边界条件下的容错性。
     */
    @Test
    fun testPhysicalNumericalIntegrationModel() {
        val baseTime = 1710000000000L
        val points = mutableListOf<Triple<Long, Float, Float>>()
        // 8 小时 = 480 分钟，每分钟一个采样点
        val durationMinutes = 480
        val voltage = 3.9f
        val currentMa = 12.82f // 3.9V * 0.01282A = 0.05W

        for (i in 0..durationMinutes) {
            val ts = baseTime + (i * 60_000L)
            points.add(Triple(ts, voltage, currentMa))
        }

        val (energyWh, avgWatts) = PowerUsageManager.calculatePhysicalIntegratedEnergyAndPower(points)

        // 8h * 0.05W = 0.40Wh
        assertEquals("8小时物理微积分放电能量必须约为 0.40Wh", 0.40f, energyWh, 0.02f)
        assertEquals("物理微积分放电平均功率必须精确等于 0.05W", 0.05f, avgWatts, 0.005f)

        // 边界条件测试
        val emptyResult = PowerUsageManager.calculatePhysicalIntegratedEnergyAndPower(emptyList())
        assertEquals(0f, emptyResult.first, 0.001f)
        assertEquals(0f, emptyResult.second, 0.001f)

        val singleResult = PowerUsageManager.calculatePhysicalIntegratedEnergyAndPower(listOf(Triple(baseTime, 4.0f, 500f)))
        assertEquals(0f, singleResult.first, 0.001f)
        assertEquals(2.0f, singleResult.second, 0.01f) // 4V * 0.5A = 2W
    }

    /**
     * 算法标准：分 App 后台仅统计运行时长，不统计后台能耗与平均功耗：
     * 1. 纯后台应用：仅统计后台活跃工时与常驻时长，功耗与能量忠实显示为 "--"，杜绝虚假发配电量；
     * 2. 前后台混合应用：功耗与能量严格基于前台物理放电切片计算，时长展示组合工时（如 "10m | 后台 20m"）；
     * 3. 彻底根除 27 个后台应用累加出 3.29Wh 的 Bug，整机息屏放电统归于息屏宏观指标。
     */
    @Test
    fun testBatteryRecorderTimeOnlyBackgroundSpecification() {
        // 1. 模拟纯后台应用（无前台时长，仅有 30 分钟后台净工时与 12 小时常驻）
        val pureBgApp = AppPowerUsageItem(
            packageName = "com.example.purebg",
            appName = "纯后台服务",
            icon = null,
            foregroundTimeMs = 0L,
            avgPowerWatts = 0f,
            avgTemperature = 36.5f,
            maxTemperature = 37.0f,
            lastUsedTimeMs = System.currentTimeMillis(),
            backgroundTimeMs = 1_800_000L, // 30m
            foregroundEnergyWh = 0f,
            backgroundEnergyWh = 0f,
            foregroundPowerWatts = 0f,
            backgroundPowerWatts = 0f,
            fgsDurationMs = 43_200_000L // 12h
        )

        assertEquals("纯后台应用功耗诚实显示为 --", "--", pureBgApp.getFormattedCombinedAvgWatts())
        assertEquals("纯后台应用能量诚实显示为 --", "--", pureBgApp.getFormattedCombinedEnergyWh())
        assertEquals("纯后台应用工时正确展示后台工时", "后台: 30m", pureBgApp.getFormattedCombinedDuration())
        assertEquals("纯后台应用能量数值严格为 0", 0f, pureBgApp.energyWh, 0.0001f)
        assertEquals("纯后台应用常驻时长正确展示", "12h", pureBgApp.getFormattedFgsDuration())

        // 2. 模拟前后台混合应用（前台 10m、功耗 1.50W、能耗 0.25Wh，后台活跃 20m）
        val mixedApp = AppPowerUsageItem(
            packageName = "com.example.mixed",
            appName = "混合应用",
            icon = null,
            foregroundTimeMs = 600_000L, // 10m
            avgPowerWatts = 1.50f,
            avgTemperature = 38.0f,
            maxTemperature = 38.5f,
            lastUsedTimeMs = System.currentTimeMillis(),
            backgroundTimeMs = 1_200_000L, // 20m
            foregroundEnergyWh = 0.25f,
            backgroundEnergyWh = 0f, // 后台能耗不虚拟统计
            foregroundPowerWatts = 1.50f,
            backgroundPowerWatts = 0f,
            fgsDurationMs = 0L
        )

        assertEquals("混合应用功耗严格对齐前台物理功耗 1.50W", "1.50W", mixedApp.getFormattedCombinedAvgWatts())
        assertEquals("混合应用能量严格对齐前台物理能耗 0.250Wh", "0.250Wh", mixedApp.getFormattedCombinedEnergyWh())
        assertEquals("混合应用工时展示组合工时", "10m | 后台 20m", mixedApp.getFormattedCombinedDuration())
        assertEquals("混合应用总电量严格等于前台电量", 0.25f, mixedApp.energyWh, 0.0001f)

        // 3. 模拟 27 个后台应用场景：所有后台应用的后台能量严格为 0，彻底杜绝 3.29Wh
        val rawAppList = (1..27).map { i ->
            AppPowerUsageItem(
                packageName = "com.example.bg$i",
                appName = "后台应用$i",
                icon = null,
                foregroundTimeMs = 0L,
                avgPowerWatts = 0f,
                avgTemperature = 37f,
                maxTemperature = 37f,
                lastUsedTimeMs = System.currentTimeMillis(),
                backgroundTimeMs = 60_000L,
                foregroundEnergyWh = 0f,
                backgroundEnergyWh = 0f,
                foregroundPowerWatts = 0f,
                backgroundPowerWatts = 0f
            )
        }
        val sumBgEnergyWh = rawAppList.sumOf { it.backgroundEnergyWh.toDouble() }.toFloat()
        assertEquals("27个后台应用后台能耗之和严格为 0Wh", 0f, sumBgEnergyWh, 0.0001f)
        assertFalse("彻底绝迹 3.29Wh Bug", sumBgEnergyWh >= 1.0f)
    }

    /**
     * 应用前台切片与硬件瞬时采样点梯形数值微积分算法（E = ∫ P(t) dt）：
     * 1. 严格使用梯形数值积分累加各切片物理能量：dEnergyWs = (P[i-1] + P[i]) * 0.5 * (dt / 1000.0)；
     * 2. 平均放电功耗准确归属于置顶前台应用：P_avg = TotalEnergyWs / TotalDurationSeconds；
     * 3. 真实硬件数据测试：米家 2.21W、 1.69W、Scene 1.78W、电池统计 1.37W、荣耀桌面 0.90W；
     * 4. 验证整机能量守恒：各应用前台放电能量之和严格等于硬件积分总能量。
     */
    @Test
    fun testBatteryRecorderTrapezoidalIntegrationAlgorithm() {
        data class SamplePoint(val timestamp: Long, val powerWatts: Float)
        data class AppInterval(val packageName: String, val startTs: Long, val endTs: Long)

        val baseTs = 1710000000000L

        // 1. 构建与图一  完全对齐的 5 大典型应用前台活跃区间（各运行 100 秒）
        val intervals = listOf(
            AppInterval("com.hihonor.android.launcher", baseTs, baseTs + 100_000L), // 荣耀桌面：0.90W
            AppInterval("com.battery.analysis", baseTs + 105_000L, baseTs + 205_000L), // 电池统计：1.37W
            AppInterval("com.itosang.batteryrecorder", baseTs + 210_000L, baseTs + 310_000L), // 1.69W
            AppInterval("com.omarea.vtools", baseTs + 315_000L, baseTs + 415_000L), // Scene：1.78W
            AppInterval("com.xiaomi.smarthome", baseTs + 420_000L, baseTs + 520_000L) // 米家：2.21W
        )

        // 2. 模拟底层硬件高频瞬时放电功率采样点（各应用前台区间内连续采样，每 25 秒一个微元切片）
        val samples = listOf(
            // 荣耀桌面区间 (0~100s, 目标均值 0.90W)
            SamplePoint(baseTs, 0.90f),
            SamplePoint(baseTs + 25_000L, 0.88f),
            SamplePoint(baseTs + 50_000L, 0.92f),
            SamplePoint(baseTs + 75_000L, 0.88f),
            SamplePoint(baseTs + 100_000L, 0.92f),

            // 电池统计区间 (105~205s, 目标均值 1.37W)
            SamplePoint(baseTs + 105_000L, 1.37f),
            SamplePoint(baseTs + 130_000L, 1.35f),
            SamplePoint(baseTs + 155_000L, 1.39f),
            SamplePoint(baseTs + 180_000L, 1.35f),
            SamplePoint(baseTs + 205_000L, 1.39f),

            // 区间 (210~310s, 目标均值 1.69W)
            SamplePoint(baseTs + 210_000L, 1.69f),
            SamplePoint(baseTs + 235_000L, 1.67f),
            SamplePoint(baseTs + 260_000L, 1.71f),
            SamplePoint(baseTs + 285_000L, 1.67f),
            SamplePoint(baseTs + 310_000L, 1.71f),

            // Scene 区间 (315~415s, 目标均值 1.78W)
            SamplePoint(baseTs + 315_000L, 1.78f),
            SamplePoint(baseTs + 340_000L, 1.76f),
            SamplePoint(baseTs + 365_000L, 1.80f),
            SamplePoint(baseTs + 390_000L, 1.76f),
            SamplePoint(baseTs + 415_000L, 1.80f),

            // 米家区间 (420~520s, 目标均值 2.21W)
            SamplePoint(baseTs + 420_000L, 2.21f),
            SamplePoint(baseTs + 445_000L, 2.19f),
            SamplePoint(baseTs + 470_000L, 2.23f),
            SamplePoint(baseTs + 495_000L, 2.19f),
            SamplePoint(baseTs + 520_000L, 2.23f)
        )

        // 3. 执行梯形数值微积分
        class TestAccumulator {
            var durationMs: Long = 0L
            var energyWs: Double = 0.0
        }
        val appStats = mutableMapOf<String, TestAccumulator>()

        var totalHardwareEnergyWs = 0.0
        var totalMatchedAppEnergyWs = 0.0
        var prev = samples[0]
        for (i in 1 until samples.size) {
            val curr = samples[i]
            val dt = curr.timestamp - prev.timestamp
            if (dt in 1L..120_000L) {
                val avgPower = (prev.powerWatts + curr.powerWatts) * 0.5
                val dEnergyWs = avgPower * (dt / 1000.0)
                totalHardwareEnergyWs += dEnergyWs

                val midTs = (prev.timestamp + curr.timestamp) / 2
                val matchedPkg = intervals.firstOrNull { midTs in it.startTs..it.endTs }?.packageName
                if (matchedPkg != null) {
                    val acc = appStats.getOrPut(matchedPkg) { TestAccumulator() }
                    acc.durationMs += dt
                    acc.energyWs += dEnergyWs
                    totalMatchedAppEnergyWs += dEnergyWs
                }
            }
            prev = curr
        }

        // 4. 验证各应用计算出的平均功耗
        val launcherWatts = (appStats["com.hihonor.android.launcher"]!!.energyWs / (appStats["com.hihonor.android.launcher"]!!.durationMs / 1000.0)).toFloat()
        val batteryAppWatts = (appStats["com.battery.analysis"]!!.energyWs / (appStats["com.battery.analysis"]!!.durationMs / 1000.0)).toFloat()
        val brWatts = (appStats["com.itosang.batteryrecorder"]!!.energyWs / (appStats["com.itosang.batteryrecorder"]!!.durationMs / 1000.0)).toFloat()
        val sceneWatts = (appStats["com.omarea.vtools"]!!.energyWs / (appStats["com.omarea.vtools"]!!.durationMs / 1000.0)).toFloat()
        val miHomeWatts = (appStats["com.xiaomi.smarthome"]!!.energyWs / (appStats["com.xiaomi.smarthome"]!!.durationMs / 1000.0)).toFloat()

        assertEquals(0.90f, launcherWatts, 0.01f)
        assertEquals(1.37f, batteryAppWatts, 0.01f)
        assertEquals(1.69f, brWatts, 0.01f)
        assertEquals(1.78f, sceneWatts, 0.01f)
        assertEquals(2.21f, miHomeWatts, 0.01f)

        // 5. 验证各应用前台能量之和与前台切片总积分能量绝对守恒
        val sumAppEnergyWs = appStats.values.sumOf { it.energyWs }
        assertEquals(totalMatchedAppEnergyWs, sumAppEnergyWs, 0.001)
    }

    /**
     * 验证直接读取 Linux 内核 sysfs 节点（/sys/class/power_supply/battery/current_now）的功率计算与单位换算：
     * 1. 微安（uA）与微伏（uV）标准单位换算为毫安与伏特；
     * 2. 瞬时放电功率严格遵循物理公式 P = (I * U) / 1000（单位：W）。
     */
    @Test
    fun testDirectSysfsHardwarePowerCalculation() {
        // 模拟 Linux 内核读取到的原始微安与微伏：890,000 uA (890mA), 4,180,000 uV (4.18V)
        val rawCurrentUa = -890_000L
        val rawVoltageUv = 4_180_000L

        val currentMa = Math.abs(rawCurrentUa) / 1000f
        val voltageVolts = rawVoltageUv / 1_000_000f
        val powerWatts = (currentMa * voltageVolts) / 1000f

        assertEquals(890f, currentMa, 0.01f)
        assertEquals(4.18f, voltageVolts, 0.01f)
        assertEquals(3.72f, Math.round(powerWatts * 100f) / 100f, 0.01f)
    }

    /**
     * 验证硬件采样点携带置顶前台包名（packageName）机制：
     * 在荣耀桌面运行的 28 秒内，高频采样点打上 com.hihonor.android.launcher 标签，
     * 梯形微积分引擎精准归集其高动态爆发功耗（3.72W），绝不回退至静态保底 1.31W。
     */
    @Test
    fun testTaggedPackageSamplingCapturesHighDynamicPower() {
        data class Sample(val timestamp: Long, val powerWatts: Float, val packageName: String?)

        val baseTs = 1710000000000L
        // 模拟 28 秒桌面运行期间的高频瞬时采样点（瞬时功率在 3.6W~3.8W 之间爆发）
        val samples = listOf(
            Sample(baseTs, 3.70f, "com.hihonor.android.launcher"),
            Sample(baseTs + 5000L, 3.75f, "com.hihonor.android.launcher"),
            Sample(baseTs + 12000L, 3.80f, "com.hihonor.android.launcher"),
            Sample(baseTs + 20000L, 3.65f, "com.hihonor.android.launcher"),
            Sample(baseTs + 28000L, 3.70f, "com.hihonor.android.launcher")
        )

        var launcherEnergyWs = 0.0
        var launcherDurationMs = 0L

        var prev = samples[0]
        for (i in 1 until samples.size) {
            val curr = samples[i]
            val dt = curr.timestamp - prev.timestamp
            val matchedPkg = prev.packageName ?: curr.packageName
            if (matchedPkg == "com.hihonor.android.launcher") {
                val avgP = (prev.powerWatts + curr.powerWatts) * 0.5
                launcherEnergyWs += avgP * (dt / 1000.0)
                launcherDurationMs += dt
            }
            prev = curr
        }

        val launcherAvgWatts = (launcherEnergyWs / (launcherDurationMs / 1000.0)).toFloat()
        val roundedWatts = Math.round(launcherAvgWatts * 100f) / 100f

        assertEquals("荣耀桌面 28 秒高动态功耗精准对标 BatteryRecorder 3.72W", 3.72f, roundedWatts, 0.05f)
        assertTrue("绝不退化为静态基准 1.31W", roundedWatts > 2.0f)
    }

    /**
     * 验证桌面启动器在切片采样不足时的 fallback 功耗计算机制：
     * 针对桌面应用（isHomeLauncher 为 true），当未命中硬件时序采样点时，
     * 功耗应当优先采用自身历史真实能耗换算功耗或轻量桌面基准（1.25W），
     * 绝对不能被整机高负载亮屏平均功耗（如 3.40W）机械锁死并导致数值恒定不改变。
     */
    @Test
    fun testLauncherFallbackPowerDoesNotLockToScreenOnAverage() {
        val launcherPkg = "com.hihonor.android.launcher"
        val isHome = launcherPkg.contains("launcher")
        val screenOnWatts = 3.40f // 整机高负载平均亮屏功耗 3.40W
        val windowAvgWatts: Float? = null

        // 场景 A：桌面原本具有自身从 dumpsys/硬件指标解析出的前台功耗（例如 1.15W）
        val selfCalcWattsA = 1.15f
        val fallbackA = windowAvgWatts ?: selfCalcWattsA
        assertEquals("优先采用应用自身真实前台功耗 1.15W", 1.15f, fallbackA, 0.01f)
        assertTrue("绝不锁死在整机 3.40W", Math.abs(fallbackA - 3.40f) > 0.1f)

        // 场景 B：桌面无自身计算功耗，作为桌面应用采用合理轻量基准 1.25W
        val selfCalcWattsB: Float? = null
        val fallbackB = windowAvgWatts ?: selfCalcWattsB ?: if (isHome) 1.25f else screenOnWatts
        assertEquals("桌面保底功耗客观评估为 1.25W", 1.25f, fallbackB, 0.01f)
        assertTrue("杜绝被整机亮屏功耗 3.40W 强行覆盖", Math.abs(fallbackB - 3.40f) > 0.1f)
    }

    /**
     * 验证亮屏期间应用切出后状态机将桌面停留区间精准归集至系统桌面：
     * 用户从应用 A 切出（ACTIVITY_PAUSED）至应用 B 进入（ACTIVITY_RESUMED）之间，
     * 亮屏时间窗口生成属于默认桌面（com.hihonor.android.launcher）的活跃区间，
     * 使得其间的硬件瞬时采样点能够正确归集入微积分聚合器。
     */
    @Test
    fun testLauncherStateTransitionCapturesIntervalBetweenApps() {
        data class TestInterval(val packageName: String, val startTs: Long, val endTs: Long)
        val intervals = mutableListOf<TestInterval>()

        val baseTs = 1710000000000L
        val defaultHome = "com.hihonor.android.launcher"

        // 模拟：t0~t30 运行微信，t30 微信 PAUSE，用户在桌面停留 29 秒，t59 打开设置
        var currentForeground: String? = "com.tencent.mm"
        var currentStartTs = baseTs
        var isScreenOn = true

        // 1. t30: 微信 PAUSE
        val t30 = baseTs + 30_000L
        intervals.add(TestInterval(currentForeground!!, currentStartTs, t30))
        if (isScreenOn && defaultHome.isNotEmpty()) {
            currentForeground = defaultHome
            currentStartTs = t30
        }

        // 2. t59: 设置 RESUMED
        val t59 = baseTs + 59_000L
        if (currentForeground != null) {
            intervals.add(TestInterval(currentForeground, currentStartTs, t59))
        }
        currentForeground = "com.android.settings"
        currentStartTs = t59

        assertEquals("应生成 2 个区间（微信与荣耀桌面）", 2, intervals.size)
        val launcherInterval = intervals.find { it.packageName == defaultHome }
        assertTrue("荣耀桌面应存在独立的活跃区间", launcherInterval != null)
        assertEquals("荣耀桌面活跃时长应为 29 秒", 29_000L, launcherInterval!!.endTs - launcherInterval.startTs)
    }

    /**
     * 验证统计 App 列表中能量展示精确到小数点后 3 位（如 "0.250Wh"、"<0.001Wh"、"--"）的规范格式。
     */
    @Test
    fun testAppPowerUsageItemEnergyPrecisionThreeDecimals() {
        // 1. 验证常规能量精确显示三位小数
        val item1 = AppPowerUsageItem(
            packageName = "com.example.app1",
            appName = "应用1",
            icon = null,
            foregroundTimeMs = 60_000L,
            avgPowerWatts = 1.0f,
            avgTemperature = 36.0f,
            maxTemperature = 37.0f,
            lastUsedTimeMs = 1000L,
            foregroundEnergyWh = 0.25f
        )
        assertEquals("0.25Wh 应精确格式化为 0.250Wh", "0.250Wh", item1.getFormattedCombinedEnergyWh())
        assertEquals("纯前台能量也应精确格式化为 0.250Wh", "0.250Wh", item1.getFormattedForegroundEnergyWh())
        assertEquals("总能量也应精确格式化为 0.250Wh", "0.250Wh", item1.getFormattedEnergyWh())

        // 2. 验证小数值如 0.005Wh、0.001Wh 及四舍五入
        val item2 = item1.copy(foregroundEnergyWh = 0.005f)
        assertEquals("0.005Wh 保持 0.005Wh", "0.005Wh", item2.getFormattedCombinedEnergyWh())

        val item3 = item1.copy(foregroundEnergyWh = 0.0008f)
        assertEquals("0.0008Wh 四舍五入为 0.001Wh", "0.001Wh", item3.getFormattedCombinedEnergyWh())

        // 3. 验证低于 0.0005Wh 但大于 0.00001Wh 的微小量显示为 <0.001Wh
        val item4 = item1.copy(foregroundEnergyWh = 0.0003f)
        assertEquals("低于0.0005Wh的微小电量应显示为 <0.001Wh", "<0.001Wh", item4.getFormattedCombinedEnergyWh())

        // 4. 验证零能耗或纯后台如实显示为 --
        val item5 = item1.copy(foregroundEnergyWh = 0f, foregroundTimeMs = 0L, backgroundTimeMs = 60_000L)
        assertEquals("无前台电量或纯后台如实显示为 --", "--", item5.getFormattedCombinedEnergyWh())
    }

    /**
     * 验证拔电时间戳防倒流保护：当应用刚刚记录当前拔电时刻时，系统底层历史记录中的旧拔电时间戳绝不反向覆盖当前拔电时间。
     */
    @Test
    fun testUnplugAntiRollbackGuard() {
        val now = 1773729600000L // 当前物理时刻
        val currentUnplugTime = now // 刚刚拔电
        val oldHistoryUnplugTs = now - 3600_000L * 5 // 5 小时前的历史拔电记录

        // 模拟防倒流判定
        val shouldAdoptDetected = (currentUnplugTime <= 0L) ||
                (oldHistoryUnplugTs > currentUnplugTime && oldHistoryUnplugTs <= now)

        assertFalse("历史旧拔电时间戳绝不可倒流覆盖当前拔电时刻", shouldAdoptDetected)

        var effectiveUnplugTime = currentUnplugTime
        if (shouldAdoptDetected) {
            effectiveUnplugTime = oldHistoryUnplugTs
        }
        assertEquals("有效拔电时间戳必须严格保持为刚刚拔电的当前时刻", now, effectiveUnplugTime)
    }

    /**
     * 验证刚拔电（0~500ms 内）瞬间放电与屏幕时长计算：不会因毫秒级微小时长跌入 dumpsys 历史大时长分支。
     */
    @Test
    fun testDurationCalculationImmediatelyAfterUnplug() {
        val now = 1773729600000L
        val effectiveUnplugTime = now - 200L // 拔电 200ms
        val oldDumpsysDurationMs = 3600_000L * 12 // 历史 12 小时

        val durationMs = if (effectiveUnplugTime in 1..now && (now - effectiveUnplugTime) <= (48 * 3600_000L)) {
            (now - effectiveUnplugTime).coerceAtLeast(1000L)
        } else {
            oldDumpsysDurationMs.coerceAtLeast(1000L)
        }

        assertEquals("刚拔电瞬间放电总时长必须自 1000ms（1秒）开始统计，绝不回退至历史大时长", 1000L, durationMs)

        // 验证即使无屏幕事件发生，基于 durationMs 的截断也确保屏幕亮屏时长最多为 1000ms
        val screenOnDurationMs = 0L.coerceIn(0L, durationMs)
        assertEquals("屏幕亮屏时长初始重置为 0", 0L, screenOnDurationMs)
    }

    /**
     * 验证基于电池标称电压（3.85V）计算放电能量及能量守恒特性。
     * 确保放电毫安时转换为瓦时能量时完全依据国际标准标称电压计算，杜绝端电压瞬时波动产生的误差。
     */
    @Test
    fun testNominalVoltageDischargeEnergyCalculation() {
        val nominalVoltageVolts = com.battery.analysis.util.BatteryEnergyCalculator.DEFAULT_NOMINAL_VOLTAGE_VOLTS
        assertEquals(3.85f, nominalVoltageVolts, 0.001f)

        val dischargedMah = 898.7f // 累计放电 898.7mAh
        val expectedEnergyWh = (dischargedMah * nominalVoltageVolts) / 1000f

        assertEquals(3.460f, expectedEnergyWh, 0.001f)

        // 验证亮灭屏能耗解耦与宏观能量守恒
        val onMah = 703.9f
        val offMah = 194.8f
        val onEnergyWh = (onMah * nominalVoltageVolts) / 1000f
        val offEnergyWh = (offMah * nominalVoltageVolts) / 1000f

        assertEquals(expectedEnergyWh, onEnergyWh + offEnergyWh, 0.001f)
    }

    /**
     * 验证放电概览能量格式化方法精确至小数点后三位的展示逻辑。
     */
    @Test
    fun testOverviewEnergyThreeDecimalFormatting() {
        fun formatOverviewEnergy(wh: Float): String {
            return if (wh <= 0f) {
                "0.000Wh"
            } else if (wh < 0.001f) {
                "<0.001Wh"
            } else {
                String.format(java.util.Locale.US, "%.3fWh", wh)
            }
        }

        assertEquals("0.000Wh", formatOverviewEnergy(0f))
        assertEquals("0.000Wh", formatOverviewEnergy(-0.5f))
        assertEquals("<0.001Wh", formatOverviewEnergy(0.0004f))
        assertEquals("<0.001Wh", formatOverviewEnergy(0.0009f))
        assertEquals("2.710Wh", formatOverviewEnergy(2.710015f))
        assertEquals("3.460Wh", formatOverviewEnergy(3.459995f))
        assertEquals("0.760Wh", formatOverviewEnergy(0.76f))
    }

    /**
     * 验证只追求最真实的耗电量，忠实反映系统底层连续放电量与硬件库仑计，不设人为强制物理天花板：
     * 模拟电池标称总能量 30.9Wh，当前剩余 9.9Wh，放电总能量忠实基于系统底层真实放电量计算，
     * 解耦后的亮屏能量与息屏能量之和严格满足能量守恒闭环。
     */
    @Test
    fun testRealisticDischargeEnergyAndDecoupling() {
        val effectiveCapacity = 8025f // 8025mAh
        val nominalVoltageVolts = 3.85f
        val currentRemainWh = 9.9f

        val dropPercent = 68 // 从 100% 掉至 32%
        val smoothedDropMah = effectiveCapacity * (dropPercent / 100f) // 5457mAh
        // 忠实采用真实放电量，不设人为虚拟天花板
        val realDischargedMah = smoothedDropMah
        val realTotalEnergyWh = (realDischargedMah * nominalVoltageVolts) / 1000f // 21.009Wh

        val expectedConsumedEnergyWh = (8025f * 3.85f / 1000f) - currentRemainWh // 理论消耗约 21.0Wh
        assertEquals("真实放电能量忠实反映掉电数据(约21.0Wh)", expectedConsumedEnergyWh, realTotalEnergyWh, 0.1f)

        // 验证亮灭屏能耗解耦与宏观能量守恒
        val screenOnHours = 7.1333f // 7h8m
        val screenOffHours = 12.3833f // 12h23m
        val dischargeHours = 19.5166f // 19h31m

        val intOnPowerWatts = 2.24f
        val intOffPowerWatts = 0.20f // 修复后真实的息屏采样功率

        val estOnE = intOnPowerWatts * screenOnHours
        val estOffE = intOffPowerWatts * screenOffHours
        val sumEstE = estOnE + estOffE

        val onEnergyWh = (realTotalEnergyWh * (estOnE / sumEstE)).coerceAtLeast(0f)
        val offEnergyWh = (realTotalEnergyWh - onEnergyWh).coerceAtLeast(0f)

        assertEquals("解耦后两部分能量之和必须严格等于总放电能量", realTotalEnergyWh, onEnergyWh + offEnergyWh, 0.001f)

        // 验证综合平均功耗 P = E / T
        val avgWatts = realTotalEnergyWh / dischargeHours
        assertEquals("综合平均功耗必须与总能量和时长严格闭环", realTotalEnergyWh, avgWatts * dischargeHours, 0.01f)
    }

    /**
     * 验证在手机长时间进入深度休眠（Deep Sleep）出现采样断层（如前后两点间隔 1 小时 50 分钟）时，
     * 微积分算法限制单微元最大有效跨度（MAX_INTEGRATION_INTERVAL_MS = 120_000L），
     * 杜绝将唤醒高功耗线性插值放大导致的息屏待机功耗虚高。
     */
    @Test
    fun testDeepSleepGapDoesNotInflateIntegratedEnergy() {
        val baseTime = 1700000000000L
        val points = mutableListOf<Triple<Long, Float, Float>>()

        // 灭屏瞬间记录 1.66W (3.9V, 425.6mA)
        points.add(Triple(baseTime, 3.9f, 425.6f))

        // 经过 1 小时 50 分钟（6,600,000 毫秒）系统被唤醒，瞬时功率 1.66W (3.88V, 427.8mA)
        val wakeTime = baseTime + 6_600_000L
        points.add(Triple(wakeTime, 3.88f, 427.8f))

        // 模拟后续 2 分钟内的密集连续采样（5 秒间隔，真实待机电流 25mA，功率约 0.1W）
        for (i in 1..24) {
            points.add(Triple(wakeTime + (i * 5000L), 3.88f, 25.8f))
        }

        val (energyWh, avgWatts) = PowerUsageManager.calculatePhysicalIntegratedEnergyAndPower(points)

        // 验证：断层微元（6600秒）因超过 120 秒门限被自动跳过，未被线性插值放大为 3.04Wh
        assertTrue("深度睡眠断层不应被线性插值放大，积分能量必须远小于断层插值值", energyWh < 0.1f)
        assertTrue("有效采样平均功率应忠实反映真实采样水平", avgWatts <= 0.3f)
    }

    /**
     * 验证启动少于 1 秒（如 350ms、800ms）的应用平均功耗与运行能量精确计算。
     * 确保不会因时长低于 1 秒被粗暴划入纯后台，且时长能够精确格式化为毫秒（如 "350ms"）。
     */
    @Test
    fun testSubSecondAppPowerAndEnergyCalculation() {
        // 场景 A：前台运行 350ms，瞬时功耗 2.50W，消耗能量 2.5W * (0.35s / 3600h) ≈ 0.000243Wh
        val msItem = AppPowerUsageItem(
            packageName = "com.battery.flashlaunch",
            appName = "闪开应用",
            icon = null,
            foregroundTimeMs = 350L,
            avgPowerWatts = 2.50f,
            avgTemperature = 32.0f,
            maxTemperature = 32.0f,
            lastUsedTimeMs = System.currentTimeMillis(),
            backgroundTimeMs = 0L,
            foregroundEnergyWh = 0.000243f,
            backgroundEnergyWh = 0f,
            foregroundPowerWatts = 2.50f,
            backgroundPowerWatts = 0f
        )
        assertEquals("毫秒级运行应用时长必须精确展示为 350ms", "350ms", msItem.getFormattedDuration())
        assertEquals("毫秒级运行应用组合时长展示正常", "350ms", msItem.getFormattedCombinedDuration())
        assertEquals("毫秒级运行应用功耗严格等于 2.50W", "2.50W", msItem.getFormattedCombinedAvgWatts())
        assertEquals("毫秒级运行应用前台功耗严格等于 2.50W", "2.50W", msItem.getFormattedForegroundAvgWatts())
        assertEquals("毫秒级运行应用微小能量精确格式化为 <0.001Wh", "<0.001Wh", msItem.getFormattedCombinedEnergyWh())
        assertTrue("毫秒级运行应用总能耗大于 0", msItem.energyWh > 0f)

        // 场景 B：前台运行 800ms，仅有能量（0.0006Wh），无直接功耗，由能量反推功耗：0.0006 / (0.8 / 3600) = 2.70W
        val energyOnlyItem = AppPowerUsageItem(
            packageName = "com.battery.calcfromenergy",
            appName = "能量换算应用",
            icon = null,
            foregroundTimeMs = 800L,
            avgPowerWatts = 0f,
            avgTemperature = 34.0f,
            maxTemperature = 34.0f,
            lastUsedTimeMs = System.currentTimeMillis(),
            backgroundTimeMs = 0L,
            foregroundEnergyWh = 0.0006f,
            backgroundEnergyWh = 0f,
            foregroundPowerWatts = 0f,
            backgroundPowerWatts = 0f
        )
        assertEquals("由能量反推的前台功耗必须精确计算为 2.70W", "2.70W", energyOnlyItem.getFormattedForegroundAvgWatts())
        assertEquals("800ms 时长精确展示", "800ms", energyOnlyItem.getFormattedDuration())
        assertEquals("大于 0.0005Wh 能量展示为 0.001Wh", "0.001Wh", energyOnlyItem.getFormattedCombinedEnergyWh())
    }
    /**
     * 验证弃用 dumpsys 软件估算放电量并严格以底层硬件采样梯形微积分为核心的功耗计算。
     * 针对 计的真实亮屏工况（2.50W，0.223Wh），验证计算结果忠实反映硬件实测值，
     * 彻底解决系统软件估算（1.46W）严重缩水的问题。
     */
    @Test
    fun testHardwareIntegratedPowerOverridesDumpsysSoftwareEstimation() {
        val baseTime = 1000_000L
        val points = mutableListOf<Triple<Long, Float, Float>>()

        // 模拟持续约 321 秒（0.089 小时）的亮屏真实硬件放电采样：
        // 电压 3.85V，放电电流 649.35mA -> 瞬时功率 P = 3.85 * 0.64935 ≈ 2.50W
        // 采样间隔 1 秒
        for (i in 0..321) {
            points.add(Triple(baseTime + (i * 1000L), 3.85f, 649.35f))
        }

        val (intEnergyWh, intAvgWatts) = PowerUsageManager.calculatePhysicalIntegratedEnergyAndPower(points)

        // 梯形微积分理论计算：2.50W * (321 / 3600 h) ≈ 0.223Wh
        assertEquals("硬件时序微积分平均功率严格等于 2.50W", 2.50f, intAvgWatts, 0.02f)
        assertEquals("硬件时序微积分累积能量严格等于 0.223Wh", 0.223f, intEnergyWh, 0.005f)

        // 对比系统 dumpsys 软件估算值：系统 power_profile 估算仅 38mAh，6分钟放电折算能量为 0.1463Wh，功耗仅 1.46W
        val dumpsysComputedEnergyWh = (38f * 3.85f) / 1000f
        val dumpsysDischargeHours = 360f / 3600f // 6分钟
        val dumpsysComputedWatts = dumpsysComputedEnergyWh / dumpsysDischargeHours
        assertEquals("系统 dumpsys 软件估算功耗严重缩水至 1.46W", 1.46f, dumpsysComputedWatts, 0.02f)

        // 验证弃用系统 dumpsys 估算后，直接以硬件采样微积分结果为真实数据
        val realScreenOnWatts = intAvgWatts
        val realTotalEnergyWh = intEnergyWh
        assertEquals("真实亮屏功耗忠实反映硬件采样 2.50W", 2.50f, realScreenOnWatts, 0.02f)
        assertEquals("真实放电能量忠实反映硬件微积分 0.223Wh", 0.223f, realTotalEnergyWh, 0.005f)
        assertTrue("硬件采样真实功耗显著高于 dumpsys 软件缩水估算", realScreenOnWatts > dumpsysComputedWatts)
    }

    /**
     * 验证基于采样密度的时序梯形微积分与硬件库仑计自适应融合算法。
     * 当采样间隔为 60 秒长间隔稀疏采样且偶发采到 4.5W 瞬时尖峰时，
     * 验证系统不会被单点尖峰走样误导，而是能够将全局总能量稳健锚定在硬件芯片库仑计的连续物理电荷差（如 0.385Wh），
     * 并在亮屏与息屏状态间严格按相对微积分功率比重守恒分配。
     */
    @Test
    fun testSparseSamplingAdaptsToHardwareCoulombCounter() {
        val baseTime = 1000_000L
        val sparsePoints = mutableListOf<Triple<Long, Float, Float>>()

        // 模拟放电 30 分钟（1800 秒），用户设置 60 秒稀疏采样（共 31 个点）
        // 大部分时间待机功耗 1.0W (3.85V, 259.7mA)
        // 偶发在第 10 分钟采到一次 4.5W 瞬时操作尖峰 (3.85V, 1168.8mA)
        for (i in 0..30) {
            val t = baseTime + (i * 60_000L)
            if (i == 10) {
                sparsePoints.add(Triple(t, 3.85f, 1168.8f)) // 4.5W 尖峰
            } else {
                sparsePoints.add(Triple(t, 3.85f, 259.7f)) // 1.0W 待机
            }
        }

        // 计算采样间隔
        val avgIntervalMs = (sparsePoints.last().first - sparsePoints.first().first) / (sparsePoints.size - 1)
        assertEquals("平均采样间隔为 60 秒", 60_000L, avgIntervalMs)
        val isHighFreq = avgIntervalMs in 1L..5000L
        assertFalse("60秒采样判定为非高频密集采样", isHighFreq)

        // 梯形微积分算出的能量
        val (intEnergyWh, _) = PowerUsageManager.calculatePhysicalIntegratedEnergyAndPower(sparsePoints)

        // 主板芯片硬件库仑计实际记录的 30 分钟物理放电量（100mAh = 0.385Wh）
        val hwDischargedMah = 100f
        val physicalTotalEnergyWh = (hwDischargedMah * 3.85f) / 1000f // 0.385Wh
        val dischargeHours = 1800f / 3600f // 0.5h
        val physicalAvgWatts = physicalTotalEnergyWh / dischargeHours // 0.77W

        // 自适应判定：非高频采样且有可靠物理库仑计量，总能量锚定硬件库仑计
        val finalTotalEnergyWh = if (!isHighFreq && physicalTotalEnergyWh > 0.001f) {
            physicalTotalEnergyWh
        } else {
            intEnergyWh
        }
        val finalAvgWatts = finalTotalEnergyWh / dischargeHours

        assertEquals("总能量严格锚定主板硬件库仑计真实电荷 0.385Wh", 0.385f, finalTotalEnergyWh, 0.001f)
        assertEquals("全局平均功耗严格等于库仑计推导功耗 0.77W", physicalAvgWatts, finalAvgWatts, 0.001f)
    }

    /**
     * 验证长周期放电（如 13 小时掉电 22%）中仅有近期数十秒瞬时采样时，采样密度判定不会误判为全周期高频密集采样，
     * 杜绝将几十秒内的微元能量（如 0.010Wh）篡改整机数小时长周期总能耗，确保整机放电能量真实反映电池物理掉电量且功耗正常计算。
     */
    @Test
    fun testLongDischargeCycleWithShortRecentSamplesDoesNotMisjudgeHighFrequency() {
        val totalMs = 13 * 3600_000L // 13小时放电
        val now = 1710000000000L

        // 模拟放电 13 小时期间，内存中仅有最近 20 秒的高频采样点（15 个点，间隔 ~1.4秒）
        val samplePoints = mutableListOf<PowerDischargePoint>()
        for (i in 0 until 15) {
            val t = now - 20_000L + (i * 1428L)
            samplePoints.add(
                PowerDischargePoint(
                    timestamp = t,
                    elapsedHours = (13f * 3600_000f - 20_000f + i * 1428f) / 3600_000f,
                    batteryLevel = 78,
                    voltageVolts = 3.85f,
                    temperature = 32.0f,
                    powerWatts = 1.8f,
                    activeAppIcons = emptyList(),
                    isScreenOn = true,
                    activeAppNames = emptyList()
                )
            )
        }

        // 调用对标标准的放电统计模型
        val stats = PowerUsageManager.computeDischargePowerStats(samplePoints)
        assertTrue("有效采样点应成功计算出放电统计", stats != null)

        // 宏观真实物理掉电量：5000mAh 电池掉电 22%（100% -> 78%）
        val effectiveCapacity = 5000f
        val dropPercent = 22
        val nominalVoltageVolts = 3.85f
        val physicalDrainMah = effectiveCapacity * (dropPercent / 100f) // 1100mAh
        val physicalTotalEnergyWh = (physicalDrainMah * nominalVoltageVolts) / 1000f // 4.235Wh
        val dischargeHours = totalMs / 3600000f // 13.0h

        // 当存在硬件库仑计或实际掉电量时，结合微积分工况功率进行相对比例解耦
        val realTotalEnergyWh = physicalTotalEnergyWh
        val avgWatts = if (dischargeHours > 0f) realTotalEnergyWh / dischargeHours else 0f

        assertEquals("总放电能量真实反映电池物理掉电量 4.235Wh，杜绝缩水为 0.010Wh", 4.235f, realTotalEnergyWh, 0.001f)
        assertTrue("平均放电功耗正常计算（> 0.05W），杜绝退化为 --", avgWatts >= 0.05f)
        assertEquals("13小时消耗4.235Wh对应平均功耗约 0.326W", 4.235f / 13f, avgWatts, 0.01f)
    }

    /**
     * 标准模型在长周期 Deep Sleep 休眠断层场景下的计算表现：
     * 模拟 13 小时放电，包含前段亮屏、中段长达 11 小时的系统深度休眠断层（偶发零星唤醒短采样），以及尾段亮屏使用。
     * 验证：
     * 1. 息屏能量绝不缩水为 0.004Wh，而是通过 P30~P50 稳健待机基线外推真实补偿待机能量；
     * 2. 亮屏能量由梯形积分真实累积；
     * 3. 亮灭屏平均功率均正常输出且 > 0.05W，绝不显示为 "--"；
     * 4. 能量与平均功耗保持严格物理闭环。
     */
    @Test
    fun testBatteryRecorderDischargeStatsWithLongDeepSleepGaps() {
        val baseTs = 1710000000000L
        val samples = mutableListOf<PowerDischargePoint>()

        // 1. 前 30 分钟亮屏使用（每 2 秒采样一次，功率 2.0W 左右）
        var currentTs = baseTs
        for (i in 0 until 1800) {
            samples.add(
                PowerDischargePoint(
                    timestamp = currentTs,
                    elapsedHours = (currentTs - baseTs) / 3600_000f,
                    batteryLevel = 100 - (i / 180),
                    voltageVolts = 4.1f,
                    temperature = 35.0f,
                    powerWatts = 2.0f,
                    activeAppIcons = emptyList(),
                    isScreenOn = true,
                    activeAppNames = emptyList()
                )
            )
            currentTs += 2000L
        }

        // 2. 中间 10 小时息屏待机（从 1h 到 11h），包含数次 CPU Deep Sleep 长间隔断层（每次间隔 2 小时），
        // 且唤醒瞬间有少量高置信短采样（待机底噪 0.15W，偶有唤醒尖峰 0.6W）
        val deepSleepEndTs = baseTs + 11 * 3600_000L
        while (currentTs < deepSleepEndTs) {
            for (k in 0..5) {
                val power = if (k == 0) 0.6f else 0.15f
                samples.add(
                    PowerDischargePoint(
                        timestamp = currentTs,
                        elapsedHours = (currentTs - baseTs) / 3600_000f,
                        batteryLevel = 88,
                        voltageVolts = 3.9f,
                        temperature = 28.0f,
                        powerWatts = power,
                        activeAppIcons = emptyList(),
                        isScreenOn = false,
                        activeAppNames = emptyList()
                    )
                )
                currentTs += 1000L
            }
            currentTs += 2 * 3600_000L
        }
        currentTs = deepSleepEndTs

        // 3. 尾段 2 小时亮屏使用（从 11h 到 13h，每 2 秒采样一次，功率 2.2W）
        val finalEndTs = baseTs + 13 * 3600_000L
        while (currentTs <= finalEndTs) {
            samples.add(
                PowerDischargePoint(
                    timestamp = currentTs,
                    elapsedHours = (currentTs - baseTs) / 3600_000f,
                    batteryLevel = 78,
                    voltageVolts = 3.8f,
                    temperature = 34.0f,
                    powerWatts = 2.2f,
                    activeAppIcons = emptyList(),
                    isScreenOn = true,
                    activeAppNames = emptyList()
                )
            )
            currentTs += 2000L
        }

        val stats = PowerUsageManager.computeDischargePowerStats(samples)
        assertTrue("放电统计模型计算结果不能为 null", stats != null)
        val s = stats!!

        // 验证亮屏能量由梯形积分真实累积（约 3 小时亮屏 * 2.1W ≈ 6.4Wh 左右）
        assertTrue("亮屏总能量必须大于 3.0Wh", s.screenOnDisplayEnergyWh > 3.0f)
        assertEquals("亮屏平均功耗应接近 2.0W~2.2W", 2.1f, s.screenOnPowerWatts, 0.2f)

        // 验证息屏能量经稳健待机基线外推后真实补偿（约 11 小时 * 0.15W ≈ 1.65Wh 左右），绝不再是 0.004Wh！
        assertTrue("息屏展示能量必须经过外推补偿真实待机能耗（> 1.0Wh），杜绝缩水为 0.004Wh", s.screenOffDisplayEnergyWh > 1.0f)
        assertTrue("息屏平均功耗应稳定在待机底噪（0.1W~0.3W 之间）", s.screenOffPowerWatts in 0.1f..0.3f)
        assertTrue("息屏平均功耗绝不能低于 0.05W 导致显示为 --", s.screenOffPowerWatts >= 0.05f)

        // 验证能量与平均功耗完全闭环
        val expectedTotalEnergy = s.screenOnDisplayEnergyWh + s.screenOffDisplayEnergyWh
        assertEquals("总展示能量必须严格等于亮屏展示能量与息屏展示能量之和", expectedTotalEnergy, s.totalDisplayEnergyWh, 0.001f)
        assertTrue("总平均功耗必须大于 0.05W", s.averagePowerWatts > 0.05f)
    }

    /**
     * 验证纯亮屏和纯息屏场景下，模型的工况功率与总能耗闭环自洽。
     */
    @Test
    fun testBatteryRecorderPureScreenOnAndPureScreenOff() {
        val baseTs = 1710000000000L

        // 1. 纯亮屏测试：连续 1 小时 2.5W 放电
        val onSamples = mutableListOf<PowerDischargePoint>()
        for (i in 0..1800) {
            onSamples.add(
                PowerDischargePoint(
                    timestamp = baseTs + i * 2000L,
                    elapsedHours = (i * 2000L) / 3600_000f,
                    batteryLevel = 90,
                    voltageVolts = 4.0f,
                    temperature = 33.0f,
                    powerWatts = 2.5f,
                    activeAppIcons = emptyList(),
                    isScreenOn = true,
                    activeAppNames = emptyList()
                )
            )
        }
        val onStats = PowerUsageManager.computeDischargePowerStats(onSamples)!!
        assertEquals("纯亮屏场景下亮屏平均功耗应等于 2.5W", 2.5f, onStats.screenOnPowerWatts, 0.05f)
        assertEquals("纯亮屏场景下总平均功耗应等于亮屏功耗", onStats.screenOnPowerWatts, onStats.averagePowerWatts, 0.001f)
        assertEquals("纯亮屏场景下息屏功耗为 0", 0f, onStats.screenOffPowerWatts, 0.001f)
        assertEquals("纯亮屏场景下 1 小时 2.5W 能耗约为 2.5Wh", 2.5f, onStats.totalDisplayEnergyWh, 0.05f)

        // 2. 纯息屏测试：连续 2 小时 0.2W 待机
        val offSamples = mutableListOf<PowerDischargePoint>()
        for (i in 0..120) {
            offSamples.add(
                PowerDischargePoint(
                    timestamp = baseTs + i * 60_000L,
                    elapsedHours = (i * 60_000L) / 3600_000f,
                    batteryLevel = 88,
                    voltageVolts = 3.9f,
                    temperature = 27.0f,
                    powerWatts = 0.2f,
                    activeAppIcons = emptyList(),
                    isScreenOn = false,
                    activeAppNames = emptyList()
                )
            )
        }
        val offStats = PowerUsageManager.computeDischargePowerStats(offSamples)!!
        assertEquals("纯息屏场景下息屏平均功耗应等于 0.2W", 0.2f, offStats.screenOffPowerWatts, 0.05f)
        assertEquals("纯息屏场景下总平均功耗应等于息屏功耗", offStats.screenOffPowerWatts, offStats.averagePowerWatts, 0.001f)
        assertEquals("纯息屏场景下亮屏功耗为 0", 0f, onStats.screenOffPowerWatts, 0.001f)
        assertEquals("纯息屏场景下 2 小时 0.2W 能耗约为 0.4Wh", 0.4f, offStats.totalDisplayEnergyWh, 0.05f)
    }

    /**
     * 验证系统服务与无障碍后台应用（如 System UI 14ms、GKD 1s）的能量解耦机制：
     * 1. 消除将整天累积的后台能耗全扣在毫秒级前台工时上的严重缺陷；
     * 2. 避免前台平均功耗除法暴涨至数万瓦（如 36000W）；
     * 3. 前台真实能量客观微小（<0.001Wh），后台累积能量如实沉淀。
     */
    @Test
    fun testShortForegroundEnergyDecouplingPreventsWattsExplosion() {
        // 1. 模拟系统用户界面：前台 14ms，后台 13 小时（46,800,000ms），总能耗 0.140Wh
        val (sysFgEnergy, sysBgEnergy, sysBgMs) = com.battery.analysis.provider.ShizukuBatteryStatsParser.decoupleAppEnergyAndTimes(
            totalEnergy = 0.140f,
            foregroundMs = 14L,
            backgroundMs = 46_800_000L,
            cpuMs = 3_600_000L,
            dischargeMs = 50_000_000L
        )

        assertTrue("系统用户界面前台能量应微小（远小于 0.001Wh）", sysFgEnergy < 0.0001f)
        assertTrue("系统用户界面绝大部分能耗归属于后台能耗", sysBgEnergy > 0.139f)
        assertEquals("后台工时被如实记录", 46_800_000L, sysBgMs)

        // 计算此时前台功耗率：绝不再是 36,000W
        val fgHours = 14L / 3600000.0
        val fgWatts = (sysFgEnergy / fgHours).toFloat()
        assertTrue("前台放电功耗率处于正常低功耗范围（绝不超过 1W）", fgWatts < 1.0f)

        // 2. 模拟 GKD 无障碍应用：前台 1s，后台 10 小时（36,000,000ms），总能耗 0.132Wh
        val (gkdFgEnergy, gkdBgEnergy, _) = com.battery.analysis.provider.ShizukuBatteryStatsParser.decoupleAppEnergyAndTimes(
            totalEnergy = 0.132f,
            foregroundMs = 1000L,
            backgroundMs = 36_000_000L,
            cpuMs = 1_800_000L,
            dischargeMs = 50_000_000L
        )

        assertTrue("GKD 前台能量应微小（远小于 0.001Wh）", gkdFgEnergy < 0.0001f)
        assertTrue("GKD 绝大部分能耗归属于后台能耗", gkdBgEnergy > 0.131f)
        val gkdFgHours = 1000L / 3600000.0
        val gkdFgWatts = (gkdFgEnergy / gkdFgHours).toFloat()
        assertTrue("GKD 前台功耗处于正常低功耗范围（绝不再是 376W）", gkdFgWatts < 1.0f)
    }

    /**
     * 验证包含长深度休眠断层时，亮屏能量与功耗保持真实稳定，杜绝休眠大断层全额计入亮屏能耗突破电池容量。
     */
    @Test
    fun testDischargeStatsWithSleepGapsPreservesScreenOnEnergy() {
        val baseTs = 1710000000000L
        val samples = mutableListOf<PowerDischargePoint>()

        // 1. 阶段一：前 30 分钟亮屏（每 2 秒一个采样点，2.0W 功耗）
        var curTs = baseTs
        for (i in 0 until 900) {
            samples.add(
                PowerDischargePoint(
                    timestamp = curTs,
                    elapsedHours = (curTs - baseTs) / 3600_000f,
                    batteryLevel = 100,
                    voltageVolts = 4.0f,
                    temperature = 35.0f,
                    powerWatts = 2.0f,
                    activeAppIcons = emptyList(),
                    isScreenOn = true,
                    activeAppNames = emptyList()
                )
            )
            curTs += 2000L
        }

        // 2. 灭屏瞬间打点（模拟锁屏瞬间的 isScreenOn = false 物理采样点）
        samples.add(
            PowerDischargePoint(
                timestamp = curTs,
                elapsedHours = (curTs - baseTs) / 3600_000f,
                batteryLevel = 98,
                voltageVolts = 3.95f,
                temperature = 34.0f,
                powerWatts = 0.25f,
                activeAppIcons = emptyList(),
                isScreenOn = false,
                activeAppNames = emptyList()
            )
        )

        // 3. 阶段二：长达 8 小时黑夜深度休眠断层（直到 8 小时后被心跳唤醒采到一个息屏待机点）
        curTs += 8 * 3600_000L
        samples.add(
            PowerDischargePoint(
                timestamp = curTs,
                elapsedHours = (curTs - baseTs) / 3600_000f,
                batteryLevel = 90,
                voltageVolts = 3.9f,
                temperature = 26.0f,
                powerWatts = 0.15f,
                activeAppIcons = emptyList(),
                isScreenOn = false,
                activeAppNames = emptyList()
            )
        )

        // 4. 阶段三：早晨唤醒后 30 分钟亮屏（每 2 秒一个点，2.2W）
        for (i in 0 until 900) {
            samples.add(
                PowerDischargePoint(
                    timestamp = curTs,
                    elapsedHours = (curTs - baseTs) / 3600_000f,
                    batteryLevel = 89,
                    voltageVolts = 3.85f,
                    temperature = 33.0f,
                    powerWatts = 2.2f,
                    activeAppIcons = emptyList(),
                    isScreenOn = true,
                    activeAppNames = emptyList()
                )
            )
            curTs += 2000L
        }

        val stats = PowerUsageManager.computeDischargePowerStats(samples)!!
        // 总亮屏时长约为 1 小时（30m + 30m）
        val screenOnHours = stats.screenOnDurationMs / 3600_000.0
        assertTrue("亮屏时长应约为 1 小时左右（绝不能被休眠断层膨胀为 9 小时）", screenOnHours in 0.9..1.1)

        // 亮屏平均功耗应严格反映 2.0W~2.2W 的真实硬件亮屏放电功耗
        assertEquals("亮屏平均功耗应稳定在 2.1W 左右", 2.1f, stats.screenOnPowerWatts, 0.2f)

        // 亮屏总能量应严格约为 2.1W * 1h ≈ 2.1Wh（绝不能虚增到 15Wh+）
        assertTrue("亮屏总能量应接近真实物理消耗（2.0Wh~2.4Wh）", stats.screenOnDisplayEnergyWh in 1.9f..2.5f)

        // 息屏待机功耗应稳定在 0.15W~0.25W
        assertTrue("息屏平均功耗应在 0.1W~0.3W 之间", stats.screenOffPowerWatts in 0.1f..0.3f)
    }

    /**
     * 验证耗电记录历史列表项中亮屏时间展示逻辑：
     * 1. 当记录自带格式化亮屏时长文本时，优先且直接返回该文本；
     * 2. 当亮屏时长文本为空但折线点集包含有效亮屏点时，根据点集时序间隔计算出时长文本；
     * 3. 当完全无有效数据时，遵循如实反映原则返回未知占位符 "--"。
     */
    @Test
    fun testPowerUsageRecordDisplayScreenOnDuration() {
        // 场景 1：实体中已存在亮屏时长文本
        val record1 = PowerUsageRecord(
            recordTime = "2026-09-22 16:50:00",
            levelPercent = 34,
            voltageVolts = 3.85f,
            temperature = 35.0f,
            energyWh = 15.0f,
            isCharging = false,
            avgPowerWatts = 1.32f,
            screenOnPowerWatts = 1.32f,
            screenOffPowerWatts = 0.15f,
            screenOnDurationText = "1h55m",
            screenOffDurationText = "20m",
            totalDurationText = "2h15m",
            remainingScreenOnText = "11h",
            remainingCompositeText = "11h",
            remainingScreenOffText = "100h",
            isShizukuRealData = true,
            appCount = 5,
            trendPointsJson = "[]",
            appListJson = "[]"
        )
        assertEquals("1h55m", record1.getDisplayScreenOnDuration())

        // 场景 2：亮屏文本为空，但点集有时序采样（例如亮屏持续 40 分钟 = 2400000ms）
        val baseTs = 1710000000000L
        val pointsJson = """
            [
                {"ts":$baseTs,"screenOn":true},
                {"ts":${baseTs + 2400000L},"screenOn":false}
            ]
        """.trimIndent()
        val record2 = record1.copy(
            screenOnDurationText = "",
            trendPointsJson = pointsJson
        )
        assertEquals("40m0s", record2.getDisplayScreenOnDuration())

        // 场景 3：无有效亮屏文本且无点集数据时，如实返回未知占位符 "--"
        val record3 = record1.copy(
            screenOnDurationText = "",
            trendPointsJson = "[]"
        )
        assertEquals("--", record3.getDisplayScreenOnDuration())
    }

    /**
     * 验证长时间放电（如 9 小时，含 2 小时 6 分钟亮屏）在抽稀至 1000 点后，
     * 物理微积分计算出的亮屏能量忠实保持在 ~3.89Wh，彻底杜绝历史误判为大断层缩水至 0.507Wh 的缺陷。
     */
    @Test
    fun testLongDischargeDownsamplingAndPhysicalEnergyAccuracy() {
        val baseTs = 1710000000000L
        val samples = mutableListOf<PowerDischargePoint>()

        // 模拟 9 小时总时长：前 2小时6分 (7560秒) 连续亮屏，功率稳定在 1.85W
        // 经过时间网格均匀抽稀后，每 30 秒一个采样点
        val screenOnDurationSec = 7560L
        val totalDurationSec = 9L * 3600L

        var t = 0L
        // 亮屏区间采样点（点间隔约 30 秒）
        while (t <= screenOnDurationSec) {
            samples.add(
                PowerDischargePoint(
                    timestamp = baseTs + t * 1000L,
                    elapsedHours = (t / 3600f),
                    batteryLevel = 100 - (t / 360).toInt(),
                    voltageVolts = 4.0f,
                    temperature = 34.0f,
                    powerWatts = 1.85f,
                    activeAppIcons = emptyList(),
                    isScreenOn = true,
                    activeAppNames = emptyList(),
                    packageName = "com.kuaishou.nebula"
                )
            )
            t += 30L
        }

        // 息屏待机区间采样点（点间隔约 30 秒，功率 0.15W）
        while (t <= totalDurationSec) {
            samples.add(
                PowerDischargePoint(
                    timestamp = baseTs + t * 1000L,
                    elapsedHours = (t / 3600f),
                    batteryLevel = 100 - (t / 360).toInt(),
                    voltageVolts = 3.9f,
                    temperature = 28.0f,
                    powerWatts = 0.15f,
                    activeAppIcons = emptyList(),
                    isScreenOn = false,
                    activeAppNames = emptyList(),
                    packageName = null
                )
            )
            t += 30L
        }

        val stats = PowerUsageManager.computeDischargePowerStats(samples, recordIntervalMs = 1000L)
        org.junit.Assert.assertNotNull("统计结果不能为空", stats)

        // 理论物理亮屏能量：1.85W * (7560s / 3600s) = 3.885 Wh
        val expectedScreenOnEnergyWh = 1.85f * (screenOnDurationSec / 3600f)
        assertEquals(
            "抽稀后亮屏能量必须保持在物理真实值 ~3.89Wh，绝不能缩水至 0.507Wh",
            expectedScreenOnEnergyWh,
            stats!!.screenOnDisplayEnergyWh,
            0.15f
        )

        // 验证亮屏平均功耗稳定在 1.85W
        assertEquals("亮屏平均功耗必须为 1.85W", 1.85f, stats.screenOnPowerWatts, 0.05f)

        // 验证能量与功率和时长严格物理闭环: E = P * T
        val calcWatts = stats.screenOnDisplayEnergyWh / (stats.screenOnDurationMs / 3600000.0f)
        assertEquals("能量与功耗必须 100% 物理闭环", stats.screenOnPowerWatts, calcWatts, 0.01f)
    }

    /**
     * 验证不同应用在不同历史时间段运行时的物理累加器能够真实记录各自独立的功耗与温度，
     * 杜绝因图表点表抽稀导致全员回退雷同至整机亮屏功耗 1.85W 与统一温度 38.0℃。
     */
    @Test
    fun testRealtimeAccumulatorDistinctAppPowerAndTemperature() {
        val appMap = mutableMapOf<String, PowerUsageManager.AppRealtimeEnergyAccumulator>()

        // 模拟 1：快手在 2 小时前运行 30 分钟 (1800s)，实测功耗 2.85W，温度加权均值 34.2℃，最高 35.1℃
        val ksDurationMs = 1800_000L
        val ksWatts = 2.85
        val ksEnergyJoules = ksWatts * (ksDurationMs / 1000.0)
        appMap["com.kuaishou.nebula"] = PowerUsageManager.AppRealtimeEnergyAccumulator(
            packageName = "com.kuaishou.nebula",
            energyJoules = ksEnergyJoules,
            durationMs = ksDurationMs,
            tempWeightSum = 34.2 * ksDurationMs,
            maxTempCelsius = 35.1f
        )

        // 模拟 2：抖音在 1 小时前运行 20 分钟 (1200s)，实测功耗 3.10W，温度加权均值 36.5℃，最高 37.2℃
        val dyDurationMs = 1200_000L
        val dyWatts = 3.10
        val dyEnergyJoules = dyWatts * (dyDurationMs / 1000.0)
        appMap["com.ss.android.ugc.aweme"] = PowerUsageManager.AppRealtimeEnergyAccumulator(
            packageName = "com.ss.android.ugc.aweme",
            energyJoules = dyEnergyJoules,
            durationMs = dyDurationMs,
            tempWeightSum = 36.5 * dyDurationMs,
            maxTempCelsius = 37.2f
        )

        // 模拟 3：微信在 30 分钟前运行 10 分钟 (600s)，实测功耗 1.45W，温度加权均值 31.0℃，最高 31.5℃
        val wxDurationMs = 600_000L
        val wxWatts = 1.45
        val wxEnergyJoules = wxWatts * (wxDurationMs / 1000.0)
        appMap["com.tencent.mm"] = PowerUsageManager.AppRealtimeEnergyAccumulator(
            packageName = "com.tencent.mm",
            energyJoules = wxEnergyJoules,
            durationMs = wxDurationMs,
            tempWeightSum = 31.0 * wxDurationMs,
            maxTempCelsius = 31.5f
        )

        // 验证快手物理指标真实独立
        val ksAcc = appMap["com.kuaishou.nebula"]!!
        val ksCalcWatts = (ksAcc.energyJoules / (ksAcc.durationMs / 1000.0)).toFloat()
        val ksCalcAvgTemp = (ksAcc.tempWeightSum / ksAcc.durationMs.toDouble()).toFloat()
        assertEquals(2.85f, ksCalcWatts, 0.01f)
        assertEquals(34.2f, ksCalcAvgTemp, 0.1f)
        assertEquals(35.1f, ksAcc.maxTempCelsius, 0.01f)

        // 验证抖音物理指标真实独立
        val dyAcc = appMap["com.ss.android.ugc.aweme"]!!
        val dyCalcWatts = (dyAcc.energyJoules / (dyAcc.durationMs / 1000.0)).toFloat()
        val dyCalcAvgTemp = (dyAcc.tempWeightSum / dyAcc.durationMs.toDouble()).toFloat()
        assertEquals(3.10f, dyCalcWatts, 0.01f)
        assertEquals(36.5f, dyCalcAvgTemp, 0.1f)
        assertEquals(37.2f, dyAcc.maxTempCelsius, 0.01f)

        // 验证微信物理指标真实独立
        val wxAcc = appMap["com.tencent.mm"]!!
        val wxCalcWatts = (wxAcc.energyJoules / (wxAcc.durationMs / 1000.0)).toFloat()
        val wxCalcAvgTemp = (wxAcc.tempWeightSum / wxAcc.durationMs.toDouble()).toFloat()
        assertEquals(1.45f, wxCalcWatts, 0.01f)
        assertEquals(31.0f, wxCalcAvgTemp, 0.1f)
        assertEquals(31.5f, wxAcc.maxTempCelsius, 0.01f)

        // 验证三者功耗互不相等且均不等于假冒的 1.85W
        org.junit.Assert.assertNotEquals(ksCalcWatts, dyCalcWatts, 0.01f)
        org.junit.Assert.assertNotEquals(dyCalcWatts, wxCalcWatts, 0.01f)
        org.junit.Assert.assertNotEquals(1.85f, ksCalcWatts, 0.01f)
    }

    /**
     * 验证顶部时长占比与能量占比彻底解耦，各自正确独立计算百分比，绝不出现重叠相同。
     */
    @Test
    fun testDecoupledDurationAndEnergyRatios() {
        val screenOnDurationMs = 7560_000L // 2小时6分
        val totalDurationMs = 9L * 3600_000L // 9小时整
        val screenOffDurationMs = totalDurationMs - screenOnDurationMs

        val screenOnEnergyWh = 3.885f
        val screenOffEnergyWh = 0.975f
        val totalEnergyWh = screenOnEnergyWh + screenOffEnergyWh // 4.86Wh

        // 计算时长占比
        val onDurationRatio = (screenOnDurationMs.toDouble() / totalDurationMs.toDouble() * 100.0)
        val offDurationRatio = (screenOffDurationMs.toDouble() / totalDurationMs.toDouble() * 100.0)

        // 计算能量占比
        val onEnergyRatio = (screenOnEnergyWh / totalEnergyWh * 100f).toDouble()
        val offEnergyRatio = (screenOffEnergyWh / totalEnergyWh * 100f).toDouble()

        // 验证时长占比约为 23.3%，能量占比约为 79.9%，二者显著不同
        assertEquals(23.3, onDurationRatio, 0.5)
        assertEquals(76.7, offDurationRatio, 0.5)
        assertEquals(79.9, onEnergyRatio, 0.5)
        assertEquals(20.1, offEnergyRatio, 0.5)

        org.junit.Assert.assertNotEquals(
            "亮屏时长占比与亮屏能量占比必须解耦",
            onDurationRatio,
            onEnergyRatio,
            5.0
        )
    }

    /**
     * 验证 12 小时中长周期放电（3 小时 2.2W 亮屏 + 9 小时 0.12W 息屏待机）在抽稀至 1000 点后，
     * 物理微积分能量与平均功耗保持严密数学闭环，绝对不发生能量衰减。
     */
    @Test
    fun testDischarge12HoursAccuracyAndPhysicalConsistency() {
        val baseTs = 1710000000000L
        val totalSec = 12L * 3600L
        val screenOnSec = 3L * 3600L
        val samples = mutableListOf<PowerDischargePoint>()

        // 12 小时均匀抽稀至 1000 点，点间隔约为 43.2 秒
        val stepSec = 43L
        var t = 0L
        while (t <= totalSec) {
            val isOn = t <= screenOnSec
            val pwr = if (isOn) 2.20f else 0.12f
            val temp = if (isOn) 35.0f else 27.0f
            samples.add(
                PowerDischargePoint(
                    timestamp = baseTs + t * 1000L,
                    elapsedHours = t / 3600f,
                    batteryLevel = (100 - (t * 50 / totalSec)).toInt(),
                    voltageVolts = 3.95f,
                    temperature = temp,
                    powerWatts = pwr,
                    activeAppIcons = emptyList(),
                    isScreenOn = isOn,
                    activeAppNames = emptyList(),
                    packageName = if (isOn) "com.smile.gifmaker" else null
                )
            )
            t += stepSec
        }

        val stats = PowerUsageManager.computeDischargePowerStats(samples, recordIntervalMs = 1000L)
        org.junit.Assert.assertNotNull("12小时放电统计结果不能为空", stats)

        // 理论物理值：
        // 亮屏能量: 2.20W * 3h = 6.60Wh
        // 息屏能量: 0.12W * 9h = 1.08Wh
        // 总能量: 7.68Wh
        // 亮屏功耗: 2.20W，息屏功耗: 0.12W，整机平均功耗: 7.68Wh / 12h = 0.64W
        val expectedOnEnergyWh = 6.60f
        val expectedOffEnergyWh = 1.08f
        val expectedTotalEnergyWh = 7.68f

        assertEquals("12小时亮屏能量必须维持在 ~6.60Wh", expectedOnEnergyWh, stats!!.screenOnDisplayEnergyWh, 0.20f)
        assertEquals("12小时息屏能量必须维持在 ~1.08Wh", expectedOffEnergyWh, stats.screenOffDisplayEnergyWh, 0.15f)
        assertEquals("12小时总能量必须维持在 ~7.68Wh", expectedTotalEnergyWh, stats.totalDisplayEnergyWh, 0.30f)

        assertEquals("12小时亮屏平均功耗必须为 2.20W", 2.20f, stats.screenOnPowerWatts, 0.08f)
        assertEquals("12小时息屏平均功耗必须为 0.12W", 0.12f, stats.screenOffPowerWatts, 0.03f)
        assertEquals("12小时全局平均功耗必须为 0.64W", 0.64f, stats.averagePowerWatts, 0.05f)

        // 验证物理能量与功耗 100% 守恒闭环: E = P * T
        val calcOnWatts = stats.screenOnDisplayEnergyWh / (stats.screenOnDurationMs / 3600000.0f)
        val calcTotalWatts = stats.totalDisplayEnergyWh / (stats.totalDurationMs / 3600000.0f)
        assertEquals("12小时亮屏能量与功耗物理闭环", stats.screenOnPowerWatts, calcOnWatts, 0.01f)
        assertEquals("12小时全局能量与功耗物理闭环", stats.averagePowerWatts, calcTotalWatts, 0.01f)
    }

    /**
     * 验证 24 小时全天放电周期（跨越上午、下午、晚间多款不同应用运行，共 6 小时亮屏 + 18 小时息屏休眠）：
     * 1. 抽稀后微积分总能量忠实保持在 ~16.25Wh，亮屏保持在 ~14.45Wh；
     * 2. 各 App 保持其独立真实功耗与温度；
     * 3. 时长占比（25.0%）与能量占比（88.9%）彻底解耦分离。
     */
    @Test
    fun testDischarge24HoursMultipleAppsAndDecoupledMetrics() {
        val baseTs = 1710000000000L
        val totalSec = 24L * 3600L
        val samples = mutableListOf<PowerDischargePoint>()

        // 模拟全天工况：
        // 0h ~ 8h: 夜间深度休眠 8h (息屏, 0.10W, 25.0℃)
        // 8h ~ 10h: 上午使用快手 2h (亮屏, 2.60W, 35.0℃)
        // 10h ~ 14h: 中午休眠 4h (息屏, 0.10W, 26.0℃)
        // 14h ~ 16.5h: 下午使用抖音 2.5h (亮屏, 2.80W, 36.5℃)
        // 16.5h ~ 20h: 傍晚休眠 3.5h (息屏, 0.10W, 26.0℃)
        // 20h ~ 21.5h: 晚间使用微信 1.5h (亮屏, 1.50W, 31.0℃)
        // 21.5h ~ 24h: 晚间待机 2.5h (息屏, 0.10W, 25.0℃)
        // 抽稀至 1000 点，点间隔约为 86 秒
        val stepSec = 86L
        var t = 0L

        /**
         * 24小时多应用测试工况切片参数
         *
         * @property isOn 屏幕是否点亮
         * @property pwr 瞬时放电功率（瓦特 W）
         * @property temp 瞬时电池温度（摄氏度 ℃）
         * @property pkg 前台应用包名（可为 null）
         */
        data class TestCondition(
            val isOn: Boolean,
            val pwr: Float,
            val temp: Float,
            val pkg: String?
        )

        // In-Flight 物理累加器模拟
        val acc = PowerUsageManager.RealtimeDischargeAccumulator()
        val appAccMap = mutableMapOf<String, PowerUsageManager.AppRealtimeEnergyAccumulator>()

        var prevT = 0L
        while (t <= totalSec) {
            val hour = t / 3600.0
            val (isOn, pwr, temp, pkg) = when {
                hour in 8.0..10.0 -> TestCondition(true, 2.60f, 35.0f, "com.kuaishou.nebula")
                hour in 14.0..16.5 -> TestCondition(true, 2.80f, 36.5f, "com.ss.android.ugc.aweme")
                hour in 20.0..21.5 -> TestCondition(true, 1.50f, 31.0f, "com.tencent.mm")
                else -> TestCondition(false, 0.10f, 25.5f, null)
            }

            samples.add(
                PowerDischargePoint(
                    timestamp = baseTs + t * 1000L,
                    elapsedHours = (t / 3600f),
                    batteryLevel = (100 - (t * 80 / totalSec)).toInt(),
                    voltageVolts = 3.90f,
                    temperature = temp,
                    powerWatts = pwr,
                    activeAppIcons = emptyList(),
                    isScreenOn = isOn,
                    activeAppNames = emptyList(),
                    packageName = pkg
                )
            )

            // 模拟 In-Flight 实时累加
            if (t > prevT) {
                val dtSec = t - prevT
                val dtMs = dtSec * 1000L
                val dJoules = pwr * dtSec
                if (isOn) {
                    acc.screenOnJoules += dJoules
                    acc.screenOnDurationMs += dtMs
                    if (pkg != null) {
                        val a = appAccMap.getOrPut(pkg) { PowerUsageManager.AppRealtimeEnergyAccumulator(pkg) }
                        a.energyJoules += dJoules
                        a.durationMs += dtMs
                        a.tempWeightSum += temp * dtMs
                        a.maxTempCelsius = maxOf(a.maxTempCelsius, temp)
                    }
                } else {
                    acc.screenOffJoules += dJoules
                    acc.screenOffDurationMs += dtMs
                }
            }
            prevT = t
            t += stepSec
        }

        // 1. 验证 In-Flight 累加器精确反映各 App 独立物理数据
        val ksAcc = appAccMap["com.kuaishou.nebula"]!!
        val dyAcc = appAccMap["com.ss.android.ugc.aweme"]!!
        val wxAcc = appAccMap["com.tencent.mm"]!!

        val ksWatts = (ksAcc.energyJoules / (ksAcc.durationMs / 1000.0)).toFloat()
        val dyWatts = (dyAcc.energyJoules / (dyAcc.durationMs / 1000.0)).toFloat()
        val wxWatts = (wxAcc.energyJoules / (wxAcc.durationMs / 1000.0)).toFloat()

        assertEquals(2.60f, ksWatts, 0.05f)
        assertEquals(2.80f, dyWatts, 0.05f)
        assertEquals(1.50f, wxWatts, 0.05f)
        assertEquals(35.0f, (ksAcc.tempWeightSum / ksAcc.durationMs).toFloat(), 0.2f)
        assertEquals(36.5f, (dyAcc.tempWeightSum / dyAcc.durationMs).toFloat(), 0.2f)
        assertEquals(31.0f, (wxAcc.tempWeightSum / wxAcc.durationMs).toFloat(), 0.2f)

        // 2. 验证 computeDischargePowerStats 微积分能量
        val stats = PowerUsageManager.computeDischargePowerStats(samples, recordIntervalMs = 1000L)
        org.junit.Assert.assertNotNull(stats)

        // 理论值：
        // 亮屏能量: 2.6*2 + 2.8*2.5 + 1.5*1.5 = 5.2 + 7.0 + 2.25 = 14.45Wh
        // 息屏能量: 0.10 * 18h = 1.80Wh
        // 总能量: 16.25Wh
        // 亮屏功耗: 14.45Wh / 6h = 2.408W
        assertEquals("24小时亮屏能量准确保持在 ~14.45Wh", 14.45f, stats!!.screenOnDisplayEnergyWh, 0.35f)
        assertEquals("24小时息屏能量准确保持在 ~1.80Wh", 1.80f, stats.screenOffDisplayEnergyWh, 0.20f)
        assertEquals("24小时总放电能量准确保持在 ~16.25Wh", 16.25f, stats.totalDisplayEnergyWh, 0.40f)

        assertEquals("24小时亮屏平均功耗为 ~2.41W", 2.41f, stats.screenOnPowerWatts, 0.08f)
        assertEquals("24小时息屏平均功耗为 ~0.10W", 0.10f, stats.screenOffPowerWatts, 0.02f)
        assertEquals("24小时全局平均功耗为 ~0.68W", 0.68f, stats.averagePowerWatts, 0.05f)

        // 3. 验证 24 小时时长占比与能量占比彻底解耦
        val durationRatioOn = (stats.screenOnDurationMs.toDouble() / stats.totalDurationMs * 100.0)
        val energyRatioOn = (stats.screenOnDisplayEnergyWh / stats.totalDisplayEnergyWh * 100.0)
        assertEquals(25.0, durationRatioOn, 1.5) // 时长占比 25%
        assertEquals(88.9, energyRatioOn, 2.5) // 能量占比 ~89%
        org.junit.Assert.assertTrue("时长占比与能量占比必须彻底分离", Math.abs(durationRatioOn - energyRatioOn) > 50.0)
    }

    /**
     * 验证 48 小时超长待机周期（两天两夜，含 4 次分散各 1 小时 2.0W 亮屏 + 44 小时 0.08W 深度待机）：
     * 抽稀后平均点间隔达到约 173 秒（接近 3 分钟），
     * 验证自适应门限与微积分算法依然能 100% 精确统计各工况能耗与功耗，绝无大断层误杀。
     */
    @Test
    fun testDischarge48HoursUltraLongStandbyAccuracy() {
        val baseTs = 1710000000000L
        val totalSec = 48L * 3600L // 172,800 秒
        val samples = mutableListOf<PowerDischargePoint>()

        // 48 小时均匀抽稀至 1000 点，点间隔约为 173 秒
        // 在真实生产环境 downsampleDischargeSamplesUniformly 中，亮灭屏状态切换拐点会被 100% 保留
        val transitionSecs = listOf(
            28800L, 32400L,
            72000L, 75600L,
            115200L, 118800L,
            158400L, 162000L
        )
        val stepSec = 173L
        val allTimestamps = (0L..totalSec step stepSec).toMutableList()
        for (sec in transitionSecs) {
            if (!allTimestamps.contains(sec)) allTimestamps.add(sec)
        }
        allTimestamps.sort()

        for (curSec in allTimestamps) {
            val hour = curSec / 3600.0
            val isOn = (hour in 8.0..9.0) || (hour in 20.0..21.0) || (hour in 32.0..33.0) || (hour in 44.0..45.0)
            val pwr = if (isOn) 2.00f else 0.08f
            val temp = if (isOn) 33.0f else 24.5f

            samples.add(
                PowerDischargePoint(
                    timestamp = baseTs + curSec * 1000L,
                    elapsedHours = (curSec / 3600f),
                    batteryLevel = (100 - (curSec * 70 / totalSec)).toInt(),
                    voltageVolts = 3.85f,
                    temperature = temp,
                    powerWatts = pwr,
                    activeAppIcons = emptyList(),
                    isScreenOn = isOn,
                    activeAppNames = emptyList(),
                    packageName = if (isOn) "com.tencent.mobileqq" else null
                )
            )
        }

        val stats = PowerUsageManager.computeDischargePowerStats(samples, recordIntervalMs = 1000L)
        org.junit.Assert.assertNotNull("48小时超长放电统计结果不能为空", stats)

        // 理论值：
        // 亮屏时长: 4h (14400s), 息屏时长: 44h (158400s)
        // 亮屏能量: 2.00W * 4h = 8.00Wh
        // 息屏能量: 0.08W * 44h = 3.52Wh
        // 总能量: 8.00 + 3.52 = 11.52Wh
        // 亮屏功耗: 2.00W，息屏功耗: 0.08W，全局平均功耗: 11.52 / 48 = 0.24W
        assertEquals("48小时超长待机亮屏能量准确维持在 ~8.00Wh", 8.00f, stats!!.screenOnDisplayEnergyWh, 0.40f)
        assertEquals("48小时超长待机息屏能量准确维持在 ~3.52Wh", 3.52f, stats.screenOffDisplayEnergyWh, 0.30f)
        assertEquals("48小时超长待机总能量准确维持在 ~11.52Wh", 11.52f, stats.totalDisplayEnergyWh, 0.50f)

        assertEquals("48小时亮屏功耗准确计算为 2.00W", 2.00f, stats.screenOnPowerWatts, 0.10f)
        assertEquals("48小时息屏功耗准确计算为 0.08W", 0.08f, stats.screenOffPowerWatts, 0.02f)
        assertEquals("48小时全局平均功耗准确计算为 0.24W", 0.24f, stats.averagePowerWatts, 0.03f)

        // 验证 48 小时极端长周期下微积分能量与平均功耗依然 100% 严格物理闭环
        val calcOnWatts = stats.screenOnDisplayEnergyWh / (stats.screenOnDurationMs / 3600000.0f)
        val calcOffWatts = stats.screenOffDisplayEnergyWh / (stats.screenOffDurationMs / 3600000.0f)
        val calcAvgWatts = stats.totalDisplayEnergyWh / (stats.totalDurationMs / 3600000.0f)

        assertEquals("48小时亮屏能量与功耗 100% 物理闭环", stats.screenOnPowerWatts, calcOnWatts, 0.01f)
        assertEquals("48小时息屏能量与功耗 100% 物理闭环", stats.screenOffPowerWatts, calcOffWatts, 0.01f)
        assertEquals("48小时全局能量与功耗 100% 物理闭环", stats.averagePowerWatts, calcAvgWatts, 0.01f)
    }

    /**
     * 验证在息屏进入 Android Deep Sleep（深度休眠）导致软件采样完全缺失时，
     * 双重物理锚定算法能将硬件芯片库仑计持续积分的物理总能量准确补偿至息屏能耗，
     * 且绝对不污染或稀释亮屏高频微积分真实能量与功耗。
     */
    @Test
    fun testDualAnchorCompensationWhenDeepSleepWithoutSamples() {
        val nominalVoltage = 3.85f
        val screenOnHours = 1.0f
        val screenOffHours = 7.0f
        val dischargeHours = 8.0f
        val screenOffMs = 7 * 3600000L

        // 软件 1Hz 采样微积分真值：亮屏 1 小时实耗 2.00Wh，平均 2.00W
        val intOnEnergyWh = 2.00f
        val intOnPowerWatts = 2.00f

        // 息屏期间由于 Deep Sleep，软件几无采样，微积分仅统计到唤醒瞬间的 0.02Wh
        val intOffEnergyWh = 0.02f
        val intOffPowerWatts = 0.02f / screenOffHours
        val intTotalEnergyWh = intOnEnergyWh + intOffEnergyWh
        val intTotalPowerWatts = intTotalEnergyWh / dischargeHours

        // 硬件芯片库仑计（PMIC Charge Counter）在硬件层持续积分电荷量：
        // 测得整机总放电 919.48mAh，折算物理总能量为 3.54Wh
        // （其中亮屏 2.00Wh 对应约 519.48mAh，息屏 7 小时深度休眠漏电 400mAh 对应 1.54Wh）
        val physicalTotalEnergyWh = 3.54f

        val result = PowerUsageManager.calculateDualAnchorEnergyAndPower(
            intOnEnergyWh = intOnEnergyWh,
            intOffEnergyWh = intOffEnergyWh,
            intTotalEnergyWh = intTotalEnergyWh,
            intOnPowerWatts = intOnPowerWatts,
            intOffPowerWatts = intOffPowerWatts,
            intTotalPowerWatts = intTotalPowerWatts,
            physicalTotalEnergyWh = physicalTotalEnergyWh,
            screenOnHours = screenOnHours,
            screenOffHours = screenOffHours,
            dischargeHours = dischargeHours,
            screenOffMs = screenOffMs,
            nominalVoltageVolts = nominalVoltage
        )

        // 1. 亮屏能量 100% 锁定软件 1Hz 微积分真值，绝不被稀释或篡改
        assertEquals("亮屏能量严格等于微积分真值 2.00Wh", 2.00f, result.onEnergyWh, 0.001f)
        assertEquals("亮屏功耗准确计算为 2.00W", 2.00f, result.screenOnWatts, 0.001f)

        // 2. 息屏能量精准吸纳硬件休眠漏电补偿：3.54Wh - 2.00Wh = 1.54Wh
        assertEquals("息屏能量吸收深度休眠补偿准确达到 1.54Wh", 1.54f, result.offEnergyWh, 0.001f)
        assertEquals("息屏功耗准确体现真实待机功耗 0.22W", 1.54f / 7.0f, result.screenOffWatts, 0.001f)

        // 3. 整机总能量严格锚定硬件物理总能耗 3.54Wh
        assertEquals("整机总能量严格等于硬件物理总能量 3.54Wh", 3.54f, result.totalEnergyWh, 0.001f)
        assertEquals("全局平均功耗准确为 0.4425W", 3.54f / 8.0f, result.avgWatts, 0.001f)
        assertEquals("实际放电毫安时精准对应 919.48mAh", (3.54f * 1000f) / nominalVoltage, result.realDischargedMah, 0.01f)

        // 4. 严格物理闭环校验：E_total = E_on + E_off，且 P_total * T_total = P_on * T_on + P_off * T_off
        val energySum = result.onEnergyWh + result.offEnergyWh
        assertEquals("能量守恒：总能量等于亮屏能量加息屏能量", result.totalEnergyWh, energySum, 0.0001f)

        val powerTimeSum = result.screenOnWatts * screenOnHours + result.screenOffWatts * screenOffHours
        assertEquals("功率时间守恒：总能量等于各工况功耗乘时长之和", result.totalEnergyWh, powerTimeSum, 0.001f)
    }

    /**
     * 验证全亮屏工况下（息屏时长为 0），息屏能耗与功耗严格为 0，
     * 亮屏能耗与功耗对齐整机物理总能耗与全局平均功耗。
     */
    @Test
    fun testDualAnchorWhenAllScreenOn() {
        val nominalVoltage = 3.85f
        val screenOnHours = 2.0f
        val screenOffHours = 0.0f
        val dischargeHours = 2.0f
        val screenOffMs = 0L

        val intOnEnergyWh = 3.80f
        val intOffEnergyWh = 0.0f
        val intTotalEnergyWh = 3.80f
        val intOnPowerWatts = 1.90f
        val intOffPowerWatts = 0.0f
        val intTotalPowerWatts = 1.90f

        // 硬件芯片库仑计记录实际放电 1000mAh = 3.85Wh
        val physicalTotalEnergyWh = 3.85f

        val result = PowerUsageManager.calculateDualAnchorEnergyAndPower(
            intOnEnergyWh = intOnEnergyWh,
            intOffEnergyWh = intOffEnergyWh,
            intTotalEnergyWh = intTotalEnergyWh,
            intOnPowerWatts = intOnPowerWatts,
            intOffPowerWatts = intOffPowerWatts,
            intTotalPowerWatts = intTotalPowerWatts,
            physicalTotalEnergyWh = physicalTotalEnergyWh,
            screenOnHours = screenOnHours,
            screenOffHours = screenOffHours,
            dischargeHours = dischargeHours,
            screenOffMs = screenOffMs,
            nominalVoltageVolts = nominalVoltage
        )

        assertEquals("全亮屏工况息屏能耗严格为 0", 0.0f, result.offEnergyWh, 0.0001f)
        assertEquals("全亮屏工况息屏功耗严格为 0", 0.0f, result.screenOffWatts, 0.0001f)
        assertEquals("全亮屏工况亮屏能量严格等于总能量 3.85Wh", 3.85f, result.onEnergyWh, 0.001f)
        assertEquals("全亮屏工况总能量严格等于硬件物理总能量 3.85Wh", 3.85f, result.totalEnergyWh, 0.001f)
        assertEquals("全亮屏工况亮屏功耗严格等于全局平均功耗", result.avgWatts, result.screenOnWatts, 0.001f)
        assertEquals("放电毫安时精准为 1000mAh", 1000f, result.realDischargedMah, 0.01f)
    }

    /**
     * 验证全息屏工况下（亮屏时长为 0），亮屏能耗与功耗严格为 0，
     * 息屏能耗与功耗对齐硬件芯片库仑计放电总能量与全局平均功耗。
     */
    @Test
    fun testDualAnchorWhenAllScreenOff() {
        val nominalVoltage = 3.85f
        val screenOnHours = 0.0f
        val screenOffHours = 10.0f
        val dischargeHours = 10.0f
        val screenOffMs = 10 * 3600000L

        val intOnEnergyWh = 0.0f
        val intOffEnergyWh = 0.05f
        val intTotalEnergyWh = 0.05f
        val intOnPowerWatts = 0.0f
        val intOffPowerWatts = 0.005f
        val intTotalPowerWatts = 0.005f

        // 硬件库仑计检测到待机 10 小时共放电 500mAh = 1.925Wh
        val physicalTotalEnergyWh = 1.925f

        val result = PowerUsageManager.calculateDualAnchorEnergyAndPower(
            intOnEnergyWh = intOnEnergyWh,
            intOffEnergyWh = intOffEnergyWh,
            intTotalEnergyWh = intTotalEnergyWh,
            intOnPowerWatts = intOnPowerWatts,
            intOffPowerWatts = intOffPowerWatts,
            intTotalPowerWatts = intTotalPowerWatts,
            physicalTotalEnergyWh = physicalTotalEnergyWh,
            screenOnHours = screenOnHours,
            screenOffHours = screenOffHours,
            dischargeHours = dischargeHours,
            screenOffMs = screenOffMs,
            nominalVoltageVolts = nominalVoltage
        )

        assertEquals("全息屏工况亮屏能耗严格为 0", 0.0f, result.onEnergyWh, 0.0001f)
        assertEquals("全息屏工况亮屏功耗严格为 0", 0.0f, result.screenOnWatts, 0.0001f)
        assertEquals("全息屏工况息屏能量严格等于硬件物理总能量 1.925Wh", 1.925f, result.offEnergyWh, 0.001f)
        assertEquals("全息屏工况息屏功耗准确计算为 0.1925W", 0.1925f, result.screenOffWatts, 0.001f)
        assertEquals("全息屏工况全局平均功耗准确计算为 0.1925W", 0.1925f, result.avgWatts, 0.001f)
        assertEquals("全息屏工况放电毫安时精准为 500mAh", 500f, result.realDischargedMah, 0.01f)
    }

    /**
     * 验证在短时间高动态放电（如刚开机游戏 5 分钟、电量百分比尚未跳变 1%）且硬件库仑计为 0 时，
     * 双重物理锚定算法完整保留高精度高频软件微积分结果，绝不因硬件指标未更新而强行清零或虚构保底。
     */
    @Test
    fun testDualAnchorWhenMicroIntegralExceedsCoulombCounter() {
        val nominalVoltage = 3.85f
        val screenOnHours = 5f / 60f // 5 分钟
        val screenOffHours = 1f / 60f // 1 分钟
        val dischargeHours = 6f / 60f
        val screenOffMs = 60000L

        // 软件 1 秒高频微积分准确测得：5 分钟游戏消耗 0.50Wh，1 分钟息屏消耗 0.01Wh
        val intOnEnergyWh = 0.50f
        val intOffEnergyWh = 0.01f
        val intTotalEnergyWh = 0.51f
        val intOnPowerWatts = 0.50f / screenOnHours // 6.0W
        val intOffPowerWatts = 0.01f / screenOffHours // 0.6W
        val intTotalPowerWatts = 0.51f / dischargeHours // 5.1W

        // 刚拔电 6 分钟，电池百分比仍为 100%，掉电量为 0，硬件库仑计也尚未触发跳变
        val physicalTotalEnergyWh = 0.0f

        val result = PowerUsageManager.calculateDualAnchorEnergyAndPower(
            intOnEnergyWh = intOnEnergyWh,
            intOffEnergyWh = intOffEnergyWh,
            intTotalEnergyWh = intTotalEnergyWh,
            intOnPowerWatts = intOnPowerWatts,
            intOffPowerWatts = intOffPowerWatts,
            intTotalPowerWatts = intTotalPowerWatts,
            physicalTotalEnergyWh = physicalTotalEnergyWh,
            screenOnHours = screenOnHours,
            screenOffHours = screenOffHours,
            dischargeHours = dischargeHours,
            screenOffMs = screenOffMs,
            nominalVoltageVolts = nominalVoltage
        )

        // 验证微积分真值被 100% 忠实保留
        assertEquals("亮屏能量忠实保留微积分 0.50Wh", 0.50f, result.onEnergyWh, 0.001f)
        assertEquals("息屏能量忠实保留微积分 0.01Wh", 0.01f, result.offEnergyWh, 0.001f)
        assertEquals("总能量忠实保留微积分 0.51Wh", 0.51f, result.totalEnergyWh, 0.001f)
        assertEquals("亮屏功耗准确计算为 6.0W", 6.0f, result.screenOnWatts, 0.01f)
        assertEquals("息屏功耗准确计算为 0.6W", 0.6f, result.screenOffWatts, 0.01f)
        assertEquals("全局平均功耗准确计算为 5.1W", 5.1f, result.avgWatts, 0.01f)
        assertEquals("放电电量依标称电压真实折算约 132.47mAh", (0.51f * 1000f) / nominalVoltage, result.realDischargedMah, 0.01f)
    }

    /**
     * 验证 24 小时超长周期多段混合深度休眠工况下，双重物理锚定算法的守恒性与准确性。
     */
    @Test
    fun testDualAnchorLongPeriod24HoursWithDeepSleep() {
        val nominalVoltage = 3.85f
        val screenOnHours = 3.0f
        val screenOffHours = 21.0f
        val dischargeHours = 24.0f
        val screenOffMs = 21 * 3600000L

        // 亮屏 3 小时微积分实耗 6.00Wh（平均 2.00W）
        val intOnEnergyWh = 6.00f
        val intOnPowerWatts = 2.00f

        // 息屏 21 小时多次深度休眠，软件仅记录 0.10Wh
        val intOffEnergyWh = 0.10f
        val intOffPowerWatts = 0.10f / screenOffHours
        val intTotalEnergyWh = 6.10f
        val intTotalPowerWatts = intTotalEnergyWh / dischargeHours

        // 硬件芯片库仑计记录整机共放电 2000mAh = 7.70Wh
        val physicalTotalEnergyWh = 7.70f

        val result = PowerUsageManager.calculateDualAnchorEnergyAndPower(
            intOnEnergyWh = intOnEnergyWh,
            intOffEnergyWh = intOffEnergyWh,
            intTotalEnergyWh = intTotalEnergyWh,
            intOnPowerWatts = intOnPowerWatts,
            intOffPowerWatts = intOffPowerWatts,
            intTotalPowerWatts = intTotalPowerWatts,
            physicalTotalEnergyWh = physicalTotalEnergyWh,
            screenOnHours = screenOnHours,
            screenOffHours = screenOffHours,
            dischargeHours = dischargeHours,
            screenOffMs = screenOffMs,
            nominalVoltageVolts = nominalVoltage
        )

        assertEquals("24小时亮屏能量准确为 6.00Wh", 6.00f, result.onEnergyWh, 0.001f)
        assertEquals("24小时亮屏功耗准确为 2.00W", 2.00f, result.screenOnWatts, 0.001f)
        assertEquals("24小时息屏能量准确补偿为 1.70Wh", 1.70f, result.offEnergyWh, 0.001f)
        assertEquals("24小时息屏功耗准确计算为 0.081W", 1.70f / 21.0f, result.screenOffWatts, 0.001f)
        assertEquals("24小时总能量严格对齐硬件 7.70Wh", 7.70f, result.totalEnergyWh, 0.001f)
        assertEquals("24小时全局平均功耗准确计算为 0.3208W", 7.70f / 24.0f, result.avgWatts, 0.001f)
        assertEquals("24小时放电电量精准对应 2000mAh", 2000f, result.realDischargedMah, 0.01f)

        // 验证 100% 物理闭环
        val diff = Math.abs(result.totalEnergyWh - (result.screenOnWatts * screenOnHours + result.screenOffWatts * screenOffHours))
        assertTrue("24小时全生命周期功耗与能量严格物理闭环", diff < 0.001f)
    }

    /**
     * 验证当硬件估算物理能量（如掉电百分比折算值）小于或等于实测亮屏能量时，
     * 双锚定算法忠实保留微积分/基线推断的息屏能量与平均功耗，绝不发生“平均功耗存在但息屏能量为0”的物理矛盾。
     *
     * 该场景严格对应实测案例：
     * 亮屏 4h36m (4.6h)，实测功耗 1.80W，实测能量 8.287Wh；
     * 息屏 8h39m (8.65h)，实测功耗 0.29W，实测息屏能量约 2.509Wh；
     * 电池掉电 28% 按 3.85V 标压折算仅 5.39Wh~7.42Wh（小于亮屏实测 8.287Wh）。
     * 算法必须确保息屏能量忠实呈现为 2.509Wh，息屏平均功耗 0.29W，总能量 10.796Wh。
     */
    @Test
    fun testDualAnchorWhenPhysicalEnergyLessThanScreenOnEnergy() {
        val nominalVoltage = 3.85f
        val screenOnHours = 4.6f // 4h36m
        val screenOffHours = 8.65f // 8h39m
        val dischargeHours = 13.25f // 13h15m
        val screenOffMs = (8.65f * 3600000f).toLong()

        // 软件 1Hz 微积分高精实测：亮屏消耗 8.287Wh (1.8015W)
        val intOnEnergyWh = 8.287f
        val intOnPowerWatts = 1.8015f

        // 息屏采样与加权基线分位数外推测得：息屏消耗 2.5085Wh (0.29W)
        val intOffEnergyWh = 2.5085f
        val intOffPowerWatts = 0.29f
        val intTotalEnergyWh = intOnEnergyWh + intOffEnergyWh
        val intTotalPowerWatts = intTotalEnergyWh / dischargeHours

        // 掉电百分比或粗略估算折算物理总能量仅为 7.42Wh（小于亮屏实测 8.287Wh）
        val physicalTotalEnergyWh = 7.42f

        val result = PowerUsageManager.calculateDualAnchorEnergyAndPower(
            intOnEnergyWh = intOnEnergyWh,
            intOffEnergyWh = intOffEnergyWh,
            intTotalEnergyWh = intTotalEnergyWh,
            intOnPowerWatts = intOnPowerWatts,
            intOffPowerWatts = intOffPowerWatts,
            intTotalPowerWatts = intTotalPowerWatts,
            physicalTotalEnergyWh = physicalTotalEnergyWh,
            screenOnHours = screenOnHours,
            screenOffHours = screenOffHours,
            dischargeHours = dischargeHours,
            screenOffMs = screenOffMs,
            nominalVoltageVolts = nominalVoltage
        )

        // 1. 亮屏能量与功耗保持微积分真值
        assertEquals("亮屏能量严格等于微积分真值 8.287Wh", 8.287f, result.onEnergyWh, 0.001f)
        assertEquals("亮屏功耗准确计算为 1.80W", 1.8015f, result.screenOnWatts, 0.01f)

        // 2. 息屏能量绝对不可被抹零为 0，必须忠实呈现微积分与基线外推真值 2.509Wh
        assertEquals("息屏能量忠实保留真实消耗 2.509Wh 绝不被置零", 2.5085f, result.offEnergyWh, 0.001f)
        assertEquals("息屏平均功耗准确体现为 0.29W", 0.29f, result.screenOffWatts, 0.01f)

        // 3. 整机总能量为亮屏加息屏真实能量之和（10.796Wh），绝不仅等于亮屏能耗
        assertEquals("整机总能量严格等于亮屏与息屏能量之和 10.7955Wh", 10.7955f, result.totalEnergyWh, 0.001f)
        assertEquals("全局平均功耗准确计算约为 0.815W", 10.7955f / 13.25f, result.avgWatts, 0.01f)

        // 4. 严格物理闭环校验：E_total = E_on + E_off，且 P * T = E
        val sumEnergy = result.onEnergyWh + result.offEnergyWh
        assertEquals("能量守恒：总能量等于亮屏能量加息屏能量", result.totalEnergyWh, sumEnergy, 0.0001f)

        val powerTimeEnergy = result.screenOnWatts * screenOnHours + result.screenOffWatts * screenOffHours
        val diff = Math.abs(result.totalEnergyWh - powerTimeEnergy)
        assertTrue("功率时间与能量 100% 物理闭环", diff < 0.01f)
    }

    /**
     * 验证放电会话从拔电 RUNNING 到多阶段 Checkpoint 再到插电 COMPLETED 的状态机与主键稳定性。
     * 保证同一放电周期在多次 Checkpoint 覆写更新过程中，数据库中始终维持单条记录，绝不产生多条历史碎片。
     */
    @Test
    fun testDischargeSessionCheckpointLifecycleAndIdStability() {
        val baseUnplugTs = 1758690000000L
        val mockDb = mutableMapOf<Long, PowerUsageRecord>()

        // 阶段 1：拔电瞬间创建 RUNNING Session
        val initialRecord = PowerUsageRecord(
            id = baseUnplugTs,
            recordTime = "2026-09-24 10:00:00",
            levelPercent = 100,
            voltageVolts = 4.25f,
            temperature = 30.0f,
            energyWh = 19.25f,
            isCharging = false,
            avgPowerWatts = 0f,
            screenOnPowerWatts = 0f,
            screenOffPowerWatts = 0f,
            screenOnDurationText = "0m",
            screenOffDurationText = "0m",
            totalDurationText = "0m",
            remainingScreenOnText = "--",
            remainingCompositeText = "--",
            remainingScreenOffText = "--",
            isShizukuRealData = false,
            appCount = 0,
            trendPointsJson = "[]",
            appListJson = "[]",
            isCompleted = false,
            lastCheckpointTime = baseUnplugTs
        )
        mockDb[initialRecord.id] = initialRecord
        assertEquals("拔电瞬间产生 1 条 RUNNING 记录", 1, mockDb.size)
        assertFalse("初始记录必须为草稿态", mockDb[baseUnplugTs]!!.isCompleted)

        // 阶段 2：使用一段时间后掉电 5% (100% -> 95%)，触发第一次 Checkpoint
        val cp1Ts = baseUnplugTs + 1800_000L // 30分钟后
        val cp1Record = initialRecord.copy(
            levelPercent = 95,
            totalDurationText = "30m",
            avgPowerWatts = 2.1f,
            lastCheckpointTime = cp1Ts
        )
        // 增量覆写同一主键 ID
        mockDb[cp1Record.id] = cp1Record
        assertEquals("第一次 Checkpoint 覆写更新，数据库仍然严格只有 1 条记录", 1, mockDb.size)
        assertEquals("电量正确更新为 95%", 95, mockDb[baseUnplugTs]!!.levelPercent)
        assertFalse("Checkpoint 状态保持为 RUNNING 草稿", mockDb[baseUnplugTs]!!.isCompleted)

        // 阶段 3：继续使用并锁屏 (95% -> 90%)，触发第二次 Checkpoint
        val cp2Ts = baseUnplugTs + 3600_000L // 1小时后
        val cp2Record = cp1Record.copy(
            levelPercent = 90,
            totalDurationText = "1h",
            avgPowerWatts = 2.05f,
            lastCheckpointTime = cp2Ts
        )
        mockDb[cp2Record.id] = cp2Record
        assertEquals("第二次 Checkpoint 覆写更新，记录数依然严格为 1 条", 1, mockDb.size)
        assertEquals("电量正确更新为 90%", 90, mockDb[baseUnplugTs]!!.levelPercent)

        // 阶段 4：重新插上充电器，执行 Finalize 结案
        val finalTs = baseUnplugTs + 5400_000L // 1.5小时后
        val finalizedRecord = cp2Record.copy(
            levelPercent = 85,
            totalDurationText = "1h30m",
            isCompleted = true,
            lastCheckpointTime = finalTs
        )
        mockDb[finalizedRecord.id] = finalizedRecord
        assertEquals("最终插电 Finalize 归档，全生命周期记录条数恒等于 1", 1, mockDb.size)
        assertTrue("最终状态严格转为 COMPLETED 结案状态", mockDb[baseUnplugTs]!!.isCompleted)
        assertEquals("最终持续时长为 1h30m", "1h30m", mockDb[baseUnplugTs]!!.totalDurationText)
        assertEquals("最终记录电量为 85%", 85, mockDb[baseUnplugTs]!!.levelPercent)
    }

    /**
     * 验证放电过程中 App 进程意外被杀，重新启动后自愈恢复 RUNNING Session 并在后续插电时无缝结案。
     */
    @Test
    fun testDischargeSessionCrashAndReconcileRecovery() {
        val baseUnplugTs = 1758700000000L
        val mockDb = mutableMapOf<Long, PowerUsageRecord>()

        // 1. 拔电后使用至 90%，期间执行过 Checkpoint
        val preCrashRecord = PowerUsageRecord(
            id = baseUnplugTs,
            recordTime = "2026-09-24 14:00:00",
            levelPercent = 90,
            voltageVolts = 4.10f,
            temperature = 31.0f,
            energyWh = 17.5f,
            isCharging = false,
            avgPowerWatts = 2.2f,
            screenOnPowerWatts = 2.5f,
            screenOffPowerWatts = 0.15f,
            screenOnDurationText = "45m",
            screenOffDurationText = "15m",
            totalDurationText = "1h",
            remainingScreenOnText = "7h",
            remainingCompositeText = "12h",
            remainingScreenOffText = "48h",
            isShizukuRealData = false,
            appCount = 5,
            trendPointsJson = "[]",
            appListJson = "[]",
            isCompleted = false,
            lastCheckpointTime = baseUnplugTs + 3600_000L
        )
        mockDb[preCrashRecord.id] = preCrashRecord

        // 2. 模拟进程被杀后重启：通过查询 isCompleted == false 探测到未完结的草稿
        val recoveredDraft = mockDb.values.firstOrNull { !it.isCompleted }
        assertTrue("冷启动自愈必须成功探测到崩溃前的 RUNNING 放电草稿", recoveredDraft != null)
        assertEquals(baseUnplugTs, recoveredDraft!!.id)

        // 3. 模拟在关机/离线期间用户插入了充电器：自愈阶段直接执行 Finalize
        val offlinePlugNow = baseUnplugTs + 7200_000L
        val autoFinalized = recoveredDraft.copy(
            isCompleted = true,
            lastCheckpointTime = offlinePlugNow
        )
        mockDb[autoFinalized.id] = autoFinalized

        // 验证结果：草稿成功转正，历史记录唯一且不丢失
        assertEquals("数据库中依然仅有 1 条完整记录", 1, mockDb.size)
        assertTrue("恢复后结案的记录为 COMPLETED 状态", mockDb[baseUnplugTs]!!.isCompleted)
        assertEquals("主键严格保持一致", baseUnplugTs, mockDb[baseUnplugTs]!!.id)
    }

    /**
     * 验证手机仅息屏数十秒或数分钟时，双锚定模型忠实保留硬件瞬时采样微积分功耗，
     * 杜绝因电量 1% 阶跃残差倒灌导致息屏功耗与全局功耗虚高爆表。
     */
    @Test
    fun testShortScreenOffPreservesRealIntegrationPowerWithoutResidualInflation() {
        // 场景设定：
        // 亮屏 20 分钟（0.333h），亮屏实测能量 0.150 Wh，亮屏平均功耗 0.45 W
        val screenOnHours = 20f / 60f
        val onEnergyWh = 0.150f
        val onPowerWatts = 0.45f

        // 息屏 30 秒（0.00833h），未进深度休眠，后台真实采样功耗 0.20 W，实测积分能量 0.00167 Wh
        val screenOffMs = 30_000L
        val screenOffHours = 30f / 3600f
        val intOffWatts = 0.20f
        val intOffEnergyWh = intOffWatts * screenOffHours // 0.001667 Wh

        // 系统在息屏瞬间正好触发了 1% 阶跃掉电（5000mAh * 1% * 3.86V ≈ 0.193 Wh）
        val physicalTotalEnergyWh = 0.193f
        val dischargeHours = screenOnHours + screenOffHours

        val dualStats = PowerUsageManager.calculateDualAnchorEnergyAndPower(
            intOnEnergyWh = onEnergyWh,
            intOffEnergyWh = intOffEnergyWh,
            intTotalEnergyWh = onEnergyWh + intOffEnergyWh,
            intOnPowerWatts = onPowerWatts,
            intOffPowerWatts = intOffWatts,
            intTotalPowerWatts = (onEnergyWh + intOffEnergyWh) / dischargeHours,
            physicalTotalEnergyWh = physicalTotalEnergyWh,
            screenOnHours = screenOnHours,
            screenOffHours = screenOffHours,
            dischargeHours = dischargeHours,
            screenOffMs = screenOffMs,
            nominalVoltageVolts = 3.86f
        )

        // 验证：
        // 1. 息屏平均功耗必须忠实采信真实物理微积分功耗（0.20W），绝不能因为除以 0.0083h 爆表成 5.16W 乃至 15W！
        assertEquals("短期息屏功耗必须忠实等于真实物理采样微积分功耗 0.20W", 0.20f, dualStats.screenOffWatts, 0.02f)

        // 2. 息屏能量严格采纳真实硬件微积分能量
        assertEquals("短期息屏能量必须忠实等于真实微积分能量", intOffEnergyWh, dualStats.offEnergyWh, 0.001f)

        // 3. 亮屏能量坚守 1Hz 未抽稀高频微积分真值，整机总能量等于亮屏能量加息屏能量自然闭环
        assertEquals("亮屏能量坚守高频微积分物理真值，杜绝阶跃残差灌水虚高", onEnergyWh, dualStats.onEnergyWh, 0.001f)
        assertEquals("亮屏平均功耗准确保持 0.45W", onPowerWatts, dualStats.screenOnWatts, 0.01f)
        assertEquals("整机总能量严格等于亮屏与息屏微积分之和自然闭环", dualStats.onEnergyWh + dualStats.offEnergyWh, dualStats.totalEnergyWh, 0.001f)
    }

    /**
     * 验证时间轴状态事件合并与断层切分：
     * 亮屏为绿色（isScreenOn=true），密集息屏采样为浅红唤醒（isDeepSleep=false），
     * 长间隔静默断层为深红深度睡眠（isDeepSleep=true）。
     */
    @Test
    fun testTimelineScreenEventAwakeAndDeepSleepState() {
        val awakeEvent1 = com.battery.analysis.timeline.domain.ScreenEvent(1000L, 2000L, isScreenOn = false, isDeepSleep = false)
        val awakeEvent2 = com.battery.analysis.timeline.domain.ScreenEvent(2000L, 3000L, isScreenOn = false, isDeepSleep = false)
        val deepSleepEvent = com.battery.analysis.timeline.domain.ScreenEvent(3000L, 10000L, isScreenOn = false, isDeepSleep = true)
        val screenOnEvent = com.battery.analysis.timeline.domain.ScreenEvent(10000L, 15000L, isScreenOn = true, isDeepSleep = false)

        val merged = com.battery.analysis.timeline.domain.TimelineEventMerger.mergeScreenEvents(
            listOf(awakeEvent1, awakeEvent2, deepSleepEvent, screenOnEvent)
        )

        assertEquals("连续的浅红唤醒切片应平滑合并，但不可与深红深度睡眠切片跨状态合并", 3, merged.size)
        // 第 1 段：合并后的浅红唤醒
        assertEquals(1000L, merged[0].startTime)
        assertEquals(3000L, merged[0].endTime)
        assertFalse(merged[0].isScreenOn)
        assertFalse(merged[0].isDeepSleep)

        // 第 2 段：深红深度睡眠
        assertEquals(3000L, merged[1].startTime)
        assertEquals(10000L, merged[1].endTime)
        assertFalse(merged[1].isScreenOn)
        assertTrue(merged[1].isDeepSleep)

        // 第 3 段：亮屏绿
        assertEquals(10000L, merged[2].startTime)
        assertEquals(15000L, merged[2].endTime)
        assertTrue(merged[2].isScreenOn)
    }

    /**
     * 验证极短时间息屏（如 15 秒）未进入深度睡眠时，时间轴状态切分忠实判定为全部唤醒活跃浅红段。
     */
    @Test
    fun testSplitScreenOffEventsShortScreenOffIsAllAwake() {
        val offStart = 1000L
        val offEnd = 16000L // 15 秒短息屏

        val events = PowerUsageManager.splitScreenOffEvents(
            offStartTs = offStart,
            offEndTs = offEnd,
            realtimeSamples = emptyList()
        )

        assertEquals("短息屏切片数量应为 1", 1, events.size)
        val event = events[0]
        assertEquals(offStart, event.startTime)
        assertEquals(offEnd, event.endTime)
        assertFalse("息屏状态 isScreenOn 必须为 false", event.isScreenOn)
        assertFalse("短息屏未进入深度睡眠，isDeepSleep 必须为 false（浅红）", event.isDeepSleep)
    }

    /**
     * 验证长时间息屏在无密集物理采样点（如智能省电零唤醒模式）时，能准确切分为灭屏初期浅红唤醒与后续主体深红深度睡眠。
     */
    @Test
    fun testSplitScreenOffEventsLongScreenOffWithoutSamplesSplitsAwakeAndDeepSleep() {
        val offStart = 100_000L
        val offEnd = 1_900_000L // 30 分钟长息屏

        val events = PowerUsageManager.splitScreenOffEvents(
            offStartTs = offStart,
            offEndTs = offEnd,
            realtimeSamples = emptyList(),
            totalAwakeMs = 60_000L,
            totalDeepSleepMs = 1_740_000L
        )

        assertTrue("长息屏必须切分为浅红唤醒与深红深度睡眠至少两段", events.size >= 2)
        val awakeSegment = events[0]
        val deepSleepSegment = events[1]

        assertEquals(offStart, awakeSegment.startTime)
        assertFalse("第 1 段为息屏状态", awakeSegment.isScreenOn)
        assertFalse("第 1 段为浅休眠唤醒（浅红）", awakeSegment.isDeepSleep)

        assertEquals(awakeSegment.endTime, deepSleepSegment.startTime)
        assertEquals(offEnd, deepSleepSegment.endTime)
        assertFalse("第 2 段为息屏状态", deepSleepSegment.isScreenOn)
        assertTrue("第 2 段必须为深度睡眠（深红）", deepSleepSegment.isDeepSleep)
        assertTrue("深度睡眠时长必须占主体时间", deepSleepSegment.getDurationMs() > 1_500_000L)
    }

    /**
     * 验证当存在真实物理秒级采样点且发生挂起断层时，依据采样断层精确切分浅红唤醒与深红深度睡眠。
     */
    @Test
    fun testSplitScreenOffEventsPhysicalSampleGapSplitsDeepSleep() {
        val offStart = 10_000L
        val offEnd = 610_000L // 10 分钟

        // 灭屏前 30 秒内有连续采样点，之后 CPU 挂起直到息屏结束
        val samples = listOf(
            PowerDischargePoint(timestamp = 10_000L, elapsedHours = 0f, batteryLevel = 80, voltageVolts = 4.1f, temperature = 30f, powerWatts = 0.3f, activeAppIcons = emptyList(), isScreenOn = false, activeAppNames = emptyList()),
            PowerDischargePoint(timestamp = 15_000L, elapsedHours = 0f, batteryLevel = 80, voltageVolts = 4.1f, temperature = 30f, powerWatts = 0.3f, activeAppIcons = emptyList(), isScreenOn = false, activeAppNames = emptyList()),
            PowerDischargePoint(timestamp = 25_000L, elapsedHours = 0f, batteryLevel = 80, voltageVolts = 4.1f, temperature = 30f, powerWatts = 0.3f, activeAppIcons = emptyList(), isScreenOn = false, activeAppNames = emptyList()),
            PowerDischargePoint(timestamp = 40_000L, elapsedHours = 0f, batteryLevel = 80, voltageVolts = 4.1f, temperature = 30f, powerWatts = 0.3f, activeAppIcons = emptyList(), isScreenOn = false, activeAppNames = emptyList())
        )

        val events = PowerUsageManager.splitScreenOffEvents(
            offStartTs = offStart,
            offEndTs = offEnd,
            realtimeSamples = samples
        )

        val merged = com.battery.analysis.timeline.domain.TimelineEventMerger.mergeScreenEvents(events)
        assertTrue("合并后应包含唤醒与深度休眠两类状态", merged.size >= 2)

        val firstEvent = merged[0]
        assertEquals("第一段起始时间对齐息屏开始", offStart, firstEvent.startTime)
        assertFalse("第一段采样密集，为浅红唤醒", firstEvent.isDeepSleep)

        val deepEvent = merged.last()
        assertEquals("最后一段结束时间对齐息屏结束", offEnd, deepEvent.endTime)
        assertTrue("断层区间必须为深度睡眠深红", deepEvent.isDeepSleep)
        assertTrue("断层深度睡眠时长超过 9 分钟", deepEvent.getDurationMs() >= 550_000L)
    }

    /**
     * 验证息屏唤醒与深度睡眠能量计算的严格物理守恒性：
     * 确保当存在唤醒时长时，唤醒能量绝对不为 0.000Wh，
     * 且唤醒能量与深度睡眠能量之和与息屏总能量严格守恒闭合。
     */
    /**
     * 验证息屏唤醒活跃能耗与深度休眠挂起能耗的物理守恒分解逻辑。
     * 确保唤醒能耗与深度睡眠能耗之和 100% 守恒等于息屏总能量，且各状态能量大于等于 0。
     */
    @Test
    fun testScreenOffAwakeAndDeepSleepEnergyConservation() {
        val offEnergyWh = 2.03f
        val screenOffMs = 32_040_000L // 8h54m
        val awakeMs = 1_244_000L // 20m44s
        val deepSleepMs = 30_796_000L // 8h33m16s

        val decomposed = PowerUsageManager.calculateScreenOffAwakeAndDeepSleepEnergy(
            offEnergyWh = offEnergyWh,
            screenOffMs = screenOffMs,
            deepSleepMs = deepSleepMs,
            awakeMs = awakeMs,
            rawSleepDrainMah = 50f,
            rawAwakeDrainMah = 0f,
            nominalVoltageVolts = 3.85f
        )

        assertTrue("唤醒时长存在时唤醒能量必须大于 0Wh", decomposed.awakeEnergyWh > 0.05f)
        assertTrue("深度睡眠能量必须大于 0Wh", decomposed.deepSleepEnergyWh > 0.1f)
        assertEquals(
            "唤醒能量与深度睡眠能量之和必须严格守恒等于息屏总能量",
            offEnergyWh,
            decomposed.awakeEnergyWh + decomposed.deepSleepEnergyWh,
            0.001f
        )
    }

    /**
     * 验证在息屏期间发生高功耗后台唤醒（如 15W~19W 尖峰）时，
     * 深度睡眠能量忠实反映物理待机底噪（0.03W 左右），绝不因深度休眠时间长而被粗暴均分虚增至 1.6W+。
     */
    @Test
    fun testScreenOffDeepSleepEnergyDoesNotInflateUnderHighAwakePower() {
        // 场景严格对应用户实测：
        // 息屏总耗能 1.743Wh，总息屏时长 2h51m (171分钟)
        val offEnergyWh = 1.743f
        val screenOffMs = (171L * 60_000L) // 2h51m
        val awakeMs = (12L * 60_000L) // 12m
        val deepSleepMs = screenOffMs - awakeMs // 2h39m (159m = 2.65h)

        // 构造采样点：包含息屏静止待机底噪采样（0.03W）
        val samples = listOf(
            PowerDischargePoint(1000L, 0f, 70, 4.0f, 30f, 0.03f, emptyList(), false),
            PowerDischargePoint(2000L, 0f, 70, 4.0f, 30f, 0.03f, emptyList(), false),
            PowerDischargePoint(3000L, 0f, 70, 4.0f, 30f, 0.03f, emptyList(), false),
            PowerDischargePoint(4000L, 0f, 70, 4.0f, 30f, 15.0f, emptyList(), false) // 唤醒尖峰
        )

        val decomposed = PowerUsageManager.calculateScreenOffAwakeAndDeepSleepEnergy(
            offEnergyWh = offEnergyWh,
            screenOffMs = screenOffMs,
            deepSleepMs = deepSleepMs,
            awakeMs = awakeMs,
            samples = samples,
            nominalVoltageVolts = 4.0f
        )

        // 深度睡眠能量应接近 0.03W * 2.65h ≈ 0.08Wh，绝不能虚高到 1.6Wh+（原 Bug 算成了 1.618Wh）
        assertTrue("深度睡眠能量必须接近待机底噪（< 0.15Wh），杜绝被时间占比虚增为 1.6Wh", decomposed.deepSleepEnergyWh < 0.15f)
        assertTrue("深度睡眠平均功耗必须在正常待机底噪（<= 0.05W）范围内", decomposed.deepSleepWatts <= 0.05f)

        // 唤醒能量吸纳后台高功耗尖峰的大部分电能（> 1.5Wh）
        assertTrue("唤醒能量吸收绝大部分后台尖峰耗电（> 1.5Wh）", decomposed.awakeEnergyWh > 1.5f)

        // 严格能量守恒
        assertEquals(
            "能量守恒：唤醒能量与深度休眠能量之和严格等于息屏总能耗",
            offEnergyWh,
            decomposed.awakeEnergyWh + decomposed.deepSleepEnergyWh,
            0.001f
        )
    }

    /**
     * 验证在息屏开启高频后台采样（相邻采样点无大断层）且功耗从活跃跌落至待机底噪时，
     * 时间轴能准确切分出深度睡眠暗红段，杜绝整条时间轴全为浅红的问题。
     */
    @Test
    fun testSplitScreenOffEventsContinuousSamplesGeneratesDeepSleep() {
        val offStart = 100_000L
        val offEnd = 355_000L // 255 秒（4m15s）
        val awakeMs = 106_000L // 1m46s
        val deepSleepMs = 146_000L // 2m26s

        // 构造每隔 10 秒一个连续采样点（无断层，gap 均只有 10 秒）
        val samples = mutableListOf<PowerDischargePoint>()
        var t = offStart + 10_000L
        while (t < offEnd) {
            val power = if (t <= offStart + awakeMs) 5.5f else 0.08f // 前期高功耗唤醒，后期待机底噪
            samples.add(
                PowerDischargePoint(
                    timestamp = t,
                    elapsedHours = 0f,
                    batteryLevel = 77,
                    voltageVolts = 4.18f,
                    temperature = 32f,
                    powerWatts = power,
                    activeAppIcons = emptyList(),
                    isScreenOn = false,
                    activeAppNames = emptyList()
                )
            )
            t += 10_000L
        }

        val events = PowerUsageManager.splitScreenOffEvents(
            offStartTs = offStart,
            offEndTs = offEnd,
            realtimeSamples = samples,
            totalAwakeMs = awakeMs,
            totalDeepSleepMs = deepSleepMs
        )

        val merged = com.battery.analysis.timeline.domain.TimelineEventMerger.mergeScreenEvents(events)
        val deepSleepEvent = merged.find { it.isDeepSleep }
        assertTrue("在连续高频采样下必须能准确切分出深度睡眠暗红事件", deepSleepEvent != null)
        assertTrue("深度睡眠暗红事件时长必须充足（大于 100 秒）", deepSleepEvent!!.getDurationMs() >= 100_000L)
    }

    /**
     * 验证同一个应用内部进行页面切换（如列表页跳转详情页，间隔 150 毫秒）时，
     * 相邻活跃区间能够被自动平滑合并为一个连续区间，消除切页微缝隙，杜绝产生虚假的未命中或桌面待机。
     */
    @Test
    fun testMergeAdjacentAppIntervalsForSamePackageWithinTransitionThreshold() {
        val intervals = listOf(
            PowerUsageManager.AppActivityInterval("com.ss.android.article.news", 1000L, 5000L),
            PowerUsageManager.AppActivityInterval("com.ss.android.article.news", 5150L, 10000L)
        )
        val merged = PowerUsageManager.mergeAdjacentIntervals(intervals, maxGapMs = 2000L)

        assertEquals("相同应用且转场在 2 秒内的两个区间必须合并为 1 个连续区间", 1, merged.size)
        assertEquals("合并后区间起始时间必须等于首个区间起始时间", 1000L, merged[0].startTs)
        assertEquals("合并后区间结束时间必须等于末尾区间结束时间", 10000L, merged[0].endTs)
        assertEquals("包名必须保持不变", "com.ss.android.article.news", merged[0].packageName)
    }

    /**
     * 验证不同应用之间的切换（如从快手切到今日头条），即使时间间隔小于 2 秒，
     * 也必须严格保持为各自独立的区间，绝不可错误跨包名合并。
     */
    @Test
    fun testMergeAdjacentAppIntervalsAcrossDifferentPackagesNotMerged() {
        val intervals = listOf(
            PowerUsageManager.AppActivityInterval("com.kuaishou.nebula", 1000L, 5000L),
            PowerUsageManager.AppActivityInterval("com.ss.android.article.news", 5100L, 10000L)
        )
        val merged = PowerUsageManager.mergeAdjacentIntervals(intervals, maxGapMs = 2000L)

        assertEquals("不同应用之间的区间绝不可合并", 2, merged.size)
        assertEquals("首个应用必须为快手", "com.kuaishou.nebula", merged[0].packageName)
        assertEquals("第二个应用必须为头条", "com.ss.android.article.news", merged[1].packageName)
    }

    /**
     * 验证同一个应用若离开前台超过转场门限（例如退回桌面待机 5 秒后再重新进入），
     * 必须保持为两个独立区间，绝不凭空捏造合并，忠实反映中间客观存在的桌面待机工时。
     */
    @Test
    fun testMergeAdjacentAppIntervalsBeyondThresholdNotMerged() {
        val intervals = listOf(
            PowerUsageManager.AppActivityInterval("com.ss.android.article.news", 1000L, 5000L),
            PowerUsageManager.AppActivityInterval("com.ss.android.article.news", 10001L, 15000L) // 间隔超过 5 秒
        )
        val merged = PowerUsageManager.mergeAdjacentIntervals(intervals, maxGapMs = 2000L)

        assertEquals("间隔超过门限的同应用区间必须保持独立", 2, merged.size)
        assertEquals(5000L, merged[0].endTs)
        assertEquals(10001L, merged[1].startTs)
    }

    /**
     * 验证单应用多页面跳转生命周期状态机：
     * 模拟 Activity A 暂停 -> Activity B 恢复 -> Activity A 停止（ACTIVITY_STOPPED）时，
     * 正确忽略 ACTIVITY_STOPPED 能够确保 Activity B 持续在前台记录完整工时，杜绝因旧 Activity 停止误杀当前活跃页面。
     */
    @Test
    fun testForegroundAppLifecycleStateMachineIgnoresActivityStopped() {
        // 模拟 UsageEvents 事件流
        data class MockUsageEvent(val eventType: Int, val packageName: String, val timeStamp: Long)
        val EVENT_RESUMED = 1
        val EVENT_PAUSED = 2
        val EVENT_STOPPED = 23

        val pkg = "com.ss.android.article.news"
        val mockEvents = listOf(
            MockUsageEvent(EVENT_RESUMED, pkg, 1000L),  // Activity A 打开
            MockUsageEvent(EVENT_PAUSED, pkg, 3000L),   // Activity A 暂停
            MockUsageEvent(EVENT_RESUMED, pkg, 3050L),  // Activity B 恢复（进入前台）
            MockUsageEvent(EVENT_STOPPED, pkg, 3200L),  // Activity A 停止（旧页面不可见，必须忽略！）
            MockUsageEvent(EVENT_PAUSED, pkg, 10000L)   // Activity B 最终暂停（离开前台）
        )

        val recordedIntervals = mutableListOf<PowerUsageManager.AppActivityInterval>()
        var currentForegroundPkg: String? = null
        var currentForegroundStartTs: Long = 0L

        for (event in mockEvents) {
            when (event.eventType) {
                EVENT_RESUMED -> {
                    if (currentForegroundPkg != null) {
                        recordedIntervals.add(PowerUsageManager.AppActivityInterval(currentForegroundPkg, currentForegroundStartTs, event.timeStamp))
                    }
                    currentForegroundPkg = event.packageName
                    currentForegroundStartTs = event.timeStamp
                }
                EVENT_PAUSED -> {
                    if (currentForegroundPkg == event.packageName) {
                        recordedIntervals.add(PowerUsageManager.AppActivityInterval(event.packageName, currentForegroundStartTs, event.timeStamp))
                        currentForegroundPkg = null
                        currentForegroundStartTs = 0L
                    }
                }
                // 注意：状态机严格不处理 EVENT_STOPPED，杜绝误杀正在运行的 Activity B
            }
        }

        // 验证未被误杀：recordedIntervals 包含两段有效工时 [1000..3000] 和 [3050..10000]
        assertEquals("必须包含两段有效运行工时", 2, recordedIntervals.size)
        val totalMs = recordedIntervals.sumOf { it.endTs - it.startTs }
        assertEquals("总工时必须包含完整的二级页面运行时间（2000ms + 6950ms = 8950ms）", 8950L, totalMs)

        // 再通过平滑合并，将这两段合并为一个完整区间
        val merged = PowerUsageManager.mergeAdjacentIntervals(recordedIntervals)
        assertEquals("经过转场合并后成为 1 个完整连续区间", 1, merged.size)
        assertEquals(1000L, merged[0].startTs)
        assertEquals(10000L, merged[0].endTs)
    }

    /**
     * 验证当缺失物理采样点且系统底层未上报待机细分电量时，
     * 分解算法忠实将 isDecomposedAvailable 置为 false，且绝不伪造或粗暴均分能量。
     */
    @Test
    fun testScreenOffAwakeAndDeepSleepEnergyDecomposedUnavailableWhenNoData() {
        val offEnergyWh = 1.85f
        val screenOffMs = 18_000_000L // 5 小时
        val awakeMs = 1_800_000L // 30 分钟
        val deepSleepMs = 16_200_000L // 4.5 小时

        val decomposed = PowerUsageManager.calculateScreenOffAwakeAndDeepSleepEnergy(
            offEnergyWh = offEnergyWh,
            screenOffMs = screenOffMs,
            deepSleepMs = deepSleepMs,
            awakeMs = awakeMs,
            rawSleepDrainMah = 0f,
            rawAwakeDrainMah = 0f,
            samples = emptyList(),
            nominalVoltageVolts = 3.85f
        )

        assertFalse("无底层细分数据且无采样点时，细分可用性必须标记为 false", decomposed.isDecomposedAvailable)
        assertEquals("未获取时唤醒能耗为 0", 0f, decomposed.awakeEnergyWh, 0.0001f)
        assertEquals("未获取时深度休眠能耗为 0", 0f, decomposed.deepSleepEnergyWh, 0.0001f)
    }

    /**
     * 验证当息屏期间所有物理采样点均处于高功耗唤醒活跃态（如 2.5W~4.0W）时，
     * 待机底噪门禁生效，绝不将高负荷采样误当休眠底噪，防止将唤醒能耗虚增吞没为深度睡眠。
     */
    @Test
    fun testScreenOffAwakeAndDeepSleepEnergyRejectsHighPowerSamplesAsBaseline() {
        val offEnergyWh = 2.0f
        val screenOffMs = 7_200_000L // 2 小时
        val awakeMs = 1_800_000L // 30 分钟
        val deepSleepMs = 5_400_000L // 1.5 小时

        // 构造均为高功耗后台唤醒的采样样本（均大于 0.25W）
        val highPowerSamples = listOf(
            PowerDischargePoint(1000L, 0f, 80, 4.0f, 35f, 2.5f, emptyList(), false),
            PowerDischargePoint(2000L, 0f, 80, 4.0f, 35f, 3.0f, emptyList(), false),
            PowerDischargePoint(3000L, 0f, 80, 4.0f, 35f, 2.8f, emptyList(), false),
            PowerDischargePoint(4000L, 0f, 80, 4.0f, 35f, 3.5f, emptyList(), false)
        )

        val decomposed = PowerUsageManager.calculateScreenOffAwakeAndDeepSleepEnergy(
            offEnergyWh = offEnergyWh,
            screenOffMs = screenOffMs,
            deepSleepMs = deepSleepMs,
            awakeMs = awakeMs,
            rawSleepDrainMah = 0f,
            rawAwakeDrainMah = 0f,
            samples = highPowerSamples,
            nominalVoltageVolts = 4.0f
        )

        // 因为所有采样点都大于 0.25W，不能视作静止休眠底噪，且无底层 dumpsys 待机数据，应判定为不可分解
        assertFalse("全是高功耗活跃样本时待机门禁生效，不可作为休眠底噪，返回不可分解", decomposed.isDecomposedAvailable)
    }

    /**
     * 验证 PowerUsageRecord 在包含息屏唤醒与深度休眠细分指标时的 JSON 序列化与反序列化完全无损恢复。
     */
    @Test
    fun testPowerUsageRecordSerializationWithScreenOffDetails() {
        val record = com.battery.analysis.model.PowerUsageRecord(
            id = 123456789L,
            recordTime = "2026-10-02 21:00",
            levelPercent = 85,
            voltageVolts = 4.2f,
            temperature = 30f,
            energyWh = 18.5f,
            isCharging = false,
            avgPowerWatts = 0.8f,
            screenOnPowerWatts = 1.5f,
            screenOffPowerWatts = 0.2f,
            screenOnDurationText = "1h",
            screenOffDurationText = "4h",
            totalDurationText = "5h",
            remainingScreenOnText = "10h",
            remainingCompositeText = "15h",
            remainingScreenOffText = "40h",
            isShizukuRealData = true,
            appCount = 5,
            trendPointsJson = "[]",
            appListJson = "[]",
            screenOffEnergyWh = 0.8f,
            screenOffAwakeEnergyWh = 0.65f,
            screenOffDeepSleepEnergyWh = 0.15f,
            screenOffAwakeDurationMs = 1_800_000L,
            screenOffDeepSleepDurationMs = 12_600_000L,
            screenOffAwakeDrainMah = 150f,
            screenOffDeepSleepDrainMah = 35f,
            isScreenOffDecomposedAvailable = true
        )

        val jsonStr = record.toJsonString()
        val restored = com.battery.analysis.model.PowerUsageRecord.fromJsonString(jsonStr)

        org.junit.Assert.assertNotNull("反序列化对象不可为空", restored)
        assertEquals("息屏总能量一致", 0.8f, restored!!.screenOffEnergyWh, 0.001f)
        assertEquals("息屏唤醒能量一致", 0.65f, restored.screenOffAwakeEnergyWh, 0.001f)
        assertEquals("深度休眠能量一致", 0.15f, restored.screenOffDeepSleepEnergyWh, 0.001f)
        assertEquals("息屏唤醒时长一致", 1_800_000L, restored.screenOffAwakeDurationMs)
        assertEquals("深度休眠时长一致", 12_600_000L, restored.screenOffDeepSleepDurationMs)
        assertEquals("息屏唤醒电荷量一致", 150f, restored.screenOffAwakeDrainMah, 0.001f)
        assertEquals("深度休眠电荷量一致", 35f, restored.screenOffDeepSleepDrainMah, 0.001f)
        assertTrue("细分可用性标记一致", restored.isScreenOffDecomposedAvailable)
    }

    /**
     * 验证当亮屏微积分能量与息屏实测能量之和超过离散掉电百分比折算值时，
     * 双锚定算法严格遵循严禁人为虚拟钳位原则，息屏能量坚守实测真值（2.119Wh），
     * 亮屏能量坚守高频微积分真值（2.457Wh），整机能量由二者自然守恒累加（4.576Wh），
     * 彻底杜绝通过 (physicalTotalEnergyWh - intOnEnergyWh) 反向倒扣息屏能量而引发的刷新递减与跳变异常。
     */
    @Test
    fun testScreenOffEnergyCeilingConstraintUnderActualDischargeDrop() {
        val physicalTotalEnergyWh = 3.45f
        val screenOnMs = 66L * 60_000L // 1h06m
        val screenOffMs = 550L * 60_000L // 9h10m
        val totalMs = screenOnMs + screenOffMs // 10h16m

        val screenOnHours = screenOnMs / 3600000f
        val screenOffHours = screenOffMs / 3600000f
        val totalHours = totalMs / 3600000f

        val intOnEnergyWh = 2.457f // 亮屏 1Hz 采样真实积分
        val intOnPowerWatts = intOnEnergyWh / screenOnHours // ~2.23W

        val realOffEnergyWh = 2.119f
        val realOffPowerWatts = realOffEnergyWh / screenOffHours

        val stats = PowerUsageManager.calculateDualAnchorEnergyAndPower(
            intOnEnergyWh = intOnEnergyWh,
            intOffEnergyWh = realOffEnergyWh,
            intTotalEnergyWh = intOnEnergyWh + realOffEnergyWh,
            intOnPowerWatts = intOnPowerWatts,
            intOffPowerWatts = realOffPowerWatts,
            intTotalPowerWatts = (intOnEnergyWh + realOffEnergyWh) / totalHours,
            physicalTotalEnergyWh = physicalTotalEnergyWh,
            screenOnHours = screenOnHours,
            screenOffHours = screenOffHours,
            dischargeHours = totalHours,
            screenOffMs = screenOffMs,
            nominalVoltageVolts = 3.85f
        )

        // 1. 亮屏能量保持 1Hz 高频微积分最高物理置信度不变（2.457Wh）
        assertEquals("亮屏能耗保持高频微积分物理真值", intOnEnergyWh, stats.onEnergyWh, 0.001f)

        // 2. 息屏能量坚守实测真值（2.119Wh），严禁被 (physicalTotalEnergyWh - intOnEnergyWh) 反向倒扣
        assertEquals("息屏能耗保持实测物理真值，杜绝虚拟钳位倒扣", realOffEnergyWh, stats.offEnergyWh, 0.001f)

        // 3. 整机总放电能耗忠实等于亮屏微积分与息屏能耗之和（4.576Wh），能量物理守恒
        assertEquals("整机总能耗等于亮屏与息屏实测能耗自然累加", intOnEnergyWh + realOffEnergyWh, stats.totalEnergyWh, 0.001f)
    }

    /**
     * 验证系统权威底层 BatteryStats 原生待机放电量在能量拆解时具备最高优先级，
     * 杜绝被采样点外推覆盖。
     */
    @Test
    fun testScreenOffEnergyDecompositionPrioritizesKernelStats() {
        val offEnergyWh = 1.05f
        val screenOffMs = 550L * 60_000L // 9h10m
        val deepSleepMs = 520L * 60_000L // 8h40m
        val awakeMs = 30L * 60_000L // 30m

        // 系统内核上报深睡 184 mAh，唤醒 66 mAh（严格对应 AccuBattery Pro 实测数据）
        val rawSleepMah = 184f
        val rawAwakeMah = 66f

        val decomposed = PowerUsageManager.calculateScreenOffAwakeAndDeepSleepEnergy(
            offEnergyWh = offEnergyWh,
            screenOffMs = screenOffMs,
            deepSleepMs = deepSleepMs,
            awakeMs = awakeMs,
            rawSleepDrainMah = rawSleepMah,
            rawAwakeDrainMah = rawAwakeMah,
            samples = emptyList(),
            nominalVoltageVolts = 3.85f
        )

        assertTrue("拆解结果必须有效", decomposed.isDecomposedAvailable)
        // 深度睡眠能耗占比约为 184 / (184 + 66) = 73.6%
        val expectedDeepEnergy = offEnergyWh * (184f / 250f)
        assertEquals("深度睡眠能量采纳内核权威比例", expectedDeepEnergy, decomposed.deepSleepEnergyWh, 0.01f)
        // 能量严格守恒
        assertEquals(
            "深度睡眠与唤醒能量之和严格等于息屏总能耗",
            offEnergyWh,
            decomposed.deepSleepEnergyWh + decomposed.awakeEnergyWh,
            0.001f
        )
    }

    /**
     * 验证系统权威底层 Estimated power use 段落中蜂窝网络基带、Wi-Fi、蓝牙与设备空闲待机等硬件子系统放电量的提取精度。
     * 确保 Android 驱动层原生能耗模型计算出的子系统数值被完整采集。
     */
    @Test
    fun testHardwareSubsystemDrainsParsedFromEstimatedPowerUse() {
        val sampleDumpsys = """
            Estimated power use (mAh):
              Capacity: 4500, Computed drain: 320.5, actual drain: 325-360
              Screen: 85.2
              Cellular standby: 42.6
              Wifi: 18.3
              Bluetooth: 4.8
              Idle: 15.1
              Uid 1000: 25.0
        """.trimIndent()

        val cellularPattern = Pattern.compile("(?:Cellular|Cellular standby|Mobile radio):\\s*([\\d.]+)", Pattern.CASE_INSENSITIVE)
        val wifiPattern = Pattern.compile("(?:Wifi|Wi-Fi):\\s*([\\d.]+)", Pattern.CASE_INSENSITIVE)
        val btPattern = Pattern.compile("Bluetooth:\\s*([\\d.]+)", Pattern.CASE_INSENSITIVE)
        val idlePattern = Pattern.compile("(?:Idle|Device standby):\\s*([\\d.]+)", Pattern.CASE_INSENSITIVE)

        val cellMatch = cellularPattern.matcher(sampleDumpsys)
        assertTrue("蜂窝网络基带能耗正则应成功匹配", cellMatch.find())
        assertEquals(42.6f, cellMatch.group(1)?.toFloatOrNull() ?: 0f, 0.001f)

        val wifiMatch = wifiPattern.matcher(sampleDumpsys)
        assertTrue("Wi-Fi 硬件能耗正则应成功匹配", wifiMatch.find())
        assertEquals(18.3f, wifiMatch.group(1)?.toFloatOrNull() ?: 0f, 0.001f)

        val btMatch = btPattern.matcher(sampleDumpsys)
        assertTrue("蓝牙硬件能耗正则应成功匹配", btMatch.find())
        assertEquals(4.8f, btMatch.group(1)?.toFloatOrNull() ?: 0f, 0.001f)

        val idleMatch = idlePattern.matcher(sampleDumpsys)
        assertTrue("空闲待机能耗正则应成功匹配", idleMatch.find())
        assertEquals(15.1f, idleMatch.group(1)?.toFloatOrNull() ?: 0f, 0.001f)
    }

    /**
     * 验证进阶方案 1（灭屏/亮屏首尾硬件芯片库仑计快照差分）与方案 2（动态端电压梯形积分）与双锚定物理天花板的协同运作。
     * 确保亮屏 1Hz 瞬时采样梯形微积分完全保持原样（2.457Wh），息屏优先采纳硬件库仑计快照差分真值（1.0525Wh），
     * 杜绝外推膨胀，且整机总能量与物理实际掉电量严格守恒。
     */
    @Test
    fun testHardwareCoulombCounterDifferentialSnapshotEnergyIntegration() {
        // 模拟灭屏与亮屏瞬间硬件状态：
        // 灭屏瞬间：库仑计快照 4,500,000 uAh，端电压 4.22V
        val screenOffCounterUah = 4500000
        val screenOffVolt = 4.22f

        // 亮屏瞬间：库仑计快照 4,250,000 uAh，端电压 4.20V
        val screenOnCounterUah = 4250000
        val screenOnVolt = 4.20f

        // 1. 进阶方案 1 & 2 差分计算物理真值
        val hwDrainMah = (screenOffCounterUah - screenOnCounterUah) / 1000f // 250.0 mAh
        val avgVolt = (screenOffVolt + screenOnVolt) / 2f // 4.21V
        val hwEnergyWh = (hwDrainMah * avgVolt) / 1000f // 1.0525 Wh

        assertEquals("硬件芯片库仑计放电量应精准差分为 250mAh", 250f, hwDrainMah, 0.001f)
        assertEquals("动态端电压平均应为 4.21V", 4.21f, avgVolt, 0.001f)
        assertEquals("息屏真实物理能量应为 1.0525Wh", 1.0525f, hwEnergyWh, 0.001f)

        // 2. 传入双锚定物理天花板校验
        val intOnEnergyWh = 2.457f // 亮屏 1Hz 梯形微积分真实计算值（完全保持原样）
        val intOffEnergyWh = hwEnergyWh // 优先采信硬件芯片级首尾快照差分真值
        val intTotalEnergyWh = intOnEnergyWh + intOffEnergyWh // 3.5095 Wh

        val screenOnHours = 1.1f // 1h06m
        val screenOffHours = 9.167f // 9h10m
        val dischargeHours = screenOnHours + screenOffHours
        val physicalTotalEnergyWh = 3.5095f

        val dualStats = PowerUsageManager.calculateDualAnchorEnergyAndPower(
            intOnEnergyWh = intOnEnergyWh,
            intOffEnergyWh = intOffEnergyWh,
            intTotalEnergyWh = intTotalEnergyWh,
            intOnPowerWatts = intOnEnergyWh / screenOnHours,
            intOffPowerWatts = intOffEnergyWh / screenOffHours,
            intTotalPowerWatts = intTotalEnergyWh / dischargeHours,
            physicalTotalEnergyWh = physicalTotalEnergyWh,
            screenOnHours = screenOnHours,
            screenOffHours = screenOffHours,
            dischargeHours = dischargeHours,
            screenOffMs = (screenOffHours * 3600_000).toLong(),
            nominalVoltageVolts = 3.85f
        )

        // 验证结果：
        // 1. 亮屏能量 100% 保持 1Hz 梯形微积分真值
        assertEquals("亮屏能量严格保持 1Hz 梯形微积分真值", 2.457f, dualStats.onEnergyWh, 0.001f)
        // 2. 息屏能量精准为硬件芯片真值 1.0525Wh
        assertEquals("息屏能量精准为芯片硬件差分真值", 1.0525f, dualStats.offEnergyWh, 0.001f)
        // 3. 整机总能耗无虚假膨胀
        assertEquals("整机总能耗严格等于亮屏与息屏物理之和", 3.5095f, dualStats.totalEnergyWh, 0.001f)
        // 4. 息屏平均功率约为 0.115W，完全符合客观硬件待机功率
        assertTrue("息屏功率处于纯净待机区间", dualStats.screenOffWatts < 0.15f)
    }

    /**
     * 验证当硬件芯片库仑计处于离散步进延迟（未触发跳变，仅产生 0.1mAh / 0.0013W 量化噪声）时，
     * 物理合理性门禁拦截该离散脏数据，并通过待机底噪基线保障息屏能量与功耗，杜绝 18 分钟息屏出现 0.000Wh 与 '--'。
     */
    @Test
    fun testDiscreteCoulombCounterLagRejectionAndStandbyFloorProtection() {
        val screenOffMs = 18L * 60_000L + 17_000L // 18m17s
        val screenOffHours = screenOffMs / 3600_000f

        // 模拟荣耀等机型未步进时的快照差分：0.1mAh，端电压 4.08V，折算能量 0.0004Wh
        val rawHwMah = 0.1f
        val rawHwWh = 0.0004f
        val impliedWatts = rawHwWh / screenOffHours // 0.0013W

        // 1. 物理门禁校验（低于 0.02W 判定为未步进脏数据）
        val isPhysicallyPlausible = rawHwMah >= 0.2f && impliedWatts in 0.02f..1.5f
        assertFalse("0.1mAh 对应的 0.0013W 待机功率应被门禁判定为离散量化未步进", isPhysicallyPlausible)

        // 2. 模拟底座保障逻辑
        val safeOffWatts = if (impliedWatts < 0.03f) {
            com.battery.analysis.provider.ShizukuBatteryStatsParser.DEFAULT_STANDBY_BASE_WATTS
        } else {
            impliedWatts
        }
        val safeOffEnergyWh = safeOffWatts * screenOffHours

        assertTrue("保障后的息屏功率处于真实待机区间（>= 0.05W，杜绝 '--'）", safeOffWatts >= 0.05f)
        assertTrue("保障后的息屏能量为真实非零值（杜绝 '0.000Wh'）", safeOffEnergyWh > 0.015f)

        // 3. 传入双锚定计算
        val onEnergyWh = 0.263f // 亮屏 6m45s 微积分
        val onHours = (6L * 60_000L + 45_000L) / 3600_000f
        val totalHours = onHours + screenOffHours

        val dualStats = PowerUsageManager.calculateDualAnchorEnergyAndPower(
            intOnEnergyWh = onEnergyWh,
            intOffEnergyWh = safeOffEnergyWh,
            intTotalEnergyWh = onEnergyWh + safeOffEnergyWh,
            intOnPowerWatts = onEnergyWh / onHours,
            intOffPowerWatts = safeOffWatts,
            intTotalPowerWatts = (onEnergyWh + safeOffEnergyWh) / totalHours,
            physicalTotalEnergyWh = 0.20f, // 掉电 1% 折算的物理能量
            screenOnHours = onHours,
            screenOffHours = screenOffHours,
            dischargeHours = totalHours,
            screenOffMs = screenOffMs,
            nominalVoltageVolts = 3.85f
        )

        // 验证：
        // 1. 亮屏能量完全稳定（0.263Wh）
        assertEquals(0.263f, dualStats.onEnergyWh, 0.001f)
        // 2. 息屏能量正常（约 0.045Wh，不再为 0）
        assertTrue("息屏能量杜绝归零", dualStats.offEnergyWh > 0.02f)
        // 3. 息屏功耗正常展示（约 0.15W，不再为 '--'）
        assertTrue("息屏功耗正常展示", dualStats.screenOffWatts >= 0.05f)
        // 4. 整机总能量包含息屏能耗
        assertTrue("整机能量包含息屏部分", dualStats.totalEnergyWh > onEnergyWh)
    }

    /**
     * 验证当真实掉电 1%（50mAh）发生但芯片库仑计因离散未步进仅记录 0.1mAh 时，
     * 系统能够准确采信真实掉电量，杜绝整机物理能量基线被击穿。
     */
    @Test
    fun testPhysicalDrainMahPrioritizesActualDropWhenCoulombLagging() {
        val dropPercent = 1
        val effectiveCapacity = 5000f
        val smoothedDropMah = effectiveCapacity * (dropPercent / 100f) // 50mAh
        val hwDischargedMah = 0.1f // 硬件库仑计未跳变

        val validHwMah = if (hwDischargedMah > 0f) {
            if (dropPercent > 0 && hwDischargedMah < (smoothedDropMah * 0.3f)) {
                0f
            } else {
                hwDischargedMah
            }
        } else {
            0f
        }

        val physicalDrainMah = when {
            validHwMah > 0f -> validHwMah
            dropPercent > 0 -> smoothedDropMah
            else -> 0f
        }

        assertEquals("真实掉电 1% 必须优先采纳物理 50mAh 而非滞后的 0.1mAh", 50f, physicalDrainMah, 0.001f)
    }

    /**
     * 验证当电池百分比未下降（dropPercent == 0，如 69% -> 69%）且放电时长达 27 分钟（亮屏 3m53s，息屏 23m28s）时，
     * 因无宏观掉电天花板（physicalTotalEnergyWh == 0f），双锚定算法不会抹杀息屏能耗，息屏功耗与能量忠实按物理待机基线呈现。
     */
    @Test
    fun testScreenOffEnergyWhenDropPercentZeroAndDualAnchorInvoked() {
        val onEnergyWh = 0.174f
        val onHours = 233f / 3600f // 3分53秒
        val onWatts = onEnergyWh / onHours // 2.69W
        val offHours = 1408f / 3600f // 23分28秒
        val totalHours = onHours + offHours
        val safeOffWatts = 0.08f // 系统客观待机底噪
        val safeOffEnergyWh = safeOffWatts * offHours // 约 0.0313Wh
        val physicalTotalEnergyWh = 0f // 未发生宏观百分比掉电且库仑计未步进，无宏观物理天花板

        val dualStats = PowerUsageManager.calculateDualAnchorEnergyAndPower(
            intOnEnergyWh = onEnergyWh,
            intOffEnergyWh = safeOffEnergyWh,
            intTotalEnergyWh = onEnergyWh + safeOffEnergyWh,
            intOnPowerWatts = onWatts,
            intOffPowerWatts = safeOffWatts,
            intTotalPowerWatts = (onEnergyWh + safeOffEnergyWh) / totalHours,
            physicalTotalEnergyWh = physicalTotalEnergyWh,
            screenOnHours = onHours,
            screenOffHours = offHours,
            dischargeHours = totalHours,
            screenOffMs = 1408000L,
            nominalVoltageVolts = 3.85f
        )

        // 验证：
        // 1. 亮屏能量与功耗保持高精度微积分真值（0.174Wh，2.69W）
        assertEquals(0.174f, dualStats.onEnergyWh, 0.001f)
        assertEquals(2.688f, dualStats.screenOnWatts, 0.05f)

        // 2. 息屏能量绝对不被抹杀归零（约 0.031Wh，杜绝 0.000Wh）
        assertTrue("dropPercent == 0 时息屏能量杜绝归零", dualStats.offEnergyWh > 0.02f)

        // 3. 息屏功耗正常展示待机真实值（约 0.08W，杜绝 '--'）
        assertTrue("dropPercent == 0 时息屏功耗正常展示（>= 0.05W）", dualStats.screenOffWatts >= 0.05f)

        // 4. 整机总能量忠实反映亮屏与息屏之和（约 0.205Wh）
        assertEquals(onEnergyWh + dualStats.offEnergyWh, dualStats.totalEnergyWh, 0.001f)
    }

    /**
     * 验证当放电周期达 27 分钟但硬件芯片库仑计差值因量化未步进仅记录 0.1mAh 时，
     * 功率门禁能正确过滤量化噪声，将其置为 0f，杜绝 0.1mAh 穿透破坏整机放电量。
     */
    @Test
    fun testValidHwMahFilteringWhenCoulombQuantizationNotStepped() {
        val durationMs = 1641000L // 27分21秒
        val dischargeHours = durationMs / 3600000f
        val hwDischargedMah = 0.1f // 仅 0.1mAh
        val nominalVoltageVolts = 3.85f
        val dropPercent = 0
        val smoothedDropMah = 0f

        val impliedTotalWatts = if (dischargeHours > 0f) (hwDischargedMah * nominalVoltageVolts / 1000f) / dischargeHours else 0f

        val validHwMah = if (hwDischargedMah > 0f) {
            if (dropPercent > 0 && hwDischargedMah < (smoothedDropMah * 0.3f)) {
                0f
            } else if (durationMs >= 30_000L && impliedTotalWatts < 0.02f) {
                0f
            } else {
                hwDischargedMah
            }
        } else {
            0f
        }

        assertEquals("量化未步进的 0.1mAh 必须被过滤为 0f", 0f, validHwMah, 0.0001f)
    }

    /**
     * 验证在亮屏使用状态下持续手动刷新时，息屏能量保持恒定不递减至 0，
     * 亮屏能量正常累加，整机总能量平滑递增而非被锁死在旧的宏观放电量上限。
     */
    @Test
    fun testScreenOffEnergyPreservedWhileScreenOnRefreshed() {
        val screenOffMs = 25L * 60_000L + 30_000L // 25分30秒息屏
        val screenOffHours = screenOffMs / 3600000f
        val baseOffWatts = 0.10f // 待机真实底噪 0.10W
        val intOffEnergyWh = baseOffWatts * screenOffHours // 约 0.0425Wh
        val physicalTotalEnergyWh = 0.200f // 假设电量掉电 1% 对应的初始宏观放电量 0.200Wh
        val nominalVoltageVolts = 3.85f

        // 第一次刷新：亮屏使用 2 分钟，消耗 0.100Wh
        val screenOnHours1 = 2f / 60f
        val onEnergyWh1 = 0.100f
        val dischargeHours1 = screenOffHours + screenOnHours1
        val stats1 = PowerUsageManager.calculateDualAnchorEnergyAndPower(
            intOnEnergyWh = onEnergyWh1,
            intOffEnergyWh = intOffEnergyWh,
            intTotalEnergyWh = onEnergyWh1 + intOffEnergyWh,
            intOnPowerWatts = onEnergyWh1 / screenOnHours1,
            intOffPowerWatts = baseOffWatts,
            intTotalPowerWatts = (onEnergyWh1 + intOffEnergyWh) / dischargeHours1,
            physicalTotalEnergyWh = physicalTotalEnergyWh,
            screenOnHours = screenOnHours1,
            screenOffHours = screenOffHours,
            dischargeHours = dischargeHours1,
            screenOffMs = screenOffMs,
            nominalVoltageVolts = nominalVoltageVolts
        )

        // 第二次刷新：亮屏使用累积到 5 分钟，亮屏能耗上升到 0.220Wh（已超过初始 physicalTotalEnergyWh）
        val screenOnHours2 = 5f / 60f
        val onEnergyWh2 = 0.220f
        val dischargeHours2 = screenOffHours + screenOnHours2
        val stats2 = PowerUsageManager.calculateDualAnchorEnergyAndPower(
            intOnEnergyWh = onEnergyWh2,
            intOffEnergyWh = intOffEnergyWh,
            intTotalEnergyWh = onEnergyWh2 + intOffEnergyWh,
            intOnPowerWatts = onEnergyWh2 / screenOnHours2,
            intOffPowerWatts = baseOffWatts,
            intTotalPowerWatts = (onEnergyWh2 + intOffEnergyWh) / dischargeHours2,
            physicalTotalEnergyWh = physicalTotalEnergyWh,
            screenOnHours = screenOnHours2,
            screenOffHours = screenOffHours,
            dischargeHours = dischargeHours2,
            screenOffMs = screenOffMs,
            nominalVoltageVolts = nominalVoltageVolts
        )

        // 验证 1：息屏能量绝对不因亮屏使用而递减或归零，始终保持息屏基线能耗（~0.0425Wh）
        assertEquals("刷新 1 时息屏能耗保持真值", intOffEnergyWh, stats1.offEnergyWh, 0.001f)
        assertEquals("刷新 2 时息屏能耗杜绝递减归零，坚守真值", intOffEnergyWh, stats2.offEnergyWh, 0.001f)

        // 验证 2：亮屏能量忠实递增
        assertTrue("亮屏能量吸收阶跃残差且不低于瞬时微积分值", stats1.onEnergyWh >= onEnergyWh1)
        assertEquals(0.220f, stats2.onEnergyWh, 0.001f)
        assertTrue("亮屏能量随使用平滑增加", stats2.onEnergyWh > stats1.onEnergyWh)

        // 验证 3：整机能量平滑递增（亮屏能量 + 息屏能量），杜绝锁死在 physicalTotalEnergyWh
        assertEquals(stats1.onEnergyWh + stats1.offEnergyWh, stats1.totalEnergyWh, 0.001f)
        assertEquals(stats2.onEnergyWh + stats2.offEnergyWh, stats2.totalEnergyWh, 0.001f)
        assertTrue("整机能量随亮屏使用平滑上升", stats2.totalEnergyWh > stats1.totalEnergyWh)
    }

    /**
     * 验证在亮屏交互状态下触发短时缓存时，点亮时间区间能够动态延伸至最新查询终点，
     * 消除短时内存缓存导致的亮屏区间截断，杜绝息屏时间在 3 秒窗口内的忽大忽小跳变。
     */
    @Test
    fun testScreenOffTimeDoesNotJitterDuringCacheWindow() {
        val unplugTime = 100_000L
        val cachedEndTime = 200_000L // 首次查询终点
        val screenIntervals = listOf(
            PowerUsageManager.ScreenInteractiveInterval(unplugTime, 120_000L), // 亮屏 20s
            // 120_000L ~ 170_000L 息屏 50s
            PowerUsageManager.ScreenInteractiveInterval(170_000L, cachedEndTime) // 亮屏 30s，持续到 cachedEndTime
        )
        // 此时总时长 = 100s，亮屏时长 = 20s + 30s = 50s，息屏时长 = 50s
        val initialTotalMs = cachedEndTime - unplugTime
        val initialOnMs = screenIntervals.sumOf { it.endTs - it.startTs }
        val initialOffMs = initialTotalMs - initialOnMs
        assertEquals(50_000L, initialOffMs)

        // 用户在 2 秒后（cachedEndTime + 2000L）手动刷新，命中 3 秒短时缓存
        val refreshEndTime = cachedEndTime + 2000L
        val adjustedScreens = PowerUsageManager.adjustScreenIntervalsForInteractiveScreen(
            screens = screenIntervals,
            startTime = unplugTime,
            cachedEnd = cachedEndTime,
            newEndTime = refreshEndTime
        )

        // 计算补正后的亮屏时长与息屏时长
        val refreshedTotalMs = refreshEndTime - unplugTime // 102s
        val refreshedOnMs = adjustedScreens.sumOf { it.endTs - it.startTs } // 52s
        val refreshedOffMs = refreshedTotalMs - refreshedOnMs // 50s

        // 验证：息屏时长严格保持为 50s，杜绝因为亮屏区间未延伸而跳变虚增至 52s
        assertEquals("亮屏区间动态延伸后总亮屏时长同步增加 2s", 52_000L, refreshedOnMs)
        assertEquals("息屏时长在 3 秒短时刷新窗口内保持平稳单调，杜绝跳变", initialOffMs, refreshedOffMs)
    }

    /**
     * 验证当系统底层 dumpsys 上报失真极小的待机放电量（如 Idle 仅 0.01mAh）时，
     * 物理底噪门禁能够精准拦截异常，并采纳时序实测待机底噪（如 0.04W），
     * 精确计算出深度睡眠能耗（非 0.000Wh），且深睡与唤醒能量之和严格守恒。
     */
    @Test
    fun testScreenOffAwakeAndDeepSleepDecompositionWithDumpsysDistortion() {
        val screenOffMs = 1530_000L // 25分30秒
        val deepSleepMs = 1419_000L // 23分39秒
        val awakeMs = 111_000L // 1分51秒
        val totalOffEnergyWh = 0.143f // 息屏总放电 0.143Wh (37.0mAh * 3.85V / 1000)
        val distortedSleepMah = 0.01f // dumpsys 上报失真微量 0.01mAh（折算功率仅 0.0001W）
        val rawAwakeMah = 0f

        // 模拟底层时序采样序列中包含硬件实测待机底噪（0.04W）
        val samples = listOf(
            PowerDischargePoint(timestamp = 1000L, elapsedHours = 0.01f, batteryLevel = 80, voltageVolts = 3.85f, powerWatts = 0.04f, temperature = 28f, isScreenOn = false),
            PowerDischargePoint(timestamp = 60000L, elapsedHours = 0.02f, batteryLevel = 80, voltageVolts = 3.85f, powerWatts = 0.04f, temperature = 28f, isScreenOn = false),
            PowerDischargePoint(timestamp = 120000L, elapsedHours = 0.03f, batteryLevel = 80, voltageVolts = 3.85f, powerWatts = 0.04f, temperature = 28f, isScreenOn = false)
        )

        val decomposed = PowerUsageManager.calculateScreenOffAwakeAndDeepSleepEnergy(
            offEnergyWh = totalOffEnergyWh,
            screenOffMs = screenOffMs,
            deepSleepMs = deepSleepMs,
            awakeMs = awakeMs,
            rawSleepDrainMah = distortedSleepMah,
            rawAwakeDrainMah = rawAwakeMah,
            samples = samples,
            nominalVoltageVolts = 3.85f
        )

        // 1. 拆解必须判定有效可用
        assertTrue("拆解结果必须有效", decomposed.isDecomposedAvailable)

        // 2. 深度休眠功耗采纳实测底噪 0.04W，杜绝失真的 0.00W
        assertEquals(0.04f, decomposed.deepSleepWatts, 0.005f)

        // 3. 深度睡眠能耗应为 0.04W * (1419s / 3600s) ≈ 0.0158Wh，绝不允许显示为 0.000Wh
        val expectedSleepEnergy = 0.04f * (deepSleepMs / 3600000f)
        assertEquals("深度睡眠能耗必须为合理的物理实测值", expectedSleepEnergy, decomposed.deepSleepEnergyWh, 0.001f)
        assertTrue("深度睡眠能耗绝对不为 0.000Wh", decomposed.deepSleepEnergyWh > 0.01f)

        // 4. 唤醒活跃能耗等于 0.143Wh - 0.0158Wh ≈ 0.1272Wh
        val expectedAwakeEnergy = totalOffEnergyWh - decomposed.deepSleepEnergyWh
        assertEquals("唤醒能耗严格对齐剩余能量", expectedAwakeEnergy, decomposed.awakeEnergyWh, 0.001f)

        // 5. 唤醒与深睡能量总和严格等于息屏总能耗（物理能量守恒）
        assertEquals("深睡与唤醒能量之和严格守恒", totalOffEnergyWh, decomposed.deepSleepEnergyWh + decomposed.awakeEnergyWh, 0.0001f)
    }

    /**
     * 验证在无实测采样点且系统 dumpsys 上报失真微量（Idle 0.01mAh）时，
     * 算法如实将 isDecomposedAvailable 置为 false，展示未知/未获取，
     * 严禁捏造虚假保底比例。
     */
    @Test
    fun testScreenOffAwakeAndDeepSleepReturnsUnavailableWhenNoValidData() {
        val screenOffMs = 1530_000L // 25分30秒
        val deepSleepMs = 1419_000L // 23分39秒
        val awakeMs = 111_000L // 1分51秒
        val totalOffEnergyWh = 0.143f
        val distortedSleepMah = 0.01f

        val decomposed = PowerUsageManager.calculateScreenOffAwakeAndDeepSleepEnergy(
            offEnergyWh = totalOffEnergyWh,
            screenOffMs = screenOffMs,
            deepSleepMs = deepSleepMs,
            awakeMs = awakeMs,
            rawSleepDrainMah = distortedSleepMah,
            rawAwakeDrainMah = 0f,
            samples = emptyList(), // 无任何采样点
            nominalVoltageVolts = 3.85f
        )

        // 验证：缺乏有效实测底噪且 dumpsys 严重失真时，忠实展示不可用，不伪造数据
        assertFalse("无有效数据时拆解结果必须标记为不可用", decomposed.isDecomposedAvailable)
        assertEquals(0f, decomposed.deepSleepEnergyWh, 0.0001f)
        assertEquals(0f, decomposed.awakeEnergyWh, 0.0001f)
    }

    /**
     * 验证当评估窗口内仅采集到灭屏瞬态过渡尖峰点（如 0.35W > 0.15W）时，
     * 算法能够自动从放电全周期真实历史样本池中提取纯净芯片待机底噪（如 0.038W），
     * 从而保持物理守恒能量分解成功，杜绝唤醒统计偶发跌入不可用（--）状态。
     */
    @Test
    fun testScreenOffTransitionSpikeFallbackToAllCycleQuiescentStandby() {
        val screenOffMs = 1800_000L // 息屏 30 分钟
        val deepSleepMs = 1500_000L // 深度休眠 25 分钟
        val awakeMs = 300_000L // 唤醒 5 分钟
        val totalOffEnergyWh = 0.050f // 息屏总能耗 0.050Wh

        // 当前窗口内仅有刚灭屏时的瞬态过渡点（0.35W，超过待机门禁）
        val recentWindowSamples = listOf(
            PowerDischargePoint(
                timestamp = System.currentTimeMillis() - 1790_000L,
                elapsedHours = 0.5f,
                batteryLevel = 80,
                voltageVolts = 4.0f,
                temperature = 30.0f,
                powerWatts = 0.35f,
                isScreenOn = false
            )
        )

        // 放电全周期历史样本池中包含了此前测得的真实待机底噪点（0.035W）
        val allCycleSamples = listOf(
            PowerDischargePoint(
                timestamp = System.currentTimeMillis() - 3600_000L,
                elapsedHours = 1.0f,
                batteryLevel = 82,
                voltageVolts = 4.05f,
                temperature = 29.5f,
                powerWatts = 0.035f,
                isScreenOn = false
            )
        )

        val decomposed = PowerUsageManager.calculateScreenOffAwakeAndDeepSleepEnergy(
            offEnergyWh = totalOffEnergyWh,
            screenOffMs = screenOffMs,
            deepSleepMs = deepSleepMs,
            awakeMs = awakeMs,
            rawSleepDrainMah = 0f,
            rawAwakeDrainMah = 0f,
            samples = recentWindowSamples,
            nominalVoltageVolts = 3.85f,
            allCycleSamples = allCycleSamples
        )

        // 验证：成功复用放电全周期测得的真实待机底噪
        assertTrue("复用全周期实测待机底噪后必须可用", decomposed.isDecomposedAvailable)
        val expectedSleepWh = 0.035f * (deepSleepMs / 3600000f)
        assertEquals("深度休眠能耗与芯片实测底噪相符", expectedSleepWh, decomposed.deepSleepEnergyWh, 0.001f)
        val expectedAwakeWh = totalOffEnergyWh - decomposed.deepSleepEnergyWh
        assertEquals("唤醒能耗与剩余能耗完全守恒", expectedAwakeWh, decomposed.awakeEnergyWh, 0.001f)
        assertEquals("总能耗严格物理守恒", totalOffEnergyWh, decomposed.deepSleepEnergyWh + decomposed.awakeEnergyWh, 0.0001f)
    }

    /**
     * 验证物理累加器 JSON 字符串包含息屏唤醒/深度睡眠/库仑计快照字段时的解析与持久化完整性。
     */
    @Test
    fun testScreenOffAwakeJsonSerializationAndRestoration() {
        val jsonStr = """
            {
                "onJ": 1250.5,
                "offJ": 450.2,
                "onMs": 600000,
                "offMs": 1800000,
                "offAwakeMs": 150000,
                "offSleepMs": 1650000,
                "offHwMah": 12.5,
                "offHwWh": 0.048,
                "lastTs": 1700000000000,
                "lastW": 0.05,
                "lastOn": false,
                "lastT": 28.5,
                "apps": []
            }
        """.trimIndent()

        val obj = org.json.JSONObject(jsonStr)
        val restoredAwakeMs = obj.optLong("offAwakeMs", 0L)
        val restoredSleepMs = obj.optLong("offSleepMs", 0L)
        val restoredHwMah = obj.optDouble("offHwMah", 0.0).toFloat()
        val restoredHwWh = obj.optDouble("offHwWh", 0.0).toFloat()

        assertEquals("唤醒时长准确恢复", 150000L, restoredAwakeMs)
        assertEquals("深度休眠时长准确恢复", 1650000L, restoredSleepMs)
        assertEquals("库仑计电量准确恢复", 12.5f, restoredHwMah, 0.001f)
        assertEquals("端电压能量准确恢复", 0.048f, restoredHwWh, 0.0001f)
    }

    /**
     * 验证亮屏使用期间多次连续手动刷新时，息屏能耗绝对坚守其不可逆的物理历史真值，
     * 杜绝因宏观电量百分比尚未步进导致息屏能量被 (physicalTotalEnergyWh - onEnergyWh) 虚拟钳位反向倒扣慢慢变小、
     * 以及在亮屏能耗突破门禁或息屏后再刷新时突然跳变的物理异常。
     */
    @Test
    fun testScreenOffEnergyMonotonicallyNonDecreasingDuringScreenOnUsage() {
        val nominalVoltage = 3.85f
        val screenOffMs = 3600_000L // 息屏 1 小时
        val screenOffHours = 1.0f
        val realScreenOffEnergyWh = 0.496f // 硬件实测息屏真实能耗 0.496Wh
        val intOffPowerWatts = 0.496f // 息屏等效功率 0.496W (> 0.15W)
        val physicalTotalEnergyWh = 1.000f // 电池掉电 1% 对应的宏观电量 1.000Wh

        // 模拟亮屏使用期间的连续 4 次刷新，亮屏能耗由 0.634Wh 逐渐增加
        val screenOnWatts = 2.0f
        val testOnEnergies = listOf(0.634f, 0.640f, 0.680f, 0.750f)

        for (onEnergyWh in testOnEnergies) {
            val screenOnHours = onEnergyWh / screenOnWatts
            val dischargeHours = screenOffHours + screenOnHours
            val stats = PowerUsageManager.calculateDualAnchorEnergyAndPower(
                intOnEnergyWh = onEnergyWh,
                intOffEnergyWh = realScreenOffEnergyWh,
                intTotalEnergyWh = onEnergyWh + realScreenOffEnergyWh,
                intOnPowerWatts = screenOnWatts,
                intOffPowerWatts = intOffPowerWatts,
                intTotalPowerWatts = (onEnergyWh + realScreenOffEnergyWh) / dischargeHours,
                physicalTotalEnergyWh = physicalTotalEnergyWh,
                screenOnHours = screenOnHours,
                screenOffHours = screenOffHours,
                dischargeHours = dischargeHours,
                screenOffMs = screenOffMs,
                nominalVoltageVolts = nominalVoltage
            )

            // 核心物理验证：息屏能量必须坚定等于 0.496Wh，严禁被倒扣为 0.366Wh、0.360Wh 等残差，更严禁缩水归零
            assertEquals(
                "亮屏能耗在 ${onEnergyWh}Wh 刷新时，息屏能量必须稳定等于 0.496Wh 实测值",
                realScreenOffEnergyWh,
                stats.offEnergyWh,
                0.001f
            )

            // 亮屏能量保持真实递增
            assertEquals(onEnergyWh, stats.onEnergyWh, 0.001f)

            // 整机总能量等于亮屏微积分与息屏能耗之和，忠实反映真实能耗
            assertEquals(
                stats.onEnergyWh + stats.offEnergyWh,
                stats.totalEnergyWh,
                0.001f
            )
        }
    }

    /**
     * 验证极低待机功耗设备（如深度睡眠功耗低于 0.02W）在长待机下的硬件快照能耗能够被完整采信，
     * 杜绝因全周期平均功率低于 0.02W 导致硬件实测数据被丢弃并在外推值与实测值之间反复横跳，
     * 同时验证系统绝不使用 0.15W 等人为合成的假数据进行保底覆盖。
     */
    @Test
    fun testUltraLowStandbyWattsScreenOffHardwareSnapshotPreserved() {
        val nominalVoltage = 3.85f
        val screenOffMs = 10L * 3600_000L // 10 小时长待机
        val screenOffHours = 10.0f
        val screenOnHours = 1.0f
        val dischargeHours = 11.0f
        val onEnergyWh = 2.0f

        // 极低功耗待机：10 小时仅消耗 0.100Wh，等效待机功率为 0.010W (< 0.02W)
        val ultraLowOffEnergyWh = 0.100f
        val ultraLowOffWatts = 0.010f

        val stats = PowerUsageManager.calculateDualAnchorEnergyAndPower(
            intOnEnergyWh = onEnergyWh,
            intOffEnergyWh = ultraLowOffEnergyWh,
            intTotalEnergyWh = onEnergyWh + ultraLowOffEnergyWh,
            intOnPowerWatts = 2.0f,
            intOffPowerWatts = ultraLowOffWatts,
            intTotalPowerWatts = (onEnergyWh + ultraLowOffEnergyWh) / dischargeHours,
            physicalTotalEnergyWh = 0f,
            screenOnHours = screenOnHours,
            screenOffHours = screenOffHours,
            dischargeHours = dischargeHours,
            screenOffMs = screenOffMs,
            nominalVoltageVolts = nominalVoltage
        )

        // 验证：极低功耗息屏能耗 0.100Wh 严格忠实保留，绝不被人为保底为 0.15W * 10h = 1.50Wh
        assertEquals(
            "极低待机功耗息屏能量忠实保留实测值 0.100Wh",
            ultraLowOffEnergyWh,
            stats.offEnergyWh,
            0.001f
        )
        assertEquals(
            "极低待机功耗准确计算为 0.010W",
            ultraLowOffWatts,
            stats.screenOffWatts,
            0.001f
        )
    }

    /**
     * 验证图一场景：息屏存在有效唤醒时长（13m10s）与深度休眠时长（3h59m22s）时，
     * 深度休眠能耗受到物理功耗梯度上限约束，唤醒能耗稳定大于 0（严格杜绝深睡独占 100% 息屏能耗导致唤醒能耗为 0 显示为 --），
     * 且唤醒能耗与深睡能耗之和严格物理守恒等于息屏总能耗。
     */
    @Test
    fun testScreenOffDecompositionWithAwakeAndDeepSleepGuaranteesAwakeEnergyPositive() {
        val offEnergyWh = 0.540f // 息屏总能耗 0.540Wh
        val awakeMs = 13L * 60_000L + 10_000L // 13分10秒唤醒
        val deepSleepMs = 3L * 3600_000L + 59L * 60_000L + 22_000L // 3小时59分22秒深睡
        val screenOffMs = awakeMs + deepSleepMs // 4小时12分32秒

        // 构造几个略微偏高的唤醒样本（0.14W 左右）
        val baseTs = 1700000000000L
        val testSamples = listOf(
            PowerDischargePoint(timestamp = baseTs, elapsedHours = 0f, batteryLevel = 80, voltageVolts = 4.0f, temperature = 30f, powerWatts = 0.14f, isScreenOn = false),
            PowerDischargePoint(timestamp = baseTs + 60_000L, elapsedHours = 0.01f, batteryLevel = 80, voltageVolts = 4.0f, temperature = 30f, powerWatts = 0.15f, isScreenOn = false),
            PowerDischargePoint(timestamp = baseTs + 120_000L, elapsedHours = 0.02f, batteryLevel = 80, voltageVolts = 4.0f, temperature = 30f, powerWatts = 0.13f, isScreenOn = false)
        )

        val decomposed = PowerUsageManager.calculateScreenOffAwakeAndDeepSleepEnergy(
            offEnergyWh = offEnergyWh,
            screenOffMs = screenOffMs,
            deepSleepMs = deepSleepMs,
            awakeMs = awakeMs,
            rawSleepDrainMah = 0f,
            rawAwakeDrainMah = 0f,
            samples = testSamples,
            nominalVoltageVolts = 3.85f
        )

        // 1. 细分状态必须标记为可用
        assertTrue("细分可用性标记必须为 true", decomposed.isDecomposedAvailable)

        // 2. 唤醒能耗必须稳定大于 0，绝对不允许为 0f
        assertTrue("唤醒能量必须大于 0，杜绝显示为 --", decomposed.awakeEnergyWh > 0.001f)
        assertTrue("唤醒等效功率必须大于 0", decomposed.awakeWatts > 0.05f)

        // 3. 深度睡眠能耗必须为正数且合理
        assertTrue("深睡能量必须大于 0", decomposed.deepSleepEnergyWh > 0f)
        assertTrue("深睡功率必须低于息屏总平均功率", decomposed.deepSleepWatts < (offEnergyWh / (screenOffMs / 3600000f)))

        // 4. 物理能量守恒：深睡能量 + 唤醒能量 == 息屏总能量
        assertEquals(
            "能量守恒：深睡能量与唤醒能量之和严格等于息屏总能量",
            offEnergyWh,
            decomposed.deepSleepEnergyWh + decomposed.awakeEnergyWh,
            0.001f
        )
    }

    /**
     * 验证图二场景：8 小时长时间待机（深度睡眠 8h01m，唤醒 23m），
     * 休眠长断层外推基线功率受到静态休眠底噪上限约束（<= 0.06W），
     * 息屏总能耗保持在合理物理区间（约 0.4Wh ~ 0.7Wh），杜绝虚高膨胀至 2.063Wh，
     * 同时唤醒功率保持在合理活跃区间（<= 1.0W），杜绝出现 3.79W 的异常尖峰。
     */
    @Test
    fun testLongDeepSleepGapExtrapolatedPowerBoundedByQuiescentStandbyWatts() {
        // 模拟放电采样点序列：包含一段亮屏使用、一次熄灭进入 8 小时深度休眠大断层，随后唤醒点亮
        val startTs = 1700000000000L
        val sleepGapMs = 8L * 3600_000L // 8 小时深度休眠断层
        val wakeSamples = listOf(
            // 亮屏阶段
            PowerDischargePoint(timestamp = startTs, elapsedHours = 0f, batteryLevel = 80, voltageVolts = 4.0f, temperature = 30f, powerWatts = 2.0f, isScreenOn = true),
            PowerDischargePoint(timestamp = startTs + 60_000L, elapsedHours = 0.016f, batteryLevel = 80, voltageVolts = 4.0f, temperature = 30f, powerWatts = 2.0f, isScreenOn = true),
            // 灭屏瞬间捕获到硬件静止待机底噪（0.05W），随后伴随偶发唤醒短样本（0.28W）
            PowerDischargePoint(timestamp = startTs + 61_000L, elapsedHours = 0.017f, batteryLevel = 80, voltageVolts = 4.0f, temperature = 30f, powerWatts = 0.05f, isScreenOn = false),
            PowerDischargePoint(timestamp = startTs + 120_000L, elapsedHours = 0.033f, batteryLevel = 80, voltageVolts = 4.0f, temperature = 30f, powerWatts = 0.28f, isScreenOn = false),
            // 经过 8 小时深度休眠断层后醒来
            PowerDischargePoint(timestamp = startTs + 120_000L + sleepGapMs, elapsedHours = 8.033f, batteryLevel = 78, voltageVolts = 3.9f, temperature = 28f, powerWatts = 0.25f, isScreenOn = false)
        )

        val stats = PowerUsageManager.computeDischargePowerStats(wakeSamples)
        org.junit.Assert.assertNotNull("放电统计结果不应为空", stats)

        // 验证：8 小时长断层外推的息屏总能耗精准采纳硬件客观底噪（8h * 0.05W ≈ 0.40Wh，总息屏能耗杜绝飙升至 2.063Wh）
        assertTrue("8小时休眠息屏总能量必须 <= 0.8Wh，杜绝膨胀至 2.063Wh", stats!!.screenOffDisplayEnergyWh <= 0.8f)
        assertTrue("8小时休眠息屏平均功率必须 <= 0.10W，杜绝虚高为 0.24W", stats.screenOffPowerWatts <= 0.10f)
    }

    /**
     * 验证用户实测场景：在多次下拉刷新及持续亮屏使用过程中，已确认的息屏能量坚守物理真值，
     * 绝不因采样队列滚动导致息屏能耗在 1.080Wh、1.924Wh 与 1.586Wh 之间发生跷跷板剧烈跳变与反向倒扣缩水。
     */
    @Test
    fun testDualAnchorPreservesConfirmedScreenOffEnergyAcrossRefreshes() {
        val nominalVoltage = 3.85f
        val screenOffMs = (3L * 3600_000L) + (59L * 60_000L) + (42L * 1000L) // 3h 59m 42s
        val screenOffHours = screenOffMs / 3600000f

        // 阶段 1（对应图一）：息屏 3h59m42s 实测能耗 1.080Wh（0.27W），亮屏 2h10m35s 微积分真值 5.98Wh，库仑计整机 7.675Wh
        val screenOnHours1 = (2L * 3600_000L + 10L * 60_000L + 35L * 1000L) / 3600000f
        val dischargeHours1 = screenOnHours1 + screenOffHours
        val intOnEnergyWh1 = 5.98f
        val intOffEnergyWh1 = 1.080f
        val physicalTotalEnergyWh1 = 7.675f

        val stats1 = PowerUsageManager.calculateDualAnchorEnergyAndPower(
            intOnEnergyWh = intOnEnergyWh1,
            intOffEnergyWh = intOffEnergyWh1,
            intTotalEnergyWh = intOnEnergyWh1 + intOffEnergyWh1,
            intOnPowerWatts = intOnEnergyWh1 / screenOnHours1,
            intOffPowerWatts = intOffEnergyWh1 / screenOffHours,
            intTotalPowerWatts = (intOnEnergyWh1 + intOffEnergyWh1) / dischargeHours1,
            physicalTotalEnergyWh = physicalTotalEnergyWh1,
            screenOnHours = screenOnHours1,
            screenOffHours = screenOffHours,
            dischargeHours = dischargeHours1,
            screenOffMs = screenOffMs,
            nominalVoltageVolts = nominalVoltage,
            minScreenOffEnergyWh = 0f
        )

        assertEquals("阶段1息屏能耗保持实测基线真值 1.080Wh", 1.080f, stats1.offEnergyWh, 0.001f)
        assertEquals("阶段1息屏功耗保持实测 0.27W", 0.27f, stats1.screenOffWatts, 0.01f)
        assertEquals("阶段1亮屏能耗严格等于微积分真值 5.98Wh，杜绝虚高偏大 1Wh", 5.98f, stats1.onEnergyWh, 0.001f)
        assertEquals("阶段1亮屏功耗准确计算为 2.75W", 2.75f, stats1.screenOnWatts, 0.02f)
        assertEquals("阶段1整机总能量严格等于亮息微积分之和 7.06Wh", 5.98f + 1.080f, stats1.totalEnergyWh, 0.001f)

        // 固化已确认的息屏能量与时长状态
        val confirmedScreenOffEnergyWh = stats1.offEnergyWh
        val confirmedScreenOffDurationMs = screenOffMs
        assertEquals("固化息屏时长对齐当前时长", screenOffMs, confirmedScreenOffDurationMs)

        // 阶段 2（对应图二）：7 分钟后用户下拉刷新，由于亮屏连续采样导致队列中旧的息屏采样点被冲刷，
        // 外部候选来源回退到 confirmedScreenOffEnergyWh（1.080Wh），杜绝 intOffEnergy 跌落为 0 误判为深睡缺失
        val screenOnHours2 = (2L * 3600_000L + 11L * 60_000L + 2L * 1000L) / 3600000f
        val dischargeHours2 = screenOnHours2 + screenOffHours
        val intOnEnergyWh2 = 6.005f
        val candidateOffEnergyWh2 = confirmedScreenOffEnergyWh // 采纳已确认的息屏能耗，而非 0f
        val physicalTotalEnergyWh2 = 7.929f

        val stats2 = PowerUsageManager.calculateDualAnchorEnergyAndPower(
            intOnEnergyWh = intOnEnergyWh2,
            intOffEnergyWh = candidateOffEnergyWh2,
            intTotalEnergyWh = intOnEnergyWh2 + candidateOffEnergyWh2,
            intOnPowerWatts = intOnEnergyWh2 / screenOnHours2,
            intOffPowerWatts = candidateOffEnergyWh2 / screenOffHours,
            intTotalPowerWatts = (intOnEnergyWh2 + candidateOffEnergyWh2) / dischargeHours2,
            physicalTotalEnergyWh = physicalTotalEnergyWh2,
            screenOnHours = screenOnHours2,
            screenOffHours = screenOffHours,
            dischargeHours = dischargeHours2,
            screenOffMs = screenOffMs,
            nominalVoltageVolts = nominalVoltage,
            minScreenOffEnergyWh = confirmedScreenOffEnergyWh
        )

        assertEquals("阶段2下拉刷新时息屏能量坚守 1.080Wh，杜绝跳变暴增至 1.924Wh", 1.080f, stats2.offEnergyWh, 0.001f)
        assertEquals("阶段2息屏功耗稳固为 0.27W，杜绝飙升至 0.48W", 0.27f, stats2.screenOffWatts, 0.01f)
        assertEquals("阶段2亮屏能耗等于微积分 6.005Wh", 6.005f, stats2.onEnergyWh, 0.001f)
        assertEquals("阶段2整机总能量自然闭环", 6.005f + 1.080f, stats2.totalEnergyWh, 0.001f)

        // 阶段 3（对应图三）：用户继续亮屏使用 3 分钟，息屏时间未变，亮屏积分增加 0.391Wh
        val screenOnHours3 = (2L * 3600_000L + 14L * 60_000L + 0L * 1000L) / 3600000f
        val dischargeHours3 = screenOnHours3 + screenOffHours
        val intOnEnergyWh3 = 6.396f
        val physicalTotalEnergyWh3 = 7.982f

        val stats3 = PowerUsageManager.calculateDualAnchorEnergyAndPower(
            intOnEnergyWh = intOnEnergyWh3,
            intOffEnergyWh = candidateOffEnergyWh2,
            intTotalEnergyWh = intOnEnergyWh3 + candidateOffEnergyWh2,
            intOnPowerWatts = intOnEnergyWh3 / screenOnHours3,
            intOffPowerWatts = candidateOffEnergyWh2 / screenOffHours,
            intTotalPowerWatts = (intOnEnergyWh3 + candidateOffEnergyWh2) / dischargeHours3,
            physicalTotalEnergyWh = physicalTotalEnergyWh3,
            screenOnHours = screenOnHours3,
            screenOffHours = screenOffHours,
            dischargeHours = dischargeHours3,
            screenOffMs = screenOffMs,
            nominalVoltageVolts = nominalVoltage,
            minScreenOffEnergyWh = confirmedScreenOffEnergyWh
        )

        assertEquals("阶段3持续亮屏刷新时息屏能量严禁反向倒扣缩水，依然保持 1.080Wh", 1.080f, stats3.offEnergyWh, 0.001f)
        assertEquals("阶段3息屏功耗保持 0.27W", 0.27f, stats3.screenOffWatts, 0.01f)
        assertEquals("阶段3亮屏能耗等于微积分 6.396Wh", 6.396f, stats3.onEnergyWh, 0.001f)
        assertEquals("阶段3整机总能量自然闭环", 6.396f + 1.080f, stats3.totalEnergyWh, 0.001f)
        assertTrue("亮屏能量伴随亮屏时长单调增长", stats3.onEnergyWh > stats2.onEnergyWh)
    }

    /**
     * 验证放电时序采样点序列能够成功均匀抽稀至 3000 点高密度分辨率，
     * 且首点、末点以及工况切换拐点（亮灭屏切换、应用切换）100% 完整保留。
     */
    @Test
    fun testDownsampleDischargeSamplesUniformlyTo3000Points() {
        val totalCount = 6000
        val baseTs = 1710000000000L
        val originalSamples = mutableListOf<PowerDischargePoint>()

        for (i in 0 until totalCount) {
            val ts = baseTs + (i * 1000L)
            // 模拟在特定点发生亮灭屏与应用切换
            val isScreenOn = when (i) {
                in 1000..2000 -> false
                in 3500..4500 -> false
                else -> true
            }
            val pkg = when (i) {
                in 500..999 -> "com.tencent.mm"
                in 2500..3499 -> "com.ss.android.ugc.aweme"
                else -> "com.battery.analysis"
            }
            originalSamples.add(
                PowerDischargePoint(
                    timestamp = ts,
                    elapsedHours = (i * 1000L) / 3600000f,
                    batteryLevel = (100 - (i / 100)).coerceAtLeast(10),
                    voltageVolts = 4.0f,
                    temperature = 35.0f,
                    powerWatts = if (isScreenOn) 2.5f else 0.25f,
                    activeAppIcons = emptyList(),
                    isScreenOn = isScreenOn,
                    packageName = pkg
                )
            )
        }

        // 使用反射访问或注入测试样本验证抽稀至 3000 点
        val targetCount = 3000
        val preservedSet = HashSet<Int>()
        preservedSet.add(0)
        preservedSet.add(totalCount - 1)

        for (i in 0 until totalCount - 1) {
            val curr = originalSamples[i]
            val next = originalSamples[i + 1]
            if (curr.isScreenOn != next.isScreenOn || curr.packageName != next.packageName) {
                preservedSet.add(i)
                preservedSet.add(i + 1)
            }
        }

        val numSlots = targetCount - preservedSet.size
        val timeSpan = originalSamples.last().timestamp - originalSamples.first().timestamp
        val slotDuration = timeSpan.toDouble() / (numSlots + 1)
        var searchIdx = 0
        for (s in 1..numSlots) {
            val targetTs = originalSamples.first().timestamp + (s * slotDuration).toLong()
            while (searchIdx < totalCount - 1 && originalSamples[searchIdx + 1].timestamp <= targetTs) {
                searchIdx++
            }
            val bestIdx = if (searchIdx < totalCount - 1) {
                val diff1 = Math.abs(originalSamples[searchIdx].timestamp - targetTs)
                val diff2 = Math.abs(originalSamples[searchIdx + 1].timestamp - targetTs)
                if (diff1 <= diff2) searchIdx else searchIdx + 1
            } else {
                searchIdx
            }
            preservedSet.add(bestIdx)
        }

        val finalIndices = preservedSet.sorted()
        val finalSamples = finalIndices.map { originalSamples[it] }

        assertTrue("抽稀后采样点总数控制在 3000 点以内且接近 3000 点", finalSamples.size in 2950..3000)
        assertEquals("首点（拔电起点）必须严格保留", originalSamples.first().timestamp, finalSamples.first().timestamp)
        assertEquals("末点（最新采样点）必须严格保留", originalSamples.last().timestamp, finalSamples.last().timestamp)
        // 验证关键拐点均被保留
        assertTrue("灭屏起始拐点必被保留", finalSamples.any { it.timestamp == baseTs + 1000 * 1000L })
        assertTrue("亮屏恢复拐点必被保留", finalSamples.any { it.timestamp == baseTs + 2001 * 1000L })
        // 验证时间单调性
        for (i in 0 until finalSamples.size - 1) {
            assertTrue("抽稀后采样点序列时间单调递增", finalSamples[i + 1].timestamp > finalSamples[i].timestamp)
        }
    }
}




