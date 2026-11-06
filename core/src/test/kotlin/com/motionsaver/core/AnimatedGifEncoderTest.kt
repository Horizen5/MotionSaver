package com.motionsaver.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.imageio.ImageReader

/**
 * 本地合成动图的字节级回归：用 JDK 自带 ImageIO 真正解码回多帧，
 * 证明产出的 GIF 是合法、可播放、帧数正确的文件，而不是「0 秒 / 损坏」。
 */
class AnimatedGifEncoderTest {

    private fun solid(w: Int, h: Int, argb: Int) = IntArray(w * h) { argb }

    private fun framesOf(bytes: ByteArray): List<java.awt.image.BufferedImage> {
        val reader: ImageReader = ImageIO.getImageReadersByFormatName("gif").next()
        reader.input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
        val out = mutableListOf<java.awt.image.BufferedImage>()
        for (i in 0 until reader.getNumImages(true)) out += reader.read(i)
        reader.dispose()
        return out
    }

    @Test
    fun `生成的GIF有合法头与尾且为GIF89a`() {
        val gif = AnimatedGifEncoder.encode(
            listOf(AnimatedGifEncoder.Frame(solid(16, 16, -0x10000), 16, 16, 100))
        )
        assertEquals("GIF89a", String(gif, 0, 6, Charsets.US_ASCII))
        assertEquals(0x3B.toByte(), gif.last())
        assertTrue(gif.size > 100)
    }

    @Test
    fun `多帧动图能被ImageIO逐帧解码且帧数与尺寸正确`() {
        val colors = intArrayOf(-0x10000, -0xFF0100, -0xFFFF01) // 红 / 绿 / 蓝
        val gif = AnimatedGifEncoder.encode(
            colors.map { AnimatedGifEncoder.Frame(solid(32, 24, it), 32, 24, 80) }
        )
        val frames = framesOf(gif)
        assertEquals("应有 3 帧", 3, frames.size)
        frames.forEach {
            assertEquals(32, it.width)
            assertEquals(24, it.height)
        }
        // 每帧主色应彼此不同，证明 LZW 没有把帧串成同一张
        val signatures = frames.map { it.getRGB(16, 12) }.toSet()
        assertEquals("3 帧应是 3 种不同颜色: $signatures", 3, signatures.size)
    }

    @Test
    fun `不同长宽比的帧统一画布后仍能完整解码`() {
        val gif = AnimatedGifEncoder.encode(
            listOf(
                AnimatedGifEncoder.Frame(solid(40, 20, -0x10000), 40, 20, 60),
                AnimatedGifEncoder.Frame(solid(20, 40, -0xFF0100), 20, 40, 60)
            )
        )
        val frames = framesOf(gif)
        assertEquals(2, frames.size)
        assertEquals(40, frames[0].width)
        assertEquals(20, frames[0].height)
        assertEquals(40, frames[1].width)
        assertEquals(20, frames[1].height)
    }

    @Test
    fun `大色块与渐变不会触发字典溢出且可解码`() {
        val w = 120
        val h = 90
        val gradient = IntArray(w * h) { i ->
            val x = i % w
            val r = x * 255 / w
            (0xFF shl 24) or (r shl 16) or ((255 - r) shl 8) or 0x40
        }
        val gif = AnimatedGifEncoder.encode(
            listOf(
                AnimatedGifEncoder.Frame(gradient, w, h, 100),
                AnimatedGifEncoder.Frame(IntArray(w * h) { -0x1 }, w, h, 100)
            )
        )
        assertEquals(2, framesOf(gif).size)
    }

    @Test
    fun `空帧列表被拒绝而不是产出损坏文件`() {
        assertThrows(IllegalArgumentException::class.java) { AnimatedGifEncoder.encode(emptyList()) }
    }
}
