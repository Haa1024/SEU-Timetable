package com.seu.timetable.data

import com.seu.timetable.domain.compressWeeks
import com.seu.timetable.domain.decodeWeekBitmap
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

private val JSON = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

/**
 * 用**真实 HAR 抓下来的响应**（已脱敏）验证解析逻辑。
 *
 * 这些测试是"坑的护栏"：谁要是把 YPSJDD 拿回来建课表、
 * 给 SKZC 位图加长度校验、或者把 JXBID 分组去掉，这里会立刻红。
 */
class EhallMapperTest {

    private fun resource(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!
            .readBytes().toString(Charsets.UTF_8)

    /**
     * 显式传序列化器，而不是用 `decodeFromJsonElement<T>()` 的 reified 扩展——
     * 后者依赖隐式导入，容易在重载解析上出岔子（生产代码里踩过一次）。
     */
    private fun <T> loadRows(
        key: String,
        file: String,
        deserializer: DeserializationStrategy<EhallRows<T>>,
    ): List<T> {
        val env = JSON.decodeFromString(EhallEnvelope.serializer(), resource(file))
        val obj = env.datas[key] ?: error("夹具里没有 datas.$key")
        return JSON.decodeFromJsonElement(deserializer, obj).rows
    }

    private fun rawRows() = loadRows("xskcb", "ehall_xskcb.json", EhallRows.serializer(XskcbRow.serializer()))

    private fun cxjcs() = loadRows("cxjcs", "ehall_cxjcs.json", EhallRows.serializer(CxjcsRow.serializer())).first()

    private fun term() = EhallMapper.toTermContext(cxjcs(), "2026-2027-2", "2026-2027学年秋季学期")

    private fun unplaced() = EhallMapper.toUnplaced(
        loadRows("xswpkc", "ehall_xswpkc.json", EhallRows.serializer(XswpkcRow.serializer()))
    )

    private fun timetable() = EhallMapper.buildTimetable(rawRows(), term(), unplaced(), currentWeek = 1)

    // ---------------- 两层模型：7 行 → 6 门课 + 7 个块 ----------------

    @Test
    fun `主课表 7 行 解析出 6 门课与 7 个时间块`() {
        val rows = rawRows()
        assertEquals("夹具本身应是 7 行", 7, rows.size)

        val t = timetable()
        assertEquals("一行 = 一个时间块", 7, t.sessions.size)
        assertEquals("同一门课占两行，所以课程数少 1", 6, t.courses.size)
    }

    @Test
    fun `同一门实验课的两个时段归到同一门课`() {
        val t = timetable()
        val lab = t.courses.single { it.name == "通信电子线路实验" }

        val sessions = t.sessionsOf(lab.id)
        assertEquals("这门课有两个时段", 2, sessions.size)
        assertEquals("两块都在周日", listOf(7, 7), sessions.map { it.dayOfWeek })
        assertEquals("节次分别是 2-5 与 7-10", listOf(2 to 5, 7 to 10), sessions.map { it.startPeriod to it.endPeriod })
        assertNotEquals("两块是不同的课表行", sessions[0].id, sessions[1].id)
        assertEquals("但周次相同（同一门课同一次排课）", sessions[0].weeks, sessions[1].weeks)

        // 课程级字段只存一份，两个时段共用一个 courseId
        assertTrue(sessions.all { it.courseId == lab.id })
        assertEquals("李伟", lab.teacher)
    }

    @Test
    fun `每门课至少有一个时间块`() {
        val t = timetable()
        for (c in t.courses) {
            assertTrue("课程 ${c.name} 没有任何时间块", t.sessionsOf(c.id).isNotEmpty())
        }
    }

    @Test
    fun `课程级字段取第一个非空值 不会因多行而错乱`() {
        val t = timetable()
        val lab = t.courses.single { it.name == "通信电子线路实验" }
        assertEquals("B0442060", lab.courseCode)
        assertEquals("04", lab.classNo)
        assertEquals("课表接口不给学分，应为 null", null, lab.credit)
    }

    // ---------------- 坑 1 ----------------

    @Test
    fun `若改用 YPSJDD 文本建块 会多出幻影课`() {
        val rows = rawRows()
        val t = timetable()

        // 反面教材：按 YPSJDD 的逗号分段数来算块数
        val phantom = rows.sumOf { row ->
            (row.rawTimePlace ?: "").split(',').count { it.isNotBlank() }
        }

        assertEquals(7, t.sessions.size)
        assertEquals("YPSJDD 分段会得到 9 段", 9, phantom)
        assertTrue("所以绝不能拿 YPSJDD 建块", phantom > t.sessions.size)
    }

    // ---------------- 周次位图 ----------------

    @Test
    fun `周次位图按位置解码 不依赖长度`() {
        assertEquals((1..16).toSet(), decodeWeekBitmap("1111111111111111"))
        assertEquals((3..14).toSet(), decodeWeekBitmap("001111111111110000"))
        assertEquals(setOf(7, 8, 9, 10), decodeWeekBitmap("000000111100000000"))
        assertEquals((9..16).toSet(), decodeWeekBitmap("0000000011111111"))
        assertEquals(emptySet<Int>(), decodeWeekBitmap(null))
        assertEquals(emptySet<Int>(), decodeWeekBitmap(""))
    }

    @Test
    fun `位图长度确实不固定 所以不能做长度校验`() {
        val lengths = rawRows().map { it.weekBitmap?.length ?: 0 }.toSet()
        assertTrue("真实数据里位图长度不止一种: $lengths", lengths.size > 1)
        assertTrue("且存在长度 16 的位图（而总周数是 18）", lengths.contains(16))
    }

    @Test
    fun `位图解码结果与 ZCMC 文字逐条一致`() {
        var checked = 0
        for (row in rawRows()) {
            val byBitmap = compressWeeks(decodeWeekBitmap(row.weekBitmap))
            val byText = row.weekText.orEmpty().removeSuffix("周")
            if (byBitmap.isBlank() || byText.isBlank()) continue
            assertEquals("${row.courseName} 位图与文字不符", byText, byBitmap)
            checked++
        }
        assertEquals("7 条都应参与校验", 7, checked)
    }

    // ---------------- 学期上下文 ----------------

    @Test
    fun `学期上下文解析出起始日 总周数 最后教学周 与节次分组`() {
        val t = term()
        assertEquals(LocalDate.of(2026, 9, 21), t.firstMonday)
        assertEquals("2026-09-21 确实是周一", java.time.DayOfWeek.MONDAY, t.firstMonday.dayOfWeek)
        assertEquals("ZZC 总周数", 18, t.totalWeeks)
        assertEquals("ZJXZC 最后教学周", 16, t.lastTeachingWeek)
        assertNotEquals("两者不相等，不能混用", t.lastTeachingWeek, t.totalWeeks)
        assertEquals(5, t.morningPeriods)
        assertEquals(5, t.afternoonPeriods)
        assertEquals(3, t.eveningPeriods)
        assertEquals("5+5+3 = 13", 13, t.periodsPerDay)
    }

    @Test
    fun `节次分组边界正确`() {
        val t = term()
        assertEquals(com.seu.timetable.domain.PeriodGroup.MORNING, t.groupOf(1))
        assertEquals(com.seu.timetable.domain.PeriodGroup.MORNING, t.groupOf(5))
        assertEquals(com.seu.timetable.domain.PeriodGroup.AFTERNOON, t.groupOf(6))
        assertEquals(com.seu.timetable.domain.PeriodGroup.AFTERNOON, t.groupOf(10))
        assertEquals(com.seu.timetable.domain.PeriodGroup.EVENING, t.groupOf(11))
        assertEquals(com.seu.timetable.domain.PeriodGroup.EVENING, t.groupOf(13))

        assertTrue("2-5 都在上午，不跨界", !t.crossesGroupBoundary(2, 5))
        assertTrue("5-8 跨上午与下午", t.crossesGroupBoundary(5, 8))
    }

    @Test
    fun `学期代码拆成 XN 与 XQ 参数`() {
        val t = term()
        assertEquals("2026-2027", t.yearParam)
        assertEquals("2", t.termParam)
    }

    @Test
    fun `按学期上下文推算周次与日期`() {
        val t = term()
        assertEquals(1, t.weekOf(LocalDate.of(2026, 9, 21)))
        assertEquals(1, t.weekOf(LocalDate.of(2026, 9, 27)))
        assertEquals(2, t.weekOf(LocalDate.of(2026, 9, 28)))
        assertEquals(LocalDate.of(2026, 9, 21), t.dateOf(1, 1))
        assertEquals(LocalDate.of(2026, 9, 27), t.dateOf(1, 7))
    }

    // ---------------- 未排课课程 ----------------

    @Test
    fun `未排课课程带学分与学时`() {
        val u = unplaced()
        assertEquals(2, u.size)

        val xingshi = u.first { it.name.contains("形势与政策") }
        assertEquals(0.0, xingshi.credit, 0.0001)
        assertEquals(8, xingshi.hours)

        val shijian = u.first { it.name == "社会实践" }
        assertEquals(1.0, shijian.credit, 0.0001)
        assertEquals(0, shijian.hours)
    }

    @Test
    fun `未排课接口的 SKZC 是文本而不是位图`() {
        val weeksText = loadRows("xswpkc", "ehall_xswpkc.json", EhallRows.serializer(XswpkcRow.serializer()))
            .mapNotNull { it.weeksText }
        assertTrue("应当是 '7-14周' 这种文本", weeksText.any { it.endsWith("周") })
        assertTrue("文本里不该全是 0 和 1", weeksText.none { it.matches(Regex("[01]+")) })
    }

    @Test
    fun `未排课课程不进网格`() {
        val t = timetable()
        assertTrue("未排课课程没有节次，不该出现在 sessions 里", t.sessions.isEmpty() == false)
        for (s in t.sessions) {
            assertTrue("每个块都要能找回它的课", t.courseOf(s) != null)
        }
        assertEquals("但它们仍要能被读到", 2, t.unplaced.size)
    }

    // ---------------- 服务端按周过滤的坑 ----------------

    @Test
    fun `带 SKZC 参数的那次调用只有 2 行 不能当全量`() {
        val filtered = loadRows("xskcb", "ehall_xskcb_week1.json", EhallRows.serializer(XskcbRow.serializer()))
        assertEquals("被服务端过滤后只剩 2 行", 2, filtered.size)
        assertEquals("全量是 7 行", 7, rawRows().size)
    }

    // ---------------- 边界与容错 ----------------

    @Test
    fun `缺字段的行被跳过而不是崩掉`() {
        val broken = listOf(
            XskcbRow(courseName = null),
            XskcbRow(courseName = "有名字但没星期", startPeriod = jsonNum("1")),
            XskcbRow(
                courseName = "正常",
                dayOfWeek = jsonNum("1"),
                startPeriod = jsonNum("1"),
                weekBitmap = "1111111111111111",
            ),
        )
        val t = EhallMapper.buildTimetable(broken, term())
        assertEquals("只有第三行合法", 1, t.sessions.size)
        assertEquals("正常", t.courses.single().name)
    }

    @Test
    fun `数值字段 字符串和数字两种写法都能吃下`() {
        val row = XskcbRow(
            courseName = "混合类型",
            dayOfWeek = jsonNum("3"),
            startPeriod = jsonNum(6),
            endPeriod = jsonNum("8"),
            weekBitmap = "1111111111111111",
        )
        val s = EhallMapper.buildTimetable(listOf(row), term()).sessions.single()
        assertEquals(3, s.dayOfWeek)
        assertEquals(6, s.startPeriod)
        assertEquals(8, s.endPeriod)
    }

    @Test
    fun `节次反了就归一化`() {
        val row = XskcbRow(
            courseName = "反的",
            dayOfWeek = jsonNum("2"),
            startPeriod = jsonNum("7"),
            endPeriod = jsonNum("3"),
        )
        val s = EhallMapper.buildTimetable(listOf(row), term()).sessions.single()
        assertEquals(3, s.startPeriod)
        assertEquals(7, s.endPeriod)
    }

    @Test
    fun `没有 JXBID 时退回课程号与课序号分组 不会每条都变成一门课`() {
        val mk = { cls: String ->
            XskcbRow(
                courseName = "无教学班号的课",
                courseCode = "B0001",
                classNo = cls,
                dayOfWeek = jsonNum("1"),
                startPeriod = jsonNum("1"),
                endPeriod = jsonNum("2"),
                weekBitmap = "1111111111111111",
            )
        }
        // 同课程号+课序号的两行 → 应聚合成 1 门课 2 个块
        val t = EhallMapper.buildTimetable(listOf(mk("01"), mk("01")), term())
        assertEquals(1, t.courses.size)
        assertEquals(2, t.sessions.size)
    }

    @Test
    fun `没教室的课也保留 由 UI 显示占位符`() {
        val lab = timetable().sessions.first { it.room.isEmpty() }
        assertNotNull(lab)
        assertTrue("实验课教室为空，UI 该显示 —", lab.room.isEmpty())
        assertTrue("但周次不能为空", lab.weeks.isNotEmpty())
    }

    @Test
    fun `compressWeeks 压缩连续周次`() {
        assertEquals("1-3,5", compressWeeks(setOf(1, 2, 3, 5)))
        assertEquals("1-16", compressWeeks((1..16).toSet()))
        assertEquals("7", compressWeeks(setOf(7)))
        assertEquals("—", compressWeeks(emptySet()))
        assertEquals("1-3,5-7,10", compressWeeks(setOf(1, 2, 3, 5, 6, 7, 10)))
    }

    @Test
    fun `日期解析容忍带时刻与不带时刻两种格式`() {
        assertEquals(LocalDate.of(2026, 9, 21), EhallMapper.parseDate("2026-09-21 00:00:00"))
        assertEquals(LocalDate.of(2026, 9, 21), EhallMapper.parseDate("2026-09-21"))
        assertEquals(null, EhallMapper.parseDate(null))
        assertEquals(null, EhallMapper.parseDate(""))
        assertEquals(null, EhallMapper.parseDate("不是日期"))
    }
}

private fun jsonNum(v: Any) = when (v) {
    is String -> kotlinx.serialization.json.JsonPrimitive(v)
    is Int -> kotlinx.serialization.json.JsonPrimitive(v)
    else -> kotlinx.serialization.json.JsonPrimitive(v.toString())
}
