package com.motionsaver.core

import org.junit.Assert.*
import org.junit.Test

/**
 * 本轮两个真实缺陷的回归：
 *  1) 图集短链跳到 /share/slides/{id}/ 时返回未渲染页面壳，导致「0 资源」；
 *     归一化为 /share/note/{id}/ 后能拿到完整数据（3 张图）。
 *  2) 实况图（Live Photo）作品只被识别成「1 张静态图 + 1 个音频」，
 *     UI 里看不到「动图」。现在正确标注为实况图作品并给出补齐指引，
 *     同时绝不把 M4A 音频伪装成视频、也不伪造小米动态照片。
 * fixture 来自用户真实提供的两条公开分享链接的访客渲染数据。
 */
class LivePhotoAndSlidesTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/$name")) { "缺少 fixture: $name" }
            .bufferedReader(Charsets.UTF_8).readText()

    private fun parse(name: String, source: String = "https://v.douyin.com/igDF5iLZ8ec/") =
        RouterDataParser.parse(fixture(name), Platform.DOUYIN, source, "抖音作品")

    // ---------------------------------------------------------------- 路径归一化

    @Test
    fun `share slides 长链归一化为 share note`() {
        val input = "https://www.iesdouyin.com/share/slides/7636288609633685171/?region=CN&from_ssr=1"
        assertEquals(
            "https://www.iesdouyin.com/share/note/7636288609633685171/",
            ShareLinkNormalizer.canonicalShareUrl(input)
        )
    }

    @Test
    fun `m 与 www 域名的 slides 路径都能归一化`() {
        assertEquals(
            "https://www.iesdouyin.com/share/note/7636288609633685171/",
            ShareLinkNormalizer.canonicalShareUrl("https://m.douyin.com/share/slides/7636288609633685171/")
        )
        assertEquals(
            "https://www.iesdouyin.com/share/note/7636288609633685171/",
            ShareLinkNormalizer.canonicalShareUrl("https://www.douyin.com/share/slides/7636288609633685171/")
        )
    }

    @Test
    fun `普通 note 与 video 链接不被改写`() {
        val note = "https://www.iesdouyin.com/share/note/7697862523365800009/?region=CN"
        assertEquals(note, ShareLinkNormalizer.canonicalShareUrl(note))
        val video = "https://www.douyin.com/video/7703781547752639780"
        assertEquals(video, ShareLinkNormalizer.canonicalShareUrl(video))
    }

    @Test
    fun `normalize 直接处理用户粘贴的 slides 长链`() {
        assertEquals(
            "https://www.iesdouyin.com/share/note/7636288609633685171/",
            ShareLinkNormalizer.normalize("www.iesdouyin.com/share/slides/7636288609633685171/")
        )
    }

    @Test
    fun `slides 路径也能提取作品 ID`() {
        assertEquals(
            "7636288609633685171",
            ShareLinkNormalizer.itemIdOf("https://www.iesdouyin.com/share/slides/7636288609633685171/")
        )
    }

    // ---------------------------------------------------------------- 实况图识别

    @Test
    fun `实况图作品被识别为动态图且给出补齐指引`() {
        val p = requireNotNull(parse("router_live.html")) { "应能解析实况图作品" }
        assertTrue("应标记为实况图作品", p.livePhotoWork)
        assertEquals(MediaType.LIVE_PHOTO, p.type)
        assertEquals(1, p.byKind(ResourceKind.IMAGE).size)
        assertNotNull("静态帧应作为实况封面", p.liveCover)
        assertTrue(p.notes.any { it.contains("实况图") })
        assertTrue(p.warnings.any { it.contains("动态视频片段") })
        assertTrue(p.warnings.any { it.contains("解析端点") })
    }

    @Test
    fun `实况图绝不伪造动态照片合成`() {
        val p = parse("router_live.html")!!
        assertNull("公开页没给动态片段，不能凭空造一个 liveVideo", p.liveVideo)
        assertFalse("isLivePhoto 必须为 false，避免下游合成出损坏文件", p.isLivePhoto)
        assertTrue("不该出现假视频资源: ${p.videos}", p.videos.isEmpty())
    }

    @Test
    fun `实况图的原声按 M4A 标注而非 mp3`() {
        val p = parse("router_live.html")!!
        assertEquals(1, p.audios.size)
        val audio = p.audios.first()
        assertEquals("audio/mp4", audio.mime)
        assertEquals("m4a", audio.suggestedExtension())
    }

    @Test
    fun `m 站实况图数据同样能识别`() {
        val p = requireNotNull(parse("router_live_m.html")) { "m 站数据也应能解析" }
        assertTrue(p.livePhotoWork)
        assertEquals(MediaType.LIVE_PHOTO, p.type)
        assertFalse(p.isLivePhoto)
    }

    @Test
    fun `普通多图图文不被误标成实况图`() {
        val p = requireNotNull(parse("router_slides.html")) { "slides 作品应能解析" }
        assertFalse("3 张图的普通图集不是实况图", p.livePhotoWork)
        assertEquals(3, p.byKind(ResourceKind.IMAGE).size)
        assertEquals("沙龙月季", p.title)
        assertEquals(MediaType.IMAGE_TEXT, p.type)
        assertTrue(p.videos.isEmpty())
        assertNull(p.liveCover)
    }

    @Test
    fun `旧的普通图文作品也不被误标成实况图`() {
        for (name in listOf("router_note_1.html", "router_note_2.html")) {
            val p = parse(name)!!
            assertFalse("$name 不该被标成实况图", p.livePhotoWork)
            assertNotEquals(MediaType.LIVE_PHOTO, p.type)
        }
    }
}
