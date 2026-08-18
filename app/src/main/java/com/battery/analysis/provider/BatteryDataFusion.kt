package com.battery.analysis.provider

import android.content.Context
import com.battery.analysis.model.BatteryInfo

/**
 * 电池数据融合类。
 * 负责收集来自普通 API 和 Shizuku (sysfs/dumpsys) 的数据，进行比较、清洗和信任度判定，最终计算出电池健康度。
 */
class BatteryDataFusion {

    /**
     * 执行数据融合及可信度判断，输出最终的电池健康度及相关数据。
     *
     * @param context 应用程序的上下文
     * @return 经过融合与验证后得到的最可信的电池信息
     */
    fun getFusedBatteryInfo(context: Context): BatteryInfo {
        val normalApiProvider = NormalApiProvider()
        val shizukuProvider = ShizukuProvider()

        val normalInfo = normalApiProvider.getBatteryInfo(context)
        val shizukuInfo = shizukuProvider.getBatteryInfo(context)

        // 1. 数据融合：优先使用 Shizuku 获取的底层 sysfs 数据，如果缺失则退化为普通 API 数据
        val fusedDesignCapacity = shizukuInfo.designCapacity ?: normalInfo.designCapacity
        var fusedFullChargeCapacity = shizukuInfo.fullChargeCapacity ?: normalInfo.fullChargeCapacity
        var fusedCurrentCapacity = shizukuInfo.currentCapacity ?: normalInfo.currentCapacity
        val shizukuCycle = shizukuInfo.cycleCount ?: 0
        val normalCycle = normalInfo.cycleCount ?: 0
        var fusedCycleCount: Int? = if (shizukuCycle > normalCycle) shizukuCycle else normalCycle
        if (fusedCycleCount == 0) fusedCycleCount = null

        // 2. 可信度判断 (Trustworthiness Judgment)
        if (fusedFullChargeCapacity != null && fusedDesignCapacity != null) {
            if (fusedFullChargeCapacity > fusedDesignCapacity * 1.5f || fusedFullChargeCapacity < fusedDesignCapacity * 0.1f) {
                fusedFullChargeCapacity = normalInfo.fullChargeCapacity
            }
        }

        // 判断当前电量是否超出正常范围
        if (fusedCurrentCapacity != null && fusedFullChargeCapacity != null) {
            if (fusedCurrentCapacity > fusedFullChargeCapacity * 1.1f) {
                fusedCurrentCapacity = normalInfo.currentCapacity
            }
        }

        // 3. 计算电池健康度 (Battery Health)
        var health: Float? = null
        if (fusedFullChargeCapacity != null && fusedDesignCapacity != null && fusedDesignCapacity > 0) {
            health = (fusedFullChargeCapacity / fusedDesignCapacity) * 100f
        }

        return BatteryInfo(
            batteryHealth = health,
            designCapacity = fusedDesignCapacity,
            currentCapacity = fusedCurrentCapacity,
            fullChargeCapacity = fusedFullChargeCapacity,
            cycleCount = fusedCycleCount,
            source = "数据融合中心 (Fusion)"
        )
    }
}
