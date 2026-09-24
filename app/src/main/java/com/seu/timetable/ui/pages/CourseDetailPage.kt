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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.seu.timetable.domain.Course
import com.seu.timetable.domain.CourseColorAssigner
import com.seu.timetable.domain.DAY_NAMES
import com.seu.timetable.domain.Timetable
import com.seu.timetable.domain.compressWeeks
import com.seu.timetable.ui.components.BackIcon
import com.seu.timetable.ui.components.ConfirmDialog
import com.seu.timetable.ui.components.DangerButton
import com.seu.timetable.ui.components.InfoRow
import com.seu.timetable.ui.components.PrimaryButton
import com.seu.timetable.ui.components.RowDivider
import com.seu.timetable.ui.components.SectionLabel
import com.seu.timetable.ui.components.SeuCard
import com.seu.timetable.ui.components.Tag
import com.seu.timetable.ui.theme.CourseBarPalette
import com.seu.timetable.ui.theme.LocalSeuColors
import com.seu.timetable.ui.theme.LocalSeuType
import com.seu.timetable.ui.theme.SeuRadius
import com.seu.timetable.ui.theme.courseColorOf

/**
 * 页面 03 · 课程详情（规格 4.3）。
 *
 * 课表本地化之后本页承担「编辑中枢」的角色，据此做了两处减法：
 *   1. 换色直接在本页完成（点圆点即生效并落盘）。原先需 详情 → 编辑 → 滚到底 →
 *      选色 → 保存 五步，而换色是最高频的整理动作，现已简化为一步。
 *   2. 底部「编辑」「删除」并排。原先删除藏在编辑页末尾，右上角的「更多」
 *      为无响应的空壳，已移除。
 *
 * 与规格的偏离：去掉拿不到数据的字段。规格含「专业必修 · 3 学分」「考核方式」等，
 * 但 ehall 课表接口既不提供课程性质也不提供考核方式，学分仅在未排课接口存在，
 * 展示即为假数据。
 *
 * WeekBars（教学周分布）可直观呈现单双周与跳周，优于文字描述。
 *
 * @param onChangeColor 换色回调。传 null 表示交回算法分配（清除 pinned 颜色），
 *   该操作可逆：用户试色不合意时可退回自动分配。
 */
