package com.battery.analysis.domain.estimator

import com.battery.analysis.domain.estimator.model.PowerSample
import com.battery.analysis.domain.estimator.model.ScreenPowerState
import kotlin.math.abs
import kotlin.math.exp

/**
 * 功耗估计器工作场景画像分类（Estimator Profile）。
 * 针对不同物理场景的方差、信噪比与使用习惯，施加专属的时间锚点与自适应收敛曲线。
 */
enum class EstimatorProfile {
    /** 亮屏使用场景：较高方差，需兼顾过滤冷启动峰值与对主任务（视频/小说/游戏）适度敏锐感知 */
    SCREEN_ON,

    /** 息屏待机场景：极低底噪、极小方差，最忌被偶发后台推送唤醒扰动，12 小时整夜与长待机稳态绝对压舱 */
    SCREEN_OFF,

    /** 全局综合场景：中等方差、昼夜交替，以 24 小时昼夜自然大闭环主导全局放电预期 */
    GLOBAL
}

/**
 * 自适应多时间尺度放电功耗估计器。
 * 融合 Fast（最近 ~5 分钟操作）、Medium（当前会话 ~30-60 分钟稳态）与 Long（近 7 天大样本习惯）三时间尺度，
 * 并结合 MAD 异常抑制与工况突变检测，动态求解未来放电的代表性预测功率。
 *
 * @param profile 估计器所处的工作场景画像分类 [EstimatorProfile]（默认 [EstimatorProfile.SCREEN_ON]）
 * @param anomalyDetector 异常功耗检测器（用于抑制孤立采样尖峰）
 * @param stateDetector 工况跃迁检测器（用于感应进入游戏或退出高负载）
 */
