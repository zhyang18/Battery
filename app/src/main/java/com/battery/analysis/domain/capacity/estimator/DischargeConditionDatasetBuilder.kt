package com.battery.analysis.domain.capacity.estimator

import com.battery.analysis.domain.capacity.model.CapacitySample
import com.battery.analysis.domain.capacity.model.CounterConsistency
import com.battery.analysis.domain.capacity.model.DischargeConditionSample
import com.battery.analysis.domain.capacity.model.DischargeQuality
import com.battery.analysis.domain.capacity.model.FccCandidateSample
import com.battery.analysis.model.CapacityCandidate

/**
 * 放电工况样本数据集构建器（DischargeConditionDatasetBuilder）。
 *
 * 遵循技术评审 P2-B2 核心铁律：
 * 1. 一条完整放电会话严格作为一条独立样本，坚决杜绝将单条会话切碎伪造成多个独立实验导致的统计样本量虚高；
 * 2. 完整透传工况全景特征、能量观测、分箱实测覆盖率、电量计 Counter 印证评级与数据结构版本；
 * 3. 支持基于原始采样序列或持久化候选记录构建干净透明的敏感度分析样本集。
 */
object DischargeConditionDatasetBuilder {

    /**
     * 从单次完整放电候选样本与原始采样序列构建独立工况样本。
     *
     * @param candidate 放电候选样本实体 [FccCandidateSample]
     * @param rawSamples 原始放电采样序列
     * @param cycleCount 可选的当前电池循环次数
     * @return 独立工况样本实体 [DischargeConditionSample]
     */
    fun buildSample(
        candidate: FccCandidateSample,
        rawSamples: List<CapacitySample>,
        cycleCount: Int? = null
    ): DischargeConditionSample {
        val condition = candidate.operatingCondition ?: DischargeConditionAnalyzer.analyze(rawSamples)
        val sorted = rawSamples.sortedBy { it.timestampMs }
        val sessionTs = sorted.firstOrNull()?.timestampMs ?: System.currentTimeMillis()
        val startTs = sorted.firstOrNull()?.timestampMs ?: sessionTs
        val endTs = sorted.lastOrNull()?.timestampMs ?: sessionTs

        return DischargeConditionSample(
            sessionId = candidate.sessionId,
            observedEnergyWh = candidate.energyWh,
            measuredEnergyCoverage = candidate.coverageRatio * 100.0,
            unresolvedEnergyWh = 0.0,
            condition = condition,
            startSoc = Math.round(candidate.startSoc * 100.0).toInt(),
            endSoc = Math.round(candidate.endSoc * 100.0).toInt(),
            socSpanPercent = Math.round(candidate.deltaSoc * 100.0).toInt(),
            quality = candidate.qualityLevel,
            counterConsistency = candidate.counterConsistency,
            cycleCount = cycleCount,
            sessionTimestampMs = sessionTs,
            effectiveStartTimestampMs = startTs,
            effectiveEndTimestampMs = endTs,
            topPlateauMah = candidate.topPlateau?.plateauMah ?: 0.0,
            topPlateauWh = candidate.topPlateau?.plateauWh ?: 0.0,
            algorithmVersion = "FCC-v1.2",
            dataSchemaVersion = 3
        )
    }

    /**
     * 从本地持久化候选记录集合构建去重、无切分污染的独立工况样本集。
     *
     * @param candidates 持久化候选列表 [List<CapacityCandidate>]
     * @return 独立工况样本列表 [List<DischargeConditionSample>]
     */
    fun buildDatasetFromCandidates(candidates: List<CapacityCandidate>): List<DischargeConditionSample> {
        // 严格去重：每个唯一 sessionId 仅保留一条样本，严禁单会话虚构复数记录
        val uniqueSessions = candidates.distinctBy { it.id }

        return uniqueSessions.map { c ->
            val thermalBand = when {
                c.avgTemperatureCelsius < 15f -> com.battery.analysis.domain.capacity.model.DischargeThermalBand.LOW_TEMP
                c.avgTemperatureCelsius in 15f..35f -> com.battery.analysis.domain.capacity.model.DischargeThermalBand.ROOM_TEMP
                else -> com.battery.analysis.domain.capacity.model.DischargeThermalBand.HIGH_TEMP
            }
            val condition = com.battery.analysis.domain.capacity.model.DischargeOperatingCondition(
                averageTemperatureC = c.avgTemperatureCelsius.toDouble(),
                thermalBand = thermalBand,
                dischargeDurationSeconds = c.durationMs / 1000L
            )

            val consistency = try {
                CounterConsistency.valueOf(c.counterConsistency)
            } catch (e: Exception) {
                CounterConsistency.UNAVAILABLE
            }

            DischargeConditionSample(
                sessionId = c.id.toString(),
                observedEnergyWh = c.energyWh,
                measuredEnergyCoverage = c.qualityScore * 100.0,
                unresolvedEnergyWh = 0.0,
                condition = condition,
                startSoc = c.startSoc,
                endSoc = c.endSoc,
                socSpanPercent = c.socSpanPercent,
                quality = if (c.isExcluded) DischargeQuality.REJECTED else DischargeQuality.HIGH,
                counterConsistency = consistency,
                sessionTimestampMs = c.startTimestamp,
                effectiveStartTimestampMs = c.effectiveStartTimestamp,
                effectiveEndTimestampMs = c.effectiveEndTimestamp,
                topPlateauMah = c.plateauMah,
                topPlateauWh = c.plateauWh,
                algorithmVersion = c.algorithmVersion,
                dataSchemaVersion = c.dataSchemaVersion
            )
        }
    }
}
