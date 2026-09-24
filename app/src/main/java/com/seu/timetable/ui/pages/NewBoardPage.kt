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
import com.seu.timetable.data.NotLoggedInException
import com.seu.timetable.data.SettingsSnapshot
import com.seu.timetable.data.TimetableRepository
import com.seu.timetable.domain.BoardMeta
import com.seu.timetable.domain.DAY_NAMES
import com.seu.timetable.domain.TermCodes
import com.seu.timetable.domain.Timetable
import com.seu.timetable.ui.components.BackIcon
import com.seu.timetable.ui.components.InfoRow
import com.seu.timetable.ui.components.PrimaryButton
import com.seu.timetable.ui.components.RowDivider
import com.seu.timetable.ui.components.SectionLabel
import com.seu.timetable.ui.components.Segmented
import com.seu.timetable.ui.components.SeuCard
import com.seu.timetable.ui.components.SeuTextField
import com.seu.timetable.ui.theme.LocalSeuColors
import com.seu.timetable.ui.theme.LocalSeuType
import com.seu.timetable.ui.theme.SeuRadius
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

private enum class NewBoardMode { IMPORT, BLANK }

/**
 * 页面 · 导入 / 新建课表。
 *
 * 两种来源放在一页里，因为它们回答的是同一个问题：「多一张课表，从哪来」。
 * 用分段控件切换，共用一条返回路径，少一层导航。
 *
 * 导入分两步：先「查询」（拉数据但不落盘）并展示摘要，再「导入到本地」。
 * 教务查询耗时数秒，返回的起始周与周数用户事前并不知道，直接写盘存错后只能删表重来。
 */
