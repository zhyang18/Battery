package com.battery.analysis.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.battery.analysis.databinding.FragmentDetectionBinding
import com.battery.analysis.viewmodel.BatteryViewModel
import com.google.android.material.tabs.TabLayoutMediator

/**
 * 电池检测顶级页面 Fragment。
 * 承载上方三大子页签（系统api、Shizuku、错误报告）与 ViewPager2 左右滑动手势容器，并提供快速刷新按钮。
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
     * 视图创建完毕后的生命周期回调，配置 ViewPager2 与 TabLayout 联动及刷新事件。
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

        // 2. 刷新按钮点击事件
        binding.btnRefresh.setOnClickListener {
            context?.let { ctx ->
                viewModel.refreshData(ctx)
                Toast.makeText(ctx, "数据已刷新", Toast.LENGTH_SHORT).show()
            }
        }
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
