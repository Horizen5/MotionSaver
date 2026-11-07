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

        val desc = Json.str(item["desc"]).ifBlank { Json.str(item["title"]) }
        val author = Json.str(Json.path(item, "author", "nickname"))
            .ifBlank { Json.str(item["nickname"]) }
        val itemId = Json.str(item["aweme_id"]).ifBlank { ShareLinkNormalizer.itemIdOf(sourceUrl) }

        // 实况图作品（Live Photo）：单张静态帧 + 原声，动态视频片段不在公开页里。
        // 仅作为「提示」保留，不再据此把主类型误标成「动态图」。
        val liveWork = isLivePhotoWork(item, images)

        // 公开分享页只暴露静态帧与原声，动态视频片段（MP4）不在公开数据里
        // （实测 play_addr 为 ftypM4A 纯音频、bit_rate=null、image_infos=null）。
        // 只有「多张静态帧」才有足够信息做真正会动的轮播 GIF；单张静态图
        // 没有第二帧，绝不凭空造动图 —— 避免把普通图文误报成「有动图」。
        val animated = animatedResource(itemId, images, audios)
        val resources = renumber(images + videos + audios + animated)

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
        if (animated.isNotEmpty()) {
            notes.add("多图轮播：可将 ${images.size} 张静态帧在设备本地合成为轮播 GIF 与小米动态照片（非平台原生动图）")
            notes.add("轮播动图为设备本地合成，不依赖平台私有接口或签名")
        }

        val warnings = mutableListOf<String>()
        if (liveWork) {
            // 单张静态帧 + 原声的「实况图」作品：公开页未暴露动态视频片段，
            // 明确告知用户「本作品没有可下载的动图」，不再把它标成动态图类型。
            warnings.add("单张图文作品：公开分享页只提供静态帧与原声，未暴露动态视频片段；本作品不含可下载的动图。")
        }

        val parsed = ParsedMedia(
            platform = platform,
            type = MediaType.MIXED,
            title = mainTitle(desc).ifBlank { defaultTitle },
            author = author,
            caption = desc,
            itemId = itemId,
            sourceUrl = sourceUrl,
            resources = resources,
            liveCover = if (liveWork) images.firstOrNull()?.copy(kind = ResourceKind.COVER) else null,
            liveVideo = null,
            livePhotoWork = liveWork,
            warnings = warnings,
            notes = notes
        )
        // 主类型一律按「真实资源构成」推断：单张静态图 + 原声 = 图文，
        // 绝不因实况模板把作品标成 LIVE_PHOTO（其标签「动态图」会造成
        // 「其实并没有动图，软件却说有」的假识别）。
        return parsed.copy(type = parsed.inferredType())
    }

    /**
     * 生成「多图轮播动图」资源。url 是伪地址（不可网络请求），
     * [MediaResource.isLocalCompose] 为真时下载链路会走本地编码分支。
     *
     * 只有 ≥2 张静态帧才产出：单张静态图没有第二帧可轮播，作品本身也
     * 不含任何动态片段（实测 play_addr 为 M4A 纯音频），此时若仍显示
     * 「动图」就是假识别 —— 用户明确反馈「其实并没有 GIF 动态图」。
     * 每帧携带自己的全部候选直链，下载时逐帧回退，避免签名过期导致整张动图失败。
     */
    private fun animatedResource(
        itemId: String,
        images: List<MediaResource>,
        audios: List<MediaResource>
    ): List<MediaResource> {
        if (images.size < 2) return emptyList()
        val key = itemId.ifBlank { images.first().url.hashCode().toString() }
        return listOf(
            MediaResource(
                url = "motionsaver://compose/animated/$key",
                mime = "image/gif",
                kind = ResourceKind.ANIMATED,
                label = "轮播动图 · ${images.size} 帧合成",
                index = 1,
                qualityLabel = "本地合成 · 非平台动图",
                isLocalCompose = true,
                composeFrames = images.map { it.url },
                composeAudio = audios.firstOrNull()?.url.orEmpty(),
                thumbnailUrl = images.first().url,
                headers = DEFAULT_HEADERS
            )
        )
    }

    /**
     * 实况图作品判据（实测抖音「实况 / live 动图」作品结构）：
     *  - 恰好 1 张图，且候选直链带实况专用模板 `lqen`（lqen-new）
     *  - `video.play_addr` 存在但 `duration=0`、`bit_rate=null`（没有真实视频轨，只是配乐容器）
     *  - `aweme_type=2`（图文作品）
     * 三者同时满足才判定，避免把普通单图或普通图文误标成实况图。
     */
    private val LIVE_TEMPLATE = Regex("""~tplv-[a-z0-9-]*lqen""", RegexOption.IGNORE_CASE)

    private fun isLivePhotoWork(item: Map<String, Any?>, images: List<MediaResource>): Boolean {
        if (images.size != 1) return false
        val hasLiveTemplate = (listOf(images[0].url) + images[0].alternatives)
            .any { LIVE_TEMPLATE.containsMatchIn(it) }
        if (!hasLiveTemplate) return false
        val video = Json.obj(item["video"])
        val hasPlayAddr = Json.str(Json.path(video, "play_addr", "uri")).isNotBlank() ||
            Json.strings(Json.path(video, "play_addr", "url_list")).isNotEmpty()
        val noRealVideo = video["bit_rate"] == null && Json.int(video["duration"]) == 0
        val imagePost = Json.int(item["aweme_type"]) == 2 || item["aweme_type"] == null
        return hasPlayAddr && noRealVideo && imagePost
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
        // 实测图文作品的 play_addr 返回 ftypM4A / audio/mp4；旧版写死 audio/mpeg
        // 会让文件被命名成 .mp3 却装着 M4A，系统播放器打不开。
        val mp3 = url.contains(".mp3", ignoreCase = true)
        return listOf(
            MediaResource(
                url = url,
                mime = if (mp3) "audio/mpeg" else "audio/mp4",
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

    private fun mainTitle(desc: String): String {
        // 正常文案：去掉话题符号后保留正文（保持既有标题断言不变）。
        val plain = desc
            .replace(Regex("""#[^\s#]*"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trimEnd('。', '，', ',', '.', '、', '；', ';', '!', '！', '?', '？', ' ')
            .take(120)
        if (plain.isNotBlank()) return plain
        // 纯话题文案（如「# 崩坏星穹铁道 # 流萤」）：用话题词做名字，
        // 而不是把标题清空、退化成硬编码的「抖音作品」。
        return WorkTitle.topics(desc).ifBlank { "" }
    }

    private fun renumber(list: List<MediaResource>): List<MediaResource> {
        var i = 0
        return list.map { r ->
            // 本地合成动图是附加产物，保留「动图 · N 帧轮播」这类说明性标签
            if (r.isLocalCompose) return@map r
            if (r.kind == ResourceKind.IMAGE || r.kind == ResourceKind.ANIMATED) {
                i++
                r.copy(index = i, label = if (r.kind == ResourceKind.ANIMATED) "动图 $i" else "图 $i")
            } else r
        }
    }
}
