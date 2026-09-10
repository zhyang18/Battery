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

        // 硬件开销（真实无数据或无权限时显示 --，杜绝人为捏造数值）
        tvCpuTime.text = formatDurationMs(event.cpuTimeMs)
        tvNetwork.text = formatNetworkBytes(event.networkBytes)
        tvWakelockGps.text = formatWakelockAndGps(event.wakelockTimeMs, event.gpsTimeMs)

        // 数据源
        tvSource.text = event.getSourceLabel()
    }

    /**
     * 格式化毫秒耗时为紧凑可读字符串（如 "12s"、"450ms" 或 "--"）。
     *
     * @param ms 待格式化的时间毫秒数
     * @return 格式化后的时间文本
     */
    private fun formatDurationMs(ms: Long): String {
        return when {
            ms <= 0L -> "--"
            ms >= 1000L -> "${ms / 1000}s"
            else -> "${ms}ms"
        }
    }

    /**
     * 格式化网络字节数为自适应单位的人类可读文本（如 "1.2 MB"、"340.5 KB"、"512 B" 或 "--"）。
     *
     * @param bytes 传输的网络字节总数
     * @return 格式化后的网络流量文本
     */
    private fun formatNetworkBytes(bytes: Long): String {
        if (bytes <= 0L) return "--"
        val dBytes = bytes.toDouble()
        val gb = dBytes / (1024 * 1024 * 1024)
        val mb = dBytes / (1024 * 1024)
        val kb = dBytes / 1024
        return when {
            gb >= 1.0 -> String.format(Locale.getDefault(), "%.2f GB", gb)
            mb >= 1.0 -> String.format(Locale.getDefault(), "%.1f MB", mb)
            kb >= 1.0 -> String.format(Locale.getDefault(), "%.1f KB", kb)
            else -> "${bytes} B"
        }
    }

    /**
     * 格式化唤醒锁（Wakelock）与 GPS 定位复合耗时文本。
     *
     * @param wakeMs 唤醒锁持有时长（毫秒）
     * @param gpsMs GPS 定位使用时长（毫秒）
     * @return 组合展示的文本（如 "锁: 5s | GPS: 2s" 或 "--"）
     */
    private fun formatWakelockAndGps(wakeMs: Long, gpsMs: Long): String {
        if (wakeMs <= 0L && gpsMs <= 0L) return "--"
        val wakeStr = if (wakeMs > 0L) formatDurationMs(wakeMs) else "0s"
        val gpsStr = if (gpsMs > 0L) formatDurationMs(gpsMs) else "0s"
        return "锁: $wakeStr | GPS: $gpsStr"
    }
}
