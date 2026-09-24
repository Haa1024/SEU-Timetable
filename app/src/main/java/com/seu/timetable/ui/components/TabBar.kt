package com.seu.timetable.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.seu.timetable.ui.theme.LocalSeuColors
import com.seu.timetable.ui.theme.LocalSeuType

enum class HomeTab(val label: String) {
    TODAY("今日"),
    TIMETABLE("课表"),
    PROFILE("我的"),
}

/** 底部导航相对系统导航条插入高度的上提量：上提 10dp，使导航栏整体视觉下沉。 */
private val TabBarBottomLift = 10.dp

/**
 * 底部导航（胶囊式）。规格 3.3：
 * TabBar 高 95、左右下 21；胶囊高 62 / radius 36 / padding 4 / gap 4；
 * 选中项整块填充主色（不能只更换文字颜色）。
 *
 * 动画结构（本次重写的核心）：
 * 蓝色药丸不再作为选中项自身的背景，而是独立绘制在底层的一个 Box 中，
 * 通过 spring 动画移动到选中项的位置——切换时药丸是"滑动过去"，
 * 而非"旧的消失、新的出现"。图标与文字的颜色也随之渐变，
 * 整个切换为连续的一段运动，不存在跳变。
 *
 * 与规格的一处偏离：规格将底部内边距固定为 21。但真机手势条的
 * `navigationBars` 插入高度并非总是 21（常见 24~48），写死会导致贴边或留白。
 * 此处取 `max(21, 实际插入高度)`——既尊重设计基准，又不会在真机上出现问题。
 */
@Composable
fun SeuTabBar(
    current: HomeTab,
    onSelect: (HomeTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalSeuColors.current
    val t = LocalSeuType.current

    val navBarsBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // 底部留白：不再直接使用 max(21, 插入高度)——真机上会顶住导航条，
    // 显得悬空过高。改为在系统插入高度基础上上提 10dp：
    // 手势导航（插入约 24dp）→ 实际留 14dp，导航栏整体下沉一截；
    // 三键导航（插入约 48dp）→ 留 38dp，仍不会压到系统按键。
    val bottomPadding = maxOf(10.dp, navBarsBottom - TabBarBottomLift)

    Box(
        modifier
            .fillMaxWidth()
            .padding(start = 21.dp, end = 21.dp, top = 12.dp, bottom = bottomPadding)
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(62.dp)
                .clip(RoundedCornerShape(36.dp))
                .background(c.surface)
                .border(1.dp, c.border, RoundedCornerShape(36.dp))
                .padding(4.dp)
        ) {
            val gap = 4.dp
            val tabs = HomeTab.entries
            // 每个槽位的宽度：总宽减掉项与项之间的间隙后均分
            val slot = (maxWidth - gap * (tabs.size - 1)) / tabs.size
            val targetX = slot * current.ordinal + gap * current.ordinal

            // 药丸位置：spring 带轻微回弹（LowBouncy），切换时呈现滑动过渡。
            // 刚度取 MediumLow：过快则看不清滑动过程，过慢则拖沓。
            val pillX by animateDpAsState(
                targetValue = targetX,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                label = "tabPillX",
            )

            // ---- 底层：会动的药丸 ----
            Box(
                Modifier
                    .offset(x = pillX)
                    .width(slot)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(26.dp))
                    .background(c.primary)
            )

            // ---- 上层：三个可点的槽位（透明背景，只负责点击和内容）----
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                tabs.forEach { tab ->
                    val selected = tab == current
                    // 颜色跟着渐变，和药丸的滑动在同一时间段内完成
                    val tint by animateColorAsState(
                        targetValue = if (selected) c.onPrimary else c.textSecondary,
                        animationSpec = tween(200),
                        label = "tabTint",
                    )
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxSize()
                            // 此处的 clip 并非为了美观，而是用于约束按下的涟漪效果：
                            // clickable 的水波纹按 view 边界绘制，不裁剪则呈方块状。
                            // 槽位宽度与药丸宽度一致（均为 slot），裁成 26 圆角后
                            // 涟漪恰好与药丸等大——按下时不会出现方角。
                            .clip(RoundedCornerShape(26.dp))
                            .clickable { onSelect(tab) },
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        when (tab) {
                            HomeTab.TODAY -> TodayIcon(tint, 18.dp)
                            HomeTab.TIMETABLE -> GridIcon(tint, 18.dp)
                            HomeTab.PROFILE -> PersonIcon(tint, 18.dp)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(tab.label, style = t.tabLabel, color = tint)
                    }
                }
            }
        }
    }
}
