package com.motionsaver.core

import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NameTemplateTest {

    private fun ctx(
        title: String = "夏日海边",
        author: String = "陌沫",
        index: Int = 0
    ) = NameContext(
        title = title,
        author = author,
        platform = "抖音",
        itemId = "7345678901234567890",
        sourceUrl = "https://v.douyin.com/dBhPfcEmyLc/",
        typeLabel = "图文",
        topics = "旅行 海边",
        timeMillis = NameTemplate.SAMPLE_TIME,
        index = index
    )

    private val ymd: String =
        SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date(NameTemplate.SAMPLE_TIME))

    @Test
    fun `默认模板包含标题与6位短码`() {
        val s = NameTemplate.format(NameTemplate.default(), "_", ctx())
        assertTrue(s.contains("夏日海边"))
        assertTrue(Regex("[0-9A-F]{6}$").containsMatchIn(s))
        assertEquals(
            "夏日海边" + NameTemplate.DEFAULT_SEPARATOR +
                FileNameAllocator.shortCode("7345678901234567890", ""),
            s
        )
    }

    @Test
    fun `自定义顺序与分隔符生效`() {
        val tokens = NameTemplate.parse("date|title|index")
        val s = NameTemplate.format(tokens, "_", ctx(index = 3))
        assertEquals("${ymd}_夏日海边_03", s)
    }

    @Test
    fun `空字段自动跳过不产生双分隔符`() {
        val tokens = NameTemplate.parse("title|author|short_code")
        val s = NameTemplate.format(tokens, "_", ctx(author = ""))
        assertFalse("不该出现连续分隔符", s.contains("__"))
        assertTrue(s.startsWith("夏日海边_"))
        assertFalse(s.contains("陌沫"))
    }

    @Test
    fun `序号在非图集资源上省略`() {
        val tokens = NameTemplate.parse("title|index")
        assertEquals("夏日海边", NameTemplate.format(tokens, "_", ctx(index = 0)))
        assertEquals("夏日海边_01", NameTemplate.format(tokens, "_", ctx(index = 1)))
    }

    @Test
    fun `空格分隔不会被清洗折叠`() {
        val s = NameTemplate.format(NameTemplate.parse("date|title"), " ", ctx())
        assertEquals("${ymd} 夏日海边", s)
    }

    @Test
    fun `无分隔符直接拼接`() {
        val s = NameTemplate.format(NameTemplate.parse("title|date"), "", ctx())
        assertEquals("夏日海边$ymd", s)
    }

    @Test
    fun `日期时间为8位与6位数字`() {
        val s = NameTemplate.format(NameTemplate.parse("datetime"), "_", ctx())
        assertTrue(Regex("^\\d{8}_\\d{6}$").matches(s))
    }

    @Test
    fun `未知token被忽略且回落默认模板`() {
        val tokens = NameTemplate.parse("bogus|another")
        assertEquals(NameTemplate.default(), tokens)
        assertTrue(NameTemplate.format(tokens, "_", ctx()).contains("夏日海边"))
    }

    @Test
    fun `短码稳定且不同作品不同`() {
        val a = FileNameAllocator.shortCode("id-1", "")
        val b = FileNameAllocator.shortCode("id-2", "")
        assertEquals(a, FileNameAllocator.shortCode("id-1", ""))
        assertNotEquals(a, b)
        assertEquals(6, a.length)
    }

    @Test
    fun `编码解码往返`() {
        val tokens = listOf(NameToken.DATE, NameToken.AUTHOR, NameToken.TITLE, NameToken.SHORT_CODE)
        assertEquals(tokens, NameTemplate.parse(NameTemplate.encode(tokens)))
    }

    @Test
    fun `预览可用且图集序号不同`() {
        val tokens = NameTemplate.parse("date|title|index")
        val p0 = NameTemplate.preview(tokens, "_", 0)
        val p1 = NameTemplate.preview(tokens, "_", 1)
        assertNotEquals(p0, p1)
        assertTrue(p1.endsWith("_01"))
    }

    @Test
    fun `非法分隔符回落为下划线`() {
        val s = NameTemplate.format(NameTemplate.parse("date|title"), "/", ctx())
        assertEquals("${ymd}_夏日海边", s)
    }

    @Test
    fun `结果不含文件系统非法字符`() {
        val s = NameTemplate.format(
            NameToken.entries.toList(), "_",
            ctx(title = "A/B:C*D?E<F>G|H")
        )
        assertFalse(setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|').any { s.contains(it) })
    }

    @Test
    fun `作者可从分享口令模板提取`() {
        assertTrue(
            WorkTitle.authorOf("4.61 复制打开抖音，看看【陌沫つ゛的图文作品】# 阿哈的游戏")
                .startsWith("陌沫")
        )
        assertEquals("", WorkTitle.authorOf("https://v.douyin.com/abc/"))
    }
}
