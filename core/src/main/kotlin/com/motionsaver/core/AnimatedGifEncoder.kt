package com.motionsaver.core

import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * 纯 JVM 的 GIF89a 动画编码器：把若干 ARGB 帧编码成一张无限循环播放的动图。
 *
 * 存在的理由：抖音「实况图 / 图文图集」的公开分享页只暴露静态帧与原声，
 * 动态视频片段（MP4）不在公开数据里（实测 play_addr 为 ftypM4A 纯音频）。
 * 因此动图由设备本地、用公开页合法取得的静态帧合成轮播 GIF——任何相册
 * 都能直接查看播放，且字节 100% 可控，不会出现「0 秒 / 文件损坏」。
 *
 * 不依赖 Android 或任何第三方库，可在 JVM 单测里用 ImageIO 读回逐帧校验。
 * 颜色量化：6×6×6 色立方 + Floyd–Steinberg 误差扩散，照片观感明显优于纯色阶。
 */
object AnimatedGifEncoder {

    /** @param pixels ARGB_8888 打包像素，长度须为 width*height；@param delayCs 帧时长（1/100 秒） */
    data class Frame(
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        val delayCs: Int = 120
    )

    /** 编码为 GIF89a 字节流。所有帧统一到首帧画布尺寸，不同比例者居中留边。 */
    fun encode(frames: List<Frame>): ByteArray {
        require(frames.isNotEmpty()) { "至少需要一帧" }
        val w = frames.first().width
        val h = frames.first().height
        require(w > 0 && h > 0) { "帧尺寸非法" }
        require(frames.all { it.pixels.size >= it.width * it.height }) { "像素数组长度不足" }

        val normalized = frames.map {
            if (it.width == w && it.height == h) it
            else Frame(fit(it, w, h), w, h, it.delayCs)
        }

        val out = ByteArrayOutputStream()
        ascii(out, "GIF89a")
        u16(out, w); u16(out, h)
        out.write(0xF7) // 全局色表 = 1；色深 7；未排序；尺寸 7 → 256 项
        out.write(0)    // 背景色索引
        out.write(0)    // 像素宽高比

        // 全局色表：前 216 项为 6×6×6 色立方，其余补灰阶，凑满 256 色
        for (r in 0 until 6) for (g in 0 until 6) for (b in 0 until 6) {
            out.write(r * 51); out.write(g * 51); out.write(b * 51)
        }
        for (i in 216 until 256) {
            val v = ((i - 216) * 255 / 39).coerceIn(0, 255)
            out.write(v); out.write(v); out.write(v)
        }

        // Netscape 扩展：无限循环
        out.write(0x21); out.write(0xFF); out.write(0x0B)
        ascii(out, "NETSCAPE2.0")
        out.write(0x03); out.write(0x01); u16(out, 0)
        out.write(0x00)

        for (f in normalized) {
            val indexed = quantize(f.pixels, w, h)

            // Graphic Control Extension：不处置前一帧，不透明
            out.write(0x21); out.write(0xF9); out.write(0x04)
            out.write(0x04)
            u16(out, f.delayCs.coerceAtLeast(2))
            out.write(0)
            out.write(0x00)

            // Image Descriptor：无局部色表、不隔行
            out.write(0x2C)
            u16(out, 0); u16(out, 0); u16(out, w); u16(out, h)
            out.write(0x00)

            out.write(8) // LZW 最小码长
            val lzw = lzwEncode(indexed)
            var p = 0
            while (p < lzw.size) {
                val n = minOf(255, lzw.size - p)
                out.write(n)
                out.write(lzw, p, n)
                p += n
            }
            out.write(0x00) // 子块结束
        }
        out.write(0x3B) // Trailer
        return out.toByteArray()
    }

    /** 把不同尺寸的帧等比缩放并居中贴到目标画布，避免 GIF 各帧尺寸不一致。 */
    private fun fit(f: Frame, w: Int, h: Int): IntArray {
        val dst = IntArray(w * h)
        val scale = minOf(w.toFloat() / f.width, h.toFloat() / f.height)
        val sw = maxOf(1, (f.width * scale).toInt())
        val sh = maxOf(1, (f.height * scale).toInt())
        val ox = (w - sw) / 2
        val oy = (h - sh) / 2
        for (y in 0 until sh) {
            val sy = (y * f.height / sh).coerceIn(0, f.height - 1)
            for (x in 0 until sw) {
                val sx = (x * f.width / sw).coerceIn(0, f.width - 1)
                dst[(oy + y) * w + ox + x] = f.pixels[sy * f.width + sx]
            }
        }
        return dst
    }

