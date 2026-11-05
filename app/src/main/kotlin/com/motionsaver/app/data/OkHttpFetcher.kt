package com.motionsaver.app.data

import com.motionsaver.core.HttpFetcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** core 层 HttpFetcher 接口的 OkHttp 实现。流式下载不在此处，见 [Downloader]。 */
class OkHttpFetcher(private val client: OkHttpClient) : HttpFetcher {

    override fun get(url: String, headers: Map<String, String>): HttpFetcher.Response {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        return execute(builder.build())
    }

    override fun post(url: String, body: String, headers: Map<String, String>): HttpFetcher.Response {
        val builder = Request.Builder().url(url)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        headers.forEach { (k, v) -> builder.header(k, v) }
        return execute(builder.build())
    }

    private fun execute(request: Request): HttpFetcher.Response =
        client.newCall(request).execute().use { resp ->
            // Set-Cookie 可能有多条，用 " | " 连接，core 侧正则仍能提取 ttwid
            val flatHeaders = resp.headers.toMultimap().mapValues { it.value.joinToString(" | ") }
            HttpFetcher.Response(
                code = resp.code,
                finalUrl = resp.request.url.toString(),
                contentType = resp.header("Content-Type").orEmpty(),
                body = resp.body?.bytes() ?: ByteArray(0),
                headers = flatHeaders
            )
        }
}
