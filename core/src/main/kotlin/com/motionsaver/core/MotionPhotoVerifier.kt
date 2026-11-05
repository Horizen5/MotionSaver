package com.motionsaver.core

import java.nio.charset.StandardCharsets

/**
 * 独立解析并校验一个 Motion Photo 文件（不依赖合成器实现，用于自检页与单元测试）。
 */
object MotionPhotoVerifier {

    data class Report(
        val valid: Boolean,
        val problems: List<String>,
        val totalBytes: Int,
        val jpegBytes: Int,
        val videoBytes: Int,
        val microVideoOffset: Long?,
        val motionPhotoFlag: Boolean,
        val hasFtyp: Boolean,
        val hasEoiBeforeVideo: Boolean,
        val xmpPreview: String
    )

    fun verify(bytes: ByteArray): Report {
        val problems = mutableListOf<String>()
        if (bytes.size < 16) return Report(false, listOf("文件过短"), bytes.size, 0, 0, null, false, false, false, "")
        if (!(u8(bytes[0]) == 0xFF && u8(bytes[1]) == 0xD8)) problems.add("缺少 JPEG SOI 标记")

        // 扫描元数据区，定位 XMP APP1
        var xmpText = ""
        var pos = 2
        while (pos + 4 <= bytes.size) {
            if (u8(bytes[pos]) != 0xFF) break
            val marker = u8(bytes[pos + 1])
            if (marker == 0xDA || marker == 0xD9) break
            val segLen = (u8(bytes[pos + 2]) shl 8) or u8(bytes[pos + 3])
            if (segLen < 2 || pos + 2 + segLen > bytes.size) break
            val payloadStart = pos + 4
            if (marker == 0xE1 && payloadStart + HEADER.size <= bytes.size &&
                bytes.copyOfRange(payloadStart, payloadStart + HEADER.size).contentEquals(HEADER)
            ) {
                xmpText = String(
                    bytes, payloadStart + HEADER.size, segLen - 2 - HEADER.size,
                    StandardCharsets.UTF_8
                )
            }
            pos += 2 + segLen
            if (marker !in 0xE0..0xEF) break
        }

        if (xmpText.isEmpty()) problems.add("未找到 XMP 元数据段")

        val microOffset = Regex("""MicroVideoOffset="(\d+)"""").find(xmpText)
            ?.groupValues?.get(1)?.toLongOrNull()
        val motionFlag = Regex("""MotionPhoto="1"""").containsMatchIn(xmpText)
        val itemLength = Regex("""Item:Length="(\d+)"""").find(xmpText)
            ?.groupValues?.get(1)?.toLongOrNull()

        if (microOffset == null) problems.add("XMP 缺少 MicroVideoOffset")
        if (!motionFlag) problems.add("XMP 缺少 MotionPhoto 标记")

        val videoBytes = microOffset ?: 0L
        val jpegEnd = bytes.size - videoBytes.toInt()
        val hasFtyp = jpegEnd >= 0 && jpegEnd + 12 <= bytes.size &&
            MotionPhotoWriter.isFtyp(bytes.copyOfRange(jpegEnd, bytes.size))
        val hasEoi = jpegEnd >= 2 && u8(bytes[jpegEnd - 2]) == 0xFF && u8(bytes[jpegEnd - 1]) == 0xD9

        if (videoBytes <= 0 || jpegEnd <= 0) problems.add("视频偏移超出文件范围")
        if (!hasFtyp) problems.add("偏移位置不是合法 MP4（缺 ftyp）")
        if (!hasEoi) problems.add("JPEG 段结尾缺少 EOI 标记")
        if (itemLength != null && itemLength != videoBytes) {
            problems.add("Item:Length($itemLength) 与 MicroVideoOffset($videoBytes) 不一致")
        }

        return Report(
            valid = problems.isEmpty(),
            problems = problems,
            totalBytes = bytes.size,
            jpegBytes = jpegEnd,
            videoBytes = videoBytes.toInt(),
            microVideoOffset = microOffset,
            motionPhotoFlag = motionFlag,
            hasFtyp = hasFtyp,
            hasEoiBeforeVideo = hasEoi,
            xmpPreview = xmpText.take(400)
        )
    }

    private fun u8(b: Byte): Int = b.toInt() and 0xFF
    private val HEADER: ByteArray =
        MotionPhotoXmp.XMP_HEADER.toByteArray(StandardCharsets.US_ASCII)
}
