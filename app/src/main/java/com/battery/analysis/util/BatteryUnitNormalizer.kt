package com.battery.analysis.util

import android.os.Build
import java.util.Locale
import kotlin.math.abs

/**
 * 电池物理指标（电压、电流、瞬时功率）单位规范化与自适应校准工具。
 *
 * 针对 Android 各芯片方案（高通、联发科、紫光展锐等）与 OEM 厂商（华为/荣耀、小米/红米、OPPO/vivo 等）
 * 底层库仑计驱动单位上报极不统一（微安 uA vs 毫安 mA vs 0.1uA）的碎片化痛点，
 * 提供兼顾物理忠实性与机型自适应学习的单位换算与校准：
 * 1. 电压（EXTRA_VOLTAGE）：兼容 mV、0.1mV、uV、V 多种上报单位，统一转换为标准 mV 和 V；
 * 2. 电流（CURRENT_NOW）：基于硬件厂商特征与运行时数值量纲自适应学习识别（uA、mA、0.1uA），
 *    彻底杜绝荣耀/华为等以 mA 为单位的设备亮屏功耗缩水为 0.00W，
 *    同时杜绝高通等以 uA 为单位的设备待机微安（如 5000uA）被误算为 5000mA (20W)；
 * 3. 瞬时功耗：忠实遵循物理学公式 P = (U * I) / 1000，不人为虚构物理上下限。
 */
object BatteryUnitNormalizer {

    /**
     * 电池瞬时电流单位模式枚举。
     */
    enum class CurrentUnitMode {
        /** 尚未最终确立，处于自适应探测中 */
        AUTO,
        /** 毫安模式（如荣耀、华为、部分联发科机型，直接上报 100~3000 表示 100~3000mA） */
        MILLIAMPERES,
        /** 微安模式（如高通、Google Pixel、三星等原生 Android，上报 100000~3000000 表示 uA） */
        MICROAMPERES,
        /** 0.1微安极大量程模式（上报 10000000+） */
        TENTH_MICROAMPERES
    }

    /** 当前设备学习确立的电流单位模式，volatile 保证多线程读写可见性 */
    @Volatile
    private var detectedUnitMode: CurrentUnitMode = CurrentUnitMode.AUTO

