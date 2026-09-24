package com.seu.timetable.reminder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.seu.timetable.MainActivity
import com.seu.timetable.R
import com.seu.timetable.util.DebugLog

/**
 * 上课提醒的通知投放。
 *
 * 用平台 API 而非 `NotificationCompat`：minSdk 为 26，`NotificationChannel` 与
 * 「带 channelId 的 Builder」本身就是 26 起的 API，兼容库在这里只多一层包装。
 *
 * 权限：Android 13（API 33）起需 `POST_NOTIFICATIONS`。这里只负责「有权限就发」，
 * 权限申请由设置页负责（见 ReminderPage）——**不在此处静默吞掉失败**，
 * 否则用户会以为提醒开着，却永远收不到。
 */
object ReminderNotifier {

    private const val CHANNEL_ID = "class_reminder"

    /** 固定 id：同一节课的提醒复用一条，不堆满通知栏。 */
    private const val NOTIFICATION_ID = 2001

    /** 建渠道。重复调用是安全的，已存在则直接返回。 */
    fun ensureChannel(context: Context) {
        val manager = manager(context) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.reminder_channel_desc)
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun post(context: Context, title: String, body: String) {
        ensureChannel(context)
        val manager = manager(context) ?: return

        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_reminder_bell)
            .setContentTitle(title)
            .setContentText(body)
            // 课程名可能较长，展开后完整可读
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { DebugLog.w("发通知失败（多半是没拿到通知权限）：${it.message}") }
    }

    private fun manager(context: Context): NotificationManager? =
        runCatching { context.getSystemService(NotificationManager::class.java) }.getOrNull()
}
