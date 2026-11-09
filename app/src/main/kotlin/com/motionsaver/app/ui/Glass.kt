package com.motionsaver.app.ui

import androidx.compose.foundation.background
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
 * Miuix / HyperOS 系统应用材质层（对齐 LSPosed 管理器真机截图）。
 *
 * 依据：Miuix 组件库「严格遵循 Xiaomi HyperOS Design Guidelines」，
 * 结构为 SmallTitle + Card：Card 是纯白大圆角容器，内部不插分隔线、不投影、
 * 不描边；行是「标题 + 摘要 + 右侧开关 / 箭头 / 下拉值」的 BasicComponent。
 * 真机像素采样：页面底 #F7F7F7、卡片 #FFFFFF、状态卡 #DEFAE4，
 * 全页无阴影、无硬描边、无彩色渐变图标。
 *
 * 本版相对上一代「彩色渐变图标 + 大阴影 + 蓝灰渐变底」是整体回退：
 *   - 背景：纯中性浅灰（无渐变、无光晕、无彩色）；
 *   - 卡片：100% 纯白、20dp 平滑大圆角、零阴影零描边；
 *   - 强调色：只用 HyperOS 蓝（开关 / 按钮 / 分组标题），不再有小米橙；
 *   - 选中态：淡蓝实色，而非高光或玻璃。
 * 全部为静态绘制，零逐帧模糊、零新增依赖，包体保持 10MB 内。
 * 保持所有对外接口签名不变，AppUi 数十处调用点无需改动即可整体换风格。
 */
object Glass {

    /** 纯中性背景：真机采样 #F7F7F7，深浅主题均为纯色，不含渐变或光晕。 */
    val PageBg = Color(0xFFF7F7F7)
    val PageBgDark = Color(0xFF141416)
    val BgLight = listOf(PageBg, PageBg)
    val BgDark = listOf(PageBgDark, PageBgDark)

    /** 卡片 / 输入框 / 开关 / 状态卡色板（真机采样）。 */
    val CardBg = Color.White
    val CardBgDark = Color(0xFF24272E)
    val FieldBg = Color(0xFFEDEDED)
    val FieldBgDark = Color(0xFF2C2F36)
    val SwitchOff = Color(0xFFE5E5EA)
    val GreenCard = Color(0xFFDEFAE4)
    val GreenLine = Color(0xFF34C759)

    /** 只保留 HyperOS 蓝一个强调色；旧 AccentMint 接口保留但改为同色系浅蓝。 */
    val Accent = Color(0xFF3485FF)
    val Accent2 = Color(0xFF2B6FE0)
    val AccentMint = Color(0xFF6BA6FF)

    /** SmallTitle 分组标题：真机为偏蓝紫的小字。 */
    val GroupTitle = Color(0xFF4B7BF0)
    val GroupTitleDark = Color(0xFF82B0FF)

    /** 文字：主文字近黑、摘要中性灰。 */
    val TextMain = Color(0xFF1A1A1A)
    val TextMainDark = Color(0xFFF1F3F7)
    val TextSub = Color(0xFF8A8A8E)
    val TextSubDark = Color(0xFF9A9AA0)

    /** 整屏背景：单层纯色。 */
    fun backgroundBrush(dark: Boolean): Brush = Brush.verticalGradient(
        colors = if (dark) BgDark else BgLight
    )

    @Composable
    fun GlowBackground(dark: Boolean) {
        Box(Modifier.fillMaxSize().background(if (dark) PageBgDark else PageBg))
    }

    /** 卡片基底：浅色纯白 / 深色深灰；选中态淡蓝实色。 */
    fun baseBrush(dark: Boolean, selected: Boolean = false): Brush = Brush.linearGradient(
        colors = when {
            selected && dark -> listOf(Color(0xFF2B3A55), Color(0xFF2B3A55))
            selected -> listOf(Color(0xFFE8F2FF), Color(0xFFE8F2FF))
            dark -> listOf(CardBgDark, CardBgDark)
            else -> listOf(CardBg, CardBg)
        }
    )

    /** 兼容旧调用点（缩略图占位 / 消息卡）：与卡片基底同为纯色。 */
    fun surfaceBrush(dark: Boolean, selected: Boolean = false): Brush = baseBrush(dark, selected)

