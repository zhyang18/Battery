package com.battery.analysis.domain.estimator

import com.battery.analysis.model.CandidateSessionType
import com.battery.analysis.model.CapacityCandidate
import com.battery.analysis.model.MaturityLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 稳健满充容量（FCC）融合估计引擎单元测试类。
 */
class RobustFccEstimatorTest {

    /**
     * 辅助构造测试候选样本。
     *
     * @param id 样本 ID
     * @param type 充放电类型
     * @param startSoc 起始电量
     * @param endSoc 结束电量
     * @param netMah 净电量（mAh）
     * @param fccObserved 推算 FCC（mAh）
     * @return 构造出的候选样本实体
     */
    private fun createCandidate(
        id: Long,
        type: CandidateSessionType,
        startSoc: Int,
        endSoc: Int,
        netMah: Double,
        fccObserved: Double
    ): CapacityCandidate {
        val span = kotlin.math.abs(endSoc - startSoc)
        return CapacityCandidate(
            id = id,
            sessionType = type,
            startSoc = startSoc,
            endSoc = endSoc,
            socSpanPercent = span,
            netBatteryChargeMah = netMah,
            fccObservedMah = fccObserved,
            startTimestamp = id,
            endTimestamp = id + 3_600_000L,
            durationMs = 3_600_000L,
            avgTemperatureCelsius = 25.0f,
            maxTemperatureCelsius = 28.0f,
            sampleCount = 100,
            qualityScore = 1.0
        )
    }

    /**
     * 测试一阶误差传递方差的规律：
     * 电量跨度越大（如 60% vs 20%），由于 SOC 整数量化误差被更大跨度稀释，其方差必须显著更小。
     */
    @Test
    fun testErrorPropagationVarianceScalesWithSocSpan() {
        // 跨度 20%：netMah = 1000, span = 0.20 -> FCC = 5000
        val varianceSmallSpan = RobustFccEstimator.calculateSampleVariance(1000.0, 0.20)
        // 跨度 60%：netMah = 3000, span = 0.60 -> FCC = 5000
        val varianceLargeSpan = RobustFccEstimator.calculateSampleVariance(3000.0, 0.60)

        assertTrue(
            "大跨度样本的不确定度方差必须远小于小跨度样本",
            varianceLargeSpan < varianceSmallSpan
        )
    }

    /**
     * 测试 MAD 离群点过滤功能。
     * 注入 5 个围绕 5000 mAh 的正常样本，再注入一个 8500 mAh 的离群脏样本，
     * 验证该离群脏数据被自动过滤，加权结果收敛在 5000 mAh 附近。
     */
    @Test
    fun testMadOutlierRejection() {
        val candidates = listOf(
            createCandidate(1L, CandidateSessionType.CHARGE, 20, 60, 2000.0, 5000.0),
            createCandidate(2L, CandidateSessionType.CHARGE, 30, 70, 2020.0, 5050.0),
            createCandidate(3L, CandidateSessionType.DISCHARGE, 80, 40, 1980.0, 4950.0),
            createCandidate(4L, CandidateSessionType.CHARGE, 15, 65, 2510.0, 5020.0),
            createCandidate(5L, CandidateSessionType.DISCHARGE, 90, 40, 2490.0, 4980.0),
            // 离群脏数据（例如由于插拔干扰误测出的 8500 mAh）
            createCandidate(6L, CandidateSessionType.CHARGE, 20, 35, 1275.0, 8500.0)
        )

        val result = RobustFccEstimator.estimateFusedFcc(candidates)
        assertNotNull(result.estimatedFccMah)
        // 最终估计值必须受到保护，仍在 5000 附近 (误差 < 50 mAh)，不受 8500 拉偏
        assertEquals(5000.0, result.estimatedFccMah!!, 60.0)
        assertEquals(5, result.validCandidateCount) // 8500 被剔除，剩下 5 个有效样本
    }

    /**
     * 测试充放电双向相对冲突率 D 的计算。
     */
    @Test
    fun testCrossDirectionConflictRateCalculation() {
        val candidates = listOf(
            // 充电样本均值 5000
            createCandidate(1L, CandidateSessionType.CHARGE, 20, 60, 2000.0, 5000.0),
            // 放电样本均值 4900
            createCandidate(2L, CandidateSessionType.DISCHARGE, 80, 40, 1960.0, 4900.0)
        )

        val result = RobustFccEstimator.estimateFusedFcc(candidates)
        assertNotNull(result.chargeDischargeConflictPercent)
        // D = |5000 - 4900| / 4950 * 100% ≈ 2.02%
        assertEquals(2.02, result.chargeDischargeConflictPercent!!, 0.1)
    }

