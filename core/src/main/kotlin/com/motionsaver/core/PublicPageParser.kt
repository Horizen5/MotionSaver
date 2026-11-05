package com.motionsaver.core

/**
 * 「公开分享页」解析基类。
 *
 * 只读取用户粘贴的公开页面自身声明的元信息（og:* / twitter:* / 内嵌 JSON / CDN 链接），
 * 不构造任何平台私有签名、不请求需要鉴权的内部接口。
 *
 * 资源发现走三层，尽量覆盖真实页面：
 *   1. og:image / og:video / twitter:image 等标准 meta
 *   2. 页面内嵌 JSON 里常见的媒体字段（url_list / imageList / play_addr / music 等）
 *   3. HTML 里裸出现的、带媒体扩展名或媒体路径的 http(s) 链接
 * 收集后按 URL 特征自动归类到 图文 / 视频 / 音频 / 动图，避免字段改名就整体失效。
 *
 * 说明：正则内统一用 \x22 / \x27 表示引号，由正则引擎解析，规避 Kotlin 字符串转义限制。
 */
abstract class PublicPageParser : MediaParser {

    companion object {
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
        private const val SEO_UA =
            "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"
        /** 平台 SSR 是概率性返回（实测同一链接前 1~2 次可能是空壳），留足重试次数。 */
        private const val SSR_ATTEMPTS = 5
    }

    protected val fetchHeaders = mapOf(
        "User-Agent" to MOBILE_UA,
        "Accept" to "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
        "Accept-Language" to "zh-CN,zh;q=0.9"
    )

    /**
     * 搜索引擎爬虫 UA：平台为 SEO 收录会服务端渲染出完整 JSON-LD 结构化数据。
     * 这是公开页面自身声明的内容，不涉及任何签名或鉴权。
     */
    private val seoHeaders = mapOf(
        "User-Agent" to SEO_UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "zh-CN,zh;q=0.9"
    )

    /** 下载媒体时带的通用请求头（CDN 直链对空 UA 可能拦截）。 */
    private val mediaHeaders = mapOf("User-Agent" to MOBILE_UA)

    protected fun fetchPublicPage(url: String, fetcher: HttpFetcher): HttpFetcher.Response {
        val res = fetcher.get(url, fetchHeaders)
        if (res.code !in 200..299) throw ParseException("公开页返回 HTTP ${res.code}")
        return res
    }

    /**
     * 多策略取页。第一次用常规 UA 跟随短链 302，拿到带分享参数的完整 URL；
     * 后续直接用该 URL + 爬虫 UA 请求，命中 SSR 结构化数据即早停。
     * 每次都记录「数据最全」的一次，全部落空时返回它并给出明确警告。
     */
    protected fun fetchForParsing(url: String, fetcher: HttpFetcher): Pair<HttpFetcher.Response, JsonLdMedia> {
        var bestResponse: HttpFetcher.Response? = null
        var bestLd = JsonLdMedia()
        var bestScore = -1
        var requestUrl = url

        repeat(SSR_ATTEMPTS) { attempt ->
            val headers = if (attempt == 0) fetchHeaders else seoHeaders
            val res = runCatching { fetcher.get(requestUrl, headers) }.getOrNull() ?: return@repeat
            if (res.code !in 200..299) return@repeat
            // 复用重定向后的完整地址，避免每次重新走短链跳转、也保留分享签名参数
            if (res.finalUrl.isNotBlank()) requestUrl = res.finalUrl

            val html = res.text()
            val ld = JsonLdParser.parse(html)
            val score = ld.mediaCount * 1000 + discoverResources(html).size
            if (score > bestScore) {
                bestScore = score
                bestResponse = res
                bestLd = ld
            }
            if (ld.mediaCount > 0) return Pair(res, ld)
        }

        val fallback = bestResponse ?: fetchPublicPage(url, fetcher)
        return fallback to bestLd
    }

    // ---------------------------------------------------------------- 标题 / 正文

