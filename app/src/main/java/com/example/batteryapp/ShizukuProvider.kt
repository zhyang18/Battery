package com.example.batteryapp

import android.content.Context
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 负责通过 Shizuku 执行 root/shell 权限命令，从 sysfs、dumpsys 和厂商服务中读取电池信息的提供者。
 */
class ShizukuProvider : BatteryDataProvider {

    /**
     * 获取电池信息，内部会分别从 sysfs、dumpsys 和厂商扩展服务中读取并融合数据。
     *
     * @param context 应用程序的上下文
     * @return 获取到的电池信息对象
     */
    override fun getBatteryInfo(context: Context): BatteryInfo {
        if (!Shizuku.pingBinder()) {
            return BatteryInfo(source = "Shizuku (未激活)")
        }
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return BatteryInfo(source = "Shizuku (无权限)")
        }

        val sysfsInfo = readFromSysfs()
        val dumpsysInfo = readFromDumpsys()
        val vendorInfo = readFromVendor()

        // 优先层级与数据聚合
        val designCap = sysfsInfo.designCapacity
            ?: dumpsysInfo.designCapacity
            ?: vendorInfo.designCapacity

        val currentCap = sysfsInfo.currentCapacity
            ?: dumpsysInfo.currentCapacity
            ?: vendorInfo.currentCapacity

        val fullCap = sysfsInfo.fullChargeCapacity
            ?: dumpsysInfo.fullChargeCapacity
            ?: vendorInfo.fullChargeCapacity

        val allCycles = listOfNotNull(sysfsInfo.cycleCount, dumpsysInfo.cycleCount, vendorInfo.cycleCount)
        val cycleCount = if (allCycles.isNotEmpty()) allCycles.maxOrNull() else null

        val level = sysfsInfo.level ?: dumpsysInfo.level ?: vendorInfo.level
        val status = sysfsInfo.status ?: dumpsysInfo.status ?: vendorInfo.status
        val healthStatus = sysfsInfo.healthStatus ?: dumpsysInfo.healthStatus ?: vendorInfo.healthStatus
        val temperature = sysfsInfo.temperature ?: dumpsysInfo.temperature ?: vendorInfo.temperature
        val voltage = sysfsInfo.voltage ?: dumpsysInfo.voltage ?: vendorInfo.voltage
        val currentNow = sysfsInfo.currentNow ?: dumpsysInfo.currentNow ?: vendorInfo.currentNow
        val powerWatts = if (voltage != null && currentNow != null) {
            Math.abs(voltage * currentNow) / 1000000f
        } else null
        val technology = sysfsInfo.technology ?: dumpsysInfo.technology ?: vendorInfo.technology
        val isDualCell = sysfsInfo.isDualCell ?: dumpsysInfo.isDualCell ?: vendorInfo.isDualCell ?: false

        var health: Float? = sysfsInfo.batteryHealth ?: dumpsysInfo.batteryHealth ?: vendorInfo.batteryHealth
        if (health == null && fullCap != null && designCap != null && designCap > 0) {
            health = (fullCap / designCap) * 100f
            if (health > 100f) health = 100f
        }

