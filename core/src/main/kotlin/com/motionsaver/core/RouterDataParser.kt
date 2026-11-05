package com.motionsaver.core

/**
 * 解析平台 SSR 分享页里的 `window._ROUTER_DATA.videoInfoRes`。
 *
 * 取得方式：向 `ttwid.bytedance.com` 注册一个**公开访客标识**（Cookie，
 * 不需要账号、不需要登录、不需要 a_bogus/x-s 签名），带该 Cookie 用普通
 * 移动端 UA 请求用户粘贴的公开分享链接，页面即返回完整作品数据。
 *
 * 与「全文正则扫链接」不同，这里只精确读取 `item_list[0]` 的
 * `images[].url_list` / `video.bit_rate[].play_addr` / `music` 等明确字段，
 * 因此不会把页面里的「相关推荐」作品链接（www.douyin.com/video/xxx）
 * 误当成可下载视频——那类链接下载下来是 HTML，会表现为 0 秒 / 文件损坏。
 *
 * 画质：平台为每张图给出多个候选直链，按「JPEG 优先 > 排除水印 > 排除缩放
 * 模板 > 尺寸更大」排序，选最高质量的一条作为首选，其余留作下载失败回退。
 */
object RouterDataParser {

    private val ROUTER = Regex(
        """window\._ROUTER_DATA\s*=\s*(\{.*?\})\s*;?\s*</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val WATER = Regex("""water|5oqW6Z|watermark""", RegexOption.IGNORE_CASE)
    private val SHRINK = Regex("""shrink|resize|thumb|crop|adapt""", RegexOption.IGNORE_CASE)
    private val DIMS = Regex(""":(\d{3,4}):(\d{3,4})""")
    private val VIDEO_ID = Regex("""video_id=(https?[^&]+)""")

    private val DEFAULT_HEADERS = mapOf(
        "User-Agent" to
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36",
        "Referer" to "https://www.douyin.com/"
    )

    fun parse(
        html: String,
        platform: Platform,
        sourceUrl: String,
        defaultTitle: String
    ): ParsedMedia? {
        val raw = ROUTER.find(html)?.groupValues?.get(1) ?: return null
        val root = Json.parse(raw) ?: return null
        val item = findItem(root) ?: return null

        val images = imageResources(item)
        val videos = videoResources(item, images.isNotEmpty())
        val audios = audioResources(item, images.isNotEmpty())
        if (images.isEmpty() && videos.isEmpty() && audios.isEmpty()) return null

        val resources = renumber(images + videos + audios)
        val desc = Json.str(item["desc"]).ifBlank { Json.str(item["title"]) }
        val author = Json.str(Json.path(item, "author", "nickname"))
            .ifBlank { Json.str(item["nickname"]) }
        val itemId = Json.str(item["aweme_id"]).ifBlank { ShareLinkNormalizer.itemIdOf(sourceUrl) }

        val notes = mutableListOf<String>()
        notes.add("数据源：公开分享页访客渲染数据 _ROUTER_DATA.videoInfoRes")
        if (images.isNotEmpty()) {
            notes.add("图集 ${images.size} 张 · 已选最高质量候选（JPEG 优先，排除水印与缩放模板）")
            images.firstOrNull { it.qualityLabel.isNotBlank() }?.let {
                notes.add("声明尺寸：${it.qualityLabel}")
            }
        }
        if (videos.isNotEmpty()) notes.add("视频：已挑最高码率直链")
        if (audios.isNotEmpty()) notes.add("原声：单独列出，可勾选是否下载")

        return ParsedMedia(
            platform = platform,
            type = MediaType.MIXED,
            title = mainTitle(desc).ifBlank { defaultTitle },
            author = author,
            caption = desc,
            itemId = itemId,
            sourceUrl = sourceUrl,
            resources = resources,
            warnings = emptyList(),
            notes = notes
        ).let { it.copy(type = it.inferredType()) }
    }

    // ---------------------------------------------------------------- 结构定位

    private fun findItem(root: Any?): Map<String, Any?>? {
        val loader = Json.obj(Json.obj(root)["loaderData"])
        for (page in loader.values) {
            val vir = Json.obj(Json.obj(page)["videoInfoRes"])
            val list = Json.arr(vir["item_list"])
            if (list.isNotEmpty()) return Json.obj(list[0])
        }
        val vir = Json.findFirst(root, "videoInfoRes") ?: return null
        val list = Json.arr(Json.obj(vir)["item_list"])
        return list.firstOrNull()?.let { Json.obj(it) }
    }

    // ---------------------------------------------------------------- 四类资源

    private fun imageResources(item: Map<String, Any?>): List<MediaResource> {
        val list = Json.arr(item["images"])
        return list.mapIndexedNotNull { i, v ->
            val im = Json.obj(v)
            // url_list 是主候选；download_url_list 带水印，不用
            val candidates = rank(Json.strings(im["url_list"]))
            val url = candidates.firstOrNull() ?: return@mapIndexedNotNull null
            val w = Json.int(im["width"])
            val h = Json.int(im["height"])
            val jpeg = isJpeg(url)
            MediaResource(
                url = url,
                mime = if (jpeg) "image/jpeg" else "image/webp",
                kind = ResourceKind.IMAGE,
                label = "图 ${i + 1}",
                index = i + 1,
                alternatives = candidates.drop(1),
                width = w,
                height = h,
                qualityLabel = if (w > 0 && h > 0) {
                    "${w}×${h} · ${if (jpeg) "JPEG 高质量" else "WebP"}"
                } else "",
                headers = DEFAULT_HEADERS
            )
        }
    }

    private fun videoResources(item: Map<String, Any?>, hasImages: Boolean): List<MediaResource> {
        // 图文作品的 play_addr 指向配乐 mp3，不是视频，绝不能当视频资源
        if (hasImages) return emptyList()
        val video = Json.obj(item["video"])
        val candidates = mutableListOf<String>()
        for (r in Json.arr(video["bit_rate"])) {
            candidates += Json.strings(Json.path(r, "play_addr", "url_list"))
        }
        candidates += Json.strings(Json.path(video, "play_addr", "url_list"))
        val cleaned = candidates
            .map { it.replace("playwm", "play") }
            .filter { it.startsWith("http") && !it.contains(".mp3", true) }
            .distinct()
            .let { rank(it) }
        val url = cleaned.firstOrNull() ?: return emptyList()
        return listOf(
            MediaResource(
                url = url,
                mime = "video/mp4",
                kind = ResourceKind.VIDEO,
                label = "视频",
                alternatives = cleaned.drop(1),
                headers = DEFAULT_HEADERS
            )
        )
    }

    private fun audioResources(item: Map<String, Any?>, hasImages: Boolean): List<MediaResource> {
        if (!hasImages) return emptyList()
        val video = Json.obj(item["video"])
        val urls = Json.strings(Json.path(video, "play_addr", "url_list"))
        val direct = urls.flatMap { u ->
            val m = VIDEO_ID.find(u)
            if (m != null) {
                listOf(runCatching { java.net.URLDecoder.decode(m.groupValues[1], "UTF-8") }
                    .getOrDefault(m.groupValues[1]))
            } else listOf(u)
        }.filter { it.startsWith("http") }.distinct()
        val url = direct.firstOrNull() ?: return emptyList()
        val musicTitle = Json.str(Json.path(item, "music", "title"))
        return listOf(
            MediaResource(
                url = url,
                mime = "audio/mpeg",
                kind = ResourceKind.AUDIO,
                label = if (musicTitle.isNotBlank()) "原声 · ${musicTitle.take(24)}" else "原声 BGM",
                alternatives = direct.drop(1),
                headers = DEFAULT_HEADERS
            )
        )
    }

    // ---------------------------------------------------------------- 画质优选

    /** 分数越高画质越好；缩放模板与水印链剔除，JPEG 明显优先于 WebP。 */
    private fun rank(list: List<String>): List<String> {
        val clean = list
            .filter { it.startsWith("http") }
            .filterNot { WATER.containsMatchIn(it) }
            .distinct()
        val scored = clean.map { it to score(it) }.sortedByDescending { it.second }
        val withoutShrink = scored.filterNot { SHRINK.containsMatchIn(it.first) }
        return (withoutShrink.ifEmpty { scored }).map { it.first }
    }

    private fun score(u: String): Int {
        val l = u.lowercase()
        var s = 0
        when {
            isJpeg(u) -> s += 100_000
            l.contains(".webp") -> s += 10_000
        }
        if (l.contains("q80")) s += 2_000
        if (l.contains("q75")) s += 500
        DIMS.find(u)?.let { s += maxOf(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        if (SHRINK.containsMatchIn(u)) s -= 50_000
        return s
    }

    private fun isJpeg(u: String): Boolean {
        val l = u.lowercase()
        return l.contains(".jpeg") || l.contains(".jpg")
    }

    private fun mainTitle(desc: String): String = desc
        .replace(Regex("""#[^\s#]*"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .trimEnd('。', '，', ',', '.', '、', '；', ';', '!', '！', '?', '？', ' ')
        .take(120)

    private fun renumber(list: List<MediaResource>): List<MediaResource> {
        var i = 0
        return list.map { r ->
            if (r.kind == ResourceKind.IMAGE || r.kind == ResourceKind.ANIMATED) {
                i++
                r.copy(index = i, label = if (r.kind == ResourceKind.ANIMATED) "动图 $i" else "图 $i")
            } else r
        }
    }
}
