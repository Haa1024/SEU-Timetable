package com.seu.timetable.ui.pages

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.seu.timetable.domain.CourseColorAssigner
import com.seu.timetable.domain.CourseSession
import com.seu.timetable.domain.DAY_NAMES
import com.seu.timetable.domain.PeriodSchedule
import com.seu.timetable.domain.TermContext
import com.seu.timetable.domain.Timetable
import com.seu.timetable.ui.components.BackIcon
import com.seu.timetable.ui.components.CloseIcon
import com.seu.timetable.ui.components.ForwardIcon
import com.seu.timetable.ui.components.PlusIcon
import com.seu.timetable.ui.components.TextInputDialog
import com.seu.timetable.ui.theme.CourseColorTriple
import com.seu.timetable.ui.theme.LocalSeuColors
import com.seu.timetable.ui.theme.LocalSeuType
import com.seu.timetable.ui.theme.SeuRadius
import com.seu.timetable.ui.theme.courseColorOf
import kotlin.math.min
import java.time.LocalDate

/** 每节的行高。规格按 5 个大节取 102dp，真实 13 节沿用会达到 1326dp，过长。 */
private val PeriodPitch = 62.dp

/** 上午 / 下午 / 晚上三段之间的额外间隙，使分段边界清晰。 */
private val GroupGap = 8.dp

/**
 * 网格左右留白。规格取 12dp，但节次栏时间文字右对齐，
 * 该留白叠加右对齐空档后，会在 "08:00" 左侧形成近 30dp 空白。
 * 此处收窄至 6dp：视觉上仍为贴边课表，省下的宽度全部留给课程列。
 */
private val GridHorizontalPadding = 6.dp

/** 节次栏文字与其右侧课程列之间的固定间隙（节次栏宽 = 实测文字宽 + 此值）。 */
private val TimeGutterGap = 6.dp

/** 非本周影子块头部标注文字。 */
private const val OutOfWeekNote = "[非本周]"

/**
 * 表头高度。原为单行 30dp；加日期后改两行（周几 + 日期）。
 *
 * 取 40dp 而非 46dp：`t.micro` 行高约 14dp，两行 28dp，再留 12dp 内边距足够。
 * 表头是固定不滚的，每多 1dp 都从课程网格的可视高度里扣，故不取宽裕值。
 */
private val HeaderHeight = 40.dp

/**
 * 左右滑动翻周的触发距离（像素）。
 *
 * 取 56dp 换算而来——约合一个指腹的宽度。比常见的"滑动阈值 24~40dp"更大，
 * 理由是翻周属于低频且"看错周"代价不小的动作（要再点回来），
 * 宁可让用户多划一点，也不要一次意外的横向抖动就跳周。
 *
 * 注意它必须在 `pointerInput` 的 lambda 里用 `density` 换算，
 * 不能直接写 `56.dp` —— 拖动回调给的是**像素**。
 */
private val SwipeThresholdDp = 56.dp

/** 切周动画时长。200ms 是"看得见在动、又不拖沓"的常见取值。 */
private const val WeekSlideMs = 200

/**
 * 页面 02 · 课表周网格（核心页，规格 4.2）。
 *
 * 与规格存在以下五处偏差（均已确认）：
 *
 * 1. 周次切换器由「5 个 WeekChip」改为 `‹ 第 X 周 ›` 箭头切换。
 *    五个 chip 一次仅显示 5 周，切换至第 16 周需点击十数次，效率过低。
 *    另支持左右滑动切周（见 [SwipeThresholdDp]）与点击周次标签直接跳周。
 *
 * 2. 行数与行高：规格按「5 个大节 × PITCH 102」出图，而真实一天为 13 节，
 *    照搬会产生 1326dp。此处改为「每节一行、62dp」，上午 / 下午 / 晚上之间
 *    保留 [GroupGap] 间隙。课块高度按 `endPeriod - startPeriod + 1` 实算，
 *    不假定均为两节连堂（实测存在四节连堂的课块）。
 *
 * 3. 未排课提示条：规格未定义该位置，按需求补充一条可关闭提示，停靠于网格下方。
 *    置于课表页而非今日页——其描述的是「本学期的课程」，而非「今日」。
 *
 * 4. 表头在「周几」下方增加该周对应日期（如 `周一` / `9.21`）。
 *    规格的表头只有周几，但课表按周翻动后，用户常需确认"这是哪一周的哪一天"，
 *    尤其在看第 5 周之后的课表时。日期仅加在下方一行，不改变表头高度以外的布局。
 *
 * 5. 周次由外部传入（受控组件），本页**不自行推算当前周**。
 *    跨周校正的时机由调用方决定（见 `AppRoot` 的「回前台校正」）——
 *    放在本页会让"用户手动翻的周"与"今天所在的周"两套意图纠缠在一起。
 */
