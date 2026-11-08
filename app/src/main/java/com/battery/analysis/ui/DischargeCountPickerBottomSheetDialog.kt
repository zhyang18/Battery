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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_discharge_count_picker, container, false)
    }

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

        view.findViewById<MaterialButton>(R.id.btn_confirm_count)?.setOnClickListener {
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

    private class CountOptionAdapter(
        private val items: List<CountOptionItem>,
        initialSelected: Int,
        private val onSelected: (CountOptionItem) -> Unit
    ) : RecyclerView.Adapter<CountOptionViewHolder>() {

        private var selectedCount: Int = initialSelected

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CountOptionViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_capacity_threshold_option, parent, false)
            return CountOptionViewHolder(view)
        }

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

        override fun getItemCount(): Int = items.size
    }

    private class CountOptionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val rootLayout: View = itemView.findViewById(R.id.layout_threshold_item_root)
        private val tvValue: TextView = itemView.findViewById(R.id.tv_threshold_value)
        private val tvDesc: TextView = itemView.findViewById(R.id.tv_threshold_desc)
        private val ivCheck: ImageView = itemView.findViewById(R.id.iv_threshold_check)

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