    /** 主按钮与次按钮统一为 HyperOS 蓝实色；名字沿用旧接口以兼容调用点。 */
    val accentBrush: Brush = Brush.horizontalGradient(listOf(Accent, Accent2))
    val accentBrushMint: Brush = Brush.horizontalGradient(listOf(Color(0xFF5B9BFF), Accent))

    /** Miuix 大圆角：卡片 20dp、磁贴 16dp、按钮胶囊。 */
    val CardShape: Shape = RoundedCornerShape(20.dp)
    val TileShape: Shape = RoundedCornerShape(16.dp)
    val PillShape: Shape = RoundedCornerShape(50)

    /** Miuix 卡片无描边：返回全透明，保留接口以兼容旧调用。 */
    @Suppress("UNUSED_PARAMETER")
    fun highlightBorder(dark: Boolean, selected: Boolean = false): Brush = Brush.linearGradient(
        colors = listOf(Color.Transparent, Color.Transparent)
    )

    fun onGlass(dark: Boolean): Color = if (dark) TextMainDark else TextMain
    fun onGlassSub(dark: Boolean): Color = if (dark) TextSubDark else TextSub
    fun groupTitle(dark: Boolean): Color = if (dark) GroupTitleDark else GroupTitle
    fun cardColor(dark: Boolean): Color = if (dark) CardBgDark else CardBg
    fun fieldColor(dark: Boolean): Color = if (dark) FieldBgDark else FieldBg
    fun pageColor(dark: Boolean): Color = if (dark) PageBgDark else PageBg

    // 以下保留旧接口以兼容潜在调用，Miuix 实色风格下均返回透明。
    @Suppress("UNUSED_PARAMETER")
    internal fun dispersionCyan(dark: Boolean): Color = Color.Transparent
    @Suppress("UNUSED_PARAMETER")
    internal fun dispersionPink(dark: Boolean): Color = Color.Transparent
    @Suppress("UNUSED_PARAMETER")
    internal fun innerShade(dark: Boolean): Color = Color.Transparent
    @Suppress("UNUSED_PARAMETER")
    internal fun edgeColor(dark: Boolean, soft: Boolean): Color = Color.Transparent
}

/** Miuix 卡片：裁剪大圆角 → 铺纯白实色底。无描边、无阴影。 */
@Suppress("UNUSED_PARAMETER")
fun Modifier.liquidGlass(
    dark: Boolean,
    selected: Boolean = false,
    shape: Shape = Glass.CardShape,
    corner: Dp = 20.dp,
    borderWidth: Dp = 0.dp
): Modifier = this
    .clip(shape)
    .background(Glass.baseBrush(dark, selected), shape)

/** 形状 → 圆角半径；胶囊用极大值。 */
private fun cornerOf(shape: Shape): Dp = when {
    shape === Glass.PillShape -> 999.dp
    shape === Glass.TileShape -> 16.dp
    else -> 20.dp
}

/** 实色容器：所有既有调用点无需改动即可获得 Miuix 白卡。 */
@Suppress("UNUSED_PARAMETER")
fun Modifier.glassSurface(
    dark: Boolean,
    selected: Boolean = false,
    shape: Shape = Glass.CardShape,
    borderWidth: Dp = 0.dp
): Modifier = this.liquidGlass(dark, selected, shape, cornerOf(shape), borderWidth)

/**
 * 输入框 / 底栏专用：Miuix 输入框是浅灰实色圆角块，无描边、无玻璃高光。
 * 旧版这里正是「白色方框不融合」的来源，现已彻底消失。
 */
@Suppress("UNUSED_PARAMETER")
fun Modifier.edgeGlass(
    dark: Boolean,
    selected: Boolean = false,
    shape: Shape = Glass.CardShape,
    borderWidth: Dp = 0.dp,
    centerTint: Color = Color.Transparent
): Modifier = this
    .clip(shape)
    .background(Glass.fieldColor(dark), shape)

/** Miuix 实心按钮：纯色填充，无顶部弧形高光、无边缘亮线。 */
fun Modifier.liquidButton(
    brush: Brush,
    shape: Shape = Glass.PillShape
): Modifier = this
    .clip(shape)
    .background(brush, shape)

/** 便捷：把内容包进一层纯白大圆角卡片（零阴影，靠白底与浅灰页底区分）。 */
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
