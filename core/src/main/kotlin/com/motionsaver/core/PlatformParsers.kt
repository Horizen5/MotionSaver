package com.motionsaver.core

/**
 * 抖音解析器（公开分享页路径）。
 *
 * 合规边界：只请求用户粘贴的公开分享链接，读取页面自身公开的元信息；
 * 不生成 a_bogus / msToken 等私有签名，不请求需要鉴权的内部接口。
 * 若页面未公开直链，会返回带 warning 的部分结果，由调用方决定是否提示用户。
 */
class DouyinParser : PublicPageParser() {

    override val platform = Platform.DOUYIN

    override fun canHandle(shareText: String): Boolean =
        Platform.from(shareText) == Platform.DOUYIN

    override fun parse(shareText: String, fetcher: HttpFetcher): ParsedMedia {
        val url = ShareText.extractUrl(shareText)
            ?: ShareText.extractUrl(shareText.trim())
            ?: throw ParseException("分享文本中未找到链接")
        val page = fetchPublicPage(url, fetcher)
        val html = page.text()

        val title = extractTitle(html).ifBlank {
            ShareText.extractTitleFallback(shareText).ifBlank { "抖音作品" }
        }
        val warnings = mutableListOf<String>()
        val urls = extractMediaUrls(html)
        val (liveCover, liveVideo) = extractLivePhoto(html)

        // 页面声明了实况图封面 + 动态片段 → 走小米动态照片合成
        if (liveCover != null && liveVideo != null) {
            return ParsedMedia(
                platform = Platform.DOUYIN,
                type = MediaType.LIVE_PHOTO,
                title = title,
                itemId = extractItemId(page.finalUrl),
                liveCover = MediaResource(liveCover, "image/jpeg"),
                liveVideo = MediaResource(liveVideo, "video/mp4"),
                warnings = warnings
            )
        }

        val videoUrl = urls.firstOrNull { guessMime(it) == "video/mp4" }
        val resources = urls.map { MediaResource(it, guessMime(it)) }
        if (videoUrl == null) {
            warnings.add("公开分享页未暴露视频直链，仅能保存封面图；如需原始视频请接入你自己的合规数据源。")
        }

        return ParsedMedia(
            platform = Platform.DOUYIN,
            type = if (videoUrl != null) MediaType.VIDEO else MediaType.IMAGES,
            title = title,
            itemId = extractItemId(page.finalUrl),
            resources = resources,
            warnings = warnings
        )
    }

    private fun extractItemId(url: String): String =
        Regex("""(?:video|note|modal_id|itemId)[/=](\d{6,})""").find(url)?.groupValues?.get(1)
            ?: Regex("""/([a-zA-Z0-9]{8,})/?(?:[?#]|$)""").find(url)?.groupValues?.get(1)
            ?: ""
}

/**
 * 小红书解析器（占位实现，第二优先级）。
 * 与抖音共用同一套公开页解析框架，后续补齐 Live 图字段映射即可。
 */
class XiaohongshuParser : PublicPageParser() {

    override val platform = Platform.XIAOHONGSHU

    override fun canHandle(shareText: String): Boolean =
        Platform.from(shareText) == Platform.XIAOHONGSHU

    override fun parse(shareText: String, fetcher: HttpFetcher): ParsedMedia {
        val url = ShareText.extractUrl(shareText) ?: throw ParseException("分享文本中未找到链接")
        val page = fetchPublicPage(url, fetcher)
        val html = page.text()

        val title = extractTitle(html).ifBlank {
            ShareText.extractTitleFallback(shareText).ifBlank { "小红书笔记" }
        }
        val urls = extractMediaUrls(html)
        val (liveCover, liveVideo) = extractLivePhoto(html)

        if (liveCover != null && liveVideo != null) {
            return ParsedMedia(
                platform = Platform.XIAOHONGSHU,
                type = MediaType.LIVE_PHOTO,
                title = title,
                liveCover = MediaResource(liveCover, "image/jpeg"),
                liveVideo = MediaResource(liveVideo, "video/mp4")
            )
        }

        val warnings = mutableListOf<String>()
        if (urls.isEmpty()) warnings.add("公开页未暴露任何可下载资源，需接入你自己的合规数据源。")

        return ParsedMedia(
            platform = Platform.XIAOHONGSHU,
            type = if (urls.any { guessMime(it) == "video/mp4" }) MediaType.VIDEO else MediaType.IMAGES,
            title = title,
            resources = urls.map { MediaResource(it, guessMime(it)) },
            warnings = warnings
        )
    }
}

/** 解析器注册表：新增平台只需 register，无需改动下载链路。 */
class ParserRegistry(parsers: List<MediaParser> = defaultParsers()) {

    private val list = parsers.toList()

    fun parse(shareText: String, fetcher: HttpFetcher): ParsedMedia {
        val parser = list.firstOrNull { it.canHandle(shareText) }
            ?: throw ParseException("暂不支持该链接（当前支持：${supported()}）")
        return parser.parse(shareText, fetcher)
    }

    fun supported(): String = list.joinToString("、") { it.platform.label }

    companion object {
        fun defaultParsers(): List<MediaParser> = listOf(DouyinParser(), XiaohongshuParser())
    }
}
