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
import com.battery.analysis.domain.capacity.model.StartDischargeCondition
import com.battery.analysis.domain.capacity.model.StartDischargePolicy
import com.battery.analysis.manager.BatteryCapacityManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton

/**
 * 放电样本起始 SOC 政策选择底部抽屉弹窗（StartDischargePolicyBottomSheetDialog）。
 * 遵循技术评审铁律：起始电量政策与门限必须严格显式化与可配置，
 * 默认要求满电起步（≥95%）以大幅抑制浅放电分母放大与测量噪声。
 */
class StartDischargePolicyBottomSheetDialog : BottomSheetDialogFragment() {

    private var onPolicySelectedListener: ((StartDischargeCondition) -> Unit)? = null

    fun setOnPolicySelectedListener(listener: (StartDischargeCondition) -> Unit) {
        this.onPolicySelectedListener = listener
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_start_discharge_policy_picker, container, false)
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
        val currentCondition = manager.getStartDischargeCondition()
        var selectedItem = policyOptions.firstOrNull {
            it.policy == currentCondition.policy && (it.policy == StartDischargePolicy.ANY_SOC || it.minStartSoc == currentCondition.minStartSocPercent)
        } ?: policyOptions.first()

        view.findViewById<ImageView>(R.id.btn_close_policy_dialog)?.setOnClickListener {
            dismissWithSlideDownAnimation()
        }
        view.findViewById<MaterialButton>(R.id.btn_cancel_policy)?.setOnClickListener {
            dismissWithSlideDownAnimation()
        }

        view.findViewById<MaterialButton>(R.id.btn_confirm_policy)?.setOnClickListener {
            val newCondition = StartDischargeCondition(
                policy = selectedItem.policy,
                minStartSocPercent = selectedItem.minStartSoc
            )
            manager.setStartDischargeCondition(newCondition)
            onPolicySelectedListener?.invoke(newCondition)
            Toast.makeText(ctx, getString(R.string.toast_start_discharge_policy_updated, selectedItem.valueLabel), Toast.LENGTH_SHORT).show()
            dismissWithSlideDownAnimation()
        }

        val rv = view.findViewById<RecyclerView>(R.id.rv_policy_options)
        rv.layoutManager = LinearLayoutManager(ctx)
        val adapter = PolicyOptionAdapter(policyOptions, selectedItem) { clickedItem ->
            selectedItem = clickedItem
        }
        rv.adapter = adapter

        val selectedIdx = policyOptions.indexOf(selectedItem)
        if (selectedIdx >= 0) {
            rv.scrollToPosition(selectedIdx)
        }
    }

    private data class PolicyOptionItem(
        val policy: StartDischargePolicy,
        val minStartSoc: Int,
        val valueLabel: String,
        val descResId: Int
    )

    private val policyOptions = listOf(
        PolicyOptionItem(StartDischargePolicy.START_FROM_100_FULL, 95, "满电起步 (≥95%)", R.string.start_discharge_policy_desc_95),
        PolicyOptionItem(StartDischargePolicy.START_FROM_100_FULL, 90, "满电起步 (≥90%)", R.string.start_discharge_policy_desc_90),
        PolicyOptionItem(StartDischargePolicy.START_FROM_100_FULL, 85, "高电量起步 (≥85%)", R.string.start_discharge_policy_desc_85),
        PolicyOptionItem(StartDischargePolicy.ANY_SOC, 0, "任意电量起步", R.string.start_discharge_policy_desc_any)
    )

    private class PolicyOptionAdapter(
        private val items: List<PolicyOptionItem>,
        initialSelected: PolicyOptionItem,
        private val onSelected: (PolicyOptionItem) -> Unit
    ) : RecyclerView.Adapter<PolicyOptionViewHolder>() {

        var selectedItem: PolicyOptionItem = initialSelected

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PolicyOptionViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_capacity_threshold_option, parent, false)
            return PolicyOptionViewHolder(v)
        }

        override fun onBindViewHolder(holder: PolicyOptionViewHolder, position: Int) {
            val item = items[position]
            val isSelected = item == selectedItem

            holder.tvValue.text = item.valueLabel
            holder.tvDesc.setText(item.descResId)

            if (isSelected) {
                holder.root.setBackgroundResource(R.drawable.bg_threshold_opt_selected)
                holder.tvValue.setTextColor(Color.parseColor("#f59e0b"))
                holder.ivCheck.visibility = View.VISIBLE
                holder.ivCheck.setColorFilter(Color.parseColor("#f59e0b"))
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

    private class PolicyOptionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val root: View = view.findViewById(R.id.layout_threshold_item_root)
        val tvValue: TextView = view.findViewById(R.id.tv_threshold_value)
        val tvDesc: TextView = view.findViewById(R.id.tv_threshold_desc)
        val ivCheck: ImageView = view.findViewById(R.id.iv_threshold_check)
    }
}
