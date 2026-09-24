package com.seu.timetable.update

import androidx.annotation.VisibleForTesting

/**
 * 版本号比较。
 *
 * 只比较 [versionCode]（整数），不比 versionName。
 * 理由：versionName 是给人看的展示串（"0.1.0"、"1.0-beta2"），格式不受约束；
 * 而 versionCode 是 Android 包管理器判定「谁更新」的唯一依据——安装器会拒绝
 * versionCode 更低或相等的包。若用 versionName 决定要不要提示更新，就可能出现
 * 「App 说能更新，安装器却拒装」的矛盾，所以判断标准必须与安装器一致。
 *
 * 纯函数、无副作用，便于单元测试钉住边界。
 */
internal object AppVersion {

    /**
     * 是否建议更新。
     *
     * 只有远端 versionCode **严格大于**本机时才为真：[equal] 或更旧都属于「无需更新」。
     * 特别注意「远端更旧」的情形（用户手动装了内测包、或发版回滚）——
     * 此时绝不能提示更新，否则用户点了会因降级安装被系统拒绝，看起来像 Bug。
     */
    fun shouldUpdate(localCode: Int, remoteCode: Int): Boolean = remoteCode > localCode

    /**
     * 把任意串解析成 versionCode，解析不出返回 null。
     *
     * 用于容忍发布方在清单里填了 "v1.2"、"1.2.3" 这类非整数写法：
     * 取第一段连续数字。比直接崩溃或静默当 0（会导致永远认为无更新）更可控。
     */
    @VisibleForTesting
    fun parseCode(raw: String?): Int? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        // 先整体尝试，最省事也最准
        s.toIntOrNull()?.let { return it }
        // 退一步：取开头可选 'v'/'V' 之后的第一段数字
        val digits = s.dropWhile { it == 'v' || it == 'V' }
            .takeWhile { it.isDigit() }
        return digits.toIntOrNull()
    }

    /** 供界面展示的版本串；空则回落成占位符，避免界面上出现空白。 */
    fun displayName(name: String?, code: Int): String {
        val n = name?.trim().orEmpty()
        return if (n.isEmpty()) "版本 $code" else n
    }
}
