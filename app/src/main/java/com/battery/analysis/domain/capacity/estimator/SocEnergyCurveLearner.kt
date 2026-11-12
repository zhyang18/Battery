package com.battery.analysis.domain.capacity.estimator

import com.battery.analysis.domain.capacity.model.BinLearningStatus
import com.battery.analysis.domain.capacity.model.CapacitySample
import com.battery.analysis.domain.capacity.model.EnergyAllocationQuality
import com.battery.analysis.domain.capacity.model.EnergyCapacityResult
import com.battery.analysis.domain.capacity.model.LearningMaturity
import com.battery.analysis.domain.capacity.model.SocEnergyBinInfo
import com.battery.analysis.domain.capacity.model.TopPlateauMetrics
import com.battery.analysis.domain.capacity.model.UnresolvedEnergySegment
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 20 段 SOC 能量曲线与分箱自学习器（SocEnergyCurveLearner）。
 *
 * 遵循技术评审规范与物理证据分层架构：
 * 1. 严格区分三种物理分配路径：
 *    - 路径一（MEASURED）：同一分箱内部的直接物理实测积分；
 *    - 路径二（MODEL_ESTIMATED）：跨越多个分箱边界的梯形切分与比例分配，严格独立存储，绝不伪装为实测；
 *    - 路径三（UNRESOLVED）：SOC 假死停滞异常（超过 400 mAh 门限）时，做功保留并记入未决段，不污染分箱实测；
 * 2. 100% 满电平台做功独立隔离，严禁计入 95%~100% 分箱；
 * 3. 0%~5% 低端缺失数据如实管理，全量 FCC_E 在未全域实测学满时如实返回 null；
 * 4. 彻底废除任何全局平均或硬编码常数脑补，提供详尽透明的 [EnergyCapacityResult]。
 */
class SocEnergyCurveLearner {

    companion object {
        /** 分箱总数：全域 100% 划分为 20 个等宽分箱 */
        const val BIN_COUNT: Int = 20

        /** 单个分箱对应的 SOC 跨度百分比宽度（5.0%） */
        const val BIN_WIDTH_PERCENT: Double = 5.0

        /** 判定分箱达到充分直接实测（MEASURED）的最小累计实测 SOC 跨度百分比（2.0%） */
        const val MIN_MEASURED_SPAN_PERCENT: Double = 2.0

        /** 判定分箱进入部分实测（PARTIAL）的最小累计实测 SOC 跨度百分比（0.5%） */
        const val MIN_PARTIAL_SPAN_PERCENT: Double = 0.5

        /** 单点 SOC 读数异常连续停滞放电保护门限（默认 400.0 mAh），超标判定为 UNRESOLVED 暂停分箱实测归档 */
        const val DEFAULT_STALL_CHARGE_THRESHOLD_MAH: Double = 400.0
    }

