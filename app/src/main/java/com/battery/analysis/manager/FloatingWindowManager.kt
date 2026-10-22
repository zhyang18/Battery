package com.battery.analysis.manager

import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.battery.analysis.R
import com.battery.analysis.service.BatteryMonitorService
import com.battery.analysis.util.setUnitText
import java.util.Locale
import kotlin.math.abs

/**
 * 电池实时监控全局桌面悬浮窗核心管理器。
 * 负责管理系统悬浮窗权限、悬浮窗窗口创建与渲染、手势拖拽自由交互、横竖屏自适应布局、
 * 状态栏沉浸吸附定位、明暗主题联动、文字大小自定义、透明度自适应以及低功耗实时采样更新。
 */
object FloatingWindowManager {

    private const val PREF_NAME = "floating_window_prefs"
    const val KEY_ENABLED = "pref_floating_enabled"
    const val KEY_SHOW_POWER = "pref_floating_show_power"
    const val KEY_SHOW_TEMP = "pref_floating_show_temp"
    const val KEY_ALIGN_STATUS_BAR = "pref_floating_align_status_bar"
    const val KEY_POS_X = "pref_floating_pos_x"
    const val KEY_POS_Y = "pref_floating_pos_y"
    const val KEY_POS_X_LAND = "pref_floating_pos_x_land"
    const val KEY_POS_Y_LAND = "pref_floating_pos_y_land"
    const val KEY_HORIZONTAL_ALIGN = "pref_floating_horizontal_align"
    const val KEY_OPACITY = "pref_floating_opacity"
    const val KEY_THEME_MODE = "pref_floating_theme_mode"
    const val KEY_FONT_SIZE = "pref_floating_font_size"
    const val KEY_SHOW_UNIT = "pref_floating_show_unit"
    const val KEY_SHOW_BACKGROUND = "pref_floating_show_background"
    const val KEY_OFFSET_X = "pref_floating_offset_x"
    const val KEY_FONT_BOLD = "pref_floating_font_bold"
    const val KEY_POS_PERCENT_X = "pref_floating_pos_percent_x"
    const val KEY_POS_PERCENT_Y = "pref_floating_pos_percent_y"

    const val DEFAULT_FONT_SIZE = 11
    const val MIN_FONT_SIZE = 5
    const val MAX_FONT_SIZE = 20

    const val ALIGN_LEFT = 0
    const val ALIGN_CENTER = 1
    const val ALIGN_RIGHT = 2
    const val ALIGN_CUSTOM = 3

    const val THEME_FOLLOW_SYSTEM = 0
    const val THEME_DARK = 1
    const val THEME_LIGHT = 2

    /**
     * 悬浮窗视图内部子控件快速引用持有者，避免高频刷新时反复遍历 View 树。
     *
     * @property layoutPower 实时功率布局容器
     * @property layoutTemp 实时温度布局容器
     * @property tvDivider 分隔符文本控件
     * @property tvPower 功率数值文本控件
     * @property tvTemp 温度数值文本控件
     * @property ivPower 功率图标控件
     * @property ivTemp 温度图标控件
     */
    private class FloatingViewHolder(
        val layoutPower: View,
        val layoutTemp: View,
        val tvDivider: TextView,
        val tvPower: TextView,
        val tvTemp: TextView,
        val ivPower: ImageView,
        val ivTemp: ImageView
    )

    private var floatingView: View? = null
    private var viewHolder: FloatingViewHolder? = null
    private var windowManager: WindowManager? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var isCallbacksRegistered = false

    private var appContextRef: Context? = null

    @Volatile
    private var cachedPowerWatts: Float? = null

    @Volatile
    private var cachedTemperatureCelsius: Float? = null

    @Volatile
    private var cachedIsCharging: Boolean = false

    /**
     * 系统配置变化回调（如屏幕旋转方向横竖屏切换），用于实时自适应重绘悬浮窗位置。
     */
    private val componentCallbacks = object : ComponentCallbacks2 {
        /**
         * 响应系统配置变化事件。
         *
         * @param newConfig 新系统配置 [Configuration]
         */
        override fun onConfigurationChanged(newConfig: Configuration) {
            val ctx = appContextRef ?: return
            mainHandler.post {
                updatePositionAndAppearance(ctx)
            }
        }

        /**
         * 响应系统低内存微调事件。
         *
         * @param level 内存等级状态
         */
        override fun onTrimMemory(level: Int) {}

        /**
         * 响应系统极端低内存预警事件。
         */
        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onLowMemory() {}
    }

    /**
     * 获取悬浮窗专属 SharedPreferences 配置实例。
     *
     * @param context 运行上下文
     * @return [SharedPreferences] 实例
     */
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 检测当前应用是否具备显示在其他应用上层的系统悬浮窗权限。
     *
     * @param context 运行上下文
     * @return 若已获取权限返回 true，否则返回 false
     */
    fun canDrawOverlays(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    /**
     * 引导用户跳转至系统设置授权悬浮窗管理页面。
     *
     * @param context 运行上下文
     */
    fun requestOverlayPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                try {
                    val fallbackIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(fallbackIntent)
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * 查询是否开启了悬浮窗显示。
     *
     * @param context 运行上下文
     * @return 若已开启返回 true，未开启返回 false
     */
    fun isFloatingEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ENABLED, false)
    }

