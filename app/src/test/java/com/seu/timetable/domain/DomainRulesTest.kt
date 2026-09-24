package com.seu.timetable.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * 领域规则的护栏测试。
 *
 * 这里最有价值的一条是 [作息表自查无问题] —— 作息表是人工抄的 13 行时间，
 * 极易出现"结束早于开始"这类笔误（真发生过）。
 */
class DomainRulesTest {

    private fun term(
        totalWeeks: Int = 18,
        lastTeachingWeek: Int = 16,
    ) = TermContext(
        termCode = "2026-2027-2",
        termName = "2026-2027学年秋季学期",
        firstMonday = LocalDate.of(2026, 9, 21),
        totalWeeks = totalWeeks,
        lastTeachingWeek = lastTeachingWeek,
        morningPeriods = 5,
        afternoonPeriods = 5,
        eveningPeriods = 3,
    )

    private fun session(
        id: String = "s1",
        courseId: String = "c1",
        day: Int = 1,
        from: Int = 1,
        to: Int = 2,
        weeks: Set<Int> = (1..16).toSet(),
        room: String = "",
    ) = CourseSession(id, courseId, day, from, to, weeks, room)

    // ---------------- 作息表 ----------------

    @Test
    fun `作息表自查无问题`() {
        val problems = PeriodSchedule(PeriodTimes.SEU).problems()
        assertTrue("作息表有笔误：$problems", problems.isEmpty())
    }

    @Test
    fun `作息表 13 节且分组与接口给的一致`() {
        val s = PeriodTimes.SEU
        assertEquals(13, s.size)
        assertEquals(13, PeriodSchedule(s).maxPeriod)

        // 上午 1-5：08:00 起，12:15 结束
        assertEquals(LocalTime.of(8, 0), s[0].begin)
        assertEquals(LocalTime.of(12, 15), s[4].end)
        // 下午 6-10：14:00 起
        assertEquals(LocalTime.of(14, 0), s[5].begin)
        assertEquals(LocalTime.of(18, 15), s[9].end)
        // 晚上 11-13：19:00 起
        assertEquals(LocalTime.of(19, 0), s[10].begin)
        assertEquals(LocalTime.of(21, 25), s[12].end)
    }

    @Test
    fun `第 9 节是 16-40 到 17-25 而不是抄错的 15-25`() {
        // 回归测试：用户提供原始数据时把第 9 节写成 "16:40 – 15:25"（结束早于开始）。
        // 按 45 分钟节长与第 10 节 17:30 开始反推，正确值应为 17:25。
        val p9 = PeriodTimes.SEU.single { it.index == 9 }
        assertEquals(LocalTime.of(16, 40), p9.begin)
        assertEquals(LocalTime.of(17, 25), p9.end)
        assertEquals(45L, p9.durationMinutes())
    }

    @Test
    fun `每节都是 45 分钟`() {
        PeriodTimes.SEU.forEach {
            assertEquals("第 ${it.index} 节不是 45 分钟", 45L, it.durationMinutes())
        }
    }

    @Test
    fun `节次 index 连续从 1 到 13`() {
        assertEquals((1..13).toList(), PeriodTimes.SEU.map { it.index })
    }

    @Test
    fun `非法作息行构造即失败 而 ofOrNull 返回 null`() {
        // 硬编码常量该 fail fast
        try {
            PeriodTime(9, LocalTime.of(16, 40), LocalTime.of(15, 25))
            assertTrue("结束早于开始竟然构造成功了", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("第 9 节"))
        }
        // 用户输入不该让 App 崩
        assertNull(PeriodSchedule.ofOrNull(9, "16:40", "15:25"))
        assertNull(PeriodSchedule.ofOrNull(1, "八点", "08:45"))
        assertNotNull(PeriodSchedule.ofOrNull(1, "08:00", "08:45"))
    }

    // ---------------- 今日页需要的三个判断 ----------------

    @Test
    fun `课程状态 未开始 进行中 已结束`() {
        val sch = PeriodSchedule()
        val s = session()          // 第 1-2 节 = 08:00 – 09:35

        assertEquals(SessionStatus.UPCOMING, sch.statusOf(s, LocalTime.of(7, 30)))
        assertEquals(SessionStatus.ONGOING, sch.statusOf(s, LocalTime.of(8, 20)))
        assertEquals(SessionStatus.ONGOING, sch.statusOf(s, LocalTime.of(9, 0)))
        assertEquals(SessionStatus.FINISHED, sch.statusOf(s, LocalTime.of(9, 36)))
    }

