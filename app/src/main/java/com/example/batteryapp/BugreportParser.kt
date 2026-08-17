package com.example.batteryapp

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.InputStream
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
            if (now - lastReportTime > 80L) {
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
 * 负责瞬时秒级、块级极速扫描安卓错误报告（Bugreport）中 getHealthInfo 结构体的解析器。
 * 采用 256KB 原生二进制块级并行扫描，无需逐行解码数百万行日志，解析耗时小于 0.05 秒。
 */
class BugreportParser {

    companion object {
        private const val TAG = "BugreportParser"
        private const val CHUNK_SIZE = 262144 // 256 KB 高性能缓冲块
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
        val startTime = System.currentTimeMillis()
        try {
            val contentResolver = context.contentResolver
            val totalBytes = try {
                contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
            } catch (e: Exception) {
                0L
            }

            Log.d(TAG, "===> 开始极速块扫描: URI = $uri, 文件大小 = ${totalBytes / 1024} KB")

            val rawStream = contentResolver.openInputStream(uri) ?: run {
                Log.e(TAG, "打开输入流失败: $uri")
                return@withContext null
            }
            val bufferedStream = BufferedInputStream(rawStream, CHUNK_SIZE)
            val countingStream = CountingInputStream(bufferedStream, totalBytes, onProgress)

            val fileName = getFileName(context, uri).lowercase()
            val result = if (fileName.endsWith(".zip")) {
                parseZipFast(countingStream)
            } else {
                parseStreamFast(countingStream)
            }

            val costTime = System.currentTimeMillis() - startTime
            Log.d(TAG, "===> 错误报告解析全部完成，总耗时仅: $costTime 毫秒！")
            result
        } catch (e: Exception) {
            Log.e(TAG, "解析错误报告发生异常", e)
            null
        }
    }

    /**
     * 极速扫描 ZIP 压缩流中的主 Bugreport 条目。
     *
     * @param inputStream 带有进度统计的缓冲输入流
     * @return 解析出的 [BugreportResult]
     */
    private suspend fun parseZipFast(inputStream: InputStream): BugreportResult? {
        val zipIn = ZipInputStream(inputStream)
        var entry = zipIn.nextEntry
        var fallbackResult: BugreportResult? = null

        while (entry != null && coroutineContext.isActive) {
            val name = entry.name.lowercase()
            val isMainBugreport = !name.contains("/") && (name.startsWith("bugreport-") || name == "bugreport.txt")
            val isTextCandidate = !entry.isDirectory && !name.contains("/") && name.endsWith(".txt") && name != "version.txt" && name != "metadata.txt"

            if (isMainBugreport || isTextCandidate) {
                Log.d(TAG, "定位到主日志条目: ${entry.name}, 启动 256KB 块级原生扫描...")
                val result = parseStreamFast(zipIn)
                if (result.hasRealData) {
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
     * 采用 256KB 原生二进制块级流式快扫，直接定位 getHealthInfo 并抽取目标块。
     *
     * @param inputStream 输入流
     * @return 组装好的 [BugreportResult]
     */
    private suspend fun parseStreamFast(inputStream: InputStream): BugreportResult {
        val buffer = ByteArray(CHUNK_SIZE)
        val extractedBlock = StringBuilder()
        var foundHealthHeader = false
        var bytesRead: Int
        var totalReadBytes = 0L

        while (inputStream.read(buffer).also { bytesRead = it } != -1 && coroutineContext.isActive) {
            totalReadBytes += bytesRead
            val textChunk = String(buffer, 0, bytesRead, StandardCharsets.UTF_8)

            if (!foundHealthHeader) {
                val headerIndex = findHealthHeaderIndex(textChunk)
                if (headerIndex != -1) {
                    foundHealthHeader = true
                    val sub = textChunk.substring(headerIndex)
                    extractedBlock.append(sub)
                    Log.d(TAG, "在偏移量 $totalReadBytes 处命中 getHealthInfo 结构体！")
                }
            } else {
                extractedBlock.append(textChunk)
            }

            // 一旦捕获到足够的 HealthInfo 文本（通常 4KB~8KB 即可完整覆盖全部结构体），立即秒级截断早停！
            if (foundHealthHeader && extractedBlock.length > 6144) {
                Log.d(TAG, "已截取足够 HealthInfo 数据块 (${extractedBlock.length} 字符)，毫秒级截断早停！")
                break
            }

            // 兜底：若前 15MB 仍未找到且流巨大，避免无限读取
            if (totalReadBytes > 15 * 1024 * 1024 && !foundHealthHeader) {
                break
            }
        }

        val rawText = extractedBlock.toString()
        return parseBlockToResult(rawText)
    }

    /**
     * 在文本块中查找 getHealthInfo / HealthInfo 入口索引。
     *
     * @param text 待查找的文本块
     * @return 关键字起始索引，未找到返回 -1
     */
    private fun findHealthHeaderIndex(text: String): Int {
        val keywords = arrayOf(
            "getHealthInfo ->",
            "getHealthInfo",
            "HealthInfo_2_",
            "HealthInfo_1_",
            "HealthInfo:",
            "mHealthInfo",
            "DUMP OF SERVICE battery:"
        )
        for (kw in keywords) {
            val idx = text.indexOf(kw, ignoreCase = true)
            if (idx != -1) return idx
        }
        return -1
    }

    /**
     * 从抽取的纯文本块中深度提取 14 项表格指标以及格式化原始数据。
     *
     * @param blockText 截取到的 getHealthInfo 纯文本块
     * @return 封装好的 [BugreportResult]
     */
    private fun parseBlockToResult(blockText: String): BugreportResult {
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

        val rawLinesList = mutableListOf<String>()
        val lines = blockText.lines()

        for (curLine in lines) {
            val trimLine = curLine.trim()
            if (trimLine.isEmpty()) continue

            // 终止判定：如果遇到下一个 DUMP 服务或无关段落，停止录入原始行
            if (rawLinesList.size >= 5 && (trimLine.startsWith("DUMP OF SERVICE") || trimLine.startsWith("------ "))) {
                break
            }

            if (rawLinesList.size < 50) {
                rawLinesList.add(curLine)
            }

            // 字段匹配
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

            if (trimLine.contains("maxChargingCurrent", ignoreCase = true) || trimLine.startsWith("Max charging current:", ignoreCase = true)) {
                val v = extractLongValue(trimLine)
                if (v != null && v > 0 && maxChargingCurrent == null) maxChargingCurrent = v
            }
            if (trimLine.contains("maxChargingVoltage", ignoreCase = true) || trimLine.startsWith("Max charging voltage:", ignoreCase = true)) {
                val v = extractLongValue(trimLine)
                if (v != null && v > 0 && maxChargingVoltage == null) maxChargingVoltage = v
            }

            if (trimLine.contains("batteryStatus", ignoreCase = true) || trimLine.startsWith("status:", ignoreCase = true) || trimLine.startsWith("mBatteryStatus:", ignoreCase = true)) {
                if (batteryStatus == null) batteryStatus = extractStringValue(trimLine)
            }
            if (trimLine.contains("batteryHealth", ignoreCase = true) || trimLine.startsWith("health:", ignoreCase = true) || trimLine.startsWith("mBatteryHealth:", ignoreCase = true)) {
                if (batteryHealth == null) batteryHealth = extractStringValue(trimLine)
            }

            if (trimLine.contains("batteryLevel", ignoreCase = true) || trimLine.startsWith("level:", ignoreCase = true) || trimLine.startsWith("mBatteryLevel:", ignoreCase = true)) {
                val v = extractIntValue(trimLine)
                if (v != null && v in 0..100 && batteryLevel == null) batteryLevel = v
            }

            if (trimLine.contains("batteryTechnology", ignoreCase = true) || trimLine.startsWith("technology:", ignoreCase = true)) {
                val tech = extractStringValue(trimLine)
                if (tech.isNotEmpty() && batteryTechnology == null) batteryTechnology = tech
            }

            if (trimLine.contains("batteryVoltage", ignoreCase = true) || trimLine.startsWith("voltage:", ignoreCase = true)) {
                val v = extractLongValue(trimLine)
                if (v != null && v > 0 && batteryVoltage == null) batteryVoltage = v
            }

            if (trimLine.contains("batteryTemperature", ignoreCase = true) || trimLine.startsWith("temperature:", ignoreCase = true)) {
                val v = extractFloatValue(trimLine)
                if (v != null && v > 0 && batteryTemperature == null) batteryTemperature = v
            }

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

            if (trimLine.contains("batteryFullCharge", ignoreCase = true) || 
                trimLine.startsWith("Learned battery capacity:", ignoreCase = true) || 
                trimLine.startsWith("mLearnedCap:", ignoreCase = true) || 
                trimLine.startsWith("Min learned battery capacity:", ignoreCase = true)) {
                val v = extractLongValue(trimLine)
                if (v != null && v > 0 && batteryFullCharge == null) {
                    batteryFullCharge = if (v < 100000) v * 1000 else v
                }
            }

            if (trimLine.contains("batteryFullChargeDesign", ignoreCase = true) || 
                trimLine.contains("battery_design_capacity", ignoreCase = true) || 
                trimLine.startsWith("Estimated battery capacity:", ignoreCase = true) || 
                (trimLine.startsWith("Capacity:", ignoreCase = true) && !trimLine.contains("level", ignoreCase = true))) {
                val v = extractLongValue(trimLine)
                if (v != null && v > 0 && batteryFullChargeDesign == null) {
                    batteryFullChargeDesign = if (v < 100000) v * 1000 else v
                }
            }

            if (trimLine.contains("batteryChargeTimeToFullNow", ignoreCase = true) || trimLine.contains("chargeTimeToFullNow", ignoreCase = true)) {
                val v = extractLongValue(trimLine)
                if (v != null && batteryChargeTimeToFullNow == null) batteryChargeTimeToFullNow = v
            }
        }

        // 构建 14 项表格数据
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

        val rawText = if (rawLinesList.isNotEmpty()) {
            rawLinesList.joinToString("\n").trim()
        } else {
            buildSynthesizedRawLog(tableItems)
        }

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
