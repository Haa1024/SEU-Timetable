package com.seu.timetable.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import com.seu.timetable.MainActivity
import com.seu.timetable.R
import com.seu.timetable.data.TimetableLibrary
import com.seu.timetable.domain.CourseColorAssigner
import com.seu.timetable.domain.CourseSession
import com.seu.timetable.domain.DAY_NAMES
import com.seu.timetable.domain.SessionStatus
import com.seu.timetable.domain.Timetable
import com.seu.timetable.domain.seuDayOfWeek
import com.seu.timetable.ui.theme.CourseBarPalette
import com.seu.timetable.util.DebugLog
import com.seu.timetable.util.PlayfulTalk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/**
 * 今日课程桌面小组件。
 *
 * 运行模型：小组件由桌面（launcher）进程渲染，本类只在被系统唤醒时提交一份 RemoteViews，
 * 因此这里没有 Composable 可用——颜色走资源、文字走 setTextViewText、点击走 PendingIntent。
 *
 * 数据全部来自本机课表库（`files/boards/`），**不联网**，未登录也能正常显示。
 *
 * 两个尺寸共用本渲染逻辑，只有布局、行高与「是否显示周次」不同，
 * 见 [TodayWidgetSmall] 与 [TodayWidgetWide]。
 */

/** 布局里预留的行槽位数量；实际显示几行由小组件高度决定。 */
internal const val MAX_ROWS = 6

/** 4×2 的单行高度，须与 widget_today_4x2.xml 里的 26dp 一致，否则行数换算会错。 */
internal const val ROW_HEIGHT_DP = 26

/**
 * 2×2 的单行高度，须与 widget_today_2x2.xml 里的 34dp 一致。
 *
 * 比 4×2 高一档，是因为 2×2 的一行放不下「时刻 + 课名 + 教室」：实测可用宽度约 123dp，
 * 扣掉时刻 34dp + 色条与间距 13dp 后课名只剩约 76dp（六七个字），再塞教室就没了。
 * 故 2×2 改为每行两行文字——课名一行、教室一行，代价是每行多占 8dp。
 *
 * 行数由 [rowCapacity] 按桌面报告的高度除以本值换算，多的 8dp 会实打实地少换一行，
 * 所以这里的值必须和布局里的固定高度严格相等，改一处就得改另一处。
 */
internal const val ROW_HEIGHT_DP_SMALL = 34

/** 头部 + 分隔线 + 页脚 + 内边距占用的高度，用于从总高反推可显示行数。 */
internal const val CHROME_HEIGHT_DP = 70

internal val ROW_IDS = intArrayOf(
    R.id.widget_row_1, R.id.widget_row_2, R.id.widget_row_3,
    R.id.widget_row_4, R.id.widget_row_5, R.id.widget_row_6,
)

internal val TIME_IDS = intArrayOf(
    R.id.widget_time_1, R.id.widget_time_2, R.id.widget_time_3,
    R.id.widget_time_4, R.id.widget_time_5, R.id.widget_time_6,
)

internal val BAR_IDS = intArrayOf(
    R.id.widget_bar_1, R.id.widget_bar_2, R.id.widget_bar_3,
    R.id.widget_bar_4, R.id.widget_bar_5, R.id.widget_bar_6,
)

internal val NAME_IDS = intArrayOf(
    R.id.widget_name_1, R.id.widget_name_2, R.id.widget_name_3,
    R.id.widget_name_4, R.id.widget_name_5, R.id.widget_name_6,
)

internal val ROOM_IDS = intArrayOf(
    R.id.widget_room_1, R.id.widget_room_2, R.id.widget_room_3,
    R.id.widget_room_4, R.id.widget_room_5, R.id.widget_room_6,
)

/**
 * 两个尺寸的公共实现。
 *
 * 重绘一律异步：渲染要读磁盘上的课表 JSON。用 [goAsync] 而不引 WorkManager，
 * 是因为读的是数 KB 本地文件、毫秒级完成，为它排一次后台任务反而更重。
 */
abstract class BaseTodayWidget : AppWidgetProvider() {

    /** 本尺寸使用的布局资源 */
    protected abstract val layoutId: Int

    /** 是否显示教室。两个尺寸都显示，只是 2×2 把它排在课名下面的第二行。 */
    protected abstract val showRoom: Boolean

