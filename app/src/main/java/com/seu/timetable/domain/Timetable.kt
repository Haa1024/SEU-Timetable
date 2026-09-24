package com.seu.timetable.domain

import java.time.LocalDate

/**
 * 一个学期的完整课表：学期上下文 + 课程 + 时间块 + 未排课课程。
 *
 * 网格只关心 [sessions]（块）；详情页/编辑页关心 [courses]（课）。
 * 两者通过 [CourseSession.courseId] 关联，所以「同一门课必然同色」是结构保证的。
 */
class Timetable(
    val term: TermContext,
    val courses: List<Course>,
    val sessions: List<CourseSession>,
    val unplaced: List<UnplacedCourse> = emptyList(),
    val currentWeek: Int = 1,
) {

    private val courseById: Map<String, Course> = courses.associateBy { it.id }

    fun courseOf(session: CourseSession): Course? = courseById[session.courseId]

    fun course(courseId: String): Course? = courseById[courseId]

    fun sessionsOf(courseId: String): List<CourseSession> =
        sessions.filter { it.courseId == courseId }.sortedWith(SESSION_ORDER)

    /** 某门课所有时段周次的并集（课程详情的周次分布条用） */
    fun weeksOf(courseId: String): Set<Int> =
        sessionsOf(courseId).flatMapTo(LinkedHashSet()) { it.weeks }

    fun sessionsIn(week: Int): List<CourseSession> = sessions.filter { it.isActiveIn(week) }

    fun sessionsOn(week: Int, dayOfWeek: Int): List<CourseSession> =
        sessionsIn(week).filter { it.dayOfWeek == dayOfWeek }.sortedWith(SESSION_ORDER)

    /** 指定日期当天的课。周次走本地推算；要服务端权威值用 EhallClient.weekOf */
    fun sessionsOnDate(date: LocalDate): List<CourseSession> =
        sessionsOn(term.weekOf(date), date.dayOfWeek.value)

    /** 所有时间块里出现过的最大周次 */
    fun lastWeekWithClass(): Int =
        sessions.maxOfOrNull { it.weeks.maxOrNull() ?: 0 } ?: 0

    /**
     * 周次切换器应显示到第几周。
     *
     * 以 `ZJXZC`（最后教学周）为主，但必须覆盖位图中实际出现过的最大周次
     * 以及当前周。理由：若某天课程排到了 17-18 周，而切换器只到 16，
     * 那些课程将永远无法切换到，且不报任何错误——这是最难以排查的静默失败。
     *
     * 取各项的最大值，既尊重"仅到教学周"的紧凑界面，又不会被数据所否定。
     */
    val displayedWeeks: Int
        get() = maxOf(term.lastTeachingWeek, lastWeekWithClass(), currentWeek, 1)

    companion object {
        private val SESSION_ORDER =
            compareBy<CourseSession>({ it.dayOfWeek }, { it.startPeriod }, { it.id })
    }
}
