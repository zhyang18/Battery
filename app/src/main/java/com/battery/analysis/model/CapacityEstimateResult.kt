package com.battery.analysis.model

/**
 * 电池容量数据来源等级枚举。
 */
enum class CapacitySourceTier(val displayName: String) {
    /** 硬件驱动层直接读取并校验通过的电量计真实 FCC */
    HARDWARE_VERIFIED("硬件电量计直读"),
    /** 基于多次日常充放电历史会话高精微积分稳健学习收敛所得 */
    MULTI_SESSION_LEARNED("充放电会话自学习"),
    /** 系统出厂内置 PowerProfile 标称设计规格底线参考 */
    DESIGN_SPEC_PROFILE("出厂设计规格参考")
}

/**
 * 电池容量自学习模型的成熟度与置信收敛等级枚举。
 */
enum class MaturityLevel(val displayName: String) {
    /** 尚无有效会话样本，等待用户自然充放电积累数据 */
    PENDING("样本积累中 · 待校准"),
    /** 初步积累了 1~2 次有效会话，处于初始建模阶段 */
    INITIAL_OBSERVATION("初步建模中"),
    /** 积累了 ≥ 3 次有效会话且跨度充分，样本自洽收敛，达到工业级高置信度 */
    CONVERGED("高置信度 · 样本自洽"),
    /** 经过长时间、多温区与持续验证，形成高度稳定的容量基准 */
    STABLE_TRACKING("长期稳定追踪")
}

/**
 * 电池剩余容量、满充基准与自学习档案全景输出实体类。
 * 严格贯彻零假数据原则：在数据缺失或未收敛阶段如实返回 null，UI 呈现未获取状态，绝不捏造任何固定保底数据。
 *
 * @property remainingChargeMah 当前估算的剩余可用电荷量（单位：mAh，无可靠依据时为 null）
 * @property remainingEnergyWh 当前估算的剩余可用物理能量（单位：Wh，无可靠依据时为 null）
 * @property fullChargeCapacityMah 当前实际满充基准容量 FCC（单位：mAh，无可靠依据时为 null）
 * @property designCapacityMah 电池出厂额定设计容量（单位：mAh，无可靠依据时为 null）
 * @property sohPercent 真实电池容量健康度百分比（FCC ÷ 设计容量，无可靠依据时为 null）
 * @property currentSoc 当前电池电量百分比（0 至 100，未获取到系统广播时为 null）
 * @property sourceTier 当前采用的数据来源等级 [CapacitySourceTier]
 * @property maturityLevel 当前自学习模型的成熟度等级 [MaturityLevel]
 * @property validSessionCount 参与当前学习的有效会话总数（充放电合计）
 * @property chargeSessionCount 其中包含的有效充电会话数
 * @property dischargeSessionCount 其中包含的有效放电会话数
 * @property totalSocSpanCovered 累计覆盖的电量变化百分比总和（如 245% 表示累计跨度达到 2.45 个完整循环）
 * @property dispersionCoefficientPercent 样本加权离散度变异系数 CV（例如 1.82 表示 ±1.82%，越小代表自洽度越高）
 * @property conflictRateDPercent 充放电跨方向相对冲突差异率 D（百分比，若小于 10% 表明充放电一致性良好）
 * @property confidenceIntervalMah 基于样本方差计算的模型内部估计区间半径（单位：mAh，如 ±35 mAh，单样本或数据不足时为 null）
 * @property updateTimestampMs 本次估计的更新时间戳（毫秒）
 * @property isTemperatureModelCalibrated 当前设备电化学温度模型是否经过实测台架标定（默认 false，如实声明）
 */
data class CapacityEstimateResult(
    val remainingChargeMah: Double?,
    val remainingEnergyWh: Double?,
    val fullChargeCapacityMah: Double?,
    val designCapacityMah: Double?,
    val sohPercent: Double?,
    val currentSoc: Int?,
    val sourceTier: CapacitySourceTier,
    val maturityLevel: MaturityLevel,
    val validSessionCount: Int,
    val chargeSessionCount: Int,
    val dischargeSessionCount: Int,
    val totalSocSpanCovered: Int,
    val dispersionCoefficientPercent: Double?,
    val conflictRateDPercent: Double?,
    val confidenceIntervalMah: Double?,
    val updateTimestampMs: Long = System.currentTimeMillis(),
    val isTemperatureModelCalibrated: Boolean = false
) {
    /**
     * 判断当前是否具备向用户呈现有效数值的最低可信条件。
     *
     * @return 若具备有效容量数值返回 true，否则返回 false
     */
    fun hasValidCapacity(): Boolean {
        return fullChargeCapacityMah != null && fullChargeCapacityMah > 0.0
    }

    /**
     * 格式化输出剩余电荷量的友好文本。
     *
     * @return 格式化后的剩余电荷量文本（如 "3,420 mAh" 或 "-- mAh"）
     */
    fun getFormattedRemainingCharge(): String {
        return remainingChargeMah?.let {
            String.format(java.util.Locale.getDefault(), "%.0f mAh", it)
        } ?: "-- mAh"
    }

    /**
     * 格式化输出可用剩余能量的友好文本。
     *
     * @return 格式化后的可用能量文本（如 "13.17 Wh" 或 "-- Wh"）
     */
    fun getFormattedRemainingEnergy(): String {
        return remainingEnergyWh?.let {
            String.format(java.util.Locale.getDefault(), "%.2f Wh", it)
        } ?: "-- Wh"
    }

    /**
     * 格式化输出满充基准容量 FCC 的友好文本。
     *
     * @return 格式化后的 FCC 文本（如 "4,980 mAh" 或 "-- mAh"）
     */
    fun getFormattedFcc(): String {
        return fullChargeCapacityMah?.let {
            String.format(java.util.Locale.getDefault(), "%.0f mAh", it)
        } ?: "-- mAh"
    }

    /**
     * 格式化输出满充物理可用能量的友好文本。
     * 基于标称工作平台电压（3.85V）与满充容量 FCC 计算：FullEnergy(Wh) = (FCC * 3.85) / 1000。
     *
     * @return 格式化后的满充能量文本（如 "19.17 Wh" 或 "-- Wh"）
     */
    fun getFormattedFullEnergy(): String {
        return fullChargeCapacityMah?.let {
            val fullEnergyWh = (it * 3.85) / 1000.0
            String.format(java.util.Locale.getDefault(), "%.2f Wh", fullEnergyWh)
        } ?: "-- Wh"
    }

    /**
     * 格式化输出容量健康度 SOH 的友好文本。
     *
     * @return 格式化后的 SOH 文本（如 "97.2%" 或 "--%"）
     */
    fun getFormattedSoh(): String {
        return sohPercent?.let {
            String.format(java.util.Locale.getDefault(), "%.1f%%", it)
        } ?: "--%"
    }
}
