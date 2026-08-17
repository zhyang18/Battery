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
            if (now - lastReportTime > 100L) {
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
 * 负责毫秒级极速、深度解析安卓错误报告（Bugreport）中 getHealthInfo -> HealthInfo 结构体的解析器。
 */
class BugreportParser {

    companion object {
        private const val TAG = "BugreportParser"
    }

    /**
     * 从选定的文件 URI 流式极速解析错误报告日志并提取 getHealthInfo 表格与原始数据。
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
     * 从 ZIP 压缩流中寻找并流式解析真正的主错误报告文本文件。
     *
     * @param inputStream 带有进度统计的输入流
     * @return 解析出的 [BugreportResult]
     */
    private suspend fun parseZipStream(inputStream: InputStream): BugreportResult? {
        val zipIn = ZipInputStream(inputStream)
        var entry = zipIn.nextEntry
        var fallbackResult: BugreportResult? = null

        while (entry != null && coroutineContext.isActive) {
            val name = entry.name.lowercase()
            val isMainBugreport = !name.contains("/") && (name.startsWith("bugreport-") || name == "bugreport.txt")
            val isTextFile = !entry.isDirectory && !name.contains("/") && name.endsWith(".txt") && name != "version.txt" && name != "metadata.txt"

            if (isMainBugreport || isTextFile) {
                Log.d(TAG, "正在快速读取主日志条目: ${entry.name} ...")
                val reader = BufferedReader(InputStreamReader(zipIn, StandardCharsets.UTF_8), 65536)
                val result = parseFromReader(reader)
                if (result.hasRealData) {
                    Log.d(TAG, "在条目 ${entry.name} 中成功匹配到 getHealthInfo 真实数据，极速返回！")
                    zipIn.closeEntry()
                    zipIn.close()
                    return result
                } else if (fallbackResult == null && result.tableItems.isNotEmpty()) {
                    fallbackResult = result
                }
            }
            zipIn.closeEntry()
            entry = zipIn.nextEntry
        }
        zipIn.close()
        return fallbackResult
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
     * 逐行读取流，精准提取 getHealthInfo -> HealthInfo 结构体以及目标 14 项精简指标。
     *
     * @param reader 缓冲字符读取流
     * @return 组装好的 [BugreportResult]
     */
    private suspend fun parseFromReader(reader: BufferedReader): BugreportResult {
        var chargerAcOnline: Boolean? = null
        var chargerUsbOnline: Boolean? = null
        var chargerWirelessOnline: Boolean? = null
        var chargerDockOnline: Boolean? = null
        var maxChargingCurrent: Long? = null
        var maxChargingVoltage: Long? = null

        var batteryStatus: String? = null
        var batteryHealth: String? = null
        var batteryLevel: Int? = null
        var batteryTechnology: String? = null
        var batteryVoltage: Long? = null
        var batteryTemperature: Float? = null

        var batteryCycleCount: Int? = null
        var batteryFullCharge: Long? = null
        var batteryFullChargeDesign: Long? = null
        var batteryChargeTimeToFullNow: Long? = null

        val healthInfoRawLines = mutableListOf<String>()
        var inHealthInfoBlock = false
        var hasFinishedHealthBlock = false
        var line: String?
        var totalLineCount = 0

        while (reader.readLine().also { line = it } != null) {
            totalLineCount++
            if (!coroutineContext.isActive) {
                return BugreportResult(emptyList(), "", false)
            }
            val curLine = line!!
            if (curLine.length > 300) continue
            val trimLine = curLine.trim()
            if (trimLine.isEmpty()) continue

            // 1. 识别 getHealthInfo / HealthInfo 段落入口
            if (trimLine.contains("getHealthInfo", ignoreCase = true) ||
                trimLine.startsWith("HealthInfo_2_") ||
                trimLine.startsWith("HealthInfo_1_") ||
                trimLine.startsWith("HealthInfo:") ||
                trimLine.startsWith("mHealthInfo") ||
                trimLine.contains("Health HAL - getHealthInfo") ||
                trimLine.contains("android.hardware.health")) {
                inHealthInfoBlock = true
            }

            // 2. 录入 getHealthInfo 原始代码块
            if (inHealthInfoBlock) {
                if (trimLine.startsWith("DUMP OF SERVICE") && !trimLine.contains("health") && !trimLine.contains("battery")) {
                    inHealthInfoBlock = false
                    hasFinishedHealthBlock = true
                } else if (trimLine.startsWith("------ ") && healthInfoRawLines.size > 5) {
                    inHealthInfoBlock = false
                    hasFinishedHealthBlock = true
                } else if (healthInfoRawLines.size < 65) {
                    healthInfoRawLines.add(curLine)
                }
            }

            // 3. 毫秒级极速早停：getHealthInfo 结构体读完且已有核心数据，立即退出循环
            if (hasFinishedHealthBlock && batteryVoltage != null && (batteryFullCharge != null || batteryLevel != null)) {
                Log.d(TAG, "getHealthInfo 结构体已完整提取，在第 $totalLineCount 行立即极速早停！")
                break
            }

            // 4. 兜底早停：如果进入后续无关服务，也立即退出
            if (batteryVoltage != null && (batteryFullCharge != null || batteryLevel != null)) {
                if (trimLine.startsWith("DUMP OF SERVICE package:") ||
                    trimLine.startsWith("DUMP OF SERVICE window:") ||
                    trimLine.startsWith("DUMP OF SERVICE activity:") ||
                    trimLine.startsWith("------ SYSTEM LOG") ||
                    trimLine.startsWith("------ LOGCAT")) {
                    Log.d(TAG, "在第 $totalLineCount 行已收集齐 HealthInfo 数据，早停退出！")
                    break
                }
            }

            // 5. 精准匹配 getHealthInfo -> HealthInfo 中各个字段

            // 5.1 充电方式
            if (trimLine.contains("chargerUsbOnline", ignoreCase = true) || trimLine.startsWith("USB powered:", ignoreCase = true)) {
                val v = parseBooleanValue(trimLine)
                if (v != null && chargerUsbOnline == null) chargerUsbOnline = v
            }
            if (trimLine.contains("chargerAcOnline", ignoreCase = true) || trimLine.startsWith("AC powered:", ignoreCase = true)) {
                val v = parseBooleanValue(trimLine)
                if (v != null && chargerAcOnline == null) chargerAcOnline = v
            }
            if (trimLine.contains("chargerWirelessOnline", ignoreCase = true) || trimLine.startsWith("Wireless powered:", ignoreCase = true)) {
                val v = parseBooleanValue(trimLine)
                if (v != null && chargerWirelessOnline == null) chargerWirelessOnline = v
            }
            if (trimLine.contains("chargerDockOnline", ignoreCase = true) || trimLine.startsWith("Dock powered:", ignoreCase = true)) {
                val v = parseBooleanValue(trimLine)
                if (v != null && chargerDockOnline == null) chargerDockOnline = v
            }

            // 5.2 最大充电电流/电压
            if (trimLine.contains("maxChargingCurrent", ignoreCase = true) || trimLine.startsWith("Max charging current:", ignoreCase = true)) {
                val v = extractLongValue(trimLine)
                if (v != null && v > 0 && maxChargingCurrent == null) maxChargingCurrent = v
            }
            if (trimLine.contains("maxChargingVoltage", ignoreCase = true) || trimLine.startsWith("Max charging voltage:", ignoreCase = true)) {
                val v = extractLongValue(trimLine)
                if (v != null && v > 0 && maxChargingVoltage == null) maxChargingVoltage = v
            }

            // 5.3 电池状态与健康
            if (trimLine.contains("batteryStatus", ignoreCase = true) || trimLine.startsWith("status:", ignoreCase = true) || trimLine.startsWith("mBatteryStatus:", ignoreCase = true)) {
                if (batteryStatus == null) batteryStatus = extractStringValue(trimLine)
            }
            if (trimLine.contains("batteryHealth", ignoreCase = true) || trimLine.startsWith("health:", ignoreCase = true) || trimLine.startsWith("mBatteryHealth:", ignoreCase = true)) {
                if (batteryHealth == null) batteryHealth = extractStringValue(trimLine)
            }

            // 5.4 电量
            if (trimLine.contains("batteryLevel", ignoreCase = true) || trimLine.startsWith("level:", ignoreCase = true) || trimLine.startsWith("mBatteryLevel:", ignoreCase = true)) {
                val v = extractIntValue(trimLine)
                if (v != null && v in 0..100 && batteryLevel == null) batteryLevel = v
            }

            // 5.5 电池类型
            if (trimLine.contains("batteryTechnology", ignoreCase = true) || trimLine.startsWith("technology:", ignoreCase = true)) {
                val tech = extractStringValue(trimLine)
                if (tech.isNotEmpty() && batteryTechnology == null) batteryTechnology = tech
            }

            // 5.6 电池电压
            if (trimLine.contains("batteryVoltage", ignoreCase = true) || trimLine.startsWith("voltage:", ignoreCase = true)) {
                val v = extractLongValue(trimLine)
                if (v != null && v > 0 && batteryVoltage == null) batteryVoltage = v
            }

            // 5.7 电池温度
            if (trimLine.contains("batteryTemperature", ignoreCase = true) || trimLine.startsWith("temperature:", ignoreCase = true)) {
                val v = extractFloatValue(trimLine)
                if (v != null && v > 0 && batteryTemperature == null) batteryTemperature = v
            }

            // 5.8 循环次数
            if (trimLine.contains("batteryCycleCount", ignoreCase = true) || 
                trimLine.contains("cycle count:", ignoreCase = true) || 
                trimLine.contains("mCycleCount:", ignoreCase = true) || 
                trimLine.contains("android.os.extra.CYCLE_COUNT=", ignoreCase = true)) {
                val v = if (trimLine.contains("android.os.extra.CYCLE_COUNT=")) {
                    Regex("(?i)android\\.os\\.extra\\.CYCLE_COUNT=(\\d+)").find(trimLine)?.groupValues?.get(1)?.toIntOrNull()
                } else {
                    extractIntValue(trimLine)
                }
                if (v != null && v > 0) {
                    if (batteryCycleCount == null || v > batteryCycleCount) batteryCycleCount = v
                }
            }

            // 5.9 满充容量 (FCC)
            if (trimLine.contains("batteryFullCharge", ignoreCase = true) || 
                trimLine.startsWith("Learned battery capacity:", ignoreCase = true) || 
                trimLine.startsWith("mLearnedCap:", ignoreCase = true) || 
                trimLine.startsWith("Min learned battery capacity:", ignoreCase = true)) {
                val v = extractLongValue(trimLine)
                if (v != null && v > 0 && batteryFullCharge == null) {
                    batteryFullCharge = if (v < 100000) v * 1000 else v
                }
            }

            // 5.10 设计容量
            if (trimLine.contains("batteryFullChargeDesign", ignoreCase = true) || 
                trimLine.contains("battery_design_capacity", ignoreCase = true) || 
                trimLine.startsWith("Estimated battery capacity:", ignoreCase = true) || 
                (trimLine.startsWith("Capacity:", ignoreCase = true) && !trimLine.contains("level", ignoreCase = true))) {
                val v = extractLongValue(trimLine)
                if (v != null && v > 0 && batteryFullChargeDesign == null) {
                    batteryFullChargeDesign = if (v < 100000) v * 1000 else v
                }
            }

            // 5.11 预计充满时间
            if (trimLine.contains("batteryChargeTimeToFullNow", ignoreCase = true) || trimLine.contains("chargeTimeToFullNow", ignoreCase = true)) {
                val v = extractLongValue(trimLine)
                if (v != null && batteryChargeTimeToFullNow == null) batteryChargeTimeToFullNow = v
            }
        }

        // 6. 构建 14 项表格数据
        val tableItems = mutableListOf<HealthInfoItem>()

        // 1. 🔌 充电方式
        val chargeMethod = when {
            chargerUsbOnline == true -> "USB"
            chargerWirelessOnline == true -> "无线充电"
            chargerAcOnline == true -> "AC 适配器"
            chargerDockOnline == true -> "Dock 底座"
            else -> if (batteryStatus?.contains("CHARGING", ignoreCase = true) == true || batteryStatus == "2") "USB" else "未连接"
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
            chargerUsbOnline == true || chargerAcOnline == true -> "Charging"
            else -> "Discharging"
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
        val levelDisplay = if (batteryLevel != null) "$batteryLevel%" else "未知"
        tableItems.add(HealthInfoItem("🔋 电量", levelDisplay, "当前剩余电量"))

        // 4. 🔋 电池电压
        val voltDisplay = if (batteryVoltage != null) {
            val mv = when {
                batteryVoltage in 2500..9500 -> batteryVoltage.toFloat()
                batteryVoltage in 2500000..9500000 -> batteryVoltage / 1000f
                batteryVoltage > 9500 -> batteryVoltage / 1000f
                else -> batteryVoltage.toFloat()
            }
            String.format("%.3f V", mv / 1000f)
        } else "未知"
        tableItems.add(HealthInfoItem("🔋 电池电压", voltDisplay, "当前电池端电压"))

        // 5. 🌡️ 电池温度
        val tempDisplay = if (batteryTemperature != null) {
            val degC = if (batteryTemperature > 200) batteryTemperature / 10f else batteryTemperature
            String.format("%.1f℃", degC)
        } else "未知"
        tableItems.add(HealthInfoItem("🌡️ 电池温度", tempDisplay, "当前温度"))

        // 6. 🔄 循环次数
        val cycleDisplay = if (batteryCycleCount != null) "$batteryCycleCount" else "未知"
        tableItems.add(HealthInfoItem("🔄 循环次数", cycleDisplay, "等效完整循环"))

        // 7. 🔋 设计容量
        val designDisplay = if (batteryFullChargeDesign != null) {
            val mah = batteryFullChargeDesign / 1000
            "$mah mAh"
        } else "未知"
        tableItems.add(HealthInfoItem("🔋 设计容量", designDisplay, "出厂设计容量"))

        // 8. 🔋 当前满充容量
        val fccDisplay = if (batteryFullCharge != null) {
            val mah = batteryFullCharge / 1000
            "$mah mAh"
        } else "未知"
        tableItems.add(HealthInfoItem("🔋 当前满充容量", fccDisplay, "电量计估算 FCC"))

        // 9. ❤️ 理论容量健康度
        val healthDisplay = if (batteryFullCharge != null && batteryFullChargeDesign != null && batteryFullChargeDesign > 0) {
            val fMah = batteryFullCharge / 1000f
            val dMah = batteryFullChargeDesign / 1000f
            val healthPercent = (fMah / dMah) * 100f
            String.format("%.1f%%", healthPercent)
        } else "未知"
        tableItems.add(HealthInfoItem("❤️ 理论容量健康度", healthDisplay, "FCC / Design Capacity"))

        // 10. ⚡ 最大充电电流
        val maxCurDisplay = if (maxChargingCurrent != null) {
            val ma = if (maxChargingCurrent >= 1000) maxChargingCurrent / 1000 else maxChargingCurrent
            "$ma mA"
        } else "未知"
        tableItems.add(HealthInfoItem("⚡ 最大充电电流", maxCurDisplay, "系统报告值"))

        // 11. ⚡ 最大充电电压
        val maxVoltDisplay = if (maxChargingVoltage != null) {
            val v = if (maxChargingVoltage >= 1000000) (maxChargingVoltage / 1000000f).toInt() else if (maxChargingVoltage >= 1000) (maxChargingVoltage / 1000f).toInt() else maxChargingVoltage.toInt()
            "$v V"
        } else "未知"
        tableItems.add(HealthInfoItem("⚡ 最大充电电压", maxVoltDisplay, "系统报告值"))

        // 12. ⏱️ 预计充满时间
        val timeDisplay = if (batteryChargeTimeToFullNow == null || batteryChargeTimeToFullNow < 0) {
            "未知"
        } else {
            val mins = batteryChargeTimeToFullNow / 60
            "$mins 分钟"
        }
        val timeDesc = if (batteryChargeTimeToFullNow == null || batteryChargeTimeToFullNow < 0) "返回 -1" else "预计充满时间"
        tableItems.add(HealthInfoItem("⏱️ 预计充满时间", timeDisplay, timeDesc))

        // 13. 🔋 电池类型
        val techDisplay = batteryTechnology ?: "Li-ion"
        tableItems.add(HealthInfoItem("🔋 电池类型", techDisplay, "锂离子"))

        // 14. 🩺 电池健康
        val healthStatusVal = when {
            batteryHealth?.contains("GOOD", ignoreCase = true) == true || batteryHealth == "2" -> "GOOD"
            batteryHealth?.contains("OVERHEAT", ignoreCase = true) == true || batteryHealth == "3" -> "OVERHEAT (过热)"
            batteryHealth?.contains("DEAD", ignoreCase = true) == true || batteryHealth == "4" -> "DEAD (损坏)"
            batteryHealth?.contains("OVER_VOLTAGE", ignoreCase = true) == true || batteryHealth == "5" -> "OVER_VOLTAGE (电压过高)"
            batteryHealth?.contains("COLD", ignoreCase = true) == true || batteryHealth == "7" -> "COLD (过冷)"
            else -> batteryHealth ?: "GOOD"
        }
        tableItems.add(HealthInfoItem("🩺 电池健康", healthStatusVal, "Android 判断正常"))

        val hasRealData = (batteryLevel != null || batteryVoltage != null || batteryFullCharge != null || batteryCycleCount != null || batteryTemperature != null)

        val rawText = if (healthInfoRawLines.isNotEmpty()) {
            healthInfoRawLines.joinToString("\n").trim()
        } else {
            buildSynthesizedRawLog(tableItems)
        }

        Log.d(TAG, "===> getHealthInfo 解析完成: 行数 = $totalLineCount, 是否捕获有效数据 = $hasRealData, 原始行数 = ${healthInfoRawLines.size}")

        return BugreportResult(tableItems, rawText, hasRealData)
    }

    private fun buildSynthesizedRawLog(items: List<HealthInfoItem>): String {
        val sb = StringBuilder()
        sb.append("getHealthInfo -> HealthInfo:\n")
        for (item in items) {
            sb.append("  ").append(item.itemTitle).append(" = ").append(item.currentValue).append("\n")
        }
        return sb.toString().trim()
    }

    private fun parseBooleanValue(line: String): Boolean? {
        val clean = line.replace(",", "").replace(";", "").lowercase()
        val raw = if (clean.contains("=")) clean.substringAfter("=").trim() else clean.substringAfter(":").trim()
        return when {
            raw.startsWith("true") || raw.startsWith("1") -> true
            raw.startsWith("false") || raw.startsWith("0") -> false
            else -> null
        }
    }

    private fun extractLongValue(line: String): Long? {
        val clean = line.replace(",", "").replace(";", "")
        val raw = if (clean.contains("=")) clean.substringAfter("=").trim() else clean.substringAfter(":").trim()
        val numStr = raw.replace("mAh", "").replace("uAh", "").replace("mV", "").replace("uV", "").trim().split(" ")[0].trim()
        return numStr.toLongOrNull()
    }

    private fun extractIntValue(line: String): Int? {
        val clean = line.replace(",", "").replace(";", "")
        val raw = if (clean.contains("=")) clean.substringAfter("=").trim() else clean.substringAfter(":").trim()
        val numStr = raw.replace("%", "").trim().split(" ")[0].trim()
        return numStr.toIntOrNull()
    }

    private fun extractFloatValue(line: String): Float? {
        val clean = line.replace(",", "").replace(";", "")
        val raw = if (clean.contains("=")) clean.substringAfter("=").trim() else clean.substringAfter(":").trim()
        val numStr = raw.replace("C", "").trim().split(" ")[0].trim()
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
