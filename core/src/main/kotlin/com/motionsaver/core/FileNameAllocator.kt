package com.motionsaver.core

/**
 * 把作品标题变成最终落盘文件名（不含扩展名），并处理重名。
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

    private fun truncate(s: String, max: Int): String =
        if (s.codePointCount(0, s.length) <= max) s
        else s.substring(0, s.offsetByCodePoints(0, max.coerceAtLeast(1)))
}
