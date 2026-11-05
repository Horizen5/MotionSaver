package com.motionsaver.core

/**
 * 极简 JSON 读取器。core 是纯 JVM 模块且不引第三方库，这里用正则覆盖三类常见结构：
 * 字符串字段、字符串数组、以及对象数组里的字段。足够对接自填端点返回的 JSON。
 *
 * 说明：正则统一用 \x22 / \x27 表示引号，由正则引擎解析，避开 Kotlin 字符串转义限制。
 */
object MiniJson {

    private val STR_FIELD =
        Regex("""\x22([^\x22]+)\x22\s*:\s*\x22((?:\\.|[^\x22\\])*)\x22""")
    private val ARR_FIELD =
        Regex("""\x22([^\x22]+)\x22\s*:\s*\[([^\]]*)\]""")
    private val QUOTED =
        Regex("""\x22((?:\\.|[^\x22\\])*)\x22""")

    /** 取第一个匹配 key 的字符串值。 */
    fun stringField(json: String, key: String): String? =
        STR_FIELD.findAll(json)
            .firstOrNull { it.groupValues[1] == key }
            ?.groupValues?.get(2)
            ?.let { unescape(it) }

    /** 取所有匹配 key 的字符串值（用于跨层级查找，例如嵌套在 item 里的 url）。 */
    fun allStringFields(json: String, key: String): List<String> =
        STR_FIELD.findAll(json)
            .filter { it.groupValues[1] == key }
            .map { unescape(it.groupValues[2]) }
            .toList()

    /** 取 key 对应数组里所有字符串（忽略数字 / 对象元素）。 */
    fun stringArray(json: String, key: String): List<String> {
        val body = ARR_FIELD.findAll(json)
            .firstOrNull { it.groupValues[1] == key }
            ?.groupValues?.get(2)
            ?: return emptyList()
        return QUOTED.findAll(body).map { unescape(it.groupValues[1]) }.toList()
    }

    /** 从整个 JSON 里找出所有形如 http(s) 的字符串，作为兜底资源发现。 */
    fun allUrls(json: String): List<String> =
        QUOTED.findAll(json)
            .map { unescape(it.groupValues[1]) }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .toList()

    fun unescape(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val n = s[i + 1]
                when (n) {
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append('\u000C'); i += 2 }
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    'u' -> {
                        if (i + 5 < s.length) {
                            runCatching {
                                sb.append(s.substring(i + 2, i + 6).toInt(16).toChar())
                            }
                            i += 6
                        } else {
                            sb.append(c); i++
                        }
                    }
                    else -> { sb.append(n); i += 2 }
                }
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
    }
}
