package com.battery.analysis.domain.capacity.audit

import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * 原始放电采样行物理记录实体。
 *
 * 遵循技术评审 P2-B3.1-A 铁律：
 * 1. 忠实保留硬件底层原始读数、符号极性与标称物理单位，绝不私自篡改或反转极性；
 * 2. 明确记录系统引导标识 [bootId]，若未提供标记为 "BOOT_ID_UNAVAILABLE"，杜绝伪造隔离证据；
 * 3. 严格保留原始 CSV 行号，确保所有后续容量分析与异常定位可完整回溯；
 * 4. 原始电流若缺失如实保持为 null，杜绝伪造数据，仅提供透明派生辅助属性。
 *
 * @property rawLineNumber 原始文件物理行号（从 1 开始）
 * @property timestampMs 记录采样绝对毫秒时间戳
 * @property socPercent 电池电量百分比（SOC 0.0 ~ 100.0）
 * @property rawVoltage 硬件底层未经转换的原始电压数值
 * @property voltageUnit 电压物理单位（如 "mV", "uV", "V"）
 * @property voltageMv 转换为标准毫伏的电压数值（mV）
 * @property rawCurrent 硬件底层未经转换的原始电流数值（严格保留原始硬件正负极性符号，缺失时为 null）
 * @property currentUnit 电流物理单位（如 "mA", "uA", "A"）
 * @property currentMa 转换为标准毫安的电流数值（mA，严格保留原始正负极性，缺失时为 null）
 * @property rawTemperature 硬件底层未经转换的原始温度数值
 * @property temperatureUnit 温度物理单位（如 "C", "0.1C"）
 * @property temperatureC 转换为标准摄氏度的温度数值（℃）
 * @property powerW 瞬时做功功率（W，可选）
 * @property isScreenOn 是否处于亮屏做功状态（可选）
 * @property chargeCounterUah 硬件底层库仑电荷计数器读数（uAh，可选）
 * @property bootId 系统引导标识符（UUID），若系统未暴露则标记为 "BOOT_ID_UNAVAILABLE"
 */
data class RawDischargeRecord(
    val rawLineNumber: Long,
    val timestampMs: Long,
    val socPercent: Double,
    val rawVoltage: Double,
    val voltageUnit: String,
    val voltageMv: Double,
    val rawCurrent: Double?,
    val currentUnit: String,
    val currentMa: Double?,
    val rawTemperature: Double,
    val temperatureUnit: String,
    val temperatureC: Double,
    val powerW: Double? = null,
    val isScreenOn: Boolean? = null,
    val chargeCounterUah: Long? = null,
    val bootId: String? = null
) {
    /**
     * 透明派生放电电流（mA，负值表示做功流出）。
     * 仅在底层 rawCurrent 缺失但具备有效功率与电压时透明计算，绝不污染原始数据。
     */
    val derivedCurrentMa: Double?
        get() = currentMa ?: if (powerW != null && voltageMv > 0.0) {
            -(powerW * 1000.0 / (voltageMv / 1000.0))
        } else {
            null
        }
}

