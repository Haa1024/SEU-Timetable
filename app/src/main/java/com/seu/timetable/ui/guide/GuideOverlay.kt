package com.seu.timetable.ui.guide

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.drawWithContent
import com.seu.timetable.ui.components.PrimaryButton
import com.seu.timetable.ui.theme.LocalSeuColors
import com.seu.timetable.ui.theme.LocalSeuType
import com.seu.timetable.ui.theme.SeuRadius

/** 洞四周留白：让高亮的控件不全贴着蒙层切口，有"被框住"的观感。 */
private val HolePadding = 8.dp

/** 洞的圆角。与卡片圆角一致，视觉上像"把这块单独端起来"。 */
private val HoleRadius = 16.dp

/** 蒙层不透明度。不能太黑——用户仍需看清周围布局来建立空间感。 */
private const val ScrimAlpha = 0.72f

/** 气泡与洞之间的间距。 */
private val BubbleGap = 16.dp

/** 气泡左右距屏幕边缘。 */
private val BubbleMargin = 20.dp

/** 洞位置变化的动画时长。切步骤时洞从旧位置平移过来，比直接跳更容易跟上。 */
private const val HoleAnimMs = 260

/**
 * 引导浮层。
 *
 * ## 挖洞是怎么做出来的
 *
 * 蒙层本身是一整块半透明黑。要"挖"出洞，常规写法是 `Modifier.background(...)`
 * 后再叠一个透明 Box——那挖不干净（底色仍会透出蒙层的黑）。
 * 正确手法是走**混合模式**：把整层画在一个离屏缓冲里
 * （`graphicsLayer { compositingStrategy = Offscreen }`），
 * 先铺满蒙层色，再用 `BlendMode.Clear` 在洞的矩形上**清掉**已画的像素。
 * `Clear` 是"把目标区域擦成全透明"，因此洞里露出的是下层真实界面，颜色不失真。
 *
 * 若不设 `Offscreen`，`Clear` 会作用到整个窗口的后备缓冲上，
 * 把下层界面一并擦掉、露出黑屏——这是这个写法唯一的坑。
 *
 * ## 为什么洞要在"窗口坐标"里算
 *
 * 目标控件通过 [guideTarget] 上报的是 `boundsInWindow()`；
 * 本浮层铺满整个窗口，自身原点即窗口原点，故两者相减可直接得到绘制坐标。
 * 全程不涉及状态栏 / 导航条插入高度，折叠屏与手势导航都无需另做适配。
 */
