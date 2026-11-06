package com.motionsaver.core

import org.junit.Assert.*
import org.junit.Test

/**
 * 动态照片读取器回归测试。
 * 其中真机样本字段来自用户上传的 MVIMG_20261106_230518.jpg（小米 MicroVideo V1）。
 */
class MotionPhotoReaderTest {

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())

    private fun mp4(): ByteArray = ByteArray(64).also {
        it[3] = 64.toByte()
        "ftyp".toByteArray().copyInto(it, 4)
        "mp42".toByteArray().copyInto(it, 8)
    }

    /** 把一段 XMP 文本封装成合法的 JPEG APP1 段。 */
    private fun jpegWithXmp(xmp: String): ByteArray {
        val payload = MotionPhotoXmp.XMP_HEADER.toByteArray(Charsets.US_ASCII) +
            xmp.toByteArray(Charsets.UTF_8)
        val len = 2 + payload.size
        val seg = byteArrayOf(
            0xFF.toByte(), 0xE1.toByte(),
            ((len shr 8) and 0xFF).toByte(), (len and 0xFF).toByte()
        ) + payload
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + seg +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    }

    @Test
    fun `合成的动态照片能被读取器精确定位`() {
        val clip = mp4()
        val composed = MotionPhotoWriter.compose(jpeg, clip)
        val layout = requireNotNull(MotionPhotoReader.parse(composed.bytes)) { "未能解析出动态照片布局" }

        assertEquals(composed.jpegLength.toLong(), layout.videoOffset)
        assertEquals(composed.jpegLength.toLong(), layout.jpegBytes)
        assertEquals(clip.size.toLong(), layout.videoLength)
        assertEquals(composed.bytes.size.toLong(), layout.totalBytes)
        assertTrue(layout.source, layout.source.contains("MicroVideoOffset"))
        assertTrue(MotionPhotoReader.isFtypAt(composed.bytes, layout.videoOffset))

        val report = MotionPhotoVerifier.verify(composed.bytes)
        assertTrue(report.problems.joinToString(), report.valid)
    }

    @Test
    fun `XMP 带真机样本必需的 MicroVideo 标记与两套长度字段`() {
        val xmp = MotionPhotoXmp.build(1234L)
        assertTrue(xmp.contains("GCamera:MicroVideo=\"1\""))
        assertTrue(xmp.contains("GCamera:MicroVideoOffset=\"1234\""))
        assertTrue(xmp.contains("Item:Length=\"1234\""))
        assertTrue(xmp.contains("GCamera:MotionPhoto=\"1\""))
    }

    @Test
    fun `只读文件头部即可定位尾部视频`() {
        val composed = MotionPhotoWriter.compose(jpeg, mp4())
        val head = composed.bytes.copyOf(64 * 1024)
        val layout = requireNotNull(
            MotionPhotoReader.parseHeader(head, composed.bytes.size.toLong())
        ) { "头部解析失败" }
        assertEquals(composed.videoLength.toLong(), layout.videoLength)
        assertEquals(composed.jpegLength.toLong(), layout.videoOffset)
    }

    @Test
    fun `真机小米样本的 XMP 能算出正确的视频起点与长度`() {
        val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.0-jc003">
  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description rdf:about=""
        xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
        xmlns:MiCamera="http://ns.xiaomi.com/photos/1.0/camera/"
      GCamera:MicroVideoVersion="1"
      GCamera:MicroVideo="1"
      GCamera:MicroVideoOffset="2990126"
      GCamera:MicroVideoPresentationTimestampUs="910229"
      MiCamera:XMPMeta="&lt;?xml version='1.0' encoding='UTF-8' standalone='yes' ?&gt;"/>
  </rdf:RDF>
</x:xmpmeta>"""
        val head = jpegWithXmp(xmp)
        val layout = requireNotNull(
            MotionPhotoReader.parseHeader(head, 5_983_189L)
        ) { "真机样本解析失败" }
        assertEquals(2_990_126L, layout.videoLength)
        assertEquals(2_993_063L, layout.videoOffset)
        assertEquals(910_229L, layout.presentationTimestampUs)
        assertEquals("GCamera:MicroVideoOffset", layout.source)
    }

    @Test
    fun `XMP 被剥离时仍能靠 ftyp 定位`() {
        val clip = mp4()
        val bytes = jpeg + clip
        assertNull(MotionPhotoReader.parseHeader(bytes.copyOf(64 * 1024), bytes.size.toLong()))
        val layout = requireNotNull(MotionPhotoReader.parse(bytes)) { "ftyp 兜底失败" }
        assertEquals(jpeg.size.toLong(), layout.videoOffset)
        assertEquals(clip.size.toLong(), layout.videoLength)
        assertTrue(layout.source, layout.source.contains("ftyp"))
    }

    @Test
    fun `普通静态照片不会被误判成动态照片`() {
        assertNull(MotionPhotoReader.parse(jpeg))
    }

    @Test
    fun `动态照片文件名同时满足两代相册识别条件`() {
        val a = FileNameAllocator.allocateMotionPhoto("沙龙月季", emptySet())
        assertTrue(a, a.startsWith("MVIMG_"))
        assertTrue(a, a.endsWith("MP.jpg"))
        assertTrue(a.contains("沙龙月季"))

        val b = FileNameAllocator.allocateMotionPhoto("沙龙月季", setOf(a))
        assertNotEquals(a, b)
        assertTrue(b, b.startsWith("MVIMG_"))
        assertTrue(b, b.endsWith("MP.jpg"))
    }
}
