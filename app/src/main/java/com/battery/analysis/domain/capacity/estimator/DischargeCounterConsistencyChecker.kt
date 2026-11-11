package com.battery.analysis.domain.capacity.estimator

import com.battery.analysis.domain.capacity.model.CapacitySample
import com.battery.analysis.domain.capacity.model.CounterConsistency
import com.battery.analysis.domain.capacity.model.CounterQuality
import kotlin.math.abs
import kotlin.math.max

/**
 * 电量计硬件 CHARGE_COUNTER 交叉一致性检验结果实体。
 *
 * @property consistency 一致性判定状态 [CounterConsistency]
 * @property counterQuality Counter 硬件数据质量状态 [CounterQuality]
 * @property qIntegralMah 梯形微积分算得的有效放电净电量（mAh）
 * @property qCounterMah 电量计 CHARGE_COUNTER 差值算得的物理电量（mAh），若不支持则为 null
 * @property relativeDifferencePercent 相对偏差百分比（0.0 ~ 100.0%）
 * @property counterStartUah 有效起点处的电量计原始读数（uAh）
 * @property counterEndUah 有效终点处的电量计原始读数（uAh）
 * @property details 校验详细说明文字
 */
data class CounterConsistencyResult(
    val consistency: CounterConsistency,
    val counterQuality: CounterQuality,
    val qIntegralMah: Double,
    val qCounterMah: Double?,
    val relativeDifferencePercent: Double?,
    val counterStartUah: Long?,
    val counterEndUah: Long?,
    val details: String
)

/**
 * 放电电量计 CHARGE_COUNTER 交叉验证器（DischargeCounterConsistencyChecker）。
 *
 * 核心技术规范（遵循第二阶段 P1-B 补充指示）：
 * 1. 时间戳严格对齐：Counter 的起止点必须与有效放电积分起止点 [t_effective, t_end] 严格保持一致（第一次 99% 的历史读数），严禁从 100% 拔电处提前读取；
 * 2. 严格过滤 Sentinel 哨兵值：过滤 Integer.MIN_VALUE 与 Long.MIN_VALUE 及无效读数；
 * 3. 细分数据质量（CounterQuality）：识别 STALE（停滞不更新）、NON_MONOTONIC（逆向倒流）、RESET（异常清零回绕）、UNAVAILABLE（不支持）；
 * 4. 独立印证非篡改：仅用于检验库仑梯形积分的可信度，Counter 异常或不一致不直接否定电流积分，绝不用 Counter 强行篡改积分结果；
 * 5. 容差评定：正常单调情况下，相对偏差 DQ <= 5.0% 判定为 AGREE；DQ > 5.0% 判定为 DIVERGE。
 */
object DischargeCounterConsistencyChecker {

    /** 判定为一致的最大允许相对偏差百分比（5.0%） */
    const val MAX_AGREE_DIFFERENCE_PERCENT: Double = 5.0

    /** 放电期间允许 Counter 偶发微小跳变的最大反向容差（50,000 uAh = 50 mAh） */
    private const val MAX_ALLOWABLE_COUNTER_RISE_UAH: Long = 50_000L

    /** 判定 Counter 停滞（STALE）的最小积分电量门限（100 mAh） */
    private const val STALE_MIN_INTEGRAL_MAH: Double = 100.0

    /** 判定 Counter 停滞的最大读数变动阈值（5,000 uAh = 5 mAh） */
    private const val STALE_MAX_COUNTER_DELTA_UAH: Long = 5_000L

