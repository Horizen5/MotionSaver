package com.motionsaver.core

/**
 * 「公开分享页」解析基类：只读取页面公开元信息（og:* / JSON-LD / meta），
 * 不构造任何平台私有签名、不绕过鉴权、不请求非公开接口。
 *
 * 平台接口策略变化时，具体解析器只需调整选择器；框架与下载链路不受影响。
 *
 * 说明：正则内统一用 \x22 / \x27 表示引号，避免 Kotlin 原始字符串的引号歧义。
 */
abstract class PublicPageParser : MediaParser {

    protected val fetchHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
        "Accept-Language" to "zh-CN,zh;q=0.9"
    )

    protected fun fetchPublicPage(url: String, fetcher: HttpFetcher): HttpFetcher.Response {
        val res = fetcher.get(url, fetchHeaders)
        if (res.code !in 200..299) throw ParseException("公开页返回 HTTP ${res.code}")
        return res
    }

    /** 提取 og:title / <title> / JSON-LD headline 作为作品标题。 */
    protected fun extractTitle(html: String): String {
        listOf(
            Regex("""<meta[^>]+property=[\x22\x27]og:title[\x22\x27][^>]+content=[\x22\x27]([^\x22\x27]+)[\x22\x27]""", RegexOption.IGNORE_CASE),
            Regex("""<meta[^>]+content=[\x22\x27]([^\x22\x27]+)[\x22\x27][^>]+property=[\x22\x27]og:title[\x22\x27]""", RegexOption.IGNORE_CASE),
            Regex("""<meta[^>]+name=[\x22\x27]twitter:title[\x22\x27][^>]+content=[\x22\x27]([^\x22\x27]+)[\x22\x27]""", RegexOption.IGNORE_CASE),
            Regex("""<title>([^<]+)</title>""", RegexOption.IGNORE_CASE),
            Regex("""\x22headline\x22\s*:\s*\x22([^\x22]+)\x22""")
        ).forEach { r ->
            r.find(html)?.groupValues?.get(1)?.let { t ->
                val decoded = decodeEntities(t.trim())
                if (decoded.isNotBlank()) return decoded
            }
        }
        return ""
    }

    protected fun extractMeta(html: String, property: String): String? {
        val r = Regex(
            """<meta[^>]+(?:property|name)=[\x22\x27]${Regex.escape(property)}[\x22\x27][^>]+content=[\x22\x27]([^\x22\x27]+)[\x22\x27]""",
            RegexOption.IGNORE_CASE
        )
        return r.find(html)?.groupValues?.get(1)?.let { decodeEntities(it) }
    }

    /** 从 HTML 中找出页面公开声明的图片/视频资源。 */
    protected fun extractMediaUrls(html: String): List<String> {
        val urls = linkedSetOf<String>()
        listOf(
            Regex("""<meta[^>]+property=[\x22\x27]og:image[\x22\x27][^>]+content=[\x22\x27]([^\x22\x27]+)[\x22\x27]""", RegexOption.IGNORE_CASE),
            Regex("""<meta[^>]+property=[\x22\x27]og:video(?::url|:secure_url)?[\x22\x27][^>]+content=[\x22\x27]([^\x22\x27]+)[\x22\x27]""", RegexOption.IGNORE_CASE),
            Regex("""\x22url_list\x22\s*:\s*\[\s*\x22([^\x22]+)"""),
            Regex("""\x22url_default\x22\s*:\s*\x22([^\x22]+)\x22""")
        ).forEach { r ->
            r.findAll(html).forEach { m ->
                m.groupValues[1].let { u -> if (u.startsWith("http")) urls.add(unescapeJson(u)) }
            }
        }
        return urls.toList()
    }

    /** 从页面公开 JSON 中识别实况图（Live Photo）的封面与动态片段。 */
    protected fun extractLivePhoto(html: String): Pair<String?, String?> {
        val cover = Regex("""\x22(?:cover|static_url|image_url)\x22\s*:\s*\x22([^\x22]+)\x22""")
            .find(html)?.groupValues?.get(1)
        val video = Regex("""\x22(?:video_url|live_video|play_addr)\x22?\s*:?\s*\x22([^\x22]+\.mp4[^\x22]*)\x22""")
            .find(html)?.groupValues?.get(1)
        return unescape(cover) to unescape(video)
    }

    private fun unescape(s: String?): String? = s?.let { unescapeJson(it) }?.takeIf { it.startsWith("http") }

    protected fun decodeEntities(s: String): String = s
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")

    protected fun unescapeJson(s: String): String =
        s.replace("\\u002F", "/").replace("\\/", "/").replace("&amp;", "&")

    protected fun guessMime(url: String): String = when {
        url.contains(".mp4") || url.contains("video") -> "video/mp4"
        url.contains(".m4a") || url.contains("audio") -> "audio/mp4"
        url.contains(".png") -> "image/png"
        url.contains(".webp") -> "image/webp"
        else -> "image/jpeg"
    }
}
