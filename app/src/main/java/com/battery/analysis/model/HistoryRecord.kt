package com.battery.analysis.model

import android.content.Context
import com.battery.analysis.R
import java.util.Locale

/**
 * 历史电池检测记录数据实体类。
 * 用于本地持久化存储电池快照数据，按系统api、Shizuku、错误报告三大数据源分类存储，包含抓取时间及全部核心电池参数指标，
 * 并全面支持根据当前多语言环境（中文/英文）格式化详细日志与键值对。
 */
data class HistoryRecord(
    /**
     * 唯一记录 ID（采用创建时的时间戳毫秒值）。
     */
    val id: Long = System.currentTimeMillis(),

    /**
     * 抓取/采集报告的日期时间（如 "2026-08-18 15:30:22"）。
     */
    val captureTime: String,

    /**
     * 数据分类与来源渠道（固定为 "系统api"、"Shizuku" 或 "错误报告"）。
     */
    val source: String,

    /**
     * 数据分类名称（与 source 保持一致，便于分类筛选展示）。
     */
    val category: String = source,

    /**
     * 快照备注说明（可选）。
     */
    val note: String? = null,

    /**
     * 电池健康度百分比（如 98.50 表示 98.50%）。
     */
    val batteryHealth: Float? = null,

    /**
     * 电池健康状态描述（如 "良好"、"过热" 等）。
     */
    val healthStatus: String? = null,

    /**
     * 电池电量百分比（如 85%）。
     */
    val level: Int? = null,

    /**
     * 电池工作状态（如 "充电中"、"放电中"）。
     */
    val status: String? = null,

    /**
     * 电池总设计容量（单位：mAh）。
     */
    val designCapacity: Float? = null,

    /**
     * 电池当前剩余容量（单位：mAh）。
     */
    val currentCapacity: Float? = null,

    /**
     * 电池充满电时的容量（单位：mAh）。
     */
    val fullChargeCapacity: Float? = null,

    /**
     * 电池循环次数。
     */
    val cycleCount: Int? = null,

    /**
     * 电池温度（单位：℃）。
     */
    val temperature: Float? = null,

    /**
     * 电池电压（单位：mV）。
     */
    val voltage: Float? = null,

    /**
     * 当前电流（单位：mA）。
     */
    val currentNow: Float? = null,

    /**
     * 实时功率（单位：W）。
     */
    val powerWatts: Float? = null,

    /**
     * 是否为双电芯结构。
     */
    val isDualCell: Boolean? = null,

    /**
     * 电池技术类型（如 Li-ion）。
     */
    val technology: String? = null
) {
    /**
     * 将该历史记录的所有参数格式化为便于查看与复制的详细文本（支持自适应中英文）。
     *
     * @param context 可选 Android 上下文，若传入则通过资源系统获取文本，若为 null 则根据系统 Locale 自适应
     * @return 包含全量指标与分类信息的格式化多行字符串
     */
    fun formatFullDetails(context: Context? = null): String {
        val isZh = context?.resources?.configuration?.locales?.get(0)?.language?.startsWith("zh")
            ?: Locale.getDefault().language.startsWith("zh")

        val unknownText = if (context != null) context.getString(R.string.unknown) else if (isZh) "未知" else "Unknown"
        val yesText = if (context != null) context.getString(R.string.yes) else if (isZh) "是" else "Yes"
        val noText = if (context != null) context.getString(R.string.no) else if (isZh) "否" else "No"

        val localizedCat = when (category) {
            "系统api" -> if (context != null) context.getString(R.string.tab_normal_api) else if (isZh) "系统api" else "System API"
            "Shizuku" -> "Shizuku"
            "错误报告" -> if (context != null) context.getString(R.string.tab_bugreport) else if (isZh) "错误报告" else "Bugreport"
            else -> category
        }

        val localizedStatus = localizeStatusText(status, isZh) ?: unknownText
        val localizedHealth = localizeHealthText(healthStatus, isZh) ?: unknownText

        val noteText = if (!note.isNullOrBlank()) {
            val noteLabel = if (isZh) "📝 备注" else "📝 Note"
            "\n$noteLabel: $note"
        } else ""

        val cycleText = cycleCount?.let { if (isZh) "$it 次" else "$it cycles" } ?: unknownText
        val dualCellText = isDualCell?.let { if (it) yesText else noText } ?: noText

        return if (isZh) {
            """
            【电池检测历史记录】
            🏷️ 数据分类: $localizedCat
            📅 检测时间: $captureTime$noteText
            🔋 当前电量: ${level?.let { "$it%" } ?: unknownText}
            ⚡ 工作状态: $localizedStatus
            💚 健康状态: $localizedHealth
            ✨ 健康度: ${batteryHealth?.let { String.format(Locale.getDefault(), "%.2f%%", it) } ?: unknownText}
            🔄 循环次数: $cycleText
            🌡️ 电池温度: ${temperature?.let { String.format(Locale.getDefault(), "%.1f ℃", it) } ?: unknownText}
            ⚡ 电池电压: ${voltage?.let { String.format(Locale.getDefault(), "%.0f mV", it) } ?: unknownText}
            📈 实时电流: ${currentNow?.let { String.format(Locale.getDefault(), "%.0f mA", it) } ?: unknownText}
            💡 实时功率: ${powerWatts?.let { String.format(Locale.getDefault(), "%.2f W", it) } ?: unknownText}
            🔋 设计容量: ${designCapacity?.let { String.format(Locale.getDefault(), "%.1f mAh", it) } ?: unknownText}
            🔋 充满容量: ${fullChargeCapacity?.let { String.format(Locale.getDefault(), "%.1f mAh", it) } ?: unknownText}
            🔋 当前容量: ${currentCapacity?.let { String.format(Locale.getDefault(), "%.1f mAh", it) } ?: unknownText}
            ⚙️ 双电芯: $dualCellText
            🔬 电池技术: ${technology ?: unknownText}
            """.trimIndent()
        } else {
            """
            [Battery Inspection History]
            🏷️ Category: $localizedCat
            📅 Capture Time: $captureTime$noteText
            🔋 Battery Level: ${level?.let { "$it%" } ?: unknownText}
            ⚡ Status: $localizedStatus
            💚 Health State: $localizedHealth
            ✨ Battery Health: ${batteryHealth?.let { String.format(Locale.getDefault(), "%.2f%%", it) } ?: unknownText}
            🔄 Cycle Count: $cycleText
            🌡️ Temperature: ${temperature?.let { String.format(Locale.getDefault(), "%.1f ℃", it) } ?: unknownText}
            ⚡ Voltage: ${voltage?.let { String.format(Locale.getDefault(), "%.0f mV", it) } ?: unknownText}
            📈 Current: ${currentNow?.let { String.format(Locale.getDefault(), "%.0f mA", it) } ?: unknownText}
            💡 Power: ${powerWatts?.let { String.format(Locale.getDefault(), "%.2f W", it) } ?: unknownText}
            🔋 Design Capacity: ${designCapacity?.let { String.format(Locale.getDefault(), "%.1f mAh", it) } ?: unknownText}
            🔋 Full Capacity: ${fullChargeCapacity?.let { String.format(Locale.getDefault(), "%.1f mAh", it) } ?: unknownText}
            🔋 Current Capacity: ${currentCapacity?.let { String.format(Locale.getDefault(), "%.1f mAh", it) } ?: unknownText}
            ⚙️ Dual Cell: $dualCellText
            🔬 Technology: ${technology ?: unknownText}
            """.trimIndent()
        }
    }

    /**
     * 获取结构化的键值对列表，用于在详情弹窗中左右分开对齐排列展示（支持多语言）。
     *
     * @param context 可选 Android 上下文
     * @return 包含 (键名, 值文本, 可选文本高亮色HEX) 的三元组列表
     */
    fun getDetailPairs(context: Context? = null): List<Triple<String, String, String?>> {
        val isZh = context?.resources?.configuration?.locales?.get(0)?.language?.startsWith("zh")
            ?: Locale.getDefault().language.startsWith("zh")

        val unknownText = if (context != null) context.getString(R.string.unknown) else if (isZh) "未知" else "Unknown"
        val yesText = if (context != null) context.getString(R.string.yes) else if (isZh) "是" else "Yes"
        val noText = if (context != null) context.getString(R.string.no) else if (isZh) "否" else "No"

        val localizedCat = when (category) {
            "系统api" -> if (context != null) context.getString(R.string.tab_normal_api) else if (isZh) "系统api" else "System API"
            "Shizuku" -> "Shizuku"
            "错误报告" -> if (context != null) context.getString(R.string.tab_bugreport) else if (isZh) "错误报告" else "Bugreport"
            else -> category
        }

        val localizedStatus = localizeStatusText(status, isZh) ?: unknownText
        val localizedHealth = localizeHealthText(healthStatus, isZh) ?: unknownText

        val list = mutableListOf<Triple<String, String, String?>>()
        list.add(Triple(if (isZh) "🏷️ 数据分类" else "🏷️ Category", localizedCat, "#2196F3"))
        list.add(Triple(if (isZh) "📅 检测时间" else "📅 Capture Time", captureTime, null))
        if (!note.isNullOrBlank()) {
            list.add(Triple(if (isZh) "📝 快照备注" else "📝 Note", note, null))
        }
        list.add(Triple(if (isZh) "🔋 当前电量" else "🔋 Battery Level", level?.let { "$it%" } ?: unknownText, null))
        list.add(Triple(if (isZh) "⚡ 工作状态" else "⚡ Status", localizedStatus, null))
        list.add(Triple(if (isZh) "💚 健康状态" else "💚 Health State", localizedHealth, "#10B981"))
        list.add(Triple(if (isZh) "✨ 健康度" else "✨ Battery Health", batteryHealth?.let { String.format(Locale.getDefault(), "%.2f%%", it) } ?: unknownText, "#10B981"))
        list.add(Triple(if (isZh) "🔄 循环次数" else "🔄 Cycle Count", cycleCount?.let { if (isZh) "$it 次" else "$it cycles" } ?: unknownText, null))
        list.add(Triple(if (isZh) "🌡️ 电池温度" else "🌡️ Temperature", temperature?.let { String.format(Locale.getDefault(), "%.1f ℃", it) } ?: unknownText, null))
        list.add(Triple(if (isZh) "⚡ 电池电压" else "⚡ Voltage", voltage?.let { String.format(Locale.getDefault(), "%.0f mV", it) } ?: unknownText, null))
        list.add(Triple(if (isZh) "📈 实时电流" else "📈 Current", currentNow?.let { String.format(Locale.getDefault(), "%.0f mA", it) } ?: unknownText, null))
        list.add(Triple(if (isZh) "💡 实时功率" else "💡 Power", powerWatts?.let { String.format(Locale.getDefault(), "%.2f W", it) } ?: unknownText, null))
        list.add(Triple(if (isZh) "🔋 设计容量" else "🔋 Design Capacity", designCapacity?.let { String.format(Locale.getDefault(), "%.1f mAh", it) } ?: unknownText, null))
        list.add(Triple(if (isZh) "🔋 充满容量" else "🔋 Full Capacity", fullChargeCapacity?.let { String.format(Locale.getDefault(), "%.1f mAh", it) } ?: unknownText, "#2196F3"))
        list.add(Triple(if (isZh) "🔋 当前容量" else "🔋 Current Capacity", currentCapacity?.let { String.format(Locale.getDefault(), "%.1f mAh", it) } ?: unknownText, null))
        list.add(Triple(if (isZh) "⚙️ 双电芯" else "⚙️ Dual Cell", isDualCell?.let { if (it) yesText else noText } ?: noText, null))
        list.add(Triple(if (isZh) "🔬 电池技术" else "🔬 Technology", technology ?: unknownText, null))
        return list
    }

    /**
     * 将电池状态本地化为当前语言。
     *
     * @param raw 原始状态字符串
     * @param isZh 是否为中文环境
     * @return 本地化后的电池状态
     */
    private fun localizeStatusText(raw: String?, isZh: Boolean): String? {
        if (raw.isNullOrBlank()) return null
        return if (isZh) {
            when {
                raw.contains("Charging", true) && !raw.contains("Not", true) && !raw.contains("Dis", true) -> "充电中"
                raw.contains("Discharging", true) -> "放电中"
                raw.contains("Not charging", true) -> "未充电"
                raw.contains("Full", true) -> "已充满"
                else -> raw
            }
        } else {
            when {
                raw.contains("充电中", true) -> "Charging"
                raw.contains("放电中", true) -> "Discharging"
                raw.contains("未充电", true) -> "Not Charging"
                raw.contains("充满", true) -> "Full"
                else -> raw
            }
        }
    }

    /**
     * 将电池健康状态本地化为当前语言。
     *
     * @param raw 原始健康状态字符串
     * @param isZh 是否为中文环境
     * @return 本地化后的电池健康状态
     */
    private fun localizeHealthText(raw: String?, isZh: Boolean): String? {
        if (raw.isNullOrBlank()) return null
        return if (isZh) {
            when {
                raw.contains("Good", true) -> "良好"
                raw.contains("Overheat", true) -> "过热"
                raw.contains("Dead", true) -> "损坏"
                raw.contains("Over voltage", true) -> "过压"
                raw.contains("Cold", true) -> "低温"
                else -> raw
            }
        } else {
            when {
                raw.contains("良好", true) -> "Good"
                raw.contains("过热", true) -> "Overheat"
                raw.contains("损坏", true) -> "Dead"
                raw.contains("过压", true) -> "Over Voltage"
                raw.contains("低温", true) -> "Cold"
                else -> raw
            }
        }
    }

    /**
     * 将该历史记录对象反向转换为 [BatteryInfo] 实体对象。
     *
     * @param customSource 自定义数据源名称
     * @return 转换构建的 [BatteryInfo] 实例
     */
    fun toBatteryInfo(customSource: String = "错误报告 (历史快照)"): BatteryInfo {
        return BatteryInfo(
            batteryHealth = this.batteryHealth,
            healthStatus = this.healthStatus,
            level = this.level,
            status = this.status,
            designCapacity = this.designCapacity,
            currentCapacity = this.currentCapacity,
            fullChargeCapacity = this.fullChargeCapacity,
            cycleCount = this.cycleCount,
            temperature = this.temperature,
            voltage = this.voltage,
            currentNow = this.currentNow,
            powerWatts = this.powerWatts,
            isDualCell = this.isDualCell,
            technology = this.technology,
            source = customSource,
            captureTime = this.captureTime
        )
    }

    companion object {
        /**
         * 从 [BatteryInfo] 实例快速构建 [HistoryRecord] 历史记录对象。
         *
         * @param info 电池数据信息对象
         * @param category 数据分类名称（"系统api"、"Shizuku" 或 "错误报告"）
         * @param id 自定义唯一 ID（若并发批量保存可传入微秒差异时间戳）
         * @param note 可选备注
         * @return 构建成功的 [HistoryRecord] 实例
         */
        fun fromBatteryInfo(
            info: BatteryInfo,
            category: String = "系统api",
            id: Long = System.currentTimeMillis(),
            note: String? = null
        ): HistoryRecord {
            val time = info.captureTime ?: java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                Locale.getDefault()
            ).format(java.util.Date())

            return HistoryRecord(
                id = id,
                captureTime = time,
                source = category,
                category = category,
                note = note,
                batteryHealth = info.batteryHealth,
                healthStatus = info.healthStatus,
                level = info.level,
                status = info.status,
                designCapacity = info.designCapacity,
                currentCapacity = info.currentCapacity,
                fullChargeCapacity = info.fullChargeCapacity,
                cycleCount = info.cycleCount,
                temperature = info.temperature,
                voltage = info.voltage,
                currentNow = info.currentNow,
                powerWatts = info.powerWatts,
                isDualCell = info.isDualCell,
                technology = info.technology
            )
        }
    }
}
