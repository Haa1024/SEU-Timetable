package com.seu.timetable.domain

/**
 * 学期代码的算术：`2026-2027-2` ↔ 前后第几个学期。
 *
 * 此处存在一个不确定的地方，必须说明：
 * `dqxnxq.do` 仅返回当前学年学期，并无"学期列表"接口。
 * 因此无法从服务端获知共有哪些学期，只能按规律推算候选项。
 *
 * 而"学年内第 1 个学期是春还是秋"这一点，目前仅有一个数据点：
 * 实测当前学期 `2026-2027-2` 的名称为"2026-2027学年秋季学期"，
 * 即 `N=2` 对应秋季。由此推出学年内的时间顺序为 `N=2`（秋）→ `N=1`（春）。
 *
 * 该推论可能错误（例如某些年份的编号规则不同）。故设计的容错方式为：
 * [nearby] 仅用于生成候选代码（界面上只显示代码本身，不标注"春季/秋季"），
 * 真正的判定交由 `cxjcs.do` —— 能查到即说明该代码有效，
 * 且界面会将该学期真实的起始日期展示给用户确认。
 * 即便顺序猜错，用户看到的仍是真实数据，不会在静默中用错学期。
 */
object TermCodes {

    /**
     * 以 [current] 为基准，取前 [before] 个、后 [after] 个学期的代码（含自身）。
     *
     * @return 从早到晚排列。无法解析时返回只含 [current] 的列表。
     */
    fun nearby(current: String, before: Int = 3, after: Int = 1): List<String> {
        val base = toOrdinal(current) ?: return listOf(current)
        return (base - before..base + after).map { fromOrdinal(it) }
    }

    /** ← `2026-2027-2`：单调递增的序号。秋在前、春在后，见类注释。 */
    fun toOrdinal(code: String): Int? {
        val parts = code.split("-")
        if (parts.size < 3) return null
        val yearStart = parts[0].toIntOrNull() ?: return null
        val n = parts[2].toIntOrNull() ?: return null
        // 秋(2) 在学年内排在春(1) 之前 → 秋占偶数位、春占奇数位
        val withinYear = if (n == 2) 0 else 1
        return yearStart * 2 + withinYear
    }

    fun fromOrdinal(ordinal: Int): String {
        val yearStart = ordinal / 2
        val n = if (ordinal % 2 == 0) 2 else 1
        return "$yearStart-${yearStart + 1}-$n"
    }
}
