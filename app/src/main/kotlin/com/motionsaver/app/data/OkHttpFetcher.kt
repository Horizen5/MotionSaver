package com.motionsaver.app.data

import com.motionsaver.core.HttpFetcher
import okhttp3.OkHttpClient
import okhttp3.Request

/** core 层 HttpFetcher 接口的 OkHttp 实现。流式下载不在此处，见 [Downloader]。 */
class OkHttpFetcher(private val client: OkHttpClient) : HttpFetcher {

    override fun get(url: String, headers: Map<String, String>): HttpFetcher.Response {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { resp ->
            val body = resp.body?.bytes() ?: ByteArray(0)
            val flatHeaders = resp.headers.toMultimap().mapValues { it.value.joinToString(",") }
            return HttpFetcher.Response(
                code = resp.code,
                finalUrl = resp.request.url.toString(),
                contentType = resp.header("Content-Type").orEmpty(),
                body = body,
                headers = flatHeaders
            )
        }
    }
}
