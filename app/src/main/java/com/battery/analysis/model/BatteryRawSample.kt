package com.battery.analysis.model

/**
 * 电池物理底层原始采样不可变数据实体类。
 * 严格记录单次微元采样的真实物理数值，包含单调时钟、开机周期标识与电池端净电流，
 * 杜绝任何应用功耗模型或续航预测反算的虚拟数据，为高精度安时积分与自学习提供纯粹的物理输入。
 *
 * @property bootSessionId 设备单次启动周期唯一标识符，防止跨系统重启误算时差
 * @property elapsedRealtimeNanos 自开机以来的单调纳秒时间戳，用于高精度微积分差分
 * @property wallTimeMs 挂钟绝对时间戳（毫秒），用于跨天展示与持久化对齐
 * @property netCurrentMa 电池电芯两端测得的真实物理净电流（单位：毫安 mA，流入电芯充电为正，流出电芯放电为负）
 * @property voltageMv 电池电芯两端端电压（单位：毫伏 mV）
 * @property socPercent 系统上报的当前电池电量整数百分比（取值 0 至 100）
 * @property temperatureCelsius 电池 NTC 热敏电阻测得的实时温度（单位：摄氏度 ℃）
 * @property isScreenOn 采样瞬间屏幕是否处于点亮交互状态
 */
data class BatteryRawSample(
    val bootSessionId: String,
    val elapsedRealtimeNanos: Long,
    val wallTimeMs: Long,
    val netCurrentMa: Float,
    val voltageMv: Float,
    val socPercent: Int,
    val temperatureCelsius: Float,
    val isScreenOn: Boolean
) {
    /**
     * 将纳秒单调时钟转换为毫秒浮点数。
     *
     * @return 毫秒级单调时钟数值
     */
    fun getElapsedRealtimeMs(): Double {
        return elapsedRealtimeNanos / 1_000_000.0
    }

    /**
     * 获取归一化后的电量无量纲比例 z（取值范围 0.0 至 1.0）。
     *
     * @return 归一化电量比例
     */
    fun getNormalizedSoc(): Double {
        return socPercent.coerceIn(0, 100) / 100.0
    }
}