@Composable
fun TimetablePage(
    timetable: Timetable,
    slots: CourseColorAssigner,
    schedule: PeriodSchedule,
    boardName: String,
    onOpenBoards: () -> Unit,
    today: LocalDate,
    week: Int,
    onWeekChange: (Int) -> Unit,
    onCourseClick: (String) -> Unit,
    onAddClick: () -> Unit,
    showUnplacedBar: Boolean,
    onDismissUnplaced: () -> Unit,
    /** 是否将非本周课程绘制为半透明影子块（于课表设置中控制，即时生效）。 */
    showOutOfWeek: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val c = LocalSeuColors.current
    val t = LocalSeuType.current
    val term = timetable.term
    // 控制「点击周数文字弹出输入框以跳周」的开关。
    var weekJumpOpen by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        // 收紧顶部留白，使周切换器上移（此前与上方间距过大）。
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 标题由固定的「课表」改为当前课表名称。
                // 本地课表库可能包含多张课表（多学期 / 自建 / 副本），
                // 固定标题会导致用户无法区分当前所览课表——这是本地化后
                // 新增的歧义，标题须承担该信息职责。
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            boardName,
                            Modifier.weight(1f, fill = false),
                            style = t.pageTitle,
                            color = c.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(8.dp))
                        BoardSwitchChip(onClick = onOpenBoards)
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        dateRangeLabel(term, week),
                        style = t.body,
                        color = c.textSecondary,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(c.primary)
                        .clickable { onAddClick() },
                    contentAlignment = Alignment.Center,
                ) {
                    PlusIcon(c.onPrimary, 18.dp)
                }
            }

            Spacer(Modifier.height(3.dp))

            WeekSwitcher(
                week = week,
                totalWeeks = timetable.displayedWeeks,
                onChange = onWeekChange,
                onLabelClick = { weekJumpOpen = true },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }

        Box(Modifier.weight(1f)) {
            WeekGrid(
                timetable = timetable,
                slots = slots,
                schedule = schedule,
                today = today,
                week = week,
                onWeekChange = onWeekChange,
                onCourseClick = onCourseClick,
                showOutOfWeek = showOutOfWeek,
            )
        }

        if (showUnplacedBar && timetable.unplaced.isNotEmpty()) {
            UnplacedBar(
                names = timetable.unplaced.map { it.name },
                onClose = onDismissUnplaced,
                modifier = Modifier.padding(
                    start = GridHorizontalPadding,
                    end = GridHorizontalPadding,
                    bottom = 6.dp,
                ),
            )
        }
    }

    // 点击「第 X 周」弹出的跳周输入框：使用数字键盘，越界值自动夹回 1..总周数，
    // 非数字输入保留在框中（数字键盘基本无法输入非数字字符）。
    if (weekJumpOpen) {
        TextInputDialog(
            title = "跳转到第几周",
            initialValue = week.toString(),
            body = "范围：1 – ${timetable.displayedWeeks} 周",
            placeholder = "周数",
            confirmText = "跳转",
            keyboardType = KeyboardType.Number,
            onConfirm = { input ->
                val target = input.toIntOrNull() ?: return@TextInputDialog
                onWeekChange(target.coerceIn(1, timetable.displayedWeeks))
                weekJumpOpen = false
            },
            onDismiss = { weekJumpOpen = false },
        )
    }
}

/**
 * 「切换」小胶囊。挨着课表名放，表明「这个名字是可以点的」。
 *
 * 此处使用文字而非箭头图标：箭头在 22sp 标题旁易被误认为装饰，
 * 而「切换」二字直接说明点击后的行为。本地课表库为本版新增的核心能力，
 * 入口须保证一眼可见。
 */
