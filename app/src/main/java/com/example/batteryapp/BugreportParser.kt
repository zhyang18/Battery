package com.example.batteryapp

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

/**
 * 负责瞬时秒级、100% 精准解析安卓错误报告（Bugreport）中 getHealthInfo -> HealthInfo{...} 结构体的解析器。
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
        val startTime = System.currentTimeMillis()
        try {
            val fileName = getFileName(context, uri).lowercase()
            Log.d(TAG, "===> 开始极速解析: URI = $uri, 文件名 = $fileName")

            val result = if (fileName.endsWith(".zip")) {
                parseZipViaZipFile(context, uri, onProgress)
            } else {
                val rawStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
                val reader = BufferedReader(InputStreamReader(rawStream, StandardCharsets.UTF_8), 65536)
                val res = parseFromReader(reader)
                reader.close()
                rawStream.close()
                res
            }

            val costTime = System.currentTimeMillis() - startTime
            Log.d(TAG, "===> 错误报告解析完成，耗时: $costTime ms, 获取到有效数据: ${result?.hasRealData}")
            result
        } catch (e: Exception) {
            Log.e(TAG, "解析错误报告发生异常", e)
            null
        }
    }

    /**
     * 利用 ZipFile 随机访问索引直接秒级定位主 bugreport-*.txt。
     *
     * @param context 应用程序上下文
     * @param uri 错误报告 ZIP 的 URI
     * @param onProgress 进度回调
     * @return 解析结果
     */
    private suspend fun parseZipViaZipFile(
        context: Context,
        uri: Uri,
        onProgress: (Int) -> Unit
    ): BugreportResult? {
        val tempZip = File(context.cacheDir, "temp_bugreport_${System.currentTimeMillis()}.zip")
        try {
            // 1. 极速将 ZIP 复制到缓存目录
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val outputStream = FileOutputStream(tempZip)
            val buffer = ByteArray(262144) // 256KB 复制
            var bytesCopied = 0L
            var read: Int
            val totalBytes = try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
            } catch (e: Exception) {
                0L
            }

            var lastReport = 0L
            while (inputStream.read(buffer).also { read = it } != -1 && coroutineContext.isActive) {
                outputStream.write(buffer, 0, read)
                bytesCopied += read
                if (totalBytes > 0) {
                    val now = System.currentTimeMillis()
                    if (now - lastReport > 80L) {
                        lastReport = now
                        val pct = ((bytesCopied * 70) / totalBytes).toInt().coerceIn(0, 70)
                        onProgress(pct)
                    }
                }
            }
            outputStream.flush()
            outputStream.close()
            inputStream.close()

            if (!coroutineContext.isActive) return null
            onProgress(75)

            // 2. 利用 ZipFile 目录索引直接定位主日志
            val zipFile = ZipFile(tempZip)
            var targetEntry = zipFile.entries().asSequence().firstOrNull {
                val name = it.name.lowercase()
                !it.isDirectory && (name.startsWith("bugreport-") && name.endsWith(".txt") || name == "bugreport.txt")
            }

            if (targetEntry == null) {
                targetEntry = zipFile.entries().asSequence().firstOrNull {
                    val name = it.name.lowercase()
                    !it.isDirectory && !name.contains("/") && name.endsWith(".txt") && name != "version.txt" && name != "metadata.txt"
                }
            }

            if (targetEntry == null) {
                Log.e(TAG, "未在 ZIP 中找到主错误报告条目！")
                zipFile.close()
                return null
            }

            Log.d(TAG, "直接命中主日志条目: ${targetEntry.name}, 启动流式截断解析...")
            val entryStream = zipFile.getInputStream(targetEntry)
            val reader = BufferedReader(InputStreamReader(entryStream, StandardCharsets.UTF_8), 65536)
            val result = parseFromReader(reader)

            reader.close()
            entryStream.close()
            zipFile.close()
            onProgress(100)

            return result
        } finally {
            if (tempZip.exists()) {
                tempZip.delete()
            }
        }
    }

    /**
     * 逐行流式读取，精准提取 getHealthInfo -> HealthInfo{...} 单行及多行结构体。
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
        var inHealthSection = false
        var line: String?
        var totalLineCount = 0

        while (reader.readLine().also { line = it } != null) {
            totalLineCount++
            if (!coroutineContext.isActive) {
                return BugreportResult(emptyList(), "", false)
            }
            val curLine = line!!
            val trimLine = curLine.trim()
            if (trimLine.isEmpty()) continue

            // 1. 进入 IHealth / Health HAL / getHealthInfo 服务段落
            if (trimLine.contains("android.hardware.health", ignoreCase = true) ||
                trimLine.contains("getHealthInfo", ignoreCase = true) ||
                trimLine.startsWith("HealthInfo") ||
                trimLine.startsWith("mHealthInfo") ||
                trimLine.startsWith("DUMP OF SERVICE battery:")) {
                inHealthSection = true
                Log.d(TAG, "命中 Health 关键服务段落: $trimLine (第 $totalLineCount 行)")
            }

            if (inHealthSection) {
                if (trimLine.startsWith("DUMP OF SERVICE") && !trimLine.contains("health") && !trimLine.contains("battery")) {
                    inHealthSection = false
                } else if (trimLine.startsWith("---------") && healthInfoRawLines.size > 3) {
                    inHealthSection = false
                } else if (healthInfoRawLines.size < 40) {
                    healthInfoRawLines.add(curLine)
                }
            }

            // 2. 核心匹配 1：如果是单行 getHealthInfo -> HealthInfo{...}
            if (trimLine.contains("getHealthInfo", ignoreCase = true) && trimLine.contains("{") && trimLine.contains("}")) {
                Log.d(TAG, "命中单行 getHealthInfo -> HealthInfo{...} 完整结构体！")
                if (!healthInfoRawLines.contains(curLine)) {
                    healthInfoRawLines.add(curLine)
                }

                // 提取大括号内部的键值对
                val insideBraces = trimLine.substringAfter("{").substringBeforeLast("}")
                val map = Regex("(\\w+):\\s*([^,}]+)").findAll(insideBraces).associate {
                    it.groupValues[1].trim() to it.groupValues[2].trim()
                }

                map["chargerAcOnline"]?.let { chargerAcOnline = it.equals("true", true) || it == "1" }
                map["chargerUsbOnline"]?.let { chargerUsbOnline = it.equals("true", true) || it == "1" }
                map["chargerWirelessOnline"]?.let { chargerWirelessOnline = it.equals("true", true) || it == "1" }
                map["chargerDockOnline"]?.let { chargerDockOnline = it.equals("true", true) || it == "1" }

                map["maxChargingCurrentMicroamps"]?.toLongOrNull()?.let { maxChargingCurrent = it }
                map["maxChargingVoltageMicrovolts"]?.toLongOrNull()?.let { maxChargingVoltage = it }

                map["batteryStatus"]?.let { batteryStatus = it }
                map["batteryHealth"]?.let { batteryHealth = it }
                map["batteryLevel"]?.toIntOrNull()?.let { batteryLevel = it }
                map["batteryTechnology"]?.let { batteryTechnology = it }

                map["batteryVoltageMillivolts"]?.toLongOrNull()?.let { batteryVoltage = it }
                map["batteryTemperatureTenthsCelsius"]?.toFloatOrNull()?.let { batteryTemperature = it }

                map["batteryCycleCount"]?.toIntOrNull()?.let { batteryCycleCount = it }
                map["batteryFullChargeUah"]?.toLongOrNull()?.let { batteryFullCharge = it }
                map["batteryFullChargeDesignCapacityUah"]?.toLongOrNull()?.let { batteryFullChargeDesign = it }
                map["batteryChargeTimeToFullNowSeconds"]?.toLongOrNull()?.let { batteryChargeTimeToFullNow = it }

                // 单行已捕获全部 100% 数据，直接瞬时早停！
                Log.d(TAG, "已从单行结构体中完整获取全部电池核心字段，第 $totalLineCount 行瞬时极速早停！")
                break
            }

            // 3. 核心匹配 2：兼容多行键值对匹配（ac: 0 usb: 1 / level: 73 voltage: 4192 等格式）
            if (trimLine.contains("usb:", ignoreCase = true) || trimLine.contains("chargerUsbOnline", ignoreCase = true)) {
                if (chargerUsbOnline == null) {
                    chargerUsbOnline = parseBooleanValue(trimLine) ?: (extractInlineValue(trimLine, "usb:")?.toIntOrNull() == 1)
                }
            }
            if (trimLine.contains("ac:", ignoreCase = true) || trimLine.contains("chargerAcOnline", ignoreCase = true)) {
                if (chargerAcOnline == null) {
                    chargerAcOnline = parseBooleanValue(trimLine) ?: (extractInlineValue(trimLine, "ac:")?.toIntOrNull() == 1)
                }
            }
            if (trimLine.contains("wireless:", ignoreCase = true) || trimLine.contains("chargerWirelessOnline", ignoreCase = true)) {
                if (chargerWirelessOnline == null) {
                    chargerWirelessOnline = parseBooleanValue(trimLine) ?: (extractInlineValue(trimLine, "wireless:")?.toIntOrNull() == 1)
                }
            }
            if (trimLine.contains("dock:", ignoreCase = true) || trimLine.contains("chargerDockOnline", ignoreCase = true)) {
                if (chargerDockOnline == null) {
                    chargerDockOnline = parseBooleanValue(trimLine) ?: (extractInlineValue(trimLine, "dock:")?.toIntOrNull() == 1)
                }
            }

            if (trimLine.contains("current_max:", ignoreCase = true) || trimLine.contains("maxChargingCurrent", ignoreCase = true)) {
                val v = extractInlineValue(trimLine, "current_max:")?.toLongOrNull() ?: extractLongValue(trimLine)
                if (v != null && v > 0 && maxChargingCurrent == null) maxChargingCurrent = v
            }
            if (trimLine.contains("voltage_max:", ignoreCase = true) || trimLine.contains("maxChargingVoltage", ignoreCase = true)) {
                val v = extractInlineValue(trimLine, "voltage_max:")?.toLongOrNull() ?: extractLongValue(trimLine)
                if (v != null && v > 0 && maxChargingVoltage == null) maxChargingVoltage = v
            }

            if (trimLine.contains("status:", ignoreCase = true) || trimLine.contains("batteryStatus", ignoreCase = true)) {
                if (batteryStatus == null) batteryStatus = extractInlineValue(trimLine, "status:") ?: extractStringValue(trimLine)
            }
            if (trimLine.contains("health:", ignoreCase = true) || trimLine.contains("batteryHealth", ignoreCase = true)) {
                if (batteryHealth == null) batteryHealth = extractInlineValue(trimLine, "health:") ?: extractStringValue(trimLine)
            }

            if (trimLine.contains("level:", ignoreCase = true) || trimLine.contains("batteryLevel", ignoreCase = true)) {
                val v = extractInlineValue(trimLine, "level:")?.toIntOrNull() ?: extractIntValue(trimLine)
                if (v != null && v in 0..100 && batteryLevel == null) batteryLevel = v
            }
            if (trimLine.contains("voltage:", ignoreCase = true) || trimLine.contains("batteryVoltage", ignoreCase = true)) {
                val v = extractInlineValue(trimLine, "voltage:")?.toLongOrNull() ?: extractLongValue(trimLine)
                if (v != null && v > 0 && batteryVoltage == null) batteryVoltage = v
            }
            if (trimLine.contains("temp:", ignoreCase = true) || trimLine.contains("temperature", ignoreCase = true)) {
                val v = extractInlineValue(trimLine, "temp:")?.toFloatOrNull() ?: extractFloatValue(trimLine)
                if (v != null && v > 0 && batteryTemperature == null) batteryTemperature = v
            }

            if (trimLine.contains("cycle count:", ignoreCase = true) || trimLine.contains("batteryCycleCount", ignoreCase = true)) {
                val v = extractInlineValue(trimLine, "cycle count:")?.toIntOrNull() ?: extractIntValue(trimLine)
                if (v != null && v > 0 && batteryCycleCount == null) batteryCycleCount = v
            }
            if (trimLine.contains("Full charge:", ignoreCase = true) || trimLine.contains("batteryFullCharge", ignoreCase = true)) {
                val v = extractInlineValue(trimLine, "Full charge:")?.toLongOrNull() ?: extractLongValue(trimLine)
                if (v != null && v > 0 && batteryFullCharge == null) batteryFullCharge = v
            }
            if (trimLine.contains("batteryFullChargeDesign", ignoreCase = true) || trimLine.contains("Estimated battery capacity:", ignoreCase = true)) {
                val v = extractLongValue(trimLine)
                if (v != null && v > 0 && batteryFullChargeDesign == null) batteryFullChargeDesign = v
            }

            // 4. 多行兜底早停
            if (batteryVoltage != null && batteryLevel != null && batteryFullCharge != null && batteryCycleCount != null) {
                if (trimLine.startsWith("---------") || trimLine.startsWith("DUMP OF SERVICE")) {
                    Log.d(TAG, "多行日志已收集齐关键电池参数，第 $totalLineCount 行早停退出！")
                    break
                }
            }
        }

        // 5. 组装 14 项精简表格
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
        val voltVal = batteryVoltage
        val voltDisplay = if (voltVal != null) {
            val mv = when {
                voltVal in 2500L..9500L -> voltVal.toFloat()
                voltVal in 2500000L..9500000L -> voltVal / 1000f
                voltVal > 9500L -> voltVal / 1000f
                else -> voltVal.toFloat()
            }
            String.format("%.3f V", mv / 1000f)
        } else "未知"
        tableItems.add(HealthInfoItem("🔋 电池电压", voltDisplay, "当前电池端电压"))

        // 5. 🌡️ 电池温度
        val tempVal = batteryTemperature
        val tempDisplay = if (tempVal != null) {
            val degC = if (tempVal > 200f) tempVal / 10f else tempVal
            String.format("%.1f℃", degC)
        } else "未知"
        tableItems.add(HealthInfoItem("🌡️ 电池温度", tempDisplay, "当前温度"))

        // 6. 🔄 循环次数
        val cycleDisplay = if (batteryCycleCount != null) "$batteryCycleCount" else "未知"
        tableItems.add(HealthInfoItem("🔄 循环次数", cycleDisplay, "等效完整循环"))

        // 7. 🔋 设计容量
        val designVal = batteryFullChargeDesign
        val designDisplay = if (designVal != null) {
            val mah = if (designVal < 100000L) designVal else designVal / 1000L
            "$mah mAh"
        } else "未知"
        tableItems.add(HealthInfoItem("🔋 设计容量", designDisplay, "出厂设计容量"))

        // 8. 🔋 当前满充容量
        val fccVal = batteryFullCharge
        val fccDisplay = if (fccVal != null) {
            val mah = if (fccVal < 100000L) fccVal else fccVal / 1000L
            "$mah mAh"
        } else "未知"
        tableItems.add(HealthInfoItem("🔋 当前满充容量", fccDisplay, "电量计估算 FCC"))

        // 9. ❤️ 理论容量健康度
        val healthDisplay = if (fccVal != null && designVal != null && designVal > 0L) {
            val fMah = if (fccVal < 100000L) fccVal.toFloat() else fccVal / 1000f
            val dMah = if (designVal < 100000L) designVal.toFloat() else designVal / 1000f
            val healthPercent = (fMah / dMah) * 100f
            String.format("%.1f%%", healthPercent)
        } else "未知"
        tableItems.add(HealthInfoItem("❤️ 理论容量健康度", healthDisplay, "FCC / Design Capacity"))

        // 10. ⚡ 最大充电电流
        val curVal = maxChargingCurrent
        val maxCurDisplay = if (curVal != null) {
            val ma = if (curVal >= 1000L) curVal / 1000L else curVal
            "$ma mA"
        } else "未知"
        tableItems.add(HealthInfoItem("⚡ 最大充电电流", maxCurDisplay, "系统报告值"))

        // 11. ⚡ 最大充电电压
        val voltMaxVal = maxChargingVoltage
        val maxVoltDisplay = if (voltMaxVal != null) {
            val v = if (voltMaxVal >= 1000000L) (voltMaxVal / 1000000f).toInt() else if (voltMaxVal >= 1000L) (voltMaxVal / 1000f).toInt() else voltMaxVal.toInt()
            "$v V"
        } else "未知"
        tableItems.add(HealthInfoItem("⚡ 最大充电电压", maxVoltDisplay, "系统报告值"))

        // 12. ⏱️ 预计充满时间
        val timeVal = batteryChargeTimeToFullNow
        val timeDisplay = if (timeVal == null || timeVal < 0L) {
            "未知"
        } else {
            val mins = timeVal / 60L
            "$mins 分钟"
        }
        val timeDesc = if (timeVal == null || timeVal < 0L) "返回 -1" else "预计充满时间"
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

        return BugreportResult(tableItems, rawText, hasRealData)
    }

    private fun extractInlineValue(line: String, key: String): String? {
        val idx = line.indexOf(key, ignoreCase = true)
        if (idx == -1) return null
        val sub = line.substring(idx + key.length).trim()
        return sub.split(" ")[0].trim()
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
