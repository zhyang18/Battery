package com.battery.analysis.domain.capacity.audit

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.battery.analysis.model.PowerDischargePoint
import com.battery.analysis.model.PowerUsageRecord
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 放电原始采样数据一键导出与跨应用分享助手（DischargeDataExportHelper）。
 *
 * 遵循技术评审 P2-B3.1-A 核心铁律：
 * 1. 忠实保留硬件底层原始读数、符号极性与标称物理单位，绝不伪造或人为补齐脏数据；
 * 2. 导出字段与 [DischargeAuditCsvReplayer] 只读审计回放引擎完全自洽对接；
 * 3. 支持系统公共 Download 目录保存与基于 FileProvider 的安全原生分享双轨分发。
 */
object DischargeDataExportHelper {

    /**
     * 读取操作系统当前的引导标识符（boot_id）。
     *
     * @return 64 位或 UUID 格式的 boot_id 字符串，若无法读取则返回 null
     */
    fun getSystemBootId(): String? {
        return try {
            val file = File("/proc/sys/kernel/random/boot_id")
            if (file.exists() && file.canRead()) {
                file.readText().trim()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 将单次放电会话实体转换为符合标准审计规范的 CSV 文本内容。
     *
     * 导出的 CSV 表头与字段严格对接 [DischargeAuditCsvReplayer]：
     * timestamp_ms,soc,voltage_mv,current_ma,temp_c,power_w,screen_on,charge_counter_uah,boot_id
     *
     * @param record 待导出的耗电历史记录实体 [PowerUsageRecord]
     * @return 格式化后的 CSV 字符串
     */
    fun buildDischargeCsv(record: PowerUsageRecord): String {
        val points = record.parseTrendPointsList()
        val bootId = getSystemBootId() ?: ""
        val sb = StringBuilder()

        // 写入标准 CSV 表头
        sb.append("timestamp_ms,soc,voltage_mv,current_ma,temp_c,power_w,screen_on,charge_counter_uah,boot_id\n")

        for (pt in points) {
            val timeMs = pt.timestamp
            val soc = pt.batteryLevel.toDouble()
            val voltageMv = String.format(Locale.US, "%.1f", pt.voltageVolts * 1000.0)

            // 放电电流极性与物理量纲保留：
            // 1. 优先使用经严格芯片与厂商自适应识别的标准放电毫安值 pt.dischargeCurrentMa；
            // 2. 次选通过 BatteryUnitNormalizer 严格自适应识别底层 pt.batteryCurrentUa 的真实量纲（避免将荣耀/华为原生 mA 误除以 1000）；
            // 3. 严格遵循物理流出极性负值表示（< 0）。
            val currentMa = when {
                pt.dischargeCurrentMa != null && pt.dischargeCurrentMa > 0.0 -> {
                    String.format(Locale.US, "%.1f", -pt.dischargeCurrentMa)
                }
                pt.batteryCurrentUa != null -> {
                    val normMa = com.battery.analysis.util.BatteryUnitNormalizer.normalizeCurrentMa(
                        pt.batteryCurrentUa.toLong(),
                        isCharging = false
                    )
                    if (normMa > 0f) {
                        String.format(Locale.US, "%.1f", -normMa)
                    } else {
                        ""
                    }
                }
                else -> ""
            }

            val tempC = String.format(Locale.US, "%.1f", pt.temperature)
            val powerW = String.format(Locale.US, "%.2f", pt.powerWatts)
            val screenOn = if (pt.isScreenOn) "1" else "0"
            val counterUah = pt.chargeCounterUah?.toString() ?: ""

            sb.append(String.format(
                Locale.US,
                "%d,%.1f,%s,%s,%s,%s,%s,%s,%s\n",
                timeMs, soc, voltageMv, currentMa, tempC, powerW, screenOn, counterUah, bootId
            ))
        }

        return sb.toString()
    }

    /**
     * 执行完整的放电数据导出流程：生成 CSV、写入文件，并弹出完成提示与分享菜单。
     *
     * @param activity 当前所在的 Android 宿主 Activity
     * @param record 当前需要导出的耗电记录 [PowerUsageRecord]
     */
    fun exportAndShareDischargeData(activity: Activity, record: PowerUsageRecord) {
        val points = record.parseTrendPointsList()
        if (points.isEmpty()) {
            Toast.makeText(activity, "当前放电记录缺少高频物理采样点，无法导出", Toast.LENGTH_SHORT).show()
            return
        }

        val csvContent = buildDischargeCsv(record)
        val startSoc = record.getStartBatteryLevel()
        val endSoc = record.levelPercent
        val timeFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val recordTs = if (points.first().timestamp > 0L) points.first().timestamp else record.id
        val timeStr = timeFormat.format(Date(recordTs))
        val fileName = "Discharge_Record_${timeStr}_${startSoc}to${endSoc}pct.csv"

        // 1. 保存到应用外部缓存供 FileProvider 跨进程安全分享
        val cacheFile = saveToCache(activity, fileName, csvContent)

        // 2. 尝试保存到系统公共 Download 目录
        val savedPublicPath = saveToPublicDownload(activity, fileName, csvContent)

        // 3. 构建原生系统分享 Intent
        val shareIntent = if (cacheFile != null && cacheFile.exists()) {
            try {
                val fileUri: Uri = FileProvider.getUriForFile(
                    activity,
                    "${activity.packageName}.fileprovider",
                    cacheFile
                )
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, fileUri)
                    putExtra(Intent.EXTRA_SUBJECT, fileName)
                    putExtra(Intent.EXTRA_TEXT, "电池放电原始数据审计文件: $fileName")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        } else {
            null
        }

        // 4. 弹出精美操作弹窗供用户选择分享或查看路径
        val displayPath = savedPublicPath ?: cacheFile?.absolutePath ?: "Download/BatteryAnalysis/$fileName"
        showExportSuccessDialog(activity, fileName, displayPath, points.size, startSoc, endSoc, shareIntent)
    }

    /**
     * 将 CSV 文本持久化保存到应用的缓存共享目录。
     *
     * @param context 应用程序上下文
     * @param fileName 保存的目标文件名
     * @param content CSV 文件文本内容
     * @return 成功生成并写入的本地缓存文件 [File]，若失败返回 null
     */
    private fun saveToCache(context: Context, fileName: String, content: String): File? {
        return try {
            val exportDir = File(context.cacheDir, "exports")
            if (!exportDir.exists()) {
                exportDir.mkdirs()
            }
            val file = File(exportDir, fileName)
            FileOutputStream(file).use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
            }
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 将 CSV 文本写入系统的公共下载（Download）目录。
     *
     * @param context 应用程序上下文
     * @param fileName 目标文件名
     * @param content CSV 文本内容
     * @return 文件保存的友好物理显示路径，若写入失败返回 null
     */
    private fun saveToPublicDownload(context: Context, fileName: String, content: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BatteryAnalysis")
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        out.write(content.toByteArray(Charsets.UTF_8))
                    }
                    "Download/BatteryAnalysis/$fileName"
                } else {
                    null
                }
            } else {
                @Suppress("DEPRECATION")
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetDir = File(downloadDir, "BatteryAnalysis")
                if (!targetDir.exists()) {
                    targetDir.mkdirs()
                }
                val file = File(targetDir, fileName)
                FileOutputStream(file).use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                }
                file.absolutePath
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 弹出导出成功的引导确认弹窗。
     *
     * @param activity 宿主 Activity
     * @param fileName 文件名称
     * @param filePath 保存路径
     * @param pointCount 有效采样点总数
     * @param startSoc 起始电量（%）
     * @param endSoc 结束电量（%）
     * @param shareIntent 系统分享 Intent，若不可用则为 null
     */
    private fun showExportSuccessDialog(
        activity: Activity,
        fileName: String,
        filePath: String,
        pointCount: Int,
        startSoc: Int,
        endSoc: Int,
        shareIntent: Intent?
    ) {
        val message = "放电原始采样数据已成功生成！\n\n" +
                "• 文件名称: $fileName\n" +
                "• 保存路径: $filePath\n" +
                "• 采样点数: $pointCount 个采样点\n" +
                "• 放电跨度: $startSoc% → $endSoc%\n\n" +
                "数据格式已符合 P2-B3.1-A 审计标准，可直接用于工况分析与容量回放。"

        val builder = AlertDialog.Builder(activity)
            .setTitle("放电数据导出成功")
            .setMessage(message)
            .setPositiveButton("知道了") { dialog, _ ->
                dialog.dismiss()
                Toast.makeText(activity, "已保存至: $filePath", Toast.LENGTH_LONG).show()
            }

        if (shareIntent != null) {
            builder.setNeutralButton("分享/发送") { dialog, _ ->
                dialog.dismiss()
                activity.startActivity(Intent.createChooser(shareIntent, "发送放电数据 CSV"))
            }
        }

        builder.show()
    }
}
