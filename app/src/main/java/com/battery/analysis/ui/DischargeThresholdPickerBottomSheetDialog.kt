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
 * 放电能量模型单次周期有效数据百分比门限选择底部抽屉弹窗（DischargeThresholdPickerBottomSheetDialog）。
 * 完整支持 12 档放电跨度筛选门限（10%, 15%, 20%, 30%, 40%, 50%, 60%, 70%, 80%, 90%, 95%, 100%）。
 */
class DischargeThresholdPickerBottomSheetDialog : BottomSheetDialogFragment() {

    private var onThresholdSelectedListener: ((Int) -> Unit)? = null

    /**
     * 设置门限选择完成后的回调监听器。
     *
     * @param listener 门限选择完成后的回调函数，入参为已选中的电量跨度百分比整型数值
     */
    fun setOnThresholdSelectedListener(listener: (Int) -> Unit) {
        this.onThresholdSelectedListener = listener
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_discharge_threshold_picker, container, false)
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
        val currentVal = manager.getDischargeSocSpanThreshold()
        var selectedPercent = currentVal

        view.findViewById<ImageView>(R.id.btn_close_threshold_dialog)?.setOnClickListener {
            dismissWithSlideDownAnimation()
        }
        view.findViewById<MaterialButton>(R.id.btn_cancel_threshold)?.setOnClickListener {
            dismissWithSlideDownAnimation()
        }

        view.findViewById<MaterialButton>(R.id.btn_confirm_threshold)?.setOnClickListener {
            manager.setDischargeSocSpanThreshold(selectedPercent)
            onThresholdSelectedListener?.invoke(selectedPercent)
            val thresholdText = if (selectedPercent == 100) "100%" else "> $selectedPercent%"
            Toast.makeText(ctx, getString(R.string.toast_discharge_condition_updated), Toast.LENGTH_SHORT).show()
            dismissWithSlideDownAnimation()
        }

        val rv = view.findViewById<RecyclerView>(R.id.rv_threshold_options)
        rv.layoutManager = LinearLayoutManager(ctx)
        val optionAdapter = ThresholdOptionAdapter(thresholdOptionItems, selectedPercent) { clickedItem ->
            selectedPercent = clickedItem.percent
        }
        rv.adapter = optionAdapter

        val selectedIdx = thresholdOptionItems.indexOfFirst { it.percent == selectedPercent }
        if (selectedIdx >= 0) {
            rv.scrollToPosition(selectedIdx)
        }
    }

    private data class ThresholdOptionItem(
        val percent: Int,
        val valueLabel: String,
        val descResId: Int
    )

    private val thresholdOptionItems = listOf(
        ThresholdOptionItem(10, "> 10%", R.string.capacity_thresh_desc_10),
        ThresholdOptionItem(15, "> 15%", R.string.capacity_thresh_desc_15),
        ThresholdOptionItem(20, "> 20%", R.string.capacity_thresh_desc_20),
        ThresholdOptionItem(30, "> 30%", R.string.capacity_thresh_desc_30),
        ThresholdOptionItem(40, "> 40%", R.string.capacity_thresh_desc_40),
        ThresholdOptionItem(50, "> 50%", R.string.capacity_thresh_desc_50),
        ThresholdOptionItem(60, "> 60%", R.string.capacity_thresh_desc_60),
        ThresholdOptionItem(70, "> 70%", R.string.capacity_thresh_desc_70),
        ThresholdOptionItem(80, "> 80%", R.string.capacity_thresh_desc_80),
        ThresholdOptionItem(90, "> 90%", R.string.capacity_thresh_desc_90),
        ThresholdOptionItem(95, "> 95%", R.string.capacity_thresh_desc_95),
        ThresholdOptionItem(100, "100%", R.string.capacity_thresh_desc_100)
    )

    private class ThresholdOptionAdapter(
        private val items: List<ThresholdOptionItem>,
        initialSelected: Int,
        private val onSelected: (ThresholdOptionItem) -> Unit
    ) : RecyclerView.Adapter<ThresholdOptionViewHolder>() {

        private var selectedPercent: Int = initialSelected

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThresholdOptionViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_capacity_threshold_option, parent, false)
            return ThresholdOptionViewHolder(view)
        }

        override fun onBindViewHolder(holder: ThresholdOptionViewHolder, position: Int) {
            val item = items[position]
            val isSelected = (item.percent == selectedPercent)
            holder.bind(item, isSelected) {
                val oldIdx = items.indexOfFirst { it.percent == selectedPercent }
                selectedPercent = item.percent
                val newIdx = position
                if (oldIdx >= 0) notifyItemChanged(oldIdx)
                notifyItemChanged(newIdx)
                onSelected(item)
            }
        }

        override fun getItemCount(): Int = items.size
    }

    private class ThresholdOptionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val rootLayout: View = itemView.findViewById(R.id.layout_threshold_item_root)
        private val tvValue: TextView = itemView.findViewById(R.id.tv_threshold_value)
        private val tvDesc: TextView = itemView.findViewById(R.id.tv_threshold_desc)
        private val ivCheck: ImageView = itemView.findViewById(R.id.iv_threshold_check)

        fun bind(item: ThresholdOptionItem, isSelected: Boolean, onClick: () -> Unit) {
            tvValue.text = item.valueLabel
            tvDesc.setText(item.descResId)

            if (isSelected) {
                rootLayout.setBackgroundResource(R.drawable.bg_threshold_opt_selected)
                tvValue.setTextColor(Color.parseColor("#10b981"))
                tvDesc.setTextColor(Color.parseColor("#10b981"))
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