@Composable
private fun BoardSwitchChip(onClick: () -> Unit) {
    val c = LocalSeuColors.current
    val t = LocalSeuType.current
    Box(
        Modifier
            .clip(RoundedCornerShape(SeuRadius.tag))
            .background(c.primarySoft)
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 3.dp)
    ) {
        Text("切换", style = t.micro, color = c.primary)
    }
}

/**
 * 周次切换：`‹ 第 X 周 ›`。
 * 到达边界时箭头变淡且不可点，优于「可点但无反应」。
 * 中间「第 X 周」本身可点：弹出输入框直接跳转目标周，
 * 避免从第 1 周翻至第 16 周需连续点击 15 次箭头。
 */
@Composable
private fun WeekSwitcher(
    week: Int,
    totalWeeks: Int,
    onChange: (Int) -> Unit,
    onLabelClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalSeuColors.current
    val t = LocalSeuType.current
    val canPrev = week > 1
    val canNext = week < totalWeeks

    Row(
        modifier
            .clip(RoundedCornerShape(SeuRadius.tag))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(SeuRadius.tag))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable(enabled = canPrev) { onChange(week - 1) },
            contentAlignment = Alignment.Center,
        ) {
            BackIcon(if (canPrev) c.textPrimary else c.textTertiary, 14.dp)
        }

        Text(
            "第 $week 周",
            Modifier
                .width(88.dp)
                // clip 须置于 clickable 之前：为按下涟漪裁出圆角，避免露出方形水波纹。
                .clip(RoundedCornerShape(SeuRadius.tag))
                .clickable(onClick = onLabelClick),
            style = t.itemTitle,
            color = c.textPrimary,
            textAlign = TextAlign.Center,
        )

        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable(enabled = canNext) { onChange(week + 1) },
            contentAlignment = Alignment.Center,
        ) {
            ForwardIcon(if (canNext) c.textPrimary else c.textTertiary, 14.dp)
        }
    }
}