        return BatteryInfo(
            batteryHealth = health,
            healthStatus = healthStatus,
            level = level,
            status = status,
            designCapacity = designCap,
            currentCapacity = currentCap,
            fullChargeCapacity = fullCap,
            cycleCount = cycleCount,
            temperature = temperature,
            voltage = voltage,
            currentNow = currentNow,
            powerWatts = powerWatts,
            isDualCell = isDualCell,
            technology = technology,
            source = "Shizuku (sysfs + dumpsys + 厂商)"
        )
    }

    /**
     * 从 sysfs 节点读取电池信息。
     * 涵盖系统通用节点、uevent 以及部分常见驱动路径。
     *
     * @return 解析 sysfs 节点得到的电池信息
     */
    private fun readFromSysfs(): BatteryInfo {
        var designCapacity: Float? = null
        var fullChargeCapacity: Float? = null
        var currentCapacity: Float? = null
        var cycleCount: Int? = null
        var temperature: Float? = null
        var voltage: Float? = null
        var currentNow: Float? = null
        var status: String? = null
        var healthStatus: String? = null
        var technology: String? = null
        var isDualCell: Boolean? = null

        val sysfsCmd = "cat /sys/class/power_supply/battery/uevent /sys/class/power_supply/bms/uevent /sys/class/power_supply/main/uevent /sys/class/qcom-battery/uevent /sys/class/power_supply/*/uevent 2>/dev/null"
        val ueventRes = executeCommand(sysfsCmd)

        val lines = ueventRes.split('\n')
        for (line in lines) {
            val trimLine = line.trim()
            if (trimLine.contains("POWER_SUPPLY_CHARGE_FULL_DESIGN=") || trimLine.contains("POWER_SUPPLY_CHARGE_FULL_DESIGN_MAH=")) {
                val value = trimLine.substringAfter("=").toLongOrNull()
                if (value != null && value > 0) {
                    val mah = if (value < 100000) value.toFloat() else value / 1000f
                    if (designCapacity == null || mah > designCapacity) designCapacity = mah
                }
            }
            if (trimLine.contains("POWER_SUPPLY_CHARGE_FULL=") || trimLine.contains("POWER_SUPPLY_CHARGE_FULL_MAH=")) {
                val value = trimLine.substringAfter("=").toLongOrNull()
                if (value != null && value > 0) {
                    val mah = if (value < 100000) value.toFloat() else value / 1000f
                    if (fullChargeCapacity == null || mah > fullChargeCapacity) fullChargeCapacity = mah
                }
            }
            if (trimLine.contains("POWER_SUPPLY_CHARGE_NOW=") || trimLine.contains("POWER_SUPPLY_CHARGE_NOW_MAH=")) {
                val value = trimLine.substringAfter("=").toLongOrNull()
                if (value != null && value > 0) {
                    val mah = if (value < 100000) value.toFloat() else value / 1000f
                    if (currentCapacity == null || mah > currentCapacity) currentCapacity = mah
                }
            }
            if (trimLine.contains("POWER_SUPPLY_CYCLE_COUNT=") || trimLine.contains("POWER_SUPPLY_CHARGE_CYCLE=") || trimLine.contains("POWER_SUPPLY_BATTERY_CYCLE=")) {
                val value = trimLine.substringAfter("=").toIntOrNull()
                if (value != null && value > 0) {
                    if (cycleCount == null || value > cycleCount) cycleCount = value
                }
            }
            if (trimLine.contains("POWER_SUPPLY_TEMP=")) {
                val rawTemp = trimLine.substringAfter("=").toFloatOrNull()
                if (rawTemp != null && rawTemp > 0) {
                    temperature = rawTemp / 10f
                }
            }
            if (trimLine.contains("POWER_SUPPLY_VOLTAGE_NOW=")) {
                val rawVolt = trimLine.substringAfter("=").toLongOrNull()
                if (rawVolt != null && rawVolt > 0) {
                    voltage = if (rawVolt < 10000) rawVolt.toFloat() else rawVolt / 1000f
                }
            }
            if (trimLine.contains("POWER_SUPPLY_CURRENT_NOW=")) {
                val rawCur = trimLine.substringAfter("=").toLongOrNull()
                if (rawCur != null && rawCur != 0L) {
                    currentNow = if (Math.abs(rawCur) < 100000) rawCur.toFloat() else rawCur / 1000f
                }
            }
            if (trimLine.contains("POWER_SUPPLY_STATUS=")) {
                val s = trimLine.substringAfter("=").trim()
                if (s.isNotEmpty()) status = s
            }
            if (trimLine.contains("POWER_SUPPLY_HEALTH=")) {
                val h = trimLine.substringAfter("=").trim()
                if (h.isNotEmpty()) healthStatus = h
            }
            if (trimLine.contains("POWER_SUPPLY_TECHNOLOGY=")) {
                val t = trimLine.substringAfter("=").trim()
                if (t.isNotEmpty()) technology = t
            }
        }

        return BatteryInfo(
            designCapacity = designCapacity,
            fullChargeCapacity = fullChargeCapacity,
            currentCapacity = currentCapacity,
            cycleCount = cycleCount,
            temperature = temperature,
            voltage = voltage,
            currentNow = currentNow,
            status = status,
            healthStatus = healthStatus,
            technology = technology,
            isDualCell = isDualCell
        )
    }

    /**
     * 通过执行 dumpsys (battery, batterystats, broadcasts) 命令读取电池信息。
     *
     * @return 解析 dumpsys 得到的电池信息
     */
    private fun readFromDumpsys(): BatteryInfo {
        var designCapacity: Float? = null
        var fullChargeCapacity: Float? = null
        var currentCapacity: Float? = null
        var cycleCount: Int? = null
        var batteryLevel: Int? = null
        var statusStr: String? = null
        var healthStr: String? = null
        var temperature: Float? = null
        var voltage: Float? = null
        var currentNow: Float? = null
        var technology: String? = null

        // 1. 获取 dumpsys battery 信息
        val batteryOutput = executeCommand("dumpsys battery")
        val regexChargeCounter = Regex("(?i)Charge counter:\\s*(\\d+)")
        val matchCharge = regexChargeCounter.find(batteryOutput)
        if (matchCharge != null) {
            val raw = matchCharge.groupValues[1].toLongOrNull()
            if (raw != null && raw > 0) {
                currentCapacity = if (raw < 100000) raw.toFloat() else raw / 1000f
            }
        }

        val regexLevel = Regex("(?i)level:\\s*(\\d+)")
        val matchLevel = regexLevel.find(batteryOutput)
        if (matchLevel != null) {
            batteryLevel = matchLevel.groupValues[1].toIntOrNull()
        }

        val regexStatus = Regex("(?i)status:\\s*(\\d+)")
        val matchStatus = regexStatus.find(batteryOutput)
        if (matchStatus != null) {
            statusStr = when (matchStatus.groupValues[1].toIntOrNull()) {
                2 -> "充电中"
                3 -> "放电中"
                4 -> "未充电"
                5 -> "已充满"
                else -> "未知"
            }
        }

        val regexHealth = Regex("(?i)health:\\s*(\\d+)")
        val matchHealth = regexHealth.find(batteryOutput)
        if (matchHealth != null) {
            healthStr = when (matchHealth.groupValues[1].toIntOrNull()) {
                2 -> "良好"
                3 -> "过热"
                4 -> "损坏"
                5 -> "电压过高"
                6 -> "未知故障"
                7 -> "过冷"
                else -> "未知"
            }
        }

        val regexTemp = Regex("(?i)temperature:\\s*(\\d+)")
        val matchTemp = regexTemp.find(batteryOutput)
        if (matchTemp != null) {
            val rawTemp = matchTemp.groupValues[1].toFloatOrNull()
            if (rawTemp != null && rawTemp > 0) {
                temperature = rawTemp / 10.0f
            }
        }

        val regexVolt = Regex("(?i)voltage:\\s*(\\d+)")
        val matchVolt = regexVolt.find(batteryOutput)
        if (matchVolt != null) {
            voltage = matchVolt.groupValues[1].toFloatOrNull()
        }

        val regexTech = Regex("(?i)technology:\\s*(\\w+)")
        val matchTech = regexTech.find(batteryOutput)
        if (matchTech != null) {
            technology = matchTech.groupValues[1]
        }

        val regexCycle = Regex("(?i)(?:cycle count|mcyclecount|battery cycle|cycle_count):\\s*(\\d+)")
        val matchCycle = regexCycle.find(batteryOutput)
        if (matchCycle != null) {
            cycleCount = matchCycle.groupValues[1].toIntOrNull()
        }

        // 2. 获取 dumpsys batterystats 信息
        val statsOutput = executeCommand("dumpsys batterystats")
        val regexCapacity = Regex("(?i)(?:Estimated battery capacity|Capacity):\\s*(\\d+)\\s*(?:mAh)?")
        val matchCapacity = regexCapacity.find(statsOutput)
        if (matchCapacity != null) {
            designCapacity = matchCapacity.groupValues[1].toFloatOrNull()
        }

        val regexLearned = Regex("(?i)(?:Learned battery capacity|Min learned battery capacity|mLearnedCap):\\s*(\\d+)")
        val matchLearned = regexLearned.find(statsOutput)
        if (matchLearned != null) {
            val raw = matchLearned.groupValues[1].toLongOrNull()
            if (raw != null && raw > 0) {
                fullChargeCapacity = if (raw < 100000) raw.toFloat() else raw / 1000f
            }
        }

        // 3. 从 dumpsys activity broadcasts 中提取系统电池粘性广播里的循环次数
        val broadcastsOutput = executeCommand("dumpsys activity broadcasts")
        val regexBroadcastCycle = Regex("(?i)android\\.os\\.extra\\.CYCLE_COUNT=(\\d+)")
        val matchBroadcastCycle = regexBroadcastCycle.find(broadcastsOutput)
        if (matchBroadcastCycle != null) {
            val count = matchBroadcastCycle.groupValues[1].toIntOrNull()
            if (count != null && count > 0) {
                if (cycleCount == null || count > cycleCount) {
                    cycleCount = count
                }
            }
        }

        // 如果未直接读取到满电容量，通过当前容量和电量百分比计算
        if (fullChargeCapacity == null && currentCapacity != null && batteryLevel != null && batteryLevel > 0) {
            fullChargeCapacity = (currentCapacity / batteryLevel) * 100f
        }

        return BatteryInfo(
            designCapacity = designCapacity,
            fullChargeCapacity = fullChargeCapacity,
            currentCapacity = currentCapacity,
            cycleCount = cycleCount,
            level = batteryLevel,
            status = statusStr,
            healthStatus = healthStr,
            temperature = temperature,
            voltage = voltage,
            currentNow = currentNow,
            technology = technology
        )
    }

    /**
     * 通过执行厂商特定服务和系统属性（getprop、dumpsys miui.battery 等）读取电池信息。
     *
     * @return 解析厂商服务得到的电池信息
     */
    private fun readFromVendor(): BatteryInfo {
        var designCapacity: Float? = null
        var fullChargeCapacity: Float? = null
        var currentCapacity: Float? = null
        var cycleCount: Int? = null

        val propsRes = executeCommand("getprop")
        for (line in propsRes.split('\n')) {
            val trimLine = line.trim()
            if (trimLine.contains("[") && trimLine.contains("]")) {
                val key = trimLine.substringBefore("]:").replace("[", "").trim()
                val valueStr = trimLine.substringAfter("]:").replace("[", "").replace("]", "").trim()

                if (key.contains("charge_full_design") || key.endsWith(".charge_full_design")) {
                    val value = valueStr.toLongOrNull()
                    if (value != null && value > 0) {
                        val mah = if (value < 100000) value.toFloat() else value / 1000f
                        if (designCapacity == null || mah > designCapacity) designCapacity = mah
                    }
                }
                if (key.contains("charge_full") || key.endsWith(".charge_full")) {
                    val value = valueStr.toLongOrNull()
                    if (value != null && value > 0) {
                        val mah = if (value < 100000) value.toFloat() else value / 1000f
                        if (fullChargeCapacity == null || mah > fullChargeCapacity) fullChargeCapacity = mah
                    }
                }
                if (key.contains("charge_now") || key.endsWith(".charge_now")) {
                    val value = valueStr.toLongOrNull()
                    if (value != null && value > 0) {
                        val mah = if (value < 100000) value.toFloat() else value / 1000f
                        if (currentCapacity == null || mah > currentCapacity) currentCapacity = mah
                    }
                }
                if (key.contains("cycle_count") || key.endsWith(".cycle_count") || key.contains("charge_cycle")) {
                    val value = valueStr.toIntOrNull()
                    if (value != null && value > 0) {
                        if (cycleCount == null || value > cycleCount) cycleCount = value
                    }
                }
            }
        }

        // 尝试执行小米/澎湃专属电池调试服务
        val miuiBattery = executeCommand("dumpsys miui.battery 2>/dev/null")
        if (miuiBattery.isNotEmpty()) {
            val regexMiuiCycle = Regex("(?i)(?:cycle|cycle_count|mf_02):\\s*(\\d+)")
            val match = regexMiuiCycle.find(miuiBattery)
            if (match != null) {
                val count = match.groupValues[1].toIntOrNull()
                if (count != null && count > 0) {
                    if (cycleCount == null || count > cycleCount) cycleCount = count
                }
            }
        }

        return BatteryInfo(
            designCapacity = designCapacity,
            fullChargeCapacity = fullChargeCapacity,
            currentCapacity = currentCapacity,
            cycleCount = cycleCount
        )
    }

    /**
     * 通过 Shizuku 执行 Shell 命令。
     *
     * @param command 要执行的命令字符串
     * @return 命令执行输出的字符串结果
     */
    private fun executeCommand(command: String): String {
        return try {
            val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
            val newProcessMethod = shizukuClass.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            newProcessMethod.isAccessible = true
            val process = newProcessMethod.invoke(null, arrayOf("sh", "-c", command), null, null) as Process

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            process.waitFor()
            output.toString()
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }
}
