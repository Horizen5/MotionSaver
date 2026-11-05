package com.motionsaver.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/**
 * 流式下载器：bytes 直接落盘，不整体载入内存，几百 MB 视频也不会撑爆堆。
 * 支持 Range 断点续传（服务端返回 206 时从已下载长度继续）。
 */
class Downloader(private val client: OkHttpClient) {

    data class Progress(val downloaded: Long, val total: Long) {
        val fraction: Float get() = if (total > 0) downloaded.toFloat() / total else -1f
    }

    /**
     * @param onProgress 下载进度回调（在 IO 线程）
     * @return 最终文件
     */
    suspend fun download(
        url: String,
        headers: Map<String, String>,
        dest: File,
        onProgress: (Progress) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
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
            var downloaded = existing

            FileOutputStream(dest, append).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        downloaded += n
                        if (downloaded - lastReport > 256 * 1024 || total < 0) {
                            onProgress(Progress(downloaded, total))
                            lastReport = downloaded
                        }
                    }
                }
            }
            onProgress(Progress(downloaded, if (total > 0) total else downloaded))
        }
        dest
    }
}
