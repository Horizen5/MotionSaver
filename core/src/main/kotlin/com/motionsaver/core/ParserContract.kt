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

    /** 去掉链接与口令噪音后剩下的文字，可作为标题兜底。 */
    fun extractTitleFallback(text: String): String =
        text.replace(URL, " ")
            .replace(NOISE, " ")
            .replace(Regex("""[\p{Cntrl}]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(120)
}
