package com.battery.analysis.manager

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.battery.analysis.db.PowerUsageDbHelper
import java.io.File

/**
 * 应用程序版本升级与数据无缝衔接管理类。
 * 负责记录应用历史运行版本号、检测覆盖安装与版本升级事件，并在升级后自动执行数据迁移、
 * 运行中孤儿会话断点保护与自动封顶、偏好设置配置项平滑演化以及过期临时缓存清理。
 */
class AppUpgradeManager private constructor(private val context: Context) {

    /**
     * 获取当前安装的应用程序版本代码 (VersionCode)。
     * 兼容 Android P (API 28) 及以上版本的 [PackageInfo.getLongVersionCode] 与低版本 [PackageInfo.versionCode]。
     *
     * @return 当前运行的应用版本代码 Long 数值，若获取失败返回 0L
     */
    fun getCurrentVersionCode(): Long {
        return try {
            val pm = context.packageManager
            val pkgInfo = pm.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pkgInfo.versionCode.toLong()
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取当前应用版本号失败", e)
            0L
        }
    }

    /**
     * 获取上一次成功运行并记录的应用程序版本代码。
     *
     * @return 上次运行记录的版本代码 Long 数值，若为首次全新安装返回 -1L
     */
    fun getLastVersionCode(): Long {
        val prefs = getUpgradePrefs()
        return prefs.getLong(KEY_LAST_VERSION_CODE, -1L)
    }

    /**
     * 检测当前应用运行是否属于覆盖升级安装场景。
     *
     * @return 若当前版本代码严格大于上次记录版本代码且非首次安装，返回 true；否则返回 false
     */
    fun isUpgradedInstallation(): Boolean {
        val lastVersion = getLastVersionCode()
        val currentVersion = getCurrentVersionCode()
        return lastVersion in 1 until currentVersion
    }

    /**
     * 执行版本升级检测与数据平滑迁移全流程。
     * 内部具备线程同步与幂等性保障，确保同一次升级生命周期内仅执行一次迁移操作。
     *
     * @return 若触发并成功完成了版本升级迁移返回 true；若为常规启动或首次安装返回 false
     */
    @Synchronized
    fun checkAndPerformMigration(): Boolean {
        val prefs = getUpgradePrefs()
        val lastVersion = prefs.getLong(KEY_LAST_VERSION_CODE, -1L)
        val currentVersion = getCurrentVersionCode()

        if (currentVersion <= 0L) {
            Log.w(TAG, "当前版本号获取异常，跳过升级数据迁移")
            return false
        }

        if (lastVersion == -1L) {
            // 首次全新安装：记录当前版本代码并完成初始就绪标记
            Log.i(TAG, "检测到首次全新安装应用，初始化记录版本号: $currentVersion")
            prefs.edit().putLong(KEY_LAST_VERSION_CODE, currentVersion).apply()
            return false
        }

        if (currentVersion > lastVersion) {
            Log.i(TAG, "检测到应用升级安装: 旧版本代码 $lastVersion -> 新版本代码 $currentVersion，开始执行数据无缝迁移")
            val migrationSuccess = performMigration(lastVersion, currentVersion)
            if (migrationSuccess) {
                // 迁移成功后持久化更新记录的版本代码，防止重复触发
                prefs.edit().putLong(KEY_LAST_VERSION_CODE, currentVersion).apply()
                Log.i(TAG, "应用升级数据无缝迁移完成，已更新记录版本为 $currentVersion")
            }
            return migrationSuccess
        }

        return false
    }

    /**
     * 针对不同跨版本区间的具体数据无缝迁移流水线。
     *
     * @param fromVersion 升级前的旧版本代码
     * @param toVersion 升级后的目标版本代码
     * @return 全部迁移步骤执行正常返回 true，出现异常返回 false
     */
    private fun performMigration(fromVersion: Long, toVersion: Long): Boolean {
        return try {
            // 1. 恢复与封顶升级前被系统强杀打断的运行中孤儿会话
            recoverInterruptedSessions()

            // 2. 偏好设置配置项平滑映射与兼容升级
            migratePreferences(fromVersion, toVersion)

            // 3. 清理已废弃的历史内部临时缓存文件
            cleanupObsoleteCache()

            true
        } catch (e: Exception) {
            Log.e(TAG, "应用升级数据无缝迁移过程中抛出异常", e)
            false
        }
    }

