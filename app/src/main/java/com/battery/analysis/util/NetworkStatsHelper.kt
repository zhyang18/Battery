package com.battery.analysis.util

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.RemoteException

/**
 * 网络流量统计辅助工具类。
 * 封装 Android 官方公开的 [NetworkStatsManager] 服务，支持按应用 UID 与指定起止时间戳
 * 精确检索 Wi-Fi 与移动网络（Mobile）的上行（Tx）与下行（Rx）流量字节数。
 *
 * @param context Android 上下文环境
 */
class NetworkStatsHelper(private val context: Context) {

    private val networkStatsManager: NetworkStatsManager? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
        } else {
            null
        }
    }

    /**
     * 查询指定 UID 在给定时间段内的全部网络流量（包含 Wi-Fi 与移动网络双通道的接收与发送字节数之和）。
     *
     * @param uid 目标应用程序的 Android 系统 UID
     * @param startTime 查询起始时间戳（毫秒）
     * @param endTime 查询结束时间戳（毫秒）
     * @return 传输的总字节数（单位：字节 Byte，发生异常或无数据时返回 0L）
     */
    fun getUidNetworkBytes(uid: Int, startTime: Long, endTime: Long): Long {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return 0L
        }
        val nsm = networkStatsManager ?: return 0L
        if (startTime >= endTime || uid < 0) {
            return 0L
        }

        var totalBytes = 0L

        // 1. 统计 Wi-Fi 通道流量
        @Suppress("DEPRECATION")
        totalBytes += queryBytesForNetworkType(nsm, ConnectivityManager.TYPE_WIFI, null, uid, startTime, endTime)

        // 2. 统计移动蜂窝网络（Mobile）通道流量
        @Suppress("DEPRECATION")
        totalBytes += queryBytesForNetworkType(nsm, ConnectivityManager.TYPE_MOBILE, null, uid, startTime, endTime)

        return totalBytes
    }

    /**
     * 查询特定网络通道类型下某个 UID 的接收与发送总字节数。
     *
     * @param nsm 系统 NetworkStatsManager 实例
     * @param networkType 网络通道类型（如 [ConnectivityManager.TYPE_WIFI] 或 [ConnectivityManager.TYPE_MOBILE]）
     * @param subscriberId 用户识别码（SIM 卡 IMSI，传 null 表示统计当前活跃网络）
     * @param uid 目标系统 UID
     * @param startTime 起始时间戳（毫秒）
     * @param endTime 结束时间戳（毫秒）
     * @return 该网络类型下传输的字节总数
     */
    @Suppress("DEPRECATION")
    private fun queryBytesForNetworkType(
        nsm: NetworkStatsManager,
        networkType: Int,
        subscriberId: String?,
        uid: Int,
        startTime: Long,
        endTime: Long
    ): Long {
        var bytes = 0L
        var stats: NetworkStats? = null
        try {
            stats = nsm.queryDetailsForUid(networkType, subscriberId, startTime, endTime, uid)
            val bucket = NetworkStats.Bucket()
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket)
                val rx = bucket.rxBytes
                val tx = bucket.txBytes
                if (rx > 0L) bytes += rx
                if (tx > 0L) bytes += tx
            }
        } catch (_: SecurityException) {
            // 未授予 PACKAGE_USAGE_STATS 权限时安全降级
        } catch (_: RemoteException) {
            // 系统远程 IPC 调用异常时安全降级
        } catch (_: Exception) {
            // 其他边界异常安全降级
        } finally {
            try {
                stats?.close()
            } catch (_: Exception) {}
        }
        return bytes
    }
}
