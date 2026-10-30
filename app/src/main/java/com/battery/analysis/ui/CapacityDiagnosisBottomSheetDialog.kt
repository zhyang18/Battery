package com.battery.analysis.ui

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

    /**
     * 弹出与科学诊断主界面设计风格一致的自学习有效数据门限选择底部抽屉弹窗。
     * 从屏幕底部向上平滑滑入，采用与主诊断弹窗完全一致的现代化暗黑卡片风格，确定后即刻更新界面显示。
     *
     * @param rootView 触发弹窗的根视图对象，用于定位并局部刷新门限指示器文本
     */
    private fun showThresholdPickerDialog(rootView: View) {
        val pickerDialog = CapacityThresholdPickerBottomSheetDialog()
        pickerDialog.setOnThresholdSelectedListener { selectedPercent ->
            val labelText = if (selectedPercent == 100) "100% ▾" else "> ${selectedPercent}% ▾"
            rootView.findViewById<TextView>(R.id.tv_diag_threshold_selector)?.text = labelText
        }
        pickerDialog.show(parentFragmentManager, "CapacityThresholdPickerBottomSheetDialog")
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
            tvMaturityBadge?.text = getString(R.string.capacity_maturity_pending)
            tvMaturityBadge?.setTextColor(Color.parseColor("#64748b"))
            tvMaturityBadge?.let { setRoundedBadgeBackground(it, Color.parseColor("#64748b"), 6f) }
            tvSessionCounts?.text = getString(R.string.capacity_diag_zero_times)
            tvSocSpan?.text = "0%"
            tvDispersion?.text = "--"
            tvConfidenceInterval?.text = "--"
            tvConflictD?.text = getString(R.string.capacity_diag_accumulating)
            return
        }

        // 1. 板块一：容量与健康度全景
        tvRemainingCharge?.text = result.getFormattedRemainingCharge()
        tvRemainingEnergy?.text = result.getFormattedRemainingEnergy()
        tvFcc?.text = result.getFormattedFcc()
        tvFccSource?.text = getString(R.string.capacity_diag_source_format, result.sourceTier.getDisplayName(requireContext()))
        tvDesignCap?.text = result.designCapacityMah?.let {
            String.format(Locale.getDefault(), "%.0f mAh", it)
        } ?: getString(R.string.capacity_source_unobtained)
        tvSoh?.text = result.getFormattedSoh()

        // 2. 板块二：自学习样本档案
        val badgeColor = when (result.maturityLevel) {
            MaturityLevel.STABLE_TRACKING,
            MaturityLevel.CONVERGED -> Color.parseColor("#10b981")
            MaturityLevel.INITIAL_OBSERVATION -> Color.parseColor("#f59e0b")
            MaturityLevel.PENDING -> Color.parseColor("#64748b")
        }
        tvMaturityBadge?.text = result.maturityLevel.getDisplayName(requireContext())
        tvMaturityBadge?.setTextColor(badgeColor)
        tvMaturityBadge?.let { setRoundedBadgeBackground(it, badgeColor, 6f) }
        tvSessionCounts?.text = getString(R.string.capacity_diag_counts_format, result.chargeSessionCount, result.dischargeSessionCount)
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
                String.format(Locale.getDefault(), getString(R.string.capacity_diag_consistency_good), it)
            } else {
                String.format(Locale.getDefault(), getString(R.string.capacity_diag_consistency_diff), it)
            }
        } ?: getString(R.string.capacity_diag_no_conflict)
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
