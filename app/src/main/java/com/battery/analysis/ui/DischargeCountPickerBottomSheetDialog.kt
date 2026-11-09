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
import com.battery.analysis.manager.PowerUsageManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton

/**
 * 放电能量模型有效数据条数时效窗口选择底部抽屉弹窗（DischargeCountPickerBottomSheetDialog）。
 * 支持设置最近 3/5/10/15/20/30 条或全部历史数据，杜绝过往老化数据污染当前健康度。
 */
class DischargeCountPickerBottomSheetDialog : BottomSheetDialogFragment() {

    private var onCountSelectedListener: ((Int) -> Unit)? = null

    /**
     * 设置有效数据条数选择完成后的回调监听器。
     *
     * @param listener 回调函数，入参为选中的条数限制（0 表示全部，其余为正整数）
     */
    fun setOnCountSelectedListener(listener: (Int) -> Unit) {
        this.onCountSelectedListener = listener
    }

    /**
     * 创建并加载有效条数选择底部抽屉弹窗的根视图。
     *
     * @param inflater 布局填充器
     * @param container 父容器视图
     * @param savedInstanceState 保存的状态数据包
     * @return 实例化的根视图，可能为 null
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_discharge_count_picker, container, false)
    }

    /**
     * 初始化配置底部抽屉的高级样式与全量展开行为。
     */
    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return

        dialog.window?.let { window ->
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setDimAmount(0f)
        }

        bottomSheet.background = null
        bottomSheet.setBackgroundColor(Color.TRANSPARENT)

        val screenHeight = resources.displayMetrics.heightPixels
        val targetHeight = (screenHeight * 0.70).toInt()
        val layoutParams = bottomSheet.layoutParams
        layoutParams.height = targetHeight
        bottomSheet.layoutParams = layoutParams

