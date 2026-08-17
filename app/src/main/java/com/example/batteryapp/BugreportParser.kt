package com.example.batteryapp

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

/**
 * 负责解析安卓系统错误报告（Bugreport）中 getHealthInfo 结构体及相关电池日志的解析器。
 * 采用流式逐行解析机制，深度提取 Health HAL getHealthInfo 核心字段（容量、健康度、循环、电流、电压、温度等）。
 */
class BugreportParser {

    /**
     * 从选定的文件 URI 流式解析错误报告日志并提取电池信息。
     *
     * @param context 应用程序上下文
     * @param uri 用户选中的错误报告文件 URI
     * @return 解析得到的 [BatteryInfo] 对象，解析失败或未匹配到数据时返回 null
     */
    suspend fun parseFromUri(context: Context, uri: Uri): BatteryInfo? = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver
            val inputStream = contentResolver.openInputStream(uri) ?: return@withContext null
            
            val fileName = getFileName(context, uri).lowercase()
            if (fileName.endsWith(".zip")) {
                parseZipStream(inputStream)
            } else {
                parseTextStream(inputStream)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 从 ZIP 压缩流中寻找并流式解析主错误报告文本文件。
     *
     * @param inputStream ZIP 文件的输入流
     * @return 解析出的 [BatteryInfo]，未找到匹配项则返回 null
     */
    private fun parseZipStream(inputStream: InputStream): BatteryInfo? {
        val zipIn = ZipInputStream(inputStream)
        var entry = zipIn.nextEntry
        while (entry != null) {
            val name = entry.name.lowercase()
            if (!entry.isDirectory && (name.startsWith("bugreport-") && name.endsWith(".txt") || name.endsWith(".txt"))) {
                val reader = BufferedReader(InputStreamReader(zipIn))
                val info = parseFromReader(reader)
                if (info.designCapacity != null || info.fullChargeCapacity != null || info.cycleCount != null || info.level != null) {
                    zipIn.closeEntry()
                    zipIn.close()
                    return info
                }
            }
            zipIn.closeEntry()
            entry = zipIn.nextEntry
        }
        zipIn.close()
        return null
    }

    /**
     * 流式解析纯文本错误报告。
     *
     * @param inputStream 文本文件的输入流
     * @return 解析出的 [BatteryInfo]
     */
    private fun parseTextStream(inputStream: InputStream): BatteryInfo {
        val reader = BufferedReader(InputStreamReader(inputStream))
        val info = parseFromReader(reader)
        reader.close()
        inputStream.close()
        return info
    }

    /**
     * 逐行读取 BufferedReader 流并深度解析 getHealthInfo 结构及 dumpsys 服务段。
     *
     * @param reader 缓冲字符读取流
     * @return 组装后的 [BatteryInfo] 对象
     */
    private fun parseFromReader(reader: BufferedReader): BatteryInfo {
        var designCapacity: Float? = null
        var fullChargeCapacity: Float? = null
        var currentCapacity: Float? = null
        var cycleCount: Int? = null
        var level: Int? = null
        var statusStr: String? = null
        var healthStatusStr: String? = null
        var temperature: Float? = null
        var voltage: Float? = null
        var currentNow: Float? = null
        var technology: String? = null

        var inHealthInfoSection = false
        var currentSection = ""
        var line: String?

        while (reader.readLine().also { line = it } != null) {
            val trimLine = line!!.trim()

            // 1. 优先捕获 getHealthInfo / HealthInfo 硬件抽象层段落
            if (trimLine.contains("getHealthInfo", ignoreCase = true) || 
                trimLine.startsWith("HealthInfo_2_") || 
                trimLine.startsWith("HealthInfo:") ||
                trimLine.contains("Health HAL - getHealthInfo") ||
                trimLine.contains("Health HAL:")) {
                inHealthInfoSection = true
            }

            if (inHealthInfoSection) {
                // 解析 getHealthInfo 中的核心硬件字段
                // 1.1 满电设计容量 (batteryFullChargeDesignCapacityUah / battery_design_capacity)
                if (trimLine.contains("batteryFullChargeDesignCapacityUah", ignoreCase = true) || 
                    trimLine.contains("battery_design_capacity", ignoreCase = true) ||
                    trimLine.contains("batteryFullChargeDesign", ignoreCase = true)) {
                    val raw = extractLongValue(trimLine)
                    if (raw != null && raw > 0) {
                        designCapacity = if (raw < 100000) raw.toFloat() else raw / 1000f
                    }
                }

                // 1.2 满电学习容量 (batteryFullCharge / batteryFullChargeCapacityUah / battery_full_charge)
                if (trimLine.contains("batteryFullChargeCapacityUah", ignoreCase = true) || 
                    trimLine.contains("batteryFullCharge", ignoreCase = true) || 
                    trimLine.contains("battery_full_charge", ignoreCase = true)) {
                    val raw = extractLongValue(trimLine)
                    if (raw != null && raw > 0) {
                        fullChargeCapacity = if (raw < 100000) raw.toFloat() else raw / 1000f
                    }
                }

                // 1.3 循环次数 (batteryCycleCount / battery_cycle_count)
                if (trimLine.contains("batteryCycleCount", ignoreCase = true) || 
                    trimLine.contains("battery_cycle_count", ignoreCase = true)) {
                    val raw = extractIntValue(trimLine)
                    if (raw != null && raw > 0) {
                        if (cycleCount == null || raw > cycleCount) cycleCount = raw
                    }
                }

                // 1.4 电量百分比 (batteryLevel / battery_level)
                if (trimLine.contains("batteryLevel", ignoreCase = true) || trimLine.contains("battery_level", ignoreCase = true)) {
                    val raw = extractIntValue(trimLine)
                    if (raw != null && raw in 0..100 && level == null) {
                        level = raw
                    }
                }

                // 1.5 电池电压 (batteryVoltage / battery_voltage)
                if (trimLine.contains("batteryVoltage", ignoreCase = true) || trimLine.contains("battery_voltage", ignoreCase = true)) {
                    val raw = extractLongValue(trimLine)
                    if (raw != null) {
                        val norm = normalizeVoltage(raw)
                        if (norm != null && voltage == null) voltage = norm
                    }
                }

                // 1.6 电池瞬时电流 (batteryCurrent / battery_current / batteryCurrentAverage)
                if (trimLine.contains("batteryCurrent", ignoreCase = true) || trimLine.contains("battery_current", ignoreCase = true)) {
                    val raw = extractLongValue(trimLine)
                    if (raw != null) {
                        val norm = normalizeCurrent(raw)
                        if (norm != null && (currentNow == null || currentNow == 0f)) currentNow = norm
                    }
                }

                // 1.7 电池温度 (batteryTemperature / battery_temperature)
                if (trimLine.contains("batteryTemperature", ignoreCase = true) || trimLine.contains("battery_temperature", ignoreCase = true)) {
                    val raw = extractFloatValue(trimLine)
                    if (raw != null && raw > 0 && temperature == null) {
                        temperature = if (raw > 200) raw / 10f else raw
                    }
                }

                // 1.8 电池状态 (batteryStatus / battery_status)
                if (trimLine.contains("batteryStatus", ignoreCase = true) || trimLine.contains("battery_status", ignoreCase = true)) {
                    if (statusStr == null) {
                        statusStr = parseStatus(trimLine)
                    }
                }

                // 1.9 电池健康度描述 (batteryHealth / battery_health)
                if (trimLine.contains("batteryHealth", ignoreCase = true) || trimLine.contains("battery_health", ignoreCase = true)) {
                    if (healthStatusStr == null) {
                        healthStatusStr = parseHealth(trimLine)
                    }
                }

                // 1.10 当前剩余电量 (batteryChargeCounter / battery_charge_counter)
                if (trimLine.contains("batteryChargeCounter", ignoreCase = true) || trimLine.contains("battery_charge_counter", ignoreCase = true)) {
                    val raw = extractLongValue(trimLine)
                    if (raw != null && raw > 0 && currentCapacity == null) {
                        currentCapacity = if (raw < 100000) raw.toFloat() else raw / 1000f
                    }
                }

                // 1.11 电池技术类型 (batteryTechnology / battery_technology)
                if (trimLine.contains("batteryTechnology", ignoreCase = true) || trimLine.contains("battery_technology", ignoreCase = true)) {
                    val tech = extractStringValue(trimLine)
                    if (tech.isNotEmpty() && technology == null) {
                        technology = tech
                    }
                }
            }

            // 2. 监听段落切换 (DUMP OF SERVICE)
            if (trimLine.startsWith("DUMP OF SERVICE battery:") || (trimLine.contains("DUMP OF SERVICE") && trimLine.contains("battery"))) {
                currentSection = "battery"
            } else if (trimLine.startsWith("DUMP OF SERVICE batterystats:")) {
                currentSection = "batterystats"
            } else if (trimLine.startsWith("DUMP OF SERVICE activity:")) {
                currentSection = "activity"
            } else if (trimLine.startsWith("DUMP OF SERVICE") || trimLine.startsWith("------ ")) {
                if (!trimLine.contains("battery") && !trimLine.contains("activity") && !trimLine.contains("Health")) {
                    currentSection = ""
                }
            }

            // 2.1 解析 dumpsys battery 服务段
            if (currentSection == "battery") {
                if (trimLine.startsWith("level:") && level == null) {
                    level = trimLine.substringAfter("level:").trim().toIntOrNull()
                }
                if (trimLine.startsWith("voltage:") && voltage == null) {
                    val raw = trimLine.substringAfter("voltage:").trim().toLongOrNull()
                    if (raw != null) {
                        voltage = normalizeVoltage(raw)
                    }
                }
                if (trimLine.startsWith("temperature:") && temperature == null) {
                    val raw = trimLine.substringAfter("temperature:").trim().toFloatOrNull()
                    if (raw != null && raw > 0) {
                        temperature = raw / 10f
                    }
                }
                if (trimLine.startsWith("status:") && statusStr == null) {
                    statusStr = when (trimLine.substringAfter("status:").trim().toIntOrNull()) {
                        2 -> "充电中"
                        3 -> "放电中"
                        4 -> "未充电"
                        5 -> "已充满"
                        else -> null
                    }
                }
                if (trimLine.startsWith("health:") && healthStatusStr == null) {
                    healthStatusStr = when (trimLine.substringAfter("health:").trim().toIntOrNull()) {
                        2 -> "良好"
                        3 -> "过热"
                        4 -> "损坏"
                        5 -> "电压过高"
                        6 -> "未知故障"
                        7 -> "过冷"
                        else -> null
                    }
                }
                if (trimLine.startsWith("technology:") && technology == null) {
                    val tech = trimLine.substringAfter("technology:").trim()
                    if (tech.isNotEmpty()) technology = tech
                }
                if (trimLine.startsWith("Charge counter:") && currentCapacity == null) {
                    val raw = trimLine.substringAfter("Charge counter:").trim().toLongOrNull()
                    if (raw != null && raw > 0) {
                        currentCapacity = if (raw < 100000) raw.toFloat() else raw / 1000f
                    }
                }
                if (trimLine.contains("cycle count:") || trimLine.contains("mCycleCount:") || trimLine.contains("battery cycle:")) {
                    val count = trimLine.substringAfter(":").trim().toIntOrNull()
                    if (count != null && count > 0) {
                        if (cycleCount == null || count > cycleCount) cycleCount = count
                    }
                }
            }

            // 2.2 解析 dumpsys batterystats 服务段
            if (currentSection == "batterystats") {
                if ((trimLine.startsWith("Estimated battery capacity:") || trimLine.startsWith("Capacity:")) && designCapacity == null) {
                    val raw = trimLine.substringAfter(":").replace("mAh", "").trim().toFloatOrNull()
                    if (raw != null && raw > 0) {
                        designCapacity = raw
                    }
                }
                if ((trimLine.startsWith("Learned battery capacity:") || trimLine.startsWith("Min learned battery capacity:") || trimLine.startsWith("mLearnedCap:")) && fullChargeCapacity == null) {
                    val raw = trimLine.substringAfter(":").replace("mAh", "").trim().toLongOrNull()
                    if (raw != null && raw > 0) {
                        fullChargeCapacity = if (raw < 100000) raw.toFloat() else raw / 1000f
                    }
                }
            }

            // 2.3 解析粘性广播中的循环次数
            if (trimLine.contains("android.os.extra.CYCLE_COUNT=")) {
                val match = Regex("(?i)android\\.os\\.extra\\.CYCLE_COUNT=(\\d+)").find(trimLine)
                if (match != null) {
                    val count = match.groupValues[1].toIntOrNull()
                    if (count != null && count > 0) {
                        if (cycleCount == null || count > cycleCount) cycleCount = count
                    }
                }
            }
        }

        // 计算健康度百分比
        var health: Float? = null
        if (fullChargeCapacity != null && designCapacity != null && designCapacity > 0) {
            health = (fullChargeCapacity / designCapacity) * 100f
            if (health > 100f) health = 100f
        }

        val powerWatts = if (voltage != null && currentNow != null) {
            Math.abs(voltage * currentNow) / 1000000f
        } else null

        return BatteryInfo(
            batteryHealth = health,
            healthStatus = healthStatusStr,
            level = level,
            status = statusStr,
            designCapacity = designCapacity,
            currentCapacity = currentCapacity,
            fullChargeCapacity = fullChargeCapacity,
            cycleCount = cycleCount,
            temperature = temperature,
            voltage = voltage,
            currentNow = currentNow,
            powerWatts = powerWatts,
            technology = technology,
            source = "错误报告 (getHealthInfo)"
        )
    }

    /**
     * 从形如 "key = value" 或 "key: value" 的文本行中提取长整数。
     */
    private fun extractLongValue(line: String): Long? {
        val clean = line.replace(",", "").replace(";", "")
        val raw = if (clean.contains("=")) clean.substringAfter("=").trim() else clean.substringAfter(":").trim()
        val numStr = raw.split(" ")[0].trim()
        return numStr.toLongOrNull()
    }

    /**
     * 从文本行中提取整数。
     */
    private fun extractIntValue(line: String): Int? {
        val clean = line.replace(",", "").replace(";", "")
        val raw = if (clean.contains("=")) clean.substringAfter("=").trim() else clean.substringAfter(":").trim()
        val numStr = raw.split(" ")[0].trim()
        return numStr.toIntOrNull()
    }

    /**
     * 从文本行中提取浮点数。
     */
    private fun extractFloatValue(line: String): Float? {
        val clean = line.replace(",", "").replace(";", "")
        val raw = if (clean.contains("=")) clean.substringAfter("=").trim() else clean.substringAfter(":").trim()
        val numStr = raw.split(" ")[0].trim()
        return numStr.toFloatOrNull()
    }

    /**
     * 从文本行中提取字符串。
     */
    private fun extractStringValue(line: String): String {
        val clean = line.replace(",", "").replace(";", "")
        val raw = if (clean.contains("=")) clean.substringAfter("=").trim() else clean.substringAfter(":").trim()
        return raw.split(" ")[0].trim()
    }

    /**
     * 解析状态枚举值或文本。
     */
    private fun parseStatus(line: String): String? {
        val upper = line.uppercase()
        return when {
            upper.contains("CHARGING") && !upper.contains("NOT_CHARGING") && !upper.contains("DISCHARGING") -> "充电中"
            upper.contains("DISCHARGING") -> "放电中"
            upper.contains("NOT_CHARGING") -> "未充电"
            upper.contains("FULL") -> "已充满"
            upper.contains("2") -> "充电中"
            upper.contains("3") -> "放电中"
            upper.contains("4") -> "未充电"
            upper.contains("5") -> "已充满"
            else -> null
        }
    }

    /**
     * 解析健康状态枚举值或文本。
     */
    private fun parseHealth(line: String): String? {
        val upper = line.uppercase()
        return when {
            upper.contains("GOOD") || upper.contains("2") -> "良好"
            upper.contains("OVERHEAT") || upper.contains("3") -> "过热"
            upper.contains("DEAD") || upper.contains("4") -> "损坏"
            upper.contains("OVER_VOLTAGE") || upper.contains("5") -> "电压过高"
            upper.contains("COLD") || upper.contains("7") -> "过冷"
            upper.contains("UNSPECIFIED_FAILURE") || upper.contains("6") -> "未知故障"
            else -> null
        }
    }

    /**
     * 将原始电压数值规范化为毫伏（mV）。
     */
    private fun normalizeVoltage(rawVolt: Long): Float? {
        if (rawVolt <= 0) return null
        return when {
            rawVolt in 2500..9500 -> rawVolt.toFloat()
            rawVolt in 25000..95000 -> rawVolt / 10f
            rawVolt in 2500000..9500000 -> rawVolt / 1000f
            rawVolt > 9500000 -> rawVolt / 1000f
            else -> null
        }
    }

    /**
     * 将原始电流数值规范化为毫安（mA）。
     */
    private fun normalizeCurrent(rawCur: Long): Float? {
        if (rawCur == 0L) return null
        val abs = Math.abs(rawCur)
        return when {
            abs in 1..9999 -> rawCur.toFloat()
            abs in 10000..99999 -> rawCur / 10f
            abs >= 100000 -> rawCur / 1000f
            else -> rawCur.toFloat()
        }
    }

    /**
     * 获取指定 URI 文件的名称。
     *
     * @param context 应用程序上下文
     * @param uri 文件 URI
     * @return 文件名字符串
     */
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
