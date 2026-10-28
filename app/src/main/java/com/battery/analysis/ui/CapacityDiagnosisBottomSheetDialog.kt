package com.battery.analysis.ui

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.battery.analysis.R
import com.battery.analysis.manager.BatteryCapacityManager
import com.battery.analysis.model.CapacityEstimateResult
import com.battery.analysis.model.MaturityLevel
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 电池容量科学诊断与学习档案底部抽屉弹窗控制器（CapacityDiagnosisBottomSheetDialog）。
 * 负责呈现真实的电荷微积分模型结果、自学习样本档案统计、充放电交叉冲突率 D 及零假数据工况声明。
 */
class CapacityDiagnosisBottomSheetDialog : BottomSheetDialogFragment() {

    /**
     * 创建并加载底部弹窗的根视图。
     *
     * @param inflater 布局填充器，用于将 XML 布局实例化为视图对象
     * @param container 包含弹窗视图的父级视图组，可能为 null
     * @param savedInstanceState 保存的状态数据包，用于界面重建恢复
     * @return 填充完成的根视图实例，可能为 null
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_capacity_scientific_diagnosis, container, false)
    }

    /**
     * 生命周期启动回调，用于配置底部抽屉的高度与行为属性。
     * 1. 彻底清除系统底层容器的默认不透明直角背景，消除下方圆角边缘外露出的深色直角衬底；
     * 2. 将底部弹窗的最大高度约束在屏幕物理高度的 80% 以内，防止内容过多时顶满全屏；
     * 3. 统一点击外部遮罩、按键返回的退出动效为平滑向下下划滑出。
     */
    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return

        // 1. 彻底清除系统底层容器的默认不透明直角背景，消除下方圆角边缘外露出的深色直角衬底
        bottomSheet.background = null
        bottomSheet.setBackgroundColor(Color.TRANSPARENT)

        // 2. 配置高度限制在屏幕 80%
        val screenHeight = resources.displayMetrics.heightPixels
        val maxHeight = (screenHeight * 0.80).toInt()
        val behavior = BottomSheetBehavior.from(bottomSheet)
        behavior.maxHeight = maxHeight
        behavior.peekHeight = maxHeight
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        behavior.skipCollapsed = true
        behavior.isHideable = true

