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
 * 负责解析安卓错误报告（Bugreport）中 getHealthInfo 结构体并输出精简表格与原始数据的解析器。
 */
class BugreportParser {

    companion object {
        private const val TAG = "BugreportParser"
    }

    /**
     * 从选定的文件 URI 流式解析错误报告日志并提取精简表格与原始数据。
     *
     * @param context 应用程序上下文
     * @param uri 用户选中的错误报告文件 URI
     * @param onProgress 进度百分比回调 (0~99)
     * @return 解析得到的 [BugreportResult] 对象，解析失败或被取消时返回 null
     */
    suspend fun parseHealthInfo(
        context: Context,
        uri: Uri,
        onProgress: (Int) -> Unit = {}
    ): BugreportResult? = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver
            val totalBytes = try {
                contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
            } catch (e: Exception) {
                0L
            }

            Log.d(TAG, "===> 开始解析错误报告: URI = $uri, 文件大小 = ${totalBytes / 1024} KB")

            val rawStream = contentResolver.openInputStream(uri) ?: run {
                Log.e(TAG, "打开输入流失败: $uri")
                return@withContext null
            }
            val countingStream = CountingInputStream(rawStream, totalBytes, onProgress)

            val fileName = getFileName(context, uri).lowercase()
            if (fileName.endsWith(".zip")) {
                parseZipStream(countingStream)
            } else {
                parseTextStream(countingStream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析错误报告发生异常", e)
            null
        }
    }

    /**
     * 从 ZIP 压缩流中寻找并流式解析主错误报告文本文件。
     *
     * @param inputStream 带有进度统计的输入流
     * @return 解析出的 [BugreportResult]
     */
    private suspend fun parseZipStream(inputStream: InputStream): BugreportResult? {
        val zipIn = ZipInputStream(inputStream)
        var entry = zipIn.nextEntry
        while (entry != null && coroutineContext.isActive) {
            val name = entry.name.lowercase()
            if (!entry.isDirectory && (name.startsWith("bugreport-") && name.endsWith(".txt") || (!name.contains("/") && name.endsWith(".txt")))) {
                Log.d(TAG, "找到主日志条目: ${entry.name}, 开始流式读取...")
                val reader = BufferedReader(InputStreamReader(zipIn, StandardCharsets.UTF_8), 65536)
                val result = parseFromReader(reader)
                if (result.tableItems.isNotEmpty() || result.rawHealthInfoText.isNotEmpty()) {
                    zipIn.closeEntry()
                    zipIn.close()
                    return result
                }
            }
            zipIn.closeEntry()
            entry = zipIn.nextEntry
        }
        zipIn.close()
        return null
    }

