package com.battery.analysis.domain.capacity.estimator

import com.battery.analysis.domain.capacity.integration.CoulombIntegrator
import com.battery.analysis.domain.capacity.model.CapacitySample
import com.battery.analysis.domain.capacity.model.DischargeSessionPhase
import com.battery.analysis.domain.capacity.model.SocBoundaryQuality
import com.battery.analysis.domain.capacity.model.TopPlateauMetrics

/**
 * 放电会话切分与顶部满电平台解耦结果实体。
 *
 * @property phase 放电会话达到的最终或主导阶段 [DischargeSessionPhase]
 * @property topPlateau 100% 满电顶部平台及过渡区独立统计指标
 * @property effectiveStartSoc 有效放电库仑积分区间的起始 SOC（如 0.99，已扣除平台与过渡区）
 * @property effectiveEndSoc 有效放电库仑积分区间的结束 SOC
 * @property effectiveDeltaSoc 有效放电区间确切对应的 SOC 变化跨度
 * @property effectiveSamples 属于有效放电积分区间的采样点序列（起点严格对齐有效起始时间戳）
 * @property anomalyReason 异常标识原因（如 SOC 突降跳变等，无异常则为 null）
 */
data class DischargeDecoupledResult(
    val phase: DischargeSessionPhase,
    val topPlateau: TopPlateauMetrics,
    val effectiveStartSoc: Double,
    val effectiveEndSoc: Double,
    val effectiveDeltaSoc: Double,
    val effectiveSamples: List<CapacitySample>,
    val anomalyReason: String? = null
)

/**
 * 放电会话顶部平台解耦与状态机演化器（DischargeSessionDecoupler）。
 *
 * 核心设计规范（遵循技术评审 P1-A 与 P1-B 细则）：
 * 1. 100% 满电平台期独立隔离：当放电从 100% SOC 启动时，维持 100% 期间放出的物理电荷独立记录，严禁计入有效放电 FCC 计算分子；
 * 2. 连续采样防抖验证（方式 A）：要求连续 2 次采样确认 SOC 已稳定低于 100%。当第二次确认有效后，以第一次跌落点作为有效放电起点；
 * 3. 抖动电荷忠实保留：若出现 100% -> 99% -> 100% 抖动回弹，回弹期间的真实物理电荷全部累加计入顶部平台（plateauMah），绝不丢失物理测量数据，并打上 JITTER 标记；
 * 4. 边界过渡区间隔离：将 100% 到稳定低于 100% 的过渡区间（100% -> 99%）独立隔离在 transitionMah 中，不强行塞入平台或有效放电；
 * 5. 积分时间戳与 SOC 严格对齐：有效放电区间的库仑微积分起止点 [t_effective, t_end] 与 SOC 变化区间 [SOC_effective, SOC_end] 分秒不差严格对齐；
 * 6. 突跳异常捕获：若拔电时直接从 100% 突跳至 97% 及以下（跨度 >= 3%），标记 SOC_START_JUMP 异常。
 */
object DischargeSessionDecoupler {

    /** 判定为 100% 满电平台门限阈值（浮点容差 0.995 以上视为 100%） */
    private const val TOP_PLATEAU_SOC_THRESHOLD: Double = 0.995

    /** 判定为 SOC 起始突跳的恶性门限（<= 97% 即跨度 >= 3%） */
    private const val SOC_START_JUMP_THRESHOLD: Double = 0.970

