package com.battery.analysis.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.battery.analysis.R
import com.battery.analysis.domain.capacity.estimator.SocEnergyCurveLearner
import com.battery.analysis.domain.capacity.model.Confidence
import com.battery.analysis.domain.capacity.model.FccChannelEstimate
import com.battery.analysis.domain.capacity.model.FccUnifiedEstimate
import com.battery.analysis.domain.capacity.model.LearningMaturity
import com.battery.analysis.domain.capacity.model.SocEnergyBinInfo
import com.battery.analysis.manager.BatteryCapacityManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * FCC 充放电双向容量学习与 SOH 估算技术方案详情底部弹窗控制器 (FccCapacityLearningBottomSheetDialog)。
 *
 * 核心功能板块（严格对照技术规范第 17 节呈现）：
 * 1. 容量与健康度总览：FCC_Q (mAh), SOH_Q (可用边界), FCC_E (Wh), SOH_E, 成熟度徽章, 可信度与设计参考对比；
 * 2. 放电学习主通道：FCC_Q,dis 估计值与不确定度、样本数、累计有效 SOC 跨度、电荷加权平均温度；
 * 3. 充电学习辅助通道：表观 FCC_Q,chg 估计值、有效样本数、充满截断质量评分徽章、库仑效率 η_Q 状态；
 * 4. 双向鲁棒融合与冲突状态：双通道相对差异率 D、冲突判定横幅（D > 2σ 触发 DISAGREEMENT，不强行平均）、同源芯片链路相关性说明；
 * 5. SOC 20 段能量曲线：20 段 5% SOC 实测能量分箱与端电压分布、覆盖率统计、未测区间如实标明待采集（零假数据原则）；
 * 6. 容量边界与工况声明：系统可用边界与电芯化学全容量的物理区别，低温极化与大电流 IR 压降影响提示。
 */