    /** 是否显示周次（2×2 头部空间不够，关掉） */
    protected abstract val showWeek: Boolean

    /** 单行高度，须与所用布局里 `widget_row_N` 的固定高度一致，行数按它换算。 */
    protected open val rowHeightDp: Int = ROW_HEIGHT_DP

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        redraw(context, manager, appWidgetIds.toList())
    }

    /** 用户拖动改变大小后重绘：可见行数取决于实际高度 */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        redraw(context, manager, listOf(appWidgetId))
    }

    private fun redraw(context: Context, manager: AppWidgetManager, ids: List<Int>) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ids.forEach { id ->
                    runCatching { render(context, manager, id) }
                        .onFailure { DebugLog.e("小组件渲染失败 id=$id：${it.message}") }
                }
            } finally {
                // 不 finish() 会被系统判定为 ANR
                pending.finish()
            }
        }
    }

    private suspend fun render(context: Context, manager: AppWidgetManager, id: Int) {
        val views = RemoteViews(context.packageName, layoutId)
        views.setOnClickPendingIntent(R.id.widget_root, openApp(context))

        val library = TimetableLibrary(context)
        val index = library.readIndex()
        val activeId = index.effectiveActiveId
        val meta = activeId?.let { index.meta(it) }
        if (meta == null) {
            showEmpty(views, PlayfulTalk.line(context, PlayfulTalk.Mood.NO_BOARD))
            manager.updateAppWidget(id, views)
            return
        }

        val content = library.readContent(meta.id)
        val today = LocalDate.now()
        val week = meta.term.weekOf(today).coerceAtLeast(1)
        // 与 TimetableRepository.buildTimetable 等价。此处不复用 Repository：
        // 它的默认构造参数会建一个 OkHttpClient，而小组件只读本地文件，不需要任何网络对象。
        val timetable = Timetable(
            term = meta.term,
            courses = content.courses,
            sessions = content.sessions,
            unplaced = content.unplaced,
            currentWeek = week,
        )

        val schedule = meta.periodSchedule
        val now = LocalTime.now()
        val todaySessions = timetable.sessionsOnDate(today)
        // 已经上完的不显示，只留进行中与未开始的
        val remaining = todaySessions.filter { schedule.statusOf(it, now) != SessionStatus.FINISHED }

        views.setTextViewText(R.id.widget_board_name, meta.name)
        views.setTextViewText(R.id.widget_date, "${today.monthValue}.${today.dayOfMonth}")
        views.setTextViewText(R.id.widget_weekday, DAY_NAMES[today.seuDayOfWeek()])
        views.setTextViewText(R.id.widget_week, context.getString(R.string.widget_week, week))
        views.setViewVisibility(R.id.widget_week, if (showWeek) View.VISIBLE else View.GONE)

        val shown = remaining.take(rowCapacity(manager, id))
        val assigner = CourseColorAssigner(content.courses)
        val dark = isNight(context)
        val ongoingBg = context.getColor(R.color.widget_ongoing_bg)

        for (i in 0 until MAX_ROWS) {
            val visible = i < shown.size
            views.setViewVisibility(ROW_IDS[i], if (visible) View.VISIBLE else View.GONE)
            if (!visible) continue

            val session = shown[i]
            val slot = assigner[session.courseId]
            val bar = CourseBarPalette[slot.mod(CourseBarPalette.size)].toArgb()

            views.setTextViewText(TIME_IDS[i], schedule.beginOf(session.startPeriod)?.toString() ?: "—")
            views.setInt(BAR_IDS[i], "setBackgroundColor", bar)
            views.setTextViewText(
                NAME_IDS[i],
                timetable.course(session.courseId)?.name ?: context.getString(R.string.widget_unknown_course),
            )
            views.setTextColor(NAME_IDS[i], courseTextColor(slot, dark))
            // 进行中的一节高亮底色，便于在列表里一眼定位
            views.setInt(
                ROW_IDS[i],
                "setBackgroundColor",
                if (schedule.statusOf(session, now) == SessionStatus.ONGOING) ongoingBg else Color.Transparent.toArgb(),
            )
            views.setViewVisibility(ROOM_IDS[i], if (showRoom) View.VISIBLE else View.GONE)
            if (showRoom) views.setTextViewText(ROOM_IDS[i], session.room.ifBlank { "—" })
        }

        val hasRows = shown.isNotEmpty()
        views.setViewVisibility(R.id.widget_rows, if (hasRows) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_empty, if (hasRows) View.GONE else View.VISIBLE)
        views.setViewVisibility(R.id.widget_footer, if (hasRows) View.VISIBLE else View.GONE)
        if (hasRows) {
            views.setTextViewText(
                R.id.widget_footer,
                context.getString(R.string.widget_footer_remaining, remaining.size, week),
            )
        } else {
            views.setTextViewText(R.id.widget_empty, emptyLabel(context, today, todaySessions))
        }

        manager.updateAppWidget(id, views)
    }

    /**
     * 空态文案。三种情况不可混为一谈：全天无课、当天课程已上完（区别于"今天没课"）、周末。
     * 正文取自 [PlayfulTalk]，每 30 分钟轮换一条，与今日页同源同时。
     */
    private fun emptyLabel(context: Context, today: LocalDate, allToday: List<CourseSession>): String {
        val mood = when {
            allToday.isNotEmpty() -> PlayfulTalk.Mood.ALL_DONE
            today.seuDayOfWeek() >= 6 -> PlayfulTalk.Mood.WEEKEND
            else -> PlayfulTalk.Mood.NO_CLASS
        }
        return PlayfulTalk.line(context, mood)
    }

    private fun showEmpty(views: RemoteViews, text: String) {
        views.setViewVisibility(R.id.widget_rows, View.GONE)
        views.setViewVisibility(R.id.widget_footer, View.GONE)
        views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
        views.setTextViewText(R.id.widget_empty, text)
    }

    /**
     * 可显示行数。按桌面给出的可用高度换算，未知时保守取 3 行。
     *
     * 用 MIN_HEIGHT 而非 MAX_HEIGHT：竖屏桌面下发的是前者，且它正是"用户看到的实际高度"。
     */
    private fun rowCapacity(manager: AppWidgetManager, id: Int): Int {
        val height = manager.getAppWidgetOptions(id)
            .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        if (height <= 0) return 3
        return ((height - CHROME_HEIGHT_DP) / rowHeightDp).coerceIn(1, MAX_ROWS)
    }

    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 课程名取色，与周网格同一套推导规则（浅色压暗、深色提亮），保证同色可辨 */
    private fun courseTextColor(slot: Int, dark: Boolean): Int {
        val bar = CourseBarPalette[slot.mod(CourseBarPalette.size)]
        return if (dark) {
            lerp(bar, Color.White, 0.58f).toArgb()
        } else {
            lerp(bar, Color.Black, 0.40f).toArgb()
        }
    }

    private fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
}