    /**
     * 极速流式解析纯文本错误报告。
     *
     * @param inputStream 带有进度统计的输入流
     * @return 解析出的 [BugreportResult]
     */
    private suspend fun parseTextStream(inputStream: InputStream): BugreportResult {
        val reader = BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8), 65536)
        val result = parseFromReader(reader)
        reader.close()
        inputStream.close()
        return result
    }

    /**
     * 逐行读取流，提取目标 12-14 项精简指标以及原始 getHealthInfo 代码文本。
     *
     * @param reader 缓冲字符读取流
     * @return 组装好的 [BugreportResult]
     */
    private suspend fun parseFromReader(reader: BufferedReader): BugreportResult {
        var chargerAcOnline: Boolean? = null
        var chargerUsbOnline: Boolean? = null
        var chargerWirelessOnline: Boolean? = null
        var chargerDockOnline: Boolean? = null
        var maxChargingCurrentMicroamps: Long? = null
        var maxChargingVoltageMicrovolts: Long? = null

        var batteryStatus: String? = null
        var batteryHealth: String? = null
        var batteryLevel: Int? = null
        var batteryTechnology: String? = null
        var batteryVoltageMillivolts: Long? = null
        var batteryTemperatureTenthsCelsius: Float? = null

        var batteryCycleCount: Int? = null
        var batteryFullChargeUah: Long? = null
        var batteryFullChargeDesignCapacityUah: Long? = null
        var batteryChargeTimeToFullNowSeconds: Long? = null

        val rawLinesList = mutableListOf<String>()
        var inHealthInfoSection = false
        var currentSection = ""
        var hasFoundHealthInfoData = false
        var hasFoundBatterySection = false
        var line: String?
        var totalLineCount = 0

        while (reader.readLine().also { line = it } != null) {
            totalLineCount++
            if (!coroutineContext.isActive) {
                return BugreportResult(emptyList(), "")
            }
            val curLine = line!!
            val trimLine = curLine.trim()

            // 早停机制（Early Termination）
            if ((hasFoundHealthInfoData || hasFoundBatterySection) && 
                trimLine.startsWith("DUMP OF SERVICE") && 
                !trimLine.contains("battery") && 
                !trimLine.contains("Health")) {
                if (batteryFullChargeDesignCapacityUah != null || batteryFullChargeUah != null || batteryVoltageMillivolts != null || batteryLevel != null) {
                    Log.d(TAG, "早停机制触发：已获取核心数据并在第 $totalLineCount 行退出！")
                    break
                }
            }

            // 极速行过滤
            if (!inHealthInfoSection && currentSection.isEmpty()) {
                val startsWithDump = trimLine.startsWith("DUMP OF SERVICE")
                val isHealthHeader = trimLine.startsWith("HealthInfo") || trimLine.startsWith("Health HAL") || trimLine.contains("getHealthInfo")
                val isCycleBroadcast = trimLine.contains("android.os.extra.CYCLE_COUNT")

                if (!startsWithDump && !isHealthHeader && !isCycleBroadcast) {
                    continue
                }
            }

            // 1. 进入 getHealthInfo / Health HAL 结构体
            if (trimLine.contains("getHealthInfo", ignoreCase = true) || 
                trimLine.startsWith("HealthInfo_2_") || 
                trimLine.startsWith("HealthInfo:") ||
                trimLine.contains("Health HAL - getHealthInfo") ||
                trimLine.contains("Health HAL:")) {
                inHealthInfoSection = true
                hasFoundHealthInfoData = true
                rawLinesList.add(curLine)
            } else if (inHealthInfoSection) {
                if (trimLine.startsWith("DUMP OF SERVICE") || trimLine.startsWith("------ ")) {
                    inHealthInfoSection = false
                } else {
                    rawLinesList.add(curLine)
                }
            }

            if (inHealthInfoSection) {
                if (trimLine.contains("chargerAcOnline", ignoreCase = true)) {
                    val pres = extractStringValue(trimLine).lowercase()
                    if (chargerAcOnline == null) chargerAcOnline = (pres == "true" || pres == "1")
                }
                if (trimLine.contains("chargerUsbOnline", ignoreCase = true)) {
                    val pres = extractStringValue(trimLine).lowercase()
                    if (chargerUsbOnline == null) chargerUsbOnline = (pres == "true" || pres == "1")
                }
                if (trimLine.contains("chargerWirelessOnline", ignoreCase = true)) {
                    val pres = extractStringValue(trimLine).lowercase()
                    if (chargerWirelessOnline == null) chargerWirelessOnline = (pres == "true" || pres == "1")
                }
                if (trimLine.contains("chargerDockOnline", ignoreCase = true)) {
                    val pres = extractStringValue(trimLine).lowercase()
                    if (chargerDockOnline == null) chargerDockOnline = (pres == "true" || pres == "1")
                }
                if (trimLine.contains("maxChargingCurrentMicroamps", ignoreCase = true) || trimLine.contains("maxChargingCurrent", ignoreCase = true)) {
                    val v = extractLongValue(trimLine)
                    if (v != null && v > 0 && maxChargingCurrentMicroamps == null) maxChargingCurrentMicroamps = v
                }
                if (trimLine.contains("maxChargingVoltageMicrovolts", ignoreCase = true) || trimLine.contains("maxChargingVoltage", ignoreCase = true)) {
                    val v = extractLongValue(trimLine)
                    if (v != null && v > 0 && maxChargingVoltageMicrovolts == null) maxChargingVoltageMicrovolts = v
                }
                if (trimLine.contains("batteryStatus", ignoreCase = true) || trimLine.contains("battery_status", ignoreCase = true)) {
                    if (batteryStatus == null) batteryStatus = extractStringValue(trimLine)
                }
                if (trimLine.contains("batteryHealth", ignoreCase = true) || trimLine.contains("battery_health", ignoreCase = true)) {
                    if (batteryHealth == null) batteryHealth = extractStringValue(trimLine)
                }
                if (trimLine.contains("batteryLevel", ignoreCase = true) || trimLine.contains("battery_level", ignoreCase = true)) {
                    val v = extractIntValue(trimLine)
                    if (v != null && v in 0..100 && batteryLevel == null) batteryLevel = v
                }
                if (trimLine.contains("batteryTechnology", ignoreCase = true) || trimLine.contains("battery_technology", ignoreCase = true)) {
                    val tech = extractStringValue(trimLine)
                    if (tech.isNotEmpty() && batteryTechnology == null) batteryTechnology = tech
                }
                if (trimLine.contains("batteryVoltageMillivolts", ignoreCase = true) || trimLine.contains("batteryVoltage", ignoreCase = true) || trimLine.contains("battery_voltage", ignoreCase = true)) {
                    val v = extractLongValue(trimLine)
                    if (v != null && batteryVoltageMillivolts == null) batteryVoltageMillivolts = v
                }
                if (trimLine.contains("batteryTemperatureTenthsCelsius", ignoreCase = true) || trimLine.contains("batteryTemperature", ignoreCase = true) || trimLine.contains("battery_temperature", ignoreCase = true)) {
                    val v = extractFloatValue(trimLine)
                    if (v != null && v > 0 && batteryTemperatureTenthsCelsius == null) batteryTemperatureTenthsCelsius = v
                }
                if (trimLine.contains("batteryCycleCount", ignoreCase = true) || trimLine.contains("battery_cycle_count", ignoreCase = true)) {
                    val v = extractIntValue(trimLine)
                    if (v != null && v > 0 && batteryCycleCount == null) batteryCycleCount = v
                }
                if (trimLine.contains("batteryFullChargeUah", ignoreCase = true) || trimLine.contains("batteryFullChargeCapacityUah", ignoreCase = true) || trimLine.contains("batteryFullCharge", ignoreCase = true) || trimLine.contains("battery_full_charge", ignoreCase = true)) {
                    val v = extractLongValue(trimLine)
                    if (v != null && v > 0 && batteryFullChargeUah == null) batteryFullChargeUah = v
                }
                if (trimLine.contains("batteryChargeTimeToFullNowSeconds", ignoreCase = true) || trimLine.contains("batteryChargeTimeToFullNow", ignoreCase = true)) {
                    val v = extractLongValue(trimLine)
                    if (v != null && batteryChargeTimeToFullNowSeconds == null) batteryChargeTimeToFullNowSeconds = v
                }
                if (trimLine.contains("batteryFullChargeDesignCapacityUah", ignoreCase = true) || trimLine.contains("battery_design_capacity", ignoreCase = true) || trimLine.contains("batteryFullChargeDesign", ignoreCase = true)) {
                    val v = extractLongValue(trimLine)
                    if (v != null && v > 0 && batteryFullChargeDesignCapacityUah == null) batteryFullChargeDesignCapacityUah = v
                }
            }

            // 2. DUMP OF SERVICE battery 段落补充
            if (trimLine.startsWith("DUMP OF SERVICE battery:")) {
                currentSection = "battery"
                hasFoundBatterySection = true
                if (rawLinesList.isEmpty()) rawLinesList.add(curLine)
            } else if (trimLine.startsWith("DUMP OF SERVICE batterystats:")) {
                currentSection = "batterystats"
            } else if (trimLine.startsWith("DUMP OF SERVICE") || trimLine.startsWith("------ ")) {
                if (!trimLine.contains("battery") && !trimLine.contains("Health")) {
                    currentSection = ""
                }
            }

            if (currentSection == "battery") {
                if (rawLinesList.size < 40) rawLinesList.add(curLine)
                if (trimLine.startsWith("AC powered:") && chargerAcOnline == null) chargerAcOnline = trimLine.substringAfter(":").trim().toBooleanStrictOrNull()
                if (trimLine.startsWith("USB powered:") && chargerUsbOnline == null) chargerUsbOnline = trimLine.substringAfter(":").trim().toBooleanStrictOrNull()
                if (trimLine.startsWith("Wireless powered:") && chargerWirelessOnline == null) chargerWirelessOnline = trimLine.substringAfter(":").trim().toBooleanStrictOrNull()
                if (trimLine.startsWith("Dock powered:") && chargerDockOnline == null) chargerDockOnline = trimLine.substringAfter(":").trim().toBooleanStrictOrNull()
                if (trimLine.startsWith("Max charging current:") && maxChargingCurrentMicroamps == null) maxChargingCurrentMicroamps = trimLine.substringAfter(":").trim().toLongOrNull()
                if (trimLine.startsWith("Max charging voltage:") && maxChargingVoltageMicrovolts == null) maxChargingVoltageMicrovolts = trimLine.substringAfter(":").trim().toLongOrNull()
                if (trimLine.startsWith("level:") && batteryLevel == null) batteryLevel = trimLine.substringAfter(":").trim().toIntOrNull()
                if (trimLine.startsWith("voltage:") && batteryVoltageMillivolts == null) batteryVoltageMillivolts = trimLine.substringAfter(":").trim().toLongOrNull()
                if (trimLine.startsWith("temperature:") && batteryTemperatureTenthsCelsius == null) batteryTemperatureTenthsCelsius = trimLine.substringAfter(":").trim().toFloatOrNull()
                if (trimLine.startsWith("status:") && batteryStatus == null) batteryStatus = trimLine.substringAfter(":").trim()
                if (trimLine.startsWith("health:") && batteryHealth == null) batteryHealth = trimLine.substringAfter(":").trim()
                if (trimLine.startsWith("technology:") && batteryTechnology == null) batteryTechnology = trimLine.substringAfter(":").trim()
                if ((trimLine.contains("cycle count:") || trimLine.contains("mCycleCount:")) && batteryCycleCount == null) batteryCycleCount = trimLine.substringAfter(":").trim().toIntOrNull()
            }

            if (currentSection == "batterystats") {
                if ((trimLine.startsWith("Estimated battery capacity:") || trimLine.startsWith("Capacity:")) && batteryFullChargeDesignCapacityUah == null) {
                    val raw = trimLine.substringAfter(":").replace("mAh", "").trim().toLongOrNull()
                    if (raw != null && raw > 0) batteryFullChargeDesignCapacityUah = if (raw < 100000) raw * 1000 else raw
                }
                if ((trimLine.startsWith("Learned battery capacity:") || trimLine.startsWith("mLearnedCap:")) && batteryFullChargeUah == null) {
                    val raw = trimLine.substringAfter(":").replace("mAh", "").trim().toLongOrNull()
                    if (raw != null && raw > 0) batteryFullChargeUah = if (raw < 100000) raw * 1000 else raw
                }
            }

            if (trimLine.contains("android.os.extra.CYCLE_COUNT=") && batteryCycleCount == null) {
                val match = Regex("(?i)android\\.os\\.extra\\.CYCLE_COUNT=(\\d+)").find(trimLine)
                if (match != null) batteryCycleCount = match.groupValues[1].toIntOrNull()
            }
        }

        // 3. 构建用户指定的精确精简表格条目列表
        val tableItems = mutableListOf<HealthInfoItem>()

        // 1. 🔌 充电方式
        val chargeMethod = when {
            chargerUsbOnline == true -> "USB"
            chargerWirelessOnline == true -> "无线充电"
            chargerAcOnline == true -> "AC 适配器"
            chargerDockOnline == true -> "Dock 底座"
            else -> "未连接"
        }
        val chargeMethodDesc = when (chargeMethod) {
            "USB" -> "USB 充电"
            "无线充电" -> "无线充电中"
            "AC 适配器" -> "交流适配器供电"
            "Dock 底座" -> "底座充电中"
            else -> "未充电/电池供电"
        }
        tableItems.add(HealthInfoItem("🔌 充电方式", chargeMethod, chargeMethodDesc))

        // 2. ⚡ 充电状态
        val statusVal = when {
            batteryStatus?.contains("CHARGING", ignoreCase = true) == true && !batteryStatus!!.contains("NOT_CHARGING", ignoreCase = true) && !batteryStatus!!.contains("DISCHARGING", ignoreCase = true) -> "Charging"
            batteryStatus?.contains("DISCHARGING", ignoreCase = true) == true || batteryStatus == "3" -> "Discharging"
            batteryStatus?.contains("NOT_CHARGING", ignoreCase = true) == true || batteryStatus == "4" -> "Not Charging"
            batteryStatus?.contains("FULL", ignoreCase = true) == true || batteryStatus == "5" -> "Full"
            batteryStatus == "2" -> "Charging"
            else -> batteryStatus ?: "未知"
        }
        val statusDesc = when (statusVal) {
            "Charging" -> "正在充电"
            "Discharging" -> "放电中"
            "Not Charging" -> "未充电"
            "Full" -> "已充满"
            else -> "状态未知"
        }
        tableItems.add(HealthInfoItem("⚡ 充电状态", statusVal, statusDesc))

        // 3. 🔋 电量
        if (batteryLevel != null) {
            tableItems.add(HealthInfoItem("🔋 电量", "$batteryLevel%", "当前剩余电量"))
        }

        // 4. 🔋 电池电压
        if (batteryVoltageMillivolts != null) {
            val mv = when {
                batteryVoltageMillivolts in 2500..9500 -> batteryVoltageMillivolts.toFloat()
                batteryVoltageMillivolts in 2500000..9500000 -> batteryVoltageMillivolts / 1000f
                batteryVoltageMillivolts > 9500 -> batteryVoltageMillivolts / 1000f
                else -> batteryVoltageMillivolts.toFloat()
            }
            tableItems.add(HealthInfoItem("🔋 电池电压", String.format("%.3f V", mv / 1000f), "当前电池端电压"))
        }

        // 5. 🌡️ 电池温度
        if (batteryTemperatureTenthsCelsius != null) {
            val degC = if (batteryTemperatureTenthsCelsius > 200) batteryTemperatureTenthsCelsius / 10f else batteryTemperatureTenthsCelsius
            tableItems.add(HealthInfoItem("🌡️ 电池温度", String.format("%.1f℃", degC), "当前温度"))
        }

        // 6. 🔄 循环次数
        if (batteryCycleCount != null) {
            tableItems.add(HealthInfoItem("🔄 循环次数", "$batteryCycleCount", "等效完整循环"))
        }

        // 7. 🔋 设计容量
        if (batteryFullChargeDesignCapacityUah != null) {
            val mah = if (batteryFullChargeDesignCapacityUah < 100000) batteryFullChargeDesignCapacityUah else batteryFullChargeDesignCapacityUah / 1000
            tableItems.add(HealthInfoItem("🔋 设计容量", "$mah mAh", "出厂设计容量"))
        }

        // 8. 🔋 当前满充容量
        if (batteryFullChargeUah != null) {
            val mah = if (batteryFullChargeUah < 100000) batteryFullChargeUah else batteryFullChargeUah / 1000
            tableItems.add(HealthInfoItem("🔋 当前满充容量", "$mah mAh", "电量计估算 FCC"))
        }

        // 9. ❤️ 理论容量健康度
        if (batteryFullChargeUah != null && batteryFullChargeDesignCapacityUah != null && batteryFullChargeDesignCapacityUah > 0) {
            val fMah = if (batteryFullChargeUah < 100000) batteryFullChargeUah.toFloat() else batteryFullChargeUah / 1000f
            val dMah = if (batteryFullChargeDesignCapacityUah < 100000) batteryFullChargeDesignCapacityUah.toFloat() else batteryFullChargeDesignCapacityUah / 1000f
            val healthPercent = (fMah / dMah) * 100f
            tableItems.add(HealthInfoItem("❤️ 理论容量健康度", String.format("%.1f%%", healthPercent), "FCC / Design Capacity"))
        }

        // 10. ⚡ 最大充电电流
        if (maxChargingCurrentMicroamps != null) {
            val ma = maxChargingCurrentMicroamps / 1000
            tableItems.add(HealthInfoItem("⚡ 最大充电电流", "$ma mA", "系统报告值"))
        }

        // 11. ⚡ 最大充电电压
        if (maxChargingVoltageMicrovolts != null) {
            val v = (maxChargingVoltageMicrovolts / 1000000f).toInt()
            tableItems.add(HealthInfoItem("⚡ 最大充电电压", "$v V", "系统报告值"))
        }

        // 12. ⏱️ 预计充满时间
        val timeDisplay = if (batteryChargeTimeToFullNowSeconds == null || batteryChargeTimeToFullNowSeconds < 0) {
            "未知"
        } else {
            val mins = batteryChargeTimeToFullNowSeconds / 60
            "$mins 分钟"
        }
        val timeDesc = if (batteryChargeTimeToFullNowSeconds == null || batteryChargeTimeToFullNowSeconds < 0) "返回 -1" else "预计充满时间"
        tableItems.add(HealthInfoItem("⏱️ 预计充满时间", timeDisplay, timeDesc))

        // 13. 🔋 电池类型
        if (batteryTechnology != null) {
            tableItems.add(HealthInfoItem("🔋 电池类型", batteryTechnology, "锂离子"))
        }

        // 14. 🩺 电池健康
        if (batteryHealth != null) {
            val hVal = if (batteryHealth.contains("GOOD", ignoreCase = true) || batteryHealth == "2") "GOOD" else batteryHealth
            tableItems.add(HealthInfoItem("🩺 电池健康", hVal, "Android 判断正常"))
        }

        val rawText = rawLinesList.joinToString("\n").trim()

        Log.d(TAG, "===> 目标表格项数量: ${tableItems.size}, 原始数据文本行数: ${rawLinesList.size}")

        return BugreportResult(tableItems, rawText)
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
