package com.battery.analysis.util

import java.lang.Process

/**
 * 跨进程 [Process]（尤其是 Shizuku 远程派生进程）安全销毁与 IPC 资源彻底回收扩展函数。
 * 显式关闭标准输入、错误流与输出流管道，调用 [Process.destroy]，
 * 并联动 [ShizukuProcessCleaner.cleanProcess] 穿透反射移除 SDK 内部全局静态 CACHE 集合对该进程的强引用，
 * 置空远程 Binder 代理成员引用，彻底打破 Native GlobalRef 与 Java 对象之间的死锁循环引用链，
 * 根治远程进程代理滞留引发的 Proxy Binders 和 Death Recipients 泄漏。
 */
fun Process.safeDestroy() {
    try {
        inputStream?.close()
    } catch (_: Throwable) {}
    try {
        errorStream?.close()
    } catch (_: Throwable) {}
    try {
        outputStream?.close()
    } catch (_: Throwable) {}
    try {
        destroy()
    } catch (_: Throwable) {}
    try {
        ShizukuProcessCleaner.cleanProcess(this)
    } catch (_: Throwable) {}
}

