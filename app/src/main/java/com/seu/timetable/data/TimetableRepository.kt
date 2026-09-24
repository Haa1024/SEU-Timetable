package com.seu.timetable.data

import android.content.Context
import com.seu.timetable.domain.BoardContent
import com.seu.timetable.domain.BoardIndex
import com.seu.timetable.domain.BoardMeta
import com.seu.timetable.domain.BoardSource
import com.seu.timetable.domain.Course
import com.seu.timetable.domain.CourseSession
import com.seu.timetable.domain.MANUAL_TERM_PREFIX
import com.seu.timetable.domain.PeriodTime
import com.seu.timetable.domain.TermContext
import com.seu.timetable.domain.Timetable
import java.time.LocalDate

/**
 * 课表业务层：导入 / 复制 / 自建 / 切换 / 增删改课程。
 *
 * 课表是本地资产：仅导入时联网下载，之后可完全离线使用。
 * 写操作一律「先内容、后索引」，两步之间进程被杀时最坏留下孤儿内容文件，
 * 不会出现「索引里有这张表、打开却是空的」这类看似丢数据的状况。
 */
class TimetableRepository(
    context: Context,
    private val client: EhallClient = EhallClient(),
) {

    private val library = TimetableLibrary(context)
    private val ehall = EhallTimetableSource(client)

    /** 教务会话探测——导入页用它区分"要登录"和"网络坏了" */
    suspend fun probeSession(): SessionProbe = client.probeSession()

    suspend fun hasSession(): Boolean = client.hasSession()

    // ------------------------------------------------------------ 读

    suspend fun index(): BoardIndex = library.readIndex()

    /** 打开某张课表；读不到元信息返回 null。当前周现算不落盘：它依赖「今天」，存盘即过期。 */
    suspend fun open(id: String, today: LocalDate = LocalDate.now()): LoadedBoard? {
        val idx = library.readIndex()
        val meta = idx.meta(id) ?: return null
        val content = library.readContent(id)
        return LoadedBoard(meta, buildTimetable(meta, content, today), content)
    }

    suspend fun openActive(today: LocalDate = LocalDate.now()): LoadedBoard? =
        library.readIndex().effectiveActiveId?.let { open(it, today) }

    /**
     * 各课表的课程数。索引与内容分开存（见 [BoardMeta]），故逐一读内容文件；
     * 内容仅数 KB，不值得为省 IO 冗余进索引，两处真相必然漏同步。
     */
    suspend fun courseCounts(): Map<String, Int> {
        val idx = library.readIndex()
        return idx.boards.associate { it.id to library.readContent(it.id).courses.size }
    }

    // ------------------------------------------------------------ 切换 / 改名 / 删除

    suspend fun activate(id: String) {
        val idx = library.readIndex()
        if (idx.meta(id) == null) return
        library.writeIndex(idx.copy(activeId = id))
    }

    suspend fun rename(id: String, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val idx = library.readIndex()
        val old = idx.meta(id) ?: return
        library.writeIndex(
            idx.copy(
                boards = idx.boards.map {
                    if (it.id == id) old.copy(name = clean, updatedAt = now()) else it
                }
            )
        )
    }

    /** 删一张课表：元信息 + 内容一起删；若删的是当前选中，自动切到剩下的第一张。 */
    suspend fun remove(id: String) {
        val idx = library.readIndex()
        if (idx.meta(id) == null) return
        val rest = idx.boards.filterNot { it.id == id }
        library.deleteContent(id)
        library.writeIndex(
            idx.copy(
                boards = rest,
                activeId = if (idx.activeId == id) rest.firstOrNull()?.id else idx.activeId,
            )
        )
    }

    /** 复制一张课表。内容数据类不可变，修改即生成新对象，故无需逐条 clone。 */
    suspend fun duplicate(id: String, newName: String? = null): String? {
        val idx = library.readIndex()
        val src = idx.meta(id) ?: return null
        val newId = library.newId()
        val copy = src.copy(
            id = newId,
            name = newName?.trim()?.takeIf { it.isNotEmpty() } ?: "${src.name} 副本",
            source = BoardSource.COPY,
            createdAt = now(),
            updatedAt = now(),
        )
        val content = library.readContent(id)
        library.writeContent(newId, content)
        library.writeIndex(idx.copy(boards = idx.boards + copy, activeId = idx.activeId))
        return newId
    }

    // ------------------------------------------------------------ 导入 / 新建

    /**
     * 从教务查一份课表（先不落盘），供导入页展示摘要后确认写入。
     * @throws NotLoggedInException 会话失效，UI 应弹登录页
     * @throws TimetableException 学期查不到 / 网络出错
     */
    suspend fun fetchFromEhall(termCode: String?): Timetable = ehall.load(termCode)

    /** 当前学期代码（`dqxnxq.do`）。拿不到返回 null。 */
    suspend fun currentTermCode(): String? = runCatching { client.currentTerm()?.termCode }.getOrNull()

    /** 当前学期名（`dqxnxq.do` 的 MC），如 `2026-2027学年秋季学期` */
    suspend fun currentTermName(): String? = runCatching { client.currentTerm()?.termName }.getOrNull()

    /**
     * 把 [fetchFromEhall] 得到的课表存为本地课表。@param name 空则用学期名，再空用学期代码。
     * @return 新建课表 id
     */
    suspend fun saveImported(timetable: Timetable, name: String, termName: String? = null): String {
        val idx = library.readIndex()
        val id = library.newId()

        // 学期名优先用用户填的，其次服务端给的，最后退成学期代码
        val resolvedTerm = timetable.term.copy(
            termName = termName?.trim()?.takeIf { it.isNotEmpty() }
                ?: timetable.term.termName.ifBlank { timetable.term.termCode }
        )

        val finalName = name.trim().takeIf { it.isNotEmpty() }
            ?: resolvedTerm.termName
        val unique = uniqueName(finalName, idx.boards.map { it.name })

        val meta = BoardMeta(
            id = id,
            name = unique,
            source = BoardSource.EHALL,
            term = resolvedTerm,
            // 导入课表不写 schedule（空 = 用默认作息），避免固化 13 行后无法跟随学校作息调整。
            schedule = emptyList(),
            sourceTermCode = resolvedTerm.termCode,
            lastImportAt = now(),
            createdAt = now(),
            updatedAt = now(),
        )

        library.writeContent(
            id,
            BoardContent(timetable.courses, timetable.sessions, timetable.unplaced),
        )
        library.writeIndex(idx.copy(boards = idx.boards + meta, activeId = id))
        return id
    }

    /**
     * 新建空白课表（自建），内容为空，课程由用户在 App 内添加。
     * @param firstMonday 第一周周一，自建课表也需它才能推算周次。
     * @param schedule 作息；空 = 用学校默认。沿用他表时用 [copySettingsFrom] 取来再传入。
     * @param copiedFrom 作息复制来源课表名（仅展示）。
     */
    suspend fun createBlank(
        name: String,
        firstMonday: LocalDate,
        totalWeeks: Int,
        lastTeachingWeek: Int = totalWeeks,
        morningPeriods: Int = 5,
        afternoonPeriods: Int = 5,
        eveningPeriods: Int = 3,
        schedule: List<PeriodTime> = emptyList(),
        copiedFrom: String? = null,
    ): String {
        val idx = library.readIndex()
        val id = library.newId()
        val label = name.trim().takeIf { it.isNotEmpty() } ?: "我的课表"

        val term = TermContext(
            // 前缀为哨兵而非可见学期名（见 MANUAL_TERM_PREFIX）；有此前缀即标记该表不可手动同步。
            termCode = MANUAL_TERM_PREFIX + id,
            termName = label,
            firstMonday = firstMonday,
            totalWeeks = totalWeeks,
            lastTeachingWeek = lastTeachingWeek,
            morningPeriods = morningPeriods,
            afternoonPeriods = afternoonPeriods,
            eveningPeriods = eveningPeriods,
        )

        val meta = BoardMeta(
            id = id,
            name = uniqueName(label, idx.boards.map { it.name }),
            source = BoardSource.MANUAL,
            term = term,
            schedule = schedule,
            copiedFrom = copiedFrom,
            createdAt = now(),
            updatedAt = now(),
        )

        library.writeContent(id, BoardContent.EMPTY)
        library.writeIndex(idx.copy(boards = idx.boards + meta, activeId = id))
        return id
    }

    // ------------------------------------------------------------ 内容修改

    /** 整份内容替换，单点修改（改课 / 删课 / 加课）均走它。内容即一个 JSON，整份写回可避免并发交错丢更新。 */
    suspend fun saveContent(id: String, content: BoardContent) {
        val idx = library.readIndex()
        if (idx.meta(id) == null) return
        library.writeContent(id, content)
        touch(id)
    }

    /** 更新（或新增）一门课。id 不存在则追加。 */
    suspend fun upsertCourse(id: String, course: Course) {
        val content = library.readContent(id)
        val exists = content.courses.any { it.id == course.id }
        val courses = if (exists) {
            content.courses.map { if (it.id == course.id) course else it }
        } else {
            content.courses + course
        }
        saveContent(id, content.copy(courses = courses))
    }

    /** 一站式保存「一门课 + 其全部时间块」，分开调用会留下「课已存、块未存」的中间态。 */
    suspend fun saveCourseWithSessions(
        id: String,
        course: Course,
        sessions: List<CourseSession>,
    ) {
        val content = library.readContent(id)
        val courses = if (content.courses.any { it.id == course.id }) {
            content.courses.map { if (it.id == course.id) course else it }
        } else {
            content.courses + course
        }
        val kept = content.sessions.filterNot { it.courseId == course.id }
        saveContent(id, content.copy(courses = courses, sessions = kept + sessions))
    }

    /** 删一门课，连同它的所有时间块（只清块会留下一个永远不显示的幽灵课程）。 */
    suspend fun deleteCourse(id: String, courseId: String) {
        val content = library.readContent(id)
        saveContent(
            id,
            content.copy(
                courses = content.courses.filterNot { it.id == courseId },
                sessions = content.sessions.filterNot { it.courseId == courseId },
            )
        )
    }

    /** 只删一个时间块（同一门课还有别的时段时用） */
    suspend fun deleteSession(id: String, sessionId: String) {
        val content = library.readContent(id)
        saveContent(id, content.copy(sessions = content.sessions.filterNot { it.id == sessionId }))
    }

    // ------------------------------------------------------------ 学期骨架

    /**
     * 修改自建课表学期骨架（第一周周一 / 总周数 / 最后教学周 / 节次分组）。
     * 第一周周一是时间原点，填错会整体偏移日期且界面无异常，故必须可改；
     * 导入课表的骨架来自 `cxjcs.do`，属服务端权威值，本地改即假数据。
     * @return 非自建或找不到返回 false。
     */
    suspend fun saveTermConfig(
        id: String,
        firstMonday: LocalDate,
        totalWeeks: Int,
        lastTeachingWeek: Int,
        morningPeriods: Int,
        afternoonPeriods: Int,
        eveningPeriods: Int,
    ): Boolean {
        val idx = library.readIndex()
        val old = idx.meta(id) ?: return false
        if (old.source != BoardSource.MANUAL) return false

        val updated = old.copy(
            term = old.term.copy(
                firstMonday = firstMonday,
                totalWeeks = totalWeeks,
                lastTeachingWeek = lastTeachingWeek.coerceIn(1, totalWeeks),
                morningPeriods = morningPeriods,
                afternoonPeriods = afternoonPeriods,
                eveningPeriods = eveningPeriods,
            ),
            updatedAt = now(),
        )
        library.writeIndex(idx.copy(boards = idx.boards.map { if (it.id == id) updated else it }))
        return true
    }

    // ------------------------------------------------------------ 作息时间

    /**
     * 保存本张课表的作息；空 list = 恢复默认作息（非「无作息」）。
     * 仅改 [id] 这一张，其它课表不动；沿用他表时用 [settingsOf] 取来再调用，复制即一次性独立。
     * @param copiedFrom 复制来源课表名（仅展示）；恢复默认时清空。
     */
    suspend fun saveSchedule(
        id: String,
        times: List<PeriodTime>,
        copiedFrom: String? = null,
    ) {
        val idx = library.readIndex()
        val old = idx.meta(id) ?: return
        library.writeIndex(
            idx.copy(
                boards = idx.boards.map {
                    if (it.id == id) {
                        old.copy(
                            schedule = times,
                            copiedFrom = if (times.isEmpty()) null else copiedFrom,
                            updatedAt = now(),
                        )
                    } else {
                        it
                    }
                }
            )
        )
    }

    /** 恢复默认作息（SEU 13 节）。只影响这一张课表。 */
    suspend fun resetSchedule(id: String) = saveSchedule(id, emptyList(), copiedFrom = null)

    /**
     * 是否在周网格里显示非本周课程（半透明影子块）。
     * 刻意做成独立写入而非并入设置页的「保存」：它属于预览性质的开关，
     * 用户预期点了立即生效，要求再按保存会让人以为开关坏了。
     */
    suspend fun setShowOutOfWeek(id: String, value: Boolean) {
        val idx = library.readIndex()
        if (idx.meta(id) == null) return
        library.writeIndex(
            idx.copy(
                boards = idx.boards.map {
                    if (it.id == id) it.copy(showOutOfWeek = value, updatedAt = now()) else it
                }
            )
        )
    }

    /**
     * 读一张课表的可复制设置，供「从其它课表导入设置」用。
     *
     * 不只返回作息数值，还要带出「来源是否在用学校默认」：
     * 来源用默认时 [scheduleToApply] 返回空 list，新表同样记为「用默认」，
     * 以后学校调整作息两张表会一起跟上；来源自定义过才真抄那 13 行数值。
     * 若一律抄数值，复制出来的表都会变成「自定义」而与学校默认脱钩。
     */
    suspend fun settingsOf(id: String): SettingsSnapshot? {
        val meta = library.readIndex().meta(id) ?: return null
        return SettingsSnapshot(
            boardName = meta.name,
            custom = meta.hasCustomSchedule,
            schedule = meta.periodSchedule.times,
            morningPeriods = meta.term.morningPeriods,
            afternoonPeriods = meta.term.afternoonPeriods,
            eveningPeriods = meta.term.eveningPeriods,
        )
    }

    // ------------------------------------------------------------ 手动同步

    /**
     * 手动从教务重新拉一个学期，覆盖这张课表的课程数据。
     *
     * 必须由用户显式触发：课表是本机资产，用户改过的内容（改名、挪节次、删课）即唯一真相，
     * 自动同步会将其静默冲掉，表现为「我改的东西自己变回去了」。
     * 覆盖时按课程配对保留教务不提供的本地属性（[Course.colorOverride] / [Course.credit] /
     * [Course.note]），否则用户挑的颜色会在一次同步后无提示地变回自动分配。
     * 配对键先按 [Course.id]（即 JXBID），再退到 `课程号 + 课序号`——教务重排教学班时 JXBID 会变。
     *
     * 学期骨架（第一周周一 / 总周数 / 节次分组）来自 `cxjcs.do`，按服务端刷新；
     * 作息时间不动（接口不提供时刻），课表名也不改（那是用户起的）。
     */
    suspend fun syncFromEhall(boardId: String): SyncOutcome {
        val idx = library.readIndex()
        val meta = idx.meta(boardId) ?: return SyncOutcome.Failed("这张课表已经不存在了。")

        val termCode = meta.syncTermCode
            ?: return SyncOutcome.NotSyncable(
                "「${meta.name}」是自建课表，教务系统没有对应的学期可以拉取。"
            )

        val fresh = try {
            ehall.load(termCode)
        } catch (e: NotLoggedInException) {
            // 交给 UI 去弹登录页——同步是个用户显式发起的动作，值得为它走一次认证
            return SyncOutcome.NeedLogin
        } catch (e: Exception) {
            return SyncOutcome.Failed(e.message ?: (e::class.simpleName ?: "未知错误"))
        }

        val old = library.readContent(boardId)
        val oldById = old.courses.associateBy { it.id }
        val oldByKey = old.courses
            .filter { it.courseCode.isNotBlank() }
            .associateBy { it.courseCode to it.classNo }

        val merged = fresh.courses.map { c ->
            val prev = oldById[c.id] ?: oldByKey[c.courseCode to c.classNo]
            if (prev == null) {
                c
            } else {
                c.copy(
                    colorOverride = prev.colorOverride,
                    credit = prev.credit,
                    note = prev.note,
                )
            }
        }

        // 顺序同所有写操作：先内容、后索引（见类注释）
        library.writeContent(
            boardId,
            BoardContent(merged, fresh.sessions, fresh.unplaced),
        )

        val updated = meta.copy(
            term = meta.term.copy(
                // 学期骨架以服务端为准，但学期名保留用户/首次导入时定下的那个
                termName = meta.term.termName.ifBlank { fresh.term.termName },
                firstMonday = fresh.term.firstMonday,
                totalWeeks = fresh.term.totalWeeks,
                lastTeachingWeek = fresh.term.lastTeachingWeek,
                morningPeriods = fresh.term.morningPeriods,
                afternoonPeriods = fresh.term.afternoonPeriods,
                eveningPeriods = fresh.term.eveningPeriods,
            ),
            lastImportAt = now(),
            updatedAt = now(),
        )
        library.writeIndex(
            idx.copy(boards = idx.boards.map { if (it.id == boardId) updated else it })
        )

        return SyncOutcome.Success(
            courseCount = merged.size,
            sessionCount = fresh.sessions.size,
            unplacedCount = fresh.unplaced.size,
            changed = old.courses.size != merged.size || old.sessions.size != fresh.sessions.size,
        )
    }

    // ------------------------------------------------------------ 内部

    /** 更新 `updatedAt`。读写磁盘都是挂起操作，所以这里也必须是 suspend。 */
    private suspend fun touch(id: String) {
        val idx = library.readIndex()
        val old = idx.meta(id) ?: return
        library.writeIndex(
            idx.copy(
                boards = idx.boards.map { if (it.id == id) old.copy(updatedAt = now()) else it }
            )
        )
    }

    private fun now(): Long = System.currentTimeMillis()

    private fun buildTimetable(meta: BoardMeta, content: BoardContent, today: LocalDate): Timetable =
        Timetable(
            term = meta.term,
            courses = content.courses,
            sessions = content.sessions,
            unplaced = content.unplaced,
            // 学期还没开始（负数/0）时夹到第 1 周，否则周次切换器会显示"第 0 周"
            currentWeek = meta.term.weekOf(today).coerceAtLeast(1),
        )

    companion object {
        /** 同名就加 `(2)`、`(3)`…——两张同名课表在列表中无法区分 */
        internal fun uniqueName(base: String, taken: List<String>): String {
            if (base !in taken) return base
            var n = 2
            while ("$base ($n)" in taken) n++
            return "$base ($n)"
        }
    }
}

