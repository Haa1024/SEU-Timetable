package com.seu.timetable.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.seu.timetable.ui.theme.LocalSeuColors
import com.seu.timetable.ui.theme.LocalSeuType
import com.seu.timetable.ui.theme.SeuRadius

// ===========================================================================
// 图标：全部矢量绘制（规格 3.3 明确要求，不许用 emoji 或 Unicode 符号）
//
// 统一切法：以 24 为基准坐标系，用 minDimension/24 当缩放系数，
// 这样同一个函数在 16/18/28dp 下比例都一致。
// ===========================================================================

/** 今日：圆环（描边 2）+ 中心实心圆点 */
@Composable
fun TodayIcon(tint: Color, size: Dp = 18.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        drawCircle(tint, radius = 9f * s, style = Stroke(width = 2f * s))
        drawCircle(tint, radius = 2.6f * s)
    }
}

/** 课表：两条圆角横条 */
@Composable
fun GridIcon(tint: Color, size: Dp = 18.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        val w = 16f * s
        val h = 3.4f * s
        val r = h / 2f
        drawRoundRect(
            tint,
            topLeft = Offset(4f * s, 7.5f * s),
            size = Size(w, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
        )
        drawRoundRect(
            tint,
            topLeft = Offset(4f * s, 14f * s),
            size = Size(w, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
        )
    }
}

/** 我的：圆（头）+ 半圆角矩形（肩） */
@Composable
fun PersonIcon(tint: Color, size: Dp = 18.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        drawCircle(tint, radius = 3.6f * s, center = Offset(12f * s, 8f * s))
        drawRoundRect(
            tint,
            topLeft = Offset(5.5f * s, 13.5f * s),
            size = Size(13f * s, 8f * s),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f * s, 4f * s),
        )
    }
}

/** 铃铛：身（圆角矩形）+ 沿（圆角横条）+ 摆（小圆） */
@Composable
fun BellIcon(tint: Color, size: Dp = 18.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        drawRoundRect(
            tint,
            topLeft = Offset(6.5f * s, 6f * s),
            size = Size(11f * s, 10f * s),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.5f * s, 5.5f * s),
        )
        drawRoundRect(
            tint,
            topLeft = Offset(4.5f * s, 15.5f * s),
            size = Size(15f * s, 2.4f * s),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.2f * s, 1.2f * s),
        )
        drawCircle(tint, radius = 1.6f * s, center = Offset(12f * s, 20.5f * s))
    }
}

/** 加号：两根 12×2 / 2×12 圆角条 */
@Composable
fun PlusIcon(tint: Color, size: Dp = 18.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        val r = androidx.compose.ui.geometry.CornerRadius(1f * s, 1f * s)
        drawRoundRect(
            tint,
            topLeft = Offset(6f * s, 11f * s),
            size = Size(12f * s, 2f * s),
            cornerRadius = r,
        )
        drawRoundRect(
            tint,
            topLeft = Offset(11f * s, 6f * s),
            size = Size(2f * s, 12f * s),
            cornerRadius = r,
        )
    }
}

/** 返回：两根圆角条构成「‹」 */
@Composable
fun BackIcon(tint: Color, size: Dp = 18.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        val w = 2f * s
        drawLine(tint, Offset(13.5f * s, 5f * s), Offset(8.5f * s, 12f * s), strokeWidth = w)
        drawLine(tint, Offset(8.5f * s, 12f * s), Offset(13.5f * s, 19f * s), strokeWidth = w)
        // 端点倒圆
        listOf(
            Offset(13.5f * s, 5f * s), Offset(8.5f * s, 12f * s), Offset(13.5f * s, 19f * s)
        ).forEach { drawCircle(tint, radius = w / 2f, center = it) }
    }
}

/** 前进：两根圆角条构成「›」。单独画一个，比给「‹」加镜像变换简单也稳。 */
@Composable
fun ForwardIcon(tint: Color, size: Dp = 18.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        val w = 2f * s
        drawLine(tint, Offset(10.5f * s, 5f * s), Offset(15.5f * s, 12f * s), strokeWidth = w)
        drawLine(tint, Offset(15.5f * s, 12f * s), Offset(10.5f * s, 19f * s), strokeWidth = w)
        listOf(
            Offset(10.5f * s, 5f * s), Offset(15.5f * s, 12f * s), Offset(10.5f * s, 19f * s)
        ).forEach { drawCircle(tint, radius = w / 2f, center = it) }
    }
}

/** 更多：三个圆点，间距 8 */
@Composable
fun MoreIcon(tint: Color, size: Dp = 18.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        listOf(4.5f, 12f, 19.5f).forEach { x ->
            drawCircle(tint, radius = 1.75f * s, center = Offset(x * s, 12f * s))
        }
    }
}

/** 关闭：两根交叉圆角条 */
@Composable
fun CloseIcon(tint: Color, size: Dp = 16.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        val w = 2f * s
        drawLine(tint, Offset(6f * s, 6f * s), Offset(18f * s, 18f * s), strokeWidth = w)
        drawLine(tint, Offset(18f * s, 6f * s), Offset(6f * s, 18f * s), strokeWidth = w)
    }
}