    /**
     * 对有效放电区间的梯形微积分电量与电量计 CHARGE_COUNTER 进行交叉一致性验证。
     *
     * @param effectiveSamples 有效放电积分采样点序列（起始点严格对齐 t_effective 处的首次低于 100% 采样点）
     * @param qIntegralMah 梯形微积分算得的有效物理电荷量（mAh）
     * @return 交叉一致性验证结果实体 [CounterConsistencyResult]
     */
    fun check(
        effectiveSamples: List<CapacitySample>,
        qIntegralMah: Double
    ): CounterConsistencyResult {
        if (effectiveSamples.size < 2 || qIntegralMah <= 0.0) {
            return CounterConsistencyResult(
                consistency = CounterConsistency.UNAVAILABLE,
                counterQuality = CounterQuality.UNAVAILABLE,
                qIntegralMah = qIntegralMah,
                qCounterMah = null,
                relativeDifferencePercent = null,
                counterStartUah = null,
                counterEndUah = null,
                details = "有效放电采样点不足或积分电量为0，无法执行Counter比对"
            )
        }

        // 起点必须严格对应有效起点 t_effective 处的采样点（即首次 99% 的历史读数）
        val startSample = effectiveSamples.first()
        val endSample = effectiveSamples.last()

        val ccStart = startSample.chargeCounterUah
        val ccEnd = endSample.chargeCounterUah

        // 1. 严格过滤 Sentinel 哨兵值与不支持状态
        if (!isValidCounterReading(ccStart) || !isValidCounterReading(ccEnd)) {
            return CounterConsistencyResult(
                consistency = CounterConsistency.UNAVAILABLE,
                counterQuality = CounterQuality.UNAVAILABLE,
                qIntegralMah = qIntegralMah,
                qCounterMah = null,
                relativeDifferencePercent = null,
                counterStartUah = ccStart,
                counterEndUah = ccEnd,
                details = "设备未上报有效的 CHARGE_COUNTER 硬件读数或命中哨兵值"
            )
        }

        val rawStart = ccStart!!
        val rawEnd = ccEnd!!

        // 2. 检测 Counter 停滞（STALE）：微积分已有显著累加（>=100mAh），但 Counter 读数几乎未刷新（< 5mAh）
        val counterDeltaUah = rawStart - rawEnd
        if (qIntegralMah >= STALE_MIN_INTEGRAL_MAH && abs(counterDeltaUah) < STALE_MAX_COUNTER_DELTA_UAH) {
            return CounterConsistencyResult(
                consistency = CounterConsistency.INVALID,
                counterQuality = CounterQuality.STALE,
                qIntegralMah = qIntegralMah,
                qCounterMah = counterDeltaUah.toDouble() / 1000.0,
                relativeDifferencePercent = null,
                counterStartUah = rawStart,
                counterEndUah = rawEnd,
                details = String.format(java.util.Locale.US, "CHARGE_COUNTER 读数停滞未刷新(放电已达 %.1f mAh, Counter 变化仅 %d uAh)", qIntegralMah, counterDeltaUah)
            )
        }

        // 3. 检测非单调倒流（NON_MONOTONIC）：放电期间 Counter 明显反向增大（> 50 mAh）
        if (rawEnd > (rawStart + MAX_ALLOWABLE_COUNTER_RISE_UAH)) {
            return CounterConsistencyResult(
                consistency = CounterConsistency.INVALID,
                counterQuality = CounterQuality.NON_MONOTONIC,
                qIntegralMah = qIntegralMah,
                qCounterMah = null,
                relativeDifferencePercent = null,
                counterStartUah = rawStart,
                counterEndUah = rawEnd,
                details = String.format(java.util.Locale.US, "CHARGE_COUNTER 发生非单调异常倒流(起点: %d uAh, 终点: %d uAh)", rawStart, rawEnd)
            )
        }

        // 4. 计算 Counter 放出电荷量 Q_counter = (CC_start - CC_end) / 1000.0 (mAh)
        val qCounterMah = counterDeltaUah.toDouble() / 1000.0
        if (qCounterMah <= 0.0) {
            return CounterConsistencyResult(
                consistency = CounterConsistency.INVALID,
                counterQuality = CounterQuality.RESET,
                qIntegralMah = qIntegralMah,
                qCounterMah = qCounterMah,
                relativeDifferencePercent = null,
                counterStartUah = rawStart,
                counterEndUah = rawEnd,
                details = "CHARGE_COUNTER 差值非正，判定为硬件读数未更新或发生回绕清零"
            )
        }

        // 5. 计算相对偏差 DQ = (|Q_integral - Q_counter| / max(Q_integral, Q_counter)) * 100%
        val maxQ = max(qIntegralMah, qCounterMah)
        val diffPercent = if (maxQ > 0.0) {
            (abs(qIntegralMah - qCounterMah) / maxQ) * 100.0
        } else 0.0

        val consistency = if (diffPercent <= MAX_AGREE_DIFFERENCE_PERCENT) {
            CounterConsistency.AGREE
        } else {
            CounterConsistency.DIVERGE
        }

        val details = String.format(
            java.util.Locale.US,
            "积分: %.1f mAh, Counter: %.1f mAh, 相对偏差: %.2f%% (%s)",
            qIntegralMah, qCounterMah, diffPercent, consistency.name
        )

        return CounterConsistencyResult(
            consistency = consistency,
            counterQuality = CounterQuality.NORMAL,
            qIntegralMah = qIntegralMah,
            qCounterMah = qCounterMah,
            relativeDifferencePercent = diffPercent,
            counterStartUah = rawStart,
            counterEndUah = rawEnd,
            details = details
        )
    }

    /**
     * 校验 Counter 读数是否合法（排除 null、<=0 以及 Android 官方哨兵值 Integer.MIN_VALUE 和 Long.MIN_VALUE）。
     *
     * @param reading 原始读取值
     * @return 是否为有效的物理电量计读数
     */
    fun isValidCounterReading(reading: Long?): Boolean {
        if (reading == null) return false
        if (reading <= 0L) return false
        if (reading == Int.MIN_VALUE.toLong()) return false
        if (reading == Long.MIN_VALUE) return false
        return true
    }
}
