package com.seu.timetable.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.seu.timetable.domain.BoardIndex
import com.seu.timetable.domain.BoardMeta
import com.seu.timetable.domain.BoardSource
import com.seu.timetable.ui.components.ActionMenuDialog
import com.seu.timetable.ui.components.BackIcon
import com.seu.timetable.ui.components.ConfirmDialog
import com.seu.timetable.ui.components.MenuAction
import com.seu.timetable.ui.components.MoreIcon
import com.seu.timetable.ui.components.PrimaryButton
import com.seu.timetable.ui.components.SectionLabel
import com.seu.timetable.ui.components.SeuCard
import com.seu.timetable.ui.components.TextInputDialog
import com.seu.timetable.ui.theme.LocalSeuColors
import com.seu.timetable.ui.theme.LocalSeuType
import com.seu.timetable.ui.theme.SeuRadius

/**
 * 页面 · 我的课表（本地课表库）。
 *
 * 这是本次「课表本地化」的总入口：
 *  - **切换**：点一行就把 [onActivate] 发出去，回课表页看到的就是那一张；
 *  - **复制**：做一份独立副本，可以拿它去改出"下学期"的版本而不动原表；
 *  - **课表设置**：每张课表有自己的学期骨架与作息；
 *  - **从教务同步**：**只有用户主动点才会去教务拉**（见 [onSync]）；
 *  - **删除**：本地数据，删了就没了，所以走 [ConfirmDialog] 兜一道。
 *
 * [courseCounts] 由外部（AppRoot）读取后传入，而非本页自行读盘：
 *   如此本页为纯展示，无需 `suspend` 环境，亦无需处理 IO 调度。
 */
@Composable
fun BoardsPage(
    index: BoardIndex,
    courseCounts: Map<String, Int>,
    onBack: () -> Unit,
    onActivate: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDuplicate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onEditSchedule: (String) -> Unit,
    onSync: (String) -> Unit,
    onNewBoard: () -> Unit,
) {
    val c = LocalSeuColors.current
    val t = LocalSeuType.current

    var menuFor by remember { mutableStateOf<BoardMeta?>(null) }
    var renameFor by remember { mutableStateOf<BoardMeta?>(null) }
    var deleteFor by remember { mutableStateOf<BoardMeta?>(null) }

    val activeId = index.effectiveActiveId

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        // ---- NavBar ----
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(28.dp).clip(CircleShape).clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) { BackIcon(c.textPrimary, 18.dp) }
            Spacer(Modifier.weight(1f))
            Text("我的课表", style = t.navTitle, color = c.textPrimary)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(28.dp))
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(6.dp))

            // 说明这条是**必要**的：用户上一版的心智模型是"这份课表是教务的"，
            // 现在它变成了一份可以自己增删、可以有好几张的本地数据，得说清楚。
            SeuCard(Modifier.fillMaxWidth()) {
                Column {
                    Text("课表存在本机", style = t.itemTitle, color = c.textPrimary)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "登录校园账号仅用于把课程数据下载到本地。下载完成后可离线使用，" +
                            "也可以只保留这一张而不再使用教务。可存放多个学期的课表，也可自行新建空白课表。",
                        style = t.caption,
                        color = c.textSecondary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "课表不会自动与教务同步。在这里或课表页所做的修改都只保存在本机，" +
                            "并会长期保留。需要更新时，请在对应课表的菜单里使用「从教务同步」主动拉取一次。",
                        style = t.caption,
                        color = c.textTertiary,
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            if (index.boards.isEmpty()) {
                SectionLabel("还没有课表")
                Spacer(Modifier.height(10.dp))
                SeuCard(Modifier.fillMaxWidth()) {
                    Text(
                        "点击下方按钮，从教务导入一个学期的课表，或新建一张空白课表自行编排。",
                        style = t.body,
                        color = c.textSecondary,
                    )
                }
            } else {
                SectionLabel("全部课表 · ${index.boards.size} 张")
                Spacer(Modifier.height(10.dp))
                index.boards.forEach { board ->
                    BoardRow(
                        meta = board,
                        courseCount = courseCounts[board.id] ?: 0,
                        active = board.id == activeId,
                        onOpen = { onActivate(board.id) },
                        onMore = { menuFor = board },
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }

            Spacer(Modifier.height(8.dp))
            PrimaryButton(
                text = "导入 / 新建课表",
                onClick = onNewBoard,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(28.dp))
        }
    }

    // ---- 弹层 ----
    menuFor?.let { board ->
        ActionMenuDialog(
            title = board.name,
            subtitle = board.subtitleOf(courseCounts[board.id] ?: 0),
            // 用 buildList 而非 listOf：「从教务同步」是条件项，而 `listOf(a, if (cond) b, c)`
            // 中无 else 的 if 返回 Unit，会把 Unit 放进 List<MenuAction> 导致编译失败。
            actions = buildList {
                add(
                    MenuAction(
                        label = "设为当前课表",
                        hint = if (board.id == activeId) "已经是当前课表" else "回到课表页并显示这一张",
                        onClick = { onActivate(board.id) },
                    )
                )
                add(
                    MenuAction(
                        label = "重命名",
                        onClick = { renameFor = board },
                    )
                )
                add(
                    MenuAction(
                        label = "复制一份",
                        hint = "生成一张独立副本，修改副本不影响原课表",
                        onClick = { onDuplicate(board.id) },
                    )
                )
                add(
                    MenuAction(
                        label = "课表设置",
                        hint = when {
                            board.source == BoardSource.MANUAL ->
                                "学期起始日 / 周数 / 节次分组 / ${board.scheduleLabel}"
                            else ->
                                "作息时间（学期信息来自教务，只读）· ${board.scheduleLabel}"
                        },
                        onClick = { onEditSchedule(board.id) },
                    )
                )
                // 手动同步：「课表不会自己变」的配套出口。缺少它则教务侧的退课 / 换老师
                // 永远拉不回来，而自动同步又会静默冲掉用户改动，故保留显式按钮并在点击前确认。
                if (board.isSyncable) {
                    add(
                        MenuAction(
                            label = "从教务同步",
                            hint = "重新拉取该学期数据，课程以教务为准；" +
                                "颜色 / 学分 / 备注会保留。不会自动同步，仅在点击后执行",
                            onClick = { onSync(board.id) },
                        )
                    )
                }
                add(
                    MenuAction(
                        label = "删除",
                        hint = "本地数据，删除后无法恢复",
                        danger = true,
                        onClick = { deleteFor = board },
                    )
                )
            },
            onDismiss = { menuFor = null },
        )
    }

    renameFor?.let { board ->
        TextInputDialog(
            title = "重命名课表",
            initialValue = board.name,
            placeholder = "例如 2026 秋 · 大三上",
            confirmText = "保存",
            onConfirm = { newName ->
                // 先关弹层：TextInputDialog 只负责收集文本、不会自行关闭，
                // 不关则重命名完成后输入框仍盖在列表上，用户会以为未生效。
                renameFor = null
                if (newName.isNotEmpty()) onRename(board.id, newName)
            },
            onDismiss = { renameFor = null },
        )
    }

    deleteFor?.let { board ->
        ConfirmDialog(
            title = "删除「${board.name}」？",
            body = "会把这张课表连同里面的课程一起从本机删掉，无法撤销。" +
                "如仅需更改名称，请使用「重命名」。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                // 同上：删除后必须关闭确认框，否则课表已从列表消失而弹层仍停留在确认文案。
                deleteFor = null
                onDelete(board.id)
            },
            onDismiss = { deleteFor = null },
        )
    }
}

