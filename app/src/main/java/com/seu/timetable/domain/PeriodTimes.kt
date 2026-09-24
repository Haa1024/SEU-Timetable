package com.seu.timetable.domain

import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.LocalTime

/**
 * 一节课的起止时刻。
 *
 * 该表必须由人工确认，接口不提供。
 * ehall 的全部接口仅给出「上午 / 下午 / 晚上」的分组（`cxjcs.do` 的 `SFJJ/XFJJ/WSJJ`），
 * 无任何「第 1 节 08:00」之类的时刻——已逐条核查 78 条真实请求的 JS，确认不存在。
 * 因此今日页的倒计时、课内进度条与上课提醒，均依赖这张本地配置表。
 *
 * 作息时间表现已随课表一同存储在本地（自建课表允许用户自行填写），
 * 故本层亦需可序列化。`LocalTime` 需要自定义序列化器（见 SeuSerializers.kt）。
 */
@Serializable
data class PeriodTime(
    val index: Int,
    @Serializable(with = LocalTimeSerializer::class) val begin: LocalTime,
    @Serializable(with = LocalTimeSerializer::class) val end: LocalTime,
) {

    init {
        require(end > begin) {
            "第 $index 节的结束时间不能早于开始时间：$begin – $end"
        }
    }

    /** "08:00 – 08:45" */
    fun label(): String = "$begin – $end"

    /** "08:00" */
    fun beginLabel(): String = begin.toString()

    fun durationMinutes(): Long = Duration.between(begin, end).toMinutes()
}

/**
 * 作息时间表。
 *
 * 默认值为东南大学实际作息（2026-09-23 由用户提供并逐条确认），
 * 非推测所得，亦非设计稿中的占位示例。
 *
 * 结构规律（可用于自查是否抄错）：每节 45 分钟，节间休息 5 分钟；
 * 第 2 节后与第 7 节后各休息 15 分钟；午休 1 小时 45 分，晚休 45 分钟。
 *
 * 13 节 = 上午 1-5 + 下午 6-10 + 晚上 11-13，
 * 与接口 `cxjcs.do` 返回的 `SFJJ=5 / XFJJ=5 / WSJJ=3` 完全吻合
 * （两个独立来源互相印证）。
 */
object PeriodTimes {

    val SEU: List<PeriodTime> = listOf(
        PeriodTime(1, LocalTime.of(8, 0), LocalTime.of(8, 45)),
        PeriodTime(2, LocalTime.of(8, 50), LocalTime.of(9, 35)),
        PeriodTime(3, LocalTime.of(9, 50), LocalTime.of(10, 35)),
        PeriodTime(4, LocalTime.of(10, 40), LocalTime.of(11, 25)),
        PeriodTime(5, LocalTime.of(11, 30), LocalTime.of(12, 15)),
        PeriodTime(6, LocalTime.of(14, 0), LocalTime.of(14, 45)),
        PeriodTime(7, LocalTime.of(14, 50), LocalTime.of(15, 35)),
        PeriodTime(8, LocalTime.of(15, 50), LocalTime.of(16, 35)),
        PeriodTime(9, LocalTime.of(16, 40), LocalTime.of(17, 25)),
        PeriodTime(10, LocalTime.of(17, 30), LocalTime.of(18, 15)),
        PeriodTime(11, LocalTime.of(19, 0), LocalTime.of(19, 45)),
        PeriodTime(12, LocalTime.of(19, 50), LocalTime.of(20, 35)),
        PeriodTime(13, LocalTime.of(20, 40), LocalTime.of(21, 25)),
    )

    val default: List<PeriodTime> get() = SEU
}

/** 课程当前状态。今日页的文案与配色都按它分档。 */
enum class SessionStatus { UPCOMING, ONGOING, FINISHED }

/**
 * 作息表的查询封装。用户可以改（设置页），所以做成类而不是直接读 object。
 */
class PeriodSchedule(val times: List<PeriodTime> = PeriodTimes.SEU) {

    private val byPeriod: Map<Int, PeriodTime> = times.associateBy { it.index }

    val maxPeriod: Int get() = byPeriod.keys.maxOrNull() ?: 0

    fun timeOf(period: Int): PeriodTime? = byPeriod[period]

    fun beginOf(period: Int): LocalTime? = byPeriod[period]?.begin

    fun endOf(period: Int): LocalTime? = byPeriod[period]?.end

    /** "08:00 – 08:45"；缺该节配置时返回 null */
    fun labelOf(period: Int): String? = byPeriod[period]?.label()

    /** 一个时间块的起止："10:00 – 11:40"。缺配置返回 null。 */
    fun timeRangeLabel(fromPeriod: Int, toPeriod: Int): String? {
        val begin = beginOf(fromPeriod) ?: return null
        val end = endOf(toPeriod) ?: return null
        return "$begin – $end"
    }

    // ---------- 今日页需要的三个判断 ----------

