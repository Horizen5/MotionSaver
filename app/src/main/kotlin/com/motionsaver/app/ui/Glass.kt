package com.motionsaver.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * HyperOS / MIUI「生命感美学 · 柔光玻璃」+ iOS 质感毛玻璃的零依赖实现。
 *
 * 为什么不用真正的 backdrop-blur：
 *  - Compose 的 Modifier.blur / RenderEffect 需要 Android 12 (API 31+)，
 *    低版本无效；Haze 等库在低端机上也退化成半透明 scrim；
 *  - 实时高斯模糊走 GPU 逐帧渲染，滚动时明显增加负载，与「极致优化、
 *    高刷不掉帧」冲突，还会引入额外依赖、增大包体。
 * 小米官方同样在中低端机型上以静态渐变/柔光替代实时模糊。
 *
 * 因此这里用「背景彩色光晕 + 半透明渐变玻璃面 + 顶部高光描边 + 柔和大阴影」
 * 还原柔光玻璃观感：全部为静态绘制、零 GPU 模糊、零新增依赖、全版本一致，
 * 体积几乎不变（纯 Kotlin 代码，R8 后几 KB），稳定在 10MB 以内。
 */
object Glass {

    // HyperOS 4 柔和通透的浅色基底 + 蓝紫/青/粉三色光晕
    val BgLight = listOf(
        Color(0xFFF4F6FE), Color(0xFFEEF1FC), Color(0xFFF7F1FA)
    )
    val BgDark = listOf(
        Color(0xFF10131C), Color(0xFF141826), Color(0xFF1A1524)
    )

    val Accent = Color(0xFF5B6CF0)          // HyperOS 蓝紫主色
    val Accent2 = Color(0xFF8A6BF2)         // 渐变尾色
    val AccentMint = Color(0xFF37C2B0)      // 成功/青色点缀
    val GlassTintLight = Color(0xFFFFFFFF)
    val GlassTintDark = Color(0xFF1D2333)

    private val GlowBlue = Color(0xFF6E8BFF)
    private val GlowPurple = Color(0xFFB57BFF)
    private val GlowPink = Color(0xFFFF9EC7)
    private val GlowMint = Color(0xFF7CE0D3)

    /** 全屏背景：柔和渐变 + 四个大半径彩色光晕，形成「透过玻璃看到壁纸」的层次。 */
    fun backgroundBrush(dark: Boolean): Brush = Brush.linearGradient(
        colors = if (dark) BgDark else BgLight,
        start = Offset(0f, 0f),
        end = Offset(1400f, 2400f)
    )

    /**
     * 背景光晕层：放在 Scaffold 内容之下。用 radialGradient 画出天然柔化的光斑，
     * 本身就是「模糊」的，不需要运行时 blur；静态、不随滚动重绘。
     */
    @Composable
    fun GlowBackground(dark: Boolean) {
        val a = if (dark) 0.22f else 0.30f
        Box(Modifier.fillMaxSize().background(backgroundBrush(dark))) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.radialGradient(
                        colors = listOf(GlowBlue.copy(alpha = a), Color.Transparent),
                        center = Offset(180f, 260f), radius = 900f
                    )
                )
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.radialGradient(
                        colors = listOf(GlowPurple.copy(alpha = a * 0.85f), Color.Transparent),
                        center = Offset(900f, 620f), radius = 820f
                    )
                )
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.radialGradient(
                        colors = listOf(GlowPink.copy(alpha = a * 0.7f), Color.Transparent),
                        center = Offset(320f, 1500f), radius = 880f
                    )
                )
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.radialGradient(
                        colors = listOf(GlowMint.copy(alpha = a * 0.6f), Color.Transparent),
                        center = Offset(950f, 1950f), radius = 760f
                    )
                )
            )
        }
    }

    /** 玻璃卡片表面：斜向半透明渐变，顶部略亮，模拟柔光玻璃受光。 */
    fun surfaceBrush(dark: Boolean, selected: Boolean = false): Brush {
        val top = when {
            selected && dark -> Color(0x33425BE0)
            selected -> Color(0xCCEAF0FF)
            dark -> Color(0xB3212940)
            else -> Color(0xCCFFFFFF)
        }
        val bottom = when {
            selected && dark -> Color(0x2A24365E)
            selected -> Color(0xBBDCE6FF)
            dark -> Color(0x8C171C2B)
            else -> Color(0x9AF6F8FF)
        }
        return Brush.linearGradient(listOf(top, bottom))
    }

    /** 胶囊形主按钮渐变。 */
    val accentBrush: Brush = Brush.horizontalGradient(listOf(Accent, Accent2))
    val accentBrushMint: Brush = Brush.horizontalGradient(listOf(Color(0xFF3FBFAE), Accent))

    val CardShape: Shape = RoundedCornerShape(26.dp)
    val TileShape: Shape = RoundedCornerShape(20.dp)
    val PillShape: Shape = RoundedCornerShape(50)

    /** 顶部高光描边：玻璃边缘的白色细线，是「柔光玻璃」辨识度最高的细节。 */
    fun highlightBorder(dark: Boolean, selected: Boolean = false): Brush =
        Brush.linearGradient(
            listOf(
                when {
                    selected -> Accent.copy(alpha = 0.55f)
                    dark -> Color.White.copy(alpha = 0.16f)
                    else -> Color.White.copy(alpha = 0.9f)
                },
                when {
                    selected -> Accent2.copy(alpha = 0.30f)
                    dark -> Color.White.copy(alpha = 0.04f)
                    else -> Color.White.copy(alpha = 0.22f)
                }
            )
        )

    fun onGlass(dark: Boolean): Color =
        if (dark) Color(0xFFF2F4FF) else Color(0xFF1C2340)

    fun onGlassSub(dark: Boolean): Color =
        if (dark) Color(0xA9C6D2F2) else Color(0x9A60709A)
}

/** 玻璃容器：半透明渐变 + 高光描边。顶层扩展，任意 Composable 可直接 `.glassSurface()`。 */
fun Modifier.glassSurface(
    dark: Boolean,
    selected: Boolean = false,
    shape: Shape = Glass.CardShape,
    borderWidth: Dp = 1.dp
): Modifier = this
    .background(Glass.surfaceBrush(dark, selected), shape)
    .border(borderWidth, Glass.highlightBorder(dark, selected), shape)

/** 便捷：把内容包进一层玻璃卡片（含柔和阴影由调用方 elevation 控制）。 */
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
