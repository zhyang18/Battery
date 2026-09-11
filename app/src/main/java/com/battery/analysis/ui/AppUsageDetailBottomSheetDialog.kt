package com.battery.analysis.ui

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.battery.analysis.R
import com.battery.analysis.model.AppPowerUsageItem
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.ShapeAppearanceModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用程序全周期使用场景与前后台深度能耗详情底部弹窗组件（BottomSheetDialog）。
 * 当用户在“使用场景”应用列表中点击任意特定 App 项时弹出，展示该应用在当前放电周期内的：
 * 1. 核心能量、平均功耗（前后台解耦）与运行温度指标（前后台独立分行换行展示，移除冗余分隔符）；
 * 2. 工况运行时长（前台亮屏 vs 后台运行 vs 总时长）；
 * 3. 电量消耗分配及占比（精确到小数点三位数）；
 * 4. 真实硬件开销（CPU 算力、网络数据流量、唤醒锁与 GPS 定位）；
 * 5. 统计时间区间、最近活跃时序与底层数据来源。
 *
 * @param context Android 上下文环境
 * @param item 选中的应用程序综合功耗与使用实体
 * @param isShizuku 当前统计数据源是否来自于 Shizuku 提权深度采集
 * @param periodRangeText 统计时间区间文本（如 "10:20:00 - 12:21:23 (20s)"），可选
 */