class FccCapacityLearningBottomSheetDialog : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_fcc_capacity_learning, container, false)
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return

        // 1. 消除系统直角外底
        bottomSheet.background = null
        bottomSheet.setBackgroundColor(Color.TRANSPARENT)

        // 2. 限制最大高度为屏幕 80%
        val screenHeight = resources.displayMetrics.heightPixels
        val maxHeight = (screenHeight * 0.80).toInt()
        val behavior = BottomSheetBehavior.from(bottomSheet)
        behavior.maxHeight = maxHeight
        behavior.peekHeight = maxHeight
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

        // 3. 点击外部遮罩或返回键平滑向下退出
        dialog.setCanceledOnTouchOutside(false)
        val touchOutside = dialog.findViewById<View>(com.google.android.material.R.id.touch_outside)
        touchOutside?.setOnClickListener {
            dismissWithSlideDownAnimation()
        }

        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                dismissWithSlideDownAnimation()
                true
            } else {
                false
            }
        }
    }

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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<ImageView>(R.id.btn_close_fcc)?.setOnClickListener {
            dismissWithSlideDownAnimation()
        }
        view.findViewById<MaterialButton>(R.id.btn_fcc_confirm)?.setOnClickListener {
            dismissWithSlideDownAnimation()
        }

        // 立即渲染当前已有状态并触发一次数据刷新
        val manager = BatteryCapacityManager.getInstance(requireContext())
        bindUnifiedEstimate(view, manager.unifiedCapacityState.value, manager)
        manager.refreshEstimateAsync()

        observeCapacityData(view)
    }

    private fun observeCapacityData(root: View) {
        val manager = BatteryCapacityManager.getInstance(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 观察双向融合统一估计状态
                launch {
                    manager.unifiedCapacityState.collectLatest { unified ->
                        bindUnifiedEstimate(root, unified, manager)
                    }
                }
            }
        }
    }

    private fun bindUnifiedEstimate(
        root: View,
        estimate: FccUnifiedEstimate?,
        manager: BatteryCapacityManager
    ) {
        val context = root.context ?: return
        val legacyState = manager.capacityState.value
        val designMah = estimate?.designCapacityMah ?: legacyState?.designCapacityMah ?: 0.0
        val designWh = estimate?.designEnergyWh ?: (designMah * 3.85 / 1000.0)

        // ======================== 板块一：容量与健康度总览 ========================
        val tvMaturityBadge = root.findViewById<TextView>(R.id.tv_fcc_maturity_badge)
        val tvFccQVal = root.findViewById<TextView>(R.id.tv_fcc_q_val)
        val tvSohQVal = root.findViewById<TextView>(R.id.tv_soh_q_val)
        val tvFccEVal = root.findViewById<TextView>(R.id.tv_fcc_e_val)
        val tvSohEVal = root.findViewById<TextView>(R.id.tv_soh_e_val)
        val tvDesignInfo = root.findViewById<TextView>(R.id.tv_fcc_design_info)

        val maturity = estimate?.maturity ?: LearningMaturity.PENDING
        tvMaturityBadge?.text = maturity.name
        when (maturity) {
            LearningMaturity.STABLE, LearningMaturity.CONVERGED -> {
                tvMaturityBadge?.setTextColor(Color.parseColor("#10b981"))
                tvMaturityBadge?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2010b981"))
            }
            LearningMaturity.INITIAL -> {
                tvMaturityBadge?.setTextColor(Color.parseColor("#3b82f6"))
                tvMaturityBadge?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#203b82f6"))
            }
            LearningMaturity.DISAGREEMENT -> {
                tvMaturityBadge?.setTextColor(Color.parseColor("#ef4444"))
                tvMaturityBadge?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#20ef4444"))
            }
            LearningMaturity.PENDING -> {
                tvMaturityBadge?.setTextColor(Color.parseColor("#64748b"))
                tvMaturityBadge?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2064748b"))
            }
        }

        val fccQ = estimate?.fccQMah
        val uncQ = estimate?.fccQUncertaintyMah
        if (fccQ != null && fccQ > 0) {
            val uncertaintyStr = if (uncQ != null && uncQ > 0) {
                String.format(Locale.US, " (±%.0f mAh)", uncQ)
            } else ""
            tvFccQVal?.text = String.format(Locale.US, "%,.0f mAh%s", fccQ, uncertaintyStr)
        } else {
            tvFccQVal?.text = "-- mAh"
        }

        if (estimate?.sohQPercent != null) {
            tvSohQVal?.text = String.format(Locale.US, "SOH_Q: %.1f%%", estimate.sohQPercent)
            tvSohQVal?.setTextColor(Color.parseColor("#10b981"))
        } else {
            tvSohQVal?.text = "SOH_Q: --"
            tvSohQVal?.setTextColor(Color.parseColor("#64748b"))
        }

        val fccE = estimate?.fccEWh
        val uncE = estimate?.fccEUncertaintyWh
        if (fccE != null && fccE > 0) {
            val uncEStr = if (uncE != null && uncE > 0) {
                String.format(Locale.US, " (±%.2f Wh)", uncE)
            } else ""
            tvFccEVal?.text = String.format(Locale.US, "%.2f Wh%s", fccE, uncEStr)
        } else {
            tvFccEVal?.text = "-- Wh"
        }

        if (estimate?.sohEPercent != null) {
            tvSohEVal?.text = String.format(Locale.US, "SOH_E: %.1f%%", estimate.sohEPercent)
            tvSohEVal?.setTextColor(Color.parseColor("#3b82f6"))
        } else {
            tvSohEVal?.text = "SOH_E: --"
            tvSohEVal?.setTextColor(Color.parseColor("#64748b"))
        }

        val conf = estimate?.confidence ?: Confidence.LOW
        tvDesignInfo?.text = if (designMah > 0.0) {
            String.format(
                Locale.US,
                "设计参考: %,.0f mAh · %.1f Wh | 可信度: %s",
                designMah,
                designWh,
                conf.name
            )
        } else {
            String.format(Locale.US, "设计参考: -- mAh · -- Wh | 可信度: %s", conf.name)
        }

        // ======================== 板块二：放电学习主通道 ========================
        val tvDischargeFccVal = root.findViewById<TextView>(R.id.tv_discharge_fcc_val)
        val tvDischargeSamplesSpan = root.findViewById<TextView>(R.id.tv_discharge_samples_span)
        val tvDischargeTempVal = root.findViewById<TextView>(R.id.tv_discharge_temp_val)

        val disEst = estimate?.dischargeChannel
        if (disEst != null && disEst.fccQMah != null && disEst.fccQMah > 0) {
            val unc = if (disEst.uncertaintyMah != null && disEst.uncertaintyMah > 0) {
                String.format(Locale.US, " (±%.0f mAh)", disEst.uncertaintyMah)
            } else ""
            tvDischargeFccVal?.text = String.format(Locale.US, "%,.0f mAh%s", disEst.fccQMah, unc)
            tvDischargeSamplesSpan?.text = String.format(
                Locale.US,
                "%d 条样本 / %.0f%% 跨度",
                disEst.sampleCount,
                disEst.totalSocSpan * 100.0
            )
            val avgTemp = disEst.avgTemperatureC
            tvDischargeTempVal?.text = if (avgTemp != null && avgTemp > 0) {
                String.format(Locale.US, "%.1f °C", avgTemp)
            } else {
                "-- °C"
            }
        } else {
            tvDischargeFccVal?.text = "-- mAh (样本采集中)"
            tvDischargeSamplesSpan?.text = "0 条样本 / 0% 跨度"
            tvDischargeTempVal?.text = "-- °C"
        }

        // ======================== 板块三：充电学习辅助通道 ========================
        val tvChargeFccVal = root.findViewById<TextView>(R.id.tv_charge_fcc_val)
        val tvChargeSamplesSpan = root.findViewById<TextView>(R.id.tv_charge_samples_span)
        val tvChargeQualityEta = root.findViewById<TextView>(R.id.tv_charge_quality_eta)

        val chgEst = estimate?.chargeChannel
        if (chgEst != null && chgEst.fccQMah != null && chgEst.fccQMah > 0) {
            val calTag = if (chgEst.apparentOnly) "(未校准)" else "(已校准)"
            tvChargeFccVal?.text = String.format(Locale.US, "%,.0f mAh %s", chgEst.fccQMah, calTag)
            tvChargeSamplesSpan?.text = String.format(
                Locale.US,
                "%d 条样本 / %.0f%% 跨度",
                chgEst.sampleCount,
                chgEst.totalSocSpan * 100.0
            )
            val epStr = chgEst.endpointQuality?.name ?: "UNKNOWN"
            tvChargeQualityEta?.text = "$epStr · η_Q待标定"
        } else {
            tvChargeFccVal?.text = "-- mAh (未校准)"
            tvChargeSamplesSpan?.text = "0 条样本 / 0% 跨度"
            tvChargeQualityEta?.text = "-- · η_Q待标定"
        }

        // ======================== 板块四：双向鲁棒融合与冲突状态 ========================
        val tvChannelDiffVal = root.findViewById<TextView>(R.id.tv_channel_diff_val)
        val tvChannelConflictMsg = root.findViewById<TextView>(R.id.tv_channel_conflict_msg)

        val diffPercent = estimate?.channelConflictPercent
        val isDisagreement = estimate?.maturity == LearningMaturity.DISAGREEMENT

        if (diffPercent != null) {
            val statusTag = if (isDisagreement) "存在分歧" else "自洽融合"
            tvChannelDiffVal?.text = String.format(Locale.US, "%.2f%% (%s)", diffPercent, statusTag)
            if (isDisagreement) {
                tvChannelDiffVal?.setTextColor(Color.parseColor("#ef4444"))
                tvChannelConflictMsg?.text = "⚠️ 检测到充放电通道分歧（差异率 > 2σ），已降级仅采信放电主通道"
                tvChannelConflictMsg?.setTextColor(Color.parseColor("#ef4444"))
            } else {
                tvChannelDiffVal?.setTextColor(Color.parseColor("#10b981"))
                tvChannelConflictMsg?.text = "充放两通道数据自洽，已加权鲁棒融合"
                tvChannelConflictMsg?.setTextColor(Color.parseColor("#10b981"))
            }
        } else {
            tvChannelDiffVal?.text = "-- (单通道积累中)"
            tvChannelDiffVal?.setTextColor(Color.parseColor("#64748b"))
            tvChannelConflictMsg?.text = "正在持续采集双向充放电样本，当前以主通道优先计算"
            tvChannelConflictMsg?.setTextColor(ContextCompat.getColor(context, android.R.color.tab_indicator_text))
        }

        // ======================== 板块五：SOC 20 段能量曲线 ========================
        val bins = if (estimate?.energyBins?.isNotEmpty() == true) {
            estimate.energyBins
        } else {
            manager.socEnergyCurveLearner.getEnergyBins()
        }
        val coverage = estimate?.energyCurveCoverage ?: manager.socEnergyCurveLearner.getCoverageRatio()
        bindEnergyCurveSection(root, bins, coverage)
    }

    private fun bindEnergyCurveSection(root: View, bins: List<SocEnergyBinInfo>, coverageRatio: Double) {
        val context = root.context ?: return
        val tvCoverage = root.findViewById<TextView>(R.id.tv_energy_curve_coverage)
        val layoutContainer = root.findViewById<LinearLayout>(R.id.layout_energy_bins_container) ?: return

        val learnedCount = bins.count { it.isLearned }
        val ratio = coverageRatio * 100.0
        tvCoverage?.text = String.format(Locale.US, "覆盖率: %.0f%% (%d/20段)", ratio, learnedCount)

        val inflater = LayoutInflater.from(context)
        val shouldInflate = layoutContainer.childCount != SocEnergyCurveLearner.BIN_COUNT
        if (shouldInflate) {
            layoutContainer.removeAllViews()
        }

        // 从高 SOC 到低 SOC 逆序展示 (100%~95% 往下到 5%~0%)
        for (order in 0 until SocEnergyCurveLearner.BIN_COUNT) {
            val binIdx = (SocEnergyCurveLearner.BIN_COUNT - 1) - order
            val bin = bins.find { it.binIndex == binIdx }

            val itemView = if (shouldInflate) {
                val v = inflater.inflate(R.layout.item_soc_energy_bin, layoutContainer, false)
                layoutContainer.addView(v)
                v
            } else {
                layoutContainer.getChildAt(order)
            }

            val tvLabel = itemView.findViewById<TextView>(R.id.tv_bin_range_label)
            val tvBadge = itemView.findViewById<TextView>(R.id.tv_bin_source_badge)
            val tvSampleCount = itemView.findViewById<TextView>(R.id.tv_bin_sample_count)
            val tvEnergy = itemView.findViewById<TextView>(R.id.tv_bin_energy_val)

            val socStart = binIdx * 5
            val socEnd = (binIdx + 1) * 5
            tvLabel.text = "$socStart% - $socEnd%"

            if (bin != null && bin.isLearned) {
                tvBadge.text = "实测"
                tvBadge.setTextColor(Color.parseColor("#10b981"))
                tvBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2010b981"))

                val voltStr = if (bin.avgVoltageVolts != null) String.format(Locale.US, " · %.2fV", bin.avgVoltageVolts) else ""
                tvSampleCount.text = "${bin.sampleCount}次$voltStr"
                tvSampleCount.setTextColor(Color.parseColor("#64748b"))

                tvEnergy.text = String.format(Locale.US, "%.3f Wh", bin.energyWh)
                tvEnergy.setTextColor(Color.parseColor("#10b981"))
            } else {
                tvBadge.text = "待采集"
                tvBadge.setTextColor(Color.parseColor("#94a3b8"))
                tvBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2094a3b8"))

                tvSampleCount.text = "未覆盖"
                tvSampleCount.setTextColor(Color.parseColor("#94a3b8"))

                tvEnergy.text = "-- Wh"
                tvEnergy.setTextColor(Color.parseColor("#64748b"))
            }
        }
    }
}
