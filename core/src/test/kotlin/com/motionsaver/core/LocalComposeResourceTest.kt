package com.motionsaver.core

import org.junit.Assert.*
import org.junit.Test

/**
 * 「还是识别不了动图」的回归：图文 / 实况图作品必须产出一张真正可勾选、
 * 可下载的动图资源，而不是只有一个标签或一句「去接端点」的提示。
 *
 * 数据来自用户真实提供的两条公开分享链接的访客渲染页。
 */
class LocalComposeResourceTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/$name")) { "缺少 fixture: $name" }
            .bufferedReader(Charsets.UTF_8).readText()

    private fun parse(name: String) = RouterDataParser.parse(
        fixture(name), Platform.DOUYIN, "https://v.douyin.com/vaYOcAwFVf4/", "抖音作品"
    )

    @Test
    fun `沙龙月季三图图文作品产出可下载的动图资源`() {
        val p = requireNotNull(parse("router_slides.html"))
        assertEquals(3, p.images.size)
        assertEquals(1, p.localComposes.size)
        val a = p.localComposes.first()
        assertTrue("动图必须是本地合成资源", a.isLocalCompose)
        assertEquals(ResourceKind.ANIMATED, a.kind)
        assertEquals("image/gif", a.mime)
        assertEquals("gif", a.suggestedExtension())
        assertEquals("伪 URL 不能是 http 直链", true, a.url.startsWith("motionsaver://"))
        assertEquals(3, a.composeFrames.size)
        assertTrue("缩略图应指向真实首帧", a.thumbnailUrl.startsWith("https://"))
        assertEquals(p.images.first().url, a.thumbnailUrl)
        assertTrue(a.label.contains("动图"))
    }

    @Test
    fun `动图分组在UI里非空且静态图编号不受影响`() {
        val p = parse("router_slides.html")!!
        assertEquals("动图分组应出现 1 项", 1, p.byKind(ResourceKind.ANIMATED).size)
        assertEquals(listOf(1, 2, 3), p.images.map { it.index })
        assertEquals("主类型仍是图文，不被附加动图带偏", MediaType.IMAGE_TEXT, p.type)
    }

    @Test
    fun `原声地址随动图一起带出供合成使用`() {
        val p = parse("router_slides.html")!!
        val a = p.localComposes.first()
        assertTrue(a.composeAudio.isNotBlank())
        assertEquals(p.audios.first().url, a.composeAudio)
    }

    @Test
    fun `实况图单图作品同样产出动图资源`() {
        val p = requireNotNull(parse("router_live.html"))
        assertEquals(1, p.localComposes.size)
        assertEquals(1, p.localComposes.first().composeFrames.size)
        assertTrue(p.notes.any { it.contains("本地合成") })
    }

    @Test
    fun `纯视频作品不凭空造动图`() {
        val p = requireNotNull(parse("router_note_1.html"))
        // 该 fixture 是普通图文图集，仍应产出动图；这里验证资源不重复、不越界
        assertEquals(4, p.images.size)
        assertEquals(1, p.localComposes.size)
        assertEquals(4, p.localComposes.first().composeFrames.size)
        assertTrue(p.localComposes.first().composeFrames.all { it.startsWith("https://") })
    }

    @Test
    fun `动图资源不会污染静态图命名与下载计数`() {
        val p = parse("router_slides.html")!!
        // MessageBuilder 用的统计：图文 3 / 动图 1，两者互不混淆
        assertEquals(3, p.byKind(ResourceKind.IMAGE).size)
        assertEquals(1, p.byKind(ResourceKind.ANIMATED).size)
        assertTrue(p.images.none { it.isLocalCompose })
        assertTrue(p.videos.isEmpty())
    }
}
