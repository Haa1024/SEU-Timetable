package com.seu.timetable.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.seu.timetable.data.LoadedBoard
import com.seu.timetable.data.TimetableRepository
import com.seu.timetable.domain.BoardMeta
import com.seu.timetable.domain.BoardSource
import com.seu.timetable.domain.DAY_NAMES
import com.seu.timetable.domain.PeriodSchedule
import com.seu.timetable.domain.PeriodTimes
import com.seu.timetable.ui.components.ActionMenuDialog
import com.seu.timetable.ui.components.BackIcon
import com.seu.timetable.ui.components.InfoRow
import com.seu.timetable.ui.components.MenuAction
import com.seu.timetable.ui.components.PrimaryButton
import com.seu.timetable.ui.components.RowDivider
import com.seu.timetable.ui.components.SectionLabel
import com.seu.timetable.ui.components.SeuCard
import com.seu.timetable.ui.components.SeuTextField
import com.seu.timetable.ui.components.SeuToggle
import com.seu.timetable.ui.theme.LocalSeuColors
import com.seu.timetable.ui.theme.LocalSeuType
import com.seu.timetable.ui.theme.SeuRadius
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * 页面 · 课表设置（学期骨架 + 作息时间）。
 *
 * 两项合为一页：它们同属这张课表的本地配置（不属于课程本身），且新建课表时
 * 通常一并设置，分页会平白多出一级导航。
 *
 * 「第一周周一」必须可改：它是整张课表的时间原点，`第几周` 与「这周的周一是哪天」
 * 均由它推出。填错会使课表日期整体偏移，而周次、节次、课块仍显示正常且不报错；
 * 若不可改，用户只能删表重建，已排课程全部丢失。
 *
 * 只有自建课表可改学期骨架：导入课表的这些值来自 `cxjcs.do`（服务端权威值），
 * 本地修改即伪造数据，故只读展示。作息时间所有课表均可改：接口不提供时刻，
 * 它本就是本地配置。
 */
