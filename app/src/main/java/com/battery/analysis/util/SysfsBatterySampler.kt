package com.battery.analysis.util

import android.content.Context
import android.os.BatteryManager
import java.io.File

/**
 * Linux 内核底层电源节点 (sysfs) 硬件直读采样器。
 * 深度对标 BatteryRecorder 架构，支持通过 C/C++ 原生动态链接库 (JNI) 高性能直读
 * /sys/class/power_supply/battery/current_now、voltage_now 与 temp 节点，
 * 节点文件描述符在底层持久缓存，实现微秒级瞬时放电爆发电流与功率捕获，
 * 绕过 Android Framework 的低通平滑滤波，并具备直接 I/O、Shizuku 特权通道及系统 BatteryManager 多级安全回退机制。
 */
object SysfsBatterySampler {

    @Volatile
    private var jniLoaded: Boolean = false

    @Volatile
    private var jniInitialized: Boolean = false

    init {
        try {
            System.loadLibrary("battery_sampler")
            jniLoaded = true
            jniInitialized = (nativeInit() == 1)
        } catch (_: Throwable) {
            jniLoaded = false
            jniInitialized = false
        }
    }

    /**
     * JNI 原生方法：初始化底层节点文件描述符缓存。
     *
     * @return 1 表示成功打开关键节点，0 表示初始化失败
     */
    @JvmStatic
    external fun nativeInit(): Int

    /**
     * JNI 原生方法：从底层节点直接读取当前瞬时电压（微伏 uV 或毫伏 mV）。
     *
     * @return 瞬时电压原始数值
     */
    @JvmStatic
    external fun nativeGetVoltage(): Long

    /**
     * JNI 原生方法：从底层节点直接读取当前瞬时放电电流（微安 uA 或毫安 mA）。
     *
     * @return 瞬时放电电流原始数值
     */
    @JvmStatic
    external fun nativeGetCurrent(): Long

    /**
     * JNI 原生方法：从底层节点直接读取当前电池剩余容量百分比。
     *
     * @return 电池容量百分比数值 (0-100)
     */
    @JvmStatic
    external fun nativeGetCapacity(): Int

    /**
     * JNI 原生方法：从底层节点直接读取当前电池充放电状态 ASCII 字符。
     *
     * @return 状态字符 ASCII 码，若不可用返回 0
     */
    @JvmStatic
    external fun nativeGetStatus(): Int

    /**
     * JNI 原生方法：从底层节点直接读取当前瞬时电池温度。
     *
     * @return 电池温度原始数值（通常为十分之一摄氏度）
     */
    @JvmStatic
    external fun nativeGetTemp(): Int

    private val CURRENT_PATHS = listOf(
        "/sys/class/power_supply/battery/current_now",
        "/sys/class/power_supply/bms/current_now",
        "/sys/class/power_supply/battery_gauge/current_now",
        "/sys/class/power_supply/qcom-battery/current_now"
    )

    private val VOLTAGE_PATHS = listOf(
        "/sys/class/power_supply/battery/voltage_now",
        "/sys/class/power_supply/bms/voltage_now",
        "/sys/class/power_supply/battery_gauge/voltage_now",
        "/sys/class/power_supply/qcom-battery/voltage_now"
    )

    private val TEMP_PATHS = listOf(
        "/sys/class/power_supply/battery/temp",
        "/sys/class/power_supply/bms/temp",
        "/sys/class/power_supply/battery_gauge/temp",
        "/sys/class/power_supply/qcom-battery/temp"
    )

    /**
     * 瞬时硬件采样物理指标实体。
     *
     * @property currentMa 瞬时电流（毫安 mA，放电为正值）
     * @property voltageVolts 瞬时电压（伏特 V）
     * @property powerWatts 瞬时物理功率（瓦特 W）
     * @property temperatureCelsius 瞬时电池温度（摄氏度 ℃）
     */
    data class HardwareSample(
        val currentMa: Float,
        val voltageVolts: Float,
        val powerWatts: Float,
        val temperatureCelsius: Float? = null
    )

