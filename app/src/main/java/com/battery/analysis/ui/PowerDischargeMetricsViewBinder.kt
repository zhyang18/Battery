package com.battery.analysis.ui

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import androidx.core.text.HtmlCompat
import com.battery.analysis.R
import com.battery.analysis.databinding.ItemPowerDischargeSpeedBinding
import com.battery.analysis.databinding.ItemPowerSleepAwakeMetricsBinding
import com.battery.analysis.domain.estimator.RollingDischargeBuffer
import com.battery.analysis.manager.PowerOverviewStats
import com.battery.analysis.manager.PowerUsageManager
import com.battery.analysis.model.PowerDischargePoint
import com.battery.analysis.model.PowerDisplayUnit
import com.battery.analysis.model.SevenDaysDischargeStats
import com.battery.analysis.util.BatteryEnergyCalculator
import com.battery.analysis.util.UnitSpanFormatter
import com.battery.analysis.util.setUnitText
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 放电速度与息屏休眠指标卡片通用视图绑定辅助工具类。
 * 负责统一格式化放电速度矩阵（亮屏 / 息屏 / 全局）与息屏状态矩阵（息屏 / 唤醒 / 深度睡眠）的核心物理数据，
 * 并支持卡片轻触点击注解弹框，确保主页面与快照详情页展示规范、视觉层级与交互行为高度统一。
 */
object PowerDischargeMetricsViewBinder {

    /**
     * 息屏卡片分类标识。
     */
    const val CARD_SCREEN_OFF = 1

    /**
     * 唤醒卡片分类标识。
     */
    const val CARD_AWAKE = 2

    /**
     * 深度睡眠卡片分类标识。
     */
    const val CARD_DEEP_SLEEP = 3

    /**
     * 预测基准放电速度与续航预测核心数据高亮强调色彩（十六进制代码 #AA5AB6）。
     */
    const val COLOR_PREDICTION_HIGHLIGHT = 0xFFAA5AB6.toInt()

    /**
     * 升级模型满电续航时长数值匹配正则表达式（如匹配 "• 升级模型(v2.2)：15h41m53s (可信度高)" 中的 "15h41m53s"）。
     */
    val REGEX_V2_MODEL_DURATION = Regex("""•\s*(?:升级模型|Upgraded Model)\s*\([^)]+\)[：:]\s*([^\s(]+)""", RegexOption.IGNORE_CASE)

    /**
     * 预测基准放电速度数值匹配正则表达式（如匹配 "• 预测基准放电速度：7.4%/h" 中的 "7.4%/h"）。
     */
    val REGEX_PREDICTION_BENCHMARK_RATE = Regex("""•\s*(?:预测基准放电速度|Predicted baseline drain rate)[：:]\s*([^\s\n]+)""", RegexOption.IGNORE_CASE)

    /**
     * 当前剩余续航或当前待机续航时长数值匹配正则表达式（如匹配 "• 当前剩余续航：07h22m44s (可信度高)" 中的 "07h22m44s"）。
     */
    val REGEX_CURRENT_REMAINING_DURATION = Regex("""•\s*(?:当前剩余续航|当前待机续航|Current remaining runtime|Current remaining standby)[：:]\s*([^\s(]+)""", RegexOption.IGNORE_CASE)

    /**
     * 将放电速度矩阵（亮屏、息屏、全局）数据绑定至对应的视图组件并配置点击回调。
     * 第一行展示预测基准放电速度百分比速率，第二行展示基于物理能量与自适应多时间尺度计算的剩余续航时长。
     *
     * @param binding 放电速度布局绑定对象 [ItemPowerDischargeSpeedBinding]
     * @param overview 核心功耗指标概览数据实体 [PowerOverviewStats]，若无数据传入 null
     * @param totalCapacityWh 设备满电总能量（单位：Wh），若不可用传入 null
     * @param sevenDaysStats 累积 7 天统计放电速度数据实体 [SevenDaysDischargeStats]，若无数据传入 null
     * @param batteryLevelPercent 当前电池剩余电量百分比（0.0f ~ 100.0f），若不可用传入 null
     * @param remainingEnergyWh 当前电池剩余能量（单位：Wh），若不可用传入 null
     * @param tempCelsius 电池当前工作温度（单位：℃，可选），用于高精度修正实际物理化学可用能量 K_T
     * @param currentTrendPoints 当前周期的细粒度趋势采样点集合（可选，用于激活 Fast 尺度实时跟踪与工况突变敏捷响应）
     * @param precalculatedRuntimeResult 外部预先估算并缓存的三维度剩余续航结果实体（可选，非空时直接复用，杜绝重复计算）
     * @param animateProgress 是否对剩余续航进度条启用平滑缓慢过渡动画（仅在主动数据刷新时为 true，列表滚动复用时为 false）
     * @param onCardClick 卡片点击事件监听回调，参数依次为被点击卡片视图与行分类标识（[BatteryEnergyCalculator.ROW_SCREEN_ON] 等）
     * @return 实际用于渲染绑定的三维度剩余续航结果实体 [com.battery.analysis.domain.estimator.TripleRuntimeResult]
     */
    fun bindDischargeSpeed(
        binding: ItemPowerDischargeSpeedBinding,
        overview: PowerOverviewStats?,
        totalCapacityWh: Float?,
        sevenDaysStats: SevenDaysDischargeStats?,
        batteryLevelPercent: Float? = null,
        remainingEnergyWh: Float? = null,
        tempCelsius: Float? = null,
        currentTrendPoints: List<PowerDischargePoint>? = null,
        precalculatedRuntimeResult: com.battery.analysis.domain.estimator.TripleRuntimeResult? = null,
        animateProgress: Boolean = false,
        onCardClick: ((anchorView: View, rowType: Int) -> Unit)? = null
    ): com.battery.analysis.domain.estimator.TripleRuntimeResult {
        with(binding) {
            val onEffectiveRate = getEffectiveDischargeRate(BatteryEnergyCalculator.ROW_SCREEN_ON, totalCapacityWh, overview, sevenDaysStats)
            val offEffectiveRate = getEffectiveDischargeRate(BatteryEnergyCalculator.ROW_SCREEN_OFF, totalCapacityWh, overview, sevenDaysStats)
            val globalEffectiveRate = getEffectiveDischargeRate(BatteryEnergyCalculator.ROW_GLOBAL, totalCapacityWh, overview, sevenDaysStats)

            // --- 1. 第一行：预测基准放电速度百分比速率 ---
            tvScreenOnSpeed.setUnitText(formatDischargeRateSpannable(onEffectiveRate))
            tvScreenOffSpeed.setUnitText(formatDischargeRateSpannable(offEffectiveRate))
            tvGlobalSpeed.setUnitText(formatDischargeRateSpannable(globalEffectiveRate))

            // --- 2. 第二行：剩余续航时间（优先消费外部传入的预计算缓存结果；若无则降级现场估算，保障严格跟随下拉刷新机制） ---
            val tripleResult = precalculatedRuntimeResult ?: BatteryEnergyCalculator.estimateTripleRuntimes(
                overview = overview,
                sevenDaysStats = sevenDaysStats,
                batteryLevelPercent = batteryLevelPercent,
                remainingEnergyWh = remainingEnergyWh,
                totalCapacityWh = totalCapacityWh,
                tempCelsius = tempCelsius,
                currentTrendPoints = currentTrendPoints
            )

            val remOnMs = tripleResult.screenOnEstimate.remainingMs
            val remOffMs = tripleResult.screenOffEstimate.remainingMs
            val remGlobalMs = tripleResult.globalEstimate.remainingMs

            tvScreenOnRemaining.setUnitText(formatRemainingDurationOnly(remOnMs))
            tvScreenOffRemaining.setUnitText(formatRemainingDurationOnly(remOffMs))
            tvGlobalRemaining.setUnitText(formatRemainingDurationOnly(remGlobalMs))

            val fullOnMs = sevenDaysStats?.v2Result?.screenOnFullRuntimeMs
            val fullOffMs = sevenDaysStats?.v2Result?.screenOffFullRuntimeMs
            val fullGlobalMs = sevenDaysStats?.v2Result?.fullRuntimeMs

            tvScreenOnRemainingLabel.setUnitText(formatRemainingDurationOnly(fullOnMs))
            tvScreenOffRemainingLabel.setUnitText(formatRemainingDurationOnly(fullOffMs))
            tvGlobalRemainingLabel.setUnitText(formatRemainingDurationOnly(fullGlobalMs))

            // --- 3. 第三行：剩余续航与满电续航进度占比指示条 (高度 3dp) ---
            updateRuntimeProgressBar(progressScreenOnRemaining, remOnMs, fullOnMs, animateProgress)
            updateRuntimeProgressBar(progressScreenOffRemaining, remOffMs, fullOffMs, animateProgress)
            updateRuntimeProgressBar(progressGlobalRemaining, remGlobalMs, fullGlobalMs, animateProgress)

            // 卡片点击事件监听
            if (onCardClick != null) {
                cardScreenOnSpeed.setOnClickListener {
                    onCardClick(cardScreenOnSpeed, BatteryEnergyCalculator.ROW_SCREEN_ON)
                }
                cardScreenOffSpeed.setOnClickListener {
                    onCardClick(cardScreenOffSpeed, BatteryEnergyCalculator.ROW_SCREEN_OFF)
                }
                cardGlobalSpeed.setOnClickListener {
                    onCardClick(cardGlobalSpeed, BatteryEnergyCalculator.ROW_GLOBAL)
                }
            }
            return tripleResult
        }
    }