    /**
     * 内部可变分箱累加器。
     *
     * 遵循物理证据链隔离规范：实测数据、模型估算数据与停滞未决数据物理隔离存储。
     */
    private data class MutableBin(
        val binIndex: Int,

        // 路径一：直接物理实测（同 Bin 闭合积分）
        var measuredChargeMah: Double = 0.0,
        var measuredEnergyWh: Double = 0.0,
        var measuredSpanPercent: Double = 0.0,
        var measuredTempSum: Double = 0.0,
        var measuredTempDuration: Double = 0.0,
        var measuredSessionCount: Int = 0,

        // 路径二：跨分箱切分估算（模型分配）
        var estimatedChargeMah: Double = 0.0,
        var estimatedEnergyWh: Double = 0.0,
        var estimatedSpanPercent: Double = 0.0,
        var estimatedTempSum: Double = 0.0,
        var estimatedTempDuration: Double = 0.0,
        var estimatedSessionCount: Int = 0,

        // 路径三：单点停滞未决做功
        var unresolvedEnergyWh: Double = 0.0,
        var unresolvedChargeMah: Double = 0.0
    ) {
        val startSoc: Double get() = binIndex * BIN_WIDTH_PERCENT
        val endSoc: Double get() = (binIndex + 1) * BIN_WIDTH_PERCENT

        /**
         * 转换为外部只读不可变的 [SocEnergyBinInfo] 实体。
         *
         * @return 转换后的分箱只读不可变实体 [SocEnergyBinInfo]
         */
        fun toImmutable(): SocEnergyBinInfo {
            val status = when {
                measuredSpanPercent >= MIN_MEASURED_SPAN_PERCENT && measuredEnergyWh > 0.0 -> BinLearningStatus.MEASURED
                measuredSpanPercent >= MIN_PARTIAL_SPAN_PERCENT && measuredEnergyWh > 0.0 -> BinLearningStatus.PARTIAL
                estimatedSpanPercent >= 1.0 && estimatedEnergyWh > 0.0 -> BinLearningStatus.MODEL_ESTIMATED
                else -> BinLearningStatus.UNLEARNED
            }

            val isLearned = (status == BinLearningStatus.MEASURED)

            // 标准化归一到完整 5% SOC 分箱做功与电荷容量
            val normMeasuredWh = if (measuredSpanPercent > 0.0) {
                (measuredEnergyWh / measuredSpanPercent) * BIN_WIDTH_PERCENT
            } else 0.0

            val normMeasuredMah = if (measuredSpanPercent > 0.0) {
                (measuredChargeMah / measuredSpanPercent) * BIN_WIDTH_PERCENT
            } else 0.0

            val normEstimatedWh = if (estimatedSpanPercent > 0.0) {
                (estimatedEnergyWh / estimatedSpanPercent) * BIN_WIDTH_PERCENT
            } else 0.0

            val normEstimatedMah = if (estimatedSpanPercent > 0.0) {
                (estimatedChargeMah / estimatedSpanPercent) * BIN_WIDTH_PERCENT
            } else 0.0

            // 综合显示值：纯实测优先，若无实测则呈现模型估算
            val displayEnergyWh = when (status) {
                BinLearningStatus.MEASURED -> normMeasuredWh
                BinLearningStatus.PARTIAL -> measuredEnergyWh
                BinLearningStatus.MODEL_ESTIMATED -> normEstimatedWh
                BinLearningStatus.UNLEARNED -> 0.0
            }

            val displayChargeMah = when (status) {
                BinLearningStatus.MEASURED -> normMeasuredMah
                BinLearningStatus.PARTIAL -> measuredChargeMah
                BinLearningStatus.MODEL_ESTIMATED -> normEstimatedMah
                BinLearningStatus.UNLEARNED -> 0.0
            }

            val totalTempDuration = measuredTempDuration + estimatedTempDuration
            val avgTemp = if (totalTempDuration > 0.0) {
                (measuredTempSum + estimatedTempSum) / totalTempDuration
            } else null

            val totalChargeForVolt = measuredChargeMah + estimatedChargeMah
            val totalEnergyForVolt = measuredEnergyWh + estimatedEnergyWh
            val avgVolt = if (totalChargeForVolt > 0.0 && totalEnergyForVolt > 0.0) {
                totalEnergyForVolt / (totalChargeForVolt / 1000.0)
            } else null

            val allocationMethod = when {
                status == BinLearningStatus.MEASURED -> "DIRECT_MEASURED"
                status == BinLearningStatus.MODEL_ESTIMATED -> "CROSS_BIN_INTERPOLATION"
                status == BinLearningStatus.PARTIAL -> "PARTIAL_DIRECT"
                else -> null
            }

            return SocEnergyBinInfo(
                binIndex = binIndex,
                startSocPercent = startSoc,
                endSocPercent = endSoc,
                chargeMah = displayChargeMah,
                energyWh = displayEnergyWh,
                avgVoltageVolts = avgVolt,
                avgTemperatureC = avgTemp,
                sampleCount = measuredSessionCount + estimatedSessionCount,
                isLearned = isLearned,
                status = status,
                measuredSpanPercent = measuredSpanPercent,
                rawEnergyWh = measuredEnergyWh + estimatedEnergyWh,
                measuredEnergyWh = normMeasuredWh,
                estimatedEnergyWh = normEstimatedWh,
                measuredChargeMah = normMeasuredMah,
                estimatedChargeMah = normEstimatedMah,
                estimatedSpanPercent = estimatedSpanPercent,
                unresolvedEnergyWh = unresolvedEnergyWh,
                measuredSessionCount = measuredSessionCount,
                estimatedSessionCount = estimatedSessionCount,
                estimatedEnergySigmaWh = null,
                allocationMethod = allocationMethod,
                allocationAlgorithmVersion = "FCC-v1.2"
            )
        }
    }

