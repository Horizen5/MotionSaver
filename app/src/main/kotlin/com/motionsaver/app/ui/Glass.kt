package com.motionsaver.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * MIUI / HyperOS 实色风格材质层。
 *
 * 用户多轮真机反馈确认：iOS 液态玻璃那套「半透明白基底 + 折射 / 菲涅尔描边」
 * 在浅色主题下始终会显出一块与背景不融合的白色方框，底部导航还会带一圈
 * 描边色带。本版彻底放弃半透明 / 折射 / 光晕 / 色散，改为 MIUI 的标准做法：
 *   - 背景：纯浅灰（无彩色渐变、无光晕），整屏连续铺到物理底部；
 *   - 卡片：100% 实色白（深色为深灰），18dp 圆角，极淡描边 + 柔和阴影；
 *   - 主色：HyperOS 蓝，次要色：小米橙；按钮 / 开关 / 输入框统一实色；
 *   - 选中态：淡蓝实色底，而非高光玻璃。
 * 这样所有卡片边界一致、层次规整，「白框」不再是缺陷而是设计本身；
 * 底部也不再有半透明层与系统栏叠加出的分层。
 *
 * 全部为静态绘制，零逐帧模糊、零新增依赖，包体保持 10MB 内。
 * 保持所有对外接口签名不变，AppUi 数十处调用点无需改动即可整体换风格。
 */
object Glass {

    /** 纯中性背景：浅灰白 / 近黑，极淡竖向渐变只为保证接口返回 Brush，不含彩色光晕。 */
    val BgLight = listOf(Color(0xFFF6F7F9), Color(0xFFEFF1F5))
    val BgDark = listOf(Color(0xFF15171C), Color(0xFF101216))

    /** HyperOS 蓝 + 小米橙：与之前的紫罗兰玻璃明确区分。 */
    val Accent = Color(0xFF3485FF)
    val Accent2 = Color(0xFF0A6CFF)
    val AccentMint = Color(0xFFFF6900)

    /** 整屏背景：只有一层纯色，没有光晕、焦散、壁纸透视。 */
    fun backgroundBrush(dark: Boolean): Brush = Brush.verticalGradient(
        colors = if (dark) BgDark else BgLight
    )

    @Composable
    fun GlowBackground(dark: Boolean) {
        Box(Modifier.fillMaxSize().background(backgroundBrush(dark)))
    }

    /** 实色卡片基底：浅色纯白 / 深色深灰；选中态为淡蓝实色。 */
    fun baseBrush(dark: Boolean, selected: Boolean = false): Brush = Brush.linearGradient(
        colors = when {
            selected && dark -> listOf(Color(0xFF2B3A55), Color(0xFF2B3A55))
            selected -> listOf(Color(0xFFE7F1FF), Color(0xFFE7F1FF))
            dark -> listOf(Color(0xFF24272E), Color(0xFF24272E))
            else -> listOf(Color.White, Color.White)
        }
    )

    /** 兼容旧调用点（缩略图占位 / 消息卡）：与卡片基底同为实色。 */
    fun surfaceBrush(dark: Boolean, selected: Boolean = false): Brush = baseBrush(dark, selected)

    /** 主按钮（HyperOS 蓝）与次按钮（小米橙）实色渐变；名字沿用旧接口。 */
    val accentBrush: Brush = Brush.horizontalGradient(listOf(Accent, Accent2))
    val accentBrushMint: Brush = Brush.horizontalGradient(listOf(Color(0xFFFF8A3D), Color(0xFFFF6900)))

    /** MIUI 圆角：卡片 18dp、磁贴 14dp、按钮胶囊。 */
    val CardShape: Shape = RoundedCornerShape(18.dp)
    val TileShape: Shape = RoundedCornerShape(14.dp)
    val PillShape: Shape = RoundedCornerShape(50)

    /** 极淡实色描边：浅色用浅灰、深色用淡白，选中用主色；不再是白色高光。 */
    fun highlightBorder(dark: Boolean, selected: Boolean = false): Brush = Brush.linearGradient(
        colors = when {
            selected -> listOf(Accent.copy(alpha = 0.55f), Accent.copy(alpha = 0.28f))
            dark -> listOf(Color(0x1AFFFFFF), Color(0x0AFFFFFF))
            else -> listOf(Color(0x14000000), Color(0x08000000))
        }
    )

    fun onGlass(dark: Boolean): Color =
        if (dark) Color(0xFFF1F3F7) else Color(0xFF1B1E26)

    fun onGlassSub(dark: Boolean): Color =
        if (dark) Color(0xFF9AA0AC) else Color(0xFF8A9099)

    // 以下保留旧接口以兼容潜在调用，实色风格下均返回近乎不可见的中性色。
    internal fun dispersionCyan(dark: Boolean): Color = Color.Transparent
    internal fun dispersionPink(dark: Boolean): Color = Color.Transparent
    internal fun innerShade(dark: Boolean): Color = Color.Transparent
    internal fun edgeColor(dark: Boolean, soft: Boolean): Color = Color.Transparent
}

/**
 * MIUI 实色卡片：裁剪圆角 → 铺实色底 → 极淡描边。
 * 不再有任何 drawWithCache 折射 / 高光 / 色散层。
 */
fun Modifier.liquidGlass(
    dark: Boolean,
    selected: Boolean = false,
    shape: Shape = Glass.CardShape,
    corner: Dp = 18.dp,
    borderWidth: Dp = 1.dp
): Modifier = this
    .clip(shape)
    .background(Glass.baseBrush(dark, selected), shape)
    .border(borderWidth, Glass.highlightBorder(dark, selected), shape)

/** 形状 → 圆角半径；胶囊用极大值。 */
private fun cornerOf(shape: Shape): Dp = when {
    shape === Glass.PillShape -> 999.dp
    shape === Glass.TileShape -> 14.dp
    else -> 18.dp
}

/** 实色容器：所有既有调用点无需改动即可获得 MIUI 卡片。 */
fun Modifier.glassSurface(
    dark: Boolean,
    selected: Boolean = false,
    shape: Shape = Glass.CardShape,
    borderWidth: Dp = 1.dp
): Modifier = this.liquidGlass(dark, selected, shape, cornerOf(shape), borderWidth)

/**
 * 输入框 / 底栏专用：同样改为 100% 实色卡片。
 * 旧版这里「中心全透 + 边缘白色高光」正是截图里那圈白色方框的来源；
 * 现在整块是纯白圆角卡片，边界与所有其它卡片一致，彻底融合。
 * centerTint 参数保留以兼容旧调用，实色风格不使用。
 */
@Suppress("UNUSED_PARAMETER")
fun Modifier.edgeGlass(
    dark: Boolean,
    selected: Boolean = false,
    shape: Shape = Glass.CardShape,
    borderWidth: Dp = 1.dp,
    centerTint: Color = Color.Transparent
): Modifier = this
    .clip(shape)
    .background(if (dark) Color(0xFF24272E) else Color.White, shape)
    .border(borderWidth, Glass.highlightBorder(dark, selected), shape)

/** MIUI 实心按钮：纯色填充，无顶部弧形高光、无边缘亮线。 */
fun Modifier.liquidButton(
    brush: Brush,
    shape: Shape = Glass.PillShape
): Modifier = this
    .clip(shape)
    .background(brush, shape)

/** 便捷：把内容包进一层实色卡片（阴影由调用方 elevation 控制）。 */
@Composable
fun GlassBox(
    dark: Boolean,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    shape: Shape = Glass.CardShape,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier.glassSurface(dark, selected, shape),
        content = content
    )
}