    protected fun extractTitle(html: String): String {
        listOf(
            Regex("""<meta[^>]+property=[\x22\x27]og:title[\x22\x27][^>]+content=[\x22\x27]([^\x22\x27]+)[\x22\x27]""", RegexOption.IGNORE_CASE),
            Regex("""<meta[^>]+content=[\x22\x27]([^\x22\x27]+)[\x22\x27][^>]+property=[\x22\x27]og:title[\x22\x27]""", RegexOption.IGNORE_CASE),
            Regex("""<meta[^>]+name=[\x22\x27]twitter:title[\x22\x27][^>]+content=[\x22\x27]([^\x22\x27]+)[\x22\x27]""", RegexOption.IGNORE_CASE),
            Regex("""<title>([^<]+)</title>""", RegexOption.IGNORE_CASE),
            Regex("""\x22(?:desc|title|headline|note_title)\x22\s*:\s*\x22([^\x22]+)\x22""")
        ).forEach { r ->
            r.find(html)?.groupValues?.get(1)?.let { t ->
                val decoded = decodeEntities(MiniJson.unescape(t.trim()))
                if (decoded.isNotBlank() && decoded.length < 300) return decoded
            }
        }
        return ""
    }

    protected fun extractMeta(html: String, property: String): String? {
        val r = Regex(
            """<meta[^>]+(?:property|name)=[\x22\x27]${Regex.escape(property)}[\x22\x27][^>]+content=[\x22\x27]([^\x22\x27]+)[\x22\x27]""",
            RegexOption.IGNORE_CASE
        )
        return r.find(html)?.groupValues?.get(1)?.let { decodeEntities(MiniJson.unescape(it)) }
    }

    protected fun extractAuthor(html: String): String {
        listOf("author", "nickname", "user_name", "creator", "og:site_name").forEach { key ->
            extractMeta(html, key)?.takeIf { it.isNotBlank() && it.length < 60 }?.let { return it }
            MiniJson.stringField(preprocess(html), key)
                ?.takeIf { it.isNotBlank() && it.length < 60 }?.let { return it }
        }
        return ""
    }

    protected fun extractCaption(html: String): String {
        val d = preprocess(html)
        listOf("desc", "description", "content", "note_desc", "caption").forEach { key ->
            MiniJson.stringField(d, key)?.takeIf { it.length > 4 }?.let {
                return decodeEntities(it).take(2000)
            }
        }
        return extractMeta(html, "description").orEmpty().take(2000)
    }

    // ---------------------------------------------------------------- 资源发现

    private val META_MEDIA = listOf(
        Regex("""<meta[^>]+property=[\x22\x27]og:image(?::url|:secure_url)?[\x22\x27][^>]+content=[\x22\x27]([^\x22\x27]+)[\x22\x27]""", RegexOption.IGNORE_CASE),
        Regex("""<meta[^>]+content=[\x22\x27]([^\x22\x27]+)[\x22\x27][^>]+property=[\x22\x27]og:image[\x22\x27]""", RegexOption.IGNORE_CASE),
        Regex("""<meta[^>]+property=[\x22\x27]og:video(?::url|:secure_url)?[\x22\x27][^>]+content=[\x22\x27]([^\x22\x27]+)[\x22\x27]""", RegexOption.IGNORE_CASE),
        Regex("""<meta[^>]+name=[\x22\x27]twitter:image[\x22\x27][^>]+content=[\x22\x27]([^\x22\x27]+)[\x22\x27]""", RegexOption.IGNORE_CASE),
        Regex("""<meta[^>]+name=[\x22\x27]twitter:player[\x22\x27][^>]+content=[\x22\x27]([^\x22\x27]+)[\x22\x27]""", RegexOption.IGNORE_CASE)
    )

    private val ARRAY_KEYS = listOf(
        "url_list", "urlList", "url_default", "imageList", "image_list", "images", "imgs",
        "pics", "pic_list", "note_images", "image_url_list", "origin_image_list",
        "play_addr", "playAddr", "play_url", "download_addr", "bitrateInfo", "video_list",
        "music", "music_url", "musicUrl", "audio", "audio_url", "audio_list"
    )