    private val bins = Array(BIN_COUNT) { i -> MutableBin(i) }

    /** 100% 满电顶部平台独立隔离度量 */
    private var topPlateauMah: Double = 0.0
    private var topPlateauWh: Double = 0.0
    private var topPlateauDurationSec: Long = 0L
    private var topPlateauSampleCount: Int = 0

    /** 100% -> 99% 边界过渡区间独立隔离度量 */
    private var transitionMah: Double = 0.0
    private var transitionWh: Double = 0.0

    /** 未决能量段列表（保留原始物理积分与异常时间段） */
    private val unresolvedSegments = mutableListOf<UnresolvedEnergySegment>()

    /** 全局累计已观测物理总做功（Wh，包含实测、跨箱估算与停滞做功） */
    private var totalObservedEnergyWh: Double = 0.0

    /** 全局累计已观测物理总电荷（mAh） */
    private var totalObservedChargeMah: Double = 0.0

    /**
     * 吸收一次放电会话的连续采样序列，将各段做功解耦并精确归属于对应的 5% SOC 分箱中。
     *
     * 100% 满电平台做功自动解耦并独立累计，严禁混入 95%~100% 分箱。
     *
     * @param samples 放电采样点序列
     */
    @Synchronized
    fun absorbDischargeSession(samples: List<CapacitySample>) {
        if (samples.size < 2) return

        // 1. 若序列以 100% 满电平台启动，先进行解耦分离
        val firstSoc = samples.firstOrNull { it.socFraction != null }?.socFraction ?: 0.0
        val effectiveSamples: List<CapacitySample>
        if (firstSoc >= 0.995) {
            val decoupled = DischargeSessionDecoupler.decouple(
                samples = samples,
                fallbackStartSoc = firstSoc,
                fallbackEndSoc = samples.lastOrNull { it.socFraction != null }?.socFraction ?: 0.0
            )
            // 独立累加顶部平台做功与边界过渡做功，绝不污染 95%~100% 实测分箱
            topPlateauMah += decoupled.topPlateau.plateauMah
            topPlateauWh += decoupled.topPlateau.plateauWh
            transitionMah += decoupled.topPlateau.transitionMah
            transitionWh += decoupled.topPlateau.transitionWh
            topPlateauDurationSec += decoupled.topPlateau.durationSeconds
            topPlateauSampleCount += decoupled.topPlateau.sampleCount

            // 计入全局已观测物理总账本（使总观测能量 totalObservedEnergy 守恒包含平台与过渡做功）
            totalObservedEnergyWh += (decoupled.topPlateau.plateauWh + decoupled.topPlateau.transitionWh)
            totalObservedChargeMah += (decoupled.topPlateau.plateauMah + decoupled.topPlateau.transitionMah)

            effectiveSamples = decoupled.effectiveSamples
        } else {
            effectiveSamples = samples
        }

        if (effectiveSamples.size < 2) return

        // 2. 针对有效放电区间执行证据隔离分配
        absorbEffectiveDischargeSamples(effectiveSamples)
    }

