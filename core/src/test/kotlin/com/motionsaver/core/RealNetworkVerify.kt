package com.motionsaver.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime

/**
 * 端到端真实验证：用**编译后的 App 解析代码**（DouyinParser → PublicPageParser →
 * RouterDataParser）配合真实网络跑一遍用户原口令，把每一次取页的 URL / Cookie /
 * HTTP 状态 / 返回体特征，以及最终 ParsedMedia 全部落盘。
 *
 * 这不是 curl 手工模拟：解析、归一化、访客 Cookie 注册、多策略重试、四类资源
 * 归类走的都是线上 APK 里那一份 core 代码；唯一差异是网络层用 JDK
 * HttpURLConnection 代替 OkHttpFetcher（两者行为等价：设置 header + 跟随重定向）。
 *
 * 运行：./gradlew :core:test --tests '*RealNetworkVerify*' --rerun-tasks
 * 产物：/workspace/MotionSaver-端到端验证.txt
 */
class RealNetworkVerify {

    private class JdkFetcher : HttpFetcher {
        val log = StringBuilder()
        var requests = 0

        private fun execute(
            url: String,
            method: String,
            headers: Map<String, String>,
            body: String?
        ): HttpFetcher.Response {
            requests++
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 25_000
            conn.instanceFollowRedirects = true
            conn.requestMethod = method
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val bytes = try {
                conn.inputStream.use { it.readBytes() }
            } catch (e: Exception) {
                conn.errorStream?.use { it.readBytes() } ?: ByteArray(0)
            }
            val flat = mutableMapOf<String, String>()
            conn.headerFields.forEach { (k, vs) ->
                if (k != null) flat[k] = vs.joinToString(" | ")
            }
            val finalUrl = conn.url.toString()
            val text = bytes.toString(Charsets.UTF_8)
            val cookie = headers["Cookie"]?.let {
                if (it.contains("ttwid=")) "带访客Cookie" else "带Cookie"
            } ?: "无Cookie"
            val ua = headers["User-Agent"]?.let {
                when {
                    it.contains("Googlebot", true) || it.contains("bot", true) -> "爬虫UA"
                    else -> "手机UA"
                }
            } ?: "-"
            log.appendLine("  #$requests $method [$cookie/$ua] ${url.take(110)}")
            log.appendLine(
                "      → HTTP $code, ${bytes.size} B, final=${finalUrl.take(100)}"
            )
            log.appendLine(
                "      videoInfoRes=${text.contains("videoInfoRes")} " +
                    "item_list=${text.contains("item_list")} " +
                    "images=${text.contains("\"images\"")} " +
                    "url_list=${text.contains("url_list")} " +
                    "JSON-LD=${text.contains("application/ld+json")} " +
                    "filter_reason=" + Regex(""""filter_reason"\s*:\s*"([^"]+)"""")
                        .find(text)?.groupValues?.get(1).orEmpty().ifEmpty { "无" }
            )
            conn.disconnect()
            return HttpFetcher.Response(code, finalUrl, conn.contentType.orEmpty(), bytes, flat)
        }

        override fun get(url: String, headers: Map<String, String>): HttpFetcher.Response =
            try {
                execute(url, "GET", headers, null)
            } catch (e: Exception) {
                log.appendLine("  #${requests + 1} GET 异常: ${e.javaClass.simpleName}: ${e.message}")
                HttpFetcher.Response(-1, url, "", ByteArray(0))
            }

        override fun post(url: String, body: String, headers: Map<String, String>): HttpFetcher.Response =
            try {
                execute(url, "POST", headers, body)
            } catch (e: Exception) {
                log.appendLine("  #${requests + 1} POST 异常: ${e.javaClass.simpleName}: ${e.message}")
                HttpFetcher.Response(-1, url, "", ByteArray(0))
            }
    }

    private val userShare =
        "4.61 复制打开抖音，看看【陌沫つ゛的图文作品】# 阿哈的游戏 # 崩坏星穹铁道 # 流萤 " +
            "https://v.douyin.com/dBhPfcEmyLc/ L@W.zg 12/17 :0pm VYm:/"
    private val controlShare =
        "8.43 沙龙月季 # 粉色蔷薇花墙  https://v.douyin.com/vaYOcAwFVf4/ " +
            "复制此链接，打开抖音搜索，直接观看视频！ u@S.yg 03/05 MwF:/ :6pm"

    @Test
    fun `真实网络端到端验证`() {
        val out = StringBuilder()
        out.appendLine("MotionSaver 端到端真实验证")
        out.appendLine("时间：${LocalDateTime.now()}")
        out.appendLine("代码：编译后的 core 主源码（与线上 APK 同一份），网络层 JDK HttpURLConnection（等价 OkHttpFetcher）")
        out.appendLine("=".repeat(78))

        var controlImages = -1
        listOf(
            "A 用户反馈的链接 dBhPfcEmyLc" to userShare,
            "B 对照公开作品 沙龙月季 vaYOcAwFVf4" to controlShare
        ).forEach { (label, share) ->
            val f = JdkFetcher()
            out.appendLine()
            out.appendLine("【$label】")
            out.appendLine("口令：$share")
            out.appendLine("-".repeat(78))
            val result = runCatching { DouyinParser().parse(share, f) }
            out.appendLine("取页明细（共 ${f.requests} 次请求）：")
            out.append(f.log)
            out.appendLine("-".repeat(78))
            val p = result.getOrNull()
            if (p == null) {
                out.appendLine("解析抛出异常：${result.exceptionOrNull()}")
            } else {
                out.appendLine("最终 ParsedMedia：")
                out.appendLine("  标题    = ${p.title}")
                out.appendLine("  作者    = ${p.author}")
                out.appendLine("  类型    = ${p.type.label}")
                out.appendLine("  资源总数= ${p.resources.size}  (图 ${p.images.size} / 视频 ${p.videos.size} / 音频 ${p.audios.size})")
                p.resources.take(4).forEach { out.appendLine("    · ${it.label} ${it.url.take(110)}") }
                out.appendLine("  warnings(${p.warnings.size})：")
                p.warnings.forEach { out.appendLine("    ⚠ $it") }
                out.appendLine("  notes(${p.notes.size})：")
                p.notes.forEach { out.appendLine("    · $it") }
                if (label.startsWith("B")) controlImages = p.images.size
            }
        }

        out.appendLine()
        out.appendLine("=".repeat(78))
        out.appendLine("结论：对照作品 B 在同一份解析代码、同一网络下解析出 $controlImages 张图；")
        out.appendLine("      用户链接 A 的资源数以上方为准。若 A=0 且平台返回 status_audit_not_pass，")
        out.appendLine("      说明是平台未开放媒体，而非 App 解析链路故障。")
        File("/workspace/MotionSaver-端到端验证.txt").writeText(out.toString())
        println(out.toString())
        assertTrue("对照公开作品必须能解析出图片，否则说明网络/代码本身有问题", controlImages >= 1)
    }
}
