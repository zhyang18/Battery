package com.battery.analysis.ui

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.battery.analysis.R
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

        val tabTitles = arrayOf(
            getString(R.string.tab_normal_api),
            getString(R.string.tab_shizuku),
            getString(R.string.tab_bugreport)
        )

        TabLayoutMediator(binding.tabLayout, binding.detectionViewPager) { tab, position ->
            tab.text = tabTitles.getOrElse(position) { "" }
        }.attach()

        // 2. 页面切换监听：同步当前活跃 Tab 索引至 ViewModel，并在滑动过程中动态锁定下拉刷新避免误触
        binding.detectionViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            /**
             * 页面选中事件回调。
             *
             * @param position 选中的页面索引
             */
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                viewModel.updateActiveTab(position)
            }

            /**
             * 页面滑动状态改变回调。在滑动进行中禁用下拉刷新，空闲时恢复。
             *
             * @param state 当前滑动状态 [ViewPager2.SCROLL_STATE_IDLE]、[ViewPager2.SCROLL_STATE_DRAGGING] 或 [ViewPager2.SCROLL_STATE_SETTLING]
             */
            override fun onPageScrollStateChanged(state: Int) {
                super.onPageScrollStateChanged(state)
                _binding?.swipeRefreshLayout?.isEnabled = (state == ViewPager2.SCROLL_STATE_IDLE)
            }
        })

        // 3. 初始化 SwipeRefreshLayout 下拉刷新：根据当前选中的 Tab 精准触发对应独立刷新
        binding.swipeRefreshLayout.setColorSchemeColors(Color.parseColor("#2196F3"))
        binding.swipeRefreshLayout.setOnRefreshListener {
            val ctx = context
            if (ctx == null) {
                binding.swipeRefreshLayout.isRefreshing = false
                return@setOnRefreshListener
            }

            val currentTab = binding.detectionViewPager.currentItem
            if (viewModel.isViewingHistorySnapshot.value) {
                viewModel.exitHistorySnapshotMode(ctx)
                Toast.makeText(requireContext(), getString(R.string.toast_restored_realtime), Toast.LENGTH_SHORT).show()
            }
            when (currentTab) {
                0 -> {
                    viewModel.refreshNormalApi(ctx)
                    viewLifecycleOwner.lifecycleScope.launch {
                        delay(200)
                        if (_binding != null) {
                            binding.swipeRefreshLayout.isRefreshing = false
                            Toast.makeText(requireContext(), getString(R.string.toast_system_api_updated), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                1 -> {
                    viewModel.refreshShizuku(ctx)
                    viewLifecycleOwner.lifecycleScope.launch {
                        delay(500)
                        if (_binding != null) {
                            binding.swipeRefreshLayout.isRefreshing = false
                            Toast.makeText(requireContext(), getString(R.string.toast_shizuku_data_updated), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                else -> {
                    viewLifecycleOwner.lifecycleScope.launch {
                        delay(200)
                        if (_binding != null) {
                            binding.swipeRefreshLayout.isRefreshing = false
                            Toast.makeText(requireContext(), getString(R.string.toast_bugreport_reimport_tip), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        // 4. 点击“恢复实时”按钮退出历史快照模式，刷新恢复最新实时数据
        binding.btnRestoreRealtime.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            viewModel.exitHistorySnapshotMode(ctx)
            Toast.makeText(requireContext(), getString(R.string.toast_restored_realtime), Toast.LENGTH_SHORT).show()
        }

        // 5. 观察历史快照查看状态流，动态显示/隐藏顶部提示横幅
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isViewingHistorySnapshot.collect { isViewing ->
                    if (isViewing) {
                        val record = viewModel.loadedSnapshotRecord.value
                        val timeStr = record?.captureTime ?: ""
                        binding.tvHistorySnapshotHint.text = getString(R.string.history_snapshot_banner_format, timeStr)
                        binding.layoutHistorySnapshotBanner.visibility = View.VISIBLE
                    } else {
                        binding.layoutHistorySnapshotBanner.visibility = View.GONE
                    }
                }
            }
        }

        // 6. 观察目标子 Tab 定位请求并自动滑动至对应分类
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.targetSnapshotTab.collect { targetTab ->
                    if (targetTab != null) {
                        binding.detectionViewPager.setCurrentItem(targetTab, false)
                        viewModel.clearTargetSnapshotTab()
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
