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
 * 负责深度、高性能解析安卓错误报告（Bugreport）中 HealthInfo / getHealthInfo 结构体的解析器。
 * 覆盖：充电器信息、电池基本状态、电压温度、电流功率、容量与健康度、策略扩展等全量分类。
 */
class BugreportParser {

    companion object {
        private const val TAG = "BugreportParser"
    }

    /**
     * 从选定的文件 URI 流式极速解析错误报告日志并提取 getHealthInfo 全量分类表格条目。
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
     * 逐行读取流并深度解析 getHealthInfo / HealthInfo 全量字段。
     *
     * @param reader 缓冲字符读取流
     * @return 包含全部分类与规范三列表格信息的 [HealthInfoItem] 列表
     */
    private suspend fun parseFromReader(reader: BufferedReader): List<HealthInfoItem> {
        // 1. 充电器相关
        var chargerAcOnline: Boolean? = null
        var chargerUsbOnline: Boolean? = null
        var chargerWirelessOnline: Boolean? = null
        var chargerDockOnline: Boolean? = null
        var maxChargingCurrentMicroamps: Long? = null
        var maxChargingVoltageMicrovolts: Long? = null

        // 2. 电池基本状态
        var batteryStatus: String? = null
        var batteryHealth: String? = null
        var batteryPresent: Boolean? = null
        var batteryLevel: Int? = null
        var batteryTechnology: String? = null

        // 3. 电压与温度
        var batteryVoltageMillivolts: Long? = null
        var batteryTemperatureTenthsCelsius: Float? = null

        // 4. 电流与功率
        var batteryCurrentMicroamps: Long? = null
        var batteryCurrentAverageMicroamps: Long? = null

        // 5. 电池容量与循环
        var batteryCycleCount: Int? = null
        var batteryFullChargeUah: Long? = null
        var batteryChargeCounterUah: Long? = null
        var batteryCapacityLevel: String? = null
        var batteryChargeTimeToFullNowSeconds: Long? = null
        var batteryFullChargeDesignCapacityUah: Long? = null

        // 6. 策略与扩展状态
        var chargingState: String? = null
        var chargingPolicy: String? = null
        var batteryHealthData: String? = null

        var inHealthInfoSection = false
        var currentSection = ""
        var hasFoundHealthInfoData = false
        var hasFoundBatterySection = false
        var line: String?
        var totalLineCount = 0

        while (reader.readLine().also { line = it } != null) {
            totalLineCount++
            if (!coroutineContext.isActive) {
                return emptyList()
            }
            val curLine = line!!
            val trimLine = curLine.trim()

            // 早停机制（Early Termination）：在读完 HealthInfo / dumpsys battery 段落后，进入其他无关模块时立即早停
            if ((hasFoundHealthInfoData || hasFoundBatterySection) && 
                trimLine.startsWith("DUMP OF SERVICE") && 
                !trimLine.contains("battery") && 
                !trimLine.contains("Health")) {
                if (batteryFullChargeDesignCapacityUah != null || batteryFullChargeUah != null || batteryVoltageMillivolts != null || batteryLevel != null) {
                    Log.d(TAG, "触发早停机制：在第 $totalLineCount 行已获取完整电池参数并退出。")
                    break
                }
            }

            // 纳米级快速行跳过
            if (!inHealthInfoSection && currentSection.isEmpty()) {
                val startsWithDump = trimLine.startsWith("DUMP OF SERVICE")
                val isHealthHeader = trimLine.startsWith("HealthInfo") || trimLine.startsWith("Health HAL") || trimLine.contains("getHealthInfo")
                val isCycleBroadcast = trimLine.contains("android.os.extra.CYCLE_COUNT")

                if (!startsWithDump && !isHealthHeader && !isCycleBroadcast) {
                    continue
                }
            }

            // 进入 Health HAL / getHealthInfo 结构体
            if (trimLine.contains("getHealthInfo", ignoreCase = true) || 
                trimLine.startsWith("HealthInfo_2_") || 
                trimLine.startsWith("HealthInfo:") ||
                trimLine.contains("Health HAL - getHealthInfo") ||
                trimLine.contains("Health HAL:")) {
                inHealthInfoSection = true
                hasFoundHealthInfoData = true
                Log.d(TAG, "进入 HealthInfo 结构体段落: $trimLine")
            }

            if (inHealthInfoSection) {
                // 1. 充电器信息解析
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

                // 2. 电池基本状态
                if (trimLine.contains("batteryStatus", ignoreCase = true) || trimLine.contains("battery_status", ignoreCase = true)) {
                    if (batteryStatus == null) batteryStatus = extractStringValue(trimLine)
                }
                if (trimLine.contains("batteryHealth", ignoreCase = true) || trimLine.contains("battery_health", ignoreCase = true)) {
                    if (batteryHealth == null) batteryHealth = extractStringValue(trimLine)
                }
                if (trimLine.contains("batteryPresent", ignoreCase = true) || trimLine.contains("battery_present", ignoreCase = true)) {
                    val pres = extractStringValue(trimLine).lowercase()
                    if (batteryPresent == null) batteryPresent = (pres == "true" || pres == "1")
                }
                if (trimLine.contains("batteryLevel", ignoreCase = true) || trimLine.contains("battery_level", ignoreCase = true)) {
                    val v = extractIntValue(trimLine)
                    if (v != null && v in 0..100 && batteryLevel == null) batteryLevel = v
                }
                if (trimLine.contains("batteryTechnology", ignoreCase = true) || trimLine.contains("battery_technology", ignoreCase = true)) {
                    val tech = extractStringValue(trimLine)
                    if (tech.isNotEmpty() && batteryTechnology == null) batteryTechnology = tech
                }

                // 3. 电压与温度
                if (trimLine.contains("batteryVoltageMillivolts", ignoreCase = true) || trimLine.contains("batteryVoltage", ignoreCase = true) || trimLine.contains("battery_voltage", ignoreCase = true)) {
                    val v = extractLongValue(trimLine)
                    if (v != null && batteryVoltageMillivolts == null) batteryVoltageMillivolts = v
                }
                if (trimLine.contains("batteryTemperatureTenthsCelsius", ignoreCase = true) || trimLine.contains("batteryTemperature", ignoreCase = true) || trimLine.contains("battery_temperature", ignoreCase = true)) {
                    val v = extractFloatValue(trimLine)
                    if (v != null && v > 0 && batteryTemperatureTenthsCelsius == null) batteryTemperatureTenthsCelsius = v
                }

                // 4. 电流
                if (trimLine.contains("batteryCurrentAverageMicroamps", ignoreCase = true) || trimLine.contains("batteryCurrentAverage", ignoreCase = true)) {
                    val v = extractLongValue(trimLine)
                    if (v != null && batteryCurrentAverageMicroamps == null) batteryCurrentAverageMicroamps = v
                } else if (trimLine.contains("batteryCurrentMicroamps", ignoreCase = true) || trimLine.contains("batteryCurrent", ignoreCase = true) || trimLine.contains("battery_current", ignoreCase = true)) {
                    val v = extractLongValue(trimLine)
                    if (v != null && batteryCurrentMicroamps == null) batteryCurrentMicroamps = v
                }

                // 5. 循环与容量
                if (trimLine.contains("batteryCycleCount", ignoreCase = true) || trimLine.contains("battery_cycle_count", ignoreCase = true)) {
                    val v = extractIntValue(trimLine)
                    if (v != null && v > 0 && batteryCycleCount == null) batteryCycleCount = v
                }
                if (trimLine.contains("batteryFullChargeUah", ignoreCase = true) || trimLine.contains("batteryFullChargeCapacityUah", ignoreCase = true) || trimLine.contains("batteryFullCharge", ignoreCase = true) || trimLine.contains("battery_full_charge", ignoreCase = true)) {
                    val v = extractLongValue(trimLine)
                    if (v != null && v > 0 && batteryFullChargeUah == null) batteryFullChargeUah = v
                }
                if (trimLine.contains("batteryChargeCounterUah", ignoreCase = true) || trimLine.contains("batteryChargeCounter", ignoreCase = true) || trimLine.contains("battery_charge_counter", ignoreCase = true)) {
                    val v = extractLongValue(trimLine)
                    if (v != null && v > 0 && batteryChargeCounterUah == null) batteryChargeCounterUah = v
                }
                if (trimLine.contains("batteryCapacityLevel", ignoreCase = true) || trimLine.contains("battery_capacity_level", ignoreCase = true)) {
                    if (batteryCapacityLevel == null) batteryCapacityLevel = extractStringValue(trimLine)
                }
                if (trimLine.contains("batteryChargeTimeToFullNowSeconds", ignoreCase = true) || trimLine.contains("batteryChargeTimeToFullNow", ignoreCase = true)) {
                    val v = extractLongValue(trimLine)
                    if (v != null && batteryChargeTimeToFullNowSeconds == null) batteryChargeTimeToFullNowSeconds = v
                }
                if (trimLine.contains("batteryFullChargeDesignCapacityUah", ignoreCase = true) || trimLine.contains("battery_design_capacity", ignoreCase = true) || trimLine.contains("batteryFullChargeDesign", ignoreCase = true)) {
                    val v = extractLongValue(trimLine)
                    if (v != null && v > 0 && batteryFullChargeDesignCapacityUah == null) batteryFullChargeDesignCapacityUah = v
                }

                // 6. 策略扩展
                if (trimLine.contains("chargingState", ignoreCase = true)) {
                    if (chargingState == null) chargingState = extractStringValue(trimLine)
                }
                if (trimLine.contains("chargingPolicy", ignoreCase = true)) {
                    if (chargingPolicy == null) chargingPolicy = extractStringValue(trimLine)
                }
                if (trimLine.contains("batteryHealthData", ignoreCase = true)) {
                    if (batteryHealthData == null) batteryHealthData = extractStringValue(trimLine)
                }
            }

            // DUMP OF SERVICE battery 段落兜底提取
            if (trimLine.startsWith("DUMP OF SERVICE battery:")) {
                currentSection = "battery"
                hasFoundBatterySection = true
            } else if (trimLine.startsWith("DUMP OF SERVICE batterystats:")) {
                currentSection = "batterystats"
            } else if (trimLine.startsWith("DUMP OF SERVICE") || trimLine.startsWith("------ ")) {
                if (!trimLine.contains("battery") && !trimLine.contains("Health")) {
                    currentSection = ""
                    inHealthInfoSection = false
                }
            }

            if (currentSection == "battery") {
                if (trimLine.startsWith("AC powered:") && chargerAcOnline == null) {
                    chargerAcOnline = trimLine.substringAfter(":").trim().toBooleanStrictOrNull()
                }
                if (trimLine.startsWith("USB powered:") && chargerUsbOnline == null) {
                    chargerUsbOnline = trimLine.substringAfter(":").trim().toBooleanStrictOrNull()
                }
                if (trimLine.startsWith("Wireless powered:") && chargerWirelessOnline == null) {
                    chargerWirelessOnline = trimLine.substringAfter(":").trim().toBooleanStrictOrNull()
                }
                if (trimLine.startsWith("Dock powered:") && chargerDockOnline == null) {
                    chargerDockOnline = trimLine.substringAfter(":").trim().toBooleanStrictOrNull()
                }
                if (trimLine.startsWith("Max charging current:") && maxChargingCurrentMicroamps == null) {
                    maxChargingCurrentMicroamps = trimLine.substringAfter(":").trim().toLongOrNull()
                }
                if (trimLine.startsWith("Max charging voltage:") && maxChargingVoltageMicrovolts == null) {
                    maxChargingVoltageMicrovolts = trimLine.substringAfter(":").trim().toLongOrNull()
                }
                if (trimLine.startsWith("level:") && batteryLevel == null) {
                    batteryLevel = trimLine.substringAfter(":").trim().toIntOrNull()
                }
                if (trimLine.startsWith("voltage:") && batteryVoltageMillivolts == null) {
                    batteryVoltageMillivolts = trimLine.substringAfter(":").trim().toLongOrNull()
                }
                if (trimLine.startsWith("temperature:") && batteryTemperatureTenthsCelsius == null) {
                    batteryTemperatureTenthsCelsius = trimLine.substringAfter(":").trim().toFloatOrNull()
                }
                if (trimLine.startsWith("status:") && batteryStatus == null) {
                    batteryStatus = trimLine.substringAfter(":").trim()
                }
                if (trimLine.startsWith("health:") && batteryHealth == null) {
                    batteryHealth = trimLine.substringAfter(":").trim()
                }
                if (trimLine.startsWith("present:") && batteryPresent == null) {
                    batteryPresent = trimLine.substringAfter(":").trim().toBooleanStrictOrNull()
                }
                if (trimLine.startsWith("technology:") && batteryTechnology == null) {
                    batteryTechnology = trimLine.substringAfter(":").trim()
                }
                if (trimLine.startsWith("Charge counter:") && batteryChargeCounterUah == null) {
                    batteryChargeCounterUah = trimLine.substringAfter(":").trim().toLongOrNull()
                }
                if ((trimLine.contains("cycle count:") || trimLine.contains("mCycleCount:")) && batteryCycleCount == null) {
                    batteryCycleCount = trimLine.substringAfter(":").trim().toIntOrNull()
                }
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

        // 3. 构建全量分类的三列表格数据项
        val resultList = mutableListOf<HealthInfoItem>()

        // ------------------ 1. 充电器相关 ------------------
        if (chargerUsbOnline != null) {
            resultList.add(
                HealthInfoItem(
                    rawField = "chargerUsbOnline",
                    displayValue = if (chargerUsbOnline) "true (USB 充电中)" else "false (否)",
                    meaning = "是否通过 USB 数据线接口连接充电"
                )
            )
        }

        if (chargerAcOnline != null) {
            resultList.add(
                HealthInfoItem(
                    rawField = "chargerAcOnline",
                    displayValue = if (chargerAcOnline) "true (AC 适配器)" else "false (否)",
                    meaning = "是否检测到交流电源/传统独立电源适配器"
                )
            )
        }

        if (chargerWirelessOnline != null) {
            resultList.add(
                HealthInfoItem(
                    rawField = "chargerWirelessOnline",
                    displayValue = if (chargerWirelessOnline) "true (无线充电中)" else "false (否)",
                    meaning = "是否通过无线充电底座/线圈进行充电"
                )
            )
        }

        if (chargerDockOnline != null) {
            resultList.add(
                HealthInfoItem(
                    rawField = "chargerDockOnline",
                    displayValue = if (chargerDockOnline) "true (Dock 底座)" else "false (否)",
                    meaning = "是否通过专用扩展坞/底座触点充电"
                )
            )
        }

        if (maxChargingCurrentMicroamps != null) {
            val ma = maxChargingCurrentMicroamps / 1000
            resultList.add(
                HealthInfoItem(
                    rawField = "maxChargingCurrentMicroamps",
                    displayValue = "$ma mA ($maxChargingCurrentMicroamps µA)",
                    meaning = "系统报告的最大允许充电电流能力（非实时电流）"
                )
            )
        }

        if (maxChargingVoltageMicrovolts != null) {
            val v = maxChargingVoltageMicrovolts / 1000000f
            resultList.add(
                HealthInfoItem(
                    rawField = "maxChargingVoltageMicrovolts",
                    displayValue = String.format("%.1f V (%d µV)", v, maxChargingVoltageMicrovolts),
                    meaning = "系统报告的最大允许充电电压限制（非电芯电压）"
                )
            )
        }

        // ------------------ 2. 电池基本状态 ------------------
        if (batteryStatus != null) {
            val desc = formatStatusDesc(batteryStatus)
            resultList.add(
                HealthInfoItem(
                    rawField = "batteryStatus",
                    displayValue = "$batteryStatus ($desc)",
                    meaning = "当前电池充放电工作状态"
                )
            )
        }

        if (batteryHealth != null) {
            val desc = formatHealthDesc(batteryHealth)
            resultList.add(
                HealthInfoItem(
                    rawField = "batteryHealth",
                    displayValue = "$batteryHealth ($desc)",
                    meaning = "Android 判定电池硬件物理健康与保护状态"
                )
            )
        }

        if (batteryPresent != null) {
            resultList.add(
                HealthInfoItem(
                    rawField = "batteryPresent",
                    displayValue = if (batteryPresent) "true (检测到电池)" else "false (未检测到电池)",
                    meaning = "物理电池是否正常连接在位"
                )
            )
        }

        if (batteryLevel != null) {
            resultList.add(
                HealthInfoItem(
                    rawField = "batteryLevel",
                    displayValue = "$batteryLevel%",
                    meaning = "电量计算法拟合出的当前剩余电量百分比"
                )
            )
        }

        if (batteryTechnology != null) {
            resultList.add(
                HealthInfoItem(
                    rawField = "batteryTechnology",
                    displayValue = "$batteryTechnology (锂离子电池)",
                    meaning = "电池化学材料类型"
                )
            )
        }

        // ------------------ 3. 电池电压与温度 ------------------
        if (batteryVoltageMillivolts != null) {
            val mv = when {
                batteryVoltageMillivolts in 2500..9500 -> batteryVoltageMillivolts.toFloat()
                batteryVoltageMillivolts in 2500000..9500000 -> batteryVoltageMillivolts / 1000f
                batteryVoltageMillivolts > 9500 -> batteryVoltageMillivolts / 1000f
                else -> batteryVoltageMillivolts.toFloat()
            }
            resultList.add(
                HealthInfoItem(
                    rawField = "batteryVoltageMillivolts",
                    displayValue = String.format("%.3f V (%.0f mV)", mv / 1000f, mv),
                    meaning = "当前电池端实际工作电压（单电芯正常在 3.0V~4.4V）"
                )
            )
        }

        if (batteryTemperatureTenthsCelsius != null) {
            val degC = if (batteryTemperatureTenthsCelsius > 200) batteryTemperatureTenthsCelsius / 10f else batteryTemperatureTenthsCelsius
            resultList.add(
                HealthInfoItem(
                    rawField = "batteryTemperatureTenthsCelsius",
                    displayValue = String.format("%.1f ℃", degC),
                    meaning = "电池电芯当前温度（单位为 0.1 摄氏度）"
                )
            )
        }

        // ------------------ 4. 当前电池电流与功率 ------------------
        if (batteryCurrentMicroamps != null) {
            val ma = batteryCurrentMicroamps / 1000f
            resultList.add(
                HealthInfoItem(
                    rawField = "batteryCurrentMicroamps",
                    displayValue = String.format("%d µA (%.3f mA)", batteryCurrentMicroamps, ma),
                    meaning = "当前电池瞬时电流（部分机型该字段为驱动估值）"
                )
            )
        }

        if (batteryCurrentAverageMicroamps != null) {
            val ma = batteryCurrentAverageMicroamps / 1000f
            resultList.add(
                HealthInfoItem(
                    rawField = "batteryCurrentAverageMicroamps",
                    displayValue = String.format("%d µA (%.3f mA)", batteryCurrentAverageMicroamps, ma),
                    meaning = "系统报告的平均电流（0 表示无有效平均值）"
                )
            )
        }

        // ------------------ 5. 电池循环与容量 ------------------
        if (batteryCycleCount != null) {
            resultList.add(
                HealthInfoItem(
                    rawField = "batteryCycleCount",
                    displayValue = "$batteryCycleCount cycles (次)",
                    meaning = "累计消耗 100% 容量折算的等效完整充放电循环次数"
                )
            )
        }

        if (batteryFullChargeDesignCapacityUah != null) {
            val mah = if (batteryFullChargeDesignCapacityUah < 100000) batteryFullChargeDesignCapacityUah else batteryFullChargeDesignCapacityUah / 1000
            resultList.add(
                HealthInfoItem(
                    rawField = "batteryFullChargeDesignCapacityUah",
                    displayValue = "$mah mAh ($batteryFullChargeDesignCapacityUah µAh)",
                    meaning = "电池出厂物理规格设计标称容量"
                )
            )
        }

        if (batteryFullChargeUah != null) {
            val mah = if (batteryFullChargeUah < 100000) batteryFullChargeUah else batteryFullChargeUah / 1000
            resultList.add(
                HealthInfoItem(
                    rawField = "batteryFullChargeUah",
                    displayValue = "$mah mAh ($batteryFullChargeUah µAh)",
                    meaning = "硬件电量计当前学习到的电池老化满充容量（FCC）"
                )
            )
        }

        // 计算理论容量健康度
        if (batteryFullChargeUah != null && batteryFullChargeDesignCapacityUah != null && batteryFullChargeDesignCapacityUah > 0) {
            val fMah = if (batteryFullChargeUah < 100000) batteryFullChargeUah.toFloat() else batteryFullChargeUah / 1000f
            val dMah = if (batteryFullChargeDesignCapacityUah < 100000) batteryFullChargeDesignCapacityUah.toFloat() else batteryFullChargeDesignCapacityUah / 1000f
            val healthPercent = (fMah / dMah) * 100f
            resultList.add(
                HealthInfoItem(
                    rawField = "batteryHealthPercent (理论容量健康度)",
                    displayValue = String.format("%.2f%% (约 %.0f%%)", healthPercent, healthPercent),
                    meaning = "理论容量健康度 (FCC / Design Capacity)，受校准算法影响"
                )
            )
        }

        if (batteryChargeCounterUah != null) {
            val raw = batteryChargeCounterUah
            val display = if (raw in 1000..99999) {
                "$raw (疑似厂商映射为 mAh: 约 $raw mAh)"
            } else {
                "${raw / 1000} mAh ($raw µAh)"
            }
            resultList.add(
                HealthInfoItem(
                    rawField = "batteryChargeCounterUah",
                    displayValue = display,
                    meaning = "当前电量计数（部分厂商驱动可能存在单位映射差异）"
                )
            )
        }

        if (batteryCapacityLevel != null) {
            resultList.add(
                HealthInfoItem(
                    rawField = "batteryCapacityLevel",
                    displayValue = batteryCapacityLevel,
                    meaning = "Android 对当前容量状态的分类（NORMAL 代表电量等级正常）"
                )
            )
        }

        if (batteryChargeTimeToFullNowSeconds != null) {
            val display = if (batteryChargeTimeToFullNowSeconds < 0) {
                "-1 (系统未提供/无法计算)"
            } else {
                val mins = batteryChargeTimeToFullNowSeconds / 60
                "$batteryChargeTimeToFullNowSeconds 秒 (约 $mins 分钟)"
            }
            resultList.add(
                HealthInfoItem(
                    rawField = "batteryChargeTimeToFullNowSeconds",
                    displayValue = display,
                    meaning = "预计距离完全充满所需时间（-1 代表未提供）"
                )
            )
        }

        // ------------------ 6. 策略扩展状态 ------------------
        if (chargingState != null) {
            val desc = if (chargingState.equals("INVALID", ignoreCase = true)) "INVALID (未提供扩展策略)" else chargingState
            resultList.add(
                HealthInfoItem(
                    rawField = "chargingState",
                    displayValue = "$chargingState ($desc)",
                    meaning = "充电策略扩展状态（INVALID 代表系统未暴露此项扩展）"
                )
            )
        }

        if (chargingPolicy != null) {
            val desc = if (chargingPolicy.equals("INVALID", ignoreCase = true)) "INVALID (未提供扩展策略)" else chargingPolicy
            resultList.add(
                HealthInfoItem(
                    rawField = "chargingPolicy",
                    displayValue = "$chargingPolicy ($desc)",
                    meaning = "充电管理策略（如保护充电/优化充电，INVALID 代表未设置）"
                )
            )
        }

        if (batteryHealthData != null) {
            resultList.add(
                HealthInfoItem(
                    rawField = "batteryHealthData",
                    displayValue = batteryHealthData,
                    meaning = "高级电池健康接口扩展数据（null 代表无额外数据）"
                )
            )
        }

        Log.d(TAG, "===> 全量 HealthInfo 结构体解析完成，共提取出 ${resultList.size} 个规范字段！")
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
