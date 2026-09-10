package com.battery.analysis.provider

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.battery.analysis.model.AppPowerUsageItem
import com.battery.analysis.util.NetworkStatsHelper
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Calendar
import java.util.regex.Pattern
import kotlin.math.abs

/**
 * Shizuku 提权系统电池功耗账本解析引擎。
 * 负责通过 Shizuku 穿透执行 dumpsys batterystats --charged，提取自上次充电断开以来的系统权威能耗数据，
 * 精准解析真实亮屏耗时、总放电时长，兼容全系 Android UID 编码格式（如 u0_a234），并将用户安装的三方应用优先置顶展示。
 *
 * @property context 应用程序上下文
 */
class ShizukuBatteryStatsParser(private val context: Context) {

    private val networkStatsHelper = NetworkStatsHelper(context)

    /**
     * 单个应用 UID 的底层权威硬件资源开销统计实体数据类。
     *
     * @property uid 应用程序系统 UID
     * @property cpuUserMs 用户态 CPU 计算时长（毫秒）
     * @property cpuSystemMs 内核系统态 CPU 计算时长（毫秒）
     * @property cpuForegroundMs 前台活跃期间 CPU 计算时长（毫秒）
     * @property cpuBackgroundMs 后台服务/计算期间 CPU 计算时长（毫秒）
     * @property wakelockMs 持有唤醒锁时长（毫秒）
     * @property gpsMs GPS 定位使用时长（毫秒）
     * @property networkBytes 网络传输总字节数（单位：字节 Byte）
     * @property fgsMs 前台服务后台运行时长（毫秒）
     */
    data class UidHardwareStats(
        val uid: Int,
        var cpuUserMs: Long = 0L,
        var cpuSystemMs: Long = 0L,
        var cpuForegroundMs: Long = 0L,
        var cpuBackgroundMs: Long = 0L,
        var wakelockMs: Long = 0L,
        var gpsMs: Long = 0L,
        var networkBytes: Long = 0L,
        var fgsMs: Long = 0L
    ) {
        /**
         * 获取该 UID 的 CPU 总体真实运算耗时（毫秒）。
         * 取用户态与内核态之和与前后台 CPU 之和的较大者。
         *
         * @return CPU 综合总耗时（毫秒）
         */
        fun getTotalCpuMs(): Long {
            val sumUsrSys = cpuUserMs + cpuSystemMs
            val sumFgBg = cpuForegroundMs + cpuBackgroundMs
            return maxOf(sumUsrSys, sumFgBg)
        }
    }

    /**
     * 解析 dumpsys batterystats 返回的整体放电概要信息。
     *
     * @property capacityMah 电池满电/设计容量（mAh）
     * @property computedDrainMah 系统计算得到的总放电量（mAh）
     * @property dischargeDurationMs 自断开充电以来的真实放电总时长（毫秒）
     * @property screenOnDurationMs 自断开充电以来的真实亮屏时长（毫秒）
     * @property screenOffDurationMs 自断开充电以来的真实息屏时长（毫秒）
     * @property screenOffDrainMah 自断开充电以来的真实息屏放电量（mAh）
     * @property screenDrainMah 自断开充电以来的真实屏幕硬件放电量（mAh）
     * @property appList 解析得到的应用耗电实体列表
     * @property historyLevelPoints 解析得到的系统权威电量历史时间点与电量百分比序列列表 [List<Pair<Long, Int>>]
     * @property detectedUnplugTs 从底层历史账本中精确探测到的最近一次断开充电器的物理时间戳（毫秒，可选）
     * @property detectedUnplugLevel 从底层历史账本中精确探测到的最近一次断开充电器瞬间的电池电量（百分比，可选）
     */
    data class BatteryStatsResult(
        val capacityMah: Float,
        val computedDrainMah: Float,
        val dischargeDurationMs: Long,
        val screenOnDurationMs: Long,
        val screenOffDurationMs: Long = 0L,
        val screenOffDrainMah: Float = 0f,
        val appList: List<AppPowerUsageItem>,
        val screenDrainMah: Float = 0f,
        val historyLevelPoints: List<Pair<Long, Int>> = emptyList(),
        val historyTempPoints: List<Pair<Long, Float>> = emptyList(),
        val detectedUnplugTs: Long? = null,
        val detectedUnplugLevel: Int? = null
    )

    /**
     * 执行 dumpsys batterystats 并解析各应用真实功耗与放电概要。
     *
     * @param batteryVoltageVolts 当前测得的电池电压（单位：伏特 V，用于换算 W）
     * @param batteryTempCelsius 当前测得的电池温度（单位：摄氏度 ℃）
     * @return 包含概要及各应用耗电列表的解析结果 [BatteryStatsResult]
     */
    /**
     * 执行 dumpsys batterystats 并解析各应用真实功耗与放电概要。
     * 自动通过 dumpsys batterystats --checkin 与文本段落双通道提取各 UID 真实硬件统计（CPU、网络、唤醒锁与 GPS）。
     *
     * @param batteryVoltageVolts 当前测得的电池电压（单位：伏特 V，用于换算 W）
     * @param batteryTempCelsius 当前测得的电池温度（单位：摄氏度 ℃）
     * @param unplugTime 最近一次断开充电或手动重置的时间戳（毫秒），默认为 0L
     * @return 包含概要及各应用耗电列表的解析结果 [BatteryStatsResult]
     */
    fun parseChargedBatteryStats(
        batteryVoltageVolts: Float,
        batteryTempCelsius: Float,
        unplugTime: Long = 0L
    ): BatteryStatsResult {
        // 1. 通过 Shizuku 提权直接加载全系统所有应用的 UID 到包名映射表（100% 穿透包可见性限制）
        val uidPkgMap = loadUidPackageMapViaShizuku()

        // 2. 优先通过 dumpsys batterystats --checkin 提取结构化硬件指标
        val checkinOutput = executeShizukuCommand("dumpsys batterystats --checkin")
        val checkinHwMap = parseHardwareStatsFromCheckin(checkinOutput)

        // 3. 优先获取自上次断开充电以来的增量账本
        var rawOutput = executeShizukuCommand("dumpsys batterystats --charged")
        if (rawOutput.isBlank() || !rawOutput.contains("Estimated power use")) {
            rawOutput = executeShizukuCommand("dumpsys batterystats")
        }

        if (rawOutput.isBlank()) {
            return BatteryStatsResult(4500f, 0f, 0L, 0L, 0L, 0f, emptyList())
        }

        // 4. 若 checkin 解析为空或缺少部分 UID，结合文本段落进行互补
        val plainHwMap = parseHardwareStatsFromPlainText(rawOutput)
        val combinedHwMap = checkinHwMap.toMutableMap()
        for ((uid, pStats) in plainHwMap) {
            val exist = combinedHwMap[uid]
            if (exist == null) {
                combinedHwMap[uid] = pStats
            } else {
                if (exist.cpuUserMs == 0L && exist.cpuSystemMs == 0L) {
                    exist.cpuUserMs = pStats.cpuUserMs
                    exist.cpuSystemMs = pStats.cpuSystemMs
                }
                if (exist.wakelockMs == 0L) exist.wakelockMs = pStats.wakelockMs
                if (exist.gpsMs == 0L) exist.gpsMs = pStats.gpsMs
                if (exist.networkBytes == 0L) exist.networkBytes = pStats.networkBytes
            }
        }

        return parseStatsText(rawOutput, batteryVoltageVolts, batteryTempCelsius, uidPkgMap, unplugTime, combinedHwMap)
    }

