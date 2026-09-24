package com.seu.timetable.util

import android.util.Log

/**
 * 统一日志出口。
 *
 * 采用此类而非各处直接调用 `Log.i` 的理由：
 *  - 统一固定 tag（`SeuTT`），排查时 `adb logcat -s SeuTT:V` 即可过滤干净，
 *    无需在大量系统日志中翻找；
 *  - 登录流程每一步（跳转位置、cookie 内容、探测结果、HTTP 状态）
 *    均由此输出，出问题时无需用户截图，日志本身即可还原全过程。
 *
 * 用法（真机查看）：
 * ```bash
 * adb logcat -c                       # 先清缓冲
 * # ……操作 App……
 * adb logcat -d -s SeuTT:V            # 再一次性导出
 * ```
 * 若需同时查看网络请求，将 `-s` 替换为 `SeuTT:V okhttp.OkHttpClient:V` 等即可。
 */
object DebugLog {

    /** 固定 tag，过滤日志时以此为准。 */
    const val TAG = "SeuTT"

    fun i(msg: String) = safe { Log.i(TAG, msg) }

    fun w(msg: String) = safe { Log.w(TAG, msg) }

    fun e(msg: String, t: Throwable? = null) {
        safe { if (t == null) Log.e(TAG, msg) else Log.e(TAG, msg, t) }
    }

    /**
     * 日志本身绝不能让调用方崩掉。
     *
     * 两种真实场景：
     *  1. **JVM 单元测试**——`android.util.Log` 在本地单测里是没有实现的空壳，任何调用都抛
     *     `RuntimeException: Method ... not mocked`。更新检查器这类逻辑里有日志，
     *     若不加保护，纯逻辑单测会因为「打了一行日志」而失败，与业务无关；
     *  2. 极端情况下 Log 自身可能异常。日志失败不该影响主流程。
     */
    private fun safe(block: () -> Unit) {
        runCatching { block() }
    }

    /** 将可能过长的 URL 截断，避免刷屏；保留日志所需长度即可。 */
    fun url(prefix: String, url: String?, keep: Int = 140): String {
        val u = url ?: "(null)"
        return "$prefix ${if (u.length > keep) u.take(keep) + "…" else u}"
    }
}