@Composable
private fun UnplacedBar(
    names: List<String>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalSeuColors.current
    val t = LocalSeuType.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SeuRadius.listRow))
            .background(c.surfaceSunken)
            .border(1.dp, c.border, RoundedCornerShape(SeuRadius.listRow))
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("未排课", style = t.micro, color = c.textTertiary)
        Spacer(Modifier.width(10.dp))
        Text(
            names.joinToString("、"),
            Modifier.weight(1f),
            style = t.caption,
            color = c.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .clickable { onClose() },
            contentAlignment = Alignment.Center,
        ) {
            CloseIcon(c.textTertiary, 14.dp)
        }
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun WeekGrid(
    timetable: Timetable,
    slots: CourseColorAssigner,
    schedule: PeriodSchedule,
    today: LocalDate,
    week: Int,
    /** 左右滑动换周时回调。与 [WeekSwitcher] 的 `onChange` 是同一个出口——
     *  两条交互路径（点箭头 / 滑手势）走同一处状态更新，避免两套周次来源。 */
    onWeekChange: (Int) -> Unit,
    onCourseClick: (String) -> Unit,
    showOutOfWeek: Boolean,
) {
    val c = LocalSeuColors.current
    val t = LocalSeuType.current
    val term = timetable.term
    val periods = term.periodsPerDay

    val sessions = timetable.sessionsIn(week)

    // 影子块：非本周、且**不与本周任何课块时空重叠**的时间块。
    //
    //  为什么要做重叠排除：同一门课常常"这周 3-4 节、下周 5-6 节"地挪，
    //   影子若不加过滤就会叠在本周课块上，两块文字重叠而难以辨认。
    //   规则简单直接：影子只画在"这格这周空着"的位置——
    //   被本周课占掉位置的影子舍去，用户点本周课块进详情照样能看到全部周次。
    val ghostSessions = if (showOutOfWeek) {
        timetable.sessions.filter { ghost ->
            !ghost.isActiveIn(week) && sessions.none { act ->
                act.dayOfWeek == ghost.dayOfWeek &&
                    act.startPeriod <= ghost.endPeriod &&
                    ghost.startPeriod <= act.endPeriod
            }
        }
    } else {
        emptyList()
    }

    val todayDay = today.dayOfWeek.value

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            // ---- 左右滑动切周 ----
            //
            // 用 `detectHorizontalDragGestures` 而非 `draggable` / `swipeable`：
            // 前者在判定为水平拖动后会自动**吃掉**该手势的后续事件，
            // 而垂直方向不受影响——网格本身就是竖直滚动的，
            // 两者方向正交，能自然共存，不必手写方向仲裁。
            //
            // 阈值取 [SwipeThresholdDp]，且按**拖动距离**判定而非速度：
            // 翻周是低频动作，要求用户明确地"划一段"，比甩一下就翻更不易误触。
            // 不加速度判据是有意的——速度阈值在低端机上不稳，宁可只认距离。
            .pointerInput(week, timetable.displayedWeeks) {
                // 拖动回调给的是像素，故在此按当前屏幕密度换算一次
                val thresholdPx = SwipeThresholdDp.toPx()
                var accum = 0f
                detectHorizontalDragGestures(
                    onDragStart = { accum = 0f },
                    onDragEnd = {
                        when {
                            // 左划（accum 为负）= 看下一周
                            accum <= -thresholdPx && week < timetable.displayedWeeks ->
                                onWeekChange(week + 1)
                            // 右划（accum 为正）= 看上一周
                            accum >= thresholdPx && week > 1 ->
                                onWeekChange(week - 1)
                        }
                        accum = 0f
                    },
                    onDragCancel = { accum = 0f },
                ) { _, dragAmount -> accum += dragAmount }
            }
    ) {
        // 内容可用宽 = 屏宽 − 左右各 6；节次栏取实测时间文字宽度；剩余宽度按 7 等分。
        val contentWidth = maxWidth - GridHorizontalPadding * 2
        val gutter = rememberTimeColumnWidth(schedule, periods) + TimeGutterGap
        val colWidth = (contentWidth - gutter) / 7
        val totalHeight = gridHeight(term, periods)

        // 表头与网格一起做切周动画。
        //
        // 为何两者要"一起"：表头带日期、网格放课块，翻周时二者装的是同一天的数据。
        // 若只动网格，会出现"课块已经滑到第 2 周、日期还是第 1 周"的错位瞬间——
        // 动画只要够显眼，这种不一致就会被看见。
        //
        // 方向与手势同向：看下一周（week 增大）时新内容从**右侧**进场、
        // 旧内容向左退场，与"把第 1 周往左推走、第 2 周从右边进来"的心理模型一致。
        // 故这里记下上一次的 week 以判断方向（`targetState > initialState` 即可）。
        AnimatedContent(
            targetState = week,
            transitionSpec = {
                val forward = targetState > initialState
                val w = if (forward) 1 else -1
                (
                    slideInHorizontally(tween(WeekSlideMs)) { it * w } + fadeIn(tween(WeekSlideMs))
                    ).togetherWith(
                    slideOutHorizontally(tween(WeekSlideMs)) { -it * w } + fadeOut(tween(WeekSlideMs))
                )
            },
            label = "week",
        ) { shownWeek ->
            Column(Modifier.fillMaxSize()) {
                // ---- 固定表头：周一…周日，每列下方带该周对应日期 ----
                //
                // 日期取 `term.dateOf(shownWeek, d)`——它只依赖「第几周 + 星期几」，
                // 所以翻周时日期自动跟着变，无需另存状态。
                //
                // 两行布局（周几 / 日期）而非并排单行：列宽约 46dp（7 列平分），
                // 「周三 9/23」并排会在窄屏被压到截断，而课程表列一旦文字截断就失去意义。
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = GridHorizontalPadding)
                        .height(HeaderHeight),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.width(gutter))
                    for (d in 1..7) {
                        val isToday = d == todayDay && shownWeek == term.weekOf(today)
                        val date = term.dateOf(shownWeek, d)
                        Column(
                            Modifier.width(colWidth),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                DAY_NAMES[d],
                                style = t.micro,
                                color = when {
                                    isToday -> c.primary
                                    d >= 6 -> c.textTertiary
                                    else -> c.textSecondary
                                },
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(1.dp))
                            Text(
                                // 「9.21」比「9/21」少一个字符，窄列下更不易挤
                                "${date.monthValue}.${date.dayOfMonth}",
                                style = t.micro,
                                // 日期一律用最弱色：它是辅助信息，若与周几同色，
                                // 两行会争抢注意力，反而不易一眼扫到「周几」。
                                // 仅今天例外，跟着周几一起点亮。
                                color = if (isToday) c.primary else c.textTertiary,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }

                // ---- 可滚动网格 ----
                // 顺序要点：先令滚动容器占满视口（weight(1f) + fillMaxSize），
                // 再由内容撑至 totalHeight。反之（先 height 再 scroll）
                // 内容高等于视口高，结果将无法滚动。
                Box(Modifier.weight(1f)) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = GridHorizontalPadding)
                                .height(totalHeight)
                        ) {
                            // 今日整列淡色底（对应规格 4.2 的 TodayColumnTint，不透明度 60%）。
                            // 判据用 `shownWeek` 而非外层 `week`：动画期间二者会短暂不同
                            // （旧内容还在退场），用前者才能让底色跟着自己那张内容一起淡出。
                            if (shownWeek == term.weekOf(today)) {
                                Box(
                                    Modifier
                                        .offset(x = gutter + colWidth * (todayDay - 1))
                                        .width(colWidth)
                                        .height(totalHeight)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(c.primarySoft.copy(alpha = 0.6f))
                                )
                            }

                            // 行分隔线
                            for (p in 2..periods) {
                                Box(
                                    Modifier
                                        .offset(x = gutter, y = rowTopOf(term, p))
                                        .width(contentWidth - gutter)
                                        .height(1.dp)
                                        .background(c.border)
                                )
                            }

                            // 节次栏：节次号 + 该节起止时刻（右对齐）。
                            //
                            // 时刻取自当前课表自身的作息表（BoardMeta.periodSchedule），
                            // 而非写死常量：自建课表可由用户自行填写时间，
                            // 导入课表则使用默认 SEU 作息。因此此处须接收参数，
                            // 不得在页面内直接读取 `PeriodTimes.SEU`。
                            //
                            // 接口不提供时刻（多份真实请求中仅含上午 / 下午 / 晚上分组），
                            // 故该数据完全来自本地配置。未配置的节次仅显示节次号，不做推断。
                            for (p in 1..periods) {
                                val time = schedule.timeOf(p)
                                    Box(
                                        Modifier
                                            .offset(y = rowTopOf(term, p))
                                            .width(gutter - TimeGutterGap)
                                            .height(PeriodPitch),
                                        contentAlignment = Alignment.TopEnd,
                                    ) {
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("$p", style = t.gridRoom, color = c.textTertiary)
                                        if (time != null) {
                                            Text(
                                                time.beginLabel(),
                                                style = t.gridTime,
                                                color = c.textTertiary.copy(alpha = 0.72f),
                                                maxLines = 1,
                                            )
                                            Text(
                                                time.end.toString(),
                                                style = t.gridTime,
                                                color = c.textTertiary.copy(alpha = 0.72f),
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                }
                            }

                            // 影子块（非本周）：
                            // 必须绘制于本周课块之前——Compose 中后绘制者位于上层，
                            // 若有遗漏的重叠，被覆盖的应为影子块而非本周课程。
                            // 整块 alpha 0.32 叠加头部 [非本周] 文字标注：仅凭透明度
                            // 在缩略截图、深色主题或色弱场景下仍可能被误读为本周围课程。
                            // 影子块仍可点击——其课程详情会绘制完整周次分布条，
                            // 该处才是说明「本门课程具体在哪些周上课」的位置。
                            ghostSessions.forEach { session ->
                                val course = timetable.courseOf(session) ?: return@forEach
                                CourseBlock(
                                    modifier = Modifier
                                        .offset(
                                            x = gutter + colWidth * (session.dayOfWeek - 1) + 1.dp,
                                            y = blockTopOf(term, session) + 2.dp,
                                        )
                                        .width(colWidth - 2.dp)
                                        .height(blockHeightOf(term, session) - 4.dp),
                                    boxHeight = blockHeightOf(term, session) - 4.dp,
                                    name = course.name,
                                    room = session.room,
                                    color = courseColorOf(slots[course.id]),
                                    note = OutOfWeekNote,
                                    onClick = { onCourseClick(course.id) },
                                )
                            }

                            // 本周课块（note = null → 不透明、无标注）。
                            sessions.forEach { session ->
                                val course = timetable.courseOf(session) ?: return@forEach
                                CourseBlock(
                                    modifier = Modifier
                                        .offset(
                                            x = gutter + colWidth * (session.dayOfWeek - 1) + 1.dp,
                                            y = blockTopOf(term, session) + 2.dp,
                                        )
                                        .width(colWidth - 2.dp)
                                        .height(blockHeightOf(term, session) - 4.dp),
                                    boxHeight = blockHeightOf(term, session) - 4.dp,
                                    name = course.name,
                                    room = session.room,
                                    color = courseColorOf(slots[course.id]),
                                    note = null,
                                    onClick = { onCourseClick(course.id) },
                                )
                            }
                        }
                    }
                }
            }
            }   // Column
        }       // AnimatedContent
    }           // BoxWithConstraints（同为 WeekGrid 函数体的结束）

