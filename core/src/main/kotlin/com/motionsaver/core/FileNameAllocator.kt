package com.motionsaver.core

/**
 * 把作品标题变成最终落盘文件名（不含路径），并处理重名。
 * 去重用「已存在文件名集合」判断，纯 JVM、无平台依赖，便于测试。
 */
object FileNameAllocator {

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
