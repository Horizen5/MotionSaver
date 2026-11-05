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

    /** 补齐协议、去掉明显误粘的尾部字符。短链原样保留（重定向由网络层处理）。 */
    fun normalize(url: String): String {
        var u = url.trim()
        if (u.startsWith("//")) u = "https:$u"
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        return u
    }

    fun isShortLink(url: String): Boolean =
        SHORT_HOSTS.any { url.contains(it, ignoreCase = true) }

    fun platformOf(shareText: String): Platform = Platform.from(shareText)

    /** 提取作品 ID，用于命名兜底与历史去重。 */
    fun itemIdOf(url: String): String {
        listOf(
            Regex("""(?:modal_id|aweme_id|note_id|itemId|video|note|item)[/=]([A-Za-z0-9_-]{6,})"""),
            Regex("""/explore/([A-Za-z0-9]{8,})"""),
            Regex("""/discovery/([A-Za-z0-9]{8,})"""),
            Regex("""/(?:video|share/video|note)/(\d{6,})"""),
            Regex("""/([a-zA-Z0-9]{16,24})/?(?:[?#]|$)""")
        ).forEach { r ->
            r.find(url)?.groupValues?.get(1)?.let { return it }
        }
        return ""
    }
}
