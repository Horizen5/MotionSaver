package com.motionsaver.core

/**
 * 把作品标题变成最终落盘文件名（不含路径），并处理重名。
 * 去重用「已存在文件名集合」判断，纯 JVM、无平台依赖，便于测试。
 */
object FileNameAllocator {

    /**
     * 给落盘标题附加一个稳定的「作品短码」，避免不同作品同名互相覆盖。
     *
     * 用户反馈「很多图片重叠到一块」：此前所有纯话题作品标题都退化成
     * 「抖音作品」，多张图按 `抖音作品_01.jpg` 命名，不同作品下载后
     * 在相册里堆叠、甚至被去重逻辑判成同名。做法遵循通用文件命名实践
     * 「语义化主体 + 短 hash 保证唯一」：短码取自作品 ID（没有则用链接），
     * 同一作品恒定、不同作品几乎必不相同，且不影响 UI 显示的干净标题。
     */
    fun scopedTitle(title: String, itemId: String, sourceUrl: String): String {
        val seed = itemId.ifBlank { sourceUrl }
        if (seed.isBlank()) return title
        val code = (seed.hashCode().toLong() and 0xFFFFFFL)
            .toString(16).uppercase().padStart(6, '0')
        return "$title-$code"
    }

    fun allocate(
        title: String,
        extension: String,
        existingNames: Set<String>,
        maxLength: Int = TitleSanitizer.DEFAULT_MAX_LENGTH
    ): String {
        val ext = extension.removePrefix(".").ifEmpty { "jpg" }
        val base = TitleSanitizer.sanitize(title, maxLength)
        val candidate = "$base.$ext"
        if (candidate !in existingNames) return candidate

        var n = 2
        while (true) {
            val suffix = "_$n"
            val trimmed = truncate(base, maxLength - suffix.length)
            val name = "$trimmed$suffix.$ext"
            if (name !in existingNames) return name
            n++
        }
    }

    /**
     * 图集序号命名：`标题_01.jpg`、`标题_02.jpg`……
     * 单张时保持干净的名字不带序号。
     */
    fun allocateSeries(
        title: String,
        extension: String,
        index: Int,
        total: Int,
        existingNames: Set<String>,
        maxLength: Int = TitleSanitizer.DEFAULT_MAX_LENGTH
    ): String {
        if (total <= 1 || index < 0) return allocate(title, extension, existingNames, maxLength)
        val suffix = String.format("_%02d", index + 1)
        val base = TitleSanitizer.sanitize(title, (maxLength - suffix.length).coerceAtLeast(8))
        val ext = extension.removePrefix(".").ifEmpty { "jpg" }
        val candidate = "$base$suffix.$ext"
        if (candidate !in existingNames) return candidate
        var n = 2
        while (true) {
            val dup = "_${n}"
            val name = "${truncate(base, (maxLength - suffix.length - dup.length).coerceAtLeast(4))}$suffix$dup.$ext"
            if (name !in existingNames) return name
            n++
        }
    }

    /** 依据资源种类给出附属文件名（正文、BGM 等）。 */
    fun allocateAuxiliary(title: String, tag: String, extension: String, existingNames: Set<String>): String =
        allocate("$title-$tag", extension, existingNames)

    /**
     * 动态照片专用命名，同时满足两代相册的识别条件：
     *  - 小米 / Google Photos 老判定：`MVIMG_` 前缀；
     *  - Android Motion Photo 1.0 正则 `^([^\s/\\][^/\\]*MP)\.(jpg|jpeg|...)$`：扩展名前以 `MP` 结尾。
     * 真机样本名为 `MVIMG_20261106_230518.jpg`，缺前缀时相册常只显示静态封面。
     */
    fun allocateMotionPhoto(title: String, existingNames: Set<String>): String {
        val base = TitleSanitizer.sanitize(title, TitleSanitizer.DEFAULT_MAX_LENGTH - 16)
        // 重名序号必须放在 MP 之前，保证文件名始终以 MP.jpg 结尾（1.0 正则硬要求）
        val stem = "MVIMG_${base}-动态照片"
        var name = "${stem}MP.jpg"
        var n = 2
        while (name in existingNames) {
            name = "$stem-${n}MP.jpg"
            n++
        }
        return name
    }

    private fun truncate(s: String, max: Int): String =
        if (s.codePointCount(0, s.length) <= max) s
        else s.substring(0, s.offsetByCodePoints(0, max.coerceAtLeast(1)))
}
