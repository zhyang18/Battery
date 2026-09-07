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
            binding.cardScreenOffSpecial.visibility = View.VISIBLE
            val screenOffSign = if (record.screenOffLevelGain >= 0) "+${record.screenOffLevelGain}%" else "${record.screenOffLevelGain}%"
            binding.tvScreenOffLevelGain.text = screenOffSign
            binding.tvScreenOffEnergy.text = String.format(
                Locale.getDefault(),
                "+%.2f Wh",
                record.screenOffEnergyWh
            )
        } else {
            binding.cardScreenOffSpecial.visibility = View.GONE
        }
    }

    /**
     * 弹出删除单条充电历史记录的二次确认对话框。
     */
    private fun showDeleteConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete))
            .setMessage(getString(R.string.charging_history_clear_message))
            .setPositiveButton(getString(R.string.confirm)) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val db = ChargingHistoryDbHelper.getInstance(this@ChargingHistoryDetailActivity)
                    db.deleteRecord(recordId)
                    withContext(Dispatchers.Main) {
                        setResult(RESULT_OK)
                        finish()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    companion object {
        const val EXTRA_RECORD_ID = "extra_record_id"
    }
}