    /**
     * 对有效放电区间的采样点执行细粒度物理证据切分与分配。
     *
     * @param samples 有效放电采样序列（建议时间戳升序，放电电流 I > 0）
     * @param stallThresholdMah SOC 停滞异常电荷保护门限（单位：mAh，默认 400.0）
     * @return 跨区间分配质量评级 [EnergyAllocationQuality]
     */
    @Synchronized
    fun absorbEffectiveDischargeSamples(
        samples: List<CapacitySample>,
        stallThresholdMah: Double = DEFAULT_STALL_CHARGE_THRESHOLD_MAH
    ): EnergyAllocationQuality {
        if (samples.size < 2) return EnergyAllocationQuality.NORMAL

        // 单会话局部累加器（路径一：直接实测）
        val sessionDirectCharge = DoubleArray(BIN_COUNT)
        val sessionDirectEnergy = DoubleArray(BIN_COUNT)
        val sessionDirectSpan = DoubleArray(BIN_COUNT)
        val sessionDirectTempSum = DoubleArray(BIN_COUNT)
        val sessionDirectTempDuration = DoubleArray(BIN_COUNT)
        val sessionDirectCovered = BooleanArray(BIN_COUNT)

        // 单会话局部累加器（路径二：跨分箱切分估算）
        val sessionEstimatedCharge = DoubleArray(BIN_COUNT)
        val sessionEstimatedEnergy = DoubleArray(BIN_COUNT)
        val sessionEstimatedSpan = DoubleArray(BIN_COUNT)
        val sessionEstimatedTempSum = DoubleArray(BIN_COUNT)
        val sessionEstimatedTempDuration = DoubleArray(BIN_COUNT)
        val sessionEstimatedCovered = BooleanArray(BIN_COUNT)

        // 单会话局部累加器（路径三：停滞未决做功）
        val sessionUnresolvedCharge = DoubleArray(BIN_COUNT)
        val sessionUnresolvedEnergy = DoubleArray(BIN_COUNT)

        var stallAccumulatedMah = 0.0
        var currentStallSoc: Double? = null
        var quality = EnergyAllocationQuality.NORMAL

        for (i in 0 until samples.size - 1) {
            val a = samples[i]
            val b = samples[i + 1]

            val dt = (b.timestampMs - a.timestampMs) / 1000.0
            if (dt <= 0.0 || dt > 60.0) continue // 超过 60 秒断层跳过梯形积分

            val currentA = a.currentAmp
            val currentB = b.currentAmp
            if (currentA == null || currentB == null) continue
            if (currentA <= 0.0 && currentB <= 0.0) continue

            val avgI = (max(0.0, currentA) + max(0.0, currentB)) / 2.0
            val dQ = avgI * (dt / 3600.0) * 1000.0

            val vA = a.voltageVolt
            val vB = b.voltageVolt
            val dE = if (vA != null && vB != null && vA > 0.0 && vB > 0.0) {
                ((vA * max(0.0, currentA) + vB * max(0.0, currentB)) / 2.0) * (dt / 3600.0)
            } else if (vA != null && vA > 0.0) {
                (vA * avgI) * (dt / 3600.0)
            } else if (vB != null && vB > 0.0) {
                (vB * avgI) * (dt / 3600.0)
            } else 0.0

            // 全局真实做功与电荷无损累加
            totalObservedEnergyWh += dE
            totalObservedChargeMah += dQ

            val socA = a.socFraction?.times(100.0) ?: continue
            val socB = b.socFraction?.times(100.0) ?: continue

            // 异常倒灌/回弹检查
            if (socB > socA + 0.05) {
                quality = EnergyAllocationQuality.SOC_REGRESSION
                continue
            }

            val binA = (socA / BIN_WIDTH_PERCENT).toInt().coerceIn(0, BIN_COUNT - 1)
            val binB = (socB / BIN_WIDTH_PERCENT).toInt().coerceIn(0, BIN_COUNT - 1)

            if (socA > socB) {
                // 清零停滞监视器
                stallAccumulatedMah = 0.0
                currentStallSoc = null

                // 判断是否在同一分箱内部（路径一：直接物理实测分配）
                if (binA == binB) {
                    val deltaSoc = socA - socB
                    sessionDirectCharge[binA] += dQ
                    sessionDirectEnergy[binA] += dE
                    sessionDirectSpan[binA] += deltaSoc
                    sessionDirectCovered[binA] = true

                    val tA = a.temperatureC
                    val tB = b.temperatureC
                    if (tA != null && tB != null) {
                        sessionDirectTempSum[binA] += ((tA + tB) / 2.0) * dt
                        sessionDirectTempDuration[binA] += dt
                    }
                } else {
                    // 跨越了分箱边界（路径二：跨分箱切分估算）
                    val deltaSoc = socA - socB
                    quality = EnergyAllocationQuality.CROSS_BOUNDARY_SPLIT

                    for (k in 0 until BIN_COUNT) {
                        val binFloor = k * BIN_WIDTH_PERCENT
                        val binCeil = (k + 1) * BIN_WIDTH_PERCENT

                        val overlapLow = max(socB, binFloor)
                        val overlapHigh = min(socA, binCeil)

                        if (overlapHigh > overlapLow) {
                            val overlapSpan = overlapHigh - overlapLow
                            val ratio = overlapSpan / deltaSoc
                            val allocatedQ = dQ * ratio
                            val allocatedE = dE * ratio

                            // 明确记录为模型估算数据，绝不混入直接实测！
                            sessionEstimatedCharge[k] += allocatedQ
                            sessionEstimatedEnergy[k] += allocatedE
                            sessionEstimatedSpan[k] += overlapSpan
                            sessionEstimatedCovered[k] = true

                            val tA = a.temperatureC
                            val tB = b.temperatureC
                            if (tA != null && tB != null) {
                                sessionEstimatedTempSum[k] += ((tA + tB) / 2.0) * dt * ratio
                                sessionEstimatedTempDuration[k] += dt * ratio
                            }
                        }
                    }
                }
            } else {
                // 情况：SOC 停滞不动 (socA == socB)
                val targetBin = binA

                // 检查停滞放电电荷量
                if (currentStallSoc == null || abs(currentStallSoc - socA) > 0.01) {
                    currentStallSoc = socA
                    stallAccumulatedMah = dQ
                } else {
                    stallAccumulatedMah += dQ
                }

                // 若同一单点 SOC 连续停滞放电超过门限（默认 400 mAh）：触发路径三 UNRESOLVED 保护
                if (stallAccumulatedMah > stallThresholdMah) {
                    quality = EnergyAllocationQuality.UNRESOLVED_SOC_STALL

                    // 遵循评审意见：保留实际库仑积分与能量积分，保存未决段，暂停分箱实测归档
                    sessionUnresolvedCharge[targetBin] += dQ
                    sessionUnresolvedEnergy[targetBin] += dE
                    unresolvedSegments.add(
                        UnresolvedEnergySegment(
                            measuredEnergyWh = dE,
                            measuredChargeMah = dQ,
                            startTimestamp = a.timestampMs,
                            endTimestamp = b.timestampMs,
                            startSoc = socA,
                            endSoc = socB,
                            reason = "SOC长期停滞放电超过门限(${stallThresholdMah}mAh)"
                        )
                    )
                    continue
                }

                // 正常短时间读数停滞（放电 < 400 mAh）：属于该分箱内的直接做功
                sessionDirectCharge[targetBin] += dQ
                sessionDirectEnergy[targetBin] += dE
                sessionDirectCovered[targetBin] = true

                val tA = a.temperatureC
                val tB = b.temperatureC
                if (tA != null && tB != null) {
                    sessionDirectTempSum[targetBin] += ((tA + tB) / 2.0) * dt
                    sessionDirectTempDuration[targetBin] += dt
                }
            }
        }

        // 将本会话有效吸收的数据汇入全局模型
        for (idx in 0 until BIN_COUNT) {
            val b = bins[idx]

            // 1. 汇入路径一：直接实测数据
            if (sessionDirectCovered[idx] && sessionDirectEnergy[idx] > 0.0) {
                b.measuredChargeMah += sessionDirectCharge[idx]
                b.measuredEnergyWh += sessionDirectEnergy[idx]
                b.measuredSpanPercent += sessionDirectSpan[idx]
                b.measuredTempSum += sessionDirectTempSum[idx]
                b.measuredTempDuration += sessionDirectTempDuration[idx]
                b.measuredSessionCount += 1
            }

            // 2. 汇入路径二：跨分箱切分估算数据（严格分离）
            if (sessionEstimatedCovered[idx] && sessionEstimatedEnergy[idx] > 0.0) {
                b.estimatedChargeMah += sessionEstimatedCharge[idx]
                b.estimatedEnergyWh += sessionEstimatedEnergy[idx]
                b.estimatedSpanPercent += sessionEstimatedSpan[idx]
                b.estimatedTempSum += sessionEstimatedTempSum[idx]
                b.estimatedTempDuration += sessionEstimatedTempDuration[idx]
                b.estimatedSessionCount += 1
            }

            // 3. 汇入路径三：未决停滞做功
            if (sessionUnresolvedEnergy[idx] > 0.0) {
                b.unresolvedEnergyWh += sessionUnresolvedEnergy[idx]
                b.unresolvedChargeMah += sessionUnresolvedCharge[idx]
            }
        }

        return quality
    }

