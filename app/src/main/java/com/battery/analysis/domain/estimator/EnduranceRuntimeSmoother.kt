package com.battery.analysis.domain.estimator

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * 电池剩余续航时间时序连续平滑器（Endurance Runtime Smoother）。
 * 针对亮屏（Screen-On）、息屏（Screen-Off）与全局（Global）放电维度独立维护时序状态，
 * 基于真实物理挂钟时间的自然流逝实施单向递减衰减，并结合 [PowerStateDetector] 的工况突变识别
 * 与非对称自适应阻尼滤波（Asymmetric Adaptive Damping），彻底消除同一分钟内刷新跳变与续航倒涨缺陷，
 * 保障剩余续航在真实物理做功下的平滑单调与线性下降。
 *
 * 核心物理准则与控制工程设计：
 * 1. 真实挂钟时间单向扣除：在无新工况突变与能量未注入时，系统真实流逝的时间 Δt 刚性从剩余续航中线性扣除；
 * 2. 重载工况敏捷阻尼收敛：当设备确立进入重载（isRegimeChanging == true）时，缩短收敛时间常数，平滑但敏捷地跟进高功耗；
 * 3. 轻载工况非对称阻尼防护：当设备退出重载切入低功耗时，严格限制向上反弹斜率，呈现为“极耐用、缓慢放电”的平缓过渡，坚决杜绝剩余续航逆向倒流；
 * 4. 纯物理能量注入自适应重基准（去拔电会话化）：通过感知物理电量 SOC 的增加自动识别外源充能，以全新物理能量确立基线，无需任何外部充电状态入侵；
 * 5. 零假数据原则：数据缺失或瞬时推算不可用时如实返回 null，绝不伪造硬编码占位数据。
 */
class EnduranceRuntimeSmoother {

    companion object {
        /** 亮屏放电维度标识 */
        const val DIMENSION_SCREEN_ON = 1

        /** 息屏待机放电维度标识 */
        const val DIMENSION_SCREEN_OFF = 2

        /** 全局综合放电维度标识 */
        const val DIMENSION_GLOBAL = 3

        /** 稳态放电时间常数（秒）：300 秒（5 分钟），用于稳态微小噪声滤波 */
        private const val TAU_STEADY_SEC = 300.0

        /** 重载突变响应时间常数（秒）：45 秒，用于重载确认后敏捷平滑跟进 */
        private const val TAU_HEAVY_SEC = 45.0

        /** 轻载目标恢复时间常数（秒）：1800 秒（30 分钟），用于退出重载后平缓向轻载目标渐近收敛 */
        private const val TAU_RISE_SEC = 1800.0

        /** 目标续航变长时的正向最大变化率（秒/秒）：0.25，即挂钟每流逝 1 秒，表显最多允许上升 0.25 秒（每分钟最多 15 秒） */
        private const val MAX_UPWARD_SLEW_RATE = 0.25

        /** 短时刷新防抖死区时间门限（毫秒）：180,000 毫秒（3 分钟），死区内强制单向自然流逝，彻底消灭刷新暴涨 */
        private const val SHORT_TERM_DEADBAND_MS = 180_000L

        /** 确认物理充能注入的最小电量增量容限（百分比）：0.5%，彻底消除电芯开路电压回弹与浮点精度噪声 */
        private const val MIN_INFLUX_BATTERY_PERCENT_DIFF = 0.5f

        /** 异常挂钟时间跳变上限（毫秒）：24 小时，用于识别修改系统时间或长期休眠唤醒 */
        private const val MAX_VALID_ELAPSED_MS = 24 * 3600_000L

        @Volatile
        private var instance: EnduranceRuntimeSmoother? = null

        /**
         * 获取时序连续平滑器的全局单例实例。
         *
         * @return 单例实体 [EnduranceRuntimeSmoother]
         */
        fun getInstance(): EnduranceRuntimeSmoother {
            return instance ?: synchronized(this) {
                instance ?: EnduranceRuntimeSmoother().also { instance = it }
            }
        }
    }

    /**
     * 单维度内部平滑时序状态数据包。
     *
     * @property lastSmoothedRemainingMs 上次输出的平滑后剩余续航毫秒数
     * @property lastTimestampMs 上次计算时的系统物理挂钟时间戳（毫秒）
     * @property lastBatteryLevel 上次记录的电池剩余百分比
     */
    private data class DimensionState(
        val lastSmoothedRemainingMs: Long,
        val lastTimestampMs: Long,
        val lastBatteryLevel: Float?
    )

    /** 独立维度的时序平滑状态缓存字典 */
    private val dimensionStates = mutableMapOf<Int, DimensionState>()

