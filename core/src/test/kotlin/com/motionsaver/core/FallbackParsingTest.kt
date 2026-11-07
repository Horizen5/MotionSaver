package com.motionsaver.core

import org.junit.Assert.*
import org.junit.Test

/**
 * v2.8.1「回退解析版」回归验证。
 *
 * fixture 取自 2026/11/07 真实抓取的抖音公开分享页：
 *  - douyin_audit_not_pass.html：用户本次反馈的链接，平台返回
 *    filter_reason=status_audit_not_pass、item_list=[]，页面只剩一个站点 logo。
 *  - douyin_router_images.html：同期另一个公开作品，item_list[0].images 有 4 张图。
 *
 * 目的：证明「解析链路本身没坏」，且平台明确拒绝时 App 不再误报成解析失败。
 */
class FallbackParsingTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/$name")) { "缺少测试 fixture: $name" }
            .bufferedReader(Charsets.UTF_8).readText()

    /** 记录每次实际请求的 URL，用于验证取页策略确实归一到了 note 地址。 */
    private class RecordingFetcher(
        private val html: String,
        private val finalUrl: String,
        /** 首轮模拟「短链刚跟随、SSR 还没渲染」的空壳，逼出后续策略重试。 */
        private val firstRequestIsShell: Boolean = false
    ) : HttpFetcher {
        val requested = mutableListOf<String>()
        val headersSeen = mutableListOf<Map<String, String>>()
        override fun get(url: String, headers: Map<String, String>): HttpFetcher.Response {
            requested += url
            headersSeen += headers
            val body = if (firstRequestIsShell && requested.size == 1)
                "<html><head></head><body></body></html>"
            else html
            return HttpFetcher.Response(
                code = 200,
                finalUrl = finalUrl,
                contentType = "text/html",
                body = body.toByteArray()
            )
        }
    }

    private val auditShare =
        "4.61 复制打开抖音，看看【陌沫つ゛的图文作品】# 阿哈的游戏 # 崩坏星穹铁道 # 流萤 " +
            "https://v.douyin.com/dBhPfcEmyLc/ L@W.zg 12/17 :0pm VYm:/"
    private val auditFinal =
        "https://www.iesdouyin.com/share/note/7705030121148814498/?region=CN&mid=7704350522202950441&u_code=359llk9030e9"
    private val normalShare =
        "7.66 复制打开抖音，看看【大大怪（在冬眠）的图文作品】人的本质是赌徒 https://v.douyin.com/KOni4tR0sQQ/ iPX:/ :5pm 06/24 S@y.gO"
    private val normalFinal =
        "https://www.iesdouyin.com/share/note/7697862523365800009/?region=CN&mid=7697046348918836002"

    @Test
    fun `短链与带参数地址都归一到同ID的note地址`() {
        val slides = "https://www.iesdouyin.com/share/slides/7705030121148814498/"
        val withParams = "https://www.iesdouyin.com/share/note/7705030121148814498/?region=CN&mid=7704350522202950441"
        val expected = "https://www.iesdouyin.com/share/note/7705030121148814498/"
        assertEquals(expected, ShareLinkNormalizer.canonicalShareUrl(slides))
        assertEquals(expected, ShareLinkNormalizer.noteUrlOf(slides))
        assertEquals(expected, ShareLinkNormalizer.noteUrlOf(withParams))
        assertEquals(expected, ShareLinkNormalizer.noteUrlOf(expected))
        // canonicalShareUrl 保持旧语义：普通 note / 普通网页原样保留
        assertEquals(withParams, ShareLinkNormalizer.canonicalShareUrl(withParams))
        assertEquals("https://example.com/a.jpg", ShareLinkNormalizer.noteUrlOf("https://example.com/a.jpg"))
    }

    @Test
    fun `取页策略首轮跟随短链其后优先用归一化note地址`() {
        val f = RecordingFetcher(
            fixture("douyin_router_images.html"), normalFinal, firstRequestIsShell = true
        )
        DouyinParser().parse(normalShare, f)
        assertTrue("应发起多次取页重试: ${f.requested}", f.requested.size >= 2)
        assertEquals(
            "第 2 次应命中归一化 note 地址: ${f.requested}",
            "https://www.iesdouyin.com/share/note/7697862523365800009/",
            f.requested[1]
        )
    }

    @Test
    fun `正常公开作品仍能解析出多张图且无警告`() {
        val p = DouyinParser().parse(
            normalShare, RecordingFetcher(fixture("douyin_router_images.html"), normalFinal)
        )
        assertTrue("应解析出图集: ${p.notes}", p.images.size >= 4)
        assertTrue("不该报拿不到资源: ${p.warnings}", p.warnings.isEmpty())
        assertTrue(p.images.all { it.url.startsWith("https://") })
    }

    @Test
    fun `平台审核未通过时如实报告而非误说解析失败`() {
        val p = DouyinParser().parse(
            auditShare, RecordingFetcher(fixture("douyin_audit_not_pass.html"), auditFinal)
        )
        assertTrue(p.resources.isEmpty())
        assertTrue(
            "应明确告知审核未通过: ${p.warnings}",
            p.warnings.any { it.contains("审核") }
        )
        assertTrue(
            "应解释与版本无关: ${p.warnings}",
            p.warnings.any { it.contains("与 App 版本无关") }
        )
        assertTrue(
            "不该再误报成未渲染页面壳: ${p.warnings}",
            p.warnings.none { it.contains("未渲染的页面壳") }
        )
        assertTrue(
            "notes 应记录平台过滤原因: ${p.notes}",
            p.notes.any { it.contains("status_audit_not_pass") }
        )
        // 口令里的话题仍作为作品名，不退化成「抖音作品」
        assertFalse(p.title.isBlank())
        assertFalse(p.title == "抖音作品")
    }
}
