package com.motionsaver.core

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * 合成小米 / Google 标准「动态照片」：单个 .jpg 文件 = JPEG(含 XMP 元数据) + 尾部追加 MP4。
 *
 * 无损：JPEG 与 MP4 的原始字节都不重新编码，仅做容器级拼接，画质零损失。
 * 相册读取 XMP 中的 MicroVideoOffset，从文件末尾切出视频段并播放。
 */
object MotionPhotoWriter {

    private const val SOI = 0xD8
    private const val APP1 = 0xE1
    private const val MAX_SEGMENT = 65533 // 2 字节长度字段 - 2

    /** 合成结果。 */
    data class Result(
        val bytes: ByteArray,
        val jpegLength: Int,
        val videoLength: Int,
        val xmpOffset: Int
    )

    /**
     * @param jpeg 静态封面 JPEG 原始字节（须为合法 JPEG）
     * @param mp4  动态片段 MP4 原始字节（须以 ftyp box 开头）
     */
    fun compose(jpeg: ByteArray, mp4: ByteArray, presentationTimestampUs: Long = 0L): Result {
        require(jpeg.size >= 4 && u8(jpeg[0]) == 0xFF && u8(jpeg[1]) == SOI) {
            "封面不是合法 JPEG（缺少 SOI 标记）"
        }
        require(mp4.size >= 12) { "MP4 数据过短" }
        require(isFtyp(mp4)) { "动态片段不是合法 MP4（缺少 ftyp box）" }

        val xmp = MotionPhotoXmp.build(mp4.size.toLong(), presentationTimestampUs)
            .toByteArray(StandardCharsets.UTF_8)
        val segmentPayload = XMP_HEADER_BYTES + xmp
        require(segmentPayload.size <= MAX_SEGMENT) { "XMP 段超过 JPEG APP1 上限" }

        val jpegWithXmp = insertOrReplaceXmpApp1(jpeg, segmentPayload)

        val out = ByteArray(jpegWithXmp.size + mp4.size)
        System.arraycopy(jpegWithXmp, 0, out, 0, jpegWithXmp.size)
        System.arraycopy(mp4, 0, out, jpegWithXmp.size, mp4.size)

        return Result(out, jpegWithXmp.size, mp4.size, XMP_HEADER_BYTES.size)
    }

    /** 把 XMP APP1 段插到 SOI 之后；若原 JPEG 已有 XMP APP1 则替换之，避免重复元数据。 */
    private fun insertOrReplaceXmpApp1(jpeg: ByteArray, payload: ByteArray): ByteArray {
        val segments = mutableListOf<Pair<Int, Int>>() // start,end(exclusive) of each APPn segment after SOI
        var pos = 2
        while (pos + 4 <= jpeg.size) {
            if (u8(jpeg[pos]) != 0xFF) break
            val marker = u8(jpeg[pos + 1])
            if (marker == 0xDA || marker == 0xD9) break // SOS / EOI，元数据区结束
            val segLen = (u8(jpeg[pos + 2]) shl 8) or u8(jpeg[pos + 3])
            if (segLen < 2 || pos + 2 + segLen > jpeg.size) break
            segments.add(pos to (pos + 2 + segLen))
            pos += 2 + segLen
            // APPn 只出现在元数据区，遇到非 APPn 就停止扫描
            if (marker !in 0xE0..0xEF) break
        }

        val existingXmp = segments.firstOrNull { (s, e) ->
            u8(jpeg[s + 1]) == APP1 &&
                e - (s + 4) >= XMP_HEADER_BYTES.size &&
                jpeg.copyOfRange(s + 4, s + 4 + XMP_HEADER_BYTES.size)
                    .contentEquals(XMP_HEADER_BYTES)
        }

        val newSegment = ByteArray(4 + payload.size).also { seg ->
            seg[0] = 0xFF.toByte()
            seg[1] = APP1.toByte()
            val len = 2 + payload.size
            seg[2] = ((len shr 8) and 0xFF).toByte()
            seg[3] = (len and 0xFF).toByte()
            System.arraycopy(payload, 0, seg, 4, payload.size)
        }

        val bos = ByteArrayOutputStream(jpeg.size + newSegment.size)
        if (existingXmp != null) {
            val (s, e) = existingXmp
            bos.write(jpeg, 0, s)
            bos.write(newSegment, 0, newSegment.size)
            bos.write(jpeg, e, jpeg.size - e)
        } else {
            bos.write(jpeg, 0, 2) // SOI
            bos.write(newSegment, 0, newSegment.size)
            bos.write(jpeg, 2, jpeg.size - 2)
        }
        return bos.toByteArray()
    }

    fun isFtyp(mp4: ByteArray): Boolean =
        mp4.size >= 12 &&
            mp4[4].toInt().toChar() == 'f' && mp4[5].toInt().toChar() == 't' &&
            mp4[6].toInt().toChar() == 'y' && mp4[7].toInt().toChar() == 'p'

    private fun u8(b: Byte): Int = b.toInt() and 0xFF

    private val XMP_HEADER_BYTES: ByteArray =
        MotionPhotoXmp.XMP_HEADER.toByteArray(StandardCharsets.US_ASCII)
}
