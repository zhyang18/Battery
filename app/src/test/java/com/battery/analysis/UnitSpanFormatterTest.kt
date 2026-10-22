package com.battery.analysis

import com.battery.analysis.util.UnitSpanFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UnitSpanFormatter] 单元测试用例集。
 * 验证对各种电池、物理与时间单位的精准识别与缩小格式化处理。
 */
class UnitSpanFormatterTest {

    /**
     * 测试电量、健康度与增量百分比单位的识别与匹配。
     */
    @Test
    fun testPercentUnitMatching() {
        val matches1 = UnitSpanFormatter.UNIT_REGEX.findAll("85%").toList()
        assertEquals(1, matches1.size)
        assertEquals("%", matches1[0].value.trim())

        val matches2 = UnitSpanFormatter.UNIT_REGEX.findAll("98.50%").toList()
        assertEquals(1, matches2.size)
        assertEquals("%", matches2[0].value.trim())

        val matches3 = UnitSpanFormatter.UNIT_REGEX.findAll("25m · 20%~85%(+65%)").toList()
        // 包含 25m 的 m, 20% 的 %, 85% 的 %, +65% 的 %
        val units = matches3.map { it.value.trim() }
        assertTrue(units.contains("%"))
        assertTrue(units.contains("m"))
    }

    /**
     * 测试电池温度、电压、电流与功率等物理量单位的识别与匹配。
     */
    @Test
    fun testPhysicalUnitsMatching() {
        val tempMatches = UnitSpanFormatter.UNIT_REGEX.findAll("38.5℃").toList()
        assertEquals(1, tempMatches.size)
        assertEquals("℃", tempMatches[0].value.trim())

        val voltMatches = UnitSpanFormatter.UNIT_REGEX.findAll("4200 mV (4.200 V)").toList()
        assertEquals(2, voltMatches.size)
        assertEquals("mV", voltMatches[0].value.trim())
        assertEquals("V", voltMatches[1].value.trim())

        val currMatches = UnitSpanFormatter.UNIT_REGEX.findAll("+1500 mA / -320 mA").toList()
        assertEquals(2, currMatches.size)
        assertEquals("mA", currMatches[0].value.trim())
        assertEquals("mA", currMatches[1].value.trim())

        val powerMatches = UnitSpanFormatter.UNIT_REGEX.findAll("-2.70W / +33.20W").toList()
        assertEquals(2, powerMatches.size)
        assertEquals("W", powerMatches[0].value.trim())
        assertEquals("W", powerMatches[1].value.trim())
    }

    /**
     * 测试容量与能量等复合括号文本的识别与匹配。
     */
    @Test
    fun testCapacityAndEnergyMatching() {
        val text = "5000.0 mAh (19.25 Wh)"
        val matches = UnitSpanFormatter.UNIT_REGEX.findAll(text).toList()
        assertEquals(2, matches.size)
        assertEquals("mAh", matches[0].value.trim())
        assertEquals("Wh", matches[1].value.trim())
    }

    /**
     * 测试放电与充电速率（%/h）复合单位的识别。
     */
    @Test
    fun testRateUnitMatching() {
        val matches = UnitSpanFormatter.UNIT_REGEX.findAll("5.6%/h").toList()
        assertEquals(1, matches.size)
        assertEquals("%/h", matches[0].value.trim())
    }

    /**
     * 测试单个数值与单位字符串的拆分功能。
     */
    @Test
    fun testSplitValueAndUnit() {
        val p1 = UnitSpanFormatter.splitValueAndUnit("+18.50W")
        assertNotNull(p1)
        assertEquals("+18.50", p1?.first)
        assertEquals("W", p1?.second)

        val p2 = UnitSpanFormatter.splitValueAndUnit("35.2℃")
        assertNotNull(p2)
        assertEquals("35.2", p2?.first)
        assertEquals("℃", p2?.second)

        val p3 = UnitSpanFormatter.splitValueAndUnit("5000mAh")
        assertNotNull(p3)
        assertEquals("5000", p3?.first)
        assertEquals("mAh", p3?.second)

        val pNull = UnitSpanFormatter.splitValueAndUnit("--")
        assertNull(pNull)
    }

    /**
     * 测试普通英文单词与非单位数据不受误匹配影响。
     */
    @Test
    fun testNoFalsePositiveMatching() {
        val text = "Android 14 (Shizuku 正常)"
        val matches = UnitSpanFormatter.UNIT_REGEX.findAll(text).toList()
        assertTrue(matches.isEmpty())
    }

    /**
     * 测试判断文本区间是否处于成对圆括号内部的功能。
     */
    @Test
    fun testIsInsideParentheses() {
        val text1 = "05h31m(91.6%)"
        // 'h' 在下标 2..3，不在括号内
        val isInsideH = UnitSpanFormatter.isInsideParentheses(text1, 2, 3)
        org.junit.Assert.assertFalse(isInsideH)

        // '%' 在下标 11..12，在括号内
        val percentIdx = text1.indexOf('%')
        val isInsidePercent = UnitSpanFormatter.isInsideParentheses(text1, percentIdx, percentIdx + 1)
        assertTrue(isInsidePercent)

        // 复杂文本: "46m · 76%~69%(-7%)"
        val text2 = "46m · 76%~69%(-7%)"
        val lastPercentIdx = text2.lastIndexOf('%')
        val isInsideLastPercent = UnitSpanFormatter.isInsideParentheses(text2, lastPercentIdx, lastPercentIdx + 1)
        assertTrue(isInsideLastPercent)

        // 括号外的 76% 的 %
        val firstPercentIdx = text2.indexOf('%')
        val isInsideFirstPercent = UnitSpanFormatter.isInsideParentheses(text2, firstPercentIdx, firstPercentIdx + 1)
        org.junit.Assert.assertFalse(isInsideFirstPercent)
    }
}