@Composable
fun CourseDetailPage(
    timetable: Timetable,
    course: Course,
    slots: CourseColorAssigner,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onChangeColor: (Int?) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalSeuColors.current
    val t = LocalSeuType.current
    val triple = courseColorOf(slots[course.id])
    val sessions = timetable.sessionsOf(course.id)
    val weeks = timetable.weeksOf(course.id)

    var askDelete by remember(course.id) { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxSize()
            .background(c.bg)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        // ---- NavBar (48) ----
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                BackIcon(c.textPrimary, 18.dp)
            }
            Spacer(Modifier.weight(1f))
            Text("课程详情", style = t.navTitle, color = c.textPrimary)
            Spacer(Modifier.weight(1f))
            // 占位以保持标题居中（原「更多」按钮点击无反应，已移除）。
            Spacer(Modifier.size(28.dp))
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            // ---- CourseHero：通栏色块 ----
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(triple.fill)
                    .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 20.dp)
            ) {
                val tagText = buildList {
                    course.credit?.let { add("${trimZero(it)} 学分") }
                    if (isEmpty()) add(course.classNo.ifBlank { "课程" })
                }.joinToString(" · ")
                Tag(tagText, triple.text, c.surface)

                Spacer(Modifier.height(12.dp))
                Text(course.name, style = t.detailTitle, color = triple.text)

                if (course.teacher.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(triple.bar),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                course.teacher.take(1),
                                style = t.caption,
                                color = c.surface,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(course.teacher, style = t.itemTitle, color = triple.text)
                    }
                }
            }

            Column(Modifier.padding(20.dp)) {
                // ---- InfoCard ----
                SeuCard(Modifier.fillMaxWidth(), padding = 0.dp) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        InfoRow("上课时间", sessions.joinToString("、") {
                            "${DAY_NAMES[it.dayOfWeek]} ${it.periodLabel()}"
                        }.ifBlank { "—" })
                        RowDivider()
                        InfoRow(
                            "上课地点",
                            sessions.map { it.room }.filter { it.isNotBlank() }
                                .distinct().joinToString("、").ifBlank { "—" },
                        )
                        RowDivider()
                        InfoRow("课程编号", course.courseCode.ifBlank { "—" })
                        if (course.note.isNotBlank()) {
                            RowDivider()
                            InfoRow("备注", course.note)
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ---- WeekCard：教学周分布 ----
                SeuCard(Modifier.fillMaxWidth()) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("教学周分布", style = t.itemTitle, color = c.textPrimary)
                            Text(
                                "第 ${compressWeeks(weeks)} 周",
                                style = t.caption,
                                color = c.textSecondary,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        WeekBars(
                            totalWeeks = timetable.term.totalWeeks,
                            activeWeeks = weeks,
                            fill = c.primary,
                            idle = c.border,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ---- 颜色：点一下即生效 ----
                SeuCard(Modifier.fillMaxWidth()) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("课程颜色", style = t.itemTitle, color = c.textPrimary)
                            Spacer(Modifier.weight(1f))
                            Text(
                                if (course.colorOverride == null) "自动分配" else "已固定",
                                style = t.caption,
                                color = c.textSecondary,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CourseBarPalette.forEachIndexed { index, color ->
                                val selected = index == slots[course.id]
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
                                        .clickable { onChangeColor(index) }
                                )
                            }
                            // 「自动」须提供显式入口：颜色被钉死后没有其他方式退回算法分配，
                            // 再点一次同色仍属「钉死」，语义上并不构成取消。
                            if (course.colorOverride != null) {
                                Spacer(Modifier.width(2.dp))
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(SeuRadius.tag))
                                        .background(c.surfaceSunken)
                                        .clickable { onChangeColor(null) }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                ) {
                                    Text("恢复自动", style = t.caption, color = c.textSecondary)
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "点击即生效，无需保存。同一门课在整张表内始终同色。",
                            style = t.caption,
                            color = c.textTertiary,
                        )
                    }
                }

                if (course.note.isNotBlank()) {
                    Spacer(Modifier.height(14.dp))
                    SectionLabel("备注")
                    Spacer(Modifier.height(8.dp))
                    SeuCard(Modifier.fillMaxWidth()) {
                        Text(course.note, style = t.body, color = c.textPrimary)
                    }
                }
            }
        }

        // ---- ActionBar：编辑 / 删除 并排 ----
        //
        // 两个动作同层，而非把删除藏进编辑页：藏起来后用户要删课，得先进入一个
        // 听起来与删除毫无关系的「编辑」页。
        Row(
            Modifier
                .fillMaxWidth()
                .background(c.surface)
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DangerButton(
                text = "删除",
                onClick = { askDelete = true },
                modifier = Modifier.width(96.dp),
            )
            PrimaryButton(
                text = "编辑",
                onClick = onEdit,
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (askDelete) {
        ConfirmDialog(
            title = "删除「${course.name}」？",
            body = "会把这门课和它的 ${sessions.size} 个时间段一起从本机删掉。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                // 先关闭弹层再删除：否则删除完成后弹层仍覆盖原地，
                // 用户会误以为「无反应」而重复点击。
                askDelete = false
                onDelete()
            },
            onDismiss = { askDelete = false },
        )
    }
}

/**
 * 教学周分布条。
 * 条数 = 学期总周数（`cxjcs.ZZC`），第 i 条对应第 i+1 周。
 * 单双周、跳周、只上 7-10 周这类情况一眼可见。
 */
@Composable
fun WeekBars(
    totalWeeks: Int,
    activeWeeks: Set<Int>,
    fill: androidx.compose.ui.graphics.Color,
    idle: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    barHeight: androidx.compose.ui.unit.Dp = 22.dp,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(barHeight),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (w in 1..totalWeeks) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (w in activeWeeks) fill else idle)
            )
        }
    }
}

/** 3.0 → "3"，3.5 → "3.5" */
internal fun trimZero(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