    /**
     * 对放电时序采样点序列执行 100% 顶部平台解耦、状态机转换与有效积分区间严格对齐。
     *
     * @param samples 原始放电采样序列（建议时间戳升序）
     * @param fallbackStartSoc 若采样点缺失 SOC 时的回退起始 SOC（0.0 ~ 1.0）
     * @param fallbackEndSoc 若采样点缺失 SOC 时的回退结束 SOC（0.0 ~ 1.0）
     * @return 放电会话解耦切分结果 [DischargeDecoupledResult]
     */
    fun decouple(
        samples: List<CapacitySample>,
        fallbackStartSoc: Double,
        fallbackEndSoc: Double
    ): DischargeDecoupledResult {
        if (samples.isEmpty()) {
            return DischargeDecoupledResult(
                phase = DischargeSessionPhase.IDLE,
                topPlateau = TopPlateauMetrics(),
                effectiveStartSoc = fallbackStartSoc,
                effectiveEndSoc = fallbackEndSoc,
                effectiveDeltaSoc = 0.0,
                effectiveSamples = emptyList(),
                anomalyReason = "放电采样序列为空"
            )
        }

        val sortedSamples = samples.sortedBy { it.timestampMs }
        val startSoc = sortedSamples.first().socFraction ?: fallbackStartSoc

        // 1. 若拔电起点未达到 100% 满电平台，直接进入有效放电阶段，无需经历平台解耦
        if (startSoc < TOP_PLATEAU_SOC_THRESHOLD) {
            val endSoc = sortedSamples.last().socFraction ?: fallbackEndSoc
            val deltaSoc = (startSoc - endSoc).coerceAtLeast(0.0)
            return DischargeDecoupledResult(
                phase = DischargeSessionPhase.VALID_DISCHARGE,
                topPlateau = TopPlateauMetrics(),
                effectiveStartSoc = startSoc,
                effectiveEndSoc = endSoc,
                effectiveDeltaSoc = deltaSoc,
                effectiveSamples = sortedSamples,
                anomalyReason = null
            )
        }

        // 2. 起始点为 100% 满电，初始进入 TOP_PLATEAU 状态机
        // 寻找稳定跌落点（连续 2 次采样确认已稳定脱离 100% 满电平台，方式 A）
        var stableDropIndex = -1
        var anomalyReason: String? = null
        var boundaryQuality = SocBoundaryQuality.STABLE

        var i = 1
        while (i < sortedSamples.size) {
            val currentSoc = sortedSamples[i].socFraction
            if (currentSoc != null && currentSoc < TOP_PLATEAU_SOC_THRESHOLD) {
                // 防抖判定：检查后续点是否维持或继续下降（非噪声回弹）
                if (i < sortedSamples.size - 1) {
                    val nextSoc = sortedSamples[i + 1].socFraction
                    if (nextSoc != null && nextSoc < TOP_PLATEAU_SOC_THRESHOLD && nextSoc <= (currentSoc + 0.005)) {
                        // 确认稳定下降（方式 A：以第一次跌落点作为有效放电起点）
                        stableDropIndex = i
                        // 检查是否存在恶性跳变突降
                        if (currentSoc <= SOC_START_JUMP_THRESHOLD) {
                            val jumpPercent = Math.round((1.0 - currentSoc) * 100.0)
                            anomalyReason = "SOC_START_JUMP: 起始SOC存在突降异常(从100%突跌至${Math.round(currentSoc * 100.0)}%, 跌幅${jumpPercent}%)"
                            boundaryQuality = SocBoundaryQuality.START_JUMP
                        }
                        break
                    } else {
                        // 紧随其后的采样点回弹至 100%，判定为单点瞬态抖动噪声，标记 JITTER，电荷在平台内完整累加
                        boundaryQuality = SocBoundaryQuality.JITTER
                        i++
                    }
                } else {
                    // 已经到达序列末尾，且仅有这 1 个点低于 100%，无法构成有效放电区间（至少需要 2 个有效点）
                    break
                }
            }
            i++
        }

        // 3. 若遍历结束未找到稳定跌落点，说明整段会话全程处于 100% 满电平台
        if (stableDropIndex < 0) {
            val fullPlateauSegment = CoulombIntegrator.integrate(sortedSamples)
            val durationSec = ((sortedSamples.last().timestampMs - sortedSamples.first().timestampMs) / 1000L).coerceAtLeast(0L)
            val topPlateau = TopPlateauMetrics(
                plateauMah = fullPlateauSegment.chargeMah,
                plateauWh = fullPlateauSegment.energyWh,
                transitionMah = 0.0,
                transitionWh = 0.0,
                durationSeconds = durationSec,
                sampleCount = sortedSamples.size,
                boundaryQuality = boundaryQuality
            )
            return DischargeDecoupledResult(
                phase = DischargeSessionPhase.TOP_PLATEAU,
                topPlateau = topPlateau,
                effectiveStartSoc = 1.0,
                effectiveEndSoc = 1.0,
                effectiveDeltaSoc = 0.0,
                effectiveSamples = emptyList(),
                anomalyReason = "放电全程处于100%满电平台，无有效SOC变化跨度"
            )
        }

        // 4. 存在稳定有效跌落点，执行平台与过渡区间切分
        val plateauEndIndex = stableDropIndex - 1 // 最后一个 100% 的点索引
        val plateauSegment = if (plateauEndIndex >= 1) {
            CoulombIntegrator.integrate(sortedSamples.subList(0, plateauEndIndex + 1))
        } else {
            // 仅第 0 个点为 100%，无足够跨度构成平台梯形积分
            null
        }

        val plateauDurationSec = if (plateauEndIndex >= 1) {
            ((sortedSamples[plateauEndIndex].timestampMs - sortedSamples.first().timestampMs) / 1000L).coerceAtLeast(0L)
        } else 0L

        // 过渡区间积分：从最后一个 100% 点到首次确认稳定低于 100% 的点之间
        val transitionSegment = CoulombIntegrator.integrate(
            listOf(sortedSamples[plateauEndIndex], sortedSamples[stableDropIndex])
        )

        val topPlateau = TopPlateauMetrics(
            plateauMah = plateauSegment?.chargeMah ?: 0.0,
            plateauWh = plateauSegment?.energyWh ?: 0.0,
            transitionMah = transitionSegment.chargeMah,
            transitionWh = transitionSegment.energyWh,
            durationSeconds = plateauDurationSec,
            sampleCount = plateauEndIndex + 1,
            boundaryQuality = boundaryQuality
        )

        // 5. 截取有效放电区间 [stableDropIndex .. last]
        val effectiveSamples = sortedSamples.subList(stableDropIndex, sortedSamples.size)
        val effectiveStartSoc = sortedSamples[stableDropIndex].socFraction ?: 0.99
        val effectiveEndSoc = sortedSamples.last().socFraction ?: fallbackEndSoc
        val effectiveDeltaSoc = (effectiveStartSoc - effectiveEndSoc).coerceAtLeast(0.0)

        return DischargeDecoupledResult(
            phase = DischargeSessionPhase.VALID_DISCHARGE,
            topPlateau = topPlateau,
            effectiveStartSoc = effectiveStartSoc,
            effectiveEndSoc = effectiveEndSoc,
            effectiveDeltaSoc = effectiveDeltaSoc,
            effectiveSamples = effectiveSamples,
            anomalyReason = anomalyReason
        )
    }
}
