package com.battery.analysis.domain.capacity.estimator

import com.battery.analysis.domain.capacity.model.CapacitySample
import com.battery.analysis.domain.capacity.model.SocEnergyBinInfo
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 20 段 SOC 能量曲线自学习器（SocEnergyCurveLearner）。
 *
 * 遵循技术方案 Section 8 规范：
 * 1. 5% SOC 固定分箱，全域划分为 20 个 Bin（0%~5%, 5%~10%, ..., 95%~100%）；
 * 2. 同步电池端 V×I 梯形积分累加真实做功，严禁单点固定标称电压替代；
 * 3. 严禁使用 ΔE / ΔSOC 直接外推全量 FCC_E；
 * 4. 未学习区间标记为 UNLEARNED（严禁填 0，严禁粗暴全局均值脑补）；
 * 5. 全量 FCC_E 仅在 20 段覆盖完备或高置信度插值时输出；
 * 6. 支持基于分段实测曲线累加当前可用剩余能量 E_remaining。
 */
class SocEnergyCurveLearner {

    companion object {
        const val BIN_COUNT = 20
        const val BIN_WIDTH_PERCENT = 5.0

        /** 锂电池单段 5% SOC 物理做功上限（8000mAh*4.5V/1000/20 ≈ 1.8Wh，取保守 2.5Wh 过滤异常突变） */
        const val MAX_PHYSICAL_WH_PER_BIN = 2.5

        /** 默认锂电池放电平台标称电压（单位：V，仅用于无实测区间的保守兜底） */
        const val DEFAULT_NOMINAL_VOLTAGE = 3.85
    }

    /**
     * 内部可变分箱累加器。
     */
    private data class MutableBin(
        val binIndex: Int,
        var totalChargeMah: Double = 0.0,
        var totalEnergyWh: Double = 0.0,
        var totalSpanPercent: Double = 0.0,
        var tempSum: Double = 0.0,
        var tempDuration: Double = 0.0,
        var sampleCount: Int = 0
    ) {
        val startSoc: Double get() = binIndex * BIN_WIDTH_PERCENT
        val endSoc: Double get() = (binIndex + 1) * BIN_WIDTH_PERCENT

        fun toImmutable(): SocEnergyBinInfo {
            // 至少累计观测覆盖 1.5% 以上 SOC 跨度且有实测做功，方视为该 5% 区间已学习，避免极微小抖动误判
            val isLearned = totalSpanPercent >= 1.5 && totalEnergyWh > 0.0

            // 标准化归一到完整 5% SOC 分箱做功与电荷容量
            val normalizedEnergyWh = if (isLearned && totalSpanPercent > 0.0) {
                (totalEnergyWh / totalSpanPercent) * BIN_WIDTH_PERCENT
            } else 0.0

            val normalizedChargeMah = if (isLearned && totalSpanPercent > 0.0) {
                (totalChargeMah / totalSpanPercent) * BIN_WIDTH_PERCENT
            } else 0.0

            val avgVolt = if (totalChargeMah > 0.0 && totalEnergyWh > 0.0) {
                totalEnergyWh / (totalChargeMah / 1000.0)
            } else null
            val avgTemp = if (tempDuration > 0.0) tempSum / tempDuration else null

            return SocEnergyBinInfo(
                binIndex = binIndex,
                startSocPercent = startSoc,
                endSocPercent = endSoc,
                chargeMah = normalizedChargeMah,
                energyWh = normalizedEnergyWh,
                avgVoltageVolts = avgVolt,
                avgTemperatureC = avgTemp,
                sampleCount = sampleCount,
                isLearned = isLearned
            )
        }
    }

    private val bins = Array(BIN_COUNT) { i -> MutableBin(i) }

    /**
     * 吸收一次放电会话的连续采样序列，将各段做功归属到对应的 5% SOC 分箱中。
     *
     * @param samples 放电采样点序列
     */
    @Synchronized
    fun absorbDischargeSession(samples: List<CapacitySample>) {
        if (samples.size < 2) return

        // 临时累加器，单会话内每个 bin 只吸收一次平均做功，防止单会话内多次采样加权失衡
        val sessionBinCharge = DoubleArray(BIN_COUNT)
        val sessionBinEnergy = DoubleArray(BIN_COUNT)
        val sessionBinTempSum = DoubleArray(BIN_COUNT)
        val sessionBinTempDuration = DoubleArray(BIN_COUNT)
        val sessionBinCovered = BooleanArray(BIN_COUNT)

        for (i in 0 until samples.size - 1) {
            val a = samples[i]
            val b = samples[i + 1]

            val dt = (b.timestampMs - a.timestampMs) / 1000.0
            if (dt <= 0.0 || dt > 60.0) continue

            val socA = a.socFraction
            val socB = b.socFraction
            if (socA == null || socB == null) continue

            val currentA = a.currentAmp
            val currentB = b.currentAmp
            if (currentA == null || currentB == null) continue
            // 仅吸收放电做功 (I > 0)
            if (currentA <= 0.0 && currentB <= 0.0) continue

            val avgI = (max(0.0, currentA) + max(0.0, currentB)) / 2.0
            val dQ = avgI * (dt / 3600.0) * 1000.0

            val vA = a.voltageVolt
            val vB = b.voltageVolt
            val dE = if (vA != null && vB != null) {
                ((vA * max(0.0, currentA) + vB * max(0.0, currentB)) / 2.0) * (dt / 3600.0)
            } else if (vA != null) {
                (vA * avgI) * (dt / 3600.0)
            } else if (vB != null) {
                (vB * avgI) * (dt / 3600.0)
            } else 0.0

            val meanSocPercent = ((socA + socB) / 2.0) * 100.0
            val binIdx = (meanSocPercent / BIN_WIDTH_PERCENT).toInt().coerceIn(0, BIN_COUNT - 1)

            sessionBinCharge[binIdx] += dQ
            sessionBinEnergy[binIdx] += dE
            sessionBinCovered[binIdx] = true

            val tA = a.temperatureC
            val tB = b.temperatureC
            if (tA != null && tB != null) {
                sessionBinTempSum[binIdx] += ((tA + tB) / 2.0) * dt
                sessionBinTempDuration[binIdx] += dt
            }
        }

        // 计算该会话覆盖的起止 SOC 区间
        val firstSoc = samples.firstOrNull { it.socFraction != null }?.socFraction?.times(100.0) ?: return
        val lastSoc = samples.lastOrNull { it.socFraction != null }?.socFraction?.times(100.0) ?: return
        val sessionMinSoc = min(firstSoc, lastSoc)
        val sessionMaxSoc = max(firstSoc, lastSoc)

        // 汇入全局持久模型
        for (idx in 0 until BIN_COUNT) {
            val binFloor = idx * BIN_WIDTH_PERCENT
            val binCeil = (idx + 1) * BIN_WIDTH_PERCENT
            val overlap = max(0.0, min(binCeil, sessionMaxSoc) - max(binFloor, sessionMinSoc))

            if (sessionBinCovered[idx] && overlap >= 0.2 && sessionBinEnergy[idx] > 0.0 && sessionBinEnergy[idx] < MAX_PHYSICAL_WH_PER_BIN) {
                val b = bins[idx]
                b.totalChargeMah += sessionBinCharge[idx]
                b.totalEnergyWh += sessionBinEnergy[idx]
                b.totalSpanPercent += overlap
                b.tempSum += sessionBinTempSum[idx]
                b.tempDuration += sessionBinTempDuration[idx]
                b.sampleCount += 1
            }
        }
    }

