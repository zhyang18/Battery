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

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.normalBatteryInfo.collect { info ->
                    BatteryParamViewBinder.bind(binding.layoutParams, info)
                }
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
         * 静态工厂方法，用于创建 [SystemApiTabFragment] 实例。
         *
         * @return 新建的 [SystemApiTabFragment]
         */
        fun newInstance(): SystemApiTabFragment {
            return SystemApiTabFragment()
        }
    }
}
