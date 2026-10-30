package com.battery.analysis.ui

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.battery.analysis.R
import com.battery.analysis.db.CapacityCandidateDbHelper
import com.battery.analysis.manager.BatteryCapacityManager
import com.battery.analysis.model.CandidateSessionType
import com.battery.analysis.model.CapacityCandidate
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自学习充放电样本明细列表底部抽屉弹窗（CapacitySessionListBottomSheetDialog）。
 * 展示所有参与或历史归档的充放电样本，直观呈现起止电量、净电量、推算容量与模型纳入状态。
 */
class CapacitySessionListBottomSheetDialog : BottomSheetDialogFragment() {

    private enum class SessionTabFilter {
        ALL, CHARGE, DISCHARGE
    }

    private var currentFilter = SessionTabFilter.ALL
    private var allCandidates: List<CapacityCandidate> = emptyList()
    private var currentThreshold: Int = 15
    private lateinit var adapter: CandidateSessionAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_capacity_session_list, container, false)
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return

        // 1. 彻底清除二级弹窗自身的半透明遮罩（Dim），避免取消或关闭列表时主界面遮罩闪烁或变黑
        dialog.window?.let { window ->
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setDimAmount(0f)
        }

        // 2. 清除系统底层容器的默认不透明直角背景
        bottomSheet.background = null
        bottomSheet.setBackgroundColor(Color.TRANSPARENT)

        // 3. 始终统一固定弹窗高度为屏幕高度的 80%，避免切换充放电 Tab 时高度自适应变短抖动
        val screenHeight = resources.displayMetrics.heightPixels
        val targetHeight = (screenHeight * 0.80).toInt()
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
        dialog.findViewById<View>(com.google.android.material.R.id.touch_outside)?.setOnClickListener {
            dismissWithSlideDownAnimation()
        }

        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                dismissWithSlideDownAnimation()
                true
            } else false
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

        view.findViewById<ImageView>(R.id.btn_close_session_list)?.setOnClickListener {
            dismissWithSlideDownAnimation()
        }

        val rv = view.findViewById<RecyclerView>(R.id.rv_capacity_sessions)
        rv.layoutManager = LinearLayoutManager(context)
        adapter = CandidateSessionAdapter(
            onDeleteClicked = { candidate ->
                showDeleteConfirmDialog(candidate)
            }
        )
        rv.adapter = adapter

        setupTabs(view)
        loadData()
    }

    private fun setupTabs(root: View) {
        val tabAll = root.findViewById<TextView>(R.id.tab_session_all)
        val tabCharge = root.findViewById<TextView>(R.id.tab_session_charge)
        val tabDischarge = root.findViewById<TextView>(R.id.tab_session_discharge)

        fun updateTabStyles() {
            val activeColor = Color.parseColor("#10b981")
            val inactiveColor = Color.parseColor("#64748b")

            tabAll.setTextColor(if (currentFilter == SessionTabFilter.ALL) activeColor else inactiveColor)
            tabCharge.setTextColor(if (currentFilter == SessionTabFilter.CHARGE) activeColor else inactiveColor)
            tabDischarge.setTextColor(if (currentFilter == SessionTabFilter.DISCHARGE) activeColor else inactiveColor)

            tabAll.paint.isFakeBoldText = currentFilter == SessionTabFilter.ALL
            tabCharge.paint.isFakeBoldText = currentFilter == SessionTabFilter.CHARGE
            tabDischarge.paint.isFakeBoldText = currentFilter == SessionTabFilter.DISCHARGE
        }

        tabAll.setOnClickListener {
            currentFilter = SessionTabFilter.ALL
            updateTabStyles()
            applyFilterAndSubmit()
        }
        tabCharge.setOnClickListener {
            currentFilter = SessionTabFilter.CHARGE
            updateTabStyles()
            applyFilterAndSubmit()
        }
        tabDischarge.setOnClickListener {
            currentFilter = SessionTabFilter.DISCHARGE
            updateTabStyles()
            applyFilterAndSubmit()
        }

        updateTabStyles()
    }

    private fun loadData() {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val dbHelper = CapacityCandidateDbHelper.getInstance(ctx)
            val manager = BatteryCapacityManager.getInstance(ctx)
            currentThreshold = manager.getEffectiveSocSpanThreshold()

            allCandidates = withContext(Dispatchers.IO) {
                dbHelper.getAllCandidates()
            }
            applyFilterAndSubmit()
        }
    }

    private fun applyFilterAndSubmit() {
        val filtered = when (currentFilter) {
            SessionTabFilter.ALL -> allCandidates
            SessionTabFilter.CHARGE -> allCandidates.filter { it.sessionType == CandidateSessionType.CHARGE }
            SessionTabFilter.DISCHARGE -> allCandidates.filter { it.sessionType == CandidateSessionType.DISCHARGE }
        }

        val emptyView = view?.findViewById<View>(R.id.layout_capacity_empty)
        val rv = view?.findViewById<RecyclerView>(R.id.rv_capacity_sessions)
        if (filtered.isEmpty()) {
            emptyView?.visibility = View.VISIBLE
            rv?.visibility = View.GONE
        } else {
            emptyView?.visibility = View.GONE
            rv?.visibility = View.VISIBLE
        }

        val validCount = allCandidates.count { !it.isExcluded && it.socSpanPercent >= currentThreshold }
        val subtitle = view?.findViewById<TextView>(R.id.tv_session_list_subtitle)
        subtitle?.text = getString(R.string.capacity_session_subtitle_format, allCandidates.size, validCount, currentThreshold)

        val tabAll = view?.findViewById<TextView>(R.id.tab_session_all)
        val tabCharge = view?.findViewById<TextView>(R.id.tab_session_charge)
        val tabDischarge = view?.findViewById<TextView>(R.id.tab_session_discharge)

        val chargeTotal = allCandidates.count { it.sessionType == CandidateSessionType.CHARGE }
        val dischargeTotal = allCandidates.count { it.sessionType == CandidateSessionType.DISCHARGE }
        tabAll?.text = getString(R.string.capacity_session_tab_all_format, allCandidates.size)
        tabCharge?.text = getString(R.string.capacity_session_tab_charge_format, chargeTotal)
        tabDischarge?.text = getString(R.string.capacity_session_tab_discharge_format, dischargeTotal)

        adapter.submitList(filtered, currentThreshold)
    }

    private fun showDeleteConfirmDialog(candidate: CapacityCandidate) {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle(R.string.dialog_delete_sample_title)
            .setMessage(getString(R.string.dialog_delete_sample_desc, String.format(Locale.getDefault(), "%.0f mAh", candidate.fccObservedMah)))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        CapacityCandidateDbHelper.getInstance(ctx).deleteCandidate(candidate.id)
                    }
                    BatteryCapacityManager.getInstance(ctx).refreshEstimateAsync()
                    loadData()
                }
            }
            .show()
    }

    private class CandidateSessionAdapter(
        private val onDeleteClicked: (CapacityCandidate) -> Unit
    ) : RecyclerView.Adapter<CandidateSessionViewHolder>() {

        private var items: List<CapacityCandidate> = emptyList()
        private var threshold: Int = 15
        private val dateFormat = SimpleDateFormat("MM-dd", Locale.getDefault())
        private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        private fun formatTimeRange(startTimestamp: Long, endTimestamp: Long): String {
            if (startTimestamp <= 0L && endTimestamp <= 0L) return "--"
            if (startTimestamp <= 0L) return timeFormat.format(Date(endTimestamp))
            if (endTimestamp <= 0L) return timeFormat.format(Date(startTimestamp))

            val startDate = dateFormat.format(Date(startTimestamp))
            val endDate = dateFormat.format(Date(endTimestamp))
            val startTime = timeFormat.format(Date(startTimestamp))
            val endTime = timeFormat.format(Date(endTimestamp))

            return if (startDate == endDate) {
                "$startDate $startTime ~ $endTime"
            } else {
                "$startDate $startTime ~ $endDate $endTime"
            }
        }

        fun submitList(list: List<CapacityCandidate>, currentThreshold: Int) {
            items = list
            threshold = currentThreshold
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CandidateSessionViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_capacity_candidate_session, parent, false)
            return CandidateSessionViewHolder(view)
        }

        override fun onBindViewHolder(holder: CandidateSessionViewHolder, position: Int) {
            val item = items[position]
            val isCharge = item.sessionType == CandidateSessionType.CHARGE

            // 1. 类型徽章
            if (isCharge) {
                holder.tvTypeBadge.text = holder.itemView.context.getString(R.string.capacity_session_badge_charge)
                holder.tvTypeBadge.setTextColor(Color.parseColor("#10b981"))
            } else {
                holder.tvTypeBadge.text = holder.itemView.context.getString(R.string.capacity_session_badge_discharge)
                holder.tvTypeBadge.setTextColor(Color.parseColor("#38bdf8"))
            }

            // 2. 时间（同天省略重复日期，精简且不折行）
            holder.tvTime.text = formatTimeRange(item.startTimestamp, item.endTimestamp)

            // 3. 状态标签与排除说明
            val meetsThreshold = item.socSpanPercent >= threshold
            when {
                item.isExcluded -> {
                    holder.tvStatus.text = holder.itemView.context.getString(R.string.capacity_session_status_outlier)
                    holder.tvStatus.setTextColor(Color.parseColor("#f59e0b"))
                    holder.tvReason.visibility = View.VISIBLE
                    holder.tvReason.text = if (item.exclusionReason.isNotBlank()) item.exclusionReason else holder.itemView.context.getString(R.string.capacity_session_status_outlier)
                }
                !meetsThreshold -> {
                    holder.tvStatus.text = holder.itemView.context.getString(R.string.capacity_session_status_below_threshold)
                    holder.tvStatus.setTextColor(Color.parseColor("#64748b"))
                    holder.tvReason.visibility = View.VISIBLE
                    holder.tvReason.text = "${item.socSpanPercent}% <= $threshold%"
                }
                else -> {
                    holder.tvStatus.text = holder.itemView.context.getString(R.string.capacity_session_status_included)
                    holder.tvStatus.setTextColor(Color.parseColor("#10b981"))
                    holder.tvReason.visibility = View.GONE
                }
            }

            // 4. 电量跨度
            holder.tvSoc.text = holder.itemView.context.getString(R.string.capacity_session_soc_span_format, item.startSoc, item.endSoc, item.socSpanPercent)

            // 5. 推算 FCC
            holder.tvFcc.text = String.format(Locale.getDefault(), "%.0f mAh", item.fccObservedMah)

            // 6. 测定净电量
            val netPrefix = if (isCharge) holder.itemView.context.getString(R.string.capacity_session_net_charge) else holder.itemView.context.getString(R.string.capacity_session_net_discharge)
            holder.tvNetCharge.text = "$netPrefix: ${String.format(Locale.getDefault(), "%.0f mAh", item.netBatteryChargeMah)}"

            // 7. 时长
            val durMin = (item.durationMs / 60000L).coerceAtLeast(1L)
            holder.tvDuration.text = if (durMin >= 60) "${durMin / 60}h ${durMin % 60}m" else "${durMin}m"

            // 8. 均温
            holder.tvTemp.text = holder.itemView.context.getString(R.string.capacity_session_avg_temp_format, item.avgTemperatureCelsius)

            // 9. 删除按钮
            holder.btnDelete.setOnClickListener {
                onDeleteClicked(item)
            }
        }

        override fun getItemCount(): Int = items.size
    }

    private class CandidateSessionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTypeBadge: TextView = view.findViewById(R.id.tv_item_session_type_badge)
        val tvTime: TextView = view.findViewById(R.id.tv_item_session_time)
        val tvStatus: TextView = view.findViewById(R.id.tv_item_session_status)
        val btnDelete: ImageView = view.findViewById(R.id.btn_item_session_delete)
        val tvSoc: TextView = view.findViewById(R.id.tv_item_session_soc)
        val tvFcc: TextView = view.findViewById(R.id.tv_item_session_fcc)
        val tvNetCharge: TextView = view.findViewById(R.id.tv_item_session_net_charge)
        val tvDuration: TextView = view.findViewById(R.id.tv_item_session_duration)
        val tvTemp: TextView = view.findViewById(R.id.tv_item_session_temp)
        val tvReason: TextView = view.findViewById(R.id.tv_item_session_reason)
    }
}
