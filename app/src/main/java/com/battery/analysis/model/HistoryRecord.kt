package com.battery.analysis.model

/**
 * 历史电池检测记录数据实体类。
 * 用于本地持久化存储电池快照数据，按系统api、Shizuku、错误报告三大数据源分类存储，包含抓取时间及全部核心电池参数指标。
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
     * 将该历史记录的所有参数格式化为便于查看与复制的详细文本。
     *
     * @return 包含全量指标与分类信息的格式化多行字符串
     */
    fun formatFullDetails(): String {
        val noteText = if (!note.isNullOrBlank()) "\n📝 备注: $note" else ""
        return """
            【电池检测历史记录】
            🏷️ 数据分类: $category
            📅 检测时间: $captureTime$noteText
            🔋 当前电量: ${level?.let { "$it%" } ?: "未知"}
            ⚡ 工作状态: ${status ?: "未知"}
            💚 健康状态: ${healthStatus ?: "未知"}
            ✨ 健康度: ${batteryHealth?.let { String.format("%.2f%%", it) } ?: "未知"}
            🔄 循环次数: ${cycleCount?.let { "$it 次" } ?: "未知"}
            🌡️ 电池温度: ${temperature?.let { String.format("%.1f ℃", it) } ?: "未知"}
            ⚡ 电池电压: ${voltage?.let { String.format("%.0f mV", it) } ?: "未知"}
            📈 实时电流: ${currentNow?.let { String.format("%.0f mA", it) } ?: "未知"}
            💡 实时功率: ${powerWatts?.let { String.format("%.2f W", it) } ?: "未知"}
            🔋 设计容量: ${designCapacity?.let { String.format("%.1f mAh", it) } ?: "未知"}
            🔋 充满容量: ${fullChargeCapacity?.let { String.format("%.1f mAh", it) } ?: "未知"}
            🔋 当前容量: ${currentCapacity?.let { String.format("%.1f mAh", it) } ?: "未知"}
            ⚙️ 双电芯: ${isDualCell?.let { if (it) "是" else "否" } ?: "否"}
            🔬 电池技术: ${technology ?: "未知"}
        """.trimIndent()
    }

    /**
     * 获取结构化的键值对列表，用于在详情弹窗中左右分开对齐排列展示。
     *
     * @return 包含 (键名, 值文本, 可选文本高亮色HEX) 的三元组列表
     */
    fun getDetailPairs(): List<Triple<String, String, String?>> {
        val list = mutableListOf<Triple<String, String, String?>>()
        list.add(Triple("🏷️ 数据分类", category, "#2196F3"))
        list.add(Triple("📅 检测时间", captureTime, null))
        if (!note.isNullOrBlank()) {
            list.add(Triple("📝 快照备注", note, null))
        }
        list.add(Triple("🔋 当前电量", level?.let { "$it%" } ?: "未知", null))
        list.add(Triple("⚡ 工作状态", status ?: "未知", null))
        list.add(Triple("💚 健康状态", healthStatus ?: "未知", "#10B981"))
        list.add(Triple("✨ 健康度", batteryHealth?.let { String.format("%.2f%%", it) } ?: "未知", "#10B981"))
        list.add(Triple("🔄 循环次数", cycleCount?.let { "$it 次" } ?: "未知", null))
        list.add(Triple("🌡️ 电池温度", temperature?.let { String.format("%.1f ℃", it) } ?: "未知", null))
        list.add(Triple("⚡ 电池电压", voltage?.let { String.format("%.0f mV", it) } ?: "未知", null))
        list.add(Triple("📈 实时电流", currentNow?.let { String.format("%.0f mA", it) } ?: "未知", null))
        list.add(Triple("💡 实时功率", powerWatts?.let { String.format("%.2f W", it) } ?: "未知", null))
        list.add(Triple("🔋 设计容量", designCapacity?.let { String.format("%.1f mAh", it) } ?: "未知", null))
        list.add(Triple("🔋 充满容量", fullChargeCapacity?.let { String.format("%.1f mAh", it) } ?: "未知", "#2196F3"))
        list.add(Triple("🔋 当前容量", currentCapacity?.let { String.format("%.1f mAh", it) } ?: "未知", null))
        list.add(Triple("⚙️ 双电芯", isDualCell?.let { if (it) "是" else "否" } ?: "否", null))
        list.add(Triple("🔬 电池技术", technology ?: "未知", null))
        return list
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
                java.util.Locale.getDefault()
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
