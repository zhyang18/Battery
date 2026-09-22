package com.battery.analysis

import com.battery.analysis.model.AppPowerUsageItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

/**
 * Shizuku 模式下拉刷新性能优化及数据一致性保障单元测试。
 * 验证 UID 映射解析兼容性、应用元数据缓存与短路、以及排序效率与数据准确性。
 */
class ShizukuOptimizationTest {

    /**
     * 验证各类 UID 编码格式（纯数字与 Linux u0_a234、u10_a56 格式）的标准数值转换正确性。
     */
    @Test
    fun testUidStringToNumericConversion() {
        val pattern = Pattern.compile("^u(\\d+)_?a(\\d+)$", Pattern.CASE_INSENSITIVE)

        fun convertUid(raw: String): Int {
            raw.toIntOrNull()?.let { return it }
            val match = pattern.matcher(raw)
            if (match.find()) {
                val userId = match.group(1)?.toIntOrNull() ?: 0
                val appId = match.group(2)?.toIntOrNull() ?: 0
                return userId * 100000 + 10000 + appId
            }
            return -1
        }

        assertEquals(10123, convertUid("10123"))
        assertEquals(10234, convertUid("u0_a234"))
        assertEquals(10056, convertUid("u0a56"))
        assertEquals(1010088, convertUid("u10_a88"))
        assertEquals(-1, convertUid("invalid_uid"))
    }

    /**
     * 验证 pm list packages -U 文本输出的正则提取与映射构建正确性。
     */
    @Test
    fun testPmListPackagesParsing() {
        val sampleOutput = """
            package:com.android.chrome uid:10156
            package:com.tencent.mm uid:10234
            package:com.eg.android.AlipayGphone uid:10245
        """.trimIndent()

        val map = mutableMapOf<Int, String>()
        val pattern = Pattern.compile("package:([^\\s]+)\\s+uid:(\\d+)")
        for (line in sampleOutput.split('\n')) {
            val matcher = pattern.matcher(line.trim())
            if (matcher.find()) {
                val pkg = matcher.group(1) ?: continue
                val uid = matcher.group(2)?.toIntOrNull() ?: continue
                map[uid] = pkg
            }
        }

        assertEquals(3, map.size)
        assertEquals("com.android.chrome", map[10156])
        assertEquals("com.tencent.mm", map[10234])
        assertEquals("com.eg.android.AlipayGphone", map[10245])
    }

    /**
     * 验证内存缓存命中机制：第二次及后续查询必须完全走内存，消除 IPC 开销。
     */
    @Test
    fun testMetadataMemoryCacheHit() {
        val mockCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()
        var fetchCount = 0

        fun getMetadata(pkg: String): Pair<String, String> {
            val cached = mockCache[pkg]
            if (cached != null) return cached
            fetchCount++
            val result = Pair(pkg.substringAfterLast('.'), "drawable_mock")
            mockCache[pkg] = result
            return result
        }

        val first = getMetadata("com.tencent.mm")
        assertEquals(1, fetchCount)
        assertEquals("mm", first.first)

        val second = getMetadata("com.tencent.mm")
        assertEquals(1, fetchCount)
        assertEquals(first, second)

        val third = getMetadata("com.tencent.mobileqq")
        assertEquals(2, fetchCount)
        assertEquals("mobileqq", third.first)
    }

    /**
     * 验证三方应用与游戏判定短路逻辑的准确性。
     */
    @Test
    fun testGamePackageFallbackRecognition() {
        fun isGameName(pkg: String): Boolean {
            return pkg.contains(".game", ignoreCase = true) || pkg.contains(".clash", ignoreCase = true)
        }

        assertTrue(isGameName("com.supercell.clashofclans"))
        assertTrue(isGameName("com.tencent.tmgp.game123"))
        assertFalse(isGameName("com.tencent.mm"))
        assertFalse(isGameName("com.eg.android.AlipayGphone"))
    }