    private val STR_KEYS = listOf(
        "cover", "static_url", "image_url", "origin_url", "video_url", "videoUrl",
        "live_video", "live_photo", "play_addr", "play_url", "url_default", "url",
        "music_url", "musicUrl", "audio_url", "gif_url", "animated_url"
    )

    private val BARE_URL = Regex("""https?://[^\s\x22\x27<>\\)]+""")
    private val MEDIA_EXT =
        Regex("""\.(jpg|jpeg|png|webp|gif|apng|heic|mp4|m3u8|mov|webm|mp3|m4a|aac|flac|ogg|wav)(\?|&|$)""", RegexOption.IGNORE_CASE)
    private val MEDIA_PATH =
        Regex("""/(image|img|photo|video|audio|media|music|tos-|obj|aweme|spec|feed|live)/""", RegexOption.IGNORE_CASE)
    private val BLACKLIST =
        Regex("""logo|icon|avatar|placeholder|blank|favicon|sprite|emoji|badge|qrcode|loading|default_cover|\.css|\.js|\.ico|\.svg|\.woff""", RegexOption.IGNORE_CASE)

    /** 把 HTML 里的实体与 JSON 反斜杠转义还原，方便统一用正则提取。 */
    private fun preprocess(html: String): String = decodeEntities(html)
        .replace("\\u002F", "/")
        .replace("\\u0026", "&")
        .replace("\\/", "/")

    protected fun discoverResources(html: String): List<MediaResource> {
        val d = preprocess(html)
        val urls = linkedSetOf<String>()

        // 1) 标准 meta（已知是媒体，直接收）
        META_MEDIA.forEach { r ->
            r.findAll(d).forEach { m ->
                cleanUrl(m.groupValues[1])?.let { urls.add(it) }
            }
        }

        // 2) 内嵌 JSON 的媒体字段
        ARRAY_KEYS.forEach { key ->
            val arr = Regex("""\x22${Regex.escape(key)}\x22\s*:\s*\[([^\]]*)\]""")
            arr.findAll(d).forEach { m ->
                Regex("""\x22((?:\\.|[^\x22\\])*)\x22""").findAll(m.groupValues[1]).forEach { q ->
                    cleanUrl(MiniJson.unescape(q.groupValues[1]))?.let { urls.add(it) }
                }
            }
        }
        STR_KEYS.forEach { key ->
            val str = Regex("""\x22${Regex.escape(key)}\x22\s*:\s*\x22((?:\\.|[^\x22\\])*)\x22""")
            str.findAll(d).forEach { m ->
                cleanUrl(MiniJson.unescape(m.groupValues[1]))?.let { urls.add(it) }
            }
        }

        // 3) HTML 里裸出现的媒体 URL（无引号包裹的 CDN 直链）
        BARE_URL.findAll(d).forEach { m ->
            val u = cleanUrl(m.value) ?: return@forEach
            if ((MEDIA_EXT.containsMatchIn(u) || MEDIA_PATH.containsMatchIn(u)) &&
                !BLACKLIST.containsMatchIn(u)
            ) urls.add(u)
        }

        // 4) 过滤噪音 + 按 URL 特征归类
        val filtered = urls.filter { u ->
            MediaTypeDetector.isHttp(u) &&
                (MEDIA_EXT.containsMatchIn(u) || MEDIA_PATH.containsMatchIn(u) || u.contains("cdn", true)) &&
                !BLACKLIST.containsMatchIn(u)
        }

        var imageIndex = 0
        return filtered.map { u ->
            val kind = MediaTypeDetector.kindOf(u)
            val idx = if (kind == ResourceKind.IMAGE || kind == ResourceKind.ANIMATED) ++imageIndex else -1
            MediaResource(
                url = u,
                mime = MediaTypeDetector.mimeOf(u, kind),
                kind = kind,
                label = when (kind) {
                    ResourceKind.IMAGE -> "图 $idx"
                    ResourceKind.ANIMATED -> "动图 $idx"
                    ResourceKind.VIDEO -> "视频"
                    ResourceKind.AUDIO -> "音频"
                    else -> "资源"
                },
                index = idx
            )
        }
    }

