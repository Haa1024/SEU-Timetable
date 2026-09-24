package com.seu.timetable.ui.pages

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.seu.timetable.BuildConfig
import com.seu.timetable.ui.components.ActionMenuDialog
import com.seu.timetable.ui.components.DangerButton
import com.seu.timetable.ui.components.MenuAction
import com.seu.timetable.ui.components.MessageDialog
import com.seu.timetable.ui.components.PersonIcon
import com.seu.timetable.ui.components.RowDivider
import com.seu.timetable.ui.components.SectionLabel
import com.seu.timetable.ui.components.Segmented
import com.seu.timetable.ui.components.SeuCard
import com.seu.timetable.ui.components.SettingRow
import com.seu.timetable.ui.theme.LocalSeuColors
import com.seu.timetable.ui.theme.LocalSeuType
import com.seu.timetable.ui.theme.ThemeMode
import com.seu.timetable.update.UpdateFlow
import com.seu.timetable.util.DebugLog
import com.seu.timetable.widget.WidgetGuidance
import com.seu.timetable.widget.WidgetPin
import com.seu.timetable.widget.WidgetVendor
import kotlinx.coroutines.launch

/**
 * 页面 05 · 我的 / 设置（规格 4.5）。
 *
 * 「校园账号」一行的副标题由 [accountSubtitle] 按实际存储情况拼出。
 * 设置页允许用户主动保存凭据以换取登录态自动续期，因此该行需如实反映状态。
 *
 * 登录相关的入口只有一个：设置页的「校园账号」，登录动作与凭据管理都在那一页里。
 * 早先这里另有一行「重新登录」，但它与「校园账号」解决的是同一件事——用户面对
 * 两个都指向"登录"的入口，只会犹豫该点哪个。合并后概念单一：想登录就进「校园账号」。
 *
 * 课表改为本地资产后，「自动同步」开关与「上次同步」入口已移除，
 * 相应位置改为「我的课表」：用户在这里切换课表、导入新课表、修改作息。
 * 服务端会话仅剩一个用途——导入与手动同步时下载数据。
 *
 * 保留「从教务同步当前课表」这一显式入口：若完全没有同步能力，
 * 用户退课或改课后只能删除整张课表重新导入，自行调整过的颜色与备注会一并丢失。
 */
