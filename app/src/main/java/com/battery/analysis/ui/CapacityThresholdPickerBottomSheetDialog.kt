package com.battery.analysis.ui

import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.battery.analysis.R
import com.battery.analysis.manager.BatteryCapacityManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton

/**
 * 电池容量自学习有效数据百分比门限选择底部抽屉弹窗（CapacityThresholdPickerBottomSheetDialog）。
 * 采用与科学诊断主弹窗完全一致的现代化暗黑圆角底部抽屉样式，支持从屏幕底部平滑向上滑入，
 * 包含顶部拖动手柄、自学习门限卡片选项列表及底部操作按钮。
 */
class CapacityThresholdPickerBottomSheetDialog : BottomSheetDialogFragment() {

    private var onThresholdSelectedListener: ((Int) -> Unit)? = null

    /**
     * 设置门限选择完成后的回调监听器。
     *
     * @param listener 门限选择完成后的回调函数，入参为已选中的电量跨度百分比整型数值
     */
    fun setOnThresholdSelectedListener(listener: (Int) -> Unit) {
        this.onThresholdSelectedListener = listener
    }

    /**
     * 创建并加载底部弹窗的根视图。
     *
     * @param inflater 布局填充器，用于将 XML 布局实例化为视图对象
     * @param container 包含弹窗视图的父级视图组，可能为 null
     * @param savedInstanceState 保存的状态数据包，用于界面重建恢复
     * @return 填充完成的根视图实例，可能为 null
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_capacity_threshold_picker, container, false)
    }

    /**
     * 生命周期启动回调，配置底部抽屉的高度、圆角透明衬底与进退场交互动效。
     * 1. 清除二级弹窗自身的 Dim 遮罩，防止与主诊断弹窗的遮罩重叠造成闪烁；
     * 2. 清除底层系统容器的默认不透明直角背景，确保卡片圆角纯粹展示；
     * 3. 统一将高度约束在屏幕物理高度的 75% 左右，并绑定向下滑动退出动效。
     */
    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return

        // 1. 彻底清除二级弹窗自身的半透明遮罩（Dim），避免取消或关闭列表时主界面遮罩闪烁或变黑
        dialog.window?.let { window ->
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setDimAmount(0f)
        }

        // 2. 清除系统底层容器的默认不透明直角背景，消除下方圆角边缘外露出的深色直角衬底
        bottomSheet.background = null
        bottomSheet.setBackgroundColor(Color.TRANSPARENT)

        // 3. 配置弹窗高度为屏幕高度的 75%，确保包含充分可视选项且留有顶部可视感知
        val screenHeight = resources.displayMetrics.heightPixels
        val targetHeight = (screenHeight * 0.75).toInt()
        val layoutParams = bottomSheet.layoutParams
        layoutParams.height = targetHeight
        bottomSheet.layoutParams = layoutParams

        val behavior = BottomSheetBehavior.from(bottomSheet)
        behavior.maxHeight = targetHeight
        behavior.peekHeight = targetHeight
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        behavior.skipCollapsed = true
        behavior.isHideable = true

