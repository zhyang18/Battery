package com.battery.analysis.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.File

/**
 * Linux 内核底层电源节点 (sysfs) 硬件直读采样器。
 *
 * 深度对标 BatteryRecorder 架构，优先通过 C/C++ JNI 直读
 * /sys/class/power_supply/battery/current_now、voltage_now 等节点，
 * 支持三级降级：JNI → 直接文件读取 → Shizuku Shell 通道。
 * 全面优化：已验证可用的路径会被缓存，Shizuku 可用性每 5 秒重新检查一次，
 * 彻底绕过 Android Framework 低通平滑滤波，实现与 BatteryRecorder 一致的瞬时真实功耗。
 */
object SysfsBatterySampler {

    private const val TAG = "SysfsSampler"

    /** JNI 动态库是否成功加载 */
    @Volatile
    private var jniLoaded: Boolean = false

    /** JNI 底层节点文件描述符缓存是否已完成初始化 */
    @Volatile
    private var jniInitialized: Boolean = false

    /**
     * 已验证可用的电流 sysfs 节点路径缓存。
     * 一旦发现某路径可读，后续直接用该路径，不再遍历所有候选。
     */
    @Volatile
    private var cachedCurrentPath: String? = null

    /**
     * 已验证可用的电压 sysfs 节点路径缓存。
     */
    @Volatile
    private var cachedVoltagePath: String? = null

    /**
     * 已验证可用的温度 sysfs 节点路径缓存。
     */
    @Volatile
    private var cachedTempPath: String? = null

    /**
     * Shizuku 上次可用性检测时间戳（毫秒），避免每次采样都 pingBinder。
     */
    @Volatile
    private var shizukuCheckedAt: Long = 0L

    /**
     * 上次 Shizuku 可用性检测结果缓存。
     */
    @Volatile
    private var shizukuAvailable: Boolean = false

    /** Shizuku 可用性缓存有效期：5 秒 */
    private const val SHIZUKU_CACHE_MS = 5_000L

    /** 所有已知的电流节点候选路径（按常见设备优先级排序） */
    private val CURRENT_PATHS = listOf(
        "/sys/class/power_supply/battery/current_now",
        "/sys/class/power_supply/Battery/current_now",
        "/sys/class/power_supply/bms/current_now",
        "/sys/class/power_supply/battery_gauge/current_now",
        "/sys/class/power_supply/bq27z561-0/current_now",
        "/sys/class/power_supply/sc8545-standalone/current_now",
        "/sys/class/power_supply/qcom-battery/current_now",
        "/sys/class/power_supply/battery/current_avg",
        "/sys/class/power_supply/Battery/current_avg"
    )

    /** 所有已知的电压节点候选路径 */
    private val VOLTAGE_PATHS = listOf(
        "/sys/class/power_supply/battery/voltage_now",
        "/sys/class/power_supply/Battery/voltage_now",
        "/sys/class/power_supply/bms/voltage_now",
        "/sys/class/power_supply/battery_gauge/voltage_now",
        "/sys/class/power_supply/bq27z561-0/voltage_now",
        "/sys/class/power_supply/sc8545-standalone/voltage_now",
        "/sys/class/power_supply/qcom-battery/voltage_now"
    )

    /** 所有已知的温度节点候选路径 */
    private val TEMP_PATHS = listOf(
        "/sys/class/power_supply/battery/temp",
        "/sys/class/power_supply/Battery/temp",
        "/sys/class/power_supply/bms/temp",
        "/sys/class/power_supply/battery_gauge/temp",
        "/sys/class/power_supply/bq27z561-0/temp",
        "/sys/class/power_supply/sc8545-standalone/temp",
        "/sys/class/power_supply/qcom-battery/temp"
    )

    /** 所有已知的状态节点候选路径 */
    private val STATUS_PATHS = listOf(
        "/sys/class/power_supply/battery/status",
        "/sys/class/power_supply/Battery/status",
        "/sys/class/power_supply/bms/status",
        "/sys/class/power_supply/battery_gauge/status",
        "/sys/class/power_supply/qcom-battery/status"
    )