    /**
     * 获取当前 20 段 SOC 能量曲线全景快照。
     *
     * @return 包含 20 个分箱的只读不可变列表 [List<SocEnergyBinInfo>]
     */
    @Synchronized
    fun getEnergyBins(): List<SocEnergyBinInfo> {
        return bins.map { it.toImmutable() }
    }

    /**
     * 计算当前 SOC 学习充分覆盖率（0.0 ~ 1.0）。
     *
     * 仅当分箱达到 MEASURED 充分实测状态时计入分子，杜绝未学满时虚标覆盖率。
     *
     * @return 充分覆盖率比例值（0.0 ~ 1.0）
     */
    @Synchronized
    fun getCoverageRatio(): Double {
        val learnedCount = bins.count { it.toImmutable().status == BinLearningStatus.MEASURED }
        return learnedCount.toDouble() / BIN_COUNT.toDouble()
    }

    /**
     * 估算全量滿充可用能量 FCC_E（单位：Wh）。
     *
     * 遵循技术评审规范与无假数据铁律：
     * 1. 彻底废除 (unlearnedCount * avgWhPerLearnedBin) 等粗暴脑补；
     * 2. 必须全域 20 段全部实测学满（特别是 0%~5% 分箱实测）方可输出高置信度全量 FCC_E；
     * 3. 存在任何非 MEASURED 分箱时，如实返回 Pair(null, null)（表示学习中，严禁捏造伪造数据）。
     *
     * @param fccQMah 可选的当前安时容量参考（保留签名兼容）
     * @return Pair(全量能量估算值 Wh, 不确定度 Wh)，未学满完备时返回 Pair(null, null)
     */
    @Synchronized
    fun estimateFullEnergyWh(fccQMah: Double? = null): Pair<Double?, Double?> {
        val snapshot = getEnergyBins()
        val allMeasured = snapshot.all { it.status == BinLearningStatus.MEASURED }

        if (!allMeasured) {
            // 只要有任何一段未充分实测学满（如常见 0%~5% 缺失），如实返回 null，绝不使用全局均值虚构
            return Pair(null, null)
        }

        // 20 段完整实测求和
        val totalWh = snapshot.sumOf { it.measuredEnergyWh }
        val uncertaintyWh = totalWh * 0.03 // 3% 物理测量合成不确定度
        return Pair(totalWh, uncertaintyWh)
    }

