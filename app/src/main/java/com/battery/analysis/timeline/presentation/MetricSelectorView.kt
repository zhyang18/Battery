package com.battery.analysis.timeline.presentation

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 功耗时间轴底部指标多选/反选选择器组件。
 * 允许用户对“功耗”、“电量”、“温度”、“电压”、“应用”五维数据独立进行点选或反选（多选叠加模式），
 * 图表将根据勾选状态实时联动呈现对应的曲线与 App 图标层。
 *
 * @param context Android 上下文环境
 * @param attrs XML 属性集合
 * @param defStyleAttr 默认样式属性
 */
class MetricSelectorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /**
     * 指标多选集合变化监听器接口。
     */
    fun interface OnMetricsChangedListener {
        /**
         * 当选中的指标集合发生改变时触发。
         *
         * @param selectedMetrics 最新选中的指标集合 [Set<TimelineMetric>]
         */
        fun onMetricsChanged(selectedMetrics: Set<TimelineMetric>)
    }

    companion object {
        private const val PREFS_NAME = "timeline_metrics_prefs"
        private const val KEY_SAVED_METRICS = "saved_timeline_metrics"
    }

    // 默认通过本地持久化恢复用户上次选择（若首次进入则默认勾选“功耗”与“应用”）
    private val selectedMetrics = mutableSetOf<TimelineMetric>()
    private var listener: OnMetricsChangedListener? = null

    private val metricItems = listOf(
        Pair(TimelineMetric.BATTERY, "电量"),
        Pair(TimelineMetric.POWER, "功耗"),
        Pair(TimelineMetric.TEMPERATURE, "温度"),
        Pair(TimelineMetric.VOLTAGE, "电压"),
        Pair(TimelineMetric.APP, "应用")
    )

    private val itemLayouts = mutableListOf<LinearLayout>()
    private val dotViews = mutableListOf<View>()
    private val textViews = mutableListOf<TextView>()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        selectedMetrics.addAll(loadSavedMetrics())
        initViews()
    }

    /**
     * 从本地持久化存储加载保存的指标集合。
     *
     * @return 还原出的指标集合 [Set<TimelineMetric>]
     */
    private fun loadSavedMetrics(): Set<TimelineMetric> {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = sp.getStringSet(KEY_SAVED_METRICS, null)
        if (saved != null && saved.isNotEmpty()) {
            val result = mutableSetOf<TimelineMetric>()
            for (name in saved) {
                try {
                    result.add(TimelineMetric.valueOf(name))
                } catch (_: Exception) {}
            }
            if (result.isNotEmpty()) {
                return result
            }
        }
        return setOf(TimelineMetric.POWER, TimelineMetric.APP)
    }

    /**
     * 将当前选中的指标集合持久化保存至本地存储。
     *
     * @param metrics 待保存的指标集合 [Set<TimelineMetric>]
     */
    private fun saveMetrics(metrics: Set<TimelineMetric>) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val nameSet = metrics.map { it.name }.toSet()
        sp.edit().putStringSet(KEY_SAVED_METRICS, nameSet).apply()
    }

    /**
     * 初始化 5 个指标选项标签视图。
     * 前置圆角点与充电趋势图规范严格对齐（9dp x 9dp，圆角 2.5dp，间距 5dp）。
     */
    private fun initViews() {
        removeAllViews()
        itemLayouts.clear()
        dotViews.clear()
        textViews.clear()

        val dp4 = dpToPx(4f)
        val dp5 = dpToPx(5f)
        val dp9 = dpToPx(9f)

        for (item in metricItems) {
            val (metric, title) = item
            val itemContainer = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(0, dp4, 0, dp4)
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    toggleMetric(metric, notify = true)
                }
            }

            val dotView = View(context).apply {
                layoutParams = LayoutParams(dp9, dp9)
            }

            val tv = TextView(context).apply {
                text = title
                textSize = 12f
                isSingleLine = true
                maxLines = 1
                includeFontPadding = false
                gravity = Gravity.CENTER
                layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp5
                }
            }

            itemContainer.addView(dotView)
            itemContainer.addView(tv)

            itemLayouts.add(itemContainer)
            dotViews.add(dotView)
            textViews.add(tv)
            addView(itemContainer)
        }
        updateSelectionVisuals()
    }

    /**
     * 切换指定指标项的选中/反选状态并自动同步持久化。
     *
     * @param metric 目标指标
     * @param notify 是否通知外部监听器
     */
    fun toggleMetric(metric: TimelineMetric, notify: Boolean = false) {
        if (selectedMetrics.contains(metric)) {
            // 如果只有 1 个被选中，允许取消（或保留至少 1 个，根据用户习惯，允许自由取消）
            selectedMetrics.remove(metric)
        } else {
            selectedMetrics.add(metric)
        }
        saveMetrics(selectedMetrics)
        updateSelectionVisuals()
        if (notify) {
            listener?.onMetricsChanged(selectedMetrics.toSet())
        }
    }

    /**
     * 设置选中的指标集合并自动同步持久化。
     *
     * @param metrics 目标指标集合
     * @param notify 是否通知外部监听器
     */
    fun setSelectedMetrics(metrics: Set<TimelineMetric>, notify: Boolean = false) {
        selectedMetrics.clear()
        selectedMetrics.addAll(metrics)
        saveMetrics(selectedMetrics)
        updateSelectionVisuals()
        if (notify) {
            listener?.onMetricsChanged(selectedMetrics.toSet())
        }
    }

    /**
     * 获取当前所有被选中的指标集合。
     *
     * @return 当前选中的指标集合 [Set<TimelineMetric>]
     */
    fun getSelectedMetrics(): Set<TimelineMetric> {
        return selectedMetrics.toSet()
    }

    /**
     * 设置指标变化监听器。
     *
     * @param listener 回调监听器
     */
    fun setOnMetricsChangedListener(listener: OnMetricsChangedListener?) {
        this.listener = listener
    }

    /**
     * 更新各选项标签的选中/反选视觉状态（圆角点色彩、选中高亮与置灰半透明）。
     */
    private fun updateSelectionVisuals() {
        val dp2_5 = dpToPx(2.5f).toFloat()
        for ((index, item) in metricItems.withIndex()) {
            val (metric, _) = item
            val itemContainer = itemLayouts.getOrNull(index) ?: continue
            val dotView = dotViews.getOrNull(index) ?: continue
            val tv = textViews.getOrNull(index) ?: continue
            val isSelected = selectedMetrics.contains(metric)

            val activeColor = when (metric) {
                TimelineMetric.BATTERY -> Color.parseColor("#4CAF50") // 鲜绿电量
                TimelineMetric.POWER -> Color.parseColor("#90CAF9") // 淡蓝功耗
                TimelineMetric.TEMPERATURE -> Color.parseColor("#FF8A65") // 珊瑚橙温度
                TimelineMetric.VOLTAGE -> Color.parseColor("#FFD54F") // 金黄电压
                TimelineMetric.APP -> Color.parseColor("#E0F7FA") // 浅青应用
            }

            val dotDrawable = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp2_5
                setColor(activeColor)
            }
            dotView.background = dotDrawable

            if (isSelected) {
                itemContainer.alpha = 1.0f
                tv.setTextColor(activeColor)
                tv.setTypeface(null, Typeface.BOLD)
            } else {
                itemContainer.alpha = 0.45f
                tv.setTextColor(Color.parseColor("#757575"))
                tv.setTypeface(null, Typeface.NORMAL)
            }
        }
    }

    /**
     * 将 dp 数值转换为当前屏幕像素 px。
     *
     * @param dp dp 标量
     * @return 像素值 px
     */
    private fun dpToPx(dp: Float): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