    /**
     * 解析 dumpsys 原始输出字符串。
     *
     * @param rawText dumpsys batterystats 原始文本
     * @param voltageVolts 电池电压
     * @param tempCelsius 电池温度
     * @param uidPkgMap 事先通过提权获取的 UID 到包名映射字典
     * @param unplugTime 最近一次断开充电或手动重置的时间戳（毫秒），默认为 0L
     * @param hwStatsMap 事先通过 Checkin 或文本扫描提取的各 UID 权威硬件开销字典
     * @return 解析完成的 [BatteryStatsResult]
     */
    fun parseStatsText(
        rawText: String,
        voltageVolts: Float,
        tempCelsius: Float,
        uidPkgMap: Map<Int, String> = emptyMap(),
        unplugTime: Long = 0L,
        hwStatsMap: Map<Int, UidHardwareStats> = emptyMap()
    ): BatteryStatsResult {
        val pm = context.packageManager
        var capacityMah = 4500f
        var computedDrainMah = 0f
        var screenDrainMah = 0f
        var dischargeDurationMs = 0L
        var screenOnDurationMs = 0L

        // 1. 匹配整机容量与放电量
        val capMatcher = REGEX_CAP_DRAIN.matcher(rawText)
        if (capMatcher.find()) {
            capacityMah = capMatcher.group(1)?.toFloatOrNull() ?: capacityMah
            computedDrainMah = capMatcher.group(2)?.toFloatOrNull() ?: 0f
        } else {
            val compAloneMatcher = REGEX_COMPUTED_DRAIN_ALONE.matcher(rawText)
            if (compAloneMatcher.find()) {
                computedDrainMah = compAloneMatcher.group(1)?.toFloatOrNull() ?: 0f
            }
        }

        // 2. 匹配自断电以来的总放电耗时
        val timeMatcher = REGEX_TIME_ON_BATTERY.matcher(rawText)
        if (timeMatcher.find()) {
            val timeStr = timeMatcher.group(1) ?: ""
            dischargeDurationMs = parseDurationStringToMs(timeStr)
        }
        if (dischargeDurationMs <= 0L) {
            val disMatcher = REGEX_DISCHARGE_TIME.matcher(rawText)
            if (disMatcher.find()) {
                val timeStr = disMatcher.group(1) ?: ""
                dischargeDurationMs = parseDurationStringToMs(timeStr)
            }
        }
        if (dischargeDurationMs <= 0L) {
            dischargeDurationMs = android.os.SystemClock.elapsedRealtime()
        }

        val now = System.currentTimeMillis()
        val elapsedSinceUnplug = if (unplugTime > 0L) (now - unplugTime).coerceAtLeast(1000L) else 0L
        val isHistoricalDumpsys = unplugTime > 0L && dischargeDurationMs > (elapsedSinceUnplug + 10000L)
        if (unplugTime > 0L && dischargeDurationMs > elapsedSinceUnplug) {
            dischargeDurationMs = elapsedSinceUnplug
        }

        // 3. 匹配自断电以来的真实亮屏时长与真实息屏时长
        val screenMatcher = REGEX_SCREEN_ON.matcher(rawText)
        if (screenMatcher.find()) {
            val screenStr = screenMatcher.group(1) ?: ""
            screenOnDurationMs = parseDurationStringToMs(screenStr)
        }

        var screenOffDurationMs = 0L
        var screenOffDrainMah = 0f

        val screenOffMatcher = REGEX_SCREEN_OFF_TIME.matcher(rawText)
        if (screenOffMatcher.find()) {
            val screenOffStr = screenOffMatcher.group(1) ?: ""
            screenOffDurationMs = parseDurationStringToMs(screenOffStr)
        }
        if (screenOffDurationMs <= 0L) {
            val simpleOffMatcher = REGEX_SCREEN_OFF_SIMPLE.matcher(rawText)
            if (simpleOffMatcher.find()) {
                val screenOffStr = simpleOffMatcher.group(1) ?: ""
                screenOffDurationMs = parseDurationStringToMs(screenOffStr)
            }
        }
        if (screenOffDurationMs <= 0L && dischargeDurationMs > screenOnDurationMs) {
            screenOffDurationMs = (dischargeDurationMs - screenOnDurationMs).coerceAtLeast(0L)
        }

        // 匹配系统底层真实息屏放电量 (mAh)
        val offDrainMatcher = REGEX_SCREEN_OFF_DISCHARGE_MAH.matcher(rawText)
        if (offDrainMatcher.find()) {
            screenOffDrainMah = offDrainMatcher.group(1)?.toFloatOrNull() ?: 0f
        }
        if (screenOffDrainMah <= 0f) {
            val amountMatcher = REGEX_SCREEN_OFF_DISCHARGE_AMOUNT.matcher(rawText)
            if (amountMatcher.find()) {
                val percent = amountMatcher.group(1)?.toFloatOrNull() ?: 0f
                if (percent > 0f) {
                    val rawOffMah = (percent / 100f) * capacityMah
                    // 若息屏时长较短（<15分钟），实施基于物理最大待机功耗（3.0W）的平滑防量化尖峰保护，杜绝 45W 虚高
                    val offHours = screenOffDurationMs / 3600000f
                    if (screenOffDurationMs > 0L && offHours < 0.25f) {
                        val maxPhysicalMah = (MAX_STANDBY_POWER_WATTS * 1000f / voltageVolts.coerceAtLeast(3.7f)) * offHours
                        screenOffDrainMah = rawOffMah.coerceAtMost(maxPhysicalMah)
                    } else {
                        screenOffDrainMah = rawOffMah
                    }
                }
            }
        }
        if (screenOffDrainMah <= 0f) {
            val idleMatcher = REGEX_IDLE_DRAIN.matcher(rawText)
            if (idleMatcher.find()) {
                screenOffDrainMah = idleMatcher.group(1)?.toFloatOrNull() ?: 0f
            }
        }

        // 4. 逐行提取 Estimated power use 中的各 Uid 耗电及 Battery History 真实电量轨迹点序列
        val parsedAppMap = mutableMapOf<String, AppPowerUsageItem>()
        val pkgDrainMahMap = mutableMapOf<String, Float>()
        val historyPoints = mutableListOf<Pair<Long, Int>>()
        val historyTempList = mutableListOf<Float>()
        val historyTempPoints = mutableListOf<Pair<Long, Float>>()
        val lines = rawText.split('\n')
        var inPowerUseSection = false
        var inBatteryHistorySection = false
        var inDischargeStepSection = false
        var historyBaseTs = if (unplugTime > 0L) unplugTime else (now - dischargeDurationMs)
        var currentHistoryTs = historyBaseTs

        var detectedUnplugTs: Long? = null
        var detectedUnplugLevel: Int? = null
        var lastPluggedTs: Long? = null
        var maxDischargeStepLevel = 0

        for (line in lines) {
            val trimmed = line.trim()

            // 监听 Discharge step durations 段落
            if (trimmed.startsWith("Discharge step durations:", ignoreCase = true)) {
                inDischargeStepSection = true
                continue
            }
            if (inDischargeStepSection) {
                if (line.isNotEmpty() && !line.startsWith(" ") && !line.startsWith("\t")) {
                    inDischargeStepSection = false
                } else {
                    val stepMatcher = REGEX_DISCHARGE_STEP.matcher(trimmed)
                    if (stepMatcher.find()) {
                        val toLvl = stepMatcher.group(2)?.toIntOrNull() ?: 0
                        if (toLvl > maxDischargeStepLevel) {
                            maxDischargeStepLevel = toLvl
                        }
                    }
                }
            }

            // 监听 RESET:TIME 基准时间行
            val resetMatcher = REGEX_RESET_TIME.matcher(trimmed)
            if (resetMatcher.find()) {
                try {
                    val cal = Calendar.getInstance()
                    cal.set(
                        resetMatcher.group(1)!!.toInt(),
                        resetMatcher.group(2)!!.toInt() - 1,
                        resetMatcher.group(3)!!.toInt(),
                        resetMatcher.group(4)!!.toInt(),
                        resetMatcher.group(5)!!.toInt(),
                        resetMatcher.group(6)!!.toInt()
                    )
                    currentHistoryTs = cal.timeInMillis
                } catch (_: Exception) {}
            }

            // 监听 Battery History 段落
            if (trimmed.startsWith("Battery History", ignoreCase = true)) {
                inBatteryHistorySection = true
                continue
            }

            if (inBatteryHistorySection) {
                if (line.isNotEmpty() && !line.startsWith(" ") && !line.startsWith("\t")) {
                    if (trimmed.startsWith("Statistics since", ignoreCase = true) ||
                        trimmed.startsWith("Estimated power use", ignoreCase = true) ||
                        trimmed.startsWith("Per-app", ignoreCase = true)) {
                        inBatteryHistorySection = false
                    }
                }
                if (inBatteryHistorySection) {
                    val hMatcher = REGEX_BATTERY_HISTORY_LINE.matcher(trimmed)
                    if (hMatcher.find()) {
                        val deltaStr = hMatcher.group(1) ?: ""
                        val level = hMatcher.group(2)?.toIntOrNull()
                        if (level != null && level in 1..100) {
                            if (deltaStr.isNotEmpty()) {
                                currentHistoryTs += parseDurationStringToMs(deltaStr)
                            }
                            val isUnplug = trimmed.contains("-plugged", ignoreCase = true)
                            val isPlug = trimmed.contains("+plugged", ignoreCase = true)
                            if (isUnplug) {
                                detectedUnplugTs = currentHistoryTs
                                detectedUnplugLevel = level
                            } else if (isPlug) {
                                lastPluggedTs = currentHistoryTs
                            }
                            historyPoints.add(Pair(currentHistoryTs, level))
                        }
                    }
                    val tempMatcher = REGEX_HISTORY_TEMP.matcher(trimmed)
                    if (tempMatcher.find()) {
                        val rawT = tempMatcher.group(1)?.toFloatOrNull()
                        if (rawT != null && rawT in 0f..800f) {
                            val tVal = rawT / 10f
                            historyTempList.add(tVal)
                            historyTempPoints.add(Pair(currentHistoryTs, tVal))
                        }
                    }
                }
            }

            if (trimmed.startsWith("Estimated power use", ignoreCase = true)) {
                inPowerUseSection = true
                continue
            }

            if (inPowerUseSection) {
                // 遇到明显的大段落结束标记时才结束
                if (line.isNotEmpty() && !line.startsWith(" ") && !line.startsWith("\t")) {
                    if (trimmed.startsWith("All ", ignoreCase = true) ||
                        trimmed.startsWith("Cellular ", ignoreCase = true) ||
                        trimmed.startsWith("Per-app ", ignoreCase = true) ||
                        trimmed.startsWith("Battery History", ignoreCase = true) ||
                        trimmed.startsWith("Statistics since", ignoreCase = true)) {
                        inPowerUseSection = false
                        continue
                    }
                }

                // 提取屏幕硬件单独放电量（如 "Screen: 25.1"）
                val screenDrainMatcher = REGEX_SCREEN_DRAIN_LINE.matcher(trimmed)
                if (screenDrainMatcher.find()) {
                    val sDrain = screenDrainMatcher.group(1)?.toFloatOrNull() ?: 0f
                    if (sDrain > screenDrainMah) {
                        screenDrainMah = sDrain
                    }
                }

                // 兼容匹配：Uid 10234: 345.2、Uid 10234 (com.tencent.mobileqq): 345.2、Uid u0_a234 (com.tencent.mm): 156.2 ( cpu=120 ) 等各种真实格式
                val uidMatcher = REGEX_UID_POWER.matcher(trimmed)
                if (uidMatcher.find()) {
                    val uidRaw = uidMatcher.group(1) ?: continue
                    val directPkg = uidMatcher.group(2)?.trim()
                    val drainMah = uidMatcher.group(3)?.toFloatOrNull() ?: 0f
                    val extraDetails = uidMatcher.group(4) ?: ""

                    val uid = convertUidStringToNumeric(uidRaw)
                    // 只要产生了有效放电记录（> 0.001 mAh），即纳入统计
                    if (uid > 0 && drainMah > 0.001f) {
                        // 优先提取 dumpsys 直接携带的包名，次查 Shizuku 提权映射表，次选系统 pm
                        val pkgName = if (!directPkg.isNullOrEmpty() && directPkg.contains(".")) {
                            directPkg
                        } else {
                            uidPkgMap[uid] ?: pm.getPackagesForUid(uid)?.firstOrNull()
                        }

                        if (!pkgName.isNullOrEmpty()) {
                            pkgDrainMahMap[pkgName] = drainMah
                            val (foregroundMs, backgroundMs, cpuMs) = parseAppTimesFromDetails(
                                extraDetails,
                                dischargeDurationMs,
                                isHistoricalDumpsys
                            )
                            val totalDirectEnergyWh = (drainMah * voltageVolts) / 1000f

                            val hw = hwStatsMap[uid]
                            val realCpuMs = if (hw != null && hw.getTotalCpuMs() > 0L) hw.getTotalCpuMs() else cpuMs
                            val baseBg = if (hw != null) {
                                val bgCpu = (hw.getTotalCpuMs() - foregroundMs).coerceAtLeast(0L)
                                val directBg = hw.cpuBackgroundMs
                                maxOf(bgCpu, directBg) + hw.wakelockMs + hw.fgsMs
                            } else {
                                backgroundMs
                            }

                            // 前后台能量解耦：基于真实物理功耗合理性模型与 CPU 算力分配，彻底消除后台能耗全部算给前台的缺陷
                            val isGame = com.battery.analysis.manager.PowerUsageManager.getInstance(context).isGameApp(pkgName)
                            val (fgEnergyWh, bgEnergyWh, safeBgMs) = decoupleAppEnergyAndTimes(
                                totalEnergy = totalDirectEnergyWh,
                                foregroundMs = foregroundMs,
                                backgroundMs = maxOf(backgroundMs, baseBg),
                                cpuMs = realCpuMs,
                                dischargeMs = dischargeDurationMs,
                                isGame = isGame
                            )
                            val effectiveBackgroundMs = safeBgMs

                            // 运行平均功耗计算：若有前台活跃按前台能耗与前台时长计算；若为纯后台应用且有明确后台运行耗时，按后台运行能耗与时长计算
                            val fgHours = foregroundMs / 3600000.0
                            val bgHours = effectiveBackgroundMs / 3600000.0
                            val avgWatts = if (fgHours > 0.0) {
                                (fgEnergyWh / fgHours).toFloat()
                            } else if (bgHours > 0.0 && bgEnergyWh > 0f) {
                                (bgEnergyWh / bgHours).toFloat()
                            } else {
                                0f
                            }

                            val appTemp = tempCelsius
                            val maxTemp = tempCelsius

                            val realNetBytes = hw?.networkBytes ?: 0L
                            val realWakeMs = hw?.wakelockMs ?: 0L
                            val realGpsMs = hw?.gpsMs ?: 0L

                            try {
                                val appInfo = pm.getApplicationInfo(pkgName, 0)
                                val appName = pm.getApplicationLabel(appInfo).toString()
                                val icon = pm.getApplicationIcon(appInfo)

                                parsedAppMap[pkgName] = AppPowerUsageItem(
                                    packageName = pkgName,
                                    appName = appName,
                                    icon = icon,
                                    foregroundTimeMs = foregroundMs,
                                    avgPowerWatts = avgWatts,
                                    avgTemperature = appTemp,
                                    maxTemperature = maxTemp,
                                    lastUsedTimeMs = System.currentTimeMillis(),
                                    directEnergyWh = totalDirectEnergyWh,
                                    backgroundTimeMs = effectiveBackgroundMs,
                                    foregroundEnergyWh = fgEnergyWh,
                                    backgroundEnergyWh = bgEnergyWh,
                                    cpuTimeMs = realCpuMs,
                                    networkBytes = realNetBytes,
                                    wakelockTimeMs = realWakeMs,
                                    gpsTimeMs = realGpsMs
                                )
                            } catch (_: Exception) {
                                // 兜底处理：未能获取到特定 ApplicationInfo 时才使用简要包名
                                val simpleName = pkgName.substringAfterLast('.')
                                parsedAppMap[pkgName] = AppPowerUsageItem(
                                    packageName = pkgName,
                                    appName = simpleName,
                                    icon = pm.defaultActivityIcon,
                                    foregroundTimeMs = foregroundMs,
                                    avgPowerWatts = avgWatts,
                                    avgTemperature = tempCelsius,
                                    maxTemperature = tempCelsius,
                                    lastUsedTimeMs = System.currentTimeMillis(),
                                    directEnergyWh = totalDirectEnergyWh,
                                    backgroundTimeMs = effectiveBackgroundMs,
                                    foregroundEnergyWh = fgEnergyWh,
                                    backgroundEnergyWh = bgEnergyWh,
                                    cpuTimeMs = realCpuMs,
                                    networkBytes = realNetBytes,
                                    wakelockTimeMs = realWakeMs,
                                    gpsTimeMs = realGpsMs
                                )
                            }
                        }
                    }
                }
            }
        }

        // 计算放电周期内的真实电池温度统计指标（平均温度与最高温度）
        val cycleAvgTemp = if (historyTempList.isNotEmpty()) {
            ((Math.round(historyTempList.average() * 10.0) / 10.0).toFloat()).coerceIn(0f, 70f)
        } else {
            tempCelsius
        }
        val cycleMaxTemp = if (historyTempList.isNotEmpty()) {
            val maxRecorded = historyTempList.maxOrNull() ?: tempCelsius
            (Math.round(maxRecorded * 10f) / 10f).coerceAtLeast(cycleAvgTemp).coerceIn(0f, 70f)
        } else {
            cycleAvgTemp
        }

        // 校准已从 dumpsys 解析出的应用温度，确保严格对齐放电周期的真实温度统计
        for (key in parsedAppMap.keys.toList()) {
            val item = parsedAppMap[key] ?: continue
            parsedAppMap[key] = item.copy(
                avgTemperature = cycleAvgTemp,
                maxTemperature = cycleMaxTemp
            )
        }

        // 5. 智能融合：仅针对 dumpsys 遗漏但系统确实有前台记录的应用进行补全，不覆盖 dumpsys 原始前台时间与功率
        val userAppList = mergeWithUsageStatsUserApps(
            parsedAppMap,
            pkgDrainMahMap,
            dischargeDurationMs,
            voltageVolts,
            cycleAvgTemp,
            cycleMaxTemp,
            unplugTime,
            hwStatsMap
        )

        // 6. 若仍未匹配到亮屏时长，通过所有前台应用的累计活跃时长进行真实计算
        if (screenOnDurationMs <= 0L) {
            val sumFg = userAppList.map { it.foregroundTimeMs }.sum()
            screenOnDurationMs = if (sumFg > 0) sumFg.coerceAtMost(dischargeDurationMs) else 0L
        }

        // 上限以本次放电周期的实际总时长 dischargeDurationMs 为准
        val maxAllowedTimeMs = dischargeDurationMs
        val validatedList = userAppList.map { item ->
            val safeFg = if (item.foregroundTimeMs > maxAllowedTimeMs) maxAllowedTimeMs else item.foregroundTimeMs
            val safeBg = if (item.backgroundTimeMs > maxAllowedTimeMs) maxAllowedTimeMs else item.backgroundTimeMs
            if (safeFg != item.foregroundTimeMs || safeBg != item.backgroundTimeMs) {
                item.copy(foregroundTimeMs = safeFg, backgroundTimeMs = safeBg)
            } else {
                item
            }
        }.filter { it.foregroundTimeMs > 0L || it.backgroundTimeMs > 0L || it.energyWh > 0.001f }.toMutableList()

        // 7. 校验最近一次拔电起点与消除长段充满待机平线
        var finalUnplugTs = detectedUnplugTs
        var finalUnplugLevel = detectedUnplugLevel

        if (finalUnplugLevel == null && maxDischargeStepLevel > 0) {
            finalUnplugLevel = (maxDischargeStepLevel + 1).coerceAtMost(100)
        }

        var filteredHistoryPoints = historyPoints
        val targetUnplugTs = finalUnplugTs
        if (targetUnplugTs != null && (lastPluggedTs == null || targetUnplugTs >= lastPluggedTs)) {
            val afterUnplug = historyPoints.filter { it.first >= targetUnplugTs }
            if (afterUnplug.isNotEmpty()) {
                filteredHistoryPoints = afterUnplug.toMutableList()
            }
            val realElapsed = (now - targetUnplugTs).coerceAtLeast(1000L)
            if (realElapsed < dischargeDurationMs) {
                dischargeDurationMs = realElapsed
            }
        } else if (historyPoints.size > 2) {
            val firstDropIdx = historyPoints.indexOfFirst { it.second < (historyPoints.firstOrNull()?.second ?: 100) }
            if (firstDropIdx > 1) {
                val dropPoint = historyPoints[firstDropIdx]
                val prevPoint = historyPoints[firstDropIdx - 1]
                if (dropPoint.first - historyPoints.first().first > 1800000L) {
                    filteredHistoryPoints = historyPoints.subList(firstDropIdx - 1, historyPoints.size).toMutableList()
                    if (finalUnplugLevel == null) {
                        finalUnplugLevel = prevPoint.second
                    }
                    if (finalUnplugTs == null) {
                        finalUnplugTs = prevPoint.first
                    }
                }
            }
        }

        // 8. 总放电量累加：若 dumpsys 未直接给出整机 computedDrainMah，或由于 dumpsys 刷新滞后导致其值小于已运行子应用及屏幕的实际能耗总和，
        // 则依宏观物理能量守恒定律强制对齐下限（整机总放电量必不小于各子应用实耗之和），彻底杜绝微小底噪杂讯导致整体小于部分的物理悖论
        val totalAppWh = validatedList.sumOf { it.energyWh.toDouble() }.toFloat()
        val appMah = (totalAppWh * 1000f) / voltageVolts.coerceAtLeast(3.7f)
        val minPhysicalDrainMah = appMah + screenDrainMah
        if (validatedList.isNotEmpty() && (computedDrainMah <= 0f || computedDrainMah < minPhysicalDrainMah * 0.9f)) {
            computedDrainMah = maxOf(computedDrainMah, minPhysicalDrainMah)
        }

        // 9. 若底层未直接给出息屏放电量，但已有明确的息屏时长（>=30秒）以及整机总放电量，
        // 则整机总放电量扣除前台亮屏应用与屏幕显示所消耗电量后的结余放电量按时间占比分摊作为息屏待机放电量
        if (screenOffDrainMah <= 0f && screenOffDurationMs >= 30000L && computedDrainMah > 0f) {
            val totalFgDrain = validatedList.sumOf { (it.energyWh * 1000f / voltageVolts.coerceAtLeast(3.7f)).toDouble() }.toFloat()
            val remainingDrain = (computedDrainMah - totalFgDrain - screenDrainMah).coerceAtLeast(0f)
            val offRatio = if (dischargeDurationMs > 0L) {
                (screenOffDurationMs.toFloat() / dischargeDurationMs).coerceIn(0f, 1f)
            } else {
                0f
            }
            val allocatedOffDrain = remainingDrain * offRatio
            val offHours = screenOffDurationMs / 3600000f
            val maxPhysicalStandbyDrain = (MAX_STANDBY_POWER_WATTS * 1000f / voltageVolts.coerceAtLeast(3.7f)) * offHours
            val effectiveOffDrain = allocatedOffDrain.coerceAtMost(maxPhysicalStandbyDrain)
            if (effectiveOffDrain > 0.05f) {
                screenOffDrainMah = effectiveOffDrain
            }
        }

        // 统一物理合理性保护：息屏放电量绝不能超过待机状态下的最大物理放电量（防止底层 dumpsys 脏数据或微小时长导致功耗超标）
        if (screenOffDurationMs > 0L && screenOffDrainMah > 0f) {
            val offHours = screenOffDurationMs / 3600000f
            val maxPhysicalMah = (MAX_STANDBY_POWER_WATTS * 1000f / voltageVolts.coerceAtLeast(3.7f)) * offHours
            screenOffDrainMah = screenOffDrainMah.coerceAtMost(maxPhysicalMah)
        }

        // 10. 排序策略：用户安装的常用三方应用（带启动图标或非系统应用）排在最前，系统底层进程排在后方
        validatedList.sortWith(compareByDescending<AppPowerUsageItem> { isUserInstalledApp(it.packageName) }
            .thenByDescending { it.foregroundTimeMs }
            .thenByDescending { it.avgPowerWatts })

        return BatteryStatsResult(
            capacityMah = capacityMah,
            computedDrainMah = computedDrainMah,
            dischargeDurationMs = dischargeDurationMs,
            screenOnDurationMs = screenOnDurationMs,
            screenOffDurationMs = screenOffDurationMs,
            screenOffDrainMah = screenOffDrainMah,
            appList = validatedList,
            screenDrainMah = screenDrainMah,
            historyLevelPoints = filteredHistoryPoints,
            historyTempPoints = historyTempPoints,
            detectedUnplugTs = finalUnplugTs,
            detectedUnplugLevel = finalUnplugLevel
        )
    }

