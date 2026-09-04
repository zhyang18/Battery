package com.battery.analysis.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * 顶级主界面 ViewPager2 页面适配器。
 * 负责管理“耗电”、“检测”、“记录”、“设置”四大顶级导航页签对应 Fragment 的创建与生命周期。
 *
 * @param fragmentActivity 宿主 FragmentActivity 实例
 */
class MainPagerAdapter(fragmentActivity: FragmentActivity) : FragmentStateAdapter(fragmentActivity) {

    /**
     * 获取顶级 ViewPager2 中总页数。
     *
     * @return 固定为 4（耗电、检测、记录、设置）
     */
    override fun getItemCount(): Int = 4

    /**
     * 根据索引创建对应的顶级 Tab 页面 Fragment 实例。
     *
     * @param position 页面索引下标（0 为耗电，1 为检测，2 为记录，3 为设置）
     * @return 对应的 [Fragment] 实例
     */
    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> PowerUsageFragment.newInstance()
            1 -> DetectionFragment.newInstance()
            2 -> HistoryFragment.newInstance()
            3 -> SettingsFragment.newInstance()
            else -> PowerUsageFragment.newInstance()
        }
    }
}
