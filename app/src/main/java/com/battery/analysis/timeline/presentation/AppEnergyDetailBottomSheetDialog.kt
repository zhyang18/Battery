package com.battery.analysis.timeline.presentation

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.battery.analysis.R
import com.battery.analysis.timeline.domain.AppTimelineEvent
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用程序功耗与运行详情底部弹窗组件（BottomSheetDialog）。
 * 当用户在功耗时间轴上点击特定 App 图标时弹出，展示该应用在该时段内的能量消耗、平均/峰值功耗、CPU/网络开销及数据来源置信度。
 *
 * @param context Android 上下文环境
 * @param event 选中的 App 时间轴事件实体
 */
class AppEnergyDetailBottomSheetDialog(
    context: Context,
    private val event: AppTimelineEvent
) : BottomSheetDialog(context) {

    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /**
     * 对话框初始化生命周期回调。
     *
     * @param savedInstanceState 保存的状态 Bundle
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_app_energy_detail, null)
        setContentView(view)
        bindData(view)
    }

    /**
     * 将事件数据绑定至弹窗界面的各个控件。
     *
     * @param root 弹窗根视图 View
     */
    private fun bindData(root: View) {
        val ivIcon = root.findViewById<ImageView>(R.id.iv_detail_icon)
        val tvName = root.findViewById<TextView>(R.id.tv_detail_name)
        val tvPackage = root.findViewById<TextView>(R.id.tv_detail_package)
        val tvConfidence = root.findViewById<TextView>(R.id.tv_detail_confidence)
        val tvEnergy = root.findViewById<TextView>(R.id.tv_detail_energy)
        val tvAvgPower = root.findViewById<TextView>(R.id.tv_detail_avg_power)
        val tvPeakPower = root.findViewById<TextView>(R.id.tv_detail_peak_power)
        val tvTimeRange = root.findViewById<TextView>(R.id.tv_detail_time_range)
        val tvCpuTime = root.findViewById<TextView>(R.id.tv_detail_cpu_time)
        val tvNetwork = root.findViewById<TextView>(R.id.tv_detail_network)
        val tvWakelockGps = root.findViewById<TextView>(R.id.tv_detail_wakelock_gps)
        val tvSource = root.findViewById<TextView>(R.id.tv_detail_source)

        // 图标与基本信息
        if (event.icon != null) {
            ivIcon.setImageDrawable(event.icon)
        } else {
            ivIcon.setImageResource(R.mipmap.ic_launcher)
        }
        tvName.text = event.appName
        tvPackage.text = "${event.packageName} (UID: ${event.uid})"

        // 置信度
        tvConfidence.text = "可信度: ${event.getConfidenceLabel()}"

        // 能耗与功率
        tvEnergy.text = event.getFormattedEnergy()
        tvAvgPower.text = event.getFormattedAveragePower()
        tvPeakPower.text = event.getFormattedPeakPower()

        // 时间区间
        val startStr = timeFormatter.format(Date(event.startTime))
        val endStr = timeFormatter.format(Date(event.endTime))
        tvTimeRange.text = "$startStr - $endStr (${event.getFormattedDuration()})"

        // 硬件开销
        val cpuSeconds = event.cpuTimeMs / 1000
        tvCpuTime.text = if (cpuSeconds > 0) "${cpuSeconds}s" else "<1s"

        val mb = event.networkBytes.toDouble() / (1024 * 1024)
        tvNetwork.text = if (mb >= 0.1) String.format(Locale.getDefault(), "%.1f MB", mb) else "--"

        val wakeSeconds = event.wakelockTimeMs / 1000
        val gpsSeconds = event.gpsTimeMs / 1000
        tvWakelockGps.text = "锁: ${wakeSeconds}s | GPS: ${gpsSeconds}s"

        // 数据源
        tvSource.text = event.getSourceLabel()
    }
}
