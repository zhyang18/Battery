package com.battery.analysis.timeline.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.collection.LruCache
import com.battery.analysis.model.AppPowerUsageItem
import com.battery.analysis.util.AppIconCacheManager

/**
 * 应用程序图标 Drawable 到 Bitmap 的轻量级两级缓存工具类（L1 内存 LRU 缓存 + L2 本地磁盘持久化缓存）。
 * 避免在 Canvas 绘制循环及列表滚动中频繁进行 Drawable 转换与内存分配，提升整体帧率。
 * 针对已卸载应用，结合本地磁盘图标仓库实现原生小图标长期持久保留与息屏刷新后的毫秒级还原。
 */
object DrawableBitmapCache {

    /**
     * 计算当前进程 Bitmap 图标缓存的最大字节上限（取当前最大可用堆内存的 1/32，最大 4MB）。
     *
     * @return 最大缓存字节数
     */
    private fun calcMaxMemoryBytes(): Int {
        val maxHeap = Runtime.getRuntime().maxMemory()
        return (maxHeap / 32).coerceIn(1 * 1024 * 1024L, 4 * 1024 * 1024L).toInt()
    }

    /**
     * 基于字节大小的 LruCache，sizeOf 返回每个 Bitmap 的真实内存占用字节数。
     */
    private val cache = object : LruCache<String, Bitmap>(calcMaxMemoryBytes()) {
        /**
         * 返回单个缓存项的内存占用字节数（Bitmap 实际分配大小）。
         *
         * @param key 缓存键
         * @param value 缓存的 Bitmap 对象
         * @return Bitmap 字节大小
         */
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount
        }
    }

    // 阴性缓存集合，记录确认无法在系统和磁盘中检索到图标的无效包名，杜绝 onDraw 阶段每帧重复抛出异常或扫描磁盘
    private val negativeCache = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * 获取指定包名与指定像素尺寸的 Bitmap 图标对象。
     * 若已缓存则直接返回；若未缓存且传入了 Drawable 则转换为 Bitmap 并缓存。
     *
     * @param packageName 目标应用包名
     * @param drawable 原始 Drawable 图标
     * @param sizePx 目标绘制像素大小
     * @return 转换或命中缓存的 [Bitmap] 实例
     */
    fun getOrConvertBitmap(
        packageName: String,
        drawable: Drawable?,
        sizePx: Int
    ): Bitmap? {
        if (drawable == null || sizePx <= 0) return null
        val cacheKey = "${packageName}_$sizePx"

        val cached = cache.get(cacheKey)
        if (cached != null && !cached.isRecycled) {
            return cached
        }

        val bitmap = try {
            if (drawable is BitmapDrawable && drawable.bitmap != null && !drawable.bitmap.isRecycled) {
                Bitmap.createScaledBitmap(drawable.bitmap, sizePx, sizePx, true)
            } else {
                val newBmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(newBmp)
                drawable.setBounds(0, 0, sizePx, sizePx)
                drawable.draw(canvas)
                newBmp
            }
        } catch (_: Throwable) {
            null
        }

        if (bitmap != null) {
            cache.put(cacheKey, bitmap)
        }
        return bitmap
    }

    /**
     * 按需获取或直接从 PackageManager/本地磁盘持久化仓库极速解码指定尺寸的应用小图标 Bitmap。
     * 针对已卸载应用，自动反向检索本地持久化图标仓库与历史 UID 关联包名，
     * 解决应用卸载或息屏清空内存后原 app 小图标无法显示的缺陷。
     *
     * @param context 运行上下文
     * @param packageName 目标应用包名
     * @param sizePx 目标绘制像素大小
     * @param fallbackDrawable 可选的备选 Drawable
     * @return 转换或命中两级缓存的 [Bitmap] 实例，加载失败返回 null
     */
    fun getOrLoadBitmap(
        context: Context,
        packageName: String,
        sizePx: Int,
        fallbackDrawable: Drawable? = null
    ): Bitmap? {
        if (sizePx <= 0 || packageName.isBlank()) return null
        val cacheKey = "${packageName}_$sizePx"

        // 0. 阴性缓存快速短路：若之前已确认无法解析，直接跳过，杜绝高频 onDraw 期间反复 IPC 与磁盘扫描
        if (negativeCache.contains(cacheKey)) {
            return null
        }

        // 1. 优先从 L1 内存 LRU 缓存获取
        val cached = cache.get(cacheKey)
        if (cached != null && !cached.isRecycled) {
            return cached
        }

        // 2. 若传入了备用 Drawable，直接转换并异步备份至磁盘持久化
        if (fallbackDrawable != null) {
            val bmp = getOrConvertBitmap(packageName, fallbackDrawable, sizePx)
            if (bmp != null) {
                AppIconCacheManager.saveAppIconAsync(context, packageName, bmp)
            }
            return bmp
        }

        // 3. 处理系统桌面或待机虚拟包名
        val pm = context.packageManager
        if (packageName == "com.android.systemui.standby" || packageName.startsWith("systemui.standby") || packageName == AppPowerUsageItem.PACKAGE_SYSTEM_UI_STANDBY) {
            val launcherDrawable = getDefaultHomeLauncherIcon(context) ?: try { pm.defaultActivityIcon } catch (_: Throwable) { null }
            val bmp = getOrConvertBitmap(packageName, launcherDrawable, sizePx)
            if (bmp != null) {
                AppIconCacheManager.saveAppIconAsync(context, packageName, bmp)
            }
            return bmp
        }

        // 4. 尝试从系统已安装的应用信息中解码图标（安装状态）
        var loadedBitmap: Bitmap? = null
        var isUninstalled = false

        try {
            val ai = pm.getApplicationInfo(packageName, 0)
            val drawable = pm.getApplicationIcon(ai)
            loadedBitmap = getOrConvertBitmap(packageName, drawable, sizePx)
            if (loadedBitmap != null) {
                // 自动将当前安装的原生图标持久化至本地磁盘，为后续卸载提供留痕与追溯
                AppIconCacheManager.saveAppIconAsync(context, packageName, loadedBitmap)
            }
        } catch (_: Exception) {
            // 系统中查无此包，判定该应用已被卸载
            isUninstalled = true
        }

        // 5. 若应用已被卸载或底层查无此包，优先从 L2 本地磁盘持久化仓库恢复原 app 小图标
        if (loadedBitmap == null && (isUninstalled || AppPowerUsageItem.isUninstalledPackage(packageName))) {
            // 5.1 优先使用真实包名从本地磁盘读取卸载前已缓存的原生图标
            var diskBmp = AppIconCacheManager.loadAppIcon(context, packageName)

            // 5.2 若当前为 UID 虚拟包名（如 uninstalled_uid_10234），通过 UID 反查历史真实包名并加载磁盘图标
            if (diskBmp == null && packageName.startsWith(AppPowerUsageItem.PACKAGE_UNINSTALLED_PREFIX)) {
                val uid = packageName.removePrefix(AppPowerUsageItem.PACKAGE_UNINSTALLED_PREFIX).toIntOrNull()
                if (uid != null && uid > 0) {
                    diskBmp = AppIconCacheManager.loadAppIconByUid(context, uid)
                }
            } else if (diskBmp == null && packageName.startsWith("uninstalled_uid_")) {
                val uid = packageName.removePrefix("uninstalled_uid_").toIntOrNull()
                if (uid != null && uid > 0) {
                    diskBmp = AppIconCacheManager.loadAppIconByUid(context, uid)
                }
            }

            if (diskBmp != null && !diskBmp.isRecycled) {
                // 将磁盘加载的 Bitmap 缩放为对应 targetSize 规格并存入内存 L1 缓存
                loadedBitmap = if (diskBmp.width != sizePx || diskBmp.height != sizePx) {
                    try {
                        Bitmap.createScaledBitmap(diskBmp, sizePx, sizePx, true)
                    } catch (_: Throwable) {
                        diskBmp
                    }
                } else {
                    diskBmp
                }
            }
        }

        if (loadedBitmap != null && !loadedBitmap.isRecycled) {
            cache.put(cacheKey, loadedBitmap)
            negativeCache.remove(cacheKey)
        } else {
            negativeCache.add(cacheKey)
        }
        return loadedBitmap
    }

    /**
     * 获取系统当前默认桌面启动器的应用图标 Drawable。
     *
     * @param context 运行上下文
     * @return 默认桌面图标 Drawable，获取失败返回 null
     */
    private fun getDefaultHomeLauncherIcon(context: Context): Drawable? {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolveInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }
            val homePkg = resolveInfo?.activityInfo?.packageName
            if (!homePkg.isNullOrEmpty()) {
                val pm = context.packageManager
                val ai = pm.getApplicationInfo(homePkg, 0)
                pm.getApplicationIcon(ai)
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 响应系统低内存信号，按内存压力等级主动收缩缓存。
     * 针对息屏场景（TRIM_MEMORY_UI_HIDDEN），只做局部收缩而不彻底抹除关键图标；仅在严重内存不足时清空。
     * 即便内存被清空，后续也可直接经由本地磁盘持久化秒级恢复。
     *
     * @param level 系统低内存等级，参见 [android.content.ComponentCallbacks2] 常量
     */
    fun trimToLevel(level: Int) {
        when {
            level >= 80 -> {
                cache.evictAll() // TRIM_MEMORY_COMPLETE 严重缺内存时彻底清空
                negativeCache.clear()
            }
            level >= 40 -> cache.trimToSize(cache.size() / 2) // TRIM_MEMORY_BACKGROUND 减半
        }
    }

    /**
     * 清理所有内存中的图标位图缓存。
     */
    fun clear() {
        cache.evictAll()
        negativeCache.clear()
    }
}
