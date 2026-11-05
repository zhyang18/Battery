package com.battery.analysis.ui

import android.content.Context
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
import com.battery.analysis.domain.estimator.model.LearnedSocEnergyCurve
import com.battery.analysis.manager.BatteryCapacityManager
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
            nativeEnergyNwh.toDouble() / 1_000_000_000.0
        } else null

        // 获取基准满充容量
        val capacityResult = BatteryCapacityManager.getInstance(context).capacityState.value
        val fccMah = capacityResult?.fullChargeCapacityMah ?: capacityResult?.designCapacityMah

        val overview = learner.buildComparisonOverview(
            currentSocPercent = currentSoc,
            hardwareNativeEnergyWh = hardwareWh,
            effectiveCapacityMah = fccMah?.toFloat(),
            nominalVoltageVolts = 3.85f
        )

        // 2. 渲染板块一看板数据
        val tvCoverageBadge = root.findViewById<TextView>(R.id.tv_model_coverage_badge)
        val tvValLearned = root.findViewById<TextView>(R.id.tv_val_learned_energy)
        val tvValHardware = root.findViewById<TextView>(R.id.tv_val_hardware_energy)
        val tvValNominal = root.findViewById<TextView>(R.id.tv_val_nominal_energy)
        val tvValSessions = root.findViewById<TextView>(R.id.tv_val_model_sessions)

        val coveragePercentStr = String.format(Locale.US, "%.1f%%", overview.curveCoveragePercent * 100f)
        tvCoverageBadge.text = context.getString(R.string.energy_model_coverage_label) + ": " + coveragePercentStr + " (" + curve.maturity.name + ")"

        if (overview.learnedRemainingEnergyWh != null && overview.learnedRemainingEnergyWh > 0.0) {
            tvValLearned.text = String.format(Locale.US, "%.2f Wh", overview.learnedRemainingEnergyWh)
            tvValLearned.setTextColor(Color.parseColor("#10b981"))
        } else {
            tvValLearned.text = context.getString(R.string.energy_model_bin_unlearned)
            tvValLearned.setTextColor(Color.parseColor("#f59e0b"))
        }

        if (overview.hardwareNativeEnergyWh != null && overview.hardwareNativeEnergyWh > 0.0) {
            tvValHardware.text = String.format(Locale.US, "%.2f Wh", overview.hardwareNativeEnergyWh)
        } else {
            tvValHardware.text = "--"
        }

        if (overview.nominalCalculatedEnergyWh != null && overview.nominalCalculatedEnergyWh > 0.0) {
            tvValNominal.text = String.format(Locale.US, "%.2f Wh", overview.nominalCalculatedEnergyWh)
        } else {
            tvValNominal.text = "--"
        }

        val powerManager = com.battery.analysis.manager.PowerUsageManager.getInstance(context)
        val isDischarging = !powerManager.getCurrentBatteryStatus().isCharging
        val currentDischargeSamples = powerManager.getDischargeRealtimeSamples()
        val hasOngoingDischarge = isDischarging && currentDischargeSamples.size >= 2
        val coveredCount = curve.bins.count { it.hasLearnedData() }

        val sessionsText = if (hasOngoingDischarge && coveredCount > 0 && overview.totalSessionsCount == 0) {
            "${overview.totalSessionsCount} 次放电样本 (当前会话进行中)"
        } else {
            "${overview.totalSessionsCount} 次放电样本"
        }
        tvValSessions.text = sessionsText

        // 3. 渲染板块三 20 段 SOC 能量分布明细
        val tvBinsSummary = root.findViewById<TextView>(R.id.tv_bins_summary_count)
        val layoutBinsContainer = root.findViewById<LinearLayout>(R.id.layout_bins_container)
        layoutBinsContainer.removeAllViews()

        tvBinsSummary.text = "已学习 $coveredCount / ${LearnedSocEnergyCurve.BIN_COUNT} 段"

        val inflater = LayoutInflater.from(context)

        // 从高 SOC 到低 SOC 逆序展示（从 100%~95% 往下排列，更符合用户日常掉电心智）
        for (i in (LearnedSocEnergyCurve.BIN_COUNT - 1) downTo 0) {
            val bin = curve.bins[i]
            val itemView = inflater.inflate(R.layout.item_soc_energy_bin, layoutBinsContainer, false)

            val tvLabel = itemView.findViewById<TextView>(R.id.tv_bin_range_label)
            val tvCount = itemView.findViewById<TextView>(R.id.tv_bin_sample_count)
            val tvEnergy = itemView.findViewById<TextView>(R.id.tv_bin_energy_val)

            tvLabel.text = bin.getLabel()

            if (bin.hasLearnedData()) {
                val tempSuffix = if (bin.medianTemperatureC != null) String.format(Locale.US, " · %.0f℃", bin.medianTemperatureC) else ""
                tvCount.text = "样本: ${bin.sampleCount} 次$tempSuffix"
                tvEnergy.text = String.format(Locale.US, "%.3f Wh", bin.learnedEnergyWh!!)
                tvEnergy.setTextColor(Color.parseColor("#10b981"))
            } else {
                tvCount.text = "暂无样本"
                tvCount.setTextColor(Color.parseColor("#888888"))
                tvEnergy.text = context.getString(R.string.energy_model_bin_unlearned)
                tvEnergy.setTextColor(Color.parseColor("#888888"))
            }

            layoutBinsContainer.addView(itemView)
        }
    }
}
