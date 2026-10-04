package com.battery.analysis.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/**
 * 应用程序小图标与显示名称的本地持久化缓存管理器。
 * 解决应用卸载后系统底层 PackageManager 彻底抹除 APK 信息导致图标与名称无法还原的问题。
 * 在应用安装或活跃期间自动将原生图标位图与 Label 持久化存储至私有磁盘与配置中，
 * 即使应用被卸载或息屏清空内存，依然能原汁原味地还原原 app 图标与名称。
 */
object AppIconCacheManager {

    private const val DIR_APP_ICONS = "app_icons"
    private const val PREFS_APP_NAMES = "battery_app_name_cache"
    private const val PREFS_UID_PKG = "battery_uid_pkg_cache"

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 获取应用图标私有存储目录。若目录不存在则自动创建。
     *
     * @param context 运行上下文
     * @return 应用程序图标存储目录对象 [File]
     */
    fun getIconCacheDir(context: Context): File {
        val dir = File(context.filesDir, DIR_APP_ICONS)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * 将应用图标位图持久化保存至本地磁盘文件。
     *
     * @param context 运行上下文
     * @param packageName 目标应用程序包名
     * @param bitmap 待保存的图标位图对象
     * @return 若持久化保存成功返回 true，发生异常或参数无效返回 false
     */
    fun saveAppIcon(context: Context, packageName: String, bitmap: Bitmap): Boolean {
        if (packageName.isBlank() || bitmap.isRecycled) return false
        return try {
            val dir = getIconCacheDir(context)
            val file = File(dir, "${packageName}.png")
            if (file.exists() && file.length() > 0L) {
                return true
            }
            val tempFile = File(dir, "${packageName}.tmp")
            FileOutputStream(tempFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.flush()
            }
            tempFile.renameTo(file)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 异步将应用图标位图持久化保存至本地磁盘文件，避免阻塞调用方主线程或渲染线程。
     *
     * @param context 运行上下文
     * @param packageName 目标应用程序包名
     * @param bitmap 待保存的图标位图对象
     */
    fun saveAppIconAsync(context: Context, packageName: String, bitmap: Bitmap) {
        if (packageName.isBlank() || bitmap.isRecycled) return
        val appCtx = context.applicationContext
        ioScope.launch {
            saveAppIcon(appCtx, packageName, bitmap)
        }
    }

    /**
     * 检查本地磁盘是否已持久化指定包名的应用图标。
     *
     * @param context 运行上下文
     * @param packageName 目标应用程序包名
     * @return 若本地磁盘已存在有效图标文件返回 true，否则返回 false
     */
    fun hasAppIcon(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val file = File(getIconCacheDir(context), "${packageName}.png")
        return file.exists() && file.length() > 0L
    }

    /**
     * 从本地磁盘加载指定包名的应用图标位图。
     *
     * @param context 运行上下文
     * @param packageName 目标应用程序包名
     * @return 解码成功的应用图标 [Bitmap]，若文件不存在或损坏则返回 null
     */
    fun loadAppIcon(context: Context, packageName: String): Bitmap? {
        if (packageName.isBlank()) return null
        return try {
            val file = File(getIconCacheDir(context), "${packageName}.png")
            if (file.exists() && file.length() > 0L) {
                BitmapFactory.decodeFile(file.absolutePath)
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 将应用名称持久化保存至本地配置中。
     *
     * @param context 运行上下文
     * @param packageName 目标应用程序包名
     * @param appName 应用程序显示名称（Label）
     */
    fun saveAppName(context: Context, packageName: String, appName: String) {
        if (packageName.isBlank() || appName.isBlank()) return
        try {
            val prefs = context.getSharedPreferences(PREFS_APP_NAMES, Context.MODE_PRIVATE)
            prefs.edit().putString(packageName, appName).apply()
        } catch (_: Throwable) {
        }
    }

    /**
     * 从本地持久化存储中读取指定包名的原应用显示名称。
     *
     * @param context 运行上下文
     * @param packageName 目标应用程序包名
     * @return 原应用程序显示名称，未找到时返回 null
     */
    fun getSavedAppName(context: Context, packageName: String): String? {
        if (packageName.isBlank()) return null
        return try {
            val prefs = context.getSharedPreferences(PREFS_APP_NAMES, Context.MODE_PRIVATE)
            prefs.getString(packageName, null)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 将 UID 与应用程序包名的映射关系持久化保存至本地配置中。
     * 用于在应用被卸载后依然能够通过系统底层 BatteryStats 的 UID 反查出原应用的真实包名。
     *
     * @param context 运行上下文
     * @param uid 目标用户标识 UID
     * @param packageName 目标应用程序包名
     */
    fun saveUidMapping(context: Context, uid: Int, packageName: String) {
        if (uid <= 0 || packageName.isBlank()) return
        try {
            val prefs = context.getSharedPreferences(PREFS_UID_PKG, Context.MODE_PRIVATE)
            prefs.edit().putString(uid.toString(), packageName).apply()
        } catch (_: Throwable) {
        }
    }

    /**
     * 根据 UID 从本地历史映射字典中反查关联的应用程序包名。
     *
     * @param context 运行上下文
     * @param uid 目标用户标识 UID
     * @return 映射的应用程序包名，未命中返回 null
     */
    fun getPackageNameByUid(context: Context, uid: Int): String? {
        if (uid <= 0) return null
        return try {
            val prefs = context.getSharedPreferences(PREFS_UID_PKG, Context.MODE_PRIVATE)
            prefs.getString(uid.toString(), null)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 根据 UID 尝试从本地磁盘恢复已卸载应用的原生小图标。
     * 优先通过 UID 反查历史记录的真实包名并读取磁盘图标，若未命中则尝试读取 uid_XXX.png 文件。
     *
     * @param context 运行上下文
     * @param uid 目标用户标识 UID
     * @return 恢复的应用图标位图 [Bitmap]，未命中返回 null
     */
    fun loadAppIconByUid(context: Context, uid: Int): Bitmap? {
        if (uid <= 0) return null
        val mappedPkg = getPackageNameByUid(context, uid)
        if (!mappedPkg.isNullOrBlank()) {
            val bmp = loadAppIcon(context, mappedPkg)
            if (bmp != null) return bmp
        }
        val file = File(getIconCacheDir(context), "uid_${uid}.png")
        return if (file.exists() && file.length() > 0L) {
            try {
                BitmapFactory.decodeFile(file.absolutePath)
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }
    }

    /**
     * 根据 UID 尝试从本地历史配置中恢复已卸载应用的原显示名称。
     *
     * @param context 运行上下文
     * @param uid 目标用户标识 UID
     * @return 恢复的原应用显示名称，未命中返回 null
     */
    fun getSavedAppNameByUid(context: Context, uid: Int): String? {
        if (uid <= 0) return null
        val mappedPkg = getPackageNameByUid(context, uid) ?: return null
        return getSavedAppName(context, mappedPkg)
    }

    /**
     * 将 Drawable 转换为指定像素规格的 Bitmap 对象。
     *
     * @param drawable 原始 Drawable
     * @param targetSizePx 目标宽高像素尺寸
     * @return 转换后的 [Bitmap] 实例，若输入无效或失败则返回 null
     */
    fun drawableToBitmap(drawable: Drawable?, targetSizePx: Int): Bitmap? {
        if (drawable == null || targetSizePx <= 0) return null
        return try {
            if (drawable is BitmapDrawable && drawable.bitmap != null && !drawable.bitmap.isRecycled) {
                Bitmap.createScaledBitmap(drawable.bitmap, targetSizePx, targetSizePx, true)
            } else {
                val newBmp = Bitmap.createBitmap(targetSizePx, targetSizePx, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(newBmp)
                drawable.setBounds(0, 0, targetSizePx, targetSizePx)
                drawable.draw(canvas)
                newBmp
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 在后台 IO 协程中异步扫描当前所有已安装应用，增量预热并持久化其图标与名称至磁盘。
     * 确保在应用被卸载之前，其原生小图标与 Label 均已安全存储至本地，实现卸载后 100% 完整追溯。
     *
     * @param context 运行上下文
     */
    fun warmUpInstalledAppsAsync(context: Context) {
        val appCtx = context.applicationContext
        ioScope.launch {
            try {
                val pm = appCtx.packageManager
                val apps = pm.getInstalledApplications(0)
                val targetSizePx = (appCtx.resources.displayMetrics.density * 42f).toInt().coerceAtLeast(1)
                val namePrefs = appCtx.getSharedPreferences(PREFS_APP_NAMES, Context.MODE_PRIVATE)
                val nameEditor = namePrefs.edit()
                val uidPrefs = appCtx.getSharedPreferences(PREFS_UID_PKG, Context.MODE_PRIVATE)
                val uidEditor = uidPrefs.edit()

                for (app in apps) {
                    val pkg = app.packageName
                    if (pkg.isBlank()) continue

                    // 0. 持久化应用 UID 与包名映射
                    if (app.uid > 0) {
                        uidEditor.putString(app.uid.toString(), pkg)
                    }

                    // 1. 持久化应用显示名称
                    if (!namePrefs.contains(pkg)) {
                        val label = try {
                            pm.getApplicationLabel(app).toString()
                        } catch (_: Throwable) {
                            pkg
                        }
                        if (label.isNotBlank()) {
                            nameEditor.putString(pkg, label)
                        }
                    }

                    // 2. 增量持久化应用小图标（仅当磁盘未保存时执行提取与写入）
                    if (!hasAppIcon(appCtx, pkg)) {
                        try {
                            val drawable = pm.getApplicationIcon(app)
                            val bitmap = drawableToBitmap(drawable, targetSizePx)
                            if (bitmap != null) {
                                saveAppIcon(appCtx, pkg, bitmap)
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
                nameEditor.apply()
                uidEditor.apply()
            } catch (_: Throwable) {
            }
        }
    }
}
