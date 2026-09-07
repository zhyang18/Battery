package com.battery.analysis.timeline.util

import com.battery.analysis.timeline.domain.BatterySample

/**
 * 电池时序曲线高保真视觉降采样工具。
 * 针对长时间跨度（如 24 小时 86,400 个点）的海量数据，采用分桶 Min-Max 极值保留算法，
 * 在保证峰值、谷值与关键阶跃不丢失的前提下将绘制点集压缩至屏幕像素适配范围（如 1000 ~ 2000 点），确保 60fps 丝滑绘制。
 */
object ChartDownsampler {

    /**
     * 对采样点列表进行分桶极值保真降采样。
     *
     * @param rawSamples 原始完整物理采样点列表 [List<BatterySample>]
     * @param targetMaxPoints 降采样后目标最大点数
     * @return 降采样后的采样点序列 [List<BatterySample>]
     */
    fun downsample(
        rawSamples: List<BatterySample>,
        targetMaxPoints: Int = 1500
    ): List<BatterySample> {
        if (rawSamples.size <= targetMaxPoints || targetMaxPoints <= 4) {
            return rawSamples
        }

        val result = mutableListOf<BatterySample>()
        // 始终保留首点
        result.add(rawSamples.first())

        val innerPoints = rawSamples.subList(1, rawSamples.size - 1)
        val numBuckets = targetMaxPoints / 2
        val bucketSize = innerPoints.size.toDouble() / numBuckets

        for (bucketIdx in 0 until numBuckets) {
            val startIdx = (bucketIdx * bucketSize).toInt().coerceIn(0, innerPoints.size - 1)
            val endIdx = ((bucketIdx + 1) * bucketSize).toInt().coerceIn(startIdx + 1, innerPoints.size)

            val bucket = innerPoints.subList(startIdx, endIdx)
            if (bucket.isEmpty()) continue

            // 提取该桶内的功率最高点与最低点
            var minSample = bucket[0]
            var maxSample = bucket[0]

            for (sample in bucket) {
                if (sample.powerMw < minSample.powerMw) {
                    minSample = sample
                }
                if (sample.powerMw > maxSample.powerMw) {
                    maxSample = sample
                }
            }

            // 按时间先后顺序将极值放入结果集中
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
}
