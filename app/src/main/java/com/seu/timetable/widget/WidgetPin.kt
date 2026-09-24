package com.seu.timetable.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import com.seu.timetable.util.DebugLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 桌面小组件的「一键添加」。
 *
 * 为什么需要它：realme UI / ColorOS 自 14 版起把桌面上的「卡片」与「插件」合并为卡片中心，
 * 所有第三方 App 的小组件统一归入「全部卡片 → 滑到最底 → 插件」这一分组，用户要经过四五步
 * 才能找到。系统为此提供了 [AppWidgetManager.requestPinAppWidget]（API 26 起，与 minSdk 一致）：
 * 由 App 发起请求，桌面直接弹出「是否添加小组件」确认框，从而绕开上述路径。
 *
 * 能力由桌面一侧实现，返回值**不可当作结果**：
 *  - ColorOS 15 实测 `isRequestPinAppWidgetSupported` 与请求返回值都是 `true`，但桌面在显示
 *    确认框之前会做应用白名单检查（`AddItemActivity.isAllowedAddWidget`），不通过就直接
 *    `finish()`——不弹窗、不回调、应用侧无从感知；
 *  - 小米 / 荣耀一类桌面则可能「成功放下却不发回调」。
 *
 * 因此这里把「请求」与「结果」彻底拆开：请求只返回一张 [Ticket]（说明走了哪条路、出发时的
 * 计数基线），结果交给 [awaitAdded] 去等两条**行为信号**之一成立——
 * [pinnedCount]（桌面回传的成功回调，权威）或 `boundCount`（桌面上的实例数，兜底）。
 */
object WidgetPin {

    /** 可添加的两种尺寸，顺序即选择菜单中的展示顺序。 */
    enum class Size(
        val label: String,
        val hint: String,
        val provider: Class<out AppWidgetProvider>,
        /** 目标格子的最小宽高（dp），随请求一起交给桌面作尺寸提示。 */
        val minWidthDp: Int,
        val minHeightDp: Int,
    ) {
        SMALL(
            "今日课程 · 2×2",
            "约四个图标大小，显示时间与课名",
            TodayWidgetSmall::class.java,
            minWidthDp = 110,
            minHeightDp = 110,
        ),
        WIDE(
            "今日课程 · 4×2",
            "横向两格高，另有教室与周次",
            TodayWidgetWide::class.java,
            minWidthDp = 250,
            minHeightDp = 110,
        ),
    }

    /**
     * 这次请求走的是哪条路。它同时决定两件事：要等多久，以及等不到时该说什么。
     *
     * 之所以必须分开，是因为「小米 + 支持详情页」这条路上，用户会被送进小部件中心自己翻找、
     * 自己拖放，耗时可达一两分钟。若把它当确认框、几秒就判失败，用户还在挑的时候就会被告知
     * 「没加上」——比不给提示更糟。
     */
    enum class Route {
        /** 桌面自己声明不支持由应用发起添加，连等都不必等。 */
        UNSUPPORTED,

        /** 桌面会弹一个「是否添加」确认框，用户点一下就完成。 */
        CONFIRM_DIALOG,

        /** 用户已被送进小部件中心 / 组件库，要靠自己翻找与拖放。 */
        BROWSING,
    }

    /**
     * 一次请求的凭证：走了哪条路、选的是哪种尺寸、出发时两个成功信号的基线。
     *
     * 基线必须与请求同一时刻取。若等请求返回后再去读，理论上会漏掉「请求刚发出、桌面立刻
     * 完成绑定并回调」这种极快的情形——基线一旦是「已经被加过之后」的值，本次就永远比不出来了。
     */
    class Ticket internal constructor(
        val route: Route,
        val size: Size,
        internal val boundBaseline: Int,
        internal val pinnedBaseline: Int,
    )

    /** 桌面**真的把小工具放到桌面上**之后回传的广播，由 [WidgetPinResultReceiver] 接收。 */
    const val ACTION_PINNED = "com.seu.timetable.action.WIDGET_PINNED"

    /** 等结果的轮询间隔。桌面侧没有推送实例数变化的机制，只能自己看。 */
    const val POLL_INTERVAL_MS = 500L

