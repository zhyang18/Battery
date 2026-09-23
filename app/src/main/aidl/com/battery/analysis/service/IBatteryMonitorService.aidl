package com.battery.analysis.service;

/**
 * 电池监控后台常驻服务跨进程通信接口 (AIDL)。
 *
 * 支撑主 UI 进程与独立后台监控进程 (:monitor) 之间的解耦通信，
 * 避免 UI 进程常驻造成的额外内存开销，同时保证前台界面能够秒级获取最新充放电物理采样数据。
 */
interface IBatteryMonitorService {

    /**
     * 检测后台监控服务是否处于活跃运行与物理采样状态。
     *
     * @return 若处于活跃运行中返回 true，否则返回 false
     */
    boolean isServiceRunning();

    /**
     * 宿主应用（主 UI 进程）通知后台监控服务当前界面是否处于前台可见活跃状态。
     * 后台服务据此调整通知栏更新频率与调度策略，降低前后台重复消耗。
     *
     * @param foreground 是否处于前台可见活跃状态
     */
    void notifyHostAppForeground(boolean foreground);

    /**
     * 获取最新瞬时电池物理运行状态 JSON 字符串。
     * 字段包含：levelPercent, voltageVolts, temperature, powerWatts, isCharging, isScreenOn, chargeType 等。
     *
     * @return 电池实时快照的 JSON 字符串
     */
    String getLiveBatteryStatusJson();

    /**
     * 获取当前放电周期内存中最新的秒级瞬时采样点列表 JSON 字符串。
     *
     * @return 放电瞬时采样点列表序列化的 JSON 数组字符串
     */
    String getDischargeRealtimeSamplesJson();

    /**
     * 获取当前放电周期的常驻物理微积分能量累加器状态 JSON 字符串。
     *
     * @return 放电累加器状态序列化的 JSON 字符串
     */
    String getDischargeAccumulatorJson();

    /**
     * 获取当前充电周期的会话摘要 JSON 字符串。
     *
     * @return 充电会话摘要序列化的 JSON 字符串
     */
    String getChargingSessionSummaryJson();

    /**
     * 获取当前充电周期的采样点列表 JSON 字符串。
     *
     * @return 充电物理轨迹采样点列表序列化的 JSON 数组字符串
     */
    String getChargingSamplePointsJson();

    /**
     * 通知后台监控服务强制将当前内存中的瞬时放电与充电采样点及物理能量累加器立即落盘写入私有文件。
     */
    void forceFlushToDisk();

    /**
     * 动态同步更新后台监控服务的配置参数。
     *
     * @param statsEnabled 是否启用充放电统计
     * @param notificationEnabled 是否在通知栏常驻显示
     * @param screenOnIntervalMs 亮屏监控刷新间隔毫秒数（-1L 为不采样）
     * @param screenOffIntervalMs 息屏待机采样间隔毫秒数（0L 为智能省电，-1L 为不采样）
     */
    void updateConfig(boolean statsEnabled, boolean notificationEnabled, long screenOnIntervalMs, long screenOffIntervalMs);
}