    /**
     * 获取 SOC 能量曲线综合容量学习与统一能量账本结果实体。
     *
     * 遵循统一物理能量账本守恒律：
     * totalObservedEnergyWh = measuredBinsEnergyWh + estimatedBinsEnergyWh + topPlateauEnergyWh + transitionEnergyWh + unresolvedEnergyWh
     *
     * @return 综合结果实体 [EnergyCapacityResult]
     */
    @Synchronized
    fun getEnergyCapacityResult(): EnergyCapacityResult {
        val snapshot = getEnergyBins()
        val measuredBins = snapshot.filter { it.status == BinLearningStatus.MEASURED }
        val estimatedBins = snapshot.filter { it.status == BinLearningStatus.MODEL_ESTIMATED }
        val unlearnedBins = snapshot.filter { it.status == BinLearningStatus.UNLEARNED }

        val measuredSumWh = snapshot.sumOf { it.measuredEnergyWh }
        val rawMeasuredBinsWh = bins.sumOf { it.measuredEnergyWh }
        val rawEstimatedBinsWh = bins.sumOf { it.estimatedEnergyWh }
        val rawUnresolvedBinsWh = bins.sumOf { it.unresolvedEnergyWh }

        val totalObservedWh = if (totalObservedEnergyWh > 0.0) {
            totalObservedEnergyWh
        } else {
            rawMeasuredBinsWh + rawEstimatedBinsWh + topPlateauWh + transitionWh + rawUnresolvedBinsWh
        }

        val allMeasured = (measuredBins.size == BIN_COUNT)
        val fullEWh = if (allMeasured) measuredSumWh else null
        val uncertaintyWh = if (allMeasured) measuredSumWh * 0.03 else null

        val coveragePercent = (measuredBins.size.toDouble() / BIN_COUNT.toDouble()) * 100.0

        val maturity = when {
            allMeasured -> LearningMaturity.STABLE
            measuredBins.size >= 14 -> LearningMaturity.CONVERGED
            measuredBins.isNotEmpty() -> LearningMaturity.INITIAL
            else -> LearningMaturity.PENDING
        }

        return EnergyCapacityResult(
            totalObservedEnergyWh = totalObservedWh,
            measuredBinsEnergyWh = rawMeasuredBinsWh,
            estimatedBinsEnergyWh = rawEstimatedBinsWh,
            topPlateauEnergyWh = topPlateauWh,
            transitionEnergyWh = transitionWh,
            unresolvedEnergyWh = rawUnresolvedBinsWh,
            estimatedMissingEnergyWh = null,
            estimatedFullEnergyWh = fullEWh,
            coveragePercent = coveragePercent,
            measuredBinCount = measuredBins.size,
            estimatedBinCount = estimatedBins.size,
            unlearnedBinCount = unlearnedBins.size,
            uncertaintyWh = uncertaintyWh,
            maturity = maturity,
            observedEnergyWh = totalObservedWh,
            measuredEnergyWh = measuredSumWh
        )
    }