    /**
     * 获取当前 20 段 SOC 能量曲线全景快照。
     */
    @Synchronized
    fun getEnergyBins(): List<SocEnergyBinInfo> {
        return bins.map { it.toImmutable() }
    }

    /**
     * 计算当前 SOC 学习覆盖率（0.0 ~ 1.0）。
     */
    @Synchronized
    fun getCoverageRatio(): Double {
        val learnedCount = bins.count { it.sampleCount > 0 && it.totalEnergyWh > 0.0 }
        return learnedCount.toDouble() / BIN_COUNT.toDouble()
    }

    /**
     * 估算全量滿充可用能量 FCC_E（单位：Wh）。
     *
     * 严格遵循规则：覆盖率不足 70% 时如实返回 null（显示学习中），杜绝伪造数字；
     * 覆盖率 >= 70% 且存在未测区间时，采用已有实测区间的单位做功保守插值，并记录不确定度。
     */
    @Synchronized
    fun estimateFullEnergyWh(fccQMah: Double? = null): Pair<Double?, Double?> {
        val snapshot = getEnergyBins()
        val learnedBins = snapshot.filter { it.isLearned }
        val learnedCount = learnedBins.size

        if (learnedCount < 14) { // 覆盖不足 70% (14 / 20)
            return Pair(null, null)
        }

        var totalWh = 0.0
        var unlearnedCount = 0

        for (b in snapshot) {
            if (b.isLearned) {
                totalWh += b.energyWh
            } else {
                unlearnedCount++
            }
        }

        // 若全部 20 段完整实测
        if (unlearnedCount == 0) {
            return Pair(totalWh, totalWh * 0.03) // 3% 不确定度
        }

        // 若部分区间未覆盖，利用已学习区间的平均每段做功进行补齐，但不确定度相应放大
        val avgWhPerLearnedBin = totalWh / learnedCount.toDouble()
        val interpolatedFullWh = totalWh + (unlearnedCount * avgWhPerLearnedBin)
        val uncertaintyWh = interpolatedFullWh * (0.03 + (unlearnedCount.toDouble() / BIN_COUNT) * 0.10)

        return Pair(interpolatedFullWh, uncertaintyWh)
    }

    /**
     * 基于分段实测能量曲线计算当前剩余电量 safeSoc 对应的可用剩余能量（单位：Wh）。
     */
    @Synchronized
    fun estimateRemainingEnergyWh(currentSocPercent: Int, fallbackFullWh: Double? = null): Double? {
        val safeSoc = currentSocPercent.coerceIn(0, 100)
        if (safeSoc == 0) return 0.0

        val snapshot = getEnergyBins()

        var accWh = 0.0
        var allBinsLearned = true

        for (idx in 0 until BIN_COUNT) {
            val bin = snapshot[idx]
            val binFloor = idx * BIN_WIDTH_PERCENT
            val binCeil = (idx + 1) * BIN_WIDTH_PERCENT

            // 计算该分箱处于 [0, safeSoc] 内的实际覆盖 SOC 跨度
            val coveredSocInBin = (min(binCeil, safeSoc.toDouble()) - binFloor).coerceIn(0.0, BIN_WIDTH_PERCENT)
            if (coveredSocInBin <= 0.0) continue

            if (bin.isLearned) {
                val fraction = coveredSocInBin / BIN_WIDTH_PERCENT
                accWh += bin.energyWh * fraction
            } else {
                allBinsLearned = false
            }
        }

        if (allBinsLearned && accWh > 0.0) {
            return accWh
        }

        // 若关键区间未学习，降级采用可靠的满充能量比例折算
        if (fallbackFullWh != null && fallbackFullWh > 0.0) {
            return (fallbackFullWh * safeSoc) / 100.0
        }

        return null
    }

    /**
     * 重置学习曲线。
     */
    @Synchronized
    fun reset() {
        for (i in 0 until BIN_COUNT) {
            bins[i] = MutableBin(i)
        }
    }
}
