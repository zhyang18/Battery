package com.battery.analysis.timeline.domain

/**
 * 功耗数据估算可信度级别枚举。
 */
enum class ConfidenceLevel {
    /**
     * 高可信度：来自系统底层权威硬件库或官方 API 直接统计（如 BatteryStats 物理账本）。
     */
    HIGH,

    /**
     * 中可信度：基于多源时序交叉对齐与加权归因估算（如 CPU/网络使用时间换算）。
     */
    MEDIUM,

    /**
     * 低可信度：在缺乏精确硬件数据时通过粗粒度时间比率推导。
     */
    LOW
}

/**
 * 功耗数据来源渠道枚举。
 */
enum class EnergySource {
    /**
     * 系统权威 BatteryStats 导出。
     */
    BATTERY_STATS,

    /**
     * Linux 内核 sysfs 电源子系统节点。
     */
    SYSFS,

    /**
     * Android 官方 BatteryManager 广播与属性。
     */
    BATTERY_MANAGER,

    /**
     * 基于 UsageStats 及算法多源融合估算。
     */
    ESTIMATED
}
