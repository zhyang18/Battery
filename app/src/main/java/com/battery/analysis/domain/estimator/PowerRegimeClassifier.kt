package com.battery.analysis.domain.estimator

import com.battery.analysis.domain.estimator.model.DischargeInterval
import com.battery.analysis.domain.estimator.model.PowerRegime

/**
 * 设备日常运行功耗工况分类器。
 * 结合屏幕状态（亮屏/息屏）、区间物理功率以及设备历史基准，
 * 将离散的放电物理区间自适应归入五大核心工况分类（OFF_LOW, OFF_HIGH, ON_LIGHT, ON_NORMAL, ON_HEAVY），
 * 支持动态适配不同机型的功耗基线水平，杜绝机械僵化的固定绝对阈值。
 */
class PowerRegimeClassifier {

    companion object {
        /** 行业常规智能手机息屏深度休眠平均参考底噪功率（单位：W） */
        const val DEFAULT_BASELINE_OFF_WATTS: Double = 0.15

        /** 行业常规智能手机中位亮度普通交互平均参考功率（单位：W） */
        const val DEFAULT_MEDIAN_ON_WATTS: Double = 1.80

        /** 亮屏持续重载确立所需的最小持续物理时长门槛（毫秒，默认 10 秒 = 10,000 ms） */
        const val MIN_HEAVY_SUSTAIN_MS: Long = 10_000L
    }

    /**
     * 将单个放电物理区间判定归入对应的功耗工况分类。
     * 自动区分瞬态短时尖峰与真实持续重载：当放电功率超过重载门槛时，若区间持续时间
     * 短于持续门槛 [minHeavySustainMs]（如瞬时脉冲或启动毛刺），不直接建立重载工况，
     * 而是平滑归入日常工况 [PowerRegime.ON_NORMAL]；达到持续时间门槛时方确立为真实重载 [PowerRegime.ON_HEAVY]。
     *
     * @param interval 待分类的放电物理区间实体 [DischargeInterval]
     * @param baselineOffWatts 该设备测得的息屏待机基准功率（单位：W，默认 0.15W）
     * @param medianOnWatts 该设备测得的亮屏日常中位功率（单位：W，默认 1.80W）
     * @param minHeavySustainMs 确立为持续重载工况所需的最小持续毫秒数（默认 10,000 ms）
     * @return 对应的物理工况枚举 [PowerRegime]
     */
    fun classify(
        interval: DischargeInterval,
        baselineOffWatts: Double = DEFAULT_BASELINE_OFF_WATTS,
        medianOnWatts: Double = DEFAULT_MEDIAN_ON_WATTS,
        minHeavySustainMs: Long = MIN_HEAVY_SUSTAIN_MS
    ): PowerRegime {
        val p = interval.powerWatts
        if (p <= 0.0 || !p.isFinite()) {
            return PowerRegime.UNKNOWN
        }

        val safeOffBase = if (baselineOffWatts > 0.0 && baselineOffWatts.isFinite()) baselineOffWatts else DEFAULT_BASELINE_OFF_WATTS
        val safeOnMedian = if (medianOnWatts > 0.0 && medianOnWatts.isFinite()) medianOnWatts else DEFAULT_MEDIAN_ON_WATTS

        return if (!interval.isScreenOn) {
            // 息屏状态：以基线 1.5 倍为界区分深睡底噪与高耗电唤醒
            if (p <= safeOffBase * 1.5) {
                PowerRegime.OFF_LOW
            } else {
                PowerRegime.OFF_HIGH
            }
        } else {
            // 亮屏状态：以中位基准比例区分轻载、日常中载与重载
            when {
                p < safeOnMedian * 0.65 -> PowerRegime.ON_LIGHT
                p <= safeOnMedian * 2.00 -> PowerRegime.ON_NORMAL
                else -> {
                    // 对于高功耗区间，严格区分偶发尖峰与真实持续重载
                    if (interval.durationMs >= minHeavySustainMs) {
                        PowerRegime.ON_HEAVY
                    } else {
                        // 短时偶发脉冲不单独建立 HEAVY 工况，归为日常中载中的瞬态波动
                        PowerRegime.ON_NORMAL
                    }
                }
            }
        }
    }
}
