package com.motionsaver.core

import org.junit.Assert.*
import org.junit.Test

class TitleSanitizerTest {

    @Test fun `保留中文与英文数字`() {
        assertEquals("夏日海边 vlog 2026", TitleSanitizer.sanitize("夏日海边 vlog 2026"))
    }

    @Test fun `去除 emoji 与装饰符号`() {
        assertEquals("好喜欢这种氛围", TitleSanitizer.sanitize("好喜欢这种氛围✨🌊💙"))
    }

    @Test fun `去除文件系统非法字符`() {
        assertEquals("A B C D E F G H I J", TitleSanitizer.sanitize("A/B\\C:D*E?F\"G<H>I|J"))
        // 断言结果里不含任何文件系统非法字符
        assertTrue(TitleSanitizer.sanitize("A/B\\C:D*E?F\"G<H>I|J")
            .none { it in setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|') })
    }

    @Test fun `折叠连续空白并去首尾`() {
        assertEquals("山间的风", TitleSanitizer.sanitize("  山间\t\t的\n\n 风  "))
    }

    @Test fun `超长按 code point 截断且不切坏代理对`() {
        val raw = "景".repeat(200)
        val out = TitleSanitizer.sanitize(raw, maxLength = 20)
        assertEquals(20, out.codePointCount(0, out.length))
        assertTrue(out.all { it == '景' })
        // 尾部 emoji 不应产生孤立代理项
        val withEmoji = "a".repeat(79) + "😀"
        val r = TitleSanitizer.sanitize(withEmoji, maxLength = 80)
        assertTrue(r.none { Character.isSurrogate(it) })
    }

    @Test fun `空标题回退`() {
        assertEquals("untitled", TitleSanitizer.sanitize(null))
        assertEquals("untitled", TitleSanitizer.sanitize("   "))
        assertEquals("untitled", TitleSanitizer.sanitize("✨💙"))
    }

    @Test fun `去掉结尾的点`() {
        assertEquals("风景", TitleSanitizer.sanitize("风景..."))
    }

    @Test fun `保留常见中文标点`() {
        assertEquals("好喜欢，这种氛围！", TitleSanitizer.sanitize("好喜欢，这种氛围！"))
    }
}

class FileNameAllocatorTest {

    @Test fun `标题直接作为文件名`() {
        assertEquals("好喜欢这种氛围.mp4", FileNameAllocator.allocate("好喜欢这种氛围✨", "mp4", emptySet()))
    }

    @Test fun `重名自动加序号`() {
        val existing = setOf("风景.mp4", "风景_2.mp4")
        assertEquals("风景_3.mp4", FileNameAllocator.allocate("风景", ".mp4", existing))
    }

    @Test fun `带点扩展名归一`() {
        assertEquals("风景.jpg", FileNameAllocator.allocate("风景", "", emptySet()))
    }
}
