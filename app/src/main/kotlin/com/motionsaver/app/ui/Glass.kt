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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * iOS 26「Liquid Glass」+ HyperOS「生命感美学」的零依赖静态实现。
 *
 * 调研结论（Apple HIG Materials / WWDC25 219、Haze 2.1、AGSL SDF 折射方案）：
 * 苹果的液态玻璃**不是**「均匀半透明白色填充」，而是
 *   1. 只在轮廓边缘 8~16px 的 bevel 带内折射光线，中心几乎清澈；
 *   2. 沿轮廓的镜面高光（specular），偏左上光源，随几何与交互移动；
 *   3. 掠射角更亮的菲涅尔边缘（Fresnel rim）+ 顶部内发光（inner glow）；
 *   4. 边缘极淡的色散（青 / 粉），以及把玻璃「压」在内容上的柔和阴影。
 * 旧版把整块卡片涂成 80% 白，正是用户截图里「白色方框、不融合」的根因。
 *
 * 为什么仍然不用真正的 backdrop-blur：
 *  - Compose Modifier.blur / RenderEffect 需 API 31+，Haze / AGSL 方案在低版本
 *    同样退化为 scrim；app minSdk 26，全版本一致比局部更好更重要；
 *  - 实时模糊逐帧走 GPU 离屏缓冲，滚动掉帧、增包体，与「高刷极致优化」冲突。
 *
 * 这里用 drawWithCache 一次性构建并缓存所有渐变画笔（size 不变时零重建），
 * 多层 drawRoundRect 叠加出折射 / 高光 / 色散 / 厚度：静态、零逐帧模糊、
 * 零新增依赖、R8 后仅几 KB，包体稳定在 10MB 内。
 */
object Glass {

    val BgLight = listOf(Color(0xFFF4F6FE), Color(0xFFE9EDFC), Color(0xFFF8F0FA))
    val BgDark = listOf(Color(0xFF0E111A), Color(0xFF141827), Color(0xFF1B1526))

    val Accent = Color(0xFF5B6CF0)
    val Accent2 = Color(0xFF8A6BF2)
    val AccentMint = Color(0xFF37C2B0)

    private val GlowBlue = Color(0xFF6E8BFF)
    private val GlowPurple = Color(0xFFB57BFF)
    private val GlowPink = Color(0xFFFF9EC7)
    private val GlowMint = Color(0xFF7CE0D3)
    private val DispCyan = Color(0xFF7CE0FF)
    private val DispPink = Color(0xFFFF9ED8)
    private val InnerShade = Color(0xFF5B6488)

    /** 全屏背景：柔和渐变 + 彩色光晕，形成「透过玻璃看到壁纸」的层次。 */
    fun backgroundBrush(dark: Boolean): Brush = Brush.linearGradient(
        colors = if (dark) BgDark else BgLight,
        start = Offset(0f, 0f),
        end = Offset(1400f, 2400f)
    )