    private fun cleanUrl(raw: String): String? {
        var u = raw.trim()
            .trimEnd('，', '。', ',', ';', '；', '、', ')', ']', '}', '"', '\'')
            .replace("&amp;", "&")
        if (!MediaTypeDetector.isHttp(u)) return null
        if (u.length < 12) return null
        return u
    }

    /** 检测实况 / 动态照片：页面有 live 标记且同时存在封面与动态片段。 */
    protected fun detectLive(html: String, resources: List<MediaResource>): Pair<MediaResource?, MediaResource?> {
        val d = preprocess(html)
        val hasLiveMarker =
            Regex("""live[_-]?photo|live[_-]?video|motion[_-]?photo|实况|动态照片|microvideo""", RegexOption.IGNORE_CASE)
                .containsMatchIn(d) ||
                // 页面同时给出 cover 与 video_url 两个字段，也视作实况图结构
                (Regex("""\x22(?:cover|static_url)\x22\s*:""").containsMatchIn(d) &&
                    Regex("""\x22(?:video_url|live_video)\x22\s*:""").containsMatchIn(d))
        if (!hasLiveMarker) return null to null

        val cover = extractMeta(html, "og:image")?.let { u ->
            MediaResource(u, "image/jpeg", ResourceKind.COVER, label = "动态封面")
        } ?: resources.firstOrNull { it.kind == ResourceKind.IMAGE }?.copy(kind = ResourceKind.COVER)

        val video = resources.firstOrNull { it.kind == ResourceKind.VIDEO }
            ?.copy(kind = ResourceKind.LIVE_VIDEO, label = "动态片段")
            ?: Regex("""\x22(?:video_url|live_video)\x22\s*:\s*\x22([^\x22]+\.mp4[^\x22]*)\x22""")
                .find(d)?.groupValues?.get(1)?.let {
                    MediaResource(MiniJson.unescape(it), "video/mp4", ResourceKind.LIVE_VIDEO, label = "动态片段")
                }

        return if (cover != null && video != null) cover to video else null to null
    }

    protected fun decodeEntities(s: String): String = s
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace("&#x22;", "\"").replace("&#x27;", "'")

    protected fun unescapeJson(s: String): String = MiniJson.unescape(s)

    protected fun guessMime(url: String): String = MediaTypeDetector.mimeOf(url)

    /** 兼容旧调用：返回所有发现的 URL。 */
    protected fun extractMediaUrls(html: String): List<String> = discoverResources(html).map { it.url }

    protected fun extractLivePhoto(html: String): Pair<String?, String?> {
        val (c, v) = detectLive(html, discoverResources(html))
        return c?.url to v?.url
    }

    // ---------------------------------------------------------------- 结构化数据 → 资源

    /** JSON-LD 命中的资源优先：顺序由图集数组决定，比正则扫 HTML 更准确。 */
    private fun buildLdResources(ld: JsonLdMedia): List<MediaResource> {
        val out = mutableListOf<MediaResource>()
        ld.imageUrls.forEachIndexed { i, u ->
            val kind = MediaTypeDetector.kindOf(u)
            out += MediaResource(
                url = u,
                mime = MediaTypeDetector.mimeOf(u, kind),
                kind = kind,
                label = if (kind == ResourceKind.ANIMATED) "动图 ${i + 1}" else "图 ${i + 1}",
                index = i + 1,
                headers = mediaHeaders
            )
        }
        ld.thumbnailUrls.filter { u -> out.none { it.url == u } }.forEach { u ->
            out += MediaResource(
                url = u,
                mime = MediaTypeDetector.mimeOf(u),
                kind = ResourceKind.COVER,
                label = "封面",
                headers = mediaHeaders
            )
        }
        ld.videoUrls.forEach { u ->
            out += MediaResource(u, "video/mp4", ResourceKind.VIDEO, label = "视频", headers = mediaHeaders)
        }
        ld.audioUrls.forEach { u ->
            out += MediaResource(
                u,
                MediaTypeDetector.mimeOf(u, ResourceKind.AUDIO),
                ResourceKind.AUDIO,
                label = "音频",
                headers = mediaHeaders
            )
        }
        return out
    }

