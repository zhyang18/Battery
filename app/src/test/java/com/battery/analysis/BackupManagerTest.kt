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
}
