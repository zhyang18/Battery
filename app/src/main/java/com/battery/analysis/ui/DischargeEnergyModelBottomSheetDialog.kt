package com.battery.analysis.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.BatteryManager
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.battery.analysis.R
import com.battery.analysis.domain.estimator.SocEnergyCurveLearner
import com.battery.analysis.domain.estimator.model.EnergyComparisonOverview
import com.battery.analysis.domain.estimator.model.EnergyEstimateSource
import com.battery.analysis.domain.estimator.model.LearnedSocEnergyCurve
import com.battery.analysis.domain.estimator.model.ModelMaturity
import com.battery.analysis.manager.BatteryCapacityManager
import com.battery.analysis.util.BatteryUnitNormalizer
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import java.util.Locale
import androidx.appcompat.app.AlertDialog
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 放电能量模型与 5% SOC 历史放电能量曲线实测档案底部抽屉弹窗控制器（DischargeEnergyModelBottomSheetDialog）。
 *
 * 核心功能与原则：
 * 1. 突破黑盒：呈现基于端电压与电流梯形微积分做功计算的实测剩余能量、硬件原生寄存器直读与传统标压粗算三源对比；
 * 2. 20 段 SOC 能量释放分布：逐一展示 20 个 5% 区间历史放电释放做功（Wh）与样本次数；
 * 3. 严格遵循零假数据原则：未采集的 SOC 区间如实展示“待采集”，绝不合成伪造数据。
 */
class DischargeEnergyModelBottomSheetDialog : BottomSheetDialogFragment() {

    private var refreshDataJob: Job? = null

    /**
     * 创建并加载放电能量模型底部弹窗的根视图。
     *
     * @param inflater 布局填充器
     * @param container 父容器
     * @param savedInstanceState 保存的状态包
     * @return 实例化的根视图，可能为 null
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_discharge_energy_model, container, false)
    }

    /**
     * 配置底部抽屉的最大高度为屏幕 80%，并消除系统直角外衬底。
     */
    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return

