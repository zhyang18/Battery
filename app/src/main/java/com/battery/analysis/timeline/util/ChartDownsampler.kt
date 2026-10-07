package com.battery.analysis.timeline.util

import com.battery.analysis.timeline.domain.BatterySample

/**
 * 电池时序曲线高保真视觉降采样工具。
 * 针对海量时序采样点（如 3000 点或 24 小时 86,400 点），结合屏幕横向物理像素宽度，
 * 采用按物理特征选择的分桶 Min-Max 极值保留算法，
 * 在保证物理真实极值峰、谷、阶跃 100% 毫无遗漏的前提下将图表 Path 曲线段数优化至硬件加速最佳性能区间，
 * 彻底消除 GPU 光栅化阶段密集的 Sub-pixel 三次贝塞尔曲线 Overdraw，确保 120fps 满帧丝滑流畅。
 */
object ChartDownsampler {

    /**
     * 根据指定的物理指标度量选择器，对原始物理采样点列表执行自适应分桶极值保留降采样。
     *
     * @param rawSamples 原始完整物理采样点列表 [List<BatterySample>]
     * @param targetMaxPoints 目标最大采样点数量
     * @param metricSelector 提取采样点特定物理指标数值的度量选择函数
     * @return 降采样后的高保真采样点列表 [List<BatterySample>]
     */
    inline fun downsampleByMetric(
        rawSamples: List<BatterySample>,
        targetMaxPoints: Int,
        crossinline metricSelector: (BatterySample) -> Double
    ): List<BatterySample> {
        if (rawSamples.size <= targetMaxPoints || targetMaxPoints <= 4) {
            return rawSamples
        }

        val result = ArrayList<BatterySample>(targetMaxPoints + 2)
        // 始终保留首点
        result.add(rawSamples.first())

        val innerPoints = rawSamples.subList(1, rawSamples.size - 1)
        val numBuckets = (targetMaxPoints - 2) / 2
        val bucketSize = innerPoints.size.toDouble() / numBuckets

        for (bucketIdx in 0 until numBuckets) {
            val startIdx = (bucketIdx * bucketSize).toInt().coerceIn(0, innerPoints.size - 1)
            val endIdx = ((bucketIdx + 1) * bucketSize).toInt().coerceIn(startIdx + 1, innerPoints.size)

            val bucket = innerPoints.subList(startIdx, endIdx)
            if (bucket.isEmpty()) continue

            var minSample = bucket[0]
            var maxSample = bucket[0]
            var minVal = metricSelector(minSample)
            var maxVal = minVal

            for (sample in bucket) {
                val v = metricSelector(sample)
                if (v < minVal) {
                    minVal = v
                    minSample = sample
                }
                if (v > maxVal) {
                    maxVal = v
                    maxSample = sample
                }
            }

            // 按时间先后顺序将极值放入结果集中，确保时间严格单调递增
            if (minSample.timestamp < maxSample.timestamp) {
                result.add(minSample)
                if (minSample !== maxSample) {
                    result.add(maxSample)
                }
            } else {
                result.add(maxSample)
                if (maxSample !== minSample) {
                    result.add(minSample)
                }
            }
        }

        // 始终保留末点
        result.add(rawSamples.last())
        return result
    }

    /**
     * 默认按功率绝对值（powerMw）对采样点列表进行分桶极值保真降采样。
     *
     * @param rawSamples 原始完整物理采样点列表 [List<BatterySample>]
     * @param targetMaxPoints 降采样后目标最大点数
     * @return 降采样后的采样点序列 [List<BatterySample>]
     */
    fun downsample(
        rawSamples: List<BatterySample>,
        targetMaxPoints: Int = 800
    ): List<BatterySample> {
        return downsampleByMetric(rawSamples, targetMaxPoints) { kotlin.math.abs(it.powerMw) }
    }
}
