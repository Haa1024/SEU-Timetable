package com.seu.timetable.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 主题模式。规格 4.5「外观」分段控件用。 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

// ---------------------------------------------------------------------------
// 颜色 token（规格 2.1）
//
// 同一组 token 名对应浅色与深色两套取值。所有 UI 一律读 LocalSeuColors，
// 页面内不得硬编码颜色，否则深色主题会出现漏改的色块。
// ---------------------------------------------------------------------------

@Immutable
data class SeuColors(
    val bg: Color,              // 页面底色
    val surface: Color,         // 卡片 / 弹层 / 导航胶囊
    val surfaceSunken: Color,   // 内凹槽（分段控件轨道）
    val border: Color,          // 1px 描边、分隔线、网格线
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val primary: Color,
    val primarySoft: Color,     // 主色浅底（标签、今日列底色）
    val onPrimary: Color,
    val danger: Color,
    val success: Color,
    val isDark: Boolean,
)

/**
 * 主色：东大绿，即东南大学 VI 规范 A5-1 的标准主色，
 * CMYK(70,30,100,20) → RGB(76,125,44) → #4C7D2C。
 *
 * 浅色主题直接采用官方值，白底上作按钮与胶囊填充对比度充足；
 * 深色主题改用提亮变体 #6EA64C，原值在深底上会显得发闷。
 * 页面底色与描边同步由蓝灰调改为绿灰调，使中性色系与主色冷暖一致。
 */
val LightSeuColors = SeuColors(
    bg = Color(0xFFF2F5F0),
    surface = Color(0xFFFFFFFF),
    surfaceSunken = Color(0xFFFAFBF8),
    border = Color(0xFFE6EAE1),
    textPrimary = Color(0xFF14171A),
    textSecondary = Color(0xFF707580),
    textTertiary = Color(0xFFA1A6B0),
    primary = Color(0xFF4C7D2C),     // 东大绿（官方标准色）
    primarySoft = Color(0xFFEFF4E9), // 东大绿冲淡到贴近白底
    onPrimary = Color(0xFFFFFFFF),
    danger = Color(0xFFD93B3B),
    success = Color(0xFF1F9E54),
    isDark = false,
)

/**
 * 深色主题。注意 `surfaceSunken` 必须比 `surface` 更暗（`#0A0B0D` < `#171A1F`），
 * 否则分段控件的选中态无法辨认。
 */
val DarkSeuColors = SeuColors(
    bg = Color(0xFF0D0F12),
    surface = Color(0xFF171A1F),
    surfaceSunken = Color(0xFF0A0B0D),
    border = Color(0xFF272D26),
    textPrimary = Color(0xFFF2F2F7),
    textSecondary = Color(0xFF99A1AD),
    textTertiary = Color(0xFF6B7380),
    primary = Color(0xFF6EA64C),     // 东大绿的深色变体：提亮，否则深底上发闷
    primarySoft = Color(0xFF1D2B15), // 东大绿压暗到贴近深底
    onPrimary = Color(0xFFFFFFFF),
    danger = Color(0xFFF26363),
    success = Color(0xFF4ADE80),
    isDark = true,
)

val LocalSeuColors = staticCompositionLocalOf { LightSeuColors }

// ---------------------------------------------------------------------------
// 课程配色（规格 2.4 的改进版）
//
// 规格给出 5 组配色并要求「课数 % 5 + 持久化」，此处改为 16 组 + 名称散列探测
// （见 domain/CourseColorAssigner）。
//
// 规格的 5 组是手工调过的 fill/bar/text 三元组；扩到 16 组意味着 48 个手写色值、
// 浅深两套共 96 个，既易抄错也难以整体调整。因此这里只定义 16 个主色（bar），
// fill/text 按统一明度关系推导：
//   浅色：fill = bar 冲淡到贴近白底；text = bar 压暗
//   深色：fill = bar 压暗到贴近深底；text = bar 提亮
// 效果与规格的 5 组同源，待维护的值减少到一个色系。
// ---------------------------------------------------------------------------

/** 16 个主色，均取中深调：既作色条，也要在白底与深底上保持足够对比度。 */
val CourseBarPalette: List<Color> = listOf(
    Color(0xFF406BE8), // 蓝
    Color(0xFF26A68F), // 青
    Color(0xFF7A5CE0), // 紫
    Color(0xFFDE8A29), // 橙
    Color(0xFFDE5C85), // 粉
    Color(0xFF1F9BA8), // 青蓝
    Color(0xFF8A6FD8), // 淡紫
    Color(0xFF5A6A7F), // 灰蓝
    Color(0xFF2E9E6B), // 绿
    Color(0xFFB06A22), // 棕橙
    Color(0xFF9A4FD0), // 品紫
    Color(0xFF4E8F3A), // 橄榄
    Color(0xFFC4517A), // 玫红
    Color(0xFF4C6791), // 石板蓝
    Color(0xFFA08028), // 金褐
    Color(0xFF35738A), // 钢青
)