        val behavior = BottomSheetBehavior.from(bottomSheet)
        behavior.maxHeight = targetHeight
        behavior.peekHeight = targetHeight
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        behavior.skipCollapsed = true
        behavior.isHideable = true

        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                    dismissAllowingStateLoss()
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {}
        })

        dialog.setCanceledOnTouchOutside(false)
        val touchOutside = dialog.findViewById<View>(com.google.android.material.R.id.touch_outside)
        touchOutside?.setOnClickListener {
            dismissWithSlideDownAnimation()
        }

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
     * 视图构建完成后的回调，用于初始化有效数据条数选项列表与绑定确定/取消交互事件。
     *
     * @param view 已创建的根视图实例
     * @param savedInstanceState 保存的状态数据包，可能为 null
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = context ?: return
        val manager = PowerUsageManager.getInstance(ctx)
        val currentVal = manager.getDischargeSampleCountLimit()
        var selectedCount = currentVal

        view.findViewById<ImageView>(R.id.btn_close_count_dialog)?.setOnClickListener {
            dismissWithSlideDownAnimation()
        }
        view.findViewById<MaterialButton>(R.id.btn_cancel_count)?.setOnClickListener {
            dismissWithSlideDownAnimation()
        }

        var isDismissing = false
        view.findViewById<MaterialButton>(R.id.btn_confirm_count)?.setOnClickListener {
            if (isDismissing) return@setOnClickListener
            // 若用户未做任何变更，直接退出，避免冗余重算与刷新开销
            if (selectedCount == currentVal) {
                dismissWithSlideDownAnimation()
                return@setOnClickListener
            }
            isDismissing = true
            manager.setDischargeSampleCountLimit(selectedCount)
            onCountSelectedListener?.invoke(selectedCount)
            Toast.makeText(ctx, getString(R.string.toast_discharge_condition_updated), Toast.LENGTH_SHORT).show()
            dismissWithSlideDownAnimation()
        }

        val rv = view.findViewById<RecyclerView>(R.id.rv_count_options)
        rv.layoutManager = LinearLayoutManager(ctx)
        val optionAdapter = CountOptionAdapter(countOptionItems, selectedCount) { clickedItem ->
            selectedCount = clickedItem.count
        }
        rv.adapter = optionAdapter

        val selectedIdx = countOptionItems.indexOfFirst { it.count == selectedCount }
        if (selectedIdx >= 0) {
            rv.scrollToPosition(selectedIdx)
        }
    }

    /**
     * 条数选项列表项实体数据类。
     *
     * @property count 时效窗口最大样本条数（0 表示全部）
     * @property valueLabel 界面显示的条数文案（如 "最近 10 条 (推荐)"）
     * @property descResId 对应的本地化描述字符串资源 ID
     */
    private data class CountOptionItem(
        val count: Int,
        val valueLabel: String,
        val descResId: Int
    )

    private val countOptionItems = listOf(
        CountOptionItem(3, "最近 3 条", R.string.discharge_count_desc_3),
        CountOptionItem(5, "最近 5 条", R.string.discharge_count_desc_5),
        CountOptionItem(10, "最近 10 条 (推荐)", R.string.discharge_count_desc_10),
        CountOptionItem(15, "最近 15 条", R.string.discharge_count_desc_15),
        CountOptionItem(20, "最近 20 条", R.string.discharge_count_desc_20),
        CountOptionItem(30, "最近 30 条", R.string.discharge_count_desc_30),
        CountOptionItem(0, "全部数据", R.string.discharge_count_desc_all)
    )

    /**
     * 有效数据条数选项列表 RecyclerView 适配器。
     *
     * @param items 条数选项列表数据源
     * @param initialSelected 初始选中的有效条数限制
     * @param onSelected 选项单选选中后的回调函数
     */
    private class CountOptionAdapter(
        private val items: List<CountOptionItem>,
        initialSelected: Int,
        private val onSelected: (CountOptionItem) -> Unit
    ) : RecyclerView.Adapter<CountOptionViewHolder>() {

        private var selectedCount: Int = initialSelected

        /**
         * 创建列表项 ViewHolder 实例。
         *
         * @param parent 父容器视图
         * @param viewType 视图类型
         * @return 实例化的 [CountOptionViewHolder]
         */
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CountOptionViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_capacity_threshold_option, parent, false)
            return CountOptionViewHolder(view)
        }

        /**
         * 绑定列表项视图数据与选中状态。
         *
         * @param holder 目标 ViewHolder 实例
         * @param position 列表项索引位置
         */
        override fun onBindViewHolder(holder: CountOptionViewHolder, position: Int) {
            val item = items[position]
            val isSelected = (item.count == selectedCount)
            holder.bind(item, isSelected) {
                val oldIdx = items.indexOfFirst { it.count == selectedCount }
                selectedCount = item.count
                val newIdx = position
                if (oldIdx >= 0) notifyItemChanged(oldIdx)
                notifyItemChanged(newIdx)
                onSelected(item)
            }
        }

        /**
         * 获取列表项总数量。
         *
         * @return 数据项总数
         */
        override fun getItemCount(): Int = items.size
    }

    /**
     * 有效条数选项列表项视图持有者。
     *
     * @param itemView 列表项根视图
     */
    private class CountOptionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val rootLayout: View = itemView.findViewById(R.id.layout_threshold_item_root)
        private val tvValue: TextView = itemView.findViewById(R.id.tv_threshold_value)
        private val tvDesc: TextView = itemView.findViewById(R.id.tv_threshold_desc)
        private val ivCheck: ImageView = itemView.findViewById(R.id.iv_threshold_check)

        /**
         * 绑定单条有效条数数据并设置高亮与选中对勾。
         *
         * @param item 条数数据实体
         * @param isSelected 是否处于被选中状态
         * @param onClick 项点击回调函数
         */
        fun bind(item: CountOptionItem, isSelected: Boolean, onClick: () -> Unit) {
            tvValue.text = item.valueLabel
            tvDesc.setText(item.descResId)

            if (isSelected) {
                rootLayout.setBackgroundResource(R.drawable.bg_threshold_opt_selected)
                tvValue.setTextColor(Color.parseColor("#3b82f6"))
                tvDesc.setTextColor(Color.parseColor("#3b82f6"))
                ivCheck.visibility = View.VISIBLE
            } else {
                rootLayout.setBackgroundResource(R.drawable.bg_threshold_opt_normal)
                tvValue.setTextColor(Color.parseColor("#f1f5f9"))
                tvDesc.setTextColor(Color.parseColor("#94a3b8"))
                ivCheck.visibility = View.GONE
            }

            rootLayout.setOnClickListener { onClick() }
        }
    }
}
