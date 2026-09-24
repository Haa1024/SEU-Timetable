package com.seu.timetable.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.seu.timetable.domain.CourseColorAssigner
import com.seu.timetable.domain.CourseSession
import com.seu.timetable.domain.DAY_NAMES_FULL
import com.seu.timetable.domain.PeriodSchedule
import com.seu.timetable.domain.SessionStatus
import com.seu.timetable.domain.Timetable
import com.seu.timetable.ui.components.BellIcon
import com.seu.timetable.ui.components.CourseRow
import com.seu.timetable.ui.components.Tag
import com.seu.timetable.ui.guide.GuideTargetKey
import com.seu.timetable.ui.guide.guideTarget
import com.seu.timetable.ui.theme.LocalSeuColors
import com.seu.timetable.ui.theme.LocalSeuType
import com.seu.timetable.ui.theme.SeuRadius
import com.seu.timetable.ui.theme.courseColorOf
import com.seu.timetable.util.PlayfulTalk
import java.time.LocalDate
import java.time.LocalTime

/**
 * 页面 01 · 今日课程（规格 4.1）。
 *
 * 与规格的一处偏离，因为数据拿不到：
 * 规格的「共 4 节 · 6 学时」→ 改成「共 N 门 · M 节」。
 * **学时字段课表接口不返回**（`xskcb.do` 的完整字段集里没有 XS/XF），
 * 「6 学时」根本算不出来，摆上去就是假数据。门数（去重 courseId）与
 * 节数（周期跨度求和）都能实算。
 */
@Composable
fun TodayPage(
    timetable: Timetable,
    schedule: PeriodSchedule,
    slots: CourseColorAssigner,
    today: LocalDate,
    now: LocalTime,
    onCourseClick: (String) -> Unit,
    onBellClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalSeuColors.current
    val t = LocalSeuType.current
    val week = timetable.term.weekOf(today)

    val allToday = timetable.sessionsOnDate(today)
    // 已经上完的不再列出，只留进行中与未开始的（与桌面小组件同一口径）
    val todays = allToday.filter { schedule.statusOf(it, now) != SessionStatus.FINISHED }
    val hero = resolveHero(timetable, schedule, today, now)

    // 空态取哪一组俏皮文案：全天无课 / 周末 / 今天的课已上完
    val emptyMood = when {
        allToday.isNotEmpty() -> PlayfulTalk.Mood.ALL_DONE
        today.dayOfWeek.value >= 6 -> PlayfulTalk.Mood.WEEKEND
        else -> PlayfulTalk.Mood.NO_CLASS
    }

    Column(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 24.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("今日", style = t.pageTitle, color = c.textPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${today.monthValue}月${today.dayOfMonth}日 " +
                        "${DAY_NAMES_FULL[today.dayOfWeek.value]} · 第 $week 周",
                    style = t.body,
                    color = c.textSecondary,
                )
            }
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(c.primarySoft)
                    .guideTarget(GuideTargetKey.TODAY_REMINDER)
                    .clickable { onBellClick() }
                    .padding(11.dp)
            ) {
                BellIcon(c.primary, 18.dp)
            }
        }

        Spacer(Modifier.height(20.dp))

        HeroCard(
            hero = hero,
            timetable = timetable,
            schedule = schedule,
            now = now,
            emptyMood = emptyMood,
            onCourseClick = onCourseClick,
        )

        Spacer(Modifier.height(20.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("今天的课", style = t.itemTitle, color = c.textPrimary)
            Text(
                when {
                    todays.isNotEmpty() -> "共 ${todays.map { it.courseId }.distinct().size} 门 · " +
                        "${todays.sumOf { it.periodSpan }} 节"
                    allToday.isEmpty() -> "无"
                    else -> "已全部上完"
                },
                style = t.caption,
                color = c.textSecondary,
            )
        }

        Spacer(Modifier.height(12.dp))

        if (todays.isEmpty()) {
            val (face, line) = PlayfulTalk.split(LocalContext.current, emptyMood)
            Text(face, style = t.itemTitle, color = c.textPrimary)
            Spacer(Modifier.height(6.dp))
            Text(line, style = t.body, color = c.textSecondary)
            return@Column
        }

        todays.forEachIndexed { index, session ->
            val course = timetable.courseOf(session)
            val triple = courseColorOf(slots[session.courseId])
            CourseRow(
                title = course?.name ?: "未知课程",
                subtitle = buildString {
                    val timeRange = schedule.timeRangeLabel(
                        session.startPeriod,
                        session.endPeriod,
                    )
                    append(timeRange ?: session.periodLabel())
                    if (session.room.isNotBlank()) append(" · ${session.room}")
                },
                statusText = session.periodLabel(),
                barColor = triple.bar,
                accentText = schedule.statusOf(session, now) == SessionStatus.ONGOING,
                onClick = { onCourseClick(session.courseId) },
            )
            if (index != todays.lastIndex) Spacer(Modifier.height(12.dp))
        }
    }
}

