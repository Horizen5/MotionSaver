package com.motionsaver.core

import org.junit.Assert.*
import org.junit.Test

class MediaTypeDetectorTest {

    @Test fun `按 URL 判定四种资源`() {
        assertEquals(ResourceKind.VIDEO, MediaTypeDetector.kindOf("https://x.com/a.mp4"))
        assertEquals(ResourceKind.VIDEO, MediaTypeDetector.kindOf("https://x.com/play?id=1", "video/mp4"))
        assertEquals(ResourceKind.AUDIO, MediaTypeDetector.kindOf("https://x.com/bgm.m4a"))
        assertEquals(ResourceKind.AUDIO, MediaTypeDetector.kindOf("https://x.com/a.mp3"))
        assertEquals(ResourceKind.ANIMATED, MediaTypeDetector.kindOf("https://x.com/a.gif"))
        assertEquals(ResourceKind.IMAGE, MediaTypeDetector.kindOf("https://x.com/a.jpg"))
        assertEquals(ResourceKind.IMAGE, MediaTypeDetector.kindOf("https://x.com/a.webp"))
    }

    @Test fun `mime 与扩展名一致`() {
        assertEquals("video/mp4", MediaTypeDetector.mimeOf("https://x.com/a.mp4"))
        assertEquals("audio/mp4", MediaTypeDetector.mimeOf("https://x.com/a.m4a"))
        assertEquals("audio/mpeg", MediaTypeDetector.mimeOf("https://x.com/a.mp3"))
        assertEquals("image/gif", MediaTypeDetector.mimeOf("https://x.com/a.gif"))
        assertEquals("image/png", MediaTypeDetector.mimeOf("https://x.com/a.png"))
    }

    @Test fun `URL 合法性判定`() {
        assertTrue(MediaTypeDetector.isHttp("https://x.com/a"))
        assertTrue(MediaTypeDetector.isHttp("http://x.com/a"))
        assertFalse(MediaTypeDetector.isHttp("//x.com/a"))
    }

    @Test fun `去跟踪参数保留查询主体`() {
        val u = "https://cdn.x.com/a.jpg?x-oss-process=image/resize,w_500&timestamp=123&t=9"
        val cleaned = MediaTypeDetector.stripTracking(u)
        assertTrue(cleaned.contains("x-oss-process"))
        assertFalse(cleaned.contains("timestamp"))
    }
}
