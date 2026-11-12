package com.battery.analysis.ui

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.battery.analysis.R
import com.battery.analysis.db.CapacityCandidateDbHelper
import com.battery.analysis.domain.capacity.model.DataPurityCondition
import com.battery.analysis.domain.capacity.model.StartDischargeCondition
import com.battery.analysis.domain.capacity.model.StartDischargePolicy
import com.battery.analysis.manager.BatteryCapacityManager
import com.battery.analysis.model.CandidateSessionType
import com.battery.analysis.model.CapacityCandidate
import com.battery.analysis.util.DialogWindowHelper
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自学习充放电样本明细列表底部抽屉弹窗（CapacitySessionListBottomSheetDialog）。
 * 展示所有参与或历史归档的充放电样本，支持顶部 Tab 点击与列表区域左右手势滑动切换（全部/充电/放电），
 * 直观呈现起止电量、净电量、推算容量与模型纳入状态。
 */
class CapacitySessionListBottomSheetDialog : BottomSheetDialogFragment() {

    private var allCandidates: List<CapacityCandidate> = emptyList()
    private var currentThreshold: Int = 15
    private var currentCountLimit: Int = 10
    private var currentStartCondition: StartDischargeCondition = StartDischargeCondition()
    private var currentPurityCondition: DataPurityCondition = DataPurityCondition()
    private var isOnlyMeetsThreshold: Boolean = false
    private lateinit var sessionPageAdapter: SessionPageAdapter
    private var viewPager: ViewPager2? = null

