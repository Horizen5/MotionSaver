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

    private fun truncate(s: String, max: Int): String =
        if (s.codePointCount(0, s.length) <= max) s
        else s.substring(0, s.offsetByCodePoints(0, max.coerceAtLeast(1)))
}