    /**
     * 通过 Shizuku 执行 pm list packages -U，提取全系统所有已安装包名及其对应的真实整数 UID。
     *
     * @return UID 到包名的映射字典 [Map<Int, String>]
     */
    private fun loadUidPackageMapViaShizuku(): Map<Int, String> {
        val map = mutableMapOf<Int, String>()
        val output = executeShizukuCommand("pm list packages -U")
        if (output.isNotBlank()) {
            val pattern = Pattern.compile("package:([^\\s]+)\\s+uid:(\\d+)")
            for (line in output.split('\n')) {
                val matcher = pattern.matcher(line.trim())
                if (matcher.find()) {
                    val pkg = matcher.group(1) ?: continue
                    val uid = matcher.group(2)?.toIntOrNull() ?: continue
                    map[uid] = pkg
                }
            }
        }
        return map
    }

    /**
     * 将 Uid 字符串转换为整型 UID，完整兼容纯数字（如 "10234"）与各类 Linux 用户名格式（如 "u0_a234"、"u0a234"、"u10_a56"）。
     *
     * @param uidRaw 原始 UID 字符串
     * @return 转换后的标准整数 UID，失败返回 -1
     */
    private fun convertUidStringToNumeric(uidRaw: String): Int {
        val direct = uidRaw.toIntOrNull()
        if (direct != null) return direct

        // 匹配各类形如 u0_a234, u0a234, u10_a56 的 Android 格式
        val match = REGEX_ANDROID_UID.matcher(uidRaw)
        if (match.find()) {
            val userId = match.group(1)?.toIntOrNull() ?: 0
            val appId = match.group(2)?.toIntOrNull() ?: 0
            return userId * 100000 + 10000 + appId
        }
        return -1
    }

