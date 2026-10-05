package com.netspeedandroid.app

import org.junit.Assert.assertEquals
import org.junit.Test

class TestHistoryTest {
    @Test
    fun roundTripAndIgnoreMalformedRecords() {
        val entry = TestHistoryEntry(123L, "Wi-Fi", 18.0, 126.4, 32.8)
        val encoded = TestHistory.encode(listOf(entry)) + "\nbroken"

        assertEquals(listOf(entry), TestHistory.decode(encoded))
    }

    @Test
    fun newestEntryIsFirstAndHistoryIsCapped() {
        val existing = (1L..10L).map { TestHistoryEntry(it, "Wi-Fi", 1.0, 2.0, 3.0) }
        val newest = TestHistoryEntry(11L, "Mobile Data", 4.0, 5.0, 6.0)
        val result = TestHistory.add(existing, newest)

        assertEquals(TestHistory.MAX_ENTRIES, result.size)
        assertEquals(newest, result.first())
        assertEquals(9L, result.last().timestampMillis)
    }
}