/**
 * 原始放电 CSV 文件审计与完整性检验摘要实体。
 *
 * @property fileName 文件名或来源标识
 * @property fileSha256 数据内容的 SHA-256 哈希指纹（单流读取时同步计算）
 * @property fileSizeBytes 文件总字节数
 * @property totalLines 原始文件解析的总物理行数（含表头）
 * @property validRecordsCount 成功解析并通过物理合理性检验的有效记录数
 * @property invalidLinesCount 格式错误或缺失必要字段的无效行数
 * @property bootIds 采样期间观测到的所有引导标识符集合 [Set<String>]
 * @property isBootIdAvailable 原始数据是否包含真实的系统 boot_id 信息
 * @property hasRebootBoundary 是否检测到跨重启边界（包含多个不同真实 boot_id）
 * @property isRebootIsolationVerified 是否经过可靠的跨重启隔离验证（无真实 boot_id 时严格为 false）
 * @property minVoltageMv 采样期间观测到的最低电压（mV）
 * @property maxVoltageMv 采样期间观测到的最高电压（mV）
 * @property minCurrentMa 采样期间观测到的最小电流（mA，保留符号）
 * @property maxCurrentMa 采样期间观测到的最大电流（mA，保留符号）
 * @property minTemperatureC 采样期间观测到的最低温度（℃）
 * @property maxTemperatureC 采样期间观测到的最高温度（℃）
 * @property startTimeMs 首条有效记录的毫秒时间戳
 * @property endTimeMs 末条有效记录的毫秒时间戳
 * @property durationSeconds 有效采样跨越的实际秒数
 * @property startSocPercent 起始电量百分比（0.0 ~ 100.0）
 * @property endSocPercent 终止电量百分比（0.0 ~ 100.0）
 * @property deltaSocPercent 采样区间电量消耗差值（%）
 * @property integratedReportedPowerFieldWh 对 CSV 中的 power_w 字段进行梯形离散积分得到的功耗积分值（Wh，非真实电池可用能量）
 * @property observedBatteryEnergyWh 经物理校准与边界检验确立的电池真实放出能量（Wh，未验证时严格为 null）
 * @property verifiedDischargeChargeMah 经物理校准与量纲检验确立的电池真实放出库仑量（mAh，未验证时严格为 null）
 * @property normalizedFullUsableEnergyWh 归一化满电可用物理能量（Wh，未验证时严格为 null）
 * @property sohE 能量健康度（SOH_E，未验证时严格为 null）
 * @property isPhysicalEnergyValidated 物理能量合理性边界检验是否通过
 * @property physicalEnergyValidationReason 物理能量合理性边界检验状态说明
 */
data class CsvAuditSummary(
    val fileName: String,
    val fileSha256: String,
    val fileSizeBytes: Long,
    val totalLines: Long,
    val validRecordsCount: Int,
    val invalidLinesCount: Int,
    val bootIds: Set<String>,
    val isBootIdAvailable: Boolean,
    val hasRebootBoundary: Boolean,
    val isRebootIsolationVerified: Boolean,
    val minVoltageMv: Double?,
    val maxVoltageMv: Double?,
    val minCurrentMa: Double?,
    val maxCurrentMa: Double?,
    val minTemperatureC: Double?,
    val maxTemperatureC: Double?,
    val startTimeMs: Long?,
    val endTimeMs: Long?,
    val durationSeconds: Long?,
    val startSocPercent: Double? = null,
    val endSocPercent: Double? = null,
    val deltaSocPercent: Double? = null,
    val integratedReportedPowerFieldWh: Double? = null,
    val observedBatteryEnergyWh: Double? = null,
    val verifiedDischargeChargeMah: Double? = null,
    val normalizedFullUsableEnergyWh: Double? = null,
    val sohE: Double? = null,
    val isPhysicalEnergyValidated: Boolean = false,
    val physicalEnergyValidationReason: String = "UNVALIDATED"
)

/**
 * 放电数据 CSV 独立只读审计回放引擎（DischargeAuditCsvReplayer）。
 *
 * 遵循技术评审 P2-B3.1-A 核心准则：
 * 1. 同流单次哈希：在流式读取字节的同时通过 DigestInputStream 计算 SHA-256，彻底消除预计算与解析时序不一致风险；
 * 2. 原始极性与单位保留：严格保留底层的原始电流极性（无论正负）与物理量纲，杜绝粗暴反转符号或无依据缩放；
 * 3. 严格 boot_id 隔离：检测到多于一个真实 boot_id 时标记重启边界，缺失 boot_id 时标记 BOOT_ID_UNAVAILABLE 且拒绝谎称隔离；
 * 4. 标准 RFC 4180 CSV 解析：健壮支持 UTF-8 BOM 过滤、双引号转义、行内逗号、CRLF / LF 混合换行；
 * 5. 零假数据：字段缺失或非法格式如实计入 invalidLinesCount，严禁人为插值保底伪造数据。
 */
