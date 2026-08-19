package com.battery.analysis.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.battery.analysis.R
import com.battery.analysis.databinding.FragmentHistoryBinding
import com.battery.analysis.model.HistoryRecord
import com.battery.analysis.viewmodel.BatteryViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 历史记录顶级页面 Fragment。
 * 负责展示已保存的电池检测快照历史单行列表，支持按系统api、Shizuku、错误报告三大分类进行等宽筛选展示、一键同时保存三大数据源快照、查看详情、左滑手势删除与全量清空等功能。
 */
class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: BatteryViewModel by activityViewModels()
    private lateinit var adapter: HistoryAdapter

    private var shouldScrollToTopOnUpdate = false

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
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    /**
     * 视图创建完毕后的生命周期回调，配置单行列表适配器、左滑删除手势、等宽分类栏联动、数据流观察及各项按钮交互。
     *
     * @param view 创建完成的根视图
     * @param savedInstanceState 状态保存 Bundle
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupSwipeToDelete()
        setupCategoryTabs()
        setupListeners()
        observeData()

        // 初始加载历史数据
        context?.let { ctx ->
            viewModel.loadHistoryRecords(ctx)
        }
    }

    /**
     * 初始化 RecyclerView 与单行列表适配器，并显式启用嵌套滚动支持。
     */
    private fun setupRecyclerView() {
        adapter = HistoryAdapter(
            onItemClick = { record -> showRecordDetailsDialog(record) }
        )
        binding.rvHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.rvHistory.adapter = adapter
        binding.rvHistory.isNestedScrollingEnabled = true
    }

    /**
     * 初始化 RecyclerView 的左滑手势删除事件与平滑跟随绘制。
     */
    private fun setupSwipeToDelete() {
        val swipeCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            private val bgPaint = Paint().apply {
                color = Color.parseColor("#EF4444")
                isAntiAlias = true
            }
            private val deleteIcon: Drawable? by lazy {
                ContextCompat.getDrawable(requireContext(), R.drawable.ic_delete)?.apply {
                    setTint(Color.WHITE)
                }
            }

            /**
             * 拖拽排序移动回调（本页面不使用拖拽排序）。
             *
             * @param recyclerView 目标 RecyclerView
             * @param viewHolder 拖拽视图持有者
             * @param target 目标位置持有者
             * @return 始终返回 false
             */
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            /**
             * 左滑触发删除手势回调。
             *
             * @param viewHolder 滑动视图持有者
             * @param direction 滑动方向
             */
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                if (position != RecyclerView.NO_POSITION && position < adapter.currentList.size) {
                    val record = adapter.currentList[position]
                    // 刷新视图防止空隙残留，并弹出高颜值二次确认弹窗
                    adapter.notifyItemChanged(position)
                    showDeleteConfirmDialog(record)
                }
            }

            /**
             * 绘制左滑过程中的红色背景与垃圾桶图标。
             *
             * @param c 画布
             * @param recyclerView 目标 RecyclerView
             * @param viewHolder 视图持有者
             * @param dX X轴位移距离
             * @param dY Y轴位移距离
             * @param actionState 动作状态
             * @param isCurrentlyActive 是否处于手势活跃中
             */
            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                val itemView = viewHolder.itemView
                if (dX < 0) { // 向左滑动
                    val cornerRadius = 12 * resources.displayMetrics.density
                    val rectF = RectF(
                        itemView.right + dX,
                        itemView.top.toFloat(),
                        itemView.right.toFloat(),
                        itemView.bottom.toFloat()
                    )
                    c.drawRoundRect(rectF, cornerRadius, cornerRadius, bgPaint)

                    // 绘制白色垃圾桶图标
                    deleteIcon?.let { icon ->
                        val iconMargin = (itemView.height - icon.intrinsicHeight) / 2
                        val iconTop = itemView.top + iconMargin
                        val iconBottom = iconTop + icon.intrinsicHeight
                        val iconRight = itemView.right - 24 * resources.displayMetrics.density.toInt()
                        val iconLeft = iconRight - icon.intrinsicWidth

                        if (itemView.right + dX < iconLeft) {
                            icon.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                            icon.draw(c)
                        }
                    }
                }
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        }

        ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.rvHistory)
    }

    /**
     * 初始化等宽均分的四大分类筛选标签（全部、系统api、Shizuku、错误报告）。
     */
    private fun setupCategoryTabs() {
        binding.btnFilterAll.setOnClickListener {
            viewModel.setSelectedCategory("全部")
        }
        binding.btnFilterNormal.setOnClickListener {
            viewModel.setSelectedCategory("系统api")
        }
        binding.btnFilterShizuku.setOnClickListener {
            viewModel.setSelectedCategory("Shizuku")
        }
        binding.btnFilterBugreport.setOnClickListener {
            viewModel.setSelectedCategory("错误报告")
        }
    }

    /**
     * 更新四大等宽分类按钮的选中高亮状态。
     *
     * @param selectedCategory 当前选中的分类名称
     */
    private fun updateCategoryTabsState(selectedCategory: String) {
        val tabs = listOf(
            Pair(binding.btnFilterAll, "全部"),
            Pair(binding.btnFilterNormal, "系统api"),
            Pair(binding.btnFilterShizuku, "Shizuku"),
            Pair(binding.btnFilterBugreport, "错误报告")
        )

        for ((textView, cat) in tabs) {
            val isSelected = (cat == selectedCategory)
            if (isSelected) {
                textView.setBackgroundResource(R.drawable.bg_filter_chip_selected)
                textView.setTextColor(Color.WHITE)
                textView.setTypeface(null, android.graphics.Typeface.BOLD)
            } else {
                textView.setBackgroundResource(R.drawable.bg_filter_chip_normal)
                textView.setTextColor(Color.parseColor("#9CA3AF"))
                textView.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
        }
    }

    /**
     * 初始化顶部保存、清空按钮及下拉刷新手势监听器。
     */
    private fun setupListeners() {
        // 1. 顶部“保存快照”按钮：一键同时保存三种数据
        binding.btnSaveSnapshot.setOnClickListener {
            handleSaveAllSnapshots()
        }

        // 2. 空状态下的“立即保存当前快照”按钮
        binding.btnEmptySaveSnapshot.setOnClickListener {
            handleSaveAllSnapshots()
        }

        // 3. 顶部“一键清空”按钮
        binding.btnClearHistory.setOnClickListener {
            showClearAllConfirmDialog()
        }

        // 4. 下拉刷新
        binding.swipeRefreshHistory.setColorSchemeColors(Color.parseColor("#2196F3"))
        binding.swipeRefreshHistory.setOnRefreshListener {
            context?.let { ctx ->
                viewModel.loadHistoryRecords(ctx)
            }
            viewLifecycleOwner.lifecycleScope.launch {
                delay(300)
                if (_binding != null) {
                    binding.swipeRefreshHistory.isRefreshing = false
                }
            }
        }

        // 5. 解决 AppBarLayout 与 SwipeRefreshLayout 嵌套滑动冲突引起的向下滑动高频闪烁
        binding.appBarLayoutHistory.addOnOffsetChangedListener { _, verticalOffset ->
            binding.swipeRefreshHistory.isEnabled = (verticalOffset == 0)
        }
    }

    /**
     * 观察历史记录列表与分类筛选状态。
     */
    private fun observeData() {
        // 1. 观察经过分类筛选后的记录列表
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.filteredHistoryRecords.collect { records ->
                    adapter.submitList(records) {
                        if (shouldScrollToTopOnUpdate) {
                            binding.rvHistory.scrollToPosition(0)
                            shouldScrollToTopOnUpdate = false
                        }
                    }
                    val count = records.size
                    binding.tvHistoryCount.text = "共 $count 条"

                    if (records.isEmpty()) {
                        binding.rvHistory.visibility = View.GONE
                        binding.layoutEmptyHistory.visibility = View.VISIBLE
                        if (viewModel.selectedCategory.value != "全部") {
                            binding.tvEmptyTitle.text = "【${viewModel.selectedCategory.value}】分类下暂无记录"
                            binding.tvEmptyDesc.text = "您可以切换上方其他分类标签查看，或点击下方按钮保存当前全部数据源快照。"
                        } else {
                            binding.tvEmptyTitle.text = "暂无电池检测记录"
                            binding.tvEmptyDesc.text = "点击右上角或下方按钮，可一次性同时保存【系统api、Shizuku、错误报告】的全部检测数据。"
                        }
                    } else {
                        binding.rvHistory.visibility = View.VISIBLE
                        binding.layoutEmptyHistory.visibility = View.GONE
                    }
                }
            }
        }

        // 2. 观察当前选中的分类，同步高亮状态
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.selectedCategory.collect { selected ->
                    updateCategoryTabsState(selected)
                }
            }
        }

        // 3. 观察全量记录控制清空按钮展示
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.historyRecords.collect { allRecords ->
                    binding.btnClearHistory.visibility = if (allRecords.isNotEmpty()) View.VISIBLE else View.GONE
                }
            }
        }

        // 4. 观察健康度衰减趋势数据点流，绘制平滑折线图并更新统计概要
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.healthTrendPoints.collect { points ->
                    binding.chartHealthTrend.setData(points)

                    if (points.isNotEmpty()) {
                        binding.layoutTrendChartCard.visibility = View.VISIBLE
                        val latestHealth = points.last().health
                        if (points.size >= 2) {
                            val firstHealth = points.first().health
                            val delta = latestHealth - firstHealth
                            if (delta < 0) {
                                binding.tvTrendSummary.text = String.format("最新 %.2f%% (衰减 %.2f%%)", latestHealth, delta)
                                binding.tvTrendSummary.setTextColor(Color.parseColor("#EF4444"))
                            } else {
                                binding.tvTrendSummary.text = String.format("最新 %.2f%%", latestHealth)
                                binding.tvTrendSummary.setTextColor(Color.parseColor("#10B981"))
                            }
                        } else {
                            binding.tvTrendSummary.text = String.format("当前 %.2f%%", latestHealth)
                            binding.tvTrendSummary.setTextColor(Color.parseColor("#10B981"))
                        }
                    } else {
                        binding.layoutTrendChartCard.visibility = View.GONE
                    }
                }
            }
        }
    }

    /**
     * 一键同时保存系统api、Shizuku、错误报告三种分类的历史快照，并在成功后自动滚动列表至顶部。
     */
    private fun handleSaveAllSnapshots() {
        val ctx = context ?: return
        shouldScrollToTopOnUpdate = true
        viewModel.saveAllSnapshots(ctx) { savedCategories ->
            if (savedCategories.isNotEmpty()) {
                val catStr = savedCategories.joinToString("、")
                Toast.makeText(ctx, "已同时保存【$catStr】历史快照", Toast.LENGTH_SHORT).show()
                binding.rvHistory.post {
                    binding.rvHistory.scrollToPosition(0)
                }
            } else {
                shouldScrollToTopOnUpdate = false
                Toast.makeText(ctx, "当前暂无可用的电池检测数据，请先在检测页刷新", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 弹出单条历史记录的完整 14 项指标大圆角自定义详情对话框（键值左右分开排列）。
     *
     * @param record 待查看的历史记录对象
     */
    private fun showRecordDetailsDialog(record: HistoryRecord) {
        val detailsText = record.formatFullDetails()
        val dialogView = layoutInflater.inflate(R.layout.dialog_history_detail, null)

        val tvTitle = dialogView.findViewById<TextView>(R.id.tv_dialog_detail_title)
        val container = dialogView.findViewById<ViewGroup>(R.id.layout_detail_items_container)
        val btnClose = dialogView.findViewById<TextView>(R.id.btn_dialog_detail_close)
        val btnCopy = dialogView.findViewById<TextView>(R.id.btn_dialog_detail_copy)

        tvTitle.text = "【${record.category}】检测详情"
        container.removeAllViews()

        val pairs = record.getDetailPairs()
        pairs.forEachIndexed { index, triple ->
            val rowView = layoutInflater.inflate(R.layout.item_dialog_detail_row, container, false)
            val tvLabel = rowView.findViewById<TextView>(R.id.tv_row_label)
            val tvValue = rowView.findViewById<TextView>(R.id.tv_row_value)
            val divider = rowView.findViewById<View>(R.id.divider_row)

            tvLabel.text = triple.first
            tvValue.text = triple.second
            triple.third?.let { colorHex ->
                tvValue.setTextColor(Color.parseColor(colorHex))
            }

            // 最后一行隐藏分割线
            if (index == pairs.size - 1) {
                divider.visibility = View.GONE
            }

            container.addView(rowView)
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        btnCopy.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Battery History Record", detailsText)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), "已复制该条历史记录至剪贴板", Toast.LENGTH_SHORT).show()
        }

        dialog.show()

        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            val width = (resources.displayMetrics.widthPixels * 0.92).toInt()
            window.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setGravity(android.view.Gravity.CENTER)
        }
    }

    /**
     * 弹出删除单条记录的现代化精致二次确认对话框。
     *
     * @param record 待删除的历史记录对象
     */
    private fun showDeleteConfirmDialog(record: HistoryRecord) {
        val ctx = context ?: return
        val dialogView = layoutInflater.inflate(R.layout.dialog_custom_delete_confirm, null)

        val tvTitle = dialogView.findViewById<TextView>(R.id.tv_dialog_delete_title)
        val tvDesc = dialogView.findViewById<TextView>(R.id.tv_dialog_delete_desc)
        val layoutPreview = dialogView.findViewById<View>(R.id.layout_delete_item_preview)
        val tvPreviewCat = dialogView.findViewById<TextView>(R.id.tv_preview_cat)
        val tvPreviewTime = dialogView.findViewById<TextView>(R.id.tv_preview_time)
        val tvPreviewSummary = dialogView.findViewById<TextView>(R.id.tv_preview_summary)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btn_dialog_delete_cancel)
        val btnConfirm = dialogView.findViewById<TextView>(R.id.btn_dialog_delete_confirm)

        tvTitle.text = "确认删除该条快照？"
        tvDesc.text = "删除后此条电池检测记录将从本地数据库中永久移除。"
        layoutPreview.visibility = View.VISIBLE

        tvPreviewCat.text = record.category
        when {
            record.category.contains("Shizuku", ignoreCase = true) -> tvPreviewCat.setTextColor(Color.parseColor("#818CF8"))
            record.category.contains("错误报告", ignoreCase = true) -> tvPreviewCat.setTextColor(Color.parseColor("#10B981"))
            else -> tvPreviewCat.setTextColor(Color.parseColor("#2196F3"))
        }
        tvPreviewTime.text = record.captureTime

        val levelStr = record.level?.let { "🔋 $it%" } ?: "🔋 未知"
        val healthStr = record.batteryHealth?.let { "   💚 ${String.format("%.2f%%", it)}" } ?: ""
        val cycleStr = record.cycleCount?.let { "   🔄 $it 次" } ?: ""
        tvPreviewSummary.text = "$levelStr$healthStr$cycleStr"

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnConfirm.setOnClickListener {
            viewModel.deleteHistoryRecord(ctx, record.id)
            Toast.makeText(ctx, "已成功删除该条快照", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()

        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            val width = (resources.displayMetrics.widthPixels * 0.92).toInt()
            window.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setGravity(android.view.Gravity.CENTER)
        }
    }

    /**
     * 弹出清空全部历史记录的高颜值危险操作警告对话框。
     */
    private fun showClearAllConfirmDialog() {
        val ctx = context ?: return
        val totalCount = viewModel.historyRecords.value.size
        val dialogView = layoutInflater.inflate(R.layout.dialog_custom_delete_confirm, null)

        val tvTitle = dialogView.findViewById<TextView>(R.id.tv_dialog_delete_title)
        val tvDesc = dialogView.findViewById<TextView>(R.id.tv_dialog_delete_desc)
        val layoutPreview = dialogView.findViewById<View>(R.id.layout_delete_item_preview)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btn_dialog_delete_cancel)
        val btnConfirm = dialogView.findViewById<TextView>(R.id.btn_dialog_delete_confirm)

        tvTitle.text = "清空全部历史记录？"
        tvDesc.text = "确定要清空本地保存的全部 $totalCount 条电池快照吗？\n此操作不可撤销。"
        layoutPreview.visibility = View.GONE
        btnConfirm.text = "清空全部"

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnConfirm.setOnClickListener {
            viewModel.clearAllHistory(ctx)
            Toast.makeText(ctx, "已清空全部历史记录", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()

        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            val width = (resources.displayMetrics.widthPixels * 0.92).toInt()
            window.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setGravity(android.view.Gravity.CENTER)
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
         * 静态工厂方法，用于创建 [HistoryFragment] 实例。
         *
         * @return 新建的 [HistoryFragment]
         */
        fun newInstance(): HistoryFragment {
            return HistoryFragment()
        }
    }
}
