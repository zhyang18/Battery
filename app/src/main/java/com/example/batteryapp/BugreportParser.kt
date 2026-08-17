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
 * 负责解析安卓系统错误报告（Bugreport）压缩包 (.zip) 或纯文本 (.txt) 的解析器。
 * 采用流式按行扫描机制，防止大文件 OOM，精准提取 dumpsys battery、batterystats 及广播中的电池指标。
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
     * 逐行读取 BufferedReader 流并提取电池相关参数。
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

        var currentSection = ""
        var line: String?

        while (reader.readLine().also { line = it } != null) {
            val trimLine = line!!.trim()

            // 监听段落切换
            if (trimLine.startsWith("DUMP OF SERVICE battery:") || (trimLine.contains("DUMP OF SERVICE") && trimLine.contains("battery"))) {
                currentSection = "battery"
            } else if (trimLine.startsWith("DUMP OF SERVICE batterystats:")) {
                currentSection = "batterystats"
            } else if (trimLine.startsWith("DUMP OF SERVICE activity:")) {
                currentSection = "activity"
            } else if (trimLine.startsWith("DUMP OF SERVICE") || trimLine.startsWith("------ ")) {
                if (!trimLine.contains("battery") && !trimLine.contains("activity")) {
                    currentSection = ""
                }
            }

            // 1. 解析 battery 服务段
            if (currentSection == "battery") {
                if (trimLine.startsWith("level:") && level == null) {
                    level = trimLine.substringAfter("level:").trim().toIntOrNull()
                }
                if (trimLine.startsWith("voltage:") && voltage == null) {
                    val raw = trimLine.substringAfter("voltage:").trim().toLongOrNull()
                    if (raw != null) {
                        voltage = when {
                            raw in 2500..9500 -> raw.toFloat()
                            raw in 2500000..9500000 -> raw / 1000f
                            raw > 9500 -> raw / 1000f
                            else -> raw.toFloat()
                        }
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

            // 2. 解析 batterystats 服务段
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

            // 3. 解析广播中或全局行中的循环次数
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
            source = "错误报告 (Bugreport)"
        )
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
