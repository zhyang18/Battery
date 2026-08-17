package com.example.batteryapp

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * 包装输入流以统计读取进度并按时间节流回调百分比。
 *
 * @property wrapped 被包装的原始输入流
 * @property totalBytes 文件总字节数
 * @property onProgress 进度百分比变化时的回调
 */
class CountingInputStream(
    private val wrapped: InputStream,
    private val totalBytes: Long,
    private val onProgress: (Int) -> Unit
) : InputStream() {
    private var bytesRead: Long = 0
    private var lastReportTime: Long = 0
    private var lastPercent = -1

    override fun read(): Int {
        val b = wrapped.read()
        if (b != -1) {
            bytesRead++
            notifyProgressThrottled()
        }
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val count = wrapped.read(b, off, len)
        if (count > 0) {
            bytesRead += count
            notifyProgressThrottled()
        }
        return count
    }

    private fun notifyProgressThrottled() {
        if (totalBytes > 0) {
            val now = System.currentTimeMillis()
            if (now - lastReportTime > 120L) {
                lastReportTime = now
                val percent = ((bytesRead * 100) / totalBytes).toInt().coerceIn(0, 99)
                if (percent != lastPercent) {
                    lastPercent = percent
                    onProgress(percent)
                }
            }
        }
    }

    override fun close() {
        wrapped.close()
    }
}

/**
 * 负责高性能、极速解析安卓系统错误报告（Bugreport）中 getHealthInfo 结构体的解析器。
 * 具备 64KB 大缓冲区、快速行过滤与提前早停（Early Termination）机制，并输出详细调试日志。
 */
class BugreportParser {

    companion object {
        private const val TAG = "BugreportParser"
    }

    /**
     * 从选定的文件 URI 流式极速解析错误报告日志并提取 getHealthInfo 表格条目列表。
     *
     * @param context 应用程序上下文
     * @param uri 用户选中的错误报告文件 URI
     * @param onProgress 进度百分比回调 (0~99)
     * @return 解析得到的 [HealthInfoItem] 列表，解析失败或被取消时返回空列表
     */
    suspend fun parseHealthInfoTable(
        context: Context,
        uri: Uri,
        onProgress: (Int) -> Unit = {}
    ): List<HealthInfoItem> = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver
            val totalBytes = try {
                contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
            } catch (e: Exception) {
                0L
            }

            Log.d(TAG, "===> 开始解析错误报告: URI = $uri, 文件总大小 = ${totalBytes / 1024} KB")

            val rawStream = contentResolver.openInputStream(uri) ?: run {
                Log.e(TAG, "打开输入流失败: $uri")
                return@withContext emptyList()
            }
            val countingStream = CountingInputStream(rawStream, totalBytes, onProgress)

            val fileName = getFileName(context, uri).lowercase()
            Log.d(TAG, "目标文件名: $fileName")
            if (fileName.endsWith(".zip")) {
                parseZipStream(countingStream)
            } else {
                parseTextStream(countingStream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析错误报告发生异常", e)
            emptyList()
        }
    }

    /**
     * 从 ZIP 压缩流中寻找并极速解析主错误报告文本文件。
     *
     * @param inputStream 带有进度统计的输入流
     * @return 解析出的 [HealthInfoItem] 列表
     */
    private suspend fun parseZipStream(inputStream: InputStream): List<HealthInfoItem> {
        val zipIn = ZipInputStream(inputStream)
        var entry = zipIn.nextEntry
        while (entry != null && coroutineContext.isActive) {
            val name = entry.name.lowercase()
            // 过滤：仅提取主 bugreport 文本，跳过无用子目录与非目标日志
            if (!entry.isDirectory && (name.startsWith("bugreport-") && name.endsWith(".txt") || (!name.contains("/") && name.endsWith(".txt")))) {
                Log.d(TAG, "找到主日志条目: ${entry.name}, 开始流式解析...")
                val reader = BufferedReader(InputStreamReader(zipIn, StandardCharsets.UTF_8), 65536)
                val items = parseFromReader(reader)
                if (items.isNotEmpty()) {
                    zipIn.closeEntry()
                    zipIn.close()
                    Log.d(TAG, "成功解析到 ${items.size} 个有效电池字段！")
                    return items
                }
            }
            zipIn.closeEntry()
            entry = zipIn.nextEntry
        }
        zipIn.close()
        Log.w(TAG, "ZIP 遍历完成，未找到有效数据")
        return emptyList()
    }

