package com.battery.analysis.util

import java.lang.Process

/**
 * 跨进程 [Process]（尤其是 Shizuku 远程派生进程）安全销毁与 IPC 资源彻底回收扩展函数。
 * 显式关闭标准输入、错误流与输出流管道，并调用 [Process.destroy]，
 * 彻底注销跨进程 Binder 代理、管道文件描述符并解绑死亡监听器（DeathRecipient），
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
}