object DischargeAuditCsvReplayer {

    const val BOOT_ID_UNAVAILABLE: String = "BOOT_ID_UNAVAILABLE"

    /**
     * 计算字符串内容的 SHA-256 哈希指纹。
     *
     * @param content 原始文本内容
     * @return 64 位小写十六进制 SHA-256 字符串
     */
    fun computeSha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(content.toByteArray(Charsets.UTF_8))
        return bytesToHex(hashBytes)
    }

    /**
     * 将字节数组转为小写十六进制字符串。
     *
     * @param bytes 字节数组
     * @return 字符串
     */
    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format(Locale.US, "%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    /**
     * 解析 CSV 文本并执行不可变审计与格式提取。
     *
     * @param csvContent 待解析的 CSV 原始文本内容
     * @param sourceName 数据源标识（如文件名）
     * @return 包含审计摘要与有效记录列表的二元组 [Pair<CsvAuditSummary, List<RawDischargeRecord>>]
     */
    fun parseAndAudit(
        csvContent: String,
        sourceName: String = "inline_csv"
    ): Pair<CsvAuditSummary, List<RawDischargeRecord>> {
        val bytes = csvContent.toByteArray(Charsets.UTF_8)
        val byteStream = ByteArrayInputStream(bytes)
        return parseStream(byteStream, sourceName, bytes.size.toLong())
    }

    /**
     * 解析本地文件并执行不可变审计与格式提取。
     *
     * @param file 待解析的本地 CSV 物理文件
     * @return 包含审计摘要与有效记录列表的二元组 [Pair<CsvAuditSummary, List<RawDischargeRecord>>]
     */
    fun parseAndAudit(file: File): Pair<CsvAuditSummary, List<RawDischargeRecord>> {
        require(file.exists() && file.isFile) { "指定的文件不存在或不是有效文件: ${file.absolutePath}" }
        val fileSize = file.length()
        val inputStream = file.inputStream()
        return parseStream(inputStream, file.name, fileSize)
    }

    /**
     * 按 boot_id 将连续采样记录隔离切分为独立会话段。
     *
     * 遵循技术评审 P2-B3.1-A 铁律：
     * 设备重启后（boot_id 改变），由于系统电源管理时钟重置、BMS 读数存在重初始化跳变，
     * 严禁将不同 boot_id 的记录拼接在一起作为连续放电过程。
     *
     * @param records 待分组的记录列表
     * @return 按 boot_id 键值分组后的独立段映射表 [Map<String, List<RawDischargeRecord>>]
     */
    fun segmentByBootId(records: List<RawDischargeRecord>): Map<String, List<RawDischargeRecord>> {
        return records.groupBy { it.bootId ?: BOOT_ID_UNAVAILABLE }
    }

    /**
     * 内部通用流解析器：在同一个流中同步完成 SHA-256 计算与行级解析。
     *
     * @param rawStream 原始数据输入流
     * @param sourceName 数据源名称
     * @param fileSize 数据字节总大小
     * @return 包含审计摘要与有效记录列表的二元组 [Pair<CsvAuditSummary, List<RawDischargeRecord>>]
     */
    private fun parseStream(
        rawStream: InputStream,
        sourceName: String,
        fileSize: Long
    ): Pair<CsvAuditSummary, List<RawDischargeRecord>> {
        val digest = MessageDigest.getInstance("SHA-256")
        val digestStream = DigestInputStream(rawStream, digest)
        val reader = BufferedReader(InputStreamReader(digestStream, Charsets.UTF_8))

        var lineNumber = 0L
        var headerMap: Map<String, Int>? = null
        val validRecords = mutableListOf<RawDischargeRecord>()
        var invalidLines = 0
        val bootIds = mutableSetOf<String>()

        reader.useLines { lines ->
            for (rawLine in lines) {
                lineNumber++
                // 过滤 UTF-8 BOM
                val cleanLine = if (lineNumber == 1L && rawLine.startsWith("\uFEFF")) {
                    rawLine.substring(1).trim()
                } else {
                    rawLine.trim()
                }

                if (cleanLine.isEmpty() || cleanLine.startsWith("#")) {
                    continue
                }

                val tokens = parseCsvLine(cleanLine)

                if (headerMap == null) {
                    headerMap = parseHeader(tokens)
                    continue
                }

                val record = parseRecord(lineNumber, tokens, headerMap!!)
                if (record != null) {
                    validRecords.add(record)
                    val bId = record.bootId ?: BOOT_ID_UNAVAILABLE
                    bootIds.add(bId)
                } else {
                    invalidLines++
                }
            }
        }

        val sha256 = bytesToHex(digest.digest())
        val isBootIdAvailable = bootIds.isNotEmpty() && !bootIds.all { it == BOOT_ID_UNAVAILABLE }
        val hasRebootBoundary = isBootIdAvailable && bootIds.filter { it != BOOT_ID_UNAVAILABLE }.size > 1
        val isRebootIsolationVerified = isBootIdAvailable

        val minVoltage = validRecords.map { it.voltageMv }.minOrNull()
        val maxVoltage = validRecords.map { it.voltageMv }.maxOrNull()
        val currents = validRecords.mapNotNull { it.currentMa }
        val minCurrent = currents.minOrNull()
        val maxCurrent = currents.maxOrNull()
        val minTemp = validRecords.map { it.temperatureC }.minOrNull()
        val maxTemp = validRecords.map { it.temperatureC }.maxOrNull()
        val startTime = validRecords.firstOrNull()?.timestampMs
        val endTime = validRecords.lastOrNull()?.timestampMs
        val duration = if (startTime != null && endTime != null && endTime >= startTime) {
            (endTime - startTime) / 1000L
        } else {
            null
        }

        val startSoc = validRecords.firstOrNull()?.socPercent
        val endSoc = validRecords.lastOrNull()?.socPercent
        val deltaSoc = if (startSoc != null && endSoc != null && startSoc > endSoc) {
            startSoc - endSoc
        } else {
            null
        }

        // 对原始 CSV 的 power_w 字段执行梯形微积分求和
        var integratedJoules = 0.0
        var hasPowerData = false
        for (i in 1 until validRecords.size) {
            val p0 = validRecords[i - 1].powerW
            val p1 = validRecords[i].powerW
            val t0 = validRecords[i - 1].timestampMs
            val t1 = validRecords[i].timestampMs
            val dtSec = (t1 - t0) / 1000.0
            if (p0 != null && p1 != null && dtSec in 0.001..300.0) {
                integratedJoules += (p0 + p1) / 2.0 * dtSec
                hasPowerData = true
            }
        }
        val integratedWh = if (hasPowerData) {
            integratedJoules / 3600.0
        } else {
            null
        }

        // 物理边界合理性检验（Physical Energy Boundary Validation）：
        // 遵循评审铁律：E_observed,partial <= E_full,usable,max。
        // 若局部放电区间积分能量就已超出电池标称满电可用能量上限（默认 32.0Wh），
        // 或按实际掉电 SOC 折算出的 100% 全域能量超出物理允许范围（超额 > 15%），
        // 严格标记为 PHYSICAL_ENERGY_VALIDATION_FAILED，并拒绝赋予真实可用能量语义。
        val ratedFullEnergyWh = 32.0 // 单电芯大容量物理上限基准（Honor 500 Pro 额定约 29.6~30.8Wh）
        var isPhysicalEnergyValidated = true
        var physicalEnergyValidationReason = "PASSED"

        if (integratedWh != null) {
            val equivalentFullEnergyWh = if (deltaSoc != null && deltaSoc >= 5.0) {
                integratedWh / (deltaSoc / 100.0)
            } else {
                null
            }

            if (integratedWh > ratedFullEnergyWh) {
                isPhysicalEnergyValidated = false
                physicalEnergyValidationReason = "PHYSICAL_ENERGY_VALIDATION_FAILED: integrated partial energy (${String.format(Locale.US, "%.2f", integratedWh)}Wh) exceeds rated full battery energy (${ratedFullEnergyWh}Wh)"
            } else if (equivalentFullEnergyWh != null && equivalentFullEnergyWh > ratedFullEnergyWh * 1.15) {
                isPhysicalEnergyValidated = false
                physicalEnergyValidationReason = "PHYSICAL_ENERGY_VALIDATION_FAILED: equivalent full energy (${String.format(Locale.US, "%.2f", equivalentFullEnergyWh)}Wh, derived from delta SOC ${String.format(Locale.US, "%.1f", deltaSoc)}%) exceeds maximum physical bound (${ratedFullEnergyWh * 1.15}Wh)"
            }
        } else {
            isPhysicalEnergyValidated = false
            physicalEnergyValidationReason = "UNVALIDATED: no continuous power field data"
        }

        val summary = CsvAuditSummary(
            fileName = sourceName,
            fileSha256 = sha256,
            fileSizeBytes = fileSize,
            totalLines = lineNumber,
            validRecordsCount = validRecords.size,
            invalidLinesCount = invalidLines,
            bootIds = bootIds,
            isBootIdAvailable = isBootIdAvailable,
            hasRebootBoundary = hasRebootBoundary,
            isRebootIsolationVerified = isRebootIsolationVerified,
            minVoltageMv = minVoltage,
            maxVoltageMv = maxVoltage,
            minCurrentMa = minCurrent,
            maxCurrentMa = maxCurrent,
            minTemperatureC = minTemp,
            maxTemperatureC = maxTemp,
            startTimeMs = startTime,
            endTimeMs = endTime,
            durationSeconds = duration,
            startSocPercent = startSoc,
            endSocPercent = endSoc,
            deltaSocPercent = deltaSoc,
            integratedReportedPowerFieldWh = integratedWh,
            observedBatteryEnergyWh = null,
            verifiedDischargeChargeMah = null,
            normalizedFullUsableEnergyWh = null,
            sohE = null,
            isPhysicalEnergyValidated = isPhysicalEnergyValidated,
            physicalEnergyValidationReason = physicalEnergyValidationReason
        )

        return Pair(summary, validRecords)
    }

    /**
     * 遵循 RFC 4180 标准的双引号与逗号分隔行解析器。
     *
     * @param line 包含一行 CSV 的文本
     * @return 分割后的字段值列表
     */
    fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        val len = line.length

        while (i < len) {
            val c = line[i]
            when {
                c == '\"' -> {
                    if (inQuotes && i + 1 < len && line[i + 1] == '\"') {
                        // 转义的双引号 ""
                        sb.append('\"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                c == ',' && !inQuotes -> {
                    result.add(sb.toString().trim())
                    sb.setLength(0)
                }
                else -> {
                    sb.append(c)
                }
            }
            i++
        }
        result.add(sb.toString().trim())
        return result
    }

    /**
     * 解析 CSV 表头列索引映射。
     *
     * @param tokens 表头各列文本
     * @return 列名称小写到列索引的映射 [Map<String, Int>]
     */
    private fun parseHeader(tokens: List<String>): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        for (i in tokens.indices) {
            val key = tokens[i].lowercase(Locale.US)
                .replace("\"", "")
                .replace(" ", "_")
            map[key] = i
        }
        return map
    }

    /**
     * 将单行文本按表头映射解析为原始采样记录实体。
     *
     * @param lineNumber 行号
     * @param tokens 该行拆分后的字段数组
     * @param headers 表头索引映射
     * @return 若解析成功且核心物理数值有效返回 [RawDischargeRecord]，否则返回 null
     */
    private fun parseRecord(
        lineNumber: Long,
        tokens: List<String>,
        headers: Map<String, Int>
    ): RawDischargeRecord? {
        fun getCol(vararg names: String): String? {
            for (name in names) {
                val idx = headers[name]
                if (idx != null && idx < tokens.size) {
                    val v = tokens[idx].replace("\"", "").trim()
                    if (v.isNotEmpty()) return v
                }
            }
            return null
        }

        val timeStr = getCol("timestamp_ms", "timestamp", "time_ms", "time", "timestamp_elapsed") ?: return null
        val timestampMs = timeStr.toDoubleOrNull()?.toLong() ?: return null

        val socStr = getCol("soc", "soc_percent", "battery_level", "level") ?: return null
        val socPercent = socStr.toDoubleOrNull() ?: return null
        if (socPercent < 0.0 || socPercent > 100.0) return null

        val vStr = getCol("voltage_mv", "voltage", "voltage_now", "v", "voltage_uv") ?: return null
        val rawVoltage = vStr.toDoubleOrNull() ?: return null
        val vUnit = getCol("voltage_unit", "v_unit") ?: when {
            headers.containsKey("voltage_uv") -> "uV"
            headers.containsKey("voltage_mv") -> "mV"
            else -> "mV"
        }
        val voltageMv = when (vUnit.lowercase(Locale.US)) {
            "uv" -> rawVoltage / 1000.0
            "v" -> rawVoltage * 1000.0
            else -> rawVoltage
        }

        // 真实电流读取：如果缺失则保持为 null，杜绝假数据
        val iStr = getCol("current_ma", "current", "current_now", "i", "current_now_ua", "battery_current_sysfs_ua")
        val rawCurrent = iStr?.toDoubleOrNull()
        val iUnit = getCol("current_unit", "i_unit") ?: when {
            headers.containsKey("current_now_ua") || headers.containsKey("battery_current_sysfs_ua") -> "uA"
            headers.containsKey("current_ma") -> "mA"
            else -> "mA"
        }
        val currentMa = if (rawCurrent != null) {
            when (iUnit.lowercase(Locale.US)) {
                "ua" -> rawCurrent / 1000.0
                "a" -> rawCurrent * 1000.0
                else -> rawCurrent
            }
        } else {
            null
        }

        val tStr = getCol("temp_c", "temperature_c", "temperature", "temp", "temperature_raw") ?: return null
        val rawTemperature = tStr.toDoubleOrNull() ?: return null
        val tUnit = getCol("temp_unit", "temperature_unit", "t_unit") ?: when {
            headers.containsKey("temperature_raw") -> "0.1C"
            else -> "C"
        }
        val temperatureC = when (tUnit.lowercase(Locale.US)) {
            "0.1c", "tenth_c", "deci_c" -> rawTemperature / 10.0
            else -> rawTemperature
        }

        val pStr = getCol("power_w", "power", "power_watts", "pwr")
        val powerW = pStr?.toDoubleOrNull()

        val screenStr = getCol("screen_on", "screenon", "is_screen_on")
        val isScreenOn = when (screenStr?.lowercase(Locale.US)) {
            "1", "true", "yes" -> true
            "0", "false", "no" -> false
            else -> null
        }

        val ccStr = getCol("charge_counter_uah", "charge_counter", "cc")
        val chargeCounterUah = ccStr?.toLongOrNull()?.takeIf { it > 0L }

        val rawBootId = getCol("boot_id", "bootid", "boot_uuid")
        val bootId = if (!rawBootId.isNullOrBlank()) rawBootId else BOOT_ID_UNAVAILABLE

        return RawDischargeRecord(
            rawLineNumber = lineNumber,
            timestampMs = timestampMs,
            socPercent = socPercent,
            rawVoltage = rawVoltage,
            voltageUnit = vUnit,
            voltageMv = voltageMv,
            rawCurrent = rawCurrent,
            currentUnit = iUnit,
            currentMa = currentMa,
            rawTemperature = rawTemperature,
            temperatureUnit = tUnit,
            temperatureC = temperatureC,
            powerW = powerW,
            isScreenOn = isScreenOn,
            chargeCounterUah = chargeCounterUah,
            bootId = bootId
        )
    }
}
