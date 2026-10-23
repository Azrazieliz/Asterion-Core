package com.ailm.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationPagingPolicyTest {
    @Test
    fun `keyset handles duplicate import orders and strict image id tie breaks`() {
        assertTrue(AutomationPagingPolicy.isAfter(100L, 21, 100L, 20))
        assertFalse(AutomationPagingPolicy.isAfter(100L, 20, 100L, 20))
        assertFalse(AutomationPagingPolicy.isAfter(100L, 19, 100L, 20))
        assertTrue(AutomationPagingPolicy.isAfter(101L, 1, 100L, 20))
        assertFalse(AutomationPagingPolicy.isAfter(99L, Int.MAX_VALUE, 100L, 20))
    }

    @Test
    fun `300001 keys stream through bounded pages without duplicates or offsets`() {
        val total = 300_001
        val pageSize = AutomationPagingPolicy.PAGE_SIZE
        var cursorOrder = Long.MIN_VALUE
        var cursorId = 0
        var visited = 0
        var pages = 0
        while (visited < total) {
            val remaining = total - visited
            val pageCount = minOf(pageSize, remaining)
            for (step in 0 until pageCount) {
                val nextId = visited + step + 1
                val nextOrder = nextId.toLong() / 3L
                assertTrue(AutomationPagingPolicy.isAfter(nextOrder, nextId, cursorOrder, cursorId))
                cursorOrder = nextOrder
                cursorId = nextId
            }
            visited += pageCount
            pages++
        }
        assertEquals(total, visited)
        assertEquals(total, cursorId)
        assertTrue(pages > 4_000)
        assertEquals(64, pageSize)
    }
}