/**
 * 打开一张课表的结果：元信息 + 运行时课表 + 原始内容。
 * 内容一并带出是因为编辑页要基于最新的 content 修改，再读一次磁盘既多余也可能读到更新的版本。
 */
data class LoadedBoard(
    val meta: BoardMeta,
    val timetable: Timetable,
    val content: BoardContent,
)

/** 手动同步的结果。UI 按它决定"提示成功 / 弹登录页 / 报错"。 */
sealed interface SyncOutcome {

    /**
     * @param courseCount 同步后这张课表有几门课
     * @param changed `false` 表示内容与本地一致，UI 应提示「已是最新」而非笼统的「同步成功」。
     */
    data class Success(
        val courseCount: Int,
        val sessionCount: Int,
        val unplacedCount: Int,
        val changed: Boolean,
    ) : SyncOutcome

    /** 会话失效 —— 调用方该弹登录页，登录成功后可以重试这一次同步 */
    data object NeedLogin : SyncOutcome

    /** 这张课表不能同步（自建课表 / 学期代码不可用） */
    data class NotSyncable(val message: String) : SyncOutcome

    data class Failed(val message: String) : SyncOutcome
}

/**
 * 一张课表里可被复制过去的那部分设置。
 *
 * 只含作息时间与节次分组，不含第一周周一 / 总周数——后两者属于「这个学期」，
 * 换一张课表就是另一个学期，抄过去反而是错的。
 *
 * 用法：`repo.saveSchedule(目标id, snap.scheduleToApply, snap.copiedFromLabel)`。
 */
data class SettingsSnapshot(
    val boardName: String,
    /** 来源那张表是否自定义过作息 */
    val custom: Boolean,
    /** 生效的作息值（来源没自定义时就是学校默认的 13 行，供界面预览） */
    val schedule: List<PeriodTime>,
    val morningPeriods: Int,
    val afternoonPeriods: Int,
    val eveningPeriods: Int,
) {
    /**
     * 应写进目标课表的作息。来源在用学校默认时返回空 list（而非 13 行数值）：
     * 空 list 的语义是「跟随学校默认」，两张表以后会一起跟上学校调整。
     */
    val scheduleToApply: List<PeriodTime> get() = if (custom) schedule else emptyList()

    /** 写进 [BoardMeta.copiedFrom] 的来源名；用默认时没有"抄自哪里"可言，返回 null */
    val copiedFromLabel: String? get() = if (custom) boardName else null
}
