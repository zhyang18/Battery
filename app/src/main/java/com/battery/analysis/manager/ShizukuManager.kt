package com.battery.analysis.manager

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import com.battery.analysis.MainActivity
import com.battery.analysis.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import com.battery.analysis.util.ShizukuShellExecutor

/**
 * Shizuku 提权服务统一状态管理器与授权生命周期控制器。
 * 负责管理用户对 Shizuku 提权的主动停用偏好持久化、检测提权可用性与授权状态、
 * 执行解除授权（系统层权限撤销、客户端缓存清理、工作模式回退）以及启动 Shizuku 管理器应用。
 */
object ShizukuManager {

    private const val PREFS_NAME = "battery_app_settings"
    private const val KEY_SHIZUKU_USER_DISABLED = "pref_shizuku_user_disabled"
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val SHIZUKU_PERMISSION = "moe.shizuku.manager.permission.API_V23"

    /**
     * 检查用户是否主动在设置中解除了对 Shizuku 的提权使用。
     *
     * @param context 应用程序上下文
     * @return 若用户已主动解除 Shizuku 提权返回 true，否则返回 false
     */
    fun isUserDisabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_SHIZUKU_USER_DISABLED, false)
    }

    /**
     * 设置并持久化用户是否主动停用 Shizuku 提权使用的偏好。
     *
     * @param context 应用程序上下文
     * @param disabled 是否主动解除/停用 Shizuku 提权
     */
    fun setUserDisabled(context: Context, disabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_SHIZUKU_USER_DISABLED, disabled).apply()
    }

    /**
     * 检查当前环境是否能够使用 Shizuku 服务（服务已运行且未被用户主动停用）。
     *
     * @param context 应用程序上下文
     * @return 若服务已连接且未被用户停用返回 true，否则返回 false
     */
    fun isShizukuAvailable(context: Context): Boolean {
        if (isUserDisabled(context)) return false
        return try {
            Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 检查当前应用是否拥有 Shizuku 授权（服务运行中、已授权且未被用户主动停用）。
     *
     * @param context 应用程序上下文
     * @return 若已完全授权返回 true，否则返回 false
     */
    fun isAuthorized(context: Context): Boolean {
        if (isUserDisabled(context)) return false
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 打开 Shizuku 官方管理器应用，供用户在系统层面管理已授权的应用列表。
     *
     * @param context 应用程序上下文
     * @return 成功唤起应用返回 true，未安装或启动失败返回 false
     */
    fun openShizukuApp(context: Context): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            if (intent != null) {
                if (context !is android.app.Activity) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            } else {
                Toast.makeText(context, context.getString(R.string.toast_shizuku_not_found), Toast.LENGTH_LONG).show()
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, context.getString(R.string.toast_shizuku_not_found), Toast.LENGTH_LONG).show()
            false
        }
    }

    /**
     * 主动解除当前应用对 Shizuku 的提权授权。
     * 立即将持久化标记设为停用，将耗电统计模式自动回退至标准模式，
     * 并在后台异步尝试执行 pm revoke 系统撤销命令及清理客户端内部静态缓存，确保 UI 与数据即时更新。
     *
     * @param activity 宿主 [MainActivity] 实例
     * @param scope 用于执行底层异步任务的协程作用域
     * @param onComplete 解除完成后的回调函数，参数为成功标识及可选错误说明
     */
    fun revokeAuthorization(
        activity: MainActivity,
        scope: CoroutineScope,
        onComplete: ((Boolean, String?) -> Unit)? = null
    ) {
        // 1. 立即持久化记录用户主动解除授权状态
        setUserDisabled(activity, true)

        // 2. 若当前耗电统计工作在 Shizuku 模式，自动回退切回标准模式
        val powerManager = PowerUsageManager.getInstance(activity)
        if (powerManager.getSelectedMode() == PowerUsageManager.MODE_SHIZUKU) {
            powerManager.setSelectedMode(PowerUsageManager.MODE_NORMAL)
        }

        // 3. 立即刷新主界面的 Shizuku 状态为未授权
        activity.updateShizukuStatusState()

        // 4. 清理 Shizuku 客户端内部的 permissionGranted 静态缓存
        try {
            val field = Shizuku::class.java.getDeclaredField("permissionGranted")
            field.isAccessible = true
            field.set(null, false)
        } catch (_: Throwable) {
        }

        // 5. 在后台异步尝试通过 privileged shell 执行系统 pm revoke 命令
        scope.launch(Dispatchers.IO) {
            var revokeSuccess = true
            var errorDetail: String? = null

            try {
                if (ShizukuShellExecutor.isAvailable()) {
                    ShizukuShellExecutor.execute("pm revoke ${activity.packageName} $SHIZUKU_PERMISSION")
                }
            } catch (e: Exception) {
                revokeSuccess = false
                errorDetail = e.message
            }

            withContext(Dispatchers.Main) {
                activity.updateShizukuStatusState()
                Toast.makeText(activity, activity.getString(R.string.toast_shizuku_revoke_success), Toast.LENGTH_SHORT).show()
                onComplete?.invoke(revokeSuccess, errorDetail)
            }
        }
    }

    /**
     * 发起重新向 Shizuku 请求提权授权或唤起 Shizuku 应用。
     * 自动重置用户主动停用状态并调用系统授权对话框。
     *
     * @param activity 宿主 [MainActivity] 实例
     * @param requestCode 权限申请请求码
     */
    fun requestAuthorization(activity: MainActivity, requestCode: Int) {
        // 清除用户主动停用标记，重新尝试连接
        setUserDisabled(activity, false)

        if (Shizuku.pingBinder()) {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                try {
                    Shizuku.requestPermission(requestCode)
                } catch (e: Exception) {
                    Toast.makeText(activity, activity.getString(R.string.toast_request_auth_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(activity, activity.getString(R.string.toast_already_authorized), Toast.LENGTH_SHORT).show()
                activity.updateShizukuStatusState()
            }
        } else {
            Toast.makeText(activity, activity.getString(R.string.toast_shizuku_not_connected), Toast.LENGTH_SHORT).show()
            openShizukuApp(activity)
            activity.updateShizukuStatusState()
        }
    }
}
