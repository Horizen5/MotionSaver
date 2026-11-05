package com.motionsaver.core

import org.junit.Assert.*
import org.junit.Test

/** 覆盖用户要的四类解析：图文 / 视频 / 音频 / 动图。 */
class FourTypeParsingTest {

    private val fullPage = """
        <html><head>
        <meta property="og:title" content="周末露营记录✨">
        <meta property="og:description" content="山里的风很舒服">
        </head><body>
        <script>
        window.__DATA__ = {
          "author": "小林",
          "desc": "山里的风很舒服，记录一下这次露营",
          "imageList": ["https://cdn.example.com/img/1.jpg","https://cdn.example.com/img/2.jpg","https://cdn.example.com/img/3.webp"],
          "play_addr": "https://cdn.example.com/video/clip.mp4",
          "music_url": "https://cdn.example.com/audio/bgm.m4a",
          "animated_url": "https://cdn.example.com/gif/a.gif"
        };
        </script></body></html>
    """.trimIndent()

    private fun parse(html: String = fullPage) =
        XiaohongshuParser().parse("https://www.xiaohongshu.com/explore/abc123456", ParserContractTest.FakeFetcher(html))

    @Test fun `一次解析同时拿到四类资源`() {
        val p = parse()
        assertEquals("周末露营记录✨", p.title)
        assertEquals("小林", p.author)
        assertTrue(p.caption.contains("露营"))

        assertTrue("图文应有多张", p.byKind(ResourceKind.IMAGE).size >= 3)
        assertEquals(1, p.videos.size)
        assertEquals(1, p.audios.size)
        assertEquals(1, p.byKind(ResourceKind.ANIMATED).size)
        assertEquals(MediaType.MIXED, p.inferredType())
    }

    @Test fun `资源种类与 mime 判定正确`() {
        val p = parse()
        p.videos.forEach { assertEquals("video/mp4", it.mime) }
        p.audios.forEach { assertTrue(it.mime.startsWith("audio")) }
        p.byKind(ResourceKind.ANIMATED).forEach { assertEquals("image/gif", it.mime) }
        assertTrue(p.byKind(ResourceKind.IMAGE).all { it.mime.startsWith("image") })
    }

    @Test fun `图集带序号用于命名`() {
        val p = parse()
        val images = p.byKind(ResourceKind.IMAGE)
        assertTrue(images.all { it.index > 0 })
        assertEquals(1, images.first().index)
    }

    @Test fun `纯图集识别为图文类型`() {
        val html = """
            <html><head><meta property="og:title" content="九宫格"></head><body>
            <script>{"imageList":["https://cdn.x.com/1.jpg","https://cdn.x.com/2.jpg","https://cdn.x.com/3.png"]}</script>
            </body></html>
        """.trimIndent()
        val p = parse(html)
        assertEquals(MediaType.IMAGE_TEXT, p.inferredType())
        assertEquals(3, p.byKind(ResourceKind.IMAGE).size)
    }

    @Test fun `纯音频识别为音频类型`() {
        val html = """
            <html><head><meta property="og:title" content="原声 BGM"></head><body>
            <script>{"music_url":"https://cdn.x.com/audio/bgm.m4a"}</script>
            </body></html>
        """.trimIndent()
        val p = parse(html)
        assertEquals(MediaType.AUDIO, p.inferredType())
        assertEquals(1, p.audios.size)
    }

    @Test fun `纯动图识别为动图类型`() {
        val html = """
            <html><head><meta property="og:title" content="可爱动图"></head><body>
            <script>{"gif_url":"https://cdn.x.com/a.gif"}</script>
            </body></html>
        """.trimIndent()
        val p = parse(html)
        assertEquals(MediaType.ANIMATED, p.inferredType())
    }

    @Test fun `HTML 实体与 JSON 反斜杠转义都能还原`() {
        val html = """
            <html><head><meta property="og:title" content="转义测试"></head><body>
            <script>{"url_list":["https:\/\/cdn.x.com\/p.jpg?a=1&amp;b=2"]}</script>
            </body></html>
        """.trimIndent()
        val p = parse(html)
        assertTrue(
            "实际: ${p.resources.map { it.url }}",
            p.resources.any { it.url.contains("cdn.x.com/p.jpg") && it.url.contains("a=1&b=2") }
        )
    }
}