    @Test
    fun `距开始还有几分钟 与 课内进度`() {
        val sch = PeriodSchedule()
        val s = session()

        assertEquals(30L, sch.minutesUntilStart(s, LocalTime.of(7, 30))!!)
        assertNull("已开始就没有'还有几分钟'", sch.minutesUntilStart(s, LocalTime.of(8, 20)))

        assertNull("未开始没有进度", sch.progressOf(s, LocalTime.of(7, 30)))
        assertNull("已结束没有进度", sch.progressOf(s, LocalTime.of(9, 36)))
        val p = sch.progressOf(s, LocalTime.of(8, 45))!!
        assertTrue("进度应在 0..1 之间，实际 $p", p in 0f..1f)
        assertTrue("约 45 分钟 / 95 分钟 ≈ 0.47", p > 0.4f && p < 0.55f)
    }

    @Test
    fun `下一节课 = 今天里开始时间晚于现在的最早一节课`() {
        val sch = PeriodSchedule()
        val morning = session(id = "a", from = 1, to = 2)    // 08:00 起
        val noon = session(id = "b", from = 6, to = 7)       // 14:00 起
        val evening = session(id = "c", from = 11, to = 13)  // 19:00 起
        val today = listOf(evening, morning, noon)           // 故意乱序，验证不是取第一个

        assertEquals("a", sch.nextSessionOf(today, LocalTime.of(7, 0))!!.id)
        assertEquals("b", sch.nextSessionOf(today, LocalTime.of(12, 30))!!.id)
        assertEquals("c", sch.nextSessionOf(today, LocalTime.of(18, 0))!!.id)
        assertEquals("正在上的那节不算'下一节'，要跳到 c", "c", sch.nextSessionOf(today, LocalTime.of(14, 30))!!.id)
        assertNull("今天没课了就返回 null", sch.nextSessionOf(today, LocalTime.of(21, 30)))
    }

    // ---------------- 配色 ----------------

    private fun courses(vararg names: String): List<Course> =
        names.mapIndexed { i, n -> Course("id$i", n) }

    private val demoCourseNames = listOf(
        "通信电子线路实验", "体育V", "通信电子线路", "领导力素养",
        "信息与随机性(研讨)", "计算机组织结构与接口技术实验II",
        "数据结构与算法", "大学物理（下）", "计算机网络", "概率论与数理统计",
    )

    @Test
    fun `10 门课零撞色`() {
        val assigner = CourseColorAssigner(courses(*demoCourseNames.toTypedArray()))
        assertFalse("课程数 ≤ 16 时不该撞色", assigner.hasCollision())
        assertEquals(10, assigner.slotCount())
    }

    @Test
    fun `配色结果与传入顺序无关`() {
        val a = CourseColorAssigner(courses(*demoCourseNames.toTypedArray()))
        val shuffled = demoCourseNames.reversed()
        val b = CourseColorAssigner(shuffled.mapIndexed { i, n -> Course("id${demoCourseNames.size - 1 - i}", n) })

        for (i in demoCourseNames.indices) {
            val id = "id$i"
            assertEquals("课程 $id 在不同顺序下颜色变了", a[id], b[id])
        }
    }

    @Test
    fun `超过 16 门课时不会崩 只是开始复用槽位`() {
        val many = courses(*(1..25).map { "课程$it" }.toTypedArray())
        val assigner = CourseColorAssigner(many)
        many.forEach { assertTrue("槽位应在 0..15", assigner[it.id] in 0..15) }
    }

    @Test
    fun `用户指定颜色优先 且不会被自动分配抢走`() {
        val pinned = listOf(
            Course("x", "被钉死的课", colorOverride = 7),
            Course("y", "另一门课"),
        )
        val assigner = CourseColorAssigner(pinned)
        assertEquals(7, assigner["x"])
        assertTrue(
            "自动分配不该占用 7",
            assigner["y"] != 7,
        )
    }

    @Test
    fun `改名后颜色会变 但同一学期内稳定`() {
        val list = courses("课程甲", "课程乙")
        val first = CourseColorAssigner(list)
        val again = CourseColorAssigner(list)
        assertEquals(first["id0"], again["id0"])
        assertEquals(first["id1"], again["id1"])
    }

    // ---------------- 周次切换器 ----------------

