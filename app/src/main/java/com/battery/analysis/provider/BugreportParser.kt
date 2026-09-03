package com.battery.analysis.provider

import android.content.Context
import android.net.Uri
import android.util.Log
import com.battery.analysis.model.BatteryInfo
import com.battery.analysis.model.BugreportResult
import com.battery.analysis.model.HealthInfoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

/**
 * 负责瞬时秒级解析安卓错误报告（Bugreport）中 getHealthInfo 结构体的解析器。
 * 采用原生字节流快扫技术，在短时间内完成解压并捕获所有核心电池参数。
 */
class BugreportParser {

    companion object {
        private const val TAG = "BugreportParser"
        private const val BUFFER_SIZE = 1048576 // 1MB 极速缓冲块
        private const val OVERLAP_SIZE = 512    // 512 字节防跨块截断区

        // 预分配字节特征模式常量，避免每次扫描重复转换字节数组
        private val HEALTH_PATTERNS = listOf(
            "getHealthInfo".toByteArray(StandardCharsets.US_ASCII),
            "DUMP OF SERVICE android.hardware.health".toByteArray(StandardCharsets.US_ASCII),
            "DUMP OF SERVICE health".toByteArray(StandardCharsets.US_ASCII),
            "HealthInfo{".toByteArray(StandardCharsets.US_ASCII),
            "DUMP OF SERVICE battery:".toByteArray(StandardCharsets.US_ASCII)
        )

        // 预编译高频正则表达式
        private val REGEX_INSIDE_BRACES = Regex("(\\w+)[=:]\\s*([^,}]+)")
        private val REGEX_FULL_DASH = Regex("(\\d{4})[-_](\\d{2})[-_](\\d{2})[-_](\\d{2})[-_](\\d{2})[-_](\\d{2})")
        private val REGEX_STANDARD = Regex("(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2})")
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
            val initialCaptureTime = extractCaptureTime(fileName)
            Log.d(TAG, "===> 开始极速字节扫描: URI = $uri, 文件名 = $fileName, 初始抓取时间 = $initialCaptureTime")

            val result = if (fileName.endsWith(".zip")) {
                parseZipViaZipFile(context, uri, initialCaptureTime, onProgress)
            } else {
                val rawStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
                val res = scanStreamForHealthInfo(rawStream, initialCaptureTime)
                rawStream.close()
                res
            }

            val costTime = System.currentTimeMillis() - startTime
            Log.d(TAG, "===> 错误报告解析全部完成，总耗时: $costTime ms！")
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
        initialCaptureTime: String,
        onProgress: (Int) -> Unit
    ): BugreportResult? {
        val tempZip = File(context.cacheDir, "temp_bugreport_${System.currentTimeMillis()}.zip")
        try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val outputStream = FileOutputStream(tempZip)
            val buffer = ByteArray(524288)
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
                    if (now - lastReport > 60L) {
                        lastReport = now
                        val pct = ((bytesCopied * 50) / totalBytes).toInt().coerceIn(0, 50)
                        onProgress(pct)
                    }
                }
            }
            outputStream.flush()
            outputStream.close()
            inputStream.close()

            if (!coroutineContext.isActive) return null
            onProgress(60)

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

            // 从内部主日志条目名称尝试提取更精准的抓取时间
            val preciseTime = if (targetEntry.name.contains("202") || targetEntry.name.contains("203")) {
                extractCaptureTime(targetEntry.name, targetEntry.time.takeIf { it > 0 } ?: System.currentTimeMillis())
            } else {
                initialCaptureTime
            }

            Log.d(TAG, "命中主日志条目: ${targetEntry.name}, 抓取时间 = $preciseTime, 启动字节级扫描...")
            onProgress(75)

            val entryStream = zipFile.getInputStream(targetEntry)
            val result = scanStreamForHealthInfo(entryStream, preciseTime)

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
     * 采用原生字节数组滑动窗口快扫输入流，定位并迭代提取最佳 getHealthInfo 电池健康数据。
     *
     * @param stream 输入流
     * @param captureTime 报告抓取日期时间
     * @return 解析得到的 [BugreportResult] 对象
     */
    internal suspend fun scanStreamForHealthInfo(stream: InputStream, captureTime: String): BugreportResult {
        val buffer = ByteArray(BUFFER_SIZE)
        var overlapLen = 0
        var totalBytesRead = 0L
        var bestResult: BugreportResult? = null
        var bestScore = 0

        while (coroutineContext.isActive) {
            val readLen = stream.read(buffer, overlapLen, BUFFER_SIZE - overlapLen)
            if (readLen == -1) break

            val totalInChunk = overlapLen + readLen
            totalBytesRead += readLen

            var searchStart = 0
            while (searchStart < totalInChunk && coroutineContext.isActive) {
                // 寻找当前 chunk 中从 searchStart 开始最早出现的任意目标 pattern
                val match = findEarliestPattern(buffer, totalInChunk, searchStart, HEALTH_PATTERNS) ?: break
                val (matchIdx, matchedPattern) = match

                Log.d(TAG, "在字节偏移 ${totalBytesRead - totalInChunk + matchIdx} 处命中模式: ${String(matchedPattern, StandardCharsets.US_ASCII)}")

                val sliceLength = minOf(16384, totalInChunk - matchIdx)
                val textSlice = String(buffer, matchIdx, sliceLength, StandardCharsets.UTF_8)
                val candidateResult = parseHealthInfoText(textSlice, captureTime)
                val score = calculateQualityScore(candidateResult)

                if (score > bestScore) {
                    bestScore = score
                    bestResult = candidateResult
                    Log.d(TAG, "捕获更高质量电池数据快照，质量分 = $score (hasRealData = ${candidateResult.hasRealData})")

                    // 质量分 >= 3 表示已获取到包含循环次数/容量/电压等核心完整健康数据，可立即返回
                    if (score >= 3) {
                        return candidateResult
                    }
                } else if (bestResult == null) {
                    bestResult = candidateResult
                }

                searchStart = matchIdx + maxOf(1, matchedPattern.size)
            }

            // 防跨块复制
            if (totalInChunk > OVERLAP_SIZE) {
                System.arraycopy(buffer, totalInChunk - OVERLAP_SIZE, buffer, 0, OVERLAP_SIZE)
                overlapLen = OVERLAP_SIZE
            } else {
                overlapLen = 0
            }
        }

        return bestResult ?: BugreportResult(emptyList(), "", false)
    }

    /**
     * 计算解析结果的数据质量完整度评分。
     *
     * @param result 待评估的 [BugreportResult]
     * @return 质量得分（0 表示无有效电池数据，分数越高表示关键指标越齐全）
     */
    private fun calculateQualityScore(result: BugreportResult): Int {
        if (!result.hasRealData) return 0
        val info = result.parsedBatteryInfo ?: return 0
        var score = 0
        if (info.level != null) score += 1
        if (info.voltage != null) score += 1
        if (info.temperature != null) score += 1
        if (info.cycleCount != null) score += 2
        if (info.fullChargeCapacity != null) score += 2
        if (info.designCapacity != null) score += 2
        if (info.batteryHealth != null) score += 1
        return score
    }

    /**
     * 在字节数组指定区间内查找一组模式中最早出现的一个。
     *
     * @param data 字节数据源
     * @param length 有效数据长度
     * @param startIndex 开始搜索的偏移量
     * @param patterns 待匹配的字节模式列表
     * @return 包含命中索引和模式的键值对，若未找到则返回 null
     */
    private fun findEarliestPattern(data: ByteArray, length: Int, startIndex: Int, patterns: List<ByteArray>): Pair<Int, ByteArray>? {
        var minIdx = -1
        var matchedPattern: ByteArray? = null

        for (p in patterns) {
            val idx = indexOfPattern(data, length, startIndex, p)
            if (idx != -1) {
                if (minIdx == -1 || idx < minIdx) {
                    minIdx = idx
                    matchedPattern = p
                }
            }
        }

        return if (minIdx != -1 && matchedPattern != null) Pair(minIdx, matchedPattern) else null
    }

    /**
     * 在字节数组中从指定位置查找目标 ASCII 字节模式。
     *
     * @param data 字节数据源
     * @param length 有效数据长度
     * @param startIndex 开始检索的位置偏移
     * @param pattern 目标字节模式
     * @return 模式起始索引，未找到返回 -1
     */
    private fun indexOfPattern(data: ByteArray, length: Int, startIndex: Int, pattern: ByteArray): Int {
        if (pattern.isEmpty() || length < pattern.size || startIndex > length - pattern.size) return -1
        val first = pattern[0]
        val max = length - pattern.size
        for (i in startIndex..max) {
            if (data[i] == first) {
                var match = true
                for (j in 1 until pattern.size) {
                    if (data[i + j] != pattern[j]) {
                        match = false
                        break
                    }
                }
                if (match) return i
            }
        }
        return -1
    }

    /**
     * 精准解析提取出的纯文本切片。
     *
     * @param text 包含 HealthInfo 的切片文本
     * @param captureTime 报告抓取日期时间
     * @return 结构化结果
     */
    internal fun parseHealthInfoText(text: String, captureTime: String): BugreportResult {
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
        var batteryCurrent: Long? = null
        var batteryTemperature: Float? = null

        var batteryCycleCount: Int? = null
        var batteryChargeCounter: Long? = null
        var batteryFullCharge: Long? = null
        var batteryFullChargeDesign: Long? = null

        val healthInfoRawLines = mutableListOf<String>()
        val lines = text.lines()

        for (curLine in lines) {
            val trimLine = curLine.trim()
            if (trimLine.isEmpty()) continue

            if (rawLinesListShouldStop(trimLine, healthInfoRawLines.size)) {
                break
            }

            if (healthInfoRawLines.size < 35) {
                healthInfoRawLines.add(curLine)
            }

            // 1. 单行 getHealthInfo -> HealthInfo{...} 结构体解析
            if ((trimLine.contains("getHealthInfo", ignoreCase = true) || trimLine.contains("HealthInfo{", ignoreCase = true)) && trimLine.contains("{") && trimLine.contains("}")) {
                val insideBraces = trimLine.substringAfter("{").substringBeforeLast("}")
                val map = REGEX_INSIDE_BRACES.findAll(insideBraces).associate {
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
                map["batteryCurrentMicroamps"]?.toLongOrNull()?.let { batteryCurrent = it }
                map["batteryTemperatureTenthsCelsius"]?.toFloatOrNull()?.let { batteryTemperature = it }

                map["batteryCycleCount"]?.toIntOrNull()?.let { batteryCycleCount = it }
                map["batteryChargeCounterUah"]?.toLongOrNull()?.let { batteryChargeCounter = it }
                map["batteryFullChargeUah"]?.toLongOrNull()?.let { batteryFullCharge = it }
                map["batteryFullChargeDesignCapacityUah"]?.toLongOrNull()?.let { batteryFullChargeDesign = it }

                break
            }

            // 2. 多行兼容匹配
            if (trimLine.contains("usb:", ignoreCase = true) || trimLine.contains("chargerUsbOnline", ignoreCase = true)) {
                if (chargerUsbOnline == null) chargerUsbOnline = parseBooleanValue(trimLine) ?: (extractInlineValue(trimLine, "usb:")?.toIntOrNull() == 1)
            }
            if (trimLine.contains("ac:", ignoreCase = true) || trimLine.contains("chargerAcOnline", ignoreCase = true)) {
                if (chargerAcOnline == null) chargerAcOnline = parseBooleanValue(trimLine) ?: (extractInlineValue(trimLine, "ac:")?.toIntOrNull() == 1)
            }
            if (trimLine.contains("wireless:", ignoreCase = true) || trimLine.contains("chargerWirelessOnline", ignoreCase = true)) {
                if (chargerWirelessOnline == null) chargerWirelessOnline = parseBooleanValue(trimLine) ?: (extractInlineValue(trimLine, "wireless:")?.toIntOrNull() == 1)
            }
            if (trimLine.contains("dock:", ignoreCase = true) || trimLine.contains("chargerDockOnline", ignoreCase = true)) {
                if (chargerDockOnline == null) chargerDockOnline = parseBooleanValue(trimLine) ?: (extractInlineValue(trimLine, "dock:")?.toIntOrNull() == 1)
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
            if (trimLine.contains("current now:", ignoreCase = true) || trimLine.contains("current_now:", ignoreCase = true) || trimLine.contains("batteryCurrent", ignoreCase = true)) {
                val v = extractInlineValue(trimLine, "current now:") ?: extractInlineValue(trimLine, "current_now:")
                val longV = v?.toLongOrNull() ?: extractLongValue(trimLine)
                if (longV != null && batteryCurrent == null) batteryCurrent = longV
            }
            if (trimLine.contains("charge counter:", ignoreCase = true) || trimLine.contains("batteryChargeCounter", ignoreCase = true)) {
                val v = extractInlineValue(trimLine, "charge counter:")?.toLongOrNull() ?: extractLongValue(trimLine)
                if (v != null && batteryChargeCounter == null) batteryChargeCounter = v
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
        }

        // 3. 构建表格项
        val tableItems = mutableListOf<HealthInfoItem>()

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

        val levelVal = batteryLevel
        val levelDisplay = if (levelVal != null) "$levelVal%" else "未知"
        tableItems.add(HealthInfoItem("🔋 电量", levelDisplay, "当前剩余电量"))

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

        val curVal = batteryCurrent
        val curDisplay = if (curVal != null) {
            val ma = if (Math.abs(curVal) >= 10000L) curVal / 1000f else curVal.toFloat()
            String.format("%.0f mA", ma)
        } else "未知"
        tableItems.add(HealthInfoItem("⚡ 电流", curDisplay, "当前电池电流"))

        val powerDisplay = if (voltVal != null && curVal != null) {
            val mv = when {
                voltVal in 2500L..9500L -> voltVal.toFloat()
                voltVal in 2500000L..9500000L -> voltVal / 1000f
                voltVal > 9500L -> voltVal / 1000f
                else -> voltVal.toFloat()
            }
            val ma = if (Math.abs(curVal) >= 10000L) Math.abs(curVal) / 1000f else Math.abs(curVal).toFloat()
            val w = (mv / 1000f) * (ma / 1000f)
            String.format("%.2f W", w)
        } else "未知"
        tableItems.add(HealthInfoItem("⚡ 电池功率", powerDisplay, "电池瞬时工作功率"))

        val tempVal = batteryTemperature
        val tempDisplay = if (tempVal != null) {
            val degC = if (tempVal > 200f) tempVal / 10f else tempVal
            String.format("%.1f℃", degC)
        } else "未知"
        tableItems.add(HealthInfoItem("🌡️ 电池温度", tempDisplay, "当前温度"))

        val cycleDisplay = if (batteryCycleCount != null) "$batteryCycleCount" else "未知"
        tableItems.add(HealthInfoItem("🔄 循环次数", cycleDisplay, "等效完整循环"))

        val designVal = batteryFullChargeDesign
        val designDisplay = if (designVal != null) {
            val mah = if (designVal < 100000L) designVal else designVal / 1000L
            "$mah mAh"
        } else "未知"
        tableItems.add(HealthInfoItem("🔋 设计容量", designDisplay, "出厂设计容量"))

        val fccVal = batteryFullCharge
        val fccDisplay = if (fccVal != null) {
            val mah = if (fccVal < 100000L) fccVal else fccVal / 1000L
            "$mah mAh"
        } else "未知"
        tableItems.add(HealthInfoItem("🔋 充满容量", fccDisplay, "电量计估算 FCC"))

        val chargeVal = batteryChargeCounter
        val chargeDisplay = when {
            chargeVal != null -> {
                val mah = if (chargeVal >= 100000L) chargeVal / 1000L else chargeVal
                "$mah mAh"
            }
            fccVal != null && levelVal != null -> {
                val fMah = if (fccVal < 100000L) fccVal else fccVal / 1000L
                val mah = (fMah * levelVal) / 100L
                "$mah mAh"
            }
            else -> "未知"
        }
        tableItems.add(HealthInfoItem("🔋 当前容量", chargeDisplay, "当前剩余容量"))

        val healthDisplay = if (fccVal != null && designVal != null && designVal > 0L) {
            val fMah = if (fccVal < 100000L) fccVal.toFloat() else fccVal / 1000f
            val dMah = if (designVal < 100000L) designVal.toFloat() else designVal / 1000f
            val healthPercent = (fMah / dMah) * 100f
            String.format("%.2f%%", healthPercent)
        } else "未知"
        tableItems.add(HealthInfoItem("❤️ 健康度", healthDisplay, "FCC / Design Capacity"))

        val techDisplay = batteryTechnology ?: "Li-ion"
        tableItems.add(HealthInfoItem("🔋 电池类型", techDisplay, "锂离子"))

        val healthStatusVal = when {
            batteryHealth?.contains("GOOD", ignoreCase = true) == true || batteryHealth == "2" -> "GOOD"
            batteryHealth?.contains("OVERHEAT", ignoreCase = true) == true || batteryHealth == "3" -> "OVERHEAT (过热)"
            batteryHealth?.contains("DEAD", ignoreCase = true) == true || batteryHealth == "4" -> "DEAD (损坏)"
            batteryHealth?.contains("OVER_VOLTAGE", ignoreCase = true) == true || batteryHealth == "5" -> "OVER_VOLTAGE (电压过高)"
            batteryHealth?.contains("COLD", ignoreCase = true) == true || batteryHealth == "7" -> "COLD (过冷)"
            else -> batteryHealth ?: "GOOD"
        }
        tableItems.add(HealthInfoItem("🩺 电池健康", healthStatusVal, "Android 判断正常"))

        // 增加抓取/报告日期时间
        tableItems.add(HealthInfoItem("⏱️ 报告时间", captureTime, "错误报告生成/抓取时间"))

        val hasRealData = (batteryLevel != null || batteryVoltage != null || batteryFullCharge != null || batteryCycleCount != null || batteryTemperature != null)
        val rawText = healthInfoRawLines.joinToString("\n").trim()

        val parsedBatteryInfo = BatteryInfo(
            batteryHealth = if (fccVal != null && designVal != null && designVal > 0L) {
                val fMah = if (fccVal < 100000L) fccVal.toFloat() else fccVal / 1000f
                val dMah = if (designVal < 100000L) designVal.toFloat() else designVal / 1000f
                (fMah / dMah) * 100f
            } else null,
            healthStatus = healthStatusVal,
            level = levelVal,
            status = statusVal,
            designCapacity = designVal?.let { if (it < 100000L) it.toFloat() else it / 1000f },
            currentCapacity = chargeVal?.let { if (it < 100000L) it.toFloat() else it / 1000f } ?: (if (fccVal != null && levelVal != null) {
                val fMah = if (fccVal < 100000L) fccVal.toFloat() else fccVal / 1000f
                (fMah * levelVal) / 100f
            } else null),
            fullChargeCapacity = fccVal?.let { if (it < 100000L) it.toFloat() else it / 1000f },
            cycleCount = batteryCycleCount,
            temperature = tempVal?.let { if (it > 200f) it / 10f else it },
            voltage = voltVal?.let {
                when {
                    it in 2500L..9500L -> it.toFloat()
                    it in 2500000L..9500000L -> it / 1000f
                    it > 9500L -> it / 1000f
                    else -> it.toFloat()
                }
            },
            currentNow = curVal?.let { if (Math.abs(it) >= 10000L) it / 1000f else it.toFloat() },
            powerWatts = if (voltVal != null && curVal != null) {
                val mv = when {
                    voltVal in 2500L..9500L -> voltVal.toFloat()
                    voltVal in 2500000L..9500000L -> voltVal / 1000f
                    voltVal > 9500L -> voltVal / 1000f
                    else -> voltVal.toFloat()
                }
                val ma = if (Math.abs(curVal) >= 10000L) Math.abs(curVal) / 1000f else Math.abs(curVal).toFloat()
                (mv / 1000f) * (ma / 1000f)
            } else null,
            isDualCell = null,
            technology = techDisplay,
            source = "getHealthInfo",
            captureTime = captureTime
        )

        return BugreportResult(tableItems, rawText, hasRealData, parsedBatteryInfo)
    }

    /**
     * 从文件名或条目名称解析抓取时间，若未能提取到则使用已知时间戳或当前系统时间。
     *
     * @param fileName 文件名或条目名称
     * @param fallbackTime 当前系统时间戳或已知修改时间
     * @return 格式化后的日期时间字符串（如 "2024-05-18 15:30:22"）
     */
    private fun extractCaptureTime(fileName: String, fallbackTime: Long = System.currentTimeMillis()): String {
        // 匹配 YYYY-MM-DD-HH-MM-SS 或 YYYY-MM-DD_HH-MM-SS 或 YYYYMMDD-HHMMSS
        val matchDash = REGEX_FULL_DASH.find(fileName)
        if (matchDash != null) {
            val (y, m, d, hh, mm, ss) = matchDash.destructured
            return "$y-$m-$d $hh:$mm:$ss"
        }

        // 匹配 YYYY-MM-DD HH:MM:SS
        val matchStd = REGEX_STANDARD.find(fileName)
        if (matchStd != null) {
            return matchStd.value
        }

        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(fallbackTime))
    }

    /**
     * 判断当前行是否表明已达到当前服务 DUMP 的结束边界。
     *
     * @param line 当前读取到的日志文本行
     * @param count 当前已收集的日志行数
     * @return 若已达到边界应终止后续收集则返回 true，否则返回 false
     */
    private fun rawLinesListShouldStop(line: String, count: Int): Boolean {
        return count >= 3 && (line.startsWith("DUMP OF SERVICE") || line.startsWith("---------"))
    }

    /**
     * 从包含指定关键字的行中提取紧随其后的行内参数值。
     *
     * @param line 包含目标关键字的文本行
     * @param key 目标参数名称关键字
     * @return 提取出的字符串值，未找到返回 null
     */
    private fun extractInlineValue(line: String, key: String): String? {
        val idx = line.indexOf(key, ignoreCase = true)
        if (idx == -1) return null
        val sub = line.substring(idx + key.length).trim()
        return sub.split(" ")[0].trim()
    }

    /**
     * 解析形如 `key=value` 或 `key: value` 的布尔类型参数值。
     *
     * @param line 待解析的文本行
     * @return 解析得到的布尔值，无法识别返回 null
     */
    private fun parseBooleanValue(line: String): Boolean? {
        val clean = line.replace(",", "").replace(";", "").lowercase()
        val raw = if (clean.contains("=")) clean.substringAfter("=").trim() else clean.substringAfter(":").trim()
        return when {
            raw.startsWith("true") || raw.startsWith("1") -> true
            raw.startsWith("false") || raw.startsWith("0") -> false
            else -> null
        }
    }

    /**
     * 从键值对行中提取长整型数值，自动剥离常见物理单位。
     *
     * @param line 待解析的文本行
     * @return 提取到的长整型数值，解析失败返回 null
     */
    private fun extractLongValue(line: String): Long? {
        val clean = line.replace(",", "").replace(";", "")
        val raw = if (clean.contains("=")) clean.substringAfter("=").trim() else clean.substringAfter(":").trim()
        val numStr = raw.replace("mAh", "").replace("uAh", "").replace("mV", "").replace("uV", "").trim().split(" ")[0].trim()
        return numStr.toLongOrNull()
    }

    /**
     * 从键值对行中提取整型数值，自动剥离百分号等符号。
     *
     * @param line 待解析的文本行
     * @return 提取到的整型数值，解析失败返回 null
     */
    private fun extractIntValue(line: String): Int? {
        val clean = line.replace(",", "").replace(";", "")
        val raw = if (clean.contains("=")) clean.substringAfter("=").trim() else clean.substringAfter(":").trim()
        val numStr = raw.replace("%", "").trim().split(" ")[0].trim()
        return numStr.toIntOrNull()
    }

    /**
     * 从键值对行中提取浮点型数值，自动剥离摄氏度符号。
     *
     * @param line 待解析的文本行
     * @return 提取到的浮点数值，解析失败返回 null
     */
    private fun extractFloatValue(line: String): Float? {
        val clean = line.replace(",", "").replace(";", "")
        val raw = if (clean.contains("=")) clean.substringAfter("=").trim() else clean.substringAfter(":").trim()
        val numStr = raw.replace("C", "").trim().split(" ")[0].trim()
        return numStr.toFloatOrNull()
    }

    /**
     * 从键值对行中提取字符串类型值。
     *
     * @param line 待解析的文本行
     * @return 提取到的纯字符串值
     */
    private fun extractStringValue(line: String): String {
        val clean = line.replace(",", "").replace(";", "")
        val raw = if (clean.contains("=")) clean.substringAfter("=").trim() else clean.substringAfter(":").trim()
        return raw.split(" ")[0].trim()
    }

    /**
     * 从 ContentProvider 或 URI 中检索错误报告文件名。
     *
     * @param context 应用程序上下文
     * @param uri 文件 URI
     * @return 解析得到的文件名字符串
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