@Composable
fun BoardSettingsPage(
    repo: TimetableRepository,
    boardId: String,
    onSaved: () -> Unit,
    /** 不经过「保存」按钮的即时改动（目前只有显示开关）落到主界面 */
    onMetaChanged: () -> Unit = {},
    onBack: () -> Unit,
) {
    val c = LocalSeuColors.current
    val t = LocalSeuType.current
    val scope = rememberCoroutineScope()

    var loaded by remember { mutableStateOf<LoadedBoard?>(null) }
    var loading by remember { mutableStateOf(true) }

    // ---- 学期骨架（自建课表可改）----
    var mondayText by remember { mutableStateOf("") }
    var totalWeeksText by remember { mutableStateOf("") }
    var teachingWeeksText by remember { mutableStateOf("") }
    var morningText by remember { mutableStateOf("") }
    var afternoonText by remember { mutableStateOf("") }
    var eveningText by remember { mutableStateOf("") }

    // ---- 作息 ----
    var rows by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    /** 作息是抄自哪张课表（名字）。恢复默认时清掉；保存时写进 BoardMeta。 */
    var copiedFromName by remember { mutableStateOf<String?>(null) }

    // ---- 显示（即时生效，不走「保存」）----
    var showOutOfWeek by remember { mutableStateOf(false) }
    var togglingShow by remember { mutableStateOf(false) }

    // ---- 「从其它课表导入设置」----
    var otherBoards by remember { mutableStateOf<List<BoardMeta>>(emptyList()) }
    var showImportMenu by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }

    var message by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    fun fillFrom(board: LoadedBoard) {
        val term = board.meta.term
        mondayText = term.firstMonday.toString()
        totalWeeksText = term.totalWeeks.toString()
        teachingWeeksText = term.lastTeachingWeek.toString()
        morningText = term.morningPeriods.toString()
        afternoonText = term.afternoonPeriods.toString()
        eveningText = term.eveningPeriods.toString()
        rows = board.meta.periodSchedule.times.sortedBy { it.index }
            .map { it.beginLabel() to it.end.toString() }
        copiedFromName = board.meta.copiedFrom
        showOutOfWeek = board.meta.showOutOfWeek
    }

    LaunchedEffect(boardId) {
        loading = true
        loaded = repo.open(boardId)
        loaded?.let { fillFrom(it) }
        // 其它课表：给「从其它课表导入设置」用。要排除自己。
        otherBoards = repo.index().boards.filter { it.id != boardId }
        loading = false
    }

    /**
     * 将某张课表的设置复制进来。
     *
     * 复制的是值而非绑定：复制后目标课表获得的是自身的一份副本，
     *   两边的值自此各自独立——修改任一边均不影响另一边。
     *   这正是「可快速导入已有设置，但不强制共用一套」的落点：
     *   省去重复录入，但不产生任何隐式联动。
     *
     * 仅复制作息 + 节次分组，不复制第一周周一 / 周数：
     *   后二者是「那个学期」的属性，复制到另一学期是错误的。
     */
    fun importSettingsFrom(source: BoardMeta) {
        importing = true
        message = null
        scope.launch {
            try {
                val snap = repo.settingsOf(source.id)
                if (snap == null) {
                    message = "「${source.name}」已经不存在了。"
                    return@launch
                }
                rows = snap.schedule.sortedBy { it.index }
                    .map { it.beginLabel() to it.end.toString() }
                // 来源若也是"跟随默认"，那抄过来仍然记成"跟随默认"，来源名不记
                copiedFromName = snap.copiedFromLabel
                val manual = loaded?.meta?.source == BoardSource.MANUAL
                if (manual) {
                    // 节次分组只对自建课表可改；导入课表的分组是教务给的，不动
                    morningText = snap.morningPeriods.toString()
                    afternoonText = snap.afternoonPeriods.toString()
                    eveningText = snap.eveningPeriods.toString()
                }
                // 括号不可省略：`a + if (c) x else y + z` 会被解析为 `a + (if (c) x else (y + z))`，
                // 尾串落入 else 分支，manual=true 时会丢掉后半句。
                message = "已从「${snap.boardName}」导入作息" +
                    (if (manual) "和节次分组" else "") +
                    "。确认后点击「保存」才会写入该课表。"
            } finally {
                importing = false
            }
        }
    }

    fun save() {
        val board = loaded ?: return
        val manual = board.meta.source == BoardSource.MANUAL
        message = null

        // ---- ① 学期骨架校验（只对自建课表）----
        var monday = board.meta.term.firstMonday
        var total = board.meta.term.totalWeeks
        var teaching = board.meta.term.lastTeachingWeek
        var morning = board.meta.term.morningPeriods
        var afternoon = board.meta.term.afternoonPeriods
        var evening = board.meta.term.eveningPeriods

        if (manual) {
            val parsedMonday = runCatching { LocalDate.parse(mondayText.trim()) }.getOrNull()
            if (parsedMonday == null) {
                message = "第一周周一要写成 2026-09-21 这样的日期"
                return
            }
            // 必须是周一：周次推算为 `起点 + (周-1)*7 天 + (星期-1) 天`，
            // 起点非周一会使所有日期整体偏移，且不报错。
            if (parsedMonday.dayOfWeek != DayOfWeek.MONDAY) {
                message = "第一周周一是「${DAY_NAMES[parsedMonday.dayOfWeek.value]}」" +
                    "（$parsedMonday），但它必须是周一。整张课表的日期均由此推算。"
                return
            }
            val tt = totalWeeksText.trim().toIntOrNull()
            val tw = teachingWeeksText.trim().toIntOrNull()
            val m = morningText.trim().toIntOrNull()
            val a = afternoonText.trim().toIntOrNull()
            val e = eveningText.trim().toIntOrNull()
            if (tt == null || tt !in 1..30) {
                message = "总周数要填 1 到 30 之间的整数"
                return
            }
            if (tw == null || tw !in 1..tt) {
                message = "最后教学周要在 1 到总周数（$tt）之间"
                return
            }
            if (m == null || a == null || e == null || m < 0 || a < 0 || e < 0 || m + a + e == 0) {
                message = "上午 / 下午 / 晚上的节数都要填 0 或正整数，且不能全为 0"
                return
            }

            // 缩小节次分组的后果须明确提示：已排在第 12、13 节的课在新分组下会被画到
            // 网格之外，界面上只是「不见了」且不报错，属本地编辑最易静默丢数据的一处。
            val newPeriods = m + a + e
            val overflow = board.content.sessions.filter { it.endPeriod > newPeriods }
            if (overflow.isNotEmpty()) {
                val names = overflow.mapNotNull { s ->
                    board.content.courses.firstOrNull { it.id == s.courseId }?.name
                }.distinct().take(4).joinToString("、")
                message = "改成 $newPeriods 节（$m + $a + $e）之后，" +
                    "有 ${overflow.size} 个时间块会落到第 $newPeriods 节之后，" +
                    "在课表上将不再显示：$names。" +
                    "可调大节数，或先删除这些时段。"
                return
            }

            monday = parsedMonday
            total = tt
            teaching = tw
            morning = m
            afternoon = a
            evening = e
        }

        // ---- ② 作息校验 ----
        val inputs = rows.mapIndexed { i, p -> Triple(i + 1, p.first, p.second) }
        val schedule = PeriodSchedule.fromInputs(inputs).getOrElse {
            message = it.message ?: "作息时间填得不对"
            return
        }

        saving = true
        scope.launch {
            try {
                if (manual) {
                    repo.saveTermConfig(
                        id = boardId,
                        firstMonday = monday,
                        totalWeeks = total,
                        lastTeachingWeek = teaching,
                        morningPeriods = morning,
                        afternoonPeriods = afternoon,
                        eveningPeriods = evening,
                    )
                }
                repo.saveSchedule(boardId, schedule.times, copiedFromName)
                onSaved()
            } catch (e: Exception) {
                message = "保存失败：${e.message ?: "未知错误"}"
            } finally {
                saving = false
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(28.dp).clip(CircleShape).clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) { BackIcon(c.textPrimary, 18.dp) }
            Spacer(Modifier.weight(1f))
            Text("课表设置", style = t.navTitle, color = c.textPrimary)
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

            val board = loaded
            if (loading || board == null) {
                SeuCard(Modifier.fillMaxWidth()) {
                    Text(
                        if (loading) "正在读取…" else "这张课表已经不存在了。",
                        style = t.body,
                        color = c.textSecondary,
                    )
                }
                Spacer(Modifier.height(28.dp))
                return@Column
            }

            val manual = board.meta.source == BoardSource.MANUAL
            val term = board.meta.term

            SeuCard(Modifier.fillMaxWidth()) {
                Column {
                    Text(board.meta.name, style = t.itemTitle, color = c.textPrimary)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        board.meta.subtitleOf(board.content.courses.size),
                        style = t.caption,
                        color = c.textSecondary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "作息：${board.meta.scheduleLabel}",
                        style = t.caption,
                        color = c.textTertiary,
                    )
                }
            }

            // ================= 显示 =================
            Spacer(Modifier.height(18.dp))
            SectionLabel("显示")
            Spacer(Modifier.height(8.dp))
            SeuCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("显示非本周课程", style = t.body, color = c.textPrimary)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "非本周的课程会以半透明影子绘制在本周网格中" +
                                "（与本周课程重叠时不绘制）。点击影子可查看其全部周次。",
                            style = t.caption,
                            color = c.textTertiary,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    SeuToggle(
                        checked = showOutOfWeek,
                        onCheckedChange = { v ->
                            if (togglingShow) return@SeuToggle
                            showOutOfWeek = v
                            togglingShow = true
                            scope.launch {
                                try {
                                    repo.setShowOutOfWeek(boardId, v)
                                    onMetaChanged()
                                } finally {
                                    togglingShow = false
                                }
                            }
                        },
                    )
                }
            }

            // ================= 学期骨架 =================
            Spacer(Modifier.height(18.dp))
            SectionLabel(if (manual) "学期与周数" else "学期与周数（来自教务，不可修改）")
            Spacer(Modifier.height(8.dp))

            if (manual) {
                SeuCard(Modifier.fillMaxWidth()) {
                    Column {
                        Text("第一周周一", style = t.body, color = c.textSecondary)
                        Spacer(Modifier.height(8.dp))
                        // 文本键盘：数字键盘没有 "-"，写不出 2026-09-21
                        SeuTextField(mondayText, { mondayText = it }, placeholder = "2026-09-21")

                        Spacer(Modifier.height(8.dp))
                        val thisMonday = LocalDate.now()
                            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(
                                "本周一" to thisMonday,
                                "下周一" to thisMonday.plusWeeks(1),
                                "上周一" to thisMonday.minusWeeks(1),
                                "往前一周" to null,
                            ).forEach { (label, date) ->
                                val target = date
                                    ?: runCatching { LocalDate.parse(mondayText.trim()) }
                                        .getOrNull()?.minusWeeks(1)
                                if (target == null) return@forEach
                                val selected = mondayText.trim() == target.toString()
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(SeuRadius.tag))
                                        .background(if (selected) c.primary else c.surfaceSunken)
                                        .clickable { mondayText = target.toString() }
                                        .padding(horizontal = 12.dp, vertical = 7.dp),
                                ) {
                                    Text(
                                        label,
                                        style = t.caption,
                                        color = if (selected) c.onPrimary else c.textSecondary,
                                    )
                                }
                            }
                        }

                        // 实时回显：让用户能把"这个日期"和"第 1 周是哪几天"对上
                        Spacer(Modifier.height(10.dp))
                        val parsed = runCatching { LocalDate.parse(mondayText.trim()) }.getOrNull()
                        val ok = parsed?.dayOfWeek == DayOfWeek.MONDAY
                        Text(
                            when {
                                parsed == null ->
                                    "填写日期后，这里会显示推算出的第 1 周区间。"
                                ok ->
                                    "第 1 周：${rangeLabel(parsed)}；" +
                                        "改动只影响「第几周 ↔ 哪几天」的换算，已排好的课不会丢。"
                                else ->
                                    "$parsed 是「${DAY_NAMES[parsed.dayOfWeek.value]}」，不是周一。" +
                                        "起点有误会使整张课表的日期整体偏移，且界面不显示任何异常。"
                            },
                            style = t.caption,
                            color = if (parsed != null && !ok) c.danger else c.textTertiary,
                        )

                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("总周数", style = t.caption, color = c.textSecondary)
                                Spacer(Modifier.height(6.dp))
                                SeuTextField(
                                    totalWeeksText, { totalWeeksText = it },
                                    keyboardType = KeyboardType.Number,
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text("最后教学周", style = t.caption, color = c.textSecondary)
                                Spacer(Modifier.height(6.dp))
                                SeuTextField(
                                    teachingWeeksText, { teachingWeeksText = it },
                                    keyboardType = KeyboardType.Number,
                                )
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                        Text("节次分组", style = t.caption, color = c.textSecondary)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("上午", style = t.caption, color = c.textTertiary)
                                Spacer(Modifier.height(6.dp))
                                SeuTextField(
                                    morningText, { morningText = it },
                                    keyboardType = KeyboardType.Number,
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text("下午", style = t.caption, color = c.textTertiary)
                                Spacer(Modifier.height(6.dp))
                                SeuTextField(
                                    afternoonText, { afternoonText = it },
                                    keyboardType = KeyboardType.Number,
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text("晚上", style = t.caption, color = c.textTertiary)
                                Spacer(Modifier.height(6.dp))
                                SeuTextField(
                                    eveningText, { eveningText = it },
                                    keyboardType = KeyboardType.Number,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "三个数之和为一天总节数。调小前请确认没有被移除节次上的课程" +
                                "（保存时会校验并指出具体错误）。",
                            style = t.caption,
                            color = c.textTertiary,
                        )
                    }
                }
            } else {
                SeuCard(Modifier.fillMaxWidth(), padding = 0.dp) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        InfoRow("第一周周一", term.firstMonday.toString())
                        RowDivider()
                        InfoRow("周数", "${term.totalWeeks} 周（教学周 ${term.lastTeachingWeek} 周）")
                        RowDivider()
                        InfoRow(
                            "节次分组",
                            "上午 ${term.morningPeriods} · 下午 ${term.afternoonPeriods} · " +
                                "晚上 ${term.eveningPeriods}（共 ${term.periodsPerDay} 节）",
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "这些值来自教务系统的学期设置（cxjcs 接口），属服务端权威值。" +
                        "本地改动会产生与实际不符的数据，因此只读；作息时间不受此限制。",
                    style = t.caption,
                    color = c.textTertiary,
                )
            }

            // ================= 作息 =================
            Spacer(Modifier.height(18.dp))
            SectionLabel("作息时间（每节的开始 / 结束时刻）")
            Spacer(Modifier.height(8.dp))

            // 从别的课表抄一份：省掉重复录入，但**不建立任何联动**——
            // 抄完两边各自独立，之后改哪边都不影响另一边。
            if (otherBoards.isNotEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(SeuRadius.button))
                        .background(c.primarySoft)
                        .clickable(enabled = !importing) { showImportMenu = true }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (importing) "正在读取…" else "从其它课表导入设置",
                        style = t.itemTitle,
                        color = c.primary,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            SeuCard(Modifier.fillMaxWidth()) {
                Column {
                    rows.forEachIndexed { i, pair ->
                        if (i > 0) Spacer(Modifier.height(10.dp))
                        PeriodTimeRow(
                            period = i + 1,
                            begin = pair.first,
                            end = pair.second,
                            onBegin = { v ->
                                rows = rows.toMutableList().also { it[i] = v to pair.second }
                            },
                            onEnd = { v ->
                                rows = rows.toMutableList().also { it[i] = pair.first to v }
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "时刻写成 24 小时制两位，例如 08:00 / 14:30，结束必须晚于开始。" +
                    "课表页的节次栏会直接显示这些时刻。",
                style = t.caption,
                color = c.textTertiary,
            )

            message?.let { msg ->
                Spacer(Modifier.height(12.dp))
                SeuCard(Modifier.fillMaxWidth()) {
                    Text(msg, style = t.body, color = c.danger)
                }
            }

            Spacer(Modifier.height(16.dp))
            PrimaryButton(
                text = if (saving) "正在保存…" else "保存",
                onClick = { save() },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(SeuRadius.button))
                    .background(c.surface)
                    .border(1.dp, c.border, RoundedCornerShape(SeuRadius.button))
                    .clickable {
                        message = null
                        // 恢复默认即回到「跟随学校默认」，故「抄自何处」记录亦须清除：
                        // 默认作息无来源可言，保留会使列表页显示
                        // 「自定义作息 · 复制自 X」这类自相矛盾的表述。
                        copiedFromName = null
                        rows = PeriodTimes.SEU.map { it.beginLabel() to it.end.toString() }
                    }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("作息恢复成学校默认（东南大学 13 节）", style = t.itemTitle, color = c.primary)
            }

            Spacer(Modifier.height(28.dp))
        }
    }

    if (showImportMenu) {
        ActionMenuDialog(
            title = "从其它课表导入设置",
            subtitle = "复制一份作息（自建课表会连同节次分组一并复制）。" +
                "复制后两边各自独立，互不影响。",
            actions = otherBoards.map { src ->
                MenuAction(
                    label = src.name,
                    hint = "作息：${src.scheduleLabel} · " +
                        "节次分组 ${src.term.morningPeriods}/" +
                        "${src.term.afternoonPeriods}/${src.term.eveningPeriods}",
                    onClick = { importSettingsFrom(src) },
                )
            },
            onDismiss = { showImportMenu = false },
        )
    }
}

