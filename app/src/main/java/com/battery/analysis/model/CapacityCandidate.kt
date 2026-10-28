package com.battery.analysis.model

/**
 * 候选容量会话类型枚举。
 */
enum class CandidateSessionType {
    /** 充电会话产生的候选样本 */
    CHARGE,
    /** 放电会话产生的候选样本 */
    DISCHARGE
}

/**
 * 单次充放电会话生成的满充容量（FCC）候选样本数据实体类。
 * 记录单次完整会话的净充放电荷量、电量跨度、推算的 FCC 观测值、起止时间戳及质量信息。
 *
 * @property id 唯一标识符（通常使用会话结束时间戳毫秒值）
 * @property sessionType 会话类型 [CandidateSessionType]
 * @property startSoc 起始电量百分比（0 至 100）
 * @property endSoc 结束电量百分比（0 至 100）
 * @property socSpanPercent 绝对电量跨度（如从 20% 充至 65%，跨度为 45）
 * @property netBatteryChargeMah 电池端积分测得的绝对净物理电荷量（单位：mAh）
 * @property fccObservedMah 本次会话推算出的满充容量单点观测值（单位：mAh）
 * @property startTimestamp 会话开始绝对时间戳（毫秒）
 * @property endTimestamp 会话结束绝对时间戳（毫秒）
 * @property durationMs 会话持续时长（毫秒）
 * @property avgTemperatureCelsius 会话期间的平均电芯温度（单位：℃）
 * @property maxTemperatureCelsius 会话期间的最高电芯温度（单位：℃）
 * @property sampleCount 参与本次积分的有效采样点数量
 * @property qualityScore 本次样本的综合质检评分（0.0 至 1.0）
 * @property isExcluded 是否被质检过滤器或离群点算法排除
 * @property exclusionReason 被排除的具体原因说明（若有效则为空）
 */
data class CapacityCandidate(
    val id: Long,
    val sessionType: CandidateSessionType,
    val startSoc: Int,
    val endSoc: Int,
    val socSpanPercent: Int,
    val netBatteryChargeMah: Double,
    val fccObservedMah: Double,
    val startTimestamp: Long,
    val endTimestamp: Long,
    val durationMs: Long,
    val avgTemperatureCelsius: Float,
    val maxTemperatureCelsius: Float,
    val sampleCount: Int,
    val qualityScore: Double,
    val isExcluded: Boolean = false,
    val exclusionReason: String = ""
) {
    /**
     * 获取归一化电量变化跨度比例 Δz（0.0 至 1.0）。
     *
     * @return 归一化电量变化跨度
     */
    fun getNormalizedSocSpan(): Double {
        return socSpanPercent.coerceIn(1, 100) / 100.0
    }
}
