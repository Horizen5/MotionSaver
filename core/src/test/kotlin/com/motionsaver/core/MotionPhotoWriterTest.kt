package com.motionsaver.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MotionPhotoWriterTest {

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) { "缺少测试资源 $name" }
            .use { it.readBytes() }

    @Test fun `合成结果通过独立校验器`() {
        val jpeg = resource("cover.jpg")
        val mp4 = resource("clip.mp4")
        val result = MotionPhotoWriter.compose(jpeg, mp4)

        assertEquals(mp4.size, result.videoLength)
        assertTrue("JPEG 应因插入 XMP 而变长", result.jpegLength > jpeg.size)
        assertEquals(jpeg.size + (result.jpegLength - jpeg.size) + mp4.size, result.bytes.size)

        val report = MotionPhotoVerifier.verify(result.bytes)
        assertTrue("校验失败: ${report.problems}", report.valid)
        assertEquals(mp4.size, report.videoBytes)
        assertEquals(mp4.size.toLong(), report.microVideoOffset)
        assertTrue(report.motionPhotoFlag)
        assertTrue(report.hasFtyp)
        assertTrue(report.hasEoiBeforeVideo)
    }

    @Test fun `尾部字节就是原始 MP4，未重新编码`() {
        val mp4 = resource("clip.mp4")
        val out = MotionPhotoWriter.compose(resource("cover.jpg"), mp4).bytes
        val tail = out.copyOfRange(out.size - mp4.size, out.size)
        assertArrayEquals("视频段必须与源 MP4 逐字节一致（无损）", mp4, tail)
    }

    @Test fun `JPEG 部分仍是合法 JPEG`() {
        val out = MotionPhotoWriter.compose(resource("cover.jpg"), resource("clip.mp4"))
        val jpegPart = out.bytes.copyOfRange(0, out.jpegLength)
        assertEquals(0xFF, jpegPart[0].toInt() and 0xFF)
        assertEquals(0xD8, jpegPart[1].toInt() and 0xFF)
        assertEquals(0xFF, jpegPart[jpegPart.size - 2].toInt() and 0xFF)
        assertEquals(0xD9, jpegPart[jpegPart.size - 1].toInt() and 0xFF)
    }

    @Test fun `拒绝非 MP4 输入`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            MotionPhotoWriter.compose(resource("cover.jpg"), ByteArray(64) { 0x11 })
        }
    }

    @Test fun `重复合成不产生重复 XMP 段`() {
        val jpeg = resource("cover.jpg")
        val mp4 = resource("clip.mp4")
        val first = MotionPhotoWriter.compose(jpeg, mp4).bytes
        val jpegPart = first.copyOfRange(0, first.size - mp4.size)
        val second = MotionPhotoWriter.compose(jpegPart, mp4).bytes
        val report = MotionPhotoVerifier.verify(second)
        assertTrue(report.valid)
        val count = Regex("MicroVideoOffset").findAll(
            String(second.copyOfRange(0, 8000), Charsets.ISO_8859_1)
        ).count()
        assertEquals("XMP 段应被替换而非叠加", 1, count)
    }

    @Test fun `产出样例文件供真机验证`() {
        val out = MotionPhotoWriter.compose(resource("cover.jpg"), resource("clip.mp4")).bytes
        val dest = File("/workspace/AndroidDev/MotionSaver/samples/motion_photo_sample.jpg")
        dest.writeBytes(out)
        println("样例已生成: ${dest.absolutePath} (${out.size} bytes)")
        assertTrue(MotionPhotoVerifier.verify(out).valid)
    }
}