    /**
     * 设置并持久化是否开启悬浮窗显示。
     * 开启时拉起悬浮窗，若后台监控服务尚未运行则启动服务作为数据源驱动；
     * 关闭时隐藏悬浮窗，并在系统无需继续运行服务时安全停止后台服务。
     *
     * @param context 运行上下文
     * @param enabled 是否开启
     */
    fun setFloatingEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) {
            show(context)
            if (BatteryMonitorService.shouldServiceRun(context)) {
                BatteryMonitorService.start(context)
            }
        } else {
            hide(context)
            if (!BatteryMonitorService.shouldServiceRun(context)) {
                BatteryMonitorService.stop(context)
            }
        }
    }

    /**
     * 查询悬浮窗是否开启实时功率显示。
     *
     * @param context 运行上下文
     * @return 若开启返回 true，未开启返回 false（默认 true）
     */
    fun isShowPower(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SHOW_POWER, true)
    }

    /**
     * 设置并持久化悬浮窗实时功率显示开关。
     *
     * @param context 运行上下文
     * @param show 是否显示实时功率
     */
    fun setShowPower(context: Context, show: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SHOW_POWER, show).apply()
        updateViewContent(context)
    }

    /**
     * 查询悬浮窗是否开启实时温度显示。
     *
     * @param context 运行上下文
     * @return 若开启返回 true，未开启返回 false（默认 true）
     */
    fun isShowTemp(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SHOW_TEMP, true)
    }

    /**
     * 设置并持久化悬浮窗实时温度显示开关。
     *
     * @param context 运行上下文
     * @param show 是否显示实时温度
     */
    fun setShowTemp(context: Context, show: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SHOW_TEMP, show).apply()
        updateViewContent(context)
    }

    /**
     * 查询悬浮窗是否设置为显示在状态栏上。
     *
     * @param context 运行上下文
     * @return 若贴齐状态栏返回 true，否则返回 false（默认 true）
     */
    fun isAlignStatusBar(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ALIGN_STATUS_BAR, true)
    }

    /**
     * 设置并持久化悬浮窗是否贴齐显示在状态栏上。
     *
     * @param context 运行上下文
     * @param align 是否贴齐状态栏
     */
    fun setAlignStatusBar(context: Context, align: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ALIGN_STATUS_BAR, align).apply()
        updatePositionAndAppearance(context)
    }

    /**
     * 精确获取当前设备物理屏幕的真实宽度和高度像素（自适应横屏与竖屏及刘海屏打孔区域）。
     *
     * @param context 运行上下文
     * @return 屏幕真实尺寸键值对，Pair(width, height)（单位：物理像素 px）
     */
    fun getRealScreenSize(context: Context): Pair<Int, Int> {
        val wm = (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager) ?: windowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
            val bounds = wm.maximumWindowMetrics.bounds
            Pair(bounds.width(), bounds.height())
        } else if (wm != null) {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Pair(dm.widthPixels, dm.heightPixels)
        } else {
            val dm = context.resources.displayMetrics
            Pair(dm.widthPixels, dm.heightPixels)
        }
    }

    /**
     * 判断当前系统设备是否正处于横屏显示状态。
     *
     * @param context 运行上下文
     * @return 若处于横屏模式返回 true，否则返回 false
     */
    fun isLandscape(context: Context): Boolean {
        return context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    /**
     * 获取悬浮窗相对屏幕水平可活动区间的百分比位置（0.0 表示最左侧，0.5 表示居中，1.0 表示最右侧）。
     * 解决横竖屏旋转切换时固定像素偏移导致的比例失调与错位问题。
     *
     * @param context 运行上下文
     * @return 水平百分比浮点数，范围 0.0f 至 1.0f
     */
    fun getPercentX(context: Context): Float {
        val prefs = getPrefs(context)
        if (prefs.contains(KEY_POS_PERCENT_X)) {
            return prefs.getFloat(KEY_POS_PERCENT_X, 0.5f).coerceIn(0f, 1f)
        }
        val (w, h) = getRealScreenSize(context)
        val portWidth = minOf(w, h)
        val density = context.resources.displayMetrics.density
        val defaultViewWidth = (120 * density).toInt()
        val currentViewWidth = floatingView?.width?.takeIf { it > 0 } ?: defaultViewWidth
        val maxX = (portWidth - currentViewWidth).coerceAtLeast(1)
        val alignMode = getHorizontalAlign(context)
        val percent = when (alignMode) {
            ALIGN_LEFT -> 0f
            ALIGN_CENTER -> 0.5f
            ALIGN_RIGHT -> 1f
            else -> {
                val posX = prefs.getInt(KEY_POS_X, -1)
                if (posX >= 0) {
                    (posX.toFloat() / maxX).coerceIn(0f, 1f)
                } else {
                    0.5f
                }
            }
        }
        prefs.edit().putFloat(KEY_POS_PERCENT_X, percent).apply()
        return percent
    }

    /**
     * 设置并持久化悬浮窗相对屏幕水平可活动区间的百分比位置。
     *
     * @param context 运行上下文
     * @param percent 水平百分比浮点数（范围 0.0f 至 1.0f）
     */
    fun setPercentX(context: Context, percent: Float) {
        val valid = percent.coerceIn(0f, 1f)
        getPrefs(context).edit().putFloat(KEY_POS_PERCENT_X, valid).apply()
    }

    /**
     * 获取悬浮窗相对屏幕垂直有效可活动区间的百分比位置（0.0 表示最顶端，1.0 表示最底端）。
     * 自动适配状态栏是否允许进入及横竖屏下状态栏高度差异，防止横屏溢出或越界。
     *
     * @param context 运行上下文
     * @return 垂直有效区间百分比浮点数，范围 0.0f 至 1.0f
     */
    fun getPercentY(context: Context): Float {
        val prefs = getPrefs(context)
        if (prefs.contains(KEY_POS_PERCENT_Y)) {
            return prefs.getFloat(KEY_POS_PERCENT_Y, 0f).coerceIn(0f, 1f)
        }
        val (w, h) = getRealScreenSize(context)
        val portHeight = maxOf(w, h)
        val statusBarHeight = getStatusBarHeight(context)
        val density = context.resources.displayMetrics.density
        val defaultViewHeight = (26 * density).toInt()
        val currentViewHeight = floatingView?.height?.takeIf { it > 0 } ?: defaultViewHeight
        val isAlignStatusBar = isAlignStatusBar(context)
        val minY = if (isAlignStatusBar) 0 else statusBarHeight
        val maxY = (portHeight - currentViewHeight).coerceAtLeast(minY)
        val availH = (maxY - minY).coerceAtLeast(1)
        val posY = prefs.getInt(KEY_POS_Y, 0)
        val percent = ((posY - minY).toFloat() / availH).coerceIn(0f, 1f)
        prefs.edit().putFloat(KEY_POS_PERCENT_Y, percent).apply()
        return percent
    }

    /**
     * 设置并持久化悬浮窗相对屏幕垂直有效可活动区间的百分比位置。
     *
     * @param context 运行上下文
     * @param percent 垂直百分比浮点数（范围 0.0f 至 1.0f）
     */
    fun setPercentY(context: Context, percent: Float) {
        val valid = percent.coerceIn(0f, 1f)
        getPrefs(context).edit().putFloat(KEY_POS_PERCENT_Y, valid).apply()
    }

    /**
     * 获取悬浮窗相对屏幕水平中心基准线的偏移量（单位：像素）。
     *
     * @param context 运行上下文
     * @return 相对屏幕水平中心的偏移量像素值
     */
    fun getOffsetX(context: Context): Int {
        val (w, h) = getRealScreenSize(context)
        val isLand = isLandscape(context)
        val screenWidth = if (isLand) maxOf(w, h) else minOf(w, h)
        val density = context.resources.displayMetrics.density
        val defaultViewWidth = (120 * density).toInt()
        val currentViewWidth = floatingView?.width?.takeIf { it > 0 } ?: defaultViewWidth
        val maxX = (screenWidth - currentViewWidth).coerceAtLeast(1)
        val centerX = (screenWidth - currentViewWidth) / 2
        val percentX = getPercentX(context)
        val currentX = (percentX * maxX).toInt()
        return currentX - centerX
    }

    /**
     * 设置并持久化悬浮窗相对屏幕水平中心基准线的偏移量（单位：像素）。
     *
     * @param context 运行上下文
     * @param offset 相对屏幕水平中心的偏移量（0 居中，负数居中偏左，正数居中偏右）
     */
    fun setOffsetX(context: Context, offset: Int) {
        val (w, h) = getRealScreenSize(context)
        val isLand = isLandscape(context)
        val screenWidth = if (isLand) maxOf(w, h) else minOf(w, h)
        val density = context.resources.displayMetrics.density
        val defaultViewWidth = (120 * density).toInt()
        val currentViewWidth = floatingView?.width?.takeIf { it > 0 } ?: defaultViewWidth
        val maxX = (screenWidth - currentViewWidth).coerceAtLeast(1)
        val centerX = (screenWidth - currentViewWidth) / 2
        val targetX = (centerX + offset).coerceIn(0, maxX)
        val percentX = targetX.toFloat() / maxX
        setPercentX(context, percentX)
        getPrefs(context).edit()
            .putInt(KEY_OFFSET_X, offset)
            .putInt(KEY_POS_X, targetX)
            .apply()
    }

    /**
     * 获取指定或当前屏幕方向下持久化的水平位置 X 坐标（像素）。
     * 基于百分占比实时换算，保证横屏与竖屏几何比例无缝贴合。
     *
     * @param context 运行上下文
     * @param isLandscape 是否为横屏方向（默认依据当前屏幕动态判定）
     * @return 当前屏幕下的 X 坐标物理像素值
     */
    fun getPosX(context: Context, isLandscape: Boolean = isLandscape(context)): Int {
        val (w, h) = getRealScreenSize(context)
        val screenWidth = if (isLandscape) maxOf(w, h) else minOf(w, h)
        val density = context.resources.displayMetrics.density
        val defaultViewWidth = (120 * density).toInt()
        val currentViewWidth = floatingView?.width?.takeIf { it > 0 } ?: defaultViewWidth
        val maxX = (screenWidth - currentViewWidth).coerceAtLeast(1)
        val percent = getPercentX(context)
        return (percent * maxX).toInt().coerceIn(0, maxX)
    }

    /**
     * 设置并持久化指定或当前屏幕方向下的水平位置 X 坐标（像素）。
     * 自动转换为全局百分占比并持久化，保证横竖屏切换时自适应。
     *
     * @param context 运行上下文
     * @param x X 坐标像素值
     * @param isLandscape 是否为横屏方向（默认依据当前屏幕动态判定）
     */
    fun setPosX(context: Context, x: Int, isLandscape: Boolean = isLandscape(context)) {
        val (w, h) = getRealScreenSize(context)
        val screenWidth = if (isLandscape) maxOf(w, h) else minOf(w, h)
        val density = context.resources.displayMetrics.density
        val defaultViewWidth = (120 * density).toInt()
        val currentViewWidth = floatingView?.width?.takeIf { it > 0 } ?: defaultViewWidth
        val maxX = (screenWidth - currentViewWidth).coerceAtLeast(1)
        val validX = x.coerceIn(0, maxX)
        val percent = validX.toFloat() / maxX
        setPercentX(context, percent)
        val key = if (isLandscape) KEY_POS_X_LAND else KEY_POS_X
        getPrefs(context).edit().putInt(key, validX).apply()
    }

    /**
     * 获取指定或当前屏幕方向下持久化的垂直位置 Y 坐标（像素）。
     * 基于垂直可用区间百分比推导，横屏下自适应压缩，绝不越界或顶栏溢出。
     *
     * @param context 运行上下文
     * @param isLandscape 是否为横屏方向（默认依据当前屏幕动态判定）
     * @return 当前屏幕下的 Y 坐标物理像素值
     */
    fun getPosY(context: Context, isLandscape: Boolean = isLandscape(context)): Int {
        val (w, h) = getRealScreenSize(context)
        val screenHeight = if (isLandscape) minOf(w, h) else maxOf(w, h)
        val statusBarHeight = getStatusBarHeight(context)
        val density = context.resources.displayMetrics.density
        val defaultViewHeight = (26 * density).toInt()
        val currentViewHeight = floatingView?.height?.takeIf { it > 0 } ?: defaultViewHeight
        val isAlignStatusBar = isAlignStatusBar(context)
        val minY = if (isAlignStatusBar) 0 else statusBarHeight
        val maxY = (screenHeight - currentViewHeight).coerceAtLeast(minY)
        val availHeight = (maxY - minY).coerceAtLeast(1)
        val percent = getPercentY(context)
        return (minY + percent * availHeight).toInt().coerceIn(minY, maxY)
    }

    /**
     * 设置并持久化指定或当前屏幕方向下的垂直位置 Y 坐标（像素）。
     * 自动转换为可用区间百分占比并持久化，保证横竖屏自适应联动。
     *
     * @param context 运行上下文
     * @param y Y 坐标像素值
     * @param isLandscape 是否为横屏方向（默认依据当前屏幕动态判定）
     */
    fun setPosY(context: Context, y: Int, isLandscape: Boolean = isLandscape(context)) {
        val (w, h) = getRealScreenSize(context)
        val screenHeight = if (isLandscape) minOf(w, h) else maxOf(w, h)
        val statusBarHeight = getStatusBarHeight(context)
        val density = context.resources.displayMetrics.density
        val defaultViewHeight = (26 * density).toInt()
        val currentViewHeight = floatingView?.height?.takeIf { it > 0 } ?: defaultViewHeight
        val isAlignStatusBar = isAlignStatusBar(context)
        val minY = if (isAlignStatusBar) 0 else statusBarHeight
        val maxY = (screenHeight - currentViewHeight).coerceAtLeast(minY)
        val availHeight = (maxY - minY).coerceAtLeast(1)
        val validY = y.coerceIn(minY, maxY)
        val percent = (validY - minY).toFloat() / availHeight
        setPercentY(context, percent)
        val key = if (isLandscape) KEY_POS_Y_LAND else KEY_POS_Y
        getPrefs(context).edit().putInt(key, validY).apply()
    }

    /**
     * 获取是否开启文字粗体显示。
     *
     * @param context 运行上下文
     * @return 若开启粗体返回 true（默认开启以增强数字可读性），否则返回 false
     */
    fun isFontBold(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_FONT_BOLD, true)
    }

    /**
     * 设置并持久化是否开启文字粗体显示，并立即刷新悬浮窗外观。
     *
     * @param context 运行上下文
     * @param isBold 是否启用粗体
     */
    fun setFontBold(context: Context, isBold: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_FONT_BOLD, isBold).apply()
        updatePositionAndAppearance(context)
    }

    /**
     * 获取悬浮窗文字大小（单位：sp）。
     *
     * @param context 运行上下文
     * @return 字体大小整数（范围 5 至 20，默认 11）
     */
    fun getFontSize(context: Context): Int {
        return getPrefs(context).getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
    }

    /**
     * 设置并持久化悬浮窗文字大小（单位：sp）。
     *
     * @param context 运行上下文
     * @param sizeSp 字体大小整数（范围 5 至 20）
     */
    fun setFontSize(context: Context, sizeSp: Int) {
        val validSize = sizeSp.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        getPrefs(context).edit().putInt(KEY_FONT_SIZE, validSize).apply()
        updatePositionAndAppearance(context)
    }

    /**
     * 获取水平对齐模式。
     *
     * @param context 运行上下文
     * @return 模式枚举值 [ALIGN_LEFT], [ALIGN_CENTER], [ALIGN_RIGHT], [ALIGN_CUSTOM]
     */
    fun getHorizontalAlign(context: Context): Int {
        return getPrefs(context).getInt(KEY_HORIZONTAL_ALIGN, ALIGN_CENTER)
    }

    /**
     * 设置并持久化水平对齐模式。
     * 若设置为居中对齐，自动重置相对居中偏移量为 0。
     *
     * @param context 运行上下文
     * @param align 对齐模式枚举值
     */
    fun setHorizontalAlign(context: Context, align: Int) {
        getPrefs(context).edit().putInt(KEY_HORIZONTAL_ALIGN, align).apply()
        if (align == ALIGN_CENTER) {
            getPrefs(context).edit().putInt(KEY_OFFSET_X, 0).apply()
        }
        updatePositionAndAppearance(context)
    }

    /**
     * 获取悬浮窗透明度百分比。
     *
     * @param context 运行上下文
     * @return 透明度整数，范围 20 至 100（默认 85）
     */
    fun getOpacity(context: Context): Int {
        return getPrefs(context).getInt(KEY_OPACITY, 85)
    }

    /**
     * 设置并持久化悬浮窗透明度百分比。
     *
     * @param context 运行上下文
     * @param opacity 透明度整数（20 至 100）
     */
    fun setOpacity(context: Context, opacity: Int) {
        getPrefs(context).edit().putInt(KEY_OPACITY, opacity.coerceIn(20, 100)).apply()
        updatePositionAndAppearance(context)
    }

    /**
     * 获取悬浮窗主题明暗对应模式。
     *
     * @param context 运行上下文
     * @return 主题模式枚举值 [THEME_FOLLOW_SYSTEM], [THEME_DARK], [THEME_LIGHT]
     */
    fun getThemeMode(context: Context): Int {
        return getPrefs(context).getInt(KEY_THEME_MODE, THEME_FOLLOW_SYSTEM)
    }

    /**
     * 设置并持久化悬浮窗主题明暗对应模式。
     *
     * @param context 运行上下文
     * @param mode 主题模式枚举值
     */
    fun setThemeMode(context: Context, mode: Int) {
        getPrefs(context).edit().putInt(KEY_THEME_MODE, mode).apply()
        updatePositionAndAppearance(context)
    }

    /**
     * 查询悬浮窗是否开启单位符号显示（W、℃）。
     *
     * @param context 运行上下文
     * @return 若开启显示单位返回 true，未开启返回 false（默认 true）
     */
    fun isShowUnit(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SHOW_UNIT, true)
    }

    /**
     * 设置并持久化悬浮窗是否开启单位符号显示。
     *
     * @param context 运行上下文
     * @param show 是否显示单位
     */
    fun setShowUnit(context: Context, show: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SHOW_UNIT, show).apply()
        updatePositionAndAppearance(context)
    }

    /**
     * 查询悬浮窗是否显示卡片背景与边框。
     *
     * @param context 运行上下文
     * @return 若显示背景和边框返回 true，透明沉浸返回 false（默认 true）
     */
    fun isShowBackground(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SHOW_BACKGROUND, true)
    }

    /**
     * 设置并持久化悬浮窗是否显示卡片背景与边框。
     *
     * @param context 运行上下文
     * @param show 是否显示背景和边框
     */
    fun setShowBackground(context: Context, show: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SHOW_BACKGROUND, show).apply()
        updatePositionAndAppearance(context)
    }

    /**
     * 获取系统顶层状态栏的高度物理像素。
     *
     * @param context 运行上下文
     * @return 状态栏高度（像素）
     */
    @SuppressLint("InternalInsetResource", "DiscouragedApi")
    fun getStatusBarHeight(context: Context): Int {
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) {
            context.resources.getDimensionPixelSize(resId)
        } else {
            (24 * context.resources.displayMetrics.density).toInt()
        }
    }

    /**
     * 启动并展示桌面悬浮窗。
     * 若未授权悬浮窗权限或未开启，则安全返回。
     *
     * @param context 运行上下文
     */
    fun show(context: Context) {
        val appContext = context.applicationContext
        if (!canDrawOverlays(appContext) || !isFloatingEnabled(appContext)) {
            return
        }

        // 若服务已在运行，预先提取最新缓存数据用于首次展示
        cachedPowerWatts = BatteryMonitorService.getLatestCachedPowerWatts()
        cachedTemperatureCelsius = BatteryMonitorService.getLatestCachedTemperature()
        cachedIsCharging = BatteryMonitorService.isCurrentlyCharging(appContext)

        mainHandler.post {
            try {
                if (floatingView != null) {
                    updatePositionAndAppearance(appContext)
                    return@post
                }

                windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val inflater = LayoutInflater.from(appContext)
                val view = inflater.inflate(R.layout.layout_floating_window, null)
                floatingView = view
                viewHolder = FloatingViewHolder(
                    layoutPower = view.findViewById(R.id.layout_floating_power),
                    layoutTemp = view.findViewById(R.id.layout_floating_temp),
                    tvDivider = view.findViewById(R.id.tv_floating_divider),
                    tvPower = view.findViewById(R.id.tv_floating_power),
                    tvTemp = view.findViewById(R.id.tv_floating_temp),
                    ivPower = view.findViewById(R.id.iv_floating_power_icon),
                    ivTemp = view.findViewById(R.id.iv_floating_temp_icon)
                )

                setupTouchDrag(appContext, view)

                val wmParams = WindowManager.LayoutParams().apply {
                    type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_PHONE
                    }
                    format = PixelFormat.TRANSLUCENT
                    flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                            WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        @Suppress("DEPRECATION")
                        flags = flags or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    }

                    // 突破刘海屏/挖孔屏与状态栏限制，允许覆盖在状态栏之上，并避免被系统应用（如设置、电话）强制避让
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                        fitInsetsTypes = 0
                        fitInsetsSides = 0
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        @Suppress("DEPRECATION")
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }

                    gravity = Gravity.TOP or Gravity.START
                    width = WindowManager.LayoutParams.WRAP_CONTENT
                    height = WindowManager.LayoutParams.WRAP_CONTENT
                }
                layoutParams = wmParams

                view.fitsSystemWindows = false
                @Suppress("DEPRECATION")
                view.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE

                applyLayoutPosition(appContext, view, wmParams)
                applyThemeAndOpacity(appContext, view)
                updateViewContent(appContext)

                windowManager?.addView(view, wmParams)

                registerComponentCallbacks(appContext)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 关闭并隐藏桌面悬浮窗，释放 Window 与屏幕方向监听资源。
     *
     * @param context 运行上下文
     */
    fun hide(context: Context) {
        val appContext = context.applicationContext
        mainHandler.post {
            try {
                unregisterComponentCallbacks(appContext)
                floatingView?.let { view ->
                    windowManager?.removeViewImmediate(view)
                }
            } catch (_: Exception) {
            } finally {
                floatingView = null
                viewHolder = null
                layoutParams = null
                windowManager = null
            }
        }
    }

    /**
     * 注册系统配置与屏幕方向变更动态监听。
     *
     * @param context 运行上下文
     */
    private fun registerComponentCallbacks(context: Context) {
        if (!isCallbacksRegistered) {
            try {
                appContextRef = context.applicationContext
                context.applicationContext.registerComponentCallbacks(componentCallbacks)
                isCallbacksRegistered = true
            } catch (_: Exception) {}
        }
    }

    /**
     * 注销系统配置与屏幕方向变更动态监听。
     *
     * @param context 运行上下文
     */
    private fun unregisterComponentCallbacks(context: Context) {
        if (isCallbacksRegistered) {
            try {
                context.applicationContext.unregisterComponentCallbacks(componentCallbacks)
            } catch (_: Exception) {}
            isCallbacksRegistered = false
            appContextRef = null
        }
    }

    /**
     * 依据最新配置同步刷新悬浮窗的坐标位置、状态栏吸附、透明度与明暗主题。
     *
     * @param context 运行上下文
     */
    fun updatePositionAndAppearance(context: Context) {
        val appContext = context.applicationContext
        mainHandler.post {
            val view = floatingView ?: return@post
            val params = layoutParams ?: return@post
            val wm = windowManager ?: return@post

            try {
                applyLayoutPosition(appContext, view, params)
                applyThemeAndOpacity(appContext, view)
                wm.updateViewLayout(view, params)
                updateViewContent(appContext)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 计算并为悬浮窗应用屏幕坐标位置与状态栏显示范围限制。
     * 自适应当前屏幕横竖屏真实物理尺寸，防止横屏越界或居中/对齐错位。
     * 当允许显示在状态栏上时，允许悬浮窗放置于状态栏区域内，不自动贴齐；
     * 当关闭显示在状态栏时，不允许显示在状态栏位置上，强制位于状态栏高度下方。
     *
     * @param context 运行上下文
     * @param view 悬浮窗根视图
     * @param params 窗口布局参数
     */
    private fun applyLayoutPosition(context: Context, view: View, params: WindowManager.LayoutParams) {
        val (screenWidth, screenHeight) = getRealScreenSize(context)
        val statusBarHeight = getStatusBarHeight(context)

        // 测量 View 实际宽高
        view.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val density = context.resources.displayMetrics.density
        val viewWidth = if (view.measuredWidth > 0) view.measuredWidth else (120 * density).toInt()
        val viewHeight = if (view.measuredHeight > 0) view.measuredHeight else (26 * density).toInt()

        // 垂直 Y 坐标计算：
        // 基于垂直可用区间百分比推导，横屏与竖屏均能自适应贴合，绝不越界或顶栏溢出。
        val isAlignStatusBar = isAlignStatusBar(context)
        val percentY = getPercentY(context)
        val minY = if (isAlignStatusBar) 0 else statusBarHeight
        val maxY = (screenHeight - viewHeight).coerceAtLeast(minY)
        val availHeight = (maxY - minY).coerceAtLeast(1)
        params.y = (minY + percentY * availHeight).toInt().coerceIn(minY, maxY)

        // 水平 X 坐标计算
        val alignMode = getHorizontalAlign(context)
        val edgePadding = (16 * density).toInt()
        val maxX = (screenWidth - viewWidth).coerceAtLeast(1)
        val centerX = ((screenWidth - viewWidth) / 2).coerceAtLeast(0)
        params.x = when (alignMode) {
            ALIGN_LEFT -> edgePadding.coerceIn(0, maxX)
            ALIGN_CENTER -> centerX
            ALIGN_RIGHT -> (screenWidth - viewWidth - edgePadding).coerceAtLeast(0)
            ALIGN_CUSTOM -> {
                val percentX = getPercentX(context)
                (percentX * maxX).toInt().coerceIn(0, maxX)
            }
            else -> centerX
        }
    }

    /**
     * 应用悬浮窗透明度、自定义文字大小与明暗主题自适应配色。
     *
     * @param context 运行上下文
     * @param view 悬浮窗根视图
     */
    private fun applyThemeAndOpacity(context: Context, view: View) {
        val opacity = getOpacity(context)
        view.alpha = opacity / 100f

        val themeMode = getThemeMode(context)
        val isDark = when (themeMode) {
            THEME_DARK -> true
            THEME_LIGHT -> false
            else -> isSystemDarkMode(context)
        }

        val vh = viewHolder ?: FloatingViewHolder(
            layoutPower = view.findViewById(R.id.layout_floating_power),
            layoutTemp = view.findViewById(R.id.layout_floating_temp),
            tvDivider = view.findViewById(R.id.tv_floating_divider),
            tvPower = view.findViewById(R.id.tv_floating_power),
            tvTemp = view.findViewById(R.id.tv_floating_temp),
            ivPower = view.findViewById(R.id.iv_floating_power_icon),
            ivTemp = view.findViewById(R.id.iv_floating_temp_icon)
        ).also { viewHolder = it }

        val tvPower = vh.tvPower
        val tvTemp = vh.tvTemp
        val tvDivider = vh.tvDivider
        val ivPower = vh.ivPower
        val ivTemp = vh.ivTemp

        // 动态应用用户设置的文字大小与粗体
        val fontSize = getFontSize(context).toFloat()
        tvPower.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)
        tvTemp.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)
        tvDivider.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize + 0.5f)

        val isBold = isFontBold(context)
        val typeface = if (isBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        tvPower.typeface = typeface
        tvTemp.typeface = typeface
        tvDivider.typeface = typeface

        val showBg = isShowBackground(context)
        if (showBg) {
            if (isDark) {
                view.setBackgroundResource(R.drawable.bg_floating_window_dark)
            } else {
                view.setBackgroundResource(R.drawable.bg_floating_window_light)
            }
            tvPower.setShadowLayer(0f, 0f, 0f, 0)
            tvTemp.setShadowLayer(0f, 0f, 0f, 0)
            tvDivider.setShadowLayer(0f, 0f, 0f, 0)
        } else {
            view.setBackgroundColor(Color.TRANSPARENT)
            val density = context.resources.displayMetrics.density
            val hPad = (4 * density).toInt()
            val vPad = (1 * density).toInt()
            view.setPadding(hPad, vPad, hPad, vPad)
            val shadowColor = if (isDark) Color.parseColor("#80000000") else Color.parseColor("#40000000")
            tvPower.setShadowLayer(2.5f, 0f, 1f, shadowColor)
            tvTemp.setShadowLayer(2.5f, 0f, 1f, shadowColor)
            tvDivider.setShadowLayer(2.5f, 0f, 1f, shadowColor)
        }

        if (isDark) {
            tvPower.setTextColor(Color.WHITE)
            tvTemp.setTextColor(Color.WHITE)
            tvDivider.setTextColor(Color.parseColor("#88FFFFFF"))
            ivPower.setColorFilter(Color.parseColor("#10B981"))
            ivTemp.setColorFilter(Color.parseColor("#F59E0B"))
        } else {
            tvPower.setTextColor(Color.parseColor("#111827"))
            tvTemp.setTextColor(Color.parseColor("#111827"))
            tvDivider.setTextColor(Color.parseColor("#88000000"))
            ivPower.setColorFilter(Color.parseColor("#059669"))
            ivTemp.setColorFilter(Color.parseColor("#D97706"))
        }
    }

    /**
     * 判断当前系统或应用是否处于深色暗黑模式。
     *
     * @param context 运行上下文
     * @return 若处于深色模式返回 true，否则返回 false
     */
    private fun isSystemDarkMode(context: Context): Boolean {
        val appNight = AppCompatDelegate.getDefaultNightMode()
        if (appNight == AppCompatDelegate.MODE_NIGHT_YES) return true
        if (appNight == AppCompatDelegate.MODE_NIGHT_NO) return false

        val nightModeFlags = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * 配置悬浮窗触摸拖拽交互监听，支持在屏幕上任意拖拽移动并在释放时记忆位置。
     * 依据当前横屏或竖屏分别持久化保存坐标，在切换方向时无缝呈现对应最佳视觉位置；
     * 当禁止显示在状态栏时，限制不可拖拽进入状态栏高度范围内；
     * 松手释放时保持实际拖动坐标，绝不篡改用户的状态栏显示配置。
     *
     * @param context 运行上下文
     * @param view 悬浮窗根视图
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchDrag(context: Context, view: View) {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        view.setOnTouchListener { _, event ->
            val params = layoutParams ?: return@setOnTouchListener false
            val wm = windowManager ?: return@setOnTouchListener false
            val (screenWidth, screenHeight) = getRealScreenSize(context)
            val statusBarHeight = getStatusBarHeight(context)
            val allowInStatusBar = isAlignStatusBar(context)
            val isLand = isLandscape(context)
            val minY = if (allowInStatusBar) 0 else statusBarHeight
            val density = context.resources.displayMetrics.density
            val viewH = if (view.height > 0) view.height else (26 * density).toInt()
            val viewW = if (view.width > 0) view.width else (120 * density).toInt()
            val maxY = (screenHeight - viewH).coerceAtLeast(minY)
            val maxX = (screenWidth - viewW).coerceAtLeast(0)

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = (initialX + dx).coerceIn(0, maxX)
                        params.y = (initialY + dy).coerceIn(minY, maxY)
                        try {
                            wm.updateViewLayout(view, params)
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        val finalY = params.y.coerceIn(minY, maxY)
                        val finalX = params.x.coerceIn(0, maxX)
                        params.y = finalY
                        params.x = finalX
                        val currentPercentX = (finalX.toFloat() / maxX.coerceAtLeast(1)).coerceIn(0f, 1f)
                        val availH = (maxY - minY).coerceAtLeast(1)
                        val currentPercentY = ((finalY - minY).toFloat() / availH).coerceIn(0f, 1f)
                        setPercentX(context, currentPercentX)
                        setPercentY(context, currentPercentY)
                        val currentCenterX = ((screenWidth - viewW) / 2).coerceAtLeast(0)
                        val currentOffset = finalX - currentCenterX
                        getPrefs(context).edit()
                            .putInt(KEY_OFFSET_X, currentOffset)
                            .putInt(if (isLand) KEY_POS_X_LAND else KEY_POS_X, finalX)
                            .putInt(if (isLand) KEY_POS_Y_LAND else KEY_POS_Y, finalY)
                            .apply()
                        setHorizontalAlign(context, ALIGN_CUSTOM)
                    }
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 更新悬浮窗内部文本与图标展示。
     * 根据“实时功率显示”与“实时温度显示”子设置项决定各项可见性，
     * 并忠实反映系统底层硬件数据，缺失时如实展示未知占位符。
     *
     * @param context 运行上下文
     */
    private fun updateViewContent(context: Context) {
        val view = floatingView ?: return
        val vh = viewHolder ?: FloatingViewHolder(
            layoutPower = view.findViewById(R.id.layout_floating_power),
            layoutTemp = view.findViewById(R.id.layout_floating_temp),
            tvDivider = view.findViewById(R.id.tv_floating_divider),
            tvPower = view.findViewById(R.id.tv_floating_power),
            tvTemp = view.findViewById(R.id.tv_floating_temp),
            ivPower = view.findViewById(R.id.iv_floating_power_icon),
            ivTemp = view.findViewById(R.id.iv_floating_temp_icon)
        ).also { viewHolder = it }

        val showPower = isShowPower(context)
        val showTemp = isShowTemp(context)
        val showUnit = isShowUnit(context)

        vh.layoutPower.visibility = if (showPower) View.VISIBLE else View.GONE
        vh.layoutTemp.visibility = if (showTemp) View.VISIBLE else View.GONE
        vh.tvDivider.visibility = if (showPower && showTemp) View.VISIBLE else View.GONE

        // 格式化实时功率展示
        if (showPower) {
            vh.tvPower.setUnitText(formatPowerText(cachedPowerWatts, cachedIsCharging, showUnit))
        }

        // 格式化实时温度展示
        if (showTemp) {
            val temp = cachedTemperatureCelsius
            val tempText = if (temp != null && temp > 0f) {
                val formatted = String.format(Locale.getDefault(), "%.1f", temp)
                if (showUnit) "${formatted}℃" else formatted
            } else {
                if (showUnit) "--℃" else "--"
            }
            vh.tvTemp.setUnitText(tempText)
        }
    }

    /**
     * 根据充电与放电状态格式化实时功率字符串，带 +、- 号以区分充、放电。
     *
     * @param powerWatts 瞬时充放电功率瓦特数，缺失传 null
     * @param isCharging 是否处于充电状态
     * @param showUnit 是否显示单位符号（W）
     * @return 格式化后的实时功率展示文本
     */
    fun formatPowerText(powerWatts: Float?, isCharging: Boolean, showUnit: Boolean = true): String {
        return if (powerWatts != null && abs(powerWatts) > 0.05f) {
            val formatted = if (isCharging) {
                if (powerWatts > 0f) {
                    String.format(Locale.getDefault(), "+%.1f", powerWatts)
                } else {
                    String.format(Locale.getDefault(), "-%.1f", abs(powerWatts))
                }
            } else {
                String.format(Locale.getDefault(), "-%.1f", abs(powerWatts))
            }
            if (showUnit) "${formatted}W" else formatted
        } else if (powerWatts != null) {
            val formatted = if (isCharging) "+0.0" else "-0.0"
            if (showUnit) "${formatted}W" else formatted
        } else {
            if (showUnit) "--W" else "--"
        }
    }

    /**
     * 接收后台采样服务数据源推送的最新物理功率、温度与充电状态数据并刷新悬浮窗展示。
     * 悬浮窗作为纯展示组件，自身不维护任何后台定时器或轮询协程，完全由系统采样服务单一数据源驱动更新。
     *
     * @param context 运行上下文
     * @param powerWatts 瞬时充放电功率（瓦特 W），无法获取传 null
     * @param tempCelsius 瞬时电池温度（摄氏度 ℃），无法获取传 null
     * @param isCharging 当前是否处于充电状态，缺失传 null
     */
    fun updateData(context: Context, powerWatts: Float?, tempCelsius: Float?, isCharging: Boolean? = null) {
        if (powerWatts != null) cachedPowerWatts = powerWatts
        if (tempCelsius != null) cachedTemperatureCelsius = tempCelsius
        if (isCharging != null) cachedIsCharging = isCharging
        mainHandler.post {
            updateViewContent(context.applicationContext)
        }
    }
}