    /**
     * 6×6×6 色立方最近色量化 + Floyd–Steinberg 抖动。
     * 色立方是规则网格，最近色可用 O(1) 除法算出，无需遍历 256 色调色板。
     */
    private fun quantize(argb: IntArray, w: Int, h: Int): ByteArray {
        val idx = ByteArray(w * h)
        val errR = FloatArray(w * h)
        val errG = FloatArray(w * h)
        val errB = FloatArray(w * h)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = y * w + x
                val a = (argb[p] ushr 24) and 0xFF
                var r = (((argb[p] ushr 16) and 0xFF).toFloat() + errR[p])
                var g = (((argb[p] ushr 8) and 0xFF).toFloat() + errG[p])
                var b = ((argb[p] and 0xFF).toFloat() + errB[p])
                if (a < 16) { r = 0f; g = 0f; b = 0f } // 透明按黑处理
                val ri = clampF(r); val gi = clampF(g); val bi = clampF(b)

                val lr = (ri / 51f).roundToInt().coerceIn(0, 5)
                val lg = (gi / 51f).roundToInt().coerceIn(0, 5)
                val lb = (bi / 51f).roundToInt().coerceIn(0, 5)
                idx[p] = (lr * 36 + lg * 6 + lb).toByte()

                val dr = ri - lr * 51f
                val dg = gi - lg * 51f
                val db = bi - lb * 51f
                spread(errR, errG, errB, w, h, x + 1, y, dr, dg, db, 7f / 16)
                spread(errR, errG, errB, w, h, x - 1, y + 1, dr, dg, db, 3f / 16)
                spread(errR, errG, errB, w, h, x, y + 1, dr, dg, db, 5f / 16)
                spread(errR, errG, errB, w, h, x + 1, y + 1, dr, dg, db, 1f / 16)
            }
        }
        return idx
    }

    private fun clampF(v: Float): Float = if (v < 0f) 0f else if (v > 255f) 255f else v

    private fun spread(
        er: FloatArray, eg: FloatArray, eb: FloatArray, w: Int, h: Int,
        x: Int, y: Int, dr: Float, dg: Float, db: Float, factor: Float
    ) {
        if (x < 0 || x >= w || y < 0 || y >= h) return
        val p = y * w + x
        er[p] += dr * factor; eg[p] += dg * factor; eb[p] += db * factor
    }

    /**
     * GIF 变体 LZW：码流按 LSB-first 位序打包，码长从 9 位增长到 12 位，
     * 字典满 4096 时发 ClearCode 重置。采用 GIF 标准的 early-change 规则。
     */
    private fun lzwEncode(pixels: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream(pixels.size / 2 + 64)
        val bw = BitWriter(bos)
        val CLEAR = 256
        val EOI = 257

        var codeSize = 9
        var next = 258
        var dict = HashMap<Int, Int>(4096)

        bw.write(CLEAR, codeSize)
        var prefix = pixels[0].toInt() and 0xFF
        for (i in 1 until pixels.size) {
            val c = pixels[i].toInt() and 0xFF
            val key = (prefix shl 8) or c
            val found = dict[key]
            if (found != null) {
                prefix = found
                continue
            }
            bw.write(prefix, codeSize)
            if (next < 4096) {
                dict[key] = next++
                // early change：可用码超过当前码长上限时，下一个码起加一位
                if (next >= (1 shl codeSize) && codeSize < 12) codeSize++
            } else {
                bw.write(CLEAR, codeSize)
                dict.clear()
                next = 258
                codeSize = 9
            }
            prefix = c
        }
        bw.write(prefix, codeSize)
        bw.write(EOI, codeSize)
        bw.flush()
        return bos.toByteArray()
    }

    /** GIF 位流：低位在前，累积到整字节才输出。 */
    private class BitWriter(private val out: ByteArrayOutputStream) {
        private var acc = 0
        private var bits = 0

        fun write(code: Int, n: Int) {
            acc = acc or (code shl bits)
            bits += n
            while (bits >= 8) {
                out.write(acc and 0xFF)
                acc = acc ushr 8
                bits -= 8
            }
        }

        fun flush() {
            if (bits > 0) {
                out.write(acc and 0xFF)
                acc = 0
                bits = 0
            }
        }
    }

    private fun u16(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
    }

    private fun ascii(out: ByteArrayOutputStream, s: String) {
        for (c in s) out.write(c.code)
    }
}
