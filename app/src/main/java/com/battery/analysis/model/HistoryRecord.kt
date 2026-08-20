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

        val localizedStatus = localizeStatusText(context, status, isZh) ?: unknownText
        val localizedHealth = localizeHealthText(context, healthStatus, isZh) ?: unknownText

        val noteText = if (!note.isNullOrBlank()) {
            val noteLabel = if (context != null) context.getString(R.string.history_detail_note) else if (isZh) "📝 快照备注" else "📝 Note"
            "\n$noteLabel: $note"
        } else ""

        val cycleText = cycleCount?.let {
            if (context != null) context.getString(R.string.history_cycle_format, it) else if (isZh) "$it 次" else "$it cycles"
        } ?: unknownText
        val dualCellText = isDualCell?.let { if (it) yesText else noText } ?: noText

        val title = if (context != null) context.getString(R.string.history_details_title) else if (isZh) "【电池检测历史记录】" else "[Battery Inspection History]"
        val labelCat = if (context != null) context.getString(R.string.history_detail_category) else if (isZh) "🏷️ 数据分类" else "🏷️ Category"
        val labelTime = if (context != null) context.getString(R.string.history_detail_time) else if (isZh) "⏱️ 检测时间" else "⏱️ Capture Time"
        val labelLevel = if (context != null) context.getString(R.string.history_detail_level) else if (isZh) "🔋 当前电量" else "🔋 Battery Level"
        val labelStatus = if (context != null) context.getString(R.string.history_detail_status) else if (isZh) "⚡ 工作状态" else "⚡ Status"
        val labelHealthStatus = if (context != null) context.getString(R.string.history_detail_health_status) else if (isZh) "💚 健康状态" else "💚 Health State"
        val labelHealth = if (context != null) context.getString(R.string.history_detail_health) else if (isZh) "✨ 健康度" else "✨ Battery Health"
        val labelCycle = if (context != null) context.getString(R.string.history_detail_cycle) else if (isZh) "🔄 循环次数" else "🔄 Cycle Count"
        val labelTemp = if (context != null) context.getString(R.string.history_detail_temp) else if (isZh) "🌡️ 电池温度" else "🌡️ Temperature"
        val labelVoltage = if (context != null) context.getString(R.string.history_detail_voltage) else if (isZh) "⚡ 电池电压" else "⚡ Voltage"
        val labelCurrent = if (context != null) context.getString(R.string.history_detail_current) else if (isZh) "⚡ 实时电流" else "⚡ Current"
        val labelPower = if (context != null) context.getString(R.string.history_detail_power) else if (isZh) "💡 实时功率" else "💡 Power"
        val labelDesignCap = if (context != null) context.getString(R.string.history_detail_design_cap) else if (isZh) "🔋 设计容量" else "🔋 Design Capacity"
        val labelFullCap = if (context != null) context.getString(R.string.history_detail_full_cap) else if (isZh) "🔋 充满容量" else "🔋 Full Capacity"
        val labelCurrentCap = if (context != null) context.getString(R.string.history_detail_current_cap) else if (isZh) "🔋 当前容量" else "🔋 Current Capacity"
        val labelDualCell = if (context != null) context.getString(R.string.history_detail_dual_cell) else if (isZh) "⚙️ 双电芯" else "⚙️ Dual Cell"
        val labelTech = if (context != null) context.getString(R.string.history_detail_tech) else if (isZh) "🔬 电池技术" else "🔬 Technology"

        return """
        $title
        $labelCat: $localizedCat
        $labelTime: $captureTime$noteText
        $labelLevel: ${level?.let { "$it%" } ?: unknownText}
        $labelStatus: $localizedStatus
        $labelHealthStatus: $localizedHealth
        $labelHealth: ${batteryHealth?.let { String.format(Locale.getDefault(), "%.2f%%", it) } ?: unknownText}
        $labelCycle: $cycleText
        $labelTemp: ${temperature?.let { String.format(Locale.getDefault(), "%.1f ℃", it) } ?: unknownText}
        $labelVoltage: ${voltage?.let { String.format(Locale.getDefault(), "%.0f mV", it) } ?: unknownText}
        $labelCurrent: ${currentNow?.let { String.format(Locale.getDefault(), "%.0f mA", it) } ?: unknownText}
        $labelPower: ${powerWatts?.let { String.format(Locale.getDefault(), "%.2f W", it) } ?: unknownText}
        $labelDesignCap: ${designCapacity?.let { String.format(Locale.getDefault(), "%.1f mAh", it) } ?: unknownText}
        $labelFullCap: ${fullChargeCapacity?.let { String.format(Locale.getDefault(), "%.1f mAh", it) } ?: unknownText}
        $labelCurrentCap: ${currentCapacity?.let { String.format(Locale.getDefault(), "%.1f mAh", it) } ?: unknownText}
        $labelDualCell: $dualCellText
        $labelTech: ${technology ?: unknownText}
        """.trimIndent()
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

        val localizedStatus = localizeStatusText(context, status, isZh) ?: unknownText
        val localizedHealth = localizeHealthText(context, healthStatus, isZh) ?: unknownText

        val labelCat = if (context != null) context.getString(R.string.history_detail_category) else if (isZh) "🏷️ 数据分类" else "🏷️ Category"
        val labelTime = if (context != null) context.getString(R.string.history_detail_time) else if (isZh) "⏱️ 检测时间" else "⏱️ Capture Time"
        val labelNote = if (context != null) context.getString(R.string.history_detail_note) else if (isZh) "📝 快照备注" else "📝 Note"
        val labelLevel = if (context != null) context.getString(R.string.history_detail_level) else if (isZh) "🔋 当前电量" else "🔋 Battery Level"
        val labelStatus = if (context != null) context.getString(R.string.history_detail_status) else if (isZh) "⚡ 工作状态" else "⚡ Status"
        val labelHealthStatus = if (context != null) context.getString(R.string.history_detail_health_status) else if (isZh) "💚 健康状态" else "💚 Health State"
        val labelHealth = if (context != null) context.getString(R.string.history_detail_health) else if (isZh) "✨ 健康度" else "✨ Battery Health"
        val labelCycle = if (context != null) context.getString(R.string.history_detail_cycle) else if (isZh) "🔄 循环次数" else "🔄 Cycle Count"
        val labelTemp = if (context != null) context.getString(R.string.history_detail_temp) else if (isZh) "🌡️ 电池温度" else "🌡️ Temperature"
        val labelVoltage = if (context != null) context.getString(R.string.history_detail_voltage) else if (isZh) "⚡ 电池电压" else "⚡ Voltage"
        val labelCurrent = if (context != null) context.getString(R.string.history_detail_current) else if (isZh) "⚡ 实时电流" else "⚡ Current"
        val labelPower = if (context != null) context.getString(R.string.history_detail_power) else if (isZh) "💡 实时功率" else "💡 Power"
        val labelDesignCap = if (context != null) context.getString(R.string.history_detail_design_cap) else if (isZh) "🔋 设计容量" else "🔋 Design Capacity"
        val labelFullCap = if (context != null) context.getString(R.string.history_detail_full_cap) else if (isZh) "🔋 充满容量" else "🔋 Full Capacity"
        val labelCurrentCap = if (context != null) context.getString(R.string.history_detail_current_cap) else if (isZh) "🔋 当前容量" else "🔋 Current Capacity"
        val labelDualCell = if (context != null) context.getString(R.string.history_detail_dual_cell) else if (isZh) "⚙️ 双电芯" else "⚙️ Dual Cell"
        val labelTech = if (context != null) context.getString(R.string.history_detail_tech) else if (isZh) "🔬 电池技术" else "🔬 Technology"

        val cycleText = cycleCount?.let {
            if (context != null) context.getString(R.string.history_cycle_format, it) else if (isZh) "$it 次" else "$it cycles"
        } ?: unknownText

        val list = mutableListOf<Triple<String, String, String?>>()
        list.add(Triple(labelCat, localizedCat, "#2196F3"))
        list.add(Triple(labelTime, captureTime, null))
        if (!note.isNullOrBlank()) {
            list.add(Triple(labelNote, note, null))
        }
        list.add(Triple(labelLevel, level?.let { "$it%" } ?: unknownText, null))
        list.add(Triple(labelStatus, localizedStatus, null))
        list.add(Triple(labelHealthStatus, localizedHealth, "#10B981"))
        list.add(Triple(labelHealth, batteryHealth?.let { String.format(Locale.getDefault(), "%.2f%%", it) } ?: unknownText, "#10B981"))
        list.add(Triple(labelCycle, cycleText, null))
        list.add(Triple(labelTemp, temperature?.let { String.format(Locale.getDefault(), "%.1f ℃", it) } ?: unknownText, null))
        list.add(Triple(labelVoltage, voltage?.let { String.format(Locale.getDefault(), "%.0f mV", it) } ?: unknownText, null))
        list.add(Triple(labelCurrent, currentNow?.let { String.format(Locale.getDefault(), "%.0f mA", it) } ?: unknownText, null))
        list.add(Triple(labelPower, powerWatts?.let { String.format(Locale.getDefault(), "%.2f W", it) } ?: unknownText, null))
        list.add(Triple(labelDesignCap, designCapacity?.let { String.format(Locale.getDefault(), "%.1f mAh", it) } ?: unknownText, null))
        list.add(Triple(labelFullCap, fullChargeCapacity?.let { String.format(Locale.getDefault(), "%.1f mAh", it) } ?: unknownText, "#2196F3"))
        list.add(Triple(labelCurrentCap, currentCapacity?.let { String.format(Locale.getDefault(), "%.1f mAh", it) } ?: unknownText, null))
        list.add(Triple(labelDualCell, isDualCell?.let { if (it) yesText else noText } ?: noText, null))
        list.add(Triple(labelTech, technology ?: unknownText, null))
        return list
    }

    /**
     * 将电池状态本地化为当前语言。
     *
     * @param context 可选 Android 上下文环境
     * @param raw 原始状态字符串
     * @param isZh 是否为中文环境
     * @return 本地化后的电池状态
     */
    private fun localizeStatusText(context: Context?, raw: String?, isZh: Boolean): String? {
        if (raw.isNullOrBlank()) return null
        if (context != null) {
            return when {
                (raw.contains("Charging", true) && !raw.contains("Not", true) && !raw.contains("Dis", true)) || raw.contains("充电中") -> context.getString(R.string.battery_status_charging)
                raw.contains("Discharging", true) || raw.contains("放电中") -> context.getString(R.string.battery_status_discharging)
                raw.contains("Not charging", true) || raw.contains("未充电") -> context.getString(R.string.battery_status_not_charging)
                raw.contains("Full", true) || raw.contains("充满") -> context.getString(R.string.battery_status_full)
                else -> raw
            }
        }
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
     * @param context 可选 Android 上下文环境
     * @param raw 原始健康状态字符串
     * @param isZh 是否为中文环境
     * @return 本地化后的电池健康状态
     */
    private fun localizeHealthText(context: Context?, raw: String?, isZh: Boolean): String? {
        if (raw.isNullOrBlank()) return null
        if (context != null) {
            return when {
                raw.contains("Good", true) || raw.contains("良好") -> context.getString(R.string.battery_health_good)
                raw.contains("Overheat", true) || raw.contains("过热") -> context.getString(R.string.battery_health_overheat)
                raw.contains("Dead", true) || raw.contains("损坏") -> context.getString(R.string.battery_health_dead)
                raw.contains("Over voltage", true) || raw.contains("过压") || raw.contains("电压过高") -> context.getString(R.string.battery_health_over_voltage)
                raw.contains("Cold", true) || raw.contains("低温") || raw.contains("过冷") -> context.getString(R.string.battery_health_cold)
                else -> raw
            }
        }
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