    /**
     * 判断当前设备是否为已知原生以毫安（mA）为单位上报的厂商（华为、荣耀等）。
     *
     * @return 若为华为或荣耀设备返回 true，否则返回 false
     */
    fun isHonorOrHuaweiDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER?.uppercase(Locale.ROOT).orEmpty()
        val brand = Build.BRAND?.uppercase(Locale.ROOT).orEmpty()
        return manufacturer.contains("HUAWEI") || manufacturer.contains("HONOR") ||
                brand.contains("HUAWEI") || brand.contains("HONOR")
    }

    /**
     * 重置电流单位自适应学习状态（供单元测试或环境重置调用）。
     */
    fun resetUnitDetectionForTest() {
        detectedUnitMode = CurrentUnitMode.AUTO
    }

    /**
     * 获取当前设备识别出的电流单位模式。
     *
     * @return 当前生效的 [CurrentUnitMode]
     */
    fun getDetectedUnitMode(): CurrentUnitMode {
        return detectedUnitMode
    }

    /**
     * 将底层系统广播或传感器上报的原始电压数值规范化为标准毫伏（mV）。
     *
     * @param rawVolt 原始电压值（可能为 mV、0.1mV、uV 或 V）
     * @return 规范化后的毫伏电压（mV），若输入异常则返回 0f
     */
    fun normalizeVoltageMv(rawVolt: Long): Float {
        if (rawVolt <= 0) return 0f
        val mv = when {
            rawVolt in 2500..9999 -> rawVolt.toFloat() // 规范毫伏 mV（单电芯 3.0~4.5V，双电芯串联 6.0~9.0V）
            rawVolt in 10000..99999 -> rawVolt / 10f // 0.1 毫伏（部分高通/联发科机型，如 41500 -> 4150.0 mV）
            rawVolt >= 100000 -> rawVolt / 1000f // 微伏 uV（如 4150000 -> 4150.0 mV）
            rawVolt in 1..24 -> rawVolt * 1000f // 伏特 V（如 4 -> 4000.0 mV）
            else -> rawVolt.toFloat()
        }
        return mv.coerceAtLeast(0f)
    }

    /**
     * 将底层系统广播或传感器上报的原始电压数值规范化为标准伏特（V）。
     *
     * @param rawVolt 原始电压值
     * @return 规范化后的伏特电压（V）
     */
    fun normalizeVoltageVolts(rawVolt: Long): Float {
        return normalizeVoltageMv(rawVolt) / 1000f
    }

    /**
     * 将底层库仑计上报的原始瞬时电流规范化为标准毫安（mA）。
     *
     * 核心物理裁决逻辑：
     * 1. 极大量程（>= 10,000,000）：判定为 0.1uA 步进，除以 10,000 得到 mA；
     * 2. 万级及以上大数（10,000 .. 9,999,999）：
     *    由于手机放电电流不可能达到 10,000mA（10A）以上，因此出现此类大数 100% 证实底层为微安（uA）驱动，
     *    确立当前设备为 [CurrentUnitMode.MICROAMPERES]，并除以 1000 得到 mA；
     * 3. 小数区间（1 .. 9,999）：
     *    - 若已学习确立为 [CurrentUnitMode.MICROAMPERES]（例如高通机型在亮屏时已采到数十万微安）：
     *      此小数为休眠待机微安（如 5,000 uA = 5mA），严格除以 1000 恢复真实 5mA 待机电流，绝不膨胀为 5000mA；
     *    - 若已知属于荣耀/华为等以 mA 驱动的设备，或已确立为 [CurrentUnitMode.MILLIAMPERES]：
     *      此小数直接代表真实毫安（如 267 代表 267mA 放电，约 1.09W），忠实保留为 mA，绝不误除以 1000 缩水为 0.00W；
     *    - 若尚未确立且品牌为华为/荣耀：直接确立为 [CurrentUnitMode.MILLIAMPERES] 并返回 absCur.toFloat()；
     *    - 若尚未确立且处于亮屏/日常功耗区间（如 100..4000）：
     *      若除以 1000 功率小于 0.02W（无法满足智能手机亮屏正常工作的物理底噪），
     *      则自适应确立为 [CurrentUnitMode.MILLIAMPERES]，返回 absCur.toFloat()；
     *    - 其他未确立的极小待机情况：若 absCur in 1..99，直接作为 mA（1~99mA 对应 0.004W~0.4W 待机功耗）；
     *      若 absCur >= 1000 且未确立，由于高通待机常为 1000~9999uA，若无明确证据则除以 1000 避免 20W 待机暴增。
     *
     * @param rawCur 硬件库仑计读取的原始电流数值
     * @param isCharging 当前是否处于充电状态
     * @return 规范化后的正数电流值（单位：mA），无法获取时返回 0f
     */
    fun normalizeCurrentMa(rawCur: Long, isCharging: Boolean = false): Float {
        if (rawCur == 0L || rawCur == Int.MIN_VALUE.toLong() || rawCur == Long.MIN_VALUE) {
            return 0f
        }
        val absCur = abs(rawCur)

        val ma = when {
            absCur >= 10_000_000L -> {
                // 极大量程（0.1uA 步进，例如 35,000,000 -> 3500mA）
                detectedUnitMode = CurrentUnitMode.TENTH_MICROAMPERES
                absCur / 10000f
            }
            absCur >= 10_000L -> {
                // 明确的微安级大数（例如 250,000 uA -> 250mA，1,200,000 uA -> 1200mA）
                // 物理学事实：手机不可能出现持续 10A 以上的放电，故确立底层驱动为微安 (uA)
                detectedUnitMode = CurrentUnitMode.MICROAMPERES
                absCur / 1000f
            }
            else -> {
                // absCur in 1..9_999L
                when (detectedUnitMode) {
                    CurrentUnitMode.MICROAMPERES -> {
                        // 已经确认是微安设备（如高通/Pixel），此区间为待机休眠低微安（如 5000 uA = 5mA）
                        absCur / 1000f
                    }
                    CurrentUnitMode.MILLIAMPERES -> {
                        // 已经确认是毫安设备（如荣耀/华为），此区间直接为毫安（如 267 = 267mA）
                        absCur.toFloat()
                    }
                    else -> {
                        // AUTO 探测阶段
                        if (isHonorOrHuaweiDevice()) {
                            detectedUnitMode = CurrentUnitMode.MILLIAMPERES
                            absCur.toFloat()
                        } else if (absCur in 100..4000) {
                            // 典型智能手机亮屏正常放电毫安区间（100mA ~ 4000mA 对应约 0.4W ~ 16W）
                            // 驱动直接报告 mA 的机型，确立为 MILLIAMPERES
                            detectedUnitMode = CurrentUnitMode.MILLIAMPERES
                            absCur.toFloat()
                        } else if (absCur < 100) {
                            // 极小电流（1~99）：
                            // 若是微安，1~99uA 仅对应 0.001mA~0.099mA（几微瓦，甚至低于任何芯片休眠底噪），
                            // 故属于 mA 待机（1mA~99mA 对应 0.004W~0.4W 待机功耗）
                            absCur.toFloat()
                        } else {
                            // absCur in 4001..9999 且未知设备：
                            // 常见于高通深度休眠唤醒时的微安读数（4000uA~9999uA = 4mA~10mA），
                            // 除以 1000 转换为毫安，杜绝暴增为 4000mA（16W+）
                            absCur / 1000f
                        }
                    }
                }
            }
        }

        return ma.coerceAtLeast(0f)
    }

    /**
     * 根据电池电压（V）与电流（mA）精确计算瞬时功率（瓦特 W）。
     * 忠实遵循物理公式 P = (U * I) / 1000，不人为限制或缩小高功率工况。
     *
     * @param voltageVolts 电池电压（单位：V）
     * @param currentMa 电池电流（单位：mA，绝对值或正负均可）
     * @param isCharging 当前是否处于充电状态
     * @return 计算得到的瞬时功率绝对值（单位：W）
     */
    @Suppress("UNUSED_PARAMETER")
    fun calculatePowerWatts(voltageVolts: Float, currentMa: Float, isCharging: Boolean = false): Float {
        val safeVolt = if (voltageVolts > 0f) voltageVolts else 4.0f
        val safeCur = abs(currentMa)
        if (safeVolt <= 0f || safeCur <= 0f) return 0f

        val pWatts = (safeVolt * safeCur) / 1000f
        return (Math.round(pWatts * 100f) / 100f).coerceAtLeast(0f)
    }
}
