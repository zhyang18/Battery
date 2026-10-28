package com.battery.analysis.manager

import android.content.Context
import android.os.BatteryManager
import android.os.SystemClock
import com.battery.analysis.provider.NormalApiProvider
import java.io.File
import java.util.UUID

/**
 * 硬件电量数据源能力描述实体类。
 *
 * @property bootSessionId 本次开机周期的唯一标识
 * @property isChargeCounterSupported 硬件层是否支持电荷计数器属性
 * @property chargeCounterRawUnit 探测到的计数器典型单位描述（如 "uAh" 或 "mAh"）
 * @property isCurrentNowSupported 硬件层是否支持瞬时净电流属性
 * @property hasSysfsDirectAccess 是否具备直接读取底层 sysfs 驱动节点的权限
 * @property hardwareFccMah 底层硬件电量计原生上报的真实充满容量（若支持则不为 null）
 * @property designCapacityMah 系统 PowerProfile 出厂设计标称容量（若获取成功则不为 null）
 */
data class BatterySourceCapability(
    val bootSessionId: String,
    val isChargeCounterSupported: Boolean,
    val chargeCounterRawUnit: String,
    val isCurrentNowSupported: Boolean,
    val hasSysfsDirectAccess: Boolean,
    val hardwareFccMah: Float?,
    val designCapacityMah: Float?
)

/**
 * 硬件电池数据源与驱动语义能力探测验证器（BatterySourceProbe）。
 *
 * 负责在运行时对当前具体机型进行底层电源节点与系统接口能力的只读探测：
 * 1. 建立开机唯一标识 bootSessionId，严防设备重启导致单调时钟归零引发的时差错乱；
 * 2. 探测 BatteryManager 属性的真实可用性与数值范围；
 * 3. 探测出厂设计容量与内核底层电量计寄存器；
 * 4. 严格恪守只读探测与零假数据原则，绝不强行伪造不存在的硬件节点。
 */
object BatterySourceProbe {

    /**
     * 当前开机周期的唯一标识符，基于开机系统锚点与 UUID 动态生成。
     */
    val currentBootSessionId: String by lazy {
        val bootAnchorTime = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        "boot_${bootAnchorTime}_${UUID.randomUUID().toString().substring(0, 8)}"
    }

    @Volatile
    private var cachedCapability: BatterySourceCapability? = null

    /**
     * 探测并获取当前运行设备的电池数据源能力报告。
     * 内部具备轻量内存缓存，避免频繁跨进程访问系统服务。
     *
     * @param context 应用程序上下文
     * @return 电池数据源能力实体 [BatterySourceCapability]
     */
    fun probeCapability(context: Context): BatterySourceCapability {
        cachedCapability?.let { return it }

        synchronized(this) {
            cachedCapability?.let { return it }

            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

            // 1. 探测电荷计数器 (CHARGE_COUNTER)
            val rawCounter = try {
                bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: Int.MIN_VALUE
            } catch (e: Exception) {
                Int.MIN_VALUE
            }
            val hasCounter = rawCounter > 0 && rawCounter != Int.MIN_VALUE
            val counterUnit = if (hasCounter) {
                if (rawCounter > 100_000) "uAh" else "mAh"
            } else {
                "UNKNOWN"
            }

            // 2. 探测瞬时净电流 (CURRENT_NOW)
            val rawCurrent = try {
                bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
            } catch (e: Exception) {
                0
            }
            val hasCurrent = rawCurrent != 0 && rawCurrent != Int.MIN_VALUE

            // 3. 探测底层 sysfs 直接可读性
            val sysfsFile = File("/sys/class/power_supply/battery/uevent")
            val hasSysfs = sysfsFile.exists() && sysfsFile.canRead()

            // 4. 获取出厂设计容量
            val designCap = NormalApiProvider.getDesignCapacity(context)

            // 5. 尝试只读探测底层硬件充满容量 charge_full
            val hwFcc = probeHardwareChargeFull()

            val capability = BatterySourceCapability(
                bootSessionId = currentBootSessionId,
                isChargeCounterSupported = hasCounter,
                chargeCounterRawUnit = counterUnit,
                isCurrentNowSupported = hasCurrent,
                hasSysfsDirectAccess = hasSysfs,
                hardwareFccMah = hwFcc,
                designCapacityMah = designCap
            )

            cachedCapability = capability
            return capability
        }
    }

    /**
     * 尝试从 Linux 内核只读节点探测硬件真实的满充容量 charge_full。
     *
     * @return 硬件真实满充容量（mAh），若不可读或无权限则如实返回 null
     */
    private fun probeHardwareChargeFull(): Float? {
        val candidatePaths = listOf(
            "/sys/class/power_supply/battery/charge_full",
            "/sys/class/power_supply/bms/charge_full"
        )
        for (path in candidatePaths) {
            try {
                val file = File(path)
                if (file.exists() && file.canRead()) {
                    val text = file.readText().trim()
                    val rawVal = text.toLongOrNull()
                    if (rawVal != null && rawVal > 0) {
                        return if (rawVal > 100_000) (rawVal / 1000f) else rawVal.toFloat()
                    }
                }
            } catch (e: Exception) {
                // 忽略异常，忠实返回 null
            }
        }
        return null
    }
}
