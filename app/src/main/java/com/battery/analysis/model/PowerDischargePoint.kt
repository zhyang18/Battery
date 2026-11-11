package com.battery.analysis.model

import android.graphics.drawable.Drawable

/**
 * 放电电流数据采集来源枚举。
 */
enum class CurrentSource {
    /** 来源于 Android 官方 BatteryManager (Health HAL) */
    BATTERY_MANAGER,
    /** 来源于 Linux 内核 sysfs 节点直接读取 */
    SYSFS_BATTERY,
    /** 来源于并联或串联双电芯多节点合并 */
    SYSFS_PARALLEL,
    /** 历史旧版本基于展示功率与电压反推估算 */
    LEGACY_ESTIMATED,
    /** 未知或未成功探测 */
    UNKNOWN
}

/**
 * 瞬时电流物理极性枚举。
 */
enum class CurrentPolarity {
    /** 遵循 Android 原生规范：放电电流为负数 (< 0) */
    DISCHARGE_NEGATIVE,
    /** 部分 sysfs 驱动原生直接以正数上报放电电流 */
    DISCHARGE_POSITIVE,
    /** 极性未知或无法判定 */
    UNKNOWN
}

/**
 * 放电数据点物理来源与纯度标记枚举。
 * 用于严格隔离历史估算/插值数据与高可信物理测量数据。
 */
enum class DischargeDataProvenance {
    /** 来自底层传感器或 BatteryManager 的纯正真实物理测量电流 */
    MEASURED_BATTERY_CURRENT,
    /** 历史旧版本由截断功率反推得到的估算值，严禁参与高精度 FCC 库仑积分 */
    LEGACY_ESTIMATED_POWER,
    /** 息屏休眠断层注入的待机底噪补偿功耗，严禁参与物理库仑积分 */
    STANDBY_COMPENSATED_POWER,
    /** 未知来源 */
    UNKNOWN
}

/**
 * 放电过程电量走势与采样数据点实体类。
 * 记录特定时间采样点的电量百分比、活跃应用图标列表、电压与温度信息，用于在折线图上绘制走势曲线与应用打点，
 * 同时高精度存储底层原生电池侧电流、采集来源与物理测量标记，为 FCC 库仑积分提供不可篡改的真实物理依据。
 *
 * @property timestamp 采样的绝对时间戳（毫秒）
 * @property elapsedHours 自统计起点以来经过的小时数（浮点数，如 0.5f 表示 30 分钟）
 * @property batteryLevel 电池电量百分比（0 ~ 100）
 * @property voltageVolts 电池电压（单位：伏特 V）
 * @property temperature 电池温度（单位：摄氏度 ℃）
 * @property powerWatts 瞬时放电功耗（单位：瓦特 W）
 * @property activeAppIcons 在当前时间段内活跃的应用图标列表，用于折线图打点堆叠展示
 * @property isScreenOn 当前采样时间段是否处于亮屏状态（true 为亮屏绿色，false 为息屏红色）
 * @property activeAppNames 在当前时间段内活跃的应用名称列表，用于悬浮气泡探查展示
 * @property packageName 在采样瞬间处于置顶前台的应用包名（可为 null）
 * @property batteryCurrentUa 原始电池侧微安电流（保留原始符号与量纲，放电时通常为负数，单位：μA）
 * @property currentAvgUa 硬件平均电流（单位：μA，若硬件不支持或为 MIN_VALUE 则为 null）
 * @property currentSource 瞬时电流物理采集来源 [CurrentSource]
 * @property currentPolarity 瞬时电流物理极性定义 [CurrentPolarity]
 * @property dischargeCurrentMa 归一化后的正值放电电流（单位：mA，仅当确认为真实放电测量时有效）
 * @property currentMeasured 是否为底层硬件传感器的真实物理测量值
 * @property provenance 放电数据点物理纯度与来源标记 [DischargeDataProvenance]
 */
data class PowerDischargePoint(
    val timestamp: Long,
    val elapsedHours: Float,
    val batteryLevel: Int,
    val voltageVolts: Float = 0f,
    val temperature: Float = 0f,
    val powerWatts: Float = 0f,
    val activeAppIcons: List<Drawable> = emptyList(),
    val isScreenOn: Boolean = true,
    val activeAppNames: List<String> = emptyList(),
    val packageName: String? = null,
    val batteryCurrentUa: Int? = null,
    val currentAvgUa: Int? = null,
    val currentSource: CurrentSource = CurrentSource.UNKNOWN,
    val currentPolarity: CurrentPolarity = CurrentPolarity.UNKNOWN,
    val dischargeCurrentMa: Double? = null,
    val currentMeasured: Boolean = false,
    val provenance: DischargeDataProvenance = DischargeDataProvenance.UNKNOWN,
    val chargeCounterUah: Long? = null
) {
    /**
     * 获取用于 X 轴或浮窗气泡展示的格式化时间描述。
     *
     * @return 格式化后的时间字符串（如 "12h" 或 "01d06h"）
     */
    fun getFormattedTimeLabel(): String {
        val totalHours = elapsedHours.toInt()
        val days = totalHours / 24
        val remHours = totalHours % 24
        return when {
            days > 0 && remHours > 0 -> String.format(java.util.Locale.getDefault(), "%02dd%02dh", days, remHours)
            days > 0 -> String.format(java.util.Locale.getDefault(), "%02dd", days)
            else -> String.format(java.util.Locale.getDefault(), "%02dh", remHours)
        }
    }
}
