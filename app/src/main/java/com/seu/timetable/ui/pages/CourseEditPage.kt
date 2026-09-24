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
import com.seu.timetable.data.TimetableRepository
import com.seu.timetable.domain.Course
import com.seu.timetable.domain.CourseSession
import com.seu.timetable.domain.DAY_NAMES
import com.seu.timetable.domain.PeriodSchedule
import com.seu.timetable.domain.TermContext
import com.seu.timetable.domain.compressWeeks
import com.seu.timetable.domain.parseWeeks
import com.seu.timetable.ui.components.BackIcon
import com.seu.timetable.ui.components.ConfirmDialog
import com.seu.timetable.ui.components.DangerButton
import com.seu.timetable.ui.components.PrimaryButton
import com.seu.timetable.ui.components.SectionLabel
import com.seu.timetable.ui.components.SeuCard
import com.seu.timetable.ui.components.SeuTextField
import com.seu.timetable.ui.theme.CourseBarPalette
import com.seu.timetable.ui.theme.LocalSeuColors
import com.seu.timetable.ui.theme.LocalSeuType
import com.seu.timetable.ui.theme.SeuRadius
import kotlinx.coroutines.launch

/**
 * 一个时间段的草稿（编辑过程中的形态）。
 *
 * 编辑对象为文本而非直接的 [CourseSession]：
 * 编辑过程几乎每步都「不合法」——节次框刚被清空、周次只输入了 "1-"。
 * 若直接写入领域对象，须令 `CourseSession` 容忍非法值，相当于将脏数据
 * 引入整个 App（网格、今日页均读取它）。草稿层将「不合法」隔离在页面内，
 * 落盘前统一校验一次。
 */
private data class BlockDraft(
    val day: Int = 1,
    val start: String = "1",
    val end: String = "2",
    val weeks: String = "1-16",
    val room: String = "",
) {
    companion object {
        fun of(session: CourseSession): BlockDraft = BlockDraft(
            day = session.dayOfWeek,
            start = session.startPeriod.toString(),
            end = session.endPeriod.toString(),
            weeks = compressWeeks(session.weeks),
            room = session.room,
        )
    }
}

/**
 * 页面 · 新增 / 编辑课程（所有来源的课表共用同一页面）。
 *
 * 这是本次改动中最重要的一处语义修正。
 *
 *   改造前分为两页：教务导入课表走只读的 `EditCoursePage`（仅可改颜色 /
 *   学分 / 备注），自建课表才走可改结构的 `ManualCoursePage`。
 *   该设计唯一理由：课表每次启动都从教务实时拉取，本地改动会被下次同步覆盖，
 *   故直接禁止修改，以免用户误以为「修改生效」。
 *
 *   现该前提已不成立：课表为本机资产，打开 App 不会从教务拉取任何数据，
 *   用户修改的即唯一数据副本，不存在「被覆盖」。因此：
 *
 *   - 所有课表均可修改全部字段（名称 / 教师 / 星期 / 节次 / 周次 / 教室 / 多个时段）；
 *   - 所有课表均可删除课程——「删除后下次同步会恢复」已不成立；
 *   - 若需覆盖，仅能由用户主动点「手动同步」，且同步前会明确提示。
 *
 *   唯一保留的差异是顶部一句来源提示：来自教务的课表会说明「修改的是本机副本，
 *   手动同步以教务为准」。这是必要的告知，而非限制。
 *
 * 关于本地信息（颜色 / 学分 / 备注）：其与会务无关，
 *   手动同步时按课程配对保留（见 `TimetableRepository.syncFromEhall`），
 *   故此处可放心交由用户修改。
 *
 * @param existing null 表示新增
 * @param defaultColorSlot 新增时的初始颜色槽（由课表页按自动分配给出）
 * @param importedFromEhall 教务侧是否存在该课表对应学期
 *   （= `BoardMeta.isSyncable`）。为 true 时顶部多出一张卡片，说明
 *   「修改的是本机副本、手动同步以教务为准」——这是必要的告知，而非限制。
 */
