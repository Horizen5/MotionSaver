package com.motionsaver.core

/**
 * 分享链接归一化：把「口令 + 短链」变成可请求的 URL，并识别平台与作品 ID。
 * 纯字符串处理，无网络、无平台依赖，便于单测。
 */
object ShareLinkNormalizer {

    private val SHORT_HOSTS = listOf(
        "v.douyin.com", "xhslink.com", "t.cn", "dwz.cn", "url.cn", "b23.tv", "suo.im"
    )

    /** 从任意分享文案里取出链接；没有链接时返回 null。 */
    fun urlOf(shareText: String): String? = ShareText.extractUrl(shareText)

    /**
     * 图集短链会 302 到 `/share/slides/{id}/`，而该路径只返回未渲染的页面壳
     * （实测 25KB、0 资源）；同一 itemId 的 `/share/note/{id}/` 会返回完整
     * `_ROUTER_DATA`（实测 53KB、3 张图）。因此把 slides 路径统一改写成 note 路径。
     *
     * 短链本身不含该路径，需在网络层跟随重定向拿到 finalUrl 后再调用本函数。
     * 直接粘贴长链时由 [normalize] 提前处理。
     */
    private val SLIDES_PATH = Regex(
        """(?:www\.|m\.)?(?:iesdouyin|douyin)\.com/share/slides/(\d{6,})""",
        RegexOption.IGNORE_CASE
    )

    /** 仅把 slides 页壳改写成同 ID 的 note 页；普通 note / video 链接原样保留。 */
    fun canonicalShareUrl(url: String): String =
        SLIDES_PATH.find(url)?.let { "https://www.iesdouyin.com/share/note/${it.groupValues[1]}/" }
            ?: url

    private val SHARE_PATH = Regex(
        """(?:www\.|m\.)?(?:iesdouyin|douyin)\.com/share/(?:slides|note)/(\d{6,})""",
        RegexOption.IGNORE_CASE
    )

    /**
     * 生成「同一 itemId 的不带参数 /share/note/{id}/」地址，供访客取页首选。
     *
     * 实测：/share/slides/{id}/ 只回 25KB 未渲染页面壳；短链跟随后的
     * /share/note/{id}/?region=...&share_sign=... 与不带参数的同路径都能拿到
     * 完整 _ROUTER_DATA，但去掉参数更稳（避免个别参数触发 SSR 降级）。
     * 完整参数地址仍作为备选策略保留。
     */
    fun noteUrlOf(url: String): String =
        SHARE_PATH.find(url)?.let { "https://www.iesdouyin.com/share/note/${it.groupValues[1]}/" }
            ?: url

    /** 补齐协议、去掉明显误粘的尾部字符。短链原样保留（重定向由网络层处理）。 */
    fun normalize(url: String): String {
        var u = url.trim()
        if (u.startsWith("//")) u = "https:$u"
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        return canonicalShareUrl(u)
    }

    fun isShortLink(url: String): Boolean =
        SHORT_HOSTS.any { url.contains(it, ignoreCase = true) }

    fun platformOf(shareText: String): Platform = Platform.from(shareText)

    /** 提取作品 ID，用于命名兜底与历史去重。 */
    fun itemIdOf(url: String): String {
        listOf(
            Regex("""(?:modal_id|aweme_id|note_id|itemId|video|note|slides|item)[/=]([A-Za-z0-9_-]{6,})"""),
            Regex("""/explore/([A-Za-z0-9]{8,})"""),
            Regex("""/discovery/([A-Za-z0-9]{8,})"""),
            Regex("""/(?:video|share/video|share/slides|share/note|note|slides)/(\d{6,})"""),
            Regex("""/([a-zA-Z0-9]{16,24})/?(?:[?#]|$)""")
        ).forEach { r ->
            r.find(url)?.groupValues?.get(1)?.let { return it }
        }
        return ""
    }
}
