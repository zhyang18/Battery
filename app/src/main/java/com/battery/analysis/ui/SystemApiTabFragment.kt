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
import com.battery.analysis.databinding.FragmentSystemApiBinding
import com.battery.analysis.viewmodel.BatteryViewModel
import com.battery.analysis.MainActivity
import kotlinx.coroutines.launch

/**
 * 系统 API 数据展示 Tab 页面 Fragment。
 * 负责观察 ViewModel 中的系统原生 API 电池数据，并将其渲染至界面。
 */
class SystemApiTabFragment : Fragment() {

    private var _binding: FragmentSystemApiBinding? = null
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
        _binding = FragmentSystemApiBinding.inflate(inflater, container, false)
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

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.normalBatteryInfo.collect { info ->
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
            if (dy > 4) {
                // 手指向上滑动列表，灵敏联动隐藏底部页签栏
                (activity as? MainActivity)?.setBottomNavigationVisibility(false)
            } else if (dy < -8 || (dy < 0 && scrollY <= 0)) {
                // 手指向下滑动列表或已回滚至最顶端，恢复展示底部页签栏
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
                    if (deltaY < -12f) {
                        // 手指向上拖动，确保立即联动隐藏底部导航栏，避免遮挡底部内容
                        (activity as? MainActivity)?.setBottomNavigationVisibility(false)
                        startTouchY = event.rawY
                    } else if (deltaY > 15f && binding.nestedScrollView.scrollY <= 0) {
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
         * 静态工厂方法，用于创建 [SystemApiTabFragment] 实例。
         *
         * @return 新建的 [SystemApiTabFragment]
         */
        fun newInstance(): SystemApiTabFragment {
            return SystemApiTabFragment()
        }
    }
}
