package com.motionsaver.core

import org.junit.Assert.*
import org.junit.Test

/**
 * 本轮两个真实反馈的回归：
 *  1) 单张静态图 + 原声的作品「其实并没有动图」，软件不该识别成有动图、
 *     更不该把标题标成「动态图」；
 *  2) 命名要用抖音文案 / 话题名，且不同作品文件名不能重叠堆叠。
 */
class WorkTitleTest {

    private val momoShare =
        "4.61 复制打开抖音，看看【陌沫つ゛的图文作品】# 阿哈的游戏 # 崩坏星穹铁道 # 流萤 " +
            "https://v.douyin.com/dBhPfcEmyLc/ L@W.zg 12/17 :0pm VYm:/"

    // ---------------------------------------------------------------- 话题命名

    @Test
    fun `纯话题口令提取出语义化标题`() {
        val t = WorkTitle.fromShare(momoShare)
        assertTrue("应含话题词，实际: $t", t.contains("崩坏星穹铁道"))
        assertTrue("应含话题词，实际: $t", t.contains("流萤"))
        assertFalse("不应含链接", t.contains("http"))
        assertFalse("不应含口令模板词", t.contains("复制打开"))
        assertFalse("不应含话题符号", t.contains("#"))
    }

    @Test
    fun `话题提取丢弃尾部随机码与模板括号`() {
        val t = WorkTitle.topics(momoShare)
        assertFalse("不含 \u7684图文作品 模板", t.contains("图文作品"))
        assertFalse("不含时间随机码", t.contains("12/17") || t.contains("VYm"))
        assertFalse("不含 @乱码", t.contains("L@W"))
    }

    @Test
    fun `平台通用词被识别为 generic`() {
        assertTrue(WorkTitle.isGeneric("抖音作品"))
        assertTrue(WorkTitle.isGeneric("抖音 - 记录美好生活"))
        assertTrue(WorkTitle.isGeneric(""))
        assertFalse("真实文案不是通用词", WorkTitle.isGeneric("周末露营记录"))
        assertFalse("话题名不是通用词", WorkTitle.isGeneric("崩坏星穹铁道 流萤"))
    }

    @Test
    fun `正常文案口令用正文而非空`() {
        val t = WorkTitle.fromShare("9.66 复制打开抖音，看看【小林的作品】周末去露营啦 " +
            "https://v.douyin.com/abc123/ 8.43 xyz :0pm")
        assertTrue("应保留正文，实际: $t", t.contains("露营"))
    }

    // ---------------------------------------------------------------- 短码去重

    @Test
    fun `短码稳定且不同作品不同`() {
        val a = FileNameAllocator.scopedTitle("崩坏星穹铁道 流萤", "7705030121148814498", "")
        val b = FileNameAllocator.scopedTitle("崩坏星穹铁道 流萤", "7705030121148814498", "")
        val c = FileNameAllocator.scopedTitle("崩坏星穹铁道 流萤", "7705999999999999999", "")
        assertEquals("同一作品短码必须恒定", a, b)
        assertNotEquals("不同作品短码必须区分，避免同名堆叠", a, c)
        assertTrue("仍保留语义化主体", a.startsWith("崩坏星穹铁道"))
        assertTrue("短码为 6 位十六进制", Regex("""-[0-9A-F]{6}$""").containsMatchIn(a))
    }

    @Test
    fun `无作品ID时退化为链接短码且不抛异常`() {
        assertEquals("风景", FileNameAllocator.scopedTitle("风景", "", ""))
        val a = FileNameAllocator.scopedTitle("风景", "", "https://v.douyin.com/abc/")
        assertTrue(a.startsWith("风景-"))
    }

    @Test
    fun `带短码的标题落盘名合法且带序号`() {
        val scoped = FileNameAllocator.scopedTitle("崩坏星穹铁道 流萤", "7705030121148814498", "")
        val n1 = FileNameAllocator.allocateSeries(scoped, "jpg", 0, 2, emptySet())
        val n2 = FileNameAllocator.allocateSeries(scoped, "jpg", 1, 2, setOf(n1))
        assertTrue(n1.endsWith("_01.jpg"))
        assertTrue(n2.endsWith("_02.jpg"))
        assertNotEquals(n1, n2)
        assertFalse("文件名不含非法字符", n1.any { it in "\\/:*?\"<>|#" })
    }

    // ---------------------------------------------------------------- 多条口令切分

    @Test
    fun `单条口令原样保留完整文案`() {
        val shares = ShareText.splitShares(momoShare)
        assertEquals(1, shares.size)
        assertTrue("应保留话题文案", shares[0].contains("崩坏星穹铁道"))
    }

    @Test
    fun `多条口令按链接切分且每段带自己的文案`() {
        val text = "看看【甲的图文作品】# 话题A https://v.douyin.com/aaa/ 9.9 " +
            "看看【乙的图文作品】# 话题B https://v.douyin.com/bbb/ :0pm"
        val shares = ShareText.splitShares(text)
        assertEquals(2, shares.size)
        assertTrue("第一段应带话题A", shares[0].contains("话题A"))
        assertTrue("第二段应带话题B", shares[1].contains("话题B"))
        assertEquals("每段只含一个链接", 1, ShareText.extractUrls(shares[0]).size)
        assertEquals("每段只含一个链接", 1, ShareText.extractUrls(shares[1]).size)
    }
}
