package com.seu.timetable.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 闹钟到点，以及"该重排了"的系统广播。
 *
 * 闹钟响过之后**接着排下一个**：全应用只维护一个待响闹钟（见 [ReminderScheduler]），
 * 靠这种接力跨越每一天、每一周，不必预先把整学期的课铺成闹钟。
 *
 * 重排一律走 `goAsync()` + 协程：`onReceive` 只给 10 秒，而重排要读本机课表文件；
 * 漏掉 `finish()` 会被判 ANR。
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 开机、改时间、改时区、跨日、应用被覆盖安装 —— 这些都会让已排的闹钟失效或错位。
        // RTC 闹钟存的是绝对毫秒数，改时区后原来的时刻就不再对应"上课前 15 分钟"。
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED,
            -> {
                rescheduleAsync(context)
                return
            }
        }

        if (intent.action != ACTION_REMIND) return

        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
        // 文案在排闹钟时就算好了：Notification 在接收器里只做投放，不做业务计算。
        if (title.isNotBlank()) ReminderNotifier.post(context, title, body)

        rescheduleAsync(context)
    }

    private fun rescheduleAsync(context: Context) {
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ReminderScheduler.reschedule(appContext)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** 闹钟用的 action。与 [ReminderScheduler] 里构造 PendingIntent 时保持一致。 */
        const val ACTION_REMIND = "com.seu.timetable.action.CLASS_REMIND"

        const val EXTRA_TITLE = "extra_reminder_title"
        const val EXTRA_BODY = "extra_reminder_body"
    }
}
