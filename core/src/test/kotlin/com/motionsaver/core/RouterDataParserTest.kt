package com.motionsaver.core

import org.junit.Assert.*
import org.junit.Test

/**
 * 用真实抓取的「访客渲染」分享页回归：
 * 验证能拿到最高质量图集、不会把相关推荐的作品页链接误判成视频、
 * 以及批量多链接与 JSON 解析器本身。
 */
class RouterDataParserTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/$name")) { "缺少 fixture: $name" }
            .bufferedReader(Charsets.UTF_8).readText()

    private class StaticFetcher(private val html: String) : HttpFetcher {
        override fun get(url: String, headers: Map<String, String>) = HttpFetcher.Response(
            code = 200,
            finalUrl = "https://www.iesdouyin.com/share/note/7697862523365800009/?region=CN",
            contentType = "text/html",
            body = html.toByteArray()
        )
    }

    private fun parse(name: String) = RouterDataParser.parse(
        fixture(name), Platform.DOUYIN,
        "https://v.douyin.com/KOni4tR0sQQ/", "抖音作品"
    )

    @Test
    fun `真实作品1拿到4张最高质量图与干净标题作者`() {
        val p = requireNotNull(parse("router_note_1.html")) { "应能解析访客渲染数据" }
        assertEquals("人的本质是赌徒，不赌！就是在赌自己输", p.title)
        assertEquals("大大怪（在冬眠）", p.author)
        assertEquals(4, p.byKind(ResourceKind.IMAGE).size)
        assertEquals(MediaType.IMAGE_TEXT, p.type)
        assertTrue(p.caption.contains("赌徒"))
        assertTrue(p.notes.any { it.contains("videoInfoRes") })
    }

    @Test
    fun `图集首选JPEG高质量候选且排除水印与缩放模板`() {
        val p = parse("router_note_1.html")!!
        val imgs = p.byKind(ResourceKind.IMAGE)
        assertEquals(listOf(1, 2, 3, 4), imgs.map { it.index })
        assertTrue("首选应是 jpeg: ${imgs.first().url}", imgs.first().url.contains(".jpeg"))
        assertTrue(imgs.all { !it.url.contains("water", true) })
        assertTrue(imgs.all { !it.url.contains("shrink", true) })
        assertTrue(imgs.all { it.alternatives.isNotEmpty() })
        assertTrue(imgs.all { it.mime == "image/jpeg" || it.mime == "image/webp" })
        assertTrue(imgs.first().qualityLabel.contains("1080"))
    }

    @Test
    fun `真实作品2拿到3张图`() {
        val p = parse("router_note_2.html")!!
        assertEquals("我想说 天确实冷了", p.title)
        assertEquals("@无敌美少女", p.author)
        assertEquals(3, p.byKind(ResourceKind.IMAGE).size)
    }

    @Test
    fun `相关推荐的作品页链接不再被误判成视频`() {
        val p = parse("router_note_1.html")!!
        assertTrue("不该出现假视频: ${p.videos.map { it.url }}", p.videos.isEmpty())
        // 经过完整解析器链路同样不应出现
        val viaParser = DouyinParser().parse(
            "https://v.douyin.com/KOni4tR0sQQ/",
            StaticFetcher(fixture("router_note_1.html"))
        )
        assertTrue(viaParser.videos.isEmpty())
        assertEquals(4, viaParser.images.size)
    }

    @Test
    fun `图文作品的原声单独列为可勾选音频`() {
        val p = parse("router_note_1.html")!!
        assertEquals(1, p.audios.size)
        assertTrue(p.audios.first().label.contains("原声"))
    }

    @Test
    fun `一次粘贴多条分享链接全部识别`() {
        val text = """
            7.66 复制打开抖音，看看【A的图文作品】… https://v.douyin.com/KOni4tR0sQQ/ iPX:/ :5pm
            6.99 复制打开抖音，看看【B的图文作品】… https://v.douyin.com/nb2psOMhb3U/ 03/30 :8pm
            https://v.douyin.com/KOni4tR0sQQ/
            https://www.xiaohongshu.com/explore/abc123456
        """.trimIndent()
        val urls = ShareText.extractUrls(text)
        assertEquals(3, urls.size)
        assertEquals("https://v.douyin.com/KOni4tR0sQQ/", urls[0])
        assertEquals("https://v.douyin.com/nb2psOMhb3U/", urls[1])
        assertEquals("https://www.xiaohongshu.com/explore/abc123456", urls[2])
    }

    @Test
    fun `Json解析器能处理嵌套数组与unicode转义`() {
        val root = Json.parse("""{"a":[{"b":["x","y"]}],"c":"note_(id)\u002Fpage"}""")
        val first = Json.arr(Json.obj(root)["a"]).firstOrNull()
        assertEquals(listOf("x", "y"), Json.strings(Json.obj(first)["b"]))
        assertEquals("note_(id)/page", Json.str(Json.obj(root)["c"]))
        assertEquals(listOf("x", "y"), Json.strings(Json.findFirst(root, "b")))
    }

    @Test
    fun `旧版JSON-LD页面仍可回退解析`() {
        val ld = JsonLdParser.parse(fixture("douyin_note_1.html"))
        assertEquals(4, ld.imageUrls.size)
        assertEquals("大大怪（在冬眠）", ld.author)
    }

    @Test
    fun `纯页面壳不含作品数据时不会伪造资源`() {
        val shell = """<html><head><title>抖音</title></head><body>
            <script>window._ROUTER_DATA={"loaderData":{"note_(id)/page":{"itemId":"123"}}};</script>
            <a href="https://www.douyin.com/video/7703781547752639780">推荐</a>
            </body></html>""".trimIndent()
        val p = DouyinParser().parse("https://v.douyin.com/abc", StaticFetcher(shell))
        assertTrue("不该把推荐链接当视频: ${p.videos}", p.videos.isEmpty())
        assertTrue(p.resources.isEmpty())
        assertTrue(p.warnings.any { it.contains("没有暴露") })
    }
}
