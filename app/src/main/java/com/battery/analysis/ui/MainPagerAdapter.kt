package com.battery.analysis.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * 主界面 ViewPager2 页面适配器。
 * 负责管理“系统api”、“Shizuku”、“错误报告”三大 Tab 对应 Fragment 的创建与生命周期。
 */
class MainPagerAdapter(fragmentActivity: FragmentActivity) : FragmentStateAdapter(fragmentActivity) {

    /**
     * 获取 ViewPager2 中总页数。
     *
     * @return 固定为 3（系统api、Shizuku、错误报告）
     */
    override fun getItemCount(): Int = 3

    /**
     * 根据索引创建对应的 Tab 页面 Fragment 实例。
     *
     * @param position 页面索引下标（0 为系统 API，1 为 Shizuku，2 为错误报告）
     * @return 对应的 [Fragment] 实例
     */
    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> SystemApiTabFragment.newInstance()
            1 -> ShizukuTabFragment.newInstance()
            2 -> BugreportTabFragment.newInstance()
            else -> SystemApiTabFragment.newInstance()
        }
    }
}
