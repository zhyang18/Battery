package com.battery.analysis.provider

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.battery.analysis.model.BatteryInfo
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
        var voltage = sysfsInfo.voltage ?: dumpsysInfo.voltage ?: vendorInfo.voltage
        var currentNow = sysfsInfo.currentNow ?: dumpsysInfo.currentNow ?: vendorInfo.currentNow
        val technology = sysfsInfo.technology ?: dumpsysInfo.technology ?: vendorInfo.technology
        val isDualCell = sysfsInfo.isDualCell ?: dumpsysInfo.isDualCell ?: vendorInfo.isDualCell ?: false

        // 如果底层未直接解析到电流，尝试从 BatteryManager 读取底层瞬时电流
        if (currentNow == null) {
            try {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                val rawCur = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                if (rawCur != null && rawCur != Int.MIN_VALUE && rawCur != 0) {
                    currentNow = if (Math.abs(rawCur) < 100000) rawCur.toFloat() else rawCur / 1000f
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 如果底层未直接解析到电压或电压超出正常范围(2.5V-9.5V)，尝试从系统粘性广播读取当前电压
        if (voltage == null || voltage < 2500f || voltage > 9500f) {
            try {
                val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val rawVolt = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
                if (rawVolt in 2500..9500) {
                    voltage = rawVolt.toFloat()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 实时电池功率计算（支持直接读取功率节点或通过电压与电流计算：P = U * I / 10^6）
        val powerWatts = sysfsInfo.powerWatts ?: if (voltage != null && currentNow != null) {
            Math.abs(voltage * currentNow) / 1000000f
        } else null

        // 计算健康度（支持大于 100% 的精确数值计算）
        var health: Float? = sysfsInfo.batteryHealth ?: dumpsysInfo.batteryHealth ?: vendorInfo.batteryHealth
        if (health == null && fullCap != null && designCap != null && designCap > 0) {
            health = (fullCap / designCap) * 100f
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
     * 将原始电压数值规范化为毫伏（mV）。
     *
     * @param rawVolt 原始电压数值
     * @return 规范化后的毫伏电压，无效则返回 null
     */
    private fun normalizeVoltage(rawVolt: Long): Float? {
        if (rawVolt <= 0) return null
        return when {
            rawVolt in 2500..9500 -> rawVolt.toFloat() // 已经为 mV（单电芯 3.0-4.5V，双电芯串联 6.0-9.0V）
            rawVolt in 25000..95000 -> rawVolt / 10f // 0.1 mV
            rawVolt in 2500000..9500000 -> rawVolt / 1000f // uV 微伏转换为 mV
            rawVolt > 9500000 -> rawVolt / 1000f
            else -> null
        }
    }

    /**
     * 将原始电流数值规范化为毫安（mA）。
     *
     * @param rawCur 原始电流数值
     * @return 规范化后的毫安电流，无效则返回 null
     */
    private fun normalizeCurrent(rawCur: Long): Float? {
        if (rawCur == 0L) return null
        val abs = Math.abs(rawCur)
        return when {
            abs in 1..9999 -> rawCur.toFloat() // 已经为 mA
            abs in 10000..99999 -> rawCur / 10f // 0.1 mA
            abs >= 100000 -> rawCur / 1000f // uA 微安转换为 mA
            else -> rawCur.toFloat()
        }
    }

    /**
     * 从 sysfs 节点读取电池信息。
     * 涵盖系统通用节点、uevent 以及全路径直接遍历节点。
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
        var powerWatts: Float? = null
        var batteryHealth: Float? = null
        var status: String? = null
        var healthStatus: String? = null
        var technology: String? = null
        var isDualCell: Boolean? = null

        // 1. 读取专属电池 power_supply 下的 uevent 文件
        val sysfsCmd = "cat /sys/class/power_supply/battery/uevent /sys/class/power_supply/bms/uevent /sys/class/qcom-battery/uevent /sys/class/power_supply/battery_gauge/uevent 2>/dev/null"
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
                if (rawVolt != null) {
                    val normVolt = normalizeVoltage(rawVolt)
                    if (normVolt != null) {
                        voltage = normVolt
                    }
                }
            }
            if (trimLine.contains("POWER_SUPPLY_CURRENT_NOW=") || trimLine.contains("POWER_SUPPLY_CURRENT_AVG=")) {
                val rawCur = trimLine.substringAfter("=").toLongOrNull()
                if (rawCur != null) {
                    val normCur = normalizeCurrent(rawCur)
                    if (normCur != null && (currentNow == null || currentNow == 0f)) {
                        currentNow = normCur
                    }
                }
            }
            if (trimLine.contains("POWER_SUPPLY_POWER_NOW=")) {
                val rawPwr = trimLine.substringAfter("=").toLongOrNull()
                if (rawPwr != null && rawPwr > 0) {
                    powerWatts = if (rawPwr < 100000) rawPwr / 1000f else rawPwr / 1000000f
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
            if (trimLine.contains("POWER_SUPPLY_SOH=") || trimLine.contains("POWER_SUPPLY_HEALTH_PERCENT=")) {
                val soh = trimLine.substringAfter("=").toFloatOrNull()
                if (soh != null && soh > 0) {
                    if (batteryHealth == null) batteryHealth = soh
                }
            }
            if (trimLine.contains("POWER_SUPPLY_TECHNOLOGY=")) {
                val t = trimLine.substringAfter("=").trim()
                if (t.isNotEmpty()) technology = t
            }
        }

        // 2. 深度扫描 battery/bms 驱动文件节点
        val directNodesCmd = """
            for node in /sys/class/power_supply/battery /sys/class/power_supply/bms /sys/class/qcom-battery /sys/class/power_supply/battery_gauge; do
                if [ -d "${'$'}node" ]; then
                    for f in current_now batt_current battery_current current_avg power_now batt_power voltage_now batt_vol; do
                        if [ -f "${'$'}node/${'$'}f" ]; then
                            echo "${'$'}f=$(cat ${'$'}node/${'$'}f 2>/dev/null)"
                        fi
                    done
                fi
            done
        """.trimIndent()
        val directRes = executeCommand(directNodesCmd)
        for (line in directRes.split('\n')) {
            val trimLine = line.trim()
            if (trimLine.startsWith("current_now=") || trimLine.startsWith("batt_current=") || trimLine.startsWith("battery_current=") || trimLine.startsWith("current_avg=")) {
                val raw = trimLine.substringAfter("=").toLongOrNull()
                if (raw != null) {
                    val normCur = normalizeCurrent(raw)
                    if (normCur != null && (currentNow == null || currentNow == 0f)) {
                        currentNow = normCur
                    }
                }
            }
            if (trimLine.startsWith("voltage_now=") || trimLine.startsWith("batt_vol=")) {
                val raw = trimLine.substringAfter("=").toLongOrNull()
                if (raw != null) {
                    val normVolt = normalizeVoltage(raw)
                    if (normVolt != null && voltage == null) {
                        voltage = normVolt
                    }
                }
            }
            if (trimLine.startsWith("power_now=") || trimLine.startsWith("batt_power=")) {
                val raw = trimLine.substringAfter("=").toLongOrNull()
                if (raw != null && raw > 0) {
                    val w = if (raw < 100000) raw / 1000f else raw / 1000000f
                    if (powerWatts == null) powerWatts = w
                }
            }
        }

        return BatteryInfo(
            batteryHealth = batteryHealth,
            designCapacity = designCapacity,
            fullChargeCapacity = fullChargeCapacity,
            currentCapacity = currentCapacity,
            cycleCount = cycleCount,
            temperature = temperature,
            voltage = voltage,
            currentNow = currentNow,
            powerWatts = powerWatts,
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
        val regexChargeCounter = Regex("(?m)^\\s*Charge counter:\\s*(\\d+)")
        val matchCharge = regexChargeCounter.find(batteryOutput)
        if (matchCharge != null) {
            val raw = matchCharge.groupValues[1].toLongOrNull()
            if (raw != null && raw > 0) {
                currentCapacity = if (raw < 100000) raw.toFloat() else raw / 1000f
            }
        }

        val regexLevel = Regex("(?m)^\\s*level:\\s*(\\d+)")
        val matchLevel = regexLevel.find(batteryOutput)
        if (matchLevel != null) {
            batteryLevel = matchLevel.groupValues[1].toIntOrNull()
        }

        val regexStatus = Regex("(?m)^\\s*status:\\s*(\\d+)")
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

        val regexHealth = Regex("(?m)^\\s*health:\\s*(\\d+)")
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

        val regexTemp = Regex("(?m)^\\s*temperature:\\s*(\\d+)")
        val matchTemp = regexTemp.find(batteryOutput)
        if (matchTemp != null) {
            val rawTemp = matchTemp.groupValues[1].toFloatOrNull()
            if (rawTemp != null && rawTemp > 0) {
                temperature = rawTemp / 10.0f
            }
        }

        val regexVolt = Regex("(?m)^\\s*voltage:\\s*(\\d+)")
        val matchVolt = regexVolt.find(batteryOutput)
        if (matchVolt != null) {
            val raw = matchVolt.groupValues[1].toLongOrNull()
            if (raw != null) {
                voltage = normalizeVoltage(raw)
            }
        }

        val regexTech = Regex("(?m)^\\s*technology:\\s*(\\w+)")
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
