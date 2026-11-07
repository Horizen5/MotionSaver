package com.motionsaver.core

/**
 * 网络访问抽象。core 不依赖任何 Android / OkHttp 类型，由 app 层注入实现。
 * 这样核心解析逻辑可在纯 JVM 下单元测试，也方便替换数据源。
 */
interface HttpFetcher {
    data class Response(
        val code: Int,
        val finalUrl: String,
        val contentType: String,
        val body: ByteArray,
        val headers: Map<String, String> = emptyMap()
    ) {
        fun text(): String = body.toString(Charsets.UTF_8)
    }

    fun get(url: String, headers: Map<String, String> = emptyMap()): Response

    /** 注册公开访客标识等需要 POST 的场景；默认实现让纯 JVM 测试无需覆盖。 */
    fun post(url: String, body: String, headers: Map<String, String> = emptyMap()): Response =
        Response(405, url, "", ByteArray(0))
}

/** 可插拔解析器契约。新增平台 = 新增一个实现并注册，无需改动其它代码。 */
interface MediaParser {
    val platform: Platform
    fun canHandle(shareText: String): Boolean
    fun parse(shareText: String, fetcher: HttpFetcher): ParsedMedia
}

class ParseException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** 从分享文案里抽出链接、清洗口令，兼容「口令 + 链接」混合文本。 */
object ShareText {
    private val URL = Regex("""https?://[^\s"'<>\p{Cntrl}]+""")

    private val TAIL_PUNCT = charArrayOf(
        '，', '。', ',', '.', '；', ';', '、', ')', ']', '】', '》', '"', '\'', '!', '！', '?', '？'
    )

    private val NOISE = Regex(
        """[0-9.]+复制打开抖音|复制此链接|打开[Dd]ouyin|打开小红书|看看|发布在小红书|长按复制|复制口令|去抖音|戳这里|↑|👇"""
    )

    fun extractUrl(text: String): String? =
        URL.find(text)?.value?.trimEnd(*TAIL_PUNCT)

    /** 一次粘贴多条分享口令时，按出现顺序抽出全部链接并去重。 */
    fun extractUrls(text: String, limit: Int = 10): List<String> =
        URL.findAll(text)
            .map { it.value.trimEnd(*TAIL_PUNCT) }
            .filter { it.startsWith("http") }
            .distinct()
            .take(limit)
            .toList()

    /**
     * 把整段口令按链接切成 N 个「分享片段」，每段 = 该作品的引导文案/话题 + 一个链接。
     *
     * 旧版 parseAll 只把纯 URL 传给解析器，口令里的 `#话题`、文案全被丢掉，
     * 于是标题永远回退成「抖音作品」，不同作品下载后文件名堆叠在一起。
     * 保留每段的文案后，[WorkTitle] 就能为每条链接生成它自己的语义化标题。
     * 单条口令原样返回（含完整文案），不改动 [extractUrl] 的行为。
     */
    fun splitShares(text: String): List<String> {
        val matches = URL.findAll(text).toList()
        if (matches.size <= 1) return listOf(text)
        return matches.mapIndexed { i, m ->
            val start = if (i == 0) 0 else matches[i - 1].range.last + 1
            text.substring(start, m.range.last + 1).trim()
        }.filter { it.isNotBlank() && URL.containsMatchIn(it) }
    }

    /** 去掉链接与口令噪音后剩下的文字，可作为标题兜底。 */
    fun extractTitleFallback(text: String): String =
        text.replace(URL, " ")
            .replace(NOISE, " ")
            .replace(Regex("""[\p{Cntrl}]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(120)
}