class PowerEstimator(
    val profile: EstimatorProfile = EstimatorProfile.SCREEN_ON,
    private val anomalyDetector: PowerAnomalyDetector = PowerAnomalyDetector(),
    private val stateDetector: PowerStateDetector = PowerStateDetector(minRegimeDurationSec = 60.0)
) {

    companion object {
        /** Fast 模型时间常数（秒）：5 分钟 = 300 秒 */
        private const val TAU_FAST_SEC = 300.0
        /** Medium 模型时间常数（秒）：30 分钟 = 1800 秒 */
        private const val TAU_MEDIUM_SEC = 1800.0
        /** 滑动对照窗口最大样本量上限 */
        private const val MAX_WINDOW_SIZE = 30

        /**
         * 亮屏专属时间锚点静态配置（动态平衡型，覆盖 5m, 10m, 20m, 30m, 60m, 120m 连续阶梯）：
         * 0min: (0.05, 0.15, 0.80)
         * 5min: (0.10, 0.20, 0.70)
         * 10min: (0.12, 0.28, 0.60)
         * 20min: (0.15, 0.35, 0.50)
         * 30min: (0.15, 0.45, 0.40)
         * 60min: (0.15, 0.55, 0.30)
         * >=120min: (0.15, 0.60, 0.25)
         */
        val SCREEN_ON_ANCHORS = listOf(
            WeightAnchor(0L, 0.05, 0.15, 0.80),
            WeightAnchor(300_000L, 0.10, 0.20, 0.70),      // 5 min
            WeightAnchor(600_000L, 0.12, 0.28, 0.60),      // 10 min
            WeightAnchor(1_200_000L, 0.15, 0.35, 0.50),    // 20 min
            WeightAnchor(1_800_000L, 0.15, 0.45, 0.40),    // 30 min
            WeightAnchor(3_600_000L, 0.15, 0.55, 0.30),    // 60 min (1h)
            WeightAnchor(7_200_000L, 0.15, 0.60, 0.25)     // 120 min (2h 终态)
        )

        /**
         * 息屏专属时间锚点静态配置（超稳低噪型，覆盖 4h, 12h 至 24h 连续阶梯）：
         * 0min: (0.05, 0.15, 0.80)
         * 5min: (0.08, 0.22, 0.70)
         * 10min: (0.10, 0.30, 0.60)
         * 30min: (0.10, 0.45, 0.45)
         * 60min: (0.10, 0.55, 0.35)
         * 120min: (0.10, 0.62, 0.28)
         * 240min (4h): (0.10, 0.70, 0.20)
         * 720min (12h): (0.10, 0.75, 0.15)
         * >=1440min (24h及以上终态): (0.10, 0.80, 0.10)
         */
        val SCREEN_OFF_ANCHORS = listOf(
            WeightAnchor(0L, 0.05, 0.15, 0.80),
            WeightAnchor(300_000L, 0.08, 0.22, 0.70),       // 5 min
            WeightAnchor(600_000L, 0.10, 0.30, 0.60),       // 10 min
            WeightAnchor(1_800_000L, 0.10, 0.45, 0.45),     // 30 min
            WeightAnchor(3_600_000L, 0.10, 0.55, 0.35),     // 60 min (1h)
            WeightAnchor(7_200_000L, 0.10, 0.62, 0.28),     // 120 min (2h)
            WeightAnchor(14_400_000L, 0.10, 0.70, 0.20),    // 240 min (4h)
            WeightAnchor(43_200_000L, 0.10, 0.75, 0.15),    // 720 min (12h)
            WeightAnchor(86_400_000L, 0.10, 0.80, 0.10)     // 1440 min (24h 终态)
        )

        /**
         * 全局专属时间锚点静态配置（宏观全天闭环型，覆盖 6h, 12h 至 24h 连续阶梯）：
         * 0min: (0.05, 0.15, 0.80)
         * 10min: (0.12, 0.28, 0.60)
         * 30min: (0.15, 0.40, 0.45)
         * 60min: (0.15, 0.50, 0.35)
         * 120min: (0.15, 0.55, 0.30)
         * 240min: (0.15, 0.60, 0.25)
         * 360min (6h): (0.15, 0.65, 0.20)
         * 720min (12h): (0.15, 0.70, 0.15)
         * >=1440min (24h及以上终态): (0.15, 0.75, 0.10)
         */
        val GLOBAL_ANCHORS = listOf(
            WeightAnchor(0L, 0.05, 0.15, 0.80),
            WeightAnchor(600_000L, 0.12, 0.28, 0.60),       // 10 min
            WeightAnchor(1_800_000L, 0.15, 0.40, 0.45),     // 30 min
            WeightAnchor(3_600_000L, 0.15, 0.50, 0.35),     // 60 min (1h)
            WeightAnchor(7_200_000L, 0.15, 0.55, 0.30),     // 120 min (2h)
            WeightAnchor(14_400_000L, 0.15, 0.60, 0.25),    // 240 min (4h)
            WeightAnchor(21_600_000L, 0.15, 0.65, 0.20),    // 360 min (6h)
            WeightAnchor(43_200_000L, 0.15, 0.70, 0.15),    // 720 min (12h)
            WeightAnchor(86_400_000L, 0.15, 0.75, 0.10)     // 1440 min (24h 终态)
        )

        /** 兼容旧代码的静态访问别名，默认指向 SCREEN_ON_ANCHORS */
        val WEIGHT_ANCHORS get() = SCREEN_ON_ANCHORS
    }

    /** 最近样本滑动窗口，用于计算 Median + MAD */
    private val recentSamples = mutableListOf<Float>()

    /** 上次采样时间戳（毫秒） */
    private var lastSampleTimestampMs: Long = 0L

    /** 当前跟踪的有效统计总物理观测时长（毫秒） */
    var observationDurationMs: Long = 0L
        private set

    /** 兼容旧代码访问别名，指向 [observationDurationMs] */
    val totalSessionDurationMs: Long
        get() = observationDurationMs

    /** Fast 尺度（~5min）EWMA 估算功率（单位：W） */
    private var fastPowerWatts: Double? = null

    /** Medium 尺度（~30-60min）稳态滑动估算功率（单位：W） */
    private var mediumPowerWatts: Double? = null

    /** Long 尺度（近 7 天）大样本加权基准功率（单位：W） */
    private var longPowerWatts: Double? = null

    /** 累计有效物理样本点总数 */
    var sampleCount: Int = 0
        private set

    /**
     * 获取工况跃迁检测器当前是否处于持续突变激活状态。
     */
    val isRegimeChanging: Boolean
        get() = stateDetector.isRegimeChanging

    /**
     * 获取工况突变退出后的连续平滑衰减过渡比例（1.0 连续衰减至 0.0）。
     */
    val recoveryDecayRatio: Double
        get() = stateDetector.recoveryDecayRatio


    /**
     * 重置估计器所有内部状态与缓存数据。
     *
     * @param initialLongPowerWatts 可选的初始 7 天长期基准功耗（单位：W）
     */
    fun reset(initialLongPowerWatts: Double? = null) {
        recentSamples.clear()
        lastSampleTimestampMs = 0L
        observationDurationMs = 0L
        fastPowerWatts = null
        mediumPowerWatts = null
        longPowerWatts = initialLongPowerWatts?.takeIf { it.isFinite() && it > 0.0 }
        sampleCount = 0
        longQualityScore = 1.0
        stateDetector.reset(longPowerWatts)
    }

    /**
     * 设置或更新过去 7 天长期历史大样本基准功率。
     *
     * @param powerWatts 7 天加权平均放电功率（单位：W，若无历史数据传入 null）
     */
    fun setLongTermPower(powerWatts: Double?) {
        longPowerWatts = powerWatts?.takeIf { it.isFinite() && it > 0.0 }
        if (stateDetector.baselinePowerWatts == null && longPowerWatts != null) {
            stateDetector.reset(longPowerWatts)
        }
    }

    /**
     * 直接注入连续滑动窗口或当前统计的宏观物理稳态均值（用于快速初始化与冷启动同步）。
     *
     * @param durationMs 已持续的物理观测时长（毫秒）
     * @param powerWatts 测得的加权平均物理功率（单位：W）
     */
    fun seedMediumTermPower(durationMs: Long, powerWatts: Float) {
        if (durationMs >= 0L && powerWatts.isFinite() && powerWatts > 0f) {
            observationDurationMs = durationMs
            val p = powerWatts.toDouble()
            fastPowerWatts = p
            mediumPowerWatts = p
            sampleCount = sampleCount.coerceAtLeast(1)
            stateDetector.reset(p)
        }
    }

    /**
     * 兼容旧接口别名，直接委托给 [seedMediumTermPower]。
     *
     * @param durationMs 已持续的物理观测时长（毫秒）
     * @param powerWatts 测得的加权平均物理功率（单位：W）
     */
    fun seedCurrentSession(durationMs: Long, powerWatts: Float) {
        seedMediumTermPower(durationMs, powerWatts)
    }

    /**
     * 单独设置 Medium 尺度（当前放电滑动稳态）估算功率与观测时长，保持 Fast 尺度不变或空置。
     * 用于从持久化滑动统计加载稳态基准，但尚无近期细粒度采样点的场景。
     *
     * @param durationMs 已持续物理时长（单位：毫秒）
     * @param powerWatts 测得的平均物理功率（单位：W）
     */
    fun setMediumTermPower(durationMs: Long, powerWatts: Float) {
        if (durationMs >= 0L && powerWatts.isFinite() && powerWatts > 0f) {
            observationDurationMs = durationMs
            val p = powerWatts.toDouble()
            mediumPowerWatts = p
            sampleCount = sampleCount.coerceAtLeast(1)
            if (stateDetector.baselinePowerWatts == null) {
                stateDetector.reset(p)
            }
        }
    }

    /**
     * 标记或注入外部工况突变状态。
     * 当外部业务层（如亮灭屏、启动大型应用等）识别到工况跃迁时通知估计器，用于敏捷自适应加权调整。
     *
     * @param isChanging 是否正处于工况突变跃迁状态
     */
    fun notifyRegimeChange(isChanging: Boolean) {
        if (isChanging) {
            val base = mediumPowerWatts ?: fastPowerWatts ?: longPowerWatts ?: 2.0
            stateDetector.reset(base)
            // 连续输入偏离样本以激活检测器的工况突变标志与响应乘数（确保累计物理偏离时长达到 minRegimeDurationSec 门限）
            stateDetector.update((base * 2.5).toFloat(), deltaSec = 60.0)
            stateDetector.update((base * 2.5).toFloat(), deltaSec = 60.0)
        } else {
            val base = mediumPowerWatts ?: fastPowerWatts ?: longPowerWatts ?: 2.0
            stateDetector.reset(base)
        }
    }

    /**
     * 向估计器注入一个新的物理电池采样点并更新各时间尺度模型。
     * 充电状态数据将被严格拦截，不进入放电功耗模型。
     *
     * @param sample 包含电压、功率、状态与时间戳的物理采样数据包 [PowerSample]
     */
    fun update(sample: PowerSample) {
        // 充电状态数据与无效浮点数严禁污染放电估计器
        if (sample.state == ScreenPowerState.CHARGING || !sample.powerWatts.isFinite() || sample.powerWatts <= 0f) {
            return
        }

        val power = sample.powerWatts
        val currentTs = sample.timestampMs

        // 1. 计算自适应采样间隔与时长累加
        val deltaSec = if (lastSampleTimestampMs > 0L && currentTs > lastSampleTimestampMs) {
            ((currentTs - lastSampleTimestampMs) / 1000.0).coerceIn(0.1, 300.0)
        } else {
            1.0 // 默认首帧或微小间隔 1s
        }
        lastSampleTimestampMs = currentTs
        observationDurationMs += (deltaSec * 1000.0).toLong()
        sampleCount++

        // 2. 工况迁移检测（先识别是否已处于跃迁或发生跃迁触发，传入 deltaSec 累计持续物理时间）
        stateDetector.update(power, deltaSec)
        val isChanging = stateDetector.isRegimeChanging
        val responsiveness = stateDetector.getResponsivenessMultiplier()

        // 3. MAD 异常检验与置信度权重计算（联动工况跃迁标志，跃迁激活期不将持续高负载误杀为尖峰）
        val sampleWeight = anomalyDetector.getSampleWeight(power, recentSamples, isChanging)
        recentSamples.add(power)
        if (recentSamples.size > MAX_WINDOW_SIZE) {
            recentSamples.removeAt(0)
        }

        // 极端脉冲物理有效持续时间修正：
        // 冷启动等 15W~18W 瞬态在物理上仅持续 1~2 秒，若为离群脉冲，有效时间步长受物理现实约束（<= 2.0s），杜绝 EWMA 能量过度积分
        val effectiveDeltaSec = when {
            profile == EstimatorProfile.SCREEN_OFF -> deltaSec.coerceAtMost(3.0)
            sampleWeight <= 0.25 || (power > 5.0f && power > (fastPowerWatts?.toFloat() ?: 1.0f) * 3.5f) -> deltaSec.coerceAtMost(2.0)
            else -> deltaSec
        }

        // 4. Fast 模型更新（真实连续时间物理 EWMA: alpha = 1 - exp(-dt / tau)，上限平滑约束在 0.30 避免单点支配）
        val rawAlphaFast = 1.0 - exp(-effectiveDeltaSec / TAU_FAST_SEC)
        val effectiveAlphaFast = (rawAlphaFast * sampleWeight * responsiveness).coerceIn(0.001, 0.30)
        fastPowerWatts = if (fastPowerWatts == null) {
            power.toDouble()
        } else {
            (effectiveAlphaFast * power.toDouble()) + ((1.0 - effectiveAlphaFast) * fastPowerWatts!!)
        }

        // 5. Medium 模型更新（更平缓的 EWMA）
        val rawAlphaMedium = 1.0 - exp(-effectiveDeltaSec / TAU_MEDIUM_SEC)
        val effectiveAlphaMedium = (rawAlphaMedium * sampleWeight).coerceIn(0.0005, 0.15)
        mediumPowerWatts = if (mediumPowerWatts == null) {
            power.toDouble()
        } else {
            (effectiveAlphaMedium * power.toDouble()) + ((1.0 - effectiveAlphaMedium) * mediumPowerWatts!!)
        }
    }

    /**
     * 流式批量重放注入当前放电周期的历史细粒度时序采样点集合。
     * 用于从时序采样点中激活 Fast 尺度实时跟踪、MAD 异常抑制与工况突变感知（PowerStateDetector）。
     * 若在注入前已通过 [seedMediumTermPower] 设定了宏观物理观测时长 [observationDurationMs]，
     * 则优先保持真实宏观物理总时长守恒与 Medium 宏观物理稳态基石，杜绝采样点 deltaMs 重复叠加导致时长虚高或局部刷新瞬态冲垮全局；
     * 若此前尚未设定宏观时长，则依序累加点集跨度并平缓更新 Medium 尺度。
     *
     * @param samples 包含电压、功率、状态与时间戳的物理电池采样点列表 [PowerSample]
     */
    fun feedSessionTrendSamples(samples: List<PowerSample>) {
        if (samples.isEmpty()) return
        val validSamples = samples.filter { it.state != ScreenPowerState.CHARGING && it.powerWatts.isFinite() && it.powerWatts > 0f }
            .sortedBy { it.timestampMs }
        if (validSamples.isEmpty()) return

        val presetDurationMs = observationDurationMs
        var pointsAccumulatedDurationMs = 0L
        var prevTs = 0L

        for (sample in validSamples) {
            val power = sample.powerWatts
            val currentTs = sample.timestampMs
            val deltaSec = if (prevTs > 0L && currentTs > prevTs) {
                ((currentTs - prevTs) / 1000.0).coerceIn(0.1, 300.0)
            } else {
                1.0
            }
            prevTs = currentTs
            pointsAccumulatedDurationMs += (deltaSec * 1000.0).toLong()
            lastSampleTimestampMs = currentTs
            sampleCount++

            // 工况迁移检测（累积持续物理时间，只有真正持续稳定做功 >= 60s 才确立新工况）
            stateDetector.update(power, deltaSec)
            val isChanging = stateDetector.isRegimeChanging
            val responsiveness = stateDetector.getResponsivenessMultiplier()

            // MAD 异常检验
            val sampleWeight = anomalyDetector.getSampleWeight(power, recentSamples, isChanging)
            recentSamples.add(power)
            if (recentSamples.size > MAX_WINDOW_SIZE) {
                recentSamples.removeAt(0)
            }

            // 极端脉冲物理有效持续时间修正：
            // 点集抽稀后采样点时间跨度可能达到 10s~30s，若单个点捕获了 App 冷启动 15W~18W 脉冲，
            // 其真实物理做功持续期不超过 2.0s，单点有效时间步长必须受物理现实约束，杜绝虚假能量放大
            val effectiveDeltaSec = when {
                profile == EstimatorProfile.SCREEN_OFF -> deltaSec.coerceAtMost(3.0)
                sampleWeight <= 0.25 || (power > 5.0f && power > (fastPowerWatts?.toFloat() ?: 1.0f) * 3.5f) -> deltaSec.coerceAtMost(2.0)
                else -> deltaSec
            }

            // Fast 模型更新 (真实物理连续 EWMA)
            val rawAlphaFast = 1.0 - exp(-effectiveDeltaSec / TAU_FAST_SEC)
            val effectiveAlphaFast = (rawAlphaFast * sampleWeight * responsiveness).coerceIn(0.001, 0.30)
            fastPowerWatts = if (fastPowerWatts == null) {
                power.toDouble()
            } else {
                (effectiveAlphaFast * power.toDouble()) + ((1.0 - effectiveAlphaFast) * fastPowerWatts!!)
            }

            // Medium 尺度：若外部已预设滑动窗口的宏观累计物理平均功耗，Medium 作为物理稳态基石保持锚定；
            // 仅在无预设宏观功耗时由采样点驱动更新
            if (presetDurationMs <= 0L) {
                val rawAlphaMedium = 1.0 - exp(-effectiveDeltaSec / TAU_MEDIUM_SEC)
                val effectiveAlphaMedium = (rawAlphaMedium * sampleWeight).coerceIn(0.0005, 0.15)
                mediumPowerWatts = if (mediumPowerWatts == null) {
                    power.toDouble()
                } else {
                    (effectiveAlphaMedium * power.toDouble()) + ((1.0 - effectiveAlphaMedium) * mediumPowerWatts!!)
                }
            }
        }

        observationDurationMs = if (presetDurationMs > 0L) {
            kotlin.math.max(presetDurationMs, pointsAccumulatedDurationMs)
        } else {
            pointsAccumulatedDurationMs
        }
    }

    /**
     * 获取 Fast 尺度估算功率。
     *
     * @return Fast 估算放电功率（单位：W，缺失时为 null）
     */
    fun getFastPower(): Double? = fastPowerWatts?.takeIf { it > 0.0 }

    /**
     * 获取 Medium 尺度估算功率。
     *
     * @return Medium 估算放电功率（单位：W，缺失时为 null）
     */
    fun getMediumPower(): Double? = mediumPowerWatts?.takeIf { it > 0.0 }

    /**
     * 获取 Long 尺度估算功率。
     *
     * @return Long 估算放电功率（单位：W，缺失时为 null）
     */
    fun getLongPower(): Double? = longPowerWatts?.takeIf { it > 0.0 }

    /**
     * 会话时间尺度权重锚点定义数据包。
     *
     * @property durationMs 放电会话物理持续时间戳（毫秒）
     * @property wf Fast 尺度基础权重
     * @property wm Medium 尺度基础权重
     * @property wl Long 尺度基础权重
     */
    data class WeightAnchor(
        val durationMs: Long,
        val wf: Double,
        val wm: Double,
        val wl: Double
    )

    /** 长期先验大样本数据质量评分（0.0 ~ 1.0，默认 1.0） */
    private var longQualityScore: Double = 1.0

    /**
     * 设置或更新长期先验（7 天）大样本的数据质量与充分度评分。
     *
     * @param quality 长期先验质量分（0.0 至 1.0，根据历史有效时长与工况匹配度确定）
     */
    fun setLongTermQuality(quality: Double) {
        longQualityScore = quality.coerceIn(0.1, 1.0)
    }

    /**
     * 评估 Fast 尺度当前的数据质量评分 C_fast（0.0 至 1.0）。
     * 当有效功率存在时质量基准为 1.0；若无该数据源则为 0.0。
     *
     * @return Fast 尺度数据质量评分
     */
    fun getFastDataQuality(): Double {
        if (fastPowerWatts == null || fastPowerWatts!! <= 0.0) return 0.0
        return 1.0
    }

    /**
     * 评估 Medium 尺度当前的数据质量评分 C_medium（0.0 至 1.0）。
     * 当有效功率存在时质量基准为 1.0；若无该数据源则为 0.0。
     *
     * @return Medium 尺度数据质量评分
     */
    fun getMediumDataQuality(): Double {
        if (mediumPowerWatts == null || mediumPowerWatts!! <= 0.0) return 0.0
        return 1.0
    }

    /**
     * 评估 Long 尺度当前的数据质量评分 C_long（0.0 至 1.0）。
     *
     * @return Long 尺度数据质量评分
     */
    fun getLongDataQuality(): Double {
        if (longPowerWatts == null || longPowerWatts!! <= 0.0) return 0.0
        return longQualityScore
    }

    /**
     * 结合当前已积累的数据量、多尺度间方差稳定性与工况迁移状态，
     * 自适应计算 Fast/Medium/Long 三个模型的动态归一化权重。
     * 严格遵循凸组合守恒：所有存在的尺度权重和恒等于 1.0，不存在的尺度权重严格为 0.0。
     * 权重计算公式：w_k = (w_k^time * C_k * M_k(R)) / Sum。
     *
     * @return 包含 (w_fast, w_medium, w_long) 且有效项之和为 1.0 的权重三元组 [Triple]
     */
    fun calculateAdaptiveWeights(): Triple<Double, Double, Double> {
        val hasFast = fastPowerWatts != null && fastPowerWatts!! > 0.0
        val hasMedium = mediumPowerWatts != null && mediumPowerWatts!! > 0.0
        val hasLong = longPowerWatts != null && longPowerWatts!! > 0.0

        if (!hasFast && !hasMedium && !hasLong) {
            return Triple(0.0, 0.0, 0.0)
        }

        // 仅有一个有效数据源时，直接赋予 100% 权重，彻底杜绝除零或越界
        if (hasFast && !hasMedium && !hasLong) return Triple(1.0, 0.0, 0.0)
        if (!hasFast && hasMedium && !hasLong) return Triple(0.0, 1.0, 0.0)
        if (!hasFast && !hasMedium && hasLong) return Triple(0.0, 0.0, 1.0)

        // 1. 依据当前放电时序已持续物理观测时长在 7 级时间锚点间进行连续平滑线性插值
        val (rawWf, rawWm, rawWl) = interpolateBaselineWeights(observationDurationMs)

        // 2. 依据各时间尺度的数据质量因子 (C_fast, C_medium, C_long) 进行可靠性加权
        val cFast = getFastDataQuality()
        val cMed = getMediumDataQuality()
        val cLong = getLongDataQuality()

        val initWf = if (hasFast) rawWf * cFast else 0.0
        val initWm = if (hasMedium) rawWm * cMed else 0.0
        val initWl = if (hasLong) rawWl * cLong else 0.0
        val initSum = initWf + initWm + initWl

        val baseWf = if (initSum > 0.0) initWf / initSum else 0.0
        val baseWm = if (initSum > 0.0) initWm / initSum else 0.0
        val baseWl = if (initSum > 0.0) initWl / initSum else 0.0

        // 3. 在有效数据源集合内应用工况突变增益或平滑衰减过渡调整
        return applyRegimeChangeIfNeeded(
            baseWf = baseWf,
            baseWm = baseWm,
            baseWl = baseWl,
            hasFast = hasFast,
            hasMedium = hasMedium,
            hasLong = hasLong
        )
    }

    /**
     * 根据工况突变状态对基准权重进行动态凸组合调整，并严格约束仅在有效数据源间分配。
     * 涵盖突变激活期（快速倾斜响应）与退出突变后的连续平滑阻尼过渡期（衰减恢复至基线）。
     *
     * 动态分配策略：
     * 1. 发生工况突变或处于衰减过渡期时：优先将响应权重向 Fast 尺度倾斜；若 Fast 不存在但 Medium 存在，则向 Medium 倾斜；
     *    严禁给不存在（null）的尺度分配任何突变权重，防止有效权重凭空蒸发导致预测功率暴跌；
     * 2. 稳态运行且 Fast 与 Medium 接近时：适度降低 Long 依赖，增强当前会话代表性；
     * 3. 最终严格执行重新归一化消除微小浮点误差，确保权重和恒等于 1.0。
     *
     * @param baseWf Fast 基础权重（若无该数据源传入 0.0）
     * @param baseWm Medium 基础权重（若无该数据源传入 0.0）
     * @param baseWl Long 基础权重（若无该数据源传入 0.0）
     * @param hasFast 是否具备有效的 Fast 尺度数据源
     * @param hasMedium 是否具备有效的 Medium 尺度数据源
     * @param hasLong 是否具备有效的 Long 尺度数据源
     * @return 经过工况响应与归一化后的 (w_fast, w_medium, w_long) 权重三元组 [Triple]
     */
    private fun applyRegimeChangeIfNeeded(
        baseWf: Double,
        baseWm: Double,
        baseWl: Double,
        hasFast: Boolean,
        hasMedium: Boolean,
        hasLong: Boolean
    ): Triple<Double, Double, Double> {
        val isTransitioning = stateDetector.isRegimeChanging || stateDetector.recoveryDecayRatio > 0.0
        val (finalWf, finalWm, finalWl) = if (isTransitioning) {
            val mult = stateDetector.getResponsivenessMultiplier()
            // 计算自适应响应因子 gamma：
            // 若无 7 天长期数据（!hasLong，如新安装或无历史统计），施加稳态阻尼保护，gamma 约束在 0.20 至 0.35 之间，
            // 确保 Medium 尺度（当前稳态均值）至少保留 50%~65% 压舱权重，杜绝冷启动初始化尖峰主导全局预测；
            // 若具备 7 天长期数据，则保持 0.40 至 0.85 的敏捷突变响应能力。
            val gamma = if (!hasLong) {
                (0.20 + (mult - 1.0) * 0.15).coerceIn(0.20, 0.35)
            } else {
                val rawGamma = (0.30 + (mult - 1.0) * 0.40).coerceIn(0.40, 0.85)
                if (observationDurationMs < 300_000L) {
                    val progress = (observationDurationMs.toDouble() / 300_000.0).coerceIn(0.0, 1.0)
                    // 放电时序观测极早期（前 5 分钟 / 300秒）工况突变时间阻尼保护：
                    // 观测早期样本较少，单次刷新操作或点亮瞬态极易产生虚假突变；
                    // 引入连续平滑时间阻尼，确保 7 天长期习惯保持压舱石作用，杜绝刷新时 Fast 权重脉冲翻转；
                    // 从 0 分钟时的 0.10 平滑过渡到 5 分钟时的 full rawGamma
                    0.10 + progress * (rawGamma - 0.10)
                } else {
                    rawGamma
                }
            }

            when {
                // 首选：Fast 存在，向 Fast 尺度快速倾斜
                hasFast -> {
                    if (profile == EstimatorProfile.SCREEN_OFF) {
                        // 息屏待机专属低噪保护：
                        // 锁屏待机功耗主要由硬件底噪与唤醒占空比决定，具有极强的物理惯性与稳定性；
                        // 待机预测以 Medium（当前会话稳态均值）与 Long（7天大样本先验）为核心基石，
                        // Fast 仅用于瞬态微调，权重上限严格锁定在 0.20，Medium 保底 0.52，Long 保底 0.28
                        val maxFast = 0.20
                        val targetWf = (baseWf + 0.05 * (mult - 1.0)).coerceIn(0.05, maxFast)
                        val remaining = (1.0 - targetWf).coerceAtLeast(0.0)
                        val targetWm = if (hasMedium && hasLong) remaining * 0.65 else if (hasMedium) remaining else 0.0
                        val targetWl = if (hasLong && hasMedium) remaining * 0.35 else if (hasLong) remaining else 0.0
                        Triple(targetWf, targetWm, targetWl)
                    } else if (observationDurationMs >= 1_200_000L && hasMedium) {
                        // 若当前放电时序已持续观测 20 分钟以上，Medium 尺度汇聚了数千焦耳的宏观物理稳态。
                        // 针对不同场景施加专属的自适应压舱保护与限幅：
                        // - 全局综合：Fast 上限严格锁定在 0.25（25%），Medium 保底 0.55（55%）；
                        // - 亮屏使用：Fast 上限锁定在 0.30（30%），Medium 保底 0.45（45%）。
                        val (minMediumBase, maxFastLimit) = when (profile) {
                            EstimatorProfile.SCREEN_OFF -> 0.60 to 0.20
                            EstimatorProfile.GLOBAL -> 0.55 to 0.25
                            EstimatorProfile.SCREEN_ON -> 0.45 to 0.30
                        }
                        val targetWm = (baseWm * 0.90).coerceAtLeast(minMediumBase).coerceAtMost(0.75)
                        val remainingForFL = (1.0 - targetWm).coerceAtLeast(0.0)
                        val rawWfShift = baseWf + 0.10 * (mult - 1.0)
                        val targetWf = if (hasLong) rawWfShift.coerceIn(0.10, maxFastLimit).coerceAtMost(remainingForFL) else remainingForFL
                        val targetWl = if (hasLong) (remainingForFL - targetWf).coerceAtLeast(0.0) else 0.0
                        Triple(targetWf, targetWm, targetWl)
                    } else {
                        val wfPrime = baseWf + gamma * (1.0 - baseWf)
                        val wmPrime = if (hasMedium) (1.0 - gamma) * baseWm else 0.0
                        val wlPrime = if (hasLong) (1.0 - gamma) * baseWl else 0.0
                        Triple(wfPrime, wmPrime, wlPrime)
                    }
                }
                // 次选：Fast 不存在但 Medium 存在（如只有 Medium + Long），向当前最快的 Medium 尺度倾斜
                hasMedium && hasLong -> {
                    val wmPrime = baseWm + gamma * (1.0 - baseWm)
                    val wlPrime = (1.0 - gamma) * baseWl
                    Triple(0.0, wmPrime, wlPrime)
                }
                else -> {
                    Triple(baseWf, baseWm, baseWl)
                }
            }
        } else {
            // 稳态下若 Fast 与 Medium 均存在且差异较小（稳定运行），连续平滑降低 Long 依赖（消除硬编码阶跃跳变）
            if (hasFast && hasMedium && hasLong) {
                val f = fastPowerWatts ?: 0.0
                val m = mediumPowerWatts ?: 0.0
                if (f > 0.0 && m > 0.0 && baseWl > 0.0) {
                    val relDiff = abs(f - m) / m
                    val factor = ((0.20 - relDiff) / 0.20).coerceIn(0.0, 1.0)
                    val shift = baseWl * 0.15 * factor
                    Triple(baseWf, baseWm + shift, baseWl - shift)
                } else {
                    Triple(baseWf, baseWm, baseWl)
                }
            } else {
                Triple(baseWf, baseWm, baseWl)
            }
        }

        // 严格消除微小浮点精度误差，确保有效项之和严格恒等于 1.0
        val sum = finalWf + finalWm + finalLCorrection(finalWl, hasLong)
        return if (sum > 0.0) {
            val normWf = if (hasFast) finalWf / sum else 0.0
            val normWm = if (hasMedium) finalWm / sum else 0.0
            val normWl = if (hasLong) (1.0 - normWf - normWm).coerceAtLeast(0.0) else 0.0
            Triple(normWf, normWm, normWl)
        } else {
            Triple(0.0, 0.0, 1.0)
        }
    }

    /**
     * 辅助校验 Long 权重的存在性。
     *
     * @param wl 计算得出的 Long 权重数值
     * @param hasLong 是否具备有效的 Long 尺度数据源
     * @return 校验修正后的 Long 权重数值
     */
    private fun finalLCorrection(wl: Double, hasLong: Boolean): Double {
        return if (hasLong) wl else 0.0
    }

    /**
     * 根据会话持续物理时长在当前场景专属的时间锚点间进行连续线性插值，求解平滑连续的基础权重分布。
     *
     * @param durationMs 当前放电会话物理持续毫秒数
     * @return 连续平滑插值后的 (wFast, wMedium, wLong) 基础权重三元组，和恒等于 1.0
     */
    fun interpolateBaselineWeights(durationMs: Long): Triple<Double, Double, Double> {
        val anchors = when (profile) {
            EstimatorProfile.SCREEN_ON -> SCREEN_ON_ANCHORS
            EstimatorProfile.SCREEN_OFF -> SCREEN_OFF_ANCHORS
            EstimatorProfile.GLOBAL -> GLOBAL_ANCHORS
        }
        if (durationMs <= anchors.first().durationMs) {
            val first = anchors.first()
            return Triple(first.wf, first.wm, first.wl)
        }
        val last = anchors.last()
        if (durationMs >= last.durationMs) {
            return Triple(last.wf, last.wm, last.wl)
        }

        // 线性扫描区间 [a0, a1] 进行连续平滑插值
        for (i in 0 until anchors.size - 1) {
            val a0 = anchors[i]
            val a1 = anchors[i + 1]
            if (durationMs in a0.durationMs..a1.durationMs) {
                val span = (a1.durationMs - a0.durationMs).toDouble()
                val p = if (span > 0.0) ((durationMs - a0.durationMs).toDouble() / span).coerceIn(0.0, 1.0) else 0.0
                val wf = a0.wf + p * (a1.wf - a0.wf)
                val wm = a0.wm + p * (a1.wm - a0.wm)
                val wl = a0.wl + p * (a1.wl - a0.wl)
                val sum = wf + wm + wl
                return if (sum > 0.0) Triple(wf / sum, wm / sum, wl / sum) else Triple(a0.wf, a0.wm, a0.wl)
            }
        }

        return Triple(last.wf, last.wm, last.wl)
    }

    /**
     * 计算并获取经过三时间尺度自适应加权后的最终综合预测放电功率。
     *
     * @return 预测的放电物理功率（单位：W，若无有效数据返回 null）
     */
    fun getPredictedPower(): Double? {
        val (wf, wm, wl) = calculateAdaptiveWeights()
        val f = fastPowerWatts ?: 0.0
        val m = mediumPowerWatts ?: 0.0
        val l = longPowerWatts ?: 0.0

        val predicted = (wf * f) + (wm * m) + (wl * l)
        return if (predicted > 0.0) predicted else null
    }

    /**
     * 计算当前功耗预测模型的综合可信度指数（Confidence）。
     * 对齐 0m, 5m, 10m, 20m, 30m, 60m, 120m 七个时间尺度。
     *
     * @return 介于 0.0 至 1.0 之间的置信度指数
     */
    fun getConfidence(): Double {
        val hasCurrent = (fastPowerWatts != null || mediumPowerWatts != null)
        val hasLong = (longPowerWatts != null)

        if (!hasCurrent && !hasLong) return 0.0

        var baseScore = when {
            observationDurationMs >= 7_200_000L -> 0.95 // >= 120min 超稳态
            observationDurationMs >= 3_600_000L -> 0.85 // >= 60min
            observationDurationMs >= 1_800_000L -> 0.75 // >= 30min
            observationDurationMs >= 1_200_000L -> 0.65 // >= 20min
            observationDurationMs >= 600_000L -> 0.55   // >= 10min
            observationDurationMs >= 300_000L -> 0.45   // >= 5min
            observationDurationMs > 0L -> 0.35          // < 5min
            else -> 0.30
        }

        if (hasLong) baseScore += 0.05
        if (stateDetector.isRegimeChanging) baseScore -= 0.15

        return baseScore.coerceIn(0.10, 1.0)
    }
}
