package com.seu.timetable.ui.guide

import com.seu.timetable.ui.components.HomeTab

/**
 * 引导的一步。
 *
 * @property target 要高亮的目标；null 表示本步是"整屏说明"（不挖洞）
 * @property title 气泡标题（一句话，动词开头）
 * @property body 气泡正文（说明"这能干什么"，不重复标题）
 * @property tab 该步需要切到哪个主页面才能看见目标。
 *               浮层在进入本步前会先把界面切到这一页，等目标上报再挖洞。
 */
@androidx.compose.runtime.Immutable
data class GuideStep(
    val target: GuideTargetKey?,
    val title: String,
    val body: String,
    val tab: HomeTab? = null,
)

/**
 * 首次使用引导的步骤脚本。
 *
 * ## 顺序为什么是这样
 *
 * 按用户**实际会走的路**排，每一步都建立在"上一步已经能看见"的基础上：
 *
 * 1. 先认导航栏——它是唯一"永远在屏幕上"的锚点，先讲它，
 *    后面每一步说"在课表页"才有意义。
 * 2. 课表页是主战场，占四步：把课表**从哪切换**、**怎么加课**、
 *    **怎么翻周**一次讲完，用户之后不必再自己摸。
 * 3. 今日页只提一件事（提醒），因为今日页本身就一眼能懂，
 *    值得单独讲的只有"它还会提前叫你上课"。
 * 4. 我的页放最后：它装着同步、小组件这些"进阶"能力，
 *    用户此前的课表已经能用，这里属于"还能更多"。
 *
 * 每步的正文都刻意写"**能做什么**"而非"**按钮叫什么**"——
 * 后者在气泡里重复一遍屏幕上的字，是浪费用户注意力的写法。
 */
val FirstRunGuideSteps: List<GuideStep> = listOf(
    GuideStep(
        target = GuideTargetKey.TAB_BAR,
        title = "三个页面，各管一件事",
        body = "「今日」看今天还剩哪些课；「课表」是完整的周视图，加课改课都在这里；" +
            "「我的」放同步、桌面小组件和各项设置。",
    ),
    GuideStep(
        target = GuideTargetKey.TIMETABLE_SWITCH_BOARD,
        title = "这里是「我的课表」",
        body = "可以新建、导入、切换、重命名多张课表——同一学期做两张对照表也没问题。" +
            "当前正在看的是哪一张，就显示在这里。",
        tab = HomeTab.TIMETABLE,
    ),
    GuideStep(
        target = GuideTargetKey.TIMETABLE_ADD,
        title = "要加课，点这里",
        body = "教务没排的讲座、辅导课、实验补课，都可以在这里手动加进课表，" +
            "并单独设置周次和颜色。",
        tab = HomeTab.TIMETABLE,
    ),
    GuideStep(
        target = GuideTargetKey.TIMETABLE_WEEK,
        title = "翻周：点箭头，或者直接左右滑",
        body = "点「第 X 周」能直接输入周数跳转，左右箭头逐周切换；" +
            "在课表上左右滑动同样能翻周。回到这一页时会自动定位到当前周。",
        tab = HomeTab.TIMETABLE,
    ),
    GuideStep(
        target = GuideTargetKey.TODAY_REMINDER,
        title = "快到上课时间，它会提醒你",
        body = "开启后，每节课开始前会按你设置的提前量发出提醒，" +
            "不用一直盯着屏幕算时间。",
        tab = HomeTab.TODAY,
    ),
    GuideStep(
        target = GuideTargetKey.PROFILE_ENTRIES,
        title = "更多能力在「我的」",
        body = "在这里可以从教务同步最新课表、把课表添加到桌面小组件、" +
            "调整提醒与主题。本机数据与账号设置也都在这一页。之后想再看这份指引，就到这里找「新手指引」。",
        tab = HomeTab.PROFILE,
    ),
)