    /**
     * 恢复并保护升级前因进程硬杀而中断的会话，委托充放电管理类按真实硬件状态对齐。
     * 若升级后硬件仍处于放电中，则无缝继续当前放电会话统计，确保放电数据与图表连续不断；
     * 若升级后硬件已插电，则自动结案上一个放电会话并开启充电统计。
     */
    private fun recoverInterruptedSessions() {
        try {
            ChargingStatsManager.getInstance(context).checkAndReconcileChargingState()
            PowerUsageManager.getInstance(context).checkAndReconcileDischargeState()
            Log.i(TAG, "成功执行升级后充放电会话自愈恢复与状态对齐")
        } catch (e: Exception) {
            Log.e(TAG, "自愈恢复升级前会话状态失败", e)
        }
    }

    /**
     * 跨版本的 SharedPreferences 偏好配置项映射与类型兼容处理。
     *
     * @param fromVersion 升级前的旧版本代码
     * @param toVersion 升级后的目标版本代码
     */
    private fun migratePreferences(fromVersion: Long, toVersion: Long) {
        try {
            Log.d(TAG, "执行跨版本配置项兼容与映射迁移: 旧版本 $fromVersion -> 新版本 $toVersion")
            val appPrefs = context.getSharedPreferences("battery_app_settings", Context.MODE_PRIVATE)
            val servicePrefs = context.getSharedPreferences("battery_service_prefs", Context.MODE_PRIVATE)

            // 示例：平滑检查旧版服务开关持久化配置，确保无损继承
            if (!servicePrefs.contains("pref_charge_discharge_stats_enabled") &&
                appPrefs.contains("pref_charge_discharge_stats_enabled")
            ) {
                val enabled = appPrefs.getBoolean("pref_charge_discharge_stats_enabled", false)
                servicePrefs.edit().putBoolean("pref_charge_discharge_stats_enabled", enabled).apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "偏好设置项升级迁移失败", e)
        }
    }

    /**
     * 清理升级过程中已废弃或多余的历史内部临时缓存，防止旧文件长期占用内部存储空间。
     */
    private fun cleanupObsoleteCache() {
        try {
            val cacheDir = context.cacheDir
            if (cacheDir != null && cacheDir.exists()) {
                val tempFiles = cacheDir.listFiles { file ->
                    file.name.startsWith("dump_") || file.name.startsWith("temp_battery_")
                }
                tempFiles?.forEach { file ->
                    safeDeleteFile(file)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "清理升级临时缓存文件失败", e)
        }
    }

    /**
     * 安全删除指定文件或目录，静默捕获删除过程中的 IO 异常。
     *
     * @param file 待删除的目标文件对象
     * @return 删除成功返回 true，文件不存在或删除失败返回 false
     */
    private fun safeDeleteFile(file: File): Boolean {
        return try {
            if (file.exists()) {
                file.delete()
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "删除旧临时文件失败: ${file.absolutePath}", e)
            false
        }
    }

    /**
     * 获取用于记录版本升级追踪状态的 [SharedPreferences] 实例。
     *
     * @return 升级追踪专属偏好配置对象
     */
    private fun getUpgradePrefs(): SharedPreferences {
        return context.getSharedPreferences(PREFS_UPGRADE_TRACKER, Context.MODE_PRIVATE)
    }

    companion object {
        private const val TAG = "AppUpgradeManager"
        private const val PREFS_UPGRADE_TRACKER = "battery_app_upgrade_tracker"
        private const val KEY_LAST_VERSION_CODE = "last_run_version_code"

        @Volatile
        private var instance: AppUpgradeManager? = null

        /**
         * 获取 [AppUpgradeManager] 单例实例。
         *
         * @param context 应用程序上下文
         * @return 单例管理对象
         */
        fun getInstance(context: Context): AppUpgradeManager {
            return instance ?: synchronized(this) {
                instance ?: AppUpgradeManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
