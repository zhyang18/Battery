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
import com.battery.analysis.domain.capacity.model.DataPurityCondition
import com.battery.analysis.manager.BatteryCapacityManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton

/**
 * 放电样本数据纯度（实测覆盖率）门限选择底部抽屉弹窗（DataPurityPickerBottomSheetDialog）。
 * 遵循技术评审 P2-B3 规范：将分箱实测覆盖率门限与样本质量拦截准则提取为独立、可配置、自校验的显式条件设置，
 * 默认要求物理实测覆盖率 ≥80% 以确保数据纯净。
 */
class DataPurityPickerBottomSheetDialog : BottomSheetDialogFragment() {

    private var onPuritySelectedListener: ((DataPurityCondition) -> Unit)? = null

    fun setOnPuritySelectedListener(listener: (DataPurityCondition) -> Unit) {
        this.onPuritySelectedListener = listener
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_data_purity_picker, container, false)
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
        val manager = BatteryCapacityManager.getInstance(ctx)
        val currentCondition = manager.getDataPurityCondition()
        var selectedItem = purityOptions.firstOrNull {
            kotlin.math.abs(it.minCoverage - currentCondition.minMeasuredCoveragePercent) < 0.1
        } ?: purityOptions.first()

        view.findViewById<ImageView>(R.id.btn_close_purity_dialog)?.setOnClickListener {
            dismissWithSlideDownAnimation()
        }
        view.findViewById<MaterialButton>(R.id.btn_cancel_purity)?.setOnClickListener {
            dismissWithSlideDownAnimation()
        }

        view.findViewById<MaterialButton>(R.id.btn_confirm_purity)?.setOnClickListener {
            val newCondition = DataPurityCondition(
                minMeasuredCoveragePercent = selectedItem.minCoverage,
                rejectPoorQuality = true
            )
            manager.setDataPurityCondition(newCondition)
            onPuritySelectedListener?.invoke(newCondition)
            Toast.makeText(ctx, getString(R.string.toast_data_purity_updated, getString(selectedItem.labelResId)), Toast.LENGTH_SHORT).show()
            dismissWithSlideDownAnimation()
        }

        val rv = view.findViewById<RecyclerView>(R.id.rv_purity_options)
        rv.layoutManager = LinearLayoutManager(ctx)
        val adapter = PurityOptionAdapter(purityOptions, selectedItem) { clickedItem ->
            selectedItem = clickedItem
        }
        rv.adapter = adapter

        val selectedIdx = purityOptions.indexOf(selectedItem)
        if (selectedIdx >= 0) {
            rv.scrollToPosition(selectedIdx)
        }
    }

    private data class PurityOptionItem(
        val minCoverage: Double,
        val labelResId: Int,
        val descResId: Int
    )

    private val purityOptions = listOf(
        PurityOptionItem(80.0, R.string.data_purity_opt_80, R.string.data_purity_desc_80),
        PurityOptionItem(70.0, R.string.data_purity_opt_70, R.string.data_purity_desc_70),
        PurityOptionItem(60.0, R.string.data_purity_opt_60, R.string.data_purity_desc_60),
        PurityOptionItem(50.0, R.string.data_purity_opt_50, R.string.data_purity_desc_50),
        PurityOptionItem(40.0, R.string.data_purity_opt_40, R.string.data_purity_desc_40),
        PurityOptionItem(30.0, R.string.data_purity_opt_30, R.string.data_purity_desc_30),
        PurityOptionItem(10.0, R.string.data_purity_opt_10, R.string.data_purity_desc_10)
    )

    private class PurityOptionAdapter(
        private val items: List<PurityOptionItem>,
        initialSelected: PurityOptionItem,
        private val onSelected: (PurityOptionItem) -> Unit
    ) : RecyclerView.Adapter<PurityOptionViewHolder>() {

        var selectedItem: PurityOptionItem = initialSelected

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PurityOptionViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_capacity_threshold_option, parent, false)
            return PurityOptionViewHolder(v)
        }

        override fun onBindViewHolder(holder: PurityOptionViewHolder, position: Int) {
            val item = items[position]
            val isSelected = item == selectedItem

            holder.tvValue.setText(item.labelResId)
            holder.tvDesc.setText(item.descResId)

            if (isSelected) {
                holder.root.setBackgroundResource(R.drawable.bg_threshold_opt_selected)
                holder.tvValue.setTextColor(Color.parseColor("#8b5cf6"))
                holder.ivCheck.visibility = View.VISIBLE
                holder.ivCheck.setColorFilter(Color.parseColor("#8b5cf6"))
            } else {
                holder.root.setBackgroundResource(R.drawable.bg_threshold_opt_normal)
                holder.tvValue.setTextColor(Color.parseColor("#f1f5f9"))
                holder.ivCheck.visibility = View.GONE
            }

            holder.root.setOnClickListener {
                selectedItem = item
                notifyDataSetChanged()
                onSelected(item)
            }
        }

        override fun getItemCount(): Int = items.size
    }

    private class PurityOptionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val root: View = view.findViewById(R.id.layout_threshold_item_root)
        val tvValue: TextView = view.findViewById(R.id.tv_threshold_value)
        val tvDesc: TextView = view.findViewById(R.id.tv_threshold_desc)
        val ivCheck: ImageView = view.findViewById(R.id.iv_threshold_check)
    }
}
