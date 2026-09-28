package com.battery.analysis.util

import android.util.Log
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Shizuku 远程进程与其绑定的跨进程 Binder 代理（[android.os.BinderProxy]）及死亡监听器（[android.os.IBinder.DeathRecipient]）深度清理工具。
 *
 * <p>深度背景：
 * Rikka Shizuku SDK 内部在创建 [java.lang.Process]（实际类型为 rikka.shizuku.ShizukuRemoteProcess）时，
 * 在其构造方法中将每个实例存入私有静态强引用集合 `CACHE` 中，并在底层 Binder 上调用了 `linkToDeath`。
 * 由于 Shizuku 服务端长期驻留存活，该 `DeathRecipient` 永远无法自然触发 `binderDied`，
 * 且 SDK 提供的 `destroy()` 方法并未从 `CACHE` 中剔除自身，形成死锁闭环与强引用 GC 根驻留。
 *
 * 本清理器借助安全反射穿透内部私有状态，主动将废弃进程从全局静态缓存中抹除，
 * 并切断对其远程 Binder 的强引用，促使底层 BpBinder 析构并注销死亡监听器，从根本上解决 Proxy Binders 与 Death Recipients 泄漏。
 */
object ShizukuProcessCleaner {

    private const val TAG = "ShizukuProcessCleaner"

    /** 静态缓存的 ShizukuRemoteProcess 类引用 */
    @Volatile
    private var shizukuRemoteProcessClass: Class<*>? = null

    /** 静态缓存的 CACHE 字段反射引用 */
    @Volatile
    private var cacheField: Field? = null

    /** 静态缓存的 remote 字段反射引用 */
    @Volatile
    private var remoteField: Field? = null

    /** 静态缓存的 is（标准输入流）字段反射引用 */
    @Volatile
    private var isField: Field? = null

    /** 静态缓存的 os（标准输出流）字段反射引用 */
    @Volatile
    private var osField: Field? = null

    /** 静态缓存的 lambda$new$0 清理方法反射引用 */
    @Volatile
    private var lambdaCleanMethod: Method? = null

    /** 是否已完成反射元数据探测 */
    @Volatile
    private var hasInitializedReflection: Boolean = false

    /**
     * 初始化 ShizukuRemoteProcess 相关私有字段与方法的反射引用。
     */
    private fun initReflectionIfNeeded() {
        if (hasInitializedReflection) return
        synchronized(this) {
            if (hasInitializedReflection) return
            try {
                val clazz = Class.forName("rikka.shizuku.ShizukuRemoteProcess")
                shizukuRemoteProcessClass = clazz

                cacheField = clazz.getDeclaredField("CACHE").apply { isAccessible = true }
                remoteField = clazz.getDeclaredField("remote").apply { isAccessible = true }
                isField = clazz.getDeclaredField("is").apply { isAccessible = true }
                osField = clazz.getDeclaredField("os").apply { isAccessible = true }

                try {
                    lambdaCleanMethod = clazz.declaredMethods.firstOrNull {
                        it.name.startsWith("lambda\$new\$") && it.parameterTypes.isEmpty()
                    }?.apply { isAccessible = true } ?: clazz.getDeclaredMethod("lambda\$new\$0").apply { isAccessible = true }
                } catch (_: Throwable) {
                    lambdaCleanMethod = null
                }
            } catch (t: Throwable) {
                Log.w(TAG, "初始化 ShizukuRemoteProcess 反射字段失败: ${t.message}")
            } finally {
                hasInitializedReflection = true
            }
        }
    }

    /**
     * 针对单个特定的 [Process] 对象执行深度清理与跨进程 Binder 解除绑定。
     *
     * @param process 需要彻底销毁并清理 Binder 资源的进程实例
     */
    fun cleanProcess(process: java.lang.Process) {
        initReflectionIfNeeded()
        val targetClass = shizukuRemoteProcessClass
        if (targetClass == null || !targetClass.isInstance(process)) {
            return
        }

        try {
            // 1. 调用 lambda$new$0 回调逻辑（若存在）
            try {
                lambdaCleanMethod?.invoke(process)
            } catch (_: Throwable) {}

            // 2. 从静态 CACHE 集合中强制移除当前实例，彻底消除 GC Root 强引用
            try {
                val cacheObj = cacheField?.get(null)
                if (cacheObj is MutableCollection<*>) {
                    synchronized(cacheObj) {
                        cacheObj.remove(process)
                    }
                }
            } catch (_: Throwable) {}

            // 3. 将 remote、is、os 成员变量置为 null，打破与 BinderProxy 的强引用链
            try {
                remoteField?.set(process, null)
            } catch (_: Throwable) {}

            try {
                isField?.set(process, null)
            } catch (_: Throwable) {}

            try {
                osField?.set(process, null)
            } catch (_: Throwable) {}
        } catch (t: Throwable) {
            Log.w(TAG, "清理单个 Shizuku 远程进程失败: ${t.message}")
        }
    }

    /**
     * 批量扫描并强制肃清全局静态 `CACHE` 中滞留的所有已结束或悬挂的 Shizuku 远程进程。
     * 建议在 Service 销毁、内存紧缩（[android.content.ComponentCallbacks2.onTrimMemory]）或应用冷启动时主动调用。
     *
     * @return 成功清理并剥离的滞留远程进程对象总数
     */
    fun purgeDanglingProcesses(): Int {
        initReflectionIfNeeded()
        val cacheObj = try {
            cacheField?.get(null)
        } catch (_: Throwable) {
            null
        } ?: return 0

        var purgedCount = 0
        try {
            if (cacheObj is MutableCollection<*>) {
                val toRemoveList = mutableListOf<Any>()
                synchronized(cacheObj) {
                    for (item in cacheObj) {
                        if (item != null) {
                            toRemoveList.add(item)
                        }
                    }
                }

                for (proc in toRemoveList) {
                    try {
                        if (proc is java.lang.Process) {
                            try {
                                proc.inputStream?.close()
                            } catch (_: Throwable) {}
                            try {
                                proc.errorStream?.close()
                            } catch (_: Throwable) {}
                            try {
                                proc.outputStream?.close()
                            } catch (_: Throwable) {}
                            try {
                                proc.destroy()
                            } catch (_: Throwable) {}

                            cleanProcess(proc)
                            purgedCount++
                        }
                    } catch (_: Throwable) {}
                }

                synchronized(cacheObj) {
                    cacheObj.removeAll(toRemoveList.toSet())
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "批量清理滞留 Shizuku 远程进程异常: ${t.message}")
        }

        if (purgedCount > 0) {
            Log.i(TAG, "已成功深度回收 $purgedCount 个滞留的 ShizukuRemoteProcess 及关联 BinderProxy")
        }
        return purgedCount
    }
}
