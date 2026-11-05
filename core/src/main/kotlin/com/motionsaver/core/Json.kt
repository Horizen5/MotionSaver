package com.motionsaver.core

/**
 * 零依赖递归下降 JSON 解析器（core 是纯 JVM 模块，不引第三方库）。
 *
 * [MiniJson] 的正则方案适合扁平字段，但平台 SSR 数据（_ROUTER_DATA）里
 * `images[].url_list` 是嵌套数组，必须用真正的解析器才能可靠取值。
 * 输出类型：Map<String,Any?> / List<Any?> / String / Long / Double / Boolean / null。
 */
object Json {

    fun parse(text: String): Any? = runCatching { Parser(text).run { parseValue() } }.getOrNull()

    @Suppress("UNCHECKED_CAST")
    fun obj(v: Any?): Map<String, Any?> = v as? Map<String, Any?> ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    fun arr(v: Any?): List<Any?> = v as? List<Any?> ?: emptyList()

    fun str(v: Any?): String = when (v) {
        is String -> v
        is Number -> v.toString()
        is Boolean -> v.toString()
        else -> ""
    }

    fun strings(v: Any?): List<String> = arr(v).mapNotNull { it as? String }.filter { it.isNotBlank() }

    fun int(v: Any?): Int = when (v) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull() ?: 0
        else -> 0
    }

    /** 按路径逐层取 Map 字段，任一层缺失返回 null。 */
    fun path(root: Any?, vararg keys: String): Any? {
        var cur = root
        for (k in keys) {
            cur = obj(cur)[k] ?: return null
        }
        return cur
    }

    /** 在整个结构里深度查找第一个名为 [key] 的字段（用于结构微变时兜底）。 */
    fun findFirst(root: Any?, key: String): Any? {
        when (root) {
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val m = root as Map<String, Any?>
                if (m.containsKey(key)) return m[key]
                for (v in m.values) findFirst(v, key)?.let { return it }
            }
            is List<*> -> for (v in root) findFirst(v, key)?.let { return it }
        }
        return null
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun parseValue(): Any? {
            skipWs()
            if (i >= s.length) return null
            return when (s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> parseNumber()
            }
        }

        private fun parseObject(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++ // {
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (i < s.length) {
                skipWs()
                if (s[i] != '"') break
                val k = parseString()
                skipWs()
                if (i >= s.length || s[i] != ':') break
                i++
                m[k] = parseValue()
                skipWs()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == '}') { i++; break }
                break
            }
            return m
        }

        private fun parseArray(): List<Any?> {
            val l = ArrayList<Any?>()
            i++ // [
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (i < s.length) {
                l.add(parseValue())
                skipWs()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == ']') { i++; break }
                break
            }
            return l
        }

        private fun parseString(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) break
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 <= s.length) {
                                    runCatching { sb.append(s.substring(i, i + 4).toInt(16).toChar()) }
                                    i += 4
                                }
                            }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        private fun parseNumber(): Number {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val raw = s.substring(start, i)
            return raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: 0L
        }

        private fun expect(word: String) {
            if (s.startsWith(word, i)) i += word.length
        }

        private fun skipWs() {
            while (i < s.length && s[i] <= ' ') i++
        }
    }
}
