package com.battery.analysis.timeline.presentation

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
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

    // 默认勾选“功耗”与“应用”
    private val selectedMetrics = mutableSetOf(TimelineMetric.POWER, TimelineMetric.APP)
    private var listener: OnMetricsChangedListener? = null

    private val metricItems = listOf(
        Pair(TimelineMetric.POWER, "功耗"),
        Pair(TimelineMetric.BATTERY, "电量"),
        Pair(TimelineMetric.TEMPERATURE, "温度"),
        Pair(TimelineMetric.VOLTAGE, "电压"),
        Pair(TimelineMetric.APP, "应用")
    )

    private val textViews = mutableListOf<TextView>()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        initViews()
    }

    /**
     * 初始化 5 个指标选项标签视图。
     */
    private fun initViews() {
        removeAllViews()
        textViews.clear()

        val dp4 = dpToPx(4f)
        val dp8 = dpToPx(8f)

        for (item in metricItems) {
            val (metric, title) = item
            val tv = TextView(context).apply {
                text = "● $title"
                textSize = 13f
                setPadding(dp8, dp4, dp8, dp4)
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp4, 0, dp4, 0)
                }
                setOnClickListener {
                    toggleMetric(metric, notify = true)
                }
            }
            textViews.add(tv)
            addView(tv)
        }
        updateSelectionVisuals()
    }

    /**
     * 切换指定指标项的选中/反选状态。
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
        updateSelectionVisuals()
        if (notify) {
            listener?.onMetricsChanged(selectedMetrics.toSet())
        }
    }

    /**
     * 设置选中的指标集合。
     *
     * @param metrics 目标指标集合
     * @param notify 是否通知外部监听器
     */
    fun setSelectedMetrics(metrics: Set<TimelineMetric>, notify: Boolean = false) {
        selectedMetrics.clear()
        selectedMetrics.addAll(metrics)
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
     * 更新各选项标签的选中/反选视觉状态（选中色彩与置灰半透明）。
     */
    private fun updateSelectionVisuals() {
        for ((index, item) in metricItems.withIndex()) {
            val (metric, _) = item
            val tv = textViews.getOrNull(index) ?: continue
            val isSelected = selectedMetrics.contains(metric)

            if (isSelected) {
                val activeColor = when (metric) {
                    TimelineMetric.POWER -> Color.parseColor("#90CAF9") // 淡蓝功耗
                    TimelineMetric.BATTERY -> Color.parseColor("#4CAF50") // 鲜绿电量
                    TimelineMetric.TEMPERATURE -> Color.parseColor("#FF8A65") // 珊瑚橙温度
                    TimelineMetric.VOLTAGE -> Color.parseColor("#FFD54F") // 金黄电压
                    TimelineMetric.APP -> Color.parseColor("#E0F7FA") // 浅青应用
                }
                tv.setTextColor(activeColor)
                tv.setTypeface(null, Typeface.BOLD)
                tv.alpha = 1.0f
            } else {
                tv.setTextColor(Color.parseColor("#757575"))
                tv.setTypeface(null, Typeface.NORMAL)
                tv.alpha = 0.45f
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