        // 3. 监听状态：滑动到屏幕下方完全隐藏时再解构弹窗
        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                    dismissAllowingStateLoss()
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
            }
        })

        // 4. 接管外部遮罩点击，关闭系统原生 cancel 竞争，统一使用向下滑出取消动画
        dialog.setCanceledOnTouchOutside(false)
        val touchOutside = dialog.findViewById<View>(com.google.android.material.R.id.touch_outside)
        touchOutside?.setOnClickListener {
            dismissWithSlideDownAnimation()
        }

        // 5. 拦截返回键，同样统一使用向下滑出取消动画
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                dismissWithSlideDownAnimation()
                true
            } else {
                false
            }
        }
    }

    /**
     * 以与手势向下滑动完全一致的平滑下落动画退出底部弹窗。
     * 当处于已附加状态时，触发 [BottomSheetBehavior.STATE_HIDDEN]，驱动弹窗顺畅滑出屏幕；
     * 待滑出完成并在回调中安全解构，确保全场景动画质感 100% 统一。
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
     * 视图构建完成后的回调，用于初始化交互事件与绑定数据流观察。
     *
     * @param view 已创建的根视图实例
     * @param savedInstanceState 保存的状态数据包，可能为 null
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 绑定关闭按钮与确认按钮：统一触发平滑向下滑出退出动效
        view.findViewById<ImageView>(R.id.btn_close_diagnosis)?.setOnClickListener {
            dismissWithSlideDownAnimation()
        }
        view.findViewById<MaterialButton>(R.id.btn_diag_confirm)?.setOnClickListener {
            dismissWithSlideDownAnimation()
        }

        // 绑定自学习会话明细列表点击入口
        view.findViewById<View>(R.id.layout_diag_session_counts)?.setOnClickListener {
            val listDialog = CapacitySessionListBottomSheetDialog()
            listDialog.show(parentFragmentManager, "CapacitySessionListBottomSheetDialog")
        }

        // 绑定有效数据电量跨度门限设置项点击事件
        val thresholdClickListener = View.OnClickListener {
            showThresholdPickerDialog(view)
        }
        view.findViewById<View>(R.id.layout_diag_threshold_setting)?.setOnClickListener(thresholdClickListener)
        view.findViewById<View>(R.id.tv_diag_threshold_selector)?.setOnClickListener(thresholdClickListener)

        // 获取并动态观察最新的容量状态结果流，自动刷新诊断视图
        val context = view.context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                BatteryCapacityManager.getInstance(context).capacityState.collect { result ->
                    bindDataToViews(view, result)
                }
            }
        }
    }

    private data class ThresholdOptionItem(
        val percent: Int,
        val valueLabel: String,
        val desc: String
    )

    private val thresholdOptionItems = listOf(
        ThresholdOptionItem(10, "> 10%", "极速建模 · 容纳浅充浅放"),
        ThresholdOptionItem(15, "> 15%", "日常均衡模式"),
        ThresholdOptionItem(20, "> 20%", "推荐 · 精度与收敛最佳平衡"),
        ThresholdOptionItem(25, "> 25%", "常态充放跨度"),
        ThresholdOptionItem(30, "> 30%", "高精度 · 减少量化离散"),
        ThresholdOptionItem(40, "> 40%", "深度自学习"),
        ThresholdOptionItem(50, "> 50%", "半程以上 · 高置信度"),
        ThresholdOptionItem(60, "> 60%", "大跨度充放"),
        ThresholdOptionItem(70, "> 70%", "高纯度样本"),
        ThresholdOptionItem(80, "> 80%", "深度充放 · 极高精度"),
        ThresholdOptionItem(90, "> 90%", "超深度充放循环"),
        ThresholdOptionItem(100, "100%", "完整 0%~100% 深度充放")
    )

    /**
     * 弹出与项目现代暗黑设计语言 100% 协调的美化版有效数据门限选择弹窗。
     * 采用自定义卡片化列表、翡翠绿高亮勾选态与圆角质感按钮，确定后即时保存门限并触发模型实时重新计算。
     */
    private fun showThresholdPickerDialog(rootView: View) {
        val ctx = context ?: return
        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_capacity_threshold_picker, null)
        val dialog = AlertDialog.Builder(ctx)
            .setView(dialogView)
            .create()

        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            val displayMetrics = resources.displayMetrics
            val width = (displayMetrics.widthPixels * 0.90).toInt()
            val maxListHeight = (displayMetrics.heightPixels * 0.46).toInt()
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setGravity(android.view.Gravity.CENTER)

            val rv = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_threshold_options)
            rv.layoutParams.height = maxListHeight
        }

        val manager = BatteryCapacityManager.getInstance(ctx)
        val currentVal = manager.getEffectiveSocSpanThreshold()
        var selectedPercent = currentVal

        val rv = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_threshold_options)
        rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(ctx)
        val optionAdapter = ThresholdOptionAdapter(thresholdOptionItems, selectedPercent) { clickedItem ->
            selectedPercent = clickedItem.percent
        }
        rv.adapter = optionAdapter

        val selectedIdx = thresholdOptionItems.indexOfFirst { it.percent == selectedPercent }
        if (selectedIdx >= 0) {
            rv.scrollToPosition(selectedIdx)
        }

        dialogView.findViewById<View>(R.id.btn_close_threshold_dialog)?.setOnClickListener {
            dialog.dismiss()
        }
        dialogView.findViewById<View>(R.id.btn_cancel_threshold)?.setOnClickListener {
            dialog.dismiss()
        }
        dialogView.findViewById<View>(R.id.btn_confirm_threshold)?.setOnClickListener {
            dialog.dismiss()
            manager.setEffectiveSocSpanThreshold(selectedPercent)
            val labelText = if (selectedPercent == 100) "100% ▾" else "> ${selectedPercent}% ▾"
            rootView.findViewById<TextView>(R.id.tv_diag_threshold_selector)?.text = labelText
            Toast.makeText(ctx, "已设为门限 ${if (selectedPercent == 100) "100%" else "> $selectedPercent%"}，已即时重新计算模型", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    private class ThresholdOptionAdapter(
        private val items: List<ThresholdOptionItem>,
        initialSelected: Int,
        private val onSelected: (ThresholdOptionItem) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<ThresholdOptionViewHolder>() {

        var selectedPercent: Int = initialSelected

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThresholdOptionViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_capacity_threshold_option, parent, false)
            return ThresholdOptionViewHolder(v)
        }

        override fun onBindViewHolder(holder: ThresholdOptionViewHolder, position: Int) {
            val item = items[position]
            val isSelected = item.percent == selectedPercent

            holder.tvValue.text = item.valueLabel
            holder.tvDesc.text = item.desc

            if (isSelected) {
                holder.root.setBackgroundResource(R.drawable.bg_threshold_opt_selected)
                holder.tvValue.setTextColor(Color.parseColor("#10b981"))
                holder.ivCheck.visibility = View.VISIBLE
            } else {
                holder.root.setBackgroundResource(R.drawable.bg_threshold_opt_normal)
                holder.tvValue.setTextColor(Color.parseColor("#f1f5f9"))
                holder.ivCheck.visibility = View.GONE
            }

            holder.root.setOnClickListener {
                selectedPercent = item.percent
                notifyDataSetChanged()
                onSelected(item)
            }
        }

        override fun getItemCount(): Int = items.size
    }

    private class ThresholdOptionViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
        val root: View = view.findViewById(R.id.layout_threshold_item_root)
        val tvValue: TextView = view.findViewById(R.id.tv_threshold_value)
        val tvDesc: TextView = view.findViewById(R.id.tv_threshold_desc)
        val ivCheck: ImageView = view.findViewById(R.id.iv_threshold_check)
    }

    /**
     * 将容量估计全景结果数据绑定渲染至各个子视图。
     * 严格遵守零假数据原则：在数据缺失时如实显示 "--" 或未知，绝不伪造硬编码数据。
     *
     * @param root 弹窗根视图
     * @param result 容量与学习状态结果实体 [CapacityEstimateResult]
     */
    private fun bindDataToViews(root: View, result: CapacityEstimateResult?) {
        val tvRemainingCharge = root.findViewById<TextView>(R.id.tv_diag_remaining_charge)
        val tvRemainingEnergy = root.findViewById<TextView>(R.id.tv_diag_remaining_energy)
        val tvFcc = root.findViewById<TextView>(R.id.tv_diag_fcc)
        val tvFccSource = root.findViewById<TextView>(R.id.tv_diag_fcc_source)
        val tvDesignCap = root.findViewById<TextView>(R.id.tv_diag_design_capacity)
        val tvSoh = root.findViewById<TextView>(R.id.tv_diag_soh)

        val tvMaturityBadge = root.findViewById<TextView>(R.id.tv_diag_maturity_badge)
        val tvSessionCounts = root.findViewById<TextView>(R.id.tv_diag_session_counts)
        val tvSocSpan = root.findViewById<TextView>(R.id.tv_diag_soc_span)
        val tvDispersion = root.findViewById<TextView>(R.id.tv_diag_dispersion)
        val tvConfidenceInterval = root.findViewById<TextView>(R.id.tv_diag_confidence_interval)
        val tvConflictD = root.findViewById<TextView>(R.id.tv_diag_conflict_d)

        val threshold = BatteryCapacityManager.getInstance(root.context).getEffectiveSocSpanThreshold()
        val tvThresholdSelector = root.findViewById<TextView>(R.id.tv_diag_threshold_selector)
        tvThresholdSelector?.text = if (threshold == 100) "100% ▾" else "> ${threshold}% ▾"

        if (result == null) {
            tvRemainingCharge?.text = "-- mAh"
            tvRemainingEnergy?.text = "-- Wh"
            tvFcc?.text = "-- mAh"
            tvDesignCap?.text = "-- mAh"
            tvSoh?.text = "--%"
            tvMaturityBadge?.text = "样本积累中 · 待校准"
            tvMaturityBadge?.setTextColor(Color.parseColor("#64748b"))
            tvMaturityBadge?.let { setRoundedBadgeBackground(it, Color.parseColor("#64748b"), 6f) }
            tvSessionCounts?.text = "0 次"
            tvSocSpan?.text = "0%"
            tvDispersion?.text = "--"
            tvConfidenceInterval?.text = "--"
            tvConflictD?.text = "数据积累中"
            return
        }

        // 1. 板块一：容量与健康度全景
        tvRemainingCharge?.text = result.getFormattedRemainingCharge()
        tvRemainingEnergy?.text = result.getFormattedRemainingEnergy()
        tvFcc?.text = result.getFormattedFcc()
        tvFccSource?.text = "来源: ${result.sourceTier.displayName}"
        tvDesignCap?.text = result.designCapacityMah?.let {
            String.format(Locale.getDefault(), "%.0f mAh", it)
        } ?: "未获取"
        tvSoh?.text = result.getFormattedSoh()

        // 2. 板块二：自学习样本档案
        val badgeColor = when (result.maturityLevel) {
            MaturityLevel.STABLE_TRACKING,
            MaturityLevel.CONVERGED -> Color.parseColor("#10b981")
            MaturityLevel.INITIAL_OBSERVATION -> Color.parseColor("#f59e0b")
            MaturityLevel.PENDING -> Color.parseColor("#64748b")
        }
        tvMaturityBadge?.text = result.maturityLevel.displayName
        tvMaturityBadge?.setTextColor(badgeColor)
        tvMaturityBadge?.let { setRoundedBadgeBackground(it, badgeColor, 6f) }
        tvSessionCounts?.text = "充电 ${result.chargeSessionCount} 次 ｜ 放电 ${result.dischargeSessionCount} 次"
        tvSocSpan?.text = "${result.totalSocSpanCovered}%"
        tvDispersion?.text = result.dispersionCoefficientPercent?.let {
            String.format(Locale.getDefault(), "±%.2f%%", it)
        } ?: "--"
        tvConfidenceInterval?.text = result.confidenceIntervalMah?.let {
            String.format(Locale.getDefault(), "±%.0f mAh", it)
        } ?: "--"

        // 3. 板块三：交叉冲突率 D
        tvConflictD?.text = result.conflictRateDPercent?.let {
            if (it < 10.0) {
                String.format(Locale.getDefault(), "%.2f%% (一致性良好)", it)
            } else {
                String.format(Locale.getDefault(), "%.2f%% (差异偏大 · 观察中)", it)
            }
        } ?: "单方向数据 · 暂无冲突"
    }

    /**
     * 为文本徽章动态配置带圆角的半透明胶囊背景。
     *
     * @param view 目标徽章文本视图
     * @param textColor 徽章前景主题色
     * @param cornerRadiusDp 圆角半径（单位：dp，默认为 6dp）
     */
    private fun setRoundedBadgeBackground(view: TextView, textColor: Int, cornerRadiusDp: Float = 6f) {
        val density = view.resources.displayMetrics.density
        val alphaColor = Color.argb(32, Color.red(textColor), Color.green(textColor), Color.blue(textColor))
        val drawable = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(alphaColor)
            setCornerRadius(cornerRadiusDp * density)
        }
        view.background = drawable
    }
}
