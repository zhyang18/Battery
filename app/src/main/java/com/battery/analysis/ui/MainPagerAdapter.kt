package com.battery.analysis.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * 顶级主界面 ViewPager2 页面适配器。
 * 负责管理“耗电/充电”、“健康度”、“设置”顶级导航页签对应 Fragment 的动态创建与生命周期。
 * 支持根据“启用充、放电统计”配置动态增删“充、耗电”页签，并重写稳定 ID 保障页面安全切换。
 *
 * @param fragmentActivity 宿主 FragmentActivity 实例
 * @param isChargeDischargeEnabled 初始是否启用充、放电统计功能
 */
class MainPagerAdapter(
    fragmentActivity: FragmentActivity,
    isChargeDischargeEnabled: Boolean = false
) : FragmentStateAdapter(fragmentActivity) {

    companion object {
        /** 充、耗电统计页面稳定 ID */
        const val ID_POWER = 0L
        /** 电池健康度检测页面稳定 ID */
        const val ID_DETECTION = 1L
        /** 应用设置页面稳定 ID */
        const val ID_SETTINGS = 2L
    }

    /** 当前 ViewPager2 实际承载的页面稳定 ID 列表 */
    private val currentPages = mutableListOf<Long>()

    init {
        updatePages(isChargeDischargeEnabled)
    }

    /**
     * 根据是否启用充、放电统计功能动态更新 ViewPager2 页面数据源列表。
     *
     * @param enabled 是否开启充、放电统计功能（true 为开启，false 为关闭）
     */
    fun updatePages(enabled: Boolean) {
        currentPages.clear()
        if (enabled) {
            currentPages.add(ID_POWER)
        }
        currentPages.add(ID_DETECTION)
        currentPages.add(ID_SETTINGS)
    }

    /**
     * 获取顶级 ViewPager2 中当前实际页面总数。
     *
     * @return 页面总数（关闭充放电统计时为 2，开启时为 3）
     */
    override fun getItemCount(): Int = currentPages.size

    /**
     * 获取指定位置页面的全局稳定唯一 ID。
     *
     * @param position 页面在 ViewPager2 中的索引下标
     * @return 页面稳定 ID（[ID_POWER]、[ID_DETECTION] 或 [ID_SETTINGS]）
     */
    override fun getItemId(position: Int): Long = currentPages[position]

    /**
     * 判断指定页面稳定 ID 是否依然存在于当前数据源列表中。
     * 供 ViewPager2 在数据集变化时精细判断 Fragment 缓存与复用。
     *
     * @param itemId 待检查的页面稳定 ID
     * @return 若当前列表中包含该 ID 返回 true，否则返回 false
     */
    override fun containsItem(itemId: Long): Boolean = currentPages.contains(itemId)

    /**
     * 根据索引创建对应的顶级 Tab 页面 Fragment 实例。
     *
     * @param position 页面索引下标
     * @return 对应的 [Fragment] 实例
     */
    override fun createFragment(position: Int): Fragment {
        return when (currentPages[position]) {
            ID_POWER -> PowerUsageFragment.newInstance()
            ID_DETECTION -> DetectionFragment.newInstance()
            ID_SETTINGS -> SettingsFragment.newInstance()
            else -> DetectionFragment.newInstance()
        }
    }

    /**
     * 根据页面稳定 ID 查询当前在 ViewPager2 中的索引位置。
     *
     * @param itemId 页面稳定 ID
     * @return 对应的索引下标，若未包含则返回 -1
     */
    fun getPositionForItemId(itemId: Long): Int {
        return currentPages.indexOf(itemId)
    }

    /**
     * 根据 ViewPager2 的位置索引查询对应的页面稳定 ID。
     *
     * @param position 页面位置索引
     * @return 对应的页面稳定 ID，越界时默认返回 [ID_DETECTION]
     */
    fun getItemIdForPosition(position: Int): Long {
        return if (position in currentPages.indices) currentPages[position] else ID_DETECTION
    }
}
