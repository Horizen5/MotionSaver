package com.motionsaver.core

import org.junit.Assert.*
import org.junit.Test

class ParserContractTest {

    private class FakeFetcher(private val html: String) : HttpFetcher {
        override fun get(url: String, headers: Map<String, String>) =
            HttpFetcher.Response(200, url, "text/html", html.toByteArray())
    }

    @Test fun `从分享口令中抽出链接`() {
        val text = "8.94 复制打开抖音，看看【三融在东汶的作品】好喜欢沉浸在这种氛围 https://v.douyin.com/iABCdef/ 复制此链接"
        assertEquals("https://v.douyin.com/iABCdef/", ShareText.extractUrl(text))
    }

    @Test fun `平台识别`() {
        assertEquals(Platform.DOUYIN, Platform.from("https://v.douyin.com/abc"))
        assertEquals(Platform.XIAOHONGSHU, Platform.from("https://www.xiaohongshu.com/explore/123"))
        assertEquals(Platform.UNKNOWN, Platform.from("https://example.com"))
    }

    @Test fun `抖音公开页解析出标题并按标题命名`() {
        val html = """
            <html><head>
            <meta property="og:title" content="好喜欢沉浸在这种氛围里✨🌊">
            <meta property="og:image" content="https://p.douyin.com/cover.jpg">
            <meta property="og:video" content="https://v.douyin.com/raw/video.mp4">
            <title>抖音</title></head><body></body></html>
        """.trimIndent()
        val parsed = ParserRegistry().parse(
            "复制打开抖音 https://v.douyin.com/iABCdef/", FakeFetcher(html)
        )
        assertEquals(Platform.DOUYIN, parsed.platform)
        assertEquals(MediaType.VIDEO, parsed.type)
        // 解析层保留原始标题（含 emoji），清洗只发生在生成文件名时
        assertEquals("好喜欢沉浸在这种氛围里✨🌊", parsed.title)
        assertEquals("好喜欢沉浸在这种氛围里.mp4", FileNameAllocator.allocate(parsed.title, "mp4", emptySet()))
        assertTrue(parsed.warnings.isEmpty())
    }

    @Test fun `实况图解析为 LIVE_PHOTO 并带封面与动态片段`() {
        val html = """
            <html><head><meta property="og:title" content="海边的风"></head><body>
            <script>{"cover":"https://cdn.example.com/cover.jpg","video_url":"https://cdn.example.com/live.mp4"}</script>
            </body></html>
        """.trimIndent()
        val parsed = DouyinParser().parse("https://v.douyin.com/abc", FakeFetcher(html))
        assertEquals(MediaType.LIVE_PHOTO, parsed.type)
        assertTrue(parsed.isLivePhoto)
        assertNotNull(parsed.liveCover)
        assertNotNull(parsed.liveVideo)
    }

    @Test fun `公开页无直链时给出明确警告而非伪造`() {
        val html = """<html><head><meta property="og:title" content="作品"></head></html>"""
        val parsed = DouyinParser().parse("https://v.douyin.com/abc", FakeFetcher(html))
        assertEquals(MediaType.IMAGES, parsed.type)
        assertTrue(parsed.warnings.any { it.contains("未暴露视频直链") })
    }

    @Test fun `不支持的平台报清晰错误`() {
        val e = org.junit.Assert.assertThrows(ParseException::class.java) {
            ParserRegistry().parse("https://example.com/x", FakeFetcher("<html></html>"))
        }
        assertTrue(e.message!!.contains("暂不支持"))
    }

    @Test fun `注册表可插拔`() {
        val custom = object : MediaParser {
            override val platform = Platform.UNKNOWN
            override fun canHandle(s: String) = s.contains("custom")
            override fun parse(s: String, f: HttpFetcher) =
                ParsedMedia(Platform.UNKNOWN, MediaType.VIDEO, "自定义", resources = listOf(MediaResource("http://x/v.mp4", "video/mp4")))
        }
        val registry = ParserRegistry(listOf(custom))
        assertEquals("自定义", registry.parse("custom link", FakeFetcher("")).title)
    }
}
