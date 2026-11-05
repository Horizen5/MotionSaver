package com.motionsaver.core

/**
 * 自填端点解析器：把用户配置的解析服务返回的 JSON 映射成四类资源。
 *
 * 这是「最开放」的一层：数据源完全由使用者提供并自行负责授权，App 不含任何
 * 硬编码的第三方私有接口、不构造平台签名。填好端点后，图文/视频/音频/动图
 * 四类资源可以一次性完整拿到，下载链路与公开页解析器完全相同。
 */
class EndpointParser(private val spec: EndpointSpec) : MediaParser {

    override val platform: Platform = Platform.GENERIC

    override fun canHandle(shareText: String): Boolean =
        spec.isValid() && ShareLinkNormalizer.urlOf(shareText) != null

    override fun parse(shareText: String, fetcher: HttpFetcher): ParsedMedia {
        val shareUrl = ShareLinkNormalizer.urlOf(shareText)
            ?: throw ParseException("分享文本中未找到链接")
        if (!spec.isValid()) throw ParseException("解析端点未配置完整（需含 {url} 占位符）")

        val res = fetcher.get(spec.buildUrl(shareUrl), spec.headers)
        if (res.code !in 200..299) throw ParseException("解析端点返回 HTTP ${res.code}")
        val json = res.text()

        val f = spec.fields
        val title = firstNonBlank(
            MiniJson.stringField(json, f.title),
            ShareText.extractTitleFallback(shareText),
            "作品"
        )
        val author = MiniJson.stringField(json, f.author).orEmpty()
        val caption = MiniJson.stringField(json, f.caption).orEmpty()
        val itemId = MiniJson.stringField(json, f.itemId).orEmpty()

        val collected = linkedMapOf<String, ResourceKind>()
        fun addAll(key: String, kind: ResourceKind) {
            val urls = MiniJson.stringArray(json, key).ifEmpty { MiniJson.allStringFields(json, key) }
            urls.filter { MediaTypeDetector.isHttp(it) }.forEach { collected.putIfAbsent(it, kind) }
        }
        addAll(f.images, ResourceKind.IMAGE)
        addAll(f.videos, ResourceKind.VIDEO)
        addAll(f.audios, ResourceKind.AUDIO)
        addAll(f.animated, ResourceKind.ANIMATED)

        // 端点未做字段分组时，从整个 JSON 里兜底发现 URL 并按类型归类
        if (collected.isEmpty()) {
            MiniJson.allUrls(json)
                .filter { !it.contains("your.host") }
                .forEach { u -> collected.putIfAbsent(u, MediaTypeDetector.kindOf(u)) }
        }

        val resources = collected.entries.mapIndexed { idx, (url, kind) ->
            MediaResource(
                url = url,
                mime = MediaTypeDetector.mimeOf(url, kind),
                kind = kind,
                headers = emptyMap(),
                label = labelOf(kind, idx + 1),
                index = if (kind == ResourceKind.IMAGE || kind == ResourceKind.ANIMATED) idx + 1 else -1
            )
        }

        val coverUrl = MiniJson.stringField(json, f.cover)
        val liveUrl = MiniJson.stringField(json, f.liveVideo)
        val cover = coverUrl?.takeIf { MediaTypeDetector.isHttp(it) }?.let {
            MediaResource(it, "image/jpeg", ResourceKind.COVER, label = "动态封面")
        }
        val liveVideo = liveUrl?.takeIf { MediaTypeDetector.isHttp(it) }?.let {
            MediaResource(it, "video/mp4", ResourceKind.LIVE_VIDEO, label = "动态片段")
        }

        val notes = mutableListOf<String>()
        notes.add("数据源：自填端点")
        if (resources.isEmpty() && cover == null) notes.add("端点未返回任何媒体资源，请检查字段映射")

        return ParsedMedia(
            platform = Platform.GENERIC,
            type = if (cover != null && liveVideo != null) MediaType.LIVE_PHOTO else MediaType.MIXED,
            title = title,
            author = author,
            caption = caption,
            itemId = itemId,
            sourceUrl = shareUrl,
            resources = resources,
            liveCover = cover,
            liveVideo = liveVideo,
            warnings = if (resources.isEmpty() && cover == null) {
                listOf("解析端点未返回可下载资源，请在设置里检查字段映射或临时关闭端点走公开页。")
            } else emptyList(),
            notes = notes
        ).let { it.copy(type = it.inferredType()) }
    }

    private fun firstNonBlank(vararg values: String?): String =
        values.firstOrNull { !it.isNullOrBlank() }!!.trim()

    private fun labelOf(kind: ResourceKind, i: Int): String = when (kind) {
        ResourceKind.IMAGE -> "图 $i"
        ResourceKind.ANIMATED -> "动图 $i"
        ResourceKind.VIDEO -> "视频 $i"
        ResourceKind.LIVE_VIDEO -> "动态片段"
        ResourceKind.AUDIO -> "音频 $i"
        ResourceKind.COVER -> "封面"
        ResourceKind.TEXT -> "文本"
    }
}