// ---------------------------------------------------------------------------
// 网格几何：每节一行 + 组间额外间隙
// ---------------------------------------------------------------------------

/** 第 p 节所在行的顶边 y。跨越上午 / 下午 / 晚上边界时追加 [GroupGap]。 */
private fun rowTopOf(term: TermContext, p: Int): Dp {
    var y = 0.dp
    for (i in 1 until p) {
        y += PeriodPitch
        if (i == term.morningPeriods) y += GroupGap
        if (i == term.morningPeriods + term.afternoonPeriods) y += GroupGap
    }
    return y
}

private fun gridHeight(term: TermContext, periods: Int): Dp =
    rowTopOf(term, periods) + PeriodPitch

private fun blockTopOf(term: TermContext, session: CourseSession): Dp =
    rowTopOf(term, session.startPeriod)

/** 高度按真实跨度实算：跨越分组边界时须计入 [GroupGap]，否则课块偏低。 */
private fun blockHeightOf(term: TermContext, session: CourseSession): Dp =
    rowTopOf(term, session.endPeriod + 1) - rowTopOf(term, session.startPeriod)

/**
 * 课块内的教室号：原样显示，保留「教」字前缀——"教四-302" 较 "四-302" 更易辨识。
 * 旧版去除「教」字是为节省 8dp 宽度，现教室行交由 [FittedLine] 缩放字号兜底，
 * 无需再以信息换取空间。确实超长者（如「桃园田径场」）缩至下限后仍裁切。
 */