    /**
     * 将放电速度与续航预测气泡弹窗的详细说明文案进行关键预测数据高亮强调格式化。
     * 将弹框中的升级模型满电续航时长数值、预测基准放电速度数值以及当前剩余（或待机）续航数值着色为统一的高亮强调色（#AA5AB6），
     * 辅助用户在信息密集的说明弹窗中快速聚焦核心算法预测结论。
     *
     * @param rawMessage 原始详细说明文案字符串
     * @return 包含关键预测数据高亮颜色 Span 的富文本对象 [CharSequence]
     */
    fun formatDischargeSpeedBubbleMessage(rawMessage: String): CharSequence {
        if (rawMessage.isBlank()) return rawMessage

        val spannable = SpannableString(rawMessage)

        fun highlightMatch(regex: Regex) {
            regex.findAll(rawMessage).forEach { matchResult ->
                val group = matchResult.groups[1]
                if (group != null && group.range.first <= group.range.last) {
                    spannable.setSpan(
                        ForegroundColorSpan(COLOR_PREDICTION_HIGHLIGHT),
                        group.range.first,
                        group.range.last + 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
        }

        highlightMatch(REGEX_V2_MODEL_DURATION)
        highlightMatch(REGEX_PREDICTION_BENCHMARK_RATE)
        highlightMatch(REGEX_CURRENT_REMAINING_DURATION)

        return spannable
    }

    /**
     * 将息屏、唤醒与深度睡眠三状态指标卡片数据绑定至对应的视图组件并配置点击回调。
     * 支持自适应根据传入的电量单位 [unit] 智能调整第二行（时长与主指标）与第三行（对照副指标与功率）的主次排版，
     * 并确保右侧数值与单位严格靠右对齐。
     *
     * @param binding 息屏细分状态布局绑定对象 [ItemPowerSleepAwakeMetricsBinding]
     * @param overview 核心功耗指标概览数据实体 [PowerOverviewStats]，若无数据传入 null
     * @param unit 当前电量呈现单位 [PowerDisplayUnit]（默认为 [PowerDisplayUnit.WH]）
     * @param onCardClick 卡片点击事件监听回调，参数依次为被点击卡片视图与卡片分类标识（[CARD_SCREEN_OFF] 等）
     */
    fun bindSleepAwakeMetrics(
        binding: ItemPowerSleepAwakeMetricsBinding,
        overview: PowerOverviewStats?,
        unit: PowerDisplayUnit = PowerDisplayUnit.WH,
        onCardClick: ((anchorView: View, cardType: Int) -> Unit)? = null
    ) {
        val noRecordText = binding.root.context.getString(R.string.power_metric_no_record)
        val isMahUnit = unit == PowerDisplayUnit.MAH
        with(binding) {
            if (overview == null) {
                tvScreenOffPercent.text = "--"
                tvScreenOffDuration.text = noRecordText
                tvScreenOffEnergy.text = "--"
                tvScreenOffCompare.text = "--"
                tvScreenOffPower.text = "--"

                tvAwakePercent.text = "--"
                tvAwakeDuration.text = noRecordText
                tvAwakeEnergy.text = "--"
                tvAwakeCompare.text = "--"
                tvAwakePower.text = "--"

                tvSleepPercent.text = "--"
                tvSleepDuration.text = noRecordText
                tvSleepEnergy.text = "--"
                tvSleepCompare.text = "--"
                tvSleepPower.text = "--"
                return
            }

            // 1. 息屏卡片
            if (overview.screenOffDurationMs <= 0L && overview.screenOffEnergyWh <= 0f) {
                tvScreenOffPercent.text = "--"
                tvScreenOffDuration.text = noRecordText
                tvScreenOffEnergy.text = "--"
                tvScreenOffCompare.text = "--"
                tvScreenOffPower.text = "--"
            } else {
                val offPercent = if (overview.screenOffPercent > 0f) {
                    overview.screenOffPercent
                } else {
                    overview.screenOffAwakePercent + overview.screenOffDeepSleepPercent
                }
                tvScreenOffPercent.setUnitText(if (offPercent > 0f) String.format(Locale.US, "%.1f%%", offPercent) else "--")

                val durText = formatSleepAwakeCardDuration(
                    durationMs = if (overview.screenOffDurationMs > 0L) overview.screenOffDurationMs else parseDurationTextToMs(overview.screenOffDurationText),
                    fallbackText = overview.screenOffDurationText
                )
                val offDrainMah = if (overview.screenOffDrainMah > 0f) {
                    overview.screenOffDrainMah
                } else if (overview.screenOffEnergyWh > 0f) {
                    (overview.screenOffEnergyWh * 1000f) / BatteryEnergyCalculator.DEFAULT_NOMINAL_VOLTAGE_VOLTS
                } else {
                    0f
                }
                val offPowerWatts = if (overview.screenOffPowerWatts > 0f) {
                    overview.screenOffPowerWatts
                } else if (overview.screenOffDurationMs > 0L && overview.screenOffEnergyWh > 0f) {
                    (overview.screenOffEnergyWh / (overview.screenOffDurationMs.toDouble() / 3600000.0)).toFloat()
                } else {
                    0f
                }
                val offPowerText = if (overview.screenOffDurationMs > 0L || overview.screenOffEnergyWh > 0f) {
                    formatCardPowerWatts(offPowerWatts) ?: "--"
                } else {
                    "--"
                }

                tvScreenOffDuration.text = durText

                if (isMahUnit) {
                    val mahSubtitle = if (offDrainMah > 0f) String.format(Locale.US, "%.1fmAh", offDrainMah) else "--"
                    tvScreenOffEnergy.setUnitText(mahSubtitle)
                    tvScreenOffCompare.setUnitText(String.format(Locale.US, "%.3fWh", overview.screenOffEnergyWh))
                    tvScreenOffPower.setUnitText(offPowerText)
                } else {
                    val energyText = String.format(Locale.US, "%.3fWh", overview.screenOffEnergyWh)
                    tvScreenOffEnergy.setUnitText(energyText)
                    val mahStr = if (offDrainMah > 0f) {
                        String.format(Locale.US, "%.1fmAh", offDrainMah)
                    } else if (overview.screenOffDurationMs > 0L) {
                        "0.0mAh"
                    } else {
                        "--"
                    }
                    tvScreenOffCompare.setUnitText(mahStr)
                    tvScreenOffPower.setUnitText(offPowerText)
                }
            }

            // 2. 唤醒卡片
            if (overview.screenOffAwakeDurationMs <= 0L && overview.screenOffAwakeEnergyWh <= 0f) {
                if (overview.screenOffDurationMs > 0L) {
                    // 放电周期内经历过息屏，但唤醒时长与能耗均为 0（处于 100% 纯深度睡眠挂起状态）
                    tvAwakePercent.setUnitText("0.0%")
                    tvAwakeDuration.text = "00s"
                    if (isMahUnit) {
                        tvAwakeEnergy.setUnitText("0.0mAh")
                        tvAwakeCompare.setUnitText("0.000Wh")
                        tvAwakePower.setUnitText("0.00W")
                    } else {
                        tvAwakeEnergy.setUnitText("0.000Wh")
                        tvAwakeCompare.setUnitText("0.0mAh")
                        tvAwakePower.setUnitText("0.00W")
                    }
                } else {
                    tvAwakePercent.text = "--"
                    tvAwakeDuration.text = noRecordText
                    tvAwakeEnergy.text = "--"
                    tvAwakeCompare.text = "--"
                    tvAwakePower.text = "--"
                }
            } else {
                val awakePercentStr = if (overview.isScreenOffDecomposedAvailable && overview.screenOffAwakePercent > 0f) {
                    String.format(Locale.US, "%.1f%%", overview.screenOffAwakePercent)
                } else if (overview.isScreenOffDecomposedAvailable && overview.screenOffAwakeEnergyWh > 0f) {
                    "<0.1%"
                } else if (overview.screenOffAwakeDurationMs > 0L) {
                    "0.0%"
                } else {
                    "--"
                }
                tvAwakePercent.setUnitText(awakePercentStr)

                val durText = formatSleepAwakeCardDuration(
                    durationMs = if (overview.screenOffAwakeDurationMs > 0L) overview.screenOffAwakeDurationMs else parseDurationTextToMs(overview.screenOffAwakeDurationText),
                    fallbackText = overview.screenOffAwakeDurationText
                )

                val awakeDrainMah = if (overview.isScreenOffDecomposedAvailable && overview.screenOffAwakeDrainMah > 0f) {
                    overview.screenOffAwakeDrainMah
                } else if (overview.isScreenOffDecomposedAvailable && overview.screenOffAwakeEnergyWh > 0f) {
                    (overview.screenOffAwakeEnergyWh * 1000f) / BatteryEnergyCalculator.DEFAULT_NOMINAL_VOLTAGE_VOLTS
                } else {
                    0f
                }
                val awakePowerWatts = if (overview.screenOffAwakePowerWatts > 0f) {
                    overview.screenOffAwakePowerWatts
                } else if (overview.screenOffAwakeDurationMs > 0L && overview.screenOffAwakeEnergyWh > 0f) {
                    (overview.screenOffAwakeEnergyWh / (overview.screenOffAwakeDurationMs.toDouble() / 3600000.0)).toFloat()
                } else {
                    0f
                }
                val awakePowerText = if (overview.screenOffAwakeDurationMs > 0L || overview.screenOffAwakeEnergyWh > 0f) {
                    formatCardPowerWatts(awakePowerWatts) ?: "--"
                } else {
                    "--"
                }

                tvAwakeDuration.text = durText

                if (isMahUnit) {
                    val awakeMahSubtitle = if (overview.isScreenOffDecomposedAvailable && awakeDrainMah >= 0.1f) {
                        String.format(Locale.US, "%.1fmAh", awakeDrainMah)
                    } else if (overview.isScreenOffDecomposedAvailable && awakeDrainMah > 0f) {
                        "<0.1mAh"
                    } else if (overview.screenOffAwakeDurationMs > 0L) {
                        "0.0mAh"
                    } else {
                        "--"
                    }
                    tvAwakeEnergy.setUnitText(awakeMahSubtitle)

                    val energyDisplay = if (overview.isScreenOffDecomposedAvailable && overview.screenOffAwakeEnergyWh >= 0.001f) {
                        String.format(Locale.US, "%.3fWh", overview.screenOffAwakeEnergyWh)
                    } else if (overview.isScreenOffDecomposedAvailable && overview.screenOffAwakeEnergyWh > 0f) {
                        "<0.001Wh"
                    } else if (overview.screenOffAwakeDurationMs > 0L) {
                        "0.000Wh"
                    } else {
                        "--"
                    }
                    tvAwakeCompare.setUnitText(energyDisplay)
                    tvAwakePower.setUnitText(awakePowerText)
                } else {
                    val energyText = if (overview.isScreenOffDecomposedAvailable && overview.screenOffAwakeEnergyWh >= 0.001f) {
                        String.format(Locale.US, "%.3fWh", overview.screenOffAwakeEnergyWh)
                    } else if (overview.isScreenOffDecomposedAvailable && overview.screenOffAwakeEnergyWh > 0f) {
                        "<0.001Wh"
                    } else if (overview.screenOffAwakeDurationMs > 0L) {
                        "0.000Wh"
                    } else {
                        "--"
                    }
                    tvAwakeEnergy.setUnitText(energyText)

                    val mahText = if (awakeDrainMah >= 0.1f) {
                        String.format(Locale.US, "%.1fmAh", awakeDrainMah)
                    } else if (overview.screenOffAwakeDurationMs > 0L) {
                        "0.0mAh"
                    } else {
                        "--"
                    }
                    tvAwakeCompare.setUnitText(mahText)
                    tvAwakePower.setUnitText(awakePowerText)
                }
            }

            // 3. 深度睡眠卡片
            if (overview.screenOffDeepSleepDurationMs <= 0L && overview.screenOffDeepSleepEnergyWh <= 0f) {
                if (overview.screenOffDurationMs > 0L) {
                    // 放电周期内经历过息屏，但深度睡眠时长为 0（息屏期间 CPU 全程被持有唤醒锁活跃工作）
                    tvSleepPercent.setUnitText("0.0%")
                    tvSleepDuration.text = "00s"
                    if (isMahUnit) {
                        tvSleepEnergy.setUnitText("0.0mAh")
                        tvSleepCompare.setUnitText("0.000Wh")
                        tvSleepPower.setUnitText("0.00W")
                    } else {
                        tvSleepEnergy.setUnitText("0.000Wh")
                        tvSleepCompare.setUnitText("0.0mAh")
                        tvSleepPower.setUnitText("0.00W")
                    }
                } else {
                    tvSleepPercent.text = "--"
                    tvSleepDuration.text = noRecordText
                    tvSleepEnergy.text = "--"
                    tvSleepCompare.text = "--"
                    tvSleepPower.text = "--"
                }
            } else {
                val sleepPercentStr = if (overview.isScreenOffDecomposedAvailable && overview.screenOffDeepSleepPercent > 0f) {
                    String.format(Locale.US, "%.1f%%", overview.screenOffDeepSleepPercent)
                } else {
                    "--"
                }
                tvSleepPercent.setUnitText(sleepPercentStr)

                val durText = formatSleepAwakeCardDuration(
                    durationMs = if (overview.screenOffDeepSleepDurationMs > 0L) overview.screenOffDeepSleepDurationMs else parseDurationTextToMs(overview.screenOffDeepSleepDurationText),
                    fallbackText = overview.screenOffDeepSleepDurationText
                )

                val sleepDrainMah = if (overview.isScreenOffDecomposedAvailable && overview.screenOffDeepSleepDrainMah > 0f) {
                    overview.screenOffDeepSleepDrainMah
                } else if (overview.isScreenOffDecomposedAvailable && overview.screenOffDeepSleepEnergyWh > 0f) {
                    (overview.screenOffDeepSleepEnergyWh * 1000f) / BatteryEnergyCalculator.DEFAULT_NOMINAL_VOLTAGE_VOLTS
                } else {
                    0f
                }
                val deepSleepPowerWatts = if (overview.screenOffDeepSleepPowerWatts > 0f) {
                    overview.screenOffDeepSleepPowerWatts
                } else if (overview.screenOffDeepSleepDurationMs > 0L && overview.screenOffDeepSleepEnergyWh > 0f) {
                    (overview.screenOffDeepSleepEnergyWh / (overview.screenOffDeepSleepDurationMs.toDouble() / 3600000.0)).toFloat()
                } else {
                    0f
                }
                val sleepPowerText = if (overview.screenOffDeepSleepDurationMs > 0L || overview.screenOffDeepSleepEnergyWh > 0f) {
                    formatCardPowerWatts(deepSleepPowerWatts) ?: "--"
                } else {
                    "--"
                }

                tvSleepDuration.text = durText

                if (isMahUnit) {
                    val sleepMahSubtitle = if (overview.isScreenOffDecomposedAvailable && sleepDrainMah >= 0.1f) {
                        String.format(Locale.US, "%.1fmAh", sleepDrainMah)
                    } else if (overview.isScreenOffDecomposedAvailable && sleepDrainMah > 0f) {
                        "<0.1mAh"
                    } else if (overview.screenOffDeepSleepDurationMs > 0L) {
                        "0.0mAh"
                    } else {
                        "--"
                    }
                    tvSleepEnergy.setUnitText(sleepMahSubtitle)

                    val energyDisplay = if (overview.isScreenOffDecomposedAvailable && overview.screenOffDeepSleepEnergyWh > 0f) {
                        String.format(Locale.US, "%.3fWh", overview.screenOffDeepSleepEnergyWh)
                    } else {
                        "--"
                    }
                    tvSleepCompare.setUnitText(energyDisplay)
                    tvSleepPower.setUnitText(sleepPowerText)
                } else {
                    val energyText = if (overview.isScreenOffDecomposedAvailable && overview.screenOffDeepSleepEnergyWh > 0f) {
                        String.format(Locale.US, "%.3fWh", overview.screenOffDeepSleepEnergyWh)
                    } else {
                        "--"
                    }
                    tvSleepEnergy.setUnitText(energyText)

                    val mahText = if (sleepDrainMah > 0f) {
                        String.format(Locale.US, "%.1fmAh", sleepDrainMah)
                    } else if (overview.screenOffDeepSleepDurationMs > 0L) {
                        "0.0mAh"
                    } else {
                        "--"
                    }
                    tvSleepCompare.setUnitText(mahText)
                    tvSleepPower.setUnitText(sleepPowerText)
                }
            }

            // 绑定点击事件监听
            if (onCardClick != null) {
                cardScreenOff.setOnClickListener {
                    onCardClick(cardScreenOff, CARD_SCREEN_OFF)
                }
                cardAwake.setOnClickListener {
                    onCardClick(cardAwake, CARD_AWAKE)
                }
                cardDeepSleep.setOnClickListener {
                    onCardClick(cardDeepSleep, CARD_DEEP_SLEEP)
                }
            }
        }
    }

    /**
     * 计算并获取指定维度的有效实测放电速度（优先物理做功微积分功率折算，兜底滑动时序与单次会话统计）。
     * 针对息屏、亮屏与全局综合场景，优先基于连续滑动窗口/会话实测的平均微积分功率及电池满充物理能量计算高精度放电速率，
     * 彻底消除 Android 系统广播电量 1% 粗粒度整型阶梯量化跳变（Integer Quantization Noise）导致的速率虚高放大问题；
     * 仅在设备总容量不可用或功率缺失时，才回退到基于百分比电量差值的粗粒度掉电率。
     *
     * @param rowType 行分类标识（[BatteryEnergyCalculator.ROW_SCREEN_ON] 等）
     * @param totalCapacityWh 设备满充总能量容量（单位：Wh）
     * @param overview 当前放电统计实体 [PowerOverviewStats]，若无数据传入 null
     * @param sevenDaysStats 过去 7 天统计实体 [SevenDaysDischargeStats]，用于动态提供待机底噪画像（可选）
     * @return 实测放电速率（单位：%/h，缺失时返回 null）
     */
    fun getEffectiveDischargeRate(
        rowType: Int,
        totalCapacityWh: Float?,
        overview: PowerOverviewStats?,
        sevenDaysStats: SevenDaysDischargeStats? = null
    ): Float? {
        val rollingBuffer = RollingDischargeBuffer.getInstanceOrNull()
        val offLowW = sevenDaysStats?.v2Result?.profile?.offLowPowerW
        return when (rowType) {
            BatteryEnergyCalculator.ROW_SCREEN_ON -> {
                val win = rollingBuffer?.extractRollingScreenOnWindow(7200_000L)
                val w = win?.avgPowerWatts?.takeIf { it > 0f } ?: overview?.screenOnPowerWatts?.takeIf { it > 0f } ?: 0f
                val dur = win?.effectiveDurationMs?.takeIf { it > 0L } ?: (overview?.screenOnDurationMs ?: 0L)
                val powerRate = calculateDischargeRatePercentPerHour(w, totalCapacityWh, dur)
                val sessionPercentRate = if (overview != null && overview.screenOnPercent > 0f && overview.screenOnDurationMs > 0L) {
                    val hrs = overview.screenOnDurationMs / 3600000.0f
                    if (hrs > 0.05f) overview.screenOnPercent / hrs else null
                } else null
                powerRate ?: win?.dischargeRatePercentPerHour ?: sessionPercentRate
            }
            BatteryEnergyCalculator.ROW_SCREEN_OFF -> {
                // 升级为 12 小时（43200_000L）大跨度切片，完整覆盖一整夜深度睡眠底噪与半天长待机周期
                val win = rollingBuffer?.extractRollingScreenOffWindow(43200_000L, baselineSleepWatts = offLowW)
                val w = win?.avgPowerWatts?.takeIf { it > 0f } ?: overview?.screenOffPowerWatts?.takeIf { it > 0f } ?: 0f
                val dur = win?.effectiveDurationMs?.takeIf { it > 0L } ?: (overview?.screenOffDurationMs ?: 0L)
                val powerRate = calculateDischargeRatePercentPerHour(w, totalCapacityWh, dur)
                val sessionPercentRate = if (overview != null && overview.screenOffPercent > 0f && overview.screenOffDurationMs > 0L) {
                    val hrs = overview.screenOffDurationMs / 3600000.0f
                    if (hrs > 0.05f) overview.screenOffPercent / hrs else null
                } else null
                powerRate ?: win?.dischargeRatePercentPerHour ?: sessionPercentRate
            }
            BatteryEnergyCalculator.ROW_GLOBAL -> {
                // 升级为 24 小时（86400_000L）宏观昼夜闭环切片，彻底消除早晚相位偏态
                val win = rollingBuffer?.extractRollingGlobalWindow(86400_000L, baselineSleepWatts = offLowW)
                val w = win?.avgPowerWatts?.takeIf { it > 0f } ?: overview?.avgPowerWatts?.takeIf { it > 0f } ?: 0f
                val dur = win?.effectiveDurationMs?.takeIf { it > 0L } ?: (overview?.totalDurationMs ?: 0L)
                val powerRate = calculateDischargeRatePercentPerHour(w, totalCapacityWh, dur)
                val sessionPercentRate = if (overview != null && overview.totalDurationMs > 0L) {
                    val totalPct = overview.screenOnPercent + overview.screenOffPercent
                    val hrs = overview.totalDurationMs / 3600000.0f
                    if (totalPct > 0f && hrs > 0.05f) totalPct / hrs else null
                } else null
                powerRate ?: win?.dischargeRatePercentPerHour ?: sessionPercentRate
            }
            else -> null
        }
    }

    /**
     * 计算并获取自本次拔电以来的当前会话实测放电速度及有效时长。
     * 针对亮屏、息屏与全局综合维度，优先基于本次拔电会话的实测做功功率及电池满充能量计算高精度放电速率，
     * 仅在总容量不可用或功率缺失时降级采用基于电量掉电差值推算，忠实反映真实设备会话数据。
     *
     * @param rowType 行分类标识（[BatteryEnergyCalculator.ROW_SCREEN_ON] 等）
     * @param totalCapacityWh 设备满充总能量容量（单位：Wh）
     * @param overview 当前放电会话统计实体 [PowerOverviewStats]，若无数据传入 null
     * @return 包含会话放电速度（单位：%/h，缺失时为 null）与对应会话有效持续时长（单位：毫秒，缺失时为 null）的配对对象 [Pair]
     */
    fun calculateSessionDischargeRate(
        rowType: Int,
        totalCapacityWh: Float?,
        overview: PowerOverviewStats?
    ): Pair<Float?, Long?> {
        if (overview == null) return Pair(null, null)
        return when (rowType) {
            BatteryEnergyCalculator.ROW_SCREEN_ON -> {
                val dur = overview.screenOnDurationMs
                val w = overview.screenOnPowerWatts.takeIf { it > 0f }
                val powerRate = if (w != null && totalCapacityWh != null && totalCapacityWh > 0f) {
                    calculateDischargeRatePercentPerHour(w, totalCapacityWh, dur)
                } else null
                val sessionPercentRate = if (overview.screenOnPercent > 0f && dur > 0L) {
                    val hrs = dur / 3600000.0f
                    if (hrs > 0.005f) overview.screenOnPercent / hrs else null
                } else null
                val effectiveRate = powerRate ?: sessionPercentRate
                val effectiveDur = if (effectiveRate != null && dur > 0L) dur else null
                Pair(effectiveRate, effectiveDur)
            }
            BatteryEnergyCalculator.ROW_SCREEN_OFF -> {
                val dur = overview.screenOffDurationMs
                val w = overview.screenOffPowerWatts.takeIf { it > 0f }
                val powerRate = if (w != null && totalCapacityWh != null && totalCapacityWh > 0f) {
                    calculateDischargeRatePercentPerHour(w, totalCapacityWh, dur)
                } else null
                val sessionPercentRate = if (overview.screenOffPercent > 0f && dur > 0L) {
                    val hrs = dur / 3600000.0f
                    if (hrs > 0.005f) overview.screenOffPercent / hrs else null
                } else null
                val effectiveRate = powerRate ?: sessionPercentRate
                val effectiveDur = if (effectiveRate != null && dur > 0L) dur else null
                Pair(effectiveRate, effectiveDur)
            }
            BatteryEnergyCalculator.ROW_GLOBAL -> {
                val dur = overview.totalDurationMs
                val w = overview.avgPowerWatts.takeIf { it > 0f }
                val totalPct = overview.screenOnPercent + overview.screenOffPercent
                val powerRate = if (w != null && totalCapacityWh != null && totalCapacityWh > 0f) {
                    calculateDischargeRatePercentPerHour(w, totalCapacityWh, dur)
                } else null
                val sessionPercentRate = if (totalPct > 0f && dur > 0L) {
                    val hrs = dur / 3600000.0f
                    if (hrs > 0.005f) totalPct / hrs else null
                } else null
                val effectiveRate = powerRate ?: sessionPercentRate
                val effectiveDur = if (effectiveRate != null && dur > 0L) dur else null
                Pair(effectiveRate, effectiveDur)
            }
            else -> Pair(null, null)
        }
    }

    /**
     * 根据放电平均功耗与电池总容量能量计算每小时放电百分比速率（%/h）。
     *
     * 遵循物理学定义：
     * 功率 P (W) = 能量 E (Wh) / 时间 t (h)；
     * 每小时放电百分比 Rate (%/h) = (P (W) / 电池总容量 E_total (Wh)) * 100%。
     * 严禁编写任何假数据或虚拟钳位，当功率或电池总能量无效时返回 null。
     *
     * @param powerWatts 放电平均功率（单位：W）
     * @param totalCapacityWh 电池标称/满充总能量容量（单位：Wh）
     * @param durationMs 对应时段实际持续时长（毫秒）
     * @return 计算得到的每小时放电百分比（单位：%/h），若数据缺失或非正数则返回 null
     */
    fun calculateDischargeRatePercentPerHour(
        powerWatts: Float,
        totalCapacityWh: Float?,
        durationMs: Long
    ): Float? {
        if (totalCapacityWh == null || !totalCapacityWh.isFinite() || totalCapacityWh <= 0f) {
            return null
        }
        if (!powerWatts.isFinite() || powerWatts <= 0f || durationMs <= 0L) {
            return null
        }
        val rate = (powerWatts / totalCapacityWh) * 100f
        return if (rate.isFinite() && rate > 0f) rate else null
    }

    /**
     * 将放电速度数值与单位转换为复合富文本，单位部分（"%/h"）比前面的数值缩小 2 号显示。
     *
     * @param rate 放电速率百分比数值（单位：%/h），若为空或非正数则返回 "--"
     * @return 格式化后的富文本对象 [CharSequence]
     */
    fun formatDischargeRateSpannable(rate: Float?): CharSequence {
        if (rate == null || rate <= 0f) {
            return "--"
        }
        val numberStr = String.format(Locale.getDefault(), "%.1f", rate)
        val unitStr = "%/h"
        val fullText = "$numberStr$unitStr"
        return UnitSpanFormatter.format(fullText)
    }

    /**
     * 将实际统计放电速度与有效时长组合格式化为复合富文本。
     * 放电百分比数值与单位的文字样式大小完全对齐第一行放电速度百分比（单位比数值缩小 2 号），
     * 实际有效统计时长紧随其后（如 "5.2%/h 1d08h"），采用次要文本颜色与紧凑字号（10sp 常规字重）呈现。
     * 若数据缺失则展示 "--"。
     *
     * @param context Android 上下文对象，用于解析系统主题次要文字颜色
     * @param rate 统计放电速率百分比（单位：%/h）
     * @param durationMs 7 天窗口内实际有效统计时长（单位：毫秒）
     * @return 格式化后的富文本对象 [CharSequence]
     */
    fun formatSecondaryDischargeRateSpannable(
        context: Context,
        rate: Float?,
        durationMs: Long?
    ): CharSequence {
        if (rate == null || rate <= 0f) {
            return "--"
        }
        val numberStr = String.format(Locale.getDefault(), "%.1f", rate)
        val unitStr = "%/h"
        val durStr = formatActualStatsDurationText(durationMs)
        val fullText = if (durStr.isNotBlank()) "$numberStr$unitStr $durStr" else "$numberStr$unitStr"
        val spannable = SpannableString(fullText)

        // "%/h" 单位字号比数值缩小 2 号
        spannable.setSpan(
            UnitSpanFormatter.SmallerUnitSpan(2f),
            numberStr.length,
            numberStr.length + unitStr.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        // 时长部分保持次要紧凑字号（10sp）、常规字重与主题次要文本颜色
        if (durStr.isNotBlank()) {
            val durStart = numberStr.length + unitStr.length + 1
            val typedArray = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary))
            val secondaryColor = typedArray.getColor(0, 0x8A000000.toInt())
            typedArray.recycle()

            spannable.setSpan(
                AbsoluteSizeSpan(10, true),
                durStart,
                fullText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannable.setSpan(
                StyleSpan(Typeface.NORMAL),
                durStart,
                fullText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannable.setSpan(
                ForegroundColorSpan(secondaryColor),
                durStart,
                fullText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return spannable
    }

    /**
     * 将充满电可用时长与满电说明标签组合格式化为紧凑富文本。
     * 时长数值位于前面（如 "19h14m"），满电说明标签位于后面（如 "19h14m 满电续航"）。
     * 若时长无效或数据缺失则展示 "--"。
     *
     * @param context Android 上下文对象
     * @param ms 充满电可用物理时长毫秒数
     * @return 格式化后的富文本对象 [CharSequence]
     */
    fun formatFullDurationTextWithSuffix(context: Context, ms: Long?): CharSequence {
        if (ms == null || ms <= 0L) {
            return "--"
        }
        val durText = formatFullDurationText(ms)
        if (durText == "--") return "--"
        val label = context.getString(R.string.power_discharge_full_label)
        val fullText = "$durText $label"
        val spannable = SpannableString(fullText)

        // 时长数值加粗突出
        spannable.setSpan(
            StyleSpan(Typeface.BOLD),
            0,
            durText.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        // 满电标签字号微缩至 10sp
        val labelStart = durText.length + 1
        spannable.setSpan(
            AbsoluteSizeSpan(10, true),
            labelStart,
            fullText.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return spannable
    }

    /**
     * 将预测剩余续航时长与剩余说明标签组合格式化为紧凑富文本。
     * 时长数值位于前面（如 "17h46m"），剩余说明标签位于后面（如 "17h46m 剩余续航"）。
     * 若时长无效或数据缺失则展示 "--"。
     *
     * @param context Android 上下文对象
     * @param ms 预测剩余可用物理时长毫秒数
     * @return 格式化后的富文本对象 [CharSequence]
     */
    fun formatRemainingDurationTextWithSuffix(context: Context, ms: Long?): CharSequence {
        if (ms == null || ms <= 0L) {
            return "--"
        }
        val durText = BatteryEnergyCalculator.formatCardRemainingDuration(ms)
        if (durText == "--") return "--"
        val label = context.getString(R.string.power_discharge_remaining_label)
        val fullText = "$durText $label"
        val spannable = SpannableString(fullText)

        // 时长数值加粗突出
        spannable.setSpan(
            StyleSpan(Typeface.BOLD),
            0,
            durText.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        // 剩余标签微缩至 10sp 并应用次要文本颜色
        val labelStart = durText.length + 1
        spannable.setSpan(
            AbsoluteSizeSpan(10, true),
            labelStart,
            fullText.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        val typedArray = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary))
        val secondaryColor = typedArray.getColor(0, 0x8A000000.toInt())
        typedArray.recycle()
        spannable.setSpan(
            ForegroundColorSpan(secondaryColor),
            labelStart,
            fullText.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return spannable
    }

    /**
     * 将满电使用时间物理毫秒数格式化为紧凑易读的时长文本。
     * 当持续时间大于等于 1 天时输出为 "XdXXh" 格式（如 05d05h）；
     * 当持续时间大于等于 1 小时时输出为 "XhXXm" 格式（如 19h14m）；
     * 否则输出为 "Xm" 格式（如 45m）。
     *
     * @param ms 充满电可用物理时长毫秒数
     * @return 格式化后的紧凑时长字符串，数据无效时返回 "--"
     */
    fun formatFullDurationText(ms: Long): String {
        if (ms <= 0L) return "--"
        val totalSec = ms / 1000L
        val days = totalSec / 86400L
        val hours = (totalSec % 86400L) / 3600L
        val minutes = (totalSec % 3600L) / 60L
        return when {
            days > 0L -> String.format(Locale.getDefault(), "%02dd%02dh", days, hours)
            hours > 0L -> String.format(Locale.getDefault(), "%02dh%02dm", hours, minutes)
            else -> String.format(Locale.getDefault(), "%02dm", minutes)
        }
    }

    /**
     * 将 7 天滑动窗口内实际有效统计时长（毫秒）格式化为紧凑时长文本。
     * 当持续时间大于等于 7 天时显示为 "7d"；
     * 当持续时间大于等于 1 天时显示为 "XdXXh" 格式（如 01d08h、05d19h）；
     * 当持续时间大于等于 1 小时时显示为 "XhXXm" 格式（如 04h30m、18h20m）；
     * 否则显示为 "Xm" 格式（如 25m、05m）。
     *
     * @param ms 实际有效统计时长物理毫秒数
     * @return 格式化后的紧凑时长字符串，数据无效时返回空字符串
     */
    fun formatActualStatsDurationText(ms: Long?): String {
        if (ms == null || ms <= 0L) return ""
        val totalSec = ms / 1000L
        val days = totalSec / 86400L
        val hours = (totalSec % 86400L) / 3600L
        val minutes = (totalSec % 3600L) / 60L
        return when {
            days >= 7L -> "7d"
            days > 0L -> String.format(Locale.getDefault(), "%02dd%02dh", days, hours)
            hours > 0L -> String.format(Locale.getDefault(), "%02dh%02dm", hours, minutes)
            else -> String.format(Locale.getDefault(), "%02dm", minutes.coerceAtLeast(1L))
        }
    }

    /**
     * 格式化卡片平均放电功耗数值为紧凑单位文本。
     * 遵循物理客观真值，当平均功耗大于等于 0.01W 时保留两位小数（如 "0.25W"、"2.05W"）；
     * 当平均功耗在 (0W, 0.01W) 微弱区间时保留三位小数（如 "0.008W"），杜绝四舍五入为 0.00W；
     * 当功耗等于 0W 时展示为 "0.00W"；数据缺失时返回 null。
     *
     * @param watts 物理实测或能量积分推导的平均放电功率（单位：W）
     * @return 格式化后的紧凑功耗字符串（如 "0.25W"），数值无效时返回 null
     */
    fun formatCardPowerWatts(watts: Float?): String? {
        if (watts == null || watts < 0f || watts.isNaN()) return null
        return if (watts >= 0.01f) {
            String.format(Locale.US, "%.2fW", watts)
        } else if (watts > 0f) {
            String.format(Locale.US, "%.3fW", watts)
        } else {
            "0.00W"
        }
    }

    /**
     * 将包含中英文字符或冒号格式的时长文本解析换算为对应的物理毫秒数。
     *
     * @param text 待解析的时长文本内容
     * @return 解析得出的时长毫秒数值，若为空或无法解析返回 0L
     */
    fun parseDurationTextToMs(text: String): Long {
        if (text.isBlank() || text == "--") return 0L
        var totalMs = 0L
        try {
            val dMatch = Regex("(\\d+)\\s*(?:天|d)").find(text)
            val hMatch = Regex("(\\d+)\\s*(?:小时|h)").find(text)
            val mMatch = Regex("(\\d+)\\s*(?:分|m)").find(text)
            val sMatch = Regex("(\\d+)\\s*(?:秒|s)").find(text)

            if (dMatch != null || hMatch != null || mMatch != null || sMatch != null) {
                dMatch?.groupValues?.get(1)?.toLongOrNull()?.let { totalMs += it * 86400000L }
                hMatch?.groupValues?.get(1)?.toLongOrNull()?.let { totalMs += it * 3600000L }
                mMatch?.groupValues?.get(1)?.toLongOrNull()?.let { totalMs += it * 60000L }
                sMatch?.groupValues?.get(1)?.toLongOrNull()?.let { totalMs += it * 1000L }
                return totalMs
            }

            val colonMatch = Regex("(\\d+):(\\d+)(?::(\\d+))?").find(text)
            if (colonMatch != null) {
                val parts = colonMatch.destructured
                if (parts.component3().isNotEmpty()) {
                    val h = parts.component1().toLongOrNull() ?: 0L
                    val m = parts.component2().toLongOrNull() ?: 0L
                    val s = parts.component3().toLongOrNull() ?: 0L
                    return (h * 3600L + m * 60L + s) * 1000L
                } else {
                    val m = parts.component1().toLongOrNull() ?: 0L
                    val s = parts.component2().toLongOrNull() ?: 0L
                    return (m * 60L + s) * 1000L
                }
            }
        } catch (_: Exception) {}
        return totalMs
    }

    /**
     * 将息屏、唤醒与深度睡眠三状态指标卡片的持续时长毫秒数格式化为紧凑易读的时长文本。
     * 遵循业务呈现规范：
     * 1. 若持续时间超过 1 小时（hours > 0），则不显示秒数，输出时分格式（如 "06h53m"）；
     * 2. 否则（不足 1 小时，hours == 0），则显示秒数：
     *    - 持续时间大于等于 1 分钟时输出分秒格式（如 "19m01s"）；
     *    - 持续时间小于 1 分钟时输出秒数格式（如 "45s"、"00s"）。
     *
     * @param durationMs 物理持续时长毫秒数值
     * @param fallbackText 当时长毫秒数值小于等于 0 时的备用文本（若非空则优先解析物理毫秒值，无法解析时作为兜底展示）
     * @return 格式化后的紧凑卡片时长字符串
     */
    fun formatSleepAwakeCardDuration(durationMs: Long, fallbackText: String = ""): String {
        val effectiveMs = if (durationMs > 0L) {
            durationMs
        } else if (fallbackText.isNotBlank()) {
            parseDurationTextToMs(fallbackText)
        } else {
            0L
        }

        if (effectiveMs <= 0L) {
            return if (fallbackText.isNotBlank() && fallbackText != "0s" && fallbackText != "00s") {
                fallbackText
            } else {
                "00s"
            }
        }

        val totalSec = effectiveMs / 1000L
        val hours = totalSec / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val seconds = totalSec % 60L

        return when {
            hours > 0L -> String.format(Locale.getDefault(), "%02dh%02dm", hours, minutes)
            minutes > 0L -> String.format(Locale.getDefault(), "%02dm%02ds", minutes, seconds)
            else -> String.format(Locale.getDefault(), "%02ds", seconds)
        }
    }

    /**
     * 将预测剩余可用物理时长毫秒数格式化为卡片简明时长数值（如 "03h16m"）。
     * 严禁伪造或虚构数据，若时长无效或数据缺失则真实返回 "--"。
     *
     * @param ms 预测剩余可用物理时长毫秒数（单位：毫秒）
     * @return 格式化后的时长数值字符串（如 "03h16m"），若无效则返回 "--"
     */
    fun formatRemainingDurationOnly(ms: Long?): String {
        if (ms == null || ms <= 0L) {
            return "--"
        }
        val durText = BatteryEnergyCalculator.formatCardRemainingDuration(ms)
        return if (durText == "--") "--" else durText
    }

    /**
     * 将升级模型满电预测续航物理时长格式化为带斜杠分割符号的紧凑显示文本（如 "/ 15h41m"）。
     * 遵循系统底层物理数据真实性规范，若时长无效、小于等于 0 或数据缺失则如实返回 "/ --"，严禁伪造或虚构数据。
     *
     * @param ms 升级模型满电预测可用物理时长毫秒数（单位：毫秒，若无数据传入 null）
     * @return 包含斜杠分割符的前缀文本（如 "/ 15h41m" 或 "/ --"）
     */
    fun formatFullDurationWithSlash(ms: Long?): String {
        if (ms == null || ms <= 0L) return "/ --"
        val durText = formatFullDurationText(ms)
        return if (durText == "--") "/ --" else "/ $durText"
    }

    /**
     * 计算剩余续航相较于升级模型满电预测续航的进度占比数值（取值范围 0 ~ 1000）。
     * 遵循系统数据真实性规范，严禁编写任何假数据或虚构占位数据；
     * 当剩余续航或满电续航任一无效、为 null 或小于等于 0 时，如实返回 0；
     * 当两者均有效且为正数时，计算真实物理比例并换算为 0 ~ 1000 范围整数（若比例超出 1.0 则以 1000 封顶）。
     *
     * @param remainingMs 剩余可用物理时长（单位：毫秒，若无数据传入 null）
     * @param fullMs 升级模型满电预测可用物理时长（单位：毫秒，若无数据传入 null）
     * @return 换算为 0 ~ 1000 标度的进度整数值
     */
    fun calculateRuntimeProgress(remainingMs: Long?, fullMs: Long?): Int {
        if (remainingMs == null || fullMs == null || remainingMs <= 0L || fullMs <= 0L) {
            return 0
        }
        val ratio = (remainingMs.toDouble() / fullMs.toDouble()).coerceIn(0.0, 1.0)
        return (ratio * 1000.0).roundToInt().coerceIn(0, 1000)
    }

    /**
     * 计算剩余续航与升级模型满电续航的进度占比并更新线性进度指示条。
     * 仅在主动刷新数据（animate = true）且目标进度发生真实变化时，启动平滑缓慢增长或减少的物理过渡动画；
     * 当处于列表上下滑动或常规视图复用场景时（animate = false），静默直接设置目标进度，严禁从 0 重新加载或播放入场动效；
     * 当剩余可用时长有效但升级模型满电时长尚未完成计算（如全量下拉刷新异步计算中）时，保持当前进度不变，彻底杜绝中间态归零闪烁。
     *
     * @param progressBar 目标线性进度指示条组件 [LinearProgressIndicator]
     * @param remainingMs 剩余可用物理时长（单位：毫秒，若无数据传入 null）
     * @param fullMs 升级模型满电预测可用物理时长（单位：毫秒，若无数据传入 null）
     * @param animate 是否启用平滑缓慢过渡动画，默认为 false（仅在全量数据刷新就绪时传 true）
     */
    fun updateRuntimeProgressBar(
        progressBar: LinearProgressIndicator,
        remainingMs: Long?,
        fullMs: Long?,
        animate: Boolean = false
    ) {
        // 若剩余可用时长有效但满电时长尚未就绪（如后台异步计算 7 天统计中），保持当前进度不变，杜绝中途归零闪烁
        if (remainingMs != null && remainingMs > 0L && (fullMs == null || fullMs <= 0L) && progressBar.progress > 0) {
            return
        }

        val targetProgress = calculateRuntimeProgress(remainingMs, fullMs)
        val currentProgress = progressBar.progress

        // 若目标进度与当前进度一致，无需任何更新操作，避免重复重绘
        if (currentProgress == targetProgress) {
            return
        }

        if (animate && progressBar.isAttachedToWindow) {
            // 仅在明确刷新数据且视图已挂载时，启动平滑缓慢增长或减少的物理过渡动画
            progressBar.setProgressCompat(targetProgress, true)
        } else {
            // 列表滚动复用或未启用动画时，静默快速更新到目标值，严禁重置为 0 或重新播放动画
            progressBar.setProgressCompat(targetProgress, false)
        }
    }

    /**
     * 响应时间轴游标滑动事件，以极轻量无重排方式高频局部刷新放电速度与息屏睡眠卡片。
     * 仅执行文本级直接更新，不重新调用布局度量，零 GC、零测量抖动。
     * 支持根据当前所选电量单位（Wh 或 mAh）自适应切换息屏、唤醒与深度睡眠卡片的副指标与功率排版，
     * 且右侧各项指标严格靠右对齐。
     *
     * @param speedBinding 放电速度布局绑定对象 [ItemPowerDischargeSpeedBinding]
     * @param sleepBinding 息屏细分状态布局绑定对象 [ItemPowerSleepAwakeMetricsBinding]
     * @param subStats 阶段子区间累计统计快照对象 [com.battery.analysis.timeline.util.SubIntervalOverviewStats]
     * @param unit 当前电量显示单位 [PowerDisplayUnit]，默认为 [PowerDisplayUnit.WH]
     */
    fun updateCursorInspectionStats(
        speedBinding: ItemPowerDischargeSpeedBinding,
        sleepBinding: ItemPowerSleepAwakeMetricsBinding,
        subStats: com.battery.analysis.timeline.util.SubIntervalOverviewStats,
        unit: PowerDisplayUnit = PowerDisplayUnit.WH
    ) {
        val isMahUnit = (unit == PowerDisplayUnit.MAH)

        // 1. 刷新放电速度概览卡片第一行（当前阶段放电速度速率 %/h）
        with(speedBinding) {
            tvScreenOnSpeed.setUnitText(formatDischargeRateSpannable(subStats.screenOnDischargeRate))
            tvScreenOffSpeed.setUnitText(formatDischargeRateSpannable(subStats.screenOffDischargeRate))
            tvGlobalSpeed.setUnitText(formatDischargeRateSpannable(subStats.globalDischargeRate))
        }

        // 2. 刷新息屏、唤醒与深度睡眠三卡片指标
        val noRecordText = sleepBinding.root.context.getString(R.string.power_metric_no_record)
        with(sleepBinding) {
            // 2.1 息屏卡片
            if (subStats.screenOffDurationMs <= 0L && subStats.screenOffEnergyWh <= 0f) {
                tvScreenOffPercent.text = "--"
                tvScreenOffDuration.text = noRecordText
                tvScreenOffEnergy.text = "--"
                tvScreenOffCompare.text = "--"
                tvScreenOffPower.text = "--"
            } else {
                tvScreenOffPercent.setUnitText(if (subStats.screenOffPercent > 0f) {
                    String.format(Locale.US, "%.1f%%", subStats.screenOffPercent)
                } else "--")

                val durText = formatSleepAwakeCardDuration(subStats.screenOffDurationMs)
                val energyText = String.format(Locale.US, "%.3fWh", subStats.screenOffEnergyWh)
                val offPowerText = formatCardPowerWatts(subStats.screenOffPowerWatts) ?: "--"
                val mahStr = String.format(Locale.US, "%.1fmAh", subStats.screenOffDrainMah)

                tvScreenOffDuration.text = durText
                if (isMahUnit) {
                    val mahSubtitle = if (subStats.screenOffDrainMah > 0f) String.format(Locale.US, "%.1fmAh", subStats.screenOffDrainMah) else "--"
                    val energyDisplay = String.format(Locale.US, "%.3fWh", subStats.screenOffEnergyWh)
                    tvScreenOffEnergy.setUnitText(mahSubtitle)
                    tvScreenOffCompare.setUnitText(energyDisplay)
                    tvScreenOffPower.setUnitText(offPowerText)
                } else {
                    tvScreenOffEnergy.setUnitText(energyText)
                    tvScreenOffCompare.setUnitText(mahStr)
                    tvScreenOffPower.setUnitText(offPowerText)
                }
            }

            // 2.2 唤醒卡片
            if (subStats.screenOffAwakeDurationMs <= 0L && subStats.screenOffAwakeEnergyWh <= 0f) {
                if (subStats.screenOffDurationMs > 0L) {
                    tvAwakePercent.setUnitText("0.0%")
                    tvAwakeDuration.text = "00s"
                    if (isMahUnit) {
                        tvAwakeEnergy.setUnitText("0.0mAh")
                        tvAwakeCompare.setUnitText("0.000Wh")
                        tvAwakePower.setUnitText("0.00W")
                    } else {
                        tvAwakeEnergy.setUnitText("0.000Wh")
                        tvAwakeCompare.setUnitText("0.0mAh")
                        tvAwakePower.setUnitText("0.00W")
                    }
                } else {
                    tvAwakePercent.text = "--"
                    tvAwakeDuration.text = noRecordText
                    tvAwakeEnergy.text = "--"
                    tvAwakeCompare.text = "--"
                    tvAwakePower.text = "--"
                }
            } else {
                val awakePercentStr = if (subStats.isScreenOffDecomposedAvailable && subStats.screenOffAwakePercent > 0f) {
                    String.format(Locale.US, "%.1f%%", subStats.screenOffAwakePercent)
                } else if (subStats.isScreenOffDecomposedAvailable && subStats.screenOffAwakeEnergyWh > 0f) {
                    "<0.1%"
                } else if (subStats.screenOffAwakeDurationMs > 0L) {
                    "0.0%"
                } else "--"
                tvAwakePercent.setUnitText(awakePercentStr)

                val durText = formatSleepAwakeCardDuration(subStats.screenOffAwakeDurationMs)
                val energyText = if (subStats.isScreenOffDecomposedAvailable && subStats.screenOffAwakeEnergyWh >= 0.001f) {
                    String.format(Locale.US, "%.3fWh", subStats.screenOffAwakeEnergyWh)
                } else if (subStats.isScreenOffDecomposedAvailable && subStats.screenOffAwakeEnergyWh > 0f) {
                    "<0.001Wh"
                } else "0.000Wh"

                val awakePowerText = formatCardPowerWatts(subStats.screenOffAwakePowerWatts) ?: "--"
                val mahStr = String.format(Locale.US, "%.1fmAh", subStats.screenOffAwakeDrainMah)

                tvAwakeDuration.text = durText

                if (isMahUnit) {
                    val awakeMahSubtitle = if (subStats.isScreenOffDecomposedAvailable && subStats.screenOffAwakeDrainMah >= 0.1f) {
                        String.format(Locale.US, "%.1fmAh", subStats.screenOffAwakeDrainMah)
                    } else if (subStats.isScreenOffDecomposedAvailable && subStats.screenOffAwakeDrainMah > 0f) {
                        "<0.1mAh"
                    } else if (subStats.screenOffAwakeDurationMs > 0L) {
                        "0.0mAh"
                    } else "--"
                    val energyDisplay = if (subStats.isScreenOffDecomposedAvailable && subStats.screenOffAwakeEnergyWh >= 0.001f) {
                        String.format(Locale.US, "%.3fWh", subStats.screenOffAwakeEnergyWh)
                    } else if (subStats.isScreenOffDecomposedAvailable && subStats.screenOffAwakeEnergyWh > 0f) {
                        "<0.001Wh"
                    } else if (subStats.screenOffAwakeDurationMs > 0L) {
                        "0.000Wh"
                    } else "--"

                    tvAwakeEnergy.setUnitText(awakeMahSubtitle)
                    tvAwakeCompare.setUnitText(energyDisplay)
                    tvAwakePower.setUnitText(awakePowerText)
                } else {
                    tvAwakeEnergy.setUnitText(energyText)
                    tvAwakeCompare.setUnitText(mahStr)
                    tvAwakePower.setUnitText(awakePowerText)
                }
            }

            // 2.3 深度睡眠卡片
            if (subStats.screenOffDeepSleepDurationMs <= 0L && subStats.screenOffDeepSleepEnergyWh <= 0f) {
                if (subStats.screenOffDurationMs > 0L) {
                    tvSleepPercent.setUnitText("0.0%")
                    tvSleepDuration.text = "00s"
                    if (isMahUnit) {
                        tvSleepEnergy.setUnitText("0.0mAh")
                        tvSleepCompare.setUnitText("0.000Wh")
                        tvSleepPower.setUnitText("0.00W")
                    } else {
                        tvSleepEnergy.setUnitText("0.000Wh")
                        tvSleepCompare.setUnitText("0.0mAh")
                        tvSleepPower.setUnitText("0.00W")
                    }
                } else {
                    tvSleepPercent.text = "--"
                    tvSleepDuration.text = noRecordText
                    tvSleepEnergy.text = "--"
                    tvSleepCompare.text = "--"
                    tvSleepPower.text = "--"
                }
            } else {
                val sleepPercentStr = if (subStats.isScreenOffDecomposedAvailable && subStats.screenOffDeepSleepPercent > 0f) {
                    String.format(Locale.US, "%.1f%%", subStats.screenOffDeepSleepPercent)
                } else "--"
                tvSleepPercent.setUnitText(sleepPercentStr)

                val durText = formatSleepAwakeCardDuration(subStats.screenOffDeepSleepDurationMs)
                val energyText = if (subStats.isScreenOffDecomposedAvailable && subStats.screenOffDeepSleepEnergyWh > 0f) {
                    String.format(Locale.US, "%.3fWh", subStats.screenOffDeepSleepEnergyWh)
                } else "--"

                val sleepPowerText = formatCardPowerWatts(subStats.screenOffDeepSleepPowerWatts) ?: "--"
                val mahStr = String.format(Locale.US, "%.1fmAh", subStats.screenOffDeepSleepDrainMah)

                tvSleepDuration.text = durText

                if (isMahUnit) {
                    val sleepMahSubtitle = if (subStats.isScreenOffDecomposedAvailable && subStats.screenOffDeepSleepDrainMah >= 0.1f) {
                        String.format(Locale.US, "%.1fmAh", subStats.screenOffDeepSleepDrainMah)
                    } else if (subStats.isScreenOffDecomposedAvailable && subStats.screenOffDeepSleepDrainMah > 0f) {
                        "<0.1mAh"
                    } else if (subStats.screenOffDeepSleepDurationMs > 0L) {
                        "0.0mAh"
                    } else "--"
                    val energyDisplay = if (subStats.isScreenOffDecomposedAvailable && subStats.screenOffDeepSleepEnergyWh > 0f) {
                        String.format(Locale.US, "%.3fWh", subStats.screenOffDeepSleepEnergyWh)
                    } else "--"

                    tvSleepEnergy.setUnitText(sleepMahSubtitle)
                    tvSleepCompare.setUnitText(energyDisplay)
                    tvSleepPower.setUnitText(sleepPowerText)
                } else {
                    tvSleepEnergy.setUnitText(energyText)
                    tvSleepCompare.setUnitText(mahStr)
                    tvSleepPower.setUnitText(sleepPowerText)
                }
            }
        }
    }
}
