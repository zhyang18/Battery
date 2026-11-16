package com.battery.analysis.domain.estimator

import com.battery.analysis.domain.estimator.model.AdaptiveWindowMetrics
import com.battery.analysis.domain.estimator.model.DataQuality
import com.battery.analysis.domain.estimator.model.DataSufficiencyLevel
import com.battery.analysis.domain.estimator.model.DischargeInterval
import com.battery.analysis.domain.estimator.model.PowerRegime
import com.battery.analysis.manager.PowerOverviewStats
import com.battery.analysis.model.PowerDischargePoint
import com.battery.analysis.model.PowerUsageRecord
import org.json.JSONArray
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * 历史放电采样与物理区间规范化归一器。
 * 负责从设备本地持久化的历史放电快照 [PowerUsageRecord] 及当前正在进行的活跃放电会话 [PowerOverviewStats] 中，
 * 按时间倒序解析并提取不重叠、时间单调的有效放电物理区间序列，
 * 严格过滤充电记录与异常数据，并在跨越 168 小时（7 天物理使用时间）边界时执行精确的物理等比裁剪，
 * 确保各区间放电功率物理守恒、能量严格守恒。
 */
class PowerSampleNormalizer {

    companion object {
        /** 7 天基准自然墙钟毫秒时长常量（7 * 24 * 3600 * 1000 毫秒 = 168 小时） */
        const val SEVEN_DAYS_MS: Long = 7L * 24L * 3600L * 1000L

        /** 7 天优先基准自然时间窗口毫秒数 */
        const val BASE_WINDOW_MS: Long = 7L * 24L * 3600L * 1000L

        /** 自适应历史回溯最大硬上限毫秒数（30 天自然时间 = 30 * 24 * 3600 * 1000 毫秒） */
        const val MAX_WINDOW_MS: Long = 30L * 24L * 3600L * 1000L

        /** 历史样本时间指数衰减半衰时间常数 tau（单位：天，默认 7.0 天） */
        const val TAU_AGE_DAYS: Double = 7.0

        /** 每天的物理毫秒数常量（24 * 3600 * 1000） */
        const val MS_PER_DAY: Double = 86_400_000.0

        /** 亮屏轻载工况最小稳定维持时间门槛（30 秒 = 30,000 ms，防微小波动碎片化） */
        const val MIN_LIGHT_SUSTAIN_MS: Long = 30_000L

        /** 亮屏持续重载最小稳定维持时间门槛（10 秒 = 10,000 ms，防短时脉冲误报） */
        const val MIN_HEAVY_SUSTAIN_MS: Long = 10_000L

        /** 亮屏采样点间最大允许连续时间跨度（5 分钟 = 300,000 ms） */
        const val MAX_ON_SEGMENT_GAP_MS: Long = 5L * 60L * 1000L

        /** 息屏待机采样点间最大允许连续时间跨度（24 小时） */
        const val MAX_OFF_SEGMENT_GAP_MS: Long = 24L * 3600L * 1000L

        /**
         * 从 JSON 序列化字符串中快速反序列化放电采样点列表。
         *
         * @param trendPointsJson 包含采样点对象的 JSON 字符串
         * @return 解析得到的放电采样点列表 [List]<[PowerDischargePoint]>，若为空或解析异常返回空列表
         */
        fun parseTrendPoints(trendPointsJson: String?): List<PowerDischargePoint> {
            if (trendPointsJson.isNullOrBlank() || trendPointsJson == "[]") return emptyList()
            val list = mutableListOf<PowerDischargePoint>()
            try {
                val jsonArray = JSONArray(trendPointsJson)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val ts = obj.optLong("ts", 0L)
                    if (ts <= 0L) continue
                    val elapsed = obj.optDouble("elapsed", 0.0).toFloat()
                    val lvl = obj.optInt("lvl", 100)
                    val volt = obj.optDouble("volt", 3.85).toFloat()
                    val temp = obj.optDouble("temp", 25.0).toFloat()
                    val pwr = obj.optDouble("pwr", 0.0).toFloat()
                    val isScreenOn = obj.optBoolean("screenOn", true)
                    list.add(
                        PowerDischargePoint(
                            timestamp = ts,
                            elapsedHours = elapsed,
                            batteryLevel = lvl,
                            voltageVolts = volt,
                            temperature = temp,
                            powerWatts = pwr,
                            isScreenOn = isScreenOn
                        )
                    )
                }
            } catch (_: Exception) {}
            return list
        }
    }

    /**
     * 从当前实时放电会话及历史归档记录中提取并构建滚动 168 小时（7 天）墙钟时间窗口内的有效放电物理区间列表。
     *
     * 核心逻辑升级（RuntimeModel v2.1）：
     * 严格将历史统计范围界定为当前时刻向前滚动 168 小时的真实自然时间窗口：[baseTimestamp - 168h, baseTimestamp]，
     * 仅在该窗口内筛选纯放电区段，并在边界区间跨越 168 小时墙钟起点时执行精确的物理等比截断，
     * 杜绝将“回看 7 天”错误实现为“累积凑满 168 小时放电时长”所导致的过度回溯历史陈旧机况（轻度使用甚至回溯一个半月前）。
     *
     * @param currentOverview 当前正在进行的活跃放电会话概览统计（若无传入 null）
     * @param allRecords 设备本地持久化的历史放电记录列表（已默认按时间倒序排列）
     * @param maxTargetDurationMs 最大墙钟回溯时间跨度上限（单位：毫秒，默认 168 小时）
     * @param baseTimestamp 统计基准时间戳（毫秒，默认当前系统时间）
     * @return 落在 168 小时滚动墙钟窗口内的有效放电物理区间列表 [List]<[DischargeInterval]>
     */
    /**
     * 将单次放电会话的高频时序采样点序列切片并重构为连贯的工况物理区间列表（v2.2 核心算法）。
     *
     * 遵循底层物理与控制论原则：
     * 1. 梯形能量微积分：dt = t_{k+1} - t_k, p = (p_k + p_{k+1}) / 2, dE = p * dt;
     * 2. 状态机与工况判定：按屏幕状态与瞬时功率分类；
     * 3. 尖峰抑制与防抖：重载需持续 >= 10s，瞬时脉冲吸收入普通；轻载需持续 >= 30s；
     * 4. 连贯合并：相邻同工况微区段合并为连续 DischargeInterval；
     * 5. 严格能量与时长守恒校准：若提供了会话已知的真实时长与真实能量，按比例平摊消除离散梯形积分离散误差。
     *
     * @param points 时序放电采样点列表
     * @param expectedScreenOnDurMs 会话记录中的真实总亮屏时长（单位：毫秒，若无传入 null）
     * @param expectedScreenOnEnergyWh 会话记录中的真实总亮屏能量（单位：Wh，若无传入 null）
     * @param expectedScreenOffDurMs 会话记录中的真实总息屏时长（单位：毫秒，若无传入 null）
     * @param expectedScreenOffEnergyWh 会话记录中的真实总息屏能量（单位：Wh，若无传入 null）
     * @return 切片并校准后的物理区间列表 [List]<[DischargeInterval]>，若采样点不足以切片返回空列表
     */
    fun sliceTrendPointsToIntervals(
        points: List<PowerDischargePoint>,
        expectedScreenOnDurMs: Long? = null,
        expectedScreenOnEnergyWh: Double? = null,
        expectedScreenOffDurMs: Long? = null,
        expectedScreenOffEnergyWh: Double? = null
    ): List<DischargeInterval> {
        val validPoints = points.filter { it.timestamp > 0L && it.powerWatts >= 0f }.sortedBy { it.timestamp }
        if (validPoints.size < 2) {
            return emptyList()
        }

        val classifier = PowerRegimeClassifier()

        // 内部可变中间切片结构
        data class MutableSlice(
            var startTs: Long,
            var endTs: Long,
            var durMs: Long,
            var energyWh: Double,
            var isScreenOn: Boolean,
            var regime: PowerRegime
        ) {
            val powerWatts: Double
                get() = if (durMs > 0L) energyWh / (durMs.toDouble() / 3600000.0) else 0.0
        }

        val rawSlices = mutableListOf<MutableSlice>()
        var currentSlice: MutableSlice? = null

        for (i in 0 until validPoints.size - 1) {
            val p1 = validPoints[i]
            val p2 = validPoints[i + 1]
            val dt = p2.timestamp - p1.timestamp
            if (dt <= 0L) continue

            val isScreenOn = p1.isScreenOn
            if (isScreenOn && dt > MAX_ON_SEGMENT_GAP_MS) {
                // 亮屏异常过大间隔，中断当前切片，跳过异常缺口
                currentSlice = null
                continue
            }
            if (!isScreenOn && dt > MAX_OFF_SEGMENT_GAP_MS) {
                currentSlice = null
                continue
            }

            val pwr1 = p1.powerWatts.toDouble()
            val pwr2 = p2.powerWatts.toDouble()
            val pAvg = if (pwr1 > 0.0 && pwr2 > 0.0) (pwr1 + pwr2) / 2.0 else max(pwr1, pwr2)
            if (pAvg <= 0.0) continue

            val dE = pAvg * (dt.toDouble() / 3600000.0)
            val tentativeInterval = DischargeInterval(
                startTimeMs = p1.timestamp,
                endTimeMs = p2.timestamp,
                durationMs = dt,
                energyWh = dE,
                powerWatts = pAvg,
                isScreenOn = isScreenOn,
                quality = DataQuality.VALID
            )
            val tentativeRegime = classifier.classify(tentativeInterval)

            val curr = currentSlice
            if (curr != null && curr.isScreenOn == isScreenOn && curr.regime == tentativeRegime && p1.timestamp == curr.endTs) {
                curr.endTs = p2.timestamp
                curr.durMs += dt
                curr.energyWh += dE
            } else {
                if (curr != null && curr.durMs > 0L) {
                    rawSlices.add(curr)
                }
                currentSlice = MutableSlice(
                    startTs = p1.timestamp,
                    endTs = p2.timestamp,
                    durMs = dt,
                    energyWh = dE,
                    isScreenOn = isScreenOn,
                    regime = tentativeRegime
                )
            }
        }
        currentSlice?.let { if (it.durMs > 0L) rawSlices.add(it) }

        if (rawSlices.isEmpty()) {
            return emptyList()
        }

        // 2. 防抖与持续时长约束判定：
        // 持续重载不足 10s 降级为普通中载；轻载不足 30s 降级为普通中载
        for (slice in rawSlices) {
            if (slice.isScreenOn) {
                if (slice.regime == PowerRegime.ON_HEAVY && slice.durMs < MIN_HEAVY_SUSTAIN_MS) {
                    slice.regime = PowerRegime.ON_NORMAL
                } else if (slice.regime == PowerRegime.ON_LIGHT && slice.durMs < MIN_LIGHT_SUSTAIN_MS) {
                    slice.regime = PowerRegime.ON_NORMAL
                }
            }
        }

        // 3. 再次合并相邻由于防抖降级变为相同工况的切片
        val consolidatedSlices = mutableListOf<MutableSlice>()
        for (slice in rawSlices) {
            val last = consolidatedSlices.lastOrNull()
            if (last != null && last.isScreenOn == slice.isScreenOn && last.regime == slice.regime && last.endTs == slice.startTs) {
                last.endTs = slice.endTs
                last.durMs += slice.durMs
                last.energyWh += slice.energyWh
            } else {
                consolidatedSlices.add(slice)
            }
        }

        // 4. 能量与时长物理守恒校准
        val onSlices = consolidatedSlices.filter { it.isScreenOn }
        val offSlices = consolidatedSlices.filter { !it.isScreenOn }

        val actualOnDur = onSlices.sumOf { it.durMs }
        val actualOnEnergy = onSlices.sumOf { it.energyWh }
        if (expectedScreenOnDurMs != null && expectedScreenOnDurMs > 0L && actualOnDur > 0L) {
            val timeScale = expectedScreenOnDurMs.toDouble() / actualOnDur.toDouble()
            val energyScale = if (expectedScreenOnEnergyWh != null && expectedScreenOnEnergyWh > 0.0 && actualOnEnergy > 0.0) {
                expectedScreenOnEnergyWh / actualOnEnergy
            } else {
                timeScale
            }
            if (timeScale in 0.70..1.45 && energyScale in 0.70..1.45) {
                for (s in onSlices) {
                    s.durMs = (s.durMs.toDouble() * timeScale).roundToLong().coerceAtLeast(1L)
                    s.energyWh = s.energyWh * energyScale
                }
            }
        }

        val actualOffDur = offSlices.sumOf { it.durMs }
        val actualOffEnergy = offSlices.sumOf { it.energyWh }
        if (expectedScreenOffDurMs != null && expectedScreenOffDurMs > 0L && actualOffDur > 0L) {
            val timeScale = expectedScreenOffDurMs.toDouble() / actualOffDur.toDouble()
            val energyScale = if (expectedScreenOffEnergyWh != null && expectedScreenOffEnergyWh > 0.0 && actualOffEnergy > 0.0) {
                expectedScreenOffEnergyWh / actualOffEnergy
            } else {
                timeScale
            }
            // 息屏待机状态特殊性：由于 AP 深度休眠 Suspend 期间无密集采样，离散梯形积分往往因唤醒瞬态而存在偏置；
            // 只要底层电量计提供了合法的真实物理总能量与总时长，必须执行物理守恒缩放，消除唤醒采样偏置（Wakeup Bias）
            if (timeScale in 0.50..2.00 && energyScale > 0.0 && energyScale.isFinite()) {
                for (s in offSlices) {
                    s.durMs = (s.durMs.toDouble() * timeScale).roundToLong().coerceAtLeast(1L)
                    s.energyWh = s.energyWh * energyScale
                }
            }
        }

        return consolidatedSlices.map {
            DischargeInterval(
                startTimeMs = it.startTs,
                endTimeMs = it.endTs,
                durationMs = it.durMs,
                energyWh = it.energyWh,
                powerWatts = it.powerWatts,
                isScreenOn = it.isScreenOn,
                quality = DataQuality.VALID
            )
        }
    }

    /**
     * 从当前实时放电会话及历史归档记录中提取并构建滚动 168 小时（7 天）墙钟时间窗口内的有效放电物理区间列表。
     * 支持传入实时采样点集或历史 trendPoints 进行细粒度工况切片（v2.2 架构）。
     *
     * @param currentOverview 当前正在进行的活跃放电会话概览统计（若无传入 null）
     * @param allRecords 设备本地持久化的历史放电记录列表（已默认按时间倒序排列）
     * @param maxTargetDurationMs 最大墙钟回溯时间跨度上限（单位：毫秒，默认 168 小时）
     * @param baseTimestamp 统计基准时间戳（毫秒，默认当前系统时间）
     * @param currentTrendPoints 当前活跃会话的时序采样点列表（可选，若提供则优先切片）
     * @return 落在 168 小时滚动墙钟窗口内的有效放电物理区间列表 [List]<[DischargeInterval]>
     */
    fun extractDischargeIntervals(
        currentOverview: PowerOverviewStats?,
        allRecords: List<PowerUsageRecord>,
        maxTargetDurationMs: Long = SEVEN_DAYS_MS,
        baseTimestamp: Long = System.currentTimeMillis(),
        currentTrendPoints: List<PowerDischargePoint>? = null
    ): List<DischargeInterval> {
        val intervals = mutableListOf<DischargeInterval>()
        val windowStartTs = baseTimestamp - maxTargetDurationMs

        /**
         * 内部辅助函数：向结果集合中安全追加物理区间。
         * 若区间完全落在 168 小时滚动墙钟窗口内则直接纳入；
         * 若跨越窗口边界（startTimeMs < windowStartTs 且 endTimeMs > windowStartTs），则在边界处等比截断；
         * 若区间完全早于窗口起始点（endTimeMs <= windowStartTs），则忽略并指示无需继续遍历更早记录。
         *
         * @param interval 待追加的完整放电物理区间
         * @return 是否已完全超出 168 小时墙钟窗口起始边界（true 为已早于窗口，无需继续遍历历史记录）
         */
        fun tryAppendInterval(interval: DischargeInterval): Boolean {
            if (interval.durationMs <= 0L || interval.energyWh <= 0.0 || !interval.powerWatts.isFinite()) {
                return false
            }

            // 区间完全早于 168 小时自然时间窗口起点，终止遍历
            if (interval.endTimeMs <= windowStartTs) {
                return true
            }

            if (interval.startTimeMs >= windowStartTs) {
                // 完全在窗口内
                intervals.add(interval)
                return false
            } else {
                // 跨越自然时间窗口边界：执行边界物理等比裁剪
                val spanMs = (interval.endTimeMs - interval.startTimeMs).coerceAtLeast(1L)
                val validSpanMs = (interval.endTimeMs - windowStartTs).coerceIn(0L, spanMs)
                if (validSpanMs > 0L) {
                    val ratio = (validSpanMs.toDouble() / spanMs.toDouble()).coerceIn(0.0, 1.0)
                    val clippedEnergy = interval.energyWh * ratio
                    val clippedDurationMs = (interval.durationMs.toDouble() * ratio).roundToLong().coerceAtLeast(1L)
                    val clippedInterval = DischargeInterval(
                        startTimeMs = windowStartTs,
                        endTimeMs = interval.endTimeMs,
                        durationMs = clippedDurationMs,
                        energyWh = clippedEnergy,
                        powerWatts = interval.powerWatts, // 平均功率保持守恒
                        isScreenOn = interval.isScreenOn,
                        quality = interval.quality,
                        ageWeight = interval.ageWeight
                    )
                    intervals.add(clippedInterval)
                }
                return true // 已经到达窗口边界，更早的记录无需继续遍历
            }
        }

        // 1. 优先解构当前正在进行的活跃会话
        if (currentOverview != null) {
            val now = baseTimestamp
            val onDur = currentOverview.screenOnDurationMs
            val onEnergy = if (currentOverview.screenOnEnergyWh > 0f) {
                currentOverview.screenOnEnergyWh.toDouble()
            } else if (currentOverview.screenOnPowerWatts > 0f && onDur > 0L) {
                currentOverview.screenOnPowerWatts.toDouble() * (onDur / 3600000.0)
            } else 0.0

            val offDur = currentOverview.screenOffDurationMs
            val offEnergy = if (currentOverview.screenOffEnergyWh > 0f) {
                currentOverview.screenOffEnergyWh.toDouble()
            } else if (currentOverview.screenOffPowerWatts > 0f && offDur > 0L) {
                currentOverview.screenOffPowerWatts.toDouble() * (offDur / 3600000.0)
            } else 0.0

            // 优先尝试基于高频采样点切片（v2.2 细粒度重构）
            val slicedCurrent = if (!currentTrendPoints.isNullOrEmpty()) {
                sliceTrendPointsToIntervals(
                    points = currentTrendPoints,
                    expectedScreenOnDurMs = onDur.takeIf { it > 0L },
                    expectedScreenOnEnergyWh = onEnergy.takeIf { it > 0.0 },
                    expectedScreenOffDurMs = offDur.takeIf { it > 0L },
                    expectedScreenOffEnergyWh = offEnergy.takeIf { it > 0.0 }
                )
            } else {
                emptyList()
            }

            val hasOnSlice = slicedCurrent.any { it.isScreenOn }
            val hasOffSlice = slicedCurrent.any { !it.isScreenOn }

            if (slicedCurrent.isNotEmpty()) {
                for (slice in slicedCurrent.sortedByDescending { it.endTimeMs }) {
                    val fullAppend = tryAppendInterval(slice)
                    if (fullAppend) return intervals
                }
            }

            // 若细粒度采样切片中缺少息屏区间（例如整段息屏期间都在深度休眠挂起未采到离散点），
            // 但宏观会话具备实测息屏时长与能量，必须宏观补齐，杜绝息屏物理数据丢失
            if (!hasOffSlice && offDur > 0L && offEnergy > 0.0) {
                val pOff = offEnergy / (offDur.toDouble() / 3600000.0)
                val fullAppend = tryAppendInterval(
                    DischargeInterval(
                        startTimeMs = now - onDur - offDur,
                        endTimeMs = now - onDur,
                        durationMs = offDur,
                        energyWh = offEnergy,
                        powerWatts = pOff,
                        isScreenOn = false,
                        quality = DataQuality.VALID
                    )
                )
                if (fullAppend) return intervals
            }

            // 若细粒度采样切片中缺少亮屏区间，宏观补齐
            if (!hasOnSlice && onDur > 0L && onEnergy > 0.0) {
                val pOn = onEnergy / (onDur.toDouble() / 3600000.0)
                val fullAppend = tryAppendInterval(
                    DischargeInterval(
                        startTimeMs = now - onDur,
                        endTimeMs = now,
                        durationMs = onDur,
                        energyWh = onEnergy,
                        powerWatts = pOn,
                        isScreenOn = true,
                        quality = DataQuality.VALID
                    )
                )
                if (fullAppend) return intervals
            }
        }

        // 2. 倒序解构历史归档记录（严格按有效结束物理时间戳降序排序）
        val eligibleRecords = allRecords.filter { record ->
            !record.isCharging && record.getEffectiveStartTimeMs() <= (baseTimestamp + 60_000L)
        }.sortedByDescending { it.getEffectiveEndTimeMs() }

        for (record in eligibleRecords) {
            // 若历史记录中包含未完结草稿且当前已存在实时会话，跳过旧草稿以最新实时数据为准
            if (!record.isCompleted && currentOverview != null) {
                continue
            }

            val recEndTs = record.getEffectiveEndTimeMs()
            val onDur = record.getScreenOnDurationMs()
            val onEnergy = if (record.screenOnEnergyWh > 0f) {
                record.screenOnEnergyWh.toDouble()
            } else if (record.screenOnPowerWatts > 0f && onDur > 0L) {
                record.screenOnPowerWatts.toDouble() * (onDur / 3600000.0)
            } else 0.0

            val offDur = record.getScreenOffDurationMs()
            val offEnergy = if (record.screenOffEnergyWh > 0f) {
                record.screenOffEnergyWh.toDouble()
            } else if (record.screenOffPowerWatts > 0f && offDur > 0L) {
                record.screenOffPowerWatts.toDouble() * (offDur / 3600000.0)
            } else 0.0

            // 优先尝试基于历史快照附带的高频采样点切片
            val slicedHistory = if (record.trendPointsJson.isNotBlank()) {
                val pts = parseTrendPoints(record.trendPointsJson)
                if (pts.size >= 2) {
                    sliceTrendPointsToIntervals(
                        points = pts,
                        expectedScreenOnDurMs = onDur.takeIf { it > 0L },
                        expectedScreenOnEnergyWh = onEnergy.takeIf { it > 0.0 },
                        expectedScreenOffDurMs = offDur.takeIf { it > 0L },
                        expectedScreenOffEnergyWh = offEnergy.takeIf { it > 0.0 }
                    )
                } else emptyList()
            } else emptyList()

            if (slicedHistory.isNotEmpty()) {
                var reachedLimit = false
                for (slice in slicedHistory.sortedByDescending { it.endTimeMs }) {
                    val isFull = tryAppendInterval(slice)
                    if (isFull) {
                        reachedLimit = true
                        break
                    }
                }
                if (reachedLimit) break
            } else {
                // 降级使用宏观记录：先归集本条记录的所有有效子区间，统一依序处理，防止息屏数据被提前截断丢弃
                var recordReachedLimit = false
                val subIntervals = mutableListOf<DischargeInterval>()

                if (onDur > 0L && onEnergy > 0.0) {
                    val pOn = onEnergy / (onDur.toDouble() / 3600000.0)
                    subIntervals.add(
                        DischargeInterval(
                            startTimeMs = recEndTs - onDur,
                            endTimeMs = recEndTs,
                            durationMs = onDur,
                            energyWh = onEnergy,
                            powerWatts = pOn,
                            isScreenOn = true,
                            quality = DataQuality.VALID
                        )
                    )
                }

                // 息屏细分：若具备深度休眠与唤醒的细分数据，则拆分为两个更具物理代表性的独立区间
                val hasDecomposed = record.isScreenOffDecomposedAvailable &&
                        (record.screenOffAwakeDurationMs > 0L || record.screenOffDeepSleepDurationMs > 0L)

                if (hasDecomposed) {
                    val awakeDur = record.screenOffAwakeDurationMs
                    val awakeEnergy = record.screenOffAwakeEnergyWh.toDouble()
                    if (awakeDur > 0L && awakeEnergy > 0.0) {
                        val pAwake = awakeEnergy / (awakeDur.toDouble() / 3600000.0)
                        subIntervals.add(
                            DischargeInterval(
                                startTimeMs = recEndTs - onDur - awakeDur,
                                endTimeMs = recEndTs - onDur,
                                durationMs = awakeDur,
                                energyWh = awakeEnergy,
                                powerWatts = pAwake,
                                isScreenOn = false,
                                quality = DataQuality.VALID
                            )
                        )
                    }

                    val sleepDur = record.screenOffDeepSleepDurationMs
                    val sleepEnergy = record.screenOffDeepSleepEnergyWh.toDouble()
                    if (sleepDur > 0L && sleepEnergy > 0.0) {
                        val pSleep = sleepEnergy / (sleepDur.toDouble() / 3600000.0)
                        subIntervals.add(
                            DischargeInterval(
                                startTimeMs = recEndTs - onDur - awakeDur - sleepDur,
                                endTimeMs = recEndTs - onDur - awakeDur,
                                durationMs = sleepDur,
                                energyWh = sleepEnergy,
                                powerWatts = pSleep,
                                isScreenOn = false,
                                quality = DataQuality.VALID
                            )
                        )
                    }
                } else {
                    // 无细分时作为综合息屏区间纳入
                    if (offDur > 0L && offEnergy > 0.0) {
                        val pOff = offEnergy / (offDur.toDouble() / 3600000.0)
                        subIntervals.add(
                            DischargeInterval(
                                startTimeMs = recEndTs - onDur - offDur,
                                endTimeMs = recEndTs - onDur,
                                durationMs = offDur,
                                energyWh = offEnergy,
                                powerWatts = pOff,
                                isScreenOn = false,
                                quality = DataQuality.VALID
                            )
                        )
                    }
                }

                for (sub in subIntervals) {
                    val isFull = tryAppendInterval(sub)
                    if (isFull) {
                        recordReachedLimit = true
                    }
                }
                if (recordReachedLimit) break
            }
        }

        return intervals
    }

    /**
     * 计算历史放电区间相对于当前基准时刻的时间衰减权重 w_age。
     * 7 天以内的区间为全额权重 1.0；
     * 超出 7 天（7 ~ 30 天）的区间按照指数函数平滑衰减：w_age = exp(-(a - 7.0) / tau)；
     * 超出 30 天的区间硬截断为 0.0。
     *
     * @param endTimestampMs 放电区间结束系统时间戳（毫秒）
     * @param baseTimestamp 当前统计基准系统时间戳（毫秒）
     * @param tauDays 时间衰减半衰时间常数（单位：天，默认 7.0 天）
     * @return 介于 0.0 至 1.0 之间的时间衰减权重
     */
    fun calculateAgeWeight(
        endTimestampMs: Long,
        baseTimestamp: Long,
        tauDays: Double = TAU_AGE_DAYS
    ): Double {
        if (endTimestampMs >= baseTimestamp) return 1.0
        val ageDays = (baseTimestamp - endTimestampMs).toDouble() / MS_PER_DAY
        return when {
            ageDays <= 7.0 -> 1.0
            ageDays <= 30.0 -> exp(-(ageDays - 7.0) / tauDays).coerceIn(0.01, 1.0)
            else -> 0.0
        }
    }

    /**
     * 评估已收集的历史放电区间在时间充分性与工况类别覆盖度上的数据充分性等级。
     *
     * 评定准则：
     * 1. 充足（SUFFICIENT）：亮屏有效时长 >= 6h、息屏有效时长 >= 12h、覆盖 >= 2 类基础工况且总时长 >= 18h；
     * 2. 基本可用（MODERATE）：总有效时长 >= 6h 且同时具备亮屏与息屏数据；
     * 3. 样本较少（INSUFFICIENT）：总有效时长 < 6h，或严重缺失单一核心工况。
     *
     * @param intervals 待评估的放电物理区间集合
     * @return 包含数据充分性等级 [DataSufficiencyLevel] 与覆盖核心工况类别数的二元组 [Pair]
     */
    fun evaluateSufficiency(intervals: List<DischargeInterval>): Pair<DataSufficiencyLevel, Int> {
        val onDur = intervals.filter { it.isScreenOn }.sumOf { it.durationMs }
        val offDur = intervals.filter { !it.isScreenOn }.sumOf { it.durationMs }
        val totalDur = intervals.sumOf { it.durationMs }

        var regimeCount = 0
        if (onDur > 0L) regimeCount++
        if (offDur > 0L) regimeCount++

        val sufficiency = when {
            onDur >= 6L * 3600_000L && offDur >= 12L * 3600_000L && totalDur >= 18L * 3600_000L && regimeCount >= 2 -> {
                DataSufficiencyLevel.SUFFICIENT
            }
            totalDur >= 6L * 3600_000L && onDur > 0L && offDur > 0L -> {
                DataSufficiencyLevel.MODERATE
            }
            else -> {
                DataSufficiencyLevel.INSUFFICIENT
            }
        }
        return Pair(sufficiency, regimeCount)
    }

    /**
     * 自适应历史时间窗口放电物理区间提取器（RuntimeModel v2.1 核心算法）。
     *
     * 遵循三级自适应阶梯分级架构（最近 7 天优先 + 不足扩展至 14 天 + 仍不足最多扩展至 30 天）：
     * 1. 第一优先级（近 7 天基准优先）：优先收集最近 168 小时（7 天自然墙钟）内的有效放电区间，全额权重（w_age = 1.0）；
     * 2. 第二优先级（充分性门控检查）：评估 7 天内样本充分性；若已满足充足条件则立即停止回溯，锁定统计范围为最近 7 天；
     * 3. 第三优先级（按需扩展至 14 天并衰减加权）：若 7 天样本不足，扩展至 14 天，超出 7 天的旧记录赋予时间衰减权重；若充足则锁定；
     * 4. 第四优先级（扩展至最多 30 天与硬上限截断）：若 14 天仍不足，最多扩展至 30 天；超出 30 天硬上限严格丢弃，若仍不足如实降低置信度。
     *
     * @param currentOverview 当前正在进行的活跃放电会话概览（若无传入 null）
     * @param allRecords 设备本地持久化的历史放电记录列表
     * @param baseTimestamp 统计基准时间戳（毫秒，默认当前系统时间）
     * @param tauDays 时间衰减半衰常数（单位：天，默认 7.0 天）
     * @param currentTrendPoints 当前活跃放电会话的采样点序列（可选）
     * @return 包含自适应提取的放电区间集合与对应窗口元信息指标的二元组 [Pair]<[List]<[DischargeInterval]>, [AdaptiveWindowMetrics]>
     */
    fun extractAdaptiveDischargeIntervals(
        currentOverview: PowerOverviewStats?,
        allRecords: List<PowerUsageRecord>,
        baseTimestamp: Long = System.currentTimeMillis(),
        tauDays: Double = TAU_AGE_DAYS,
        currentTrendPoints: List<PowerDischargePoint>? = null
    ): Pair<List<DischargeInterval>, AdaptiveWindowMetrics> {
        val selector = DefaultHistoricalWindowSelector(sampleNormalizer = this, tauDays = tauDays)
        val window = selector.select(
            nowMs = baseTimestamp,
            records = allRecords,
            currentOverview = currentOverview,
            currentTrendPoints = currentTrendPoints
        )
        return Pair(window.intervals, window.metrics)
    }
}
