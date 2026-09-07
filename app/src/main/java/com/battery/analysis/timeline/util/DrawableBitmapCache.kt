package com.battery.analysis.timeline.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.collection.LruCache

/**
 * 应用图标 Drawable 到 Bitmap 的内存缓存工具类。
 * 避免在 Canvas 绘制循环中频繁进行 Drawable 转换与内存分配，提升整体帧率。
 */
object DrawableBitmapCache {

    // 最大缓存 100 个常用应用图标
    private val cache = LruCache<String, Bitmap>(100)

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
     * 清理所有图标位图缓存。
     */
    fun clear() {
        cache.evictAll()
    }
}
