package com.battery.analysis.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.BatteryManager
import android.os.Bundle
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

/**
 * 放电能量模型与 5% SOC 历史放电能量曲线实测档案底部抽屉弹窗控制器（DischargeEnergyModelBottomSheetDialog）。
 *
 * 核心功能与原则：
 * 1. 突破黑盒：呈现基于端电压与电流梯形微积分做功计算的实测剩余能量、硬件原生寄存器直读与传统标压粗算三源对比；
 * 2. 20 段 SOC 能量释放分布：逐一展示 20 个 5% 区间历史放电释放做功（Wh）与样本次数；
 * 3. 严格遵循零假数据原则：未采集的 SOC 区间如实展示“待采集”，绝不合成伪造数据。
 */
class DischargeEnergyModelBottomSheetDialog : BottomSheetDialogFragment() {

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

        bindEnergyData(view)
    }

    /**
     * 绑定实测三源能量对比看板与 20 段 SOC 能量释放明细。
     *
     * @param root 弹窗根视图
     */
    private fun bindEnergyData(root: View) {
        val context = context ?: return

        // 核心增强：打开弹窗时优先触发一次正在进行中放电会话的即时学习评估，让已跑满的 5% 区间立即可见
        try {
            com.battery.analysis.manager.PowerUsageManager.getInstance(context).evaluateInFlightSocLearning()
        } catch (_: Exception) {}

        val learner = SocEnergyCurveLearner.getInstance(context)
        val curve = learner.getLearnedCurve()

        // 1. 获取当前电量与底层硬件原始数据
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val currentSoc = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.toFloat() ?: 50.0f
        val nativeEnergyNwh = batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
        val hardwareWh = if (nativeEnergyNwh != null && nativeEnergyNwh != Long.MIN_VALUE && nativeEnergyNwh > 0L) {
            val convertedWh = nativeEnergyNwh.toDouble() / 1_000_000_000.0
            if (convertedWh in 0.1..100.0) convertedWh else null
        } else null

        // 获取基准满充容量
        val capacityResult = BatteryCapacityManager.getInstance(context).capacityState.value
        val fccMah = capacityResult?.fullChargeCapacityMah ?: capacityResult?.designCapacityMah

        val powerManager = com.battery.analysis.manager.PowerUsageManager.getInstance(context)
        val currentStatus = powerManager.getCurrentBatteryStatus()
        val isDischarging = !currentStatus.isCharging
        val currentDischargeMa = if (isDischarging) {
            val rawCur = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
            if (rawCur != 0 && rawCur != Int.MIN_VALUE) {
                val normalizedMa = BatteryUnitNormalizer.normalizeCurrentMa(rawCur.toLong(), isCharging = false)
                if (normalizedMa > 0f) normalizedMa.toDouble() else null
            } else null
        } else null
        val tempCelsius = currentStatus.temperature

        val overview = learner.buildComparisonOverview(
            currentSocPercent = currentSoc,
            hardwareNativeEnergyWh = hardwareWh,
            effectiveCapacityMah = fccMah?.toFloat(),
            nominalVoltageVolts = 3.85f,
            currentDischargeCurrentMa = currentDischargeMa,
            temperatureCelsius = tempCelsius
        )

        // 2. 渲染板块一看板数据（纵向每行显示对比）
        val tvCoverageBadge = root.findViewById<TextView>(R.id.tv_model_coverage_badge)
        val tvValLearned = root.findViewById<TextView>(R.id.tv_val_learned_energy)
        val tvValLearnedCoverage = root.findViewById<TextView>(R.id.tv_val_learned_coverage)
        val tvValSessions = root.findViewById<TextView>(R.id.tv_val_model_sessions)
        val tvValHardware = root.findViewById<TextView>(R.id.tv_val_hardware_energy)
        val tvValHardwareDesc = root.findViewById<TextView>(R.id.tv_val_hardware_desc)
        val tvLabelNominal = root.findViewById<TextView>(R.id.tv_label_nominal_energy)
        val tvValNominal = root.findViewById<TextView>(R.id.tv_val_nominal_energy)

        // 覆盖率 badge：显示全局覆盖率 + 成熟度（支持多语言，中文环境下显示中文）
        val coveragePercentStr = String.format(Locale.US, "%.1f%%", overview.curveCoveragePercent * 100f)
        val maturityText = getModelMaturityLabel(context, curve.maturity)
        tvCoverageBadge.text = "$coveragePercentStr ($maturityText)"

        // 行1：实测学习模型（三层混合估算值 + 覆盖血统 + 放电周期/分段样本状态）
        val hybrid = overview.hybridEstimate
        val mixed = overview.mixedEstimate
        if (hybrid != null && hybrid.remainingEnergyWh != null && hybrid.remainingEnergyWh > 0.0) {
            val mainText = if (hybrid.uncertaintyWh != null && hybrid.uncertaintyWh > 0.005) {
                String.format(Locale.US, "%.2f ± %.2f Wh", hybrid.remainingEnergyWh, hybrid.uncertaintyWh)
            } else {
                String.format(Locale.US, "%.2f Wh", hybrid.remainingEnergyWh)
            }
            tvValLearned.text = mainText
            tvValLearned.setTextColor(Color.parseColor("#10b981"))

            val covText = if (hybrid.isFullyLearned) {
                "100% 实测微积分"
            } else {
                val measStr = String.format(Locale.US, "%.0f%%", hybrid.measuredSocCoverage * 100f)
                val modStr = String.format(Locale.US, "%.0f%%", hybrid.modeledSocCoverage * 100f)
                "${measStr}实测 + ${modStr}模型"
            }
            tvValLearnedCoverage?.text = covText
        } else if (mixed != null && overview.learnedRemainingEnergyWh != null && overview.learnedRemainingEnergyWh > 0.0) {
            val prefix = if (mixed.isFullyLearned) "" else "~"
            tvValLearned.text = String.format(Locale.US, "%s%.2f Wh", prefix, overview.learnedRemainingEnergyWh)
            tvValLearned.setTextColor(Color.parseColor("#10b981"))
            val covText = if (mixed.isFullyLearned) {
                "${mixed.learnedBinCount}/${mixed.totalBinCount} 段实测"
            } else {
                "${mixed.learnedBinCount}/${mixed.totalBinCount} 段实测+估算"
            }
            tvValLearnedCoverage?.text = covText
        } else {
            tvValLearned.text = context.getString(R.string.energy_model_bin_unlearned)
            tvValLearned.setTextColor(Color.parseColor("#f59e0b"))
            tvValLearnedCoverage?.text = "0 段已学习"
        }

        // 实测学习放电样本与会话说明
        val currentDischargeSamples = powerManager.getDischargeRealtimeSamples()
        val hasOngoingDischarge = isDischarging && currentDischargeSamples.size >= 2
        val maxBinSamples = curve.bins.maxOfOrNull { it.sampleCount } ?: 0
        tvValSessions.text = formatDischargeSessionsSummary(
            totalSessions = overview.totalSessionsCount,
            hasOngoingDischarge = hasOngoingDischarge,
            maxBinSamples = maxBinSamples
        )

        // 行2：硬件直读行
        if (overview.hardwareNativeEnergyWh != null && overview.hardwareNativeEnergyWh > 0.0) {
            tvValHardware.text = String.format(Locale.US, "%.2f Wh", overview.hardwareNativeEnergyWh)
            tvValHardwareDesc.text = context.getString(R.string.energy_col_hardware_supported)
            tvValHardwareDesc.setTextColor(Color.parseColor("#10b981"))
        } else {
            tvValHardware.text = "--"
            tvValHardwareDesc.text = context.getString(R.string.energy_col_hardware_unsupported)
            tvValHardwareDesc.setTextColor(Color.parseColor("#64748b"))
        }

        // 行3：标称折算行
        val nominalVStr = String.format(Locale.US, "%.2fV", overview.nominalVoltageVolts)
        tvLabelNominal.text = "传统标称折算 (${nominalVStr})"
        if (overview.nominalCalculatedEnergyWh != null && overview.nominalCalculatedEnergyWh > 0.0) {
            tvValNominal.text = String.format(Locale.US, "%.2f Wh", overview.nominalCalculatedEnergyWh)
        } else {
            tvValNominal.text = "--"
        }

        // 3. 渲染板块三 20 段 SOC 能量分布明细
        val tvBinsSummary = root.findViewById<TextView>(R.id.tv_bins_summary_count)
        val layoutBinsContainer = root.findViewById<LinearLayout>(R.id.layout_bins_container)
        layoutBinsContainer.removeAllViews()

        val coveredCount = curve.bins.count { it.hasLearnedData() }
        tvBinsSummary.text = "已学习 $coveredCount / ${LearnedSocEnergyCurve.BIN_COUNT} 段"

        val inflater = LayoutInflater.from(context)

        // 从高 SOC 到低 SOC 逆序展示（从 100%~95% 往下排列，更符合用户日常掉电心智）
        for (i in (LearnedSocEnergyCurve.BIN_COUNT - 1) downTo 0) {
            val bin = curve.bins[i]
            val resolved = overview.resolvedBins.getOrNull(i)
            val itemView = inflater.inflate(R.layout.item_soc_energy_bin, layoutBinsContainer, false)

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
                    tvCount.text = "样本: ${bin.sampleCount} 次$tempSuffix"
                    tvCount.setTextColor(Color.parseColor("#64748b"))
                    tvEnergy.text = String.format(Locale.US, "%.3f Wh", bin.learnedEnergyWh!!)
                    tvEnergy.setTextColor(Color.parseColor("#10b981"))
                }
                EnergyEstimateSource.INTERPOLATED -> {
                    tvSourceBadge.text = context.getString(R.string.energy_source_badge_interpolated)
                    tvSourceBadge.setTextColor(Color.parseColor("#3b82f6"))
                    tvSourceBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#203b82f6"))
                    tvCount.text = "相邻电压插值"
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

            layoutBinsContainer.addView(itemView)
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