        // 4. 监听状态：滑动到屏幕下方完全隐藏时再解构弹窗
        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                    dismissAllowingStateLoss()
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
            }
        })

        // 5. 接管外部遮罩点击，关闭系统原生 cancel 竞争，统一使用向下滑出取消动画
        dialog.setCanceledOnTouchOutside(false)
        val touchOutside = dialog.findViewById<View>(com.google.android.material.R.id.touch_outside)
        touchOutside?.setOnClickListener {
            dismissWithSlideDownAnimation()
        }

        // 6. 拦截返回键，同样统一使用向下滑出取消动画
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                dismissWithSlideDownAnimation()
                true
            } else {
                false
            }
        }
    }

    /**
     * 以与手势向下滑动完全一致的平滑下落动画退出底部弹窗。
     */
    private fun dismissWithSlideDownAnimation() {
        val dialog = dialog as? BottomSheetDialog
        val bottomSheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        if (bottomSheet != null && isAdded) {
            val behavior = BottomSheetBehavior.from(bottomSheet)
            behavior.isHideable = true
            behavior.state = BottomSheetBehavior.STATE_HIDDEN
        } else {
            dismissAllowingStateLoss()
        }
    }

    /**
     * 视图构建完成后的回调，用于初始化门限选项列表与绑定确定/取消交互事件。
     *
     * @param view 已创建的根视图实例
     * @param savedInstanceState 保存的状态数据包，可能为 null
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = context ?: return
        val manager = BatteryCapacityManager.getInstance(ctx)
        val currentVal = manager.getEffectiveSocSpanThreshold()
        var selectedPercent = currentVal

        // 绑定顶部关闭与底部取消按钮：统一触发平滑下落退出动效
        view.findViewById<ImageView>(R.id.btn_close_threshold_dialog)?.setOnClickListener {
            dismissWithSlideDownAnimation()
        }
        view.findViewById<MaterialButton>(R.id.btn_cancel_threshold)?.setOnClickListener {
            dismissWithSlideDownAnimation()
        }

        // 绑定底部确定按钮：保存有效跨度门限并刷新上层展示
        view.findViewById<MaterialButton>(R.id.btn_confirm_threshold)?.setOnClickListener {
            manager.setEffectiveSocSpanThreshold(selectedPercent)
            onThresholdSelectedListener?.invoke(selectedPercent)
            val thresholdText = if (selectedPercent == 100) "100%" else "> $selectedPercent%"
            Toast.makeText(ctx, "已设为门限 $thresholdText，已即时重新计算模型", Toast.LENGTH_SHORT).show()
            dismissWithSlideDownAnimation()
        }

        // 初始化 RecyclerView 与适配器
        val rv = view.findViewById<RecyclerView>(R.id.rv_threshold_options)
        rv.layoutManager = LinearLayoutManager(ctx)
        val optionAdapter = ThresholdOptionAdapter(thresholdOptionItems, selectedPercent) { clickedItem ->
            selectedPercent = clickedItem.percent
        }
        rv.adapter = optionAdapter

        // 自动居中滚动至当前已选中的选项位置
        val selectedIdx = thresholdOptionItems.indexOfFirst { it.percent == selectedPercent }
        if (selectedIdx >= 0) {
            rv.scrollToPosition(selectedIdx)
        }
    }

    /**
     * 有效数据门限选项条目数据封装实体。
     *
     * @property percent 电量跨度阈值百分比整型数值
     * @property valueLabel 界面显示的跨度数值标签（例如 "> 20%"）
     * @property desc 对应门限的技术释义与精度权衡说明
     */
    private data class ThresholdOptionItem(
        val percent: Int,
        val valueLabel: String,
        val desc: String
    )

    private val thresholdOptionItems = listOf(
        ThresholdOptionItem(10, "> 10%", "极速建模 · 容纳浅充浅放"),
        ThresholdOptionItem(15, "> 15%", "日常均衡模式"),
        ThresholdOptionItem(20, "> 20%", "推荐 · 精度与收敛最佳平衡"),
        ThresholdOptionItem(25, "> 25%", "常态充放跨度"),
        ThresholdOptionItem(30, "> 30%", "高精度 · 减少量化离散"),
        ThresholdOptionItem(40, "> 40%", "深度自学习"),
        ThresholdOptionItem(50, "> 50%", "半程以上 · 高置信度"),
        ThresholdOptionItem(60, "> 60%", "大跨度充放"),
        ThresholdOptionItem(70, "> 70%", "高纯度样本"),
        ThresholdOptionItem(80, "> 80%", "深度充放 · 极高精度"),
        ThresholdOptionItem(90, "> 90%", "超深度充放循环"),
        ThresholdOptionItem(100, "100%", "完整 0%~100% 深度充放")
    )

    /**
     * 有效数据门限选项列表适配器（ThresholdOptionAdapter）。
     *
     * @param items 门限选项数据列表
     * @param initialSelected 初始选中的门限百分比数值
     * @param onSelected 用户点击选中某个选项时的回调函数
     */
    private class ThresholdOptionAdapter(
        private val items: List<ThresholdOptionItem>,
        initialSelected: Int,
        private val onSelected: (ThresholdOptionItem) -> Unit
    ) : RecyclerView.Adapter<ThresholdOptionViewHolder>() {

        var selectedPercent: Int = initialSelected

        /**
         * 创建选项列表项的视图持有者。
         *
         * @param parent 包含条目视图的父级视图组
         * @param viewType 视图类型标识
         * @return 实例化后的 [ThresholdOptionViewHolder]
         */
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThresholdOptionViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_capacity_threshold_option, parent, false)
            return ThresholdOptionViewHolder(v)
        }

        /**
         * 将指定索引的门限选项数据与高亮状态绑定到视图持有者。
         *
         * @param holder 目标视图持有者
         * @param position 列表项索引位置
         */
        override fun onBindViewHolder(holder: ThresholdOptionViewHolder, position: Int) {
            val item = items[position]
            val isSelected = item.percent == selectedPercent

            holder.tvValue.text = item.valueLabel
            holder.tvDesc.text = item.desc

            if (isSelected) {
                holder.root.setBackgroundResource(R.drawable.bg_threshold_opt_selected)
                holder.tvValue.setTextColor(Color.parseColor("#10b981"))
                holder.ivCheck.visibility = View.VISIBLE
            } else {
                holder.root.setBackgroundResource(R.drawable.bg_threshold_opt_normal)
                holder.tvValue.setTextColor(Color.parseColor("#f1f5f9"))
                holder.ivCheck.visibility = View.GONE
            }

            holder.root.setOnClickListener {
                selectedPercent = item.percent
                notifyDataSetChanged()
                onSelected(item)
            }
        }

        /**
         * 获取门限选项条目总数。
         *
         * @return 列表项数量整型数值
         */
        override fun getItemCount(): Int = items.size
    }

    /**
     * 门限选项视图持有者（ThresholdOptionViewHolder）。
     *
     * @param view 列表条目根视图
     */
    private class ThresholdOptionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val root: View = view.findViewById(R.id.layout_threshold_item_root)
        val tvValue: TextView = view.findViewById(R.id.tv_threshold_value)
        val tvDesc: TextView = view.findViewById(R.id.tv_threshold_desc)
        val ivCheck: ImageView = view.findViewById(R.id.iv_threshold_check)
    }
}