private fun roomLabel(room: String): String = room.ifBlank { "—" }

// ---------------------------------------------------------------------------
// 课块本体：影子块与本周块共用一套布局，只差一个 [非本周] 标注
// ---------------------------------------------------------------------------

/**
 * 单个课块。布局：左侧 3dp 强调条 + 内容区（[note] → 课程名 → 教室）。
 *
 * 课程名行数按块高实算，不再写死 3 行：
 * 单节块仅 58dp 高，四节连堂可达 240dp，固定 maxLines 或浪费空间或溢出。
 * 固定开销（padding、间隔、教室行、标注行）已知，剩余高度除以行高即为行数。
 */
@Composable
private fun CourseBlock(
    name: String,
    room: String,
    color: CourseColorTriple,
    /** 块内容高度（含上下 padding），用于计算课程名可容纳的行数。 */
    boxHeight: Dp,
    /** 非空 = 非本周影子块：整块半透明 + 头部标注。 */
    note: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalSeuType.current
    val density = LocalDensity.current

    fun lineHeightOf(style: TextStyle, fallbackSp: Float): Dp =
        with(density) {
            (style.lineHeight.takeIf { it != TextUnit.Unspecified } ?: fallbackSp.sp).toDp()
        }

    val titleLine = lineHeightOf(t.gridTitle, 13f)
    val roomLine = lineHeightOf(t.gridRoom, 11f)
    val noteLine = lineHeightOf(t.gridTime, 10f)

    // 固定开销 = 上下 padding 5+5 + 名/教室间距 3dp + 标注行（行高 + 2dp 间隔）。
    val noteBlock = if (note == null) 0.dp else noteLine + 2.dp
    val fixed = 5.dp * 2 + 3.dp + roomLine + noteBlock

    // (块高 − 固定开销) / 每行行高，下限 1 行、上限 8 行（更高者应前往详情页查看）。
    val titleLines = ((boxHeight - fixed) / titleLine).toInt().coerceIn(1, 8)

    Box(
        modifier
            .alpha(if (note == null) 1f else 0.32f)
            .clip(RoundedCornerShape(SeuRadius.gridBlock))
            .background(color.fill)
            .clickable(onClick = onClick)
    ) {
        Row(Modifier.fillMaxSize()) {
            // 左侧 3dp 强调条
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxSize()
                    .background(color.bar)
            )
            Column(Modifier.padding(horizontal = 5.dp, vertical = 5.dp)) {
                if (note != null) {
                    FittedLine(
                        text = note,
                        style = t.gridTime,
                        color = color.text.copy(alpha = 0.95f),
                        minSp = 5f,
                    )
                    Spacer(Modifier.height(2.dp))
                }
                Text(
                    name,
                    style = t.gridTitle,
                    color = color.text,
                    maxLines = titleLines,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                FittedLine(
                    text = roomLabel(room),
                    style = t.gridRoom,
                    color = color.text.copy(alpha = 0.85f),
                    minSp = 5f,
                )
            }
        }
    }
}

