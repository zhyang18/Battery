package com.battery.analysis.model

/**
 * 耗电统计主列表多视图类型实体定义。
 * 将原嵌套滚动视图中的引导卡片、状态提示横幅、功耗时间轴概览卡片、
 * 使用场景操作栏、充电统计卡片以及各个具体的应用耗电项统一抽象为平铺列表条目，
 * 供主 RecyclerView 统一调度并实现真正高效的滑动复用与虚拟化渲染。
 */
sealed class PowerUsageItem {

    /**
     * 首次打开 App 时的模式选择引导卡片条目。
     */
    data object FirstTimeSetup : PowerUsageItem()

    /**
     * 查看历史快照时的顶部蓝色提示横幅条目。
     *
     * @property hintText 横幅提示文本内容
     */
    data class SnapshotBanner(val hintText: String) : PowerUsageItem()

    /**
     * Shizuku 授权与运行状态提示引导卡片条目。
     *
     * @property title 引导标题
     * @property desc 引导详细说明描述
     * @property actionText 操作按钮文本
     */
    data class ShizukuGuide(
        val title: String,
        val desc: String,
        val actionText: String
    ) : PowerUsageItem()

    /**
     * 放电速度核心概览卡片条目（包含全局放电速度、亮屏放电速度与息屏放电速度三个主要模块）。
     */
    data object DischargeSpeedMetrics : PowerUsageItem()

    /**
     * 息屏唤醒与深度睡眠耗电指标双卡片条目。
     */
    data object SleepAwakeMetrics : PowerUsageItem()


    /**
     * 耗电模式下的核心概览卡片条目（包含能量、温度、电压、充电状态指示，以及时间轴图表和指标切换器）。
     */
    data object UsageOverview : PowerUsageItem()

    /**
     * 应用使用场景列表头部条目（包含场景标题、后台统计开关、排序按钮及普通模式权限横幅）。
     *
     * @property count 当前展示的应用总数
     * @property showBackgroundStats 是否开启后台应用统计
     */
    data class SceneHeader(
        val count: Int,
        val showBackgroundStats: Boolean
    ) : PowerUsageItem()

    /**
     * 单个应用耗电数据条目，是主列表中唯一大量重复且享受 RecyclerView ViewHolder 完整虚拟化复用的核心项。
     *
     * @property data 具体的应用能耗与时长统计数据实体
     */
    data class AppUsage(
        val data: AppPowerUsageItem
    ) : PowerUsageItem()

    /**
     * 充电统计卡片条目（连接充电器或切入充电模式时呈现，包含三合一走势折线图与圆形进度等）。
     */
    data object ChargingContent : PowerUsageItem()
}