    /**
     * 判断指定包名是否为用户安装的三方应用（非纯底层系统进程或具有桌面启动入口）。
     *
     * @param packageName 目标包名
     * @return 若为用户三方应用返回 true，否则返回 false
     */
    private fun isUserInstalledApp(packageName: String): Boolean {
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystem = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            val hasLauncher = pm.getLaunchIntentForPackage(packageName) != null
            !isSystem || isUpdatedSystem || hasLauncher
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 将解析到的 App 列表与系统 UsageStats 中的前台三方应用进行深度融合。
     *
     * @param existingMap 已解析的 App 映射字典
     * @param dischargeMs 总放电时长
     * @param voltage 电池电压
     * @param baseTemp 基础温度
     * @return 融合后的完整应用耗电列表 [MutableList<AppPowerUsageItem>]
     */
    /**
     * 基于 UsageEvents 精准提取指定时间区间 [startTime, endTime] 内各应用的前台活跃毫秒数。
     * 采用严格的单前台应用生命周期状态机，仅认准 ACTIVITY_RESUMED 至 ACTIVITY_PAUSED，
     * 并向前回溯探测区间开始时刻处于活跃的应用，彻底杜绝孤立事件或后台被杀（ACTIVITY_STOPPED）导致的误判与虚高。
     *
     * @param usm UsageStatsManager 实例
     * @param startTime 统计起始时间戳（毫秒）
     * @param endTime 统计结束时间戳（毫秒）
     * @return 包名对应的前台活跃时长（毫秒）映射表
     */
    private fun queryPreciseForegroundTimes(
        usm: UsageStatsManager,
        startTime: Long,
        endTime: Long
    ): Map<String, Long> {
        val resultMap = mutableMapOf<String, Long>()
        if (startTime >= endTime) return resultMap

        try {
            // 向前回溯探测在 startTime 瞬间正处于前台活跃状态的应用（最多回溯 15 分钟）
            val lookbackStart = (startTime - 15 * 60 * 1000L).coerceAtLeast(0L)
            val events = usm.queryEvents(lookbackStart, endTime)
            val event = UsageEvents.Event()
            var currentForegroundPkg: String? = null
            var currentForegroundStartTs: Long = 0L

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val pkg = event.packageName ?: continue
                val ts = event.timeStamp

                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        // 若先前已有应用在前台且未收到 PAUSE 事件（被新 Activity 覆盖），结算其有效前台时长
                        if (currentForegroundPkg != null) {
                            val activeStart = maxOf(currentForegroundStartTs, startTime)
                            val activeEnd = minOf(ts, endTime)
                            if (activeEnd > activeStart) {
                                resultMap[currentForegroundPkg] = (resultMap[currentForegroundPkg] ?: 0L) + (activeEnd - activeStart)
                            }
                        }
                        currentForegroundPkg = pkg
                        currentForegroundStartTs = ts
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED -> {
                        // 仅当当前离开前台的应用正是记录中的前台应用时才进行结算，杜绝后台事件或旧事件误判
                        if (currentForegroundPkg == pkg) {
                            val activeStart = maxOf(currentForegroundStartTs, startTime)
                            val activeEnd = minOf(ts, endTime)
                            if (activeEnd > activeStart) {
                                resultMap[pkg] = (resultMap[pkg] ?: 0L) + (activeEnd - activeStart)
                            }
                            currentForegroundPkg = null
                            currentForegroundStartTs = 0L
                        }
                    }
                }
            }

            // 处理在 endTime 时刻仍然驻留前台的应用
            if (currentForegroundPkg != null) {
                val activeStart = maxOf(currentForegroundStartTs, startTime)
                val activeEnd = endTime
                if (activeEnd > activeStart) {
                    resultMap[currentForegroundPkg] = (resultMap[currentForegroundPkg] ?: 0L) + (activeEnd - activeStart)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return resultMap
    }

    /**
     * 结合系统应用使用情况管理器与底层硬件指标，对 dumpsys 数据进行前台时间与硬件开销辅助补全。
     * 当 dumpsys 详情中未携带具体前台时间（top/fg 字段缺省）时，通过 UsageStats 增量精准补全前台时长并重新解耦前后台能量与亮屏功耗；
     * 并结合 [hwStatsMap] 与 [NetworkStatsHelper] 补足应用真实 CPU、网络流量、唤醒锁与 GPS 定位消耗。
     *
     * @param existingMap 已通过 dumpsys batterystats 解析得到的应用映射字典
     * @param pkgDrainMahMap 各应用由 dumpsys 解析得到的真实放电量（mAh）字典
     * @param dischargeMs 本次放电周期的实际放电时长毫秒数
     * @param voltage 电池电压（V）
     * @param cycleAvgTemp 放电周期内测得的电池平均温度（℃）
     * @param cycleMaxTemp 放电周期内测得的电池最高温度（℃）
     * @param unplugTime 最近一次断开充电或手动重置的时间戳（毫秒），默认为 0L
     * @param hwStatsMap 各应用 UID 硬件指标映射表
     * @return 融合校准后的应用耗电列表 [MutableList<AppPowerUsageItem>]
     */
    private fun mergeWithUsageStatsUserApps(
        existingMap: MutableMap<String, AppPowerUsageItem>,
        pkgDrainMahMap: Map<String, Float>,
        dischargeMs: Long,
        voltage: Float,
        cycleAvgTemp: Float,
        cycleMaxTemp: Float,
        unplugTime: Long = 0L,
        hwStatsMap: Map<Int, UidHardwareStats> = emptyMap()
    ): MutableList<AppPowerUsageItem> {
        val pm = context.packageManager
        val endTime = System.currentTimeMillis()
        val startTime = if (unplugTime > 0L) unplugTime else (endTime - dischargeMs).coerceAtLeast(0L)

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        val hasUsageStats = usm != null && com.battery.analysis.manager.PowerUsageManager.getInstance(context).hasUsageStatsPermission()
        val preciseTimes = if (hasUsageStats && usm != null) {
            queryPreciseForegroundTimes(usm, startTime, endTime)
        } else {
            emptyMap()
        }

        // 1. 对 dumpsys 原本提取的应用进行前台时长、后台活跃时长与硬件开销的精准补全
        val existingKeys = existingMap.keys.toList()
        for (pkg in existingKeys) {
            val old = existingMap[pkg] ?: continue
            val uid = try { pm.getApplicationInfo(pkg, 0).uid } catch (_: Exception) { -1 }
            val hw = hwStatsMap[uid]

            var effectiveFg = old.foregroundTimeMs
            var effectiveBg = old.backgroundTimeMs

            // 若 dumpsys 详情中没有携带前台活跃时间，从 UsageEvents 精准补齐
            if (effectiveFg <= 0L) {
                val realFg = preciseTimes[pkg] ?: 0L
                if (realFg > 0L) {
                    effectiveFg = realFg.coerceAtMost(dischargeMs)
                } else if (pkg == context.packageName) {
                    effectiveFg = dischargeMs.coerceAtLeast(1000L)
                }
            }

            val realCpuMs = if (hw != null && hw.getTotalCpuMs() > 0L) hw.getTotalCpuMs() else old.cpuTimeMs
            val baseBg = if (hw != null) {
                val bgCpu = (hw.getTotalCpuMs() - effectiveFg).coerceAtLeast(0L)
                maxOf(bgCpu, hw.cpuBackgroundMs) + hw.wakelockMs + hw.fgsMs
            } else {
                effectiveBg
            }

            // 依据真实补全后的前后台时长，重新科学解耦前台消耗能量、后台消耗能量以及亮屏平均功耗
            val totalEnergy = old.directEnergyWh ?: ((pkgDrainMahMap[pkg] ?: 0f) * voltage / 1000f)
            val isGame = com.battery.analysis.manager.PowerUsageManager.getInstance(context).isGameApp(pkg)
            val (fgEnergyWh, bgEnergyWh, safeBgMs) = decoupleAppEnergyAndTimes(
                totalEnergy = totalEnergy,
                foregroundMs = effectiveFg,
                backgroundMs = maxOf(effectiveBg, baseBg),
                cpuMs = realCpuMs,
                dischargeMs = dischargeMs,
                isGame = isGame
            )
            val effectiveFinalBg = safeBgMs

            val fgHours = effectiveFg / 3600000.0
            val bgHours = effectiveFinalBg / 3600000.0
            val avgWatts = if (fgHours > 0.0) {
                (fgEnergyWh / fgHours).toFloat()
            } else if (bgHours > 0.0 && bgEnergyWh > 0f) {
                (bgEnergyWh / bgHours).toFloat()
            } else {
                0f
            }

            var netBytes = if (hw != null && hw.networkBytes > 0L) hw.networkBytes else old.networkBytes
            if (netBytes <= 0L && uid > 0) {
                netBytes = networkStatsHelper.getUidNetworkBytes(uid, startTime, endTime)
            }

            existingMap[pkg] = old.copy(
                foregroundTimeMs = effectiveFg,
                backgroundTimeMs = effectiveFinalBg,
                foregroundEnergyWh = fgEnergyWh,
                backgroundEnergyWh = bgEnergyWh,
                avgPowerWatts = avgWatts,
                cpuTimeMs = realCpuMs,
                networkBytes = netBytes,
                wakelockTimeMs = hw?.wakelockMs ?: old.wakelockTimeMs,
                gpsTimeMs = hw?.gpsMs ?: old.gpsTimeMs
            )
        }

        // 2. 补充 dumpsys 遗漏但系统事件中确实在前台运行的用户应用
        val knownFgHours = existingMap.values.sumOf { it.foregroundTimeMs } / 3600000.0
        val knownFgEnergyWh = existingMap.values.sumOf { it.foregroundEnergyWh.toDouble() }
        val sysAvgWatts = if (dischargeMs > 0L) {
            val totalMah = pkgDrainMahMap.values.sum()
            if (totalMah > 0f) (totalMah * voltage / 1000f) / (dischargeMs / 3600000f) else 0f
        } else {
            0f
        }
        val baselineWatts = if (knownFgHours > 0.02 && knownFgEnergyWh > 0.0) {
            (knownFgEnergyWh / knownFgHours).toFloat()
        } else if (sysAvgWatts > 0f) {
            sysAvgWatts
        } else {
            0f
        }

        for ((pkgName, fgTime) in preciseTimes) {
            val safeFgTime = fgTime.coerceAtMost(dischargeMs)
            if (safeFgTime >= 1000L && isUserInstalledApp(pkgName)) {
                if (!existingMap.containsKey(pkgName)) {
                    try {
                        val appInfo = pm.getApplicationInfo(pkgName, 0)
                        val appName = pm.getApplicationLabel(appInfo).toString()
                        val icon = pm.getApplicationIcon(appInfo)
                        val uid = appInfo.uid
                        val hw = hwStatsMap[uid]

                        var netBytes = hw?.networkBytes ?: 0L
                        if (netBytes <= 0L && uid > 0) {
                            netBytes = networkStatsHelper.getUidNetworkBytes(uid, startTime, endTime)
                        }
                        val realCpu = hw?.getTotalCpuMs() ?: 0L
                        val realWake = hw?.wakelockMs ?: 0L
                        val realGps = hw?.gpsMs ?: 0L
                        val bgTime = if (hw != null) {
                            ((realCpu - safeFgTime).coerceAtLeast(0L) + realWake + hw.fgsMs).coerceAtMost(dischargeMs)
                        } else {
                            0L
                        }

                        val fgEnergy = (baselineWatts * (safeFgTime / 3600000f)).coerceAtLeast(0f)

                        existingMap[pkgName] = AppPowerUsageItem(
                            packageName = pkgName,
                            appName = appName,
                            icon = icon,
                            foregroundTimeMs = safeFgTime,
                            avgPowerWatts = baselineWatts,
                            avgTemperature = cycleAvgTemp,
                            maxTemperature = cycleMaxTemp,
                            lastUsedTimeMs = endTime,
                            directEnergyWh = fgEnergy,
                            backgroundTimeMs = bgTime,
                            foregroundEnergyWh = fgEnergy,
                            backgroundEnergyWh = 0f,
                            cpuTimeMs = realCpu,
                            networkBytes = netBytes,
                            wakelockTimeMs = realWake,
                            gpsTimeMs = realGps
                        )
                    } catch (_: PackageManager.NameNotFoundException) {
                    }
                }
            }
        }

        return existingMap.values.toMutableList()
    }

    /**
     * 客观解耦应用在前台活跃期间与后台常驻/休眠期间的能量消耗与真实运行时长。
     * 针对前台使用场景施加物理功耗合理性保护，杜绝因未识别出后台时间导致后台消耗全量误判为前台算力。
     *
     * @param totalEnergy 应用消耗的总电量（单位：瓦时 Wh）
     * @param foregroundMs 前台活跃时长（毫秒）
     * @param backgroundMs 后台活跃时长（毫秒）
     * @param cpuMs 应用 CPU 计算总耗时（毫秒）
     * @param dischargeMs 本次放电周期总时长（毫秒）
     * @param isGame 是否为高能耗 3D 游戏
     * @return 包含前台能量、后台能量与有效后台时长的三元组 [Triple<Float, Float, Long>]
     */
    fun decoupleAppEnergyAndTimes(
        totalEnergy: Float,
        foregroundMs: Long,
        backgroundMs: Long,
        cpuMs: Long,
        dischargeMs: Long,
        isGame: Boolean = false
    ): Triple<Float, Float, Long> {
        if (totalEnergy <= 0.0001f) {
            return Triple(0f, 0f, backgroundMs)
        }

        // 纯后台应用（前台时长为 0）：能量 100% 归属于后台能量，前台能量严格为 0f
        if (foregroundMs <= 0L) {
            val effectiveBg = if (backgroundMs > 0L) {
                backgroundMs
            } else if (cpuMs > 0L) {
                cpuMs.coerceAtMost(dischargeMs.coerceAtLeast(1000L))
            } else if (totalEnergy > 0.0005f) {
                val inferredMs = ((totalEnergy / 1.5f) * 3600000.0).toLong()
                inferredMs.coerceIn(1000L, dischargeMs.coerceAtLeast(1000L))
            } else {
                0L
            }
            return Triple(0f, totalEnergy, effectiveBg)
        }

        val fgHours = foregroundMs / 3600000.0
        if (fgHours <= 0.0) {
            val effectiveBg = if (backgroundMs > 0L) {
                backgroundMs
            } else if (cpuMs > 0L) {
                cpuMs
            } else if (totalEnergy > 0.0005f) {
                val inferredMs = ((totalEnergy / 1.5f) * 3600000.0).toLong()
                inferredMs.coerceIn(1000L, dischargeMs.coerceAtLeast(1000L))
            } else {
                0L
            }
            return Triple(0f, totalEnergy, effectiveBg)
        }

        // 前台核心纯算力功耗物理合理上限（不含恒定屏幕面板底座功率）：
        // 普通日常应用（微信、QQ、浏览器、系统界面等）核心算力功耗通常在 0.4W ~ 1.5W，单 App 物理极限不超过 2.2W；
        // 3D 游戏由于持续高负载图形渲染，核心算力功耗物理极限可达 4.5W
        val maxReasonableCoreWatts = if (isGame) 4.5f else 2.2f
        val rawCoreWatts = (totalEnergy / fgHours).toFloat()

        val fgEnergyWh: Float
        val bgEnergyWh: Float

        if (rawCoreWatts > maxReasonableCoreWatts) {
            // 核心算力功耗严重超出物理合理极限（如微信 20.46W），确凿表明应用在后台常驻期间累积消耗了绝大部分电量
            val targetFgCoreWatts = if (isGame) {
                maxReasonableCoreWatts
            } else {
                // 普通日常应用前台正常操作典型核心算力功耗约 0.8W ~ 1.2W
                1.0f.coerceAtMost(maxReasonableCoreWatts)
            }
            val fgCalculatedEnergy = (targetFgCoreWatts * fgHours).toFloat()
            fgEnergyWh = minOf(fgCalculatedEnergy, totalEnergy)
            bgEnergyWh = (totalEnergy - fgEnergyWh).coerceAtLeast(0f)
        } else if (backgroundMs > 0L) {
            // 后台已明确记录了运行耗时，按前台与后台算力权重客观分配
            val bgHours = backgroundMs / 3600000.0
            val fgWeight = fgHours * 5.0 // 前台算力权重约为后台的 5 倍
            val bgWeight = bgHours * 1.0
            val totalWeight = fgWeight + bgWeight
            if (totalWeight > 0.0) {
                val ratio = (fgWeight / totalWeight).toFloat()
                fgEnergyWh = (totalEnergy * ratio).coerceIn(0f, totalEnergy)
                bgEnergyWh = (totalEnergy - fgEnergyWh).coerceAtLeast(0f)
            } else {
                fgEnergyWh = totalEnergy
                bgEnergyWh = 0f
            }
        } else if (cpuMs > foregroundMs) {
            // CPU 计算耗时超出前台时长，超出的部分为后台算力
            val totalCpu = cpuMs.toFloat()
            val fgRatio = (foregroundMs.toFloat() / totalCpu).coerceIn(0.1f, 1.0f)
            fgEnergyWh = totalEnergy * fgRatio
            bgEnergyWh = (totalEnergy - fgEnergyWh).coerceAtLeast(0f)
        } else {
            // 总能耗完全在前台物理功耗合理范围内且无后台活动：全额归属于前台
            fgEnergyWh = totalEnergy
            bgEnergyWh = 0f
        }

        // 后台活跃时长仅保留真实的后台记录时间或后台 CPU 算力时间，绝不虚拟成整机放电总时长
        val rawEffectiveBgMs = if (backgroundMs > 0L) {
            backgroundMs
        } else if (cpuMs > foregroundMs) {
            (cpuMs - foregroundMs).coerceAtLeast(0L)
        } else {
            0L
        }

        // 消除“后台分配了能量，但后台时间却显示 0s”的缺陷
        val finalEffectiveBgMs = if (rawEffectiveBgMs > 0L) {
            rawEffectiveBgMs
        } else if (bgEnergyWh > 0.0005f) {
            val inferredMs = ((bgEnergyWh / 1.5f) * 3600000.0).toLong()
            inferredMs.coerceIn(1000L, dischargeMs.coerceAtLeast(1000L))
        } else {
            0L
        }

        return Triple(fgEnergyWh, bgEnergyWh, finalEffectiveBgMs)
    }

    /**
     * 从 Uid 详情中提取应用在前台的运行活跃耗时（top 或 fg）、后台运行耗时（bg）以及实际 CPU 算力耗时（cpu）。
     *
     * @param details 详情括号内的字符串（如 "cpu=2m15s top=1m10s bg=15m5s"）
     * @param totalDischargeMs 整机总放电时长（毫秒）
     * @param isHistoricalOutput dumpsys 是否包含超越当前周期的历史累积输出
     * @return 包含前台时长、后台时长与 CPU 耗时的三元组 [Triple<Long, Long, Long>]
     */
    private fun parseAppTimesFromDetails(
        details: String,
        totalDischargeMs: Long,
        isHistoricalOutput: Boolean = false
    ): Triple<Long, Long, Long> {
        var fgMs = 0L
        var bgMs = 0L
        var cpuMs = 0L

        // 1. 优先匹配前台运行耗时 top
        val topMatch = REGEX_TOP_TIME.matcher(details)
        if (topMatch.find()) {
            val tStr = topMatch.group(1)
            if (!tStr.isNullOrBlank()) {
                fgMs = parseDurationStringToMs(tStr)
            }
        }

        // 2. 次选匹配前台运行耗时 fg
        if (fgMs <= 0L) {
            val fgMatch = REGEX_FG_TIME.matcher(details)
            if (fgMatch.find()) {
                val fStr = fgMatch.group(1)
                if (!fStr.isNullOrBlank()) {
                    fgMs = parseDurationStringToMs(fStr)
                }
            }
        }

        // 3. 匹配后台运行耗时 bg
        val bgMatch = REGEX_BG_TIME.matcher(details)
        if (bgMatch.find()) {
            val bStr = bgMatch.group(1)
            if (!bStr.isNullOrBlank()) {
                bgMs = parseDurationStringToMs(bStr)
            }
        }

        // 4. 匹配 CPU 真实计算耗时 cpu
        val cpuMatch = REGEX_CPU_TIME.matcher(details)
        if (cpuMatch.find()) {
            val cStr = cpuMatch.group(1)
            if (!cStr.isNullOrBlank()) {
                cpuMs = parseDurationStringToMs(cStr)
            }
        }

        if (isHistoricalOutput) {
            if (fgMs > totalDischargeMs) fgMs = 0L
            if (bgMs > totalDischargeMs) bgMs = 0L
            if (cpuMs > totalDischargeMs * 8) cpuMs = 0L
        }

        val safeFg = fgMs.coerceAtMost(totalDischargeMs.coerceAtLeast(1000L))
        var safeBg = bgMs.coerceAtMost(totalDischargeMs.coerceAtLeast(1000L))
        val safeCpu = cpuMs.coerceAtLeast(0L)

        // 若底层 dumpsys 未明确输出 bg= 字段（Android 对常驻系统服务与后台广播通常仅以 cpu= 记录计算耗时），
        // 且前台耗时为 0（纯后台服务进程），则将其 CPU 计算耗时客观确认为其后台活跃运行时间；
        // 若既有前台时间又有 CPU 耗时且 cpuMs > safeFg，超出的计算耗时即为后台计算时间
        if (safeBg <= 0L) {
            if (safeFg <= 0L && safeCpu > 0L) {
                safeBg = safeCpu.coerceAtMost(totalDischargeMs.coerceAtLeast(1000L))
            } else if (safeCpu > safeFg) {
                safeBg = (safeCpu - safeFg).coerceAtMost(totalDischargeMs.coerceAtLeast(1000L))
            }
        }

        return Triple(safeFg, safeBg, safeCpu)
    }

    /**
     * 将持续时间文本（如 "1d 2h 30m 45s"、"58m45s"、"19m 38s" 或 "500ms"）解析为毫秒数。
     * 使用严谨的正则 Token 匹配，杜绝毫秒 ms 的 'm' 被当做分钟误匹配。
     *
     * @param durationStr 时间文本字符串
     * @return 解析得到的毫秒数值
     */
    fun parseDurationStringToMs(durationStr: String): Long {
        var totalMs = 0L
        val clean = durationStr.trim()
        if (clean.isBlank()) return 0L

        // 优先匹配 ms，再匹配 d, h, m, s，防止 ms 的 'm' 被当做分钟误匹配
        val regex = Regex("(\\d+)\\s*(ms|d|h|m|s)", RegexOption.IGNORE_CASE)
        val matches = regex.findAll(clean)
        var matchedAny = false
        for (match in matches) {
            matchedAny = true
            val value = match.groupValues[1].toLongOrNull() ?: continue
            val unit = match.groupValues[2].lowercase()
            when (unit) {
                "d" -> totalMs += value * 86400000L
                "h" -> totalMs += value * 3600000L
                "m" -> totalMs += value * 60000L
                "s" -> totalMs += value * 1000L
                "ms" -> totalMs += value
            }
        }

        if (!matchedAny) {
            val pureNumber = clean.toLongOrNull()
            if (pureNumber != null) {
                totalMs = pureNumber
            }
        }

        return totalMs
    }

    /**
     * 解析 dumpsys batterystats --checkin 原始输出，提取所有 UID 的硬件消耗细节（CPU、网络、唤醒锁、GPS、前台服务）。
     *
     * @param checkinText dumpsys batterystats --checkin 命令的原始文本输出
     * @return 按 UID 索引的硬件资源消耗映射表 [Map<Int, UidHardwareStats>]
     */
    fun parseHardwareStatsFromCheckin(checkinText: String): Map<Int, UidHardwareStats> {
        if (checkinText.isBlank()) return emptyMap()
        val resultMap = mutableMapOf<Int, UidHardwareStats>()

        checkinText.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach

            val parts = trimmed.split(",")
            if (parts.size < 4) return@forEach

            val uid = parts[1].toIntOrNull() ?: return@forEach
            if (uid < 0) return@forEach

            val aggType = parts[2]
            // 只关切自上次拔电/充饱电以来的增量聚合段 ("l" 代表 since last charged, "u" 代表 unplugged)
            if (aggType != "l" && aggType != "u") return@forEach

            val tag = parts[3]
            val stats = resultMap.getOrPut(uid) { UidHardwareStats(uid) }

            when (tag) {
                "cpu" -> {
                    // 格式: vers,uid,l,cpu,userTime,systemTime,...
                    val user = parts.getOrNull(4)?.toLongOrNull() ?: 0L
                    val sys = parts.getOrNull(5)?.toLongOrNull() ?: 0L
                    stats.cpuUserMs += user
                    stats.cpuSystemMs += sys
                }
                "nt" -> {
                    // 格式: vers,uid,l,nt,mobileRx,mobileTx,wifiRx,wifiTx,...
                    val mRx = parts.getOrNull(4)?.toLongOrNull() ?: 0L
                    val mTx = parts.getOrNull(5)?.toLongOrNull() ?: 0L
                    val wRx = parts.getOrNull(6)?.toLongOrNull() ?: 0L
                    val wTx = parts.getOrNull(7)?.toLongOrNull() ?: 0L
                    stats.networkBytes += (mRx + mTx + wRx + wTx).coerceAtLeast(0L)
                }
                "wl" -> {
                    // 格式: vers,uid,l,wl,tag,fullTime,fullCount,partialTime,partialCount,...
                    var idx = 4
                    while (idx + 3 < parts.size) {
                        val partialTime = parts.getOrNull(idx + 3)?.toLongOrNull() ?: 0L
                        if (partialTime > 0L) {
                            stats.wakelockMs += partialTime
                        }
                        idx += 6
                    }
                }
                "g" -> {
                    // 格式: vers,uid,l,g,gpsTimeMs,gpsCount
                    val gpsTime = parts.getOrNull(4)?.toLongOrNull() ?: 0L
                    stats.gpsMs += gpsTime
                }
                "fgs" -> {
                    // 格式: vers,uid,l,fgs,fgsTimeMs,fgsCount
                    val fgsTime = parts.getOrNull(4)?.toLongOrNull() ?: 0L
                    stats.fgsMs += fgsTime
                }
                "pr" -> {
                    // 格式: vers,uid,l,pr,procName,userTime,systemTime,starts,numCrashes,numAnrs,fgTime
                    val user = parts.getOrNull(5)?.toLongOrNull() ?: 0L
                    val sys = parts.getOrNull(6)?.toLongOrNull() ?: 0L
                    val fg = parts.getOrNull(10)?.toLongOrNull() ?: 0L
                    if (stats.cpuUserMs == 0L && stats.cpuSystemMs == 0L) {
                        stats.cpuUserMs += user
                        stats.cpuSystemMs += sys
                    }
                    if (fg > 0L) {
                        stats.cpuForegroundMs += fg
                    }
                }
            }
        }
        return resultMap
    }

    /**
     * 从常规文本格式的 dumpsys batterystats 输出中扫描提取各 UID 的硬件指标（作为 Checkin 缺失时的兜底补充）。
     *
     * @param rawText 常规 dumpsys batterystats 文本内容
     * @return 按 UID 索引的硬件资源消耗映射表 [Map<Int, UidHardwareStats>]
     */
    fun parseHardwareStatsFromPlainText(rawText: String): Map<Int, UidHardwareStats> {
        if (rawText.isBlank()) return emptyMap()
        val resultMap = mutableMapOf<Int, UidHardwareStats>()
        var currentUid = -1

        val uidHeaderRegex = Pattern.compile("^\\s{2,4}(?:Uid\\s+)?([\\w]+):\\s*$", Pattern.CASE_INSENSITIVE)
        val cpuLineRegex = Pattern.compile("CPU:\\s*(?:([\\d\\w\\s]+?)\\s*usr)?(?:\\s*\\+\\s*([\\d\\w\\s]+?)\\s*krn)?(?:\\s*;\\s*([\\d\\w\\s]+?)\\s*fg)?(?:\\s*;\\s*([\\d\\w\\s]+?)\\s*bg)?", Pattern.CASE_INSENSITIVE)
        val wakeLineRegex = Pattern.compile("(?:TOTAL wake|Wake lock [^:]+):\\s*([\\d\\w\\s]+?)\\s*partial", Pattern.CASE_INSENSITIVE)
        val gpsLineRegex = Pattern.compile("(?:GPS|Sensor GPS):\\s*([\\d\\w\\s]+?)(?:\\s+realtime|\\s*\\(|$)", Pattern.CASE_INSENSITIVE)
        val netLineRegex = Pattern.compile("(?:Mobile|Wi-Fi|WiFi) network:\\s*([\\d\\w\\s.]+?)\\s*received,\\s*([\\d\\w\\s.]+?)\\s*sent", Pattern.CASE_INSENSITIVE)

        rawText.lineSequence().forEach { line ->
            val uMatch = uidHeaderRegex.matcher(line)
            if (uMatch.find()) {
                val rawStr = uMatch.group(1) ?: ""
                currentUid = convertUidStringToNumeric(rawStr)
                return@forEach
            }

            if (currentUid <= 0) return@forEach
            val trimmed = line.trim()

            if (line.isNotEmpty() && !line.startsWith(" ") && !line.startsWith("\t")) {
                currentUid = -1
                return@forEach
            }

            val stats = resultMap.getOrPut(currentUid) { UidHardwareStats(currentUid) }

            val cpuMatch = cpuLineRegex.matcher(trimmed)
            if (cpuMatch.find()) {
                val usr = cpuMatch.group(1)?.let { parseDurationStringToMs(it) } ?: 0L
                val krn = cpuMatch.group(2)?.let { parseDurationStringToMs(it) } ?: 0L
                val fg = cpuMatch.group(3)?.let { parseDurationStringToMs(it) } ?: 0L
                val bg = cpuMatch.group(4)?.let { parseDurationStringToMs(it) } ?: 0L
                if (usr > 0L || krn > 0L) {
                    stats.cpuUserMs = maxOf(stats.cpuUserMs, usr)
                    stats.cpuSystemMs = maxOf(stats.cpuSystemMs, krn)
                }
                if (fg > 0L) stats.cpuForegroundMs = maxOf(stats.cpuForegroundMs, fg)
                if (bg > 0L) stats.cpuBackgroundMs = maxOf(stats.cpuBackgroundMs, bg)
            }

            val wakeMatch = wakeLineRegex.matcher(trimmed)
            if (wakeMatch.find()) {
                val wakeMs = wakeMatch.group(1)?.let { parseDurationStringToMs(it) } ?: 0L
                if (wakeMs > 0L) {
                    stats.wakelockMs = maxOf(stats.wakelockMs, wakeMs)
                }
            }

            val gpsMatch = gpsLineRegex.matcher(trimmed)
            if (gpsMatch.find()) {
                val gpsMs = gpsMatch.group(1)?.let { parseDurationStringToMs(it) } ?: 0L
                if (gpsMs > 0L) {
                    stats.gpsMs = maxOf(stats.gpsMs, gpsMs)
                }
            }

            val netMatch = netLineRegex.matcher(trimmed)
            if (netMatch.find()) {
                val rxBytes = parseNetworkBytes(netMatch.group(1) ?: "")
                val txBytes = parseNetworkBytes(netMatch.group(2) ?: "")
                stats.networkBytes += (rxBytes + txBytes)
            }
        }
        return resultMap
    }

    /**
     * 解析网络流量文本为字节数（如 "12.50KB"、"1.20MB"、"345B"）。
     *
     * @param text 流量文本
     * @return 转换后的字节总数
     */
    private fun parseNetworkBytes(text: String): Long {
        val clean = text.trim()
        val numMatch = Pattern.compile("([\\d.]+)\\s*([a-zA-Z]+)?").matcher(clean)
        if (!numMatch.find()) return 0L
        val value = numMatch.group(1)?.toDoubleOrNull() ?: return 0L
        val unit = numMatch.group(2)?.uppercase() ?: "B"
        return when {
            unit.startsWith("G") -> (value * 1024 * 1024 * 1024).toLong()
            unit.startsWith("M") -> (value * 1024 * 1024).toLong()
            unit.startsWith("K") -> (value * 1024).toLong()
            else -> value.toLong()
        }
    }

    /**
     * 通过 Shizuku 反射执行底层 Shell 命令并获取输出结果。
     *
     * @param command 要执行的 Shell 命令字符串
     * @return 命令标准输出文本
     */
    fun executeShizukuShellCommand(command: String): String {
        return try {
            if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return ""
            }
            val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
            val newProcessMethod = shizukuClass.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }

            val process = newProcessMethod.invoke(null, arrayOf("sh", "-c", command), null, null) as? Process ?: return ""
            val reader = BufferedReader(InputStreamReader(process.inputStream), 8192)
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line).append('\n')
            }
            reader.close()
            process.waitFor()
            sb.toString()
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    private fun executeShizukuCommand(command: String): String = executeShizukuShellCommand(command)

    /**
     * 通过 Shizuku 提权执行 dumpsys batterystats --reset 重置系统底层的放电统计账本。
     */
    fun resetBatteryStats() {
        executeShizukuShellCommand("dumpsys batterystats --reset")
    }

    companion object {
        private val REGEX_CAP_DRAIN = Pattern.compile("Capacity:\\s*([\\d.]+).*?Computed drain:\\s*([\\d.]+)", Pattern.CASE_INSENSITIVE)
        private val REGEX_COMPUTED_DRAIN_ALONE = Pattern.compile("Computed drain:\\s*([\\d.]+)", Pattern.CASE_INSENSITIVE)
        private val REGEX_SCREEN_DRAIN_LINE = Pattern.compile("^\\s*Screen:\\s*([\\d.]+)", Pattern.CASE_INSENSITIVE)
        private val REGEX_TIME_ON_BATTERY = Pattern.compile("Time on battery:\\s*([^\\n\\(]+)", Pattern.CASE_INSENSITIVE)
        private val REGEX_DISCHARGE_TIME = Pattern.compile("Discharge:\\s*([^\\n\\(]+)", Pattern.CASE_INSENSITIVE)
        private val REGEX_SCREEN_ON = Pattern.compile("Screen on:\\s*([^\\n\\(]+)", Pattern.CASE_INSENSITIVE)
        private val REGEX_SCREEN_OFF_TIME = Pattern.compile("Time on battery screen off:\\s*([^\\n\\(]+)", Pattern.CASE_INSENSITIVE)
        private val REGEX_SCREEN_OFF_SIMPLE = Pattern.compile("Screen off:\\s*([^\\n\\(]+)", Pattern.CASE_INSENSITIVE)
        private val REGEX_SCREEN_OFF_DISCHARGE_MAH = Pattern.compile("Screen off discharge:\\s*([\\d.]+)\\s*mAh", Pattern.CASE_INSENSITIVE)
        private val REGEX_SCREEN_OFF_DISCHARGE_AMOUNT = Pattern.compile("Amount discharged while screen off:\\s*(\\d+)", Pattern.CASE_INSENSITIVE)
        private val REGEX_IDLE_DRAIN = Pattern.compile("(?:Idle|Device standby):\\s*([\\d.]+)", Pattern.CASE_INSENSITIVE)
        private val REGEX_UID_POWER = Pattern.compile("Uid\\s+([\\w]+)(?:\\s*\\(([^\\)]+)\\))?:\\s*([\\d.]+)(?:\\s*\\((.*?)\\))?", Pattern.CASE_INSENSITIVE)
        private val REGEX_TOP_TIME = Pattern.compile("(?:top|fg)[=:]\\s*([\\d\\w\\s]+?)(?=\\s+[a-zA-Z_-]+[=:]|\\)|$)", Pattern.CASE_INSENSITIVE)
        private val REGEX_FG_TIME = Pattern.compile("fg[=:]\\s*([\\d\\w\\s]+?)(?=\\s+[a-zA-Z_-]+[=:]|\\)|$)", Pattern.CASE_INSENSITIVE)
        private val REGEX_BG_TIME = Pattern.compile("(?:bg|fgs|service|cached)[=:]\\s*([\\d\\w\\s]+?)(?=\\s+[a-zA-Z_-]+[=:]|\\)|$)", Pattern.CASE_INSENSITIVE)
        private val REGEX_CPU_TIME = Pattern.compile("cpu[=:]\\s*([\\d\\w\\s]+?)(?=\\s+[a-zA-Z_-]+[=:]|\\)|$)", Pattern.CASE_INSENSITIVE)
        private val REGEX_ANDROID_UID = Pattern.compile("^u(\\d+)_?a(\\d+)$", Pattern.CASE_INSENSITIVE)
        private val REGEX_RESET_TIME = Pattern.compile("RESET:TIME:\\s*(\\d{4})-(\\d{2})-(\\d{2})-(\\d{2})-(\\d{2})-(\\d{2})", Pattern.CASE_INSENSITIVE)
        private val REGEX_BATTERY_HISTORY_LINE = Pattern.compile("^(?:([+-]?[\\w\\d]+)\\s+)?\\(\\d+\\)\\s*(\\d{1,3})\\b", Pattern.CASE_INSENSITIVE)
        private val REGEX_HISTORY_TEMP = Pattern.compile("(?:^|\\s)[+-]?temp=(\\d+)", Pattern.CASE_INSENSITIVE)
        private val REGEX_DISCHARGE_STEP = Pattern.compile("#\\d+:\\s*\\+([\\w\\d]+)\\s+to\\s+(\\d{1,3})", Pattern.CASE_INSENSITIVE)

        /** 息屏待机状态下的绝对最大物理放电功耗上限（单位：W），手机在熄灭屏幕与GPU休眠时功耗绝不应超过该极限 */
        const val MAX_STANDBY_POWER_WATTS = 3.0f

        /** 手机息屏待机状态下的典型底座基础功率（单位：W），客观反映基带待机与系统基础唤醒保活底噪 */
        const val DEFAULT_STANDBY_BASE_WATTS = 0.15f
    }
}
