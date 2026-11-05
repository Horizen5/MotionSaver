package com.motionsaver.core

/**
 * 平台解析器。三者共用 [PublicPageParser.parsePublic] 的资源发现与归类逻辑，
 * 各自只负责「识别自己的链接」和「标题兜底文案」，新增平台成本极低。
 *
 * 合规边界：只请求用户粘贴的公开分享链接，读取页面自身公开的元信息；
 * 不生成 a_bogus / x-s / msToken 等私有签名，不请求需要鉴权的内部接口。
 */
class DouyinParser : PublicPageParser() {
    override val platform = Platform.DOUYIN
    override fun canHandle(shareText: String): Boolean =
        Platform.from(shareText) == Platform.DOUYIN
    override fun parse(shareText: String, fetcher: HttpFetcher): ParsedMedia =
        parsePublic(shareText, fetcher, Platform.DOUYIN, "抖音作品")
}

class XiaohongshuParser : PublicPageParser() {
    override val platform = Platform.XIAOHONGSHU
    override fun canHandle(shareText: String): Boolean =
        Platform.from(shareText) == Platform.XIAOHONGSHU
    override fun parse(shareText: String, fetcher: HttpFetcher): ParsedMedia =
        parsePublic(shareText, fetcher, Platform.XIAOHONGSHU, "小红书笔记")
}

/** 兜底：任意 http(s) 网页都按公开元信息解析，做到「任何链接都能试」。 */
class GenericParser : PublicPageParser() {
    override val platform = Platform.GENERIC
    override fun canHandle(shareText: String): Boolean =
        ShareLinkNormalizer.urlOf(shareText) != null
    override fun parse(shareText: String, fetcher: HttpFetcher): ParsedMedia =
        parsePublic(shareText, fetcher, Platform.GENERIC, "网页作品")
}

/**
 * 解析器注册表。优先级：自填端点（若启用）→ 抖音 → 小红书 → 通用网页。
 * 新增平台只需在 [defaultParsers] 里插入一个实现，下载链路完全不用改。
 */
class ParserRegistry(parsers: List<MediaParser> = defaultParsers()) {

    private val list = parsers.toList()

    fun parse(shareText: String, fetcher: HttpFetcher): ParsedMedia {
        val parser = list.firstOrNull { it.canHandle(shareText) }
            ?: throw ParseException("分享文本中未找到可解析的链接")
        return parser.parse(shareText, fetcher)
    }

    fun supported(): String = list.joinToString("、") { it.platform.label }

    /** 只看第一个链接，用于解析前预览（不发起网络请求）。 */
    fun previewPlatform(shareText: String): Platform =
        list.firstOrNull { it.canHandle(shareText) }?.platform ?: Platform.UNKNOWN

    companion object {
        fun defaultParsers(endpoint: EndpointSpec? = null): List<MediaParser> = buildList {
            if (endpoint != null && endpoint.isValid()) add(EndpointParser(endpoint))
            add(DouyinParser())
            add(XiaohongshuParser())
            add(GenericParser())
        }
    }
}
