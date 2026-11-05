package com.motionsaver.core

import org.junit.Assert.*
import org.junit.Test

/**
 * 用「真实抓取」的抖音图文分享页做回归验证。
 *
 * fixture 来自用户提供的两条分享短链，跟随 302 到
 * https://www.iesdouyin.com/share/note/{id}/ 后，以搜索引擎爬虫 UA 命中的
 * 服务端渲染页面，仅保留其中的 schema.org JSON-LD 结构化数据块。
 * 目的：证明 APP 能真的从公开页解析出图集，而不是只返回「0 个资源」。
 */
class DouyinRealPageTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/$name")) { "缺少测试 fixture: $name" }
            .bufferedReader(Charsets.UTF_8).readText()

    /** 模拟网络层：直接返回抓取到的真实页面，同时给出重定向后的完整地址。 */
    private class StaticFetcher(private val html: String) : HttpFetcher {
        override fun get(url: String, headers: Map<String, String>) = HttpFetcher.Response(
            code = 200,
            finalUrl = "https://www.iesdouyin.com/share/note/7697862523365800009/?region=CN&mid=7697046348918836002",
            contentType = "text/html",
            body = html.toByteArray()
        )
    }

    private val share1 =
        "7.66 复制打开抖音，看看【大大怪（在冬眠）的图文作品】人的本质是赌徒，不赌！就是在赌自己输！# fx战士... https://v.douyin.com/KOni4tR0sQQ/ iPX:/ :5pm 06/24 S@y.gO"
    private val share2 =
        "6.99 复制打开抖音，看看【@无敌美少女的图文作品】我想说 天确实冷了。# 冷的猝不及防 # 心动瞬间... https://v.douyin.com/nb2psOMhb3U/ 03/30 :8pm e@o.Qx cNj:/"

    private fun parse(fixtureName: String, share: String) =
        DouyinParser().parse(share, StaticFetcher(fixture(fixtureName)))

    @Test
    fun `真实链接1解析出4张图与干净标题作者`() {
        val p = parse("douyin_note_1.html", share1)
        assertEquals(Platform.DOUYIN, p.platform)
        assertEquals("人的本质是赌徒不赌就是在赌自己输#fx战士久留美#股票行情#", p.title)
        assertEquals("大大怪（在冬眠）", p.author)
        assertEquals(4, p.byKind(ResourceKind.IMAGE).size)
        assertEquals(MediaType.IMAGE_TEXT, p.type)
        assertTrue("不该再报「拿不到资源」: ${p.warnings}", p.warnings.isEmpty())
        assertTrue("应标注命中结构化数据: ${p.notes}", p.notes.any { it.contains("JSON-LD") })
    }

    @Test
    fun `真实链接2解析出3张图与干净标题作者`() {
        val p = parse("douyin_note_2.html", share2)
        assertEquals("我想说 天确实冷了", p.title)
        assertEquals("@无敌美少女", p.author)
        assertEquals(3, p.byKind(ResourceKind.IMAGE).size)
        assertEquals(MediaType.IMAGE_TEXT, p.type)
        assertTrue("不该再报「拿不到资源」: ${p.warnings}", p.warnings.isEmpty())
    }

    @Test
    fun `图集保持原顺序编号并指向真实CDN直链`() {
        val imgs = parse("douyin_note_1.html", share1).images
        assertEquals(listOf(1, 2, 3, 4), imgs.map { it.index })
        assertTrue(imgs.all { it.url.startsWith("https://") && it.url.contains("douyinpic.com") })
        assertTrue(imgs.all { it.url.contains("aweme-images") })
        assertTrue(imgs.all { it.mime == "image/webp" })
        assertTrue(imgs.all { it.headers.containsKey("User-Agent") })
    }

    @Test
    fun `下载文件名用作品标题而不是整段分享口令`() {
        val p = parse("douyin_note_1.html", share1)
        val name = FileNameAllocator.allocate(p.title, "webp", emptySet())
        assertTrue("实际文件名: $name", name.contains("人的本质是赌徒"))
        assertFalse("不该混入口令: $name", name.contains("复制打开"))
        assertFalse("不该混入乱码: $name", name.contains("iPX"))
        assertTrue(name.endsWith(".webp"))
    }

    @Test
    fun `图集序号用于系列命名不重名`() {
        val p = parse("douyin_note_2.html", share2)
        val used = mutableSetOf<String>()
        val names = p.images.map { r ->
            FileNameAllocator.allocateSeries(p.title, "webp", r.index - 1, p.images.size, used)
                .also { used.add(it) }
        }
        assertEquals(3, names.toSet().size)
        assertTrue(names.all { it.contains("我想说") })
    }

    @Test
    fun `SSR空壳页面重试无果时明确提示而不是假装成功`() {
        val shell = """<html><head><title>抖音</title></head><body>
            <script>window._ROUTER_DATA={"loaderData":{"note_(id)/page":{"itemId":"123"}}};</script>
            </body></html>""".trimIndent()
        val p = DouyinParser().parse("https://v.douyin.com/abc", StaticFetcher(shell))
        assertTrue(p.resources.isEmpty())
        assertTrue(p.warnings.any { it.contains("没有暴露") })
        assertTrue(p.warnings.any { it.contains("解析端点") })
        assertTrue(p.warnings.any { it.contains("重试") })
    }

    @Test
    fun `JsonLdParser直接解析忽略导航块与作者头像`() {
        val ld = JsonLdParser.parse(fixture("douyin_note_1.html"))
        assertEquals("article", ld.type)
        assertEquals(4, ld.imageUrls.size)
        assertTrue(ld.imageUrls.none { it.contains("avatar") || it.contains("100x100") })
        assertEquals("大大怪（在冬眠）", ld.author)
        assertTrue(ld.description.contains("人的本质是赌徒"))
    }
}