/** 一组课程配色：色条 / 底色 / 文字色（对应规格的 cXBar / cXFill / cXText） */
@Immutable
data class CourseColorTriple(val bar: Color, val fill: Color, val text: Color)

private fun tripleOf(bar: Color, isDark: Boolean): CourseColorTriple = if (isDark) {
    CourseColorTriple(
        bar = bar,
        fill = lerp(bar, Color(0xFF0D0F12), 0.82f),
        text = lerp(bar, Color.White, 0.58f),
    )
} else {
    CourseColorTriple(
        bar = bar,
        fill = lerp(bar, Color.White, 0.90f),
        text = lerp(bar, Color.Black, 0.40f),
    )
}

/** 槽位（0..15）→ 配色组。槽位由 domain 的 CourseColorAssigner 决定。 */
@Composable
fun courseColorOf(slot: Int): CourseColorTriple {
    val colors = LocalSeuColors.current
    val idx = slot.mod(CourseBarPalette.size)
    return tripleOf(CourseBarPalette[idx], colors.isDark)
}

// ---------------------------------------------------------------------------
// 字号阶梯（规格 2.3）
//
// 规格要求数字用 Inter、中文用 Noto Sans SC。两者都需额外打包字体
// （Noto Sans SC 全字重超过 10 MB），与体积预算冲突，故暂用系统默认字体。
// ---------------------------------------------------------------------------

@Immutable
data class SeuType(
    val pageTitle: TextStyle,     // 一级页标题（全 App 统一，不得逐屏变化）
    val detailTitle: TextStyle,   // 课程详情页课程名
    val heroName: TextStyle,      // 今日页「下一节课」课程名
    val navTitle: TextStyle,      // 二级页导航栏标题
    val itemTitle: TextStyle,     // 列表项主标题 / 区块标题
    val body: TextStyle,          // 正文、表单值、信息行
    val caption: TextStyle,       // 副标题、字段标签
    val micro: TextStyle,         // 标签胶囊、状态文字
    val tabLabel: TextStyle,      // 底部导航文字
    val gridTitle: TextStyle,     // 周网格课块课程名
    val gridRoom: TextStyle,      // 周网格课块教室号
    val gridTime: TextStyle,      // 周网格节次栏的起止时刻（比教室号再小一号）
)

private fun style(size: Int, lineHeight: Int, weight: FontWeight) = TextStyle(
    fontFamily = FontFamily.Default,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    fontWeight = weight,
)

val SeuTypography = SeuType(
    pageTitle = style(22, 30, FontWeight.SemiBold),
    detailTitle = style(24, 32, FontWeight.Bold),
    heroName = style(19, 26, FontWeight.SemiBold),
    navTitle = style(17, 24, FontWeight.SemiBold),
    itemTitle = style(15, 21, FontWeight.Medium),
    body = style(13, 19, FontWeight.Normal),
    caption = style(12, 17, FontWeight.Normal),
    micro = style(11, 16, FontWeight.Medium),
    tabLabel = style(10, 13, FontWeight.Medium),
    gridTitle = style(10, 13, FontWeight.Medium),
    gridRoom = style(8, 11, FontWeight.Normal),
    // lineHeight 给 10sp：三行（节次号 / 开始 / 结束）合计 11+10+10 = 31sp，
    // 远小于 62dp 的行高，不会把下一行顶掉。
    gridTime = style(8, 10, FontWeight.Normal),
)

val LocalSeuType = staticCompositionLocalOf { SeuTypography }

// ---------------------------------------------------------------------------
// 圆角与间距（规格 2.5）
// ---------------------------------------------------------------------------

object SeuRadius {
    val gridBlock = 8.dp
    val listRow = 14.dp
    val card = 16.dp
    val heroCard = 20.dp
    val button = 14.dp
    val tag = 20.dp
    val navPill = 36.dp
    val navItem = 26.dp
    val segmentedTrack = 9.dp
    val segmentedThumb = 7.dp
    val weekChip = 10.dp
}

object SeuDimens {
    val tabBarHeight = 95.dp
    val tabPillHeight = 62.dp
    val tabBarHorizontal = 21.dp
    val navBarHeight = 48.dp
}

// ---------------------------------------------------------------------------

@Composable
fun SeuTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (dark) DarkSeuColors else LightSeuColors

    // MaterialTheme 只用来给 Text/Icon/Scaffold 之类的默认值兜底；
    // 本项目外观全部走 LocalSeuColors，不依赖 Material 的语义色。
    val materialColors = if (dark) {
        darkColorScheme(
            primary = colors.primary,
            onPrimary = colors.onPrimary,
            background = colors.bg,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            error = colors.danger,
        )
    } else {
        lightColorScheme(
            primary = colors.primary,
            onPrimary = colors.onPrimary,
            background = colors.bg,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            error = colors.danger,
        )
    }

    CompositionLocalProvider(
        LocalSeuColors provides colors,
        LocalSeuType provides SeuTypography,
    ) {
        MaterialTheme(colorScheme = materialColors, content = content)
    }
}