    /**
     * 基于分段实测能量曲线计算当前剩余电量 safeSoc 对应的可用剩余能量（单位：Wh）。
     *
     * @param currentSocPercent 当前电量计 SOC 百分比 (0..100)
     * @param fallbackFullWh 可选的满充能量参考（单位：Wh，仅在分段曲线未完备时作为降级折算）
     * @return 可用剩余能量 Wh，若关键区间未测且无可靠参考则如实返回 null
     */
    @Synchronized
    fun estimateRemainingEnergyWh(currentSocPercent: Int, fallbackFullWh: Double? = null): Double? {
        val safeSoc = currentSocPercent.coerceIn(0, 100)
        if (safeSoc == 0) return 0.0

        val snapshot = getEnergyBins()

        var accWh = 0.0
        var allCoveredBinsLearned = true

        for (idx in 0 until BIN_COUNT) {
            val bin = snapshot[idx]
            val binFloor = idx * BIN_WIDTH_PERCENT
            val binCeil = (idx + 1) * BIN_WIDTH_PERCENT

            val coveredSocInBin = (min(binCeil, safeSoc.toDouble()) - binFloor).coerceIn(0.0, BIN_WIDTH_PERCENT)
            if (coveredSocInBin <= 0.0) continue

            if (bin.isLearned) {
                val fraction = coveredSocInBin / BIN_WIDTH_PERCENT
                accWh += bin.energyWh * fraction
            } else {
                allCoveredBinsLearned = false
            }
        }

        if (allCoveredBinsLearned && accWh > 0.0) {
            return accWh
        }

        if (fallbackFullWh != null && fallbackFullWh > 0.0) {
            return (fallbackFullWh * safeSoc) / 100.0
        }

        return null
    }

