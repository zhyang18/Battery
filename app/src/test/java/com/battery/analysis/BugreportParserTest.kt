package com.battery.analysis

import com.battery.analysis.provider.BugreportParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/**
 * 错误报告（Bugreport）底层解析器单元测试套件。
 * 验证对 AIDL getHealthInfo、多行 dumpsys 结构以及包含 SELinux auditd 权限报错干扰时的正确解析与过滤能力。
 */
class BugreportParserTest {

    /**
     * 验证包含 SELinux auditd 假命中日志及完整 HealthInfo dump 的输入流能够正确跳过干扰并提取出全部指标。
     */
    @Test
    fun testParseHealthInfoWithAuditdInterference() = runBlocking {
        val parser = BugreportParser()

        // 模拟真实的 Bugreport：前半部分包含 auditd 权限报错，后半部分包含真实的 DUMP
        val mockBugreportContent = """
            ========================================================
            == dumpstate: 2026-08-20 14:22:32
            ========================================================
            --------- beginning of main
            08-20 13:52:48.010 1000 1222 1222 I auditd : avc: denied { find } for pid=14712 uid=10348 name=android.hardware.health.IHealth/default scontext=u:r:untrusted_app:s0:c92,c257,c512,c768 tcontext=u:object_r:hal_health_service:s0 tclass=service_manager permissive=0
            08-20 13:52:48.011 1000 1222 1222 I auditd : avc: denied { find } for pid=14712 uid=10348 name=android.hardware.ir.IConsumerIr/default scontext=u:r:untrusted_app:s0:c92,c257,c512,c768 tcontext=u:object_r:hal_ir_service:s0 tclass=service_manager permissive=0
            --------- beginning of system
            08-20 13:53:00.000 1000 1200 1200 I ActivityManager: Displayed com.battery.analysis
            -------------------------------------------------------------------------------
            DUMP OF SERVICE android.hardware.health.IHealth/default:
            Service host process PID: 2867
            Threads in use: 1/1
            Client PIDs: 3155, 1989, 2549, 1222
            ac: 0 usb: 1 wireless: 0 dock: 0 current_max: 500000 voltage_max: 5000000
            status: 2 health: 2 present: 1
            level: 59 voltage: 4000 temp: 360
            current now: -146
            current avg: 0
            charge counter: 4622
            current now: -212
            cycle count: 60
            Full charge: 8065000

            getHealthInfo -> HealthInfo{chargerAcOnline: false, chargerUsbOnline: true, chargerWirelessOnline: false, chargerDockOnline: false, maxChargingCurrentMicroamps: 500000, maxChargingVoltageMicrovolts: 5000000, batteryStatus: CHARGING, batteryHealth: GOOD, batteryPresent: true, batteryLevel: 59, batteryVoltageMillivolts: 4003, batteryTemperatureTenthsCelsius: 360, batteryCurrentMicroamps: -146, batteryCycleCount: 60, batteryFullChargeUah: 8065000, batteryChargeCounterUah: 4622, batteryTechnology: Li-ion, batteryCurrentAverageMicroamps: 0, diskStats: [], storageInfos: [], batteryCapacityLevel: NORMAL, batteryChargeTimeToFullNowSeconds: -1, batteryFullChargeDesignCapacityUah: 8000000, chargingState: INVALID, chargingPolicy: INVALID, batteryHealthData: (null)}
            --------- 0.006s was the duration of dumpsys android.hardware.health.IHealth/default, ending at: 2026-08-20 14:23:22
            -------------------------------------------------------------------------------
        """.trimIndent()

        val inputStream = ByteArrayInputStream(mockBugreportContent.toByteArray(StandardCharsets.UTF_8))
        val result = parser.scanStreamForHealthInfo(inputStream, "2026-08-20 14:22:32")

        assertNotNull(result)
        assertTrue("应成功跳过 auditd 假命中并解析出有效电池数据", result.hasRealData)
        val info = result.parsedBatteryInfo
        assertNotNull(info)
        assertEquals(59, info?.level)
        assertEquals(4003f, info?.voltage ?: 0f, 0.1f)
        assertEquals(36.0f, info?.temperature ?: 0f, 0.1f)
        assertEquals(60, info?.cycleCount)
        assertEquals(8065f, info?.fullChargeCapacity ?: 0f, 0.1f)
        assertEquals(8000f, info?.designCapacity ?: 0f, 0.1f)
        assertEquals("Charging", info?.status)
        assertEquals("GOOD", info?.healthStatus)
        assertEquals(100.8125f, info?.batteryHealth ?: 0f, 0.01f)
    }
}
