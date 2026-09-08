package com.battery.analysis.provider

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.battery.analysis.model.AppPowerUsageItem
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

        // 2. 优先获取自上次断开充电以来的增量账本
        var rawOutput = executeShizukuCommand("dumpsys batterystats --charged")
        if (rawOutput.isBlank() || !rawOutput.contains("Estimated power use")) {
            rawOutput = executeShizukuCommand("dumpsys batterystats")
        }

        if (rawOutput.isBlank()) {
            return BatteryStatsResult(4500f, 0f, 0L, 0L, 0L, 0f, emptyList())
        }

        return parseStatsText(rawOutput, batteryVoltageVolts, batteryTempCelsius, uidPkgMap, unplugTime)
    }

    /**
     * 解析 dumpsys 原始输出字符串。
     *
     * @param rawText dumpsys batterystats 原始文本
     * @param voltageVolts 电池电压
     * @param tempCelsius 电池温度
     * @param uidPkgMap 事先通过提权获取的 UID 到包名映射字典
     * @param unplugTime 最近一次断开充电或手动重置的时间戳（毫秒），默认为 0L
     * @return 解析完成的 [BatteryStatsResult]
     */
    /**
     * 解析 dumpsys 原始输出字符串。
     *
     * @param rawText dumpsys batterystats 原始文本
     * @param voltageVolts 电池电压
     * @param tempCelsius 电池温度
     * @param uidPkgMap 事先通过提权获取的 UID 到包名映射字典
     * @param unplugTime 最近一次断开充电或手动重置的时间戳（毫秒），默认为 0L
     * @return 解析完成的 [BatteryStatsResult]
     */
    fun parseStatsText(
        rawText: String,
        voltageVolts: Float,
        tempCelsius: Float,
        uidPkgMap: Map<Int, String> = emptyMap(),
        unplugTime: Long = 0L
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
                    screenOffDrainMah = (percent / 100f) * capacityMah
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

                            // 前后台能量拆分：基于真实 CPU 算力与物理活跃时长客观分配总能量，彻底废除写死 3.0 倍假权重
                            val fgEnergyWh: Float
                            val bgEnergyWh: Float
                            if (foregroundMs > 0L && backgroundMs > 0L) {
                                if (cpuMs > 0L) {
                                    if (cpuMs <= foregroundMs) {
                                        // 应用总 CPU 算力均发生在前台活跃期间（后台处于挂起休眠状态，无计算功耗），前台承担全部能量
                                        fgEnergyWh = totalDirectEnergyWh
                                        bgEnergyWh = 0f
                                    } else {
                                        // 后台存在真实持续计算负载（超出部分为后台算力），按前台与后台真实算力占比分配
                                        val fgRatio = (foregroundMs.toFloat() / cpuMs.toFloat()).coerceIn(0.1f, 1.0f)
                                        fgEnergyWh = totalDirectEnergyWh * fgRatio
                                        bgEnergyWh = (totalDirectEnergyWh - fgEnergyWh).coerceAtLeast(0f)
                                    }
                                } else {
                                    // 未解析到 cpu 字段时，按前后台真实物理时长占比客观切分
                                    val totalMs = foregroundMs + backgroundMs
                                    val fgRatio = if (totalMs > 0L) (foregroundMs.toFloat() / totalMs.toFloat()) else 1.0f
                                    fgEnergyWh = totalDirectEnergyWh * fgRatio
                                    bgEnergyWh = (totalDirectEnergyWh - fgEnergyWh).coerceAtLeast(0f)
                                }
                            } else if (foregroundMs > 0L) {
                                fgEnergyWh = totalDirectEnergyWh
                                bgEnergyWh = 0f
                            } else {
                                // 纯后台应用（前台时长为 0）：能量 100% 归属于后台能量，前台能量严格为 0f，
                                // 彻底杜绝纯后台常驻守护进程能耗误算为前台能耗并侵吞整机屏幕基底功率池
                                fgEnergyWh = 0f
                                bgEnergyWh = totalDirectEnergyWh
                            }

                            // 运行平均功耗计算：若有前台活跃按前台能耗与前台时长计算；若为纯后台应用且有明确后台运行耗时，按后台运行能耗与时长计算
                            val fgHours = foregroundMs / 3600000.0
                            val bgHours = backgroundMs / 3600000.0
                            val avgWatts = if (fgHours > 0.0) {
                                (fgEnergyWh / fgHours).toFloat()
                            } else if (bgHours > 0.0 && bgEnergyWh > 0f) {
                                (bgEnergyWh / bgHours).toFloat()
                            } else {
                                0f
                            }

                            val appTemp = tempCelsius
                            val maxTemp = tempCelsius

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
                                    backgroundTimeMs = backgroundMs,
                                    foregroundEnergyWh = fgEnergyWh,
                                    backgroundEnergyWh = bgEnergyWh
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
                                    backgroundTimeMs = backgroundMs,
                                    foregroundEnergyWh = fgEnergyWh,
                                    backgroundEnergyWh = bgEnergyWh
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
        val userAppList = mergeWithUsageStatsUserApps(parsedAppMap, pkgDrainMahMap, dischargeDurationMs, voltageVolts, cycleAvgTemp, cycleMaxTemp, unplugTime)

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
        // 则整机总放电量扣除前台亮屏应用与屏幕显示所消耗电量后的结余放电量作为息屏待机放电量
        if (screenOffDrainMah <= 0f && screenOffDurationMs >= 30000L && computedDrainMah > 0f) {
            val totalFgDrain = validatedList.sumOf { (it.energyWh * 1000f / voltageVolts.coerceAtLeast(3.7f)).toDouble() }.toFloat()
            val remainingDrain = computedDrainMah - totalFgDrain - screenDrainMah
            if (remainingDrain > 0.05f) {
                screenOffDrainMah = remainingDrain
            }
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
     * 结合系统应用使用情况管理器，对 dumpsys 数据进行前台时间与能耗辅助补全。
     * 当 dumpsys 详情中未携带具体前台时间（top/fg 字段缺省）时，通过 UsageStats 增量精准补全前台时长并重新解耦前后台能量与亮屏功耗；
     * 并对 dumpsys 遗漏但系统确实有前台记录的用户应用进行补充。
     *
     * @param existingMap 已通过 dumpsys batterystats 解析得到的应用映射字典
     * @param pkgDrainMahMap 各应用由 dumpsys 解析得到的真实放电量（mAh）字典
     * @param dischargeMs 本次放电周期的实际放电时长毫秒数
     * @param voltage 电池电压（V）
     * @param cycleAvgTemp 放电周期内测得的电池平均温度（℃）
     * @param cycleMaxTemp 放电周期内测得的电池最高温度（℃）
     * @param unplugTime 最近一次断开充电或手动重置的时间戳（毫秒），默认为 0L
     * @return 融合校准后的应用耗电列表 [MutableList<AppPowerUsageItem>]
     */
    private fun mergeWithUsageStatsUserApps(
        existingMap: MutableMap<String, AppPowerUsageItem>,
        pkgDrainMahMap: Map<String, Float>,
        dischargeMs: Long,
        voltage: Float,
        cycleAvgTemp: Float,
        cycleMaxTemp: Float,
        unplugTime: Long = 0L
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

        // 1. 对 dumpsys 原本提取的应用进行前台时长与真实亮屏功耗的精准补全
        val existingKeys = existingMap.keys.toList()
        for (pkg in existingKeys) {
            val old = existingMap[pkg] ?: continue
            var effectiveFg = old.foregroundTimeMs
            val effectiveBg = old.backgroundTimeMs

            // 若 dumpsys 详情中没有携带前台活跃时间，从 UsageEvents 精准补齐
            if (effectiveFg <= 0L) {
                val realFg = preciseTimes[pkg] ?: 0L
                if (realFg > 0L) {
                    effectiveFg = realFg.coerceAtMost(dischargeMs)
                } else if (pkg == context.packageName) {
                    // 本应用持续在前台运行，若 UsageEvents 延迟未返回，以当前放电周期时长作为有效前台时长
                    effectiveFg = dischargeMs.coerceAtLeast(1000L)
                }
            }

            // 依据真实补全后的前后台时长，重新解耦前台消耗能量、后台消耗能量以及亮屏平均功耗
            val totalEnergy = old.directEnergyWh ?: ((pkgDrainMahMap[pkg] ?: 0f) * voltage / 1000f)
            val fgEnergyWh: Float
            val bgEnergyWh: Float
            if (effectiveFg > 0L && effectiveBg > 0L) {
                val totalMs = effectiveFg + effectiveBg
                val fgRatio = if (totalMs > 0L) (effectiveFg.toFloat() / totalMs.toFloat()) else 1.0f
                fgEnergyWh = totalEnergy * fgRatio
                bgEnergyWh = (totalEnergy - fgEnergyWh).coerceAtLeast(0f)
            } else if (effectiveFg > 0L) {
                fgEnergyWh = totalEnergy
                bgEnergyWh = 0f
            } else {
                // 纯后台应用：前台为 0，能量 100% 归属于后台
                fgEnergyWh = 0f
                bgEnergyWh = totalEnergy
            }

            val fgHours = effectiveFg / 3600000.0
            val bgHours = effectiveBg / 3600000.0
            val avgWatts = if (fgHours > 0.0) {
                (fgEnergyWh / fgHours).toFloat()
            } else if (bgHours > 0.0 && bgEnergyWh > 0f) {
                (bgEnergyWh / bgHours).toFloat()
            } else {
                0f
            }

            existingMap[pkg] = old.copy(
                foregroundTimeMs = effectiveFg,
                backgroundTimeMs = effectiveBg,
                foregroundEnergyWh = fgEnergyWh,
                backgroundEnergyWh = bgEnergyWh,
                avgPowerWatts = avgWatts
            )
        }

        // 2. 补充 dumpsys 遗漏但系统事件中确实在前台运行的用户应用
        // 计算已知应用的前台平均功耗作为参考基准，杜绝使用写死常数
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
                            backgroundTimeMs = 0L,
                            foregroundEnergyWh = fgEnergy,
                            backgroundEnergyWh = 0f
                        )
                    } catch (_: PackageManager.NameNotFoundException) {
                    }
                }
            }
        }

        return existingMap.values.toMutableList()
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
        private val REGEX_BG_TIME = Pattern.compile("bg[=:]\\s*([\\d\\w\\s]+?)(?=\\s+[a-zA-Z_-]+[=:]|\\)|$)", Pattern.CASE_INSENSITIVE)
        private val REGEX_CPU_TIME = Pattern.compile("cpu[=:]\\s*([\\d\\w\\s]+?)(?=\\s+[a-zA-Z_-]+[=:]|\\)|$)", Pattern.CASE_INSENSITIVE)
        private val REGEX_ANDROID_UID = Pattern.compile("^u(\\d+)_?a(\\d+)$", Pattern.CASE_INSENSITIVE)
        private val REGEX_RESET_TIME = Pattern.compile("RESET:TIME:\\s*(\\d{4})-(\\d{2})-(\\d{2})-(\\d{2})-(\\d{2})-(\\d{2})", Pattern.CASE_INSENSITIVE)
        private val REGEX_BATTERY_HISTORY_LINE = Pattern.compile("^(?:([+-]?[\\w\\d]+)\\s+)?\\(\\d+\\)\\s*(\\d{1,3})\\b", Pattern.CASE_INSENSITIVE)
        private val REGEX_HISTORY_TEMP = Pattern.compile("(?:^|\\s)[+-]?temp=(\\d+)", Pattern.CASE_INSENSITIVE)
        private val REGEX_DISCHARGE_STEP = Pattern.compile("#\\d+:\\s*\\+([\\w\\d]+)\\s+to\\s+(\\d{1,3})", Pattern.CASE_INSENSITIVE)
    }
}
