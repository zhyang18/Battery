package com.battery.analysis.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.battery.analysis.databinding.FragmentShizukuBinding
import com.battery.analysis.viewmodel.BatteryViewModel
import com.battery.analysis.MainActivity
import kotlinx.coroutines.launch

/**
 * Shizuku 底层数据展示 Tab 页面 Fragment。
 * 负责展示通过 Shizuku 执行 root/shell 提权后从 sysfs/dumpsys/厂商服务提取的底层核心电池指标。
 */
class ShizukuTabFragment : Fragment() {

    private var _binding: FragmentShizukuBinding? = null
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
        _binding = FragmentShizukuBinding.inflate(inflater, container, false)
        return binding.root
    }

    /**
     * 视图创建完毕后的生命周期回调，配置数据流观察与刷新。
     *
     * @param view 创建完成的根视图
     * @param savedInstanceState 状态保存 Bundle
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupScrollListener()

        // 收集 Shizuku 电池数据流
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.shizukuBatteryInfo.collect { info ->
                    BatteryParamViewBinder.bind(binding.layoutParams, info)
                }
            }
        }
    }

    /**
     * 配置列表滚动与手势监听，联动控制 MainActivity 底部页签栏的显示与隐藏。
     */
    private fun setupScrollListener() {
        binding.nestedScrollView.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            val dy = scrollY - oldScrollY
            if (scrollY <= 0) {
                // 滚动至列表最顶端，强制恢复底部页签栏展示
                (activity as? MainActivity)?.setBottomNavigationVisibility(true)
            } else if (dy > 6) {
                // 手指向上滑动列表，隐藏底部页签栏
                (activity as? MainActivity)?.setBottomNavigationVisibility(false)
            } else if (dy < -12) {
                // 手指向下滑动列表，显示底部页签栏
                (activity as? MainActivity)?.setBottomNavigationVisibility(true)
            }
        }

        var startTouchY = 0f
        binding.nestedScrollView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startTouchY = event.rawY
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val deltaY = event.rawY - startTouchY
                    if (deltaY < -20f) {
                        // 手指持续向上拖动，确保底部导航栏保持隐藏
                        (activity as? MainActivity)?.setBottomNavigationVisibility(false)
                        startTouchY = event.rawY
                    } else if (deltaY > 20f && binding.nestedScrollView.scrollY <= 0) {
                        // 处于最顶部且手指向下拉动，恢复展示底部导航栏
                        (activity as? MainActivity)?.setBottomNavigationVisibility(true)
                        startTouchY = event.rawY
                    }
                }
            }
            false
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
         * 静态工厂方法，用于创建 [ShizukuTabFragment] 实例。
         *
         * @return 新建的 [ShizukuTabFragment]
         */
        fun newInstance(): ShizukuTabFragment {
            return ShizukuTabFragment()
        }
    }
}