class AppUsageDetailBottomSheetDialog(
    context: Context,
    private val item: AppPowerUsageItem,
    private val isShizuku: Boolean = true,
    private val periodRangeText: String? = null
) : BottomSheetDialog(context) {

    private val dateTimeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val timeOnlyFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /**
     * 对话框初始化生命周期回调。
     *
     * @param savedInstanceState 保存的状态 Bundle
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_app_usage_detail, null)
        setContentView(view)
        bindData(view)
    }

    /**
     * 将应用使用与能耗数据绑定至弹窗界面的各个控件。
     *
     * @param root 弹窗根视图 View
     */
    private fun bindData(root: View) {
        val ivIcon = root.findViewById<ShapeableImageView>(R.id.iv_detail_icon)
        val tvName = root.findViewById<TextView>(R.id.tv_detail_name)
        val tvPackage = root.findViewById<TextView>(R.id.tv_detail_package)
        val tvStatusBadge = root.findViewById<TextView>(R.id.tv_detail_status_badge)

        val tvEnergyPrimary = root.findViewById<TextView>(R.id.tv_detail_energy_primary)
        val tvEnergyFg = root.findViewById<TextView>(R.id.tv_detail_energy_fg)
        val tvEnergyBg = root.findViewById<TextView>(R.id.tv_detail_energy_bg)

        val tvPowerPrimary = root.findViewById<TextView>(R.id.tv_detail_power_primary)
        val tvPowerFg = root.findViewById<TextView>(R.id.tv_detail_power_fg)
        val tvPowerBg = root.findViewById<TextView>(R.id.tv_detail_power_bg)

        val tvTempPrimary = root.findViewById<TextView>(R.id.tv_detail_temp_primary)
        val tvTempAvg = root.findViewById<TextView>(R.id.tv_detail_temp_avg)
        val tvTempMax = root.findViewById<TextView>(R.id.tv_detail_temp_max)

        val tvFgDuration = root.findViewById<TextView>(R.id.tv_detail_fg_duration)
        val tvBgDuration = root.findViewById<TextView>(R.id.tv_detail_bg_duration)
        val tvTotalDuration = root.findViewById<TextView>(R.id.tv_detail_total_duration)

        val tvFgEnergy = root.findViewById<TextView>(R.id.tv_detail_fg_energy)
        val tvBgEnergy = root.findViewById<TextView>(R.id.tv_detail_bg_energy)
        val tvTotalEnergy = root.findViewById<TextView>(R.id.tv_detail_total_energy)

        val tvCpuTime = root.findViewById<TextView>(R.id.tv_detail_cpu_time)
        val tvNetwork = root.findViewById<TextView>(R.id.tv_detail_network)
        val tvWakelockGps = root.findViewById<TextView>(R.id.tv_detail_wakelock_gps)

        val tvPeriodRange = root.findViewById<TextView>(R.id.tv_detail_period_range)
        val tvLastUsed = root.findViewById<TextView>(R.id.tv_detail_last_used)
        val tvSource = root.findViewById<TextView>(R.id.tv_detail_source)

        // 1. 图标圆角与基础信息
        val shapeModel = ShapeAppearanceModel.builder()
            .setAllCorners(CornerFamily.ROUNDED, 24f)
            .build()
        ivIcon.shapeAppearanceModel = shapeModel
        if (item.icon != null) {
            ivIcon.setImageDrawable(item.icon)
        } else {
            ivIcon.setImageResource(R.mipmap.ic_launcher)
        }

        tvName.text = item.appName
        val uid = getAppUid(item.packageName)
        val uidStr = if (uid > 0) " (UID: $uid)" else ""
        tvPackage.text = "${item.packageName}$uidStr"

        // 2. 状态标签（根据真实前后台活动时长设定）
        val hasFg = item.foregroundTimeMs >= 1000L
        val hasBg = item.backgroundTimeMs >= 1000L
        when {
            hasFg && hasBg -> {
                tvStatusBadge.text = "前台+后台"
                tvStatusBadge.setTextColor(Color.parseColor("#2196F3"))
            }
            hasFg -> {
                tvStatusBadge.text = "前台活跃"
                tvStatusBadge.setTextColor(Color.parseColor("#34C759"))
            }
            hasBg -> {
                tvStatusBadge.text = "纯后台运行"
                tvStatusBadge.setTextColor(Color.parseColor("#2196F3"))
            }
            else -> {
                tvStatusBadge.text = "后台待机"
                tvStatusBadge.setTextColor(Color.parseColor("#888888"))
            }
        }

        // 3. 核心指标矩阵（前台与后台独立换行展示，去除分隔符）
        // 能量列
        tvEnergyPrimary.text = formatEnergyValue(item.energyWh)
        tvEnergyFg.text = "前: ${formatEnergyValue(item.foregroundEnergyWh)}"
        tvEnergyBg.text = "后: ${formatEnergyValue(item.backgroundEnergyWh)}"

        // 功率列
        val fgPwrStr = formatWattsValue(item.foregroundPowerWatts)
        val bgPwrStr = formatWattsValue(item.backgroundPowerWatts)
        val primaryPwr = if (item.foregroundPowerWatts >= 0.005f) {
            fgPwrStr
        } else if (item.backgroundPowerWatts >= 0.005f) {
            bgPwrStr
        } else if (item.avgPowerWatts >= 0.005f) {
            formatWattsValue(item.avgPowerWatts)
        } else {
            "--"
        }
        tvPowerPrimary.text = primaryPwr
        tvPowerFg.text = "前: $fgPwrStr"
        tvPowerBg.text = "后: $bgPwrStr"

        // 温度列
        tvTempPrimary.text = String.format(Locale.getDefault(), "%.1f ℃", item.avgTemperature)
        tvTempAvg.text = String.format(Locale.getDefault(), "平均: %.1f ℃", item.avgTemperature)
        tvTempMax.text = String.format(Locale.getDefault(), "最高: %.1f ℃", item.maxTemperature)

        // 4. 工况运行时长（明确区分前台、后台与总时长）
        tvFgDuration.text = formatDurationMs(item.foregroundTimeMs)
        tvBgDuration.text = formatDurationMs(item.backgroundTimeMs)
        tvTotalDuration.text = formatDurationMs(item.foregroundTimeMs + item.backgroundTimeMs)

        // 5. 电量消耗分配（精确到小数点后三位）
        val totalE = item.energyWh
        tvFgEnergy.text = formatEnergyWithRatio(item.foregroundEnergyWh, totalE)
        tvBgEnergy.text = formatEnergyWithRatio(item.backgroundEnergyWh, totalE)
        tvTotalEnergy.text = formatEnergyValue(totalE)

        // 6. 硬件系统开销
        tvCpuTime.text = formatDurationMs(item.cpuTimeMs)
        tvNetwork.text = formatNetworkBytes(item.networkBytes)
        tvWakelockGps.text = formatWakelockAndGps(item.wakelockTimeMs, item.gpsTimeMs)

        // 7. 统计时间区间、最近活跃与数据来源
        tvPeriodRange.text = periodRangeText ?: formatDefaultPeriodRange()
        tvLastUsed.text = if (item.lastUsedTimeMs > 0L) dateTimeFormatter.format(Date(item.lastUsedTimeMs)) else "--"
        tvSource.text = if (isShizuku) "BatteryStats (系统底座)" else "UsageStats (系统事件)"
    }

    /**
     * 当外部未明确传入统计区间文本时，依据应用最近使用时间戳及累计运行时长客观推算区间。
     *
     * @return 格式化后的统计时间区间文本
     */
    private fun formatDefaultPeriodRange(): String {
        val lastUsed = item.lastUsedTimeMs
        val totalMs = item.foregroundTimeMs + item.backgroundTimeMs
        if (lastUsed <= 0L) return "--"
        return if (totalMs > 0L) {
            val startTs = (lastUsed - totalMs).coerceAtLeast(0L)
            val sStr = timeOnlyFormatter.format(Date(startTs))
            val eStr = timeOnlyFormatter.format(Date(lastUsed))
            "$sStr - $eStr (${formatDurationMs(totalMs)})"
        } else {
            dateTimeFormatter.format(Date(lastUsed))
        }
    }

    /**
     * 查询指定包名在当前系统中的 UID 标识。
     *
     * @param pkgName 应用程序包名
     * @return 应用程序数值 UID，查询失败返回 -1
     */
    private fun getAppUid(pkgName: String): Int {
        return try {
            context.packageManager.getApplicationInfo(pkgName, 0).uid
        } catch (_: PackageManager.NameNotFoundException) {
            -1
        }
    }

    /**
     * 格式化瓦时能量为自适应单位文本，精确到小数点后三位（如 "6.400 mWh"、"0.250 Wh" 或 "0.000 mWh"）。
     *
     * @param wh 能量数值（单位：Wh）
     * @return 格式化后的精确能量文本
     */
    private fun formatEnergyValue(wh: Float): String {
        if (wh <= 0f) return "0.000 mWh"
        val mwh = wh * 1000f
        return if (mwh >= 1000f) {
            String.format(Locale.getDefault(), "%.3f Wh", wh)
        } else {
            String.format(Locale.getDefault(), "%.3f mWh", mwh)
        }
    }

    /**
     * 格式化瓦时能量并附带占总能耗的百分比，能量部分精确到小数点后三位（如 "0.250 Wh (75.8%)" 或 "0.000 mWh (0.0%)"）。
     *
     * @param partWh 部分能耗（单位：Wh）
     * @param totalWh 总体能耗（单位：Wh）
     * @return 格式化后的带占比精确能量文本
     */
    private fun formatEnergyWithRatio(partWh: Float, totalWh: Float): String {
        val energyStr = formatEnergyValue(partWh)
        if (partWh <= 0f || totalWh <= 0f) {
            return "$energyStr (0.0%)"
        }
        val ratio = (partWh / totalWh) * 100f
        return String.format(Locale.getDefault(), "%s (%.1f%%)", energyStr, ratio.coerceIn(0f, 100f))
    }

    /**
     * 格式化功率为文本（如 "1.44 W"、"0.01 W" 或 "--"）。
     * 当功率低于有效统计门槛（0.005W，即保留两位小数无法达到 0.01W）时显示为 "--"，杜绝微小底噪误报与伪零显示。
     *
     * @param watts 功率数值（单位：W）
     * @return 格式化后的功率文本
     */
    private fun formatWattsValue(watts: Float): String {
        return if (watts >= 0.005f) {
            String.format(Locale.getDefault(), "%.2f W", watts)
        } else {
            "--"
        }
    }

    /**
     * 格式化毫秒耗时为紧凑可读字符串（如 "1h 20m"、"15m 32s"、"20s"、"450ms" 或 "--"）。
     *
     * @param ms 待格式化的时间毫秒数
     * @return 格式化后的时间文本
     */
    private fun formatDurationMs(ms: Long): String {
        if (ms <= 0L) return "--"
        if (ms < 1000L) return "${ms}ms"

        val totalSeconds = ms / 1000
        if (totalSeconds < 60) return "${totalSeconds}s"

        val minutes = totalSeconds / 60
        val remainingSeconds = totalSeconds % 60
        if (minutes < 60) {
            return if (remainingSeconds > 0) "${minutes}m ${remainingSeconds}s" else "${minutes}m"
        }

        val hours = minutes / 60
        val remainingMinutes = minutes % 60
        return if (remainingMinutes > 0) "${hours}h ${remainingMinutes}m" else "${hours}h"
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
     * @return 组合展示的文本（如 "锁: 12s | GPS: 0s" 或 "--"）
     */
    private fun formatWakelockAndGps(wakeMs: Long, gpsMs: Long): String {
        if (wakeMs <= 0L && gpsMs <= 0L) return "--"
        val wakeStr = if (wakeMs > 0L) formatDurationMs(wakeMs) else "0s"
        val gpsStr = if (gpsMs > 0L) formatDurationMs(gpsMs) else "0s"
        return "锁: $wakeStr | GPS: $gpsStr"
    }
}
