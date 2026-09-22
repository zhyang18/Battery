package com.battery.analysis

import com.battery.analysis.manager.BackupManager
import com.battery.analysis.model.BackupData
import com.battery.analysis.model.BackupSettings
import com.battery.analysis.model.HistoryRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份与恢复数据解析核心单元测试。
 * 验证 [BackupManager] 对 JSON 数据的序列化与反序列化正确性、参数完整性及异常容错能力。
 */
class BackupManagerTest {

    /**
     * 测试标准备份数据 JSON 解析与字段还原。
     */
    @Test
    fun testParseBackupJson_validData() {
        val testJson = """
            {
                "version": 1,
                "app_version": "1.2.1",
                "backup_time": "2026-08-19 13:30:00",
                "backup_timestamp": 1787117400000,
                "settings": {
                    "theme_mode": 2,
                    "auto_refresh_enabled": true,
                    "refresh_interval_ms": 3000
                },
                "history_records": [
                    {
                        "id": 1787117400000,
                        "capture_time": "2026-08-19 13:30:00",
                        "source": "系统api",
                        "category": "系统api",
                        "note": "日常测试",
                        "battery_health": 98.5,
                        "health_status": "良好",
                        "level": 85,
                        "status": "放电中",
                        "design_capacity": 5000.0,
                        "current_capacity": 4250.0,
                        "full_charge_capacity": 4925.0,
                        "cycle_count": 42,
                        "temperature": 28.5,
                        "voltage": 4120.0,
                        "current_now": -350.0,
                        "power_watts": 1.44,
                        "is_dual_cell": false,
                        "technology": "Li-ion"
                    }
                ]
            }
        """.trimIndent()

        val manager = BackupManager.getInstance()
        val backupData = manager.parseBackupJson(testJson)

        assertEquals(1, backupData.version)
        assertEquals("1.2.1", backupData.appVersion)
        assertEquals("2026-08-19 13:30:00", backupData.backupTime)
        assertEquals(1787117400000L, backupData.backupTimestamp)

        // 验证设置项
        assertEquals(2, backupData.settings.themeMode)
        assertEquals(true, backupData.settings.autoRefreshEnabled)
        assertEquals(3000L, backupData.settings.refreshIntervalMs)

        // 验证历史记录项
        assertEquals(1, backupData.historyRecords.size)
        val record = backupData.historyRecords[0]
        assertEquals(1787117400000L, record.id)
        assertEquals("2026-08-19 13:30:00", record.captureTime)
        assertEquals("系统api", record.source)
        assertEquals("系统api", record.category)
        assertEquals("日常测试", record.note)
        assertEquals(98.5f, record.batteryHealth!!, 0.001f)
        assertEquals("良好", record.healthStatus)
        assertEquals(85, record.level)
        assertEquals("放电中", record.status)
        assertEquals(5000.0f, record.designCapacity!!, 0.001f)
        assertEquals(4250.0f, record.currentCapacity!!, 0.001f)
        assertEquals(4925.0f, record.fullChargeCapacity!!, 0.001f)
        assertEquals(42, record.cycleCount)
        assertEquals(28.5f, record.temperature!!, 0.001f)
        assertEquals(4120.0f, record.voltage!!, 0.001f)
        assertEquals(-350.0f, record.currentNow!!, 0.001f)
        assertEquals(1.44f, record.powerWatts!!, 0.001f)
        assertEquals(false, record.isDualCell)
        assertEquals("Li-ion", record.technology)
    }

    /**
     * 测试当部分非必填字段缺失或为 null 时，解析器能够稳定降级处理且不发生崩溃。
     */
    @Test
    fun testParseBackupJson_partialData() {
        val testJson = """
            {
                "version": 1,
                "app_version": "1.0.0",
                "backup_time": "2026-08-19 12:00:00",
                "settings": {},
                "history_records": [
                    {
                        "capture_time": "2026-08-19 12:00:00",
                        "source": "Shizuku",
                        "level": 90
                    }
                ]
            }
        """.trimIndent()

        val manager = BackupManager.getInstance()
        val backupData = manager.parseBackupJson(testJson)

        assertEquals(1, backupData.version)
        assertNull(backupData.settings.themeMode)
        assertNull(backupData.settings.autoRefreshEnabled)
        assertNull(backupData.settings.refreshIntervalMs)

        assertEquals(1, backupData.historyRecords.size)
        val record = backupData.historyRecords[0]
        assertEquals("2026-08-19 12:00:00", record.captureTime)
        assertEquals("Shizuku", record.source)
        assertEquals("Shizuku", record.category)
        assertEquals(90, record.level)
        assertNull(record.batteryHealth)
        assertNull(record.designCapacity)
    }