@Composable
fun CourseEditPage(
    repo: TimetableRepository,
    boardId: String,
    term: TermContext,
    schedule: PeriodSchedule,
    defaultColorSlot: Int,
    existing: Course?,
    existingSessions: List<CourseSession>,
    importedFromEhall: Boolean,
    onSaved: () -> Unit,
    onDeleted: () -> Unit,
    onBack: () -> Unit,
) {
    val c = LocalSeuColors.current
    val t = LocalSeuType.current
    val scope = rememberCoroutineScope()

    // key 取 existing?.id：同一页内从「新增」切换至「编辑另一门」时必须重填。
    //   使用 remember(existing?.id) 而非 remember{}，否则会残留上一门课的值。
    var name by remember(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var teacher by remember(existing?.id) { mutableStateOf(existing?.teacher.orEmpty()) }
    var creditText by remember(existing?.id) {
        mutableStateOf(existing?.credit?.let { trimZero(it) }.orEmpty())
    }
    var note by remember(existing?.id) { mutableStateOf(existing?.note.orEmpty()) }

    var colorSlot by remember(existing?.id) {
        mutableStateOf(existing?.colorOverride ?: defaultColorSlot)
    }

    /**
     * 用户是否亲手点过颜色。
     *
     * 该标记用于保住「未选择 = 自动分配」的性质。
     *   `colorOverride = null` 表示颜色交由算法分配——同一门课在整张表中
     *   始终同色，增删课程后仍稳定。若保存时无条件写死当前槽位，
     *   用户进入查看再保存也会把该课颜色钉死，
     *   后续算法改进亦无法影响，这并非用户期望。
     */
    var colorTouched by remember(existing?.id) { mutableStateOf(false) }

    var blocks by remember(existing?.id) {
        mutableStateOf(
            if (existingSessions.isEmpty()) listOf(BlockDraft())
            else existingSessions.map { BlockDraft.of(it) }
        )
    }

    var message by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }

    val periods = term.periodsPerDay

    /** 校验好的一个时间段。局部类：它只是本页的中间产物，没必要进领域层。 */
    data class ParsedBlock(val day: Int, val from: Int, val to: Int, val weeks: Set<Int>, val room: String)

    fun doSave() {
        if (name.isBlank()) {
            message = "课程名不能为空"
            return
        }
        if (blocks.isEmpty()) {
            message = "至少需要一个时间段，否则该课程不会出现在课表上"
            return
        }

        // 逐块校验，并标明是第几个时间段：用户可能添加多个块，
        // 仅回「节次不对」会让用户自行定位。
        val parsed = ArrayList<ParsedBlock>(blocks.size)
        blocks.forEachIndexed { i, b ->
            val label = "第 ${i + 1} 个时间段"
            val from = b.start.trim().toIntOrNull()
            val to = b.end.trim().toIntOrNull()
            if (from == null || to == null || from !in 1..periods || to !in 1..periods) {
                message = "$label 的节次填得不对：要填 1 到 $periods 之间的数字"
                return
            }
            if (from > to) {
                message = "$label 的起始节次（$from）大于结束节次（$to），顺序有误"
                return
            }
            val weeks = parseWeeks(b.weeks)
            if (weeks.isEmpty()) {
                message = "$label 的周次无法解析出有效范围，请写成 1-16 或 1,3,5-8 的形式"
                return
            }
            parsed += ParsedBlock(b.day, from, to, weeks, b.room.trim())
        }

        // 同一门课的两个时段完全相同，通常是误点「添加时间段」所致，在网格上会重叠而难以察觉。
        // 此处直接拦截，优于让其在课表上呈现为颜色更深的单块。
        val dup = parsed.groupBy { listOf(it.day, it.from, it.to) }.entries.firstOrNull { it.value.size > 1 }
        if (dup != null) {
            message = "有两个时间段的星期与节次完全相同，请删除重复项"
            return
        }

        // 时段 id：同一门课的两个时段须能区分（否则 Compose 的 key 冲突）。
        // 同一门课修改后的 id 须保持稳定，故不再将节次编入 id 后半段——
        // 早期版本为 `|s$i|$day|$from-$to`，挪动一次节次 id 即变化，
        // 而 id 代表「同一时间块」的身份，不应随内容漂移。
        val courseId = existing?.id ?: "m${System.currentTimeMillis()}${(100..999).random()}"
        val course = Course(
            id = courseId,
            name = name.trim(),
            teacher = teacher.trim(),
            courseCode = existing?.courseCode.orEmpty(),
            classNo = existing?.classNo.orEmpty(),
            credit = creditText.trim().toDoubleOrNull(),
            note = note.trim(),
            // 未亲手选过颜色则交回算法（null = 自动分配），详见 colorTouched 说明。
            colorOverride = if (colorTouched || existing?.colorOverride != null) colorSlot else null,
        )
        val sessions = parsed.mapIndexed { i, p ->
            CourseSession(
                id = "$courseId|b$i",
                courseId = courseId,
                dayOfWeek = p.day,
                startPeriod = p.from,
                endPeriod = p.to,
                weeks = p.weeks,
                room = p.room,
            )
        }

        saving = true
        message = null
        scope.launch {
            try {
                repo.saveCourseWithSessions(boardId, course, sessions)
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
            Text(
                if (existing == null) "新增课程" else "编辑课程",
                style = t.navTitle,
                color = c.textPrimary,
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .size(56.dp, 28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !saving) { doSave() },
                contentAlignment = Alignment.Center,
            ) { Text("保存", style = t.itemTitle, color = c.primary) }
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(6.dp))

            // 来源提示：这是「所有课表均可修改」之后必须保留的一句话。
            //   来自教务的课表，用户修改的实为本地副本；手动同步以教务为准。
            //   若不说明，用户会误以为自己的改动已写回教务系统。
            if (importedFromEhall) {
                SeuCard(Modifier.fillMaxWidth()) {
                    Column {
                        Text("这是从教务导入的课表", style = t.itemTitle, color = c.textPrimary)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "此处修改的是本机副本，教务系统中的数据不会被改动，" +
                                "课表也不会自行还原（App 不会自动同步）。" +
                                "仅在你主动点击「从教务同步」时，课程才会以教务为准重新拉取；" +
                                "颜色、学分、备注等教务没有的字段会保留。",
                            style = t.caption,
                            color = c.textSecondary,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            SectionLabel("课程")
            Spacer(Modifier.height(8.dp))
            SeuCard(Modifier.fillMaxWidth()) {
                Column {
                    Text("课程名", style = t.body, color = c.textSecondary)
                    Spacer(Modifier.height(8.dp))
                    SeuTextField(name, { name = it }, placeholder = "例如 高等数学")
                    Spacer(Modifier.height(14.dp))
                    Text("授课教师", style = t.body, color = c.textSecondary)
                    Spacer(Modifier.height(8.dp))
                    SeuTextField(teacher, { teacher = it }, placeholder = "可留空")
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionLabel("上课时间（可添加多个时间段）")
            Spacer(Modifier.height(8.dp))

            blocks.forEachIndexed { i, b ->
                BlockEditor(
                    index = i,
                    draft = b,
                    periods = periods,
                    totalWeeks = term.totalWeeks,
                    schedule = schedule,
                    canRemove = blocks.size > 1,
                    onChange = { nb ->
                        blocks = blocks.mapIndexed { j, old -> if (j == i) nb else old }
                    },
                    onRemove = { blocks = blocks.filterIndexed { j, _ -> j != i } },
                )
                Spacer(Modifier.height(10.dp))
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(SeuRadius.button))
                    .background(c.surface)
                    .border(1.dp, c.border, RoundedCornerShape(SeuRadius.button))
                    .clickable { blocks = blocks + BlockDraft() }
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("＋ 添加时间段", style = t.itemTitle, color = c.primary)
            }

            Spacer(Modifier.height(16.dp))
            SectionLabel("本地信息")
            Spacer(Modifier.height(8.dp))
            SeuCard(Modifier.fillMaxWidth()) {
                Column {
                    Text("颜色", style = t.body, color = c.textSecondary)
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CourseBarPalette.forEachIndexed { index, color ->
                            val selected = index == colorSlot
                            Box(
                                Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .then(
                                        if (selected) {
                                            Modifier.border(2.dp, c.textPrimary, CircleShape)
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .clickable {
                                        colorSlot = index
                                        colorTouched = true
                                    }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (colorTouched || existing?.colorOverride != null) {
                            "该课程颜色已固定为你所选的颜色。"
                        } else {
                            "未选择时由算法分配：同一门课在整张表内始终同色，" +
                                "增删课程不会导致颜色错乱。"
                        },
                        style = t.caption,
                        color = c.textTertiary,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("学分", style = t.body, color = c.textSecondary)
                    Spacer(Modifier.height(8.dp))
                    SeuTextField(
                        creditText, { creditText = it },
                        placeholder = "例如 3 或 3.5",
                        keyboardType = KeyboardType.Decimal,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "课表接口不返回学分，填写后才会显示在课程详情中",
                        style = t.caption,
                        color = c.textTertiary,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("备注", style = t.body, color = c.textSecondary)
                    Spacer(Modifier.height(8.dp))
                    SeuTextField(
                        note, { note = it },
                        placeholder = "例如：带计算器、期末闭卷",
                        singleLine = false,
                    )
                }
            }

            message?.let { msg ->
                Spacer(Modifier.height(12.dp))
                SeuCard(Modifier.fillMaxWidth()) {
                    Text(msg, style = t.body, color = c.danger)
                }
            }

            // 删除对所有课表开放。改造前的前提是「删除即下次同步恢复、语义为隐藏」，
            //   故做了只读页；现无自动同步，删除即为真删除，无需「隐藏」这一中间态。
            if (existing != null) {
                Spacer(Modifier.height(20.dp))
                DangerButton(
                    text = "删除这门课",
                    onClick = { askDelete = true },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(24.dp))
        }

        Box(
            Modifier
                .fillMaxWidth()
                .background(c.surface)
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp)
        ) {
            PrimaryButton(
                text = if (saving) "正在保存…" else "保存",
                onClick = { doSave() },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (askDelete && existing != null) {
        ConfirmDialog(
            title = "删除「${existing.name}」？",
            body = "会把这门课和它的 ${existingSessions.size} 个时间段一起从本机删掉。" +
                if (importedFromEhall) "以后手动同步时它会被重新拉回来。" else "删除后无法恢复。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                // 先关闭弹层再执行删除：否则删除完成后弹层仍覆盖页面，
                //   用户会误以为「点击无效」而重复点击（此时课程已不存在）。
                askDelete = false
                scope.launch {
                    runCatching { repo.deleteCourse(boardId, existing.id) }
                        .onSuccess { onDeleted() }
                        .onFailure { message = "删除失败：${it.message ?: "未知错误"}" }
                }
            },
            onDismiss = { askDelete = false },
        )
    }
}

/**
 * 一个时段的编辑块：星期 chips + 起止节次 + 周次（含快捷选项）+ 教室。
 *
 * 底部实时计算「08:00 – 09:35」：节次本身不承载时间含义，
 * 将其对照当前课表作息翻译后，用户才能确认所排时间正确
 * （排错在网格上完全无法察觉）。
 */
@Composable
private fun BlockEditor(
    index: Int,
    draft: BlockDraft,
    periods: Int,
    totalWeeks: Int,
    schedule: PeriodSchedule,
    canRemove: Boolean,
    onChange: (BlockDraft) -> Unit,
    onRemove: () -> Unit,
) {
    val c = LocalSeuColors.current
    val t = LocalSeuType.current

    SeuCard(Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("时间段 ${index + 1}", style = t.itemTitle, color = c.textPrimary)
                Spacer(Modifier.weight(1f))
                if (canRemove) {
                    Text(
                        "删除",
                        Modifier
                            .clip(RoundedCornerShape(SeuRadius.button))
                            .clickable { onRemove() }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        style = t.caption,
                        color = c.danger,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text("星期", style = t.caption, color = c.textSecondary)
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (d in 1..7) {
                    val selected = d == draft.day
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) c.primary else c.surfaceSunken)
                            .clickable { onChange(draft.copy(day = d)) }
                            .padding(horizontal = 11.dp, vertical = 7.dp),
                    ) {
                        Text(
                            DAY_NAMES[d],
                            style = t.caption,
                            color = if (selected) c.onPrimary else c.textSecondary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("起始节", style = t.caption, color = c.textSecondary)
                    Spacer(Modifier.height(6.dp))
                    SeuTextField(
                        draft.start, { onChange(draft.copy(start = it)) },
                        placeholder = "1", keyboardType = KeyboardType.Number,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text("–", style = t.body, color = c.textTertiary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("结束节", style = t.caption, color = c.textSecondary)
                    Spacer(Modifier.height(6.dp))
                    SeuTextField(
                        draft.end, { onChange(draft.copy(end = it)) },
                        placeholder = "2", keyboardType = KeyboardType.Number,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("周次", style = t.caption, color = c.textSecondary)
            Spacer(Modifier.height(6.dp))
            SeuTextField(
                draft.weeks, { onChange(draft.copy(weeks = it)) },
                placeholder = "1-16",
            )
            // 快捷周次：手写 "1,3,5,7,9,11" 是此处最繁琐的一步，
            //   而单双周又是最常见需求（体育课、实验课多隔周进行）。
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    "全学期" to (1..totalWeeks).toSet(),
                    "单周" to (1..totalWeeks).filter { it % 2 == 1 }.toSet(),
                    "双周" to (1..totalWeeks).filter { it % 2 == 0 }.toSet(),
                    "前半学期" to (1..(totalWeeks / 2).coerceAtLeast(1)).toSet(),
                    "后半学期" to (((totalWeeks / 2) + 1)..totalWeeks).toSet(),
                ).forEach { (label, weeks) ->
                    if (weeks.isEmpty()) return@forEach
                    val selected = parseWeeks(draft.weeks) == weeks
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(SeuRadius.tag))
                            .background(if (selected) c.primary else c.surfaceSunken)
                            .clickable { onChange(draft.copy(weeks = compressWeeks(weeks))) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            label,
                            style = t.caption,
                            color = if (selected) c.onPrimary else c.textSecondary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("教室", style = t.caption, color = c.textSecondary)
            Spacer(Modifier.height(6.dp))
            SeuTextField(
                draft.room, { onChange(draft.copy(room = it)) },
                placeholder = "可留空，例如 教二-301",
            )

            // ---- 实时时刻预览 ----
            val from = draft.start.trim().toIntOrNull()
            val to = draft.end.trim().toIntOrNull()
            val timeLabel = if (from != null && to != null && from <= to) {
                schedule.timeRangeLabel(from, to)
            } else {
                null
            }
            Spacer(Modifier.height(10.dp))
            Text(
                when {
                    timeLabel != null -> "按当前作息：$timeLabel"
                    from == null || to == null -> "填好起止节次后，这里会显示对应的时刻"
                    else -> "起止节次填写有误，无法计算时刻（一天共 $periods 节）"
                },
                style = t.caption,
                color = if (timeLabel != null) c.primary else c.textTertiary,
            )
        }
    }
}