/** 勾：两根圆角条 */
@Composable
fun CheckIcon(tint: Color, size: Dp = 18.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        val w = 2.2f * s
        drawLine(tint, Offset(5f * s, 12.5f * s), Offset(10f * s, 17.5f * s), strokeWidth = w)
        drawLine(tint, Offset(10f * s, 17.5f * s), Offset(19f * s, 6.5f * s), strokeWidth = w)
    }
}

// ===========================================================================
// 通用组件（规格 3.5）
// ===========================================================================

/** 卡片：radius 16，padding 16，fill surface，1px border */
@Composable
fun SeuCard(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    content: @Composable () -> Unit,
) {
    val c = LocalSeuColors.current
    Box(
        modifier
            .clip(RoundedCornerShape(SeuRadius.card))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(SeuRadius.card))
            .padding(padding)
    ) { content() }
}

/** 区块小标题：12sp Medium textTertiary */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    val c = LocalSeuColors.current
    Text(text, modifier, style = LocalSeuType.current.caption, color = c.textTertiary)
}

/**
 * 信息行：标签宽**固定 76**（跨页面一致，规格 3.5 明确要求），
 * 上下 padding 11，横向 gap 14。
 */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    val c = LocalSeuColors.current
    val t = LocalSeuType.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, Modifier.width(76.dp), style = t.body, color = c.textSecondary)
        Spacer(Modifier.width(14.dp))
        Text(value, Modifier.weight(1f), style = t.body, color = c.textPrimary)
    }
}

/** 行间 1px 分隔线（用于信息行与设置行之间）。 */
@Composable
fun RowDivider(modifier: Modifier = Modifier) {
    val c = LocalSeuColors.current
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(c.border)
    )
}

/** 设置行：标题 + 可选副文案 + 右侧任意内容 */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val c = LocalSeuColors.current
    val t = LocalSeuType.current
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = t.body, color = c.textPrimary)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = t.caption, color = c.textSecondary)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(14.dp))
            trailing()
        }
    }
}

/** 标签胶囊：padding 5/10，radius 20，11sp Medium */
@Composable
fun Tag(
    text: String,
    contentColor: Color,
    containerColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(SeuRadius.tag))
            .background(containerColor)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(text, style = LocalSeuType.current.micro, color = contentColor)
    }
}

/** 主按钮：高 48，radius 14，fill primary */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = LocalSeuColors.current
    Box(
        modifier
            .height(48.dp)
            .clip(RoundedCornerShape(SeuRadius.button))
            .background(if (enabled) c.primary else c.border)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = LocalSeuType.current.itemTitle, color = c.onPrimary)
    }
}

/** 危险按钮：高 48，radius 14，surface 底 + danger 描边 + danger 文字 */
@Composable
fun DangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalSeuColors.current
    Box(
        modifier
            .height(48.dp)
            .clip(RoundedCornerShape(SeuRadius.button))
            .background(c.surface)
            .border(1.dp, c.danger, RoundedCornerShape(SeuRadius.button))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = LocalSeuType.current.itemTitle, color = c.danger)
    }
}

/**
 * 分段控件：轨道高 32 / radius 9 / padding 3 / gap 3 / fill surfaceSunken，
 * 选中段 radius 7 / fill surface / textPrimary Medium。
 */
@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalSeuColors.current
    val t = LocalSeuType.current
    Row(
        modifier
            .height(32.dp)
            .clip(RoundedCornerShape(SeuRadius.segmentedTrack))
            .background(c.surfaceSunken)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clip(RoundedCornerShape(SeuRadius.segmentedThumb))
                    .background(if (isSelected) c.surface else Color.Transparent)
                    .clickable { onSelect(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = t.caption,
                    color = if (isSelected) c.textPrimary else c.textSecondary,
                    // 单行且不折行：分段控件只有 32dp 高，折行后第二行会被轨道的圆角裁掉，
                    // 看起来是「字被切了」。宁可窄到省略号，也不要折行。
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 开关：轨道 44×26 radius 13，开=primary；圆点 20dp */
@Composable
fun SeuToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalSeuColors.current
    Box(
        modifier
            .size(width = 44.dp, height = 26.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(if (checked) c.primary else c.border)
            .clickable { onCheckedChange(!checked) },
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(horizontal = 3.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

/**
 * 列表行 CourseRow：radius 14，padding 14，横向 gap 12；
 * 左色条 4×38 radius 2；中间标题 15sp + 副文案 12sp；右侧状态文字 11sp。
 */
@Composable
fun CourseRow(
    title: String,
    subtitle: String,
    statusText: String?,
    barColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accentText: Boolean = false,
) {
    val c = LocalSeuColors.current
    val t = LocalSeuType.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SeuRadius.listRow))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(SeuRadius.listRow))
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 4.dp, height = 38.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(barColor)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = t.itemTitle, color = c.textPrimary, maxLines = 1)
            Spacer(Modifier.height(3.dp))
            Text(subtitle, style = t.caption, color = c.textSecondary, maxLines = 1)
        }
        if (statusText != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                statusText,
                style = t.micro,
                color = if (accentText) c.primary else c.textTertiary,
                textAlign = TextAlign.End,
            )
        }
    }
}