@Composable
private fun BoardRow(
    meta: BoardMeta,
    courseCount: Int,
    active: Boolean,
    onOpen: () -> Unit,
    onMore: () -> Unit,
) {
    val c = LocalSeuColors.current
    val t = LocalSeuType.current

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SeuRadius.listRow))
            .background(c.surface)
            .border(
                if (active) 1.5.dp else 1.dp,
                if (active) c.primary else c.border,
                RoundedCornerShape(SeuRadius.listRow),
            )
            .clickable { onOpen() }
            .padding(start = 14.dp, end = 6.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 4.dp, height = 38.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (active) c.primary else c.border)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(meta.name, style = t.itemTitle, color = c.textPrimary, maxLines = 1)
            Spacer(Modifier.height(3.dp))
            Text(
                meta.subtitleOf(courseCount),
                style = t.caption,
                color = c.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 第三行对所有课表均显示（原仅自建课表可见）。
            // 来源已在副标题中，此处补充「作息 + 能否手动同步」——
            //   后者是用户判断「此表今后是否还会变化」的唯一线索。
            Spacer(Modifier.height(3.dp))
            Text(
                if (meta.isSyncable) "${meta.scheduleLabel} · 可手动同步"
                else "${meta.scheduleLabel} · 仅在本机",
                style = t.micro,
                color = c.textTertiary,
            )
        }
        if (active) {
            Spacer(Modifier.width(8.dp))
            Text("使用中", style = t.micro, color = c.primary)
        }
        Box(
            Modifier.size(34.dp).clip(CircleShape).clickable { onMore() },
            contentAlignment = Alignment.Center,
        ) { MoreIcon(c.textTertiary, 16.dp) }
    }
}