@Composable
fun ProfilePage(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    accountSubtitle: String,
    onOpenAccount: () -> Unit,
    /**
     * 校园登录态。null 表示仍在检查。
     *
     * 之所以传三态而非一个字符串：除了显示文案，它还要决定「退出登录」整块是否出现
     * （见 [onSignOut]）。若只传字符串，界面就得靠比对文案来判断状态，那是最脆的做法。
     */
    sessionOk: Boolean?,
    boardCount: Int,
    /** 当前课表摘要，例如「2026-2027学年秋季学期 · 6 门课」 */
    activeBoardSummary: String,
    onOpenBoards: () -> Unit,
    lastImportLabel: String,
    /** 当前课表能否手动同步。不能（自建课表）时该行需说明原因 */
    syncSubtitle: String,
    onSyncCurrent: () -> Unit,
    courseCount: Int,
    sessionCount: Int,
    unplacedCount: Int,
    /**
     * 退出登录。**仅在 [sessionOk] 为 true 时界面才提供入口**。
     *
     * 原先这个按钮是无条件渲染的，导致退出成功后按钮仍留在原位——界面显示的
     * 状态与真实状态不一致，用户会以为没退成功而反复点击。现改为跟随登录态。
     */
    onSignOut: () -> Unit,
    /** 打开静态使用说明页 */
    onOpenHelp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalSeuColors.current
    val t = LocalSeuType.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 一键添加的两步状态：先弹尺寸选择；桌面拒绝或不响应时再弹手动添加的图文引导。
    // 引导里要说明「你刚才选的是哪种尺寸」——vivo 的组件库跳转需要它定位到对应组件，
    // 故存整张凭证而不是一个布尔量。
    var pinMenuOpen by remember { mutableStateOf(false) }
    var pinFailure by remember { mutableStateOf<WidgetPin.Ticket?>(null) }
    // 厂商只用来挑文案与入口，不用来判断「这台机器行不行」——那是行为探测的事。
    val pinVendor = remember { WidgetVendor.current() }

    // 检查更新：不自动触发，用户点了才走。更新检查要联网，
    // 进设置页就自动请求会在用户毫无预期时消耗流量，也拖慢首帧。
    var updateStart by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Text("我的", style = t.pageTitle, color = c.textPrimary)
        Spacer(Modifier.height(16.dp))

        // ---- ProfileCard ----
        SeuCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(c.primarySoft),
                    contentAlignment = Alignment.Center,
                ) {
                    PersonIcon(c.primary, 26.dp)
                }
                Spacer(Modifier.size(14.dp))
                Column {
                    Text("东南大学", style = t.navTitle, color = c.textPrimary)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        when (sessionOk) {
                            true -> "校园登录态有效"
                            // 本地课表模式下"未登录"完全正常，文案不制造焦虑
                            false -> "未登录校园账号（仅在导入课表时需要）"
                            null -> "正在检查登录态…"
                        },
                        style = t.caption,
                        color = c.textSecondary,
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("课表数据")
        Spacer(Modifier.height(8.dp))
        SeuCard(Modifier.fillMaxWidth(), padding = 0.dp) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                SettingRow(
                    title = "我的课表",
                    subtitle = "$boardCount 张 · 当前 $activeBoardSummary",
                    onClick = onOpenBoards,
                )
                RowDivider()
                SettingRow(
                    title = "校园账号",
                    subtitle = accountSubtitle,
                    onClick = onOpenAccount,
                )
                RowDivider()
                SettingRow(
                    title = "从教务同步当前课表",
                    subtitle = syncSubtitle,
                    onClick = onSyncCurrent,
                )
                RowDivider()
                SettingRow(
                    title = "上次导入",
                    subtitle = lastImportLabel,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("当前课表统计")
        Spacer(Modifier.height(8.dp))
        SeuCard(Modifier.fillMaxWidth(), padding = 0.dp) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                SettingRow(title = "课程", subtitle = "$courseCount 门")
                RowDivider()
                SettingRow(title = "时间块", subtitle = "$sessionCount 个（含同课多时段）")
                RowDivider()
                SettingRow(title = "未排课", subtitle = "$unplacedCount 门")
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("外观")
        Spacer(Modifier.height(8.dp))
        SeuCard(Modifier.fillMaxWidth(), padding = 0.dp) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                // 主题的三个选项在「标题在左、选择器在右」的窄行里放不下：
                // 选择器只能分到约 62% 的宽度，三段各约 59dp，而「跟随系统」需要约 68dp，
                // 于是折成两行、又被轨道的圆角裁掉。改为上下排布，三段各得约 96dp。
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 11.dp)
                ) {
                    Text("主题", style = t.body, color = c.textPrimary)
                    Spacer(Modifier.height(10.dp))
                    Segmented(
                        options = ThemeMode.entries,
                        selected = themeMode,
                        label = {
                            when (it) {
                                ThemeMode.SYSTEM -> "跟随系统"
                                ThemeMode.LIGHT -> "浅色"
                                ThemeMode.DARK -> "深色"
                            }
                        },
                        onSelect = onThemeModeChange,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(11.dp))
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("桌面小组件")
        Spacer(Modifier.height(8.dp))
        SeuCard(Modifier.fillMaxWidth(), padding = 0.dp) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                SettingRow(
                    title = "添加到桌面",
                    subtitle = "不用打开 App 就能看见今天上什么，已上完的课会自动隐藏",
                    onClick = { pinMenuOpen = true },
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("帮助")
        Spacer(Modifier.height(8.dp))
        SeuCard(Modifier.fillMaxWidth(), padding = 0.dp) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                SettingRow(
                    title = "使用帮助",
                    subtitle = "课表来源、登录与同步、界面操作与常见问题",
                    onClick = onOpenHelp,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("关于")
        Spacer(Modifier.height(8.dp))
        SeuCard(Modifier.fillMaxWidth(), padding = 0.dp) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                // 版本号取自 BuildConfig，与 build.gradle.kts 里的定义同源；
                // 更新检查用的也是这个值，不会出现「界面显示一个版本、比较用另一个」的情况。
                SettingRow(
                    title = "版本",
                    subtitle = "${BuildConfig.VERSION_NAME} （${BuildConfig.VERSION_CODE}）",
                )
                RowDivider()
                SettingRow(
                    title = "检查更新",
                    subtitle = "从发布页获取最新版本，装上即可覆盖更新，课表数据不受影响",
                    onClick = { updateStart = true },
                )
                RowDivider()
                SettingRow(title = "数据来源", subtitle = "ehall 教务系统 · 导入后存在本机")
                RowDivider()
                SettingRow(title = "第三方库", subtitle = "OkHttp · kotlinx.serialization · Compose")
            }
        }

        // 退出登录只在**确实已登录**时出现。
        // 判定用 sessionOk == true 而非 != false：检查中（null）时不该给一个退出按钮，
        // 那时点它是无意义的操作，而且会话可能刚被别处清掉，会出现「退出一个不存在的会话」。
        if (sessionOk == true) {
            Spacer(Modifier.height(20.dp))
            DangerButton(
                text = "退出登录",
                onClick = onSignOut,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "退出仅清除校园登录态，已下载到本机的课表不会被删除，" +
                    "离线状态下仍可正常使用。自动登录将一并关闭（保存的账号密码仍保留）。",
                style = t.caption,
                color = c.textTertiary,
            )
        }
        Spacer(Modifier.height(24.dp))
    }

    if (pinMenuOpen) {
        ActionMenuDialog(
            title = "添加到桌面",
            subtitle = "选择一种尺寸。加好后可长按拖动位置，也可拖动边缘改大小。",
            actions = WidgetPin.Size.entries.map { size ->
                MenuAction(label = size.label, hint = size.hint) {
                    pinMenuOpen = false
                    val ticket = WidgetPin.request(context, size)
                    if (ticket.route == WidgetPin.Route.UNSUPPORTED) {
                        pinFailure = ticket
                        return@MenuAction
                    }
                    // 其余两条路都必须等：request 的返回值只代表桌面收下了请求。
                    // 会不会真加上，只有两个行为信号说了算（桌面回传的回调 / 桌面实例数），
                    // 见 WidgetPin.awaitAdded。ColorOS 就是「两个都返回 true 却静默丢弃」的典型。
                    scope.launch {
                        when {
                            WidgetPin.awaitAdded(context, ticket) ->
                                Toast.makeText(context, "已添加到桌面", Toast.LENGTH_SHORT).show()

                            ticket.route == WidgetPin.Route.BROWSING ->
                                // 这条路是把用户送进小部件中心自己翻、自己拖，多半还没弄完。
                                // 此时弹「没加上」既不准也帮不上忙，只记日志。
                                DebugLog.i("小组件：组件中心路径未等到成功信号（用户可能尚未完成添加）")

                            else -> {
                                DebugLog.i("小组件：等到超时仍无成功信号 → 转手动引导")
                                pinFailure = ticket
                            }
                        }
                    }
                }
            },
            onDismiss = { pinMenuOpen = false },
        )
    }

    // 更新流程整体交给 UpdateFlow：设置页不必理解「检查→下载→授权→安装」四个阶段，
    // 只负责把「用户点了检查更新」这个意图传进去。
    UpdateFlow(start = updateStart, onFinished = { updateStart = false })

    pinFailure?.let { ticket ->
        // vivo 的原子组件库入口。对方没声明这个入口时 gallery 为 null，按钮随之消失，
        // 而不是留一个点了没反应的按钮。
        val gallery: Intent? =
            if (pinVendor.showsGalleryJump) WidgetPin.galleryIntent(context, ticket.size) else null
        MessageDialog(
            title = "桌面上还没有加上小组件",
            body = WidgetGuidance.manualBody(pinVendor),
            actionText = if (gallery != null) WidgetGuidance.GALLERY_ACTION else null,
            onAction = if (gallery != null) ({ context.startActivity(gallery) }) else null,
            onDismiss = { pinFailure = null },
        )
    }
}