    /** 确认框路径的等待上限：正常交互在几秒内完成，超过这个时长基本是没弹或被丢了。 */
    const val CONFIRM_DIALOG_TIMEOUT_MS = 8_000L

    /** 组件中心路径的等待上限：放宽到三分钟，够用户翻到列表并拖出来。 */
    const val BROWSING_TIMEOUT_MS = 180_000L

    /** 小米文档化的 extras 键：决定「打开详情页」还是走标准确认框。 */
    private const val KEY_ADD_TYPE = "addType"

    /** [KEY_ADD_TYPE] 的取值：打开小部件中心里本应用的详情页。 */
    private const val ADD_TYPE_WIDGET_CENTER_DETAIL = "appWidgetDetail"

    /** 小米文档化的 extras 键：指定详情页定位到哪个组件。 */
    private const val KEY_WIDGET_NAME = "widgetName"

    /** 成功回调的 PendingIntent 标识；固定不变才能稳定复用同一个。 */
    private const val REQUEST_CODE_CALLBACK = 6207

    /** vivo 桌面（原子组件库）的包名。 */
    private const val VIVO_LAUNCHER_PACKAGE = "com.bbk.launcher2"

    private val _pinnedCount = MutableStateFlow(0)

    /**
     * 成功回调的**累计次数**。
     *
     * 存计数而不是一次性布尔：调用方在请求前记下当前值，之后比较「是否变大」即可判断本次是否
     * 成功，于是上一轮遗留的旧回调会自然失效，不必额外清状态。
     */
    val pinnedCount: StateFlow<Int> = _pinnedCount.asStateFlow()

    /** 当前桌面是否支持由 App 主动发起添加。它只用来短路请求，**不是**能力结论。 */
    fun isSupported(context: Context): Boolean =
        AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported

