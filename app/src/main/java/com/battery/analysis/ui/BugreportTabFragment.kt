package com.battery.analysis.ui

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
import com.battery.analysis.MainActivity
import com.battery.analysis.R
import com.battery.analysis.databinding.FragmentBugreportBinding
import com.battery.analysis.viewmodel.BatteryViewModel
import kotlinx.coroutines.launch

/**
 * 错误报告 (Bugreport) 解析展示 Tab 页面 Fragment。
 * 提供错误报告日志文件的选择导入、流式解析进度提示、结构化电池参数展示及原始数据折叠视图。
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

        // 初始自动从历史快照中加载最新一条错误报告数据
        context?.let { ctx ->
            viewModel.loadLatestBugreportFromHistory(ctx)
        }

        // 导入错误报告 / 取消解析按钮点击事件
        binding.btnImportBugreport.setOnClickListener {
            if (viewModel.isParsingBugreport.value) {
                viewModel.cancelBugreportParsing(requireContext())
                Toast.makeText(requireContext(), getString(R.string.cancel), Toast.LENGTH_SHORT).show()
            } else {
                openBugreportLauncher.launch(arrayOf("*/*", "application/zip", "text/plain"))
            }
        }

        // 配置滚动监听联动隐藏底部页签栏
        setupScrollListener()

        // 原始数据折叠/展开事件
        binding.rawToggleHeader.setOnClickListener {
            isRawLogExpanded = !isRawLogExpanded
            binding.rawContentContainer.visibility = if (isRawLogExpanded) View.VISIBLE else View.GONE
            binding.ivToggleIcon.animate().rotation(if (isRawLogExpanded) 0f else -90f).setDuration(200).start()
        }

        // 监听 ViewModel 中的状态与数据流
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.bugreportResult.collect { result ->
                        BatteryParamViewBinder.bind(binding.layoutParams, result?.parsedBatteryInfo)
                        if (result != null) {
                            if (result.tableItems.isEmpty() && result.parsedBatteryInfo != null) {
                                val isZh = requireContext().resources.configuration.locales[0].language.startsWith("zh")
                                val header = if (isZh) {
                                    "【历史快照载入】\n检测时间: ${result.parsedBatteryInfo.captureTime ?: ""}\n数据来源: 错误报告快照\n"
                                } else {
                                    "[History Snapshot Loaded]\nCapture Time: ${result.parsedBatteryInfo.captureTime ?: ""}\nSource: Bugreport Snapshot\n"
                                }
                                val record = com.battery.analysis.model.HistoryRecord.fromBatteryInfo(result.parsedBatteryInfo, "错误报告")
                                binding.tvRawHealthInfo.text = "$header${record.formatFullDetails(requireContext())}"
                            } else if (result.rawHealthInfoText.isNotEmpty()) {
                                binding.tvRawHealthInfo.text = result.rawHealthInfoText
                            }
                        }
                    }
                }

                launch {
                    viewModel.isParsingBugreport.collect { isParsing ->
                        binding.progressBarBugreport.visibility = if (isParsing) View.VISIBLE else View.GONE
                        if (!isParsing) {
                            binding.btnImportBugreport.text = getString(R.string.bugreport_btn_import)
                        }
                    }
                }

                launch {
                    viewModel.bugreportProgress.collect { progress ->
                        if (viewModel.isParsingBugreport.value) {
                            binding.progressBarBugreport.progress = progress
                            binding.btnImportBugreport.text = "${getString(R.string.bugreport_btn_cancel)} ($progress%)"
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
         * 静态工厂方法，用于创建 [BugreportTabFragment] 实例。
         *
         * @return 新建的 [BugreportTabFragment]
         */
        fun newInstance(): BugreportTabFragment {
            return BugreportTabFragment()
        }
    }
}
