package com.battery.analysis.manager

import android.content.Context
import android.content.SharedPreferences

/**
 * 电池硬件电流校准管理器。
 *
 * 针对国产厂商及各芯片方案设备在电流采集时可能出现的：
 * 1. 电流测出为 0（HAL 接口未上报或普通权限受限，支持库仑计推算）；
 * 2. 驱动单位量级偏差（支持 10 的倍数倍率：-1000、-100、-10、-1、1、10、100、1000）；
 * 3. 双电芯串/并联快充仅上报单电芯电流（支持独立开启双电芯读数翻倍 x2 开关）；
 * 4. 充放电方向相反（支持独立极性反转及负倍率协同判定）；
 * 提供倍率校准、双电芯翻倍、极性反转及库仑计电荷差分推算补偿的持久化管理。
 */
object CurrentCalibrationManager {

    private const val PREF_NAME = "current_calibration_prefs"
    private const val KEY_CALIBRATION_MULTIPLIER = "key_calibration_multiplier"
    private const val KEY_DUAL_CELL = "key_dual_cell"
    private const val KEY_INVERT_POLARITY = "key_invert_polarity"
    private const val KEY_COULOMB_FALLBACK = "key_coulomb_fallback"

    /** 默认电流校准倍率（1.0x 标准单电芯） */
    const val DEFAULT_MULTIPLIER = 1.0f

    /** 支持的 10 的倍数电流校准倍率档位列表（包含正负数量级） */
    val SUPPORTED_MULTIPLIERS = floatArrayOf(-1000f, -100f, -10f, -1f, 1f, 10f, 100f, 1000f)

    /**
     * 获取电流校准配置专用的 [SharedPreferences] 实例。
     *
     * @param context 应用程序上下文
     * @return 对应的 SharedPreferences 实例
     */
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 获取用户配置的电流校准倍率。
     *
     * @param context 应用程序上下文
     * @return 电流校准倍率浮点数（默认为 1.0f，支持 -1000f 到 1000f）
     */
    fun getMultiplier(context: Context): Float {
        return getPrefs(context).getFloat(KEY_CALIBRATION_MULTIPLIER, DEFAULT_MULTIPLIER)
    }

    /**
     * 设置并持久化用户配置的电流校准倍率。
     *
     * @param context 应用程序上下文
     * @param multiplier 电流校准倍率（必须属于预设的 10 的倍数档位列表）
     */
    fun setMultiplier(context: Context, multiplier: Float) {
        val validMultiplier = if (SUPPORTED_MULTIPLIERS.any { it == multiplier }) {
            multiplier
        } else {
            DEFAULT_MULTIPLIER
        }
        getPrefs(context).edit().putFloat(KEY_CALIBRATION_MULTIPLIER, validMultiplier).apply()
    }

    /**
     * 获取是否开启双电芯电流翻倍校准。
     *
     * @param context 应用程序上下文
     * @return 若开启双电芯模式返回 true，否则返回 false（默认 false）
     */
    fun isDualCellEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_DUAL_CELL, false)
    }

    /**
     * 设置并持久化是否开启双电芯电流翻倍校准。
     *
     * @param context 应用程序上下文
     * @param enabled 是否开启双电芯模式
     */
    fun setDualCellEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_DUAL_CELL, enabled).apply()
    }

    /**
     * 获取是否开启电流极性反转（手动开关状态）。
     *
     * @param context 应用程序上下文
     * @return 若用户手动开启极性反转返回 true，否则返回 false（默认 false）
     */
    fun isInvertPolarity(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_INVERT_POLARITY, false)
    }

    /**
     * 设置并持久化是否开启电流极性反转（手动开关状态）。
     *
     * @param context 应用程序上下文
     * @param invert 是否手动反转极性
     */
    fun setInvertPolarity(context: Context, invert: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_INVERT_POLARITY, invert).apply()
    }

    /**
     * 获取当前综合生效的最终极性反转状态。
     *
     * 结合了负数倍率（如 -1000、-100、-10、-1 本身具有反向语义）与手动极性反转开关的异或结果。
     *
     * @param context 应用程序上下文
     * @return 最终是否应对充放电方向进行极性反转
     */
    fun isEffectiveInvertPolarity(context: Context): Boolean {
        val isNegativeMultiplier = getMultiplier(context) < 0f
        val manualInvert = isInvertPolarity(context)
        return isNegativeMultiplier xor manualInvert
    }

    /**
     * 获取是否开启库仑计电荷差分推算补偿功能。
     *
     * @param context 应用程序上下文
     * @return 若开启库仑计推算补偿返回 true，否则返回 false（默认 true）
     */
    fun isCoulombFallbackEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_COULOMB_FALLBACK, true)
    }

    /**
     * 设置并持久化是否开启库仑计电荷差分推算补偿功能。
     *
     * @param context 应用程序上下文
     * @param enabled 是否开启库仑计差分推算
     */
    fun setCoulombFallbackEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_COULOMB_FALLBACK, enabled).apply()
    }

    /**
     * 对采集到的原始物理电流（毫安 mA）应用用户校准倍率与双电芯翻倍系数。
     *
     * 计算公式：校准后瞬时电流大小 = rawCurrentMa * abs(multiplier) * (若开启双电芯则 2.0 否则 1.0)。
     * 极性反转由 [isEffectiveInvertPolarity] 统一协同处理，避免瞬时放电功率出现负数异常。
     *
     * @param rawCurrentMa 底层硬件读取到的真实瞬时电流大小（毫安 mA）
     * @param context 应用程序上下文
     * @return 经倍率与双电芯校准后的电流大小（毫安 mA）
     */
    fun applyCalibration(rawCurrentMa: Float, context: Context): Float {
        if (rawCurrentMa == 0f) return 0f
        val multiplier = Math.abs(getMultiplier(context))
        val dualFactor = if (isDualCellEnabled(context)) 2.0f else 1.0f
        return rawCurrentMa * multiplier * dualFactor
    }
}
