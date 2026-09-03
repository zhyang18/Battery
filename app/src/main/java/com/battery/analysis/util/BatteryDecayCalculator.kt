package com.battery.analysis.util

import com.battery.analysis.model.DecayStatistics
import com.battery.analysis.model.HealthTrendPoint
import com.battery.analysis.model.PeriodicDecayItem
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.max

/**
 * 电池健康度衰减与充放电循环消耗计算与统计工具类。
 * 提供从历史快照趋势点集中分析健康度日/月/年衰减率、循环次数日/月/年消耗速率以及按自然周期（日、月、年）聚合统计的核心算法。
 */
object BatteryDecayCalculator {

    private val dateFormatThreadLocal = object : ThreadLocal<SimpleDateFormat>() {
        /**
         * 初始化线程独立的 SimpleDateFormat 实例。
         *
         * @return SimpleDateFormat 实例
         */
        override fun initialValue(): SimpleDateFormat {
            return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        }
    }

    /**
     * 根据传入的健康度采样点列表计算全量衰减与循环消耗统计数据。
     *
     * @param points 按时间或ID排序的健康度趋势点列表
     * @return 计算构建完成的 [DecayStatistics] 统计结果对象
     */
    fun calculate(points: List<HealthTrendPoint>): DecayStatistics {
        if (points.isEmpty()) {
            return DecayStatistics()
        }

        // 过滤有效健康度数据点，并按时间自然字典序正序排序（格式为 yyyy-MM-dd HH:mm:ss 天然单调递增，彻底消除排序时上千次 SimpleDateFormat parse 开销）
        val validPoints = points.filter { it.health > 0f }.sortedWith(Comparator { a, b ->
            val cmp = a.captureTime.compareTo(b.captureTime)
            if (cmp != 0) cmp else a.id.compareTo(b.id)
        })

        val totalPoints = validPoints.size
        if (totalPoints == 0) {
            return DecayStatistics()
        }

        val firstPt = validPoints.first()
        val lastPt = validPoints.last()

        val firstTimeMillis = parseTimeToMillis(firstPt.captureTime, firstPt.id)
        val lastTimeMillis = parseTimeToMillis(lastPt.captureTime, lastPt.id)

        // 聚合按日、月、年的实际衰减与循环明细列表
        val dailyItems = groupPointsByPeriod(validPoints, PeriodType.DAY)
        val monthlyItems = groupPointsByPeriod(validPoints, PeriodType.MONTH)
        val yearlyItems = groupPointsByPeriod(validPoints, PeriodType.YEAR)

        // 提取包含有效循环次数的数据点集合
        val cyclePoints = validPoints.filter { it.cycleCount != null }
        var firstCycle: Int? = null
        var lastCycle: Int? = null
        var totalCycleChange: Int? = null
        var dailyCycleRate: Float? = null
        var monthlyCycleRate: Float? = null
        var yearlyCycleRate: Float? = null
        var hasCycleData = false

        if (cyclePoints.size >= 2) {
            val firstCyclePt = cyclePoints.first()
            val lastCyclePt = cyclePoints.last()
            firstCycle = firstCyclePt.cycleCount
            lastCycle = lastCyclePt.cycleCount

            if (firstCycle != null && lastCycle != null) {
                totalCycleChange = lastCycle - firstCycle

                val cycleSpanMillis = max(
                    0L,
                    parseTimeToMillis(lastCyclePt.captureTime, lastCyclePt.id) -
                            parseTimeToMillis(firstCyclePt.captureTime, firstCyclePt.id)
                )
                val rawCycleDays = cycleSpanMillis / (1000.0 * 60 * 60 * 24)
                val cycleSpanDays = if (rawCycleDays <= 0.0) 0.01 else rawCycleDays

                // 每日平均循环消耗量 (次/天)
                dailyCycleRate = (totalCycleChange / cycleSpanDays).toFloat()
                // 每月平均循环消耗量 (次/月，按平均每月 30.4375 天折算)
                monthlyCycleRate = dailyCycleRate * 30.4375f
                // 每年预估循环消耗量 (次/年，按每年 365.25 天折算)
                yearlyCycleRate = dailyCycleRate * 365.25f
                hasCycleData = true
            }
        } else if (cyclePoints.size == 1) {
            firstCycle = cyclePoints.first().cycleCount
            lastCycle = cyclePoints.first().cycleCount
            totalCycleChange = 0
        }

        if (totalPoints < 2) {
            return DecayStatistics(
                totalPoints = totalPoints,
                firstTime = firstPt.captureTime,
                lastTime = lastPt.captureTime,
                spanDays = 0.0,
                totalDecay = 0f,
                dailyDecayRate = null,
                monthlyDecayRate = null,
                yearlyDecayRate = null,
                firstCycle = firstCycle,
                lastCycle = lastCycle,
                totalCycleChange = totalCycleChange,
                dailyCycleRate = null,
                monthlyCycleRate = null,
                yearlyCycleRate = null,
                dailyItems = dailyItems,
                monthlyItems = monthlyItems,
                yearlyItems = yearlyItems,
                hasSufficientData = false,
                hasCycleData = false
            )
        }

        // 时间跨度（单位：毫秒）
        val spanMillis = max(0L, lastTimeMillis - firstTimeMillis)
        // 转换为天数（至少保留0.01天以防止除以零异常）
        val rawDays = spanMillis / (1000.0 * 60 * 60 * 24)
        val spanDays = if (rawDays <= 0.0) 0.01 else rawDays

        // 总衰减量：首个快照健康度 - 最新快照健康度（正数代表健康度下降损耗）
        val totalDecay = firstPt.health - lastPt.health

        // 每日平均衰减速率 (%/天)
        val dailyRate = (totalDecay / spanDays).toFloat()
        // 每月平均衰减速率 (%/月，按平均每月 30.4375 天折算)
        val monthlyRate = dailyRate * 30.4375f
        // 每年平均衰减速率 (%/年，按每年 365.25 天折算)
        val yearlyRate = dailyRate * 365.25f

        return DecayStatistics(
            totalPoints = totalPoints,
            firstTime = firstPt.captureTime,
            lastTime = lastPt.captureTime,
            spanDays = spanDays,
            totalDecay = totalDecay,
            dailyDecayRate = dailyRate,
            monthlyDecayRate = monthlyRate,
            yearlyDecayRate = yearlyRate,
            firstCycle = firstCycle,
            lastCycle = lastCycle,
            totalCycleChange = totalCycleChange,
            dailyCycleRate = dailyCycleRate,
            monthlyCycleRate = monthlyCycleRate,
            yearlyCycleRate = yearlyCycleRate,
            dailyItems = dailyItems,
            monthlyItems = monthlyItems,
            yearlyItems = yearlyItems,
            hasSufficientData = true,
            hasCycleData = hasCycleData
        )
    }