    /** 合并两类来源后重新编号图集，保证「图 1 / 图 2 …」连续且与展示顺序一致。 */
    private fun renumberImages(list: List<MediaResource>): List<MediaResource> {
        var i = 0
        return list.map { r ->
            if (r.kind == ResourceKind.IMAGE || r.kind == ResourceKind.ANIMATED) {
                i++
                r.copy(index = i, label = if (r.kind == ResourceKind.ANIMATED) "动图 $i" else "图 $i")
            } else r
        }
    }

    // ---------------------------------------------------------------- 统一编排

    protected fun parsePublic(
        shareText: String,
        fetcher: HttpFetcher,
        platform: Platform,
        defaultTitle: String
    ): ParsedMedia {
        val url = ShareLinkNormalizer.urlOf(shareText)
            ?: throw ParseException("分享文本中未找到链接")
        val (page, ld) = fetchForParsing(ShareLinkNormalizer.normalize(url), fetcher)
        val html = page.text()

        // 结构化数据的标题/作者/正文最干净，优先于 meta 与口令兜底
        val title = ld.title.ifBlank { extractTitle(html) }.ifBlank {
            ShareText.extractTitleFallback(shareText).ifBlank { defaultTitle }
        }
        val author = ld.author.ifBlank { extractAuthor(html) }
        val caption = ld.description.ifBlank { extractCaption(html) }

        // 图集以 JSON-LD 为准，再补页面里其它未重复的媒体链接
        val merged = buildLdResources(ld).toMutableList()
        discoverResources(html).forEach { b ->
            if (merged.none { it.url == b.url }) merged.add(b)
        }
        val resources = renumberImages(merged)
        val (liveCover, liveVideo) = detectLive(html, resources)

        val warnings = mutableListOf<String>()
        if (resources.isEmpty() && liveCover == null) {
            warnings.add("该公开页没有暴露可下载的媒体地址（平台返回了未渲染的页面壳）。")
            warnings.add("已自动重试 $SSR_ATTEMPTS 次并切换渲染策略；仍失败时可在「设置 → 解析端点」填入你自己的合规数据源。")
        }

        val notes = mutableListOf<String>()
        notes.add("数据源：公开分享页 ${page.finalUrl.take(120)}")
        if (ld.mediaCount > 0) {
            notes.add(
                "命中 JSON-LD 结构化数据：图 ${ld.imageUrls.size} / 视频 ${ld.videoUrls.size} / 音频 ${ld.audioUrls.size}"
            )
        }
        notes.add("发现资源 ${resources.size} 个（图 ${resources.count { it.isImage }} / 视频 ${resources.count { it.isVideo }} / 音频 ${resources.count { it.isAudio }}）")
        if (resources.isNotEmpty() && resources.none { it.isVideo }) {
            notes.add("本次为图文作品，页面未声明视频直链。")
        }
        if (liveCover != null && liveVideo != null) notes.add("检测到实况图：将合成小米动态照片")

        val type = if (liveCover != null && liveVideo != null) MediaType.LIVE_PHOTO else MediaType.MIXED
        return ParsedMedia(
            platform = platform,
            type = type,
            title = title,
            author = author,
            caption = caption,
            itemId = ShareLinkNormalizer.itemIdOf(page.finalUrl).ifEmpty { ShareLinkNormalizer.itemIdOf(url) },
            sourceUrl = url,
            resources = resources,
            liveCover = liveCover,
            liveVideo = liveVideo,
            warnings = warnings,
            notes = notes
        ).let { if (it.isLivePhoto) it else it.copy(type = it.inferredType()) }
    }
}