    /**
     * 已验证可用的状态 sysfs 节点路径缓存。
     */
    @Volatile
    private var cachedStatusPath: String? = null

    init {
        try {
            System.loadLibrary("battery_sampler")
            jniLoaded = true
            jniInitialized = (nativeInit() == 1)
            Log.i(TAG, "JNI 加载成功，nativeInit=${if (jniInitialized) "节点已就绪" else "节点不可访问（将降级）"}")
        } catch (e: Throwable) {
            jniLoaded = false
            jniInitialized = false
            Log.w(TAG, "JNI 加载失败（将降级至文件/Shizuku 通道）: ${e.message}")
        }
    }

    /**
     * JNI 原生方法：初始化底层节点文件描述符缓存。
     *
     * @return 1 表示成功打开至少一个关键节点，0 表示初始化失败
     */
    @JvmStatic
    external fun nativeInit(): Int

    /**
     * JNI 原生方法：从底层节点直接读取当前瞬时电压（微伏 uV 或毫伏 mV）。
     *
     * @return 瞬时电压原始数值，0 表示不可用
     */
    @JvmStatic
    external fun nativeGetVoltage(): Long

    /**
     * JNI 原生方法：从底层节点直接读取当前瞬时放电电流（微安 uA 或毫安 mA）。
     *
     * @return 瞬时放电电流原始数值（带符号），0 表示不可用
     */
    @JvmStatic
    external fun nativeGetCurrent(): Long

    /**
     * JNI 原生方法：从底层节点直接读取当前电池剩余容量百分比。
     *
     * @return 电池容量百分比 (0-100)，0 表示不可用
     */
    @JvmStatic
    external fun nativeGetCapacity(): Int

    /**
     * JNI 原生方法：从底层节点直接读取当前电池充放电状态 ASCII 字符。
     *
     * @return 状态字符 ASCII 码，0 表示不可用
     */
    @JvmStatic
    external fun nativeGetStatus(): Int

    /**
     * JNI 原生方法：从底层节点直接读取当前瞬时电池温度（通常为十分之一摄氏度）。
     *
     * @return 电池温度原始数值，0 表示不可用
     */
    @JvmStatic
    external fun nativeGetTemp(): Int

    /**
     * 瞬时硬件采样物理指标数据类。
     *
     * @property currentMa 瞬时电流（毫安 mA，充电正向，净放电为负值）
     * @property voltageVolts 瞬时电压（伏特 V）
     * @property powerWatts 瞬时物理功率（瓦特 W，充电正向，净放电为负值）
     * @property temperatureCelsius 瞬时电池温度（摄氏度 ℃），可为 null
     */
    data class HardwareSample(
        val currentMa: Float,
        val voltageVolts: Float,
        val powerWatts: Float,
        val temperatureCelsius: Float? = null
    )

