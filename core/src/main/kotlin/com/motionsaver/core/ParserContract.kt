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

    /** @param range 可选 Range 头值，用于分片下载 */
    fun get(url: String, headers: Map<String, String> = emptyMap()): Response
}

/** 可插拔解析器契约。新增平台 = 新增一个实现并注册，无需改动其它代码。 */
interface MediaParser {
    val platform: Platform
    fun canHandle(shareText: String): Boolean
    fun parse(shareText: String, fetcher: HttpFetcher): ParsedMedia
}

class ParseException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** 从分享文案里抽出第一个 http(s) 链接，兼容抖音/小红书「口令 + 链接」混合文本。 */
object ShareText {
    private val URL = Regex("""https?://[^\s"'<>\p{Cntrl}]+""")

    fun extractUrl(text: String): String? =
        URL.find(text)?.value?.trimEnd('，', '。', ',', '.', '；', ';', '、', ')', ']', '】')

    fun extractTitleFallback(text: String): String =
        text.replace(URL, " ")
            .replace(Regex("""[0-9.]+复制打开抖音|复制此链接|打开[Dd]ouyin|看看|发布在小红书|小红书"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(120)
}