/**
 * 2×2 基座：时刻与课名一行，教室另起一行。
 *
 * 教室曾经是关掉的（当时的判断是"宽度只够时刻 + 课程名"），但「在哪上课」正是这块小组件
 * 最有用的信息之一。同行的做法实测会把课名压到两三个字，得不偿失，故改为两行。
 */
class TodayWidgetSmall : BaseTodayWidget() {
    override val layoutId = R.layout.widget_today_2x2
    override val showRoom = true
    override val showWeek = false
    override val rowHeightDp = ROW_HEIGHT_DP_SMALL
}

/** 4×2 横幅：教室与课名同行，另加周次。 */
class TodayWidgetWide : BaseTodayWidget() {
    override val layoutId = R.layout.widget_today_4x2
    override val showRoom = true
    override val showWeek = true
}

/**
 * 主动刷新入口。
 *
 * 小组件自带的 updatePeriodMillis 最小只能到 30 分钟，改完课表等它自然刷新太慢，
 * 故在 App 侧数据可能变化后（见 MainActivity.onStop）直接广播一次重绘。
 * 只对已放置的小组件发广播，没放小组件时等于空操作。
 */
object TodayWidget {

    internal val PROVIDERS = listOf(TodayWidgetSmall::class.java, TodayWidgetWide::class.java)

    fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        PROVIDERS.forEach { cls ->
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(context, cls)) }
                .getOrNull()
            if (ids == null || ids.isEmpty()) return@forEach
            val intent = Intent(context, cls).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
        }
    }
}
