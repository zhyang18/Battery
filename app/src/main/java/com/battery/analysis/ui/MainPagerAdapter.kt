package com.battery.analysis.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * 顶级主界面 ViewPager2 页面适配器。
 * 负责管理“检测”、“记录”、“设置”三大顶级导航页签对应 Fragment 的创建与生命周期。
 */
class MainPagerAdapter(fragmentActivity: FragmentActivity) : FragmentStateAdapter(fragmentActivity) {

    /**
     * 获取顶级 ViewPager2 中总页数。
     *
     * @return 固定为 3（检测、记录、设置）
     */
    override fun getItemCount(): Int = 3

    /**
     * 根据索引创建对应的顶级 Tab 页面 Fragment 实例。
     *
     * @param position 页面索引下标（0 为检测，1 为记录，2 为设置）
     * @return 对应的 [Fragment] 实例
     */
    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> DetectionFragment.newInstance()
            1 -> HistoryFragment.newInstance()
            2 -> SettingsFragment.newInstance()
            else -> DetectionFragment.newInstance()
        }
    }
}
