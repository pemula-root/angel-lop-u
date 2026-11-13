package com.example

import com.example.lyrics.LyricsParser
import org.junit.Assert.assertEquals
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun lyricsParser_and_binarySearch_isCorrect() {
        val lrc = """
            [00:00.00]First line
            [00:10.00]Second line
            [00:20.50]Third line
        """.trimIndent()

        val parsed = LyricsParser.parseLrc(lrc)
        assertEquals(3, parsed.size)
        assertEquals("First line", parsed[0].text)
        assertEquals(0L, parsed[0].timeMs)
        assertEquals("Second line", parsed[1].text)
        assertEquals(10000L, parsed[1].timeMs)
        assertEquals(20500L, parsed[2].timeMs)

        // Test binary search index calculation
        val idx0 = LyricsParser.findActiveLineIndex(parsed, 5000L)
        assertEquals(0, idx0)

        val idx1 = LyricsParser.findActiveLineIndex(parsed, 12000L)
        assertEquals(1, idx1)

        val idx2 = LyricsParser.findActiveLineIndex(parsed, 35000L)
        assertEquals(2, idx2)
    }
}
