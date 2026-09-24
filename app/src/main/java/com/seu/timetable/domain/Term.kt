package com.seu.timetable.domain

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 上午 / 下午 / 晚上。分界由服务端下发（cxjcs 的 SFJJ/XFJJ/WSJJ），不是猜的。 */
enum class PeriodGroup(val label: String) {
    MORNING("上午"),
    AFTERNOON("下午"),
    EVENING("晚上"),
}

/**
 * 学期上下文。计算"今天是第几周""本周周一是哪天"均依赖它。
 *
 * 数据来自 `POST /jwapp/sys/wdkb/modules/jshkcb/cxjcs.do`：
 * `XQKSRQ` = 第一周周一、`ZZC` = 总周数、`SFJJ/XFJJ/WSJJ` = 上午/下午/晚上各几节。
 *
 * 注意：接口不提供节次对应的时钟时间（如第 1 节的具体时刻）。
 * 在 78 条真实请求的全部 JS 中，仅有「上午/下午/晚上」分组，无时刻表。
 * 因此本 App 仅显示节次序号与用户在设置中配置的作息时间，不推测时刻。
 *
 * 添加 `@Serializable` 的原因：课表如今需存储在本地（本地课表库，见 [BoardData]），
 * 不再是"每次启动从教务拉取"。`firstMonday` 是唯一的非基本类型字段，需要自定义序列化器。
 */
@Serializable
data class TermContext(
    val termCode: String,             // "2026-2027-2"
    val termName: String = "",        // "2026-2027学年秋季学期"
    @Serializable(with = LocalDateSerializer::class)
    val firstMonday: LocalDate,       // 2026-09-21
    val totalWeeks: Int,              // 18  ← ZZC
    /**
     * 最后教学周（`ZJXZC`，实测 16）。周次切换器以它为主。
     * 它与 `totalWeeks`（18）不相等，二者不可混用。
     * 此外它也不等于位图长度——位图长度实测为 16 或 18 两种，无规律可循。
     */
    val lastTeachingWeek: Int = totalWeeks,
    val morningPeriods: Int = 5,      // SFJJ
    val afternoonPeriods: Int = 5,    // XFJJ
    val eveningPeriods: Int = 3,      // WSJJ
) {
    /** 一天总节数。SEU 实测 = 5 + 5 + 3 = 13 */
    val periodsPerDay: Int get() = morningPeriods + afternoonPeriods + eveningPeriods

    val periodNumbers: List<Int> get() = (1..periodsPerDay).toList()

    fun groupOf(period: Int): PeriodGroup = when {
        period <= morningPeriods -> PeriodGroup.MORNING
        period <= morningPeriods + afternoonPeriods -> PeriodGroup.AFTERNOON
        else -> PeriodGroup.EVENING
    }

    /** 这一段节次有没有跨过上午/下午/晚上的分界；跨了 UI 上要留视觉分隔 */
    fun crossesGroupBoundary(from: Int, to: Int): Boolean = groupOf(from) != groupOf(to)

    /** 某天是第几周（本地推算；服务端权威值走 dqzc.do） */
    fun weekOf(date: LocalDate): Int =
        (ChronoUnit.DAYS.between(firstMonday, date) / 7).toInt() + 1

    /** 第 week 周、星期 dayOfWeek 的具体日期 */
    fun dateOf(week: Int, dayOfWeek: Int): LocalDate =
        firstMonday.plusWeeks((week - 1).toLong()).plusDays((dayOfWeek - 1).toLong())

    /** cxjcs.do 的 XN 参数："2026-2027-2" -> "2026-2027" */
    val yearParam: String
        get() = termCode.split("-").take(2).joinToString("-")

    /** cxjcs.do 的 XQ 参数："2026-2027-2" -> "2" */
    val termParam: String
        get() = termCode.split("-").getOrNull(2) ?: "1"

    companion object {
        /** 拿不到服务端数据时的兜底分组 */
        const val DEFAULT_PERIODS_PER_DAY = 13
    }
}

/** 周几的中文名。下标 1..7，第 0 位留空好对齐。 */
val DAY_NAMES = listOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")

val DAY_NAMES_FULL = listOf("", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")

/** LocalDate 的 dayOfWeek（MONDAY=1..SUNDAY=7）正好和 SKXQ 一致，不用转换。 */
fun LocalDate.seuDayOfWeek(): Int = dayOfWeek.value
