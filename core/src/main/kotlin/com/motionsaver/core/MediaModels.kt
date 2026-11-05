package com.motionsaver.core

/**
 * 支持的平台。解析器以可插拔方式注册，新增平台只需实现一个 [MediaParser]。
 */
enum class Platform(val label: String, val hostHints: List<String>) {
    DOUYIN("抖音", listOf("douyin.com", "iesdouyin.com", "v.douyin.com", "snssdk1128")),
    XIAOHONGSHU("小红书", listOf("xiaohongshu.com", "xhslink.com", "xhscdn.com")),
    UNKNOWN("未知", emptyList());

    companion object {
        fun from(text: String): Platform =
            entries.firstOrNull { p -> p.hostHints.any { text.contains(it, ignoreCase = true) } }
                ?: UNKNOWN
    }
}

/** 作品类型。LIVE_PHOTO 才会走小米动态照片合成。 */
enum class MediaType { VIDEO, IMAGES, LIVE_PHOTO, AUDIO }

/** 单个可下载资源。 */
data class MediaResource(
    val url: String,
    val mime: String,
    val headers: Map<String, String> = emptyMap(),
    val sizeBytes: Long = -1L
)

/**
 * 解析结果。
 * @param title 作品标题，用于生成下载文件名
 * @param liveVideo 实况/动态照片的动态片段；仅当 type == LIVE_PHOTO 时参与合成
 * @param liveCover 动态照片的静态封面 JPEG
 */
data class ParsedMedia(
    val platform: Platform,
    val type: MediaType,
    val title: String,
    val author: String = "",
    val itemId: String = "",
    val resources: List<MediaResource> = emptyList(),
    val liveCover: MediaResource? = null,
    val liveVideo: MediaResource? = null,
    val warnings: List<String> = emptyList()
) {
    val isLivePhoto: Boolean get() = type == MediaType.LIVE_PHOTO
}
