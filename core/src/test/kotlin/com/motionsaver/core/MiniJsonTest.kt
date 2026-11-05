package com.motionsaver.core

import org.junit.Assert.*
import org.junit.Test

class MiniJsonTest {

    @Test fun `读取字符串字段`() {
        assertEquals("你好", MiniJson.stringField("""{"title":"你好","n":5}""", "title"))
        assertNull(MiniJson.stringField("""{"n":5}""", "title"))
    }

    @Test fun `读取字符串数组`() {
        val json = """{"images":["https://x/1.jpg","https://x/2.jpg"],"count":2}"""
        assertEquals(listOf("https://x/1.jpg", "https://x/2.jpg"), MiniJson.stringArray(json, "images"))
        assertTrue(MiniJson.stringArray(json, "missing").isEmpty())
    }

    @Test fun `反斜杠与实体转义还原`() {
        assertEquals("https://x.com/a?b=1&c=2", MiniJson.unescape("https:\\/\\/x.com\\/a?b=1&amp;c=2"))
        assertEquals("a\"b", MiniJson.unescape("a\\\"b"))
        assertEquals("a\nb", MiniJson.unescape("a\\nb"))
    }

    @Test fun `unicode 转义还原`() {
        assertEquals("A/B", MiniJson.unescape("A\\u002FB"))
        assertEquals("\"", MiniJson.unescape("\\u0022"))
    }

    @Test fun `从整个 JSON 发现 URL`() {
        val json = """{"u":"https://x.com/a.jpg","n":5,"other":"not-a-url"}"""
        assertEquals(listOf("https://x.com/a.jpg"), MiniJson.allUrls(json))
    }
}