    /**
     * 从 Linux 底层节点直接采样当前瞬时硬件物理指标（通用方法，支持充电与放电场景）。
     *
     * 依次尝试：JNI 原生缓存直读 → 直接文件读取 → Shizuku Shell 特权通道；
     * 若均受权限限制，自动回退至系统 [BatteryManager] 软件滤波值（最终兜底）。
     *
     * 充电场景下：
     * 若硬件底层识别为净放电（例如 `status` 节点为 Discharging / Not charging，或电流上报为负值），
     * 保持真实的物理方向，返回负向功率（如 -12.3W）与负向电流，供图表在 0W 基准线下方绘制，
     * 杜绝将重载/弱充时的放电尖峰错误统计为正向充电峰值功率。
     *
     * @param context 应用程序上下文（用于 BatteryManager 回退）
     * @param isCharging 是否处于充电连接状态
     * @param fallbackVoltageVolts 广播提供的备用电压（伏特 V）
     * @param fallbackTempCelsius 广播提供的备用温度（摄氏度 ℃）
     * @return 包含电流、电压、功率与温度的硬件采样实体，若无法获取则返回 null
     */
    fun sampleHardwareBattery(
        context: Context,
        isCharging: Boolean,
        fallbackVoltageVolts: Float? = null,
        fallbackTempCelsius: Float? = null
    ): HardwareSample? {
        // 1. 优先尝试 JNI 原生缓存直读（微秒级）
        if (jniLoaded) {
            try {
                if (!jniInitialized) {
                    jniInitialized = (nativeInit() == 1)
                }
                val rawCur = nativeGetCurrent()
                val rawVolt = nativeGetVoltage()
                val rawTemp = nativeGetTemp()
                val rawStatus = nativeGetStatus()
                if (rawCur != 0L && rawVolt > 0L) {
                    val curMa = normalizeCurrentToMa(Math.abs(rawCur))
                    val voltV = normalizeVoltageToVolts(rawVolt)
                    val tempC = if (rawTemp != 0) (if (rawTemp >= 100 || rawTemp <= -100) rawTemp / 10f else rawTemp.toFloat()) else fallbackTempCelsius
                    val pWatts = (curMa * voltV) / 1000f
                    val isDischargingStatus = rawStatus == 'D'.code || rawStatus == 'd'.code || rawStatus == 'N'.code || rawStatus == 'n'.code
                    val isNetDischarging = isDischargingStatus || (rawCur < 0L)
                    val signedPower = if (isCharging) {
                        if (isNetDischarging) -pWatts else pWatts
                    } else {
                        pWatts.coerceAtLeast(0f)
                    }
                    val signedCur = if (isCharging) {
                        if (isNetDischarging) -curMa else curMa
                    } else {
                        curMa
                    }
                    return HardwareSample(
                        currentMa = signedCur,
                        voltageVolts = voltV,
                        powerWatts = Math.round(signedPower * 1000f) / 1000f,
                        temperatureCelsius = tempC
                    )
                }
            } catch (_: Throwable) {}
        }

        // 2. 尝试通过 Shizuku 单次进程批量读取全部节点（避免多次 fork 进程耗时）
        if (isShizukuAvailable()) {
            val batchSample = readHardwareBatchViaShizuku(fallbackVoltageVolts, fallbackTempCelsius, isCharging)
            if (batchSample != null && Math.abs(batchSample.currentMa) > 0f) {
                return batchSample
            }
        }

        // 3. 回退单独节点读取（直接文件流 / 缓存节点）
        val sysfsCurrent = readSysfsCurrentMa()
        val sysfsVoltage = readSysfsVoltageVolts()
        val sysfsTemp = readSysfsTemperature()
        val sysfsStatus = readSysfsStatus()

        val finalVoltage = sysfsVoltage ?: fallbackVoltageVolts ?: 0f
        val finalTemp = sysfsTemp ?: fallbackTempCelsius

        if (sysfsCurrent != null && Math.abs(sysfsCurrent) > 0f) {
            val pWatts = (Math.abs(sysfsCurrent) * finalVoltage) / 1000f
            val isDischargingStatus = sysfsStatus != null && (sysfsStatus.startsWith("D", ignoreCase = true) || sysfsStatus.startsWith("N", ignoreCase = true))
            val isNetDischarging = isDischargingStatus || (sysfsCurrent < 0f)
            val signedPower = if (isCharging) {
                if (isNetDischarging) -pWatts else pWatts
            } else {
                pWatts.coerceAtLeast(0f)
            }
            val signedCur = if (isCharging) {
                if (isNetDischarging) -Math.abs(sysfsCurrent) else Math.abs(sysfsCurrent)
            } else {
                Math.abs(sysfsCurrent)
            }
            return HardwareSample(
                currentMa = signedCur,
                voltageVolts = finalVoltage,
                powerWatts = Math.round(signedPower * 1000f) / 1000f,
                temperatureCelsius = finalTemp
            )
        }

        // ===== 4. 终极回退：BatteryManager API（Android Framework 软件滤波兜底） =====
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                ?: return null
            val rawCur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            if (rawCur != 0 && rawCur != Int.MIN_VALUE) {
                val curMa = BatteryUnitNormalizer.normalizeCurrentMa(rawCur.toLong(), isCharging = isCharging)
                if (curMa > 0f && finalVoltage > 0f) {
                    val pWatts = BatteryUnitNormalizer.calculatePowerWatts(finalVoltage, curMa, isCharging = isCharging)
                    val isNetDischarging = rawCur < 0
                    val signedPower = if (isCharging) {
                        if (isNetDischarging) -pWatts else pWatts
                    } else {
                        pWatts.coerceAtLeast(0f)
                    }
                    val signedCur = if (isCharging) {
                        if (isNetDischarging) -curMa else curMa
                    } else {
                        curMa
                    }
                    HardwareSample(
                        currentMa = signedCur,
                        voltageVolts = finalVoltage,
                        powerWatts = Math.round(signedPower * 1000f) / 1000f,
                        temperatureCelsius = finalTemp
                    )
                } else null
            } else null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 从 Linux 底层节点直接采样当前瞬时放电硬件物理指标。
     *
     * @param context 应用程序上下文
     * @param fallbackVoltageVolts 广播提供的备用电压（伏特 V）
     * @param fallbackTempCelsius 广播提供的备用温度（摄氏度 ℃）
     * @return 包含放电电流、电压、功率与温度的硬件采样实体，若无法获取则返回 null
     */
    fun sampleHardwareDischarge(
        context: Context,
        fallbackVoltageVolts: Float? = null,
        fallbackTempCelsius: Float? = null
    ): HardwareSample? {
        return sampleHardwareBattery(context, isCharging = false, fallbackVoltageVolts, fallbackTempCelsius)
    }

    /**
     * 从 Linux 底层节点直接采样当前瞬时充电硬件物理指标（深度对标 BatteryRecorder JNI 直读架构）。
     *
     * @param context 应用程序上下文
     * @param fallbackVoltageVolts 广播提供的备用电压（伏特 V）
     * @param fallbackTempCelsius 广播提供的备用温度（摄氏度 ℃）
     * @return 包含充电电流、电压、功率与温度的硬件采样实体，若无法获取则返回 null
     */
    fun sampleHardwareCharging(
        context: Context,
        fallbackVoltageVolts: Float? = null,
        fallbackTempCelsius: Float? = null
    ): HardwareSample? {
        return sampleHardwareBattery(context, isCharging = true, fallbackVoltageVolts, fallbackTempCelsius)
    }

    /**
     * 从 Linux 内核 sysfs 节点读取瞬时电流，归一化为绝对值毫安 (mA)。
     *
     * 优先级：JNI 原生直读 → 缓存路径文件读取 → 全量路径扫描 → Shizuku Shell 通道。
     * 已成功读取的路径会被缓存，后续无需重新遍历所有候选。
     *
     * @return 瞬时电流绝对值（毫安 mA），若所有通道均无权限则返回 null
     */
    fun readSysfsCurrentMa(): Float? {
        // ── 1. JNI 原生通道（最快，微秒级；SELinux 允许时直接返回） ──
        if (jniLoaded) {
            try {
                if (!jniInitialized) {
                    jniInitialized = (nativeInit() == 1)
                }
                val raw = nativeGetCurrent()
                if (raw != 0L) {
                    return normalizeCurrentToMa(Math.abs(raw))
                }
            } catch (_: Throwable) {}
        }

        // ── 2. 缓存路径直接文件读取（跳过 exists() 检查，避免 sysfs 虚拟文件 stat 误判） ──
        cachedCurrentPath?.let { path ->
            val result = tryReadCurrentFile(path)
            if (result != null) return result
            // 缓存路径失效，清除后重新探测
            cachedCurrentPath = null
        }

        // ── 3. 全量候选路径直接文件读取（不调用 exists()） ──
        for (path in CURRENT_PATHS) {
            val result = tryReadCurrentFile(path)
            if (result != null) {
                Log.d(TAG, "电流节点直接读取成功：$path")
                cachedCurrentPath = path
                return result
            }
        }

        // ── 4. Shizuku Shell 通道（Shell UID 可绕过普通 App SELinux 限制） ──
        if (isShizukuAvailable()) {
            val candidates = if (cachedCurrentPath != null) listOf(cachedCurrentPath!!) else CURRENT_PATHS
            for (path in candidates) {
                val text = readViaShizuku(path) ?: continue
                val raw = text.toLongOrNull() ?: continue
                if (raw != 0L) {
                    Log.d(TAG, "电流节点 Shizuku 读取成功：$path")
                    cachedCurrentPath = path
                    return normalizeCurrentToMa(Math.abs(raw))
                }
            }
        }

        return null
    }

    /**
     * 从 Linux 内核 sysfs 节点读取瞬时电压，归一化为伏特 (V)。
     *
     * 优先级：JNI 原生直读 → 缓存路径文件读取 → 全量路径扫描 → Shizuku Shell 通道。
     *
     * @return 瞬时电压（伏特 V），若所有通道均无权限则返回 null
     */
    fun readSysfsVoltageVolts(): Float? {
        // ── 1. JNI 原生通道 ──
        if (jniLoaded) {
            try {
                if (!jniInitialized) {
                    jniInitialized = (nativeInit() == 1)
                }
                val raw = nativeGetVoltage()
                if (raw > 0L) {
                    return normalizeVoltageToVolts(raw)
                }
            } catch (_: Throwable) {}
        }

        // ── 2. 缓存路径直接文件读取 ──
        cachedVoltagePath?.let { path ->
            val result = tryReadVoltageFile(path)
            if (result != null) return result
            cachedVoltagePath = null
        }

        // ── 3. 全量候选路径扫描 ──
        for (path in VOLTAGE_PATHS) {
            val result = tryReadVoltageFile(path)
            if (result != null) {
                Log.d(TAG, "电压节点直接读取成功：$path")
                cachedVoltagePath = path
                return result
            }
        }

        // ── 4. Shizuku Shell 通道 ──
        if (isShizukuAvailable()) {
            val candidates = if (cachedVoltagePath != null) listOf(cachedVoltagePath!!) else VOLTAGE_PATHS
            for (path in candidates) {
                val text = readViaShizuku(path) ?: continue
                val raw = text.toLongOrNull() ?: continue
                if (raw > 0L) {
                    Log.d(TAG, "电压节点 Shizuku 读取成功：$path")
                    cachedVoltagePath = path
                    return normalizeVoltageToVolts(raw)
                }
            }
        }

        return null
    }

    /**
     * 从 Linux 内核 sysfs 节点读取瞬时电池温度（摄氏度 ℃）。
     *
     * 优先级：JNI 原生直读 → 缓存路径文件读取 → 全量路径扫描。
     *
     * @return 瞬时温度（摄氏度 ℃），若无可用节点则返回 null
     */
    fun readSysfsTemperature(): Float? {
        // ── 1. JNI 原生通道 ──
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

        // ── 2. 缓存路径直接文件读取 ──
        cachedTempPath?.let { path ->
            val result = tryReadTempFile(path)
            if (result != null) return result
            cachedTempPath = null
        }

        // ── 3. 全量候选路径扫描 ──
        for (path in TEMP_PATHS) {
            val result = tryReadTempFile(path)
            if (result != null) {
                cachedTempPath = path
                return result
            }
        }

        return null
    }

    /**
     * 从 Linux 内核 sysfs 节点读取电池状态字符串（例如 "Charging", "Discharging", "Not charging", "Full"）。
     *
     * 优先级：JNI 原生直读 → 缓存路径文件读取 → 全量候选路径扫描 → Shizuku 通道。
     *
     * @return 电池状态文本，若无可用节点则返回 null
     */
    fun readSysfsStatus(): String? {
        // ── 1. JNI 原生通道 ──
        if (jniLoaded) {
            try {
                if (!jniInitialized) {
                    jniInitialized = (nativeInit() == 1)
                }
                val rawChar = nativeGetStatus()
                if (rawChar != 0) {
                    return when (rawChar.toChar().uppercaseChar()) {
                        'C' -> "Charging"
                        'D' -> "Discharging"
                        'N' -> "Not charging"
                        'F' -> "Full"
                        else -> rawChar.toChar().toString()
                    }
                }
            } catch (_: Throwable) {}
        }

        // ── 2. 缓存路径直接文件读取 ──
        cachedStatusPath?.let { path ->
            val result = tryReadStatusFile(path)
            if (result != null) return result
            cachedStatusPath = null
        }

        // ── 3. 全量候选路径扫描 ──
        for (path in STATUS_PATHS) {
            val result = tryReadStatusFile(path)
            if (result != null) {
                cachedStatusPath = path
                return result
            }
        }

        // ── 4. Shizuku 通道 ──
        if (isShizukuAvailable()) {
            val candidates = if (cachedStatusPath != null) listOf(cachedStatusPath!!) else STATUS_PATHS
            for (path in candidates) {
                val text = readViaShizuku(path) ?: continue
                if (text.isNotEmpty()) {
                    cachedStatusPath = path
                    return text
                }
            }
        }

        return null
    }

    // ──────────────────────────── 私有辅助方法 ────────────────────────────

    /**
     * 直接尝试从指定路径读取状态文本原始值（不调用 exists()）。
     *
     * @param path sysfs 节点文件绝对路径
     * @return 状态文本，若读取失败则返回 null
     */
    private fun tryReadStatusFile(path: String): String? {
        return try {
            val raw = File(path).readText().trim()
            if (raw.isNotEmpty()) raw else null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 直接尝试从指定路径读取电流原始值（不调用 exists()）。
     *
     * @param path sysfs 节点文件绝对路径
     * @return 归一化后的毫安值，若读取失败则返回 null
     */
    private fun tryReadCurrentFile(path: String): Float? {
        return try {
            val raw = File(path).readText().trim().toLongOrNull() ?: return null
            if (raw == 0L) return null
            normalizeCurrentToMa(Math.abs(raw))
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 直接尝试从指定路径读取电压原始值（不调用 exists()）。
     *
     * @param path sysfs 节点文件绝对路径
     * @return 归一化后的伏特值，若读取失败则返回 null
     */
    private fun tryReadVoltageFile(path: String): Float? {
        return try {
            val raw = File(path).readText().trim().toLongOrNull() ?: return null
            if (raw <= 0L) return null
            normalizeVoltageToVolts(raw)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 直接尝试从指定路径读取温度原始值（不调用 exists()）。
     *
     * @param path sysfs 节点文件绝对路径
     * @return 摄氏度温度值，若读取失败则返回 null
     */
    private fun tryReadTempFile(path: String): Float? {
        return try {
            val raw = File(path).readText().trim().toFloatOrNull() ?: return null
            if (raw <= 0f) return null
            if (raw >= 100f) raw / 10f else raw
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 将原始电流绝对值（uA 或 mA）归一化为毫安（mA）。
     * 约定：绝对值 >= 10000 视为微安 (uA)，除以 1000；否则视为毫安 (mA)。
     *
     * @param absVal 电流绝对值
     * @return 毫安值
     */
    private fun normalizeCurrentToMa(absVal: Long): Float {
        return if (absVal >= 10_000L) absVal / 1000f else absVal.toFloat()
    }

    /**
     * 将原始电压值（uV 或 mV）归一化为伏特（V）。
     *
     * @param raw 电压原始值
     * @return 伏特值
     */
    private fun normalizeVoltageToVolts(raw: Long): Float {
        return when {
            raw >= 100_000L -> raw / 1_000_000f  // uV → V
            raw >= 1_000L   -> raw / 1000f        // mV → V
            else            -> raw.toFloat()
        }
    }

    /**
     * 检测 Shizuku 是否可用且已授权。
     * 结果缓存 [SHIZUKU_CACHE_MS] 毫秒，避免每次采样都发起 pingBinder IPC。
     *
     * @return true 表示 Shizuku 已就绪并已授权，可通过 Shell 通道读取 sysfs
     */
    private fun isShizukuAvailable(): Boolean {
        val now = System.currentTimeMillis()
        if (now - shizukuCheckedAt < SHIZUKU_CACHE_MS) return shizukuAvailable
        shizukuCheckedAt = now
        shizukuAvailable = try {
            Shizuku.pingBinder() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
        if (shizukuAvailable) {
            Log.i(TAG, "Shizuku 可用，将通过 Shell UID 通道直读 sysfs（绕过 Framework 滤波）")
        }
        return shizukuAvailable
    }

    /**
     * 通过 Shizuku Shell 通道读取指定 sysfs 节点内容。
     * Shell UID 可读取普通 App 因 SELinux 无法访问的内核 sysfs 节点。
     *
     * 注意：Shizuku 13.x 中 newProcess 为 package-private，必须通过反射调用。
     * pingBinder / checkSelfPermission 为公开 API，可直接调用。
     *
     * @param path 目标 sysfs 文件绝对路径
     * @return 节点文本内容，若不可用则返回 null
     */
    private fun readViaShizuku(path: String): String? {
        return try {
            // Shizuku 13.x 中 newProcess 是 package-private，需要反射访问
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }
            val proc = method.invoke(null, arrayOf("cat", path), null, null) as? Process
                ?: return null
            val text = proc.inputStream.bufferedReader().use { it.readText().trim() }
            proc.waitFor()
            if (text.isNotEmpty() &&
                !text.contains("No such") &&
                !text.contains("Permission denied") &&
                !text.contains("Operation not permitted")
            ) text else null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 通过 Shizuku 单次进程并发读取电流、电压、温度与状态节点，极大降低系统 fork 进程开销并提升采样准度。
     *
     * @param fallbackVoltage 兜底电压
     * @param fallbackTemp 兜底温度
     * @param isCharging 是否处于充电连接状态
     * @return 硬件采样结果，失败返回 null
     */
    fun readHardwareBatchViaShizuku(
        fallbackVoltage: Float?,
        fallbackTemp: Float?,
        isCharging: Boolean = false
    ): HardwareSample? {
        val curPath = cachedCurrentPath ?: CURRENT_PATHS.firstOrNull() ?: return null
        val voltPath = cachedVoltagePath ?: VOLTAGE_PATHS.firstOrNull() ?: return null
        val tempPath = cachedTempPath ?: TEMP_PATHS.firstOrNull() ?: return null
        val statusPath = cachedStatusPath ?: STATUS_PATHS.firstOrNull()

        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }
            val cmd = if (statusPath != null) {
                "cat $curPath $voltPath $tempPath $statusPath 2>/dev/null"
            } else {
                "cat $curPath $voltPath $tempPath 2>/dev/null"
            }
            val proc = method.invoke(
                null,
                arrayOf("sh", "-c", cmd),
                null,
                null
            ) as? Process ?: return null

            val lines = proc.inputStream.bufferedReader().use { it.readLines() }
            proc.waitFor()
            if (lines.size >= 2) {
                val rawCur = lines[0].trim().toLongOrNull() ?: return null
                val rawVolt = lines[1].trim().toLongOrNull() ?: return null
                val rawTemp = if (lines.size >= 3) lines[2].trim().toFloatOrNull() else null
                val rawStatus = if (lines.size >= 4) lines[3].trim() else null

                val curMa = normalizeCurrentToMa(Math.abs(rawCur))
                val voltV = normalizeVoltageToVolts(rawVolt)
                val tempC = if (rawTemp != null && rawTemp != 0f) {
                    if (rawTemp >= 100f || rawTemp <= -100f) rawTemp / 10f else rawTemp
                } else {
                    fallbackTemp
                }

                if (curMa > 0f && voltV > 0f) {
                    cachedCurrentPath = curPath
                    cachedVoltagePath = voltPath
                    if (rawTemp != null) cachedTempPath = tempPath
                    if (rawStatus != null && rawStatus.isNotEmpty()) cachedStatusPath = statusPath

                    val pWatts = (curMa * voltV) / 1000f
                    val isDischargingStatus = rawStatus != null && (rawStatus.startsWith("D", ignoreCase = true) || rawStatus.startsWith("N", ignoreCase = true))
                    val isNetDischarging = isDischargingStatus || (rawCur < 0L)
                    val signedPower = if (isCharging) {
                        if (isNetDischarging) -pWatts else pWatts
                    } else {
                        pWatts.coerceAtLeast(0f)
                    }
                    val signedCur = if (isCharging) {
                        if (isNetDischarging) -curMa else curMa
                    } else {
                        curMa
                    }

                    HardwareSample(
                        currentMa = signedCur,
                        voltageVolts = voltV,
                        powerWatts = Math.round(signedPower * 1000f) / 1000f,
                        temperatureCelsius = tempC
                    )
                } else null
            } else null
        } catch (_: Throwable) {
            null
        }
    }
}