    /**
     * 重置所有维度的平滑状态与时序记忆。
     * 通常在系统插入充电器、拔电进入新会话、或手动清理缓存时调用。
     */
    @Synchronized
    fun reset() {
        dimensionStates.clear()
    }

    /**
     * 单独重置指定维度的平滑状态。
     *
     * @param dimension 维度标识（[DIMENSION_SCREEN_ON], [DIMENSION_SCREEN_OFF], [DIMENSION_GLOBAL]）
     */
    @Synchronized
    fun resetDimension(dimension: Int) {
        dimensionStates.remove(dimension)
    }

    /**
     * 对瞬时物理模型推算的剩余续航毫秒数执行时序连续与非对称阻尼平滑。
     * 支持动态能耗流速缩放（Dynamic Drain Rate Scaling）：
     * 依据即时做功功率与稳态习惯基准功率的比值 [drainRateMultiplier]，按比例缩放时间流逝衰减速度。
     * 重载工况（如 3D 游戏）以加速流速（如 3x）消耗剩余时间，轻载工况（如看电子书）以减速流速（如 0.6x）节省时间消耗，
     * 既忠实反映电池真实能量消耗，又杜绝 5 分钟短时游戏将整天剩余续航断崖式拉崩的短视外推缺陷。
     *
     * @param dimension 放电维度标识（如 [DIMENSION_SCREEN_ON] 等）
     * @param rawTargetRemainingMs 瞬时物理推算的原始目标剩余续航（单位：毫秒，若无数据传入 null）
     * @param currentTimestampMs 当前物理挂钟时间戳（单位：毫秒）
     * @param isRegimeChanging 当前是否确立处于持续工况突变跃迁状态（来自 [PowerStateDetector]）
     * @param recoveryDecayRatio 当前是否处于退出突变后的连续衰减过渡期（来自 [PowerStateDetector]）
     * @param batteryLevelPercent 当前电池剩余电量百分比（0.0 ~ 100.0，可选，用于感知外源物理充能注入）
     * @param drainRateMultiplier 动态能耗流速倍率（即时做功功率 / 基准稳态功率，默认 1.0）
     * @return 经过动态流速递减与工况阻尼平滑后的最终剩余续航毫秒数，数据不可用时如实返回 null
     */
    @Synchronized
    fun smoothRemainingRuntime(
        dimension: Int,
        rawTargetRemainingMs: Long?,
        currentTimestampMs: Long,
        isRegimeChanging: Boolean = false,
        recoveryDecayRatio: Double = 0.0,
        batteryLevelPercent: Float? = null,
        drainRateMultiplier: Double = 1.0
    ): Long? {
        // 原始推算值无效时，如实返回 null
        if (rawTargetRemainingMs == null || rawTargetRemainingMs <= 0L) {
            return null
        }

        val prevState = dimensionStates[dimension]

        // 首次进入或历史状态不存在时，以物理实测值为初态确立基线
        if (prevState == null) {
            dimensionStates[dimension] = DimensionState(
                lastSmoothedRemainingMs = rawTargetRemainingMs,
                lastTimestampMs = currentTimestampMs,
                lastBatteryLevel = batteryLevelPercent
            )
            return rawTargetRemainingMs
        }

        // 核心物理第一性原理：检测能量外源注入（电池被充入电量，电量百分比显著增加）
        // 增加 0.5% 物理迟滞门限，彻底消除电芯开路电压自然回弹与浮点精度噪声
        val isEnergyInjected = batteryLevelPercent != null &&
                prevState.lastBatteryLevel != null &&
                (batteryLevelPercent - prevState.lastBatteryLevel) >= MIN_INFLUX_BATTERY_PERCENT_DIFF

        if (isEnergyInjected) {
            dimensionStates[dimension] = DimensionState(
                lastSmoothedRemainingMs = rawTargetRemainingMs,
                lastTimestampMs = currentTimestampMs,
                lastBatteryLevel = batteryLevelPercent
            )
            return rawTargetRemainingMs
        }

        val elapsedMs = currentTimestampMs - prevState.lastTimestampMs

        // 挂钟时间异常判定（时间倒流或跨越超过 24 小时）：重新确立基线
        if (elapsedMs < 0L || elapsedMs > MAX_VALID_ELAPSED_MS) {
            dimensionStates[dimension] = DimensionState(
                lastSmoothedRemainingMs = rawTargetRemainingMs,
                lastTimestampMs = currentTimestampMs,
                lastBatteryLevel = batteryLevelPercent
            )
            return rawTargetRemainingMs
        }

        // 1. 动态能耗流速时间衰减：
        // 挂钟时间流逝 Δt 结合当前工况相对基准习惯的能耗倍率进行流速缩放：
        // 重载时 Δt_eff = Δt * 3.0（加速消耗），轻载时 Δt_eff = Δt * 0.6（省电耐用）
        val effectiveRate = if (drainRateMultiplier.isFinite() && drainRateMultiplier > 0.0) {
            drainRateMultiplier.coerceIn(0.1, 10.0)
        } else {
            1.0
        }
        val effectiveElapsedMs = (elapsedMs * effectiveRate).toLong()
        val decayedRemainingMs = max(0L, prevState.lastSmoothedRemainingMs - effectiveElapsedMs)
        val elapsedSec = elapsedMs / 1000.0

        // 2. 根据工况突变状态与目标差值执行非对称自适应阻尼滤波
        val smoothedMs = when {
            // Case A: 确认发生重载工况突变且物理目标续航显著缩短（如启动游戏）
            isRegimeChanging && rawTargetRemainingMs < decayedRemainingMs -> {
                val alpha = 1.0 - exp(-elapsedSec / TAU_HEAVY_SEC)
                val targetDiff = (rawTargetRemainingMs - decayedRemainingMs).toDouble()
                val blended = decayedRemainingMs + (alpha * targetDiff)
                blended.toLong().coerceIn(rawTargetRemainingMs, decayedRemainingMs)
            }

            // Case B: 处于退出突变后的连续衰减过渡期且目标变小
            recoveryDecayRatio > 0.0 && rawTargetRemainingMs < decayedRemainingMs -> {
                val effectiveTau = TAU_HEAVY_SEC + (1.0 - recoveryDecayRatio) * (TAU_STEADY_SEC - TAU_HEAVY_SEC)
                val alpha = 1.0 - exp(-elapsedSec / effectiveTau)
                val targetDiff = (rawTargetRemainingMs - decayedRemainingMs).toDouble()
                val blended = decayedRemainingMs + (alpha * targetDiff)
                blended.toLong().coerceIn(rawTargetRemainingMs, decayedRemainingMs)
            }

            // Case C: 稳态放电或工况持续加重（rawTargetRemainingMs <= decayedRemainingMs）
            rawTargetRemainingMs <= decayedRemainingMs -> {
                if (elapsedMs < 120_000L) {
                    // 短时刷新（< 2 分钟）且无工况突变：严格跟随自然挂钟时间单调线性流逝，彻底过滤微小白噪声
                    decayedRemainingMs
                } else {
                    // 长周期自然稳态收敛
                    val alpha = 1.0 - exp(-elapsedSec / TAU_STEADY_SEC)
                    val targetDiff = (rawTargetRemainingMs - decayedRemainingMs).toDouble()
                    val blended = decayedRemainingMs + (alpha * targetDiff)
                    blended.toLong().coerceIn(rawTargetRemainingMs, decayedRemainingMs)
                }
            }

            // Case D: 物理目标续航变长（rawTargetRemainingMs > decayedRemainingMs，如退出游戏看小说）
            // 采用经典控制理论「短时死区严格单调 + 长时慢速渐近 + 最大正向变率限幅」：
            // - 在短时死区内（< 3分钟）：强制输出自然衰减值 decayedRemainingMs，彻底消灭短时刷新暴涨 45 分钟的跳变；
            // - 在长时间跨度下（>= 3分钟）：允许以慢速常数 TAU_RISE_SEC 渐近收敛，并严格受限于 MAX_UPWARD_SLEW_RATE，
            //   既彻底杜绝同一会话内骤然暴涨，又彻底终结退出游戏后时间死扣到 0 的单向死锁缺陷！
            else -> {
                if (elapsedMs < SHORT_TERM_DEADBAND_MS) {
                    decayedRemainingMs
                } else {
                    val alpha = 1.0 - exp(-elapsedSec / TAU_RISE_SEC)
                    val targetDiff = (rawTargetRemainingMs - decayedRemainingMs).toDouble()
                    val candidate = decayedRemainingMs + (alpha * targetDiff)
                    val maxAllowed = prevState.lastSmoothedRemainingMs + (elapsedSec * MAX_UPWARD_SLEW_RATE * 1000.0)
                    min(candidate, maxAllowed).toLong().coerceAtLeast(decayedRemainingMs)
                }
            }
        }

        // 保存当前维度的连续时序状态
        dimensionStates[dimension] = DimensionState(
            lastSmoothedRemainingMs = smoothedMs,
            lastTimestampMs = currentTimestampMs,
            lastBatteryLevel = batteryLevelPercent
        )

        return smoothedMs
    }
}