    /**
     * 测试单样本边界工况：
     * 具备估计容量与初始成熟度，但由于单样本无法做统计离散度推断，置信半径必须如实返回 null。
     */
    @Test
    fun testSingleCandidateHasNullConfidenceInterval() {
        val candidate = createCandidate(1L, CandidateSessionType.CHARGE, 20, 60, 2000.0, 5000.0)
        val result = RobustFccEstimator.estimateFusedFcc(listOf(candidate))
        assertNotNull(result.estimatedFccMah)
        assertEquals(5000.0, result.estimatedFccMah!!, 0.01)
        assertEquals(MaturityLevel.INITIAL_OBSERVATION, result.maturityLevel)
        assertNull("单样本时置信区间半径必须为 null", result.confidenceIntervalMah)
    }

    /**
     * 测试零假数据原则：在空样本或全为排除样本时，如实返回 null 与 PENDING。
     */
    @Test
    fun testZeroFakeDataPrincipleOnEmptyCandidates() {
        val result = RobustFccEstimator.estimateFusedFcc(emptyList())
        assertNull(result.estimatedFccMah)
        assertNull(result.sampleStdDevMah)
        assertNull(result.dispersionPercent)
        assertEquals(MaturityLevel.PENDING, result.maturityLevel)
        assertEquals(0, result.validCandidateCount)
    }

    /**
     * 测试自学习有效数据百分比门限动态筛选效果：
     * 构造 12%, 18%, 25%, 60% 等不同跨度充放电样本，
     * 验证门限分别在 15%、20%、50% 时过滤出的样本数与会话分类计数准确。
     */
    @Test
    fun testSocSpanThresholdFiltering() {
        val candidates = listOf(
            createCandidate(1L, CandidateSessionType.CHARGE, 20, 32, 600.0, 5000.0), // span = 12%
            createCandidate(2L, CandidateSessionType.CHARGE, 30, 48, 900.0, 5000.0), // span = 18%
            createCandidate(3L, CandidateSessionType.DISCHARGE, 80, 55, 1250.0, 5000.0), // span = 25%
            createCandidate(4L, CandidateSessionType.CHARGE, 20, 80, 3000.0, 5000.0), // span = 60%
            createCandidate(5L, CandidateSessionType.DISCHARGE, 90, 20, 3500.0, 5000.0) // span = 70%
        )

        // 门限 > 10%：所有 5 个样本均纳入
        val list10 = candidates.filter { it.socSpanPercent >= 10 }
        val res10 = RobustFccEstimator.estimateFusedFcc(list10)
        assertEquals(5, res10.validCandidateCount)
        assertEquals(3, res10.chargeCandidateCount)
        assertEquals(2, res10.dischargeCandidateCount)

        // 门限 > 15%：排除 12% 样本，剩余 4 个
        val list15 = candidates.filter { it.socSpanPercent >= 15 }
        val res15 = RobustFccEstimator.estimateFusedFcc(list15)
        assertEquals(4, res15.validCandidateCount)
        assertEquals(2, res15.chargeCandidateCount)
        assertEquals(2, res15.dischargeCandidateCount)

        // 门限 > 20%：排除 12% 与 18% 样本，剩余 3 个
        val list20 = candidates.filter { it.socSpanPercent >= 20 }
        val res20 = RobustFccEstimator.estimateFusedFcc(list20)
        assertEquals(3, res20.validCandidateCount)
        assertEquals(1, res20.chargeCandidateCount)
        assertEquals(2, res20.dischargeCandidateCount)

        // 门限 > 50%：仅保留 60% 与 70% 深度充放样本，剩余 2 个
        val list50 = candidates.filter { it.socSpanPercent >= 50 }
        val res50 = RobustFccEstimator.estimateFusedFcc(list50)
        assertEquals(2, res50.validCandidateCount)
        assertEquals(1, res50.chargeCandidateCount)
        assertEquals(1, res50.dischargeCandidateCount)

        // 门限 100%：无样本达标，模型安全回退至 PENDING
        val list100 = candidates.filter { it.socSpanPercent >= 100 }
        val res100 = RobustFccEstimator.estimateFusedFcc(list100)
        assertEquals(0, res100.validCandidateCount)
        assertEquals(MaturityLevel.PENDING, res100.maturityLevel)
        assertNull(res100.estimatedFccMah)
    }