    /**
     * 极速流式解析纯文本错误报告。
     *
     * @param inputStream 带有进度统计的输入流
     * @return 解析出的 [HealthInfoItem] 列表
     */
    private suspend fun parseTextStream(inputStream: InputStream): List<HealthInfoItem> {
        Log.d(TAG, "开始解析纯文本日志...")
        val reader = BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8), 65536)
        val items = parseFromReader(reader)
        reader.close()
        inputStream.close()
        Log.d(TAG, "文本日志解析完成，获取到 ${items.size} 个字段")
        return items
    }

    /**
     * 逐行读取流并深度解析 getHealthInfo 结构体，配备快速行过滤与提前早停机制。
     *
     * @param reader 缓冲字符读取流
     * @return 包含三列字段信息的 [HealthInfoItem] 列表
     */
    private suspend fun parseFromReader(reader: BufferedReader): List<HealthInfoItem> {
        var rawDesignCap: Long? = null
        var rawFullCharge: Long? = null
        var rawChargeCounter: Long? = null
        var rawCycleCount: Int? = null
        var rawLevel: Int? = null
        var rawVoltage: Long? = null
        var rawCurrent: Long? = null
        var rawTemp: Float? = null
        var rawStatus: String? = null
        var rawHealth: String? = null
        var rawCapacityLevel: String? = null
        var rawTimeToFull: Long? = null
        var rawTechnology: String? = null
        var rawPresent: Boolean? = null

        var inHealthInfoSection = false
        var currentSection = ""
        var hasFoundHealthInfoData = false
        var hasFoundBatterySection = false
        var hasFoundBatteryStatsSection = false
        var line: String?
        var totalLineCount = 0

        while (reader.readLine().also { line = it } != null) {
            totalLineCount++
            if (!coroutineContext.isActive) {
                Log.d(TAG, "协程被取消，终止解析 (已读取 $totalLineCount 行)")
                return emptyList()
            }
            val curLine = line!!
            val trimLine = curLine.trim()

            // 早停检查（Early Termination）：若核心 getHealthInfo / 电池服务段已经完整读取且进入了不相关的后续服务（如 Activity/Window/Meminfo），立即返回！
            if ((hasFoundHealthInfoData || (hasFoundBatterySection && hasFoundBatteryStatsSection)) && 
                trimLine.startsWith("DUMP OF SERVICE") && 
                !trimLine.contains("battery") && 
                !trimLine.contains("Health")) {
                if (rawDesignCap != null || rawFullCharge != null || rawVoltage != null || rawLevel != null) {
                    Log.d(TAG, "触发早停机制（Early Termination）：已获取关键数据并在第 $totalLineCount 行退出！")
                    break
                }
            }

            // 极速行过滤：如果不在关注段落且非特征行，跳过后续匹配
            if (!inHealthInfoSection && currentSection.isEmpty()) {
                val startsWithDump = trimLine.startsWith("DUMP OF SERVICE")
                val isHealthHeader = trimLine.startsWith("HealthInfo") || trimLine.startsWith("Health HAL") || trimLine.contains("getHealthInfo")
                val isCycleBroadcast = trimLine.contains("android.os.extra.CYCLE_COUNT")

                if (!startsWithDump && !isHealthHeader && !isCycleBroadcast) {
                    continue
                }
            }

            // 1. 优先捕获 getHealthInfo / HealthInfo 硬件抽象层段落
            if (trimLine.contains("getHealthInfo", ignoreCase = true) || 
                trimLine.startsWith("HealthInfo_2_") || 
                trimLine.startsWith("HealthInfo:") ||
                trimLine.contains("Health HAL - getHealthInfo") ||
                trimLine.contains("Health HAL:")) {
                inHealthInfoSection = true
                hasFoundHealthInfoData = true
                Log.d(TAG, "进入 getHealthInfo / Health HAL 段落: $trimLine")
            }

            if (inHealthInfoSection) {
                if (trimLine.contains("batteryFullChargeDesignCapacityUah", ignoreCase = true) || 
                    trimLine.contains("battery_design_capacity", ignoreCase = true) ||
                    trimLine.contains("batteryFullChargeDesign", ignoreCase = true)) {
                    val v = extractLongValue(trimLine)
                    if (v != null && v > 0 && rawDesignCap == null) {
                        rawDesignCap = v
                        Log.d(TAG, "  -> 捕获设计容量: $v")
                    }
                }
                if (trimLine.contains("batteryFullChargeCapacityUah", ignoreCase = true) || 
                    trimLine.contains("batteryFullCharge", ignoreCase = true) || 
                    trimLine.contains("battery_full_charge", ignoreCase = true)) {
                    val v = extractLongValue(trimLine)
                    if (v != null && v > 0 && rawFullCharge == null) {
                        rawFullCharge = v
                        Log.d(TAG, "  -> 捕获充满电容量: $v")
                    }
                }
                if (trimLine.contains("batteryCycleCount", ignoreCase = true) || 
                    trimLine.contains("battery_cycle_count", ignoreCase = true)) {
                    val v = extractIntValue(trimLine)
                    if (v != null && v > 0 && rawCycleCount == null) {
                        rawCycleCount = v
                        Log.d(TAG, "  -> 捕获循环次数: $v")
                    }
                }
                if (trimLine.contains("batteryChargeCounter", ignoreCase = true) || 
                    trimLine.contains("battery_charge_counter", ignoreCase = true)) {
                    val v = extractLongValue(trimLine)
                    if (v != null && v > 0 && rawChargeCounter == null) {
                        rawChargeCounter = v
                        Log.d(TAG, "  -> 捕获剩余容量: $v")
                    }
                }
                if (trimLine.contains("batteryLevel", ignoreCase = true) || 
                    trimLine.contains("battery_level", ignoreCase = true)) {
                    val v = extractIntValue(trimLine)
                    if (v != null && v in 0..100 && rawLevel == null) {
                        rawLevel = v
                        Log.d(TAG, "  -> 捕获电量: $v%")
                    }
                }
                if (trimLine.contains("batteryVoltage", ignoreCase = true) || 
                    trimLine.contains("battery_voltage", ignoreCase = true)) {
                    val v = extractLongValue(trimLine)
                    if (v != null && rawVoltage == null) {
                        rawVoltage = v
                        Log.d(TAG, "  -> 捕获电压: $v")
                    }
                }
                if (trimLine.contains("batteryCurrent", ignoreCase = true) || 
                    trimLine.contains("battery_current", ignoreCase = true)) {
                    val v = extractLongValue(trimLine)
                    if (v != null && rawCurrent == null) {
                        rawCurrent = v
                        Log.d(TAG, "  -> 捕获电流: $v")
                    }
                }
                if (trimLine.contains("batteryTemperature", ignoreCase = true) || 
                    trimLine.contains("battery_temperature", ignoreCase = true)) {
                    val v = extractFloatValue(trimLine)
                    if (v != null && v > 0 && rawTemp == null) {
                        rawTemp = v
                        Log.d(TAG, "  -> 捕获温度: $v")
                    }
                }
                if (trimLine.contains("batteryStatus", ignoreCase = true) || 
                    trimLine.contains("battery_status", ignoreCase = true)) {
                    if (rawStatus == null) rawStatus = extractStringValue(trimLine)
                }
                if (trimLine.contains("batteryHealth", ignoreCase = true) || 
                    trimLine.contains("battery_health", ignoreCase = true)) {
                    if (rawHealth == null) rawHealth = extractStringValue(trimLine)
                }
                if (trimLine.contains("batteryCapacityLevel", ignoreCase = true) || 
                    trimLine.contains("battery_capacity_level", ignoreCase = true)) {
                    if (rawCapacityLevel == null) rawCapacityLevel = extractStringValue(trimLine)
                }
                if (trimLine.contains("batteryChargeTimeToFullNowSeconds", ignoreCase = true) || 
                    trimLine.contains("batteryChargeTimeToFullNow", ignoreCase = true)) {
                    val v = extractLongValue(trimLine)
                    if (v != null && rawTimeToFull == null) rawTimeToFull = v
                }
                if (trimLine.contains("batteryTechnology", ignoreCase = true) || 
                    trimLine.contains("battery_technology", ignoreCase = true)) {
                    val tech = extractStringValue(trimLine)
                    if (tech.isNotEmpty() && rawTechnology == null) rawTechnology = tech
                }
                if (trimLine.contains("batteryPresent", ignoreCase = true) || 
                    trimLine.contains("battery_present", ignoreCase = true)) {
                    val pres = extractStringValue(trimLine).lowercase()
                    if (rawPresent == null) rawPresent = (pres == "true" || pres == "1")
                }
            }

            // 2. 监听段落切换 (DUMP OF SERVICE)
            if (trimLine.startsWith("DUMP OF SERVICE battery:")) {
                currentSection = "battery"
                hasFoundBatterySection = true
                Log.d(TAG, "进入 DUMP OF SERVICE battery 段落")
            } else if (trimLine.startsWith("DUMP OF SERVICE batterystats:")) {
                currentSection = "batterystats"
                hasFoundBatteryStatsSection = true
                Log.d(TAG, "进入 DUMP OF SERVICE batterystats 段落")
            } else if (trimLine.startsWith("DUMP OF SERVICE") || trimLine.startsWith("------ ")) {
                if (!trimLine.contains("battery") && !trimLine.contains("Health")) {
                    currentSection = ""
                    inHealthInfoSection = false
                }
            }

            if (currentSection == "battery") {
                if (trimLine.startsWith("level:") && rawLevel == null) {
                    rawLevel = trimLine.substringAfter("level:").trim().toIntOrNull()
                }
                if (trimLine.startsWith("voltage:") && rawVoltage == null) {
                    rawVoltage = trimLine.substringAfter("voltage:").trim().toLongOrNull()
                }
                if (trimLine.startsWith("temperature:") && rawTemp == null) {
                    rawTemp = trimLine.substringAfter("temperature:").trim().toFloatOrNull()
                }
                if (trimLine.startsWith("status:") && rawStatus == null) {
                    rawStatus = trimLine.substringAfter("status:").trim()
                }
                if (trimLine.startsWith("health:") && rawHealth == null) {
                    rawHealth = trimLine.substringAfter("health:").trim()
                }
                if (trimLine.startsWith("technology:") && rawTechnology == null) {
                    rawTechnology = trimLine.substringAfter("technology:").trim()
                }
                if (trimLine.startsWith("Charge counter:") && rawChargeCounter == null) {
                    rawChargeCounter = trimLine.substringAfter("Charge counter:").trim().toLongOrNull()
                }
                if ((trimLine.contains("cycle count:") || trimLine.contains("mCycleCount:")) && rawCycleCount == null) {
                    rawCycleCount = trimLine.substringAfter(":").trim().toIntOrNull()
                }
            }

            if (currentSection == "batterystats") {
                if ((trimLine.startsWith("Estimated battery capacity:") || trimLine.startsWith("Capacity:")) && rawDesignCap == null) {
                    val raw = trimLine.substringAfter(":").replace("mAh", "").trim().toLongOrNull()
                    if (raw != null && raw > 0) rawDesignCap = raw
                }
                if ((trimLine.startsWith("Learned battery capacity:") || trimLine.startsWith("mLearnedCap:")) && rawFullCharge == null) {
                    val raw = trimLine.substringAfter(":").replace("mAh", "").trim().toLongOrNull()
                    if (raw != null && raw > 0) rawFullCharge = raw
                }
            }

            if (trimLine.contains("android.os.extra.CYCLE_COUNT=") && rawCycleCount == null) {
                val match = Regex("(?i)android\\.os\\.extra\\.CYCLE_COUNT=(\\d+)").find(trimLine)
                if (match != null) rawCycleCount = match.groupValues[1].toIntOrNull()
            }
        }

        // 3. 构建规范的三列表格条目列表
        val resultList = mutableListOf<HealthInfoItem>()

        // 3.1 设计容量
        if (rawDesignCap != null) {
            val mah = if (rawDesignCap < 100000) rawDesignCap else rawDesignCap / 1000
            resultList.add(
                HealthInfoItem(
                    rawField = "batteryFullChargeDesignCapacityUah",
                    displayValue = "$mah mAh ($rawDesignCap µAh)",
                    meaning = "电池出厂物理规格设计标称容量"
                )
            )
        }

        // 3.2 满电学习容量
        if (rawFullCharge != null) {
            val mah = if (rawFullCharge < 100000) rawFullCharge else rawFullCharge / 1000
            resultList.add(
                HealthInfoItem(
                    rawField = "batteryFullCharge (batteryFullChargeCapacityUah)",
                    displayValue = "$mah mAh ($rawFullCharge µAh)",
                    meaning = "硬件电量计当前学习到的电池老化满充容量"
                )
            )
        }

        // 3.3 计算健康度
        if (rawFullCharge != null && rawDesignCap != null && rawDesignCap > 0) {
            val fMah = if (rawFullCharge < 100000) rawFullCharge.toFloat() else rawFullCharge / 1000f
            val dMah = if (rawDesignCap < 100000) rawDesignCap.toFloat() else rawDesignCap / 1000f
            var healthPercent = (fMah / dMah) * 100f
            if (healthPercent > 100f) healthPercent = 100f
            resultList.add(
                HealthInfoItem(
                    rawField = "batteryHealthPercent (FullCharge / DesignCapacity)",
                    displayValue = String.format("%.2f%%", healthPercent),
                    meaning = "综合计算所得电池当前物理健康度百分比"
                )
            )
        }

        // 3.4 循环计数
        if (rawCycleCount != null) {
            resultList.add(
                HealthInfoItem(
                    rawField = "batteryCycleCount",
                    displayValue = "$rawCycleCount 次",
                    meaning = "硬件电量计记录的完整充放电循环次数"
                )
            )
        }

        // 3.5 剩余电荷量
        if (rawChargeCounter != null) {
            val mah = if (rawChargeCounter < 100000) rawChargeCounter else rawChargeCounter / 1000
            resultList.add(
                HealthInfoItem(
                    rawField = "batteryChargeCounter",
                    displayValue = "$mah mAh ($rawChargeCounter µAh)",
                    meaning = "电池当前剩余实时可用电荷容量"
                )
            )
        }

        // 3.6 电量百分比
        if (rawLevel != null) {
            resultList.add(
                HealthInfoItem(
                    rawField = "batteryLevel",
                    displayValue = "$rawLevel%",
                    meaning = "电池当前剩余电量百分比"
                )
            )
        }

        // 3.7 电池电压
        if (rawVoltage != null) {
            val mv = when {
                rawVoltage in 2500..9500 -> rawVoltage.toFloat()
                rawVoltage in 2500000..9500000 -> rawVoltage / 1000f
                rawVoltage > 9500 -> rawVoltage / 1000f
                else -> rawVoltage.toFloat()
            }
            resultList.add(
                HealthInfoItem(
                    rawField = "batteryVoltage",
                    displayValue = String.format("%.0f mV (%.2f V)", mv, mv / 1000f),
                    meaning = "电池电芯当前实际物理工作电压"
                )
            )
        }

        // 3.8 瞬时电流
        if (rawCurrent != null && rawCurrent != 0L) {
            val ma = if (Math.abs(rawCurrent) < 100000) rawCurrent.toFloat() else rawCurrent / 1000f
            resultList.add(
                HealthInfoItem(
                    rawField = "batteryCurrent",
                    displayValue = String.format("%.0f mA (%d µA)", ma, rawCurrent),
                    meaning = "电池当前瞬时充放电电流（正为充电，负为放电）"
                )
            )

            // 3.9 联动计算瞬时功率
            if (rawVoltage != null) {
                val mv = when {
                    rawVoltage in 2500..9500 -> rawVoltage.toFloat()
                    rawVoltage in 2500000..9500000 -> rawVoltage / 1000f
                    rawVoltage > 9500 -> rawVoltage / 1000f
                    else -> rawVoltage.toFloat()
                }
                val watts = Math.abs(mv * ma) / 1000000f
                resultList.add(
                    HealthInfoItem(
                        rawField = "batteryPower (Voltage * Current)",
                        displayValue = String.format("%.2f W", watts),
                        meaning = "电池当前瞬时实时功率（瓦特）"
                    )
                )
            }
        }

        // 3.10 电池温度
        if (rawTemp != null) {
            val degC = if (rawTemp > 200) rawTemp / 10f else rawTemp
            resultList.add(
                HealthInfoItem(
                    rawField = "batteryTemperature",
                    displayValue = String.format("%.1f ℃", degC),
                    meaning = "电池电芯当前实时温度"
                )
            )
        }

        // 3.11 电池工作状态
        if (rawStatus != null) {
            val statusDesc = formatStatusDesc(rawStatus)
            resultList.add(
                HealthInfoItem(
                    rawField = "batteryStatus",
                    displayValue = "$rawStatus ($statusDesc)",
                    meaning = "当前电池供电与充放电工作状态"
                )
            )
        }

        // 3.12 电池物理健康
        if (rawHealth != null) {
            val healthDesc = formatHealthDesc(rawHealth)
            resultList.add(
                HealthInfoItem(
                    rawField = "batteryHealth",
                    displayValue = "$rawHealth ($healthDesc)",
                    meaning = "电池硬件物理健康与安全保护状态"
                )
            )
        }

        // 3.13 电量级别
        if (rawCapacityLevel != null) {
            resultList.add(
                HealthInfoItem(
                    rawField = "batteryCapacityLevel",
                    displayValue = rawCapacityLevel,
                    meaning = "系统划分的电量安全等级（如 HIGH / NORMAL / LOW）"
                )
            )
        }

        // 3.14 预估充满时间
        if (rawTimeToFull != null && rawTimeToFull > 0) {
            val mins = rawTimeToFull / 60
            resultList.add(
                HealthInfoItem(
                    rawField = "batteryChargeTimeToFullNowSeconds",
                    displayValue = "$rawTimeToFull 秒 (约 $mins 分钟)",
                    meaning = "硬件电量计预估距离完全充满所需时间"
                )
            )
        }

        // 3.15 电池技术材料
        if (rawTechnology != null) {
            resultList.add(
                HealthInfoItem(
                    rawField = "batteryTechnology",
                    displayValue = "$rawTechnology (锂离子/聚合物)",
                    meaning = "电池制造化学材料与技术类型"
                )
            )
        }

        // 3.16 电池在位状态
        if (rawPresent != null) {
            resultList.add(
                HealthInfoItem(
                    rawField = "batteryPresent",
                    displayValue = if (rawPresent) "true (在位正常)" else "false (未检测到电池)",
                    meaning = "物理电池是否正常连接在位"
                )
            )
        }

        Log.d(TAG, "===> 解析完成，共提取到 ${resultList.size} 项表格数据:")
        for ((idx, item) in resultList.withIndex()) {
            Log.d(TAG, "  [#${idx + 1}] 原始: ${item.rawField} | 展示: ${item.displayValue} | 含义: ${item.meaning}")
        }

        return resultList
    }

    private fun extractLongValue(line: String): Long? {
        val clean = line.replace(",", "").replace(";", "")
        val raw = if (clean.contains("=")) clean.substringAfter("=").trim() else clean.substringAfter(":").trim()
        val numStr = raw.split(" ")[0].trim()
        return numStr.toLongOrNull()
    }

    private fun extractIntValue(line: String): Int? {
        val clean = line.replace(",", "").replace(";", "")
        val raw = if (clean.contains("=")) clean.substringAfter("=").trim() else clean.substringAfter(":").trim()
        val numStr = raw.split(" ")[0].trim()
        return numStr.toIntOrNull()
    }

    private fun extractFloatValue(line: String): Float? {
        val clean = line.replace(",", "").replace(";", "")
        val raw = if (clean.contains("=")) clean.substringAfter("=").trim() else clean.substringAfter(":").trim()
        val numStr = raw.split(" ")[0].trim()
        return numStr.toFloatOrNull()
    }

    private fun extractStringValue(line: String): String {
        val clean = line.replace(",", "").replace(";", "")
        val raw = if (clean.contains("=")) clean.substringAfter("=").trim() else clean.substringAfter(":").trim()
        return raw.split(" ")[0].trim()
    }

    private fun formatStatusDesc(status: String): String {
        val upper = status.uppercase()
        return when {
            upper.contains("CHARGING") && !upper.contains("NOT_CHARGING") && !upper.contains("DISCHARGING") -> "充电中"
            upper.contains("DISCHARGING") -> "放电中"
            upper.contains("NOT_CHARGING") -> "未充电"
            upper.contains("FULL") -> "已充满"
            upper == "2" -> "充电中"
            upper == "3" -> "放电中"
            upper == "4" -> "未充电"
            upper == "5" -> "已充满"
            else -> "未知"
        }
    }

    private fun formatHealthDesc(health: String): String {
        val upper = health.uppercase()
        return when {
            upper.contains("GOOD") || upper == "2" -> "良好"
            upper.contains("OVERHEAT") || upper == "3" -> "过热"
            upper.contains("DEAD") || upper == "4" -> "损坏"
            upper.contains("OVER_VOLTAGE") || upper == "5" -> "电压过高"
            upper.contains("COLD") || upper == "7" -> "过冷"
            upper.contains("UNSPECIFIED_FAILURE") || upper == "6" -> "未知故障"
            else -> "未知"
        }
    }

    private fun getFileName(context: Context, uri: Uri): String {
        var name = ""
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    name = it.getString(nameIndex) ?: ""
                }
            }
        }
        if (name.isEmpty()) {
            name = uri.lastPathSegment ?: "bugreport.zip"
        }
        return name
    }
}
