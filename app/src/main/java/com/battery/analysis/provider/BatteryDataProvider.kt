package com.battery.analysis.provider

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.battery.analysis.model.BatteryInfo

/**
 * 电池数据提供者接口。
 * 定义了获取电池信息的方法。
 */
interface BatteryDataProvider {
    /**
     * 获取电池信息。
     *
     * @param context 应用程序的上下文
     * @return 获取到的电池信息对象，若无法获取则返回空的 [BatteryInfo] 或各字段为 null
     */
    fun getBatteryInfo(context: Context): BatteryInfo
}

/**
 * 使用 Android 系统普通 API (BatteryManager 及电池广播) 获取电池信息的实现类。
 */
class NormalApiProvider : BatteryDataProvider {
    /**
     * 通过 [BatteryManager] 及系统电池广播获取全量电池信息。
     *
     * @param context 应用程序的上下文
     * @return 包含从 API 获取的电池容量及各项运行参数的 [BatteryInfo]
     */
    override fun getBatteryInfo(context: Context): BatteryInfo {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        
        // 注册接收一次粘性广播以获取温度、电压、状态等
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, intentFilter)

        // 1. 获取当前电量百分比
        val levelFromProp = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val levelFromIntent = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val level = if (levelFromProp in 1..100) levelFromProp else if (levelFromIntent in 1..100) levelFromIntent else null

        // 2. 获取电池状态 (充电中/放电中/已充满等)
        val rawStatus = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val statusStr = when (rawStatus) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "充电中"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "放电中"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "未充电"
            BatteryManager.BATTERY_STATUS_FULL -> "已充满"
            else -> "未知"
        }

        // 3. 获取电池健康状态描述 (良好/过热/损坏等)
        val rawHealth = batteryStatus?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        val healthStatusStr = when (rawHealth) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "良好"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "过热"
            BatteryManager.BATTERY_HEALTH_DEAD -> "损坏"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "电压过高"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "未知故障"
            BatteryManager.BATTERY_HEALTH_COLD -> "过冷"
            else -> "未知"
        }

        // 4. 获取电池温度（单位转换为 ℃）
        val tempRaw = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val temperature = if (tempRaw > 0) tempRaw / 10.0f else null

        // 5. 获取电池电压（单位：mV）
        val voltRaw = batteryStatus?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val voltage = if (voltRaw > 0) voltRaw.toFloat() else null

        // 6. 获取电池电流（单位转换为 mA）
        val rawCurrent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val currentNow = if (rawCurrent != 0 && rawCurrent != Int.MIN_VALUE) {
            val absCur = Math.abs(rawCurrent)
            if (absCur < 100000) rawCurrent.toFloat() else rawCurrent / 1000f
        } else null

        // 7. 计算实时功率（单位：W）
        val powerWatts = if (voltage != null && currentNow != null) {
            Math.abs(voltage * currentNow) / 1000000f
        } else null

        // 8. 获取电池技术类型
        val technology = batteryStatus?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)

        // 9. 获取循环次数 (API 34 加入了 EXTRA_CYCLE_COUNT)
        var cycleCount: Int? = null
        try {
            val count = batteryStatus?.getIntExtra("android.os.extra.CYCLE_COUNT", -1) ?: -1
            if (count > 0) {
                cycleCount = count
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        // 10. 获取电池总容量（以微安时为单位，需转换为毫安时）
        val chargeCounter = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val currentCapacityMah = if (chargeCounter > 0) {
            if (chargeCounter < 100000) {
                chargeCounter.toFloat()
            } else {
                chargeCounter / 1000f
            }
        } else null
        
        // 计算预估的满电容量
        val fullChargeCapacityMah = if (currentCapacityMah != null && level != null && level > 0) {
            (currentCapacityMah / level) * 100
        } else null

        // 尝试通过反射获取系统内部的电池容量 profile（设计容量）
        val designCapacityMah = getDesignCapacity(context)

        // 计算健康度百分比（精确浮点计算）
        var healthPercent: Float? = null
        if (fullChargeCapacityMah != null && designCapacityMah != null && designCapacityMah > 0) {
            healthPercent = (fullChargeCapacityMah / designCapacityMah) * 100f
        }

        val currentTimeStr = getCurrentFormattedTime()

        return BatteryInfo(
            batteryHealth = healthPercent,
            healthStatus = healthStatusStr,
            level = level,
            status = statusStr,
            designCapacity = designCapacityMah,
            currentCapacity = currentCapacityMah,
            fullChargeCapacity = fullChargeCapacityMah,
            cycleCount = cycleCount,
            temperature = temperature,
            voltage = voltage,
            currentNow = currentNow,
            powerWatts = powerWatts,
            isDualCell = false,
            technology = technology,
            source = "普通 API (BatteryManager)",
            captureTime = currentTimeStr
        )
    }

    /**
     * 通过反射调用 PowerProfile 获取电池设计容量，并在内存中进行线程安全的静态缓存以避免重复反射开销。
     *
     * @param context 应用程序的上下文
     * @return 电池设计容量（mAh），如果获取失败则返回 null
     */
    private fun getDesignCapacity(context: Context): Float? {
        if (hasCheckedDesignCapacity) {
            return cachedDesignCapacity
        }
        synchronized(NormalApiProvider::class.java) {
            if (hasCheckedDesignCapacity) {
                return cachedDesignCapacity
            }
            cachedDesignCapacity = try {
                val powerProfileClass = Class.forName("com.android.internal.os.PowerProfile")
                val powerProfile = powerProfileClass.getConstructor(Context::class.java).newInstance(context)
                val getBatteryCapacityMethod = powerProfileClass.getMethod("getBatteryCapacity")
                val capacity = getBatteryCapacityMethod.invoke(powerProfile) as Double
                if (capacity > 0) capacity.toFloat() else null
            } catch (e: Exception) {
                null
            }
            hasCheckedDesignCapacity = true
            return cachedDesignCapacity
        }
    }

    companion object {
        @Volatile
        private var cachedDesignCapacity: Float? = null
        @Volatile
        private var hasCheckedDesignCapacity: Boolean = false

        private val dateFormatThreadLocal = object : ThreadLocal<java.text.SimpleDateFormat>() {
            /**
             * 初始化线程局部变量的 SimpleDateFormat 实例。
             *
             * @return 初始化的 SimpleDateFormat 格式化对象
             */
            override fun initialValue(): java.text.SimpleDateFormat {
                return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            }
        }

        /**
         * 获取当前格式化后的日期时间字符串。
         *
         * @return 格式化后的当前时间文本
         */
        fun getCurrentFormattedTime(): String {
            return dateFormatThreadLocal.get()?.format(java.util.Date()) ?: ""
        }
    }
}
