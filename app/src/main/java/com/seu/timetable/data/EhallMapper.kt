package com.seu.timetable.data

import com.seu.timetable.domain.Course
import com.seu.timetable.domain.CourseSession
import com.seu.timetable.domain.TermContext
import com.seu.timetable.domain.Timetable
import com.seu.timetable.domain.UnplacedCourse
import com.seu.timetable.domain.compressWeeks
import com.seu.timetable.domain.decodeWeekBitmap
import java.time.LocalDate

/**
 * ehall 原始行 → 领域模型（两层）。
 *
 * 这一层集中承载解析过程中已知的边界情况与历史问题，相关逻辑已用**真实 HAR 数据**验证过：
 * 7 行 → **6 门课 + 7 个时间块**，位图与 `ZCMC` 文字交叉校验 0 处不符。
 */
object EhallMapper {

    // ------------------------------------------------------------------
    // 要点 1（最关键）：一行 = 一个时间块，切勿以 YPSJDD 生成块
    //
    // 实测同一门「通信电子线路实验」返回两行，YPSJDD 字符串**完全相同**：
    //   行1: KSJC=2  JSJC=5   YPSJDD="7-10周 星期日 2-5节 ,7-10周 星期日 7-10节 "
    //   行2: KSJC=7  JSJC=10  YPSJDD="7-10周 星期日 2-5节 ,7-10周 星期日 7-10节 "
    // 后端已将多段时间拆分为多行，但未同步切分 YPSJDD。
    // 若依 YPSJDD 解析：7 块 → 9 段，多出 2 块幻影课（+28%）。
    // 结论：权威字段为 SKXQ + KSJC + JSJC + SKZC，YPSJDD 仅用于展示原始文本。
    // ------------------------------------------------------------------

    fun buildTimetable(
        rows: List<XskcbRow>,
        term: TermContext,
        unplaced: List<UnplacedCourse> = emptyList(),
        currentWeek: Int = 1,
    ): Timetable {
        val sessions = ArrayList<CourseSession>(rows.size)
        val accumulator = LinkedHashMap<String, CourseAccumulator>()

        for (row in rows) {
            val name = row.courseName?.takeIf { it.isNotBlank() } ?: continue
            val day = row.dayOfWeek.asIntOrNull() ?: continue
            val from = row.startPeriod.asIntOrNull() ?: continue
            val to = row.endPeriod.asIntOrNull() ?: from
            if (day !in 1..7 || from <= 0) continue

            val start = minOf(from, to)
            val end = maxOf(from, to)
            val weeks = decodeWeekBitmap(row.weekBitmap)

            // 分组键 = JXBID（教学班号）。实测同一门课的多行 JXBID 相同，
            // 不同课互不相同。缺失时依次退回 课程号-课序号、课程名，
            // 保证同一门课仍会聚合，而不是每条都变成独立课程。
            val courseId = row.classId?.takeIf { it.isNotBlank() }
                ?: listOf(row.courseCode.orEmpty(), row.classNo.orEmpty())
                    .filter { it.isNotBlank() }
                    .joinToString("-")
                    .ifBlank { name }

            accumulator.getOrPut(courseId) { CourseAccumulator(courseId, name) }.absorb(row)

            sessions += CourseSession(
                // 附带节次与周次：同一门课的两个时段（KBID 亦不同）须区分，
                //   否则将其作为 Compose 的 key 会发生冲突。
                id = listOfNotNull(
                    row.sessionId, day, "$start-$end", compressWeeks(weeks)
                ).joinToString("|"),
                courseId = courseId,
                dayOfWeek = day,
                startPeriod = start,
                endPeriod = end,
                weeks = weeks,
                room = row.roomName.orEmpty(),
            )
            // 刻意不做去重：「同一时段但周次不同、教室不同」为真实存在场景
            //   （如中途换教室），去重会误删此类合法数据。
        }

        return Timetable(
            term = term,
            courses = accumulator.values.map { it.toCourse() },
            sessions = sessions,
            unplaced = unplaced,
            currentWeek = currentWeek,
        )
    }

    /**
     * 学期上下文。
     *
     * 兜底值 4/4/4 与 `kbck.js` 中的默认值一致（源码：`var swjc = 4, xwjc = 4, wsjc = 4`），
     * 仅用于服务端未返回分组时的回退。
     *
     * `ZJXZC`（最后教学周）与 `ZZC`（总周数）**不相等**（实测 16 vs 18），
     *   二者均须保留：周次切换器主要依赖 `ZJXZC`。
     */
    fun toTermContext(row: CxjcsRow, termCode: String, termName: String = ""): TermContext {
        val start = parseDate(row.termStartDate)
            ?: throw TimetableException("学期起始日解析失败：${row.termStartDate}")

        val total = row.totalWeeks.asIntOrNull() ?: 20

        return TermContext(
            termCode = termCode,
            termName = termName,
            firstMonday = start,
            totalWeeks = total,
            lastTeachingWeek = row.lastTeachingWeek.asIntOrNull() ?: total,
            morningPeriods = row.morningPeriods.asIntOrNull() ?: 4,
            afternoonPeriods = row.afternoonPeriods.asIntOrNull() ?: 4,
            eveningPeriods = row.eveningPeriods.asIntOrNull() ?: 4,
        )
    }

    /**
     * 未排课课程（形势与政策、社会实践…）。
     * 主课表接口不含学分字段，**仅此处可获取 XF / XS**。
     *
     * 要点 2：本接口的 `SKZC` 为**文本**（"7-14周"），非位图，直接作为文本处理。
     */
    fun toUnplaced(rows: List<XswpkcRow>): List<UnplacedCourse> = rows.mapNotNull { r ->
        val name = r.courseName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        UnplacedCourse(
            name = name,
            teacher = r.teacher.orEmpty(),
            credit = r.credit.asDoubleOrNull() ?: 0.0,
            hours = r.hours.asIntOrNull() ?: 0,
            weeksText = r.weeksText.orEmpty(),
            courseCode = r.courseCode.orEmpty(),
        )
    }

    /** 兼容 `"2026-09-21 00:00:00"` 与 `"2026-09-21"` 两种格式 */
    internal fun parseDate(raw: String?): LocalDate? {
        val s = raw?.trim().orEmpty()
        if (s.length < 10) return null
        return try {
            LocalDate.parse(s.take(10))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 将同一门课的多行收拢为一个 [Course]。
     *
     * 课程级字段取"首个非空值"：同一门课各行这些字段重复，取值等价，
     *   取首个最为简洁稳定。
     */
    private class CourseAccumulator(val id: String, val name: String) {
        private var teacher = ""
        private var courseCode = ""
        private var classNo = ""

        fun absorb(row: XskcbRow) {
            if (teacher.isBlank()) teacher = row.teacher.orEmpty()
            if (courseCode.isBlank()) courseCode = row.courseCode.orEmpty()
            if (classNo.isBlank()) classNo = row.classNo.orEmpty()
        }

        fun toCourse(): Course = Course(
            id = id,
            name = name,
            teacher = teacher,
            courseCode = courseCode,
            classNo = classNo,
            // 课表接口不给学分，留 null；未排课接口的学分挂 UnplacedCourse
            credit = null,
        )
    }
}
