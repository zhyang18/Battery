package com.battery.analysis.domain.capacity.integration

import com.battery.analysis.domain.capacity.model.CapacitySample
import com.battery.analysis.domain.capacity.model.IntegratedSegment
import kotlin.math.max

/**
 * 电池侧电流梯形库仑积分与能量积分引擎（TrapezoidalIntegrator）。
 *
 * 遵循技术方案规范：
 * 1. 采用相邻采样点梯形积分；
 * 2. 采样缺口阈值默认为 60s，dt > 60s 必须切断积分区间，严禁跨大缺口盲目线性插值；
 * 3. 严格校验时间戳单调性，过滤 dt <= 0 异常；
 * 4. 统计有效积分时长、最大缺口与采样覆盖率。
 */
object CoulombIntegrator {

    /** 允许连续梯形微积分的最大时间间隔（单位：秒）。超过此门限视为系统断层，切断积分 */
    const val MAX_INTEGRATION_GAP_SEC: Double = 60.0

    /**
     * 对标准化采样点时序执行梯形库仑积分与能量积分。
     *
     * @param samples 按时间戳升序排列的采样点序列
     * @param maxGapSec 允许连续积分的最大缺口秒数（默认 60s）
     * @return 积分统计结果实体 [IntegratedSegment]
     */
    fun integrate(
        samples: List<CapacitySample>,
        maxGapSec: Double = MAX_INTEGRATION_GAP_SEC
    ): IntegratedSegment {
        if (samples.size < 2) {
            return IntegratedSegment(
                chargeMah = 0.0,
                energyWh = 0.0,
                validDurationSec = 0.0,
                expectedDurationSec = 0.0,
                maxGapSec = 0.0,
                coverageRatio = 0.0
            )
        }

        var totalChargeMah = 0.0
        var totalEnergyWh = 0.0
        var validDurationSec = 0.0
        var maxObservedGapSec = 0.0

        val startTs = samples.first().timestampMs
        val endTs = samples.last().timestampMs
        val expectedDurationSec = max(0.0, (endTs - startTs) / 1000.0)

        var tempSumWeighted = 0.0
        var tempDurationWeighted = 0.0

        for (i in 0 until samples.size - 1) {
            val a = samples[i]
            val b = samples[i + 1]

            val dt = (b.timestampMs - a.timestampMs) / 1000.0

            // 异常时间序列过滤：非单调递增或无效时间步长
            if (dt <= 0.0) continue

            if (dt > maxObservedGapSec) {
                maxObservedGapSec = dt
            }

            // 超过最大缺口门限：切断积分区间，不跨缺口累加
            if (dt > maxGapSec) continue

            val currentA = a.currentAmp
            val currentB = b.currentAmp
            if (currentA == null || currentB == null) continue

            // 梯形电流积分: ΔQ (mAh) = ((I_a + I_b) / 2) * (dt / 3600) * 1000
            val avgCurrentAmp = (currentA + currentB) / 2.0
            val dQMah = avgCurrentAmp * (dt / 3600.0) * 1000.0

            // 梯形能量积分: ΔE (Wh) = ((V_a * I_a + V_b * I_b) / 2) * (dt / 3600)
            val voltA = a.voltageVolt
            val voltB = b.voltageVolt
            val dEWh = if (voltA != null && voltB != null) {
                val pA = voltA * currentA
                val pB = voltB * currentB
                ((pA + pB) / 2.0) * (dt / 3600.0)
            } else if (voltA != null) {
                (voltA * avgCurrentAmp) * (dt / 3600.0)
            } else if (voltB != null) {
                (voltB * avgCurrentAmp) * (dt / 3600.0)
            } else 0.0

            totalChargeMah += dQMah
            totalEnergyWh += dEWh
            validDurationSec += dt

            val tempA = a.temperatureC
            val tempB = b.temperatureC
            if (tempA != null && tempB != null) {
                tempSumWeighted += ((tempA + tempB) / 2.0) * dt
                tempDurationWeighted += dt
            }
        }

        val coverageRatio = if (expectedDurationSec > 0.0) {
            (validDurationSec / expectedDurationSec).coerceIn(0.0, 1.0)
        } else if (validDurationSec > 0.0) 1.0 else 0.0

        val avgVolt = if (totalChargeMah > 0.0 && totalEnergyWh > 0.0) {
            // V_avg = E(Wh) / Q(Ah)
            (totalEnergyWh / (totalChargeMah / 1000.0))
        } else null

        val avgTemp = if (tempDurationWeighted > 0.0) {
            tempSumWeighted / tempDurationWeighted
        } else null

        return IntegratedSegment(
            chargeMah = totalChargeMah,
            energyWh = totalEnergyWh,
            validDurationSec = validDurationSec,
            expectedDurationSec = expectedDurationSec,
            maxGapSec = maxObservedGapSec,
            coverageRatio = coverageRatio,
            avgVoltageVolts = avgVolt,
            avgTemperatureC = avgTemp
        )
    }

    /**
     * 内部物理自洽性校验（Section 5.4）：
     * 校验由能量与电荷推导的加权平均工作电压是否处于合理范围（如 2.5V ~ 4.6V，串联双电芯为 5.0V ~ 9.5V 或上报等效 2.5V ~ 4.6V）。
     * 若未采样电压（energyWh <= 0），则不作为物理自洽性排查依据（避免因传感器无电压而误判数据）。
     */
    fun isPhysicalConsistencyValid(chargeMah: Double, energyWh: Double, isDualCell: Boolean = false): Boolean {
        if (chargeMah <= 0.0 || energyWh <= 0.0) return true
        val avgVolt = energyWh / (chargeMah / 1000.0)
        val minV = 2.5
        val maxV = if (isDualCell) 9.5 else 4.6
        return avgVolt in minV..maxV
    }
}
