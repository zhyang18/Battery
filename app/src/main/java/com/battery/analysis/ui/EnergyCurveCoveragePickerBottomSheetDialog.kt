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
 * 20 段 SOC 能量曲线全量能量推算覆盖率门限选择底部抽屉弹窗（EnergyCurveCoveragePickerBottomSheetDialog）。
 * 遵循技术评审 P2-B 与规范：
 * 允许用户灵活设定推算满充可用能量 (FCC_E) 所需达到的分箱覆盖率门限（70%、80%、85%、90%、100%），
 * 只要达到该门限即可严格基于已学满分箱的物理做功按比例外推满电能量与 SOH_E，杜绝死板苛求日常极难发生的 0% 深度过放电。
 */
class EnergyCurveCoveragePickerBottomSheetDialog : BottomSheetDialogFragment() {

    private var onCoverageSelectedListener: ((Int) -> Unit)? = null

    /**
     * 设置覆盖率门限更新选择回调监听器。
     *
     * @param listener 覆盖率门限百分比回调函数 (Int) -> Unit
     */
    fun setOnCoverageSelectedListener(listener: (Int) -> Unit) {
        this.onCoverageSelectedListener = listener
    }

    /**
     * 创建底部抽屉弹窗视图层次结构。
     *
     * @param inflater 布局填充器 [LayoutInflater]
     * @param container 父容器视图组 [ViewGroup]
     * @param savedInstanceState 状态保存包 [Bundle]
     * @return 初始化的视图根节点 [View]
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_energy_curve_coverage_picker, container, false)
    }

    /**
     * 弹窗启动并配置动画、尺寸与无黑色半透明背景遮罩规范。
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
     * 以平滑顺畅的向下滑动折叠动画关闭弹窗。
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
     * 绑定选项数据、单选事件以及确定/取消控制逻辑。
     *
     * @param view 弹窗根视图 [View]
     * @param savedInstanceState 状态保存包 [Bundle]
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = context ?: return
        val manager = BatteryCapacityManager.getInstance(ctx)
        val currentThreshold = manager.getEnergyCurveCoverageThreshold()
        var selectedItem = coverageOptions.firstOrNull { it.thresholdPercent == currentThreshold }
            ?: coverageOptions.first()

        view.findViewById<ImageView>(R.id.btn_close_coverage_dialog)?.setOnClickListener {
            dismissWithSlideDownAnimation()
        }
        view.findViewById<MaterialButton>(R.id.btn_cancel_coverage)?.setOnClickListener {
            dismissWithSlideDownAnimation()
        }

        view.findViewById<MaterialButton>(R.id.btn_confirm_coverage)?.setOnClickListener {
            manager.setEnergyCurveCoverageThreshold(selectedItem.thresholdPercent)
            onCoverageSelectedListener?.invoke(selectedItem.thresholdPercent)
            Toast.makeText(
                ctx,
                getString(R.string.toast_energy_curve_coverage_updated, getString(selectedItem.labelResId)),
                Toast.LENGTH_SHORT
            ).show()
            dismissWithSlideDownAnimation()
        }

        val rv = view.findViewById<RecyclerView>(R.id.rv_coverage_options)
        rv.layoutManager = LinearLayoutManager(ctx)
        val adapter = CoverageOptionAdapter(coverageOptions, selectedItem) { clickedItem ->
            selectedItem = clickedItem
        }
        rv.adapter = adapter

        val selectedIdx = coverageOptions.indexOf(selectedItem)
        if (selectedIdx >= 0) {
            rv.scrollToPosition(selectedIdx)
        }
    }

    /**
     * 覆盖率门限选项数据实体。
     *
     * @property thresholdPercent 覆盖率门限百分比数值（如 80）
     * @property labelResId 选项主标题文案资源 ID
     * @property descResId 选项副标题描述文案资源 ID
     */
    private data class CoverageOptionItem(
        val thresholdPercent: Int,
        val labelResId: Int,
        val descResId: Int
    )

    private val coverageOptions = listOf(
        CoverageOptionItem(80, R.string.energy_curve_coverage_opt_80, R.string.energy_curve_coverage_desc_80),
        CoverageOptionItem(85, R.string.energy_curve_coverage_opt_85, R.string.energy_curve_coverage_desc_85),
        CoverageOptionItem(90, R.string.energy_curve_coverage_opt_90, R.string.energy_curve_coverage_desc_90),
        CoverageOptionItem(70, R.string.energy_curve_coverage_opt_70, R.string.energy_curve_coverage_desc_70),
        CoverageOptionItem(100, R.string.energy_curve_coverage_opt_100, R.string.energy_curve_coverage_desc_100)
    )

    /**
     * 门限选项列表适配器。
     *
     * @param items 候选选项集合
     * @param initialSelected 初始选中项
     * @param onSelected 选项被点击时的回调
     */
    private class CoverageOptionAdapter(
        private val items: List<CoverageOptionItem>,
        initialSelected: CoverageOptionItem,
        private val onSelected: (CoverageOptionItem) -> Unit
    ) : RecyclerView.Adapter<CoverageOptionViewHolder>() {

        var selectedItem: CoverageOptionItem = initialSelected

        /**
         * 创建选项列表项视图持有者。
         *
         * @param parent 父视图容器 [ViewGroup]
         * @param viewType 视图类型整数标识
         * @return 创建的选项视图持有者 [CoverageOptionViewHolder]
         */
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CoverageOptionViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_capacity_threshold_option, parent, false)
            return CoverageOptionViewHolder(v)
        }

        /**
         * 绑定列表项数据与选中视觉状态。
         *
         * @param holder 选项视图持有者 [CoverageOptionViewHolder]
         * @param position 列表数据项索引
         */
        override fun onBindViewHolder(holder: CoverageOptionViewHolder, position: Int) {
            val item = items[position]
            val isSelected = item == selectedItem

            holder.tvValue.setText(item.labelResId)
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

        /**
         * 获取数据项总数。
         *
         * @return 选项总数
         */
        override fun getItemCount(): Int = items.size
    }

    /**
     * 选项视图持有者。
     *
     * @param view 列表项根视图 [View]
     */
    private class CoverageOptionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val root: View = view.findViewById(R.id.layout_threshold_item_root)
        val tvValue: TextView = view.findViewById(R.id.tv_threshold_value)
        val tvDesc: TextView = view.findViewById(R.id.tv_threshold_desc)
        val ivCheck: ImageView = view.findViewById(R.id.iv_threshold_check)
    }
}