    private val dateFormat = SimpleDateFormat("MM-dd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    /**
     * 创建并返回底部抽屉弹窗的根视图。
     *
     * @param inflater 布局填充器实例
     * @param container 父容器视图（若有）
     * @param savedInstanceState 状态保存数据包
     * @return 抽屉弹窗的根视图 [View]
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_capacity_session_list, container, false)
    }

    /**
     * 在抽屉弹窗启动展示时配置窗口属性，消除背景遮罩叠层，并固定统一高度。
     */
    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return

        // 1. 彻底清除二级弹窗自身的半透明遮罩（Dim），避免取消或关闭列表时主界面遮罩闪烁或变黑
        dialog.window?.let { window ->
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setDimAmount(0f)
        }

        // 2. 清除系统底层容器的默认不透明直角背景
        bottomSheet.background = null
        bottomSheet.setBackgroundColor(Color.TRANSPARENT)

        // 3. 始终统一固定弹窗高度为屏幕高度的 80%，避免切换充放电 Tab 时高度自适应变短抖动
        val screenHeight = resources.displayMetrics.heightPixels
        val targetHeight = (screenHeight * 0.80).toInt()
        val layoutParams = bottomSheet.layoutParams
        layoutParams.height = targetHeight
        bottomSheet.layoutParams = layoutParams

        val behavior = BottomSheetBehavior.from(bottomSheet)
        behavior.maxHeight = targetHeight
        behavior.peekHeight = targetHeight
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        behavior.skipCollapsed = true
        behavior.isHideable = true

        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                    dismissAllowingStateLoss()
                }
            }
            override fun onSlide(bottomSheet: View, slideOffset: Float) {}
        })

        dialog.setCanceledOnTouchOutside(false)
        dialog.findViewById<View>(com.google.android.material.R.id.touch_outside)?.setOnClickListener {
            dismissWithSlideDownAnimation()
        }

        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                dismissWithSlideDownAnimation()
                true
            } else false
        }
    }

    /**
     * 触发抽屉平滑向下滑出收起动效并安全关闭弹窗。
     */
    private fun dismissWithSlideDownAnimation() {
        val dialog = dialog as? BottomSheetDialog
        val bottomSheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        if (bottomSheet != null && isAdded) {
            val behavior = BottomSheetBehavior.from(bottomSheet)
            behavior.isHideable = true
            behavior.state = BottomSheetBehavior.STATE_HIDDEN
        } else {
            dismissAllowingStateLoss()
        }
    }

    /**
     * 视图构建完成后初始化事件监听、ViewPager2 左右手势滑动切换、分类 Tab 与数据绑定。
     *
     * @param view 弹窗根视图 [View]
     * @param savedInstanceState 状态保存数据包
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 从本地持久化偏好设置中读取上次记忆的“仅看达标”筛选状态
        isOnlyMeetsThreshold = loadFilterMeetsThresholdPreference()

        view.findViewById<ImageView>(R.id.btn_close_session_list)?.setOnClickListener {
            dismissWithSlideDownAnimation()
        }

        view.findViewById<TextView>(R.id.btn_filter_meets_threshold)?.setOnClickListener {
            toggleThresholdFilter()
        }
        updateThresholdFilterButtonUI()

        view.findViewById<TextView>(R.id.btn_session_conditions)?.setOnClickListener {
            showConditionsMenuDialog()
        }
        view.findViewById<TextView>(R.id.tv_session_list_subtitle)?.setOnClickListener {
            showConditionsMenuDialog()
        }

        val vp = view.findViewById<ViewPager2>(R.id.vp_capacity_sessions)
        viewPager = vp
        sessionPageAdapter = SessionPageAdapter(
            formatTimeRange = { start, end -> formatTimeRange(start, end) },
            onDeleteClicked = { candidate ->
                showDeleteConfirmDialog(candidate)
            }
        )
        vp.adapter = sessionPageAdapter
        // 预加载并常驻相邻页签，保障左右滑动手势极致顺畅无白屏
        vp.offscreenPageLimit = 2

        vp.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                updateTabStyles(position)
            }
        })

        setupTabs(view)
        loadData()
    }

    /**
     * 初始化顶部分类选项卡（全部、充电、放电）的点击切换联动。
     *
     * @param root 弹窗根视图 [View]
     */
    private fun setupTabs(root: View) {
        val tabAll = root.findViewById<TextView>(R.id.tab_session_all)
        val tabCharge = root.findViewById<TextView>(R.id.tab_session_charge)
        val tabDischarge = root.findViewById<TextView>(R.id.tab_session_discharge)

        tabAll.setOnClickListener {
            viewPager?.setCurrentItem(0, true)
        }
        tabCharge.setOnClickListener {
            viewPager?.setCurrentItem(1, true)
        }
        tabDischarge.setOnClickListener {
            viewPager?.setCurrentItem(2, true)
        }

        val currentPos = viewPager?.currentItem ?: 0
        updateTabStyles(currentPos)
    }

    /**
     * 根据当前选中的页签索引更新顶部全部、充电、放电三大 Tab 的高亮胶囊背景、文本颜色及粗体样式。
     *
     * @param selectedIndex 当前选中的页签下标（0：全部，1：充电，2：放电）
     */
    private fun updateTabStyles(selectedIndex: Int) {
        val tabAll = view?.findViewById<TextView>(R.id.tab_session_all) ?: return
        val tabCharge = view?.findViewById<TextView>(R.id.tab_session_charge) ?: return
        val tabDischarge = view?.findViewById<TextView>(R.id.tab_session_discharge) ?: return

        val activeColor = Color.parseColor("#10b981")
        val inactiveColor = Color.parseColor("#64748b")

        tabAll.setTextColor(if (selectedIndex == 0) activeColor else inactiveColor)
        tabCharge.setTextColor(if (selectedIndex == 1) activeColor else inactiveColor)
        tabDischarge.setTextColor(if (selectedIndex == 2) activeColor else inactiveColor)

        tabAll.setBackgroundResource(if (selectedIndex == 0) R.drawable.bg_session_tab_selected else R.drawable.bg_session_tab_unselected)
        tabCharge.setBackgroundResource(if (selectedIndex == 1) R.drawable.bg_session_tab_selected else R.drawable.bg_session_tab_unselected)
        tabDischarge.setBackgroundResource(if (selectedIndex == 2) R.drawable.bg_session_tab_selected else R.drawable.bg_session_tab_unselected)

        tabAll.paint.isFakeBoldText = (selectedIndex == 0)
        tabCharge.paint.isFakeBoldText = (selectedIndex == 1)
        tabDischarge.paint.isFakeBoldText = (selectedIndex == 2)
    }

    /**
     * 切换“已达门限”单项快速过滤开关，并联动刷新列表数据与胶囊按钮状态。
     * 同步将最新筛选状态永久记忆至本地持久化偏好设置中。
     */
    private fun toggleThresholdFilter() {
        isOnlyMeetsThreshold = !isOnlyMeetsThreshold
        saveFilterMeetsThresholdPreference(isOnlyMeetsThreshold)
        updateThresholdFilterButtonUI()
        applyDataToPages()
    }

    /**
     * 从本地偏好配置中加载“仅看达标”筛选开关历史记录。
     * 若此前未设置则返回默认值 false（展示全部样本）。
     *
     * @return 上次记忆的筛选状态布尔值
     */
    private fun loadFilterMeetsThresholdPreference(): Boolean {
        val ctx = context ?: return false
        val sp = ctx.getSharedPreferences(BatteryCapacityManager.PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getBoolean(PREF_KEY_FILTER_MEETS_THRESHOLD, false)
    }

    /**
     * 将用户当前选择的“仅看达标”筛选状态持久化保存至本地偏好配置。
     *
     * @param meetsThreshold 待持久化保存的筛选状态布尔值
     */
    private fun saveFilterMeetsThresholdPreference(meetsThreshold: Boolean) {
        val ctx = context ?: return
        val sp = ctx.getSharedPreferences(BatteryCapacityManager.PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putBoolean(PREF_KEY_FILTER_MEETS_THRESHOLD, meetsThreshold).apply()
    }

    /**
     * 刷新顶部“已达门限”切换胶囊按钮的高亮背景、边框、文案与文本粗细样式。
     */
    private fun updateThresholdFilterButtonUI() {
        val btnFilter = view?.findViewById<TextView>(R.id.btn_filter_meets_threshold) ?: return
        if (isOnlyMeetsThreshold) {
            btnFilter.text = getString(R.string.capacity_session_filter_meets_threshold_active)
            btnFilter.setTextColor(Color.parseColor("#10b981"))
            btnFilter.setBackgroundResource(R.drawable.bg_session_filter_chip_selected)
            btnFilter.paint.isFakeBoldText = true
        } else {
            btnFilter.text = getString(R.string.capacity_session_filter_meets_threshold)
            btnFilter.setTextColor(Color.parseColor("#94a3b8"))
            btnFilter.setBackgroundResource(R.drawable.bg_session_filter_chip_unselected)
            btnFilter.paint.isFakeBoldText = false
        }
    }

    /**
     * 异步从本地数据库加载所有充放电候选样本数据，并同步有效电量跨度阈值与最新条数时效窗口。
     */
    private fun loadData() {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val dbHelper = CapacityCandidateDbHelper.getInstance(ctx)
            val manager = BatteryCapacityManager.getInstance(ctx)
            currentThreshold = manager.getEffectiveSocSpanThreshold()
            currentCountLimit = manager.getEffectiveSampleCountLimit()
            currentStartCondition = manager.getStartDischargeCondition()
            currentPurityCondition = manager.getDataPurityCondition()

            allCandidates = withContext(Dispatchers.IO) {
                // 加载前自动执行历史虚高异常放电样本自愈校准，确保呈现给用户的皆为物理真值
                manager.sanitizeHistoricalCandidates()
                dbHelper.getAllCandidates()
            }
            applyDataToPages()
        }
    }

    /**
     * 判断单条样本是否满足当前设定的全部自学习准入条件（跨度、起始政策、数据纯度）。
     */
    private fun isCandidateEligible(candidate: CapacityCandidate): Boolean {
        if (candidate.isExcluded || candidate.fccObservedMah <= 0.0) return false
        if (candidate.socSpanPercent < currentThreshold) return false
        if (candidate.sessionType == CandidateSessionType.DISCHARGE) {
            if (!currentStartCondition.isSatisfied(candidate.startSoc)) return false
            val coveragePercent = candidate.qualityScore * 100.0
            if (coveragePercent < currentPurityCondition.minMeasuredCoveragePercent) return false
        }
        return true
    }

    /**
     * 将候选样本数据按分类（全部、充电、放电）及当前门限过滤状态分发至 ViewPager2 的各个子页面，并同步更新副标题统计和 Tab 数量。
     */
    private fun applyDataToPages() {
        val eligibleCandidates = allCandidates.filter { isCandidateEligible(it) }
        val includedIds = (if (currentCountLimit > 0) eligibleCandidates.take(currentCountLimit) else eligibleCandidates).map { it.id }.toSet()

        val filteredCandidates = if (isOnlyMeetsThreshold) {
            allCandidates.filter { isCandidateEligible(it) }
        } else {
            allCandidates
        }
        val chargeList = filteredCandidates.filter { it.sessionType == CandidateSessionType.CHARGE }
        val dischargeList = filteredCandidates.filter { it.sessionType == CandidateSessionType.DISCHARGE }

        sessionPageAdapter.updateAllPages(
            allData = filteredCandidates,
            chargeData = chargeList,
            dischargeData = dischargeList,
            threshold = currentThreshold,
            includedIds = includedIds,
            countLimit = currentCountLimit,
            startCondition = currentStartCondition,
            purityCondition = currentPurityCondition
        )

        val totalCount = allCandidates.size
        val validCount = eligibleCandidates.size
        val subtitle = view?.findViewById<TextView>(R.id.tv_session_list_subtitle)
        val startPolicyDesc = when (currentStartCondition.policy) {
            StartDischargePolicy.START_FROM_100_FULL -> "起始≥${currentStartCondition.minStartSocPercent}%"
            StartDischargePolicy.ANY_SOC -> "任意起步"
        }
        val purityDesc = "纯度≥${currentPurityCondition.minMeasuredCoveragePercent.toInt()}%"
        val limitDesc = if (currentCountLimit > 0 && validCount > currentCountLimit) "纳入最新 ${currentCountLimit} 条 · " else ""
        subtitle?.text = "共 $totalCount 次 · $validCount 次达标 (${limitDesc}跨度>${currentThreshold}% · $startPolicyDesc · $purityDesc) ⚙️"

        val tabAll = view?.findViewById<TextView>(R.id.tab_session_all)
        val tabCharge = view?.findViewById<TextView>(R.id.tab_session_charge)
        val tabDischarge = view?.findViewById<TextView>(R.id.tab_session_discharge)
        val layoutTabs = view?.findViewById<View>(R.id.layout_session_tabs)

        if (dischargeList.isEmpty()) {
            layoutTabs?.visibility = View.GONE
            viewPager?.isUserInputEnabled = false
            viewPager?.setCurrentItem(0, false)
        } else {
            layoutTabs?.visibility = View.VISIBLE
            viewPager?.isUserInputEnabled = true
        }

        tabAll?.text = getString(R.string.capacity_session_tab_all_format, filteredCandidates.size)
        tabCharge?.text = getString(R.string.capacity_session_tab_charge_format, chargeList.size)
        tabDischarge?.text = getString(R.string.capacity_session_tab_discharge_format, dischargeList.size)
    }

    /**
     * 将候选样本的起止时间戳格式化为易读的字符串。
     * 同一天内自动合并日期显示，避免多余重复文本。
     *
     * @param startTimestamp 会话开始绝对时间戳（毫秒）
     * @param endTimestamp 会话结束绝对时间戳（毫秒）
     * @return 格式化后的时间跨度字符串
     */
    private fun formatTimeRange(startTimestamp: Long, endTimestamp: Long): String {
        if (startTimestamp <= 0L && endTimestamp <= 0L) return "--"
        if (startTimestamp <= 0L) return timeFormat.format(Date(endTimestamp))
        if (endTimestamp <= 0L) return timeFormat.format(Date(startTimestamp))

        val startDate = dateFormat.format(Date(startTimestamp))
        val endDate = dateFormat.format(Date(endTimestamp))
        val startTime = timeFormat.format(Date(startTimestamp))
        val endTime = timeFormat.format(Date(endTimestamp))

        return if (startDate == endDate) {
            "$startDate $startTime ~ $endTime"
        } else {
            "$startDate $startTime ~ $endDate $endTime"
        }
    }

    /**
     * 弹出确认删除指定容量会话样本的统一现代化高颜值对话框。
     * 采用与系统各模块一致的圆角卡片布局、红色危险操作警告图标及双色功能按钮，
     * 并清晰预览待删除样本的类型徽章、起止时间、推算容量与电量变化。
     *
     * @param candidate 待删除的候选容量样本数据实体 [CapacityCandidate]
     */
    private fun showDeleteConfirmDialog(candidate: CapacityCandidate) {
        val ctx = context ?: return
        val dialogView = layoutInflater.inflate(R.layout.dialog_custom_delete_confirm, null)

        val tvTitle = dialogView.findViewById<TextView>(R.id.tv_dialog_delete_title)
        val tvDesc = dialogView.findViewById<TextView>(R.id.tv_dialog_delete_desc)
        val tvPreviewCat = dialogView.findViewById<TextView>(R.id.tv_preview_cat)
        val tvPreviewTime = dialogView.findViewById<TextView>(R.id.tv_preview_time)
        val tvPreviewSummary = dialogView.findViewById<TextView>(R.id.tv_preview_summary)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btn_dialog_delete_cancel)
        val btnConfirm = dialogView.findViewById<TextView>(R.id.btn_dialog_delete_confirm)

        tvTitle.text = getString(R.string.dialog_delete_sample_title)
        tvDesc.text = getString(
            R.string.dialog_delete_sample_desc,
            String.format(Locale.getDefault(), "%.0f mAh", candidate.fccObservedMah)
        )

        // 配置待删除样本卡片预览信息
        val isCharge = candidate.sessionType == CandidateSessionType.CHARGE
        tvPreviewCat.text = if (isCharge) {
            getString(R.string.capacity_session_badge_charge)
        } else {
            getString(R.string.capacity_session_badge_discharge)
        }
        tvPreviewCat.setTextColor(
            if (isCharge) Color.parseColor("#10b981") else Color.parseColor("#38bdf8")
        )
        tvPreviewTime.text = formatTimeRange(candidate.startTimestamp, candidate.endTimestamp)

        val fccStr = String.format(Locale.getDefault(), "%.0f mAh", candidate.fccObservedMah)
        val socStr = getString(
            R.string.capacity_session_soc_span_format,
            candidate.startSoc,
            candidate.endSoc,
            candidate.socSpanPercent
        )
        tvPreviewSummary.text = "🔋 $fccStr   •   $socStr"

        val dialog = AlertDialog.Builder(ctx)
            .setView(dialogView)
            .create()

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }
        btnConfirm.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    CapacityCandidateDbHelper.getInstance(ctx).deleteCandidate(candidate.id)
                }
                BatteryCapacityManager.getInstance(ctx).refreshEstimateAsync()
                loadData()
                dialog.dismiss()
            }
        }

        dialog.show()
        DialogWindowHelper.applyAdaptiveDialogWindowStyle(
            dialog = dialog,
            dialogView = dialogView,
            widthRatio = 0.92,
            maxHeightRatio = 0.90f
        )
    }

    /**
     * 弹出准入条件快速设置选择框，采用与其他系统弹窗一致的圆角分层卡片风格，
     * 直观展示并支持一键切换跨度门限、时效条数、起始放电政策与数据纯度。
     */
    private fun showConditionsMenuDialog() {
        val ctx = context ?: return
        val dialogView = layoutInflater.inflate(R.layout.dialog_capacity_conditions_menu, null)

        val startDesc = when (currentStartCondition.policy) {
            StartDischargePolicy.START_FROM_100_FULL -> "满电 ≥${currentStartCondition.minStartSocPercent}%"
            StartDischargePolicy.ANY_SOC -> "任意起步"
        }
        val countDesc = if (currentCountLimit > 0) "最新 $currentCountLimit 条" else "全部数据"
        val purityDesc = "实测纯度 ≥${currentPurityCondition.minMeasuredCoveragePercent.toInt()}%"
        val thresholdDesc = if (currentThreshold == 100) "100%" else "> ${currentThreshold}%"

        val tvThresholdVal = dialogView.findViewById<TextView>(R.id.tv_condition_threshold_value)
        val tvCountVal = dialogView.findViewById<TextView>(R.id.tv_condition_count_value)
        val tvStartVal = dialogView.findViewById<TextView>(R.id.tv_condition_start_value)
        val tvPurityVal = dialogView.findViewById<TextView>(R.id.tv_condition_purity_value)

        tvThresholdVal.text = thresholdDesc
        tvCountVal.text = countDesc
        tvStartVal.text = startDesc
        tvPurityVal.text = purityDesc

        applyConditionBadgeStyle(tvThresholdVal, Color.parseColor("#10B981"))
        applyConditionBadgeStyle(tvCountVal, Color.parseColor("#3B82F6"))
        applyConditionBadgeStyle(tvStartVal, Color.parseColor("#F59E0B"))
        applyConditionBadgeStyle(tvPurityVal, Color.parseColor("#8B5CF6"))

        val dialog = AlertDialog.Builder(ctx)
            .setView(dialogView)
            .create()

        dialogView.findViewById<View>(R.id.item_condition_threshold).setOnClickListener {
            dialog.dismiss()
            val picker = CapacityThresholdPickerBottomSheetDialog()
            picker.setOnThresholdSelectedListener {
                loadData()
            }
            picker.show(parentFragmentManager, "CapacityThresholdPickerBottomSheetDialog")
        }

        dialogView.findViewById<View>(R.id.item_condition_count).setOnClickListener {
            dialog.dismiss()
            val picker = CapacityCountPickerBottomSheetDialog()
            picker.setOnCountSelectedListener {
                loadData()
            }
            picker.show(parentFragmentManager, "CapacityCountPickerBottomSheetDialog")
        }

        dialogView.findViewById<View>(R.id.item_condition_start_policy).setOnClickListener {
            dialog.dismiss()
            val picker = StartDischargePolicyBottomSheetDialog()
            picker.setOnPolicySelectedListener {
                loadData()
            }
            picker.show(parentFragmentManager, "StartDischargePolicyBottomSheetDialog")
        }

        dialogView.findViewById<View>(R.id.item_condition_purity).setOnClickListener {
            dialog.dismiss()
            val picker = DataPurityPickerBottomSheetDialog()
            picker.setOnPuritySelectedListener {
                loadData()
            }
            picker.show(parentFragmentManager, "DataPurityPickerBottomSheetDialog")
        }

        dialogView.findViewById<View>(R.id.btn_conditions_cancel).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        DialogWindowHelper.applyAdaptiveDialogWindowStyle(
            dialog = dialog,
            dialogView = dialogView,
            widthRatio = 0.92,
            maxHeightRatio = 0.90f
        )
    }

    /**
     * 为学习条件状态值徽章配置半透明胶囊圆角背景。
     *
     * @param view 目标状态值文本视图 [TextView]
     * @param accentColor 状态主题高亮色值
     */
    private fun applyConditionBadgeStyle(view: TextView, accentColor: Int) {
        val density = view.resources.displayMetrics.density
        val bgAlphaColor = Color.argb(30, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        val drawable = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(bgAlphaColor)
            cornerRadius = 8f * density
        }
        view.background = drawable
    }

    /**
     * ViewPager2 承载全部、充电、放电三大分类样本列表的分页适配器。
     *
     * @property formatTimeRange 起止时间格式化委托函数
     * @property onDeleteClicked 样本删除按钮点击事件回调
     */
    private class SessionPageAdapter(
        private val formatTimeRange: (Long, Long) -> String,
        private val onDeleteClicked: (CapacityCandidate) -> Unit
    ) : RecyclerView.Adapter<SessionPageViewHolder>() {

        private val candidateAdapters = Array(3) {
            CandidateSessionAdapter(formatTimeRange, onDeleteClicked)
        }
        private val pageDataList = Array<List<CapacityCandidate>>(3) { emptyList() }
        private val attachedHolders = mutableMapOf<Int, SessionPageViewHolder>()
        private var currentThreshold: Int = 15

        /**
         * 更新所有页面的数据并触发子列表渲染。
         */
        fun updateAllPages(
            allData: List<CapacityCandidate>,
            chargeData: List<CapacityCandidate>,
            dischargeData: List<CapacityCandidate>,
            threshold: Int,
            includedIds: Set<Long>,
            countLimit: Int,
            startCondition: StartDischargeCondition = StartDischargeCondition(),
            purityCondition: DataPurityCondition = DataPurityCondition()
        ) {
            currentThreshold = threshold
            pageDataList[0] = allData
            pageDataList[1] = chargeData
            pageDataList[2] = dischargeData

            for (i in 0..2) {
                candidateAdapters[i].submitList(pageDataList[i], threshold, includedIds, countLimit, startCondition, purityCondition)
                attachedHolders[i]?.let { holder ->
                    bindPageHolder(holder, i)
                }
            }
        }

        /**
         * 创建分页 ViewHolder 容器。
         *
         * @param parent 父容器视图组 [ViewGroup]
         * @param viewType 视图类型编号
         * @return 初始化后的 [SessionPageViewHolder] 实例
         */
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionPageViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_capacity_session_page, parent, false)
            return SessionPageViewHolder(view)
        }

        /**
         * 绑定对应页签的 RecyclerView 与空视图状态。
         *
         * @param holder 页面 ViewHolder [SessionPageViewHolder]
         * @param position 页面下标索引（0：全部，1：充电，2：放电）
         */
        override fun onBindViewHolder(holder: SessionPageViewHolder, position: Int) {
            attachedHolders[position] = holder
            if (holder.rv.adapter != candidateAdapters[position]) {
                holder.rv.layoutManager = LinearLayoutManager(holder.itemView.context)
                holder.rv.adapter = candidateAdapters[position]
            }
            bindPageHolder(holder, position)
        }

        /**
         * 当页面 ViewHolder 从窗口解绑时移除持有，防止内存泄漏。
         *
         * @param holder 页面 ViewHolder [SessionPageViewHolder]
         */
        override fun onViewRecycled(holder: SessionPageViewHolder) {
            super.onViewRecycled(holder)
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                attachedHolders.remove(pos)
            }
        }

        /**
         * 刷新指定 ViewHolder 的空状态与列表可见性。
         *
         * @param holder 页面 ViewHolder [SessionPageViewHolder]
         * @param position 页面下标索引
         */
        private fun bindPageHolder(holder: SessionPageViewHolder, position: Int) {
            val data = pageDataList[position]
            if (data.isEmpty()) {
                holder.layoutEmpty.visibility = View.VISIBLE
                holder.rv.visibility = View.GONE
            } else {
                holder.layoutEmpty.visibility = View.GONE
                holder.rv.visibility = View.VISIBLE
            }
        }

        /**
         * 获取 ViewPager2 总页签数量。
         *
         * @return 页面总数（固定为 3）
         */
        override fun getItemCount(): Int = 3
    }

    /**
     * 单个分类页面的 ViewHolder 容器。
     *
     * @param view 分页根视图 [View]
     */
    private class SessionPageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val rv: RecyclerView = view.findViewById(R.id.rv_page_sessions)
        val layoutEmpty: View = view.findViewById(R.id.layout_page_empty)
    }

    /**
     * 候选容量会话 RecyclerView 列表适配器。
     *
     * @property formatTimeRange 时间范围格式化函数
     * @property onDeleteClicked 删除按钮点击回调监听器
     */
    private class CandidateSessionAdapter(
        private val formatTimeRange: (Long, Long) -> String,
        private val onDeleteClicked: (CapacityCandidate) -> Unit
    ) : RecyclerView.Adapter<CandidateSessionViewHolder>() {

        private var items: List<CapacityCandidate> = emptyList()
        private var threshold: Int = 15
        private var includedCandidateIds: Set<Long> = emptySet()
        private var countLimit: Int = 0
        private var startCondition: StartDischargeCondition = StartDischargeCondition()
        private var purityCondition: DataPurityCondition = DataPurityCondition()

        /**
         * 提交最新的候选样本列表并更新当前电量门限与有效时效名单。
         *
         * @param list 待展示的候选样本列表集合
         * @param currentThreshold 当前生效的电量跨度最低门限百分比
         * @param includedIds 当前模型纳入计算的样本 ID 集合
         * @param limit 当前生效的有效条数窗口限制
         * @param currentStartCondition 当前放电起始政策条件
         * @param currentPurityCondition 当前放电数据纯度条件
         */
        fun submitList(
            list: List<CapacityCandidate>,
            currentThreshold: Int,
            includedIds: Set<Long> = emptySet(),
            limit: Int = 0,
            currentStartCondition: StartDischargeCondition = StartDischargeCondition(),
            currentPurityCondition: DataPurityCondition = DataPurityCondition()
        ) {
            items = list
            threshold = currentThreshold
            includedCandidateIds = includedIds
            countLimit = limit
            startCondition = currentStartCondition
            purityCondition = currentPurityCondition
            notifyDataSetChanged()
        }

        /**
         * 创建候选会话列表条目的 ViewHolder 实例。
         *
         * @param parent 列表条目的父级容器 [ViewGroup]
         * @param viewType 视图类型整型标识
         * @return 初始化后的 [CandidateSessionViewHolder] 实例
         */
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CandidateSessionViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_capacity_candidate_session, parent, false)
            return CandidateSessionViewHolder(view)
        }

        /**
         * 将指定索引位置的候选样本数据绑定至 ViewHolder 对应视图元素。
         *
         * @param holder 列表条目视图持有者 [CandidateSessionViewHolder]
         * @param position 列表条目索引位置
         */
        override fun onBindViewHolder(holder: CandidateSessionViewHolder, position: Int) {
            val item = items[position]
            val isCharge = item.sessionType == CandidateSessionType.CHARGE

            // 1. 类型徽章
            if (isCharge) {
                holder.tvTypeBadge.text = holder.itemView.context.getString(R.string.capacity_session_badge_charge)
                holder.tvTypeBadge.setTextColor(Color.parseColor("#10b981"))
            } else {
                holder.tvTypeBadge.text = holder.itemView.context.getString(R.string.capacity_session_badge_discharge)
                holder.tvTypeBadge.setTextColor(Color.parseColor("#38bdf8"))
            }

            // 2. 时间（同天省略重复日期，精简且不折行）
            holder.tvTime.text = formatTimeRange(item.startTimestamp, item.endTimestamp)

            // 3. 状态标签与排除说明
            val meetsThreshold = item.socSpanPercent >= threshold
            val meetsStartPolicy = item.sessionType != CandidateSessionType.DISCHARGE || startCondition.isSatisfied(item.startSoc)
            val meetsPurity = item.sessionType != CandidateSessionType.DISCHARGE || (item.qualityScore * 100.0 >= purityCondition.minMeasuredCoveragePercent)
            val isWithinWindow = includedCandidateIds.contains(item.id)
            when {
                item.isExcluded -> {
                    holder.tvStatus.text = holder.itemView.context.getString(R.string.capacity_session_status_outlier)
                    holder.tvStatus.setTextColor(Color.parseColor("#f59e0b"))
                    holder.tvReason.visibility = View.VISIBLE
                    holder.tvReason.text = if (item.exclusionReason.isNotBlank()) item.exclusionReason else holder.itemView.context.getString(R.string.capacity_session_status_outlier)
                }
                !meetsThreshold -> {
                    holder.tvStatus.text = holder.itemView.context.getString(R.string.capacity_session_status_below_threshold)
                    holder.tvStatus.setTextColor(Color.parseColor("#64748b"))
                    holder.tvReason.visibility = View.VISIBLE
                    holder.tvReason.text = "${item.socSpanPercent}% < $threshold%"
                }
                !meetsStartPolicy -> {
                    holder.tvStatus.text = holder.itemView.context.getString(R.string.capacity_session_status_below_threshold)
                    holder.tvStatus.setTextColor(Color.parseColor("#64748b"))
                    holder.tvReason.visibility = View.VISIBLE
                    holder.tvReason.text = "起始电量未达政策要求 (${item.startSoc}% < ${startCondition.minStartSocPercent}%)"
                }
                !meetsPurity -> {
                    holder.tvStatus.text = holder.itemView.context.getString(R.string.capacity_session_status_below_threshold)
                    holder.tvStatus.setTextColor(Color.parseColor("#64748b"))
                    holder.tvReason.visibility = View.VISIBLE
                    holder.tvReason.text = String.format(Locale.US, "数据纯度不足 (%.1f%% < %.0f%%)", item.qualityScore * 100.0, purityCondition.minMeasuredCoveragePercent)
                }
                countLimit > 0 && !isWithinWindow -> {
                    holder.tvStatus.text = holder.itemView.context.getString(R.string.capacity_session_status_exceeded_window)
                    holder.tvStatus.setTextColor(Color.parseColor("#64748b"))
                    holder.tvReason.visibility = View.VISIBLE
                    holder.tvReason.text = holder.itemView.context.getString(R.string.capacity_session_reason_exceeded_window, countLimit)
                }
                else -> {
                    holder.tvStatus.text = holder.itemView.context.getString(R.string.capacity_session_status_included)
                    holder.tvStatus.setTextColor(Color.parseColor("#10b981"))
                    holder.tvReason.visibility = View.GONE
                }
            }

            // 4. 电量跨度
            holder.tvSoc.text = holder.itemView.context.getString(R.string.capacity_session_soc_span_format, item.startSoc, item.endSoc, item.socSpanPercent)

            // 5. 推算 FCC
            holder.tvFcc.text = String.format(Locale.getDefault(), "%.0f mAh", item.fccObservedMah)

            // 6. 测定净电量
            val netPrefix = if (isCharge) holder.itemView.context.getString(R.string.capacity_session_net_charge) else holder.itemView.context.getString(R.string.capacity_session_net_discharge)
            holder.tvNetCharge.text = "$netPrefix: ${String.format(Locale.getDefault(), "%.0f mAh", item.netBatteryChargeMah)}"

            // 7. 时长
            val durMin = (item.durationMs / 60000L).coerceAtLeast(1L)
            holder.tvDuration.text = if (durMin >= 60) "${durMin / 60}h ${durMin % 60}m" else "${durMin}m"

            // 8. 均温
            holder.tvTemp.text = holder.itemView.context.getString(R.string.capacity_session_avg_temp_format, item.avgTemperatureCelsius)

            // 9. 删除按钮
            holder.btnDelete.setOnClickListener {
                onDeleteClicked(item)
            }
        }

        /**
         * 获取当前列表展示的数据项总数量。
         *
         * @return 候选样本条目数量
         */
        override fun getItemCount(): Int = items.size
    }

    /**
     * 单个候选会话条目的视图容器 ViewHolder。
     *
     * @param view 单个条目的根视图 [View]
     */
    private class CandidateSessionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTypeBadge: TextView = view.findViewById(R.id.tv_item_session_type_badge)
        val tvTime: TextView = view.findViewById(R.id.tv_item_session_time)
        val tvStatus: TextView = view.findViewById(R.id.tv_item_session_status)
        val btnDelete: ImageView = view.findViewById(R.id.btn_item_session_delete)
        val tvSoc: TextView = view.findViewById(R.id.tv_item_session_soc)
        val tvFcc: TextView = view.findViewById(R.id.tv_item_session_fcc)
        val tvNetCharge: TextView = view.findViewById(R.id.tv_item_session_net_charge)
        val tvDuration: TextView = view.findViewById(R.id.tv_item_session_duration)
        val tvTemp: TextView = view.findViewById(R.id.tv_item_session_temp)
        val tvReason: TextView = view.findViewById(R.id.tv_item_session_reason)
    }

    /**
     * 自学习会话样本明细弹窗伴生对象。
     * 维护弹窗相关的持久化键值常量。
     */
    companion object {
        /**
         * 记忆“仅看达标”筛选开关持久化状态的偏好设置键名。
         */
        const val PREF_KEY_FILTER_MEETS_THRESHOLD = "pref_capacity_session_filter_meets_threshold"
    }
}
