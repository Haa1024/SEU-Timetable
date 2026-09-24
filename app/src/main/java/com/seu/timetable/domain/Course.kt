package com.seu.timetable.domain

import kotlinx.serialization.Serializable

/**
 * 一门课程（课程级）。
 *
 * ehall 的 `xskcb.do` 一行中混合了两类属性：**一次上课**（`SKXQ` 星期 / `KSJC`·`JSJC` 节次 /
 * `JASMC` 教室，每行不同）与**课程**（`KCM` 课程名 / `SKJS` 教师 / 颜色 / 备注 / 学分，多行重复）。
 * 课程的唯一标识是 `JXBID`（教学班号）：实测「通信电子线路实验」两行 JXBID 相同，
 * 故 7 行 = 7 个时间块，却只有 6 门课。
 *
 * 拆成两层的原因：详情页与编辑页属课级操作，改颜色 / 备注必须只改一处，
 * 否则同一门课会渲染出两种颜色。拆分后「同一门课必然同色」在结构上不可能出错。
 */
@Serializable
data class Course(
    val id: String,                    // JXBID 教学班号
    val name: String,                  // KCM
    val teacher: String = "",          // SKJS
    val courseCode: String = "",       // KCH
    val classNo: String = "",          // KXH
    /** 学分。仅未排课接口 `xswpkc.do` 提供，主课表接口 `xskcb.do` 不返回，其余场景由用户填写。 */
    val credit: Double? = null,
    /** 用户备注 */
    val note: String = "",
    /** 用户指定的配色槽位（0..15）；null = 自动分配。据此区分「用户固定」与「自动计算」。 */
    val colorOverride: Int? = null,
)

/**
 * 一次上课（时间块）。一行接口数据 = 一个 CourseSession。
 *
 * 不得依据 `YPSJDD` 文本生成 session：多段课程被后端拆成多行时该字段未随之切分，
 * 每行重复完整原文，按此解析会使块数翻倍（实测 7 块 → 9 段）。
 * 权威字段为 [dayOfWeek] + [startPeriod] + [endPeriod] + [weeks]。
 */
@Serializable
data class CourseSession(
    val id: String,                    // KBID + 节次，保证同一门课的两个时段不撞
    val courseId: String,              // → Course.id
    val dayOfWeek: Int,                // 1=周一 … 7=周日（7 是周日，不是 0）
    val startPeriod: Int,              // 起始节次，1-based
    val endPeriod: Int,                // 结束节次，闭区间
    val weeks: Set<Int>,               // 上课周次，1-based，来自 SKZC 位图
    val room: String = "",             // JASMC，可能为空（实验课）→ UI 显示 "—"
) {
    val periodSpan: Int get() = (endPeriod - startPeriod + 1).coerceAtLeast(1)

    fun isActiveIn(week: Int): Boolean = week in weeks

    fun periodLabel(): String =
        if (startPeriod == endPeriod) "第 $startPeriod 节" else "第 $startPeriod-$endPeriod 节"

    fun weekLabel(): String = compressWeeks(weeks)
}

/**
 * 未排课的课程（如形势与政策、社会实践等）。
 *
 * 这类课程没有星期与节次、无法绘制进网格，但唯有此处含学分与学时——主课表接口不返回学分。
 * [weeksText] 来自本接口的 `SKZC`，为文本形式（如 `"7-14周"`），与 `xskcb.do` 中同名字段的
 * 0/1 位图含义完全不同，二者不可混用。
 */
@Serializable
data class UnplacedCourse(
    val name: String,
    val teacher: String = "",
    val credit: Double = 0.0,          // XF
    val hours: Int = 0,                // XS
    val weeksText: String = "",        // 文本周次，不是位图
    val courseCode: String = "",
)

/** 将 {1,2,3,5,6,7,10} 压缩为 "1-3,5-7,10"。课块与课程详情均需展示，未压缩则难以排布。 */
fun compressWeeks(weeks: Set<Int>): String {
    if (weeks.isEmpty()) return "—"
    val sorted = weeks.sorted()
    val sb = StringBuilder()
    var start = sorted.first()
    var prev = start

    for (i in 1..sorted.size) {
        val cur = sorted.getOrNull(i)
        if (cur != null && cur == prev + 1) {
            prev = cur
            continue
        }
        if (sb.isNotEmpty()) sb.append(',')
        if (start == prev) sb.append(start) else sb.append(start).append('-').append(prev)
        if (cur != null) {
            start = cur
            prev = cur
        }
    }
    return sb.toString()
}

/**
 * [compressWeeks] 的逆运算：将用户输入的 `"1-3,5-7,10"` 解析为周次集合。
 *
 * 自建课表须由用户手填周次（无教务 SKZC 位图可读），而 [compressWeeks] 恰好生成该格式，
 * 故预填内容可原样重新解析，用户改其中一段也不会令整串失效。
 *
 * 容错（均静默跳过，不抛异常——此处接收的是键盘输入）：中英文逗号均识别；区间连接符识别
 * `-` `~` `—`；空白、非数字、倒序区间（`8-3`）忽略该段。
 *
 * 返回空集合表示未解析出任何一段，调用方须视为输入不合法并报错，
 * 不可当作「该课程无上课周次」，否则用户笔误会使课表静默缺失一节课。
 */
fun parseWeeks(text: String): Set<Int> {
    val out = LinkedHashSet<Int>()
    text.split(',', '，').forEach { part ->
        val seg = part.trim().replace('~', '-').replace('—', '-')
        if (seg.isEmpty()) return@forEach
        val dash = seg.indexOf('-')
        if (dash > 0) {
            val a = seg.substring(0, dash).trim().toIntOrNull()
            val b = seg.substring(dash + 1).trim().toIntOrNull()
            if (a != null && b != null && a in 1..60 && b in a..60) {
                for (w in a..b) out += w
            }
        } else {
            seg.toIntOrNull()?.let { if (it in 1..60) out += it }
        }
    }
    return out
}

/**
 * 解码 `SKZC` 周次位图。
 *
 * 位图长度不固定（实测 16 与 18 均出现过，而 `cxjcs.ZZC` 恒为 18），故不可假设长度等于总周数，
 * 也不可据此做长度校验；只需按位置读取：第 i 个字符（下标自 0 起）= 第 i+1 周。
 */
fun decodeWeekBitmap(bitmap: String?): Set<Int> {
    if (bitmap.isNullOrEmpty()) return emptySet()
    val out = LinkedHashSet<Int>()
    bitmap.forEachIndexed { i, ch ->
        if (ch == '1') out += i + 1
    }
    return out
}