    /**
     * 从 Linux 底层节点直接采样当前瞬时放电硬件物理指标。
     * 依次尝试 JNI 原生缓存直读、直接文件流读取、Shizuku 特权读取；
     * 若均受权限限制，自动平滑回退至系统 [BatteryManager] 读取。
     *
     * @param context 应用程序上下文
     * @param fallbackVoltageVolts 系统粘性广播提供的备用电压（伏特 V）
     * @param fallbackTempCelsius 系统粘性广播提供的备用温度（摄氏度 ℃）
     * @return 包含电流、电压、功率与温度的硬件采样实体 [HardwareSample]，若无法获取则返回 null
     */
    fun sampleHardwareDischarge(
        context: Context,
        fallbackVoltageVolts: Float = 3.85f,
        fallbackTempCelsius: Float? = null
    ): HardwareSample? {
        val sysfsCurrent = readSysfsCurrentMa()
        val sysfsVoltage = readSysfsVoltageVolts()
        val sysfsTemp = readSysfsTemperature()

        val finalVoltage = if (sysfsVoltage != null && sysfsVoltage in 2.5f..15.0f) {
            sysfsVoltage
        } else {
            fallbackVoltageVolts
        }

        val finalTemp = sysfsTemp ?: fallbackTempCelsius

        if (sysfsCurrent != null && sysfsCurrent > 0f) {
            val pWatts = (sysfsCurrent * finalVoltage) / 1000f
            return HardwareSample(
                currentMa = sysfsCurrent,
                voltageVolts = finalVoltage,
                powerWatts = (Math.round(pWatts * 100f) / 100f).coerceAtLeast(0f),
                temperatureCelsius = finalTemp
            )
        }

        // 回退至系统 BatteryManager 读取
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
            val rawCur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            if (rawCur != 0 && rawCur != Int.MIN_VALUE) {
                val curMa = BatteryUnitNormalizer.normalizeCurrentMa(rawCur.toLong(), isCharging = false)
                if (curMa > 0f && finalVoltage > 0f) {
                    val pWatts = (curMa * finalVoltage) / 1000f
                    HardwareSample(
                        currentMa = curMa,
                        voltageVolts = finalVoltage,
                        powerWatts = (Math.round(pWatts * 100f) / 100f).coerceAtLeast(0f),
                        temperatureCelsius = finalTemp
                    )
                } else {
                    null
                }
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 直接从 Linux 内核 sysfs 节点读取瞬时电流，并统一归一化为绝对值毫安 (mA)。
     * 优先通过 JNI 极速原生直读；其次尝试直接文件读取；最后通过 Shizuku Shell 通道直读。
     *
     * @return 瞬时电流（毫安 mA），若无可用节点或无权限读取则返回 null
     */
    fun readSysfsCurrentMa(): Float? {
        // 1. 优先通过 JNI 原生通道直读（微秒级）
        if (jniLoaded) {
            try {
                if (!jniInitialized) {
                    jniInitialized = (nativeInit() == 1)
                }
                val raw = nativeGetCurrent()
                if (raw != 0L) {
                    val absVal = Math.abs(raw)
                    return if (absVal >= 10_000L) {
                        absVal / 1000f // 微安 uA -> 毫安 mA
                    } else {
                        absVal.toFloat() // 毫安 mA
                    }
                }
            } catch (_: Throwable) {}
        }

        // 2. 直接文件读取（不依赖 canRead()，直接尝试打开避免 SELinux 误判）
        for (candidate in CURRENT_PATHS) {
            try {
                val file = File(candidate)
                if (file.exists()) {
                    val text = file.readText().trim()
                    val raw = text.toLongOrNull()
                    if (raw != null && raw != 0L) {
                        val absVal = Math.abs(raw)
                        return if (absVal >= 10_000L) absVal / 1000f else absVal.toFloat()
                    }
                }
            } catch (_: Throwable) {}
        }

        // 3. 尝试通过 Shizuku 特权通道直读内核节点
        for (candidate in CURRENT_PATHS) {
            val text = readSysfsViaShizuku(candidate)
            val raw = text?.toLongOrNull()
            if (raw != null && raw != 0L) {
                val absVal = Math.abs(raw)
                return if (absVal >= 10_000L) absVal / 1000f else absVal.toFloat()
            }
        }
        return null
    }

    /**
     * 直接从 Linux 内核 sysfs 节点读取瞬时电压，并统一归一化为伏特 (V)。
     * 优先通过 JNI 极速原生直读；其次尝试直接文件读取；最后通过 Shizuku Shell 通道直读。
     *
     * @return 瞬时电压（伏特 V），若无可用节点或无权限读取则返回 null
     */
    fun readSysfsVoltageVolts(): Float? {
        // 1. 优先通过 JNI 原生通道直读
        if (jniLoaded) {
            try {
                if (!jniInitialized) {
                    jniInitialized = (nativeInit() == 1)
                }
                val raw = nativeGetVoltage()
                if (raw > 0L) {
                    return if (raw >= 100_000L) {
                        raw / 1_000_000f // 微伏 uV -> 伏特 V
                    } else if (raw >= 1000L) {
                        raw / 1000f // 毫伏 mV -> 伏特 V
                    } else {
                        raw.toFloat()
                    }
                }
            } catch (_: Throwable) {}
        }

        // 2. 直接文件读取
        for (candidate in VOLTAGE_PATHS) {
            try {
                val file = File(candidate)
                if (file.exists()) {
                    val text = file.readText().trim()
                    val raw = text.toLongOrNull()
                    if (raw != null && raw > 0L) {
                        return if (raw >= 100_000L) {
                            raw / 1_000_000f
                        } else if (raw >= 1000L) {
                            raw / 1000f
                        } else {
                            raw.toFloat()
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        // 3. 尝试通过 Shizuku 特权通道直读内核节点
        for (candidate in VOLTAGE_PATHS) {
            val text = readSysfsViaShizuku(candidate)
            val raw = text?.toLongOrNull()
            if (raw != null && raw > 0L) {
                return if (raw >= 100_000L) {
                    raw / 1_000_000f
                } else if (raw >= 1000L) {
                    raw / 1000f
                } else {
                    raw.toFloat()
                }
            }
        }
        return null
    }

    /**
     * 通过 Shizuku Shell 通道读取指定节点的文本内容。
     *
     * @param nodePath 目标 sysfs 文件绝对路径
     * @return 节点内容文本，若不可用则返回 null
     */
    private fun readSysfsViaShizuku(nodePath: String): String? {
        return try {
            val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
            val pingMethod = shizukuClass.getMethod("pingBinder")
            if (pingMethod.invoke(null) == true) {
                val checkPermMethod = shizukuClass.getMethod("checkSelfPermission")
                val permResult = checkPermMethod.invoke(null) as? Int ?: -1
                if (permResult == 0) {
                    val newProcMethod = shizukuClass.getDeclaredMethod(
                        "newProcess",
                        Array<String>::class.java,
                        Array<String>::class.java,
                        String::class.java
                    ).apply { isAccessible = true }
                    val proc = newProcMethod.invoke(null, arrayOf("cat", nodePath), null, null) as? Process
                    val text = proc?.inputStream?.bufferedReader()?.use { it.readText().trim() }
                    proc?.waitFor()
                    if (!text.isNullOrEmpty() && !text.contains("No such") && !text.contains("Permission denied")) {
                        return text
                    }
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 直接从 Linux 内核 sysfs 节点读取瞬时电池温度（摄氏度 ℃）。
     * 优先通过 JNI 极速原生直读；其次尝试直接文件读取。
     *
     * @return 瞬时温度（摄氏度 ℃），若无可用节点或无权限读取则返回 null
     */
    fun readSysfsTemperature(): Float? {
        // 1. 优先通过 JNI 原生通道直读
        if (jniLoaded) {
            try {
                if (!jniInitialized) {
                    jniInitialized = (nativeInit() == 1)
                }
                val raw = nativeGetTemp()
                if (raw > 0) {
                    return if (raw >= 100) raw / 10f else raw.toFloat()
                }
            } catch (_: Throwable) {}
        }

        // 2. 直接文件读取
        for (candidate in TEMP_PATHS) {
            try {
                val file = File(candidate)
                if (file.exists()) {
                    val text = file.readText().trim()
                    val raw = text.toFloatOrNull()
                    if (raw != null && raw > 0f) {
                        return if (raw >= 100f) raw / 10f else raw
                    }
                }
            } catch (_: Throwable) {}
        }
        return null
    }
}