    /**
     * 测试全量 App 设置项与充、放电历史记录的 JSON 反序列化解析。
     * 验证包含语言、服务开关、亮息屏间隔、开机自启、充电常亮以及充电会话与放电记录的字段完整性。
     */
    @Test
    fun testParseBackupJson_fullSettingsAndChargeDischargeRecords() {
        val testJson = """
            {
                "version": 2,
                "app_version": "1.3.0",
                "backup_time": "2026-09-22 17:00:00",
                "backup_timestamp": 1790067600000,
                "settings": {
                    "language_mode": 1,
                    "theme_mode": 1,
                    "charge_discharge_stats_enabled": true,
                    "charging_keep_screen_on": true,
                    "power_stats_mode": 0,
                    "power_mode_configured": true,
                    "shizuku_user_disabled": false,
                    "notification_display_enabled": true,
                    "screen_on_interval_ms": 3000,
                    "screen_off_interval_ms": 10000,
                    "boot_auto_start_enabled": true
                },
                "history_records": [],
                "charging_records": [
                    {
                        "id": 101,
                        "record_time": "2026-09-22 15:00:00",
                        "start_timestamp": 1790060400000,
                        "end_timestamp": 1790064000000,
                        "duration_ms": 3600000,
                        "start_level": 20,
                        "end_level": 85,
                        "level_gain": 65,
                        "charged_energy_wh": 18.5,
                        "avg_power_watts": 18.5,
                        "max_power_watts": 33.0,
                        "max_temperature": 36.2,
                        "charge_type": "交流快充",
                        "screen_off_duration_ms": 3000000,
                        "screen_off_level_gain": 55,
                        "screen_off_energy_wh": 15.2,
                        "sample_points_json": "[{\"ts\":1790060400000,\"pw\":33.0,\"lv\":20}]"
                    }
                ],
                "power_usage_records": [
                    {
                        "id": 201,
                        "record_time": "2026-09-22 16:30:00",
                        "level_percent": 80,
                        "voltage_volts": 4.15,
                        "temperature": 29.0,
                        "energy_wh": 16.2,
                        "is_charging": false,
                        "avg_power_watts": 1.25,
                        "screen_on_power_watts": 2.1,
                        "screen_off_power_watts": 0.15,
                        "screen_on_duration_text": "1h30m",
                        "screen_off_duration_text": "4h00m",
                        "total_duration_text": "5h30m",
                        "rem_screen_on": "6h30m",
                        "rem_composite": "12h00m",
                        "rem_screen_off": "35h00m",
                        "is_shizuku_real_data": true,
                        "app_count": 12,
                        "trend_points_json": "[]",
                        "app_list_json": "[]",
                        "background_power_watts": 0.2,
                        "background_duration_text": "2h",
                        "rem_background": "20h",
                        "screen_on_energy_wh": 3.15,
                        "total_energy_wh": 6.88,
                        "screen_off_energy_wh": 0.6,
                        "background_energy_wh": 0.4
                    }
                ]
            }
        """.trimIndent()

        val manager = BackupManager.getInstance()
        val backupData = manager.parseBackupJson(testJson)

        assertEquals(2, backupData.version)
        assertEquals("1.3.0", backupData.appVersion)

        // 验证全部扩展设置项
        val s = backupData.settings
        assertEquals(1, s.languageMode)
        assertEquals(1, s.themeMode)
        assertEquals(true, s.chargeDischargeStatsEnabled)
        assertEquals(true, s.chargingKeepScreenOn)
        assertEquals(0, s.powerStatsMode)
        assertEquals(true, s.powerModeConfigured)
        assertEquals(false, s.shizukuUserDisabled)
        assertEquals(true, s.notificationDisplayEnabled)
        assertEquals(3000L, s.screenOnIntervalMs)
        assertEquals(10000L, s.screenOffIntervalMs)
        assertEquals(true, s.bootAutoStartEnabled)

        // 验证充电记录
        assertEquals(1, backupData.chargingRecords.size)
        val charging = backupData.chargingRecords[0]
        assertEquals(101L, charging.id)
        assertEquals("2026-09-22 15:00:00", charging.recordTime)
        assertEquals(20, charging.startLevel)
        assertEquals(85, charging.endLevel)
        assertEquals(65, charging.levelGain)
        assertEquals(18.5f, charging.chargedEnergyWh, 0.01f)
        assertEquals(33.0f, charging.maxPowerWatts, 0.01f)
        assertEquals("交流快充", charging.chargeType)
        assertEquals(3000000L, charging.screenOffDurationMs)
        assertEquals(1, charging.getSamplePoints().size)

        // 验证放电记录
        assertEquals(1, backupData.powerUsageRecords.size)
        val power = backupData.powerUsageRecords[0]
        assertEquals(201L, power.id)
        assertEquals("2026-09-22 16:30:00", power.recordTime)
        assertEquals(80, power.levelPercent)
        assertEquals(4.15f, power.voltageVolts, 0.01f)
        assertEquals(29.0f, power.temperature, 0.01f)
        assertEquals(false, power.isCharging)
        assertEquals(1.25f, power.avgPowerWatts, 0.01f)
        assertEquals(2.1f, power.screenOnPowerWatts, 0.01f)
        assertEquals("1h30m", power.screenOnDurationText)
        assertEquals("5h30m", power.totalDurationText)
        assertTrue(power.isShizukuRealData)
        assertEquals(12, power.appCount)
    }

    /**
     * 测试向后兼容版本 1 备份文件。
     * 当备份中无 charging_records 与 power_usage_records 时，解析为非 null 的空列表，保证安全平滑升级。
     */
    @Test
    fun testParseBackupJson_v1Compatibility() {
        val v1Json = """
            {
                "version": 1,
                "app_version": "1.0.0",
                "backup_time": "2026-08-01 10:00:00",
                "settings": {
                    "theme_mode": 2
                },
                "history_records": []
            }
        """.trimIndent()

        val manager = BackupManager.getInstance()
        val data = manager.parseBackupJson(v1Json)

        assertEquals(1, data.version)
        assertEquals(2, data.settings.themeMode)
        assertNull(data.settings.languageMode)
        assertNull(data.settings.chargeDischargeStatsEnabled)
        assertNotNull(data.historyRecords)
        assertTrue(data.historyRecords.isEmpty())
        assertNotNull(data.chargingRecords)
        assertTrue(data.chargingRecords.isEmpty())
        assertNotNull(data.powerUsageRecords)
        assertTrue(data.powerUsageRecords.isEmpty())
    }
}
