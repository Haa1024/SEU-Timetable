package com.seu.timetable.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.seu.timetable.util.DebugLog

/**
 * 接收桌面在「小工具确实被放到桌面上」之后回传的成功回调。
 *
 * 为什么要单独一个接收器：这是整条链路上**唯一由系统背书**的成功信号。
 * [WidgetPin.request] 的返回值只代表「请求被桌面接收」，`isRequestPinAppWidgetSupported`
 * 也可能说谎——实测 ColorOS 15 两者都返回 `true`，桌面却在白名单检查处直接 `finish()`，
 * 既不弹窗也不回调。所以只能等桌面回过头来通知，收不到就按「没加上」处理。
 *
 * 归属校验只做做得到的部分：回调里若带了 `EXTRA_APPWIDGET_ID`，就查一下这个 id 属于谁，
 * 明确属于别的应用才丢弃。**不带这个 extra 时照常记账**——Intent 由本应用创建的
 * PendingIntent 投递、接收器又声明了 `exported="false"`，第三方本就无法伪造，
 * 这里不必也不该再加一道会误伤真回调的关卡。
 */
class WidgetPinResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WidgetPin.ACTION_PINNED) return
        val widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID &&
            !belongsToThisApp(context, widgetId)
        ) {
            DebugLog.i("小组件：成功回调的 appWidgetId=$widgetId 不属于本应用，已忽略")
            return
        }
        DebugLog.i("小组件：桌面回传成功回调（appWidgetId=$widgetId）")
        WidgetPin.onPinnedReported()
    }

    /**
     * 这个 appWidgetId 是否指向本应用的组件。
     *
     * 查不到就**不作否定判断**：回调可能早于实例完成绑定到达，那时 `getAppWidgetInfo`
     * 会返回 null。只有「明确查到属于别家」才算可疑。
     */
    private fun belongsToThisApp(context: Context, widgetId: Int): Boolean = runCatching {
        val info = AppWidgetManager.getInstance(context).getAppWidgetInfo(widgetId)
        info == null || info.provider.packageName == context.packageName
    }.getOrDefault(true)
}