    /** 该尺寸当前已绑定到桌面上的实例数。轮询它的变化即可判断「到底加上没有」。 */
    fun boundCount(context: Context, size: Size): Int =
        runCatching {
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, size.provider))
                .size
        }.getOrDefault(0)

    /**
     * 请求把 [size] 添加到桌面，返回本次的 [Ticket]。
     *
     * 这个方法**只负责把请求发出去**，成功与否一律由 [awaitAdded] 判定：返回值只表示「请求已被
     * 桌面接收」，[Route.UNSUPPORTED] 才是唯一可以当场下结论的情形。
     */
    fun request(context: Context, size: Size): Ticket {
        val manager = AppWidgetManager.getInstance(context)
        // 两个基线必须在这里取，理由见 Ticket 的注释。
        val bound = boundCount(context, size)
        val pinned = _pinnedCount.value

        if (!manager.isRequestPinAppWidgetSupported) {
            DebugLog.i("小组件：桌面声明不支持应用发起添加 ${size.label} → 直接给手动引导")
            return Ticket(Route.UNSUPPORTED, size, bound, pinned)
        }

        val useDetailPage = shouldUseDetailPage(context)
        val accepted = runCatching {
            manager.requestPinAppWidget(
                ComponentName(context, size.provider),
                buildExtras(context, size, useDetailPage),
                successCallback(context),
            )
        }.getOrDefault(false)

        if (!accepted) {
            DebugLog.i("小组件：桌面拒绝了添加请求 ${size.label} → 直接给手动引导")
            return Ticket(Route.UNSUPPORTED, size, bound, pinned)
        }

        val route = if (useDetailPage) Route.BROWSING else Route.CONFIRM_DIALOG
        DebugLog.i("小组件：添加请求已被桌面接收 ${size.label} route=$route（不代表会加上）")
        return Ticket(route, size, bound, pinned)
    }

    /**
     * 等「到底加上没有」，两个信号谁先成立算谁：
     *  - [pinnedCount] 比基线大：桌面回传了成功回调，权威结论，不必再等；
     *  - 实例数比基线大：桌面上的确多了一个，兜底用于那些不发回调的桌面。
     *
     * @return 真表示已添加；假表示等满了本次路线允许的时长仍无任何信号。
     */
    suspend fun awaitAdded(context: Context, ticket: Ticket): Boolean {
        if (ticket.route == Route.UNSUPPORTED) return false
        val timeout = if (ticket.route == Route.BROWSING) {
            BROWSING_TIMEOUT_MS
        } else {
            CONFIRM_DIALOG_TIMEOUT_MS
        }
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(POLL_INTERVAL_MS)
            // 先看回调：它是桌面的明确表态，比数实例更可靠，也能避开查询实例数的那次跨进程调用。
            if (_pinnedCount.value > ticket.pinnedBaseline) {
                DebugLog.i("小组件：收到桌面成功回调 → 判定已添加")
                return true
            }
            if (boundCount(context, ticket.size) > ticket.boundBaseline) {
                DebugLog.i("小组件：桌面实例数增加 → 判定已添加")
                return true
            }
        }
        return false
    }

    /**
     * vivo 原子组件库的跳转入口。
     *
     * vivo 的桌面要求应用接入它的原子组件平台才能由应用发起添加，未接入时 pin 完全无效
     * （社区与官方适配指南口径一致）。既然无法由应用添加，至少把用户直接送到组件库，
     * 省掉「自己翻到那一页」的过程。
     *
     * @return 可解析的跳转 Intent；非 vivo 或对方没有声明该入口时返回 `null`（调用方据此不显示按钮）。
     */
    fun galleryIntent(context: Context, size: Size): Intent? {
        val uri = Uri.parse(galleryUri(context.packageName, size.provider.name))
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // targetSdk 30 起，未在 <queries> 里声明可见性的包连解析都看不见，
        // 故 manifest 里对 com.bbk.launcher2 声明了可见性，否则这里永远是 null。
        val resolvable = runCatching {
            context.packageManager.resolveActivity(intent, 0) != null
        }.getOrDefault(false)
        if (!resolvable) {
            DebugLog.i("小组件：vivo 组件库入口不可解析 → 不显示跳转按钮")
            return null
        }
        return intent
    }

    /**
     * 拼 vivo 组件库跳转 URI 的**唯一**出处。
     *
     * 两处有意为之：
     *  1. **不做 URL 编码**：包名与类名都只含字母数字与点下划线，编码反而可能让对方解析失败；
     *  2. **参数名取 `comType`**：官方文档同一节正文写 `cmpType`、示例代码写 `comType`，自相矛盾。
     *     这里依示例代码取值，并收敛到这一个函数，真机若确认应为 `cmpType` 只改这一处。
     */
    fun galleryUri(packageName: String, providerClassName: String): String =
        "vivo://$VIVO_LAUNCHER_PACKAGE/origin" +
            "?pkg=$packageName&classname=$providerClassName&comType=0&locType=1"

    /** 记一次桌面回传的成功（由 [WidgetPinResultReceiver] 校验归属后调用）。 */
    internal fun onPinnedReported() {
        _pinnedCount.value += 1
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 这次要不要带小米那组「打开详情页」的 extras。
     *
     * 判定收敛在 [WidgetVendor.usesDetailPageExtras] 一处，这里只负责把三个输入凑齐。
     */
    private fun shouldUseDetailPage(context: Context): Boolean {
        val vendor = WidgetVendor.current()
        return WidgetVendor.usesDetailPageExtras(
            vendor = vendor,
            modern = WidgetVendor.isModern(Build.VERSION.SDK_INT),
            detailPageSupported = WidgetVendorProbe.detailPageSupported(context, vendor),
        )
    }

    /**
     * 本次请求要带的 extras。
     *
     * 尺寸键**恒定带上**：`OPTION_APPWIDGET_MIN_WIDTH/HEIGHT` 是尽力而为的尺寸提示，
     * 与厂商无关。小米那组只在 [shouldUseDetailPage] 为真时追加。
     */
    private fun buildExtras(context: Context, size: Size, withDetailPage: Boolean): Bundle =
        Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, size.minWidthDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, size.minHeightDp)
            if (withDetailPage) {
                putString(KEY_ADD_TYPE, ADD_TYPE_WIDGET_CENTER_DETAIL)
                putString(KEY_WIDGET_NAME, "${context.packageName}/${size.provider.name}")
            }
        }

    /** 桌面放下小工具后要发送的广播。显式指向本应用的接收器，不留别的应用可乘之机。 */
    private fun successCallback(context: Context): PendingIntent {
        val intent = Intent(context, WidgetPinResultReceiver::class.java).setAction(ACTION_PINNED)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_CALLBACK,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
