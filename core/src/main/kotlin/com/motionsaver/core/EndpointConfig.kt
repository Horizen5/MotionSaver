package com.motionsaver.core

import java.net.URLEncoder

/**
 * 自填解析端点配置。
 *
 * 公开分享页常常只暴露封面与标题，拿不到视频/音频直链。要拿到完整四类资源，
 * 最稳妥的做法是让用户填一个自己有授权的数据源（自建服务 / yt-dlp 自托管 / 已授权 API）。
 * 本类只描述「URL 模板 + 请求头 + JSON 字段映射」，App 设置页可直接编辑，无需改代码、无需重编译。
 */
data class FieldMap(
    val title: String = "title",
    val author: String = "author",
    val caption: String = "caption",
    val itemId: String = "id",
    val images: String = "images",
    val videos: String = "videos",
    val audios: String = "audios",
    val animated: String = "animated",
    val cover: String = "cover",
    val liveVideo: String = "live_video"
)

data class EndpointSpec(
    val enabled: Boolean = false,
    /** 例如 https://your.host/parse?url={url}；{url} 会被 URL 编码后的分享链接替换 */
    val urlTemplate: String = "",
    /** 每行一个 Header，形如 Authorization: Bearer xxx */
    val headers: Map<String, String> = emptyMap(),
    val fields: FieldMap = FieldMap()
) {
    fun isValid(): Boolean =
        enabled && urlTemplate.contains("{url}") && urlTemplate.startsWith("http")

    fun buildUrl(shareUrl: String): String {
        val encoded = URLEncoder.encode(shareUrl, Charsets.UTF_8.name())
        var u = urlTemplate.replace("{url}", encoded)
        u = u.replace("{raw}", shareUrl)
        return u
    }

    /** 把「一行一个 Header」的文本解析成 Map。 */
    companion object {
        fun parseHeaders(text: String): Map<String, String> =
            text.lines().mapNotNull { line ->
                val i = line.indexOf(':')
                if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
            }.toMap()

        fun headersToText(headers: Map<String, String>): String =
            headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
    }
}