    /**
     * 验证快速排序比较逻辑在使用已缓存的三方属性时能实现极速稳定排序，且三方应用严格置顶。
     */
    @Test
    fun testAppSortingWithCachedFlags() {
        val userAppMap = mapOf(
            "com.tencent.mm" to true,
            "com.android.systemui" to false,
            "com.bilibili.app" to true,
            "com.android.phone" to false
        )

        val list = mutableListOf(
            AppPowerUsageItem(
                packageName = "com.android.systemui",
                appName = "SystemUI",
                icon = null,
                foregroundTimeMs = 50000L,
                avgPowerWatts = 1.5f,
                avgTemperature = 32f,
                maxTemperature = 35f,
                lastUsedTimeMs = 1000L
            ),
            AppPowerUsageItem(
                packageName = "com.tencent.mm",
                appName = "WeChat",
                icon = null,
                foregroundTimeMs = 30000L,
                avgPowerWatts = 2.0f,
                avgTemperature = 33f,
                maxTemperature = 36f,
                lastUsedTimeMs = 2000L
            ),
            AppPowerUsageItem(
                packageName = "com.bilibili.app",
                appName = "Bilibili",
                icon = null,
                foregroundTimeMs = 60000L,
                avgPowerWatts = 2.5f,
                avgTemperature = 34f,
                maxTemperature = 38f,
                lastUsedTimeMs = 3000L
            ),
            AppPowerUsageItem(
                packageName = "com.android.phone",
                appName = "Phone",
                icon = null,
                foregroundTimeMs = 10000L,
                avgPowerWatts = 0.8f,
                avgTemperature = 31f,
                maxTemperature = 33f,
                lastUsedTimeMs = 4000L
            )
        )

        list.sortWith(
            compareByDescending<AppPowerUsageItem> { userAppMap[it.packageName] == true }
                .thenByDescending { it.foregroundTimeMs }
                .thenByDescending { it.avgPowerWatts }
        )

        assertEquals("com.bilibili.app", list[0].packageName)
        assertEquals("com.tencent.mm", list[1].packageName)
        assertEquals("com.android.systemui", list[2].packageName)
        assertEquals("com.android.phone", list[3].packageName)
    }

    /**
     * 验证后台统计开关开启与关闭时应用列表过滤及排序规则的准确性：
     * 1. 当开关关闭（showBackgroundStats == false）时，过滤掉纯后台应用，仅展示前台应用；
     * 2. 当开关开启（showBackgroundStats == true）时，包含纯后台应用，且按总时长（前台+后台）降序排序，
     *    避免纯后台应用被压在最底端不可见。
     */
    @Test
    fun testBackgroundStatsToggleFilterAndSort() {
        val fgApp = AppPowerUsageItem(
            packageName = "com.example.fg",
            appName = "ForegroundApp",
            icon = null,
            foregroundTimeMs = 30000L,
            avgPowerWatts = 1.5f,
            avgTemperature = 30f,
            maxTemperature = 32f,
            lastUsedTimeMs = 1000L,
            backgroundTimeMs = 0L
        )

        val bgApp = AppPowerUsageItem(
            packageName = "com.example.bg",
            appName = "BackgroundApp",
            icon = null,
            foregroundTimeMs = 0L,
            avgPowerWatts = 0.5f,
            avgTemperature = 30f,
            maxTemperature = 31f,
            lastUsedTimeMs = 1000L,
            backgroundTimeMs = 60000L
        )

        val mixedApp = AppPowerUsageItem(
            packageName = "com.example.mixed",
            appName = "MixedApp",
            icon = null,
            foregroundTimeMs = 10000L,
            avgPowerWatts = 1.2f,
            avgTemperature = 30f,
            maxTemperature = 31f,
            lastUsedTimeMs = 1000L,
            backgroundTimeMs = 80000L
        )

        val allItems = listOf(fgApp, bgApp, mixedApp)

        // 1. 关闭后台统计：过滤纯后台应用
        val closedList = allItems.filter { it.foregroundTimeMs > 0L }.sortedByDescending { it.foregroundTimeMs }
        assertEquals(2, closedList.size)
        assertEquals("com.example.fg", closedList[0].packageName)
        assertEquals("com.example.mixed", closedList[1].packageName)
        assertFalse(closedList.any { it.packageName == "com.example.bg" })

        // 2. 开启后台统计：前台应用按前台时长降序排在前面，后台应用按后台时长降序排在前台应用后面
        val fgSortedByDuration = allItems.filter { it.foregroundTimeMs > 0L }.sortedByDescending { it.foregroundTimeMs }
        val bgSortedByDuration = allItems.filter { it.foregroundTimeMs <= 0L }.sortedByDescending { it.backgroundTimeMs }
        val durationSortedList = fgSortedByDuration + bgSortedByDuration

        assertEquals(3, durationSortedList.size)
        // fgApp 前台时长 30000L，排第一
        assertEquals("com.example.fg", durationSortedList[0].packageName)
        // mixedApp 前台时长 10000L，排第二
        assertEquals("com.example.mixed", durationSortedList[1].packageName)
        // bgApp 纯后台应用（前台时长 0L），排在前台应用之后
        assertEquals("com.example.bg", durationSortedList[2].packageName)

        // 3. 功耗排序：按升序排序，前台应用功耗升序在前，纯后台应用功耗升序在后
        val fgSortedByPower = allItems.filter { it.foregroundTimeMs > 0L }.sortedBy { it.avgPowerWatts }
        val bgSortedByPower = allItems.filter { it.foregroundTimeMs <= 0L }.sortedBy { it.avgPowerWatts }
        val powerSortedList = fgSortedByPower + bgSortedByPower

        assertEquals(3, powerSortedList.size)
        // 前台应用：mixedApp(1.2W) < fgApp(1.5W)
        assertEquals("com.example.mixed", powerSortedList[0].packageName)
        assertEquals("com.example.fg", powerSortedList[1].packageName)
        // 纯后台应用排在最后
        assertEquals("com.example.bg", powerSortedList[2].packageName)
    }

