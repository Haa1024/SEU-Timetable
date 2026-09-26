package com.seu.timetable.domain

import kotlinx.serialization.Serializable

/**
 * 课表的来源类型。不决定能否编辑（课表属本机资产，任何来源均可增删改），仅承担两职：
 * 列表 / 详情标注来源；判断能否手动同步——带真实学期代码者（[EHALL]、[COPY]）方可拉取。
 */
@Serializable
enum class BoardSource {
    EHALL,
    MANUAL,
    COPY,
}

/**
 * 自建课表的学期代码前缀（如 `manual-b3f1a2…`）。
 *
 * 自建课表不对应教务的任何学期，但 [TermContext.termCode] 非空，故用此前缀构造占位代码。
 * 前缀在两处以哨兵值使用：[BoardMeta.isSyncable] 据此判定教务侧无对应学期、不可同步；
 * [TimetableRepository.saveImported] 等做学期名兜底。它不是用户可见的学期名，
 * 展示应使用 [TermContext.termName]。
 */
const val MANUAL_TERM_PREFIX = "manual-"

/**
 * 单张课表的元信息。与课程内容分开存储：列表页只需名称 / 学期 / 来源 / 更新时间等少量字段，
 * 若课程数据也在同一文件，渲染一行文字便要读入数十 KB；分离后 `index.json` 体积恒定很小，
 * 列表页可瞬时打开，仅打开某张课表时才读其内容文件。
 *
 * [term] 置于元信息中，因为它是课表骨架：第几周、每周日期区间与节次分组均依赖它。
 */
@Serializable
data class BoardMeta(
    val id: String,
    val name: String,
    val source: BoardSource,
    val term: TermContext,

    /**
     * 作息时间（13 节的起止时刻）。
     *
     * 空 list 表示「使用学校默认作息」（[PeriodTimes.SEU]），而非「没有作息」：学校日后调整
     * 默认作息（如变更午休）时，未自定义的课表会自动跟随；用户一旦修改即固定为自定义版本。
     * 用哨兵值而非复制 13 行数据，正是为了保留该性质。
     * 它仅指学校默认，不表示「所有课表共用一套」——每张课表的作息独立存储，互不影响；
     * 如需复用可经 [copiedFrom] 复制，复制是一次性取值，完成后两边各自独立。
     */
    val schedule: List<PeriodTime> = emptyList(),

    /**
     * 作息复制自哪张课表（记录名称，仅用于展示）。
     * 存名称而非 id：被复制方删除后 id 悬空即无从追溯。它不参与任何逻辑判断，
     * 不做「来源变更后自动同步」之类的联动，否则又会把两边重新绑定。
     */
    val copiedFrom: String? = null,

    /** 从哪个学期代码导入的（仅 EHALL 来源有值），用于导入页标记"已导入过" */
    val sourceTermCode: String? = null,

    /**
     * 周网格中是否绘制非本周课程（半透明影子块）。
     * 属每张课表自身而非全局设置：「是否查看其他周」是这张表的使用习惯，默认关闭。
     */
    val showOutOfWeek: Boolean = false,

    /** 上次从教务拉取的时刻（毫秒）。自建课表恒为 null。 */
    val lastImportAt: Long? = null,

    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {

    /** 生效的作息表：没自定义就用默认 */
    val periodSchedule: PeriodSchedule
        get() = PeriodSchedule(schedule.ifEmpty { PeriodTimes.SEU })

    /** 用户是否自己改过作息 */
    val hasCustomSchedule: Boolean get() = schedule.isNotEmpty()

    /** 作息来源的可读描述，课表库列表与设置页均使用。 */
    val scheduleLabel: String
        get() = when {
            !hasCustomSchedule -> "学校默认作息"
            copiedFrom != null -> "自定义作息 · 复制自「$copiedFrom」"
            else -> "自定义作息"
        }

    val sourceLabel: String
        get() = when (source) {
            BoardSource.EHALL -> "教务导入"
            BoardSource.MANUAL -> "自建"
            BoardSource.COPY -> "复制"
        }

    /**
     * 可用于在教务侧重新拉取的学期代码；无法获取时返回 null（不可手动同步）。
     *
     * 判据是学期代码的形式而非 [source]：复制教务课表所得的副本带着来源的 `sourceTermCode`，
     * 同样可同步；复制自建课表所得的副本代码为 `manual-xxx`，教务侧不存在该学期。
     */
    val syncTermCode: String?
        get() = sourceTermCode?.takeIf { it.isNotBlank() }
            ?: term.termCode.takeIf { it.isNotBlank() && !it.startsWith(MANUAL_TERM_PREFIX) }

    /** 这张课表能不能手动同步（能不能在教务找到对应学期） */
    val isSyncable: Boolean get() = syncTermCode != null

    /** 列表页那行副标题：`2026-2027学年秋季学期 · 教务导入 · 6 门课` */
    fun subtitleOf(courseCount: Int): String {
        val term = term.termName.ifBlank { term.termCode }
        return "$term · $sourceLabel · $courseCount 门课"
    }
}

/**
 * 单张课表的全部内容，与 [BoardMeta] 分开存储。
 * 它是 `Timetable` 的可序列化版本：`Timetable` 为带索引的运行时对象（含惰性 map），
 * 不直接序列化，只存这三个列表，读取时重建。
 */
@Serializable
data class BoardContent(
    val courses: List<Course> = emptyList(),
    val sessions: List<CourseSession> = emptyList(),
    val unplaced: List<UnplacedCourse> = emptyList(),
    /** AI receipt and idempotency IDs are committed in the same file as courses. */
    val aiState: kotlinx.serialization.json.JsonObject? = null,
) {

    val isEmpty: Boolean get() = courses.isEmpty() && sessions.isEmpty()

    companion object {
        val EMPTY = BoardContent()
    }
}

/**
 * 课表库索引文件。
 * `activeId` 只存一份：切换课表等价于改这一个字段，无需触碰内容文件，而切换是最频繁的操作。
 */
@Serializable
data class BoardIndex(
    val activeId: String? = null,
    val boards: List<BoardMeta> = emptyList(),
) {

    val isEmpty: Boolean get() = boards.isEmpty()

    fun meta(id: String?): BoardMeta? = boards.firstOrNull { it.id == id }

    val active: BoardMeta? get() = meta(activeId)

    /**
     * 生效的课表：`activeId` 指向者；已被删除或从未设置时回退到第一张。
     * 保证 activeId 不悬空，否则打开 App 显示空白，而用户明明拥有课表。
     */
    val effectiveActiveId: String?
        get() = when {
            activeId != null && boards.any { it.id == activeId } -> activeId
            else -> boards.firstOrNull()?.id
        }

    companion object {
        val EMPTY = BoardIndex()
    }
}
