package com.example.batteryapp

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.batteryapp.databinding.FragmentBugreportBinding
import kotlinx.coroutines.launch

/**
 * 错误报告 (Bugreport) 解析展示 Tab 页面 Fragment。
 * 提供错误报告日志文件的选择导入、秒级快速流式解析进度提示、结构化电池参数展示及原始数据折叠视图。
 */
class BugreportTabFragment : Fragment() {

    private var _binding: FragmentBugreportBinding? = null
    private val binding get() = _binding!!

    private val viewModel: BatteryViewModel by activityViewModels()

    private var isRawLogExpanded: Boolean = true

    /**
     * 文件选择器回调：用于选择错误报告 zip / txt 日志文件并启动流式解析。
     */
    private val openBugreportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            context?.let { ctx ->
                viewModel.importBugreport(ctx, uri)
            }
        }
    }

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
        _binding = FragmentBugreportBinding.inflate(inflater, container, false)
        return binding.root
    }

    /**
     * 视图创建完毕后的生命周期回调，配置数据流观察与用户交互事件。
     *
     * @param view 创建完成的根视图
     * @param savedInstanceState 状态保存 Bundle
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 导入错误报告 / 取消解析按钮点击事件
        binding.btnImportBugreport.setOnClickListener {
            if (viewModel.isParsingBugreport.value) {
                viewModel.cancelBugreportParsing()
                Toast.makeText(requireContext(), "已取消解析", Toast.LENGTH_SHORT).show()
            } else {
                openBugreportLauncher.launch(arrayOf("*/*", "application/zip", "text/plain"))
            }
        }

        // 原始数据折叠/展开事件
        binding.rawToggleHeader.setOnClickListener {
            isRawLogExpanded = !isRawLogExpanded
            binding.rawContentContainer.visibility = if (isRawLogExpanded) View.VISIBLE else View.GONE
            binding.tvToggleIcon.text = if (isRawLogExpanded) "▾" else "▸"
        }

        // 监听 ViewModel 中的状态与数据流
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.bugreportResult.collect { result ->
                        BatteryParamViewBinder.bind(binding.layoutParams, result?.parsedBatteryInfo)
                        if (result != null && result.rawHealthInfoText.isNotEmpty()) {
                            binding.tvRawHealthInfo.text = result.rawHealthInfoText
                        }
                    }
                }

                launch {
                    viewModel.isParsingBugreport.collect { isParsing ->
                        binding.progressBarBugreport.visibility = if (isParsing) View.VISIBLE else View.GONE
                        if (!isParsing) {
                            binding.btnImportBugreport.text = "导入报告"
                        }
                    }
                }

                launch {
                    viewModel.bugreportProgress.collect { progress ->
                        if (viewModel.isParsingBugreport.value) {
                            binding.progressBarBugreport.progress = progress
                            binding.btnImportBugreport.text = "取消 ($progress%)"
                        }
                    }
                }

                launch {
                    viewModel.bugreportStatus.collect { status ->
                        binding.tvBugreportStatus.text = status
                    }
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
         * 静态工厂方法，用于创建 [BugreportTabFragment] 实例。
         *
         * @return 新建的 [BugreportTabFragment]
         */
        fun newInstance(): BugreportTabFragment {
            return BugreportTabFragment()
        }
    }
}