    fun statusOf(session: CourseSession, now: LocalTime): SessionStatus {
        val begin = beginOf(session.startPeriod)
        val end = endOf(session.endPeriod)
        return when {
            begin == null || end == null -> SessionStatus.UPCOMING
            now < begin -> SessionStatus.UPCOMING
            now > end -> SessionStatus.FINISHED
            else -> SessionStatus.ONGOING
        }
    }

    /** 距开始还有几分钟；已开始或拿不到时间返回 null */
    fun minutesUntilStart(session: CourseSession, now: LocalTime): Long? {
        val begin = beginOf(session.startPeriod) ?: return null
        if (now >= begin) return null
        return Duration.between(now, begin).toMinutes()
    }

    /** 进行中的进度 0f..1f；未开始或已结束返回 null */
    fun progressOf(session: CourseSession, now: LocalTime): Float? {
        val begin = beginOf(session.startPeriod) ?: return null
        val end = endOf(session.endPeriod) ?: return null
        if (now <= begin || now >= end) return null
        val total = Duration.between(begin, end).toMinutes().toFloat()
        if (total <= 0f) return null
        val done = Duration.between(begin, now).toMinutes().toFloat()
        return (done / total).coerceIn(0f, 1f)
    }

    /** 今天里 begin > now 的最早一节课（"下一节课"） */
    fun nextSessionOf(
        sessions: List<CourseSession>,
        now: LocalTime,
    ): CourseSession? = sessions
        .filter { (beginOf(it.startPeriod) ?: return@filter false) > now }
        .minByOrNull { beginOf(it.startPeriod)!! }

    /**
     * 自查作息表是否录入错误。人工抄录 13 行时间极易出现"结束早于开始"之类的笔误
     * （确曾发生：2026-09-23 用户提供的版本中第 9 节写为 `16:40 – 15:25`）。
     * 单元测试会执行该函数，将笔误拦截在发布之前。
     */
    fun problems(): List<String> {
        val out = ArrayList<String>()
        val sorted = times.sortedBy { it.index }

        sorted.forEachIndexed { i, t ->
            if (t.index != i + 1) out += "节次不连续：第 ${i + 1} 个位置的 index 是 ${t.index}"
            if (t.end <= t.begin) out += "第 ${t.index} 节结束不晚于开始：${t.begin} – ${t.end}"
            if (t.durationMinutes() != 45L) {
                out += "第 ${t.index} 节时长 ${t.durationMinutes()} 分钟（本校作息应为 45 分钟）"
            }
        }
        sorted.zipWithNext { a, b ->
            if (b.begin < a.end) out += "第 ${a.index} 节与第 ${b.index} 节时间重叠"
            if (a.index == 2 || a.index == 7) {
                val gap = Duration.between(a.end, b.begin).toMinutes()
                if (gap != 15L) out += "第 ${a.index} 节后应休息 15 分钟，实际 $gap 分钟"
            }
        }
        return out
    }

    companion object {
        /**
         * 安全构造入口，供用户输入/设置使用。
         * 不合法时返回 null，而非像 [PeriodTime] 的 init 那样抛出异常——
         * 硬编码常量应 fail fast，但用户输入不应导致 App 崩溃。
         */
        fun ofOrNull(index: Int, begin: String, end: String): PeriodTime? = try {
            PeriodTime(index, LocalTime.parse(begin.trim()), LocalTime.parse(end.trim()))
        } catch (_: Exception) {
            null
        }

        /**
         * 作息时间编辑页的整表校验入口。
         *
         * 返回 `Result` 而非 `null` 的原因：用户修改的是 13 行 × 2 个输入框，
         * 一旦某处有误，必须能说明"是哪一行、错在何处"，否则用户只能逐行猜测。
         * 此处将首个出错的行转换为可读信息并置入异常消息。
         */
        fun fromInputs(inputs: List<Triple<Int, String, String>>): Result<PeriodSchedule> {
            val times = ArrayList<PeriodTime>(inputs.size)
            inputs.forEach { (index, beginText, endText) ->
                val begin = runCatching { LocalTime.parse(beginText.trim()) }.getOrNull()
                    ?: return Result.failure(
                        IllegalArgumentException("第 $index 节的开始时间「${beginText.trim()}」不是合法时刻，要写成 08:00 这样")
                    )
                val end = runCatching { LocalTime.parse(endText.trim()) }.getOrNull()
                    ?: return Result.failure(
                        IllegalArgumentException("第 $index 节的结束时间「${endText.trim()}」不是合法时刻，要写成 08:45 这样")
                    )
                if (end <= begin) {
                    return Result.failure(
                        IllegalArgumentException("第 $index 节的结束时间（$end）要晚于开始时间（$begin）")
                    )
                }
                times += PeriodTime(index, begin, end)
            }
            if (times.isEmpty()) return Result.failure(IllegalArgumentException("作息时间不能为空"))
            return Result.success(PeriodSchedule(times))
        }
    }
}
