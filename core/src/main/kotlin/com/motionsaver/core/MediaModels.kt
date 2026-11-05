package com.motionsaver.core

/**
 * 支持的内容来源。解析器以可插拔方式注册：新增平台 = 新增一个 MediaParser 实现。
 * GENERIC 是兜底的通用网页解析器，任何 http(s) 链接都能尝试读取公开元信息。
 */
enum class Platform(val label: String, val hostHints: List<String>) {
    DOUYIN("抖音", listOf("douyin.com", "iesdouyin.com", "snssdk1128")),
    XIAOHONGSHU("小红书", listOf("xiaohongshu.com", "xhslink.com", "xhscdn.com")),
    GENERIC("通用网页", emptyList()),
    UNKNOWN("未知", emptyList());

    companion object {
        fun from(text: String): Platform =
            entries.firstOrNull { p ->
                p.hostHints.isNotEmpty() && p.hostHints.any { text.contains(it, ignoreCase = true) }
            } ?: GENERIC
    }
}

/** 资源种类：决定在 UI 里归到哪一栏、落盘时用哪个目录与扩展名。 */
enum class ResourceKind { IMAGE, COVER, ANIMATED, VIDEO, LIVE_VIDEO, AUDIO, TEXT }

/**
 * 作品主类型，对应「图文解析 / 视频解析 / 音频解析 / 动图解析」四类入口。
 * MIXED 表示一个作品同时含有多种资源（例如图文笔记里带视频和 BGM）。
 */
enum class MediaType(val label: String) {
    IMAGE_TEXT("图文"),
    VIDEO("视频"),
    AUDIO("音频"),
    LIVE_PHOTO("动态图"),
    ANIMATED("动图"),
    MIXED("混合")
}

/** 单个可下载资源。 */
data class MediaResource(
    val url: String,
    val mime: String,
    val kind: ResourceKind = ResourceKind.IMAGE,
    val headers: Map<String, String> = emptyMap(),
    val sizeBytes: Long = -1L,
    /** 展示用标签，例如「图 1」「原声 BGM」「1080p」 */
    val label: String = "",
    /** 图集里的序号，从 1 开始；非图集为 -1 */
    val index: Int = -1,
    /** 同一资源的备用直链（不同 CDN / 不同格式），首选失败时依次回退 */
    val alternatives: List<String> = emptyList(),
    /** 原始像素尺寸，0 表示页面未声明 */
    val width: Int = 0,
    val height: Int = 0,
    /** 画质说明，例如「原图 1080×778 · JPEG」 */
    val qualityLabel: String = ""
) {
    val isVideo: Boolean get() = kind == ResourceKind.VIDEO || kind == ResourceKind.LIVE_VIDEO
    val isAudio: Boolean get() = kind == ResourceKind.AUDIO
    val isImage: Boolean get() =
        kind == ResourceKind.IMAGE || kind == ResourceKind.COVER || kind == ResourceKind.ANIMATED

    fun suggestedExtension(): String = when {
        isVideo -> "mp4"
        isAudio -> if (mime.contains("mpeg") || url.contains(".mp3")) "mp3" else "m4a"
        mime.contains("png") || url.contains(".png") -> "png"
        mime.contains("webp") || url.contains(".webp") -> "webp"
        mime.contains("gif") || url.contains(".gif") -> "gif"
        else -> "jpg"
    }
}

/**
 * 一次解析的完整结果。
 * 所有资源平铺在 [resources] 里，UI 按 kind 分组展示、用户按需勾选下载。
 */
data class ParsedMedia(
    val platform: Platform,
    val type: MediaType,
    val title: String,
    val author: String = "",
    /** 正文文案，图文笔记会单独存成 .txt 一起打包 */
    val caption: String = "",
    val itemId: String = "",
    val sourceUrl: String = "",
    val resources: List<MediaResource> = emptyList(),
    val liveCover: MediaResource? = null,
    val liveVideo: MediaResource? = null,
    /** 需要用户注意但不阻断下载的信息（例如公开页未暴露视频直链） */
    val warnings: List<String> = emptyList(),
    /** 诊断信息（命中了哪些字段、来源是什么） */
    val notes: List<String> = emptyList()
) {
    val isLivePhoto: Boolean
        get() = type == MediaType.LIVE_PHOTO && liveCover != null && liveVideo != null

    fun byKind(kind: ResourceKind): List<MediaResource> = resources.filter { it.kind == kind }

    val images: List<MediaResource> get() = resources.filter { it.isImage }
    val videos: List<MediaResource> get() = resources.filter { it.isVideo }
    val audios: List<MediaResource> get() = resources.filter { it.isAudio }

    /**
     * 主类型由资源构成推断。单张图 + 视频视为「视频作品」（那张图是封面），
     * 避免把带封面的视频误标成「混合」。
     */
    fun inferredType(): MediaType {
        if (isLivePhoto) return MediaType.LIVE_PHOTO
        val imgCount = byKind(ResourceKind.IMAGE).size
        val hasVid = byKind(ResourceKind.VIDEO).isNotEmpty()
        val hasAud = byKind(ResourceKind.AUDIO).isNotEmpty()
        val hasAnim = byKind(ResourceKind.ANIMATED).isNotEmpty()
        return when {
            hasVid && imgCount <= 1 && !hasAud && !hasAnim -> MediaType.VIDEO
            hasAud && !hasVid && imgCount <= 1 && !hasAnim -> MediaType.AUDIO
            hasAnim && !hasVid && imgCount == 0 -> MediaType.ANIMATED
            // 图文作品常附带原声 BGM，主类型仍应是「图文」，不要误标成「混合」
            !hasVid && !hasAnim && imgCount > 0 -> MediaType.IMAGE_TEXT
            !hasVid && !hasAud && !hasAnim && imgCount == 0 -> type
            else -> MediaType.MIXED
        }
    }
}