/**
 * 单行自适应文字：先按原字号测量一次宽度，放不下则等比缩小字号（设下限），
 * 缩至下限仍放不下再裁切。
 *
 * 引入该组件的原因：课块内容区仅约 30dp 宽，"教四-302" 在 8sp×1.15 下约 37dp——
 * 固定字号必然截断，而教室号末尾数字（"-302"）被截较之不显示更不可取。
 * 等比缩放依据为文字宽度 ∝ 字号，故测量一次即可算出目标字号；
 * remember 按 (文本, 可用宽, 字体) 缓存，滚动 / 重组时无需重测。
 */
@Composable
private fun FittedLine(
    text: String,
    style: TextStyle,
    color: Color,
    minSp: Float,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val maxPx = constraints.maxWidth
        val scale = remember(text, maxPx, style) {
            val w = measurer.measure(
                text = text,
                style = style,
                maxLines = 1,
                softWrap = false,
                constraints = Constraints(),
            ).size.width
            if (w <= 0 || maxPx <= 0 || maxPx == Constraints.Infinity) 1f
            else min(1f, maxPx.toFloat() / w)
        }
        val fontSize = style.fontSize.takeIf { it != TextUnit.Unspecified } ?: 8.sp
        val lineHeight = style.lineHeight.takeIf { it != TextUnit.Unspecified }
            ?: (fontSize.value * 1.3f).sp
        val size = (fontSize.value * scale).coerceAtLeast(minSp)
        // 行高随字号缩放，缩后不得小于字号的 1.25 倍，否则上下文字相贴。
        val line = (lineHeight.value * scale).coerceAtLeast(size * 1.25f)
        Text(
            text,
            Modifier.fillMaxWidth(),
            style = style.copy(fontSize = size.sp, lineHeight = line.sp),
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}

/**
 * 节次栏时间文字宽度取实测值，不再写死 46dp。
 *
 * 写死 46dp 的后果："08:00"（8sp×1.15 ≈ 23dp）右对齐后左侧空出十余 dp，
 * 叠加页面左边距，用户会看到「08:00 左侧多出一段空白」。
 * 改为实测宽度后兼顾两端：常规字体下空白归零、节省宽度全给课程列；
 * 用户调大系统字体时栏宽自动变宽，时间不会被截为 "08:0"。
 * 结果仅随 fontScale 变化，故按 fontScale 缓存即可（density 变化在换算中抵消）。
 */
@Composable
private fun rememberTimeColumnWidth(schedule: PeriodSchedule, periods: Int): Dp {
    val t = LocalSeuType.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    return remember(schedule, periods, t.gridTime, density.fontScale) {
        val labels = buildList {
            for (p in 1..periods) {
                val time = schedule.timeOf(p) ?: continue
                add(time.beginLabel())
                add(time.end.toString())
            }
        }.ifEmpty { listOf("08:00") }
        val widest = labels.maxOf {
            measurer.measure(it, t.gridTime, maxLines = 1, softWrap = false).size.width
        }
        with(density) { widest.toDp() }
    }
}

private fun dateRangeLabel(term: TermContext, week: Int): String {
    val monday = term.dateOf(week, 1)
    val sunday = term.dateOf(week, 7)
    return "${monday.monthValue}/${monday.dayOfMonth} – ${sunday.monthValue}/${sunday.dayOfMonth}"
}