/**
 * 一行作息。左「第 N 节」固定宽，右侧两个输入框等宽。
 *
 * 用两个独立输入框而非单个 `08:00 – 08:45`：拆分后校验能指明是开始还是结束有误。
 * 键盘用默认文本键盘，不可用数字键盘——数字键盘无冒号，而时刻必须含冒号。
 */
@Composable
private fun PeriodTimeRow(
    period: Int,
    begin: String,
    end: String,
    onBegin: (String) -> Unit,
    onEnd: (String) -> Unit,
) {
    val c = LocalSeuColors.current
    val t = LocalSeuType.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "第 $period 节",
            Modifier.width(56.dp),
            style = t.caption,
            color = c.textSecondary,
        )
        Column(Modifier.weight(1f)) {
            SeuTextField(begin, onBegin, placeholder = "08:00")
        }
        Spacer(Modifier.width(8.dp))
        Text("–", style = t.body, color = c.textTertiary)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            SeuTextField(end, onEnd, placeholder = "08:45")
        }
    }
}

/** 从第一周周一推出的第 1 周区间：`9/21（周一）– 9/27（周日）` */
private fun rangeLabel(monday: LocalDate): String {
    val end = monday.plusDays(6)
    return "${monday.monthValue}/${monday.dayOfMonth}（周一）– " +
        "${end.monthValue}/${end.dayOfMonth}（周日）"
}