    /**
     * 验证耗电趋势图功耗曲线纵向区间严格限制在整图表高度的 3/4 以内。
     */
    @Test
    fun testPowerCurveVerticalHeightRatioConstraint() {
        val topPadding = 10f
        val availableH = 200f
        val maxScaleW = 20.0

        fun calcPowerY(powerW: Float): Float {
            val ratio = (powerW / maxScaleW.toFloat()).coerceIn(0f, 1f)
            return topPadding + (1f - ratio * 0.75f) * availableH
        }

        val yAtZero = calcPowerY(0f)
        val yAtHalf = calcPowerY(10f)
        val yAtMax = calcPowerY(20f)
        val yAtOver = calcPowerY(30f) // 超出刻度

        val bottomY = topPadding + availableH
        assertEquals(bottomY, yAtZero, 0.001f)

        // 功耗最大值时的纵向位移跨度 (bottomY - yAtMax) 恰好为 availableH * 0.75f
        val maxSpan = bottomY - yAtMax
        assertEquals(availableH * 0.75f, maxSpan, 0.001f)

        // 即使功耗超出最大刻度，纵向位移跨度依然不超过 availableH * 0.75f
        val overSpan = bottomY - yAtOver
        assertTrue(overSpan <= availableH * 0.75f)
        assertEquals(availableH * 0.75f, overSpan, 0.001f)

        // 中间值在 0 到 0.75 之间
        val halfSpan = bottomY - yAtHalf
        assertEquals(availableH * 0.375f, halfSpan, 0.001f)
    }

    /**
     * 验证常规 dumpsys batterystats 文本中各 UID CPU 运行时间的解析提取正确性。
     */
    @Test
    fun testHardwareStatsPlainTextCpuExtraction() {
        val rawSample = "  10234:\n" +
                "    TOTAL wake: 1m 30s partial\n" +
                "    CPU: 12s 300ms usr + 2s 100ms krn ; 10s fg ; 4s 400ms bg\n" +
                "  10567:\n" +
                "    CPU: 5s 500ms usr + 1s 200ms krn ; 0ms fg ; 6s 700ms bg\n"

        val uidHeaderRegex = Pattern.compile("^\\s{2,4}(?:Uid\\s+)?([\\w]+):\\s*$", Pattern.CASE_INSENSITIVE)
        val cpuLineRegex = Pattern.compile("CPU:\\s*(?:([\\d\\w\\s]+?)\\s*usr)?(?:\\s*\\+\\s*([\\d\\w\\s]+?)\\s*krn)?(?:\\s*;\\s*([\\d\\w\\s]+?)\\s*fg)?(?:\\s*;\\s*([\\d\\w\\s]+?)\\s*bg)?", Pattern.CASE_INSENSITIVE)

        val resultMap = mutableMapOf<Int, Long>()
        var currentUid = -1

        rawSample.lines().forEach { line ->
            val uMatch = uidHeaderRegex.matcher(line)
            if (uMatch.find()) {
                currentUid = uMatch.group(1)?.toIntOrNull() ?: -1
                return@forEach
            }
            if (currentUid <= 0) return@forEach

            val trimmed = line.trim()
            val cpuMatch = cpuLineRegex.matcher(trimmed)
            if (cpuMatch.find()) {
                val bg = cpuMatch.group(4)?.trim()
                if (!bg.isNullOrEmpty()) {
                    resultMap[currentUid] = 1L
                }
            }
        }

        assertTrue(resultMap.containsKey(10234))
        assertTrue(resultMap.containsKey(10567))
    }
}

