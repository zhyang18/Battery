package com.battery.analysis.timeline.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.collection.LruCache

/**
 * 应用图标 Drawable 到 Bitmap 的内存缓存工具类。
 * 避免在 Canvas 绘制循环中频繁进行 Drawable 转换与内存分配，提升整体帧率。
 *
 * 优化：改为基于 Bitmap 字节大小的 [LruCache]，上限取应用可用堆的 1/8，
 * 避免固定 100 条目在低内存设备上常驻过多内存；
 * 同时提供 [trimToLevel] 方法响应系统 [android.content.ComponentCallbacks2] 低内存回调，
 * 在内存紧张时主动 evict 缓存，降低 OOM 风险。
 */
object DrawableBitmapCache {

    /**
     * 计算当前进程 Bitmap 图标缓存的最大字节上限（取当前最大可用堆内存的 1/8）。
     *
     * @return 最大缓存字节数
     */
    private fun calcMaxMemoryBytes(): Int {
        // Runtime.maxMemory() 返回 JVM 可用最大堆大小，取 1/8 作为图标缓存上限
        val maxHeap = Runtime.getRuntime().maxMemory()
        return (maxHeap / 8).coerceIn(2 * 1024 * 1024L, 16 * 1024 * 1024L).toInt()
    }

    /**
     * 基于字节大小的 LruCache，sizeOf 返回每个 Bitmap 的真实内存占用字节数，
     * 使缓存总内存严格受限在 [calcMaxMemoryBytes] 以内。
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
     * 响应系统低内存信号，按内存压力等级主动收缩缓存。
     * 建议在 [android.view.View.onDetachedFromWindow] 或
     * Activity/Service 的 [android.content.ComponentCallbacks2.onTrimMemory] 中调用。
     *
     * @param level 系统低内存等级，参见 [android.content.ComponentCallbacks2] 常量：
     *   - TRIM_MEMORY_UI_HIDDEN (20)：UI 不可见，释放 50% 缓存
     *   - TRIM_MEMORY_BACKGROUND (40)：进程已进入后台 LRU 列表，释放 75% 缓存
     *   - TRIM_MEMORY_MODERATE (60) / TRIM_MEMORY_COMPLETE (80)：内存极度紧张，清空全部缓存
     */
    fun trimToLevel(level: Int) {
        when {
            level >= 80 -> cache.evictAll()                           // TRIM_MEMORY_COMPLETE：清空
            level >= 40 -> cache.trimToSize(cache.maxSize() / 4)     // TRIM_MEMORY_BACKGROUND：保留 25%
            level >= 20 -> cache.trimToSize(cache.maxSize() / 2)     // TRIM_MEMORY_UI_HIDDEN：保留 50%
        }
    }

    /**
     * 清理所有图标位图缓存。
     */
    fun clear() {
        cache.evictAll()
    }
}
