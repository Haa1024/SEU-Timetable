package com.seu.timetable.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.seu.timetable.R
import com.seu.timetable.data.SettingsStore
import com.seu.timetable.data.TimetableLibrary
import com.seu.timetable.domain.Timetable
import com.seu.timetable.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 上课提醒的排程。
 *
 * **一次只排一个闹钟**——就是"下一个该提醒的时刻"。响过之后由 [ReminderReceiver] 接着排下一个。
 * 好处是不必预先把两周的课铺成闹钟：调课、改作息、换课表都只影响"下一个"，
 * 不会留下一串已经过期的闹钟（那类残留最难排查，因为它平时不响，只在某个奇怪的日子响一次）。
 *
 * 重排时机全部汇到 [reschedule] 一处：
 *   ① 开关或提前量改变；② 进入提醒设置页；③ App 启动；④ 开机 / 改时间 / 改时区 / 跨日 / 覆盖安装。
 *
 * 精确性：Android 12 起精确闹钟需要系统授权。未授权时退化为"大致准点"——
 * 宁可晚几分钟，也不能因为缺权限就彻底不响（见 [canScheduleExact]）。
 */
object ReminderScheduler {

    /** 闹钟与 PendingIntent 的固定标识。取消靠它匹配，故不可随意变动。 */
    private const val REQUEST_CODE = 4731

    /** 往后看多少天。更远的课等下次重排——排得太满，一次调课就会让它们全部作废。 */
    private const val LOOKAHEAD_DAYS = 14

    /** 下一个该提醒的时刻。 */
    data class Next(
        val triggerAtMillis: Long,
        val title: String,
        val body: String,
        /** 供设置页展示，形如「明天 07:45」 */
        val whenLabel: String,
    )

    /**
     * 按当前设置与课表重排闹钟。读本机文件与 DataStore，故自行切到 IO。
     *
     * 关闭时、或近 [LOOKAHEAD_DAYS] 天没有课可提醒时，取消已排的闹钟——
     * 「关掉开关却仍响一次」比不响更糟。
     */
    suspend fun reschedule(context: Context) = withContext(Dispatchers.IO) {
        val settings = SettingsStore(context)
        if (!settings.reminderEnabled.first()) {
            cancel(context)
            return@withContext
        }
        val minutes = settings.reminderMinutes.first()
        val next = nextReminder(context, minutes)
        if (next == null) {
            DebugLog.i("提醒：近 $LOOKAHEAD_DAYS 天没有可提醒的课 → 不排闹钟")
            cancel(context)
            return@withContext
        }
        setAlarm(context, next)
    }

    /** 供设置页展示「下一次提醒」。只算不排，故不影响闹钟。 */
    suspend fun describeNext(context: Context): String = withContext(Dispatchers.IO) {
        val settings = SettingsStore(context)
        if (!settings.reminderEnabled.first()) return@withContext "未开启"
        val next = nextReminder(context, settings.reminderMinutes.first())
        if (next == null) {
            "近 $LOOKAHEAD_DAYS 天没有课，无需提醒"
        } else {
            "${next.whenLabel} · ${next.title}"
        }
    }

