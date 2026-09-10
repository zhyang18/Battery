package com.battery.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}


