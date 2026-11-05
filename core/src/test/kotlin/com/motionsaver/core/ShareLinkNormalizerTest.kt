package com.motionsaver.core

import org.junit.Assert.*
import org.junit.Test

class ShareLinkNormalizerTest {

    @Test fun `清洗口令尾部中文标点`() {
        assertEquals(
            "https://v.douyin.com/abc/",
            ShareLinkNormalizer.urlOf("7.86 复制打开抖音 https://v.douyin.com/abc/。 看看")
        )
    }

    @Test fun `补齐协议`() {
        assertEquals("https://x.com/a", ShareLinkNormalizer.normalize("//x.com/a"))
        assertEquals("https://x.com/a", ShareLinkNormalizer.normalize("x.com/a"))
        assertEquals("http://x.com/a", ShareLinkNormalizer.normalize("http://x.com/a"))
    }

    @Test fun `短链识别`() {
        assertTrue(ShareLinkNormalizer.isShortLink("https://v.douyin.com/iABC/"))
        assertTrue(ShareLinkNormalizer.isShortLink("https://xhslink.com/a/abc"))
        assertFalse(ShareLinkNormalizer.isShortLink("https://www.douyin.com/video/123"))
    }

    @Test fun `提取作品 ID`() {
        assertEquals("7123456789012345678", ShareLinkNormalizer.itemIdOf("https://www.douyin.com/video/7123456789012345678"))
        assertEquals("abcdef123456", ShareLinkNormalizer.itemIdOf("https://www.xiaohongshu.com/explore/abcdef123456"))
    }
}
