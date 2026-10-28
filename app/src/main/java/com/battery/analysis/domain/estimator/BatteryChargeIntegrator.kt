package com.battery.analysis.domain.estimator

/**
 * 可进行库仑安时数值积分的基础微元时序点接口。
 */
interface IntegrablePoint {
    /**
     * 单调流逝时间戳（单位：毫秒）。
     */
    val elapsedRealtimeMs: Long

    /**
     * 电池端测得的真实物理净电流（单位：毫安 mA，流入为正，流出为负）。
     */
    val netCurrentMa: Float
}

/**
 * 高精度梯形微元库仑安时积分器（BatteryChargeIntegrator）。
 * 遵循严格真实物理量纲与单调时钟差分，具备时钟倒流防护与超时采样缺口自动断开熔断能力，
 * 杜绝将长时间休眠缺样盲目插值为连续放电，确保物理积分结果绝对忠实可靠。
 */
object BatteryChargeIntegrator {

    /**
     * 允许进行连续梯形积分的单一切片默认最大时间间隔上限（单位：毫秒，默认 180 秒/3分钟）。
     * 既包容息屏 Doze 模式下系统的正常采样周期，又能有效隔离数十分钟以上的长期服务中断。
     */
    const val DEFAULT_MAX_VALID_GAP_MS: Long = 180_000L

    /**
     * 对时序微元采样点序列进行严格的梯形数值安时积分。
     * 内部具备严格的时间戳单调性维护，遇到时间倒流或重复时间戳时自动过滤。
     *
     * 积分公式：
     * \Delta Q_{\mathrm{mAh}} = \sum_{k=0}^{n-1} \frac{I_k + I_{k+1}}{2} \cdot \frac{t_{k+1} - t_k}{3600 \times 1000}
     *
     * @param points 待积分的时序采样点列表
     * @param maxGapMs 允许连线积分的最大时间间隔上限（单位：毫秒）
     * @return 累计计算出的净充入或净放出电荷量（单位：毫安时 mAh，若净流入则为正，若净流出则为负；无有效点时返回 0.0）
     */
    fun integrateChargeMah(
        points: List<IntegrablePoint>,
        maxGapMs: Long = DEFAULT_MAX_VALID_GAP_MS
    ): Double {
        if (points.size < 2) {
            return 0.0
        }

        var totalChargeMah = 0.0
        var lastValidPoint: IntegrablePoint? = null

        for (point in points) {
            val prev = lastValidPoint
            if (prev == null) {
                lastValidPoint = point
                continue
            }

            val dtMs = point.elapsedRealtimeMs - prev.elapsedRealtimeMs

            // 若时间戳未单调递增（倒流或重复），丢弃当前异常点，保持前驱锚点不变
            if (dtMs <= 0L) {
                continue
            }

            // 若在允许的时间间隔切片内，进行梯形微积分累加
            if (dtMs <= maxGapMs) {
                val avgCurrentMa = (prev.netCurrentMa.toDouble() + point.netCurrentMa.toDouble()) / 2.0
                val dtHours = dtMs.toDouble() / 3_600_000.0
                totalChargeMah += avgCurrentMa * dtHours
            }

            // 更新合法的前驱锚点
            lastValidPoint = point
        }

        return totalChargeMah
    }

    /**
     * 校验采样序列的连续性与完整性质量评分。
     * 评估序列中是否存在大跨度断层或时钟乱序。
     * 内部具备严格单调性过滤，计算有效单调时段的真实连续覆盖比。
     *
     * @param points 待校验的时序采样点列表
     * @param maxGapMs 允许连续的最大切片间隔
     * @return 连续性质量评分（取值范围 0.0 至 1.0，点数不足或严重断层返回 0.0）
     */
    fun evaluateContinuityScore(
        points: List<IntegrablePoint>,
        maxGapMs: Long = DEFAULT_MAX_VALID_GAP_MS
    ): Double {
        if (points.size < 2) {
            return 0.0
        }

        var validSlices = 0
        var totalGapMs = 0L
        var firstValidMs = -1L
        var lastValidMs = -1L
        var lastPt: IntegrablePoint? = null

        for (pt in points) {
            val prev = lastPt
            if (prev == null) {
                lastPt = pt
                firstValidMs = pt.elapsedRealtimeMs
                lastValidMs = pt.elapsedRealtimeMs
                continue
            }
            val dtMs = pt.elapsedRealtimeMs - prev.elapsedRealtimeMs
            if (dtMs <= 0L) {
                // 过滤时间倒流或重复点，保持前驱锚点不变
                continue
            }
            if (dtMs <= maxGapMs) {
                validSlices++
                totalGapMs += dtMs
            }
            lastPt = pt
            lastValidMs = pt.elapsedRealtimeMs
        }

        if (firstValidMs < 0L || lastValidMs <= firstValidMs) {
            return 0.0
        }
        val totalSpanMs = (lastValidMs - firstValidMs).coerceAtLeast(1L)
        val coverageRatio = totalGapMs.toDouble() / totalSpanMs.toDouble()
        return coverageRatio.coerceIn(0.0, 1.0)
    }
}
