package com.battery.analysis.ui

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

        bottomSheet.background = null
        bottomSheet.setBackgroundColor(Color.TRANSPARENT)

        val screenHeight = resources.displayMetrics.heightPixels
        val maxHeight = (screenHeight * 0.80).toInt()
        val behavior = BottomSheetBehavior.from(bottomSheet)
        behavior.maxHeight = maxHeight
        behavior.peekHeight = maxHeight
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
        subtitle?.text = "累计 ${allCandidates.size} 次记录 · ${validCount} 次纳入有效模型 (门限 > ${currentThreshold}%)"

        val tabAll = view?.findViewById<TextView>(R.id.tab_session_all)
        val tabCharge = view?.findViewById<TextView>(R.id.tab_session_charge)
        val tabDischarge = view?.findViewById<TextView>(R.id.tab_session_discharge)

        val chargeTotal = allCandidates.count { it.sessionType == CandidateSessionType.CHARGE }
        val dischargeTotal = allCandidates.count { it.sessionType == CandidateSessionType.DISCHARGE }
        tabAll?.text = "全部 (${allCandidates.size})"
        tabCharge?.text = "⚡ 充电 ($chargeTotal)"
        tabDischarge?.text = "🔋 放电 ($dischargeTotal)"

        adapter.submitList(filtered, currentThreshold)
    }

    private fun showDeleteConfirmDialog(candidate: CapacityCandidate) {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle("删除样本确认")
            .setMessage("确定删除本次推算容量为 ${String.format(Locale.getDefault(), "%.0f mAh", candidate.fccObservedMah)} 的会话样本？删除后系统将立即重新计算容量模型。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
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
        private val timeFormatter = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

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
                holder.tvTypeBadge.text = "⚡ 充电会话"
                holder.tvTypeBadge.setTextColor(Color.parseColor("#10b981"))
            } else {
                holder.tvTypeBadge.text = "🔋 放电会话"
                holder.tvTypeBadge.setTextColor(Color.parseColor("#38bdf8"))
            }

            // 2. 时间
            val startStr = if (item.startTimestamp > 0L) timeFormatter.format(Date(item.startTimestamp)) else "--"
            val endStr = if (item.endTimestamp > 0L) timeFormatter.format(Date(item.endTimestamp)) else "--"
            holder.tvTime.text = "$startStr ~ $endStr"

            // 3. 状态标签与排除说明
            val meetsThreshold = item.socSpanPercent >= threshold
            when {
                item.isExcluded -> {
                    holder.tvStatus.text = "离群排除"
                    holder.tvStatus.setTextColor(Color.parseColor("#f59e0b"))
                    holder.tvReason.visibility = View.VISIBLE
                    holder.tvReason.text = if (item.exclusionReason.isNotBlank()) "排除原因: ${item.exclusionReason}" else "离群异常样本已排除"
                }
                !meetsThreshold -> {
                    holder.tvStatus.text = "未达门限"
                    holder.tvStatus.setTextColor(Color.parseColor("#64748b"))
                    holder.tvReason.visibility = View.VISIBLE
                    holder.tvReason.text = "单次电量跨度 (${item.socSpanPercent}%) 小于当前门限 (> ${threshold}%)"
                }
                else -> {
                    holder.tvStatus.text = "✓ 纳入模型"
                    holder.tvStatus.setTextColor(Color.parseColor("#10b981"))
                    holder.tvReason.visibility = View.GONE
                }
            }

            // 4. 电量跨度
            holder.tvSoc.text = "${item.startSoc}% → ${item.endSoc}% (跨度 ${item.socSpanPercent}%)"

            // 5. 推算 FCC
            holder.tvFcc.text = String.format(Locale.getDefault(), "%.0f mAh", item.fccObservedMah)

            // 6. 测定净电量
            val netPrefix = if (isCharge) "净充入" else "净放出"
            holder.tvNetCharge.text = "$netPrefix: ${String.format(Locale.getDefault(), "%.0f mAh", item.netBatteryChargeMah)}"

            // 7. 时长
            val durMin = (item.durationMs / 60000L).coerceAtLeast(1L)
            holder.tvDuration.text = if (durMin >= 60) "${durMin / 60}h ${durMin % 60}m" else "${durMin}m"

            // 8. 均温
            holder.tvTemp.text = String.format(Locale.getDefault(), "均温: %.1f℃", item.avgTemperatureCelsius)

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