    /**
     * 背景光晕层：radialGradient 天生就是柔化的光斑，等价于「已经模糊过的背景」，
     * 不需要运行时 blur；再加一道斜向焦散亮带，模拟光穿过玻璃的流动感。
     */
    @Composable
    fun GlowBackground(dark: Boolean) {
        val a = if (dark) 0.26f else 0.34f
        Box(Modifier.fillMaxSize().background(backgroundBrush(dark))) {
            Box(Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    listOf(GlowBlue.copy(alpha = a), Color.Transparent),
                    center = Offset(180f, 240f), radius = 980f
                )
            ))
            Box(Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    listOf(GlowPurple.copy(alpha = a * 0.9f), Color.Transparent),
                    center = Offset(940f, 600f), radius = 880f
                )
            ))
            Box(Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    listOf(GlowPink.copy(alpha = a * 0.75f), Color.Transparent),
                    center = Offset(300f, 1480f), radius = 940f
                )
            ))
            Box(Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    listOf(GlowMint.copy(alpha = a * 0.66f), Color.Transparent),
                    center = Offset(980f, 1980f), radius = 820f
                )
            ))
            // 斜向焦散亮带：玻璃把背景光聚成一道柔和的高光流
            Box(Modifier.fillMaxSize().background(
                Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = if (dark) 0.05f else 0.16f),
                        Color.Transparent
                    ),
                    start = Offset(0f, 520f),
                    end = Offset(1080f, 1720f)
                )
            ))
        }
    }

    /**
     * 玻璃基底：中心接近透明、只带极轻的 tint，让背景彩色光晕透出来。
     * 亮度集中在边缘（由 [liquidGlass] 的折射层负责），不再是整块白。
     */
    fun baseBrush(dark: Boolean, selected: Boolean = false): Brush = Brush.linearGradient(
        colors = when {
            selected && dark -> listOf(Color(0x3A5B72FF), Color(0x2C2B3F70))
            selected -> listOf(Color(0x6FC3D8FF), Color(0x5AA6D4FF))
            dark -> listOf(Color(0x33222B42), Color(0x24191F30))
            else -> listOf(Color(0x52FFFFFF), Color(0x30E9F2FF))
        },
        start = Offset(0f, 0f),
        end = Offset(900f, 1600f)
    )

    /** 兼容旧调用点（缩略图占位 / 消息卡）：与玻璃基底保持一致，避免再出现白块。 */
    fun surfaceBrush(dark: Boolean, selected: Boolean = false): Brush = baseBrush(dark, selected)

    private fun edgeTint(dark: Boolean): Color = if (dark) Color(0xFFB7C6FF) else Color.White

    /** 胶囊形主按钮渐变。 */
    val accentBrush: Brush = Brush.horizontalGradient(listOf(Accent, Accent2))
    val accentBrushMint: Brush = Brush.horizontalGradient(listOf(Color(0xFF3FBFAE), Accent))

    val CardShape: Shape = RoundedCornerShape(26.dp)
    val TileShape: Shape = RoundedCornerShape(20.dp)
    val PillShape: Shape = RoundedCornerShape(50)

    /**
     * 镜面高光描边：左上强、右下弱的斜向渐变，是液态玻璃辨识度最高的细节。
     * 苹果默认的「无边框」其实来自光照高光，不是描一条实线。
     */
    fun highlightBorder(dark: Boolean, selected: Boolean = false): Brush = Brush.linearGradient(
        colors = listOf(
            when {
                selected -> Color.White.copy(alpha = 0.80f)
                dark -> Color.White.copy(alpha = 0.30f)
                else -> Color.White.copy(alpha = 0.88f)
            },
            when {
                selected -> Accent.copy(alpha = 0.32f)
                dark -> Color.White.copy(alpha = 0.05f)
                else -> Color.White.copy(alpha = 0.14f)
            }
        ),
        start = Offset(0f, 0f),
        end = Offset(700f, 1200f)
    )

    fun onGlass(dark: Boolean): Color =
        if (dark) Color(0xFFF2F4FF) else Color(0xFF1C2340)

    fun onGlassSub(dark: Boolean): Color =
        if (dark) Color(0xA9C6D2F2) else Color(0x9A60709A)

    internal fun dispersionCyan(dark: Boolean): Color = DispCyan.copy(alpha = if (dark) 0.10f else 0.17f)
    internal fun dispersionPink(dark: Boolean): Color = DispPink.copy(alpha = if (dark) 0.08f else 0.14f)
    internal fun innerShade(dark: Boolean): Color = InnerShade.copy(alpha = if (dark) 0.20f else 0.11f)
    internal fun edgeColor(dark: Boolean, soft: Boolean): Color =
        edgeTint(dark).copy(alpha = if (dark) (if (soft) 0.10f else 0.30f) else (if (soft) 0.16f else 0.52f))
}

/**
 * 液态玻璃主体：多层静态渐变叠加出「边缘折射 + 镜面高光 + 色散 + 厚度」。
 * 所有画笔在 drawWithCache 中按 size 构建并缓存；无动画、无离屏缓冲、无逐帧模糊。
 */