    /**
     * 将时间字符串解析为自纪元以来的毫秒时间戳。
     *
     * @param timeStr 待解析的时间字符串（如 "2026-08-18 16:15:46"）
     * @param fallbackId 解析失败时使用的回退时间戳 ID
     * @return 解析得出的毫秒时间戳 [Long]
     */
    fun parseTimeToMillis(timeStr: String, fallbackId: Long): Long {
        return try {
            dateFormatThreadLocal.get()?.parse(timeStr)?.time ?: fallbackId
        } catch (_: Exception) {
            fallbackId
        }
    }

    /**
     * 周期聚合类型枚举。
     */
    private enum class PeriodType {
        DAY, MONTH, YEAR
    }

    /**
     * 将数据点按照指定的自然周期（日/月/年）进行分组聚合，并统计各周期的起止健康度、衰减损耗及起止循环。
     *
     * @param points 按时间正序排列的健康度点列表
     * @param type 周期聚合类型
     * @return 聚合计算得到的 [PeriodicDecayItem] 列表（按时间倒序排列便于用户优先查阅最新周期）
     */
    private fun groupPointsByPeriod(
        points: List<HealthTrendPoint>,
        type: PeriodType
    ): List<PeriodicDecayItem> {
        val groupedMap = LinkedHashMap<String, MutableList<HealthTrendPoint>>()

        for (pt in points) {
            val key = getPeriodKey(pt.captureTime, type)
            groupedMap.getOrPut(key) { mutableListOf() }.add(pt)
        }

        val result = mutableListOf<PeriodicDecayItem>()
        for ((key, group) in groupedMap) {
            val startPoint = group.first()
            val endPoint = group.last()
            val startHealth = startPoint.health
            val endHealth = endPoint.health
            val decay = startHealth - endHealth
            val label = formatPeriodLabel(key, type)

            val groupCyclePoints = group.filter { it.cycleCount != null }
            val startCycle = groupCyclePoints.firstOrNull()?.cycleCount
            val endCycle = groupCyclePoints.lastOrNull()?.cycleCount
            val cycleChange = if (startCycle != null && endCycle != null) endCycle - startCycle else null

            result.add(
                PeriodicDecayItem(
                    periodKey = key,
                    periodLabel = label,
                    startHealth = startHealth,
                    endHealth = endHealth,
                    decay = decay,
                    sampleCount = group.size,
                    startCycle = startCycle,
                    endCycle = endCycle,
                    cycleChange = cycleChange
                )
            )
        }

        // 倒序排列，最新周期排在最前面
        return result.reversed()
    }

    /**
     * 从完整时间戳中截取对应周期的分组键值。
     *
     * @param timeStr 完整时间字符串（如 "2026-08-18 16:15:46"）
     * @param type 周期类型
     * @return 周期键值字符串（如 "2026-08-18"、"2026-08" 或 "2026"）
     */
    private fun getPeriodKey(timeStr: String, type: PeriodType): String {
        return when (type) {
            PeriodType.DAY -> {
                if (timeStr.length >= 10) timeStr.substring(0, 10) else timeStr
            }
            PeriodType.MONTH -> {
                if (timeStr.length >= 7) timeStr.substring(0, 7) else timeStr
            }
            PeriodType.YEAR -> {
                if (timeStr.length >= 4) timeStr.substring(0, 4) else timeStr
            }
        }
    }

    /**
     * 将周期键值格式化为便于用户理解的友好标签。
     *
     * @param key 周期键值
     * @param type 周期类型
     * @return 友好标签字符串（如 "2026-08-18"、"2026年08月"、"2026年度"）
     */
    private fun formatPeriodLabel(key: String, type: PeriodType): String {
        return when (type) {
            PeriodType.DAY -> key
            PeriodType.MONTH -> {
                if (key.length == 7 && key.contains("-")) {
                    val parts = key.split("-")
                    "${parts[0]}年${parts[1]}月"
                } else {
                    key
                }
            }
            PeriodType.YEAR -> {
                "${key}年度"
            }
        }
    }
}
