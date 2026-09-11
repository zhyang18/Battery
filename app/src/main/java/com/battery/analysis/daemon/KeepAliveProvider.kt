package com.battery.analysis.daemon

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.battery.analysis.service.BatteryMonitorService

/**
 * 特权守护进程反向穿透拉活 ContentProvider。
 *
 * 利用 Android 系统底层架构中访问 ContentProvider 会无条件拉起目标应用宿主进程（不受 Android 12+ 前台服务后台启动限制）的特性，
 * 为独立特权守护进程提供高可靠性的秒级反向穿透拉活通道。
 */
class KeepAliveProvider : ContentProvider() {

    /**
     * ContentProvider 初始化生命周期回调。
     * 当收到外部穿透请求唤醒进程时，在此检查并自动恢复后台电池监控前台服务。
     *
     * @return 初始化是否成功，恒定返回 true
     */
    override fun onCreate(): Boolean {
        val ctx = context ?: return true
        // 唤醒进程后，若用户配置了开启后台监控，立即恢复前台服务
        try {
            if (BatteryMonitorService.shouldServiceRun(ctx)) {
                BatteryMonitorService.start(ctx)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return true
    }

    /**
     * 响应外部 query 穿透请求，触发前台监控服务的自愈启动。
     *
     * @param uri 穿透请求的目标 Uri
     * @param projection 返回列投影数组
     * @param selection 查询过滤条件
     * @param selectionArgs 查询过滤参数数组
     * @param sortOrder 排序规则
     * @return 查询结果游标，始终返回 null
     */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val ctx = context
        if (ctx != null) {
            try {
                if (BatteryMonitorService.shouldServiceRun(ctx)) {
                    BatteryMonitorService.start(ctx)
                }
            } catch (_: Exception) {}
        }
        return null
    }

    /**
     * 返回 MIME 类型，无需特殊类型支持。
     *
     * @param uri 目标 Uri
     * @return 恒定返回 null
     */
    override fun getType(uri: Uri): String? = null

    /**
     * 插入数据接口，不支持数据写入。
     *
     * @param uri 目标 Uri
     * @param values 待插入数据键值对
     * @return 恒定返回 null
     */
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    /**
     * 删除数据接口，不支持数据删除。
     *
     * @param uri 目标 Uri
     * @param selection 删除条件
     * @param selectionArgs 删除参数数组
     * @return 影响行数，恒定返回 0
     */
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    /**
     * 更新数据接口，不支持数据更新。
     *
     * @param uri 目标 Uri
     * @param values 待更新数据键值对
     * @param selection 更新条件
     * @param selectionArgs 更新参数数组
     * @return 影响行数，恒定返回 0
     */
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    /**
     * 响应外部 call 自定义穿透方法请求。
     *
     * @param method 调用方法名称
     * @param arg 附加调用参数
     * @param extras 扩展 Bundle 数据包
     * @return 执行结果 Bundle
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context
        if (ctx != null) {
            try {
                if (BatteryMonitorService.shouldServiceRun(ctx)) {
                    BatteryMonitorService.start(ctx)
                }
            } catch (_: Exception) {}
        }
        return Bundle().apply {
            putBoolean("revived", true)
        }
    }
}
