package com.motionsaver.core

import org.junit.Assert.*
import org.junit.Test

class FileNameAllocatorSeriesTest {

    @Test fun `图集按序号命名`() {
        assertEquals("风景_01.jpg", FileNameAllocator.allocateSeries("风景", "jpg", 0, 3, emptySet()))
        assertEquals("风景_03.jpg", FileNameAllocator.allocateSeries("风景", "jpg", 2, 3, emptySet()))
    }

    @Test fun `单张不带序号`() {
        assertEquals("风景.jpg", FileNameAllocator.allocateSeries("风景", "jpg", 0, 1, emptySet()))
    }

    @Test fun `序号命名也处理重名`() {
        val existing = setOf("风景_01.jpg")
        assertEquals("风景_01_2.jpg", FileNameAllocator.allocateSeries("风景", "jpg", 0, 3, existing))
    }

    @Test fun `附属文件命名`() {
        assertEquals("风景-正文.txt", FileNameAllocator.allocateAuxiliary("风景", "正文", "txt", emptySet()))
    }
}
