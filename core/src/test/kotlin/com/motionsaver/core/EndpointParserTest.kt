package com.motionsaver.core

import org.junit.Assert.*
import org.junit.Test

/** 自填端点：让四类资源真正跑全的路径。 */
class EndpointParserTest {

    private val spec = EndpointSpec(
        enabled = true,
        urlTemplate = "https://api.example.com/parse?url={url}",
        headers = mapOf("X-Key" to "abc")
    )

    private val json = """
        {"title":"测试作品✨","author":"阿杰","caption":"这是正文","id":"N123456",
         "images":["https://cdn.x.com/1.jpg","https://cdn.x.com/2.png"],
         "videos":["https://cdn.x.com/v.mp4"],
         "audios":["https://cdn.x.com/bgm.mp3"],
         "animated":["https://cdn.x.com/a.gif"],
         "cover":"https://cdn.x.com/cover.jpg",
         "live_video":"https://cdn.x.com/live.mp4"}
    """.trimIndent()

    @Test fun `端点配置校验与 URL 编码`() {
        assertTrue(spec.isValid())
        assertFalse(spec.copy(enabled = false).isValid())
        assertFalse(spec.copy(urlTemplate = "https://api.example.com/parse").isValid())
        assertEquals(
            "https://api.example.com/parse?url=https%3A%2F%2Fv.douyin.com%2Fabc",
            spec.buildUrl("https://v.douyin.com/abc")
        )
    }

    @Test fun `Header 文本与 Map 互转`() {
        val text = "X-Key: abc\nAuthorization: Bearer zzz"
        val map = EndpointSpec.parseHeaders(text)
        assertEquals("abc", map["X-Key"])
        assertEquals("Bearer zzz", map["Authorization"])
        assertTrue(EndpointSpec.headersToText(map).contains("X-Key: abc"))
    }

    @Test fun `端点优先命中并映射四类资源`() {
        val registry = ParserRegistry(ParserRegistry.defaultParsers(spec))
        val p = registry.parse(
            "https://v.douyin.com/abc",
            ParserContractTest.FakeFetcher(json, "application/json")
        )
        assertEquals(Platform.GENERIC, p.platform)
        assertEquals("测试作品✨", p.title)
        assertEquals("阿杰", p.author)
        assertEquals("这是正文", p.caption)
        assertEquals(2, p.byKind(ResourceKind.IMAGE).size)
        assertEquals(1, p.videos.size)
        assertEquals(1, p.audios.size)
        assertEquals(1, p.byKind(ResourceKind.ANIMATED).size)
    }

    @Test fun `端点返回实况字段时合成动态照片`() {
        val registry = ParserRegistry(ParserRegistry.defaultParsers(spec))
        val p = registry.parse(
            "https://v.douyin.com/abc",
            ParserContractTest.FakeFetcher(json, "application/json")
        )
        assertTrue(p.isLivePhoto)
        assertEquals(MediaType.LIVE_PHOTO, p.type)
        assertNotNull(p.liveCover)
        assertNotNull(p.liveVideo)
    }

    @Test fun `端点未启用时回落到公开页解析器`() {
        val registry = ParserRegistry(ParserRegistry.defaultParsers(null))
        val p = registry.parse(
            "https://v.douyin.com/abc",
            ParserContractTest.FakeFetcher("<html><head><title>抖音</title></head></html>")
        )
        assertEquals(Platform.DOUYIN, p.platform)
    }

    @Test fun `端点返回空资源时给出告警`() {
        val registry = ParserRegistry(ParserRegistry.defaultParsers(spec))
        val p = registry.parse(
            "https://v.douyin.com/abc",
            ParserContractTest.FakeFetcher("{\"title\":\"空\"}", "application/json")
        )
        assertTrue(p.warnings.isNotEmpty())
    }
}
