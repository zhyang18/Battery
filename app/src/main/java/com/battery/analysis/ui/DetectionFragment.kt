package com.battery.analysis.ui

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.battery.analysis.databinding.FragmentDetectionBinding
import com.battery.analysis.viewmodel.BatteryViewModel
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 电池检测顶级页面 Fragment。
 * 承载上方三大子页签（系统api、Shizuku、错误报告）与 ViewPager2 左右滑动手势容器，并支持下拉解耦独立刷新数据。
 */
class DetectionFragment : Fragment() {

    private var _binding: FragmentDetectionBinding? = null
    private val binding get() = _binding!!

    private val viewModel: BatteryViewModel by activityViewModels()

    /**
     * 创建 Fragment 的视图层级。
     *
     * @param inflater 布局填充器
     * @param container 父容器视图
     * @param savedInstanceState 状态保存 Bundle
     * @return 初始化的根视图 [View]
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDetectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    /**
     * 视图创建完毕后的生命周期回调，配置 ViewPager2、TabLayout 联动及解耦独立下拉刷新。
     *
     * @param view 创建完成的根视图
     * @param savedInstanceState 状态保存 Bundle
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1. 初始化子 ViewPager2 与 TabLayout
        val pagerAdapter = DetectionPagerAdapter(this)
        binding.detectionViewPager.adapter = pagerAdapter
        binding.detectionViewPager.offscreenPageLimit = 2

        val tabTitles = arrayOf("系统api", "Shizuku", "错误报告")

        TabLayoutMediator(binding.tabLayout, binding.detectionViewPager) { tab, position ->
            tab.text = tabTitles.getOrElse(position) { "" }
        }.attach()



        // 3. 初始化 SwipeRefreshLayout 下拉刷新：根据当前选中的 Tab 精准触发对应独立刷新
        binding.swipeRefreshLayout.setColorSchemeColors(Color.parseColor("#2196F3"))
        binding.swipeRefreshLayout.setOnRefreshListener {
            val ctx = context
            if (ctx == null) {
                binding.swipeRefreshLayout.isRefreshing = false
                return@setOnRefreshListener
            }

            val currentTab = binding.detectionViewPager.currentItem
            when (currentTab) {
                0 -> {
                    viewModel.refreshNormalApi(ctx)
                    viewLifecycleOwner.lifecycleScope.launch {
                        delay(200)
                        if (_binding != null) {
                            binding.swipeRefreshLayout.isRefreshing = false
                            Toast.makeText(requireContext(), "系统 API 数据已更新", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                1 -> {
                    viewModel.refreshShizuku(ctx)
                    viewLifecycleOwner.lifecycleScope.launch {
                        delay(500)
                        if (_binding != null) {
                            binding.swipeRefreshLayout.isRefreshing = false
                            Toast.makeText(requireContext(), "Shizuku 数据已更新", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                else -> {
                    viewLifecycleOwner.lifecycleScope.launch {
                        delay(200)
                        if (_binding != null) {
                            binding.swipeRefreshLayout.isRefreshing = false
                            Toast.makeText(requireContext(), "错误报告请点击卡片重新导入", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    /**
     * 获取当前处于活跃展示状态的子 Tab 索引位置（0: 系统api, 1: Shizuku, 2: 错误报告）。
     *
     * @return 当前子 Tab 的索引数值
     */
    fun getCurrentTabPosition(): Int {
        return _binding?.detectionViewPager?.currentItem ?: 0
    }

    /**
     * 视图销毁时的清理工作。
     */
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /**
         * 静态工厂方法，用于创建 [DetectionFragment] 实例。
         *
         * @return 新建的 [DetectionFragment]
         */
        fun newInstance(): DetectionFragment {
            return DetectionFragment()
        }
    }
}
