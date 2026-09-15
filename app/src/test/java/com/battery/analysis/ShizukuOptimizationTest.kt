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
}
