package com.battery.analysis.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.battery.analysis.R
import com.battery.analysis.databinding.ActivityChargingHistoryDetailBinding
import com.battery.analysis.db.ChargingHistoryDbHelper
import com.battery.analysis.model.ChargingHistoryRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 充电历史记录详情展示 Activity。
 * 承载单次完整充电会话的全量物理指标与息屏统计卡片，支持起止时段、充入能量、功率极值与温度监控查看，并提供单条删除能力。
 */
class ChargingHistoryDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChargingHistoryDetailBinding
    private var recordId: Long = -1L
    private var currentRecord: ChargingHistoryRecord? = null

    /**
     * 活动初始化生命周期回调，配置沉浸式状态栏、获取传入参数并触发数据加载。
     *
     * @param savedInstanceState 状态恢复 Bundle
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateSystemBarAppearance()

        binding = ActivityChargingHistoryDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        recordId = intent.getLongExtra(EXTRA_RECORD_ID, -1L)
        setupListeners()
        loadRecordData()
    }

    /**
     * 根据当前日夜间主题适配状态栏明暗图标色彩。
     */
    private fun updateSystemBarAppearance() {
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        val isNight = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        insetsController.isAppearanceLightStatusBars = !isNight
        insetsController.isAppearanceLightNavigationBars = !isNight
    }

    /**
     * 设置返回导航及删除按钮的点击交互事件。
     */
    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnDelete.setOnClickListener {
            showDeleteConfirmDialog()
        }
    }

    /**
     * 从本地 SQLite 数据库中异步加载指定 ID 的充电记录并更新界面渲染。
     */
    private fun loadRecordData() {
        if (recordId <= 0L) {
            finish()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val db = ChargingHistoryDbHelper.getInstance(this@ChargingHistoryDetailActivity)
            val list = db.getAllRecords()
            val record = list.firstOrNull { it.id == recordId }

            withContext(Dispatchers.Main) {
                if (record != null) {
                    currentRecord = record
                    renderRecordDetails(record)
                } else {
                    finish()
                }
            }
        }
    }

    /**
     * 将充电会话详情数据绑定到界面各展示卡片与文字控件中。
     *
     * @param record 充电历史快照数据实体
     */
    private fun renderRecordDetails(record: ChargingHistoryRecord) {
        binding.tvChargeTypeBadge.text = record.chargeType.ifEmpty { "外部供电" }
        binding.tvDetailTimeRange.text = record.getFormattedTimeRange()

        binding.tvTotalDuration.text = record.getFormattedDuration()
        binding.tvScreenOffDuration.text = record.getFormattedScreenOffDuration()

        val sign = if (record.levelGain >= 0) "+${record.levelGain}%" else "${record.levelGain}%"
        binding.tvLevelChange.text = "${record.startLevel}% → ${record.endLevel}% ($sign)"

        binding.tvChargedEnergy.text = String.format(
            Locale.getDefault(),
            "+%.2f Wh",
            record.chargedEnergyWh
        )

        binding.tvAvgPower.text = String.format(
            Locale.getDefault(),
            "%.2f W",
            record.avgPowerWatts
        )

        binding.tvPeakPower.text = String.format(
            Locale.getDefault(),
            "%.2f W",
            record.maxPowerWatts
        )

        binding.tvMaxTemp.text = String.format(
            Locale.getDefault(),
            "%.1f ℃",
            record.maxTemperature
        )

        if (record.screenOffDurationMs > 0L) {
            binding.layoutScreenOffSpecial.visibility = View.VISIBLE
            val screenOffSign = if (record.screenOffLevelGain >= 0) "+${record.screenOffLevelGain}%" else "${record.screenOffLevelGain}%"
            binding.tvScreenOffLevelGain.text = screenOffSign
            binding.tvScreenOffEnergy.text = String.format(
                Locale.getDefault(),
                "+%.2f Wh",
                record.screenOffEnergyWh
            )
        } else {
            binding.layoutScreenOffSpecial.visibility = View.GONE
        }

        // 绑定三合一走势折线图数据（电量、功率、温度），支持手势标尺交互
        val samplePoints = record.getSamplePoints()
        binding.chargingChartView.setData(samplePoints)
    }

    /**
     * 为自定义对话框应用居中、半透明背景及适屏宽度的窗口样式。
     *
     * @param dialog 待配置样式的 [AlertDialog] 实例
     */
    private fun applyDialogWindowStyle(dialog: AlertDialog) {
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            val width = (resources.displayMetrics.widthPixels * 0.92).toInt()
            window.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setGravity(android.view.Gravity.CENTER)
        }
    }

    /**
     * 弹出删除单条充电历史记录的高颜值二次确认对话框。
     */
    private fun showDeleteConfirmDialog() {
        val record = currentRecord ?: return
        val dialogView = layoutInflater.inflate(R.layout.dialog_custom_delete_confirm, null)
        val tvTitle = dialogView.findViewById<android.widget.TextView>(R.id.tv_dialog_delete_title)
        val tvDesc = dialogView.findViewById<android.widget.TextView>(R.id.tv_dialog_delete_desc)
        val tvPreviewCat = dialogView.findViewById<android.widget.TextView>(R.id.tv_preview_cat)
        val tvPreviewTime = dialogView.findViewById<android.widget.TextView>(R.id.tv_preview_time)
        val tvPreviewSummary = dialogView.findViewById<android.widget.TextView>(R.id.tv_preview_summary)
        val btnCancel = dialogView.findViewById<android.widget.TextView>(R.id.btn_dialog_delete_cancel)
        val btnConfirm = dialogView.findViewById<android.widget.TextView>(R.id.btn_dialog_delete_confirm)

        tvTitle.text = "确认删除此充电记录？"
        tvDesc.text = "删除后该条充电历史记录将从本地永久移除，无法找回。"
        tvPreviewCat.text = if (record.chargeType.isNotEmpty()) record.chargeType else "充电记录"
        tvPreviewTime.text = record.recordTime
        val sign = if (record.levelGain >= 0) "+${record.levelGain}%" else "${record.levelGain}%"
        tvPreviewSummary.text = "⚡ $sign (${record.startLevel}% → ${record.endLevel}%)   •   ⏱️ ${record.getFormattedDuration()}"

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnConfirm.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val db = ChargingHistoryDbHelper.getInstance(this@ChargingHistoryDetailActivity)
                db.deleteRecord(recordId)
                withContext(Dispatchers.Main) {
                    setResult(RESULT_OK)
                    finish()
                }
            }
        }

        dialog.show()
        applyDialogWindowStyle(dialog)
    }

    companion object {
        const val EXTRA_RECORD_ID = "extra_record_id"
    }
}
