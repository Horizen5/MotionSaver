package com.motionsaver.core

/**
 * 页面内 `<script type="application/ld+json">` 的 schema.org 结构化数据解析结果。
 *
 * 抖音、小红书等平台为了被搜索引擎收录，会在服务端渲染（SSR）时把作品标题、
 * 图集、作者、发布时间以 JSON-LD 写进 HTML。这是页面自身公开的元信息：
 * 不构造平台私有签名、不请求需要鉴权的内部接口、不绕过登录，浏览器直接可见。
 *
 * 实测：抖音图文分享页 `www.iesdouyin.com/share/note/{id}/` 在搜索引擎爬虫 UA 下
 * 返回带 `article` JSON-LD 的页面，`image` 数组即完整图集直链。
 */
data class JsonLdMedia(
    val type: String = "",
    val title: String = "",
    val description: String = "",
    val author: String = "",
    val publishedAt: String = "",
    val imageUrls: List<String> = emptyList(),
    val thumbnailUrls: List<String> = emptyList(),
    val videoUrls: List<String> = emptyList(),
    val audioUrls: List<String> = emptyList()
) {
    val mediaCount: Int
        get() = imageUrls.size + thumbnailUrls.size + videoUrls.size + audioUrls.size
}

object JsonLdParser {

    private val BLOCK = Regex(
        """<script\b[^>]*application/ld\+json[^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val AUTHOR_START = Regex("""\x22author\x22\s*:\s*\{""")
    private val QUOTED = Regex("""\x22((?:\\.|[^\x22\\])*)\x22""")

    private val MEDIA_TYPES = listOf(
        "article", "videoobject", "imageobject", "audioobject", "creativework",
        "blogposting", "socialmediaposting", "newsarticle", "photograph", "clip"
    )
    private val NOISE_URL = Regex(
        """schema\.org|/aweme-avatar|/100x100/|avatar|logo|favicon""",
        RegexOption.IGNORE_CASE
    )
    private val VIDEO_EXT = Regex("""\.(mp4|m3u8|mov|webm|mkv)(\?|$)""", RegexOption.IGNORE_CASE)
    private val AUDIO_EXT = Regex("""\.(mp3|m4a|aac|flac|ogg|wav|opus)(\?|$)""", RegexOption.IGNORE_CASE)

    fun parse(html: String): JsonLdMedia {
        val blocks = BLOCK.findAll(html)
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() }
            .toList()
        if (blocks.isEmpty()) return JsonLdMedia()

        var type = ""
        var title = ""
        var description = ""
        var author = ""
        var published = ""
        val images = linkedSetOf<String>()
        val thumbs = linkedSetOf<String>()
        val videos = linkedSetOf<String>()
        val audios = linkedSetOf<String>()

        for (block in blocks) {
            val blockType = MiniJson.stringField(block, "@type").orEmpty()
            // BreadcrumbList 之类只含站点导航，不拿它的 name 当标题
            val isMediaBlock = blockType.isBlank() ||
                MEDIA_TYPES.any { blockType.contains(it, ignoreCase = true) } ||
                blocks.size == 1

            // 1) 图集：只取数组形式，天然避开 author.image（头像，是单字符串）
            arrayValues(block, "image").forEach { if (isUsable(it)) images.add(it) }
            arrayValues(block, "hasPart").forEach { if (isUsable(it)) images.add(it) }
            arrayValues(block, "thumbnailUrl").forEach { if (isUsable(it)) thumbs.add(it) }
            stringValue(block, "thumbnailUrl")?.let { if (isUsable(it)) thumbs.add(it) }

            // 2) 视频 / 音频：contentUrl、embedUrl 及显式数组
            val content = arrayValues(block, "contentUrl") + listOfNotNull(stringValue(block, "contentUrl"))
            val embed = arrayValues(block, "embedUrl") + listOfNotNull(stringValue(block, "embedUrl"))
            (content + embed).forEach { u ->
                if (!isUsable(u)) return@forEach
                when {
                    VIDEO_EXT.containsMatchIn(u) || u.contains("/video/", true) -> videos.add(u)
                    AUDIO_EXT.containsMatchIn(u) || u.contains("/audio/", true) -> audios.add(u)
                }
            }
            arrayValues(block, "video").forEach { if (isUsable(it)) videos.add(it) }
            arrayValues(block, "audio").forEach { if (isUsable(it)) audios.add(it) }

            if (!isMediaBlock) continue
            if (type.isBlank() && blockType.isNotBlank()) type = blockType
            if (title.isBlank()) title = firstNonBlank(
                stringValue(block, "headline"),
                stringValue(block, "name"),
                stringValue(block, "title")
            )
            if (description.isBlank()) description = firstNonBlank(
                stringValue(block, "articleBody"),
                stringValue(block, "description"),
                stringValue(block, "caption")
            )
            if (author.isBlank()) author = authorOf(block)
            if (published.isBlank()) published = firstNonBlank(
                stringValue(block, "datePublished"),
                stringValue(block, "uploadDate")
            )
        }

        // 图集里若混进了和封面/视频相同的地址，去重
        thumbs.removeAll(images)
        videos.removeAll(images)
        audios.removeAll(images)
        return JsonLdMedia(
            type = type,
            title = title.trim(),
            description = description.trim(),
            author = author.trim(),
            publishedAt = published.trim(),
            imageUrls = images.toList(),
            thumbnailUrls = thumbs.toList(),
            videoUrls = videos.toList(),
            audioUrls = audios.toList()
        )
    }

    /** 取 `"key": [ ... ]` 里所有字符串值（可能有多个同名 key，全部合并）。 */
    private fun arrayValues(json: String, key: String): List<String> {
        val r = Regex("""\x22${Regex.escape(key)}\x22\s*:\s*\[([^\]]*)\]""")
        val out = linkedSetOf<String>()
        r.findAll(json).forEach { m ->
            QUOTED.findAll(m.groupValues[1]).forEach { q ->
                val v = MiniJson.unescape(q.groupValues[1])
                if (v.startsWith("http")) out.add(v)
            }
        }
        return out.toList()
    }

    /** 取第一个 `"key": "value"` 字符串值。 */
    private fun stringValue(json: String, key: String): String? =
        Regex("""\x22${Regex.escape(key)}\x22\s*:\s*\x22((?:\\.|[^\x22\\])*)\x22""")
            .find(json)?.groupValues?.get(1)
            ?.let { MiniJson.unescape(it) }
            ?.takeIf { it.isNotBlank() }

    /**
     * author 对象里常嵌套 interactionStatistic 数组（内部还有 {}），
     * 不能用「对象内无花括号」的朴素正则；这里从 author 起点往后取一段再找 name。
     */
    private fun authorOf(json: String): String {
        val start = AUTHOR_START.find(json)?.range?.last
        if (start != null) {
            val tail = json.substring(start + 1).take(3000)
            MiniJson.stringField(tail, "name")?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return MiniJson.stringField(json, "name")
            ?.takeIf { it.isNotBlank() && !it.startsWith("http") }
            .orEmpty()
    }

    private fun isUsable(url: String): Boolean =
        url.startsWith("http") && !NOISE_URL.containsMatchIn(url)

    private fun firstNonBlank(vararg values: String?): String =
        values.firstOrNull { !it.isNullOrBlank() }.orEmpty()
}
