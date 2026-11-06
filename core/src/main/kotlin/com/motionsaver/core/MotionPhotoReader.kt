package com.motionsaver.core

/**
 * 解析手机「动态照片」(Motion Photo) 的物理布局：
 * 一个 .jpg 文件 = JPEG 封面（含 XMP APP1 段） + 紧随其后的 MP4 视频。
 *
 * 兼容三代索引方式：
 *  - 小米 / Google MicroVideo V1：`GCamera:MicroVideoOffset`，语义是「从文件末尾到视频起点的字节数」，
 *    也就是尾部 MP4 的长度；
 *  - Android Motion Photo 1.0（2024）：`Container:Item Item:Length`，同样是视频字节数；
 *  - XMP 被社交 App 剥掉时：直接扫描 `ftyp` box 兜底定位。
 *
 * 纯 JVM、无 Android 依赖。只做定位与校验，不解码、不重编码任何字节，因此是无损的。
 */
data class MotionPhotoLayout(
    val totalBytes: Long,
    /** JPEG 封面部分的字节数，也等于 MP4 起点相对文件开头的偏移 */
    val jpegBytes: Long,
    /** MP4 起点相对文件开头的偏移 */
    val videoOffset: Long,
    /** 内嵌 MP4 的字节数 */
    val videoLength: Long,
    /** 与静态封面相对应的视频帧时间戳（微秒），-1 表示未声明 */
    val presentationTimestampUs: Long,
    /** 定位依据，用于自检与排错 */
    val source: String
)

object MotionPhotoReader {

    private val XMP_HEADER = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)

    /** 整文件解析：适合单测、自检页，以及用户导入的动态照片样本。 */
    fun parse(bytes: ByteArray): MotionPhotoLayout? {
        val fromMeta = parseHeader(bytes, bytes.size.toLong())
        if (fromMeta != null && isFtypAt(bytes, fromMeta.videoOffset)) return fromMeta
        val scanned = scanFtyp(bytes)
        return if (scanned != null && isFtypAt(bytes, scanned.videoOffset)) scanned else null
    }

    /**
     * 只读头部元数据区解析，避免把 6MB+ 的动态照片整个读进内存。
     * [head] 需覆盖 JPEG 的 APPn 段（实测真机样本 XMP APP1 位于 25KB 处，64KB 足够）。
     */
    fun parseHeader(head: ByteArray, totalBytes: Long): MotionPhotoLayout? {
        val xmp = extractXmp(head) ?: return null
        val micro = Regex("""MicroVideoOffset="(\d+)"""").find(xmp)?.groupValues?.get(1)?.toLongOrNull()
        val item = Regex("""Item:Length="(\d+)"""").find(xmp)?.groupValues?.get(1)?.toLongOrNull()
        val len = micro ?: item ?: return null
        val source = if (micro != null) "GCamera:MicroVideoOffset" else "Container:Item:Length"
        if (len <= 0 || len >= totalBytes) return null
        val offset = totalBytes - len
        val ts = Regex("""(?:MicroVideo|MotionPhoto)PresentationTimestampUs="(-?\d+)"""")
            .find(xmp)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
        return MotionPhotoLayout(totalBytes, offset, offset, len, ts, source)
    }

    /** XMP 被剥离时，按 MP4 的 ftyp box 定位视频起点（微信等 App 转发的常见情况）。 */
    fun scanFtyp(bytes: ByteArray): MotionPhotoLayout? {
        var i = 4
        while (i + 8 <= bytes.size) {
            if (bytes[i] == 'f'.code.toByte() && bytes[i + 1] == 't'.code.toByte() &&
                bytes[i + 2] == 'y'.code.toByte() && bytes[i + 3] == 'p'.code.toByte()
            ) {
                val start = i - 4
                val size = u32(bytes, start)
                if (size >= 16 && size <= bytes.size - start) {
                    return MotionPhotoLayout(
                        bytes.size.toLong(), start.toLong(), start.toLong(),
                        (bytes.size - start).toLong(), -1L, "ftyp 扫描（XMP 缺失）"
                    )
                }
            }
            i++
        }
        return null
    }

    /** 校验指定偏移处确实是 MP4（ftyp box 头）。 */
    fun isFtypAt(bytes: ByteArray, offset: Long): Boolean {
        if (offset < 0 || offset + 12 > bytes.size) return false
        val i = offset.toInt()
        return bytes[i + 4] == 'f'.code.toByte() && bytes[i + 5] == 't'.code.toByte() &&
            bytes[i + 6] == 'y'.code.toByte() && bytes[i + 7] == 'p'.code.toByte()
    }

    /** 从 JPEG 元数据区提取 XMP APP1 段里的 XML 文本。 */
    fun extractXmp(head: ByteArray): String? {
        var pos = 2
        while (pos + 4 <= head.size) {
            if (u8(head[pos]) != 0xFF) break
            val marker = u8(head[pos + 1])
            if (marker == 0xDA || marker == 0xD9) break
            val segLen = (u8(head[pos + 2]) shl 8) or u8(head[pos + 3])
            if (segLen < 2 || pos + 2 + segLen > head.size) break
            val payload = pos + 4
            if (marker == 0xE1 && payload + XMP_HEADER.size <= head.size &&
                head.copyOfRange(payload, payload + XMP_HEADER.size).contentEquals(XMP_HEADER)
            ) {
                return String(head, payload + XMP_HEADER.size, segLen - 2 - XMP_HEADER.size, Charsets.UTF_8)
            }
            pos += 2 + segLen
            if (marker !in 0xE0..0xEF) break
        }
        return null
    }

    private fun u8(b: Byte): Int = b.toInt() and 0xFF
    private fun u32(b: ByteArray, i: Int): Long =
        (u8(b[i]).toLong() shl 24) or (u8(b[i + 1]).toLong() shl 16) or
            (u8(b[i + 2]).toLong() shl 8) or u8(b[i + 3]).toLong()
}