fun Modifier.liquidGlass(
    dark: Boolean,
    selected: Boolean = false,
    shape: Shape = Glass.CardShape,
    corner: Dp = 26.dp,
    borderWidth: Dp = 1.dp
): Modifier = this
    .clip(shape)
    .background(Glass.baseBrush(dark, selected), shape)
    .drawWithCache {
        val w = size.width
        val h = size.height
        val r = minOf(corner.toPx(), h / 2f, w / 2f)
        val cr = CornerRadius(r, r)
        val maxDim = maxOf(w, h)
        val center = Offset(w / 2f, h / 2f)
        val strokeW = borderWidth.toPx()

        // 1) 折射透镜带：中心完全清澈，只在靠近轮廓处逐渐聚光
        val lens = Brush.radialGradient(
            colors = listOf(
                Color.Transparent, Color.Transparent, Color.Transparent,
                Glass.edgeColor(dark, soft = true),
                Glass.edgeColor(dark, soft = false)
            ),
            center = center,
            radius = maxDim * 0.72f
        )
        // 2) 色散：左上淡青、右下淡粉，极淡，仅在边缘可感知
        val dispA = Brush.radialGradient(
            listOf(Glass.dispersionCyan(dark), Color.Transparent),
            center = Offset(0f, 0f), radius = maxDim * 0.55f
        )
        val dispB = Brush.radialGradient(
            listOf(Glass.dispersionPink(dark), Color.Transparent),
            center = Offset(w, h), radius = maxDim * 0.55f
        )
        // 3) 底部内阴影：给玻璃「厚度」，让它像浮在内容之上的实物
        val inner = Brush.verticalGradient(
            colors = listOf(
                Color.Transparent, Color.Transparent, Color.Transparent,
                Glass.innerShade(dark)
            ),
            startY = 0f, endY = h
        )
        // 4) 镜面高光沿轮廓扫光：左上最强，右下几乎消失
        val specular = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = if (dark) 0.44f else 0.92f),
                Color.White.copy(alpha = if (dark) 0.12f else 0.30f),
                Color.White.copy(alpha = 0.02f)
            ),
            start = Offset(0f, 0f),
            end = Offset(w * 0.85f, h.coerceAtLeast(1f))
        )

        onDrawBehind {
            drawRoundRect(brush = lens, cornerRadius = cr)
            drawRoundRect(brush = dispA, cornerRadius = cr)
            drawRoundRect(brush = dispB, cornerRadius = cr)
            drawRoundRect(brush = inner, cornerRadius = cr)
            drawRoundRect(
                brush = specular,
                cornerRadius = cr,
                style = Stroke(width = strokeW * 1.7f)
            )
        }
    }
    .border(borderWidth, Glass.highlightBorder(dark, selected), shape)

/** 形状 → 圆角半径；胶囊用极大值，绘制时自动收敛到 h/2。 */
private fun cornerOf(shape: Shape): Dp = when {
    shape === Glass.PillShape -> 999.dp
    shape === Glass.TileShape -> 20.dp
    else -> 26.dp
}

/** 玻璃容器：所有既有调用点无需改动即可获得新的液态玻璃材质。 */
fun Modifier.glassSurface(
    dark: Boolean,
    selected: Boolean = false,
    shape: Shape = Glass.CardShape,
    borderWidth: Dp = 1.dp
): Modifier = this.liquidGlass(dark, selected, shape, cornerOf(shape), borderWidth)

/**
 * 液态玻璃按钮：在主题色渐变上加顶部弧形高光 + 边缘亮线，
 * 让主操作按钮与卡片是同一种材质，而不是一块实心塑料色。
 */
fun Modifier.liquidButton(
    brush: Brush,
    shape: Shape = Glass.PillShape
): Modifier = this
    .clip(shape)
    .background(brush, shape)
    .drawWithCache {
        val w = size.width
        val h = size.height
        val cr = CornerRadius(h / 2f, h / 2f)
        val topSheen = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.34f),
                Color.White.copy(alpha = 0.08f),
                Color.Transparent
            ),
            startY = 0f, endY = h * 0.62f
        )
        val rim = Brush.linearGradient(
            colors = listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.04f)),
            start = Offset(0f, 0f), end = Offset(w, h)
        )
        onDrawBehind {
            drawRoundRect(brush = topSheen, cornerRadius = cr)
            drawRoundRect(brush = rim, cornerRadius = cr, style = Stroke(width = 1.2f * density))
        }
    }

/** 便捷：把内容包进一层玻璃卡片（阴影由调用方 elevation 控制）。 */
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
