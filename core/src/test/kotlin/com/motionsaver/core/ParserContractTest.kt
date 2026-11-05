package com.motionsaver.core

import org.junit.Assert.*
import org.junit.Test

class ParserContractTest {

    class FakeFetcher(
        private val body: String,
        private val contentType: String = "text/html"
    ) : HttpFetcher {
        override fun get(url: String, headers: Map<String, String>) =
            HttpFetcher.Response(200, url, contentType, body.toByteArray())
    }

    @Test fun `从分享口令中抽出链接`() {
        val text = "8.94 复制打开抖音，看看【三融在东汶的作品】好喜欢沉浸在这种氛围 https://v.douyin.com/iABCdef/ 复制此链接"
        assertEquals("https://v.douyin.com/iABCdef/", ShareText.extractUrl(text))
    }

    @Test fun `平台识别与通用兜底`() {
        assertEquals(Platform.DOUYIN, Platform.from("https://v.douyin.com/abc"))
        assertEquals(Platform.XIAOHONGSHU, Platform.from("https://www.xiaohongshu.com/explore/123"))
        assertEquals(Platform.GENERIC, Platform.from("https://example.com"))
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
        assertEquals("好喜欢沉浸在这种氛围里✨🌊", parsed.title)
        assertEquals(
            "好喜欢沉浸在这种氛围里.mp4",
            FileNameAllocator.allocate(parsed.title, "mp4", emptySet())
        )
        assertTrue(parsed.videos.isNotEmpty())
        assertTrue(parsed.warnings.isEmpty())
    }

    @Test fun `实况图解析为动态照片并带封面与动态片段`() {
        val html = """
            <html><head><meta property="og:title" content="海边的风"></head><body>
            <script>{"live_photo":true,"cover":"https://cdn.example.com/cover.jpg","video_url":"https://cdn.example.com/live.mp4"}</script>
            </body></html>
        """.trimIndent()
        val parsed = DouyinParser().parse("https://v.douyin.com/abc", FakeFetcher(html))
        assertEquals(MediaType.LIVE_PHOTO, parsed.type)
        assertTrue(parsed.isLivePhoto)
        assertNotNull(parsed.liveCover)
        assertNotNull(parsed.liveVideo)
        assertTrue(parsed.notes.any { it.contains("实况图") })
    }

    @Test fun `公开页无直链时给出明确警告而非伪造数据`() {
        val html = """<html><head><meta property="og:title" content="作品"></head></html>"""
        val parsed = DouyinParser().parse("https://v.douyin.com/abc", FakeFetcher(html))
        assertTrue(parsed.resources.isEmpty())
        assertTrue(parsed.warnings.any { it.contains("没有暴露") })
        assertTrue(parsed.warnings.any { it.contains("解析端点") })
    }

    @Test fun `任意网页由通用解析器兜底`() {
        val html = """
            <html><head><meta property="og:title" content="一篇图文">
            <meta property="og:image" content="https://cdn.example.com/pic.jpg"></head></html>
        """.trimIndent()
        val parsed = ParserRegistry().parse("https://example.com/post/1", FakeFetcher(html))
        assertEquals(Platform.GENERIC, parsed.platform)
        assertEquals(MediaType.IMAGE_TEXT, parsed.type)
        assertTrue(parsed.images.isNotEmpty())
    }

    @Test fun `注册表可插拔`() {
        val custom = object : MediaParser {
            override val platform = Platform.UNKNOWN
            override fun canHandle(s: String) = s.contains("custom")
            override fun parse(s: String, f: HttpFetcher) =
                ParsedMedia(
                    Platform.UNKNOWN, MediaType.VIDEO, "自定义",
                    resources = listOf(MediaResource("http://x/v.mp4", "video/mp4", ResourceKind.VIDEO))
                )
        }
        val registry = ParserRegistry(listOf(custom))
        assertEquals("自定义", registry.parse("custom link", FakeFetcher("")).title)
    }
}