    @Test
    fun `切换器周数以最后教学周为准`() {
        val t = Timetable(
            term = term(totalWeeks = 18, lastTeachingWeek = 16),
            courses = listOf(Course("c1", "课")),
            sessions = listOf(session(weeks = (1..16).toSet())),
            currentWeek = 1,
        )
        assertEquals("ZJXZC = 16，比 ZZC = 18 更紧凑", 16, t.displayedWeeks)
    }

    @Test
    fun `如果课排到了教学周之后 切换器必须跟上去 否则那些课永远切不到`() {
        val t = Timetable(
            term = term(totalWeeks = 18, lastTeachingWeek = 16),
            courses = listOf(Course("c1", "期末冲刺课")),
            sessions = listOf(session(weeks = setOf(17, 18))),
            currentWeek = 1,
        )
        assertEquals("必须被顶到 18，否则这门课不可达且不报错", 18, t.displayedWeeks)
    }

    @Test
    fun `当前周也会被兜住`() {
        val t = Timetable(
            term = term(totalWeeks = 18, lastTeachingWeek = 16),
            courses = listOf(Course("c1", "课")),
            sessions = listOf(session(weeks = (1..16).toSet())),
            currentWeek = 17,
        )
        assertEquals(17, t.displayedWeeks)
    }

    // ---------------- Timetable 取课 ----------------

    @Test
    fun `按周与星期取课 并按节次排序`() {
        val t = Timetable(
            term = term(),
            courses = listOf(Course("c1", "甲"), Course("c2", "乙")),
            sessions = listOf(
                session("s1", "c1", day = 1, from = 6, to = 7),
                session("s2", "c2", day = 1, from = 1, to = 2),
                session("s3", "c1", day = 2, from = 1, to = 2),
            ),
            currentWeek = 1,
        )
        assertEquals(listOf("s2", "s1"), t.sessionsOn(1, 1).map { it.id })
        assertEquals(listOf("s3"), t.sessionsOn(1, 2).map { it.id })
        assertEquals(3, t.sessionsIn(1).size)
    }

    @Test
    fun `单双周与跳周按位图过滤 不用取模`() {
        val oddOnly = session(id = "odd", weeks = setOf(1, 3, 5, 7, 9, 11, 13, 15))
        val jumped = session(id = "jump", weeks = setOf(7, 8, 9, 10))
        val t = Timetable(
            term = term(),
            courses = listOf(Course("c1", "课")),
            sessions = listOf(oddOnly, jumped),
            currentWeek = 1,
        )
        assertEquals(listOf("odd"), t.sessionsIn(3).map { it.id })
        assertEquals(listOf("odd"), t.sessionsIn(15).map { it.id })
        assertEquals(emptyList<String>(), t.sessionsIn(2).map { it.id })
        assertEquals(listOf("jump"), t.sessionsIn(8).map { it.id })
        assertEquals(emptyList<String>(), t.sessionsIn(6).map { it.id })
    }

    @Test
    fun `按日期取课 用学期起始日推算`() {
        val t = Timetable(
            term = term(),
            courses = listOf(Course("c1", "周一课"), Course("c2", "周三课")),
            sessions = listOf(
                session("s1", "c1", day = 1),
                session("s2", "c2", day = 3),
            ),
            currentWeek = 1,
        )
        // 2026-09-23 是第 1 周的周三
        assertEquals(DayOfWeek.WEDNESDAY, LocalDate.of(2026, 9, 23).dayOfWeek)
        assertEquals(listOf("s2"), t.sessionsOnDate(LocalDate.of(2026, 9, 23)).map { it.id })
        assertEquals(listOf("s1"), t.sessionsOnDate(LocalDate.of(2026, 9, 21)).map { it.id })
    }

    @Test
    fun `课程周次取各时段并集`() {
        val t = Timetable(
            term = term(),
            courses = listOf(Course("c1", "课")),
            sessions = listOf(
                session("s1", "c1", weeks = (1..8).toSet()),
                session("s2", "c1", weeks = (9..16).toSet()),
            ),
            currentWeek = 1,
        )
        assertEquals((1..16).toSet(), t.weeksOf("c1"))
    }

    @Test
    fun `课表能按 courseId 找回课程 保证同课同色`() {
        val t = Timetable(
            term = term(),
            courses = listOf(Course("c1", "甲"), Course("c2", "乙")),
            sessions = listOf(session("s1", "c1"), session("s2", "c1")),
            currentWeek = 1,
        )
        val ids = t.sessions.map { t.courseOf(it)!!.id }.toSet()
        assertEquals("两个时段必须指向同一门课", setOf("c1"), ids)
    }
}