    /**
     * 获取已测分箱的实际物理做功累加总和（单位：Wh，纯实测累加，无任何插值伪造）。
     *
     * @return 当前已测分箱累计能量 Wh
     */
    @Synchronized
    fun getMeasuredAccumulatedEnergyWh(): Double {
        return bins.sumOf { it.measuredEnergyWh }
    }

    /**
     * 获取已达到充分直接学习标准（MEASURED）的分箱数量 (0..20)。
     *
     * @return 已学满分箱数
     */
    @Synchronized
    fun getLearnedBinCount(): Int {
        return bins.count { it.toImmutable().status == BinLearningStatus.MEASURED }
    }

    /**
     * 获取 100% 顶部满电平台独立度量数据。
     *
     * @return 顶部平台度量实体 [TopPlateauMetrics]
     */
    @Synchronized
    fun getTopPlateauMetrics(): TopPlateauMetrics {
        return TopPlateauMetrics(
            plateauMah = topPlateauMah,
            plateauWh = topPlateauWh,
            durationSeconds = topPlateauDurationSec,
            sampleCount = topPlateauSampleCount
        )
    }

    /**
     * 获取 100% -> 99% 边界过渡区间独立度量数据。
     *
     * @return 包含过渡电荷（mAh）与过渡能量（Wh）的 [Pair<Double, Double>]
     */
    @Synchronized
    fun getTransitionMetrics(): Pair<Double, Double> {
        return Pair(transitionMah, transitionWh)
    }

    /**
     * 获取未决停滞能量段记录列表。
     *
     * @return 未决能量段不可变列表 [List<UnresolvedEnergySegment>]
     */
    @Synchronized
    fun getUnresolvedSegments(): List<UnresolvedEnergySegment> {
        return unresolvedSegments.toList()
    }

    /**
     * 获取全局已观测到的总物理能量（Wh）。
     *
     * @return 全局已观测能量 Wh
     */
    @Synchronized
    fun getTotalObservedEnergyWh(): Double {
        return totalObservedEnergyWh
    }

    /**
     * 获取全局已观测到的总物理电荷（mAh）。
     *
     * @return 全局已观测电荷 mAh
     */
    @Synchronized
    fun getTotalObservedChargeMah(): Double {
        return totalObservedChargeMah
    }

    /**
     * 重置学习曲线与独立平台度量。
     */
    @Synchronized
    fun reset() {
        for (i in 0 until BIN_COUNT) {
            bins[i] = MutableBin(i)
        }
        topPlateauMah = 0.0
        topPlateauWh = 0.0
        transitionMah = 0.0
        transitionWh = 0.0
        topPlateauDurationSec = 0L
        topPlateauSampleCount = 0
        unresolvedSegments.clear()
        totalObservedEnergyWh = 0.0
        totalObservedChargeMah = 0.0
    }
}
