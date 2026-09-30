package com.battery.analysis.timeline.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.collection.LruCache

/**
 * 应用图标 Drawable 到 Bitmap 的轻量级内存缓存工具类。
 * 避免在 Canvas 绘制循环及列表滚动中频繁进行 Drawable 转换与内存分配，提升整体帧率。
 *
 * 优化：
 * 1. 采用按需加载与直出指定尺寸 Bitmap 机制，防止在数据模型中长期强引用庞大的原始 AdaptiveIconDrawable；
 * 2. 基于 Bitmap 字节大小的 [LruCache]，上限压缩至最大 4MB，显著降低常驻内存；
 * 3. 提供 [trimToLevel] 响应 [android.content.ComponentCallbacks2] 内存修剪，UI 不可见或切后台时彻底清空。
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
        } catch (_: Exception) {
            null
        }

        if (bitmap != null) {
            cache.put(cacheKey, bitmap)
        }
        return bitmap
    }

    /**
     * 按需获取或直接从 PackageManager 极速解码指定尺寸的应用小图标 Bitmap。
     * 解决数据模型中强引用原始 Drawable 导致内存暴涨的问题，解码后原始 Drawable 立即释放，
     * 仅将极小尺寸（如 42dp，单张约 60KB）的 Bitmap 保留在 LRU 缓存中。
     *
     * @param context 运行上下文
     * @param packageName 目标应用包名
     * @param sizePx 目标绘制像素大小
     * @param fallbackDrawable 可选的备选 Drawable
     * @return 转换或命中缓存的 [Bitmap] 实例，加载失败返回 null
     */
    fun getOrLoadBitmap(
        context: Context,
        packageName: String,
        sizePx: Int,
        fallbackDrawable: Drawable? = null
    ): Bitmap? {
        if (sizePx <= 0) return null
        val cacheKey = "${packageName}_$sizePx"

        val cached = cache.get(cacheKey)
        if (cached != null && !cached.isRecycled) {
            return cached
        }

        val bitmap = try {
            val drawable = fallbackDrawable ?: run {
                val pm = context.packageManager
                if (packageName == "com.android.systemui.standby" || packageName.startsWith("systemui.standby")) {
                    getDefaultHomeLauncherIcon(context) ?: pm.defaultActivityIcon
                } else {
                    try {
                        val ai = pm.getApplicationInfo(packageName, 0)
                        pm.getApplicationIcon(ai)
                    } catch (_: Exception) {
                        pm.defaultActivityIcon
                    }
                }
            }
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
     * 当进入后台（level >= 20）时立即释放全部缓存，彻底消除图标内存驻留。
     *
     * @param level 系统低内存等级，参见 [android.content.ComponentCallbacks2] 常量
     */
    fun trimToLevel(level: Int) {
        when {
            level >= 20 -> cache.evictAll() // TRIM_MEMORY_UI_HIDDEN 及以上直接清空全部图标缓存
        }
    }

    /**
     * 清理所有图标位图缓存。
     */
    fun clear() {
        cache.evictAll()
    }
}