    /** 当前是否拿到精确闹钟权限。Android 12 起才需要授权；更低版本一律视为有。 */
    fun canScheduleExact(context: Context): Boolean {
        val manager = alarmManager(context) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    /**
     * 立即发一条测试通知，让用户在开启前就能看到提醒长什么样。
     * 内容优先取「下一节真实要提醒的课」；近两周无课时退化为固定示例，样式完全一致。
     */
    suspend fun testNotify(context: Context) = withContext(Dispatchers.IO) {
        val settings = SettingsStore(context)
        val next = nextReminder(context, settings.reminderMinutes.first())
        if (next != null) {
            ReminderNotifier.post(context, next.title, next.body)
        } else {
            ReminderNotifier.post(
                context,
                context.getString(R.string.reminder_sample_title),
                context.getString(R.string.reminder_sample_body),
            )
        }
    }

    // ---------------------------------------------------------------- 内部

    private fun alarmManager(context: Context): AlarmManager? =
        runCatching { context.getSystemService(AlarmManager::class.java) }.getOrNull()

    /**
     * 构造 PendingIntent。
     *
     * `next == null` 时用于取消：`AlarmManager.cancel` 只按 (组件, action, requestCode) 匹配，
     * extras 不参与，故用同一个构造入口即可。
     */
    private fun pendingIntent(context: Context, next: Next?): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_REMIND
            if (next != null) {
                putExtra(ReminderReceiver.EXTRA_TITLE, next.title)
                putExtra(ReminderReceiver.EXTRA_BODY, next.body)
            }
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancel(context: Context) {
        alarmManager(context)?.cancel(pendingIntent(context, null))
    }

    private fun setAlarm(context: Context, next: Next) {
        val manager = alarmManager(context) ?: return
        val pending = pendingIntent(context, next)
        val exact = canScheduleExact(context)
        runCatching {
            if (exact) {
                // 用 AllowWhileIdle：Doze 下普通 set() 会被推迟到下一个维护窗口，
                // 对"上课前 15 分钟"这种时点而言等于失效。
                manager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    next.triggerAtMillis,
                    pending,
                )
            } else {
                manager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    next.triggerAtMillis,
                    pending,
                )
            }
        }.onSuccess {
            DebugLog.i("提醒已排：${next.whenLabel}（${if (exact) "精确" else "非精确"}）· ${next.title}")
        }.onFailure {
            DebugLog.w("排闹钟失败：${it.message}")
        }
    }

    /** 算出"下一个该提醒的时刻"。近 [LOOKAHEAD_DAYS] 天都没课则返回 null。 */
    private suspend fun nextReminder(context: Context, minutes: Int): Next? {
        val library = TimetableLibrary(context)
        val index = library.readIndex()
        val activeId = index.effectiveActiveId ?: return null
        val meta = index.meta(activeId) ?: return null
        val content = library.readContent(meta.id)

        val today = LocalDate.now()
        // 与 TimetableRepository.buildTimetable 等价。这里不复用 Repository：
        // 它的默认构造参数会建一个 OkHttpClient，而提醒只读本地文件，不需要网络对象。
        val timetable = Timetable(
            term = meta.term,
            courses = content.courses,
            sessions = content.sessions,
            unplaced = content.unplaced,
            currentWeek = meta.term.weekOf(today).coerceAtLeast(1),
        )
        val schedule = meta.periodSchedule
        val now = LocalDateTime.now()

        for (offset in 0 until LOOKAHEAD_DAYS) {
            val date = today.plusDays(offset.toLong())
            // sessionsOnDate 已按「星期 + 起始节次」升序返回，故当天第一个还没过提醒点的就是最近的一节。
            // 周次过滤也在其中完成（含放假周的判断），本节无须重复。
            for (session in timetable.sessionsOnDate(date)) {
                val begin = schedule.beginOf(session.startPeriod) ?: continue
                val remindAt = date.atTime(begin).minusMinutes(minutes.toLong())
                if (!remindAt.isAfter(now)) continue

                val name = timetable.course(session.courseId)?.name
                    ?: context.getString(R.string.reminder_unknown_course)
                val title = context.getString(R.string.reminder_title, name, session.periodLabel())
                // 文案在排闹钟时就算好。用「提前量」而不是"此刻距上课还有多久"——
                // 通知是在 remindAt **那一刻**发的，届时距上课正好是提前量。
                val body = if (session.room.isBlank()) {
                    context.getString(R.string.reminder_body, begin.toString(), minutes)
                } else {
                    context.getString(R.string.reminder_body_room, begin.toString(), minutes, session.room)
                }
                return Next(
                    triggerAtMillis = remindAt
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli(),
                    title = title,
                    body = body,
                    whenLabel = whenLabel(date, begin.toString()),
                )
            }
        }
        return null
    }

    /** 「今天 13:55」「明天 07:45」「3 月 5 日 07:45」 */
    private fun whenLabel(date: LocalDate, time: String): String =
        when (ChronoUnit.DAYS.between(LocalDate.now(), date)) {
            0L -> "今天 $time"
            1L -> "明天 $time"
            else -> "${date.monthValue} 月 ${date.dayOfMonth} 日 $time"
        }
}
