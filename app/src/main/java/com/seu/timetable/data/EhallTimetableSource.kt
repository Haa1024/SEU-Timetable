package com.seu.timetable.data

import com.seu.timetable.domain.CourseSession
import com.seu.timetable.domain.Timetable
import com.seu.timetable.domain.UnplacedCourse
import com.seu.timetable.domain.compressWeeks
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

/**
 * 基于 ehall 微应用的数据源。
 *
 * 拉取顺序（设计规格第 6 章所述五步）：
 * ```
 * 1) dqxnxq.do         -> 当前学期代码
 * 2) cxjcs.do          -> 学期起始日 / 总周数 / 最后教学周 / 节次分组
 * 3) xskcb.do          -> 课表行（一行 = 一个时间块）
 * 4) xswpkc.do         -> 未排课课程（学分/学时）
 * 5) dqzc.do（可选）    -> 今天第几周（服务端权威值）
 * ```
 *
 * 含一层内存缓存：同一学期重复调用不再请求接口，切换学期自动失效。
 */
class EhallTimetableSource(
    private val client: EhallClient = EhallClient(),
) : TimetableSource {

    private val lock = Mutex()
    private var cached: Timetable? = null

    override suspend fun load(termCode: String?): Timetable = lock.withLock {
        val requested = termCode
            ?: client.currentTerm()?.termCode
            ?: throw TimetableException("拿不到当前学期（dqxnxq 返回空）")

        cached?.let { if (it.term.termCode == requested) return it }

        val term = client.termContext(requested)

        // 未排课课程获取失败不应导致整个课表失败——其仅为补充信息
        val unplaced: List<UnplacedCourse> =
            runCatching { client.unplacedCourses(requested) }.getOrDefault(emptyList())

        val today = LocalDate.now()
        val week = runCatching { client.weekOf(requested, today) }.getOrNull()
            ?: term.weekOf(today)

        // 注意：此处**不携带** SKZC 参数。携带后服务端将按周过滤
        //   （实测携带 SKZC=1 仅返回 2 行，全量为 7 行）。
        val rows = client.fetchTimetableRows(requested)

        EhallMapper.buildTimetable(
            rows = rows,
            term = term,
            unplaced = unplaced,
            currentWeek = week,
        ).also { cached = it }
    }

    /** 强制下一次 [load] 重新请求接口（规格第 8 章「下拉刷新」用） */
    fun invalidate() {
        cached = null
    }

    /** 供 UI 判断是否需要弹登录页 */
    suspend fun hasSession(): Boolean = client.hasSession()

    fun cachedTermCode(): String? = cached?.term?.termCode

    /**
     * 诊断用：将解析结果摘要为一行，接入真实数据后优先核对，
     *   数值异常可立即发现（如课程数多于时间块数，即分组键有误）。
     */
    fun describe(): String {
        val t = cached ?: return "未加载"
        return "学期 ${t.term.termCode}｜课程 ${t.courses.size} 门｜时间块 ${t.sessions.size} 个｜" +
            "未排课 ${t.unplaced.size} 门｜当前第 ${t.currentWeek} 周｜切换器到第 ${t.displayedWeeks} 周"
    }

    /** 单块的可读描述，便于日志核对 */
    fun sessionLabel(session: CourseSession): String {
        val name = cached?.course(session.courseId)?.name ?: session.courseId
        return "$name 周${session.dayOfWeek} 第${session.startPeriod}-${session.endPeriod}节 " +
            "周次[${compressWeeks(session.weeks)}] 教室[${session.room.ifBlank { "—" }}]"
    }
}