@Composable
fun NewBoardPage(
    repo: TimetableRepository,
    onNeedLogin: () -> Unit,
    onCreated: (String) -> Unit,
    onBack: () -> Unit,
) {
    val c = LocalSeuColors.current
    val t = LocalSeuType.current
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(NewBoardMode.IMPORT) }

    // ---- 导入分支的状态 ----
    var currentTerm by remember { mutableStateOf<String?>(null) }
    var serverTermName by remember { mutableStateOf<String?>(null) }
    var candidates by remember { mutableStateOf<List<String>>(emptyList()) }
    var termInput by remember { mutableStateOf("") }
    var querying by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Timetable?>(null) }
    var previewTermName by remember { mutableStateOf<String?>(null) }
    var boardName by remember { mutableStateOf("") }
    var nameEdited by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var needLogin by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    // ---- 新建空白分支的状态 ----
    var blankName by remember { mutableStateOf("我的课表") }
    var mondayText by remember {
        mutableStateOf(
            LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()
        )
    }
    var totalWeeksText by remember { mutableStateOf("18") }
    var teachingWeeksText by remember { mutableStateOf("16") }
    var morningText by remember { mutableStateOf("5") }
    var afternoonText by remember { mutableStateOf("5") }
    var eveningText by remember { mutableStateOf("3") }

    // ---- 「复制已有课表的设置」的状态 ----
    var allBoards by remember { mutableStateOf<List<BoardMeta>>(emptyList()) }
    var copySettings by remember { mutableStateOf(false) }
    var copyFromId by remember { mutableStateOf<String?>(null) }
    var snapshot by remember { mutableStateOf<SettingsSnapshot?>(null) }
    var copyLoading by remember { mutableStateOf(false) }

    // 局部函数与 lambda 只能引用声明在其之前的局部变量，故把两套状态全部提到最前，
    // 再写 LaunchedEffect 与各处理函数。

    LaunchedEffect(Unit) {
        // 谁也不知道一共有哪些学期（没有列表接口），所以先问"当前是哪个学期"，
        // 再据此推算前后几个候选。详见 TermCodes 的说明。
        val current = repo.currentTermCode()
        currentTerm = current
        serverTermName = repo.currentTermName()
        candidates = if (current != null) TermCodes.nearby(current) else emptyList()
        termInput = current ?: ""
        if (!nameEdited) boardName = serverTermName.orEmpty()
        // 已有课表的列表：给"复制已有课表的设置"用
        allBoards = repo.index().boards
    }

    /**
     * 把某张已有课表的设置取过来。
     *
     * 只取作息与节次分组，不取第一周周一 / 周数——后两者属于「那个学期」，新表是另一个学期。
     * 取到的未必是那 13 行数值：来源在用学校默认作息时，
     * [SettingsSnapshot.scheduleToApply] 返回空 list，表示新表同样跟随默认，
     * 以后学校调整作息两张表会一起跟上；复制的是「安排」，不是把默认值冻成自定义。
     */
    fun applySettingsFrom(sourceId: String) {
        copyFromId = sourceId
        copyLoading = true
        scope.launch {
            try {
                val snap = repo.settingsOf(sourceId)
                snapshot = snap
                if (snap != null) {
                    // 节次分组也一起抄，用户随后可以改
                    morningText = snap.morningPeriods.toString()
                    afternoonText = snap.afternoonPeriods.toString()
                    eveningText = snap.eveningPeriods.toString()
                }
            } finally {
                copyLoading = false
            }
        }
    }

    fun query(code: String) {
        val target = code.trim()
        if (target.isEmpty()) {
            message = "请先填写学期代码，例如 2026-2027-2"
            return
        }
        querying = true
        message = null
        needLogin = false
        preview = null
        scope.launch {
            try {
                val tt = repo.fetchFromEhall(target)
                preview = tt
                previewTermName = if (target == currentTerm) serverTermName else null
                if (!nameEdited) {
                    boardName = previewTermName ?: target
                }
            } catch (e: NotLoggedInException) {
                message = "登录态已失效。导入需先使用校园账号登录一次（仅导入时需要）。"
                needLogin = true
            } catch (e: Exception) {
                message = "没查到 ${target} 的课表：${e.message ?: "未知错误"}\n" +
                    "若学期代码有误，可尝试上方列出的代码。"
            } finally {
                querying = false
            }
        }
    }

    fun doImport() {
        val tt = preview ?: return
        saving = true
        message = null
        scope.launch {
            try {
                val id = repo.saveImported(tt, boardName, previewTermName)
                onCreated(id)
            } catch (e: Exception) {
                message = "保存失败：${e.message ?: "未知错误"}"
            } finally {
                saving = false
            }
        }
    }

    fun doCreateBlank() {
        val monday = runCatching { LocalDate.parse(mondayText.trim()) }.getOrNull()
        if (monday == null) {
            message = "第一周周一要写成 2026-09-21 这样的日期"
            return
        }
        // 必须是周一：周次推算为 `firstMonday + (week-1)*7 天 + (dayOfWeek-1) 天`，
        // 起点非周一会使课表每一天整体偏移（填周三即偏 2 天），而界面上周次、节次、
        // 课块仍显示正常且不报错，属最难自查的一类错误，须在入口拦截。
        if (monday.dayOfWeek != DayOfWeek.MONDAY) {
            message = "第一周周一是「${DAY_NAMES[monday.dayOfWeek.value]}」（$monday），" +
                "但它必须是周一。整张课表的日期均由此往后推算，" +
                "起点有误会导致整体偏移且不易察觉。"
            return
        }
        val total = totalWeeksText.trim().toIntOrNull()
        val teaching = teachingWeeksText.trim().toIntOrNull() ?: total
        val morning = morningText.trim().toIntOrNull()
        val afternoon = afternoonText.trim().toIntOrNull()
        val evening = eveningText.trim().toIntOrNull()
        if (total == null || total !in 1..30) {
            message = "总周数要填 1 到 30 之间的整数"
            return
        }
        if (teaching == null || teaching !in 1..total) {
            message = "最后教学周要在 1 到总周数（$total）之间"
            return
        }
        if (morning == null || afternoon == null || evening == null ||
            morning < 0 || afternoon < 0 || evening < 0 || morning + afternoon + evening == 0
        ) {
            message = "上午 / 下午 / 晚上的节数都要填 0 或正整数，且不能全为 0"
            return
        }
        if (copySettings && snapshot == null) {
            message = "已选择「复制已有课表的设置」，但尚未选择来源课表。"
            return
        }
        saving = true
        message = null
        scope.launch {
            try {
                val id = repo.createBlank(
                    name = blankName,
                    firstMonday = monday,
                    totalWeeks = total,
                    lastTeachingWeek = teaching,
                    morningPeriods = morning,
                    afternoonPeriods = afternoon,
                    eveningPeriods = evening,
                    schedule = if (copySettings) snapshot?.scheduleToApply.orEmpty() else emptyList(),
                    copiedFrom = if (copySettings) snapshot?.copiedFromLabel else null,
                )
                onCreated(id)
            } catch (e: Exception) {
                message = "创建失败：${e.message ?: "未知错误"}"
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
            Text("添加课表", style = t.navTitle, color = c.textPrimary)
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
            Segmented(
                options = listOf(NewBoardMode.IMPORT, NewBoardMode.BLANK),
                selected = mode,
                label = { if (it == NewBoardMode.IMPORT) "从教务导入" else "新建空白" },
                onSelect = { mode = it; message = null },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(18.dp))

            if (mode == NewBoardMode.IMPORT) {
                ImportSection(
                    termInput = termInput,
                    onTermInput = { termInput = it },
                    candidates = candidates,
                    currentTerm = currentTerm,
                    querying = querying,
                    onQuery = { query(termInput) },
                )

                preview?.let { tt ->
                    Spacer(Modifier.height(16.dp))
                    SectionLabel("确认导入")
                    Spacer(Modifier.height(8.dp))
                    SeuCard(Modifier.fillMaxWidth(), padding = 0.dp) {
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            InfoRow(
                                "学期",
                                tt.term.termName.ifBlank { previewTermName ?: tt.term.termCode },
                            )
                            RowDivider()
                            InfoRow("第一周周一", tt.term.firstMonday.toString())
                            RowDivider()
                            InfoRow("周数", "${tt.term.totalWeeks} 周（教学周 ${tt.term.lastTeachingWeek} 周）")
                            RowDivider()
                            InfoRow(
                                "课程",
                                "${tt.courses.size} 门 · ${tt.sessions.size} 个时间块" +
                                    if (tt.unplaced.isEmpty()) "" else " · 未排课 ${tt.unplaced.size} 门",
                            )
                            RowDivider()
                            InfoRow(
                                "课表名",
                                boardName.ifBlank { "（用学期名）" },
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    SeuTextField(
                        value = boardName,
                        onValueChange = { boardName = it; nameEdited = true },
                        placeholder = "给这张课表起个名字",
                        suffix = "可改",
                    )
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton(
                        text = if (saving) "正在保存…" else "导入到本地",
                        onClick = { doImport() },
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                BlankSection(
                    name = blankName,
                    onName = { blankName = it },
                    monday = mondayText,
                    onMonday = { mondayText = it },
                    totalWeeks = totalWeeksText,
                    onTotalWeeks = { totalWeeksText = it },
                    teachingWeeks = teachingWeeksText,
                    onTeachingWeeks = { teachingWeeksText = it },
                    morning = morningText,
                    onMorning = { morningText = it },
                    afternoon = afternoonText,
                    onAfternoon = { afternoonText = it },
                    evening = eveningText,
                    onEvening = { eveningText = it },
                )
                Spacer(Modifier.height(16.dp))
                SectionLabel("作息设置")
                Spacer(Modifier.height(8.dp))
                SeuCard(Modifier.fillMaxWidth()) {
                    Column {
                        Segmented(
                            options = listOf(false, true),
                            selected = copySettings,
                            label = { if (it) "复制已有课表" else "用学校默认" },
                            onSelect = { v ->
                                copySettings = v
                                message = null
                                if (!v) {
                                    copyFromId = null
                                    snapshot = null
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(10.dp))

                        if (!copySettings) {
                            Text(
                                "用东南大学默认作息（13 节，第 1 节 08:00 开始，第 13 节 21:25 结束）。" +
                                    "该表将持续跟随默认值：学校日后调整作息时它会自动同步，无需手动修改。",
                                style = t.caption,
                                color = c.textSecondary,
                            )
                        } else if (allBoards.isEmpty()) {
                            Text(
                                "课表库中暂无可供复制的其它课表。先按学校默认创建这张课表，" +
                                    "日后可在「课表设置」中从其它课表导入。",
                                style = t.caption,
                                color = c.textSecondary,
                            )
                        } else {
                            Text(
                                "选择一张课表，复制其作息与节次分组：",
                                style = t.caption,
                                color = c.textSecondary,
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                allBoards.forEach { b ->
                                    val selected = b.id == copyFromId
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(SeuRadius.tag))
                                            .background(if (selected) c.primary else c.surfaceSunken)
                                            .clickable { applySettingsFrom(b.id) }
                                            .padding(horizontal = 12.dp, vertical = 7.dp),
                                    ) {
                                        Text(
                                            b.name,
                                            style = t.caption,
                                            color = if (selected) c.onPrimary else c.textSecondary,
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(10.dp))

                            val snap = snapshot
                            when {
                                copyLoading -> Text(
                                    "正在读取…",
                                    style = t.caption,
                                    color = c.textTertiary,
                                )

                                snap == null -> Text(
                                    "选择一张课表后，这里会显示其作息安排。",
                                    style = t.caption,
                                    color = c.textTertiary,
                                )

                                else -> {
                                    Text(
                                        "来源「${snap.boardName}」" +
                                            (if (snap.custom) "：自定义作息" else "：用的是学校默认作息"),
                                        style = t.caption,
                                        color = c.textSecondary,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "第 1 节 ${snap.schedule.firstOrNull()?.beginLabel() ?: "—"} 开始，" +
                                            "共 ${snap.schedule.size} 节；" +
                                            "节次分组 ${snap.morningPeriods}/" +
                                            "${snap.afternoonPeriods}/${snap.eveningPeriods}",
                                        style = t.caption,
                                        color = c.textTertiary,
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        if (snap.custom) {
                                            "复制得到的是一份独立副本，之后修改任一张都不影响另一张。"
                                        } else {
                                            "来源使用学校默认，因此新表同样记为「跟随默认」，" +
                                                "而非把当前数值固化为自定义。"
                                        },
                                        style = t.caption,
                                        color = c.textTertiary,
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                PrimaryButton(
                    text = if (saving) "正在创建…" else "创建空白课表",
                    onClick = { doCreateBlank() },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            message?.let { msg ->
                Spacer(Modifier.height(14.dp))
                SeuCard(Modifier.fillMaxWidth()) {
                    Column {
                        Text(msg, style = t.body, color = c.textSecondary)
                        if (needLogin) {
                            Spacer(Modifier.height(12.dp))
                            PrimaryButton(
                                text = "去登录",
                                onClick = { onNeedLogin() },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun ImportSection(
    termInput: String,
    onTermInput: (String) -> Unit,
    candidates: List<String>,
    currentTerm: String?,
    querying: Boolean,
    onQuery: () -> Unit,
) {
    val c = LocalSeuColors.current
    val t = LocalSeuType.current

    SectionLabel("选择学期")
    Spacer(Modifier.height(8.dp))

    if (candidates.isEmpty()) {
        Text(
            "无法获取当前学期（可能尚未登录，或教务接口不可用）。" +
                "也可在下方直接手动填写学期代码。",
            style = t.caption,
            color = c.textSecondary,
        )
        Spacer(Modifier.height(8.dp))
    } else {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            candidates.forEach { code ->
                val selected = code == termInput
                Box(
                    Modifier
                        .clip(RoundedCornerShape(SeuRadius.tag))
                        .background(if (selected) c.primary else c.surface)
                        .border(
                            1.dp,
                            if (selected) c.primary else c.border,
                            RoundedCornerShape(SeuRadius.tag),
                        )
                        .clickable { onTermInput(code) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Text(
                        if (code == currentTerm) "$code · 当前" else code,
                        style = t.caption,
                        color = if (selected) c.onPrimary else c.textSecondary,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }

    // 候选之外的学期（比如很早以前的）只能手填：没有列表接口，我们不知道有哪些。
    Text(
        "也可以直接填学期代码，格式 `学年-学年-学期`，例如 2025-2026-1",
        style = t.caption,
        color = c.textTertiary,
    )
    Spacer(Modifier.height(8.dp))
    SeuTextField(
        value = termInput,
        onValueChange = onTermInput,
        placeholder = "2026-2027-2",
        suffix = "学期代码",
    )
    Spacer(Modifier.height(12.dp))
    PrimaryButton(
        text = if (querying) "正在查询…" else "查询课表",
        onClick = onQuery,
        enabled = !querying,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun BlankSection(
    name: String,
    onName: (String) -> Unit,
    monday: String,
    onMonday: (String) -> Unit,
    totalWeeks: String,
    onTotalWeeks: (String) -> Unit,
    teachingWeeks: String,
    onTeachingWeeks: (String) -> Unit,
    morning: String,
    onMorning: (String) -> Unit,
    afternoon: String,
    onAfternoon: (String) -> Unit,
    evening: String,
    onEvening: (String) -> Unit,
) {
    val c = LocalSeuColors.current
    val t = LocalSeuType.current

    SectionLabel("基本信息")
    Spacer(Modifier.height(8.dp))
    SeuCard(Modifier.fillMaxWidth()) {
        Column {
            Text("课表名", style = t.body, color = c.textSecondary)
            Spacer(Modifier.height(8.dp))
            SeuTextField(name, onName, placeholder = "例如 考研复习安排")

            Spacer(Modifier.height(16.dp))
            Text("第一周周一（必填，且必须是周一）", style = t.body, color = c.textSecondary)
            Spacer(Modifier.height(8.dp))
            // 此处刻意用默认文本键盘：数字键盘无 "-"，无法输入 2026-09-21
            SeuTextField(
                monday, onMonday,
                placeholder = "2026-09-21",
            )

            // 手动输入日期易错且错误难以察觉（见 doCreateBlank 说明），
            // 故提供三个一键正确的快捷项，多数用户无需手动输入。
            Spacer(Modifier.height(8.dp))
            val thisMonday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    "本周一" to thisMonday,
                    "下周一" to thisMonday.plusWeeks(1),
                    "上周一" to thisMonday.minusWeeks(1),
                ).forEach { (label, date) ->
                    val selected = monday.trim() == date.toString()
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(SeuRadius.tag))
                            .background(if (selected) c.primary else c.surfaceSunken)
                            .clickable { onMonday(date.toString()) }
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

            // ---- 实时回显：把这一天的推算结果摊出来给用户确认 ----
            Spacer(Modifier.height(10.dp))
            val parsedMonday = runCatching { LocalDate.parse(monday.trim()) }.getOrNull()
            val weekdayOk = parsedMonday?.dayOfWeek == DayOfWeek.MONDAY
            Text(
                when {
                    parsedMonday == null ->
                        "填写日期后，这里会显示推算出的第 1 周区间，用于确认无误。"
                    weekdayOk ->
                        "第 1 周：${rangeLabel(parsedMonday, 0)}；" +
                            "填写的周次将以这一天为起点往后推算。"
                    else ->
                        "$parsedMonday 是「${DAY_NAMES[parsedMonday.dayOfWeek.value]}」，不是周一。" +
                            "整张课表的日期均由此推算，起点有误会导致整体偏移。"
                },
                style = t.caption,
                color = if (parsedMonday != null && !weekdayOk) c.danger else c.textTertiary,
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    SectionLabel("周数")
    Spacer(Modifier.height(8.dp))
    SeuCard(Modifier.fillMaxWidth()) {
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("总周数", style = t.body, color = c.textSecondary)
                    Spacer(Modifier.height(8.dp))
                    SeuTextField(
                        totalWeeks, onTotalWeeks,
                        placeholder = "18",
                        keyboardType = KeyboardType.Number,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text("最后教学周", style = t.body, color = c.textSecondary)
                    Spacer(Modifier.height(8.dp))
                    SeuTextField(
                        teachingWeeks, onTeachingWeeks,
                        placeholder = "16",
                        keyboardType = KeyboardType.Number,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "周次切换器以「最后教学周」为准；若有课程排在更晚的周，周数会自动延长。",
                style = t.caption,
                color = c.textTertiary,
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    SectionLabel("节次分组（上午 / 下午 / 晚上各几节）")
    Spacer(Modifier.height(8.dp))
    SeuCard(Modifier.fillMaxWidth()) {
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("上午", style = t.caption, color = c.textSecondary)
                    Spacer(Modifier.height(6.dp))
                    SeuTextField(morning, onMorning, keyboardType = KeyboardType.Number)
                }
                Column(Modifier.weight(1f)) {
                    Text("下午", style = t.caption, color = c.textSecondary)
                    Spacer(Modifier.height(6.dp))
                    SeuTextField(afternoon, onAfternoon, keyboardType = KeyboardType.Number)
                }
                Column(Modifier.weight(1f)) {
                    Text("晚上", style = t.caption, color = c.textSecondary)
                    Spacer(Modifier.height(6.dp))
                    SeuTextField(evening, onEvening, keyboardType = KeyboardType.Number)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "三个数字加起来就是一天的总节数（东南大学是 5 + 5 + 3 = 13 节）。",
                style = t.caption,
                color = c.textTertiary,
            )
        }
    }
}

/**
 * 从第一周周一推出来的第 N 周区间，例如 `9/21（周一）– 9/27（周日）`。
 *
 * 用于让用户确认起点填写正确：单独的 2026-09-21 说明不了什么，
 * 配上「第 1 周是 9/21–9/27」即可一眼对上。同一规则也用于 [TimetablePage] 的周次副标题。
 */
private fun rangeLabel(monday: LocalDate, weekOffset: Int): String {
    val start = monday.plusWeeks(weekOffset.toLong())
    val end = start.plusDays(6)
    return "${start.monthValue}/${start.dayOfMonth}（周一）– " +
        "${end.monthValue}/${end.dayOfMonth}（周日）"
}