// ---------------------------------------------------------------------------

private data class Hero(
    val session: CourseSession,
    val isTomorrow: Boolean,
    val status: SessionStatus,
)

/**
 * 规格 4.1：取「当前时刻之后最早开始的课」；
 * 今天的课全结束了就展示**明天的第一节**并标注「明天」；再无则空状态。
 */
private fun resolveHero(
    timetable: Timetable,
    schedule: PeriodSchedule,
    today: LocalDate,
    now: LocalTime,
): Hero? {
    val todays = timetable.sessionsOnDate(today)

    todays.firstOrNull { schedule.statusOf(it, now) == SessionStatus.ONGOING }
        ?.let { return Hero(it, isTomorrow = false, status = SessionStatus.ONGOING) }

    schedule.nextSessionOf(todays, now)
        ?.let { return Hero(it, isTomorrow = false, status = SessionStatus.UPCOMING) }

    val first = timetable.sessionsOnDate(today.plusDays(1)).minByOrNull { it.startPeriod }
        ?: return null
    return Hero(first, isTomorrow = true, status = SessionStatus.UPCOMING)
}

@Composable
private fun HeroCard(
    hero: Hero?,
    timetable: Timetable,
    schedule: PeriodSchedule,
    now: LocalTime,
    emptyMood: PlayfulTalk.Mood,
    onCourseClick: (String) -> Unit,
) {
    val c = LocalSeuColors.current
    val t = LocalSeuType.current

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SeuRadius.heroCard))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(SeuRadius.heroCard))
            .then(
                if (hero != null) Modifier.clickable { onCourseClick(hero.session.courseId) }
                else Modifier
            )
            .padding(18.dp)
    ) {
        if (hero == null) {
            val (face, line) = PlayfulTalk.split(LocalContext.current, emptyMood)
            Tag("暂无后续课程", c.textSecondary, c.surfaceSunken)
            Spacer(Modifier.height(14.dp))
            Text(face, style = t.heroName, color = c.textPrimary)
            Spacer(Modifier.height(6.dp))
            Text(line, style = t.body, color = c.textSecondary)
            return@Column
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tagText = when {
                hero.isTomorrow -> "明天"
                hero.status == SessionStatus.ONGOING -> "进行中"
                else -> "即将开始"
            }
            Tag(tagText, c.primary, c.primarySoft)

            val rightText = when {
                hero.isTomorrow -> "明天第一节"
                hero.status == SessionStatus.ONGOING -> "正在上课"
                else -> schedule.minutesUntilStart(hero.session, now)
                    ?.let { "还有 $it 分钟" }
                    .orEmpty()
            }
            if (rightText.isNotEmpty()) {
                Text(rightText, style = t.caption, color = c.textSecondary)
            }
        }

        Spacer(Modifier.height(14.dp))

        Text(
            timetable.courseOf(hero.session)?.name ?: "未知课程",
            style = t.heroName,
            color = c.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(10.dp))

        val metas = buildList {
            schedule.timeRangeLabel(hero.session.startPeriod, hero.session.endPeriod)
                ?.let { add(it) }
            add(hero.session.periodLabel())
            if (hero.session.room.isNotBlank()) add(hero.session.room)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            metas.forEachIndexed { i, s ->
                if (i > 0) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .size(3.dp)
                            .clip(CircleShape)
                            .background(c.textTertiary)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(s, style = t.body, color = c.textSecondary)
            }
        }

        // 进度条只在「进行中」显示（规格 4.1 末段）
        if (hero.status == SessionStatus.ONGOING) {
            Spacer(Modifier.height(14.dp))
            val progress = schedule.progressOf(hero.session, now) ?: 0f
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(c.border)
            ) {
                if (progress > 0f) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(c.primary)
                    )
                }
            }
        }
    }
}