    /**
     * 测试自学习有效数据条数时效窗口（Count Limit）筛选效果：
     * 模拟用户半年前的老电池数据（FCC ~4500 mAh）与近期更换电芯/近期健康状态（FCC ~4950 mAh）。
     * 验证限制条数窗口能成功淘汰半年前的老数据，使容量模型准确聚焦近期真实性能。
     */
    @Test
    fun testSampleCountLimitWindowFiltering() {
        val oldTimestamp = 1_000_000L
        val recentTimestamp = 20_000_000L

        // 构造 10 条半年前的老旧合格会话（FCC 为 4500 mAh）
        val oldCandidates = (1..10).map { i ->
            createCandidate(
                id = i.toLong(),
                type = if (i % 2 == 0) CandidateSessionType.CHARGE else CandidateSessionType.DISCHARGE,
                startSoc = 20,
                endSoc = 70,
                netMah = 2250.0,
                fccObserved = 4500.0
            )
        }

        // 构造 5 条近期的合格会话（FCC 为 4950 mAh，时间戳更新，排在最前面）
        val recentCandidates = (11..15).map { i ->
            createCandidate(
                id = i.toLong(),
                type = if (i % 2 == 0) CandidateSessionType.CHARGE else CandidateSessionType.DISCHARGE,
                startSoc = 20,
                endSoc = 70,
                netMah = 2475.0,
                fccObserved = 4950.0
            )
        }

        // 模拟数据库按 end_time DESC 返回的所有样本（最新 5 条在前，老旧 10 条在后）
        val allCandidatesDesc = recentCandidates.reversed() + oldCandidates.reversed()

        val threshold = 15

        // 1. 无条数限制（全部数据，countLimit = 0）：老旧数据填充有效数据，FCC 被严重拉低
        val eligibleAll = allCandidatesDesc.filter { !it.isExcluded && it.socSpanPercent >= threshold && it.fccObservedMah > 0.0 }
        val resAll = RobustFccEstimator.estimateFusedFcc(eligibleAll)
        assertEquals(15, resAll.validCandidateCount)
        // 10 个 4500 和 5 个 4950 的均值必定远低于 4900
        assertTrue("全量历史数据应被老旧数据拉低", resAll.estimatedFccMah!! < 4700.0)

        // 2. 设置最新有效数据条数窗口为 5 条（countLimit = 5）：彻底淘汰 10 条老数据，仅保留最新 5 条
        val countLimit5 = 5
        val qualified5 = eligibleAll.take(countLimit5)
        assertEquals(5, qualified5.size)
        val res5 = RobustFccEstimator.estimateFusedFcc(qualified5)
        assertEquals(5, res5.validCandidateCount)
        assertEquals(4950.0, res5.estimatedFccMah!!, 5.0)

        // 3. 设置最新有效数据条数窗口为 3 条（countLimit = 3）：极速敏感追踪，仅保留最新 3 条
        val countLimit3 = 3
        val qualified3 = eligibleAll.take(countLimit3)
        assertEquals(3, qualified3.size)
        val res3 = RobustFccEstimator.estimateFusedFcc(qualified3)
        assertEquals(3, res3.validCandidateCount)
        assertEquals(4950.0, res3.estimatedFccMah!!, 5.0)

        // 4. 验证排除异常点或未达门限的记录不会占用有效条数窗口配额
        val defectiveCandidate = createCandidate(
            id = 99L,
            type = CandidateSessionType.CHARGE,
            startSoc = 10,
            endSoc = 60,
            netMah = 200.0,
            fccObserved = 400.0
        ).copy(isExcluded = true)

        val candidatesWithDefective = listOf(defectiveCandidate) + allCandidatesDesc
        val eligibleClean = candidatesWithDefective.filter { !it.isExcluded && it.socSpanPercent >= threshold && it.fccObservedMah > 0.0 }
        val qualifiedClean5 = eligibleClean.take(5)
        assertEquals(5, qualifiedClean5.size)
        assertTrue("被排除的脏样本不应占用有效条数配额", qualifiedClean5.none { it.id == 99L })
        val resClean5 = RobustFccEstimator.estimateFusedFcc(qualifiedClean5)
        assertEquals(4950.0, resClean5.estimatedFccMah!!, 5.0)
    }
}