        bottomSheet.background = null
        bottomSheet.setBackgroundColor(Color.TRANSPARENT)

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
    }

    /**
     * 视图构建完成后的数据绑定与视图初始化。
     *
     * @param view 弹窗根视图
     * @param savedInstanceState 保存的状态包
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val btnClose = view.findViewById<ImageView>(R.id.btn_close_energy_model)
        val btnConfirm = view.findViewById<MaterialButton>(R.id.btn_energy_model_confirm)
        val closeListener = View.OnClickListener { dismiss() }
        btnClose.setOnClickListener(closeListener)
        btnConfirm.setOnClickListener(closeListener)

        val resetClickListener = View.OnClickListener {
            showResetConfirmationDialog(view)
        }
        view.findViewById<MaterialButton>(R.id.btn_energy_model_reset)?.setOnClickListener(resetClickListener)

        bindEnergyData(view)
    }

    /**
     * 弹出重置放电能量学习模型的警告确认对话框。
     * 用户确认后清空已学习的 20 段区间数据与历史放电样本，并即刻刷新当前弹窗。
     *
     * @param root 弹窗根视图
     */
    private fun showResetConfirmationDialog(root: View) {
        val context = context ?: return
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_custom_delete_confirm, null)

        val tvTitle = dialogView.findViewById<TextView>(R.id.tv_dialog_delete_title)
        val tvDesc = dialogView.findViewById<TextView>(R.id.tv_dialog_delete_desc)
        val tvCat = dialogView.findViewById<TextView>(R.id.tv_preview_cat)
        val tvTime = dialogView.findViewById<TextView>(R.id.tv_preview_time)
        val tvSummary = dialogView.findViewById<TextView>(R.id.tv_preview_summary)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btn_dialog_delete_cancel)
        val btnConfirm = dialogView.findViewById<TextView>(R.id.btn_dialog_delete_confirm)

        tvTitle?.text = getString(R.string.energy_model_reset_title)
        tvDesc?.text = getString(R.string.energy_model_reset_message)

        val learner = SocEnergyCurveLearner.getInstance(context)
        val curve = learner.getLearnedCurve()
        val coveredCount = curve.bins.count { it.hasLearnedData() }

        tvCat?.text = "已学习 $coveredCount / ${LearnedSocEnergyCurve.BIN_COUNT} 段"
        tvTime?.text = "累计 ${curve.totalSampleSessions} 次放电会话"
        val overview = learner.buildComparisonOverview(
            currentSocPercent = 100f,
            nominalVoltageVolts = 3.85f
        )
        val fullWh = overview.fullLearnedEnergyWh ?: curve.estimatedFullUsableEnergyWh
        val fullWhStr = if (fullWh != null && fullWh > 0.0) String.format(Locale.US, "%.2f Wh", fullWh) else "-- Wh"
        tvSummary?.text = "当前满充预测: $fullWhStr"

        btnConfirm?.text = getString(R.string.energy_model_reset_confirm)

        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .create()

        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            val displayMetrics = resources.displayMetrics
            val width = (displayMetrics.widthPixels * 0.90).toInt()
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        btnCancel?.setOnClickListener { dialog.dismiss() }
        btnConfirm?.setOnClickListener {
            dialog.dismiss()
            com.battery.analysis.manager.PowerUsageManager.getInstance(context).resetSocEnergyCurve()
            bindEnergyData(root)
            Toast.makeText(context, R.string.energy_model_reset_toast, Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    /**
     * 绑定实测三源能量对比看板与 20 段 SOC 能量释放明细。
     * 采用主线程轻量级指示器更新与协程后台模型计算异步分离架构，杜绝界面卡顿与掉帧。
     *
     * @param root 弹窗根视图
     */
    private fun bindEnergyData(root: View) {
        val context = context ?: return

        val powerManager = com.battery.analysis.manager.PowerUsageManager.getInstance(context)

        // 1. 立即在主线程更新双重条件选择器文案与弹窗监听器（零延迟）
        val tvThresholdSelector = root.findViewById<TextView>(R.id.tv_discharge_threshold_selector)
        val tvCountSelector = root.findViewById<TextView>(R.id.tv_discharge_count_selector)

        val currentThreshold = powerManager.getDischargeSocSpanThreshold()
        val currentLimit = powerManager.getDischargeSampleCountLimit()

        tvThresholdSelector?.text = if (currentThreshold == 100) "门限: 100% ▾" else "门限: > $currentThreshold% ▾"
        tvCountSelector?.text = if (currentLimit <= 0) "全部数据 ▾" else "最近 $currentLimit 条 ▾"

        tvThresholdSelector?.setOnClickListener {
            val picker = DischargeThresholdPickerBottomSheetDialog()
            picker.setOnThresholdSelectedListener {
                bindEnergyData(root)
            }
            picker.show(childFragmentManager, "DischargeThresholdPicker")
        }

        tvCountSelector?.setOnClickListener {
            val picker = DischargeCountPickerBottomSheetDialog()
            picker.setOnCountSelectedListener {
                bindEnergyData(root)
            }
            picker.show(childFragmentManager, "DischargeCountPicker")
        }

        // 2. 异步在后台 IO 线程调度模型计算与数据重算，彻底杜绝主线程卡顿
        refreshDataJob?.cancel()
        refreshDataJob = viewLifecycleOwner.lifecycleScope.launch {
            val (overview, curve) = withContext(Dispatchers.IO) {
                try {
                    powerManager.evaluateInFlightSocLearning()
                } catch (_: Exception) {}
                val ov = powerManager.recalculateDischargeEnergyModel()
                val learner = SocEnergyCurveLearner.getInstance(context)
                val crv = learner.getLearnedCurve()
                Pair(ov, crv)
            }

            if (!isAdded || view == null) return@launch
            renderEnergyOverviewAndBins(root, overview, curve)
        }
    }

    /**
     * 渲染实测三源能量看板数据与 20 段 SOC 能量释放明细视图。
     *
     * @param root 弹窗根视图
     * @param overview 放电能量模型三源对比概览实体
     * @param curve 20 段 SOC 能量学习曲线实体
     */
    private fun renderEnergyOverviewAndBins(
        root: View,
        overview: EnergyComparisonOverview,
        curve: LearnedSocEnergyCurve
    ) {
        val context = context ?: return

        // 渲染板块一看板数据（纵向每行显示对比）
        val tvCoverageBadge = root.findViewById<TextView>(R.id.tv_model_coverage_badge)
        val tvValLearned = root.findViewById<TextView>(R.id.tv_val_learned_energy)
        val tvLabelLearnedHealth = root.findViewById<TextView>(R.id.tv_label_learned_health)
        val tvValLearnedCoverage = root.findViewById<TextView>(R.id.tv_val_learned_coverage)
        val tvValSessions = root.findViewById<TextView>(R.id.tv_val_model_sessions)
        val tvLabelRemaining = root.findViewById<TextView>(R.id.tv_label_remaining_energy)
        val tvDescRemaining = root.findViewById<TextView>(R.id.tv_desc_remaining_energy)
        val tvValRemaining = root.findViewById<TextView>(R.id.tv_val_remaining_energy)
        val tvValRemainingDesc = root.findViewById<TextView>(R.id.tv_val_remaining_desc)
        val tvLabelNominal = root.findViewById<TextView>(R.id.tv_label_nominal_energy)
        val tvLabelNominalHealth = root.findViewById<TextView>(R.id.tv_label_nominal_health)
        val tvValNominal = root.findViewById<TextView>(R.id.tv_val_nominal_energy)

        // 覆盖率 badge：优先响应参与统计的多会话/在途宏观锚定，其次响应单次纯亮屏实测
        val (badgeText, badgeColorHex) = when {
            overview.macroHistoricalFullWh != null && overview.participatingSessionCount > 0 -> {
                val cov = overview.curveCoveragePercent
                val count = overview.participatingSessionCount
                val disp = overview.historicalDispersionPercent
                when {
                    cov < 0.50f -> Pair(getString(R.string.energy_badge_macro_low_coverage_extrapolated), "#f59e0b")
                    cov < 0.80f -> {
                        if (count <= 1) Pair(getString(R.string.energy_badge_macro_single_ref), "#f59e0b")
                        else Pair(getString(R.string.energy_badge_macro_mid_confidence), "#3b82f6")
                    }
                    else -> {
                        when {
                            count <= 1 -> Pair(getString(R.string.energy_badge_macro_single_deep_ref), "#3b82f6")
                            count == 2 -> Pair(getString(R.string.energy_badge_macro_dual_deep_check), "#3b82f6")
                            disp != null && disp > 8.0 -> Pair(getString(R.string.energy_badge_macro_dispersed), "#8b5cf6")
                            else -> Pair(getString(R.string.energy_badge_macro_historical_anchored), "#10b981")
                        }
                    }
                }
            }
            overview.isScreenOnAnchored -> {
                val drop = overview.screenOnDropPercent ?: 0f
                when {
                    drop >= 80f -> Pair(getString(R.string.energy_badge_screen_on_anchored_deep_ref), "#3b82f6")
                    drop >= 50f -> Pair(getString(R.string.energy_badge_screen_on_anchored), "#3b82f6")
                    else -> Pair(getString(R.string.energy_badge_macro_low_coverage_extrapolated), "#f59e0b")
                }
            }
            else -> {
                if (overview.curveCoveragePercent > 0f) {
                    val coveragePercentStr = String.format(Locale.US, "%.1f%%", overview.curveCoveragePercent * 100f)
                    val maturityText = getModelMaturityLabel(context, curve.maturity)
                    Pair("$coveragePercentStr ($maturityText)", "#3b82f6")
                } else {
                    Pair(getString(R.string.energy_badge_unreliable), "#94a3b8")
                }
            }
        }

        tvCoverageBadge.text = badgeText
        val parsedColor = Color.parseColor(badgeColorHex)
        tvCoverageBadge.setTextColor(parsedColor)
        val alphaColor = Color.argb(32, Color.red(parsedColor), Color.green(parsedColor), Color.blue(parsedColor))
        tvCoverageBadge.backgroundTintList = ColorStateList.valueOf(alphaColor)

        // 行1：实测学习满充预测（三层混合满充估算值 + 覆盖血统 + 放电周期/分段样本状态 + 能量转化效率）
        val hybridFull = overview.hybridFullEstimate ?: overview.hybridEstimate
        val fullLearnedWh = overview.fullLearnedEnergyWh ?: hybridFull?.remainingEnergyWh ?: overview.learnedRemainingEnergyWh
        val tvHintLearned = root.findViewById<TextView>(R.id.tv_hint_learned_energy)
        if (hybridFull != null && fullLearnedWh != null && fullLearnedWh > 0.0) {
            val mainText = if (hybridFull.uncertaintyWh != null && hybridFull.uncertaintyWh > 0.005) {
                String.format(Locale.US, "%.2f ± %.2f Wh", fullLearnedWh, hybridFull.uncertaintyWh)
            } else {
                String.format(Locale.US, "%.2f Wh", fullLearnedWh)
            }
            tvValLearned.text = mainText
            tvValLearned.setTextColor(Color.parseColor("#10b981"))

            val threshText = if (overview.effectiveThresholdPercent == 100) "100%" else "≥${overview.effectiveThresholdPercent}%"
            val covText = when {
                hybridFull.isFullyLearned -> {
                    if (overview.participatingSessionCount > 0) "100% 实测微积分 (门限 $threshText · ${overview.participatingSessionCount}条)"
                    else "100% 实测微积分"
                }
                hybridFull.measuredSocCoverage >= 0.80f -> {
                    val measStr = String.format(Locale.US, "%.0f%%", hybridFull.measuredSocCoverage * 100f)
                    if (overview.participatingSessionCount > 0) "${measStr}实测微积分 (门限 $threshText · ${overview.participatingSessionCount}条)"
                    else "${measStr}实测微积分"
                }
                overview.macroHistoricalFullWh != null && overview.participatingSessionCount > 0 -> {
                    "历史中位数 (门限 $threshText · ${overview.participatingSessionCount}条)"
                }
                overview.isScreenOnAnchored -> {
                    val onWh = overview.screenOnMeasuredEnergyWh ?: 0f
                    val onDrop = overview.screenOnDropPercent ?: 0f
                    String.format(Locale.US, "实测 %.2f Wh (掉电 %.1f%%)", onWh, onDrop)
                }
                else -> {
                    val measStr = String.format(Locale.US, "%.0f%%", hybridFull.measuredSocCoverage * 100f)
                    val modStr = String.format(Locale.US, "%.0f%%", hybridFull.modeledSocCoverage * 100f)
                    "${measStr}实测 + ${modStr}自举"
                }
            }
            tvValLearnedCoverage?.text = covText

            val energyHealth = overview.energyHealthRetentionPercent
            val equivMah = overview.equivalentCapacityMah
            if (energyHealth != null && energyHealth in 30.0..130.0 && equivMah != null && equivMah > 0.0) {
                tvLabelLearnedHealth?.visibility = View.VISIBLE
                tvLabelLearnedHealth?.text = getString(R.string.energy_source_learned_health_title)
                tvHintLearned?.gravity = Gravity.END
                tvHintLearned?.text = getString(
                    R.string.energy_source_learned_health_val,
                    energyHealth,
                    Math.round(equivMah)
                )
            } else {
                tvLabelLearnedHealth?.visibility = View.GONE
                tvHintLearned?.gravity = Gravity.START
                val efficiency = overview.dischargeEnergyEfficiencyPercent
                if (efficiency != null && efficiency in 40.0..130.0) {
                    tvHintLearned?.text = getString(R.string.energy_source_learned_efficiency_hint, efficiency)
                } else {
                    tvHintLearned?.text = getString(R.string.energy_source_learned_hint)
                }
            }
        } else {
            tvValLearned.text = context.getString(R.string.energy_model_bin_unlearned)
            tvValLearned.setTextColor(Color.parseColor("#f59e0b"))
            val learnedBinsCount = curve.bins.count { it.hasLearnedData() }
            val progressText = if (learnedBinsCount in 1 until LearnedSocEnergyCurve.MIN_BOOTSTRAP_LEARNED_BINS) {
                "采样积累中 ($learnedBinsCount/${LearnedSocEnergyCurve.MIN_BOOTSTRAP_LEARNED_BINS} 段)"
            } else {
                "0 段已学习"
            }
            tvValLearnedCoverage?.text = progressText
            tvLabelLearnedHealth?.visibility = View.GONE
            tvHintLearned?.gravity = Gravity.START
            tvHintLearned?.text = getString(R.string.energy_source_learned_hint)
        }

        // 实测学习放电样本与会话说明
        if (overview.macroHistoricalFullWh != null && overview.participatingSessionCount > 0) {
            val baseDesc = "基于最近 ${overview.participatingSessionCount} 条有效放电记录"
            val medianStr = String.format(Locale.US, "宏观中位数 %.2f Wh", overview.macroHistoricalFullWh)
            tvValSessions?.text = when {
                overview.curveCoveragePercent < 0.50f -> {
                    "$baseDesc · $medianStr · 实测覆盖 ${String.format(Locale.US, "%.0f%%", overview.curveCoveragePercent * 100f)}，低电量依赖外推"
                }
                overview.participatingSessionCount <= 1 -> {
                    "$baseDesc · $medianStr · 单次放电实测，需积累验证"
                }
                overview.historicalDispersionPercent != null && overview.historicalDispersionPercent > 8.0 -> {
                    "$baseDesc · $medianStr · 样本离散度 ${String.format(Locale.US, "%.1f%%", overview.historicalDispersionPercent)}"
                }
                else -> "$baseDesc · $medianStr"
            }
        } else if (overview.isScreenOnAnchored) {
            tvValSessions?.text = overview.confidenceReason ?: getString(R.string.energy_source_sessions_anchored)
        } else {
            val powerManager = com.battery.analysis.manager.PowerUsageManager.getInstance(context)
            val isDischarging = !powerManager.getCurrentBatteryStatus().isCharging
            val currentDischargeSamples = powerManager.getDischargeRealtimeSamples()
            val hasOngoingDischarge = isDischarging && currentDischargeSamples.size >= 2
            val maxBinSamples = curve.bins.maxOfOrNull { it.sampleCount } ?: 0
            tvValSessions?.text = formatDischargeSessionsSummary(
                totalSessions = overview.totalSessionsCount,
                hasOngoingDischarge = hasOngoingDischarge,
                maxBinSamples = maxBinSamples
            )
        }

        // 行2：当前剩余能量（包含实测微积分、硬件直读与容量模型折算，忠实呈现当前电量下的实际可用做功）
        tvLabelRemaining?.text = getString(R.string.energy_col_remaining_label)
        val currentSoc = overview.currentSocPercent
        val currentSocInt = Math.round(currentSoc)
        val effCap = overview.effectiveCapacityMah ?: 0f

        // 优先判断硬件原生上报剩余能量（若有且物理有效）
        val hwNativeWh = overview.hardwareNativeEnergyWh
        if (hwNativeWh != null && hwNativeWh > 0.0) {
            tvValRemaining?.text = String.format(Locale.US, "%.2f Wh", hwNativeWh)
            tvValRemaining?.setTextColor(Color.parseColor("#3b82f6"))
            tvDescRemaining?.text = getString(R.string.energy_col_remaining_source_hardware, currentSocInt)
            val equivMah = (hwNativeWh * 1000.0) / overview.nominalVoltageVolts
            tvValRemainingDesc?.text = getString(R.string.energy_col_remaining_equiv_mah, Math.round(equivMah))
        } else {
            // 实测学习模型推算的当前剩余能量（已学 20 段曲线积分或宏观锚定展开）
            val fullLearned = overview.fullLearnedEnergyWh ?: hybridFull?.remainingEnergyWh ?: overview.learnedRemainingEnergyWh
            val learnedRemainingWh = overview.learnedRemainingEnergyWh
                ?: (if (fullLearned != null && fullLearned > 0.0 && currentSoc > 0f) fullLearned * (currentSoc / 100.0) else null)

            if (learnedRemainingWh != null && learnedRemainingWh > 0.0) {
                tvValRemaining?.text = String.format(Locale.US, "%.2f Wh", learnedRemainingWh)
                tvValRemaining?.setTextColor(Color.parseColor("#3b82f6"))
                tvDescRemaining?.text = getString(R.string.energy_col_remaining_source_learned, currentSocInt)

                val equivFullMah = overview.equivalentCapacityMah
                val equivRemainMah = if (equivFullMah != null && equivFullMah > 0.0) {
                    equivFullMah * (currentSoc / 100.0)
                } else {
                    (learnedRemainingWh * 1000.0) / overview.nominalVoltageVolts
                }
                tvValRemainingDesc?.text = getString(R.string.energy_col_remaining_equiv_mah, Math.round(equivRemainMah))
            } else if (overview.nominalCalculatedEnergyWh != null && overview.nominalCalculatedEnergyWh > 0.0) {
                // 标称模型推算（未学习冷启动或初始阶段）
                tvValRemaining?.text = String.format(Locale.US, "%.2f Wh", overview.nominalCalculatedEnergyWh)
                tvValRemaining?.setTextColor(Color.parseColor("#3b82f6"))
                tvDescRemaining?.text = getString(R.string.energy_col_remaining_source_nominal, currentSocInt)

                val equivRemainMah = if (effCap > 0f) effCap * (currentSoc / 100.0) else (overview.nominalCalculatedEnergyWh * 1000.0) / overview.nominalVoltageVolts
                tvValRemainingDesc?.text = getString(R.string.energy_col_remaining_equiv_mah, Math.round(equivRemainMah))
            } else {
                tvValRemaining?.text = "--"
                tvValRemaining?.setTextColor(Color.parseColor("#64748b"))
                tvDescRemaining?.text = getString(R.string.energy_col_remaining_source_nominal, currentSocInt)
                tvValRemainingDesc?.text = "--"
            }
        }

        // 行3：出厂设计标称额定满电基准与健康度对比
        val nominalVStr = String.format(Locale.US, "%.2fV", overview.nominalVoltageVolts)
        val fullDesign = overview.fullDesignEnergyWh
        val fullNominal = overview.fullNominalEnergyWh
        val tvDescNominal = root.findViewById<TextView>(R.id.tv_desc_nominal_energy)
        val tvHintNominal = root.findViewById<TextView>(R.id.tv_hint_nominal_desc)

        if (fullDesign != null && fullDesign > 0.0) {
            tvLabelNominal.text = "${getString(R.string.energy_col_design_label)} (${nominalVStr})"
            tvValNominal.text = String.format(Locale.US, "%.2f Wh", fullDesign)
            val designCap = overview.designCapacityMah ?: 0f
            val capSoh = overview.capacityModelSohPercent ?: (if (designCap > 0f && effCap > 0f) (effCap / designCap).toDouble() * 100.0 else null)
            if (capSoh != null && fullNominal != null && Math.abs(fullDesign - fullNominal) > 0.1) {
                tvLabelNominalHealth?.visibility = View.VISIBLE
                tvLabelNominalHealth?.text = getString(R.string.energy_col_capacity_model_health_title)
                tvDescNominal?.gravity = Gravity.END
                tvDescNominal?.text = getString(
                    R.string.energy_col_capacity_model_health_val,
                    capSoh,
                    Math.round(effCap),
                    fullNominal
                )
                tvHintNominal?.text = getString(
                    R.string.energy_col_design_base_line,
                    Math.round(designCap)
                )
            } else {
                tvLabelNominalHealth?.visibility = View.GONE
                tvDescNominal?.gravity = Gravity.START
                tvDescNominal?.text = getString(R.string.energy_col_nominal_source)
                tvHintNominal?.text = getString(R.string.energy_col_nominal_desc)
            }
        } else if (fullNominal != null && fullNominal > 0.0) {
            tvLabelNominal.text = "${getString(R.string.energy_col_nominal_label)} (${nominalVStr})"
            tvValNominal.text = String.format(Locale.US, "%.2f Wh", fullNominal)
            tvLabelNominalHealth?.visibility = View.GONE
            tvDescNominal?.gravity = Gravity.START
            tvDescNominal?.text = getString(R.string.energy_col_nominal_source)
            tvHintNominal?.text = getString(R.string.energy_col_nominal_desc)
        } else if (overview.nominalCalculatedEnergyWh != null && overview.nominalCalculatedEnergyWh > 0.0) {
            tvValNominal.text = String.format(Locale.US, "%.2f Wh", overview.nominalCalculatedEnergyWh)
            tvLabelNominalHealth?.visibility = View.GONE
            tvDescNominal?.gravity = Gravity.START
            tvHintNominal?.text = getString(R.string.energy_col_nominal_desc)
        } else {
            tvValNominal.text = "--"
            tvLabelNominalHealth?.visibility = View.GONE
            tvDescNominal?.gravity = Gravity.START
            tvHintNominal?.text = getString(R.string.energy_col_nominal_desc)
        }

        // 渲染板块三 20 段 SOC 能量分布明细（优化复用既有 View，杜绝无节制重复 Inflate 布局）
        val tvBinsSummary = root.findViewById<TextView>(R.id.tv_bins_summary_count)
        val layoutBinsContainer = root.findViewById<LinearLayout>(R.id.layout_bins_container)

        val coveredCount = curve.bins.count { it.hasLearnedData() }
        tvBinsSummary.text = "已学习 $coveredCount / ${LearnedSocEnergyCurve.BIN_COUNT} 段"

        val inflater = LayoutInflater.from(context)
        val fullResolvedBins = overview.hybridFullEstimate?.resolvedBins ?: overview.resolvedBins
        val shouldInflate = layoutBinsContainer.childCount != LearnedSocEnergyCurve.BIN_COUNT
        if (shouldInflate) {
            layoutBinsContainer.removeAllViews()
        }

        // 从高 SOC 到低 SOC 逆序展示（从 100%~95% 往下排列，更符合用户日常掉电心智）
        for (order in 0 until LearnedSocEnergyCurve.BIN_COUNT) {
            val i = (LearnedSocEnergyCurve.BIN_COUNT - 1) - order
            val bin = curve.bins[i]
            val resolved = fullResolvedBins.find { it.binIndex == i } ?: overview.resolvedBins.getOrNull(i)
            val itemView = if (shouldInflate) {
                val v = inflater.inflate(R.layout.item_soc_energy_bin, layoutBinsContainer, false)
                layoutBinsContainer.addView(v)
                v
            } else {
                layoutBinsContainer.getChildAt(order)
            }

            val tvLabel = itemView.findViewById<TextView>(R.id.tv_bin_range_label)
            val tvSourceBadge = itemView.findViewById<TextView>(R.id.tv_bin_source_badge)
            val tvCount = itemView.findViewById<TextView>(R.id.tv_bin_sample_count)
            val tvEnergy = itemView.findViewById<TextView>(R.id.tv_bin_energy_val)

            tvLabel.text = bin.getLabel()

            val source = resolved?.source ?: if (bin.hasLearnedData()) EnergyEstimateSource.MEASURED_LEARNED else EnergyEstimateSource.UNKNOWN
            when (source) {
                EnergyEstimateSource.MEASURED_LEARNED -> {
                    tvSourceBadge.text = context.getString(R.string.energy_source_badge_measured)
                    tvSourceBadge.setTextColor(Color.parseColor("#10b981"))
                    tvSourceBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2010b981"))
                    val tempSuffix = if (bin.medianTemperatureC != null) String.format(Locale.US, " · %.0f℃", bin.medianTemperatureC) else ""
                    tvCount.text = "${bin.sampleCount}次$tempSuffix"
                    tvCount.setTextColor(Color.parseColor("#64748b"))
                    tvEnergy.text = String.format(Locale.US, "%.3f Wh", bin.learnedEnergyWh!!)
                    tvEnergy.setTextColor(Color.parseColor("#10b981"))
                }
                EnergyEstimateSource.SCREEN_ON_ANCHORED -> {
                    tvSourceBadge.text = context.getString(R.string.energy_source_badge_screen_on_anchored)
                    tvSourceBadge.setTextColor(Color.parseColor("#10b981"))
                    tvSourceBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2010b981"))
                    tvCount.text = context.getString(R.string.energy_source_screen_on_anchored_desc)
                    tvCount.setTextColor(Color.parseColor("#10b981"))
                    tvEnergy.text = String.format(Locale.US, "%.3f Wh", resolved?.effectiveEnergyWh ?: 0.0)
                    tvEnergy.setTextColor(Color.parseColor("#10b981"))
                }
                EnergyEstimateSource.INTERPOLATED -> {
                    tvSourceBadge.text = context.getString(R.string.energy_source_badge_interpolated)
                    tvSourceBadge.setTextColor(Color.parseColor("#38bdf8"))
                    tvSourceBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2038bdf8"))
                    tvCount.text = "双侧插值"
                    tvCount.setTextColor(Color.parseColor("#38bdf8"))
                    tvEnergy.text = String.format(Locale.US, "~%.3f Wh", resolved?.effectiveEnergyWh ?: 0.0)
                    tvEnergy.setTextColor(Color.parseColor("#38bdf8"))
                }
                EnergyEstimateSource.BOOTSTRAP_EXTRAPOLATED -> {
                    tvSourceBadge.text = context.getString(R.string.energy_source_badge_bootstrap)
                    tvSourceBadge.setTextColor(Color.parseColor("#3b82f6"))
                    tvSourceBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#203b82f6"))
                    tvCount.text = "形态自举"
                    tvCount.setTextColor(Color.parseColor("#3b82f6"))
                    tvEnergy.text = String.format(Locale.US, "~%.3f Wh", resolved?.effectiveEnergyWh ?: 0.0)
                    tvEnergy.setTextColor(Color.parseColor("#3b82f6"))
                }
                EnergyEstimateSource.MODEL_PRIOR -> {
                    tvSourceBadge.text = context.getString(R.string.energy_source_badge_model)
                    tvSourceBadge.setTextColor(Color.parseColor("#f59e0b"))
                    tvSourceBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#20f59e0b"))
                    tvCount.text = if (i == 0) "模型(末端修正)" else "容量先验模型"
                    tvCount.setTextColor(Color.parseColor("#f59e0b"))
                    tvEnergy.text = String.format(Locale.US, "~%.3f Wh", resolved?.effectiveEnergyWh ?: 0.0)
                    tvEnergy.setTextColor(Color.parseColor("#f59e0b"))
                }
                EnergyEstimateSource.HARDWARE_VALIDATED -> {
                    tvSourceBadge.text = "硬件"
                    tvSourceBadge.setTextColor(Color.parseColor("#8b5cf6"))
                    tvSourceBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#208b5cf6"))
                    tvCount.text = "硬件能量校验"
                    tvCount.setTextColor(Color.parseColor("#8b5cf6"))
                    tvEnergy.text = String.format(Locale.US, "%.3f Wh", resolved?.effectiveEnergyWh ?: 0.0)
                    tvEnergy.setTextColor(Color.parseColor("#8b5cf6"))
                }
                EnergyEstimateSource.UNKNOWN -> {
                    tvSourceBadge.text = context.getString(R.string.energy_source_badge_unknown)
                    tvSourceBadge.setTextColor(Color.parseColor("#888888"))
                    tvSourceBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#15888888"))
                    tvCount.text = "暂无样本"
                    tvCount.setTextColor(Color.parseColor("#888888"))
                    tvEnergy.text = context.getString(R.string.energy_model_bin_unlearned)
                    tvEnergy.setTextColor(Color.parseColor("#888888"))
                }
            }
        }
    }

    /**
     * 将放电能量模型的成熟度枚举映射为符合本地化语言规范的描述文本。
     *
     * @param context 上下文对象
     * @param maturity 模型成熟度阶段枚举
     * @return 对应的本地化描述字符串
     */
    private fun getModelMaturityLabel(context: Context, maturity: ModelMaturity): String {
        return when (maturity) {
            ModelMaturity.COLD_START -> context.getString(R.string.energy_model_maturity_cold_start)
            ModelMaturity.DEVELOPING -> context.getString(R.string.energy_model_maturity_developing)
            ModelMaturity.CONVERGED -> context.getString(R.string.energy_model_maturity_converged)
        }
    }

    /**
     * 生成实测放电会话与分段样本统计描述文本。
     *
     * 真实反映历史归档会话与当前进行中放电实时增量采样的状态差异，
     * 消除全局归档计数与单段最大样本计数之间的理解歧义。
     *
     * @param totalSessions 历史已完整归档的独立放电会话总次数
     * @param hasOngoingDischarge 当前设备是否正处于放电且具备实时采样
     * @param maxBinSamples 20 个 SOC 区间中已采样的最大单段样本次数
     * @return 格式化后的样本状态描述文案
     */
    private fun formatDischargeSessionsSummary(
        totalSessions: Int,
        hasOngoingDischarge: Boolean,
        maxBinSamples: Int
    ): String {
        return when {
            totalSessions > 0 && hasOngoingDischarge -> {
                if (maxBinSamples > totalSessions) {
                    "已归档 $totalSessions 次放电 · 当前实时采样中 (各段最高 $maxBinSamples 次)"
                } else {
                    "已归档 $totalSessions 次放电 · 当前实时采样中"
                }
            }
            totalSessions > 0 -> {
                if (maxBinSamples > totalSessions) {
                    "已归档 $totalSessions 次放电 (各段最高 $maxBinSamples 次样本)"
                } else {
                    "累计 $totalSessions 次放电周期样本"
                }
            }
            hasOngoingDischarge -> {
                if (maxBinSamples > 0) {
                    "首个放电周期采样中 (已采集 $maxBinSamples 次分段样本)"
                } else {
                    "首个放电周期采样中"
                }
            }
            else -> {
                "暂无历史放电样本"
            }
        }
    }
}
