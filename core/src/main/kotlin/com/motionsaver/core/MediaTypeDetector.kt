package com.motionsaver.core

/** 依据 URL / mime 判定资源属于图文、视频、音频还是动图。 */
object MediaTypeDetector {

    private val VIDEO_HINT = Regex("""\.(mp4|m3u8|mov|webm|mkv|avi)(\?|$)""", RegexOption.IGNORE_CASE)
    private val AUDIO_HINT = Regex("""\.(mp3|m4a|aac|flac|ogg|wav|opus)(\?|$)""", RegexOption.IGNORE_CASE)
    private val GIF_HINT = Regex("""\.(gif|apng)(\?|$)""", RegexOption.IGNORE_CASE)
    private val PNG_HINT = Regex("""\.png(\?|$)""", RegexOption.IGNORE_CASE)
    private val WEBP_HINT = Regex("""\.webp(\?|$)""", RegexOption.IGNORE_CASE)

    fun kindOf(url: String, mimeHint: String = ""): ResourceKind {
        val u = url.lowercase()
        val m = mimeHint.lowercase()
        return when {
            m.startsWith("video") || VIDEO_HINT.containsMatchIn(u) ||
                u.contains("/video/") || u.contains("play_addr") || u.contains("videoid") ->
                ResourceKind.VIDEO

            m.startsWith("audio") || AUDIO_HINT.containsMatchIn(u) ||
                u.contains("/audio/") || u.contains("music") || u.contains("bgm") ->
                ResourceKind.AUDIO

            GIF_HINT.containsMatchIn(u) || u.contains("animated") || u.contains("live_photo") ->
                ResourceKind.ANIMATED

            else -> ResourceKind.IMAGE
        }
    }

    fun mimeOf(url: String, kind: ResourceKind = kindOf(url)): String = when (kind) {
        ResourceKind.VIDEO, ResourceKind.LIVE_VIDEO -> "video/mp4"
        ResourceKind.AUDIO -> if (url.contains(".mp3", true)) "audio/mpeg" else "audio/mp4"
        ResourceKind.ANIMATED -> if (url.contains(".webp", true)) "image/webp" else "image/gif"
        else -> when {
            PNG_HINT.containsMatchIn(url) -> "image/png"
            WEBP_HINT.containsMatchIn(url) -> "image/webp"
            else -> "image/jpeg"
        }
    }

    fun isHttp(url: String): Boolean =
        url.startsWith("http://", true) || url.startsWith("https://", true)

    /** 常规图片 CDN 往往带缩略图后缀；这里只做保守的去参数，不改写路径。 */
    fun stripTracking(url: String): String =
        url.replace(Regex("""[?&](?:from|timestamp|t|sign|token|xy|trace)=[^&]*""", RegexOption.IGNORE_CASE), "")
            .trimEnd('?', '&')
}
