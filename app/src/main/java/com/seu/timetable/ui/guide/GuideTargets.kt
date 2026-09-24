package com.seu.timetable.ui.guide

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * 引导目标在**窗口坐标系**中的位置。
 *
 * 用窗口坐标系而非屏幕坐标系：引导浮层挂在 Activity 内容视图最上层，
 * 与目标控件共享同一套窗口坐标，两者相减即得洞的位置，无需关心状态栏 / 导航条插入。
 */
@androidx.compose.runtime.Immutable
data class GuideTarget(val key: GuideTargetKey, val bounds: Rect)

/** 可被引导高亮的目标。每加一处引导，在此补一个枚举值与对应控件。 */
enum class GuideTargetKey {
    /** 底部导航栏（今日 / 课表 / 我的） */
    TAB_BAR,

    /** 今日页的提醒入口 */
    TODAY_REMINDER,

    /** 课表页右上角「添加课程」 */
    TIMETABLE_ADD,

    /** 课表页顶部「切换课表」 */
    TIMETABLE_SWITCH_BOARD,

    /** 课表页顶部「第 X 周」+ 左右箭头 */
    TIMETABLE_WEEK,

    /** 我的页各功能入口（同步 / 桌面小组件 / 新手指引）所处的分组 */
    PROFILE_ENTRIES,
}

/**
 * 各步骤目标矩形的登记处。
 *
 * ## 为什么要共享一个可变字典，而不是让浮层"问"页面要坐标
 *
 * 目标控件分散在三个 tab、四个页面里，浮层无法反向持有它们。
 * 方向反过来即可：控件自己在 `onGloballyPositioned` 时把矩形**写进来**，
 * 浮层按当前步骤的 key **读出去**。两边只通过这个字典耦合，互不引用。
 *
 * ## 为什么记录所有目标、而不是只记当前步的
 *
 * 引导进行中用户可能切 tab（例如从课表切到今日），切走的那一页会被
 * [com.seu.timetable.ui.AppRoot] 的 HomeTabHost 保留在组合里但不再摆放，
 * 此时它上报的矩形会变成"过期的旧值"。因此读取时**必须判过期**（见 [boundsOf]）：
 * 只认当前步骤自己的 key，其它一律忽略。
 *
 * 用 [mutableStateOf] 而非普通 Map：矩形由布局阶段写入、由绘制阶段读取，
 * 是跨阶段的写入，必须让快照系统感知到，否则浮层不会在新的坐标上重绘。
 */
class GuideTargetRegistry {
    private val entries = mutableMapOf<GuideTargetKey, MutableState<Rect?>>()

    internal fun stateOf(key: GuideTargetKey): MutableState<Rect?> =
        entries.getOrPut(key) { mutableStateOf(null) }

    /** 供浮层读取某一步目标当前的位置。未上报或所在页面未摆放时返回 null。 */
    fun boundsOf(key: GuideTargetKey): Rect? = entries[key]?.value
}

/**
 * 全局登记处。
 *
 * 默认值给一个**可用的空实例**而非 `error(...)`：
 * 引导未启用时（绝大多数时候），各页面的 [guideTarget] 仍会读到它并上报，
 * 只是没人消费这些矩形。若默认抛异常，任何一次"忘了 provide"都会让整个页面崩掉，
 * 而这类漏配在编译期毫无痕迹——代价远大于那点内存。
 */
val LocalGuideTargets = compositionLocalOf { GuideTargetRegistry() }

/**
 * 把该 Modifier 挂在任何控件上，即可让它成为引导可高亮的目标。
 *
 * 用法：`Modifier.guideTarget(GuideTargetKey.TIMETABLE_ADD)`
 *
 * 无论引导是否进行中都照常上报——判断"是否要用"是浮层的事，
 * 控件侧不该关心当前有没有在引导（否则每次开始引导都要重组整棵树让它重新上报）。
 */
fun Modifier.guideTarget(key: GuideTargetKey): Modifier = composed {
    val registry = LocalGuideTargets.current
    onGloballyPositioned { coords ->
        // `boundsInWindow` 给的是相对窗口的矩形，与浮层同系；直接存。
        // 注意：被 clip 掉的部分不计入，正是我们想要的（洞只圈可见区域）。
        registry.stateOf(key).value = coords.boundsInWindow()
    }
}
