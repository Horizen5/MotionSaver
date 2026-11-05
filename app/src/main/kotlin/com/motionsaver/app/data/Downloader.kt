package com.motionsaver.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/**
 * 流式下载器：bytes 直接落盘，不整体载入内存，几百 MB 视频也不会撑爆堆。
 * 支持 Range 断点续传（服务端返回 206 时从已下载长度继续）与指数退避重试。
 */
class Downloader(private val client: OkHttpClient) {

    data class Progress(val downloaded: Long, val total: Long, val label: String = "") {
        val fraction: Float get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else -1f
    }

    suspend fun download(
        url: String,
        headers: Map<String, String>,
        dest: File,
        label: String = "",
        retries: Int = 2,
        onProgress: (Progress) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        var attempt = 0
        var last: Exception? = null
        while (attempt <= retries) {
            try {
                return@withContext doDownload(url, headers, dest, label, onProgress)
            } catch (e: Exception) {
                last = e
                attempt++
                // 断点续传场景：失败时保留已下载部分，下次从该长度继续
                if (attempt <= retries) delay(400L * attempt)
            }
        }
        throw last ?: IllegalStateException("下载失败")
    }

    private fun doDownload(
        url: String,
        headers: Map<String, String>,
        dest: File,
        label: String,
        onProgress: (Progress) -> Unit
    ): File {
        dest.parentFile?.mkdirs()
        val existing = if (dest.exists()) dest.length() else 0L

        val requestHeaders = headers.toMutableMap()
        if (existing > 0) requestHeaders["Range"] = "bytes=$existing-"

        val builder = Request.Builder().url(url)
        requestHeaders.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { resp ->
            if (resp.code != 200 && resp.code != 206) {
                error("下载失败：HTTP ${resp.code}")
            }
            val body = resp.body ?: error("下载失败：空响应体")
            val append = resp.code == 206 && existing > 0
            val contentLength = body.contentLength()
            val total = if (append) existing + contentLength else contentLength
            var downloaded = if (append) existing else 0L

            FileOutputStream(dest, append).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var lastReport = downloaded
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        downloaded += n
                        if (downloaded - lastReport > 256 * 1024 || total < 0) {
                            onProgress(Progress(downloaded, total, label))
                            lastReport = downloaded
                        }
                    }
                }
            }
            onProgress(Progress(downloaded, if (total > 0) total else downloaded, label))
        }
        return dest
    }
}
