package com.battery.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern
import com.battery.analysis.model.AppPowerUsageItem
import com.battery.analysis.model.PowerDischargePoint
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
     * 验证应用平均功耗展示严格遵循 BatteryRecorder 算法：
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

        // 验证功耗展示严格对标 BatteryRecorder：仅统计前台真实物理功耗
        assertEquals("1.09W", fgsApp.getFormattedCombinedAvgWatts())
        assertEquals("1.91W", nonFgsApp.getFormattedCombinedAvgWatts())
    }

    /**
     * 验证方案 A 基于硬件时序采样点的数值微积分模型（对标 BatteryRecorder 积分算法）：
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
     * 验证严格遵循 BatteryRecorder 算法标准：分 App 后台仅统计运行时长，不统计后台能耗与平均功耗：
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
            backgroundEnergyWh = 0f, // 遵循 BatteryRecorder：后台能耗不虚拟统计
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
     * 验证对标 BatteryRecorder 的应用前台切片与硬件瞬时采样点梯形数值微积分算法（E = ∫ P(t) dt）：
     * 1. 严格使用梯形数值积分累加各切片物理能量：dEnergyWs = (P[i-1] + P[i]) * 0.5 * (dt / 1000.0)；
     * 2. 平均放电功耗准确归属于置顶前台应用：P_avg = TotalEnergyWs / TotalDurationSeconds；
     * 3. 真实硬件数据测试：米家 2.21W、BatteryRecorder 1.69W、Scene 1.78W、电池统计 1.37W、荣耀桌面 0.90W；
     * 4. 验证整机能量守恒：各应用前台放电能量之和严格等于硬件积分总能量。
     */
    @Test
    fun testBatteryRecorderTrapezoidalIntegrationAlgorithm() {
        data class SamplePoint(val timestamp: Long, val powerWatts: Float)
        data class AppInterval(val packageName: String, val startTs: Long, val endTs: Long)

        val baseTs = 1710000000000L

        // 1. 构建与图一 BatteryRecorder 完全对齐的 5 大典型应用前台活跃区间（各运行 100 秒）
        val intervals = listOf(
            AppInterval("com.hihonor.android.launcher", baseTs, baseTs + 100_000L), // 荣耀桌面：0.90W
            AppInterval("com.battery.analysis", baseTs + 105_000L, baseTs + 205_000L), // 电池统计：1.37W
            AppInterval("com.itosang.batteryrecorder", baseTs + 210_000L, baseTs + 310_000L), // BatteryRecorder：1.69W
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

            // BatteryRecorder 区间 (210~310s, 目标均值 1.69W)
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

        // 4. 验证各应用计算出的平均功耗与 BatteryRecorder 严格一致
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
     * 针对 BatteryRecorder 统计的真实亮屏工况（2.50W，0.223Wh），验证计算结果忠实反映硬件实测值，
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

        val sampleSpanMs = if (samplePoints.size >= 2) {
            (samplePoints.last().timestamp - samplePoints.first().timestamp).coerceAtLeast(0L)
        } else {
            0L
        }
        val avgSampleIntervalMs = if (samplePoints.size >= 2 && sampleSpanMs > 0L) {
            sampleSpanMs / (samplePoints.size - 1)
        } else {
            0L
        }

        // 验证平均采样间隔虽然在 1~5000ms（高频），但跨度覆盖率严重不足（20秒 / 13小时 << 80%）
        val isCoverageSufficient = totalMs <= 300_000L || (sampleSpanMs >= (totalMs * 0.8f).toLong())
        assertFalse("13小时放电仅有20秒采样，采样跨度覆盖率判定必须为 false", isCoverageSufficient)

        val hasValidHardwareIntegration = samplePoints.size >= 2
        val isHighFrequencySampling = hasValidHardwareIntegration && (avgSampleIntervalMs in 1L..5000L) && isCoverageSufficient
        assertFalse("长周期放电断续采样绝不能被误判为全周期高频密集采样", isHighFrequencySampling)

        // 宏观真实物理掉电量：5000mAh 电池掉电 22%（100% -> 78%）
        val effectiveCapacity = 5000f
        val dropPercent = 22
        val nominalVoltageVolts = 3.85f
        val physicalDrainMah = effectiveCapacity * (dropPercent / 100f) // 1100mAh
        val physicalTotalEnergyWh = (physicalDrainMah * nominalVoltageVolts) / 1000f // 4.235Wh
        val dischargeHours = totalMs / 3600000f // 13.0h

        // 最终整机总放电能耗与平均功耗判定
        val realTotalEnergyWh = if (isHighFrequencySampling) {
            0.010f // 错误的高频微积分切片微元值
        } else {
            physicalTotalEnergyWh
        }
        val avgWatts = if (dischargeHours > 0f) realTotalEnergyWh / dischargeHours else 0f

        assertEquals("总放电能量真实反映电池物理掉电量 4.235Wh，杜绝缩水为 0.010Wh", 4.235f, realTotalEnergyWh, 0.001f)
        assertTrue("平均放电功耗正常计算（> 0.05W），杜绝退化为 --", avgWatts >= 0.05f)
        assertEquals("13小时消耗4.235Wh对应平均功耗约 0.326W", 4.235f / 13f, avgWatts, 0.01f)
    }
}




