package com.motionsaver.core

/**
 * 把作品标题清洗成合法、可读、跨平台一致的文件名。
 *
 * 规则（与需求对齐）：
 * 1. 去掉 emoji / 符号类字符 / 控制字符，保留中英日韩文字、数字、常见标点
 * 2. 去掉文件系统非法字符  \ / : * ? " < > |  以及 Windows 尾部点与空格
 * 3. 连续空白折叠为一个空格，去首尾空白
 * 4. 按 Unicode code point 截断到 [maxLength]（不会切断 surrogate pair）
 * 5. 结果为空时回退到 [fallback]
 */
object TitleSanitizer {

    const val DEFAULT_MAX_LENGTH = 80
    const val DEFAULT_FALLBACK = "untitled"

    private val ILLEGAL = setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')

    fun sanitize(raw: String?, maxLength: Int = DEFAULT_MAX_LENGTH, fallback: String = DEFAULT_FALLBACK): String {
        if (raw.isNullOrEmpty()) return fallback
        require(maxLength > 0) { "maxLength must be positive" }

        val kept = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            i += Character.charCount(cp)
            when {
                // 控制字符 / 空白 -> 一个空格占位
                Character.isISOControl(cp) || Character.isWhitespace(cp) -> {
                    if (kept.isNotEmpty() && kept.last() != ' ') kept.append(' ')
                }
                // 文件系统非法字符 -> 替换为空格，避免把 "A/B" 粘成 "AB"
                cp < 128 && ILLEGAL.contains(cp.toChar()) -> {
                    if (kept.isNotEmpty() && kept.last() != ' ') kept.append(' ')
                }
                // 允许的标点
                isAllowedPunctuation(cp) -> kept.appendCodePoint(cp)
                // 文字与数字
                Character.isLetterOrDigit(cp) -> kept.appendCodePoint(cp)
                // 其余（emoji、装饰符号、方向控制符等）丢弃
                else -> Unit
            }
        }

        var result = normalizeSpaces(kept.toString())
            // Windows / 部分文件系统不允许以点结尾
            .trimEnd('.', ' ')

        if (result.length > maxLength) {
            result = truncateByCodePoint(result, maxLength).trimEnd('.', ' ')
        }
        return result.ifEmpty { fallback }
    }

    /** 按 code point 截断，避免把 emoji / 增补汉字切成半个代理对。 */
    private fun truncateByCodePoint(s: String, max: Int): String {
        if (s.codePointCount(0, s.length) <= max) return s
        val end = s.offsetByCodePoints(0, max)
        return s.substring(0, end)
    }

    /**
     * 折叠空白，并删除「两个汉字之间」的空格：中文标题不该被空格打断，
     * 但中英文、英文与数字之间的空格要保留（如「夏日海边 vlog 2026」）。
     */
    private fun normalizeSpaces(s: String): String {
        val collapsed = s.replace(Regex(" {2,}"), " ").trim()
        if (collapsed.length < 3) return collapsed
        val sb = StringBuilder(collapsed.length)
        for (i in collapsed.indices) {
            val c = collapsed[i]
            if (c == ' ' && i > 0 && i < collapsed.length - 1 &&
                isCjk(collapsed[i - 1]) && isCjk(collapsed[i + 1])
            ) continue
            sb.append(c)
        }
        return sb.toString()
    }

    private fun isCjk(c: Char): Boolean = when (c.code) {
        in 0x3400..0x4DBF, in 0x4E00..0x9FFF, in 0xF900..0xFAFF,
        in 0x3040..0x30FF, in 0xAC00..0xD7AF -> true
        else -> false
    }

    private fun isAllowedPunctuation(cp: Int): Boolean = when (cp) {
        '-'.code, '_'.code, '.'.code, ','.code, '!'.code, '\''.code,
        '('.code, ')'.code, '['.code, ']'.code, '{'.code, '}'.code,
        '&'.code, '+'.code, '='.code, '#'.code, '@'.code, '%'.code, '~'.code,
        '·'.code, '—'.code, '…'.code, '、'.code, '，'.code, '。'.code,
        '！'.code, '？'.code, '：'.code, '；'.code, '（'.code, '）'.code,
        '【'.code, '】'.code, '《'.code, '》'.code, '「'.code, '」'.code -> true
        else -> false
    }
}