@Composable
fun GuideOverlay(
    steps: List<GuideStep>,
    registry: GuideTargetRegistry,
    onFinish: () -> Unit,
    /**
     * 步骤变化时回调，参数为该步需要的页面（可能为 null 表示不切页）。
     *
     * 由宿主（AppRoot）执行实际的 tab 切换——浮层不认识 `HomeTab`，
     * 也不该认识：它只管"高亮哪块、说什么"，"切到哪一页"属于导航职责。
     * 这层解耦让浮层可以直接搬到别的界面复用。
     */
    onStepShown: (GuideStep) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (steps.isEmpty()) return
    val c = LocalSeuColors.current
    val t = LocalSeuType.current
    val density = LocalDensity.current

    var index by remember { mutableStateOf(0) }
    val step = steps[index]
    val isLast = index == steps.lastIndex

    // 步骤一变就通知宿主切页。用 LaunchedEffect 而非直接调用：
    // 切页会改宿主的 tab 状态，在组合期间改状态会把这一帧的写入丢弃并触发警告。
    LaunchedEffect(index) { onStepShown(steps[index]) }

    // 目标矩形。可能为 null：目标所在页面尚未摆放（切换 tab 后的第一帧）。
    // 为空时**不挖洞**，只显示蒙层与气泡——比让洞停在错误位置好。
    val rawBounds = step.target?.let { registry.boundsOf(it) }

    // 洞的位置做动画：切步骤时从上一个洞平移到新洞，用户能看出"注意力被带过去了"。
    // 用 Rect 的四边分别动画会各自插值，出现矩形忽宽忽窄的畸变；
    // 这里对 center 与 size 各做一次插值，保证始终是一个规整矩形。
    val targetCenter = rawBounds?.center
    val animatedCenterX by animateFloatAsState(
        targetValue = targetCenter?.x ?: 0f,
        animationSpec = tween(HoleAnimMs),
        label = "holeCx",
    )
    val animatedCenterY by animateFloatAsState(
        targetValue = targetCenter?.y ?: 0f,
        animationSpec = tween(HoleAnimMs),
        label = "holeCy",
    )
    val animatedW by animateFloatAsState(
        targetValue = rawBounds?.width ?: 0f,
        animationSpec = tween(HoleAnimMs),
        label = "holeW",
    )
    val animatedH by animateFloatAsState(
        targetValue = rawBounds?.height ?: 0f,
        animationSpec = tween(HoleAnimMs),
        label = "holeH",
    )

    val holePadPx = with(density) { HolePadding.toPx() }
    val holeRadiusPx = with(density) { HoleRadius.toPx() }

    // 有目标但尚未上报时（切换 tab 的第一帧），画面上什么都不挖。
    val hasHole = rawBounds != null && animatedW > 0f && animatedH > 0f

    val hole = if (hasHole) {
        Rect(
            left = animatedCenterX - animatedW / 2f - holePadPx,
            top = animatedCenterY - animatedH / 2f - holePadPx,
            right = animatedCenterX + animatedW / 2f + holePadPx,
            bottom = animatedCenterY + animatedH / 2f + holePadPx,
        )
    } else {
        null
    }

    // 蒙层整体淡入：进入引导时不要"啪"地变黑。
    // 用 LaunchedEffect 把目标值从 0 推到 ScrimAlpha，首帧即为 0，随后动画到位。
    var scrimVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { scrimVisible = true }
    val scrimAlpha by animateFloatAsState(
        targetValue = if (scrimVisible) ScrimAlpha else 0f,
        animationSpec = tween(200),
        label = "scrim",
    )

    BoxWithConstraints(modifier.fillMaxSize()) {
        val screenH = maxHeight
        val screenW = maxWidth

        // ---- 蒙层 + 洞 ----
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    // 先铺满蒙层色
                    drawRect(Color.Black.copy(alpha = scrimAlpha))
                    if (hole != null) {
                        // 再用 Clear 把洞擦成全透明，露出下层真实界面
                        drawRoundRect(
                            color = Color.Transparent,
                            topLeft = androidx.compose.ui.geometry.Offset(hole.left, hole.top),
                            size = androidx.compose.ui.geometry.Size(hole.width, hole.height),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(holeRadiusPx),
                            blendMode = BlendMode.Clear,
                        )
                        // 洞边缘描一圈主色，让"这里可以点"更明确
                        drawRoundRect(
                            color = c.primary.copy(alpha = 0.9f),
                            topLeft = androidx.compose.ui.geometry.Offset(hole.left, hole.top),
                            size = androidx.compose.ui.geometry.Size(hole.width, hole.height),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(holeRadiusPx),
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                }
                // 点蒙层不关闭引导：误触最外层就退出，会让用户丢掉当前进度。
                // 要退出有明确的「跳过」按钮。
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                ) { }
        )

        // ---- 文字气泡 ----
        //
        // 位置策略：默认放在洞的**下方**；若下方空间不够（洞在屏幕下半部分），
        // 则改放上方。这个判断是"气泡不能盖住自己高亮的控件"的唯一保证。
        //
        // 当本步没有目标（hasHole 为 false）或目标尚未上报时，气泡居中显示。
        val bubbleMaxH = screenH * 0.42f
        val holeBottomDp = hole?.let { with(density) { it.bottom.toDp() } }
        val holeTopDp = hole?.let { with(density) { it.top.toDp() } }

        // 下方剩余空间（把气泡高度估成 bubbleMaxH，富余够就放下方）
        val spaceBelow = holeBottomDp?.let { screenH - it - BubbleGap } ?: screenH
        val spaceAbove = holeTopDp?.let { it - BubbleGap } ?: screenH
        val placeBelow = spaceBelow >= bubbleMaxH || spaceBelow >= spaceAbove

        val bubbleTop: Dp = when {
            holeBottomDp == null -> (screenH - bubbleMaxH) / 2f
            placeBelow -> holeBottomDp + BubbleGap
            else -> (holeTopDp!! - BubbleGap - bubbleMaxH).coerceAtLeast(BubbleMargin)
        }

        Column(
            Modifier
                .fillMaxWidth()
                .offset(y = bubbleTop)
                .padding(horizontal = BubbleMargin)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(SeuRadius.card))
                    .background(c.surface)
                    .border(1.dp, c.border, RoundedCornerShape(SeuRadius.card))
                    .padding(18.dp)
            ) {
                Column {
                    // 步骤序号：让用户知道"还有几步"，避免以为被卡住
                    Text(
                        "${index + 1} / ${steps.size}",
                        style = t.micro,
                        color = c.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(step.title, style = t.itemTitle, color = c.textPrimary)
                    Spacer(Modifier.height(8.dp))
                    Text(step.body, style = t.caption, color = c.textSecondary)
                    Spacer(Modifier.height(16.dp))

                    // 按钮排布：末步给「完成」，其余给「下一步」；
                    // 「跳过 / 上一步」永远是文字按钮，不与主按钮抢视觉。
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (index > 0) {
                            Text(
                                "上一步",
                                style = t.caption,
                                color = c.textSecondary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { index-- }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (!isLast) {
                            Text(
                                "跳过",
                                style = t.caption,
                                color = c.textTertiary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onFinish() }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        }
                        Box(Modifier.width(96.dp)) {
                            PrimaryButton(
                                text = if (isLast) "开始使用" else "下一步",
                                onClick = { if (isLast) onFinish() else index++ },
                            )
                        }
                    }
                }
            }
        }
    }
}
